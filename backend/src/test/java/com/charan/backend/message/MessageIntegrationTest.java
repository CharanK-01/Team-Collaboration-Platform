package com.charan.backend.message;

import com.charan.backend.BaseIntegrationTest;
import com.charan.backend.auth.AuthResponse;
import com.charan.backend.auth.LoginRequest;
import com.charan.backend.auth.SignupRequest;
import com.charan.backend.auth.UserResponse;
import com.charan.backend.channel.*;
import com.charan.backend.invite.WorkspaceInviteRepository;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for T2.2: send and fetch messages over REST.
 *
 * Strategy: clean all relevant tables in @BeforeEach (same approach as
 * ChannelIntegrationTest).  This guarantees tests are independent of each
 * other regardless of run order.
 *
 * All HTTP calls go through TestRestTemplate on a random port so the full
 * Spring Security filter chain, Bean Validation, and Flyway migrations all run.
 * No mocks.
 */
class MessageIntegrationTest extends BaseIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    // --- repositories used only for setup / teardown ---
    @Autowired private MessageRepository messageRepository;
    @Autowired private ChannelMemberRepository channelMemberRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private WorkspaceInviteRepository workspaceInviteRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        // Delete in FK-safe order: messages reference channels; channels reference workspaces, etc.
        messageRepository.deleteAll();
        channelMemberRepository.deleteAll();
        channelRepository.deleteAll();
        workspaceInviteRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }

    // =========================================================================
    // 1. Send message – happy path
    // =========================================================================

    @Test
    @DisplayName("POST /messages - Send to public channel as member returns 201 with message body")
    void send_publicChannel_member_returns201() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);
        // alice created the channel so she is already a member

        ResponseEntity<MessageResponse> resp = sendMessage(token, ws.id(), ch.id(), "hello world");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().content()).isEqualTo("hello world");
        assertThat(resp.getBody().channelId()).isEqualTo(ch.id());
        assertThat(resp.getBody().sender()).isNotNull();
        assertThat(resp.getBody().sender().displayName()).isEqualTo("Alice");
        assertThat(resp.getBody().createdAt()).isNotNull();
    }

    // =========================================================================
    // 2. Send – validation failures (400)
    // =========================================================================

    @Test
    @DisplayName("POST /messages - Blank content returns 400")
    void send_blankContent_returns400() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()), HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest("   "), authHeaders(token)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /messages - Content over 4000 chars returns 400")
    void send_contentOver4000_returns400() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        String longContent = "a".repeat(4001);
        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()), HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest(longContent), authHeaders(token)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /messages - Exactly 4000 chars is accepted (boundary check)")
    void send_exactly4000Chars_returns201() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        String maxContent = "a".repeat(4000);
        ResponseEntity<MessageResponse> resp = sendMessage(token, ws.id(), ch.id(), maxContent);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /messages - 20 concurrent sends into one channel all succeed with seq 1..20 and no duplicates")
    void send_concurrentSends_allSucceedWithUniqueSequences() throws Exception {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        int count = 20;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch readyLatch = new CountDownLatch(count);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<ResponseEntity<MessageResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await();
                return sendMessage(token, ws.id(), ch.id(), "concurrent-" + index);
            }));
        }

        readyLatch.await();
        startLatch.countDown();

        for (Future<ResponseEntity<MessageResponse>> future : futures) {
            ResponseEntity<MessageResponse> resp = future.get();
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        executor.shutdown();

        List<Message> savedMessages = messageRepository.findNewest(ch.id(), 100);
        assertThat(savedMessages).hasSize(count);

        List<Long> actualSeqs = savedMessages.stream().map(Message::getSeq).sorted().toList();
        List<Long> expectedSeqs = LongStream.rangeClosed(1, count).boxed().toList();

        assertThat(actualSeqs).containsExactlyElementsOf(expectedSeqs);
    }

    // =========================================================================
    // 3. Access control – workspace membership
    // =========================================================================

    @Test
    @DisplayName("POST /messages - Workspace non-member returns 403")
    void send_workspaceNonMember_returns403() {
        String ownerToken = signupAndLogin("alice@example.com", "Alice");
        String outsiderToken = signupAndLogin("outsider@example.com", "Outsider");
        WorkspaceResponse ws = createWorkspace(ownerToken, "WS");
        ChannelResponse ch = createChannel(ownerToken, ws.id(), "general", false);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()), HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest("hi"), authHeaders(outsiderToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /messages - Workspace non-member returns 403")
    void fetch_workspaceNonMember_returns403() {
        String ownerToken = signupAndLogin("alice@example.com", "Alice");
        String outsiderToken = signupAndLogin("outsider@example.com", "Outsider");
        WorkspaceResponse ws = createWorkspace(ownerToken, "WS");
        ChannelResponse ch = createChannel(ownerToken, ws.id(), "general", false);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(outsiderToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =========================================================================
    // 4. Access control – channel from another workspace treated as 404
    // =========================================================================

    @Test
    @DisplayName("POST /messages - Channel id from another workspace returns 404")
    void send_channelFromOtherWorkspace_returns404() {
        String aliceToken = signupAndLogin("alice@example.com", "Alice");
        String bobToken   = signupAndLogin("bob@example.com", "Bob");
        WorkspaceResponse ws1 = createWorkspace(aliceToken, "WS1");
        WorkspaceResponse ws2 = createWorkspace(bobToken, "WS2");
        ChannelResponse chInWs2 = createChannel(bobToken, ws2.id(), "other", false);
        // Alice is a member of ws1 but NOT ws2; chInWs2 belongs to ws2
        // Using ws1 + chInWs2.id() should give 404 (channel not in ws1)
        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws1.id(), chInWs2.id()), HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest("hi"), authHeaders(aliceToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /messages - Channel id from another workspace returns 404")
    void fetch_channelFromOtherWorkspace_returns404() {
        String aliceToken = signupAndLogin("alice@example.com", "Alice");
        String bobToken   = signupAndLogin("bob@example.com", "Bob");
        WorkspaceResponse ws1 = createWorkspace(aliceToken, "WS1");
        WorkspaceResponse ws2 = createWorkspace(bobToken, "WS2");
        ChannelResponse chInWs2 = createChannel(bobToken, ws2.id(), "other", false);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws1.id(), chInWs2.id()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(aliceToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // 5. Access control – public channel non-member (403)
    // =========================================================================

    @Test
    @DisplayName("POST /messages - Public channel non-member returns 403 (must join first)")
    void send_publicChannelNonMember_returns403() {
        String aliceToken = signupAndLogin("alice@example.com", "Alice");
        UserResponse bob = signupUser("bob@example.com", "Bob");
        String bobToken  = login("bob@example.com");
        WorkspaceResponse ws = createWorkspace(aliceToken, "WS");
        joinWorkspace(ws.id(), bob.id()); // bob is a workspace member
        ChannelResponse ch = createChannel(aliceToken, ws.id(), "general", false);
        // Bob has NOT joined the channel yet

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()), HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest("hi"), authHeaders(bobToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /messages - Public channel non-member returns 403 (must join first)")
    void fetch_publicChannelNonMember_returns403() {
        String aliceToken = signupAndLogin("alice@example.com", "Alice");
        UserResponse bob = signupUser("bob@example.com", "Bob");
        String bobToken  = login("bob@example.com");
        WorkspaceResponse ws = createWorkspace(aliceToken, "WS");
        joinWorkspace(ws.id(), bob.id());
        ChannelResponse ch = createChannel(aliceToken, ws.id(), "general", false);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(bobToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =========================================================================
    // 6. Access control – private channel non-member (404 to hide existence)
    // =========================================================================

    @Test
    @DisplayName("POST /messages - Private channel non-member returns 404 (existence hidden)")
    void send_privateChannelNonMember_returns404() {
        String aliceToken = signupAndLogin("alice@example.com", "Alice");
        UserResponse bob = signupUser("bob@example.com", "Bob");
        String bobToken  = login("bob@example.com");
        WorkspaceResponse ws = createWorkspace(aliceToken, "WS");
        joinWorkspace(ws.id(), bob.id());
        ChannelResponse ch = createChannel(aliceToken, ws.id(), "secret", true);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()), HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest("hi"), authHeaders(bobToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /messages - Private channel non-member returns 404 (existence hidden)")
    void fetch_privateChannelNonMember_returns404() {
        String aliceToken = signupAndLogin("alice@example.com", "Alice");
        UserResponse bob = signupUser("bob@example.com", "Bob");
        String bobToken  = login("bob@example.com");
        WorkspaceResponse ws = createWorkspace(aliceToken, "WS");
        joinWorkspace(ws.id(), bob.id());
        ChannelResponse ch = createChannel(aliceToken, ws.id(), "secret", true);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(bobToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // 7. DM – participant can send and fetch; outsider gets 404
    // =========================================================================

    @Test
    @DisplayName("POST /messages - DM participant can send a message (200 -> 201)")
    void send_dmParticipant_returns201() {
        String aliceToken = signupAndLogin("alice@example.com", "Alice");
        UserResponse bob  = signupUser("bob@example.com", "Bob");
        WorkspaceResponse ws = createWorkspace(aliceToken, "WS");
        joinWorkspace(ws.id(), bob.id());
        DmChannelResponse dm = createDm(aliceToken, ws.id(), bob.id());

        ResponseEntity<MessageResponse> resp = sendMessage(aliceToken, ws.id(), dm.id(), "Hey Bob!");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().content()).isEqualTo("Hey Bob!");
    }

    @Test
    @DisplayName("GET /messages - DM participant can fetch message history")
    void fetch_dmParticipant_returns200() {
        String aliceToken = signupAndLogin("alice@example.com", "Alice");
        UserResponse bob  = signupUser("bob@example.com", "Bob");
        WorkspaceResponse ws = createWorkspace(aliceToken, "WS");
        joinWorkspace(ws.id(), bob.id());
        DmChannelResponse dm = createDm(aliceToken, ws.id(), bob.id());
        sendMessage(aliceToken, ws.id(), dm.id(), "Hello");

        ResponseEntity<MessagePageResponse> resp = restTemplate.exchange(
                uri(ws.id(), dm.id()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(aliceToken)),
                MessagePageResponse.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().messages()).hasSize(1);
        assertThat(resp.getBody().messages().get(0).content()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("POST /messages - DM outsider (workspace member not in DM) returns 404")
    void send_dmOutsider_returns404() {
        String aliceToken   = signupAndLogin("alice@example.com", "Alice");
        UserResponse bob    = signupUser("bob@example.com", "Bob");
        UserResponse charlie = signupUser("charlie@example.com", "Charlie");
        String charlieToken  = login("charlie@example.com");
        WorkspaceResponse ws = createWorkspace(aliceToken, "WS");
        joinWorkspace(ws.id(), bob.id());
        joinWorkspace(ws.id(), charlie.id());
        DmChannelResponse dm = createDm(aliceToken, ws.id(), bob.id()); // Alice ↔ Bob

        // Charlie is a workspace member but NOT in this DM
        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), dm.id()), HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest("spy"), authHeaders(charlieToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /messages - DM outsider returns 404")
    void fetch_dmOutsider_returns404() {
        String aliceToken   = signupAndLogin("alice@example.com", "Alice");
        UserResponse bob    = signupUser("bob@example.com", "Bob");
        UserResponse charlie = signupUser("charlie@example.com", "Charlie");
        String charlieToken  = login("charlie@example.com");
        WorkspaceResponse ws = createWorkspace(aliceToken, "WS");
        joinWorkspace(ws.id(), bob.id());
        joinWorkspace(ws.id(), charlie.id());
        DmChannelResponse dm = createDm(aliceToken, ws.id(), bob.id());

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), dm.id()), HttpMethod.GET,
                new HttpEntity<>(authHeaders(charlieToken)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // 8. Pagination – limit validation
    // =========================================================================

    @Test
    @DisplayName("GET /messages - limit=0 returns 400")
    void fetch_limitZero_returns400() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()) + "?limit=0", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("GET /messages - limit=101 returns 400")
    void fetch_limitOver100_returns400() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        ResponseEntity<String> resp = restTemplate.exchange(
                uri(ws.id(), ch.id()) + "?limit=101", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                String.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // 9. Pagination – cursor walk-through (7 messages, page size 3)
    //
    // This is the main pagination test.  It verifies:
    //   a) newest-first order across all pages
    //   b) no duplicates anywhere
    //   c) no gaps (every message id appears exactly once)
    //   d) nextCursor is non-null between pages and null on the last page
    // =========================================================================

    @Test
    @DisplayName("GET /messages - Paginate 7 messages with limit=3: no duplicates, no gaps, newest-first, null nextCursor on last page")
    void fetch_paginateSeven_threePagesCorrect() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        // Send 7 messages; collect their ids in insertion order (ascending)
        List<Long> sentIds = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            MessageResponse m = sendMessage(token, ws.id(), ch.id(), "msg-" + i).getBody();
            assertThat(m).isNotNull();
            sentIds.add(m.id());
        }

        // ---- Page 1: no cursor ----
        MessagePageResponse page1 = fetchPage(token, ws.id(), ch.id(), null, 3);
        assertThat(page1.messages()).hasSize(3);
        assertThat(page1.nextCursor()).isNotNull(); // more pages remain
        // Newest first → page1 should contain the 3 highest ids
        List<Long> page1Ids = page1.messages().stream().map(MessageResponse::id).toList();
        assertThat(page1Ids).containsExactly(
                sentIds.get(6), sentIds.get(5), sentIds.get(4)); // ids 7,6,5

        // ---- Page 2: cursor = page1.nextCursor ----
        MessagePageResponse page2 = fetchPage(token, ws.id(), ch.id(), page1.nextCursor(), 3);
        assertThat(page2.messages()).hasSize(3);
        assertThat(page2.nextCursor()).isNotNull();
        List<Long> page2Ids = page2.messages().stream().map(MessageResponse::id).toList();
        assertThat(page2Ids).containsExactly(
                sentIds.get(3), sentIds.get(2), sentIds.get(1)); // ids 4,3,2

        // ---- Page 3: cursor = page2.nextCursor ----
        MessagePageResponse page3 = fetchPage(token, ws.id(), ch.id(), page2.nextCursor(), 3);
        assertThat(page3.messages()).hasSize(1); // only 1 message left
        assertThat(page3.nextCursor()).isNull(); // last page
        List<Long> page3Ids = page3.messages().stream().map(MessageResponse::id).toList();
        assertThat(page3Ids).containsExactly(sentIds.get(0)); // id 1

        // ---- Global invariants ----
        List<Long> allReturnedIds = new ArrayList<>();
        allReturnedIds.addAll(page1Ids);
        allReturnedIds.addAll(page2Ids);
        allReturnedIds.addAll(page3Ids);

        // No duplicates
        Set<Long> unique = new HashSet<>(allReturnedIds);
        assertThat(unique).hasSameSizeAs(allReturnedIds);

        // No gaps: every sent id appears exactly once
        assertThat(unique).containsExactlyInAnyOrderElementsOf(sentIds);

        // Newest-first order across the full list
        for (int i = 0; i < allReturnedIds.size() - 1; i++) {
            assertThat(allReturnedIds.get(i)).isGreaterThan(allReturnedIds.get(i + 1));
        }
    }

    @Test
    @DisplayName("GET /messages - New message sent between page 1 and page 2 does not cause duplicates or gaps")
    void fetch_newMessageBetweenPages_noDuplicatesOrGaps() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        // 1. Create 7 initial messages
        List<Long> initialIds = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            MessageResponse m = sendMessage(token, ws.id(), ch.id(), "msg-" + i).getBody();
            assertThat(m).isNotNull();
            initialIds.add(m.id());
        }

        // 2. Fetch page 1 (limit = 3, newest first) -> should return msg-7, msg-6, msg-5
        MessagePageResponse page1 = fetchPage(token, ws.id(), ch.id(), null, 3);
        assertThat(page1.messages()).hasSize(3);
        List<Long> page1Ids = page1.messages().stream().map(MessageResponse::id).toList();
        assertThat(page1Ids).containsExactly(initialIds.get(6), initialIds.get(5), initialIds.get(4));
        Long cursor = page1.nextCursor();
        assertThat(cursor).isEqualTo(initialIds.get(4)); // id of msg-5

        // 3. Send a new message (8th message) AFTER fetching page 1
        MessageResponse m8 = sendMessage(token, ws.id(), ch.id(), "msg-8-brand-new").getBody();
        assertThat(m8).isNotNull();
        assertThat(m8.id()).isGreaterThan(initialIds.get(6));

        // 4. Fetch page 2 using cursor from page 1 (limit = 3) -> should return msg-4, msg-3, msg-2
        MessagePageResponse page2 = fetchPage(token, ws.id(), ch.id(), cursor, 3);
        assertThat(page2.messages()).hasSize(3);
        List<Long> page2Ids = page2.messages().stream().map(MessageResponse::id).toList();
        assertThat(page2Ids).containsExactly(initialIds.get(3), initialIds.get(2), initialIds.get(1));

        // 5. Assert page 2 has no duplicates with page 1, does not contain the new message, and has no gaps
        List<Long> combined = new ArrayList<>();
        combined.addAll(page1Ids);
        combined.addAll(page2Ids);

        // No duplicates
        assertThat(new HashSet<>(combined)).hasSameSizeAs(combined);
        // Does not contain m8
        assertThat(combined).doesNotContain(m8.id());
        // Matches the first 6 of the original 7 (newest first: 7, 6, 5, 4, 3, 2)
        assertThat(combined).containsExactly(
                initialIds.get(6), initialIds.get(5), initialIds.get(4),
                initialIds.get(3), initialIds.get(2), initialIds.get(1)
        );
    }

    @Test
    @DisplayName("GET /messages - Empty channel returns empty list and null nextCursor")
    void fetch_emptyChannel_returnsEmptyPage() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        MessagePageResponse page = fetchPage(token, ws.id(), ch.id(), null, 10);
        assertThat(page.messages()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("GET /messages - Exactly limit messages returns null nextCursor (no extra page)")
    void fetch_exactlyLimitMessages_nextCursorNull() {
        String token = signupAndLogin("alice@example.com", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "WS");
        ChannelResponse ch = createChannel(token, ws.id(), "general", false);

        // Send exactly 3 messages, fetch with limit=3
        for (int i = 0; i < 3; i++) {
            sendMessage(token, ws.id(), ch.id(), "msg-" + i);
        }

        MessagePageResponse page = fetchPage(token, ws.id(), ch.id(), null, 3);
        assertThat(page.messages()).hasSize(3);
        assertThat(page.nextCursor()).isNull(); // exactly limit rows → no next page
    }

    // =========================================================================
    // Helper methods
    // =========================================================================

    /** Signs up a user and logs in; returns the JWT token. */
    private String signupAndLogin(String email, String displayName) {
        restTemplate.postForEntity("/api/auth/signup",
                new SignupRequest(email, "password123", displayName), UserResponse.class);
        return login(email);
    }

    /** Signs up a user and returns the UserResponse (id, email, displayName). */
    private UserResponse signupUser(String email, String displayName) {
        ResponseEntity<UserResponse> resp = restTemplate.postForEntity("/api/auth/signup",
                new SignupRequest(email, "password123", displayName), UserResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    /** Logs in an existing user; returns the JWT token. */
    private String login(String email) {
        ResponseEntity<AuthResponse> resp = restTemplate.postForEntity("/api/auth/login",
                new LoginRequest(email, "password123"), AuthResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        return resp.getBody().token();
    }

    private WorkspaceResponse createWorkspace(String token, String name) {
        ResponseEntity<WorkspaceResponse> resp = restTemplate.exchange(
                "/api/workspaces", HttpMethod.POST,
                new HttpEntity<>(new CreateWorkspaceRequest(name), authHeaders(token)),
                WorkspaceResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    /** Directly inserts a workspace member row (bypasses invite flow for test speed). */
    private void joinWorkspace(Long workspaceId, Long userId) {
        workspaceMemberRepository.save(
                new WorkspaceMember(workspaceId, userId, WorkspaceRole.MEMBER));
    }

    private ChannelResponse createChannel(String token, Long wsId, String name, boolean isPrivate) {
        ResponseEntity<ChannelResponse> resp = restTemplate.exchange(
                "/api/workspaces/" + wsId + "/channels", HttpMethod.POST,
                new HttpEntity<>(new CreateChannelRequest(name, isPrivate), authHeaders(token)),
                ChannelResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private DmChannelResponse createDm(String token, Long wsId, Long targetUserId) {
        ResponseEntity<DmChannelResponse> resp = restTemplate.exchange(
                "/api/workspaces/" + wsId + "/dms", HttpMethod.POST,
                new HttpEntity<>(new CreateDmRequest(targetUserId), authHeaders(token)),
                DmChannelResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private ResponseEntity<MessageResponse> sendMessage(String token, Long wsId, Long chId, String content) {
        return restTemplate.exchange(
                uri(wsId, chId), HttpMethod.POST,
                new HttpEntity<>(new SendMessageRequest(content), authHeaders(token)),
                MessageResponse.class);
    }

    private MessagePageResponse fetchPage(String token, Long wsId, Long chId, Long before, int limit) {
        String url = uri(wsId, chId) + "?limit=" + limit + (before != null ? "&before=" + before : "");
        ResponseEntity<MessagePageResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)),
                MessagePageResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private String uri(Long wsId, Long chId) {
        return "/api/workspaces/" + wsId + "/channels/" + chId + "/messages";
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
