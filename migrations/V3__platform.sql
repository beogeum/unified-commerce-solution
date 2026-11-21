-- =====================================================================
-- V3: 감사로그·Outbox·소비 기록·비동기 작업·멱등키·파일·저장된 보기
-- docs/02 §3, docs/07 §1 §6, docs/08 §4~5, CLAUDE.md 품질 기준
-- =====================================================================

-- 감사로그: append-only. 값 원문 대신 마스킹된 before/after 만 저장(docs/08 §5)
create table audit_log (
    id                  uuid primary key,
    workspace_id        uuid not null references workspace (id),
    occurred_at         timestamptz not null default now(),
    actor_type          text not null check (actor_type in ('USER', 'SYSTEM', 'INTEGRATION', 'APPROVER')),
    actor_member_id     uuid,
    actor_label         text,
    action              text not null,
    outcome             text not null check (outcome in ('SUCCESS', 'DENIED', 'FAILED')),
    target_type         text not null,
    target_id           uuid,
    business_entity_id  uuid,
    channel_account_id  uuid,
    request_id          text,
    reason              text,
    before_state        jsonb,
    after_state         jsonb,
    metadata            jsonb
);
create index audit_log_target_idx on audit_log (workspace_id, target_type, target_id, occurred_at desc);
create index audit_log_ws_time_idx on audit_log (workspace_id, occurred_at desc);
create index audit_log_actor_idx on audit_log (workspace_id, actor_member_id, occurred_at desc);

create function forbid_mutation() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only', tg_table_name using errcode = 'insufficient_privilege';
end $$;
create trigger audit_log_append_only before update or delete on audit_log
    for each row execute function forbid_mutation();
create trigger audit_log_no_truncate before truncate on audit_log
    for each statement execute function forbid_mutation();

-- Outbox: 업무 데이터와 같은 트랜잭션에 기록하고 커밋 후 전달(at-least-once)
create table outbox_event (
    id                  uuid primary key,          -- event_id
    workspace_id        uuid not null references workspace (id),
    business_entity_id  uuid,
    aggregate_type      text not null,
    aggregate_id        uuid not null,
    event_type          text not null,
    schema_version      integer not null default 1,
    payload             jsonb not null,
    occurred_at         timestamptz not null default now(),
    correlation_id      text,
    source_event_id     uuid,
    status              text not null default 'PENDING' check (status in ('PENDING', 'DISPATCHED', 'FAILED')),
    attempts            integer not null default 0,
    available_at        timestamptz not null default now(),
    dispatched_at       timestamptz,
    last_error          text
);
create index outbox_event_pending_idx on outbox_event (available_at) where status = 'PENDING';

-- 소비자별 처리 기록(중복 전달 시 업무 적용을 1회로 제한)
create table event_consumption (
    consumer      text not null,
    event_id      uuid not null,
    processed_at  timestamptz not null default now(),
    primary key (consumer, event_id)
);

-- 비동기 작업 (docs/02 §3 상태, DEC-F10)
create table async_job (
    id                    uuid primary key,
    workspace_id          uuid not null references workspace (id),
    business_entity_id    uuid,
    channel_account_id    uuid,
    connection_id         uuid,
    job_type              text not null,
    status                text not null default 'PENDING'
                          check (status in ('PENDING', 'RUNNING', 'SUCCEEDED', 'RETRY_WAIT', 'REVIEW_REQUIRED', 'FAILED')),
    payload               jsonb not null default '{}'::jsonb,
    result                jsonb,
    error_code            text,
    error_message_ko      text,
    attempts              integer not null default 0 check (attempts >= 0),
    max_attempts          integer not null default 5 check (max_attempts >= 1),
    available_at          timestamptz not null default now(),
    lease_expires_at      timestamptz,
    locked_by             text,
    progress_total        integer,
    progress_done         integer,
    created_by_member_id  uuid,
    created_at            timestamptz not null default now(),
    started_at            timestamptz,
    finished_at           timestamptz,
    updated_at            timestamptz not null default now(),
    unique (workspace_id, id)
);
create index async_job_runnable_idx on async_job (available_at) where status in ('PENDING', 'RETRY_WAIT');
create index async_job_lease_idx on async_job (lease_expires_at) where status = 'RUNNING';
create index async_job_connection_idx on async_job (connection_id, created_at desc);

-- 멱등키 (DEC-F12)
create table idempotency_record (
    workspace_id     uuid not null references workspace (id),
    member_id        uuid not null,
    idem_key         text not null check (length(idem_key) between 8 and 200),
    request_method   text not null,
    request_path     text not null,
    request_hash     bytea not null,
    response_status  integer not null,
    response_body    jsonb,
    created_at       timestamptz not null default now(),
    expires_at       timestamptz not null,
    primary key (workspace_id, member_id, idem_key)
);
create index idempotency_record_expiry_idx on idempotency_record (expires_at);

-- 업로드/생성 파일 메타데이터. 본문은 객체 저장소, 원본 불변(DEC-F15)
create table stored_file (
    id                  uuid primary key,
    workspace_id        uuid not null references workspace (id),
    business_entity_id  uuid,
    purpose             text not null check (purpose in ('GENERIC_IMPORT', 'SETTLEMENT_SOURCE', 'VAT_SOURCE', 'CAPABILITY_EVIDENCE', 'EXPORT')),
    original_filename   text not null check (length(original_filename) between 1 and 255),
    content_type        text not null,
    size_bytes          bigint not null check (size_bytes >= 0),
    sha256              char(64) not null check (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key         text not null unique,
    scan_status         text not null check (scan_status in ('CLEAN', 'INFECTED', 'SCAN_UNAVAILABLE')),
    contains_pii        boolean not null default false,
    uploaded_by         uuid,
    created_at          timestamptz not null default now(),
    expires_at          timestamptz,
    unique (workspace_id, id)
);
create index stored_file_hash_idx on stored_file (workspace_id, sha256);
create trigger stored_file_immutable before update on stored_file
    for each row execute function forbid_mutation();

-- 사용자별 저장된 보기(docs/01 §2)
create table saved_view (
    id            uuid primary key,
    workspace_id  uuid not null,
    member_id     uuid not null,
    screen_key    text not null check (screen_key ~ '^[a-z0-9_.-]{1,100}$'),
    name          text not null check (length(name) between 1 and 100),
    filters       jsonb not null default '{}'::jsonb,
    columns       jsonb not null default '[]'::jsonb,
    sort          jsonb not null default '[]'::jsonb,
    is_default    boolean not null default false,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    version       integer not null default 1,
    unique (member_id, screen_key, name),
    foreign key (workspace_id, member_id) references workspace_member (workspace_id, id)
);
create unique index saved_view_one_default on saved_view (member_id, screen_key) where is_default;
