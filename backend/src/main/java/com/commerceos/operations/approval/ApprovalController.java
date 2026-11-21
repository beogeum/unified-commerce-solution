package com.commerceos.operations.approval;

import com.commerceos.shared.web.ApiResponse;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
public class ApprovalController {
    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    public record Decision(String decision, @Size(max = 500) String note) {
    }

    @GetMapping
    public ApiResponse<List<ApprovalService.Request>> list(@RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "false") boolean mine) {
        return ApiResponse.of(approvals.list(status, mine));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApprovalService.Request> get(@PathVariable UUID id) {
        return ApiResponse.of(approvals.get(id));
    }

    @PostMapping("/{id}/decision")
    public ApiResponse<ApprovalService.Request> decide(@PathVariable UUID id, @RequestBody Decision d) {
        if (!"APPROVE".equals(d.decision()) && !"REJECT".equals(d.decision())) {
            throw com.commerceos.shared.error.Errors.validation("decision", "decision 은 APPROVE 또는 REJECT 여야 합니다.");
        }
        return ApiResponse.of(approvals.decide(id, "APPROVE".equals(d.decision()), d.note()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ApprovalService.Request> cancel(@PathVariable UUID id) {
        return ApiResponse.of(approvals.cancel(id));
    }
}
