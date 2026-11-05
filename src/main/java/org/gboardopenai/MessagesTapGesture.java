// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** Recognizes a single-pointer tap; target identity and native hit bounds remain the caller's responsibility. */
public final class MessagesTapGesture {
    private final double slopSquared;
    private boolean active;
    private int pointerId;
    private float startX,startY;

    public MessagesTapGesture(float touchSlop){
        if(!Float.isFinite(touchSlop) || touchSlop<0)throw new IllegalArgumentException("Invalid touch slop");
        slopSquared=(double)touchSlop*touchSlop;
    }

    public void begin(int pointerId,float x,float y,boolean targetHit){
        cancel();
        if(!targetHit || pointerId<0 || !Float.isFinite(x) || !Float.isFinite(y))return;
        this.pointerId=pointerId;startX=x;startY=y;active=true;
    }

    public void move(int pointerCount,int pointerId,float x,float y){
        if(!valid(pointerCount,pointerId,x,y))cancel();
    }

    public boolean finish(int pointerCount,int pointerId,float x,float y,boolean targetStillValid){
        boolean tap=targetStillValid && valid(pointerCount,pointerId,x,y);
        cancel();
        return tap;
    }

    public void cancel(){active=false;pointerId=-1;startX=0;startY=0;}

    private boolean valid(int pointerCount,int pointerId,float x,float y){
        if(!active || pointerCount!=1 || pointerId!=this.pointerId || !Float.isFinite(x) || !Float.isFinite(y))return false;
        double dx=(double)x-startX,dy=(double)y-startY;
        return dx*dx+dy*dy<=slopSquared;
    }
}
