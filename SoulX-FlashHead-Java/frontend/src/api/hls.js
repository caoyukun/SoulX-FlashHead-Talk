/**
 * HLS 播放相关 API
 */

export const hlsApi = {
  /**
   * 获取 HLS 播放列表 URL
   */
  getPlaylistUrl(sessionId) {
    return `/api/hls/${sessionId}/playlist.m3u8`
  },

  /**
   * 创建 HLS 会话
   */
  async createSession(sessionId) {
    const response = await fetch(`/api/hls/${sessionId}/create`, {
      method: 'POST'
    })
    return response.ok
  },

  /**
   * 结束 HLS 会话
   */
  async endSession(sessionId) {
    const response = await fetch(`/api/hls/${sessionId}/end`, {
      method: 'POST'
    })
    return response.ok
  }
}

export default hlsApi
