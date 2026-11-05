package org.gboardopenai.test;

import android.app.Instrumentation;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.gboardopenai.VoiceConfig;
import org.gboardopenai.VoiceRuntime;
import org.json.JSONObject;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Explicit ADB-only setup harness, signed locally to match the keyboard. No key is printed. */
public final class SetupInstrumentation extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args){arguments=args;start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            String action=arguments.getString("action", "verify");
            KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
            String alias="gboard-openai-migration";
            if("contextCleanupTest".equals(action)){
                Thread.sleep(Math.min(10000,Math.max(0,Integer.parseInt(arguments.getString("delayMs","0")))));
                ContextCleanupChecks.run(getTargetContext());
                result.putString("contextCleanupTests","passed: selected-word replacement, mid-sentence insertion, list continuation, whole-message names, fidelity guard");
            } else if("editorContextTest".equals(action)){
                final Throwable[] failure={null};
                runOnMainSync(()->{try{EditorContextChecks.run(getTargetContext());}catch(Throwable error){failure[0]=error;}});
                if(failure[0]!=null)throw new AssertionError(failure[0]);
                result.putString("editorContextTests","passed: full message, selection, partial-extract fallback, password exclusion");
            } else if("windowTest".equals(action)){
                VoiceWindowIntegrationChecks.run(this);
                result.putString("windowTests","passed: real full-screen window, upper-screen stop, processing cancel, editor connection, window restoration, Back cancellation");
            } else if("insertionTest".equals(action)){
                final Throwable[] failure={null};
                runOnMainSync(()->{try{DictationInsertionChecks.run(getTargetContext());}catch(Throwable error){failure[0]=error;}});
                if(failure[0]!=null)throw new AssertionError(failure[0]);
                result.putString("insertionTests","passed: repeated dictation, typing continuation, existing space, selection replacement, punctuation, structured fields");
            } else if("panelTest".equals(action)){
                final Throwable[] failure={null};
                runOnMainSync(()->{try{VoicePanelChecks.run(getTargetContext());}catch(Throwable error){failure[0]=error;}});
                if(failure[0]!=null)throw new AssertionError(failure[0]);
                result.putString("panelTests","passed: full-screen touch coverage, duplicate stop, accessibility, opacity, teardown, speech contrast, breathing shape, inset and window restoration");
            } else if("publicKey".equals(action)){
                if(store.containsAlias(alias))store.deleteEntry(alias);
                KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");
                generator.initialize(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(3072).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build());
                KeyPair pair=generator.generateKeyPair();
                result.putString("publicKey",Base64.encodeToString(pair.getPublic().getEncoded(),Base64.NO_WRAP));
            } else if("import".equals(action)){
                JSONObject envelope=new JSONObject(new String(Base64.decode(arguments.getString("envelope"),Base64.NO_WRAP),"UTF-8"));
                Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");
                rsa.init(Cipher.DECRYPT_MODE,store.getKey(alias,null),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
                byte[] key=rsa.doFinal(Base64.decode(envelope.getString("wrappedKey"),Base64.NO_WRAP));
                Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");
                aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));
                JSONObject config=new JSONObject(new String(aes.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP)),"UTF-8"));
                VoiceConfig.save(getTargetContext(),config);store.deleteEntry(alias);
                result.putString("status","Encrypted configuration saved");
            } else if("inspect".equals(action)){
                Thread.sleep(Math.min(10000, Math.max(0, Integer.parseInt(arguments.getString("delayMs", "0")))));
                final JSONObject[] snapshot=new JSONObject[1];
                runOnMainSync(()->{try{snapshot[0]=VoiceRuntime.inspectActive();}catch(Exception ignored){}});
                result.putString("snapshot",snapshot[0]==null?"{}":snapshot[0].toString());
            } else if("metrics".equals(action)) {
                try(java.io.InputStream in=getTargetContext().openFileInput("pixelvoice-last-metrics.json")){
                    java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;
                    while((count=in.read(buffer))>0)bytes.write(buffer,0,count);
                    result.putString("metrics",bytes.toString("UTF-8"));
                }
            } else {
                JSONObject config=VoiceConfig.load(getTargetContext());
                result.putBoolean("configured",config.getString("key").startsWith("sk-"));
                result.putString("transcription","gpt-live-transcribe");result.putString("cleanup","gpt-6.1-sol/ultrafast/low");
            }
            finish(-1,result);
        }catch(Throwable error){
            result.putString("error",error.getClass().getSimpleName());
            if(arguments.getString("action","").endsWith("Test"))result.putString("checkFailure",String.valueOf(error.getMessage()));
            finish(0,result);
        }
    }
}
