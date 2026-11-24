// =====================================================================
//  NOVA: Android uchun ovozli yordamchi. Butun ilova shu bitta faylda.
//  Bo'limlar: 1 Sozlamalar  2 Yordamchilar  3 Orb  4 Oflayn  5 AI
//             6 Qidiruv  7 Ovoz tanish  8 Diagnostika  9 Buyruqlar
//             10 Boshqaruv xizmati  11 Asosiy xizmat  12 Ekran
// =====================================================================
package com.example.nova

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ---------------------------------------------------------------------
// 1. SOZLAMALAR
// ---------------------------------------------------------------------
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("nova", Context.MODE_PRIVATE)

    fun put(c: Context, k: String, v: Any) {
        val e = sp(c).edit()
        when (v) { is String -> e.putString(k, v); is Float -> e.putFloat(k, v); is Boolean -> e.putBoolean(k, v) }
        e.apply()
    }

    fun apiKey(c: Context): String = sp(c).getString("key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.OPENROUTER_API_KEY
    fun model(c: Context): String = sp(c).getString("model", "openrouter/auto") ?: "openrouter/auto"
    fun wake(c: Context): String = sp(c).getString("wake", "nova,novo,nuva,jarvis") ?: "nova"
    fun wakeWords(c: Context): List<String> =
        wake(c).split(",").map { Util.latin(it) }.filter { it.isNotEmpty() }
    fun city(c: Context): String = sp(c).getString("city", "Tashkent") ?: "Tashkent"
    fun voiceName(c: Context): String = sp(c).getString("voice", "") ?: ""
    fun activeModel(c: Context): String = sp(c).getString("amodel", "uz-small") ?: "uz-small"
    fun repo(c: Context): String = sp(c).getString("repo", "") ?: ""            // GitHub: egasi/nomi
    fun lastUrl(c: Context): String = sp(c).getString("lasturl", "") ?: ""
    fun spend(c: Context): List<String> = (sp(c).getString("spend", "") ?: "").split("\n").filter { it.isNotBlank() }
    fun setSpend(c: Context, l: List<String>) = put(c, "spend", l.joinToString("\n"))
    fun getStr(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun vlock(c: Context): Boolean = sp(c).getBoolean("vlock", false)
    fun ttsEngine(c: Context): String = sp(c).getString("tts", "") ?: ""   // o'zingizning ovoz dvigateli paketi
    fun pitch(c: Context): Float = sp(c).getFloat("pitch", 0.8f)           // past = yo'g'on erkak ovozi
    fun rate(c: Context): Float = sp(c).getFloat("rate", 0.95f)
    fun notes(c: Context): List<String> = (sp(c).getString("notes", "") ?: "").split("\n").filter { it.isNotBlank() }
    fun setNotes(c: Context, l: List<String>) = put(c, "notes", l.joinToString("\n"))
    fun systemPrompt(c: Context): String = sp(c).getString("sys", null) ?: DEFAULT_SYSTEM

    const val DEFAULT_SYSTEM =
        "Sen Nova \u2014 o'zbek tilida gapiradigan ovozli yordamchisan (Iron Man'dagi JARVIS kabi, lekin do'stona). " +
        "Toshkent so'zlashuv shevasiga yaqin, ravon, samimiy va hurmatli ('siz') uslubda, o'zbek lotin yozuvida javob ber. " +
        "Javoblar qisqa bo'lsin: 1-3 gap. Javob ovozga aylantiriladi, shuning uchun emoji, markdown, ro'yxat va havola ishlatma. " +
        "Ruscha va inglizcha so'zlarni faqat zarur bo'lganda ishlat. Bilmasang, ochiq ayt."
}

// ---------------------------------------------------------------------
// 2. YORDAMCHILAR
// ---------------------------------------------------------------------
data class Hit(val title: String, val snippet: String, val url: String, val source: String)

object Bus {
    val lines = ArrayList<String>()
    var hits: List<Hit> = emptyList()
    var mode = OrbView.Mode.IDLE
    var onLine: ((String) -> Unit)? = null
    var onMode: ((OrbView.Mode) -> Unit)? = null
    var onHits: ((List<Hit>) -> Unit)? = null
    var enrolling = 0                                  // egasining ovozini o'rganish
    val enrollVecs = ArrayList<DoubleArray>()
    @Volatile var ownerOk = true                       // hozirgi gapiruvchi egasimi

    fun add(s: String) { synchronized(lines) { lines.add(s); if (lines.size > 200) lines.removeAt(0) }; onLine?.invoke(s) }
    fun setMode(m: OrbView.Mode) { mode = m; onMode?.invoke(m) }
    fun setHits(h: List<Hit>) { hits = h; onHits?.invoke(h) }
}

object Util {
    private val cyr = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "yo", 'ж' to "j",
        'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
        'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "x", 'ц' to "s",
        'ч' to "ch", 'ш' to "sh", 'ъ' to "", 'ы' to "i", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
        'ў' to "o", 'қ' to "q", 'ғ' to "g", 'ҳ' to "h"
    )

    /** Kirill -> lotin, kichik harf. keepApos=true bo'lsa o' va g' apostrofi saqlanadi. */
    fun latin(s: String, keepApos: Boolean = false): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.ROOT)) {
            when {
                ch in "'\u2019\u2018\u02BB\u02BC`" -> if (keepApos) sb.append('\'')
                keepApos && ch == 'ў' -> sb.append("o'")
                keepApos && ch == 'ғ' -> sb.append("g'")
                cyr.containsKey(ch) -> sb.append(cyr[ch])
                else -> sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("[,!?;:]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun has(t: String, vararg keys: String) = keys.any { Regex("(^|\\s)" + Regex.escape(it)).containsMatchIn(t) }

    /** Ikki so'z orasidagi farq (Levenshtein). Chaqiruv so'zini noaniq tanishda ham topish uchun. */
    fun lev(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    private val nums = mapOf(
        "nol" to 0, "bir" to 1, "ikki" to 2, "uch" to 3, "tort" to 4, "besh" to 5, "olti" to 6,
        "yetti" to 7, "sakkiz" to 8, "toqqiz" to 9, "on" to 10, "yigirma" to 20, "ottiz" to 30,
        "qirq" to 40, "ellik" to 50, "oltmish" to 60, "yetmish" to 70, "sakson" to 80, "toqson" to 90
    )

    /** "yigirma besh" -> "25", "yetti ottiz" -> "7 30". */
    fun words2digits(t: String): String {
        val out = ArrayList<String>()
        var total = 0; var cur = 0; var last = -1; var inNum = false
        fun flush() { if (inNum) { out.add((total + cur).toString()); total = 0; cur = 0; last = -1; inNum = false } }
        for (w in t.split(" ")) {
            val n = nums[w]
            when {
                n != null -> {
                    val cont = inNum && last >= 10 && last % 10 == 0 && n in 1..9
                    if (inNum && !cont && last != 100 && last != 1000) flush()
                    cur += n; last = n; inNum = true
                }
                w == "yuz" -> {
                    if (inNum && last in 1..9) cur = cur - last + last * 100 else cur += 100
                    last = 100; inNum = true
                }
                w == "ming" -> {
                    val prev = out.lastOrNull()
                    if (!inNum && prev != null && prev.isNotEmpty() && prev.all { it.isDigit() }) {
                        out[out.size - 1] = (prev.toLong() * 1000).toString()   // "20 ming" -> 20000
                    } else {
                        val m = if (inNum && cur > 0) cur else 1
                        total += m * 1000; cur = 0; last = 1000; inNum = true   // "yigirma ming" -> 20000
                    }
                }
                else -> { flush(); out.add(w) }
            }
        }
        flush()
        return out.joinToString(" ")
    }

    /** Papka ichida faqat bitta papka bo'lsa, uni yuqoriga ko'taradi. */
    fun flatten(dir: File) {
        val kids = dir.listFiles() ?: return
        if (kids.size == 1 && kids[0].isDirectory) {
            val tmp = File(dir.parentFile, dir.name + "_tmp"); tmp.deleteRecursively()
            kids[0].renameTo(tmp); dir.deleteRecursively(); tmp.renameTo(dir)
        }
    }

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun http(url: String, ua: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        if (ua != null) c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "uz,en;q=0.8")
        return c.inputStream.bufferedReader().readText()
    }

    fun speakable(s: String): String = s
        .replace(Regex("https?://\\S+"), "havola")
        .replace(Regex("[*#_`~>|]"), "")
        .replace(Regex("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}]"), "")
        .replace('\u2019', '\'').replace('\u2018', '\'').replace('\u02BB', '\'').replace('\u02BC', '\'')
        .trim()

    /** Vosk modelini zip'dan ochadi (yuqori papkali yoki papkasiz zip ham bo'ladi). */
    fun unzip(ins: InputStream, out: File) {
        out.deleteRecursively(); out.mkdirs()
        ZipInputStream(ins).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val f = File(out, e.name)
                if (!f.canonicalPath.startsWith(out.canonicalPath)) throw SecurityException("zip")
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                z.closeEntry(); e = z.nextEntry
            }
        }
        if (!File(out, "conf").exists()) {
            out.listFiles()?.firstOrNull { File(it, "conf").exists() }?.let { inner ->
                val tmp = File(out.parentFile, "model_tmp"); tmp.deleteRecursively()
                inner.renameTo(tmp); out.deleteRecursively(); tmp.renameTo(out)
            }
        }
    }
}

/** Ovoz modellari: bir nechta model saqlanadi, bittasi faol. */
object Models {
    fun root(c: Context) = File(c.filesDir, "models")
    fun dir(c: Context) = File(root(c), Prefs.activeModel(c))
    fun list(c: Context): List<String> {
        val old = File(c.filesDir, "model")   // eski versiyadan ko'chirish
        if (File(old, "conf").exists()) { root(c).mkdirs(); old.renameTo(File(root(c), "uz-small")) }
        return root(c).listFiles()?.filter { File(it, "conf").exists() }?.map { it.name }?.sorted() ?: emptyList()
    }
}

