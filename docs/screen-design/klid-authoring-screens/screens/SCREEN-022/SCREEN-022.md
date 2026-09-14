---
logicraft_item: SCREEN-022
type: screen_spec
version: 51
last_updated_at: 2026-09-02T10:10:35.993Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-14T05:34:04.726Z
sync_session: 39
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-022.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-042]]", "[[API-059]]", "[[API-060]]", "[[API-092]]", "[[API-179]]"]
  required_roles: ["[[ROLE-001]]"]
  realizes_use_cases: ["[[UC-001]]", "[[UC-003]]"]
---

# 증강 요청 화면

## route

/augment

## title

증강 요청 화면

## device

desktop

## status

draft

## purpose

REVIEWER 가 검수 완료(승인) 영상 1건에 처리 종류 하나(증강 AI 또는 해상도 변경)를 단일 선택해 요청하는 화면(/augment). 증강은 외부 위탁 잡 요청(POST /v1/augments/request), 해상도 변경은 저작도구 직접 수행(POST /v1/videos/{rawSn}/resolution)이다.

[증강 — 생성 조건과 자유 지시문] 증강을 고르면 생성 조건·자유 지시문 블록이 나타난다. ①생성 조건은 시간대·계절·날씨·지형·심각도 다섯 항목이며 각각 정해진 보기 중에서 고른다(자유 입력이 아니다). 다섯 항목을 모두 골라야 하며 하나라도 비어 있으면 요청 버튼이 비활성이고 사유를 안내한다. 외부 연동 계약 자체는 최소 한 항목만 요구하므로 이 화면이 더 엄격하다 — 의도된 선택이다. 하나라도 비우면 외부가 어떤 기본값으로 채울지 이쪽에서 알 수 없어 같은 요청의 결과가 비결정적이 된다. ②자유 지시문은 1,000자 이내 선택 입력이며, 생성 조건과 충돌하는 표현을 적으면 생성 조건이 우선하고 무시된 표현이 경고로 회신된다. 선택·입력한 값은 개인식별정보 입력 금지 안내와 함께 그대로 외부 생성형 AI 로 전송되고 요청 원문이 보관된다. 증강 종류 코드는 단일값 AUGMENT 이며 생성 조건에서 파생하지 않는다 — 무엇으로 바꿀지는 생성 조건이 정한다. 겨울·야간·우천은 생성 조건 프리셋으로 제시한다. 이벤트 유형과 세부 유형은 요청자가 고르지 않으며 서버가 중립값으로 고정해 싣는다(ADR-059). 해상도 변경은 외부 위탁이 아니라 이 블록을 받지 않는다.

[요청 응답 처리] 요청 성공 응답의 jobId 는 식별자가 아닌 임시값이며 실제 엔티티를 가리키지 않으므로 결과 화면 이동에 사용하지 않는다 — 이동 대상은 요청 본문에 실은 원본 영상 번호(videoIds[0])다.

[거부 조건] ①파생영상(ORGNL_RAW_SN non-null)은 요청 대상이 아니며 400 이다 — 파생 깊이를 1 로 고정하는 영구 조건이라 재시도 여지가 없고(비식별 신고의 412 와 다른 축), 화면은 영상 상세의 derivative 로 미리 알아 버튼을 비활성화하고 툴팁으로 사유를 보인다 ②외부 증강 시스템 미연동 503 — 배포 환경 구성이라 요청자가 해소할 수 없고, 이 거부가 아래 셋보다 앞이라 미연동 환경에서는 아래 거부가 나타나지 않는다 ③미검수 영상 400(NOT_REVIEWED) ④비식별 누락 신고 구간 412 ⑤프레임 미추출 412.

[중복 요청] ★같은 (영상 × 종류) 재요청은 몇 번이든 허용되며 BE 가 차단하지 않는다 — 생성 결과가 매번 달라 원하는 이미지가 안 나오면 동일 조건으로 다시 요청하는 것이 정상 운영 동선이기 때문이다. 오조작(연타) 방어는 FE 단독 책임이다(제출 중 버튼 비활성).

접근: REVIEWER.

## sections

### 안내 배너

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: lucide Info 아이콘 + 안내 텍스트
- **type**: Alert
- **label**: 처리 요청은 검수 완료(승인)된 영상만 가능합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 검수 완료(승인) 영상만 처리 요청 가능하다는 정책 안내 배너. Info 아이콘 + 안내 문구 고정 표시. 미승인 영상은 목록에 노출되지 않음을 명시.

**references_apis**:

_(empty)_

**references_features**:

- FEAT-004

