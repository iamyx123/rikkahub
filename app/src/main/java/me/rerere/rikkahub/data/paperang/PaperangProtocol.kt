package me.rerere.rikkahub.data.paperang

/**
 * 喵喵机 Paperang N2 — Protocol_A5 帧编码（Kotlin 复刻自逆向的 paperang_n2.py）。
 *
 * 帧格式:  A5 01 [len 小端2] [content] [crc 小端4] 5A
 * content = [parent][child][type=1] + [参数长度 小端2] + [参数]
 * CRC     = zlib.crc32(content, key=896963873)  （标准 CRC32，初值=密钥）
 *
 * 全部为纯字节编码，无 Android 依赖，方便单元验证。
 */
object PaperangProtocol {
    const val HEAD = 0xA5
    const val EDITION = 0x01
    const val TAIL = 0x5A

    // 父功能码
    const val PARENT_SYSTEM = 0x01
    const val PARENT_FILE = 0x02
    const val PARENT_THERMALPRINTER = 0x05

    // 数据类型
    const val TYPE_REQUEST = 0x01

    // 热敏打印子命令
    const val TP_GET_INFO = 0x01          // 查询打印头信息（含当前纸张）
    const val TP_SET_HEAT_DENSITY = 0x11  // 设置打印浓度
    const val TP_SET_MOVE_PAPER = 0x16    // 走纸
    const val TP_SELF_TEST = 0x17         // 自检页
    const val TP_CTRL_PRINT_START = 0x19  // 打印开始
    const val TP_CTRL_PRINT_END = 0x1A    // 打印结束
    const val TP_CTRL_PRINT_DATA = 0x1B   // 打印数据块
    const val TP_GET_SIZE_INFO = 0x28     // 查询支持尺寸详情（含 HotSpot 打印像素宽）

    // 系统子命令
    const val SYS_GET_BATTERY = 0x0B      // 查询电量（parent=1, child=11）

    /** CRC32 密钥（CRC32Util.standardKey），等价 zlib.crc32(data, 896963873)。 */
    const val CRC_KEY = 896963873

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) {
            c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
        }
        c
    }

    /** 复刻 zlib.crc32(data, seed)：以 seed 为初值的标准 CRC32。 */
    fun crc32(data: ByteArray, seed: Int = CRC_KEY): Int {
        var crc = seed.inv()
        for (b in data) {
            crc = CRC_TABLE[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }

    private fun u16le(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun u32le(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

    /** packData：content -> 完整 A5 帧。 */
    fun packData(content: ByteArray): ByteArray {
        val crc = crc32(content)
        val out = ArrayList<Byte>(content.size + 8)
        out.add(HEAD.toByte())
        out.add(EDITION.toByte())
        out.addAll(u16le(content.size).toList())
        out.addAll(content.toList())
        out.addAll(u32le(crc).toList())
        out.add(TAIL.toByte())
        return out.toByteArray()
    }

    /** content = [parent][child][type] + u16(参数长) + 参数 */
    fun makeContent(parent: Int, child: Int, params: ByteArray = ByteArray(0), type: Int = TYPE_REQUEST): ByteArray {
        return byteArrayOf(parent.toByte(), child.toByte(), type.toByte()) + u16le(params.size) + params
    }

    /** 构造完整命令帧。 */
    fun buildCommand(parent: Int, child: Int, params: ByteArray = ByteArray(0), type: Int = TYPE_REQUEST): ByteArray {
        return packData(makeContent(parent, child, params, type))
    }

    /** 多参数(TLV)命令：params = [(key, value)...]（LinkedHashMap 顺序）。 */
    fun buildMultiParam(parent: Int, child: Int, params: List<Pair<Int, ByteArray>>, type: Int = TYPE_REQUEST): ByteArray {
        val tlv = ArrayList<Byte>()
        for ((key, value) in params) {
            tlv.add(key.toByte())
            tlv.addAll(u16le(value.size).toList())
            tlv.addAll(value.toList())
        }
        val tlvBytes = tlv.toByteArray()
        val content = byteArrayOf(parent.toByte(), child.toByte(), type.toByte()) + u16le(tlvBytes.size) + tlvBytes
        return packData(content)
    }

    fun u16(v: Int) = u16le(v)
    fun u32(v: Int) = u32le(v)

    /**
     * 构造一个黑白打印数据块（CommUtil.packData_A5 复刻）。
     * inner  = [precision][u16 宽/每点][u16 offset][00][u16 行数据长][行数据]
     * merged = [u16 块号+1][u16 inner长] + inner
     * content= [5,27,1] + [u16 merged长] + merged
     */
    fun buildPrintDataBlock(
        blockIndex: Int,
        lineData: ByteArray,
        devWidth: Int,
        offset: Int = 0,
        precision: Int = 1,
        perPoint: Int = 8,
    ): ByteArray {
        val widthField = devWidth / perPoint
        val inner = byteArrayOf(precision.toByte()) + u16le(widthField) + u16le(offset) +
            byteArrayOf(0) + u16le(lineData.size) + lineData
        val merged = u16le(blockIndex + 1) + u16le(inner.size) + inner
        val content = byteArrayOf(0x05, 0x1B, 0x01) + u16le(merged.size) + merged
        return packData(content)
    }

    /** 解析 A5 响应帧 -> ResponseFrame，失败返回 null。 */
    fun parseResponse(data: ByteArray): ResponseFrame? {
        if (data.size < 9 || (data[0].toInt() and 0xFF) != HEAD || (data[data.size - 1].toInt() and 0xFF) != TAIL) return null
        val contentLen = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        if (4 + contentLen > data.size) return null
        val content = data.copyOfRange(4, 4 + contentLen)
        if (content.size < 3) return null
        val parent = content[0].toInt() and 0xFF
        val child = content[1].toInt() and 0xFF
        val type = content[2].toInt() and 0xFF
        val params = if (content.size > 5) content.copyOfRange(5, content.size) else ByteArray(0)
        return ResponseFrame(parent, child, type, params)
    }

    data class ResponseFrame(val parent: Int, val child: Int, val type: Int, val params: ByteArray)
}
