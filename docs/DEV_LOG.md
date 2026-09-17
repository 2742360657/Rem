# 开发记录

按 `Gallery_Project_Guide.md` 31.5 维护：每次内容开发、性能优化或缺陷修复，记录**遇到的问题**与**解决措施**。

这里写过程与判断依据；面向使用者的版本变化写 `CHANGELOG.md`，两处不重复。

条目按时间倒序。同一问题的后续进展追加到原条目，不另起新条。

---

## 2026-09-17 · 有界的本机离线小预览

**范围**：`media`、`data`、`ui`；便携 Schema 不变

### 问题与取舍

- Library 位于可拔出的移动介质上；离线时本机索引仍能显示标题和路径，但目录图片集、压缩漫画与视频封面依赖原介质，难以辨认。
- 一次性为约 7.6 万个文件生成缩略图会放大首次扫描成本，也会制造不可控的本机占用；把缩略图写入 `.gallery` 又会污染便携真相并增加移动盘写入。

### 措施

- 新增 `OfflinePreviewStore`，仅在媒体卡片实际进入界面时按需生成最长边 512 px、质量 80 的 JPEG。支持普通图片、目录图片集首图、ZIP/CBZ 首图和视频关键帧。
- 预览放在 `noBackupFilesDir`，不参与系统备份、不写入 Library。缓存键包含 Library、条目、修改时间与封面来源；原内容变化后不会继续使用旧预览。
- 总量限制为 256 MiB / 20,000 张；超过任一上限时按最近使用时间清理到 90%，避免每新增一张都全量排序。刚写入的预览在本轮清理中受保护。
- 设置页显示当前数量与体积，并提供安全清除；清除只删除派生预览。
- 项目处于测试期：未稳定发布的旧 Schema 不作为长期兼容负担。可以在备份元数据后选择更干净的新模型并重建本机索引，但任何格式调整都不得改动媒体原文件。该约定已写入 `AGENTS.md` 与项目指南。

### 验证

- `testDebugUnitTest`：**105 项通过，0 失败，0 跳过**；新增用例锁定双重容量约束、按最旧访问清理和本轮新预览保护。
- `lintDebug`：**0 errors、31 warnings**；没有新增警告类别；`assembleDebug` 与 `assembleDebugAndroidTest` 成功。
- 真机 Xiaomi 23127PN0CC / Android 16：`AndroidJUnitRunner` **7 项通过，0 失败**。新增用例通过真实 `ContentResolver` 写入 1200×600 PNG，生成最长边 128 px 的测试预览，确认二次命中同一缓存，清除后源图仍存在。
- 最新 debug 覆盖安装成功，冷启动 784 ms，进程保持运行；退出历史只有 APK 更新与测试结束产生的正常停止。

### 剩余风险

- Android `BitmapFactory` 不能为 SVG/部分新图片格式生成离线 JPEG；这些项目仍使用现有 Coil 显示路径，后续可按实际样本补充解码适配。
- 真实 E 盘当前未挂载到手机；尚未验证大批卡片首次滚动时的视频取帧速度与最终命中率。

---

## 2026-09-17 · 混合图片/视频目录的统一分组浏览

**范围**：`scanner`、`data`、`ui`；便携 Schema v3 不变

### 现场与问题

- 对真实 Library 的只读盘点确认有 272 个图片集目录同时包含 320 个直属视频。此前扫描会生成一个 `IMAGE_SET` 和若干独立 `VIDEO`，但它们只在作品库中各自显示，无法作为一次拍摄/写真内容连续浏览。
- 不能仅凭“同一作者”或相似标题永久合并，也不能为了展示关系移动文件；现有 Schema 又没有 Group/Edition 文档，所以本阶段不能把推断伪装成便携用户决定。

### 措施

- 增加只读的 `MixedMediaPresentation`：仅把同一 Library、目录型 `IMAGE_SET` 与该目录的直属视频组成派生组；嵌套视频、其他目录视频和压缩包不会误并入。
- “图片 / 视频”新增“分组”页签。每组只显示一个图集入口和图片/视频数量；进入后可打开完整图片集或逐个播放附带视频。组内视频从独立视频页隐藏，避免重复入口。
- 含视频的图片叶子目录默认归入 `classified`，但 `Comics/`、`ImageSets/`、`Works/`、`Manga/`、`漫画/`、`作品/` 等明确作品根目录仍保留在作品库。用户已经手动修改过的 domain 始终优先，自动旧建议则允许扫描器纠正。
- 不写 Group 元数据，不改便携 Schema，不移动或重命名任何媒体；未来引入可编辑 Group 时，可以用明确关系替换当前派生展示。

