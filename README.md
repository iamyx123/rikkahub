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
- **阅读书签与回答导航（常驻按钮 + 定格手势，专为墨水屏）**：右侧（或左侧）常驻一个半透明小按钮，位置固定、收起即在原位：
  - **轻点**：展开 / 收起完整书签面板（无动画）。
  - **按住上下拖动**：定格式快速翻页——上一条 / 上两条 / 回到顶部、下一条 / 下两条 / 回到底部；卡到档位才刷新指示，指示图标凸出在按钮一侧避免被手指遮挡，松手即跳转；拖动期间屏蔽列表滚动。
  - **长按 ≥3 秒**：进入移动模式，可沿左/右侧上下调整按钮位置（范围受限以保证上下档位可达）。
  - **书签**：可在同一条长回答里的**多个位置**分别加书签，每个书签标签带「第几条 + 阅读百分比 + 片段」反映真实定位；短按跳转、长按删除。书签锚定回答节点，**位置绝对**——新回答、编辑/重生成、切换助手或对话都不会让书签漂移。
- **引用功能**：在 AI 回答中**直接选中文本即可引用**到输入框（作为 Markdown 引用块），方便就这一点继续追问。
- **输入框透明度（随键盘智能切换）**：除毛玻璃外新增输入框（及回答建议）的**普通不透明度**滑块；并在**键盘弹起时自动变为不透明**保证可读、键盘收起时恢复半透明透出背景。
- **去除菜单遮罩/阴影**：去掉左侧抽屉、各类底部弹窗的暗色遮罩（scrim），避免墨水屏因半屏变暗而整屏重刷。

### 📎 附件增强

- **一键导入相册「最新一组照片」**：「+」菜单新增「导入照片」——以相册最新一张为基准，自动把与它**间隔不超过 N 分钟**的照片归为一组一并加入输入框（**长按**该按钮可调节分钟阈值，默认 2 分钟）。需相册读取权限。
- **第三方文件选择器**：**长按**「上传文件」用 `ACTION_GET_CONTENT + createChooser` 强制弹出应用选择框，可调起 MT 管理器 / MiXplorer 等第三方文件管理器并多选（Android 13/14 上也有效；短按仍走系统 SAF）。

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

- 关于页将应用图标与作者纯黑头像**并排展示**，标题 **RikkaHub × iamyx33**（共创）：点击黑色头像掉落「爱心 + 757520」表情彩蛋，点击作者名查看开发者手记。

### 📚 喵喵机错题本 & 蓝牙打印（作业帮 + 喵喵机 N2）

面向高三/备考：直接对接**作业帮错题本**与**喵喵机 N2 蓝牙打印机**，实现「拍错题 → 问 AI → 打印」一条龙，无需在官方 App 之间来回截图复制。

- **一键导入错题**：「+」菜单新增「喵喵机错题」。**短按**拉取作业帮**最新拍摄的错题**（拍照原图 + 自动搜题得到的清晰题目图），像导入照片一样进入输入框直接提问；纯文字（LaTeX 公式）题以带公式文本导入，图片始终完整保留。**长按**进入设置。
- **图片预览打印**：图片预览页「下载」旁新增「打印」。**短按**打整图；**长按先裁切**，只打印裁切出的小范围，**省纸省耗材**。
- **AI 回复打印**：每条 AI 回复「更多」→「打印这条回复」，含公式的回答**原生渲染为图片**后紧凑黑白打印。**短按**按默认字号直接打印，**长按**打开预览窗口实时调字体、显示当前纸张信息后再打印；字体大小亦可在设置里带预览调节。**公式随字体一起缩放**，过宽公式自动缩放到纸宽不裁切。
- **打印机体验**：蓝牙自动扫描、点击连接；**设备记忆**（重开 App 自动连接上次的打印机）；**仅前台 + 蓝牙开启时自动重连**（省电，正确识别蓝牙开关，未开时打印会提示先开蓝牙）；自动检测纸张宽度（2 寸 576px / 3 寸 864px）；黑白（文字/题目）与灰度（照片）两种打印；浓度可调；打印后不额外走纸（N2 自带走纸机制，避免浪费）。
- **墨水屏友好**：设置项选中态用**深色高对比**填充，无彩色墨水屏也能清晰分辨。
- **配置入口**：长按「喵喵机错题」或 设置 → 喵喵机 · 错题与打印：作业帮账号登录、默认科目、导入模式；打印机扫描/连接、浓度、灰度、打印字体等。账号密码仅保存在本机。

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
| <img src="docs/sponsors/suixiang.jpg" alt="随想AI中转" width="50" /><br /><b>随想AI中转</b> | 感谢随想AI中转对本项目的赞助！随想AI中转 是一家可靠高效的 API 中继服务提供商，提供 Claude、Codex、Gemini 等的中继服务。注重隐私的中转站·无数据倒卖·无模型掺水，隐私，透明，极速售后。新账户注册每日签到就送 0.5 元测试额度，充值额度 1:1，无需订阅，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。99.9% 可用性，关键调用从不掉队。 |

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
