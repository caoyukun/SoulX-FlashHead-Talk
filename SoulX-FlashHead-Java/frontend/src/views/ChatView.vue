<template>
  <div class="chat-app">
    <div class="main-container">
      <div class="video-section">
        <div class="video-wrapper" @mouseenter="showControls = true" @mouseleave="showControls = false">
          <video
            ref="videoPlayer1"
            class="digital-human-video"
            :class="{ active: currentPlayerIndex === 0 }"
            playsinline
            preload="auto"
            :loop="false"
            @timeupdate="handleTimeUpdate"
            @loadedmetadata="handleMetadataLoaded"
            @ended="handleVideoEnded(0)"
            @error="handleVideoError(0, $event)"
            @volumechange="handleVolumeChange"
            @canplaythrough="handleCanPlayThrough(0)"
          >
            您的浏览器不支持视频播放。
          </video>
          
          <video
            ref="videoPlayer2"
            class="digital-human-video"
            :class="{ active: currentPlayerIndex === 1 }"
            playsinline
            preload="auto"
            :loop="false"
            @timeupdate="handleTimeUpdate"
            @loadedmetadata="handleMetadataLoaded"
            @ended="handleVideoEnded(1)"
            @error="handleVideoError(1, $event)"
            @volumechange="handleVolumeChange"
            @canplaythrough="handleCanPlayThrough(1)"
          >
            您的浏览器不支持视频播放。
          </video>
          
          <div class="video-overlay" :class="{ 'controls-visible': showControls || alwaysShowControls }">
            <div class="status-bar">
              <div class="connection-status" :class="{ connected: isConnected }">
                <span class="status-dot"></span>
                {{ isConnected ? '已连接' : '连接中...' }}
              </div>
              <button class="toggle-controls-btn" @click="alwaysShowControls = !alwaysShowControls">
                {{ alwaysShowControls ? '隐藏控件' : '显示控件' }}
              </button>
            </div>
            
            <div class="custom-controls" v-if="alwaysShowControls">
              <div class="progress-container" @click="handleProgressClick">
                <div class="progress-bar" :style="{ width: globalProgress + '%' }"></div>
              </div>
              
              <div class="controls-row">
                <span class="time-display">{{ formatTime(currentGlobalTime) }} / {{ formatTime(totalDuration) }}</span>
                
                <div class="center-controls">
                  <button class="control-btn" @click="togglePlay">
                    {{ isPaused ? '▶️' : '⏸️' }}
                  </button>
                </div>
                
                <button class="control-btn" @click="toggleMute">
                  {{ isMuted ? '🔇' : '🔊' }}
                </button>
              </div>
            </div>
          </div>
        </div>
        
        <div class="settings-toggle" @click="showSettings = !showSettings">
          <span class="toggle-icon">{{ showSettings ? '⚙️' : '⚙️' }}</span>
          <span class="toggle-text">{{ showSettings ? '隐藏设置' : '显示设置' }}</span>
        </div>
        
        <transition name="settings">
          <div class="settings-panel" v-if="showSettings">
            <div class="panel-header">
              <span class="panel-title">⚙️ 设置</span>
            </div>
            
            <div class="panel-content">
              <div class="form-item">
                <label>数字人照片</label>
                <el-input v-model="settings.condImage" placeholder="examples/girl.png" size="small" />
              </div>
              
              <el-collapse class="advanced-collapse">
                <el-collapse-item title="🔧 高级设置">
                  <div class="form-item">
                    <label>Checkpoint目录</label>
                    <el-input v-model="settings.ckptDir" placeholder="models/SoulX-FlashHead-1_3B" size="small" />
                  </div>
                  
                  <div class="form-item">
                    <label>Wav2Vec目录</label>
                    <el-input v-model="settings.wav2vecDir" placeholder="models/wav2vec2-base-960h" size="small" />
                  </div>
                  
                  <div class="form-item">
                    <label>模型类型</label>
                    <el-select v-model="settings.modelType" placeholder="请选择" size="small" style="width: 100%">
                      <el-option label="Pro" value="pro" />
                      <el-option label="Lite" value="lite" />
                    </el-select>
                  </div>
                  
                  <div class="form-item">
                    <label>随机种子</label>
                    <el-input-number v-model="settings.seed" :min="0" size="small" style="width: 100%" />
                  </div>
                  
                  <div class="form-item">
                    <label>人脸裁剪</label>
                    <el-switch v-model="settings.useFaceCrop" />
                  </div>
                </el-collapse-item>
              </el-collapse>
              
              <div class="button-group">
                <el-button type="primary" @click="initializeModel" :loading="isInitializing" size="small" style="flex: 1">
                  初始化模型
                </el-button>
                <el-button type="success" @click="downloadCompleteVideo" :loading="isDownloading" size="small">
                  📥
                </el-button>
              </div>
            </div>
          </div>
        </transition>
      </div>
      
      <div class="chat-section">
        <div class="chat-header">
          <div class="avatar">
            <div class="avatar-circle">🤖</div>
          </div>
          <div class="chat-info">
            <h2>数字人助手</h2>
            <p class="status-text">随时为您服务</p>
          </div>
        </div>
        
        <div class="chat-messages" ref="chatMessages">
          <div
            v-for="(msg, index) in chatStore.chatHistory"
            :key="index"
            class="message-item"
          >
            <div class="message-row user">
              <div class="message-content">
                <div class="message-bubble">{{ msg.user }}</div>
              </div>
              <div class="message-avatar">👤</div>
            </div>
            <div class="message-row assistant">
              <div class="message-avatar">🤖</div>
              <div class="message-content">
                <div class="message-bubble">{{ msg.assistant }}</div>
              </div>
            </div>
          </div>
          
          <div v-if="chatStore.chatHistory.length === 0" class="empty-state">
            <div class="empty-icon">👋</div>
            <p>开始和数字人聊天吧！</p>
          </div>
        </div>
        
        <div class="chat-input-area">
          <el-input
            v-model="inputMessage"
            type="textarea"
            :rows="2"
            placeholder="请输入您想说的话..."
            @keyup.enter.ctrl="sendMessage"
            :disabled="isSending"
            resize="none"
          />
          <div class="input-actions">
            <span class="hint-text">Ctrl + Enter 发送</span>
            <el-button type="primary" @click="sendMessage" :loading="isSending" round>
              发送
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useChatStore } from '../store/chat'
import { chatApi, videoApi } from '../api'

