---
logicraft_item: DOMAIN-015
type: domain
version: 7
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T13:21:52.303Z
status: CHANGED
prev_version: 6
content_hash: b5fcfc9418787af93e77710ebe9998ad8dbe991bc9b6c79721df930f3d733fc2
stale: false
raw: ./_raw/DOMAIN-015.json
links:
  based_on: ["[[ADR-001]]"]
  applies_to_backward: ["[[NFR-011]]"]
  belongs_to_domain_backward: ["[[AC-1075]]", "[[AC-1076]]", "[[ADR-038]]", "[[API-070]]", "[[API-071]]", "[[API-072]]", "[[API-073]]", "[[API-116]]", "[[API-136]]", "[[API-137]]", "[[API-187]]", "[[CDIAG-007]]", "[[CDIAG-026]]", "[[DFEAT-006]]", "[[ERD-014]]", "[[SCREEN-012]]", "[[SD-003]]", "[[SEQ-017]]", "[[STATE-001]]", "[[UC-029]]"]
  collaborates_with_backward: ["[[DOMAIN-017]]"]
  derived_domain_backward: ["[[AC-1075]]", "[[AC-1076]]"]
  implements_in_backward: ["[[MOD-014]]"]
  references_backward: ["[[ADR-067]]"]
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

[★배정의 대상은 작업자뿐 (ADR-067)] 검수자를 영상에 배정하는 절차를 두지 않는다 — 검수는 배정 없이 전체 대기열에서 집어가고 검수 시작·승인·반려의 자격은 역할이 정한다. 따라서 배정을 검수의 인가 축으로 쓰지 않으며 작업 목록에도 검수자 축을 두지 않는다. 배정 원장의 작업 유형 값역에 남아 있는 검수자 값은 이미 적재된 행을 판독하기 위해 존치하는 것이며 새로 만들지 않는다.

[★작업 이벤트 로그의 새 책임 — 검수 점유] 지금 누가 그 영상을 검수 중인지를 전용 컬럼이나 별도 표가 아니라 이 원장의 「검수 시작」 이벤트로 표현한다. 판정은 저장된 값이 아니라 조회 시점 파생이다 — 최신 검수 시작 이벤트가 있고, 그보다 뒤에 승인·반려 같은 종결 이벤트가 없으며, 발생시각에 유예를 더한 시각이 아직 지나지 않았으면 그 이벤트의 행위자가 점유 중이다. 유예는 배포 설정값이며 기본값은 30분이다. 점유를 푸는 별도 동작은 두지 않는다 — 승인·반려가 자기 이벤트를 남겨 풀린다. ⚠ 목록 조회가 행마다 이 판정을 되짚으면 성능이 나빠지므로 영상별 최신 이벤트를 한 번의 조회로 합친다. ⚠ 점유는 잠금이 아니다 — 만료가 있고 승인 시점의 동시성 보호가 실제 방어로 그대로 남으므로 두 축은 서로를 대체하지 않는다.

[★작업 이벤트 로그의 새 책임 — 행위 시점 역할] 이력 항목마다 그 행위를 한 시점의 행위자 역할을 함께 남긴다. 조회 시점에 현재 역할을 다시 읽으면 그 사람의 역할이 바뀔 때 과거 행위의 역할까지 따라 바뀌므로 행위 시점 값을 박으며, 허용값에 관리자를 반드시 넣는다. 이미 적재된 이력은 역할을 복원할 수 없어 비워 둘 수 있고 백필하지 않는다 — 기본값을 주면 지어낸 값이 사실처럼 남는다.

## upstream_of

_(empty)_

## context_kind

core

## collaborators

_(empty)_

## attached_files

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

### [5]

- **term**: 검수 점유
- **meaning**: 검수를 시작한 사람이 그 영상을 잠시 차지한 상태. 전용 컬럼이나 표가 아니라 작업 이벤트 로그의 「검수 시작」 이벤트로 표현하고 조회 시점에 파생 판정하며, 승인·반려가 남기는 종결 이벤트나 유예 경과로 저절로 풀린다. 잠금이 아니므로 승인 시점의 동시성 보호를 대체하지 않는다

### [6]

- **term**: 행위 시점 역할
- **meaning**: 이력 항목에 함께 남는 행위자의 역할. 조회 시점의 현재 역할을 다시 읽은 값이 아니라 그 행위를 한 시점의 값이라, 그 사람의 역할이 나중에 바뀌어도 과거 행위의 역할은 그대로다. 역할 칸이 생기기 전에 쌓인 이력은 비어 있을 수 있으며 백필하지 않는다
