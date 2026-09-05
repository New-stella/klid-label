---
logicraft_item: SCREEN-023
type: screen_spec
version: 47
last_updated_at: 2026-09-02T10:15:11.325Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T02:34:48.356Z
sync_session: 35
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-023.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-061]]", "[[API-062]]", "[[API-063]]", "[[API-188]]", "[[API-189]]", "[[API-190]]", "[[API-175]]"]
  required_roles: ["[[ROLE-001]]"]
  realizes_use_cases: ["[[UC-002]]", "[[UC-010]]"]
---

# 증강 결과 화면

## route

/augment/result/:rawSn

## title

증강 결과 화면

## device

desktop

## status

draft

## purpose

증강 결과를 조회하고 결과물의 활용(채택)·폐기(반려)를 결정하는 화면(/augment/result/:rawSn). 경로 변수는 새로 발급되는 작업 식별자가 아니라 원본 영상 번호다 — 증강 요청 응답의 jobId 필드(임시값)와도 다른 값이므로 그 필드를 이동에 사용하지 않고, 요청 시 사용한 원본 영상 번호(videoIds[0])를 그대로 쓴다. 접근: REVIEWER.

★결정은 새 영상을 만드는 행위가 아니다 — 파생영상(RAW_SN)은 외부 증강 웹훅 수신 시점에 이미 생성되어 있고, 이 화면의 accept/reject 는 검수 행(LS_DATA_AUG_RVW.RVW_STTS_CD)만 쓴다(검수자·검수일시·반려사유 동반). AUG_PROC_STTS_CD 는 생성 결과 전용(웹훅 소유)이라 사람의 검수 결정과는 별개 축이며, 이 화면의 결정은 그 값을 바꾸지 않는다. 검수 행이 ACCEPTED 여야 파생이 작업목록·배정에 등재된다.

[해상도 파생 예외] RESL_1080P/720P/480P 항목은 저작도구 내부 생성물이라 검수 대상이 아니다 — 결정 카드를 노출하지 않고 BE 도 accept/reject 진입을 400 으로 차단한다(이력·집계·프레임 쌍 비교에는 함께 노출).

[반려 후 폐기·복구] 반려하면 그 파생이 즉시 작업 대상에서 빠지고(등재 게이트가 검수 축이라 자동) 유예기간(설정값, 기본 7일) 경과 후 배치가 DB 행과 생성 파일까지 실삭제한다. 유예 내에는 '복구'(POST /v1/augments/{id}/restore, 사유 입력)로 반려 자체를 되돌려 검수를 재오픈해 다시 채택/반려를 고를 수 있으며 되돌린 이력이 남는다. 실삭제된 항목은 복구할 수 없고 증강을 다시 요청해야 한다.

[페이징 2축] page/size = 프레임 쌍, itemPage/itemSize = 결과 항목. 서로 독립된 컨트롤이며 항목 페이저를 프레임 쌍 총량으로 렌더하면 프레임 쌍이 0건인 순수 외부 위탁 잡에서 21번째 항목부터 도달 불가해진다. 진행률은 잡 단위 값이 아니라 항목별 서버 실값이다.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 증강 결과 확인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Breadcrumb
- **label**: 데이터 증강 › 증강 결과 확인 · 영상 #{rawSn}

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: Badge
- **label**: 잡 상태

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: summary.status

