package org.gboardopenai;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceUiTest {
    @Test public void panelIncludesKeyboardPaddingButNotTransparentImeWindow() {
        assertTrue(VoiceSurfaceBounds.canExpand(774, 795, 2.625f));
        assertFalse(VoiceSurfaceBounds.canExpand(795, 2232, 2.625f));
        assertFalse(VoiceSurfaceBounds.canExpand(0, 2232, 2.625f));
        assertFalse(VoiceSurfaceBounds.canExpand(795, 600, 2.625f));
    }
    private byte[] samples(int value, int count) {
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < bytes.length; i += 2) {
            bytes[i] = (byte)value; bytes[i + 1] = (byte)(value >> 8);
        }
        return bytes;
    }
    @Test public void silenceAndNoiseFloorStayFlat() {
        assertEquals(0f, AudioLevel.measure(new byte[3200], 3200), 0f);
        assertEquals(0f, AudioLevel.measure(samples(10, 1600), 3200), 0f);
    }
    @Test public void positiveAndNegativePcmHaveSameLevel() {
        assertEquals(AudioLevel.measure(samples(2048, 100), 200),
            AudioLevel.measure(samples(-2048, 100), 200), 0.00001f);
    }
    @Test public void louderSpeechIncreasesLevelWithoutExceedingOne() {
        assertTrue(AudioLevel.measure(samples(300, 100), 200) < AudioLevel.measure(samples(3000, 100), 200));
        assertEquals(1f, AudioLevel.measure(samples(-32768, 100), 200), 0f);
    }
    @Test public void incompleteOrUnusedBufferBytesAreIgnored() {
        byte[] data = {0, 0, 127, 127};
        assertEquals(0f, AudioLevel.measure(data, 3), 0f);
        assertEquals(0f, AudioLevel.measure(data, 0), 0f);
        assertEquals(AudioLevel.measure(data, 4), AudioLevel.measure(data, 100), 0f);
    }
    @Test public void stopBeforeAudioStartsCannotReturnToListening() {
        VoiceUiState state = new VoiceUiState();
        assertTrue(state.begin()); assertTrue(state.stop()); state.recording();
        assertEquals(VoiceUiState.Phase.PROCESSING, state.phase());
        assertFalse(state.stop()); assertFalse(state.begin());
    }
    @Test public void silenceDoesNotFinishRecordingAndCompletionAllowsNextSession() {
        VoiceUiState state = new VoiceUiState(); state.begin(); state.recording();
        AudioLevel.measure(new byte[3200], 3200);
        assertEquals(VoiceUiState.Phase.LISTENING, state.phase());
        assertTrue(state.stop()); assertFalse(state.stop()); state.reset();
        assertTrue(state.begin());
    }
    @Test public void cancellationAtAnyPhaseReturnsToIdle() {
        VoiceUiState state = new VoiceUiState();
        state.begin(); state.reset(); assertEquals(VoiceUiState.Phase.IDLE, state.phase());
        state.begin(); state.recording(); state.reset(); assertTrue(state.begin());
        state.stop(); state.reset(); assertTrue(state.begin());
    }
}
