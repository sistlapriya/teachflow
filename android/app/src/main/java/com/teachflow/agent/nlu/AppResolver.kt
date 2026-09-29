package com.teachflow.agent.nlu

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class LaunchableApp(val label: String, val packageName: String)

/** Lists launchable apps (needs the <queries> element in the manifest on Android 11+). */
class AppResolver(private val context: Context) {
    @Volatile private var cache: List<LaunchableApp>? = null

    fun apps(): List<LaunchableApp> = cache ?: load().also { cache = it }

    fun refresh() { cache = null }

    fun byLabel(label: String?): LaunchableApp? =
        label?.let { l -> apps().firstOrNull { it.label.equals(l, ignoreCase = true) } }

    fun byPackage(pkg: String): LaunchableApp? = apps().firstOrNull { it.packageName == pkg }

    /** Packages of home-screen launchers, used by the relevance filter. */
    fun launcherPackages(): Set<String> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
    }

    private fun load(): List<LaunchableApp> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun launchFresh(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        // Start from the app's entry screen so replay begins where teaching began.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        return true
    }
}
