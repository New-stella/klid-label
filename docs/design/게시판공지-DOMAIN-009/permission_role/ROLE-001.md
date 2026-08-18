---
logicraft_item: ROLE-001
type: permission_role
version: 10
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:53.429Z
status: NEW
prev_version: null
content_hash: d50ab58d55e8796b352ecb87f593d0baad9ff8f967261c5bb7d995585c6da918
stale: true
raw: ./_raw/ROLE-001.json
links:
  granted_on: ["[[FEAT-001]]", "[[FEAT-002]]", "[[FEAT-003]]", "[[FEAT-004]]", "[[FEAT-005]]", "[[FEAT-006]]", "[[FEAT-008]]", "[[FEAT-009]]", "[[SCREEN-008]]", "[[SCREEN-012]]", "[[SCREEN-018]]", "[[SCREEN-019]]", "[[SCREEN-020]]", "[[SCREEN-021]]", "[[SCREEN-022]]", "[[SCREEN-023]]", "[[SCREEN-024]]", "[[SCREEN-025]]", "[[SCREEN-026]]", "[[SCREEN-030]]", "[[SCREEN-031]]", "[[SCREEN-032]]", "[[SCREEN-035]]", "[[SCREEN-036]]", "[[SCREEN-037]]", "[[SCREEN-038]]"]
  requires_backward: ["[[NAV-001]]", "[[SCREEN-005]]", "[[SCREEN-006]]", "[[SCREEN-008]]", "[[SCREEN-009]]", "[[SCREEN-010]]", "[[SCREEN-011]]", "[[SCREEN-012]]", "[[SCREEN-018]]", "[[SCREEN-019]]", "[[SCREEN-020]]", "[[SCREEN-021]]", "[[SCREEN-022]]", "[[SCREEN-023]]", "[[SCREEN-024]]", "[[SCREEN-025]]", "[[SCREEN-026]]", "[[SCREEN-027]]", "[[SCREEN-030]]", "[[SCREEN-031]]", "[[SCREEN-032]]", "[[SCREEN-035]]", "[[SCREEN-036]]", "[[SCREEN-037]]", "[[SCREEN-038]]"]
---

# 검수자 (REVIEWER)

## name

REVIEWER

## brownfield

### status

modified

### decided_by

ADR-003

### change_kind

- role-merge

### diff_summary

1차 ADMIN/담당자 → 2차 REVIEWER 통합(관리권한 포함)

### legacy_source

#### type

role

#### identifier

ADMIN

## description

