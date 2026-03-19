package com.soulx.flashhead.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class DoubaoStartSessionRequest {
    @JsonProperty("user")
    private User user;

    @JsonProperty("request")
    private Request request;

    @Data
    public static class User {
        @JsonProperty("uid")
        private String uid;
    }

    @Data
    public static class Request {
        @JsonProperty("model")
        private String model;

        @JsonProperty("audio")
        private Audio audio;

        @JsonProperty("tts")
        private Tts tts;

        @Data
        public static class Audio {
            @JsonProperty("format")
            private String format = "pcm";

            @JsonProperty("sample_rate")
            private int sampleRate = 16000;

            @JsonProperty("channel")
            private int channel = 1;
        }

        @Data
        public static class Tts {
            @JsonProperty("audio_config")
            private AudioConfig audioConfig;

            @JsonProperty("speaker")
            private String speaker;

            @Data
            public static class AudioConfig {
                @JsonProperty("format")
                private String format;

                @JsonProperty("sample_rate")
                private int sampleRate = 24000;

                @JsonProperty("channel")
                private int channel = 1;
            }
        }
    }
}