### Step 1: 처리 종류 선택 (단일 선택)

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **note**: role=radiogroup 내 카드 2개. 로빙 tabindex + 화살표(↑↓←→) 탐색, 선택 시 selected aria-checked. 겨울·야간·우천은 생성 조건 프리셋으로 제시하며, 프리셋을 고르면 생성 조건 다섯 항목에 그에 맞는 값이 채워진다 — 겨울은 계절과 날씨, 야간은 시간대, 우천은 날씨. 채워진 값은 검수자가 수정할 수 있으며, 이미 입력한 항목은 프리셋을 바꿔도 덮어쓰지 않는다.
- **type**: Custom
- **label**: 처리 종류 카드 ×2 (증강 AI / 해상도 변경) — 단일 선택

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ProcessKindCard

#### [2]

- **note**: 선택된 종류 라벨 표시 (selectedKind 있을 때만)
- **type**: Badge
- **label**: 선택된 종류 라벨

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: selectedKind===null 시 노출
- **type**: Alert
- **label**: 처리 종류를 하나 선택하세요.
- **state**: default

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [4]

- **note**: selectedKind==='RESOLUTION' 일 때만 노출(TargetResolutionSelect). ★체크박스 다중 선택 이며 기본값은 3종 전체다. 프리셋의 두 수치는 산출 프레임의 고정 크기가 아니라 크기 상한이며, 실제 산출 프레임 크기는 원본 종횡비에 따라 달라진다 — 화면은 프리셋을 고정 산출 크기로 안내하지 않는다. 선택값은 RESOLUTION_PRESETS allowlist 로만 좁혀 임의 문자열 분기를 차단한다. 요청 바디의 presets[] 는 optional — 미지정이면 표준 3종 전체가 대상. 파생영상 생성 결과/에러 inline 표시.
- **type**: Custom
- **label**: 타겟 해상도 선택 (RESOLUTION 종류 선택 시에만)

**columns**:

_(empty)_

**options**:

- RESL_1080P
- RESL_720P
- RESL_480P

- **custom_name**: TargetResolutionSelect
- **triggers_api**: API-092

#### [5]

- **note**: 증강 AI 선택 시에만 노출. 시간대·계절·날씨·지형·심각도 다섯 항목을 각각 드롭다운에서 고른다 — 자유 입력이 아니며 보기는 외부 연동 계약이 정한 허용 코드다(시간대 새벽/낮/황혼/밤, 계절 봄/여름/가을/겨울, 날씨 맑음/흐림/비/눈/안개/바람, 지형 도로/지하차도/하천/도심/주거지역/시골/산지/숲, 심각도 낮음/보통/높음). 다섯 항목을 모두 골라야 하며 하나라도 비어 있으면 요청 버튼이 비활성이고 사유를 안내한다. ⚠ 외부 연동 계약 자체는 최소 한 항목만 요구하므로 이 화면이 더 엄격하다 — 의도된 선택이다. 하나라도 비우면 외부가 어떤 기본값으로 채울지 이쪽에서 알 수 없어 같은 요청의 결과가 비결정적이 된다. 선택값은 가공 없이 외부 생성형 AI 로 전송되고 요청 원문에 함께 보관된다. 증강 종류는 이 값에서 파생하지 않는다. 해상도 변경은 미노출.
- **type**: Custom
- **label**: 생성 조건 5항목 드롭다운 (증강 AI 선택 시에만)

**columns**:

_(empty)_

**options**:

- time
- season
- weather
- terrain
- severity

- **custom_name**: AugmentPromptFieldset

#### [6]

- **note**: 생성 조건(prompt) 5필드 입력 블록 내에 상시 노출 — 이름·차량번호·연락처 등 개인식별정보(PII)를 입력하지 말 것을 입력 지점에서 안내한다. 입력값은 정규화 후 그대로 외부 생성형 AI 서비스로 전송된다는 사실을 함께 알린다.
- **type**: Alert
- **label**: 개인식별정보 입력 금지 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [7]

