// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** Main-thread interaction state, separate from asynchronous audio/network callbacks. */
public final class VoiceUiState {
    public enum Phase { IDLE, STARTING, LISTENING, PROCESSING }
    private Phase phase = Phase.IDLE;
    public Phase phase() { return phase; }
    public boolean begin() {
        if (phase != Phase.IDLE) return false;
        phase = Phase.STARTING;
        return true;
    }
    public void recording() {
        // A delayed microphone callback must not undo a fast second tap.
        if (phase == Phase.STARTING) phase = Phase.LISTENING;
    }
    public boolean stop() {
        if (phase != Phase.STARTING && phase != Phase.LISTENING) return false;
        phase = Phase.PROCESSING;
        return true;
    }
    public void reset() { phase = Phase.IDLE; }
}
