package com.commerceos.finance;

import com.commerceos.platform.outbox.OutboxEvent;
import com.commerceos.platform.outbox.OutboxSubscriber;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 출고·반품 검수·클레임 완료·폐기 사건을 관리 손익 원장에 기록(M5). 같은 사건 재전달은 원장 source_key 로 멱등. */
@Component
public class FinanceSubscriber implements OutboxSubscriber {
    private final PostingService posting;
    private final com.commerceos.b2b.ReceivableService receivables;

    public FinanceSubscriber(PostingService posting, com.commerceos.b2b.ReceivableService receivables) {
        this.posting = posting;
        this.receivables = receivables;
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
            case "ShipmentRecorded" -> {
                UUID fo = UUID.fromString(e.payload().get("fulfillment_order_id").asString());
                posting.onShipment(ws, fo);
                receivables.onShipment(ws, fo); // 외상 주문: 방금 기록한 매출 원장과 같은 금액으로 청구(DLG-229)
            }
            case "ReturnInspected" -> posting.onReturnInspected(ws, UUID.fromString(e.payload().get("return_receipt_id").asString()));
            case "ClaimCompleted" -> {
                UUID claim = UUID.fromString(e.payload().get("claim_id").asString());
                posting.onClaimCompleted(ws, claim);
                receivables.onClaimCompleted(ws, claim);
            }
            case "IntercompanyTransferCompleted" -> posting.onIntercompanyTransfer(ws, UUID.fromString(e.payload().get("transfer_id").asString()));
            default -> posting.onDisposed(ws, UUID.fromString(e.payload().get("approval_id").asString()), UUID.fromString(e.payload().get("stock_id").asString()),
                    e.payload().get("quantity").asInt());
        }
    }
}
