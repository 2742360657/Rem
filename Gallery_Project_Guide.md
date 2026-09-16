# Gallery 项目工程指导文档

## 0. 当前产品决策（2026-09-16，优先于下文旧描述）

Gallery 的一级信息架构固定为三个入口：

1. **相册**：图片与视频混排，按拍摄时间浏览，并显示来源位置、时间等文件信息；不提供作者、Tag、Series 等作品管理能力。
2. **图片 / 视频**：一个只按真实目录分层的“可分类相册”。目录就是分类，不用 Collection 或 Tag 伪装文件夹，也不在首页堆叠额外功能。
3. **漫画 / 动漫**：作品库。漫画、写真集、动漫、电影和剧集在这里使用作者、Tag、Series、搜索、排序、阅读/观看进度等能力。

Library 是用户显式授权的实体文件夹。日常分类只写便携元数据，不移动真实文件；只有预览并确认 Organizer 计划后才能改名或移动。每个 Library 都要生成可供用户、脚本和 Agent 阅读的 `GALLERY_LIBRARY.md`，让外部 Agent 能根据稳定规则整理目录。

Schema v3 使用 `domain` 明确记录条目属于 `album`、`classified` 或 `works`，避免仅凭容易变化的路径猜测上层视图。缺少 `domain` 的旧条目由媒体类型、目录约定和文件名推断；人工选择写回便携元数据并优先于后续自动识别。

第一版推荐约定：

```text
Photos/                 # 相册导入
Images/                 # 分类图片
Videos/                 # 分类视频
ImageSets/ 或 Comics/   # 漫画、写真、图集
Anime/ Movies/ Series/  # 动漫、电影、剧集
Works/                  # 其他作品
```

这些名称不是接入门槛。任意现有文件夹都可以成为 Library；扫描器应兼容“作者目录/编号作品目录/顺序图片”、漫画“系列/章节/页面”、`S01E02` 视频、ZIP/CBZ、混合图片与附带视频等真实下载结构。自动识别必须可人工修正，失败时仍允许按原目录正常浏览。

> 用途：指导后续本地 Agent 在**现有 Git 仓库**中持续开发 Gallery。  
> 本文描述项目目标、必须保持的数据语义、推荐技术路线、模块边界和开发顺序。  
> **不要把它当成“一次性全部实现”的提示词。** Agent 应分模块实现、验证、提交 Git，再进入下一阶段。

---

## 1. 项目目标

Gallery 是一个 Android 端、本地优先的媒体库 / 阅读器 / 播放器。

第一阶段主要处理：

- 普通图片；
- 相册中的图片和视频；
- `ImageSet`：漫画、图集等“按顺序阅读的一组图片”；
- 本地视频：普通视频、电影、动画、系列视频；
- ZIP / CBZ 形式的 ImageSet；
- 可移动存储中的便携媒体库；
- 作者、标签、Collection、Series、搜索、阅读/观看进度；
- 安全的文件整理、删除和恢复。

第一阶段只做 Android 客户端。未来可以单独实现 Windows/Linux 客户端，但从一开始就要保证 Library 格式与平台无关。

核心体验：

```text
选择文件夹作为 Library
→ Library 自己携带元数据
→ Android / 未来 PC / 本地 Agent 都能理解
→ 日常分类不乱动真实文件
→ 需要时显式整理底层目录
```

项目必须避免两种极端：

1. 所有分类都只是手机 App 私有数据库，硬盘拿到电脑上后一团糟；
2. 每改一次分类都真的移动、复制大量文件。

---

# 2. 不应轻易改变的核心原则

## 2.1 Library 必须显式接入

不默认扫描并接管所有目录。

用户通过 Android 目录选择器主动选择一个文件夹作为 Library。初始化后 Library 获得稳定 `libraryId`。

以后即使：

- 移动硬盘重新插入；
- Android 挂载路径变化；
- Windows 盘符变化；
- 换设备；

也通过 Library 自身身份识别，而不是依赖绝对路径。

---

## 2.2 逻辑分类与物理文件分离

以下行为**默认只修改索引/元数据，不移动、不复制文件**：

- Collection；
- Author；
- Tag；
- Series；
- 收藏；
- 搜索结果；
- 动态筛选；
- 已读/未读；
- 阅读/观看进度。

一个作品可以同时：

```text
Collection = 收藏
Author = A
Tags = 校园, 彩色
Series = B
```

真实文件仍只有一份。

---

## 2.3 只有显式操作才能改变底层文件

允许用户执行：

```text
按当前规则整理底层文件
```

但必须：

1. 生成计划；
2. 展示预览；
3. 检查冲突；
4. 用户确认；
5. 建立事务记录；
6. 执行移动/重命名；
7. 更新元数据；
8. 校验；
9. 清理安全的空目录。

用户仅仅修改 Collection、Tag、Author 时绝不能自动搬动文件。

---

## 2.4 物理目录只服从一套主整理规则

逻辑上可以无限交叉分类，但一个真实文件只有一个物理位置。

整理时使用**可配置模板**，例如：

```text
ImageSet:
ImageSets/{author}/{title}/

Photos:
Photos/{year}/{month}/

Movie:
Videos/Movies/{title}/

Series:
Videos/Series/{series}/{season}/{episode} - {title}.{ext}
```

允许保存多套模板，如“按作者整理”“按系列整理”。

一次整理只选择一套主规则。

缺少字段时合理省略目录层，不要生成大量：

```text
Unknown/Unknown/Unknown/
```

---

## 2.5 原始媒体默认不改写

默认：

- 不改 EXIF；
- 不改视频内部 Metadata；
- 不为了兼容自动转码；
- 不为了 Tag 重写图片；
- 不把 Gallery 元数据写进媒体文件。

