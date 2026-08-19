package com.sample.safprobe

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * S1 SAF Provider 技术探针（开发计划 §2 S1）。
 *
 * 验证目标（对应设计 §3.4/§3.6 假设）：
 *  1. OpenDocument 返回 URI 的 provider/displayName/size；
 *  2. takePersistableUriPermission 后权限是否持久（含杀进程重启后再查）；
 *  3. openOutputStream(uri, "wt") 是否被支持、是否截断（写短内容后读回应无旧尾部残留）；
 *  4. "wt" 失败时回退 "w" 的实际行为与截断表现。
 *
 * 使用：先在云盘/文件管理器里准备一个专用测试文件（会被覆盖！探针会先自动备份，
 * 可用「5. 恢复原内容」写回），按 1→2→3→4 顺序点按钮；然后杀进程重进点 4 验证持久权限。
 */
class MainActivity : ComponentActivity() {

    private lateinit var logView: TextView
    private var uri: Uri? = null
    private var backup: ByteArray? = null

    private val opener = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { result ->
        if (result == null) {
            log("打开取消")
            return@registerForActivityResult
        }
        uri = result
        log("=== 1. OpenDocument 返回 ===")
        log("uri = $result")
        log("authority = ${result.authority}")
        contentResolver.query(result, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                log("displayName = ${if (nameIdx >= 0) c.getString(nameIdx) else "<无列>"}")
                log("size = ${if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else "<未知>"}")
            }
        }
        // 持久化读+写权限
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(result, flags)
            log("takePersistableUriPermission(R|W) = 成功")
        } catch (e: Exception) {
            log("takePersistableUriPermission(R|W) = 失败: ${e.javaClass.simpleName}: ${e.message}")
            try {
                contentResolver.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                log("takePersistableUriPermission(R) = 成功（仅读）")
            } catch (e2: Exception) {
                log("takePersistableUriPermission(R) = 失败: ${e2.javaClass.simpleName}: ${e2.message}")
            }
        }
        dumpPersisted("打开后")
        // 备份原内容（上限 1MB，探针会覆盖该文件）
        backup = readAll(result, 1 shl 20)
        log("已备份原内容 ${backup?.size ?: 0} 字节，前 80 字节: ${backup?.take(80)?.toByteArray()?.let { String(it, Charsets.UTF_8) }?.replace("\n", "\\n")}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { textSize = 13f; setPadding(24, 16, 24, 16) }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 0) }
        fun btn(label: String, onClick: () -> Unit) {
            panel.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
        }
        btn("1. 打开文件 (OpenDocument)") { opener.launch(arrayOf("text/*", "application/epub+zip", "*/*")) }
        btn("2. 写入 wt（写短内容测截断）") { writeTest("wt") }
        btn("3. 写入 w（写短内容测截断）") { writeTest("w") }
        btn("4. 检查持久权限") { dumpPersisted("主动检查") }
        btn("5. 恢复原内容") { restoreBackup() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(this@MainActivity).apply { addView(logView) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        log("SAF 探针就绪。请准备专用测试文件（会被覆盖），按 1→2→3→4 操作。")
    }

    /** 写已知短内容，读回验证截断行为；"wt" 失败时自动回退 "w" 并记录 */
    private fun writeTest(mode: String) {
        val target = uri ?: run { log("请先执行 1. 打开文件"); return }
        log("=== 写入测试 mode=$mode ===")
        val short = "PROBE_${mode}_短内容_0123456789\n"
        val ok = tryWrite(target, mode, short)
        if (!ok && mode == "wt") {
            log("回退尝试 mode=w ...")
            tryWrite(target, "w", "PROBE_w_fallback_短内容\n")
        }
        // 读回校验截断
        val after = readAll(target, 1 shl 20)
        if (after == null) {
            log("读回失败")
            return
        }
        val text = String(after, Charsets.UTF_8)
        log("写后文件长度 = ${after.size} 字节")
        log("写后内容 = ${text.replace("\n", "\\n")}")
        val originalTail = backup?.let { String(it, Charsets.UTF_8).takeLast(40) }
        log("残留检测：原内容尾部「$originalTail」${if (originalTail != null && text.contains(originalTail)) "仍存在 → 未截断!" else "不存在 → 截断正常"}")
    }

    private fun tryWrite(target: Uri, mode: String, content: String): Boolean {
        return try {
            contentResolver.openOutputStream(target, mode)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: throw IllegalStateException("openOutputStream 返回 null")
            log("openOutputStream($mode) 写入成功")
            true
        } catch (e: Exception) {
            log("openOutputStream($mode) 失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun restoreBackup() {
        val target = uri ?: run { log("请先执行 1. 打开文件"); return }
        val data = backup ?: run { log("无备份"); return }
        log("=== 恢复原内容（${data.size} 字节）===")
        if (!tryWriteBytes(target, "wt", data)) {
            log("wt 恢复失败，回退 w ...")
            tryWriteBytes(target, "w", data)
        }
    }

    private fun tryWriteBytes(target: Uri, mode: String, data: ByteArray): Boolean {
        return try {
            contentResolver.openOutputStream(target, mode)?.use { it.write(data); it.flush() }
                ?: throw IllegalStateException("openOutputStream 返回 null")
            log("恢复写入($mode)成功")
            true
        } catch (e: Exception) {
            log("恢复写入($mode)失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun dumpPersisted(tag: String) {
        log("=== 持久权限（$tag）===")
        val list = contentResolver.persistedUriPermissions
        if (list.isEmpty()) log("<空>")
        list.forEach {
            log("uri=${it.uri} read=${it.isReadPermission} write=${it.isWritePermission} persistableTaken=${it.persistedTime}")
        }
    }

    private fun readAll(target: Uri, limit: Int): ByteArray? {
        return try {
            contentResolver.openInputStream(target)?.use { it.readBytes().take(limit).toByteArray() }
        } catch (e: Exception) {
            log("读取失败: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun log(msg: String) {
        Log.i("SafProbe", msg)
        logView.append(msg + "\n")
    }
}
