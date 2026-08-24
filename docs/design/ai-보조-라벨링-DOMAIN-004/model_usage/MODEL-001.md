---
logicraft_item: MODEL-001
type: model_usage
version: 1
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-24T10:55:56.251Z
status: NEW
prev_version: null
content_hash: 011bd62b6993e26dede38ba9a6c9236c0f2c8e432b0c6c1e8407c1ad1e91eb29
stale: false
raw: ./_raw/MODEL-001.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
---

# 객체 탐지 모델 — YOLOX (ONNX Runtime)

## cost

### currency

USD

## title

객체 탐지 모델 — YOLOX (ONNX Runtime)

## status

active

## provider

local

## rationale

허용적 라이선스만 쓴다는 정책이 선택을 정했다. 널리 쓰이는 대체 구현이 강한 상호주의 라이선스라 채택할 수 없었고, 탐지와 분할을 모두 허용적 라이선스 계열로 맞추기 위해 이 모델을 골랐다. 추적은 같은 계열의 별도 추적기가 맡는다.

## model_name

yolox_s (ONNX)

## routing_rules

_(empty)_

## benchmark_notes

가중치 파일이 없으면 추론 서버가 실제 추론 대신 목 응답을 돌려주고 그 사유를 함께 싣는다. 호출 한 건의 대기 상한은 60초이며 그 값은 정책 클래스 한 곳에서만 정한다.

## used_by_prompts

_(empty)_
