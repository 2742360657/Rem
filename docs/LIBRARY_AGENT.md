# Rem Library Organizing Agent Contract

> 本文件面向“整理用户媒体 Library 的本地 Agent”，不是开发 Rem 代码的 Agent。
> 目标是：让 Agent 能独立、高质量、可审计地整理 Library，同时不与 Android 弱识别争夺职责。

本文件规定整理流程，格式与字段唯一权威为 [PORTABLE_FORMAT](PORTABLE_FORMAT.md)。产品需求见 [PRODUCT](PRODUCT.md) 的 R10。当前桌面工具支持 v4 校验与已有 Work 的批量元数据编辑；新建实体、关系整理和物理操作仍需后续工具支持，不能宣称完整无人值守整理已完成。

### 与库内说明的关系

只有 Library 的 Agent 应先读库内 `GALLERY_LIBRARY.md`，不依赖 Android 数据库或仓库。App 与桌面工具在构建时打包本文件及 PORTABLE_FORMAT 全文，新建库自动生成带 Library 身份的说明；已有库接入不覆盖用户定制。构建产物中仓库相对链接转换为文本，格式契约附在同一文件后部。

若现有库内规则与当前格式不一致，保留原文件，先报告冲突和生成更新计划。仅在用户授权范围内更新；不忽略库中特定用户限制。

## 1. 定位

Android App 做：
**发现 + 弱识别 + 用户确认 + 批量编辑 + 移动端观看**。

Library Agent 做：
**跨目录分析 + 大规模整理 + 规则化元数据 + 关系建立 + 歧义报告 + 可选物理整理计划**。

Agent 不应要求 Android 先把所有内容识别正确。
它必须能仅凭 Library 文件、`.gallery/` 和自己的固定规则完成整理。

## 2. 开始前

### Agent 开始前必须读取

1. 找到 Library root：存在 `.gallery/library.json`。
2. 读取：
   - `GALLERY_LIBRARY.md`
   - `.gallery/library.json`
   - `.gallery/schema/v4.json`
   - `.gallery/items/catalog.json`
   - `.gallery/state/state.json`
   - `.gallery/state/inbox.json`
   - `.gallery/transactions/` 中的活动事务与文档恢复槽状态
3. 确认 Schema 是自己支持的版本。
4. 对将写入的便携文档建立备份。
5. 先输出范围、字段和关系差异、冲突与未决项，再在已有授权范围内提交。物理变更必须对具体计划取得用户确认。
6. 与 Android 及其他写入 Agent 保持同库单写入者；提交前复读基线，有冲突重新合并。高风险真实库备份必须位于原介质之外。

绝不把主机盘符或当前挂载点写入便携数据。

账号、Cookie、Token 等凭据只能在用户授权会话中临时使用，不得进入 Library、日志、fixture、诊断导出或 Git。不要删除 `.gallery/`、`.nomedia` 或活动事务；`.nomedia` 用于阻止 Android 系统相册重复收录 Library 媒体，Rem 的 SAF 扫描不受影响。`.gallery/imports/` 是程序维护的导入及派生来源清单，不把它当成可随意改写的整理结果。

任何来源为 `manual` 的字段都不得修改、清空、追加、翻译、规范化或去重。
`field_sources.tags` 为 `manual`，整组标签必须原样保留；人工空值同样保护。
Group 只表达一起浏览；Series 只表达顺序；Edition 表达同一 Work 的取得版本。

## 3. 识别层级

Agent 应区分：

### 3.1 物理事实

例如：

- 一个目录中有 40 张顺序 JPG；
- 一个文件是 CBZ；
- 一个目录有图片和直属 MP4；
- 文件名有 `NO.123`；
- 父目录名是某作者；
- ComicInfo 声明标题/作者。

### 3.2 推断

例如：

- 这个目录大概率是一个写真 Work；
- 这些 Work 大概率属于同一 Series；
- 这个父目录大概率是 creator shelf。

### 3.3 决定

只有在规则足够确定，或用户已有约定时，才把推断写成稳定关系。

不能把“文件夹名字像系列”直接升级成 Series。

## 4. 针对常见媒体的默认整理策略

### 单本顺序图片漫画

一个叶子目录内主要是有自然顺序的图片：
优先建一个 Work + Edition，并保存稳定页序。

