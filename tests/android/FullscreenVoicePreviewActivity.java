package org.gboardopenai.test;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Interactive visual study only. No microphone, network, stored text, or keyboard hooks. */
public final class FullscreenVoicePreviewActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable complete=()->showResult();
    private FrameLayout root,overlay;
    private LinearLayout home;
    private TextView result;
    private VoiceCanvas voice;
    private float density;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(getActionBar()!=null)getActionBar().hide();
        density=getResources().getDisplayMetrics().density;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        root=new FrameLayout(this);root.setBackgroundColor(0xff1f1f1f);setContentView(root);
        home=new LinearLayout(this);home.setOrientation(LinearLayout.VERTICAL);home.setPadding(dp(28),dp(80),dp(28),dp(44));
        root.addView(home,new FrameLayout.LayoutParams(-1,-1));
        home.addView(label("Full-screen voice",28,0xffe3e3e3));
        TextView note=label("Visual preview only. The waveform is simulated; no audio is recorded.",15,0xffaeb3ba);
        note.setPadding(0,dp(16),0,0);home.addView(note);
        home.addView(new View(this),new LinearLayout.LayoutParams(1,0,1));
        result=label("Your message appears here after processing.",18,0xffdfe3e8);
        result.setPadding(dp(20),dp(22),dp(20),dp(22));result.setBackground(shape(0xff30343a,24));home.addView(result);
        Button replay=new Button(this);replay.setAllCaps(false);replay.setText("Preview microphone");
        replay.setTextColor(0xff15233a);replay.setBackground(shape(0xffc2d7fa,28));
        LinearLayout.LayoutParams buttonLayout=new LinearLayout.LayoutParams(-1,dp(56));buttonLayout.topMargin=dp(24);
        home.addView(replay,buttonLayout);replay.setOnClickListener(v->showVoice());
        showVoice();
    }
    private int dp(float value){return Math.round(value*density);}
    private TextView label(String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private void showVoice(){
        main.removeCallbacks(complete);
        if(overlay!=null)root.removeView(overlay);
        home.setVisibility(View.GONE);
        overlay=new FrameLayout(this);voice=new VoiceCanvas();overlay.addView(voice,new FrameLayout.LayoutParams(-1,-1));
        Button close=new Button(this);close.setText("×");close.setTextSize(24);close.setTextColor(0xffdadce0);close.setContentDescription("Close visual preview");
        close.setPadding(0,0,0,0);close.setMinWidth(0);close.setMinimumWidth(0);close.setBackground(shape(0xff303237,24));
        FrameLayout.LayoutParams closeLayout=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT);
        closeLayout.topMargin=dp(24);closeLayout.rightMargin=dp(24);overlay.addView(close,closeLayout);
        close.setOnClickListener(v->showResult());root.addView(overlay,new FrameLayout.LayoutParams(-1,-1));
        if(android.os.Build.VERSION.SDK_INT>=30){
            WindowInsetsController controller=getWindow().getInsetsController();
            if(controller!=null){controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);controller.hide(WindowInsets.Type.statusBars());}
        }
    }
    private void showResult(){
        main.removeCallbacks(complete);
        if(overlay!=null){root.removeView(overlay);overlay=null;}
        home.setVisibility(View.VISIBLE);
        result.setText("Example result\n\nMake the button green. Leave everything else alone.");
        if(android.os.Build.VERSION.SDK_INT>=30 && getWindow().getInsetsController()!=null)getWindow().getInsetsController().show(WindowInsets.Type.statusBars());
    }
    @Override protected void onPause(){super.onPause();main.removeCallbacks(complete);}
    @Override protected void onResume(){super.onResume();if(voice!=null && overlay!=null && voice.processing)main.postDelayed(complete,3000);}
    @Override protected void onDestroy(){main.removeCallbacks(complete);super.onDestroy();}

    private final class VoiceCanvas extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path=new Path();
        final long started=SystemClock.uptimeMillis();
        long processingAt;boolean processing;
        VoiceCanvas(){
            super(FullscreenVoicePreviewActivity.this);setClickable(true);setFocusable(true);setKeepScreenOn(true);
            setContentDescription("Full-screen visual preview. Tap anywhere to preview processing. No audio is recorded.");
            setOnClickListener(v->{if(!processing){processing=true;processingAt=SystemClock.uptimeMillis();setContentDescription("Processing animation preview");main.postDelayed(complete,3400);invalidate();}});
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);canvas.drawColor(0xff1f1f1f);
            float d=density,cx=getWidth()/2f,cy=getHeight()/2f;
            long now=SystemClock.uptimeMillis();boolean animate=android.animation.ValueAnimator.areAnimatorsEnabled();
            double time=animate?(now-started)/1000.0:.6;
            float morph=processing?(animate?Math.min(1,(now-processingAt)/450f):1):0;
            morph=morph*morph*(3-2*morph);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
            paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(11*d);paint.setColor(0xffa1a6ad);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);
            canvas.drawText("VISUAL PREVIEW · NO RECORDING",cx,112*d,paint);
            if(morph<1){
                float width=Math.min(346*d,getWidth()-40*d)*(1-.8f*morph);
                float height=Math.min(300*d,getHeight()*.36f);
                double speechTime=processing?(processingAt-started)/1000.0:time;
                float voice=(speechTime%7>5)?0:(float)(.55+.45*Math.abs(Math.sin(speechTime*1.9)));
                paint.setColor(0xffd8dce2);paint.setAlpha((int)(240*(1-morph)));paint.setStrokeWidth(2.6f*d);paint.setStrokeCap(Paint.Cap.ROUND);
                for(int i=0;i<49;i++){
                    float p=i/48f,taper=(float)Math.sin(p*Math.PI);
                    float detail=(float)(.25+.75*Math.abs(Math.sin(speechTime*4-i*.41)*Math.cos(speechTime*2-i*.23)));
                    float h=(d+height*taper*voice*detail)*(1-morph),x=cx-width/2+p*width;
                    canvas.drawLine(x,cy-h/2,x,cy+h/2,paint);
                }
            }
            if(processing){
                double t=animate?(now-processingAt)/1000.0:0;
                float breath=(float)(.5+.5*Math.sin(t*3.2));
                float radius=(60+5*breath)*d*(.65f+.35f*morph);
                // A large, gently breathing Material-like shape gives the full screen a clear center.
                paint.setStyle(Paint.Style.FILL);paint.setColor(0xffd8dce2);
                drawShape(canvas,cx,cy,radius*1.22f,3.5f,0,(int)(12*morph));
                drawShape(canvas,cx,cy,radius,3.5f+(float)Math.sin(t*1.4)*.6f,(float)Math.sin(t*.9)*.1f,(int)(220*morph));
            }
            paint.setStyle(Paint.Style.FILL);paint.setColor(0xffaeb3ba);paint.setAlpha(255);paint.setTextSize(14*d);
            canvas.drawText(processing?"Processing preview":"Tap anywhere to finish",cx,getHeight()-72*d,paint);
            if(animate && isAttachedToWindow() && isShown())postInvalidateOnAnimation();
        }
        private void drawShape(Canvas canvas,float cx,float cy,float radius,float exponent,float rotation,int alpha){
            path.reset();
            for(int i=0;i<=96;i++){
                double a=i*Math.PI*2/96,cos=Math.cos(a),sin=Math.sin(a);
                float x=radius*(float)(Math.signum(cos)*Math.pow(Math.abs(cos),2/exponent));
                float y=radius*(float)(Math.signum(sin)*Math.pow(Math.abs(sin),2/exponent));
                float px=cx+x*(float)Math.cos(rotation)-y*(float)Math.sin(rotation),py=cy+x*(float)Math.sin(rotation)+y*(float)Math.cos(rotation);
                if(i==0)path.moveTo(px,py);else path.lineTo(px,py);
            }
            path.close();paint.setAlpha(alpha);canvas.drawPath(path,paint);
        }
    }
}