Gallery 的分类、显示名、作者、标签等写入旁路元数据。

复制、移动、重命名属于文件管理，不属于媒体内容改写。

---

# 3. 三层数据结构

项目必须明确分成：

```text
真实媒体文件
+
Library 便携元数据
↓
设备本地索引 / 缓存
↓
UI
```

## 3.1 真实媒体文件

例如：

```text
001.jpg
IMG_001.HEIC
movie.mkv
episode_01.mp4
comic.cbz
```

这是内容本体。

## 3.2 便携元数据

随 Library 文件夹一起移动，保存：

- Library 身份；
- 作品信息；
- Author / Tag / Collection / Series；
- 来源；
- 用户覆盖字段；
- 阅读/观看状态；
- 回收站状态；
- 文件关系；
- Schema 版本；
- 文件事务。

**它是跨设备持久化真相来源。**

## 3.3 Android 本地数据库和缓存

Android 可使用 Room / SQLite 保存：

- 搜索索引；
- 扫描结果；
- 路径映射；
- 作者/标签倒排索引；
- 缩略图索引；
- UI 查询缓存。

原则：

> 删除本机 Room 数据库后，应能通过 Library 重新构建主要业务状态。

缩略图、视频预览、解码缓存也只放本机，不污染移动 Library。

---

# 4. 推荐 Library 目录

具体命名可调整，但职责应保持：

```text
GalleryLibrary/
├── GALLERY_LIBRARY.md
├── .gallery/
│   ├── library.json
│   ├── schema/
│   ├── items/
│   ├── series/
│   ├── collections/
│   ├── authors/
│   ├── state/
│   ├── imports/
│   ├── trash/
│   ├── transactions/
│   └── backups/
│
├── Photos/
├── Images/
├── ImageSets/
├── Videos/
└── Inbox/
```

这些顶层媒体目录只是默认推荐，不要求用户已有 Library 必须完全符合。

PC 上直接丢进来的其他目录也应该允许被扫描和整理。

---

# 5. `GALLERY_LIBRARY.md`

初始化 Library 时，在根目录生成一份给用户、脚本和 Agent 看的说明：

```text
GALLERY_LIBRARY.md
```

至少写清：

- Library 身份和 Schema 版本在哪里；
- `.gallery` 各目录作用；
- JSON 字段规范；
- 路径必须是 Library 根目录下的相对路径；
- 哪些字段可人工/Agent 修改；
- 哪些字段是程序维护字段；
- 如何新增 ImageSet；
- 如何改标题、作者、Tag、Collection、Series；
- 修改后 App 如何重新扫描；
- 不应直接改 Android 本地 Room 数据库；
- 不应随意删除 `.gallery`；
- Agent 不应自行发明未知字段。

目标场景：

> 用户在 PC 上批量放入很多漫画后，可以让本地 Agent 阅读 `GALLERY_LIBRARY.md`，再直接批量修改合法元数据完成初步分类。

---

# 6. Schema 与版本

`.gallery/library.json` 必须有显式版本，例如：

```json
{
  "format": "gallery-library",
  "schema_version": 1,
  "library_id": "UUID",
  "name": "My Library"
}
```

规则：

- 客户端先检查 `schema_version`；
- 支持的旧版本可迁移；
- 迁移前备份元数据；
- Schema 升级不自动改变原媒体；
- 遇到比客户端更新的 Schema 时不要盲写，可只读或拒绝写入；
- Agent 新增字段时必须同步修改 Schema 说明。

---

# 7. 核心领域对象

## 7.1 `Library`

独立便携媒体库。

便携字段示例：

```text
id
name
schemaVersion
createdAt
updatedAt
```

Android 本机另存：

```text
treeUri
permissionState
lastScanAt
```

Android URI 不能成为跨平台 Library 身份的一部分。

---

## 7.2 `MediaAsset`

表示真实文件。

可记录：

```text
relativePath
mediaType
fileSize
modifiedTime
contentHash
mimeType
width
height
duration
capturedAt
```

**路径不是身份。**

用户可能在 Windows/Linux 文件管理器里移动或重命名文件。App 应结合路径、大小、Hash、修改时间和目录上下文重新定位。

无法可靠判断时进入“需要修复”，不要瞎关联。

---

# 8. 媒体类型

## 8.1 普通图片

单张观看和管理。

支持：

- 缩放；
- 前后切换；
- Author；
- Tag；
- Collection；
- 收藏；
- 搜索。

普通图片与“相册照片”在产品语义上互斥。

如果用户要把相册照片或漫画页变成普通图片，使用显式**复制/派生**。

---

## 8.2 `ImageSet`

漫画和图集统一为同一个底层类型：

```text
ImageSet
```

含义：

> 一组有明确顺序、用于连续阅读的图片。

底层可以是：

### 普通目录

```text
WorkA/
├── 001.jpg
├── 002.jpg
└── 003.jpg
```

### ZIP / CBZ

```text
WorkA.zip
WorkB.cbz
```

第一版支持文件夹、ZIP、CBZ。

RAR / CBR / 7z 可后续增加。

### 默认顺序

采用自然文件名排序：

```text
1.jpg
2.jpg
3.jpg
10.jpg
```

不能错误排序为：

```text
1
10
2
3
```

### 阅读模式

第一优先级：

```text
vertical_scroll
```

即从上到下连续阅读。

架构可以预留：

```text
paged
left_to_right
right_to_left
```

但不要第一阶段就为所有模式过度设计。

### 作品入口与阅读交互

漫画/ImageSet 的列表点击不能直接跳进阅读器。推荐层级是：

