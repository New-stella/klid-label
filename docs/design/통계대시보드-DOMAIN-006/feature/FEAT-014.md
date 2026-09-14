---
logicraft_item: FEAT-014
type: feature
version: 1
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-14T05:35:47.027Z
status: NEW
prev_version: null
content_hash: 771f51f26ba38bb7f2e4ff83f8a0066f8175cc4c6e87a2ff588a5a09094d3647
stale: false
raw: ./_raw/FEAT-014.json
links:
  realizes_backward: ["[[UC-033]]"]
---

# 학습데이터 구축 현황 통계

## priority

should

## complexity

simple

## user_story

### goal

학습데이터가 목표 대비 얼마나 쌓였고 어디가 막혀 있는지 알기를 원한다

### actor

검수자

### benefit

남은 일의 규모와 병목을 알고 작업을 배분할 수 있다

## description

학습데이터 구축이 어디까지 왔는지 확인하는 기능이다. 검수 완료를 기준으로 한 확정 수치와 진행 중을 포함한 전체 수치를 함께 보여 산출물 목표 대비 진척과 병목을 파악하게 한다. 집계 기준은 관제 이벤트 유형의 표시명 그룹이다.

구현하는 요구사항 자리는 비워 둔다. 비어 있는 것은 대응 항목을 찾지 못했다는 뜻이 아니라 대응 항목이 없다는 결정의 표현이다. 요구사항을 전건 대조하면 구축 현황을 보여 달라고 요구하는 항목이 없다. 가까워 보이는 이미지·영상 학습데이터 구축 요구는 산출물 목표 규모를 수용하라는 용량 요구이지 그 진척을 보고하라는 요구가 아니며, 그 요구는 구축 기능들이 이미 담당한다. 그럼에도 이 기능을 두는 이유는 목표 규모를 향해 가는 동안 어디가 막혀 있는지 알 수단이 실제로 필요하기 때문이다. 추적성을 점검하는 자리에서 이 기능이 요구사항에 연결되지 않았다는 경고가 나올 수 있으나 그것은 의도된 상태다. 그 자리를 채우려고 성격이 다른 요구사항을 끌어다 연결하지 않는다 — 연결하면 그 요구사항의 범위가 사실과 달라진다.

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

_(empty)_
