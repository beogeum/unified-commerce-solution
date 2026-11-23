-- P1 수동·B2B·샘플 주문(FR-ORD-04, DLG-197~200)

-- 주문 종류: 채널 주문(NORMAL)과 화면에서 직접 만든 수동 주문(MANUAL 일반·B2B 납품·SAMPLE 무상 샘플)
alter table sales_order add column order_kind text not null default 'NORMAL' check (order_kind in ('NORMAL', 'MANUAL', 'B2B', 'SAMPLE'));
alter table sales_order add column customer_name text check (customer_name is null or length(customer_name) <= 100);
alter table sales_order add column created_by uuid;
alter table sales_order add constraint sales_order_manual_kind_ck check (order_kind = 'NORMAL' or source_type = 'MANUAL');

alter table claim_number_counter drop constraint claim_number_counter_kind_check;
alter table claim_number_counter add constraint claim_number_counter_kind_check
    check (kind in ('CLAIM', 'RETURN_RECEIPT', 'WMS_WAVE', 'PURCHASE_ORDER', 'PO_RECEIPT', 'MANUAL_ORDER'));