const videoPlayer1 = ref(null)
const videoPlayer2 = ref(null)
const chatMessages = ref(null)
const inputMessage = ref('')
const isSending = ref(false)
const isInitializing = ref(false)
const isDownloading = ref(false)
const isConnected = ref(false)
const isMuted = ref(false)
const isPaused = ref(false)
const savedVolume = ref(1)
const showControls = ref(false)
const alwaysShowControls = ref(false)
const showSettings = ref(true)

const currentPlayerIndex = ref(0)
const videoPlayers = computed(() => [videoPlayer1, videoPlayer2])
const currentVideoPlayer = computed(() => videoPlayers.value[currentPlayerIndex.value])
const nextVideoPlayer = computed(() => videoPlayers.value[1 - currentPlayerIndex.value])

const videoReadyState = reactive({ 0: false, 1: false })

const videoSegments = ref([])
let currentSegmentIndex = ref(-1)
const totalDuration = ref(0)
const currentGlobalTime = ref(0)
const globalProgress = computed(() => {
  if (totalDuration.value === 0) return 0
  return Math.min(100, (currentGlobalTime.value / totalDuration.value) * 100)
})

let hasUserInteracted = ref(false)
let ws = null
let tempVideo = null
let isWaitingForNewVideo = ref(false)

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

const formatTime = (seconds) => {
  if (!seconds || isNaN(seconds)) return '00:00'
  const mins = Math.floor(seconds / 60)
  const secs = Math.floor(seconds % 60)
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
}

const recalculateTotalDuration = () => {
  let total = 0
  videoSegments.value.forEach((segment) => {
    segment.startOffset = total
    if (segment.duration && !isNaN(segment.duration)) {
      total += segment.duration
    }
  })
  totalDuration.value = total
  console.log('重新计算总时长:', totalDuration.value)
}

