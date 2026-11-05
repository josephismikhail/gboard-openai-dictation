package org.gboardopenai;

import org.junit.Test;
import static org.junit.Assert.*;

public class MessagesHandoffTest {
    private static final class Target implements MessagesHandoff.Target {
        boolean current=true,focused=true,throwsCheck;
        int expectedField;
        int releases;
        public boolean isCurrent(){if(throwsCheck)throw new IllegalStateException();return current;}
        public boolean isFocused(){return focused;}
        public boolean matchesEditor(int fieldId){return expectedField==0 || expectedField==fieldId;}
        public void release(){releases++;}
    }
    @Test public void waitsForReadyMessagesEditorThenConsumesOnlyOnce(){
        MessagesHandoff handoff=new MessagesHandoff();Target target=new Target();
        assertTrue(handoff.request(target,100));
        assertFalse(handoff.consume(null,false,110));
        assertFalse(handoff.consume(MessagesHandoff.PACKAGE,false,120));
        assertTrue(handoff.consume(MessagesHandoff.PACKAGE,true,130));
        assertFalse(handoff.consume(MessagesHandoff.PACKAGE,true,140));
        assertEquals(1,target.releases);
    }
    @Test public void changedComposerCancelsTheRequest(){
        MessagesHandoff handoff=new MessagesHandoff();Target target=new Target();handoff.request(target,0);
        target.current=false;
        assertFalse(handoff.consume(MessagesHandoff.PACKAGE,true,30));assertEquals(1,target.releases);
        target.current=true;assertFalse(handoff.consume(MessagesHandoff.PACKAGE,true,40));
    }
    @Test public void otherFieldInMessagesCannotConsumeUntilOriginalComposerHasInputFocus(){
        MessagesHandoff handoff=new MessagesHandoff();Target target=new Target();target.focused=false;
        assertTrue(handoff.request(target,0));
        assertFalse(handoff.consume(MessagesHandoff.PACKAGE,true,10));assertEquals(0,target.releases);
        target.focused=true;
        assertTrue(handoff.consume(MessagesHandoff.PACKAGE,true,20));assertEquals(1,target.releases);
    }
    @Test public void focusedComposerStillRequiresMatchingEditorFieldWhenAvailable(){
        MessagesHandoff handoff=new MessagesHandoff();Target target=new Target();target.expectedField=27;
        assertTrue(handoff.request(target,0));
        assertFalse(handoff.consume(MessagesHandoff.PACKAGE,42,true,10));
        assertTrue(handoff.consume(MessagesHandoff.PACKAGE,27,true,20));
    }
    @Test public void observedTargetLossCancellationCannotRestartWhenTargetReturns(){
        MessagesHandoff handoff=new MessagesHandoff();Target target=new Target();assertTrue(handoff.request(target,0));
        target.current=false;handoff.cancel();target.current=true;
        assertFalse(handoff.consume(MessagesHandoff.PACKAGE,true,20));assertEquals(1,target.releases);
    }
    @Test public void anotherAppCancelsInsteadOfDeferringRecording(){
        MessagesHandoff handoff=new MessagesHandoff();Target target=new Target();handoff.request(target,0);
        assertFalse(handoff.consume("other.editor",true,100));
        assertFalse(handoff.consume(MessagesHandoff.PACKAGE,true,200));assertEquals(1,target.releases);
    }
    @Test public void expiredOrReversedClockCannotStartRecording(){
        for(long now:new long[]{MessagesHandoff.TIMEOUT_MS+100,99}){
            MessagesHandoff handoff=new MessagesHandoff();Target target=new Target();handoff.request(target,100);
            assertFalse(handoff.consume(MessagesHandoff.PACKAGE,true,now));assertEquals(1,target.releases);
        }
    }
    @Test public void duplicateTapDoesNotReplacePendingTarget(){
        MessagesHandoff handoff=new MessagesHandoff();Target first=new Target(),second=new Target();
        assertTrue(handoff.request(first,0));assertFalse(handoff.request(second,20));assertEquals(1,second.releases);
        assertFalse(handoff.request(first,30));assertEquals(0,first.releases);
        assertTrue(handoff.consume(MessagesHandoff.PACKAGE,true,40));assertEquals(1,first.releases);
    }
    @Test public void cancellationReleasesTargetAndAllowsAnotherRequest(){
        MessagesHandoff handoff=new MessagesHandoff();Target target=new Target();handoff.request(target,0);
        handoff.cancel();handoff.cancel();assertEquals(1,target.releases);
        assertFalse(handoff.consume(MessagesHandoff.PACKAGE,true,20));assertTrue(handoff.request(new Target(),30));
    }
    @Test public void invalidOrThrowingTargetsFailClosed(){
        MessagesHandoff handoff=new MessagesHandoff();assertFalse(handoff.request(null,0));
        Target target=new Target();target.current=false;assertFalse(handoff.request(target,0));assertEquals(1,target.releases);
        target=new Target();target.throwsCheck=true;assertFalse(handoff.request(target,10));assertEquals(1,target.releases);
    }
    @Test public void timerDoesNotCancelANewerRequest(){
        MessagesHandoff handoff=new MessagesHandoff();Target first=new Target(),second=new Target();
        handoff.request(first,0);handoff.cancel();handoff.request(second,2000);
        handoff.expire(3000);assertTrue(handoff.consume(MessagesHandoff.PACKAGE,true,3100));
        assertEquals(1,first.releases);assertEquals(1,second.releases);
    }
}
