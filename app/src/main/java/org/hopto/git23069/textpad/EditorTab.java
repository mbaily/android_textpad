package org.hopto.git23069.textpad;

final class EditorTab {
    final long id;
    EditorDocument document;
    int selectionStart, selectionEnd, scrollX, scrollY;
    EditorTab(long id, EditorDocument document) { this.id = id; this.document = document; }
}
