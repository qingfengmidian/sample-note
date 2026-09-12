package com.sample.docreader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sample.docreader.core.model.ReadingTheme
import kotlin.math.roundToInt

/** 进度条 testTag（设备端/Robolectric 语义定位，F4.8） */
const val READER_PROGRESS_TAG = "reader_progress"

/** 进度比例语义键：供 UI 测试断言 `firstVisibleItemIndex/(totalItems-1)`（F4.8/TC-UI-11） */
val ReaderProgressFractionKey = SemanticsPropertyKey<Float>("ReaderProgressFraction")

/**
 * 全书进度比例（F4.8，纯函数）：`firstVisibleItemIndex / (totalItems - 1)`。
 * `totalItems <= 1` 时按 0 处理（不除零）；越界输入 clamp 到 [0, 1]。
 */
internal fun progressFraction(firstVisibleItemIndex: Int, totalItems: Int): Float {
    if (totalItems <= 1) return 0f
    return (firstVisibleItemIndex.toFloat() / (totalItems - 1)).coerceIn(0f, 1f)
}

/** 拖动比例 → 目标全局 item（纯函数）：四舍五入到最近 item，越界 clamp；空书返回 0 */
internal fun progressTargetItem(fraction: Float, totalItems: Int): Int {
    if (totalItems <= 0) return 0
    return (fraction.coerceIn(0f, 1f) * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
}

/**
 * 右缘全书进度条（F4.8，设计 §3.8）：
 * - overlay 轨道 + 拇指，比例 = `firstVisibleItemIndex/(totalItems-1)`（解析全量，精确线性）；
 * - 拖动（[detectDragGestures]）→ [onSeek] 目标全局 item，未物化章由调用方先经 VM `ensureMaterialized`；
 * - 触摸目标 ≥48dp（热区宽度 48dp，轨道/拇指居中）；[contentDescription] 语义 + [READER_PROGRESS_TAG]；
 * - 配色随 [theme]：拇指/轨道取主题前景色，对主题背景对比度 ≥4.5:1（无障碍硬要求）。
 */
@Composable
fun ReaderProgressBar(
    listState: LazyListState,
    totalItems: Int,
    theme: ReadingTheme,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 在进度条自身组合域读取首可见 item：滚动只重组进度条，不牵连正文（性能）
    val firstVisibleItemIndex = listState.firstVisibleItemIndex
    val fraction = progressFraction(firstVisibleItemIndex, totalItems)
    val total = totalItems.coerceAtLeast(0)
    val current = if (total == 0) 0 else (firstVisibleItemIndex + 1).coerceIn(1, total)
    val description = "阅读进度，第 $current/$total 段"
    BoxWithConstraints(
        modifier = modifier
            .width(48.dp) // 无障碍：触摸目标 ≥48dp
            .fillMaxHeight()
            .testTag(READER_PROGRESS_TAG)
            .semantics {
                contentDescription = description
                this[ReaderProgressFractionKey] = fraction
            }
            .pointerInput(totalItems) {
                detectDragGestures(
                    onDragStart = { offset -> onSeek(progressTargetItem(offset.y / size.height, totalItems)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onSeek(progressTargetItem(change.position.y / size.height, totalItems))
                    }
                )
            }
    ) {
        val thumbSize = 16.dp
        // 轨道：主题前景色低透明度（装饰性，不承载信息）
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(theme.foreground.copy(alpha = 0.25f))
        )
        // 拇指：主题前景色（对主题背景对比度 ≥4.5:1，随主题明暗）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    IntOffset(0, ((maxHeight - thumbSize) * fraction).roundToPx())
                }
                .size(thumbSize)
                .clip(CircleShape)
                .background(theme.foreground)
        )
    }
}
