<template>
  <div class="video-chat-app">
    <div class="video-panel" :class="{ 'centered': !showChatPanel }">
      <div class="video-wrapper" @click="toggleControls">
        <video
          ref="hlsVideoPlayer"
          class="digital-human-video"
          playsinline
          preload="auto"
          :loop="false"
          @timeupdate="handleHlsTimeUpdate"
          @loadedmetadata="handleHlsMetadataLoaded"
          @waiting="handleHlsWaiting"
          @playing="handleHlsPlaying"
          @error="handleHlsError"
          @volumechange="handleVolumeChange"
          @ended="handleHlsEnded"
        >
          您的浏览器不支持视频播放。
        </video>
        
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
          
          <div class="bottom-controls" ref="customControls">
            <div class="progress-container" @click.stop="handleProgressClick">
              <div class="progress-bar" :style="{ width: globalProgress + '%' }"></div>
            </div>
            
            <div class="time-display">
              <span>{{ formatTime(currentGlobalTime) }}</span>
              <span>/</span>
              <span>{{ totalDuration.value === 0 ? 'Live' : formatTime(totalDuration) }}</span>
            </div>
            
            <button class="control-btn" @click.stop="togglePlay">
              <svg v-if="!isPaused" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="6" y="4" width="4" height="16"></rect>
                <rect x="14" y="4" width="4" height="16"></rect>
              </svg>
              <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="5 3 19 12 5 21 5 3"></polygon>
              </svg>
            </button>
            
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
    
    <transition name="slide">
      <div v-if="showChatPanel" class="chat-panel">
        <div class="chat-header">
          <h2>对话内容</h2>
          <button class="icon-btn" @click="showChatPanel = false">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"></line>
              <line x1="6" y1="6" x2="18" y2="18"></line>
            </svg>
          </button>
        </div>
        
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
        
        <div class="chat-input-area">
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
    
    <button v-if="!showChatPanel" class="expand-chat-btn" @click="showChatPanel = true">
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"></path>
      </svg>
      <span class="unread-badge" v-if="unreadCount > 0">{{ unreadCount }}</span>
    </button>
    
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
              <el-button type="success" @click="downloadCompleteVideo" :disabled="!currentFinalVideoPath" style="width: 100%; margin-top: 10px;">
                📥 下载完整视频
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useChatStore } from '../store/chat'
import { chatApi } from '../api'
import Hls from 'hls.js'

const hlsVideoPlayer = ref(null)
const chatMessages = ref(null)
const customControls = ref(null)

let hls = null
const currentHlsUrl = ref(null)
const isHlsBuffering = ref(false)

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
const currentFinalVideoPath = ref(null)
const isDownloading = ref(false)

const totalDuration = ref(0)
const currentGlobalTime = ref(0)
const globalProgress = computed(() => {
  if (totalDuration.value === 0) return 0
  return Math.min(100, (currentGlobalTime.value / totalDuration.value) * 100)
})

const messages = ref([])

const isRecording = ref(false)
const recordingTime = ref(0)
let recordingTimer = null
let mediaRecorder = null
let audioChunks = []

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

const avatarUrlCache = computed(() => {
  if (settings.condImage.startsWith('http')) {
    return settings.condImage
  }
  return `/${settings.condImage}`
})

const getAvatarUrl = () => {
  return avatarUrlCache.value
}

const formatTime = (seconds) => {
  if (!seconds || isNaN(seconds)) return '00:00'
  const mins = Math.floor(seconds / 60)
  const secs = Math.floor(seconds % 60)
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatMessages.value) {
      chatMessages.value.scrollTop = chatMessages.value.scrollHeight
    }
  })
}

const toggleControls = () => {
  showOverlay.value = !showOverlay.value
}

const addMessage = (type, text) => {
  messages.value.push({
    type,
    text,
    time: new Date().toLocaleTimeString()
  })
  scrollToBottom()
  
  if (!showChatPanel.value && type === 'assistant') {
    unreadCount.value++
  }
}

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
    ElMessage.info('语音输入功能需要接入语音识别服务')
  } catch (error) {
    console.error('语音识别失败:', error)
  }
}

const togglePlay = () => {
  hasUserInteracted.value = true

  if (hlsVideoPlayer.value) {
    if (isPaused.value) {
      hlsVideoPlayer.value.play().catch(() => {})
      isPaused.value = false
    } else {
      hlsVideoPlayer.value.pause()
      isPaused.value = true
    }
  }
}

