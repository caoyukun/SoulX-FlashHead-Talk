<template>
  <div class="video-chat-app">
    <!-- 左侧/中间：1:1 视频区域 -->
    <div class="video-panel" :class="{ 'centered': !showChatPanel }">
      <div class="video-wrapper" @click="toggleControls">
        <!-- HLS 播放器 -->
        <video
          ref="hlsVideoPlayer"
          class="digital-human-video hls-player"
          :class="{ active: useHls }"
          playsinline
          preload="auto"
          :loop="false"
          @timeupdate="handleHlsTimeUpdate"
          @loadedmetadata="handleHlsMetadataLoaded"
          @waiting="handleHlsWaiting"
          @playing="handleHlsPlaying"
          @error="handleHlsError"
          @volumechange="handleVolumeChange"
        >
          您的浏览器不支持视频播放。
        </video>

        <!-- 原有分段 MP4 播放器（作为 fallback） -->
        <video
          ref="videoPlayer1"
          class="digital-human-video"
          :class="{ active: !useHls && currentPlayerIndex === 0 }"
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
          :class="{ active: !useHls && currentPlayerIndex === 1 }"
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
          <div class="bottom-controls" ref="customControls">
            <!-- 进度条 -->
            <div class="progress-container" @click.stop="handleProgressClick">
              <div class="progress-bar" :style="{ width: globalProgress + '%' }"></div>
            </div>
            
            <!-- 时间显示 -->
            <div class="time-display">
              <span>{{ formatTime(currentGlobalTime) }}</span>
              <span>/</span>
              <span>{{ formatTime(totalDuration) }}</span>
            </div>
            
            <!-- 播放/暂停按钮 -->
            <button class="control-btn" @click.stop="togglePlay">
              <svg v-if="!isPaused" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="6" y="4" width="4" height="16"></rect>
                <rect x="14" y="4" width="4" height="16"></rect>
              </svg>
              <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="5 3 19 12 5 21 5 3"></polygon>
              </svg>
            </button>
            
            <!-- 音量控制 -->
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
import { ref, reactive, computed, onMounted, nextTick, onUnmounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useChatStore } from '../store/chat'
import { chatApi, videoApi } from '../api'
import { hlsApi } from '../api/hls'
import Hls from 'hls.js'

// 视频播放器
const hlsVideoPlayer = ref(null)
const videoPlayer1 = ref(null)
const videoPlayer2 = ref(null)
const chatMessages = ref(null)
const customControls = ref(null)

// HLS 相关
let hls = null
const useHls = ref(false)
const currentHlsUrl = ref(null)
const isHlsBuffering = ref(false)

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
const currentFinalVideoPath = ref(null)
const isDownloading = ref(false)

// 进度条相关状态
const totalDuration = ref(0)
const currentGlobalTime = ref(0)
const globalProgress = computed(() => {
  if (totalDuration.value === 0) return 0
  return Math.min(100, (currentGlobalTime.value / totalDuration.value) * 100)
})

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