```text
作品列表
→ 作品详情（封面、作者、Tag、路径、进度、编辑）
→ 沉浸阅读
```

阅读器短按页面显示或隐藏控件，长按页面打开针对当前页的操作栏；系统返回先从阅读器回到作品详情，再回到作品列表。列表缩略图长按也应提供编辑、收藏、派生和回收站等上下文操作。

左侧侧栏只放 Library 与全局管理。目录导航、筛选、排序和当前页操作属于具体页面，应放入各自的右侧工具栏；关闭工具栏不能清空筛选或目录状态。切换一级页面或在图片/视频之间切换时，应分别保留各自的浏览路径。

### 页面元数据

一部 500 页漫画不要自动生成 500 个页面 JSON。

只有需要覆盖默认行为时记录额外信息，如：

- 手动页序；
- 排除广告页；
- 指定封面。

---

## 8.3 相册 `Photos View`

相册是特殊系统视图，不是普通 Collection。

里面可以按时间混排：

```text
Photo Image
Photo Video
Photo Image
Photo Video
```

关注：

- 拍摄时间；
- EXIF；
- GPS；
- Live Photo / Motion Photo；
- 来源目录；
- 时间线。

---

## 8.4 视频

视频分：

### 相册视频

放在 Photos View 中按时间线浏览。

### 作品视频

如：

- 电影；
- 动画；
- 纪录片；
- 系列视频。

拥有：

- Title；
- Author / Studio；
- Tag；
- Series；
- Season；
- Episode；
- `sortIndex`；
- Cover；
- Watch Progress。

扩展名不决定语义：

```text
VID_001.mp4 -> 相册视频
01.mp4      -> 动画集数
movie.mkv   -> 电影
```

---

# 9. 派生与引用

必须区分：

## 9.1 Reference

不产生新文件：

- Collection；
- Tag；
- Author；
- Series；
- 收藏；
- 搜索结果。

## 9.2 Derive

真的创建新文件：

- 漫画某页复制成普通图片；
- 相册照片复制成普通图片；
- 多张普通图片组成新 ImageSet。

允许内容 Hash 完全相同，但它们仍是独立媒体对象。

可记录：

```text
derivedFrom
```

仅用于追踪来源，不建立删除依赖。

---

# 10. Series

ImageSet 和视频共用通用 `Series`。

不要重复设计 `ComicSeries`、`AnimeSeries`、`MovieSeries`。

可选字段：

```text
seriesId
seriesTitle
season
episode
volume
chapter
entryTitle
sortIndex
```

真正决定排序的是：

```text
sortIndex
```

这样可支持：

- Vol；
- Chapter；
- Season；
- Episode；
- SP；
- OVA；
- 特典；
- 番外；
- 电影系列。

第一版一个 Work 只需要有一个主 Series。

---

# 11. Author、Tag、Collection

## 11.1 Author

Author 是独立实体，允许表示：

- 作者；
- 画师；
- 社团；
- Studio / 制作方。

支持 Alias：

```text
作者A
A-sensei
作者A老师
```

指向同一实体。

## 11.2 Tag

允许普通标签：

```text
彩色
校园
高质量
```

也允许 namespace：

```text
genre:科幻
theme:校园
character:角色A
language:中文
```

不强制所有 Tag 使用 namespace。

## 11.3 Collection

用户自由建立的逻辑集合：

```text
收藏
待看
东方
2026收藏
```

第一版 Collection 只属于单个 Library，不做跨 Library 持久成员关系。

---

# 12. 搜索与多 Library

第一版搜索至少支持：

- 显示标题；
- 原始标题；
- Author；
- Tag；
- Series；
- Collection；
- 媒体类型；
- 收藏；
- 已读/未读；
- 未整理；
- 未识别。

支持组合筛选。

多 Library 同时在线时：

- 可跨在线 Library 聚合搜索；
- Author / Tag 可聚合展示；
- 可以限定单一 Library。

Library 离线时：

> 显示离线，不把其内容误判成已删除。

跨库复制/移动必须显式执行。

---

# 13. Android 技术路线

推荐第一版：

```text
Kotlin
Jetpack Compose
Storage Access Framework (SAF)
Room
AndroidX Media3 / ExoPlayer
Kotlin Coroutines
WorkManager（仅适合持久后台任务时）
AndroidX ExifInterface
Coil 或等价成熟图片加载库
kotlinx.serialization 或等价序列化方案
```

具体依赖版本由 Agent 实现模块时检查当前稳定版本，不在本文档长期钉死。

## 13.1 SAF

通过 `ACTION_OPEN_DOCUMENT_TREE` 等机制让用户选择 Library 根目录，并持久化合法授权。

不要依赖：

```text
/storage/XXXX-XXXX/...
```

作为永久路径。

业务层最好封装统一 Storage API，不要让 `DocumentFile` / `ContentResolver` / `Uri` 散落到所有领域代码。

---

# 14. 扫描、Inbox 与外部改动

## 14.1 增量扫描

Library 接入后：

```text
识别 libraryId
→ 默认增量扫描
→ 新内容进入 Inbox
→ 更新本机索引
```

支持手动：

```text
Full Rescan
```

扫描只更新索引，不移动媒体。

设置里允许关闭“接入后自动扫描”。

---

## 14.2 PC 导入的典型场景

用户常会在 PC 上直接放入：

```text
AuthorA/
├── Work1/
│   ├── 001.jpg
│   └── ...
├── Work2/
│   ├── 001.jpg
│   └── ...
└── video.mp4
```

Android 扫描后：

