---
logicraft_item: DFEAT-049
type: domain_feature
version: 8
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T01:11:03.598Z
status: CHANGED
prev_version: 7
content_hash: fb187a6a43f8f346a3f545c88427b2b3aaa1cbf3b4ceb170835de98cb7274cf2
stale: false
raw: ./_raw/DFEAT-049.json
links:
  based_on: ["[[ADR-015]]"]
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[API-102]]", "[[API-103]]", "[[API-104]]", "[[API-105]]"]
---

# 검수자↔작업자 이슈 소통 채널 (반려·문의 통합 스레드)

## title

검수자↔작업자 이슈 소통 채널 (반려·문의 통합 스레드)

## status

implemented

## consumes

_(empty)_

## priority

could

## triggers

_(empty)_

## brownfield

### status

new

### decided_by

ADR-015

### change_kind

- feature-add

### diff_summary

R1 외 추가 — v1 이슈 탭의 v2 재구현 (양방향 스레드로 확장)

## user_story

### as

라벨링 작업자(WORKER)

### i_want

작업 중인 영상에서 검수자에게 문의를 남기고 반려 사유와 함께 답변을 한 곳에서 확인하고 싶다

### so_that

화면 밖 채널(메신저 등) 없이 작업 맥락 안에서 소통해 재작업·재검수 주기를 단축한다

## description

v1 '관리자 확인요청/이슈 탭'의 v2 재구현 (R1 요구사항 외 추가 결정, 2026-06-05).

- **통합 스레드**: 검수 반려 이력(REJECTION, 생성 시점부터 RESOLVED 고정)과 작업자 문의(INQUIRY)를 영상 단위 단일 API 로 통합 조회
- **상태머신**: INQUIRY 는 OPEN →(REVIEWER 댓글 시 자동)→ ANSWERED →(resolve, REVIEWER 전용)→ RESOLVED. 역행 금지, resolve 멱등
- **댓글 규칙**: RESOLVED INQUIRY 댓글 차단(409), 단 REJECTION 은 RESOLVED 여도 후속 질문 허용
- **범위**: 영상 단위 + 선택적 프레임 참조(srcSn). 타 영상 프레임 참조 차단
- **권한**: WORKER 는 현재 배정(LS_TASK_ALTMNT) 또는 본인 작성 이슈만, REVIEWER 전체. PORTAL_USER 차단(내부 전용)
- **동시성**: 낙관적 잠금 — resolve↔댓글 충돌 시 409
- **미포함**: 첨부파일·푸시·이메일 알림

FE: 라벨링 화면(/label/:id) 우측 패널 '이슈' 탭 (IssueThreadPanel) + 검수 화면 통합. 미해소 문의 카운트 배지. videoId 부재 시 탭 비노출(프레임 PK 폴백 금지).

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

### [1]

- **then**: INQUIRY/OPEN 이슈가 생성되고 스레드에 즉시 표시된다
- **when**: 이슈 탭에서 문의를 등록하면
- **given**: WORKER 가 현재 배정된 영상의 라벨링 화면에 있을 때

### [2]

- **then**: 상태가 ANSWERED 로 자동 전이된다
- **when**: REVIEWER 가 댓글을 작성하면
- **given**: OPEN 상태의 INQUIRY 에

### [3]

- **then**: 409 CONFLICT 로 거부된다 (REJECTION 은 허용)
- **when**: 댓글을 작성하면
- **given**: RESOLVED 상태의 INQUIRY 에

### [4]

- **then**: 403 으로 거부된다
- **when**: 스레드 조회/댓글/등록을 시도하면
- **given**: WORKER 가 배정되지 않고 본인 작성 이슈도 없는 영상에

## persists_in_tables

- LS_DATA_ISSUE
- LS_ISSUE_COMMENT

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-102
- API-103
- API-104
- API-105

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
