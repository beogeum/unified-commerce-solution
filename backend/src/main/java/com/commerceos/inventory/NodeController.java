package com.commerceos.inventory;

import com.commerceos.shared.web.ApiResponse;
import com.commerceos.shared.web.IfMatch;
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
import org.springframework.web.bind.annotation.RestController;

/** 출고거점(M3 I1). */
@RestController
@RequestMapping("/api/v1/fulfillment-nodes")
public class NodeController {
    private final NodeService service;

    public NodeController(NodeService service) {
        this.service = service;
    }

    public record LinkShipper(UUID shipperAccountId) {
    }

    public record NewLocation(String code, String zone) {
    }

    @GetMapping
    public ApiResponse<List<NodeService.NodeView>> list() {
        return ApiResponse.of(service.list());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<NodeService.NodeView>> create(@RequestBody NodeService.Create req) {
        var v = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<NodeService.NodeView>> get(@PathVariable UUID id) {
        var v = service.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<NodeService.NodeView>> patch(@PathVariable UUID id, @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                  @RequestBody NodeService.Patch req) {
        var v = service.patch(id, IfMatch.parse(ifMatch), req);
        return ResponseEntity.ok().eTag(IfMatch.etag(v.version())).body(ApiResponse.of(v));
    }

    @PostMapping("/{id}/shippers")
    public ApiResponse<NodeService.NodeView> linkShipper(@PathVariable UUID id, @RequestBody LinkShipper req) {
        return ApiResponse.of(service.linkShipper(id, req.shipperAccountId()));
    }

    @GetMapping("/{id}/locations")
    public ApiResponse<List<NodeService.LocationView>> locations(@PathVariable UUID id) {
        return ApiResponse.of(service.locations(id));
    }

    @PostMapping("/{id}/locations")
    public ResponseEntity<ApiResponse<NodeService.LocationView>> addLocation(@PathVariable UUID id, @RequestBody NewLocation req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.addLocation(id, req.code(), req.zone())));
    }
}
