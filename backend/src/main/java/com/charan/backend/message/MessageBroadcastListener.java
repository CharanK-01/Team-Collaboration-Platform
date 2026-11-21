package com.charan.backend.message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MessageBroadcastListener {

    private static final Logger log = LoggerFactory.getLogger(MessageBroadcastListener.class);

    private final SimpMessagingTemplate messagingTemplate;

    public MessageBroadcastListener(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Runs only after the message is committed, so subscribers never see an unsaved
     * message.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(MessageCreatedEvent event) {
        MessageResponse m = event.message();
        try {
            messagingTemplate.convertAndSend("/topic/channels." + m.channelId(), m);
        } catch (Exception e) {
            // The message is already saved; a broadcast failure must not fail the sender.
            // Never log the message content.
            log.warn("Broadcast failed for message {} in channel {}: {}",
                    m.id(), m.channelId(), e.getClass().getSimpleName());
        }
    }
}