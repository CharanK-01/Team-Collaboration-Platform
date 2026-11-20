package com.charan.backend.workspace;

import com.charan.backend.BaseIntegrationTest;
import com.charan.backend.auth.AuthResponse;
import com.charan.backend.auth.LoginRequest;
import com.charan.backend.auth.SignupRequest;
import com.charan.backend.auth.UserResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.HttpStatus;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.WorkspaceRepository;
import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.channel.ChannelRepository;
import com.charan.backend.channel.ChannelMemberRepository;
import com.charan.backend.message.MessageRepository;
import com.charan.backend.invite.WorkspaceInviteRepository;

class WorkspaceListTest extends BaseIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;

    @BeforeEach
    void clean() {
        // clean tables in FK‑safe order like other integration tests
        workspaceInviteRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }
    
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceMemberRepository workspaceMemberRepository;
    @Autowired private WorkspaceInviteRepository workspaceInviteRepository;

    @Test
    @DisplayName("GET /api/workspaces – user sees only their workspaces")
    void listWorkspaces_userSeesOwnWorkspaces() {
        // sign up Alice and Bob
        restTemplate.postForEntity("/api/auth/signup",
                new SignupRequest("alice@example.com", "password123", "Alice"),
                UserResponse.class);
        restTemplate.postForEntity("/api/auth/signup",
                new SignupRequest("bob@example.com", "password123", "Bob"),
                UserResponse.class);
        // login Alice
        ResponseEntity<AuthResponse> aliceLogin = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest("alice@example.com", "password123"),
                AuthResponse.class);
        assertThat(aliceLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        String aliceToken = aliceLogin.getBody().token();
        // Alice creates a workspace
        HttpHeaders ah = new HttpHeaders();
        ah.setBearerAuth(aliceToken);
        ResponseEntity<WorkspaceResponse> wsResp = restTemplate.exchange(
                "/api/workspaces",
                HttpMethod.POST,
                new HttpEntity<>(new CreateWorkspaceRequest("AliceWS"), ah),
                WorkspaceResponse.class);
        assertThat(wsResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Alice lists workspaces – should contain the one she created
        ResponseEntity<WorkspaceResponse[]> listResp = restTemplate.exchange(
                "/api/workspaces",
                HttpMethod.GET,
                new HttpEntity<>(ah),
                WorkspaceResponse[].class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).hasSize(1);
        assertThat(listResp.getBody()[0].id()).isEqualTo(wsResp.getBody().id());
        // login Bob (no workspace)
        ResponseEntity<AuthResponse> bobLogin = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest("bob@example.com", "password123"),
                AuthResponse.class);
        String bobToken = bobLogin.getBody().token();
        HttpHeaders bh = new HttpHeaders();
        bh.setBearerAuth(bobToken);
        ResponseEntity<WorkspaceResponse[]> bobList = restTemplate.exchange(
                "/api/workspaces",
                HttpMethod.GET,
                new HttpEntity<>(bh),
                WorkspaceResponse[].class);
        assertThat(bobList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobList.getBody()).isEmpty();
    }
}
