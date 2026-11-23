package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 재고 폐기 요청(M4 O6). 항상 승인을 거친다. Idempotency-Key 필수. */
@RestController
@RequestMapping("/api/v1/inventory")
public class DisposalController {
    private final DisposalService disposals;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public DisposalController(DisposalService disposals, IdempotencyService idempotency, AccessGuard guard) {
        this.disposals = disposals;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    @PostMapping("/disposals")
    public ResponseEntity<?> request(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody DisposalService.Request body,
                                     HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), body, () ->
                ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(disposals.request(body))));
    }
}
