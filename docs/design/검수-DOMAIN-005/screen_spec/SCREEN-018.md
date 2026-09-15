---
logicraft_item: SCREEN-018
type: screen_spec
version: 33
domain: DOMAIN-005
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-15T13:21:55.828Z
status: CHANGED
prev_version: 29
content_hash: f95cc73616226d190581ff229f7ae215e59a461b986adf2bef640f0a12048de5
stale: true
raw: ./_raw/SCREEN-018.json
links:
  based_on: ["[[ADR-002]]"]
  belongs_to_domain: ["[[DOMAIN-005]]"]
  consumes: ["[[API-008]]", "[[API-138]]", "[[API-250]]"]
  covered_by: ["[[AC-1113]]", "[[AC-1114]]", "[[AC-1116]]", "[[AC-1117]]"]
  implements: ["[[IMPREC-334]]"]
  realizes: ["[[UC-023]]"]
  references: ["[[API-008]]", "[[API-138]]", "[[API-250]]"]
  requires: ["[[ROLE-001]]"]
  applies_to_backward: ["[[SHELL-001]]"]
  designs_backward: ["[[SD-001]]"]
  granted_on_backward: ["[[ROLE-001]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  realizes_backward: ["[[MOD-009]]"]
  references_backward: ["[[TEST-006]]", "[[TEST-007]]", "[[UC-023]]"]
---

# 검수 목록 화면

## route

/review

## title

검수 목록 화면

## device

desktop

## status

draft

## purpose

REVIEWER가 검수 대상 영상 목록을 조회하는 화면(/review/pending alias). 진입 기본값은 검수요청(REVIEW_PENDING) + 제출일 오래된순(FIFO)이며 URL 에 기록되어 새로고침·북마크·뒤로가기에서도 유지된다(필터의 단일 진실원=URL). 화면에서 행을 다시 거르지 않는다 — 서버가 이미 거른 결과를 그대로 그린다. 미등록 정렬 키는 이 화면(/v1/reviews*)에서 lenient 200 + 기본 정렬 폴백 + WARN(작업목록의 strict 400과 의도적으로 다름 — '변경 전 그 엔드포인트가 200이었는가' 기준이며 통일하지 않는다). KPI 4장(검수요청/검수중/승인/반려)는 GET /v1/reviews/summary(API-138) 서버 집계 전체 기준이며 카드 클릭으로 상태 필터 토글(재클릭 해제). 접근: REVIEWER. 비식별이 아직 완료되지 않아 승인이 거부되는 영상은 목록에서 미리 구분해 표시한다 — 이 표시는 승인만 막힌다는 뜻이며 검수 착수·라벨 확인·프레임 열람은 그대로 가능하다. 지금 누가 어떤 영상을 검수 중인지 목록에 함께 보여 같은 영상을 둘이 열어 겹쳐 보는 헛수고를 줄인다 — 내가 잡은 것과 남이 잡은 것을 구분해 표시하고, 유예가 지나 저절로 풀린 것은 표시하지 않는다. 내가 잡고 있는 영상은 여러 건을 골라 한 번에 검수완료할 수 있으며, 고를 수 있는 것은 내가 잡고 있는 영상뿐이다. 마지막으로 누가 언제 어떤 역할로 승인했는지도 함께 보인다. 이 세 표시는 모두 보여 주기만 하는 값이라 검색·정렬 축을 늘리지 않는다 — 이 목록은 검수 대기 전체를 보여주며 「내가 검수 중인 것만 보기」 같은 사용자 축 선택지를 두지 않는다.

## sections

### 헤더 (제목·새로고침)

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 검수 목록

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 화면 상단 제목 아래의 부제 문구.
- **type**: Text
- **label**: 작업자가 제출한 라벨링 결과를 검수합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 목록을 다시 조회한다.
- **type**: Button
- **label**: 새로고침

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 좌측 '검수 목록' 제목 + 부제 문구, 우측 새로고침 버튼.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 검수 현황 KPI

- **role**: hero
- **layout**: grid

**components**:

#### [1]

- **note**: status=REVIEW_PENDING 건수
- **type**: Card
- **label**: 검수요청

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: status=REVIEWING 건수
- **type**: Card
- **label**: 검수중

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: status=COMPLETED 건수
- **type**: Card
- **label**: 승인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: status=REJECTED 건수
- **type**: Card
- **label**: 반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: KPI 4카드(검수 대기/검수중/승인/반려)는 GET /v1/reviews/summary(API-138) 서버 집계로 필터 결과 전체 기준이며 현재 페이지 20건 안에서 세지 않는다. 카드에 장식 아이콘을 두지 않는다 — 지표명과 값 텍스트가 카드의 정보를 모두 전달한다. 로딩/에러/정상 3상태 분기, 집계 실패해도 목록 표시는 막지 않는다. 카드 클릭은 status 필터를 토글(같은 카드 재클릭 시 해제=전체).

**references_apis**:

- API-138

**references_features**:

_(empty)_

### 검색·상태 필터

- **role**: filter
- **layout**: stack

**components**:

#### [1]

- **type**: Input
- **label**: 영상명 / 작업자명

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: keyword
- **placeholder**: 검색어를 입력하세요

#### [2]

- **type**: Select
- **label**: 상태

**columns**:

_(empty)_

**options**:

- 전체
- 검수요청
- 검수중
- 승인
- 반려

- **binds_to**: statusFilter

#### [3]

- **type**: Button
- **label**: 조회

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [4]

- **note**: 필터 활성 시에만 enabled
- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

- **description**: 영상명/작업자명 키워드(q)와 상태(status) select 는 URL 이 단일 진실원이며 GET /v1/reviews(API-008)로 서버 위임된다 — 화면에서 현재 페이지 행을 다시 거르지 않는다. 초기화 버튼은 진입 기본값(검수요청·오래된순)으로 되돌리며, 이미 기본값이면(정렬 포함) 비활성화된다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 일괄 검수완료 실행줄

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 체크칸으로 고른 건수를 보인다. 한 건도 고르지 않았으면 이 줄 전체를 숨긴다.
- **type**: Text
- **label**: 선택한 {n}건

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 누르면 곧바로 처리하지 않고 대상 목록을 보여주는 확인 창을 먼저 연다. 한 건도 고르지 않았거나 고른 건수가 한 번에 보낼 수 있는 상한을 넘으면 비활성이며, 그때는 옆의 안내가 이유를 알린다.
- **type**: Button
- **label**: 일괄 검수완료

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-250

#### [3]

- **note**: 고른 것을 모두 푼다. 페이지를 넘기거나 조회 조건을 바꿔도 고른 것은 유지되지 않고 풀린다 — 화면에 보이지 않는 행이 대상에 남아 있으면 무엇을 보내는지 확인할 수 없기 때문이다.
- **type**: Button
- **label**: 선택 해제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [4]

- **note**: 고른 건수가 한 번에 보낼 수 있는 상한을 넘으면 실행 버튼을 막고 그 사실과 상한을 문구로 알린다. 상한은 운영 설정값이라 화면에 숫자를 박아 두지 않고 그때그때 받은 값을 보인다 — 그 값은 검수 목록 조회 응답이 행마다가 아니라 응답 한 번에 하나 내려주는 값이다.
- **type**: Alert
- **label**: 한 번에 검수완료할 수 있는 건수를 넘었습니다. 선택을 줄여 주세요.
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: 체크칸으로 고른 영상을 한 번에 검수완료하는 실행줄. 목록 표 바로 위에 두며 한 건도 고르지 않았으면 보이지 않는다. 검수완료(승인)만 다루고 반려는 여기에 두지 않는다 — 반려는 건마다 사유가 달라 한 번에 묶을 수 없다. 고른 건수·실행 버튼·선택 해제·상한 초과 안내로 구성된다. 실행 버튼은 곧바로 처리하지 않고 대상 목록을 보여주는 확인 창을 먼저 연다 — 되돌릴 수 없는 처리를 한 번의 클릭으로 시작하지 않는다. 고른 것은 페이지를 넘기거나 조회 조건을 바꾸면 풀린다. 고를 수 있는 것은 내가 잡고 있는 영상뿐이다 — 그 판정은 목록 응답이 행마다 내려주는 일괄 검수완료 자격 값으로 하고 화면이 따로 계산하지 않는다. 남이 잡고 있거나 아무도 잡지 않은 행은 체크칸이 꺼지고 그 자리에 왜 고를 수 없는지 알리는 안내가 함께 선다 — 체크칸만 꺼 두면 이유를 알 길이 없다. 머리행의 전체 선택은 현재 페이지에서 고를 수 있는 행만 고르고 고를 수 없는 행은 건너뛴다. ★이 제약이 안전장치다 — 한 번도 열어 보지 않은 영상을 무더기로 검수완료하는 길이 열리지 않는다. ★점유는 표시이지 필터가 아니다 — 이 목록은 검수 대기 전체를 그대로 보여주며 「내가 검수 중인 것만 보기」 같은 사용자 축 선택지를 두지 않는다.

**references_apis**:

- API-250

**references_features**:

_(empty)_

### 검수 목록 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 검수 목록

**columns**:

- 선택
- 영상명
- 이벤트
- 작업자
- 제출일
- 라벨 수
- 상태
- 검수 중
- 최근 승인
- 액션

**options**:

_(empty)_

#### [2]

- **note**: EventTypeBadge — eventName 없으면 -
- **type**: Badge
- **label**: 이벤트 유형

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: StatusBadge — REVIEW_PENDING/REVIEWING/COMPLETED/REJECTED
- **type**: Badge
- **label**: 상태

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 상태와 재검토 필요 여부로 라벨·강조를 가르고 /review/{id} 로 이동한다. 검수요청은 「검수 시작」, 검수중은 「이어서 검수」, 승인됐지만 재검토가 필요한 영상은 「재검수 시작」, 그 밖은 「결과 보기」다. ★재검수 건을 「결과 보기」로 두지 않는다 — 그 영상도 검수 시작을 눌러야 그 사람이 영상을 잡고 일괄 검수완료 대상에 담을 수 있는데, 결과 보기로 두면 그 길이 화면에 드러나지 않는다.
- **type**: Button
- **label**: 검수 시작 / 이어서 검수 / 재검수 시작 / 결과 보기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [5]

- **note**: onPageChange → URL page 갱신
- **type**: Pagination
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **type**: Custom
- **label**: 갱신 중 안내(이전 결과 표시)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: RefreshingNotice

#### [7]

- **note**: needsRecheck=true 인 영상의 상태 셀에 StatusBadge와 나란히 표시한다. 색상만으로 구분하지 않고 '재검토 필요' 텍스트를 함께 표시한다. 필터·정렬 축은 추가하지 않는다(표시 전용).
- **type**: Badge
- **label**: 재검토 필요

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 비식별이 아직 완료되지 않아 승인이 거부되는 영상의 상태 셀에 검수 상태 배지와 나란히 표시한다. 색상만으로 구분하지 않고 승인이 막혔다는 사실과 그 사유를 텍스트로 함께 표시한다. 막히는 것은 승인 하나뿐이므로 행 액션 버튼은 비활성으로 두지 않는다. 재검토 필요 배지와는 다른 축이라 하나로 합치지 않는다. 필터·정렬 축은 추가하지 않는다(표시 전용).
- **type**: Badge
- **label**: 승인 불가 (비식별 완료 전)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [9]

- **note**: 내가 잡고 있는 영상의 행에서만 켜진다 — 그 판정은 목록 응답이 행마다 내려주는 일괄 검수완료 자격 값으로 하고 화면이 따로 계산하지 않는다. 남이 잡고 있거나 아무도 잡지 않은 행에서는 꺼지며, 그 자리에 왜 고를 수 없는지 알리는 안내를 함께 둔다.
- **type**: Checkbox
- **label**: 행 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [10]

- **note**: 머리행의 전체 선택. 현재 페이지에서 고를 수 있는 행만 고르며 고를 수 없는 행은 건너뛴다. 일부만 골라져 있으면 중간 상태로 보인다.
- **type**: Checkbox
- **label**: 현재 페이지 전체 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [11]

- **note**: 지금 그 영상을 검수 중인 사람을 「{이름} 검수 중」으로 보인다. 내가 잡은 것은 「내가 검수 중」으로 달리 보여 남의 것과 구분한다. 아무도 잡지 않았거나 유예가 지나 풀렸으면 이 칸을 비운다. 색상만으로 구분하지 않고 문구로 함께 알린다. 보여 주기만 하는 값이라 검색·정렬 축을 늘리지 않는다.
- **type**: Badge
- **label**: {이름} 검수 중

**columns**:

_(empty)_

**options**:

_(empty)_

#### [12]

- **note**: 마지막으로 그 영상을 승인한 사람과 그 행위 시점의 역할, 승인 시각을 「{이름}({역할}) · {시각}」으로 보인다. 승인 이력이 없으면 칸을 비운다. 역할은 승인한 그 시점에 기록된 값이라 그 사람의 지금 역할과 다를 수 있으며 그것이 의도다. 역할만 비어 있는 옛 기록은 빈 괄호를 남기지 않고 이름과 시각만 보인다. 보여 주기만 하는 값이라 검색·정렬 축을 늘리지 않는다.
- **type**: Text
- **label**: {이름}({역할}) · {시각}

**columns**:

_(empty)_

**options**:

_(empty)_

#### [13]

- **note**: 고를 수 없는 행의 체크칸 자리에 그 이유를 짧게 보인다 — 남이 잡고 있으면 「{이름} 검수 중」, 아무도 잡지 않았으면 「검수 시작 후 선택할 수 있습니다」다. 체크칸만 꺼 두면 왜 안 되는지 알 길이 없다.
- **type**: Text
- **label**: 선택할 수 없는 이유

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: DataTable로 검수 대상 영상을 페이징 표시. 컬럼: 영상명(+video-NNNN id)/이벤트(EventTypeBadge)/작업자/제출일(정렬 가능, BE allowlist 유일 sortable 컬럼)/라벨 수(좌정렬)/상태(StatusBadge, sortable 미노출 — 진입 기본화면은 상태가 한 종류로 수렴해 1차 정렬이 무효화되고 FIFO 의미가 깨질 수 있어 의도적으로 제외)/액션. 행 액션 버튼은 상태별로 라벨·스타일 분기(REVIEW_PENDING=검수 시작 primary / REVIEWING=이어서 검수 / 그 외=결과 보기), 클릭 시 /review/{id} 이동. 서버 페이징(page/size/sort은 URL searchParams)+정렬 변경 시 page=0 리셋. 에러 시 ErrorState, 빈 목록 시 안내. 필터·정렬·페이지 전환 중에는 새 결과가 도착하기 전까지 이전 조건의 행을 그대로 유지하며 '갱신 중' 안내를 함께 표시해 과도기 상태임을 알려준다. 총 페이지 수가 줄어 현재 페이지가 범위를 벗어나면 자동으로 마지막 페이지로 되돌린다. 상태 셀에는 재검토 필요(needsRecheck=true) 영상을 별도로 식별할 수 있는 배지를 StatusBadge와 나란히 표시한다 — 색상만으로 구분하지 않고 '재검토 필요' 텍스트를 함께 표시한다. 이 배지는 표시 전용이며 별도의 필터·정렬 축을 추가하지 않는다. 비식별이 아직 완료되지 않아 승인이 거부되는 영상은 상태 셀에 그 사실을 알리는 배지를 함께 표시해 목록에서 미리 구분한다 — 열어 본 뒤에야 승인이 막힌 것을 알게 되는 동선을 만들지 않는다. 막히는 것은 승인 하나뿐이므로 행 액션 버튼은 그대로 두어 검수 착수·라벨 확인·프레임 열람이 가능함을 함께 드러낸다. 재검토 필요 배지와는 다른 축이라 하나로 합치지 않으며, 이 배지도 표시 전용이라 필터·정렬 축을 추가하지 않는다. 선택 체크칸과 「검수 중」·「최근 승인」 칸의 규칙은 각 컴포넌트 메모를 따른다.

**references_apis**:

- API-008
- API-250

**references_features**:

_(empty)_

### 일괄 검수완료 확인 창

- **role**: modal
- **layout**: list

**components**:

#### [1]

- **type**: Heading
- **label**: 일괄 검수완료

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 무엇을 처리하는지 건수로 먼저 알린다.
- **type**: Text
- **label**: 선택한 {n}건을 검수완료합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 고른 영상을 이름과 영상 번호로 모두 보인다. 건수만 보이고 무엇인지 보이지 않으면 잘못 고른 것을 확인할 수 없다. 목록이 길면 창 안에서 스크롤한다.
- **type**: List
- **label**: 대상 영상 목록

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 검수완료는 작업 완료를 뜻하며 되돌릴 수 없다는 사실을 알린다.
- **type**: Text
- **label**: 검수완료는 작업 완료를 뜻합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 누르면 고른 건을 한 번에 보낸다. 처리하는 동안 비활성이며 두 번 눌리지 않는다.
- **type**: Button
- **label**: 검수완료

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-250

#### [6]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 일괄 검수완료 실행 전에 무엇을 처리하는지 보여주는 확인 창. 건수와 대상 영상 목록을 함께 보이고, 검수완료가 작업 완료를 뜻해 되돌릴 수 없음을 알린다. 확인을 누르면 고른 건을 한 번에 보내고 처리하는 동안 버튼이 비활성이 되어 두 번 눌리지 않는다. 처리가 끝나면 이 창을 닫고 결과 창을 연다.

**references_apis**:

- API-250

**references_features**:

_(empty)_

### 일괄 검수완료 결과 창

- **role**: modal
- **layout**: list

**components**:

#### [1]

- **type**: Heading
- **label**: 일괄 검수완료 결과

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 성공과 실패를 함께 보인다. 한 건도 되지 않았어도 요청 자체는 받아들여진 것이므로 오류 화면으로 바꾸지 않고 이 결과로 알린다.
- **type**: Text
- **label**: 성공 {n}건 · 실패 {m}건

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 되지 않은 건을 영상 이름과 사유로 하나씩 보인다. 사유는 응답이 건마다 내려주는 사유 코드로 가르고 메시지 문자열로 가르지 않는다. 실패가 없으면 이 표를 두지 않는다.
- **type**: Table
- **label**: 처리되지 않은 영상

**columns**:

- 영상명
- 사유

**options**:

_(empty)_

#### [4]

- **note**: 되지 않은 건만 다시 고른 상태로 목록에 돌아간다. 사유를 없앤 뒤 곧바로 다시 시도할 수 있게 한다. 실패가 없으면 두지 않는다.
- **type**: Button
- **label**: 실패한 건만 다시 선택

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [5]

- **note**: 닫으면 목록을 다시 조회해 처리된 건의 상태와 검수 중 표시를 새로 받는다.
- **type**: Button
- **label**: 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 일괄 검수완료를 보낸 뒤 건별 결과를 보여주는 창. 되는 것만 처리되므로 성공과 실패가 함께 나올 수 있고, 성공 건수와 실패 건수를 함께 보인다. 처리되지 않은 영상은 이름과 사유를 하나씩 보여 무엇을 더 해야 하는지 알린다 — 사유는 응답이 건마다 내려주는 사유 코드로 가르고 메시지 문자열로 가르지 않는다. 되지 않은 건만 다시 골라 이어서 시도할 수 있다. 한 건도 처리되지 않았어도 요청 자체는 받아들여진 것이므로 오류 화면으로 바꾸지 않고 이 결과로 알린다. 창을 닫으면 목록을 다시 조회해 처리된 건의 상태와 검수 중 표시를 새로 받는다.

**references_apis**:

- API-250

**references_features**:

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-002

### change_kind

- merge

### diff_summary

1차 1·2차 검수 → 2차 단일 검수 목록

## surface_kind

web

## consumes_apis

- API-008
- API-138
- API-250

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-334

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:25:14.006Z

### module_paths

_(empty)_

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-018/main.html
- **label**: 메인 페이지
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

#### 헤더 (제목·새로고침)

- **role**: header
- **layout**: stack

**components**:

##### [1]

- **type**: Heading
- **label**: 검수 목록

**columns**:

_(empty)_

**options**:

_(empty)_

##### [2]

- **note**: 화면 상단 제목 아래의 부제 문구.
- **type**: Text
- **label**: 작업자가 제출한 라벨링 결과를 검수합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

##### [3]

- **note**: 목록을 다시 조회한다.
- **type**: Button
- **label**: 새로고침

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 좌측 '검수 목록' 제목 + 부제 문구, 우측 새로고침 버튼.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

#### 검수 현황 KPI

- **role**: hero
- **layout**: grid

**components**:

##### [1]

- **note**: status=REVIEW_PENDING 건수
- **type**: Card
- **label**: 검수요청

**columns**:

_(empty)_

**options**:

_(empty)_

##### [2]

- **note**: status=REVIEWING 건수
- **type**: Card
- **label**: 검수중

**columns**:

_(empty)_

**options**:

_(empty)_

##### [3]

- **note**: status=COMPLETED 건수
- **type**: Card
- **label**: 승인

**columns**:

_(empty)_

**options**:

_(empty)_

##### [4]

- **note**: status=REJECTED 건수
- **type**: Card
- **label**: 반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: KPI 4카드(검수 대기/검수중/승인/반려)는 GET /v1/reviews/summary(API-138) 서버 집계로 필터 결과 전체 기준이며 현재 페이지 20건 안에서 세지 않는다. 카드에 장식 아이콘을 두지 않는다 — 지표명과 값 텍스트가 카드의 정보를 모두 전달한다. 로딩/에러/정상 3상태 분기, 집계 실패해도 목록 표시는 막지 않는다. 카드 클릭은 status 필터를 토글(같은 카드 재클릭 시 해제=전체).

**references_apis**:

- API-138

**references_features**:

_(empty)_

#### 검색·상태 필터

- **role**: filter
- **layout**: stack

**components**:

##### [1]

- **type**: Input
- **label**: 영상명 / 작업자명

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: keyword
- **placeholder**: 검색어를 입력하세요

##### [2]

- **type**: Select
- **label**: 상태

**columns**:

_(empty)_

**options**:

- 전체
- 검수요청
- 검수중
- 승인
- 반려

- **binds_to**: statusFilter

##### [3]

- **type**: Button
- **label**: 조회

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

##### [4]

- **note**: 필터 활성 시에만 enabled
- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

- **description**: 영상명/작업자명 키워드(q)와 상태(status) select 는 URL 이 단일 진실원이며 GET /v1/reviews(API-008)로 서버 위임된다 — 화면에서 현재 페이지 행을 다시 거르지 않는다. 초기화 버튼은 진입 기본값(검수요청·오래된순)으로 되돌리며, 이미 기본값이면(정렬 포함) 비활성화된다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

#### 검수 목록 테이블

- **role**: main
- **layout**: list

**components**:

##### [1]

- **type**: Table
- **label**: 검수 목록

**columns**:

- 영상명
- 이벤트
- 작업자
- 제출일
- 라벨 수
- 상태
- 액션

**options**:

_(empty)_

##### [2]

- **note**: EventTypeBadge — eventName 없으면 -
- **type**: Badge
- **label**: 이벤트 유형

**columns**:

_(empty)_

**options**:

_(empty)_

##### [3]

- **note**: StatusBadge — REVIEW_PENDING/REVIEWING/COMPLETED/REJECTED
- **type**: Badge
- **label**: 상태

**columns**:

_(empty)_

**options**:

_(empty)_

##### [4]

- **note**: 상태별 라벨·variant 분기, /review/{id} 이동
- **type**: Button
- **label**: 검수 시작 / 이어서 검수 / 결과 보기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

##### [5]

- **note**: onPageChange → URL page 갱신
- **type**: Pagination
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

##### [6]

- **type**: Custom
- **label**: 갱신 중 안내(이전 결과 표시)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: RefreshingNotice

##### [7]

- **note**: needsRecheck=true 인 영상의 상태 셀에 StatusBadge와 나란히 표시한다. 색상만으로 구분하지 않고 '재검토 필요' 텍스트를 함께 표시한다. 필터·정렬 축은 추가하지 않는다(표시 전용).
- **type**: Badge
- **label**: 재검토 필요

**columns**:

_(empty)_

**options**:

_(empty)_

##### [8]

- **note**: 비식별이 아직 완료되지 않아 승인이 거부되는 영상의 상태 셀에 검수 상태 배지와 나란히 표시한다. 색상만으로 구분하지 않고 승인이 막혔다는 사실과 그 사유를 텍스트로 함께 표시한다. 막히는 것은 승인 하나뿐이므로 행 액션 버튼은 비활성으로 두지 않는다. 재검토 필요 배지와는 다른 축이라 하나로 합치지 않는다. 필터·정렬 축은 추가하지 않는다(표시 전용).
- **type**: Badge
- **label**: 승인 불가 (비식별 완료 전)

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: DataTable로 검수 대상 영상을 페이징 표시. 컬럼: 영상명(+video-NNNN id)/이벤트(EventTypeBadge)/작업자/제출일(정렬 가능, BE allowlist 유일 sortable 컬럼)/라벨 수(좌정렬)/상태(StatusBadge, sortable 미노출 — 진입 기본화면은 상태가 한 종류로 수렴해 1차 정렬이 무효화되고 FIFO 의미가 깨질 수 있어 의도적으로 제외)/액션. 행 액션 버튼은 상태별로 라벨·스타일 분기(REVIEW_PENDING=검수 시작 primary / REVIEWING=이어서 검수 / 그 외=결과 보기), 클릭 시 /review/{id} 이동. 서버 페이징(page/size/sort은 URL searchParams)+정렬 변경 시 page=0 리셋. 에러 시 ErrorState, 빈 목록 시 안내. 필터·정렬·페이지 전환 중에는 새 결과가 도착하기 전까지 이전 조건의 행을 그대로 유지하며 '갱신 중' 안내를 함께 표시해 과도기 상태임을 알려준다. 총 페이지 수가 줄어 현재 페이지가 범위를 벗어나면 자동으로 마지막 페이지로 되돌린다. 상태 셀에는 재검토 필요(needsRecheck=true) 영상을 별도로 식별할 수 있는 배지를 StatusBadge와 나란히 표시한다 — 색상만으로 구분하지 않고 '재검토 필요' 텍스트를 함께 표시한다. 이 배지는 표시 전용이며 별도의 필터·정렬 축을 추가하지 않는다. 비식별이 아직 완료되지 않아 승인이 거부되는 영상은 상태 셀에 그 사실을 알리는 배지를 함께 표시해 목록에서 미리 구분한다 — 열어 본 뒤에야 승인이 막힌 것을 알게 되는 동선을 만들지 않는다. 막히는 것은 승인 하나뿐이므로 행 액션 버튼은 그대로 두어 검수 착수·라벨 확인·프레임 열람이 가능함을 함께 드러낸다. 재검토 필요 배지와는 다른 축이라 하나로 합치지 않으며, 이 배지도 표시 전용이라 필터·정렬 축을 추가하지 않는다.

**references_apis**:

- API-008

**references_features**:

_(empty)_

- **source_hash**: 5cf289494fb360a90e45417b4fa9e003b9913ba04555376f3f03f736f57abec7
- **generated_at**: 2026-08-19T18:56:38.411Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## uses_components

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-023

## covered_by_acceptances

- AC-1113
- AC-1114
- AC-1116
- AC-1117
