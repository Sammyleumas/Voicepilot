package com.voicepilot.core.integrations

import com.voicepilot.core.text.NameMatcher
import com.voicepilot.core.text.TextNormalizer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class InstalledApp(val label: String, val packageName: String)

/** Lists launchable apps. Implemented by the Android layer via PackageManager. */
interface AppCatalog {
    suspend fun launchableApps(): List<InstalledApp>
}

/** Resolves a spoken app name to an installed, launchable package. */
class AppResolver(private val catalog: AppCatalog) {

    suspend fun resolve(spokenName: String): InstalledApp? {
        val query = TextNormalizer.normalize(spokenName).removePrefix("the ").removeSuffix(" app").trim()
        if (query.isEmpty()) return null
        val apps = catalog.launchableApps()
        KNOWN_PACKAGES[query]?.let { pkgs ->
            pkgs.firstNotNullOfOrNull { pkg -> apps.firstOrNull { it.packageName == pkg } }?.let { return it }
        }
        val best = apps.map { it to NameMatcher.score(query, it.label) }.maxByOrNull { it.second } ?: return null
        return best.first.takeIf { best.second >= 0.7 }
    }

    companion object {
        /** Common spoken names whose label varies by device or locale. */
        val KNOWN_PACKAGES: Map<String, List<String>> = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "whatsapp business" to listOf("com.whatsapp.w4b"),
            "youtube" to listOf("com.google.android.youtube"),
            "chrome" to listOf("com.android.chrome"),
            "gmail" to listOf("com.google.android.gm"),
            "email" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "google maps" to listOf("com.google.android.apps.maps"),
            "play store" to listOf("com.android.vending"),
            "calendar" to listOf("com.google.android.calendar", "com.samsung.android.calendar"),
            "clock" to listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage"),
            "camera" to listOf("com.google.android.GoogleCamera", "com.sec.android.app.camera"),
            "telegram" to listOf("org.telegram.messenger"),
            "instagram" to listOf("com.instagram.android"),
            "facebook" to listOf("com.facebook.katana"),
            "x" to listOf("com.twitter.android"),
            "twitter" to listOf("com.twitter.android"),
            "spotify" to listOf("com.spotify.music"),
            "boomplay" to listOf("com.afmobi.boomplayer"),
            "linkedin" to listOf("com.linkedin.android"),
            "messages" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            "phone" to listOf("com.google.android.dialer", "com.samsung.android.dialer"),
            "contacts" to listOf("com.google.android.contacts", "com.samsung.android.app.contacts"),
        )
    }
}

/** Builds WhatsApp Click to Chat links (https://wa.me/<number>?text=<message>). */
object WhatsAppLinks {
    const val PACKAGE_CONSUMER = "com.whatsapp"
    const val PACKAGE_BUSINESS = "com.whatsapp.w4b"

    fun clickToChat(internationalDigits: String, message: String?): String {
        val digits = internationalDigits.filter { it.isDigit() }
        require(digits.length in 8..15) { "Invalid phone number" }
        val base = "https://wa.me/$digits"
        return if (message.isNullOrBlank()) base else "$base?text=${encode(message)}"
    }

    private fun encode(text: String): String =
        URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
}
