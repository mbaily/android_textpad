package org.hopto.git23069.textpad;

import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.TestWorkerBuilder;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        byte[] nextMergedContent;
        public List<Remote> list() { return new ArrayList<>(files.values()); }
        public Remote get(String id) { return files.get(id); }
        public Remote put(String id,String name,byte[] content,String base,String request) throws java.io.IOException {
            uploads++;
            if(requests.containsKey(request)) return requests.get(request);
            byte[] stored = nextMergedContent != null ? nextMergedContent : content;
            nextMergedContent = null;
            Remote remote=new Remote(id,name,String.valueOf(++accepted),hash(stored),stored.length,stored);
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
            app.settings.prefs.edit().remove("tagGroups").commit();
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

    private ListenableWorker.Result worker() {
        var executor=Executors.newSingleThreadExecutor();
        try { return TestWorkerBuilder.from(app,SyncWorker.class,executor).build().doWork(); }
        finally { executor.shutdownNow(); }
    }

    @Test public void backgroundWorkerRetriesTheOriginalUploadReceipt() throws Exception {
        Uri uri=seed("sync-worker.txt","worker saved text");
        EditorDocument doc=new EditorDocument("sync-worker.txt",new DocumentBuffer(TextCodec.decode(bytes("worker saved text"))));
        doc.uri=uri;doc.savedHash=SaveTransaction.hash(bytes("worker saved text"));
        main(() -> app.sync.link(doc,"worker-file",null,null));api.loseNextReceipt=true;
        assertEquals(ListenableWorker.Result.retry(),worker());
        assertEquals(1,api.accepted);
        assertEquals(ListenableWorker.Result.success(),worker());
        assertEquals(1,api.accepted);assertEquals(2,api.uploads);
        assertEquals("worker saved text",new String(api.get("worker-file").content(),StandardCharsets.UTF_8));
    }

    @Test public void backgroundWorkerKeepsDirtyBuffersAndBacksUpACleanPull() throws Exception {
        Uri uri=seed("sync-worker-dirty.txt","saved");var remote=api.remoteEdit("worker-dirty","saved");
        EditorDocument doc=new EditorDocument("sync-worker-dirty.txt",new DocumentBuffer(TextCodec.decode(bytes("saved"))));
        doc.uri=uri;doc.savedHash=SaveTransaction.hash(bytes("saved"));
        main(() -> { app.sync.link(doc,remote.id(),remote.revision(),remote.hash());app.addTab(doc);doc.buffer.edit(doc.buffer.length(),doc.buffer.length()," unsaved"); });
        api.remoteEdit(remote.id(),"new remote");
        assertEquals(ListenableWorker.Result.success(),worker());
        assertEquals("saved",read(uri));assertEquals("saved unsaved",doc.buffer.text());
        main(() -> doc.buffer.undo());
        assertEquals(ListenableWorker.Result.success(),worker());
        assertEquals("new remote",read(uri));assertEquals("new remote",doc.buffer.text());
        assertTrue(app.store.backups().stream().anyMatch(backup -> backup.originalUri().equals(uri.toString())));
    }

    @Test public void periodicWorkIsPersistedDeduplicatedAndCancelledWhenDisabled() throws Exception {
        try {
            SyncScheduler.configure(app,true).getResult().get(10,TimeUnit.SECONDS);
            SyncScheduler.configure(app,true).getResult().get(10,TimeUnit.SECONDS);
            var infos=WorkManager.getInstance(app).getWorkInfosForUniqueWork(SyncScheduler.PERIODIC).get(10,TimeUnit.SECONDS);
            assertEquals(1,infos.stream().filter(info -> !info.getState().isFinished()).count());
            assertEquals(androidx.work.NetworkType.CONNECTED,infos.stream().filter(info -> !info.getState().isFinished()).findFirst().orElseThrow().getConstraints().getRequiredNetworkType());
        } finally { SyncScheduler.configure(app,false).getResult().get(10,TimeUnit.SECONDS); }
        assertTrue(WorkManager.getInstance(app).getWorkInfosForUniqueWork(SyncScheduler.PERIODIC).get(10,TimeUnit.SECONDS)
                .stream().allMatch(info -> info.getState().isFinished()));
    }

    @Test public void upgradeMigratesLegacyEndpointWithoutChangingCredentialsOrFileLinks() throws Exception {
        Uri uri=seed("sync-upgrade.txt","saved local text");
        EditorDocument doc=new EditorDocument("sync-upgrade.txt",new DocumentBuffer(TextCodec.decode(bytes("saved local text"))));
        doc.uri=uri; doc.savedHash=SaveTransaction.hash(bytes("saved local text"));
        main(() -> {
            app.sync.link(doc,"upgrade-file","42",hash(bytes("saved local text")));
            List<SyncManager.Binding> before=app.sync.bindings();
            String key=app.store.key(uri);
            for(String endpoint:List.of("https://git23069.hopto.org:8443/",
                    "https://git23069.hopto.org/android_textpad",
                    "https://git23069.hopto.org:443/android_textpad/")) {
                app.sync.prefs.edit().putString("endpoint",endpoint)
                        .putString("token","existing encrypted token").putString(key+".pendingHash","pending hash")
                        .putString(key+".request","pending request").commit();
                SyncManager upgraded=new SyncManager(app);
                assertEquals(SyncHttpClient.DEFAULT_ENDPOINT,upgraded.prefs.getString("endpoint",null));
                assertEquals("existing encrypted token",upgraded.prefs.getString("token",null));
                assertEquals("pending hash",upgraded.prefs.getString(key+".pendingHash",null));
                assertEquals("pending request",upgraded.prefs.getString(key+".request",null));
                assertEquals(before,upgraded.bindings());
                assertEquals("upgrade-file",upgraded.bindings().get(0).id());
                assertEquals("42",upgraded.bindings().get(0).revision());
                assertEquals(SyncHttpClient.DEFAULT_ENDPOINT,new SyncManager(app).prefs.getString("endpoint",null));
            }
        });
        assertEquals("saved local text",read(uri));
    }

    @Test public void upgradePreservesCustomEndpoints() {
        main(() -> {
            for(String endpoint:List.of("https://example.com:8443", "https://git23069.hopto.org:8443/custom", SyncHttpClient.DEFAULT_ENDPOINT)) {
                app.sync.prefs.edit().putString("endpoint",endpoint).commit();
                assertEquals(endpoint,new SyncManager(app).prefs.getString("endpoint",null));
            }
            app.sync.prefs.edit().remove("endpoint").commit();
            assertFalse(new SyncManager(app).prefs.contains("endpoint"));
        });
    }

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

    @Test public void moveAndRenameDeletesOldFileMigratesSyncBindingAndUpdatesServerName() throws Exception {
        Uri oldUri=seed("old-name.txt","same content");
        Uri newUri=seed("moved-name.txt","");
        EditorDocument doc=new EditorDocument("old-name.txt",new DocumentBuffer(TextCodec.decode(bytes("same content"))));
        doc.uri=oldUri; doc.savedHash=SaveTransaction.hash(bytes("same content"));
        main(() -> { app.store.remember(oldUri,"old-name.txt"); app.addTab(doc); app.sync.link(doc,"move-doc",null,null); });
        sync();
        assertEquals("old-name.txt",api.get("move-doc").name());
        assertEquals("1",api.get("move-doc").revision());

        main(() -> {
            app.pendingSave=new TextpadApplication.PendingSave(doc,null,false,true);
            app.saveAsResult(newUri);
        });
        waitFor(() -> !doc.saving && !app.sync.busy && "2".equals(doc.serverRevision));

        assertEquals(newUri,doc.uri);
        assertEquals("moved-name.txt",doc.name);
        assertTrue(doc.syncEnabled);
        assertEquals("move-doc",doc.syncId);
        assertEquals("same content",read(newUri));
        assertThrows(java.io.IOException.class,() -> app.store.read(oldUri));
        assertEquals(1,app.sync.bindings().size());
        assertEquals(newUri,app.sync.bindings().get(0).uri());
        assertEquals("moved-name.txt",app.sync.bindings().get(0).name());
        assertEquals("moved-name.txt",api.get("move-doc").name());
        assertEquals("same content",new String(api.get("move-doc").content(),StandardCharsets.UTF_8));
        assertFalse(app.store.recents().stream().anyMatch(r -> DocumentStore.same(r.uri(),oldUri)));
        assertTrue(app.store.recents().stream().anyMatch(r -> DocumentStore.same(r.uri(),newUri)));
        app.sync.testApi=null;
    }

    @Test public void downloadingHistoricalRevisionSavesLocalFileWithoutLinkingSync() throws Exception {
        RemoteApi.Remote first=api.remoteEdit("rev-doc","first revision");
        api.remoteEdit("rev-doc","second revision");
        Uri target=seed("revision-copy.txt","");
        RemoteApi.Remote oldRev=api.revision("rev-doc",first.revision());
        EditorDocument doc=new EditorDocument(oldRev.name(),new DocumentBuffer(TextCodec.decode(oldRev.content())));
        main(() -> {
            app.pendingSave=new TextpadApplication.PendingSave(doc,null,false);
            app.saveAsResult(target);
        });
        waitFor(() -> !doc.saving && doc.uri != null && app.current() == doc);
        assertEquals("first revision",read(target));
        assertFalse(doc.syncEnabled);
        assertTrue(app.sync.bindings().isEmpty());
        sync();
        assertEquals("first revision",read(target));
        app.sync.testApi=null;
    }

    @Test public void remoteRenameAndServerMergedUploadAreAdoptedAndPreserved() throws Exception {
        Uri uri=seed("initial.txt","line 1\nline 2\n");
        EditorDocument doc=new EditorDocument("initial.txt",new DocumentBuffer(TextCodec.decode(bytes("line 1\nline 2\n"))));
        doc.uri=uri; doc.savedHash=SaveTransaction.hash(bytes("line 1\nline 2\n"));
        main(() -> { app.addTab(doc); app.sync.link(doc,"merge-doc",null,null); });
        sync();
        // Remote client renames the file without changing content.
        api.put("merge-doc","web-renamed.txt",bytes("line 1\nline 2\n"),"1","remote-rename");
        sync();
        assertEquals("web-renamed.txt",app.sync.bindings().get(0).name());
        // Local edit uploaded while server returns 3-way merged content.
        try(OutputStream out=app.getContentResolver().openOutputStream(uri,"wt")) { out.write(bytes("line 1\nLINE 2\n")); }
        DocumentBuffer nextLocal=new DocumentBuffer(TextCodec.decode(bytes("line 1\nLINE 2\n")));
        main(() -> { doc.buffer=nextLocal; doc.savedHash=SaveTransaction.hash(bytes("line 1\nLINE 2\n")); });
        api.nextMergedContent=bytes("LINE 1\nLINE 2\n");
        sync();
        assertEquals("LINE 1\nLINE 2\n",read(uri));
        assertEquals("LINE 1\nLINE 2\n",doc.buffer.text());
        assertEquals("web-renamed.txt",api.get("merge-doc").name());
        assertTrue(app.store.backups().stream().anyMatch(b -> b.originalUri().equals(uri.toString())));
        app.sync.testApi=null;
    }
}
