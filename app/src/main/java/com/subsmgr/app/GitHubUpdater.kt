package com.subsmgr.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

private const val GH_OWNER = "ahmadga808"
private const val GH_REPO = "VoltNetPro-v"
private const val GH_LATEST_API = "https://api.github.com/repos/$GH_OWNER/$GH_REPO/releases/latest"

private data class GithubReleaseInfo(
    val versionName: String,
    val versionCode: Long,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkName: String,
    val checksumUrl: String,
    val apiDigest: String
)

private fun httpGet(url: String): ByteArray {
    if (!url.startsWith("https://")) throw IllegalStateException("تم رفض مصدر تحديث غير موثوق")
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15000
        readTimeout = 30000
        setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream, text/plain")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "VoltNet-Pro-Android-Updater")
        instanceFollowRedirects = true
    }
    try {
        val code = connection.responseCode
        if (connection.url.protocol != "https" || connection.url.host !in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com"))
            throw IllegalStateException("تم رفض تحويل إلى مصدر غير موثوق")
        if (code !in 200..299) throw IllegalStateException("GitHub أعاد رمز HTTP $code")
        return connection.inputStream.use { it.readBytes() }
    } finally { connection.disconnect() }
}

private fun latestRelease(): GithubReleaseInfo {
    val json = JSONObject(String(httpGet(GH_LATEST_API), Charsets.UTF_8))
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) throw IllegalStateException("الإصدار المنشور ليس إصداراً مستقراً")
    val assets = json.optJSONArray("assets") ?: throw IllegalStateException("لا توجد ملفات مرفقة بالإصدار")
    var apkName = ""
    var apkUrl = ""
    var checksumUrl = ""
    var apiDigest = ""
    for (i in 0 until assets.length()) {
        val a = assets.getJSONObject(i)
        val name = a.optString("name")
        val url = a.optString("browser_download_url")
        if (name.endsWith(".apk", true) && apkUrl.isEmpty()) {
            apkName = name; apkUrl = url
            apiDigest = a.optString("digest").removePrefix("sha256:").lowercase(Locale.US)
            if (!apiDigest.matches(Regex("[0-9a-f]{64}"))) apiDigest = ""
        }
        if ((name.endsWith(".sha256", true) || name.equals("SHA256SUMS.txt", true) || name.equals("checksums.txt", true)) && checksumUrl.isEmpty()) checksumUrl = url
    }
    if (apkUrl.isBlank()) throw IllegalStateException("لم أجد ملف APK ضمن ملفات GitHub Release")
    if (apiDigest.isBlank() && checksumUrl.isBlank()) throw IllegalStateException("لا توجد بصمة SHA-256 معروفة لملف APK ضمن الإصدار؛ أوقفنا التحديث للحماية")
    if (!apkUrl.startsWith("https://github.com/") || (apiDigest.isBlank() && !checksumUrl.startsWith("https://github.com/"))) throw IllegalStateException("روابط ملفات التحديث يجب أن تستخدم HTTPS من GitHub")
    if (checksumUrl.isNotBlank() && !checksumUrl.startsWith("https://")) throw IllegalStateException("رابط ملف التحقق غير آمن")
    val tag = json.optString("tag_name").removePrefix("v").trim()
    val version = Regex("^\\d+(?:\\.\\d+){1,3}$").matchEntire(tag)?.value
        ?: throw IllegalStateException("اسم وسم الإصدار غير صالح: $tag")
    val notes = json.optString("body").ifBlank { "لا توجد ملاحظات إصدار." }
    val codeMatch = Regex("(?im)^\\s*(?:Build|versionCode)\\s*[:：#]?\\s*(\\d+)").find(notes)
    val versionCode = codeMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    return GithubReleaseInfo(version, versionCode, json.optString("name").ifBlank { "VoltNet Pro $version" },
        notes, json.optString("html_url"), apkUrl, apkName, checksumUrl, apiDigest)
}

