-- P1 발주·매입(FR-FIN-01, docs/01 §발주·매입, docs/04 §8, docs/13 §9, DLG-190~196)

insert into permission (code, category, name_ko, sensitive) values
 ('purchase.manage', 'OPERATIONS', '공급처·발주 관리', false);
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'purchase.manage' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'OPS_ADMIN', 'MD')
on conflict do nothing;

alter table claim_number_counter drop constraint claim_number_counter_kind_check;
alter table claim_number_counter add constraint claim_number_counter_kind_check
    check (kind in ('CLAIM', 'RETURN_RECEIPT', 'WMS_WAVE', 'PURCHASE_ORDER', 'PO_RECEIPT'));

-- 공급처(사업자별). 같은 업체도 사업자마다 따로 둔다(거래·세금계산서 주체가 사업자).
create table supplier (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    name                text not null check (length(name) between 1 and 100),
    business_registration_no text check (business_registration_no is null or business_registration_no ~ '^[0-9]{10}$'),
    lead_time_days      integer not null default 7 check (lead_time_days between 0 and 365),
    payment_terms       text check (payment_terms is null or length(payment_terms) <= 200),
    memo                text check (memo is null or length(memo) <= 500),
    status              text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (business_entity_id, name),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);

-- 공급처 SKU: 단가(공급가)·MOQ·발주단위·리드타임
create table supplier_sku (
    id                  uuid primary key,
    workspace_id        uuid not null,
    supplier_id         uuid not null,
    sku_id              uuid not null,
    supplier_sku_code   text check (supplier_sku_code is null or length(supplier_sku_code) <= 100),
    unit_cost           numeric(18, 0) not null check (unit_cost >= 0),
    tax_type            text not null default 'TAXABLE' check (tax_type in ('TAXABLE', 'TAX_FREE', 'ZERO_RATED')),
    moq                 integer not null default 1 check (moq >= 1),
    order_multiple      integer not null default 1 check (order_multiple >= 1),
    lead_time_days      integer check (lead_time_days is null or lead_time_days between 0 and 365),
    status              text not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (supplier_id, sku_id),
    foreign key (workspace_id, supplier_id) references supplier (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id)
);

create table purchase_order (
    id                  uuid primary key,
    workspace_id        uuid not null,
    po_no               text not null,
    business_entity_id  uuid not null,
    supplier_id         uuid not null,
    node_id             uuid not null,
    status              text not null check (status in ('DRAFT', 'APPROVAL_PENDING', 'PLACED', 'SUPPLIER_CONFIRMED', 'PARTIALLY_RECEIVED', 'RECEIVED',
                                                        'CLOSED', 'CANCELLED')),
    expected_on         date,
    supplier_ref        text check (supplier_ref is null or length(supplier_ref) <= 100),
    memo                text check (memo is null or length(memo) <= 500),
    close_reason        text check (close_reason is null or length(close_reason) <= 500),
    approval_id         uuid,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    placed_at           timestamptz,
    updated_at          timestamptz not null default now(),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, po_no),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, supplier_id) references supplier (workspace_id, id),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id)
);
create index purchase_order_list_idx on purchase_order (workspace_id, created_at desc);

create table purchase_order_line (
    id                  uuid primary key,
    workspace_id        uuid not null,
    po_id               uuid not null,
    line_no             integer not null,
    sku_id              uuid not null,
    supplier_sku_id     uuid,
    quantity            integer not null check (quantity >= 1),
    unit_cost           numeric(18, 0) not null check (unit_cost >= 0),
    tax_type            text not null check (tax_type in ('TAXABLE', 'TAX_FREE', 'ZERO_RATED')),
    supply_amount       numeric(18, 0) not null,
    vat_amount          numeric(18, 0) not null,
    moq_override_reason text check (moq_override_reason is null or length(moq_override_reason) <= 300),
    received_qty        integer not null default 0,
    rejected_qty        integer not null default 0,
    unique (workspace_id, id),
    unique (po_id, line_no),
    foreign key (workspace_id, po_id) references purchase_order (workspace_id, id),
    foreign key (workspace_id, sku_id) references sku (workspace_id, id),
    check (received_qty >= 0 and rejected_qty >= 0 and received_qty + rejected_qty <= quantity)
);

-- 발주 입고(1 발주 : N 입고). 정상은 재고 원장 입고(실매입 단가), 불량은 공급처 반송 대상으로 받지 않는다.
create table po_receipt (
    id                  uuid primary key,
    workspace_id        uuid not null,
    receipt_no          text not null,
    po_id               uuid not null,
    received_at         timestamptz not null default now(),
    note                text check (note is null or length(note) <= 500),
    created_by          uuid,
    unique (workspace_id, id),
    unique (workspace_id, receipt_no),
    foreign key (workspace_id, po_id) references purchase_order (workspace_id, id)
);
create trigger po_receipt_append_only before update or delete on po_receipt for each row execute function forbid_mutation();

create table po_receipt_line (
    id                  uuid primary key,
    workspace_id        uuid not null,
    receipt_id          uuid not null,
    po_line_id          uuid not null,
    good_qty            integer not null check (good_qty >= 0),
    rejected_qty        integer not null check (rejected_qty >= 0),
    unit_cost           numeric(18, 0) not null check (unit_cost >= 0),
    po_unit_cost        numeric(18, 0) not null,
    lot_code            text,
    expires_on          date,
    inventory_txn_id    uuid,
    reject_reason       text check (reject_reason is null or length(reject_reason) <= 300),
    unique (workspace_id, id),
    foreign key (workspace_id, receipt_id) references po_receipt (workspace_id, id),
    foreign key (workspace_id, po_line_id) references purchase_order_line (workspace_id, id),
    check (good_qty + rejected_qty >= 1)
);
create trigger po_receipt_line_append_only before update or delete on po_receipt_line for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['supplier', 'supplier_sku', 'purchase_order', 'purchase_order_line', 'po_receipt', 'po_receipt_line']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
