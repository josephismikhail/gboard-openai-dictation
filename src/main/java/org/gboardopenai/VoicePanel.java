// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.util.Arrays;

/** Translucent voice view with a live waveform and a centered breathing processing shape. */
public class VoicePanel extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final float[] levels = new float[49];
    private final int background, accent;
    private VoiceUiState.Phase phase = VoiceUiState.Phase.STARTING;
    private float displayed, target;
    private long lastFrame, lastSample, processingStarted;

    public VoicePanel(Context context, int background, int accent) {
        super(context);
        this.background = background; this.accent = accent;
        setClickable(true); setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setPhase(VoiceUiState.Phase.STARTING);
    }
    public void setPhase(VoiceUiState.Phase value) {
        if (value == VoiceUiState.Phase.PROCESSING && phase != value) processingStarted = frameTime();
        phase = value;
        boolean processing = value == VoiceUiState.Phase.PROCESSING;
        setContentDescription(processing ? "Processing dictation" : "Listening. Tap anywhere to finish dictation");
        if (android.os.Build.VERSION.SDK_INT >= 30) setStateDescription(processing ? "Processing" : "Recording");
        setKeepScreenOn(!processing);
        invalidate();
    }
    public void level(float value) {
        // Suppress the quiet floor and give ordinary speech a stronger visual response.
        // This gain is visual only: recorded audio and transcription are unchanged.
        target = (float)Math.pow(Math.max(0, Math.min(1, (value - .18f) / .45f)), .4);
        lastSample = frameTime();
        System.arraycopy(levels, 1, levels, 0, levels.length - 1);
        levels[levels.length - 1] = target;
        invalidate();
    }
    public void clear() {
        Arrays.fill(levels, 0); target = displayed = 0; setKeepScreenOn(false);
    }
    /** Overridable clock for deterministic animation regression checks. */
    protected long frameTime() { return SystemClock.uptimeMillis(); }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
        if (phase == VoiceUiState.Phase.PROCESSING) {
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            info.setClickable(false);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(background);
        float d = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        long now = frameTime();
        paint.setColor(accent); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND);
        boolean animate = android.animation.ValueAnimator.areAnimatorsEnabled();
        float width = Math.max(d,Math.min(346*d, getWidth() - 40*d));
        if (phase == VoiceUiState.Phase.PROCESSING) {
            float transition = animate ? Math.min(1, (now-processingStarted)/450f) : 1;
            transition = transition*transition*(3-2*transition);
            if (transition<1) {
                paint.setStrokeWidth((1.2f+2f*displayed)*d);
                paint.setAlpha((int)((190+65*displayed)*(1-transition)));
                for (int i=0; i<levels.length; i++) {
                    float contracted=width*(1-.8f*transition);
                    float x=cx-contracted/2+i*contracted/(levels.length-1);
                    float height=speechHeight(i,d)*(1-transition);
                    canvas.drawLine(x,cy-height/2,x,cy+height/2,paint);
                }
            }
            double seconds=animate?(now-processingStarted)/1000.0:0;
            float breath=(float)(.5+.5*Math.sin(seconds*3.2));
            float radius=Math.min((60+5*breath)*d,Math.min(getWidth(),getHeight())*.23f)*(.65f+.35f*transition);
            paint.setStyle(Paint.Style.FILL);
            // Indeterminate feedback: completion is driven only by the actual result callback.
            drawShape(canvas,cx,cy,radius*1.22f,3.5f,0,(int)(12*transition));
            drawShape(canvas,cx,cy,radius,3.5f+(float)Math.sin(seconds*1.4)*.6f,
                (float)Math.sin(seconds*.9)*.1f,(int)(220*transition));
        } else {
            float dt = lastFrame == 0 ? .016f : Math.min(.1f, (now - lastFrame) / 1000f);
            float desired = now - lastSample > 220 ? 0 : target;
            displayed += (desired - displayed) * (1 - (float)Math.exp(-dt * (desired > displayed ? 32 : 14)));
            if (displayed < .02f) {
                paint.setStrokeWidth(d); paint.setAlpha(128);
                canvas.drawLine(cx-width/2, cy, cx+width/2, cy, paint);
            } else {
                paint.setStrokeWidth((1.2f + 2f * displayed)*d);
                paint.setAlpha((int)(190 + 65 * displayed));
                for (int i = 0; i < levels.length; i++) {
                    float position = i / (float)(levels.length - 1);
                    float height = speechHeight(i,d);
                    float x = cx - width/2 + position * width;
                    canvas.drawLine(x, cy - height/2, x, cy + height/2, paint);
                }
            }
        }
        lastFrame = now;
        if (animate && isAttachedToWindow() && isShown()) postInvalidateOnAnimation();
    }
    private float speechHeight(int index, float density) {
        float position=index/(float)(levels.length-1);
        float taper=(float)Math.sin(position*Math.PI);
        // New speech appears immediately at the center; older samples flow outward.
        int age=Math.round(Math.abs(2*position-1)*(levels.length/2));
        float history=levels[levels.length-1-age];
        float energy=displayed*(.55f+.45f*history);
        float maximumHeight=Math.min(300*density,getHeight()*.36f);
        return density+maximumHeight*taper*energy;
    }
    private void drawShape(Canvas canvas,float cx,float cy,float radius,float exponent,float rotation,int alpha){
        shape.reset();
        float cosRotation=(float)Math.cos(rotation),sinRotation=(float)Math.sin(rotation);
        for(int i=0;i<=96;i++){
            double angle=i*Math.PI*2/96,cos=Math.cos(angle),sin=Math.sin(angle);
            float x=radius*(float)(Math.signum(cos)*Math.pow(Math.abs(cos),2/exponent));
            float y=radius*(float)(Math.signum(sin)*Math.pow(Math.abs(sin),2/exponent));
            float px=cx+x*cosRotation-y*sinRotation,py=cy+x*sinRotation+y*cosRotation;
            if(i==0)shape.moveTo(px,py);else shape.lineTo(px,py);
        }
        shape.close();paint.setAlpha(alpha);canvas.drawPath(shape,paint);
    }
}
