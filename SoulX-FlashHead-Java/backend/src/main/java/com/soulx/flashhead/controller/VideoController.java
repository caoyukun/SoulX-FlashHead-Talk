package com.soulx.flashhead.controller;

import com.soulx.flashhead.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoController {

    private final ChatService chatService;

    public VideoController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/stream")
    public ResponseEntity<Resource> streamVideo(@RequestParam String path) throws IOException {
        Path videoPath = Paths.get(path);
        
        if (!Files.exists(videoPath)) {
            return ResponseEntity.notFound().build();
        }
        
        Resource resource = new FileSystemResource(videoPath);
        String contentType = Files.probeContentType(videoPath);
        
        if (contentType == null) {
            contentType = "video/mp4";
        }
        
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + videoPath.getFileName() + "\"")
                .body(resource);
    }

    @GetMapping("/download-complete")
    public ResponseEntity<Resource> downloadCompleteVideo() throws IOException {
        String finalVideoPath = chatService.getCurrentFinalVideoPath();
        
        if (finalVideoPath == null) {
            return ResponseEntity.notFound().build();
        }
        
        Path videoPath = Paths.get(finalVideoPath);
        
        if (!Files.exists(videoPath)) {
            return ResponseEntity.notFound().build();
        }
        
        Resource resource = new FileSystemResource(videoPath);
        String contentType = Files.probeContentType(videoPath);
        
        if (contentType == null) {
            contentType = "video/mp4";
        }
        
        String filename = "SoulX-FlashHead_" + System.currentTimeMillis() + ".mp4";
        
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    @GetMapping("/has-complete-video")
    public ResponseEntity<Map<String, Object>> hasCompleteVideo() {
        String finalVideoPath = chatService.getCurrentFinalVideoPath();
        Map<String, Object> result = new HashMap<>();
        result.put("hasVideo", finalVideoPath != null);
        if (finalVideoPath != null) {
            result.put("path", finalVideoPath);
        }
        return ResponseEntity.ok(result);
    }
}
