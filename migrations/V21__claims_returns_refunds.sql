-- M4 O6 취소·반품·교환 클레임, 반품 입고·검수, 환불, 판매채널 클레임 수집·처리 반영 (docs/04 §5·§6, docs/13 §5, DLG-115, CHG-010, DLG-137~146)
-- 클레임 상태축 4개(클레임·회수·환불·재출고)는 서로 독립. 요청만으로 수량·재고·매출을 바꾸지 않는다.

create table claim (
    id                         uuid primary key,
    workspace_id               uuid not null,
    claim_no                   text not null,
    order_id                   uuid not null,
    business_entity_id         uuid not null,
    channel_account_id         uuid not null,
    claim_type                 text not null check (claim_type in ('CANCEL', 'RETURN', 'EXCHANGE')),
    source                     text not null check (source in ('MANUAL', 'API')),
    external_claim_key         text check (external_claim_key is null or length(external_claim_key) between 1 and 200),
    status                     text not null check (status in ('REQUESTED', 'REVIEWING', 'APPROVED', 'REJECTED', 'PROCESSING', 'COMPLETED', 'CANCELLED')),
    return_status              text not null check (return_status in ('NOT_REQUIRED', 'REQUESTED', 'IN_TRANSIT', 'RECEIVED', 'INSPECTED')),
    refund_status              text not null check (refund_status in ('NOT_REQUIRED', 'EXPECTED', 'REQUESTED', 'PARTIALLY_CONFIRMED', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    reship_status              text not null check (reship_status in ('NOT_REQUIRED', 'PENDING', 'SHIPPED')),
    -- 취소: 출고지시 취소 확인을 기다리는 중이면 false(수량 미반영)
    cancel_applied             boolean not null default false,
    reason_code                text not null check (reason_code in ('CHANGE_OF_MIND', 'DEFECT', 'WRONG_ITEM', 'DAMAGED_IN_TRANSIT', 'DELIVERY_DELAY', 'OUT_OF_STOCK',
                                                                     'WRONG_ADDRESS', 'OTHER')),
    reason_note                text check (reason_note is null or length(reason_note) <= 500),
    fault_party                text not null check (fault_party in ('CUSTOMER', 'SELLER', 'CARRIER', 'UNKNOWN')),
    ship_before_return         boolean not null default false,
    return_carrier_code        text references carrier (code),
    return_tracking_no         text,
    -- 금액(부가세 포함 원). 환불 예정액 = 상품 환불 + 배송비 환불 − 고객 부담 반품 배송비(0 미만 없음). 교환 차액(+고객 추가 결제 / −환불)
    item_refund_amount         numeric(18, 0) not null default 0 check (item_refund_amount >= 0),
    shipping_refund_amount     numeric(18, 0) not null default 0 check (shipping_refund_amount >= 0),
    return_shipping_fee        numeric(18, 0) not null default 0 check (return_shipping_fee >= 0),
    price_difference           numeric(18, 0) not null default 0,
    expected_refund_amount     numeric(18, 0) not null default 0 check (expected_refund_amount >= 0),
    additional_payment_amount  numeric(18, 0) not null default 0 check (additional_payment_amount >= 0),
    -- 판매채널 처리 반영(채널에서 수집한 클레임의 승인·거부를 채널에도 반영)
    channel_action_status      text not null default 'NOT_REQUIRED'
        check (channel_action_status in ('NOT_REQUIRED', 'PENDING', 'SENT', 'MANUAL_REQUIRED', 'MANUAL_DONE', 'FAILED')),
    channel_action             text check (channel_action in ('APPROVE', 'REJECT')),
    channel_action_error       text,
    external_status            text,
    external_updated_at        timestamptz,
    decision_note              text check (decision_note is null or length(decision_note) <= 500),
    requested_at               timestamptz not null,
    decided_at                 timestamptz,
    decided_by                 uuid,
    completed_at               timestamptz,
    cancelled_at               timestamptz,
    created_by                 uuid,
    created_at                 timestamptz not null default now(),
    updated_at                 timestamptz not null default now(),
    version                    integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, claim_no),
    foreign key (workspace_id, order_id) references sales_order (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create unique index claim_external_uq on claim (channel_account_id, claim_type, external_claim_key) where external_claim_key is not null;
create index claim_list_idx on claim (workspace_id, created_at desc);
create index claim_order_idx on claim (order_id);

create table claim_item (
    id                         uuid primary key,
    workspace_id               uuid not null,
    claim_id                   uuid not null,
    line_no                    integer not null,
    order_item_id              uuid not null,
    quantity                   integer not null check (quantity >= 1),
    refund_amount              numeric(18, 0) not null default 0 check (refund_amount >= 0),
    -- 교환 대체 상품(같은 SKU 무상 교환 또는 다른 SKU)
    replacement_sku_id         uuid,
    replacement_quantity       integer check (replacement_quantity is null or replacement_quantity >= 1),
    replacement_order_item_id  uuid,
    -- 회수(구성품 기준 수량): 기대·입고·검수
    expected_return_qty        integer not null default 0 check (expected_return_qty >= 0),
    received_qty               integer not null default 0 check (received_qty >= 0),
    inspected_qty              integer not null default 0 check (inspected_qty >= 0),
    version                    integer not null default 1,
    unique (workspace_id, id),
    unique (claim_id, line_no),
    unique (claim_id, order_item_id),
    check (inspected_qty <= received_qty),
    check ((replacement_sku_id is null) = (replacement_quantity is null)),
    foreign key (workspace_id, claim_id) references claim (workspace_id, id),
    foreign key (workspace_id, order_item_id) references order_item (workspace_id, id)
);

create table claim_event (
    id              uuid primary key,
    workspace_id    uuid not null,
    claim_id        uuid not null,
    event_type      text not null check (event_type ~ '^[A-Z][A-Z0-9_]{2,60}$'),
    from_value      text,
    to_value        text,
    detail          jsonb not null default '{}',
    actor_type      text not null check (actor_type in ('USER', 'SYSTEM', 'INTEGRATION')),
    actor_member_id uuid,
    occurred_at     timestamptz not null default now(),
    foreign key (workspace_id, claim_id) references claim (workspace_id, id)
);
create index claim_event_idx on claim_event (claim_id, occurred_at desc);
create trigger claim_event_append_only before update or delete on claim_event for each row execute function forbid_mutation();

-- 판매채널 클레임 원본(개인정보 키 제거 후 보관, 같은 원본은 한 번만)
create table claim_raw (
    id                  uuid primary key,
    workspace_id        uuid not null,
    channel_account_id  uuid not null,
    connection_id       uuid,
    external_claim_key  text,
    claim_id            uuid,
    payload             jsonb not null,
    payload_hash        text not null,
    mapping_version     text not null,
    result              text not null check (result in ('CREATED', 'UPDATED', 'IGNORED_STALE', 'UNMATCHED', 'REJECTED')),
    result_detail       text,
    received_at         timestamptz not null default now(),
    unique (workspace_id, channel_account_id, payload_hash)
);
create trigger claim_raw_append_only before update or delete on claim_raw for each row execute function forbid_mutation();

-- 환불(클레임 1:N). 요청·확정은 별개, 확정액 합 ≤ 예정액. 멱등 키로 중복 요청 방지.
create table refund (
    id                  uuid primary key,
    workspace_id        uuid not null,
    claim_id            uuid not null,
    order_id            uuid not null,
    business_entity_id  uuid not null,
    channel_account_id  uuid not null,
    executor            text not null check (executor in ('CHANNEL', 'PG', 'MANUAL')),
    status              text not null check (status in ('REQUESTED', 'CONFIRMED', 'FAILED', 'CANCELLED')),
    requested_amount    numeric(18, 0) not null check (requested_amount > 0),
    confirmed_amount    numeric(18, 0) check (confirmed_amount is null or confirmed_amount >= 0),
    idempotency_key     text not null,
    external_ref        text check (external_ref is null or length(external_ref) <= 200),
    source              text not null check (source in ('MANUAL', 'API')),
    note                text check (note is null or length(note) <= 500),
    requested_by        uuid,
    requested_at        timestamptz not null default now(),
    confirmed_by        uuid,
    confirmed_at        timestamptz,
    failure_reason      text,
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, idempotency_key),
    check (status <> 'CONFIRMED' or (confirmed_amount is not null and confirmed_amount <= requested_amount and confirmed_at is not null)),
    foreign key (workspace_id, claim_id) references claim (workspace_id, id)
);
create index refund_claim_idx on refund (claim_id);

-- 반품 입고(실물 도착). 원주문·클레임과 맞지 않으면 미확인(판매가능 재고로 넣지 않음, T37).
create table return_receipt (
    id                  uuid primary key,
    workspace_id        uuid not null,
    receipt_no          text not null,
    node_id             uuid not null,
    business_entity_id  uuid not null,
    claim_id            uuid,
    order_id            uuid,
    identification      text not null check (identification in ('MATCHED', 'MISMATCH', 'UNIDENTIFIED')),
    status              text not null check (status in ('RECEIVED', 'INSPECTING', 'INSPECTED')),
    -- 3PL(외부 재고) 거점: 재고는 물류사 동기화가 기준, 여기 검수는 보완정보
    external_inventory  boolean not null,
    inspection_support  text not null check (inspection_support in ('INTERNAL', 'API', 'MANUAL_ONLY')),
    carrier_code        text references carrier (code),
    tracking_no         text,
    note                text check (note is null or length(note) <= 500),
    received_at         timestamptz not null,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, receipt_no),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create index return_receipt_list_idx on return_receipt (workspace_id, created_at desc);
create index return_receipt_claim_idx on return_receipt (claim_id);

create table return_receipt_line (
    id              uuid primary key,
    workspace_id    uuid not null,
    receipt_id      uuid not null,
    line_no         integer not null,
    sku_id          uuid not null,
    quantity        integer not null check (quantity >= 1),
    claim_item_id   uuid,
    matched         boolean not null,
    stock_id        uuid,
    inspected_qty   integer not null default 0,
    unique (workspace_id, id),
    unique (receipt_id, line_no),
    check (inspected_qty between 0 and quantity),
    foreign key (workspace_id, receipt_id) references return_receipt (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id)
);

-- 검수 판정(추가 전용). 판정 어휘 = CHG-010 품질 상태(검수대기 제외)
create table return_inspection (
    id                  uuid primary key,
    workspace_id        uuid not null,
    receipt_line_id     uuid not null,
    condition           text not null check (condition in ('SELLABLE', 'REPACK', 'DEFECTIVE', 'DAMAGED', 'EXPIRED', 'DISPOSAL_PENDING', 'UNDETERMINED')),
    quantity            integer not null check (quantity >= 1),
    reason              text check (reason is null or length(reason) <= 500),
    source              text not null check (source in ('INTERNAL', 'MANUAL', 'API')),
    applied_to_stock    boolean not null,
    to_stock_id         uuid,
    inspected_by        uuid,
    inspected_at        timestamptz not null default now(),
    foreign key (workspace_id, receipt_line_id) references return_receipt_line (workspace_id, id)
);
create index return_inspection_line_idx on return_inspection (receipt_line_id);
create trigger return_inspection_append_only before update or delete on return_inspection for each row execute function forbid_mutation();

-- 교환 대체 출고용 주문상품(0원, 원판매 매출과 분리)
alter table order_item add column item_kind text not null default 'ORIGINAL' check (item_kind in ('ORIGINAL', 'EXCHANGE_REPLACEMENT'));
alter table order_item add column claim_id uuid;

create table claim_number_counter (
    workspace_id    uuid not null references workspace (id),
    kind            text not null check (kind in ('CLAIM', 'RETURN_RECEIPT')),
    day             date not null,
    last_value      integer not null,
    primary key (workspace_id, kind, day)
);

do $$
declare
    t text;
begin
    foreach t in array array['claim', 'claim_item', 'claim_event', 'claim_raw', 'refund', 'return_receipt', 'return_receipt_line', 'return_inspection',
                             'claim_number_counter']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
