package com.soulx.flashhead.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final DoubaoWebSocketHandler doubaoWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler, DoubaoWebSocketHandler doubaoWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.doubaoWebSocketHandler = doubaoWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws")
                .setAllowedOriginPatterns("*");
        registry.addHandler(doubaoWebSocketHandler, "/ws/doubao")
                .setAllowedOriginPatterns("*");
    }
}
