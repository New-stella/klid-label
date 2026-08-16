---
logicraft_item: SCREEN-006
type: screen_spec
version: 41
last_updated_at: 2026-08-16T12:43:59.112Z
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:18:44.966Z
sync_session: 14
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-006.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-047]]", "[[API-043]]", "[[API-091]]", "[[API-114]]", "[[API-084]]"]
  required_roles: ["[[ROLE-001]]", "[[ROLE-002]]"]
  realizes_use_cases: ["[[UC-019]]"]
---

# 마킹 화면

## route

/marking/:rawSn

## title

마킹 화면

## device

desktop

## status

draft

## purpose

영상 스트리밍(서명 URL 선행 조회 후 HTTP Range) + 배속(0.25x~4x) + 키보드 단축키(1=수동/2=자동 모드 전환은 항상 발화, Space=마킹/Del·Backspace=삭제/Enter=완료는 수동 모드에서만 발화)로 이벤트 시점을 마킹. 스트리밍은 항상 비식별 영상을 서빙하며 비식별 미완료 시 NOT_FOUND 로 원본 노출을 차단한다. 이벤트명은 입력받지 않고 마킹 대상 영상의 이벤트 유형(LS_DATA_RAW.EVNT_TYPE_CD)에서 자동 소싱해 화면에 표시한다. VLM 위탁 요청의 event_type 은 이와 다른 컬럼인 관제 인입값(LS_DATA_INGEST.VRFC_EVNT_TYPE_CD)에서 별도로 조달하며, 두 컬럼은 서로 다른 값이라 대체·통합하거나 한쪽에서 유도하지 않는다. 마킹 위치(프레임 지점)는 프레임 추출 기준으로 유지. durationSec 미상 시 ffprobe 등 3단 폴백(쓰기 트랜잭션 밖에서 실행)으로 산출하며, 자동 마킹은 durationSec 확보 실패 시 거부된다.

[비식별 누락 신고 — 마킹 단계 진입점] rawSn 기준으로 POST /v1/videos/{rawSn}/deident-report 를 접수한다. ★접수 조건은 배치 단계가 MARKING_READY 일 때뿐이며 그 외에는 412 다 — 그 상태는 선두 비식별 성공 직후·마킹 이전이라 프레임 행도 라벨도 아직 없어 재마킹이 파괴할 작업 결과가 없기 때문이다. 파생영상(증강·해상도 변환본)은 신고 체계 밖이라 아예 접수하지 않는다(412). ★신고해도 라벨과 개인정보 3필드 판정은 모두 보존된다. 신고 즉시 작업락이 걸리고 DE_IDNTF_YN='F' 로 전이되어 스트리밍·라벨 조회·저장이 차단되므로 마킹을 계속할 수 없다. 자동 재비식별 큐는 없으며 외부 솔루션으로 수동 비식별화한 뒤 POST /v1/deident-reports/{rprtSn}/resolve(WORKER 본인 배정 / REVIEWER 전체)로 해소하면, 마킹 단계 신고는 배치 단계가 MARKING_READY 로 되감기고 활성 마킹이 SKIPPED 로 종결돼 재마킹할 수 있게 된다.

접근: REVIEWER/WORKER.

## sections

### 영상 플레이어 (스트리밍·배속)

- **role**: hero
- **layout**: stack

**components**:

#### [1]

- **note**: GET /v1/videos/{rawSn}/stream-url 로 단기 서명 URL 을 먼저 발급받아 <video src> 에 바인딩(HTTP Range 재생). <video> 엘리먼트는 Authorization 헤더를 붙일 수 없어 인증 스트림(/api/v1/videos/{rawSn}/stream)을 직접 재생하지 못한다. 서명 만료 등 재생 오류 시 1회 한해 stream-url 재발급 후 재시도.
- **type**: Custom
- **label**: 영상 플레이어

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: VideoPlayer

#### [2]

- **type**: Button
- **label**: 재생 / 일시정지

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [3]

- **type**: Custom
- **label**: 배속 0.25x~4x

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: SpeedButtons

#### [4]

