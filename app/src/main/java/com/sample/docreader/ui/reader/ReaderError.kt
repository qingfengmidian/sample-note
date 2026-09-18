package com.sample.docreader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.sample.docreader.R
import com.sample.docreader.core.model.ParseException

/**
 * M14（F4.6，T3）阅读/编辑错误类型：VM 只传错误**类型**，UI 经 `stringResource` 映射文案，
 * 避免中文文案硬编码在 VM 层导致无法本地化。
 *
 * 分支与 [ParseException] 子类一一对应（MalformedContent 只保留 cause 供日志，
 * 展示固定文案，见简报 T3）；[ProviderNotRunning] 对应「文件提供方未运行」。
 */
sealed interface ReaderError {
    /** [ParseException.UnsupportedFormat]：不支持的格式 */
    data object UnsupportedFormat : ReaderError

    /** [ParseException.MissingOpf]：epub 缺少 OPF 描述文件 */
    data object MissingOpf : ReaderError

    /** [ParseException.MalformedContent]：内容解析失败（cause 仅日志，展示固定文案） */
    data class MalformedContent(val cause: Throwable?) : ReaderError

    /** [ParseException.TooLarge]：文件过大，超出可编辑上限 */
    data object TooLarge : ReaderError

    /** [ParseException.NoPermission]：权限不足或文件已被移动/删除 */
    data object NoPermission : ReaderError

    /** 云盘 Provider 进程被系统/OEM 杀死（异常消息含 "No content provider"，S1 探针结论） */
    data object ProviderNotRunning : ReaderError

    companion object {
        /** [ParseException] → [ReaderError]，穷尽映射五个子类 */
        fun from(e: ParseException): ReaderError = when (e) {
            is ParseException.UnsupportedFormat -> UnsupportedFormat
            is ParseException.MissingOpf -> MissingOpf
            is ParseException.MalformedContent -> MalformedContent(e.cause)
            is ParseException.TooLarge -> TooLarge
            is ParseException.NoPermission -> NoPermission
        }
    }
}

/** 错误类型 → 本地化文案（阅读/编辑界面共用；必须在 @Composable 上下文调用） */
@Composable
fun readerErrorText(error: ReaderError): String = when (error) {
    ReaderError.UnsupportedFormat -> stringResource(R.string.reader_error_unsupported_format)
    ReaderError.MissingOpf -> stringResource(R.string.reader_error_missing_opf)
    is ReaderError.MalformedContent -> stringResource(R.string.reader_error_malformed_content)
    ReaderError.TooLarge -> stringResource(R.string.reader_error_too_large)
    ReaderError.NoPermission -> stringResource(R.string.reader_error_no_permission)
    ReaderError.ProviderNotRunning -> stringResource(R.string.reader_error_provider_not_running)
}
