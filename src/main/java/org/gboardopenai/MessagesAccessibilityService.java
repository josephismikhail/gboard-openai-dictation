// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
    private AccessibilityNodeInfo movingComposer,movingRecorder;
    private long trackUntil,lastRefresh;
    private final Runnable track=this::trackPosition;

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
    private void clear(){main.removeCallbacksAndMessages(null);releaseTracking();removeShortcut();handingOff=false;recorderAvailable=false;VoiceRuntime.cancelMessagesHandoff();}
    private void schedule(long delay){main.removeCallbacks(update);main.postDelayed(update,delay);}
    public static void refreshActive(){if(instance!=null)instance.schedule(40);}
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

    private void refresh(){
        main.removeCallbacks(update);
        lastRefresh=android.os.SystemClock.uptimeMillis();
        try(Target target=findTarget()){
            recorderAvailable=target!=null;
            if(target==null){loseTarget();return;}
            if(movingComposer!=null)movingComposer.recycle();
            if(movingRecorder!=null)movingRecorder.recycle();
            movingComposer=AccessibilityNodeInfo.obtain(target.composer);
            movingRecorder=AccessibilityNodeInfo.obtain(target.recorder);
            if(android.os.SystemClock.uptimeMillis()<trackUntil){main.removeCallbacks(track);main.postDelayed(track,24);}
            if(handingOff || VoiceRuntime.isVoiceUiVisible())removeShortcut();
            else showShortcut(target.bounds);
            // Package-filtered accessibility events cannot announce another app opening.
            // Poll only while a Messages recorder exists, and revalidate again on every tap.
            schedule(500);
        }catch(RuntimeException ignored){loseTarget();}
    }
    private void loseTarget(){
        recorderAvailable=false;handingOff=false;VoiceRuntime.cancelMessagesHandoff();
        releaseTracking();removeShortcut();
        // Layout replacement may briefly remove the composer during an IME transition.
        if(android.os.SystemClock.uptimeMillis()<trackUntil)schedule(40);
    }
    private void startDictation(){
        Target target=null;
        try{target=findTarget();}catch(RuntimeException ignored){}
        startDictation(target);
    }
    /** Takes ownership of the target captured at the start of a validated tap. */
    private void startDictation(Target target){
        if(target==null){refresh();return;}
        if(handingOff || VoiceRuntime.isVoiceUiVisible()){target.close();return;}
        try{
            if(!target.isCurrent()){target.close();refresh();return;}
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
        handingOff=false;recorderAvailable=false;removeShortcut();schedule(80);
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
            shortcut=new ShortcutView();
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
        AccessibilityNodeInfo composer=null,recorder=null,header=null;boolean ambiguous=false;
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
                if(MessagesControlPolicy.isRecorder(pkg,id,node.isClickable(),node.isEnabled(),node.isVisibleToUser(),labels)){
                    if(recorder!=null)ambiguous=true;else recorder=node;
                }
            }
        }
        Target result=null;
        if(!ambiguous && composer!=null && recorder!=null && header!=null){
            Rect bounds=new Rect();recorder.getBoundsInScreen(bounds);
            String identity=headerIdentity(header);
            if(!bounds.isEmpty() && !identity.isEmpty()){
                result=new Target(AccessibilityNodeInfo.obtain(composer),AccessibilityNodeInfo.obtain(recorder),AccessibilityNodeInfo.obtain(header),identity,bounds);
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
        final MessagesTapGesture tap=new MessagesTapGesture(ViewConfiguration.get(MessagesAccessibilityService.this).getScaledTouchSlop());
        Target touchTarget,pendingClick;
        ShortcutView(){
            super(MessagesAccessibilityService.this);setClickable(true);setFocusable(true);
            setContentDescription("Start OpenAI dictation");
            // No drawable, background, tint or ripple. Messages alone renders and
            // animates its native button; this window only changes its tap action.
            setWillNotDraw(true);
            setDefaultFocusHighlightEnabled(false);
        }
        private boolean alignedHit(Target target,float x,float y){
            if(target==null || !Float.isFinite(x) || !Float.isFinite(y))return false;
            int[] at=new int[2];getLocationOnScreen(at);
            Rect actual=new Rect(at[0],at[1],at[0]+getWidth(),at[1]+getHeight());
            return actual.equals(target.bounds) && target.bounds.contains((int)x,(int)y);
        }
        private void cancelTouch(){
            tap.cancel();if(touchTarget!=null){touchTarget.close();touchTarget=null;}
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            final float x=event.getRawX(),y=event.getRawY();
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    cancelTouch();
                    try{touchTarget=findTarget();}catch(RuntimeException ignored){}
                    boolean hit=event.getPointerCount()==1 && alignedHit(touchTarget,x,y);
                    tap.begin(event.getPointerId(0),x,y,hit);
                    if(!hit){cancelTouch();schedule(0);}
                    break;
                case MotionEvent.ACTION_MOVE:
                    tap.move(event.getPointerCount(),event.getPointerId(0),x,y);break;
                case MotionEvent.ACTION_UP:
                    Target pressed=touchTarget;touchTarget=null;
                    boolean current=false;
                    try(Target now=findTarget()){
                        current=pressed!=null && pressed.isCurrent() && alignedHit(now,x,y);
                    }catch(RuntimeException ignored){}
                    if(tap.finish(event.getPointerCount(),event.getPointerId(0),x,y,current)){
                        pendingClick=pressed;performClick();
                    }else{if(pressed!=null)pressed.close();schedule(0);}
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_CANCEL:
                    cancelTouch();break;
            }
            // A rejected touch is consumed, never replayed over a possible Send button.
            return true;
        }
        @Override public boolean performClick(){
            super.performClick();Target target=pendingClick;pendingClick=null;
            if(target==null)startDictation();else startDictation(target);
            return true;
        }
        @Override protected void onDetachedFromWindow(){
            cancelTouch();if(pendingClick!=null){pendingClick.close();pendingClick=null;}
            super.onDetachedFromWindow();
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);info.setClassName(android.widget.Button.class.getName());
        }
    }
}