const downloadCompleteVideo = async () => {
  if (!currentFinalVideoPath.value) {
    ElMessage.warning('暂无可下载的完整视频')
    return
  }
  
  isDownloading.value = true
  try {
    const link = document.createElement('a')
    link.href = '/api/video/download-complete'
    link.download = 'SoulX-FlashHead_' + new Date().toISOString().slice(0, 19).replace(/:/g, '-') + '.mp4'
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    ElMessage.success('完整视频下载已开始')
  } catch (error) {
    console.error('下载失败:', error)
    ElMessage.error('下载失败: ' + (error.message || error))
  } finally {
    isDownloading.value = false
  }
}

const handleVolumeChange = () => {
  if (hlsVideoPlayer.value) {
    isMuted.value = hlsVideoPlayer.value.muted
    if (!isMuted.value) savedVolume.value = hlsVideoPlayer.value.volume
  }
}

const toggleMute = () => {
  if (hlsVideoPlayer.value) {
    hlsVideoPlayer.value.muted = !hlsVideoPlayer.value.muted
  }
}

const handleProgressClick = (event) => {
  if (!customControls.value || totalDuration.value === 0 || totalDuration.value === Infinity) return
  
  const rect = customControls.value.getBoundingClientRect()
  const x = event.clientX - rect.left
  const percentage = Math.max(0, Math.min(1, x / rect.width))
  const targetTime = percentage * totalDuration.value
  
  if (hlsVideoPlayer.value) {
    hlsVideoPlayer.value.currentTime = targetTime
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
    addMessage('assistant', data.data.assistant)
    chatStore.addMessage(data.data.user, data.data.assistant)
  } else if (data.type === 'error') {
    ElMessage.error(data.message)
    addMessage('system', '错误: ' + data.message)
  } else if (data.type === 'hls_stream') {
    if (data.hls_url) {
      initHlsPlayer(data.hls_url)
    }
  }
}

const initHlsPlayer = async (hlsUrl) => {
  if (!hlsUrl) return

  await nextTick()

  if (!hlsVideoPlayer.value) {
    console.error('HLS video player element not found')
    return
  }

  currentHlsUrl.value = hlsUrl

  if (Hls.isSupported()) {
    if (hls) {
      hls.destroy()
      hls = null
    }

    hlsVideoPlayer.value.pause()
    hlsVideoPlayer.value.removeAttribute('src')
    hlsVideoPlayer.value.load()

    hls = new Hls({
      enableWorker: true,
      lowLatencyMode: true,
      backBufferLength: 30,
      maxBufferLength: 10,
      maxMaxBufferLength: 20,
      liveSyncDurationCount: 2,
      liveMaxLatencyDurationCount: 5,
      fragLoadingTimeOut: 20000,
      manifestLoadingTimeOut: 10000,
      levelLoadingTimeOut: 10000,
      liveDurationInfinity: false,
      debug: false
    })

    hls.on(Hls.Events.MEDIA_ATTACHED, () => {
      console.log('HLS: Media attached')
    })

    hls.on(Hls.Events.MANIFEST_PARSED, (event, data) => {
      console.log('HLS: Manifest parsed, levels:', data.levels.length)
      if (hlsVideoPlayer.value) {
        hlsVideoPlayer.value.play().catch(e => {
          console.log('HLS autoplay prevented:', e)
          isPaused.value = true
        })
      }
    })

    hls.on(Hls.Events.ERROR, (event, data) => {
      console.error('HLS Error:', data)
      if (data.fatal) {
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            console.log('HLS: Fatal network error, trying to recover')
            hls.startLoad()
            break
          case Hls.ErrorTypes.MEDIA_ERROR:
            console.log('HLS: Fatal media error, trying to recover')
            hls.recoverMediaError()
            break
          default:
            console.log('HLS: Fatal error, destroying')
            destroyHlsPlayer()
            ElMessage.error('HLS 播放失败')
            break
        }
      }
    })

    hls.on(Hls.Events.BUFFER_APPENDING, () => {
      isHlsBuffering.value = true
    })

    hls.on(Hls.Events.BUFFER_APPENDED, () => {
      isHlsBuffering.value = false
    })

    hls.on(Hls.Events.LEVEL_UPDATED, (event, data) => {
      if (data.details && data.details.endList) {
        console.log('HLS: End list detected')
      }
    })

    hls.on(Hls.Events.MANIFEST_LOADED, (event, data) => {
      console.log('HLS: Manifest loaded, endList:', data.endList)
    })

    hls.attachMedia(hlsVideoPlayer.value)
    hls.loadSource(hlsUrl)

  } else if (hlsVideoPlayer.value.canPlayType('application/vnd.apple.mpegurl')) {
    console.log('Using native HLS support')
    hlsVideoPlayer.value.src = hlsUrl
    hlsVideoPlayer.value.addEventListener('loadedmetadata', () => {
      hlsVideoPlayer.value.play().catch(e => {
        console.log('Native HLS autoplay prevented:', e)
        isPaused.value = true
      })
    })
  } else {
    console.error('HLS not supported')
    ElMessage.error('您的浏览器不支持 HLS 播放')
  }
}

