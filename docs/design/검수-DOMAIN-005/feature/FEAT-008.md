---
logicraft_item: FEAT-008
type: feature
version: 3
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:41:14.291Z
status: NEW
prev_version: null
content_hash: 26190d882f8927760760370c6272f5460c484895618aa8fc20e18a253ba27362
stale: false
raw: ./_raw/FEAT-008.json
links:
  implements: ["[[REQ-024]]"]
  granted_on_backward: ["[[ROLE-001]]", "[[ROLE-002]]"]
  implements_backward: ["[[API-008]]", "[[API-009]]", "[[API-010]]", "[[API-011]]", "[[API-012]]", "[[API-013]]", "[[API-014]]", "[[API-015]]", "[[API-102]]", "[[API-103]]", "[[API-104]]", "[[API-105]]", "[[API-138]]", "[[API-178]]"]
  realizes_backward: ["[[UC-023]]"]
  specializes_backward: ["[[DFEAT-021]]", "[[DFEAT-024]]", "[[DFEAT-025]]"]
---

# 학습데이터 검수 (승인·반려)

## priority

must

## brownfield

### status

new

### diff_summary

보완요청 SFR-11-10 검수 요구로 검수 워크플로우를 글로벌 FEAT로 정식화(검수 DFEAT-021/024/025 롤업)

## complexity

moderate

## user_story

### goal

구축된 학습데이터를 검토해 승인·반려하기를 원한다

### actor

검수자(REVIEWER)

### benefit

검증된 데이터만 학습데이터로 확정된다

## description

구축된 학습데이터(라벨·메타)를 REVIEWER 가 영상 단위로 검토하여 승인/반려한다. 자동 라벨링·VLM 자동 결과를 검수큐에 모으는 자동 보조 + REVIEWER 수동 검수. 승인 시 LS_RAW_DATA_STATUS APPROVED 전이 + 라벨 버전 스냅샷 + TASK_COMPLETED 통지, 반려 시 사유 기록·재작업. 보완요청 SFR-11-10·16·17 자동/수동 검수 요구 반영 — 기존 검수 DFEAT-021/024/025·UC-023을 롤업한 글로벌 기능 단위.

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

## implements_requirements

- REQ-024
