package org.gboardopenai;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

/** Exercises the real Android Window attribute restoration without displaying another app. */
public final class VoiceWindowChecks {
    public static void run(Context context){
        Window window=new Dialog(context).getWindow();
        window.getDecorView(); // The real IME already has its decorated window before voice entry.
        Drawable background=new ColorDrawable(Color.MAGENTA);
        window.setBackgroundDrawable(background);
        window.setFormat(PixelFormat.RGB_565);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams original=new WindowManager.LayoutParams();original.copyFrom(window.getAttributes());
        VoiceWindowState state=new VoiceWindowState(window);
        if((window.getAttributes().flags&WindowManager.LayoutParams.FLAG_FULLSCREEN)==0)throw new AssertionError("Full-screen flag missing");
        if((window.getAttributes().flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0
            || (window.getAttributes().flags&WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)==0)
            throw new AssertionError("Voice window must hide editor handles without becoming an input target");
        if(window.getAttributes().format!=PixelFormat.TRANSLUCENT
            || (window.getAttributes().flags&WindowManager.LayoutParams.FLAG_DIM_BEHIND)!=0)
            throw new AssertionError("Recording window must composite the scrim without double dimming");
        Drawable voiceBackground=window.getDecorView().getBackground();
        if(!(voiceBackground instanceof ColorDrawable) || Color.alpha(((ColorDrawable)voiceBackground).getColor())!=0)
            throw new AssertionError("Recording window background must be transparent");
        state.restore();state.restore();
        WindowManager.LayoutParams restored=window.getAttributes();
        if(restored.flags!=original.flags || restored.layoutInDisplayCutoutMode!=original.layoutInDisplayCutoutMode
            || restored.format!=original.format || window.getDecorView().getBackground()!=background)
            throw new AssertionError("Window attributes were not restored");
        if(Build.VERSION.SDK_INT>=30 && restored.getFitInsetsTypes()!=original.getFitInsetsTypes())throw new AssertionError("Window inset types were not restored");
    }
}
