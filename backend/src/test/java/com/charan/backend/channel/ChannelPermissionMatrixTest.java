package com.charan.backend.channel;

import com.charan.backend.BaseIntegrationTest;
import com.charan.backend.auth.AuthResponse;
import com.charan.backend.auth.LoginRequest;
import com.charan.backend.auth.SignupRequest;
import com.charan.backend.auth.UserResponse;
import com.charan.backend.invite.WorkspaceInviteRepository;
import com.charan.backend.message.MessageRepository;
import com.charan.backend.message.SendMessageRequest;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * =========================================================================================
 *                         CHANNEL PERMISSION MATRIX (T2.3)
 * =========================================================================================
 *
 * This test suite acts as living documentation for all authorization and visibility rules
 * governing channel-scoped operations across various actors, targets, and endpoints.
 *
 * All endpoints tested are nested under Workspace 1:
 *   GET    /api/workspaces/{ws1}/channels/{targetChannel}/messages
 *   POST   /api/workspaces/{ws1}/channels/{targetChannel}/messages
 *   POST   /api/workspaces/{ws1}/channels/{targetChannel}/join
 *   POST   /api/workspaces/{ws1}/channels/{targetChannel}/members
 *
 * -----------------------------------------------------------------------------------------
 * Target Channel            | Actor                      | GET /msgs | POST /msgs | POST /join | POST /members
 * --------------------------+----------------------------+-----------+------------+------------+--------------
 * Public Channel            | Non-member (Eve)           | 403       | 403        | 403        | 403
 * Public Channel            | WS Member not joined (Bob) | 403       | 403        | 200        | 403
 * Public Channel            | Channel Member (Alice)     | 200       | 201        | 200        | 200
 * --------------------------+----------------------------+-----------+------------+------------+--------------
 * Private Channel           | Non-member (Eve)           | 403       | 403        | 403        | 403
 * Private Channel           | WS Member not joined (Bob) | 404       | 404        | 404        | 404
 * Private Channel           | Channel Member (Alice)     | 200       | 201        | 200        | 200
 * --------------------------+----------------------------+-----------+------------+------------+--------------
 * DM Channel (Alice & Chas) | Non-member (Eve)           | 403       | 403        | 403        | 403
 * DM Channel (Alice & Chas) | DM Outsider (Bob)          | 404       | 404        | 404        | 404
 * DM Channel (Alice & Chas) | Channel Member (Alice)     | 200       | 201        | 404        | 404
 * --------------------------+----------------------------+-----------+------------+------------+--------------
 * Other-Workspace Channel   | Non-member (Eve)           | 403       | 403        | 403        | 403
 * Other-Workspace Channel   | WS1 Member (Bob)           | 404       | 404        | 404        | 404
 * Other-Workspace Channel   | WS1 Member / Owner (Alice) | 404       | 404        | 404        | 404
 * -----------------------------------------------------------------------------------------
 *
 * Core Principles Demonstrated:
 * 1. Non-workspace users always receive 403 Forbidden.
 * 2. Private channels and DMs return 404 Not Found to unauthorized users to avoid leaking channel existence.
 * 3. Public channels require joining before reading, sending, or managing members (403 Forbidden).
 * 4. DMs cannot be joined or have members added (404 Not Found).
 * 5. Cross-workspace channel accesses return 404 Not Found (channel does not belong to the requested workspace).
 */
