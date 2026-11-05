// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

/** Expand across keyboard padding, never into the IME's transparent full-screen container. */
public final class VoiceSurfaceBounds {
    public static boolean canExpand(int childHeight, int parentHeight, float density) {
        return childHeight > 0 && parentHeight >= childHeight
            && parentHeight - childHeight <= 24 * density;
    }
    private VoiceSurfaceBounds() {}
}
