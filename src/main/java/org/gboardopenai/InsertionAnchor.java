// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.view.inputmethod.InputConnection;

/** Local bounded insertion guard for editors that cannot provide a complete extract. */
public final class InsertionAnchor {
    private static final int CONTEXT_CHARS=256;
    private final String before,after,selected;

    private InsertionAnchor(String before,String after,String selected){
        this.before=before;this.after=after;this.selected=selected;
    }
    public static InsertionAnchor capture(InputConnection editor){
        if(editor==null)return null;
        try{
            CharSequence before=editor.getTextBeforeCursor(CONTEXT_CHARS,0);
            CharSequence after=editor.getTextAfterCursor(CONTEXT_CHARS,0);
            if(before==null || after==null)return null;
            CharSequence selected=editor.getSelectedText(0);
            if(selected!=null && selected.length()>4096)return null;
            // Defensively enforce the bound even if a custom editor ignores the request.
            String left=before.subSequence(Math.max(0,before.length()-CONTEXT_CHARS),before.length()).toString();
            String right=after.subSequence(0,Math.min(CONTEXT_CHARS,after.length())).toString();
            return new InsertionAnchor(left,right,selected==null?"":selected.toString());
        }catch(RuntimeException ignored){return null;}
    }
    public boolean matches(InputConnection editor){
        InsertionAnchor fresh=capture(editor);
        return fresh!=null && before.equals(fresh.before) && after.equals(fresh.after) && selected.equals(fresh.selected);
    }
}
