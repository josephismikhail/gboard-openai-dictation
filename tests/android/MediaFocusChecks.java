package org.gboardopenai.test;

import android.app.Instrumentation;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.gboardopenai.MediaPlaybackGate;
import org.gboardopenai.VoiceRuntime;
import org.gboardopenai.VoiceUiState;
import org.json.JSONObject;

/** Queries real IME audio-focus eligibility without recording audio or making API calls. */
public final class MediaFocusChecks {
    private static Object field(Object target,String name)throws Exception{
        Field value=target.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(target);
    }
    private static Object call(Object target,String name)throws Exception{
        Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(target);
    }
    private static void main(Instrumentation instrumentation,Work work)throws Exception{
        Throwable[] failure={null};instrumentation.runOnMainSync(()->{try{work.run();}catch(Throwable error){failure[0]=error;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private interface Work{void run()throws Exception;}
    public static JSONObject run(Instrumentation instrumentation)throws Exception{
        Object[] current={null};
        for(int i=0;i<40 && current[0]==null;i++){
            SystemClock.sleep(250);
            main(instrumentation,()->{
                Field sessions=VoiceRuntime.class.getDeclaredField("SESSIONS");sessions.setAccessible(true);
                for(Object session:((Map<?,?>)sessions.get(null)).values()){
                    InputMethodService ime=(InputMethodService)field(session,"ime");
                    if(ime.getCurrentInputEditorInfo()!=null
                            && "org.gboardopenai.test".equals(ime.getCurrentInputEditorInfo().packageName)
                            && field(session,"button")!=null){current[0]=session;break;}
                }
            });
        }
        if(current[0]==null)throw new AssertionError("Open the local QA editor with the dictation keyboard selected");
        JSONObject result=new JSONObject();Object session=current[0];
        main(instrumentation,()->{
            try{
                if(!(Boolean)call(session,"showPanel"))throw new AssertionError("Could not show real IME voice window");
                MediaPlaybackGate gate=(MediaPlaybackGate)field(session,"mediaPlayback");
                if(gate==null)throw new AssertionError("Media focus gate did not initialize");
                boolean granted=gate.isAcquired();
                result.put("audioFocusGranted",granted);
                VoiceUiState state=(VoiceUiState)field(session,"ui");state.begin();state.recording();state.stop();
                if(field(session,"mediaPlayback")!=gate || gate.isAcquired()!=granted)
                    throw new AssertionError("Media focus was released before processing finished");
                result.put("heldThroughProcessing",granted);
                call(session,"removePanel");
                if(gate.isAcquired() || field(session,"mediaPlayback")!=null)
                    throw new AssertionError("Media focus was not released with the voice window");
                result.put("releasedAfterClose",true);
            }finally{call(session,"cancel");}
        });
        return result;
    }
}