검수자. 시스템 관리자(ADMIN) 역할을 별도로 두지 않으며 모든 관리 권한이 이 역할에 통합돼 있다. 화면 호칭은 '검수자'이고 관리 화면은 /manage/* 경로에 모인다.

**관리** — 사용자 관리 · 시스템 설정 · 라벨 마스터 관리 · 오토라벨 프리셋 관리 · 비식별 누락 신고 관리 · 이벤트유형 관리.

**작업 운영** — 작업자 배정 · 재배정 · 배정 이력 조회. 작업 목록과 통계를 배정 범위 제한 없이 전체 기준으로 조회한다(작업자는 본인 배정분으로 좁혀진다).

**검수** — 검수 목록·상세에서 승인·반려. 승인 시점에 학습데이터 버전이 확정되고 완료 통지가 발행된다.

**파생** — 증강 요청, 그리고 증강 결과의 사용·폐기 결정.

**버전** — 버전 간 비교와 복구.

**비식별 누락 신고** — 접수 확인과 해소를 전체 영상 범위로 수행한다(작업자는 본인 배정분에 한한다).

진입 경로: 저작도구는 자체 로그인 화면을 갖지 않고 상위 시스템이 발급한 토큰을 인계받으며, 토큰의 역할 클레임과 채널 클레임으로 접근을 분기한다. 검수자는 내부 채널로 진입해 외부 채널(포털 회원)과 진입 경로가 다르다.

선행조건(권한과 구분) — 비식별 누락 신고가 열려 있는 구간의 차단은 권한이 아니라 선행조건이라 역할과 무관하게 적용된다. 검수자여도 그 영상의 라벨 조회·저장, 프레임 이미지, 영상 재생은 거부되며, 신고가 해소돼야 풀린다.

## permissions

### [1]

**actions**:

- view
- update
- approve

- **condition**: 검수 승인·반려. 승인 시점에 학습데이터 버전이 확정된다
- **target_id**: FEAT-008
- **target_kind**: feature

### [2]

**actions**:

- view
- create
- update

- **condition**: 전체 영상의 버전 비교·복구
- **target_id**: FEAT-002
- **target_kind**: feature

### [3]

**actions**:

- view
- create
- update
- approve

- **condition**: 증강 요청, 증강 결과의 사용·폐기 결정
- **target_id**: FEAT-004
- **target_kind**: feature

### [4]

**actions**:

- view
- update

- **condition**: 비식별 옵션 설정, 비식별 누락 신고 해소(전체 영상 범위)
- **target_id**: FEAT-005
- **target_kind**: feature

### [5]

**actions**:

- view

- **condition**: 비식별 처리 상태·이력 확인(전체 영상)
- **target_id**: FEAT-006
- **target_kind**: feature

### [6]

**actions**:

- view
- execute

- **condition**: 검수 완료 시 데이터마트 통지 발행
- **target_id**: FEAT-003
- **target_kind**: feature

### [7]

**actions**:

- view
- update

- **condition**: 시계열 메타 검토·수정
- **target_id**: FEAT-009
- **target_kind**: feature

### [8]

**actions**:

- view
- update

- **condition**: 배정 범위 제한 없이 전체 영상
- **target_id**: FEAT-001
- **target_kind**: feature

### [9]

**actions**:

- view
- update

- **condition**: 사용자 관리 — 목록 조회·검색·역할 수정. 사용자 생성은 두지 않는다(사용자 마스터는 역할 클레임 시 자동등록된다). 계정 활성 여부는 외부 시스템 소유라 읽기 전용이다
- **target_id**: SCREEN-024
- **target_kind**: screen_spec

### [10]

**actions**:

- view
- update

- **condition**: 시스템 설정
- **target_id**: SCREEN-025
- **target_kind**: screen_spec

### [11]

**actions**:

- view
- create
- update
- delete

- **condition**: 오토라벨 프리셋 관리
- **target_id**: SCREEN-026
- **target_kind**: screen_spec

### [12]

**actions**:

- view
- create
- update
- delete

- **condition**: 라벨 마스터 관리
- **target_id**: SCREEN-035
- **target_kind**: screen_spec

### [13]

**actions**:

- view
- update

- **condition**: 비식별 신고 관리 — 접수 확인·해소를 전체 영상 범위로 수행
- **target_id**: SCREEN-032
- **target_kind**: screen_spec

### [14]

**actions**:

- view

- **condition**: 검수 목록
- **target_id**: SCREEN-018
- **target_kind**: screen_spec

### [15]

**actions**:

- view
- update
- approve

- **condition**: 검수 상세 — 승인·반려
- **target_id**: SCREEN-019
- **target_kind**: screen_spec

### [16]

**actions**:

- view

- **condition**: 전체 구축 현황 통계
- **target_id**: SCREEN-021
- **target_kind**: screen_spec

### [17]

**actions**:

- view

- **condition**: 작업자 통계 — 전체 작업자 기준
- **target_id**: SCREEN-020
- **target_kind**: screen_spec

### [18]

**actions**:

- view
- create

- **condition**: 증강 요청
- **target_id**: SCREEN-022
- **target_kind**: screen_spec

### [19]

**actions**:

- view
- approve

- **condition**: 증강 결과 — 사용·폐기 결정
- **target_id**: SCREEN-023
- **target_kind**: screen_spec

### [20]

**actions**:

- view
- update

- **condition**: 작업 목록을 전체 기준으로 조회, 배정·재배정·배정 이력 조회
- **target_id**: SCREEN-012
- **target_kind**: screen_spec

### [21]

**actions**:

- view
- update

- **condition**: 영상 처리 현황을 전체 기준으로 조회, 마킹 진입·작업자 배정·재배정
- **target_id**: SCREEN-008
- **target_kind**: screen_spec

### [22]

**actions**:

- view

- **condition**: 공지 목록 조회
- **target_id**: SCREEN-030
- **target_kind**: screen_spec

### [23]

**actions**:

- view
- update
- delete

- **condition**: 공지 상세 조회·발행·발행취소·삭제 — 발행 제어와 삭제는 이 역할만 할 수 있다
- **target_id**: SCREEN-031
- **target_kind**: screen_spec

### [24]

**actions**:

- view
- create

- **condition**: 공지 작성 — 이 역할만 작성할 수 있다
- **target_id**: SCREEN-036
- **target_kind**: screen_spec

### [25]

**actions**:

- view
- update
- delete

- **condition**: 공지 수정·삭제 — 이 역할만 수정할 수 있다
- **target_id**: SCREEN-037
- **target_kind**: screen_spec

### [26]

**actions**:

- view
- update

- **condition**: 이벤트유형 관리 — 표시명·수집여부 정정. 생성·삭제는 누구에게도 없다(등록은 관제 인입으로만 이뤄진다)
- **target_id**: SCREEN-038
- **target_kind**: screen_spec

## inherits_from

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

## typical_actors

- 검수자
- 운영 관리자(겸임)
