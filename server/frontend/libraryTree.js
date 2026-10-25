// Flat rows and iterative traversal keep deeply nested libraries out of the call stack.
const compare = (a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: "base" }) || a.id.localeCompare(b.id);

export function indexLibrary(folders, files = []) {
  const byId = new Map(folders.map(folder => [folder.id, folder]));
  const childFolders = new Map(), childFiles = new Map();
  const add = (map, parent, item) => {
    if (!map.has(parent)) map.set(parent, []);
    map.get(parent).push(item);
  };
  for (const folder of folders) add(childFolders, byId.has(folder.parent_id) ? folder.parent_id : null, folder);
  for (const file of files) add(childFiles, byId.has(file.folder_id) ? file.folder_id : null, file);
  for (const list of [...childFolders.values(), ...childFiles.values()]) list.sort(compare);
  return { byId, childFolders, childFiles };
}

export function folderPath(index, folderId) {
  const path = [], seen = new Set();
  while (folderId != null && index.byId.has(folderId) && !seen.has(folderId)) {
    seen.add(folderId);
    const folder = index.byId.get(folderId);
    path.push(folder);
    folderId = folder.parent_id;
  }
  return path.reverse();
}

export function folderEntries(index, folderId, query = "") {
  const q = query.trim().toLocaleLowerCase();
  return [
    ...(index.childFolders.get(folderId) || []).map(entry => ({ kind: "folder", entry })),
    ...(index.childFiles.get(folderId) || []).map(entry => ({ kind: "file", entry })),
  ].filter(row => !q || row.entry.name.toLocaleLowerCase().includes(q));
}

export function descendantIds(index, folderId) {
  const ids = new Set(), stack = [folderId];
  while (stack.length) {
    const id = stack.pop();
    if (ids.has(id)) continue;
    ids.add(id);
    for (const child of index.childFolders.get(id) || []) stack.push(child.id);
  }
  return ids;
}

export function libraryRows(index, expandedIds, query = "") {
  const q = query.trim().toLocaleLowerCase();
  const includedFolders = new Set(), includedFiles = new Set();
  const includeAncestors = id => {
    while (id != null && index.byId.has(id) && !includedFolders.has(id)) {
      includedFolders.add(id);
      id = index.byId.get(id).parent_id;
    }
  };
  if (q) {
    for (const folder of index.byId.values()) if (folder.name.toLocaleLowerCase().includes(q)) includeAncestors(folder.id);
    for (const files of index.childFiles.values()) {
      for (const file of files) if (file.name.toLocaleLowerCase().includes(q)) {
        includedFiles.add(file.id);
        includeAncestors(file.folder_id);
      }
    }
  }
  const rows = [], stack = [], visited = new Set(), expanded = new Set(expandedIds);
  const pushChildren = (parent, depth) => {
    const files = index.childFiles.get(parent) || [], folders = index.childFolders.get(parent) || [];
    for (let i = files.length - 1; i >= 0; i--) if (!q || includedFiles.has(files[i].id)) stack.push({ kind: "file", entry: files[i], depth });
    for (let i = folders.length - 1; i >= 0; i--) if (!q || includedFolders.has(folders[i].id)) stack.push({ kind: "folder", entry: folders[i], depth });
  };
  pushChildren(null, 0);
  while (stack.length) {
    const row = stack.pop();
    if (row.kind === "folder") {
      if (visited.has(row.entry.id)) continue;
      visited.add(row.entry.id);
      row.expanded = Boolean(q) || expanded.has(row.entry.id);
      row.count = (index.childFolders.get(row.entry.id)?.length || 0) + (index.childFiles.get(row.entry.id)?.length || 0);
      rows.push(row);
      if (row.expanded) pushChildren(row.entry.id, row.depth + 1);
    } else rows.push(row);
  }
  return rows;
}
