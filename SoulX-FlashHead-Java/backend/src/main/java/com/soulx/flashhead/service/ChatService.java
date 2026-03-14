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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class ChatService {
    private final VolcengineClient volcengineClient;
    private final PythonServiceClient pythonServiceClient;
    private final ChatWebSocketHandler webSocketHandler;
    private final FlashHeadProperties properties;
    private final VideoStreamService videoStreamService;
    
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

    public ChatService(VolcengineClient volcengineClient, 
                       PythonServiceClient pythonServiceClient,
                       ChatWebSocketHandler webSocketHandler,
                       FlashHeadProperties properties,
                       VideoStreamService videoStreamService) {
        this.volcengineClient = volcengineClient;
        this.pythonServiceClient = pythonServiceClient;
        this.webSocketHandler = webSocketHandler;
        this.properties = properties;
        this.videoStreamService = videoStreamService;
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
        
        sessionVideoSegments.clear();
        hasReceivedFirstReply.set(false);
        startIdleVideoGeneration();
    }

    private void startIdleVideoGeneration() {
        if (idleVideoThread != null && idleVideoThread.isAlive()) {
            return;
        }
        
        idleVideoThread = new Thread(() -> {
            log.info("开始持续生成空闲视频");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 只有在收到第一个回复视频后才停止生成空闲视频
                    if (hasReceivedFirstReply.get()) {
                        Thread.sleep(100);
                        continue;
                    }
                    
                    if (isGeneratingIdle.compareAndSet(false, true)) {
                        try {
                            String idleVideoPath = pythonServiceClient.generateIdleVideo(
                                currentCondImage, currentCkptDir, currentWav2vecDir,
                                currentModelType, currentSeed, currentUseFaceCrop, 3.0
                            );
                            
                            sessionVideoSegments.add(idleVideoPath);
                            log.info("添加空闲视频片段: {}", idleVideoPath);
                            
                            Map<String, Object> videoMsg = new HashMap<>();
                            videoMsg.put("type", "video_segment");
                            videoMsg.put("path", idleVideoPath);
                            webSocketHandler.broadcastMessage(videoMsg);
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
        sessionVideoSegments.add(videoPath);
        log.info("添加视频片段: {}", videoPath);
        
        // 检查是否是第一个回复视频
        boolean isReply = !videoPath.contains("idle");
        if (isReply && !hasReceivedFirstReply.get()) {
            hasReceivedFirstReply.set(true);
            log.info("收到第一个回复视频，停止生成空闲视频");
        }
        
        // 同时添加到实时视频流
        videoStreamService.addSegment(videoPath);
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
    
    public void onVideoGenerationComplete() {
        log.info("视频生成完成");
        cleanupAudioFile();
        hasPendingReply.set(false);
        processingSessions.clear();
        
        // 重新开始生成空闲视频
        hasReceivedFirstReply.set(false);
        log.info("重新开始生成空闲视频");
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
                
                pythonServiceClient.setCallbackUrl("http://localhost:8080/api/callback/new-segment");
                pythonServiceClient.generateVideoStreamingWithCallback(
                    audioFile, currentCondImage, currentCkptDir, currentWav2vecDir,
                    currentModelType, currentSeed, currentUseFaceCrop, streamId
                );
                
            } catch (Exception e) {
                log.error("Error processing chat message", e);
                Map<String, Object> errorMsg = new HashMap<>();
                errorMsg.put("type", "error");
                errorMsg.put("message", e.getMessage());
                webSocketHandler.broadcastMessage(errorMsg);
                cleanupAudioFile();
                hasPendingReply.set(false);
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
}
