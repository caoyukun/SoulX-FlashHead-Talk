package com.soulx.flashhead.controller;

import com.soulx.flashhead.config.ChatWebSocketHandler;
import com.soulx.flashhead.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/callback")
@CrossOrigin(origins = "*")
public class VideoCallbackController {
    
    private final ChatService chatService;
    private final ChatWebSocketHandler webSocketHandler;

    public VideoCallbackController(ChatService chatService, ChatWebSocketHandler webSocketHandler) {
        this.chatService = chatService;
        this.webSocketHandler = webSocketHandler;
    }

    @PostMapping("/new-segment")
    public Map<String, Object> handleNewSegment(@RequestBody Map<String, Object> data) {
        String videoPath = (String) data.get("path");
        log.info("收到新视频段回调: {}", videoPath);
        
        chatService.addVideoSegment(videoPath);
        
        Map<String, Object> videoMsg = new HashMap<>();
        videoMsg.put("type", "video_segment");
        videoMsg.put("path", videoPath);
        webSocketHandler.broadcastMessage(videoMsg);
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        return result;
    }
    
    @PostMapping("/generation-complete")
    public Map<String, Object> handleGenerationComplete() {
        log.info("收到视频生成完成回调");
        chatService.onVideoGenerationComplete();
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        return result;
    }
}
