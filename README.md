# Rem

Rem 是一个本地图片、漫画和视频查看工具。当前项目正在按最小目标重构，产品和 Agent 规则以 [AGENT_PRODUCT_RULES.md](AGENT_PRODUCT_RULES.md) 为准。

## 当前 Library 结构

```text
Library/
  相册/
  画集/
  待分类/
  .gallery/
```

- `相册/` 直接存放图片和视频，按媒体拍摄时间排序。
- `画集/作者名称-项目名称/` 直接存放从 `0001` 开始的四位序号媒体文件。
- `待分类/` 当前不读取、不索引、不展示。
- `.gallery/` 为可重建的内部状态。

## 当前文档

- [AGENT_PRODUCT_RULES.md](AGENT_PRODUCT_RULES.md)：完整产品和文件模式
- [AGENTS.md](AGENTS.md)：Agent 开发协议
- [docs/PRODUCT.md](docs/PRODUCT.md)：产品目标摘要
- [docs/STATUS.md](docs/STATUS.md)：实现状态和当前边界
- [docs/USER_GUIDE.md](docs/USER_GUIDE.md)：当前用户操作

当前不执行底层文件编辑，不自动全盘扫描，不内置备用播放器，也不实现旧的复杂关系、标签、自动整理或阅读进度功能。
