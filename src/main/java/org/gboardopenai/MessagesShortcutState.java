// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** Keeps the recorder icon through short transitions, then fails back to ordinary dictation. */
public final class MessagesShortcutState {
    public static final long TRANSIENT_GRACE_MS=1000;
    public enum Mode { RECORDER, DICTATION }
    private Mode mode=Mode.DICTATION;
    private long unknownSince=-1;

    public Mode mode(long nowMs){
        if(mode==Mode.RECORDER && unknownSince>=0
                && (nowMs<unknownSince || nowMs-unknownSince>=TRANSIENT_GRACE_MS))reset();
        return mode;
    }
    public void reset(){mode=Mode.DICTATION;unknownSince=-1;}

    public Mode update(boolean inMessages,boolean recorderValidated,boolean sendVisible,long nowMs){
        if(!inMessages || sendVisible)reset();
        else if(recorderValidated){mode=Mode.RECORDER;unknownSince=-1;}
        else if(mode==Mode.RECORDER && unknownSince<0)unknownSince=nowMs;
        return mode(nowMs);
    }
}
