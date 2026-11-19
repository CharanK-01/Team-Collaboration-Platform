package com.charan.backend.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface ChannelMemberRepository extends JpaRepository<ChannelMember, ChannelMemberId> {

    boolean existsByIdChannelIdAndIdUserId(Long channelId, Long userId);

    List<ChannelMember> findByIdChannelId(Long channelId);

    @Query("SELECT cm.id.channelId FROM ChannelMember cm WHERE cm.id.userId = :userId")
    Set<Long> findJoinedChannelIdsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query(value = """
        INSERT INTO channel_members (channel_id, user_id, joined_at)
        VALUES (:channelId, :userId, now())
        ON CONFLICT (channel_id, user_id) DO NOTHING
    """, nativeQuery = true)
    int insertMemberIfNotExists(@Param("channelId") Long channelId, @Param("userId") Long userId);
}
