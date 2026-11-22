-- M3 I1~I3 출고거점·재고 버킷·원장·예약 (docs/03 §4, docs/04 §4, CHG-009, T05~T07)
-- 재고 소유는 사업자 단위. 재고 버킷 = (소유 사업자, SKU, 거점, 로케이션, LOT, 품질, 보류사유)의 실물 수량.
-- 확보수량(committed)은 예약 활성 단계(RESERVED/ALLOCATED/PICKING) 하나로만 집계(불변조건 4). 모든 변화는 원장(append-only)에 남는다.

insert into permission (code, category, name_ko, sensitive) values
 ('inventory.read',    'INVENTORY', '재고 조회', false),
 ('inventory.operate', 'INVENTORY', '입고·품질 변경·예약·출고 처리', false);
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'inventory.read' from role r where r.is_system
on conflict do nothing;
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'inventory.operate' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'OPS_ADMIN', 'LOGISTICS_MANAGER', 'WAREHOUSE_WORKER')
on conflict do nothing;

-- I1 출고거점: 물류사업자 → 센터(거점). 3PL 거점은 사업자별 화주계정으로 연결(T05).
create table fulfillment_node (
    id                       uuid primary key,
    workspace_id             uuid not null references workspace (id),
    code                     text not null check (code ~ '^[A-Z0-9][A-Z0-9_-]{0,39}$'),
    name                     text not null check (length(btrim(name)) between 1 and 100),
    logistics_provider_code  text not null references logistics_provider (code),
    -- 재고 원본: INTERNAL(우리 원장이 기준) / EXTERNAL(3PL·플랫폼 제공값이 기준, I4)
    inventory_source         text not null check (inventory_source in ('INTERNAL', 'EXTERNAL')),
    cutoff_time              time,
    timezone                 text not null default 'Asia/Seoul',
    address_note             text check (address_note is null or length(address_note) <= 300),
    status                   text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    version                  integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, code)
);

-- 3PL 거점에 화주계정 연결(사업자마다 별도 화주계정). 자체 WMS 는 연결 없이 여러 사업자가 사용
create table fulfillment_node_shipper (
    workspace_id        uuid not null,
    node_id             uuid not null,
    shipper_account_id  uuid not null,
    business_entity_id  uuid not null,
    created_at          timestamptz not null default now(),
    primary key (node_id, shipper_account_id),
    unique (node_id, business_entity_id),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id),
    foreign key (workspace_id, shipper_account_id) references shipper_account (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);

create table node_location (
    id            uuid primary key,
    workspace_id  uuid not null,
    node_id       uuid not null,
    code          text not null check (code ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$'),
    zone          text check (zone is null or length(zone) <= 40),
    status        text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    unique (workspace_id, id),
    unique (node_id, code),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id)
);

create table inventory_lot (
    id                uuid primary key,
    workspace_id      uuid not null,
    sku_id            uuid not null,
    lot_code          text not null check (lot_code ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,39}$'),
    manufactured_on   date,
    expires_on        date,
    created_at        timestamptz not null default now(),
    unique (workspace_id, id),
    unique (sku_id, lot_code),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    check (expires_on is null or manufactured_on is null or expires_on >= manufactured_on)
);

-- I2 재고 버킷(실물)
create table inventory_stock (
    id                  uuid primary key,
    workspace_id        uuid not null,
    owner_business_entity_id uuid not null,
    sku_id              uuid not null,
    node_id             uuid not null,
    location_id         uuid,
    lot_id              uuid,
    -- 품질(CHG-010, docs/13 §5)
    quality_status      text not null check (quality_status in ('SELLABLE', 'PENDING_INSPECTION', 'REPACK', 'DEFECTIVE', 'DAMAGED', 'EXPIRED', 'DISPOSAL_PENDING', 'UNDETERMINED')),
    hold_reason         text check (hold_reason is null or hold_reason ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    on_hand             integer not null check (on_hand >= 0),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, owner_business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id),
    foreign key (workspace_id, location_id) references node_location (workspace_id, id),
    foreign key (workspace_id, lot_id) references inventory_lot (workspace_id, id)
);
create unique index inventory_stock_bucket_uq on inventory_stock (owner_business_entity_id, sku_id, node_id,
    coalesce(location_id, '00000000-0000-0000-0000-000000000000'), coalesce(lot_id, '00000000-0000-0000-0000-000000000000'),
    quality_status, coalesce(hold_reason, ''));
