-- M3 I6 재고 검증센터 (docs/13 §10, docs/03 §7-3): 이상 유형·사업자·거점·SKU·수량/원가 영향·근거·해결·재검증
-- 검증은 원장을 고치지 않는다. 발견한 이상을 이슈로 남기고, 다시 검증해 원인이 사라지면 자동으로 해결 처리한다.

create table inventory_check_run (
    id                  uuid primary key,
    workspace_id        uuid not null references workspace (id),
    trigger_type        text not null check (trigger_type in ('MANUAL', 'SCHEDULED', 'RECHECK')),
    requested_by        uuid,
    started_at          timestamptz not null default now(),
    finished_at         timestamptz,
    checked_positions   integer not null default 0,
    found_issues        integer not null default 0,
    new_issues          integer not null default 0,
    resolved_issues     integer not null default 0,
    unique (workspace_id, id)
);
create index inventory_check_run_list_idx on inventory_check_run (workspace_id, started_at desc);

create table inventory_check_issue (
    id                  uuid primary key,
    workspace_id        uuid not null,
    -- 같은 이상(검사 종류 + 대상)은 한 건으로 이어서 추적한다
    issue_key           text not null,
    check_code          text not null check (check_code ~ '^[A-Z][A-Z0-9_]{2,60}$'),
    severity            text not null check (severity in ('HIGH', 'MEDIUM', 'LOW')),
    business_entity_id  uuid not null,
    node_id             uuid,
    sku_id              uuid,
    position_id         uuid,
    ref_type            text,
    ref_id              text,
    expected            bigint,
    actual              bigint,
    cost_impact         numeric(18, 0),
    message_ko          text not null,
    evidence            jsonb not null default '{}',
    status              text not null default 'OPEN' check (status in ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    first_detected_at   timestamptz not null default now(),
    last_detected_at    timestamptz not null default now(),
    detection_count     integer not null default 1,
    last_run_id         uuid not null,
    acknowledged_by     uuid,
    acknowledged_at     timestamptz,
    acknowledge_reason  text check (acknowledge_reason is null or length(acknowledge_reason) <= 500),
    resolved_at         timestamptz,
    resolved_run_id     uuid,
    version             integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id),
    foreign key (workspace_id, node_id) references fulfillment_node (workspace_id, id),
    check (status <> 'ACKNOWLEDGED' or (acknowledged_by is not null and acknowledge_reason is not null)),
    check ((status = 'RESOLVED') = (resolved_at is not null))
);
-- 열린 이슈(OPEN·ACKNOWLEDGED)는 같은 키로 하나만. 해결 후 다시 생기면 새 이슈
create unique index inventory_check_issue_open_uq on inventory_check_issue (workspace_id, issue_key) where status in ('OPEN', 'ACKNOWLEDGED');
create index inventory_check_issue_list_idx on inventory_check_issue (workspace_id, status, severity, last_detected_at desc);

do $$
declare
    t text;
begin
    foreach t in array array['inventory_check_run', 'inventory_check_issue']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