### 验证

- `testDebugUnitTest`：**103 项通过，0 失败，0 跳过**；新增用例覆盖直属视频自然排序、嵌套/异目录排除、压缩包不伪装成混合组，以及作品根目录的归属边界。
- `lintDebug`：**0 errors、31 warnings**；`assembleDebug` 与 `assembleDebugAndroidTest` 成功。
- 真机 Xiaomi 23127PN0CC / Android 16：覆盖安装成功；`AndroidJUnitRunner` **6 项通过，0 失败**；最新 debug 冷启动 673 ms，进程保持运行，退出记录只有测试结束与 APK 更新产生的正常停止。

### 剩余风险

- 当前手机没有挂载 E 盘，尚未用真实 272 个混合目录检查分组封面、目录层级与滚动密度。
- 派生组不能手动增删成员、跨目录组合或保存自定义顺序；画集版本比较、重复页识别与虚拟/物理合并仍需后续便携 Group/Edition 模型。

---

## 2026-09-17 · 大库扫描分阶段提交与可恢复补全

**范围**：`scanner`、`data`、`ui`、本机数据库 v6；便携 Schema v3 不变

### 现场与根因

- 新安装的 `0.0.3-debug` 接入约 464.92 GiB、76,517 个文件的真实 Library 后扫描约 25 分钟仍未完成，随后在 17:18:40 被 MIUI 最近任务“上划清理”强制停止；系统退出原因是 `USER REQUESTED / FORCE STOP`，没有崩溃、ANR 或 OOM。
- 被结束前应用仍在访问 `IContentProvider`。日志缓冲区最后约一分钟记录到 179 次超过 200 ms 的 provider 调用，平均 271.5 ms、最大 5.785 s，证明任务在推进但被大量慢 SAF 调用拖住。
- 原扫描把目录清点、压缩包遍历、哈希与 EXIF/视频元数据读取串成一个长过程，并在全部结束后才提交媒体索引。进程被结束时既看不到已发现内容，也没有可恢复的深度读取检查点。

### 措施

- 扫描拆成两个阶段：`INVENTORY` 只遍历目录、分类路径并提交可浏览索引，不打开媒体 payload；哈希、ZIP/CBZ 页数和 ComicInfo、相册拍摄信息转入逐项补全。
- 本机数据库升级到 v6，新增可丢弃的 `scan_enrichment` 队列。队列用路径、大小、修改时间与状态区分未完成和已完成结果，不写入 `.gallery/`，也不改变便携 Schema。
- 补全按 24 项一批同时提交媒体技术字段与完成检查点；取消或进程死亡最多重复尚未提交的一批。失败项本轮隔离，下次显式扫描重新排队，不会形成无限重试。
- 应用重新打开时会验证 Library 身份并自动继续剩余补全；Library 离线时静默保留队列。人工字段 provenance 仍优先，ComicInfo 补全只覆盖非 `manual` 字段。
- 清点阶段立即刷新界面，并把“媒体、待补全、其他发现”数量写入私有诊断日志；目录进度每 100 个目录落盘一次。
- v5→v6 迁移把旧扫描已提交的媒体标记为完成，避免升级后无意义地重读整个旧索引。

### 验证

- `testDebugUnitTest`：**100 项通过，0 失败，0 跳过**；新增用例锁定未完成补全即使大小/时间未变也不得复用页数。
- 真机 Xiaomi 23127PN0CC / Android 16：保留数据覆盖安装成功，数据库从 v5 打开并升级到 v6，冷启动 706 ms，无 SQLite 异常或新崩溃。
- 真机 `AndroidJUnitRunner`：**6 项通过，0 失败**；新增数据库用例验证 `PENDING → 批次提交 → COMPLETE` 后才允许快照复用，既有 5 项真实 `ContentResolver`/DocumentsProvider 回归继续通过。
- `lintDebug`：**0 errors、31 warnings**；`assembleDebug` 与 `assembleDebugAndroidTest` 成功。