- **type**: Custom
- **label**: 탐색 슬라이더(range)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: SeekBar

#### [5]

- **type**: Heading
- **label**: 현재시각 / 총길이 (mm:ss)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: video 의 waiting·seeking 이벤트에서 노출되고 canplay·playing·seeked 시 해제된다. 느린 네트워크에서 재생이 멈춘 이유를 시각적으로 안내한다.
- **type**: Custom
- **label**: 버퍼링/탐색 중 스피너

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Spinner

- **description**: VideoPlayer — 먼저 GET /v1/videos/{rawSn}/stream-url(서명 URL 발급)을 조회하고, 응답의 서명된 URL을 <video src>에 바인딩해 HTTP Range 로 재생한다(<video> 태그는 Authorization 헤더를 붙일 수 없어 인증이 걸린 /stream 을 직접 재생할 수 없다 — 별도 fetch 없는 직접 src 바인딩이 아니다). 서명 만료 등 재생 오류 시 1회 한해 stream-url 을 재발급받아 재시도한다. 재생/일시정지 토글, 배속 버튼(0.25/0.5/1/1.5/2/4x), seek range bar, 현재시각/총길이(mm:ss) 표시. 현재 재생 시각·현재 프레임(영상 실측 fps 기준 환산, 미상 시 30 폴백)·특정 지점 이동 기능을 다른 컴포넌트에 노출한다.

**references_apis**:

- API-114
- API-084

**references_features**:

_(empty)_

### 마킹 타임라인

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Custom
- **label**: 타임라인 마킹 막대

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: localMarks
- **custom_name**: MarkingTimeline

- **description**: MarkingTimeline — 로컬 마킹(localMarks)을 영상 길이(durationSec, 영상 실측 fps) 대비 비율로 막대 표시. 막대 클릭 시 해당 마킹을 선택한다(선택 시 파란 강조). frameIndex/총프레임 비율로 left% 위치.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 마킹 툴바 (모드·완료·비식별 신고)

- **role**: navigation
- **layout**: form

**components**:

#### [1]

- **note**: 탭 라벨에 단축키 1(수동)/2(자동) 시각 표시(Kbd) — 전역 keydown 에서 항상 발화(입력 필드 포커스 시 제외), 수동 모드로 스코프 제한되지 않는다.
- **type**: Custom
- **label**: 자동 / 수동 모드

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ModeToggle

#### [2]

- **note**: AUTO 모드만. 1 이상 정수, 상한 없음(하한만 검증). 기본값 300
- **type**: Input
- **label**: 간격(프레임)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: intervalFrames
- **placeholder**: 300

#### [3]

- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [4]

- **note**: 항상 클릭 가능(비활성 상태 없음). MANUAL 모드에서 마크 0건 상태로 클릭하면 토스트 경고('재생하며 마킹을 1건 이상 쌓아 주세요.')를 띄우고 제출만 막는다. AUTO 모드는 간격 값(1 이상)만 있으면 되며 이 검사 대상이 아니다 — 마크 건수로 제출을 막으면 AUTO 모드 제출 자체가 불가능해진다.
- **type**: Button
- **label**: 마킹 완료

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-047

#### [5]

- **note**: rawSn 기준 신고. 사유 textarea(1~1000자) 입력 후 제출 → 작업락 + deIdntfYn='F'. ★라벨·개인정보 3필드는 보존한다(리셋 없음). 버튼은 videoDetail 기준으로 사전 비활성화 — ①파생영상 ②배치 단계≠MARKING_READY. BE 거부는 둘 다 412. 신고 성공 시 작업 목록(/task)으로 이동한다.
- **type**: Button
- **label**: 비식별 누락 신고

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-091

#### [6]

- **note**: videoDetail.derivative 면 '증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다.' / status≠MARKING_READY 면 '이미 다음 단계로 넘어간 영상이라 이 화면에서는 신고할 수 없습니다. 라벨링 화면에서 신고해 주세요.' 원본으로 유도하지 않고 부모 rawSn 도 노출하지 않는다.
- **type**: Custom
- **label**: 신고 불가 사유 툴팁

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: UnsupportedReasonTooltip

