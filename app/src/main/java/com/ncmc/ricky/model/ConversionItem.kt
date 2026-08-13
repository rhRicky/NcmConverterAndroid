package com.ncmc.ricky.model

import java.io.File

/**
 * 单个文件的转换状态
 */
class ConversionItem(val file: File, val size: Long) {

    enum class Status { PENDING, CONVERTING, DONE, FAILED, SKIPPED }

    @Volatile
    var status: Status = Status.PENDING

    /** 已解密字节数（用于单文件进度） */
    @Volatile
    var progress: Long = 0

    @Volatile
    var message: String? = null
}
