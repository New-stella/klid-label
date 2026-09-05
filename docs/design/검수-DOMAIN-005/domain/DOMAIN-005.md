---
logicraft_item: DOMAIN-005
type: domain
version: 16
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-03T07:39:15.569Z
status: CHANGED
prev_version: 15
content_hash: 8041df8c66a64a63d5b6be979b37c0c6b216d09699b7844d12e19874db847e39
stale: false
raw: ./_raw/DOMAIN-005.json
links:
  based_on: ["[[ADR-002]]"]
  collaborates_with: ["[[DOMAIN-001]]", "[[DOMAIN-006]]", "[[DOMAIN-010]]"]
  applies_to_backward: ["[[NFR-008]]", "[[NFR-009]]", "[[NFR-010]]", "[[NFR-011]]", "[[NFR-018]]", "[[NFR-019]]"]
  belongs_to_domain_backward: ["[[AC-1036]]", "[[AC-1037]]", "[[AC-1038]]", "[[AC-1039]]", "[[AC-1040]]", "[[AC-1041]]", "[[AC-1042]]", "[[ADR-002]]", "[[ADR-015]]", "[[API-008]]", "[[API-009]]", "[[API-010]]", "[[API-011]]", "[[API-012]]", "[[API-013]]", "[[API-014]]", "[[API-015]]", "[[API-016]]", "[[API-017]]", "[[API-102]]", "[[API-103]]", "[[API-104]]", "[[API-105]]", "[[API-138]]", "[[API-178]]", "[[CDIAG-006]]", "[[CDIAG-014]]", "[[CMP-005]]", "[[DFEAT-021]]", "[[DFEAT-023]]", "[[DFEAT-024]]", "[[DFEAT-025]]", "[[DFEAT-049]]", "[[DFEAT-054]]", "[[ERD-015]]", "[[ERD-023]]", "[[ERD-030]]", "[[EVT-006]]", "[[SCREEN-018]]", "[[SCREEN-019]]", "[[SD-001]]", "[[SD-005]]", "[[SEQ-015]]", "[[SEQ-023]]", "[[UC-010]]", "[[UC-022]]", "[[UC-023]]"]
  collaborates_with_backward: ["[[DOMAIN-001]]", "[[DOMAIN-006]]", "[[DOMAIN-007]]", "[[DOMAIN-010]]", "[[DOMAIN-017]]"]
  derived_domain_backward: ["[[AC-1040]]", "[[AC-1041]]", "[[AC-1042]]"]
  implements_in_backward: ["[[MOD-009]]", "[[MOD-010]]", "[[MOD-011]]", "[[MOD-043]]", "[[MOD-047]]"]
  references_backward: ["[[ADR-002]]", "[[TEST-002]]", "[[TEST-004]]", "[[TEST-006]]", "[[TEST-007]]", "[[TEST-008]]"]
---

# 검수

## name

검수

## brownfield

### notes

1차 도메인(1차/2차 2단계 검수) 대비 단순화됨 — 2차는 단일 검수 워크플로우다(ADR-002)

### status

modified

### decided_by

ADR-002

## description

작업자가 제출한 라벨링 결과를 REVIEWER 가 검토해 승인·반려하는 도메인.

