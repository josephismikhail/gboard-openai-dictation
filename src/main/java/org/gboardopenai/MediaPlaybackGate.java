// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/** Ask the current player to pause for the whole voice UI, then return its focus. */
public final class MediaPlaybackGate implements AutoCloseable {
    private final MediaFocusLease lease;

    public MediaPlaybackGate(Context context){
        AudioManager audio=context.getSystemService(AudioManager.class);
        AudioFocusRequest request=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(change->{},new Handler(Looper.getMainLooper()))
            .build();
        lease=new MediaFocusLease(new MediaFocusLease.Backend(){
            @Override public boolean acquire(){
                return audio!=null && audio.requestAudioFocus(request)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
            @Override public void release(){audio.abandonAudioFocusRequest(request);}
        });
    }
    public boolean isAcquired(){return lease.isAcquired();}
    @Override public void close(){lease.close();}
}
