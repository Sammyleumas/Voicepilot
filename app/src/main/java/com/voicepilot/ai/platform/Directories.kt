package com.voicepilot.ai.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import com.voicepilot.core.contacts.Contact
import com.voicepilot.core.contacts.ContactDirectory
import com.voicepilot.core.contacts.PhoneNumber
import com.voicepilot.core.integrations.AppCatalog
import com.voicepilot.core.integrations.InstalledApp
import com.voicepilot.core.text.TextNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads names and phone numbers from the on-device address book. Only the
 * columns needed for matching are queried; nothing is cached or uploaded.
 */
@Singleton
class AndroidContactDirectory @Inject constructor(
    @ApplicationContext private val context: Context,
) : ContactDirectory {

    override suspend fun search(query: String): List<Contact> = withContext(Dispatchers.IO) {
        val tokens = TextNormalizer.tokens(query).filter { it.length >= 2 }
        if (tokens.isEmpty()) return@withContext emptyList()
        // Pre-filter in SQL on the first letters of each token, then score in memory.
        val selection = tokens.joinToString(" OR ") { "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} LIKE ?" }
        val args = tokens.map { "%${it.take(3)}%" }.toTypedArray()
        val byId = linkedMapOf<String, MutableList<PhoneNumber>>()
        val names = mutableMapOf<String, String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            ),
            selection,
            args,
            null,
        )?.use { c ->
            while (c.moveToNext() && byId.size < MAX_CONTACTS) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val number = c.getString(2) ?: continue
                val type = c.getInt(3)
                val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, type, "").toString()
                names[id] = name
                byId.getOrPut(id) { mutableListOf() } += PhoneNumber(number, label.ifBlank { null }, c.getInt(4) == 1)
            }
        }
        byId.map { (id, phones) -> Contact(id, names.getValue(id), phones) }
    }

    private companion object {
        const val MAX_CONTACTS = 300
    }
}

/** Lists launchable apps visible through the manifest's <queries> launcher intent. */
@Singleton
class AndroidAppCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppCatalog {
    @Volatile private var cache: Pair<Long, List<InstalledApp>>? = null

    override suspend fun launchableApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.takeIf { now - it.first < CACHE_MS }?.let { return@withContext it.second }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        val apps = resolved
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        cache = now to apps
        apps
    }

    @Suppress("DEPRECATION")
    fun isInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            context.packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private companion object {
        const val CACHE_MS = 60_000L
    }
}
