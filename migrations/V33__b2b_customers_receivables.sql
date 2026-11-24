-- B2B 거래처 마스터·단가표·외상(매출채권) 원장(FR-ORD-04 후속, DLG-200 → DLG-226~233).
insert into permission (code, category, name_ko, sensitive) values
 ('customer.manage', 'OPERATIONS', 'B2B 거래처·단가표 관리', false),
 ('receivable.manage', 'FINANCE', '외상(매출채권) 한도·수금·대손 관리', true);
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'customer.manage' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'OPS_ADMIN', 'ORDER_MANAGER')
on conflict do nothing;
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'receivable.manage' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'FINANCE')
on conflict do nothing;

-- 거래처(사업자별: 거래·세금계산서 주체가 사업자). 담당자 연락처는 암호화, 화면에는 마스킹.
create table b2b_customer (
    id                      uuid primary key,
    workspace_id            uuid not null,
    business_entity_id      uuid not null,
    code                    text not null check (code ~ '^[A-Za-z0-9_-]{1,40}$'),
    name                    text not null check (length(name) between 1 and 100),
    business_registration_no text check (business_registration_no is null or business_registration_no ~ '^[0-9]{10}$'),
    contact_cipher          bytea,
    contact_key_id          text,
    contact_name_masked     text,
    contact_phone_masked    text,
    payment_terms_days      integer not null default 30 check (payment_terms_days between 0 and 365),
    credit_limit            numeric(18, 0) not null default 0 check (credit_limit >= 0),
    status                  text not null default 'ACTIVE' check (status in ('ACTIVE', 'ON_HOLD', 'INACTIVE')),
    hold_reason             text check (hold_reason is null or length(hold_reason) <= 300),
    memo                    text check (memo is null or length(memo) <= 500),
    created_by              uuid,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    version                 integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, business_entity_id, code),
    unique (workspace_id, business_entity_id, name),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    check (status <> 'ON_HOLD' or hold_reason is not null)
);

-- 거래처 단가표(부가세 포함 판매 단가). 변경은 새 적용일 행 추가(이력 보존).
create table b2b_price (
    id                      uuid primary key,
    workspace_id            uuid not null,
    customer_id             uuid not null,
    sku_id                  uuid not null,
    unit_price              numeric(18, 0) not null check (unit_price >= 0),
    effective_from          date not null,
    created_by              uuid,
    created_at              timestamptz not null default now(),
    unique (workspace_id, id),
    unique (customer_id, sku_id, effective_from),
    foreign key (workspace_id, customer_id) references b2b_customer (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id)
);
create trigger b2b_price_append_only before update or delete on b2b_price for each row execute function forbid_mutation();

-- 주문 ↔ 거래처, 결제 조건(선결제 / 외상)
alter table sales_order add column b2b_customer_id uuid;
alter table sales_order add column payment_terms text check (payment_terms is null or payment_terms in ('PREPAID', 'CREDIT'));
alter table sales_order add constraint sales_order_b2b_customer_fk foreign key (workspace_id, b2b_customer_id) references b2b_customer (workspace_id, id);
alter table sales_order add constraint sales_order_b2b_ck check (b2b_customer_id is null or (order_kind = 'B2B' and payment_terms is not null));
create index sales_order_b2b_customer_idx on sales_order (b2b_customer_id) where b2b_customer_id is not null;

-- 외상 원장(append-only). 금액 부호: + 채권 증가(출고 청구·수금 취소), − 감소(반품 차감·수금·대손).
create table receivable_entry (
    id                      uuid primary key,
    workspace_id            uuid not null,
    business_entity_id      uuid not null,
    customer_id             uuid not null,
    entry_type              text not null check (entry_type in ('CHARGE', 'CREDIT_NOTE', 'PAYMENT', 'PAYMENT_REVERSAL', 'WRITE_OFF')),
    amount                  numeric(18, 0) not null check (amount <> 0),
    order_id                uuid,
    fo_id                   uuid,
    claim_id                uuid,
    occurred_on             date not null,
    due_on                  date,
    method                  text check (method is null or method in ('BANK_TRANSFER', 'CASH', 'CARD', 'NOTE', 'OFFSET', 'OTHER')),
    reference               text check (reference is null or length(reference) <= 100),
    memo                    text check (memo is null or length(memo) <= 500),
    reverses_id             uuid unique,
    approval_id             uuid,
    source_key              text not null,
    created_by              uuid,
    created_at              timestamptz not null default now(),
    unique (workspace_id, id),
    unique (workspace_id, source_key),
    foreign key (workspace_id, customer_id) references b2b_customer (workspace_id, id),
    foreign key (workspace_id, reverses_id) references receivable_entry (workspace_id, id),
    check (entry_type <> 'CHARGE' or (amount > 0 and due_on is not null)),
    check (entry_type not in ('PAYMENT', 'WRITE_OFF') or amount < 0),
    check (entry_type <> 'PAYMENT_REVERSAL' or (amount > 0 and reverses_id is not null))
);
create index receivable_entry_customer_idx on receivable_entry (customer_id, occurred_on);
create trigger receivable_entry_append_only before update or delete on receivable_entry for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['b2b_customer', 'b2b_price', 'receivable_entry']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
