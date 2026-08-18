---
logicraft_item: DFEAT-012
type: domain_feature
version: 3
domain: DOMAIN-010
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:54.248Z
status: NEW
prev_version: null
content_hash: 837910445fe0f9e34e7b6c8b8415023cd8c10f9dd208bc0d7c1603bcf0640919
stale: false
raw: ./_raw/DFEAT-012.json
links:
  belongs_to_domain: ["[[DOMAIN-010]]"]
  migrated_from: ["[[LEGACY-068]]"]
  depicts_backward: ["[[CDIAG-004]]"]
  realizes_backward: ["[[UC-021]]"]
  references_backward: ["[[CDIAG-004]]"]
---

# 도형 어노테이션 (바운딩박스·폴리곤·스켈레톤)

## title

도형 어노테이션 (바운딩박스·폴리곤·스켈레톤)

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

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-07

#### legacy_artifact_id

LEGACY-068

## user_story

### as

라벨링 작업자

### i_want

박스·폴리곤·스켈레톤으로 객체를 주석하기를

### so_that

학습 데이터용 라벨을 제작한다

## description

캔버스에서 바운딩박스/폴리곤/세그멘테이션/스켈레톤으로 객체를 라벨링한다. 라벨·번호 자동 부여, 일반/트랙 생성 방식, 관리자 설정 색상 표기. 모든 연산은 원본 해상도 기준. (1차 baseline, 화면 SKKLID-UI-02-02-07/08)

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

2026-05-30T02:34:28.769Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## implemented_by_endpoints

_(empty)_

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
