# SoulX-FlashHead HLS 流式视频播放改造计划

## 1. 项目概述

### 1.1 当前架构问题
目前项目采用**分段 MP4 + 双播放器切换**方案实现"伪流式"播放：
- Python 服务将大模型生成的视频保存为小 MP4 片段
- 通过 WebSocket 将片段路径推送给前端
- 前端使用两个 `<video>` 元素交替播放，实现无缝切换

**存在的问题：**
1. **播放间隙卡顿**：MP4 片段之间切换时仍有明显卡顿
2. **预加载延迟**：需要等待片段完全生成并保存到磁盘后才能播放
3. **双播放器复杂度**：需要维护两个播放器的状态同步
4. **浏览器兼容性**：不同浏览器对 MP4 播放的处理有差异

### 1.2 目标架构
采用 **FFmpeg 实时转封装 + HLS 流式传输** 方案：
- Python 服务将生成的视频帧**实时**通过管道喂给 FFmpeg
- FFmpeg 实时解封装 MP4、合并音频、转封装为 **HLS (HTTP Live Streaming)** 格式
- 后端提供 HLS 播放列表 (.m3u8) 和 TS 片段接口
- 前端使用 **hls.js** 播放 HLS 流，实现真正的低延迟流式播放

**优势：**
1. **真正的流式播放**：视频生成和播放同时进行，无需等待完整文件
2. **更低的延迟**：HLS 支持低延迟模式 (LL-HLS)，延迟可控制在 2-3 秒
3. **更好的兼容性**：HLS 是苹果提出的标准，浏览器支持良好
4. **自适应码率**：HLS 原生支持多码率自适应（未来可扩展）

---

## 2. 技术方案

### 2.1 整体架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              前端 (Vue 3)                                │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  hls.js 播放器                                                   │   │
│  │  - 加载 .m3u8 播放列表                                           │   │
│  │  - 自动下载并播放 TS 片段                                        │   │
│  │  - 支持低延迟模式 (LL-HLS)                                       │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │ HTTP/HLS
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         后端 (Spring Boot)                               │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  HLS Controller                                                  │   │
│  │  - /api/hls/{sessionId}/playlist.m3u8  (播放列表接口)            │   │
│  │  - /api/hls/{sessionId}/{segment}.ts   (TS片段接口)              │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  HLS Stream Service                                              │   │
│  │  - 管理 HLS 会话和播放列表                                       │   │
│  │  - 接收 FFmpeg 输出的 TS 片段                                    │   │
│  │  - 动态更新 .m3u8 文件                                           │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                    │ HTTP/回调
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        Python 服务 (Flask)                               │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  视频生成器                                                      │   │
│  │  - 使用 FlashHead 模型生成视频帧                                 │   │
│  │  - 实时将帧数据写入 FFmpeg stdin 管道                            │   │
│  └─────────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────────┐   │
│  │  FFmpeg 进程 (子进程)                                            │   │
│  │  - 输入：原始视频帧 + 音频                                       │   │
│  │  - 处理：编码为 H.264 + AAC                                      │   │
│  │  - 输出：HLS 格式 (TS 片段 + .m3u8 播放列表)                     │   │
│  │  - 片段通过 HTTP 推送到后端                                      │   │
│  └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
```

### 2.2 数据流

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│  FlashHead   │────▶│   FFmpeg     │────▶│   后端 HLS   │────▶│  hls.js      │
│   模型生成   │     │  实时转封装  │     │   服务缓存   │     │  播放器      │
│   视频帧     │     │  为 HLS      │     │   TS 片段    │     │              │
└──────────────┘     └──────────────┘     └──────────────┘     └──────────────┘
      │                    │                    │                    │
      │ 实时帧数据          │ HLS 片段           │ HTTP 响应          │ 播放
      │ (通过管道)          │ (HTTP POST)        │ (HTTP GET)         │
```

---

## 3. 详细实现步骤

### 3.1 后端实现 (Spring Boot)

#### 3.1.1 新增 HLS 流服务

**文件**: `backend/src/main/java/com/soulx/flashhead/service/HlsStreamService.java`

功能：
- 管理 HLS 会话（每个聊天会话一个 HLS 流）
- 缓存 TS 片段（内存或临时文件）
- 动态生成 .m3u8 播放列表
- 处理片段过期和清理

