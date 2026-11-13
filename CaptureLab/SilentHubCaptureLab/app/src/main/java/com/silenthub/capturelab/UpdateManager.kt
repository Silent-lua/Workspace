package com.silenthub.capturelab

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val mandatory: Boolean,
    val minimumSupportedVersionCode: Long,
    val changelog: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseUrl: String
)

internal class ReleaseSignatureMismatchException :
    Exception("El APK descargado no utiliza el mismo certificado que esta instalación.")

internal class UpdateManager(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun currentVersionCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return packageVersionCode(info)
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    fun checkLatest(force: Boolean): UpdateInfo? {
        val now = System.currentTimeMillis()
        val checkedAt = prefs.getLong(KEY_CHECKED_AT, 0L)
        val cached = prefs.getString(KEY_MANIFEST, null)

        if (!force && cached != null && now - checkedAt in 0..CHECK_CACHE_MS) {
            return parseManifest(cached)
        }

        try {
            val request = Request.Builder()
                .url(UPDATE_ENDPOINT)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("El servicio de actualizaciones respondió HTTP " + response.code + ".")
                }
                val body = response.body?.string()
                    ?: throw IllegalStateException("El manifiesto remoto llegó vacío.")
                require(JSONObject(body).optBoolean("ok", false)) { "El manifiesto no superó la validación." }
                prefs.edit().putString(KEY_MANIFEST, body).putLong(KEY_CHECKED_AT, now).apply()
                return parseManifest(body)
            }
        } catch (error: Exception) {
            if (cached != null && now - checkedAt <= STALE_CACHE_MS) return parseManifest(cached)
            throw error
        }
    }

    fun download(info: UpdateInfo, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
        val directory = File(app.cacheDir, "verified-updates").apply { mkdirs() }
        val destination = File(directory, "SilentHub-External-ESP-" + info.versionName + ".apk")
        if (destination.isFile) {
            try {
                verifyFile(destination, info)
                verifyPackageAndSigner(destination, info)
                return destination
            } catch (_: Exception) {
                destination.delete()
            }
        }

        val partial = File(directory, destination.name + ".part")
        partial.delete()
        val request = Request.Builder().url(info.apkUrl)
            .header("Accept", "application/octet-stream")
            .get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("La descarga respondió HTTP " + response.code + ".")
                }
                val body = response.body ?: throw IllegalStateException("El paquete descargado está vacío.")
                val contentLength = body.contentLength()
                if (contentLength > 0 && contentLength != info.sizeBytes) {
                    throw IllegalStateException("El tamaño recibido no coincide con el manifiesto.")
                }

                val digest = MessageDigest.getInstance("SHA-256")
                var downloaded = 0L
                FileOutputStream(partial).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > info.sizeBytes || downloaded > MAX_APK_BYTES) {
                                throw IllegalStateException("El paquete supera el tamaño permitido.")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onProgress(downloaded, info.sizeBytes)
                        }
                    }
                    output.fd.sync()
                }

                if (downloaded != info.sizeBytes) throw IllegalStateException("La descarga quedó incompleta.")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!MessageDigest.isEqual(
                        hash.toByteArray(Charsets.US_ASCII),
                        info.sha256.toByteArray(Charsets.US_ASCII)
                    )) {
                    throw IllegalStateException("La comprobación SHA-256 falló.")
                }
            }

            verifyPackageAndSigner(partial, info)
            try {
                Files.move(
                    partial.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                if (!partial.renameTo(destination)) {
                    throw IllegalStateException("No se pudo finalizar el archivo temporal.")
                }
            }
            return destination
        } catch (error: Exception) {
            partial.delete()
            throw error
        }
    }

    fun findVerifiedCachedFile(info: UpdateInfo): File? {
        val file = File(
            File(app.cacheDir, "verified-updates"),
            "SilentHub-External-ESP-" + info.versionName + ".apk"
        )
        if (!file.isFile) return null
        return try {
            verifyFile(file, info)
            verifyPackageAndSigner(file, info)
            file
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    fun install(file: File) {
        require(file.isFile && file.length() in 1..MAX_APK_BYTES) {
            "El paquete de instalación no es válido."
        }
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                FileInputStream(file).use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val callback = android.content.Intent(app, InstallStatusReceiver::class.java)
                    .setPackage(app.packageName)
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                } else android.app.PendingIntent.FLAG_UPDATE_CURRENT
                val pending = android.app.PendingIntent.getBroadcast(app, INSTALL_REQUEST_CODE, callback, flags)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun parseManifest(raw: String): UpdateInfo? {
        val root = JSONObject(raw)
        require(root.optBoolean("ok", false)) { "Manifiesto remoto inválido." }
        val latest = root.optJSONObject("latest") ?: return null
        val version = SemanticVersion.parse(latest.optString("versionName", ""))
        val versionCode = latest.optLong("versionCode", -1L)
        require(versionCode > 0L) { "versionCode no válido." }
        if (versionCode <= currentVersionCode()) return null

        val apkUrl = latest.optString("apkUrl", "")
        val releaseUrl = latest.optString("releaseUrl", "")
        require(isAllowedAssetUrl(apkUrl)) { "El origen del APK no está autorizado." }
        require(isAllowedReleaseUrl(releaseUrl)) { "La URL del release no está autorizada." }

        val sha = latest.optString("sha256", "").lowercase()
        require(sha.matches(Regex("^[a-f0-9]{64}$"))) { "SHA-256 no válido." }
        val size = latest.optLong("sizeBytes", -1L)
        require(size in 1..MAX_APK_BYTES) { "Tamaño de paquete no válido." }

        return UpdateInfo(
            versionName = version.toString(),
            versionCode = versionCode,
            mandatory = latest.optBoolean("mandatory", true),
            minimumSupportedVersionCode = latest.optLong("minimumSupportedVersionCode", versionCode),
            changelog = latest.optString("changelog", "Actualización de SilentHub External ESP.").take(1800),
            apkUrl = apkUrl,
            sha256 = sha,
            sizeBytes = size,
            releaseUrl = releaseUrl
        )
    }

    private fun isAllowedAssetUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "https" && uri.host == "github.com" &&
            uri.path.orEmpty().startsWith("/Silent-lua/Workspace/releases/download/")
    }.getOrDefault(false)

    private fun isAllowedReleaseUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        uri.scheme == "https" && uri.host == "github.com" &&
            uri.path.orEmpty().startsWith("/Silent-lua/Workspace/releases/")
    }.getOrDefault(false)

    private fun verifyFile(file: File, info: UpdateInfo) {
        require(file.length() == info.sizeBytes) { "El tamaño del archivo almacenado cambió." }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == info.sha256) { "El archivo almacenado no supera SHA-256." }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageAndSigner(file: File, info: UpdateInfo) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else PackageManager.GET_SIGNATURES
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalStateException("El archivo no es un APK Android válido.")
        require(archive.packageName == app.packageName) { "El APK pertenece a otra aplicación." }
        require(packageVersionCode(archive) == info.versionCode) {
            "versionCode no coincide con el manifiesto."
        }
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        if (certificateDigests(installed) != certificateDigests(archive)) {
            throw ReleaseSignatureMismatchException()
        }
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certificateDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList().orEmpty()
        } else info.signatures?.toList().orEmpty()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    companion object {
        private const val PREFS = "silenthub_update_cache"
        private const val KEY_MANIFEST = "manifest_json"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val UPDATE_ENDPOINT = "https://silenthub-web.vercel.app/api/external-esp/update"
        private const val CHECK_CACHE_MS = 30L * 60L * 1000L
        private const val STALE_CACHE_MS = 24L * 60L * 60L * 1000L
        private const val MAX_APK_BYTES = 80L * 1024L * 1024L
        private const val INSTALL_REQUEST_CODE = 7505
    }
}