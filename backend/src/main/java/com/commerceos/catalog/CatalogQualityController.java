package com.commerceos.catalog;

import com.commerceos.shared.web.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 상품정보 품질 점검(FR-CAT-08). */
@RestController
@RequestMapping("/api/v1/catalog")
public class CatalogQualityController {
    private final CatalogQualityService quality;

    public CatalogQualityController(CatalogQualityService quality) {
        this.quality = quality;
    }

    @GetMapping("/quality")
    public ApiResponse<CatalogQualityService.Report> quality(@RequestParam(name = "business_entity_id", required = false) List<String> be) {
        return ApiResponse.of(quality.check(be));
    }
}