- `Work1` / `Work2` 作为 ImageSet 候选；
- `AuthorA` 这种上层目录视为物理组织目录；
- 视频作为视频候选；
- 进入 Inbox 后允许批量设置 Author / Tag / Collection / Series。

默认不移动真实目录。

---

## 14.3 嵌套识别

### 叶子型图片目录

主要包含图片、没有明显下级作品目录：

```text
Work/
├── 001.jpg
├── 002.jpg
└── 003.jpg
```

候选为 ImageSet。

### 上层组织目录

主要只包含多个子目录：

```text
AuthorA/
├── Work1/
└── Work2/
```

不登记为 ImageSet。

### 混合目录

同时大量包含图片和多个子目录时，标记结构不明确，进入待确认。

### ZIP / CBZ

直接作为 ImageSet 候选。

---

## 14.4 外部移动 / 重命名

允许用户直接在 Windows/Linux 文件管理器中操作 Library。

路径变化后尝试通过：

- 路径历史；
- 文件大小；
- 修改时间；
- Hash；
- 目录内容特征；

重新定位。

无法可靠匹配：

```text
Needs Repair
```

不要自动乱绑定。

---

# 15. 相册导入

手机现有系统相册不属于 Library，因此使用独立入口：

```text
Import System Media
```

默认：

> **复制，不移动，不删除源文件。**

尽可能保留：

- 原始文件字节；
- 文件名；
- 扩展名；
- EXIF；
- GPS；
- 拍摄时间；
- 视频元数据；
- Live Photo / Motion Photo 关系。

记录来源，例如：

```text
DCIM/Camera
Pictures/Screenshots
Pictures/WeChat
```

后续显式整理时允许重新组织进入 Library 的副本，并清理安全的空目录。

---

# 16. Live Photo / Motion Photo

GIF、Animated WebP、APNG 等仍按单一动态图片处理。

照片 + 短视频型 Live Photo / Motion Photo：

```text
LiveAsset
├── photo
└── motion
```

逻辑上显示为一个相片。

要求：

- 原文件不合并、不转码；
- 自动尝试配对；
- 无法判断时允许人工绑定；
- 移动、复制、删除时默认整体处理。

---

# 17. 自动元数据识别

识别优先级建议：

```text
1. Library 已有元数据
2. ComicInfo.xml 等本地标准元数据
3. 文件夹 / 压缩包名称
4. 在线 Metadata Provider
```

---

## 17.1 ComicInfo.xml

如果 ImageSet / CBZ 内存在 `ComicInfo.xml`，优先解析可用字段，如：

- Title；
- Series；
- Number；
- Volume；
- Writer；
- Genre；
- Web；
- PageCount；
- Language。

Gallery 不需要把 ComicInfo.xml 当自己的主格式，它只是可兼容的输入来源。

## 17.2 下载器目录与来源 ID

第一版离线扫描识别以下稳定结构，目录仍然是用户的真实目录，不会因识别而移动：

- 禁漫 / JM：`JM/<album_id>/`，其中 `album_id` 为纯数字；如果还有一层纯数字目录，按 `photo_id` / 章节处理。也识别文件名中的 `JM<id>`。
- EhViewer：默认下载目录 `<gid>-<title>/`，并以目录中的 `.ehviewer` 作为强校验标记；`gid` 保存为来源 ID。
- Pixiv：从 `<illust_id>_p<page>` 识别作品和页码，从 `<illust_id>_ugoira<尺寸>` 识别动图来源。同一扁平目录包含多个 Pixiv 作品 ID 时，不得把整个目录误合并为一本漫画。

机器可读 Tag 使用 `source:jm`、`jm:album:<id>`、`source:ehviewer`、
`eh:gid:<id>`、`source:pixiv`、`pixiv:id:<id>`。这些 Tag 是将来在线 Metadata Provider 的匹配锚点。

第一版不在 App 内实现站点抓取或自动联网同步。需要补全、校正标题、作者、标签、系列等
信息时，由用户明确要求 Agent 根据稳定来源 ID、目录和当前站点信息进行一次性识别与同步；
不要把容易失效的网页抓取规则硬编码进核心扫描器。

## 17.3 Agent 辅助识别与同步

Agent 处理 Library 前必须先阅读根目录的 `GALLERY_LIBRARY.md`、Schema 和当前条目，按字段合并，
不得把在线结果整条覆盖到本地条目：

1. 先使用 `source:*` 与来源 ID Tag 匹配；缺少可靠 ID 时可参考目录名、文件名和页数，但低置信度或多个候选必须交给用户确认。
2. 每个字段分别检查 `field_sources`。值为 `manual` 的字段是永久人工锁，Agent 不得修改、清空、追加、翻译、规范化或去重。
3. 没有人工锁的字段可以用已确认的来源数据补全或刷新，并把来源记为 `provider:<id>`；用户明确指定的值才标为 `manual`。
4. `tags` 当前是字段级保护而不是逐 Tag 保护：只要 `field_sources.tags == "manual"`，整组标签保持原样。未锁定时可以规范化、去重并同步，但必须保留稳定的 `source:*` 和来源 ID Tag。
5. `id`、相对路径、`revision`、时间戳和事务状态不得由 Agent 随意重写。批量修改前备份 `.gallery` 元数据，完成后让 App 重新扫描并向用户报告匹配失败、冲突和实际变更。
6. 账号、Cookie、Token 不得写入 Library、日志或 Git。Agent 只在用户授权的会话中临时使用；无法安全访问来源时保持现有数据不变。

这条流程是当前推荐的“同步”方式。将来即使增加 App 内 Provider，也必须复用相同的字段来源和人工锁规则。

## 17.4 动图和超大图片

