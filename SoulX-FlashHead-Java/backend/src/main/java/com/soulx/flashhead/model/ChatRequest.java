package com.soulx.flashhead.model;

import lombok.Data;

@Data
public class ChatRequest {
    private String message;
    private String apiKey;
    private String condImage;
    private String ckptDir;
    private String wav2vecDir;
    private String modelType;
    private Integer seed;
    private Boolean useFaceCrop;
    private Double duration;  // 用于空闲视频生成时长
}
