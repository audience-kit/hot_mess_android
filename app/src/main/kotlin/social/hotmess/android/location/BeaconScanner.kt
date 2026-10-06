package social.hotmess.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Listens for the venues' iBeacons, as the iOS app ranges them, and reports the nearest one's
 * major and minor values. A beacon's major value names a locale.
 */
class BeaconScanner(
    private val context: Context,
    uuid: String,
    private val onNearest: (major: Int, minor: Int) -> Unit,
) {
    private val proximityUuid = runCatching { UUID.fromString(uuid) }.getOrNull()
    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(APPLE) ?: return
            val beacon = parse(data) ?: return
            onNearest(beacon.first, beacon.second)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w("HotMess", "Beacon scan failed: $errorCode")
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val uuid = proximityUuid ?: return
        if (scanning || !hasPermission()) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return

        // An iBeacon advertisement: Apple's company ID, then 0x02 0x15, the UUID, major and minor.
        val prefix = ByteBuffer.allocate(18).put(0x02).put(0x15)
            .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        val mask = ByteArray(18) { 0xFF.toByte() }
        val filter = ScanFilter.Builder().setManufacturerData(APPLE, prefix + ByteArray(4), mask + ByteArray(4)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()

        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
        } catch (e: SecurityException) {
            Log.w("HotMess", "Beacon scan not allowed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Log.w("HotMess", "Beacon scan not stopped: ${e.message}")
        }
        scanning = false
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val APPLE = 0x004C

        /** The major and minor values from an iBeacon's manufacturer data, or null if it isn't one. */
        fun parse(data: ByteArray): Pair<Int, Int>? {
            if (data.size < 22 || data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null
            fun uint16(at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
            return uint16(18) to uint16(20)
        }
    }
}