create index inventory_stock_pos_idx on inventory_stock (workspace_id, owner_business_entity_id, sku_id, node_id);

-- 판매 가능 재고·확보수량 집계(잠금 단위). (소유 사업자, SKU, 거점)당 1행 — 동시 예약은 이 행을 잠가 직렬화(T07)
create table inventory_position (
    id                  uuid primary key,
    workspace_id        uuid not null,
    owner_business_entity_id uuid not null,
    sku_id              uuid not null,
    node_id             uuid not null,
    sellable_on_hand    integer not null default 0 check (sellable_on_hand >= 0),
    committed           integer not null default 0 check (committed >= 0),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (owner_business_entity_id, sku_id, node_id),
    foreign key (workspace_id, owner_business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id),
    -- 확보수량은 판매 가능 실물을 넘을 수 없다(과확보 방지 — DB 최종 방어)
    check (committed <= sellable_on_hand)
);

-- I3 예약: 한 예약은 한 시점에 한 단계(불변조건 4). 수요(주문상품 등)는 M4 전까지 참조 문자열로
create table inventory_reservation (
    id                  uuid primary key,
    workspace_id        uuid not null,
    position_id         uuid not null,
    owner_business_entity_id uuid not null,
    sku_id              uuid not null,
    node_id             uuid not null,
    quantity            integer not null check (quantity >= 1),
    state               text not null check (state in ('RESERVED', 'ALLOCATED', 'PICKING', 'CONSUMED', 'RELEASED')),
    demand_type         text not null check (demand_type ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    demand_ref          text not null check (length(demand_ref) between 1 and 200),
    group_id            uuid not null,
    expires_at          timestamptz,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, position_id) references inventory_position (workspace_id, id)
);
create index inventory_reservation_demand_idx on inventory_reservation (workspace_id, demand_type, demand_ref);
create index inventory_reservation_active_idx on inventory_reservation (position_id) where state in ('RESERVED', 'ALLOCATED', 'PICKING');
-- 같은 수요(예: 주문상품)·SKU 의 활성 예약은 하나(중복 확보 방지)
create unique index inventory_reservation_demand_active_uq on inventory_reservation (workspace_id, demand_type, demand_ref, sku_id)
    where state in ('RESERVED', 'ALLOCATED', 'PICKING');

-- 원장(append-only). 실물 변화(on_hand_delta)와 확보 변화(committed_delta)를 따로 기록
create table inventory_transaction (
    id                  uuid primary key,
    workspace_id        uuid not null,
    owner_business_entity_id uuid not null,
    sku_id              uuid not null,
    node_id             uuid not null,
    stock_id            uuid,
    position_id         uuid not null,
    reservation_id      uuid,
    txn_type            text not null check (txn_type in ('RECEIPT', 'QUALITY_CHANGE', 'RESERVE', 'ALLOCATE', 'PICK', 'CONSUME', 'RELEASE',
                                                          'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN', 'ASSEMBLY_OUT', 'ASSEMBLY_IN', 'DISPOSAL')),
    quality_status      text,
    on_hand_delta       integer not null default 0,
    sellable_delta      integer not null default 0,
    committed_delta     integer not null default 0,
    reservation_state_from text,
    reservation_state_to   text,
    reference_type      text,
    reference_id        text,
    reason              text check (reason is null or length(reason) <= 500),
    actor_member_id     uuid,
    idempotency_key     text,
    occurred_at         timestamptz not null default now(),
    foreign key (workspace_id, position_id) references inventory_position (workspace_id, id),
    foreign key (workspace_id, stock_id) references inventory_stock (workspace_id, id)
);
create index inventory_transaction_lookup_idx on inventory_transaction (workspace_id, owner_business_entity_id, sku_id, node_id, occurred_at desc);
create unique index inventory_transaction_idem_uq on inventory_transaction (workspace_id, idempotency_key) where idempotency_key is not null;
create trigger inventory_transaction_append_only before update or delete on inventory_transaction for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['fulfillment_node', 'fulfillment_node_shipper', 'node_location', 'inventory_lot', 'inventory_stock',
                             'inventory_position', 'inventory_reservation', 'inventory_transaction']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
