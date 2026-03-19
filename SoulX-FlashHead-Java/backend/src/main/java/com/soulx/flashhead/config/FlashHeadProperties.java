package com.soulx.flashhead.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "flashhead")
public class FlashHeadProperties {
    private PythonService pythonService = new PythonService();
    private Volcengine volcengine = new Volcengine();
    private Doubao doubao = new Doubao();
    private Video video = new Video();

    @Data
    public static class PythonService {
        private String url = "http://localhost:5000";
    }

    @Data
    public static class Volcengine {
        private String apiUrl = "https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions";
        private String apiKey = "";
    }

    @Data
    public static class Doubao {
        private String apiUrl = "wss://openspeech.bytedance.com/api/v1/ai_voice_assistant/chat";
        private String apiKey = "";
        private String appId = "";
        private String modelVersion = "o2.0";
        private String speaker = "zh_female_vv_jupiter_bigtts";
        private String audioFormat = "pcm_s16le";
    }

    @Data
    public static class Video {
        private String storagePath = "./chat_results";
    }
}
