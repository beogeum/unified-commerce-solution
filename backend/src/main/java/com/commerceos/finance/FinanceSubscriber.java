package com.commerceos.finance;

import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 출고·반품 검수·클레임 완료·폐기 사건을 관리 손익 원장에 기록(M5). 같은 사건 재전달은 원장 source_key 로 멱등. */
@Component
public class FinanceSubscriber implements OutboxSubscriber {
    private final PostingService posting;

    public FinanceSubscriber(PostingService posting) {
        this.posting = posting;
    }

    @Override
    public String consumerName() {
        return "finance.posting";
    }

    @Override
    public boolean supports(String eventType) {
        return switch (eventType) {
            case "ShipmentRecorded", "ReturnInspected", "ClaimCompleted", "InventoryDisposed", "IntercompanyTransferCompleted" -> true;
            default -> false;
        };
    }

    @Override
    public void handle(OutboxEvent e) {
        UUID ws = e.workspaceId();
        switch (e.eventType()) {
            case "ShipmentRecorded" -> posting.onShipment(ws, UUID.fromString(e.payload().get("fulfillment_order_id").asString()));
            case "ReturnInspected" -> posting.onReturnInspected(ws, UUID.fromString(e.payload().get("return_receipt_id").asString()));
            case "ClaimCompleted" -> posting.onClaimCompleted(ws, UUID.fromString(e.payload().get("claim_id").asString()));
            case "IntercompanyTransferCompleted" -> posting.onIntercompanyTransfer(ws, UUID.fromString(e.payload().get("transfer_id").asString()));
            default -> posting.onDisposed(ws, UUID.fromString(e.payload().get("approval_id").asString()), UUID.fromString(e.payload().get("stock_id").asString()),
                    e.payload().get("quantity").asInt());
        }
    }
}
