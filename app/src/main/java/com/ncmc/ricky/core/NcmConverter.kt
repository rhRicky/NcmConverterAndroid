package com.ncmc.ricky.core

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 非 NCM 文件异常
 */
class NotNcmException(message: String) : Exception(message)
/**
 * 转换被用户取消
 */
class ConversionCancelledException : Exception("已取消")

/**
 * NCM 转换异常
 */
class NcmConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 解密进度回调（按字节） */
typealias ProgressListener = (bytesDone: Long, totalBytes: Long) -> Unit

/**
 * NCM 解密核心 —— 逐行移植自 ncm_decrypt_reference.py
 *
 * 对应关系：
 *   core_key / meta_key       -> Python dumpfile() 中的密钥准备
 *   aesEcbDecryptNoPad + unpad-> Python AES.new(mode_ECB) + unpad
 *   rc4 密钥盒初始化            -> Python “关键优化 1: RC4 Key Box 初始化”
 *   掩码表 mask                -> Python “关键优化 2: 预计算 XOR 掩码”
 *   分块异或解密                -> Python “关键优化 3: 整数位运算加速”
 */
object NcmConverter {

    private const val TAG = "NcmConverter"

    /** 与 Python 中 core_key 一致 */
    private val CORE_KEY = hexStringToBytes("687A4852416D736F356B496E62617857")

    /** 与 Python 中 meta_key 一致 */
    private val META_KEY = hexStringToBytes("2331346C6A6B5F215C5D2630553C2728")

    /** 与 Python 中 CHUNK_SIZE 一致 (64KB，256 的倍数，保证掩码对齐) */
    private const val CHUNK_SIZE = 65536

    /** 与 Python tag_audio() 中的 MetaData 一致 */
    class MetaData(val format: String, val musicName: String, val album: String, val artist: String)

