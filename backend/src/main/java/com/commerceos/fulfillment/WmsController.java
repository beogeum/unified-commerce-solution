package com.commerceos.fulfillment;

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

/** 자체 WMS 작업(M4 O7): 웨이브·스캔 피킹·결품·검수 포장. */
@RestController
@RequestMapping("/api/v1/wms")
public class WmsController {
    private final WmsService wms;

    public WmsController(WmsService wms) {
        this.wms = wms;
    }

    @GetMapping("/candidates")
    public ApiResponse<WmsService.Candidates> candidates(@RequestParam("node_id") UUID nodeId) {
        return ApiResponse.of(wms.candidates(nodeId));
    }

    @GetMapping("/waves")
    public ApiResponse<List<WmsService.WaveView>> waves(@RequestParam(name = "node_id", required = false) UUID nodeId, @RequestParam(required = false) String status) {
        return ApiResponse.of(wms.list(nodeId, status));
    }

    @PostMapping("/waves")
    public ResponseEntity<ApiResponse<WmsService.WaveView>> create(@RequestBody WmsService.NewWave body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(wms.createWave(body)));
    }

    @GetMapping("/waves/{id}")
    public ApiResponse<WmsService.WaveView> wave(@PathVariable UUID id) {
        return ApiResponse.of(wms.get(id));
    }

    @PostMapping("/waves/{id}/picks")
    public ApiResponse<WmsService.WaveView> pick(@PathVariable UUID id, @RequestBody WmsService.Scan body) {
        return ApiResponse.of(wms.pick(id, body));
    }

    @PostMapping("/pick-tasks/{id}/short")
    public ApiResponse<WmsService.WaveView> shortPick(@PathVariable UUID id, @RequestBody WmsService.Short body) {
        return ApiResponse.of(wms.shortPick(id, body));
    }

    @GetMapping("/fulfillment-orders/{id}/packing")
    public ApiResponse<WmsService.PackingView> packing(@PathVariable UUID id) {
        return ApiResponse.of(wms.packing(id));
    }

    @PostMapping("/fulfillment-orders/{id}/pack")
    public ApiResponse<WmsService.PackingView> pack(@PathVariable UUID id, @RequestBody WmsService.Pack body) {
        return ApiResponse.of(wms.pack(id, body));
    }
}
