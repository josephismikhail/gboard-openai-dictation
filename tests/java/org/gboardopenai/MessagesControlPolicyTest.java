package org.gboardopenai;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class MessagesControlPolicyTest {
    private static final String PACKAGE=MessagesControlPolicy.MESSAGES_PACKAGE;
    private static final String RECORDER=MessagesControlPolicy.RECORDER_VIEW_ID;
    private static final String LABEL="Show record a voice message screen";

    @Test public void identifiesOnlyTheSupportedEditableComposer(){
        assertTrue(MessagesControlPolicy.isComposer(PACKAGE,MessagesControlPolicy.COMPOSER_VIEW_ID,true,true,true));
        assertFalse(MessagesControlPolicy.isComposer("other.app",MessagesControlPolicy.COMPOSER_VIEW_ID,true,true,true));
        assertFalse(MessagesControlPolicy.isComposer(PACKAGE,"compose_message_text",true,true,true));
        assertFalse(MessagesControlPolicy.isComposer(PACKAGE,MessagesControlPolicy.COMPOSER_VIEW_ID,false,true,true));
        assertFalse(MessagesControlPolicy.isComposer(PACKAGE,MessagesControlPolicy.COMPOSER_VIEW_ID,true,false,true));
        assertFalse(MessagesControlPolicy.isComposer(PACKAGE,MessagesControlPolicy.COMPOSER_VIEW_ID,true,true,false));
    }

    @Test public void recognizesTheRecorderThroughItsDescendantLabel(){
        assertTrue(recorder("",LABEL,""));
        assertTrue(recorder(LABEL));
    }

    @Test public void sharedSendResourceAloneCannotIdentifyARecorder(){
        assertFalse(recorder());
        assertFalse(recorder(""));
        assertFalse(recorder("Microphone"));
        assertFalse(recorder("Record audio"));
        assertFalse(recorder("Mostrar pantalla para grabar un mensaje de voz"));
        assertFalse(recorder("Show record a voice message screen "));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,true,true,null));
    }

    @Test public void conflictingSendSemanticsAlwaysPreventTheSwap(){
        for(String send:Arrays.asList("Send","Send SMS","Send RCS message","Schedule send","Sending message","Message sent","SEND")){
            assertFalse(send,recorder(LABEL,send));
            assertFalse(send,recorder(send,LABEL));
        }
    }

    @Test public void hiddenDisabledAndNonclickableRecordersStayUntouched(){
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,false,true,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,false,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,true,false,Collections.singletonList(LABEL)));
    }

    @Test public void packageAndControlIdentityMustBothMatch(){
        assertFalse(MessagesControlPolicy.isRecorder("other.app",RECORDER,true,true,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,"other_control",true,true,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(null,RECORDER,true,true,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,null,true,true,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isComposer(null,null,true,true,true));
    }

    @Test public void changingFromRecordToSendDisablesTheShortcut(){
        assertTrue(recorder(LABEL));
        assertFalse(recorder("Send RCS message"));
        assertTrue(recorder(LABEL));
    }

    private static boolean recorder(String... labels){
        return MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,true,true,Arrays.asList(labels));
    }
}
