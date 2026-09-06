package com.sample.docreader.core.parse

import com.sample.docreader.core.model.DocFormat
import com.sample.docreader.core.model.Document
import com.sample.docreader.core.model.Chapter
import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 编码探测器：对前若干字节给出最佳猜测的 charset 名；探测失败返回 null。可替换以便单测验证回退路径。 */
internal fun interface CharsetProbe {
    fun detect(head: ByteArray): String?
}

/**
 * 严格解码级联探测：能严格按 UTF-8 解码则判 UTF-8，否则返回 null（交由 GBK → Latin-1 回退）。
 * 原设计的 android.icu.text.CharsetDetector 非公开 SDK 类，真机 hidden API 策略拦截反射调用
 * （M3 真机验证发现：UTF-8 无 BOM 文件被误判为 Latin-1 乱码），故改为确定性级联。
 */
internal object StrictUtf8Probe : CharsetProbe {
    override fun detect(head: ByteArray): String? {
        // 尾部最多 3 字节可能是不完整的多字节字符（8KB 截断），逐一回退重试
        for (cut in 0..3) {
            val candidate = if (cut == 0) head else head.copyOf(head.size - cut)
            if (TextDecoding.decodableStrictly(candidate, Charsets.UTF_8)) return "UTF-8"
        }
        return null
    }
}

/** txt/md/xml 共用的文本解码逻辑：编码探测 + BOM 剥离 + 按行分段 */
internal object TextDecoding {
    private const val HEAD_BYTES = 8 * 1024

    /** 探测顺序：① BOM 判定 UTF-8/UTF-16LE/BE；② 前 8KB 严格 UTF-8 判定；③ 回退 GBK → Latin-1 */
    fun detectCharset(bytes: ByteArray, probe: CharsetProbe): Charset {
        // ① BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE

        // ② 探测（默认严格 UTF-8 判定，可替换）
        val head = bytes.copyOfRange(0, minOf(HEAD_BYTES, bytes.size))
        probe.detect(head)?.let { name ->
            runCatching { charset(name) }.getOrNull()?.let { return it }
        }

        // ③ 回退：GBK 严格解码（不可无损解码则落 Latin-1；Latin-1 任意字节可解码）
        val gbk = charset("GBK")
        return if (decodableStrictly(bytes, gbk)) gbk else Charsets.ISO_8859_1
    }

    /** 按 [charset] 解码并剥离 BOM，首段不得含 \uFEFF */
    fun decode(bytes: ByteArray, charset: Charset): String {
        val offset = when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
            bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
                (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())) -> 2
            else -> 0
        }
        return String(bytes, offset, bytes.size - offset, charset)
    }

    /** 解码并逐行分段（段落 = 行结构）；循环内 ensureActive 响应协程取消 */
    suspend fun toParagraphs(bytes: ByteArray, probe: CharsetProbe): Pair<Charset, List<String>> {
        val charset = detectCharset(bytes, probe)
        return charset to toParagraphsWith(bytes, charset)
    }

    /**
     * 按指定 [charset] 解码并逐行分段（G1 手动编码覆盖，F2.6）：跳过探测级联。
     * 循环内 ensureActive 响应协程取消。
     */
    suspend fun toParagraphsWith(bytes: ByteArray, charset: Charset): List<String> {
        val lines = decode(bytes, charset).lines()
        val paragraphs = ArrayList<String>(lines.size)
        for (line in lines) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            paragraphs += line
        }
        return paragraphs
    }

    /** 严格模式校验能否无损解码（REPORT 遇错即失败） */
    internal fun decodableStrictly(bytes: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }
}

/** TXT 解析器：编码探测后按行分段，输出单章 Document */
class TxtParser internal constructor(
    private val probe: CharsetProbe = StrictUtf8Probe
) : DocumentParser {

    override suspend fun parse(uri: Uri, resolver: ContentResolver): Document {
        val (bytes, name) = readAllBytes(uri, resolver)
        return parseBytes(bytes, name, uri.toString())
    }

    /**
     * 直接喂字节解析（单测不经 ContentResolver）。
     * [charsetOverride] 非空时跳过编码探测、按指定编码解码（G1 手动切换编码，F2.6；默认 null = 自动探测）。
     */
    internal suspend fun parseBytes(
        bytes: ByteArray,
        name: String,
        uri: String = name,
        charsetOverride: Charset? = null
    ): Document =
        withContext(Dispatchers.IO) {
            val paragraphs = if (charsetOverride != null) {
                TextDecoding.toParagraphsWith(bytes, charsetOverride)
            } else {
                TextDecoding.toParagraphs(bytes, probe).second
            }
            Document(uri, name, DocFormat.TXT, listOf(Chapter(null, paragraphs)))
        }

    /** 暴露编码判定结果，便于单测断言探测行为 */
    internal fun detectCharset(bytes: ByteArray): Charset = TextDecoding.detectCharset(bytes, probe)
}
