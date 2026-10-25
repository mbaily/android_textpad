import test from "node:test";
import assert from "node:assert/strict";
import { indentation, indentUnit, adjustIndent, insertionBelow } from "./lineInsertion.js";

test("copies whitespace and infers nearby space steps", () => {
  const text = "root\n  first\n    nested\n  sibling\n\n    last";
  assert.equal(indentation(text, text.indexOf("nested") + 2), "    ");
  assert.equal(indentUnit(text, text.indexOf("nested")), "  ");
  assert.equal(indentUnit("root\n    first\n        nested\n    sibling", 0, 2), "    ");
  assert.equal(indentUnit("root\n   first\n      nested", 0), "   ");
});

test("blanks and alignment do not override repeated steps", () => {
  assert.equal(indentUnit("root\n  first\n    nested\n  sibling\n\n                 aligned\n  another", 0), "  ");
  assert.equal(indentUnit("root\n\nplain", 0), "    ");
  assert.equal(indentUnit("  first\n  second", 0), "  ");
  assert.equal(indentUnit(" ".repeat(12) + "alignment", 0), "    ");
});

test("preserves tabs and bounds decreases at zero", () => {
  assert.equal(indentUnit("root\n\tfirst\n\t\tnested", 0), "\t");
  assert.equal(adjustIndent("\t", "\t", true), "\t\t");
  assert.equal(adjustIndent("\t\t", "\t", false), "\t");
  assert.equal(adjustIndent(" ", "    ", false), "");
  assert.equal(adjustIndent("", "  ", false), "");
  assert.equal(adjustIndent("\t  ", "  ", false), "\t");
});

test("inserts after the whole line without replacing existing text", () => {
  const text = "root\n  first\n    nested\nlast";
  const insertion = insertionBelow(text, text.indexOf("first") + 2, "  ", "new 😀");
  const result = text.slice(0, insertion.from) + insertion.insert + text.slice(insertion.to);
  assert.equal(result, "root\n  first\n  new 😀\n    nested\nlast");
  assert.equal(insertion.cursor, result.indexOf("new 😀") + "new 😀".length);
});

test("handles empty documents and both final newline cases", () => {
  assert.deepEqual(insertionBelow("", 0, "", "hello"), { from: 0, to: 0, insert: "hello", cursor: 5 });
  assert.deepEqual(insertionBelow("first", 2, "  ", "last"), { from: 5, to: 5, insert: "\n  last", cursor: 12 });
  assert.deepEqual(insertionBelow("first\n", 2, "", "last"), { from: 5, to: 5, insert: "\nlast", cursor: 10 });
  assert.deepEqual(insertionBelow("first\n", 6, "", "last"), { from: 6, to: 6, insert: "last", cursor: 10 });
});

test("fills only the current blank line without adding a separator", () => {
  for (const [text, offset, indent, expected] of [
    ["first\n\nlast", 6, "  ", "first\n  added\nlast"],
    ["first\n \t \nlast", 7, "  ", "first\n  added\nlast"],
    [" \t ", 1, "", "added"],
    ["first\n", 6, "", "first\nadded"],
    ["first\n\n\nlast", 6, "", "first\nadded\n\nlast"],
    ["\nfirst", 0, "", "added\nfirst"],
  ]) {
    const insertion = insertionBelow(text, offset, indent, "added");
    const result = text.slice(0, insertion.from) + insertion.insert + text.slice(insertion.to);
    assert.equal(result, expected);
    assert.equal(result.split("\n").length, text.split("\n").length);
    assert.equal(insertion.cursor, expected.indexOf("added") + 5);
  }
});

test("replaces only blank whitespace with the requested indentation", () => {
  assert.deepEqual(insertionBelow("first\n \t \nlast", 7, "    ", "filled 😀"),
    { from: 6, to: 9, insert: "    filled 😀", cursor: 19 });
});

test("nonblank whitespace characters remain protected", () => {
  assert.deepEqual(insertionBelow(" \u00a0", 1, "", "added"), { from: 2, to: 2, insert: "\nadded", cursor: 8 });
});

test("rejects multiline input", () => {
  for (const input of ["a\nb", "a\rb", "a\r\nb", "a\u2028b", "a\u2029b"]) {
    assert.throws(() => insertionBelow("original", 0, "", input));
  }
  assert.throws(() => insertionBelow("original", 0, "x", "valid"));
});

test("local style wins in long mixed documents", () => {
  const text = "root\n    first\n        nested\n".repeat(150) + "root\n  first\n    nested\n".repeat(150);
  assert.equal(indentUnit(text, text.length), "  ");
  assert.equal(indentUnit(text, 0, 2), "    ");
});