若多个独立 Work 共享明显系列前缀或用户规则：
可以建立 Series，但需要有比“同父目录”更强的依据。

### 连载漫画

目录/压缩包明显按“第 N 话 / Chapter N / Episode N”组织时：
建立有序 Series/章节关系。

不要把整部连载的根目录同时错误建成一个与章节并列的普通 Work，除非产品语义确实需要。

### 写真 / 图集

叶子目录可以是一个 Work。
图片与视频可以属于同一个 Work/Edition 或保存为适合一起浏览的 Group，取决于实际来源语义。

### 作者作品集

父目录明显是作者容器时：
批量写 creator/author 元数据；
不要仅为了作者聚合建立 Series。

### 纯视频作者

每个独立视频可直接成为 Work；
如文件名存在明确序号/系列，可建立 Series；
否则作者只是查询维度。

### 生活照片/视频

优先保留时间、拍摄信息、目录来源。
不要强迫每张照片变成“作品系列”。
这类内容主要依赖时间轴、Album、Collection、Tag。

### 梗图/表情包/截图

优先分类维度，不擅自移动真实文件。
逻辑分类可与物理多级目录不同。

## 5. 目录不是最终模型

允许从目录获得证据，但不允许把目录深度机械映射为：

`作者 -> 系列 -> 作品 -> 章节`

例如：

```text
Works/作者/NO.123 某作品/
```

可能表示：
作者 -> Work

而：

```text
Comics/downloads/来源/漫画名/第001话/
```

可能表示：
下载来源 -> Series/作品 -> Chapter

必须结合命名、内容组成、已有 metadata 和用户规则判断。

## 6. 批量整理优先

Agent 的优势是批量处理。

优先做：

- 同作者批量标准化；
- 规则化标题；
- 统一来源标签；
- 建立 Series 顺序；
- 批量填 season/episode 或 volume/chapter；
- 修正明显错误 domain；
- 为混合图片/视频作品建立一致关系；
- 输出未决歧义列表。

不要一条一条做能用稳定规则批量完成的工作。

## 7. 冲突策略

优先级：

1. `manual`
2. 明确的用户规则（不隐式解除 manual 锁）
3. 已有稳定决定与来源记录
4. 高置信来源 metadata（与已有决定冲突时报告）
5. 文件名/目录名启发式

发生冲突时：

- 保留更高优先级；
- 不整行覆盖；
- 在报告中列出冲突；
- 不为了“整齐”删除未知字段。

## 8. Series / Group / Edition 判断

### 使用 Series，当且仅当：

成员之间有明确阅读/观看顺序。

### 使用 Group，当：

用户希望一起浏览，但没有连续阅读语义。

### 使用 Edition，当：

两个来源实际上是同一 Work 的不同版本。

禁止：

- 同作者 = Series；
- 同目录 = Group；
- 标题相似 = Edition；
- 文件重复 = 可以自动删除。

## 9. 物理整理

默认只改 `.gallery/` 逻辑元数据。

如果用户明确要求整理真实目录：

1. 先生成计划；
2. 列出每个 source -> target；
3. 检查冲突；
4. 对具体计划取得用户确认，并备份便携数据；
5. 使用可恢复事务；
6. 复制/移动后验证；
7. 最后才清理空目录；
8. 更新 Asset 路径与 Inbox relocation；
9. 输出最终结果和失败项。

禁止“为了让目录更漂亮”自动执行物理移动。

## 10. 输出报告

每次大规模整理至少报告：

```text
Library:
Schema:
Scanned scope:

Created/updated:
- Works:
- Editions:
- Series:
- Groups:
- metadata fields:

Preserved manual decisions:
Conflicts skipped:
Ambiguous items:
Unsupported items:
Physical files changed: yes/no
Backups:
```

## 11. 写入来源

Agent 新写字段的 `field_sources` 使用：

`provider:<stable-agent-id>`

Inbox 决定的 `by` 使用 `agent:<stable-agent-id>`。二者是不同字段，不能混为统一来源值。保留已有来源标签，人工锁定的 tags 整组保护。revision / 时间戳由遵守协议的编辑器维护，不能随手重置。工具尚未支持的操作先交付差异计划和校验结果，不用手写整份 JSON 绕过保护。

