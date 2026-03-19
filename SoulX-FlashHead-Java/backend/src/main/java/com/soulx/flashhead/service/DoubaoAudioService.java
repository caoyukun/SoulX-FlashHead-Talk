package com.soulx.flashhead.service;

import com.soulx.flashhead.client.DoubaoRealtimeClient;
import com.soulx.flashhead.client.PythonServiceClient;
import com.soulx.flashhead.config.FlashHeadProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Service
public class DoubaoAudioService {

    private final DoubaoRealtimeClient doubaoClient;
    private final PythonServiceClient pythonServiceClient;
    private final FlashHeadProperties properties;
    
    private final BlockingQueue<byte[]> audioBuffer = new LinkedBlockingQueue<>();
    private volatile boolean isProcessing = false;
    private Thread processingThread;
    private ByteArrayOutputStream currentAudioStream;
    private static final int AUDIO_BUFFER_SIZE = 16000 * 2 * 5; // 5秒的PCM 16kHz 16bit音频
    
    public DoubaoAudioService(DoubaoRealtimeClient doubaoClient,
                               PythonServiceClient pythonServiceClient,
                               FlashHeadProperties properties) {
        this.doubaoClient = doubaoClient;
        this.pythonServiceClient = pythonServiceClient;
        this.properties = properties;
        
        setupConsumers();
    }
    
    private void setupConsumers() {
        doubaoClient.setAudioDataConsumer(this::handleReceivedAudio);
        doubaoClient.setTextDataConsumer(this::handleReceivedText);
        doubaoClient.setErrorConsumer(this::handleError);
    }
    
    public void startSession() throws Exception {
        log.info("Starting Doubao session");
        currentAudioStream = new ByteArrayOutputStream();
        doubaoClient.connect();
        startProcessingThread();
    }
    
    public void sendUserAudio(byte[] audioData) {
        if (doubaoClient.isConnected()) {
            doubaoClient.sendAudio(audioData);
        } else {
            log.warn("Doubao client not connected, cannot send audio");
        }
    }
    
    private void handleReceivedAudio(byte[] audioData) {
        log.debug("Received audio chunk: {} bytes", audioData.length);
        try {
            byte[] convertedAudio = convertAudioFormat(audioData);
            audioBuffer.offer(convertedAudio);
            
            if (currentAudioStream != null) {
                currentAudioStream.write(convertedAudio);
            }
        } catch (Exception e) {
            log.error("Error handling received audio", e);
        }
    }
    
    private void handleReceivedText(String text) {
        log.info("Received text: {}", text);
    }
    
    private void handleError(Exception e) {
        log.error("Doubao client error", e);
    }
    
    private byte[] convertAudioFormat(byte[] inputAudio) throws Exception {
        String audioFormat = properties.getDoubao().getAudioFormat();
        
        if ("pcm_s16le".equals(audioFormat)) {
            return resamplePcm(inputAudio, 24000, 16000);
        } else if ("pcm".equals(audioFormat)) {
            return resamplePcm(inputAudio, 24000, 16000);
        } else {
            throw new UnsupportedOperationException("Unsupported audio format: " + audioFormat);
        }
    }
    
    private byte[] resamplePcm(byte[] pcmData, int srcSampleRate, int dstSampleRate) {
        int srcBytesPerSample = 2;
        int srcChannels = 1;
        int dstBytesPerSample = 2;
        int dstChannels = 1;
        
        int srcSampleCount = pcmData.length / (srcBytesPerSample * srcChannels);
        double resampleRatio = (double) dstSampleRate / srcSampleRate;
        int dstSampleCount = (int) (srcSampleCount * resampleRatio);
        
        ByteBuffer srcBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dstBuffer = ByteBuffer.allocate(dstSampleCount * dstBytesPerSample * dstChannels)
                                         .order(ByteOrder.LITTLE_ENDIAN);
        
        short[] srcSamples = new short[srcSampleCount];
        for (int i = 0; i < srcSampleCount; i++) {
            srcSamples[i] = srcBuffer.getShort();
        }
        
        short[] dstSamples = new short[dstSampleCount];
        for (int i = 0; i < dstSampleCount; i++) {
            double srcIndex = i / resampleRatio;
            int lower = (int) Math.floor(srcIndex);
            int upper = Math.min(lower + 1, srcSampleCount - 1);
            double fraction = srcIndex - lower;
            
            if (lower >= 0 && upper < srcSampleCount) {
                dstSamples[i] = (short) (srcSamples[lower] * (1 - fraction) + srcSamples[upper] * fraction);
            } else if (lower >= 0) {
                dstSamples[i] = srcSamples[lower];
            }
        }
        
        for (short sample : dstSamples) {
            dstBuffer.putShort(sample);
        }
        
        return dstBuffer.array();
    }
    
    private void startProcessingThread() {
        if (isProcessing) {
            return;
        }
        
        isProcessing = true;
        processingThread = new Thread(() -> {
            log.info("Audio processing thread started");
            while (isProcessing && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] audioChunk = audioBuffer.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (audioChunk != null) {
                        processAudioChunk(audioChunk);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error processing audio chunk", e);
                }
            }
            log.info("Audio processing thread stopped");
        }, "DoubaoAudioProcessingThread");
        processingThread.setDaemon(true);
        processingThread.start();
    }
    
    private void processAudioChunk(byte[] audioChunk) {
        log.debug("Processing audio chunk: {} bytes", audioChunk.length);
    }
    
    public File getCurrentAudioFile() throws IOException {
        if (currentAudioStream == null || currentAudioStream.size() == 0) {
            return null;
        }
        
        byte[] audioData = currentAudioStream.toByteArray();
        File tempFile = File.createTempFile("doubao_audio_", ".wav");
        tempFile.deleteOnExit();
        
        saveToWavFile(audioData, tempFile, 16000, 16, 1);
        return tempFile;
    }
    
    private void saveToWavFile(byte[] pcmData, File file, int sampleRate, int bitsPerSample, int channels) throws IOException {
        AudioFormat format = new AudioFormat(sampleRate, bitsPerSample, channels, true, false);
        ByteArrayInputStream bais = new ByteArrayInputStream(pcmData);
        AudioInputStream ais = new AudioInputStream(bais, format, pcmData.length / format.getFrameSize());
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, file);
    }
    
    public void endSession() {
        log.info("Ending Doubao session");
        isProcessing = false;
        if (processingThread != null && processingThread.isAlive()) {
            processingThread.interrupt();
        }
        doubaoClient.disconnect();
        
        if (currentAudioStream != null) {
            try {
                currentAudioStream.close();
            } catch (IOException e) {
                log.error("Error closing audio stream", e);
            }
            currentAudioStream = null;
        }
    }
}
