---
logicraft_item: ROLE-001
type: permission_role
version: 15
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T13:21:59.015Z
status: CHANGED
prev_version: 14
content_hash: 4a54bf112c4fa0e8200752731e22efd417da6b52908627d65a1a787a04f6f427
stale: true
raw: ./_raw/ROLE-001.json
links:
  based_on: ["[[ADR-055]]"]
  granted_on: ["[[FEAT-001]]", "[[FEAT-002]]", "[[FEAT-003]]", "[[FEAT-004]]", "[[FEAT-005]]", "[[FEAT-006]]", "[[FEAT-008]]", "[[FEAT-009]]", "[[SCREEN-008]]", "[[SCREEN-012]]", "[[SCREEN-018]]", "[[SCREEN-019]]", "[[SCREEN-020]]", "[[SCREEN-021]]", "[[SCREEN-022]]", "[[SCREEN-023]]", "[[SCREEN-025]]", "[[SCREEN-026]]", "[[SCREEN-030]]", "[[SCREEN-031]]", "[[SCREEN-032]]", "[[SCREEN-035]]", "[[SCREEN-036]]", "[[SCREEN-037]]", "[[SCREEN-038]]"]
  inherits_from_backward: ["[[ROLE-004]]"]
  references_backward: ["[[ADR-067]]"]
  requires_backward: ["[[NAV-001]]", "[[SCREEN-005]]", "[[SCREEN-006]]", "[[SCREEN-008]]", "[[SCREEN-009]]", "[[SCREEN-010]]", "[[SCREEN-011]]", "[[SCREEN-012]]", "[[SCREEN-018]]", "[[SCREEN-019]]", "[[SCREEN-020]]", "[[SCREEN-021]]", "[[SCREEN-022]]", "[[SCREEN-023]]", "[[SCREEN-025]]", "[[SCREEN-026]]", "[[SCREEN-030]]", "[[SCREEN-031]]", "[[SCREEN-032]]", "[[SCREEN-035]]", "[[SCREEN-036]]", "[[SCREEN-037]]", "[[SCREEN-038]]"]
---

# 검수자 (REVIEWER)

## name

REVIEWER

## brownfield

### status

modified

### decided_by

ADR-055

### change_kind

- role-merge

### diff_summary

1차 ADMIN/담당자 → 2차 REVIEWER 통합(관리권한 포함)이었으나, 관리 권한을 관리자 역할로 다시 분리했다. 검수자 권한 자체는 관리자가 계층으로 물려받아 그대로 유지된다.

### legacy_source

#### type

role

#### identifier

ADMIN

## description

검수자. 화면 호칭은 '검수자'다. 관리 권한은 이 역할에 통합돼 있지 않고 관리자 역할이 소유한다 — 관리자는 이 역할을 계층으로 물려받으므로, 관리자가 검수 업무를 겸하되 검수자는 관리 기능에 닿지 않는다.

이 역할이 소유하는 관리 화면은 라벨 마스터 · 오토라벨 프리셋 · 이벤트유형 · 비식별 누락 신고 관리와 배치·추론·정밀도·비식별 설정이다. 사용자 관리 · 연동 서버 주소 · 파일 업로드 · 산출물 가져오기 · 위험 작업 · 관리자 패스워드 교체는 관리자 역할로 옮겨갔다. 그 화면들에 들어오는 것은 관리자뿐이라 조회조차 이 역할 권한만으로는 되지 않는다 — 이것은 화면 축의 서술이다.

창구 축은 화면 축과 갈린다. 사용자 목록·작업자 목록·사용자 단건 조회처럼 창구 자체는 이 역할 권한으로도 응답하는 자리가 있고, 역할 지정처럼 관리자 역할에 유효창까지 요구하는 자리가 있다. 이 역할이 소유하는 관리 창구는 이 역할을 하한으로 연다. 어느 축의 서술인지를 밝히지 않으면 두 축이 서로 어긋난 사양으로 읽힌다.

**관리** — 라벨 마스터 관리 · 오토라벨 프리셋 관리 · 비식별 누락 신고 관리 · 이벤트유형 관리 · 배치·추론·정밀도·비식별 설정. 사용자 관리는 이 역할에 두지 않는다(관리자 역할이 소유한다).

**작업 운영** — 작업자 배정 · 재배정 · 배정 이력 조회. 작업 목록과 통계를 배정 범위 제한 없이 전체 기준으로 조회한다(작업자는 본인 배정분으로 좁혀진다).

**검수** — 검수 목록·상세에서 승인·반려. 승인 시점에 학습데이터 버전이 확정되고 완료 통지가 발행된다.

