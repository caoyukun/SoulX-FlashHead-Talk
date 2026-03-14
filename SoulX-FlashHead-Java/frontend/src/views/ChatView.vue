<template>
  <div class="video-chat-app">
    <!-- 左侧/中间：1:1 视频区域 -->
    <div class="video-panel" :class="{ 'centered': !showChatPanel }">
      <div class="video-wrapper" @click="toggleControls">
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
        
        <!-- 视频控制覆盖层 -->
        <div class="video-overlay" :class="{ 'controls-visible': showOverlay }">
          <div class="top-bar">
            <div class="user-info">
              <div class="avatar">
                <img v-if="settings.condImage" :src="getAvatarUrl()" alt="avatar" />
                <span v-else>🤖</span>
              </div>
              <div class="user-details">
                <h3>雪梦婵</h3>
                <span class="status" :class="{ online: isConnected }">
                  <span class="status-dot"></span>
                  {{ isConnected ? '在线' : '连接中...' }}
                </span>
              </div>
            </div>
            <div class="top-actions">
              <button class="icon-btn" @click.stop="showSettings = !showSettings">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="3"></circle>
                  <path d="M12 1v6m0 6v6m4.22-10.22l4.24-4.24M6.34 6.34L2.1 2.1m17.8 17.8l-4.24-4.24M6.34 17.66l-4.24 4.24M23 12h-6m-6 0H1m20.07-4.93l-4.24 4.24M6.34 6.34l-4.24-4.24"></path>
                </svg>
              </button>
              <button class="icon-btn" @click.stop="showChatPanel = !showChatPanel" v-if="showChatPanel">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="18" y1="6" x2="6" y2="18"></line>
                  <line x1="6" y1="6" x2="18" y2="18"></line>
                </svg>
              </button>
            </div>
          </div>
          
          <!-- 底部控制栏 -->
          <div class="bottom-controls">
            <button class="control-btn" :class="{ active: isMuted }" @click.stop="toggleMute">
              <svg v-if="isMuted" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon>
                <line x1="23" y1="9" x2="17" y2="15"></line>
                <line x1="17" y1="9" x2="23" y2="15"></line>
              </svg>
              <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"></polygon>
                <path d="M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"></path>
              </svg>
            </button>
          </div>
        </div>
      </div>
      
      <!-- 底部浮动输入区（当对话面板收起时显示） -->
      <div v-if="!showChatPanel" class="floating-input-area">
        <button 
          class="voice-btn large" 
          :class="{ recording: isRecording }"
          @mousedown="startVoiceInput"
          @mouseup="stopVoiceInput"
          @touchstart="startVoiceInput"
          @touchend="stopVoiceInput"
        >
          <svg v-if="!isRecording" width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path>
            <path d="M19 10v2a7 7 0 0 1-14 0v-2"></path>
            <line x1="12" y1="19" x2="12" y2="23"></line>
            <line x1="8" y1="23" x2="16" y2="23"></line>
          </svg>
          <svg v-else width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="6" y="4" width="4" height="16"></rect>
            <rect x="14" y="4" width="4" height="16"></rect>
          </svg>
        </button>
        <span class="voice-hint">{{ isRecording ? `录音中 ${recordingTime}s` : '按住说话' }}</span>
      </div>
    </div>
    
    <!-- 右侧：对话内容区域 -->
    <transition name="slide">
      <div v-if="showChatPanel" class="chat-panel">
        <!-- 头部 -->
        <div class="chat-header">
          <h2>对话内容</h2>
          <button class="icon-btn" @click="showChatPanel = false">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
        
        <!-- 消息列表 -->
        <div class="chat-content" ref="chatMessages">
          <div v-for="(msg, index) in messages" :key="index" class="message-item">
            <div class="message-row user" v-if="msg.type === 'user'">
              <div class="message-avatar">👤</div>
              <div class="message-bubble user">{{ msg.text }}</div>
            </div>
            <div class="message-row assistant" v-if="msg.type === 'assistant'">
              <div class="message-avatar">🤖</div>
              <div class="message-bubble assistant">{{ msg.text }}</div>
            </div>
            <div class="message-row system" v-if="msg.type === 'system'">
              <div class="system-text">{{ msg.text }}</div>
            </div>
          </div>
          
          <div v-if="messages.length === 0" class="empty-state">
            <div class="empty-icon">💬</div>
            <p>开始和数字人对话吧</p>
          </div>
        </div>
        
        <!-- 底部输入区域 -->
        <div class="chat-input-area">
          <!-- 语音输入按钮 -->
          <button 
            class="voice-btn" 
            :class="{ recording: isRecording }"
            @mousedown="startVoiceInput"
            @mouseup="stopVoiceInput"
            @touchstart="startVoiceInput"
            @touchend="stopVoiceInput"
          >
            <svg v-if="!isRecording" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"></path>
              <path d="M19 10v2a7 7 0 0 1-14 0v-2"></path>
              <line x1="12" y1="19" x2="12" y2="23"></line>
              <line x1="8" y1="23" x2="16" y2="23"></line>
            </svg>
            <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="6" y="4" width="4" height="16"></rect>
              <rect x="14" y="4" width="4" height="16"></rect>
            </svg>
          </button>
          
          <!-- 文本输入 -->
          <div class="text-input-wrapper">
            <input
              v-model="inputMessage"
              type="text"
              placeholder="输入消息或按住麦克风说话..."
              @keyup.enter="sendMessage"
              :disabled="isSending || isRecording"
            />
            <button class="send-btn" @click="sendMessage" :disabled="!inputMessage.trim() || isSending">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="22" y1="2" x2="11" y2="13"></line>
                <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
              </svg>
            </button>
          </div>
        </div>
      </div>
    </transition>
    
    <!-- 展开对话按钮（当对话面板收起时显示） -->
    <button v-if="!showChatPanel" class="expand-chat-btn" @click="showChatPanel = true">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"></path>
      </svg>
      <span class="unread-badge" v-if="unreadCount > 0">{{ unreadCount }}</span>
    </button>
    
    <!-- 设置面板 -->
    <transition name="fade">
      <div v-if="showSettings" class="settings-modal" @click.self="showSettings = false">
        <div class="settings-content">
          <div class="settings-header">
            <h3>设置</h3>
            <button class="icon-btn" @click="showSettings = false">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
          </div>
          
          <div class="settings-body">
            <div class="form-group">
              <label>数字人照片</label>
              <el-input v-model="settings.condImage" placeholder="examples/girl.png" size="small" />
            </div>
            
            <el-collapse>
              <el-collapse-item title="高级设置">
                <div class="form-group">
                  <label>Checkpoint目录</label>
                  <el-input v-model="settings.ckptDir" placeholder="models/SoulX-FlashHead-1_3B" size="small" />
                </div>
                <div class="form-group">
                  <label>Wav2Vec目录</label>
                  <el-input v-model="settings.wav2vecDir" placeholder="models/wav2vec2-base-960h" size="small" />
                </div>
                <div class="form-group">
                  <label>模型类型</label>
                  <el-select v-model="settings.modelType" size="small" style="width: 100%">
                    <el-option label="Pro" value="pro" />
                    <el-option label="Lite" value="lite" />
                  </el-select>
                </div>
                <div class="form-group">
                  <label>随机种子</label>
                  <el-input-number v-model="settings.seed" :min="0" size="small" style="width: 100%" />
                </div>
              </el-collapse-item>
            </el-collapse>
            
            <div class="settings-actions">
              <el-button type="primary" @click="initializeModel" :loading="isInitializing" style="width: 100%">
                初始化模型
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick, onUnmounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useChatStore } from '../store/chat'
import { chatApi, videoApi } from '../api'

