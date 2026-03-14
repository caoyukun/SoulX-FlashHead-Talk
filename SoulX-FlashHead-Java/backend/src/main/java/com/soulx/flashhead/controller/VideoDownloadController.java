package com.soulx.flashhead.controller;

import com.soulx.flashhead.service.ChatService;
import com.soulx.flashhead.service.VideoMergeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoDownloadController {
    
    private final ChatService chatService;
    private final VideoMergeService videoMergeService;
    
    public VideoDownloadController(ChatService chatService, VideoMergeService videoMergeService) {
        this.chatService = chatService;
        this.videoMergeService = videoMergeService;
    }
    
    @GetMapping("/download-session")
    public ResponseEntity<Resource> downloadSessionVideo() {
        try {
            List<String> videoSegments = chatService.getSessionVideoSegments();
            
            if (videoSegments.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            
            File mergedVideo = videoMergeService.mergeVideos(videoSegments);
            
            String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            String filename = "SoulX-FlashHead_" + timestamp + ".mp4";
            
            Resource resource = new FileSystemResource(mergedVideo);
            
            String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8.toString())
                    .replace("+", "%20");
            
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, 
                            "attachment; filename*=UTF-8''" + encodedFilename)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(mergedVideo.length())
                    .body(resource);
                    
        } catch (Exception e) {
            log.error("下载会话视频失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
