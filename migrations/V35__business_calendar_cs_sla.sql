-- 영업일·영업시간·공휴일과 CS 응답기한 정책(DLG-205 후속 → DLG-239~243).
insert into permission (code, category, name_ko, sensitive) values
 ('calendar.manage', 'OPERATIONS', '영업일·공휴일·CS 응답기한 정책 관리', false);
insert into role_permission (workspace_id, role_id, permission_code)
select r.workspace_id, r.id, 'calendar.manage' from role r where r.is_system and r.code in ('SUPER_ADMIN', 'OPS_ADMIN')
on conflict do nothing;

-- 요일별 영업시간(Asia/Seoul). 행이 없는 요일은 휴무. 운영그룹에 행이 하나도 없으면 기본값(월~금 09:00~18:00).
create table business_hours (
    workspace_id    uuid not null references workspace (id),
    weekday         smallint not null check (weekday between 1 and 7),   -- ISO: 1 월요일 … 7 일요일
    start_time      time not null,
    end_time        time not null,
    primary key (workspace_id, weekday),
    check (end_time > start_time)
);

create table business_holiday (
    id              uuid primary key,
    workspace_id    uuid not null references workspace (id),
    day             date not null,
    name            text not null check (length(name) between 1 and 50),
    created_by      uuid,
    created_at      timestamptz not null default now(),
    unique (workspace_id, day)
);

create table cs_sla_policy (
    workspace_id    uuid primary key references workspace (id),
    mode            text not null check (mode in ('CALENDAR_HOURS', 'BUSINESS_HOURS')),
    base_hours      integer not null check (base_hours between 1 and 240),
    updated_by      uuid,
    updated_at      timestamptz not null default now(),
    version         integer not null default 1
);

do $$
declare
    t text;
begin
    foreach t in array array['business_hours', 'business_holiday', 'cs_sla_policy']
    loop
        execute format('alter table %I enable row level security', t);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t);
    end loop;
end
$$;
