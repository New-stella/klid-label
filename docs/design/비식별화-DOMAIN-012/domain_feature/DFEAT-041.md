---
logicraft_item: DFEAT-041
type: domain_feature
version: 12
domain: DOMAIN-012
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.724Z
status: NEW
prev_version: null
content_hash: cd3b3ba0e91f9176846b14d842e414e0c02d9abc19598a3c1e117f8243e2caab
stale: false
raw: ./_raw/DFEAT-041.json
links:
  belongs_to_domain: ["[[DOMAIN-012]]"]
  consumes: ["[[EVT-007]]"]
  implements: ["[[API-112]]"]
  specializes: ["[[FEAT-005]]"]
  verifies: ["[[AC-011]]"]
  depicts_backward: ["[[CDIAG-003]]", "[[CMP-003]]"]
  realizes_backward: ["[[MOD-049]]", "[[UC-011]]"]
  references_backward: ["[[CDIAG-003]]"]
---

# 비식별 처리 위탁·결과 저장

## title

비식별 처리 위탁·결과 저장

## status

designed

## consumes

- EVT-007

## priority

must

## triggers

_(empty)_

## brownfield

### notes

2026-07-30: refreshExisting 강제 재생성 + resolve fail-closed 게이트(경로실존+시점비교) 반영. 2026-08-06: AC-011 정합 — 비식별 대상 게이팅 폐지(전체 영상)·논블로킹 제출·KPST 폴링 단일화(구 콜백 경로 제거)·자동 재비식별 큐 폐기 반영.

### status

new

### decided_by

ADR-006

## description

전체 영상(PRVC/PSDO/ANONY — PRVC_TYPE_CD 게이팅 폐지)을 외부 KPST 비식별 솔루션에 위탁하고 결과 비식별본을 STORAGE_DEIDENTIFIED_PATH 에 저장하며 원본을 보존한다. 비식별은 파이프라인 선두 단계로, 적재 완료(VideoIngestedEvent, 커밋 이후 비동기)를 신호로 자동 시작하고 REVIEWER 의 재비식별 요청(POST /v1/videos/{rawSn}/redeident, 202 Accepted)도 진입점으로 둔다. 성공 시 LS_DATA_RAW.DATA_STTS_CD 가 MARKING_READY 로 전이해 비식별 영상 대상 마킹이 허용된다.

[제출은 논블로킹] 비식별 위탁은 WAITING 원장 행을 선커밋한 뒤 프로젝트 생성을 비동기 디스패치한다 — 파이프라인 스레드를 붙잡지 않는다. 결과는 별도 콜백이 아니라 주기 폴링 배치가 진행을 조회해 회수하며, 아무 신호도 없으면 미결 스위퍼가 유일한 회수 경로다(ACK 창 기본 30분 · 콜백 창 기본 360분을 구분해 정상 위탁을 빼앗지 않는다).

[결과 경로] 결과 파일 경로는 솔루션이 통보한 값을 LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM 에 그대로 기록한다 — 파일명을 조합하거나 추측하지 않는다(mock=deidentified.mp4 / KPST={원본stem}-mask{ext} 로 영상마다 다르다).

[실패·신고] 실패 시 LS_DATA_RAW.DE_IDENT_YN='F'(뷰 노출명 DE_IDNTF_YN) 마킹 + 원본 절대 삭제 금지. 자동 재비식별 큐는 폐기됐고 외부 솔루션에서 수동 재비식별한 뒤 resolve 로 해소한다. 비식별 누락 신고 구간에는 외부 위탁을 개시하지 않고 보류한다. 재비식별 시 강제 재생성(refreshExisting=true)하며, 신고 해소(resolve) 시 산출물 실재 검증(최신 SUCCESS 비식별 procLog 경로 실존 + 시점 비교)이 fail-closed 로 통과를 판정한다(원본 폴백 없음).

[폐기된 구 서술] ①'PRVC/PSDO 영상에 대해서만 위탁' — 게이팅은 폐지됐다. ②'콜백 POST /v1/deidentify/result 로 결과 수신' — 그 콜백 경로는 두지 않는다. 비식별 결과 수신은 KPST 폴링으로 단일화한다. ③'실패 시 재시도 큐' — 자동 재비식별 큐는 폐기됐다. 수동 블러(DFEAT-013)는 폐기되고 외부 솔루션 연동으로 대체됨(ADR-006).

## invokes_apis

_(empty)_

## implementation

### status

planned

### modules

_(empty)_

### records

_(empty)_

### progress

0

### subtasks

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DEIDENT_PROC_LOG

## related_acceptances

- AC-011

## specializes_feature

FEAT-005

## implemented_by_endpoints

- API-112

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
