package org.hopto.git23069.textpad;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.net.Uri;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;

/** Actual SAF file descriptors, isolated to the instrumentation APK. */
public class TestDocumentsProvider extends ContentProvider {
    private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_FLAGS};
    @Override public boolean onCreate() { return true; }
    private File file(String id) {
        if (!id.matches("[a-zA-Z0-9._-]+")) throw new IllegalArgumentException("Invalid test document ID");
        return new File(getContext().getFilesDir(), id);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String id = DocumentsContract.getDocumentId(uri);
        MatrixCursor result = new MatrixCursor(projection == null ? COLUMNS : projection);
        MatrixCursor.RowBuilder row = result.newRow();
        for (String column : result.getColumnNames()) {
            Object value = switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME -> id;
                case DocumentsContract.Document.COLUMN_MIME_TYPE -> "text/plain";
                case DocumentsContract.Document.COLUMN_SIZE -> file(id).length();
                case DocumentsContract.Document.COLUMN_FLAGS -> id.startsWith("readonly") ? 0
                        : (DocumentsContract.Document.FLAG_SUPPORTS_WRITE | DocumentsContract.Document.FLAG_SUPPORTS_DELETE);
                default -> null;
            };
            row.add(value);
        }
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.parseMode(mode));
    }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) {
        String id = DocumentsContract.getDocumentId(uri);
        if (id.startsWith("readonly")) throw new SecurityException("Read-only test document");
        return file(id).delete() ? 1 : 0;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        // DocumentsContract.deleteDocument uses ContentProvider.call, not delete.
        // An unhandled call returning null is otherwise mistaken for success.
        if ("android:deleteDocument".equals(method)) {
            Uri uri = extras == null ? null : extras.getParcelable("uri", Uri.class);
            if (uri == null || delete(uri, null, null) != 1) throw new IllegalArgumentException("Test document was not deleted");
            return Bundle.EMPTY;
        }
        throw new UnsupportedOperationException(method);
    }
}
