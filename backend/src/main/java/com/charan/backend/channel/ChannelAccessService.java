package com.charan.backend.channel;

import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single source of truth for channel authorization and access rules.
 *
 * Enforces workspace membership, channel existence scoping, and channel visibility rules:
 * - User not in workspace -> 403 Forbidden
 * - Workspace or Channel not found in workspace -> 404 Not Found
 * - PRIVATE or DM channel where caller is not a member -> 404 Not Found (avoids leaking existence)
 * - PUBLIC channel where caller is not a member:
 *     * read / send / addMember -> 403 Forbidden (caller must join first)
 *     * join -> allowed (200 OK)
 */
@Service
@Transactional(readOnly = true)
public class ChannelAccessService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository channelMemberRepository;

    public ChannelAccessService(WorkspaceRepository workspaceRepository,
                                WorkspaceMemberRepository workspaceMemberRepository,
                                ChannelRepository channelRepository,
                                ChannelMemberRepository channelMemberRepository) {
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.channelRepository = channelRepository;
        this.channelMemberRepository = channelMemberRepository;
    }

    /**
     * Validates that the workspace exists and the user is a member of it.
     */
    public void validateWorkspaceMembership(Long workspaceId, Long userId) {
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
        }
        if (!workspaceMemberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this workspace");
        }
    }

    /**
     * Resolves a channel and checks if the caller has permission to read or send messages.
     *
     * Rules:
     * - Workspace missing -> 404
     * - User not in workspace -> 403
     * - Channel not found in workspace -> 404
     * - PRIVATE or DM and caller not a member -> 404 (hide existence)
     * - PUBLIC and caller not a member -> 403 (must join before reading or sending)
     */
    public Channel resolveChannelForMessage(Long callerId, Long workspaceId, Long channelId) {
        validateWorkspaceMembership(workspaceId, callerId);

        Channel channel = channelRepository.findByIdAndWorkspaceId(channelId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        boolean isMember = channelMemberRepository.existsByIdChannelIdAndIdUserId(channelId, callerId);

        if (channel.getType() == ChannelType.PRIVATE || channel.getType() == ChannelType.DM) {
            if (!isMember) {
                // Return 404 so existence is not leaked to unauthorized users
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
            }
        } else {
            if (!isMember) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You must join this channel before you can send or read messages");
            }
        }

        return channel;
    }

    /**
     * Resolves a channel for joining.
     *
     * Rules:
     * - Workspace missing -> 404
     * - User not in workspace -> 403
     * - Channel not found or DM -> 404
     * - PRIVATE and caller not a member -> 404 (cannot join private channels; hide existence)
     */
    public Channel resolveChannelForJoin(Long userId, Long workspaceId, Long channelId) {
        validateWorkspaceMembership(workspaceId, userId);

        Channel channel = channelRepository.findByIdAndWorkspaceId(channelId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        if (channel.getType() == ChannelType.DM) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }

        if (channel.getType() == ChannelType.PRIVATE) {
            boolean alreadyMember = channelMemberRepository.existsByIdChannelIdAndIdUserId(channelId, userId);
            if (!alreadyMember) {
                // Return 404 so private channel existence is not leaked to unauthorized users
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
            }
        }

        return channel;
    }

    /**
     * Resolves a channel for adding a member.
     *
     * Rules:
     * - Workspace missing -> 404
     * - Caller not in workspace -> 403
     * - Channel not found or DM -> 404
     * - PRIVATE and caller not a member -> 404 (hide existence!)
     * - PUBLIC and caller not a member -> 403 (caller must be a channel member to add others)
     */
    public Channel resolveChannelForAddMember(Long callerId, Long workspaceId, Long channelId) {
        validateWorkspaceMembership(workspaceId, callerId);

        Channel channel = channelRepository.findByIdAndWorkspaceId(channelId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        if (channel.getType() == ChannelType.DM) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }

        boolean isMember = channelMemberRepository.existsByIdChannelIdAndIdUserId(channelId, callerId);

        if (channel.getType() == ChannelType.PRIVATE) {
            if (!isMember) {
                // Return 404 so private channel existence is not leaked to unauthorized users
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
            }
        } else {
            if (!isMember) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Caller is not a member of this channel");
            }
        }

        return channel;
    }
}