    /**
     * 转换单个 NCM 文件。
     *
     * @param inputFile  NCM 源文件
     * @param outputDir  输出目录（会自动创建）
     * @param listener   解密进度回调（按字节）
     * @param isCancelled 返回 true 时中止转换并抛出 [ConversionCancelledException]
     * @return 输出文件
     */
    @Throws(Exception::class)
    fun convert(
        inputFile: File,
        outputDir: File,
        listener: ProgressListener? = null,
        isCancelled: () -> Boolean = { false }
    ): File {
        if (!inputFile.isFile) throw NcmConversionException("文件不存在: ${inputFile.name}")
        val fileNameRaw = inputFile.name

        RandomAccessFile(inputFile, "r").use { reader ->
            // ============ 1. 密钥准备与文件头校验 ============
            val magic = ByteArray(8)
            reader.readFully(magic)
            // 头部魔数应为 "CTENFDAM"
            if (bytesToHex(magic) != "4354454e4644414d") {
                throw NotNcmException("非 NCM 文件: $fileNameRaw")
            }

            reader.skipBytes(2) // 跳过 2 字节保留位

            // ============ 2. 解析并解密 RC4 密钥 ============
            val keyLength = reader.readIntLe()
            if (keyLength <= 0 || keyLength > 1024 * 1024) {
                throw NcmConversionException("非法的密钥长度: $keyLength")
            }
            val keyXored = ByteArray(keyLength)
            reader.readFully(keyXored)
            // 逐字节异或 0x64（对应 Python: [byte ^ 0x64 for byte in key_data]）
            val keyRaw = ByteArray(keyLength)
            for (i in keyRaw.indices) keyRaw[i] = ((keyXored[i].toInt() and 0xff) xor 0x64).toByte()

            // AES-128-ECB 解密 + 去除 PKCS7 填充
            val keyDecrypted = aesEcbDecryptNoPad(CORE_KEY, keyRaw)
            val keyPadded = pkcs7Unpad(keyDecrypted)
            // 跳过前 17 字节 "neteasecloudmusic" 魔数（对应 Python: [17:]）
            if (keyPadded.size < 17) {
                throw NcmConversionException("密钥数据异常: $fileNameRaw")
            }
            val aesKey = keyPadded.copyOfRange(17, keyPadded.size)
            val aesKeyLen = aesKey.size

            // ============ 3. RC4 Key Box 初始化 ============
            // 对应 Python “关键优化 1”，逐行一致
            val keyBox = IntArray(256) { it }
            var c = 0
            var lastByte = 0
            var keyPos = 0
            for (i in 0 until 256) {
                c = (keyBox[i] + lastByte + (aesKey[keyPos].toInt() and 0xff)) and 0xff
                keyPos++
                if (keyPos >= aesKeyLen) keyPos = 0
                val tmp = keyBox[i]
                keyBox[i] = keyBox[c]
                keyBox[c] = tmp
                lastByte = c
            }

            // ============ 4. 元数据解析 ============
            var format = "mp3"
            var musicName = "Unknown"
            var album = "Unknown"
            var artist = "Unknown"

            val metaLength = reader.readIntLe()
            if (metaLength >= 22) {
                if (metaLength > 16 * 1024 * 1024) {
                    throw NcmConversionException("非法的元数据长度: $metaLength")
                }
                val metaXored = ByteArray(metaLength)
                reader.readFully(metaXored)
                // 逐字节异或 0x63（对应 Python: [byte ^ 0x63 for byte in meta_data_bytes]）
                val metaRaw = ByteArray(metaLength)
                for (i in metaRaw.indices) metaRaw[i] = ((metaXored[i].toInt() and 0xff) xor 0x63).toByte()
                // 跳过前 22 字节 "163 key(Don't modify):" 后 base64 解码
                val metaB64 = metaRaw.copyOfRange(22, metaRaw.size)
                val metaEnc = Base64.decode(metaB64, Base64.DEFAULT)
                val metaDecrypted = pkcs7Unpad(aesEcbDecryptNoPad(META_KEY, metaEnc))
                // 跳过前 6 字节 "music:" 前缀后为 JSON
                if (metaDecrypted.size >= 6) {
                    val metaJson = String(metaDecrypted, Charsets.UTF_8).substring(6)
                    try {
                        val json = JSONObject(metaJson)
                        format = json.optString("format", "mp3")
                        musicName = json.optString("musicName", "Unknown")
                        album = json.optString("album", "Unknown")
                        val artistArr = json.optJSONArray("artist")
                        if (artistArr != null && artistArr.length() > 0) {
                            val names = mutableListOf<String>()
                            for (i in 0 until artistArr.length()) {
                                val a = artistArr.optJSONArray(i)
                                if (a != null && a.length() > 0) names.add(a.optString(0))
                            }
                            if (names.isNotEmpty()) artist = names.joinToString("/")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "元数据解析失败: ${e.message}")
                    }
                }
            } else if (metaLength > 0) {
                Log.w(TAG, "元数据长度过短，忽略: $metaLength")
            }

            // ============ 5. 跳过 CRC / 保留字段 / 读取封面 ============
            reader.skipBytes(4) // CRC32
            reader.skipBytes(5) // 保留字段
            val imageSize = reader.readIntLe()
            var imageData = ByteArray(0)
            if (imageSize > 0) {
                if (imageSize > 32 * 1024 * 1024) {
                    throw NcmConversionException("非法的封面长度: $imageSize")
                }
                imageData = ByteArray(imageSize)
                reader.readFully(imageData)
            }

            // ============ 6. 预计算 256 字节 XOR 掩码表 ============
            // 对应 Python “关键优化 2”，逐行一致
            val mask = ByteArray(256)
            for (i in 0 until 256) {
                val j = (i + 1) and 0xff
                val a = keyBox[j]
                val b = keyBox[(a + j) and 0xff]
                mask[i] = keyBox[(a + b) and 0xff].toByte()
            }

            // ============ 7. 分块解密并写出音频流 ============
            // 对应 Python “关键优化 3”。CHUNK_SIZE 为 256 的倍数，
            // 每块从索引 0 重新套用掩码表与全局计数等价，结果一致。
            outputDir.mkdirs()
            val outFile = File(outputDir, fileNameRaw.substringBeforeLast('.') + "." + format)

            val totalBytes = inputFile.length()
            var processed = 0L
            val chunk = ByteArray(CHUNK_SIZE)
            try {
                FileOutputStream(outFile).buffered(1 shl 16).use { out ->
                    var lastReport = System.currentTimeMillis()
                    while (true) {
                        if (isCancelled()) throw ConversionCancelledException()
                        val n = reader.read(chunk)
                        if (n < 0) break
                        val write = if (n == CHUNK_SIZE) chunk else chunk.copyOf(n)
                        for (k in 0 until n) {
                            write[k] = (write[k].toInt() xor (mask[k and 0xff].toInt() and 0xff)).toByte()
                        }
                        out.write(write)
                        processed += n
                        val now = System.currentTimeMillis()
                        if (listener != null && (now - lastReport >= 80 || processed >= totalBytes)) {
                            listener.invoke(processed, totalBytes)
                            lastReport = now
                        }
                    }
                    out.flush()
                }
            } catch (e: ConversionCancelledException) {
                if (outFile.exists()) outFile.delete()
                throw e
            }

            // ============ 8. 写入标签 ============
            val meta = MetaData(format, musicName, album, artist)
            if (format == "flac") {
                FlacTagWriter.write(outFile, meta, imageData)
            } else {
                Id3v2Writer.write(outFile, meta, imageData)
            }
            return outFile
        }
    }

    // ------------------------------------------------------------------
    // 工具函数
    // ------------------------------------------------------------------

    /**
     * AES-128-ECB 解密（不自动处理填充）。
     * 对应 Python: AES.new(key, AES.MODE_ECB).decrypt(data)
     */
    private fun aesEcbDecryptNoPad(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    /**
     * 去除 PKCS7 填充。对应 Python: unpad = lambda s: s[:-(s[-1])]
     * 若填充长度非法则原样返回，避免误伤异常文件。
     */
    private fun pkcs7Unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        val padLen = data[data.size - 1].toInt() and 0xff
        if (padLen <= 0 || padLen > 16 || padLen > data.size) return data
        return data.copyOf(data.size - padLen)
    }

    private fun hexStringToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (byte in b) {
            val v = byte.toInt() and 0xff
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }
}

/**
 * 小端序读取 4 字节无符号整数
 */
private fun RandomAccessFile.readIntLe(): Int {
    val b0 = read() and 0xff
    val b1 = read() and 0xff
    val b2 = read() and 0xff
    val b3 = read() and 0xff
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}