### 剩余风险

- 当前 E 盘接在开发机而不是手机，尚未完成新版对真实 465 GiB Library 的耗时测试；只能确认旧现场与新架构，不宣称全库速度已经验收。
- 首次目录清点仍作为一次原子遍历提交；若在清点完成前强制停止，目录遍历会重来。后续若清点本身仍过慢，再按顶层子树增加清点检查点。
- 续扫依赖用户重新打开应用；尚未引入系统前台服务或 WorkManager。Android 的“强行停止”本来也不会允许后台任务继续运行。

---

## 2026-09-17 · 系列书架与完整顺序编辑

**范围**：`model`、`metadata`、`ui`

### 问题

1. 作品页只有平铺网格；Series 只能作为筛选和排序字段，漫画无法先按系列浏览再进入有序作品列表。
2. `SeriesRef` 已有 `season`、`episode`、`volume`、`chapter`，但编辑器只提交标题和 `sortIndex`，每次保存都会把其余位置字段清空。
3. 手动给多个作品填写相同系列名时会逐项生成随机 ID；旧 Library 因此可能出现“标题相同、ID 不同”的系列引用。
4. `sortIndex` 强制默认为 `0.0`，无法表达“用户没有设置顺序”；显式 Chapter 文件名也只保存通用排序值，没有保留章号语义。

### 措施

- 作品页默认增加系列书架，并保留“全部作品”切换；点进系列后，媒体详情的浏览上下文只包含该系列。没有系列的内容集中在“单篇 / 未归系列”，不会消失。
- 书架是从现有便携 `SeriesRef` 派生的展示层，不引入新的本机真相；同名系列按大小写无关标题合并，以兼容历史随机 ID。
- `sortIndex` 改为可空；排序优先使用显式手动顺序，其次使用季/集或卷/章，最后按标题。编辑器补齐所有字段，校验非负数字，并允许全部留空。
- 手动保存同名系列时复用当前 Library 中字典序最小的既有 ID；新系列使用 Library ID + 规范化标题生成稳定 UUID。保存会完整携带手动顺序、季、集、卷和章。
- Chapter 目录与下载器编号 CBZ 的弱识别同时保留 `chapter` 和通用排序建议；结果仍需经过 Inbox 确认。
- 本阶段不提升便携 Schema 版本，也不创建独立 Series 文档；已有 v3 catalog 可直接读取，只有用户保存时才写回相应条目的可空字段。

### 验证

- `testDebugUnitTest`：**99 项通过，0 失败，0 跳过**。
- 新增测试覆盖同名不同 ID 合并、单篇保留、手动顺序优先、卷/章回退、未编号排序、稳定系列 ID 与全部可选位置字段；既有文件名测试增加 Chapter 语义断言。
- `lintDebug`：**0 errors、31 warnings**，没有新增警告类别；`assembleDebug` 与 `assembleDebugAndroidTest` 均成功。
- 当前无 ADB 设备，系列书架的真实封面布局、窄屏编辑器和返回手势仍需真机检查。

### 后续

- 设计独立、便携的 Series 与 Group 文档及迁移；支持系列重命名、批量成员编辑与拖拽顺序的一次性原子提交。
- 图片 / 视频的混合 Group 与画集合并不能复用“同名系列”代替，需在下一阶段建立明确成员关系和 Edition 语义。

---

## 2026-09-17 · 弱识别与 Inbox 确认边界

**范围**：`scanner`、`metadata`、`data`、`ui`

### 背景

对 `E:\Rem-lib` 做只读盘点后确认：Library 已增长到约 464.92 GiB、76,517 个文件，既有作者/编号目录，也有 1,566 个下载器 CBZ、数字 JM ZIP、EhViewer 目录和大量图片/视频混合写真。目录命名包含程序产物与人工整理，不能把名称推测直接当作用户决定。

### 问题

