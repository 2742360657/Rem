# 开发记录

每次内容开发、性能优化或缺陷修复都记录问题、证据、取舍、措施、验证和剩余风险。

这里写过程与判断依据；面向使用者的版本变化写 `CHANGELOG.md`，两处不重复。

条目按时间倒序。同一问题的后续进展追加到原条目，不另起新条。

旧条目保留当时的版本号和判断，仅用于追溯；当前产品与格式以 `Gallery_Project_Guide.md`、`AGENTS.md` 和 `docs/ARCHITECTURE.md` 为准。

---

## 2026-09-17 · Edition 比较与虚拟合并（一）：页清单、比较报告、合并写入

**范围**：`media`、`compare`（新增）、`metadata`、`data`；媒体原文件不变

### 问题与决策

- 用户手上有同一画集的多个来源（目录版、CBZ 版），需要知道哪些页完全重复、哪些只在一侧、哪些“同名但内容不同”，并希望把去重后的阅读顺序固定下来。标题相似或同作者不能自动判定同属一个作品，所以比较与合并都必须由用户显式发起。
- 手机上的移动介质吞吐可能只有约 20 MB/s，所以比较必须按 I/O 分层：能用索引里的页数/大小解决的绝不读内容；必须读时每个来源只读一遍并缓存。
- 现有阅读器用 `ZipInputStream` 流式读取，**不能随机访问条目**：枚举一个 CBZ 的页需要完整读一遍，而“逐页重开压缩包”会把文件读成 N 倍。因此深度比较的按页哈希必须在同一次顺序读取里完成——这样对压缩包而言哈希是零额外 I/O 的。
- 合并结果表达为同一 Work 上的新 Edition，成员是跨 Asset 的有序页级引用（`entry_path`）；v4 本来就能表达，不需要升 Schema，也不复制、移动或删除任何文件。

### 措施

- 新增 `PageManifestService`：为单个来源产出有序页清单（容器路径、压缩包内条目、页名、字节大小、可选 SHA-256）。目录 = 一次列目录（仅在需要哈希时逐页读取）；压缩包 = **一次顺序读取**，边流边计数边计算每页哈希；单文件 = 一条记录。支持进度回调与协程取消，并带按（Library、路径、大小、修改时间、是否哈希）键控的会话内 LRU 缓存，重复比较不再读第二遍。
- 新增纯逻辑 `compare` 包：`PageComparison` 两轮匹配（先内容哈希，再页名规范化），区分完全重复、同名不同内容、同名同大小（未读内容时只报“疑似重复”，绝不称为重复）；`MergePlan` 生成虚拟阅读顺序——左侧保持原序，只存在于右侧的页插入到“下一个双方共有页”之前（没有则排在最后）；`MergeManifest` 记录来源、深度、字节数与报告计数。
- `PortableMetadataStore.upsertEdition`：一次原子写入创建/替换 Edition，带 `revision` 冲突检查、Work 与 Asset 引用校验，并可在同一次写入里把新 Edition 设为该 Work 的首选版本，避免为了“设为首选”再重写一遍目录文档。
- `GalleryRepository.compareWorks`（快速 / 深度，带进度）与 `createMergedEdition`（生成页计划 Edition、设为首选、把证据写入 `.gallery/imports/merge-<editionId>.json`）。合并 Edition 的 ID 由（Library、目标 Work、两个来源 Work）推导，重复合并同一对来源只会更新同一个 Edition。
- 测试夹具去重：三个便携存储测试共用一个 `MemoryLibraryAccess`（保留“提供方改名”和“发布 ` (1)` 副本”两种真实冲突语义），不再各写一份内存实现。

### 验证

- `testDebugUnitTest`：**157 项通过，0 失败，0 跳过**（新增 13 项）。新增用例锁定：深度比较按内容哈希匹配（即使页名不同）、快速比较绝不声称“完全重复”而只报疑似、同名不同大小与同名不同哈希都归为冲突、目录页与压缩包页按页名基名匹配、同名重复页按顺序一一配对；合并计划保持左序、把右侧独有页插到下一个共有页之前、首位插入与左侧独有页保留；Edition 写入创建页计划并保留两个来源、`prefer` 在同一次写入里改首选版本、重复写入只更新一行且 revision 递增、过期 revision / 未知 Asset / 空成员 / 未知 Work 全部拒绝。
- `lintDebug`：**0 errors、31 warnings**，与改动前一致；`assembleDebug` 与 `assembleDebugAndroidTest` 成功。
- 尚未接入界面，也尚未做真机验证：页清单对真实 CBZ 的耗时、深层比较的取消响应、以及合并后按页计划阅读都还需要真机检查。

