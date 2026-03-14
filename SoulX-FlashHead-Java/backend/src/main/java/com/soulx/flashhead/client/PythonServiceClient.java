package com.soulx.flashhead.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulx.flashhead.config.FlashHeadProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PythonServiceClient {
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final FlashHeadProperties properties;

    public PythonServiceClient(FlashHeadProperties properties) {
        this.properties = properties;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public boolean isHealthy() {
        try {
            Request request = new Request.Builder()
                    .url(properties.getPythonService().getUrl() + "/health")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (IOException e) {
            log.error("Python service health check failed", e);
            return false;
        }
    }

    public File textToSpeech(String text) throws IOException {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("text", text);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(properties.getPythonService().getUrl() + "/tts")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("TTS failed: " + response.code());
            }

            File tempFile = File.createTempFile("tts_", ".wav");
            Files.copy(response.body().byteStream(), tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile;
        }
    }

    public void initializeModel(String condImage, String ckptDir, String wav2vecDir, 
                                 String modelType, int seed, boolean useFaceCrop) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("cond_image", condImage);
        requestBody.put("ckpt_dir", ckptDir);
        requestBody.put("wav2vec_dir", wav2vecDir);
        requestBody.put("model_type", modelType);
        requestBody.put("seed", seed);
        requestBody.put("use_face_crop", useFaceCrop);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(properties.getPythonService().getUrl() + "/initialize")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Model initialization failed: {}", errorBody);
                throw new IOException("Model initialization failed: " + response.code());
            }
        }
    }

    public void setCallbackUrl(String callbackUrl) throws IOException {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("url", callbackUrl);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(properties.getPythonService().getUrl() + "/set-callback")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Setting callback URL failed: {}", errorBody);
                throw new IOException("Setting callback URL failed: " + response.code());
            }
            log.info("Callback URL set successfully: {}", callbackUrl);
        }
    }
    
    public void generateVideoStreamingWithCallback(File audioFile, String condImage, 
                                                   String ckptDir, String wav2vecDir, 
                                                   String modelType, int seed, boolean useFaceCrop,
                                                   String streamId) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("audio_path", audioFile.getAbsolutePath());
        requestBody.put("cond_image", condImage);
        requestBody.put("ckpt_dir", ckptDir);
        requestBody.put("wav2vec_dir", wav2vecDir);
        requestBody.put("model_type", modelType);
        requestBody.put("seed", seed);
        requestBody.put("use_face_crop", useFaceCrop);
        requestBody.put("stream_id", streamId);
        requestBody.put("use_streaming_callback", true);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(properties.getPythonService().getUrl() + "/generate-video-streaming")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("Video generation failed asynchronously", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                        log.error("Video generation failed: {}", errorBody);
                    } else {
                        log.info("Video generation completed successfully");
                    }
                } finally {
                    response.close();
                }
            }
        });
    }
    
    public Map<String, Object> generateVideoStreaming(File audioFile, String condImage, 
                                                       String ckptDir, String wav2vecDir, 
                                                       String modelType, int seed, boolean useFaceCrop,
                                                       String streamId) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("audio_path", audioFile.getAbsolutePath());
        requestBody.put("cond_image", condImage);
        requestBody.put("ckpt_dir", ckptDir);
        requestBody.put("wav2vec_dir", wav2vecDir);
        requestBody.put("model_type", modelType);
        requestBody.put("seed", seed);
        requestBody.put("use_face_crop", useFaceCrop);
        requestBody.put("stream_id", streamId);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(properties.getPythonService().getUrl() + "/generate-video-streaming")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Video generation failed: {}", errorBody);
                throw new IOException("Video generation failed: " + response.code());
            }

            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, Map.class);
        }
    }

    public String generateIdleVideo(String condImage, String ckptDir, String wav2vecDir,
                                     String modelType, int seed, boolean useFaceCrop,
                                     double duration) throws IOException {
        return generateIdleVideo(condImage, ckptDir, wav2vecDir, modelType, seed, useFaceCrop, duration, false, null);
    }
    
    public String generateIdleVideo(String condImage, String ckptDir, String wav2vecDir,
                                     String modelType, int seed, boolean useFaceCrop,
                                     double duration, boolean useStreamingCallback, String streamId) throws IOException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("duration", duration);
        requestBody.put("cond_image", condImage);
        requestBody.put("ckpt_dir", ckptDir);
        requestBody.put("wav2vec_dir", wav2vecDir);
        requestBody.put("model_type", modelType);
        requestBody.put("seed", seed);
        requestBody.put("use_face_crop", useFaceCrop);
        requestBody.put("use_streaming_callback", useStreamingCallback);
        if (streamId != null) {
            requestBody.put("stream_id", streamId);
        }

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        Request request = new Request.Builder()
                .url(properties.getPythonService().getUrl() + "/generate-idle-video")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("Idle video generation failed: {}", errorBody);
                throw new IOException("Idle video generation failed: " + response.code());
            }

            String responseBody = response.body().string();
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            if (useStreamingCallback) {
                return (String) result.get("stream_id");
            } else {
                return (String) result.get("video_path");
            }
        }
    }
}
