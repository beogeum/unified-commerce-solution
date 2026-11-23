package com.commerceos.finance;

import com.commerceos.shared.error.Errors;
import com.commerceos.shared.web.ApiResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 부가세 신고자료 표준화(M6 2차, docs/15). */
@RestController
@RequestMapping("/api/v1/vat")
public class VatController {
    private final VatService vat;
    private final VatExportService exports;

    public VatController(VatService vat, VatExportService exports) {
        this.vat = vat;
        this.exports = exports;
    }

    public record OverrideBody(String classification, String reason) {
    }

    public record ExportBody(UUID businessEntityId, LocalDate periodFrom, LocalDate periodTo) {
    }

    @GetMapping("/batches")
    public ApiResponse<List<VatService.BatchView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.of(vat.list(be, from, to));
    }

    @GetMapping("/batches/{id}")
    public ApiResponse<VatService.BatchView> get(@PathVariable UUID id) {
        return ApiResponse.of(vat.get(id));
    }

    @PostMapping("/batches")
    public ResponseEntity<ApiResponse<VatService.BatchView>> create(@RequestBody VatService.NewBatch body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(vat.createBatch(body)));
    }

    @PostMapping("/batches/{id}/files")
    public ApiResponse<VatService.ImportResult> upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        try {
            return ApiResponse.of(vat.importFile(id, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        } catch (IOException e) {
            throw Errors.validation("file", "파일을 읽지 못했습니다.");
        }
    }

    @PostMapping("/rows/{id}/override")
    public ApiResponse<VatService.RowView> override(@PathVariable UUID id, @RequestBody OverrideBody body) {
        return ApiResponse.of(vat.override(id, body == null ? null : body.classification(), body == null ? null : body.reason()));
    }

    @PostMapping("/batches/{id}/confirm")
    public ApiResponse<VatService.BatchView> confirm(@PathVariable UUID id) {
        return ApiResponse.of(vat.confirm(id));
    }

    @GetMapping("/reports")
    public ApiResponse<VatService.EntityReport> report(@RequestParam(name = "business_entity_id") UUID be,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.of(vat.entityReport(be, from, to));
    }

    @PostMapping("/exports")
    public ResponseEntity<ApiResponse<VatExportService.Accepted>> export(@RequestBody ExportBody body) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(exports.request(body == null ? null : body.businessEntityId(),
                body == null ? null : body.periodFrom(), body == null ? null : body.periodTo())));
    }
}
