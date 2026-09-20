# Rem

Rem 是一个本地图片、漫画和视频查看工具。产品和 Agent 规则以 [AGENT_PRODUCT_RULES.md](AGENT_PRODUCT_RULES.md) 为准。

名称与图标保持不变，当前版本 `0.0.4`。

## Library 结构

```text
Library/
  相册/
  画集/
  待分类/
  .gallery/
    RULES.md
    index.json
```

- `相册/` 直接存放图片和视频，按媒体拍摄时间排序，没有拍摄时间时用文件修改时间。
- `画集/作者名称-项目名称/` 直接存放从 `0001` 开始的四位序号媒体文件。
- `待分类/` 当前不读取、不索引、不展示；归位由人完成。
- `.gallery/RULES.md` 是给整理方看的文件模式说明，每次接入都被覆写为当前版本。
- `.gallery/index.json` 是可重建的扫描缓存。

## 两种工作模式

- **安卓端**：显式接入目录，先显示缓存再后台增量扫描，只读取和展示。
- **整理端（人或 AI）**：依据 `AGENT_LIBRARY_RULES.md` / `.gallery/RULES.md` 调整底层文件的实际存放位置和命名。

## 构建与验证

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

`AGENT_LIBRARY_RULES.md` 由 Gradle 的 `syncLibraryRules` 任务复制为 `app/src/main/assets/RULES.md`，不手工维护第二份副本。

## 当前文档

- [AGENT_PRODUCT_RULES.md](AGENT_PRODUCT_RULES.md)：完整产品和文件模式
- [AGENT_LIBRARY_RULES.md](AGENT_LIBRARY_RULES.md)：写入 Library 的文件模式说明
- [AGENTS.md](AGENTS.md)：Agent 开发协议
- [docs/PRODUCT.md](docs/PRODUCT.md)：产品目标摘要
- [docs/STATUS.md](docs/STATUS.md)：实现状态和当前边界
- [docs/USER_GUIDE.md](docs/USER_GUIDE.md)：当前用户操作

当前不执行底层文件编辑，不自动全盘扫描，也不实现旧的复杂关系、标签、自动整理或阅读进度功能。

## 内置查看器

方向已确认、**尚未实现**：点击媒体后改为在 Rem 内查看或播放，不再跳转到其他应用。

规则见 [AGENT_PRODUCT_RULES.md](AGENT_PRODUCT_RULES.md) 第 5.1 节，实测依据见 [docs/STATUS.md](docs/STATUS.md)。系统查看器/播放器的调用路径继续保留，作为显式入口和失败回退。