- **description**: PageHeader — 제목 '증강 결과 확인' + breadcrumb(데이터 증강 › 증강 결과 확인 · 영상 #{rawSn}) + actions 슬롯에 잡 상태 StatusBadge(PROCESSING/COMPLETED/FAILED). 상태는 서버가 계산한 값을 그대로 표시하며 결과 목록의 유무나 개수로 화면이 다시 계산하지 않는다.

**references_apis**:

- API-061

**references_features**:

- FEAT-004

### 잡 상태 배너 / 결과 지표

- **role**: hero
- **layout**: stack

**components**:

#### [1]

- **note**: PROCESSING 상태일 때 노출하는 배너(스피너 포함). 진행 상태가 종결로 관측되면 결과가 자동 갱신되지만 그 신호가 오지 않는 환경(진행 상태 미연동·항목이 아직 없는 잡)을 대비해 수동 새로고침 버튼을 함께 둔다(아래 새로고침 버튼).
- **type**: Alert
- **label**: 증강 처리 중입니다... (PROCESSING 배너)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: FAILED 상태일 때 노출하는 배너 — 실패 사유(외부 시스템 응답 오류·리소스 부족 등) 안내와 재시도 버튼을 함께 보여준다.
- **type**: Alert
- **label**: 증강 처리 실패 (FAILED 배너)

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [3]

- **note**: FAILED 배너 안에서만 노출 — 클릭 시 화면을 새로고침해 최신 상태를 다시 확인한다.
- **type**: Button
- **label**: 재시도 (FAILED 배너 내)

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [4]

- **note**: COMPLETED 이고 비교 이미지 쌍이 있는 항목이 있을 때만 표시 — 현재 화면에 로드된 프레임 쌍 중 증강 이미지가 생성된 비율(95%↑ 우수·85%↑ 양호·그 외 주의 톤 구분). 라벨을 검사한 값이 아니며 프레임 페이지를 넘기면 값이 바뀔 수 있다는 점을 함께 표기한다('라벨 무결성'이 아니라 '증강 이미지 생성률'이 정확한 명칭).
- **type**: Stat
- **label**: 증강 이미지 생성률 %

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: PROCESSING 배너 내에서만 노출 — 클릭 시 서버 상태를 즉시 재조회한다. 자동 갱신(진행 상태 종결 관측 시 재조회)이 오지 않는 환경의 수동 보완 수단이며 조회 중에는 비활성화된다.
- **type**: Button
- **label**: 새로고침

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [6]

- **note**: COMPLETED 이면서 결과 항목이 0건일 때만 노출 — 처리는 끝났으나 표시할 항목이 없다는 사실만 안내한다(원인을 추정하지 않는다).
- **type**: Alert
- **label**: 증강 처리 완료 — 표시할 결과 항목이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

- **description**: 잡 상태에 따라 배너를 노출한다 — PROCESSING(스피너 배너 + 수동 새로고침), FAILED(실패 배너 + 재시도), COMPLETED(비교 이미지 쌍이 있는 항목이 있으면 증강 이미지 생성률 스탯, 결과 항목이 0건이면 빈 결과 안내). 잡 단위 진행률 바는 표시하지 않는다 — 생성 진행 상황은 결과 항목 단위 진행 상태(아래 절)로 확인한다.

**references_apis**:

- API-061

**references_features**:

- FEAT-004

### 작업 요약 카드

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **note**: 결과 항목 건수는 응답의 전체 건수 값을 쓰고 없으면 로드된 건수로 대신한다. 대상 영상·비교 프레임 쌍은 결과 항목이 페이지 단위로 나뉘어 조회된 경우 '(이 페이지)' 로 범위를 표시한다. 요청일시는 결과 조회 응답의 요청 일시 값이며 값이 없으면 '-' 로 표시한다.
- **type**: KeyValue
- **label**: 작업 ID/대상 영상 건수/결과 항목 건수/비교 프레임 쌍/요청일시

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: summary

- **description**: 작업 요약 dl(5칸): 작업 ID · 대상 영상 건수 · 결과 항목 건수 · 비교 프레임 쌍 · 요청일시(값 없으면 '-'). 대상 영상·비교 프레임 쌍은 결과 항목이 페이지 단위로 조회된 경우 '이 페이지' 기준임을 표기한다. 생성 진행 상황은 결과 항목 단위 진행 상태로 확인한다(아래 절 참조).

**references_apis**:

- API-061

**references_features**:

- FEAT-004

### 영상별 결과 섹션

- **role**: main
- **layout**: tabs

**components**:

#### [1]

- **note**: 탭 하나 = 결과 항목 하나다. 같은 (영상 × 종류) 요청이 여러 건일 수 있어 종류만으로는 항목을 구분할 수 없다 — 종류당 항목이 1건이면 종류명만, 2건 이상이면 종류명 뒤에 순번(잡 전체 기준)과 결정 상태를 덧붙인다(예: '증강 #2 · 반려'). 증강 축의 코드값은 단일 상수 AUGMENT 로 고정하며 생성 조건에서 파생하지 않는다(ADR-059). 증강 종류 코드가 단일값이라 코드만으로는 파생본을 서로 구분할 수 없다 — 구분 축은 생성 조건 원문이다(생성 조건 요약 참조). 구 WINTER/NIGHT/RAIN 은 이미 만들어진 파생본에 남아 있어 보존한다(백필하지 않는다) — 조회·표시 경로는 옛 값과 새 값을 모두 견뎌야 한다.
- **type**: Tabs
- **label**: 항목 탭 (유형 + 순번·결정 상태로 구분)

**columns**:

_(empty)_

**options**:

- AUGMENT
- WINTER
- NIGHT
- RAIN
- RESOLUTION
- RESL_1080P
- RESL_720P
- RESL_480P

#### [2]

- **note**: 프레임 쌍은 페이지당 12건이다(프레임 쌍 축 페이징, 결과 항목 축과 별개). 칸을 클릭하면 원본·증강 확대 비교가 열린다.
- **type**: Custom
- **label**: 12 프레임 페어 그리드(원본/증강)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: result.framePairs
- **custom_name**: FrameGrid12

#### [3]

- **note**: 그리드에서 프레임을 클릭하면 열리는 비교 창 — 좌측 원본, 우측 증강 결과를 나란히 표시한다. 이미지가 없으면 사유 문구('원본 이미지 없음' / '생성 실패')를 보여준다.
- **type**: Custom
- **label**: 원본 vs 증강 확대 비교

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: selectedPair
- **custom_name**: SideBySideCompare

#### [4]

- **note**: 같은(영상×종류) 재요청이 허용되므로 이 결과물이 어떤 조건으로 생성됐는지 보여주는 유일한 단서(역추적). 서버가 저장한 전송 원문(생성 조건 5필드 JSON)을 필드별로 표시하고, 파싱 실패 시 원문을 그대로 표시한다. 해상도 파생·구 요청은 생성 조건이 없어 미노출.
- **type**: Custom
- **label**: 생성 조건 요약

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: result.prompt
- **custom_name**: AugmentPromptSummary

#### [5]

- **note**: 프레임 쌍이 페이지당 12건을 넘을 때만 노출. 쌍이 실재하는데 현재 페이지에만 없는 경우(탭 전환 직후 등) '첫 페이지로' 돌아가는 복구 수단을 함께 제공한다.
- **type**: Pagination
- **label**: 프레임 쌍 페이저

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: framePage

#### [6]

- **note**: 결과 항목 축(itemPage/itemSize) 페이저 — 프레임 쌍 축과 서로 독립된 컨트롤이다. 항목 페이지를 넘기면 프레임 쌍 페이지는 첫 페이지로 리셋된다(빈 그리드에 갇히는 것을 방지).
- **type**: Pagination
- **label**: 결과 항목 페이저

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: itemPage

- **description**: 영상 번호로 그룹핑한 영상별 섹션 — 조회된 결과에 포함된 영상 그룹을 모두 펼쳐서 보여준다(그룹 개수 제한 없음, 결과 항목 자체는 항목 축 페이징으로 건수를 제한한다). 각 섹션: 영상명 제목 + 항목 탭(위 컴포넌트). 활성 탭마다 생성 조건 요약, (생성 진행 중인 항목이면) 진행 상태(아래 절), 활용 결정 카드, 프레임 비교 그리드를 순서대로 보여준다.

**references_apis**:

- API-061

**references_features**:

- FEAT-004

### 결과 항목 진행 상태 · 취소

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **note**: 위탁 증강에서 생성이 진행 중인 항목에만 노출한다(해상도 파생은 외부 위탁이 없어 대상이 아니다). 완료/전체 건수와 진행률(0~100%, 산출 불가 시 사유)을 함께 표시하고, 취소 가능한 상태일 때만 '요청 취소' 버튼을 노출한다. GET /v1/augments/{id}/progress 로 진행 상태를 주기 조회하며, 조회 간격은 서버가 알려준다(0 이면 더 조회하지 않는다). 조회에 실패하면 자동 조회를 멈추고 수동 재시도를 제공한다.
- **type**: Custom
- **label**: 생성 진행 패널 (완료/전체 건수, 진행률 %)

**columns**:

_(empty)_

**options**:

- RECEIVED
- RUNNING
- SUCCEEDED
- FAILED
- CANCELED

- **custom_name**: AugmentProgressPanel

#### [2]

- **note**: 진행률을 계산할 수 없는 사유를 그대로 안내한다 — 미연동(오류 아님) / 외부 조회 일시 실패(자동 재표시) / 접수 응답 대기 중 / 조회 한도 도달(갱신 지연). 0% 로 표시하지 않는다.
- **type**: Custom
- **label**: 진행률 산출 불가 사유 안내 (4종)

**columns**:

_(empty)_

**options**:

- NOOP
- TRANSIENT_ERROR
- AWAITING_ACK
- QUERY_LIMIT_EXCEEDED

#### [3]

- **note**: 취소 가능한 상태(대기 중)일 때만 노출한다. 클릭하면 취소 확인 대화상자를 연다.
- **type**: Button
- **label**: 요청 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [4]

- **note**: 사유 입력은 선택이다(최대 500자, 기록용) — 필수로 만들지 않는다. '되돌릴 수 없으며 여러 건으로 나눠 위탁되어 일부만 취소될 수 있다'는 점을 확인 전 안내한다. POST /v1/augments/{id}/cancel 로 제출한다. REVIEWER 전용.
- **type**: Dialog
- **label**: 증강 요청 취소 확인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 전체가 취소되지 않고 일부만 취소되는 것은 정상 결과다 — 그 경우 별도 재시도를 안내하지 않는다.
- **type**: Toast
- **label**: 취소 처리 결과 안내 (부분 취소 포함)

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 결과 항목별 진행 상태 — 위탁 증강에서 생성이 진행 중인 항목에 표시한다(해상도 파생은 대상이 아니다). 진행률(완료/전체 건수, 0~100% 또는 산출 불가 사유)과 취소 가능 시 취소 버튼을 함께 보여준다. 취소는 REVIEWER 전용이며 사유는 선택 입력이고, 하나의 요청이 여러 건으로 나뉘어 위탁되므로 일부만 취소되는 결과도 정상이다.

**references_apis**:

- API-188
- API-189

**references_features**:

- FEAT-004

### 활용 결정 카드(DecisionCard)

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **type**: Button
- **label**: 채택

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-062

#### [2]

- **note**: 사유 입력 대화상자로 사유를 입력한다(필수, 최대 500자).
- **type**: Button
- **label**: 반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive
- **triggers_api**: API-063

#### [3]

- **note**: 사유 필수, 최대 500자.
- **type**: Dialog
- **label**: 반려 사유 입력

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **type**: Toast
- **label**: 채택/반려 처리됨·처리 실패

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 반려(REJECTED) 항목 중 복구 가능(restoreEligible=true)일 때만 노출한다. 클릭하면 사유 입력 대화상자가 열리고, 사유(필수, 최대 500자) 입력 후 제출하면 폐기 표식이 해제되고 검수가 재오픈되어 다시 채택·반려를 선택할 수 있다. 되돌린 이력이 남는다. POST /v1/augments/{id}/restore 로 제출한다. 실삭제된 항목에는 노출하지 않는다.
- **type**: Button
- **label**: 복구

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [6]

- **note**: 반려 항목에 붙는 폐기 안내(폐기 일시/실삭제 예정 일시/실삭제 여부/복구 가능 여부). 유예 기본 7일, 경과 시 배치가 DB 행·파일 실삭제
- **type**: Alert
- **label**: 폐기 유예 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [7]

- **note**: 사용자 요청으로 취소되어 이 결과는 활용되지 않는다는 안내 — 생성 실패·사람의 반려와 구분되는 별도 상태(결정 대기/채택됨/반려됨과 함께 4번째 상태)다. 채택·반려·복구 버튼은 표시하지 않는다.
- **type**: Alert
- **label**: 취소됨

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

- **description**: 결과 항목별 활용 결정. 채택/반려는 검수 결정만 기록하며 파생 영상 자체를 새로 만들지 않는다 — 파생 영상은 이 화면에 결과가 표시되는 시점에 이미 생성돼 있고, 사람의 결정은 그 파생을 작업목록·배정에 등재할지(채택) 폐기할지(반려)를 정할 뿐이다. 노출 규칙 — 해상도 파생 항목은 결정 카드를 표시하지 않는다(내부 생성물이라 검수 대상이 아니다), 그 외는 결정이 이미 내려졌거나 결정 가능 상태면 표시한다. 결정 대기=[채택]/[반려](반려는 사유 입력 대화상자로 사유 필수·최대 500자), 채택됨=채택됨+결정일시, 반려됨=반려됨+결정일시+사유+폐기 유예 안내(폐기 일시/실삭제 예정 일시/실삭제 여부/복구 가능 여부). 반려 항목에는 [복구] 버튼이 남아 반려 자체를 되돌리고 검수를 재오픈한다(표식만 지우면 반쪽 복구다). 복구 가능 여부는 서버가 계산한 값을 그대로 쓴다(재유도 금지 — 눌러도 반드시 실패하는 버튼을 만들지 않는다). 실삭제된 항목은 복구 불가·재요청 안내. 항목별 상태값(생성 중/프레임 준비 중/준비 완료/비식별 신고로 보류/생성 실패/취소됨/삭제됨/이전 결과물이라 연결 끊김)이 비교 이미지가 없는 이유를 설명한다. 채택 API-062, 반려 API-063.

**references_apis**:

- API-062
- API-063
- API-190

**references_features**:

- FEAT-004

### 로딩 / 에러 / 잘못된 영상 번호

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Skeleton
- **label**: 로딩 스켈레톤
- **state**: loading

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Alert
- **label**: 증강 결과를 불러올 수 없습니다 / 잘못된 영상 번호
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: isLoading=Skeleton, error=ErrorState('증강 결과를 불러올 수 없습니다'), jobId 가 양의 정수가 아니면 ErrorState('잘못된 영상 번호'). API-061 조회 상태 기반.

**references_apis**:

- API-061

**references_features**:

- FEAT-004

## brownfield

### status

modified

### decided_by

ADR-004

### change_kind

- redesign

### diff_summary

증강 결과 수락·거부(FEAT-004)

## surface_kind

web

## consumes_apis

- API-061
- API-062
- API-063
- API-188
- API-189
- API-190
- API-175

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-210

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-29T01:18:02.915Z

### module_paths

_(empty)_

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-023/main.html
- **label**: 증강 결과 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 0872ab0ec132a6842802a0a0816ad16b6959c2536e09d66787fa7dc9447975a7
- **generated_at**: 2026-09-02T10:15:11.325Z
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

- UC-002
- UC-010

## covered_by_acceptances

_(empty)_
