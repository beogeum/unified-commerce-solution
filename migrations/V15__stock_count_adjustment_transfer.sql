-- M3 I5 재고 실사(블라인드)·재실사 기준·차이 승인·재고 조정·거점 이동 (CONF-012~014, DEC-F21, DLG-043, DLG-034, T40)

-- 재실사 기준(CONF-014): 사업자 기본값 + 거점·Zone 재정의. 범위마다 버전을 쌓고(append-only) 실사는 생성 시점 버전에 고정(DEC-F21)
create table stock_count_policy (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    node_id             uuid,
    zone                text check (zone is null or length(zone) between 1 and 40),
    version             integer not null check (version >= 1),
    removed             boolean not null default false,
    qty_threshold       integer check (qty_threshold is null or qty_threshold >= 0),
    rate_threshold_pct  numeric(7, 2) check (rate_threshold_pct is null or rate_threshold_pct >= 0),
    cost_threshold      numeric(18, 0) check (cost_threshold is null or cost_threshold >= 0),
    combine             text check (combine in ('ANY', 'ALL')),
    on_exceed           text check (on_exceed in ('RECOUNT_REQUIRED', 'RECOUNT_RECOMMENDED')),
    recount_assignee    text check (recount_assignee in ('DIFFERENT_COUNTER', 'SAME_ALLOWED')),
    created_by          uuid not null,
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id),
    check (zone is null or node_id is not null),
    -- 조건이 하나도 없는 기준은 둘 수 없다(빈칸 = 그 조건 미사용)
    check (removed or ((qty_threshold is not null or rate_threshold_pct is not null or cost_threshold is not null)
                       and combine is not null and on_exceed is not null and recount_assignee is not null)),
    check (not removed or node_id is not null)
);
create unique index stock_count_policy_scope_version_uq on stock_count_policy (business_entity_id,
    coalesce(node_id, '00000000-0000-0000-0000-000000000000'), coalesce(zone, ''), version);
create trigger stock_count_policy_append_only before update or delete on stock_count_policy for each row execute function forbid_mutation();

-- 재고 조정 승인 기준(DLG-043 기본 원가 영향 100,000원 이상). 사업자별 재정의
create table inventory_adjustment_policy (
    workspace_id        uuid not null,
    business_entity_id  uuid primary key,
    approval_threshold  numeric(18, 0) not null check (approval_threshold >= 0),
    updated_by          uuid not null,
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);

-- 실사
create table stock_count (
    id                  uuid primary key,
    workspace_id        uuid not null,
    count_no            text not null,
    business_entity_id  uuid not null,
    node_id             uuid not null,
    scope               text not null check (scope in ('ALL', 'ZONE', 'SKU')),
    zone                text,
    sku_ids             uuid[],
    blind               boolean not null,
    policy_id           uuid not null,
    status              text not null default 'COUNTING' check (status in ('COUNTING', 'REVIEW', 'COMPLETED', 'CANCELLED')),
    round               integer not null default 1,
    snapshot_at         timestamptz not null,
    note                text check (note is null or length(note) <= 500),
    requested_by        uuid not null,
    submitted_at        timestamptz,
    completed_at        timestamptz,
    cancelled_at        timestamptz,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, count_no),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id),
    foreign key (workspace_id, policy_id) references stock_count_policy (workspace_id, id),
    check ((scope = 'ZONE') = (zone is not null)),
    check ((scope = 'SKU') = (sku_ids is not null and cardinality(sku_ids) > 0))
);
create index stock_count_active_idx on stock_count (workspace_id, node_id, business_entity_id) where status = 'COUNTING';
create index stock_count_list_idx on stock_count (workspace_id, created_at desc);

create table stock_count_counter (
    workspace_id    uuid not null,
    count_id        uuid not null,
    member_id       uuid not null,
    primary key (count_id, member_id),
    foreign key (workspace_id, count_id) references stock_count (workspace_id, id)
);

