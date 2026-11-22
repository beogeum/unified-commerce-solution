-- M4 O5 출고 확정·송장·배송 추적·판매채널 발송처리 (docs/04 §1·§2·§9, docs/03 shipment, DLG-129~136)
-- 출고 확정 = 출고지시(FO) 단위. 송장(shipment) 은 FO 와 1:N(박스 여러 개). 배송 단계는 송장 축으로 따로 관리(송장 발급 ≠ 배송완료).

-- 택배사 표준 코드(내부). 판매채널·물류사 코드와의 대응은 각 커넥터가 맡는다(실계정 확인 전에는 모의 연동만).
create table carrier (
    code        text primary key check (code ~ '^[A-Z][A-Z0-9_]{1,30}$'),
    name_ko     text not null,
    active      boolean not null default true,
    sort_order  integer not null
);
insert into carrier (code, name_ko, sort_order) values
 ('CJ',      'CJ대한통운',       10),
 ('LOTTE',   '롯데택배',         20),
 ('HANJIN',  '한진택배',         30),
 ('EPOST',   '우체국택배',       40),
 ('LOGEN',   '로젠택배',         50),
 ('KDEXP',   '경동택배',         60),
 ('DAESIN',  '대신택배',         70),
 ('ILYANG',  '일양로지스',       80),
 ('CVSNET',  'GS Postbox 택배',  90),
 ('CU_POST', 'CU 편의점택배',   100),
 ('DIRECT',  '직접 배송',       900),
 ('OTHER',   '기타',            990);

-- 송장 미수신 SLA(docs/04 §9): 물류사 접수 후 이 시간 안에 송장이 없으면 업무함
alter table fulfillment_node add column tracking_sla_hours integer not null default 24 check (tracking_sla_hours between 1 and 720);

alter table fulfillment_order add column shipped_at timestamptz;
alter table fulfillment_order add column ship_source text check (ship_source in ('INTERNAL', 'API', 'FILE', 'MANUAL'));

-- 주문 배송 축(파생): 출고 전 · 일부 출고 · 출고 완료 · 배송완료
alter table sales_order add column shipping_status text not null default 'NONE'
    check (shipping_status in ('NONE', 'PARTIALLY_SHIPPED', 'SHIPPED', 'DELIVERED'));
alter table sales_order add column delivered_at timestamptz;