1. 扫描候选虽然带 `inInbox`，但相册、图片 / 视频、漫画 / 动漫和全局搜索并未排除它们；识别结果实际上会在确认前进入正式视图。
2. 用户只能进入编辑器并保存才能离开 Inbox；没有“保持当前自动建议”的单项或批量确认动作。
3. `withManualEdits()` 只保留已有 `manual` 来源。新识别项即使携带自动来源，经过编辑保存也会丢掉 `filename` / `comic_info` provenance。
4. 当前样本中的 `eh/` 根目录、`JM/数字.zip` 和“系列目录/编号标题_hash.cbz”没有稳定落入现有识别规则。
5. 不支持的扩展名会被扫描器静默忽略；同时，“包含多图且还有子目录”的结构虽然被计入 `ambiguousDirectories`，但没有持久索引和可见入口。把所有未知内容都强行建成 `MediaItem` 又会虚构可解码能力与作品边界。

### 措施

- 正式视图和全局搜索统一排除 Inbox 项；扫描完成后的新内容只在 Inbox 出现。
- Inbox 增加选择模式、全选和批量接受；媒体长按操作增加单项“接受识别建议”。接受会一次写入便携 catalog 并清除本机 Inbox 状态，不产生 `manual` 锁。
- `RecognizedMetadata` 携带逐字段自动来源；ComicInfo 与文件名推断合并时保留真正提供标题、作者、标签和系列的来源。
- 修正 `withManualEdits()`：保留未修改字段的自动 provenance，只把实际变化的字段升级为 `manual`。
- 增加上述三类确定性路径识别，但仍只作为 Inbox 建议，目录名不会直接成为不可覆盖的用户真相。
- 新增独立的本机 `discoveries` 索引，把不支持的用户文件与结构不明确的目录显示在 Inbox“其他待判断”中；发现记录不写入便携 catalog，不移动文件，也不冒充可打开媒体。
- 增加显式发现过滤策略：忽略 `.gallery/`、隔离目录、`.nomedia`、`.ehviewer`、`ComicInfo.xml` 和常见系统缩略图；未登记的 TXT、CBR 等仍保持可见，便于后续交给 Agent 或新增解码器处理。
- 本机数据库从 v4 增量升级到 v5，只新增 `discoveries` 表；扫描成功后按 Library 替换可重建发现结果，遇到暂时不可读子树时与媒体索引一样保留该子树旧记录。

### 验证

- 第一阶段 `testDebugUnitTest`：**95 项通过，0 失败，0 跳过**；发现索引加入后：**96 项通过，0 失败，0 跳过**。
- 新增测试覆盖字段来源合并、接受未改建议时保留自动来源、单字段编辑只锁定该字段、`eh/` 别名、JM 数字 ZIP 和下载器 CBZ 父系列。
- 新增发现策略测试，锁定已知 sidecar/内部文件会被隐藏，而普通 TXT、CBR 和下载目录不会因“不认识”而消失。
- `lintDebug`：**0 errors、31 warnings**；警告仍为依赖更新、KTX 建议和启动图标轮廓等既有类别。`assembleDebug` 与 `assembleDebugAndroidTest` 均成功。
- 当前 ADB 没有连接设备，且本机 Android SDK 未安装命令行 Emulator 组件，因此本轮尚未执行真实 SAF 扫描与数据库 v4→v5 的设备升级回归。
- 首次沙箱测试被既有 Gradle 锁文件 ACL 阻止；改用授权的现有 Gradle 缓存后构建成功。临时创建的 `.gradle-codex/` 已删除。

### 后续

- 为 discovered-entry 增加可便携的“忽略 / 已处理 / 手动分类”用户决定；当前发现记录刻意保持为可重建的本机扫描证据。
- 在引入逻辑 Group / Edition 前，不继续用更多文件名正则代替作品关系。

---

## 2026-09-16 · 大容量 Library 接入缓慢与重复初始化

**范围**：`storage`、`scanner`、`data`、`library`、`logging`，以及新增的开发记录制度
**提交**：`57da3c7`、`9337c57`、`6ac41c3`、`a3a7e31`、`f46a858`、`c9c236b`、`d809d59`、`4543631`、`d91e95d`、`7ba0e41`、`3d137d5`

### 背景

用户把一块 356 GB 的移动硬盘接在手机上作为 Library（`E:\Rem-lib`），接入后长时间加载不出来。同时反馈"重复点了一次选择库，库里面初始文件生成了多份"。

实测该库：**598 个目录、27,835 个文件、356.11 GB**，结构是"作品目录 / 子目录 / 页面图"，主要为 JPEG。

---