// 视频播放器
const videoPlayer1 = ref(null)
const videoPlayer2 = ref(null)
const chatMessages = ref(null)

// 状态
const inputMessage = ref('')
const isSending = ref(false)
const isInitializing = ref(false)
const isConnected = ref(false)
const isMuted = ref(false)
const isPaused = ref(false)
const savedVolume = ref(1)
const showOverlay = ref(true)
const showChatPanel = ref(true)
const showSettings = ref(false)
const unreadCount = ref(0)

// 消息列表（本地管理，立即显示）
const messages = ref([])

// 语音输入
const isRecording = ref(false)
const recordingTime = ref(0)
let recordingTimer = null
let mediaRecorder = null
let audioChunks = []

// 双播放器管理
const currentPlayerIndex = ref(0)
const videoPlayers = computed(() => [videoPlayer1, videoPlayer2])
const currentVideoPlayer = computed(() => videoPlayers.value[currentPlayerIndex.value])

const videoReadyState = reactive({ 0: false, 1: false })

// 视频段管理
const videoSegments = ref([])
let currentSegmentIndex = ref(-1)

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

// 获取头像URL - 直接使用相对路径，不走视频流API
const getAvatarUrl = () => {
  // 如果路径以 http 开头，直接使用
  if (settings.condImage.startsWith('http')) {
    return settings.condImage
  }
  // 否则使用相对路径，添加前缀避免缓存问题
  return `/${settings.condImage}?t=${Date.now()}`
}

