// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.knowledge.index


data class BindingCandidate(
    val clientNodeId: String,
    val candidateText: String,
    val strategy: String,
    val confidence: Float,
)


class UIContentAnalyzer {

    companion object {

        private val RESOURCE_PATH_PATTERN = Regex(
            """["'](\w+/[\w/]+)["']""",
        )


        private val PASCAL_CASE_PATTERN = Regex(
            """\b([A-Z][a-z]+(?:[A-Z][a-z]+)+)\b""",
        )


        private val JSON_KEY_PATTERN = Regex(
            """"(item|recipe|block|npc|entity|slot|inventory|equipment|weapon|armor|tool|resource|prefab)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )


        private val PASCAL_CASE_EXCLUSIONS = setOf(
            "DataContext", "DataTemplate", "StackPanel", "DockPanel", "GridPanel",
            "TextBlock", "TextBox", "ScrollViewer", "ContentControl", "UserControl",
            "ItemsControl", "ResourceDictionary", "SolidColorBrush", "LinearGradientBrush",
            "ColumnDefinition", "RowDefinition", "ContentPresenter", "TemplateBinding",
            "StaticResource", "DynamicResource", "EventTrigger", "DataTrigger",
            "MultiBinding", "RelativeSource", "TargetType", "BasedOn",
            "HorizontalAlignment", "VerticalAlignment", "BorderThickness",
        )
    }


    fun analyze(content: String, nodeId: String): List<BindingCandidate> {
        val candidates = mutableListOf<BindingCandidate>()

        candidates += extractResourcePaths(content, nodeId)
        candidates += extractPascalCaseIdentifiers(content, nodeId)
        candidates += extractFilenameStem(content, nodeId)
        candidates += extractJsonKeyReferences(content, nodeId)

        return candidates
    }


    private fun extractResourcePaths(content: String, nodeId: String): List<BindingCandidate> {
        return RESOURCE_PATH_PATTERN.findAll(content)
            .map { match ->
                val path = match.groupValues[1]

                val stem = path.substringAfterLast('/')
                BindingCandidate(
                    clientNodeId = nodeId,
                    candidateText = stem,
                    strategy = "resource_path",
                    confidence = 0.8f,
                )
            }
            .toList()
    }


    private fun extractPascalCaseIdentifiers(content: String, nodeId: String): List<BindingCandidate> {
        return PASCAL_CASE_PATTERN.findAll(content)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it !in PASCAL_CASE_EXCLUSIONS }
            .map { identifier ->
                BindingCandidate(
                    clientNodeId = nodeId,
                    candidateText = identifier,
                    strategy = "pascal_case",
                    confidence = 0.5f,
                )
            }
            .toList()
    }


    private fun extractFilenameStem(content: String, nodeId: String): List<BindingCandidate> {

        val withoutPrefix = nodeId.substringAfter(':')
        val filename = withoutPrefix.substringAfterLast('/').substringBeforeLast('.')
        if (filename.isBlank()) return emptyList()

        return listOf(
            BindingCandidate(
                clientNodeId = nodeId,
                candidateText = filename,
                strategy = "filename_stem",
                confidence = 0.4f,
            ),
        )
    }


    private fun extractJsonKeyReferences(content: String, nodeId: String): List<BindingCandidate> {
        return JSON_KEY_PATTERN.findAll(content)
            .map { match ->
                val value = match.groupValues[2]

                val stem = value.substringAfterLast('/').substringBeforeLast('.')
                BindingCandidate(
                    clientNodeId = nodeId,
                    candidateText = stem,
                    strategy = "json_key",
                    confidence = 0.6f,
                )
            }
            .toList()
    }
}