/** Natija kartochkasi (ilova ichida ham, orb ustida ham ishlatiladi). */
fun buildCard(ctx: Context, i: Int, h: Hit): LinearLayout {
    val d = ctx.resources.displayMetrics.density
    fun tv(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 3) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color); maxLines = lines
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
    return LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
        background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(0xE6061418.toInt()); setStroke((1 * d).toInt(), 0xFF2DD4BF.toInt()) }
        addView(tv("${i + 1}. ${h.title}", 15f, 0xFF2DD4BF.toInt(), true, 2))
        if (h.snippet.isNotBlank()) addView(tv(h.snippet.take(150), 12.5f, 0xFFE6FFFB.toInt()))
        addView(tv("${h.source} \u2022 ${Uri.parse(h.url).host ?: ""}", 11f, 0xFF7AA8A2.toInt(), false, 1))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (6 * d).toInt() }
        setOnClickListener {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(h.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        }
    }
}

// ---------------------------------------------------------------------
// 3. ORB (JARVIS uslubidagi yonib turuvchi firuza sfera)
// ---------------------------------------------------------------------
class OrbView(ctx: Context) : View(ctx) {
    enum class Mode { IDLE, LISTENING, THINKING, SPEAKING }

    var mode = Mode.IDLE
    private var t = 0f
    private val teal = 0xFF2DD4BF.toInt()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = teal }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal }
    private val path = Path()
    private val n = 260
    private val px = FloatArray(n); private val py = FloatArray(n); private val pz = FloatArray(n)

    init {
        val ga = PI.toFloat() * (3f - sqrt(5f))
        for (i in 0 until n) {
            val y = 1f - 2f * (i + 0.5f) / n
            val r = sqrt(1f - y * y)
            px[i] = cos(ga * i) * r; py[i] = y; pz[i] = sin(ga * i) * r
        }
    }

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            t += when (mode) { Mode.THINKING -> 0.05f; Mode.LISTENING -> 0.03f; Mode.SPEAKING -> 0.035f; else -> 0.012f }
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    private fun amp(): Float = when (mode) {
        Mode.IDLE -> 0.015f
        Mode.LISTENING -> 0.05f + 0.025f * sin(t * 3f)
        Mode.THINKING -> 0.03f + 0.02f * sin(t * 6f)
        Mode.SPEAKING -> 0.04f + 0.06f * abs(sin(t * 4.3f)) * abs(sin(t * 1.7f) + 0.3f)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val s = min(width, height).toFloat()
        val a = amp()
        val fade = if (mode == Mode.IDLE) 0.6f else 1f
        val twoPi = 2f * PI.toFloat()

        for (k in 0..2) {
            val r0 = s * (0.46f - k * 0.075f)
            val dir = if (k % 2 == 0) 1f else -1f
            path.reset()
            val steps = 120
            for (i in 0..steps) {
                val th = i * twoPi / steps
                val r = r0 * (1f + a * sin(th * (5 + k * 2) + t * dir * (1.2f + k * 0.4f)) + a * 0.5f * sin(th * 3f - t * 0.8f))
                val x = cx + r * cos(th); val y = cy + r * sin(th)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            ring.strokeWidth = s * 0.022f; ring.alpha = (45 * fade).toInt(); canvas.drawPath(path, ring)
            ring.strokeWidth = s * 0.006f; ring.alpha = (230 * fade).toInt(); canvas.drawPath(path, ring)
        }

        val big = s * 0.17f * (1f + 0.05f * sin(t * 2f)) * (1f + a)
        val ang = t * 0.6f
        val ca = cos(ang); val sa = sin(ang)
        for (i in 0 until n) {
            val x = px[i] * ca + pz[i] * sa
            val z = -px[i] * sa + pz[i] * ca
            dot.alpha = ((40f + 200f * (z + 1f) / 2f) * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx + x * big, cy + py[i] * big, s * 0.004f * (1.3f + z), dot)
        }
    }
}

// ---------------------------------------------------------------------
// 4. OFLAYN: suhbat, hazil, kalkulyator
// ---------------------------------------------------------------------
object Offline {
    private val jokes = listOf(
        "Afandi eshagini yo'qotib qo'ydi-yu, shukr qildi. Sababini so'rashsa: eshakning ustida o'tirmagan edim, bo'lmasam men ham yo'qolardim, dedi.",
        "O'quvchi: ustoz, hech narsa qilmagan odamni jazolasa bo'ladimi? Ustoz: yo'q. O'quvchi: unda men uy vazifasini qilmadim.",
        "Afandi bozorga borib, bir kilo yolg'on so'radi. Sotuvchi: yolg'on og'irlikda sotilmaydi. Afandi: unda nega siz uni hamisha tarozida sotasiz?"
    )

    fun smallTalk(raw: String): String? {
        val t = Util.words2digits(Util.latin(raw))
        return when {
            Util.has(t, "assalom", "salom") -> listOf("Vaalaykum assalom! Sizga qanday yordam bera olaman?", "Salom! Eshitaman sizni.").random()
            Util.has(t, "qalaysan", "qalaysiz", "ahvol", "yaxshimisiz") -> "Yaxshiman, rahmat! O'zingiz qalaysiz?"
            Util.has(t, "isming nima", "sen kim", "kimsan", "ozingni tanishtir") ->
                "Men Nova, sizning ovozli yordamchingizman. Telefonni boshqaraman va savollarga javob beraman."
            Util.has(t, "rahmat", "raxmat", "tashakkur") -> listOf("Arzimaydi!", "Doim tayyorman.", "Marhamat!").random()
            Util.has(t, "xayr", "korishguncha") -> "Xayr! Kerak bo'lsam chaqiring."
            Util.has(t, "latifa", "hazil", "kuldir") -> jokes.random()
            Util.has(t, "tanga tashla") -> if (Random.nextBoolean()) "Gerb tushdi." else "Raqam tushdi."
            Util.has(t, "zar tashla") -> "Tushgan son: ${(1..6).random()}."
            Util.has(t, "tasodifiy son") -> "Tasodifiy son: ${(1..100).random()}."
            else -> calc(t)
        }
    }

    private fun calc(t: String): String? {
        if (!(t.contains("hisobla") || t.contains("natija") || t.contains("necha boladi"))) return null
        var e = t
            .replace(Regex("\\b(qoshuv|qosh\\w*|plyus|plus)\\b"), "+")
            .replace(Regex("\\b(ayir\\w*|minus)\\b"), "-")
            .replace(Regex("\\b(kopaytir\\w*|marta|x)\\b"), "*")
            .replace(Regex("\\b(bolish|bol\\w*)\\b"), "/")
        e = e.filter { it.isDigit() || it in ".+-*/()" }
        if (e.none { it.isDigit() }) return null
        return try {
            val v = P(e).expr()
            if (v.isNaN() || v.isInfinite()) "Bunday hisoblab bo'lmaydi."
            else "Natija: " + if (v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else "%.4f".format(v).trimEnd('0').trimEnd('.')
        } catch (ex: Exception) { null }
    }

    private class P(val s: String) {
        var i = 0
        fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val o = s[i++]; val r = term(); v = if (o == '+') v + r else v - r }
            return v
        }
        fun term(): Double {
            var v = fac()
            while (i < s.length && (s[i] == '*' || s[i] == '/')) { val o = s[i++]; val r = fac(); v = if (o == '*') v * r else v / r }
            return v
        }
        fun fac(): Double {
            if (s[i] == '-') { i++; return -fac() }
            if (s[i] == '(') { i++; val v = expr(); i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }
}

// ---------------------------------------------------------------------
// 5. AI (OpenRouter)
// ---------------------------------------------------------------------
object AiClient {
    private val history = ArrayList<Pair<String, String>>()

    /** Fon oqimida chaqiring. remember=false: suhbat tarixiga yozilmaydi (tarjima, ekran tahlili). */
    fun ask(ctx: Context, text: String, remember: Boolean = true): String {
        val key = Prefs.apiKey(ctx)
        if (key.isBlank()) return "OpenRouter kaliti kiritilmagan. Menyu, Sozlamalar bo'limidan kiriting."
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", Prefs.systemPrompt(ctx)))
        if (remember) {
            history.add("user" to text)
            history.takeLast(12).forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) }
        } else msgs.put(JSONObject().put("role", "user").put("content", text))

        val body = JSONObject().put("model", Prefs.model(ctx)).put("max_tokens", 400).put("messages", msgs)
        val c = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("X-Title", "Nova")
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = c.responseCode
        val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) {
            if (remember) history.removeAt(history.size - 1)
            return "AI xatosi ($code). Kalit yoki model nomini tekshiring."
        }
        val reply = JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        if (remember) history.add("assistant" to reply)
        return reply
    }
}

// ---------------------------------------------------------------------
// 6. QIDIRUV: DuckDuckGo + Vikipediya + Bing, eng yaxshi 3 ta natija
// ---------------------------------------------------------------------
object Search {
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ").trim()

    fun ddg(q: String): List<Hit> {
        val html = Util.http("https://html.duckduckgo.com/html/?q=${enc(q)}", UA)
        val links = Regex("<a\\s[^>]*class=\"result__a\"[^>]*>.*?</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snips = Regex("<a\\s[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { clean(it.groupValues[1]) }.toList()
        return links.mapIndexedNotNull { i, m ->
            var href = Regex("href=\"([^\"]+)\"").find(m.value.substringBefore(">"))?.groupValues?.get(1)?.replace("&amp;", "&")
                ?: return@mapIndexedNotNull null
            Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            if (href.contains("duckduckgo.com/y.js")) null else Hit(clean(m.value), snips.getOrElse(i) { "" }, href, "DuckDuckGo")
        }.take(4)
    }

    fun wiki(q: String, lang: String = "uz"): List<Hit> {
        val j = JSONObject(Util.http("https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(q)}&srlimit=3&format=json&utf8=1", UA))
        val arr = j.getJSONObject("query").getJSONArray("search")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); val title = o.getString("title")
            Hit(title, clean(o.getString("snippet")), "https://$lang.wikipedia.org/wiki/" + enc(title.replace(' ', '_')), "Vikipediya")
        }
    }

