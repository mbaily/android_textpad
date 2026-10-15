package org.hopto.git23069.textpad.core;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SaveTransactionTest {
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static class MemoryTarget implements SaveTransaction.Target {
        byte[] contents = bytes("original"); int writes; boolean fail, corrupt;
        public byte[] read() { return contents; }
        public void write(byte[] b) throws IOException {
            writes++; contents = b;
            if (fail && writes == 1) { contents = bytes("partial"); throw new IOException("Disk full"); }
            if (corrupt && writes == 1) contents = bytes("corrupted");
        }
    }
    @Test public void backsUpBeforeWritingAndReturnsVerifiedHash() throws Exception {
        MemoryTarget target = new MemoryTarget(); List<byte[]> backup = new ArrayList<>();
        byte[] hash = SaveTransaction.save(target, SaveTransaction.hash(target.contents), bytes("edited"), b -> {
            assertEquals(0,target.writes); backup.add(b);
        });
        assertArrayEquals(bytes("original"),backup.get(0)); assertArrayEquals(SaveTransaction.hash(bytes("edited")),hash);
    }
    @Test public void changedFileIsNeverWrittenBeforeConfirmation() {
        MemoryTarget target = new MemoryTarget();
        assertThrows(SaveTransaction.Changed.class, () -> SaveTransaction.save(target, SaveTransaction.hash(bytes("old")),bytes("edited"),b -> fail()));
        assertEquals(0,target.writes);
    }
    @Test public void failedBackupPreventsAnyOverwrite() {
        MemoryTarget target = new MemoryTarget();
        assertThrows(IOException.class, () -> SaveTransaction.save(target, SaveTransaction.hash(target.contents),bytes("edited"),b -> { throw new IOException("No space for backup"); }));
        assertEquals(0,target.writes); assertArrayEquals(bytes("original"),target.contents);
    }
    @Test public void failedWriteRestoresOriginalAndKeepsBackup() {
        MemoryTarget target = new MemoryTarget(); target.fail = true; List<byte[]> backup = new ArrayList<>();
        assertThrows(IOException.class, () -> SaveTransaction.save(target, SaveTransaction.hash(target.contents),bytes("edited"),backup::add));
        assertArrayEquals(bytes("original"),target.contents); assertArrayEquals(bytes("original"),backup.get(0)); assertEquals(2,target.writes);
    }
    @Test public void verificationFailureRestoresOriginal() {
        MemoryTarget target = new MemoryTarget(); target.corrupt = true;
        assertThrows(IOException.class, () -> SaveTransaction.save(target, SaveTransaction.hash(target.contents),bytes("edited"),b -> {}));
        assertArrayEquals(bytes("original"),target.contents);
    }
    @Test public void unchangedSaveDoesNotWriteOrBackUp() throws Exception {
        MemoryTarget target = new MemoryTarget();
        SaveTransaction.save(target, SaveTransaction.hash(target.contents),target.contents,b -> fail()); assertEquals(0,target.writes);
    }
    @Test public void failedRollbackIsReportedWithoutClaimingSuccess() {
        SaveTransaction.Target target = new SaveTransaction.Target() {
            int writes;
            public byte[] read() { return writes == 0 ? bytes("original") : bytes("partial"); }
            public void write(byte[] ignored) throws IOException { writes++; throw new IOException("Storage unavailable"); }
        };
        IOException failure = assertThrows(IOException.class, () -> SaveTransaction.save(target,
                SaveTransaction.hash(bytes("original")),bytes("edited"),b -> {}));
        assertTrue(failure.getMessage().contains("could not be restored automatically"));
    }
}