不要伪装成 `manual`。
如果用户随后在 App 中手改，对应字段升级为 `manual`，以后 Agent 不覆盖。

## 12. 目标结果

完成整理后的 Library 应满足：

- Android 换机后只读 `.gallery/` 就能立刻看到整理结果；
- 真实媒体暂时未挂载时逻辑结构仍完整；
- 作者/系列/作品/章节关系语义明确；
- 用户能按作者、系列、Tag、Collection、时间等路径浏览；
- 保留媒体到 Work 的来源，支持后续 App 随机浏览回溯（不宣称当前随机浏览已实现）；
- 批量编辑后不会被下一次扫描“改回去”；
- 所有真实文件变更都有可审计记录。

## 13. 桌面校验与安全编辑工具

运行环境 Java 17+；Windows 使用 `library-tool.bat`，Linux/macOS 使用 `library-tool`。开发者从仓库执行 `:library-tool:installDist`，分发 `library-tool/build/install/library-tool/` 整个目录后无需 Android SDK、Gradle 或本机数据库即可运行。

```text
library-tool validate ROOT
library-tool preview ROOT PLAN.json
library-tool apply ROOT PLAN.json EXISTING_BACKUP_DIRECTORY --exclusive
library-tool recover-catalog ROOT --exclusive
library-tool export-guide ROOT GALLERY_LIBRARY.next.md
```

- ROOT 是 Library 根目录，含已存在的 v4 catalog；工具不会初始化、扫描、打开或哈希媒体。
- validate 校验四份便携文档（state/inbox 未创建可缺省）、身份、引用、路径及 Schema，输出 catalog_sha256；结构合法不表示媒体可达。
- preview 不写 Library，报告变更字段及 skipped_manual；结合计划中的目标值审阅。
- apply 仅编辑现有 Work，支持 display_title、original_title、domain、authors、tags、collections、favorite、cover_path、preferred_edition_id。列表是显式替换；追加时先合并旧值，清空用 [] 或可空字段的 null。人工锁会跳过并报告；稳定来源 Tag 不能移除；新结果标 provider:<agent_id>。
- `--exclusive` 表示已经断开/停止 Android 和其他写入者。工具只防同一主机工具并发，不与 Android 或别的主机形成分布式锁；不要同时运行 App 写入。
- expected_catalog_sha256 必须来自同一份未改动文档；提交前还核对 identity/state/inbox/schema/guide 未变化。冲突时拒绝，不自动刷新哈希绕过冲突。
- EXISTING_BACKUP_DIRECTORY 必须已存在且位于 Library 外。真实库使用另一介质并先完整备份 .gallery；工具另建唯一子目录保存本次读取的身份、catalog、state、Inbox、Schema 及库内说明，校验后才提交。
- 单次只提交 catalog，一次提升 catalog 和变更 Work 的 revision，保留其他字段及未知扩展；不改 state、Inbox、媒体、关系实体或 Library 身份。
- 目录里存在物理事务 JSON 时，此版工具保守拒绝编辑，不能判定历史/活动事务。不要为绕过检查删除事务；此限制待独立事务诊断支持。
- 文件系统必须支持同卷原子 rename；不支持则拒绝。中断后 live catalog 优先，否则从 .catalog.json.rem-backup 恢复旧版；recover-catalog 是显式恢复，正常 validate 不暗中修复。恢复后重新 validate/preview；新 revision 已提交时旧计划应被拒绝。
- export-guide 创建新文件且拒绝覆盖，不替换 GALLERY_LIBRARY.md；审阅并合并库内用户规则后再按显式授权更新原说明。新说明分发不修改 Schema。

计划示例（先用 validate 获取实际 ID 与 SHA-256）：

```json
{
  "library_id": "实际 Library UUID",
  "expected_catalog_sha256": "validate 输出的 catalog_sha256",
  "agent_id": "local-organizer",
  "edits": [
    {"work_id": "稳定 Work ID", "set": {"authors": ["作者"], "collections": ["写真"]}}
  ]
}
```

不要把用户要求的明确清空误解为未填写，也不能让工具把 Agent 决定伪装为 manual。App 内人工批量编辑与此工具的自动字段保护是不同的授权语境。
