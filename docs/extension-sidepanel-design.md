# Chrome 扩展侧边栏改版设计

> 2026-09-10 · 状态：已确认，实施中

## 背景

扩展侧边栏目前把分析结果直接塞进 `<pre>` 用 `textContent` 展示，用户看到的是一坨原始
Markdown（`##`、`-`、`**` 原样露出）。扩展自己的 README 也把「Markdown 渲染」列为未支持项。
除此之外，登录、输入、进度三个视图用的是浅色样式，与网页端的暗色科技风不一致。

## 目标

1. 结果不再是原始文本，而是排版过的结构化内容
2. 四个视图统一为网页端的暗色科技风
3. 关键提示（如「部分结论未通过 Critic 校验」）不再被淹没在正文里

非目标：不改动后端、不改动网页端、不引入构建步骤。

## 关键决策

### 决策一：自写结构化渲染，不引入 marked

网页端用 marked + 白名单 sanitizer 渲染（`client/src/markdown.js`）。扩展不照搬，理由是：

- **安全模型不同**。扩展带 `tabs` / `storage` 权限，脚本注入的后果比网页严重得多。
  网页端是「先 `innerHTML` 解析成 DOM，再逐节点白名单过滤」——安全性依赖过滤逻辑不出漏洞。
  扩展改为自写渲染器，全程用 `createElement` + `textContent` 构建 DOM，**从不接触
  `innerHTML`**，XSS 在构造上就不可能发生。
- **零依赖**。扩展当前没有任何依赖和构建步骤，引入 marked 要么 vendor 一个 50KB 文件，
  要么加打包器。
- **领域化渲染**。需要把「核心结论 / 视频证据 / 建议」等章节渲染成卡片、把 `[00:30]`
  渲染成可点击的时间戳胶囊——通用 Markdown 渲染器反而绕远路。

代价：不支持表格与嵌套列表。后端当前产物用不到，若将来需要再补。

### 决策二：纯逻辑与 DOM 分离

沿用扩展既有模式（`lib/` 放纯逻辑 + `tests/` 用 `node:test`）：

- `lib/markdown.js`：纯函数，输入输出都是普通数据，可单测
- `lib/markdownView.js`：把数据渲染成 DOM，依赖 `document`，不单测

## 模块设计

### lib/markdown.js

```js
stripThink(text)        // 剔除 LLM 的 <think> 推理残留（对齐网页端行为）
linkifyTimestamps(text) // [00:30] → [00:30](#video-t=30)，沿用网页端的约定
parseInline(text)       // → InlineToken[]，处理粗体/斜体/行内代码/链接
parseBlocks(markdown)   // → Block[]，文档级结构
groupSections(blocks)   // → { intro, sections }，按二级/三级标题切卡片
```

块类型：

| type | 字段 |
| :--- | :--- |
| `heading` | `level`, `tokens` |
| `paragraph` | `tokens` |
| `list` | `ordered`, `items`（每项是 `InlineToken[]`） |
| `quote` | `tokens` |
| `code` | `text` |
| `hr` | — |

行内类型：`text` / `strong` / `em` / `code` / `link` / `timestamp`。
其中 `timestamp` 由 `[00:30](#video-t=30)` 解析而来，带 `seconds` 字段。

### lib/markdownView.js

```js
renderResult(blocks, { onSeek }) // → DocumentFragment
```

`groupSections` 切出的每个 section 渲染成一张卡片：左侧强调色竖线 + 标题 + 内容。
intro 里以 `>` 开头的引用块渲染成警告 callout（用于承载「结果提示」）。

### 时间戳跳转

结果里点 `[00:30]` 时：

1. 取当前活动标签页，用 `parseVideoUrl` 校验它确实是本次分析的视频（比对规范化后的 URL）
2. 一致 → `chrome.tabs.update(tabId, { url: 带 t 参数的目标 } )`
3. 不一致 → 只提示「当前标签页不是本次分析的视频」，不跳转

评分表：B 站用 `?t=秒`，YouTube 用 `?t=秒s`，两者都接受 `t` 参数。

`b23.tv` 的短链标识不能在本地反推出 BV 号，因此短链只支持提交分析；时间戳跳转要求
当前输入或标签页是包含 BV 号的 B 站完整视频地址。

## 测试

`tests/markdown.test.js` 覆盖 `lib/markdown.js` 的全部导出：

- `stripThink`：成对标签、只有闭合标签、无标签三种情况
- `linkifyTimestamps`：`[00:30]`、`[1:02:03]`、已是链接的 `[00:30](...)` 不应二次处理
- `parseInline`：粗体/斜体/行内代码/链接/时间戳混排
- `parseBlocks`：标题层级、有序/无序列表、引用、围栏代码、水平线、段落
- `groupSections`：卡片切分、标题前的内容进 intro

DOM 渲染层不单测（需要浏览器环境），由手动验证覆盖。

## 验收

- `node --test` 全绿
- 加载扩展后，对同一视频重跑分析，结果区呈现为卡片化排版而非原始文本
- 点时间戳能跳到视频对应位置；在非目标视频页点时给出提示而非误跳
