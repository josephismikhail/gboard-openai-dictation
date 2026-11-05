package org.gboardopenai.test;

import android.app.Instrumentation;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.os.Build;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.view.inputmethod.InputConnection;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.gboardopenai.VoiceRuntime;
import org.gboardopenai.VoiceUiState;
import org.gboardopenai.VoicePanel;
import org.gboardopenai.DictationContext;
import org.gboardopenai.EditorContext;
import org.gboardopenai.DictationInsertion;

/** Exercises the installed IME's real window and touch routing, without recording or API calls. */
public final class VoiceWindowIntegrationChecks {
    private final Instrumentation instrumentation;
    private Object session;private InputMethodService ime;
    private boolean selectionCase;
    private final float[] point=new float[2];
    private VoiceWindowIntegrationChecks(Instrumentation instrumentation){this.instrumentation=instrumentation;}
    public static void run(Instrumentation instrumentation) throws Exception {new VoiceWindowIntegrationChecks(instrumentation).run();}
    public static void runSelection(Instrumentation instrumentation) throws Exception {
        VoiceWindowIntegrationChecks check=new VoiceWindowIntegrationChecks(instrumentation);check.selectionCase=true;check.run();
    }
    public static void preview(Instrumentation instrumentation)throws Exception{
        VoiceWindowIntegrationChecks check=new VoiceWindowIntegrationChecks(instrumentation);
        check.awaitTestEditor();
        try{
            check.main(check::show);
            android.util.Log.i("PixelVoicePreview","Recording preview started");
            for(int frame=0;frame<32;frame++){
                check.main(()->{
                    check.requireTestEditor();
                    VoicePanel panel=(VoicePanel)field(check.session,"panel");
                    if(panel==null)throw new AssertionError("Recording preview was dismissed");
                    panel.level(.45f);
                });
                SystemClock.sleep(250);
            }
            check.main(()->{
                check.requireTestEditor();
                if(!((VoiceUiState)field(check.session,"ui")).stop())throw new AssertionError("Preview did not stop");
                check.call("showProcessing");
            });
            android.util.Log.i("PixelVoicePreview","Processing preview started");
            for(int frame=0;frame<32;frame++){
                check.main(check::requireTestEditor);SystemClock.sleep(250);
            }
        }finally{check.main(()->check.call("cancel"));}
    }
    private static Object field(Object object,String name)throws Exception{
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private Object call(String name)throws Exception{
        Method m=session.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(session);
    }
    private interface Check{void run()throws Exception;}
    private void main(Check check)throws Exception{
        Throwable[] failure={null};instrumentation.runOnMainSync(()->{try{check.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private void requireTestEditor(){
        if(ime.getCurrentInputEditorInfo()==null || !"org.gboardopenai.test".equals(ime.getCurrentInputEditorInfo().packageName))
            throw new AssertionError("Only the blank local QA editor may be operated");
    }
    private void tap()throws Exception{
        main(this::requireTestEditor);
        long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,point[0],point[1],0);
        MotionEvent up=MotionEvent.obtain(now,now+40,MotionEvent.ACTION_UP,point[0],point[1],0);
        try{
            if(!instrumentation.getUiAutomation().injectInputEvent(down,true) || !instrumentation.getUiAutomation().injectInputEvent(up,true))throw new AssertionError("Touch injection failed");
        }finally{down.recycle();up.recycle();}
        SystemClock.sleep(200);
    }
    private void longPress()throws Exception{
        main(this::requireTestEditor);
        long now=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,point[0],point[1],0);
        try{
            if(!instrumentation.getUiAutomation().injectInputEvent(down,true))throw new AssertionError("Long press down failed");
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+150);
            MotionEvent up=MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,point[0],point[1],0);
            try{if(!instrumentation.getUiAutomation().injectInputEvent(up,true))throw new AssertionError("Long press up failed");}
            finally{up.recycle();}
        }finally{down.recycle();}
        SystemClock.sleep(250);
    }
    private void awaitTestEditor()throws Exception{
        // The caller launches the empty test activity and reselects the IME after instrumentation starts.
        for(int i=0;i<40 && session==null;i++){
            SystemClock.sleep(250);
            main(()->{
                Field f=VoiceRuntime.class.getDeclaredField("SESSIONS");f.setAccessible(true);
                for(Object value:((Map<?,?>)f.get(null)).values()){
                    InputMethodService candidate=(InputMethodService)field(value,"ime");
                    if(candidate.getCurrentInputEditorInfo()!=null && "org.gboardopenai.test".equals(candidate.getCurrentInputEditorInfo().packageName) && field(value,"button")!=null){session=value;ime=candidate;break;}
                }
            });
        }
        if(session==null)throw new AssertionError("QA editor and attached microphone required");
    }
    private void run()throws Exception{
        awaitTestEditor();
        if(selectionCase)SystemClock.sleep(900);
        InputConnection[] connection={null};WindowManager.LayoutParams original=new WindowManager.LayoutParams();View[] surface={null};float[] alpha={0};
        DictationContext[] snapshot={null};
        try{
            main(()->{
                requireTestEditor();connection[0]=ime.getCurrentInputConnection();original.copyFrom(ime.getWindow().getWindow().getAttributes());
                if(selectionCase && !"red".contentEquals(connection[0].getSelectedText(0)))throw new AssertionError("Synthetic selected word missing before recording");
                if(selectionCase){snapshot[0]=EditorContext.capture(connection[0],ime.getCurrentInputEditorInfo());if(snapshot[0]==null)throw new AssertionError("Full context unavailable before recording");}
                show();surface[0]=(View)field(session,"keyboardSurface");alpha[0]=(Float)field(session,"surfaceAlpha");
            });
            SystemClock.sleep(650);
            main(()->{
                View overlay=(View)field(session,"overlay");int[] location=new int[2];overlay.getLocationOnScreen(location);
                View root=(View)field(session,"root");
                if(!root.hasWindowFocus())throw new AssertionError("Voice window did not take focus from editor selection controls");
                if(selectionCase && !"red".contentEquals(connection[0].getSelectedText(0)))throw new AssertionError("Recording changed the selected range");
                if(selectionCase && !snapshot[0].sameTarget(EditorContext.capture(connection[0],ime.getCurrentInputEditorInfo())))throw new AssertionError("Full message changed during recording");
                // Reproduce stale geometry while discovery is throttled. A layout notification must repair it immediately.
                FrameLayout.LayoutParams stale=(FrameLayout.LayoutParams)overlay.getLayoutParams();
                stale.width=Math.max(1,root.getWidth()-100);stale.height=Math.max(1,root.getHeight()-100);overlay.setLayoutParams(stale);
                Field lastScan=session.getClass().getDeclaredField("lastScan");lastScan.setAccessible(true);lastScan.setLong(session,SystemClock.elapsedRealtime());
                ((ViewTreeObserver.OnGlobalLayoutListener)field(session,"layoutListener")).onGlobalLayout();
                if(overlay.getLayoutParams().width!=root.getWidth() || overlay.getLayoutParams().height!=root.getHeight())
                    throw new AssertionError("Throttled discovery left stale full-screen geometry");
                if(overlay.getHeight()<ime.getResources().getDisplayMetrics().heightPixels*.8f)throw new AssertionError("Panel is not full screen");
                point[0]=location[0]+overlay.getWidth()*.25f;point[1]=location[1]+overlay.getHeight()*.2f;
                if(ime.getCurrentInputConnection()!=connection[0])throw new AssertionError("Full-screen entry changed the editor connection");
            });
            tap(); // This point is above the original keyboard: verifies the patched WMS touch region.
            SystemClock.sleep(400); // Let the ordinary keyboard finish restoring its bounds.
            main(()->{
                if(((VoiceUiState)field(session,"ui")).phase()!=VoiceUiState.Phase.PROCESSING)throw new AssertionError("Upper-screen tap did not stop recording");
                if(field(session,"overlay")!=null || field(session,"panel")!=null || field(session,"windowState")!=null)
                    throw new AssertionError("Processing did not dismiss the full-screen voice surface");
                if(surface[0].getAlpha()!=alpha[0])throw new AssertionError("Processing did not restore the keyboard alpha");
                View mic=(View)field(session,"button");
                if(!(Boolean)field(mic,"processing") || mic.getVisibility()!=View.VISIBLE)
                    throw new AssertionError("Processing spinner is not visible on the keyboard microphone");
                if(((View)field(session,"nativeMic")).getAlpha()!=0)throw new AssertionError("Native microphone remains behind the spinner");
                VoiceRuntime.selectionChanged(ime,0,0,0,0);
                if((Boolean)field(session,"targetChanged"))throw new AssertionError("Unchanged selection invalidated dictation");
                VoiceRuntime.selectionChanged(ime,0,0,1,1);
                if(!(Boolean)field(session,"targetChanged"))throw new AssertionError("Selection movement must invalidate pending insertion");
                if(ime.getCurrentInputConnection()!=connection[0])throw new AssertionError("Processing changed the editor connection");
                WindowManager.LayoutParams restored=ime.getWindow().getWindow().getAttributes();
                if(restored.flags!=original.flags || restored.format!=original.format
                        || (Build.VERSION.SDK_INT>=30 && restored.getFitInsetsTypes()!=original.getFitInsetsTypes()))
                    throw new AssertionError("Processing did not restore the ordinary IME window");
                int[] location=new int[2];mic.getLocationOnScreen(location);
                point[0]=location[0]+mic.getWidth()/2f;point[1]=location[1]+mic.getHeight()/2f;
            });
            longPress();
            main(()->{
                if(field(session,"overlay")!=null || surface[0].getAlpha()!=alpha[0])throw new AssertionError("Cancel did not restore keyboard");
                if(((VoiceUiState)field(session,"ui")).phase()!=VoiceUiState.Phase.IDLE
                    || (Boolean)field(field(session,"button"),"processing"))throw new AssertionError("Cancel did not clear the microphone spinner");
                if(((View)field(session,"nativeMic")).getAlpha()!=(Float)field(session,"nativeMicAlpha"))
                    throw new AssertionError("Cancel did not restore the native microphone alpha");
                WindowManager.LayoutParams restored=ime.getWindow().getWindow().getAttributes();
                if(restored.flags!=original.flags || (Build.VERSION.SDK_INT>=30 && restored.getFitInsetsTypes()!=original.getFitInsetsTypes()))throw new AssertionError("Window flags/insets leaked");
                if(ime.getCurrentInputConnection()!=connection[0])throw new AssertionError("Cancel changed the editor connection");
                if(selectionCase && !"red".contentEquals(connection[0].getSelectedText(0)))throw new AssertionError("Cancel changed the selected range");
                if(selectionCase){
                    if(!DictationInsertion.commit(connection[0],"green",ime.getCurrentInputEditorInfo()))throw new AssertionError("Selected replacement failed");
                }
                show();
            });
            SystemClock.sleep(450);main(this::requireTestEditor);
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            SystemClock.sleep(400);
            main(()->{if(field(session,"overlay")!=null)throw new AssertionError("Back did not cancel and remove the full-screen view");});
        }finally{main(()->call("cancel"));}
    }
    private void show()throws Exception{
        requireTestEditor();
        if(!(Boolean)call("showPanel"))throw new AssertionError("Could not open real voice window");
        VoiceUiState ui=(VoiceUiState)field(session,"ui");ui.begin();ui.recording();
        ((VoicePanel)field(session,"panel")).setPhase(ui.phase());
    }
}
