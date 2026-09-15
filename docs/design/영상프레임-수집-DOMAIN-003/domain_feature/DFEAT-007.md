---
logicraft_item: DFEAT-007
type: domain_feature
version: 11
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T15:34:12.049Z
status: CHANGED
prev_version: 10
content_hash: 624c47d82140589f98620bfc020451fd878b14b0bc2934f1d9cfc266265350b3
stale: false
raw: ./_raw/DFEAT-007.json
links:
  based_on: ["[[ADR-001]]"]
  belongs_to_domain: ["[[DOMAIN-003]]"]
  implements: ["[[API-042]]", "[[API-043]]", "[[API-260]]", "[[API-261]]", "[[IMPREC-351]]"]
  migrated_from: ["[[LEGACY-119]]"]
  depicts_backward: ["[[CDIAG-001]]", "[[CMP-010]]"]
  realizes_backward: ["[[UC-018]]", "[[UC-043]]"]
  references_backward: ["[[ADR-069]]"]
---

# 영상/이미지 관리

## title

영상/이미지 관리

## status

designed

## consumes

_(empty)_

## priority

must

## triggers

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-001

### change_kind

- scope-shrink

### diff_summary

1차 영상/이미지 관리·프로젝트 배정 → 2차 영상 단위 관리(프로젝트 배정 제거), 영상·프레임 수집 도메인으로 이관

### legacy_source

#### repo

KLID-AI-PF-001

#### type

screen

#### identifier

SKKLID-UI-03-03-01

#### legacy_artifact_id

LEGACY-119

## user_story

### as

검수자(REVIEWER)·라벨링 작업자(WORKER)

### i_want

영상/이미지 데이터를 관리하기를

### so_that

라벨링 대상 데이터를 영상 단위로 공급한다

## description

수집된 영상/이미지를 목록·상세 조회하고, 유휴 데이터를 처리한다. 작업 단위는 영상 1건이며 프로젝트 단위 개념은 사용하지 않는다. (1차 baseline, 화면 SKKLID-UI-03-03-01~04)

## 가시성 — 제외·복원

영상에 **제외 표시**를 두어 저작도구 화면 목록 세 곳(영상 처리 현황 · 작업 목록 · 검수 목록)에서 뺀다. ★**행을 지우지 않고 표시만 하므로 언제든 되돌릴 수 있다.** 인가는 **검수자 이상**이며 역할 계층으로 관리자도 그대로 수행한다(작업자 불가). 누가 · 언제 · 왜 바꿨는지는 작업 이벤트 원장에 남기되 ★**감추는 쪽만 사유를 남긴다** — 제외는 사유 필수, 복원은 사유 없음. 제외된 영상은 배정 대상이 되지 않으며, 배정이 있는 영상은 제외가 거부된다(배정을 풀면 되는 일시 조건).

### ★경계 — 이것이 이 축 서술의 핵심이다

제외는 **저작도구 화면 시야만**이다. 다음은 전부 **무변경**이며 제외된 영상도 종전과 똑같이 흐른다 — 배치 파이프라인 · 관제 통지 · 데이터마트 조회 뷰 · 학습데이터 산출물 · 관제 조회 창구 · 통계 · 포털 채널.

- ★**산출물 콘텐츠 해시의 입력이 아니다.** 넣으면 제외를 켰다 끄는 것만으로 산출물이 재생성되고 수정 통지가 나간다 — 내용은 하나도 바뀌지 않았는데.
- ★**라벨링 화면 진입을 막지 않는다.** 그 결과 검수자가 주소를 직접 넣으면 제외된 영상의 상세가 열린다 — 인지하고 수용한 비대칭이며 결함으로 다시 보고하지 말 것.

⚠ 이 경계를 적어 두지 않으면 다음 사람이 「일관성」을 이유로 배치·관제에도 같은 조건을 붙인다. 이 서술의 목적이 그것을 막는 것이다.

### 판정의 소유

제외 판정은 **한 곳이 소유하고 모든 조회 경로가 그것을 공유한다.** 경로마다 따로 판정하면 한 곳만 빠져도 제외한 영상이 화면에 샌다.

⚠ 가시성은 **개인정보 성질 축**(익명 · 가명 · 개인정보 포함 여부)과 **다른 축**이다. 혼동해 함께 다루지 말 것.

근거 결정 ADR-069.

## invokes_apis

_(empty)_

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-351

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:26:19.774Z

### module_paths

_(empty)_

## uses_constants

_(empty)_

## acceptance_rules

### [1]

- **then**: 영상 처리 현황 · 작업 목록 · 검수 목록 세 곳에서 그 영상이 보이지 않고, 행은 지워지지 않으며, 작업 이벤트 원장에 누가 · 그 시점 역할 · 언제 · 사유가 남는다
- **when**: 그 영상을 사유와 함께 제외한다
- **given**: 배정이 없고 화면 목록에 보이는 영상이 있고 검수자 이상이 로그인해 있다

### [2]

- **then**: 세 목록에 다시 나타나고, 이력에는 누가 · 그 시점 역할 · 언제만 남으며 사유는 요구하지도 남기지도 않는다
- **when**: 검수자 이상이 그 영상을 복원한다
- **given**: 제외된 영상이 있다

### [3]

- **then**: 거부되고 제외 표시가 서지 않으며, 배정을 풀면 다시 시도할 수 있는 일시 조건임이 안내된다
- **when**: 그 영상을 제외하려 한다
- **given**: 배정이 있는 영상이 있다

### [4]

- **then**: 제외 이전과 똑같이 흐르고, 제외를 켜고 끄는 것만으로는 산출물이 재생성되지 않으며 수정 통지도 나가지 않는다
- **when**: 배치 파이프라인 · 관제 통지 · 데이터마트 조회 뷰 · 학습데이터 산출물 · 관제 조회 창구 · 통계 · 포털 채널이 그 영상을 다룬다
- **given**: 제외된 영상이 있다

### [5]

- **then**: 상세가 열린다 — 인지하고 수용한 비대칭이며 결함이 아니다
- **when**: 검수자가 그 영상의 상세 주소로 직접 진입한다
- **given**: 제외된 영상이 있다

### [6]

- **then**: 어느 경로에서도 그 영상이 나타나지 않는다 — 제외 판정은 한 곳이 소유하고 모든 조회 경로가 그것을 공유한다
- **when**: 세 목록 중 어느 조회 경로로 조회하더라도
- **given**: 제외된 영상이 있다

## persists_in_tables

_(empty)_

## related_acceptances

_(empty)_

## implemented_by_endpoints

- API-042
- API-043
- API-260
- API-261

## implemented_by_module_apis

_(empty)_

## implemented_by_service_interfaces

_(empty)_
