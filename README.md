<div align="center">
  <h1>DoVideo-AI</h1>
  <p>
    <img src="https://img.shields.io/badge/Spring%20Boot-3.0-green" alt="Spring Boot">
    <img src="https://img.shields.io/badge/Vue-3.0-42b883" alt="Vue">
    <img src="https://img.shields.io/badge/RocketMQ-4.9-blue" alt="RocketMQ">
    <img src="https://img.shields.io/badge/Redisson-Distributed-red" alt="Redisson">
    <img src="https://img.shields.io/badge/DeepSeek-AI-purple" alt="DeepSeek">
    <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
  </p>
</div>

<br/>

本项目为高并发分布式AI视频解析平台。用户可注册登录，通过上传 **本地视频** 或 **在线链接** ，一键解析出该视频的 **音频，文字，AI总结概括** 。

本项目涉及分布式锁防并发、消息队列异步化、多线程并行编排。针对视频处理场景中“长耗时阻塞”、“高并发资源冲突”以及“非结构化数据分析难”等需求需求设计了充足的解决方案。

系统总体采用 **全链路异步化架构**，用户上传视频（或链接）后，通过 **RocketMQ** 进行流量削峰，利用 **Redisson** 分布式锁保障资源一致性，由 **JUC** 线程池编排AI模型(本项目按例使用**TeleSpeech ASR**)，高效实现对音视频内容的结构化总结。

<br/>

## 项目预览

![项目简览](https://github.com/user-attachments/assets/e2f27517-c43d-4032-a8a1-ee6de5121629)

<img width="2879" height="1719" alt="注册登录" src="https://github.com/user-attachments/assets/85e6ebbc-a0da-488b-bdfe-9b5be7616e53" />

<img width="2874" height="1416" alt="展示区" src="https://github.com/user-attachments/assets/b887e8fb-4e26-477d-b893-1f2b0d9774cc" />

<img width="2873" height="1666" alt="工作区" src="https://github.com/user-attachments/assets/b393966f-4b7b-4b1b-b305-dd933f86ed64" />

<img width="2874" height="1702" alt="文字提取" src="https://github.com/user-attachments/assets/5685a5ea-2404-4087-89a8-36b89d822810" />

<img width="2874" height="1714" alt="AI调用分析" src="https://github.com/user-attachments/assets/9115f18e-2465-4e28-bc22-731c1cc59d33" />


<br/>

##  核心功能

-  **高并发流量削峰**：引入 RocketMQ 构建生产者-消费者模型，将耗时任务（下载/转码）异步化，解决 Web 线程阻塞。
-  **分布式并发控制**：基于 Redis 实现细粒度分布式锁，防止热点视频重复提交，节省 70% 计算资源。
-  **异构任务编排**：使用 JUC CompletableFuture 并行处理视频下载、元数据解析与音频分离，显著降低端到端耗时。
-  **智能状态同步机制**：即时响应 + 延迟轮询 + 时间戳防缓存，精准感知后端任务状态，解决长耗时任务导致的 HTTP 超时与页面假死问题。
-  **私有化对象存储**：部署 MinIO 对象存储服务，实现音视频文件与业务服务的物理隔离，保障数据隐私与系统的无状态化扩展能力。
-  **令牌桶限流**：设置每分钟提交次数上限，有效遏制恶意请求导致的AI Token费用爆炸
-  **全平台视频兼容**：基于 yt-dlp 实现对 Bilibili、YouTube 等全网主流视频流的解析与高码率下载。
-  **高可用重试机制**：针对第三方 AI API 的不稳定性，设计了基于指数退避的自动重试策略。

<br/>

## 技术栈

### 后端

SpringBoot + RocketMQ + Redis + MySQL + MyBatis Plus + MinIO + FFmpeg

### 部署

Docker 

### 前端

Vue 3 + Vite 



<br/>

## 我的开发环境 


| 组件 | 版本 | 备注 |
| :--- | :--- | :--- |
| **JDK** | 21.0.8 | 支持 Spring Boot 3 即可 |
| **Node** | v22.18.0 | 前端构建依赖 |
| **MySQL** | 8.0 | Docker 镜像 `mysql:8.0` |
| **Redis** | Latest (7.x) | Docker 镜像 `redis:latest` |
| **RocketMQ** | 4.9.4 | Docker 镜像 `apache/rocketmq:4.9.4` |
| **FFmpeg** | Latest | 推荐 2025 年后的 Snapshot 版本 |
| **yt-dlp** | Latest | 建议定期 `update` 保持解析库最新 |

<br/>

## 如何本地部署

### 中间件部署 (Docker Compose)
本项目依赖多个中间件，已封装为 Docker Compose 文件。请确保本地已安装 Docker Desktop。

```bash
# 在项目的根目录下，直接一键启动所有服务
# (包含 MySQL, Redis, MinIO, RocketMQ, Dashboard)
docker-compose up -d
```
<img width="920" height="288" alt="一键部署" src="https://github.com/user-attachments/assets/592ce99a-18c8-4bec-96cc-f6d709f4aad1" />


### 后端配置修改

在启动后端前，请修改 `server/src/main/resources/application.properties` 文件，还原以下配置：
#### 1. 配置数据库密码
确保与 docker-compose 中的 MySQL 密码一致（默认是 root）：
```properties
spring.datasource.password=root
```

#### 2. 配置AI模型密钥
请填入你自己的 API Key(该项目默认使用了硅基流动的api)：
```properties
# 不知道api是什么？可以前往 [https://cloud.siliconflow.cn/] 申请密钥，主要也有免费额度
ai.deepseek.api-key=sk-你的密钥xxxxxxxxxxxxxxxx
```

#### 3. 请确保本地已安装 FFmpeg 和 yt-dlp，并填入绝对路径：
```properties
# Windows 环境示例 (注意使用斜杠 /)
tool.ffmpeg.dir=D:/ffmpeg/bin
tool.ytdlp.path=D:/yt-dlp/yt-dlp.exe

# Mac/Linux 环境示例
# tool.ffmpeg.dir=/usr/local/bin
# tool.ytdlp.path=/usr/local/bin/yt-dlp
```

### 启动项目

🟢 启动后端

```properties

cd server

# 启动服务
mvn clean spring-boot:run
# 当看到控制台输出 Started DOVideoApplication in x.xxx seconds 即表示后端启动成功。
```

🔵 启动前端

```properties

cd client
# 1. 安装依赖
npm install

# 2. 启动开发模式
npm run dev
```

<img width="2873" height="1770" alt="前后端启动" src="https://github.com/user-attachments/assets/12ddc037-b60b-4f9e-9d78-280864cf95b4" />

访问前端界面内显示地址（默认为接口http://localhost:5173
可成功访问该项目！



<br/>

## 贡献与支持
如果这个项目对你有帮助，请给个 Star ⭐️⭐️⭐️⭐️⭐️！
