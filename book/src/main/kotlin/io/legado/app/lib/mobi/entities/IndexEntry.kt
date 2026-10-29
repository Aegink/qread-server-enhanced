package io.legado.app.lib.mobi.entities

data class IndexEntry(
    val label: String,
    val tags: List<IndexTag>,
    val tagMap: Map<Int, IndexTag>
)
