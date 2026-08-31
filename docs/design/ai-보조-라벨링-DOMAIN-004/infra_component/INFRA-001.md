---
logicraft_item: INFRA-001
type: infra_component
version: 5
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-31T11:09:00.878Z
status: CHANGED
prev_version: 4
content_hash: 716cda7067f173c10e92c3e7aa4f8529c9c0f5dc5de1b60e649a727044d57910
stale: false
raw: ./_raw/INFRA-001.json
links:
  depends_on: ["[[INFRA-003]]"]
  deploys_to: ["[[API-113]]", "[[API-119]]", "[[API-120]]", "[[API-121]]", "[[API-122]]", "[[FEAT-001]]"]
---

# ai-server (오토라벨링 추론 서버) — GPU 장비에 이중화 배포

## sla

_(empty)_

## cost

### currency

USD

## kind

compute

## name

ai-server (YOLO/SAM2 추론 서버)

## tech

### engine

Python FastAPI · YOLOX(onnxruntime)/SAM2(torch) 추론

### replicas

2

## owner

저작도구 운영팀(프로세스) · 협력 파트너(장비)

## status

active

## provider

협력 파트너 장비에 배포한다 — 장비는 우리 소유가 아니다

## brownfield

### status

new

### change_kind

- component-add

### diff_summary

고도화 신규 — Nuclio 스타일 함수 엔드포인트 분리 추론 서버 (구 EXTSYS-001 external_system 오분류를 infra_component로 재분류)

## description

저작도구의 오토라벨링 추론을 담당하는 경량 추론 서버. 객체탐지·추적과 분할·트랙 추론을 수행하며 상태·인증·저장소를 갖지 않는다. 오케스트레이션은 백엔드가 맡으며 호출을 한 곳으로 모아 타임아웃과 서킷브레이커를 건다.

## 어디에 배포되는가
**우리 장비가 아니다** — 협력 파트너가 소유한 GPU 장비 두 대에 **이중화로** 올라간다.
⚠ 그 장비에는 비식별화 서버가 함께 있고 생성형 AI 와 시계열 분석도 추가될 예정이라, 가속기를 여럿이 나눠 쓰게 된다.

## ★ 이중화인데 부를 수 있는 주소는 하나다 — 미해결
백엔드가 이 서버를 부를 때 쓰는 설정값은 **주소 하나**라, 두 대를 나눠 쓸 배선이 없다. 셋 중 하나를 정해야 한다.
1. 앞단에서 분산한다 — 앱 서버의 웹 서버가 두 장비로 중계. 설정만으로 된다
2. 백엔드가 목록을 갖고 분산한다 — 코드 변경이 필요하다
3. 한 쪽 고정 · 장애 시 수동 전환 — 지금 형상으로 유일하게 되는 것이며 이중화의 효과는 절반이다

⚠ 부하를 균등하게 나누는 것이 최선이 아닐 수 있다 — 첫 번째 장비는 비식별화까지 함께 돌므로 그쪽이 먼저 포화된다.

## 설정 주의
주소 기본값이 자기 자신을 가리키므로 **두 장비 구성에서는 반드시 틀린다.** 틀려도 기동과 상태점검은 정상이고 **오토라벨링만 조용히 실패**한다. 설치 직후 주소를 따로 확인해야 한다.

## 범위 밖
시계열 분석 본체는 외부 서비스이며, 이 서버의 시계열 경로는 그 외부 서비스를 부르는 어댑터일 뿐이다.

## environment

production

## deploys_apis

- API-113
- API-119
- API-120
- API-121
- API-122

## persists_erds

_(empty)_

## attached_files

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

## depends_on_infra

- INFRA-003

## deploys_features

- FEAT-001
