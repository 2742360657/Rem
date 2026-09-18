package dev.susnowy.gallery.model

/** A confirmation authorizes the displayed source and trash event, never a later replacement. */
object TrashRules {
    /** Reject a Work whose physical sources are shared, nested or not fully shown by its card. */
    fun requireExclusiveSource(item: MediaItem, catalog: PortableCatalog) {
        val ownAssetIds = catalog.editions.filter { it.workId == item.id }
            .flatMap { it.assets }.mapTo(mutableSetOf()) { it.assetId }
        val ownAssets = catalog.assets.filter { it.id in ownAssetIds }
        check(ownAssets.isNotEmpty() && ownAssets.all {
            it.relativePath == item.relativePath && it.secondaryPath == item.secondaryPath
        }) { "作品包含多个来源，请先检查 Edition；不能从单一路径卡片永久删除" }
        val otherIds = catalog.editions.filter { it.workId != item.id }
            .flatMap { it.assets }.mapTo(mutableSetOf()) { it.assetId }
        val deleting = listOfNotNull(item.relativePath, item.secondaryPath)
        check(catalog.assets.filter { it.id in otherIds }.none { asset ->
            listOfNotNull(asset.relativePath, asset.secondaryPath).any { path ->
                deleting.any { target ->
                    path == target || path.startsWith("$target/") || target.startsWith("$path/")
                }
            }
        }) { "来源仍被其他作品或 Edition 引用，请先处理关联，避免删除共享媒体" }
    }

    fun requireUnchanged(confirmed: MediaItem, current: MediaItem) {
        check(current.trashed && confirmed.trashed &&
            current.id == confirmed.id && current.libraryId == confirmed.libraryId &&
            current.relativePath == confirmed.relativePath && current.secondaryPath == confirmed.secondaryPath &&
            current.sourceKind == confirmed.sourceKind && current.size == confirmed.size &&
            current.modifiedAt == confirmed.modifiedAt && current.deletedAt == confirmed.deletedAt &&
            current.revision == confirmed.revision
        ) { "项目已变化，请重新打开回收站并确认删除" }
    }

    fun needsReview(item: MediaItem, days: Int, now: Long): Boolean =
        days > 0 && item.trashed && item.deletedAt?.let {
            it <= now - days.toLong() * 86_400_000L
        } == true
}
