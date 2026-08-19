---
logicraft_item: SCREEN-009
type: screen_spec
version: 46
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-19T13:52:30.200Z
status: CHANGED
prev_version: 44
content_hash: ef3bf73bbb9d846eb775b952512d7889dbdc7653b134c9e7f60680a68bcb612e
stale: true
raw: ./_raw/SCREEN-009.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
  consumes: ["[[API-021]]", "[[API-043]]", "[[API-044]]"]
  migrated_from: ["[[LEGACY-003]]"]
  references: ["[[API-021]]", "[[API-043]]", "[[API-044]]", "[[API-167]]", "[[API-198]]", "[[API-200]]", "[[API-201]]"]
  requires: ["[[ROLE-001]]", "[[ROLE-002]]"]
  applies_to_backward: ["[[SHELL-001]]"]
  designs_backward: ["[[SD-004]]"]
  granted_on_backward: ["[[ROLE-002]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  realizes_backward: ["[[MOD-003]]"]
  references_backward: ["[[UC-011]]", "[[UC-016]]"]
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

영상 메타와 오토라벨 결과를 조회하는 상세 화면. 기본 정보 탭에서 배치 파이프라인 처리 단계(BatchStageIndicator, video.stages — GET /v1/videos/{id}=API-043 응답)를 표시한다. ★개인정보 유무(분류)는 표시하지 않는다(관제가 그 값을 실제로 보내지 않는다 — 응답 필드 privacyTypeCd 자체는 하위호환으로 존치). 접근: REVIEWER/WORKER.

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

- **note**: CCTV ID/해상도/길이/녹화시각/처리단계/생성일/수정일 — '개인정보 분류' 항목은 제외한다
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

- **note**: 배치가 실패했거나 건너뛴 묶음이 있으면 노출한다. 사유는 서버가 준 문구를 그대로 보여주고 화면이 해석하지 않으며, 단계를 특정할 수 없는 실패도 사유만은 표시한다. 건너뛰기·되돌리기·재수행은 시계열과 오토라벨 두 묶음 단위이며 건너뛰기는 사유를 받는다. 되돌린 묶음에는 재수행 버튼을 하나 둔다 — 범위를 고르지 않는다(묶음이 곧 범위다). 오토라벨 재수행은 트랙 보간까지 다시 만들어 사람이 손댄 보간 라벨이 새로 계산된 값으로 바뀌므로 고르는 시점에 알린다. 전체 재기동은 실패한 영상에만 노출한다. 파생영상에는 이 영역의 조작을 노출하지 않는다.
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

- **note**: 검수자와 라벨링 작업자가 함께 본다. 정렬은 서버가 정하고 화면은 다시 정렬하지 않는다. 알 수 없는 처리 상태 값은 코드를 그대로 드러내지 않고 진행 중으로 표시한다. 파일 경로는 표시하지 않는다 — 개인정보가 있는 자산의 위치를 특정하는 정보다. 이력이 없으면 '비식별 이력이 없습니다.' 안내를 보여준다.
- **type**: Custom
- **label**: 비식별 이력

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: video.deidentHistory
- **custom_name**: DeidentHistoryPanel

- **description**: 영상 메타 2열 그리드 — CCTV ID, 해상도, 길이, 녹화 시각, 처리 단계, 생성일/수정일. 값은 영상 상세 응답(API-043)에서 파생. ★'개인정보 분류'는 표시하지 않는다 — 관제가 그 값을 실제로 보내지 않아, 화면이 보던 것은 관제값이 아니라 적재 시 고정되는 레거시 컬럼이었다. 바뀐 것은 노출뿐이며 그 컬럼과 응답 필드는 하위호환으로 존치되고 비식별 대상 판정에 계속 쓰인다. 라벨링 화면의 개인정보 메타 패널은 사람이 입력하는 별개 축이다. ★배치가 실패했거나 건너뛴 묶음이 있으면 이 영역이 보인다. 사유는 서버가 사용자 문구로 변환한 값이며 내부 원문은 나오지 않고, 단계를 특정할 수 없는 실패는 단계가 비고 사유만 표시된다. ★조작 단위는 개별 단계가 아니라 작업 묶음이다 — 시계열과 오토라벨(AI 탐지 · AI 분할 · 트랙 보간)이며, 오토라벨은 쪼개서 부분 수행하지 않는다. 뒤 작업이 앞 결과를 이어받고 보간이 그 산출물을 재계산하므로 일부만 수행하면 산출물끼리 어긋나고, 보간을 묶음 밖에 두면 어떤 재수행에서도 보간이 무조건 돌아 사람이 손댄 라벨을 지운다. ★전체 재기동은 실패한 영상에만 노출한다 — 완주 영상에 쓰면 파이프라인 전체가 돌아 같은 파괴가 일어난다. 문제가 생긴 곳부터 재시도한다. 재기동·재수행은 접수까지만 즉시 확인되고 파이프라인은 뒤에서 이어 돈다. ★비식별 이력은 이 탭 맨 아래, 배치 실패 사유·조치 다음에 둔다. 검수자와 라벨링 작업자가 함께 본다 — 바로 위 영역이 검수자 전용인 것과 다르다. 한 항목이 위탁 1회차라 최초 비식별과 검수 완료 후 재비식별이 각각 한 항목으로 쌓인다. 요청 종류·처리 상태·요청 일시는 항상 보이고, 얼굴·번호판 검출 수와 총 프레임 수, 외부 처리 시작~종료 일시는 그 값을 받은 회차에만 보인다. ★집계를 받지 못한 회차는 그 줄을 감춘다 — 0 으로 채우면 0 건이 검출된 회차와 구분되지 않는다. 최신 회차가 위이며 최신 20건까지 내려온다.

**references_apis**:

- API-043
- API-167
- API-198
- API-200
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

- **description**: framePreviews 썸네일 그리드(3~6열). 각 프레임은 AuthImage(srcSn 기반 blob 이미지), 이슈 있으면 빨간 점 표시, 하단 #frameNo 라벨. 클릭 시 라이트박스 Modal 오픈 — 확대 이미지 + 프레임 번호/타임스탬프/이슈 표시. 프레임 없으면 빈 상태 메시지.

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

- **description**: 프레임 썸네일 클릭 시 열리는 확대 보기 Modal(size xl). 확대 이미지(AuthImage), 프레임 #/타임스탬프(ms)/이슈 여부를 표시하고, 푸터는 '닫기' 단일 버튼이다.

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

- **description**: useVideoLabels(API-044)로 오토라벨 결과 조회. 처리 정보 카드(총 라벨 수/오토라벨 수/오토라벨 비율/처리 상태), 신뢰도 분포 막대(0.9+ / 0.7~0.9 / <0.7, 오토라벨 기준 집계), 라벨별 분포 바(상위 10종, count 내림차순). 로딩 스켈레톤·에러·빈 상태 분기.

**references_apis**:

- API-044

**references_features**:

_(empty)_

## brownfield

### status

modified

### decided_by

ADR-001

### change_kind

- scope-shrink

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

## implementation

### status

implemented

### modules

_(empty)_

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

## required_roles

- ROLE-001
- ROLE-002

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
- **source_hash**: fdbd61f698ca622cdeba32a2547db500fc11b223af4506b29a471bde9e99e4d8
- **generated_at**: 2026-08-18T03:19:10.298Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

_(empty)_

## covered_by_acceptances

_(empty)_
