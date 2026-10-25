package org.hopto.git23069.textpad.core;

import java.util.Locale;

/** Known addresses of the same Textpad deployment; other servers stay unchanged. */
public final class SyncEndpoint {
    public static final String DEFAULT = "https://git23069.hopto.org:9443/android_textpad";

    private SyncEndpoint() {}

    public static String migrate(String endpoint) {
        if (endpoint == null) return null;
        String normalized = endpoint.trim().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "https://git23069.hopto.org:8443",
                 "https://git23069.hopto.org/android_textpad",
                 "https://git23069.hopto.org:443/android_textpad" -> DEFAULT;
            default -> endpoint;
        };
    }
}