### 追加：阅读器支持页计划与比较界面

- `editionPlanPages` 把 Edition 的页计划翻译成阅读器页：目录里的页变成该文件的路径，压缩包里的页保留“容器 + 条目”，单图容器就是它自己；遇到“整个目录/压缩包”这种需要先展开的成员则返回 null，让该作品回退到普通单来源阅读，而不是把目录当成一张图。
- 仓库在接入与扫描时（目录文档已经在手）为“首选版本是页计划”的作品预生成阅读顺序，键为 `libraryId:workId`；合并写入后立即发布新计划。阅读器取页不再需要重新读目录文档。
- 计划页的 URI 解析按父目录分组：一个目录只列一次，避免“每页一次 provider 查询”把合并阅读变成几百次往返。
- `MediaContentService.decodeArchivePage` 增加显式 `archivePath`，阅读器的三处压缩包解码（预加载、当前页、页序缩略图）都改为使用该页自己的容器，因此合并版本里的页可以来自不同压缩包。
- 作品详情页新增“比较版本”入口（图片集作品）：选择另一个来源 → 快速/深度比较（可取消）→ 报告按“完全重复 / 同名不同内容 / 疑似重复 / 仅左 / 仅右”列出，并显示每个来源实际读取的字节数与耗时 → 确认后生成虚拟合并版本并设为默认。界面明确区分“未读内容时只是疑似重复”。

### 验证

- 界面与阅读器改动后：`testDebugUnitTest` **163 项通过，0 失败，0 跳过**（新增 6 项：3 项页计划用例——跨容器页各自携带正确的阅读路径、顺序按 `sort_index` 而非存储顺序、整容器成员与未知 Asset 一律返回 null 让调用方回退；3 项压缩包单遍读取用例——只把图片条目计入页清单并按自然序排列、未启用哈希时仍从同一次读取得到每页字节数、嵌套条目的 `entry_path` 保留完整路径而显示名取基名，并用“读取字节数等于各页负载之和”锁定只读一遍）；`lintDebug` **0 errors、31 warnings**；`assembleDebug`、`assembleDebugAndroidTest` 成功。
- 压缩包读取被抽成对 `InputStream` 的独立函数，因此这条最容易出错的 I/O 路径现在有无需真机的回归测试。
- 仍未真机验证：合并版本的阅读性能（每页解析 + 计划里的跨容器读取）、深度比较在真实 CBZ 上的耗时与取消响应、以及 692 成员系列与合并计划同时存在时的界面表现。

### 剩余风险

- 页清单缓存只在进程内（LRU 24 项）；应用重启后深度比较需要重读。若真机实测觉得可惜，再考虑放进本机数据库并设上限。
- 合并版本的阅读需要按页解析来源：目前按父目录批量解析 URI，但单个压缩包页仍要在每次解码时重新流式读取整个压缩包（既有阅读器行为），页数多时逐页翻动会明显受盘速限制。
- 像素级比对（重编码但同一张图）尚未实现，当前只把“同名不同内容”列为冲突；这是刻意留的入口。
- 合并 Edition 会让 Work 的运行时投影取“第一页所在来源”，因此合并后卡片路径可能仍指向原来源之一；该行为已按“第一页优先”实现，但未在真机上确认。

---

## 2026-09-17 · 系列批量编辑：重命名、批量增删、顺序与编号

**范围**：`model`、`metadata`、`data`（本机数据库 v9）、`ui`、库内生成说明；媒体原文件不变

### 问题与决策

- Series 在 v4 里已经是独立实体，但成员关系只能通过“编辑单个作品”的 `SeriesRef` 逐条写入：无法批量加入/移出、无法重命名、无法调整顺序，也没有一次性原子提交。真实库里有 8 个系列（最大 692 个成员），靠逐个编辑不现实。
- 扫描的字段优先级是 `manual > 识别 > 便携值`。系列改成批量编辑后，如果只写实体不写来源，下一次扫描遇到同名目录就会把作品重新归类——包括用户刚刚移出系列的作品。因此把“系列归属”明确记为人工决定是这次的核心。
- 每次系列写入都会重写整份 `catalog.json`（真实库 4.79 MB），所以和分组一致：界面先改，点“保存”才写一次。

