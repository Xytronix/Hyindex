// Copyright 2026 Hyve. All rights reserved.
package com.hyve.knowledge.core.diff

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Significance { HIGH, MEDIUM, LOW }

@Serializable
data class CrossRef(
    val id: String,
    val displayName: String,
    val corpus: String,
    val edgeType: String,
)

@Serializable
data class PatchNoteRef(
    val changedEntity: String,
    val patchNoteTitle: String,
)

@Serializable
data class VersionDiff(
    val versionA: String,
    val versionB: String,
    val computedAt: String,
    val summary: DiffSummary,
    val entries: List<DiffEntry>,
    val relatedPatchNotes: List<PatchNoteRef> = emptyList(),
)

@Serializable
data class DiffSummary(
    val totalAdded: Int,
    val totalRemoved: Int,
    val totalChanged: Int,
    val byCorpus: Map<String, CorpusDiffSummary>,
    val skippedCorpora: Map<String, String> = emptyMap(),
)

@Serializable
data class CorpusDiffSummary(
    val added: Int,
    val removed: Int,
    val changed: Int,
)

@Serializable
data class DiffEntry(
    val nodeId: String,
    val displayName: String,
    val corpus: String,
    val dataType: String? = null,
    val nodeType: String,
    val changeType: ChangeType,
    val filePath: String? = null,
    val detail: DiffDetail? = null,
    val significance: Significance = Significance.LOW,
    val crossRefs: List<CrossRef> = emptyList(),
)

@Serializable
sealed class DiffDetail {
    @Serializable
    @SerialName("code")
    data class Code(
        val signatureChanged: Boolean = false,
        val oldSignature: String? = null,
        val newSignature: String? = null,
        val bodyChanged: Boolean = false,
    ) : DiffDetail()

    @Serializable
    @SerialName("gamedata")
    data class GameData(
        val fieldChanges: List<FieldChange> = emptyList(),
    ) : DiffDetail()

    @Serializable
    @SerialName("client")
    data class Client(
        val contentChanged: Boolean = true,
    ) : DiffDetail()

    @Serializable
    @SerialName("docs")
    data class Docs(
        val titleChanged: Boolean = false,
        val oldTitle: String? = null,
        val newTitle: String? = null,
        val bodyChanged: Boolean = false,
        val oldPublishedDate: String? = null,
        val newPublishedDate: String? = null,
    ) : DiffDetail()
}

@Serializable
data class FieldChange(
    val field: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val changeType: ChangeType,
)

@Serializable
enum class ChangeType { ADDED, REMOVED, CHANGED }
