---
logicraft_item: DOMAIN-015
type: domain
version: 6
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:57.796Z
status: NEW
prev_version: null
content_hash: b821330f6f2dbc6d515fd1a10d6d516003011ef6ff95bc2cf84e42c51d340524
stale: false
raw: ./_raw/DOMAIN-015.json
links:
  applies_to_backward: ["[[NFR-011]]"]
  belongs_to_domain_backward: ["[[ADR-038]]", "[[API-070]]", "[[API-071]]", "[[API-072]]", "[[API-073]]", "[[API-116]]", "[[API-136]]", "[[API-137]]", "[[API-187]]", "[[CDIAG-007]]", "[[DFEAT-006]]", "[[ERD-014]]", "[[SCREEN-012]]", "[[SD-003]]", "[[SEQ-017]]", "[[STATE-001]]", "[[UC-029]]"]
  implements_in_backward: ["[[MOD-014]]"]
---

# 작업 배정

## name

작업 배정

## brownfield

### status

modified

### decided_by

ADR-001

### change_kind

- scope-shrink
- redesign

### diff_summary

1차 프로젝트 단위 배정 → 2차 영상 단위 배정(프로젝트 폐기). DFEAT-006 과 정합. [추정 — 1차 배정 도메인 경계 확인 필요]

## description

REVIEWER 가 WORKER 에게 영상 단위 작업을 배정·재배정하는 도메인. LS_TASK_ALTMNT(TASK_TYPE_CD='LABELER') INSERT, 재배정 시 작업 이벤트 로그에 기록. 배정 이력 조회·재배정 권한도 REVIEWER 가 보유하며 TaskBoard 로 작업 현황을 조회한다.

[작업 상태 소유권] LS_RAW_DATA_STATUS.DATA_STTS_CD 가 작업·검수 워크플로우 상태를 소유한다(배치 단계 축인 LS_DATA_RAW.DATA_STTS_CD 와 별개). 배정 시점에 생성되고 배치 완료 시 ASSIGNED 로 복귀하며, 검수 승인 시 APPROVED 로 종결한다(이 축에 COMPLETED 로 전이하는 경로는 없다).

[★목록 정렬·필터 정책 (2026-07-29 확정, 구속)] 정렬은 시간축 단일 기준이며 상태 우선순위를 ORDER BY CASE 로 섞지 않는다 — '지금 처리할 것'은 필터·KPI 카드로 표현한다(CVAT·Label Studio 관행). 필터·집계는 BE 에서 전체 기준으로 처리하고 정렬 키는 allowlist 매핑으로만 해석한다(CWE-89/770). ⚠ 미등록 정렬키의 응답은 엔드포인트별로 다르다 — 작업목록(/v1/tasks/board*)은 strict(400), 검수목록(/v1/reviews*)은 lenient(200 + 기본정렬 폴백). 근거는 '변경 전에 그 엔드포인트가 200 이었는가'이며 일관성을 이유로 통일하지 말 것.

[★파생영상 등재 게이트] 증강 파생은 REVIEWER 가 사용하기로 결정한 것만 작업목록·배정에 등재된다. 판정축은 LS_DATA_AUG_RVW.RVW_STTS_CD(검수 결정)이며 생성 결과축(AUG_PROC_STTS_CD)이 아니다 — 생성 성공만으로 통과시키면 게이트가 무의미해진다. ⚠ 해상도 파생(RESL_*)은 검수 대상이 아니라 리뷰 행이 영영 생기지 않으므로 통과 예외를 명시적으로 박는다. ⚠ 게이트 도입 이전 파생은 그랜드퍼더링한다 — 이미 배정된 WORKER 의 영상이 화면에서 사라지면 고아 배정이 된다.

## upstream_of

_(empty)_

## context_kind

core

## collaborators

_(empty)_

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: 배정(Assignment)
- **meaning**: REVIEWER→WORKER 영상 단위 작업 할당

### [2]

- **term**: 재배정
- **meaning**: 배정 변경 — 작업 이벤트 로그에 이력 기록

### [3]

- **term**: TaskBoard
- **meaning**: 작업 현황 목록. 배치 상태 축(status)과 워크플로 축(workStatus)을 별도 파라미터로 구분한다

### [4]

- **term**: 등재 게이트
- **meaning**: 파생영상이 작업목록에 등재될 자격. 검수 결정축(리뷰 행)으로 판정하며 해상도 파생은 예외다
