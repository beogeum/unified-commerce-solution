package com.commerceos.identity.member;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import com.commerceos.shared.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class MemberController {
    private final MemberService members;
    private final AccessPolicyService policies;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public MemberController(MemberService members, AccessPolicyService policies, IdempotencyService idempotency, AccessGuard guard) {
        this.members = members;
        this.policies = policies;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record CreateRequest(@NotBlank @Email @Size(max = 254) String email,
                                @NotBlank @Size(max = 100) String displayName,
                                @Size(max = 200) String initialPassword,
                                @NotEmpty List<@NotBlank String> roleCodes) {
    }

    public record UpdateRequest(String status, List<String> roleCodes) {
    }

    @GetMapping
    public ApiResponse<List<MemberService.MemberView>> list(@RequestParam(required = false) Integer limit,
                                                            @RequestParam(required = false) String cursor,
                                                            @RequestParam(required = false) String status) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(members.list(page, status), MemberService.MemberView::memberId);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<MemberService.MemberView>> get(@PathVariable UUID userId) {
        MemberService.MemberView v = members.get(userId);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(members.create(
                        new MemberService.CreateMember(req.email(), req.displayName(), req.initialPassword(), req.roleCodes())))));
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<ApiResponse<MemberService.MemberView>> update(@PathVariable UUID userId,
                                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                        @RequestBody UpdateRequest req) {
        MemberService.MemberView v = members.update(userId, IfMatch.parse(ifMatch), new MemberService.UpdateMember(req.status(), req.roleCodes()));
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @GetMapping("/{userId}/access-policy")
    public ResponseEntity<ApiResponse<AccessPolicyService.PolicyView>> getPolicy(@PathVariable UUID userId) {
        AccessPolicyService.PolicyView v = policies.get(userId);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.accessPolicyVersion())).body(ApiResponse.of(v));
    }

    @PutMapping("/{userId}/access-policy")
    public ResponseEntity<ApiResponse<AccessPolicyService.PolicyView>> putPolicy(@PathVariable UUID userId,
                                                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                                @RequestBody AccessPolicyService.PolicyInput body) {
        AccessPolicyService.PolicyView v = policies.replace(userId, IfMatch.parse(ifMatch), body);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.accessPolicyVersion())).body(ApiResponse.of(v));
    }
}