关键数据结构：
```java
@Component
public class HlsStreamService {
    // 会话ID -> HLS 会话
    private final Map<String, HlsSession> sessions = new ConcurrentHashMap<>();
    
    // TS 片段信息
    public static class TsSegment {
        private int sequenceNumber;  // 序列号
        private byte[] data;         // TS 数据
        private double duration;     // 片段时长（秒）
        private long timestamp;      // 生成时间戳
    }
    
    // HLS 会话
    public static class HlsSession {
        private String sessionId;
        private List<TsSegment> segments;  // TS 片段列表
        private boolean isLive;            // 是否正在直播
        private long targetDuration;       // #EXT-X-TARGETDURATION
    }
}
```

#### 3.1.2 新增 HLS 控制器

**文件**: `backend/src/main/java/com/soulx/flashhead/controller/HlsController.java`

接口：
```java
@RestController
@RequestMapping("/api/hls")
public class HlsController {
    
    // 获取 M3U8 播放列表
    @GetMapping("/{sessionId}/playlist.m3u8")
    public ResponseEntity<String> getPlaylist(@PathVariable String sessionId);
    
    // 获取 TS 片段
    @GetMapping("/{sessionId}/{segmentName}.ts")
    public ResponseEntity<byte[]> getSegment(
        @PathVariable String sessionId, 
        @PathVariable String segmentName
    );
    
    // 接收 FFmpeg 推送的 TS 片段（内部接口）
    @PostMapping("/{sessionId}/segment")
    public ResponseEntity<Void> uploadSegment(
        @PathVariable String sessionId,
        @RequestParam("sequence") int sequenceNumber,
        @RequestParam("duration") double duration,
        @RequestBody byte[] tsData
    );
    
    // 结束 HLS 流
    @PostMapping("/{sessionId}/end")
    public ResponseEntity<Void> endStream(@PathVariable String sessionId);
}
```

#### 3.1.3 修改 ChatService

- 在初始化视频流时创建 HLS 会话
- 接收 Python 服务推送的 TS 片段
- 向前端发送 HLS 播放地址

### 3.2 Python 服务改造

#### 3.2.1 新增 HLS 生成器

**文件**: `python-service/hls_generator.py`

功能：
- 启动 FFmpeg 进程，配置 HLS 输出
- 将 FlashHead 生成的视频帧实时写入 FFmpeg stdin
- FFmpeg 配置为输出 HLS 格式，通过 HTTP 推送 TS 片段到后端

FFmpeg 命令示例：
```bash
ffmpeg -y \
  -f rawvideo -pix_fmt rgb24 -s 512x512 -r 25 -i - \
  -f s16le -ar 16000 -ac 1 -i audio_pipe \
  -c:v libx264 -pix_fmt yuv420p -profile:v baseline -level 3.0 \
  -preset ultrafast -tune zerolatency \
  -c:a aac -b:a 128k \
  -f hls \
  -hls_time 2 \
  -hls_list_size 0 \
  -hls_segment_type mpegts \
  -hls_segment_filename pipe: \
  -method POST \
  -http_persistent 0 \
  "http://localhost:8080/api/hls/{sessionId}/segment?sequence=%05d&duration=%d"
```

#### 3.2.2 修改 app.py

- 新增 `/generate-video-hls` 接口
- 使用 HLS 生成器替代分段 MP4 生成
- 保持向后兼容（保留原有接口）

### 3.3 前端改造

#### 3.3.1 安装 hls.js

```bash
cd frontend
npm install hls.js
```

#### 3.3.2 修改 ChatView.vue

- 替换双播放器方案为 hls.js 播放器
- 使用单个 `<video>` 元素播放 HLS 流
- 监听 WebSocket 获取 HLS 播放地址

关键代码：
```javascript
import Hls from 'hls.js'

// 初始化 HLS 播放器
const initHlsPlayer = (hlsUrl) => {
  if (Hls.isSupported()) {
    const hls = new Hls({
      enableWorker: true,
      lowLatencyMode: true,  // 启用低延迟模式
      backBufferLength: 90   // 后退缓冲区长度
    })
    hls.loadSource(hlsUrl)
    hls.attachMedia(videoPlayer.value)
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      videoPlayer.value.play()
    })
  } else if (videoPlayer.value.canPlayType('application/vnd.apple.mpegurl')) {
    // Safari 原生支持 HLS
    videoPlayer.value.src = hlsUrl
    videoPlayer.value.play()
  }
}
```

