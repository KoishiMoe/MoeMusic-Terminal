package org.lolicode.moemusic.terminal

import org.lolicode.moemusic.core.plugin.PluginDiscoveryReport

class PluginIssueException(
    val report: PluginDiscoveryReport,
) : RuntimeException(formatReport(report)) {

    companion object {
        fun formatReport(report: PluginDiscoveryReport): String = buildString {
            appendLine("================================================================================")
            appendLine("MoeMusic Terminal - Plugin issues detected:")
            if (report.incompatiblePlugins.isNotEmpty()) {
                appendLine("\nIncompatible Plugins:")
                for (inc in report.incompatiblePlugins) {
                    appendLine("  - [${inc.pluginId}] v${inc.version}: ${inc.reason}")
                    if (inc.filePath != null) {
                        appendLine("    Path: ${inc.filePath}")
                    }
                }
            }
            if (report.duplicatePlugins.isNotEmpty()) {
                appendLine("\nDuplicate Plugins:")
                for (dup in report.duplicatePlugins) {
                    appendLine("  - [${dup.pluginId}] Multiple versions found:")
                    appendLine("    Selected: v${dup.selected.plugin.version} (${dup.selected.filePath ?: dup.selected.origin})")
                    for (skipped in dup.skipped) {
                        appendLine("    Skipped:  v${skipped.plugin.version} (${skipped.filePath ?: skipped.origin})")
                    }
                }
            }
            if (report.failedPlugins.isNotEmpty()) {
                appendLine("\nCorrupt or Unreadable Plugin Jars:")
                for (fail in report.failedPlugins) {
                    appendLine("  - Jar: ${fail.jarPath.fileName}")
                    appendLine("    Error: ${fail.message}")
                    appendLine("    Path:  ${fail.jarPath}")
                }
            }
            appendLine("\nRefusing to start with plugin issues (terminal platform fails closed).")
            appendLine("Please resolve the issues in your plugin directory and restart.")
            append("================================================================================")
        }
    }
}
