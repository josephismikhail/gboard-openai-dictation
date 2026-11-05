package org.gboardopenai;

import org.junit.Test;
import static org.junit.Assert.*;

public class MessagesTargetSnapshotTest {
    @Test public void reusedComposerCannotMoveToADifferentConversation(){
        MessagesTargetSnapshot snapshot=new MessagesTargetSnapshot("Conversation A","",-1,-1);
        assertTrue(snapshot.matches("Conversation A","",0,0));
        assertFalse(snapshot.matches("Conversation B","",0,0));
    }
    @Test public void changedDraftOrKnownSelectionInvalidatesHandoff(){
        MessagesTargetSnapshot snapshot=new MessagesTargetSnapshot("Conversation A","Draft",2,2);
        assertTrue(snapshot.matches("Conversation A","Draft",2,2));
        assertFalse(snapshot.matches("Conversation A","Other",2,2));
        assertFalse(snapshot.matches("Conversation A","Draft",5,5));
        assertFalse(snapshot.matches("Conversation A","Draft",-1,-1));
    }
    @Test public void missingConversationIdentityIsRejected(){
        for(String header:new String[]{null,""}){
            try{new MessagesTargetSnapshot(header,"",0,0);fail("Missing identity accepted");}
            catch(IllegalArgumentException expected){}
        }
    }
}
