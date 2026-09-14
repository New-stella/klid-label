---
logicraft_item: FEAT-013
type: feature
version: 1
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-14T05:35:53.622Z
status: NEW
prev_version: null
content_hash: 28b3140b2b3bac884752eeccceaa60b8c478ca112ea3e9f91c5f2fabd7e41337
stale: false
raw: ./_raw/FEAT-013.json
links:
  implements: ["[[REQ-019]]", "[[REQ-022]]", "[[REQ-025]]", "[[REQ-026]]"]
  realizes_backward: ["[[UC-021]]"]
---

# 수동 라벨링 (도형 어노테이션·속성 부여)

## priority

must

## complexity

complex

## user_story

### goal

프레임의 객체에 도형과 속성을 부여해 학습데이터를 만들기를 원한다

### actor

라벨링 작업자

### benefit

수집한 영상이 학습에 쓸 수 있는 데이터가 된다

## description

작업자가 프레임 위에 직접 라벨을 그리고 속성을 부여하는 기능이다. 바운딩박스·폴리곤·세그멘테이션 등 도형으로 객체를 표시하고 라벨 마스터가 정의한 분류와 속성값을 지정하며 작업본을 임시저장한다. 인공지능 보조 라벨링이 만들어 준 결과를 사람이 확인·수정하는 자리도 여기다. 학습데이터의 실체를 만드는 핵심 작업이며 검수를 거쳐 학습데이터셋으로 확정된다.

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

## implements_requirements

- REQ-019
- REQ-022
- REQ-025
- REQ-026
