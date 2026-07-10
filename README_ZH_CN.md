<div align="center">
  <img src="docs/icon.png" alt="App 图标" width="100" />
  <h1>RikkaHub</h1>

一个原生Android LLM 聊天客户端，支持切换不同的供应商进行聊天 🤖💬

[English](README.md) | [繁體中文](README_ZH_TW.md) | 简体中文

点击链接加入群聊 👉 [【RikkaHub】](https://qm.qq.com/q/I8MSU0FkOu)

</div>

<div align="center">
  <img src="docs/img/chat.png" alt="Chat Interface" width="150" />
  <img src="docs/img/desktop.png" alt="Models Picker" width="450" />
</div>


## 🚀 下载

🔗 [前往官网下载](https://rikka-ai.com/download)（推荐）
🔗 [前往 Google Play 下载](https://play.google.com/store/apps/details?id=me.rerere.rikkahub)


## 📚 喵喵机错题本 & 蓝牙打印（本分支增强）

面向高三/备考场景的增强：直接对接**作业帮错题本**与**喵喵机 N2 蓝牙打印机**，让「拍错题 → 问 AI → 打印」一气呵成，无需在官方 App 之间来回截图复制。

### ✨ 功能
- **一键导入错题**：加号展开菜单新增「喵喵机错题」按钮。**短按**即可拉取作业帮错题本里**最新拍摄的错题**——拍照原图 + 作业帮自动搜题得到的清晰题目图，像导入照片一样直接进入输入框，立刻向 AI 提问。
  - **长按打开「错题浏览与导入」页**：按**科目分类**切换、翻页查看**历史错题**、**跨科目多选**、一键导入选中的多道错题，方便系统地查找与批量导入。
  - 图片始终完整保留；**文字按「每题一个 TXT」作为附件导入**（不再插入输入框），这样不会干扰你在对话框里正在输入的提问；「仅图片」模式下若该题无图会自动改用文字。
  - 可选是否附带官方解析（默认关闭，解析交给 AI）。
- **图片预览一键打印**：图片预览页在「下载」旁新增「打印」按钮。**短按**按纸宽填满打印；**长按打开打印预览窗口**，可**裁切**图片、拖动调节**图片在纸上的整体大小**（100% 填满做宣传 / 缩小省纸），所见即打印。
- **AI 回复一键打印**：每条 AI 回复的「更多」里新增「打印这条回复」，将含公式的回答**原生渲染为图片**后紧凑黑白打印。**短按**按默认字号直接打印，**长按**打开预览窗口实时调节字体、并显示当前纸张信息后再打印；字体大小也可在设置里带预览调节。**标题与公式都随字体一起缩放**，过宽的公式自动缩放到纸张宽度不裁切。
- **打印机体验**：蓝牙自动扫描、点击连接；**设备记忆**——重开 App 自动连接上次的打印机；**仅在前台且蓝牙开启时自动重连**（省电，正确识别蓝牙开关）；触发打印时若蓝牙未开会提示先打开蓝牙；**纸张尺寸可在设置里手动指定（2 寸 576px / 3 寸 864px）**（自动检测在部分机型不准，会把 3 寸认成 2 寸导致打印不满）；黑白（文字/题目）与灰度（照片）两种打印；浓度可调；打印后不走纸（N2 自带走纸机制，避免浪费）。
- **AI 打印工具**：在「助手 → 本地工具」开启「打印(喵喵机)」后，**AI 可直接调用打印**——输出 Markdown（含公式）自动渲染成图片并按默认字号打印，也可由 AI 指定字号。
- **打印后走纸可调**：默认不走纸；需要走一小段方便撕纸时在设置里调节行数（不再固定 30）。
- **墨水屏友好**：设置项选中态采用**深色高对比**填充，无彩色的墨水屏也能清晰分辨。

### ⚙️ 配置
配置统一在 设置 → 喵喵机 · 错题与打印（**长按**加号里的「喵喵机错题」现在打开的是错题浏览页，不再直接进设置）：
- **作业帮账号**：手机号 + 密码登录，可选默认科目与导入内容模式（仅图片 / 仅文字 / 图片+文字）。
- **喵喵机打印机**：扫描并点击连接、打印浓度、灰度开关、断线自动重连、自检页/走纸测试。

> 说明：账号与打印协议为设备本地直连（蓝牙 BLE + 作业帮云端 API），账号密码仅保存在本机。


## 💖 赞助商

|                                         赞助商                                         | 介绍                                                                                                                                              |
|:-----------------------------------------------------------------------------------:|:------------------------------------------------------------------------------------------------------------------------------------------------|
| <img src="docs/sponsors/aihubmix.png" alt="Aihubmix" width="50" /><br /><b>Aihubmix</b> | 感谢 <a href="https://aihubmix.com?aff=pG7r">aihubmix.com</a> 的资金支持。我们推荐使用 aihubmix 作为全球主流模型的一站式服务平台。（OpenAI、Claude、Google Gemini、DeepSeek、Qwen 以及数百种其他模型）。 |
| <img src="docs/sponsors/suixiang.jpg" alt="随想AI中转" width="50" /><br /><b>随想AI中转</b> | 感谢随想AI中转对本项目的赞助！随想AI中转 是一家可靠高效的 API 中继服务提供商，提供 Claude、Codex、Gemini 等的中继服务。注重隐私的中转站·无数据倒卖·无模型掺水，隐私，透明，极速售后。新账户注册每日签到就送 0.5 元测试额度，充值额度 1:1，无需订阅，按量付费。多线路冗余、跨区域容灾、自动故障切换，长链路 SSE 不中断。99.9% 可用性，关键调用从不掉队。 |

## ✨ 功能特色

- 🎨 现代化安卓APP设计（Material You / 预测性返回）和 🌙 暗色模式
- 📦 工作区：基于 proot 的 Linux 智能体环境
- 🖥️ Web多端访问支持
- 🛠️ MCP 支持
- 🔄 多种类型的供应商支持，自定义 API / URL / 模型（目前支持 OpenAI、Google、Anthropic）
- 🖼️ 多模态输入支持
- 📝 Markdown 渲染（支持代码高亮、数学公式、表格、Mermaid）
- 🔍 搜索功能（Exa、Tavily、Zhipu、LinkUp、Brave、Perplexity、..）
- 🧩 Prompt 变量（模型名称、时间等）
- 🤳 二维码导出和导入提供商
- 🤖 智能体自定义
- 🧠 类ChatGPT记忆功能
- 📝 AI翻译
- 🌐 自定义HTTP请求头和请求体

## ✨ 贡献

本项目使用[Android Studio](https://developer.android.com/studio)开发，欢迎提交PR

技术栈文档:

- [Kotlin](https://kotlinlang.org/) (开发语言)
- [Koin](https://insert-koin.io/) (依赖注入)
- [Jetpack Compose](https://developer.android.com/jetpack/compose) (UI 框架)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore?hl=zh-cn#preferences-datastore) (
  偏好数据存储)
- [Room](https://developer.android.com/training/data-storage/room) (数据库)
- [Coil](https://coil-kt.github.io/coil/) (图片加载)
- [Material You](https://m3.material.io/) (UI 设计)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3) (导航)
- [Okhttp](https://square.github.io/okhttp/) (HTTP 客户端)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) (Json序列化)

> [!TIP]
> 你需要在 `app` 文件夹下添加 `google-services.json` 文件才能构建应用。

> [!IMPORTANT]  
> 以下PR将被拒绝：
> 1. 添加新语言，因为添加新语言会增加后续本地化的工作量
> 2. 添加新功能，这个项目是有态度的
> 3. AI生成的大规模重构和更改

## 💰 捐赠

* [Patreon](https://patreon.com/rikkahub)
* [爱发电](https://afdian.com/a/reovo)

## ⭐ Star History

如果喜欢这个项目，请给个Star ⭐

[![Star History Chart](https://api.star-history.com/svg?repos=re-ovo/rikkahub&type=Date)](https://star-history.com/#re-ovo/rikkahub&Date)

## 📄 许可证

[License](LICENSE)
