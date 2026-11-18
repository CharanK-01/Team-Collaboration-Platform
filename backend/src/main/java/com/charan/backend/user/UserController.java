package com.charan.backend.user;

import com.charan.backend.auth.UserResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Returns the profile of the currently authenticated user.
     *
     * Spring Security decodes the Bearer JWT and injects it here as a Jwt object.
     * The JWT sub claim holds the user ID (stored as a String when the token was
     * created in JwtService). We parse it back to Long, fetch the user row, and
     * map it to a UserResponse record so the password hash is never sent to the client.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.parseLong(jwt.getSubject());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found"));
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
