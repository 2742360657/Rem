# 可用版本持续开发

- 需求 / 目标：按 PRODUCT 持续完成可用版本；当前优先 R07 漫画体验、R11 前台读取、所有长界面快速定位。避免复杂低频功能。
- 基线与归属：本次接续自 70a1fea；前台读取模块已提交 8eeca30。当前漫画位置修复由主 Agent 独占 MediaDetail.kt、ReaderPositionTest.kt 和权威文档；默认单 Agent。
- 便携影响：本次无 Schema、便携字段或原媒体变更；进度仍经原 ViewModel/Repository 写入。真实 Library 未访问。
- 已完成：批量字段和关系操作、视频完成判定/系列队列/横屏全屏、混合目录视频导航、共享网格和漫画页码定位、页列表 IO 与取消重试、缓存锁拆分、离线预览为前台页读取让路。具体证据以 STATUS 和 DEV_LOG 为准。
- 不要重复：GalleryApp 已有 SaveableStateHolder；视频队列已实现。虚拟 Edition 页解析已移 IO。不能照旧交接再次添加这些功能。
- 最新阅读修复：initialized 精确偏移保留、同页跳转归零、短末页实际布局定位、Coil Empty 占位，避免零高度把末页跳转拉到前面；完成只由活动滚动或明确确认驱动。两个实际阅读组件设备测试及完整工程检查、48 项模拟器设备测试已通过。
- 最新验证：Windows 298 单测、lint、debug/test APK 和 API 36 全部 48 项设备测试通过，包含实际 swipe、状态重建、跳末页不自动完成。设备测试退出曾卡在模拟器 GPU qemu_pipe 帧提交，三次主动终止不计为通过；已改 swiftshader_indirect、禁用 Vulkan，并让测试时钟等待滑动动画结束。
- 工具：ADB C:/Users/Susnowy/AppData/Local/Android/Sdk/platform-tools/adb.exe；emulator-5556 / Rem_API_36 / API 36。当前启动参数 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect -feature -Vulkan。adbd 已恢复非 root。Gradle 前设置 DEBUG 为空，设备测试指定 ANDROID_SERIAL=emulator-5556。
- 用户真机已主动移除，旧真机安装包为 d9f2324，不能说最新修复已真机验收。旧日志补全 1261 项失败 1259，Provider 单次最慢 51 秒；S: 曾可由 Get-PSDrive 发现，不因 Get-Volume 无结果断言不存在。不要为整理文档访问原库。
- 下一步：扩展 Series 章节、作品/关系选择器及其他长列表的快速定位并验证返回锚点；进一步覆盖解码前尺寸与缩放/翻页协调。调度尚未覆盖扫描/补全和 Coil 直接读取，Provider 同步调用不能立即取消。视频实解码与真实多集、R03/R04/R05 场景、R06 随机发现、R10 新实体关系工具和 R11 真介质验收均仍未完成。
- 验收边界：合成 PNG 实际阅读组件测试不等于 SAF 进度写回、真机手势或全量真实介质验证；当前不可称最终成熟版本。
- 更新时间：2026-09-19。

- 后续模块：13ef21f 漫画位置修复已提交。章节列表、添加成员和选择分组/系列现已接入 ListPositionButton；LongListPositionTest 三项 API 36 测试、298 单测、lint 和两个 APK 构建通过。此模块未重跑之前全部 48 项设备测试，不能累计成一次 51 项通过。下一步扩展其余长界面，勿再次实现这三个入口。
