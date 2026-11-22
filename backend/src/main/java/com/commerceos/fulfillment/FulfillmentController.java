package com.commerceos.fulfillment;

import com.commerceos.shared.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 출고 배정·출고지시(M4 O4, docs/07 `/fulfillment-orders`). */
@RestController
@RequestMapping("/api/v1")
public class FulfillmentController {
    private final FulfillmentService service;

    public FulfillmentController(FulfillmentService service) {
        this.service = service;
    }

    @PostMapping("/orders/{id}/allocate")
    public ApiResponse<FulfillmentService.AllocationResult> allocate(@PathVariable UUID id) {
        return ApiResponse.of(service.allocate(id));
    }

    @GetMapping("/fulfillment-orders")
    public ApiResponse<List<FulfillmentService.FoView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                            @RequestParam(required = false) String status, @RequestParam(name = "node_id", required = false) UUID node,
                                                            @RequestParam(name = "order_id", required = false) UUID order) {
        return ApiResponse.of(service.list(be, status, node, order));
    }

    @GetMapping("/fulfillment-orders/{id}")
    public ApiResponse<FulfillmentService.FoView> get(@PathVariable UUID id) {
        return ApiResponse.of(service.get(id));
    }

    @PostMapping("/fulfillment-orders/{id}/dispatch")
    public ApiResponse<FulfillmentService.FoView> dispatch(@PathVariable UUID id) {
        return ApiResponse.of(service.requestDispatch(id));
    }

    @PostMapping("/fulfillment-orders/{id}/cancel")
    public ApiResponse<FulfillmentService.FoView> cancel(@PathVariable UUID id, @RequestBody FulfillmentService.Cancel req) {
        return ApiResponse.of(service.cancel(id, req));
    }

    @PostMapping("/fulfillment-orders/{id}/manual-accept")
    public ApiResponse<FulfillmentService.FoView> manualAccept(@PathVariable UUID id, @RequestBody FulfillmentService.ManualAccept req) {
        return ApiResponse.of(service.manualAccept(id, req));
    }
}
