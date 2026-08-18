---
logicraft_item: DFEAT-038
type: domain_feature
version: 15
domain: DOMAIN-009
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:53.424Z
status: NEW
prev_version: null
content_hash: fbc09e154e0859451f1a86d170e7d59b2a946174bd03f9a3db45e0de4982d8fe
stale: true
raw: ./_raw/DFEAT-038.json
links:
  belongs_to_domain: ["[[DOMAIN-009]]"]
  implements: ["[[API-097]]", "[[API-098]]", "[[API-099]]", "[[API-100]]", "[[API-101]]", "[[API-106]]", "[[API-108]]"]
  migrated_from: ["[[LEGACY-127]]"]
---

# 게시글 작성·수정·발행 (REVIEWER)

## title

게시글 작성·수정·발행 (REVIEWER)

## status

implemented

## consumes

_(empty)_

## priority

could

## triggers

_(empty)_

## brownfield

### notes

v1 폐기(ADR-011, superseded) → ADR-014로 재도입 정형화. ADMIN→REVIEWER 권한 이관(ADR-003 정합).

### status

modified

### decided_by

ADR-014

### change_kind

- reintroduced
- feature-add

### diff_summary

v1 관리자 게시판 → 2차 폐기(ADR-011) → R1 외 추가로 재도입 + ADMIN→REVIEWER 권한 이관

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-03-06-02

#### legacy_artifact_id

LEGACY-127

## user_story

### as

검수자(REVIEWER)

### i_want

공지·가이드라인을 작성하고 발행 상태를 제어하기를

### so_that

작업자에게 작업 규칙·공지를 시의적절한 시점에 전달한다

## description

REVIEWER 가 공지·가이드라인 게시글을 작성·수정·삭제하고 DRAFT→PUBLISHED 발행/발행취소로 노출을 제어한다. v2 재구현 (R1 외 추가) — 첨부파일은 UUID 안전 파일명 저장 + 원본명 DB 보관. 발행일시(PBLCN_DT)는 현재 발행 상태의 전이 시점만 가리켜 발행 취소 시 비워지고 재발행 시 다시 기록된다(최초 발행 이력 미보존). v2 는 ADMIN 없이 REVIEWER 가 관리 권한 통합 보유. 화면: 작성(/notice/new, SCREEN-036)·수정(/notice/:id/edit, SCREEN-037). (1차 baseline: SKKLID-UI-03-06-01~04, 관리자 작성)

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

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_NOTICE
- LS_NOTICE_ATTACH

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-097
- API-098
- API-099
- API-100
- API-101
- API-106
- API-108

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