### 措施

- `PortableMetadataStore` 增加 `upsertSeries` / `deleteSeries`：一次原子写入，带 `revision` 冲突检查、标题去空白、成员去重并按 `sort_index` 排序、引用校验；同一个写入里把 `markSeriesManualFor` 里的每个 Work 标记 `field_sources.series = manual`。
- `GalleryRepository.saveSeries` 把列表下标写成 `sort_index`，保留季/集/卷/章（除非用户选择清空），随后用 `applySeriesAssignment` 把新关系写回所有受影响的本机媒体行：仍在系列里的写新 `SeriesRef`，被移出的清空 `series_json`，两者都保留 `series = manual` 来源。
- `deleteSeries` 只删除实体并清空成员的本机分配；Work、Edition、Asset 与媒体文件完全不动。
- 本机数据库升级到 v9，新增可丢弃的 `series` 表（`members_json`），接入与扫描时用便携目录文档整体重建；单次编辑走增量 upsert/delete 行。
- 界面新增 `SeriesEditor`：重命名、批量添加（搜索标题/路径/作者）、移出、上下移动、移动到指定序号、清空编号、删除系列；未保存修改在返回时提示并提交。成员列表对 692 个成员仍用惰性列表渲染。
- 选择成员的对话框抽成共享的 `WorkPickerDialog`，分组编辑器同时改用它，避免两套重复实现。
- 库内生成说明补充：`sort_index` 是阅读顺序，Agent 不得擅自重排或清空用户编号。

### 验证

- `testDebugUnitTest`：**144 项通过，0 失败，0 跳过**（新增 11 项）。新增用例锁定：系列创建与重载、重排写入 `sort_index` 并给受影响 Work 打上 `series = manual`、未受影响的 Work 不被误标记、被移出系列的 Work 仍保留人工“无系列”决定且投影里系列为空、重命名保留季/章编号、过期 revision / 空成员 / 空标题拒绝、删除系列不改动 Works/Assets/Editions、未知成员拒绝；模型层覆盖列表顺序与编号的对应、清空编号只清位置不清顺序、阅读顺序优先级与编号摘要文本。
- `lintDebug`：**0 errors、31 warnings**，与改动前一致；`assembleDebug` 与 `assembleDebugAndroidTest` 成功。
- 真机：本轮仍未验证（手机未连接）。系列编辑器、v9 迁移和 692 成员列表的滚动表现都需要真机检查。
- 真实 E 盘仍只读：没有写入盘上 `.gallery` 或媒体。

### 剩余风险

- 一次系列保存要读两遍、写一遍 `catalog.json`（加载既有实体 + 写入）。真实库上是 0.5–1 秒量级的开销，用户点保存时主观可接受，但还没有实测。
- 拖拽手势仍未实现：顺序调整目前是上下箭头与“移动到指定序号”，与既有漫画页序对话框保持一致；真正的拖拽会在交互打磨阶段统一补上。
- 同名不同 ID 的历史系列会在书架层合并显示，编辑器只编辑其中一个实体。真实库当前没有这种重复；该限制已记录在用户指南。
- “一个 Work 只能属于一个 Series”仍是全局校验规则；跨系列复用同一作品会被拒绝。

---

## 2026-09-17 · 可编辑 Group：保存派生混合组并手动增删、排序、设封面

**范围**：`model`、`metadata`、`data`（本机数据库 v8）、`ui`、库内生成说明；媒体原文件不变

### 问题与决策

- Schema v4 早就有 `PortableGroup`，但整个仓库里没有任何写入点、没有投影表、也没有界面：真实库 1352 个 Work 里 `groups` 是 0，混合图片/视频只能靠 `MixedMediaPresentation` 派生展示。用户既不能把“这次拍摄”固定下来，也不能向已有画集增删成员或调整顺序。
- 派生组不能自动升级成 Group：同一目录里有图片集和直属视频只是结构证据，不是用户决定。因此选择“显式保存”——用户点一下才写便携层，避免扫描把推断伪装成决定。
- 每次分组编辑都会重写整份 `catalog.json`（真实库 4.79 MB，手机上盘速约 20 MB/s），所以界面把标题、成员、顺序、封面先在本地改，只有点“保存”才写一次；逐次自动保存会让每一次点击都变成一次整文档写入。

### 措施