const destroyHlsPlayer = () => {
  if (hls) {
    try {
      hls.destroy()
    } catch (e) {
      console.log('HLS destroy error:', e)
    }
    hls = null
  }
  if (hlsVideoPlayer.value) {
    try {
      hlsVideoPlayer.value.pause()
      hlsVideoPlayer.value.removeAttribute('src')
      hlsVideoPlayer.value.load()
    } catch (e) {
      console.log('Video element cleanup error:', e)
    }
  }
  currentHlsUrl.value = null
}

const playIdleVideo = () => {
  console.log('请求生成空闲视频')
  chatApi.generateIdleVideo({ duration: 3.0 }).then(() => {
    console.log('空闲视频生成请求已发送')
  }).catch(err => {
    console.error('请求生成空闲视频失败:', err)
  })
}

const handleHlsTimeUpdate = () => {
  if (hlsVideoPlayer.value) {
    currentGlobalTime.value = hlsVideoPlayer.value.currentTime
  }
}

const handleHlsMetadataLoaded = () => {
  if (hlsVideoPlayer.value) {
    const duration = hlsVideoPlayer.value.duration
    if (duration && duration !== Infinity && !isNaN(duration)) {
      totalDuration.value = duration
    } else {
      totalDuration.value = 0
    }
  }
}

const handleHlsWaiting = () => {
  isHlsBuffering.value = true
}

const handleHlsPlaying = () => {
  isHlsBuffering.value = false
  isPaused.value = false
}

const handleHlsError = (e) => {
  const video = e.target
  if (video && video.error) {
    const errorCode = video.error.code
    const errorMessage = video.error.message
    console.error('HLS Video Error - Code:', errorCode, 'Message:', errorMessage)

    if (errorCode === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED ||
        errorCode === MediaError.MEDIA_ERR_NETWORK) {
      ElMessage.error('HLS 播放出错')
    }
  }
}

const handleHlsEnded = () => {
  console.log('HLS: Playback ended')
  destroyHlsPlayer()
  playIdleVideo()
}

const initializeModel = async () => {
  isInitializing.value = true
  try {
    await chatApi.initialize(settings)
    ElMessage.success('模型初始化成功')
  } catch (error) {
    console.error('初始化失败:', error)
    ElMessage.error('初始化失败: ' + (error.response?.data?.message || error.message))
  } finally {
    isInitializing.value = false
  }
}

const sendMessage = async () => {
  if (!inputMessage.value.trim() || isSending.value) return

  const message = inputMessage.value.trim()
  inputMessage.value = ''

  addMessage('user', message)

  isSending.value = true
  try {
    await chatApi.sendMessage({ message })
  } catch (error) {
    console.error('发送消息失败:', error)
    ElMessage.error('发送消息失败: ' + (error.response?.data?.message || error.message))
    addMessage('system', '发送失败: ' + (error.response?.data?.message || error.message))
  } finally {
    isSending.value = false
  }
}

onMounted(() => {
  connectWebSocket()
})

onUnmounted(() => {
  if (ws) {
    ws.close()
  }
  destroyHlsPlayer()
})
</script>

<style scoped>
.video-chat-app {
  display: flex;
  height: 100vh;
  background: #1a1a2e;
  color: white;
  overflow: hidden;
}

.video-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
}

.video-panel.centered {
  flex: 1;
}

.video-wrapper {
  position: relative;
  width: 100%;
  max-width: 600px;
  aspect-ratio: 1/1;
  background: #0f0f23;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.5);
}

.digital-human-video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.video-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(to bottom, rgba(0,0,0,0.4) 0%, transparent 20%, transparent 80%, rgba(0,0,0,0.4) 100%);
  opacity: 0;
  transition: opacity 0.3s;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 20px;
}

.video-overlay.controls-visible {
  opacity: 1;
}

.video-wrapper:hover .video-overlay {
  opacity: 1;
}

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
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  overflow: hidden;
}

.avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.user-details h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: rgba(255,255,255,0.7);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #666;
}

