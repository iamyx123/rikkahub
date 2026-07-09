package me.rerere.rikkahub.data.paperang

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.json.JSONObject

/**
 * 喵喵机 N2 蓝牙(BLE)打印机管理器。
 *
 * 职责：扫描 / 连接 / 保持连接与自动重连 / 纸张自动检测 / 黑白 & 灰度打印。
 * 协议编码见 [PaperangProtocol]，图片编码见 [PaperangImaging]。
 *
 * 说明：Android 每次只允许一个未完成的 GATT 写操作，因此写入串行化并等待
 * onCharacteristicWrite 回调（这是正确的快路径，而非人为 sleep）。
 */
@SuppressLint("MissingPermission")
class PaperangPrinter(
    private val context: Context,
    private val appScope: CoroutineScope,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "PaperangPrinter"
        private const val DEFAULT_NAME_FILTER = "paperang"
        private const val CHUNK = 134
        const val DEFAULT_WIDTH = 576 // 2寸；连接后自动检测覆盖
    }

    enum class ConnState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

    data class Status(
        val state: ConnState = ConnState.DISCONNECTED,
        val deviceName: String? = null,
        val deviceAddress: String? = null,
        val paperWidthPx: Int = DEFAULT_WIDTH,
        val message: String? = null,
    )

    data class ScannedDevice(val name: String, val address: String, val rssi: Int)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    private val bluetoothManager get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu = 23

    private val notifyChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeMutex = Mutex()
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var servicesDeferred: CompletableDeferred<Boolean>? = null
    private var mtuDeferred: CompletableDeferred<Boolean>? = null

    @Volatile private var autoReconnectEnabled = true
    @Volatile private var appInForeground = true
    private var desiredAddress: String? = null
    private var scanning = false

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnectEnabled = enabled
    }

    /** 是否满足自动重连条件：开关开 + 应用在前台 + 蓝牙已开 + 有目标设备。 */
    private fun canReconnect(): Boolean =
        autoReconnectEnabled && appInForeground && isBluetoothOn() && desiredAddress != null

    init {
        // 仅在前台自动重连（后台 BLE 重连很耗电）；回到前台时尝试自动连接上次记忆的设备
        appScope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            appInForeground = true
                            maybeAutoConnect()
                        }
                        Lifecycle.Event.ON_STOP -> appInForeground = false
                        else -> {}
                    }
                }
            )
        }
        // 监听蓝牙开关：关闭时立即断开并停止重连，打开时（前台）恢复连接
        runCatching {
            ContextCompat.registerReceiver(
                context, btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        // 应用启动即尝试连接上次记忆的设备
        maybeAutoConnect()
    }

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    stopScan()
                    if (gatt != null) {
                        runCatching { gatt?.disconnect() }
                        cleanupGatt()
                    }
                    _status.value = _status.value.copy(state = ConnState.DISCONNECTED, message = "蓝牙已关闭")
                }
                BluetoothAdapter.STATE_ON -> maybeAutoConnect()
            }
        }
    }

    /**
     * 设备记忆：若配置了上次连接的设备且开启自动重连，前台 + 蓝牙开启时自动连接。
     * 覆盖两种场景：冷启动首次连接、以及断线/回到前台后的恢复。
     */
    @Volatile private var autoConnectInFlight = false

    private fun maybeAutoConnect() {
        // 同步守卫：init 与 ON_START 可能相继触发，避免并发发起两次连接
        if (autoConnectInFlight || gatt != null || !appInForeground || !isBluetoothOn()) return
        if (_status.value.state == ConnState.CONNECTING || _status.value.state == ConnState.SCANNING) return
        autoConnectInFlight = true
        appScope.launch {
            try {
                val cfg = settingsStore.settingsFlow.value.displaySetting.paperangPrinter
                val addr = (desiredAddress ?: cfg.deviceAddress).takeIf { it.isNotBlank() } ?: return@launch
                if (!cfg.autoReconnect) return@launch
                autoReconnectEnabled = cfg.autoReconnect
                runCatching { connect(addr) }
            } finally {
                autoConnectInFlight = false
            }
        }
    }

    // ─── 扫描 ───

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = runCatching { dev.name }.getOrNull() ?: result.scanRecord?.deviceName ?: return
            if (name.isBlank()) return
            if (!name.lowercase().contains(DEFAULT_NAME_FILTER)) return
            val entry = ScannedDevice(name, dev.address, result.rssi)
            val cur = _scanResults.value.toMutableList()
            val i = cur.indexOfFirst { it.address == entry.address }
            if (i >= 0) cur[i] = entry else cur.add(entry)
            _scanResults.value = cur.sortedByDescending { it.rssi }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            if (_status.value.state == ConnState.SCANNING) {
                _status.value = _status.value.copy(state = ConnState.DISCONNECTED, message = "扫描失败($errorCode)")
            }
        }
    }

    suspend fun startScan(timeoutMs: Long = 10_000) {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _status.value = _status.value.copy(message = "蓝牙不可用")
            return
        }
        if (scanning) return
        _scanResults.value = emptyList()
        scanning = true
        _status.value = _status.value.copy(state = ConnState.SCANNING, message = null)
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure { _status.value = _status.value.copy(message = "无法扫描: ${it.message}") }
        delay(timeoutMs)
        stopScan()
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_status.value.state == ConnState.SCANNING) {
            _status.value = _status.value.copy(state = if (gatt != null) ConnState.CONNECTED else ConnState.DISCONNECTED)
        }
    }

    // ─── 连接 ───

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "connected, discovering services")
                connectDeferred?.complete(true)
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "disconnected (status=$statusCode)")
                connectDeferred?.complete(false)
                servicesDeferred?.complete(false)
                cleanupGatt()
                _status.value = _status.value.copy(state = ConnState.DISCONNECTED)
                // 仅前台 + 蓝牙开启 + 有目标设备时自动重连
                if (canReconnect()) {
                    appScope.launch { reconnectLoop() }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            var w: BluetoothGattCharacteristic? = null
            var n: BluetoothGattCharacteristic? = null
            for (svc in g.services) {
                for (ch in svc.characteristics) {
                    val props = ch.properties
                    val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                        (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    if (canWrite && w == null) w = ch
                    if (canNotify && n == null) n = ch
                }
            }
            writeChar = w
            notifyChar = n
            if (n != null) {
                runCatching {
                    g.setCharacteristicNotification(n, true)
                    val cccd = n.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    if (cccd != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            g.writeDescriptor(cccd)
                        }
                    }
                }
            }
            servicesDeferred?.complete(w != null)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, statusCode: Int) {
            negotiatedMtu = mtu
            mtuDeferred?.complete(true)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            notifyChannel.trySend(ch.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            notifyChannel.trySend(value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, statusCode: Int) {
            writeDeferred?.complete(statusCode == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun cleanupGatt() {
        writeChar = null
        notifyChar = null
        runCatching { gatt?.close() }
        gatt = null
    }

    suspend fun connect(address: String): Boolean = withContext(Dispatchers.IO) {
        if (!isBluetoothOn()) {
            _status.value = _status.value.copy(state = ConnState.DISCONNECTED, message = "蓝牙未开启，请先打开蓝牙")
            return@withContext false
        }
        stopScan()
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return@withContext false
        _status.value = _status.value.copy(state = ConnState.CONNECTING, deviceAddress = address, message = null)
        connectDeferred = CompletableDeferred()
        servicesDeferred = CompletableDeferred()
        cleanupGatt()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice_TRANSPORT_LE)
        val connected = withTimeoutOrNull(20_000) { connectDeferred?.await() } ?: false
        if (!connected) {
            _status.value = _status.value.copy(state = ConnState.DISCONNECTED, message = "连接超时")
            cleanupGatt()
            return@withContext false
        }
        val servicesOk = withTimeoutOrNull(15_000) { servicesDeferred?.await() } ?: false
        if (!servicesOk || writeChar == null) {
            _status.value = _status.value.copy(state = ConnState.DISCONNECTED, message = "未找到可写特征")
            disconnect()
            return@withContext false
        }
        // 请求更大 MTU，便于 134 字节分片
        mtuDeferred = CompletableDeferred()
        runCatching { gatt?.requestMtu(247) }
        withTimeoutOrNull(3_000) { mtuDeferred?.await() }
        delay(200)
        val name = runCatching { device.name }.getOrNull() ?: _scanResults.value.find { it.address == address }?.name
        desiredAddress = address // 连接成功后才记为目标设备（用于前台自动重连）
        _status.value = _status.value.copy(
            state = ConnState.CONNECTED,
            deviceName = name,
            deviceAddress = address,
            message = null,
        )
        // 自动检测纸张宽度
        val width = detectPaperWidth()
        if (width != null) _status.value = _status.value.copy(paperWidthPx = width)
        true
    }

    private suspend fun reconnectLoop() {
        val addr = desiredAddress ?: return
        var attempt = 0
        while (canReconnect() && gatt == null && attempt < 5) {
            attempt++
            _status.value = _status.value.copy(state = ConnState.CONNECTING, message = "重新连接中($attempt)")
            val ok = runCatching { connect(addr) }.getOrDefault(false)
            if (ok) return
            delay(2000L * attempt)
        }
    }

    fun disconnect() {
        desiredAddress = null // 用户主动断开，不再自动重连
        runCatching { gatt?.disconnect() }
        cleanupGatt()
        _status.value = Status()
    }

    // ─── 底层发送 ───

    private suspend fun sendRaw(data: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        val maxChunk = minOf(CHUNK, (negotiatedMtu - 3).coerceAtLeast(20))
        var i = 0
        while (i < data.size) {
            val end = minOf(i + maxChunk, data.size)
            val chunk = data.copyOfRange(i, end)
            val ok = writeMutex.withLock {
                writeDeferred = CompletableDeferred()
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        ch.value = chunk
                        g.writeCharacteristic(ch)
                    }
                }
                if (!started) false
                else withTimeoutOrNull(3_000) { writeDeferred?.await() } ?: false
            }
            if (!ok) return false
            i = end
        }
        return true
    }

    private suspend fun sendCommand(parent: Int, child: Int, params: ByteArray = ByteArray(0)): Boolean =
        sendRaw(PaperangProtocol.buildCommand(parent, child, params))

    private suspend fun awaitResponse(parent: Int, child: Int, timeoutMs: Long): PaperangProtocol.ResponseFrame? {
        return withTimeoutOrNull(timeoutMs) {
            var found: PaperangProtocol.ResponseFrame? = null
            while (found == null) {
                val raw = notifyChannel.receive()
                val p = PaperangProtocol.parseResponse(raw)
                if (p != null && p.parent == parent && p.child == child) found = p
            }
            found
        }
    }

    // ─── 纸张检测 ───

    private suspend fun detectPaperWidth(): Int? {
        // drain 旧通知
        while (notifyChannel.tryReceive().isSuccess) { /* drain */ }
        var current: Int? = null
        runCatching {
            sendCommand(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_GET_INFO)
            val r = awaitResponse(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_GET_INFO, 3000)
            if (r != null) current = extractJson(r.params)?.optJSONObject("TPInfo")?.optInt("TPCurrentSize", -1)?.takeIf { it > 0 }
        }
        var width: Int? = null
        runCatching {
            sendCommand(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_GET_SIZE_INFO)
            val r = awaitResponse(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_GET_SIZE_INFO, 3000)
            if (r != null) {
                val arr = extractJson(r.params)?.optJSONArray("TPSizeInfo")
                if (arr != null) {
                    for (idx in 0 until arr.length()) {
                        val o = arr.optJSONObject(idx) ?: continue
                        if (current != null && o.optInt("PaperWidth", -1) == current) {
                            width = o.optInt("HotSpot", 0).takeIf { it > 0 }
                        }
                    }
                }
            }
        }
        return width
    }

    private fun extractJson(params: ByteArray): JSONObject? {
        val i = params.indexOf('{'.code.toByte())
        if (i < 0) return null
        return runCatching {
            JSONObject(String(params, i, params.size - i, Charsets.UTF_8))
        }.getOrNull()
    }

    // ─── 打印 ───

    /** 黑白/灰度打印一张位图；返回是否成功。density 1-255。 */
    suspend fun printBitmap(bitmap: android.graphics.Bitmap, grayscale: Boolean, density: Int = 90, feedAfter: Int = 0): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (gatt == null || writeChar == null) return@withContext Result.failure(IllegalStateException("打印机未连接"))
            val width = _status.value.paperWidthPx.takeIf { it > 0 } ?: DEFAULT_WIDTH
            runCatching {
                if (grayscale) printGray(bitmap, width, density, feedAfter)
                else printBw(bitmap, width, density, feedAfter)
            }
        }

    private suspend fun printBw(bitmap: android.graphics.Bitmap, devWidth: Int, density: Int, feedAfter: Int) {
        val bw = PaperangImaging.imageTo1bpp(bitmap, devWidth, dither = true)
        val lineBytes = devWidth / 8
        val devMaxLen = 1024
        var blockSize = 0
        while (blockSize + lineBytes < devMaxLen - 2 * lineBytes) blockSize += lineBytes
        if (blockSize == 0) blockSize = lineBytes

        sendCommand(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_SET_HEAT_DENSITY, byteArrayOf(density.toByte()))
        delay(50)
        sendCommand(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_CTRL_PRINT_START)
        delay(50)
        var idx = 0
        var i = 0
        while (i < bw.data.size) {
            val end = minOf(i + blockSize, bw.data.size)
            val block = bw.data.copyOfRange(i, end)
            sendRaw(PaperangProtocol.buildPrintDataBlock(idx, block, devWidth))
            idx++
            i = end
        }
        delay(100)
        sendCommand(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_CTRL_PRINT_END)
        if (feedAfter > 0) {
            delay(50)
            feed(feedAfter)
        }
    }

    private suspend fun printGray(bitmap: android.graphics.Bitmap, devWidth: Int, density: Int, feedAfter: Int) {
        val gray = PaperangImaging.imageToGray4(bitmap, devWidth)
        val outWidth = gray.width
        sendCommand(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_SET_HEAT_DENSITY, byteArrayOf(density.toByte()))
        delay(50)
        // grayPrintStart: file_type=9,len,accuracy=8,width,offset=0,compress=0,resume=0
        val start = PaperangProtocol.buildMultiParam(
            PaperangProtocol.PARENT_FILE, 0x01,
            listOf(
                1 to PaperangProtocol.u16(9),
                2 to PaperangProtocol.u32(gray.data.size),
                3 to byteArrayOf(8),
                4 to PaperangProtocol.u16(outWidth),
                5 to PaperangProtocol.u16(0),
                6 to PaperangProtocol.u16(0),
                7 to byteArrayOf(0),
            )
        )
        sendRaw(start)
        delay(80)
        var order = 0
        var i = 0
        while (i < gray.data.size) {
            val end = minOf(i + outWidth, gray.data.size)
            var blk = gray.data.copyOfRange(i, end)
            if (blk.size < outWidth) blk += ByteArray(outWidth - blk.size) // 末块补 0(=白)
            order++
            val merged = PaperangProtocol.u16(order) + PaperangProtocol.u16(blk.size) + blk
            val content = byteArrayOf(0x02, 0x03, 0x01) + PaperangProtocol.u16(merged.size) + merged
            sendRaw(PaperangProtocol.packData(content))
            i = end
        }
        delay(100)
        sendRaw(PaperangProtocol.buildCommand(PaperangProtocol.PARENT_FILE, 0x02))
        if (feedAfter > 0) {
            delay(50)
            feed(feedAfter)
        }
    }

    suspend fun feed(lines: Int) {
        sendCommand(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_SET_MOVE_PAPER, PaperangProtocol.u16(lines))
    }

    /** 重新检测当前纸张宽度并更新状态（供打印预览刷新纸张信息）。 */
    suspend fun refreshPaperWidth(): Int? = withContext(Dispatchers.IO) {
        if (gatt == null || writeChar == null) return@withContext null
        val w = detectPaperWidth()
        if (w != null) _status.value = _status.value.copy(paperWidthPx = w)
        w ?: _status.value.paperWidthPx
    }

    suspend fun selfTest() {
        sendCommand(PaperangProtocol.PARENT_THERMALPRINTER, PaperangProtocol.TP_SELF_TEST)
    }
}

/** BluetoothDevice.TRANSPORT_LE 常量（避免 import 名冲突）。 */
private const val BluetoothDevice_TRANSPORT_LE = 2