- GIF 与 Animated WebP 走动态 Drawable 解码；Android 9 及以上使用平台 `ImageDecoder`。
- Pixiv ugoira 转换得到的 WebP/GIF 作为单一动态图片播放；原始 `_ugoira…zip` 仍视为压缩内容，不假装成标准视频。
- SVG 使用独立矢量解码器；HEIF、AVIF、DNG、ICO、WBMP 等由系统解码能力决定。
- 缩略图必须按视图目标尺寸解码，禁止为小卡片载入原始超高清位图。
- 对超过 4000 万像素或单边超过 16000 像素的静态图先读取 bounds，再按 800 万解码像素预算降采样；动画不走静态 Bitmap 路径。
- ZIP/CBZ 页面同样执行像素预算，防止超长条漫或扫描原图导致 OOM。

---

## 17.5 E-Hentai / ExHentai Provider（后续可选）

可以设计独立：

```text
MetadataProvider
```

而不是把 E-Hentai 网络代码散落在扫描/UI 中。

概念能力：

```text
search(query)
match(localItem, candidates)
fetch(candidate)
apply(metadata)
```

匹配规则：

```text
唯一 + 高置信度
→ 自动写入

多个候选 / 低置信度
→ 用户确认

无结果
→ 保持未识别
```

自动批量识别后应提供本次变更结果页。

可保存：

```text
provider
remoteId
remoteToken
sourceUrl
originalTitle
alternateTitle
authors
tags
language
category
pageCount
```

实际字段按 Provider 能力确定。

账号 Cookie / Token 只保存在当前设备安全区域，绝不能进入 `.gallery`、日志或 Git。

---

# 18. 元数据覆盖优先级

固定：

```text
人工修改
>
用户确认过的在线数据
>
自动匹配在线数据
>
文件名 / 文件夹名推测
```

例如：

```text
originalTitle = 在线来源原标题
displayTitle  = 用户自己的中文名称
```

以后刷新在线数据不能覆盖人工 `displayTitle`。

不需要做复杂 CRDT，但要能记录字段来源和人工覆盖状态。

---

# 19. 重复文件

可以使用：

```text
fileSize + contentHash
```

识别可能重复。

普通导入发现同内容：

> 提示“可能已存在”，默认不要重复复制。

但：

> **不自动去重。**

用户主动派生时允许两份完全相同内容同时存在。

Hash 大文件时按需后台计算，不能阻塞 UI。

---

# 20. 阅读/观看状态

状态随 Library 跨设备保存，但与作品元数据分开。

推荐：

```text
.gallery/items/
.gallery/state/
```

状态示例：

```text
ImageSet:
lastPage
finished
lastOpenedAt

Video:
positionMs
finished
lastOpenedAt
```

第一版按单用户 Library 设计。

---

# 21. 封面与缓存

ImageSet：

> 默认第一张有效图片作为封面，可手动指定页。

Video：

> 默认生成视频帧，可手动指定图片。

Series：

> 可单独设置封面。

封面缩略图本身属于本机缓存，`.gallery` 只需记录封面来源，不为了封面额外复制媒体。

首次在新设备打开大 Library 时允许逐步生成缓存；优先当前屏幕附近内容。

---

# 22. 删除与回收站

用户规则：

> 普通“删除”只列入 Gallery 回收站，不移动真实文件。

删除时记录：

```text
trashed = true
deletedAt = timestamp
```

普通视图隐藏。

## 恢复

只撤销标记，因为文件没有移动。

## 真删除

只有：

```text
从回收站删除
```

或自动到期清理时才真实删除底层文件。

## 自动清理

按 `deletedAt` 计算。

A 先进入、B 后进入：

> A 先到期。

保留期可设置：

```text
7 天
30 天
90 天
永久
自定义
```

真删除前再次验证文件身份，避免路径变化导致误删。

---

# 23. Organizer 与文件事务

整理、移动、重命名、真删除属于高风险操作。

不能简单连续 `move()`。

建议写：

```text
.gallery/transactions/<operation-id>.json
```

记录：

```text
operationId
createdAt
status
steps
source
target
completedSteps
```

移动硬盘突然断开时，下次重新接入应能识别未完成事务并恢复/继续/回滚。

整理流程：

```text
生成计划
→ 检查冲突
→ 用户预览
→ 确认
→ 建立 transaction
→ 逐步执行
→ 更新元数据
→ 校验
→ 标记完成
→ 安全清理空目录
```

不能静默覆盖同名文件。

生成路径时兼容 Windows/Linux/Android 常见文件名限制。

---

# 24. 多端冲突

Library 可能被：

- Android；
- 未来 PC 客户端；
- 本地 Agent；

修改。

每个便携元数据对象至少带：

```text
revision
updatedAt
```

保存前检查 revision。

如果磁盘内容已经更新：

> 不静默覆盖。

互不冲突字段可以合并，例如：

```text
A 改 title
B 加 tag
```

同字段冲突则提示选择。

不需要做复杂实时分布式协作，因为移动介质通常同一时间只接一台设备。

---

# 25. 开源模块复用原则

允许引用成熟开源局部能力。

不要形成两个极端：

- “所有东西自己造轮子”；
- “看到相似项目就整体照搬”。

原则：

> 局部问题复杂、社区已经有成熟维护方案、许可证兼容时，优先评估复用。

可参考：

### Android 官方 AndroidX

- SAF / DocumentsContract；
- Room；
- Media3 / ExoPlayer；
- WorkManager；
- ExifInterface；
- Lifecycle / Navigation 等。

### Coil

可用于 Compose 图片加载、Downsampling、内存/磁盘缓存。

### ComicInfo

可参考 `anansi-project/comicinfo` 对 ComicInfo.xml 的 Schema 和字段语义。

