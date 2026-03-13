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
          <el-form-item label="API Key">
            <el-input
              v-model="settings.apiKey"
              type="password"
              placeholder="请输入火山引擎 ARK API Key"
            />
          </el-form-item>
          
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
        </el-form>
      </el-card>
    </el-col>
    
    <el-col :span="16">
      <el-card class="video-card">
        <template #header>
          <div class="card-header">
            <span>📺 数字人</span>
          </div>
        </template>
        <div class="video-wrapper">
          <video
            ref="videoPlayer"
            class="digital-human-video"
            controls
            autoplay
            loop
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
            v-for="(msg, index) in chatHistory"
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
import { ref, reactive, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'

const videoPlayer = ref(null)
const chatMessages = ref(null)
const currentVideoUrl = ref('')
const inputMessage = ref('')
const isSending = ref(false)
const chatHistory = ref([])

const settings = reactive({
  apiKey: '',
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

const sendMessage = async () => {
  if (!inputMessage.value.trim()) {
    ElMessage.warning('请输入消息')
    return
  }
  
  if (!settings.apiKey.trim()) {
    ElMessage.warning('请先配置 API Key')
    return
  }
  
  isSending.value = true
  const message = inputMessage.value
  inputMessage.value = ''
  
  try {
    ElMessage.info('正在处理...')
  } catch (error) {
    console.error('发送消息失败:', error)
    ElMessage.error('发送消息失败: ' + (error.message || '未知错误'))
  } finally {
    isSending.value = false
  }
}

onMounted(() => {
  console.log('ChatView mounted')
})
</script>

<style scoped>
.chat-container {
  height: 100%;
}

.card-header {
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
