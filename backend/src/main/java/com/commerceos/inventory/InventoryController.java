package com.commerceos.inventory;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
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

/** 재고 조회·입고·품질 변경·확보(M3 I2~I3). 쓰기는 모두 Idempotency-Key 필수. */
@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {
    private final InventoryService inventory;
    private final ReservationService reservations;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public InventoryController(InventoryService inventory, ReservationService reservations, IdempotencyService idempotency, AccessGuard guard) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    @GetMapping("/positions")
    public ApiResponse<List<InventoryService.PositionView>> positions(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                     @RequestParam(name = "node_id", required = false) UUID node,
                                                                     @RequestParam(name = "sku_id", required = false) UUID sku,
                                                                     @RequestParam(required = false) String q,
                                                                     @RequestParam(name = "non_zero", required = false) Boolean nonZero) {
        return ApiResponse.of(inventory.positions(new InventoryService.Filter(be, node, sku, q, null, null, nonZero)));
    }

    @GetMapping("/stocks")
    public ApiResponse<List<InventoryService.StockView>> stocks(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                               @RequestParam(name = "node_id", required = false) UUID node,
                                                               @RequestParam(name = "sku_id", required = false) UUID sku,
                                                               @RequestParam(required = false) String q,
                                                               @RequestParam(name = "quality_status", required = false) String quality) {
        return ApiResponse.of(inventory.stocks(new InventoryService.Filter(be, node, sku, q, quality, null, null)));
    }

    @GetMapping("/transactions")
    public ApiResponse<List<InventoryService.TxnView>> transactions(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                   @RequestParam(name = "node_id", required = false) UUID node,
                                                                   @RequestParam(name = "sku_id", required = false) UUID sku,
                                                                   @RequestParam(required = false) String q,
                                                                   @RequestParam(name = "txn_type", required = false) String type,
                                                                   @RequestParam(required = false) Integer limit,
                                                                   @RequestParam(required = false) String cursor) {
        return inventory.transactions(new InventoryService.Filter(be, node, sku, q, null, type, null), PageQuery.of(limit, cursor));
    }

    @GetMapping("/availability")
    public ApiResponse<InventoryService.Availability> availability(@RequestParam(name = "business_entity_id") UUID be, @RequestParam(name = "sku_id") UUID sku) {
        return ApiResponse.of(inventory.availability(be, sku));
    }

    @GetMapping("/verification")
    public ApiResponse<List<InventoryService.Mismatch>> verification() {
        return ApiResponse.of(inventory.verify());
    }

    @PostMapping("/receipts")
    public ResponseEntity<?> receive(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody InventoryService.Receipt req,
                                     HttpServletRequest http) {
        return idem(key, http, req, () -> inventory.receive(req));
    }

    @PostMapping("/quality-changes")
    public ResponseEntity<?> changeQuality(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody InventoryService.QualityChange req,
                                           HttpServletRequest http) {
        return idem(key, http, req, () -> inventory.changeQuality(req));
    }

    @GetMapping("/reservations")
    public ApiResponse<List<ReservationService.ReservationView>> reservations(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                             @RequestParam(name = "node_id", required = false) UUID node,
                                                                             @RequestParam(name = "sku_id", required = false) UUID sku,
                                                                             @RequestParam(required = false) String state,
                                                                             @RequestParam(name = "demand_type", required = false) String demandType,
                                                                             @RequestParam(name = "demand_ref", required = false) String demandRef) {
        return ApiResponse.of(reservations.list(new ReservationService.Filter(be, node, sku, state, demandType, demandRef)));
    }

    @GetMapping("/reservations/{id}")
    public ApiResponse<ReservationService.ReservationView> reservation(@PathVariable UUID id) {
        return ApiResponse.of(reservations.get(id));
    }

    @PostMapping("/reservations")
    public ResponseEntity<?> reserve(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody ReservationService.Reserve req,
                                     HttpServletRequest http) {
        return idem(key, http, req, () -> reservations.reserve(req));
    }

    @PostMapping("/reservations/{id}/{action}")
    public ResponseEntity<?> transition(@PathVariable UUID id, @PathVariable String action, @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @RequestBody(required = false) ReservationService.Transition req, HttpServletRequest http) {
        ReservationService.Transition t = req == null ? new ReservationService.Transition(null, null) : req;
        Supplier<Object> work = switch (action) {
            case "allocate" -> () -> reservations.allocate(id, t);
            case "pick" -> () -> reservations.pick(id, t);
            case "consume" -> () -> reservations.consume(id, t);
            case "release" -> () -> reservations.release(id, t);
            default -> throw com.commerceos.shared.error.Errors.notFound("ACTION");
        };
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), t, () -> ResponseEntity.ok(ApiResponse.of(work.get())));
    }

    @PostMapping("/reservations/release-by-demand")
    public ResponseEntity<?> releaseByDemand(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                             @RequestBody ReservationService.ReleaseByDemand req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req,
                () -> ResponseEntity.ok(ApiResponse.of(reservations.releaseByDemand(req))));
    }

    private ResponseEntity<?> idem(String key, HttpServletRequest http, Object req, Supplier<Object> work) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(work.get())));
    }
}
