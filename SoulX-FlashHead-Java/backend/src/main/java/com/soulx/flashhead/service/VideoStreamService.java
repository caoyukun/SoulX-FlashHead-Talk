package com.soulx.flashhead.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * 实时视频流服务
 * 使用FFmpeg实时合并视频段，提供连续的视频流
 */
@Slf4j
@Service
public class VideoStreamService {
    
    private final BlockingQueue<String> videoSegmentQueue = new LinkedBlockingQueue<>();
    private volatile boolean isStreaming = false;
    private volatile OutputStream currentOutputStream = null;

    /**
     * 开始视频流
     */
    public void startStream(OutputStream outputStream) {
        if (isStreaming) {
            log.warn("视频流已在运行中");
            return;
        }
        
        isStreaming = true;
        currentOutputStream = outputStream;
        videoSegmentQueue.clear();
        
        log.info("视频流已启动，等待视频段...");
        
        try {
            // 等待第一个视频段
            String firstSegment = videoSegmentQueue.poll(30, TimeUnit.SECONDS);
            if (firstSegment == null) {
                log.warn("30秒内没有收到视频段，停止流");
                isStreaming = false;
                return;
            }
            
            // 收集所有视频段
            StringBuilder fileList = new StringBuilder();
            fileList.append("file '").append(firstSegment).append("'\n");
            
            // 继续收集后续视频段（最多等待5秒）
            while (isStreaming) {
                String segment = videoSegmentQueue.poll(5, TimeUnit.SECONDS);
                if (segment != null) {
                    fileList.append("file '").append(segment).append("'\n");
                } else {
                    // 5秒内没有新视频段，开始合并
                    break;
                }
            }
            
            if (!isStreaming) {
                return;
            }
            
            // 创建临时文件列表
            File listFile = File.createTempFile("video_list_", ".txt");
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(listFile))) {
                writer.write(fileList.toString());
            }
            
            log.info("开始合并 {} 个视频段", fileList.toString().split("\n").length);
            
            // 使用FFmpeg合并视频
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
            Process ffmpegProcess = pb.start();
            
            try (InputStream ffmpegOutput = ffmpegProcess.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = ffmpegOutput.read(buffer)) != -1 && isStreaming) {
                    outputStream.write(buffer, 0, bytesRead);
                    outputStream.flush();
                }
            }
            
            ffmpegProcess.destroy();
            listFile.delete();
            
            log.info("视频流传输完成");
            
        } catch (InterruptedException e) {
            log.info("视频流线程被中断");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("视频流处理错误", e);
        } finally {
            isStreaming = false;
            currentOutputStream = null;
        }
    }
    
    /**
     * 添加视频段到流
     */
    public void addSegment(String videoPath) {
        try {
            videoSegmentQueue.put(videoPath);
            log.info("视频段已加入队列: {}", videoPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("添加视频段失败", e);
        }
    }
    
    /**
     * 停止视频流
     */
    public void stopStream() {
        isStreaming = false;
        log.info("视频流已停止");
    }
    
    /**
     * 检查是否正在流式传输
     */
    public boolean isStreaming() {
        return isStreaming;
    }
}
