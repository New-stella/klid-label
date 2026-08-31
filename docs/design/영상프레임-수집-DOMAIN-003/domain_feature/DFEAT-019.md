---
logicraft_item: DFEAT-019
type: domain_feature
version: 9
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-31T11:11:17.772Z
status: NEW
prev_version: null
content_hash: 1b9890188231935f9f751c410ab40111d077cb206185c1af387422b1ad389bbe
stale: true
raw: ./_raw/DFEAT-019.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
  implements: ["[[API-123]]", "[[API-124]]"]
  migrated_from: ["[[LEGACY-067]]"]
  specializes: ["[[FEAT-001]]"]
  depicts_backward: ["[[CDIAG-005]]"]
  realizes_backward: ["[[UC-034]]", "[[UC-038]]", "[[UC-039]]"]
  references_backward: ["[[ADR-047]]", "[[CDIAG-005]]"]
---

# Auto Labeling (YOLO 객체 탐지)

## title

Auto Labeling (YOLO 객체 탐지)

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

2026-07-30 갱신 — RT-DETRv2 제거·YOLOX(onnxruntime, Apache-2.0) 단일화(ADR-041), 온디맨드 트랙 API(API-123) 신설, confThreshold/simplifyTolerance 1회성 조절, clamp 정규화 3경로(온라인/배치/트랙) 통일

### status

preserved

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-02-02-06

#### legacy_artifact_id

LEGACY-067

## user_story

### as

라벨링 작업자

### i_want

AI가 프레임 객체를 자동 탐지해 라벨 초기값을 제안하기를

### so_that

라벨링 초기 입력 부하를 줄인다

## description

YOLOX(onnxruntime, Apache-2.0) 백엔드로 프레임 내 객체를 자동 탐지해 클래스·좌표를 제안한다(RT-DETRv2 제거·YOLOX 단일화 — ultralytics AGPL-3.0 회피 라이선스 정책, ADR-041). 온라인(캔버스 AI탐지)·배치·온디맨드 트랙(POST /v1/frames/{srcSn}/yolo-track = API-123) 3경로가 좌표 clamp 정규화 로직을 공통 유틸로 통일한다. confThreshold/simplifyTolerance는 요청마다 1회성(비영속) 조절 가능. 탐지 후보는 라벨 마스터(LS_LABEL) 기준으로 노출하되 DTCT_TYPE_CD(COCO 클래스 매핑)가 지정된 라벨만 실제 검출 가능(미매핑은 표시만, 선택 불가) — BE 가 FE 요청을 신뢰하지 않고 마스터 화이트리스트와의 교집합만 ai-server로 전달한다. 검출 결과를 응답으로 내보내는 모든 경로에서 라벨 마스터 식별자를 해석하는 주체는 서버다 — 온라인·배치·온디맨드 트랙 어느 경로든 검출 클래스 축으로 해석한 마스터 식별자를 응답에 실어 내려주며, 화면이 검출 클래스명으로 마스터를 다시 찾아내지 않는다(같은 판정이 두 곳에 있으면 한쪽이 낡는다). 대응하는 마스터가 없거나 그 클래스에 매핑이 지정되지 않은 검출은 식별자를 비워 내보내고 값을 지어내지 않는다. (1차 baseline 화면 SKKLID-UI-02-02-06 계승, 2차 재구성)

## invokes_apis

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-355

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:20.239Z

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## specializes_feature

FEAT-001

## implemented_by_endpoints

- API-124
- API-123

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