const preloadVideoToPlayer = (index, playerIndex) => {
  if (index < 0 || index >= videoSegments.value.length) {
    console.log('视频段索引超出范围')
    return
  }
  
  const segment = videoSegments.value[index]
  const player = videoPlayers.value[playerIndex]
  
  if (player?.value && segment?.url) {
    console.log(`预加载视频段 ${index} 到播放器 ${playerIndex}:`, segment.path)
    player.value.src = segment.url
    player.value.muted = true
    player.value.volume = savedVolume.value
    player.value.loop = false
    player.value.load()
    videoReadyState[playerIndex] = false
  }
}

const switchToNextVideo = () => {
  const nextIndex = currentSegmentIndex.value + 1
  if (nextIndex >= videoSegments.value.length) {
    console.log('没有更多视频')
    isPaused.value = true
    return
  }
  
  const nextPlayerIndex = 1 - currentPlayerIndex.value
  const prevPlayerIndex = 1 - nextPlayerIndex
  
  if (!videoReadyState[nextPlayerIndex]) {
    console.log('下一个视频还未准备好，等待中...')
    return
  }
  
  console.log('丝滑切换到下一个视频段:', nextIndex)
  
  const nextPlayer = videoPlayers.value[nextPlayerIndex]
  const prevPlayer = videoPlayers.value[prevPlayerIndex]
  
  if (nextPlayer?.value) {
    nextPlayer.value.currentTime = 0
    nextPlayer.value.muted = isMuted.value
    nextPlayer.value.volume = savedVolume.value
    nextPlayer.value.loop = false
    
    if (hasUserInteracted.value || !isPaused.value) {
      nextPlayer.value.play().catch((err) => {
        console.log('自动播放失败:', err)
        isPaused.value = true
      })
    }
  }
  
  currentSegmentIndex.value = nextIndex
  currentPlayerIndex.value = nextPlayerIndex
  isWaitingForNewVideo.value = false
  
  setTimeout(() => {
    if (prevPlayer?.value) {
      prevPlayer.value.pause()
      prevPlayer.value.muted = true
      prevPlayer.value.currentTime = 0
    }
  }, 150)
  
  const preloadIndex = nextIndex + 1
  if (preloadIndex < videoSegments.value.length) {
    preloadVideoToPlayer(preloadIndex, 1 - nextPlayerIndex)
  }
}

const playSegment = (index) => {
  if (index < 0 || index >= videoSegments.value.length) {
    console.log('视频段索引超出范围')
    return
  }
  
  const segment = videoSegments.value[index]
  console.log('播放视频段:', index, segment.path)
  
  currentSegmentIndex.value = index
  isWaitingForNewVideo.value = false
  
  videoReadyState[0] = false
  videoReadyState[1] = false
  
  for (let i = 0; i < videoPlayers.value.length; i++) {
    const p = videoPlayers.value[i]
    if (p?.value) {
      p.value.pause()
      p.value.muted = true
    }
  }
  
  if (index + 1 < videoSegments.value.length) {
    preloadVideoToPlayer(index + 1, 1 - currentPlayerIndex.value)
  }
  
  const player = currentVideoPlayer.value
  if (player?.value) {
    player.value.src = segment.url
    player.value.muted = isMuted.value
    player.value.volume = savedVolume.value
    player.value.loop = false
    player.value.load()
    
    if (hasUserInteracted.value || !isPaused.value) {
      const playPromise = player.value.play()
      if (playPromise !== undefined) {
        playPromise.catch(() => {
          isPaused.value = true
        })
      }
    } else {
      isPaused.value = true
    }
  }
}

const handleTimeUpdate = () => {
  if (currentSegmentIndex.value >= 0) {
    const player = currentVideoPlayer.value
    const segment = videoSegments.value[currentSegmentIndex.value]
    if (player?.value && segment && segment.startOffset !== undefined) {
      currentGlobalTime.value = segment.startOffset + (player.value.currentTime || 0)
    }
  }
}

const handleMetadataLoaded = (e) => {
  const player = e.target
  console.log('元数据加载:', player.duration, 'src:', player.src)
  
  for (let i = 0; i < videoSegments.value.length; i++) {
    const segment = videoSegments.value[i]
    const segmentFilename = segment.url.split('/').pop()
    const playerFilename = player.src.split('/').pop()
    
    if (segmentFilename === playerFilename || segment.url === player.src) {
      if ((segment.duration === undefined || segment.duration === 0 || isNaN(segment.duration)) && player.duration && !isNaN(player.duration)) {
        segment.duration = player.duration
        console.log('视频段', i, '时长已设置:', segment.duration)
        recalculateTotalDuration()
      }
      break
    }
  }
}

