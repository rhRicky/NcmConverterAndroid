package com.ncmc.ricky.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 存储路径相关工具
 */
object StorageUtil {

    /** 默认 NCM 输入目录 */
    fun defaultInputDir(): File =
        File(Environment.getExternalStorageDirectory(), "Music")

    /** 默认输出目录 */
    fun defaultOutputDir(): File =
        File(Environment.getExternalStorageDirectory(), "Music/NCM-Converter")

    /** 输出不可写时的回退目录（应用专属外部目录，无需存储权限） */
    fun fallbackOutputDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Converted")
        dir.mkdirs()
        return dir
    }

    /** 目录下是否存在 .ncm 文件 */
    fun hasNcmFiles(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory) return false
        val files = dir.listFiles { _, name -> name.toLowerCase().endsWith(".ncm") }
        return files != null && files.isNotEmpty()
    }

    /**
     * 真实写入探测：尝试在目录下创建并删除一个临时文件。
     * 用于在 Android 12+ 等严格作用域存储环境下提前检测不可写。
     */
    fun isWritable(dir: File): Boolean {
        return try {
            dir.mkdirs()
            val probe = File(dir, ".probe_${System.currentTimeMillis()}")
            probe.createNewFile()
            probe.delete()
            true
        } catch (e: Exception) {
            false
        }
    }
}
