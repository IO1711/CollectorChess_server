package com.bilolbek.collectorChess.integration;

import com.bilolbek.collectorChess.api.dto.CreateMatchRequest;
import com.bilolbek.collectorChess.api.dto.JoinMatchRequest;
import com.bilolbek.collectorChess.api.dto.MatchConnectResponse;
import com.bilolbek.collectorChess.domain.model.Contracts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MatchWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void websocketReceivesSnapshotOnConnectAndMatchJoinBroadcast() throws Exception {
        UUID whiteGuestId = UUID.randomUUID();
        UUID blackGuestId = UUID.randomUUID();
        HttpClient httpClient = HttpClient.newHttpClient();

        MatchConnectResponse createBody = postJson(
                httpClient,
                "/v1/matches",
                new CreateMatchRequest(whiteGuestId, "Socket Host"),
                MatchConnectResponse.class
        );

        assertThat(createBody).isNotNull();

        MessageCollector collector = new MessageCollector();
        WebSocket socket = httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(createBody.webSocketURL()), collector)
                .join();

        Contracts.OnlineMatchEvent initialSnapshot = collector.awaitEvent(objectMapper);
        assertThat(initialSnapshot.type()).isEqualTo(Contracts.EventType.SNAPSHOT);
        assertThat(initialSnapshot.snapshot().roomCode()).isEqualTo(createBody.snapshot().roomCode());

        MatchConnectResponse joinBody = postJson(
                httpClient,
                "/v1/matches/join",
                new JoinMatchRequest(createBody.snapshot().roomCode(), blackGuestId, "Socket Joiner"),
                MatchConnectResponse.class
        );

        assertThat(joinBody).isNotNull();
        Contracts.OnlineMatchEvent joinBroadcast = collector.awaitEvent(objectMapper);
        assertThat(joinBroadcast.type()).isEqualTo(Contracts.EventType.SNAPSHOT);
        assertThat(joinBroadcast.snapshot().revision()).isEqualTo(2);
        assertThat(joinBroadcast.snapshot().phase()).isEqualTo(Contracts.MatchPhase.DRAFTING);
        assertThat(joinBroadcast.snapshot().seats()).hasSize(2);

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private <T> T postJson(HttpClient httpClient, String path, Object requestBody, Class<T> responseType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readValue(response.body(), responseType);
    }

    private static final class MessageCollector implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder currentMessage = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            currentMessage.append(data);
            if (last) {
                messages.add(currentMessage.toString());
                currentMessage.setLength(0);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        Contracts.OnlineMatchEvent awaitEvent(ObjectMapper objectMapper) throws Exception {
            String payload = messages.poll(5, TimeUnit.SECONDS);
            assertThat(payload).as("websocket payload").isNotNull();
            return objectMapper.readValue(payload, Contracts.OnlineMatchEvent.class);
        }
    }
}
