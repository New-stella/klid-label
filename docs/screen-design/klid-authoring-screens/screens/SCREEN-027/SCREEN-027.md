---
logicraft_item: SCREEN-027
type: screen_spec
version: 16
last_updated_at: 2026-08-13T11:03:46.839Z
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-13T11:06:22.401Z
sync_session: 5
stale: false
status: CHANGED
prev_version: 14
raw: ./_raw/SCREEN-027.json
wireframe: ./wireframe.html
links:
  consumes_apis: [API-043, API-156, API-158, API-160, API-162, API-164]
  required_roles: [ROLE-001]
---

> ⚠️ **버전 변경 감지 — logicraft v14 → v16**
> change_summary: 정적 HTML 와이어프레임 자동 생성 — 1440×auto (17.4KB)
> ↳ 요약/구현 노트 재검토 후 작성된 코드에 반영. 직전 요약은 git diff 확인.

# 오토라벨 테스트 화면 (개발)

## route

/dev/autolabel-test

## title

오토라벨 테스트 화면 (개발)

## device

desktop

## status

draft

## purpose

개발/검수 환경에서 오토라벨링 파이프라인을 시험하기 위한 화면. 영상 파일과 인입 메타데이터를 함께 올리면 비식별 처리 후 마킹 대기 상태로 들어가며, 관제 인입 데이터를 그대로 재현하는 대용량 재개 업로드 기능도 함께 제공한다. 비운영 용도. 접근: 검수자.

## sections

### 파일 + 메타 입력 폼

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: description: 프레임 추출+오토라벨링 파이프라인 실행 (개발/검수 전용)
- **type**: Heading
- **label**: 영상 업로드 (PageHeader)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: type=file, accept=video/mp4,webm,quicktime,x-msvideo + .mp4/.webm/.mov/.avi. 선택 후 파일명·크기(MB) 표시
- **type**: Input
- **label**: 영상 파일
- **state**: default

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: hint: 영문/숫자/-/_ 1~64자
- **type**: Input
- **label**: vmsClipId

**columns**:

_(empty)_

**options**:

_(empty)_

- **placeholder**: test-xxxx

#### [4]

- **note**: hint: 영문/숫자/-/_ 1~64자, 사전 등록 불필요
- **type**: Input
- **label**: cctvId

**columns**:

_(empty)_

**options**:

_(empty)_

- **placeholder**: CCTV-001

#### [5]

- **note**: 이벤트유형마스터에 등록된 코드를 동적으로 조회해 선택 — 형식은 EV+숫자 8자리(예 EV02000101=화재, EV03000101=교통사고, EV05000101=싸움). 고정된 6종 화이트리스트가 아니라 관제가 운영하는 코드 전체가 대상이며, 신규 등록되면 즉시 선택 가능해진다. 여러 코드가 같은 한글 이름으로 해석되는 경우(예 화재가 여러 상세코드로 나뉨) 이름 옆에 코드를 함께 표시해 구분한다.
- **type**: Select
- **label**: eventTypeCd

**columns**:

_(empty)_

**options**:

- EV02000101 (화재)
- EV03000101 (교통사고)
- EV05000101 (싸움)
- … (관제 등록 코드 전체, 동적 로드)

#### [6]

- **note**: hint: 숫자 1~10자리 (기본 11680=강남구), inputMode=numeric
- **type**: Input
- **label**: localGovCd

**columns**:

_(empty)_

**options**:

_(empty)_

- **placeholder**: 11680

#### [7]

- **note**: 선택값은 표시용 메타데이터일 뿐이며 비식별 처리 여부에는 영향을 주지 않는다 — 비식별은 업로드된 모든 영상에 대해 예외 없이 자동 실행된다.
- **type**: RadioGroup
- **label**: prvcTypeCd

**columns**:

_(empty)_

**options**:

- ANONY (비식별 미적용)
- PRVC (개인정보 포함)
- PSDO (가명 정보)

#### [8]

- **note**: type=datetime-local, 제출 시 ISO-8601 UTC로 변환
- **type**: Input
- **label**: capturedAt

