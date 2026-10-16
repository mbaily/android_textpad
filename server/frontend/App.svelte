<script>
  import { onMount } from "svelte";
  import { SvelteMap } from "svelte/reactivity";
  import { EditorState, Compartment, EditorSelection } from "@codemirror/state";
  import { EditorView, keymap, lineNumbers, highlightActiveLineGutter, drawSelection, rectangularSelection } from "@codemirror/view";
  import { defaultKeymap, history, historyKeymap, undo, redo, undoDepth, redoDepth, indentMore, indentLess, indentWithTab } from "@codemirror/commands";
  import { search, SearchQuery, setSearchQuery, openSearchPanel, closeSearchPanel } from "@codemirror/search";

  const prefix = document.body.dataset.prefix || "";
  const apiBase = `${prefix}/editor/api`;
  const csrf = document.querySelector('meta[name="csrf-token"]')?.content || "";
  const nonce = document.querySelector('meta[name="csp-nonce"]')?.content || "";

  const wrapCompartment = new Compartment();
  const guttersCompartment = new Compartment();
  const fonts = {
    mono: 'ui-monospace,"SFMono-Regular",Consolas,monospace',
    sans: "system-ui,sans-serif",
    serif: "Georgia,serif",
  };

  let initialPrefs = { wrap: true, lines: true, fontSize: 15, font: "mono", dividerDashes: 40 };
  try {
    initialPrefs = { ...initialPrefs, ...JSON.parse(localStorage.getItem("textpad.editor.preferences") || "{}") };
  } catch {}

  let initialCollapsed = false;
  try {
    initialCollapsed = localStorage.getItem("textpad.editor.sidebarCollapsed") === "true";
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

  let editorHost = $state(null);
  let dialogEl = $state(null);
  let modalState = $state(null);

  let findOpen = $state(false);
  let findAllFiles = $state(false);
  let findQuery = $state("");
  let findMatchCase = $state(false);
  let findResultText = $state("");
  let findInputEl = $state(null);

  let cursorLine = $state(1);
  let cursorCol = $state(1);
  let lineCount = $state(1);
  let canUndo = $state(false);
  let canRedo = $state(false);

  let view = null;
  let viewDocument = null;
  let opening = null;
  let polling = false;
  let refreshAgain = false;
  let events = null;
  let reconnectTimer = null;
  let reconnectAttempts = 0;

  const activeDoc = $derived(active ? documents.get(active) ?? null : null);
  const filteredFiles = $derived.by(() => {
    const q = fileFilter.toLocaleLowerCase();
    return files.filter((file) => file.name.toLocaleLowerCase().includes(q));
  });
  const connectionLabel = $derived(
    apiOnline ? (eventsReady ? "Connected · live updates" : "Connected to server") : "Connection unavailable"
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
    !activeDoc ? "Ready" : activeDoc.saving ? "Saving…" : activeDoc.dirty ? "Unsaved changes" : "Saved"
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
        headers: { "Content-Type": "application/json", "X-CSRF-Token": csrf, ...options.headers },
      });
    } catch {
      apiOnline = false;
      throw new Error("Connection lost. Your open edits are still here. Reconnect and retry.");
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

  function makeState(doc, text, selection) {
    return EditorState.create({
      doc: text,
      selection,
      extensions: [
        history(),
        drawSelection(),
        rectangularSelection(),
        highlightActiveLineGutter(),
        EditorState.tabSize.of(4),
        EditorView.cspNonce.of(nonce),
        EditorView.contentAttributes.of({ "aria-label": "Document text", spellcheck: "false" }),
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
          ...historyKeymap,
          indentWithTab,
          ...defaultKeymap,
        ]),
        EditorView.updateListener.of((update) => {
          if (documents.get(doc.id) !== doc) return;
          doc.state = update.state;
          if (update.docChanged) {
            doc.dirty =
              doc.revision === null ||
              !update.state.doc.eq(doc.savedDoc) ||
              doc.name !== doc.savedName ||
              doc.encoding !== doc.savedEncoding ||
              doc.bom !== doc.savedBom;
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
      pending: null,
      savedName: metadata.name,
      savedEncoding: metadata.encoding,
      savedBom: metadata.bom,
      state: null,
      savedDoc: null,
      scrollTop: 0,
      scrollLeft: 0,
    });
    const initialState = makeState(doc, metadata.text);
    doc.state = initialState;
    doc.savedDoc = initialState.doc;
    return doc;
  }

  function activate(id) {
    const before = viewDocument;
    if (before && view) {
      before.state = view.state;
      before.scrollTop = view.scrollDOM.scrollTop;
      before.scrollLeft = view.scrollDOM.scrollLeft;
    }
    active = id;
    const doc = id ? documents.get(id) ?? null : null;
    viewDocument = doc;
    if (doc && view) {
      view.setState(doc.state);
      view.scrollDOM.scrollTop = doc.scrollTop || 0;
      view.scrollDOM.scrollLeft = doc.scrollLeft || 0;
      if (findOpen) syncSearchHighlight();
      else view.focus();
    } else if (!doc && findOpen) {
      findOpen = false;
      findResultText = "";
    }
    syncEditorMetrics();
    if (historyDocument && historyDocument !== id) closeHistory();
  }

  async function openFile(id) {
    opening = id;
    if (documents.has(id)) {
      activate(id);
      return;
    }
    try {
      const metadata = await api(`/documents/${encodeURIComponent(id)}`);
      if (!documents.has(id)) documents.set(id, createDocModel(metadata));
      if (opening === id) activate(id);
      const remote = files.find((file) => file.id === id);
      if (remote && documents.get(id).revision !== remote.revision) refreshFiles().catch(failure);
    } catch (error) {
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
    const name = (result.filename ?? "").trim();
    if (!name || /[/\\\x00-\x1f]/.test(name)) {
      notice("Choose a filename without slashes or control characters.", true);
      return;
    }
    opening = null;
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

  async function save(doc = activeDoc) {
    if (!doc || doc.saving) return;
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
      doc.revision = result.revision;
      doc.sha256 = result.sha256;
      doc.size = result.size;
      doc.updated_at = result.updated_at;
      doc.line_endings = result.line_endings;
      doc.savedDoc = pending.doc;
      doc.savedName = pending.body.name;
      doc.savedEncoding = pending.body.encoding;
      doc.savedBom = pending.body.bom;
      doc.pending = null;
      doc.remoteChanged = false;
      files = [result, ...files.filter((file) => file.id !== result.id)];
      doc.dirty =
        !doc.state.doc.eq(doc.savedDoc) ||
        doc.name !== doc.savedName ||
        doc.encoding !== doc.savedEncoding ||
        doc.bom !== doc.savedBom;
      if (result.replaced_newer_revision) {
        notice("Saved as the latest revision. The displaced server version is available in History.");
      }
      await refreshFiles();
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
    const metadata = await api(`/documents/${doc.id}`);
    if (documents.get(doc.id) !== doc || doc.saving || !doc.state.doc.eq(before) || (!prompt && doc.dirty)) {
      const current = documents.get(doc.id);
      if (current) current.remoteChanged = true;
      return;
    }
    const selection = doc.state.selection.main;
    const next = createDocModel(metadata);
    const nextState = makeState(
      next,
      metadata.text,
      EditorSelection.range(Math.min(selection.anchor, metadata.text.length), Math.min(selection.head, metadata.text.length))
    );
    next.state = nextState;
    next.savedDoc = nextState.doc;
    next.scrollTop = doc.scrollTop;
    next.scrollLeft = doc.scrollLeft;
    documents.set(doc.id, next);
    if (active === doc.id) activate(doc.id);
  }

  async function refreshFiles() {
    if (polling) {
      refreshAgain = true;
      return;
    }
    polling = true;
    try {
      const result = await api("/documents");
      files = result.documents;
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
    } finally {
      polling = false;
      if (refreshAgain) {
        refreshAgain = false;
        refreshFiles().catch(failure);
      }
    }
  }

  function closeHistory() {
    historyDocument = null;
    historyRevisions = [];
    selectedRevision = null;
    previewText = "";
  }

  async function showHistory() {
    const doc = activeDoc;
    if (!doc?.revision) return;
    historyDocument = doc.id;
    selectedRevision = null;
    previewText = "";
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
      const name = (result.filename ?? "").trim();
      if (!name || /[/\\\x00-\x1f]/.test(name)) {
        notice("Choose a filename without slashes or control characters.", true);
        return;
      }
      doc.name = name;
      doc.dirty = true;
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
      doc.bom = result.bom && result.encoding !== "ascii";
      doc.dirty = true;
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

  function insertSnippet(snippet) {
    if (!activeDoc || !view) return;
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

  function toggleTodo() {
    if (!activeDoc || !view) return;
    const sel = view.state.selection.main;
    const line = view.state.doc.lineAt(sel.head);
    const text = line.text;
    let from = line.from;
    let to = line.from;
    let insert = "";
    let nextHead = sel.head;
    if (text.startsWith("[ ]")) {
      to = line.from + 3;
      insert = "[X]";
      nextHead = Math.max(line.from + 3, sel.head);
    } else if (text.startsWith("[X]") || text.startsWith("[x]")) {
      const removeLen = text.startsWith("[X] ") || text.startsWith("[x] ") ? 4 : 3;
      to = line.from + removeLen;
      nextHead = Math.max(line.from, sel.head - removeLen);
    } else {
      insert = "[ ] ";
      nextHead = sel.head + 4;
    }
    view.dispatch({
      changes: { from, to, insert },
      selection: { anchor: nextHead, head: nextHead },
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

  function findStep(forward = true) {
    if (!activeDoc || !view) return;
    if (!findQuery) {
      findResultText = "";
      syncSearchHighlight();
      return;
    }
    const sel = view.state.selection.main;
    const curMatches = findMatchesInText(view.state.doc.toString(), findQuery, findMatchCase);

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
      const matches = findMatchesInText(text, findQuery, findMatchCase);
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
    connectEvents();

    const onBeforeUnload = (event) => {
      if ([...documents.values()].some((d) => d.dirty || d.saving)) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    const onKeyDown = (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        save().catch(failure);
      } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f") {
        if (activeDoc) {
          event.preventDefault();
          openFind(event.shiftKey);
        }
      }
    };
    const onOnline = () => {
      closeEvents();
      connectEvents();
      refreshFiles().catch(failure);
    };
    const onVisibilityChange = () => {
      if (document.hidden) closeEvents();
      else {
        connectEvents();
        refreshFiles().catch(failure);
      }
    };

    window.addEventListener("beforeunload", onBeforeUnload);
    window.addEventListener("keydown", onKeyDown);
    window.addEventListener("online", onOnline);
    window.addEventListener("pagehide", closeEvents);
    window.addEventListener("pageshow", connectEvents);
    document.addEventListener("visibilitychange", onVisibilityChange);

    const pollInterval = setInterval(() => {
      if (!document.hidden && !eventsReady) refreshFiles().catch(failure);
    }, 30000);

    return () => {
      clearInterval(pollInterval);
      window.removeEventListener("beforeunload", onBeforeUnload);
      window.removeEventListener("keydown", onKeyDown);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("pagehide", closeEvents);
      window.removeEventListener("pageshow", connectEvents);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      closeEvents();
      view?.destroy();
    };
  });
</script>

<div class="workspace">
  <aside id="file-sidebar" class="sidebar" aria-label="Synced files" hidden={sidebarCollapsed}>
    <a class="brand" href="{prefix}/"><span class="brand-mark" aria-hidden="true">T_</span> Textpad <span class="edition">WEB</span></a>
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
      <button id="save" class="primary icon-button" title="Save (Ctrl+S)" aria-label="Save" disabled={!activeDoc || activeDoc.saving} onclick={() => save().catch(failure)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/></svg>
      </button>
      <button id="undo" class="icon-button" title="Undo (Ctrl+Z)" aria-label="Undo" disabled={!activeDoc || !canUndo} onclick={() => { undo(view); view.focus(); syncEditorMetrics(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M3 9h11a5 5 0 0 1 0 10H9"/><polyline points="7 5 3 9 7 13"/></svg>
      </button>
      <button id="redo" class="icon-button" title="Redo (Ctrl+Shift+Z)" aria-label="Redo" disabled={!activeDoc || !canRedo} onclick={() => { redo(view); view.focus(); syncEditorMetrics(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M21 9H10a5 5 0 0 0 0 10h5"/><polyline points="17 5 21 9 17 13"/></svg>
      </button>
      <span class="toolbar-divider"></span>
      <button id="find" class="icon-button" title="Find in file (Ctrl+F)" aria-label="Find in file" disabled={!activeDoc} onclick={() => openFind(false)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><circle cx="11" cy="11" r="7"/><path d="m20 20-4.35-4.35"/></svg>
      </button>
      <button id="find-all" class="icon-button" title="Find in open files (Ctrl+Shift+F)" aria-label="Find in open files" disabled={!activeDoc} onclick={() => openFind(true)}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M4 6h10"/><path d="M4 10h6"/><path d="M4 14h5"/><circle cx="15.5" cy="14.5" r="4.5"/><path d="m19 18 3 3"/></svg>
      </button>
      <button id="indent-less" class="icon-button" title="Decrease indentation" aria-label="Decrease indentation" disabled={!activeDoc} onclick={() => { indentLess(view); view.focus(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="4" y1="5" x2="4" y2="19"/><line x1="20" y1="12" x2="9" y2="12"/><polyline points="13 8 9 12 13 16"/></svg>
      </button>
      <button id="indent-more" class="icon-button" title="Increase indentation" aria-label="Increase indentation" disabled={!activeDoc} onclick={() => { indentMore(view); view.focus(); }}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="4" y1="5" x2="4" y2="19"/><line x1="8" y1="12" x2="19" y2="12"/><polyline points="15 8 19 12 15 16"/></svg>
      </button>
      <button id="toggle-todo" class="icon-button" title="Toggle todo ([ ] / [X])" aria-label="Toggle todo" disabled={!activeDoc} onclick={toggleTodo}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="3" y="3" width="18" height="18" rx="2"/><polyline points="8.5 12.5 11 15 16 9.5"/></svg>
      </button>
      <button id="insert-date" class="icon-button" title="Insert date (YYYY-MM-DD)" aria-label="Insert date" disabled={!activeDoc} onclick={() => insertSnippet(currentDateString())}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><rect x="3" y="5" width="18" height="16" rx="2"/><line x1="8" y1="3" x2="8" y2="7"/><line x1="16" y1="3" x2="16" y2="7"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
      </button>
      <button id="insert-divider" class="icon-button" title="Insert dividing line" aria-label="Insert dividing line" disabled={!activeDoc} onclick={() => insertSnippet("-".repeat(preferences.dividerDashes))}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><line x1="3" y1="12" x2="7" y2="12"/><line x1="10" y1="12" x2="14" y2="12"/><line x1="17" y1="12" x2="21" y2="12"/></svg>
      </button>
      <button id="go-bottom" class="icon-button" title="Go to bottom of file" aria-label="Go to bottom of file" disabled={!activeDoc} onclick={goToBottom}>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="M12 4v12"/><path d="m7 11 5 5 5-5"/><path d="M5 20h14"/></svg>
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
      <button id="close-history" class="icon-button" aria-label="Close history" onclick={closeHistory}>×</button>
    </div>
    <p class="small muted">Older saved versions stay here.</p>
    <div id="history-list" class="history-list">
      {#each historyRevisions as revision (revision.revision)}
        <button
          class="history-row"
          class:active={selectedRevision?.revision === revision.revision}
          onclick={() => selectHistoryRevision(revision)}
        >
          Revision {revision.revision}{revision.revision === activeDoc?.revision ? " · current" : ""}
          <span class="small">{date(revision.updated_at)} · {bytes(revision.size)}</span>
        </button>
      {/each}
    </div>
    <div id="revision-preview" class="revision-preview" class:hidden={!selectedRevision}>
      <div class="revision-actions">
        <span id="preview-label">{selectedRevision ? `Revision ${selectedRevision.revision}` : ""}</span>
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
      <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
      <pre id="preview-text" tabindex="0">{previewText}</pre>
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
