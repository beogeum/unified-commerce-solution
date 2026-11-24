package com.commerceos.inventory;

import com.commerceos.shared.web.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 거점 포장 프로필(부자재 출고 차감)과 출고지시별 부자재 차감 내역. */
@RestController
@RequestMapping("/api/v1")
public class MaterialController {
    private final MaterialService materials;

    public MaterialController(MaterialService materials) {
        this.materials = materials;
    }

    public record PackagingBody(String packagingProfileCode) {
    }

    @GetMapping("/fulfillment-nodes/{id}/packaging")
    public ApiResponse<MaterialService.Packaging> get(@PathVariable UUID id) {
        return ApiResponse.of(materials.packaging(id));
    }

    @PutMapping("/fulfillment-nodes/{id}/packaging")
    public ApiResponse<MaterialService.Packaging> set(@PathVariable UUID id, @RequestBody PackagingBody body) {
        return ApiResponse.of(materials.setPackaging(id, body == null ? null : body.packagingProfileCode()));
    }

    @GetMapping("/fulfillment-orders/{id}/materials")
    public ApiResponse<List<MaterialService.ConsumptionView>> consumption(@PathVariable UUID id) {
        return ApiResponse.of(materials.consumption(id));
    }
}