.status.online .status-dot {
  background: #4ade80;
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

.icon-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  border: none;
  background: rgba(255,255,255,0.15);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.icon-btn:hover {
  background: rgba(255,255,255,0.25);
}

.bottom-controls {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.progress-container {
  width: 100%;
  height: 4px;
  background: rgba(255,255,255,0.2);
  border-radius: 2px;
  cursor: pointer;
}

.progress-bar {
  height: 100%;
  background: linear-gradient(90deg, #667eea 0%, #764ba2 100%);
  border-radius: 2px;
  transition: width 0.1s;
}

.time-display {
  display: flex;
  justify-content: center;
  gap: 4px;
  font-size: 12px;
  color: rgba(255,255,255,0.8);
}

.control-btn {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: none;
  background: rgba(255,255,255,0.2);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s, transform 0.1s;
}

.control-btn:hover {
  background: rgba(255,255,255,0.3);
}

.control-btn:active {
  transform: scale(0.95);
}

.control-btn.active {
  background: rgba(239, 68, 68, 0.5);
}

.bottom-controls {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
}

.floating-input-area {
  position: absolute;
  bottom: 40px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.voice-btn {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.2s, box-shadow 0.2s;
  box-shadow: 0 8px 24px rgba(102, 126, 234, 0.4);
}

.voice-btn.large {
  width: 80px;
  height: 80px;
}

.voice-btn:hover {
  transform: scale(1.05);
  box-shadow: 0 12px 32px rgba(102, 126, 234, 0.5);
}

.voice-btn.recording {
  background: linear-gradient(135deg, #ef4444 0%, #dc2626 100%);
  animation: record-pulse 1s infinite;
}

@keyframes record-pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.1); }
}

.voice-hint {
  font-size: 14px;
  color: rgba(255,255,255,0.7);
}

.chat-panel {
  width: 400px;
  background: #16213e;
  display: flex;
  flex-direction: column;
  border-left: 1px solid rgba(255,255,255,0.1);
}

.chat-header {
  padding: 20px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.chat-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.chat-content {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message-item {
  width: 100%;
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  max-width: 85%;
}

.message-row.user {
  margin-left: auto;
  flex-direction: row-reverse;
}

.message-avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: rgba(255,255,255,0.1);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex-shrink: 0;
}

.message-bubble {
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.5;
}

.message-bubble.user {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-bottom-right-radius: 4px;
}

.message-bubble.assistant {
  background: rgba(255,255,255,0.1);
  border-bottom-left-radius: 4px;
}

.system-text {
  text-align: center;
  color: rgba(255,255,255,0.5);
  font-size: 12px;
}

.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: rgba(255,255,255,0.5);
  gap: 12px;
}

.empty-icon {
  font-size: 48px;
}

.chat-input-area {
  padding: 20px;
  border-top: 1px solid rgba(255,255,255,0.1);
  display: flex;
  gap: 12px;
  align-items: center;
}

.text-input-wrapper {
  flex: 1;
  display: flex;
  gap: 8px;
  background: rgba(255,255,255,0.08);
  border-radius: 24px;
  padding: 4px 4px 4px 16px;
  align-items: center;
}

.text-input-wrapper input {
  flex: 1;
  background: transparent;
  border: none;
  color: white;
  font-size: 14px;
  outline: none;
}

.text-input-wrapper input::placeholder {
  color: rgba(255,255,255,0.4);
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
  transition: opacity 0.2s;
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.expand-chat-btn {
  position: absolute;
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
  box-shadow: 0 8px 24px rgba(102, 126, 234, 0.4);
  transition: transform 0.2s;
}

.expand-chat-btn:hover {
  transform: scale(1.1);
}

.unread-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  background: #ef4444;
  color: white;
  font-size: 12px;
  font-weight: 600;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.settings-modal {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0,0,0,0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}

.settings-content {
  width: 400px;
  max-height: 80vh;
  background: #16213e;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0,0,0,0.5);
}

.settings-header {
  padding: 20px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.settings-header h3 {
  margin: 0;
  font-size: 18px;
}

.settings-body {
  padding: 20px;
  overflow-y: auto;
}

.form-group {
  margin-bottom: 16px;
}

.form-group label {
  display: block;
  margin-bottom: 8px;
  font-size: 14px;
  color: rgba(255,255,255,0.8);
}

.settings-actions {
  margin-top: 20px;
}

.slide-enter-active,
.slide-leave-active {
  transition: transform 0.3s ease;
}

.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.3s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
