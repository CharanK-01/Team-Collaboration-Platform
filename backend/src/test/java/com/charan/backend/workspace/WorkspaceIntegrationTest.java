package com.charan.backend.workspace;

import com.charan.backend.BaseIntegrationTest;
import com.charan.backend.auth.AuthResponse;
import com.charan.backend.auth.LoginRequest;
import com.charan.backend.auth.SignupRequest;
import com.charan.backend.auth.UserResponse;
import com.charan.backend.invite.AcceptInviteResponse;
import com.charan.backend.invite.CreateInviteRequest;
import com.charan.backend.invite.InviteResponse;
import com.charan.backend.invite.WorkspaceInvite;
import com.charan.backend.invite.WorkspaceInviteRepository;
import com.charan.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceIntegrationTest extends BaseIntegrationTest {

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

    /**
     * Test Isolation Strategy:
     * Clean all database tables before every test (@BeforeEach).
     * Child tables referencing users (invites, members, workspaces) are truncated first
     * to respect PostgreSQL foreign key constraints.
     */
    @BeforeEach
    void setUp() {
        workspaceInviteRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }

    // =========================================================================
    // 1. Workspace Creation & Member Access
    // =========================================================================

    @Test
    @DisplayName("POST /api/workspaces - Valid request returns 201 Created and caller is assigned OWNER role")
    void createWorkspace_validRequest_returns201AndCallerIsOwner() {
        String token = signupAndLogin("charan@example.com", "password123", "Charan");

        ResponseEntity<WorkspaceResponse> response = restTemplate.exchange(
                "/api/workspaces",
                HttpMethod.POST,
                new HttpEntity<>(new CreateWorkspaceRequest("Engineering"), authHeaders(token)),
                WorkspaceResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("Engineering");
        assertThat(response.getBody().role()).isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    @DisplayName("GET /api/workspaces/{id}/members - Non-member caller gets 404 Not Found")
    void getMembers_nonMember_returns404() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String nonMemberToken = signupAndLogin("bob@example.com", "password123", "Bob");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Secret Space");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/members",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(nonMemberToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /api/workspaces/{id}/members - Member gets 200 OK with all members and their roles")
    void getMembers_member_returns200WithAllMembersAndRoles() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String adminToken = signupAndLogin("dave@example.com", "password123", "Dave");
        String memberToken = signupAndLogin("bob@example.com", "password123", "Bob");

        WorkspaceResponse ws = createWorkspace(ownerToken, "General");

        // Invite and accept Dave as ADMIN
        String daveInvite = sendInvite(ownerToken, ws.id(), "dave@example.com", WorkspaceRole.ADMIN);
        acceptInvite(adminToken, daveInvite);

        // Invite and accept Bob as MEMBER
        String bobInvite = sendInvite(ownerToken, ws.id(), "bob@example.com", WorkspaceRole.MEMBER);
        acceptInvite(memberToken, bobInvite);

        ResponseEntity<WorkspaceMemberResponse[]> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/members",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(ownerToken)),
                WorkspaceMemberResponse[].class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        List<WorkspaceMemberResponse> members = Arrays.asList(response.getBody());
        assertThat(members).hasSize(3);

        assertThat(members).anyMatch(m -> m.email().equals("charan@example.com") && m.role() == WorkspaceRole.OWNER);
        assertThat(members).anyMatch(m -> m.email().equals("dave@example.com") && m.role() == WorkspaceRole.ADMIN);
        assertThat(members).anyMatch(m -> m.email().equals("bob@example.com") && m.role() == WorkspaceRole.MEMBER);
    }

    // =========================================================================
    // 2. Invite Creation Role Rules
    // =========================================================================

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - OWNER invites ADMIN returns 201 Created")
    void invite_ownerInvitesAdmin_returns201() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        ResponseEntity<InviteResponse> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest("dave@example.com", WorkspaceRole.ADMIN), authHeaders(ownerToken)),
                InviteResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().role()).isEqualTo(WorkspaceRole.ADMIN);
        assertThat(response.getBody().email()).isEqualTo("dave@example.com");
    }

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - OWNER invites MEMBER returns 201 Created")
    void invite_ownerInvitesMember_returns201() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        ResponseEntity<InviteResponse> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest("bob@example.com", WorkspaceRole.MEMBER), authHeaders(ownerToken)),
                InviteResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().role()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(response.getBody().email()).isEqualTo("bob@example.com");
    }

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - ADMIN invites MEMBER succeeds with 201 Created")
    void invite_adminInvitesMember_returns201() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String adminToken = signupAndLogin("dave@example.com", "password123", "Dave");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        String daveInvite = sendInvite(ownerToken, ws.id(), "dave@example.com", WorkspaceRole.ADMIN);
        acceptInvite(adminToken, daveInvite);

        ResponseEntity<InviteResponse> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest("heidi@example.com", WorkspaceRole.MEMBER), authHeaders(adminToken)),
                InviteResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().role()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(response.getBody().email()).isEqualTo("heidi@example.com");
    }

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - ADMIN invites ADMIN returns 403 Forbidden")
    void invite_adminInvitesAdmin_returns403() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String adminToken = signupAndLogin("dave@example.com", "password123", "Dave");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        String daveInvite = sendInvite(ownerToken, ws.id(), "dave@example.com", WorkspaceRole.ADMIN);
        acceptInvite(adminToken, daveInvite);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest("grace@example.com", WorkspaceRole.ADMIN), authHeaders(adminToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - MEMBER invites anyone returns 403 Forbidden")
    void invite_memberInvitesAnyone_returns403() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String memberToken = signupAndLogin("bob@example.com", "password123", "Bob");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        String bobInvite = sendInvite(ownerToken, ws.id(), "bob@example.com", WorkspaceRole.MEMBER);
        acceptInvite(memberToken, bobInvite);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest("frank@example.com", WorkspaceRole.MEMBER), authHeaders(memberToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - Inviting with role OWNER is rejected with 403 Forbidden")
    void invite_withRoleOwner_returns403() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest("someone@example.com", WorkspaceRole.OWNER), authHeaders(ownerToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - Inviting an existing member returns 409 Conflict")
    void invite_existingMember_returns409() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String memberToken = signupAndLogin("bob@example.com", "password123", "Bob");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        String bobInvite = sendInvite(ownerToken, ws.id(), "bob@example.com", WorkspaceRole.MEMBER);
        acceptInvite(memberToken, bobInvite);

        // Attempting to re-invite Bob who is already an active member
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest("bob@example.com", WorkspaceRole.MEMBER), authHeaders(ownerToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - Second pending invite for the same email returns 409 Conflict")
    void invite_secondPendingInviteForSameEmail_returns409() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        // First invite succeeds
        sendInvite(ownerToken, ws.id(), "pending@example.com", WorkspaceRole.MEMBER);

        // Second invite for the same email while first is still pending
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest("pending@example.com", WorkspaceRole.MEMBER), authHeaders(ownerToken)),
                String.class
        );

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /api/workspaces/{id}/invites - Missing role in request body returns 400 Bad Request")
    void invite_missingRole_returns400() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        HttpHeaders headers = authHeaders(ownerToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>("{\"email\":\"norole@example.com\"}", headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/workspaces/" + ws.id() + "/invites",
                HttpMethod.POST,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // =========================================================================
    // 3. Invite Acceptance Rules
    // =========================================================================

    @Test
    @DisplayName("POST /api/invites/{token}/accept - Wrong email returns 403 Forbidden")
    void acceptInvite_wrongEmail_returns403() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String bobToken = signupAndLogin("bob@example.com", "password123", "Bob");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        String daveInviteToken = sendInvite(ownerToken, ws.id(), "dave@example.com", WorkspaceRole.ADMIN);

        // Bob tries to accept Dave's invite
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/invites/" + daveInviteToken + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(bobToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("POST /api/invites/{token}/accept - Right email returns 200 OK and assigns role")
    void acceptInvite_rightEmail_returns200AndAssignsRole() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String daveToken = signupAndLogin("dave@example.com", "password123", "Dave");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        String daveInviteToken = sendInvite(ownerToken, ws.id(), "dave@example.com", WorkspaceRole.ADMIN);

        ResponseEntity<AcceptInviteResponse> response = restTemplate.exchange(
                "/api/invites/" + daveInviteToken + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(daveToken)),
                AcceptInviteResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().workspaceId()).isEqualTo(ws.id());
        assertThat(response.getBody().role()).isEqualTo(WorkspaceRole.ADMIN);
    }

    @Test
    @DisplayName("POST /api/invites/{token}/accept - Accepting the same invite twice returns 400 Bad Request")
    void acceptInvite_acceptTwice_returns400() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String daveToken = signupAndLogin("dave@example.com", "password123", "Dave");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        String daveInviteToken = sendInvite(ownerToken, ws.id(), "dave@example.com", WorkspaceRole.ADMIN);
        acceptInvite(daveToken, daveInviteToken);

        // Accepting a second time
        ResponseEntity<String> secondAccept = restTemplate.exchange(
                "/api/invites/" + daveInviteToken + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(daveToken)),
                String.class
        );

        assertThat(secondAccept.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/invites/{token}/accept - Expired invite returns 400 Bad Request")
    void acceptInvite_expiredInvite_returns400() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String daveToken = signupAndLogin("dave@example.com", "password123", "Dave");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        // Manually insert an expired invite row (expires_at in the past)
        String expiredToken = "expired-token-" + System.currentTimeMillis();
        WorkspaceInvite expiredInvite = new WorkspaceInvite(
                ws.id(),
                "dave@example.com",
                WorkspaceRole.MEMBER,
                expiredToken,
                ws.ownerId(),
                Instant.now().minus(2, ChronoUnit.DAYS)
        );
        workspaceInviteRepository.save(expiredInvite);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/invites/" + expiredToken + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(daveToken)),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/invites/{token}/accept - Invite email case-insensitivity allows acceptance")
    void invite_caseInsensitiveEmail_acceptSucceeds() {
        String ownerToken = signupAndLogin("charan@example.com", "password123", "Charan");
        // User registered with lowercase email
        String daveToken = signupAndLogin("dave@example.com", "password123", "Dave");
        WorkspaceResponse ws = createWorkspace(ownerToken, "Space");

        // Invite issued with mixed-case email
        String inviteToken = sendInvite(ownerToken, ws.id(), "Dave@Example.COM", WorkspaceRole.MEMBER);

        ResponseEntity<AcceptInviteResponse> response = restTemplate.exchange(
                "/api/invites/" + inviteToken + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(daveToken)),
                AcceptInviteResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().role()).isEqualTo(WorkspaceRole.MEMBER);
    }

    // =========================================================================
    // 4. Workspace Isolation
    // =========================================================================

    @Test
    @DisplayName("GET /api/workspaces - Returns only the workspaces the caller belongs to")
    void getWorkspaces_returnsOnlyCallerWorkspaces() {
        String charanToken = signupAndLogin("charan@example.com", "password123", "Charan");
        String daveToken = signupAndLogin("dave@example.com", "password123", "Dave");

        WorkspaceResponse charanWs = createWorkspace(charanToken, "Charan Space");
        WorkspaceResponse daveWs = createWorkspace(daveToken, "Dave Solo Space");

        // Charan queries workspaces: MUST see Charan Space, MUST NOT see Dave Solo Space
        ResponseEntity<WorkspaceResponse[]> charanResponse = restTemplate.exchange(
                "/api/workspaces",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(charanToken)),
                WorkspaceResponse[].class
        );
        assertThat(charanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(charanResponse.getBody()).isNotNull();
        List<WorkspaceResponse> charanList = Arrays.asList(charanResponse.getBody());
        assertThat(charanList).anyMatch(w -> w.id().equals(charanWs.id()));
        assertThat(charanList).noneMatch(w -> w.id().equals(daveWs.id()));

        // Dave queries workspaces: MUST see Dave Solo Space, MUST NOT see Charan Space
        ResponseEntity<WorkspaceResponse[]> daveResponse = restTemplate.exchange(
                "/api/workspaces",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(daveToken)),
                WorkspaceResponse[].class
        );
        assertThat(daveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(daveResponse.getBody()).isNotNull();
        List<WorkspaceResponse> daveList = Arrays.asList(daveResponse.getBody());
        assertThat(daveList).anyMatch(w -> w.id().equals(daveWs.id()));
        assertThat(daveList).noneMatch(w -> w.id().equals(charanWs.id()));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String signupAndLogin(String email, String password, String displayName) {
        restTemplate.postForEntity(
                "/api/auth/signup",
                new SignupRequest(email, password, displayName),
                UserResponse.class
        );

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest(email, password),
                AuthResponse.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        return loginResponse.getBody().token();
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

    private String sendInvite(String callerToken, Long workspaceId, String email, WorkspaceRole role) {
        ResponseEntity<InviteResponse> response = restTemplate.exchange(
                "/api/workspaces/" + workspaceId + "/invites",
                HttpMethod.POST,
                new HttpEntity<>(new CreateInviteRequest(email, role), authHeaders(callerToken)),
                InviteResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().token();
    }

    private void acceptInvite(String callerToken, String token) {
        ResponseEntity<AcceptInviteResponse> response = restTemplate.exchange(
                "/api/invites/" + token + "/accept",
                HttpMethod.POST,
                new HttpEntity<>(authHeaders(callerToken)),
                AcceptInviteResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
