// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** Ephemeral identity data for the short handoff; never persisted or logged. */
public final class MessagesTargetSnapshot {
    private final String header,text;
    private final int start,end;
    public MessagesTargetSnapshot(String header,String text,int start,int end){
        if(header==null || header.isEmpty() || text==null)throw new IllegalArgumentException("Missing Messages identity");
        this.header=header;this.text=text;this.start=start;this.end=end;
    }
    public boolean matches(String header,String text,int start,int end){
        if(!this.header.equals(header) || !this.text.equals(text))return false;
        // A hidden, unfocused composer may not publish a selection until it receives focus.
        return (this.start<0 || this.end<0) || (this.start==start && this.end==end);
    }
}