### EhViewer

可参考当前维护分支中的：

- E-Hentai / ExHentai 网络交互；
- Gallery 元数据解析；
- Tag 行为；
- 漫画阅读交互；
- ComicInfo 处理；
- 下载内容识别。

但 Gallery 的核心不是 EhViewer：

> E-Hentai 只是可选 Metadata Provider，Gallery 本质是本地便携媒体库。

**直接复制 EhViewer 代码前必须检查目标仓库和具体文件许可证。**  
如果许可证不适合当前项目，只参考设计/行为，不直接复制实现。

不要因为本文允许复用开源模块，就为了“复用”强行引入不必要依赖。

---

# 26. 第一阶段明确不做

暂时不要做：

- 云同步；
- 多用户；
- 实时协作；
- CRDT；
- 图片区域标注；
- 备注/笔记系统；
- 视频编辑；
- 图片编辑；
- 自动转码；
- 在线流媒体；
- AI 内容识别；
- 推荐系统；
- PC 客户端本身。

允许为未来保留合理扩展点，但不要提前堆抽象层。

---

# 27. 第一版必须跑通的完整链路

MVP 要真正跑通：

```text
接入目录
→ 初始化 / 识别 Library
→ 扫描
→ 新内容进入 Inbox
→ 识别图片 / ImageSet / 视频
→ 分类
→ 图片浏览
→ ImageSet 连续阅读
→ 视频播放
→ 保存进度
→ Author / Tag / Collection / Series
→ 搜索
→ 删除到回收站
→ 显式整理真实文件
→ 重新插拔移动介质
→ Library 正常恢复
```

这条链稳定比堆很多半成品页面重要。

---

# 28. 推荐 UI 功能区

逐步实现即可：

```text
Home
Libraries
Inbox
Photos
Images
ImageSets
Videos
Series
Collections
Authors
Tags
Search
Trash
Settings
```

---

# 29. 推荐模块边界

先保持逻辑职责清晰，不要求一开始就拆成很多 Gradle Module。

领域可按以下职责组织：

```text
library
storage
scanner
metadata
index
images
imageset
photos
video
series
organizer
trash
provider
search
ui
```

代码量上来以后再判断是否物理拆 Gradle module。

---

# 30. 分阶段开发顺序

后续 Agent 必须按阶段推进。

## Phase 0：仓库审计

先：

```text
git status
git branch --show-current
git log --oneline -n 10
```

再检查：

- 现有目录；
- Gradle；
- 当前能否构建；
- 已有代码；
- 用户未提交改动。

**不要重新 `git init`。**

不要覆盖已有工作。

---

## Phase 1：Library Core

实现：

- `libraryId`；
- `library.json`；
- Schema v1；
- 初始化/识别 Library；
- `GALLERY_LIBRARY.md` 初版；
- 本机 Library 登记。

完成后构建、测试、Commit。

---

## Phase 2：SAF Storage Layer

封装：

```text
list
stat
open
copy
move
delete
createDirectory
rename
```

目标：

- 目录授权；
- 持久权限；
- 移动介质重新接入；
- 离线状态。

业务代码不要到处直接碰 `DocumentFile` / `ContentResolver`。

---

## Phase 3：Scanner + Inbox

实现：

- 基础增量扫描；
- 新文件发现；
- 外部改动；
- Image / Video / ImageSet Candidate；
- 嵌套目录判断；
- ZIP / CBZ Candidate；
- Inbox。

先不做复杂在线 Provider。

---

## Phase 4：Room Local Index

实现高速本机索引。

必须验证：

```text
删除 Room DB
→ 重扫 Library
→ 主要索引恢复
```

---

## Phase 5：普通图片浏览

实现：

- 列表；
- 缩略图；
- 单图；
- 缩放；
- 前后切换；
- 本机缓存。

---

## Phase 6：ImageSet Reader

实现：

- 文件夹 ImageSet；
- 自然排序；
- 纵向连续阅读；
- 进度；
- 默认/指定封面；
- ZIP / CBZ；
- 大图集内存控制。

不要一次解码整部漫画。

---

## Phase 7：Metadata

实现：

- displayTitle / originalTitle；
- Author；
- Alias；
- Tag；
- namespace；
- Collection；
- 人工覆盖；
- 批量编辑；
- 搜索基础。

---

## Phase 8：Series

统一实现 ImageSet / Video 的 Series、`sortIndex`、卷、季、集、章。

---

## Phase 9：Trash

实现：

- 逻辑删除；
- 恢复；
- 真删除；
- 自动到期；
- 自定义保留期；
- 真删除前验证身份。

---

## Phase 10：Organizer

实现：

- 模板；
- 计划；
- 预览；
- 冲突检测；
- transaction；
- 中断恢复；
- 空目录清理。

这一阶段使用专门测试 Library，不拿唯一真实数据直接调试。

---

## Phase 11：系统相册导入

实现：

- 复制；
- 原数据保留；
- EXIF；
- 来源关系；
- Photos View；
- Live Photo / Motion Photo 基础支持。

---

## Phase 12：Video

Media3：

- 本地播放；
- Seek；
- 进度；
- 恢复；
- Series；
- 封面。

---

## Phase 13：Metadata Provider

顺序：

```text
ComicInfo.xml
→ 文件夹名解析
→ MetadataProvider 接口
→ E-Hentai / ExHentai
```

实现高置信唯一自动写入、低置信人工确认和人工字段保护。

---

## Phase 14：多 Library 与恢复

验证：

- 多 Library；
- 一个离线；
- 再接入；
- 全量重扫；
- 本机缓存丢失；
- DB 重建；
- 外部 Agent 改 JSON；
- revision 冲突。

