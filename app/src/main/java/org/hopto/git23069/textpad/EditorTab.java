package org.hopto.git23069.textpad;

import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.TaskFilter;

final class EditorTab {
    final long id;
    EditorDocument document;
    int selectionStart, selectionEnd, scrollX, scrollY;
    boolean tasksOnly;
    TaskFilter.FilterResult taskFilterResult;
    DocumentBuffer filteredBuffer;
    EditorTab(long id, EditorDocument document) { this.id = id; this.document = document; }
}
