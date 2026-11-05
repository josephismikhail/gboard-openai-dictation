// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** Local-only editor boundary handling. No surrounding text is logged or sent to a model. */
public final class DictationInsertion {
    public static boolean commit(InputConnection editor, String text, EditorInfo info) {
        if (text==null || text.isEmpty()) return false;
        try {
            editor.beginBatchEdit();
            try {
                DictationSpacing.Insertion insertion;
                if (prose(info)) {
                    CharSequence before=null,after=null;
                    try {
                        before=editor.getTextBeforeCursor(2,0);
                        after=editor.getTextAfterCursor(2,0);
                    } catch (RuntimeException ignored) { /* Unsupported context: preserve the text. */ }
                    insertion=DictationSpacing.prepare(text,before,after);
                } else insertion=new DictationSpacing.Insertion(text,1);
                return editor.commitText(insertion.text,insertion.cursorPosition);
            } finally { editor.endBatchEdit(); }
        } catch (RuntimeException ignored) { return false; }
    }
    private static boolean prose(EditorInfo info) {
        if (info==null) return true;
        int type=info.inputType;
        if ((type & InputType.TYPE_MASK_CLASS)!=InputType.TYPE_CLASS_TEXT) return false;
        switch(type & InputType.TYPE_MASK_VARIATION) {
            case InputType.TYPE_TEXT_VARIATION_URI:
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD: return false;
            default: return true;
        }
    }
    private DictationInsertion() {}
}
