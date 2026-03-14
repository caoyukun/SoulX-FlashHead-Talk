package com.soulx.flashhead.controller;

import com.soulx.flashhead.service.ChatService;
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

    public VideoStreamController(ChatService chatService) {
        this.chatService = chatService;
    }

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
                    "-f", "concat",
                    "-safe", "0",
                    "-i", listFile.getAbsolutePath(),
                    "-c", "copy",
                    "-f", "mp4",
                    "-movflags", "frag_keyframe+empty_moov",
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
}
