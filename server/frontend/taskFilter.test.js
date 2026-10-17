import assert from "node:assert/strict";
import test from "node:test";
import { filterTasks } from "./taskFilter.js";

test("divider headings retain the blank, title, and divider above unindented tasks", () => {
  const result = filterTasks("Unrelated notes\n\nShopping\n--------\n[ ] Milk\n[X] Bread\n");
  assert.deepEqual(result.visibleLines, [1, 2, 3, 4]);
  for (const line of [1, 2, 3]) assert.equal(result.isContext[line], 1);
  assert.equal(result.isUncompleted[4], 1);
  assert.equal(result.isVisible[5], 0);
});

test("divider headings accept whitespace, CRLF, and any configured dash count", () => {
  for (const divider of ["-", "---", "-".repeat(40), "-".repeat(200)]) {
    const result = filterTasks(` \t\r\n\tShopping \r\n  ${divider}\t\r\n[ ] Milk\r\n`);
    assert.deepEqual(result.visibleLines, [0, 1, 2, 3]);
  }
});

test("divider headings require a blank, nonblank title, and a line of only dashes", () => {
  for (const prefix of ["Shopping\n---", "Notes\nShopping\n---", "\n\n---",
    "\nShopping\n-- text --", "\nShopping\n- - -", "\nShopping\n===", "\nShopping\n"]) {
    const text = prefix + "\n[ ] Milk\n";
    assert.deepEqual(filterTasks(text).visibleLines, [text.split("\n").length - 2]);
  }
});

test("divider headings coexist with Markdown headings and task notes", () => {
  const result = filterTasks("# Projects\n\nShopping\n---\n[ ] Milk\n    Remember organic\n[X] Bread\n"
    + "\nHousework\n-----\n[ ] Sweep\n[X] Dust\n");
  assert.deepEqual(result.visibleLines, [0, 1, 2, 3, 4, 5, 7, 8, 9, 10]);
  assert.equal(result.isNote[5], 1);
  assert.equal(result.isContext[8], 1);
  assert.equal(result.isVisible[6], 0);
  assert.equal(result.isVisible[11], 0);
});

test("divider headings stay visible without any uncompleted tasks", () => {
  const result = filterTasks("Unrelated\n\nFinished section\n---\n[X] Done\n\nEmpty section\n-\n");
  assert.deepEqual(result.visibleLines, [1, 2, 3, 5, 6, 7]);
  for (const line of result.visibleLines) assert.equal(result.isContext[line], 1);
  assert.equal(result.isUncompleted.some(Boolean), false);
});
