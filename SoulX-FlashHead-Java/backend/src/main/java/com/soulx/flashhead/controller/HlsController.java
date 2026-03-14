package com.soulx.flashhead.controller;

import com.soulx.flashhead.service.HlsStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    private final HlsStreamService hlsStreamService;

    public HlsController(HlsStreamService hlsStreamService) {
        this.hlsStreamService = hlsStreamService;
    }

    /**
     * 获取 M3U8 播放列表
     */
    @GetMapping(value = "/{sessionId}/playlist.m3u8", produces = "application/vnd.apple.mpegurl")
    public ResponseEntity<String> getPlaylist(@PathVariable String sessionId) {
        log.debug("获取 HLS 播放列表: {}", sessionId);

        String playlist = hlsStreamService.generatePlaylist(sessionId);

        if (playlist == null) {
            log.warn("HLS 会话不存在: {}", sessionId);
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"));
        // 禁用缓存，确保播放器获取最新播放列表
        headers.setCacheControl(CacheControl.noCache());
        headers.setPragma("no-cache");

        return ResponseEntity.ok()
                .headers(headers)
                .body(playlist);
    }

    /**
     * 获取 TS 片段
     * 支持两种格式：
     * 1. /{sessionId}/segment_{sequence}.ts
     * 2. /{sessionId}/{sequence}.ts
     */
    @GetMapping(value = "/{sessionId}/{segmentName}.ts", produces = "video/mp2t")
    public ResponseEntity<byte[]> getSegment(
            @PathVariable String sessionId,
            @PathVariable String segmentName) {

        // 解析序列号
        int sequenceNumber = parseSequenceNumber(segmentName);
        if (sequenceNumber < 0) {
            log.warn("无效的 TS 片段名称: {}", segmentName);
            return ResponseEntity.badRequest().build();
        }

        log.debug("获取 TS 片段: sessionId={}, sequence={}", sessionId, sequenceNumber);

        byte[] data = hlsStreamService.getSegmentData(sessionId, sequenceNumber);

        if (data == null) {
            log.warn("TS 片段不存在: sessionId={}, sequence={}", sessionId, sequenceNumber);
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("video/mp2t"));
        // TS 片段可以缓存较长时间
        headers.setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS));

        return ResponseEntity.ok()
                .headers(headers)
                .body(data);
    }

    /**
     * 接收 FFmpeg 推送的 TS 片段（内部接口）
     */
    @PostMapping(value = "/{sessionId}/segment", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Void> uploadSegment(
            @PathVariable String sessionId,
            @RequestParam("sequence") int sequenceNumber,
            @RequestParam("duration") double duration,
            @RequestBody byte[] tsData) {

        log.debug("接收 TS 片段: sessionId={}, sequence={}, duration={}, size={}KB",
                sessionId, sequenceNumber, duration, tsData.length / 1024);

        hlsStreamService.addSegment(sessionId, sequenceNumber, duration, tsData);

        return ResponseEntity.ok().build();
    }

    /**
     * 结束 HLS 流
     */
    @PostMapping("/{sessionId}/end")
    public ResponseEntity<Void> endStream(@PathVariable String sessionId) {
        log.info("结束 HLS 流: {}", sessionId);

        hlsStreamService.endSession(sessionId);

        return ResponseEntity.ok().build();
    }

    /**
     * 创建 HLS 会话（可选，用于预创建会话）
     */
    @PostMapping("/{sessionId}/create")
    public ResponseEntity<Void> createSession(@PathVariable String sessionId) {
        log.info("创建 HLS 会话: {}", sessionId);

        hlsStreamService.createSession(sessionId);

        return ResponseEntity.ok().build();
    }

    /**
     * 解析 TS 片段名称中的序列号
     */
    private int parseSequenceNumber(String segmentName) {
        try {
            // 处理 segment_00001.ts 格式
            if (segmentName.startsWith("segment_")) {
                String numberPart = segmentName.substring(8); // 去掉 "segment_"
                if (numberPart.contains(".")) {
                    numberPart = numberPart.substring(0, numberPart.indexOf('.'));
                }
                return Integer.parseInt(numberPart);
            }

            // 处理 00001.ts 格式
            if (segmentName.contains(".")) {
                String numberPart = segmentName.substring(0, segmentName.indexOf('.'));
                return Integer.parseInt(numberPart);
            }

            // 纯数字格式
            return Integer.parseInt(segmentName);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
