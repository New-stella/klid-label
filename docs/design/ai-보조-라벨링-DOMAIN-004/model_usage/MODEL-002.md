---
logicraft_item: MODEL-002
type: model_usage
version: 1
domain: DOMAIN-004
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-24T10:55:56.251Z
status: NEW
prev_version: null
content_hash: 70dbd4d54cf42ae6827ac7aed8165f72897ea865dd09c1475c3c10991d624c1f
stale: false
raw: ./_raw/MODEL-002.json
links:
  belongs_to_domain: ["[[DOMAIN-004]]"]
---

# 분할 모델 — Meta SAM2

## cost

### currency

USD

## title

분할 모델 — Meta SAM2

## status

active

## provider

local

## rationale

클릭·박스 지목으로 객체 외곽을 따내는 분할과 프레임 간 전파를 맡는다. 탐지 모델과 같은 이유로 허용적 라이선스 계열에서 골랐다.

## model_name

facebook/sam2-hiera-tiny

## routing_rules

_(empty)_

## benchmark_notes

모델은 공개 저장소에서 내려받아 올린다. 목 응답일 때는 화면이 결과를 자동 적용하지 않는다. 호출 한 건의 대기 상한은 60초다.

## used_by_prompts

_(empty)_
