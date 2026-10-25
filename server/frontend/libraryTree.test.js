import test from "node:test";
import assert from "node:assert/strict";
import { indexLibrary, libraryRows, folderPath, descendantIds } from "./libraryTree.js";

const folder = (id, name, parent_id = null) => ({ id, name, parent_id });
const file = (id, name, folder_id = null) => ({ id, name, folder_id });

test("folders precede files at every level, with alphabetical ordering", () => {
  const index = indexLibrary([folder("z", "zebra"), folder("a", "Alpha"), folder("sub", "Subfolder", "a")],
    [file("one", "z.txt", "a"), file("two", "A.txt", "a"), file("root", "First.txt")]);
  assert.deepEqual(libraryRows(index, ["a"]).map(row => row.entry.id), ["a", "sub", "two", "one", "z", "root"]);
});

test("collapsing a folder hides only its descendants", () => {
  const index = indexLibrary([folder("a", "A"), folder("b", "B", "a"), folder("c", "C")], [file("note", "Note", "b")]);
  assert.deepEqual(libraryRows(index, ["a", "b"]).map(row => row.entry.id), ["a", "b", "note", "c"]);
  assert.deepEqual(libraryRows(index, ["b"]).map(row => row.entry.id), ["a", "c"]);
});

test("search reveals matching files and their ancestors without changing expansion", () => {
  const expanded = [];
  const index = indexLibrary([folder("a", "Home"), folder("b", "Work", "a"), folder("other", "Other")],
    [file("note", "Important.txt", "b"), file("other-file", "Ignore.txt", "other")]);
  assert.deepEqual(libraryRows(index, expanded, "IMPORTANT").map(row => row.entry.id), ["a", "b", "note"]);
  assert.deepEqual(libraryRows(index, expanded, "work").map(row => row.entry.id), ["a", "b"]);
  assert.deepEqual(expanded, []);
  assert.deepEqual(libraryRows(index, expanded, "missing"), []);
});

test("descendant sets exclude every illegal folder-move destination", () => {
  const index = indexLibrary([folder("a", "A"), folder("b", "B", "a"), folder("c", "C", "b"), folder("d", "D")]);
  assert.deepEqual([...descendantIds(index, "a")].sort(), ["a", "b", "c"]);
  assert.deepEqual(folderPath(index, "c").map(item => item.id), ["a", "b", "c"]);
});

test("deep folder trees and paths do not use recursive rendering", () => {
  const folders = Array.from({ length: 1500 }, (_, i) => folder(String(i), "Folder", i ? String(i - 1) : null));
  const index = indexLibrary(folders, [file("leaf", "Leaf.txt", "1499")]);
  const rows = libraryRows(index, folders.map(item => item.id));
  assert.equal(rows.length, 1501);
  assert.equal(rows.at(-1).depth, 1500);
  assert.equal(folderPath(index, "1499").length, 1500);
  assert.equal(descendantIds(index, "0").size, 1500);
});

test("partial cached catalogs keep files with missing parents accessible", () => {
  const index = indexLibrary([folder("orphan", "Folder", "not-cached")], [file("missing", "File.txt", "not-cached")]);
  assert.deepEqual(libraryRows(index, []).map(row => row.entry.id), ["orphan", "missing"]);
});

test("malformed cached cycles cannot loop forever", () => {
  const index = indexLibrary([folder("a", "A", "b"), folder("b", "B", "a")]);
  assert.equal(folderPath(index, "a").length, 2);
  assert.equal(descendantIds(index, "a").size, 2);
  assert.deepEqual(libraryRows(index, ["a", "b"]), []);
});