---

# 31. Git 工作规则

本地**已经有 Git 仓库**。

Agent 必须基于现有历史开发。

## 31.1 每个模块开始前

至少查看：

```bash
git status
git branch --show-current
git log --oneline -n 10
```

存在用户未提交修改时：

> 不得擅自 reset、覆盖、删除。

---

## 31.2 每次只做一个相对独立目标

推荐工作流：

```text
理解当前仓库
→ 说明本次小目标
→ 实现
→ 编译
→ 测试
→ git diff
→ 修问题
→ Commit
→ 再进入下一模块
```

不要一次修改几十个无关文件。

---

## 31.3 Commit 要有语义

示例：

```text
feat(library): add portable library identity
feat(storage): persist SAF tree access
feat(scanner): detect image set candidates
feat(reader): add vertical image set reader
feat(metadata): add authors and tags
feat(trash): add logical recycle bin
feat(organizer): add move transaction journal
fix(scanner): recover moved assets by fingerprint
test(organizer): cover interrupted move recovery
docs(library): add portable library guide
```

不要：

```text
update
fix
stuff
finish
```

---

## 31.4 大模块允许多个小 Commit

例如 Organizer：

```text
feat(organizer): add planning model
feat(organizer): detect path conflicts
feat(organizer): add transaction journal
feat(organizer): execute safe move plan
test(organizer): recover interrupted operations
```

比几千行一个 Commit 更好。

---

## 31.5 禁止的 Git 行为

除非用户明确要求：

- 不 `git reset --hard`；
- 不丢弃用户未提交修改；
- 不 `git push --force`；
- 不重写历史；
- 不擅自 squash 用户 Commit；
- 不创建新远程仓库；
- 不提交 Cookie / Token / Secret。

如果仓库已有分支策略就沿用。

没有的话，不需要为了形式主义给每个小功能建大量分支；高风险模块如 Organizer、Schema Migration、Storage Rewrite 可单独开 feature branch。

---

# 32. 测试重点

这个项目的文件系统测试优先级很高。

至少覆盖：

## Library

- 新建；
- 重开；
- Schema；
- 无效 Library；
- 离线/再接入。

## Scanner

- 新增；
- 删除；
- 重命名；
- 移动；
- 内容变化；
- 大量目录；
- 混合目录；
- ZIP/CBZ。

## ImageSet

- `1, 2, 10` 自然排序；
- 损坏图片；
- 空目录；
- 超大图片；
- 数百/数千页。

## Organizer

- 同名；
- 权限失败；
- 空间不足；
- 中途拔盘；
- App 被杀；
- 恢复事务；
- 空目录清理。

## Trash

- 删除不移动文件；
- 恢复；
- 真删除；
- 到期顺序；
- 路径变化后不误删。

## Portable Metadata

- Android 修改；
- Agent 修改；
- revision 冲突；
- Room 删除后重建。

---

# 33. 性能原则

不追求无意义 Benchmark，但要做到：

- 不在主线程扫描；
- 不在主线程 Hash 大文件；
- 不一次解码整个 ImageSet；
- 不一次性生成全部缩略图；
- Lazy List；
- 按需加载；
- 增量扫描；
- 缓存持久化；
- 大任务显示进度；
- 长任务可取消。

WorkManager 只用于需要持久保证的后台任务；普通短异步任务用 Coroutine 即可。

---

# 34. 元数据 JSON 示例

下面只是语义示例，不是不可改变的最终 Schema：

```json
{
  "schema_version": 1,
  "id": "UUID",
  "revision": 4,
  "type": "image_set",

  "display_title": "用户显示名称",
  "original_title": "来源原标题",

  "source": {
    "kind": "directory",
    "path": "ImageSets/raw-folder"
  },

  "authors": ["author-id"],

  "tags": [
    "theme:校园",
    "彩色"
  ],

  "series": {
    "id": "series-id",
    "sort_index": 2,
    "volume": 2
  },

  "reading": {
    "mode": "vertical",
    "order": "natural_filename"
  },

  "cover": {
    "kind": "page",
    "value": "001.jpg"
  },

  "provider": {
    "name": "ehentai",
    "remote_id": "...",
    "matched_at": "..."
  },

  "updated_at": "..."
}
```

不要机械要求每个真实文件都有独立 JSON。

尤其大型相册可能有几十万文件。相册的 EXIF、拍摄时间等已经在原文件中；Gallery 只保存额外的便携信息。实际规模变大后可按导入批次、年月或 shard 保存附加数据。

---

# 35. 安全与隐私

最低要求：

- 不上传本地媒体；
- 在线识别只发送匹配所需查询；
- Cookie / Token 不写 Library；
- Secret 不进日志；
- Secret 不进 Git；
- 日志避免无意义暴露完整私密路径。

高风险操作要有 INFO/WARN/ERROR 日志：

```text
scan
organize
move
delete
migration
provider
transaction recovery
```

不要让缩略图加载刷爆日志。

---

# 36. 元数据备份

不需要复制几十 GB 原媒体作为“备份”。

重点备份：

```text
.gallery/
```

尤其：

- Schema Migration；
- 大规模元数据变更；
- Organizer 前。

保留有限数量元数据快照即可。

---

# 37. Agent 不应做的事

后续 Agent 不要：