    fun bing(q: String): List<Hit> {
        val html = Util.http("https://www.bing.com/search?q=${enc(q)}&setlang=uz", UA)
        return Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { m ->
            val b = m.value
            val a = Regex("<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b) ?: return@mapNotNull null
            var url = a.groupValues[1].replace("&amp;", "&")
            if (url.contains("bing.com/ck/a")) {
                Regex("[?&]u=([^&]+)").find(url)?.let { u ->
                    try { url = String(Base64.decode(u.groupValues[1].removePrefix("a1"), Base64.URL_SAFE or Base64.NO_PADDING)) } catch (_: Exception) {}
                }
            }
            val sn = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)?.let { clean(it) } ?: ""
            Hit(clean(a.groupValues[2]), sn, url, "Bing")
        }.take(4).toList()
    }

    /** Uchala manbadan parallel qidiradi, navbatma-navbat eng yaxshi 3 tasini tanlaydi. */
    fun all(q: String): List<Hit> {
        val jobs = listOf<() -> List<Hit>>({ ddg(q) }, { wiki(q).ifEmpty { wiki(q, "en") } }, { bing(q) })
        val ex = Executors.newFixedThreadPool(3)
        val res = try {
            ex.invokeAll(jobs.map { j -> Callable { try { j() } catch (_: Exception) { emptyList<Hit>() } } }).map { it.get() }
        } finally { ex.shutdown() }
        val out = ArrayList<Hit>(); var i = 0
        while (out.size < 3 && res.any { i < it.size }) {
            for (r in res) if (out.size < 3 && i < r.size && out.none { it.url == r[i].url }) out.add(r[i])
            i++
        }
        return out
    }
}

// ---------------------------------------------------------------------
// 7. OVOZ TANISH: o'z dvigatelingiz (Google ishlatilmaydi)
// ---------------------------------------------------------------------
/** Istalgan ovoz tanish dvigatelini shu interfeys orqali ulang. */
interface SpeechEngine {
    fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit)
    fun pause(p: Boolean)
    fun stop()
}

/** Standart dvigatel: oflayn Vosk. O'z modelingizni (Kaldi/Vosk) papkaga qo'ysangiz, shu ishlaydi. */
class VoskEngine(private val dir: File) : SpeechEngine {
    private var model: Model? = null
    private var svc: SpeechService? = null
    private var spk: SpeakerModel? = null
    @Volatile var lastSpk: DoubleArray? = null

    fun load() { if (model == null) model = Model(dir.path) }
    fun loadSpeaker(d: File) { try { if (File(d, "mfcc.conf").exists()) spk = SpeakerModel(d.path) } catch (_: Exception) {} }

    override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        val rec = Recognizer(model, 16000f)
        spk?.let { rec.setSpeakerModel(it) }
        val s = SpeechService(rec, 16000f)
        s.startListening(object : RecognitionListener {
            override fun onPartialResult(h: String?) { h?.let { onPartial(JSONObject(it).optString("partial")) } }
            override fun onResult(h: String?) {
                h?.let {
                    val j = JSONObject(it)
                    lastSpk = j.optJSONArray("spk")?.let { a -> DoubleArray(a.length()) { i -> a.getDouble(i) } }
                    onFinal(j.optString("text"))
                }
            }
            override fun onFinalResult(h: String?) {}
            override fun onError(e: Exception?) { onError(e?.message ?: "noma'lum") }
            override fun onTimeout() {}
        })
        svc = s
    }

    override fun pause(p: Boolean) { svc?.setPause(p) }
    override fun stop() { try { svc?.stop(); svc?.shutdown() } catch (_: Exception) {}; svc = null }
    fun release() { stop(); try { model?.close() } catch (_: Exception) {}; try { spk?.close() } catch (_: Exception) {}; model = null; spk = null }
}

// ---------------------------------------------------------------------
// 8. DIAGNOSTIKA
// ---------------------------------------------------------------------
object Diagnostics {
    fun battery(ctx: Context): String {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "Batareya ma'lumoti yo'q."
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "yaxshi"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "qizib ketgan"
            BatteryManager.BATTERY_HEALTH_DEAD -> "yaroqsiz"
            BatteryManager.BATTERY_HEALTH_COLD -> "sovuq"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "kuchlanish yuqori"
            else -> "noma'lum"
        }
        return "Batareya ${level * 100 / scale}%${if (charging) ", zaryadlanmoqda" else ""}. Harorati $temp daraja, holati $health."
    }

    fun memory(ctx: Context): String {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return "RAM: ${mi.totalMem / 1_048_576} MB dan ${mi.availMem / 1_048_576} MB bo'sh."
    }

    fun storage(): String {
        val st = StatFs(Environment.getDataDirectory().path)
        return "Xotira: %.1f GB dan %.1f GB bo'sh.".format(st.totalBytes / 1_073_741_824.0, st.availableBytes / 1_073_741_824.0)
    }

    fun network(ctx: Context): String {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return when {
            caps == null -> "Internet yo'q."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi ulangan."
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobil internet ulangan."
            else -> "Internet ulangan."
        }
    }

    fun uptime(): String {
        val m = SystemClock.elapsedRealtime() / 60_000
        return "Telefon ${m / 60} soat ${m % 60} daqiqadan beri qayta yoqilmagan."
    }

    fun device() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}."

    fun report(ctx: Context) = listOf(device(), battery(ctx), memory(ctx), storage(), network(ctx), uptime()).joinToString(" ")
}

