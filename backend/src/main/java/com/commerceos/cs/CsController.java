package com.commerceos.cs;

import com.commerceos.shared.web.ApiResponse;
import java.util.List;
import java.util.Map;
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

/** CS 문의 통합함(FR-CS-01). */
@RestController
@RequestMapping("/api/v1/cs")
public class CsController {
    private final CsService cs;

    public CsController(CsService cs) {
        this.cs = cs;
    }

    public record MessageBody(String direction, String body, UUID templateId) {
    }

    public record UpdateBody(String status, UUID assigneeMemberId, String priority) {
    }

    public record ClaimBody(UUID claimId) {
    }

    @GetMapping("/inquiries")
    public ApiResponse<List<CsService.InquiryView>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                         @RequestParam(required = false) String status, @RequestParam(name = "inquiry_type", required = false) String type,
                                                         @RequestParam(required = false) Boolean mine, @RequestParam(required = false) Boolean overdue,
                                                         @RequestParam(name = "order_id", required = false) UUID orderId, @RequestParam(required = false) String q) {
        return ApiResponse.of(cs.list(be, status, type, mine, overdue, orderId, q));
    }

    @GetMapping("/kpi")
    public ApiResponse<CsService.Kpi> kpi(@RequestParam(name = "business_entity_id", required = false) List<String> be) {
        return ApiResponse.of(cs.kpi(be));
    }

    @GetMapping("/inquiries/{id}")
    public ApiResponse<CsService.InquiryView> get(@PathVariable UUID id) {
        return ApiResponse.of(cs.get(id));
    }

    @PostMapping("/inquiries")
    public ResponseEntity<ApiResponse<CsService.InquiryView>> create(@RequestBody CsService.NewInquiry body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(cs.create(body)));
    }

    @PostMapping("/inquiries/{id}/messages")
    public ResponseEntity<ApiResponse<CsService.InquiryView>> message(@PathVariable UUID id, @RequestBody MessageBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(cs.addMessage(id, body == null ? null : body.direction(), body == null ? null : body.body(),
                body == null ? null : body.templateId())));
    }

    @PostMapping("/inquiries/{id}/messages/{messageId}/channel-posted")
    public ApiResponse<CsService.InquiryView> posted(@PathVariable UUID id, @PathVariable UUID messageId) {
        return ApiResponse.of(cs.markChannelPosted(id, messageId));
    }

    @PatchMapping("/inquiries/{id}")
    public ApiResponse<CsService.InquiryView> update(@PathVariable UUID id, @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                     @RequestBody UpdateBody body) {
        Integer v = ifMatch == null || ifMatch.isBlank() ? null : com.commerceos.shared.web.IfMatch.parse(ifMatch);
        return ApiResponse.of(cs.update(id, body == null ? null : body.status(), body == null ? null : body.assigneeMemberId(), body == null ? null : body.priority(), v));
    }

    @PostMapping("/inquiries/{id}/claim")
    public ApiResponse<CsService.InquiryView> linkClaim(@PathVariable UUID id, @RequestBody ClaimBody body) {
        return ApiResponse.of(cs.linkClaim(id, body == null ? null : body.claimId()));
    }

    @GetMapping("/inquiries/{id}/render")
    public ApiResponse<Map<String, Object>> render(@PathVariable UUID id, @RequestParam(name = "template_id") UUID templateId) {
        return ApiResponse.of(cs.render(id, templateId));
    }

    @GetMapping("/templates")
    public ApiResponse<List<CsService.TemplateView>> templates(@RequestParam(name = "business_entity_id", required = false) UUID be) {
        return ApiResponse.of(cs.templates(be));
    }

    @PostMapping("/templates")
    public ResponseEntity<ApiResponse<CsService.TemplateView>> createTemplate(@RequestBody CsService.TemplateInput body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(cs.saveTemplate(null, body)));
    }

    @PutMapping("/templates/{id}")
    public ApiResponse<CsService.TemplateView> saveTemplate(@PathVariable UUID id, @RequestBody CsService.TemplateInput body) {
        return ApiResponse.of(cs.saveTemplate(id, body));
    }
}
