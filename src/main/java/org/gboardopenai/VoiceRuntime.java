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

/** In-keyboard microphone: tap to start/finish, long-press to cancel, no routine toasts. */
public final class VoiceRuntime {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<InputMethodService, Session> SESSIONS = new WeakHashMap<>();
    public static void attach(InputMethodService ime) {
        MAIN.post(() -> {
            Session session=SESSIONS.get(ime);
            if(session==null) { session=new Session(ime); SESSIONS.put(ime, session); }
            session.scanLater();
        });
    }
    public static void detach(InputMethodService ime) {
        Session session=SESSIONS.remove(ime); if(session!=null) session.close();
    }
    public static JSONObject inspect(InputMethodService ime) throws Exception {
        JSONObject result=new JSONObject(); Session s=SESSIONS.get(ime);
        result.put("attached",s!=null).put("micFound",s!=null && s.button!=null);
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
        ViewGroup root; ViewTreeObserver.OnGlobalLayoutListener layoutListener;
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
                removeButton(); root=(ViewGroup)decor;
                layoutListener=() -> { long now=android.os.SystemClock.elapsedRealtime(); if(now-lastScan>200) {lastScan=now; MAIN.post(this::scan);} };
                root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
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
                button.setOnLongClickListener(v->{ if(engine!=null){cancel();}else{ime.startActivity(new Intent(ime,VoiceSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));} return true; });
                root.addView(button);
                android.util.Log.i("PixelVoice","Microphone attached inside keyboard");
            }
            if(nativeMic!=null && nativeMic!=target){nativeMic.setAlpha(1f);nativeMic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);}
            nativeMic=target;nativeMic.setAlpha(1f);nativeMic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(bounds.width(),bounds.height());
            p.leftMargin=bounds.left-root.getPaddingLeft();p.topMargin=bounds.top-root.getPaddingTop();p.gravity=Gravity.TOP|Gravity.LEFT;
            FrameLayout.LayoutParams old=(FrameLayout.LayoutParams)button.getLayoutParams();
            if(old==null || old.width!=p.width || old.height!=p.height || old.leftMargin!=p.leftMargin || old.topMargin!=p.topMargin) button.setLayoutParams(p);
            button.bringToFront(); button.setVisibility(View.VISIBLE);
        }
        View findMic(View v) {
            if(v==button || v.getVisibility()!=View.VISIBLE) return null;
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
            if(button.state==1 || button.state==3) { button.state(2); engine.stop(); return; }
            if(button.state!=0)return;
            editor=ime.getCurrentInputConnection(); if(editor==null)return;
            editor.finishComposingText(); int current=++generation;
            button.state(3);
            engine=new VoiceEngine(ime,new VoiceEngine.Listener(){
                public void recording(){ MAIN.post(()->{if(current==generation && button!=null)button.state(1);}); }
                public void result(String text){ MAIN.post(()->{
                    if(current!=generation || closed)return;
                    if(editor==ime.getCurrentInputConnection() && text!=null && !text.isEmpty()){
                        if(!DictationInsertion.commit(editor,text,ime.getCurrentInputEditorInfo()))Toast.makeText(ime,"Could not insert dictation into this text field.",Toast.LENGTH_LONG).show();
                    }
                    editor=null;engine=null;if(button!=null)button.state(0);
                }); }
                public void error(String message){ MAIN.post(()->{
                    if(current!=generation || closed)return;
                    Toast.makeText(ime,message,Toast.LENGTH_LONG).show();
                    if(!message.startsWith("Cleanup ")){cancel();}
                }); }
            });
            engine.start();
        }
        void cancel(){ generation++;if(engine!=null)engine.cancel();engine=null;editor=null;if(button!=null)button.state(0); }
        void removeButton(){
            if(nativeMic!=null){nativeMic.setAlpha(1f);nativeMic.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);}
            if(root!=null && layoutListener!=null && root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            if(button!=null && button.getParent() instanceof ViewGroup)((ViewGroup)button.getParent()).removeView(button);
            button=null;nativeMic=null;layoutListener=null;
        }
        void close(){closed=true;cancel();removeButton();root=null;}
    }
    private static final class MicButton extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); int state;
        MicButton(android.content.Context context){ super(context);setClickable(true);setFocusable(true);state(0); }
        void state(int value){state=value;setContentDescription(value==1?"Stop OpenAI dictation":value==2?"Cleaning dictation":value==3?"Starting microphone":"Start OpenAI dictation");invalidate();}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            if(state==0)return;
            float d=getResources().getDisplayMetrics().density,cx=getWidth()/2f,cy=getHeight()/2f;
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*d);
            paint.setColor(state==1?Color.rgb(255,90,95):Color.rgb(255,193,7));
            float phase=(android.os.SystemClock.uptimeMillis()%1200)/1200f;
            paint.setAlpha(state==1?(int)(155+90*Math.sin(phase*Math.PI*2)):255);
            float radius=Math.min(getWidth(),getHeight())/2f-3*d;
            canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,state==1?0:phase*360,state==1?360:240,false,paint);
            postInvalidateOnAnimation();
        }
    }

    private VoiceRuntime(){}
}
