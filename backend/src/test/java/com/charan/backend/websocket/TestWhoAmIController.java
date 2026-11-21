package com.charan.backend.websocket;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Test-only controller: echoes the authenticated principal's name back to the
 * caller so tests can verify the server-side identity set at CONNECT time.
 */
@Controller
public class TestWhoAmIController {

    @MessageMapping("/whoami")
    @SendToUser("/queue/whoami")
    public String whoAmI(Principal principal) {
        return principal.getName();
    }
}