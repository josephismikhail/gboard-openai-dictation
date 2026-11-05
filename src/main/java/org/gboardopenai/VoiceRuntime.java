// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.inputmethodservice.InputMethodService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.graphics.*;
import android.view.*;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.WeakHashMap;

/** Stock microphone entry point, full-screen listening panel, no routine toasts. */
public final class VoiceRuntime {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<InputMethodService, Session> SESSIONS = new WeakHashMap<>();
    public static void attach(InputMethodService ime) {
        Runnable attach=() -> {
            Session session=SESSIONS.get(ime);
            if(session==null) { session=new Session(ime); SESSIONS.put(ime, session); }
            // Install the transparent action target before the keyboard's first paint.
            session.scan();
            session.scanLater();
        };
        if(Looper.myLooper()==Looper.getMainLooper())attach.run();else MAIN.post(attach);
    }
    public static void detach(InputMethodService ime) {
        Session session=SESSIONS.remove(ime); if(session!=null) session.close();
    }
    /** Runs after Gboard computes its normal editor insets, including each early return. */
    public static void adjustInsets(InputMethodService ime, InputMethodService.Insets insets) {
        Session session=SESSIONS.get(ime);
        if(session==null || session.overlay==null)return;
        int[] location=new int[2];session.overlay.getLocationInWindow(location);
        VoiceWindowInsets.apply(insets,new Rect(location[0],location[1],
            location[0]+session.overlay.getWidth(),location[1]+session.overlay.getHeight()));
    }
    /** Selection movement invalidates the original insertion point, even in editors without full extracts. */
    public static void selectionChanged(InputMethodService ime,int oldStart,int oldEnd,int newStart,int newEnd){
        Session session=SESSIONS.get(ime);
        if(session!=null && session.ui.phase()!=VoiceUiState.Phase.IDLE
                && (oldStart!=newStart || oldEnd!=newEnd))session.targetChanged=true;
    }
    public static JSONObject inspect(InputMethodService ime) throws Exception {
        JSONObject result=new JSONObject(); Session s=SESSIONS.get(ime);
        result.put("attached",s!=null).put("micFound",s!=null && s.button!=null);
        if(s!=null)result.put("phase",s.ui.phase().name()).put("panelVisible",s.panel!=null).put("fullScreen",s.overlay!=null)
            .put("mediaFocusHeld",s.mediaPlayback!=null && s.mediaPlayback.isAcquired());
        JSONArray rows=new JSONArray();
        if (ime.getWindow()!=null) describe(ime.getWindow().getWindow().getDecorView(),rows,0);
        return result.put("views",rows);
    }
    public static JSONObject inspectActive() throws Exception {
        for(InputMethodService ime:SESSIONS.keySet())return inspect(ime);
        return new JSONObject().put("attached",false);
    }
    private static void describe(View v,JSONArray rows,int depth) throws Exception {
        if(depth>15 || rows.length()>700) return;
        String id=""; try { id=v.getResources().getResourceEntryName(v.getId()); } catch(Exception ignored) {}
        Rect rect=new Rect(); boolean visible=v.getGlobalVisibleRect(rect);
        String desc=v.getContentDescription()==null?"":v.getContentDescription().toString();
        rows.put(new JSONObject().put("class",v.getClass().getName()).put("id",id)
            .put("visible",visible).put("rect",rect.toShortString()).put("depth",depth)
            .put("micDescription",desc.toLowerCase(java.util.Locale.ROOT).matches(".*(voice|microphone|dictation).*")));
        if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) describe(((ViewGroup)v).getChildAt(i),rows,depth+1);
    }
    private static final class Session {
        final InputMethodService ime;
        VoiceEngine engine; InputConnection editor; MicButton button; View nativeMic;
        DictationContext editorContext;
        InsertionAnchor insertionAnchor;boolean targetChanged;
        final VoiceUiState ui = new VoiceUiState();
        VoicePanel panel; View keyboardSurface; float surfaceAlpha; int surfaceAccessibility;
        MediaPlaybackGate mediaPlayback;
        FrameLayout overlay; VoiceWindowState windowState;
        android.window.OnBackInvokedCallback voiceBack;
        ViewGroup root; ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        ViewTreeObserver.OnPreDrawListener preDrawListener;
        int generation; boolean closed, reportedMissing; long lastScan;
        Session(InputMethodService ime) { this.ime=ime; }
        void scanLater() {
            if(closed) return;
            for(long delay:new long[]{30,150,400,1000}) MAIN.postDelayed(this::scan,delay);
        }
        void scan() {
            if(closed || ime.getWindow()==null || ime.getWindow().getWindow()==null) return;
            View decor=ime.getWindow().getWindow().getDecorView();
            if(!(decor instanceof FrameLayout)) return;
            if(root!=decor) {
                cancel(); removeButton(); root=(ViewGroup)decor;
                layoutListener=() -> {
                    // Window expansion must update geometry even when microphone discovery is throttled.
                    if(overlay!=null)positionPanel();
                    long now=android.os.SystemClock.elapsedRealtime();
                    if(now-lastScan>200) {lastScan=now; MAIN.post(this::scan);}
                };
                root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
                preDrawListener=()->{
                    if(button==null || nativeMic==null || !nativeMic.isAttachedToWindow() || !nativeMic.isShown())scan();
                    // The native glyph stays visible; adding a transparent touch
                    // target does not require delaying the first rendered frame.
                    return true;
                };
                root.getViewTreeObserver().addOnPreDrawListener(preDrawListener);
            }
            View target=findMic(root);
            if(target==null) {
                if(!reportedMissing){reportedMissing=true;MAIN.postDelayed(()->{
                    if(closed || button!=null)return;
                    try{android.util.Log.i("PixelVoiceViews",inspect(ime).toString());}catch(Exception ignored){}
                },1600);}
                return;
            }
            Rect bounds=new Rect(); if(!target.getGlobalVisibleRect(bounds)) return;
            target.getDrawingRect(bounds);root.offsetDescendantRectToMyCoords(target,bounds);
            if(button==null) {
                button=new MicButton(ime);
                button.setOnClickListener(v->toggle());
                button.setOnLongClickListener(v->{ if(ui.phase()!=VoiceUiState.Phase.IDLE){cancel();}else{ime.startActivity(new Intent(ime,VoiceSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));} return true; });
                root.addView(button);
                android.util.Log.i("PixelVoice","Microphone attached inside keyboard");
            }
            if(nativeMic!=target){
                if(nativeMic!=null)nativeMic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
                nativeMic=target;
            }
            nativeMic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(bounds.width(),bounds.height());
            p.leftMargin=bounds.left-root.getPaddingLeft();p.topMargin=bounds.top-root.getPaddingTop();p.gravity=Gravity.TOP|Gravity.LEFT;
            FrameLayout.LayoutParams old=(FrameLayout.LayoutParams)button.getLayoutParams();
            if(old==null || old.width!=p.width || old.height!=p.height || old.leftMargin!=p.leftMargin || old.topMargin!=p.topMargin) button.setLayoutParams(p);
            if(panel!=null){positionPanel();button.setVisibility(View.INVISIBLE);overlay.bringToFront();}
            else {button.bringToFront();button.setVisibility(View.VISIBLE);}
        }
        View findMic(View v) {
            if(v==button || v==overlay || v.getVisibility()!=View.VISIBLE) return null;
            String d=v.getContentDescription()==null?"":v.getContentDescription().toString().toLowerCase(java.util.Locale.ROOT);
            String id="";try{id=v.getResources().getResourceEntryName(v.getId()).toLowerCase(java.util.Locale.ROOT);}catch(Exception ignored){}
            float density=ime.getResources().getDisplayMetrics().density;
            if((d.contains("voice input") || d.contains("voice typing") || d.contains("microphone") || d.contains("start dictation") || id.equals("voice_input_key"))
                && v.getWidth()>0 && v.getWidth()<density*100 && v.getHeight()<density*100) return v;
            if(v instanceof ViewGroup) for(int i=0;i<((ViewGroup)v).getChildCount();i++) {
                View result=findMic(((ViewGroup)v).getChildAt(i));if(result!=null)return result;
            }
            return null;
        }
        void toggle() {
            if(button==null || closed) return;
            if(ui.stop()) { showProcessing(); if(engine!=null)engine.stop(); return; }
            if(ui.phase()!=VoiceUiState.Phase.IDLE)return;
            editor=ime.getCurrentInputConnection(); if(editor==null)return;
            editor.finishComposingText();
            editorContext=EditorContext.capture(editor,ime.getCurrentInputEditorInfo());
            insertionAnchor=editorContext==null?InsertionAnchor.capture(editor):null;targetChanged=false;
            if(!showPanel()){editor=null;editorContext=null;insertionAnchor=null;Toast.makeText(ime,"Could not open the voice panel. Please reopen the keyboard.",Toast.LENGTH_LONG).show();return;}
            int current=++generation;
            ui.begin();panel.setPhase(ui.phase());
            engine=new VoiceEngine(ime,new VoiceEngine.Listener(){
                public void recording(){ MAIN.post(()->{if(current==generation && !closed){ui.recording();if(panel!=null)panel.setPhase(ui.phase());}}); }
                public void level(float value){ MAIN.post(()->{if(current==generation && panel!=null && ui.phase()==VoiceUiState.Phase.LISTENING)panel.level(value);}); }
                public void result(String text){ MAIN.post(()->{
                    if(current!=generation || closed)return;
                    if(editor==ime.getCurrentInputConnection() && text!=null && !text.isEmpty()){
                        if(targetChanged || (editorContext!=null
                                ? !editorContext.sameTarget(EditorContext.capture(editor,ime.getCurrentInputEditorInfo()))
                                : insertionAnchor==null || !insertionAnchor.matches(editor)))
                            Toast.makeText(ime,"The message changed while dictating. Please try again.",Toast.LENGTH_LONG).show();
                        else if(!DictationInsertion.commit(editor,text,ime.getCurrentInputEditorInfo()))Toast.makeText(ime,"Could not insert dictation into this text field.",Toast.LENGTH_LONG).show();
                    }
                    editor=null;editorContext=null;insertionAnchor=null;engine=null;ui.reset();removePanel();
                }); }
                public void error(String message){ MAIN.post(()->{
                    if(current!=generation || closed)return;
                    Toast.makeText(ime,message,Toast.LENGTH_LONG).show();
                    if(!message.startsWith("Cleanup ")){cancel();}
                }); }
            },editorContext);
            engine.start();
        }
        boolean showPanel(){
            float density=ime.getResources().getDisplayMetrics().density;
            View candidate=null;
            for(View v=nativeMic;v!=null && v!=root;v=v.getParent() instanceof View?(View)v.getParent():null){
                if(candidate!=null && !VoiceSurfaceBounds.canExpand(candidate.getHeight(),v.getHeight(),density))break;
                if(v.getWidth()>=root.getWidth()*.65f && v.getHeight()>120*density)candidate=v;
            }
            if(candidate==null || candidate.getHeight()<=0)return false;
            keyboardSurface=candidate;surfaceAlpha=candidate.getAlpha();surfaceAccessibility=candidate.getImportantForAccessibility();
            int accent=Color.WHITE;
            panel=new VoicePanel(ime,0xCC000000,accent);
            panel.setOnClickListener(v->toggle());
            panel.setOnLongClickListener(v->{cancel();return true;});
            overlay=new VoiceOverlay(ime,this::cancel);
            overlay.setAccessibilityPaneTitle("Voice dictation");
            overlay.addView(panel,new FrameLayout.LayoutParams(-1,-1));
            windowState=new VoiceWindowState(ime.getWindow().getWindow());
            root.addView(overlay);positionPanel();
            panel.setFocusableInTouchMode(true);panel.requestFocus();
            if(android.os.Build.VERSION.SDK_INT>=33){
                voiceBack=this::cancel;
                ime.getWindow().getWindow().getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,voiceBack);
            }
            candidate.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            button.setVisibility(View.INVISIBLE);overlay.bringToFront();root.requestLayout();
            // Hold transient audio focus through capture and processing. This asks
            // cooperative players to pause without changing the media volume.
            try{mediaPlayback=new MediaPlaybackGate(ime);}catch(RuntimeException ignored){mediaPlayback=null;}
            return true;
        }
        void positionPanel(){
            if(overlay==null)return;
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(root.getWidth(),root.getHeight());
            p.gravity=Gravity.TOP|Gravity.LEFT;p.leftMargin=-root.getPaddingLeft();p.topMargin=-root.getPaddingTop();
            FrameLayout.LayoutParams old=(FrameLayout.LayoutParams)overlay.getLayoutParams();
            if(old==null || old.width!=p.width || old.height!=p.height || old.leftMargin!=p.leftMargin || old.topMargin!=p.topMargin)overlay.setLayoutParams(p);
        }
        void showProcessing(){if(panel!=null)panel.setPhase(ui.phase());}
        void removePanel(){
            hidePanel();
            if(mediaPlayback!=null){mediaPlayback.close();mediaPlayback=null;}
        }
        void hidePanel(){
            if(voiceBack!=null && android.os.Build.VERSION.SDK_INT>=33){
                ime.getWindow().getWindow().getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(voiceBack);voiceBack=null;
            }
            if(panel!=null){panel.clear();panel=null;}
            if(overlay!=null){if(overlay.getParent() instanceof ViewGroup)((ViewGroup)overlay.getParent()).removeView(overlay);overlay=null;}
            if(windowState!=null){windowState.restore();windowState=null;}
            if(keyboardSurface!=null){keyboardSurface.setAlpha(surfaceAlpha);keyboardSurface.setImportantForAccessibility(surfaceAccessibility);keyboardSurface=null;}
            if(button!=null)button.setVisibility(View.VISIBLE);
            if(root!=null)root.requestLayout();
        }
        void cancel(){ generation++;if(engine!=null)engine.cancel();engine=null;editor=null;editorContext=null;insertionAnchor=null;ui.reset();removePanel(); }
        void removeObservers(){
            if(root!=null && layoutListener!=null && root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            if(root!=null && preDrawListener!=null && root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
            layoutListener=null;preDrawListener=null;
        }
        void removeButton(){
            removeObservers();
            if(nativeMic!=null)nativeMic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if(button!=null && button.getParent() instanceof ViewGroup)((ViewGroup)button.getParent()).removeView(button);
            button=null;nativeMic=null;
        }
        void close(){closed=true;cancel();removeButton();root=null;}
    }
    private static final class MicButton extends View {
        MicButton(android.content.Context context){
            super(context);setClickable(true);setFocusable(true);setContentDescription("Start OpenAI dictation");
        }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.Button.class.getName());
        }
    }
    private static final class VoiceOverlay extends FrameLayout {
        final Runnable cancel;
        VoiceOverlay(android.content.Context context,Runnable cancel){super(context);this.cancel=cancel;}
        @Override public boolean dispatchKeyEvent(KeyEvent event){
            if(event.getKeyCode()==KeyEvent.KEYCODE_BACK){
                if(event.getAction()==KeyEvent.ACTION_UP && !event.isCanceled())cancel.run();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }
    }
    private VoiceRuntime(){}
}
