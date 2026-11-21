package com.commerceos.identity.business;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import com.commerceos.shared.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/business-entities")
public class BusinessEntityController {
    private final BusinessEntityService service;
    private final BankAccountService bankAccounts;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public BusinessEntityController(BusinessEntityService service, BankAccountService bankAccounts, IdempotencyService idempotency,
                                    AccessGuard guard) {
        this.service = service;
        this.bankAccounts = bankAccounts;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String displayName,
                                @NotBlank @Size(max = 200) String legalName,
                                @NotBlank String legalType,
                                @NotBlank String businessRegistrationNo,
                                @NotBlank String taxType) {
    }

    public record CreateBankAccountRequest(@NotBlank String bankCode, @NotBlank @Size(max = 100) String accountHolder,
                                           @NotBlank String accountNumber, Boolean isDefault) {
    }

    @GetMapping
    public ApiResponse<List<BusinessEntityService.BusinessEntityView>> list(
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
            @RequestParam(name = "business_entity_id", required = false) List<String> businessEntityIds,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(service.list(page, businessEntityIds, status, q), BusinessEntityService.BusinessEntityView::id);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BusinessEntityService.BusinessEntityView>> get(@PathVariable UUID id) {
        var v = service.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(new BusinessEntityService.Create(
                        req.displayName(), req.legalName(), req.legalType(), req.businessRegistrationNo(), req.taxType())))));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<BusinessEntityService.BusinessEntityView>> patch(
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody BusinessEntityService.Patch req) {
        var v = service.patch(id, IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<BusinessEntityService.HistoryItem>> history(@PathVariable UUID id) {
        return ApiResponse.of(service.history(id));
    }

    @GetMapping("/{id}/bank-accounts")
    public ApiResponse<List<BankAccountService.BankAccountView>> bankAccounts(@PathVariable UUID id) {
        return ApiResponse.of(bankAccounts.list(id));
    }

    @PostMapping("/{id}/bank-accounts")
    public ResponseEntity<?> createBankAccount(@PathVariable UUID id,
                                               @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody CreateBankAccountRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(bankAccounts.create(id, new BankAccountService.Create(
                        req.bankCode(), req.accountHolder(), req.accountNumber(), Boolean.TRUE.equals(req.isDefault()))))));
    }

    @PatchMapping("/{id}/bank-accounts/{accountId}")
    public ApiResponse<BankAccountService.BankAccountView> patchBankAccount(
            @PathVariable UUID id, @PathVariable UUID accountId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestBody BankAccountService.Patch req) {
        return ApiResponse.of(bankAccounts.patch(id, accountId, IfMatch.parse(ifMatch), req));
    }
}
