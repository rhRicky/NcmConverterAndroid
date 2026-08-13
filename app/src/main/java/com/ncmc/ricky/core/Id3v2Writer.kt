package com.ncmc.ricky.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * ID3v2.3 标签写入器
 *
 * 对应 Python tag_audio() 中 MP3 分支的效果：
 *   标题 (TIT2)、专辑 (TALB)、歌手 (TPE1)、封面 (APIC)。
 * 输出文件若自带 ID3v2 标签会被整体替换，保证写入结果确定、正确。
 */
object Id3v2Writer {

    private const val CHUNK = 65536

    fun write(file: File, meta: NcmConverter.MetaData, imageData: ByteArray) {
        val tagBytes = buildTag(meta, imageData)
        val temp = File(file.parentFile, file.name + ".id3.tmp")

        var audioStart = 0L
        RandomAccessFile(file, "r").use { reader ->
            val magic = ByteArray(3)
            if (reader.read(magic) == 3 && magic.contentEquals("ID3".toByteArray())) {
                // 已有 ID3v2 标签：解析其大小并跳过（异常大小则退回从头追加）
                val sizeBytes = ByteArray(6)
                reader.readFully(sizeBytes)
                audioStart = 10L + syncsafeToInt(sizeBytes)
                if (audioStart < 10L || audioStart > file.length()) {
                    audioStart = 0L
                }
            } else {
                audioStart = 0L
            }
            reader.seek(audioStart)

            FileOutputStream(temp).buffered(1 shl 16).use { out ->
                out.write(tagBytes)
                val buf = ByteArray(CHUNK)
                var n: Int
                while (true) {
                    n = reader.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.flush()
            }
        }
        temp.copyTo(file, overwrite = true)
        temp.delete()
    }

    private fun buildTag(meta: NcmConverter.MetaData, imageData: ByteArray): ByteArray {
        val frames = mutableListOf<ByteArray>()
        textFrame("TIT2", meta.musicName)?.let { frames.add(it) }
        textFrame("TALB", meta.album)?.let { frames.add(it) }
        textFrame("TPE1", meta.artist)?.let { frames.add(it) }
        if (imageData.isNotEmpty()) frames.add(apicFrame(imageData))

        var tagSize = 0
        for (f in frames) tagSize += f.size

        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray())
        out.write(byteArrayOf(3, 0, 0)) // 版本 2.3.0，无标志位
        out.write(intToSyncsafe(tagSize))
        for (f in frames) out.write(f)
        return out.toByteArray()
    }

    /** 文本帧：TIT2 / TALB / TPE1，使用 UTF-16(LE) 编码 */
    private fun textFrame(id: String, value: String): ByteArray? {
        if (value.isEmpty()) return null
        val body = ByteArrayOutputStream()
        body.write(0x01) // encoding: UTF-16 with BOM
        body.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) // BOM (LE)
        body.write(value.toByteArray(Charsets.UTF_16LE))
        return wrapFrame(id, body.toByteArray())
    }

    /** 封面帧 APIC（image/jpeg，类型 3 封面） */
    private fun apicFrame(imageData: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x00) // encoding: ISO-8859-1（仅用于 mime/描述）
        body.write("image/jpeg".toByteArray())
        body.write(0x00) // mime 结束符
        body.write(3)    // picture type: 3 = 专辑封面
        body.write(0x00) // 空描述
        body.write(imageData)
        return wrapFrame("APIC", body.toByteArray())
    }

    /** 组装单帧：帧ID(4) + 长度(4 BE) + 标志(2) + 数据 */
    private fun wrapFrame(id: String, data: ByteArray): ByteArray {
        val frame = ByteArrayOutputStream()
        frame.write(id.toByteArray())
        frame.write(intToBytesBE(data.size))
        frame.write(byteArrayOf(0, 0)) // flags
        frame.write(data)
        return frame.toByteArray()
    }

    /** 4 字节大端整数 */
    private fun intToBytesBE(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    /** ID3v2 头部的 syncsafe 32 位整数 */
    private fun intToSyncsafe(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 21) and 0x7f).toByte(),
            ((value shr 14) and 0x7f).toByte(),
            ((value shr 7) and 0x7f).toByte(),
            (value and 0x7f).toByte()
        )
    }

    /** 解析 ID3v2 头部的 syncsafe 大小（4 字节数据，索引 2..5） */
    private fun syncsafeToInt(b: ByteArray): Int {
        val b0 = b[2].toInt() and 0x7f
        val b1 = b[3].toInt() and 0x7f
        val b2 = b[4].toInt() and 0x7f
        val b3 = b[5].toInt() and 0x7f
        return (b0 shl 21) or (b1 shl 14) or (b2 shl 7) or b3
    }
}
