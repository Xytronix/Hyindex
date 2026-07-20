// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.core.diff

import kotlinx.serialization.json.Json


object DiffExporter {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun toJson(diff: VersionDiff): String = json.encodeToString(VersionDiff.serializer(), diff)

    fun toMarkdown(diff: VersionDiff): String = buildString {
        appendLine("# Version Diff: ${diff.versionA} vs ${diff.versionB}")
        appendLine()
        appendLine("Computed: ${diff.computedAt}")
        appendLine()


        val highlights = diff.entries
            .filter { it.significance == Significance.HIGH }
            .distinctBy { Triple(it.displayName, it.corpus, it.changeType) }
        if (highlights.isNotEmpty()) {
            appendLine("## Highlights")
            appendLine()
            for (entry in highlights) {
                appendLine("- **${entry.displayName}** (${entry.corpus}, ${entry.changeType.name})")
            }
            appendLine()
        }


        appendLine("## Summary")
        appendLine()
        appendLine("| | Added | Removed | Changed |")
        appendLine("|---|---|---|---|")
        appendLine("| **Total** | ${diff.summary.totalAdded} | ${diff.summary.totalRemoved} | ${diff.summary.totalChanged} |")
        for ((corpus, stats) in diff.summary.byCorpus) {
            appendLine("| $corpus | ${stats.added} | ${stats.removed} | ${stats.changed} |")
        }
        if (diff.summary.skippedCorpora.isNotEmpty()) {
            appendLine("**Skipped corpora:**")
            for ((corpus, reason) in diff.summary.skippedCorpora) {
                appendLine("- $corpus: $reason")
            }
            appendLine()
        }


        val significanceOrder = compareBy<DiffEntry> { entry ->
            when (entry.significance) {
                Significance.HIGH -> 0
                Significance.MEDIUM -> 1
                Significance.LOW -> 2
            }
        }
        val byCorpus = diff.entries.groupBy { it.corpus }
        for ((corpus, entries) in byCorpus) {
            appendLine("## $corpus")
            appendLine()

            val grouped = entries.groupBy { it.changeType }
            for (changeType in listOf(ChangeType.ADDED, ChangeType.REMOVED, ChangeType.CHANGED)) {
                val group = (grouped[changeType] ?: continue).sortedWith(significanceOrder)
                appendLine("### ${changeType.name} (${group.size})")
                appendLine()

                for (entry in group) {
                    appendLine("- **${entry.displayName}** (`${entry.nodeType}`)")
                    if (entry.filePath != null) {
                        appendLine("  - File: `${entry.filePath}`")
                    }

                    when (val detail = entry.detail) {
                        is DiffDetail.Code -> {
                            if (detail.signatureChanged) {
                                appendLine("  - Signature changed:")
                                detail.oldSignature?.let { appendLine("    - Old: `$it`") }
                                detail.newSignature?.let { appendLine("    - New: `$it`") }
                            }
                            if (detail.bodyChanged && !detail.signatureChanged) {
                                appendLine("  - Body changed")
                            }
                        }
                        is DiffDetail.GameData -> {
                            if (detail.fieldChanges.isNotEmpty()) {
                                appendLine("  - Field changes:")
                                for (fc in detail.fieldChanges.take(10)) {
                                    val desc = when (fc.changeType) {
                                        ChangeType.ADDED -> "+ `${fc.field}` = `${fc.newValue}`"
                                        ChangeType.REMOVED -> "- `${fc.field}` (was `${fc.oldValue}`)"
                                        ChangeType.CHANGED -> "~ `${fc.field}`: `${fc.oldValue}` -> `${fc.newValue}`"
                                    }
                                    appendLine("    - $desc")
                                }
                                if (detail.fieldChanges.size > 10) {
                                    appendLine("    - ... and ${detail.fieldChanges.size - 10} more")
                                }
                            }
                        }
                        is DiffDetail.Client -> {
                            appendLine("  - Content changed")
                        }
                        is DiffDetail.Docs -> {
                            if (detail.titleChanged) {
                                appendLine("  - Title changed:")
                                detail.oldTitle?.let { appendLine("    - Old: `$it`") }
                                detail.newTitle?.let { appendLine("    - New: `$it`") }
                            }
                            if (detail.bodyChanged) {
                                appendLine("  - Body changed")
                            }
                            if (detail.oldPublishedDate != detail.newPublishedDate) {
                                appendLine("  - Published date: `${detail.oldPublishedDate}` -> `${detail.newPublishedDate}`")
                            } else if (detail.newPublishedDate != null) {
                                appendLine("  - Published: `${detail.newPublishedDate}`")
                            }
                        }
                        null -> {}
                    }


                    if (entry.crossRefs.isNotEmpty()) {
                        for (ref in entry.crossRefs) {
                            appendLine("  - Referenced by: ${ref.displayName} (${ref.corpus}, ${ref.edgeType})")
                        }
                    }
                }
                appendLine()
            }
        }


        if (diff.relatedPatchNotes.isNotEmpty()) {
            appendLine("## Related patch notes")
            appendLine()
            for (ref in diff.relatedPatchNotes) {
                appendLine("- ${ref.changedEntity} → ${ref.patchNoteTitle}")
            }
            appendLine()
        }
    }
}
