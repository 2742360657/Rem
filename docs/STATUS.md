# Rem 当前状态

## 规范基线

2026-09-20 已根据用户确认重置产品规范。当前唯一有效的详细规则见 [AGENT_PRODUCT_RULES.md](../AGENT_PRODUCT_RULES.md)，Library 文件模式说明见 [AGENT_LIBRARY_RULES.md](../AGENT_LIBRARY_RULES.md)。

## 本轮完成：整体重构

旧的 Tag、Collection、Work/Series/Edition 关系模型、自动识别、Organizer、Inbox、Trash、阅读进度、内置播放器、系统相册导入、批量元数据编辑、图片集排序、归档漫画阅读、日志与崩溃导出、以及旧的可移植 JSON 目录（`.gallery` v4 及 `library-tool` CLI）已全部删除，未保留任何代码路径。

应用从零重建，当前实现：

| 模块 | 位置 | 职责 |
| --- | --- | --- |
| 模型 | `model/Entry.kt`、`model/Index.kt` | 条目、项目分组、媒体类型判定、索引序列化 |
| 存储 | `storage/LibraryTree.kt` | SAF 单次投影查询列举，一次 Binder 往返读一个目录 |
| 存储 | `storage/LibraryStore.kt` | 接入的 tree URI、`index.json` 读写、`RULES.md` 覆写 |
| 媒体 | `media/MediaProbe.kt` | 图片读 EXIF，视频读容器元数据；只报告文件自带的时间和地点 |
| 扫描 | `scan/Scanner.kt` | 只读 `相册/` 直属文件与 `画集/*/` 直属文件，记录不规范项 |
| 界面 | `ui/` | 相册网格、画集项目列表、项目详情、右侧滑块、系统查看器/播放器调用 |

## 关键行为

- 接入后先展示 `.gallery/index.json` 缓存，再在后台增量扫描；扫描不阻塞阅读。
- 增量扫描只在 `size` 或修改时间变化时重读文件内容，其余沿用缓存。
- 用户点「重新扫描」触发同样路径；扫描进行中重复点击不叠加。
- 不规范内容只在界面弹窗列出，不做任何自动修正。

## 验证证据

- `.\gradlew.bat :app:testDebugUnitTest`：38 个测试通过，0 失败。
- `.\gradlew.bat :app:assembleDebug`：构建成功，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- `aapt2 dump badging`：`com.susnowy.rem.debug`，versionCode 4，versionName `0.0.4-debug`，label `Rem`。
- 合并后 Manifest 不含任何 `READ_MEDIA_*` 或存储权限。

## 未验证

- 真机上的 SAF 读取、EXIF/视频元数据解码、缩略图加载、右侧滑块手势、系统查看器调用均未在设备上验证。
- 当前只有单元测试，未重建 androidTest；涉及 Android 框架的行为没有仪器测试覆盖。
- 应用名与图标沿用旧版资源（`@mipmap/ic_launcher`），未重新设计。

## 清理结论

旧的 Tag、Collection、复杂关系模型、自动识别整理、阅读进度、复杂页面跳转、后台全盘扫描、内置播放器和未来文件编辑的预置逻辑均不再作为当前需求依据，且已从代码中移除。后续若需要，必须重新提出并确认。
