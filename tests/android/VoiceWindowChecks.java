package org.gboardopenai;

import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;

/** Exercises the real Android Window attribute restoration without displaying another app. */
public final class VoiceWindowChecks {
    public static void run(Context context){
        Window window=new Dialog(context).getWindow();
        window.getDecorView(); // The real IME already has its decorated window before voice entry.
        WindowManager.LayoutParams original=new WindowManager.LayoutParams();original.copyFrom(window.getAttributes());
        VoiceWindowState state=new VoiceWindowState(window);
        if((window.getAttributes().flags&WindowManager.LayoutParams.FLAG_FULLSCREEN)==0)throw new AssertionError("Full-screen flag missing");
        state.restore();state.restore();
        WindowManager.LayoutParams restored=window.getAttributes();
        if(restored.flags!=original.flags || restored.layoutInDisplayCutoutMode!=original.layoutInDisplayCutoutMode)
            throw new AssertionError("Window attributes were not restored");
        if(Build.VERSION.SDK_INT>=30 && restored.getFitInsetsTypes()!=original.getFitInsetsTypes())throw new AssertionError("Window inset types were not restored");
    }
}
