// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;

/** Opt-in, local Messages shortcut. Never persists editor contents or invokes Send. */
public final class MessagesAccessibilityService extends AccessibilityService {
    private static MessagesAccessibilityService instance;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable update=this::refresh;
    private View shortcut;
    private WindowManager manager;
    private Rect placed=new Rect();
    private boolean handingOff,recorderAvailable;
    private int composerResourceId;
    private final MessagesShortcutState shortcutState=new MessagesShortcutState();
    private AccessibilityNodeInfo movingComposer,movingRecorder;
    private long trackUntil,lastRefresh;
    private final Runnable track=this::trackPosition;
    private final Runnable expireIcon=VoiceRuntime::refreshMessagesControls;

    @Override protected void onServiceConnected(){
        instance=this;manager=getSystemService(WindowManager.class);
        try{composerResourceId=getPackageManager().getResourcesForApplication(MessagesControlPolicy.MESSAGES_PACKAGE)
            .getIdentifier("compose_message_text","id",MessagesControlPolicy.MESSAGES_PACKAGE);}
        catch(android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored){composerResourceId=0;}
        refresh();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(handingOff){if(!main.hasCallbacks(update))schedule(40);return;}
        beginTracking();schedule(Math.max(0,lastRefresh+80-android.os.SystemClock.uptimeMillis()));
    }
    @Override public void onInterrupt(){clear();}
    @Override public void onDestroy(){clear();if(instance==this)instance=null;super.onDestroy();}
    private void clear(){main.removeCallbacksAndMessages(null);releaseTracking();removeShortcut();handingOff=false;recorderAvailable=false;shortcutState.reset();VoiceRuntime.cancelMessagesHandoff();VoiceRuntime.refreshMessagesControls();}
    private void schedule(long delay){main.removeCallbacks(update);main.postDelayed(update,delay);}
    public static void refreshActive(){if(instance!=null)instance.schedule(40);}
    /** Rendering survives transiently missing controls; clicks still require a fresh target. */
    public static boolean isRecorderMode(int fieldId){
        return Looper.myLooper()==Looper.getMainLooper() && instance!=null
            && (instance.composerResourceId>0 && fieldId>0
                ? instance.composerResourceId==fieldId : instance.recorderAvailable)
            && instance.shortcutState.mode(android.os.SystemClock.uptimeMillis())==MessagesShortcutState.Mode.RECORDER;
    }
    public static void keyboardTransition(){
        if(instance!=null){
            instance.beginTracking();
            // Focusing the composer can call this before its resized layout exists.
            // The queued handoff validates that layout when the IME is ready.
            if(instance.handingOff)instance.schedule(40);else instance.refresh();
        }
    }

    private void beginTracking(){
        trackUntil=android.os.SystemClock.uptimeMillis()+900;
        main.removeCallbacks(track);main.post(track);
    }
    private void releaseTracking(){
        main.removeCallbacks(track);
        if(movingComposer!=null)movingComposer.recycle();
        if(movingRecorder!=null)movingRecorder.recycle();
        movingComposer=null;movingRecorder=null;
    }
    private void trackPosition(){
        // The shortcut is hidden during this transition. Leave its deferred target
        // validation to refresh/the handoff consumer, not the geometry tracker.
        if(handingOff || VoiceRuntime.isVoiceUiVisible())return;
        if(android.os.SystemClock.uptimeMillis()>trackUntil)return;
        if(movingComposer!=null && movingRecorder!=null){
            try{
                AccessibilityNodeInfo root=messagesRoot();
                boolean sameWindow=root!=null && root.getWindowId()==movingRecorder.getWindowId();
                if(root!=null)root.recycle();
                boolean current=sameWindow && movingComposer.refresh() && movingRecorder.refresh()
                    && MessagesControlPolicy.isComposer(String.valueOf(movingComposer.getPackageName()),
                        movingComposer.getViewIdResourceName(),movingComposer.isEditable(),movingComposer.isEnabled(),movingComposer.isVisibleToUser());
                ArrayList<String> labels=new ArrayList<>();
                if(current)descriptions(movingRecorder,labels,0);
                current=current && MessagesControlPolicy.isRecorder(String.valueOf(movingRecorder.getPackageName()),
                    movingRecorder.getViewIdResourceName(),movingRecorder.isClickable(),movingRecorder.isEnabled(),movingRecorder.isVisibleToUser(),labels);
                if(!current){releaseTracking();removeShortcut();schedule(0);return;}
                Rect bounds=new Rect();movingRecorder.getBoundsInScreen(bounds);
                if(!handingOff && !VoiceRuntime.isVoiceUiVisible())showShortcut(bounds);
            }catch(RuntimeException ignored){releaseTracking();removeShortcut();schedule(0);return;}
        }
        // Only refresh two known nodes during movement, never walk the message tree per frame.
        main.postDelayed(track,24);
    }

