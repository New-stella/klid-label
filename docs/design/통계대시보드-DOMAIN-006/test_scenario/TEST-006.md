---
logicraft_item: TEST-006
type: test_scenario
version: 3
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T13:21:56.943Z
status: CHANGED
prev_version: 2
content_hash: 8a2c97c2f91ee99d47f312bc1abfa20541b344e7d0a495f2e5a23542a089732d
stale: true
raw: ./_raw/TEST-006.json
links:
  references: ["[[API-014]]", "[[API-250]]", "[[DOMAIN-005]]", "[[DOMAIN-006]]", "[[SCREEN-018]]", "[[SCREEN-019]]", "[[SCREEN-020]]", "[[SCREEN-021]]", "[[UC-023]]", "[[UC-033]]"]
---

# 검수 승인 시 전체·작업자 통계 즉시 반영

## kind

integration

## steps

### [1]

- **seq**: 1
- **action**: 승인 전 통계 스냅샷 조회
- **expected**: 전체 구축 현황의 검수완료 기준 영상 건수와 처리 현황의 승인 건수, 작업자 통계의 완료 건수를 각각 기록한다 — 대상 영상이 아직 그 값에 포함되지 않는다
- **test_item**: 대상 영상이 아직 검수완료 기준 집계에 포함되지 않음을 확인한다
- **input_data**: 대상 영상 rawSn=X, 배정 작업자 workerId=Y
- **preconditions**: 대상 영상이 검수 대기(REVIEW_PENDING) 상태다

### [2]

- **seq**: 2
- **action**: 검수 승인 실행
- **expected**: 대상 영상의 검수 상태가 승인(APPROVED)으로 전이되고, 같은 트랜잭션에서 라벨 전체 스냅샷이 버전으로 적재된다
- **test_item**: REVIEWER 가 검수 상세 화면에서 실제 승인 절차를 수행해 영상을 검수완료 상태로 전이시킨다
- **screen_ref**: SCREEN-019
- **preconditions**: 순번1 스냅샷을 확보했다

### [3]

- **seq**: 3
- **action**: 통계 재조회 — 전체 구축 현황
- **expected**: 검수완료 기준 영상 건수와 처리 현황의 승인 건수가 순번1 대비 정확히 1 증가한다. 전체 누적 영상 건수는 검수 여부와 무관한 전체 기준이므로 변하지 않는다
- **test_item**: 승인 직후 재조회만으로 수치가 갱신되는지 확인한다(별도 대기·재시도 없음)
- **screen_ref**: SCREEN-021
- **preconditions**: 순번2 승인이 완료됐다

### [4]

- **seq**: 4
- **action**: 통계 재조회 — 작업자 통계
- **expected**: 대상 작업자의 완료 건수가 1 증가하고, 오늘 날짜의 일별 완료 항목이 새로 생기거나 1 증가하며, 해당 월의 월별 완료 건수가 1 증가한다
- **test_item**: 승인이 작업자 통계의 완료 건수·일별·월별 집계에 반영되는지 확인한다
- **screen_ref**: SCREEN-020
- **preconditions**: 순번3 확인을 마쳤다

### [5]

- **seq**: 5
- **note**: 한 번에 승인하면 같은 수치 갱신이 여러 건에 동시에 걸린다
- **action**: 여러 건을 한 번에 승인 실행
- **expected**: 1. 결과 창에 성공 건수와 실패 건수가 나뉘어 표시된다. 2. 성공한 건만 목록에서 검수완료로 바뀐다.
- **test_item**: 검수 목록에서 여러 영상을 골라 한 번에 검수완료한다. 그중 한 건은 승인될 수 없는 영상을 섞는다
- **input_data**: 본인이 점유한 검수 대기 영상 세 건과 승인될 수 없는 영상 한 건
- **screen_ref**: SCREEN-018
- **preconditions**: 순번4 확인을 마쳤다. 검수 대기 영상 여러 건을 점유하고 있다

### [6]

- **seq**: 6
- **note**: 요청한 건수가 아니라 실제로 승인된 건수만큼 늘어야 한다
- **action**: 통계 재조회 — 한 번에 승인한 건들의 반영
- **expected**: 1. 검수완료 기준 영상 건수가 순번5 의 성공 건수만큼 늘어난다. 2. 실패한 건은 그 수치에 들어가지 않는다. 3. 기다리거나 다시 시도하지 않아도 한 번의 조회로 갱신된 값이 나온다.
- **test_item**: 한 번에 승인한 건이 모두 반영되고 실패한 건은 반영되지 않는지 확인한다
- **input_data**: 없음
- **screen_ref**: SCREEN-021
- **preconditions**: 순번5 일괄 승인을 마쳤다

## status

draft

## objective

REVIEWER 가 실제 검수 승인 절차(UC-023)를 수행했을 때, 별도 이벤트 전파나 캐시 없이 통계 조회(GET /v1/stats/overall, GET /v1/stats/worker)가 즉시 정합된 수치를 반환하는지 검증한다. DOMAIN-006 은 자체 테이블·도메인 이벤트가 없는 읽기 전용 집계 계층이므로, 이 정합은 요청마다 라이브 테이블을 직접 읽는다는 설계 전제가 실제로 지켜지는지를 검증하는 것이 목적이다. 여러 건을 한 번에 승인하는 경로도 같은 수치 갱신을 한꺼번에 일으키므로, 요청한 건수가 아니라 실제로 승인된 건수만큼만 집계가 느는지 함께 본다.

## related_apis

- API-014
- API-250

## preconditions

- 대상 영상이 검수 대기(REVIEW_PENDING) 상태다
- REVIEWER 로 인증되어 있다
- 한 번에 승인할 검수 대기 영상이 여러 건 있고, 그중 한 건은 승인될 수 없는 영상이다

## verifies_nfrs

_(empty)_

## attached_files

_(empty)_

## related_domains

- DOMAIN-005
- DOMAIN-006

## covers_use_cases

- UC-023
- UC-033

## exercises_screens

- SCREEN-019
- SCREEN-021
- SCREEN-020
- SCREEN-018

## verifies_requirements

_(empty)_
