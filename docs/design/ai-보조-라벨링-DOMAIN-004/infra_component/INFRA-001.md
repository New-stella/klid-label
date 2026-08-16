---
logicraft_item: INFRA-001
type: infra_component
version: 4
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:52.075Z
status: NEW
prev_version: null
content_hash: e260864ffd65dd133a311403922caea920c80b0d695579eb57f690a3aa6ef8c7
stale: false
raw: ./_raw/INFRA-001.json
links:
  deploys_to: ["[[API-113]]", "[[API-119]]", "[[API-120]]", "[[API-121]]", "[[API-122]]", "[[FEAT-001]]"]
---

# ai-server (YOLO/SAM2 추론 서버, 내부)

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

1

## owner

저작도구 운영팀

## status

active

## provider

저작도구 운영(내부 인프라)

## brownfield

### status

new

### change_kind

- component-add

### diff_summary

고도화 신규 — Nuclio 스타일 함수 엔드포인트 분리 추론 서버 (구 EXTSYS-001 external_system 오분류를 infra_component로 재분류)

## description

저작도구 내부 추론 인프라(외부 시스템 아님). Stateless 경량 추론 서버(Python FastAPI). YOLO 객체탐지/추적·SAM2 세그멘테이션/트랙 추론을 로컬 수행. 인증·DB·상태 없음. 오케스트레이션은 백엔드가 맡으며 단일 HTTP 클라이언트로 호출하고 타임아웃 60초와 서킷브레이커를 적용한다. GPU Worker 다중 인스턴스 수평 확장. ※ VLM 추론 본체는 외부 서비스이며 ai-server의 vlm 라우터는 외부 VLM 호출 어댑터일 뿐(EXTSYS-002 별개).

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

## depends_on_infra

_(empty)_

## deploys_features

- FEAT-001