// 滚动到底部
const scrollToBottom = () => {
  nextTick(() => {
    if (chatMessages.value) {
      chatMessages.value.scrollTop = chatMessages.value.scrollHeight
    }
  })
}

// 切换控制栏显示
const toggleControls = () => {
  showOverlay.value = !showOverlay.value
}

// 添加消息到本地列表
const addMessage = (type, text) => {
  messages.value.push({
    type,
    text,
    time: new Date().toLocaleTimeString()
  })
  scrollToBottom()
  
  // 如果对话面板收起，增加未读计数
  if (!showChatPanel.value && type === 'assistant') {
    unreadCount.value++
  }
}

// 语音输入
const startVoiceInput = async () => {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    mediaRecorder = new MediaRecorder(stream)
    audioChunks = []
    
    mediaRecorder.ondataavailable = (event) => {
      audioChunks.push(event.data)
    }
    
    mediaRecorder.onstop = async () => {
      const audioBlob = new Blob(audioChunks, { type: 'audio/wav' })
      await processVoiceInput(audioBlob)
    }
    
    mediaRecorder.start()
    isRecording.value = true
    recordingTime.value = 0
    recordingTimer = setInterval(() => {
      recordingTime.value++
    }, 1000)
  } catch (error) {
    console.error('无法访问麦克风:', error)
    ElMessage.error('无法访问麦克风，请检查权限设置')
  }
}

const stopVoiceInput = () => {
  if (mediaRecorder && isRecording.value) {
    mediaRecorder.stop()
    mediaRecorder.stream.getTracks().forEach(track => track.stop())
    isRecording.value = false
    clearInterval(recordingTimer)
  }
}

const processVoiceInput = async (audioBlob) => {
  try {
    // 这里可以调用语音识别API
    ElMessage.info('语音输入功能需要接入语音识别服务')
  } catch (error) {
    console.error('语音识别失败:', error)
  }
}

// 视频播放相关函数
const preloadVideoToPlayer = (index, playerIndex) => {
  if (index < 0 || index >= videoSegments.value.length) return
  
  const segment = videoSegments.value[index]
  const player = videoPlayers.value[playerIndex]
  
  if (player?.value && segment?.url) {
    player.value.src = segment.url
    player.value.muted = true
    player.value.volume = savedVolume.value
    player.value.load()
    videoReadyState[playerIndex] = false
  }
}

const switchToNextVideo = () => {
  const nextIndex = currentSegmentIndex.value + 1
  if (nextIndex >= videoSegments.value.length) {
    isPaused.value = true
    return
  }
  
  const nextPlayerIndex = 1 - currentPlayerIndex.value
  const prevPlayerIndex = 1 - nextPlayerIndex
  
  if (!videoReadyState[nextPlayerIndex]) return
  
  const nextPlayer = videoPlayers.value[nextPlayerIndex]
  const prevPlayer = videoPlayers.value[prevPlayerIndex]
  
  if (nextPlayer?.value) {
    nextPlayer.value.currentTime = 0
    nextPlayer.value.muted = isMuted.value
    nextPlayer.value.volume = savedVolume.value
    nextPlayer.value.play().catch(() => {})
  }
  
  currentSegmentIndex.value = nextIndex
  currentPlayerIndex.value = nextPlayerIndex
  isWaitingForNewVideo.value = false
  
  setTimeout(() => {
    if (prevPlayer?.value) {
      prevPlayer.value.pause()
      prevPlayer.value.muted = true
    }
  }, 150)
  
  const preloadIndex = nextIndex + 1
  if (preloadIndex < videoSegments.value.length) {
    preloadVideoToPlayer(preloadIndex, 1 - nextPlayerIndex)
  }
}

const playSegment = (index) => {
  if (index < 0 || index >= videoSegments.value.length) return
  
  const segment = videoSegments.value[index]
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
    player.value.load()
    player.value.play().catch(() => { isPaused.value = true })
  }
}

const handleTimeUpdate = () => {}

