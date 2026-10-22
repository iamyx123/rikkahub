# RikkaHub 3.1.0-custom.20261022.1

版本号 196，保留旧定制版包名、签名和数据，修复黑白墨水屏的状态辨识。

更新内容：

- 实心、空心飞机显示真实飞行状态
- 常驻文字区分自动省电和系统飞行
- 勾选、单选圆点和描边标明选择
- 修复交互按键的选中与禁用显示

Updates:

- Show actual flight mode with filled and outlined icons
- Separate automatic power saving from system flight mode
- Mark selections with checks, radio buttons and borders
- Clarify selected and disabled interactive controls

保留原入口和纯净模式、沉浸模式设置。右上角实心飞机表示系统已开启飞行模式，空心飞机表示系统未开启；无法读取时显示问号和“飞行未知”，不推断为关闭。“自动开/关”单独表示阅读省电功能的配置，等待、恢复和错误另有文字提示。短按仍切换自动省电，长按仍设置秒数。

通过系统设置变更监听和回到前台时读取真实飞行状态。即使自动省电关闭，手动切换系统飞行模式仍会反映在按钮上；读取指示状态不需要 root，不会改变系统开关。自动切换仍按原有 root 授权、恢复记录和网络/打印保护执行。顶部没有逐秒刷新。

Class Record 班级选择、分类、资料工具和表单选项，交互开关、选择器及按钮都补充非颜色标记，继续使用应用原生 Material 3 控件。上一版 195 的 APK、映射和验收记录保留，本版独立发布。

验收报告位于本版发布目录。无连接的 Android 设备，不使用模拟器；本地回归、资产检查和构建校验不代表物理墨水屏或真实系统设置事件已验收。