- **note**: 증강 AI 선택 시에만 노출하는 선택 입력. 1,000자 이내이며 글자수 카운터를 병기한다. 초과분을 잘라 보내지 않고 요청을 거부한다. 생성 조건과 충돌하는 표현(예: 밤을 고르고 지시문에 맑은 낮이라고 적는 경우)은 생성 조건이 우선하며, 무시된 표현은 경고로 회신되어 결과 화면에 표시된다.
- **type**: Textarea
- **label**: 자유 지시문 (선택, 1000자 이내)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 해상도 변경 종류를 고른 동안에만 대상 영상의 파생 확정 상태를 주기 조회해 표시한다(증강 AI 경로는 이 축과 무관하다). 항목마다 선택한 프리셋·실제 산출 프레임 크기·파생 영상 번호·확정 상태(진행 중/검수 대기/실패)를 보이고, 진행 중 건수와 실패 건수를 요약으로 함께 알린다. 바로 위의 생성 응답 블록은 '예약됨'까지만 말하므로, 확정 단계에서 난 실패는 이 블록에서만 드러난다. 실패가 있으면 해당 해상도만 다시 요청할 수 있음을 안내한다.
- **type**: Custom
- **label**: 해상도 파생 확정 현황

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 증강 AI 와 해상도 변경 두 처리 종류 카드를 단일 선택한다(라디오그룹 성격 — 방향키로 이동하며 선택 상태를 보조기술에 알린다). 증강 AI 를 고르면 생성 조건·자유 지시문 입력 블록이, 해상도 변경을 고르면 생성할 해상도 선택이 이어서 나타난다. 해상도는 1080P / 720P / 480P 체크박스 다중 선택이고 기본이 3종 전체이다. 프리셋의 두 수치는 크기 상한이라 실제 산출 프레임 크기는 원본 종횡비에 따라 달라진다. 실행 결과는 파생영상 목록(영상번호·선택한 프리셋·실제 산출 폭·실제 산출 높이·상태)과 '파생영상 N건 생성됨 — 검수 대기 (M건 실패)' 요약으로 그 자리에 표시한다. 생성 요청 응답은 예약까지만 알려주므로, 그와 별개로 대상 영상의 해상도 파생 확정 상태를 주기 조회해 확정 현황을 함께 보여준다 — 확정 단계의 실패가 드러나는 유일한 자리다. 종류를 고르지 않으면 안내 문구를 노출한다.

**references_apis**:

- API-092
- API-179

**references_features**:

- FEAT-004

### Step 2: 대상 영상 선택 (단일 선택)

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Input
- **label**: 영상명 / CCTV / ID 검색

**columns**:

_(empty)_

**options**:

_(empty)_

- **placeholder**: 검색어 입력

#### [2]

- **type**: Select
- **label**: 이벤트

**columns**:

_(empty)_

**options**:

- 전체
- 고정 이벤트 유형들

#### [3]

- **type**: Button
- **label**: 조회

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-042

#### [4]

- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [5]

- **note**: 행 1건만 선택되는 라디오(type=radio name=augment-target-video)
- **type**: Table
- **label**: 검수 완료 영상 테이블 (단건 라디오 선택)

**columns**:

- 선택(라디오)
- 영상명/CCTV
- 이벤트
- 녹화일
- 검수 완료 일시

**options**:

_(empty)_

- **triggers_api**: API-042

#### [6]

- **type**: Custom
- **label**: 이벤트 유형 배지

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EventTypeBadge

#### [7]

- **note**: totalPages>1 시 노출. 이전 · 페이지 번호 · 다음 순서로 배치하고, 페이지 번호는 양끝(첫·마지막)과 현재 앞뒤 1칸만 노출하며 그 사이는 말줄임으로 접는다.
- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Pagination

#### [8]

- **note**: 검수 완료 총건수 배지 + 선택된 영상 #ID 배지(selectedVideoId 있을 때)
- **type**: Badge
- **label**: 검수 완료 N건 / #N 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [9]

- **note**: selectedVideoId 있을 때 노출
- **type**: Button
- **label**: 선택 해제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

- **description**: 검수 완료(dataSttsCd=COMPLETED & reviewStatusCd=APPROVED — 여기서 dataSttsCd 는 배치 단계 축인 LS_DATA_RAW.DATA_STTS_CD 이며 작업·검수 워크플로 축인 LS_RAW_DATA_STATUS.DATA_STTS_CD 가 아니다) 영상만 BE 페이징(size 20)으로 조회. 영상명/CCTV/ID 검색 + 이벤트 select 필터(조회/초기화). 라디오(type=radio name=augment-target-video)로 1건 단일 선택(상태 selectedVideoId: number|null, 같은 행 재클릭 시 해제). 컬럼: 선택(라디오)/영상명·CCTV·#ID/이벤트/녹화일/검수 완료 일시. 클라이언트 측 현재 페이지 한정 필터. 페이지네이션(이전 · 페이지 번호 · 다음, 번호는 양끝과 현재 앞뒤 1칸 + 말줄임). 로딩 Skeleton, 빈 상태 EmptyState. 검수 완료 총건수 배지 + 선택 시 '#ID 선택' 배지 + 선택 해제. videoId 는 number 로만 처리.

**references_apis**:

- API-042

**references_features**:

- FEAT-004

### 최근 요청 이력

- **role**: side
- **layout**: grid

**components**:

#### [1]

- **note**: 클릭 시 /augment/result/{jobId} 로 이동. 카드 1건 = 영상 1건에 요청된 처리 종류 묶음.
- **type**: Custom
- **label**: 증강 잡 카드 ×6 (CCTV명/상태/처리 종류·해상도 배지/영상 건수/요청 일시)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: JobCard
- **triggers_api**: API-059

