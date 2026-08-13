package com.ncmc.ricky.core

/**
 * JPEG 尺寸解析器：扫描 SOF 标记，获取封面宽高（用于 FLAC PICTURE 块）。
 */
object JpegParser {

    data class Dimensions(val width: Int, val height: Int)

    fun dimensions(jpeg: ByteArray): Dimensions {
        try {
            if (jpeg.size < 10) return Dimensions(0, 0)
            if ((jpeg[0].toInt() and 0xff) != 0xFF || (jpeg[1].toInt() and 0xff) != 0xD8) {
                return Dimensions(0, 0)
            }
            var i = 2
            while (i + 9 < jpeg.size) {
                if ((jpeg[i].toInt() and 0xff) != 0xFF) {
                    i++
                    continue
                }
                val marker = jpeg[i + 1].toInt() and 0xff
                // 独立标记：无长度字段
                if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7) || marker == 0xFF) {
                    i += 2
                    continue
                }
                val len = ((jpeg[i + 2].toInt() and 0xff) shl 8) or (jpeg[i + 3].toInt() and 0xff)
                if (len < 2) return Dimensions(0, 0)
                // SOF0-SOF15（除 DHT/C2/C4/C8/CC 外）
                val isSof = marker in 0xC0..0xCF &&
                        marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (isSof) {
                    val height = ((jpeg[i + 5].toInt() and 0xff) shl 8) or (jpeg[i + 6].toInt() and 0xff)
                    val width = ((jpeg[i + 7].toInt() and 0xff) shl 8) or (jpeg[i + 8].toInt() and 0xff)
                    return Dimensions(width, height)
                }
                i += 2 + len
            }
        } catch (e: Exception) {
            // 忽略解析失败，返回 0x0
        }
        return Dimensions(0, 0)
    }
}
