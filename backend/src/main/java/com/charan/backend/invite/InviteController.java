package com.charan.backend.invite;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invites")
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @PostMapping("/{token}/accept")
    public AcceptInviteResponse acceptInvite(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String token
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return inviteService.acceptInvite(userId, token);
    }
}