**columns**:

_(empty)_

**options**:

_(empty)_

#### [9]

- **note**: 입력 불가 — 서버가 업로드된 파일에서 자동 추출(ffprobe, 1~7200초 검증) 안내 박스
- **type**: Custom
- **label**: 영상 길이 (durationSec)
- **state**: readonly

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DurationPlaceholder

#### [10]

- **note**: role=alert, 서버 메시지 표시(XSS는 자동 이스케이프). 조건부 렌더
- **type**: Alert
- **label**: 에러 메시지
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [11]

- **note**: multipart(file + meta JSON)로 업로드 파이프라인 트리거 API를 호출한다. dev 전용 endpoint. file+필수값 충족 시 활성, 처리 중 로딩 표시
- **type**: Button
- **label**: 업로드

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [12]

- **note**: 폼/파일/결과 리셋
- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 영상 파일(mp4/webm/mov/avi, 최대 500MB) 업로드 + 메타데이터 입력 후 업로드 파이프라인 트리거 API를 호출한다. 입력: vmsClipId(영문/숫자/-/_ 1~64자, 기본 test-{타임스탬프}), cctvId(영문/숫자/-/_ 1~64자, 기본 CCTV-001 — 사전 등록 불필요), eventTypeCd(이벤트유형마스터 조회 결과를 동적으로 불러와 선택 — 형식은 EV+숫자 8자리, 고정 화이트리스트 아님), localGovCd(숫자 1~10자리, 기본 11680), prvcTypeCd(ANONY/PRVC/PSDO 라디오 — 선택값은 표시용이며 비식별 실행 여부에 영향 없음, 비식별은 영상 전체에 항상 자동 실행됨), capturedAt(datetime-local→ISO-8601 변환). durationSec는 서버가 업로드된 파일에서 자동 추출(입력 불가, 안내 박스로 대체). 단계별 실행 선택 기능은 두지 않는다 — 업로드→비식별(무조건 실행)→마킹 대기 정지의 고정 플로우다. 파일 input accept로 확장자 1차 가드, 서버가 본 검증 수행. 제출 버튼(업로드)은 file+필수값 충족 시에만 활성, 처리 중에는 전체 disabled. 에러 시 서버 메시지를 role=alert 배너로 표시. 초기화 버튼은 폼/파일/결과 리셋.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 실행 결과 + 파이프라인 상태

- **role**: side
- **layout**: detail

**components**:

#### [1]

- **note**: result 존재 시에만 조건부 렌더
- **type**: Card
- **label**: 업로드 결과

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: mono 스타일, data-testid=autolabel-raw-sn
- **type**: Custom
- **label**: rawSn 칩

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: RawSnChip

#### [3]

- **note**: 영상 상세 조회 결과의 status, 없으면 업로드 응답의 pipelineStatus로 대체
- **type**: Custom
- **label**: 파이프라인 상태

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: PipelineStatus

#### [4]

- **note**: terminal(MARKING_READY/FAILED) 미도달 시 표시
- **type**: Custom
- **label**: 파이프라인 진행 중
- **state**: loading

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Spinner

#### [5]

- **note**: 상태가 MARKING_READY 도달 시 role=status로 노출(rawSn 포함) — 비식별 후 마킹 대기 상태이며, 마킹 화면에서 마킹을 진행하면 잔여 배치가 실행된다는 안내
- **type**: Alert
- **label**: 마킹 대기 안내
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [6]

- **note**: 상태가 FAILED 도달 시 role=alert (rawSn 포함)
- **type**: Alert
- **label**: 파이프라인 실행 실패
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [7]

- **note**: 파일 경로 / 프레임 수 / 트리거 시각
- **type**: List
- **label**: 결과 메타 3열

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 업로드된 영상의 상세 화면으로 이동 (react-router Link)
- **type**: Custom
- **label**: 영상 상세로 이동 →

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Link

#### [9]

- **note**: 영상 처리 현황 목록 화면으로 이동 (react-router Link)
- **type**: Custom
- **label**: 영상 처리 현황 보기 →

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Link

