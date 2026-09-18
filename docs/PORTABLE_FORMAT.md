# Rem Portable Format Contract

> 本文件是 `.gallery/` 的权威契约。
> 产品语义见 `PRODUCT.md`；当前 Kotlin 实现见 `ARCHITECTURE.md`。
> 当前稳定前格式为 Schema v4。

## 1. 核心原则

`.gallery/` 是 Library 的便携真相。

设备本机以下内容都不是便携真相：

- SQLite 查询索引；
- SAF URI；
- 扫描 checkpoint；
- 补全队列；
- 日志；
- 解码缓存；
- ZIP/CBZ 缓存；
- 离线预览。

删除以上本机数据后，用户的人工决定和逻辑结构不得丢失。

## 2. Library 目录

```text
GALLERY_LIBRARY.md
.nomedia
.gallery/
  library.json
  schema/v4.json
  items/catalog.json
  state/state.json
  state/inbox.json
  imports/
  transactions/
  backups/
```

路径要求：

- 使用 `/`；
- 相对 Library root；
- 禁止 Android content URI；
- 禁止 Windows 盘符；
- 禁止绝对路径；
- 禁止 `.` / `..` 路径逃逸。

## 3. Catalog 实体

### Asset

物理来源：

- file
- directory
- archive
- system_import（系统相册复制导入）

保存物理来源相关信息，不保存标题、作者、Tag、Series 等逻辑元数据。

### Work

用户实际管理的逻辑内容。

可保存：

- domain/type
- title/original title
- authors（作者/创作者列表）
- tags
- collections
- cover choice
- favorite
- field provenance

Work 通过 Edition 取得物理内容。

### Edition

表示同一 Work 的一个取得版本。

```text
Edition -> Work
Edition -> ordered Asset sources
```

允许角色：

- primary
- page
- image
- video
- bonus
- cover
- alternate

Edition 比较或虚拟合并不能自动删除来源。

### Group

```text
Group -> ordered/unordered Work members
```

用于“一起浏览”。
不能用于表达同一 Work 的版本，也不能隐式表示作者。

### Series

```text
Series -> ordered Work members
```

用于有连续顺序关系的 Work。
成员可保存：

- sort_index
- season / episode
- volume / chapter

手动顺序优先。

### State

```text
State -> Work
```

保存便携阅读/观看状态与逻辑回收站等用户状态。

## 4. 关系唯一拥有者

关系只能在一个位置拥有成员事实：

```text
Edition -> Work, Asset
Group   -> Work
Series  -> Work
State   -> Work
```

禁止为了查询方便再把同一成员列表复制成第二份便携真相。
设备索引可以冗余，但必须可从上述实体完整重建。

## 5. Provenance

人工决定优先。

至少区分：

- `manual`
- `provider:<id>`（Agent 产生的字段结果）
- 自动识别/来源提供者

规则：

1. `manual` 不被自动流程覆盖；
2. Agent 逐字段合并，不得整行重写抹掉 provenance；
3. Android 弱识别只能补充未锁定字段或生成建议；
4. 若用户明确清空某字段，应能表达“人工无值”，不能被下次扫描重新填回。

## 6. Inbox

`.gallery/state/inbox.json` 保存用户对发现目标做过的决定。

典型 disposition：

- accepted
- classified
- ignored
- handled

要求：

- 键使用 Library-relative path；
- Work 目标同时保留稳定 `work_id`；
- Organizer 移动后同步 relocation；
- ignored / handled 不能因本机数据库重建而复活；
- accepted/classified 必须先成功保存对应 Work 决定，再提交 Inbox disposition；
- `handled` 只用于“这个发现无需 Rem 管”的 discovery，不冒充 Work 分类。

## 7. 新设备接入语义

接入顺序必须允许：

1. 只读 `library.json` 和 catalog/state/inbox；
2. 立即建立逻辑投影；
3. 显示 Work / Edition / Group / Series 和人工元数据；
4. 后续再做媒体 inventory、URI 解析、缩略图和 enrich。

媒体未找到：

- 标记为 `missing_media` 一类正常状态；
- 不得标为损坏；
- 不得删除实体；
- 不得从 Group/Series 中移除；
- 不得覆盖人工元数据。

曾经确认存在、后来本机路径读取失败的异常状态可以单独使用 `needs_repair` 类语义。
二者不得混用。

## 8. 移动 Library

Library root 是解析基准，不写进 Asset 的永久身份。

因此：

- 从 `S:\Rem-lib` 移到 Linux 挂载点不需要迁移 catalog；
- Android 重新授权新 root 后可重新解析相对路径；
- 主机用户名、卷标、挂载点只是运行环境，不是格式字段。

