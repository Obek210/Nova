// =====================================================================
//  NOVA Extras: xavfsizlik, kuzatuv, salomatlik, diagnostika kengaytmalari.
//  Asosiy ilova Nova.kt da; bu fayl unga qo'shimcha.
// =====================================================================
package com.example.nova

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.location.LocationManager
import android.media.FaceDetector
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

// ---------------------------------------------------------------------
// Hodisalar jurnali: telefon qachon ochilgan (faqat qurilmada saqlanadi)
// ---------------------------------------------------------------------
object EventLog {
    private fun f(c: Context) = File(c.filesDir, "events.log")

    @Synchronized
    fun add(c: Context, type: String) {
        try {
            val file = f(c)
            if (file.length() > 200_000) file.writeText(file.readLines().takeLast(1500).joinToString("\n", postfix = "\n"))
            file.appendText("${System.currentTimeMillis()}|$type\n")
        } catch (_: Exception) {}
    }

    fun read(c: Context, since: Long, type: String): List<Long> = try {
        f(c).readLines().mapNotNull { l ->
            val p = l.split("|")
            val t = p.getOrNull(0)?.toLongOrNull()
            if (t != null && p.getOrNull(1) == type && t >= since) t else null
        }
    } catch (_: Exception) { emptyList() }
}

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

// ---------------------------------------------------------------------
// Ovozdan egasini tanish (Vosk speaker modeli). Qulay himoya, to'liq xavfsizlik emas.
// ---------------------------------------------------------------------
object Speaker {
    const val THRESH = 0.45

    fun dist(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in 0 until minOf(a.size, b.size)) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 1.0 else 1.0 - d / (sqrt(na) * sqrt(nb))
    }

    fun avg(l: List<DoubleArray>): DoubleArray {
        val n = l.minOf { it.size }
        return DoubleArray(n) { i -> l.sumOf { it[i] } / l.size }
    }

    fun save(c: Context, v: DoubleArray) = Prefs.put(c, "ownervoice", v.joinToString(","))
    fun load(c: Context): DoubleArray? =
        Prefs.getStr(c, "ownervoice").takeIf { it.isNotBlank() }?.split(",")?.map { it.toDouble() }?.toDoubleArray()
}

// ---------------------------------------------------------------------
// Kamera kadrlari (puls o'lchash, qo'riqlash uchun)
// ---------------------------------------------------------------------
object Imgs {
    fun meanY(img: Image): Double {
        val p = img.planes[0]; val b = p.buffer; val rs = p.rowStride
        var s = 0L; var c = 0
        for (y in img.height / 4 until 3 * img.height / 4 step 4)
            for (x in img.width / 4 until 3 * img.width / 4 step 4) { s += b.get(y * rs + x).toInt() and 0xFF; c++ }
        return if (c == 0) 0.0 else s.toDouble() / c
    }

    fun grid(img: Image, gw: Int = 16, gh: Int = 12): IntArray {
        val p = img.planes[0]; val b = p.buffer; val rs = p.rowStride
        return IntArray(gw * gh) { i ->
            val x = ((i % gw) * 2 + 1) * img.width / (gw * 2)
            val y = ((i / gw) * 2 + 1) * img.height / (gh * 2)
            b.get(y * rs + x).toInt() and 0xFF
        }
    }

