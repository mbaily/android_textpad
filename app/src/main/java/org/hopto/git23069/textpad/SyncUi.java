package org.hopto.git23069.textpad;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.hopto.git23069.textpad.core.ContentSimilarity;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

final class SyncUi {
    private final MainActivity activity;
    private final TextpadApplication app;
    SyncUi(MainActivity activity, TextpadApplication app) { this.activity=activity; this.app=app; }
    private boolean alive() { return !activity.isFinishing() && !activity.isDestroyed(); }

    void show() {
        EditorDocument doc=app.current();
        String toggle=doc != null && doc.syncEnabled ? "Disable sync for this file" : "Enable sync for this file…";
        String[] choices={"Sync now",toggle,"Browse server files…","Manage synced local files…","Server settings…"};
        new AlertDialog.Builder(activity).setTitle("Sync").setMessage(app.sync.status
                +"\n\nEnabled files sync on save and receive live updates while the app is open. Background checks run about every 15 minutes when Android allows. Unsaved edits stay protected.")
                .setPositiveButton("Actions…",(d,w) -> new AlertDialog.Builder(activity).setTitle("Sync actions")
                        .setItems(choices,(dialog,which) -> {
                            switch(which) {
                                case 0 -> app.sync.syncAll(true);
                                case 1 -> { if(doc == null) app.notice("Sync","Open a saved local file first."); else if(doc.syncEnabled) app.sync.disable(doc); else enable(doc); }
                                case 2 -> browse(); case 3 -> manage(); case 4 -> settings();
                            }
                        }).setNegativeButton("Cancel",null).show())
                .setNegativeButton("Close",null).show();
    }

