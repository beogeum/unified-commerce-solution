package com.commerceos.insight;

import com.commerceos.shared.web.ApiResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 운영 리포트·인사이트(M6 1차). 읽기·추천 전용. */
@RestController
@RequestMapping("/api/v1")
public class InsightController {
    private final AnalyticsService analytics;
    private final InsightService insights;
    private final SimulationService simulations;

    public InsightController(AnalyticsService analytics, InsightService insights, SimulationService simulations) {
        this.simulations = simulations;
        this.analytics = analytics;
        this.insights = insights;
    }

    public record DismissBody(String reason) {
    }

    @GetMapping("/analytics/inventory")
    public ApiResponse<AnalyticsService.InventoryReport> inventory(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                   @RequestParam(name = "cover_days", required = false) Integer cover,
                                                                   @RequestParam(name = "slow_days", required = false) Integer slow,
                                                                   @RequestParam(name = "expiry_days", required = false) Integer expiry) {
        return ApiResponse.of(analytics.inventory(be, cover, slow, expiry));
    }

    @GetMapping("/analytics/sales")
    public ApiResponse<AnalyticsService.SalesReport> sales(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                           @RequestParam(name = "group_by", required = false) String groupBy) {
        return ApiResponse.of(analytics.sales(be, from, to, groupBy));
    }

    @GetMapping("/analytics/logistics")
    public ApiResponse<AnalyticsService.LogisticsReport> logistics(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.of(analytics.logistics(be, from, to));
    }

    @PostMapping("/simulations/profit")
    public ApiResponse<SimulationService.Result> simulate(@RequestBody SimulationService.Scenario body) {
        return ApiResponse.of(simulations.simulate(body));
    }

    @PostMapping("/simulations/profit/work-item")
    public ApiResponse<Map<String, Object>> saveSimulation(@RequestBody SimulationService.Scenario body) {
        return ApiResponse.of(simulations.saveAsWorkItem(body));
    }

    @GetMapping("/insights")
    public ApiResponse<List<InsightService.Card>> list(@RequestParam(name = "business_entity_id", required = false) List<String> be,
                                                       @RequestParam(required = false) String status, @RequestParam(required = false) String rule) {
        return ApiResponse.of(insights.list(be, status, rule));
    }

    @GetMapping("/insights/last-run")
    public ApiResponse<Map<String, Object>> lastRun() {
        return ApiResponse.of(insights.lastRun());
    }

    @GetMapping("/insights/{id}")
    public ApiResponse<InsightService.Card> get(@PathVariable UUID id) {
        return ApiResponse.of(insights.get(id));
    }

    @PostMapping("/insights/refresh")
    public ApiResponse<InsightService.RunResult> refresh() {
        return ApiResponse.of(insights.refresh());
    }

    @PostMapping("/insights/{id}/work-item")
    public ApiResponse<InsightService.Card> workItem(@PathVariable UUID id) {
        return ApiResponse.of(insights.createWorkItem(id));
    }

    @PostMapping("/insights/{id}/dismiss")
    public ApiResponse<InsightService.Card> dismiss(@PathVariable UUID id, @RequestBody DismissBody body) {
        return ApiResponse.of(insights.dismiss(id, body == null ? null : body.reason()));
    }
}
