package com.commerceos.operations.imports;

import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 일괄 가져오기(M2 C6). 파일은 먼저 POST /files (purpose=GENERIC_IMPORT) 로 올린다. */
@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {
    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
    }

    public record Execute(Integer confirmRows) {
    }

    @GetMapping("/templates")
    public ApiResponse<List<ImportService.TemplateView>> templates() {
        return ApiResponse.of(service.templates());
    }

    @GetMapping("/templates/{type}/file")
    public ResponseEntity<byte[]> templateFile(@PathVariable String type) {
        return csv(service.templateCsv(type), type.toLowerCase() + "-template.csv");
    }

    @GetMapping
    public ApiResponse<List<ImportService.BatchView>> list() {
        return ApiResponse.of(service.list());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ImportService.BatchView>> create(@RequestBody ImportService.Create req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(req)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ImportService.BatchView>> get(@PathVariable UUID id) {
        var v = service.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @GetMapping("/{id}/rows")
    public ApiResponse<List<ImportService.RowView>> rows(@PathVariable UUID id, @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.of(service.rows(id, status, offset, limit));
    }

    @GetMapping("/{id}/problem-rows")
    public ResponseEntity<byte[]> problemRows(@PathVariable UUID id) {
        return csv(service.problemRowsCsv(id), "import-" + id + "-problems.csv");
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<ApiResponse<ImportService.BatchView>> execute(@PathVariable UUID id,
                                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                        @RequestBody Execute req) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(service.execute(id, IfMatch.parse(ifMatch), req.confirmRows())));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ImportService.BatchView> cancel(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return ApiResponse.of(service.cancel(id, IfMatch.parse(ifMatch)));
    }

    private static ResponseEntity<byte[]> csv(byte[] body, String name) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
