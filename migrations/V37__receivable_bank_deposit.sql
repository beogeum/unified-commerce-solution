-- B2B 수금 ↔ 은행 입금 연결(DLG-245): 같은 입금을 채널 정산과 B2B 수금에 이중으로 쓰지 않는다.
alter table receivable_entry add column bank_deposit_id uuid;
alter table receivable_entry add constraint receivable_entry_deposit_fk foreign key (workspace_id, bank_deposit_id) references bank_deposit (workspace_id, id);
alter table receivable_entry add constraint receivable_entry_deposit_ck check (bank_deposit_id is null or entry_type = 'PAYMENT');
create index receivable_entry_deposit_idx on receivable_entry (bank_deposit_id) where bank_deposit_id is not null;
