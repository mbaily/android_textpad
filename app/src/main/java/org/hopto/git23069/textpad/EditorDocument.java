package org.hopto.git23069.textpad;

import android.net.Uri;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import java.util.UUID;

final class EditorDocument {
    final String sessionId = UUID.randomUUID().toString();
    DocumentBuffer buffer;
    Uri uri;
    String name;
    byte[] savedHash;
    boolean writable = true;
    boolean saving;
    boolean replacing;
    RemoteApi.Remote pendingDownload;
    String syncId;
    boolean syncEnabled;
    String serverRevision;
    byte[] syncedHash;

    EditorDocument(String name, DocumentBuffer buffer) { this.name = name; this.buffer = buffer; }
}