#### [2]

- **type**: Badge
- **label**: 전체 N건

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: ErrorState
- **type**: Alert
- **label**: 이력을 불러올 수 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

- **description**: 증강 잡 이력 6건 카드 그리드(JobCard). GET /v1/augments page=0 size=6(API-059). 응답은 영상 단위 그룹핑이며 작업 식별자/영상 번호/CCTV명/요청된 처리 종류 목록(위탁 증강)/해상도 파생 목록/상태/요청 일시/완료 일시/요청 영상 수 9필드로 제공한다. 카드는 CCTV명·상태 배지·처리 종류 배지(해상도 파생은 별도 톤으로 구분, 증강 없이 해상도 파생만 있으면 '파생' 배지 병기)·영상 건수·요청 일시를 표시한다. 카드 클릭 시 /augment/result/{jobId} 이동. 전체 건수 배지. 로딩 Skeleton, 에러 ErrorState, 빈 상태 EmptyState.

**references_apis**:

- API-059

**references_features**:

- FEAT-004

### 고정 하단 액션 바

- **role**: footer
- **layout**: stack

**components**:

#### [1]

- **note**: 곱연산 카운트 아님 — '종류 × 영상 #N' 단순 선택 요약
- **type**: Custom
- **label**: 선택: {종류} × 영상 #N

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: SelectionSummary

#### [2]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [3]

- **note**: 증강 AI → POST /v1/augments/request, RESOLUTION → POST /v1/videos/{rawSn}/resolution. canSubmit 충족 시 활성/loading isPendingAny. 성공 시 이동 대상은 응답의 jobId 가 아니라 요청 본문의 videoIds[0](원본 영상 번호)이다 — jobId 는 임시값이며 결과 조회 이동에 사용하지 않는다.
- **type**: Button
- **label**: 처리 요청
- **state**: disabled

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-060

#### [4]

- **type**: Toast
- **label**: 증강 요청 등록됨 / 증강 요청 실패

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 증강 AI 를 선택한 상태에서 생성 조건 항목 중 하나라도 미선택·자유 지시문 길이 초과 중 하나라도 해당해 처리 요청 버튼이 비활성일 때만 노출 — 무엇이 모자란지 알려 버튼이 왜 안 눌리는지 찾지 못하는 상황을 막는다(RESOLUTION 종류에는 해당 없음). 드롭다운은 사용자가 건드리지 않으면 필드 오류가 뜰 계기가 없고 제출 버튼도 비활성이라 클릭 핸들러가 돌지 않으므로, 이 문구가 사유를 알려 주는 유일한 자리다.
- **type**: Text
- **label**: 요청 불가 사유 안내 문구

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 화면 하단 고정 바. 선택 요약(종류 × 영상 #N — 곱연산 아님). 취소(navigate(-1)) / 처리 요청 버튼. canSubmit = 종류 1개 선택 && 영상 1건 선택 && (RESOLUTION 종류면 preset 필수, 증강 AI 면 생성 조건 다섯 항목이 모두 선택돼 있으며 자유 지시문이 길이 제한을 지킬 것) && !isPendingAny. 증강 AI 를 고른 상태에서 생성 조건·자유 지시문 중 하나라도 미충족이면 선택 요약 아래에 버튼 비활성 사유를 안내하는 문구를 함께 노출한다(아래 경고 문구 컴포넌트). 제출 분기: 증강 종류(단일값 AUGMENT)는 POST /v1/augments/request 위탁 요청 → 성공 시 결과화면 이동(이동 대상은 요청 본문의 videoIds[0]); RESOLUTION 은 POST /v1/videos/{rawSn}/resolution 저작도구 직접 수행 → 성공 시 변환 결과 inline 표시. 성공/실패 토스트.

**references_apis**:

- API-060
- API-092

**references_features**:

- FEAT-004

## brownfield

### status

modified

### decided_by

ADR-004

### change_kind

- scope-shrink

### diff_summary

1차 증강·내보내기 → 2차 증강만(FEAT-004)

## surface_kind

web

## consumes_apis

- API-042
- API-059
- API-060
- API-092
- API-179

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

- MOD-013

### records

- IMPREC-209

### progress

100

### subtasks

_(empty)_

### last_updated

2026-09-02T10:06:58.563Z

### module_paths

_(empty)_

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-022/main.html
- **label**: 증강 요청 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 89d485edcec31937326718d971c234c131635f1112fff1d59fa5ee6f4047bb93
- **generated_at**: 2026-09-02T10:10:35.993Z
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

- UC-001
- UC-003

## covered_by_acceptances

_(empty)_