#### 3.3.3 新增 HLS API

**文件**: `frontend/src/api/hls.js`

```javascript
export const hlsApi = {
  getPlaylistUrl(sessionId) {
    return `/api/hls/${sessionId}/playlist.m3u8`
  }
}
```

---

## 4. 实施步骤

### Phase 1: 后端 HLS 基础设施
1. 创建 `HlsStreamService` 管理 HLS 会话和 TS 片段
2. 创建 `HlsController` 提供 M3U8 和 TS 接口
3. 添加单元测试验证 HLS 播放列表生成逻辑

### Phase 2: Python 服务 HLS 生成
1. 创建 `HlsGenerator` 类封装 FFmpeg HLS 生成
2. 新增 `/generate-video-hls` 接口
3. 测试 FFmpeg 命令行参数，确保 TS 片段正确生成并推送

### Phase 3: 前端 HLS 播放
1. 安装并配置 hls.js
2. 修改 `ChatView.vue`，替换播放器逻辑
3. 处理 HLS 播放事件（加载、缓冲、错误等）

### Phase 4: 集成测试
1. 端到端测试完整流程
2. 性能测试：延迟、卡顿率、CPU/内存占用
3. 兼容性测试：Chrome、Firefox、Safari、Edge

### Phase 5: 优化和清理
1. 清理旧的分段 MP4 代码（可选，保留向后兼容）
2. 优化 TS 片段缓存策略（LRU、过期清理）
3. 添加监控和日志

---

## 5. 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|----------|
| FFmpeg HLS 推送不稳定 | 高 | 实现重试机制，备用文件写入方案 |
| hls.js 兼容性问题 | 中 | 保留原生 video 标签作为 fallback |
| 内存占用过高 | 中 | 限制 TS 片段缓存数量，及时清理过期片段 |
| 延迟不达标 | 中 | 调整 HLS 片段时长（2秒 -> 1秒），启用 LL-HLS |
| 网络波动导致播放卡顿 | 低 | 增加缓冲区大小，实现自适应码率（未来） |

---

## 6. 回滚方案

如果新方案出现问题，可以快速回滚到旧方案：
1. 前端通过配置切换播放器模式（HLS / 分段MP4）
2. Python 服务保留原有接口，通过参数控制生成模式
3. 后端保留原有 VideoController，只是新增 HlsController

---

## 7. 预期效果

### 7.1 性能指标

| 指标 | 当前方案 | 目标方案 | 提升 |
|------|----------|----------|------|
| 首帧延迟 | 3-5 秒 | 1-2 秒 | 50%+ |
| 片段切换卡顿 | 明显 | 无感知 | 彻底解决 |
| 端到端延迟 | 5-8 秒 | 2-4 秒 | 50%+ |
| 播放器复杂度 | 高（双播放器） | 低（单播放器） | 简化 |

### 7.2 用户体验
- 数字人回复更流畅，无卡顿感
- 语音和视频同步更好
- 支持拖拽进度条（HLS 原生支持）

---

## 8. 附录

### 8.1 HLS 格式简介

HLS (HTTP Live Streaming) 是 Apple 提出的基于 HTTP 的流媒体传输协议：
- **.m3u8**: 播放列表文件，包含 TS 片段列表和元数据
- **.ts**: 视频片段文件，通常 2-10 秒一个
- **#EXT-X-TARGETDURATION**: 最大片段时长
- **#EXTINF**: 每个片段的实际时长

### 8.2 低延迟 HLS (LL-HLS)

Apple 在 2019 年推出的低延迟扩展：
- 片段时长缩短到 1-2 秒
- 支持部分片段加载 (Partial Segment)
- 延迟可从 10-30 秒降低到 2-3 秒

### 8.3 FFmpeg HLS 参数说明

```bash
-hls_time 2              # 每个片段目标时长 2 秒
-hls_list_size 0         # 播放列表保留所有片段（0=无限制）
-hls_segment_type mpegts # 使用 MPEG-TS 格式
-hls_flags delete_segments # 自动删除过期片段（文件模式）
```
