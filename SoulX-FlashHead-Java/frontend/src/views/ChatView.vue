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
          <video
            ref="videoPlayer"
            class="digital-human-video"
            controls
            autoplay
            playsinline
            @ended="handleVideoEnded"
            @error="handleVideoError"
            @volumechange="handleVolumeChange"
          >
            <source :src="currentVideoUrl" type="video/mp4" />
            您的浏览器不支持视频播放。
          </video>
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
import { ref, reactive, onMounted, nextTick, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useChatStore } from '../store/chat'
import { chatApi, videoApi } from '../api'

const videoPlayer = ref(null)
const chatMessages = ref(null)
const inputMessage = ref('')
const isSending = ref(false)
const isInitializing = ref(false)
const isDownloading = ref(false)
const isConnected = ref(false)
const currentVideoUrl = ref('')
const videoQueue = ref([])
const isPlaying = ref(false)
const isMuted = ref(false)
const savedVolume = ref(1)

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

const playNextVideo = () => {
  if (videoQueue.value.length > 0) {
    const nextVideo = videoQueue.value.shift()
    currentVideoUrl.value = nextVideo
    chatStore.setCurrentVideoUrl(nextVideo)
    isPlaying.value = true
    
    nextTick(() => {
      if (videoPlayer.value) {
        videoPlayer.value.muted = isMuted.value
        videoPlayer.value.volume = savedVolume.value
        videoPlayer.value.src = currentVideoUrl.value
        videoPlayer.value.play().catch(e => {
          console.log('自动播放失败，等待用户交互:', e)
        })
      }
    })
  } else {
    isPlaying.value = false
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
      const videoUrl = videoApi.getVideoUrl(data.path)
      videoQueue.value.push(videoUrl)
      
      if (!isPlaying.value) {
        playNextVideo()
      }
    }
  }
}

const initializeModel = async () => {
  isInitializing.value = true
  try {
    await chatApi.initialize(settings)
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
    ElMessage.success('完整视频下载已开始（使用StreamingResponseBody）')
  } catch (error) {
    console.error('下载失败:', error)
    ElMessage.error('下载失败: ' + error.message)
  } finally {
    isDownloading.value = false
  }
}

const handleVideoEnded = () => {
  console.log('视频播放结束，播放下一个')
  playNextVideo()
}

const handleVideoError = (e) => {
  console.error('视频播放错误:', e)
  playNextVideo()
}

onMounted(() => {
  console.log('ChatView mounted')
  connectWebSocket()
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
