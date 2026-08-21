---
logicraft_item: FEAT-008
type: feature
version: 4
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T00:02:42.640Z
status: CHANGED
prev_version: 3
content_hash: 005ac6702879bab38bf21b42b4907a00e03bb4d3f62061bef010b9cb9619e74b
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

구축된 학습데이터(라벨·메타)를 REVIEWER 가 영상 단위로 검토하여 승인/반려한다. 자동 라벨링·VLM 자동 결과를 검수큐에 모으는 자동 보조 + REVIEWER 수동 검수. 승인 시 LS_RAW_DATA_STATUS APPROVED 전이 + 라벨 버전 스냅샷 + TASK_COMPLETED 통지, 반려 시 사유 기록·재작업. 보완요청 SFR-11-10·16·17 자동/수동 검수 요구 반영 — 기존 검수 DFEAT-021/024/025·UC-023을 롤업한 글로벌 기능 단위. 승인에는 전제조건이 있다 — 검수 워크플로 상태의 비식별화완료여부가 미완료인 영상은 승인이 거부된다(외부에서 이미 라벨링이 끝난 산출물을 원본이라고 지정해 들여온 경로만 미완료로 시작하며, 그 밖의 영상은 완료가 기본이라 이 전제조건에 막히지 않는다). 판정이 상태 전이보다 먼저라, 거부될 때는 상태 전이·라벨 버전 스냅샷·학습데이터 산출물·관제 통지가 하나도 일어나지 않는다. 막는 범위는 승인 하나이며 검수 착수·라벨 확인·프레임 열람은 그대로 가능하다 — 그 통로들까지 함께 닫는 것은 비식별 누락 신고라는 별개 축이다.

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
