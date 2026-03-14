package com.soulx.flashhead.controller;

import com.soulx.flashhead.service.ChatService;
import com.soulx.flashhead.service.VideoStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.*;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoStreamController {
    
    private final ChatService chatService;
    private final VideoStreamService videoStreamService;

    public VideoStreamController(ChatService chatService, VideoStreamService videoStreamService) {
        this.chatService = chatService;
        this.videoStreamService = videoStreamService;
    }

    /**
     * 实时视频流 - 持续推送合并后的视频
     */
    @GetMapping(value = "/live-stream", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> liveStream() {
        log.info("开始实时视频流");
        
        StreamingResponseBody responseBody = outputStream -> {
            try {
                videoStreamService.startStream(outputStream);
            } catch (Exception e) {
                log.error("视频流处理错误", e);
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header("Content-Disposition", "inline")
                .body(responseBody);
    }

    /**
     * 添加视频段到实时流
     */
    @PostMapping("/add-segment")
    public ResponseEntity<?> addSegment(@RequestBody SegmentRequest request) {
        log.info("添加视频段到流: {}", request.getPath());
        videoStreamService.addSegment(request.getPath());
        return ResponseEntity.ok().build();
    }

    /**
     * 停止视频流
     */
    @PostMapping("/stop-stream")
    public ResponseEntity<?> stopStream() {
        log.info("停止视频流");
        videoStreamService.stopStream();
        return ResponseEntity.ok().build();
    }

    /**
     * 流式传输完整合并视频（下载用）
     */
    @GetMapping(value = "/stream-complete", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> streamCompleteVideo() {
        log.info("开始传输完整视频流");
        
        List<String> segments = chatService.getSessionVideoSegments();
        
        if (segments.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        StreamingResponseBody responseBody = outputStream -> {
            Process ffmpegProcess = null;
            File listFile = null;
            
            try {
                listFile = createFileList(segments);
                
                ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg",
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.getAbsolutePath(),
                    "-c:v", "libx264",
                    "-pix_fmt", "yuv420p",
                    "-profile:v", "baseline",
                    "-level", "3.0",
                    "-movflags", "frag_keyframe+empty_moov",
                    "-c:a", "aac",
                    "-b:a", "128k",
                    "-f", "mp4",
                    "-"
                );
                
                pb.redirectErrorStream(true);
                ffmpegProcess = pb.start();
                
                try (InputStream ffmpegOutput = ffmpegProcess.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = ffmpegOutput.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        outputStream.flush();
                    }
                }
                
            } catch (Exception e) {
                log.error("传输视频流失败", e);
            } finally {
                if (ffmpegProcess != null) {
                    ffmpegProcess.destroy();
                }
                if (listFile != null && listFile.exists()) {
                    listFile.delete();
                }
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header("Content-Disposition", "attachment; filename=\"SoulX-FlashHead.mp4\"")
                .body(responseBody);
    }

    private File createFileList(List<String> videoPaths) throws IOException {
        File tempFile = File.createTempFile("video_list_", ".txt");
        
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile))) {
            for (String path : videoPaths) {
                String escapedPath = path.replace("'", "'\\''");
                writer.write("file '" + escapedPath + "'");
                writer.newLine();
            }
        }
        
        return tempFile;
    }

    // 请求DTO
    public static class SegmentRequest {
        private String path;
        
        public String getPath() {
            return path;
        }
        
        public void setPath(String path) {
            this.path = path;
        }
    }
}
