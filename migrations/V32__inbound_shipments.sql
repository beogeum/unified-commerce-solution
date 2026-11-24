-- 단계별 입고(docs/04 §8 입고: EXPECTED→ARRIVED→INSPECTING→PUTAWAY→COMPLETED|DISCREPANCY, 발주 1:N 입고, DLG-220~225).
-- 도착 수량은 실물 확인 기록일 뿐 재고 원장에 넣지 않는다. 검수 확정 시 정상 수량만 재고 원장 입고(검수 대기·적치 대기 보류),
-- 적치에서 판매가능으로 바뀐다. 불량·초과 도착은 재고에 넣지 않고 반송 업무로.
alter table claim_number_counter drop constraint claim_number_counter_kind_check;
alter table claim_number_counter add constraint claim_number_counter_kind_check
    check (kind in ('CLAIM', 'RETURN_RECEIPT', 'WMS_WAVE', 'PURCHASE_ORDER', 'PO_RECEIPT', 'MANUAL_ORDER', 'CS_INQUIRY', 'INBOUND_SHIPMENT'));

create table inbound_shipment (
    id                      uuid primary key,
    workspace_id            uuid not null,
    shipment_no             text not null,
    po_id                   uuid not null,
    business_entity_id      uuid not null,
    node_id                 uuid not null,
    status                  text not null default 'EXPECTED'
        check (status in ('EXPECTED', 'ARRIVED', 'INSPECTING', 'PUTAWAY', 'COMPLETED', 'DISCREPANCY', 'CANCELLED')),
    expected_on             date,
    carrier                 text check (carrier is null or length(carrier) <= 100),
    tracking_no             text check (tracking_no is null or length(tracking_no) <= 100),
    note                    text check (note is null or length(note) <= 500),
    receipt_id              uuid,
    has_discrepancy         boolean not null default false,
    arrived_at              timestamptz,
    inspected_at            timestamptz,
    completed_at            timestamptz,
    cancel_reason           text check (cancel_reason is null or length(cancel_reason) <= 500),
    created_by              uuid,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    version                 integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, shipment_no),
    foreign key (workspace_id, po_id) references purchase_order (workspace_id, id),
    foreign key (workspace_id, receipt_id) references po_receipt (workspace_id, id),
    check (status not in ('PUTAWAY', 'COMPLETED', 'DISCREPANCY') or inspected_at is not null),
    check (status <> 'CANCELLED' or cancel_reason is not null)
);
create index inbound_shipment_po_idx on inbound_shipment (po_id);
create index inbound_shipment_status_idx on inbound_shipment (workspace_id, status, expected_on);

create table inbound_shipment_line (
    id                      uuid primary key,
    workspace_id            uuid not null,
    shipment_id             uuid not null,
    po_line_id              uuid not null,
    sku_id                  uuid not null,
    expected_qty            integer not null check (expected_qty >= 1),
    arrived_qty             integer check (arrived_qty is null or arrived_qty >= 0),
    good_qty                integer check (good_qty is null or good_qty >= 0),
    rejected_qty            integer check (rejected_qty is null or rejected_qty >= 0),
    over_qty                integer check (over_qty is null or over_qty >= 0),
    reject_reason           text check (reject_reason is null or length(reject_reason) <= 300),
    staging_stock_id        uuid,
    putaway_qty             integer not null default 0 check (putaway_qty >= 0),
    unique (workspace_id, id),
    unique (shipment_id, po_line_id),
    foreign key (workspace_id, shipment_id) references inbound_shipment (workspace_id, id),
    foreign key (workspace_id, po_line_id) references purchase_order_line (workspace_id, id),
    check (good_qty is null or arrived_qty is not null and good_qty + rejected_qty + over_qty = arrived_qty),
    check (putaway_qty <= coalesce(good_qty, 0))
);

-- 상태 전환 원장(append-only)
create table inbound_shipment_event (
    id                      uuid primary key,
    workspace_id            uuid not null,
    shipment_id             uuid not null,
    from_status             text,
    to_status               text not null,
    actor_member_id         uuid,
    detail                  jsonb not null default '{}'::jsonb,
    occurred_at             timestamptz not null default now(),
    foreign key (workspace_id, shipment_id) references inbound_shipment (workspace_id, id)
);
create index inbound_shipment_event_idx on inbound_shipment_event (shipment_id, occurred_at);
create trigger inbound_shipment_event_append_only before update or delete on inbound_shipment_event for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['inbound_shipment', 'inbound_shipment_line', 'inbound_shipment_event']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
