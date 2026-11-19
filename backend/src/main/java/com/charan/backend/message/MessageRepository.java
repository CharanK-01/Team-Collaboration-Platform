package com.charan.backend.message;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Finds the maximum sequence number assigned in a channel.
     * Returns 0 if no messages exist yet in the channel.
     */
    @Query("SELECT COALESCE(MAX(m.seq), 0) FROM Message m WHERE m.channelId = :channelId")
    Long findMaxSeqByChannelId(@Param("channelId") Long channelId);

    /**
     * Fetch newest messages in a channel (no cursor).
     * Returns up to {@code size} rows, newest first, excluding soft-deleted rows.
     * The service passes limit + 1 as size to detect whether a next page exists.
     */
    @Query("""
        SELECT m FROM Message m
        WHERE m.channelId = :channelId
          AND m.deletedAt IS NULL
        ORDER BY m.id DESC
        LIMIT :size
    """)
    List<Message> findNewest(
            @Param("channelId") Long channelId,
            @Param("size") int size
    );

    /**
     * Cursor-based fetch: return messages older than {@code beforeId}.
     * "Older" means id < beforeId.
     */
    @Query("""
        SELECT m FROM Message m
        WHERE m.channelId = :channelId
          AND m.id < :beforeId
          AND m.deletedAt IS NULL
        ORDER BY m.id DESC
        LIMIT :size
    """)
    List<Message> findBefore(
            @Param("channelId") Long channelId,
            @Param("beforeId") Long beforeId,
            @Param("size") int size
    );
}
