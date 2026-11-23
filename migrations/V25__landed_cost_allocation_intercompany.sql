-- M5 3차: 입고 부대비용(T33), 공통비 배부(T34), 사업자 간 재고 이전·내부거래 제거(T35, DLG-033·034, DLG-169~176)

-- 입고 단가 보존(부대비용 금액 기준 배부 근거). 재고 원장은 수량만, 단가는 이 표에.
create table inventory_receipt_cost (
    txn_id              uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    sku_id              uuid not null,
    quantity            integer not null check (quantity >= 1),
    unit_cost           numeric(18, 4) not null check (unit_cost >= 0),
    created_at          timestamptz not null default now(),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create trigger inventory_receipt_cost_append_only before update or delete on inventory_receipt_cost for each row execute function forbid_mutation();

-- 입고 부대비용(운임·관세·검사 등): 입고분에 배부해 취득원가로. 이미 출고된 수량분만 매출원가로. 같은 청구(근거번호)는 한 번만.
create table landed_cost (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    cost_type           text not null check (cost_type in ('FREIGHT', 'DUTY', 'CUSTOMS', 'INSPECTION', 'OTHER')),
    supply_amount       numeric(18, 0) not null check (supply_amount > 0),
    vat_amount          numeric(18, 0) not null default 0 check (vat_amount >= 0),
    method              text not null check (method in ('QUANTITY', 'AMOUNT', 'MANUAL')),
    source_ref          text not null check (length(source_ref) between 1 and 200),
    memo                text check (memo is null or length(memo) <= 500),
    capitalized_amount  numeric(18, 0) not null,
    expensed_amount     numeric(18, 0) not null,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    unique (business_entity_id, source_ref),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create trigger landed_cost_append_only before update or delete on landed_cost for each row execute function forbid_mutation();

create table landed_cost_line (
    id                  uuid primary key,
    workspace_id        uuid not null,
    landed_cost_id      uuid not null,
    receipt_txn_id      uuid not null,
    sku_id              uuid not null,
    quantity            integer not null,
    basis_value         numeric(18, 4) not null,
    allocated_amount    numeric(18, 0) not null,
    capitalized_amount  numeric(18, 0) not null,
    expensed_amount     numeric(18, 0) not null,
    on_hand_at_allocation integer not null,
    unique (landed_cost_id, receipt_txn_id),
    foreign key (workspace_id, landed_cost_id) references landed_cost (workspace_id, id)
);
create trigger landed_cost_line_append_only before update or delete on landed_cost_line for each row execute function forbid_mutation();

-- 공통비 배부(사업자 간). 재배부는 새 버전 + 이전 버전 원장 반대 거래. 배부 합계 = 원본 금액.
create table cost_allocation (
    id                  uuid primary key,
    workspace_id        uuid not null,
    allocation_no       text not null,
    version             integer not null check (version >= 1),
    supersedes_id       uuid,
    status              text not null check (status in ('ACTIVE', 'SUPERSEDED')),
    category            text not null,
    supply_amount       numeric(18, 0) not null check (supply_amount > 0),
    vat_amount          numeric(18, 0) not null default 0 check (vat_amount >= 0),
    period_date         date not null,
    method              text not null check (method in ('NET_SALES', 'EQUAL', 'MANUAL_RATIO')),
    basis_from          date,
    basis_to            date,
    source_ref          text check (source_ref is null or length(source_ref) <= 200),
    memo                text check (memo is null or length(memo) <= 500),
    idempotency_key     text,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    unique (workspace_id, allocation_no, version),
    unique (workspace_id, idempotency_key)
);
create table cost_allocation_line (
    id                  uuid primary key,
    workspace_id        uuid not null,
    allocation_id       uuid not null,
    business_entity_id  uuid not null,
    numerator           numeric(18, 4) not null,
    denominator         numeric(18, 4) not null,
    supply_amount       numeric(18, 0) not null,
    vat_amount          numeric(18, 0) not null,
    entry_id            uuid,
    unique (allocation_id, business_entity_id),
    foreign key (workspace_id, allocation_id) references cost_allocation (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create trigger cost_allocation_line_append_only before update or delete on cost_allocation_line for each row execute function forbid_mutation();
create sequence if not exists cost_allocation_no_seq;

-- 사업자 간 재고 소유권 이전(내부 매출·매입, 승인 필요). 실물 위치는 그대로, 소유 사업자만 바뀐다.
create table intercompany_transfer (
    id                      uuid primary key,
    workspace_id            uuid not null,
    from_business_entity_id uuid not null,
    to_business_entity_id   uuid not null check (to_business_entity_id <> from_business_entity_id),
    sku_id                  uuid not null,
    node_id                 uuid not null,
    from_stock_id           uuid not null,
    to_stock_id             uuid,
    quantity                integer not null check (quantity >= 1),
    unit_price              numeric(18, 0) not null check (unit_price >= 0),
    supply_amount           numeric(18, 0) not null,
    vat_amount              numeric(18, 0) not null,
    tax_type                text not null check (tax_type in ('TAXABLE', 'TAX_FREE', 'ZERO_RATED')),
    unit_cost               numeric(18, 4),
    status                  text not null check (status in ('PENDING', 'COMPLETED', 'REJECTED', 'CANCELLED')),
    approval_id             uuid,
    reason                  text not null check (length(reason) between 1 and 500),
    created_by              uuid,
    created_at              timestamptz not null default now(),
    completed_at            timestamptz,
    version                 integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, from_business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, to_business_entity_id) references business_entity (workspace_id, id)
);
create index intercompany_transfer_list_idx on intercompany_transfer (workspace_id, created_at desc);

alter table inventory_transaction drop constraint inventory_transaction_txn_type_check;
alter table inventory_transaction add constraint inventory_transaction_txn_type_check
    check (txn_type in ('RECEIPT', 'QUALITY_CHANGE', 'RESERVE', 'ALLOCATE', 'PICK', 'CONSUME', 'RELEASE', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN',
                        'ASSEMBLY_OUT', 'ASSEMBLY_IN', 'DISPOSAL', 'EXTERNAL_SYNC', 'LOCATION_MOVE', 'OWNERSHIP_TRANSFER_OUT', 'OWNERSHIP_TRANSFER_IN'));

-- 내부거래 상대 사업자(운영그룹 합계 제거용, DLG-033)
alter table financial_entry add column counterparty_business_entity_id uuid;
alter table financial_entry add constraint financial_entry_intercompany_ck check (not intercompany or counterparty_business_entity_id is not null);

do $$
declare
    t text;
begin
    foreach t in array array['inventory_receipt_cost', 'landed_cost', 'landed_cost_line', 'cost_allocation', 'cost_allocation_line', 'intercompany_transfer']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
