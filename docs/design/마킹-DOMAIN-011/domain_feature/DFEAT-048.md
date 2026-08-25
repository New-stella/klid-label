---
logicraft_item: DFEAT-048
type: domain_feature
version: 16
domain: DOMAIN-012
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T09:53:10.146Z
status: CHANGED
prev_version: 16
content_hash: 63ad0d6f622446bf9ea6525cfb91ce0b6763f810d6b5e6a589bf67e29b7b72b6
stale: true
raw: ./_raw/DFEAT-048.json
links:
  belongs_to_domain: ["[[DOMAIN-012]]"]
  implements: ["[[API-032]]", "[[API-091]]", "[[API-094]]", "[[API-109]]", "[[API-202]]"]
  specializes: ["[[FEAT-005]]"]
  triggers: ["[[EVT-007]]"]
  verifies: ["[[AC-016]]", "[[AC-019]]"]
  depicts_backward: ["[[CDIAG-003]]", "[[CMP-003]]"]
  realizes_backward: ["[[MOD-049]]", "[[UC-016]]"]
---

# 비식별 누락 신고

## title

비식별 누락 신고

## status

designed

## consumes

_(empty)_

## priority

should

## triggers

- EVT-007

## brownfield

### notes

검증 외부화에 따른 잔존 안전망 — 신고 기능 명시화 (검토 화면/조회 API 폐기와 동반)

### status

new

### decided_by

ADR-022

## description

작업자가 개인정보 노출(비식별 누락)을 발견하면 신고한다 → LS_DEIDENT_REPORT(OPEN) 적재 + 작업락 선점 + LS_DATA_RAW.DE_IDENT_YN='F'(뷰 노출명 DE_IDNTF_YN) 마킹. 신고 단계는 DCLR_STP_CD(V171)에 기록한다.

[★라벨도 개인정보 판정도 지우지 않는다 — 구 정책 2건 폐기] ①'해당 영상 전체 라벨 삭제(삭제 전 스냅샷 보존)' → 2026-07-27 폐기. 신고는 비식별이 잘못됐다는 신호일 뿐 라벨 작업 결과를 폐기할 근거가 아니며, 그 스냅샷은 DATA_SRC_SN=NULL(영상 스코프)이라 srcSn 스코프 조회·롤백 진입점이 없는 write-only 이력이었다. 스냅샷도 남기지 않는다. ②'개인정보 3필드(익명/가명/PII 포함여부)를 프레임 축(LS_DATA_SRC, V130)·영상 축(LS_DATA_RAW, V163) 둘 다 null 로 리셋' → 2026-08-04 폐기. 같은 취지로 사람이 입력한 판정도 라벨과 같은 작업 결과이므로 보존하고, resolve 후 기존 판정을 그대로 이어서 진행한다. 리셋 감사 이벤트 타입(PRIVACY_META_RESET)은 신규 발생이 없지만 과거 행 판독을 위해 존치한다.

[차단은 412 로 통일한다] 신고 구간에는 라벨 조회·이력, 라벨 저장(PUT /v1/frames/{srcSn}/labels), 라벨 객체 속성값 조회·저장, 버전 diff·롤백, 프레임 이미지, 포털 라벨 조회·저장·포털 프레임 이미지, 개인정보 메타 PUT(영상 축·프레임 축), 이벤트 어노테이션 저장·승인·반려, 검수 승인, 관제 조회 API 라벨 본문이 412 로 차단되고 영상 스트리밍만 404 다(그 엔드포인트의 기존 규약. 신고 여부로 404/412 가 갈리면 응답이 영상 상태 오라클이 된다 — CWE-209). ⚠ 구 서술 '저장·수정은 기존 작업락으로 409 차단' 은 2026-08-04 폐기 — 작업락은 6시간 만료 후 회수되는데 'F' 는 resolve 까지 남아 조회 412 대 저장 200 비대칭이 열렸고, full-replace 계약상 빈 목록 저장이 기존 라벨을 전량 삭제했다. 게이트는 락 검사보다 먼저 평가하며, 409 는 신고와 무관한 락(트랙 병합 등)에만 남는다. 게이트가 걸린 미디어 응답은 Cache-Control: no-store 이고, FE 는 게이트가 닫히는 변화에 removeQueries 로 캐시를 제거한다.