    fun toJpeg(img: Image): ByteArray? = try {
        val w = img.width; val h = img.height
        val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
        val nv = ByteArray(w * h * 3 / 2)
        var pos = 0
        for (row in 0 until h) { yp.buffer.position(row * yp.rowStride); yp.buffer.get(nv, pos, w); pos += w }
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            nv[pos++] = vp.buffer.get(row * vp.rowStride + col * vp.pixelStride)
            nv[pos++] = up.buffer.get(row * up.rowStride + col * up.pixelStride)
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, out)
        out.toByteArray()
    } catch (_: Exception) { null }

    /** Taxminiy yuz aniqlash (kim ekanini aniqlamaydi). */
    fun faces(jpg: ByteArray): Int {
        val src = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: return 0
        var best = 0
        for (deg in intArrayOf(0, 90, 270)) {
            try {
                var b = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
                b = Bitmap.createBitmap(b, 0, 0, b.width - b.width % 2, b.height)
                val b565 = b.copy(Bitmap.Config.RGB_565, false)
                best = maxOf(best, FaceDetector(b565.width, b565.height, 3).findFaces(b565, arrayOfNulls<FaceDetector.Face>(3)))
                if (best > 0) break
            } catch (_: Exception) {}
        }
        return best
    }
}

class CamGrabber(private val ctx: Context) {
    private var thread: HandlerThread? = null
    private var dev: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null

    @SuppressLint("MissingPermission")
    fun start(front: Boolean, torch: Boolean, onFrame: (Image) -> Unit, onFail: (String) -> Unit) {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val want = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
                ?: run { onFail("kamera topilmadi"); return }
            val sizes = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
            val size = sizes.filter { it.width >= 320 && it.height >= 240 }.minByOrNull { it.width * it.height }
                ?: sizes.firstOrNull() ?: Size(640, 480)
            val t = HandlerThread("nova-cam").also { it.start() }; thread = t
            val h = Handler(t.looper)
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2); reader = r
            r.setOnImageAvailableListener({ rd ->
                val img = rd.acquireLatestImage()
                if (img != null) try { onFrame(img) } finally { img.close() }
            }, h)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    dev = c
                    try {
                        val req = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(r.surface)
                            if (torch) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                        }
                        c.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) { session = s; s.setRepeatingRequest(req.build(), null, h) }
                            override fun onConfigureFailed(s: CameraCaptureSession) { onFail("kamera sozlanmadi") }
                        }, h)
                    } catch (e: Exception) { onFail(e.message ?: "xato") }
                }
                override fun onDisconnected(c: CameraDevice) { c.close() }
                override fun onError(c: CameraDevice, e: Int) { c.close(); onFail("kamera xatosi $e") }
            }, h)
        } catch (e: Exception) { onFail(e.message ?: "xato") }
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { dev?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        thread?.quitSafely()
        session = null; dev = null; reader = null; thread = null
    }
}

// ---------------------------------------------------------------------
// Xavfsizlik tekshiruvi
// ---------------------------------------------------------------------
object Security {
    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun pkgs(raw: String?, ctx: Context) = (raw ?: "").split(":").filter { it.contains("/") }
        .map { it.substringBefore("/") }.filter { it != ctx.packageName }.distinct()

