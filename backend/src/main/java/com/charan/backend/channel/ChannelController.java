package com.charan.backend.channel;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/channels")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelResponse createChannel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long workspaceId,
            @Valid @RequestBody CreateChannelRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return channelService.createChannel(userId, workspaceId, request);
    }

    @GetMapping
    public List<ChannelResponse> listChannels(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long workspaceId
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return channelService.listChannels(userId, workspaceId);
    }

    @PostMapping("/{channelId}/join")
    public ChannelResponse joinChannel(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long workspaceId,
            @PathVariable Long channelId
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return channelService.joinChannel(userId, workspaceId, channelId);
    }

    @PostMapping("/{channelId}/members")
    public ChannelResponse addMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long workspaceId,
            @PathVariable Long channelId,
            @Valid @RequestBody AddChannelMemberRequest request
    ) {
        Long userId = Long.parseLong(jwt.getSubject());
        return channelService.addMemberToChannel(userId, workspaceId, channelId, request);
    }
}
