package com.charan.backend.message;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "channel_id", nullable = false)
    private Long channelId;

    @Column(name = "seq", nullable = false)
    private Long seq;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "client_message_id", nullable = false, columnDefinition = "uuid")
    private UUID clientMessageId;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public Message(Long channelId, Long seq, Long userId, UUID clientMessageId, String body) {
        this.channelId = channelId;
        this.seq = seq;
        this.userId = userId;
        this.clientMessageId = clientMessageId;
        this.body = body;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
