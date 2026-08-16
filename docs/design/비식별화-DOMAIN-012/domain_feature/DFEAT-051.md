---
logicraft_item: DFEAT-051
type: domain_feature
version: 4
domain: DOMAIN-010
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.727Z
status: NEW
prev_version: null
content_hash: e2e08aba5a264faedfdd69cf2e5429e73adcd094673338ea9f9252ebe1f5b473
stale: false
raw: ./_raw/DFEAT-051.json
links:
  belongs_to_domain: ["[[DOMAIN-010]]"]
  implements: ["[[API-168]]", "[[API-170]]", "[[API-172]]", "[[API-173]]", "[[API-174]]", "[[API-183]]", "[[API-184]]"]
  realizes_backward: ["[[MOD-043]]"]
---

# 촬영환경·개인정보 메타 수동입력

## title

촬영환경·개인정보 메타 수동입력

## status

designed

## consumes

_(empty)_

## priority

should

## triggers

_(empty)_

## brownfield

### notes

촬영환경 메타는 영상 단위 한 벌이고, 개인정보 메타는 영상 축과 프레임 축의 입도가 달라 각각 별도 통로로 조회·저장한다. 프레임 축에는 여러 프레임을 한 번에 저장하는 통로를 함께 둔다.

### status

new

### decided_by

ADR-032

### change_kind

- feature-add

### diff_summary

R1 요구 외 신규 — 촬영환경·개인정보 메타 수동입력 신설(규칙기반 self-fill 자동파생은 두지 않는다)

## user_story

### as

라벨링 작업자(WORKER)·검수자(REVIEWER)

### i_want

촬영환경과 프레임별 개인정보 유형을 직접 입력하기를

### so_that

자동파생이 놓치는 실제 촬영 상황과 개인정보 노출 여부를 정확히 기록해 학습데이터 메타 품질을 확보한다

## description

라벨링 화면 메타탭에서 촬영환경(날씨/시간대/계절, 영상 단위)과 개인정보(익명/가명/PII 포함 여부, 프레임 단위) 메타를 수동으로 입력·저장한다. R1 요구사항 외 신규 추가 결정(2026-07-24). 조회는 수동값 우선, 없으면 촬영일시 파생 프리필(source=MANUAL/DERIVED)로 표시한다. 촬영환경 저장은 전체 교체(3필드), 개인정보 메타는 단건 저장(path/body srcSn 불일치 시 400)과 벌크 저장(다건, 영상당 수천 프레임)을 함께 제공한다. 규칙기반 self-fill(자동파생) 정책은 오분류가 실증되어 제거되었다(V145). export(NiaVideo/NiaImage)에는 season(구 촬영일시 파생)·anonymity(구 Export종류 파생)·weather(구 null) 필드가 이미 존재했으나 수동입력 경로가 없던 것이 실제 갭이었다.

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

- LS_DATA_RAW
- LS_DATA_SRC

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-168
- API-170
- API-172
- API-173
- API-174
- API-183
- API-184

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
