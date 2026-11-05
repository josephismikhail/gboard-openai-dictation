// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;

/** Expand touch handling only; keep the underlying editor's layout/insets unchanged. */
public final class VoiceWindowInsets {
    public static void apply(InputMethodService.Insets insets, Rect bounds) {
        if (bounds==null || bounds.isEmpty()) return;
        insets.touchableInsets=InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
        insets.touchableRegion.set(bounds);
    }
    private VoiceWindowInsets() {}
}
