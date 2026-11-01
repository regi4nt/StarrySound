package com.fxsoundlite.app

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.Environment
import android.os.storage.StorageManager
import java.util.Locale

/** Where a song lives. Values are stable (used as filter ids). */
object StorageKind {
    const val ANY = -1
    const val DEVICE = 0
    const val SDCARD = 1
    const val OTG = 2

    fun labelRes(kind: Int): Int = when (kind) {
        SDCARD -> R.string.storage_sd
        OTG -> R.string.storage_otg
        else -> R.string.storage_device
    }
}

/** One storage volume the system currently knows about. [key] is "primary" or the lower-case volume uuid. */
data class StorageVol(val key: String, val kind: Int, val label: String, val mounted: Boolean)

/**
 * Tells the phone's own storage, the SD card and an OTG (USB) drive apart.
 *
 * MediaStore and the Storage Access Framework both name a volume the same way ("primary" for the internal
 * storage, the lower-case uuid such as "1234-5678" for anything removable), so that name is the common key.
 */
object Storages {

    const val PRIMARY = "primary"

    /** Volumes right now (mounted or not), the internal storage first. Cheap enough to call on a background thread. */
    fun volumes(ctx: Context): List<StorageVol> {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return emptyList()
        val list = try { sm.storageVolumes } catch (e: Exception) { return emptyList() }
        val removable = list.filter { !it.isPrimary && it.uuid != null }
        val usbOnly = usbDriveAttached(ctx) && removable.size == 1
        val out = ArrayList<StorageVol>()
        for (v in list) {
            val mounted = v.state == Environment.MEDIA_MOUNTED || v.state == Environment.MEDIA_MOUNTED_READ_ONLY
            val desc = try { v.getDescription(ctx).orEmpty() } catch (e: Exception) { "" }
            if (v.isPrimary) {
                out.add(StorageVol(PRIMARY, StorageKind.DEVICE, desc.ifBlank { "Device" }, mounted))
                continue
            }
            val uuid = v.uuid?.lowercase(Locale.ROOT) ?: continue
            out.add(StorageVol(uuid, classifyRemovable(desc, usbOnly), desc.ifBlank { uuid }, mounted))
        }
        return out.sortedBy { if (it.key == PRIMARY) 0 else 1 }
    }

    /** SD card or OTG? The label the system gives the volume is the best hint; a lone USB drive plugged in settles the rest. */
    private fun classifyRemovable(description: String, usbOnly: Boolean): Int {
        val d = description.lowercase(Locale.ROOT)
        return when {
            d.contains("usb") || d.contains("otg") -> StorageKind.OTG
            d.contains("sd card") || d.contains("sdcard") || d.contains("micro") || Regex("\\bsd\\b").containsMatchIn(d) -> StorageKind.SDCARD
            usbOnly -> StorageKind.OTG
            else -> StorageKind.SDCARD
        }
    }

    /** A USB mass-storage device (flash drive, card reader, disk) is connected to the OTG port. */
    private fun usbDriveAttached(ctx: Context): Boolean = try {
        val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        um.deviceList.values.any { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE }
        }
    } catch (e: Exception) {
        false
    }

    /** Kind of the volume called [key] (the internal storage when it is not known any more). */
    fun kindOf(ctx: Context, key: String): Int {
        if (key == PRIMARY || key.isEmpty()) return StorageKind.DEVICE
        return volumes(ctx).firstOrNull { it.key == key }?.kind ?: StorageKind.SDCARD
    }

    /** MediaStore's volume name ("external_primary", "1234-5678") -> our key. */
    fun keyOfMediaStoreName(name: String?): String = when {
        name.isNullOrEmpty() || name == "external_primary" || name == "external" -> PRIMARY
        else -> name.lowercase(Locale.ROOT)
    }

    /** Old Android (before 10) has only a file path: /storage/emulated/0/... is the phone, /storage/1234-5678/... a card or drive. */
    fun keyOfPath(path: String?): String {
        if (path.isNullOrEmpty()) return PRIMARY
        val m = Regex("^/storage/([^/]+)/").find(path) ?: return PRIMARY
        val seg = m.groupValues[1]
        return if (seg == "emulated" || seg == "self") PRIMARY else seg.lowercase(Locale.ROOT)
    }

    /**
     * Same file, same key, whichever API found it: "primary:music/a.mp3" or "1234-5678:music/a.mp3" (lower-case).
     * Lets a song found through a picked folder be recognised when MediaStore already lists it.
     */
    fun pathKey(volumeKey: String, relativePath: String?, name: String?): String {
        val rel = (relativePath ?: "").trim('/')
        val file = name ?: ""
        val full = if (rel.isEmpty()) file else "$rel/$file"
        return (volumeKey + ":" + full).lowercase(Locale.ROOT)
    }
}