- **description**: 업로드 성공(rawSn 수신) 시에만 렌더되는 결과 카드. 영상 상세를 2초 간격으로 폴링하여 파이프라인 진행을 가시화한다. 표시: rawSn 칩, 파이프라인 상태 텍스트, 진행 중 스피너(terminal 도달 전까지), 저장 파일 경로, 프레임 수, 트리거 시각. dev 업로드는 고정 플로우(업로드→비식별 무조건 실행→마킹 대기 정지)이므로 폴링 종료(terminal) 조건은 상태가 MARKING_READY(마킹 대기) 또는 FAILED에 도달한 시점이며, 이 경로에서 상태가 완료(COMPLETED)로 가는 일은 없다 — 잔여 배치(마킹 이후 단계)는 사용자가 마킹 화면에서 마킹을 완료해야 트리거된다. MARKING_READY 도달 시 마킹 대기 안내 배너를, FAILED 도달 시 실패 배너(role=alert)를 노출한다. 하단에 영상 상세 화면(rawSn 기준)과 영상 처리 현황 목록으로 이동하는 링크 2건을 제공한다.

**references_apis**:

- API-043

**references_features**:

_(empty)_

### TUS 재개 가능 업로드 (관제 인입 재현)

- **role**: main
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: TUS 재개 가능 업로드 (대용량) — 관제 인입 재현

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 청크 단위 업로드로 네트워크 중단 시 이어받기를 지원한다. 입력값은 관제서버가 인입 테이블에 보내는 항목과 동일하게 적재된다.
- **type**: Custom
- **label**: 안내 문구

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Text

#### [3]

- **note**: type=file, accept=video/mp4,webm,quicktime,x-msvideo + .mp4/.webm/.mov/.avi
- **type**: Input
- **label**: 영상 파일

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 필수 4종 + 선택 1종. 영상 클립 ID*(저장 파일명이 됨, 영문/숫자/_/- 64자) · CCTV ID*(영문/숫자/_/- 64자) · 출처유형*(select) · 지자체코드*(숫자 1~10자리) · 촬영일시(datetime-local, 선택)
- **type**: Custom
- **label**: 식별 정보

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FieldsetGroup

#### [5]

- **note**: 전부 선택 입력. 지자체명 · 기관코드 · CCTV명 · 카메라 높이(m) · 위도(WGS84) · 경도(WGS84) · 주감시방향(도, 0~360). 촬영 시점 값으로 고정 저장되어, 이후 카메라 제원이 바뀌어도 과거 영상 값은 유지된다.
- **type**: Custom
- **label**: 위치 · CCTV 제원

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FieldsetGroup

#### [6]

- **note**: 선택, 예 ABA_0001 — 이벤트유형코드가 아니다
- **type**: Input
- **label**: 이벤트 ID

**columns**:

_(empty)_

**options**:

_(empty)_

#### [7]

- **note**: 선택
- **type**: Input
- **label**: 이벤트명

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 값은 '미지정(외부 시계열 검증 위탁 생략)', 자주 쓰이는 6종 프리셋(벤더 규격 원문 영문 소문자 값), '직접 입력' 중에서 고른다. '직접 입력'을 고르면 별도 입력칸이 열리고 그 칸에 적은 값이 전송된다 — '직접 입력' 항목 자체는 화면의 입력 모드를 바꾸는 표식일 뿐 전송되는 값이 아니다. 서버는 이 값을 고정 목록으로 판정하지 않고 형식(영문 소문자·숫자·밑줄 조합, 20자 이내)으로만 판정한다 — 프리셋 6종 밖의 값이라도 형식만 맞으면 그대로 외부 검증에 위탁되고, 형식을 벗어나면 거부된다.
- **type**: Select
- **label**: 검증이벤트유형

**columns**:

_(empty)_

**options**:

- 미지정 (시계열 검증 위탁 생략)
- 6종 프리셋 (벤더 규격 영문 소문자 값)
- 직접 입력

#### [9]

