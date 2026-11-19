package com.charan.backend.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

    boolean existsByWorkspaceIdAndNameIgnoreCase(Long workspaceId, String name);

    Optional<Channel> findByIdAndWorkspaceId(Long id, Long workspaceId);

    Optional<Channel> findByWorkspaceIdAndDmKey(Long workspaceId, String dmKey);

    @Query("""
        SELECT c FROM Channel c
        WHERE c.workspaceId = :workspaceId
          AND c.type != com.charan.backend.channel.ChannelType.DM
          AND (c.type = com.charan.backend.channel.ChannelType.PUBLIC OR EXISTS (
              SELECT 1 FROM ChannelMember cm
              WHERE cm.id.channelId = c.id AND cm.id.userId = :userId
          ))
        ORDER BY c.name ASC
    """)
    List<Channel> findVisibleChannelsForUser(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    @Query("""
        SELECT c FROM Channel c
        JOIN ChannelMember cm ON cm.id.channelId = c.id
        WHERE c.workspaceId = :workspaceId
          AND c.type = com.charan.backend.channel.ChannelType.DM
          AND cm.id.userId = :userId
        ORDER BY c.createdAt DESC
    """)
    List<Channel> findDmsForUser(@Param("workspaceId") Long workspaceId, @Param("userId") Long userId);

    @Modifying
    @Query(value = """
        INSERT INTO channels (workspace_id, type, dm_key, created_at)
        VALUES (:workspaceId, 'DM', :dmKey, now())
        ON CONFLICT (workspace_id, dm_key) DO NOTHING
    """, nativeQuery = true)
    int insertDmIfNotExists(@Param("workspaceId") Long workspaceId, @Param("dmKey") String dmKey);
}
