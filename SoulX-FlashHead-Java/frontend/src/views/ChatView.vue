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
          <!-- 两个video元素无缝切换 - 主流实现方式 -->
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

const videoPlayer1 = ref(null)
const videoPlayer2 = ref(null)
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

// 双播放器管理
const currentPlayerIndex = ref(0)
const videoPlayers = computed(() => [videoPlayer1, videoPlayer2])
const currentVideoPlayer = computed(() => videoPlayers.value[currentPlayerIndex.value])
const nextVideoPlayer = computed(() => videoPlayers.value[1 - currentPlayerIndex.value])

// 预加载状态跟踪
const videoReadyState = reactive({ 0: false, 1: false })

// 伪流式视频管理
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

// 预加载视频到指定播放器（设置src并load，确保准备好）
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

// 切换播放器并播放 - 确保无缝切换且无混音
const switchToNextVideo = () => {
  const nextIndex = currentSegmentIndex.value + 1
  if (nextIndex >= videoSegments.value.length) {
    console.log('没有更多视频')
    isPaused.value = true
    return
  }
  
  const nextPlayerIndex = 1 - currentPlayerIndex.value
  const prevPlayerIndex = 1 - nextPlayerIndex
  
  // 检查下一个视频是否准备好
  if (!videoReadyState[nextPlayerIndex]) {
    console.log('下一个视频还未准备好，等待中...')
    return
  }
  
  console.log('丝滑切换到下一个视频段:', nextIndex)
  
  // 更新索引切换显示
  currentSegmentIndex.value = nextIndex
  currentPlayerIndex.value = nextPlayerIndex
  isWaitingForNewVideo.value = false
  
  // 播放下一个视频
  const nextPlayer = videoPlayers.value[nextPlayerIndex]
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
  
  // 暂停上一个视频（延迟一点，避免闪黑）
  setTimeout(() => {
    const prevPlayer = videoPlayers.value[prevPlayerIndex]
    if (prevPlayer?.value) {
      prevPlayer.value.pause()
      prevPlayer.value.muted = true
      prevPlayer.value.currentTime = 0
    }
  }, 50)
  
  // 预加载下下个视频
  const preloadIndex = nextIndex + 1
  if (preloadIndex < videoSegments.value.length) {
    preloadVideoToPlayer(preloadIndex, 1 - nextPlayerIndex)
  }
}

