---
logicraft_item: DOMAIN-006
type: domain
version: 8
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:41:56.609Z
status: NEW
prev_version: null
content_hash: f2caada1c453a9de9aab596d78618e5244695d889c6af86a0b8f732b74ed5d2c
stale: true
raw: ./_raw/DOMAIN-006.json
links:
  collaborates_with: ["[[DOMAIN-005]]"]
  applies_to_backward: ["[[NFR-011]]", "[[NFR-018]]", "[[NFR-019]]"]
  belongs_to_domain_backward: ["[[API-055]]", "[[API-056]]", "[[API-057]]", "[[API-058]]", "[[CDIAG-009]]", "[[DFEAT-026]]", "[[DFEAT-027]]", "[[DFEAT-028]]", "[[SCREEN-011]]", "[[SCREEN-020]]", "[[SCREEN-021]]", "[[SD-014]]"]
  collaborates_with_backward: ["[[DOMAIN-005]]"]
  implements_in_backward: ["[[MOD-012]]"]
---

# 통계·대시보드

## name

통계·대시보드

## brownfield

### notes

1차 도메인. 2차에서도 유지

### status

preserved

## description

작업자·검수자 대시보드와 영상/작업 통계를 제공하는 도메인. 월별·일별 작업량, 전체·권한별·상태별 집계, 일일 진행률을 다룬다.

[★주수치는 검수완료 기준] 산출물 목표(이미지 10만장·영상 5,000건)는 확정된 학습데이터 기준이므로 검수완료 수치를 주수치로 쓰고 전체(진행중 포함) 수치를 병기한다. 둘을 섞어 하나로 보이면 달성률이 부풀려진다.

[★집계는 BE 에서 전체 기준으로] 필터·집계를 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다. 목록 정렬은 시간축 단일 기준이고 '지금 처리할 것'은 필터·KPI 카드로 표현한다(ADR-038).

[상태 축] 배정·작업중·완료·반려 기준으로 집계한다. 구 상태 '확인요청'은 관리자 역할 통합(ADR-003)으로 폐기됐다.

[포함 범위] 증강·해상도 파생영상도 집계에 포함된다.

## upstream_of

_(empty)_

## context_kind

supporting

## collaborators

- DOMAIN-005

## integrates_with

_(empty)_

## uses_integrations

_(empty)_

## ubiquitous_language

### [1]

- **term**: 진행률
- **meaning**: 영상/작업 단위 완료 비율

### [2]

- **term**: 상태별 통계
- **meaning**: 배정/작업중/완료/반려 건수 집계

### [3]

- **term**: 검수완료 기준 수치
- **meaning**: 학습데이터로 확정된 분만 센 주수치. 전체 수치와 함께 병기한다

### [4]

- **term**: 일별 작업량
- **meaning**: 일자별 처리 건수 추이
