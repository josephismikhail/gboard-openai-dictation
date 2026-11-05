// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** One temporary focus owner. Does not send media commands or change volume. */
public final class MediaFocusLease implements AutoCloseable {
    public interface Backend {
        boolean acquire();
        void release();
    }
    private final Backend backend;
    private boolean acquired,closed;

    public MediaFocusLease(Backend backend){
        this.backend=backend;
        try{acquired=backend.acquire();}catch(RuntimeException ignored){acquired=false;}
    }
    public boolean isAcquired(){return acquired && !closed;}
    @Override public void close(){
        if(closed)return;
        closed=true;
        if(acquired){acquired=false;try{backend.release();}catch(RuntimeException ignored){}}
    }
}
