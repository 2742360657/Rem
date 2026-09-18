package dev.susnowy.gallery.portable

import dev.susnowy.gallery.model.*

/** The same v4 structural checks used by Android and the desktop tool. */
object PortableValidation {
    fun validate(catalog: PortableCatalog) {
        require(catalog.schemaVersion == CURRENT_SCHEMA_VERSION) { "Catalog 必须使用当前 Schema v4" }
        requireUnique("Asset ID", catalog.assets.map(PortableAsset::id))
        requireUnique("Work ID", catalog.works.map(PortableWork::id))
        requireUnique("Edition ID", catalog.editions.map(PortableEdition::id))
        requireUnique("Group ID", catalog.groups.map(PortableGroup::id))
        requireUnique("Series ID", catalog.series.map(PortableSeries::id))
        requireUnique("媒体路径", catalog.assets.map(PortableAsset::relativePath))
        catalog.assets.forEach { asset ->
            requirePortablePath(asset.relativePath)
            asset.secondaryPath?.let(::requirePortablePath)
        }
        val assetIds = catalog.assets.mapTo(mutableSetOf(), PortableAsset::id)
        val workIds = catalog.works.mapTo(mutableSetOf(), PortableWork::id)
        catalog.editions.forEach { edition ->
            require(edition.workId in workIds) { "Edition ${edition.id} 引用了不存在的 Work" }
            require(edition.assets.isNotEmpty()) { "Edition ${edition.id} 没有来源" }
            require(edition.assets.all { it.assetId in assetIds }) { "Edition ${edition.id} 引用了不存在的 Asset" }
            edition.assets.mapNotNull(PortableEditionAsset::entryPath).forEach(::requirePortablePath)
        }
        catalog.works.forEach { work ->
            require(catalog.editions.any { it.workId == work.id }) { "Work ${work.id} 没有 Edition" }
            require(
                work.preferredEditionId == null || catalog.editions.any {
                    it.id == work.preferredEditionId && it.workId == work.id
                },
            ) {
                "Work ${work.id} 的首选 Edition 不存在"
            }
            work.coverPath?.let(::requirePortablePath)
        }
        catalog.groups.forEach { group ->
            require(group.members.all { it.workId in workIds }) { "Group ${group.id} 引用了不存在的 Work" }
            requireUnique("Group ${group.id} 成员", group.members.map { it.workId })
            require(group.coverWorkId == null || group.members.any { it.workId == group.coverWorkId }) {
                "Group ${group.id} 的封面 Work 必须属于组成员"
            }
        }
        catalog.series.forEach { sequence ->
            require(sequence.members.all { it.workId in workIds }) {
                "Series ${sequence.id} 引用了不存在的 Work"
            }
            requireUnique("Series ${sequence.id} 成员", sequence.members.map(PortableSeriesMember::workId))
        }
        requireUnique(
            "Series 全局成员",
            catalog.series.flatMap { sequence -> sequence.members.map(PortableSeriesMember::workId) },
        )
    }

    fun validate(state: PortableState) {
        require(state.schemaVersion == CURRENT_SCHEMA_VERSION) { "State 必须使用当前 Schema v4" }
        requireUnique("进度 Work", state.progress.map(PortableProgress::itemId))
        requireUnique("回收站 Work", state.trash.map(PortableTrashEntry::itemId))
        state.trash.forEach { entry -> requirePortablePath(entry.relativePath) }
    }

    fun validate(inbox: PortableInbox) {
        require(inbox.schemaVersion == CURRENT_SCHEMA_VERSION) { "Inbox 决策必须使用当前 Schema v4" }
        requireUnique("Inbox 决策目标", inbox.decisions.map(PortableInboxDecision::key))
        requireUnique("Inbox 决策路径", inbox.decisions.map(PortableInboxDecision::relativePath))
        inbox.decisions.forEach { decision ->
            requirePortablePath(decision.relativePath)
            decision.workId?.let { require(it.isNotBlank()) { "Inbox 决策的 Work ID 不能为空" } }
        }
    }

    private fun requirePortablePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path) {
            "媒体路径必须是 Library 内的相对路径：$path"
        }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "媒体路径包含无效片段：$path"
        }
    }

    private fun requireUnique(label: String, values: List<String>) {
        require(values.distinct().size == values.size) { "$label 必须唯一" }
    }

}
