package com.commerceos.finance;

import com.commerceos.operations.approval.ApprovalService;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.web.ApiResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.multipart.MultipartFile;

/** 정산 원본·대사(N3), 입금 매칭(N4), 월 마감(N7). */
@RestController
@RequestMapping("/api/v1/finance")
public class SettlementController {
    private final SettlementService settlements;
    private final DepositService deposits;
    private final PeriodService periods;

    public SettlementController(SettlementService settlements, DepositService deposits, PeriodService periods) {
        this.settlements = settlements;
        this.deposits = deposits;
        this.periods = periods;
    }

    public record RunDetail(SettlementService.RunView run, List<DepositService.MatchView> matches) {
    }

    public record MatchBody(UUID runId, BigDecimal amount) {
    }

    public record CancelBody(String reason) {
    }

    // ---------------------------------------------------------------- 정산

    @GetMapping("/settlements")
    public ApiResponse<List<SettlementService.RunView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                             @RequestParam(name = "channel_account_id", required = false) UUID ca) {
        return ApiResponse.of(settlements.list(be, ca));
    }

    @GetMapping("/settlements/{id}")
    public ApiResponse<RunDetail> get(@PathVariable UUID id) {
        SettlementService.RunView run = settlements.get(id);
        return ApiResponse.of(new RunDetail(run, deposits.matchesOfRun(id)));
    }

    @PostMapping("/settlements")
    public ResponseEntity<ApiResponse<SettlementService.RunView>> create(@RequestBody SettlementService.NewSettlement body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(settlements.create(body)));
    }

    @PostMapping("/settlements/{id}/rematch")
    public ApiResponse<SettlementService.RunView> rematch(@PathVariable UUID id) {
        return ApiResponse.of(settlements.rematch(id));
    }

    @PostMapping("/settlements/imports")
    public ApiResponse<SettlementService.ImportResult> upload(@RequestParam("channel_account_id") UUID ca, @RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.of(settlements.importFile(ca, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        } catch (IOException e) {
            throw Errors.validation("file", "파일을 읽지 못했습니다.");
        }
    }

    // ---------------------------------------------------------------- 입금

    @GetMapping("/deposits")
    public ApiResponse<List<DepositService.DepositView>> deposits(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                  @RequestParam(name = "unmatched", required = false) Boolean unmatched) {
        return ApiResponse.of(deposits.list(be, unmatched));
    }

    @GetMapping("/deposits/{id}")
    public ApiResponse<DepositService.DepositView> deposit(@PathVariable UUID id) {
        return ApiResponse.of(deposits.get(id));
    }

    @PostMapping("/deposits")
    public ResponseEntity<ApiResponse<DepositService.DepositView>> createDeposit(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                                 @RequestBody DepositService.NewDeposit body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(deposits.create(body, key)));
    }

    @PostMapping("/deposits/{id}/matches")
    public ResponseEntity<ApiResponse<DepositService.MatchView>> match(@PathVariable UUID id, @RequestBody MatchBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(deposits.match(id, body == null ? null : body.runId(), body == null ? null : body.amount())));
    }

    @PostMapping("/deposits/{id}/auto-match")
    public ApiResponse<DepositService.AutoResult> autoMatch(@PathVariable UUID id) {
        return ApiResponse.of(deposits.autoMatch(id));
    }

    @PostMapping("/deposit-matches/{id}/cancel")
    public ApiResponse<DepositService.MatchView> cancel(@PathVariable UUID id, @RequestBody CancelBody body) {
        return ApiResponse.of(deposits.cancel(id, body == null ? null : body.reason()));
    }

    // ---------------------------------------------------------------- 월 마감

    @GetMapping("/periods")
    public ApiResponse<List<PeriodService.PeriodView>> periods(@RequestParam(name = "business_entity_id") UUID be,
                                                               @RequestParam(required = false, defaultValue = "6") int months) {
        return ApiResponse.of(periods.list(be, months));
    }

    @GetMapping("/periods/preview")
    public ApiResponse<PeriodService.PeriodView> preview(@RequestParam(name = "business_entity_id") UUID be,
                                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {
        return ApiResponse.of(periods.preview(be, month));
    }

    @PostMapping("/periods/close")
    public ApiResponse<PeriodService.PeriodView> close(@RequestBody PeriodService.CloseRequest body) {
        return ApiResponse.of(periods.close(body));
    }

    @PostMapping("/periods/reopen-requests")
    public ResponseEntity<ApiResponse<ApprovalService.Request>> reopen(@RequestBody PeriodService.ReopenRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(periods.requestReopen(body)));
    }
}
