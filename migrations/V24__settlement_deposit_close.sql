-- M5 N3 정산 원본·대사, N4 입금 다대다 매칭, N7 월 마감 (docs/05 §11·§12, DLG-036, DLG-161~168)

insert into permission (code, category, name_ko, sensitive) values
 ('settlement.manage', 'FINANCE', '정산 가져오기·입금 매칭', true);
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'settlement.manage' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'FINANCE')
on conflict do nothing;

-- 정산(판매계정별, 정산번호 단위). 같은 정산번호 재업로드는 같은 정산(T29).
create table settlement_run (
    id                    uuid primary key,
    workspace_id          uuid not null,
    business_entity_id    uuid not null,
    channel_account_id    uuid not null,
    settlement_key        text not null check (length(settlement_key) between 1 and 120),
    period_from           date,
    period_to             date,
    payout_date           date,
    declared_payout       numeric(18, 0),
    source                text not null check (source in ('FILE', 'API', 'MANUAL')),
    file_id               uuid,
    status                text not null check (status in ('IMPORTED', 'RECONCILED', 'ISSUES')),
    created_by            uuid,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    version               integer not null default 1,
    unique (workspace_id, id),
    unique (channel_account_id, settlement_key),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create index settlement_run_list_idx on settlement_run (workspace_id, created_at desc);

-- 정산 원본 행(추가 전용). 판매자 기준 부호(+ 받을 돈, − 공제), 부가세 포함 금액.
create table settlement_line (
    id                  uuid primary key,
    workspace_id        uuid not null,
    run_id              uuid not null,
    line_no             integer not null,
    line_type           text not null check (line_type in ('SALE', 'SHIPPING_FEE', 'COMMISSION', 'PAYMENT_FEE', 'DISCOUNT_COMPENSATION', 'REFUND', 'PENALTY',
                                                           'ADJUSTMENT', 'OTHER')),
    amount              numeric(18, 0) not null,
    quantity            integer,
    external_order_key  text,
    external_item_key   text,
    order_id            uuid,
    order_item_id       uuid,
    match_level         text not null check (match_level in ('ITEM', 'ORDER', 'PERIOD', 'UNMATCHED')),
    applied             text not null check (applied in ('LEDGER', 'RECON_ONLY', 'REVIEW')),
    row_hash            text not null,
    raw                 jsonb not null,
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    unique (run_id, line_no),
    foreign key (workspace_id, run_id) references settlement_run (workspace_id, id)
);
-- 같은 판매계정의 같은 원본 행은 한 번만(다른 파일로 다시 올려도)
create unique index settlement_line_hash_uq on settlement_line (workspace_id, row_hash);
create index settlement_line_item_idx on settlement_line (order_item_id);
-- 원본 행은 불변. 미매칭 행이 나중에 수집된 주문과 연결될 때만 연결 정보(order_id·order_item_id·match_level·applied)를 바꿀 수 있다.
create or replace function settlement_line_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'settlement_line is append-only' using errcode = 'P0001';
    end if;
    if old.match_level <> 'UNMATCHED' or new.run_id <> old.run_id or new.line_no <> old.line_no or new.line_type <> old.line_type or new.amount <> old.amount
       or new.quantity is distinct from old.quantity or new.external_order_key is distinct from old.external_order_key
       or new.external_item_key is distinct from old.external_item_key or new.row_hash <> old.row_hash or new.raw <> old.raw then
        raise exception 'settlement_line source columns are immutable' using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger settlement_line_guard before update or delete on settlement_line for each row execute function settlement_line_guard();

-- 입금(계좌 입금 내역)
create table bank_deposit (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    bank_account_id     uuid,
    deposited_on        date not null,
    amount              numeric(18, 0) not null check (amount > 0),
    depositor           text check (depositor is null or length(depositor) <= 100),
    external_ref        text check (external_ref is null or length(external_ref) <= 120),
    memo                text check (memo is null or length(memo) <= 500),
    idempotency_key     text not null,
    created_by          uuid,
    created_at          timestamptz not null default now(),
    unique (workspace_id, id),
    unique (workspace_id, idempotency_key),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create unique index bank_deposit_ref_uq on bank_deposit (business_entity_id, external_ref) where external_ref is not null;
create trigger bank_deposit_append_only before update or delete on bank_deposit for each row execute function forbid_mutation();

-- 입금 ↔ 정산 다대다. 연결 취소는 행을 지우지 않고 cancelled_at 기록.
create table deposit_match (
    id              uuid primary key,
    workspace_id    uuid not null,
    deposit_id      uuid not null,
    run_id          uuid not null,
    amount          numeric(18, 0) not null check (amount > 0),
    method          text not null check (method in ('AUTO', 'MANUAL')),
    created_by      uuid,
    created_at      timestamptz not null default now(),
    cancelled_at    timestamptz,
    cancelled_by    uuid,
    foreign key (workspace_id, deposit_id) references bank_deposit (workspace_id, id),
    foreign key (workspace_id, run_id) references settlement_run (workspace_id, id)
);
create index deposit_match_deposit_idx on deposit_match (deposit_id) where cancelled_at is null;
create index deposit_match_run_idx on deposit_match (run_id) where cancelled_at is null;

-- 월 마감(사업자·월). 마감된 달의 원장은 바꿀 수 없다.
create table accounting_period (
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    month               date not null check (extract(day from month) = 1),
    status              text not null check (status in ('OPEN', 'CLOSED')),
    closed_by           uuid,
    closed_at           timestamptz,
    snapshot            jsonb,
    version             integer not null default 1,
    primary key (business_entity_id, month),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);

create table accounting_period_event (
    id                  uuid primary key,
    workspace_id        uuid not null,
    business_entity_id  uuid not null,
    month               date not null,
    event_type          text not null check (event_type in ('CLOSED', 'REOPENED')),
    reason              text,
    actor_member_id     uuid,
    approval_id         uuid,
    occurred_at         timestamptz not null default now()
);
create trigger accounting_period_event_append_only before update or delete on accounting_period_event for each row execute function forbid_mutation();

-- 마감된 달에 원장 행을 넣지 못하게(애플리케이션은 다음 열린 달로 귀속하지만 우회 방지)
create or replace function financial_entry_closed_guard() returns trigger language plpgsql as $$
begin
    if exists (select 1 from accounting_period p where p.business_entity_id = new.business_entity_id
                and p.month = date_trunc('month', new.period_date)::date and p.status = 'CLOSED') then
        raise exception 'accounting period closed for % %', new.business_entity_id, to_char(new.period_date, 'YYYY-MM') using errcode = 'P0001';
    end if;
    return new;
end
$$;
create trigger financial_entry_closed_guard before insert on financial_entry for each row execute function financial_entry_closed_guard();

alter table financial_entry add column original_period_date date;

do $$
declare
    t text;
begin
    foreach t in array array['settlement_run', 'settlement_line', 'bank_deposit', 'deposit_match', 'accounting_period', 'accounting_period_event']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
