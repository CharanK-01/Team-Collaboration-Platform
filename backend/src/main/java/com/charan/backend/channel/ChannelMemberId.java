package com.charan.backend.channel;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class ChannelMemberId implements Serializable {

    @Column(name = "channel_id")
    private Long channelId;

    @Column(name = "user_id")
    private Long userId;

    public ChannelMemberId(Long channelId, Long userId) {
        this.channelId = channelId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChannelMemberId that = (ChannelMemberId) o;
        return Objects.equals(channelId, that.channelId) &&
               Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId, userId);
    }
}
