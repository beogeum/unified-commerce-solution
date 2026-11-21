package com.commerceos.operations;

import com.commerceos.platform.file.FileService;
import com.commerceos.platform.idempotency.IdempotencyService;
import com.commerceos.identity.access.AccessGuard;
import com.commerceos.identity.access.CurrentActor;
import com.commerceos.shared.error.Errors;
import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class PlatformController {
    private final FileService files;
    private final ExportService exports;
    private final SavedViewService views;
    private final SearchService search;
    private final IdempotencyService idempotency;
    private final AccessGuard guard;

    public PlatformController(FileService files, ExportService exports, SavedViewService views, SearchService search,
                              IdempotencyService idempotency, AccessGuard guard) {
        this.files = files;
        this.exports = exports;
        this.views = views;
        this.search = search;
        this.idempotency = idempotency;
        this.guard = guard;
    }

    public record ExportRequest(String exportType, List<String> businessEntityIds) {
    }

    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FileService.FileView>> upload(@RequestPart("file") MultipartFile file,
                                                                    @RequestParam String purpose,
                                                                    @RequestParam(name = "business_entity_id", required = false) UUID businessEntityId,
                                                                    @RequestParam(name = "contains_pii", defaultValue = "false") boolean containsPii)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw Errors.validation("file", "업로드할 파일을 선택하세요.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(files.upload(businessEntityId, purpose,
                file.getOriginalFilename(), file.getContentType(), file.getBytes(), containsPii)));
    }

    @GetMapping("/files/{id}")
    public ApiResponse<FileService.FileView> file(@PathVariable UUID id) {
        return ApiResponse.of(files.get(id));
    }

    @GetMapping("/files/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id) {
        FileService.Download d = files.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(d.file().originalFilename(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(d.file().contentType()))
                .body(d.content());
    }

    @PostMapping("/exports")
    public ResponseEntity<?> export(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                    @RequestBody ExportRequest req, HttpServletRequest http) {
        CurrentActor a = guard.actor();
        return idempotency.execute(a.workspaceId(), a.memberId(), key, "POST", http.getRequestURI(), req, () ->
                ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(exports.request(req.exportType(), req.businessEntityIds()))));
    }

    @GetMapping("/search")
    public ApiResponse<List<SearchService.Hit>> search(@RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 50) {
            throw Errors.validation("limit", "limit 은 1~50 사이여야 합니다.");
        }
        return ApiResponse.of(search.search(q, limit));
    }

    @GetMapping("/saved-views")
    public ApiResponse<List<SavedViewService.SavedView>> savedViews(@RequestParam(name = "screen_key", required = false) String screenKey) {
        return ApiResponse.of(views.list(screenKey));
    }

    @PostMapping("/saved-views")
    public ResponseEntity<ApiResponse<SavedViewService.SavedView>> createView(@RequestBody SavedViewService.Upsert req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(views.create(req)));
    }

    @PatchMapping("/saved-views/{id}")
    public ApiResponse<SavedViewService.SavedView> updateView(@PathVariable UUID id,
                                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                              @RequestBody SavedViewService.Upsert req) {
        return ApiResponse.of(views.update(id, IfMatch.parse(ifMatch), req));
    }

    @DeleteMapping("/saved-views/{id}")
    public ResponseEntity<Void> deleteView(@PathVariable UUID id) {
        views.delete(id);
        return ResponseEntity.noContent().build();
    }
}
