package com.charan.backend.message;

import com.charan.backend.channel.ChannelAccessService;
import com.charan.backend.channel.ChannelRepository;
import com.charan.backend.user.User;
import com.charan.backend.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service handling message sending and cursor-paginated history fetching.
 * Delegates channel access checks to ChannelAccessService.
 */
@Service
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final ChannelAccessService channelAccessService;
    private final UserRepository userRepository;

    private final ApplicationEventPublisher eventPublisher;

    public MessageService(MessageRepository messageRepository,
                          ChannelRepository channelRepository,
                          ChannelAccessService channelAccessService,
                          UserRepository userRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.messageRepository = messageRepository;
        this.channelRepository = channelRepository;
        this.channelAccessService = channelAccessService;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }
        this.messageRepository = messageRepository;
        this.channelRepository = channelRepository;
        this.channelAccessService = channelAccessService;
        this.userRepository = userRepository;
    }

    /**
     * Sends a message to the specified channel.
     * Generates a per-channel monotonic sequence number (seq).
     */
    @Transactional
    public MessageResponse send(Long callerId, Long workspaceId, Long channelId, SendMessageRequest request) {
        channelAccessService.resolveChannelForMessage(callerId, workspaceId, channelId);

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

        MessageResponse response = toResponse(message, new SenderInfo(sender.getId(), sender.getDisplayName()));
        // Publish the event so the AFTER_COMMIT listener can broadcast
        eventPublisher.publishEvent(new MessageCreatedEvent(response));
        return response;
    }

    /**
     * Fetches cursor-paginated message history for a channel, newest first.
     * Loads authors in a single batch query for the entire page.
     */
    @Transactional(readOnly = true)
    public MessagePageResponse fetchHistory(Long callerId, Long workspaceId, Long channelId,
                                            int limit, Long before) {
        channelAccessService.resolveChannelForMessage(callerId, workspaceId, channelId);

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
