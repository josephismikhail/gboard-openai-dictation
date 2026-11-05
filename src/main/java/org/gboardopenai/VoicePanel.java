// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import java.util.Arrays;

/** Full-screen translucent recording scrim driven by actual microphone levels. */
public class VoicePanel extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] levels = new float[49];
    private final int background, accent;
    private VoiceUiState.Phase phase = VoiceUiState.Phase.STARTING;
    private float displayed, target;
    private long lastFrame, lastSample;

    public VoicePanel(Context context, int background, int accent) {
        super(context);
        this.background = background; this.accent = accent;
        setClickable(true); setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setPhase(VoiceUiState.Phase.STARTING);
    }
    public void setPhase(VoiceUiState.Phase value) {
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
        if (phase == VoiceUiState.Phase.PROCESSING) return;
        float dt = lastFrame == 0 ? .016f : Math.min(.1f, (now - lastFrame) / 1000f);
        float desired = now - lastSample > 220 ? 0 : target;
        displayed += (desired - displayed) * (1 - (float)Math.exp(-dt * (desired > displayed ? 32 : 14)));
        if (displayed < .02f) {
            paint.setStrokeWidth(d); paint.setAlpha(65);
            canvas.drawLine(cx-width/2, cy, cx+width/2, cy, paint);
        } else {
            paint.setStrokeWidth((1.2f + 2f * displayed)*d);
            paint.setAlpha((int)(65 + 190 * displayed));
            for (int i = 0; i < levels.length; i++) {
                float position = i / (float)(levels.length - 1);
                float height = speechHeight(i,d);
                float x = cx - width/2 + position * width;
                canvas.drawLine(x, cy - height/2, x, cy + height/2, paint);
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
}
