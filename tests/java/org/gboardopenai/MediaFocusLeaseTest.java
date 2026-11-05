package org.gboardopenai;

import org.junit.Test;
import static org.junit.Assert.*;

public class MediaFocusLeaseTest {
    private static final class Backend implements MediaFocusLease.Backend {
        boolean granted=true,throwAcquire,throwRelease;
        int acquisitions,releases;
        public boolean acquire(){acquisitions++;if(throwAcquire)throw new IllegalStateException();return granted;}
        public void release(){releases++;if(throwRelease)throw new IllegalStateException();}
    }
    @Test public void returnsOnlyItsOwnGrantedFocusOnce(){
        Backend backend=new Backend();MediaFocusLease lease=new MediaFocusLease(backend);
        assertTrue(lease.isAcquired());assertEquals(1,backend.acquisitions);assertEquals(0,backend.releases);
        lease.close();lease.close();assertFalse(lease.isAcquired());assertEquals(1,backend.releases);
    }
    @Test public void deniedRequestCannotAbandonAnotherPlayersFocus(){
        Backend backend=new Backend();backend.granted=false;
        MediaFocusLease lease=new MediaFocusLease(backend);assertFalse(lease.isAcquired());
        lease.close();assertEquals(0,backend.releases);
    }
    @Test public void unsupportedFocusDoesNotBreakDictation(){
        Backend backend=new Backend();backend.throwAcquire=true;
        MediaFocusLease lease=new MediaFocusLease(backend);assertFalse(lease.isAcquired());
        lease.close();assertEquals(0,backend.releases);
    }
    @Test public void cleanupFailureIsContainedAndNeverRetried(){
        Backend backend=new Backend();backend.throwRelease=true;
        MediaFocusLease lease=new MediaFocusLease(backend);lease.close();lease.close();
        assertFalse(lease.isAcquired());assertEquals(1,backend.releases);
    }
}
