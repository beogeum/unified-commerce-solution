package com.commerceos.operations;

import com.commerceos.shared.web.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 운영 현황(M7). */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {
    private final OpsStatusService status;

    public OpsController(OpsStatusService status) {
        this.status = status;
    }

    @GetMapping("/status")
    public ApiResponse<OpsStatusService.Status> status() {
        return ApiResponse.of(status.status());
    }
}
