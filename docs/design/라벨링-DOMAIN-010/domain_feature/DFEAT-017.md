---
logicraft_item: DFEAT-017
type: domain_feature
version: 5
domain: DOMAIN-010
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:54.249Z
status: NEW
prev_version: null
content_hash: e596bab582d14171e481dff689fad8d078e659c7ef7fe1de8e35b236046ca396
stale: false
raw: ./_raw/DFEAT-017.json
links:
  belongs_to_domain: ["[[DOMAIN-010]]"]
  implements: ["[[API-018]]", "[[API-019]]"]
  migrated_from: ["[[LEGACY-017]]"]
  depicts_backward: ["[[CDIAG-004]]"]
  realizes_backward: ["[[UC-021]]"]
  references_backward: ["[[CDIAG-004]]"]
---

# 학습데이터 저장

## title

학습데이터 저장

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

#### type

table

#### identifier

LS_DATA_LBL

#### legacy_artifact_id

LEGACY-017

## user_story

### as

라벨링 작업자

### i_want

라벨링 결과를 학습데이터로 저장하기를

### so_that

검수·내보내기 단계로 이어진다

## description

라벨링 결과를 학습데이터로 저장하고 데이터베이스에 메타정보를 기록한다 (테이블 LS_DATA_LBL). — 라벨러의 저장은 현재 작업본을 LS_DATA_LBL에 full-replace 로 영속하는(부분 upsert 아님 — ADR-033) '작업 임시저장'이며 되돌리기는 FE undo/redo(세션)로 처리한다. 학습데이터 버전 스냅샷(LS_LABEL_VERSION)은 이 시점이 아니라 검수 승인(APPROVED) 시 생성된다(버전관리는 검수완료 단위 — SFR-08).

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

2026-05-30T02:34:35.327Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DATA_LBL

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-018
- API-019

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
