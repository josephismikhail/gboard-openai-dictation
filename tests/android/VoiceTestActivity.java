package org.gboardopenai.test;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;

/** Blank local test field. No credentials, saved text, networking, or automatic recording. */
public final class VoiceTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        EditText field = new EditText(this);
        field.setHint("Tap here to test dictation");
        field.setGravity(android.view.Gravity.TOP);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int p = (int)(24 * getResources().getDisplayMetrics().density);
        field.setPadding(p,5*p,p,p);
        setContentView(field);
        field.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
}
