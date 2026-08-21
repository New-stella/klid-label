---
logicraft_item: SCREEN-035
type: screen_spec
version: 19
last_updated_at: 2026-08-18T03:19:13.162Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T09:19:05.325Z
sync_session: 20
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-035.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-024]]", "[[API-025]]", "[[API-026]]", "[[API-027]]", "[[API-028]]", "[[API-029]]", "[[API-030]]", "[[API-031]]"]
  required_roles: ["[[ROLE-001]]"]
  realizes_use_cases: ["[[UC-028]]"]
---

# 라벨 관리 화면

## route

/manage/labels

## title

라벨 관리 화면

## device

desktop

## status

draft

## purpose

REVIEWER가 라벨 마스터(라벨 클래스 정의)와 클래스별 속성 정의를 CRUD하는 관리 화면. 라벨 프리셋은 이 마스터를 실시간으로 참조하는 구조이므로, 여기서 라벨명·형태·색상·정렬순·AI 탐지 클래스 매핑을 바꾸면 신규·기존 프리셋 모두에 즉시 반영된다. 라벨 행의 '속성' 버튼을 누르면 해당 라벨의 속성 정의 패널이 열려 속성 CRUD를 이어간다. 요청 본문은 허용 필드만 갖는 구조로 전달되며(역할·권한과 같은 민감 필드는 포함되지 않는다), 삭제는 확인 모달 승인 후에만 실행되고 확인 문구는 실제로 일어나는 일만 안내한다 — 라벨이 목록과 선택 항목에서 빠지고 그 라벨을 쓰던 프리셋에는 미연결로 표시되며, 이미 저장된 라벨 데이터와 속성 정의는 지워지지 않고 표시 색상도 그대로 유지된다. 삭제한 라벨은 이 화면에서 되살릴 수 없다. 접근: REVIEWER 전용.

## sections

### 페이지 헤더 + 라벨 추가

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 라벨 관리

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 라벨 클래스(마스터)의 이름·형태·색상·정렬 순서를 관리합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: Button
- **label**: 라벨 추가

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-024

- **description**: 제목 '라벨 관리' + 고정 부제(동적 개수 미포함). 우측 '라벨 추가' 버튼 → 신규 모드 모달(다음 정렬순 자동 채움).

**references_apis**:

- API-024

**references_features**:

_(empty)_

### 라벨 마스터 목록

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Text
- **label**: 전체 N개

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 라벨명 셀에 AI 탐지 클래스 매핑 상태를 칩으로 함께 표시한다 — 매핑된 검출 클래스명 또는 미매핑 표시. 매핑이 없는 라벨은 AI 탐지 후보로 선택할 수 없으므로, 목록에서 그 상태가 보이지 않으면 운영자가 선택 불가 사유를 알 수 없다.
- **type**: Table
- **label**: 라벨 마스터 목록

**columns**:

- 라벨명
- 형태
- 색상
- 정렬 순서
- 관리

**options**:

_(empty)_

- **triggers_api**: API-024

#### [3]

- **note**: 행마다 독립적으로 열고 닫히는 측면 패널(시트) 트리거 — 다른 행을 선택해도 관련된 테이블 행이 강조되거나 하단에 패널이 열리는 연동은 없다. 재클릭 시 패널 닫힘.
- **type**: IconButton
- **label**: 속성

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 신규 모드 모달과 동일 폼, 선택된 라벨 값으로 초기화
- **type**: IconButton
- **label**: 수정

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 확인 모달 오픈
- **type**: IconButton
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: 라벨 마스터 목록을 정렬 순서 오름차순(동률은 등록 순서)으로 표시하며, 바로 위에 '전체 N개' 텍스트가 놓인다. 컬럼: 라벨명/형태(배지)/색상(스와치+코드 텍스트)/정렬 순서/관리(속성·수정·삭제). 로딩 스켈레톤, 0건이면 빈 상태 안내+'새 라벨 만들기' 버튼, 에러 시 ErrorState. 행 선택이나 강조 표시는 없다 — 각 행의 '속성' 버튼이 독립적으로 자기 속성 패널을 여는 방식이다.

**references_apis**:

- API-024

**references_features**:

_(empty)_

### 라벨 추가/수정 모달

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Input
- **label**: 라벨명

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수. 화면 입력 제한은 50자이나, 서버 저장 허용 한도는 64자까지다(화면과 서버의 허용 길이가 서로 다르다).

#### [2]

- **type**: Select
- **label**: 형태 (바운딩 박스/폴리곤/포인트/스켈레톤)

**columns**:

_(empty)_

**options**:

- 바운딩 박스
- 폴리곤
- 포인트
- 스켈레톤

- **validation**: 필수. 4종 중 하나.

#### [3]

- **note**: AI 탐지 결과를 이 라벨로 자동 연결한다. 하나의 탐지 클래스는 하나의 라벨에만 매핑된다.
- **type**: Select
- **label**: AI 탐지 클래스 (선택)

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 선택. 지정 시 사전 정의된 탐지 클래스 목록(80종) 중 하나여야 하며 그 밖의 자유 입력값은 허용되지 않는다(20자 이하).

#### [4]

- **type**: Input
- **label**: 색상

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수. #RRGGBB 형식(6자리 16진수). 화면은 대소문자를 모두 허용하고 입력 즉시 대문자로 정규화해 전송하지만, 서버는 대문자 형식만 허용하고 소문자 형식은 거부한다.

