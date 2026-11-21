-- M2 C2 사업자별 SKU 정책·원가 이력 + 공통 승인 요청 (FR-CAT-04, CONF-004, T04, DLG-030·031·035·043)
-- 상품·SKU(공통)와 사업자별 판매 여부·가격·원가·안전재고를 분리한다. 금액은 KRW 원 단위 정수(DLG-030·031).

insert into permission (code, category, name_ko, sensitive) values
 ('cost.manage',     'FINANCE',    '원가 등록', true),
 ('approval.decide', 'OPERATIONS', '승인 요청 승인·반려', true);

insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'cost.manage' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'FINANCE')
on conflict do nothing;
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'approval.decide' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'OPS_ADMIN')
on conflict do nothing;

create table business_sku_policy (
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    sku_id              uuid not null,
    sell_enabled        boolean not null default true,
    purchase_enabled    boolean not null default true,
    currency            char(3) not null default 'KRW' check (currency = 'KRW'),
    -- 기준 판매가·최저판매가: 부가세 포함, 원 단위
    base_price          numeric(18, 0) check (base_price is null or base_price >= 0),
    min_price           numeric(18, 0) check (min_price is null or min_price >= 0),
    safety_stock        integer not null default 0 check (safety_stock >= 0),
    note                text check (note is null or length(note) <= 500),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    primary key (business_entity_id, sku_id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    -- 최저판매가 보호: 기준 판매가는 최저판매가 아래로 내려갈 수 없다
    check (min_price is null or base_price is null or base_price >= min_price)
);
create index business_sku_policy_sku_idx on business_sku_policy (workspace_id, sku_id);

-- 원가 이력: 공급가(부가세 제외) 기준, 적용 시작 시각으로 버전 관리. 기존 행은 바꾸지 않고 새 행을 쌓는다(DLG-035).
create table sku_cost (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    sku_id              uuid not null,
    unit_cost           numeric(18, 0) not null check (unit_cost >= 0),
    currency            char(3) not null default 'KRW' check (currency = 'KRW'),
    vat_included        boolean not null default false check (not vat_included),
    effective_from      timestamptz not null,
    source              text not null check (source in ('MANUAL', 'PURCHASE', 'ADJUSTMENT')),
    source_ref          text,
    reason              text not null check (length(btrim(reason)) between 1 and 500),
    created_by          uuid,
    created_at          timestamptz not null default now(),
    unique (business_entity_id, sku_id, effective_from),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id)
);
create index sku_cost_lookup_idx on sku_cost (workspace_id, business_entity_id, sku_id, effective_from desc);
create trigger sku_cost_append_only before update or delete on sku_cost for each row execute function forbid_mutation();

-- 공통 승인 요청(DLG-043). 요청자와 승인자는 달라야 한다(DB 로도 강제).
create table approval_request (
    id                  uuid primary key,
    workspace_id        uuid not null references workspace (id),
    request_type        text not null check (request_type ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    target_type         text not null,
    target_key          text not null,
    business_entity_id  uuid,
    payload             jsonb not null,
    impact              jsonb not null default '{}',
    reason              text not null check (length(btrim(reason)) between 1 and 500),
    status              text not null default 'PENDING' check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by        uuid not null,
    requested_at        timestamptz not null default now(),
    decided_by          uuid,
    decided_at          timestamptz,
    decision_note       text,
    version             integer not null default 1,
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    check (decided_by is null or decided_by <> requested_by),
    check ((status = 'PENDING') = (decided_at is null))
);
-- 같은 대상의 같은 종류 요청은 동시에 하나만 대기
create unique index approval_request_pending_uq on approval_request (workspace_id, request_type, target_key) where status = 'PENDING';
create index approval_request_list_idx on approval_request (workspace_id, status, requested_at desc);

do $$
declare
    t text;
begin
    foreach t in array array['business_sku_policy', 'sku_cost', 'approval_request']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
