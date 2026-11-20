package com.charan.backend.chat;

import com.charan.backend.BaseIntegrationTest;
import com.charan.backend.user.UserRepository;
import com.charan.backend.auth.AuthResponse;
import com.charan.backend.auth.LoginRequest;
import com.charan.backend.auth.SignupRequest;
import com.charan.backend.auth.UserResponse;
import com.charan.backend.channel.*;
import com.charan.backend.invite.*;
import com.charan.backend.message.*;
import com.charan.backend.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ChatFlowEndToEndTest extends BaseIntegrationTest {

        @Autowired
        private TestRestTemplate restTemplate;
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

        @BeforeEach
        void clean() {
                // Delete in FK‑safe order, mirroring ChannelIntegrationTest.
                messageRepository.deleteAll();
                channelMemberRepository.deleteAll();
                channelRepository.deleteAll();
                workspaceInviteRepository.deleteAll();
                workspaceMemberRepository.deleteAll();
                workspaceRepository.deleteAll();
                userRepository.deleteAll();
        }

        // ----- helper methods ----------------------------------------------------
        private String signupAndLogin(String email, String displayName) {
                restTemplate.postForEntity("/api/auth/signup",
                                new SignupRequest(email, "password123", displayName),
                                UserResponse.class);
                ResponseEntity<AuthResponse> login = restTemplate.postForEntity(
                                "/api/auth/login",
                                new LoginRequest(email, "password123"),
                                AuthResponse.class);
                assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
                return login.getBody().token();
        }

        private HttpHeaders authHeaders(String token) {
                HttpHeaders h = new HttpHeaders();
                h.setBearerAuth(token);
                return h;
        }

        private WorkspaceResponse createWorkspace(String token, String name) {
                ResponseEntity<WorkspaceResponse> r = restTemplate.exchange(
                                "/api/workspaces",
                                HttpMethod.POST,
                                new HttpEntity<>(new CreateWorkspaceRequest(name), authHeaders(token)),
                                WorkspaceResponse.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                return r.getBody();
        }

        private ChannelResponse createChannel(String token, Long wsId, String name, boolean isPrivate) {
                ResponseEntity<ChannelResponse> r = restTemplate.exchange(
                                "/api/workspaces/" + wsId + "/channels",
                                HttpMethod.POST,
                                new HttpEntity<>(new CreateChannelRequest(name, isPrivate), authHeaders(token)),
                                ChannelResponse.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                return r.getBody();
        }

        private InviteResponse sendInvite(String token, Long wsId, String email, WorkspaceRole role) {
                ResponseEntity<InviteResponse> r = restTemplate.exchange(
                                "/api/workspaces/" + wsId + "/invites",
                                HttpMethod.POST,
                                new HttpEntity<>(new CreateInviteRequest(email, role), authHeaders(token)),
                                InviteResponse.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                return r.getBody();
        }

        private AcceptInviteResponse acceptInvite(String token, String inviteToken) {
                ResponseEntity<AcceptInviteResponse> r = restTemplate.exchange(
                                "/api/invites/" + inviteToken + "/accept",
                                HttpMethod.POST,
                                new HttpEntity<>(authHeaders(token)),
                                AcceptInviteResponse.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
                return r.getBody();
        }

        private DmChannelResponse createDm(String token, Long wsId, Long targetUserId) {
                ResponseEntity<DmChannelResponse> r = restTemplate.exchange(
                                "/api/workspaces/" + wsId + "/dms",
                                HttpMethod.POST,
                                new HttpEntity<>(new CreateDmRequest(targetUserId), authHeaders(token)),
                                DmChannelResponse.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
                return r.getBody();
        }

        private MessageResponse sendMessage(String token, Long wsId, Long chId, String content) {
                ResponseEntity<MessageResponse> r = restTemplate.exchange(
                                "/api/workspaces/" + wsId + "/channels/" + chId + "/messages",
                                HttpMethod.POST,
                                new HttpEntity<>(new SendMessageRequest(content), authHeaders(token)),
                                MessageResponse.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                return r.getBody();
        }

        private MessagePageResponse fetchPage(String token, Long wsId, Long chId, Long before, int limit) {
                String url = "/api/workspaces/" + wsId + "/channels/" + chId + "/messages" +
                                "?limit=" + limit + (before != null ? "&before=" + before : "");
                ResponseEntity<MessagePageResponse> r = restTemplate.exchange(
                                url, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), MessagePageResponse.class);
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
                return r.getBody();
        }

        @Test
        @DisplayName("Full chat flow through REST only")
        void testChatFlow() {
                // a. sign up three users
                String aliceToken = signupAndLogin("alice@example.com", "Alice");
                String bobToken = signupAndLogin("bob@example.com", "Bob");
                String charlieToken = signupAndLogin("charlie@example.com", "Charlie");

                // b. Alice creates workspace and channels
                WorkspaceResponse ws = createWorkspace(aliceToken, "TeamSpace");
                ChannelResponse publicCh = createChannel(aliceToken, ws.id(), "general", false);
                ChannelResponse privateCh = createChannel(aliceToken, ws.id(), "leads", true);

                // c. Alice invites Bob, Bob accepts
                InviteResponse invite = sendInvite(aliceToken, ws.id(), "bob@example.com", WorkspaceRole.MEMBER);
                acceptInvite(bobToken, invite.token());

                // d. Bob lists channels, sees only public, joins it
                ResponseEntity<ChannelResponse[]> listResp = restTemplate.exchange(
                                "/api/workspaces/" + ws.id() + "/channels",
                                HttpMethod.GET,
                                new HttpEntity<>(authHeaders(bobToken)),
                                ChannelResponse[].class);
                assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
                List<ChannelResponse> seen = Arrays.asList(listResp.getBody());
                assertThat(seen).anyMatch(c -> c.id().equals(publicCh.id()) && !c.joined());
                assertThat(seen).noneMatch(c -> c.id().equals(privateCh.id()));
                // join public
                ResponseEntity<ChannelResponse> joinResp = restTemplate.exchange(
                                "/api/workspaces/" + ws.id() + "/channels/" + publicCh.id() + "/join",
                                HttpMethod.POST,
                                new HttpEntity<>(authHeaders(bobToken)),
                                ChannelResponse.class);
                assertThat(joinResp.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(joinResp.getBody().joined()).isTrue();

                // e. Alice sends 5 messages
                List<MessageResponse> sent = new ArrayList<>();
                for (int i = 1; i <= 5; i++) {
                        sent.add(sendMessage(aliceToken, ws.id(), publicCh.id(), "msg-" + i));
                }
                // Bob pages through history (limit=2)
                List<MessageResponse> fetched = new ArrayList<>();
                Long cursor = null;
                do {
                        MessagePageResponse page = fetchPage(bobToken, ws.id(), publicCh.id(), cursor, 2);
                        fetched.addAll(page.messages());
                        cursor = page.nextCursor();
                } while (cursor != null);
                assertThat(fetched).hasSize(5);
                // newest‑first order verification
                List<Long> fetchedIds = fetched.stream().map(MessageResponse::id).toList();
                List<Long> expectedIds = sent.stream()
                                .sorted(Comparator.comparingLong(MessageResponse::id).reversed())
                                .map(MessageResponse::id)
                                .toList();
                assertThat(fetchedIds).containsExactlyElementsOf(expectedIds);
                // sender displayName check
                fetched.forEach(m -> assertThat(m.sender().displayName()).isEqualTo("Alice"));

                // f. Bob replies, Alice fetches newest (limit=1)
                sendMessage(bobToken, ws.id(), publicCh.id(), "reply from Bob");
                MessagePageResponse newest = fetchPage(aliceToken, ws.id(), publicCh.id(), null, 1);
                assertThat(newest.messages()).hasSize(1);
                assertThat(newest.messages().get(0).sender().displayName()).isEqualTo("Bob");

                // g. Alice adds Bob to private channel, Bob can send/fetch there
                ResponseEntity<ChannelResponse> addResp = restTemplate.exchange(
                                "/api/workspaces/" + ws.id() + "/channels/" + privateCh.id() + "/members",
                                HttpMethod.POST,
                                new HttpEntity<>(new AddChannelMemberRequest(/* bobId */ getUserIdFromToken(bobToken)),
                                                authHeaders(aliceToken)),
                                ChannelResponse.class);
                assertThat(addResp.getStatusCode()).isEqualTo(HttpStatus.OK);
                // Bob sends a private message
                MessageResponse privMsg = sendMessage(bobToken, ws.id(), privateCh.id(), "private hello");
                MessagePageResponse privPage = fetchPage(bobToken, ws.id(), privateCh.id(), null, 10);
                assertThat(privPage.messages()).hasSize(1);
                assertThat(privPage.messages().get(0).content()).isEqualTo("private hello");

                // h. DM creation (twice, same id) and exchange
                DmChannelResponse dm1 = createDm(aliceToken, ws.id(), getUserIdFromToken(bobToken));
                DmChannelResponse dm2 = createDm(aliceToken, ws.id(), getUserIdFromToken(bobToken));
                assertThat(dm1.id()).isEqualTo(dm2.id());
                sendMessage(aliceToken, ws.id(), dm1.id(), "Hi Bob");
                sendMessage(bobToken, ws.id(), dm1.id(), "Hey Alice");
                MessagePageResponse dmHist = fetchPage(bobToken, ws.id(), dm1.id(), null, 10);
                assertThat(dmHist.messages()).hasSize(2);
                // both list DMs
                ResponseEntity<DmChannelResponse[]> dmlistAlice = restTemplate.exchange(
                                "/api/workspaces/" + ws.id() + "/dms",
                                HttpMethod.GET,
                                new HttpEntity<>(authHeaders(aliceToken)),
                                DmChannelResponse[].class);
                assertThat(dmlistAlice.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(dmlistAlice.getBody()).anyMatch(d -> d.id().equals(dm1.id()));
                ResponseEntity<DmChannelResponse[]> dmlistBob = restTemplate.exchange(
                                "/api/workspaces/" + ws.id() + "/dms",
                                HttpMethod.GET,
                                new HttpEntity<>(authHeaders(bobToken)),
                                DmChannelResponse[].class);
                assertThat(dmlistBob.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(dmlistBob.getBody()).anyMatch(d -> d.id().equals(dm1.id()));

                // i. Charlie (outside workspace) gets 403 on various ops
                HttpHeaders ch = authHeaders(charlieToken);
                ResponseEntity<String> listCh = restTemplate.exchange(
                                "/api/workspaces/" + ws.id() + "/channels",
                                HttpMethod.GET,
                                new HttpEntity<>(ch),
                                String.class);
                assertThat(listCh.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                ResponseEntity<String> fetchCh = restTemplate.exchange(
                                "/api/workspaces/" + ws.id() + "/channels/" + publicCh.id() + "/messages",
                                HttpMethod.GET,
                                new HttpEntity<>(ch),
                                String.class);
                assertThat(fetchCh.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                ResponseEntity<String> sendCh = restTemplate.exchange(
                                "/api/workspaces/" + ws.id() + "/channels/" + publicCh.id() + "/messages",
                                HttpMethod.POST,
                                new HttpEntity<>(new SendMessageRequest("oops"), ch),
                                String.class);
                assertThat(sendCh.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        // helper to extract user id from signup response (UserResponse contains id)
        private Long getUserIdFromToken(String token) {
                // Decode JWT without verification to extract sub claim.
                String[] parts = token.split("\\.");
                String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
                return Long.parseLong(payload.replaceAll(".*\"sub\":\"(\\d+)\".*", "$1"));
        }
}