### 问题 1：接入后一直加载不出来

**现象**：接入后长时间停留在"正在扫描媒体…"，没有进度，看不到内容。

**定位**：阅读 `DocumentTreeStorage` 与 `LibraryScanner`，并用反编译核对 `androidx.documentfile:1.1.0` 的真实行为。

**根因**：不在文件数量，在 **ContentProvider 往返次数**。

- `TreeDocumentFile.listFiles()` 只查询 `document_id`，返回的子项是**不含元数据的 URI 桩**；随后 `name`、`type`、`isDirectory`、`length()`、`lastModified()` **各自再发一次查询**——`DocumentTreeStorage.toStorageEntry` 正好把这 5 个全调了一遍。一次目录列举因此变成 `2 + 7F + 6D` 次往返。
- `TreeDocumentFile` **没有重写 `findFile()`**，其实现是"列出整个父目录 + 对每个子项调 `getName()` 直到匹配"。在 n 个子项的目录里找一个文件约 `n²/2` 次查询。`resolve()` 对路径每一段都这么走一次。
- 全项目**没有任何 SAF 缓存**：没有路径→文档映射，没有目录列表缓存，`storageFor()` 每次操作都新建 `DocumentTreeStorage`。
- 扫描器**每次全量重算**：`contentHash` 重新打开并 SHA-256 每个散图，`readCapturedMetadata` 重新读 EXIF/视频容器，`countArchivePages` 重新遍历压缩包。

**一次判断修正**：最初按文件大小估算"约 43 GB 实读"，后来核对扫描规则发现——叶子图片集目录走 `directoryFingerprint`（只算文件名与大小），而 EXIF 读取只对 `Photos/` 根生效。该库两条都不触发，**实际读取字节量接近于零**。结论随之修正为"瓶颈是纯查询次数"，这也解释了为什么磁盘几乎不转却一直加载不完。

**措施**：

| 提交 | 改动 |
| --- | --- |
| `57da3c7` | 目录列举改为单次投影查询一次取回全部列；新增路径→文档与目录列表缓存（LRU 有界）；重命名/删除只失效受影响子树而非整体清空；`createDocument` 记录 provider 实际使用的文件名 |
| `9337c57` | 增量扫描：大小与修改时间均未变化则复用已记录指纹与拍摄信息；任一不同则强制重读；扫描结果上报"读取内容/复用"计数；手工构造的 `LibraryDocument` 补上 provider locator |
| `a3a7e31` | 扫描结果改单事务批量写入，不再逐条提交 |

**为什么不干脆"什么都缓存"**：缓存被刻意做成**只增不减的加速层**——不做负缓存，查不到就回落到 provider 查询。因为 provider 可能在 rename 返回后才发布文件，负缓存会把"暂时查不到"固化成"不存在"，反而制造缺陷。

**验证**：

- 新增 `SafDocumentCacheTest`（含 LRU 淘汰、子树失效、根路径语义）与 `ScanSnapshotReuseTest`（大小变化、时间戳变化、同大小但被触碰都必须重读），共 80 项单元测试通过。
- **真机验证未完成**：需要在插入硬盘的条件下跑一次全量扫描对比耗时。用户尚未执行。

**遗留**：

- 未实测修复后的扫描耗时。
- `Gallery_Project_Guide.md` 14.1 与 33 章早已写明"增量扫描 / 缓存持久化 / 大任务显示进度"，代码此前完全没有实现；本轮已补齐进程内缓存、增量复用和扫描进度。跨进程持久化目录缓存仍不做，因为 SAF 外部变化会使它失真。
- `refreshFromDatabase()` 仍会为界面状态全量载入一次媒体行；本轮已去掉扫描提交阶段的第二次完整对象/JSON 载入，但超大库最终仍应改为分页或按需查询。

#### 后续全面审查与修正

**判断修正**：最初的单元测试只验证了 `SafDocumentCache`，没有经过真实 `ContentResolver`。复查发现 `createDocumentChild()` 查询新节点后把 `relativePath` 固定成空字符串，导致新文件覆盖缓存中的 Library 根节点；真机旧日志中的“无法提交 `.gallery/schema/v3.json`：写入后目标文件不可见”与该机制吻合。此外，重命名/删除沿路径一直失效到根，和“只失效子树”的记录不符；`query()` 返回 `null` 时还会缓存空列表，实际形成了负缓存。

