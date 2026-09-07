---
logicraft_item: CDIAG-017
type: class_diagram
version: 2
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-07T13:24:03.748Z
status: CHANGED
prev_version: 1
content_hash: 035af2b28493b1ff8fe047ee2f7b16dfbb45f3ed8615560401efc6e27e1895d5
stale: false
raw: ./_raw/CDIAG-017.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
---

# 영상·프레임 수집 표현 계층 — 컨트롤러 오퍼레이션

## theme

neutral

## title

영상·프레임 수집 표현 계층 — 컨트롤러 오퍼레이션

## classes

### VideoController

- **kind**: service

**methods**:

#### list

**params**:

- 페이지 정보
- 배치 상태
- 검수 상태
- 카메라명 검색어
- 이벤트 유형
- 조회 시작일
- 조회 종료일
- 건너뛴 단계
- 실패한 단계
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 조건에 맞는 영상 목록을 쪽 단위로 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### getOne

**params**:

- 영상 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 영상 한 건의 상세와 그 영상의 검증 이벤트 유형에 등록된 질문 목록을 함께 조회한다. 관제가 검증 이벤트 유형을 보내지 않은 영상에서는 작업자가 고를 수 있는 유형 목록도 함께 싣는다 — 관제 값이 있는 영상에서는 그 목록이 비어 있다.
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### getAutoLabels

**params**:

- 영상 식별번호
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 그 영상의 오토라벨링 결과 요약을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### streamVideo

**params**:

- 영상 식별번호
- 요청 헤더
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 비식별 영상을 구간 단위로 내려보낸다 — 비식별본이 없으면 원본을 주지 않는다
- **is_abstract**: false
- **return_type**: 스트리밍 응답

#### getFrameImage

**params**:

- 영상 식별번호
- 프레임 번호
- 원본 요청 여부
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 프레임 이미지를 내려보낸다 — 기본은 비식별본이고 검수자가 원본을 명시할 때만 원본을 준다
- **is_abstract**: false
- **return_type**: 파일 응답

#### changeResolution

**params**:

- 영상 식별번호
- 해상도 변경 요청
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 표준 해상도 프리셋마다 파생영상을 만들고 라벨 좌표를 배율로 다시 계산한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

#### listDerivatives

**params**:

- 영상 식별번호

- **is_static**: false
- **visibility**: public
- **description**: 그 영상에서 만들어진 해상도 파생영상 목록을 조회한다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 영상 조회·스트리밍·해상도 파생 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### DevAutolabelTestController

- **kind**: service

**methods**:

#### upload

**params**:

- 영상 파일
- 인입 메타데이터

- **is_static**: false
- **visibility**: public
- **description**: 관리자가 영상 한 건을 직접 올려 비식별을 선두로 파이프라인에 태운다
- **is_abstract**: false
- **return_type**: 응답 래퍼

**attributes**:

_(empty)_

- **description**: 영상 한 건 직접 투입 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### TusUploadController

- **kind**: service

**methods**:

#### create

**params**:

- 재개 프로토콜 판본
- 전체 길이
- 인입 메타데이터
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 재개 가능한 전송 세션을 만들고 인입 대기 행을 함께 남긴다
- **is_abstract**: false
- **return_type**: 전송 응답

#### patch

**params**:

- 재개 프로토콜 판본
- 이어 붙일 위치
- 조각 길이
- 전송 식별자
- 요청
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 영상 조각을 이어 붙인다 — 끊긴 지점부터 다시 보낼 수 있다
- **is_abstract**: false
- **return_type**: 전송 응답

#### delete

**params**:

- 재개 프로토콜 판본
- 전송 식별자
- 사용자 정보

- **is_static**: false
- **visibility**: public
- **description**: 진행 중인 전송을 취소한다
- **is_abstract**: false
- **return_type**: 전송 응답

**attributes**:

_(empty)_

- **description**: 재개 가능 업로드 창구

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

## brownfield

### status

new

### change_kind

- component-add

### diff_summary

2차 신규 — 컨트롤러 오퍼레이션을 설계 산출물에 처음으로 적는다.

## description

이 도메인의 컨트롤러가 밖으로 여는 오퍼레이션을 적는다. 시퀀스에서 컨트롤러가 받는 메시지는 주소 경로라 메서드 이름과 파라미터가 드러나지 않으므로, 그 자리를 채우기 위해 구현 층에서 옮겨 적은 것이다.

이름과 파라미터와 반환은 구현에서 실측했고 설명은 그 오퍼레이션이 무엇을 하는지 한 문장으로 적었다. 반환은 표를 읽기 쉽도록 추상 이름으로 적는다 — 성공 응답을 감싸는 공통 껍데기는 응답 래퍼, 파일·영상 전송은 각각 파일 응답·스트리밍 응답, 돌려줄 것이 없으면 없음이다.

주입 의존은 여기 적지 않는다 — 시퀀스에서 이미 드러난다. 이 다이어그램은 도메인 객체 모델이 아니라 표현 계층 목록이므로 같은 도메인의 도메인 모델 다이어그램과 축이 다르다.

## module_name

영상·프레임 수집 Controller

## relationships

_(empty)_

## attached_files

_(empty)_

## depicts_dfeats

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

## referenced_items

_(empty)_

## realizes_features

_(empty)_
