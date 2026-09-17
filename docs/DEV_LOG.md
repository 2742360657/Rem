# 开发记录

按 `Gallery_Project_Guide.md` 31.5 维护：每次内容开发、性能优化或缺陷修复，记录**遇到的问题**与**解决措施**。

这里写过程与判断依据；面向使用者的版本变化写 `CHANGELOG.md`，两处不重复。

条目按时间倒序。同一问题的后续进展追加到原条目，不另起新条。

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
