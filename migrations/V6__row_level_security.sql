-- F8 PostgreSQL 행 수준 보안(RLS) 방어 계층 (ADR-0002, DLG-002)
-- 애플리케이션 접근 범위 검증이 1차 방어이고, 이 정책은 코드 실수로 다른 운영그룹 행이 섞이는 것을 DB 에서 한 번 더 막는다.
--
-- 동작
--   * 앱 연결은 비소유 역할 commerce_app 으로 전환한 뒤 SQL 을 실행한다(TenantAwareDataSource).
--   * app.workspace_id = 현재 운영그룹, app.bypass_rls = 'on' 은 명시적 시스템 작업(로그인·작업자 등)만.
--   * 문맥이 없으면 아무 행도 보이지 않는다(기본 거부). 쓰기도 같은 조건(with check).
--   * 마이그레이션(테이블 소유자)은 RLS 대상이 아니다(force 미사용).
-- 운영 DB 에서 마이그레이션 사용자에게 CREATEROLE 권한이 없으면 DBA 가 commerce_app(NOLOGIN) 을 미리 만들고
-- 마이그레이션 사용자에게 멤버십을 부여해야 한다.

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'commerce_app') then
        create role commerce_app nologin;
    end if;
end
$$;

do $$
begin
    execute format('grant commerce_app to %I', current_user);
exception when others then
    raise notice 'commerce_app membership already granted or not grantable: %', sqlerrm;
end
$$;

grant usage on schema public to commerce_app;
grant select, insert, update, delete on all tables in schema public to commerce_app;
grant usage, select on all sequences in schema public to commerce_app;
revoke all on flyway_schema_history from commerce_app;
alter default privileges in schema public grant select, insert, update, delete on tables to commerce_app;
alter default privileges in schema public grant usage, select on sequences to commerce_app;

create or replace function app_current_workspace() returns uuid
    language sql stable
as $$ select nullif(current_setting('app.workspace_id', true), '')::uuid $$;

create or replace function app_rls_bypass() returns boolean
    language sql stable
as $$ select coalesce(current_setting('app.bypass_rls', true), 'off') = 'on' $$;

-- workspace_id 열이 있는 모든 테이블에 운영그룹 격리 정책
do $$
declare
    t record;
begin
    for t in
        select c.table_name
          from information_schema.columns c
          join information_schema.tables tb on tb.table_schema = c.table_schema and tb.table_name = c.table_name
         where c.table_schema = 'public' and c.column_name = 'workspace_id' and tb.table_type = 'BASE TABLE'
    loop
        execute format('alter table %I enable row level security', t.table_name);
        execute format('drop policy if exists workspace_isolation on %I', t.table_name);
        execute format('create policy workspace_isolation on %I using (app_rls_bypass() or workspace_id = app_current_workspace()) '
                       'with check (app_rls_bypass() or workspace_id = app_current_workspace())', t.table_name);
    end loop;
end
$$;

-- 운영그룹 자체
alter table workspace enable row level security;
create policy workspace_isolation on workspace
    using (app_rls_bypass() or id = app_current_workspace())
    with check (app_rls_bypass() or id = app_current_workspace());
