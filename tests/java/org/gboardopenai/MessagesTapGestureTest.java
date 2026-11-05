package org.gboardopenai;

import org.junit.Test;
import static org.junit.Assert.*;

public class MessagesTapGestureTest {
    @Test public void acceptsOneTapWithMovementInsideRadialSlop(){
        MessagesTapGesture tap=new MessagesTapGesture(5);
        tap.begin(7,100,200,true);tap.move(1,7,103,204);
        assertTrue(tap.finish(1,7,103,204,true));
        assertFalse(tap.finish(1,7,103,204,true));
    }

    @Test public void rejectsDragEvenIfFingerReturnsToStart(){
        MessagesTapGesture tap=new MessagesTapGesture(5);
        tap.begin(1,0,0,true);tap.move(1,1,4,4);
        tap.move(1,1,0,0);
        assertFalse(tap.finish(1,1,0,0,true));
    }

    @Test public void rejectsTooDistantUpWithoutAMoveEvent(){
        MessagesTapGesture tap=new MessagesTapGesture(5);
        tap.begin(1,100,200,true);
        assertFalse(tap.finish(1,1,106,200,true));
        assertFalse(tap.finish(1,1,100,200,true));
    }

    @Test public void rejectsMultitouchAndPointerSubstitution(){
        MessagesTapGesture tap=new MessagesTapGesture(5);
        tap.begin(1,0,0,true);tap.move(2,1,0,0);
        assertFalse(tap.finish(1,1,0,0,true));
        tap.begin(1,0,0,true);tap.move(1,2,0,0);
        assertFalse(tap.finish(1,1,0,0,true));
        tap.begin(1,0,0,true);
        assertFalse(tap.finish(2,1,0,0,true));
        tap.begin(1,0,0,true);
        assertFalse(tap.finish(1,2,0,0,true));
    }

    @Test public void rejectsMissedTargetAndTargetChanges(){
        MessagesTapGesture tap=new MessagesTapGesture(5);
        tap.begin(1,0,0,false);
        assertFalse(tap.finish(1,1,0,0,true));
        tap.begin(1,0,0,true);
        assertFalse(tap.finish(1,1,0,0,false));
        assertFalse(tap.finish(1,1,0,0,true));
    }

    @Test public void cancelAndUnpairedUpNeverActivate(){
        MessagesTapGesture tap=new MessagesTapGesture(5);
        assertFalse(tap.finish(1,1,0,0,true));
        tap.begin(1,0,0,true);tap.cancel();
        assertFalse(tap.finish(1,1,0,0,true));
        tap.begin(2,10,10,true);
        assertTrue(tap.finish(1,2,10,10,true));
    }

    @Test public void invalidCoordinatesCancelAndCannotBeRecoveredWithinTheGesture(){
        MessagesTapGesture tap=new MessagesTapGesture(5);
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
            tap.begin(1,bad,0,true);
            assertFalse(tap.finish(1,1,0,0,true));
            tap.begin(1,0,0,true);tap.move(1,1,0,bad);
            assertFalse(tap.finish(1,1,0,0,true));
            tap.begin(1,0,0,true);
            assertFalse(tap.finish(1,1,bad,0,true));
        }
        tap.begin(-1,0,0,true);
        assertFalse(tap.finish(1,-1,0,0,true));
    }

    @Test public void validatesTouchSlopAndSupportsZeroTolerance(){
        for(float bad:new float[]{-1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
            try{new MessagesTapGesture(bad);fail("Invalid slop accepted");}catch(IllegalArgumentException expected){}
        }
        MessagesTapGesture tap=new MessagesTapGesture(0);
        tap.begin(1,0,0,true);assertTrue(tap.finish(1,1,0,0,true));
        tap.begin(1,0,0,true);assertFalse(tap.finish(1,1,.001f,0,true));
    }
}