[해소·단계별 재개] 자동 재비식별 큐는 폐기됐다. 작업자/검수자가 외부 비식별 솔루션으로 수동 비식별화한 뒤 POST /v1/deident-reports/{rprtSn}/resolve(WORKER 본인 배정/REVIEWER 전체)로 해소한다. resolve 는 OPEN→RESOLVED 조건부 UPDATE 로 원자 클레임하고 영향행수 1 을 받은 성공자만 작업락 해제·'F'→'Y' 복원·재개를 수행한다(2노드 동시 통과로 프레임 재추출이 2회 기동하던 것을 막는다). 복원으로 게이트가 자동 해제되어 보존된 기존 라벨을 그대로 재사용한다(별도 복원 API 없음). ★재개 지점은 신고 단계로 갈린다 — MARKING 은 배치 단계를 MARKING_READY 로 되감고 활성 마킹을 SKIPPED 로 종결해 재마킹 충돌을 풀며, LABELING 은 기존 LS_DATA_SRC 행을 dirty-update 로 갱신해 SRC_SN 을 보존한 채 프레임 이미지만 재추출한다(라벨 좌표 보존). 단계 미상(컬럼 신설 이전 신고)은 백필하지 않고 재개 이벤트도 발행하지 않는다.

[접수 조건] 마킹 단계 신고(rawSn 기준, POST /v1/videos/{rawSn}/deident-report)는 배치 단계가 MARKING_READY 일 때만 접수한다(그 외 412 — 사유를 구분해 알리지 않는다). 그 상태는 선두 비식별 성공 직후·마킹 이전이라 프레임 행도 라벨도 아직 없어 재마킹이 파괴할 작업 결과가 없기 때문이다. 라벨링 단계 신고(srcSn 기준, POST /v1/labels/{srcSn}/deident-report)는 배치 단계와 무관하지만 한 번이라도 검수가 승인된 이력이 있는 영상은 접수하지 않는다(412) — 판정 축은 지금 상태가 아니라 승인 이력이며, 승인된 학습데이터는 되돌리지 않는다. ⚠ 구 서술 '검수 완료 영상 신고가 정상 동선' 은 2026-08-10 폐기. 차단은 신규 접수에만 걸리며 이미 접수된 신고의 해소는 그대로 열어 둔다 — 막으면 그 영상이 작업락과 'F' 로 영구히 남는다. 잔여 위험은 인지·수용한다 — 승인 영상은 조치 수단이 0 이다: 신고가 412 로 막히는 데 더해 재비식별 요청도 이미 막혀 있고(비식별을 마친 영상을 배제하는 가드가 따로 있으며 그 사유는 개인정보가 아니라 프레임 번호 의미 불일치다) 화면의 재비식별 버튼도 뜨지 않는다. 정상 완주한 승인 영상은 모두 그 조건에 해당하므로 잔존 개인정보를 발견해도 외부 노출을 멈출 수단이 없다. 보완책으로 승인 영상에 재비식별을 여는 안을 검토했으나 시나리오가 과도하게 복잡해져 채택하지 않았다 — 발견은 승인 전에 이뤄지는 것이 전제다. 그래서 거부된 신고의 사유는 정제해 감사 로그로 남긴다: 신고 행도 검수자 알림도 생기지 않는 경로라 로그가 사실을 보존하는 유일한 수단이다. ⚠ 파생영상(ORGNL_RAW_SN non-null)은 신고를 접수하지 않고(412) 원본의 신고도 파생에 전파되지 않는다 — 파생본을 다시 비식별할 수단이 없기 때문이며 원본으로 유도하지도 않는다.

[채널] 신고는 마킹 화면과 라벨링 화면 두 채널에서 접수하며 두 채널 모두 화면에 신고 진입점을 둔다. 비식별 검증 본체는 외부 비식별 솔루션으로 이관됐고(FEAT-006 은 폐기가 아니라 이 경량 상태·이력 확인 축으로 축소·재정의됐다 — 실제로 폐기된 것은 UC-012·SCREEN-016/017·API-051/052 다) 저작도구는 이 신고 안전망만 잔존 보유한다.

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-095

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-25T01:21:30.495Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DEIDENT_REPORT

## related_acceptances

- AC-016
- AC-019

## specializes_feature

FEAT-005

## implemented_by_endpoints

- API-091
- API-032
- API-094
- API-109
- API-202

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