**검수는 배정으로 정해지지 않는다** — 검수 목록은 검수 대기 전체를 이 역할을 가진 누구에게나 보여주고, 검수 시작·승인·반려의 자격은 역할이 정한다. 이 역할을 영상에 배정하는 절차를 두지 않으며 배정의 대상은 작업자뿐이다. 배정 여부를 검수 인가의 축으로 쓰지 않는다 — 배정되지 않은 영상도 이 역할이면 검수할 수 있고, 배정되었다는 사실이 남의 검수를 막지도 않는다. 이미 적재된 검수자 배정 기록은 판독을 위해 남아 있을 뿐 새로 만들지 않는다.

**점유는 잠금이 아니다** — 검수를 시작하면 그 사람이 그 영상을 잠시 점유하고 유예가 지나면 저절로 풀린다. 점유는 같은 영상을 둘이 끝까지 보는 헛수고를 줄이는 표시이지 남의 검수를 막는 잠금이 아니다. 실제 방어는 승인 시점의 동시성 보호가 그대로 맡는다 — 두 축은 서로를 대체하지 않으므로 점유가 생겼다는 이유로 승인 시점 보호를 걷어내지 않는다.

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

- **condition**: 시스템 설정
- **target_id**: SCREEN-025
- **target_kind**: screen_spec

### [10]

**actions**:

- view
- create
- update
- delete

- **condition**: 오토라벨 프리셋 관리
- **target_id**: SCREEN-026
- **target_kind**: screen_spec

### [11]

**actions**:

- view
- create
- update
- delete

- **condition**: 라벨 마스터 관리
- **target_id**: SCREEN-035
- **target_kind**: screen_spec

### [12]

**actions**:

- view
- update

- **condition**: 비식별 신고 관리 — 접수 확인·해소를 전체 영상 범위로 수행
- **target_id**: SCREEN-032
- **target_kind**: screen_spec

### [13]

**actions**:

- view

- **condition**: 검수 목록
- **target_id**: SCREEN-018
- **target_kind**: screen_spec

### [14]

**actions**:

- view
- update
- approve

- **condition**: 검수 상세 — 승인·반려
- **target_id**: SCREEN-019
- **target_kind**: screen_spec

### [15]

**actions**:

- view

- **condition**: 전체 구축 현황 통계
- **target_id**: SCREEN-021
- **target_kind**: screen_spec

### [16]

**actions**:

- view

- **condition**: 작업자 통계 — 전체 작업자 기준
- **target_id**: SCREEN-020
- **target_kind**: screen_spec

### [17]

**actions**:

- view
- create

- **condition**: 증강 요청
- **target_id**: SCREEN-022
- **target_kind**: screen_spec

### [18]

**actions**:

- view
- approve

- **condition**: 증강 결과 — 사용·폐기 결정
- **target_id**: SCREEN-023
- **target_kind**: screen_spec

### [19]

**actions**:

- view
- update

- **condition**: 작업 목록을 전체 기준으로 조회, 배정·재배정·배정 이력 조회
- **target_id**: SCREEN-012
- **target_kind**: screen_spec

### [20]

**actions**:

- view
- update

- **condition**: 영상 처리 현황 조회, 마킹 진입·작업자 배정·재배정
- **target_id**: SCREEN-008
- **target_kind**: screen_spec

### [21]

**actions**:

- view

- **condition**: 공지 목록 조회
- **target_id**: SCREEN-030
- **target_kind**: screen_spec

### [22]

**actions**:

- view
- update
- delete

- **condition**: 공지 상세 조회·발행·발행취소·삭제 — 발행 제어와 삭제는 이 역할만 할 수 있다
- **target_id**: SCREEN-031
- **target_kind**: screen_spec

### [23]

**actions**:

- view
- create

- **condition**: 공지 작성 — 이 역할만 작성할 수 있다
- **target_id**: SCREEN-036
- **target_kind**: screen_spec

### [24]

**actions**:

- view
- update
- delete

- **condition**: 공지 수정·삭제 — 이 역할만 수정할 수 있다
- **target_id**: SCREEN-037
- **target_kind**: screen_spec

### [25]

**actions**:

- view
- update

- **condition**: 이벤트유형 관리 — 표시명·수집여부 정정. 생성·삭제는 누구에게도 없다(등록은 관제 인입으로만 이뤄진다)
- **target_id**: SCREEN-038
- **target_kind**: screen_spec

## inherits_from

_(empty)_

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

## typical_actors

- 검수자
- 운영 관리자(겸임)
