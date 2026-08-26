---
logicraft_item: DFEAT-044
type: domain_feature
version: 11
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T04:28:12.047Z
status: CHANGED
prev_version: 10
content_hash: 16034807c7b864db5257db9cdedfd41b523ff2b999df7b3fbc446270d38e9443
stale: true
raw: ./_raw/DFEAT-044.json
links:
  based_on: ["[[ADR-013]]"]
  belongs_to_domain: ["[[DOMAIN-013]]"]
  implements: ["[[API-082]]", "[[API-083]]", "[[API-203]]", "[[IMPREC-019]]"]
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

2026-06-01 사용자 확정: 사용자별 저장·데이터마트 미정합 / 2026-07-30: 구현 API 재매핑(retired API-080 제거, API-082/083 유지) + SAM2 노출 반영 / 2026-08 다운로드 API(API-203) 신설 및 보존기간(7일, 설정 가능) 자동 삭제 정책(DFEAT-055) 추가 — 구 서술 '본인 데이터를 기간 내 다운로드할 수 있다'는 실재하지 않는 다운로드 API 를 가리키고 있었다.

### 2026-08-26 — ADR-013 정합
- 데이터마트 로드분의 메타(촬영환경·프레임 설명·개인정보 판정·시계열 메타)와 이벤트 어노테이션의 표시·수정·추가를 이 기능의 책임으로 명시했다. 적재는 포털 전용 저장소이며 데이터마트와 원본 동결본은 수정하지 않는다 — 라벨 저장과 같은 단방향 축이다.
- 미제공의 축을 정밀화했다. 구 서술 '오토라벨링·SAM2·VLM·검수·버전관리 모두 포털 미제공'은 시계열 축을 통째로 미제공으로 단정하고 있었다. 미제공인 것은 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)이며 그 결과물을 사람이 보고 고치는 일이 아니다.
- 선행 드리프트 정정 — SAM2 포털 예외 노출 서술이 2026-08-03 철회 사실을 반영하지 못한 채 남아 있었다. 철회 이력은 지우지 않고 그 판정 결과를 함께 적었다.
- 오토라벨링(YOLO)·SAM2 인터랙티브 분할·자동추적·키포인트·검수·버전관리 미제공과 라벨 도형 allowlist(BBOX·POLYGON) 는 그대로 유지한다.
- 포털 전용 저장소의 물리 테이블은 미확정이라 적지 않았다.

### status

new

### decided_by

ADR-013

## description

포털 사용자가 데이터마트 영상의 라벨을 수정·저장하면 원본/데이터마트를 변경하지 않고 사용자별 작업 데이터(LS_PORTAL_USER_LABEL)로 별도 적재한다(GET/POST /v1/portal/user-labels = API-082/083). 데이터마트에서 불러온 영상의 메타(촬영환경·프레임 설명·개인정보 판정·시계열 메타)와 이벤트 어노테이션도 포털 작업 화면에 표시하며 포털 사용자가 직접 수정·추가할 수 있다. 포털에서 고친 메타·이벤트 어노테이션은 라벨과 같은 축으로 포털 전용 저장소에만 적재하며 데이터마트와 원본 동결본(영상 메타 동결 스냅샷·이벤트 어노테이션 동결본)을 수정하지 않는다(단방향). 저장 데이터는 데이터마트에 정합/반영되지 않으며(단방향), 본인 데이터를 프레임 이미지+라벨 JSON+비식별 영상(있을 경우) ZIP으로 다운로드할 수 있다(GET /v1/portal/datamart/videos/{rawSn}/download = API-203). 저장 데이터는 마지막 저장일로부터 보존기간(기본 7일, 설정 가능) 경과 시 자동 삭제되며 삭제는 비가역이다. 저장 가능한 도형은 BBOX·POLYGON 만이고 서버 allowlist 가 그 외 값을 400 으로 거부하며, 좌표 개수도 BBOX 는 정확히 2점, POLYGON 은 3~200점으로 강제된다(위반 시 400). [폐기] SAM2 분할·추적(sam2-segment·sam2-track)을 포털에 예외적으로 노출한다 — 2026-07-15 에 좌표만 반환하고 저장하지 않는다는 근거로 한 번 열었다가 2026-08-03 보안 전수검증에서 높은 위험으로 판정되어 되돌린 서술이다. 포털 전용 SAM2 경로는 두지 않으며 FE 도 채널 분기를 두지 않는다(UC-024 와 동일). 되살리려면 그 보안 판정부터 뒤집어야 한다. 오토라벨링(YOLO)·SAM2 인터랙티브 분할·자동추적·키포인트·검수·버전관리는 포털 미제공이다(ADR-013). 시계열 축에서 미제공인 것은 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)이며, 그 결과물인 시계열 메타를 화면에 표시하고 사람이 수정·추가하는 것은 미제공 대상이 아니다. 구 구현 경로 POST /v1/portal/labels(API-080)는 retired — 실제 저장 경로는 API-082/083이다. 본인 자산(이미지/영상) 직접 업로드+라벨링은 별도 기능(포털 자산 업로드·수동 라벨링)으로 분리되어 있다. 묶음 내려받기는 클라이언트가 연결을 끊으면 서버가 전송을 이어가지 않으며 그 중단은 실패로 다루지 않는다. 다시 받으면 요청량 제한을 새로 적용받는다.

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-019

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-17T04:34:31.275Z

### module_paths

- backend/src/main/java/kr/co/cudo/authoring/portal/controller/PortalDatamartDownloadController.java
- backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalDatamartDownloadService.java

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
- API-203

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