// 播放指定的视频段（用于初始播放或跳转）
const playSegment = (index) => {
  if (index < 0 || index >= videoSegments.value.length) {
    console.log('视频段索引超出范围')
    return
  }
  
  const segment = videoSegments.value[index]
  console.log('播放视频段:', index, segment.path)
  
  currentSegmentIndex.value = index
  isWaitingForNewVideo.value = false
  
  // 重置准备状态
  videoReadyState[0] = false
  videoReadyState[1] = false
  
  // 先确保所有播放器都是暂停和静音的
  for (let i = 0; i < videoPlayers.value.length; i++) {
    const p = videoPlayers.value[i]
    if (p?.value) {
      p.value.pause()
      p.value.muted = true
    }
  }
  
  // 预加载下一个视频到另一个播放器
  if (index + 1 < videoSegments.value.length) {
    preloadVideoToPlayer(index + 1, 1 - currentPlayerIndex.value)
  }
  
  // 播放当前视频
  const player = currentVideoPlayer.value
  if (player?.value) {
    player.value.src = segment.url
    player.value.muted = isMuted.value
    player.value.volume = savedVolume.value
    player.value.loop = false
    player.value.load()
    
    // 只有当用户已交互时才尝试播放
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

// 视频可以播放（使用canplaythrough确保足够缓冲）
const handleCanPlayThrough = (playerIndex) => {
  console.log(`播放器 ${playerIndex} 视频准备就绪 (canplaythrough)`)
  videoReadyState[playerIndex] = true
  
  // 如果不是当前播放器，不自动播放，只加载
  // 避免同时播放导致混音问题
}

// 跟踪检查定时器，防止重复
let checkInterval = null
let lastEndedTime = 0

// 视频播放结束，播放下一个
const handleVideoEnded = (playerIndex) => {
  const now = Date.now()
  console.log('视频段播放结束，playerIndex:', playerIndex, 'currentSegmentIndex:', currentSegmentIndex.value, 'isWaitingForNewVideo:', isWaitingForNewVideo.value)
  
  // 只过滤超级快速的重复（100ms内），避免正常切换被阻止
  if (now - lastEndedTime < 100) {
    console.log('超快速重复的ended事件，忽略')
    return
  }
  lastEndedTime = now
  
  // 清除之前的检查定时器
  if (checkInterval) {
    clearInterval(checkInterval)
    checkInterval = null
  }
  
  if (currentSegmentIndex.value < videoSegments.value.length - 1) {
    // 检查下一个视频是否准备好
    const nextPlayerIndex = 1 - currentPlayerIndex.value
    if (videoReadyState[nextPlayerIndex]) {
      switchToNextVideo()
    } else {
      console.log('等待下一个视频准备好...')
      isWaitingForNewVideo.value = true
      
      // 每隔100ms检查一次，最多等待
      let checkCount = 0
      checkInterval = setInterval(() => {
        checkCount++
        if (videoReadyState[nextPlayerIndex]) {
          clearInterval(checkInterval)
          checkInterval = null
          isWaitingForNewVideo.value = false
          switchToNextVideo()
        } else if (checkCount > 300) {
          // 30秒超时，不再等待
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
    // 不设置isPaused，这样当收到新视频时能自动恢复
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
    // 同步到另一个播放器
    const otherPlayer = nextVideoPlayer.value
    if (otherPlayer?.value) {
      otherPlayer.value.muted = isMuted.value
      otherPlayer.value.volume = savedVolume.value
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

// 切换静音
const toggleMute = () => {
  const player = currentVideoPlayer.value
  if (!player?.value) return
  player.value.muted = !player.value.muted
}

// 添加视频到队列
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
  
  console.log('视频队列长度:', videoSegments.value.length, 'currentSegmentIndex:', currentSegmentIndex.value)
  
  // 如果没有在播放，开始播放
  if (currentSegmentIndex.value === -1 && videoSegments.value.length > 0) {
    console.log('开始播放视频')
    isWaitingForNewVideo.value = false
    playSegment(0)
    isPaused.value = false
  } else if (currentSegmentIndex.value + 1 === videoSegments.value.length - 1) {
    // 如果正在播放倒数第二个视频，预加载下一个
    console.log('预加载新添加的视频')
    preloadVideoToPlayer(videoSegments.value.length - 1, 1 - currentPlayerIndex.value)
  }
  
  // 如果正在等待新视频，或者当前已经播放完所有视频，立即处理
  if (isWaitingForNewVideo.value || (isPaused.value && currentSegmentIndex.value >= 0)) {
    console.log('收到新视频，取消等待状态或恢复播放')
    isWaitingForNewVideo.value = false
    isPaused.value = false
    
    // 检查当前播放状态和新视频情况
    if (currentSegmentIndex.value >= 0) {
      const player = currentVideoPlayer.value
      if (player?.value) {
        // 情况1：当前播放到最后一个视频，现在有新视频了
        if (currentSegmentIndex.value === videoSegments.value.length - 2) {
          console.log('播放到最后一个视频，有新视频，预加载并切换')
          preloadVideoToPlayer(videoSegments.value.length - 1, 1 - currentPlayerIndex.value)
          
          // 等待视频准备好后切换
          const checkReady = setInterval(() => {
            if (videoReadyState[1 - currentPlayerIndex.value]) {
              clearInterval(checkReady)
              switchToNextVideo()
            }
          }, 100)
          
          // 1秒后无论如何尝试切换
          setTimeout(() => {
            clearInterval(checkReady)
            if (currentSegmentIndex.value < videoSegments.value.length - 1) {
              switchToNextVideo()
            }
          }, 1000)
        }
        // 情况2：当前播放器已暂停，直接播放
        else if (player.value.paused) {
          console.log('播放器已暂停，直接播放')
          player.value.play().catch(() => {})
        }
      }
    }
    
    // 如果有下一个视频并且已准备好，直接切换
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
    
    // 重置视频队列
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
  
  // 创建临时video用于快速获取时长
  tempVideo = document.createElement('video')
  tempVideo.preload = 'metadata'
  
  connectWebSocket()
  
  // 监听用户交互
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
  min-height: 512px;
  width: 100%;
}

.digital-human-video {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  min-height: 512px;
  object-fit: contain;
  z-index: 1;
  opacity: 0;
}

.digital-human-video.active {
  opacity: 1;
  z-index: 10;
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
