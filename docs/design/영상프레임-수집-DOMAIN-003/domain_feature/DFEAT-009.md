---
logicraft_item: DFEAT-009
type: domain_feature
version: 6
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:51.139Z
status: NEW
prev_version: null
content_hash: d82bab7651f9b3098798534810b189135242a0d961982dd1e3b67c7ffdf56395
stale: false
raw: ./_raw/DFEAT-009.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
  migrated_from: ["[[LEGACY-044]]"]
  depicts_backward: ["[[CDIAG-001]]"]
  references_backward: ["[[CDIAG-001]]"]
---

# FFmpeg 프레임 자동 추출 (배치 1회/분)

## title

FFmpeg 프레임 자동 추출 (배치 1회/분)

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### status

preserved

### legacy_source

#### repo

KLID-AI-PF-005

#### type

api

#### identifier

KLID-AI-II-002

#### legacy_artifact_id

LEGACY-044

## user_story

### as

시스템

### i_want

영상에서 프레임을 자동 추출하기를

### so_that

라벨링 단위(프레임 이미지)를 생성한다

## description

적재된 영상에서 FFmpeg로 프레임을 자동 추출한다. 처리 결과(진행률·상태)는 배치 처리 이력에 기록된다. 배치 1회/분으로 수행한다. 프레임 추출은 VLM 시계열 이후·마킹 위치 기반으로 수행하며, 비식별화는 이미 선행 완료된 상태다(설계 타깃 순서: 비식별→마킹→VLM→프레임추출). (1차 baseline, 인터페이스 KLID-AI-II-001/002)

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

2026-05-30T02:33:26.637Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DATA_SRC

## related_acceptances

_(empty)_

## implemented_by_endpoints

_(empty)_

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
