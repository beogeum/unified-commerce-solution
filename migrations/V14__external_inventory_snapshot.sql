-- M3 I4 외부(3PL·플랫폼) 재고 스냅샷·수량 의미·워터마크·보수적 판매 제한 (FR-INV-04, ARCH-005, DLG-040, T08·T09·T38)
-- 외부 원본(스냅샷·행)은 그대로 보관하고, 내부 재고(미러 버킷·집계)는 원장 기록(EXTERNAL_SYNC)으로만 맞춘다.

-- 거점의 외부 재고 해석 규칙. 수량 의미는 실제 계정으로 확인되기 전까지 UNKNOWN(= 항상 보수적 판매 제한)
alter table fulfillment_node
    add column quantity_semantic text not null default 'UNKNOWN'
        check (quantity_semantic in ('UNKNOWN', 'ON_HAND', 'AVAILABLE', 'POST_OUTBOUND_ESTIMATE')),
    add column quantity_semantic_evidence text check (quantity_semantic_evidence is null or length(quantity_semantic_evidence) <= 500),
    add column quantity_semantic_verified_by uuid,
    add column quantity_semantic_verified_at timestamptz,
    -- 판매 제한 중 추가로 판매에서 빼 둘 수량(품절 버퍼, DLG-040 기본 0)
    add column restricted_sell_buffer integer not null default 0 check (restricted_sell_buffer >= 0),
    -- 이 시간보다 오래된 스냅샷은 최신으로 보지 않는다(분)
    add column snapshot_max_age_minutes integer not null default 60 check (snapshot_max_age_minutes between 5 and 10080),
    add check (quantity_semantic = 'UNKNOWN' or quantity_semantic_evidence is not null);

-- 확보의 외부 반영 추적: 3PL 이 출고지시를 접수한 시각(워터마크와 비교), 출고 확정 시각
alter table inventory_reservation
    add column external_ack_at timestamptz,
    add column external_ref text check (external_ref is null or length(external_ref) <= 200),
    add column consumed_at timestamptz;
create index inventory_reservation_consumed_idx on inventory_reservation (position_id, consumed_at) where state = 'CONSUMED';

-- 집계의 외부 동기화 상태·판매 제한
alter table inventory_position
    add column external_snapshot_id uuid,
    add column external_as_of timestamptz,
    add column restricted boolean not null default false,
    add column restriction_reason text check (restriction_reason is null or restriction_reason ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    add column restricted_hold integer not null default 0 check (restricted_hold >= 0),
    add check (restricted or restricted_hold = 0);

alter table inventory_transaction drop constraint inventory_transaction_txn_type_check;
alter table inventory_transaction add constraint inventory_transaction_txn_type_check
    check (txn_type in ('RECEIPT', 'QUALITY_CHANGE', 'RESERVE', 'ALLOCATE', 'PICK', 'CONSUME', 'RELEASE', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN',
                        'ASSEMBLY_OUT', 'ASSEMBLY_IN', 'DISPOSAL', 'EXTERNAL_SYNC'));

-- 외부 재고 스냅샷(원본 보관). 같은 (거점, 소유 사업자, 원천 키)는 한 번만 받는다(재수신 멱등)
create table external_inventory_snapshot (
    id                  uuid primary key,
    workspace_id        uuid not null,
    node_id             uuid not null,
    owner_business_entity_id uuid not null,
    shipper_account_id  uuid,
    connection_id       uuid,
    source              text not null check (source in ('API', 'FILE')),
    source_key          text not null check (length(source_key) between 1 and 200),
    -- 외부 기준시각(워터마크). 없으면 반영 여부를 판단할 수 없어 보수적으로 처리
    as_of               timestamptz,
    fetched_at          timestamptz not null,
    complete            boolean not null,
    quantity_semantic   text not null,
    mapping_version     text not null,
    capability_version  integer,
    raw_payload_ref     text,
    raw_payload         jsonb not null,
    file_id             uuid,
    status              text not null check (status in ('APPLIED', 'APPLIED_RESTRICTED', 'IGNORED_OUT_OF_ORDER')),
    line_count          integer not null check (line_count >= 0),
    issue_count         integer not null default 0,
    received_by         uuid,
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    unique (node_id, owner_business_entity_id, source, source_key),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id),
    foreign key (workspace_id, owner_business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, shipper_account_id) references shipper_account (workspace_id, id),
    foreign key (workspace_id, file_id) references stored_file (workspace_id, id)
);
create index external_inventory_snapshot_list_idx on external_inventory_snapshot (workspace_id, node_id, owner_business_entity_id, created_at desc);
create trigger external_inventory_snapshot_append_only before update or delete on external_inventory_snapshot for each row execute function forbid_mutation();

-- 스냅샷 행: 외부 값(원본)과 해석 결과(매핑 SKU·판매가능 여부·적용 결과)를 함께 남긴다
create table external_inventory_line (
    workspace_id        uuid not null,
    snapshot_id         uuid not null,
    line_no             integer not null check (line_no >= 1),
    external_sku_code   text not null check (length(external_sku_code) between 1 and 100),
    sku_id              uuid,
    lot_code            text,
    expires_on          date,
    location_code       text,
    external_status     text,
    sellable            boolean not null,
    quantity            integer not null check (quantity >= 0),
    issue               text check (issue is null or issue ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    primary key (snapshot_id, line_no),
    foreign key (workspace_id, snapshot_id) references external_inventory_snapshot (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id)
);
create trigger external_inventory_line_append_only before update or delete on external_inventory_line for each row execute function forbid_mutation();

-- 스냅샷이 SKU 별로 낸 결론(왜 이 수량이 되었는지 근거). 적용 시점에 한 번 쓰고 바꾸지 않는다
create table external_inventory_result (
    workspace_id        uuid not null,
    snapshot_id         uuid not null,
    sku_id              uuid not null,
    position_id         uuid not null,
    external_sellable   integer not null,
    external_non_sellable integer not null,
    reflected_quantity  integer not null,
    shipped_after_quantity integer not null,
    committed_at_apply  integer not null,
    sellable_before     integer not null,
    sellable_after      integer not null,
    non_sellable_before integer not null,
    non_sellable_after  integer not null,
    certain             boolean not null,
    restriction_reason  text,
    primary key (snapshot_id, sku_id),
    foreign key (workspace_id, snapshot_id) references external_inventory_snapshot (workspace_id, id),
    foreign key (workspace_id, position_id) references inventory_position (workspace_id, id)
);
create trigger external_inventory_result_append_only before update or delete on external_inventory_result for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['external_inventory_snapshot', 'external_inventory_line', 'external_inventory_result']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;

-- 3PL 재고 파일 원본 보관 용도
alter table stored_file drop constraint stored_file_purpose_check;
alter table stored_file add constraint stored_file_purpose_check
    check (purpose in ('GENERIC_IMPORT', 'SETTLEMENT_SOURCE', 'VAT_SOURCE', 'CAPABILITY_EVIDENCE', 'EXPORT', 'EXTERNAL_INVENTORY'));
