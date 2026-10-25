package org.hopto.git23069.textpad.core;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class SyncEndpointTest {
    @Test public void oldPublicAddressesMoveToCaddy() {
        for (String endpoint : List.of(
                "https://git23069.hopto.org:8443",
                "https://git23069.hopto.org/android_textpad",
                "https://git23069.hopto.org:443/android_textpad",
                " HTTPS://GIT23069.HOPTO.ORG/android_textpad/// ")) {
            assertEquals(SyncEndpoint.DEFAULT, SyncEndpoint.migrate(endpoint));
        }
    }

    @Test public void customServersPathsAndProtocolsAreNeverRepointed() {
        for (String endpoint : List.of(
                "https://example.com/android_textpad", "https://git23069.hopto.org.evil.example/android_textpad",
                "https://git23069.hopto.org:8443/custom", "https://git23069.hopto.org:9443/custom",
                "https://git23069.hopto.org/android_textpad/other", "http://git23069.hopto.org/android_textpad",
                "https://git23069.hopto.org/android_textpad?profile=other", SyncEndpoint.DEFAULT,
                "", "  ")) {
            assertEquals(endpoint, SyncEndpoint.migrate(endpoint));
        }
        assertNull(SyncEndpoint.migrate(null));
    }

    @Test public void migratingAgainDoesNotChangeTheNewAddress() {
        assertEquals(SyncEndpoint.DEFAULT, SyncEndpoint.migrate(SyncEndpoint.migrate("https://git23069.hopto.org/android_textpad")));
    }
}