- **note**: 선택, 최대 4000자. Upload-Metadata 헤더가 아니라 세션 생성 요청의 JSON 바디로 전송된다(헤더 용량 한도로는 이 분량을 담을 수 없다).
- **type**: Textarea
- **label**: 관제일지

**columns**:

_(empty)_

**options**:

_(empty)_

#### [10]

- **note**: 전부 선택. 값을 입력한 항목만 입력값 그대로 사용되고, 비운 항목만 서버가 업로드된 파일에서 자동 추출한다(ffprobe). 항목: 영상길이(초) · FPS · 프레임수 · 가로(px) · 세로(px) · 해상도(예 1920x1080) · 종횡비(예 16:9) · 코덱 · 파일형식(비우면 확장자) · 파일크기(byte, 비우면 실제 전송 크기) · BIT(색심도, 예 24bit — 비트레이트 아님) · PXL(화소, 예 4K).
- **type**: Custom
- **label**: 영상 기술메타 (선택)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FieldsetGroup

#### [11]

- **note**: 진행률(%), 전송량/전체용량(MB), 상태 텍스트 표시
- **type**: Custom
- **label**: 진행률

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ProgressBar

#### [12]

- **note**: role=alert. 조건부 렌더
- **type**: Alert
- **label**: 업로드 에러 메시지
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [13]

- **note**: 완료 시 정상 케이스('인입 대기 중')와, 서버의 인입 스캔 기능이 꺼져 있어 적재되지 않는 경고 케이스 2종을 색상으로 구분해 표시
- **type**: Custom
- **label**: 업로드 완료 안내
- **state**: hidden

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: StatusBanner

#### [14]

- **note**: 파일 선택 시 활성
- **type**: Button
- **label**: 업로드 시작

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [15]

- **note**: 업로드 진행 중에만 노출
- **type**: Button
- **label**: 일시정지

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [16]

- **note**: 일시정지 상태에서만 노출 — 기존 세션을 이어받아 재개한다
- **type**: Button
- **label**: 재개

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [17]

- **note**: 업로드 중이거나 일시정지·오류 상태에서 노출
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 관제서버가 인입 테이블에 보내는 항목과 동일한 구성으로 대용량 영상을 청크 단위로 업로드하는 패널 — 네트워크 중단 시 이어받기(재개)를 지원한다. 위쪽의 '파일 + 메타 입력 폼'과 별개로 독립 동작하며, 관제 인입 경로와 동일한 형태로 데이터가 적재되는지 시험하는 용도다. 영상 파일 선택 후 4개 입력 그룹(식별 정보 · 위치·CCTV 제원 · 이벤트·관제일지 · 영상 기술메타)을 채우고 업로드를 시작한다. 이벤트·관제일지 그룹에는 이벤트 ID·이벤트명·검증이벤트유형(6종 프리셋 + 직접 입력, 서버 판정은 목록이 아니라 형식) 선택·관제일지가 포함된다. 진행률(%)과 전송량/전체용량, 상태 텍스트를 표시하며, 업로드 중에는 일시정지, 일시정지 중에는 재개, 업로드 중이거나 일시정지·오류 상태에서는 취소가 가능하다. 완료 시 '인입 대기 중' 안내를, 서버의 인입 스캔 기능이 꺼져 있는 경우에는 적재되지 않는다는 경고를 별도 색상으로 표시한다. 오류 시 서버 메시지를 role=alert로 표시한다. TUS 프로토콜 재개 업로드 세션(세션 생성 POST, 청크 전송 PATCH) — dev 전용 endpoint.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

2차 개발용 오토라벨 테스트 (비운영)

## surface_kind

web

## consumes_apis

- API-043
- API-156
- API-158
- API-160
- API-162
- API-164

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

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-027/main.html
- **label**: 오토라벨 테스트 화면 (개발) — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **source_hash**: caa6e725362c8bc298b4488675ba658e8af9c67406b4fd3147923e75a1dcb3b0
- **generated_at**: 2026-08-13T11:03:46.839Z
- **generated_by**: sections-deterministic-generator

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