- **description**: MarkingToolbar — 자동/수동 모드 토글(탭에 단축키 1=수동/2=자동 표시, 전역에서 항상 발화). 이벤트명은 화면에서 입력받지 않고 마킹 대상 영상의 이벤트 유형(LS_DATA_RAW.EVNT_TYPE_CD)에서 자동 소싱되어 화면·마킹 응답에 표시된다. VLM 위탁 요청의 event_type 은 이와 별개로 관제 인입값(LS_DATA_INGEST.VRFC_EVNT_TYPE_CD)에서 조달한다. 자동 모드: 간격(프레임) number 입력(1 이상 정수, 상한 없음, 기본값 300). 수동 모드: 단축키 안내(Space=마킹/Del·Backspace=삭제/Enter=완료). Space/Del·Backspace/Enter 세 단축키는 수동 모드에서만 발화한다(1/2 모드 전환은 예외적으로 모드 무관 항상 발화). 현재 마킹 건수 표시, 초기화 버튼, 마킹 완료 버튼(항상 클릭 가능 — 마크 0건이면 MANUAL 모드에서 토스트 경고로 제출을 막는다. AUTO 모드는 이 검사 대상이 아니다). '비식별 누락 신고' 버튼은 rawSn 기준으로 POST /v1/videos/{rawSn}/deident-report(API-091) 호출 — 신고 접수 시 영상이 즉시 잠기고 라벨 조회·스트리밍이 차단되므로 마킹 작업을 계속할 수 없다.

**references_apis**:

- API-047
- API-091

**references_features**:

_(empty)_

### 현재 마킹 칩 목록

- **role**: side
- **layout**: list

**components**:

#### [1]

- **type**: Custom
- **label**: 마킹 칩 (F{frame} (mm:ss))

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: localMarks
- **custom_name**: MarkChip

- **description**: 제출 전 로컬 마킹(localMarks) 칩 목록. 각 칩은 'F{frameIndex} ({timestamp})' 표시, 클릭 시 해당 마킹을 선택한다. 선택된 칩은 파란 강조. 마킹이 0건이면 빈 상태 안내 문구를 노출한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 배치단계 인디케이터

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 오토라벨 3단계를 한 칸으로 접어 5칸 표시, DEIDENTIFY 선두
- **type**: Custom
- **label**: 배치단계 인디케이터

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: BatchStageIndicator
- **triggers_api**: API-043

#### [2]

- **type**: Progress
- **label**: 단계 진행률

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 마킹 화면에 배치 파이프라인 진행 상태를 표시하는 BatchStageIndicator. GET /v1/videos/{rawSn}(API-043) 응답 stages[](비식별/마킹/시계열/프레임추출/AI탐지/AI분할/보간, DEIDENTIFY 선두) 기반으로 현재 단계·상태·진행률을 표시. 마킹 중 잔여 배치 단계 진행을 확인. 표시는 응답의 7단계를 그대로 나열하지 않고 오토라벨 세 단계(AI 탐지 · AI 분할 · 트랙 보간)를 한 칸으로 접어 5칸으로 보여주며, 접은 칸에는 진행 중이거나 실패한 세부 단계를 보조 표기로 병기한다. 영상 상세 화면과 같은 표시기를 쓰므로 두 화면의 표시 단위가 갈리지 않는다.

**references_apis**:

- API-043

**references_features**:

_(empty)_

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

2차 신규 마킹 화면 — 비식별 영상 재생·배속과 자동/수동 이벤트 시점 마킹, 마킹 단계 비식별 누락 신고 진입점. 1차 화면 카탈로그에 대응 화면이 없다.

## surface_kind

web

## consumes_apis

- API-047
- API-043
- API-091
- API-114
- API-084

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
- ROLE-002

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-006/main.html
- **label**: 마킹 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: 506a22da2c2f73283b3015bbed68542c5bd14239b712d30a37f45799d31a647a
- **generated_at**: 2026-08-16T12:43:59.112Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-019

## covered_by_acceptances

_(empty)_
