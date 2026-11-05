// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** Perceptual level from PCM16 little-endian audio; retains no audio samples. */
public final class AudioLevel {
    public static float measure(byte[] pcm, int count) {
        int end = Math.min(Math.max(count, 0), pcm.length) & ~1;
        if (end == 0) return 0;
        double squares = 0;
        for (int i = 0; i < end; i += 2) {
            int sample = (short)((pcm[i] & 255) | (pcm[i + 1] << 8));
            double value = sample / 32768.0;
            squares += value * value;
        }
        double rms = Math.sqrt(squares / (end / 2));
        double db = 20 * Math.log10(Math.max(rms, 0.000001));
        return (float)Math.max(0, Math.min(1, (db + 55) / 43));
    }
    private AudioLevel() {}
}
