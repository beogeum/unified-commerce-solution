-- M6 2차: 사업자별 부가세 신고자료 수집·표준화(docs/15, FR-FIN-08, DLG-183~189). 국세청 자동 신고 아님.

insert into permission (code, category, name_ko, sensitive) values
 ('vat.manage', 'FINANCE', '부가세 신고자료 가져오기·분류 보정·확정', true);
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'vat.manage' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'FINANCE')
on conflict do nothing;

-- 신고자료 묶음: 판매계정 × 신고기간. 사업자는 판매계정의 사업자(다른 사업자 자료와 섞지 않음).
create table vat_batch (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    channel_account_id  uuid not null,
    period_from         date not null,
    period_to           date not null check (period_to >= period_from),
    status              text not null check (status in ('DRAFT', 'CONFIRMED')),
    rule_version        text not null,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    confirmed_by        uuid,
    confirmed_at        timestamptz,
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (channel_account_id, period_from, period_to),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);

-- 원본 파일(SHA-256·시트·파서 버전·가져온 사람). 같은 파일은 사업자 안에서 한 번만.
create table vat_source_file (
    id                  uuid primary key,
    workspace_id        uuid not null,
    batch_id            uuid not null,
    business_entity_id  uuid not null,
    file_id             uuid not null,
    sha256              text not null,
    original_filename   text not null,
    sheet_name          text,
    parser_version      text not null,
    row_count           integer not null,
    new_rows            integer not null,
    duplicate_rows      integer not null,
    imported_by         uuid,
    imported_at         timestamptz not null default now(),
    unique (workspace_id, id),
    unique (business_entity_id, sha256),
    foreign key (workspace_id, batch_id) references vat_batch (workspace_id, id)
);
create trigger vat_source_file_append_only before update or delete on vat_source_file for each row execute function forbid_mutation();

-- 표준화 행(추가 전용). 금액은 부가세 별도 표기(공급가·세액·합계), 환불은 음수.
create table vat_row (
    id                  uuid primary key,
    workspace_id        uuid not null,
    batch_id            uuid not null,
    source_file_id      uuid not null,
    row_no              integer not null,
    txn_date            date not null,
    source_key          text,
    sales_type          text not null check (sales_type in ('SALE', 'REFUND', 'ADJUSTMENT')),
    tax_type            text not null check (tax_type in ('TAXABLE', 'TAX_FREE', 'ZERO_RATED', 'OTHER')),
    payment_method      text not null check (payment_method in ('CARD', 'CASH_RECEIPT', 'MOBILE', 'OTHER')),
    evidence_type       text not null check (evidence_type in ('TAX_INVOICE', 'E_TAX_INVOICE', 'INVOICE', 'E_INVOICE', 'NONE')),
    supply_amount       numeric(18, 0) not null,
    vat_amount          numeric(18, 0) not null,
    total_amount        numeric(18, 0) not null,
    classification      text not null,
    issues              jsonb not null default '[]'::jsonb,
    row_hash            text not null,
    raw                 jsonb not null,
    unique (workspace_id, id),
    unique (batch_id, row_hash),
    foreign key (workspace_id, batch_id) references vat_batch (workspace_id, id),
    foreign key (workspace_id, source_file_id) references vat_source_file (workspace_id, id)
);
create index vat_row_batch_idx on vat_row (batch_id, classification);
create trigger vat_row_append_only before update or delete on vat_row for each row execute function forbid_mutation();

-- 분류 보정(원본 행은 그대로, 최신 보정이 유효). 제외(EXCLUDED) 포함.
create table vat_row_override (
    id                  uuid primary key,
    workspace_id        uuid not null,
    row_id              uuid not null,
    classification      text not null,
    reason              text not null check (length(reason) between 1 and 500),
    created_by          uuid,
    created_at          timestamptz not null default now(),
    foreign key (workspace_id, row_id) references vat_row (workspace_id, id)
);
create index vat_row_override_row_idx on vat_row_override (row_id, created_at desc);
create trigger vat_row_override_append_only before update or delete on vat_row_override for each row execute function forbid_mutation();

do $$
declare
    t text;
begin
    foreach t in array array['vat_batch', 'vat_source_file', 'vat_row', 'vat_row_override']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
