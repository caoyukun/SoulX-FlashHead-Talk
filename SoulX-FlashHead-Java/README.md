# SoulX-FlashHead Java 版本

基于 Spring Boot + Vue 的实时交互数字人聊天应用，移植自原始 Python/Gradio 版本。

## 项目结构

```
SoulX-FlashHead-Java/
├── backend/                          # Spring Boot 后端
│   ├── src/main/java/com/soulx/flashhead/
│   │   ├── controller/              # REST 控制器
│   │   ├── service/                 # 业务逻辑
│   │   ├── model/                   # 数据模型
│   │   ├── config/                  # 配置类
│   │   ├── websocket/               # WebSocket 处理
│   │   └── client/                  # Python 服务客户端
│   └── pom.xml
├── frontend/                         # Vue 3 前端
│   ├── src/
│   │   ├── components/              # 组件
│   │   ├── views/                   # 页面
│   │   ├── store/                   # Pinia 状态管理
│   │   ├── api/                     # API 调用
│   │   └── utils/                   # 工具函数
│   └── package.json
└── python-service/                   # Python 模型服务封装
    ├── app.py                        # Flask 应用
    └── requirements.txt
```

## 技术栈

### 后端
- Java 17+
- Spring Boot 3.2.x
- Spring Web (REST API)
- Spring WebSocket
- OkHttp (HTTP 客户端)
- Lombok

### 前端
- Vue 3.4.x
- Element Plus (UI 组件库)
- Pinia (状态管理)
- Axios (HTTP 客户端)
- SockJS + STOMP (WebSocket)
- Vite (构建工具)

### Python 服务
- Flask 3.0.2
- Flask-CORS
- FlashHead 模型
- edge-tts / gTTS (文本转语音)

## 快速开始

### 前置要求

1. Java 17+
2. Node.js 18+
3. Python 3.10+
4. FFmpeg
5. CUDA (推荐用于 GPU 加速)

### 1. 启动 Python 服务

```bash
cd python-service
pip install -r requirements.txt
python app.py
```

Python 服务将在 `http://localhost:5000` 启动。

### 2. 启动 Spring Boot 后端

```bash
cd backend
./mvnw spring-boot:run
```

或使用 IDE 运行 `FlashHeadApplication.java`。

后端将在 `http://localhost:8080` 启动。

### 3. 启动 Vue 前端

```bash
cd frontend
npm install
npm run dev
```

前端将在 `http://localhost:3000` 启动。

## API 说明

### Python 服务 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/tts` | POST | 文本转语音 |
| `/initialize` | POST | 初始化模型 |
| `/generate-video` | POST | 生成视频 |

### Spring Boot 后端 API

| 接口 | 方法 | 说明 |
|------|------|------|
| `/health` | GET | 健康检查 |
| `/ws` | WebSocket | WebSocket 端点 |

## 配置说明

### 后端配置 (application.yml)

```yaml
flashhead:
  python-service:
    url: http://localhost:5000  # Python 服务地址
  volcengine:
    api-url: https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions
  video:
    storage-path: ./chat_results
```

## 开发说明

### 添加新的后端功能

1. 在 `controller/` 中添加新的 REST 控制器
2. 在 `service/` 中添加业务逻辑
3. 在 `model/` 中定义数据模型

### 添加新的前端组件

1. 在 `components/` 中添加 Vue 组件
2. 在 `views/` 中添加页面视图
3. 在 `api/` 中添加 API 调用方法

## 注意事项

1. 确保三个服务按正确顺序启动：Python 服务 → Spring Boot 后端 → Vue 前端
2. 首次运行需要下载 FlashHead 模型权重
3. 确保已正确配置火山引擎 API Key
4. 视频文件将保存在 `chat_results/` 目录下

## 下一步

- [ ] 实现完整的聊天功能
- [ ] 实现 WebSocket 流式视频传输
- [ ] 实现空闲视频生成
- [ ] 添加端到端测试
- [ ] 性能优化

## 许可证

与原始 SoulX-FlashHead 项目保持一致。