    fun audit(ctx: Context): String {
        val bad = ArrayList<String>(); val good = ArrayList<String>()
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (km.isDeviceSecure) good.add("ekran qulfi bor") else bad.add("ekran qulfi yo'q, PIN yoki barmoq izi o'rnating")

        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val enc = dpm.storageEncryptionStatus
        if (enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE || enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ||
            enc == DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY) good.add("xotira shifrlangan") else bad.add("xotira shifrlanmagan")

        if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1) bad.add("USB nosozlash yoqilgan")
        if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) bad.add("dasturchi rejimi yoqilgan")

        try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(Build.VERSION.SECURITY_PATCH)
            if (d != null) {
                val days = (System.currentTimeMillis() - d.time) / 86_400_000
                if (days > 180) bad.add("xavfsizlik yangilanishi $days kun eski") else good.add("xavfsizlik yangilanishi yangi")
            }
        } catch (_: Exception) {}

        if (listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su").any { File(it).exists() }) bad.add("root belgilari topildi")

        val acc = pkgs(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), ctx)
        if (acc.isNotEmpty()) bad.add("Maxsus imkoniyatlar yoqilgan ilovalar: " + acc.joinToString { label(ctx, it) } + ". Tanimasangiz o'chiring")
        val nl = pkgs(Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners"), ctx)
        if (nl.isNotEmpty()) bad.add("bildirishnomalarni o'qiy oladigan ilovalar: " + nl.joinToString { label(ctx, it) })
        val admins = dpm.activeAdmins?.map { it.packageName }?.distinct()?.filter { it != ctx.packageName } ?: emptyList()
        if (admins.isNotEmpty()) bad.add("qurilma administratorlari: " + admins.joinToString { label(ctx, it) })

        return if (bad.isEmpty()) "Xavfsizlik yaxshi: ${good.joinToString(", ")}."
        else "${bad.size} ta ogohlantirish. ${bad.joinToString(". ")}. Yaxshi tomonlari: ${good.joinToString(", ")}."
    }

    @Suppress("DEPRECATION")
    fun permAudit(ctx: Context): String {
        val apps = ctx.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter { p ->
            ((p.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) == 0 && p.packageName != ctx.packageName &&
                p.requestedPermissions?.contains(Manifest.permission.CAMERA) == true &&
                p.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) == true
        }.map { label(ctx, it.packageName) }
        return if (apps.isEmpty()) "Kamera va mikrofonni birga so'raydigan ilova topilmadi."
        else "${apps.size} ta ilova kamera va mikrofon ruxsatini so'raydi: ${apps.take(8).joinToString(", ")}. Keraksizlarining ruxsatini Sozlamalardan oling."
    }
}

// ---------------------------------------------------------------------
// O'z Wi-Fi tarmog'ingizni skanerlash
// ---------------------------------------------------------------------
object Lan {
    data class Dev(val ip: String, val name: String)

    private val ports = intArrayOf(80, 443, 53, 554, 8080, 8000, 22, 445, 139, 8008, 62078)

    fun open(ip: String, port: Int, ms: Int = 200): Boolean =
        try { Socket().use { it.connect(InetSocketAddress(ip, port), ms); true } } catch (_: Exception) { false }

    private fun oct(v: Int, i: Int) = (v shr (8 * i)) and 0xFF

    @Suppress("DEPRECATION")
    fun gateway(ctx: Context): String? {
        val d = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo ?: return null
        return if (d.gateway == 0) null else "${oct(d.gateway, 0)}.${oct(d.gateway, 1)}.${oct(d.gateway, 2)}.${oct(d.gateway, 3)}"
    }

    @Suppress("DEPRECATION")
    fun scan(ctx: Context): List<Dev>? {
        val d = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo ?: return null
        if (d.ipAddress == 0) return null
        val prefix = "${oct(d.ipAddress, 0)}.${oct(d.ipAddress, 1)}.${oct(d.ipAddress, 2)}."
        val ex = Executors.newFixedThreadPool(48)
        val jobs = (1..254).map { n ->
            Callable<Dev?> {
                val ip = "$prefix$n"
                val up = (try { InetAddress.getByName(ip).isReachable(250) } catch (_: Exception) { false }) || ports.any { open(ip, it, 120) }
                if (up) Dev(ip, try { InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip } ?: "" } catch (_: Exception) { "" }) else null
            }
        }
        val res = try {
            ex.invokeAll(jobs, 40, TimeUnit.SECONDS).mapNotNull { f -> try { f.get() } catch (_: Exception) { null } }
        } finally { ex.shutdownNow() }
        return res.sortedBy { it.ip.substringAfterLast('.').toInt() }
    }
}

