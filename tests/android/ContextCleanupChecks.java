package org.gboardopenai.test;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.gboardopenai.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.gboardopenai.libraries.com.trevornk.ramblr.CloudCleanupFidelityGuard;
import org.gboardopenai.libraries.com.trevornk.ramblr.FidelityVerdict;

/** Explicit opt-in live test; synthetic messages only, using the key already stored on the phone. */
public final class ContextCleanupChecks {
    public static void run(Context context)throws Exception{
        JSONObject config=VoiceConfig.load(context);config.put("useMessageContext",true);
        List<String> vocabulary=new ArrayList<>();JSONArray words=config.optJSONArray("vocabulary");
        if(words!=null)for(int i=0;i<words.length();i++)vocabulary.add(words.getString(i));
        String message="Please make the red button blue.";int start=message.indexOf("red");
        String replacement=clean(context,config,vocabulary,new DictationContext(message,start,start+3),"Green.");
        if(!replacement.equals("green"))throw new AssertionError("Selected-word cleanup did not fit the existing sentence");
        String insertion=clean(context,config,vocabulary,new DictationContext("Please bring for dinner.",13,13),"Pizza and salad.");
        if(!insertion.equals("pizza and salad"))throw new AssertionError("Mid-sentence cleanup forced standalone punctuation");
        String list="- Apples\n- Pears";
        String next=clean(context,config,vocabulary,new DictationContext(list,list.length(),list.length()),"Bananas.");
        if(!next.startsWith("\n- ") || !next.toLowerCase(Locale.ROOT).contains("bananas") || next.contains("Apples") || next.contains("Pears"))
            throw new AssertionError("Cleanup did not continue the existing list");
        int between="- Apples\n".length();
        String middle=clean(context,config,vocabulary,new DictationContext(list,between,between),"Bananas.");
        if(!middle.startsWith("- ") || !middle.endsWith("\n") || !middle.toLowerCase(Locale.ROOT).contains("bananas")
            || middle.contains("Apples") || middle.contains("Pears"))throw new AssertionError("Cleanup did not insert between existing list items");
        StringBuilder earlier=new StringBuilder("The project name is Mistral Orchard.\n\n");
        for(int i=0;i<50;i++)earlier.append("Earlier details are complete. ");
        String full=earlier.append("\n\n").toString();
        String named=clean(context,config,vocabulary,new DictationContext(full,full.length(),full.length()),"mistral orchard shipped today");
        if(!named.contains("Mistral Orchard") || named.length()>100)throw new AssertionError("Cleanup did not use full-message naming context");
    }
    private static String clean(Context context,JSONObject config,List<String> vocabulary,DictationContext snapshot,String raw)throws Exception{
        VoiceEngine engine=new VoiceEngine(context,new VoiceEngine.Listener(){
            public void recording(){}public void result(String text){}public void error(String safeMessage){}
        },snapshot);
        Field setting=VoiceEngine.class.getDeclaredField("config");setting.setAccessible(true);setting.set(engine,config);
        Field terms=VoiceEngine.class.getDeclaredField("vocabulary");terms.setAccessible(true);terms.set(engine,vocabulary);
        Method cleanup=VoiceEngine.class.getDeclaredMethod("cleanup",String.class);cleanup.setAccessible(true);
        try{
            JSONObject response;
            try{response=(JSONObject)cleanup.invoke(engine,raw);}
            catch(java.lang.reflect.InvocationTargetException failure){
                Throwable cause=failure.getCause();
                String safe=cause==null?"unknown":cause.getClass().getSimpleName();
                if(cause!=null && "API rejected request".equals(cause.getMessage()))safe+=" (API rejected request)";
                if(cause instanceof java.net.UnknownHostException)safe+=" ("+cause.getMessage()+")";
                if(cause!=null && cause.getStackTrace().length>0)safe+=" at "+cause.getStackTrace()[0];
                throw new AssertionError("Live cleanup failed: "+safe);
            }
            String result=VoiceEngine.outputText(response,true);
            if(!(CloudCleanupFidelityGuard.INSTANCE.check(raw,result,vocabulary) instanceof FidelityVerdict.Ok))
                throw new AssertionError("Contextual cleanup failed the production fidelity guard");
            return result;
        }finally{engine.cancel();setting.set(engine,null);}
    }
}
