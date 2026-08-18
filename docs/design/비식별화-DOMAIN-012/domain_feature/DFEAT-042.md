---
logicraft_item: DFEAT-042
type: domain_feature
version: 11
domain: DOMAIN-012
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.726Z
status: NEW
prev_version: null
content_hash: 92bedffb1e8134ae9ee5fccd8cffb2938b2af03e571aa198157ddd6a3793337d
stale: false
raw: ./_raw/DFEAT-042.json
links:
  belongs_to_domain: ["[[DOMAIN-012]]"]
  specializes: ["[[FEAT-005]]"]
  verifies: ["[[AC-011]]", "[[AC-013]]", "[[AC-016]]"]
  depicts_backward: ["[[CDIAG-003]]"]
  realizes_backward: ["[[UC-013]]"]
  references_backward: ["[[CDIAG-003]]"]
---

# 비식별 대상·범위 설정 (전체 영상 자동 실행 — 게이팅 폐지)

## title

비식별 대상·범위 설정 (전체 영상 자동 실행 — 게이팅 폐지)

## status

designed

## consumes

_(empty)_

## priority

should

## triggers

_(empty)_

## brownfield

### notes

2026-07-30: 게이팅 폐지로 제목·본문 재작성 — 전체 영상 무조건 자동 비식별(적재 직후 파이프라인 선두), PRVC/PSDO 조건부 판단 정책 폐기

### status

new

### decided_by

ADR-006

## description

전체 영상 비식별화가 파이프라인 선두 단계로 무조건 자동 실행된다(영상 적재 직후 VideoIngestedEvent 가 커밋 이후 비동기로 트리거). 개인정보 유형(PRVC/PSDO/ANONY) 값에 따라 비식별 실행 여부를 조건부로 판단하던 구 게이팅 정책은 폐기됐다 — 유형 분류는 결과 표시·통계용 메타로만 남는다. 기존 '비식별 결과·이력 검토'는 외부 비식별 솔루션 제공 프로그램으로 이관되어 본 기능에서 제외됨(UC-012·SCREEN-016/017·API-051/052 폐기 — FEAT-006 자체는 폐기가 아니라 저작도구가 보유한 경량 상태·이력 확인 축으로 축소·재정의됐다). 또한 이 기능은 외부 비식별 솔루션에 위탁할 때 실리는 마스킹 옵션을 REVIEWER 가 관리 화면에서 설정·저장하는 책임을 함께 갖는다. 설정 항목은 3종이다 — 마스킹 방식(kpst.deid.masking-type · 색상 0 / 모자이크 2 / 블러 3, 1 은 벤더 미할당이라 연속 범위가 아니다), 마스킹 범위 배율(kpst.deid.masking-range · 0.5~2.0), 프레임 저장 여부(kpst.deid.db-save · 저장 안 함 0 / 저장 1). 벤더가 미지원이라고 회신한 exp_quality·exp_format 은 설정으로 열지 않고 규격 기본값을 그대로 싣는다. 값은 전역 1벌이며 LS_SYSTEM_CONFIG 에 키-값으로 보관되고, 비식별 옵션 전용 엔드포인트를 따로 두지 않고 일반 시스템 설정 키-값 경로(PUT /v1/manage/configs/{key}, REVIEWER 전용)를 공유한다. 저장한 값은 그 다음부터 새로 위탁하는 영상에 적용되며, 설정 캐시 TTL 때문에 다른 노드는 최대 60초 뒤에 새 값으로 위탁한다. 값 검증은 화면과 서버 양쪽에서 하고, 서버는 위탁 직전에 읽어온 값도 허용값·범위로 재확인해 벗어나면 규격 기본값으로 폴백한다 — 설정 조회 실패나 허용 목록 밖 값이 비식별 위탁 자체를 막지 않는다. 이 옵션 설정 책임은 위 전체 영상 자동 비식별(게이팅 폐지)과 별개 축이다 — 옵션은 개인정보를 어떻게 가릴지를 정할 뿐, 비식별을 실행할지 말지를 정하지 않는다.

## invokes_apis

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

## uses_constants

_(empty)_

## acceptance_rules

_(empty)_

## persists_in_tables

_(empty)_

## related_acceptances

- AC-016
- AC-013
- AC-011

## specializes_feature

FEAT-005

## implemented_by_endpoints

_(empty)_

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
