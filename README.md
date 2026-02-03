<div align="center">
  <a href="https://github.com/Xiaoc7r/DOVideo-AI">
  </a>

  <h1 align="center">DoVideoAI - 智能视频内容理解平台</h1>
  
  <p align="center">
    <strong>全链路异步化 / 长任务稳定性保障 / AI 智能问答 </strong>
  </p>

  <p align="center">
    <a href="https://github.com/Xiaoc7r/DOVideo-AI">
      <img src="https://img.shields.io/badge/Spring%20Boot-3.0-brightgreen" alt="Spring Boot">
    </a>
    <a href="https://github.com/Xiaoc7r/DOVideo-AI">
      <img src="https://img.shields.io/badge/RocketMQ-4.9-orange" alt="RocketMQ">
    </a>
    <a href="https://github.com/Xiaoc7r/DOVideo-AI">
      <img src="https://img.shields.io/badge/Redisson-Lock-red" alt="Redisson">
    </a>
    <a href="https://github.com/Xiaoc7r/DOVideo-AI">
      <img src="https://img.shields.io/badge/LangChain4j-AI-blueviolet" alt="LangChain4j">
    </a>
    <a href="https://github.com/Xiaoc7r/DOVideo-AI">
      <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
    </a>
  </p>
</div>

<br/>

<br/>

**DoVideoAI** 是一个集成用户鉴权、视频上传、音频提取及 AI 自动总结的全链路视频内容理解平台。

针对视频处理场景中常见的 **“长耗时阻塞”** 、 **“高并发资源冲突”** 以及 **“大文件传输不稳定”** 等痛点，本项目抛弃了传统的同步处理模式，基于 **RocketMQ + Redisson + 分片续传** 重构了系统架构。

系统可以接入大模型api，自定义提示词，基于 Function Calling 可以实现查询信息和精准总结。

视频平台大多只解决了“存储”和“播放”的问题。DoVideoAI 旨在解决“理解”的问题。 它通过异步架构处理长耗时任务，利用 AI 提取核心价值，让视频不再是黑盒。

<br/>

## 项目预览

![项目简览](https://github.com/user-attachments/assets/e2f27517-c43d-4032-a8a1-ee6de5121629)

<img width="2879" height="1719" alt="注册登录" src="https://github.com/user-attachments/assets/85e6ebbc-a0da-488b-bdfe-9b5be7616e53" />

<img width="2874" height="1416" alt="展示区" src="https://github.com/user-attachments/assets/b887e8fb-4e26-477d-b893-1f2b0d9774cc" />

<img width="2873" height="1666" alt="工作区" src="https://github.com/user-attachments/assets/b393966f-4b7b-4b1b-b305-dd933f86ed64" />

<img width="2874" height="1702" alt="文字提取" src="https://github.com/user-attachments/assets/5685a5ea-2404-4087-89a8-36b89d822810" />

<img width="2874" height="1714" alt="AI调用分析" src="https://github.com/user-attachments/assets/9115f18e-2465-4e28-bc22-731c1cc59d33" />

![L4J](https://github.com/user-attachments/assets/af329c20-c689-4d3b-9d23-9fe51a0ef81e)


<br/>

##  核心功能

1. 🚀 稳定上传体验

分片断点续传：针对 GB 级大文件（如 4K 课程录像），采用 Redis 维护上传分片状态。实测在 20% 丢包率弱网环境下，上传成功率从 25% 提升至 99%。

秒级响应：引入 RocketMQ 将耗时的“视频分析”动作剥离出主线程。用户上传完成后仅需 50ms 即可得到反馈，后续处理全异步化，彻底告别页面转圈卡死。

2. 🛡️ 高并发防护

分布式锁兜底：使用 Redisson + WatchDog 机制。当多个用户同时上传同一个热门公开课视频时，系统通过 MD5 内容指纹识别，利用分布式锁防止重复转码与 AI 分析，节省算力与 Token 开销。

削峰填谷：Controller 层集成 Redis 令牌桶算法，有效遏制恶意请求与突发流量，保护后端服务不被击穿。

3.  🔄 任务处理流程详解

稳健入口：文件直传 MinIO，避免应用服务器带宽瓶颈。

异步解耦：上传成功后，Controller 仅发送一条消息至 RocketMQ 即刻返回，将长耗时任务留给后台。

安全消费：消费者通过 Redisson 锁住视频 MD5，确保同一视频在同一时刻只有一个线程在处理。

智能重试：针对第三方 AI API 可能的网络抖动，设计了指数退避重试机制，确保任务最终一致性。

<br/>

## 技术栈

### 后端

SpringBoot + RocketMQ + Redis + MySQL + MyBatis Plus + MinIO + FFmpeg + LangChain4j

### 部署

Docker 

### 前端

Vue 3 + Vite 

<br/>


```bash

├── common               
├── config               
│   ├── MinioConfig
│   ├── RedisConfig
│   ├── RedissonConfig
│   ├── RocketMQConfig
│   └── WebConfig  
├── controller           
│   ├── AuthController
│   ├── VideoController  
│   └── TestController  
├── entity               
├── mapper               
├── service              
│   ├── impl
│   │   └── VideoServiceImpl
│   ├── VideoService
│   └── RocketMQConsumer
└── utils                
    ├── FFmpegUtils     
    ├── FileUtils
    ├── JwtUtils
    ├── RedisUtils    
    └── AiUtils        
src/main/resources
├── mapper               
├── application.yml
└── lock.lua             
```






<br/>

## 我的开发环境 

| 组件 | 版本 | 备注 |
| :--- | :--- | :--- |
| **JDK** | 21.0.8 | 支持 Spring Boot 3 即可 |
| **Node** | v22.18.0 | 前端构建依赖 |
| **MySQL** | 8.0 | Docker 镜像 `mysql:8.0` |
| **Redis** | Latest (7.x) | Docker 镜像 `redis:latest` |
| **RocketMQ** | 4.9.4 | Docker 镜像 `apache/rocketmq:4.9.4` |
| **LangChain4j** | DeepSeek | 硅基流动送14元免费额度 |
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

在启动后端前，还原以下配置：
#### 1. 配置数据库密码
确保与 docker-compose 中的 MySQL 密码一致：
```properties
spring.datasource.password=root
```

#### 2. 配置AI模型密钥
请填入你自己的 API Key(该项目默认使用了硅基流动的api)：
```properties
# 不知道api是什么？可以前往 [https://cloud.siliconflow.cn/] 申请密钥，主要也有免费额度
ai.deepseek.api-key=sk-你的密钥xxxxxxxxxxxxxxxx
```

#### 3. 请确保本地已安装 FFmpeg 和 yt-dlp，并填入路径：
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