const handleCanPlayThrough = (playerIndex) => {
  console.log(`播放器 ${playerIndex} 视频准备就绪 (canplaythrough)`)
  videoReadyState[playerIndex] = true
}

let checkInterval = null
let lastEndedTime = 0

const handleVideoEnded = (playerIndex) => {
  const now = Date.now()
  console.log('视频段播放结束，playerIndex:', playerIndex, 'currentSegmentIndex:', currentSegmentIndex.value, 'isWaitingForNewVideo:', isWaitingForNewVideo.value)
  
  if (now - lastEndedTime < 100) {
    console.log('超快速重复的ended事件，忽略')
    return
  }
  lastEndedTime = now
  
  if (checkInterval) {
    clearInterval(checkInterval)
    checkInterval = null
  }
  
  if (currentSegmentIndex.value < videoSegments.value.length - 1) {
    const nextPlayerIndex = 1 - currentPlayerIndex.value
    if (videoReadyState[nextPlayerIndex]) {
      switchToNextVideo()
    } else {
      console.log('等待下一个视频准备好...')
      isWaitingForNewVideo.value = true
      
      let checkCount = 0
      checkInterval = setInterval(() => {
        checkCount++
        if (videoReadyState[nextPlayerIndex]) {
          clearInterval(checkInterval)
          checkInterval = null
          isWaitingForNewVideo.value = false
          switchToNextVideo()
        } else if (checkCount > 300) {
          clearInterval(checkInterval)
          checkInterval = null
          isWaitingForNewVideo.value = false
          console.log('等待超时，保持当前状态等待新视频')
        }
      }, 100)
    }
  } else {
    console.log('所有视频段播放完毕，等待新视频...')
    isWaitingForNewVideo.value = true
  }
}

const handleVideoError = (playerIndex, e) => {
  console.error('视频播放错误:', playerIndex, e)
  if (currentSegmentIndex.value < videoSegments.value.length - 1) {
    playSegment(currentSegmentIndex.value + 1)
  }
}

const handleVolumeChange = () => {
  const player = currentVideoPlayer.value
  if (player?.value) {
    isMuted.value = player.value.muted
    if (!isMuted.value) {
      savedVolume.value = player.value.volume
    }
    const otherPlayer = nextVideoPlayer.value
    if (otherPlayer?.value) {
      otherPlayer.value.muted = isMuted.value
      otherPlayer.value.volume = savedVolume.value
    }
  }
}

const handleProgressClick = (event) => {
  if (!alwaysShowControls || totalDuration.value === 0) return
  
  const rect = event.currentTarget.getBoundingClientRect()
  const x = event.clientX - rect.left
  const percentage = Math.max(0, Math.min(1, x / rect.width))
  const targetTime = percentage * totalDuration.value
  
  console.log('跳转到时间:', targetTime)
  
  let targetIndex = 0
  let accumulatedTime = 0
  for (let i = 0; i < videoSegments.value.length; i++) {
    const segment = videoSegments.value[i]
    if (!segment.duration || isNaN(segment.duration)) continue
    
    if (targetTime < accumulatedTime + segment.duration) {
      targetIndex = i
      break
    }
    accumulatedTime += segment.duration
    targetIndex = i
  }
  
  if (targetIndex !== currentSegmentIndex.value) {
    currentPlayerIndex.value = 0
    playSegment(targetIndex)
  }
  
  setTimeout(() => {
    const player = currentVideoPlayer.value
    if (player?.value && videoSegments.value[targetIndex]) {
      const segment = videoSegments.value[targetIndex]
      const localTime = targetTime - segment.startOffset
      player.value.currentTime = Math.max(0, Math.min(localTime, segment.duration || 0))
    }
  }, 200)
}

const togglePlay = () => {
  const player = currentVideoPlayer.value
  if (!player?.value) return
  
  hasUserInteracted.value = true
  
  if (isPaused.value) {
    player.value.play().catch(() => {})
    isPaused.value = false
  } else {
    player.value.pause()
    isPaused.value = true
  }
}

const toggleMute = () => {
  const player = currentVideoPlayer.value
  if (!player?.value) return
  player.value.muted = !player.value.muted
}

