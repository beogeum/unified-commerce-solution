package com.commerceos.catalog;

import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import com.commerceos.shared.web.PageQuery;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상품·SKU·브랜드·카테고리 API(docs/07 §상품, M2 C1). 운영그룹 공통 마스터라 사업자 범위 필터가 없다. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final ProductService products;
    private final SkuService skus;
    private final BrandCategoryService refs;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public CatalogController(ProductService products, SkuService skus, BrandCategoryService refs, IdempotencyService idempotency,
                             AccessGuard guard) {
        this.products = products;
        this.skus = skus;
        this.refs = refs;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record NameRequest(@NotBlank @Size(max = 100) String name, UUID parentId) {
    }

    public record BrandPatch(String name, String status) {
    }

    public record ValueRequest(@NotBlank @Size(max = 100) String value) {
    }

    // ---- 브랜드·카테고리 ----
    @GetMapping("/brands")
    public ApiResponse<List<BrandCategoryService.BrandView>> brands(@RequestParam(required = false) String status) {
        return ApiResponse.of(refs.brands(status));
    }

    @PostMapping("/brands")
    public ResponseEntity<?> createBrand(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @Valid @RequestBody NameRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(refs.createBrand(req.name()))));
    }

    @PatchMapping("/brands/{id}")
    public ApiResponse<BrandCategoryService.BrandView> patchBrand(@PathVariable UUID id,
                                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                  @RequestBody BrandPatch req) {
        return ApiResponse.of(refs.patchBrand(id, IfMatch.parse(ifMatch), req.name(), req.status()));
    }

    @GetMapping("/categories")
    public ApiResponse<List<BrandCategoryService.CategoryView>> categories() {
        return ApiResponse.of(refs.categories());
    }

    @PostMapping("/categories")
    public ResponseEntity<?> createCategory(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @Valid @RequestBody NameRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(refs.createCategory(req.name(), req.parentId()))));
    }

    // ---- 상품 ----
    @GetMapping("/products")
    public ApiResponse<List<ProductService.ProductView>> listProducts(@RequestParam(required = false) Integer limit,
                                                                      @RequestParam(required = false) String cursor,
                                                                      @RequestParam(required = false) String q,
                                                                      @RequestParam(required = false) String status,
                                                                      @RequestParam(name = "brand_id", required = false) UUID brandId,
                                                                      @RequestParam(name = "category_id", required = false) UUID categoryId) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(products.list(page, q, status, brandId, categoryId), ProductService.ProductView::id);
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductService.ProductDetail>> getProduct(@PathVariable UUID id) {
        var v = products.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.product().version())).body(ApiResponse.of(v));
    }

    @PostMapping("/products")
    public ResponseEntity<?> createProduct(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @RequestBody ProductService.Create req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(products.create(req))));
    }

    @PatchMapping("/products/{id}")
    public ResponseEntity<ApiResponse<ProductService.ProductDetail>> patchProduct(
            @PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody ProductService.Patch req) {
        var v = products.patch(id, IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.product().version())).body(ApiResponse.of(v));
    }

    @PostMapping("/products/{id}/options")
    public ResponseEntity<ApiResponse<ProductService.OptionView>> addOption(@PathVariable UUID id, @RequestBody ProductService.NewOption req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(products.addOption(id, req)));
    }

    @PostMapping("/products/{id}/options/{optionId}/values")
    public ResponseEntity<ApiResponse<ProductService.OptionView>> addOptionValue(@PathVariable UUID id, @PathVariable UUID optionId,
                                                                                 @Valid @RequestBody ValueRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(products.addOptionValue(id, optionId, req.value())));
    }

    // ---- SKU ----
    @GetMapping("/skus")
    public ApiResponse<List<SkuService.SkuView>> listSkus(@RequestParam(required = false) Integer limit,
                                                          @RequestParam(required = false) String cursor,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(name = "sku_type", required = false) String skuType,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(name = "product_id", required = false) UUID productId) {
        PageQuery page = PageQuery.of(limit, cursor);
        return page.toResponse(skus.list(page, q, skuType, status, productId), SkuService.SkuView::id);
    }

    @GetMapping("/skus/{id}")
    public ResponseEntity<ApiResponse<SkuService.SkuView>> getSku(@PathVariable UUID id) {
        var v = skus.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping("/skus")
    public ResponseEntity<?> createSku(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                       @RequestBody SkuService.Create req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(skus.create(req))));
    }

    @PatchMapping("/skus/{id}")
    public ResponseEntity<ApiResponse<SkuService.SkuView>> patchSku(@PathVariable UUID id,
                                                                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                    @RequestBody SkuService.Patch req) {
        var v = skus.patch(id, IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }
}
