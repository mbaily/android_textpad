package org.hopto.git23069.textpad;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;
import android.net.Uri;
import android.provider.DocumentsContract;
import okhttp3.OkHttpClient;
import org.hopto.git23069.textpad.core.DocumentBuffer;
import org.hopto.git23069.textpad.core.SaveTransaction;
import org.hopto.git23069.textpad.core.TextCodec;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in live contract check. Credentials and the public CA are supplied in app-private files. */
public class LiveSyncInstrumentedTest {
    private void main(Runnable task) { InstrumentationRegistry.getInstrumentation().runOnMainSync(task); }
    private void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.currentTimeMillis()+15000;
        while(System.currentTimeMillis()<deadline) {
            AtomicBoolean ready=new AtomicBoolean();main(() -> ready.set(condition.getAsBoolean()));
            if(ready.get()) return;
            Thread.sleep(30);
        }
        fail("Timed out waiting for live synchronization");
    }

    @Test public void deployedHttpsApiPreservesBytesHistoryAndRetryOrder() throws Exception {
        assumeTrue("Live server checks require explicit opt-in",
                "true".equals(InstrumentationRegistry.getArguments().getString("liveTextpadServer")));
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir();
        File settings = new File(directory, "live-sync-test.json");
        File certificate = new File(directory, "live-sync-ca.crt");
        var originalFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        try {
            JSONObject configuration = new JSONObject(new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8));
            String token = configuration.getString("token");
            String id = configuration.getString("document_id");
            KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
            roots.load(null);
            try (var input = new FileInputStream(certificate)) {
                roots.setCertificateEntry("textpad-test-ca", CertificateFactory.getInstance("X.509").generateCertificate(input));
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(roots);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            // Trust the supplied CA for this test only; the normal hostname verifier remains active.
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            SyncHttpClient api = new SyncHttpClient(SyncHttpClient.DEFAULT_ENDPOINT, token);
            byte[] first = "Android HTTPS: café · e\u0301 · 😀 · العربية · 日本語\r\n".getBytes(StandardCharsets.UTF_8);
            String request = UUID.randomUUID().toString();
            var accepted = api.put(id, "Android live verification.txt", first, null, request);
            assertArrayEquals(first, api.get(id).content());
            assertTrue(api.list().stream().anyMatch(document -> document.id().equals(id)));
            byte[] second = ("\uFEFFUTF-16 live revision 😀\r\n").getBytes(StandardCharsets.UTF_16LE);
            var newer = api.put(id, "Android live verification.txt", second, accepted.revision(), UUID.randomUUID().toString());
            assertEquals(accepted.revision(), api.put(id, "Android live verification.txt", first, null, request).revision());
            assertEquals(newer.revision(), api.get(id).revision());
            assertArrayEquals(second, api.get(id).content());
            assertArrayEquals(first, api.revision(id, accepted.revision()).content());
            assertEquals(2, api.revisions(id).size());
            try {
                new SyncHttpClient(SyncHttpClient.DEFAULT_ENDPOINT, "invalid-test-token").list();
                fail("An invalid API token must be rejected");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("rejected the API token"));
            }
            try {
                new SyncHttpClient(SyncHttpClient.DEFAULT_ENDPOINT+"/not-a-sync-server",token).list();
                fail("A missing API route must explain that the sync server address is wrong");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("HTTP 404"));
                assertTrue(expected.getMessage().contains("Server settings"));
                assertTrue(expected.getMessage().contains(SyncHttpClient.DEFAULT_ENDPOINT));
            }
            // Real server notifications invoke the same reconciliation queue as a save.
            TextpadApplication app=(TextpadApplication)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
            Uri uri=DocumentsContract.buildDocumentUri("org.hopto.git23069.textpad.test.documents","live-events-"+UUID.randomUUID()+".txt");
            try(var output=app.getContentResolver().openOutputStream(uri,"wt")) { output.write(second); }
            EditorDocument document=new EditorDocument("Android live verification.txt",new DocumentBuffer(TextCodec.decode(second)));
            document.uri=uri;document.savedHash=SaveTransaction.hash(second);
            OkHttpClient socketClient=new OkHttpClient.Builder().sslSocketFactory(context.getSocketFactory(),(X509TrustManager)trust.getTrustManagers()[0])
                    .pingInterval(25,TimeUnit.SECONDS).build();
            SyncEvents events=new SyncEvents(app,socketClient);
            try {
                main(() -> {
                    app.tabs.clear();app.sync.prefs.edit().clear().commit();app.sync.testApi=api;
                    app.sync.link(document,id,newer.revision(),newer.hash());app.addTab(document);
                    app.sync.status="Waiting for the event connection";
                    events.start(SyncHttpClient.DEFAULT_ENDPOINT,token);
                });
                waitFor(() -> !app.sync.busy && app.sync.status.contains("downloaded"));
                byte[] pushed="Live WebSocket update café 😀\r\n".getBytes(StandardCharsets.UTF_8);
                var pushedVersion=api.put(id,document.name,pushed,newer.revision(),UUID.randomUUID().toString());
                waitFor(() -> document.buffer.text().equals("Live WebSocket update café 😀\n"));
                assertArrayEquals(pushed,app.store.read(uri));
                main(() -> { document.buffer.edit(document.buffer.length(),document.buffer.length(),"unsaved");app.sync.status="Waiting for dirty-buffer event"; });
                byte[] background="Background worker update 😀\n".getBytes(StandardCharsets.UTF_8);
                api.put(id,document.name,background,pushedVersion.revision(),UUID.randomUUID().toString());
                waitFor(() -> !app.sync.busy && app.sync.status.contains("1 pending"));
                assertArrayEquals(pushed,app.store.read(uri));
                assertTrue(document.buffer.text().endsWith("unsaved"));
                main(() -> { events.stop();document.buffer.undo();app.tabs.clear(); });
                var executor=Executors.newSingleThreadExecutor();
                try {
                    assertEquals(ListenableWorker.Result.success(),TestWorkerBuilder.from(app,SyncWorker.class,executor).build().doWork());
                } finally { executor.shutdownNow(); }
                assertArrayEquals(background,app.store.read(uri));
                assertTrue(app.store.backups().stream().filter(backup -> backup.originalUri().equals(uri.toString())).count()>=2);
            } finally {
                main(() -> { events.stop();app.tabs.clear();app.sync.testApi=null;app.sync.prefs.edit().clear().commit(); });
                socketClient.connectionPool().evictAll();socketClient.dispatcher().executorService().shutdown();
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory);
            Files.deleteIfExists(settings.toPath());
            Files.deleteIfExists(certificate.toPath());
        }
    }
}
