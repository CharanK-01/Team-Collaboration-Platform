package com.charan.backend.websocket;

import com.charan.backend.message.MessageService;
import com.charan.backend.message.SendMessageRequest;
import java.security.Principal;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class ChatMessageController {

    private final MessageService messageService;

    public ChatMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    // Same MessageService.send as REST: membership check, validation, persist, and
    // then the
    // AFTER_COMMIT listener broadcasts. Nothing is broadcast if this throws.
    @MessageMapping("/workspaces/{workspaceId}/channels/{channelId}/messages")
    public void send(@DestinationVariable Long workspaceId,
            @DestinationVariable Long channelId,
            @Payload SendMessageRequest request,
            Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        messageService.send(userId, workspaceId, channelId, request);
    }

    public record ErrorReply(int status, String message) {
    }

    // Errors go only to the sender, on /user/queue/errors.
    @MessageExceptionHandler
    @SendToUser("/queue/errors")
    public ErrorReply handleError(Exception e) {
        if (e instanceof ResponseStatusException rse) {
            return new ErrorReply(rse.getStatusCode().value(), rse.getReason());
        }
        return new ErrorReply(500, "Message could not be sent");
    }
}