const handleMetadataLoaded = (e) => {
  const player = e.target
  for (let i = 0; i < videoSegments.value.length; i++) {
    const segment = videoSegments.value[i]
    const segmentFilename = segment.url.split('/').pop()
    const playerFilename = player.src.split('/').pop()
    
    if (segmentFilename === playerFilename) {
      if (!segment.duration && player.duration) {
        segment.duration = player.duration
      }
      break
    }
  }
}

const handleCanPlayThrough = (playerIndex) => {
  videoReadyState[playerIndex] = true
}

let checkInterval = null
let lastEndedTime = 0

const handleVideoEnded = (playerIndex) => {
  const now = Date.now()
  if (now - lastEndedTime < 100) return
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
        }
      }, 100)
    }
  } else {
    isWaitingForNewVideo.value = true
  }
}

const handleVideoError = (playerIndex, e) => {
  if (currentSegmentIndex.value < videoSegments.value.length - 1) {
    playSegment(currentSegmentIndex.value + 1)
  }
}

const handleVolumeChange = () => {
  const player = currentVideoPlayer.value
  if (player?.value) {
    isMuted.value = player.value.muted
    if (!isMuted.value) savedVolume.value = player.value.volume
    const otherPlayer = videoPlayers.value[1 - currentPlayerIndex.value]
    if (otherPlayer?.value) {
      otherPlayer.value.muted = isMuted.value
      otherPlayer.value.volume = savedVolume.value
    }
  }
}

const toggleMute = () => {
  const player = currentVideoPlayer.value
  if (player?.value) {
    player.value.muted = !player.value.muted
  }
}

const addVideoSegment = (path) => {
  const videoUrl = videoApi.getVideoUrl(path)
  
  const newSegment = {
    url: videoUrl,
    path,
    duration: undefined
  }
  videoSegments.value.push(newSegment)
  
  if (tempVideo) {
    tempVideo.src = videoUrl
    tempVideo.onloadedmetadata = () => {
      if (!newSegment.duration && tempVideo.duration) {
        newSegment.duration = tempVideo.duration
      }
    }
    tempVideo.load()
  }
  
  if (currentSegmentIndex.value === -1 && videoSegments.value.length > 0) {
    isWaitingForNewVideo.value = false
    playSegment(0)
    isPaused.value = false
  } else if (currentSegmentIndex.value + 1 === videoSegments.value.length - 1) {
    preloadVideoToPlayer(videoSegments.value.length - 1, 1 - currentPlayerIndex.value)
  }
  
  if (isWaitingForNewVideo.value || (isPaused.value && currentSegmentIndex.value >= 0)) {
    isWaitingForNewVideo.value = false
    isPaused.value = false
    
    if (currentSegmentIndex.value >= 0) {
      const player = currentVideoPlayer.value
      if (player?.value) {
        if (currentSegmentIndex.value === videoSegments.value.length - 2) {
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
          player.value.play().catch(() => {})
        }
      }
    }
  }
}

const connectWebSocket = () => {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsUrl = `${protocol}//${window.location.host}/ws`
  
  ws = new WebSocket(wsUrl)
  
  ws.onopen = () => {
    isConnected.value = true
    chatStore.setConnected(true)
  }
  
  ws.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data)
      handleWebSocketMessage(data)
    } catch (e) {
      console.error('解析 WebSocket 消息失败:', e)
    }
  }
  
  ws.onerror = () => {
    isConnected.value = false
    chatStore.setConnected(false)
  }
  
  ws.onclose = () => {
    isConnected.value = false
    chatStore.setConnected(false)
  }
}

const handleWebSocketMessage = (data) => {
  if (data.type === 'chat') {
    // 添加用户消息
    addMessage('user', data.data.user)
    // 添加助手回复
    addMessage('assistant', data.data.assistant)
    // 同步到 store
    chatStore.addMessage(data.data.user, data.data.assistant)
  } else if (data.type === 'error') {
    ElMessage.error(data.message)
    addMessage('system', '错误: ' + data.message)
  } else if (data.type === 'video_segment') {
    if (data.path) addVideoSegment(data.path)
  }
}

const initializeModel = async () => {
  isInitializing.value = true
  try {
    await chatApi.initialize(settings)
    videoSegments.value = []
    currentSegmentIndex.value = -1
    currentPlayerIndex.value = 0
    videoReadyState[0] = false
    videoReadyState[1] = false
    ElMessage.success('模型初始化成功')
    showSettings.value = false
  } catch (error) {
    ElMessage.error('初始化失败: ' + (error.response?.data?.message || error.message))
  } finally {
    isInitializing.value = false
  }
}

