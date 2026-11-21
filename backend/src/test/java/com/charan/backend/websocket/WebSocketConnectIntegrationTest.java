package com.charan.backend.websocket;

import com.charan.backend.auth.AuthResponse;
import org.springframework.web.socket.WebSocketHttpHeaders;
import com.charan.backend.auth.LoginRequest;
import com.charan.backend.auth.SignupRequest;
import com.charan.backend.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.WebSocketHttpHeaders;
import com.charan.backend.channel.ChannelMemberRepository;
import com.charan.backend.channel.ChannelRepository;
import com.charan.backend.invite.WorkspaceInviteRepository;
import com.charan.backend.message.MessageRepository;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.workspace.WorkspaceRepository;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.*;

public class WebSocketConnectIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ChannelMemberRepository channelMemberRepository;
    @Autowired
    private ChannelRepository channelRepository;
    @Autowired
    private WorkspaceInviteRepository workspaceInviteRepository;
    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private UserRepository userRepository;

    private String token;
    private String userId; // signed‑up user's id as String

    @BeforeEach
    public void setUp() {
        messageRepository.deleteAll();
        channelMemberRepository.deleteAll();
        channelRepository.deleteAll();
        workspaceInviteRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
        String base = "http://localhost:" + port + "/api";
        // Sign up a user
        SignupRequest signup = new SignupRequest("alice@example.com", "password123", "Alice");
        var signupResp = restTemplate.postForEntity(base + "/auth/signup", signup,
                com.charan.backend.auth.UserResponse.class);
        assertEquals(201, signupResp.getStatusCode().value());
        userId = String.valueOf(signupResp.getBody().id());
        // Login to obtain JWT
        LoginRequest login = new LoginRequest("alice@example.com", "password123");
        AuthResponse auth = restTemplate.postForObject(base + "/auth/login", login, AuthResponse.class);
        token = auth.token();
    }

    private CompletableFuture<StompSession> connectWithHeaders(StompHeaders headers) {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        client.setTaskScheduler(scheduler);
        return client.connectAsync("ws://localhost:" + port + "/ws",
                new org.springframework.web.socket.WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                    }

                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return byte[].class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                    }
                });
    }

    @Test
    public void validTokenConnectsAndReceivesPrincipal() throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        StompSession session = connectWithHeaders(headers).get(5, TimeUnit.SECONDS);
        assertNotNull(session);
        // Subscribe to a user‑specific destination where the test controller will echo
        // the principal
        CompletableFuture<String> result = new CompletableFuture<>();
        session.subscribe("/user/queue/whoami", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                result.complete((String) payload);
            }
        });
        // Trigger server to send principal
        session.send("/app/whoami", "");
        String principal = result.get(5, TimeUnit.SECONDS);
        assertEquals(userId, principal);
    }

    private void assertConnectionRejected(StompHeaders headers) {
        try {
            CompletableFuture<StompSession> cf = connectWithHeaders(headers);
            cf.get(5, TimeUnit.SECONDS);
            fail("Connection should have been rejected");
        } catch (Exception e) {
            // Expected: timeout, ExecutionException, or connection closed
            assertTrue(true);
        }
    }

    @Test
    public void missingAuthorizationHeaderRejected() {
        assertConnectionRejected(new StompHeaders());
    }

    @Test
    public void malformedHeaderRejected() {
        StompHeaders h = new StompHeaders();
        h.add("Authorization", "Token abc");
        assertConnectionRejected(h);
    }

    @Test
    public void bearerWithoutTokenRejected() {
        StompHeaders h = new StompHeaders();
        h.add("Authorization", "Bearer ");
        assertConnectionRejected(h);
    }

    @Test
    public void tamperedTokenRejected() {
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "Token format unexpected");
        char[] sig = parts[2].toCharArray();
        int mid = sig.length / 2;
        sig[mid] = sig[mid] == 'a' ? 'b' : 'a';
        String tampered = parts[0] + "." + parts[1] + "." + new String(sig);
        StompHeaders h = new StompHeaders();
        h.add("Authorization", "Bearer " + tampered);
        assertConnectionRejected(h);
    }

    // Expired token test – not practical here, skipping.
}
