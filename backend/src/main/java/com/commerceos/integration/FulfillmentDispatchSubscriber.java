package com.commerceos.integration;

import com.commerceos.platform.job.JobService;
import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 출고지시가 만들어지면 전송 작업을 건다(외부 전송은 비동기·재시도, CLAUDE.md). */
@Component
public class FulfillmentDispatchSubscriber implements OutboxSubscriber {
    private final JobService jobs;

    public FulfillmentDispatchSubscriber(JobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public String consumerName() {
        return "integration.fulfillment-dispatch";
    }

    @Override
    public boolean supports(String eventType) {
        return "FulfillmentOrderCreated".equals(eventType) || "FulfillmentDispatchRequested".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        jobs.enqueue(new JobService.NewJob(event.workspaceId(), FulfillmentDispatchJobHandler.TYPE,
                Map.of("fulfillment_order_id", event.payload().get("fulfillment_order_id").asString()), event.businessEntityId(), null, null, null, 6));
    }
}
