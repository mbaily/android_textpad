package org.hopto.git23069.textpad.core;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class TextCodecTest {
    @Test public void unicodeAndMixedLineEndingsRoundTripByteForByte() throws Exception {
        String text = "😀 café e\u0301\r\nעברית\nالعربية\rশেষ";
        for (TextCodec.Encoding encoding : new TextCodec.Encoding[]{TextCodec.Encoding.UTF8, TextCodec.Encoding.UTF16_LE, TextCodec.Encoding.UTF16_BE}) {
            for (boolean bom : new boolean[]{false, true}) {
                var initial = TextCodec.split(text, encoding, bom);
                byte[] bytes = TextCodec.encode(initial.text(), encoding, bom, initial.endings());
                // BOM-less UTF-16 without an ASCII-dominated sample is inherently ambiguous.
                if (encoding != TextCodec.Encoding.UTF8 && !bom) continue;
                var decoded = TextCodec.decode(bytes);
                assertArrayEquals(bytes, new DocumentBuffer(decoded).snapshot().bytes());
            }
        }
    }
    @Test public void bomlessUtf16WithAsciiCanBeDetected() throws Exception {
        for (TextCodec.Encoding encoding : new TextCodec.Encoding[]{TextCodec.Encoding.UTF16_LE, TextCodec.Encoding.UTF16_BE}) {
            var decoded = TextCodec.decode("Hello\r\nworld\r\n".getBytes(encoding.charset));
            assertEquals(encoding, decoded.encoding()); assertFalse(decoded.bom());
            assertEquals("Hello\nworld\n", decoded.text());
        }
    }
    @Test public void explicitEncodingSupportsAmbiguousBomlessUtf16() throws Exception {
        byte[] bytes = "漢字 Ελληνικά العربية".getBytes(StandardCharsets.UTF_16LE);
        var decoded = TextCodec.decode(bytes, TextCodec.Encoding.UTF16_LE);
        assertEquals("漢字 Ελληνικά العربية", decoded.text());
        assertArrayEquals(bytes, new DocumentBuffer(decoded).snapshot().bytes());
    }
    @Test public void adjacentDifferentTerminatorsStillRepresentSeparateLinesAfterAnEdit() throws Exception {
        var doc = new DocumentBuffer(TextCodec.split("a\rb\n", TextCodec.Encoding.UTF8, false));
        doc.edit(2,3,"");
        assertEquals("a\n\n", TextCodec.decode(doc.snapshot().bytes()).text());
        doc.undo(); assertArrayEquals("a\rb\n".getBytes(StandardCharsets.UTF_8),doc.snapshot().bytes());
    }
    @Test public void emptyAndAsciiFilesDefaultToUtf8() throws Exception {
        assertEquals(TextCodec.Encoding.UTF8, TextCodec.decode(new byte[0]).encoding());
        assertEquals("hello", TextCodec.decode("hello".getBytes(StandardCharsets.US_ASCII)).text());
    }
    @Test public void rejectsInvalidUtf8InsteadOfReplacingCharacters() {
        assertThrows(IOException.class, () -> TextCodec.decode(new byte[]{(byte) 0xc3, 0x28}));
    }
    @Test public void rejectsMalformedUtf16() {
        assertThrows(IOException.class, () -> TextCodec.decode(new byte[]{(byte) 0xff, (byte) 0xfe, 0x3d, (byte) 0xd8}));
    }
    @Test public void rejectsBinaryAndUtf32() {
        assertThrows(IOException.class, () -> TextCodec.decode(new byte[]{1, 2, 3, 0}));
        assertThrows(IOException.class, () -> TextCodec.decode(new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0, 65, 0, 0, 0}));
    }
    @Test public void asciiSaveRejectsUnrepresentableCharacters() {
        var doc = new DocumentBuffer(TextCodec.split("café", TextCodec.Encoding.ASCII, false));
        assertThrows(IOException.class, doc::snapshot);
    }
    @Test public void acceptsTenMiBButRejectsLargerFiles() throws Exception {
        byte[] bytes = new byte[TextCodec.MAX_BYTES]; Arrays.fill(bytes, (byte) 'a');
        assertArrayEquals(bytes, new DocumentBuffer(TextCodec.decode(bytes)).snapshot().bytes());
        assertThrows(IOException.class, () -> TextCodec.decode(new byte[TextCodec.MAX_BYTES + 1]));
        var doc = new DocumentBuffer(TextCodec.split("é".repeat(TextCodec.MAX_BYTES / 2 + 1), TextCodec.Encoding.UTF8, false));
        assertThrows(IOException.class, doc::snapshot);
    }
}
