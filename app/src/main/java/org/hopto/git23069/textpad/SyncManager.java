package org.hopto.git23069.textpad;

import android.content.SharedPreferences;
import android.net.Uri;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Saved, opted-in local files only. All file and network operations share the I/O queue. */
final class SyncManager {
    record Binding(String key, Uri uri, String name, String id, String revision, String hash) {}
    private final TextpadApplication app;
    final SharedPreferences prefs;
    boolean busy, rerun;
    private boolean foreground;
    private CompletableFuture<Boolean> running;
    private SyncEvents events;
    private final SharedPreferences.OnSharedPreferenceChangeListener configurationListener=(preferences,key) -> {
        if(key==null || key.equals("endpoint") || key.equals("token") || key.endsWith(".enabled")) configurationChanged();
    };
    String status = "Sync not configured";
    RemoteApi testApi;

    SyncManager(TextpadApplication app) {
        this.app=app; prefs=app.getSharedPreferences("sync",0);
        String endpoint=prefs.getString("endpoint","").trim().replaceAll("/+$","");
        if(endpoint.equalsIgnoreCase("https://git23069.hopto.org:8443")) {
            prefs.edit().putString("endpoint",SyncHttpClient.DEFAULT_ENDPOINT).apply();
            status="Server address updated. Ready to sync";
        }
    }
    void initialize() {
        prefs.registerOnSharedPreferenceChangeListener(configurationListener);
        configurationChanged();
    }
    boolean configured() { return !prefs.getString("token","").isBlank() && !bindings().isEmpty(); }
    void configurationChanged() {
        boolean enabled=testApi==null && configured();
        SyncScheduler.configure(app,enabled);
        if(events==null) events=new SyncEvents(app);
        if(foreground && enabled) {
            try { events.start(prefs.getString("endpoint",SyncHttpClient.DEFAULT_ENDPOINT),TokenVault.read(prefs)); }
            catch(Exception ignored) { events.stop(); }
        } else events.stop();
    }
    void setForeground(boolean value) { foreground=value; configurationChanged(); }
    String clientId() {
        String id = prefs.getString("clientId", null);
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,128}")) {
            id = "android-" + UUID.randomUUID();
            prefs.edit().putString("clientId", id).apply();
        }
        return id;
    }
    RemoteApi api() throws Exception {
        if (testApi != null) return testApi;
        String token = TokenVault.read(prefs);
        if (token.isBlank()) throw new IOException("Enter your server API token in Sync settings first.");
        return new SyncHttpClient(prefs.getString("endpoint",SyncHttpClient.DEFAULT_ENDPOINT),token,clientId());
    }
    List<Binding> bindings() {
        List<Binding> bindings = new ArrayList<>();
        for (Map.Entry<String,?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().endsWith(".enabled") || !Boolean.TRUE.equals(entry.getValue())) continue;
            String key=entry.getKey().substring(0,entry.getKey().length()-8);
            String uri=prefs.getString(key+".uri",null), id=prefs.getString(key+".id",null);
            if (uri != null && id != null) bindings.add(new Binding(key,Uri.parse(uri),prefs.getString(key+".name","Untitled.txt"),id,
                    prefs.getString(key+".revision",null),prefs.getString(key+".hash",null)));
        }
        return bindings;
    }
    void link(EditorDocument doc, String id, String revision, String baselineHash) {
        String key=app.store.key(doc.uri);
        prefs.edit().putString(key+".uri",doc.uri.toString()).putString(key+".name",doc.name)
                .putString(key+".id",id).putBoolean(key+".enabled",true)
                .putString(key+".revision",revision).putString(key+".hash",baselineHash).apply();
        doc.syncEnabled=true; doc.syncId=id; doc.serverRevision=revision;
        doc.syncedHash=baselineHash == null ? null : HexFormat.of().parseHex(baselineHash); app.refresh();
    }
    void move(Uri oldUri, Uri newUri, String newName, EditorDocument doc) {
        String oldKey=app.store.key(oldUri), newKey=app.store.key(newUri);
        String id=prefs.getString(oldKey+".id",doc.syncId);
        String revision=prefs.getString(oldKey+".revision",doc.serverRevision);
        String oldName=prefs.getString(oldKey+".name",null);
        String oldHash=prefs.getString(oldKey+".hash",doc.syncedHash == null ? null : HexFormat.of().formatHex(doc.syncedHash));
        String baselineHash=newName.equals(oldName) ? oldHash : null;
        SharedPreferences.Editor editor=prefs.edit();
        if (!oldKey.equals(newKey)) {
            editor.remove(oldKey+".uri").remove(oldKey+".name").remove(oldKey+".id")
                    .remove(oldKey+".enabled").remove(oldKey+".revision").remove(oldKey+".hash")
                    .remove(oldKey+".pendingHash").remove(oldKey+".request");
        }
        editor.putString(newKey+".uri",newUri.toString()).putString(newKey+".name",newName)
                .putString(newKey+".id",id).putBoolean(newKey+".enabled",true)
                .putString(newKey+".revision",revision).putString(newKey+".hash",baselineHash)
                .remove(newKey+".pendingHash").remove(newKey+".request").commit();
        doc.syncEnabled=true; doc.syncId=id; doc.serverRevision=revision;
        doc.syncedHash=baselineHash == null ? null : HexFormat.of().parseHex(baselineHash);
    }
    void disable(EditorDocument doc) {
        if (doc.uri != null) prefs.edit().putBoolean(app.store.key(doc.uri)+".enabled",false).apply();
        doc.syncEnabled=false; app.refresh();
    }
    private boolean enabled(Binding binding) { return prefs.getBoolean(binding.key+".enabled",false); }
    private EditorDocument openDocument(Uri uri) {
        for (EditorTab tab : app.tabs) if (DocumentStore.same(uri,tab.document.uri)) return tab.document;
        return null;
    }
    private <T> T onMain(Callable<T> operation) throws Exception {
        FutureTask<T> task=new FutureTask<>(operation); app.main.post(task);
        try { return task.get(30, TimeUnit.SECONDS); }
        catch (java.util.concurrent.TimeoutException e) { task.cancel(false); throw e; }
    }
    private void record(Binding binding, RemoteApi.Remote remote) throws IOException {
        SharedPreferences.Editor editor=prefs.edit().putString(binding.key+".revision",remote.revision()).putString(binding.key+".hash",remote.hash())
                .remove(binding.key+".pendingHash").remove(binding.key+".request");
        if (remote.name() != null && !remote.name().isBlank()) editor.putString(binding.key+".name",remote.name());
        if (!editor.commit())
            throw new IOException("Could not store the sync receipt. The saved file is intact; retry sync.");
        app.main.post(() -> {
            EditorDocument doc=openDocument(binding.uri);
            if (doc != null) { doc.serverRevision=remote.revision(); doc.syncedHash=HexFormat.of().parseHex(remote.hash()); app.refresh(); }
        });
    }
    private String requestId(Binding binding, String hash) throws IOException {
        String pending=prefs.getString(binding.key+".pendingHash",null), request=prefs.getString(binding.key+".request",null);
        if (hash.equals(pending) && request != null) return request;
        request=UUID.randomUUID().toString();
        if (!prefs.edit().putString(binding.key+".pendingHash",hash).putString(binding.key+".request",request).commit())
            throw new IOException("Could not record the pending upload. The file has not been uploaded.");
        return request;
    }

    CompletableFuture<Boolean> syncAll(boolean report) { return syncAll(report,()->false,false); }
    CompletableFuture<Boolean> syncFromWorker(BooleanSupplier stopped) { return syncAll(false,stopped,true); }
    private CompletableFuture<Boolean> syncAll(boolean report,BooleanSupplier stopped,boolean fromWorker) {
        if (busy) { rerun=true; if (report) app.notice("Sync", "A sync is already running. Another pass is queued."); return running!=null&&!running.isDone()?running:CompletableFuture.completedFuture(true); }
        List<Binding> bindings=bindings();
        if (bindings.isEmpty()) { if (report) app.notice("Sync", "No local files are marked for sync."); return CompletableFuture.completedFuture(false); }
        final RemoteApi remoteApi;
        try { remoteApi=api(); }
        catch (Exception e) { status=e.getMessage(); if (report) app.notice("Sync settings",status); return CompletableFuture.completedFuture(false); }
        CompletableFuture<Boolean> completion=new CompletableFuture<>(); running=completion;
        busy=true; status="Syncing…"; app.refresh();
        app.io.execute(() -> {
            int uploaded=0, downloaded=0, deferred=0, mergedCount=0, conflictCount=0; List<String> errors=new ArrayList<>();
            try {
                Map<String,RemoteApi.Remote> remoteById=new LinkedHashMap<>();
                for (var remote : remoteApi.list()) remoteById.put(remote.id(),remote);
                for (Binding binding : bindings) {
                    if(stopped.getAsBoolean()) { errors.add("Background sync paused; it will retry."); break; }
                    if (!enabled(binding)) continue;
                    try {
                        boolean dirty=onMain(() -> { EditorDocument doc=openDocument(binding.uri); return doc != null && (doc.buffer.dirty() || doc.saving); });
                        if (dirty) { deferred++; continue; }
                        byte[] local=app.store.read(binding.uri), localHash=SaveTransaction.hash(local);
                        String localHex=HexFormat.of().formatHex(localHash);
                        boolean external=onMain(() -> { EditorDocument doc=openDocument(binding.uri); return doc != null && !Arrays.equals(doc.savedHash,localHash); });
                        if (external) throw new IOException("changed outside Textpad; reload it before syncing");
                        if (!localHex.equals(binding.hash)) {
                            TextCodec.decode(local); // Never upload binary or invalid text.
                            if (!enabled(binding) || stopped.getAsBoolean()) continue;
                            RemoteApi.Remote uploadedFile=remoteApi.put(binding.id,binding.name,local,binding.revision,requestId(binding,localHex));
                            if (!localHex.equals(uploadedFile.hash())) {
                                RemoteApi.Remote merged=remoteApi.get(binding.id);
                                if (merged == null || !merged.hash().equals(uploadedFile.hash()))
                                    throw new IOException("server did not acknowledge the uploaded checksum");
                                DocumentBuffer mergedBuffer=new DocumentBuffer(TextCodec.decode(merged.content()));
                                EditorDocument locked=onMain(() -> {
                                    EditorDocument doc=openDocument(binding.uri);
                                    if (!enabled(binding) || stopped.getAsBoolean() || (doc != null && (doc.buffer.dirty() || doc.saving))) return null;
                                    if (doc != null) { doc.saving=true; doc.replacing=true; app.refresh(); }
                                    return doc == null ? new EditorDocument("closed",DocumentBuffer.empty()) : doc;
                                });
                                if (locked == null) { deferred++; continue; }
                                try {
                                    if (!enabled(binding) || stopped.getAsBoolean()) { deferred++; continue; }
                                    SaveTransaction.save(app.store.target(binding.uri),localHash,merged.content(),old -> app.store.backup(binding.uri,binding.name,old));
                                    record(binding,merged); remoteById.put(binding.id,merged); uploaded++; downloaded++; mergedCount++;
                                    onMain(() -> {
                                        EditorDocument doc=openDocument(binding.uri);
                                        if (doc != null) {
                                            doc.buffer=mergedBuffer; doc.savedHash=SaveTransaction.hash(merged.content());
                                            doc.serverRevision=merged.revision(); doc.syncedHash=doc.savedHash;
                                        }
                                        return null;
                                    });
                                } finally { onMain(() -> { locked.saving=false; locked.replacing=false; app.refresh(); return null; }); }
                            } else {
                                record(binding,uploadedFile); remoteById.put(binding.id, uploadedFile); uploaded++;
                                if (uploadedFile.replacedNewerRevision()) conflictCount++;
                            }
                        } else {
                            RemoteApi.Remote metadata=remoteById.get(binding.id);
                            if (metadata == null) { deferred++; continue; } // No remote-delete propagation.
                            if (metadata.hash().equals(localHex)) { record(binding,metadata); continue; }
                            RemoteApi.Remote remote=remoteApi.get(binding.id);
                            DocumentBuffer nextBuffer=new DocumentBuffer(TextCodec.decode(remote.content()));
                            EditorDocument locked=onMain(() -> {
                                EditorDocument doc=openDocument(binding.uri);
                                if (!enabled(binding) || stopped.getAsBoolean() || (doc != null && (doc.buffer.dirty() || doc.saving))) return null;
                                if (doc != null) { doc.saving=true; doc.replacing=true; app.refresh(); }
                                return doc == null ? new EditorDocument("closed",DocumentBuffer.empty()) : doc;
                            });
                            if (locked == null) { deferred++; continue; }
                            try {
                                if (!enabled(binding) || stopped.getAsBoolean()) { deferred++; continue; }
                                SaveTransaction.save(app.store.target(binding.uri),localHash,remote.content(),old -> app.store.backup(binding.uri,binding.name,old));
                                record(binding,remote); downloaded++;
                                onMain(() -> {
                                    EditorDocument doc=openDocument(binding.uri);
                                    if (doc != null) {
                                        doc.buffer=nextBuffer; doc.savedHash=SaveTransaction.hash(remote.content());
                                        doc.serverRevision=remote.revision(); doc.syncedHash=doc.savedHash;
                                    }
                                    return null;
                                });
                            } finally { onMain(() -> { locked.saving=false; locked.replacing=false; app.refresh(); return null; }); }
                        }
                    } catch (Exception e) { errors.add(binding.name+": "+message(e)); }
                }
            } catch (Exception e) { errors.add(message(e)); }
            String summary=uploaded+" uploaded · "+downloaded+" downloaded · "+deferred+" pending";
            if (mergedCount > 0) summary+=" · "+mergedCount+" merged";
            if (conflictCount > 0) summary+=" · "+conflictCount+" conflict kept in history";
            if (!errors.isEmpty()) summary+="\n"+String.join("\n",errors);
            String result=summary;
            boolean retry=!errors.isEmpty();
            app.main.post(() -> {
                busy=false; status=result; app.refresh(); if (report) app.notice("Sync",result);
                completion.complete(retry);
                if(retry && !fromWorker && testApi==null && configured()) SyncScheduler.retry(app);
                if (rerun) { rerun=false; syncAll(false); }
            });
        });
        return completion;
    }
    static String message(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
