package com.bilolbek.collectorChess.config;

import com.bilolbek.collectorChess.realtime.MatchLiveSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MatchLiveSocketHandler matchLiveSocketHandler;

    public WebSocketConfig(MatchLiveSocketHandler matchLiveSocketHandler) {
        this.matchLiveSocketHandler = matchLiveSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(matchLiveSocketHandler, "/v1/matches/*/live")
                .setAllowedOrigins("*");
    }
}