[★단일 검수 — 1차/2차 단계 폐기(ADR-002)] 구 서술의 '1차 검수 → 2차 검수' 2단계 워크플로우는 폐기됐다. 검수자 1인이 승인할 때까지 반려↔재제출을 반복하는 단일 검수다. 마찬가지로 '관리자 확인 요청'도 두지 않는다 — 검수는 검수자 1인의 판단으로 종결한다. ⚠ 당초 근거였던 '별도 ADMIN 역할이 없어 확인을 요청할 상대가 없다'(ADR-003)는 전제는 무효다 — ADR-055 가 관리자 역할을 신설해 그 결정을 뒤집었고, 관리자는 검수자 권한을 계층으로 물려받는다. 전제가 무효가 된 것과 기능을 되살리는 것은 다른 축이라 '관리자 확인 요청'을 두지 않는다는 결론은 그대로 유지한다. 재도입 여부는 ADR-060 이 결정했다 — 관리자 역할이 신설된 뒤에도 「관리자 확인 요청」 창구를 두지 않는다. 가장 큰 근거는 겸직이다 — 관리자는 검수자 권한을 계층으로 물려받아 검수·배정을 그대로 수행하므로 검수 판단에 관한 한 올려보낼 위가 없다. 즉 이 창구는 대체된 것이 아니라 성립하지 않는다. ⚠ 이 근거가 덮는 것은 판단을 위로 올리는 축뿐이다. 1차 기능의 「프레임 잠금 + 폐기」 축은 권한이 아니라 워크플로이며 현재는 검수 반려로 수렴한다. 되살릴 필요가 생긴다면 그것은 그 축을 되살리는 것이 아니라 폐기 흐름 자체를 다시 논의하는 것이다. 검수자↔작업자 소통은 반려·문의를 통합한 이슈 스레드(DFEAT-049)가 대신한다.

[★검수 완료 = 작업 완료] 승인(APPROVED) 시점이 작업의 종결이다. 이 시점에 LS_RAW_DATA_STATUS.DATA_STTS_CD 가 APPROVED 로 전이한다 — 검수 종결값은 APPROVED 이며 COMPLETED 로 전이하지 않는다. COMPLETED 로 전이하면 승인된 영상이 데이터마트에 한 건도 노출되지 않기 때문이다. 같은 이름의 COMPLETED 가 배치 단계 축(LS_DATA_RAW.DATA_STTS_CD)에 별도로 존재하나 그것은 배치 처리 완료를 뜻하는 다른 축이며, 배치 완료가 검수 워크플로 상태로 점프하지 않는다(점프하면 검수 제출 ASSIGNED→PENDING 이 상태머신에서 차단된다).

[승인이 촉발하는 연쇄] ① 라벨 전체 스냅샷 생성(LS_LABEL_VERSION, SAVE_REASON_CD=APPROVED) — 학습데이터 버전은 저장 시점이 아니라 승인 시점에만 쌓인다 ② export 폴더 새 버전 전량 재생성 ③ export 성공 후 관제 TASK_COMPLETED 통지. 이벤트 체인은 ReviewApproved(EVT-006) → DatasetExportCompleted(EVT-009) → TaskCompleted(EVT-003) 이다.

[승인 후 수정] 동일 작업 ID 를 유지한 채 재검수되며 재승인 시 새 버전이 쌓인다. 수정 경로는 그 영상을 재검수 대상으로 표시할 뿐이고, export 새 버전 폴더 재생성과 TASK_MODIFIED 발송은 검수자가 그 수정을 다시 승인한 시점에 일어난다.

[검수 대상 밖] 증강·해상도 파생 중 해상도(RESL_*)는 내부 생성물이라 accept/reject 가 차단된다. 증강 결과의 사용·폐기 결정은 별도 축(LS_DATA_AUG_RVW)이다.

## upstream_of

_(empty)_

## context_kind

core

## collaborators

- DOMAIN-010
- DOMAIN-001
- DOMAIN-006

## attached_files

_(empty)_

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: 검수
- **meaning**: 검수자 1인이 승인할 때까지 반려↔재제출을 반복하는 단일 절차. 1차/2차 단계 구분은 폐기됐다

### [2]

- **term**: 승인(APPROVED)
- **meaning**: 작업 종결 시점. 라벨 스냅샷 생성 → export 재생성 → 관제 통지의 시작점이다

### [3]

- **term**: 반려
- **meaning**: 작업자에게 되돌려 재작업을 요청. 사유는 이슈 스레드로 소통한다

### [4]

- **term**: 프레임 상태 색상
- **meaning**: 연두=라벨 저장됨, 빨강=문의 제기됨. 주황(반려)은 반려가 영상 단위여서 특정 프레임에 매핑되지 않아 프레임 색으로 표시되지 않는다. 모두 연두일 때만 제출 가능

### [5]

- **term**: 검수 이력
- **meaning**: 승인·반려 이력. 라벨 변경 이력(LS_DATA_LBL_HSTRY)과는 별개 축이다
