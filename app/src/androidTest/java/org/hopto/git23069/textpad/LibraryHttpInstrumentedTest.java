package org.hopto.git23069.textpad;

import androidx.test.platform.app.InstrumentationRegistry;
import org.hopto.git23069.textpad.core.Folder;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Optional real HTTPS contract against an isolated test server, never production data. */
public class LibraryHttpInstrumentedTest {
    @Test public void nativeRequestsPreserveFoldersBytesHistoryAndConditionalRenames() throws Exception {
        assumeTrue("Requires the isolated HTTPS test fixture", "true".equals(InstrumentationRegistry.getArguments().getString("libraryHttpFixture")));
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir();
        File settings = new File(directory, "library-http-test.json"), certificate = new File(directory, "library-http-ca.crt");
        var original = HttpsURLConnection.getDefaultSSLSocketFactory();
        try {
            JSONObject configuration = new JSONObject(new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8));
            KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType()); roots.load(null);
            try (var input = new FileInputStream(certificate)) { roots.setCertificateEntry("library-test", CertificateFactory.getInstance("X.509").generateCertificate(input)); }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(roots);
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(null, trust.getTrustManagers(), null);
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());
            SyncHttpClient api = new SyncHttpClient(configuration.getString("endpoint"), configuration.getString("token"));
            Folder home = api.putFolder("home", "Home", null), child = api.putFolder("child", "Child", home.id());
            Folder renamed = api.renameFolder(child.id(), "Renamed child");
            assertEquals(home.id(), renamed.parentId());
            byte[] bytes = ("\uFEFFNative café 😀\r\n").getBytes(StandardCharsets.UTF_16LE);
            String request = UUID.randomUUID().toString();
            var one = api.createDocument("one", "One.txt", bytes, request, child.id());
            var two = api.createDocument("two", "Two.txt", new byte[0], UUID.randomUUID().toString(), child.id());
            assertEquals(child.id(), one.folderId());
            assertEquals(one.revision(), api.createDocument("one", "One.txt", bytes, request, child.id()).revision());
            assertArrayEquals(bytes, api.get("one").content());
            var moved = api.moveDocuments(List.of(one.id(), two.id()), home.id());
            assertEquals(List.of(home.id(), home.id()), moved.stream().map(RemoteApi.Remote::folderId).toList());
            assertEquals(one.revision(), moved.get(0).revision()); assertEquals(1, api.revisions(one.id()).size());
            var name = api.renameDocument(one.id(), "New name.txt", one.revision(), UUID.randomUUID().toString());
            assertEquals(home.id(), name.folderId()); assertArrayEquals(bytes, api.get(one.id()).content());
            assertEquals(2, api.revisions(one.id()).size());
            try { api.renameDocument(one.id(), "Stale.txt", one.revision(), UUID.randomUUID().toString()); fail("Must reject stale revision"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("revision changed")); }
            try { api.deleteFolder(home.id()); fail("Must reject nonempty deletion"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("files and subfolders")); }
            try { api.moveDocuments(List.of(one.id(), "missing"), null); fail("Must reject entire group move"); }
            catch (IOException expected) { assertEquals(home.id(), api.get(one.id()).folderId()); }
            api.deleteFolder(child.id()); api.moveDocuments(List.of(one.id(), two.id()), null); api.deleteFolder(home.id());
            assertTrue(api.folders().isEmpty()); assertNull(api.get(one.id()).folderId()); assertArrayEquals(bytes, api.get(one.id()).content());
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
            Files.deleteIfExists(settings.toPath()); Files.deleteIfExists(certificate.toPath());
        }
    }
}
