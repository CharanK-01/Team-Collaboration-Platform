package com.charan.backend.channel;

import com.charan.backend.BaseIntegrationTest;
import com.charan.backend.auth.AuthResponse;
import com.charan.backend.auth.LoginRequest;
import com.charan.backend.auth.SignupRequest;
import com.charan.backend.auth.UserResponse;
import com.charan.backend.invite.WorkspaceInviteRepository;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.CreateWorkspaceRequest;
import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.workspace.WorkspaceRepository;
import com.charan.backend.workspace.WorkspaceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private WorkspaceInviteRepository workspaceInviteRepository;

    @Autowired
    private ChannelRepository channelRepository;

    @Autowired
    private ChannelMemberRepository channelMemberRepository;

    @BeforeEach
    void setUp() {
        channelMemberRepository.deleteAll();
        channelRepository.deleteAll();
        workspaceInviteRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }

    // =========================================================================
    // 1. Channel Creation
    // =========================================================================

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels - Create public channel returns 201 and creator is a member")
    void createChannel_public_returns201AndCreatorIsMember() {
        String token = signupAndLogin("alice@example.com", "password123", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "Workspace A");

        CreateChannelRequest request = new CreateChannelRequest("general", false);
        ResponseEntity<ChannelResponse> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(token)),
                ChannelResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("general");
        assertThat(response.getBody().type()).isEqualTo(ChannelType.PUBLIC);
        assertThat(response.getBody().isPrivate()).isFalse();
        assertThat(response.getBody().joined()).isTrue();
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels - Create private channel returns 201 and creator is a member")
    void createChannel_private_returns201AndCreatorIsMember() {
        String token = signupAndLogin("alice@example.com", "password123", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "Workspace A");

        CreateChannelRequest request = new CreateChannelRequest("secret-project", true);
        ResponseEntity<ChannelResponse> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(token)),
                ChannelResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("secret-project");
        assertThat(response.getBody().type()).isEqualTo(ChannelType.PRIVATE);
        assertThat(response.getBody().isPrivate()).isTrue();
        assertThat(response.getBody().joined()).isTrue();
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels - Duplicate channel name in workspace returns 409 Conflict")
    void createChannel_duplicateName_returns409() {
        String token = signupAndLogin("alice@example.com", "password123", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "Workspace A");

        // First creation succeeds
        CreateChannelRequest request1 = new CreateChannelRequest("general", false);
        restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels",
                HttpMethod.POST,
                new HttpEntity<>(request1, authHeaders(token)),
                ChannelResponse.class
        );

        // Second creation with same name returns 409 Conflict
        CreateChannelRequest request2 = new CreateChannelRequest("general", false);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels",
                HttpMethod.POST,
                new HttpEntity<>(request2, authHeaders(token)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels - Bad channel name returns 400 Bad Request")
    void createChannel_badName_returns400() {
        String token = signupAndLogin("alice@example.com", "password123", "Alice");
        WorkspaceResponse ws = createWorkspace(token, "Workspace A");

        // Name with uppercase letters, spaces, or invalid characters
        CreateChannelRequest badRequest = new CreateChannelRequest("General Chat!", false);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels",
                HttpMethod.POST,
                new HttpEntity<>(badRequest, authHeaders(token)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels - Non-workspace member returns 403 Forbidden")
    void createChannel_callerNotWorkspaceMember_returns403() {
        String ownerToken = signupAndLogin("alice@example.com", "password123", "Alice");
        String outsiderToken = signupAndLogin("bob@example.com", "password123", "Bob");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Workspace A");

        CreateChannelRequest request = new CreateChannelRequest("dev", false);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(outsiderToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // =========================================================================
    // 2. Channel Listing & Visibility
    // =========================================================================

    @Test
    @DisplayName("GET /api/workspaces/{workspaceId}/channels - Public channels visible to all members; private channels hidden from non-members")
    void listChannels_returnsPublicAndJoinedPrivateChannelsOnly() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");
        String bobToken = login("bob@example.com", "password123");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");

        // Alice invites Bob to workspace and Bob joins
        joinUserToWorkspace(ws.id(), bob.id());

        // Alice creates:
        // 1. public channel "general"
        // 2. private channel "alice-private"
        ChannelResponse publicChannel = createChannel(aliceToken, ws.id(), "general", false);
        ChannelResponse privateChannel = createChannel(aliceToken, ws.id(), "alice-private", true);

        // Bob lists channels: MUST see "general" (with joined=false), MUST NOT see "alice-private"
        ResponseEntity<ChannelResponse[]> bobList = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(bobToken)),
                ChannelResponse[].class
        );

        assertThat(bobList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobList.getBody()).isNotNull();
        List<ChannelResponse> channelsSeenByBob = Arrays.asList(bobList.getBody());

        assertThat(channelsSeenByBob).anyMatch(c -> c.id().equals(publicChannel.id()) && !c.joined());
        assertThat(channelsSeenByBob).noneMatch(c -> c.id().equals(privateChannel.id()));

        // Alice lists channels: MUST see both "general" (joined=true) and "alice-private" (joined=true)
        ResponseEntity<ChannelResponse[]> aliceList = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(aliceToken)),
                ChannelResponse[].class
        );

        assertThat(aliceList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliceList.getBody()).isNotNull();
        List<ChannelResponse> channelsSeenByAlice = Arrays.asList(aliceList.getBody());
        assertThat(channelsSeenByAlice).anyMatch(c -> c.id().equals(publicChannel.id()) && c.joined());
        assertThat(channelsSeenByAlice).anyMatch(c -> c.id().equals(privateChannel.id()) && c.joined());
    }

    // =========================================================================
    // 3. Joining Channels
    // =========================================================================

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels/{channelId}/join - Join public channel returns 200 and caller is added")
    void joinChannel_publicChannel_returns200AndCallerJoins() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");
        String bobToken = login("bob@example.com", "password123");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());

        ChannelResponse publicChannel = createChannel(aliceToken, ws.id(), "general", false);

        ResponseEntity<ChannelResponse> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels/" + publicChannel.id() + "/join",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(bobToken)),
                ChannelResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().joined()).isTrue();
        assertThat(channelMemberRepository.existsByIdChannelIdAndIdUserId(publicChannel.id(), bob.id())).isTrue();
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels/{channelId}/join - Joining twice is idempotent and returns 200")
    void joinChannel_publicChannelTwice_isIdempotentAndReturns200() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");
        String bobToken = login("bob@example.com", "password123");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());
        ChannelResponse publicChannel = createChannel(aliceToken, ws.id(), "general", false);

        // First join
        restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels/" + publicChannel.id() + "/join",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(bobToken)),
                ChannelResponse.class
        );

        // Second join
        ResponseEntity<ChannelResponse> response2 = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels/" + publicChannel.id() + "/join",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(bobToken)),
                ChannelResponse.class
        );

        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response2.getBody()).isNotNull();
        assertThat(response2.getBody().joined()).isTrue();
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels/{channelId}/join - Non-member attempting to join private channel returns 404")
    void joinChannel_privateChannelCallerNotIn_returns404() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");
        String bobToken = login("bob@example.com", "password123");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());

        ChannelResponse privateChannel = createChannel(aliceToken, ws.id(), "confidential", true);

        // Bob tries to join Alice's private channel
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels/" + privateChannel.id() + "/join",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(bobToken)),
                String.class
        );

        // Must return 404 to avoid leaking existence
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // 4. Adding Members to Private Channel
    // =========================================================================

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels/{channelId}/members - Channel member adds workspace member returns 200")
    void addMember_validRequest_returns200AndTargetJoinsPrivateChannel() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());

        ChannelResponse privateChannel = createChannel(aliceToken, ws.id(), "strategy", true);

        // Alice adds Bob to "strategy"
        AddChannelMemberRequest request = new AddChannelMemberRequest(bob.id());
        ResponseEntity<ChannelResponse> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels/" + privateChannel.id() + "/members",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(aliceToken)),
                ChannelResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(channelMemberRepository.existsByIdChannelIdAndIdUserId(privateChannel.id(), bob.id())).isTrue();
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels/{channelId}/members - Caller not in channel returns 403 Forbidden")
    void addMember_callerNotInChannel_returns403() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");
        String bobToken = login("bob@example.com", "password123");
        UserResponse charlie = signupAndReturnUser("charlie@example.com", "password123", "Charlie");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());
        joinUserToWorkspace(ws.id(), charlie.id());

        ChannelResponse privateChannel = createChannel(aliceToken, ws.id(), "leadership", true);

        // Bob (not in leadership channel) attempts to add Charlie
        AddChannelMemberRequest request = new AddChannelMemberRequest(charlie.id());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels/" + privateChannel.id() + "/members",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(bobToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/channels/{channelId}/members - Target not in workspace returns 400 Bad Request")
    void addMember_targetNotInWorkspace_returns400() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse outsider = signupAndReturnUser("outsider@example.com", "password123", "Outsider");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        ChannelResponse privateChannel = createChannel(aliceToken, ws.id(), "strategy", true);

        // Alice attempts to add someone who is not a member of Workspace A
        AddChannelMemberRequest request = new AddChannelMemberRequest(outsider.id());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/channels/" + privateChannel.id() + "/members",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(aliceToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // 5. Direct Messages (DMs)
    // =========================================================================

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/dms - Create DM between two workspace members returns 200")
    void createDm_validRequest_returns200() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());

        CreateDmRequest request = new CreateDmRequest(bob.id());
        ResponseEntity<DmChannelResponse> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/dms",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(aliceToken)),
                DmChannelResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().type()).isEqualTo(ChannelType.DM);
        assertThat(response.getBody().otherUser()).isNotNull();
        assertThat(response.getBody().otherUser().email()).isEqualTo("bob@example.com");
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/dms - Calling DM create twice returns identical channel ID")
    void createDm_calledTwice_returnsSameChannelId() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());

        CreateDmRequest request = new CreateDmRequest(bob.id());

        // First call
        ResponseEntity<DmChannelResponse> resp1 = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/dms",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(aliceToken)),
                DmChannelResponse.class
        );

        // Second call
        ResponseEntity<DmChannelResponse> resp2 = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/dms",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(aliceToken)),
                DmChannelResponse.class
        );

        assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp1.getBody()).isNotNull();
        assertThat(resp2.getBody()).isNotNull();
        assertThat(resp1.getBody().id()).isEqualTo(resp2.getBody().id());
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/dms - DMing yourself returns 400 Bad Request")
    void createDm_dmSelf_returns400() {
        UserResponse alice = signupAndReturnUser("alice@example.com", "password123", "Alice");
        String aliceToken = login("alice@example.com", "password123");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");

        CreateDmRequest request = new CreateDmRequest(alice.id());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/dms",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(aliceToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/dms - Target not in workspace returns 400 Bad Request")
    void createDm_targetNotWorkspaceMember_returns400() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse outsider = signupAndReturnUser("outsider@example.com", "password123", "Outsider");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");

        CreateDmRequest request = new CreateDmRequest(outsider.id());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/dms",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(aliceToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/workspaces/{workspaceId}/dms - Caller not in workspace returns 403 Forbidden")
    void createDm_callerNotWorkspaceMember_returns403() {
        String ownerToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");
        String outsiderToken = signupAndLogin("outsider@example.com", "password123", "Outsider");

        WorkspaceResponse ws = createWorkspace(ownerToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());

        CreateDmRequest request = new CreateDmRequest(bob.id());
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/dms",
                HttpMethod.POST,
                new HttpEntity<>(request, authHeaders(outsiderToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /api/workspaces/{workspaceId}/dms - Returns only caller's DM channels")
    void listDms_returnsOnlyCallerDms() {
        String aliceToken = signupAndLogin("alice@example.com", "password123", "Alice");
        UserResponse bob = signupAndReturnUser("bob@example.com", "password123", "Bob");
        String bobToken = login("bob@example.com", "password123");
        UserResponse charlie = signupAndReturnUser("charlie@example.com", "password123", "Charlie");

        WorkspaceResponse ws = createWorkspace(aliceToken, "Workspace A");
        joinUserToWorkspace(ws.id(), bob.id());
        joinUserToWorkspace(ws.id(), charlie.id());

        // Alice creates DM with Bob
        createDm(aliceToken, ws.id(), bob.id());
        // Bob creates DM with Charlie
        createDm(bobToken, ws.id(), charlie.id());

        // Alice lists DMs: should see 1 DM (with Bob), but NOT Bob-Charlie DM
        ResponseEntity<DmChannelResponse[]> aliceDms = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/dms",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(aliceToken)),
                DmChannelResponse[].class
        );

        assertThat(aliceDms.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliceDms.getBody()).isNotNull();
        List<DmChannelResponse> aliceDmList = Arrays.asList(aliceDms.getBody());
        assertThat(aliceDmList).hasSize(1);
        assertThat(aliceDmList.get(0).otherUser().email()).isEqualTo("bob@example.com");

        // Bob lists DMs: should see 2 DMs (with Alice and with Charlie)
        ResponseEntity<DmChannelResponse[]> bobDms = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/dms",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(bobToken)),
                DmChannelResponse[].class
        );

        assertThat(bobDms.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobDms.getBody()).isNotNull();
        List<DmChannelResponse> bobDmList = Arrays.asList(bobDms.getBody());
        assertThat(bobDmList).hasSize(2);
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

    private void joinUserToWorkspace(Long workspaceId, Long userId) {
        workspaceMemberRepository.save(new com.charan.backend.workspace.WorkspaceMember(
                workspaceId, userId, com.charan.backend.workspace.WorkspaceRole.MEMBER
        ));
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

    private void createDm(String token, Long workspaceId, Long targetUserId) {
        ResponseEntity<DmChannelResponse> response = restTemplate.exchange(
                "/api/workspaces/" + workspaceId + "/dms",
                HttpMethod.POST,
                new HttpEntity<>(new CreateDmRequest(targetUserId), authHeaders(token)),
                DmChannelResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
