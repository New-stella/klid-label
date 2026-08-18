---
logicraft_item: DFEAT-037
type: domain_feature
version: 11
domain: DOMAIN-009
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:53.424Z
status: NEW
prev_version: null
content_hash: c77a8782b899e786a780d344137e9de63321fa9ccd5f09ab9e7354a59758ca19
stale: true
raw: ./_raw/DFEAT-037.json
links:
  belongs_to_domain: ["[[DOMAIN-009]]"]
  implements: ["[[API-095]]", "[[API-096]]", "[[API-107]]"]
  migrated_from: ["[[LEGACY-085]]"]
---

# 게시글 목록·상세 조회

## title

게시글 목록·상세 조회

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

v1 폐기(ADR-011, superseded) → ADR-014로 재도입 정형화. v1 테이블(LS_NTC_BBS) 미재사용, v2 신규 LS_NOTICE 구성.

### status

modified

### decided_by

ADR-014

### change_kind

- reintroduced
- feature-add

### diff_summary

v1 게시판 → 2차 폐기(ADR-011) → R1 외 추가로 재도입(LS_NOTICE 신규 테이블)

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-03-01

#### legacy_artifact_id

LEGACY-085

## user_story

### as

내부 사용자(WORKER/REVIEWER)

### i_want

공지·가이드라인 게시글을 조회하기를

### so_that

작업 규칙·공지사항을 도구 안에서 확인한다

## description

공지·가이드라인 게시글 목록·상세 조회. v2 재구현 (R1 외 추가) — 상단 고정(PIN) 우선 + 등록일 내림차순 페이징 목록, 첨부파일 포함 상세. WORKER 는 PUBLISHED 게시글만, REVIEWER 는 DRAFT 포함 전체 열람. 화면: 공지 목록(/notice, SCREEN-030)·상세(/notice/:id, SCREEN-031). (1차 baseline: SKKLID-UI-02-03-01~03)

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

- API-095
- API-096
- API-107

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
