package com.mypersonalaccess.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects the phone's own data — only ever on the user's own device, only
 * while sharing is ON, and only sent to the user's own Supabase project.
 * Every function is defensive: a denied permission returns an empty
 * result instead of crashing.
 */
object DataCollectors {

    fun deviceInfo(context: Context): JSONObject {
        val info = JSONObject()
        try {
            info.put("model", Build.MANUFACTURER + " " + Build.MODEL)
            info.put("android", "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")")
            info.put("ua", "MyPersonalAccess APK 1.0 • " + Build.MODEL)
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(dm)
            info.put("screen", dm.widthPixels.toString() + "x" + dm.heightPixels)
            info.put("lang", Locale.getDefault().language)
            info.put("online", "Online")
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            info.put("battery", "$pct%" + if (charging) " (charging)" else "")
        } catch (e: Exception) {
        }
        return info
    }

    fun batteryPercent(context: Context): Int {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, filter)
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (e: Exception) {
            -1
        }
    }

    /** Phone number as stored on the SIM. Often empty — carriers usually
     *  do not store the number on the SIM, so "not available" is normal. */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun phoneNumber(context: Context): JSONObject {
        val out = JSONObject()
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val number = try {
                tm.line1Number
            } catch (e: SecurityException) {
                null
            } catch (e: Exception) {
                null
            }
            out.put("number", if (number.isNullOrBlank()) "— (SIM par save nahi hai)" else number)
            out.put("operator", tm.simOperatorName ?: "—")
            out.put("country", tm.simCountryIso?.uppercase(Locale.US) ?: "—")
        } catch (e: Exception) {
            out.put("number", "—")
            out.put("operator", "—")
            out.put("country", "—")
        }
        return out
    }

    fun contacts(context: Context, limit: Int = 50): JSONArray {
        val arr = JSONArray()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (c.moveToNext() && count < limit) {
                    val name = if (nameIdx >= 0) c.getString(nameIdx) else "—"
                    val number = if (numIdx >= 0) c.getString(numIdx) else "—"
                    arr.put(JSONObject().apply {
                        put("name", name ?: "—")
                        put("number", number ?: "—")
                    })
                    count++
                }
            }
        } catch (e: Exception) {
        }
        return arr
    }

    fun contactsCount(context: Context): Int {
        return try {
            val uri = ContactsContract.Contacts.CONTENT_URI
            context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { c ->
                c.count
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun recentSms(context: Context, limit: Int = 10): JSONArray {
        val arr = JSONArray()
        try {
            val uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
            val sort = Telephony.Sms.DATE + " DESC"
            context.contentResolver.query(uri, projection, null, null, sort)?.use { c ->
                val aIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dIdx = c.getColumnIndex(Telephony.Sms.DATE)
                var count = 0
                while (c.moveToNext() && count < limit) {
                    val body = if (bIdx >= 0) c.getString(bIdx) else ""
                    arr.put(JSONObject().apply {
                        put("from", if (aIdx >= 0) (c.getString(aIdx) ?: "—") else "—")
                        put("body", body?.take(120) ?: "")
                        put("date", fmtDate(if (dIdx >= 0) c.getLong(dIdx) else 0L))
                    })
                    count++
                }
            }
        } catch (e: Exception) {
        }
        return arr
    }

    fun recentCalls(context: Context, limit: Int = 10): JSONArray {
        val arr = JSONArray()
        try {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                CallLog.Calls.NUMBER, CallLog.Calls.TYPE,
                CallLog.Calls.DURATION, CallLog.Calls.DATE
            )
            val sort = CallLog.Calls.DATE + " DESC"
            context.contentResolver.query(uri, projection, null, null, sort)?.use { c ->
                val nIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
                val tIdx = c.getColumnIndex(CallLog.Calls.TYPE)
                val dIdx = c.getColumnIndex(CallLog.Calls.DURATION)
                val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
                var count = 0
                while (c.moveToNext() && count < limit) {
                    val type = when (if (tIdx >= 0) c.getInt(tIdx) else -1) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        else -> "Call"
                    }
                    arr.put(JSONObject().apply {
                        put("number", if (nIdx >= 0) (c.getString(nIdx) ?: "—") else "—")
                        put("type", type)
                        put("duration", (if (dIdx >= 0) c.getLong(dIdx) else 0L).toString() + "s")
                        put("date", fmtDate(if (dateIdx >= 0) c.getLong(dateIdx) else 0L))
                    })
                    count++
                }
            }
        } catch (e: Exception) {
        }
        return arr
    }

    fun recentFiles(context: Context, limit: Int = 20): JSONArray {
        val arr = JSONArray()
        try {
            val uri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE
            )
            val selection = MediaStore.MediaColumns.MIME_TYPE + " LIKE 'image/%' OR " +
                MediaStore.MediaColumns.MIME_TYPE + " LIKE 'video/%'"
            val sort = MediaStore.MediaColumns.DATE_ADDED + " DESC"
            context.contentResolver.query(uri, projection, selection, null, sort)?.use { c ->
                val nIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val mIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                var count = 0
                while (c.moveToNext() && count < limit) {
                    val mime = if (mIdx >= 0) (c.getString(mIdx) ?: "") else ""
                    val size = if (sIdx >= 0) c.getLong(sIdx) else 0L
                    arr.put(JSONObject().apply {
                        put("name", if (nIdx >= 0) (c.getString(nIdx) ?: "—") else "—")
                        put("size", humanSize(size))
                        put("kind", if (mime.startsWith("video")) "Video" else "Photo")
                    })
                    count++
                }
            }
        } catch (e: Exception) {
        }
        return arr
    }

    /** One bundle with everything the dashboard shows. */
    fun fullBundle(context: Context): JSONObject {
        return JSONObject().apply {
            put("info", deviceInfo(context))
            put("number", phoneNumber(context))
            put("contactsCount", contactsCount(context))
            put("contacts", contacts(context))
            put("sms", recentSms(context))
            put("calls", recentCalls(context))
            put("files", recentFiles(context))
        }
    }

    private fun fmtDate(millis: Long): String {
        if (millis <= 0) return "—"
        return try {
            SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(millis))
        } catch (e: Exception) {
            "—"
        }
    }

    private fun humanSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
