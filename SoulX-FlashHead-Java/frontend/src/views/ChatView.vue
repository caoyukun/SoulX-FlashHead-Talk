<template>
  <el-row :gutter="20" class="chat-container">
    <el-col :span="8">
      <el-card class="settings-card">
        <template #header>
          <div class="card-header">
            <span>⚙️ 设置</span>
          </div>
        </template>
        
        <el-form :model="settings" label-width="120px">
          <el-form-item label="数字人照片">
            <el-input
              v-model="settings.condImage"
              placeholder="examples/girl.png"
            />
          </el-form-item>
          
          <el-collapse>
            <el-collapse-item title="🔧 高级设置">
              <el-form-item label="Checkpoint目录">
                <el-input
                  v-model="settings.ckptDir"
                  placeholder="models/SoulX-FlashHead-1_3B"
                />
              </el-form-item>
              
              <el-form-item label="Wav2Vec目录">
                <el-input
                  v-model="settings.wav2vecDir"
                  placeholder="models/wav2vec2-base-960h"
                />
              </el-form-item>
              
              <el-form-item label="模型类型">
                <el-select v-model="settings.modelType" placeholder="请选择">
                  <el-option label="Pro" value="pro" />
                  <el-option label="Lite" value="lite" />
                </el-select>
              </el-form-item>
              
              <el-form-item label="随机种子">
                <el-input-number v-model="settings.seed" :min="0" />
              </el-form-item>
              
              <el-form-item label="人脸裁剪">
                <el-switch v-model="settings.useFaceCrop" />
              </el-form-item>
            </el-collapse-item>
          </el-collapse>
          
          <el-button type="primary" @click="initializeModel" :loading="isInitializing">
            初始化模型
          </el-button>
          
          <el-button type="success" @click="downloadCompleteVideo" :loading="isDownloading" style="margin-left: 10px;">
            📥 下载完整视频
          </el-button>
        </el-form>
      </el-card>
    </el-col>
    
    <el-col :span="16">
      <el-card class="video-card">
        <template #header>
          <div class="card-header">
            <span>📺 数字人</span>
            <el-tag v-if="isConnected" type="success" size="small">已连接</el-tag>
            <el-tag v-else type="danger" size="small">未连接</el-tag>
          </div>
        </template>
        <div class="video-wrapper">
          <!-- 实际视频播放元素 -->
          <video
            ref="videoPlayer"
            class="digital-human-video"
            playsinline
            @timeupdate="handleTimeUpdate"
            @loadedmetadata="handleMetadataLoaded"
            @ended="handleVideoEnded"
            @error="handleVideoError"
            @volumechange="handleVolumeChange"
          >
            您的浏览器不支持视频播放。
          </video>
          
          <!-- 自定义控件层 -->
          <div class="custom-controls" ref="customControls">
            <!-- 进度条 -->
            <div class="progress-container" @click="handleProgressClick">
              <div class="progress-bar" :style="{ width: globalProgress + '%' }"></div>
            </div>
            
            <!-- 时间显示 -->
            <div class="time-display">
              <span>{{ formatTime(currentGlobalTime) }}</span>
              <span>/</span>
              <span>{{ formatTime(totalDuration) }}</span>
            </div>
            
            <!-- 播放/暂停按钮 -->
            <button class="play-btn" @click="togglePlay" v-if="!isPaused">
              ⏸️
            </button>
            <button class="play-btn" @click="togglePlay" v-else>
              ▶️
            </button>
            
            <!-- 音量控制 -->
            <button class="volume-btn" @click="toggleMute">
              {{ isMuted ? '🔇' : '🔊' }}
            </button>
          </div>
        </div>
      </el-card>
      
      <el-card class="chat-card">
        <template #header>
          <div class="card-header">
            <span>💬 聊天记录</span>
          </div>
        </template>
        
        <div class="chat-messages" ref="chatMessages">
          <div
            v-for="(msg, index) in chatStore.chatHistory"
            :key="index"
            class="message-item"
          >
            <div class="message-user">
              <strong>用户:</strong> {{ msg.user }}
            </div>
            <div class="message-assistant">
              <strong>数字人:</strong> {{ msg.assistant }}
            </div>
          </div>
        </div>
        
        <div class="chat-input">
          <el-input
            v-model="inputMessage"
            placeholder="请输入您想说的话..."
            @keyup.enter="sendMessage"
            :disabled="isSending"
          />
          <el-button type="primary" @click="sendMessage" :loading="isSending">
            发送
          </el-button>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useChatStore } from '../store/chat'
