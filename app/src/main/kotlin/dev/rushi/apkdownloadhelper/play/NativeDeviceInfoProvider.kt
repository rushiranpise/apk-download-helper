package dev.rushi.apkdownloadhelper.play

import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.text.TextUtils
import java.util.Locale
import java.util.Properties

class NativeDeviceInfoProvider(context: Context) : ContextWrapper(context) {
    fun getNativeDeviceProperties(): Properties {
        val properties = Properties().apply {
            setProperty("UserReadableName", "${Build.DEVICE}-default")
            setProperty("Build.HARDWARE", Build.HARDWARE)
            setProperty("Build.RADIO", Build.getRadioVersion() ?: "unknown")
            setProperty("Build.FINGERPRINT", Build.FINGERPRINT)
            setProperty("Build.BRAND", Build.BRAND)
            setProperty("Build.DEVICE", Build.DEVICE)
            setProperty("Build.VERSION.SDK_INT", "${Build.VERSION.SDK_INT}")
            setProperty("Build.VERSION.RELEASE", Build.VERSION.RELEASE)
            setProperty("Build.MODEL", Build.MODEL)
            setProperty("Build.MANUFACTURER", Build.MANUFACTURER)
            setProperty("Build.PRODUCT", Build.PRODUCT)
            setProperty("Build.ID", Build.ID)
            setProperty("Build.BOOTLOADER", Build.BOOTLOADER)

            val config = applicationContext.resources.configuration
            setProperty("TouchScreen", "${config.touchscreen}")
            setProperty("Keyboard", "${config.keyboard}")
            setProperty("Navigation", "${config.navigation}")
            setProperty("ScreenLayout", "${config.screenLayout and 15}")
            setProperty("HasHardKeyboard", "${config.keyboard == Configuration.KEYBOARD_QWERTY}")
            setProperty("HasFiveWayNavigation", "${config.navigation == Configuration.NAVIGATIONHIDDEN_YES}")

            val metrics = applicationContext.resources.displayMetrics
            setProperty("Screen.Density", "${metrics.densityDpi}")
            setProperty("Screen.Width", "${metrics.widthPixels}")
            setProperty("Screen.Height", "${metrics.heightPixels}")

            setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(","))
            setProperty("Features", getFeatures().joinToString(","))
            setProperty("Locales", getLocales().joinToString(","))
            setProperty("SharedLibraries", getSharedLibraries().joinToString(","))

            val activityManager = applicationContext.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            setProperty("GL.Version", activityManager.deviceConfigurationInfo.reqGlEsVersion.toString())
            setProperty("GL.Extensions", EglExtensionProvider.eglExtensions.joinToString(","))

            setProperty("Client", "android-google")
            setProperty("GSF.version", "203615037")
            setProperty("Vending.version", "82201710")
            setProperty("Vending.versionString", "22.0.17-21 [0] [PR] 332555730")
            setProperty("Roaming", "mobile-notroaming")
            setProperty("TimeZone", "UTC-10")
            setProperty("CellOperator", "310")
            setProperty("SimOperator", "38")
        }

        if (isHuawei()) stripHuaweiProperties(properties)
        return properties
    }

    private fun getFeatures(): List<String> =
        runCatching {
            applicationContext.packageManager.systemAvailableFeatures
                .mapNotNull { it.name?.takeIf(String::isNotEmpty) }
        }.getOrDefault(emptyList())

    private fun getLocales(): List<String> {
        val locales = mutableListOf<String>()
        locales.addAll(applicationContext.assets.locales)
        return locales
            .filterNot { TextUtils.isEmpty(it) }
            .map { it.replace("-", "_") }
    }

    private fun getSharedLibraries(): List<String> =
        runCatching {
            applicationContext.packageManager.systemSharedLibraryNames?.toList().orEmpty()
        }.getOrDefault(emptyList())

    private fun stripHuaweiProperties(properties: Properties) {
        properties["Build.HARDWARE"] = "lynx"
        properties["Build.BOOTLOADER"] = "lynx-1.0-9716681"
        properties["Build.BRAND"] = "google"
        properties["Build.DEVICE"] = "lynx"
        properties["Build.MODEL"] = "Pixel 7a"
        properties["Build.MANUFACTURER"] = "Google"
        properties["Build.PRODUCT"] = "lynx"
        properties["Build.ID"] = "TQ2A.230505.002"
    }
}

private fun isHuawei(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.getDefault()).contains("huawei") ||
        Build.HARDWARE.lowercase(Locale.getDefault()).contains("kirin") ||
        Build.HARDWARE.lowercase(Locale.getDefault()).contains("hi3")
