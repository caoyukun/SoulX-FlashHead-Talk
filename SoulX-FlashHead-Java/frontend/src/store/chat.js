import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useChatStore = defineStore('chat', () => {
  const chatHistory = ref([])
  const currentVideoUrl = ref('')
  const currentSessionId = ref('')
  const isConnected = ref(false)

  function addMessage(user, assistant) {
    chatHistory.value.push({ user, assistant })
  }

  function setCurrentVideoUrl(url) {
    currentVideoUrl.value = url
  }

  function setSessionId(sessionId) {
    currentSessionId.value = sessionId
  }

  function setConnected(connected) {
    isConnected.value = connected
  }

  function clearHistory() {
    chatHistory.value = []
  }

  return {
    chatHistory,
    currentVideoUrl,
    currentSessionId,
    isConnected,
    addMessage,
    setCurrentVideoUrl,
    setSessionId,
    setConnected,
    clearHistory
  }
})
