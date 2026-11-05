package org.gboardopenai;

import org.junit.Test;
import static org.junit.Assert.*;
import static org.gboardopenai.MessagesShortcutState.Mode.*;

public class MessagesShortcutStateTest {
    @Test public void unsupportedContextsKeepOrdinaryDictationUntilARecorderIsValidated(){
        MessagesShortcutState state=new MessagesShortcutState();
        assertEquals(DICTATION,state.mode(0));
        assertEquals(DICTATION,state.update(true,false,false,0));
    }

    @Test public void openingAndClosingNativeRecorderDoesNotFlickerToDictation(){
        MessagesShortcutState state=new MessagesShortcutState();
        assertEquals(RECORDER,state.update(true,true,false,0));
        // The native voice-note surface temporarily replaces the composer controls.
        for(int i=0;i<5;i++)assertEquals(RECORDER,state.update(true,false,false,100+i*100));
        assertEquals(RECORDER,state.update(true,true,false,600));
    }

    @Test public void sendSwitchesToDictationAndUnknownScansKeepThatChoice(){
        MessagesShortcutState state=new MessagesShortcutState();
        assertEquals(DICTATION,state.update(true,false,true,0));
        for(int i=0;i<5;i++)assertEquals(DICTATION,state.update(true,false,false,100+i*100));
        assertEquals(RECORDER,state.update(true,true,false,600));
    }

    @Test public void leavingMessagesResetsTheNextEntryToOrdinaryDictation(){
        MessagesShortcutState state=new MessagesShortcutState();
        state.update(true,true,false,0);
        assertEquals(DICTATION,state.update(false,false,false,1));
        assertEquals(DICTATION,state.update(true,false,false,2));
        state.update(true,true,false,3);state.reset();
        assertEquals(DICTATION,state.mode(4));
    }

    @Test public void sendWinsAmbiguousScansAndOutsideControlsCannotSetMode(){
        MessagesShortcutState state=new MessagesShortcutState();
        assertEquals(DICTATION,state.update(true,true,true,0));
        assertEquals(DICTATION,state.update(false,false,true,1));
    }

    @Test public void unknownStateExpiresAtTheGraceBoundaryWithoutAnotherScan(){
        MessagesShortcutState state=new MessagesShortcutState();
        state.update(true,true,false,0);
        state.update(true,false,false,100);
        assertEquals(RECORDER,state.mode(1099));
        assertEquals(DICTATION,state.mode(1100));
        assertEquals(DICTATION,state.mode(1200));
    }

    @Test public void repeatedUnknownScansNeverExtendGrace(){
        MessagesShortcutState state=new MessagesShortcutState();
        state.update(true,true,false,0);
        state.update(true,false,false,100);
        assertEquals(RECORDER,state.update(true,false,false,600));
        assertEquals(RECORDER,state.update(true,false,false,1099));
        assertEquals(DICTATION,state.update(true,false,false,1100));
        assertEquals(DICTATION,state.update(true,false,false,1200));
    }

    @Test public void recoveredRecorderStartsANewGraceWindow(){
        MessagesShortcutState state=new MessagesShortcutState();
        state.update(true,true,false,0);
        state.update(true,false,false,100);
        assertEquals(RECORDER,state.update(true,true,false,900));
        assertEquals(RECORDER,state.mode(1200));
        state.update(true,false,false,1500);
        assertEquals(RECORDER,state.mode(2499));
        assertEquals(DICTATION,state.mode(2500));
        assertEquals(RECORDER,state.update(true,true,false,2600));
    }

    @Test public void confirmedSendAndLeaveImmediatelyEndGrace(){
        MessagesShortcutState state=new MessagesShortcutState();
        state.update(true,true,false,0);state.update(true,false,false,100);
        assertEquals(DICTATION,state.update(true,false,true,101));
        state.update(true,true,false,200);state.update(true,false,false,201);
        assertEquals(DICTATION,state.update(false,false,false,202));
    }

    @Test public void backwardsClockFailsClosed(){
        MessagesShortcutState state=new MessagesShortcutState();
        state.update(true,true,false,100);state.update(true,false,false,101);
        assertEquals(DICTATION,state.mode(99));
    }
}
