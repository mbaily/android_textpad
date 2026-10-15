package org.hopto.git23069.textpad.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Strict text decoding. Line terminators are represented separately from editor text. */
public final class TextCodec {
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    public enum Encoding {
        UTF8("UTF-8", StandardCharsets.UTF_8),
        UTF16_LE("UTF-16 LE", StandardCharsets.UTF_16LE),
        UTF16_BE("UTF-16 BE", StandardCharsets.UTF_16BE),
        ASCII("ASCII", StandardCharsets.US_ASCII);

        public final String label;
        public final Charset charset;
        Encoding(String label, Charset charset) { this.label = label; this.charset = charset; }
    }

    public record Decoded(String text, Encoding encoding, boolean bom, List<String> endings) {
        public String preferredEnding() {
            if (endings.isEmpty()) return "\n";
            int lf = 0, crlf = 0, cr = 0;
            for (String e : endings) {
                if (e.equals("\r\n")) crlf++;
                else if (e.equals("\r")) cr++;
                else lf++;
            }
            return crlf > lf && crlf >= cr ? "\r\n" : cr > lf ? "\r" : "\n";
        }
    }

    private TextCodec() {}

    public static Decoded decode(byte[] bytes) throws IOException {
        return decode(bytes, null);
    }

    public static Decoded decode(byte[] bytes, Encoding explicitEncoding) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("This file exceeds the 10 MiB limit.");
        Encoding encoding = Encoding.UTF8;
        int skip = 0;
        if (starts(bytes, 0xff, 0xfe, 0, 0) || starts(bytes, 0, 0, 0xfe, 0xff)) {
            throw new IOException("UTF-32 is not supported. Use UTF-8 or UTF-16.");
        }
        if (starts(bytes, 0xef, 0xbb, 0xbf)) skip = 3;
        else if (starts(bytes, 0xff, 0xfe)) { encoding = Encoding.UTF16_LE; skip = 2; }
        else if (starts(bytes, 0xfe, 0xff)) { encoding = Encoding.UTF16_BE; skip = 2; }
        else if (explicitEncoding != null) encoding = explicitEncoding;
        else if (bytes.length >= 4 && bytes.length % 2 == 0) {
            int even = 0, odd = 0, pairs = Math.min(bytes.length / 2, 512);
            for (int i = 0; i < pairs * 2; i += 2) {
                if (bytes[i] == 0) even++;
                if (bytes[i + 1] == 0) odd++;
            }
            if (odd > pairs / 2 && even == 0) encoding = Encoding.UTF16_LE;
            else if (even > pairs / 2 && odd == 0) encoding = Encoding.UTF16_BE;
        }
        try {
            String raw = encoding.charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, skip, bytes.length - skip)).toString();
            validateText(raw);
            return split(raw, encoding, skip > 0);
        } catch (CharacterCodingException e) {
            throw new IOException("Unsupported encoding or invalid text. Use valid UTF-8 or UTF-16.", e);
        }
    }

    public static Decoded split(String raw, Encoding encoding, boolean bom) {
        StringBuilder text = new StringBuilder(raw.length());
        List<String> endings = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                    i++; endings.add("\r\n");
                } else endings.add("\r");
                text.append('\n');
            } else {
                text.append(c);
                if (c == '\n') endings.add("\n");
            }
        }
        return new Decoded(text.toString(), encoding, bom, List.copyOf(endings));
    }

    public static String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n'); }

    public static byte[] encode(String text, Encoding encoding, boolean bom, List<String> endings)
            throws IOException {
        StringBuilder raw = new StringBuilder(text.length());
        int line = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                String ending = endings.get(line++);
                // Adjacent CR and LF separators must still represent two logical line breaks.
                if (ending.equals("\n") && raw.length() > 0 && raw.charAt(raw.length() - 1) == '\r') raw.append('\r');
                raw.append(ending);
            }
            else raw.append(c);
        }
        if (line != endings.size()) throw new IllegalArgumentException("Inconsistent line terminators");
        validateText(raw);
        try {
            ByteBuffer encoded = encoding.charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw));
            byte[] prefix = !bom ? new byte[0] : switch (encoding) {
                case UTF8 -> new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
                case UTF16_LE -> new byte[]{(byte) 0xff, (byte) 0xfe};
                case UTF16_BE -> new byte[]{(byte) 0xfe, (byte) 0xff};
                case ASCII -> new byte[0];
            };
            if (encoded.remaining() + prefix.length > MAX_BYTES)
                throw new IOException("The saved file would exceed the 10 MiB limit.");
            byte[] result = new byte[encoded.remaining() + prefix.length];
            System.arraycopy(prefix, 0, result, 0, prefix.length);
            encoded.get(result, prefix.length, encoded.remaining());
            return result;
        } catch (CharacterCodingException e) {
            throw new IOException("Some characters cannot be saved as " + encoding.label + ". Choose UTF-8 or UTF-16.", e);
        }
    }

    private static void validateText(CharSequence text) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 32 && c != '\t' && c != '\n' && c != '\r' && c != '\f') || c == 127)
                throw new IOException("Binary files are not supported (non-text control characters found).");
        }
    }

    private static boolean starts(byte[] b, int... prefix) {
        if (b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((b[i] & 255) != prefix[i]) return false;
        return true;
    }
}
