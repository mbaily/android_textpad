export function visualColumn(line, tabSize = 4) {
  if (tabSize <= 0) tabSize = 4;
  let col = 0;
  for (let i = 0; i < line.length; i++) {
    const c = line.charAt(i);
    if (c === "\t") {
      col += tabSize - (col % tabSize);
    } else if (c === " ") {
      col += 1;
    } else {
      break;
    }
  }
  return col;
}

export function classifyLine(line) {
  let i = 0;
  const len = line.length;
  while (i < len && (line.charAt(i) === " " || line.charAt(i) === "\t")) i++;
  if (i === len) return "BLANK";
  const rest = line.slice(i);
  let taskRest = rest;
  if (taskRest.startsWith("- ") || taskRest.startsWith("* ") || taskRest.startsWith("+ ")) {
    taskRest = taskRest.slice(2);
  }
  if (taskRest.startsWith("[ ]")) return "UNCOMPLETED_TASK";
  if (taskRest.startsWith("[X]") || taskRest.startsWith("[x]")) return "COMPLETED_TASK";
  if (isMarkdownHeader(rest)) return "HEADER";
  return "PLAIN";
}

function isMarkdownHeader(rest) {
  if (!rest.startsWith("#")) return false;
  let h = 0;
  while (h < rest.length && rest.charAt(h) === "#") h++;
  if (h >= 1 && h <= 6 && (h === rest.length || rest.charAt(h) === " ")) return true;
  return false;
}

function headerLevel(line) {
  let i = 0;
  while (i < line.length && (line.charAt(i) === " " || line.charAt(i) === "\t")) i++;
  if (i < line.length && line.charAt(i) === "#") {
    let h = 0;
    while (i + h < line.length && line.charAt(i + h) === "#") h++;
    if (h >= 1 && h <= 6 && (i + h === line.length || line.charAt(i + h) === " ")) return h;
  }
  return 0;
}

export function filterTasks(text, tabSize = 4) {
  const rawLines = text.split("\n");
  const n = rawLines.length;
  const lines = new Array(n);
  const indents = new Int32Array(n);
  const types = new Array(n);
  const parents = new Int32Array(n);

  for (let i = 0; i < n; i++) {
    const raw = rawLines[i];
    lines[i] = raw.endsWith("\r") ? raw.slice(0, -1) : raw;
    indents[i] = visualColumn(lines[i], tabSize);
    types[i] = classifyLine(lines[i]);
    parents[i] = -1;
  }

  for (let i = 0; i < n; i++) {
    if (types[i] === "BLANK") continue;
    for (let k = i - 1; k >= 0; k--) {
      if (types[k] !== "BLANK" && indents[k] < indents[i]) {
        parents[i] = k;
        break;
      }
    }
  }

  const isVisible = new Uint8Array(n);
  const isContext = new Uint8Array(n);
  const isUncompleted = new Uint8Array(n);
  const isNote = new Uint8Array(n);

  for (let i = 0; i < n; i++) {
    if (types[i] === "UNCOMPLETED_TASK") {
      isVisible[i] = 1;
      isUncompleted[i] = 1;
    }
  }

  // Ancestors of uncompleted tasks
  for (let i = 0; i < n; i++) {
    if (!isUncompleted[i]) continue;
    let cur = parents[i];
    let top = i;
    while (cur !== -1) {
      isVisible[cur] = 1;
      isContext[cur] = 1;
      top = cur;
      cur = parents[cur];
    }
    // Enclosing markdown headers
    let targetLevel = 7;
    for (let h = top - 1; h >= 0; h--) {
      if (types[h] === "HEADER") {
        const lvl = headerLevel(lines[h]);
        if (lvl > 0 && lvl < targetLevel) {
          isVisible[h] = 1;
          isContext[h] = 1;
          targetLevel = lvl;
          if (targetLevel === 1) break;
        }
      }
    }
  }

  // Plain notes indented under uncompleted tasks
  for (let i = 0; i < n; i++) {
    if (types[i] === "PLAIN" && parents[i] !== -1) {
      const p = parents[i];
      if (isUncompleted[p] || isNote[p]) {
        isVisible[i] = 1;
        isNote[i] = 1;
      }
    }
  }

  const visibleLines = [];
  for (let i = 0; i < n; i++) {
    if (isVisible[i]) visibleLines.push(i);
  }

  return {
    lineCount: n,
    visibleLines,
    isVisible,
    isContext,
    isUncompleted,
    isNote
  };
}