import { chatApi, videoApi } from '../api'

const videoPlayer = ref(null)
const customControls = ref(null)
const chatMessages = ref(null)
const inputMessage = ref('')
const isSending = ref(false)
const isInitializing = ref(false)
const isDownloading = ref(false)
const isConnected = ref(false)
const isMuted = ref(false)
const isPaused = ref(false)
const savedVolume = ref(1)

// 伪流式视频管理
const videoSegments = ref([]) // 存储视频段信息 { url, path, type, duration, startOffset }
let currentSegmentIndex = ref(-1)
const totalDuration = ref(0)
const currentGlobalTime = ref(0)
const globalProgress = computed(() => {
  if (totalDuration.value === 0) return 0
  return (currentGlobalTime.value / totalDuration.value) * 100
})

let hasUserInteracted = ref(false)
let ws = null

const chatStore = useChatStore()

const settings = reactive({
  condImage: 'examples/girl.png',
  ckptDir: 'models/SoulX-FlashHead-1_3B',
  wav2vecDir: 'models/wav2vec2-base-960h',
  modelType: 'lite',
  seed: 9999,
  useFaceCrop: false
})

const scrollToBottom = () => {
  nextTick(() => {
    if (chatMessages.value) {
      chatMessages.value.scrollTop = chatMessages.value.scrollHeight
    }
  })
}

// 格式化时间显示
const formatTime = (seconds) => {
  const mins = Math.floor(seconds / 60)
  const secs = Math.floor(seconds % 60)
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
}

// 播放指定的视频段
const playSegment = (index) => {
  if (index < 0 || index >= videoSegments.value.length) {
    console.log('视频段索引超出范围')
    return
  }
  
  const segment = videoSegments.value[index]
  console.log('播放视频段:', index, segment.path)
  
  currentSegmentIndex.value = index
  
  if (videoPlayer.value) {
    videoPlayer.value.src = segment.url
    videoPlayer.value.muted = isMuted.value
    videoPlayer.value.volume = savedVolume.value
    
    const playPromise = videoPlayer.value.play()
    if (playPromise !== undefined) {
      playPromise.catch(e => {
        console.log('自动播放失败:', e)
        if (!hasUserInteracted.value) {
          ElMessage.info('请点击视频区域启用播放')
        }
      })
    }
  }
}

// 处理时间更新，计算全局进度
const handleTimeUpdate = () => {
  if (videoPlayer.value && currentSegmentIndex.value >= 0) {
    const segment = videoSegments.value[currentSegmentIndex.value]
    currentGlobalTime.value = segment.startOffset + videoPlayer.value.currentTime
  }
}

// 处理元数据加载，记录视频时长
const handleMetadataLoaded = () => {
  if (videoPlayer.value && currentSegmentIndex.value >= 0) {
    const segment = videoSegments.value[currentSegmentIndex.value]
    if (segment.duration === undefined || segment.duration === 0) {
      console.log('记录视频段时长:', videoPlayer.value.duration)
      segment.duration = videoPlayer.value.duration
      recalculateTotalDuration()
    }
  }
}

// 重新计算总时长和每个视频段的起始偏移
const recalculateTotalDuration = () => {
  let total = 0
  videoSegments.value.forEach((segment, index) => {
    segment.startOffset = total
    if (segment.duration) {
      total += segment.duration
    }
  })
  totalDuration.value = total
  console.log('重新计算总时长:', totalDuration.value)
}

// 视频播放结束，播放下一个
const handleVideoEnded = () => {
  console.log('视频段播放结束')
  if (currentSegmentIndex.value < videoSegments.value.length - 1) {
    playSegment(currentSegmentIndex.value + 1)
  } else {
    console.log('所有视频段播放完毕')
    isPaused.value = true
  }
}

const handleVideoError = (e) => {
  console.error('视频播放错误:', e)
  // 跳过当前视频，播放下一个
  if (currentSegmentIndex.value < videoSegments.value.length - 1) {
    playSegment(currentSegmentIndex.value + 1)
  }
}

const handleVolumeChange = () => {
  if (videoPlayer.value) {
    isMuted.value = videoPlayer.value.muted
    if (!isMuted.value) {
      savedVolume.value = videoPlayer.value.volume
    }
  }
}

