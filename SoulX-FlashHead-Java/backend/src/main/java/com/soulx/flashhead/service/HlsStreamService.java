package com.soulx.flashhead.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    // 会话ID -> HLS 会话
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
        log.info("生成播放列表: sessionId={}", sessionId);

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
     * 获取下一个可用的序列号
     */
    public int getNextSequenceNumber(String sessionId) {
        HlsSession session = getSession(sessionId);
        if (session == null || session.getSegments().isEmpty()) {
            return 0;
        }
        // 获取最后一个片段的序列号并加 1
        TsSegment lastSegment = session.getSegments().get(session.getSegments().size() - 1);
        return lastSegment.getSequenceNumber() + 1;
    }

    /**
     * 获取会话中的片段数量
     */
    public int getSegmentCount(String sessionId) {
        HlsSession session = getSession(sessionId);
        if (session == null) {
            return 0;
        }
        synchronized (session) {
            return session.getSegments().size();
        }
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