**措施**：

- `01a9737`：新节点按 provider 实际名称接到真实父路径；创建后同步父目录缓存；重命名/删除只清理目标子树与直接父目录列表；provider 不返回 Cursor 时抛出 I/O 错误，不缓存空目录。
- `cf355d9`：快照保存压缩包页数；未变化 ZIP/CBZ 不再打开，变化时一次遍历完成页数与 ComicInfo；目录指纹加入修改时间，损坏的可选 ComicInfo 不再阻断页面计数。
- `7b8c212`：每 10 个目录上报目录、条目、候选数量；读取失败的目录写入 `unreadableDirectories`，提交索引时保护该子树的旧行。

**验证**：新增父列表同步、祖先缓存保留、压缩包页数复用、同大小页面修改、不完整子树保护和损坏 ComicInfo 等测试；完整回归见本条末尾。真机能启动并生成新日志，但外置硬盘未挂载，356 GB Library 耗时仍未实测。

#### 真机 DocumentsProvider 集成回归

**判断修正**：单元测试继续使用缓存或内存假实现，仍然没有经过 Android 对树 URI 的层级校验与 `DocumentsContract` 调用。新增真机 Provider 用例后又发现两处遗漏：`resolveChildLocked()` 把完整子路径当作父路径，多层目录已存在时可能被误建成 ` (1)` 副本；`createFileExclusive()` 虽记录了 provider 实际名称，却仍把请求路径作为返回 `key`。

**措施**：子节点解析统一从完整子路径计算直接父路径；仅当父目录列表原先已缓存时才为外部变化额外刷新一次，首次缺失不重复查询；新建文件返回 provider 实际相对路径。扫描提交的缺失检测同时改为只查询 `id` 与 `relative_path`，不再重复解码整张媒体表。

**验证**：调试版内置仅 debug 可见的测试 DocumentsProvider，并在 Xiaomi 23127PN0CC / Android 16 上经真实 `ContentResolver` 跑通 5 项用例：既有多层目录、独占创建冲突改名、失败查询不形成负缓存、重命名只失效目标父目录、完整 Library 初始化/身份提交/租约释放。E 盘正在下载时只抽样两层目录名，确认现有 `作者/NO.序号 作品名[页数-体积]`、单层图片集和作者视频目录都已被当前解析规则覆盖；未读取媒体内容、未计算哈希、未写入磁盘。

---

### 问题 2：重复接入生成多份初始文件

**现象**：`.gallery` 里同时存在两份身份文件，`library.json` 与 `library (1).json`。

**定位**：读取磁盘证据。

```
library.json        239 B  library_id = 1703306e-…  mtime 16:23:17.986
library (1).json    239 B  library_id = 4f5abe22-…  mtime 16:23:18.147
schema/v3.json  与  schema/v3 (1).json  字节完全相同
transactions (1)/  backups (1)/         空目录
两份身份的 created_at 相差 96 毫秒
```

**根因**：三个缺陷叠加。

1. `initialize()` 采用**先查询、后写入**的判断（`check(access.find(LIBRARY_JSON) == null)`），而 `library.json` 被设计为最后提交的完成标记，两者之间存在数秒窗口。并发的第二次接入因此也能通过检查。
2. `attach()` **不按 treeUri 去重、也不互斥**，每次调用都当新库处理。
3. `PortableDocumentWriter` 把 provider 改名后的结果**当作提交成功**。SAF 上没有覆盖语义，Android 的 provider 在目标名已存在时会**另存为 ` (1)` 副本并保留源文件**，`renameDocument` 仍返回非空 URI。

**措施**（`6ac41c3`）：

- `initialize()` 先原子占位 `init.lock`；provider 若把该名字改回来（即 ` (1)`），说明另一个调用者已获胜，抛出 `InitializationInProgressException`。
- `attach()` 串行化、按 treeUri 复用磁盘上的身份、并短暂等待并发初始化者发布身份。
- 写入门禁：只有确认暂存文档**已移动到目标路径**才算提交；发现产生副本则删除副本、还原上一版本、报错失败。
- 本地登记与磁盘身份不一致时，以磁盘为准并替换登记（`.gallery` 是真相，本机行只是索引）。
- `scan()` 串行化，避免重复点击并发跑两次全量扫描。