// 处理进度条点击，跳转到指定位置
const handleProgressClick = (event) => {
  if (!customControls.value || totalDuration.value === 0) return
  
  const rect = customControls.value.getBoundingClientRect()
  const x = event.clientX - rect.left
  const percentage = x / rect.width
  const targetTime = percentage * totalDuration.value
  
  // 找到对应的视频段
  let targetIndex = 0
  let accumulatedTime = 0
  for (let i = 0; i < videoSegments.value.length; i++) {
    const segment = videoSegments.value[i]
    if (!segment.duration) continue
    
    if (targetTime < accumulatedTime + segment.duration) {
      targetIndex = i
      break
    }
    accumulatedTime += segment.duration
    targetIndex = i
  }
  
  // 切换到目标视频段并设置播放位置
  if (targetIndex !== currentSegmentIndex.value) {
    playSegment(targetIndex)
  }
  
  // 等待视频加载后设置播放位置
  setTimeout(() => {
    if (videoPlayer.value && videoSegments.value[targetIndex]) {
      const segment = videoSegments.value[targetIndex]
      const localTime = targetTime - segment.startOffset
      videoPlayer.value.currentTime = Math.max(0, Math.min(localTime, segment.duration || 0))
    }
  }, 100)
}

// 切换播放/暂停
const togglePlay = () => {
  if (!videoPlayer.value) return
  
  hasUserInteracted.value = true
  
  if (isPaused.value) {
    videoPlayer.value.play().catch(e => console.log('播放失败:', e))
    isPaused.value = false
  } else {
    videoPlayer.value.pause()
    isPaused.value = true
  }
}

// 切换静音
const toggleMute = () => {
  if (!videoPlayer.value) return
  videoPlayer.value.muted = !videoPlayer.value.muted
}

// 添加视频到队列
const addVideoSegment = (path) => {
  console.log('addVideoSegment called:', path)
  
  const type = path.includes('idle') ? 'idle' : 'reply'
  const videoUrl = videoApi.getVideoUrl(path)
  
  // 如果是回复视频，清空空闲视频队列，优先播放回复
  if (type === 'reply') {
    console.log('收到回复视频，清空空闲队列')
    videoSegments.value = videoSegments.value.filter(s => s.type === 'reply')
    currentSegmentIndex.value = -1
  }
  
  videoSegments.value.push({
    url: videoUrl,
    path,
    type,
    duration: undefined,
    startOffset: totalDuration.value
  })
  
  console.log('视频队列长度:', videoSegments.value.length)
  
  // 如果没有在播放，开始播放
  if (currentSegmentIndex.value === -1 && videoSegments.value.length > 0) {
    console.log('开始播放视频')
    playSegment(0)
    isPaused.value = false
  }
}

const connectWebSocket = () => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsUrl = `${protocol}//${window.location.host}/ws`
  
  ws = new WebSocket(wsUrl)
  
  ws.onopen = () => {
    console.log('WebSocket 连接成功')
    isConnected.value = true
    chatStore.setConnected(true)
    ElMessage.success('WebSocket 连接成功')
  }
  
  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)
      handleWebSocketMessage(data)
    } catch (e) {
      console.error('解析 WebSocket 消息失败:', e)
    }
  }
  
  ws.onerror = (error) => {
    console.error('WebSocket 错误:', error)
    isConnected.value = false
    chatStore.setConnected(false)
  }
  
  ws.onclose = () => {
    console.log('WebSocket 连接关闭')
    isConnected.value = false
    chatStore.setConnected(false)
  }
}

const handleWebSocketMessage = (data) => {
  if (data.type === 'chat') {
    chatStore.addMessage(data.data.user, data.data.assistant)
    scrollToBottom()
  } else if (data.type === 'error') {
    ElMessage.error(data.message)
  } else if (data.type === 'video_segment') {
    if (data.path) {
      addVideoSegment(data.path)
    }
  }
}

const initializeModel = async () => {
  isInitializing.value = true
  try {
    await chatApi.initialize(settings)
    
    // 重置视频队列
    videoSegments.value = []
    currentSegmentIndex.value = -1
    totalDuration.value = 0
    currentGlobalTime.value = 0
    
    ElMessage.success('模型初始化成功，正在生成视频流...')
  } catch (error) {
    console.error('初始化失败:', error)
    ElMessage.error('初始化失败: ' + (error.response?.data?.message || error.message))
  } finally {
    isInitializing.value = false
  }
}

