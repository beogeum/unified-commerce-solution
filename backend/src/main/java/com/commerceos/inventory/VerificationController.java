package com.commerceos.inventory;

import com.commerceos.shared.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 재고 검증센터(M3 I6). 검사 실행·재검증은 같은 결과를 내는 읽기성 작업이라 멱등키 없이 받는다(검사 자체가 멱등). */
@RestController
@RequestMapping("/api/v1/inventory/checks")
public class VerificationController {
    private final VerificationService service;

    public VerificationController(VerificationService service) {
        this.service = service;
    }

    public record Reason(String reason) {
    }

    @GetMapping("/summary")
    public ApiResponse<VerificationService.Summary> summary() {
        return ApiResponse.of(service.summary());
    }

    @GetMapping("/runs")
    public ApiResponse<List<VerificationService.RunView>> runs() {
        return ApiResponse.of(service.runs());
    }

    @PostMapping("/runs")
    public ResponseEntity<ApiResponse<VerificationService.RunView>> run() {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.runManual()));
    }

    @GetMapping("/issues")
    public ApiResponse<List<VerificationService.IssueView>> issues(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                  @RequestParam(name = "node_id", required = false) UUID node,
                                                                  @RequestParam(required = false) String status,
                                                                  @RequestParam(name = "check_code", required = false) String checkCode,
                                                                  @RequestParam(required = false) String severity) {
        return ApiResponse.of(service.issues(be, node, status, checkCode, severity));
    }

    @GetMapping("/issues/{id}")
    public ApiResponse<VerificationService.IssueView> issue(@PathVariable UUID id) {
        return ApiResponse.of(service.get(id));
    }

    @PostMapping("/issues/{id}/recheck")
    public ApiResponse<VerificationService.IssueView> recheck(@PathVariable UUID id) {
        return ApiResponse.of(service.recheck(id));
    }

    @PostMapping("/issues/{id}/acknowledge")
    public ApiResponse<VerificationService.IssueView> acknowledge(@PathVariable UUID id, @RequestBody Reason req) {
        return ApiResponse.of(service.acknowledge(id, req.reason()));
    }
}
