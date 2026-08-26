---
logicraft_item: DFEAT-043
type: domain_feature
version: 11
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T01:10:30.754Z
status: CHANGED
prev_version: 10
content_hash: 4dddcf4c7f4d11b4a9771632f48df22e52114ac5a0b533c5bcba60e3d6c9ffd1
stale: false
raw: ./_raw/DFEAT-043.json
links:
  based_on: ["[[ADR-013]]"]
  belongs_to_domain: ["[[DOMAIN-013]]"]
  implements: ["[[API-081]]", "[[API-111]]", "[[API-115]]"]
  depicts_backward: ["[[CDIAG-011]]", "[[CMP-009]]"]
  realizes_backward: ["[[UC-024]]"]
  references_backward: ["[[CDIAG-011]]"]
---

# 데이터마트 영상 등록·기존 라벨 Load

## title

데이터마트 영상 등록·기존 라벨 Load

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### notes

2026-06-01 사용자 확정: 관제→포털 데이터마트 제공·등록 흐름. 오토라벨링 없음.

### status

new

### decided_by

ADR-013

## description

관제서버가 제공한 데이터마트를 포털에 등록하고, 포털 사용자가 영상을 선택하면 저작도구 DB의 검수 승인(APPROVED) 자산(LS_DATA_RAW·LS_DATA_SRC·LS_DATA_LBL)에 저장된 기존 라벨/메타를 Load하여 라벨링 화면에 표시한다 — 포털 DB를 읽지 않는다. 포털에는 오토라벨링(YOLO/SAM2)·VLM·버전관리·검수가 없다(ADR-013). 데이터마트 영상 목록에는 본인이 저장한 작업 라벨의 보존기간 만료 예정 시각이 함께 실린다 — 보존기간 정책 자체는 포털 작업 데이터 보존기간 만료 자동 삭제 기능이 소유하며 이 기능은 그 값을 목록에 노출하는 축이다.

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

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_META_REPL_OUTBOX

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-081
- API-115
- API-111

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
