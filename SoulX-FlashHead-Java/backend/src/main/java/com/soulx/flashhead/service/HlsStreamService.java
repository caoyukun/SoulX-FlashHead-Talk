package com.soulx.flashhead.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * HLS 流服务
 * 管理 HLS 会话和 TS 片段，动态生成 M3U8 播放列表
 */
@Slf4j
@Component
public class HlsStreamService {

    private static final String BASE_DIR = "/home/yukun/SoulX-FlashHead/chat_results";

    // 会话ID -> HLS 会话（保留用于会话管理，但不存储片段数据）
    private final Map<String, HlsSession> sessions = new ConcurrentHashMap<>();

    // 清理过期会话的调度器
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    // 会话过期时间（毫秒）：30分钟
    private static final long SESSION_EXPIRE_TIME = 30 * 60 * 1000;

    // 最大保留片段数
    private static final int MAX_SEGMENTS = 100;

    public HlsStreamService() {
        // 启动定时清理任务，每5分钟执行一次
        cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * 创建新的 HLS 会话
     */
    public HlsSession createSession(String sessionId) {
        HlsSession session = new HlsSession();
        session.setSessionId(sessionId);
        session.setSegments(new ArrayList<>());
        session.setLive(true);
        session.setTargetDuration(3); // 默认目标时长 3 秒
        session.setLastActivityTime(System.currentTimeMillis());
        session.setSequenceNumber(0);

        sessions.put(sessionId, session);
        log.info("创建 HLS 会话: {}", sessionId);
        return session;
    }

    /**
     * 获取或创建会话（非递归版本）
     */
    public HlsSession getOrCreateSession(String sessionId) {
        HlsSession session = sessions.get(sessionId);
        if (session == null) {
            // 使用同步块避免递归更新
            synchronized (sessions) {
                session = sessions.get(sessionId);
                if (session == null) {
                    session = createSession(sessionId);
                }
            }
        }
        return session;
    }

    /**
     * 获取会话
     */
    public HlsSession getSession(String sessionId) {
        HlsSession session = sessions.get(sessionId);
        if (session != null) {
            session.setLastActivityTime(System.currentTimeMillis());
        }
        return session;
    }

    /**
     * 添加 TS 片段到会话
     */
    public void addSegment(String sessionId, int sequenceNumber, double duration, byte[] data) {
        log.info("准备添加 TS 片段: sessionId={}, sequence={}, duration={}, size={}KB",
                sessionId, sequenceNumber, duration, data.length / 1024);

        HlsSession session = getOrCreateSession(sessionId);

        synchronized (session) {
            TsSegment segment = new TsSegment();
            segment.setSequenceNumber(sequenceNumber);
            segment.setDuration(duration);
            segment.setData(data);
            segment.setTimestamp(System.currentTimeMillis());

            session.getSegments().add(segment);
            session.setLastActivityTime(System.currentTimeMillis());

            // 更新目标时长（取最大片段时长）
            if (duration > session.getTargetDuration()) {
                session.setTargetDuration((int) Math.ceil(duration));
            }

            // 限制片段数量，避免内存溢出
            while (session.getSegments().size() > MAX_SEGMENTS) {
                session.getSegments().remove(0);
                session.setSequenceNumber(session.getSequenceNumber() + 1);
            }

            log.info("添加 TS 片段成功: sessionId={}, sequence={}, totalSegments={}",
                    sessionId, sequenceNumber, session.getSegments().size());
        }
    }

    /**
     * 生成 M3U8 播放列表
     */
    public String generatePlaylist(String sessionId) {
        HlsSession session = getSession(sessionId);
        if (session == null) {
            log.warn("生成播放列表失败: 会话不存在, sessionId={}", sessionId);
            return null;
        }

        log.info("生成播放列表: sessionId={}, segments={}", sessionId, session.getSegments().size());

        synchronized (session) {
            StringBuilder playlist = new StringBuilder();

            // M3U8 头部
            playlist.append("#EXTM3U\n");
            playlist.append("#EXT-X-VERSION:3\n");
            playlist.append("#EXT-X-TARGETDURATION:").append(session.getTargetDuration()).append("\n");
            playlist.append("#EXT-X-MEDIA-SEQUENCE:").append(session.getSequenceNumber()).append("\n");

            // 如果是直播，添加直播标志
            if (session.isLive()) {
                playlist.append("#EXT-X-PLAYLIST-TYPE:EVENT\n");
            }

            // 片段列表
            for (TsSegment segment : session.getSegments()) {
                playlist.append("#EXTINF:").append(String.format("%.3f", segment.getDuration())).append(",\n");
                playlist.append("segment_").append(segment.getSequenceNumber()).append(".ts\n");
            }

            // 如果是直播且已结束，添加结束标记
            if (!session.isLive()) {
                playlist.append("#EXT-X-ENDLIST\n");
            }

            return playlist.toString();
        }
    }

    /**
     * 获取 TS 片段数据
     */
    public byte[] getSegmentData(String sessionId, int sequenceNumber) {
        HlsSession session = getSession(sessionId);
        if (session == null) {
            return null;
        }

        synchronized (session) {
            for (TsSegment segment : session.getSegments()) {
                if (segment.getSequenceNumber() == sequenceNumber) {
                    return segment.getData();
                }
            }
            return null;
        }
    }

    /**
     * 结束 HLS 会话（标记为不再直播）
     */
    public void endSession(String sessionId) {
        HlsSession session = sessions.get(sessionId);
        if (session != null) {
            synchronized (session) {
                session.setLive(false);
                session.setLastActivityTime(System.currentTimeMillis());
            }
            log.info("结束 HLS 会话: {}", sessionId);
        }
    }

    /**
     * 获取下一个可用的序列号 - 直接从文件系统读取
     */
    public int getNextSequenceNumber(String sessionId) {
        Path hlsDir = Paths.get(BASE_DIR, sessionId, "hls");
        
        if (!Files.exists(hlsDir)) {
            log.debug("HLS 目录不存在，返回 0: sessionId={}", sessionId);
            return 0;
        }
        
        File[] tsFiles = hlsDir.toFile().listFiles((dir, name) -> name.startsWith("segment_") && name.endsWith(".ts"));
        
        if (tsFiles == null || tsFiles.length == 0) {
            log.debug("没有找到 TS 文件，返回 0: sessionId={}", sessionId);
            return 0;
        }
        
        // 找到最大的序列号
        int maxSequence = 0;
        for (File file : tsFiles) {
            try {
                String name = file.getName();
                int startIndex = name.indexOf("segment_") + 8;
                int endIndex = name.indexOf(".ts");
                int seq = Integer.parseInt(name.substring(startIndex, endIndex));
                if (seq > maxSequence) {
                    maxSequence = seq;
                }
            } catch (Exception e) {
                log.warn("解析文件名失败: {}", file.getName());
            }
        }
        
        log.debug("获取下一个序列号: sessionId={}, max={}, next={}", sessionId, maxSequence, maxSequence + 1);
        return maxSequence + 1;
    }

    /**
     * 获取会话中的片段数量 - 直接从文件系统读取
     */
    public int getSegmentCount(String sessionId) {
        Path hlsDir = Paths.get(BASE_DIR, sessionId, "hls");
        
        if (!Files.exists(hlsDir)) {
            log.debug("HLS 目录不存在，返回 0: sessionId={}", sessionId);
            return 0;
        }
        
        File[] tsFiles = hlsDir.toFile().listFiles((dir, name) -> name.startsWith("segment_") && name.endsWith(".ts"));
        
        if (tsFiles == null) {
            log.debug("无法列出 TS 文件，返回 0: sessionId={}", sessionId);
            return 0;
        }
        
        log.debug("获取片段数量: sessionId={}, count={}", sessionId, tsFiles.length);
        return tsFiles.length;
    }
    
    /**
     * 获取当前最大的序列号 - 直接从文件系统读取
     */
    public int getMaxSequenceNumber(String sessionId) {
        Path hlsDir = Paths.get(BASE_DIR, sessionId, "hls");
        
        if (!Files.exists(hlsDir)) {
            log.debug("HLS 目录不存在，返回 -1: sessionId={}", sessionId);
            return -1;
        }
        
        File[] tsFiles = hlsDir.toFile().listFiles((dir, name) -> name.startsWith("segment_") && name.endsWith(".ts"));
        
        if (tsFiles == null || tsFiles.length == 0) {
            log.debug("没有找到 TS 文件，返回 -1: sessionId={}", sessionId);
            return -1;
        }
        
        // 找到最大的序列号
        int maxSequence = -1;
        for (File file : tsFiles) {
            try {
                String name = file.getName();
                int startIndex = name.indexOf("segment_") + 8;
                int endIndex = name.indexOf(".ts");
                int seq = Integer.parseInt(name.substring(startIndex, endIndex));
                if (seq > maxSequence) {
                    maxSequence = seq;
                }
            } catch (Exception e) {
                log.warn("解析文件名失败: {}", file.getName());
            }
        }
        
        log.debug("获取最大序列号: sessionId={}, max={}", sessionId, maxSequence);
        return maxSequence;
    }

    /**
     * 删除 HLS 会话
     */
    public void removeSession(String sessionId) {
        HlsSession removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("删除 HLS 会话: {}", sessionId);
        }
    }

    /**
     * 清理过期会话
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        List<String> expiredSessions = new ArrayList<>();

        for (Map.Entry<String, HlsSession> entry : sessions.entrySet()) {
            HlsSession session = entry.getValue();
            if (now - session.getLastActivityTime() > SESSION_EXPIRE_TIME) {
                expiredSessions.add(entry.getKey());
            }
        }

        for (String sessionId : expiredSessions) {
            removeSession(sessionId);
        }

        if (!expiredSessions.isEmpty()) {
            log.info("清理 {} 个过期 HLS 会话", expiredSessions.size());
        }
    }

    /**
     * TS 片段信息
     */
    @Data
    public static class TsSegment {
        private int sequenceNumber;  // 序列号
        private byte[] data;         // TS 数据
        private double duration;     // 片段时长（秒）
        private long timestamp;      // 生成时间戳
    }

    /**
     * HLS 会话
     */
    @Data
    public static class HlsSession {
        private String sessionId;
        private List<TsSegment> segments;  // TS 片段列表
        private boolean isLive;            // 是否正在直播
        private int targetDuration;        // #EXT-X-TARGETDURATION
        private long lastActivityTime;     // 最后活动时间
        private int sequenceNumber;        // 起始序列号（用于滑动窗口）
    }
}
