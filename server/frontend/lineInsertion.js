// Keep the bounded indentation heuristic in sync with core/LineInsertion.java.
function lineStart(text, offset) {
  let start = Math.max(0, Math.min(offset, text.length));
  while (start > 0 && text[start - 1] !== "\n") start--;
  return start;
}

export function indentation(text, offset) {
  const start = lineStart(text, offset);
  let end = start;
  while (end < text.length && (text[end] === " " || text[end] === "\t")) end++;
  return text.slice(start, end);
}

export function indentUnit(text, offset, fallback = 4) {
  let start = lineStart(text, offset);
  for (let n = 0; n < 100 && start > 0; n++) start = lineStart(text, start - 1);
  const votes = Array(9).fill(0);
  let previous = -1, tabLines = 0, spaceLines = 0;
  for (let n = 0; n < 201 && start < text.length; n++) {
    let end = text.indexOf("\n", start);
    if (end < 0) end = text.length;
    let content = start, tabs = false;
    while (content < end && (text[content] === " " || text[content] === "\t")) {
      tabs ||= text[content] === "\t";
      content++;
    }
    if (content < end) {
      const width = content - start;
      if (tabs) { tabLines++; previous = -1; }
      else {
        if (width > 0) spaceLines++;
        const delta = previous < 0 ? width : Math.abs(width - previous);
        if (delta > 0 && delta < votes.length) votes[delta]++;
        previous = width;
      }
    }
    start = end + 1;
  }
  const current = indentation(text, offset);
  if (current.endsWith("\t") || (!current && tabLines > spaceLines)) return "\t";
  let best = 0;
  for (let width = 1; width < votes.length; width++) if (votes[width] > votes[best]) best = width;
  return " ".repeat(best || Math.max(1, fallback));
}

export function adjustIndent(indent, unit, increase) {
  if (increase) return indent + unit;
  let end = indent.length;
  if (indent.endsWith("\t")) return indent.slice(0, -1);
  let count = unit === "\t" ? 4 : unit.length;
  while (count-- > 0 && end > 0 && indent[end - 1] === " ") end--;
  return indent.slice(0, end);
}

export function insertionBelow(text, offset, indent, input) {
  if (!/^[ \t]*$/.test(indent) || /[\r\n\u2028\u2029]/.test(input)) throw new Error("Enter a single line");
  let end = text.indexOf("\n", Math.max(0, Math.min(offset, text.length)));
  if (end < 0) end = text.length;
  const insert = (text.length ? "\n" : "") + indent + input;
  return { from: end, insert, cursor: end + insert.length };
}