const addVideoSegment = (path) => {
  console.log('addVideoSegment called:', path)
  
  const type = path.includes('idle') ? 'idle' : 'reply'
  const videoUrl = videoApi.getVideoUrl(path)
  
  const newSegment = {
    url: videoUrl,
    path,
    type,
    duration: undefined,
    startOffset: totalDuration.value
  }
  videoSegments.value.push(newSegment)
  
  if (tempVideo) {
    tempVideo.src = videoUrl
    tempVideo.onloadedmetadata = () => {
      if (newSegment.duration === undefined || isNaN(newSegment.duration)) {
        newSegment.duration = tempVideo.duration
        console.log('快速获取视频段', videoSegments.value.length - 1, '时长:', newSegment.duration)
        recalculateTotalDuration()
      }
    }
    tempVideo.load()
  }
  
  console.log('视频队列长度:', videoSegments.value.length, 'currentSegmentIndex:', currentSegmentIndex.value)
  
  if (currentSegmentIndex.value === -1 && videoSegments.value.length > 0) {
    console.log('开始播放视频')
    isWaitingForNewVideo.value = false
    playSegment(0)
    isPaused.value = false
  } else if (currentSegmentIndex.value + 1 === videoSegments.value.length - 1) {
    console.log('预加载新添加的视频')
    preloadVideoToPlayer(videoSegments.value.length - 1, 1 - currentPlayerIndex.value)
  }
  
  if (isWaitingForNewVideo.value || (isPaused.value && currentSegmentIndex.value >= 0)) {
    console.log('收到新视频，取消等待状态或恢复播放')
    isWaitingForNewVideo.value = false
    isPaused.value = false
    
    if (currentSegmentIndex.value >= 0) {
      const player = currentVideoPlayer.value
      if (player?.value) {
        if (currentSegmentIndex.value === videoSegments.value.length - 2) {
          console.log('播放到最后一个视频，有新视频，预加载并切换')
          preloadVideoToPlayer(videoSegments.value.length - 1, 1 - currentPlayerIndex.value)
          
          const checkReady = setInterval(() => {
            if (videoReadyState[1 - currentPlayerIndex.value]) {
              clearInterval(checkReady)
              switchToNextVideo()
            }
          }, 100)
          
          setTimeout(() => {
            clearInterval(checkReady)
            if (currentSegmentIndex.value < videoSegments.value.length - 1) {
              switchToNextVideo()
            }
          }, 1000)
        } else if (player.value.paused) {
          console.log('播放器已暂停，直接播放')
          player.value.play().catch(() => {})
        }
      }
    }
    
    if (currentSegmentIndex.value >= 0 && currentSegmentIndex.value < videoSegments.value.length - 1) {
      const nextPlayerIndex = 1 - currentPlayerIndex.value
      if (videoReadyState[nextPlayerIndex]) {
        console.log('新视频已准备好，立即切换')
        switchToNextVideo()
      }
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
      addVideoSegment(data.path)
    }
  }
}

