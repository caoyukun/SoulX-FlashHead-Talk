package com.soulx.flashhead.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulx.flashhead.config.FlashHeadProperties;
import com.soulx.flashhead.model.ChatMessage;
import com.soulx.flashhead.model.VolcengineRequest;
import com.soulx.flashhead.model.VolcengineResponse;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VolcengineClient {
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FlashHeadProperties properties;

    public VolcengineClient(FlashHeadProperties properties) {
        this.properties = properties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String getChatResponse(String userMessage, String apiKey, List<ChatMessage> chatHistory) throws IOException {
        VolcengineRequest request = new VolcengineRequest();
        
        String systemPrompt = "你是一个友好、智能的数字人助手。请用自然、亲切的语气回答用户的问题，回复要简洁明了。";
        request.getMessages().add(new VolcengineRequest.Message("system", systemPrompt));
        
        int historyCount = Math.min(chatHistory.size(), 10);
        for (int i = chatHistory.size() - historyCount; i < chatHistory.size(); i++) {
            ChatMessage msg = chatHistory.get(i);
            request.getMessages().add(new VolcengineRequest.Message("user", msg.getUser()));
            request.getMessages().add(new VolcengineRequest.Message("assistant", msg.getAssistant()));
        }
        
        request.getMessages().add(new VolcengineRequest.Message("user", userMessage));

        String jsonBody = objectMapper.writeValueAsString(request);
        
        Request httpRequest = new Request.Builder()
                .url(properties.getVolcengine().getApiUrl())
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Volcengine API call failed: {}", errorBody);
                throw new IOException("Volcengine API call failed: " + response.code());
            }

            String responseBody = response.body().string();
            VolcengineResponse volcengineResponse = objectMapper.readValue(responseBody, VolcengineResponse.class);
            
            if (volcengineResponse.getChoices() != null && !volcengineResponse.getChoices().isEmpty()) {
                return volcengineResponse.getChoices().get(0).getMessage().getContent();
            }
            
            throw new IOException("Invalid response from Volcengine API");
        }
    }
}
