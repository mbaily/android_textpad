<script>
  import { onMount } from "svelte";
  import { SvelteMap } from "svelte/reactivity";
  import { EditorState, Compartment, EditorSelection, ChangeSet, StateField } from "@codemirror/state";
  import { EditorView, keymap, lineNumbers, highlightActiveLineGutter, drawSelection, rectangularSelection, Decoration } from "@codemirror/view";
  import { defaultKeymap, history, historyKeymap, undo, redo, undoDepth, redoDepth, indentMore, indentLess, moveLineUp, moveLineDown, indentWithTab } from "@codemirror/commands";
  import { search, SearchQuery, setSearchQuery, openSearchPanel, closeSearchPanel } from "@codemirror/search";
  import { filterTasks } from "./taskFilter.js";

  const prefix = document.body.dataset.prefix || "";
  const apiBase = `${prefix}/editor/api`;
  const csrf = document.querySelector('meta[name="csrf-token"]')?.content || "";
  const nonce = document.querySelector('meta[name="csp-nonce"]')?.content || "";

  const wrapCompartment = new Compartment();
  const guttersCompartment = new Compartment();
  const readOnlyCompartment = new Compartment();
  const tasksFilterCompartment = new Compartment();
  const fonts = {
    mono: 'ui-monospace,"SFMono-Regular",Consolas,monospace',
    sans: "system-ui,sans-serif",
    serif: "Georgia,serif",
  };

  let initialPrefs = { wrap: true, lines: true, fontSize: 15, font: "mono", dividerDashes: 40 };
  try {
    initialPrefs = { ...initialPrefs, ...JSON.parse(localStorage.getItem("textpad.editor.preferences") || "{}") };
  } catch {}

  let initialCollapsed = typeof window !== "undefined" && window.matchMedia?.("(max-width: 700px)").matches;
  try {
    const savedCollapsed = localStorage.getItem("textpad.editor.sidebarCollapsed");
    if (savedCollapsed !== null) initialCollapsed = savedCollapsed === "true";
  } catch {}

  let clientId = "web-client";
  try {
    const storedClient = localStorage.getItem("textpad.editor.clientId");
    if (storedClient && /^[A-Za-z0-9_-]{1,128}$/.test(storedClient)) {
      clientId = storedClient;
    } else {
      clientId = `web-${crypto.randomUUID()}`;
      localStorage.setItem("textpad.editor.clientId", clientId);
    }
  } catch {}

  const documents = new SvelteMap();
  let files = $state([]);
  let fileFilter = $state("");
  let active = $state(null);
  let sidebarCollapsed = $state(initialCollapsed);
  let preferences = $state(initialPrefs);

  let apiOnline = $state(false);
  let eventsReady = $state(false);

  let noticeState = $state({ visible: false, text: "", error: false, expired: false });

  let historyDocument = $state(null);
  let historyRevisions = $state([]);
  let selectedRevision = $state(null);
  let previewText = $state("");
  let fullscreenRevision = $state(false);

  let editorHost = $state(null);
  let dialogEl = $state(null);
  let modalState = $state(null);

  let findOpen = $state(false);
  let findAllFiles = $state(false);
  let findQuery = $state("");
  let findMatchCase = $state(false);
  let findResultText = $state("");
  let findInputEl = $state(null);

  let initialErrands = { hashtags: [], updated_at: "", dirty: false };
  try {
    const savedErrands = JSON.parse(localStorage.getItem("textpad.editor.errands") || "{}");
    if (Array.isArray(savedErrands.hashtags)) {
      initialErrands = {
        hashtags: savedErrands.hashtags,
        updated_at: savedErrands.updated_at || "",
        dirty: Boolean(savedErrands.dirty),
      };
    }
  } catch {}

  let hashtagsPanelOpen = $state(false);
  let hashtagsTab = $state("search");
  let hashtagQuery = $state("");
  let hashtagMatchMode = $state("lines");
  let hashtagHideCompleted = $state(false);
  let errandsTags = $state(initialErrands.hashtags);
  let errandsUpdatedAt = $state(initialErrands.updated_at);
  let errandsDirty = $state(initialErrands.dirty);
  let editingErrands = $state(false);
  let errandsInput = $state("");
  let discoveredHashtags = $state([]);
  let hashtagResults = $state([]);
  let hashtagSearching = $state(false);

  let cursorLine = $state(1);
  let cursorCol = $state(1);
  let lineCount = $state(1);
  let canUndo = $state(false);
  let canRedo = $state(false);
  let readOnly = $state(false);
  let pendingSyncCount = $state(0);

  let view = null;
  let viewDocument = null;
  let opening = null;
  let polling = false;
  let refreshAgain = false;
  let events = null;
  let reconnectTimer = null;
  let reconnectAttempts = 0;
  let dbPromise = null;
  let syncChannel = null;

  const IDB_NAME = "textpad.editor.offline";
  const IDB_VERSION = 1;

  function openOfflineDb() {
    if (typeof indexedDB === "undefined") return Promise.resolve(null);
    if (!dbPromise) {
      dbPromise = new Promise((resolve) => {
        try {
          const req = indexedDB.open(IDB_NAME, IDB_VERSION);
          req.onupgradeneeded = () => {
            const db = req.result;
            if (!db.objectStoreNames.contains("files")) db.createObjectStore("files", { keyPath: "id" });
            if (!db.objectStoreNames.contains("documents")) db.createObjectStore("documents", { keyPath: "id" });
            if (!db.objectStoreNames.contains("outbox")) db.createObjectStore("outbox", { keyPath: "id" });
          };
          req.onsuccess = () => resolve(req.result);
          req.onerror = () => resolve(null);
          req.onblocked = () => resolve(null);
        } catch {
          resolve(null);
        }
      });
    }
    return dbPromise;
  }

  function idbPlain(obj) {
    return JSON.parse(JSON.stringify(obj));
  }

  async function idbGetAll(storeName) {
    const db = await openOfflineDb();
    if (!db) return [];
    return new Promise((resolve) => {
      try {
        const tx = db.transaction(storeName, "readonly");
        const req = tx.objectStore(storeName).getAll();
        req.onsuccess = () => resolve(req.result || []);
        req.onerror = () => resolve([]);
      } catch {
        resolve([]);
      }
    });
  }

  async function idbGet(storeName, key) {
    const db = await openOfflineDb();
    if (!db) return null;
    return new Promise((resolve) => {
      try {
        const tx = db.transaction(storeName, "readonly");
        const req = tx.objectStore(storeName).get(key);
        req.onsuccess = () => resolve(req.result ?? null);
        req.onerror = () => resolve(null);
      } catch {
        resolve(null);
      }
    });
  }

  async function idbStoreDocument(docMeta) {
    const db = await openOfflineDb();
    if (!db || !docMeta?.id) return;
    const plain = idbPlain(docMeta);
    await new Promise((resolve) => {
      try {
        const tx = db.transaction(["documents", "files"], "readwrite");
        tx.objectStore("documents").put(plain);
        const { text: _text, ...fileSummary } = plain;
        tx.objectStore("files").put(fileSummary);
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
  }

  async function idbSyncFileList(remoteFiles) {
    const db = await openOfflineDb();
    if (!db) return;
    const plainFiles = idbPlain(remoteFiles);
    await new Promise((resolve) => {
      try {
        const tx = db.transaction(["files", "outbox"], "readwrite");
        const filesStore = tx.objectStore("files");
        const outboxStore = tx.objectStore("outbox");
        const outboxReq = outboxStore.getAll();
        outboxReq.onsuccess = () => {
          const outboxMap = new Map((outboxReq.result || []).map((item) => [item.id, item]));
          filesStore.clear();
          for (const f of plainFiles) {
            const pending = outboxMap.get(f.id);
            filesStore.put(pending ? pending.fileSummary : f);
            outboxMap.delete(f.id);
          }
          for (const pending of outboxMap.values()) {
            filesStore.put(pending.fileSummary);
          }
        };
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
  }

  async function idbSaveOffline(id, body, docMeta) {
    const db = await openOfflineDb();
    if (!db) return;
    const plainDoc = idbPlain(docMeta);
    const { text: _text, ...fileSummary } = plainDoc;
    const outboxEntry = idbPlain({ id, body, docMeta: plainDoc, fileSummary });
    await new Promise((resolve) => {
      try {
        const tx = db.transaction(["outbox", "documents", "files"], "readwrite");
        tx.objectStore("outbox").put(outboxEntry);
        tx.objectStore("documents").put(plainDoc);
        tx.objectStore("files").put(fileSummary);
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
    const outbox = await idbGetAll("outbox");
    pendingSyncCount = outbox.length;
  }

  async function idbRemoveOutbox(id) {
    const db = await openOfflineDb();
    if (!db) return;
    await new Promise((resolve) => {
      try {
        const tx = db.transaction("outbox", "readwrite");
        tx.objectStore("outbox").delete(id);
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
    const outbox = await idbGetAll("outbox");
    pendingSyncCount = outbox.length;
  }

  async function idbClearAll() {
    const db = await openOfflineDb();
    if (!db) return;
    await new Promise((resolve) => {
      try {
        const tx = db.transaction(["files", "documents", "outbox"], "readwrite");
        tx.objectStore("files").clear();
        tx.objectStore("documents").clear();
        tx.objectStore("outbox").clear();
        tx.oncomplete = () => resolve();
        tx.onerror = () => resolve();
      } catch {
        resolve();
      }
    });
    pendingSyncCount = 0;
  }

  const activeDoc = $derived(active ? documents.get(active) ?? null : null);
  const filteredFiles = $derived.by(() => {
    const q = fileFilter.toLocaleLowerCase();
    return files.filter((file) => file.name.toLocaleLowerCase().includes(q));
  });
  const connectionLabel = $derived(
    apiOnline
      ? eventsReady
        ? "Connected · live updates"
        : "Connected to server"
      : pendingSyncCount > 0
        ? `Offline · ${pendingSyncCount} pending`
        : "Offline · cached mode"
  );
  const cursorStatus = $derived(
    activeDoc ? `Ln ${cursorLine}, Col ${cursorCol}  ·  ${lineCount.toLocaleString()} lines` : "No document open"
  );
  const encodingLabel = $derived(
    activeDoc ? `${encodingName(activeDoc.encoding)}${activeDoc.bom ? " BOM" : ""} · ${activeDoc.line_endings}` : "UTF-8"
  );
  const revisionStatus = $derived(
    !activeDoc ? "" : activeDoc.revision ? `Revision ${activeDoc.revision}` : "New file"
  );
  const saveStatus = $derived(
    !activeDoc
      ? "Ready"
      : activeDoc.saving
        ? "Saving…"
        : readOnly
          ? `Read only · ${activeDoc.dirty ? "Unsaved changes" : activeDoc.pendingSync ? "Saved offline" : "Saved"}`
          : activeDoc.dirty
            ? "Unsaved changes"
            : activeDoc.pendingSync
              ? "Saved offline"
              : "Saved"
  );

  $effect(() => {
    if (!activeDoc) {
      document.title = "Textpad · Synced files";
    } else {
      document.title = `${activeDoc.dirty ? "● " : ""}${activeDoc.name} · Textpad`;
    }
  });

  function bytes(size) {
    return size >= 1024 * 1024
      ? `${(size / 1024 / 1024).toFixed(1)} MiB`
      : size >= 1024
        ? `${(size / 1024).toFixed(1)} KiB`
        : `${size} B`;
  }

  function date(value) {
    return new Date(value).toLocaleString(undefined, {
      month: "short",
      day: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    });
  }

  function encodingName(value) {
    return { "utf-8": "UTF-8", "utf-16-le": "UTF-16 LE", "utf-16-be": "UTF-16 BE", ascii: "ASCII" }[value] || value;
  }

  function sourceLabel(value) {
    return { web: "Web", android: "Android", ios: "iOS", merge: "Auto-merged", restore: "Restored", api: "Sync" }[value] || value;
  }

  function notice(text, error = false, expired = false) {
    noticeState = { visible: true, text, error, expired };
  }

  function failure(error) {
    notice(error.message, true, error.expired);
  }

  async function api(path, options = {}) {
    let response;
    try {
      response = await fetch(apiBase + path, {
        ...options,
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-Token": csrf,
          "X-Textpad-Client": clientId,
          ...options.headers,
        },
      });
    } catch {
      apiOnline = false;
      const error = new Error("Connection lost. Your open edits are still here. Reconnect and retry.");
      error.offline = true;
      throw error;
    }
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error(`The server returned HTTP ${response.status}. Your edits are still open.`);
    }
    if (!response.ok) {
      const error = new Error(data.error || `Server returned HTTP ${response.status}.`);
      error.expired = response.status === 401;
      throw error;
    }
    apiOnline = true;
    return data;
  }

  function closeEvents() {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
    eventsReady = false;
    const before = events;
    events = null;
    if (before) before.close();
  }

  function connectEvents() {
    if (document.hidden || events || reconnectTimer) return;
    const address = new URL(`${apiBase}/events`, window.location.href);
    address.protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(address);
    events = socket;
    socket.onmessage = (event) => {
      if (events !== socket) return;
      try {
        const message = JSON.parse(event.data);
        if (message.type === "ready" || message.type === "changed") {
          eventsReady = true;
          reconnectAttempts = 0;
          refreshFiles().catch(failure);
        }
      } catch {}
    };
    socket.onclose = () => {
      if (events !== socket) return;
      events = null;
      eventsReady = false;
      if (!document.hidden) {
        reconnectTimer = setTimeout(
          () => {
            reconnectTimer = null;
            connectEvents();
          },
          Math.min(60000, 1000 * 2 ** Math.min(reconnectAttempts++, 6))
        );
      }
    };
    socket.onerror = () => socket.close();
  }

  function syncEditorMetrics() {
    if (!view || !activeDoc) {
      canUndo = false;
      canRedo = false;
      return;
    }
    const cursor = view.state.selection.main.head;
    const line = view.state.doc.lineAt(cursor);
    cursorLine = line.number;
    cursorCol = cursor - line.from + 1;
    lineCount = view.state.doc.lines;
    canUndo = undoDepth(view.state) > 0;
    canRedo = redoDepth(view.state) > 0;
  }

  function openModal(config) {
    if (dialogEl?.open && modalState?.resolve) {
      dialogEl.close();
      modalState.resolve(null);
    }
    return new Promise((resolve) => {
      modalState = { ...config, resolve };
      queueMicrotask(() => {
        if (dialogEl && !dialogEl.open) dialogEl.showModal();
      });
    });
  }

  function finishModal(value) {
    const current = modalState;
    if (dialogEl?.open) dialogEl.close();
    modalState = null;
    current?.resolve(value);
  }

  function handleModalSubmit(event) {
    event.preventDefault();
    const primary = modalState?.actions?.find((a) => a.primary);
    if (primary) finishModal(primary.value);
  }

  async function confirm(title, message, label = "Continue") {
    return (
      (await openModal({
        type: "confirm",
        title,
        message,
        actions: [
          { label: "Cancel", value: false },
          { label, value: true, primary: true },
        ],
      })) === true
    );
  }

  function isDocDirty(doc, stateDoc = doc.state?.doc) {
    if (!doc || !stateDoc) return false;
    return (
      (doc.revision === null && !doc.pendingSync) ||
      !stateDoc.eq(doc.savedDoc) ||
      doc.name !== doc.savedName ||
      doc.encoding !== doc.savedEncoding ||
      doc.bom !== doc.savedBom
    );
  }

  function getTasksFilterExtension(doc, stateDoc = doc?.state?.doc) {
    if (!doc?.tasksOnly) return [];
    const text = stateDoc ? stateDoc.toString() : (doc.savedText || "");
    const filterRes = doc.taskFilterResult || filterTasks(text, 4);
    doc.taskFilterResult = filterRes;
    const n = stateDoc ? stateDoc.lines : 0;
    if (!stateDoc || n === 0) return [];

    const hiddenRanges = [];
    let hideStart = -1;

    for (let i = 0; i < n; i++) {
      const visible = filterRes.isVisible[i] === 1;
      if (!visible) {
        if (hideStart === -1) hideStart = i;
      } else {
        if (hideStart !== -1) {
          const from = stateDoc.line(hideStart + 1).from;
          // Keep the next visible line outside the inclusive replacement.
          const to = stateDoc.line(i + 1).from - 1;
          hiddenRanges.push(Decoration.replace({ block: true }).range(from, to));
          hideStart = -1;
        }
      }
    }
    if (hideStart !== -1) {
      const from = stateDoc.line(hideStart + 1).from;
      const to = stateDoc.length;
      hiddenRanges.push(Decoration.replace({ block: true }).range(from, to));
    }

    const hiddenField = StateField.define({
      create() {
        return Decoration.set(hiddenRanges);
      },
      update(decos, tr) {
        return decos.map(tr.changes);
      },
      provide: (f) => EditorView.decorations.from(f),
    });

    const contextField = EditorView.decorations.compute(["doc"], (state) => {
      const builder = [];
      for (let i = 1; i <= state.doc.lines; i++) {
        const line = state.doc.line(i);
        let trimmed = line.text.trimStart();
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
          trimmed = trimmed.slice(2).trimStart();
        }
        const isCompleted = trimmed.startsWith("[X]") || trimmed.startsWith("[x]");
        const isContext = filterRes.isContext[i - 1] === 1;
        if (isCompleted || isContext) {
          builder.push(Decoration.line({ class: "cm-task-context" }).range(line.from));
        }
      }
      return Decoration.set(builder);
    });

    const txFilter = EditorState.transactionFilter.of((tr) => {
      if (!tr.docChanged) return tr;
      let altersLines = false;
      tr.changes.iterChanges((fromA, toA, fromB, toB, inserted) => {
        if (inserted.lines > 1) altersLines = true;
        const oldText = tr.startState.doc.sliceString(fromA, toA);
        if (oldText.includes("\n")) altersLines = true;
      });
      if (altersLines) {
        notice("Lines cannot be added or deleted in this view");
        return [];
      }
      return tr;
    });

    return [hiddenField, contextField, txFilter];
  }

  function makeState(doc, text, selection) {
    return EditorState.create({
      doc: text,
      selection,
      extensions: [
        history(),
        drawSelection(),
        readOnlyCompartment.of(
          readOnly ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : []
        ),
        tasksFilterCompartment.of(getTasksFilterExtension(doc)),
        rectangularSelection(),
        highlightActiveLineGutter(),
        EditorState.tabSize.of(4),
        EditorView.cspNonce.of(nonce),
        EditorView.contentAttributes.of({
          "aria-label": "Document text",
          spellcheck: "false",
          autocorrect: "off",
          autocapitalize: "off",
        }),
        EditorView.theme({ "&": { color: "#e3e8df" } }, { dark: true }),
        wrapCompartment.of(preferences.wrap ? EditorView.lineWrapping : []),
        guttersCompartment.of(preferences.lines ? lineNumbers() : []),
        search({
          createPanel: () => {
            const dom = document.createElement("div");
            dom.hidden = true;
            return { dom };
          },
        }),
        keymap.of([
          {
            key: "Mod-s",
            run: () => {
              save(doc).catch(failure);
              return true;
            },
          },
          {
            key: "Mod-f",
            run: () => {
              openFind(false);
              return true;
            },
          },
          {
            key: "Mod-Shift-f",
            run: () => {
              openFind(true);
              return true;
            },
          },
          {
            key: "Mod-Alt-r",
            run: () => {
              toggleReadOnly();
              return true;
            },
          },
          {
            key: "Mod-Alt-t",
            run: () => {
              toggleTasksFilter();
              return true;
            },
          },
          ...historyKeymap,
          indentWithTab,
          ...defaultKeymap,
        ]),
        EditorView.updateListener.of((update) => {
          if (documents.get(doc.id) !== doc) return;
          doc.state = update.state;
          if (update.docChanged) {
            doc.dirty = isDocDirty(doc, update.state.doc);
          }
          if (active === doc.id) syncEditorMetrics();
        }),
      ],
    });
  }

  function createDocModel(metadata) {
    const doc = $state({
      ...metadata,
      dirty: false,
      saving: false,
      remoteChanged: false,
      pendingSync: Boolean(metadata.pendingSync),
      pending: null,
      savedName: metadata.name,
      savedEncoding: metadata.encoding,
      savedBom: metadata.bom,
      tasksOnly: false,
      taskFilterResult: null,
      state: null,
      savedDoc: null,
      scrollTop: 0,
      scrollLeft: 0,
      scrollSnapshot: null,
    });
    const initialState = makeState(doc, metadata.text);
    doc.state = initialState;
    doc.savedDoc = initialState.doc;
    if (metadata.pendingBody) {
      doc.pending = { doc: initialState.doc, body: metadata.pendingBody };
    }
    return doc;
  }

  function diffBounds(oldText, newText) {
    let start = 0;
    const minLen = Math.min(oldText.length, newText.length);
    while (start < minLen && oldText.charCodeAt(start) === newText.charCodeAt(start)) {
      start++;
    }
    let oldEnd = oldText.length;
    let newEnd = newText.length;
    while (oldEnd > start && newEnd > start && oldText.charCodeAt(oldEnd - 1) === newText.charCodeAt(newEnd - 1)) {
      oldEnd--;
      newEnd--;
    }
    return { start, oldEnd, newEnd };
  }

  function mapDocPos(beforeDoc, afterDoc, bounds, pos) {
    if (pos <= bounds.start) return pos;
    if (pos >= bounds.oldEnd) return pos + (bounds.newEnd - bounds.oldEnd);
    const oldLine = beforeDoc.lineAt(pos);
    const newLine = afterDoc.line(Math.min(oldLine.number, afterDoc.lines));
    return Math.min(newLine.from + (pos - oldLine.from), newLine.to);
  }

  function mapScrollSnapshot(snapshot, beforeDoc, afterDoc, bounds) {
    if (!snapshot) return null;
    const oldPos = snapshot.value?.range?.head;
    if (typeof oldPos !== "number") return snapshot;
    const newPos = mapDocPos(beforeDoc, afterDoc, bounds, oldPos);
    const delta = newPos - oldPos;
    if (delta === 0) return snapshot;
    const change =
      delta > 0
        ? ChangeSet.of({ from: 0, to: 0, insert: " ".repeat(delta) }, beforeDoc.length)
        : ChangeSet.of({ from: 0, to: -delta }, beforeDoc.length);
    return snapshot.map(change);
  }

  function captureViewState(doc = viewDocument) {
    if (!doc || !view || viewDocument !== doc) return;
    view.measure();
    doc.state = view.state;
    doc.scrollTop = view.scrollDOM.scrollTop;
    doc.scrollLeft = view.scrollDOM.scrollLeft;
    doc.scrollSnapshot = view.scrollSnapshot();
  }

  function activate(id) {
    captureViewState();
    active = id;
    const doc = id ? documents.get(id) ?? null : null;
    viewDocument = doc;
    if (doc && view) {
      view.setState(doc.state);
      if (findOpen) syncSearchHighlight();
      else view.focus();
      if (doc.scrollSnapshot) {
        view.dispatch({ effects: doc.scrollSnapshot });
        view.measure();
      } else {
        view.scrollDOM.scrollTop = doc.scrollTop || 0;
        view.scrollDOM.scrollLeft = doc.scrollLeft || 0;
      }
    } else if (!doc && findOpen) {
      findOpen = false;
      findResultText = "";
    }
    syncEditorMetrics();
    if (historyDocument && historyDocument !== id) closeHistory();
  }

  function closeSidebarOnMobile() {
    if (typeof window !== "undefined" && window.matchMedia?.("(max-width: 700px)").matches && !sidebarCollapsed) {
      sidebarCollapsed = true;
      view?.requestMeasure();
    }
  }

  async function fetchDocument(id) {
    const cached = await idbGet("documents", id);
    const headers = {};
    if (cached?.revision && cached?.sha256 && typeof cached?.text === "string") {
      headers["If-None-Match"] = `"${cached.revision}:${cached.sha256}"`;
    }
    let response;
    try {
      response = await fetch(`${apiBase}/documents/${encodeURIComponent(id)}`, {
        credentials: "same-origin",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-Token": csrf,
          "X-Textpad-Client": clientId,
          ...headers,
        },
      });
    } catch {
      apiOnline = false;
      const error = new Error("Connection lost. Your open edits are still here. Reconnect and retry.");
      error.offline = true;
      throw error;
    }
    if (response.status === 304 && cached) {
      apiOnline = true;
      return cached;
    }
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error(`The server returned HTTP ${response.status}. Your edits are still open.`);
    }
    if (!response.ok) {
      const error = new Error(data.error || `Server returned HTTP ${response.status}.`);
      error.expired = response.status === 401;
      throw error;
    }
    apiOnline = true;
    await idbStoreDocument(data);
    return data;
  }

  async function openFile(id) {
    opening = id;
    closeSidebarOnMobile();
    if (documents.has(id)) {
      activate(id);
      return;
    }
    try {
      const pendingOutbox = await idbGet("outbox", id);
      if (pendingOutbox && !apiOnline) {
        const meta = { ...pendingOutbox.docMeta, pendingSync: true, pendingBody: pendingOutbox.body };
        if (!documents.has(id)) documents.set(id, createDocModel(meta));
        if (opening === id) activate(id);
        return;
      }
      const metadata = await fetchDocument(id);
      if (!documents.has(id)) documents.set(id, createDocModel(metadata));
      if (opening === id) activate(id);
      const remote = files.find((file) => file.id === id);
      if (remote && documents.get(id).revision !== remote.revision) refreshFiles().catch(failure);
    } catch (error) {
      if (error.offline) {
        const pendingOutbox = await idbGet("outbox", id);
        const cached = pendingOutbox
          ? { ...pendingOutbox.docMeta, pendingSync: true, pendingBody: pendingOutbox.body }
          : await idbGet("documents", id);
        if (cached) {
          if (!documents.has(id)) documents.set(id, createDocModel(cached));
          if (opening === id) activate(id);
          return;
        }
      }
      failure(error);
    }
  }

  async function newFile() {
    const result = await openModal({
      type: "newFile",
      title: "New server file",
      filename: "Untitled.txt",
      actions: [
        { label: "Cancel", value: false },
        { label: "Create", value: true, primary: true },
      ],
    });
    if (!result) return;
    const name = (result.filename ?? "").trim().normalize("NFC");
    if (!name || /[/\\\x00-\x1f]/.test(name)) {
      notice("Choose a filename without slashes or control characters.", true);
      return;
    }
    opening = null;
    closeSidebarOnMobile();
    const doc = createDocModel({
      id: crypto.randomUUID(),
      name,
      revision: null,
      text: "",
      encoding: "utf-8",
      bom: false,
      line_endings: "LF",
      size: 0,
    });
    doc.dirty = true;
    documents.set(doc.id, doc);
    activate(doc.id);
  }

  async function closeFile(id) {
    const doc = documents.get(id);
    if (!doc) return;
    if (doc.saving) {
      notice("Wait for this file to finish saving.");
      return;
    }
    if (doc.dirty) {
      const action = await openModal({
        type: "confirm",
        title: `Save changes to ${doc.name}?`,
        message: "Closing this tab discards its unsaved edits.",
        actions: [
          { label: "Cancel", value: null },
          { label: "Discard", value: "discard", danger: true },
          { label: "Save", value: "save", primary: true },
        ],
      });
      if (!action) return;
      if (action === "save") {
        try {
          await save(doc);
        } catch (error) {
          failure(error);
          return;
        }
        if (doc.dirty) return;
      }
    }
    documents.delete(id);
    if (active === id) activate(documents.keys().next().value || null);
  }

  function applyCleanDocMetadata(doc, metadata) {
    const before = doc.state.doc;
    captureViewState(doc);
    const selection = doc.state.selection.main;
    const next = createDocModel(metadata);
    const bounds = diffBounds(before.toString(), metadata.text);
    const nextSelection = EditorSelection.range(
      mapDocPos(before, next.state.doc, bounds, selection.anchor),
      mapDocPos(before, next.state.doc, bounds, selection.head)
    );
    const nextState = makeState(next, metadata.text, nextSelection);
    next.state = nextState;
    next.savedDoc = nextState.doc;
    next.scrollTop = doc.scrollTop;
    next.scrollLeft = doc.scrollLeft;
    next.scrollSnapshot = mapScrollSnapshot(doc.scrollSnapshot, before, nextState.doc, bounds);
    documents.set(doc.id, next);
    if (active === doc.id) activate(doc.id);
    return next;
  }

  async function save(doc = activeDoc) {
    if (!doc || doc.saving || !doc.dirty) return;
    const state = doc.state;
    let pending = doc.pending;
    if (
      !pending ||
      !pending.doc.eq(state.doc) ||
      pending.body.name !== doc.name ||
      pending.body.encoding !== doc.encoding ||
      pending.body.bom !== doc.bom
    ) {
      pending = {
        doc: state.doc,
        body: {
          name: doc.name,
          text: state.doc.toString(),
          encoding: doc.encoding,
          bom: doc.bom,
          base_revision: doc.revision,
          request_id: crypto.randomUUID(),
        },
      };
      doc.pending = pending;
    }
    doc.saving = true;
    try {
      const result = await api(`/documents/${doc.id}`, { method: "PUT", body: JSON.stringify(pending.body) });
      const storedText = typeof result.text === "string" ? result.text : pending.body.text;
      doc.revision = result.revision;
      doc.sha256 = result.sha256;
      doc.size = result.size;
      doc.updated_at = result.updated_at;
      doc.line_endings = result.line_endings;
      doc.savedDoc = pending.doc;
      doc.savedName = result.name || pending.body.name;
      doc.name = doc.savedName;
      doc.savedEncoding = result.encoding || pending.body.encoding;
      doc.encoding = doc.savedEncoding;
      doc.savedBom = typeof result.bom === "boolean" ? result.bom : pending.body.bom;
      doc.bom = doc.savedBom;
      doc.pending = null;
      doc.pendingSync = false;
      doc.remoteChanged = false;
      await idbRemoveOutbox(doc.id);
      const fullMetadata = {
        ...result,
        text: storedText,
        bom: doc.savedBom,
        line_endings: doc.line_endings,
      };
      await idbStoreDocument(fullMetadata);
      files = [result, ...files.filter((file) => file.id !== result.id)];
      doc.dirty = isDocDirty(doc);
      if (result.merged_newer_revision) {
        if (!doc.dirty && typeof result.text === "string") {
          doc = applyCleanDocMetadata(doc, fullMetadata);
        } else {
          doc.remoteChanged = true;
        }
        notice("Merged your edits with a newer server revision. Both versions remain in History.");
      } else if (result.replaced_newer_revision) {
        notice(`Saved as the latest revision. Overlapping revision ${result.previous_revision} was preserved in History.`);
      }
      try {
        syncChannel?.postMessage({ type: "changed", id: doc.id });
      } catch {}
      await refreshFiles();
    } catch (error) {
      if (error.offline) {
        const nowIso = new Date().toISOString();
        const byteLength = new TextEncoder().encode(pending.body.text).length;
        const offlineMeta = {
          id: doc.id,
          name: pending.body.name,
          revision: doc.revision,
          sha256: doc.sha256 || "",
          size: byteLength,
          updated_at: nowIso,
          encoding: pending.body.encoding,
          bom: pending.body.bom,
          line_endings: doc.line_endings || "LF",
          text: pending.body.text,
        };
        await idbSaveOffline(doc.id, pending.body, offlineMeta);
        doc.size = byteLength;
        doc.updated_at = nowIso;
        doc.savedDoc = pending.doc;
        doc.savedName = pending.body.name;
        doc.savedEncoding = pending.body.encoding;
        doc.savedBom = pending.body.bom;
        doc.pendingSync = true;
        const summary = {
          id: doc.id,
          name: offlineMeta.name,
          revision: offlineMeta.revision,
          sha256: offlineMeta.sha256,
          size: offlineMeta.size,
          updated_at: offlineMeta.updated_at,
          encoding: offlineMeta.encoding,
        };
        files = [summary, ...files.filter((file) => file.id !== doc.id)];
        doc.dirty = isDocDirty(doc);
        try {
          syncChannel?.postMessage({ type: "changed", id: doc.id });
        } catch {}
        notice("Saved offline in this browser. Changes will sync automatically when reconnected.");
        return;
      }
      throw error;
    } finally {
      doc.saving = false;
      syncEditorMetrics();
      if (doc.remoteChanged && !doc.dirty && !doc.pending) refreshFiles().catch(failure);
    }
  }

  async function reloadFile(doc = activeDoc, prompt = true) {
    if (!doc || doc.revision === null || doc.saving) return;
    if (
      prompt &&
      doc.dirty &&
      !(await confirm(`Reload ${doc.name}?`, "Discard unsaved edits and load the current saved server version?", "Reload"))
    ) {
      return;
    }
    const before = doc.state.doc;
    const metadata = await fetchDocument(doc.id);
    if (documents.get(doc.id) !== doc || doc.saving || !doc.state.doc.eq(before) || (!prompt && doc.dirty)) {
      const current = documents.get(doc.id);
      if (current) current.remoteChanged = true;
      return;
    }
    applyCleanDocMetadata(doc, metadata);
  }

  async function mergeRemoteFile(doc = activeDoc) {
    if (!doc || doc.revision === null || doc.saving) return;
    if (!doc.dirty) {
      await reloadFile(doc, false);
      return;
    }
    const before = doc.state.doc;
    const result = await api(`/documents/${doc.id}/merge`, {
      method: "POST",
      body: JSON.stringify({
        text: before.toString(),
        base_revision: doc.revision,
      }),
    });
    if (documents.get(doc.id) !== doc || doc.saving || !doc.state.doc.eq(before)) return;
    if (!result.merged) {
      notice("Changes overlap on the same lines. Save to keep your version (preserving the remote revision in History), or reload remote.", true);
      return;
    }
    captureViewState(doc);
    const selection = doc.state.selection.main;
    const bounds = diffBounds(before.toString(), result.text);
    const serverState = EditorState.create({ doc: result.server_text ?? result.text });
    const tempMerged = EditorState.create({ doc: result.text });
    const nextSelection = EditorSelection.range(
      mapDocPos(before, tempMerged.doc, bounds, selection.anchor),
      mapDocPos(before, tempMerged.doc, bounds, selection.head)
    );
    const nextState = makeState(doc, result.text, nextSelection);
    doc.state = nextState;
    doc.savedDoc = serverState.doc;
    if (result.name) {
      if (doc.name === doc.savedName) doc.name = result.name;
      doc.savedName = result.name;
    }
    if (result.encoding) {
      if (doc.encoding === doc.savedEncoding) doc.encoding = result.encoding;
      doc.savedEncoding = result.encoding;
    }
    if (typeof result.bom === "boolean") {
      if (doc.bom === doc.savedBom) doc.bom = result.bom;
      doc.savedBom = result.bom;
    }
    doc.revision = result.revision;
    doc.sha256 = result.sha256;
    doc.size = result.size;
    doc.updated_at = result.updated_at;
    doc.line_endings = result.line_endings;
    doc.remoteChanged = false;
    doc.dirty = isDocDirty(doc, nextState.doc);
    doc.scrollSnapshot = mapScrollSnapshot(doc.scrollSnapshot, before, nextState.doc, bounds);
    if (active === doc.id) activate(doc.id);
    notice("Merged newer server changes into your unsaved buffer.");
  }

  async function runFlushOutbox() {
    const outbox = await idbGetAll("outbox");
    pendingSyncCount = outbox.length;
    if (!outbox.length) return;
    for (const item of outbox) {
      const openDoc = documents.get(item.id);
      if (openDoc?.saving) continue;
      const result = await api(`/documents/${item.id}`, { method: "PUT", body: JSON.stringify(item.body) });
      const storedText = typeof result.text === "string" ? result.text : item.body.text;
      await idbRemoveOutbox(item.id);
      const fullMetadata = {
        ...result,
        text: storedText,
        bom: typeof result.bom === "boolean" ? result.bom : item.body.bom,
        line_endings: result.line_endings,
      };
      await idbStoreDocument(fullMetadata);
      if (openDoc) {
        if (result.name) {
          if (openDoc.name === openDoc.savedName) openDoc.name = result.name;
          openDoc.savedName = result.name;
        }
        if (result.encoding) {
          if (openDoc.encoding === openDoc.savedEncoding) openDoc.encoding = result.encoding;
          openDoc.savedEncoding = result.encoding;
        }
        if (typeof result.bom === "boolean") {
          if (openDoc.bom === openDoc.savedBom) openDoc.bom = result.bom;
          openDoc.savedBom = result.bom;
        }
        openDoc.revision = result.revision;
        openDoc.sha256 = result.sha256;
        openDoc.size = result.size;
        openDoc.updated_at = result.updated_at;
        openDoc.line_endings = result.line_endings;
        openDoc.pendingSync = false;
        openDoc.remoteChanged = false;
        if (openDoc.pending?.body?.request_id === item.body.request_id) {
          openDoc.pending = null;
        }
        openDoc.dirty = isDocDirty(openDoc);
        if (result.merged_newer_revision && !openDoc.dirty && typeof result.text === "string") {
          applyCleanDocMetadata(openDoc, fullMetadata);
        }
      }
      if (result.merged_newer_revision) {
        notice("Merged offline edits with a newer server revision. Both versions remain in History.");
      } else if (result.replaced_newer_revision) {
        notice(`Saved as the latest revision. Overlapping revision ${result.previous_revision} was preserved in History.`);
      }
      try {
        syncChannel?.postMessage({ type: "changed", id: item.id });
      } catch {}
    }
  }

  async function flushOutbox() {
    if (typeof navigator !== "undefined" && navigator.locks?.request) {
      return navigator.locks.request("textpad.editor.outbox", runFlushOutbox);
    }
    return runFlushOutbox();
  }

  async function cacheDocumentsInBackground(remoteFiles) {
    for (const remote of remoteFiles) {
      if (!apiOnline) break;
      const cached = await idbGet("documents", remote.id);
      if (cached && cached.revision === remote.revision) continue;
      const pending = await idbGet("outbox", remote.id);
      if (pending) continue;
      try {
        const res = await fetch(`${apiBase}/documents/${encodeURIComponent(remote.id)}`, {
          credentials: "same-origin",
          headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf, "X-Textpad-Client": clientId },
        });
        if (res.ok) {
          const data = await res.json();
          await idbStoreDocument(data);
        }
      } catch {
        break;
      }
    }
  }

  async function refreshFiles() {
    if (polling) {
      refreshAgain = true;
      return;
    }
    polling = true;
    try {
      await flushOutbox();
      await syncErrands();
      const result = await api("/documents");
      files = result.documents;
      await idbSyncFileList(files);
      for (const remote of files) {
        const doc = documents.get(remote.id);
        if (!doc || !doc.revision || doc.revision === remote.revision) continue;
        if (doc.dirty || doc.saving || doc.pending) {
          doc.remoteChanged = true;
          continue;
        }
        await reloadFile(doc, false);
      }
      syncEditorMetrics();
      if (hashtagsPanelOpen) runHashtagSearch();
      cacheDocumentsInBackground(files);
    } catch (error) {
      if (error.offline) {
        const cachedFiles = await idbGetAll("files");
        const outbox = await idbGetAll("outbox");
        pendingSyncCount = outbox.length;
        if (cachedFiles.length > 0 || files.length > 0) {
          if (cachedFiles.length > 0) {
            cachedFiles.sort((a, b) => String(b.updated_at || "").localeCompare(String(a.updated_at || "")));
            files = cachedFiles;
          }
          syncEditorMetrics();
          if (hashtagsPanelOpen) runHashtagSearch();
          return;
        }
      }
      throw error;
    } finally {
      polling = false;
      if (refreshAgain) {
        refreshAgain = false;
        refreshFiles().catch(failure);
      }
    }
  }

  const HASHTAG_TEXT_RE = /(?:^|(?<=[\s(\[{"']))#([A-Za-z_][A-Za-z0-9_-]*)(?=$|\s|[.,;:!?)\]}"'])/g;
  const TAG_TOKEN_JS_RE = /#?([A-Za-z_][A-Za-z0-9_-]*)/g;

  function parseTagListJs(input) {
    if (!input) return [];
    const str = Array.isArray(input) ? input.join(" ") : String(input);
    const seen = new Set();
    const out = [];
    for (const m of str.matchAll(TAG_TOKEN_JS_RE)) {
      const norm = m[1].toLowerCase();
      if (norm && norm.length <= 64 && !seen.has(norm)) {
        seen.add(norm);
        out.push(norm);
      }
    }
    return out;
  }

  function formatTagListJs(tags) {
    return (tags || []).map((t) => `#${t}`).join(" ");
  }

  function saveLocalErrands(tags, updatedAt, dirty) {
    errandsTags = tags;
    errandsUpdatedAt = updatedAt || "";
    errandsDirty = Boolean(dirty);
    try {
      localStorage.setItem(
        "textpad.editor.errands",
        JSON.stringify({ hashtags: errandsTags, updated_at: errandsUpdatedAt, dirty: errandsDirty })
      );
    } catch {}
  }

  async function syncErrands() {
    try {
      if (errandsDirty) {
        const saved = await api("/errands", { method: "PUT", body: JSON.stringify({ hashtags: errandsTags }) });
        saveLocalErrands(saved.hashtags || [], saved.updated_at || "", false);
      } else {
        const remote = await api("/errands");
        if (Array.isArray(remote.hashtags)) {
          saveLocalErrands(remote.hashtags, remote.updated_at || "", false);
        }
      }
    } catch (error) {
      if (!error.offline) throw error;
    }
  }

  async function saveErrandsFromInput() {
    const parsed = parseTagListJs(errandsInput);
    editingErrands = false;
    saveLocalErrands(parsed, errandsUpdatedAt, true);
    runHashtagSearch();
    try {
      const saved = await api("/errands", { method: "PUT", body: JSON.stringify({ hashtags: parsed }) });
      saveLocalErrands(saved.hashtags || parsed, saved.updated_at || "", false);
      try {
        syncChannel?.postMessage({ type: "changed", id: "errands" });
      } catch {}
    } catch (error) {
      if (error.offline) {
        notice("Saved errands hashtags offline. They will sync when reconnected.");
        return;
      }
      failure(error);
    }
  }

  function toggleQueryHashtag(tag) {
    const current = parseTagListJs(hashtagQuery);
    const next = current.includes(tag) ? current.filter((t) => t !== tag) : [...current, tag];
    hashtagQuery = formatTagListJs(next);
    runHashtagSearch();
  }

  function scanDocTextForHashtags(docId, docName, text, targetsSet, hideCompleted, discoveredSet, discoveredList) {
    for (const m of text.matchAll(HASHTAG_TEXT_RE)) {
      const found = m[1].toLowerCase();
      if (!discoveredSet.has(found)) {
        discoveredSet.add(found);
        discoveredList.push(found);
      }
    }
    if (!targetsSet.size) return null;
    const fileTags = [];
    const fileTagsSet = new Set();
    const lines = [];
    const rawLines = text.split("\n");
    let offset = 0;
    for (let i = 0; i < rawLines.length; i++) {
      const line = rawLines[i];
      const stripped = line.replace(/^[ \t]+/, "");
      if (!hideCompleted || !(stripped.startsWith("[X]") || stripped.startsWith("[x]"))) {
        const lineTags = [];
        const lineTagsSet = new Set();
        for (const m of line.matchAll(HASHTAG_TEXT_RE)) {
          const found = m[1].toLowerCase();
          if (targetsSet.has(found)) {
            if (!lineTagsSet.has(found)) {
              lineTagsSet.add(found);
              lineTags.push(found);
            }
            if (!fileTagsSet.has(found)) {
              fileTagsSet.add(found);
              fileTags.push(found);
            }
          }
        }
        if (lineTags.length > 0) {
          lines.push({ line_number: i + 1, offset, text: line, matched_tags: lineTags });
        }
      }
      offset += line.length + 1;
    }
    if (lines.length === 0) return null;
    return { id: docId, name: docName, matched_tags: fileTags, lines };
  }

  async function runHashtagSearch() {
    const activeTargets = hashtagsTab === "errands" ? errandsTags : parseTagListJs(hashtagQuery);
    const targetsSet = new Set(activeTargets);
    const hideCompleted = hashtagHideCompleted;
    hashtagSearching = true;
    try {
      const discoveredSet = new Set();
      const discoveredList = [];
      const results = [];
      const openIds = [];

      // 1. Scan all open tabs (including unsaved edits in memory)
      for (const doc of documents.values()) {
        openIds.push(doc.id);
        const text = active === doc.id && view ? view.state.doc.toString() : doc.state.doc.toString();
        const match = scanDocTextForHashtags(
          doc.id,
          doc.name,
          text,
          targetsSet,
          hideCompleted,
          discoveredSet,
          discoveredList
        );
        if (match) results.push(match);
      }

      // 2. Scan remaining synced/recent server files (online via API, or offline via IndexedDB)
      let remoteScanned = false;
      if (apiOnline) {
        try {
          const serverRes = await api("/hashtags", {
            method: "POST",
            body: JSON.stringify({
              tags: activeTargets,
              hide_completed: hideCompleted,
              exclude_ids: openIds,
            }),
          });
          for (const t of serverRes.all_hashtags || []) {
            if (!discoveredSet.has(t)) {
              discoveredSet.add(t);
              discoveredList.push(t);
            }
          }
          for (const r of serverRes.results || []) {
            results.push(r);
          }
          remoteScanned = true;
        } catch (err) {
          if (!err.offline) throw err;
        }
      }

      if (!remoteScanned) {
        const openIdSet = new Set(openIds);
        const cachedDocs = await idbGetAll("documents");
        for (const cached of cachedDocs) {
          if (!cached?.id || openIdSet.has(cached.id) || typeof cached.text !== "string") continue;
          const match = scanDocTextForHashtags(
            cached.id,
            cached.name || "Untitled.txt",
            cached.text,
            targetsSet,
            hideCompleted,
            discoveredSet,
            discoveredList
          );
          if (match) results.push(match);
        }
      }

      discoveredHashtags = discoveredList;
      hashtagResults = results;
    } catch (error) {
      failure(error);
    } finally {
      hashtagSearching = false;
    }
  }

  function openHashtagsPanel(tab = "search") {
    closeHistory();
    hashtagsTab = tab;
    hashtagsPanelOpen = true;
    if (tab === "errands" && errandsTags.length === 0) {
      editingErrands = true;
      errandsInput = "";
    }
    runHashtagSearch();
  }

  function closeHashtagsPanel() {
    hashtagsPanelOpen = false;
    editingErrands = false;
  }

  async function jumpToHashtagResult(fileId, lineNumber = null) {
    await openFile(fileId);
    if (lineNumber !== null && view && active === fileId) {
      if (activeDoc?.tasksOnly && activeDoc.taskFilterResult && !activeDoc.taskFilterResult.isVisible[lineNumber - 1]) {
        toggleTasksFilter(activeDoc);
      }
      const targetLineNum = Math.max(1, Math.min(lineNumber, view.state.doc.lines));
      const line = view.state.doc.line(targetLineNum);
      view.dispatch({
        selection: EditorSelection.cursor(line.from),
        effects: EditorView.scrollIntoView(line.from, { y: "center" }),
      });
      view.focus();
      syncEditorMetrics();
    }
  }

  function closeHistory() {
    historyDocument = null;
    historyRevisions = [];
    selectedRevision = null;
    previewText = "";
    fullscreenRevision = false;
  }

  async function showHistory() {
    const doc = activeDoc;
    if (!doc?.revision) return;
    closeHashtagsPanel();
    historyDocument = doc.id;
    selectedRevision = null;
    previewText = "";
    fullscreenRevision = false;
    historyRevisions = [];
    try {
      const result = await api(`/documents/${doc.id}/revisions`);
      if (historyDocument !== doc.id) return;
      historyRevisions = result.documents;
    } catch (error) {
      failure(error);
    }
  }

  async function selectHistoryRevision(revision) {
    const doc = activeDoc;
    if (!doc || historyDocument !== doc.id) return;
    try {
      const content = await api(`/documents/${doc.id}/revisions/${revision.revision}`);
      if (historyDocument !== doc.id) return;
      selectedRevision = revision;
      previewText = content.text;
    } catch (error) {
      failure(error);
    }
  }

  function downloadRevision() {
    const doc = activeDoc;
    const revision = selectedRevision;
    if (!doc || !revision) return;
    window.location.assign(
      `${apiBase}/documents/${encodeURIComponent(doc.id)}/download?revision=${encodeURIComponent(revision.revision)}`
    );
  }

  async function restoreRevision() {
    let doc = activeDoc;
    const revision = selectedRevision;
    if (!doc || !revision) return;
    if (doc.dirty || doc.saving) {
      notice("Save or discard your unsaved edits before restoring a revision.", true);
      return;
    }
    if (
      !(await confirm(
        `Restore revision ${revision.revision}?`,
        "This makes the older contents the current server version. The displaced version remains in history.",
        "Restore"
      ))
    ) {
      return;
    }
    doc = documents.get(doc.id);
    if (!doc || doc.dirty || doc.saving) {
      notice("The document changed. Save or discard the new edits before restoring.", true);
      return;
    }
    doc.saving = true;
    try {
      await api(`/documents/${doc.id}/restore`, {
        method: "POST",
        body: JSON.stringify({ revision: revision.revision, base_revision: doc.revision, request_id: crypto.randomUUID() }),
      });
      doc.saving = false;
      await reloadFile(doc, false);
      await refreshFiles();
      await showHistory();
      notice("Revision restored and available to synced devices.");
    } finally {
      doc.saving = false;
    }
  }

  async function pruneHistory() {
    const doc = activeDoc;
    if (!doc?.revision) return;
    try {
      const result = await api(`/documents/${doc.id}/prune`, { method: "POST", body: "{}" });
      if (historyDocument === doc.id) {
        historyRevisions = result.documents;
        if (selectedRevision && !historyRevisions.some((r) => r.revision === selectedRevision.revision)) {
          fullscreenRevision = false;
          selectedRevision = null;
          previewText = "";
        }
      }
      notice(
        result.removed > 0
          ? `Pruned ${result.removed} older revision${result.removed === 1 ? "" : "s"}.`
          : "No revisions needed pruning."
      );
    } catch (error) {
      failure(error);
    }
  }

  async function renameFile() {
    const doc = activeDoc;
    if (!doc || doc.saving) return;
    const result = await openModal({
      type: "renameFile",
      title: "Rename file",
      filename: doc.name,
      actions: [
        { label: "Cancel", value: false },
        { label: "Apply", value: true, primary: true },
      ],
    });
    if (result) {
      const name = (result.filename ?? "").trim().normalize("NFC");
      if (!name || /[/\\\x00-\x1f]/.test(name)) {
        notice("Choose a filename without slashes or control characters.", true);
        return;
      }
      doc.name = name;
      doc.dirty = isDocDirty(doc);
    }
  }

  async function encodingSettings() {
    const doc = activeDoc;
    if (!doc || doc.saving) return;
    const result = await openModal({
      type: "encoding",
      title: "File format",
      encoding: doc.encoding,
      bom: doc.bom,
      actions: [
        { label: "Rename…", value: "rename" },
        { label: "Cancel", value: false },
        { label: "Apply", value: true, primary: true },
      ],
    });
    if (result?.action === "rename") return renameFile();
    if (result?.action === true) {
      doc.encoding = result.encoding;
      doc.bom = Boolean(result.bom && result.encoding !== "ascii");
      doc.dirty = isDocDirty(doc);
    }
  }

  function setSidebarCollapsed(collapsed) {
    sidebarCollapsed = collapsed;
    try {
      localStorage.setItem("textpad.editor.sidebarCollapsed", String(collapsed));
    } catch {}
    view?.requestMeasure();
  }

  function applyPreferences() {
    preferences.fontSize = Math.min(30, Math.max(10, Number(preferences.fontSize) || 15));
    preferences.dividerDashes = Math.min(200, Math.max(1, Math.trunc(Number(preferences.dividerDashes)) || 40));
    document.documentElement.style.setProperty("--font-size", `${preferences.fontSize}px`);
    document.documentElement.style.setProperty("--editor-font", fonts[preferences.font] || fonts.mono);
    const effects = () => [
      wrapCompartment.reconfigure(preferences.wrap ? EditorView.lineWrapping : []),
      guttersCompartment.reconfigure(preferences.lines ? lineNumbers() : []),
    ];
    for (const doc of documents.values()) {
      if (active === doc.id && view) {
        view.dispatch({ effects: effects() });
      } else {
        doc.state = doc.state.update({ effects: effects() }).state;
      }
    }
    try {
      localStorage.setItem("textpad.editor.preferences", JSON.stringify(preferences));
    } catch {}
  }

  async function openSettings() {
    const result = await openModal({
      type: "settings",
      title: "Editor settings",
      font: preferences.font,
      fontSize: preferences.fontSize,
      dividerDashes: preferences.dividerDashes,
      wrap: preferences.wrap,
      lines: preferences.lines,
      actions: [
        { label: "Cancel", value: false },
        { label: "Apply", value: true, primary: true },
      ],
    });
    if (result?.action === true) {
      preferences = {
        font: result.font,
        fontSize: Number(result.fontSize),
        dividerDashes: Number(result.dividerDashes),
        wrap: Boolean(result.wrap),
        lines: Boolean(result.lines),
      };
      applyPreferences();
    }
  }

  function toggleReadOnly() {
    readOnly = !readOnly;
    const effect = readOnlyCompartment.reconfigure(
      readOnly ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : []
    );
    for (const doc of documents.values()) {
      if (active === doc.id && view) {
        view.dispatch({ effects: effect });
      } else {
        doc.state = doc.state.update({ effects: effect }).state;
      }
    }
    if (view && !readOnly) view.focus();
  }

  function toggleTasksFilter(targetDoc = activeDoc) {
    const doc = targetDoc;
    if (!doc) return;
    const isCurrent = doc === activeDoc && view;
    if (doc.tasksOnly) {
      doc.tasksOnly = false;
      doc.taskFilterResult = null;
      const effect = tasksFilterCompartment.reconfigure([]);
      if (isCurrent) {
        view.dispatch({ effects: effect, scrollIntoView: true });
        doc.state = view.state;
        view.focus();
      } else if (doc.state) {
        doc.state = doc.state.update({ effects: effect }).state;
      }
    } else {
      const currentDocText = isCurrent ? view.state.doc.toString() : doc.state ? doc.state.doc.toString() : "";
      const filterRes = filterTasks(currentDocText, 4);
      doc.tasksOnly = true;
      doc.taskFilterResult = filterRes;
      const stateDoc = isCurrent ? view.state.doc : doc.state?.doc;
      const ext = getTasksFilterExtension(doc, stateDoc);
      const effect = tasksFilterCompartment.reconfigure(ext);

      if (isCurrent) {
        let dispatchObj = { effects: effect };
        const sel = view.state.selection.main;
        const currentLineNo = view.state.doc.lineAt(sel.head).number - 1;
        if (!filterRes.isVisible[currentLineNo] && filterRes.visibleLines.length > 0) {
          let bestLine = filterRes.visibleLines[0];
          let minDiff = Math.abs(bestLine - currentLineNo);
          for (const vl of filterRes.visibleLines) {
            if (Math.abs(vl - currentLineNo) < minDiff) {
              minDiff = Math.abs(vl - currentLineNo);
              bestLine = vl;
            }
          }
          const targetLine = view.state.doc.line(bestLine + 1);
          dispatchObj.selection = EditorSelection.cursor(targetLine.from);
          dispatchObj.scrollIntoView = true;
        }
        view.dispatch(dispatchObj);
        doc.state = view.state;
        view.focus();
      } else if (doc.state) {
        doc.state = doc.state.update({ effects: effect }).state;
      }
      if (!filterRes.isUncompleted.some((value) => value === 1)) {
        notice("No uncompleted tasks found");
      }
    }
  }

  function insertSnippet(snippet) {
    if (!activeDoc || !view || readOnly) return;
    if (activeDoc.tasksOnly && snippet.includes("\n")) {
      notice("Lines cannot be added or deleted in this view");
      return;
    }
    const sel = view.state.selection.main;
    view.dispatch({
      changes: { from: sel.from, to: sel.to, insert: snippet },
      selection: { anchor: sel.from + snippet.length },
      scrollIntoView: true,
    });
    view.focus();
  }

  function currentDateString() {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
  }

  function currentTimeString() {
    const now = new Date();
    const h24 = now.getHours();
    const h12 = h24 % 12 || 12;
    return `${h12}:${String(now.getMinutes()).padStart(2, "0")} ${h24 >= 12 ? "PM" : "AM"}`;
  }

  function toggleTodo() {
    if (!activeDoc || !view || readOnly) return;
    const sel = view.state.selection.main;
    const doc = view.state.doc;
    const startLine = doc.lineAt(sel.from);
    const effectiveTo = sel.to > sel.from && doc.sliceString(sel.to - 1, sel.to) === "\n" ? sel.to - 1 : sel.to;
    const endLine = doc.lineAt(effectiveTo);
    const lines = [];
    for (let n = startLine.number; n <= endLine.number; n++) {
      const line = doc.line(n);
      const match = line.text.match(/^[ \t]*/);
      const indentEnd = match ? match[0].length : 0;
      const rest = line.text.slice(indentEnd);
      const blank = indentEnd === line.text.length;
      const state = rest.startsWith("[ ]")
        ? "unchecked"
        : rest.startsWith("[X]") || rest.startsWith("[x]")
          ? "checked"
          : "plain";
      lines.push({ line, indentEnd, rest, blank, state });
    }
    const hasNonBlank = lines.some((l) => !l.blank);
    const targets = hasNonBlank ? lines.filter((l) => !l.blank) : lines;
    const action = targets.some((l) => l.state === "plain")
      ? "add"
      : targets.some((l) => l.state === "unchecked")
        ? "check"
        : "remove";
    const changes = [];
    for (const item of targets) {
      const pos = item.line.from + item.indentEnd;
      if (action === "add" && item.state === "plain") {
        changes.push({ from: pos, to: pos, insert: "[ ] " });
      } else if (action === "check" && item.state === "unchecked") {
        changes.push({ from: pos, to: pos + 3, insert: "[X]" });
      } else if (action === "remove" && item.state === "checked") {
        const removeLen = item.rest.startsWith("[X] ") || item.rest.startsWith("[x] ") ? 4 : 3;
        changes.push({ from: pos, to: pos + removeLen, insert: "" });
      }
    }
    if (!changes.length) {
      view.focus();
      return;
    }
    let nextAnchor = sel.anchor;
    let nextHead = sel.head;
    if (sel.empty) {
      const ch = changes[0];
      const delta = ch.insert.length - (ch.to - ch.from);
      if (action === "add") {
        nextHead = Math.max(ch.from + 4, sel.head + 4);
      } else if (action === "check") {
        nextHead = Math.max(ch.from + 3, sel.head);
      } else {
        nextHead = sel.head <= ch.from ? sel.head : Math.max(ch.from, sel.head + delta);
      }
      nextAnchor = nextHead;
    } else {
      let newFrom = sel.from;
      let newTo = sel.to;
      for (const ch of changes) {
        const removed = ch.to - ch.from;
        const delta = ch.insert.length - removed;
        if (ch.from < sel.from) {
          newFrom += ch.to <= sel.from ? delta : ch.insert.length - (sel.from - ch.from);
        }
        if (ch.from <= sel.to) {
          newTo += ch.to <= sel.to ? delta : ch.insert.length - (sel.to - ch.from);
        }
      }
      if (sel.anchor <= sel.head) {
        nextAnchor = newFrom;
        nextHead = newTo;
      } else {
        nextAnchor = newTo;
        nextHead = newFrom;
      }
    }
    view.dispatch({
      changes,
      selection: { anchor: nextAnchor, head: nextHead },
      scrollIntoView: true,
    });
    view.focus();
  }

  function goToBottom() {
    if (!activeDoc || !view) return;
    const end = view.state.doc.length;
    view.dispatch({ selection: { anchor: end, head: end }, scrollIntoView: true });
    view.focus();
  }

  function findMatchesInText(text, needle, caseSensitive) {
    if (!needle) return [];
    const haystack = caseSensitive ? text : text.toLocaleLowerCase();
    const target = caseSensitive ? needle : needle.toLocaleLowerCase();
    if (!target) return [];
    const matches = [];
    let index = 0;
    while (index <= haystack.length - target.length) {
      const found = haystack.indexOf(target, index);
      if (found === -1) break;
      matches.push({ from: found, to: found + target.length });
      index = found + Math.max(1, target.length);
    }
    return matches;
  }

  function syncSearchHighlight() {
    if (!view || !activeDoc) return;
    if (findOpen) openSearchPanel(view);
    else closeSearchPanel(view);
    const query =
      findOpen && findQuery
        ? new SearchQuery({ search: findQuery, caseSensitive: findMatchCase })
        : new SearchQuery({ search: "" });
    view.dispatch({ effects: setSearchQuery.of(query) });
  }

  function openFind(allFiles = false) {
    if (!activeDoc) return;
    findAllFiles = allFiles;
    findOpen = true;
    syncSearchHighlight();
    queueMicrotask(() => {
      findInputEl?.focus();
      findInputEl?.select();
    });
  }

  function closeFind() {
    findOpen = false;
    findResultText = "";
    syncSearchHighlight();
    view?.focus();
  }

  function selectMatchInDoc(doc, match, index, total) {
    if (active !== doc.id) {
      activate(doc.id);
    }
    if (view) {
      openSearchPanel(view);
      const query = new SearchQuery({ search: findQuery, caseSensitive: findMatchCase });
      view.dispatch({
        selection: EditorSelection.range(match.from, match.to),
        effects: [setSearchQuery.of(query), EditorView.scrollIntoView(match.from, { y: "center" })],
      });
      doc.state = view.state;
      syncEditorMetrics();
    }
    findResultText = findAllFiles ? `${index + 1}/${total} · ${doc.name}` : `${index + 1}/${total}`;
  }

  function filterMatchesForDoc(doc, stateDoc, matches) {
    if (!doc?.tasksOnly || !doc?.taskFilterResult) return matches;
    return matches.filter((m) => {
      const lineNo = stateDoc.lineAt(m.from).number - 1;
      return doc.taskFilterResult.isVisible[lineNo] === 1;
    });
  }

  function findStep(forward = true) {
    if (!activeDoc || !view) return;
    if (!findQuery) {
      findResultText = "";
      syncSearchHighlight();
      return;
    }
    const sel = view.state.selection.main;
    const curMatchesRaw = findMatchesInText(view.state.doc.toString(), findQuery, findMatchCase);
    const curMatches = filterMatchesForDoc(activeDoc, view.state.doc, curMatchesRaw);

    if (!findAllFiles) {
      if (curMatches.length === 0) {
        findResultText = "No matches";
        syncSearchHighlight();
        return;
      }
      let targetIdx = -1;
      const exactIdx = curMatches.findIndex((m) => m.from === sel.from && m.to === sel.to);
      if (forward) {
        const searchFrom = exactIdx >= 0 ? sel.to : sel.from;
        targetIdx = curMatches.findIndex((m) => m.from >= searchFrom);
        if (targetIdx === -1) targetIdx = 0;
      } else {
        const searchBefore = exactIdx >= 0 ? sel.from : sel.to;
        for (let i = curMatches.length - 1; i >= 0; i--) {
          if (curMatches[i].to <= searchBefore) {
            targetIdx = i;
            break;
          }
        }
        if (targetIdx === -1) targetIdx = curMatches.length - 1;
      }
      selectMatchInDoc(activeDoc, curMatches[targetIdx], targetIdx, curMatches.length);
      return;
    }

    const exactIdx = curMatches.findIndex((m) => m.from === sel.from && m.to === sel.to);
    if (forward) {
      const searchFrom = exactIdx >= 0 ? sel.to : sel.from;
      const nextInCurrent = curMatches.findIndex((m) => m.from >= searchFrom);
      if (nextInCurrent !== -1) {
        selectMatchInDoc(activeDoc, curMatches[nextInCurrent], nextInCurrent, curMatches.length);
        return;
      }
    } else {
      const searchBefore = exactIdx >= 0 ? sel.from : sel.to;
      let prevInCurrent = -1;
      for (let i = curMatches.length - 1; i >= 0; i--) {
        if (curMatches[i].to <= searchBefore) {
          prevInCurrent = i;
          break;
        }
      }
      if (prevInCurrent !== -1) {
        selectMatchInDoc(activeDoc, curMatches[prevInCurrent], prevInCurrent, curMatches.length);
        return;
      }
    }

    const openDocs = [...documents.values()];
    const curDocIdx = openDocs.findIndex((d) => d.id === activeDoc.id);
    for (let step = 1; step < openDocs.length; step++) {
      const idx = forward
        ? (curDocIdx + step) % openDocs.length
        : (curDocIdx - step + openDocs.length) % openDocs.length;
      const candidate = openDocs[idx];
      const text = candidate.id === activeDoc.id ? view.state.doc.toString() : candidate.state.doc.toString();
      const candidateDoc = candidate.id === activeDoc.id ? view.state.doc : candidate.state.doc;
      const rawMatches = findMatchesInText(text, findQuery, findMatchCase);
      const matches = filterMatchesForDoc(candidate, candidateDoc, rawMatches);
      if (matches.length > 0) {
        const matchIdx = forward ? 0 : matches.length - 1;
        selectMatchInDoc(candidate, matches[matchIdx], matchIdx, matches.length);
        return;
      }
    }

    if (curMatches.length > 0) {
      const matchIdx = forward ? 0 : curMatches.length - 1;
      selectMatchInDoc(activeDoc, curMatches[matchIdx], matchIdx, curMatches.length);
    } else {
      findResultText = "No matches";
      syncSearchHighlight();
    }
  }

  async function connectAndroid() {
    const info = await api("/connection");
    await openModal({
      type: "connect",
      title: "Connect Android",
      endpoint: info.endpoint,
      token: info.token,
      showToken: false,
      copiedEndpoint: false,
      copiedToken: false,
      actions: [{ label: "Done", value: true, primary: true }],
    });
  }

  async function downloadActive() {
    const doc = activeDoc;
    if (!doc?.revision) return;
    if (
      doc.dirty &&
      !(await confirm(
        "Download saved version?",
        "This downloads the saved server file. Your unsaved edits remain in this tab.",
        "Download"
      ))
    ) {
      return;
    }
    window.location.assign(`${apiBase}/documents/${doc.id}/download`);
  }

  async function signOut() {
    if (
      [...documents.values()].some((d) => d.dirty) &&
      !(await confirm("Sign out?", "Your open unsaved edits will be discarded.", "Sign out"))
    ) {
      return;
    }
    try {
      await api("/logout", { method: "POST", body: "{}" });
      await idbClearAll();
      try {
        navigator.serviceWorker?.controller?.postMessage({ type: "CLEAR_CACHE" });
      } catch {}
      window.location.assign(`${prefix}/editor/login`);
    } catch (error) {
      failure(error);
    }
  }

  function resolveModalAction(actionValue) {
    if (!modalState) return;
    if (modalState.type === "newFile" || modalState.type === "renameFile") {
      finishModal(actionValue ? { filename: modalState.filename } : null);
    } else if (modalState.type === "encoding") {
      finishModal(actionValue ? { action: actionValue, encoding: modalState.encoding, bom: modalState.bom } : null);
    } else if (modalState.type === "settings") {
      finishModal(
        actionValue
          ? {
              action: true,
              font: modalState.font,
              fontSize: modalState.fontSize,
              dividerDashes: modalState.dividerDashes,
              wrap: modalState.wrap,
              lines: modalState.lines,
            }
          : null
      );
    } else {
      finishModal(actionValue);
    }
  }

  onMount(() => {
    view = new EditorView({
      parent: editorHost,
      state: EditorState.create({ doc: "", extensions: [EditorView.cspNonce.of(nonce)] }),
    });

    applyPreferences();
    refreshFiles().catch(failure);
    syncErrands().catch(() => {});
    connectEvents();
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register(`${prefix}/editor/sw.js`, { scope: `${prefix}/editor/` }).catch(() => {});
    }

    const onBeforeUnload = (event) => {
      if ([...documents.values()].some((d) => d.dirty || d.saving)) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    const onKeyDown = (event) => {
      if (event.defaultPrevented) return;
      if (event.key === "Escape" && fullscreenRevision) {
        event.preventDefault();
        fullscreenRevision = false;
      } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        save().catch(failure);
      } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f") {
        if (activeDoc) {
          event.preventDefault();
          openFind(event.shiftKey);
        }
      } else if ((event.ctrlKey || event.metaKey) && event.altKey && !event.shiftKey && (event.code === "KeyR" || event.key.toLowerCase() === "r")) {
        if (activeDoc) {
          event.preventDefault();
          toggleReadOnly();
        }
      } else if ((event.ctrlKey || event.metaKey) && event.altKey && !event.shiftKey && (event.code === "KeyT" || event.key.toLowerCase() === "t")) {
        if (activeDoc) {
          event.preventDefault();
          toggleTasksFilter();
        }
      }
    };
    const onOnline = () => {
      closeEvents();
      connectEvents();
      refreshFiles().catch(failure);
      syncErrands().catch(() => {});
    };
    const onOffline = () => {
      apiOnline = false;
      closeEvents();
    };
    const onVisibilityChange = () => {
      if (document.hidden) closeEvents();
      else {
        connectEvents();
        refreshFiles().catch(failure);
      }
    };
    const onPageShow = () => {
      connectEvents();
      refreshFiles().catch(failure);
    };

    try {
      if (typeof BroadcastChannel !== "undefined") {
        syncChannel = new BroadcastChannel("textpad.editor.sync");
        syncChannel.onmessage = (event) => {
          if (event.data?.type === "changed") {
            refreshFiles().catch(failure);
          }
        };
      }
    } catch {}

    window.addEventListener("beforeunload", onBeforeUnload);
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("online", onOnline);
    window.addEventListener("offline", onOffline);
    window.addEventListener("pagehide", closeEvents);
    window.addEventListener("pageshow", onPageShow);
    document.addEventListener("visibilitychange", onVisibilityChange);

    const pollInterval = setInterval(() => {
      if (!document.hidden && !eventsReady) refreshFiles().catch(failure);
    }, 30000);

    return () => {
      clearInterval(pollInterval);
      window.removeEventListener("beforeunload", onBeforeUnload);
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
      window.removeEventListener("pagehide", closeEvents);
      window.removeEventListener("pageshow", onPageShow);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      try {
        syncChannel?.close();
      } catch {}
      syncChannel = null;
      closeEvents();
      view?.destroy();
    };
  });
</script>

<div class="workspace">
  {#if !sidebarCollapsed}
    <button
      type="button"
      class="sidebar-backdrop"
      aria-label="Hide sidebar"
      tabindex="-1"
      onclick={() => setSidebarCollapsed(true)}
    ></button>
  {/if}
  <aside id="file-sidebar" class="sidebar" aria-label="Synced files" hidden={sidebarCollapsed}>
    <div class="sidebar-top">
      <a class="brand" href="{prefix}/"><span class="brand-mark" aria-hidden="true">T_</span> Textpad <span class="edition">WEB</span></a>
      <button
        id="sidebar-close"
        type="button"
        class="icon-button sidebar-close"
        aria-label="Hide sidebar"
        title="Hide sidebar"
        onclick={() => setSidebarCollapsed(true)}
      >×</button>
    </div>
    <div class="sidebar-heading">
      <h1>Synced files <span id="file-count">{files.length}</span></h1>
      <button id="new-file" class="icon-button" title="New server file" aria-label="New server file" onclick={newFile}>+</button>
    </div>
    <label for="file-filter" class="sr-only">Filter synced files</label>
    <input id="file-filter" class="file-filter" type="search" placeholder="Find a file…" autocomplete="off" bind:value={fileFilter}>
    <div id="file-list" class="file-list" aria-live="polite">
      {#each filteredFiles as file (file.id)}
        <button
          class="file-item"
          class:selected={active === file.id}
          data-id={file.id}
          title="{file.name}\n{file.id}"
          onclick={() => openFile(file.id)}
        >
          <span class="file-name">{file.name}</span>
          <span class="file-meta">{bytes(file.size)} · {date(file.updated_at)}</span>
        </button>
      {:else}
        <p class="muted small">{fileFilter ? "No matching files." : "No synced files yet. Create one here, or sync a file from Android."}</p>
      {/each}
    </div>
    <div class="sidebar-footer">
      <div class="connection">
        <span id="connection-dot" class="connection-dot" class:offline={!apiOnline}></span>
        <span id="connection-label">{connectionLabel}</span>
        <button id="refresh" class="icon-button" title="Refresh files" aria-label="Refresh files" onclick={() => refreshFiles().catch(failure)}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><polyline points="21 3 21 9 15 9"/></svg>
        </button>
      </div>
      <div class="sidebar-links">
        <button id="connect-android" class="icon-button" title="Connect Android" aria-label="Connect Android" onclick={() => connectAndroid().catch(failure)}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="6" y="2" width="12" height="20" rx="2"/><line x1="12" y1="18" x2="12.01" y2="18"/></svg>
        </button>
        <button id="settings" class="icon-button" title="Editor settings" aria-label="Editor settings" onclick={openSettings}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="18" x2="20" y2="18"/><circle cx="9" cy="6" r="2" fill="#141a17"/><circle cx="15" cy="12" r="2" fill="#141a17"/><circle cx="10" cy="18" r="2" fill="#141a17"/></svg>
        </button>
        <button id="sign-out" class="icon-button" title="Sign out" aria-label="Sign out" onclick={signOut}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
        </button>
      </div>
    </div>
  </aside>

  <main class="main-editor">
    <div class="editor-heading">
      <button
        id="sidebar-toggle"
        class="icon-button sidebar-toggle"
        aria-controls="file-sidebar"
        aria-expanded={String(!sidebarCollapsed)}
        aria-label={sidebarCollapsed ? "Show sidebar" : "Hide sidebar"}
        title={sidebarCollapsed ? "Show sidebar" : "Hide sidebar"}
        onclick={() => setSidebarCollapsed(!sidebarCollapsed)}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true" focusable="false"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/></svg>
      </button>
      <div id="tabs" class="tabs" role="tablist" aria-label="Open files">
        {#if documents.size === 0}
          <span class="tabs-placeholder">A little space to write.</span>
        {:else}
          {#each [...documents.values()] as doc (doc.id)}
            <div class="tab" class:active={active === doc.id}>
              <button
                class="tab-main"
                role="tab"
                aria-selected={String(active === doc.id)}
                title={doc.name}
                onclick={() => activate(doc.id)}
              >
                {#if doc.dirty}
                  <span class="dirty-dot">●</span>
                {/if}
                <span class="tab-name">{doc.name}</span>
              </button>
              <button class="tab-close" aria-label="Close {doc.name}" onclick={() => closeFile(doc.id)}>×</button>
            </div>
          {/each}
        {/if}
      </div>
    </div>

    <div class="toolbar">
      <button id="save" class="primary icon-button" title="Save (Ctrl+S)" aria-label="Save" disabled={!activeDoc || activeDoc.saving || !activeDoc.dirty} onclick={() => save().catch(failure)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
      </button>
      <button id="undo" class="icon-button" title="Undo (Ctrl+Z)" aria-label="Undo" disabled={!activeDoc || readOnly || !canUndo} onclick={() => { undo(view); view.focus(); syncEditorMetrics(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M3 9h11a5 5 0 0 1 0 10H9"/><polyline points="7 5 3 9 7 13"/></svg>
      </button>
      <button id="redo" class="icon-button" title="Redo (Ctrl+Shift+Z)" aria-label="Redo" disabled={!activeDoc || readOnly || !canRedo} onclick={() => { redo(view); view.focus(); syncEditorMetrics(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 9H10a5 5 0 0 0 0 10h5"/><polyline points="17 5 21 9 17 13"/></svg>
      </button>
      <span class="toolbar-divider"></span>
      <button id="go-bottom" class="icon-button" title="Go to bottom of file" aria-label="Go to bottom of file" disabled={!activeDoc} onclick={goToBottom}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M12 4v12"/><path d="m7 11 5 5 5-5"/><path d="M5 20h14"/></svg>
      </button>
      <button id="toggle-readonly" class="icon-button" class:active={readOnly} aria-pressed={String(readOnly)} title={readOnly ? "Disable read-only mode (Ctrl+Alt+R)" : "Enable read-only mode (Ctrl+Alt+R)"} aria-label="Toggle read-only mode" disabled={!activeDoc} onclick={toggleReadOnly}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7.5a4 4 0 0 1 8 0V11"/></svg>
      </button>
      <button id="find" class="icon-button" title="Find in file (Ctrl+F)" aria-label="Find in file" disabled={!activeDoc} onclick={() => openFind(false)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><circle cx="11" cy="11" r="7"/><path d="m20 20-4.35-4.35"/></svg>
      </button>
      <button id="find-all" class="icon-button" title="Find in open files (Ctrl+Shift+F)" aria-label="Find in open files" disabled={!activeDoc} onclick={() => openFind(true)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M4 6h10"/><path d="M4 10h6"/><path d="M4 14h5"/><circle cx="15.5" cy="14.5" r="4.5"/><path d="m19 18 3 3"/></svg>
      </button>
      <button id="hashtags" class="icon-button" class:active={hashtagsPanelOpen} aria-pressed={String(hashtagsPanelOpen)} title="Hashtags & Errands" aria-label="Hashtags & Errands" onclick={() => hashtagsPanelOpen ? closeHashtagsPanel() : openHashtagsPanel(hashtagsTab)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="4" y1="9" x2="20" y2="9"/><line x1="4" y1="15" x2="20" y2="15"/><line x1="10" y1="3" x2="8" y2="21"/><line x1="16" y1="3" x2="14" y2="21"/></svg>
      </button>
      <button id="indent-less" class="icon-button" title="Decrease indentation" aria-label="Decrease indentation" disabled={!activeDoc || readOnly} onclick={() => { indentLess(view); view.focus(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="4" y1="5" x2="4" y2="19"/><line x1="20" y1="12" x2="9" y2="12"/><polyline points="13 8 9 12 13 16"/></svg>
      </button>
      <button id="indent-more" class="icon-button" title="Increase indentation" aria-label="Increase indentation" disabled={!activeDoc || readOnly} onclick={() => { indentMore(view); view.focus(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="4" y1="5" x2="4" y2="19"/><line x1="8" y1="12" x2="19" y2="12"/><polyline points="15 8 19 12 15 16"/></svg>
      </button>
      <button id="toggle-todo" class="icon-button" title="Toggle todo ([ ] / [X])" aria-label="Toggle todo" disabled={!activeDoc || readOnly} onclick={toggleTodo}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="3" y="3" width="18" height="18" rx="2"/><polyline points="8.5 12.5 11 15 16 9.5"/></svg>
      </button>
      <button id="toggle-tasks-filter" class="icon-button" class:active={activeDoc?.tasksOnly} aria-pressed={String(Boolean(activeDoc?.tasksOnly))} title={activeDoc?.tasksOnly ? "Show all tasks (Ctrl+Alt+T)" : "Show uncompleted tasks only (Ctrl+Alt+T)"} aria-label="Show uncompleted tasks only" disabled={!activeDoc} onclick={() => toggleTasksFilter()}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="3" y="5" width="6" height="6" rx="1"/><line x1="12" y1="8" x2="21" y2="8"/><rect x="3" y="13" width="6" height="6" rx="1"/><line x1="12" y1="16" x2="21" y2="16"/></svg>
      </button>
      <button id="insert-date" class="icon-button" title="Insert date (YYYY-MM-DD)" aria-label="Insert date" disabled={!activeDoc || readOnly} onclick={() => insertSnippet(currentDateString())}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="3" y="5" width="18" height="16" rx="2"/><line x1="8" y1="3" x2="8" y2="7"/><line x1="16" y1="3" x2="16" y2="7"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
      </button>
      <button id="insert-time" class="icon-button" title="Insert time (h:mm AM/PM)" aria-label="Insert time" disabled={!activeDoc || readOnly} onclick={() => insertSnippet(currentTimeString())}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/></svg>
      </button>
      <button id="insert-divider" class="icon-button" title="Insert dividing line" aria-label="Insert dividing line" disabled={!activeDoc || readOnly} onclick={() => insertSnippet("-".repeat(preferences.dividerDashes))}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="3" y1="12" x2="7" y2="12"/><line x1="10" y1="12" x2="14" y2="12"/><line x1="17" y1="12" x2="21" y2="12"/></svg>
      </button>
      <button id="move-line-up" class="icon-button" title="Move line up (Alt+ArrowUp)" aria-label="Move line up" disabled={!activeDoc || readOnly || activeDoc?.tasksOnly} onclick={() => { moveLineUp(view); view.focus(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="4" y1="7" x2="11" y2="7"/><line x1="4" y1="12" x2="11" y2="12"/><line x1="4" y1="17" x2="11" y2="17"/><line x1="17" y1="19" x2="17" y2="5"/><polyline points="13.5 8.5 17 5 20.5 8.5"/></svg>
      </button>
      <button id="move-line-down" class="icon-button" title="Move line down (Alt+ArrowDown)" aria-label="Move line down" disabled={!activeDoc || readOnly || activeDoc?.tasksOnly} onclick={() => { moveLineDown(view); view.focus(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="4" y1="7" x2="11" y2="7"/><line x1="4" y1="12" x2="11" y2="12"/><line x1="4" y1="17" x2="11" y2="17"/><line x1="17" y1="5" x2="17" y2="19"/><polyline points="13.5 15.5 17 19 20.5 15.5"/></svg>
      </button>
      <span class="toolbar-spacer"></span>
      <button id="reload" class="icon-button" title="Reload from server" aria-label="Reload" disabled={!activeDoc || activeDoc.revision === null || activeDoc.saving} onclick={() => reloadFile().catch(failure)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><polyline points="21 3 21 9 15 9"/></svg>
      </button>
      <button id="download" class="icon-button" title="Download file" aria-label="Download" disabled={!activeDoc || activeDoc.revision === null} onclick={downloadActive}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
      </button>
      <button id="history" class="icon-button" title="Revision history" aria-label="History" disabled={!activeDoc || activeDoc.revision === null} onclick={showHistory}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/></svg>
      </button>
    </div>

    <div id="notice" class="notice" class:hidden={!noticeState.visible} class:error={noticeState.error} role="status">
      <span id="notice-text">
        {noticeState.text}
        {#if noticeState.expired}
          <a href="{prefix}/editor/login" target="_blank" rel="noopener"> Sign in again ↗</a>
        {/if}
      </span>
      <button id="notice-dismiss" class="text-button" aria-label="Dismiss message" onclick={() => (noticeState.visible = false)}>×</button>
    </div>

    <div id="remote-notice" class="remote-notice" class:hidden={!activeDoc?.remoteChanged} role="status">
      A newer server revision is available. Your unsaved edits are kept. Saving makes them the latest revision.
      <button id="merge-remote" class="text-button" title="Merge server version into unsaved edits" onclick={() => mergeRemoteFile().catch(failure)}>Merge</button>
      <button id="reload-remote" class="icon-button" title="Reload server version" aria-label="Reload server version" onclick={() => reloadFile().catch(failure)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><polyline points="21 3 21 9 15 9"/></svg>
      </button>
    </div>

    <div class="editor-body">
      {#if findOpen && activeDoc}
        <dialog id="find-dialog" class="find-dialog cm-search" open aria-label={findAllFiles ? "Find in open files" : "Find in file"}>
          <div class="find-dialog-header">
            <h2 id="find-dialog-title">{findAllFiles ? "Find in open files" : "Find in file"}</h2>
            <button id="find-close" name="close" type="button" class="icon-button" title="Close find" aria-label="Close find" onclick={closeFind}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="7" y1="7" x2="17" y2="17"/><line x1="17" y1="7" x2="7" y2="17"/></svg>
            </button>
          </div>
          <div class="find-dialog-row">
            <input
              id="find-input"
              name="search"
              type="search"
              placeholder="Find text…"
              aria-label="Find text"
              bind:this={findInputEl}
              bind:value={findQuery}
              oninput={() => syncSearchHighlight()}
              onkeydown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  findStep(!e.shiftKey);
                } else if (e.key === "Escape") {
                  e.preventDefault();
                  closeFind();
                }
              }}
            >
            <button id="find-prev" type="button" class="icon-button" title="Previous match (Shift+Enter)" aria-label="Previous match" onclick={() => findStep(false)}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="12" y1="19" x2="12" y2="5"/><polyline points="7 10 12 5 17 10"/></svg>
            </button>
            <button id="find-next" type="button" class="icon-button" title="Next match (Enter)" aria-label="Next match" onclick={() => findStep(true)}>
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="12" y1="5" x2="12" y2="19"/><polyline points="7 14 12 19 17 14"/></svg>
            </button>
          </div>
          <div class="find-dialog-footer">
            <label class="inline">
              <input type="checkbox" bind:checked={findMatchCase} onchange={() => syncSearchHighlight()}>
              Match case
            </label>
            <span id="find-result">{findResultText}</span>
          </div>
        </dialog>
      {/if}
      <div id="welcome" class="welcome" class:hidden={!!activeDoc}>
        <span class="welcome-mark" aria-hidden="true">T_</span>
        <h2>Your synced files,<br>ready to edit.</h2>
        <p>Choose a document from the sidebar,<br>or start a new file on your server.</p>
        <button id="welcome-new" class="primary icon-button" title="New server file" aria-label="New server file" onclick={newFile}>+</button>
        <p class="small muted">Files saved here are available to your synced devices.</p>
      </div>
      <div id="editor-host" class="editor-host" class:hidden={!activeDoc} aria-label="Document editor" bind:this={editorHost}></div>
    </div>

    <footer class="editor-status">
      <span id="cursor-status">{cursorStatus}</span>
      <div>
        <button id="encoding-status" class="text-button" disabled={!activeDoc} onclick={encodingSettings}>{encodingLabel}</button>
        <span id="revision-status">{revisionStatus}</span>
        <span id="save-status">{saveStatus}</span>
      </div>
    </footer>
  </main>

  <aside id="history-panel" class="history-panel" class:hidden={!historyDocument} aria-label="Revision history">
    <div class="history-heading">
      <h2>History</h2>
      <div>
        <button id="prune-history" class="icon-button" title="Prune old revisions" aria-label="Prune old revisions" onclick={pruneHistory}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
        </button>
        <button id="close-history" class="icon-button" aria-label="Close history" onclick={closeHistory}>×</button>
      </div>
    </div>
    <p class="small muted">Recent versions are kept; older history is thinned exponentially.</p>
    <div id="history-list" class="history-list">
      {#each historyRevisions as revision (revision.revision)}
        <button
          class="history-row"
          class:active={selectedRevision?.revision === revision.revision}
          onclick={() => selectHistoryRevision(revision)}
        >
          Revision {revision.revision}{revision.source ? ` · ${sourceLabel(revision.source)}` : ""}{revision.revision === activeDoc?.revision ? " · current" : ""}
          <span class="small">{date(revision.updated_at)} · {bytes(revision.size)}</span>
        </button>
      {/each}
    </div>
    <div id="revision-preview" class="revision-preview" class:fullscreen={fullscreenRevision} class:hidden={!selectedRevision}>
      <div class="revision-actions">
        <span id="preview-label">{selectedRevision ? `Revision ${selectedRevision.revision}` : ""}</span>
        <div>
          <button
            id="fullscreen-revision"
            class="icon-button"
            class:active={fullscreenRevision}
            aria-pressed={String(fullscreenRevision)}
            title={fullscreenRevision ? "Exit full screen" : "View full screen"}
            aria-label={fullscreenRevision ? "Exit full screen" : "View full screen"}
            disabled={!selectedRevision}
            onclick={() => (fullscreenRevision = !fullscreenRevision)}
          >
            {#if fullscreenRevision}
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><polyline points="4 14 10 14 10 20"/><polyline points="20 10 14 10 14 4"/><line x1="14" y1="10" x2="21" y2="3"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
            {:else}
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg>
            {/if}
          </button>
          <button
            id="download-revision"
            class="icon-button"
            title="Download revision"
            aria-label="Download revision"
            disabled={!selectedRevision}
            onclick={downloadRevision}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
          </button>
          <button
            id="restore-revision"
            class="primary icon-button"
            title="Restore revision"
            aria-label="Restore"
            disabled={!selectedRevision || selectedRevision.revision === activeDoc?.revision}
            onclick={() => restoreRevision().catch(failure)}
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><polyline points="3 3 3 8 8 8"/></svg>
          </button>
        </div>
      </div>
      <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
      <pre id="preview-text" tabindex="0">{previewText}</pre>
    </div>
  </aside>

  <aside id="hashtags-panel" class="hashtags-panel" class:hidden={!hashtagsPanelOpen} aria-label="Hashtags and Errands">
    <div class="history-heading">
      <h2>Hashtags &amp; Errands</h2>
      <div>
        <button id="refresh-hashtags" class="icon-button" title="Refresh hashtag search" aria-label="Refresh hashtag search" onclick={runHashtagSearch}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><polyline points="21 3 21 9 15 9"/></svg>
        </button>
        <button id="close-hashtags" class="icon-button" aria-label="Close hashtags" onclick={closeHashtagsPanel}>×</button>
      </div>
    </div>

    <div class="hashtag-tabs" role="tablist">
      <button
        type="button"
        role="tab"
        class:active={hashtagsTab === "search"}
        aria-selected={String(hashtagsTab === "search")}
        onclick={() => { hashtagsTab = "search"; runHashtagSearch(); }}
      >Search</button>
      <button
        type="button"
        role="tab"
        class:active={hashtagsTab === "errands"}
        aria-selected={String(hashtagsTab === "errands")}
        onclick={() => {
          hashtagsTab = "errands";
          if (errandsTags.length === 0) {
            editingErrands = true;
            errandsInput = "";
          }
          runHashtagSearch();
        }}
      >Errands{errandsTags.length ? ` (${errandsTags.length})` : ""}</button>
    </div>

    <div class="hashtag-controls">
      {#if hashtagsTab === "search"}
        <input
          id="hashtag-query-input"
          class="hashtag-input"
          type="search"
          placeholder="Search hashtags (e.g. #chemist #bunnings)…"
          aria-label="Hashtags to search"
          bind:value={hashtagQuery}
          oninput={runHashtagSearch}
        >
        {#if discoveredHashtags.length > 0}
          <div class="tag-chips" aria-label="Discovered hashtags">
            {#each discoveredHashtags as tag (tag)}
              {@const selected = parseTagListJs(hashtagQuery).includes(tag)}
              <button
                type="button"
                class="tag-chip"
                class:active={selected}
                onclick={() => toggleQueryHashtag(tag)}
              >#{tag}</button>
            {/each}
          </div>
        {/if}
      {:else}
        <div class="errands-box">
          <div class="errands-box-header">
            <span class="small muted">Errands hashtags (matches any)</span>
            {#if !editingErrands}
              <button
                id="edit-errands"
                type="button"
                class="text-button small"
                onclick={() => {
                  errandsInput = formatTagListJs(errandsTags);
                  editingErrands = true;
                }}
              >Edit</button>
            {/if}
          </div>
          {#if editingErrands}
            <textarea
              id="errands-textarea"
              aria-label="Errands hashtags"
              placeholder="#chemist #bunnings #postoffice #groceries"
              bind:value={errandsInput}
            ></textarea>
            {#if discoveredHashtags.length > 0}
              <div class="tag-chips" aria-label="Tap to add or remove hashtag">
                {#each discoveredHashtags as tag (tag)}
                  {@const inDraft = parseTagListJs(errandsInput).includes(tag)}
                  <button
                    type="button"
                    class="tag-chip"
                    class:active={inDraft}
                    onclick={() => {
                      const current = parseTagListJs(errandsInput);
                      const idx = current.indexOf(tag);
                      if (idx >= 0) current.splice(idx, 1);
                      else current.push(tag);
                      errandsInput = formatTagListJs(current);
                    }}
                  >#{tag}</button>
                {/each}
              </div>
            {/if}
            <div class="errands-actions">
              <button type="button" class="text-button small" onclick={() => (editingErrands = false)}>Cancel</button>
              <button id="save-errands" type="button" class="primary small" onclick={saveErrandsFromInput}>Save Errands</button>
            </div>
          {:else if errandsTags.length > 0}
            <div class="tag-chips">
              {#each errandsTags as tag (tag)}
                <span class="tag-chip active">#{tag}</span>
              {/each}
            </div>
          {:else}
            <p class="small muted">No errands hashtags configured yet. Add tags like #chemist or #bunnings to see all your errands in one place.</p>
          {/if}
        </div>
      {/if}

      <div class="hashtag-filter-row">
        <div class="mode-switch" role="group" aria-label="Match mode">
          <button
            type="button"
            class:active={hashtagMatchMode === "lines"}
            onclick={() => (hashtagMatchMode = "lines")}
          >Line matching</button>
          <button
            type="button"
            class:active={hashtagMatchMode === "files"}
            onclick={() => (hashtagMatchMode = "files")}
          >File matching</button>
        </div>
        <label class="inline small">
          <input
            id="hashtag-hide-completed"
            type="checkbox"
            bind:checked={hashtagHideCompleted}
            onchange={runHashtagSearch}
          >
          Hide [X]
        </label>
      </div>
    </div>

    <div id="hashtag-results" class="hashtag-results" aria-live="polite">
      {#if hashtagSearching}
        <p class="small muted">Searching open &amp; synced files…</p>
      {:else if hashtagsTab === "errands" && errandsTags.length === 0}
        <p class="small muted">Save your errands hashtags above to view matching lines or files.</p>
      {:else if hashtagsTab === "search" && parseTagListJs(hashtagQuery).length === 0}
        <p class="small muted">Select or type one or more hashtags above to search across open and synced files.</p>
      {:else if hashtagResults.length === 0}
        <p class="small muted">No matching {hashtagMatchMode === "lines" ? "lines" : "files"} found.</p>
      {:else if hashtagMatchMode === "files"}
        {#each hashtagResults as fileMatch (fileMatch.id)}
          <div class="hashtag-file-group">
            <button
              type="button"
              class="hashtag-file-header"
              onclick={() => jumpToHashtagResult(fileMatch.id, null)}
            >
              <span class="hashtag-file-name">{fileMatch.name}</span>
              <span class="hashtag-file-meta">
                {(fileMatch.matched_tags || []).map((t) => `#${t}`).join(" ")} · {fileMatch.lines.length} {fileMatch.lines.length === 1 ? "line" : "lines"}
              </span>
            </button>
          </div>
        {/each}
      {:else}
        {#each hashtagResults as fileMatch (fileMatch.id)}
          <div class="hashtag-file-group">
            <button
              type="button"
              class="hashtag-file-header"
              onclick={() => jumpToHashtagResult(fileMatch.id, null)}
            >
              <span class="hashtag-file-name">{fileMatch.name}</span>
              <span class="hashtag-file-meta">{fileMatch.lines.length} {fileMatch.lines.length === 1 ? "line" : "lines"}</span>
            </button>
            {#each fileMatch.lines as line (line.line_number)}
              <button
                type="button"
                class="hashtag-line-item"
                onclick={() => jumpToHashtagResult(fileMatch.id, line.line_number)}
              >
                <span class="hashtag-line-num">Line {line.line_number}</span>{line.text}
              </button>
            {/each}
          </div>
        {/each}
      {/if}
    </div>
  </aside>
</div>

<dialog id="modal" bind:this={dialogEl} oncancel={() => finishModal(null)}>
  <form id="modal-form" onsubmit={handleModalSubmit}>
    <h2 id="modal-title">{modalState?.title ?? ""}</h2>
    <div id="modal-body">
      {#if modalState?.type === "confirm"}
        <p>{modalState.message}</p>
      {:else if modalState?.type === "newFile"}
        <label for="modal-filename">Filename</label>
        <input id="modal-filename" maxlength="255" aria-label="Filename" bind:value={modalState.filename}>
        <p>The file joins your synced documents when you save it.</p>
      {:else if modalState?.type === "renameFile"}
        <label for="modal-rename">Filename</label>
        <input id="modal-rename" maxlength="255" aria-label="Filename" bind:value={modalState.filename}>
      {:else if modalState?.type === "encoding"}
        <label for="modal-encoding">Save encoding</label>
        <select id="modal-encoding" aria-label="Save encoding" bind:value={modalState.encoding}>
          {#each ["utf-8", "utf-16-le", "utf-16-be", "ascii"] as value}
            <option {value}>{encodingName(value)}</option>
          {/each}
        </select>
        <label class="inline">
          <input type="checkbox" bind:checked={modalState.bom}>
          Include byte-order mark (BOM)
        </label>
        <p>Existing line endings are preserved when saving.</p>
      {:else if modalState?.type === "settings"}
        <label for="modal-font">Font</label>
        <select id="modal-font" aria-label="Font" bind:value={modalState.font}>
          <option value="mono">Monospace</option>
          <option value="sans">Sans serif</option>
          <option value="serif">Serif</option>
        </select>
        <label for="modal-font-size">Font size</label>
        <input id="modal-font-size" type="number" min="10" max="30" aria-label="Font size" bind:value={modalState.fontSize}>
        <label for="modal-dashes">Dividing line dashes</label>
        <input id="modal-dashes" type="number" min="1" max="200" aria-label="Dividing line dashes" bind:value={modalState.dividerDashes}>
        <label class="inline">
          <input type="checkbox" bind:checked={modalState.wrap}>
          Word wrap
        </label>
        <label class="inline">
          <input type="checkbox" bind:checked={modalState.lines}>
          Line numbers
        </label>
      {:else if modalState?.type === "connect"}
        <p>In the Android app, open Sync → Server settings and enter these details.</p>
        <label for="modal-endpoint">Server address</label>
        <div class="copy-row">
          <input id="modal-endpoint" type="text" readonly aria-label="Server address" value={modalState.endpoint}>
          <button
            type="button"
            onclick={async () => {
              try {
                await navigator.clipboard.writeText(modalState.endpoint);
                modalState.copiedEndpoint = true;
              } catch {}
            }}
          >
            {modalState.copiedEndpoint ? "Copied" : "Copy"}
          </button>
        </div>
        <label for="modal-token">API token</label>
        <div class="copy-row">
          <input id="modal-token" type={modalState.showToken ? "text" : "password"} readonly aria-label="API token" value={modalState.token}>
          <button
            type="button"
            onclick={async () => {
              try {
                await navigator.clipboard.writeText(modalState.token);
                modalState.copiedToken = true;
              } catch {
                modalState.showToken = true;
              }
            }}
          >
            {modalState.copiedToken ? "Copied" : "Copy"}
          </button>
        </div>
        <button type="button" class="text-button" onclick={() => (modalState.showToken = !modalState.showToken)}>
          {modalState.showToken ? "Hide token" : "Show token"}
        </button>
      {/if}
    </div>
    <div id="modal-actions" class="modal-actions">
      {#each modalState?.actions ?? [] as action}
        <button
          type="button"
          class:primary={action.primary}
          class:danger={action.danger}
          onclick={() => resolveModalAction(action.value)}
        >
          {action.label}
        </button>
      {/each}
    </div>
  </form>
</dialog>
