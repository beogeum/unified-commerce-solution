-- 반품 검수 기한(DLG-040 '입고 후 2영업일' → DLG-244): 영업 달력(DLG-239) 기준 n번째 영업일 마감 시각.
alter table return_receipt add column inspection_due_at timestamptz;
create index return_receipt_inspection_due_idx on return_receipt (inspection_due_at) where status in ('RECEIVED', 'INSPECTING');
