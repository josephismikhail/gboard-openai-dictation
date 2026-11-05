// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

/** Cross-checks a whole-message extract when supported; rejects detected partial or inconsistent reads. */
public final class EditorContext {
    public static DictationContext capture(InputConnection editor,EditorInfo info){
        if(editor==null || info==null || !DictationInsertion.prose(info))return null;
        try{
            ExtractedTextRequest request=new ExtractedTextRequest();
            request.hintMaxChars=DictationContext.MAX_CHARS+1;request.hintMaxLines=10000;
            ExtractedText value=editor.getExtractedText(request,0);
            if(value==null || value.text==null || value.startOffset!=0 || value.partialStartOffset!=-1 || value.partialEndOffset!=-1)return null;
            DictationContext snapshot=new DictationContext(value.text.toString(),value.selectionStart,value.selectionEnd);
            // Partial-update offsets do not prove completeness. Independently read past
            // both boundaries, including an extra character to expose a capped extract.
            CharSequence before=editor.getTextBeforeCursor(DictationContext.MAX_CHARS+1,0);
            CharSequence after=editor.getTextAfterCursor(DictationContext.MAX_CHARS+1,0);
            CharSequence selected=editor.getSelectedText(0);
            if(before==null || after==null || !snapshot.message.substring(0,snapshot.start).contentEquals(before)
                || !snapshot.message.substring(snapshot.end).contentEquals(after)
                || !snapshot.message.substring(snapshot.start,snapshot.end).contentEquals(selected==null?"":selected))return null;
            return snapshot;
        }catch(RuntimeException ignored){return null;}
    }
    private EditorContext(){}
}