// 格式化时间显示
const formatTime = (seconds) => {
  if (!seconds || isNaN(seconds)) return '00:00'
  const mins = Math.floor(seconds / 60)
  const secs = Math.floor(seconds % 60)
  return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`
}

// 重新计算总时长和每个视频段的起始偏移
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
  
  // 先播放下一个视频（静音）
  if (nextPlayer?.value) {
    nextPlayer.value.currentTime = 0
    nextPlayer.value.muted = isMuted.value
    nextPlayer.value.volume = savedVolume.value
    nextPlayer.value.play().catch(() => {})
  }
  
  // 立即更新索引
  currentSegmentIndex.value = nextIndex
  currentPlayerIndex.value = nextPlayerIndex
  isWaitingForNewVideo.value = false
  
  // 立即暂停上一个视频
  if (prevPlayer?.value) {
    prevPlayer.value.pause()
    prevPlayer.value.muted = true
    prevPlayer.value.currentTime = 0
  }
  
  // 预加载下下个视频
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
  
  // 先暂停所有播放器
  for (let i = 0; i < videoPlayers.value.length; i++) {
    const p = videoPlayers.value[i]
    if (p?.value) {
      p.value.pause()
      p.value.muted = true
      p.value.currentTime = 0
    }
  }
  
  // 预加载下一个视频
  if (index + 1 < videoSegments.value.length) {
    preloadVideoToPlayer(index + 1, 1 - currentPlayerIndex.value)
  }
  
  // 加载并播放当前视频
  const player = currentVideoPlayer.value
  if (player?.value) {
    player.value.src = segment.url
    player.value.muted = isMuted.value
    player.value.volume = savedVolume.value
    player.value.load()
    
    // 等待 canplay 事件再播放，确保更流畅
    const playWhenReady = () => {
      if (hasUserInteracted.value || !isPaused.value) {
        player.value.play().catch(() => {
          isPaused.value = true
        })
      }
      player.value.removeEventListener('canplay', playWhenReady)
    }
    player.value.addEventListener('canplay', playWhenReady)
  }
}

// 处理时间更新，计算全局进度
const handleTimeUpdate = () => {
  if (currentSegmentIndex.value >= 0) {
    const player = currentVideoPlayer.value
    const segment = videoSegments.value[currentSegmentIndex.value]
    if (player?.value && segment && segment.startOffset !== undefined) {
      currentGlobalTime.value = segment.startOffset + (player.value.currentTime || 0)
    }
  }
}

// 处理元数据加载，记录视频时长
const handleMetadataLoaded = (e) => {
  const player = e.target
  console.log('元数据加载:', player.duration, 'src:', player.src)
  
  // 找到对应加载的视频段（使用更宽松的匹配）
  for (let i = 0; i < videoSegments.value.length; i++) {
    const segment = videoSegments.value[i]
    // 简单的路径匹配，避免完整URL比较问题
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

// 处理进度条点击，跳转到指定位置
const handleProgressClick = (event) => {
  if (!customControls.value || totalDuration.value === 0) return
  
  const rect = customControls.value.getBoundingClientRect()
  const x = event.clientX - rect.left
  const percentage = Math.max(0, Math.min(1, x / rect.width))
  const targetTime = percentage * totalDuration.value
  
  console.log('跳转到时间:', targetTime)
  
  // 找到对应的视频段
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
  
  // 切换到目标视频段并设置播放位置
  if (targetIndex !== currentSegmentIndex.value) {
    currentPlayerIndex.value = 0
    playSegment(targetIndex)
  }
  
  // 等待视频加载后设置播放位置
  setTimeout(() => {
    const player = currentVideoPlayer.value
    if (player?.value && videoSegments.value[targetIndex]) {
      const segment = videoSegments.value[targetIndex]
      const localTime = targetTime - segment.startOffset
      player.value.currentTime = Math.max(0, Math.min(localTime, segment.duration || 0))
    }
  }, 200)
}

// 切换播放/暂停
const togglePlay = () => {
  hasUserInteracted.value = true

  if (useHls.value && hlsVideoPlayer.value) {
    // HLS 播放器
    if (isPaused.value) {
      hlsVideoPlayer.value.play().catch(() => {})
      isPaused.value = false
    } else {
      hlsVideoPlayer.value.pause()
      isPaused.value = true
    }
  } else {
    // 原有播放器
    const player = currentVideoPlayer.value
    if (!player?.value) return

    if (isPaused.value) {
      player.value.play().catch(() => {})
      isPaused.value = false
    } else {
      player.value.pause()
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
  if (useHls.value && hlsVideoPlayer.value) {
    isMuted.value = hlsVideoPlayer.value.muted
    if (!isMuted.value) savedVolume.value = hlsVideoPlayer.value.volume
  } else {
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
}

const toggleMute = () => {
  if (useHls.value && hlsVideoPlayer.value) {
    hlsVideoPlayer.value.muted = !hlsVideoPlayer.value.muted
  } else {
    const player = currentVideoPlayer.value
    if (player?.value) {
      player.value.muted = !player.value.muted
    }
  }
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
  
  // 使用临时video快速获取时长
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
  
  // 第一个视频，开始播放
  if (currentSegmentIndex.value === -1 && videoSegments.value.length > 0) {
    isWaitingForNewVideo.value = false
    playSegment(0)
    isPaused.value = false
  }
  // 预加载下一个视频
  else if (currentSegmentIndex.value + 1 === videoSegments.value.length - 1) {
    preloadVideoToPlayer(videoSegments.value.length - 1, 1 - currentPlayerIndex.value)
  }
  
  // 如果正在等待新视频，或者已暂停，恢复播放
  if (isWaitingForNewVideo.value || (isPaused.value && currentSegmentIndex.value >= 0)) {
    isWaitingForNewVideo.value = false
    isPaused.value = false
    
    if (currentSegmentIndex.value >= 0) {
      const player = currentVideoPlayer.value
      if (player?.value) {
        // 播放倒数第二个视频时，立即预加载下一个并切换
        if (currentSegmentIndex.value === videoSegments.value.length - 2) {
          preloadVideoToPlayer(videoSegments.value.length - 1, 1 - currentPlayerIndex.value)
          // 检查是否准备好，如果准备好了就立即切换
          const checkReady = setInterval(() => {
            if (videoReadyState[1 - currentPlayerIndex.value]) {
              clearInterval(checkReady)
              switchToNextVideo()
            }
          }, 50) // 更短的检查间隔，更快切换
          // 1秒后无论如何尝试切换
          setTimeout(() => {
            clearInterval(checkReady)
            if (currentSegmentIndex.value < videoSegments.value.length - 1 && videoReadyState[1 - currentPlayerIndex.value]) {
              switchToNextVideo()
            }
          }, 500) // 缩短超时时间，更快切换
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
    // 只添加助手回复，用户消息已经在 sendMessage 中立即显示了
    addMessage('assistant', data.data.assistant)
    // 同步到 store
    chatStore.addMessage(data.data.user, data.data.assistant)
  } else if (data.type === 'error') {
    ElMessage.error(data.message)
    addMessage('system', '错误: ' + data.message)
  } else if (data.type === 'video_segment') {
    if (data.path) addVideoSegment(data.path)
  } else if (data.type === 'hls_stream') {
    // HLS 流地址推送
    if (data.hls_url) {
      initHlsPlayer(data.hls_url)
    }
  }
}

// ==================== HLS 播放器相关函数 ====================

const initHlsPlayer = async (hlsUrl) => {
  if (!hlsUrl) return

  // 等待下一个 tick，确保 DOM 已更新
  await nextTick()

  // 检查视频元素是否可用
  if (!hlsVideoPlayer.value) {
    console.error('HLS video player element not found')
    return
  }

  currentHlsUrl.value = hlsUrl
  useHls.value = true

  // 停止原有播放器
  stopLegacyPlayers()

  // 初始化 HLS
  if (Hls.isSupported()) {
    // 销毁旧的 HLS 实例
    if (hls) {
      hls.destroy()
      hls = null
    }

    // 重置视频元素
    hlsVideoPlayer.value.pause()
    hlsVideoPlayer.value.removeAttribute('src')
    hlsVideoPlayer.value.load()

    hls = new Hls({
      enableWorker: true,
      lowLatencyMode: true,
      backBufferLength: 90,
      maxBufferLength: 30,
      maxMaxBufferLength: 60,
      liveSyncDurationCount: 3,
      liveMaxLatencyDurationCount: 10,
      fragLoadingTimeOut: 20000,
      manifestLoadingTimeOut: 10000,
      levelLoadingTimeOut: 10000,
      // 添加更多调试信息
      debug: false
    })

    hls.on(Hls.Events.MEDIA_ATTACHED, () => {
      console.log('HLS: Media attached')
    })

    hls.on(Hls.Events.MANIFEST_PARSED, (event, data) => {
      console.log('HLS: Manifest parsed, levels:', data.levels.length)
      // 自动播放
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
            // 回退到原有播放器
            useHls.value = false
            ElMessage.warning('HLS 播放失败，回退到原有播放器')
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

    // 先 attachMedia 再 loadSource
    hls.attachMedia(hlsVideoPlayer.value)
    hls.loadSource(hlsUrl)

  } else if (hlsVideoPlayer.value.canPlayType('application/vnd.apple.mpegurl')) {
    // Safari 原生支持 HLS
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
    useHls.value = false
  }
}

const stopLegacyPlayers = () => {
  // 停止原有播放器
  if (videoPlayer1.value) {
    videoPlayer1.value.pause()
    videoPlayer1.value.src = ''
  }
  if (videoPlayer2.value) {
    videoPlayer2.value.pause()
    videoPlayer2.value.src = ''
  }
  // 重置状态
  videoSegments.value = []
  currentSegmentIndex.value = -1
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
  useHls.value = false
  currentHlsUrl.value = null
}

const handleHlsTimeUpdate = () => {
  if (hlsVideoPlayer.value) {
    currentGlobalTime.value = hlsVideoPlayer.value.currentTime
  }
}

const handleHlsMetadataLoaded = () => {
  if (hlsVideoPlayer.value) {
    totalDuration.value = hlsVideoPlayer.value.duration || 0
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
  // 忽略某些非致命错误
  const video = e.target
  if (video && video.error) {
    const errorCode = video.error.code
    const errorMessage = video.error.message
    console.error('HLS Video Error - Code:', errorCode, 'Message:', errorMessage)

    // MEDIA_ERR_SRC_NOT_SUPPORTED (4) 或 MEDIA_ERR_NETWORK (2) 才需要处理
    if (errorCode === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED ||
        errorCode === MediaError.MEDIA_ERR_NETWORK) {
      ElMessage.error('HLS 播放出错，尝试回退到原有播放器')
      destroyHlsPlayer()
      useHls.value = false
    } else if (errorCode === MediaError.MEDIA_ERR_ABORTED) {
      // 用户中止或切换源，忽略
      console.log('HLS: Playback aborted (possibly source switch)')
    } else {
      // 其他错误，尝试恢复
      console.log('HLS: Trying to recover from error')
      if (hls) {
        hls.recoverMediaError()
      }
    }
  } else {
    console.error('HLS Video Error:', e)
  }
}

const checkHasCompleteVideo = async () => {
  try {
    const response = await fetch('/api/video/has-complete-video')
    const data = await response.json()
    if (data.hasVideo) {
      currentFinalVideoPath.value = data.path
    } else {
      currentFinalVideoPath.value = null
    }
  } catch (error) {
    console.error('检查完整视频状态失败:', error)
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
    currentPlayerIndex.value = 0
    videoReadyState[0] = false
    videoReadyState[1] = false
    currentFinalVideoPath.value = null
    // 销毁 HLS 播放器
    destroyHlsPlayer()
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

// 监听设置面板打开，检查是否有完整视频
watch(showSettings, (newVal) => {
  if (newVal) {
    checkHasCompleteVideo()
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
  // 销毁 HLS 播放器
  destroyHlsPlayer()
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

/* HLS 播放器样式 */
.digital-human-video.hls-player {
  z-index: 3;
}

.digital-human-video.hls-player.active {
  z-index: 4;
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
  flex-direction: column;
  gap: 12px;
  align-items: center;
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
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
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

.control-buttons {
  display: flex;
  gap: 16px;
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
