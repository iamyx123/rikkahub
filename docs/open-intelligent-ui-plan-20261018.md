# Open Intelligent UI 接入评估

评估日期：2026-10-18。RikkaHub 基线：上游 `07b1238a`（3.0.0 标签及之后 3 个提交）。Open Intelligent UI 源码基线：`03213439358bf3408ad61a46ec9e3230c5572e48`。本文是实施规划，3.0.0 定制 APK 尚未加入这个功能。

## 结论

可以添加。建议保留 RikkaHub 的原生聊天、模型配置和会话存储，在消息中增加“交互卡片”渲染能力。首选 `@openuidev/react-lang` 的 Renderer 与裁剪后的组件库：现有 Web 前端直接接入，Android 把同一套静态渲染资源打进 APK，用专用 WebView 展示。手机不需要运行 Next.js/Node 服务，也不必使用 Thesys 云或新增模型账号。

适合先做学习用途：可调参数的物理/数学演示、计算器、选择题、翻卡复习、可勾选计划、表格和静态图表。地图、联网图片搜索、YouTube、任意 HTML/JS 和游戏放在后续阶段；这些功能会增加联网、资源占用和墨水屏适配工作。

接入可行性来自源码和架构核对，尚未进行依赖安装、Android WebView 实测、真实模型生成或体积/性能测量。

## 已核对的依据

| 项目事实 | 对本项目的影响 |
| --- | --- |
| 示例项目为 Next.js 16、React 19，要求 Node 24；MIT 许可 | 可复用组件和样式，但不能直接作为 Compose 控件添加。复制源码时保留版权和许可说明，并核查实际引入的依赖许可。 |
| RikkaHub 的 `web-ui` 已使用 React 19、Vite、Zod 4 | 与 react-lang 文档中的 React >=19、Zod 3.25/4 要求匹配，仍需锁定依赖并实测构建。 |
| Renderer 接受 `response`、`isStreaming`、`initialState`，提供 `onStateUpdate`、`onAction` 和 `onError` | 可以接入现有消息流，不需要替换整个聊天界面或使用示例的 AgentInterface。 |
| OpenUI Lang 使用组件、变量和函数表达交互界面 | 普通聊天模型需收到对应组件说明和输出协议；安装前端包不会自动让所有回答变成交互界面。 |
| 示例支持直接调用 OpenAI 兼容 Chat Completions，也支持 Ollama | 可复用 RikkaHub 当前服务商。直接调用路径没有 OpenUI Cloud 的输出校正，需验证模型遵循格式的能力并保留文本回退。 |
| 示例完整提示词约 8,000 tokens（README 自述） | 不宜默认加到所有聊天；首期裁剪组件，按助手/会话启用，并实测实际 token 开销。 |
| 示例使用 localStorage，最多保留 50 个会话，存储失败可静默忽略 | 不采用这套会话存储；沿用 RikkaHub 的 ConversationSession、Room 和备份链路。 |
| 地图、照片依赖 `/api/street-route`、`/api/wiki`、`/api/image-search` | 复制前端组件不能让这些功能直接可用；需要单独的服务适配、缓存和失败显示。 |
| 示例 HtmlView 使用允许脚本的 iframe，并允许指定 CDN 和 HTTPS 图片 | 其“沙箱”不等于完全离线或无外部请求；首期不开放 HtmlView。 |

## 推荐设计

### 消息与模型

新增默认关闭的“交互回答”设置，遵守 3.0.0 已有的会话配置快照规则。启用后，在现有请求工厂中追加由固定组件库生成的提示词；模型、思考深度、附件、工具调用和取消逻辑继续走现有 AI 层。不要复制示例 `/api/chat`，它只保留 user/assistant 文本，会丢弃附件和工具信息。

第一版使用有明确语言标记的 `openui` 代码块，旁边保留可读的文字说明。只有标记明确的块进入 Renderer，普通 Markdown 不作猜测。组件库版本、语言版本和提示词一起锁定。解析失败、未知组件、超限输出、生成被取消时显示说明与源码，不让整条回复空白，也不自动无限重试模型请求。

Web 端可渐进渲染。墨水屏默认等代码块闭合后再展示，点击修改变量时局部刷新；生成中仅在卡片入口显示进度。不要每个 token 重建 WebView。

### Android 与 Web 共用渲染层

新建独立的静态 renderer 构建入口，输出本地 HTML/JS/CSS，并打进 Android assets；Web 端复用组件库和解析协议。使用独立的资源入口和受控消息通道。现有 WebView 工具可以参考，但不能直接沿用它面向一般网页的权限设置。

Android 消息内先显示摘要及“打开交互卡片”，点击进入单个可复用的全屏/大面板 WebView。确认低端实机的滚动、输入法、返回键和内存表现后，再考虑内嵌卡片；避免长对话为每条消息常驻一个 WebView。

桥接只接受带版本、会话 ID、消息 ID、块 ID、请求序号的类型化数据。模型输出与用户输入通过 JSON 编码传递，禁止拼接可执行脚本。关闭文件和 content URI 访问；只让可信本地页面访问有限桥接接口。首期不绑定 Query/Mutation/MCP toolProvider，不提供任意 Android、Root、文件、打印或网络调用。

