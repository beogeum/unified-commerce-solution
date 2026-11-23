-- M5 N1·N2·N5 관리 손익 원장 (docs/05, DLG-004·030·035·051, DLG-153~160)
-- 단일 권위 원장 financial_entry: 매출·비용·메모(손익 제외) 항목을 원천 사건 기준으로 한 번만 기록(source_key). 기확정 행은 고치지 않고 반대 거래로 조정.
-- 모든 금액은 원 단위 정수, 손익은 공급가(부가세 제외) 기준.

create table financial_entry (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    channel_account_id  uuid,
    order_id            uuid,
    order_item_id       uuid,
    claim_id            uuid,
    fo_id               uuid,
    shipment_id         uuid,
    sku_id              uuid,
    node_id             uuid,
    entry_class         text not null check (entry_class in ('REVENUE', 'COST', 'MEMO')),
    category            text not null check (category in (
        -- 매출
        'SALES', 'SELLER_DISCOUNT', 'SALES_RETURN', 'SHIPPING_FEE', 'EXCHANGE_PRICE_DIFFERENCE', 'CUSTOMER_RETURN_FEE',
        -- 메모(손익 밖): 채널 부담 할인(채널이 보전, 판매자 매출 감액 아님)
        'CHANNEL_DISCOUNT',
        -- 비용(docs/05 §5)
        'PRODUCT_COGS', 'SELLING_FEE', 'PAYMENT_FEE', 'ADVERTISING', 'OUTBOUND_SHIPPING', 'THIRD_PARTY_PICK', 'PACK', 'STORAGE',
        'INBOUND_SERVICE', 'MATERIAL', 'GWP', 'RETURN_SHIPPING', 'RETURN_INSPECTION', 'REPACK', 'EXCHANGE_SHIPPING', 'INVENTORY_IMPAIRMENT',
        'DISPOSAL_SERVICE', 'CHANNEL_PENALTY', 'OTHER')),
    basis               text not null check (basis in ('ESTIMATED', 'ACTUAL')),
    -- 부호: 매출·비용이 늘면 +, 줄면 −(환입·반품·대체 상계)
    supply_amount       numeric(18, 0) not null,
    vat_amount          numeric(18, 0) not null default 0,
    tax_type            text not null default 'TAXABLE' check (tax_type in ('TAXABLE', 'TAX_FREE', 'ZERO_RATED', 'OUT_OF_SCOPE')),
    quantity            integer,
    unit_cost           numeric(18, 4),
    period_date         date not null,
    source_type         text not null check (source_type ~ '^[A-Z][A-Z0-9_]{1,40}$'),
    source_ref          text,
    -- 같은 경제적 사실은 한 번만(재처리·재수신 멱등)
    source_key          text not null,
    -- 예상 → 실제 대체: 예상 행을 상계하는 반대 행이 reverses_id 로 원 행을 가리킨다
    reverses_id         uuid,
    scope_key           text,
    allocation_method   text,
    intercompany        boolean not null default false,
    counterpart_business_entity_id uuid,
    memo                text check (memo is null or length(memo) <= 500),
    created_by          uuid,
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    unique (workspace_id, source_key),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create index financial_entry_period_idx on financial_entry (workspace_id, business_entity_id, period_date);
create index financial_entry_order_item_idx on financial_entry (order_item_id, category);
create index financial_entry_scope_idx on financial_entry (workspace_id, category, scope_key) where scope_key is not null;
create unique index financial_entry_reversal_uq on financial_entry (reverses_id) where reverses_id is not null;
create trigger financial_entry_append_only before update or delete on financial_entry for each row execute function forbid_mutation();

-- 예상 비용 규칙(계약요율). 가장 구체적인 규칙(판매계정 > 사업자 > 운영그룹 공통, 거점 지정 우선)을 쓴다. 버전은 행 단위(유효 기간).
create table cost_rule (
    id                   uuid primary key,
    workspace_id         uuid not null,
    business_entity_id   uuid,
    channel_account_id   uuid,
    node_id              uuid,
    category             text not null check (category in ('SELLING_FEE', 'PAYMENT_FEE', 'OUTBOUND_SHIPPING', 'THIRD_PARTY_PICK', 'PACK', 'MATERIAL', 'RETURN_SHIPPING',
                                                          'RETURN_INSPECTION', 'EXCHANGE_SHIPPING')),
    method               text not null check (method in ('RATE_OF_NET_SALES', 'PER_UNIT', 'PER_ORDER', 'PER_SHIPMENT', 'PER_RETURN')),
    rate_bps             integer check (rate_bps is null or rate_bps between 0 and 10000),
    amount               numeric(18, 0) check (amount is null or amount >= 0),
    refundable_on_return boolean not null default true,
    effective_from       timestamptz not null default now(),
    effective_to         timestamptz,
    memo                 text check (memo is null or length(memo) <= 500),
    created_by           uuid,
    created_at           timestamptz not null default now(),
    unique (workspace_id, id),
    check ((method = 'RATE_OF_NET_SALES') = (rate_bps is not null)),
    check ((method = 'RATE_OF_NET_SALES') or amount is not null),
    check (effective_to is null or effective_to > effective_from)
);
create index cost_rule_lookup_idx on cost_rule (workspace_id, category);

-- 이동평균 원가(사업자·SKU, 거점 통합). 입고 단가가 들어오면 갱신, 없으면 등록 원가(sku_cost) 사용.
create table sku_moving_cost (
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    sku_id              uuid not null,
    avg_unit_cost       numeric(18, 4) not null check (avg_unit_cost >= 0),
    basis_quantity      integer not null,
    updated_at          timestamptz not null default now(),
    primary key (business_entity_id, sku_id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);

-- 재고 장부가 조정(평가손실·장부가 0 반품·폐기). 품질 상태만으로 비용 처리하지 않는다(docs/05 §6).
create table inventory_valuation_entry (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    sku_id              uuid not null,
    node_id             uuid not null,
    stock_id            uuid not null,
    entry_type          text not null check (entry_type in ('ZERO_BOOK_RETURN', 'IMPAIRMENT', 'DISPOSAL')),
    quantity            integer not null check (quantity >= 1),
    unit_cost           numeric(18, 4) not null,
    -- 이 행으로 손익에 인식한 손실(장부가 0 반품은 0: 원가가 이미 매출원가에 남아 있음)
    loss_amount         numeric(18, 0) not null check (loss_amount >= 0),
    -- 폐기 행: 이미 평가손실·장부가 0 이었던 수량(이번에 다시 손실로 잡지 않은 수량)
    pre_impaired_qty    integer not null default 0,
    reason              text,
    source_key          text not null,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    unique (workspace_id, source_key),
    foreign key (workspace_id, stock_id) references inventory_stock (workspace_id, id)
);
create index inventory_valuation_stock_idx on inventory_valuation_entry (stock_id);
create trigger inventory_valuation_entry_append_only before update or delete on inventory_valuation_entry for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['financial_entry', 'cost_rule', 'sku_moving_cost', 'inventory_valuation_entry']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
