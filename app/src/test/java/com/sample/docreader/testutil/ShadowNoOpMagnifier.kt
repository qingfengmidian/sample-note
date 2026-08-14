package com.sample.docreader.testutil

import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric 下 PixelCopy 恒失败，会走 Magnifier.onPixelCopyFailed → dismiss →
 * InternalPopupWindow.destroy 的 Surface NPE（真机无此问题）。测试中将放大镜置为无操作。
 */
@Implements(Magnifier::class)
class ShadowNoOpMagnifier {
    @Implementation
    fun show(x: Float, y: Float) {
    }

    @Implementation
    fun show(x: Float, y: Float, x2: Float, y2: Float) {
    }

    @Implementation
    fun dismiss() {
    }

    @Implementation
    fun update() {
    }
}
