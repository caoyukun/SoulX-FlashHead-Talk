# SoulX-FlashHead Java 版本

基于 Spring Boot + Vue 的实时交互数字人聊天应用，移植自原始 Python/Gradio 版本。

## 项目概述

本项目将原有的 Python/Gradio 实时聊天数字人应用移植到 Java 技术栈，使用以下技术实现：

- **后端**: Spring Boot 3.x (Java 17+)
- **前端**: Vue 3 + Element Plus
- **AI 集成**: 通过 Flask 封装的 Python 服务调用 FlashHead 模型
- **实时通信**: WebSocket + SockJS/STOMP
- **流式视频**: 分段视频生成 + WebSocket 推送

## 项目结构

```
SoulX-FlashHead-Java/
├── backend/                          # Spring Boot 后端
│   ├── src/main/java/com/soulx/flashhead/
│   │   ├── controller/              # REST 控制器
│   │   │   ├── HealthController.java
│   │   │   ├── ChatController.java
│   │   │   └── VideoController.java
│   │   ├── service/                 # 业务逻辑
│   │   │   └── ChatService.java
│   │   ├── model/                   # 数据模型
│   │   │   ├── ChatMessage.java
│   │   │   ├── ChatRequest.java
│   │   │   ├── VolcengineRequest.java
│   │   │   └── VolcengineResponse.java
│   │   ├── config/                  # 配置类
│   │   │   ├── FlashHeadProperties.java
│   │   │   └── WebSocketConfig.java
│   │   └── client/                  # Python 服务客户端
│   │       ├── VolcengineClient.java
│   │       └── PythonServiceClient.java
│   ├── src/main/resources/
│   │   └── application.yml
│   └── pom.xml
├── frontend/                         # Vue 3 前端
│   ├── src/
│   │   ├── components/              # 组件
│   │   ├── views/                   # 页面
│   │   │   └── ChatView.vue
│   │   ├── store/                   # Pinia 状态管理
│   │   │   └── chat.js
│   │   ├── api/                     # API 调用
│   │   │   └── index.js
│   │   ├── App.vue
│   │   └── main.js
│   ├── index.html
│   ├── vite.config.js
│   └── package.json
└── python-service/                   # Python 模型服务封装
    ├── app.py                        # Flask 应用
    └── requirements.txt
```

## 技术栈

### 后端
- **Java**: JDK 17+
- **Spring Boot**: 3.2.5
- **Spring Web**: RESTful API
- **Spring WebSocket**: 实时通信
- **OkHttp**: HTTP 客户端
- **Lombok**: 简化代码
- **SLF4J + Logback**: 日志

### 前端
- **Vue**: 3.4.x
- **Element Plus**: UI 组件库
- **Pinia**: 状态管理
- **Axios**: HTTP 客户端
- **SockJS + STOMP**: WebSocket 客户端
- **Vite**: 构建工具

### Python 服务
- **Flask**: 3.0.2
- **Flask-CORS**: 跨域支持
- **FlashHead**: 数字人生成模型
- **edge-tts / gTTS**: 文本转语音
- **FFmpeg**: 视频处理

## 快速开始

### 前置要求

1. Java 17+
2. Node.js 18+
3. Python 3.10+
4. FFmpeg
5. CUDA (推荐用于 GPU 加速)
6. 火山引擎 ARK API Key

### 模型准备

请先按照原始 SoulX-FlashHead 项目的说明下载模型：
- SoulX-FlashHead-1_3B 模型
- wav2vec2-base-960h 模型

### 1. 启动 Python 服务

首先，将 `python-service` 目录复制或链接到原 SoulX-FlashHead 项目的根目录下，或者确保 Python 服务能够访问到 `flash_head` 模块。

```bash
cd python-service
pip install -r requirements.txt
python app.py
```

Python 服务将在 `http://localhost:5000` 启动。

### 2. 启动 Spring Boot 后端

```bash
cd backend
# 使用 Maven 编译并运行
./mvnw spring-boot:run
```

