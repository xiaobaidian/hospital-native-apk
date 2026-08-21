package com.hospital.photolog

import java.io.File

data class PhotoItem(
    val file: File,
    val time: String = "",
    val quantity: Int = 1,
    /** 写入「下载」目录时使用的文件名；非空则可用于去重，避免重复保存 */
    val downloadName: String? = null
)
