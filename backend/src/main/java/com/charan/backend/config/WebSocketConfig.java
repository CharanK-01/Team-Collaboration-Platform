package com.charan.backend.config;

import com.charan.backend.websocket.ChannelSecurityInterceptor;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over WebSocket configuration.
 *
 * Browsers cannot set an Authorization header on the WebSocket handshake, so
 * the
 * handshake (/ws) is open and the JWT is checked on the STOMP CONNECT frame
 * instead,
 * using the same JwtDecoder as the REST API. A second interceptor then
 * authorizes
 * every SUBSCRIBE and SEND frame.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtDecoder jwtDecoder;
    private final ChannelSecurityInterceptor channelSecurityInterceptor;

    public WebSocketConfig(JwtDecoder jwtDecoder,
            ChannelSecurityInterceptor channelSecurityInterceptor) {
        this.jwtDecoder = jwtDecoder;
        this.channelSecurityInterceptor = channelSecurityInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*"); // TODO: restrict origins in production.
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        ChannelInterceptor jwtInterceptor = new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                // Only authenticate the CONNECT frame; the channel interceptor handles the
                // rest.
                if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
                    return message;
                }
                String header = accessor.getFirstNativeHeader("Authorization");
                if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
                    throw new MessagingException("Missing or malformed Authorization header");
                }
                String token = header.substring(7).trim();
                try {
                    Jwt jwt = jwtDecoder.decode(token);
                    UsernamePasswordAuthenticationToken user = new UsernamePasswordAuthenticationToken(jwt.getSubject(),
                            null, List.of());
                    accessor.setUser(user);
                } catch (JwtException e) {
                    // Do not log the token itself.
                    throw new MessagingException("Invalid or expired token");
                }
                return message;
            }
        };
        // Order matters: authenticate first, then authorize.
        registration.interceptors(jwtInterceptor, channelSecurityInterceptor);
    }
}