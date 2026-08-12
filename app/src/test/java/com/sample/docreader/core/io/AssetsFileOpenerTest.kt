package com.sample.docreader.core.io

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** M1：FileOpener 桩可读取 debug assets 中的 fixtures。 */
@RunWith(RobolectricTestRunner::class)
class AssetsFileOpenerTest {

    @Test
    fun opensFixtureFromAssets() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val opener = AssetsFileOpener(context.assets)
        opener.open("asset:///fixtures/sample_utf8.txt").use { input ->
            val text = input.readBytes().toString(Charsets.UTF_8)
            assertTrue(text.contains("第一章"))
        }
    }
}
