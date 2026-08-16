---
logicraft_item: DFEAT-007
type: domain_feature
version: 10
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:51.137Z
status: NEW
prev_version: null
content_hash: 95e6af66be84931819fd794d88ed09bc052122e6a0be612e9182ed1bca0c3176
stale: false
raw: ./_raw/DFEAT-007.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
  implements: ["[[API-042]]", "[[API-043]]"]
  migrated_from: ["[[LEGACY-119]]"]
  depicts_backward: ["[[CDIAG-001]]", "[[CMP-010]]"]
  realizes_backward: ["[[UC-018]]"]
---

# 영상/이미지 관리

## title

영상/이미지 관리

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

modified

### decided_by

ADR-001

### change_kind

- scope-shrink

### diff_summary

1차 영상/이미지 관리·프로젝트 배정 → 2차 영상 단위 관리(프로젝트 배정 제거), 영상·프레임 수집 도메인으로 이관

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-03-03-01

#### legacy_artifact_id

LEGACY-119

## user_story

### as

검수자(REVIEWER)·라벨링 작업자(WORKER)

### i_want

영상/이미지 데이터를 관리하기를

### so_that

라벨링 대상 데이터를 영상 단위로 공급한다

## description

수집된 영상/이미지를 목록·상세 조회하고, 유휴 데이터를 처리한다. 작업 단위는 영상 1건이며 프로젝트 단위 개념은 사용하지 않는다. (1차 baseline, 화면 SKKLID-UI-03-03-01~04)

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

2026-05-30T04:05:03.598Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-042
- API-043

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
