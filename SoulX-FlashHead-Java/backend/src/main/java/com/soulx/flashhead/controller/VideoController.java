package com.soulx.flashhead.controller;

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

@Slf4j
@RestController
@RequestMapping("/api/video")
@CrossOrigin(origins = "*")
public class VideoController {

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
}
