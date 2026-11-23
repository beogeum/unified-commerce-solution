package com.commerceos.finance;

import com.commerceos.shared.web.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리 손익·비용 원장·예상 비용 규칙·재고 평가(M5). */
@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
    private final FinanceService finance;
    private final CostRuleService rules;

    public FinanceController(FinanceService finance, CostRuleService rules) {
        this.finance = finance;
        this.rules = rules;
    }

    @GetMapping("/profit")
    public ApiResponse<FinanceService.Profit> profit(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                     @RequestParam(name = "channel_account_id", required = false) UUID ca,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(name = "group_by", required = false) String groupBy) {
        return ApiResponse.of(finance.profit(be, ca, from, to, groupBy));
    }

    @GetMapping("/entries")
    public ApiResponse<List<FinanceService.EntryView>> entries(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                               @RequestParam(name = "order_id", required = false) UUID orderId,
                                                               @RequestParam(name = "claim_id", required = false) UUID claimId,
                                                               @RequestParam(required = false) String category,
                                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                               @RequestParam(required = false) Integer limit) {
        return ApiResponse.of(finance.entries(be, orderId, claimId, category, from, to, limit));
    }

    @PostMapping("/cost-entries")
    public ResponseEntity<ApiResponse<FinanceService.CostResult>> cost(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                       @RequestBody FinanceService.NewCost body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(finance.recordCost(body, key)));
    }

    @PostMapping("/impairments")
    public ResponseEntity<ApiResponse<FinanceService.ImpairResult>> impair(@RequestBody FinanceService.Impair body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(finance.impair(body)));
    }

    @GetMapping("/cost-rules")
    public ApiResponse<List<CostRuleService.RuleView>> rules(@RequestParam(name = "business_entity_id", required = false) UUID be) {
        return ApiResponse.of(rules.list(be));
    }

    @PostMapping("/cost-rules")
    public ResponseEntity<ApiResponse<CostRuleService.RuleView>> createRule(@RequestBody CostRuleService.NewRule body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(rules.create(body)));
    }
}