1. 一次性实现所有 Phase；
2. 一开始堆非常复杂的 Clean Architecture；
3. 为每个类建多层无意义 Interface；
4. 用移动介质上的一个巨型 SQLite 作为唯一真相；
5. 把 Android 绝对路径写进可移植 Schema；
6. 分类时自动移动真实文件；
7. 自动去重用户有意复制的内容；
8. 在线刷新时覆盖人工 Metadata；
9. 普通删除时真实删除文件；
10. 把回收站实现成“删除时立即搬文件”；
11. 自动转码；
12. 自动改 EXIF；
13. 把 E-Hentai 逻辑绑死在核心层；
14. 为未来 PC UI 提前写大量无用代码；
15. 没有测试就直接对真实 Library 写 Organizer；
16. 破坏现有 Git 历史；
17. 覆盖用户未提交改动；
18. 为了“使用开源”强行加入不需要的依赖。

---

# 38. Agent 每次继续开发时

建议固定流程：

```text
1. 阅读本文件
2. 阅读 README / AGENTS.md / 项目已有约束
3. git status
4. git branch --show-current
5. git log --oneline -n 10
6. 检查当前模块完成度
7. 只确定本次一个小目标
8. 实现
9. 实际构建
10. 运行相关测试
11. git diff
12. 修明显问题
13. 语义清晰地 Commit
14. 向用户汇报本次完成内容和下一阶段
```

用户已经明确：

> 本地仓库已存在。

所以不要假设这是空项目，也不要重新初始化 Git。

---

# 39. 可选参考项目/文档

这些只是参考，不是强制依赖；具体实现时先判断是否真的需要。

- Android Storage Access Framework  
  https://developer.android.com/guide/topics/providers/document-provider
- Android Storage Restrictions  
  https://developer.android.com/about/versions/11/privacy/storage
- AndroidX Room  
  https://developer.android.com/jetpack/androidx/releases/room
- AndroidX Media3 / ExoPlayer  
  https://developer.android.com/media/media3/exoplayer
- Android Background Work / WorkManager  
  https://developer.android.com/develop/background-work
- AndroidX ExifInterface  
  https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface
- Coil  
  https://github.com/coil-kt/coil
- ComicInfo.xml Schema / Documentation  
  https://github.com/anansi-project/comicinfo
- [Mihon Local Source（系列/章节/页面目录与 ZIP/CBZ）](https://mihon.app/docs/guides/local-source/)
- [Jellyfin Shows（季、集、附加内容和字幕命名）](https://jellyfin.org/docs/general/server/media/shows/)
- [Jellyfin Local NFO（可移植侧车元数据）](https://jellyfin.org/docs/general/server/metadata/nfo/)
- [Komga Libraries（实体根目录与扫描模型）](https://komga.org/docs/guides/libraries/)
- EhViewer（当前维护分支之一，可研究行为和局部实现）  
  https://github.com/FooIBar/EhViewer

直接复用开源代码前必须查看对应项目和具体文件许可证。

---

# 40. 已确认需求摘要

以下是用户已经明确确认的需求：

- Steam Library 式显式文件夹接入；
- 支持移动存储；
- 日常分类不修改真实目录；
- 可显式按当前分类整理底层文件；
- 分类、作者、Tag、Series 都只是索引，不复制文件；
- 漫画与图集统一为 ImageSet；
- ImageSet 主要支持纵向连续阅读；
- 支持普通图片单张管理；
- 相册图片和视频混排；
- 相册与普通作品分开；
- 漫画页可复制派生成普通图片；
- 多张图片可复制组成新 ImageSet；
- 便携元数据能被 Android、未来 PC、Agent 理解；
- PC 可直接向 Library 批量放入新内容；
- 新内容自动发现后进入 Inbox；
- 叶子图片目录可自动候选 ImageSet；
- 系统相册导入默认复制；
- 尽可能完整保留原文件信息；
- 保留相册来源关系；
- Live Photo / Motion Photo 逻辑上作为一个媒体项；
- 阅读/观看进度跟 Library；
- 删除只进入逻辑回收站，文件不移动；
- 回收站删除或到期才真删除；
- 自动清理时间可配置；
- 本机缩略图/预览缓存不写移动 Library；
- 不自动转码；
- 通用 Series；
- 重复只检测，不自动去重；
- 主动派生允许重复；
- Author 独立实体；
- Tag 支持 namespace 和 Alias；
- Collection 用户自定义；
- E-Hentai / ExHentai 可作为元数据识别来源；
- 高置信度唯一匹配自动写入；
- 人工信息优先；
- 不需要备注/标注系统；
- ZIP/CBZ 第一阶段支持；
- Schema 有版本；
- 支持多个 Library；
- 默认不修改媒体文件内部 Metadata；
- 自动封面 + 手动指定；
- ImageSet/Video 共用 Series；
- 支持全局搜索和组合筛选；
- Android 第一阶段 Kotlin + Jetpack Compose；
- PC 客户端以后单独实现；
- Collection 第一版不跨 Library；
- 可以合理引用成熟开源局部模块，但不强求；
- 必须基于现有仓库分模块、分步骤做好 Git 管理。

---

# 41. 最终设计目标

Gallery 最重要的原则可以概括为：

> **文件属于用户，Library 属于文件夹，而不是属于某一台 Android 设备。**

最终结构：

```text
真实文件：稳定、可理解、可直接访问
        +
.gallery：携带语义和状态
        +
设备本地索引：提供速度
        +
显式 Organizer：需要时把逻辑结构映射回物理结构
```

没有 App 时，用户仍能通过正常文件系统理解和访问数据。

有 Gallery 客户端时，可以获得完整的：

- 分类；
- 作者；
- 标签；
- Series；
- 阅读；
- 播放；
- 搜索；
- 进度；
- 安全整理。

这就是整个项目的核心。

---

> 本文档只在核心需求、Library Schema 原则或开发流程发生明显变化时更新。普通实现细节不需要反复重写整份文档。
