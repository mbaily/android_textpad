package org.hopto.git23069.textpad;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;

/** The main thread owns connection lifetime; no socket is kept while the activity is stopped. */
final class SyncEvents {
    private final TextpadApplication app;
    private OkHttpClient client;
    private WebSocket socket;
    private String endpoint,token;
    private long generation;
    private int attempts;
    private Runnable reconnect;
    SyncEvents(TextpadApplication app) { this.app=app; }
    SyncEvents(TextpadApplication app,OkHttpClient client) { this.app=app;this.client=client; }
    void start(String nextEndpoint,String nextToken) {
        String normalized=nextEndpoint.replaceAll("/+$","");
        if(normalized.equals(endpoint) && nextToken.equals(token)) return;
        stop(); endpoint=normalized; token=nextToken; connect();
    }
    void stop() {
        generation++; endpoint=null; token=null; attempts=0;
        if(reconnect!=null) { app.main.removeCallbacks(reconnect); reconnect=null; }
        if(socket!=null) { socket.cancel(); socket=null; }
    }
    private void connect() {
        if(endpoint==null) return;
        if(client==null) client=new OkHttpClient.Builder().connectTimeout(10,TimeUnit.SECONDS)
                .readTimeout(40,TimeUnit.SECONDS).pingInterval(25,TimeUnit.SECONDS).build();
        long current=++generation;
        try {
            Request request=new Request.Builder().url(endpoint+"/api/v1/events").header("Authorization","Bearer "+token).build();
            socket=client.newWebSocket(request,new WebSocketListener() {
                @Override public void onMessage(WebSocket ws,String text) {
                    try {
                        String type=new JSONObject(text).optString("type");
                        if(!type.equals("ready") && !type.equals("changed")) return;
                        app.main.post(() -> { if(current==generation) {
                            attempts=0; app.sync.syncAll(false);
                            if (app.filesVisible && app.library != null) app.library.refresh();
                        } });
                    } catch(Exception ignored) { }
                }
                @Override public void onClosing(WebSocket ws,int code,String reason) { ws.close(code,null); }
                @Override public void onClosed(WebSocket ws,int code,String reason) { lost(current); }
                @Override public void onFailure(WebSocket ws,Throwable error,Response response) { lost(current); }
            });
        } catch(IllegalArgumentException e) { lost(current); }
    }
    private void lost(long current) {
        app.main.post(() -> {
            if(current!=generation || endpoint==null || reconnect!=null) return;
            socket=null;
            long delay=Math.min(60_000,1_000L << Math.min(attempts++,6));
            reconnect=() -> { reconnect=null; connect(); };
            app.main.postDelayed(reconnect,delay);
        });
    }
}
