# Rem

Rem 是一个面向移动硬盘和大容量本地目录的 Android 媒体库。它管理图片、视频、写真集、漫画和系列，同时让原媒体继续保持普通文件形态。

当前项目处于首次稳定发布前，便携格式为 **Schema v4**。

## 核心特性

- 使用 Android Storage Access Framework 接入一个或多个目录；
- `.gallery/` 随 Library 保存身份、逻辑关系、人工元数据、进度、回收站和可恢复事务；
- 本机 SQLite、日志、扫描补全队列和缩略图都是可重建或可清除数据；
- 新媒体和不确定内容先进入 Inbox；
- 支持普通图片、目录 ImageSet、ZIP/CBZ、视频和系统相册复制导入；
- 支持 Series 书架、手动顺序、季/集、卷/章；
- 同目录图片集和直属视频可以作为一个混合入口浏览；
- 扫描先快速清点，再分批补全媒体信息，App 被结束后可以续做；
- 逻辑回收站、永久删除核验和 Organizer 真实文件事务相互分离；
- 浏览过的卡片按需保存 512 px 本机离线预览，限制为 256 MiB / 20,000 张。

Schema v4 将便携目录拆分为：

```text
Asset    物理来源
Work     用户编辑的逻辑作品
Edition  同一作品的取得版本
Group    一起浏览的作品集合
Series   有顺序的作品序列
```

目前 Android 界面仍通过兼容投影使用这些实体。Group 手动编辑、Edition 比较和虚拟合并是下一阶段，不应误认为已经完成。

## 文档

- [产品与便携格式规范](Gallery_Project_Guide.md)
- [开发 Agent 规则](AGENTS.md)
- [当前架构](docs/ARCHITECTURE.md)
- [使用说明](docs/USER_GUIDE.md)
- [开发记录](docs/DEV_LOG.md)
- [版本变化](CHANGELOG.md)

Library 接入后还会在根目录生成 `GALLERY_LIBRARY.md`，供本地 Agent 在整理该 Library 前阅读。它说明 Schema v4 实体、人工字段保护、来源标签、备份和文件安全边界。

## 构建与检查

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
```

Debug 包使用 `com.susnowy.rem.debug`，可以与 Release 包并存。涉及 SAF、Schema 转换、媒体解码或导航生命周期时，还应运行真机 AndroidJUnitRunner。

## 数据安全摘要

- 扫描、识别、改元数据和逻辑分组不会移动媒体；
- 真实文件变化必须先预览并确认；
- 所有便携路径都是 Library 相对路径；
- `manual` 字段不会被自动识别或 Agent 覆盖；
- 未知更高 Schema 会拒绝写入；
- Cookie、Token 和账号信息不得进入 Library、日志或 Git；
- 不要手动删除 `.gallery/`、`.nomedia` 或活动事务。