`@Set` 等纯本地交互在渲染器内完成。`@ToAssistant` 转成可见的聊天输入或一次明确的发送操作，并复用当前会话的生成状态和重复提交保护；加载历史消息不能自动发送。打开链接仅接受经检查的 http(s) 地址，剪贴板动作只在用户点击时执行。

### 状态、备份和旧版本

保存原始 OpenUI 源码、渲染器协议版本和用户变量状态。状态按 `conversationId/messageId/blockId` 关联，并用源码摘要防止编辑或重新生成后误套旧状态；分支消息保持独立。状态写入通过 ChatService/ConversationSession 的最新内存状态完成，不保存旧会话快照。

落库前确定可向后兼容的存储方案：首选独立的、可忽略的状态记录与版本字段，或验证过旧读取器容错能力的可选字段。不要直接新增旧客户端无法反序列化的密封 MessagePart。必须覆盖清空、删除、重命名、切换分支、重新生成、备份恢复和跨端访问的状态一致性。

普通导出保留文字说明与 OpenUI 源码。打印/图片导出应把当前卡片转成静态结果或可读摘要，不能只打印“打开卡片”按钮；是否具备可靠截图能力是发布前验收项。

### 墨水屏与界面

沿用应用字体大小、明暗主题与 Material 3 Expressive 的外部入口。墨水屏模式取消转场、闪烁光标、图表补间、自动轮播、翻卡动画和粒子效果；表格/图表提供文字标签和线型区分，不能只靠颜色。控件需要适合触摸的大点击区，错误和进度留在当前卡片内。

## 实施顺序与验收

1. **离线渲染验证**：锁定 react-lang/lang-core 1.x 的确切版本；实现布局、文字、表格、按钮、输入、选择、滑块和基础计算函数。用固定样例覆盖计算器、选择题、可勾选计划和参数图表；验证不联网可用、解析错误可回退、中文和窄屏显示。此步无需模型密钥。
2. **Android 容器与状态**：打包静态资源，加入专用 WebView 和严格桥接，接入本地状态与备份。实机检查墨水屏静态显示、输入法、旋转/重建、后台恢复、连续打开关闭和内存占用；恶意 URL、重复事件、超大输入和历史重放不能越过边界。
3. **接入现有模型**：按会话启用，在现有生成链路中追加裁剪提示词，支持流式/取消/重试与普通文字混排。用用户当前实际服务商和模型验证 OpenUI 语法、函数、中文、思考分离以及附件/工具多轮对话；记录成功率、首屏耗时和输入输出 token。不能仅凭模型支持 Chat Completions 宣称兼容。
4. **发布实验功能**：完成跨端状态、分支、备份恢复及打印/导出回归，保留一键回到文字模式。先作为可关闭的实验选项发布，再决定是否增加内嵌展示、静态 SVG、地图和联网图片。

前两步验证通过才值得做模型接入。若目标设备 WebView 过旧、卡顿或打印失真，先只发布 Web 端功能，并保留 Android 文字回退。完全改写为 Compose 也可行，但要长期维护 OpenUI 解析器、表达式运行时和组件映射，当前不建议作为首选。

## 对应代码入口

- Android 代码块：`app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt` 的 `MarkdownComponents.codeBlock`。
- Web 代码块：`web-ui/app/components/markdown/markdown.tsx` 与 `code-block.tsx`。
- 现有本地 Web 资源：`ui/src/main/java/me/rerere/ui/webview/WebViewLocalAssets.kt`；专用交互容器需另外限定请求范围和桥接权限。
- 消息与状态：`ai/src/main/java/me/rerere/ai/ui/Message.kt`、`app/.../data/model/ConversationConfig.kt`、`app/.../service/ConversationSession.kt` 和 `ChatService.kt`。
- 请求构造：`app/src/main/java/me/rerere/rikkahub/data/ai/ChatRequestFactory.kt`。

## 上游资料

- [示例 README](https://github.com/thesysdev/open-intelligent-ui/blob/03213439358bf3408ad61a46ec9e3230c5572e48/README.md)
- [版本与依赖](https://github.com/thesysdev/open-intelligent-ui/blob/03213439358bf3408ad61a46ec9e3230c5572e48/package.json)
- [组件库](https://github.com/thesysdev/open-intelligent-ui/blob/03213439358bf3408ad61a46ec9e3230c5572e48/src/lib/library.ts)
- [模型调用与协议转换](https://github.com/thesysdev/open-intelligent-ui/blob/03213439358bf3408ad61a46ec9e3230c5572e48/src/app/api/chat/route.ts)
- [示例状态存储](https://github.com/thesysdev/open-intelligent-ui/blob/03213439358bf3408ad61a46ec9e3230c5572e48/src/lib/chat-storage.tsx)
- [HtmlView 边界](https://github.com/thesysdev/open-intelligent-ui/blob/03213439358bf3408ad61a46ec9e3230c5572e48/src/lib/components/html-view.tsx)
- [MIT 许可](https://github.com/thesysdev/open-intelligent-ui/blob/03213439358bf3408ad61a46ec9e3230c5572e48/LICENSE)
- [React Renderer 文档](https://github.com/thesysdev/openui/blob/main/packages/react-lang/README.md)（当前默认分支，实施时须按锁定版本复核）。
