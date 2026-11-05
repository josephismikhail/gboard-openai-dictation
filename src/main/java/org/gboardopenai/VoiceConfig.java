// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-only encrypted configuration. Never contains repository defaults or a bundled key. */
public final class VoiceConfig {
    private static final String ALIAS = "gboard-openai-config";
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public static synchronized void save(Context context, JSONObject config) throws Exception {
        String token = config.getString("key");
        if (!token.startsWith("sk-") || token.length() < 40 || token.matches(".*\\s.*"))
            throw new IllegalArgumentException("Invalid credential shape");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(config.toString().getBytes("UTF-8")), Base64.NO_WRAP);
        if (!context.getSharedPreferences("openai_secure", 0).edit()
            .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString("config", encrypted).commit()) throw new IllegalStateException("Credential save failed");
    }
    public static synchronized JSONObject load(Context context) throws Exception {
        android.content.SharedPreferences prefs = context.getSharedPreferences("openai_secure", 0);
        String data = prefs.getString("config", "");
        if (data.isEmpty()) throw new IllegalStateException("OpenAI dictation needs setup");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
            Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), "UTF-8"));
    }
    private VoiceConfig() {}
}
