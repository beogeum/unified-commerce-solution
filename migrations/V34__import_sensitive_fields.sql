-- 가져오기 민감 필드 보호(수동 주문 파일 일괄 등록, DLG-234~238).
-- 민감 필드(수령인 이름·연락처·주소 등)는 import_row 에 평문으로 두지 않는다: 미리보기용 raw 는 마스킹, 실행용 값은 암호화 묶음.
alter table import_row add column secret_cipher bytea;
alter table import_row add column secret_key_id text;
alter table import_row add constraint import_row_secret_ck check ((secret_cipher is null) = (secret_key_id is null));

-- 원본 파일 본문 삭제 기록(stored_file 은 변경 불가 + 가져오기 배치가 참조하므로 행은 두고 본문만 지운다).
create table stored_file_purge (
    file_id         uuid primary key,
    workspace_id    uuid not null,
    reason          text not null check (reason in ('SENSITIVE_IMPORT_PROCESSED', 'SENSITIVE_IMPORT_EXPIRED')),
    purged_at       timestamptz not null default now(),
    foreign key (workspace_id, file_id) references stored_file (workspace_id, id)
);
create trigger stored_file_purge_append_only before update or delete on stored_file_purge for each row execute function forbid_mutation();
alter table stored_file_purge enable row level security;
create policy workspace_isolation on stored_file_purge using (app_rls_bypass() or workspace_id = app_current_workspace())
    with check (app_rls_bypass() or workspace_id = app_current_workspace());

alter table sales_order add column import_ref text;
create unique index sales_order_import_ref_uq on sales_order (workspace_id, import_ref) where import_ref is not null;

