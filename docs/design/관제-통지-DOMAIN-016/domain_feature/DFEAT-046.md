---
logicraft_item: DFEAT-046
type: domain_feature
version: 11
domain: DOMAIN-016
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-01T08:40:04.290Z
status: CHANGED
prev_version: 10
content_hash: f792716fbd581dbd13c31134a56e91dcf68af55323d1f6a886531fe9e77cb66b
stale: false
raw: ./_raw/DFEAT-046.json
links:
  based_on: ["[[ADR-007]]"]
  belongs_to_domain: ["[[DOMAIN-016]]"]
  consumes: ["[[EVT-009]]"]
  specializes: ["[[FEAT-003]]"]
  triggers: ["[[EVT-003]]", "[[EVT-004]]"]
  verifies: ["[[AC-1077]]", "[[AC-1078]]"]
  depicts_backward: ["[[CDIAG-013]]", "[[CMP-009]]"]
  realizes_backward: ["[[MOD-015]]", "[[UC-009]]"]
  references_backward: ["[[CDIAG-013]]"]
---

# 작업 완료/수정 outbound 통지

## title

작업 완료/수정 outbound 통지

## status

designed

## consumes

- EVT-009

## priority

must

## triggers

- EVT-003
- EVT-004

## brownfield

### notes

2026-07-30: outbound 통지 계약 BREAKING 변경(notify-completed/notify-updated, flat payload) 반영. 경로·필드명은 관제 정본과 정합 완료이며, 인증만 미확정으로 남아 관제 회신 대기 중이다.

### status

new

### decided_by

ADR-007

### change_kind

- capability-add

### diff_summary

2차 신규 — 검수 완료·재승인 시 관제서버로 나가는 단방향 완료·수정 통지. 1차 양방향 연동은 쓰지 않는다.

## description

검수 완료(APPROVED) 시 TASK_COMPLETED, 완료된 영상의 라벨/메타 수정이 재검수에서 승인될 때 TASK_MODIFIED 를 영상 단위로 관제서버에 단방향 push 한다(수정 요약만, PII/본문 미포함). ★BREAKING — 구 단일 경로(POST /api/v1/notify) 계약을 폐기하고 notify-completed/notify-updated 2경로 + 평면(flat) 페이로드로 전면 교체. idempotency(요청 ID)·dead-letter·재등록 큐·Resilience4j(재시도/서킷브레이커/타임아웃) 적용. 보호 방식은 인계토큰 또는 IP 화이트리스트를 두기로 하되 아직 확정되지 않았다(auth_type=none, 아래 참조). 경로·필드명은 관제 정본을 따른다. ⚠ 인증은 아직 미확정이다 — 관제 정본은 x-access-token 헤더를 요구하나 아웃바운드 통지는 인증 헤더를 부착하지 않으며, 그 토큰의 발급 주체가 확정되지 않아 관제 회신을 기다리는 중이다.

[★발송 시점 = export 성공 이후(구속)] 통지는 export 가 SUCCEEDED 된 뒤에만 나간다 — 이벤트 체인은 검수 승인(EVT-006) → export 전량 재생성 → 산출 완료(EVT-009) → TASK_COMPLETED/TASK_MODIFIED 발송이다. export 가 비동기라 통지가 앞서면 관제가 구 버전 폴더를 픽업한다. export 가 실패하면 통지를 보류하고 재산출 성공 후 재개한다(유실이 아니라 지연). ⚠ 재-export 트리거(수정 축적·디바운스 flush 포함)는 통지 발송 토글(authoring.control-notify.enabled)과 무관하게 항상 동작한다 — 그 토글은 발송 자체만 게이팅한다.

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

_(empty)_

### progress

100

### subtasks

_(empty)_

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_CONTROL_NOTIFY_FALLBACK
- LS_MON_NOTI_ACML

## related_acceptances

- AC-1077
- AC-1078

## specializes_feature

FEAT-003

## implemented_by_endpoints

_(empty)_

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
