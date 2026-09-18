# 可用版本持续开发

- 需求 / 目标：完整满足 PRODUCT，优先真实使用中的 R07 阅读/观看、R11 接入与性能、R08 管理可靠性。避免复杂低频功能，整体目标仍 active。
- 基线：a53ed65，加本轮 SyntheticVideo.kt / VideoDecodeTest.kt 设备验收。单 Agent；当前拥有上述测试与 STATUS/DEV_LOG/handoff。
- 便携影响：本轮无。生成视频只在测试 App 缓存，无真实 Library 访问、无 Schema 或原媒体变更。
- 最近已完成：8eeca30 离线预览为前台让路；13ef21f 漫画精确位置与末页跳转；c233049 章节与选择器定位；a6aa418 管理列表定位和 Inbox 分区/选择恢复；45f4c53 扫描/补全让路与可取消哈希；0d7c3c6 视频生命周期；a53ed65 音频焦点/noisy 配置。
- 最新验证：Windows / Java 17，299 单测、lint、debug/test APK、API 36 全部 60 项设备测试通过。新增 MediaCodec 合成 160×120 AVC/MP4（30 帧）在实际 MainActivity 同款播放器/Surface 上验证首帧、seek、全屏横屏、播放结束。不是完整 Library/剧集/便携进度或真机长视频验收。
- 既有证据：漫画实际组件精确偏移重建、同页定位、末页不误完成、真实 swipe 自动下一话；150 项网格/章节返回锚点；Inbox 三分区各自定位/重建及忽略媒体选择；关系编辑定位不变草稿；冷归档与哈希抢占取消；SAF 查询等待；实际 Activity 前后台暂停/手动暂停；合成 WAV 实播音频焦点竞争。
- 不要重复：GalleryApp 已按 Library 和 screen 保存状态，Inbox 已按分区保存；VideoViewer 已有系列队列/全屏/生命周期/焦点；Edition 页计划已在 IO；不要根据旧日志重复实现。
- 继续优先项：补齐分组书架、系统相册、分类/设置等长界面的快速定位；完整真实视频系列 UI 与便携进度读回；R03/R04/R05 场景审查、R06 随机发现来源回溯、R10 新实体/关系和多文档事务。
- R11 剩余：Coil 直接读取未参与调度，Provider 同步调用必须返回后才能响应取消；真实接入故障和大库冷/热性能未关闭。scanner.enrich 只有读取阶段可抢占重试，提交阶段不得放入重试块。
- 用户真机已移除，旧真机安装包 d9f2324；不能把模拟器结果写成真机验收。旧日志补全 1261 中失败 1259、Provider 最慢 51 秒，仍待来源/介质实测。S: 曾可由 Get-PSDrive 看到，Get-Volume 无结果不能证明未挂载；勿为文档访问源库。
- 工具：ADB C:/Users/Susnowy/AppData/Local/Android/Sdk/platform-tools/adb.exe；Rem_API_36 / emulator-5556。启动参数 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -feature -Vulkan。Gradle DEBUG 置空，ANDROID_SERIAL=emulator-5556。
- 环境证据：先前 GPU qemu_pipe 在测试 Activity 退出时卡住；改软件后端/禁用 Vulkan，滑动测试退出前让 Compose 时钟完成动画后正常。adbd 已恢复非 root。测试 App 伪造耳机 noisy 受保护广播被拒绝，未绕过；耳机实际拔插、蓝牙和电话场景未验收。
- 验收底线：产品要求不可缩减；不能以本轮测试通过宣称最终成熟。按模块中文提交，暂存审查与提交后状态核对；不推送、不改写历史。
- 更新时间：2026-09-19。

- 最新系统相册模块（基线 4c5d83a）：Screens.kt 增加类型/来源组合筛选、每筛选独立网格位置和定位，加载时保留选择并禁用导入；SystemGalleryPositionTest 两项 150 数据测试。299 单测、lint、APK 与 API 36 全部 63 项设备测试通过。系统相册定位不再待办。SaveableStateProvider 外层需要 key 隔离活跃组合状态，不能删除。继续其他长界面、漫画布局/真实手势与视频系列完整流程；总体目标未完成。

- 后续分组书架模块：f902e4d 视频验收已提交。GroupShelf 现用内容索引映射定位（不数标题行），持有状态跨空快照；299 单测、lint、APK 和八项共享定位设备测试通过。分组书架定位不再是待办；仍需系统相册/其他分类设置长界面、完整视频系列 UI、R06 等工作。
