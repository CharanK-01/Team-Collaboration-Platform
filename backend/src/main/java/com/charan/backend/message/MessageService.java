package com.charan.backend.message;

import com.charan.backend.channel.Channel;
import com.charan.backend.channel.ChannelMemberRepository;
import com.charan.backend.channel.ChannelRepository;
import com.charan.backend.channel.ChannelType;
import com.charan.backend.user.User;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.workspace.WorkspaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service handling message sending and cursor-paginated history fetching.
 */
@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final ChannelMemberRepository channelMemberRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    public MessageService(MessageRepository messageRepository,
                          ChannelRepository channelRepository,
                          ChannelMemberRepository channelMemberRepository,
                          WorkspaceRepository workspaceRepository,
                          WorkspaceMemberRepository workspaceMemberRepository,
                          UserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.channelRepository = channelRepository;
        this.channelMemberRepository = channelMemberRepository;
        this.workspaceRepository = workspaceRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Sends a message to the specified channel.
     * Generates a per-channel monotonic sequence number (seq).
     */
    @Transactional
    public MessageResponse send(Long callerId, Long workspaceId, Long channelId, SendMessageRequest request) {
        resolveChannel(callerId, workspaceId, channelId);

        String body = request.content().trim();
        if (body.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }
        if (body.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must be at most 4000 characters");
        }

        // Lock the channel row with PESSIMISTIC_WRITE to serialize seq generation per channel
        channelRepository.findByIdForUpdate(channelId);

        Long nextSeq = messageRepository.findMaxSeqByChannelId(channelId) + 1;
        Message message = new Message(channelId, nextSeq, callerId, UUID.randomUUID(), body);
        message = messageRepository.save(message);

        User sender = userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Sender not found"));

        return toResponse(message, new SenderInfo(sender.getId(), sender.getDisplayName()));
    }

    /**
     * Fetches cursor-paginated message history for a channel, newest first.
     * Loads authors in a single batch query for the entire page.
     */
    @Transactional(readOnly = true)
    public MessagePageResponse fetchHistory(Long callerId, Long workspaceId, Long channelId,
                                            int limit, Long before) {
        resolveChannel(callerId, workspaceId, channelId);

        int fetchSize = limit + 1; // fetch limit + 1 to check if another page exists

        List<Message> rows = (before == null)
                ? messageRepository.findNewest(channelId, fetchSize)
                : messageRepository.findBefore(channelId, before, fetchSize);

        boolean hasMore = rows.size() == fetchSize;
        List<Message> page = hasMore ? rows.subList(0, limit) : rows;

        // Batch-load senders: 1 query for all authors on the page
        List<Long> senderIds = page.stream().map(Message::getUserId).distinct().toList();
        Map<Long, User> senderMap = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        List<MessageResponse> responses = page.stream()
                .map(m -> {
                    User u = senderMap.get(m.getUserId());
                    SenderInfo sender = u != null
                            ? new SenderInfo(u.getId(), u.getDisplayName())
                            : new SenderInfo(m.getUserId(), "Unknown");
                    return toResponse(m, sender);
                })
                .toList();

        Long nextCursor = hasMore && !page.isEmpty() ? page.get(page.size() - 1).getId() : null;

        return new MessagePageResponse(responses, nextCursor);
    }

    /**
     * Enforces workspace membership and channel access rules.
     */
    private Channel resolveChannel(Long callerId, Long workspaceId, Long channelId) {
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
        }
        if (!workspaceMemberRepository.existsByIdWorkspaceIdAndIdUserId(workspaceId, callerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this workspace");
        }
        Channel channel = channelRepository.findByIdAndWorkspaceId(channelId, workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        boolean isMember = channelMemberRepository.existsByIdChannelIdAndIdUserId(channelId, callerId);

        if (channel.getType() == ChannelType.PRIVATE || channel.getType() == ChannelType.DM) {
            if (!isMember) {
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

    private MessageResponse toResponse(Message m, SenderInfo sender) {
        return new MessageResponse(
                m.getId(),
                m.getChannelId(),
                m.getBody(),
                sender,
                m.getCreatedAt()
        );
    }
}
