package com.phonepc.explorer.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import java.io.File

/**
 * Reads an APK's manifest through PackageManager instead of parsing binary
 * AXML by hand – the platform already does that correctly.
 */
object ApkInspector {

    data class Info(
        val label: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val permissions: List<String>,
        val icon: Drawable?,
        val size: Long
    )

    fun inspect(context: Context, apk: File): Info? {
        val pm = context.packageManager
        val flags = PackageManager.GET_PERMISSIONS
        val pi = try {
            pm.getPackageArchiveInfo(apk.absolutePath, flags)
        } catch (e: Exception) {
            null
        } ?: return null
        val ai: ApplicationInfo = pi.applicationInfo ?: return null

        // loadLabel / loadIcon need the source dirs pointed at the archive
        ai.sourceDir = apk.absolutePath
        ai.publicSourceDir = apk.absolutePath

        val label = try {
            ai.loadLabel(pm).toString()
        } catch (e: Exception) {
            pi.packageName ?: apk.name
        }
        val icon = try {
            ai.loadIcon(pm)
        } catch (e: Exception) {
            null
        }

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }

        val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ai.minSdkVersion else 0
        val targetSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ai.targetSdkVersion else 0

        return Info(
            label = label,
            packageName = pi.packageName ?: "",
            versionName = pi.versionName ?: "",
            versionCode = versionCode,
            minSdk = minSdk,
            targetSdk = targetSdk,
            permissions = pi.requestedPermissions?.toList().orEmpty(),
            icon = icon,
            size = apk.length()
        )
    }
}
