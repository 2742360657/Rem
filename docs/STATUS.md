# Rem 当前状态

## 规范基线

2026-09-20 已根据用户确认重置产品规范。详细规则见 [AGENT_PRODUCT_RULES.md](../AGENT_PRODUCT_RULES.md)，Library 文件模式见 [AGENT_LIBRARY_RULES.md](../AGENT_LIBRARY_RULES.md)。

## 当前实现

应用已从零实现最小 Library 接入和浏览流程：

| 模块 | 位置 | 当前职责 |
| --- | --- | --- |
| 模型 | `model/Entry.kt`、`model/Index.kt` | 目录条目、媒体类型和索引序列化 |
| 存储 | `storage/LibraryTree.kt`、`storage/LibraryStore.kt` | SAF 目录读取、`.gallery/index.json` 缓存和 `RULES.md` 写入 |
| 媒体 | `media/MediaProbe.kt` | 读取图片 EXIF 和视频容器元数据 |
| 扫描 | `scan/Scanner.kt` | 读取 `相册/` 直属文件与 `画集/*/` 直属文件，报告不规范内容 |
| 界面 | `ui/` | 相册网格、画集项目列表、项目详情、右侧滑块和系统查看器/播放器调用 |

接入后先展示缓存，再进行后台增量扫描；扫描只读取 `size` 或修改时间变化的文件。应用不修改任何媒体文件。

## “从系统相册隐藏”实现与验证

设置中的开关只管理 Library 根目录 `.nomedia`：

- 开启时创建 `Library/.nomedia`；关闭时移除该文件；未接入 Library 时不可操作。
- `storage/LibraryTree.setSystemGalleryHidden` 是唯一写入点，返回写入后由 provider 读回的真实状态，界面不显示未经确认的状态。
- 开关决策抽成纯函数 `markerAction` 并配有单测：目标状态与现状一致时不动作，名为 `.nomedia` 的目录按冲突处理、绝不删除。
- ViewModel 串行化该操作，重复点击不会并发写入，因此不会产生 `.nomedia (1)`。
- 不修改、改名、移动、删除或覆盖任何原始媒体；不主动删除 `MediaStore` 已有记录。
- 开关状态由当前 Library 中 `.nomedia` 是否存在推导，不另存本机偏好。

小米官方说明路径任一层存在 `.nomedia` 时，该路径下图片不会被系统媒体服务记录：[KA-09388](https://www.mi.com/global/support/article/KA-09388/)。

### 真机验证（2026-09-20，本轮）

设备小米 `23127PN0CC`（`houji`），Android 16，澎湃 OS `3.0`（`V816`）。经 SAF 文件选择器接入探针 Library `/Download/RemLibraryProbe/`（含 `相册/` 与 `画集/测试作者-测试项目/`），全部操作通过界面点击完成：

| 验证项 | 结果 |
| --- | --- |
| 开→关、关→开连续 4 轮 | 每轮恰好 1 个标记文件，状态与界面一致 |
| 连续 5 次快速点击 | 始终只有 1 个 `.nomedia`，无 `.nomedia (1)` |
| 重启应用 | 开关恢复为开，状态来自 Library 而非本机偏好 |
| 应用外删除 `.nomedia` 后重启 | 开关显示为关，证明状态由磁盘推导 |
| 开启后向两条分支新增媒体并重新扫描 | 磁盘 8 个文件，`MediaStore` 记录 0 条 |
| 关闭后重新扫描 | 8 条记录全部恢复 |

补充结论与边界：

1. 应用创建的 `.nomedia` 是普通文件（本机 provider 写入 44 字节父目录路径）；标记的作用只取决于文件是否存在，空文件行为相同。
2. 本机重新扫描会清除 `.nomedia` 目录下已经建立的 `MediaStore` 记录，移除标记后这些记录会随扫描恢复。此效果强于此前记录，但**仍需系统重新扫描**；应用自身不触发扫描，所以刚切换开关时已有记录可能继续显示，界面提示按此表述。
3. 小米 Gallery 界面本身的即时隐藏/恢复表现未做独立 UI 验证，本轮只验证了底层 `MediaStore` 记录。

验证用探针目录、推送脚本与临时文件已全部从设备删除；`MediaStore` 图片记录数已回到验证前的 77 条基线。

## 当前不做

不实现底层文件改名、移动、删除、覆盖或其他编辑；不读取 `待分类/`；不做后台全盘扫描、自动识别整理、复杂关系、阅读进度或内置备用播放器。

## 验证证据

- 单元测试：`./gradlew :app:testDebugUnitTest`，7 个测试类 44 个用例，0 失败（本轮实际运行结果）。其中 `storage/MarkerActionTest.kt` 覆盖开关的幂等与冲突决策。
- 构建：`./gradlew :app:assembleDebug` 通过，产物安装到真机后完成上述界面验证。
- 真机 ADB：`baff50eb` 在线；设备信息见上文。
- 上表每一行均由本轮界面操作加 `adb` 查询得出，历史数字不作为本轮证据。

## 未验证

真实 Library 的 SAF 接入规模、EXIF/视频元数据完整覆盖、右侧滑块真机手势、系统查看器/播放器在所有媒体格式上的兼容性，以及小米 Gallery 界面中对已有媒体的即时隐藏/恢复表现，仍需独立场景验证。
