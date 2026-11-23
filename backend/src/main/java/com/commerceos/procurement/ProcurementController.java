package com.commerceos.procurement;

import com.commerceos.shared.web.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 공급처·발주·입고(FR-FIN-01). */
@RestController
@RequestMapping("/api/v1")
public class ProcurementController {
    private final SupplierService suppliers;
    private final PurchaseOrderService orders;
    private final ReorderService reorder;

    public ProcurementController(SupplierService suppliers, PurchaseOrderService orders, ReorderService reorder) {
        this.reorder = reorder;
        this.suppliers = suppliers;
        this.orders = orders;
    }

    /** If-Match 는 선택(있으면 낙관적 잠금). */
    private static Integer v(String ifMatch) {
        return ifMatch == null || ifMatch.isBlank() ? null : com.commerceos.shared.web.IfMatch.parse(ifMatch);
    }

    public record ReasonBody(String reason) {
    }

    public record ConfirmBody(LocalDate expectedOn, String supplierRef) {
    }

    @GetMapping("/suppliers")
    public ApiResponse<List<SupplierService.SupplierView>> suppliers(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                     @RequestParam(required = false) String q) {
        return ApiResponse.of(suppliers.list(be, q));
    }

    @GetMapping("/suppliers/{id}")
    public ApiResponse<SupplierService.SupplierView> supplier(@PathVariable UUID id) {
        return ApiResponse.of(suppliers.get(id));
    }

    @PostMapping("/suppliers")
    public ResponseEntity<ApiResponse<SupplierService.SupplierView>> createSupplier(@RequestBody SupplierService.SupplierInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(suppliers.create(body)));
    }

    @PatchMapping("/suppliers/{id}")
    public ApiResponse<SupplierService.SupplierView> updateSupplier(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                                    @RequestBody SupplierService.SupplierInput body) {
        return ApiResponse.of(suppliers.update(id, body, v(ifMatch)));
    }

    @GetMapping("/suppliers/{id}/skus")
    public ApiResponse<List<SupplierService.SupplierSkuView>> supplierSkus(@PathVariable UUID id) {
        return ApiResponse.of(suppliers.skus(id));
    }

    @PutMapping("/suppliers/{id}/skus")
    public ApiResponse<SupplierService.SupplierSkuView> saveSupplierSku(@PathVariable UUID id, @RequestBody SupplierService.SupplierSkuInput body) {
        return ApiResponse.of(suppliers.upsertSku(id, body));
    }

    @GetMapping("/purchase-orders")
    public ApiResponse<List<PurchaseOrderService.PoView>> orders(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                 @RequestParam(required = false) String status,
                                                                 @RequestParam(name = "supplier_id", required = false) UUID supplier) {
        return ApiResponse.of(orders.list(be, status, supplier));
    }

    @GetMapping("/purchase-orders/inbound")
    public ApiResponse<List<PurchaseOrderService.InboundRow>> inbound(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                      @RequestParam(name = "sku_id", required = false) UUID sku) {
        return ApiResponse.of(orders.inbound(be, sku));
    }

    @GetMapping("/purchase-orders/suggestions")
    public ApiResponse<List<ReorderService.Suggestion>> suggestions(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                    @RequestParam(name = "cover_days", required = false) Integer cover) {
        return ApiResponse.of(reorder.suggestions(be, cover));
    }

    @PostMapping("/purchase-orders/from-suggestions")
    public ResponseEntity<ApiResponse<List<PurchaseOrderService.PoView>>> fromSuggestions(@RequestBody ReorderService.FromSuggestions body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(reorder.createDrafts(body)));
    }

    @GetMapping("/purchase-orders/{id}")
    public ApiResponse<PurchaseOrderService.PoView> order(@PathVariable UUID id) {
        return ApiResponse.of(orders.get(id));
    }

    @PostMapping("/purchase-orders")
    public ResponseEntity<ApiResponse<PurchaseOrderService.PoView>> create(@RequestBody PurchaseOrderService.PoInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(orders.create(body)));
    }

    @PatchMapping("/purchase-orders/{id}")
    public ApiResponse<PurchaseOrderService.PoView> update(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                           @RequestBody PurchaseOrderService.PoInput body) {
        return ApiResponse.of(orders.update(id, body, v(ifMatch)));
    }

    @PostMapping("/purchase-orders/{id}/submit")
    public ApiResponse<PurchaseOrderService.PoView> submit(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return ApiResponse.of(orders.submit(id, v(ifMatch)));
    }

    @PostMapping("/purchase-orders/{id}/supplier-confirm")
    public ApiResponse<PurchaseOrderService.PoView> supplierConfirm(@PathVariable UUID id, @RequestBody ConfirmBody body) {
        return ApiResponse.of(orders.supplierConfirm(id, body == null ? null : body.expectedOn(), body == null ? null : body.supplierRef()));
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    public ApiResponse<PurchaseOrderService.PoView> cancel(@PathVariable UUID id, @RequestBody ReasonBody body) {
        return ApiResponse.of(orders.cancel(id, body == null ? null : body.reason()));
    }

    @PostMapping("/purchase-orders/{id}/close")
    public ApiResponse<PurchaseOrderService.PoView> close(@PathVariable UUID id, @RequestBody ReasonBody body) {
        return ApiResponse.of(orders.closeShort(id, body == null ? null : body.reason()));
    }

    @PostMapping("/purchase-orders/{id}/receipts")
    public ResponseEntity<ApiResponse<PurchaseOrderService.PoView>> receive(@PathVariable UUID id, @RequestBody PurchaseOrderService.ReceiveInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(orders.receive(id, body)));
    }
}