const sendMessage = async () => {
  if (!inputMessage.value.trim()) return
  
  hasUserInteracted.value = true
  
  // 立即显示用户消息
  const userMessage = inputMessage.value
  addMessage('user', userMessage)
  
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    connectWebSocket()
    return
  }
  
  isSending.value = true
  inputMessage.value = ''
  
  try {
    const response = await chatApi.sendMessage({
      message: userMessage,
      condImage: settings.condImage,
      ckptDir: settings.ckptDir,
      wav2vecDir: settings.wav2vecDir,
      modelType: settings.modelType,
      seed: settings.seed,
      useFaceCrop: settings.useFaceCrop
    })
    chatStore.setSessionId(response.data.sessionId)
  } catch (error) {
    ElMessage.error('发送消息失败')
    addMessage('system', '发送失败，请重试')
  } finally {
    isSending.value = false
  }
}

// 监听对话面板展开，重置未读计数
watch(showChatPanel, (newVal) => {
  if (newVal) {
    unreadCount.value = 0
  }
})

onMounted(() => {
  tempVideo = document.createElement('video')
  tempVideo.preload = 'metadata'
  connectWebSocket()
})

onUnmounted(() => {
  if (ws) ws.close()
  if (recordingTimer) clearInterval(recordingTimer)
})
</script>

<style scoped>
.video-chat-app {
  width: 100%;
  height: 100vh;
  background: #0a0a0a;
  display: flex;
  overflow: hidden;
}

/* 视频面板 */
.video-panel {
  flex: 1;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: #000;
  transition: all 0.3s ease;
}

.video-panel.centered {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 1;
}

.video-wrapper {
  position: relative;
  width: calc(100vh - 40px);
  height: calc(100vh - 40px);
  max-width: min(calc(100vw - 420px), calc(100vh - 40px));
  max-height: min(calc(100vw - 420px), calc(100vh - 40px));
  background: #1a1a1a;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}

.video-panel.centered .video-wrapper {
  width: calc(100vh - 40px);
  height: calc(100vh - 40px);
  max-width: min(calc(100vw - 40px), calc(100vh - 40px));
  max-height: min(calc(100vw - 40px), calc(100vh - 40px));
}

.digital-human-video {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  z-index: 1;
}

.digital-human-video.active {
  opacity: 1;
  z-index: 2;
}

/* 视频控制覆盖层 */
.video-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 10;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 20px;
  background: linear-gradient(
    to bottom,
    rgba(0, 0, 0, 0.5) 0%,
    transparent 30%,
    transparent 70%,
    rgba(0, 0, 0, 0.5) 100%
  );
  opacity: 0;
  transition: opacity 0.3s ease;
}

.video-overlay.controls-visible {
  opacity: 1;
}

/* 顶部栏 */
.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  overflow: hidden;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  border: 2px solid rgba(255, 255, 255, 0.2);
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.user-details h3 {
  color: white;
  font-size: 15px;
  font-weight: 600;
  margin: 0;
}

.status {
  display: flex;
  align-items: center;
  gap: 5px;
  color: rgba(255, 255, 255, 0.7);
  font-size: 12px;
}

.status.online {
  color: #51cf66;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ff6b6b;
}

.status.online .status-dot {
  background: #51cf66;
  animation: pulse 2s infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

.top-actions {
  display: flex;
  gap: 8px;
}

/* 底部控制栏 */
.bottom-controls {
  display: flex;
  justify-content: center;
}

.control-btn {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.15);
  backdrop-filter: blur(10px);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s ease;
}

.control-btn:hover {
  background: rgba(255, 255, 255, 0.25);
}

.control-btn.active {
  background: rgba(255, 71, 87, 0.8);
}

/* 图标按钮 */
.icon-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.1);
  backdrop-filter: blur(10px);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s ease;
}

.icon-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

/* 浮动输入区（当对话面板收起时） */
.floating-input-area {
  position: absolute;
  bottom: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.voice-btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.1);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s ease;
}

.voice-btn:hover {
  background: rgba(255, 255, 255, 0.2);
}

.voice-btn.large {
  width: 72px;
  height: 72px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  box-shadow: 0 8px 24px rgba(102, 126, 234, 0.4);
}

