import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 300000
})

export const chatApi = {
  getHistory() {
    return api.get('/chat/history')
  },
  
  getCurrentVideo() {
    return api.get('/chat/current-video')
  },
  
  sendMessage(data) {
    return api.post('/chat/send', data)
  },
  
  initialize(data) {
    return api.post('/chat/initialize', data)
  },
  
  generateIdleVideo(data) {
    return api.post('/chat/idle-video', data)
  }
}

export const videoApi = {
  getVideoUrl(path) {
    return `/api/video/stream?path=${encodeURIComponent(path)}`
  }
}

export default api
