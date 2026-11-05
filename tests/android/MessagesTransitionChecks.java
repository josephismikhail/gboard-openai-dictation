package org.gboardopenai.test;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.gboardopenai.MessagesAccessibilityService;
import org.gboardopenai.MessagesControlPolicy;
import org.gboardopenai.MessagesHandoff;
import org.gboardopenai.VoiceRuntime;
import org.json.JSONObject;

/** Opt-in measurement of the current empty Messages composer; no text, audio, or network writes. */
public final class MessagesTransitionChecks {
    private final Instrumentation instrumentation;
    private android.app.UiAutomation automation;
    private Object service,target;
    private AccessibilityNodeInfo recorder;
    private int composerId,samples,paired,misses,nativeUnavailable,modeViolations,iconSamples;
    private int maxDistance;
    private long offsetSince,maxOffsetDuration;
    private MessagesTransitionChecks(Instrumentation value){instrumentation=value;}

    public static JSONObject run(Instrumentation instrumentation)throws Exception{
        return new MessagesTransitionChecks(instrumentation).run();
    }
    private interface Work {void run()throws Exception;}
    private void main(Work action)throws Exception{
        Throwable[] failure={null};
        instrumentation.runOnMainSync(()->{try{action.run();}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);
    }
    private static Object field(Object object,String name)throws Exception{
        Field value=(object instanceof Class?(Class<?>)object:object.getClass()).getDeclaredField(name);
        value.setAccessible(true);return value.get(object instanceof Class?null:object);
    }
    private Object freshTarget()throws Exception{
        Method value=service.getClass().getDeclaredMethod("findTarget");value.setAccessible(true);return value.invoke(service);
    }
    private void validateInitialTarget()throws Exception{
        if(VoiceRuntime.isVoiceUiVisible())throw new AssertionError("Finish or cancel dictation before the transition measurement");
        if(target==null)target=freshTarget();
        if(target==null)throw new AssertionError("No supported Messages recorder/composer target; nothing was changed. "+diagnostics());
        AccessibilityNodeInfo composer=(AccessibilityNodeInfo)field(target,"composer");
        CharSequence text=composer.getText();
        if(!composer.isShowingHintText() && text!=null && text.length()!=0)
            throw new AssertionError("Transition measurement requires an empty draft; nothing was changed");
        if(!((MessagesHandoff.Target)target).isCurrent())throw new AssertionError("Messages target is not current");
        composerId=(Integer)field(service,"composerResourceId");
        recorder=AccessibilityNodeInfo.obtain((AccessibilityNodeInfo)field(target,"recorder"));
    }
    private String diagnostics()throws Exception{
        android.accessibilityservice.AccessibilityService s=(android.accessibilityservice.AccessibilityService)service;
        int roots=0,composer=0,header=0,send=0,focused=0;
        java.util.ArrayList<AccessibilityNodeInfo> nodes=new java.util.ArrayList<>();
        for(android.view.accessibility.AccessibilityWindowInfo window:s.getWindows()){
            try{
                if(window.getType()!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                if(window.isActive() || window.isFocused())focused++;
                AccessibilityNodeInfo root=window.getRoot();if(root!=null){roots++;nodes.add(root);}
            }finally{window.recycle();}
        }
        for(int i=0;i<nodes.size() && i<1500;i++){
            AccessibilityNodeInfo node=nodes.get(i);String id=node.getViewIdResourceName();
            if(MessagesControlPolicy.COMPOSER_VIEW_ID.equals(id))composer++;
            if("top_app_bar_title_row".equals(id))header++;
            if(MessagesControlPolicy.RECORDER_VIEW_ID.equals(id))send++;
            for(int j=0;j<node.getChildCount();j++){AccessibilityNodeInfo child=node.getChild(j);if(child!=null)nodes.add(child);}
        }
        for(AccessibilityNodeInfo node:nodes)node.recycle();
        return "roots="+roots+",focused="+focused+",composer="+composer+",header="+header+",sharedButton="+send;
    }
    private void focusComposer()throws Exception{
        if(!((MessagesHandoff.Target)target).isCurrent())throw new AssertionError("Messages target changed; refusing to focus another editor");
        AccessibilityNodeInfo composer=(AccessibilityNodeInfo)field(target,"composer");
        Rect bounds=new Rect();composer.getBoundsInScreen(bounds);
        long now=SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+20,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{
            if(!automation.injectInputEvent(down,false) || !automation.injectInputEvent(up,false))throw new AssertionError("Could not tap the verified Messages composer");
        }finally{down.recycle();up.recycle();}
    }
    private InputMethodService messagesIme()throws Exception{
        Map<?,?> sessions=(Map<?,?>)field(VoiceRuntime.class,"SESSIONS");
        for(Object key:sessions.keySet()){
            InputMethodService ime=(InputMethodService)key;
            if(ime.getCurrentInputEditorInfo()!=null && MessagesControlPolicy.MESSAGES_PACKAGE.equals(ime.getCurrentInputEditorInfo().packageName))return ime;
        }
        return null;
    }
    private void sample()throws Exception{
        samples++;
        if(!MessagesAccessibilityService.isRecorderMode(composerId))modeViolations++;
        Map<?,?> sessions=(Map<?,?>)field(VoiceRuntime.class,"SESSIONS");
        for(Map.Entry<?,?> entry:sessions.entrySet()){
            InputMethodService ime=(InputMethodService)entry.getKey();
            if(!ime.isInputViewShown() || ime.getCurrentInputEditorInfo()==null
                || !MessagesControlPolicy.MESSAGES_PACKAGE.equals(ime.getCurrentInputEditorInfo().packageName))continue;
            View button=(View)field(entry.getValue(),"button");
            if(button!=null && button.isShown()){
                iconSamples++;
                if(!(Boolean)field(button,"recorder"))modeViolations++;
            }
        }
        if(!recorder.refresh() || !recorder.isVisibleToUser()){nativeUnavailable++;finishOffsetRun();return;}
        Rect expected=new Rect();recorder.getBoundsInScreen(expected);
        if(expected.isEmpty()){nativeUnavailable++;finishOffsetRun();return;}
        View shortcut=(View)field(service,"shortcut");
        if(shortcut==null || !shortcut.isShown() || !shortcut.isLaidOut() || shortcut.getWidth()==0 || shortcut.getHeight()==0){misses++;finishOffsetRun();return;}
        if(android.os.Build.VERSION.SDK_INT>=34 && ((android.view.WindowManager.LayoutParams)shortcut.getLayoutParams()).canPlayMoveAnimation())
            throw new AssertionError("Shortcut window adds a second movement animation");
        int[] at=new int[2];shortcut.getLocationOnScreen(at);
        Rect actual=new Rect(at[0],at[1],at[0]+shortcut.getWidth(),at[1]+shortcut.getHeight());
        int distance=Math.max(Math.max(Math.abs(expected.left-actual.left),Math.abs(expected.right-actual.right)),
            Math.max(Math.abs(expected.top-actual.top),Math.abs(expected.bottom-actual.bottom)));
        paired++;maxDistance=Math.max(maxDistance,distance);
        if(distance>2){if(offsetSince==0)offsetSince=SystemClock.uptimeMillis();}
        else finishOffsetRun();
    }
    private void finishOffsetRun(){
        if(offsetSince!=0){maxOffsetDuration=Math.max(maxOffsetDuration,SystemClock.uptimeMillis()-offsetSince);offsetSince=0;}
    }
    private void measure()throws Exception{
        long until=SystemClock.uptimeMillis()+1000;
        do{main(this::sample);SystemClock.sleep(25);}while(SystemClock.uptimeMillis()<until);
        finishOffsetRun();
    }
    private JSONObject run()throws Exception{
        automation=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        long until=SystemClock.uptimeMillis()+5000;
        do{
            main(()->service=field(MessagesAccessibilityService.class,"instance"));
            if(service!=null)break;
            SystemClock.sleep(100);
        }while(SystemClock.uptimeMillis()<until);
        if(service==null)throw new AssertionError("Messages accessibility service did not connect within five seconds");
        boolean[] restored={false};boolean interactionStarted=false;
        try{
            until=SystemClock.uptimeMillis()+5000;
            do{
                main(()->target=freshTarget());
                if(target!=null)break;
                SystemClock.sleep(100);
            }while(SystemClock.uptimeMillis()<until);
            main(this::validateInitialTarget);
            interactionStarted=true;
            main(this::focusComposer);measure();
            main(()->{
                if(!((MessagesHandoff.Target)target).isCurrent())throw new AssertionError("Messages target changed during measurement");
                InputMethodService ime=messagesIme();
                if(ime==null)throw new AssertionError("Messages keyboard did not attach");
                ime.requestHideSelf(0);
            });
            measure();
            main(()->{if(!((Map<?,?>)field(VoiceRuntime.class,"RETIRED_MICS")).isEmpty())throw new AssertionError("Retired keyboard controls outlived their exit animation");});
        }finally{
            final boolean restore=interactionStarted;
            if(target!=null)main(()->{
                try{
                    if(restore && ((MessagesHandoff.Target)target).isCurrent()){focusComposer();restored[0]=true;}
                }finally{((AutoCloseable)target).close();if(recorder!=null)recorder.recycle();}
            });
        }
        JSONObject metrics=new JSONObject().put("samples",samples).put("pairedSamples",paired)
            .put("maxOffsetPx",maxDistance).put("longestOffsetRunMs",maxOffsetDuration)
            .put("overlayMisses",misses).put("nativeUnavailableSamples",nativeUnavailable)
            .put("iconSamples",iconSamples).put("modeViolations",modeViolations).put("keyboardRestoreRequested",restored[0]);
        if(modeViolations!=0)throw new AssertionError("Recorder icon reverted during empty-composer measurement: "+metrics);
        if(paired==0 || iconSamples==0)throw new AssertionError("No visible keyboard/shortcut measurements: "+metrics);
        return metrics;
    }
}
