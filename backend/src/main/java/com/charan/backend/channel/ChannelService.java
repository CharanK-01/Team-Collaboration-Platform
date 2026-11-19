package com.charan.backend.channel;

import com.charan.backend.auth.UserResponse;
import com.charan.backend.user.User;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Service
public class ChannelService {

    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository channelMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    public ChannelService(ChannelRepository channelRepository,
                          ChannelMemberRepository channelMemberRepository,
                          WorkspaceRepository workspaceRepository,
                          WorkspaceMemberRepository workspaceMemberRepository,
                          UserRepository userRepository) {
        this.channelRepository = channelRepository;
        this.channelMemberRepository = channelMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a public or private channel in the given workspace.
     * The creator is automatically joined as a channel member.
     */
    @Transactional
    public ChannelResponse createChannel(Long userId, Long workspaceId, CreateChannelRequest request) {
        validateWorkspaceMembership(workspaceId, userId);

        String name = request.name().trim().toLowerCase();
        if (channelRepository.existsByWorkspaceIdAndNameIgnoreCase(workspaceId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Channel name already exists in this workspace");
        }

        ChannelType type = Boolean.TRUE.equals(request.isPrivate()) ? ChannelType.PRIVATE : ChannelType.PUBLIC;
        Channel channel = new Channel(workspaceId, name, type);
        channel = channelRepository.save(channel);

        channelMemberRepository.insertMemberIfNotExists(channel.getId(), userId);

        return toChannelResponse(channel, true);
    }

    /**
     * Lists visible channels in the workspace for the caller:
     * all public channels plus any private channels the caller has joined.
     * DMs are excluded.
     */
    @Transactional(readOnly = true)
    public List<ChannelResponse> listChannels(Long userId, Long workspaceId) {
        validateWorkspaceMembership(workspaceId, userId);

        List<Channel> channels = channelRepository.findVisibleChannelsForUser(workspaceId, userId);
        Set<Long> joinedChannelIds = channelMemberRepository.findJoinedChannelIdsByUserId(userId);

        return channels.stream()
                .map(c -> toChannelResponse(c, joinedChannelIds.contains(c.getId())))
                .toList();
    }

    /**
     * Joins a public channel.
     * Idempotent: repeated joins return 200 OK without errors.
     * For a private channel the caller isn't in, returns 404 to avoid leaking existence.
     */
    @Transactional
    public ChannelResponse joinChannel(Long userId, Long workspaceId, Long channelId) {
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
            return toChannelResponse(channel, true);
        }

        // Public channel: join if not already a member
        channelMemberRepository.insertMemberIfNotExists(channelId, userId);
        return toChannelResponse(channel, true);
    }

    /**
     * Adds a workspace member to a private channel.
     * Caller must already be a member of the channel.
     */
    @Transactional
    public ChannelResponse addMemberToChannel(Long callerId, Long workspaceId, Long channelId, AddChannelMemberRequest request) {
        validateWorkspaceMembership(workspaceId, callerId);

        Channel channel = channelRepository.findByIdAndWorkspaceId(channelId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        if (channel.getType() == ChannelType.DM) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found");
        }

        if (!channelMemberRepository.existsByIdChannelIdAndIdUserId(channelId, callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Caller is not a member of this channel");
        }

        if (!workspaceMemberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, request.userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target user is not a member of this workspace");
        }

        channelMemberRepository.insertMemberIfNotExists(channelId, request.userId());
        return toChannelResponse(channel, true);
    }

    /**
     * Finds or creates a two-person DM channel between the caller and target user.
     * Concurrency-safe: utilizes a database-level canonical pair unique constraint.
     */
    @Transactional
    public DmChannelResponse getOrCreateDm(Long callerId, Long workspaceId, CreateDmRequest request) {
        validateWorkspaceMembership(workspaceId, callerId);

        if (callerId.equals(request.userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot create a direct message channel with yourself");
        }

        if (!workspaceMemberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, request.userId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target user is not a member of this workspace");
        }

        User targetUser = userRepository.findById(request.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target user not found"));

        long minId = Math.min(callerId, request.userId());
        long maxId = Math.max(callerId, request.userId());
        String dmKey = minId + ":" + maxId;

        // Atomic insert if not present (DB unique constraint handles race conditions)
        channelRepository.insertDmIfNotExists(workspaceId, dmKey);

        Channel dmChannel = channelRepository.findByWorkspaceIdAndDmKey(workspaceId, dmKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to resolve DM channel"));

        channelMemberRepository.insertMemberIfNotExists(dmChannel.getId(), callerId);
        channelMemberRepository.insertMemberIfNotExists(dmChannel.getId(), request.userId());

        UserResponse otherUserResponse = new UserResponse(targetUser.getId(), targetUser.getEmail(), targetUser.getDisplayName());
        return new DmChannelResponse(dmChannel.getId(), workspaceId, ChannelType.DM, otherUserResponse, dmChannel.getCreatedAt());
    }

    /**
     * Lists all DM channels the caller participates in within the given workspace.
     */
    @Transactional(readOnly = true)
    public List<DmChannelResponse> listDms(Long callerId, Long workspaceId) {
        validateWorkspaceMembership(workspaceId, callerId);

        List<Channel> dms = channelRepository.findDmsForUser(workspaceId, callerId);

        return dms.stream().map(dm -> {
            List<ChannelMember> members = channelMemberRepository.findByIdChannelId(dm.getId());
            Long otherUserId = members.stream()
                    .map(m -> m.getId().getUserId())
                    .filter(id -> !id.equals(callerId))
                    .findFirst()
                    .orElse(callerId);

            User otherUser = userRepository.findById(otherUserId).orElse(null);
            UserResponse otherResponse = otherUser != null
                    ? new UserResponse(otherUser.getId(), otherUser.getEmail(), otherUser.getDisplayName())
                    : null;

            return new DmChannelResponse(dm.getId(), workspaceId, ChannelType.DM, otherResponse, dm.getCreatedAt());
        }).toList();
    }

    private void validateWorkspaceMembership(Long workspaceId, Long userId) {
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
        }
        if (!workspaceMemberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this workspace");
        }
    }

    private ChannelResponse toChannelResponse(Channel channel, boolean joined) {
        return new ChannelResponse(
                channel.getId(),
                channel.getWorkspaceId(),
                channel.getName(),
                channel.getType(),
                channel.getType() == ChannelType.PRIVATE,
                joined,
                channel.getCreatedAt()
        );
    }
}
