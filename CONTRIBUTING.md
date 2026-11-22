# 开发与维护

保持现有功能、数据归属和失败恢复行为。优化前先追踪调用方及共享状态，使用可复现的输入验证结果与开销。新增抽象应解决实际重复职责；不要为单个实现增加接口、工厂或转发层。

## 目录与依赖方向

- `controller` 负责 HTTP 参数、认证归属和响应，调用应用服务，不直接访问 Mapper 或 checkpoint 仓储。
- `service` 负责编排和业务策略，`repository` 管理 checkpoint 的持久化/热缓存，`mapper` 负责数据库读写。
- `dto`、`entity` 不依赖应用编排或 HTTP 控制器；基础设施不反向依赖控制器。构造器注入依赖。
- `utils/MediaProcessRunner` 统一管理外部媒体进程的准入、超时、线程参数和回收，调用方负责具体命令、文件和业务恢复。
- Vue 工作区管理分析状态，`components/MediaCard.vue` 管理独立卡片展示；通过 props 与事件交互。扩展保留自身的浏览器运行边界。

`ArchitectureTest` 在测试中约束前述 Java 依赖方向。不要为了通过规则将业务代码藏进通用 helper。

## 格式与验证

使用 JDK 21、Node.js 22。Java 格式采用固定版本 Google Java Format；前端及扩展共用根目录 Prettier 配置，UTF-8、空格缩进、LF 换行，Windows `.cmd` 保留 CRLF。

在 `server` 中执行：

```bash
./mvnw spotless:apply
./mvnw verify
```

在 `client` 中执行；格式命令同时覆盖 `extension`：

```bash
npm ci
npm run format
npm run format:check
npm test
npm run build
```

在 `extension` 中执行 `node --test`。CI 会运行后端完整验证、网页和扩展测试、格式与部署配置检查。首次下载格式器或打包插件会增加构建时间，不影响应用运行开销。

## 并发和资源

任务身份由媒体记录、目标、模式组成，内容指纹只决定可复用的计算范围。检查缓存后获取共享锁，再次检查持久化结果；失败或中断必须释放自己持有的锁。单实例信号量只约束本实例进程数量，不能替代 Redis 的跨实例协调。

原生工具排队和执行都有截止时间；ASR/OCR 循环响应中断。保留线程池的有界队列、拒绝策略和关闭等待；核心线程空闲后回收。调高工作线程前先测量 CPU、内存、磁盘和外部服务配额。JSON 压缩不包含 `text/event-stream`，避免缓存 SSE 进度。

## 文件与提交

提交源代码、测试、构建锁文件、迁移、启动配置和必要文档。`node_modules`、`target`、`dist`、日志、真实 `.env`、凭据和本地服务数据均不提交。测试和 Maven Wrapper 是项目组成部分，不能作为“无用文件”删除。删除资源前搜索 README、代码、测试、配置及动态引用，并说明依据；Git 保留历史以便恢复。

将功能/性能修改与批量格式化分为不同提交。PR 写清触发条件、最终行为、验证证据和未验证边界；不要把局部微基准描述为真实模型全链路的速度提升。
