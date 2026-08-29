---
logicraft_item: DFEAT-024
type: domain_feature
version: 10
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T01:27:29.301Z
status: CHANGED
prev_version: 10
content_hash: 281eb413b73436d60dcf50869cded036310d17a2edc0f3a96a41479b73c381ba
stale: true
raw: ./_raw/DFEAT-024.json
links:
  belongs_to_domain: ["[[DOMAIN-005]]"]
  implements: ["[[API-014]]", "[[API-015]]"]
  migrated_from: ["[[LEGACY-078]]"]
  specializes: ["[[FEAT-008]]"]
  triggers: ["[[EVT-006]]"]
  verifies: ["[[AC-022]]"]
  depicts_backward: ["[[CDIAG-006]]", "[[CMP-005]]"]
  realizes_backward: ["[[UC-023]]"]
---

# 승인·반려

## title

승인·반려

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

- EVT-006

## brownfield

### status

modified

### decided_by

ADR-003

### change_kind

- actor-change

### diff_summary

1차 승인/반려/관리자 확인 요청 → 2차 관리자 역할 폐기(REVIEWER 흡수)로 검수자 확인으로 변경

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-17

#### legacy_artifact_id

LEGACY-078

## user_story

### as

검수자

### i_want

제출 데이터를 승인 또는 반려하기를

### so_that

검수 결과를 확정하고 재작업을 지시한다

## description

검수자가 제출된 데이터를 승인 또는 반려한다. 관리자 확인 요청은 이 기능에 두지 않는다 — 관리자 역할이 검수자로 통합되어 확인을 요청할 상대가 없고, 검수자와 작업자 사이의 확인·문의 소통 축은 이슈 스레드(DFEAT-049)가 담당한다. (1차 baseline, 화면 SKKLID-UI-02-02-17)

승인에는 전제조건이 있다 — 검수 워크플로 상태의 비식별화완료여부가 완료여야 한다. 미완료이면 승인 요청은 거부된다. 이 판정은 상태 전이보다 먼저 이뤄지므로, 거부된 요청에서는 상태 전이도 라벨 버전 스냅샷도 학습데이터 산출물 생성도 관제 통지도 일어나지 않는다. 비식별화완료여부는 기본값이 완료다 — 외부 산출물 이관 경로로 원본이라고 지정해 들어온 영상만 미완료로 시작하며, 그 경로와 무관한 기존 영상이 이 전제조건 때문에 막히지 않게 하기 위함이다. 이 전제조건이 막는 것은 검수 승인뿐이다 — 라벨 조회·프레임 이미지·영상 스트리밍은 이 값으로 닫지 않고, 학습데이터 산출물 생성 경로 자체도 이 값으로 닫지 않는다(승인이 거부되면 산출물이 생기지 않는 것은 승인이 그 방아쇠이기 때문이지 산출 경로가 닫혀서가 아니다). 그 통로들을 함께 닫는 것은 비식별 누락 신고 구간의 차단이며, 이 전제조건과는 별개 축이다.

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-358

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:20.690Z

## uses_constants

_(empty)_

## acceptance_rules

### [1]

- **then**: 승인이 거부된다. 판정이 상태 전이보다 먼저 이뤄지므로 상태 전이·라벨 버전 스냅샷·학습데이터 산출물·관제 통지가 하나도 만들어지지 않는다
- **when**: 검수자가 그 영상의 검수 승인을 요청한다
- **given**: 외부 산출물 이관 경로로 원본이라고 지정해 들어온 영상이라, 검수 워크플로 상태의 비식별화완료여부가 미완료다

### [2]

- **then**: 이 전제조건은 승인을 막지 않는다
- **when**: 검수자가 그 영상의 검수 승인을 요청한다
- **given**: 이 이관 경로와 무관하게 적재된 영상이라, 비식별화완료여부가 기본값인 완료다

### [3]

- **then**: 이 전제조건은 그 통로들을 닫지 않는다. 그 통로들을 함께 닫는 것은 비식별 누락 신고 구간의 차단이며 별개 축이다
- **when**: 라벨 조회·프레임 이미지·영상 스트리밍·학습데이터 산출물 생성을 시도한다
- **given**: 비식별화완료여부가 미완료인 영상이다

## persists_in_tables

- LS_RAW_DATA_STATUS
- LS_DATA_ISSUE

## related_acceptances

- AC-022

## specializes_feature

FEAT-008

## implemented_by_endpoints

- API-014
- API-015

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
