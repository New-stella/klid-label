---
logicraft_item: DFEAT-039
type: domain_feature
version: 8
domain: DOMAIN-011
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.118Z
status: NEW
prev_version: null
content_hash: 0976f06a88d6af782a20d6f591c28051334e3e0886f63af04d4fa78b7744bbe7
stale: false
raw: ./_raw/DFEAT-039.json
links:
  belongs_to_domain: ["[[DOMAIN-011]]"]
  implements: ["[[API-047]]"]
  triggers: ["[[EVT-001]]"]
  verifies: ["[[AC-027]]", "[[AC-028]]"]
  depicts_backward: ["[[CDIAG-002]]", "[[CMP-002]]"]
  realizes_backward: ["[[MOD-004]]", "[[UC-019]]"]
  references_backward: ["[[CDIAG-002]]"]
---

# 마킹 (자동/수동 이벤트 식별)

## title

마킹 (자동/수동 이벤트 식별)

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

- EVT-001

## brownfield

### status

new

### decided_by

ADR-008

### change_kind

- capability-add

### diff_summary

V2.0 파이프라인 마킹 단계 신규 — 1차 미존재. 설계 타깃 순서: 비식별화(전체 영상, 선두)→마킹(비식별 영상)→VLM 시계열→마킹위치 프레임추출→YOLO/SAM2→트랙보간. 마킹 완료 시 잔여 배치 트리거 — 설계 타깃 순서(비식별 선두→마킹→VLM→프레임추출→YOLO/SAM2→트랙보간)대로 확정·구현되었다(구 divergence 폐기)

## user_story

### as

라벨링 작업자

### i_want

영상에서 이벤트 시점을 자동/수동으로 마킹하기를

### so_that

VLM 시계열 분석과 프레임 추출의 기준을 제공한다

## description

비식별화 완료 후 작업자가 비식별 영상에서 이벤트 시점을 자동/수동으로 마킹한다. 영상별 자동/수동 모드 설정 — 자동=프레임 간격(intervalFrames) 기반 마킹, 수동=작업자 키보드 단축키로 이벤트 시점 마킹. 마킹 완료는 마킹 완료 이벤트를 발행해 잔여 배치(VLM 시계열→마킹위치 프레임추출→YOLO/SAM2 오토라벨링→트랙보간)를 트리거하며, 그 VLM 시계열 위탁 요청은 프레임 선택 정책(frame_policy — 수동 마킹은 frame_selected+선택 프레임 목록, 자동 마킹은 frame_interval)과 검증이벤트유형(event_type — 관제 인입값을 그대로 실어 위탁하며 화이트리스트로 사전 차단하지 않는다), 프레임 간격(framerate — 마킹 프레임 간격이며 FPS 가 아니다)으로 구성된다. 위탁은 논블로킹 제출이며 파이프라인 스레드를 붙잡지 않는다. 마킹 중 비식별 누락 신고 구간에는 위탁을 보류하고 신고 해소 후 재위탁한다 — 이벤트명·영상경로·마킹 원문은 위탁 규격 밖이라 싣지 않는다. 영상 스트리밍(Range)+배속(0.25x~4x)+단축키 지원. (★구 divergence 폐기 — 이전 서술은 비식별 선두를 설계 타깃으로, 마킹(원본)의 전체 배치 트리거를 현재 코드로 적었으나 그 분기는 해소됐다: 적재 직후 선두 비식별이 먼저 돌고, 마킹은 비식별 완료 후 진입해 잔여 배치만 트리거하는 순서로 확정·구현되었다)

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

### last_updated

2026-05-30T02:38:52.483Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_MARKING

## related_acceptances

- AC-027
- AC-028

## implemented_by_endpoints

- API-047

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
