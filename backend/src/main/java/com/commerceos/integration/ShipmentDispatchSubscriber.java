package com.commerceos.integration;

import com.commerceos.platform.job.JobService;
import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** 송장이 등록되면 판매채널 발송처리(송장 전송) 작업을 송장마다 건다(M4 O5, 외부 전송은 비동기·재시도). */
@Component
public class ShipmentDispatchSubscriber implements OutboxSubscriber {
    private final JobService jobs;

    public ShipmentDispatchSubscriber(JobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public String consumerName() {
        return "integration.channel-shipment-dispatch";
    }

    @Override
    public boolean supports(String eventType) {
        return "ShipmentRecorded".equals(eventType) || "ShipmentChannelDispatchRequested".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        List<String> ids = new ArrayList<>();
        JsonNode many = event.payload().get("shipment_ids");
        if (many != null) {
            many.forEach(n -> ids.add(n.asString()));
        } else {
            ids.add(event.payload().get("shipment_id").asString());
        }
        for (String id : ids) {
            jobs.enqueue(new JobService.NewJob(event.workspaceId(), ChannelShipmentDispatchJobHandler.TYPE, Map.of("shipment_id", id), event.businessEntityId(),
                    null, null, null, ChannelShipmentDispatchJobHandler.MAX_ATTEMPTS));
        }
    }
}