    private void settings() {
        LinearLayout fields=fields(); fields.addView(label("Server address"));
        EditText endpoint=input(SyncHttpClient.DEFAULT_ENDPOINT,false);
        endpoint.setText(app.sync.prefs.getString("endpoint",SyncHttpClient.DEFAULT_ENDPOINT)); fields.addView(endpoint);
        fields.addView(label("API token")); EditText token=input("Server API token",true);
        try { token.setText(TokenVault.read(app.sync.prefs)); }
        catch(Exception e) { app.notice("Token",SyncManager.message(e)); }
        fields.addView(token);
        fields.addView(label("Use the sync server address, without /editor/ or /api/v1. For this server: "+SyncHttpClient.DEFAULT_ENDPOINT));
        fields.addView(label("For git23069.hopto.org, Textpad trusts Android's installed system and user CA certificates. Local files and editing work without a connection."));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Sync server").setView(fields)
                .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String url=endpoint.getText().toString().trim(); new SyncHttpClient(url,token.getText().toString());
                String previous=app.sync.prefs.getString("endpoint",SyncHttpClient.DEFAULT_ENDPOINT);
                if(!url.equals(previous) && !app.sync.bindings().isEmpty()) {
                    app.notice("Existing sync links", "Disable the existing file links before changing servers. Each link belongs to its current server."); return;
                }
                TokenVault.store(app.sync.prefs,token.getText().toString().trim());
                app.sync.prefs.edit().putString("endpoint",url).apply(); app.sync.status="Ready to sync"; dialog.dismiss();
            } catch(Exception e) { endpoint.setError(SyncManager.message(e)); }
        })); dialog.show();
    }

    private void manage() {
        List<SyncManager.Binding> bindings=app.sync.bindings();
        if(bindings.isEmpty()) { app.notice("Synced files","No local files are marked for sync."); return; }
        new AlertDialog.Builder(activity).setTitle("Synced local files").setItems(bindings.stream()
                .map(b -> b.name()+"\n"+app.store.location(b.uri(),b.name())).toArray(String[]::new),(d,i) -> {
            var binding=bindings.get(i);
            new AlertDialog.Builder(activity).setTitle(binding.name()).setMessage("Disable synchronization for this local file? Its local and server copies will remain.")
                    .setPositiveButton("Disable",(dialog,w) -> {
                        app.sync.prefs.edit().putBoolean(binding.key()+".enabled",false).apply();
                        for(EditorTab tab:app.tabs) if(DocumentStore.same(tab.document.uri,binding.uri())) tab.document.syncEnabled=false;
                        app.refresh();
                    }).setNegativeButton("Cancel",null).show();
        }).setNegativeButton("Close",null).show();
    }

    private record Candidate(RemoteApi.Remote remote,int similarity) {}
    private void enable(EditorDocument doc) {
        if(doc.uri == null || doc.buffer.dirty() || doc.saving) { app.notice("Save first","Save this document to local storage before enabling sync."); return; }
        if(!doc.writable) { app.notice("Read-only file","Use Save As to create a writable local copy before enabling sync."); return; }
        String localText=doc.buffer.text();
        request(api -> {
            List<Candidate> candidates=new ArrayList<>();
            for(var item:api.list()) {
                if(!item.name().equals(doc.name)) continue;
                var remote=api.get(item.id());
                int similarity=ContentSimilarity.percent(localText,TextCodec.decode(remote.content()).text());
                candidates.add(new Candidate(remote,similarity));
            }
            candidates.sort(Comparator.comparingInt(Candidate::similarity).reversed()); return candidates;
        },candidates -> {
            List<String> labels=new ArrayList<>(); labels.add("Create a separate synced document");
            for(var candidate:candidates) labels.add(candidate.remote.name()+" · "+candidate.similarity+"% similar\n"+candidate.remote.id());
            new AlertDialog.Builder(activity).setTitle("Link this local file").setItems(labels.toArray(String[]::new),(d,i) -> {
                if(doc.buffer.dirty() || doc.saving) { app.notice("Save first","The document changed while checking for matches. Save it and try again."); return; }
                if(i == 0) { app.sync.link(doc,UUID.randomUUID().toString(),null,null); app.sync.syncAll(true); }
                else confirmLink(doc,candidates.get(i-1).remote);
            }).setNegativeButton("Cancel",null).show();
        });
    }

    private void confirmLink(EditorDocument doc,RemoteApi.Remote remote) {
        new AlertDialog.Builder(activity).setTitle("Link to "+remote.name()+"?")
                .setMessage("Choose which saved version to use for the first sync. Displaced contents will be backed up. Later writes win in server acceptance order.")
                .setPositiveButton("Use server version",(d,w) -> {
                    if(doc.buffer.dirty() || doc.saving) { app.notice("Save first","Save or discard the new edits before linking."); return; }
                    app.sync.link(doc,remote.id(),remote.revision(),HexFormat.of().formatHex(doc.savedHash)); app.sync.syncAll(true);
                }).setNegativeButton("Use local version",(d,w) -> {
                    if(doc.buffer.dirty() || doc.saving) { app.notice("Save first","Save or discard the new edits before linking."); return; }
                    app.sync.link(doc,remote.id(),remote.revision(),remote.hash()); app.sync.syncAll(true);
                }).setNeutralButton("Cancel",null).show();
    }

    private void browse() {
        request(RemoteApi::list,files -> {
            if(files.isEmpty()) { app.notice("Server files","There are no synced documents on the server yet."); return; }
            new AlertDialog.Builder(activity).setTitle("Server files").setItems(files.stream()
                    .map(r -> r.name()+" · "+r.size()+" bytes\nrevision "+r.revision()).toArray(String[]::new),(d,i) -> {
                RemoteApi.Remote remote=files.get(i);
                new AlertDialog.Builder(activity).setTitle(remote.name()).setItems(new String[]{"Download to a local file…","Previous revisions…"},(dialog,which) -> {
                    if(which == 0) request(api -> api.get(remote.id()),this::download);
                    else history(remote);
                }).setNegativeButton("Cancel",null).show();
            }).setNegativeButton("Close",null).show();
        });
    }

    private void download(RemoteApi.Remote remote) {
        download(remote,true);
    }
    private void download(RemoteApi.Remote remote,boolean linkSync) {
        try {
            EditorDocument doc=new EditorDocument(remote.name(),new DocumentBuffer(TextCodec.decode(remote.content())));
            if(linkSync) doc.pendingDownload=remote;
            app.pendingSave=new TextpadApplication.PendingSave(doc,null,false); activity.launchSavePicker();
        } catch(Exception e) { app.notice("Could not download",SyncManager.message(e)); }
    }
    private void history(RemoteApi.Remote document) {
        request(api -> api.revisions(document.id()),revisions -> showRevisionsDialog(document,revisions));
    }
    private static String sourceLabel(String source) {
        if (source == null || source.isBlank()) return "";
        return " · " + switch (source) {
            case "web" -> "Web";
            case "android" -> "Android";
            case "ios" -> "iOS";
            case "merge" -> "Auto-merged";
            case "restore" -> "Restored";
            case "api" -> "Sync";
            default -> source;
        };
    }
    private void showRevisionsDialog(RemoteApi.Remote document,List<RemoteApi.Remote> revisions) {
        new AlertDialog.Builder(activity).setTitle("Revisions · "+document.name()).setItems(revisions.stream()
                .map(r -> "Revision "+r.revision()+sourceLabel(r.source())+" · "+r.size()+" bytes").toArray(String[]::new),(d,i) -> {
            RemoteApi.Remote revision=revisions.get(i);
            new AlertDialog.Builder(activity).setTitle("Revision "+revision.revision()).setItems(
                    new String[]{"View full screen…","Download to a local file…","Restore on server…"},(dialog,which) -> {
                        switch(which) {
                            case 0 -> request(api -> api.revision(document.id(),revision.revision()),r -> showFullScreenRevision(document,r));
                            case 1 -> request(api -> api.revision(document.id(),revision.revision()),r -> download(r,false));
                            case 2 -> confirmRestore(document,revision,null);
                        }
                    }).setNegativeButton("Cancel",null).show();
        }).setNeutralButton("Prune",(d,w) -> request(api -> api.prune(document.id()),remaining -> {
            int removed=Math.max(0,revisions.size()-remaining.size());
            app.notice("Revisions",removed > 0 ? "Pruned "+removed+" older revision"+(removed == 1 ? "." : "s.") : "No revisions needed pruning.");
        })).setNegativeButton("Close",null).show();
    }

    private void confirmRestore(RemoteApi.Remote document,RemoteApi.Remote revision,Dialog parent) {
        new AlertDialog.Builder(activity).setTitle("Restore revision "+revision.revision()+"?")
                .setMessage("This creates a new server revision containing the older text. The current version remains in history.")
                .setPositiveButton("Restore on server",(dialog,w) -> {
                    if(parent != null) parent.dismiss();
                    request(api -> {
                        var old=revision.content() != null ? revision : api.revision(document.id(),revision.revision());
                        return api.put(document.id(),old.name(),old.content(),document.revision(),UUID.randomUUID().toString());
                    },restored -> app.sync.syncAll(true));
                })
                .setNegativeButton("Cancel",null).show();
    }

    private void showFullScreenRevision(RemoteApi.Remote document,RemoteApi.Remote revision) {
        final String text;
        try { text=TextCodec.decode(revision.content()).text(); }
        catch(Exception e) { app.notice("Could not view revision",SyncManager.message(e)); return; }
        Dialog dialog=new Dialog(activity,android.R.style.Theme_DeviceDefault_NoActionBar);
        float density=activity.getResources().getDisplayMetrics().density;
        int pad=Math.round(16*density);
        LinearLayout root=new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xff101313);
        LinearLayout header=new LinearLayout(activity); header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(pad,pad,pad,pad); header.setBackgroundColor(0xff19201d);
        TextView title=new TextView(activity); title.setText(document.name()+" · Revision "+revision.revision());
        title.setTextColor(0xffe3e8df); title.setTextSize(15); title.setSingleLine(true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button downloadBtn=new Button(activity); downloadBtn.setText("Download"); downloadBtn.setAllCaps(false);
        downloadBtn.setOnClickListener(v -> { dialog.dismiss(); download(revision,false); });
        header.addView(downloadBtn);
        if(!revision.revision().equals(document.revision())) {
            Button restoreBtn=new Button(activity); restoreBtn.setText("Restore"); restoreBtn.setAllCaps(false);
            restoreBtn.setOnClickListener(v -> confirmRestore(document,revision,dialog));
            header.addView(restoreBtn);
        }
        Button closeBtn=new Button(activity); closeBtn.setText("Close"); closeBtn.setAllCaps(false);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(closeBtn);
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(activity); scroll.setFillViewport(true);
        TextView body=new TextView(activity); body.setText(text); body.setTextColor(0xffe3e8df);
        body.setTextSize(app.settings.fontSize()); body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(pad,pad,pad,pad); body.setTextIsSelectable(true);
        scroll.addView(body,new LinearLayout.LayoutParams(-1,-2));
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(root);
        dialog.show();
    }

    private interface Query<T> { T run(RemoteApi api) throws Exception; }
    private <T> void request(Query<T> query,Consumer<T> done) {
        if(app.sync.busy) { app.notice("Sync","Wait until the current sync finishes."); return; }
        final RemoteApi api;
        try { api=app.sync.api(); } catch(Exception e) { app.notice("Sync settings",SyncManager.message(e)); return; }
        app.sync.busy=true; app.sync.status="Contacting server…"; app.refresh();
        app.io.execute(() -> {
            try {
                T value=query.run(api);
                app.main.post(() -> { app.sync.busy=false; app.sync.status="Connected"; app.refresh(); if(alive()) done.accept(value); finishQueued(); });
            } catch(Exception e) {
                app.main.post(() -> { app.sync.busy=false; app.sync.status=SyncManager.message(e); app.refresh(); app.notice("Sync failed",app.sync.status); finishQueued(); });
            }
        });
    }
    private void finishQueued() { if(app.sync.rerun) { app.sync.rerun=false; app.sync.syncAll(false); } }
    private LinearLayout fields() {
        LinearLayout layout=new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL);
        int padding=Math.round(22*activity.getResources().getDisplayMetrics().density); layout.setPadding(padding,padding,padding,padding); return layout;
    }
    private TextView label(String text) { TextView view=new TextView(activity); view.setText(text); view.setTextSize(13); return view; }
    private EditText input(String hint,boolean password) {
        EditText view=new EditText(activity); view.setHint(hint); view.setSingleLine(true); view.setSaveEnabled(false);
        view.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI)); return view;
    }
}