    /** Called only by our in-process keyboard, on the main thread. */
    public static boolean canOpenRecorder(){
        return Looper.myLooper()==Looper.getMainLooper() && instance!=null && instance.recorderAvailable;
    }
    public static boolean openRecorder(){
        if(Looper.myLooper()!=Looper.getMainLooper() || instance==null)return false;
        try(Target target=instance.findTarget()){
            if(target==null){instance.recorderAvailable=false;instance.schedule(40);return false;}
            boolean opened=target.recorder.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            instance.beginTracking();instance.schedule(0);return opened;
        }catch(RuntimeException ignored){instance.recorderAvailable=false;instance.schedule(40);return false;}
    }
    private void refresh(){
        main.removeCallbacks(update);
        lastRefresh=android.os.SystemClock.uptimeMillis();
        try(Target target=findTarget()){
            recorderAvailable=target!=null;
            if(target==null){loseTarget();return;}
            main.removeCallbacks(expireIcon);
            if(movingComposer!=null)movingComposer.recycle();
            if(movingRecorder!=null)movingRecorder.recycle();
            movingComposer=AccessibilityNodeInfo.obtain(target.composer);
            movingRecorder=AccessibilityNodeInfo.obtain(target.recorder);
            if(android.os.SystemClock.uptimeMillis()<trackUntil){main.removeCallbacks(track);main.postDelayed(track,24);}
            if(handingOff || VoiceRuntime.isVoiceUiVisible())removeShortcut();
            else showShortcut(target.bounds);
            VoiceRuntime.refreshMessagesControls();
            // Package-filtered accessibility events cannot announce another app opening.
            // Poll only while a Messages recorder exists, and revalidate again on every tap.
            schedule(500);
        }catch(RuntimeException ignored){loseTarget();}
    }
    private void loseTarget(){
        recorderAvailable=false;handingOff=false;VoiceRuntime.cancelMessagesHandoff();
        shortcutState.update(true,false,false,android.os.SystemClock.uptimeMillis());
        if(!main.hasCallbacks(expireIcon))main.postDelayed(expireIcon,1000);
        releaseTracking();removeShortcut();VoiceRuntime.refreshMessagesControls();
        // Layout replacement may briefly remove the composer during an IME transition.
        if(android.os.SystemClock.uptimeMillis()<trackUntil)schedule(40);
    }
    private void startDictation(){
        if(handingOff || VoiceRuntime.isVoiceUiVisible())return;
        Target target=null;
        try{
            target=findTarget();if(target==null){refresh();return;}
            handingOff=true;removeShortcut();
            if(!VoiceRuntime.requestMessagesDictation(target)
                    || !target.composer.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                abortHandoff(target);return;
            }
            main.postDelayed(()->{handingOff=false;refresh();},3100);
            schedule(100);
        }catch(RuntimeException ignored){abortHandoff(target);}
    }
    private void abortHandoff(Target target){
        if(target!=null)target.invalid=true;
        VoiceRuntime.cancelMessagesHandoff();if(target!=null)target.close();
        handingOff=false;recorderAvailable=false;removeShortcut();VoiceRuntime.refreshMessagesControls();schedule(80);
    }
    private void showShortcut(Rect bounds){
        if(bounds.isEmpty())return;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(bounds.width(),bounds.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,android.graphics.PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.LEFT;p.x=bounds.left;p.y=bounds.top;
        p.windowAnimations=0;
        // Follow Messages' animation positions directly, without a second window animation.
        if(android.os.Build.VERSION.SDK_INT>=34)p.setCanPlayMoveAnimation(false);
        p.setTitle("Messages dictation shortcut");
        if(android.os.Build.VERSION.SDK_INT>=30)p.setFitInsetsTypes(0);
        if(shortcut==null){
            shortcut=new ShortcutView();shortcut.setOnClickListener(v->startDictation());
            manager.addView(shortcut,p);placed.set(bounds);
        }else if(!placed.equals(bounds)){manager.updateViewLayout(shortcut,p);placed.set(bounds);}
    }
    private void removeShortcut(){
        if(shortcut!=null){try{manager.removeView(shortcut);}catch(RuntimeException ignored){}shortcut=null;placed.setEmpty();}
    }
    private Target findTarget(){
        AccessibilityNodeInfo root=messagesRoot();if(root==null)return null;
        ArrayList<AccessibilityNodeInfo> nodes=new ArrayList<>();
        try{
        collect(root,nodes,0);
        if(nodes.size()>=1500)return null;
        AccessibilityNodeInfo composer=null,recorder=null,header=null;boolean ambiguous=false,sawSend=false;
        for(AccessibilityNodeInfo node:nodes){
            String pkg=String.valueOf(node.getPackageName()),id=node.getViewIdResourceName();
            if(MessagesControlPolicy.isComposer(pkg,id,node.isEditable(),node.isEnabled(),node.isVisibleToUser())){
                if(composer!=null)ambiguous=true;else composer=node;
            }
            if(MessagesControlPolicy.MESSAGES_PACKAGE.equals(pkg) && "top_app_bar_title_row".equals(id) && node.isVisibleToUser()){
                if(header!=null)ambiguous=true;else header=node;
            }
            if("Compose:Draft:Send".equals(id)){
                ArrayList<String> labels=new ArrayList<>();descriptions(node,labels,0);
                sawSend|=MessagesControlPolicy.isSend(pkg,id,node.isVisibleToUser(),labels);
                if(MessagesControlPolicy.isRecorder(pkg,id,node.isClickable(),node.isEnabled(),node.isVisibleToUser(),labels)){
                    if(recorder!=null)ambiguous=true;else recorder=node;
                }
            }
        }
        shortcutState.update(true,false,sawSend,android.os.SystemClock.uptimeMillis());
        Target result=null;
        if(!ambiguous && composer!=null && recorder!=null && header!=null){
            Rect bounds=new Rect();recorder.getBoundsInScreen(bounds);
            String identity=headerIdentity(header);
            if(!bounds.isEmpty() && !identity.isEmpty()){
                result=new Target(AccessibilityNodeInfo.obtain(composer),AccessibilityNodeInfo.obtain(recorder),AccessibilityNodeInfo.obtain(header),identity,bounds);
                shortcutState.update(true,!sawSend,sawSend,android.os.SystemClock.uptimeMillis());
            }
        }
        return result;
        }finally{for(AccessibilityNodeInfo node:nodes)node.recycle();}
    }
    private AccessibilityNodeInfo messagesRoot(){
        List<AccessibilityWindowInfo> windows=getWindows();
        if(windows==null)return null;
        // Windows are ordered from highest to lowest layer. Never search beneath another app.
        try{
            for(AccessibilityWindowInfo window:windows){
                if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                // A visible application underneath Recents or another system surface is not a target.
                // The normal nonfocusable IME leaves its application active/focused.
                if(!window.isActive() && !window.isFocused())return null;
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null)return null;
                if(MessagesControlPolicy.MESSAGES_PACKAGE.equals(String.valueOf(root.getPackageName())))return root;
                shortcutState.reset();
                root.recycle();return null;
            }
            return null;
        }finally{for(AccessibilityWindowInfo window:windows)window.recycle();}
    }
    private static void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> nodes,int depth){
        nodes.add(node);if(depth>=32 || nodes.size()>=1500)return;
        for(int i=0;i<node.getChildCount() && nodes.size()<1500;i++){
            AccessibilityNodeInfo child=node.getChild(i);if(child!=null)collect(child,nodes,depth+1);
        }
    }
    private static void descriptions(AccessibilityNodeInfo node,List<String> labels,int depth){
        if(node.getContentDescription()!=null)labels.add(node.getContentDescription().toString());
        if(depth>=8)return;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null){descriptions(child,labels,depth+1);child.recycle();}
        }
    }
    private static String composerText(AccessibilityNodeInfo node){
        return node.isShowingHintText() || node.getText()==null?"":node.getText().toString();
    }
    private static String headerIdentity(AccessibilityNodeInfo node){
        StringBuilder identity=new StringBuilder();headerIdentity(node,identity,0);return identity.toString();
    }
    private static void headerIdentity(AccessibilityNodeInfo node,StringBuilder identity,int depth){
        for(CharSequence value:new CharSequence[]{node.getText(),node.getContentDescription()}){
            if(value!=null && value.length()>0)identity.append(value.length()).append(':').append(value).append(';');
        }
        if(depth>=8)return;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try{headerIdentity(child,identity,depth+1);}finally{child.recycle();}
        }
    }
    private final class Target implements MessagesHandoff.Target,AutoCloseable {
        final AccessibilityNodeInfo composer,recorder,header;final Rect bounds;final MessagesTargetSnapshot snapshot;boolean closed,invalid;
        Target(AccessibilityNodeInfo composer,AccessibilityNodeInfo recorder,AccessibilityNodeInfo header,String identity,Rect bounds){
            this.composer=composer;this.recorder=recorder;this.header=header;this.bounds=bounds;
            snapshot=new MessagesTargetSnapshot(identity,composerText(composer),composer.getTextSelectionStart(),composer.getTextSelectionEnd());
        }
        @Override public boolean isCurrent(){
            if(closed || invalid || !composer.refresh() || !composer.isVisibleToUser())return false;
            try(Target current=findTarget()){
                return current!=null && composer.getWindowId()==current.composer.getWindowId()
                    && composer.equals(current.composer) && header.equals(current.header)
                    && snapshot.matches(headerIdentity(current.header),composerText(current.composer),current.composer.getTextSelectionStart(),current.composer.getTextSelectionEnd());
            }
        }
        @Override public boolean isFocused(){
            return !closed && !invalid && composer.refresh() && composer.isFocused();
        }
        @Override public boolean matchesEditor(int fieldId){
            return composerResourceId>0 && fieldId>0 ? composerResourceId==fieldId : isFocused();
        }
        @Override public void release(){close();}
        @Override public void close(){if(!closed){closed=true;composer.recycle();recorder.recycle();header.recycle();}}
    }
    private final class ShortcutView extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ShortcutView(){super(MessagesAccessibilityService.this);setClickable(true);setFocusable(true);setContentDescription("Start OpenAI dictation");}
        @Override protected void onDraw(Canvas canvas){
            float d=getResources().getDisplayMetrics().density,cx=getWidth()/2f,cy=getHeight()/2f;
            boolean dark=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
            paint.setStyle(Paint.Style.FILL);paint.setColor(dark?Color.rgb(168,199,250):Color.rgb(11,87,208));
            canvas.drawRoundRect(0,0,getWidth(),getHeight(),28*d,28*d,paint);
            paint.setColor(dark?Color.rgb(6,46,111):Color.WHITE);
            // Standard 24dp microphone proportions, optically centered in the native button.
            canvas.drawRoundRect(cx-3*d,cy-10*d,cx+3*d,cy+2*d,3*d,3*d,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*d);paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(cx-7*d,cy-7*d,cx+7*d,cy+7*d,0,180,false,paint);
            canvas.drawLine(cx,cy+7*d,cx,cy+10*d,paint);canvas.drawLine(cx-3*d,cy+10*d,cx+3*d,cy+10*d,paint);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.Button.class.getName());
        }
    }
}