create table shipment (
    id                         uuid primary key,
    workspace_id               uuid not null,
    shipment_no                text not null,
    fo_id                      uuid not null,
    order_id                   uuid not null,
    business_entity_id         uuid not null,
    channel_account_id         uuid not null,
    node_id                    uuid not null,
    parcel_no                  integer not null check (parcel_no >= 1),
    carrier_code               text not null references carrier (code),
    tracking_no                text not null check (length(tracking_no) between 4 and 40),
    -- 비교용(공백·하이픈 제거, 대문자)
    tracking_key               text not null,
    status                     text not null check (status in ('LABEL_CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'EXCEPTION')),
    status_at                  timestamptz,
    shipped_at                 timestamptz not null,
    delivered_at               timestamptz,
    source                     text not null check (source in ('INTERNAL', 'API', 'FILE', 'MANUAL')),
    -- 판매채널 발송처리(송장 전송)
    channel_dispatch_status    text not null check (channel_dispatch_status in ('PENDING', 'SENT', 'MANUAL_REQUIRED', 'MANUAL_DONE', 'FAILED', 'NOT_REQUIRED')),
    channel_dispatch_mode      text check (channel_dispatch_mode in ('API', 'MANUAL')),
    channel_dispatch_attempts  integer not null default 0,
    channel_dispatch_ref       text,
    channel_dispatch_error     text,
    channel_dispatched_at      timestamptz,
    created_by                 uuid,
    created_at                 timestamptz not null default now(),
    updated_at                 timestamptz not null default now(),
    version                    integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, shipment_no),
    unique (fo_id, parcel_no),
    unique (fo_id, carrier_code, tracking_key),
    foreign key (workspace_id, fo_id) references fulfillment_order (workspace_id, id),
    foreign key (workspace_id, order_id) references sales_order (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create index shipment_list_idx on shipment (workspace_id, created_at desc);
create index shipment_tracking_idx on shipment (workspace_id, carrier_code, tracking_key);
create index shipment_order_idx on shipment (order_id);

create table shipment_item (
    workspace_id    uuid not null,
    shipment_id     uuid not null,
    order_item_id   uuid not null,
    sku_id          uuid not null,
    quantity        integer not null check (quantity >= 1),
    primary key (shipment_id, order_item_id, sku_id),
    foreign key (workspace_id, shipment_id) references shipment (workspace_id, id),
    foreign key (workspace_id, order_item_id) references order_item (workspace_id, id)
);
create trigger shipment_item_append_only before update or delete on shipment_item for each row execute function forbid_mutation();

-- 배송 이벤트(추가 전용). 같은 이벤트 반복 수신은 dedupe_key 로 한 번만(T10). 늦게 온 이벤트도 기록하되 현재 상태를 되돌리지 않는다.
create table shipment_event (
    id               uuid primary key,
    workspace_id     uuid not null,
    shipment_id      uuid not null,
    status           text not null check (status in ('LABEL_CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'EXCEPTION')),
    occurred_at      timestamptz not null,
    description      text check (description is null or length(description) <= 500),
    location         text check (location is null or length(location) <= 200),
    external_code    text,
    source           text not null check (source in ('INTERNAL', 'API', 'FILE', 'MANUAL')),
    applied          boolean not null,
    dedupe_key       text not null,
    actor_member_id  uuid,
    received_at      timestamptz not null default now(),
    unique (shipment_id, dedupe_key),
    foreign key (workspace_id, shipment_id) references shipment (workspace_id, id)
);
create index shipment_event_idx on shipment_event (shipment_id, occurred_at desc);
create trigger shipment_event_append_only before update or delete on shipment_event for each row execute function forbid_mutation();

-- 물류 연동 원본(출고상태·송장·송장 파일 행). 개인정보 키는 저장 전에 제거한다. 같은 원본(해시) 반복은 한 번만 처리(실패분은 재시도).
create table logistics_raw (
    id              uuid primary key,
    workspace_id    uuid not null,
    connection_id   uuid,
    file_id         uuid,
    kind            text not null check (kind in ('OUTBOUND_STATUS', 'TRACKING', 'SHIPMENT_FILE_ROW', 'CHANNEL_DISPATCH')),
    external_key    text,
    payload         jsonb not null,
    payload_hash    text not null,
    mapping_version text not null,
    result          text not null check (result in ('APPLIED', 'DUPLICATE', 'IGNORED', 'UNMATCHED', 'FAILED')),
    result_detail   text,
    received_at     timestamptz not null default now()
);
-- 반영 실패(FAILED)는 같은 원본이 다시 오면 재시도하므로 중복 판정에서 뺀다
create unique index logistics_raw_dedupe_idx on logistics_raw (workspace_id, kind, payload_hash) where result <> 'FAILED';
create index logistics_raw_key_idx on logistics_raw (workspace_id, external_key);
create trigger logistics_raw_append_only before update or delete on logistics_raw for each row execute function forbid_mutation();

alter table stored_file drop constraint stored_file_purpose_check;
alter table stored_file add constraint stored_file_purpose_check
    check (purpose in ('GENERIC_IMPORT', 'SETTLEMENT_SOURCE', 'VAT_SOURCE', 'CAPABILITY_EVIDENCE', 'EXPORT', 'EXTERNAL_INVENTORY', 'SHIPMENT_IMPORT'));

do $$
declare
    t text;
begin
    foreach t in array array['shipment', 'shipment_item', 'shipment_event', 'logistics_raw']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