#### [5]

- **type**: Input
- **label**: 정렬 순서

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 0 이상의 정수.

#### [6]

- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-026

#### [7]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 신규/수정 공용 모달. 라벨명/형태/AI 탐지 클래스 매핑(선택)/색상/정렬 순서 필드로 구성된다. 화면 사전검증 + 서버가 독립적으로 재검증하는 이중 검증이며, 예외적으로 라벨명·색상은 화면과 서버의 허용 범위가 서로 다르다(각 필드 검증규칙 참조). 신규는 POST, 수정은 PUT. 제출 실패 시 서버 메시지를 폼 상단에 노출한다.

**references_apis**:

- API-025
- API-026

**references_features**:

_(empty)_

### 삭제 확인 모달

- **role**: modal
- **layout**: stack

**components**:

#### [1]

- **type**: Dialog
- **label**: 라벨 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [2]

- **type**: Button
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive
- **triggers_api**: API-027

#### [3]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 대상 라벨명을 문구에 담아 되묻는 확인 다이얼로그. 확인하면 라벨 마스터를 비활성 처리해 목록과 라벨 선택 대상에서 제외하며, 이 화면에는 되돌리는 동선을 두지 않는다. 이미 저장된 라벨 데이터와 그 라벨의 속성 정의는 함께 지우지 않는다. 이 라벨을 참조하던 프리셋 항목도 지우지 않고 미연결로 표시한다.

**references_apis**:

- API-027

**references_features**:

_(empty)_

### 속성 정의 사이드 시트

- **role**: side
- **layout**: list

**components**:

#### [1]

- **type**: List
- **label**: 속성 정의 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-028

#### [2]

- **type**: Button
- **label**: 속성 추가

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-029

#### [3]

- **type**: IconButton
- **label**: 속성 수정

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-030

#### [4]

- **type**: IconButton
- **label**: 속성 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive
- **triggers_api**: API-031

#### [5]

- **note**: 추가/수정 모달 내 필드
- **type**: Input
- **label**: 속성명

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 필수. 최대 64자.

#### [6]

- **note**: 추가/수정 모달 내 필드. 이 값이 선택/체크박스/라디오일 때에만 '선택 항목' 리스트가 함께 노출된다.
- **type**: Select
- **label**: 입력 형식

**columns**:

_(empty)_

**options**:

- 선택(드롭다운)
- 체크박스(다중)
- 라디오(단일)
- 숫자
- 텍스트

- **validation**: 필수. 5종 중 하나.

#### [7]

- **note**: 추가/수정 모달 내 필드. 입력 형식이 선택/체크박스/라디오일 때만 노출되며, 항목을 자유롭게 추가·삭제할 수 있다.
- **type**: Custom
- **label**: 선택 항목 (조건부 노출)

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 선택형 입력 형식에서는 필수 — 공백이 아닌 선택 항목이 최소 1개 이상 있어야 저장이 허용된다.
- **custom_name**: DynamicList

#### [8]

- **note**: 추가/수정 모달 내 필드. 비워두면 기본값 없이 시작한다.
- **type**: Input
- **label**: 기본값

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 선택. 최대 255자.

#### [9]

- **note**: 추가/수정 모달 내 필드
- **type**: Select
- **label**: 작업 중 값 수정 가능 여부

**columns**:

_(empty)_

**options**:

- 가능
- 고정

- **validation**: 둘 중 하나.

#### [10]

- **note**: 추가/수정 모달 내 필드
- **type**: Input
- **label**: 정렬 순서

**columns**:

_(empty)_

**options**:

_(empty)_

- **validation**: 0 이상의 정수.

- **description**: 테이블 행의 '속성' 버튼을 누르면 우측에서 슬라이드로 열리는 사이드 패널 — 테이블에서 행을 선택하는 방식이 아니라 행마다 자기 트리거로 개별 열린다. 해당 labelId의 속성 목록 조회(정렬 순서 오름차순) + 추가/수정/삭제로 구성된다. 추가/수정은 동일 폼을 공유하며 다음 6개 필드를 갖는다: ①속성명(필수) ②입력 형식(선택(드롭다운)/체크박스(다중)/라디오(단일)/숫자/텍스트 5종 중 하나) ③선택 항목(입력 형식이 선택/체크박스/라디오일 때만 노출되는 동적 리스트, 최소 1개 이상 필요) ④기본값(선택) ⑤작업 중 값 수정 가능 여부(가능/고정) ⑥정렬 순서. 선택된 라벨이 삭제되면 패널이 자동으로 닫힌다(최신 목록 기준 재조회).

**references_apis**:

- API-028
- API-029
- API-030
- API-031

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-034

### diff_summary

신규 화면 — 라벨 클래스(라벨 마스터) 관리와 클래스별 속성 정의 관리. 라벨 프리셋이 라벨 마스터를 단일 진실원으로 실시간 참조하는 구조의 관리 UI.

## surface_kind

web

## consumes_apis

- API-024
- API-025
- API-026
- API-027
- API-028
- API-029
- API-030
- API-031

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

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-035/main.html
- **label**: 라벨 관리 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: 0a0f5af870ae87b76355802b74cd69877e6997bb41f0189ae0be7310799629b2
- **generated_at**: 2026-08-18T03:19:13.162Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-028

## covered_by_acceptances

_(empty)_
