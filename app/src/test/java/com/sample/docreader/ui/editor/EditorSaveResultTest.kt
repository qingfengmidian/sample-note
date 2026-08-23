package com.sample.docreader.ui.editor

import androidx.compose.ui.text.input.TextFieldValue
import com.sample.docreader.core.io.DocumentWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * M7b 保存结果反馈（TC-UT-09 补充，由 OpenCode 编写）：
 * - [saveResult] 为一次性事件：消费后经 [EditorViewModel.consumeSaveResult] 清除；
 * - [saveResultMessage] 对 Success/FailedSaveAs/ProviderKilled 分别给出文案。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditorSaveResultTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(mainDispatcher)

    @After
    fun resetMain() = Dispatchers.resetMain()

    private class FakeWriter : DocumentWriter {
        override fun openOutput(uri: String, mode: String): OutputStream = ByteArrayOutputStream()
    }

    /** 保存成功后 saveResult = Success；consumeSaveResult 后清空（一次性事件） */
    @Test
    fun saveResult_isOneShot_consumedToNull() = runTest(mainDispatcher) {
        val vm = EditorViewModel("a", documentWriter = FakeWriter(), cacheDir = tmpFolder.root)
        vm.onContentChange(TextFieldValue("ab"))

        vm.save()
        advanceUntilIdle()
        assertTrue(vm.saveResult is SaveResult.Success)

        vm.consumeSaveResult()
        assertNull("消费后 saveResult 应清除", vm.saveResult)
    }

    /** saveResultMessage 三类结果分别给差异化文案 */
    @Test
    fun saveResultMessage_mapsAllOutcomes() {
        assertEquals("已保存", saveResultMessage(SaveResult.Success))
        assertTrue(saveResultMessage(SaveResult.FailedSaveAs).contains("另存"))
        assertTrue(saveResultMessage(SaveResult.ProviderKilled).contains("未运行"))
    }
}
