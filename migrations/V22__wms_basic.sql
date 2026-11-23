-- M4 O7 자체 WMS 기본: 로케이션 이동(적치), 웨이브·로케이션 피킹 계획, 스캔 피킹·검수 포장, 피킹한 버킷 그대로 출고 차감 (FR-INV-08, docs/01 §물류, DLG-147~152)
-- 고급 WMS(P1: 부자재 차감·라벨 출력·웨이브 최적화·모바일 전용 화면)는 범위 밖.

alter table inventory_transaction drop constraint inventory_transaction_txn_type_check;
alter table inventory_transaction add constraint inventory_transaction_txn_type_check
    check (txn_type in ('RECEIPT', 'QUALITY_CHANGE', 'RESERVE', 'ALLOCATE', 'PICK', 'CONSUME', 'RELEASE', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN',
                        'ASSEMBLY_OUT', 'ASSEMBLY_IN', 'DISPOSAL', 'EXTERNAL_SYNC', 'LOCATION_MOVE'));

alter table claim_number_counter drop constraint claim_number_counter_kind_check;
alter table claim_number_counter add constraint claim_number_counter_kind_check check (kind in ('CLAIM', 'RETURN_RECEIPT', 'WMS_WAVE'));

create table wms_wave (
    id            uuid primary key,
    workspace_id  uuid not null,
    wave_no       text not null,
    node_id       uuid not null,
    status        text not null check (status in ('PICKING', 'PICKED', 'CLOSED', 'CANCELLED')),
    created_by    uuid,
    created_at    timestamptz not null default now(),
    picked_at     timestamptz,
    version       integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, wave_no),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id)
);
create index wms_wave_list_idx on wms_wave (workspace_id, created_at desc);

alter table fulfillment_order add column wave_id uuid;
alter table fulfillment_order add column packed_at timestamptz;
alter table fulfillment_order add column packed_by uuid;

-- 피킹 작업 = (웨이브, 출고지시, 확보, 재고 버킷). 버킷 단위로 계획해 같은 실물을 두 웨이브가 계획하지 않게 한다.
create table wms_pick_task (
    id              uuid primary key,
    workspace_id    uuid not null,
    wave_id         uuid not null,
    fo_id           uuid not null,
    reservation_id  uuid not null,
    sku_id          uuid not null,
    stock_id        uuid not null,
    location_id     uuid,
    location_code   text,
    lot_id          uuid,
    quantity        integer not null check (quantity >= 1),
    picked_qty      integer not null default 0,
    status          text not null check (status in ('OPEN', 'PICKED', 'SHORT', 'CANCELLED')),
    short_reason    text check (short_reason is null or length(short_reason) <= 500),
    sort_key        text not null,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    version         integer not null default 1,
    unique (workspace_id, id),
    check (picked_qty between 0 and quantity),
    foreign key (workspace_id, wave_id) references wms_wave (workspace_id, id),
    foreign key (workspace_id, fo_id) references fulfillment_order (workspace_id, id),
    foreign key (workspace_id, stock_id) references inventory_stock (workspace_id, id)
);
create index wms_pick_task_wave_idx on wms_pick_task (wave_id, sort_key);
create index wms_pick_task_stock_idx on wms_pick_task (stock_id) where status in ('OPEN', 'PICKED');
create index wms_pick_task_fo_idx on wms_pick_task (fo_id);

-- 스캔 기록(추가 전용). 잘못된 스캔도 남긴다(차단 근거·작업 품질).
create table wms_scan (
    id              uuid primary key,
    workspace_id    uuid not null,
    kind            text not null check (kind in ('PICK', 'PACK')),
    wave_id         uuid,
    fo_id           uuid,
    location_code   text,
    barcode         text not null check (length(barcode) between 1 and 80),
    quantity        integer not null,
    result          text not null check (result in ('OK', 'REJECTED')),
    reason          text,
    actor_member_id uuid,
    scanned_at      timestamptz not null default now()
);
create index wms_scan_idx on wms_scan (workspace_id, scanned_at desc);
create trigger wms_scan_append_only before update or delete on wms_scan for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['wms_wave', 'wms_pick_task', 'wms_scan']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
