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

@Slf4j
@Service
public class ChatService {
    private final VolcengineClient volcengineClient;
    private final PythonServiceClient pythonServiceClient;
    private final ChatWebSocketHandler webSocketHandler;
    private final FlashHeadProperties properties;
    
    private final List<ChatMessage> chatHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Boolean> processingSessions = new ConcurrentHashMap<>();
    private String currentVideoPath;

    public ChatService(VolcengineClient volcengineClient, 
                       PythonServiceClient pythonServiceClient,
                       ChatWebSocketHandler webSocketHandler,
                       FlashHeadProperties properties) {
        this.volcengineClient = volcengineClient;
        this.pythonServiceClient = pythonServiceClient;
        this.webSocketHandler = webSocketHandler;
        this.properties = properties;
    }

    public List<ChatMessage> getChatHistory() {
        return new ArrayList<>(chatHistory);
    }

    public String getCurrentVideoPath() {
        return currentVideoPath;
    }

    public void processChatMessage(String userMessage, String apiKey, String condImage,
                                    String ckptDir, String wav2vecDir, String modelType,
                                    int seed, boolean useFaceCrop, String sessionId) {
        if (processingSessions.getOrDefault(sessionId, false)) {
            log.warn("Session {} is already processing", sessionId);
            return;
        }
        
        processingSessions.put(sessionId, true);
        
        new Thread(() -> {
            try {
                log.info("Processing chat message for session: {}", sessionId);
                
                String assistantMessage = volcengineClient.getChatResponse(
                    userMessage, apiKey, chatHistory
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
                
                String streamId = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
                Map<String, Object> videoResult = pythonServiceClient.generateVideoStreaming(
                    audioFile, condImage, ckptDir, wav2vecDir, modelType, seed, useFaceCrop, streamId
                );
                
                @SuppressWarnings("unchecked")
                List<String> segments = (List<String>) videoResult.get("segments");
                String finalVideo = (String) videoResult.get("final_video");
                
                for (String segmentPath : segments) {
                    currentVideoPath = segmentPath;
                    Map<String, Object> videoMsg = new HashMap<>();
                    videoMsg.put("type", "video_segment");
                    videoMsg.put("path", segmentPath);
                    webSocketHandler.broadcastMessage(videoMsg);
                    Thread.sleep(100);
                }
                
                currentVideoPath = finalVideo;
                Map<String, Object> finalVideoMsg = new HashMap<>();
                finalVideoMsg.put("type", "video_final");
                finalVideoMsg.put("path", finalVideo);
                webSocketHandler.broadcastMessage(finalVideoMsg);
                
                audioFile.delete();
                
            } catch (Exception e) {
                log.error("Error processing chat message", e);
                Map<String, Object> errorMsg = new HashMap<>();
                errorMsg.put("type", "error");
                errorMsg.put("message", e.getMessage());
                webSocketHandler.broadcastMessage(errorMsg);
            } finally {
                processingSessions.remove(sessionId);
            }
        }).start();
    }

    public void generateIdleVideo(String condImage, String ckptDir, String wav2vecDir,
                                   String modelType, int seed, boolean useFaceCrop,
                                   String sessionId) {
        new Thread(() -> {
            try {
                String idleVideoPath = pythonServiceClient.generateIdleVideo(
                    condImage, ckptDir, wav2vecDir, modelType, seed, useFaceCrop, 5.0
                );
                currentVideoPath = idleVideoPath;
                Map<String, Object> videoMsg = new HashMap<>();
                videoMsg.put("type", "idle_video");
                videoMsg.put("path", idleVideoPath);
                webSocketHandler.broadcastMessage(videoMsg);
            } catch (IOException e) {
                log.error("Error generating idle video", e);
            }
        }).start();
    }

    public void initializeModel(String condImage, String ckptDir, String wav2vecDir,
                                 String modelType, int seed, boolean useFaceCrop) throws IOException {
        pythonServiceClient.initializeModel(condImage, ckptDir, wav2vecDir, modelType, seed, useFaceCrop);
    }
}