private fun compareVersions(remote: String, local: String): Int {
    val a = remote.split('.').map { it.toIntOrNull() ?: 0 }
    val b = local.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

private fun installedVersion(context: Context): Pair<String, Long> {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    return (info.versionName ?: "2.23") to code
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(Locale.US, it) }

private suspend fun downloadAndVerify(context: Context, release: GithubReleaseInfo): File = withContext(Dispatchers.IO) {
    val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 60000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "VoltNet-Pro-Android-Updater")
        setRequestProperty("Accept", "application/vnd.android.package-archive")
    }
    val apk = try {
        val code = connection.responseCode
        if (connection.url.protocol != "https" || connection.url.host !in setOf("github.com", "release-assets.githubusercontent.com"))
            throw IllegalStateException("تم رفض رابط تنزيل غير موثوق")
        if (code !in 200..299) throw IllegalStateException("تعذّر تنزيل APK (HTTP $code)")
        if (connection.contentLengthLong > 180L * 1024L * 1024L) throw IllegalStateException("حجم ملف التحديث أكبر من الحد المسموح")
        connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > 180L * 1024L * 1024L) throw IllegalStateException("حجم ملف التحديث أكبر من الحد المسموح")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } finally { connection.disconnect() }
    if (apk.isEmpty()) throw IllegalStateException("ملف APK فارغ")
    val expected = if (release.apiDigest.matches(Regex("(?i)[0-9a-f]{64}"))) release.apiDigest else {
        val checksumText = String(httpGet(release.checksumUrl), Charsets.UTF_8)
        val hashPattern = Regex("(?i)\\b[0-9a-f]{64}\\b")
        val matchingLine = checksumText.lineSequence().firstOrNull { it.contains(release.apkName, ignoreCase = true) }
        (matchingLine?.let { hashPattern.find(it)?.value }
            ?: hashPattern.findAll(checksumText).map { it.value }.toList().singleOrNull())?.lowercase(Locale.US)
            ?: throw IllegalStateException("تعذّر تحديد بصمة SHA-256 الخاصة بملف APK")
    }
    val actual = sha256(apk)
    if (!MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray()))
        throw IllegalStateException("فشل التحقق من SHA-256؛ لن يتم تثبيت الملف")
    if (apk.size < 1024) throw IllegalStateException("حجم ملف APK غير منطقي")
    val safeName = release.apkName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
    val out = File(context.cacheDir, "updates").apply { mkdirs() }.resolve(safeName)
    out.writeBytes(apk)
    out
}

private fun launchInstaller(context: Context, apk: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

@Composable
fun GitHubUpdateCenter(open: Boolean, localNotesVisible: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var release by remember { mutableStateOf<GithubReleaseInfo?>(null) }
    var message by remember { mutableStateOf("يجري فحص GitHub...") }
    var checking by remember { mutableStateOf(true) }
    var checked by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf(false) }
    var dialogVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        checking = true
        try {
            val result = withContext(Dispatchers.IO) { latestRelease() }
            val (local, localCode) = installedVersion(context)
            release = result
            updateAvailable = compareVersions(result.versionName, local) > 0 || (result.versionCode > 0L && result.versionCode > localCode)
            message = if (updateAvailable) "يتوفر إصدار جديد: ${result.versionName} (المثبت: $local، Build $localCode)" else "التطبيق محدث. الإصدار المثبت: $local (Build $localCode)"
        } catch (e: Exception) {
            message = "تعذّر فحص التحديثات: ${e.message ?: "خطأ اتصال"}"
        } finally { checking = false; checked = true }
    }

    LaunchedEffect(open, localNotesVisible, updateAvailable, checked) {
        if (open || (checked && updateAvailable && !localNotesVisible)) dialogVisible = true
    }

    if (dialogVisible) {
        AlertDialog(
            onDismissRequest = { if (!downloading) { dialogVisible = false; onDismiss() } },
            title = { Text("تحديث VoltNet Pro") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(message)
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    release?.let { r ->
                        Spacer(Modifier.height(10.dp))
                        Text(r.title, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(r.notes)
                    }
                    if (downloading) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("جارٍ تنزيل APK والتحقق من بصمته...")
                    }
                }
            },
            confirmButton = {
                if (!updateAvailable) TextButton(onClick = { dialogVisible = false; onDismiss() }) { Text("إغلاق") }
                else if (release != null) TextButton(enabled = !downloading, onClick = {
                    downloading = true
                    message = "جارٍ تنزيل التحديث والتحقق منه..."
                    scope.launch {
                        try {
                            val file = downloadAndVerify(context, release!!)
                            // سيمنع Android التحديث إذا لم يطابق توقيع التطبيق المثبت.
                            launchInstaller(context, file)
                            downloading = false
                            dialogVisible = false
                            onDismiss()
                        } catch (e: Exception) {
                            downloading = false
                            message = "فشل التحديث: ${e.message ?: "خطأ غير معروف"}"
                            dialogVisible = true
                        }
                    }
                }) { Text("تنزيل وتثبيت") }
            },
            dismissButton = {
                if (release?.pageUrl?.isNotBlank() == true) TextButton(onClick = { openUrl(context, release!!.pageUrl) }) { Text("صفحة GitHub") }
            }
        )
    }
}