create table stock_count_line (
    id                  uuid primary key,
    workspace_id        uuid not null,
    count_id            uuid not null,
    line_no             integer not null,
    sku_id              uuid not null,
    location_id         uuid,
    lot_id              uuid,
    quality_status      text not null,
    hold_reason         text,
    -- 이 행에 적용할 재실사 기준 버전(Zone 재정의가 있으면 그 버전). 생성 시점에 고정(DEC-F21)
    policy_id           uuid not null,
    -- 스냅샷 시점(재실사면 재실사 시작 시점) 시스템 수량. 실사 중 발견된 버킷은 0
    system_qty          integer not null check (system_qty >= 0),
    found_in_count      boolean not null default false,
    counted_qty         integer check (counted_qty is null or counted_qty >= 0),
    counted_by          uuid,
    counted_at          timestamptz,
    first_counted_by    uuid,
    counted_round       integer,
    recount_count       integer not null default 0,
    status              text not null default 'COUNTING'
                        check (status in ('COUNTING', 'RECOUNT', 'MATCHED', 'PENDING_APPROVAL', 'RECOUNT_REQUIRED', 'APPROVED', 'REJECTED')),
    diff                integer,
    diff_rate_pct       numeric(9, 2),
    unit_cost           numeric(18, 0),
    cost_impact         numeric(18, 0),
    exceeds_policy      boolean,
    recount_recommended boolean not null default false,
    decided_by          uuid,
    decided_at          timestamptz,
    decision_reason     text check (decision_reason is null or length(decision_reason) <= 500),
    unique (workspace_id, id),
    unique (count_id, line_no),
    foreign key (workspace_id, count_id) references stock_count (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    foreign key (workspace_id, location_id) references node_location (workspace_id, id),
    foreign key (workspace_id, lot_id) references inventory_lot (workspace_id, id),
    foreign key (workspace_id, policy_id) references stock_count_policy (workspace_id, id),
    -- 자기 승인 금지(CONF-013)의 DB 방어: 카운트한 사람은 그 행을 결정할 수 없다
    check (decided_by is null or (decided_by is distinct from counted_by and decided_by is distinct from first_counted_by))
);
create unique index stock_count_line_bucket_uq on stock_count_line (count_id, sku_id,
    coalesce(location_id, '00000000-0000-0000-0000-000000000000'), coalesce(lot_id, '00000000-0000-0000-0000-000000000000'),
    quality_status, coalesce(hold_reason, ''));

-- 카운트 이력(회차별, append-only)
create table stock_count_entry (
    id              uuid primary key,
    workspace_id    uuid not null,
    line_id         uuid not null,
    round           integer not null,
    system_qty      integer not null,
    counted_qty     integer not null check (counted_qty >= 0),
    counted_by      uuid not null,
    counted_at      timestamptz not null default now(),
    foreign key (workspace_id, line_id) references stock_count_line (workspace_id, id)
);
create index stock_count_entry_line_idx on stock_count_entry (line_id, round);
create trigger stock_count_entry_append_only before update or delete on stock_count_entry for each row execute function forbid_mutation();

-- 거점 간 이동(같은 소유 사업자 안에서만, DLG-034). 이동 중 수량은 이동 기록에 남아 자산으로 계속 집계한다
create table inventory_transfer (
    id                  uuid primary key,
    workspace_id        uuid not null,
    transfer_no         text not null,
    business_entity_id  uuid not null,
    from_node_id        uuid not null,
    to_node_id          uuid not null,
    status              text not null default 'IN_TRANSIT' check (status in ('IN_TRANSIT', 'RECEIVED', 'RECEIVED_WITH_DISCREPANCY')),
    note                text check (note is null or length(note) <= 500),
    requested_by        uuid not null,
    shipped_at          timestamptz not null default now(),
    received_by         uuid,
    received_at         timestamptz,
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, transfer_no),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, from_node_id) references fulfillment_node (workspace_id, id),
    foreign key (workspace_id, to_node_id) references fulfillment_node (workspace_id, id),
    check (from_node_id <> to_node_id)
);
create index inventory_transfer_list_idx on inventory_transfer (workspace_id, shipped_at desc);

create table inventory_transfer_line (
    workspace_id    uuid not null,
    transfer_id     uuid not null,
    line_no         integer not null,
    sku_id          uuid not null,
    lot_id          uuid,
    quantity        integer not null check (quantity >= 1),
    received_qty    integer check (received_qty is null or (received_qty >= 0 and received_qty <= quantity)),
    received_quality text,
    primary key (transfer_id, line_no),
    foreign key (workspace_id, transfer_id) references inventory_transfer (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    foreign key (workspace_id, lot_id) references inventory_lot (workspace_id, id)
);

do $$
declare
    t text;
begin
    foreach t in array array['stock_count_policy', 'inventory_adjustment_policy', 'stock_count', 'stock_count_counter', 'stock_count_line',
                             'stock_count_entry', 'inventory_transfer', 'inventory_transfer_line']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
