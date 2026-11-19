package com.charan.backend.channel;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/dms")
public class DmController {

    private final ChannelService channelService;

    public DmController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @PostMapping
    public DmChannelResponse createOrGetDm(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long workspaceId,
            @Valid @RequestBody CreateDmRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return channelService.getOrCreateDm(userId, workspaceId, request);
    }

    @GetMapping
    public List<DmChannelResponse> listDms(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long workspaceId
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return channelService.listDms(userId, workspaceId);
    }
}