## 9. 原子写入

便携文档修改必须具有：

- revision / 冲突检测；
- staging；
- 稳定恢复槽或等效恢复机制；
- 中断后下一次读取可恢复；
- 提交后重新确认目标路径和内容身份。

Provider 返回“rename success”不能单独作为提交成功证据。

## 10. Schema 变更

Schema 变更前必须定义：

1. 输入版本；
2. 输出版本；
3. 备份范围；
4. 每个文档的迁移顺序；
5. 中途退出后的可恢复状态；
6. 幂等重试；
7. library identity 最终提交时机；
8. 未知更高版本拒绝策略。

当前规则：
Schema v3 只有一条显式 backup-first 转 v4 路径；
更早试验格式不继续扩展兼容；
未知更高版本拒绝写入。

## 11. 物理事务

元数据关系变化与真实文件变化必须分开。

真实移动/重命名/删除要求：

- 预览计划；
- 输入快照；
- 冲突检查；
- journal；
- copy/rename 后验证；
- 删除源在验证之后；
- 中断可重试；
- 当前来源已变化时拒绝继续猜测。

## 12. Agent 写入

整理 Library 的 Agent 也必须遵守本格式。

- 读取当前 Schema；
- 先备份；
- 使用稳定 ID；
- 保留 `manual`；
- 自己产生的字段标明 `provider:<id>`，Inbox 决定的 `by` 使用 `agent:<id>`；
- 不用绝对路径；
- 不把物理目录结构写成不可逆永久推断；
- 有歧义时保守保留，并输出报告。

详细工作方式见 `LIBRARY_AGENT.md`。

## 13. 当前 v4 的序列化字段

本节按当前模型核对，不引入 Schema 变化。名字均为 JSON 名称，不得用概念名称 creator/author 代替 `authors`，也不序列化运行时 `items` 投影。

对应实现：[媒体模型](../app/src/main/java/dev/susnowy/gallery/model/MediaModels.kt)、[Library 模型](../app/src/main/java/dev/susnowy/gallery/model/LibraryModels.kt)、[Inbox 模型](../app/src/main/java/dev/susnowy/gallery/model/InboxModels.kt)、[库内说明生成器](../app/src/main/java/dev/susnowy/gallery/library/PortableLibraryManager.kt)。代码是核对实现的证据，改变格式仍需先修改本契约。

新写文档显式写头部、实体 revision 和集合；读取器的默认值不是鼓励省略契约字段。时间戳使用 ISO-8601 UTC 文本，进度 page 从 0 开始，position_ms 是毫秒。

| 文档/实体 | 应显式写入 | 可选及默认 |
| --- | --- | --- |
| library.json | format=`gallery-library`、schema_version=4、library_id、name、created_at、updated_at | 无 revision |
| catalog.json | schema_version=4、library_id、revision、updated_at、assets、works、editions、groups、series | 集合为空时写 []；初始文档 revision=0 |
| Asset | id、relative_path、media_type、source、revision、updated_at | secondary_path/content_hash=null |
| Work | id、type、domain、display_title、revision、updated_at | original_title/preferred_edition_id/cover_path=null；authors/tags/collections=[]；favorite=false；field_sources={} |
| Edition | id、work_id、assets、revision、updated_at | label=null；assets 非空 |
| Edition.assets 成员 | asset_id | role=primary；sort_index=null；entry_path=null |
| Group | id、title、type、ordered、members、revision、updated_at | type=media_set；ordered=true；members=[]；cover_work_id=null；field_sources={} |
| Group.members 成员 | work_id | role=item；sort_index=null |
| Series | id、title、members、revision、updated_at | aliases=[]；members=[]；field_sources={} |
| Series.members 成员 | work_id | sort_index/season/episode/volume/chapter=null |
| state.json | schema_version=4、library_id、revision、updated_at、progress、trash | 集合为空；初始 revision=0 |
| progress 成员 | work_id、last_opened_at | page=0；position_ms=0；finished=false；opened_at=null |
| trash 成员 | work_id、relative_path、deleted_at | 无 |
| inbox.json | schema_version=4、library_id、revision、updated_at、decisions | decisions=[]；初始 revision=0 |
| decisions 成员 | relative_path、target、disposition、by、decided_at | work_id/domain/reason/note=null；解析默认 by=manual，Agent 必须显式写 agent:<id> |

实体 revision 初始为 1；成员没有独立 revision。sort_index/episode/volume/chapter 为数值，season 为整数。空值语义保留，不用空字符串冒充未知数值。

