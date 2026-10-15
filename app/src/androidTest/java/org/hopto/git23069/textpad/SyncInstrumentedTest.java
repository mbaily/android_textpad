package org.hopto.git23069.textpad;

import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import org.junit.Before;
import org.junit.Test;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class SyncInstrumentedTest {
    private TextpadApplication app;
    private FakeApi api;
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String hash(byte[] bytes) { return HexFormat.of().formatHex(SaveTransaction.hash(bytes)); }
    private static final class FakeApi implements RemoteApi {
        final Map<String,Remote> files=new LinkedHashMap<>();
        final Map<String,List<Remote>> history=new LinkedHashMap<>();
        final Map<String,Remote> requests=new LinkedHashMap<>();
        int accepted, uploads;
        boolean loseNextReceipt;
        public List<Remote> list() { return new ArrayList<>(files.values()); }
        public Remote get(String id) { return files.get(id); }
        public Remote put(String id,String name,byte[] content,String base,String request) throws java.io.IOException {
            uploads++;
            if(requests.containsKey(request)) return requests.get(request);
            Remote remote=new Remote(id,name,String.valueOf(++accepted),hash(content),content.length,content);
            files.put(id,remote); history.computeIfAbsent(id,k -> new ArrayList<>()).add(remote); requests.put(request,remote);
            if(loseNextReceipt) { loseNextReceipt=false; throw new java.io.IOException("Connection interrupted after commit"); }
            return remote;
        }
        public List<Remote> revisions(String id) { return history.get(id); }
        public Remote revision(String id,String revision) { return history.get(id).stream().filter(r -> r.revision().equals(revision)).findFirst().orElseThrow(); }
        Remote remoteEdit(String id,String text) throws Exception { return put(id,"sync.txt",bytes(text),null,java.util.UUID.randomUUID().toString()); }
    }
    @Before public void reset() {
        app=(TextpadApplication)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext(); api=new FakeApi();
        main(() -> {
            assertFalse(app.sync.busy); app.tabs.clear(); app.selectedTab=-1; app.events.clear();
            app.sync.prefs.edit().clear().commit(); app.sync.testApi=api; app.sync.rerun=false;
        });
    }
    private void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private Uri seed(String name,String content) throws Exception {
        Uri uri=DocumentsContract.buildDocumentUri("org.hopto.git23069.textpad.test.documents",name);
        try(OutputStream out=app.getContentResolver().openOutputStream(uri,"wt")) { out.write(bytes(content)); }
        return uri;
    }
    private String read(Uri uri) throws Exception { try(InputStream in=app.getContentResolver().openInputStream(uri)) { return new String(DocumentStore.readLimited(in),StandardCharsets.UTF_8); } }
    private void waitFor(java.util.function.BooleanSupplier predicate) throws Exception {
        long deadline=System.currentTimeMillis()+30000;
        while(System.currentTimeMillis()<deadline) {
            AtomicBoolean result=new AtomicBoolean(); main(() -> result.set(predicate.getAsBoolean()));
            if(result.get()) return;
            Thread.sleep(30);
        }
        fail("Timed out waiting for sync");
    }
    private void sync() throws Exception { main(() -> app.sync.syncAll(false)); waitFor(() -> !app.sync.busy); }

    @Test public void optedInClosedFilesUploadDownloadAndKeepBackups() throws Exception {
        Uri uri=seed("sync-closed.txt","first");
        EditorDocument doc=new EditorDocument("sync.txt",new DocumentBuffer(TextCodec.decode(bytes("first"))));
        doc.uri=uri; doc.savedHash=SaveTransaction.hash(bytes("first"));
        main(() -> app.sync.link(doc,"shared-file",null,null)); sync();
        assertEquals("first",new String(api.get("shared-file").content(),StandardCharsets.UTF_8));
        api.remoteEdit("shared-file","remote second"); sync(); assertEquals("remote second",read(uri));
        assertTrue(app.store.backups().stream().anyMatch(b -> b.originalUri().equals(uri.toString())));
        main(() -> app.sync.disable(doc)); api.remoteEdit("shared-file","remote third"); sync(); assertEquals("remote second",read(uri));
        app.sync.testApi=null;
    }

    @Test public void dirtyOpenBufferDefersPullThenCleanPullUpdatesTheView() throws Exception {
        Uri uri=seed("sync-dirty.txt","first"); var remote=api.remoteEdit("shared-dirty","first");
        try(var scenario=ActivityScenario.launch(new Intent(app,MainActivity.class).setAction(Intent.ACTION_VIEW).setDataAndType(uri,"text/plain")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))) {
            waitFor(() -> app.current()!=null);
            scenario.onActivity(activity -> {
                app.sync.link(app.current(),remote.id(),remote.revision(),remote.hash());
                ((CodeEditor)activity.findViewById(R.id.editor)).getText().append(" unsaved");
            });
            api.remoteEdit(remote.id(),"remote second"); sync();
            assertEquals("first",read(uri)); assertTrue(app.current().buffer.dirty());
            scenario.onActivity(activity -> {
                app.current().buffer.undo(); app.refresh();
                assertEquals("first",((CodeEditor)activity.findViewById(R.id.editor)).getText().toString());
            });
            sync(); assertEquals("remote second",read(uri));
            scenario.onActivity(activity -> assertEquals("remote second",((CodeEditor)activity.findViewById(R.id.editor)).getText().toString()));
        }
        app.sync.testApi=null;
    }

    @Test public void interruptedUploadRetriesTheSameRequestWithoutAnotherRevision() throws Exception {
        Uri uri=seed("sync-retry.txt","local version");
        EditorDocument doc=new EditorDocument("sync.txt",new DocumentBuffer(TextCodec.decode(bytes("local version")))); doc.uri=uri;
        main(() -> app.sync.link(doc,"retry-file",null,null)); api.loseNextReceipt=true; sync();
        assertEquals(1,api.accepted); assertTrue(app.sync.status.contains("interrupted"));
        sync(); assertEquals(1,api.accepted); assertEquals(2,api.uploads);
        assertEquals(hash(bytes("local version")),app.sync.bindings().get(0).hash()); app.sync.testApi=null;
    }
}
