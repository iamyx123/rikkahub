# Open Intelligent UI 原生适配（2026-10-18 开始，10-19 完成实现）

此文记录最终实现，替代早期 WebView 方案。Android 使用 Compose 原生控件和项目已有 QuickJS；不新增 WebView。网页端使用同一解析引擎，在按需启动的 Web Worker 内计算，并渲染普通 React/DOM 控件。

## 会话开关与请求缓存

聊天输入栏增加“交互 UI”，默认关闭，属于 ConversationConfig，不属于助手全局设置。新会话的选择随首条消息落库；已存在会话立即保存。生成期间禁用切换。关闭后已有卡片仍展示并可操作。

发送新用户消息时，读取此前实际发送的模式，只有变化才生成不可变的切换提醒，保存到该 UIMessage 的 interactiveUiDirective；interactiveUiMode 记录该轮模式。没有新消息时反复切换不会增加提醒。构建请求时，在该用户轮的最前面加入保存的提醒，不修改首条 system prompt，不随软件升级重写旧提醒。Google/Claude 的现有适配会过滤中途 SYSTEM 角色，所以使用用户轮中的应用提醒文本，保证各服务商都能接收且位置不变。上下文裁剪或压缩检查点丢失先前提醒时，在保留的首个用户轮补入最近的原始提醒；裁剪本身仍会改变请求前缀。

这些改动保持正常连续请求的已有前缀；缓存是否命中、计费和保留时长仍由服务商决定。首次开启附加约 24,123 字符的组件协议，关闭的会话不注入该协议。

## 组件与操作

固定 @openuidev/lang-core 1.0.1、Zod 4.4.3，适配 open-intelligent-ui 提交 03213439358bf3408ad61a46ec9e3230c5572e48 的函数库。构建入口 intelligent-ui/build.mjs，npm ci 后执行 npm run build；提交生成的 assets，正常 Gradle 构建无需重新安装这套依赖。

支持 57 个组件、59 个本地函数：覆盖上游 55 组件中除 YouTube、HtmlView 外的 53 个，加上 NumberInput、Switch、Progress、Accordion。完整清单与例子见 intelligent-ui/upstream-components.json 和 intelligent-ui/examples/all-components.oui。沿用本项目原生参数协议，不承诺所有上游位置参数原样互换。

包含布局、分页轮播、弹出详情、链接、时间线、图标按钮、可点击区域、文字/Markdown/LaTeX、表单、日期、倒计时、问答翻卡、表格、六类图表及 SVG 图形。Icon 按需读取 Lucide 0.564.0 的 1,671 个独立小文件，不一次性装载整个库。散点图采用数值横轴；Android 图表同时提供数值。Celebration 使用静态完成提示。

Map 默认只画地点，点击后加载 OpenStreetMap 街道瓦片，支持缩放、平移、类别筛选、展开及 OSRM 驾车道路概览；不声称支持步行或公共交通导航。SearchImage 由用户点击查询 Wikipedia，失败尝试其他语言和 Commons，保留来源与授权链接。网络请求使用项目现有代理，最多 3 并发，每次 15 秒超时，缓存最多 32 项/4 MiB。每视野 9 张瓦片，每张最多 512 KiB；道路最多 20 点、约 2,000 个几何点。

Image 点击后加载，Android 限制解码尺寸为 1024×1024，使用静态位图解码；网页墨水屏提供外部图片入口。Timer 普通屏每秒更新当前控件，墨水屏静态显示“计时中”，只在到期唤醒；暂停、重置、完成才写绑定状态。它是卡片内倒计时，不是系统后台闹钟。

@Set/@Reset 在本地更新状态；@ToAssistant 把文字和变量放入输入框，由用户检查并发送；@OpenUrl/@CopyToClipboard 只有点击后执行。没有 YouTube、音视频、HtmlView、iframe、任意脚本、自动工具查询或自动调用 MCP。HtmlView 运行 HTML/JavaScript 游戏需要网页运行环境，本次原生版本未实现，不应描述为上游 55 组件无差别全量移植。原有聊天工具调用仍由原来的聊天链路处理。

## 开销与边界

Android 进程最多保留一个串行 QuickJS 运行时，初始化超时 2 秒，单次求值 600 毫秒，堆上限 32 MiB，栈上限 512 KiB，闲置 15 秒释放。代码块及回复完成后才解析；输入防抖 150 毫秒，操作排队。模型文本经过 JSON 编码传入固定解析器，不作为 JavaScript 执行，没有注入网络、文件或 Android 宿主接口。

源码上限 64 KiB 字符、单块变量状态 32 KiB 字符、32 个变量、300 个组件、24 层嵌套、输出 256 KiB 字符；每条消息最多保存 16 个不同源码块的状态，总计 128 KiB 字符。网页 Worker 有 2 秒运行超时和 15 秒闲置释放，但没有浏览器内存硬限额。引擎文件约 432 KiB，文件大小不是运行内存。

本机 QuickJS/JVM 初次测量：初始化 69 ms，22 次小卡片渲染/更新合计 13 ms；引擎报告 memoryUsedSize 5,743,951 bytes、mallocSize 9,378,071 bytes。最终复测见发布 verification/测试 XML。此数字不包含 Compose、图片缓存和整机应用内存，不能代替墨水屏真机测量。

## 保存、导出与墨水屏

状态存于 UIMessage.interactiveUiStates，键为去除末尾空白后的源码 SHA-256，所属会话和消息 ID 由 ChatService 验证。只接受助手消息内的完整 OpenUI fenced block，已删除或更改的消息不会被重建。使用 ConversationSession 最新状态更新与 flush，不写回旧会话快照；网页使用同一 API 和服务端状态，不把 localStorage 当权威存储。原始消息正文不会因交互变量变化而改写，因此不破坏已有模型请求前缀。

墨水屏沿用项目 disableCursorBlink 设置：思考区显示“正在思考/思考完成”，停止原来的 50 ms 读秒循环，取消标题滚动闪光，思考预览采用直接滚动；全局 shimmer 在此模式下停用。卡片禁用 ripple，数值滑杆改为加减按钮，开关/选择使用静态控件，矢量图和图表无动画。普通屏保留原有思考计时行为。

Markdown 导出保留正文与源码；Android 图片/打印导出先用保存变量预计算卡片，再绘制静态原生图表、SVG、文字和表单值；轮播各页、翻卡两面、折叠内容展开。地图导出地点清单，网络图片保留说明和 URL，不为导出自动联网。预计算结果总上限约 8 MiB，解析失败或超限时保留源码与变量。打印固定浅色主题，避免深色内容在纸面不可见。备份和恢复通过新增可选 JSON 字段保留模式、提醒和状态；不引入数据库列迁移。不要用旧 APK 直接降级覆盖新版数据库。

## 验证

node --test intelligent-ui/test.mjs 覆盖输入、重置、动作、函数、图形 schema、错误和资源边界。JVM 测试覆盖请求前缀、上下文裁剪、序列化、分支编辑、无效状态和真实 QuickJS 超时。网页类型检查及本地浏览器组件验收验证费用计算、翻卡、图表与重置；该页面使用测试保存接口，不等同手机 HTTP 服务实测。

最终还运行全部模块 JVM 测试、Android 测试源码编译、Lint 和优化 APK 构建。未用模拟器，未安装设备，未验证真实模型遵循格式、墨水屏物理刷新和低端设备内存峰值。未知组件/解析失败显示错误和源码，回复不丢失。

MIT 版权和许可完整保留在 app/assets 与 web/public 的 intelligent-ui/NOTICE.txt，包括上游函数库、OpenUI Lang Core、Zod。