或使用 IDE（如 IntelliJ IDEA）运行 `FlashHeadApplication.java`。

后端将在 `http://localhost:8080` 启动。

### 3. 启动 Vue 前端

```bash
cd frontend
npm install
npm run dev
```

前端将在 `http://localhost:3000` 启动。

### 4. 使用应用

1. 打开浏览器访问 `http://localhost:3000`
2. 在左侧设置面板输入火山引擎 ARK API Key
3. 点击「初始化模型」按钮（可选，首次发送消息会自动初始化）
4. 在底部输入框输入消息，点击「发送」
5. 数字人将通过视频回复您！

## API 说明

### Python 服务 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/tts` | POST | 文本转语音 |
| `/initialize` | POST | 初始化模型 |
| `/generate-video` | POST | 生成完整视频 |
| `/generate-video-streaming` | POST | 流式生成视频 |
| `/generate-idle-video` | POST | 生成空闲视频 |

### Spring Boot 后端 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/api/chat/history` | GET | 获取聊天历史 |
| `/api/chat/current-video` | GET | 获取当前视频 |
| `/api/chat/send` | POST | 发送聊天消息 |
| `/api/chat/initialize` | POST | 初始化模型 |
| `/api/chat/idle-video` | POST | 生成空闲视频 |
| `/api/video/stream` | GET | 流式播放视频 |
| `/ws` | WebSocket | WebSocket 端点 |

### WebSocket 主题

| 主题 | 说明 |
|------|------|
| `/topic/chat/{sessionId}` | 聊天消息推送 |
| `/topic/video/{sessionId}` | 视频片段推送 |

## 配置说明

### 后端配置 (application.yml)

```yaml
server:
  port: 8080

spring:
  application:
    name: flashhead-backend

flashhead:
  python-service:
    url: http://localhost:5000  # Python 服务地址
  volcengine:
    api-url: https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions
  video:
    storage-path: ./chat_results

logging:
  level:
    com.soulx.flashhead: DEBUG
```

## 架构说明

### 整体架构

```
┌─────────────┐
│   Vue 前端  │
└──────┬──────┘
       │ HTTP / WebSocket
       ↓
┌────────────────────┐
│  Spring Boot 后端  │
│  - LLM 集成        │
│  - 聊天管理        │
│  - WebSocket 推送   │
└──────┬─────────────┘
       │ HTTP
       ↓
┌────────────────────┐
│  Python 服务       │
│  - TTS             │
│  - 视频生成        │
│  - 流式输出        │
└──────┬─────────────┘
       │
       ↓
┌────────────────────┐
│  FlashHead 模型    │
└────────────────────┘
```

### 工作流程

1. 用户在前端输入消息并发送
2. Spring Boot 后端接收消息
3. 后端调用火山引擎 LLM 获取回复
4. 后端调用 Python 服务进行 TTS（文本转语音）
5. Python 服务使用 FlashHead 模型根据音频生成数字人视频
6. 视频片段通过 WebSocket 推送到前端
7. 前端播放视频并显示聊天消息

## 开发说明

### 添加新的后端功能

1. 在 `controller/` 中添加新的 REST 控制器
2. 在 `service/` 中添加业务逻辑
3. 在 `model/` 中定义数据模型
4. 在 `client/` 中添加外部服务调用

### 添加新的前端组件

1. 在 `components/` 中添加 Vue 组件
2. 在 `views/` 中添加页面视图
3. 在 `api/` 中添加 API 调用方法
4. 在 `store/` 中添加状态管理

## 注意事项

1. **启动顺序**: 确保三个服务按正确顺序启动：Python 服务 → Spring Boot 后端 → Vue 前端
2. **模型路径**: 确保 FlashHead 模型路径配置正确
3. **API Key**: 确保已正确配置火山引擎 API Key
4. **视频存储**: 视频文件将保存在 `chat_results/` 目录下
5. **Python 路径**: Python 服务需要能够访问到原项目的 `flash_head` 模块

## 许可证

与原始 SoulX-FlashHead 项目保持一致。

