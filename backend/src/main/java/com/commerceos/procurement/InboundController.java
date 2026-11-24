package com.commerceos.procurement;

import com.commerceos.shared.web.ApiResponse;
import java.util.List;
import java.util.UUID;
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

/** 단계별 입고(입고예정 → 도착 → 검수 → 적치). 상태 변경은 If-Match(선택)로 낙관적 잠금. */
@RestController
@RequestMapping("/api/v1")
public class InboundController {
    private final InboundShipmentService shipments;

    public InboundController(InboundShipmentService shipments) {
        this.shipments = shipments;
    }

    private static Integer v(String ifMatch) {
        return ifMatch == null || ifMatch.isBlank() ? null : com.commerceos.shared.web.IfMatch.parse(ifMatch);
    }

    public record ReasonBody(String reason) {
    }

    @GetMapping("/inbound-shipments")
    public ApiResponse<List<InboundShipmentService.ShipmentView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                      @RequestParam(required = false) String status,
                                                                      @RequestParam(name = "po_id", required = false) UUID poId) {
        return ApiResponse.of(shipments.list(be, status, poId));
    }

    @GetMapping("/inbound-shipments/{id}")
    public ApiResponse<InboundShipmentService.ShipmentView> get(@PathVariable UUID id) {
        return ApiResponse.of(shipments.get(id));
    }

    @PostMapping("/purchase-orders/{id}/inbound-shipments")
    public ResponseEntity<ApiResponse<InboundShipmentService.ShipmentView>> plan(@PathVariable UUID id, @RequestBody InboundShipmentService.PlanInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(shipments.plan(id, body)));
    }

    @PostMapping("/inbound-shipments/{id}/arrive")
    public ApiResponse<InboundShipmentService.ShipmentView> arrive(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                  @RequestBody InboundShipmentService.ArriveInput body) {
        return ApiResponse.of(shipments.arrive(id, v(ifMatch), body));
    }

    @PostMapping("/inbound-shipments/{id}/start-inspection")
    public ApiResponse<InboundShipmentService.ShipmentView> startInspection(@PathVariable UUID id,
                                                                           @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ApiResponse.of(shipments.startInspection(id, v(ifMatch)));
    }

    @PostMapping("/inbound-shipments/{id}/inspect")
    public ApiResponse<InboundShipmentService.ShipmentView> inspect(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                   @RequestBody InboundShipmentService.InspectInput body) {
        return ApiResponse.of(shipments.inspect(id, v(ifMatch), body));
    }

    @PostMapping("/inbound-shipments/{id}/putaway")
    public ApiResponse<InboundShipmentService.ShipmentView> putaway(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                   @RequestBody InboundShipmentService.PutawayInput body) {
        return ApiResponse.of(shipments.putaway(id, v(ifMatch), body));
    }

    @PostMapping("/inbound-shipments/{id}/cancel")
    public ApiResponse<InboundShipmentService.ShipmentView> cancel(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                                  @RequestBody ReasonBody body) {
        return ApiResponse.of(shipments.cancel(id, v(ifMatch), body == null ? null : body.reason()));
    }
}
