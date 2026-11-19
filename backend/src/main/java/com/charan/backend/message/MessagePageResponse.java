package com.charan.backend.message;

import java.util.List;

/**
 * Response envelope for GET /messages.
 *
 * messages   – the page of messages, newest first.
 * nextCursor – the id of the oldest message on this page.
 *              Pass it as the "before" query parameter to fetch the next
 *              (older) page.  null means this is the last page.
 *
 * How it works:
 *   The service fetches limit + 1 rows.
 *   If it gets limit + 1 back, there are more rows; trim the extra row,
 *   set nextCursor = id of the last (oldest) item in the trimmed list.
 *   If it gets ≤ limit rows, this is the last page; nextCursor = null.
 */
public record MessagePageResponse(
        List<MessageResponse> messages,
        Long nextCursor
) {}
