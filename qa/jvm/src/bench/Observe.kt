package com.askthecar.qa.bench

import com.askthecar.CrashFlow
import com.askthecar.Consent
import java.io.File

/** What the app would show / has on disk for one incident folder. Shared by the in-process snapshot and the restart probe. */
data class AppView(
    val driveConsent: Boolean,
    val incidentDirectoryExists: Boolean,
    val incidentFiles: List<String>,
    val visibleIncidentCount: Int,
    val currentIncidentEpochSeconds: Double?,
    val crashesRoot: List<String>,
) {
    fun encode() = listOf(
        "driveConsent=$driveConsent", "dirExists=$incidentDirectoryExists", "files=${incidentFiles.joinToString("|")}",
        "visible=$visibleIncidentCount", "current=${currentIncidentEpochSeconds ?: ""}", "root=${crashesRoot.joinToString("|")}",
    ).joinToString("\n")

    companion object {
        fun decode(text: String): AppView {
            val m = text.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            fun list(k: String) = m[k].orEmpty().split('|').filter { it.isNotEmpty() }
            return AppView(m.getValue("driveConsent").toBoolean(), m.getValue("dirExists").toBoolean(), list("files"),
                m.getValue("visible").toInt(), m["current"]?.takeIf { it.isNotEmpty() }?.toDouble(), list("root"))
        }
    }
}

/**
 * visibleIncidentCount = distinct incident folders the UI can show: the in-progress incident (CrashFlow.incident,
 * keyed by CrashFlow.dir), the loaded record (CrashFlow.record), and every crashes/<name>/ holding log.json
 * (the condition CarHome.pastCrashes and CrashWatcher.loadLatest use to list a folder).
 */
fun observe(ctx: QaContext, incidentDirName: String): AppView {
    val crashes = File(ctx.filesDir, "crashes")
    val dir = File(crashes, incidentDirName)
    val names = linkedSetOf<String>()
    CrashFlow.incident?.let { names += (CrashFlow.dir?.name ?: "in-memory@${it.t}") }
    CrashFlow.record?.let { names += it.dir.name }
    crashes.listFiles()?.filter { File(it, "log.json").exists() }?.forEach { names += it.name }
    return AppView(
        driveConsent = Consent.allowed(Consent.Item.DRIVE),
        incidentDirectoryExists = dir.exists(),
        incidentFiles = if (dir.exists()) dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList() else emptyList(),
        visibleIncidentCount = names.size,
        currentIncidentEpochSeconds = CrashFlow.incident?.t,
        crashesRoot = crashes.list()?.sorted().orEmpty(),
    )
}
