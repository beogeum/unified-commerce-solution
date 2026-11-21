# 역할-권한 매트릭스 (초안)

- 상태: **초안 — 제품 오너·보안 승인 필요(OQ-026)**. 코드: `backend/src/main/java/com/commerceos/identity/access/SystemRoles.java`(운영그룹 생성 시 역할로 복사).
- 역할은 권한 묶음일 뿐이며 사업자·판매계정 **범위는 사용자별 접근정책**으로 따로 제한한다(ADR-0002).
- ★ = 민감 권한(docs/08 §1). `order.*`, `claim.refund`, `inventory.*`, `pii.read`, `cost.read`, `settlement.read`, `profit.read`, `period.close` 는 후속 모듈에서 사용하며 지금은 카탈로그에만 정의되어 있다.

| 권한 | 이름 | 최고관리자 | 운영관리자 | 상품/MD | 주문담당 | CS담당 | 물류관리자 | 창고작업자 | 정산/재무 | 분석읽기전용 | 일반읽기전용 | 연동관리자 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| `access.manage` ★ | 사용자·권한 관리 | ● |  |  |  |  |  |  |  |  |  |  |
| `member.read` | 사용자 목록 조회 | ● | ● |  |  |  |  |  |  |  |  |  |
| `channel_account.read` | 판매계정·판매단위 조회 | ● | ● | ● | ● | ● | ● |  | ● | ● | ● | ● |
| `channel_account.manage` | 판매계정·판매단위 등록·변경 | ● | ● |  |  |  |  |  |  |  |  | ● |
| `channel_account.reassign` ★ | 판매계정 사업자 귀속 변경 | ● |  |  |  |  |  |  |  |  |  |  |
| `logistics_account.read` | 물류사·화주계정 조회 | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● |
| `logistics_account.manage` | 화주계정 등록·변경 | ● | ● |  |  |  | ● |  |  |  |  | ● |
| `integration.read` | 연동 상태·지원 기능 조회 | ● | ● | ● | ● | ● | ● |  | ● | ● | ● | ● |
| `integration.manage` | 연동 연결 등록·변경·일시중지 | ● | ● |  |  |  |  |  |  |  |  | ● |
| `integration.credential.manage` ★ | 연동 인증정보 등록·교체 | ● |  |  |  |  |  |  |  |  |  | ● |
| `integration.capability.verify` ★ | 지원 기능 검증 결과 기록 | ● |  |  |  |  |  |  |  |  |  | ● |
| `integration.sync` | 연동 수집·점검 실행 | ● | ● |  |  |  | ● |  |  |  |  | ● |
| `work_item.read` | 업무함 조회 | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● |
| `work_item.update` | 업무 처리·배정 | ● | ● |  | ● | ● | ● |  |  |  |  | ● |
| `audit.read` ★ | 감사이력 조회 | ● | ● |  |  |  |  |  | ● |  |  | ● |
| `export.create` ★ | 데이터 내보내기 | ● | ● | ● | ● |  | ● |  | ● | ● |  |  |
| `file.upload` | 파일 업로드 | ● | ● | ● | ● |  | ● |  | ● |  |  | ● |
| `file.read` | 업로드 파일 조회·다운로드 | ● | ● | ● | ● |  | ● |  | ● |  |  | ● |
| `order.read` | 주문 조회 | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● | ● |
| `order.update` | 주문 변경 | ● | ● |  | ● | ● |  |  |  |  |  |  |
| `claim.refund` ★ | 환불 실행 | ● |  |  |  |  |  |  |  |  |  |  |
| `inventory.adjust` ★ | 재고 조정 | ● |  |  |  |  | ● |  |  |  |  |  |
| `inventory.dispose` ★ | 재고 폐기 | ● |  |  |  |  |  |  |  |  |  |  |
| `pii.read` ★ | 고객 개인정보 원문 조회 | ● |  |  | ● | ● |  |  |  |  |  |  |
| `cost.read` ★ | 원가·비용 조회 | ● |  | ● |  |  |  |  | ● | ● |  |  |
| `settlement.read` ★ | 정산 조회 | ● |  |  |  |  |  |  | ● | ● |  |  |
| `profit.read` ★ | 손익 조회 | ● |  |  |  |  |  |  | ● | ● |  |  |
| `period.close` ★ | 월 마감·재개방 | ● |  |  |  |  |  |  | ● |  |  |  |

## 검토가 필요한 배정(초안의 판단 근거)
- `pii.read`: 주문담당·CS담당만. 물류관리자는 3PL 이 배송정보를 처리한다는 전제로 제외 — 자체 WMS 출고 담당에게 필요하면 사용자별 ALLOW 로 부여.
- `access.manage`: 최고관리자만. 운영관리자는 `member.read` 만.
- `bank_account.read/manage`: 정산/재무만.
- `claim.refund`, `inventory.dispose` 는 승인정책(REV-058) 확정 전까지 최고관리자 외 기본 배정 없음. 재무의 `period.close` 는 초안 배정.
- 모든 역할(창고작업자 포함)에 `order.read` 를 둔 것은 CONF-003(기본 주문 조회 허용) 반영.
