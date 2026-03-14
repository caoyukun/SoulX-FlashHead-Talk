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
    public static class Video {
        private String storagePath = "./chat_results";
    }
}
