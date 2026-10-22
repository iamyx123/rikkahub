# RikkaHub 3.1.0-custom.20261022.2

版本号 197，保留原包名、签名、数据和黑白墨水屏状态显示，修复重复触发 Root 授权并加固登录恢复及 Android 权限处理。

更新内容：

- 学习桌面和阅读省电共用一个真实校验过的 Root 会话，停止学习桌面不会再关闭省电正在使用的会话。
- 启动应用、回到前台及查看设置只读取已授权会话，不再为了显示状态就启动 su；首次授权由“检查 Root 并开启”等明确操作发起。
- 合并并发 Root 授权，用户首次授权可等待最多 60 秒，短时状态检查不会取消正在等待的授权。拒绝后后台探测会退避，显式操作可以重试。
- Root 命令超时后不自动重放，修复无换行输出和阻塞流清理；保留对不同 su 启动方式的兼容。
- 应用内的省电说明确认持久保存，关闭功能、重新打开应用后不再重复显示；它不代替系统实际 Root 校验。
- MCP 网络故障不再误显示为需要重新授权。服务器拒绝旧令牌时先串行刷新，保留轮换后的令牌；工具操作不会因为自动刷新而被重复执行。
- Class Record 验证账号成功后即可恢复已登录状态，功能目录暂时加载失败可直接刷新。旧请求不能清掉新账号会话；设备密钥暂不可用时保留登录密文并允许重试。
- 修复安卓权限结果与请求错配、连续点击重复请求、Activity 重建丢失请求身份，以及临时授权撤销后误判永久拒绝。
- 适配 Android 14 及以上的部分照片访问：区分全部、所选和无权限；允许直接导入已授权照片，按需在原生照片设置中重新选择。

Updates:

- Reuse a verified process-wide Root session and serialize authorization attempts
- Preserve acknowledged reading-power explanations across app restarts
- Refresh rejected MCP tokens without replaying tool operations
- Recover Class Record sessions independently of catalog availability
- Preserve runtime permission request identity and support Android 14 selected-photo access

应用继续使用原生 Compose / Material 3 控件，保留纯净模式、沉浸模式、学习桌面、拍照截图、打印、交互界面、导入及现有服务连接。原有授权由系统及超级用户管理器校验；授权被撤销或进程被系统终止后会重新验证，不把本地布尔值当成系统授权。

编译配置为 minSdk 26、targetSdk 37、compileSdk 37.2。Android 14 的部分照片权限按 API 34 分支处理，旧系统保留原权限路径。兼容性以实际构建和回归记录为准；没有连接的 Android 设备，不使用模拟器，本次不宣称真机 Android 14 或其他系统版本已经运行验收。

本版 APK、映射、校验记录独立存放，保留版本 196 的发布文件和验收记录。
