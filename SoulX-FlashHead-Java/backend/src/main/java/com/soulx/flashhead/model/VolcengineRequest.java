package com.soulx.flashhead.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class VolcengineRequest {
    private String model = "ark-code-latest";
    private List<Message> messages = new ArrayList<>();
    private int maxTokens = 1000;
    private double temperature = 0.7;

    @Data
    public static class Message {
        private String role;
        private String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
