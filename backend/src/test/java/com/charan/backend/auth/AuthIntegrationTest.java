package com.charan.backend.auth;

import com.charan.backend.BaseIntegrationTest;
import com.charan.backend.invite.WorkspaceInviteRepository;
import com.charan.backend.user.User;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.workspace.WorkspaceRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIntegrationTest extends BaseIntegrationTest {

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
    private PasswordEncoder passwordEncoder;

    /**
     * Test Isolation Strategy:
     * We clean all database tables before every test (@BeforeEach).
     * Child tables referencing users (invites, members, workspaces) are deleted first
     * to respect PostgreSQL foreign key constraints.
     * This guarantees that tests run completely independently of each other,
     * regardless of execution order or JVM thread scheduling.
     */
    @BeforeEach
    void setUp() {
        workspaceInviteRepository.deleteAll();
        workspaceMemberRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /api/auth/signup - Valid credentials return 201 Created and user response")
    void signup_validCredentials_returns201() {
        SignupRequest request = new SignupRequest("alice@example.com", "password123", "Alice");

        ResponseEntity<UserResponse> response = restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                UserResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo("alice@example.com");
        assertThat(response.getBody().displayName()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("POST /api/auth/signup - Duplicate email returns 409 Conflict")
    void signup_duplicateEmail_returns409() {
        SignupRequest request1 = new SignupRequest("bob@example.com", "password123", "Bob");
        ResponseEntity<UserResponse> firstSignup = restTemplate.postForEntity(
                "/api/auth/signup",
                request1,
                UserResponse.class
        );
        assertThat(firstSignup.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Attempting to signup again with the same email (even with different case/display name)
        SignupRequest request2 = new SignupRequest("BOB@example.com", "differentPassword123", "Bob Duplicate");
        ResponseEntity<String> duplicateSignup = restTemplate.postForEntity(
                "/api/auth/signup",
                request2,
                String.class
        );

        assertThat(duplicateSignup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /api/auth/signup - Short password (< 8 chars) returns 400 Bad Request")
    void signup_shortPassword_returns400() {
        SignupRequest request = new SignupRequest("charlie@example.com", "short", "Charlie");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("POST /api/auth/signup - Stored password_hash starts with $2a$ and matches BCrypt")
    void signup_storesBcryptPasswordHashInDatabase() {
        String rawPassword = "securePassword123";
        SignupRequest request = new SignupRequest("dave@example.com", rawPassword, "Dave");

        ResponseEntity<UserResponse> response = restTemplate.postForEntity(
                "/api/auth/signup",
                request,
                UserResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Query raw entity directly from database to inspect the stored hash
        Optional<User> userOptional = userRepository.findByEmail("dave@example.com");
        assertThat(userOptional).isPresent();
        User user = userOptional.get();

        String storedHash = user.getPasswordHash();
        assertThat(storedHash).isNotNull();
        // Must never store the plaintext password
        assertThat(storedHash).isNotEqualTo(rawPassword);
        // Must be a standard BCrypt hash (starts with $2a$, $2b$, or $2y$)
        assertThat(storedHash).startsWith("$2a$");
        // Must verify with BCrypt password encoder
        assertThat(passwordEncoder.matches(rawPassword, storedHash)).isTrue();
    }

    @Test
    @DisplayName("POST /api/auth/login - Valid credentials return 200 OK and JWT token")
    void login_validCredentials_returns200AndToken() {
        // Prepare user
        restTemplate.postForEntity(
                "/api/auth/signup",
                new SignupRequest("eva@example.com", "password123", "Eva"),
                UserResponse.class
        );

        LoginRequest loginRequest = new LoginRequest("eva@example.com", "password123");
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                "/api/auth/login",
                loginRequest,
                AuthResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().tokenType()).isEqualTo("Bearer");
        assertThat(response.getBody().expiresInSeconds()).isPositive();
        assertThat(response.getBody().user()).isNotNull();
        assertThat(response.getBody().user().email()).isEqualTo("eva@example.com");
    }

    @Test
    @DisplayName("POST /api/auth/login - Wrong password and unknown email both return 401 with identical error shape (status, error, path)")
    void login_wrongPasswordAndUnknownEmail_bothReturnIdentical401ErrorShape() {
        // Register user for wrong-password test
        restTemplate.postForEntity(
                "/api/auth/signup",
                new SignupRequest("frank@example.com", "correctPassword123", "Frank"),
                UserResponse.class
        );

        // 1. Registered email with WRONG password
        ResponseEntity<String> wrongPasswordResponse = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest("frank@example.com", "wrongPassword123"),
                String.class
        );
        assertThat(wrongPasswordResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 2. UNKNOWN email with any password
        ResponseEntity<String> unknownEmailResponse = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest("nonexistent@example.com", "anyPassword123"),
                String.class
        );
        assertThat(unknownEmailResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Parse JSON response bodies:
        // Default Spring Boot error bodies include: timestamp, status, error, and path.
        // Timestamps differ between requests, so we parse the JSON and compare the stable fields.
        int status1 = JsonPath.read(wrongPasswordResponse.getBody(), "$.status");
        int status2 = JsonPath.read(unknownEmailResponse.getBody(), "$.status");
        String error1 = JsonPath.read(wrongPasswordResponse.getBody(), "$.error");
        String error2 = JsonPath.read(unknownEmailResponse.getBody(), "$.error");
        String path1 = JsonPath.read(wrongPasswordResponse.getBody(), "$.path");
        String path2 = JsonPath.read(unknownEmailResponse.getBody(), "$.path");

        assertThat(status1).isEqualTo(401);
        assertThat(status2).isEqualTo(401);
        assertThat(error1).isEqualTo("Unauthorized");
        assertThat(error2).isEqualTo("Unauthorized");
        assertThat(path1).isEqualTo("/api/auth/login");
        assertThat(path2).isEqualTo("/api/auth/login");
    }

    @Test
    @DisplayName("GET /api/users/me - Valid token returns 200 OK with authenticated user profile")
    void getMe_validToken_returns200() {
        restTemplate.postForEntity(
                "/api/auth/signup",
                new SignupRequest("grace@example.com", "password123", "Grace"),
                UserResponse.class
        );
        String token = obtainToken("grace@example.com", "password123");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<UserResponse> response = restTemplate.exchange(
                "/api/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                UserResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().email()).isEqualTo("grace@example.com");
        assertThat(response.getBody().displayName()).isEqualTo("Grace");
    }

    @Test
    @DisplayName("GET /api/users/me - Missing token returns 401 Unauthorized")
    void getMe_missingToken_returns401() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/users/me",
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("GET /api/users/me - Tampered token returns 401 Unauthorized")
    void getMe_tamperedToken_returns401() {
        restTemplate.postForEntity(
                "/api/auth/signup",
                new SignupRequest("heidi@example.com", "password123", "Heidi"),
                UserResponse.class
        );
        String validToken = obtainToken("heidi@example.com", "password123");

        // Tamper with the token payload (middle segment) while keeping header and signature unchanged
        String[] parts = validToken.split("\\.");
        assertThat(parts).hasSize(3);

        String payload = parts[1];
        int mid = payload.length() / 2;
        char[] chars = payload.toCharArray();
        chars[mid] = (chars[mid] == 'A') ? 'B' : 'A';
        String tamperedPayload = new String(chars);
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tamperedToken);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/users/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String obtainToken(String email, String password) {
        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/auth/login",
                new LoginRequest(email, password),
                AuthResponse.class
        );
        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        return loginResponse.getBody().token();
    }
}