public class ChannelPermissionMatrixTest extends BaseIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private MessageRepository messageRepository;
    @Autowired private ChannelMemberRepository channelMemberRepository;
    @Autowired private ChannelRepository channelRepository;
    @Autowired private WorkspaceInviteRepository workspaceInviteRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private UserRepository userRepository;

    // Fixture identifiers populated fresh in @BeforeEach
    private Long ws1Id;
    private Long ws2Id;

    private Long publicChannelId;
    private Long privateChannelId;
    private Long dmChannelId;
    private Long otherWsChannelId;

    private String aliceToken; // WS1 owner, member of all channels
    private String bobToken;   // WS1 member, not in public channel, not in private channel, not in DM
    private String eveToken;   // Non-member of WS1
    private Long targetUserId; // Charlie: valid WS member for POST /members test

    public enum EndpointType {
        GET_MESSAGES,
        POST_MESSAGES,
        POST_JOIN,
        POST_MEMBERS
    }

    public enum TargetType {
        PUBLIC_CHANNEL,
        PRIVATE_CHANNEL,
        DM_CHANNEL,
        OTHER_WORKSPACE_CHANNEL
    }

    public enum ActorType {
        NON_MEMBER,
        WS_MEMBER_NOT_IN_CHANNEL,
        CHANNEL_MEMBER,
        DM_OUTSIDER
    }

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        channelMemberRepository.deleteAll();
        channelRepository.deleteAll();
        workspaceInviteRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Users
        aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");
        bobToken = login("bob@example.com", "password123");

        UserResponse charlie = signupAndReturnUser("charlie@example.com", "password123", "Charlie");
        targetUserId = charlie.id();

        signupAndLogin("eve@example.com", "password123", "Eve");
        eveToken = login("eve@example.com", "password123");

        // 2. Workspaces
        WorkspaceResponse ws1 = createWorkspace(aliceToken, "Workspace 1");
        ws1Id = ws1.id();

        WorkspaceResponse ws2 = createWorkspace(aliceToken, "Workspace 2");
        ws2Id = ws2.id();

        // Join Bob and Charlie to Workspace 1 (Eve is NOT in Workspace 1)
        workspaceMemberRepository.save(new WorkspaceMember(ws1Id, bob.id(), WorkspaceRole.MEMBER));
        workspaceMemberRepository.save(new WorkspaceMember(ws1Id, charlie.id(), WorkspaceRole.MEMBER));

        // 3. Channels in Workspace 1
        // Public channel: Alice is member, Bob is NOT
        ChannelResponse pubChan = createChannel(aliceToken, ws1Id, "general", false);
        publicChannelId = pubChan.id();

        // Private channel: Alice is member, Bob is NOT
        ChannelResponse privChan = createChannel(aliceToken, ws1Id, "confidential", true);
        privateChannelId = privChan.id();

        // DM between Alice and Charlie: Alice is member, Bob is outsider
        DmChannelResponse dm = createDm(aliceToken, ws1Id, charlie.id());
        dmChannelId = dm.id();

        // 4. Channel in Workspace 2
        ChannelResponse otherChan = createChannel(aliceToken, ws2Id, "ws2-public", false);
        otherWsChannelId = otherChan.id();
    }

    @ParameterizedTest(name = "{index}: [{0}] on [{1}] via [{2}] -> expected {3}")
    @MethodSource("matrixCases")
    @DisplayName("Channel permission matrix enforcement across actors, targets, and endpoints")
    void testPermissionMatrix(ActorType actor, TargetType target, EndpointType endpoint, int expectedStatus) {
        String token = switch (actor) {
            case NON_MEMBER -> eveToken;
            case WS_MEMBER_NOT_IN_CHANNEL, DM_OUTSIDER -> bobToken;
            case CHANNEL_MEMBER -> aliceToken;
        };

        Long channelId = switch (target) {
            case PUBLIC_CHANNEL -> publicChannelId;
            case PRIVATE_CHANNEL -> privateChannelId;
            case DM_CHANNEL -> dmChannelId;
            case OTHER_WORKSPACE_CHANNEL -> otherWsChannelId;
        };

        ResponseEntity<String> response = executeEndpoint(endpoint, ws1Id, channelId, token);
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
    }

    private static Stream<Arguments> matrixCases() {
        return Stream.of(
                // --- TARGET 1: PUBLIC CHANNEL ---
                // Non-member
                Arguments.of(ActorType.NON_MEMBER, TargetType.PUBLIC_CHANNEL, EndpointType.GET_MESSAGES, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.PUBLIC_CHANNEL, EndpointType.POST_MESSAGES, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.PUBLIC_CHANNEL, EndpointType.POST_JOIN, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.PUBLIC_CHANNEL, EndpointType.POST_MEMBERS, 403),

                // WS member not joined
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.PUBLIC_CHANNEL, EndpointType.GET_MESSAGES, 403),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.PUBLIC_CHANNEL, EndpointType.POST_MESSAGES, 403),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.PUBLIC_CHANNEL, EndpointType.POST_JOIN, 200),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.PUBLIC_CHANNEL, EndpointType.POST_MEMBERS, 403),

                // Channel member
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.PUBLIC_CHANNEL, EndpointType.GET_MESSAGES, 200),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.PUBLIC_CHANNEL, EndpointType.POST_MESSAGES, 201),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.PUBLIC_CHANNEL, EndpointType.POST_JOIN, 200),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.PUBLIC_CHANNEL, EndpointType.POST_MEMBERS, 200),

                // --- TARGET 2: PRIVATE CHANNEL ---
                // Non-member
                Arguments.of(ActorType.NON_MEMBER, TargetType.PRIVATE_CHANNEL, EndpointType.GET_MESSAGES, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.PRIVATE_CHANNEL, EndpointType.POST_MESSAGES, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.PRIVATE_CHANNEL, EndpointType.POST_JOIN, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.PRIVATE_CHANNEL, EndpointType.POST_MEMBERS, 403),

                // WS member not joined (must be 404 to avoid leaking existence)
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.PRIVATE_CHANNEL, EndpointType.GET_MESSAGES, 404),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.PRIVATE_CHANNEL, EndpointType.POST_MESSAGES, 404),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.PRIVATE_CHANNEL, EndpointType.POST_JOIN, 404),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.PRIVATE_CHANNEL, EndpointType.POST_MEMBERS, 404),

                // Channel member
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.PRIVATE_CHANNEL, EndpointType.GET_MESSAGES, 200),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.PRIVATE_CHANNEL, EndpointType.POST_MESSAGES, 201),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.PRIVATE_CHANNEL, EndpointType.POST_JOIN, 200),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.PRIVATE_CHANNEL, EndpointType.POST_MEMBERS, 200),

                // --- TARGET 3: DM CHANNEL ---
                // Non-member
                Arguments.of(ActorType.NON_MEMBER, TargetType.DM_CHANNEL, EndpointType.GET_MESSAGES, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.DM_CHANNEL, EndpointType.POST_MESSAGES, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.DM_CHANNEL, EndpointType.POST_JOIN, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.DM_CHANNEL, EndpointType.POST_MEMBERS, 403),

                // DM outsider (WS member, but not in this DM)
                Arguments.of(ActorType.DM_OUTSIDER, TargetType.DM_CHANNEL, EndpointType.GET_MESSAGES, 404),
                Arguments.of(ActorType.DM_OUTSIDER, TargetType.DM_CHANNEL, EndpointType.POST_MESSAGES, 404),
                Arguments.of(ActorType.DM_OUTSIDER, TargetType.DM_CHANNEL, EndpointType.POST_JOIN, 404),
                Arguments.of(ActorType.DM_OUTSIDER, TargetType.DM_CHANNEL, EndpointType.POST_MEMBERS, 404),

                // Channel member (DM participant)
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.DM_CHANNEL, EndpointType.GET_MESSAGES, 200),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.DM_CHANNEL, EndpointType.POST_MESSAGES, 201),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.DM_CHANNEL, EndpointType.POST_JOIN, 404),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.DM_CHANNEL, EndpointType.POST_MEMBERS, 404),

                // --- TARGET 4: OTHER-WORKSPACE CHANNEL ---
                // Non-member
                Arguments.of(ActorType.NON_MEMBER, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.GET_MESSAGES, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_MESSAGES, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_JOIN, 403),
                Arguments.of(ActorType.NON_MEMBER, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_MEMBERS, 403),

                // WS1 member
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.GET_MESSAGES, 404),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_MESSAGES, 404),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_JOIN, 404),
                Arguments.of(ActorType.WS_MEMBER_NOT_IN_CHANNEL, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_MEMBERS, 404),

                // Channel member / WS1 owner
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.GET_MESSAGES, 404),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_MESSAGES, 404),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_JOIN, 404),
                Arguments.of(ActorType.CHANNEL_MEMBER, TargetType.OTHER_WORKSPACE_CHANNEL, EndpointType.POST_MEMBERS, 404)
        );
    }

    private ResponseEntity<String> executeEndpoint(EndpointType endpoint, Long wsId, Long channelId, String token) {
        String baseUrl = "/api/workspaces/" + wsId + "/channels/" + channelId;
        HttpHeaders headers = authHeaders(token);

        return switch (endpoint) {
            case GET_MESSAGES -> restTemplate.exchange(
                    baseUrl + "/messages",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    String.class
            );
            case POST_MESSAGES -> restTemplate.exchange(
                    baseUrl + "/messages",
                    HttpMethod.POST,
                    new HttpEntity<>(new SendMessageRequest("Matrix test message"), headers),
                    String.class
            );
            case POST_JOIN -> restTemplate.exchange(
                    baseUrl + "/join",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    String.class
            );
            case POST_MEMBERS -> restTemplate.exchange(
                    baseUrl + "/members",
                    HttpMethod.POST,
                    new HttpEntity<>(new AddChannelMemberRequest(targetUserId), headers),
                    String.class
            );
        };
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private String signupAndLogin(String email, String password, String displayName) {
        restTemplate.postForEntity(
                "/api/auth/signup",
                new SignupRequest(email, password, displayName),
                UserResponse.class
        );
        return login(email, password);
    }

    private UserResponse signupAndReturnUser(String email, String password, String displayName) {
        ResponseEntity<UserResponse> resp = restTemplate.postForEntity(
                "/api/auth/signup",
                new SignupRequest(email, password, displayName),
                UserResponse.class
        );
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private String login(String email, String password) {
        ResponseEntity<AuthResponse> loginResp = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest(email, password),
                AuthResponse.class
        );
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResp.getBody()).isNotNull();
        return loginResp.getBody().token();
    }

    private WorkspaceResponse createWorkspace(String token, String name) {
        ResponseEntity<WorkspaceResponse> response = restTemplate.exchange(
                "/api/workspaces",
                HttpMethod.POST,
                new HttpEntity<>(new CreateWorkspaceRequest(name), authHeaders(token)),
                WorkspaceResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ChannelResponse createChannel(String token, Long workspaceId, String name, boolean isPrivate) {
        ResponseEntity<ChannelResponse> response = restTemplate.exchange(
                "/api/workspaces/" + workspaceId + "/channels",
                HttpMethod.POST,
                new HttpEntity<>(new CreateChannelRequest(name, isPrivate), authHeaders(token)),
                ChannelResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private DmChannelResponse createDm(String token, Long workspaceId, Long targetUserId) {
        ResponseEntity<DmChannelResponse> response = restTemplate.exchange(
                "/api/workspaces/" + workspaceId + "/dms",
                HttpMethod.POST,
                new HttpEntity<>(new CreateDmRequest(targetUserId), authHeaders(token)),
                DmChannelResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
