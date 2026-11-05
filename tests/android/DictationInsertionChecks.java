package org.gboardopenai.test;

import android.content.Context;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import org.gboardopenai.DictationInsertion;

/** Exercise real Android EditableInputConnection selection and cursor semantics without API calls. */
public final class DictationInsertionChecks {
    public static void run(Context context) {
        EditText field=new EditText(new ContextThemeWrapper(context,android.R.style.Theme_Material_Light));
        field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditorInfo info=new EditorInfo();InputConnection editor=field.onCreateInputConnection(info);
        if(editor==null)throw new AssertionError("No editable connection");

        field.setText("");field.setSelection(0);
        insert(editor,info,"Hello.");insert(editor,info,"Another sentence.");
        expect(field,"Hello. Another sentence. ");
        editor.commitText("Typed next",1);
        expect(field,"Hello. Another sentence. Typed next");

        field.setText("Hello friend");field.setSelection(5);
        insert(editor,info,"dear");expect(field,"Hello dear friend");
        if(field.getSelectionStart()!=11)throw new AssertionError("Caret did not reuse the existing space");

        field.setText("red button");field.setSelection(0,3);
        insert(editor,info,"green");expect(field,"green button");
        if(field.getSelectionStart()!=6)throw new AssertionError("Replacement caret misplaced");

        field.setText("Hello!");field.setSelection(5);
        insert(editor,info,"friend");expect(field,"Hello friend!");

        field.setText("John's book");field.setSelection(0,4);
        insert(editor,info,"Jane");expect(field,"Jane's book");

        field.setText("");field.setSelection(0);
        insert(editor,info,"(");insert(editor,info,"hello");expect(field,"(hello ");

        for(int type:new int[]{InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_NUMBER}){
            info.inputType=type;field.setText("");field.setSelection(0);
            insert(editor,info,"123");expect(field,"123");
        }
    }
    private static void insert(InputConnection editor,EditorInfo info,String text){
        if(!DictationInsertion.commit(editor,text,info))throw new AssertionError("Insertion failed");
    }
    private static void expect(EditText field,String expected){
        if(!expected.contentEquals(field.getText()))throw new AssertionError("Unexpected spacing in synthetic editor test");
    }
}
