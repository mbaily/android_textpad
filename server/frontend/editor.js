import {EditorState, Compartment, EditorSelection} from "@codemirror/state";
import {EditorView, keymap, lineNumbers, highlightActiveLineGutter, drawSelection, rectangularSelection} from "@codemirror/view";
import {defaultKeymap, history, historyKeymap, undo, redo, undoDepth, redoDepth, indentMore, indentLess, indentWithTab} from "@codemirror/commands";
import {search, searchKeymap, openSearchPanel} from "@codemirror/search";

const $ = id => document.getElementById(id);
const prefix = document.body.dataset.prefix;
const apiBase = `${prefix}/editor/api`;
const csrf = document.querySelector('meta[name="csrf-token"]').content;
const nonce = document.querySelector('meta[name="csp-nonce"]').content;
const documents = new Map();
let active = null, viewDocument = null, files = [], opening = null, polling = false, refreshAgain = false, selectedRevision = null, historyDocument = null;
let events = null, eventsReady = false, reconnectTimer = null, reconnectAttempts = 0, apiOnline = false;
let preferences = {wrap:true, lines:true, fontSize:15, font:"mono"};
try { preferences = {...preferences,...JSON.parse(localStorage.getItem("textpad.editor.preferences") || "{}")}; } catch {}
let sidebarCollapsed = false;
try { sidebarCollapsed = localStorage.getItem("textpad.editor.sidebarCollapsed") === "true"; } catch {}
const wrap = new Compartment(), gutters = new Compartment();
const fonts = {mono:'ui-monospace,"SFMono-Regular",Consolas,monospace', sans:'system-ui,sans-serif', serif:'Georgia,serif'};

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function bytes(size) { return size >= 1024*1024 ? `${(size/1024/1024).toFixed(1)} MiB` : size >= 1024 ? `${(size/1024).toFixed(1)} KiB` : `${size} B`; }
function date(value) { return new Date(value).toLocaleString(undefined,{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit"}); }
function encodingName(value) { return ({"utf-8":"UTF-8","utf-16-le":"UTF-16 LE","utf-16-be":"UTF-16 BE",ascii:"ASCII"})[value] || value; }
function notice(text, error=false, expired=false) {
  $("notice").classList.remove("hidden"); $("notice").classList.toggle("error",error);
  $("notice-text").textContent = text;
  if (expired) {
    const link = element("a"," Sign in again ↗"); link.href=`${prefix}/editor/login`; link.target="_blank"; link.rel="noopener";
    $("notice-text").append(link);
  }
}
async function api(path, options={}) {
  let response;
  try { response = await fetch(apiBase+path,{...options,credentials:"same-origin",headers:{"Content-Type":"application/json","X-CSRF-Token":csrf,...options.headers}}); }
  catch { setConnection(false); throw new Error("Connection lost. Your open edits are still here. Reconnect and retry."); }
  let data;
  try { data = await response.json(); } catch { throw new Error(`The server returned HTTP ${response.status}. Your edits are still open.`); }
  if (!response.ok) {
    const error = new Error(data.error || `Server returned HTTP ${response.status}.`); error.expired=response.status===401;
    throw error;
  }
  setConnection(true); return data;
}
function failure(error) { notice(error.message,true,error.expired); }
function setConnection(online) { apiOnline=online; $("connection-label").textContent=online?(eventsReady?"Connected · live updates":"Connected to server"):"Connection unavailable"; $("connection-dot").classList.toggle("offline",!online); }

function closeEvents() {
  clearTimeout(reconnectTimer);reconnectTimer=null;eventsReady=false;
  const before=events;events=null;if(before)before.close();setConnection(apiOnline);
}
function connectEvents() {
  if(document.hidden || events || reconnectTimer) return;
  const address=new URL(`${apiBase}/events`,window.location.href);address.protocol=location.protocol==="https:"?"wss:":"ws:";
  const socket=new WebSocket(address);events=socket;
  socket.onmessage=event=>{
    if(events!==socket) return;
    try {
      const message=JSON.parse(event.data);
      if(message.type==="ready" || message.type==="changed") {
        eventsReady=true;reconnectAttempts=0;setConnection(apiOnline);refreshFiles().catch(failure);
      }
    }catch{}
  };
  socket.onclose=()=>{
    if(events!==socket) return;
    events=null;eventsReady=false;setConnection(apiOnline);
    if(!document.hidden) reconnectTimer=setTimeout(()=>{reconnectTimer=null;connectEvents();},Math.min(60000,1000*2**Math.min(reconnectAttempts++,6)));
  };
  socket.onerror=()=>socket.close();
}

let resolveModal = null;
function modal(title, build, actions=[{label:"Cancel",value:null}]) {
  if ($("modal").open) { $("modal").close(); resolveModal?.(null); }
  $("modal-title").textContent=title; $("modal-body").replaceChildren(); $("modal-actions").replaceChildren();
  build($("modal-body"));
  return new Promise(resolve => {
    resolveModal=resolve;
    const finish=value=>{ $("modal").close(); resolveModal=null; resolve(value); };
    for (const action of actions) {
      const button=element("button",action.label,action.primary?"primary":action.danger?"danger":""); button.type="button";
      button.onclick=()=>finish(action.value); $("modal-actions").append(button);
    }
    $("modal-form").onsubmit=event=>{ event.preventDefault(); const action=actions.find(a=>a.primary); if(action) finish(action.value); };
    $("modal").oncancel=()=>finish(null); $("modal").showModal();
  });
}
async function confirm(title,message,label="Continue") {
  return await modal(title,body=>body.append(element("p",message)),[{label:"Cancel",value:false},{label,value:true,primary:true}])===true;
}

function makeState(doc, text, selection) {
  return EditorState.create({doc:text,selection,extensions:[
    history(),drawSelection(),rectangularSelection(),highlightActiveLineGutter(),
    EditorState.tabSize.of(4),EditorView.cspNonce.of(nonce),
    EditorView.contentAttributes.of({"aria-label":"Document text",spellcheck:"false"}),
    EditorView.theme({"&":{color:"#e3e8df"}},{dark:true}),
    wrap.of(preferences.wrap?EditorView.lineWrapping:[]),gutters.of(preferences.lines?lineNumbers():[]),
    search({top:true}),
    keymap.of([{key:"Mod-s",run:()=>{save(doc).catch(failure);return true;}},...historyKeymap,...searchKeymap,indentWithTab,...defaultKeymap]),
    EditorView.updateListener.of(update=>{
      if(documents.get(doc.id)!==doc) return;
      doc.state=update.state;
      if(update.docChanged) {
        doc.dirty=doc.revision===null || !update.state.doc.eq(doc.savedDoc) || doc.name!==doc.savedName || doc.encoding!==doc.savedEncoding || doc.bom!==doc.savedBom;
        renderTabs();
      }
      if(active===doc.id) updateStatus();
    })
  ]});
}
const view = new EditorView({parent:$("editor-host"),state:EditorState.create({doc:"",extensions:[EditorView.cspNonce.of(nonce)]})});
function activeDocument() { return documents.get(active); }
function activate(id) {
  const before=viewDocument; if(before) {before.state=view.state;before.scrollTop=view.scrollDOM.scrollTop;before.scrollLeft=view.scrollDOM.scrollLeft;}
  active=id; const doc=activeDocument();
  viewDocument=doc||null;
  $("welcome").classList.toggle("hidden",!!doc); $("editor-host").classList.toggle("hidden",!doc);
  if(doc) {
    view.setState(doc.state);
    view.scrollDOM.scrollTop=doc.scrollTop||0; view.scrollDOM.scrollLeft=doc.scrollLeft||0;
    view.focus();
  }
  renderTabs(); renderFiles(); updateStatus();
  if(historyDocument && historyDocument!==id) closeHistory();
}
function loaded(metadata) {
  const doc={...metadata,dirty:false,saving:false,remoteChanged:false,pending:null,savedName:metadata.name,savedEncoding:metadata.encoding,savedBom:metadata.bom};
  doc.state=makeState(doc,metadata.text); doc.savedDoc=doc.state.doc; return doc;
}
async function openFile(id) {
  opening=id;
  if(documents.has(id)) {activate(id);return;}
  try {
    const metadata=await api(`/documents/${encodeURIComponent(id)}`);
    if(!documents.has(id)) documents.set(id,loaded(metadata));
    if(opening===id) activate(id);
    const remote=files.find(file=>file.id===id);
    if(remote && documents.get(id).revision!==remote.revision) refreshFiles().catch(failure);
  } catch(error) {failure(error);}
}
async function newFile() {
  const input=element("input"); input.value="Untitled.txt"; input.maxLength=255; input.setAttribute("aria-label","Filename");
  const result=await modal("New server file",body=>{body.append(element("label","Filename"),input);body.append(element("p","The file joins your synced documents when you save it."));},[{label:"Cancel",value:false},{label:"Create",value:true,primary:true}]);
  if(!result) return;
  const name=input.value.trim(); if(!name || /[/\\\x00-\x1f]/.test(name)) {notice("Choose a filename without slashes or control characters.",true);return;}
  opening=null;
  const doc=loaded({id:crypto.randomUUID(),name,revision:null,text:"",encoding:"utf-8",bom:false,line_endings:"LF",size:0});
  doc.dirty=true; documents.set(doc.id,doc); activate(doc.id);
}
async function closeFile(id) {
  const doc=documents.get(id); if(doc.saving) {notice("Wait for this file to finish saving.");return;}
  if(doc.dirty) {
    const action=await modal(`Save changes to ${doc.name}?`,body=>body.append(element("p","Closing this tab discards its unsaved edits.")),[{label:"Cancel",value:null},{label:"Discard",value:"discard",danger:true},{label:"Save",value:"save",primary:true}]);
    if(!action) return;
    if(action==="save") {try {await save(doc);} catch(error){failure(error);return;} if(doc.dirty) return;}
  }
  documents.delete(id); if(active===id) activate(documents.keys().next().value||null); else renderTabs();
}
function renderTabs() {
  $("tabs").replaceChildren();
  if(!documents.size) $("tabs").append(element("span","A little space to write.","tabs-placeholder"));
  for(const doc of documents.values()) {
    const tab=element("div",undefined,"tab"+(active===doc.id?" active":""));
    const main=element("button",undefined,"tab-main");main.setAttribute("role","tab");main.setAttribute("aria-selected",String(active===doc.id));main.title=doc.name;
    if(doc.dirty) main.append(element("span","●","dirty-dot"));main.append(element("span",doc.name,"tab-name"));main.onclick=()=>activate(doc.id);
    const close=element("button","×","tab-close");close.setAttribute("aria-label",`Close ${doc.name}`);close.onclick=()=>closeFile(doc.id);
    tab.append(main,close);$("tabs").append(tab);
  }
}
function renderFiles() {
  const filter=$("file-filter").value.toLocaleLowerCase(); const list=$("file-list");list.replaceChildren();
  $("file-count").textContent=String(files.length);
  const filtered=files.filter(file=>file.name.toLocaleLowerCase().includes(filter));
  for(const file of filtered) {
    const row=element("button",undefined,"file-item"+(active===file.id?" selected":""));row.dataset.id=file.id;row.title=`${file.name}\n${file.id}`;
    row.append(element("span",file.name,"file-name"),element("span",`${bytes(file.size)} · ${date(file.updated_at)}`,"file-meta"));row.onclick=()=>openFile(file.id);list.append(row);
  }
  if(!filtered.length) list.append(element("p",filter?"No matching files.":"No synced files yet. Create one here, or sync a file from Android.","muted small"));
}
function updateStatus() {
  const doc=activeDocument();
  for(const name of ["save","find","indent-less","indent-more","go-bottom","reload","download","history","encoding-status"]) $(name).disabled=!doc;
  $("undo").disabled=!doc || !undoDepth(view.state);$("redo").disabled=!doc || !redoDepth(view.state);
  $("save").disabled=!doc || doc.saving;$("reload").disabled=!doc || doc.revision===null || doc.saving;
  $("download").disabled=!doc || doc.revision===null;$("history").disabled=!doc || doc.revision===null;
  $("remote-notice").classList.toggle("hidden",!doc?.remoteChanged);
  if(!doc) {$("cursor-status").textContent="No document open";$("revision-status").textContent="";$("save-status").textContent="Ready";document.title="Textpad · Synced files";return;}
  const cursor=view.state.selection.main.head,line=view.state.doc.lineAt(cursor);
  $("cursor-status").textContent=`Ln ${line.number}, Col ${cursor-line.from+1}  ·  ${view.state.doc.lines.toLocaleString()} lines`;
  $("encoding-status").textContent=`${encodingName(doc.encoding)}${doc.bom?" BOM":""} · ${doc.line_endings}`;
  $("revision-status").textContent=doc.revision?`Revision ${doc.revision}`:"New file";
  $("save-status").textContent=doc.saving?"Saving…":doc.dirty?"Unsaved changes":"Saved";
  document.title=`${doc.dirty?"● ":""}${doc.name} · Textpad`;
}
async function save(doc=activeDocument()) {
  if(!doc || doc.saving) return;
  const state=doc.state;
  let pending=doc.pending;
  if(!pending || !pending.doc.eq(state.doc) || pending.body.name!==doc.name || pending.body.encoding!==doc.encoding || pending.body.bom!==doc.bom) {
    pending={doc:state.doc,body:{name:doc.name,text:state.doc.toString(),encoding:doc.encoding,bom:doc.bom,base_revision:doc.revision,request_id:crypto.randomUUID()}}; doc.pending=pending;
  }
  doc.saving=true;updateStatus();
  try {
    const result=await api(`/documents/${doc.id}`,{method:"PUT",body:JSON.stringify(pending.body)});
    doc.revision=result.revision;doc.sha256=result.sha256;doc.size=result.size;doc.updated_at=result.updated_at;
    doc.line_endings=result.line_endings;doc.savedDoc=pending.doc;doc.savedName=pending.body.name;doc.savedEncoding=pending.body.encoding;doc.savedBom=pending.body.bom;
    doc.pending=null;doc.remoteChanged=false;
    files=[result,...files.filter(file=>file.id!==result.id)];renderFiles();
    doc.dirty=!doc.state.doc.eq(doc.savedDoc)||doc.name!==doc.savedName||doc.encoding!==doc.savedEncoding||doc.bom!==doc.savedBom;
    if(result.replaced_newer_revision) notice("Saved as the latest revision. The displaced server version is available in History.");
    await refreshFiles();
  } finally {
    doc.saving=false;renderTabs();updateStatus();
    if(doc.remoteChanged && !doc.dirty && !doc.pending) refreshFiles().catch(failure);
  }
}
async function reloadFile(doc=activeDocument(),prompt=true) {
  if(!doc || doc.revision===null || doc.saving) return;
  if(prompt && doc.dirty && !await confirm(`Reload ${doc.name}?`,"Discard unsaved edits and load the current saved server version?","Reload")) return;
  const before=doc.state.doc;
  const metadata=await api(`/documents/${doc.id}`);
  if(documents.get(doc.id)!==doc || doc.saving || !doc.state.doc.eq(before) || (!prompt&&doc.dirty)) {
    const current=documents.get(doc.id);if(current)current.remoteChanged=true;updateStatus();return;
  }
  const selection=doc.state.selection.main;
  const next=loaded(metadata);next.state=makeState(next,metadata.text,EditorSelection.range(Math.min(selection.anchor,metadata.text.length),Math.min(selection.head,metadata.text.length)));
  next.savedDoc=next.state.doc;next.scrollTop=doc.scrollTop;next.scrollLeft=doc.scrollLeft;
  documents.set(doc.id,next);if(active===doc.id) activate(doc.id);else renderTabs();
}
async function refreshFiles() {
  if(polling) {refreshAgain=true;return;}polling=true;
  try {
    const result=await api("/documents");files=result.documents;renderFiles();
    for(const remote of files) {
      const doc=documents.get(remote.id);
      if(!doc || !doc.revision || doc.revision===remote.revision) continue;
      if(doc.dirty || doc.saving || doc.pending) {doc.remoteChanged=true;continue;}
      await reloadFile(doc,false);
    }
    updateStatus();
  } finally {polling=false;if(refreshAgain){refreshAgain=false;refreshFiles().catch(failure);}}
}
function closeHistory() {$("history-panel").classList.add("hidden");historyDocument=null;selectedRevision=null;}
async function showHistory() {
  const doc=activeDocument();if(!doc?.revision) return;
  historyDocument=doc.id;selectedRevision=null;$("revision-preview").classList.add("hidden");$("history-list").replaceChildren();$("history-panel").classList.remove("hidden");
  try {
    const result=await api(`/documents/${doc.id}/revisions`);if(historyDocument!==doc.id) return;
    for(const revision of result.documents) {
      const row=element("button",`Revision ${revision.revision}${revision.revision===doc.revision?" · current":""}`,"history-row");
      row.append(element("span",`${date(revision.updated_at)} · ${bytes(revision.size)}`,"small"));
      row.onclick=async()=>{
        try {
          const content=await api(`/documents/${doc.id}/revisions/${revision.revision}`);if(historyDocument!==doc.id) return;
          selectedRevision=revision;
          for(const other of $("history-list").children) other.classList.toggle("active",other===row);
          $("preview-text").textContent=content.text;$("preview-label").textContent=`Revision ${revision.revision}`;
          $("revision-preview").classList.remove("hidden");$("restore-revision").disabled=revision.revision===doc.revision;
        }catch(error){failure(error);}
      };$("history-list").append(row);
    }
  }catch(error){failure(error);}
}
async function restoreRevision() {
  let doc=activeDocument();const revision=selectedRevision;
  if(!doc || !revision) return;
  if(doc.dirty || doc.saving) {notice("Save or discard your unsaved edits before restoring a revision.",true);return;}
  if(!await confirm(`Restore revision ${revision.revision}?`,"This makes the older contents the current server version. The displaced version remains in history.","Restore")) return;
  doc=documents.get(doc.id);if(!doc||doc.dirty||doc.saving){notice("The document changed. Save or discard the new edits before restoring.",true);return;}
  doc.saving=true;updateStatus();
  try {
    await api(`/documents/${doc.id}/restore`,{method:"POST",body:JSON.stringify({revision:revision.revision,base_revision:doc.revision,request_id:crypto.randomUUID()})});
    doc.saving=false;await reloadFile(doc,false);await refreshFiles();await showHistory();notice("Revision restored and available to synced devices.");
  }finally{doc.saving=false;updateStatus();}
}
async function renameFile() {
  const doc=activeDocument();if(!doc || doc.saving) return;
  const input=element("input");input.value=doc.name;input.maxLength=255;input.setAttribute("aria-label","Filename");
  if(await modal("Rename file",body=>body.append(element("label","Filename"),input),[{label:"Cancel",value:false},{label:"Apply",value:true,primary:true}])) {
    const name=input.value.trim();if(!name || /[/\\\x00-\x1f]/.test(name)) {notice("Choose a filename without slashes or control characters.",true);return;}
    doc.name=name;doc.dirty=true;renderTabs();updateStatus();
  }
}
async function encodingSettings() {
  const doc=activeDocument();if(!doc || doc.saving) return;
  const select=element("select");select.setAttribute("aria-label","Save encoding");
  for(const value of ["utf-8","utf-16-le","utf-16-be","ascii"]) {const option=element("option",encodingName(value));option.value=value;select.append(option);}select.value=doc.encoding;
  const bom=element("input");bom.type="checkbox";bom.checked=doc.bom;
  const ok=await modal("File format",body=>{body.append(element("label","Save encoding"),select);const label=element("label",undefined,"inline");label.append(bom,document.createTextNode("Include byte-order mark (BOM)"));body.append(label,element("p","Existing line endings are preserved when saving."));},[{label:"Rename…",value:"rename"},{label:"Cancel",value:false},{label:"Apply",value:true,primary:true}]);
  if(ok==="rename") return renameFile();
  if(ok) {doc.encoding=select.value;doc.bom=bom.checked&&select.value!=="ascii";doc.dirty=true;renderTabs();updateStatus();}
}
function setSidebarCollapsed(collapsed) {
  sidebarCollapsed = collapsed;
  $("file-sidebar").hidden = collapsed;
  const toggle = $("sidebar-toggle"), label = collapsed ? "Show sidebar" : "Hide sidebar";
  toggle.setAttribute("aria-expanded",String(!collapsed));
  toggle.setAttribute("aria-label",label);
  toggle.title = label;
  try {localStorage.setItem("textpad.editor.sidebarCollapsed",String(collapsed));} catch {}
  view.requestMeasure();
}
function applyPreferences() {
  preferences.fontSize=Math.min(30,Math.max(10,Number(preferences.fontSize)||15));
  document.documentElement.style.setProperty("--font-size",`${preferences.fontSize}px`);
  document.documentElement.style.setProperty("--editor-font",fonts[preferences.font]||fonts.mono);
  const effects=()=>[wrap.reconfigure(preferences.wrap?EditorView.lineWrapping:[]),gutters.reconfigure(preferences.lines?lineNumbers():[])];
  for(const doc of documents.values()) {
    if(active===doc.id) view.dispatch({effects:effects()});else doc.state=doc.state.update({effects:effects()}).state;
  }
  try {localStorage.setItem("textpad.editor.preferences",JSON.stringify(preferences));}catch{}
}
async function settings() {
  const size=element("input");size.type="number";size.min=10;size.max=30;size.value=preferences.fontSize;
  size.setAttribute("aria-label","Font size");
  const font=element("select");for(const [value,label] of [["mono","Monospace"],["sans","Sans serif"],["serif","Serif"]]) {const option=element("option",label);option.value=value;font.append(option);}font.value=preferences.font;
  font.setAttribute("aria-label","Font");
  const wrapping=element("input");wrapping.type="checkbox";wrapping.checked=preferences.wrap;
  const numbers=element("input");numbers.type="checkbox";numbers.checked=preferences.lines;
  if(await modal("Editor settings",body=>{
    body.append(element("label","Font"),font,element("label","Font size"),size);
    for(const [input,text] of [[wrapping,"Word wrap"],[numbers,"Line numbers"]]) {const label=element("label",undefined,"inline");label.append(input,document.createTextNode(text));body.append(label);}
  },[{label:"Cancel",value:false},{label:"Apply",value:true,primary:true}])) {
    preferences={font:font.value,fontSize:Number(size.value),wrap:wrapping.checked,lines:numbers.checked};applyPreferences();
  }
}
async function connectAndroid() {
  const info=await api("/connection");
  modal("Connect Android",body=>{
    body.append(element("p","In the Android app, open Sync → Server settings and enter these details."));
    for(const [label,value,secret] of [["Server address",info.endpoint,false],["API token",info.token,true]]) {
      body.append(element("label",label));const row=element("div",undefined,"copy-row");const input=element("input");input.value=value;input.readOnly=true;input.type=secret?"password":"text";input.setAttribute("aria-label",label);
      const copy=element("button","Copy");copy.type="button";copy.onclick=async()=>{try{await navigator.clipboard.writeText(value);copy.textContent="Copied";}catch{input.type="text";input.select();}};
      row.append(input,copy);body.append(row);
      if(secret){const reveal=element("button","Show token","text-button");reveal.type="button";reveal.onclick=()=>{input.type=input.type==="password"?"text":"password";reveal.textContent=input.type==="password"?"Show token":"Hide token";};body.append(reveal);}
    }
  },[{label:"Done",value:true,primary:true}]);
}

$("new-file").onclick=()=>newFile();$("welcome-new").onclick=()=>newFile();$("file-filter").oninput=renderFiles;
$("sidebar-toggle").onclick=()=>setSidebarCollapsed(!sidebarCollapsed);
$("save").onclick=()=>save().catch(failure);$("refresh").onclick=()=>refreshFiles().catch(failure);
$("undo").onclick=()=>{undo(view);view.focus();};$("redo").onclick=()=>{redo(view);view.focus();};
$("find").onclick=()=>openSearchPanel(view);$("indent-more").onclick=()=>{indentMore(view);view.focus();};$("indent-less").onclick=()=>{indentLess(view);view.focus();};
$("go-bottom").onclick=()=>{const doc=activeDocument();if(!doc)return;const end=view.state.doc.length;view.dispatch({selection:{anchor:end,head:end},scrollIntoView:true});view.focus();};
$("reload").onclick=()=>reloadFile().catch(failure);$("reload-remote").onclick=()=>reloadFile().catch(failure);
$("history").onclick=showHistory;$("close-history").onclick=closeHistory;$("restore-revision").onclick=()=>restoreRevision().catch(failure);
$("encoding-status").onclick=encodingSettings;$("settings").onclick=settings;$("connect-android").onclick=()=>connectAndroid().catch(failure);
$("notice-dismiss").onclick=()=>$("notice").classList.add("hidden");
$("download").onclick=async()=>{const doc=activeDocument();if(!doc?.revision)return;if(doc.dirty&&!await confirm("Download saved version?","This downloads the saved server file. Your unsaved edits remain in this tab.","Download"))return;window.location.assign(`${apiBase}/documents/${doc.id}/download`);};
$("sign-out").onclick=async()=>{
  if([...documents.values()].some(d=>d.dirty)&&!await confirm("Sign out?","Your open unsaved edits will be discarded.","Sign out"))return;
  try{await api("/logout",{method:"POST",body:"{}"});window.location.assign(`${prefix}/editor/login`);}catch(error){failure(error);}
};
window.addEventListener("beforeunload",event=>{if([...documents.values()].some(d=>d.dirty||d.saving)){event.preventDefault();event.returnValue="";}});
window.addEventListener("keydown",event=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==="s"){event.preventDefault();save().catch(failure);}});
window.addEventListener("online",()=>{closeEvents();connectEvents();refreshFiles().catch(failure);});
window.addEventListener("pagehide",closeEvents);
window.addEventListener("pageshow",connectEvents);
document.addEventListener("visibilitychange",()=>{if(document.hidden)closeEvents();else{connectEvents();refreshFiles().catch(failure);}});
setInterval(()=>{if(!document.hidden&&!eventsReady)refreshFiles().catch(failure);},30000);
setSidebarCollapsed(sidebarCollapsed);applyPreferences();updateStatus();refreshFiles().catch(failure);connectEvents();