- `PortableMetadataStore` 增加 `upsertGroup` / `deleteGroup`：一次原子写入，带 `revision` 冲突检查、成员去重、标题去空白与引用校验（成员必须是已存在的 Work，封面必须存在）。删除只移除关系，不动 Work、Edition 或 Asset。
- 成员顺序就是列表顺序：仓库把列表下标写成 `PortableGroupMember.sortIndex`，投影再按它读回，所以“拖动顺序”在任何设备上都是同一个结果。
- 派生组保存使用 `derivedGroupId(libraryId, primaryWorkId)` 作为稳定 ID：同一目录重复保存只更新既有 Group，不会产生第二份。
- 本机数据库升级到 v8，新增可丢弃的 `groups` 表（`members_json`）。接入 Library 与每次扫描后用便携目录文档整体重建；单次编辑走 `upsertGroup` / `deleteGroupRow`，避免为了看一个分组而重载整份 catalog。重建本机索引会清空并重新投影。
- 界面新增「分组」货架与分组编辑器：货架分“手动分组”和“目录派生”（带“保存为 Group”），支持“新建分组”；编辑器支持添加成员（全库搜索多选）、上下移动、移出、设为封面、重命名、删除分组，未保存修改会在返回时提示并提交。
- 分组相关的可见性沿用上一阶段的规则：被忽略的 Work 不进入分组选择列表，也不会出现在分组详情中。
- 库内生成说明补充分组语义：`sort_index` 表示成员顺序，Agent 不得擅自重排或删除用户建立的分组。

### 验证

- `testDebugUnitTest`：**133 项通过，0 失败，0 跳过**（新增 10 项）。新增用例锁定：分组创建与重载、成员替换与 revision 递增、过期 revision 拒绝、未知成员与空标题拒绝、删除分组只移除关系（Works/Assets/Editions 完全不变）、重复成员写入时折叠、列表顺序写成 `sort_index`、无顺序时按 `work_id` 回退、派生组 ID 的稳定性（不同 Work / 不同 Library 不相同）、成员角色按媒体类型推导。
- `lintDebug`：**0 errors、31 warnings**，与改动前一致；`assembleDebug` 与 `assembleDebugAndroidTest` 成功。
- 真机：本轮未验证。手机仍未连接（上一阶段记录过：原 APK 由 Windows 密钥签名，已卸载并用本机密钥重装），分组界面与 v8 迁移都还需要一次真机检查。
- 真实 E 盘仍只做只读盘点，没有写入盘上 `.gallery` 或媒体。

### 剩余风险

- 分组编辑是整份 catalog 写入：拖动顺序后点一次保存就要重写约 4.8 MB。真实库上的主观延迟还没测；如果不可接受，下一步应改为分文档存储或增量写入。
- 分组删除采用“引用清理”语义：成员被永久删除时 `removeItem` 会把它从分组里移除，成员清空的分组会被删除；这条路径尚未在真机验证。
- 派生组的判定仍是“同一叶子目录的 IMAGE_SET + 直属视频”，不含嵌套目录、压缩包或跨目录推断。

---

## 2026-09-17 · 便携 Inbox 决策：接受、人工归类、忽略与已处理

**范围**：`model`、`metadata`、`data`（本机数据库 v7）、`ui`、`organizer`、库内生成说明；媒体原文件不变

### 问题与决策

- Inbox 此前只有“接受”一种出口，而且只把本机 `media.in_inbox` 置 0；忽略、已处理和人工归类没有记录位置，“其他待判断”的路径更是只能看、不能处理。删除本机索引、清数据或换手机后，被忽略和已确认的内容会重新回到 Inbox。
- 决定属于用户真相，必须跟盘走。但把决定写进 `catalog.json` 会让每次翻页保存进度都重写整个目录文档（真实库已有 4.79 MB），也会把 Work 元数据和“我处理过这个路径”两种语义混在一起。
- 因此新增独立的 `.gallery/state/inbox.json`，仍属 Schema v4：文档不存在时读取方按“没有任何决定”处理，旧客户端忽略它也不会影响 `catalog.json` / `state.json`。
- 四种决定：`accepted`（接受建议）、`classified`（在 Inbox 人工改归属，权威值仍是 Work 的 `domain`）、`ignored`（不进入普通视图，也不回 Inbox）、`handled`（只用于没有 Work 的待判断路径）。

### 措施

