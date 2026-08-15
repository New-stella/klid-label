---
logicraft_item: ROLE-002
type: permission_role
version: 5
status: NEW
prev_version: null
raw: ./_raw/ROLE-002.json
---

# 라벨링 작업자 (WORKER)

## name

WORKER

## brownfield

### status

preserved

### change_kind

- preserved

### diff_summary

1차 라벨링 작업자 역할 보존

### legacy_source

#### type

role

#### identifier

WORKER

## description

라벨링 작업자. 본인에게 배정된 영상에 대해서만 라벨을 만들고 고치며 검수를 제출한다.

**행 단위 인가** — 편집 대상은 본인 배정 영상으로 한정되고, 본인 배정이 아닌 프레임의 편집 요청은 거부된다. 작업 목록과 통계도 본인 배정분 기준으로 좁혀 보인다(검수자는 배정 범위 제한 없이 전체를 본다). 비식별 누락 신고의 해소도 본인 배정분에 한한다(검수자는 전체 영상 범위).

**라벨링 보조** — 객체 추적(박스를 시작점으로 여러 프레임에 전파)과 분할(클릭 또는 박스 지시로 다각형 획득)을 사용한다. 자동 제안 결과는 사람이 확인·수정한 뒤 확정된다.

**작업 흐름** — 라벨 저장 → 검수 제출 → 검수 착수 전이면 본인 제출 취소. 라벨 저장은 작업 임시저장이라 학습데이터 버전을 만들지 않는다(버전 확정은 검수 승인 시점이며 검수자 몫이다). 본인 배정 영상의 라벨 이력과 버전은 조회한다.

**비식별 누락 신고** — 마킹 또는 라벨링 중 개인정보 노출을 발견하면 신고한다.

진입 경로: 저작도구는 자체 로그인 화면을 갖지 않고 상위 시스템이 발급한 토큰을 인계받으며, 토큰의 역할 클레임과 채널 클레임으로 접근을 분기한다. 작업자는 내부 채널로 진입해 외부 채널(포털 회원)과 진입 경로가 다르다. 시스템 관리자(ADMIN) 역할은 두지 않으며 관리 권한은 검수자에 통합돼 있어, 이 역할은 관리 화면(/manage/*)에 접근하지 않는다.

선행조건(권한과 구분) — 비식별 누락 신고가 열려 있는 구간의 차단은 권한이 아니라 선행조건이라 역할과 무관하게 적용된다. 본인 배정 영상이어도 신고가 열려 있는 동안에는 라벨 조회·저장, 프레임 이미지, 영상 재생이 거부되며, 신고가 해소돼야 풀린다.

## permissions

### [1]

**actions**:

- view
- update
- execute

- **condition**: 본인 배정 영상만 — 추적·분할 보조 사용, 결과는 사람이 확인·수정 후 확정
- **target_id**: FEAT-001
- **target_kind**: feature

### [2]

**actions**:

- view
- execute

- **condition**: 본인 배정 영상의 검수 제출, 검수 착수 전이면 본인 제출 취소. 승인·반려 권한은 두지 않는다
- **target_id**: FEAT-008
- **target_kind**: feature

### [3]

**actions**:

- view

- **condition**: 본인 배정 영상의 라벨 이력·버전 조회. 버전 확정과 복구는 두지 않는다
- **target_id**: FEAT-002
- **target_kind**: feature

### [4]

**actions**:

- view
- create

- **condition**: 비식별 누락 신고 접수, 해소는 본인 배정분에 한한다
- **target_id**: FEAT-005
- **target_kind**: feature

### [5]

**actions**:

- view

- **condition**: 본인 배정 영상의 비식별 처리 상태·이력 확인
- **target_id**: FEAT-006
- **target_kind**: feature

### [6]

**actions**:

- view
- create
- update
- delete

- **condition**: 라벨링 캔버스 — 본인 배정 영상만
- **target_id**: SCREEN-005
- **target_kind**: screen_spec

### [7]

**actions**:

- view
- update

- **condition**: 마킹 화면 — 본인 배정 영상만. 비식별 완료 영상을 대상으로 마킹하며 비식별 누락 신고도 여기서 접수
- **target_id**: SCREEN-006
- **target_kind**: screen_spec

### [8]

**actions**:

- view

- **condition**: 작업 목록 — 본인 배정분으로 좁혀진다
- **target_id**: SCREEN-012
- **target_kind**: screen_spec

### [9]

**actions**:

- view

- **condition**: 라벨 이력 — 본인 배정 영상
- **target_id**: SCREEN-010
- **target_kind**: screen_spec

### [10]

**actions**:

- view

- **condition**: 영상 상세 — 본인 배정 영상
- **target_id**: SCREEN-009
- **target_kind**: screen_spec

### [11]

**actions**:

- view

- **condition**: 대시보드 — 본인 배정분 기준
- **target_id**: SCREEN-011
- **target_kind**: screen_spec

### [12]

**actions**:

- view

- **condition**: 작업자 통계 — 본인 기준으로 좁혀진다
- **target_id**: SCREEN-020
- **target_kind**: screen_spec

### [13]

**actions**:

- view

- **condition**: 영상 처리 현황 — 조회만 가능하며 마킹 진입·배정 동선은 없다
- **target_id**: SCREEN-008
- **target_kind**: screen_spec

### [14]

**actions**:

- view

- **condition**: 공지 목록 조회
- **target_id**: SCREEN-030
- **target_kind**: screen_spec

### [15]

**actions**:

- view

- **condition**: 공지 상세 조회 — 읽기만 가능하며 작성·수정은 검수자 몫이다
- **target_id**: SCREEN-031
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

- 라벨링 작업자
