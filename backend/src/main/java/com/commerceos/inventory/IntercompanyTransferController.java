package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사업자 간 재고 소유권 이전(T35). 요청은 승인 대기(202), 승인은 공통 승인함. */
@RestController
@RequestMapping("/api/v1/inventory")
public class IntercompanyTransferController {
    private final IntercompanyTransferService transfers;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public IntercompanyTransferController(IntercompanyTransferService transfers, IdempotencyService idempotency, AccessGuard guard) {
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    @GetMapping("/ownership-transfers")
    public ApiResponse<List<IntercompanyTransferService.TransferView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be) {
        return ApiResponse.of(transfers.list(be));
    }

    @GetMapping("/ownership-transfers/{id}")
    public ApiResponse<IntercompanyTransferService.TransferView> get(@PathVariable UUID id) {
        return ApiResponse.of(transfers.get(id));
    }

    @PostMapping("/ownership-transfers")
    public ResponseEntity<?> request(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody IntercompanyTransferService.Request body,
                                     HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), body, () ->
                ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(transfers.request(body))));
    }
}