// ---------------------------------------------------------------------
// 9. BUYRUQLAR (yangi buyruq: tryHandle ichiga yangi shart qo'shing)
// ---------------------------------------------------------------------
class Commands(
    private val ctx: Context,
    private val say: (String) -> Unit,
    private val results: (String, List<Hit>) -> Unit
) {
    private val svc get() = NovaService.instance
    val extras = Extras(ctx, say, results)

    /** Nozik buyruqlar faqat egasining ovozi bilan (ovoz qulfi yoqilgan bo'lsa). */
    private fun owner(): Boolean {
        if (Bus.ownerOk) return true
        say("Bu buyruq faqat egasining ovozi bilan bajariladi.")
        return false
    }

    private val cities = mapOf(
        "toshkent" to "Tashkent", "samarqand" to "Samarkand", "buxoro" to "Bukhara", "andijon" to "Andijan",
        "namangan" to "Namangan", "fargona" to "Fergana", "nukus" to "Nukus", "xiva" to "Khiva",
        "qarshi" to "Karshi", "termiz" to "Termez", "jizzax" to "Jizzakh", "navoiy" to "Navoi", "urganch" to "Urgench"
    )
    private val unitF = mapOf("kilometr" to 1000.0, "km" to 1000.0, "metr" to 1.0, "mil" to 1609.344, "fut" to 0.3048,
        "kilogramm" to 1.0, "kg" to 1.0, "funt" to 0.45359237)

    fun tryHandle(raw: String): Boolean {
        val t = Util.words2digits(Util.latin(raw))
        val tq = Util.words2digits(Util.latin(raw, true))   // apostroflari saqlangan (qidiruv, tarjima uchun)
        fun has(vararg k: String) = Util.has(t, *k)

        return when {
            // ----- Xavfsizlik, kuzatuv, salomatlik (Extras) -----
            has("toliq diagnostika", "chuqur diagnostika") -> extras.fullReport()
            has("xavfsizlik tekshiruv", "xavfsizlik audit", "xavfsizligimni tekshir") -> extras.audit()
            has("ruxsat tekshiruv", "josuslik") -> extras.permAudit()
            has("blokni och", "telefonni och", "qulfni och") -> if (owner()) extras.unlock() else true
            has("telefonni uygot", "ekranni yoq") -> { extras.wake(); say("Ekran yoqildi.").ok() }
            has("telefon qachon", "necha marta ochil", "ochilish tarixi") -> extras.unlocks()
            has("ilovalar statistika", "qaysi ilovalar", "ekran vaqti", "ishlatilgan ilova") -> extras.usage()
            has("wifi qurilmalar", "tarmoqdagi qurilmalar", "wifiga kim", "kim wifi") -> extras.lan(false)
            has("qurilmalarga ishonaman", "qurilmalarni ishonchli") -> extras.lan(true)
            has("kameralarni qidir", "kamera qidir") -> extras.findCams()
            has("kamerani och", "kamerani korsat") -> extras.openCam(t.replace(Regex("^.*?kamerani\\s+(och|korsat)\\s*"), ""))
            has("router", "modem") -> extras.router()
            has("qoriqlash") -> if (has("toxtat", "ochir", "yopish")) { if (owner()) extras.guardStop() else true } else extras.guardStart(has("old"))
            has("puls", "yurak urishi") -> extras.pulse()
            has("qadam") -> extras.steps()
            has("bmi", "tana vazn") -> extras.bmi(t)
            has("suv ichish") -> extras.water()
            has("sos", "yordam kerak", "favqulodda") -> extras.sos()
            has("joylashuvim", "qayerdaman") -> extras.where()
            has("kundalik") -> if (owner()) extras.siteShot("Kundalik", "https://kundalik.com") else true
            t.startsWith("sayt skrinshot ") -> if (owner()) t.removePrefix("sayt skrinshot ").trim().let { extras.siteShot(it, "https://$it") } else true
            has("ovozimni organ") -> extras.enroll()
            has("ovoz qulfini yoq") -> extras.vlock(true)
            has("ovoz qulfini ochir", "ovoz qulfini toxtat") -> extras.vlock(false)

            // ----- Yangi funksiyalar -----
            has("yangilik") -> news()
            has("haqida malumot", "kim bu", "nima bu") -> about(tq)
            has("xarajat") -> expense(t)
            has("pomodoro") -> go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, 25 * 60)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Pomodoro").putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Pomodoro boshlandi: 25 daqiqa.")
            has("yol korsat", "yonalish") -> navigate(t.replace(Regex("\\b(yol korsat\\w*|yonalish\\w*|ga|ni)\\b"), " ").replace(Regex("\\s+"), " ").trim())
            has("kalendarga") -> go(Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, t.substringAfter("kalendarga").replace(Regex("^\\s*(qosh\\w*|yoz\\w*)"), "").trim()), "Kalendar ochildi.")
            has("tejash rejimi") -> go(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), "Tejash rejimi sozlamalari.")
            has("xotirani tozala", "joy bosh") -> go(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), "Xotira sozlamalari.")
            has("ilovalar sozlamasi", "ilovalar sozlamalari") -> go(Intent(Settings.ACTION_APPLICATION_SETTINGS), "Ilovalar sozlamalari.")
            has("modelni yangila") -> { act(AssistantService.ACTION_UPDATE); say("GitHub'dan model tekshirilmoqda.").ok() }
            has("modellar") -> say(Models.list(ctx).let { if (it.isEmpty()) "Model yo'q." else "Modellar: " + it.joinToString(", ") + ". Faol: ${Prefs.activeModel(ctx)}." }).ok()

            // ----- Diagnostika -----
            has("diagnostika", "telefon holati") -> say(Diagnostics.report(ctx)).ok()
            has("batareya", "zaryad") -> say(Diagnostics.battery(ctx)).ok()
            has("xotira", "joy qoldi") -> say(Diagnostics.storage()).ok()
            has("ram", "operativ") -> say(Diagnostics.memory(ctx)).ok()
            has("internet", "tarmoq", "wifi holati") -> say(Diagnostics.network(ctx)).ok()
            has("ilovalar soni") -> say("Telefonda ${launcherApps().size} ta ilova bor.").ok()

            // ----- Ma'lumot -----
            has("soat nech", "vaqt nech", "hozir soat") -> say("Soat " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())).ok()
            has("bugun qaysi", "sana", "bugun nech") -> say(SimpleDateFormat("d MMMM yyyy, EEEE", Locale("uz")).format(Date())).ok()
            has("ob-havo", "obhavo", "ob havo") -> weather(t.substringAfter("havo").trim())
            has("namoz") -> prayer(t.substringAfter("namoz").replace("vaqti", "").replace("vaqt", "").trim())
            has("kurs") -> rate(t)

            // ----- Tizim tugmalari -----
            has("orqaga") -> service { it.back() }
            has("bosh ekran") || t == "uy" -> service { it.home() }
            has("oxirgi ilova") -> service { it.recents() }
            has("bildirishnoma") -> service { it.notifications() }
            has("tez sozlamalar") -> service { it.quickSettings() }
            has("skrinshot", "ekran rasmi") -> service { it.screenshot() }
            has("qulfla") -> service { it.lockScreen() }
            has("quvvat menyusi", "ochirish menyusi") -> service { it.powerMenu() }
            has("pastga") -> service { it.scroll(true) }
            has("yuqoriga") -> service { it.scroll(false) }
            has("ekranda nima", "ekranni tushuntir") -> explainScreen()
            has("ekranni oqi") -> {
                val s = svc
                if (s == null) needService() else say(s.readScreen().ifBlank { "Ekranda matn topilmadi." })
                true
            }
            t.startsWith("bos ") -> { val q = t.removePrefix("bos ").trim(); service { it.clickText(q) } }
            t.startsWith("yoz ") -> { val q = raw.trim().substringAfter(" "); service { it.typeText(q) } }

            // ----- Nova sozlamalari -----
            has("jim bol", "gapirma") -> { act(AssistantService.ACTION_SHUTUP); true }
            has("yordamchini ochir", "tinglashni toxtat") -> { say("Xayr!"); act(AssistantService.ACTION_STOP); true }
            has("diktor") -> { Prefs.put(ctx, "pitch", 0.7f); Prefs.put(ctx, "rate", 0.9f); say("Diktor ovozi yoqildi.").ok() }
            has("tezroq gapir") -> tweak("rate", Prefs.rate(ctx) + 0.15f, 0.5f, 1.8f, "Tezroq gapiraman.")
            has("sekinroq gapir") -> tweak("rate", Prefs.rate(ctx) - 0.15f, 0.5f, 1.8f, "Sekinroq gapiraman.")
            has("yogonroq", "erkakroq") -> tweak("pitch", Prefs.pitch(ctx) - 0.1f, 0.4f, 1.5f, "Ovozim yo'g'onlashdi.")
            has("ingichkaroq") -> tweak("pitch", Prefs.pitch(ctx) + 0.1f, 0.4f, 1.5f, "Ovozim ingichkalashdi.")

            // ----- Eslatma va rejalar -----
            t.startsWith("eslatib") -> remind(t)
            has("eslatma") -> notes(t)

            // ----- Musiqa va ovoz -----
            has("keyingi qoshiq", "keyingi musiqa") -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            has("oldingi qoshiq", "oldingi musiqa") -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            has("musiqani toxtat", "pauza", "davom ettir", "qoshiqni toxtat") -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            has("vibratsiya") -> ringer(AudioManager.RINGER_MODE_VIBRATE, "Vibratsiya rejimi.")
            has("oddiy rejim", "ovozli rejim") -> ringer(AudioManager.RINGER_MODE_NORMAL, "Oddiy rejim.")
            has("ovozni oshir", "ovoz balandroq", "kuchaytir") -> volume(AudioManager.ADJUST_RAISE)
            has("ovozni pasaytir", "ovoz pastroq") -> volume(AudioManager.ADJUST_LOWER)
            has("ovozsiz", "jim rejim") -> volume(AudioManager.ADJUST_MUTE)

            // ----- Qurilma -----
            has("chiroq", "fonar") -> torch(!has("ochir", "uchir"))
            has("yorqinlik") -> go(Intent(Settings.ACTION_DISPLAY_SETTINGS), "Ekran sozlamalari.")
            has("wifi", "vay fay") -> go(Intent(Settings.ACTION_WIFI_SETTINGS), "Wi-Fi sozlamalari.")
            has("bluetooth", "blyutuz") -> go(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "Bluetooth sozlamalari.")
            has("samolyot") -> go(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "Samolyot rejimi.")
            has("sozlamalar") -> go(Intent(Settings.ACTION_SETTINGS), "Sozlamalar ochildi.")
            has("kamera", "rasmga ol") -> go(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "Kamera ochildi.")
            has("musiqa") -> go(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC), "Musiqa ilovasi.")

            // ----- Aloqa -----
            has("kontakt") -> contact(t.substringAfter("kontakt").replace(Regex("\\b(qidir\\w*|izla\\w*|raqami|nomeri)\\b"), "").trim())
            t.startsWith("qongiroq") || t.startsWith("call ") -> call(t.substringAfter(" ").removePrefix("qil").trim())
            t.startsWith("sms ") -> sms(t.removePrefix("sms "))

            // ----- Budilnik, taymer -----
            t.startsWith("budilnik") -> alarm(t)
            t.startsWith("taymer") -> timer(t)

            // ----- Qidiruv (DuckDuckGo, Vikipediya, Bing; Google/Yandex/YouTube brauzerda) -----
            Bus.hits.isNotEmpty() && has("natija", "birinchi", "ikkinchi", "uchinchi") && has("och", "oqi", "ayt") -> openHit(t)
            has("qidir", "izla") -> search(t, tq)

            // ----- Tarjima, birliklar -----
            has("tarjima") -> translate(tq)
            Regex("\\d+(\\.\\d+)?\\s*[a-z]+\\s+(necha|qancha)\\s+[a-z]+").containsMatchIn(t) -> convert(t)

            // ----- Brauzer / xarita / ilova -----
            t.startsWith("youtube ") -> go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(t.removePrefix("youtube ")))), "YouTube'da qidirilmoqda.")
            t.startsWith("google ") -> go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(tq.removePrefix("google ")))), "Google'da qidirilmoqda.")
            t.startsWith("xarita ") -> go(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(t.removePrefix("xarita ")))), "Xaritada ochilmoqda.")
            t.startsWith("och ") || t.startsWith("ochish ") -> openApp(t.substringAfter(" ").trim())

            else -> false   // AI'ga yuboriladi
        }
    }

    // ---------- yordamchi ----------
    private fun Unit.ok() = true
    private fun needService() = say("Avval Maxsus imkoniyatlar bo'limida Nova xizmatini yoqing.")
    private fun act(a: String) { ctx.startService(Intent(ctx, AssistantService::class.java).setAction(a)) }

    private fun service(block: (NovaService) -> Any?): Boolean {
        val s = svc
        if (s == null) needService() else say(if (block(s) == false) "Bajarib bo'lmadi." else "Bajarildi.")
        return true
    }

    private fun go(i: Intent, msg: String): Boolean {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); say(msg) } catch (e: Exception) { say("Bajarib bo'lmadi.") }
        return true
    }

    private fun tweak(key: String, v: Float, lo: Float, hi: Float, msg: String): Boolean {
        Prefs.put(ctx, key, v.coerceIn(lo, hi)); say(msg); return true
    }

    private fun launcherApps() = ctx.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)

    private fun openApp(name: String): Boolean {
        val pm = ctx.packageManager
        val match = launcherApps().firstOrNull { Util.latin(it.loadLabel(pm).toString()).contains(name) }
        if (match == null) { say("$name ilovasi topilmadi."); return true }
        val i = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        return if (i != null) go(i, "$name ochilmoqda.") else true
    }

    private fun torch(on: Boolean): Boolean {
        try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            cm.setTorchMode(id, on)
            say(if (on) "Chiroq yoqildi." else "Chiroq o'chirildi.")
        } catch (e: Exception) { say("Chiroqni boshqarib bo'lmadi.") }
        return true
    }

    private fun volume(dir: Int): Boolean {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        say("Tayyor."); return true
    }

    private fun media(code: Int): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        say("Tayyor."); return true
    }

    private fun ringer(mode: Int, msg: String): Boolean {
        try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = mode; say(msg) }
        catch (e: Exception) { say("Bu rejimni o'rnatib bo'lmadi.") }
        return true
    }

    private fun findNumber(name: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val p = ContactsContract.CommonDataKinds.Phone
        ctx.contentResolver.query(p.CONTENT_URI, arrayOf(p.NUMBER), "${p.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null)
            ?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun contact(name: String): Boolean {
        val n = findNumber(name)
        say(if (n == null) "$name topilmadi." else "$name: ${n.filter { it.isDigit() }.toList().joinToString(" ")}")
        return true
    }

    private fun call(who: String): Boolean {
        val number = if (who.any { it.isDigit() }) who.filter { it.isDigit() || it == '+' } else findNumber(who)
        if (number == null) { say("$who topilmadi."); return true }
        return go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), "$who ga qo'ng'iroq tayyor, tugmani bosing.")
    }

    private fun sms(rest: String): Boolean {
        val p = rest.trim().split(" ", limit = 2)
        val number = if (p[0].any { it.isDigit() }) p[0] else findNumber(p[0])
        if (number == null) { say("${p[0]} topilmadi."); return true }
        return go(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", p.getOrElse(1) { "" }), "SMS tayyor, yuborish tugmasini bosing.")
    }

    private fun nums(t: String) = Regex("\\d+").findAll(t).map { it.value.toInt() }.toList()

    private fun alarm(t: String): Boolean {
        val n = nums(t)
        if (n.isEmpty()) { say("Vaqtni ayting, masalan: budilnik 7 30."); return true }
        val m = n.getOrElse(1) { 0 }
        return go(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, n[0]).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Budilnik ${n[0]}:${"%02d".format(m)} ga qo'yildi.")
    }

    private fun timer(t: String): Boolean {
        val n = nums(t).firstOrNull()
        if (n == null) { say("Necha daqiqa? Masalan: taymer 5 daqiqa."); return true }
        return go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, n * 60).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "Taymer $n daqiqaga qo'yildi.")
    }

    /** "eslatib qo'y 10 daqiqadan keyin sut olish" */
    private fun remind(t: String): Boolean {
        val n = nums(t).firstOrNull()
        if (n == null) { say("Necha daqiqadan keyin? Masalan: eslatib qo'y 10 daqiqadan keyin sut olish."); return true }
        val msg = t.replace(Regex("^.*?daqiqa\\w*\\s*(keyin)?\\s*"), "").ifBlank { "Eslatma" }
        return go(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, n * 60)
            .putExtra(AlarmClock.EXTRA_MESSAGE, msg).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "$n daqiqadan keyin eslataman.")
    }

    private fun notes(t: String): Boolean {
        val list = Prefs.notes(ctx).toMutableList()
        when {
            Util.has(t, "eslatmalarni ochir", "eslatmalarni tozala", "eslatma ochir") -> { Prefs.setNotes(ctx, emptyList()); say("Eslatmalar o'chirildi.") }
            t.endsWith("eslatmalar") || Util.has(t, "eslatmalarni oqi", "eslatmalarni ayt", "eslatma oqi") ->
                say(if (list.isEmpty()) "Eslatmalar yo'q." else list.mapIndexed { i, s -> "${i + 1}: $s" }.joinToString(". "))
            else -> {
                val body = t.replace(Regex("^.*?eslatma\\w*\\s*(yoz|qosh|saqla)?\\s*"), "").trim()
                if (body.isBlank()) say("Nima deb yozay? Masalan: eslatma yoz sut olish.")
                else { list.add(body); Prefs.setNotes(ctx, list); say("Eslatma saqlandi.") }
            }
        }
        return true
    }

    private fun cityFor(text: String): Pair<String, String> {
        val key = cities.keys.firstOrNull { text.startsWith(it) }
        return if (key != null) key.replaceFirstChar { it.uppercase() } to cities[key]!! else Prefs.city(ctx).let { it to it }
    }

    private fun weather(city: String): Boolean {
        if (!Util.online(ctx)) { say("Ob-havo uchun internet kerak."); return true }
        val (shown, q) = cityFor(city)
        try {
            val r = Util.http("https://wttr.in/${Uri.encode(q)}?format=%25t%7C%25h%7C%25w").trim().split("|")
            say("$shown: harorat ${r[0].replace("+", "").replace("\u00B0C", " daraja")}, namlik ${r.getOrElse(1) { "?" }}, " +
                "shamol ${r.getOrElse(2) { "?" }.replace("\u2192", "").replace("\u2190", "").trim()}.")
        } catch (e: Exception) { say("Ob-havoni olib bo'lmadi.") }
        return true
    }

    private fun prayer(city: String): Boolean {
        if (!Util.online(ctx)) { say("Namoz vaqti uchun internet kerak."); return true }
        val (shown, q) = cityFor(city)
        try {
            val tm = JSONObject(Util.http("https://api.aladhan.com/v1/timingsByCity?city=${Uri.encode(q)}&country=Uzbekistan&method=2"))
                .getJSONObject("data").getJSONObject("timings")
            say("$shown: bomdod ${tm.getString("Fajr")}, peshin ${tm.getString("Dhuhr")}, asr ${tm.getString("Asr")}, " +
                "shom ${tm.getString("Maghrib")}, xufton ${tm.getString("Isha")}.")
        } catch (e: Exception) { say("Namoz vaqtini olib bo'lmadi.") }
        return true
    }

    /** Valyuta kursi (taxminiy bozor kursi, so'mda). */
    private fun rate(t: String): Boolean {
        if (!Util.online(ctx)) { say("Kurs uchun internet kerak."); return true }
        val (code, name) = when {
            t.contains("yevro") || t.contains("evro") || t.contains("eur") -> "EUR" to "yevro"
            t.contains("rubl") -> "RUB" to "rubl"
            t.contains("tenge") -> "KZT" to "tenge"
            t.contains("yuan") -> "CNY" to "yuan"
            else -> "USD" to "dollar"
        }
        try {
            val uzs = JSONObject(Util.http("https://open.er-api.com/v6/latest/$code")).getJSONObject("rates").getDouble("UZS")
            say("Taxminan 1 $name = ${"%,.0f".format(uzs)} so'm. Bank kursi biroz farq qilishi mumkin.")
        } catch (e: Exception) { say("Kursni olib bo'lmadi.") }
        return true
    }

    /** "5 km necha mil", "10 kg necha funt" */
    private fun convert(t: String): Boolean {
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*([a-z]+)\\s+(?:necha|qancha)\\s+([a-z]+)").find(t) ?: return false
        fun f(s: String) = unitF.entries.sortedByDescending { it.key.length }.firstOrNull { s.startsWith(it.key) }
        val a = f(m.groupValues[2]); val b = f(m.groupValues[3])
        if (a == null || b == null) return false
        val v = m.groupValues[1].toDouble() * a.value / b.value
        say("${m.groupValues[1]} ${m.groupValues[2]} taxminan " + "%.3f".format(v).trimEnd('0').trimEnd('.') + " ${m.groupValues[3]}.")
        return true
    }

    private fun translate(tq: String): Boolean {
        if (!Util.online(ctx)) { say("Tarjima uchun internet kerak."); return true }
        val lang = when { tq.contains("ingliz") -> "ingliz"; tq.contains("rus") -> "rus"; tq.contains("turk") -> "turk"
            tq.contains("arab") -> "arab"; tq.contains("ozbek") || tq.contains("o'zbek") -> "o'zbek"; else -> "ingliz" }
        val text = tq.replace(Regex("^.*?tarjima\\w*\\s*(qil\\w*|ayt\\w*)?\\s*"), "")
            .replace(Regex("\\b(ingliz|rus|turk|arab|ozbek|o'zbek)\\w*\\b"), "").replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) { say("Nimani tarjima qilay? Masalan: tarjima qil salom dunyo inglizchaga."); return true }
        say(AiClient.ask(ctx, "Quyidagi matnni $lang tiliga tarjima qil. Faqat tarjimani yoz: $text", false))
        return true
    }

    private fun explainScreen(): Boolean {
        val s = svc
        if (s == null) { needService(); return true }
        val txt = s.readScreen()
        if (txt.isBlank()) { say("Ekranda matn topilmadi."); return true }
        say(if (Util.online(ctx)) AiClient.ask(ctx, "Telefon ekranidagi matnlar: $txt. Ekranda nima borligini 1-2 gapda tushuntir.", false) else txt.take(300))
        return true
    }

    // ---------- yangi funksiyalar ----------
    /** Yangiliklar: 3 ta sarlavha kartochka bo'lib chiqadi va o'qib beriladi. */
    private fun news(): Boolean {
        if (!Util.online(ctx)) { say("Yangiliklar uchun internet kerak."); return true }
        try {
            val xml = Util.http("https://news.google.com/rss?hl=uz&gl=UZ&ceid=UZ:uz", "Mozilla/5.0")
            val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(3).map { m ->
                val b = m.groupValues[1]
                fun tag(n: String) = Regex("<$n>(.*?)</$n>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1)
                    ?.replace("<![CDATA[", "")?.replace("]]>", "")?.replace(Regex("<[^>]+>"), "")?.replace("&amp;", "&")?.trim() ?: ""
                Hit(tag("title"), "", tag("link"), "Yangiliklar")
            }.filter { it.url.isNotBlank() }.toList()
            if (items.isEmpty()) { say("Yangilik topilmadi."); return true }
            results("yangiliklar", items)
            say("Asosiy yangiliklar. " + items.mapIndexed { i, h -> "${i + 1}: ${h.title}" }.joinToString(". "))
        } catch (e: Exception) { say("Yangiliklarni olib bo'lmadi.") }
        return true
    }

    /** "Amir Temur haqida ma'lumot": Vikipediyadan qisqa javob va kartochka. */
    private fun about(tq: String): Boolean {
        val q = tq.replace(Regex("\\b(haqida|malumot|ma'lumot|ber|ayt|kim|bu|nima)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.isBlank()) { say("Nima haqida?"); return true }
        if (!Util.online(ctx)) return false
        try {
            val hit = Search.wiki(q).ifEmpty { Search.wiki(q, "en") }.firstOrNull() ?: return false
            val lang = if (hit.url.contains("//uz.")) "uz" else "en"
            val s = JSONObject(Util.http("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(hit.title.replace(' ', '_')), "Nova-App")).optString("extract")
            results(q, listOf(hit))
            say(s.split(". ").take(2).joinToString(". ").ifBlank { hit.snippet })
        } catch (e: Exception) { return false }
        return true
    }

    /** "xarajat 20 ming non", "xarajatlar jami", "xarajatlarni o'chir" */
    private fun expense(t: String): Boolean {
        val list = Prefs.spend(ctx).toMutableList()
        when {
            Util.has(t, "xarajatlarni ochir", "xarajat ochir", "xarajatlarni tozala") -> { Prefs.setSpend(ctx, emptyList()); say("Xarajatlar o'chirildi.") }
            Util.has(t, "jami", "hisobot", "xarajatlarni oqi") -> {
                val total = list.sumOf { it.substringBefore("|").toLongOrNull() ?: 0L }
                say("Jami xarajat: ${"%,d".format(total)} so'm, ${list.size} ta yozuv." +
                    if (list.isEmpty()) "" else " Oxirgilari: " + list.takeLast(3).joinToString(". ") { it.replace("|", " so'm, ") })
            }
            else -> {
                val amt = Regex("\\d+").find(t)?.value?.toLongOrNull()
                if (amt == null) say("Summani ayting. Masalan: xarajat 20 ming non.")
                else {
                    val note = t.substringAfter(amt.toString()).replace(Regex("^\\s*(ming|som)\\b"), "").trim()
                    list.add("$amt|$note"); Prefs.setSpend(ctx, list)
                    say("${"%,d".format(amt)} so'm saqlandi${if (note.isNotBlank()) ": $note" else ""}.")
                }
            }
        }
        return true
    }

    private fun navigate(d: String): Boolean {
        if (d.isBlank()) { say("Qayerga borasiz?"); return true }
        return go(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(d))), "$d ga yo'l ko'rsatilmoqda.")
    }

    // ---------- qidiruv ----------
    private fun search(t: String, tq: String): Boolean {
        val src = when {
            t.contains("vikipedi") -> "wiki"; t.contains("bing") -> "bing"; t.contains("duckduck") || t.contains("dakdak") -> "ddg"
            t.contains("yandeks") || t.contains("yandex") -> "yandex"; t.contains("googl") || t.contains("gugl") -> "google"
            t.contains("youtube") || t.contains("yutub") -> "youtube"; else -> "all"
        }
        val q = tq.replace(Regex("\\b(qidir\\w*|izla\\w*)\\b"), " ")
            .replace(Regex("\\b(vikipedi\\w*|bing\\w*|duckduck\\w*|dakdak\\w*|yandeks\\w*|yandex\\w*|googl\\w*|gugl\\w*|youtube\\w*|yutub\\w*)\\b"), " ")
            .replace(Regex("\\b(dan|da|ni|haqida|boyicha)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.isBlank()) { say("Nimani qidiray?"); return true }
        val e = Uri.encode(q)
        when (src) {
            "google" -> return go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$e")), "$q Google'da qidirilmoqda.")
            "yandex" -> return go(Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.com/search/?text=$e")), "$q Yandex'da qidirilmoqda.")
            "youtube" -> return go(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$e")), "$q YouTube'da qidirilmoqda.")
        }
        if (!Util.online(ctx)) { say("Qidiruv uchun internet kerak."); return true }
        say("$q bo'yicha qidiryapman.")
        val hits = try {
            when (src) {
                "wiki" -> Search.wiki(q).ifEmpty { Search.wiki(q, "en") }.take(3)
                "bing" -> Search.bing(q).take(3)
                "ddg" -> Search.ddg(q).take(3)
                else -> Search.all(q)
            }
        } catch (ex: Exception) { emptyList() }
        if (hits.isEmpty()) { say("Natija topilmadi."); return true }
        results(q, hits)
        say("${hits.size} ta natija topdim. Birinchisi: ${hits[0].title}.")
        return true
    }

    /** "ikkinchi natijani och", "birinchi natijani o'qi" */
    private fun openHit(t: String): Boolean {
        val i = when {
            t.contains("ikkinchi") || Regex("\\b2\\b").containsMatchIn(t) -> 1
            t.contains("uchinchi") || Regex("\\b3\\b").containsMatchIn(t) -> 2
            else -> 0
        }
        val h = Bus.hits.getOrNull(i)
        if (h == null) { say("Bunday natija yo'q."); return true }
        return if (Util.has(t, "oqi", "ayt")) { say(h.snippet.ifBlank { h.title }); true }
        else go(Intent(Intent.ACTION_VIEW, Uri.parse(h.url)), "${h.title} ochilmoqda.")
    }
}

// ---------------------------------------------------------------------
// 10. TELEFONNI BOSHQARISH (Maxsus imkoniyatlar xizmati)
// ---------------------------------------------------------------------
class NovaService : AccessibilityService() {
    companion object { var instance: NovaService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun quickSettings() = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun powerMenu() = performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    fun lockScreen(): Boolean = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun screenshot(): Boolean = Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    fun scroll(down: Boolean) {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val from = dm.heightPixels * if (down) 0.7f else 0.3f
        val to = dm.heightPixels * if (down) 0.3f else 0.7f
        val p = Path().apply { moveTo(x, from); lineTo(x, to) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null)
    }

    fun clickText(text: String): Boolean {
        val node = rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return false
        var n: AccessibilityNodeInfo? = node
        while (n != null) { if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK); n = n.parent }
        return false
    }

    fun typeText(text: String): Boolean {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val a = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a)
    }

    fun readScreen(): String {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return out.take(25).joinToString(". ")
    }
}

// ---------------------------------------------------------------------
// 11. ASOSIY XIZMAT: tinglash, orb, natijalar, ovoz
// ---------------------------------------------------------------------
class AssistantService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "nova.start"
        const val ACTION_STOP = "nova.stop"
        const val ACTION_ACTIVATE = "nova.activate"
        const val ACTION_TEXT = "nova.text"
        const val ACTION_DOWNLOAD = "nova.download"
        const val ACTION_SHUTUP = "nova.shutup"
        const val ACTION_UPDATE = "nova.update"
        const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-uz-0.22.zip"
        var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private lateinit var commands: Commands
    private lateinit var wm: WindowManager

    private var engine: VoskEngine? = null
    private var active = false
    private var pending = 0
    private var keepOpen = false

    private var overlay: LinearLayout? = null
    private var orb: OrbView? = null
    private var subtitle: TextView? = null
    private var resultsBox: LinearLayout? = null
    private val hideRunnable = Runnable { hideOrb() }
    private val activeTimeout = Runnable { active = false; hideOrb() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> EventLog.add(c, "unlock")
                Intent.ACTION_SCREEN_ON -> EventLog.add(c, "on")
                Intent.ACTION_SCREEN_OFF -> EventLog.add(c, "off")
            }
        }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val eng = Prefs.ttsEngine(this)
        tts = if (eng.isBlank()) TextToSpeech(this, this) else TextToSpeech(this, this, eng)
        commands = Commands(this, { say(it) }, { q, hits -> showResults(q, hits) })
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (intent?.action) {
            ACTION_STOP -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
            ACTION_TEXT -> intent?.getStringExtra("text")?.let { Bus.ownerOk = true; handle(it) }
            ACTION_ACTIVATE -> wake()
            ACTION_DOWNLOAD -> downloadModel(intent?.getStringExtra("url") ?: MODEL_URL, intent?.getStringExtra("name") ?: "uz-small")
            ACTION_UPDATE -> updateFromGithub()
            ACTION_SHUTUP -> { tts.stop(); pending = 0; finishSpeaking() }
            else -> loadModelAndListen()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        commands.extras.shutdown()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        engine?.release()
        main.removeCallbacksAndMessages(null)
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        tts.shutdown(); worker.shutdownNow()
        Bus.setMode(OrbView.Mode.IDLE)
        super.onDestroy()
    }

    private fun startFg() {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("nova", "Nova", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getForegroundService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "nova")
            .setContentTitle("Nova tinglamoqda")
            .setContentText("\"${Prefs.wakeWords(this).firstOrNull() ?: "nova"}\" deb chaqiring")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_delete, "To'xtatish", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(1, n, type)
        } else startForeground(1, n)
    }

    // ---------- ovoz chiqarish ----------
    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { pending = maxOf(0, pending - 1); if (pending == 0) finishSpeaking() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { pending = maxOf(0, pending - 1); if (pending == 0) finishSpeaking() } }
        })
        var r = tts.setLanguage(Locale("uz", "UZ"))
        if (r < TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(Locale("tr", "TR"))
            Bus.add("• O'zbek ovozi o'rnatilmagan, turkcha ovoz ishlatilmoqda. Telefon sozlamalari > Matndan nutqqa dan o'zbek tilini yuklang.")
        }
        ttsReady = true
    }

    private fun applyVoice() {
        val vs = try { tts.voices?.toList() } catch (_: Exception) { null } ?: emptyList()
        val lang = tts.voice?.locale?.language ?: "uz"
        val saved = Prefs.voiceName(this)
        val pick = vs.firstOrNull { it.name == saved } ?: vs.filter { it.locale.language == lang }.let { c ->
            c.firstOrNull { it.name.contains("male", true) && !it.name.contains("female", true) }
                ?: c.firstOrNull { !it.name.contains("female", true) }
        }
        if (pick != null) tts.voice = pick
        tts.setPitch(Prefs.pitch(this)); tts.setSpeechRate(Prefs.rate(this))
    }

    fun say(raw: String) {
        main.post {
            Bus.add("Nova: $raw")
            showOrb(OrbView.Mode.SPEAKING); showSub(raw)
            if (!ttsReady) { main.postDelayed({ finishSpeaking() }, 1500); return@post }
            engine?.pause(true)
            applyVoice()
            pending++
            tts.speak(Util.speakable(raw), TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
            if (raw.trim().endsWith("?")) keepOpen = true
        }
    }

    private fun finishSpeaking() {
        engine?.pause(false)
        if (keepOpen) {
            keepOpen = false; active = true
            showOrb(OrbView.Mode.LISTENING)
            main.removeCallbacks(activeTimeout); main.postDelayed(activeTimeout, 8000)
        } else {
            Bus.setMode(OrbView.Mode.IDLE); orb?.mode = OrbView.Mode.IDLE
            val delay = if ((resultsBox?.childCount ?: 0) > 0) 25000L else 1500L   // natijalar 25 soniya turadi
            main.removeCallbacks(hideRunnable); main.postDelayed(hideRunnable, delay)
        }
    }

    // ---------- tinglash ----------
    private fun loadModelAndListen() {
        Models.list(this)
        var dir = Models.dir(this)
        if (!File(dir, "conf").exists()) {
            Models.list(this).firstOrNull()?.let { Prefs.put(this, "amodel", it); dir = Models.dir(this) }
        }
        if (!File(dir, "conf").exists()) {
            Bus.add("• Ovoz modeli yo'q. Menyu > \"Oflayn modelni yuklash\" yoki \"O'z modelimni qo'shish\" ni bosing.")
            return
        }
        worker.execute {
            try {
                engine?.release()
                val e = VoskEngine(dir); e.load(); e.loadSpeaker(File(filesDir, "spk"))
                main.post { startEngine(e) }
            } catch (ex: Exception) { say("Model xatosi: ${ex.message}") }
        }
    }

    private fun startEngine(e: VoskEngine) {
        engine = e
        try {
            e.start({ onPartial(it) }, { onFinal(it) }, { Bus.add("• Mikrofon xatosi: $it") })
            if (pending > 0) e.pause(true)
            Bus.add("• Tinglayapman. \"${Prefs.wakeWords(this).firstOrNull() ?: "nova"}\" deb chaqiring.")
        } catch (ex: Exception) { Bus.add("• Tinglashni boshlab bo'lmadi: ${ex.message}") }
    }

    private fun wakeMatch(text: String): Pair<String, IntRange>? {
        val t = Util.latin(text)
        for (w in Prefs.wakeWords(this)) {
            val m = Regex("(^|\\s)" + Regex.escape(w) + "(\\s|$)").find(t)
            if (m != null) return t to IntRange(m.range.first, m.range.last)
        }
        // noaniq tanishga chidamli: "nuva", "novo", "noba" kabi 1 harf farqli so'zlar ham chaqiruv hisoblanadi
        var pos = 0
        for (tok in t.split(" ")) {
            val start = t.indexOf(tok, pos); pos = start + tok.length
            if (tok.length < 3) continue
            if (Prefs.wakeWords(this).any { it.length >= 4 && !it.contains(" ") && Util.lev(tok, it) <= 1 })
                return t to IntRange(start, start + tok.length - 1)
        }
        return null
    }

    private fun onPartial(p: String) {
        if (p.isBlank() || active || overlay != null) return
        if (wakeMatch(p) != null) { showOrb(OrbView.Mode.LISTENING); main.postDelayed(hideRunnable, 6000) }
    }

    private fun onFinal(text: String) {
        if (text.isBlank() || pending > 0) return
        val vec = engine?.lastSpk
        if (Bus.enrolling > 0 && vec != null) {   // egasining ovozini o'rganish
            Bus.enrollVecs.add(vec); Bus.enrolling--
            if (Bus.enrolling == 0) { Speaker.save(this, Speaker.avg(Bus.enrollVecs)); Bus.enrollVecs.clear(); say("Ovozingiz saqlandi. Ovoz qulfi uchun \"ovoz qulfini yoq\" deng.") }
            else say("Yana bir gap ayting.")
            return
        }
        val own = Speaker.load(this)
        Bus.ownerOk = !Prefs.vlock(this) || (own != null && vec != null && Speaker.dist(own, vec) < Speaker.THRESH)
        val m = wakeMatch(text)
        if (!active && m == null) return
        if (!active) beep()
        val cmd = if (m != null) m.first.substring(m.second.last + 1).trim() else text
        if (cmd.isBlank()) wake() else { active = false; main.removeCallbacks(activeTimeout); handle(cmd) }
    }

    private fun wake() {
        if (engine == null) { say("Avval ovoz modelini yuklang: Menyu, Oflayn modelni yuklash."); return }
        active = true
        showOrb(OrbView.Mode.LISTENING); showSub("Eshitaman...")
        main.removeCallbacks(activeTimeout); main.postDelayed(activeTimeout, 9000)
    }

    private fun beep() { try { ToneGenerator(AudioManager.STREAM_MUSIC, 60).startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Exception) {} }

    private fun handle(text: String) {
        Bus.add("Siz: $text")
        showOrb(OrbView.Mode.THINKING); showSub(text)
        worker.execute {
            try {
                if (commands.tryHandle(text)) return@execute
                Offline.smallTalk(text)?.let { say(it); return@execute }
                if (Util.online(this)) say(AiClient.ask(this, text))
                else say("Internet yo'q. Bu savolga AI kerak, lekin buyruqlar va oddiy suhbat oflayn ishlaydi.")
            } catch (e: Exception) { say("Xatolik: ${e.message}") }
        }
    }

    // ---------- orb va natija kartochkalari ----------
    private fun showOrb(m: OrbView.Mode) {
        Bus.setMode(m); orb?.mode = m
        main.removeCallbacks(hideRunnable)
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val d = resources.displayMetrics.density
        val o = OrbView(this).apply {
            mode = m
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xB3001014.toInt()) }
        }
        val sub = TextView(this).apply {
            setTextColor(0xFFB8FFF5.toInt()); textSize = 14f; gravity = Gravity.CENTER; maxLines = 3
            setShadowLayer(8f, 0f, 0f, 0xFF000000.toInt())
        }
        val rb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; alpha = 0f
            addView(rb, LinearLayout.LayoutParams((330 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(o, LinearLayout.LayoutParams((220 * d).toInt(), (220 * d).toInt()))
            addView(sub, LinearLayout.LayoutParams((300 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnClickListener { active = false; hideOrb() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (60 * d).toInt() }
        try { wm.addView(box, lp) } catch (e: Exception) { return }
        box.animate().alpha(1f).setDuration(250).start()
        overlay = box; orb = o; subtitle = sub; resultsBox = rb
    }

    private fun showResults(q: String, hits: List<Hit>) {
        main.post {
            Bus.setHits(hits)
            showOrb(OrbView.Mode.THINKING)
            val rb = resultsBox ?: return@post
            rb.removeAllViews()
            hits.forEachIndexed { i, h -> rb.addView(buildCard(this, i, h)) }
        }
    }

    private fun showSub(s: String) { subtitle?.text = if (s.length > 140) s.take(140) + "..." else s }

    private fun hideOrb() {
        val box = overlay ?: return
        overlay = null; orb = null; subtitle = null; resultsBox = null
        box.animate().alpha(0f).setDuration(250).withEndAction { try { wm.removeView(box) } catch (_: Exception) {} }.start()
        Bus.setMode(OrbView.Mode.IDLE)
    }

    // ---------- GitHub Release'dan model yangilash ----------
    private fun updateFromGithub() {
        val repo = Prefs.repo(this)
        if (repo.isBlank()) { say("Sozlamalarda GitHub repozitoriyni kiriting, masalan: egasi slash nomi."); return }
        worker.execute {
            try {
                val j = JSONObject(Util.http("https://api.github.com/repos/$repo/releases/latest", "Nova-App"))
                val tag = j.getString("tag_name")
                val assets = j.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".zip")) { url = a.getString("browser_download_url"); break }
                }
                if (url == null) { say("Oxirgi relizda zip model topilmadi."); return@execute }
                val name = safeName("gh-$tag")
                if (File(Models.root(this), name).exists()) {
                    Prefs.put(this, "amodel", name)
                    say("Model allaqachon yangi: $tag.")
                    main.post { loadModelAndListen() }
                } else downloadModel(url, name)
            } catch (e: Exception) { say("Yangilashda xato: ${e.message}") }
        }
    }

    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_")

    // ---------- oflayn modelni yuklab olish ----------
    private fun downloadModel(url: String, name: String) {
        worker.execute {
            try {
                Bus.add("• Model yuklanmoqda...")
                val tmp = File(cacheDir, "model.zip")
                val c = URL(url).openConnection()
                val total = c.contentLength.toLong()
                var done = 0L; var lastPct = -1
                c.getInputStream().use { i -> tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() / 10 * 10 else -1
                        if (pct != lastPct && pct >= 0) { lastPct = pct; Bus.add("• Yuklanmoqda: $pct%") }
                    }
                } }
                if (name == "__spk") {   // ovoz tanish (egasi) modeli
                    tmp.inputStream().use { Util.unzip(it, File(filesDir, "spk")) }
                    Util.flatten(File(filesDir, "spk"))
                    tmp.delete()
                    say("Ovoz tanish modeli tayyor. Endi \"ovozimni o'rgan\" deng.")
                    main.post { loadModelAndListen() }
                    return@execute
                }
                val safe = safeName(name)
                if (Prefs.activeModel(this) == safe) { engine?.release(); engine = null }
                tmp.inputStream().use { Util.unzip(it, File(Models.root(this), safe)) }
                Prefs.put(this, "amodel", safe)
                tmp.delete()
                say("Model tayyor: $safe.")
                main.post { loadModelAndListen() }
            } catch (e: Exception) { say("Modelni yuklab bo'lmadi: ${e.message}") }
        }
    }
}

