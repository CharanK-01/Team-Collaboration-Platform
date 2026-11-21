package com.charan.backend.websocket;

import com.charan.backend.channel.Channel;
import com.charan.backend.channel.ChannelAccessService;
import com.charan.backend.channel.ChannelRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class ChannelSecurityInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ChannelSecurityInterceptor.class);
    private static final Pattern CHANNEL_TOPIC = Pattern.compile("^/topic/channels\\.(\\d{1,18})$");
    private static final String DENIED = "Subscription not allowed";

    private final ChannelAccessService channelAccessService;
    private final ChannelRepository channelRepository;

    public ChannelSecurityInterceptor(ChannelAccessService channelAccessService,
            ChannelRepository channelRepository) {
        this.channelAccessService = channelAccessService;
        this.channelRepository = channelRepository;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel messageChannel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message; // heartbeat frames have no command
        }
        if (StompCommand.SUBSCRIBE.equals(command)) {
            authorizeSubscribe(accessor);
        } else if (StompCommand.SEND.equals(command)) {
            authorizeSend(accessor);
        }
        return message;
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new MessagingException(DENIED);
        }
        if (destination.startsWith("/user/queue/")) {
            return; // per-user queue
        }
        Matcher m = CHANNEL_TOPIC.matcher(destination);
        if (!m.matches()) {
            log.warn("Subscription to unsupported destination {} rejected", destination);
            throw new MessagingException(DENIED);
        }
        try {
            Long channelId = Long.valueOf(m.group(1));
            Long userId = currentUserId(accessor);
            // The workspace comes from the database, never from the client.
            Channel ch = channelRepository.findById(channelId)
                    .orElseThrow(() -> new MessagingException(DENIED));
            channelAccessService.resolveChannelForMessage(userId, ch.getWorkspaceId(), channelId);
        } catch (Exception e) {
            // Same message for "no such channel" and "not a member".
            log.warn("Subscription denied for {}: {}", destination, e.getMessage());
            throw new MessagingException(DENIED);
        }
    }

    private Long currentUserId(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof Authentication auth) {
            return Long.valueOf(auth.getName());
        }
        throw new MessagingException(DENIED);
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/app/")) {
            log.warn("Send to unsupported destination {} rejected", destination);
            throw new MessagingException("Send not allowed");
        }
    }
}