- `PortableInboxStore` 复用 `PortableDocumentWriter` 原子写入，带 `revision` 冲突检查、相对路径校验、同一文档内目标唯一性校验；`handled` 只允许 discovery 目标；更高 Schema 拒绝写入。路径必须是以 `/` 分隔的 Library 相对路径，禁止盘符、URI、`.` 和 `..`。
- 决定以相对路径为键，目标是 Work 时同时记录 `work_id`：扫描先按 `work_id` 匹配，Organizer 提交移动或重命名后改写决定的路径，所以被忽略的 Work 不会因路径变化复活；永久删除 Work 时一并清理它的决定。
- 接受建议、保存编辑、批量修改会在同一次操作里写 `accepted`（改动过归属则写 `classified`），因此“处理过”不再只存在于本机。忽略只写决定，不写 Work 元数据——用户要求的是“别再显示”，不是“改它”。
- 本机数据库升级到 v7：`media` 增加 `inbox_disposition`，`discoveries` 增加 `disposition`；`in_inbox` 保持原义（是否待处理）。重扫时 `replaceDiscoveries` 保留既有决定，`applyInboxDecisions` 用便携文档整体覆盖镜像列，重新接入 Library 时先回填再刷新界面。便携文档始终是唯一真相，镜像列可以随时重建。
- Inbox 界面拆成「媒体建议 / 其他待判断 / 已忽略 / 已处理」四个分区；待判断路径可逐条标记已处理或忽略，媒体可多选忽略；已忽略与已处理都提供撤销，撤销只删除决定，不修改元数据、不移动、不删除任何媒体。
- 普通视图、搜索与 Organizer 预览统一排除被忽略的 Work（`visibleInLibrary`），避免“忽略后仍被批量整理计划搬走”。
- `PortableMetadataStore.createBackup` 现在连同 `inbox.json` 一起快照，Organizer 等高风险批量操作仍可整体回滚。
- 库内生成说明同步：`GALLERY_LIBRARY.md` 增加该文档的读取顺序、语义与 Agent 写入规则（必须保留已有决定，自己写入时 `by` 标为 `agent:<标识>`，`handled` 只能用于待判断路径）；`.gallery/schema/v4.json` 增加该文档的必需字段、取值与目标类型。

### 验证

- `testDebugUnitTest`：**123 项通过，0 失败，0 跳过**（新增 15 项）。新增用例锁定：决定写入与重载、同一目标重复决定只保留一条、revision 冲突、撤销与永久删除清理、跟随 `work_id` 的路径改写、非便携路径拒绝、跨 Library 拒绝、`handled` 只能用于待判断路径、更高 Schema 拒绝，以及“提供方把提交结果发布成 ` (1)` 副本”时必须放弃写入并恢复上一版；另有忽略项不进入普通视图的可见性断言。
- `lintDebug`：**0 errors、31 warnings**，与改动前完全一致（未新增警告类别）。
- `assembleDebug` 与 `assembleDebugAndroidTest` 成功；debug APK 中已包含新文档路径与界面字符串。
- 真机：本轮**未完成**。手机上原有的 `com.susnowy.rem.debug` 是 Windows 调试密钥签名的版本，本机（Linux）密钥不同，无法覆盖安装；卸载重装后 MIUI 先拦截了一次 `INSTALL_FAILED_USER_RESTRICTED`，改用 `pm install` 成功，但随后手机从 USB 掉线，v6→v7 迁移的真机验证与 `connectedDebugAndroidTest` 都还没跑。旧库文件已备份在开发机 `/tmp/phone-v6.db`（`user_version = 6`，仅一条 `Rem-lib` 记录、0 条媒体），手机重新连上后可以先把这份库放回应用私有目录再启动，验证迁移而不是只验证全新数据库。
- 真实 E 盘本轮只做只读盘点：没有写入盘上 `.gallery`，也没有改动任何媒体。

### 剩余风险

- 本机索引的 `inbox_disposition` 是镜像：如果外部 Agent 在 Rem 运行时修改 `inbox.json`，需要一次重新接入或扫描才会反映到界面。
- 决定只按路径/`work_id` 记录，不做失效清理；长期使用后可能留下指向已删除路径的记录（无害，但会缓慢增长）。
- v7 迁移尚未在真机上验证；全新安装路径可以工作，但老库升级路径仍需一次真实检查。
- Group、Edition 和 Inbox 决定的跨设备并发仍以整文档 `revision` 为准，没有字段级合并。

