package com.charan.backend.websocket;

import com.charan.backend.BaseIntegrationTest;
import com.charan.backend.auth.AuthResponse;
import com.charan.backend.auth.LoginRequest;
import com.charan.backend.auth.SignupRequest;
import com.charan.backend.auth.UserResponse;
import com.charan.backend.channel.ChannelMemberRepository;
import com.charan.backend.channel.ChannelRepository;
import com.charan.backend.channel.ChannelResponse;
import com.charan.backend.channel.CreateChannelRequest;
import com.charan.backend.invite.WorkspaceInviteRepository;
import com.charan.backend.message.MessageRepository;
import com.charan.backend.message.MessageResponse;
import com.charan.backend.message.SendMessageRequest;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.CreateWorkspaceRequest;
import com.charan.backend.workspace.WorkspaceMember;
import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.workspace.WorkspaceRepository;
import com.charan.backend.workspace.WorkspaceResponse;
import com.charan.backend.workspace.WorkspaceRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class WebSocketMessagingIntegrationTest extends BaseIntegrationTest {

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

    private final ObjectMapper mapper = new ObjectMapper();

    private String aliceToken, bobToken, carolToken, daveToken;
    private long aliceId, bobId, carolId;
    private long wsId, publicCh, privateCh;

    /**
     * One STOMP session, a queue of everything it receives (as JSON text), and any
     * errors.
     */
    private static class Client {
        StompSession session;
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        final List<String> problems = new CopyOnWriteArrayList<>();
    }

    private final List<Client> clients = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        channelMemberRepository.deleteAll();
        channelRepository.deleteAll();
        workspaceInviteRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        UserResponse alice = signup("alice@example.com", "Alice");
        UserResponse bob = signup("bob@example.com", "Bob");
        UserResponse carol = signup("carol@example.com", "Carol");
        signup("dave@example.com", "Dave"); // never added to the workspace
        aliceId = alice.id();
        bobId = bob.id();
        carolId = carol.id();
        aliceToken = login("alice@example.com");
        bobToken = login("bob@example.com");
        carolToken = login("carol@example.com");
        daveToken = login("dave@example.com");

        WorkspaceResponse ws = rest(aliceToken, "/api/workspaces", HttpMethod.POST,
                new CreateWorkspaceRequest("WS"), WorkspaceResponse.class);
        wsId = ws.id();
        workspaceMemberRepository.save(new WorkspaceMember(wsId, bobId, WorkspaceRole.MEMBER));
        workspaceMemberRepository.save(new WorkspaceMember(wsId, carolId, WorkspaceRole.MEMBER));

        publicCh = createChannel(aliceToken, "general", false);
        privateCh = createChannel(aliceToken, "secret", true);
        joinChannel(bobToken, publicCh); // Bob joins the public channel; Carol does not
    }

    @AfterEach
    void closeClients() {
        for (Client c : clients) {
            if (c.session != null && c.session.isConnected()) {
                c.session.disconnect();
            }
        }
        clients.clear();
    }

    // ---------------------------------------------------------------- tests

    @Test
    void a_stompSend_persistsFirst_thenBothSubscribersReceive() throws Exception {
        Client alice = connect(aliceToken);
        Client bob = connect(bobToken);
        subscribe(alice, "/topic/channels." + publicCh);
        subscribe(bob, "/topic/channels." + publicCh);

        sendStomp(alice, "/app/workspaces/" + wsId + "/channels/" + publicCh + "/messages",
                "{\"content\":\"hello from alice\"}");

        MessageResponse atAlice = mapper.readValue(poll(alice, 5), MessageResponse.class);
        MessageResponse atBob = mapper.readValue(poll(bob, 5), MessageResponse.class);
        for (MessageResponse m : List.of(atAlice, atBob)) {
            assertEquals("hello from alice", m.content());
            assertEquals(publicCh, m.channelId());
            assertEquals("Alice", m.sender().displayName());
            assertTrue(messageRepository.findById(m.id()).isPresent(), "must be persisted");
        }
        assertEquals(atAlice.id(), atBob.id());
        assertTrue(restHistoryIds(aliceToken, publicCh).contains(atAlice.id()));
    }

    @Test
    void b_restSend_isAlsoBroadcast() throws Exception {
        Client bob = connect(bobToken);
        subscribe(bob, "/topic/channels." + publicCh);

        rest(aliceToken, "/api/workspaces/" + wsId + "/channels/" + publicCh + "/messages",
                HttpMethod.POST, new SendMessageRequest("sent over REST"), MessageResponse.class);

        MessageResponse m = mapper.readValue(poll(bob, 5), MessageResponse.class);
        assertEquals("sent over REST", m.content());
    }

    @Test
    void c_unjoinedPublicChannel_cannotBeSubscribed() throws Exception {
        Client carol = connect(carolToken); // workspace member, has NOT joined the channel
        subscribe(carol, "/topic/channels." + publicCh);

        rest(aliceToken, "/api/workspaces/" + wsId + "/channels/" + publicCh + "/messages",
                HttpMethod.POST, new SendMessageRequest("secret-ish"), MessageResponse.class);

        assertNothingArrives(carol);
    }

    @Test
    void d_privateChannel_andOutsiders_cannotSubscribe() throws Exception {
        Client bob = connect(bobToken); // workspace member, not in the private channel
        Client dave = connect(daveToken); // not in the workspace at all
        subscribe(bob, "/topic/channels." + privateCh);
        subscribe(dave, "/topic/channels." + publicCh);

        rest(aliceToken, "/api/workspaces/" + wsId + "/channels/" + privateCh + "/messages",
                HttpMethod.POST, new SendMessageRequest("for alice only"), MessageResponse.class);
        rest(aliceToken, "/api/workspaces/" + wsId + "/channels/" + publicCh + "/messages",
                HttpMethod.POST, new SendMessageRequest("members only"), MessageResponse.class);

        assertNothingArrives(bob);
        assertNothingArrives(dave);
    }

    @Test
    void e_arbitraryDestination_isRejected() throws Exception {
        Client bob = connect(bobToken);
        subscribe(bob, "/topic/anything");

        rest(aliceToken, "/api/workspaces/" + wsId + "/channels/" + publicCh + "/messages",
                HttpMethod.POST, new SendMessageRequest("hello"), MessageResponse.class);

        assertNothingArrives(bob);
    }

    @Test
    void f_nonMemberSend_isNotPersistedNorBroadcast_andSenderGetsError() throws Exception {
        Client alice = connect(aliceToken);
        Client carol = connect(carolToken); // not a member of the public channel
        subscribe(alice, "/topic/channels." + publicCh);
        subscribe(carol, "/user/queue/errors");
        long before = messageRepository.count();

        sendStomp(carol, "/app/workspaces/" + wsId + "/channels/" + publicCh + "/messages",
                "{\"content\":\"let me in\"}");

        String error = poll(carol, 5);
        assertTrue(error.contains("403"), "sender should get a 403 error, got: " + error);
        assertEquals(before, messageRepository.count());
        assertNothingArrives(alice);
    }

    @Test
    void g_blankContent_isNotPersistedNorBroadcast_andSenderGetsError() throws Exception {
        Client alice = connect(aliceToken);
        Client bob = connect(bobToken);
        subscribe(bob, "/topic/channels." + publicCh);
        subscribe(alice, "/user/queue/errors");
        long before = messageRepository.count();

        sendStomp(alice, "/app/workspaces/" + wsId + "/channels/" + publicCh + "/messages",
                "{\"content\":\"   \"}");

        String error = poll(alice, 5);
        assertTrue(error.contains("400"), "sender should get a 400 error, got: " + error);
        assertEquals(before, messageRepository.count());
        assertNothingArrives(bob);
    }

    @Test
    void h_directSendToTopic_doesNotBypassThePersistPath() throws Exception {
        Client alice = connect(aliceToken);
        Client bob = connect(bobToken);
        subscribe(bob, "/topic/channels." + publicCh);
        long before = messageRepository.count();

        sendStomp(alice, "/topic/channels." + publicCh, "{\"content\":\"sneaky\"}");

        assertNothingArrives(bob);
        assertEquals(before, messageRepository.count());
    }

    @Test
    void i_messagesArriveInSendOrder() throws Exception {
        Client alice = connect(aliceToken);
        Client bob = connect(bobToken);
        subscribe(bob, "/topic/channels." + publicCh);

        for (int i = 1; i <= 3; i++) {
            sendStomp(alice, "/app/workspaces/" + wsId + "/channels/" + publicCh + "/messages",
                    "{\"content\":\"m" + i + "\"}");
            // Wait for each to arrive so the sends are strictly ordered in time.
            MessageResponse m = mapper.readValue(poll(bob, 5), MessageResponse.class);
            assertEquals("m" + i, m.content());
        }
    }

    // -------------------------------------------------------------- helpers

    private Client connect(String token) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        // No custom converter: the default passes frame bodies through as raw bytes.
        // The helpers below read and write UTF-8 JSON text themselves.
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        stomp.setTaskScheduler(scheduler);

        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + token);
        Client c = new Client();
        c.session = stomp.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), headers, new StompSessionHandlerAdapter() {
                    @Override
                    public void handleException(StompSession s, StompCommand command,
                            StompHeaders h, byte[] payload, Throwable ex) {
                        c.problems.add("exception: " + ex);
                    }

                    @Override
                    public void handleTransportError(StompSession s, Throwable ex) {
                        c.problems.add("transport: " + ex);
                    }
                }).get(5, TimeUnit.SECONDS);
        clients.add(c);
        return c;
    }

    private void subscribe(Client c, String destination) throws InterruptedException {
        c.session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                c.received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        // Give the server a moment to register (or reject) the subscription, because a
        // message published before that would simply be lost.
        Thread.sleep(500);
    }

    private void sendStomp(Client c, String destination, String jsonBody) {
        StompHeaders headers = new StompHeaders();
        headers.setDestination(destination);
        headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
        try {
            c.session.send(headers, jsonBody.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // A frame the server rejects can close the session first, which is fine for the
            // negative tests. A failure while still connected is a real bug and must show.
            if (c.session.isConnected()) {
                throw e;
            }
        }
    }

    private String poll(Client c, int seconds) throws InterruptedException {
        String s = c.received.poll(seconds, TimeUnit.SECONDS);
        assertNotNull(s, "expected a frame within " + seconds + "s; client problems: " + c.problems);
        return s;
    }

    private void assertNothingArrives(Client c) throws InterruptedException {
        assertNull(c.received.poll(2, TimeUnit.SECONDS), "no frame should have been delivered");
    }

    private UserResponse signup(String email, String name) {
        ResponseEntity<UserResponse> r = restTemplate.postForEntity("/api/auth/signup",
                new SignupRequest(email, "password123", name), UserResponse.class);
        assertEquals(201, r.getStatusCode().value());
        return r.getBody();
    }

    private String login(String email) {
        return restTemplate.postForObject("/api/auth/login",
                new LoginRequest(email, "password123"), AuthResponse.class).token();
    }

    private <T> T rest(String token, String path, HttpMethod method, Object body, Class<T> type) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<T> r = restTemplate.exchange(path, method, new HttpEntity<>(body, h), type);
        assertTrue(r.getStatusCode().is2xxSuccessful(),
                "REST call failed: " + path + " -> " + r.getStatusCode());
        return r.getBody();
    }

    private long createChannel(String token, String name, boolean isPrivate) {
        ChannelResponse ch = rest(token, "/api/workspaces/" + wsId + "/channels", HttpMethod.POST,
                new CreateChannelRequest(name, isPrivate), ChannelResponse.class);
        return ch.id();
    }

    private void joinChannel(String token, long channelId) {
        rest(token, "/api/workspaces/" + wsId + "/channels/" + channelId + "/join",
                HttpMethod.POST, null, ChannelResponse.class);
    }

    private List<Long> restHistoryIds(String token, long channelId) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        String json = restTemplate.exchange(
                "/api/workspaces/" + wsId + "/channels/" + channelId + "/messages",
                HttpMethod.GET, new HttpEntity<>(h), String.class).getBody();
        List<Long> ids = new ArrayList<>();
        mapper.readTree(json).get("messages").forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }
}