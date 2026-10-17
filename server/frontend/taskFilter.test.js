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

test("divider headings require a blank away from file start and a dash-only divider", () => {
  for (const prefix of ["Notes\nShopping\n---", "\n\n---",
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

test("divider headings are hidden without any uncompleted tasks", () => {
  const result = filterTasks("Unrelated\n\nFinished section\n---\n[X] Done\n\nEmpty section\n-\n");
  assert.deepEqual(result.visibleLines, []);
  assert.equal(result.isUncompleted.some(Boolean), false);
});

test("divider headings only apply to tasks before the next heading", () => {
  const text = "\nFinished\n---\n[X] Done\n\nOpen\n---\n[ ] Todo\n\nEmpty\n---\n";
  assert.deepEqual(filterTasks(text).visibleLines, [4, 5, 6, 7]);
  const markdownBoundary = "\nFinished\n---\n[X] Done\n# Open section\n[ ] Todo\n";
  assert.deepEqual(filterTasks(markdownBoundary).visibleLines, [4, 5]);
  const indentedMarkdownBoundary = "\nFinished\n---\n    # Open section\n    [ ] Todo\n";
  assert.deepEqual(filterTasks(indentedMarkdownBoundary).visibleLines, [3, 4]);
});

test("Markdown titles can also have dividers", () => {
  assert.deepEqual(filterTasks("\n# Shopping\n---\n[ ] Milk\n").visibleLines, [0, 1, 2, 3]);
});

test("divider sections do not inherit tasks or notes from earlier sections", () => {
  const completedSection = "[ ] Previous task\n    Previous note\n\n    Finished section\n    ---\n    [X] Done\n    Hidden note\n";
  assert.deepEqual(filterTasks(completedSection).visibleLines, [0, 1]);
  const openSection = "[X] Previous project\n\n    Open section\n    ---\n    [ ] Child\n";
  assert.deepEqual(filterTasks(openSection).visibleLines, [1, 2, 3, 4]);
});

test("a divider heading at the start of the file does not need a blank line", () => {
  for (const title of ["Shopping", " \tShopping ", "# Shopping"]) {
    const result = filterTasks(`${title}\r\n  ---\t\r\n[ ] Milk\r\n[X] Bread\r\n`);
    assert.deepEqual(result.visibleLines, [0, 1, 2]);
    assert.equal(result.isContext[0], 1);
    assert.equal(result.isContext[1], 1);
    assert.equal(result.isUncompleted[2], 1);
  }
});

test("a divider heading at file start still requires an open task in its own section", () => {
  assert.deepEqual(filterTasks("Shopping\n---").visibleLines, []);
  assert.deepEqual(filterTasks("Shopping\n---\n[X] Bread\n").visibleLines, []);
  const text = "Shopping\n---\n[X] Bread\n\nNext section\n---\n[ ] Milk\n";
  assert.deepEqual(filterTasks(text).visibleLines, [3, 4, 5, 6]);
});