---

## 2026-09-17 · 文档收敛与便携 Schema v4

**范围**：项目说明、`model`、`metadata`、`library`；媒体原文件不变

### 问题与决策

- `Gallery_Project_Guide.md` 同时保存产品规范、旧阶段计划、Git 规则、实现细节和历史总结，已超过 2,100 行；`AGENTS.md`、架构和用户指南又重复其中一部分，出现当前行为与旧计划互相冲突。
- v3 把物理路径、用户可编辑作品、版本和内嵌 `SeriesRef` 放在同一 item 中，无法安全表达跨目录 Group、同一作品的多个 Edition 和后续虚拟合并。
- 项目仍在测试期，决定只维护干净的当前格式；但已有 v3 测试元数据仍须在修改前备份，转换失败不得提前更新 Library 身份，任何转换不得触碰媒体字节。

### 措施

- 重新规定 Markdown 职责：README 只做入口，项目指南只放产品/格式，AGENTS 只放执行规则与当前状态，架构只描述已实现代码，用户指南只描述已可用操作，DEV_LOG 保留历史证据，CHANGELOG 记录版本变化。
- 把项目指南从混合长文收敛为当前规范，删除已经完成的 Phase 0–14 清单、重复 Git 规则、重复技术选型和过期格式示例；其余说明同步到各自唯一位置。
- Schema v4 将便携 Catalog 规范化为 `Asset`、`Work`、`Edition`、`Group`、`Series`。Edition 单向引用 Work/Asset，Group 和 Series 单向引用 Work；`items` 只作为 Android 运行时计算投影，不再序列化。
- 写入时分别更新 Asset、Work、首选 Edition 和 Series 成员；路径迁移只改 Asset 与封面路径；删除 Work 时同步清理 Group/Series 关系，并只移除没有其他 Edition 引用的 Asset 元数据。
- 保存后把规范化 Catalog 的计算投影完整回写本机索引；同名旧 Series 被归一或标题修改时，同一 Series 的其他本机行会立即同步稳定 ID/标题，不必等待下次扫描。
- 加入 ID、相对路径、首选 Edition 所属关系、成员引用和成员唯一性校验；状态改用 `work_id`。
- v3 转换先快照 identity、catalog、state、guide 和旧 Schema，再转换 Catalog/State，最后才提交 v4 identity。转换逐文档幂等，中途停止后可重试；同名旧 Series 合并为一个实体并选用稳定的最小 ID。
- Library 身份丢失但 v3 catalog/state 尚存时，从一致且有效的 `library_id` 恢复身份并完成转换；残留文档无法解析、ID 冲突、更早 Schema 或更高 Schema 时拒绝自动认领。
- 重写新接入 Library 的 `GALLERY_LIBRARY.md`：明确 v4 实体职责、Agent 读取顺序、人工字段保护、来源标签、相对路径、备份和物理文件安全边界。

### 验证

- `testDebugUnitTest`：**108 项通过，0 失败，0 跳过**。覆盖规范化写入、Series 单一所有者、v3 Catalog/State 转换、同名 Series 合并、转换失败不升级 identity，以及丢失 identity 后从便携文档恢复。
- `lintDebug`：**0 errors、31 warnings**；`assembleDebug` 与 `assembleDebugAndroidTest` 成功。
- Xiaomi 23127PN0CC / Android 16：AndroidJUnitRunner **8 项通过，0 失败**。新增测试经真实 `ContentResolver`/DocumentsProvider 完成 v3 残留文档、身份恢复、备份、v4 写入、旧 Schema 清理和租约释放。
- 最新 Debug 覆盖安装并冷启动成功：663 ms，进程保持运行；退出历史仅见测试结束、主动停止和 APK 更新，没有崩溃、ANR 或 OOM。

### 剩余风险

- v4 已建立便携 Group/Edition 结构，但手动 Group 编辑、派生组保存、Edition 比较和虚拟合并尚未接入 UI。
- 这次只用隔离的测试 DocumentsProvider 验证转换，没有对 E 盘现有 `.gallery` 执行迁移，也没有修改 E 盘媒体或元数据。首次用本构建接入 v3 Library 时会自动备份并转换，应先保留盘外备份再做真实库验收。
- Android 运行时仍使用 `MediaItem` 投影；后续 Group/Edition UI 应直接修改 v4 实体，不能重新引入序列化 `items`。

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
