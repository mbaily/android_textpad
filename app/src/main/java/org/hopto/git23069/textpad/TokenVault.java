package org.hopto.git23069.textpad;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TokenVault {
    private static final String ALIAS = "textpad-sync-token";
    private static SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
            if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        } catch (java.io.IOException e) { throw new GeneralSecurityException(e); }
    }
    static void store(SharedPreferences prefs, String token) throws GeneralSecurityException {
        if (token.isEmpty()) { prefs.edit().remove("token").apply(); return; }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        String value = Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP) + ":"
                + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
        prefs.edit().putString("token",value).apply();
    }
    static String read(SharedPreferences prefs) throws GeneralSecurityException {
        String value = prefs.getString("token", ""); if (value.isEmpty()) return "";
        try {
            String[] parts = value.split(":"); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
        } catch (RuntimeException e) { throw new GeneralSecurityException("Please enter the sync token again.",e); }
    }
}