const sendMessage = async () => {
  if (!inputMessage.value.trim()) {
    ElMessage.warning('请输入消息')
    return
  }
  
  hasUserInteracted.value = true
  
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    ElMessage.warning('WebSocket 未连接，正在尝试连接...')
    connectWebSocket()
    return
  }
  
  isSending.value = true
  const message = inputMessage.value
  inputMessage.value = ''
  
  try {
    const response = await chatApi.sendMessage({
      message,
      condImage: settings.condImage,
      ckptDir: settings.ckptDir,
      wav2vecDir: settings.wav2vecDir,
      modelType: settings.modelType,
      seed: settings.seed,
      useFaceCrop: settings.useFaceCrop
    })
    
    const sessionId = response.data.sessionId
    chatStore.setSessionId(sessionId)
    ElMessage.info('消息已发送，正在处理...')
  } catch (error) {
    console.error('发送消息失败:', error)
    ElMessage.error('发送消息失败: ' + (error.response?.data?.message || error.message))
  } finally {
    isSending.value = false
  }
}

const downloadCompleteVideo = async () => {
  isDownloading.value = true
  try {
    const link = document.createElement('a')
    link.href = '/api/video/stream-complete'
    link.download = 'SoulX-FlashHead_' + new Date().toISOString().slice(0, 19).replace(/:/g, '-') + '.mp4'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    ElMessage.success('完整视频下载已开始')
  } catch (error) {
    console.error('下载失败:', error)
    ElMessage.error('下载失败: ' + error.message)
  } finally {
    isDownloading.value = false
  }
}

onMounted(() => {
  console.log('ChatView mounted')
  connectWebSocket()
  
  // 监听用户交互
  const handleInteraction = () => {
    hasUserInteracted.value = true
    if (videoPlayer.value && videoPlayer.value.paused && !isPaused.value) {
      videoPlayer.value.play().catch(e => console.log('播放失败:', e))
    }
  }
  
  document.addEventListener('click', handleInteraction)
  document.addEventListener('keydown', handleInteraction)
  
  onUnmounted(() => {
    document.removeEventListener('click', handleInteraction)
    document.removeEventListener('keydown', handleInteraction)
  })
})

onUnmounted(() => {
  if (ws) {
    ws.close()
  }
})
</script>

<style scoped>
.chat-container {
  height: 100%;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: bold;
  font-size: 16px;
}

.settings-card,
.video-card,
.chat-card {
  margin-bottom: 20px;
}

.video-wrapper {
  position: relative;
  display: flex;
  justify-content: center;
  align-items: center;
  background-color: #000;
  border-radius: 8px;
  overflow: hidden;
}

.digital-human-video {
  width: 100%;
  max-height: 512px;
  object-fit: contain;
  z-index: 1;
}

.custom-controls {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  background: linear-gradient(to top, rgba(0,0,0,0.8), transparent);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 10;
}

.progress-container {
  width: 100%;
  height: 6px;
  background-color: rgba(255,255,255,0.3);
  border-radius: 3px;
  cursor: pointer;
}

.progress-bar {
  height: 100%;
  background-color: #409eff;
  border-radius: 3px;
  transition: width 0.1s linear;
}

.time-display {
  display: flex;
  align-items: center;
  gap: 5px;
  color: white;
  font-size: 14px;
}

.play-btn,
.volume-btn {
  background: none;
  border: none;
  color: white;
  font-size: 18px;
  cursor: pointer;
  padding: 5px;
}

.play-btn:hover,
.volume-btn:hover {
  transform: scale(1.1);
}

.chat-messages {
  height: 300px;
  overflow-y: auto;
  padding: 10px;
  background-color: #f5f7fa;
  border-radius: 4px;
  margin-bottom: 15px;
}

.message-item {
  margin-bottom: 15px;
  padding: 10px;
  background-color: white;
  border-radius: 4px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}

.message-user {
  color: #409eff;
  margin-bottom: 5px;
}

.message-assistant {
  color: #67c23a;
}

.chat-input {
  display: flex;
  gap: 10px;
}

.chat-input .el-input {
  flex: 1;
}
</style>
