---
logicraft_item: DFEAT-025
type: domain_feature
version: 8
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T13:21:55.598Z
status: CHANGED
prev_version: 6
content_hash: 2c9c445338a97e9830438528f14bf16d993af6eb6baac9744c82faf436c7d881
stale: true
raw: ./_raw/DFEAT-025.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[API-011]]", "[[IMPREC-359]]"]
  migrated_from: ["[[LEGACY-079]]"]
  references: ["[[ADR-067]]"]
  specializes: ["[[FEAT-008]]"]
  depicts_backward: ["[[CDIAG-006]]"]
  realizes_backward: ["[[UC-023]]"]
---

# 검수 이력

## title

검수 이력

## status

designed

## consumes

_(empty)_

## priority

should

## triggers

_(empty)_

## brownfield

### status

preserved

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-18

#### legacy_artifact_id

LEGACY-079

## user_story

### as

검수자

### i_want

검수 이력을 조회하기를

### so_that

검수 과정을 추적·감사한다

## description

검수 제출·검수 시작·승인·반려 등 검수 이력을 한 타임라인으로 조회한다.

[검수 시작이 이력에 남는다] 검수를 시작하면 그 사람이 그 영상을 점유하고, 그 점유는 전용 컬럼이나 별도 표가 아니라 이 이력에 「검수 시작」을 남기는 것으로 표현한다([[ADR-067]]). 그래서 이 기능은 점유의 표현 수단이자 「언제 처음 열었는가」의 보존처다 — 검수 시작이 배정·제출·승인·반려와 같은 타임라인에 함께 놓인다.

[행위 시점 역할] 이력 항목마다 행위자와 함께 그 행위를 한 시점의 역할이 남는다. 조회 시점에 현재 역할을 다시 읽은 값이 아니라서 그 사람의 역할이 나중에 바뀌어도 과거 행위의 역할은 그대로다 — 관리자가 승인한 건은 관리자로 남는다. 역할 칸이 생기기 전에 쌓인 옛 이력은 역할을 복원할 수 없어 비어 있을 수 있으며, 그때 화면은 역할을 비워 두고 지어낸 값으로 채우지 않는다.

[연속 재진입은 한 줄로 합쳐 보인다] 같은 행위자가 잇달아 남긴 「검수 시작」은 점유 시각을 갱신한 것이라 건마다 한 줄씩 늘어놓지 않고 한 줄로 합쳐, 최초 시작 시각과 마지막 재진입 시각을 함께 보인다. 최초 시작 시각을 덮어쓰지 않는 것이 이 표시의 전제다.

## invokes_apis

_(empty)_

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-359

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:20.824Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_DATA_ISSUE
- LS_TASK_EVNT_LOG

## related_acceptances

_(empty)_

## specializes_feature

FEAT-008

## implemented_by_endpoints

- API-011

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
