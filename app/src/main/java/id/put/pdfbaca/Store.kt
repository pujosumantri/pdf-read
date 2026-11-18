package id.put.pdfbaca

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecentItem(val key: String, val name: String, val time: Long)

/** Riwayat file terbaru + posisi halaman/rotasi terakhir tiap file. */
object Store {
    private const val MAX_RECENT = 50

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("pdfbaca", Context.MODE_PRIVATE)

    fun recents(c: Context): List<RecentItem> {
        val arr = try {
            JSONArray(prefs(c).getString("recents", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val out = ArrayList<RecentItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val k = o.optString("k")
            if (k.isEmpty()) continue
            out.add(RecentItem(k, o.optString("n", k), o.optLong("t")))
        }
        return out
    }

    fun addRecent(c: Context, key: String, name: String) {
        val list = recents(c).filter { it.key != key }.toMutableList()
        list.add(0, RecentItem(key, name, System.currentTimeMillis()))
        saveRecents(c, list.take(MAX_RECENT))
    }

    fun removeRecent(c: Context, key: String) {
        saveRecents(c, recents(c).filter { it.key != key })
    }

    fun clearRecents(c: Context) = saveRecents(c, emptyList())

    private fun saveRecents(c: Context, list: List<RecentItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("k", it.key).put("n", it.name).put("t", it.time)) }
        prefs(c).edit().putString("recents", arr.toString()).apply()
    }

    fun getPage(c: Context, key: String) = prefs(c).getInt("page:$key", 0)
    fun setPage(c: Context, key: String, page: Int) = prefs(c).edit().putInt("page:$key", page).apply()

    fun getRotation(c: Context, key: String) = prefs(c).getInt("rot:$key", 0)
    fun setRotation(c: Context, key: String, rot: Int) = prefs(c).edit().putInt("rot:$key", rot).apply()

    fun getLastDir(c: Context): String? = prefs(c).getString("lastDir", null)
    fun setLastDir(c: Context, path: String?) = prefs(c).edit().putString("lastDir", path).apply()

    // ---- About ----
    const val APP_NAME = "PDF Read: Simple, Reliable"
    const val DEVELOPER = "pujosumantri"
    const val YEAR = "2026"

    fun showAbout(a: AppCompatActivity) {
        val ver = try {
            a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "-"
        } catch (e: Exception) {
            "-"
        }
        AlertDialog.Builder(a)
            .setTitle("About")
            .setMessage(
                "$APP_NAME\n\n" +
                    "Versi: $ver\n" +
                    "Developer: $DEVELOPER\n" +
                    "Tahun: $YEAR\n\n" +
                    "Pembaca PDF gratis, tanpa iklan."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- format teks ----
    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))

    fun fmtDate(t: Long): String = synchronized(dateFmt) { dateFmt.format(Date(t)) }

    fun fmtSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
    }
}
