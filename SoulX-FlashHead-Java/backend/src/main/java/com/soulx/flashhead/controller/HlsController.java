package com.soulx.flashhead.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * HLS 流控制器
 * 提供 M3U8 播放列表和 TS 片段的 HTTP 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/hls")
@CrossOrigin(origins = "*")
public class HlsController {

    private static final String BASE_DIR = "/home/yukun/SoulX-FlashHead/chat_results";

    /**
     * 获取 M3U8 播放列表 - 直接读取 Python 生成的文件
     */
    @GetMapping(value = "/{sessionId}/playlist.m3u8", produces = "application/vnd.apple.mpegurl")
    public ResponseEntity<String> getPlaylist(@PathVariable String sessionId) {
        log.debug("获取 HLS 播放列表: {}", sessionId);

        Path playlistPath = Paths.get(BASE_DIR, sessionId, "hls", "playlist.m3u8");

        if (!Files.exists(playlistPath)) {
            log.info("playlist.m3u8 不存在，返回最小有效 M3U8: sessionId={}", sessionId);
            
            // 返回最小的有效 M3U8 播放列表
            String minimalPlaylist = "#EXTM3U\n" +
                    "#EXT-X-VERSION:3\n" +
                    "#EXT-X-TARGETDURATION:10\n" +
                    "#EXT-X-MEDIA-SEQUENCE:0\n";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"));
            headers.setCacheControl(CacheControl.noCache());
            headers.setPragma("no-cache");
            
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(minimalPlaylist);
        }

        try {
            String playlistContent = Files.readString(playlistPath);
            log.debug("读取 playlist.m3u8 成功: sessionId={}, size={} bytes", sessionId, playlistContent.length());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"));
            headers.setCacheControl(CacheControl.noCache());
            headers.setPragma("no-cache");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(playlistContent);
        } catch (IOException e) {
            log.error("读取 playlist.m3u8 失败: sessionId={}, error={}", sessionId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取 TS 片段 - 直接从文件系统读取
     * 支持两种格式：
     * 1. /{sessionId}/segment_{sequence}.ts
     * 2. /{sessionId}/{sequence}.ts
     */
    @GetMapping(value = "/{sessionId}/{segmentName}.ts", produces = "video/mp2t")
    public ResponseEntity<byte[]> getSegment(
            @PathVariable String sessionId,
            @PathVariable String segmentName) {

        // 直接从文件名构造路径
        String tsFileName = segmentName + ".ts";
        Path tsPath = Paths.get(BASE_DIR, sessionId, "hls", tsFileName);

        log.debug("获取 TS 片段: sessionId={}, file={}", sessionId, tsFileName);

        if (!Files.exists(tsPath)) {
            log.warn("TS 片段不存在: sessionId={}, file={}", sessionId, tsFileName);
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] data = Files.readAllBytes(tsPath);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("video/mp2t"));
            headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(data);
        } catch (IOException e) {
            log.error("读取 TS 片段失败: sessionId={}, file={}, error={}", sessionId, tsFileName, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 接收 TS 片段上传（内部接口，供 Python 调用）
     */
    @PostMapping(value = "/{sessionId}/segment", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> uploadSegment(
            @PathVariable String sessionId,
            @RequestParam("sequence") int sequenceNumber,
            @RequestParam("duration") double duration,
            @RequestBody byte[] tsData) {

        log.debug("接收 TS 片段上传: sessionId={}, sequence={}, duration={}, size={}KB",
                sessionId, sequenceNumber, duration, tsData.length / 1024);

        // TS 文件已由 Python 直接写入文件系统，这里只记录日志即可
        // Java 后端直接从文件系统读取文件提供给前端

        return ResponseEntity.ok().build();
    }
}
