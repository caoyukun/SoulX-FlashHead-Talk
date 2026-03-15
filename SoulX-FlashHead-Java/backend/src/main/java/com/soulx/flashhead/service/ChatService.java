package com.soulx.flashhead.service;

import com.soulx.flashhead.client.PythonServiceClient;
import com.soulx.flashhead.client.VolcengineClient;
import com.soulx.flashhead.config.ChatWebSocketHandler;
import com.soulx.flashhead.config.FlashHeadProperties;
import com.soulx.flashhead.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatService {
    private final VolcengineClient volcengineClient;
    private final PythonServiceClient pythonServiceClient;
    private final ChatWebSocketHandler webSocketHandler;
    private final FlashHeadProperties properties;
    private final VideoStreamService videoStreamService;
    private final HlsStreamService hlsStreamService;

    private final List<ChatMessage> chatHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Boolean> processingSessions = new ConcurrentHashMap<>();

    private final List<String> sessionVideoSegments = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean isGeneratingIdle = new AtomicBoolean(false);
    private final AtomicBoolean hasReceivedFirstReply = new AtomicBoolean(false);
    private final AtomicBoolean hasPendingReply = new AtomicBoolean(false);
    private Thread idleVideoThread = null;
    private String currentCondImage;
    private String currentCkptDir;
    private String currentWav2vecDir;
    private String currentModelType;
    private int currentSeed;
    private boolean currentUseFaceCrop;
    private File currentAudioFile = null;

    private final BlockingQueue<String> pendingVideoQueue = new LinkedBlockingQueue<>();
    private volatile boolean isPushingVideos = false;
    private Thread videoPushThread = null;
    private volatile long lastPushTime = 0;
    private static final long MIN_PUSH_INTERVAL_MS = 2000;

    private final AtomicBoolean idleVideoGenerationComplete = new AtomicBoolean(false);
    private String currentFinalVideoPath = null;

    // HLS 相关
    private volatile boolean useHls = true; // 默认启用 HLS
    private String currentHlsSessionId = null;

    public ChatService(VolcengineClient volcengineClient,
                       PythonServiceClient pythonServiceClient,
                       ChatWebSocketHandler webSocketHandler,
                       FlashHeadProperties properties,
                       VideoStreamService videoStreamService,
                       HlsStreamService hlsStreamService) {
        this.volcengineClient = volcengineClient;
        this.pythonServiceClient = pythonServiceClient;
        this.webSocketHandler = webSocketHandler;
        this.properties = properties;
        this.videoStreamService = videoStreamService;
        this.hlsStreamService = hlsStreamService;
    }
    
    public String getCurrentFinalVideoPath() {
        return currentFinalVideoPath;
    }

    public List<ChatMessage> getChatHistory() {
        return new ArrayList<>(chatHistory);
    }

    public List<String> getSessionVideoSegments() {
        return new ArrayList<>(sessionVideoSegments);
    }

    public void initializeVideoStream(String condImage, String ckptDir, String wav2vecDir,
                                       String modelType, int seed, boolean useFaceCrop) {
        this.currentCondImage = condImage != null ? condImage : "examples/girl.png";
        this.currentCkptDir = ckptDir != null ? ckptDir : "models/SoulX-FlashHead-1_3B";
        this.currentWav2vecDir = wav2vecDir != null ? wav2vecDir : "models/wav2vec2-base-960h";
        this.currentModelType = modelType != null ? modelType : "lite";
        this.currentSeed = seed;
        this.currentUseFaceCrop = useFaceCrop;

        pendingVideoQueue.clear();
        sessionVideoSegments.clear();
        hasReceivedFirstReply.set(false);
        idleVideoGenerationComplete.set(false);

        // 生成新的 HLS 会话 ID
        if (useHls) {
            currentHlsSessionId = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            hlsStreamService.createSession(currentHlsSessionId);
            log.info("创建 HLS 会话: {}", currentHlsSessionId);
        }

        startVideoPushThread();
        startIdleVideoGeneration();
    }
    
    private void startVideoPushThread() {
        if (videoPushThread != null && videoPushThread.isAlive()) {
            return;
        }
        
        isPushingVideos = true;
        videoPushThread = new Thread(() -> {
            log.info("视频推送线程已启动");
            while (isPushingVideos && !Thread.currentThread().isInterrupted()) {
                try {
                    // 检查是否有 pending 的回复视频，有回复视频时不再推送空闲视频
                    if (hasReceivedFirstReply.get() || hasPendingReply.get()) {
                        // 清空待推送的空闲视频队列，避免穿插
                        if (!pendingVideoQueue.isEmpty()) {
                            pendingVideoQueue.clear();
                            log.info("有回复视频，清空空闲视频队列");
                        }
                        Thread.sleep(100);
                        continue;
                    }
                    
                    // 正常空闲视频推送逻辑
                    String videoPath = pendingVideoQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (videoPath != null) {
                        // 再次检查状态，防止在等待期间状态发生变化
                        if (hasReceivedFirstReply.get() || hasPendingReply.get()) {
                            log.info("状态已变化，丢弃空闲视频: {}", videoPath);
                            continue;
                        }
                        
                        // 检查是否满足最小推送间隔
                        long now = System.currentTimeMillis();
                        long timeSinceLastPush = now - lastPushTime;
                        if (timeSinceLastPush < MIN_PUSH_INTERVAL_MS) {
                            // 还没到时间，把视频放回队列
                            pendingVideoQueue.offer(videoPath);
                            Thread.sleep(MIN_PUSH_INTERVAL_MS - timeSinceLastPush);
                        } else {
                            pushVideoToFrontend(videoPath);
                            lastPushTime = now;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("视频推送线程错误", e);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("视频推送线程已停止");
        });
        videoPushThread.setDaemon(true);
        videoPushThread.start();
    }
    
    private void pushVideoToFrontend(String videoPath) {
        sessionVideoSegments.add(videoPath);
        log.info("推送视频片段到前端: {}", videoPath);
        
        Map<String, Object> videoMsg = new HashMap<>();
        videoMsg.put("type", "video_segment");
        videoMsg.put("path", videoPath);
        webSocketHandler.broadcastMessage(videoMsg);
        
        // 同时添加到实时视频流
        videoStreamService.addSegment(videoPath);
    }

    private void startIdleVideoGeneration() {
        if (idleVideoThread != null && idleVideoThread.isAlive()) {
            return;
        }
        
        idleVideoThread = new Thread(() -> {
            log.info("开始持续生成空闲视频");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 只有在没有pending回复且没有收到第一个回复视频时才生成空闲视频
                    if (hasReceivedFirstReply.get() || hasPendingReply.get()) {
                        Thread.sleep(100);
                        continue;
                    }
                    
                    // 如果空闲视频生成已完成，等待一会儿再重新开始
                    if (idleVideoGenerationComplete.get()) {
                        Thread.sleep(1000);
                        idleVideoGenerationComplete.set(false);
                    }
                    
                    if (isGeneratingIdle.compareAndSet(false, true)) {
                        try {
                            // 再次检查状态，避免在检查后状态变化
                            if (hasReceivedFirstReply.get() || hasPendingReply.get()) {
                                log.info("状态已变化，跳过本次空闲视频生成");
                                continue;
                            }
                            
                            String streamId = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
                            log.info("开始生成空闲视频, streamId: {}, useHls: {}", streamId, useHls);
                            
                            if (useHls) {
                                // 使用 HLS 方式生成空闲视频
                                String backendUrl = "http://localhost:8080";
                                
                                // 预创建 HLS 会话
                                hlsStreamService.createSession(streamId);
                                
                                // 推送 HLS 地址到前端
                                String hlsUrl = backendUrl + "/api/hls/" + streamId + "/playlist.m3u8";
                                Map<String, Object> hlsMsg = new HashMap<>();
                                hlsMsg.put("type", "hls_stream");
                                hlsMsg.put("hls_url", hlsUrl);
                                hlsMsg.put("stream_id", streamId);
                                webSocketHandler.broadcastMessage(hlsMsg);
                                
                                // 调用 Python 服务生成空闲视频
                                pythonServiceClient.generateIdleVideoHls(
                                    currentCondImage, currentCkptDir, currentWav2vecDir,
                                    currentModelType, currentSeed, currentUseFaceCrop,
                                    15.0, streamId, backendUrl
                                );
                            } else {
                                // 使用原有流式回调方式
                                pythonServiceClient.setCallbackUrl("http://localhost:8080/api/callback/new-segment");
                                pythonServiceClient.generateIdleVideo(
                                    currentCondImage, currentCkptDir, currentWav2vecDir,
                                    currentModelType, currentSeed, currentUseFaceCrop, 15.0,
                                    true, streamId
                                );
                            }
                            
                            // 等待空闲视频生成完成
                            while (!idleVideoGenerationComplete.get() && 
                                   !hasReceivedFirstReply.get() && 
                                   !hasPendingReply.get()) {
                                Thread.sleep(100);
                            }
                            
                            log.info("空闲视频生成流程完成");
                        } finally {
                            isGeneratingIdle.set(false);
                        }
                    }
                } catch (Exception e) {
                    log.error("生成空闲视频失败", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("停止生成空闲视频");
        });
        idleVideoThread.setDaemon(true);
        idleVideoThread.start();
    }

    public void addVideoSegment(String videoPath) {
        log.info("收到视频片段: {}", videoPath);
        
        // 检查是否是回复视频
        boolean isReply = !videoPath.contains("idle");
        if (isReply) {
            // 只要收到回复视频，就立即设置标志，停止空闲视频生成
            if (!hasReceivedFirstReply.get()) {
                hasReceivedFirstReply.set(true);
                log.info("收到第一个回复视频，停止生成空闲视频");
            }
            // 回复视频直接推送到前端，不通过队列
            pushVideoToFrontend(videoPath);
        } else {
            // 空闲视频放入队列，等待缓慢推送
            try {
                pendingVideoQueue.put(videoPath);
                log.info("空闲视频已加入推送队列: {}", videoPath);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("添加空闲视频到队列失败", e);
            }
        }
    }
    
    private void cleanupAudioFile() {
        if (currentAudioFile != null && currentAudioFile.exists()) {
            try {
                currentAudioFile.delete();
                log.info("已删除临时音频文件: {}", currentAudioFile.getAbsolutePath());
            } catch (Exception e) {
                log.error("删除临时音频文件失败", e);
            }
            currentAudioFile = null;
        }
    }
    
    public void onVideoGenerationComplete(String finalVideoPath) {
        log.info("视频生成完成");
        
        // 保存完整视频路径（如果是回复视频）
        if (hasPendingReply.get() && finalVideoPath != null) {
            currentFinalVideoPath = finalVideoPath;
            log.info("保存完整回复视频路径: {}", finalVideoPath);
        }
        
        // 检查是否是回复视频完成
        if (hasPendingReply.get()) {
            cleanupAudioFile();
            hasPendingReply.set(false);
            processingSessions.clear();
            
            // 重新开始生成空闲视频
            hasReceivedFirstReply.set(false);
            idleVideoGenerationComplete.set(false);
            log.info("回复视频生成完成，重新开始生成空闲视频");
        } else {
            // 空闲视频生成完成
            idleVideoGenerationComplete.set(true);
            log.info("空闲视频生成完成");
        }
    }
    
    public void onVideoGenerationComplete() {
        onVideoGenerationComplete(null);
    }
    
    public void processChatMessage(String userMessage, String sessionId) {
        if (processingSessions.getOrDefault(sessionId, false)) {
            log.warn("Session {} is already processing", sessionId);
            return;
        }

        processingSessions.put(sessionId, true);
        hasPendingReply.set(true);
        hasReceivedFirstReply.set(false); // 重置，等待新的回复视频

        new Thread(() -> {
            try {
                log.info("Processing chat message for session: {}", sessionId);

                String assistantMessage = volcengineClient.getChatResponse(
                    userMessage, chatHistory
                );

                ChatMessage message = new ChatMessage();
                message.setUser(userMessage);
                message.setAssistant(assistantMessage);
                chatHistory.add(message);

                Map<String, Object> chatMsg = new HashMap<>();
                chatMsg.put("type", "chat");
                chatMsg.put("data", message);
                webSocketHandler.broadcastMessage(chatMsg);

                File audioFile = pythonServiceClient.textToSpeech(assistantMessage);
                currentAudioFile = audioFile;

                String streamId = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());

                if (useHls) {
                    // 使用 HLS 流式生成
                    log.info("使用 HLS 流式生成视频, streamId: {}", streamId);

                    // 预创建 HLS 会话，这样前端请求时会话已经存在
                    hlsStreamService.createSession(streamId);
                    log.info("预创建 HLS 会话: {}", streamId);

                    // 生成后端 URL（用于 Python 服务回调）
                    String backendUrl = "http://localhost:8080";

                    // 先推送 HLS 流地址到前端
                    String hlsUrl = backendUrl + "/api/hls/" + streamId + "/playlist.m3u8";
                    log.info("HLS 流地址: {}", hlsUrl);

                    Map<String, Object> hlsMsg = new HashMap<>();
                    hlsMsg.put("type", "hls_stream");
                    hlsMsg.put("hls_url", hlsUrl);
                    hlsMsg.put("stream_id", streamId);
                    webSocketHandler.broadcastMessage(hlsMsg);

                    // 然后调用 Python 服务生成视频（异步）
                    Map<String, Object> result = pythonServiceClient.generateVideoHls(
                        audioFile, currentCondImage, currentCkptDir, currentWav2vecDir,
                        currentModelType, currentSeed, currentUseFaceCrop, streamId, backendUrl
                    );

                    log.info("Python HLS 生成启动结果: {}", result);

                } else {
                    // 使用原有分段 MP4 方式
                    pythonServiceClient.setCallbackUrl("http://localhost:8080/api/callback/new-segment");
                    pythonServiceClient.generateVideoStreamingWithCallback(
                        audioFile, currentCondImage, currentCkptDir, currentWav2vecDir,
                        currentModelType, currentSeed, currentUseFaceCrop, streamId
                    );
                }

            } catch (Exception e) {
                log.error("Error processing chat message", e);
                Map<String, Object> errorMsg = new HashMap<>();
                errorMsg.put("type", "error");
                errorMsg.put("message", e.getMessage());
                webSocketHandler.broadcastMessage(errorMsg);
                cleanupAudioFile();
                hasPendingReply.set(false);
                hasReceivedFirstReply.set(false);
                processingSessions.remove(sessionId);
            }
        }).start();
    }

    public void initializeModel(String condImage, String ckptDir, String wav2vecDir,
                                 String modelType, int seed, boolean useFaceCrop) throws IOException {
        pythonServiceClient.initializeModel(condImage, ckptDir, wav2vecDir, modelType, seed, useFaceCrop);
        initializeVideoStream(condImage, ckptDir, wav2vecDir, modelType, seed, useFaceCrop);
    }
    
    public void stopIdleVideoGeneration() {
        if (idleVideoThread != null) {
            idleVideoThread.interrupt();
        }
    }

    /**
     * 前端请求生成空闲视频（回复视频播放完后调用）
     */
    public void startIdleVideoGeneration(double duration) {
        log.info("前端请求生成空闲视频, duration={}", duration);

        // 重置状态，允许生成空闲视频
        hasReceivedFirstReply.set(false);
        idleVideoGenerationComplete.set(false);

        // 启动一个新的线程来生成空闲视频
        Thread idleThread = new Thread(() -> {
            try {
                String streamId = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
                log.info("开始生成空闲视频, streamId: {}", streamId);

                if (useHls) {
                    // 使用 HLS 方式生成空闲视频
                    String backendUrl = "http://localhost:8080";

                    // 预创建 HLS 会话
                    hlsStreamService.createSession(streamId);

                    // 推送 HLS 地址到前端
                    String hlsUrl = backendUrl + "/api/hls/" + streamId + "/playlist.m3u8";
                    Map<String, Object> hlsMsg = new HashMap<>();
                    hlsMsg.put("type", "hls_stream");
                    hlsMsg.put("hls_url", hlsUrl);
                    hlsMsg.put("stream_id", streamId);
                    webSocketHandler.broadcastMessage(hlsMsg);

                    // 调用 Python 服务生成空闲视频
                    pythonServiceClient.generateIdleVideoHls(
                        currentCondImage, currentCkptDir, currentWav2vecDir,
                        currentModelType, currentSeed, currentUseFaceCrop,
                        duration, streamId, backendUrl
                    );
                } else {
                    // 使用原有方式生成空闲视频
                    pythonServiceClient.setCallbackUrl("http://localhost:8080/api/callback/new-segment");
                    pythonServiceClient.generateIdleVideo(
                        currentCondImage, currentCkptDir, currentWav2vecDir,
                        currentModelType, currentSeed, currentUseFaceCrop, duration,
                        true, streamId
                    );
                }

                log.info("空闲视频生成请求已发送");
            } catch (Exception e) {
                log.error("生成空闲视频失败", e);
            }
        });
        idleThread.setDaemon(true);
        idleThread.start();
    }
}