**验证**：`PortableLibraryIdentityTest` 用两个假 provider 复现真实语义——"已有该名字时另存为 ` (1)` 并保留源文件"，以及"拒绝 rename 时保留源文件"。测试过程中发现写流程**总是先把旧文件挪成备份**，所以提交时目标名其实已空出；要触发冲突必须让备份步骤失效，这一点由 `refusesRename` 参数模拟。

#### 后续全面审查与修正

**判断修正**：`init.lock` 的假 provider 会在同名创建时返回 ` (1)`，但真实 `DocumentTreeStorage.createFile()` 会先找到旧文件并直接返回，因此原“原子占位”在第二实例/第二设备场景并不成立；永久保留锁还与“身份丢失后重新认领”冲突。

**措施**：

- `f779db2`：增加绕过“已存在即返回”的 provider 独占创建接口；锁移到 Library 根目录，先于 `.gallery` 创建，提交身份后释放。
- `f79b0c7`：锁写入 UTC 时间戳并作为 15 分钟租约；进程被终止后，下一次接入可清理过期租约再尝试一次。活跃锁产生的 ` (1)` 文件由失败方删除。

**验证**：覆盖活跃锁拒绝、成功后释放、过期锁恢复、第二次初始化不产生新身份；并已在真机测试 DocumentsProvider 上跑通完整初始化、身份读取和租约释放。仍需在两台真实设备同时连接同一可写卷的条件下验证具体 provider 的跨设备一致性。

---

### 问题 3：真机问题无法调查

**现象**：用户在手机上遇到问题后，没有任何可取的现场。

**定位**：全项目仅有 3 个文件、5 处 `android.util.Log`；release 开启 `isMinifyEnabled`，堆栈混淆；`mapping.txt` 位于 `build/` 会被清理。多处失败路径还会静默吞掉异常（`GalleryRepository.cleanupExpired`、`MediaDetail` 的图片加载等）。

**措施**（`f46a858`、`c9c236b`、`d91e95d`）：

- 新增 `logging` 包：记录写入 `filesDir/logs/`，单后台线程串行写入，按天滚动，保留 14 天、最多 10 个文件，未捕获异常单独落盘。
- 脱敏：`content://` URI 与盘符路径在写入前替换掉，对应 `GALLERY_LIBRARY.md` 中"账号、Cookie、Token 不得写入日志"的既有约束。
- `设置 → 诊断日志` 支持查看、导出（FileProvider + 系统分享）、清除。
- `assembleRelease` 自动把 R8 mapping 复制到 `dist/Rem-<版本>-mapping.txt`，并保留 `SourceFile`/`LineNumberTable`。

**为什么不写进 Library**：`.gallery` 是用户的便携真相，且盘未挂载时写不进去——而"盘异常"恰恰是最需要日志的场景。因此日志留在本机私有目录，卸载即清除。

**验证**：`RemLogScrubTest`（脱敏不得泄漏卷 ID 与盘符）、`LogRetentionTest`（年龄窗口、文件上限、tail 截断）通过；真机确认 `session-*.log` 与 `session-header.log` 正常生成。写入轮转测试时发现 **`session-header.log` 也以 `session-` 开头**，会占用滚动窗口名额并最终被当最旧会话删除——已修。

**遗留**：

- 日志改为不含任何绝对路径，因此真机排查时需要额外信息时，仍需在消息里带上 Library 相对路径。
- `MediaDetail`、`MediaGrid`、`SystemMediaImporter` 中仍有静默 `.getOrNull()` 兜底未补日志。

#### 后续全面审查与修正

**根因补充**：`describe()` 只脱敏异常消息，却把未经处理的堆栈首行再次拼入记录，URI 和盘符路径仍可能从这里泄漏；两个 `SimpleDateFormat` 还会被调用线程、日志线程和崩溃线程并发访问。导出文件列表在日志任务排队前生成，可能漏掉刚发生的错误，并从后台线程直接启动分享界面。

**措施**（`fc85bb3`）：整段异常摘要统一脱敏；改用线程安全的 `DateTimeFormatter`；查看日志先等待排队写入，导出在日志线程完成文件快照后回主线程启动系统分享。

