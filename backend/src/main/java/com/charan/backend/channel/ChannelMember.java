package com.charan.backend.channel;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "channel_members")
@Getter
@Setter
@NoArgsConstructor
public class ChannelMember {

    @EmbeddedId
    private ChannelMemberId id;

    @Column(name = "last_read_message_id")
    private Long lastReadMessageId;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) {
            joinedAt = Instant.now();
        }
    }

    public ChannelMember(Long channelId, Long userId) {
        this.id = new ChannelMemberId(channelId, userId);
    }
}
