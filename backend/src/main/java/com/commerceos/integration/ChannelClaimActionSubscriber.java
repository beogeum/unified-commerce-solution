package com.commerceos.integration;

import com.commerceos.platform.job.JobService;
import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 채널에서 수집한 클레임을 승인·거부하면 채널에도 반영하는 작업을 건다(M4 O6). */
@Component
public class ChannelClaimActionSubscriber implements OutboxSubscriber {
    private final JobService jobs;

    public ChannelClaimActionSubscriber(JobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public String consumerName() {
        return "integration.channel-claim-action";
    }

    @Override
    public boolean supports(String eventType) {
        return "ClaimChannelActionRequested".equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        jobs.enqueue(new JobService.NewJob(event.workspaceId(), ChannelClaimActionJobHandler.TYPE, Map.of("claim_id", event.payload().get("claim_id").asString()),
                event.businessEntityId(), null, null, null, ChannelClaimActionJobHandler.MAX_ATTEMPTS));
    }
}
