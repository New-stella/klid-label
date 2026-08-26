---
logicraft_item: ROLE-003
type: permission_role
version: 8
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-26T04:28:08.173Z
status: CHANGED
prev_version: 7
content_hash: f7b96c5a16b1ed0afaf7d82133dc9e81f0f14c07782fa181c4c046be1b0f7912
stale: false
raw: ./_raw/ROLE-003.json
links:
  based_on: ["[[ADR-013]]"]
  granted_on: ["[[SCREEN-028]]", "[[SCREEN-029]]", "[[SCREEN-033]]", "[[SCREEN-034]]"]
  implements: ["[[IMPREC-103]]"]
  requires_backward: ["[[NAV-002]]", "[[SCREEN-028]]", "[[SCREEN-029]]", "[[SCREEN-033]]", "[[SCREEN-034]]"]
---

# 포털 회원 (PORTAL_USER)

## name

PORTAL_USER

## brownfield

### status

preserved

### decided_by

ADR-013

### change_kind

- preserved
- permission-change

### diff_summary

1차 포털 회원 역할 보존(간편 라벨링·다운로드). 2차에서 데이터마트 로드분의 메타·이벤트 어노테이션 편집과 업로드 영상 한정 이벤트구간 마킹·AI 증강 연동 요청이 더해졌다

### legacy_source

#### type

role

#### identifier

PORTAL_USER

## description

포털 회원(외부 채널). 접근 범위는 본인 작업 데이터로 한정된다.

**데이터마트 경로** — 데이터마트에 등재된 영상을 골라 기존에 저장된 라벨·메타를 불러와 확인·수정·저장한다. 저장은 원본과 데이터마트를 수정하지 않는 단방향이며, 사용자별 작업 데이터로 별도 적재된다.

**본인 자산 업로드 경로** — 본인 이미지(jpg·jpeg·png, 1장 20MB, 1회 50장)와 영상(mp4·mov·avi, 5GB, 재개 가능 업로드)을 직접 올려 수동 라벨링(사각형·다각형)한 뒤 본인 데이터를 내려받는다(작업 결과 JSON·원본). 이 경로는 내부 파이프라인(비식별 → 마킹 → 배치 → 검수)과 완전히 분리돼 있어 내부 적재 흐름과 데이터마트에 섞이지 않는다. 본인이 올린 영상에 한해 이벤트구간 마킹과 AI 증강 연동 요청도 수행한다. 이 증강 연동은 서버가 서버로 보내는 비동기 위탁이라, 사용자가 외부 채널에서 직접 호출하는 추론 엔드포인트와 성질이 다른 축이다.

**메타·이벤트 어노테이션 편집** — 데이터마트에서 불러온 영상의 메타(촬영환경 · 프레임 설명 · 개인정보 판정 · 시계열 메타)와 이벤트 어노테이션을 화면에서 확인하고 직접 수정·추가한다. 결과는 라벨 저장과 같은 단방향이라 포털 전용 저장소에만 적재되고 데이터마트와 원본 동결본을 수정하지 않는다.

**다운로드** — 본인 작업 데이터를 기간 내에 내려받는다.

**이 역할에 두지 않는 것** — 오토라벨링 · 분할 보조(인터랙티브 분할 · 자동추적 · 키포인트) · 외부 시계열 분석 서버로 나가는 위탁 연동(호출 · 콜백) · 검수 · 버전관리 · 기여도 점수. 두지 않는 것은 위탁 연동이지 그 결과물인 시계열 메타가 아니다. 분할 보조를 두지 않는 것은 외부 채널이 추론 엔드포인트를 직접 호출하지 않게 하려는 보안 판단이라 약화하지 않는다.

진입 경로: 저작도구는 자체 로그인 화면을 갖지 않고 상위 시스템이 발급한 토큰을 인계받으며, 토큰의 역할 클레임과 채널 클레임으로 접근을 분기한다. 포털 회원은 외부 채널로 진입해 내부 채널(검수자·작업자)과 진입 경로가 다르다. 시스템 관리자(ADMIN) 역할은 두지 않으며 관리 권한은 검수자에 통합돼 있어, 이 역할은 관리 화면(/manage/*)에 접근하지 않는다. 다만 이 제한은 화면 축이며, 라벨링 화면이 라벨 분류와 표시명·색상을 그리기 위해 읽는 라벨 마스터 조회는 관리 화면이 아니라 라벨링이 의존하는 공용 읽기 계약이라 이 역할에도 허용된다. 노출되는 값은 라벨 분류 정의(라벨명·형태·색상·검출 클래스 매핑)이고 개인정보가 아니다. 라벨 마스터의 등록·수정·삭제는 검수자 전용이다.

선행조건(권한과 구분) — 데이터마트 경로에서 불러오는 내부 파이프라인 영상은 비식별 누락 신고가 열려 있는 동안 차단되며, 이 차단은 권한이 아니라 선행조건이라 역할과 무관하게 적용된다. 본인이 올린 업로드 자산은 내부 파이프라인 밖에 있어 이 신고 체계의 대상이 아니다.

## permissions

### [1]

**actions**:

- view

- **condition**: 포털 홈 — 데이터마트 영상 목록과 본인 업로드 진입
- **target_id**: SCREEN-028
- **target_kind**: screen_spec

### [2]

**actions**:

- view
- update

- **condition**: 포털 라벨링 — 데이터마트 영상의 기존 라벨과 메타(촬영환경 · 프레임 설명 · 개인정보 판정 · 시계열 메타) · 이벤트 어노테이션을 확인·수정·추가하고 저장한다. 저장은 단방향이라 원본과 데이터마트를 수정하지 않는다
- **target_id**: SCREEN-029
- **target_kind**: screen_spec

### [3]

**actions**:

- view
- create
- delete

- **condition**: 포털 업로드 — 본인 자산만. 내부 파이프라인과 분리된 경로이며 본인 데이터 다운로드를 포함한다
- **target_id**: SCREEN-033
- **target_kind**: screen_spec

### [4]

**actions**:

- view
- create
- update
- delete

- **condition**: 포털 업로드 라벨링 — 본인 업로드 자산만, 수동 라벨링(사각형·다각형)으로 한정
- **target_id**: SCREEN-034
- **target_kind**: screen_spec

## inherits_from

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-103

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-25T01:21:32.459Z

### module_paths

_(empty)_

## typical_actors

- 포털 일반 사용자
