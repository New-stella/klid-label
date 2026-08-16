---
logicraft_item: DOMAIN-004
type: domain
version: 11
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:52.073Z
status: NEW
prev_version: null
content_hash: fd5fc9a7233318a9484fa31fa6ec02a4e3e6de2f2b8b3dca2c04cdd4c63d1f44
stale: false
raw: ./_raw/DOMAIN-004.json
links:
  collaborates_with: ["[[DOMAIN-010]]"]
  migrated_from: ["[[LEGACY-005]]"]
  applies_to_backward: ["[[NFR-009]]", "[[NFR-012]]"]
  belongs_to_domain_backward: ["[[ADR-019]]", "[[ADR-026]]", "[[ADR-035]]", "[[ADR-040]]", "[[ADR-041]]", "[[API-020]]", "[[API-065]]", "[[API-093]]", "[[API-113]]", "[[API-119]]", "[[API-120]]", "[[API-121]]", "[[API-122]]", "[[API-123]]", "[[API-124]]", "[[API-152]]", "[[API-177]]", "[[CDIAG-005]]", "[[CMP-008]]", "[[DFEAT-018]]", "[[DFEAT-019]]", "[[DFEAT-020]]", "[[SCREEN-027]]", "[[UC-004]]", "[[UC-005]]", "[[UC-006]]"]
  collaborates_with_backward: ["[[DOMAIN-010]]"]
  derived_domain_backward: ["[[AC-004]]", "[[AC-005]]"]
  implements_in_backward: ["[[MOD-006]]", "[[MOD-018]]", "[[MOD-020]]", "[[MOD-041]]"]
---

# AI 보조 라벨링

## name

AI 보조 라벨링

## brownfield

### notes

1차 도메인(II-003~007). Pass 2 고도화(AI 보조 라벨링) modified

### status

preserved

### legacy_source

#### type

module

#### legacy_artifact_id

LEGACY-005

## description

라벨러의 수작업을 AI 추론으로 보조하는 도메인. 추론은 상태 없는 ai-server(FastAPI)가 수행하고 Spring Boot 가 오케스트레이션을 맡는다.

[구성]
· Auto Labeling — YOLOX(onnxruntime) 객체 탐지. ★ultralytics(AGPL-3.0)를 쓰지 않고 permissive 백엔드로 단일화했다(ADR-041).
· AI Tool — SAM2 클릭/박스 프롬프트 분할(폴리곤 + 신뢰도 반환).
· SAM2 Track — 박스를 N 프레임에 전파하는 VOS 추적. 추적 출력 형태는 선택한 객체의 형태를 따른다(ADR-040).
· 트랙 모드 — 프레임 간 좌표 선형보간(CVAT 알고리즘 포팅).

[★매칭축은 COCO 검출클래스다 (ADR-019)] 프리셋↔검출 라벨 매칭을 마스터 라벨명(한글)이 아니라 LS_LABEL.DTCT_TYPE_CD(COCO 80 클래스명)로 일원화했다. 한글 라벨명과 COCO 영문명이 1:1 대응하지 않기 때문이다. 배치·온라인·SAM2·프리셋 토글 4경로가 모두 같은 축을 쓴다. 매핑이 없는 라벨은 화면에 보이되 선택 불가이며, 강제는 BE 가 화이트리스트 교집합으로 건다 — FE 요청을 신뢰하지 않는다.

[★2경로의 저장 시맨틱이 다르다] 배치 오토라벨은 결과를 DB 에 저장하고, 온라인 오토라벨은 반환만 하고 저장하지 않는다(사용자가 캔버스에서 확인 후 저장).

[실행 대상] YOLO/SAM2 는 원본 이미지에만 실행하고 해상도가 같으므로 비식별본과 좌표를 공유한다(별도 실행 없음). 추론 서버 호출은 단일 클라이언트로 통일하고 타임아웃·서킷브레이커를 적용한다.

[범위 밖] 모델 학습·파인튜닝은 외부 책임이다. FE 문구에는 YOLO/SAM2 같은 기술 모델명을 노출하지 않는다.

## upstream_of

_(empty)_

## context_kind

core

## collaborators

- DOMAIN-010

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: AI Tool
- **meaning**: 객체 일부를 클릭하거나 박스를 그리면 경계를 자동 분할해 주는 도구(SAM2)

### [2]

- **term**: Auto Labeling
- **meaning**: 사람·차량 등을 자동 탐지해 라벨을 생성하는 도구(YOLOX)

### [3]

- **term**: DTCT_TYPE_CD
- **meaning**: 라벨 마스터의 COCO 검출클래스 매핑 컬럼. 오토라벨 매칭의 단일 진실원이다

### [4]

- **term**: SAM2 Track
- **meaning**: 박스 하나로 N 프레임까지 추적을 전파하는 VOS 기능

### [5]

- **term**: 선형보간
- **meaning**: 트랙 프레임 간 좌표를 Linear Interpolation 으로 채우는 알고리즘

### [6]

- **term**: 온라인 오토라벨
- **meaning**: 사용자가 화면에서 즉시 돌리는 추론. 결과를 반환만 하고 저장하지 않는다
