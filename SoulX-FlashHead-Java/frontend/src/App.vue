<template>
  <div id="app">
    <el-container class="app-container">
      <el-header>
        <h1>🎭 SoulX-FlashHead 数字人实时聊天</h1>
      </el-header>
      <el-main>
        <ChatView />
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import ChatView from './views/ChatView.vue'
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

#app {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB',
    'Microsoft YaHei', '微软雅黑', Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  height: 100vh;
}

.app-container {
  height: 100%;
}

.el-header {
  background-color: #409eff;
  color: white;
  display: flex;
  align-items: center;
  padding: 0 20px;
}

.el-header h1 {
  font-size: 24px;
}

.el-main {
  padding: 20px;
}
</style>
