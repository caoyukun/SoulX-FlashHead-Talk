package com.soulx.flashhead.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.soulx.flashhead.config.FlashHeadProperties;
import com.soulx.flashhead.model.DoubaoStartSessionRequest;
import jakarta.websocket.*;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.tyrus.client.ClientManager;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

@Slf4j
@Component
public class DoubaoRealtimeClient {

    private final FlashHeadProperties properties;
    private final ObjectMapper objectMapper;

    private Session session;
    private Consumer<byte[]> audioDataConsumer;
    private Consumer<String> textDataConsumer;
    private Consumer<Exception> errorConsumer;
    private final BlockingQueue<byte[]> audioSendQueue = new LinkedBlockingQueue<>();
    private volatile boolean isConnected = false;
    private Thread audioSendThread;

    public DoubaoRealtimeClient(FlashHeadProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    public void setAudioDataConsumer(Consumer<byte[]> consumer) {
        this.audioDataConsumer = consumer;
    }

    public void setTextDataConsumer(Consumer<String> consumer) {
        this.textDataConsumer = consumer;
    }

    public void setErrorConsumer(Consumer<Exception> consumer) {
        this.errorConsumer = consumer;
    }

    public void connect() throws Exception {
        if (isConnected) {
            log.warn("Already connected to Doubao API");
            return;
        }

        ClientManager client = ClientManager.createClient();
        String apiUrl = properties.getDoubao().getApiUrl();
        String apiKey = properties.getDoubao().getApiKey();
        String appId = properties.getDoubao().getAppId();

        URI uri = URI.create(apiUrl + "?appid=" + appId + "&token=" + apiKey);

        log.info("Connecting to Doubao API: {}", apiUrl);

        session = client.connectToServer(new Endpoint() {
            @Override
            public void onOpen(Session session, EndpointConfig config) {
                log.info("Connected to Doubao API");
                isConnected = true;
                
                try {
                    sendStartSession();
                    startAudioSendThread();
                } catch (Exception e) {
                    log.error("Failed to send start session", e);
                    handleError(e);
                }
            }

            @Override
            public void onClose(Session session, CloseReason closeReason) {
                log.info("Disconnected from Doubao API: {}", closeReason);
                isConnected = false;
                stopAudioSendThread();
            }

            @Override
            public void onError(Session session, Throwable throwable) {
                log.error("Doubao API error", throwable);
                handleError(new Exception(throwable));
            }
        }, uri);

        session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String message) {
                handleTextMessage(message);
            }
        });

        session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {
            @Override
            public void onMessage(ByteBuffer message) {
                handleBinaryMessage(message);
            }
        });
    }

    private void sendStartSession() throws IOException {
        DoubaoStartSessionRequest request = new DoubaoStartSessionRequest();
        
        DoubaoStartSessionRequest.User user = new DoubaoStartSessionRequest.User();
        user.setUid(UUID.randomUUID().toString());
        request.setUser(user);

        DoubaoStartSessionRequest.Request req = new DoubaoStartSessionRequest.Request();
        req.setModel(properties.getDoubao().getModelVersion());

        DoubaoStartSessionRequest.Request.Audio audio = new DoubaoStartSessionRequest.Request.Audio();
        req.setAudio(audio);

        DoubaoStartSessionRequest.Request.Tts tts = new DoubaoStartSessionRequest.Request.Tts();
        DoubaoStartSessionRequest.Request.Tts.AudioConfig audioConfig = new DoubaoStartSessionRequest.Request.Tts.AudioConfig();
        audioConfig.setFormat(properties.getDoubao().getAudioFormat());
        tts.setAudioConfig(audioConfig);
        tts.setSpeaker(properties.getDoubao().getSpeaker());
        req.setTts(tts);

        request.setRequest(req);

        String json = objectMapper.writeValueAsString(request);
        log.info("Sending StartSession: {}", json);
        
        session.getBasicRemote().sendText(json);
    }

    private void startAudioSendThread() {
        audioSendThread = new Thread(() -> {
            while (isConnected && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] audioData = audioSendQueue.take();
                    if (isConnected && session != null && session.isOpen()) {
                        session.getBasicRemote().sendBinary(ByteBuffer.wrap(audioData));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Failed to send audio data", e);
                }
            }
        }, "DoubaoAudioSendThread");
        audioSendThread.setDaemon(true);
        audioSendThread.start();
    }

    private void stopAudioSendThread() {
        if (audioSendThread != null && audioSendThread.isAlive()) {
            audioSendThread.interrupt();
        }
    }

    public void sendAudio(byte[] audioData) {
        if (!isConnected) {
            log.warn("Not connected to Doubao API, cannot send audio");
            return;
        }
        audioSendQueue.offer(audioData);
    }

    private void handleTextMessage(String message) {
        log.debug("Received text message: {}", message);
        if (textDataConsumer != null) {
            textDataConsumer.accept(message);
        }
    }

    private void handleBinaryMessage(ByteBuffer message) {
        byte[] data = new byte[message.remaining()];
        message.get(data);
        log.debug("Received binary message, size: {} bytes", data.length);
        
        if (audioDataConsumer != null) {
            audioDataConsumer.accept(data);
        }
    }

    private void handleError(Exception e) {
        if (errorConsumer != null) {
            errorConsumer.accept(e);
        }
    }

    public void disconnect() {
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.error("Error closing session", e);
            }
        }
        isConnected = false;
    }

    public boolean isConnected() {
        return isConnected;
    }
}
