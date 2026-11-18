package com.charan.backend.invite;

import com.charan.backend.user.User;
import com.charan.backend.user.UserRepository;
import com.charan.backend.workspace.WorkspaceMember;
import com.charan.backend.workspace.WorkspaceMemberRepository;
import com.charan.backend.workspace.WorkspaceRole;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InviteService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WorkspaceInviteRepository workspaceInviteRepository;
    private final WorkspaceMemberRepository workspaceMemberRepository;
    private final UserRepository userRepository;

    public InviteService(WorkspaceInviteRepository workspaceInviteRepository,
                         WorkspaceMemberRepository workspaceMemberRepository,
                         UserRepository userRepository) {
        this.workspaceInviteRepository = workspaceInviteRepository;
        this.workspaceMemberRepository = workspaceMemberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Issues an invite to a workspace.
     * Permission rules:
     * - Only members can invite; non-members get 404 (prevents ID probing).
     * - Nobody can invite as OWNER.
     * - MEMBER cannot invite anyone (403).
     * - ADMIN can only invite MEMBER (403 if inviting ADMIN).
     * - OWNER can invite ADMIN or MEMBER.
     * - Rejects (409) if user is already a member or has a pending invite.
     */
    @Transactional
    public InviteResponse createInvite(Long callerId, Long workspaceId, CreateInviteRequest request) {
        WorkspaceMember callerMember = workspaceMemberRepository
                .findByIdWorkspaceIdAndIdUserId(workspaceId, callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found"));

        if (request.role() == WorkspaceRole.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot invite someone with OWNER role");
        }

        if (callerMember.getRole() == WorkspaceRole.MEMBER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Members do not have permission to send invites");
        }

        if (callerMember.getRole() == WorkspaceRole.ADMIN && request.role() != WorkspaceRole.MEMBER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins can only invite members");
        }

        String targetEmail = request.email().trim().toLowerCase();

        if (workspaceMemberRepository.isEmailMemberOfWorkspace(workspaceId, targetEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member of this workspace");
        }

        if (workspaceInviteRepository.hasPendingInvite(workspaceId, targetEmail, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A pending invite already exists for this email");
        }

        // Generate 32 bytes of cryptographically secure random bytes, Base64 URL-safe
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        WorkspaceInvite invite = new WorkspaceInvite(
                workspaceId,
                targetEmail,
                request.role(),
                token,
                callerId,
                expiresAt
        );
        invite = workspaceInviteRepository.save(invite);

        // NOTE: We do not send real emails yet. The invite token is returned in the API
        // response for verification and direct client consumption.
        return new InviteResponse(
                invite.getId(),
                invite.getWorkspaceId(),
                invite.getEmail(),
                invite.getRole(),
                invite.getToken(),
                invite.getExpiresAt(),
                invite.getCreatedAt()
        );
    }

    /**
     * Accepts an invite using its secure token.
     * In one atomic transaction:
     * - Validates token existence, expiration, and prior acceptance.
     * - Validates that the logged-in user's email matches the invite recipient (403 if not).
     * - Inserts membership row with the invited role and marks acceptedAt.
     */
    @Transactional
    public AcceptInviteResponse acceptInvite(Long callerId, String token) {
        User user = userRepository.findById(callerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        WorkspaceInvite invite = workspaceInviteRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invite not found"));

        if (invite.getAcceptedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite has already been accepted");
        }

        if (invite.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invite has expired");
        }

        if (!user.getEmail().equalsIgnoreCase(invite.getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invite was issued for a different email address");
        }

        if (workspaceMemberRepository.existsByIdWorkspaceIdAndIdUserId(invite.getWorkspaceId(), user.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member of this workspace");
        }

        WorkspaceMember newMember = new WorkspaceMember(invite.getWorkspaceId(), user.getId(), invite.getRole());
        workspaceMemberRepository.save(newMember);

        invite.setAcceptedAt(Instant.now());
        workspaceInviteRepository.save(invite);

        return new AcceptInviteResponse(
                invite.getWorkspaceId(),
                invite.getRole(),
                "Invite accepted successfully"
        );
    }
}
