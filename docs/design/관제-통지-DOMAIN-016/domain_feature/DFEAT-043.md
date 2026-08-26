---
logicraft_item: DFEAT-043
type: domain_feature
version: 12
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T05:49:23.997Z
status: CHANGED
prev_version: 11
content_hash: 43eec364f308a37d8d5cf9fbc54854500d49a7d3aaeecdcee0c351a866981b6d
stale: false
raw: ./_raw/DFEAT-043.json
links:
  based_on: ["[[ADR-013]]"]
  belongs_to_domain: ["[[DOMAIN-013]]"]
  implements: ["[[API-081]]", "[[API-111]]", "[[API-115]]"]
  depicts_backward: ["[[CDIAG-011]]", "[[CMP-009]]"]
  realizes_backward: ["[[UC-024]]"]
  references_backward: ["[[CDIAG-011]]"]
---

# 데이터마트 영상 등록·기존 라벨 Load

## title

데이터마트 영상 등록·기존 라벨 Load

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### notes

2026-06-01 사용자 확정: 관제→포털 데이터마트 제공·등록 흐름. 오토라벨링 없음.

### 2026-08-26 — ADR-013 정합
- 데이터마트 로드분의 메타(촬영환경·프레임 설명·개인정보 판정·시계열 메타)와 이벤트 어노테이션을 함께 Load·표시하고, 포털 사용자가 직접 수정·추가할 수 있다는 책임을 본문에 명시.
- 포털에서 고친 값은 포털 전용 저장소에만 적재하며 데이터마트·원본 동결본을 수정하지 않는 단방향 축임을 명시.
- 미제공의 축을 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)으로 좁힘 — 그 결과물인 시계열 메타의 표시·수정은 제공이다.

### status

new

### decided_by

ADR-013

## description

관제서버가 제공한 데이터마트를 포털에 등록하고, 포털 사용자가 영상을 선택하면 저작도구 DB의 검수 승인(APPROVED) 자산(LS_DATA_RAW·LS_DATA_SRC·LS_DATA_LBL)에 저장된 기존 라벨/메타를 Load하여 라벨링 화면에 표시한다 — 포털 DB를 읽지 않는다. Load 대상에는 라벨뿐 아니라 그 영상의 메타(촬영환경·프레임 설명·개인정보 판정·시계열 메타)와 이벤트 어노테이션이 함께 포함되며, 포털 작업 화면에 표시한다. 포털 사용자는 표시된 메타와 이벤트 어노테이션을 직접 수정·추가할 수 있고, 그 결과는 포털 전용 저장소에만 적재되어 데이터마트와 원본 동결본을 수정하지 않는다 — 포털 라벨 저장과 같은 단방향 축이다. 포털에 두지 않는 것은 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)이며, 그 결과물인 시계열 메타를 화면에 표시하고 사람이 수정·추가하는 것은 미제공 대상이 아니다. 오토라벨링(YOLO)·SAM2 인터랙티브 분할·자동추적·키포인트·버전관리·검수도 포털에 두지 않는다(ADR-013). 데이터마트 영상 목록에는 본인이 저장한 작업 라벨의 보존기간 만료 예정 시각이 함께 실린다 — 보존기간 정책 자체는 포털 작업 데이터 보존기간 만료 자동 삭제 기능이 소유하며 이 기능은 그 값을 목록에 노출하는 축이다.

## invokes_apis

_(empty)_

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

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_META_REPL_OUTBOX

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-081
- API-115
- API-111

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
