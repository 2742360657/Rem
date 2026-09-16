# Rem

Rem 是一个 Android 本地优先媒体库，当前版本为 **0.0.3**。用户通过系统目录选择器接入实体文件夹；媒体保持在原位置，`.gallery/` 保存可随硬盘移动、可供 Agent 理解的元数据和规则，本机 SQLite 只作为可重建索引。

正式应用 ID 为 `com.susnowy.rem`。它会与早期使用 `dev.susnowy.gallery` 的开发版并存，不能直接继承旧应用的本机索引和 SAF 目录授权；重新接入原 Library 后，`.gallery/` 中的标签、分类和进度仍可恢复。Kotlin 包名、`GALLERY_LIBRARY.md` 和 Gallery Schema 等内部格式标识暂不重命名。

当前一级界面只有三类：

- **相册**：照片和相册视频的纯净时间线。
- **图片 / 视频**：按真实目录分类的普通媒体相册。
- **漫画 / 动漫**：带作者、Tag、Series、搜索、排序和阅读/观看进度的作品库。

各主页面把筛选、目录和作品操作放在自己的右侧工具栏中，左侧只保留 Library 与全局管理。页面切换会保留各自状态；漫画采用“作品列表 → 详情与编辑 → 沉浸阅读”的操作层级，缩略图和漫画页均支持长按快捷操作。

扫描器额外识别常见下载结构：`JM/<纯数字 ID>/`、EhViewer 的
`<gid>-<title>/.ehviewer`，以及 Pixiv 的 `<illust_id>_pN` / `_ugoira…`。
识别结果保留稳定来源 ID。当前版本不内置站点抓取；需要补全标题、作者或标签时，可让 Agent 按 Library 自带规则辅助同步，所有标记为 `manual` 的人工字段始终保持不变，站点 Cookie 也不会写入 Library。

图片显示支持 GIF、Animated WebP（Android 9+）、SVG，以及系统可解码的
HEIF/AVIF/DNG 等格式。缩略图固定按显示尺寸解码；超大静态图会先探测尺寸并安全降采样，避免整张原图直接解码造成内存溢出。

详细设计见 `Gallery_Project_Guide.md`，使用方法见 `docs/USER_GUIDE.md`，架构约束见 `docs/ARCHITECTURE.md`，版本变化见 `CHANGELOG.md`，开发过程中的问题与解决措施见 `docs/DEV_LOG.md`。

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease
```

设置页提供可导出的诊断日志（`设置 → 诊断日志`）；日志只写在本机 App 私有目录，不进入 Library。`assembleRelease` 会把 R8 mapping 归档到 `dist/Rem-<版本>-mapping.txt`，用于还原真机崩溃堆栈。

可直接安装的开发 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。正式构建默认从 `T:/jks/keystore.properties` 读取仓库外签名配置，也可用 Gradle 属性 `rem.signingProperties` 或环境变量 `REM_SIGNING_PROPERTIES` 指定其他位置；密码和 JKS 不得提交到仓库。