const initializeModel = async () => {
  isInitializing.value = true
  try {
    await chatApi.initialize(settings)
    
    videoSegments.value = []
    currentSegmentIndex.value = -1
    totalDuration.value = 0
    currentGlobalTime.value = 0
    currentPlayerIndex.value = 0
    videoReadyState[0] = false
    videoReadyState[1] = false
    
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
  
  tempVideo = document.createElement('video')
  tempVideo.preload = 'metadata'
  
  connectWebSocket()
  
  const handleInteraction = () => {
    hasUserInteracted.value = true
    const player = currentVideoPlayer.value
    if (player?.value && player.value.paused && !isPaused.value) {
      player.value.play().catch(() => {})
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
.chat-app {
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  box-sizing: border-box;
}

.main-container {
  display: flex;
  gap: 20px;
  width: 100%;
  max-width: 1400px;
  height: calc(100vh - 40px);
}

.video-section {
  flex: 0 0 520px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.settings-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 20px;
  background: white;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  transition: all 0.3s ease;
}

.settings-toggle:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.15);
}

.toggle-icon {
  font-size: 20px;
}

.toggle-text {
  color: #495057;
  font-size: 14px;
  font-weight: 500;
}

.settings-enter-active,
.settings-leave-active {
  transition: all 0.3s ease;
  max-height: 500px;
  opacity: 1;
}

.settings-enter-from,
.settings-leave-to {
  max-height: 0;
  opacity: 0;
  overflow: hidden;
}

.video-wrapper {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  background: linear-gradient(180deg, #1a1a2e 0%, #16213e 100%);
  border-radius: 24px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.digital-human-video {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  z-index: 1;
  opacity: 0;
}

.digital-human-video.active {
  opacity: 1;
  z-index: 10;
}

.video-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  pointer-events: none;
}

.video-overlay.controls-visible {
  pointer-events: auto;
}

.status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: linear-gradient(180deg, rgba(0,0,0,0.6) 0%, transparent 100%);
}

.connection-status {
  display: flex;
  align-items: center;
  gap: 8px;
  color: white;
  font-size: 14px;
  font-weight: 500;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ff6b6b;
  animation: pulse 2s infinite;
}

.connection-status.connected .status-dot {
  background: #51cf66;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.toggle-controls-btn {
  background: rgba(255, 255, 255, 0.15);
  border: none;
  color: white;
  padding: 6px 12px;
  border-radius: 20px;
  font-size: 12px;
  cursor: pointer;
  backdrop-filter: blur(10px);
  transition: all 0.3s ease;
}

.toggle-controls-btn:hover {
  background: rgba(255, 255, 255, 0.25);
}

.custom-controls {
  padding: 16px 20px;
  background: linear-gradient(0deg, rgba(0,0,0,0.7) 0%, transparent 100%);
}

.progress-container {
  width: 100%;
  height: 4px;
  background: rgba(255,255,255,0.2);
  border-radius: 2px;
  cursor: pointer;
  margin-bottom: 12px;
}

.progress-bar {
  height: 100%;
  background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
  border-radius: 2px;
  transition: width 0.1s linear;
}

.controls-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.time-display {
  color: white;
  font-size: 13px;
  font-weight: 500;
  min-width: 100px;
}

.center-controls {
  display: flex;
  gap: 12px;
}

.control-btn {
  background: rgba(255, 255, 255, 0.15);
  border: none;
  color: white;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  font-size: 18px;
  cursor: pointer;
  backdrop-filter: blur(10px);
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  justify-content: center;
}

.control-btn:hover {
  background: rgba(255, 255, 255, 0.3);
  transform: scale(1.1);
}

.settings-panel {
  background: white;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.15);
}

.panel-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 16px 20px;
}

.panel-title {
  color: white;
  font-weight: 600;
  font-size: 15px;
}

.panel-content {
  padding: 20px;
}

.form-item {
  margin-bottom: 16px;
}

.form-item label {
  display: block;
  color: #495057;
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 8px;
}

.advanced-collapse {
  margin-bottom: 16px;
}

.button-group {
  display: flex;
  gap: 10px;
}

.chat-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: white;
  border-radius: 24px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.chat-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.avatar {
  flex-shrink: 0;
}

.avatar-circle {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  border: 3px solid rgba(255, 255, 255, 0.3);
}

.chat-info h2 {
  margin: 0;
  color: white;
  font-size: 20px;
  font-weight: 600;
}

.status-text {
  margin: 4px 0 0 0;
  color: rgba(255, 255, 255, 0.8);
  font-size: 13px;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  background: #f8f9fa;
}

.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track {
  background: transparent;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: #dee2e6;
  border-radius: 3px;
}

.message-item {
  margin-bottom: 24px;
}

.message-row {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  align-items: flex-end;
}

.message-row.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}

.message-row.user .message-avatar {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
}

.message-content {
  max-width: 70%;
}

.message-bubble {
  padding: 14px 18px;
  border-radius: 18px;
  font-size: 15px;
  line-height: 1.6;
  word-wrap: break-word;
}

.message-row.assistant .message-bubble {
  background: white;
  color: #212529;
  border-bottom-left-radius: 4px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.message-row.user .message-bubble {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-bottom-right-radius: 4px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #868e96;
}

.empty-icon {
  font-size: 64px;
  margin-bottom: 16px;
}

.empty-state p {
  margin: 0;
  font-size: 16px;
}

.chat-input-area {
  padding: 20px 24px;
  background: white;
  border-top: 1px solid #e9ecef;
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
}

.hint-text {
  color: #868e96;
  font-size: 12px;
}
</style>
