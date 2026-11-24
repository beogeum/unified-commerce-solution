package com.commerceos.b2b;

import com.commerceos.operations.approval.ApprovalService;
import com.commerceos.shared.web.ApiResponse;
import java.util.List;
import java.util.UUID;
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

/** B2B 거래처·단가표·외상(매출채권). */
@RestController
@RequestMapping("/api/v1")
public class B2bController {
    private final B2bCustomerService customers;
    private final ReceivableService receivables;

    public B2bController(B2bCustomerService customers, ReceivableService receivables) {
        this.customers = customers;
        this.receivables = receivables;
    }

    private static Integer v(String ifMatch) {
        return ifMatch == null || ifMatch.isBlank() ? null : com.commerceos.shared.web.IfMatch.parse(ifMatch);
    }

    public record PricesBody(List<B2bCustomerService.PriceInput> prices) {
    }

    public record ReasonBody(String reason) {
    }

    @GetMapping("/b2b-customers")
    public ApiResponse<List<B2bCustomerService.CustomerView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                  @RequestParam(required = false) String q, @RequestParam(required = false) String status) {
        return ApiResponse.of(customers.list(be, q, status));
    }

    @GetMapping("/b2b-customers/{id}")
    public ApiResponse<B2bCustomerService.CustomerView> get(@PathVariable UUID id) {
        return ApiResponse.of(customers.get(id));
    }

    @PostMapping("/b2b-customers")
    public ResponseEntity<ApiResponse<B2bCustomerService.CustomerView>> create(@RequestBody B2bCustomerService.CustomerInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(customers.create(body)));
    }

    @PatchMapping("/b2b-customers/{id}")
    public ApiResponse<B2bCustomerService.CustomerView> update(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                              @RequestBody B2bCustomerService.CustomerInput body) {
        return ApiResponse.of(customers.update(id, v(ifMatch), body));
    }

    @PostMapping("/b2b-customers/{id}/contact/reveal")
    public ApiResponse<B2bCustomerService.Contact> reveal(@PathVariable UUID id) {
        return ApiResponse.of(customers.revealContact(id));
    }

    @GetMapping("/b2b-customers/{id}/prices")
    public ApiResponse<List<B2bCustomerService.PriceView>> prices(@PathVariable UUID id) {
        return ApiResponse.of(customers.prices(id));
    }

    @PostMapping("/b2b-customers/{id}/prices")
    public ResponseEntity<ApiResponse<List<B2bCustomerService.PriceView>>> addPrices(@PathVariable UUID id, @RequestBody PricesBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(customers.addPrices(id, body == null ? null : body.prices())));
    }

    @GetMapping("/b2b-customers/{id}/receivables")
    public ApiResponse<ReceivableService.Statement> statement(@PathVariable UUID id) {
        return ApiResponse.of(receivables.statement(id));
    }

    @PostMapping("/b2b-customers/{id}/payments")
    public ResponseEntity<ApiResponse<ReceivableService.EntryView>> pay(@PathVariable UUID id,
                                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                       @RequestBody ReceivableService.PaymentInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(receivables.recordPayment(id, key, body)));
    }

    @PostMapping("/receivable-entries/{id}/reverse")
    public ResponseEntity<ApiResponse<ReceivableService.EntryView>> reverse(@PathVariable UUID id, @RequestBody ReasonBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(receivables.reversePayment(id, body == null ? null : body.reason())));
    }

    @PostMapping("/b2b-customers/{id}/write-offs")
    public ResponseEntity<ApiResponse<ApprovalService.Request>> writeOff(@PathVariable UUID id, @RequestBody ReceivableService.WriteOffInput body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(receivables.requestWriteOff(id, body)));
    }

    @GetMapping("/receivables")
    public ApiResponse<List<ReceivableService.SummaryRow>> summary(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                  @RequestParam(name = "overdue_only", required = false) Boolean overdueOnly) {
        return ApiResponse.of(receivables.summary(be, overdueOnly));
    }
}
