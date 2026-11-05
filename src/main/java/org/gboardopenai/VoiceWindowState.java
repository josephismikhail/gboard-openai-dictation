// SPDX-License-Identifier: GPL-3.0-only
package org.gboardopenai;

import android.os.Build;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** A temporary full-screen IME window, restored without changing the editor or IME mode. */
final class VoiceWindowState {
    private final Window window;
    private final int flags, systemUi, cutoutMode, fitTypes, barBehavior, format;
    private final Drawable background;
    private final int leftPadding,topPadding,rightPadding,bottomPadding;
    private final boolean statusVisible;
    private boolean restored;
    private static final int FLAGS = WindowManager.LayoutParams.FLAG_FULLSCREEN
        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        | WindowManager.LayoutParams.FLAG_DIM_BEHIND
        | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;

    VoiceWindowState(Window window) {
        this.window = window;
        WindowManager.LayoutParams attrs = window.getAttributes();
        flags = attrs.flags; cutoutMode = attrs.layoutInDisplayCutoutMode;
        format=attrs.format;
        View decor=window.getDecorView();background=decor.getBackground();
        leftPadding=decor.getPaddingLeft();topPadding=decor.getPaddingTop();
        rightPadding=decor.getPaddingRight();bottomPadding=decor.getPaddingBottom();
        systemUi = window.getDecorView().getSystemUiVisibility();
        WindowInsets insets = window.getDecorView().getRootWindowInsets();
        statusVisible = Build.VERSION.SDK_INT < 30 || insets == null || insets.isVisible(WindowInsets.Type.statusBars());
        fitTypes = Build.VERSION.SDK_INT >= 30 ? attrs.getFitInsetsTypes() : 0;
        WindowInsetsController controller = Build.VERSION.SDK_INT >= 30 ? window.getInsetsController() : null;
        barBehavior = controller == null ? 0 : controller.getSystemBarsBehavior();
        // Giving the voice surface window focus dismisses editor-owned selection handles,
        // while ALT_FOCUSABLE_IM keeps this non-editor window from replacing the input target.
        attrs.flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        attrs.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // The view supplies one translucent scrim; keep the app and keyboard visible behind it.
        window.setBackgroundDrawableResource(android.R.color.transparent);
        attrs.format=PixelFormat.TRANSLUCENT;
        attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) attrs.setFitInsetsTypes(fitTypes & ~WindowInsets.Type.statusBars());
        window.setAttributes(attrs);
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.statusBars());
        } else window.getDecorView().setSystemUiVisibility(systemUi | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
    void restore() {
        if (restored) return;
        restored = true;
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.flags = (attrs.flags & ~FLAGS) | (flags & FLAGS);
        attrs.layoutInDisplayCutoutMode = cutoutMode;
        attrs.format=format;
        if (Build.VERSION.SDK_INT >= 30) attrs.setFitInsetsTypes(fitTypes);
        window.setAttributes(attrs);
        window.setBackgroundDrawable(background);
        window.getDecorView().setPadding(leftPadding,topPadding,rightPadding,bottomPadding);
        if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
            WindowInsetsController controller = window.getInsetsController();
            controller.setSystemBarsBehavior(barBehavior);
            if (statusVisible) controller.show(WindowInsets.Type.statusBars());
        } else window.getDecorView().setSystemUiVisibility(systemUi);
    }
}