// ---------------------------------------------------------------------
// 12. ASOSIY EKRAN
// ---------------------------------------------------------------------
class MainActivity : AppCompatActivity() {

    private lateinit var orb: OrbView
    private lateinit var hitsBox: LinearLayout
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var toggle: Button
    private var previewTts: TextToSpeech? = null

    private val pickModel = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importModel(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        window.decorView.setBackgroundColor(0xFF03080A.toInt())

        orb = OrbView(this)
        hitsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 4, 16, 4) }
        log = TextView(this).apply { textSize = 15f; setTextColor(0xFFCFFAF4.toInt()); setPadding(32, 16, 32, 16) }
        scroll = ScrollView(this).apply { addView(log) }
        input = EditText(this).apply { hint = "Buyruq yozing..."; setHintTextColor(0x88CFFAF4.toInt()); setTextColor(Color.WHITE); setSingleLine() }
        val send = Button(this).apply { text = "➤"; setOnClickListener { sendText() } }
        val row = LinearLayout(this).apply {
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(send)
        }
        toggle = Button(this).apply { setOnClickListener { toggleService() } }
        val mic = Button(this).apply { text = "🎤"; setOnClickListener { svc(AssistantService.ACTION_ACTIVATE) } }
        val menu = Button(this).apply { text = "⋮ Menyu"; setOnClickListener { showMenu() } }
        val buttons = LinearLayout(this).apply {
            addView(toggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
            addView(mic, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(menu, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(orb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (200 * d).toInt()))
            addView(hitsBox)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(row); addView(buttons)
        })

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        log.text = synchronized(Bus.lines) { Bus.lines.joinToString("\n") }.ifEmpty {
            "Nova tayyor. \"Yoqish\" ni bosing va \"Nova\" deb chaqiring.\nMisollar: diagnostika, och telegram, qidir O'zbekiston, dollar kursi, ob-havo\n"
        }
        showHits(Bus.hits)
        orb.mode = Bus.mode
        Bus.onLine = { s -> runOnUiThread { log.append("\n$s"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } } }
        Bus.onMode = { m -> runOnUiThread { orb.mode = m } }
        Bus.onHits = { h -> runOnUiThread { showHits(h) } }
        refreshToggle()
    }

    override fun onPause() { Bus.onLine = null; Bus.onMode = null; Bus.onHits = null; super.onPause() }
    override fun onDestroy() { previewTts?.shutdown(); super.onDestroy() }

    private fun showHits(h: List<Hit>) { hitsBox.removeAllViews(); h.forEachIndexed { i, x -> hitsBox.addView(buildCard(this, i, x)) } }

    private fun refreshToggle() { toggle.text = if (AssistantService.running) "⏹ To'xtatish" else "▶ Yoqish" }

    private fun svc(action: String, extra: (Intent.() -> Unit)? = null) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        val i = Intent(this, AssistantService::class.java).setAction(action)
        extra?.invoke(i)
        ContextCompat.startForegroundService(this, i)
        toggle.postDelayed({ refreshToggle() }, 400)
    }

    private fun toggleService() = if (AssistantService.running) svc(AssistantService.ACTION_STOP) else svc(AssistantService.ACTION_START)

    private fun sendText() {
        val s = input.text.toString().trim()
        if (s.isEmpty()) return
        input.text.clear()
        svc(AssistantService.ACTION_TEXT) { putExtra("text", s) }
    }

    private fun showMenu() {
        val items = arrayOf(
            "Sozlamalar (API kalit, model, ovoz dvigateli)", "Ovozni tanlash", "Diktor ovozi (yo'g'on erkak)",
            "Oflayn modelni yuklash", "O'z ovoz modelimni qo'shish (zip)",
            "Modelni URL dan yuklash (GitHub)", "Modelni tanlash", "GitHub'dan model yangilash",
            "Boshqaruv ruxsati (Maxsus imkoniyatlar)", "Orb ruxsati (ilovalar ustida)",
            "Batareya cheklovini o'chirish", "Buyruqlar ro'yxati",
            "Ovoz tanish modelini yuklash (egasi ovozi)", "Foydalanish statistikasi ruxsati"
        )
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> settingsDialog()
                1 -> pickVoice()
                2 -> { Prefs.put(this, "pitch", 0.7f); Prefs.put(this, "rate", 0.9f); Toast.makeText(this, "Diktor ovozi yoqildi", Toast.LENGTH_SHORT).show() }
                3 -> svc(AssistantService.ACTION_DOWNLOAD)
                4 -> pickModel.launch("application/zip")
                5 -> urlDialog()
                6 -> chooseModel()
                7 -> svc(AssistantService.ACTION_UPDATE)
                8 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                9 -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                10 -> startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                11 -> help()
                12 -> svc(AssistantService.ACTION_DOWNLOAD) { putExtra("url", AssistantService.SPK_URL); putExtra("name", "__spk") }
                13 -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        }.show()
    }

    private fun urlDialog() {
        val url = EditText(this).apply { hint = "https://github.com/.../releases/download/.../model.zip"; setText(Prefs.lastUrl(this@MainActivity)); setSingleLine() }
        val name = EditText(this).apply { hint = "Model nomi (masalan: uz-mening)"; setSingleLine() }
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0); addView(url); addView(name) }
        AlertDialog.Builder(this).setTitle("Modelni URL dan yuklash").setView(lay)
            .setPositiveButton("Yuklash") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isNotEmpty()) {
                    Prefs.put(this, "lasturl", u)
                    svc(AssistantService.ACTION_DOWNLOAD) { putExtra("url", u); putExtra("name", name.text.toString().trim().ifEmpty { "custom" }) }
                }
            }.setNegativeButton("Bekor", null).show()
    }

    private fun chooseModel() {
        val l = Models.list(this)
        if (l.isEmpty()) { Toast.makeText(this, "Model yo'q. Avval yuklang.", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("Faol model: ${Prefs.activeModel(this)}")
            .setItems(l.toTypedArray()) { _, i -> Prefs.put(this, "amodel", l[i]); svc(AssistantService.ACTION_START) }.show()
    }

    private fun importModel(uri: Uri) {
        Toast.makeText(this, "Model o'rnatilmoqda...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val n = "custom-" + System.currentTimeMillis() / 1000
                contentResolver.openInputStream(uri)!!.use { Util.unzip(it, File(Models.root(this), n)) }
                Prefs.put(this, "amodel", n)
                runOnUiThread { Toast.makeText(this, "Model tayyor", Toast.LENGTH_SHORT).show(); svc(AssistantService.ACTION_START) }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    private fun settingsDialog() {
        fun field(h: String, v: String) = EditText(this).apply { hint = h; setText(v); setSingleLine() }
        val key = field("OpenRouter API kalit (sk-or-...)", Prefs.apiKey(this))
        val model = field("Model (masalan: openrouter/auto)", Prefs.model(this))
        val wake = field("Chaqiruv so'zlari (vergul bilan)", Prefs.wake(this))
        val city = field("Shahar (Tashkent, Samarkand...)", Prefs.city(this))
        val eng = field("Ovoz dvigateli paketi (bo'sh = standart)", Prefs.ttsEngine(this))
        val repo = field("GitHub repozitoriy: egasi/nomi (model yangilash uchun)", Prefs.repo(this))
        val sos = field("Favqulodda raqam (SOS uchun)", Prefs.getStr(this, "sos_number"))
        val cams = EditText(this).apply { hint = "IP kameralar (har qatorda: nom|havola)"; setText(Prefs.getStr(this@MainActivity, "cams")); minLines = 2 }
        val lay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            listOf(key, model, wake, city, eng, repo, sos, cams).forEach { addView(it) }
        }
        AlertDialog.Builder(this).setTitle("Sozlamalar").setView(lay)
            .setPositiveButton("Saqlash") { _, _ ->
                Prefs.put(this, "key", key.text.toString().trim())
                Prefs.put(this, "model", model.text.toString().trim().ifEmpty { "openrouter/auto" })
                Prefs.put(this, "wake", wake.text.toString().trim().ifEmpty { "nova" })
                Prefs.put(this, "city", city.text.toString().trim().ifEmpty { "Tashkent" })
                Prefs.put(this, "tts", eng.text.toString().trim())
                Prefs.put(this, "repo", repo.text.toString().trim())
                Prefs.put(this, "sos_number", sos.text.toString().trim())
                Prefs.put(this, "cams", cams.text.toString().trim())
                Toast.makeText(this, "Saqlandi. Yordamchini qayta yoqing.", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Bekor", null).show()
    }

    private fun pickVoice() {
        previewTts?.shutdown()
        previewTts = TextToSpeech(this) { st ->
            val t = previewTts
            if (st != TextToSpeech.SUCCESS || t == null) return@TextToSpeech
            val all = t.voices?.toList() ?: emptyList()
            val uz = all.filter { it.locale.language == "uz" }
            val list = (if (uz.isNotEmpty()) uz else all.filter { it.locale.language == "tr" }).sortedBy { it.name }
            runOnUiThread {
                if (list.isEmpty()) {
                    Toast.makeText(this, "Ovoz topilmadi. Telefon sozlamalarida matndan nutqqa o'zbek tilini yuklab oling.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this).setTitle("Ovozni tanlang (bosib eshiting)")
                    .setItems(list.map { it.name }.toTypedArray()) { _, i ->
                        Prefs.put(this, "voice", list[i].name)
                        t.voice = list[i]; t.setPitch(Prefs.pitch(this))
                        t.speak("Salom, men Nova. Sizga qanday yordam bera olaman?", TextToSpeech.QUEUE_FLUSH, null, "x")
                    }.show()
            }
        }
    }

    private fun help() {
        AlertDialog.Builder(this).setTitle("Buyruqlar").setMessage(
            "• diagnostika, batareya, xotira, ram, internet\n" +
            "• soat nech, bugun qaysi sana, ob-havo Toshkent, namoz vaqti, dollar kursi\n" +
            "• qidir <so'z>: 3 ta natija ekranda (DuckDuckGo, Vikipediya, Bing)\n" +
            "• vikipediyadan qidir ..., bingdan qidir ..., yandexdan qidir ..., googledan qidir ..., youtubedan qidir ...\n" +
            "• ikkinchi natijani och / birinchi natijani o'qi\n" +
            "• tarjima qil salom inglizchaga, 5 km necha mil\n" +
            "• orqaga, bosh ekran, oxirgi ilovalar, bildirishnoma, skrinshot, qulfla\n" +
            "• pastga, yuqoriga, bos <matn>, yoz <matn>, ekranni o'qi, ekranda nima bor\n" +
            "• chiroqni yoq/o'chir, ovozni oshir/pasaytir, vibratsiya rejimi, oddiy rejim\n" +
            "• keyingi qo'shiq, oldingi qo'shiq, musiqani to'xtat\n" +
            "• qo'ng'iroq qil Ali, sms Ali salom, kontakt Ali\n" +
            "• budilnik 7 30, taymer 5 daqiqa, eslatib qo'y 10 daqiqadan keyin sut olish\n" +
            "• eslatma yoz ..., eslatmalarni o'qi, eslatmalarni o'chir\n" +
            "• hisobla 25 qo'shuv 3, tanga tashla, zar tashla, latifa ayt\n" +
            "• diktor ovozi, tezroq/sekinroq gapir, yo'g'onroq, jim bo'l\n" +
            "• yangiliklar, Amir Temur haqida ma'lumot, yo'l ko'rsat Chorsu, kalendarga qo'sh uchrashuv\n" +
            "• xarajat 20 ming non, xarajatlar jami, pomodoro, tejash rejimi\n" +
            "• modellar, modelni yangila (GitHub'dan)\n" +
            "• xavfsizlik tekshiruvi, ruxsat tekshiruvi, to'liq diagnostika\n" +
            "• blokni och (ekranni yoqadi, tasdiqlash sizda), telefon qachon ochilgan, qaysi ilovalar ishlatilgan\n" +
            "• wifi qurilmalar, qurilmalarga ishonaman, kameralarni qidir, kamerani och <nom>, router\n" +
            "• qo'riqlashni boshla / to'xtat (telefon kamerasi), puls, qadam, bmi 70 175, suv ichish\n" +
            "• SOS, joylashuvim, kundalik (sayt ochib skrinshot)\n" +
            "• ovozimni o'rgan, ovoz qulfini yoq/o'chir\n" +
            "• Boshqa hamma savol: AI javob beradi"
        ).setPositiveButton("OK", null).show()
    }
}
