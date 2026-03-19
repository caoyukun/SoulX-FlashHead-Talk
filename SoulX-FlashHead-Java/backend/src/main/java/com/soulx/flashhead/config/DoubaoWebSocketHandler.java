package com.soulx.flashhead.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulx.flashhead.service.ChatService;
import com.soulx.flashhead.service.DoubaoAudioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DoubaoWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final DoubaoAudioService doubaoAudioService;
    private final ChatService chatService;

    public DoubaoWebSocketHandler(DoubaoAudioService doubaoAudioService, ChatService chatService) {
        this.doubaoAudioService = doubaoAudioService;
        this.chatService = chatService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = session.getId();
        sessions.put(sessionId, session);
        log.info("Doubao WebSocket 连接建立: {}", sessionId);
        
        try {
            doubaoAudioService.startSession();
            sendMessageToSession(sessionId, Map.of("type", "connected", "status", "success"));
        } catch (Exception e) {
            log.error("Failed to start Doubao session", e);
            sendMessageToSession(sessionId, Map.of("type", "error", "message", e.getMessage()));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String sessionId = session.getId();
        log.debug("收到文本消息: {}", message.getPayload());
        
        try {
            Map<String, Object> data = objectMapper.readValue(message.getPayload(), Map.class);
            String type = (String) data.get("type");
            
            if ("audio_data".equals(type)) {
                String base64Audio = (String) data.get("audio");
                byte[] audioData = java.util.Base64.getDecoder().decode(base64Audio);
                doubaoAudioService.sendUserAudio(audioData);
            } else if ("end_audio".equals(type)) {
                log.info("收到音频结束信号");
                java.io.File audioFile = doubaoAudioService.getCurrentAudioFile();
                if (audioFile != null) {
                    chatService.processAudioMessage(audioFile, sessionId);
                }
            }
        } catch (Exception e) {
            log.error("处理消息失败", e);
            sendMessageToSession(sessionId, Map.of("type", "error", "message", e.getMessage()));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String sessionId = session.getId();
        ByteBuffer buffer = message.getPayload();
        byte[] audioData = new byte[buffer.remaining()];
        buffer.get(audioData);
        
        log.debug("收到二进制音频数据: {} bytes", audioData.length);
        doubaoAudioService.sendUserAudio(audioData);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        sessions.remove(sessionId);
        log.info("Doubao WebSocket 连接关闭: {}, 状态: {}", sessionId, status);
        
        try {
            doubaoAudioService.endSession();
        } catch (Exception e) {
            log.error("Error ending Doubao session", e);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Doubao WebSocket 传输错误: {}", session.getId(), exception);
    }

    public void sendMessageToSession(String sessionId, Map<String, Object> message) {
        WebSocketSession session = sessions.get(sessionId);
        if (session != null && session.isOpen()) {
            try {
                String json = objectMapper.writeValueAsString(message);
                session.sendMessage(new TextMessage(json));
            } catch (Exception e) {
                log.error("发送消息失败: {}", sessionId, e);
            }
        }
    }

    public void broadcastMessage(Map<String, Object> message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("序列化消息失败", e);
            return;
        }

        TextMessage textMessage = new TextMessage(json);
        for (WebSocketSession session : sessions.values()) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(textMessage);
                } catch (Exception e) {
                    log.error("广播消息失败: {}", session.getId(), e);
                }
            }
        }
    }
}
