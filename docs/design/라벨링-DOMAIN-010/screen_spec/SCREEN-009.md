---
logicraft_item: SCREEN-009
type: screen_spec
version: 80
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-17T01:12:38.290Z
status: CHANGED
prev_version: 78
content_hash: b11c1a2659bef1f324d98c9adae62f898058603d7849c6f0f292b565f59e457f
stale: true
raw: ./_raw/SCREEN-009.json
links:
  based_on: ["[[ADR-001]]"]
  belongs_to_domain: ["[[DOMAIN-003]]"]
  consumes: ["[[API-021]]", "[[API-043]]", "[[API-044]]", "[[API-167]]", "[[API-198]]", "[[API-201]]"]
  covered_by: ["[[AC-1022]]", "[[AC-1023]]"]
  implements: ["[[IMPREC-038]]", "[[IMPREC-040]]", "[[IMPREC-053]]", "[[IMPREC-131]]", "[[IMPREC-148]]", "[[IMPREC-150]]"]
  migrated_from: ["[[LEGACY-003]]"]
  references: ["[[API-021]]", "[[API-043]]", "[[API-044]]", "[[API-167]]", "[[API-198]]", "[[API-201]]"]
  requires: ["[[ROLE-001]]"]
  applies_to_backward: ["[[SHELL-001]]"]
  designs_backward: ["[[SD-004]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  realizes_backward: ["[[MOD-003]]"]
  references_backward: ["[[AC-1133]]", "[[TEST-001]]", "[[UC-011]]", "[[UC-016]]"]
---

# 영상 상세 화면

## route

/video/:id

## title

영상 상세 화면

## device

desktop

## status

draft

## purpose

영상 메타와 오토라벨 결과를 조회하는 상세 화면. 기본 정보 탭에서 배치 파이프라인 처리 단계(BatchStageIndicator, video.stages — GET /v1/videos/{id}=API-043 응답)를 표시한다. ★개인정보 유무(분류)는 표시하지 않는다(관제가 그 값을 실제로 보내지 않는다 — 응답 필드 privacyTypeCd 자체는 하위호환으로 존치). 접근: REVIEWER 전용. 이 화면은 배치 처리 상태 확인에 더해 재시도·건너뛰기·재수행 같은 운영 조치를 제공하는 자리다 — 파이프라인을 다시 돌리거나 단계를 건너뛰게 하는 것은 운영 행위이지, 라벨 수정과 검수 제출을 맡는 작업자의 역할 축이 아니다. 이 화면의 진입 경로인 영상 처리 현황(SCREEN-008)도 같은 축으로 검수자 전용이다.

## sections

### 영상 헤더 카드

- **role**: hero
- **layout**: detail

**components**:

#### [1]

- **note**: navigate(-1)
- **type**: Button
- **label**: 뒤로가기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [2]

- **type**: Custom
- **label**: 썸네일

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: framePreviews[0].srcSn
- **custom_name**: AuthImage
- **triggers_api**: API-021

#### [3]

- **type**: Heading
- **label**: CCTV명

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.cctvName

#### [4]

- **type**: Badge
- **label**: 이벤트 유형 배지

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.eventTypeCd

#### [5]

- **type**: Badge
- **label**: 상태 배지

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.status

- **description**: 뒤로가기 버튼 + 헤더 카드. 썸네일(첫 프레임 이미지, 없으면 '미리보기 없음' 자리표시), CCTV명, 이벤트 유형 배지와 상태 배지, 길이·녹화일 메타로 구성한다. 썸네일은 인증 헤더를 실어 받아 표시한다.

**references_apis**:

- API-043
- API-021

**references_features**:

_(empty)_

### 탭 네비게이션

- **role**: navigation
- **layout**: tabs

**components**:

#### [1]

- **type**: Tabs
- **label**: 상세 탭

**columns**:

_(empty)_

**options**:

- 기본 정보
- 프레임 미리보기
- 오토라벨 결과

- **description**: 3개 탭 전환 — 기본 정보 / 프레임 미리보기 / 오토라벨 결과. 로컬 state(activeTab) 기반 클라이언트 전환.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 기본 정보 탭

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **note**: CCTV ID/해상도/길이/녹화시각/처리단계/생성일/수정일 — '개인정보 분류' 항목은 제외한다. CCTV ID 는 영상이 보유한 실제 CCTV 식별자를 그대로 표시하고 일련번호로 문자열을 조립하지 않는다 — 식별자가 없으면 조립값으로 대체하지 말고 빈 표시로 둔다(없는 식별자를 지어내면 그것이 실값처럼 보인다). 해상도는 영상 상세 응답(API-043)의 resolution 을 그대로 표시하고 화면이 가로·세로로 재조립하지 않으며, 값이 없으면 빈 표시다
- **type**: KeyValue
- **label**: 메타 정보 그리드

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video

#### [2]

- **note**: 오토라벨 세 단계(AI 탐지 · AI 분할 · 트랙 보간)는 한 칸으로 접어 표시하고 그 칸에 진행 중이거나 실패한 세부 단계를 보조 표기로 병기한다 — 표시 단위를 아래 조작 단위와 맞춘다. 실패한 단계는 FAIL 로 표시한다. 이 표시기는 마킹 화면과 공유하므로 재실행·스킵 같은 조작 버튼을 표시기 안에 두지 않는다 — 넣으면 마킹 화면에도 함께 나타난다. 조작은 아래 별도 영역이 담당한다.
- **type**: Custom
- **label**: 처리 단계 표시

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.stages
- **custom_name**: BatchStageIndicator

#### [3]

- **note**: 판정 축은 재수행을 눌렀는가가 아니라 그 묶음의 조치가 끝났는가다 — 최초 처리로 만든 산출물과 재수행으로 만든 산출물은 구분하지 않는다. 과거 이력은 감사 기록에 그대로 남는다. 건너뛰기는 사유를 받고 서버가 내려주는 실패한 묶음에만 노출한다. 건너뛴 적이 있는 묶음에 재수행 버튼을 하나 두고 해제 버튼은 두지 않는다 — 범위는 고르지 않는다(묶음이 곧 범위다). 오토라벨 재수행은 사람이 손댄 보간 라벨을 새로 계산된 값으로 바꾸므로 고르는 시점에 알린다. 상태(건너뜀·해제됨·실패)는 공용 배지(UI-111, 실패는 error variant)가, 조작은 버튼이 맡는 서로 다른 요소라 같은 형태로 그리지 않는다. ★검수가 완료된 적 있는 영상은 두 묶음이 갈린다 — 시계열 재수행은 그대로 누르고 오토라벨 재수행은 비활성 + 사유 툴팁이다(라벨을 다시 만들어 승인 시점 스냅샷과 어긋난다) — 되돌릴 수 없어 미리 알린다. 파생영상에는 이 영역의 조작을 노출하지 않는다.
- **type**: Custom
- **label**: 배치 실패 사유 + 조치 (REVIEWER 전용)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.batchFailureReason
- **custom_name**: BatchFailurePanel
- **triggers_api**: API-167

#### [4]

- **note**: 정렬은 서버가 정하고 화면은 다시 정렬하지 않는다. 알 수 없는 처리 상태 값은 코드를 그대로 드러내지 않고 진행 중으로 표시한다. 파일 경로는 표시하지 않는다 — 개인정보가 있는 자산의 위치를 특정하는 정보다. 이력이 없으면 '비식별 이력이 없습니다.' 안내를 보여준다. 한 항목이 위탁 1회차라 최초 비식별과 검수 완료 후 재비식별이 각각 한 항목으로 쌓인다. 요청 종류·처리 상태·요청 일시는 항상 보이고, 얼굴·번호판 검출 수와 총 프레임 수, 외부 처리 시작~종료 일시는 그 값을 받은 회차에만 보인다. ★집계를 받지 못한 회차는 그 줄을 감춘다 — 0 으로 채우면 0 건이 검출된 회차와 구분되지 않는다. 최신 회차가 위이며 최신 20건까지 내려온다.
- **type**: Custom
- **label**: 비식별 이력

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.deidentHistory
- **custom_name**: DeidentHistoryPanel

#### [5]

- **note**: 오토라벨 묶음에만 둔다. 확인 창이 뜨기 전, 재수행을 고르기 전부터 패널에 보인다 — 되돌릴 수 없는 조작임을 고르는 시점에 알리기 위해서다. 확인 창 안의 경고 상자와는 별개 요소이며 둘 다 둔다. 시계열 묶음에는 두지 않는다.
- **type**: Alert
- **label**: 오토라벨 재수행 사전 경고

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 배치 실패 패널 안의 재기동 버튼이다. 선두 비식별 단계가 실패한 영상에도 같은 자리에 나타나며, 그 영상에서는 적재 직후와 같은 선두 비식별 단계를 다시 수행하고 성공하면 마킹 준비 상태가 된다(API-167). 비식별을 통과하지 못한 영상이므로 마킹 이후 단계만 다시 도는 것으로 안내하지 않는다. 접수 응답의 단계 값이 PENDING 이어도 PROCESSING 과 같은 정상 접수 안내를 보이고 오류로 다루지 않는다. 거부되면(승인 이력 있음 · 열린 비식별 누락 신고 있음 · 진행 중인 비식별 위탁 있음 · 같은 영상 요청이 이미 수락됨 등) 서버가 내려준 사유 문구를 그대로 표시한다. 검수자에게만 보이며 파생영상에는 노출하지 않는다.
- **type**: Button
- **label**: 재기동 (배치 실패 패널, REVIEWER 전용)

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-167

- **description**: 영상 메타 2열 그리드 — CCTV ID, 해상도, 길이, 녹화 시각, 처리 단계, 생성일/수정일. 값은 영상 상세 응답(API-043)에서 파생. ★'개인정보 분류'는 표시하지 않는다 — 관제가 그 값을 실제로 보내지 않아, 화면이 보던 것은 관제값이 아니라 적재 시 고정되는 레거시 컬럼이었다. 바뀐 것은 노출뿐이며 그 컬럼과 응답 필드는 하위호환으로 존치되고 비식별 대상 판정에 계속 쓰인다. 라벨링 화면의 개인정보 메타 패널은 사람이 입력하는 별개 축이다. ★배치가 실패했거나 지금 조치가 필요한 작업 묶음이 있으면 이 영역이 보인다. 조치가 끝난 묶음은 제외하며, 판정은 산출물 보유(시계열은 시계열 메타, 오토라벨은 자동 생성 라벨)나 오토라벨의 해제 뒤 배치 완주(실패 제외)다. 제목은 상태별로 갈린다 — 건너뛴 묶음이 있으면 '건너뛴 작업 있음'이 우선하고, 해제된 묶음만 있으면 '건너뛰기 해제됨'이다. 사유는 서버가 사용자 문구로 변환한 값이며 내부 원문은 나오지 않고, 단계를 특정할 수 없는 실패는 단계가 비고 사유만 표시된다. ★조작 단위는 개별 단계가 아니라 작업 묶음이다 — 시계열과 오토라벨(AI 탐지 · AI 분할 · 트랙 보간)이며, 오토라벨은 쪼개서 부분 수행하지 않는다. 뒤 작업이 앞 결과를 이어받고 보간이 그 산출물을 재계산하므로 일부만 수행하면 산출물끼리 어긋나고, 보간을 묶음 밖에 두면 어떤 재수행에서도 보간이 무조건 돌아 사람이 손댄 라벨을 지운다. ★전체 재기동은 실패한 영상에만 노출한다 — 완주 영상에 쓰면 파이프라인 전체가 돌아 같은 파괴가 일어난다. 문제가 생긴 곳부터 재시도한다. ★선두 비식별 단계 실패도 배치 실패로 보아 이 영역과 재기동 버튼을 보인다. 재기동·재수행은 접수까지만 즉시 확인되고 파이프라인은 뒤에서 이어 돈다. 재수행은 두 묶음 모두 확인 창을 거치되 확인의 층은 위험도에 따라 갈린다. ★비식별 이력은 이 탭 맨 아래, 배치 실패 사유·조치 다음에 둔다.

**references_apis**:

- API-043
- API-167
- API-198
- API-201

**references_features**:

_(empty)_

### 프레임 미리보기 탭

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **type**: Custom
- **label**: 프레임 썸네일

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: framePreviews[].srcSn
- **custom_name**: AuthImage
- **triggers_api**: API-021

#### [2]

- **note**: 라이트박스 오픈
- **type**: Button
- **label**: 프레임 상세 보기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

- **description**: framePreviews 썸네일 그리드(3~6열). 각 프레임은 AuthImage(srcSn 기반 blob 이미지), 이슈 있으면 빨간 점 표시, 하단 #frameNo 라벨. 클릭 시 라이트박스 Modal 오픈 — 확대 이미지 + 프레임 번호/타임스탬프/이슈 표시. 프레임 없으면 빈 상태 메시지. 프레임의 영상 내 시각을 알 수 없으면 그 자리에 값 없음 표기 '-' 를 적고 단위를 붙이지 않는다 — 알 수 없음과 영상 맨 앞을 화면에서 구분해야 하기 때문이다. 시각이 0인 프레임은 알 수 없는 것이 아니라 실제 값이므로 그대로 시각으로 적는다.

**references_apis**:

- API-021

**references_features**:

_(empty)_

### 프레임 라이트박스 모달

- **role**: modal
- **layout**: detail

**components**:

#### [1]

- **note**: Modal size=xl
- **type**: Dialog
- **label**: 프레임 확대 모달

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Custom
- **label**: 확대 프레임 이미지

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: lightboxFrame.srcSn
- **custom_name**: AuthImage
- **triggers_api**: API-021

#### [3]

- **type**: Button
- **label**: 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 프레임 썸네일 클릭 시 열리는 확대 보기 Modal(size xl). 확대 이미지(AuthImage), 프레임 #/타임스탬프(hh:mm:ss.SSS — 시·분·초는 두 자리, 밀리초는 세 자리로 0 을 채운다)/이슈 여부를 표시하고, 푸터는 '닫기' 단일 버튼이다. 프레임의 영상 내 시각을 알 수 없으면 그 줄을 숨기지 않고 값 없음 표기 '-' 를 적으며 단위를 붙이지 않는다 — 프레임 미리보기 탭과 같은 표기를 써서 같은 프레임이 자리에 따라 다르게 읽히지 않게 한다. 시각이 0인 프레임은 알 수 없는 것이 아니라 실제 값이므로 그대로 시각으로 적는다.

**references_apis**:

- API-021

**references_features**:

_(empty)_

### 오토라벨 결과 탭

- **role**: main
- **layout**: dashboard

**components**:

#### [1]

- **type**: Stat
- **label**: 처리 정보(총 라벨/오토라벨/비율/상태 4종)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Chart
- **label**: 신뢰도 분포 막대

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: labels.confidence

#### [3]

- **type**: Chart
- **label**: 라벨별 분포 바

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: labels.labelCode

#### [4]

- **note**: 오토라벨 없음 / 불러오기 실패 메시지
- **type**: Alert
- **label**: 빈/에러 상태

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: API-044 로 오토라벨 결과를 조회한다. 처리 정보 카드(총 라벨 수/오토라벨 수/오토라벨 비율/처리 상태), 신뢰도 분포 막대(0.9+ / 0.7~0.9 / <0.7, 오토라벨 기준 집계), 라벨별 분포 바(상위 10종, count 내림차순). 라벨 표시명은 응답이 내려주는 값을 그대로 쓴다 — 화면이 다시 해석하거나 다른 말로 바꾸지 않는다. 표시명의 단일 진실원은 라벨 마스터이므로, 화면이 자기 사전으로 치환하면 마스터와 어긋나는 두 번째 진실원이 된다. 색상은 응답에 실려 오지 않을 수 있으며 그것이 정상 경로다 — 색이 없다고 항목을 비우거나 오류로 다루지 않는다. 라벨별 분포 막대는 라벨마다 다른 색으로 그린다 — 색은 라벨 마스터에 운영자가 등록한 값이며, 화면이 한 색으로 덮으면 라벨 표시 색상의 단일 진실원이 라벨 마스터라는 확정 정책을 이 화면에서만 어기게 된다. 이 색은 화면이 배정하는 범주 구분색의 대상이 아니다 — 사람이 고른 값이기 때문이다. 로딩 스켈레톤·에러·빈 상태 분기.

**references_apis**:

- API-044

**references_features**:

_(empty)_

### 재수행 확인 창

- **role**: modal
- **layout**: stack

**components**:

#### [1]

- **note**: 경고 상자 없이 확인 문구만 두고 확정 버튼은 주 버튼 톤이다. 시계열 재수행은 사람이 손댄 값을 덮지 않아 되돌릴 수 없다는 근거에 해당하지 않기 때문이다. 검수가 완료된 적 있는 영상에서도 이 창에 이른다 — 그 영상에서 막히는 것은 오토라벨 묶음 쪽이다.
- **type**: Dialog
- **label**: 시계열 묶음 재수행 확인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 확인 창 안에 경고 상자를 두고 확정 버튼은 위험 톤이다. 오토라벨 재수행은 사람이 손댄 보간 라벨을 새로 계산된 값으로 덮어 되돌릴 수 없기 때문이다. 검수가 완료된 적 있는 영상은 이 창에 이르지 못한다 — 그 영상에서는 재수행 버튼 자체가 비활성이고 사유를 툴팁으로 알린다.
- **type**: Dialog
- **label**: 오토라벨 묶음 재수행 확인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 오토라벨 묶음의 확인 창 안에만 둔다. 시계열 묶음의 확인 창에는 두지 않는다 — 그 묶음은 사람이 손댄 값을 덮지 않는다.
- **type**: Alert
- **label**: 되돌릴 수 없음 경고 상자

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 배치 조치 패널에서 재수행을 고르면 뜨는 확인 창이다. 두 작업 묶음(시계열·오토라벨) 모두 이 창을 거치며, 확인 없이 곧바로 접수되는 재수행은 없다. 다만 두 창은 같은 모양이 아니다 — 창을 두는 근거와 경고 상자를 두는 근거가 서로 다르기 때문이다. ★확인 창을 두는 근거는 재수행이 공짜가 아니라는 것이다. 재수행은 외부 분석 서비스로 다시 위탁을 보내는 행위라 시간이 들고 동시 처리 한도를 먹는다. 두 묶음 모두 이 근거에 해당한다. ★경고 상자를 두는 근거는 되돌릴 수 없다는 것이다. 오토라벨 재수행은 사람이 손댄 보간 라벨을 새로 계산된 값으로 덮는다. 시계열 묶음은 이 근거에 해당하지 않는다. ⇒ 확인이 균일해져 무뎌지는 것이 아니라 위험도에 따라 층이 갈린다. 일관성을 이유로 두 창을 같은 모양으로 만들면 한쪽이 틀리게 된다.

**references_apis**:

- API-167

**references_features**:

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-001

### change_kind

- scope-shrink
- role-change

### diff_summary

영상 단위 상세

### legacy_source

#### type

module

#### legacy_artifact_id

LEGACY-003

## surface_kind

web

## consumes_apis

- API-021
- API-043
- API-044
- API-167
- API-198
- API-201

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

- MOD-003

### records

- IMPREC-038
- IMPREC-040
- IMPREC-053
- IMPREC-131
- IMPREC-148
- IMPREC-150
- IMPREC-496

### progress

100

### subtasks

#### [1]

- **done**: true
- **description**: 프레임 라이트박스 모달의 재생 시점을 확정 시안 표기 hh:mm:ss.SSS(00:00:00.960)로 통일한다 — 구 표기는 원시 밀리초(960 ms)라 긴 영상에서 영상 내 위치를 읽을 수 없었다

#### [2]

- **done**: true
- **description**: 썸네일 캡션의 mm:ss 표기는 유지하고 두 표기를 합치지 않는다 — 좁은 타일 자리와 정밀 표기는 목적이 다르며, 두 자리가 공유하는 것은 미상 판정 한 곳뿐이다

#### [3]

- **done**: true
- **description**: 미상은 - 로 적고 단위를 붙이지 않으며 시각 0은 실제 값으로 표기한다(미상과 구분)

#### [4]

- **done**: true
- **description**: 시·분·초·밀리 네 칸이 각각 제 자리에 들어가는지 경계 케이스로 고정한다 — 공용 픽스처는 시·분이 모두 0이라 이 축을 검증하지 못한다

#### [5]

- **done**: true
- **description**: 그 재생 시점을 등폭 글꼴로 그린다 — 자릿수가 고정된 값이라 본문체로 그리면 프레임을 넘길 때 숫자가 좌우로 흔들린다. 같은 화면의 길이·해상도 값이 이미 같은 이유로 쓰는 등폭 관례를 그대로 따르고 새 클래스·새 토큰을 만들지 않는다

### last_updated

2026-09-17T01:09:26.319Z

### module_paths

_(empty)_

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-009/main.html
- **label**: 영상 상세 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: af5c0fa70f5e422eea5cb347a7b130a3b33c410c9cc0606f32acef5a36856563
- **generated_at**: 2026-09-17T01:09:22.471Z
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

_(empty)_

## covered_by_acceptances

- AC-1022
- AC-1023
