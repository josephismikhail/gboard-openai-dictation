package org.gboardopenai.test;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import org.gboardopenai.InsertionAnchor;

/** Bounded fallback target checks without a real editor, microphone, or API request. */
public final class InsertionAnchorChecks {
    private static final class Editor extends BaseInputConnection {
        String text="Before red after";int start=7,end=10;
        boolean nullBefore,nullAfter,throwBefore;
        int largestRequest;
        Editor(Context context){super(new View(context),true);}
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request,int flags){return null;}
        @Override public CharSequence getTextBeforeCursor(int length,int flags){
            largestRequest=Math.max(largestRequest,length);
            if(throwBefore)throw new UnsupportedOperationException();
            return nullBefore?null:text.substring(Math.max(0,start-length),start);
        }
        @Override public CharSequence getTextAfterCursor(int length,int flags){
            largestRequest=Math.max(largestRequest,length);
            return nullAfter?null:text.substring(end,Math.min(text.length(),end+length));
        }
        @Override public CharSequence getSelectedText(int flags){return start==end?null:text.substring(start,end);}
    }
    public static void run(Context context){
        Editor editor=new Editor(context);
        if(editor.getExtractedText(new ExtractedTextRequest(),0)!=null)throw new AssertionError("Test requires unsupported extraction");
        InsertionAnchor anchor=InsertionAnchor.capture(editor);
        if(anchor==null || !anchor.matches(editor))throw new AssertionError("Unsupported full extract must still allow a stable bounded anchor");
        editor.start=editor.end=editor.text.length();
        if(anchor.matches(editor))throw new AssertionError("Cursor movement was accepted");
        editor.start=7;editor.end=10;editor.text="Before tan after";
        if(anchor.matches(editor))throw new AssertionError("Selected text change was accepted");
        editor.text="Before red after";editor.end=editor.start;
        if(anchor.matches(editor))throw new AssertionError("Selection collapse was accepted");
        editor.end=10;editor.text="Before red AFTER";
        if(anchor.matches(editor))throw new AssertionError("Text change at the same insertion position was accepted");
        editor.text="BEFORE red after";
        if(anchor.matches(editor))throw new AssertionError("Leading context change was accepted");
        editor.text="Before red after";editor.nullBefore=true;
        if(InsertionAnchor.capture(editor)!=null || anchor.matches(editor))throw new AssertionError("Missing leading boundary must fail closed");
        editor.nullBefore=false;editor.nullAfter=true;
        if(InsertionAnchor.capture(editor)!=null || anchor.matches(editor))throw new AssertionError("Missing trailing boundary must fail closed");
        editor.nullAfter=false;editor.throwBefore=true;
        if(InsertionAnchor.capture(editor)!=null || anchor.matches(editor))throw new AssertionError("Unsupported boundary reads must fail closed");
        if(InsertionAnchor.capture(null)!=null || anchor.matches(null))throw new AssertionError("Missing editor must fail closed");
        editor.throwBefore=false;editor.text="";editor.start=editor.end=0;
        InsertionAnchor empty=InsertionAnchor.capture(editor);
        if(empty==null || !empty.matches(editor))throw new AssertionError("Empty boundaries are valid at an empty cursor");
        char[] text=new char[2000];java.util.Arrays.fill(text,'x');editor.text=new String(text);editor.start=editor.end=1000;
        InsertionAnchor bounded=InsertionAnchor.capture(editor);
        if(bounded==null || !bounded.matches(editor) || editor.largestRequest!=256)throw new AssertionError("Anchor reads must remain bounded");
        editor.text=new String(new char[5000]);editor.start=0;editor.end=5000;
        if(InsertionAnchor.capture(editor)!=null)throw new AssertionError("Large selections must not be retained by the fallback guard");
    }
    private InsertionAnchorChecks(){}
}
