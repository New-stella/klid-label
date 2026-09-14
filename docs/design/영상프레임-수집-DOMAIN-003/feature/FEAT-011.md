---
logicraft_item: FEAT-011
type: feature
version: 1
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-14T05:35:33.825Z
status: NEW
prev_version: null
content_hash: ac15024a3b721e5a4342865165fa5284fb599be8477604f5113cda5c4024125c
stale: false
raw: ./_raw/FEAT-011.json
links:
  implements: ["[[REQ-019]]"]
  realizes_backward: ["[[UC-018]]", "[[UC-042]]"]
---

# 영상 수집·적재 (관제 인입 폴링·직접 투입)

## priority

must

## complexity

moderate

## user_story

### goal

학습 대상 영상을 저작도구에 넘겨 가공을 시작하게 하기를 원한다

### actor

관제지원시스템

### benefit

수집한 영상이 사람 손을 거치지 않고 가공 흐름에 올라간다

## description

학습 대상 영상을 저작도구로 들여오는 기능이다. 관제지원시스템이 저작도구 소유 인입 원장에 영상 메타를 처리 대기 상태로 넣으면 저작도구의 주기 배치가 미처리 행을 가져와 원시 영상으로 적재하고 후속 처리에 태운다. 저작도구가 관제지원시스템을 호출하는 구간은 없다. 영상 본문은 오가지 않고 행에 실린 경로로 공유 저장소에서 읽는다. 인입을 기다리지 않고 파일 업로드로 영상 한 건을 직접 투입하는 경로도 함께 둔다. 적재된 영상은 비식별을 선두로 하는 처리 흐름으로 넘어간다.

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
