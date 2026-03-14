package com.soulx.flashhead.controller;

import com.soulx.flashhead.model.ChatMessage;
import com.soulx.flashhead.model.ChatRequest;
import com.soulx.flashhead.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/history")
    public List<ChatMessage> getHistory() {
        return chatService.getChatHistory();
    }

    @GetMapping("/video-segments")
    public List<String> getVideoSegments() {
        return chatService.getSessionVideoSegments();
    }

    @PostMapping("/send")
    public Map<String, String> sendMessage(@RequestBody ChatRequest request) {
        String sessionId = UUID.randomUUID().toString();
        
        chatService.processChatMessage(
            request.getMessage(),
            sessionId
        );
        
        Map<String, String> result = new HashMap<>();
        result.put("sessionId", sessionId);
        result.put("status", "processing");
        return result;
    }

    @PostMapping("/initialize")
    public Map<String, String> initialize(@RequestBody ChatRequest request) throws IOException {
        chatService.initializeModel(
            request.getCondImage() != null ? request.getCondImage() : "examples/girl.png",
            request.getCkptDir() != null ? request.getCkptDir() : "models/SoulX-FlashHead-1_3B",
            request.getWav2vecDir() != null ? request.getWav2vecDir() : "models/wav2vec2-base-960h",
            request.getModelType() != null ? request.getModelType() : "lite",
            request.getSeed() != null ? request.getSeed() : 9999,
            request.getUseFaceCrop() != null ? request.getUseFaceCrop() : false
        );

        Map<String, String> result = new HashMap<>();
        result.put("status", "initialized");
        return result;
    }

    @PostMapping("/idle-video")
    public Map<String, String> generateIdleVideo(@RequestBody ChatRequest request) {
        log.info("收到空闲视频生成请求");

        Double duration = request.getDuration() != null ? request.getDuration() : 3.0;

        // 启动空闲视频生成
        chatService.startIdleVideoGeneration(duration);

        Map<String, String> result = new HashMap<>();
        result.put("status", "started");
        result.put("message", "空闲视频生成已启动");
        return result;
    }
}
