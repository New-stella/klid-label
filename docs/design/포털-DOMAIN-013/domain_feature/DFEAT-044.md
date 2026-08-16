---
logicraft_item: DFEAT-044
type: domain_feature
version: 8
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:56.454Z
status: NEW
prev_version: null
content_hash: ae0a5291b70489439cc1cfcc8e42c45578495ba87ad892dac686fb31df54e90f
stale: false
raw: ./_raw/DFEAT-044.json
links:
  belongs_to_domain: ["[[DOMAIN-013]]"]
  implements: ["[[API-082]]", "[[API-083]]"]
  depicts_backward: ["[[CDIAG-011]]", "[[CMP-009]]"]
  realizes_backward: ["[[UC-024]]"]
  references_backward: ["[[CDIAG-011]]"]
---

# 포털 사용자 라벨 수정·저장·다운로드 (사용자별 격리)

## title

포털 사용자 라벨 수정·저장·다운로드 (사용자별 격리)

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

2026-06-01 사용자 확정: 사용자별 저장·데이터마트 미정합 / 2026-07-30: 구현 API 재매핑(retired API-080 제거, API-082/083 유지) + SAM2 노출 반영

### status

new

### decided_by

ADR-013

## description

포털 사용자가 데이터마트 영상의 라벨을 수정·저장하면 원본/데이터마트를 변경하지 않고 사용자별 작업 데이터(LS_PORTAL_USER_LABEL)로 별도 적재한다(GET/POST /v1/portal/user-labels = API-082/083). 저장 데이터는 데이터마트에 정합/반영되지 않으며(단방향), 본인 데이터를 기간 내 다운로드할 수 있다. 저장 가능한 도형은 BBOX·POLYGON 만이고 서버 allowlist 가 그 외 값을 400 으로 거부하며, 좌표 개수도 BBOX 는 정확히 2점, POLYGON 은 3~200점으로 강제된다(위반 시 400). [폐기] SAM2 분할·추적(sam2-segment·sam2-track)을 포털에 예외적으로 노출한다 — 포털 전용 SAM2 경로는 두지 않으며 FE 도 채널 분기를 두지 않는다(UC-024 와 동일). 오토라벨링(YOLO)·SAM2·VLM·검수·버전관리 모두 포털 미제공(ADR-013). 구 구현 경로 POST /v1/portal/labels(API-080)는 retired — 실제 저장 경로는 API-082/083이다. 본인 자산(이미지/영상) 직접 업로드+라벨링은 별도 기능(포털 자산 업로드·수동 라벨링)으로 분리되어 있다.

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

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

- LS_PORTAL_USER_LABEL

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-082
- API-083

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
