---
logicraft_item: DFEAT-008
type: domain_feature
version: 6
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:51.138Z
status: NEW
prev_version: null
content_hash: b622a80eff6e1aa39e1a879d6112fd77f0acb2adf9042b98c0ccfd8e14494de4
stale: false
raw: ./_raw/DFEAT-008.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
  implements: ["[[API-191]]", "[[API-192]]"]
  migrated_from: ["[[LEGACY-043]]"]
  triggers: ["[[EVT-005]]"]
  depicts_backward: ["[[CDIAG-001]]", "[[CMP-010]]"]
  realizes_backward: ["[[MOD-045]]", "[[UC-018]]"]
  references_backward: ["[[CDIAG-001]]"]
---

# 클립영상 수신·적재

## title

클립영상 수신·적재

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

- EVT-005

## brownfield

### status

preserved

### legacy_source

#### repo

KLID-AI-PF-005

#### type

api

#### identifier

KLID-AI-II-001

#### legacy_artifact_id

LEGACY-043

## user_story

### as

시스템

### i_want

원시 클립영상을 수신·적재하기를

### so_that

이후 프레임 추출·라벨링의 원천 데이터를 확보한다

## description

관제가 학습용으로 지정한 영상을 저작도구로 들여와 LS_DATA_RAW 에 적재한다.

[★2차 구조 — 적재 주체 반전(ADR-042)] 관제서버가 LS_DATA_INGEST 에 직접 INSERT 하고, 저작도구 주기 배치(1회/분)가 미처리 행을 폴링해 적재한다. 영상 관련 정보는 전부 이 인입 테이블에서 평면으로 받으며 관제 공유 마스터 조인은 하지 않는다. 구 방식(공유 MNG_CLIP_MASTER.JOB_DMND_YN='Y' 후보를 READ 해 픽업)은 폐기됐다.

[동시성] 2노드 Active-Active 이므로 후보 선점을 조건부 UPDATE 로 원자 클레임하고(Quartz 클러스터링은 트리거 중복만 막는다), 클레임 후 노드가 죽어 PROCESSING 으로 고착된 행은 타임아웃 기반 좀비 회수로 되살린다. 회수는 스캔 앞에 돌아 같은 tick 에 바로 처리되게 하고, 회수 실패가 그 tick 의 정상 적재를 막지 않도록 예외를 흡수한다.

[멱등·격리] 행별 적재를 REQUIRES_NEW 로 분리해 한 건의 실패가 나머지를 깨뜨리지 않게 하고, 멱등은 VMS_CLIP_ID 조회 + UK 위반 catch 이중 방어다. 식별자·파일경로가 비면 skip(WARN).

[후속] 정상 적재 시 VideoIngestedEvent(EVT-005) 를 발행해 선두 비식별로 이어진다. 적재 상태는 PENDING → (비식별 성공) MARKING_READY.

(1차 baseline: 데이터송신시스템으로부터 원시 클립영상 수신, 인터페이스 KLID-AI-II-001)

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

2026-05-30T02:33:24.525Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-191
- API-192

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
