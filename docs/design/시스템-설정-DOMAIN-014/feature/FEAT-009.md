---
logicraft_item: FEAT-009
type: feature
version: 3
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T10:53:48.344Z
status: NEW
prev_version: null
content_hash: 091368476aeebcffad16fba1fa41e708b380be625a77a0940abbc650f202dd02
stale: false
raw: ./_raw/FEAT-009.json
links:
  implements: ["[[REQ-026]]"]
  covers_backward: ["[[AC-024]]"]
  granted_on_backward: ["[[ROLE-001]]"]
  implements_backward: ["[[API-016]]", "[[API-017]]", "[[API-065]]", "[[API-132]]", "[[API-133]]", "[[API-134]]", "[[API-135]]", "[[API-219]]", "[[API-220]]"]
  realizes_backward: ["[[MOD-010]]", "[[MOD-022]]", "[[UC-022]]"]
  references_backward: ["[[CDIAG-014]]"]
  specializes_backward: ["[[DFEAT-050]]"]
---

# VLM 시계열 메타 검토·수정

## priority

must

## brownfield

### status

new

### diff_summary

보완요청 SFR-17 영상 메타 요구로 VLM 시계열 메타 검토를 글로벌 FEAT로 정식화(UC-022 롤업)

## complexity

moderate

## user_story

### goal

VLM 이 생성한 시계열 메타를 검토·수정·승인하기를 원한다

### actor

검수자(REVIEWER)

### benefit

검증된 메타만 영상 학습데이터에 포함된다

## description

외부 VLM 이 생성한 시계열 메타를 검수큐(LS_DATA_META_REVIEW)에서 REVIEWER 가 검토·수정·승인한다. VLM 본체는 외부 책임이며 저작도구는 시계열 정보 획득 연동 + 메타 검수만 보유한다. 승인된 메타(RVW_STTS_CD='APPROVED')만 학습데이터에 포함. 보완요청 SFR-17 영상 학습데이터 메타 요구 반영.

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

## implements_requirements

- REQ-026
