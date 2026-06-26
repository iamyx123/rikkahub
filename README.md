<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>RikkaHub</h1>

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/rikkahub/rikkahub)
[![Ask DeepWiki](https://img.shields.io/badge/zread.ai-blue?style=flat&logo=readthedocs)](https://zread.ai/rikkahub/rikkahub)

A native Android LLM chat client that supports switching between different providers for
conversations 🤖💬

Click to join our Discord server 👉 [【RikkaHub】](https://discord.gg/9weBqxe5c4)

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English
</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>

## 📚 墨水屏专用版（适合高中学校使用）

> 本分支是基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的**墨水屏（E-Ink）定制版**，针对墨水屏阅读器 / 平板做了适配与增强，
> 方便在高中等学习场景中作为随身的离线 / 局域网 AI 助手使用：护眼、低耗电、可把电脑屏幕内容快速发给 AI。

以下是本分支相对上游的**全部改动**。

### 🖋️ 墨水屏适配

- **「拍照」改为「电脑截屏」**（墨水屏通常无摄像头）：
  - **短按**输入栏拍照按钮：连接电脑端截图服务，自动截取电脑上**所有显示器**（每块屏幕一张图），一次性加入当前对话。
  - **长按**拍照按钮：配置电脑端服务的 **IP 地址与端口**（默认 `5000`），内置「测试连接」，配置自动持久化。
  - 电脑端需运行开源的 [Screenshotter](https://github.com/timheuer/screenshotter)（Windows，含截图 HTTP 服务），手机与电脑同一局域网即可。例如上课时把电脑上的题目 / 课件一键截图发给 AI 讲解。
- **禁用输入光标闪烁**（设置 → 偏好设置 → 界面 → 墨水屏优化）：开启后输入框光标变为透明、停止闪烁，避免墨水屏因光标每 0.5 秒刷新而持续重绘，降低耗电、消除闪烁；界面动画继续跟随系统设置。
- **沉浸模式开关**：隐藏状态栏 / 导航栏，扩大墨水屏可视区域；并修复了弹出对话框时沉浸模式失效的问题。

### 💬 聊天与阅读优化

- **发送消息时是否跳转到最新消息的开关**：长文阅读时发送消息不再被强制拉到底部。
- **音量键翻页**：修复冷启动恢复会话后音量键翻页失效的问题（墨水屏常用物理键翻页）。
- **阅读书签与回答导航（常驻、无动画）**：右侧常驻一个半透明小按钮，点一下即展开导航面板（不带动画，避免墨水屏闪烁），可：
  - **上一条 / 下一条 / 顶部 / 底部** 快速跳转，并显示**回答进度**（当前第几条 / 共几条）。
  - **新增书签 + 书签地图**：书签锚定到具体回答节点，**位置绝对**——生成新回答、编辑/重新生成被标记的回答、切换助手或对话都不会让书签漂移；节点被删除时书签自动失效清理。
  - **短按书签跳转、长按书签删除**；书签随导航按钮一起折叠 / 展开。
- **引用功能**：长按某条 AI 回答 → 更多 → **引用**，在弹出的文本里选中需要的片段（不选则引用整条），即可作为 Markdown 引用块插入输入框，方便就这一点继续追问。
- **输入框普通透明度调节**：除毛玻璃外，新增输入框（及回答建议）的**普通不透明度**滑块（设置 → 偏好设置 → 通用，关闭毛玻璃时生效）。

### 📎 附件增强

- **一键导入相册「最新一组照片」**：「+」菜单新增「导入照片」——以相册最新一张为基准，自动把与它**间隔不超过 N 分钟**的照片归为一组一并加入输入框（**长按**该按钮可调节分钟阈值，默认 2 分钟）。需相册读取权限。
- **第三方文件选择器**：**长按**「上传文件」改用 `ACTION_GET_CONTENT`，可调起 MT 管理器 / MiXplorer 等第三方文件管理器并多选（短按仍走系统 SAF）。

### 🖨️ 导出增强

- **修复超长图片导出被截断**：上万字的长回答导出长图时按真实高度完整渲染，不再被高度上限裁断（并在内存吃紧时自动降配重试）。
- **导出进度提示**：长图导出期间显示进度指示并禁用按钮，避免无反馈。
- **Markdown 导出可不含图片（记忆该选择）**：普通 Markdown 解析器不支持内嵌 base64 图片，可一键排除，设置会被记住。

### 🔍 搜索

- **搜索结果支持按日期排序**，并**记住所选排序方式**。
- **高亮改为加粗**：聊天内搜索与全局搜索的命中词由「背景色高亮」改为「**加粗**」，墨水屏上更清晰、不再遮挡文字。
- **全局搜索精确定位**：点击搜索结果会**精确滚动到匹配的那条消息**，而不是只跳到对话开头。

### 🤖 AI / 工具调用修复

- 修复**工具（MCP / 本地工具）返回的图片无法被模型读取**的问题。
- Claude 工具图片改为 `tool_result` 之后的普通 image block。
- 补充 **Response API 图片传递**，并实现 **MCP audience 注解**处理。

### 🌟 关于页彩蛋

- 关于页标题改为 **RikkaHub × iamyx33**（共创）：点击黑色头像掉落「爱心 + 7/5/2/0」表情彩蛋，点击作者名查看开发者手记。

### 🛠️ 构建说明

本版本以 `releaseDebug` 构建类型打包：**release 级别优化**（精简体积、无「开发模式」、无冗余日志），但**保留 `.debug` 包名与 debug 签名**，可直接覆盖安装在现有自定义版之上，非可调试包。

```bash
# Windows（无 zsh）下需跳过 web-ui 构建
./gradlew :app:assembleReleaseDebug -x buildWebUi
# 产物：app/build/outputs/apk/releaseDebug/ 下的 *arm64-v8a*.apk
```


---

## 🚀 Download

🔗 [Download from Website](https://rikka-ai.com/download) (Recommended)

🔗 [Download from Google Play](https://play.google.com/store/apps/details?id=me.rerere.rikkahub)

## 💖 Sponsors

|                                         Sponsor                                         | Description                                                                                                                                                                                                                                         |
|:---------------------------------------------------------------------------------------:|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <img src="docs/sponsors/aihubmix.png" alt="Aihubmix" width="50" /><br /><b>Aihubmix</b> | Thanks to <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> for their financial support. We recommend using aihubmix as a one-stop shop for mainstream models worldwide. (OpenAI, Claude, Google Gemini, DeepSeek, Qwen, and hundreds more). |
| <img src="docs/sponsors/suixiang.jpg" alt="随想AI网关" width="50" /><br /><b>随想AI网关</b> | 感谢随想AI网关对本项目的赞助！随想AI网关 是一家可靠高效的 API 中继服务提供商，提供 Claude、Codex、Gemini 等的中继服务。注重隐私的中转站·无数据倒卖·无模型掺水，隐私，透明，极速售后。新账户注册每日签到就送 0.5 元测试额度，充值额度 1:1，无需订阅，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。99.9% 可用性，关键调用从不掉队。 |

## ✨ Features

- 🎨 Material You Design and 🌙 Dark mode
- 📦 Workspace: a proot-based Linux agent environment
- 🔄 Multiple AI Provider Support: custom API / URL / models (all OpenAI, Google, Anthropic compatible api)
- 🖼️ Multimodal input support (Image, Text Documentation, PDF, Docx)
- 🖥️ Web access for multi-platform use
- 🛠️ MCP support
- 📝 Markdown Rendering (with code highlighting, Latex formulas, tables, Mermaid)
- 🪾 Message Branching
- 🔍 Search capabilities (Exa, Tavily, Zhipu, LinkUp, Brave, Perplexity, etc.)
- 🧩 Prompt variables (model name, time, etc.)
- 🤳 QR code export and import for providers
- 🤖 Agent customization
- 🧠 ChatGPT-like memory feature
- 📝 AI Translation
- 🌐 Custom HTTP request headers and request bodies
- 💌 Silly Tavern character card import

## ✨ Contributing

This project is developed using [Android Studio](https://developer.android.com/studio). PRs are
welcome!

Technology stack documentation:

- [Kotlin](https://kotlinlang.org/) (Development language)
- [Koin](https://insert-koin.io/) (Dependency Injection)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI framework)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) (Preference data
  storage)
- [Room](https://developer.android.com/training/data-storage/room) (Database)
- [Coil](https://coil-kt.github.io/coil/) (Image loading)
- [Material You](https://m3.material.io/) (UI design)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (Navigation)
- [Okhttp](https://square.github.io/okhttp/) (HTTP client)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (JSON serialization)

> [!TIP]
> You need a `google-services.json` file at `app` folder to build the app.

> [!IMPORTANT]  
> The following PRs will be rejected:
> 1. Translation related changes, such as adding new languages or updating existing translations
> 2. Adding new features, this project is opinionated and will not accept pull requests for new features
> 3. Large-scale refactoring and changes generated by AI

## 💰 Donate

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## ⭐ Star History

If you like this project, please give it a star ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=re-ovo/rikkahub&type=Date)](https://star-history.com/#re-ovo/rikkahub&Date)

## 📄 License

[License](LICENSE)
