package org.gboardopenai.test;

import android.content.Context;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.*;
import android.widget.EditText;
import org.gboardopenai.DictationContext;
import org.gboardopenai.EditorContext;
import org.gboardopenai.VoiceEngine;
import org.json.JSONArray;
import org.json.JSONObject;

public final class EditorContextChecks {
    public static void run(Context context)throws Exception{
        EditText field=new EditText(new ContextThemeWrapper(context,android.R.style.Theme_Material_Light));
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setText("Earlier paragraph.\n\nChoose red today.\nLast paragraph.");
        int start=field.getText().toString().indexOf("red");field.setSelection(start,start+3);
        EditorInfo info=new EditorInfo();InputConnection editor=field.onCreateInputConnection(info);
        DictationContext snapshot=EditorContext.capture(editor,info);
        if(snapshot==null || !snapshot.message.contentEquals(field.getText()) || snapshot.start!=start || snapshot.end!=start+3)
            throw new AssertionError("Complete message or selection was lost");
        InputConnection partial=new InputConnectionWrapper(editor,false){
            @Override public ExtractedText getExtractedText(ExtractedTextRequest request,int flags){
                ExtractedText result=super.getExtractedText(request,flags);result.startOffset=12;return result;
            }
        };
        if(EditorContext.capture(partial,info)!=null)throw new AssertionError("Partial extract was treated as complete");
        InputConnection prefixOnly=new InputConnectionWrapper(editor,false){
            @Override public ExtractedText getExtractedText(ExtractedTextRequest request,int flags){
                ExtractedText result=super.getExtractedText(request,flags);
                result.text=result.text.subSequence(0,result.text.length()-8);return result;
            }
        };
        if(EditorContext.capture(prefixOnly,info)!=null)throw new AssertionError("Prefix-only extract was treated as complete");
        field.getText().append(" Changed omitted tail.");
        if(EditorContext.capture(prefixOnly,info)!=null)throw new AssertionError("Changed omitted tail was accepted");
        InputConnection noBoundary=new InputConnectionWrapper(editor,false){
            @Override public CharSequence getTextAfterCursor(int n,int flags){return null;}
        };
        if(EditorContext.capture(noBoundary,info)!=null)throw new AssertionError("Unverifiable boundary was accepted");
        char[] overLimit=new char[DictationContext.MAX_CHARS+1];java.util.Arrays.fill(overLimit,'x');
        field.setText(new String(overLimit));field.setSelection(0);
        InputConnection capped=new InputConnectionWrapper(editor,false){
            @Override public ExtractedText getExtractedText(ExtractedTextRequest request,int flags){
                if(request.hintMaxChars<=DictationContext.MAX_CHARS)throw new AssertionError("Missing overflow probe");
                ExtractedText result=super.getExtractedText(request,flags);
                result.text=result.text.subSequence(0,DictationContext.MAX_CHARS);return result;
            }
        };
        if(EditorContext.capture(capped,info)!=null)throw new AssertionError("Capped over-limit extract was accepted");
        if(EditorContext.capture(editor,info)!=null)throw new AssertionError("Over-limit extract was accepted");
        JSONObject response=new JSONObject().put("status","completed").put("output",new JSONArray().put(
            new JSONObject().put("type","message").put("content",new JSONArray().put(
                new JSONObject().put("type","output_text").put("text"," \n- Bananas\n ")))));
        if(!"\n- Bananas\n".equals(VoiceEngine.outputText(response,true)))throw new AssertionError("Contextual line break was trimmed");
        if(!"- Bananas".equals(VoiceEngine.outputText(response)))throw new AssertionError("Standalone output behavior changed");
        InputConnection mustNotRead=new InputConnectionWrapper(editor,false){
            @Override public ExtractedText getExtractedText(ExtractedTextRequest request,int flags){throw new AssertionError("Sensitive field context was read");}
        };
        for(int variation:new int[]{InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD}){
            info.inputType=InputType.TYPE_CLASS_TEXT|variation;
            if(EditorContext.capture(mustNotRead,info)!=null)throw new AssertionError("Sensitive context returned");
        }
        if(EditorContext.capture(mustNotRead,null)!=null)throw new AssertionError("Unknown editor context returned");
    }
}
