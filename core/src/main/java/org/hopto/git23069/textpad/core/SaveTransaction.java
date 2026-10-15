package org.hopto.git23069.textpad.core;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Compare before overwrite; back up before truncation; attempt rollback on failure. */
public final class SaveTransaction {
    public interface Target {
        byte[] read() throws IOException;
        void write(byte[] bytes) throws IOException;
    }
    public interface Backup { void store(byte[] original) throws IOException; }
    public static final class Changed extends IOException {
        public final byte[] actualHash;
        public Changed(byte[] actualHash) {
            super("The file has changed since it was opened or last saved."); this.actualHash = actualHash;
        }
    }
    private SaveTransaction() {}

    public static byte[] save(Target target, byte[] expectedHash, byte[] replacement, Backup backup)
            throws IOException {
        byte[] original = target.read(), actualHash = hash(original);
        if (!Arrays.equals(actualHash, expectedHash)) throw new Changed(actualHash);
        if (Arrays.equals(original, replacement)) return actualHash;
        backup.store(original); // Do not touch the destination if a backup cannot be made.
        try {
            target.write(replacement);
            if (!Arrays.equals(target.read(), replacement)) throw new IOException("The saved contents could not be verified.");
        } catch (IOException failure) {
            try {
                target.write(original);
                if (!Arrays.equals(target.read(), original)) throw new IOException("Rollback contents could not be verified.");
            }
            catch (IOException rollback) { failure.addSuppressed(rollback); }
            throw new IOException("Save failed. Your edits are still open and the previous file is in Backups. "
                    + (failure.getSuppressed().length == 0 ? "The original contents were restored." : "The original could not be restored automatically.")
                    + " " + failure.getMessage(), failure);
        }
        return hash(replacement);
    }

    public static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
