package org.gboardopenai;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class MessagesControlPolicyTest {
    private static final String PACKAGE=MessagesControlPolicy.MESSAGES_PACKAGE;
    private static final String RECORDER=MessagesControlPolicy.RECORDER_VIEW_ID;
    private static final String LABEL="Show record a voice message screen";
    private static final String OPEN_LABEL="Hide record a voice message screen";

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
        assertTrue(recorder(OPEN_LABEL));
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
            assertFalse(send,recorder(OPEN_LABEL,send));
            assertFalse(send,recorder(send,OPEN_LABEL));
        }
    }

    @Test public void hiddenDisabledAndNonclickableRecordersStayUntouched(){
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,false,true,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,false,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,true,false,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,false,true,true,Collections.singletonList(OPEN_LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,false,true,Collections.singletonList(OPEN_LABEL)));
        assertFalse(MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,true,false,Collections.singletonList(OPEN_LABEL)));
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

    @Test public void openingAndClosingNativeRecorderPreservesTheToggle(){
        assertTrue(recorder(LABEL));
        assertTrue(recorder(OPEN_LABEL));
        assertTrue(recorder(LABEL));
        assertFalse(recorder("Send RCS message"));
    }

    @Test public void recorderOpenRequiresTheExactObservedStateAndRejectsSend(){
        assertTrue(MessagesControlPolicy.isRecorderOpen(Arrays.asList(null,"",OPEN_LABEL)));
        assertFalse(MessagesControlPolicy.isRecorderOpen(Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isRecorderOpen(Collections.singletonList("Hide recorder")));
        assertFalse(MessagesControlPolicy.isRecorderOpen(Collections.singletonList(OPEN_LABEL+" ")));
        assertFalse(MessagesControlPolicy.isRecorderOpen(Arrays.asList(OPEN_LABEL,"Send RCS message")));
        assertFalse(MessagesControlPolicy.isRecorderOpen(Arrays.asList("Send",OPEN_LABEL)));
        assertFalse(MessagesControlPolicy.isRecorderOpen(null));
    }

    @Test public void visibleSendSemanticsIdentifyDraftModeEvenWhenRecorderLabelAlsoExists(){
        for(String send:Arrays.asList("Send","Send SMS","Send RCS message","Schedule send","Sending message","Message sent","SEND")){
            assertTrue(MessagesControlPolicy.isSend(PACKAGE,RECORDER,true,Arrays.asList(null,LABEL,send)));
        }
    }

    @Test public void unknownHiddenAndOtherAppControlsCannotIdentifySend(){
        assertFalse(MessagesControlPolicy.isSend(PACKAGE,RECORDER,true,Collections.singletonList(LABEL)));
        assertFalse(MessagesControlPolicy.isSend(PACKAGE,RECORDER,true,Collections.singletonList("sender")));
        assertFalse(MessagesControlPolicy.isSend(PACKAGE,RECORDER,false,Collections.singletonList("Send")));
        assertFalse(MessagesControlPolicy.isSend("other.app",RECORDER,true,Collections.singletonList("Send")));
        assertFalse(MessagesControlPolicy.isSend(PACKAGE,"other_control",true,Collections.singletonList("Send")));
        assertFalse(MessagesControlPolicy.isSend(PACKAGE,RECORDER,true,null));
    }

    private static boolean recorder(String... labels){
        return MessagesControlPolicy.isRecorder(PACKAGE,RECORDER,true,true,true,Arrays.asList(labels));
    }
}
