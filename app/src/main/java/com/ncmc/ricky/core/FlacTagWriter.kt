package com.ncmc.ricky.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * FLAC 元数据块写入器
 *
 * 对应 Python tag_audio() 中 FLAC 分支的效果：
 *   写入 VORBIS_COMMENT（title / album / artist）与 PICTURE（封面）。
 * 会移除文件中原有的 VORBIS_COMMENT / PICTURE 块后重建，
 * 保留 STREAMINFO 及其它块（SEEKTABLE 等），保证输出始终合法。
 */
object FlacTagWriter {

    private const val CHUNK = 65536

    /** VORBIS_COMMENT 块类型 */
    private const val BLOCK_VORBIS = 4

    /** PICTURE 块类型 */
    private const val BLOCK_PICTURE = 6

    fun write(file: File, meta: NcmConverter.MetaData, imageData: ByteArray) {
        val temp = File(file.parentFile, file.name + ".flac.tmp")

        RandomAccessFile(file, "r").use { reader ->
            val magic = ByteArray(4)
            reader.readFully(magic)
            if (!magic.contentEquals("fLaC".toByteArray())) {
                throw NcmConversionException("不是有效的 FLAC 文件: ${file.name}")
            }

            // ---- 读取现有元数据块 ----
            val blocks = mutableListOf<Pair<Int, ByteArray>>()
            var last = false
            while (!last) {
                val header = ByteArray(4)
                reader.readFully(header)
                last = (header[0].toInt() and 0x80) != 0
                val type = header[0].toInt() and 0x7f
                val len = ((header[1].toInt() and 0xff) shl 16) or
                        ((header[2].toInt() and 0xff) shl 8) or
                        (header[3].toInt() and 0xff)
                val data = ByteArray(len)
                if (len > 0) reader.readFully(data)
                blocks.add(type to data)
            }
            val audioOffset = reader.filePointer

            // ---- 重建块列表 ----
            // 始终重建 VORBIS_COMMENT；仅当拿到新封面时才替换 PICTURE，
            // 否则保留原封面，避免丢失。
            val haveImage = imageData.isNotEmpty()
            val newBlocks = mutableListOf<Pair<Int, ByteArray>>()
            for ((type, data) in blocks) {
                if (type == BLOCK_VORBIS) continue
                if (type == BLOCK_PICTURE && haveImage) continue
                newBlocks.add(type to data)
            }
            newBlocks.add(BLOCK_VORBIS to buildVorbisComment(meta))
            if (haveImage) {
                newBlocks.add(BLOCK_PICTURE to buildPicture(imageData))
            }

            // ---- 写出新文件 ----
            FileOutputStream(temp).buffered(1 shl 16).use { out ->
                out.write(magic)
                for ((index, pair) in newBlocks.withIndex()) {
                    val type = pair.first
                    val data = pair.second
                    val isLast = index == newBlocks.size - 1
                    val headerByte = (if (isLast) 0x80 else 0) or (type and 0x7f)
                    out.write(headerByte)
                    out.write(intTo24BE(data.size))
                    out.write(data)
                }
                reader.seek(audioOffset)
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

    /** VORBIS_COMMENT 块数据：vendor + TITLE/ALBUM/ARTIST */
    private fun buildVorbisComment(meta: NcmConverter.MetaData): ByteArray {
        val vendor = "reference libFLAC 1.3.2".toByteArray(Charsets.UTF_8)
        val comments = listOf(
            "TITLE=${meta.musicName}",
            "ALBUM=${meta.album}",
            "ARTIST=${meta.artist}"
        )
        val body = ByteArrayOutputStream()
        writeLeInt(body, vendor.size)
        body.write(vendor)
        writeLeInt(body, comments.size)
        for (comment in comments) {
            val b = comment.toByteArray(Charsets.UTF_8)
            writeLeInt(body, b.size)
            body.write(b)
        }
        return body.toByteArray()
    }

    /** PICTURE 块数据（FLAC 规范：JPEG 封面） */
    private fun buildPicture(imageData: ByteArray): ByteArray {
        val dims = JpegParser.dimensions(imageData)
        val mime = "image/jpeg".toByteArray(Charsets.UTF_8)
        val body = ByteArrayOutputStream()
        writeBeInt(body, 3)            // picture type: 3 = 专辑封面
        writeBeInt(body, mime.size)
        body.write(mime)
        writeBeInt(body, 0)            // 描述长度 0
        writeBeInt(body, dims.width)
        writeBeInt(body, dims.height)
        writeBeInt(body, 24)           // 色深
        writeBeInt(body, 0)            // 颜色数（非索引图）
        writeBeInt(body, imageData.size)
        body.write(imageData)
        return body.toByteArray()
    }

    /** 小端 4 字节 */
    private fun writeLeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 24) and 0xff)
    }

    /** 大端 4 字节 */
    private fun writeBeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xff)
        out.write((value ushr 16) and 0xff)
        out.write((value ushr 8) and 0xff)
        out.write(value and 0xff)
    }

    /** 24 位大端整数（FLAC 块长度字段） */
    private fun intTo24BE(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }
}