.voice-btn.large:hover {
  transform: scale(1.05);
  box-shadow: 0 12px 32px rgba(102, 126, 234, 0.5);
}

.voice-btn.recording {
  background: #ff4757;
  animation: recording-pulse 1s infinite;
}

@keyframes recording-pulse {
  0%, 100% { transform: scale(1); box-shadow: 0 0 0 0 rgba(255, 71, 87, 0.7); }
  50% { transform: scale(1.05); box-shadow: 0 0 0 15px rgba(255, 71, 87, 0); }
}

.voice-hint {
  color: rgba(255, 255, 255, 0.6);
  font-size: 14px;
}

/* 展开对话按钮 */
.expand-chat-btn {
  position: fixed;
  right: 20px;
  bottom: 20px;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 16px rgba(102, 126, 234, 0.4);
  transition: all 0.3s ease;
  z-index: 50;
}

.expand-chat-btn:hover {
  transform: scale(1.1);
  box-shadow: 0 6px 24px rgba(102, 126, 234, 0.5);
}

.unread-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 10px;
  background: #ff4757;
  color: white;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 对话面板 */
.chat-panel {
  width: 380px;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #141414;
  border-left: 1px solid rgba(255, 255, 255, 0.1);
  flex-shrink: 0;
}

.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.chat-header h2 {
  color: white;
  font-size: 16px;
  font-weight: 600;
  margin: 0;
}

.chat-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.message-item {
  margin-bottom: 16px;
}

.message-row {
  display: flex;
  gap: 10px;
  margin-bottom: 8px;
  align-items: flex-start;
}

.message-row.user {
  flex-direction: row-reverse;
}

.message-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  flex-shrink: 0;
}

.message-row.user .message-avatar {
  background: linear-gradient(135deg, #f093fb 0%, #f5576c 100%);
}

.message-bubble {
  max-width: 75%;
  padding: 10px 14px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.5;
  word-wrap: break-word;
}

.message-bubble.assistant {
  background: rgba(255, 255, 255, 0.1);
  color: white;
  border-bottom-left-radius: 4px;
}

.message-bubble.user {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  border-bottom-right-radius: 4px;
}

.message-row.system {
  justify-content: center;
}

.system-text {
  color: rgba(255, 255, 255, 0.5);
  font-size: 12px;
  padding: 4px 12px;
  background: rgba(255, 255, 255, 0.05);
  border-radius: 12px;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: rgba(255, 255, 255, 0.4);
}

.empty-icon {
  font-size: 48px;
  margin-bottom: 12px;
}

.empty-state p {
  margin: 0;
  font-size: 14px;
}

/* 底部输入区域 */
.chat-input-area {
  padding: 16px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  display: flex;
  align-items: center;
  gap: 12px;
}

.text-input-wrapper {
  flex: 1;
  display: flex;
  align-items: center;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 20px;
  padding: 4px 4px 4px 16px;
}

.text-input-wrapper input {
  flex: 1;
  background: transparent;
  border: none;
  color: white;
  font-size: 14px;
  outline: none;
  padding: 8px 0;
}

.text-input-wrapper input::placeholder {
  color: rgba(255, 255, 255, 0.4);
}

.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s ease;
}

.send-btn:hover:not(:disabled) {
  transform: scale(1.05);
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 设置面板 */
.settings-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.8);
  backdrop-filter: blur(10px);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
}

.settings-content {
  width: 100%;
  max-width: 420px;
  background: #1a1a1a;
  border-radius: 20px;
  padding: 24px;
  max-height: 80vh;
  overflow-y: auto;
}

.settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.settings-header h3 {
  color: white;
  margin: 0;
  font-size: 18px;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
  margin-bottom: 6px;
}

.settings-actions {
  margin-top: 24px;
}

/* 动画 */
.slide-enter-active,
.slide-leave-active {
  transition: all 0.3s ease;
}

.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
  opacity: 0;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

/* 响应式 */
@media (max-width: 900px) {
  .chat-panel {
    position: fixed;
    top: 0;
    right: 0;
    bottom: 0;
    width: 100%;
    max-width: 400px;
    z-index: 40;
  }
  
  .video-wrapper {
    width: calc(100vh - 180px);
    height: calc(100vh - 180px);
    max-width: min(calc(100vw - 40px), calc(100vh - 180px));
    max-height: min(calc(100vw - 40px), calc(100vh - 180px));
  }
}
</style>
