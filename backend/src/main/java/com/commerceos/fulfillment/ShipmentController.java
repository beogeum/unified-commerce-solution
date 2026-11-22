package com.commerceos.fulfillment;

import com.commerceos.shared.error.Errors;
import com.commerceos.shared.web.ApiResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 출고 확정·송장·배송 추적·판매채널 발송처리(M4 O5). */
@RestController
@RequestMapping("/api/v1")
public class ShipmentController {
    private final ShipmentService service;
    private final ShipmentImportService imports;

    public ShipmentController(ShipmentService service, ShipmentImportService imports) {
        this.service = service;
        this.imports = imports;
    }

    @GetMapping("/carriers")
    public ApiResponse<List<ShipmentService.Carrier>> carriers() {
        return ApiResponse.of(service.carriers());
    }

    @PostMapping("/fulfillment-orders/{id}/ship")
    public ApiResponse<FulfillmentService.FoView> ship(@PathVariable UUID id, @RequestBody ShipmentService.Ship body) {
        return ApiResponse.of(service.ship(id, body));
    }

    @GetMapping("/shipments")
    public ApiResponse<List<ShipmentService.ShipmentView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                @RequestParam(required = false) String status,
                                                                @RequestParam(name = "channel_dispatch_status", required = false) String channelDispatchStatus,
                                                                @RequestParam(name = "order_id", required = false) UUID orderId,
                                                                @RequestParam(name = "fulfillment_order_id", required = false) UUID foId,
                                                                @RequestParam(required = false) String q) {
        return ApiResponse.of(service.list(be, status, channelDispatchStatus, orderId, foId, q));
    }

    @GetMapping("/shipments/{id}")
    public ApiResponse<ShipmentService.ShipmentView> get(@PathVariable UUID id) {
        return ApiResponse.of(service.get(id));
    }

    @PostMapping("/shipments/{id}/tracking-events")
    public ApiResponse<ShipmentService.TrackingResult> tracking(@PathVariable UUID id, @RequestBody ShipmentService.TrackingInput body) {
        return ApiResponse.of(service.addTracking(id, body));
    }

    @PostMapping("/shipments/{id}/channel-dispatch/retry")
    public ApiResponse<ShipmentService.ShipmentView> retry(@PathVariable UUID id) {
        return ApiResponse.of(service.retryChannelDispatch(id));
    }

    @PostMapping("/shipments/{id}/channel-dispatch/manual-done")
    public ApiResponse<ShipmentService.ShipmentView> manualDone(@PathVariable UUID id, @RequestBody(required = false) ShipmentService.ManualDone body) {
        return ApiResponse.of(service.manualChannelDone(id, body));
    }

    @PostMapping("/shipments/imports")
    public ApiResponse<ShipmentImportService.ImportResult> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.of(imports.upload(file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        } catch (IOException e) {
            throw Errors.validation("file", "파일을 읽지 못했습니다.");
        }
    }
}
