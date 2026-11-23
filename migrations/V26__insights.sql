-- M6 1차: 규칙 기반 인사이트 카드(docs/14 §4·§5, FR-OPS-04·05, DLG-177~182). 읽기·추천 전용 — 자동 실행 없음.

create table insight_card (
    id                  uuid primary key,
    workspace_id        uuid not null,
    rule_code           text not null check (rule_code in ('MARGIN_DROP', 'STOCKOUT_RISK', 'SLOW_MOVING', 'EXPIRY_RISK', 'SETTLEMENT_MISSING', 'UNDEPOSITED')),
    dedupe_key          text not null,
    business_entity_id  uuid not null,
    channel_account_id  uuid,
    sku_id              uuid,
    severity            text not null check (severity in ('HIGH', 'MEDIUM', 'LOW')),
    status              text not null check (status in ('OPEN', 'ACTIONED', 'DISMISSED', 'RESOLVED')),
    title               text not null,
    summary             text not null,
    -- 지표·비교기간·원인 후보(확실성 포함)·근거 레코드·후속 액션·미확인 데이터 표시
    metric              jsonb not null,
    causes              jsonb not null default '[]'::jsonb,
    evidence            jsonb not null default '{}'::jsonb,
    unconfirmed         jsonb not null default '[]'::jsonb,
    observed_on         date not null,
    first_detected_at   timestamptz not null default now(),
    last_detected_at    timestamptz not null default now(),
    resolved_at         timestamptz,
    work_item_id        uuid,
    dismissed_by        uuid,
    dismissed_at        timestamptz,
    dismiss_reason      text check (dismiss_reason is null or length(dismiss_reason) <= 500),
    version             integer not null default 1,
    unique (workspace_id, id),
    unique (workspace_id, dedupe_key),
    foreign key (workspace_id, business_entity_id) references business_entity (workspace_id, id)
);
create index insight_card_open_idx on insight_card (workspace_id, status, severity);

create table insight_run (
    id              uuid primary key,
    workspace_id    uuid not null,
    trigger         text not null check (trigger in ('MANUAL', 'SCHEDULED')),
    started_at      timestamptz not null default now(),
    finished_at     timestamptz,
    detected        integer not null default 0,
    opened          integer not null default 0,
    resolved        integer not null default 0,
    requested_by    uuid
);
create index insight_run_ws_idx on insight_run (workspace_id, started_at desc);

do $$
declare
    t text;
begin
    foreach t in array array['insight_card', 'insight_run']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