枚举：

- type/media_type：image、image_set、video、photo、photo_video、live_photo。
- source：file、directory、archive、system_import（不是 imported source）。
- domain：album、classified、works，与物理来源类型无关。
- Group.type：media_set、manual_collection；Group 成员 role：item、image、video、bonus、cover。
- Edition 成员 role 见第 3 节；Inbox target：media、discovery；disposition 见第 6 节。

`entry_path` 相对其目录/归档 Asset，不相对 Library；同样禁止路径逃逸。`cover_path`、`secondary_path` 是 Library 相对路径。entry_path 配合有序 page 来源表达虚拟页计划；不复制媒体、不伪造哈希。

## 14. 引用、来源及提交完整性

- ID 在各自实体集合内唯一且稳定；Asset relative_path 唯一。Edition 必须引用本 Library 存在的 Work/Asset；Work.preferred_edition_id 若有值，必须属于该 Work。
- Group/Series 成员引用已有 Work，组封面引用成员；Series 内成员不重复。当前 App 单个 Work 的运行时投影只有一个 Series，新整理默认遵守一个主要 Series，勿利用可序列化列表制造 App 无法完整表达的多归属。
- 每个要进入当前 App 投影的 Work 至少有一个含有效 Asset 引用的 Edition；媒体文件缺失可以，引用断裂不可以。
- 删除 Work 元数据时清理 Edition、Group/Series、State、Inbox 引用；只移除已无引用的 Asset 元数据，不因此删除媒体。普通回收站不执行这套实体删除。
- `field_sources.tags=manual` 保护整组标签，包括追加和去重；人工清空保留对应 manual 锁。手工 Series 关系编辑对受影响 Work 写 `field_sources.series=manual`，包括退出系列后的人工作空决定。
- 保留已有来源值及 source:jm、jm:album:<id>、source:ehviewer、eh:gid:<id>、source:pixiv、pixiv:id:<id> 等稳定标签。字段来源可见 manual、import、library、filename、comic_info、system_import、provider:<id>；不要把 Inbox.by 的 agent:<id> 混入新字段来源约定。
- 不声明现有 App 已完整实现任意来源之间的优先级排序；manual 锁是硬约束，其余按已授权规则逐字段保守合并。
- 缺失标志、URI、页数补全、拍摄信息缓存不能擅自新增进便携文档；新人工字段需要明确格式设计。
- 保留未理解字段，不通过“重新构造已知字段”把它们丢掉。未知更高 Schema 拒绝写入；当前版本未知语义影响安全时停在计划阶段。
- 当前 Android 便携写入有进程内 mutex 和部分 revision 检查，不能等同跨进程或跨设备锁。外部 Agent 与 Android 不同时写同一 Library；提交前复读比较基线，冲突时重做合并。
- 现有稳定恢复槽为同目录 `.<name>.rem-backup`；有效 live 文档优先，无 live 时读流程恢复槽。不要删除活动恢复槽或伪造事务记录。任意 JSON Schema 校验器不能把生成的 v4.json 概要当完整可执行校验器。

## 15. 最小完整 catalog 示例

以下是示例 Library 的文档，不能覆盖真实库；配套 library/state/inbox 的 library_id 必须相同。示例没有声称媒体一定可达。

```json
{
  "schema_version": 4,
  "library_id": "example-library",
  "revision": 1,
  "updated_at": "2026-09-18T00:00:00Z",
  "assets": [{"id":"a1","relative_path":"Comics/Book01","media_type":"image_set","source":"directory","revision":1,"updated_at":"2026-09-18T00:00:00Z"}],
  "works": [{"id":"w1","type":"image_set","domain":"works","display_title":"第一本","authors":["示例作者"],"tags":[],"collections":[],"preferred_edition_id":"e1","favorite":false,"field_sources":{"display_title":"provider:example","authors":"provider:example"},"revision":1,"updated_at":"2026-09-18T00:00:00Z"}],
  "editions": [{"id":"e1","work_id":"w1","assets":[{"asset_id":"a1","role":"primary"}],"revision":1,"updated_at":"2026-09-18T00:00:00Z"}],
  "groups": [],
  "series": []
}
```

外部编辑器应负责 revision 和时间戳的协议维护，不能让语言模型凭猜测手改；该编辑/校验工具链尚未完成，见 [STATUS](STATUS.md)。初始化/升级时 identity 最后提交；一般多文档编辑不具备整体原子性，需计划有效中间态、备份及恢复，不承诺一次 rename 解决全部文档一致性。