**验证**：新增异常堆栈 URI、卷 ID 和 Windows 路径不得残留的测试；真机覆盖安装后 `session-20260916.log` 与 `session-header.log` 正常生成。

---

### 问题 4：隔离重复身份后，库无法再接入

**现象**：用户插入硬盘重新接入时连续三次失败，界面报"目录中已有 Rem 保留文件：GALLERY_LIBRARY.md"。

**定位**：真机日志直接抓到三次失败（19:03:17 / 19:03:24 / 19:03:55，修复前的构建），堆栈指向 `PortableLibraryManager.initialize` 的保留文件检查。

**根因**：`initialize()` 把 `GALLERY_LIBRARY.md` 与 `schema/v3.json` 当作"用户冲突文件"拒绝写入：

```kotlin
val reservedConflicts = listOf(GUIDE_FILE, SCHEMA_FILE).filter { access.find(it) != null }
check(reservedConflicts.isEmpty()) { "目录中已有 Rem 保留文件：…" }
```

这两个文件是 **Rem 自己生成的**。而在整理重复身份时移走 `library.json`，恰好把目录变成了"有 md、没有身份文件"的状态——`inspect()` 返回 `Missing`，于是必然走进这条拒绝分支。**这个缺陷由本次整理动作引入**，不是既有问题。

**措施**（`7ba0e41`）：

- 已有 schema 时只解析其声明版本：可写则认领，声明版本高于本客户端才拒绝。
- 已有 md 时**不重写**——它是用户的库说明，可能被编辑过。
- 其余情况就地补出 `library.json`。

**验证**：新增三个测试（md 存在时认领并保留 md、schema 存在时认领并补出 md、schema 版本过新仍拒绝）。其中之一抓到一个连带隐患：读版本时字段名写成 `schemaVersion`，而磁盘上是 `schema_version`，反序列化静默退化为 `0`，**把版本保护整个绕过**；已加 `@SerialName` 并由测试锁定。

**遗留**：无。

---

### 本次得到的方法论

- **判断可被推翻，且必须写下来。** "43 GB 实读"被实测推翻为"接近零字节读"，这个修正直接改变了优化方向——从"少读字节"转向"少发查询"。
- **provider 的行为要靠反编译确认，不能凭直觉。** `findFile` 的 O(n²) 与 `listFiles` 返回无元数据桩，都是从 AAR 字节码里读出来的，只读源码看不出来。
- **假实现必须复刻真实语义。** 测试里的假 provider 起初"冲突时删掉源文件"，与 Android 实际行为（保留源文件、另存副本）不符，导致测试一度无法复现真实缺陷。
- **整理用户数据前，先想清楚"整理完的中间状态还能不能用"。** 本次问题 4 就是漏了这一步。
- **写测试经常比读代码更快发现 bug。** 本轮由测试直接抓出的真实缺陷有三个：日志头占用滚动名额、schema 版本字段名不匹配导致保护失效、以及写入器把 provider 改名当成功。

### 待办

- 插入硬盘后实测扫描耗时，与前次对比。
- 为静默失败路径补日志级别记录。
- `refreshFromDatabase()` 首次全量载入在超大库下的开销与分页方案。

### 本轮完整回归

- `testDebugUnitTest`：**89 项通过，0 失败，0 跳过**；真机 `AndroidJUnitRunner`：**5 项通过，0 失败**。
- Android Lint：**0 errors，28 warnings**；警告为依赖更新提示、圆形图标轮廓和 KTX 建议，没有新增阻断项。
- `assembleDebug` 与使用外部 JKS 的 `assembleRelease` 均成功；Release 通过 APK Signature Scheme v2 校验，签名人为 `CN=susnowy`，R8 mapping 已重新归档。
- 真机 Xiaomi 23127PN0CC / Android 16：Debug 包保留数据覆盖安装成功，`MainActivity` 前台运行、进程存活、私有日志生成，无新崩溃。手机当时只有 private/emulated 卷，未挂载移动硬盘，因此没有把“启动成功”误写成“真实 Library 扫描已验证”。
- 调试版改用 `com.susnowy.rem.debug`，已与原 `com.susnowy.rem` 并存安装；后续真机调试不再需要用 Debug 证书覆盖正式包，也不会清除正式包的本机索引、日志和 SAF 授权。
