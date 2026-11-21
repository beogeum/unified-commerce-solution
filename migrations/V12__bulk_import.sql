-- M2 C6 일괄 가져오기(엑셀·CSV) (docs/01 대량 파일, docs/13 §파일 템플릿, BulkJobs 시안)
-- 파일 → 양식 인식·컬럼 매핑 → 미리보기(행별 정상/경고/오류) → 확인 후 비동기 실행 → 행별 결과. 원본 파일은 stored_file(해시 포함)에 그대로 남는다.

create table import_batch (
    id                uuid primary key,
    workspace_id      uuid not null references workspace (id),
    import_type       text not null check (import_type ~ '^[A-Z][A-Z0-9_]{2,40}$'),
    template_version  text not null,
    file_id           uuid not null,
    sheet_name        text,
    column_mapping    jsonb not null,
    status            text not null default 'PREVIEWED'
                      check (status in ('PREVIEWED', 'QUEUED', 'RUNNING', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'CANCELLED', 'FAILED')),
    total_rows        integer not null check (total_rows >= 0),
    ok_rows           integer not null default 0,
    warning_rows      integer not null default 0,
    error_rows        integer not null default 0,
    applied_rows      integer not null default 0,
    failed_rows       integer not null default 0,
    job_id            uuid,
    created_by        uuid not null,
    created_at        timestamptz not null default now(),
    executed_by       uuid,
    executed_at       timestamptz,
    completed_at      timestamptz,
    error_message_ko  text,
    version           integer not null default 1,
    unique (workspace_id, id),
    foreign key (workspace_id, file_id) references stored_file (workspace_id, id)
);
create index import_batch_list_idx on import_batch (workspace_id, created_at desc);

create table import_row (
    workspace_id    uuid not null,
    batch_id        uuid not null,
    row_number      integer not null check (row_number >= 1),
    status          text not null check (status in ('OK', 'WARNING', 'ERROR')),
    messages        jsonb not null default '[]',
    raw             jsonb not null,
    normalized      jsonb,
    result          text not null default 'PENDING' check (result in ('PENDING', 'APPLIED', 'FAILED', 'SKIPPED')),
    result_message  text,
    applied_ref     text,
    processed_at    timestamptz,
    primary key (batch_id, row_number),
    foreign key (workspace_id, batch_id) references import_batch (workspace_id, id),
    check (status <> 'ERROR' or result in ('PENDING', 'SKIPPED'))
);
create index import_row_status_idx on import_row (batch_id, status);

do $$
declare
    t text;
begin
    foreach t in array array['import_batch', 'import_row']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
