// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** Main-thread, one-use handoff from a user-tapped Messages shortcut to its ready IME. */
public final class MessagesHandoff {
    public static final String PACKAGE="com.google.android.apps.messaging";
    public static final long TIMEOUT_MS=3000;
    public interface Target {
        boolean isCurrent();
        /** Input focus, not accessibility focus; an unfocused composer may still request a handoff. */
        default boolean isFocused(){return false;}
        /** Compare a nonzero EditorInfo field ID where the host editor supplies one. */
        default boolean matchesEditor(int fieldId){return true;}
        default void release(){}
    }
    private Target target;
    private long requestedAt;
    public boolean request(Target candidate,long now){
        expire(now);
        if(candidate==null)return false;
        if(target!=null || !current(candidate)){if(candidate!=target)release(candidate);return false;}
        target=candidate;requestedAt=now;return true;
    }
    public boolean consume(String editorPackage,boolean ready,long now){
        return consume(editorPackage,0,ready,now);
    }
    public boolean consume(String editorPackage,int fieldId,boolean ready,long now){
        expire(now);
        if(target==null)return false;
        if(!current(target) || (editorPackage!=null && !PACKAGE.equals(editorPackage))){cancel();return false;}
        if(!ready || !PACKAGE.equals(editorPackage))return false;
        if(!focusedEditor(target,fieldId))return false;
        cancel();return true;
    }
    public void expire(long now){
        if(target!=null && (now<requestedAt || now-requestedAt>=TIMEOUT_MS))cancel();
    }
    public void cancel(){Target previous=target;target=null;if(previous!=null)release(previous);}
    private static boolean current(Target target){try{return target.isCurrent();}catch(RuntimeException ignored){return false;}}
    private static boolean focusedEditor(Target target,int fieldId){
        try{return target.isFocused() && target.matchesEditor(fieldId);}catch(RuntimeException ignored){return false;}
    }
    private static void release(Target target){try{target.release();}catch(RuntimeException ignored){}}
}