// ---------------------------------------------------------------------
// Extras: buyruqlar (Commands orqali chaqiriladi)
// ---------------------------------------------------------------------
class Extras(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val results: (String, List<Hit>) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var guardCam: CamGrabber? = null
    @Volatile private var prevGrid: IntArray? = null
    private var guardStartAt = 0L
    private var lastAlert = 0L

    private fun go(i: Intent, msg: String? = null): Boolean {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); if (msg != null) say(msg) }
        catch (e: Exception) { say("Bajarib bo'lmadi.") }
        return true
    }

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun shutdown() { guardCam?.stop(); guardCam = null }

    // ----- telefonni uyg'otish va blokni ochish -----
    @Suppress("DEPRECATION")
    fun wake() {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "nova:wake").acquire(5000)
    }

    /** Ekranni yoqadi va tizimning ochish oynasini chiqaradi. PIN/barmoq izini o'zingiz tasdiqlaysiz. */
    fun unlock(): Boolean {
        wake()
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) { say("Telefon allaqachon ochiq."); return true }
        go(Intent(ctx, UnlockActivity::class.java))
        say(if (km.isDeviceSecure) "Ekran yoqildi. PIN, barmoq izi yoki yuz bilan tasdiqlang." else "Ekran ochilmoqda.")
        return true
    }

    // ----- xavfsizlik -----
    fun audit(): Boolean { say(Security.audit(ctx)); return true }
    fun permAudit(): Boolean { say(Security.permAudit(ctx)); return true }

    fun vlock(on: Boolean): Boolean {
        if (on && Speaker.load(ctx) == null) { say("Avval \"ovozimni o'rgan\" deng."); return true }
        Prefs.put(ctx, "vlock", on)
        say(if (on) "Ovoz qulfi yoqildi. Nozik buyruqlar faqat sizning ovozingiz bilan." else "Ovoz qulfi o'chirildi.")
        return true
    }

    fun enroll(): Boolean {
        if (!File(ctx.filesDir, "spk/mfcc.conf").exists()) {
            say("Avval Menyu, \"Ovoz tanish modelini yuklash\" ni bosing."); return true
        }
        Bus.enrollVecs.clear(); Bus.enrolling = 3
        say("Ovozingizni o'rganaman. Uchta uzunroq gap ayting.")
        return true
    }

    fun sos(): Boolean {
        val num = Prefs.getStr(ctx, "sos_number")
        val link = locationLink()?.let { " $it" } ?: ""
        if (num.isBlank()) {
            say("Favqulodda raqam kiritilmagan. Sozlamalarda kiriting. Hozir 112 raqami ochilmoqda.")
            return go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        }
        go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", "SOS! Menga yordam kerak.$link"))
        say("SOS xabari tayyor, yuborish tugmasini bosing. Zarurat bo'lsa 112 ga qo'ng'iroq qiling.")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun locationLink(): String? {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
        return loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" }
    }

    fun where(): Boolean {
        val l = locationLink()
        if (l == null) { say("Joylashuv topilmadi. Ruxsat bering va GPS'ni yoqing."); return true }
        return go(Intent(Intent.ACTION_VIEW, Uri.parse(l)), "Joylashuvingiz xaritada ochilmoqda.")
    }

    // ----- Wi-Fi qurilmalari va kameralar -----
    fun lan(markTrusted: Boolean): Boolean {
        say("Wi-Fi tarmog'ini skanerlayapman, 10-20 soniya.")
        val devs = Lan.scan(ctx)
        if (devs == null) { say("Wi-Fi'ga ulanmagansiz."); return true }
        val known = Prefs.getStr(ctx, "lan_known").split(",").filter { it.isNotBlank() }.toSet()
        if (markTrusted) {
            Prefs.put(ctx, "lan_known", devs.joinToString(",") { it.ip })
            say("${devs.size} ta qurilma ishonchli deb belgilandi."); return true
        }
        val fresh = devs.filter { it.ip !in known }
        results("wifi", devs.take(6).map {
            Hit("${it.ip}  ${it.name}".trim(), if (known.isEmpty()) "" else if (it in fresh) "Yangi qurilma!" else "Tanish", "http://${it.ip}", "Wi-Fi")
        })
        say("Tarmoqda ${devs.size} ta qurilma topildi." +
            if (known.isEmpty()) " Ularni ishonchli deb belgilash uchun \"qurilmalarga ishonaman\" deng."
            else if (fresh.isEmpty()) " Hammasi tanish." else " Yangi qurilmalar: ${fresh.size} ta. Notanish bo'lsa, Wi-Fi parolini almashtiring.")
        return true
    }

    fun findCams(): Boolean {
        say("IP kameralarni qidiryapman.")
        val devs = Lan.scan(ctx)
        if (devs == null) { say("Wi-Fi'ga ulanmagansiz."); return true }
        val cams = devs.filter { Lan.open(it.ip, 554, 300) }
        if (cams.isEmpty()) { say("Ochiq kamera portli qurilma topilmadi."); return true }
        results("kameralar", cams.take(5).map { Hit("Kamera ${it.ip}", "RTSP port ochiq. VLC bilan ochiladi.", "rtsp://${it.ip}:554/", "Kamera") })
        say("${cams.size} ta kamera bo'lishi mumkin. Kartochkani bosing, kamera parolini o'zingiz kiritasiz.")
        return true
    }

    /** Sozlamalardagi "nom|url" ro'yxatidan o'z kameralaringizni ochadi. */
    fun openCam(name: String): Boolean {
        val list = Prefs.getStr(ctx, "cams").lines().mapNotNull { l -> l.split("|").takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        if (list.isEmpty()) { say("Kamera ro'yxati bo'sh. Sozlamalarda nom|havola ko'rinishida kiriting."); return true }
        val pick = list.firstOrNull { Util.latin(it.first).contains(Util.latin(name)) } ?: list.first()
        return go(Intent(Intent.ACTION_VIEW, Uri.parse(pick.second)), "${pick.first} kamerasi ochilmoqda.")
    }

    fun router(): Boolean {
        val g = Lan.gateway(ctx)
        if (g == null) { say("Wi-Fi'ga ulanmagansiz."); return true }
        return go(Intent(Intent.ACTION_VIEW, Uri.parse("http://$g")), "Router sahifasi ochilmoqda.")
    }

    // ----- telefon faolligi -----
    fun unlocks(): Boolean {
        val t = EventLog.read(ctx, startOfToday(), "unlock")
        if (t.isEmpty()) { say("Bugun ochilish qayd etilmagan. Jurnal Nova yoqilgandan beri yuritiladi."); return true }
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        say("Bugun telefon ${t.size} marta ochilgan. Oxirgisi ${f.format(Date(t.last()))}." +
            if (t.size > 1) " Oldingilari: " + t.dropLast(1).takeLast(4).joinToString(", ") { f.format(Date(it)) } else "")
        return true
    }

    fun usage(): Boolean {
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val st = um.queryAndAggregateUsageStats(startOfToday(), System.currentTimeMillis())
        if (st.isNullOrEmpty()) {
            say("Ruxsat kerak. Foydalanish statistikasi sozlamalari ochilmoqda, Nova'ni yoqing.")
            return go(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        val top = st.values.filter { it.totalTimeInForeground > 60_000 && it.packageName != ctx.packageName }
            .sortedByDescending { it.totalTimeInForeground }.take(5)
        val total = st.values.filter { it.packageName != ctx.packageName }.sumOf { it.totalTimeInForeground } / 60_000
        say("Bugun ilovalar bilan taxminan $total daqiqa ishlangan. Eng ko'p: " +
            top.joinToString(", ") { "${Security.label(ctx, it.packageName)} ${it.totalTimeInForeground / 60_000} daqiqa" })
        return true
    }

    fun fullReport(): Boolean {
        val ul = EventLog.read(ctx, startOfToday(), "unlock").size
        say(Diagnostics.report(ctx) + " Bugun telefon $ul marta ochilgan. " + Security.audit(ctx))
        return true
    }

    // ----- salomatlik (tibbiy asbob emas) -----
    fun steps(): Boolean {
        if (!granted(Manifest.permission.ACTIVITY_RECOGNITION)) { say("Qadam sanash uchun jismoniy faollik ruxsati kerak."); return true }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (s == null) { say("Qadam sensori yo'q."); return true }
        val latch = CountDownLatch(1); var v = -1f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { v = e.values[0]; latch.countDown() }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
        latch.await(3, TimeUnit.SECONDS); sm.unregisterListener(l)
        if (v < 0) { say("Qadam ma'lumoti olinmadi."); return true }
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val base = Prefs.getStr(ctx, "step_base").toFloatOrNull()
        if (Prefs.getStr(ctx, "step_day") != day || base == null || v < base) {
            Prefs.put(ctx, "step_day", day); Prefs.put(ctx, "step_base", v.toString())
            say("Bugungi qadam sanash hozir boshlandi. Keyinroq so'rang.")
        } else say("Bugun taxminan ${(v - base).toInt()} qadam yurdingiz.")
        return true
    }

    fun bmi(t: String): Boolean {
        val n = Regex("\\d+(?:\\.\\d+)?").findAll(t).map { it.value.toDouble() }.toList()
        if (n.size < 2) { say("Vazn va bo'yni ayting. Masalan: bmi 70 kilogramm 175 santimetr."); return true }
        val m = if (n[1] < 3) n[1] else n[1] / 100
        val b = n[0] / (m * m)
        val cat = when { b < 18.5 -> "vazn yetishmaydi"; b < 25 -> "normal"; b < 30 -> "ortiqcha vazn"; else -> "semizlik" }
        say("Tana massasi indeksi %.1f, bu: %s. Bu faqat taxminiy ko'rsatkich.".format(b, cat))
        return true
    }

    fun water(): Boolean = go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 3600)
        .putExtra(AlarmClock.EXTRA_MESSAGE, "Suv iching").putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Bir soatdan keyin suv ichishni eslataman.")

    /** Puls: barmoq orqa kamera va chiroq ustida, 15 soniya. Taxminiy, tibbiy asbob emas. */
    fun pulse(): Boolean {
        if (!granted(Manifest.permission.CAMERA)) { say("Kamera ruxsati kerak."); return true }
        if (busy || guardCam != null) { say("Kamera band."); return true }
        busy = true
        say("Barmog'ingizni orqa kamera va chiroq ustiga yengil bosib qo'ying. 15 soniya o'lchayman, qimirlamang.")
        val ts = Collections.synchronizedList(ArrayList<Long>()); val vs = Collections.synchronizedList(ArrayList<Double>())
        val cam = CamGrabber(ctx)
        cam.start(false, true, { img -> ts.add(img.timestamp / 1_000_000); vs.add(Imgs.meanY(img)) }, { e ->
            busy = false; main.post { say("Kamera xatosi: $e") } })
        main.postDelayed({
            cam.stop(); busy = false
            val r = synchronized(ts) { bpm(ArrayList(ts), ArrayList(vs)) }
            say(if (r == null) "Natija ishonchsiz. Barmog'ingizni kamerani to'liq yopadigan qilib bosib, qayta urinib ko'ring."
            else "Pulsingiz taxminan $r zarba daqiqasiga. Bu tibbiy o'lchov emas. O'zingizni yomon sezsangiz, shifokorga murojaat qiling.")
        }, 16000)
        return true
    }

    private fun bpm(ts: List<Long>, vs: List<Double>): Int? {
        val n = vs.size
        if (n < 90 || vs.average() < 40) return null
        val w = 12
        val x = DoubleArray(n) { i -> vs[i] - vs.subList(maxOf(0, i - w), minOf(n, i + w + 1)).average() }
        val y = DoubleArray(n) { i -> (x[maxOf(0, i - 1)] + x[i] + x[minOf(n - 1, i + 1)]) / 3.0 }
        val thr = y.map { abs(it) }.average() * 0.6
        val peaks = ArrayList<Long>()
        for (i in 1 until n - 1)
            if (y[i] > y[i - 1] && y[i] >= y[i + 1] && y[i] > thr && (peaks.isEmpty() || ts[i] - peaks.last() > 330)) peaks.add(ts[i])
        if (peaks.size < 8) return null
        val iv = peaks.zipWithNext { a, b -> (b - a).toDouble() }
        val mean = iv.average()
        if (sqrt(iv.map { (it - mean) * (it - mean) }.average()) / mean > 0.3) return null
        val b = (60000.0 / mean).toInt()
        return if (b in 40..200) b else null
    }

    // ----- qo'riqlash rejimi (telefon kamerasi, bildirishnoma bilan ochiq ishlaydi) -----
    fun guardStart(front: Boolean): Boolean {
        if (!granted(Manifest.permission.CAMERA)) { say("Kamera ruxsati kerak."); return true }
        if (guardCam != null) { say("Qo'riqlash allaqachon yoqilgan."); return true }
        if (busy) { say("Kamera band."); return true }
        val cam = CamGrabber(ctx); guardCam = cam
        prevGrid = null; guardStartAt = System.currentTimeMillis()
        cam.start(front, false, { img -> onGuardFrame(img) }, { e -> guardCam = null; main.post { say("Kamera xatosi: $e") } })
        say("Qo'riqlash yoqildi. ${if (front) "Old" else "Orqa"} kamera harakatni kuzatadi. To'xtatish uchun: qo'riqlashni to'xtat.")
        return true
    }

    fun guardStop(): Boolean {
        if (guardCam == null) { say("Qo'riqlash yoqilmagan."); return true }
        shutdown(); say("Qo'riqlash to'xtatildi."); return true
    }

    private fun onGuardFrame(img: Image) {
        val now = System.currentTimeMillis()
        val g = Imgs.grid(img); val p = prevGrid; prevGrid = g
        if (p == null || now - guardStartAt < 4000 || now - lastAlert < 20_000) return
        var c = 0
        for (i in g.indices) if (abs(g[i] - p[i]) > 25) c++
        if (c < 20) return
        lastAlert = now
        val jpg = Imgs.toJpeg(img)
        val faces = jpg?.let { Imgs.faces(it) } ?: 0
        var path = ""
        if (jpg != null) try {
            val dir = ctx.getExternalFilesDir("guard") ?: ctx.filesDir
            val f = File(dir, "guard_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg")
            f.writeBytes(jpg); path = f.name
        } catch (_: Exception) {}
        EventLog.add(ctx, "motion")
        notifyAlert("Harakat aniqlandi" + if (faces > 0) " (yuz bor)" else "", if (path.isNotEmpty()) "Rasm saqlandi: $path" else "")
        say("Harakat aniqlandi." + if (faces > 0) " Kadrda odam yuzi bor." else "")
    }

    private fun notifyAlert(title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nova_alert", "Nova ogohlantirish", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify((System.currentTimeMillis() % 100000).toInt(), android.app.Notification.Builder(ctx, "nova_alert")
            .setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(open).setAutoCancel(true).build())
    }

    // ----- sayt ochib skrinshot (masalan kundalik.com; parolni brauzer o'zi to'ldiradi) -----
    fun siteShot(label: String, url: String): Boolean {
        val s = NovaService.instance
        if (s == null) { say("Avval Maxsus imkoniyatlar xizmatini yoqing."); return true }
        go(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "$label ochilmoqda. Hisobingizga kirgan bo'lsangiz, 8 soniyadan keyin skrinshot olaman.")
        main.postDelayed({ say(if (s.screenshot()) "Skrinshot olindi. Rasmlar papkasida." else "Skrinshot olib bo'lmadi.") }, 8000)
        return true
    }
}

/** Ekran qulfini ochish oynasini chiqaradi (tizimning o'zi tasdiqlashni so'raydi). */
class UnlockActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { finish() }
            override fun onDismissCancelled() { finish() }
            override fun onDismissError() { finish() }
        })
    }
}
