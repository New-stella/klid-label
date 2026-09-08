---
logicraft_item: SCREEN-006
type: screen_spec
version: 52
last_updated_at: 2026-09-07T15:30:03.835Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-08T06:20:00.296Z
sync_session: 38
stale: false
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

영상 스트리밍(서명 URL 선행 조회 후 HTTP Range) + 배속(0.25x~4x) + 키보드 단축키(1=수동/2=자동 모드 전환은 항상 발화, Space=마킹/Del·Backspace=삭제/Enter=완료는 수동 모드에서만 발화)로 이벤트 시점을 마킹. 스트리밍은 항상 비식별 영상을 서빙하며 비식별 미완료 시 NOT_FOUND 로 원본 노출을 차단한다. 이벤트명은 입력받지 않고 마킹 대상 영상의 이벤트 유형(LS_DATA_RAW.EVNT_TYPE_CD)에서 자동 소싱해 화면에 표시한다. 외부 시계열 위탁 요청에 싣는 검증 이벤트 유형은 이와 다른 컬럼인 관제 인입값(LS_DATA_INGEST.VRFC_EVNT_TYPE_CD)에서 별도로 조달하며, 두 컬럼은 서로 다른 값이라 대체·통합하거나 한쪽에서 유도하지 않는다. 관제가 그 값을 보내지 않은 영상에서는 작업자가 화면에서 검증 이벤트 유형을 직접 고른다 — 관제 값이 있으면 유형 선택을 아예 노출하지 않으므로 두 조달처가 맞붙지 않는다. 고를 수 있는 유형 목록은 항목마다 그 유형의 질문 목록을 함께 싣고 있어, 유형을 고르면 서버에 다시 묻지 않고 그 유형의 질문 목록이 열린다 — 질문을 고를 수 없고 외부 시계열 위탁도 유형이 없어 거부되던 상태가 함께 풀린다. 유형 선택을 노출한 영상에서는 유형을 고르지 않으면 마킹을 완료할 수 없다. 외부 위탁에 싣는 질문 문구는 어느 마킹 방식에서도 반드시 값이 있어야 하고, 질문을 고르려면 유형이 먼저 정해져야 하기 때문이다. 다만 완료 버튼을 비활성화하지는 않으며, 유형이 빈 채로 누르면 토스트로 사유를 알리고 제출만 막는다. 작업자가 고른 질문 문구는 외부 시계열 위탁 요청에 그대로 실려 나간다. 마킹 위치(프레임 지점)는 프레임 추출 기준으로 유지. durationSec 미상 시 ffprobe 등 3단 폴백(쓰기 트랜잭션 밖에서 실행)으로 산출하며, 자동 마킹은 durationSec 확보 실패 시 거부된다.

[비식별 누락 신고 — 마킹 단계 진입점] rawSn 기준으로 POST /v1/videos/{rawSn}/deident-report 를 접수한다. ★접수 조건은 배치 단계가 MARKING_READY 일 때뿐이며 그 외에는 412 다 — 그 상태는 선두 비식별 성공 직후·마킹 이전이라 프레임 행도 라벨도 아직 없어 재마킹이 파괴할 작업 결과가 없기 때문이다. 파생영상(증강·해상도 변환본)은 신고 체계 밖이라 아예 접수하지 않는다(412). ★신고해도 라벨과 개인정보 3필드 판정은 모두 보존된다. 신고 즉시 작업락이 걸리고 DE_IDNTF_YN='F' 로 전이되어 스트리밍·라벨 조회·저장이 차단되므로 마킹을 계속할 수 없다. 자동 재비식별 큐는 없으며 외부 솔루션으로 수동 비식별화한 뒤 POST /v1/deident-reports/{rprtSn}/resolve(WORKER 본인 배정 / REVIEWER 전체)로 해소하면, 마킹 단계 신고는 배치 단계가 MARKING_READY 로 되감기고 활성 마킹이 SKIPPED 로 종결돼 재마킹할 수 있게 된다.

접근: REVIEWER/WORKER.

## sections

### 영상 플레이어 (스트리밍·배속)

- **role**: hero
- **layout**: stack

**components**:

#### [1]

- **note**: GET /v1/videos/{rawSn}/stream-url 로 단기 서명 URL 을 먼저 발급받아 <video src> 에 바인딩(HTTP Range 재생). <video> 엘리먼트는 Authorization 헤더를 붙일 수 없어 인증 스트림(/api/v1/videos/{rawSn}/stream)을 직접 재생하지 못한다. 서명 만료 등 재생 오류 시 1회 한해 stream-url 재발급 후 재시도. 서명 URL 재생은 발급 응답이 함께 내려준 nonce 쿠키(klid_stream_nonce)를 전제로 한다 — 브라우저가 자동으로 실어 보내고 스크립트로는 다룰 수 없어, 동일 오리진이면서 쿠키가 실리는 경로여야 한다. 쿠키가 없거나 유효하지 않으면 401 이며, 위 1회 재시도가 이 401 도 포괄한다.
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
- **label**: 탐색 슬라이더

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

- **description**: VideoPlayer — 먼저 GET /v1/videos/{rawSn}/stream-url(서명 URL 발급)을 조회하고, 응답의 서명된 URL을 <video src>에 바인딩해 HTTP Range 로 재생한다(<video> 태그는 Authorization 헤더를 붙일 수 없어 인증이 걸린 /stream 을 직접 재생할 수 없다 — 별도 fetch 없는 직접 src 바인딩이 아니다). 서명 만료 등 재생 오류 시 1회 한해 stream-url 을 재발급받아 재시도한다. 서명 URL 재생이 성립하려면 발급 응답이 함께 내려준 nonce 쿠키(klid_stream_nonce)가 후속 스트림 요청에 함께 실려야 한다. 그 쿠키는 스크립트가 다룰 수 없고 브라우저가 자동으로 부착하므로, 재생 요청이 동일 오리진이면서 쿠키가 실리는 경로여야 한다는 제약이 따른다. 쿠키가 없거나 유효하지 않으면 서명 검증이 실패해 401 이며, 위의 1회 재시도는 서명 만료뿐 아니라 이 401 도 포괄한다 — 재발급을 받으면 쿠키도 함께 새로 내려오기 때문이다. 쿠키 속성 전문은 API-114 가 정하므로 이 화면 사양에 옮겨 적지 않는다. 재생/일시정지 토글, 배속 버튼(0.25/0.5/1/1.5/2/4x), seek range bar, 현재시각/총길이(mm:ss) 표시. 현재 재생 시각·현재 프레임(영상 실측 fps 기준 환산, 미상 시 30 폴백)·특정 지점 이동 기능을 다른 컴포넌트에 노출한다.

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

- **note**: 항상 클릭 가능(비활성 상태 없음). MANUAL 모드에서 마크 0건 상태로 클릭하면 토스트 경고('재생하며 마킹을 1건 이상 쌓아 주세요.')를 띄우고 제출만 막는다. AUTO 모드는 간격 값(1 이상)만 있으면 되며 이 검사 대상이 아니다 — 마크 건수로 제출을 막으면 AUTO 모드 제출 자체가 불가능해진다. 검증 이벤트 유형 선택을 노출한 영상에서 유형을 고르지 않은 채 클릭하면 같은 방식으로 토스트 경고를 띄우고 제출만 막으며, 이 검사는 두 모드에 모두 적용된다.
- **type**: Button
- **label**: 마킹 완료

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-047

#### [5]

- **note**: rawSn 기준 신고. 사유 textarea(1~1000자) 입력 후 제출 → 작업락 + deIdntfYn='F'. ★라벨·개인정보 3필드는 보존한다(리셋 없음). 버튼은 videoDetail 기준으로 파생영상일 때 사전 비활성화한다(BE 거부 412). 배치 단계가 마킹 대기가 아닐 때는 버튼이 비활성화되는 것이 아니라 화면 진입 자체가 막혀 이 버튼이 렌더되지 않는다 — 그 축은 마킹 진입 차단 안내가 규정한다. 신고 성공 시 작업 목록(/task)으로 이동한다.
- **type**: Button
- **label**: 비식별 누락 신고

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-091

#### [6]

- **note**: videoDetail.derivative 면 '증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다.' 원본으로 유도하지 않고 부모 rawSn 도 노출하지 않는다.
- **type**: Custom
- **label**: 신고 불가 사유 툴팁

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: UnsupportedReasonTooltip

- **description**: MarkingToolbar — 자동/수동 모드 토글(탭에 단축키 1=수동/2=자동 표시, 전역에서 항상 발화). 이벤트명은 화면에서 입력받지 않고 마킹 대상 영상의 이벤트 유형(LS_DATA_RAW.EVNT_TYPE_CD)에서 자동 소싱되어 화면·마킹 응답에 표시된다. 외부 시계열 위탁 요청에 싣는 검증 이벤트 유형은 이와 별개로 관제 인입값(LS_DATA_INGEST.VRFC_EVNT_TYPE_CD)에서 조달하며, 관제가 그 값을 보내지 않은 영상에서는 작업자가 검증 이벤트 유형·질문 선택 영역에서 직접 고른다. 자동 모드: 간격(프레임) number 입력(1 이상 정수, 상한 없음, 기본값 300). 수동 모드: 단축키 안내(Space=마킹/Del·Backspace=삭제/Enter=완료). Space/Del·Backspace/Enter 세 단축키는 수동 모드에서만 발화한다(1/2 모드 전환은 예외적으로 모드 무관 항상 발화). 현재 마킹 건수 표시, 초기화 버튼, 마킹 완료 버튼(항상 클릭 가능 — 마크 0건이면 MANUAL 모드에서 토스트 경고로 제출을 막는다. AUTO 모드는 이 검사 대상이 아니다. 검증 이벤트 유형 선택을 노출한 영상에서 유형을 고르지 않았으면 두 모드 모두 같은 방식으로 토스트 경고를 띄우고 제출만 막는다). '비식별 누락 신고' 버튼은 rawSn 기준으로 POST /v1/videos/{rawSn}/deident-report(API-091) 호출 — 신고 접수 시 영상이 즉시 잠기고 라벨 조회·스트리밍이 차단되므로 마킹 작업을 계속할 수 없다.

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

### 검증 이벤트 유형·질문 선택

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: 관제가 보낸 검증 이벤트 유형이 없는 영상에서만 노출한다 — 관제 값이 있으면 이 선택 자체를 띄우지 않는다. 고를 수 있는 유형 목록은 화면 진입 시 함께 받아 싣는다. 그 목록은 항목마다 해당 유형의 질문 목록을 정렬순서 오름차순으로 함께 싣고 있어, 유형을 고르면 서버에 다시 묻지 않고 그 항목 안의 질문 목록이 질문 선택기에 채워지고 첫 번째가 기본 선택된다. 이 선택을 노출한 영상에서는 유형이 필수라 고르지 않으면 마킹을 완료할 수 없다 — 질문은 어느 마킹 방식에서도 반드시 값이 있어야 하고, 질문을 고르려면 유형이 먼저 정해져야 하기 때문이다. 완료 버튼을 비활성화하지는 않으며 유형이 빈 채로 누르면 토스트로 사유를 알리고 제출만 막는다. 고른 값은 마킹 완료 요청에 함께 실린다.
- **type**: Select
- **label**: 검증 이벤트 유형

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: vrfcEvntTypeCd

#### [2]

- **note**: 그 영상의 검증 이벤트 유형에 등록된 질문 목록을 정렬순서 오름차순으로 싣고 첫 번째 질문을 기본으로 선택해 둔다. 그 목록은 유형 목록 항목 안에 함께 실려 온 것이라 유형을 고른 뒤 서버에 다시 묻지 않는다. 유형 선택을 노출한 영상에서는 작업자가 유형을 고른 뒤에 목록이 열린다. 고른 질문의 일련번호가 마킹 완료 요청에 함께 실린다. 고를 질문이 하나도 없으면 이 드롭다운을 노출하지 않는다.
- **type**: Select
- **label**: 검증 질문

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: vrfcEvntQstnSn

#### [3]

- **note**: 드롭다운에서 잘릴 수 있는 긴 문구를 확인할 수 있도록 고른 질문의 전문을 그대로 표시한다.
- **type**: Text
- **label**: 선택한 질문 전문

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 작업자의 질문 선택이 기록에 머무르지 않고 외부로 나가는 값임을 알리는 안내 문구. 질문 드롭다운 아래에 함께 노출한다.
- **type**: Text
- **label**: 고른 질문 문구는 외부 시계열 위탁 요청에 그대로 실려 나갑니다.

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

마킹과 함께 저장할 검증 이벤트 유형과 검증 질문을 나란히 고르는 영역.

[검증 이벤트 유형 선택] 관제가 보낸 검증 이벤트 유형이 없는 영상에서만 유형 선택을 노출한다. 관제 값이 있으면 아예 띄우지 않아 두 조달처가 맞붙지 않는다. 유형이 없으면 질문을 고를 수 없고 외부 위탁의 묘사 축도 거부되는데, 유형을 고르면 그 둘이 함께 풀린다. 고를 수 있는 유형 목록은 화면 진입 시 함께 받는다. 항목마다 그 유형의 질문 목록을 정렬순서 오름차순으로 함께 싣고 있어, 유형을 고르면 서버에 다시 묻지 않고 질문 드롭다운이 채워지며 첫 번째가 기본 선택된다. 이 선택을 노출한 영상에서는 유형이 필수라 고르지 않으면 마킹을 완료할 수 없다 — 외부 위탁에 싣는 질문 문구는 수동이든 자동이든(자동은 그 유형의 첫 질문이 자동 선택) 반드시 값이 있어야 하는데, 질문을 고르려면 유형이 먼저 정해져야 한다. 다만 완료 버튼은 비활성화하지 않는다 — 유형이 빈 채로 누르면 토스트로 사유를 알리고 제출만 막는다(마크 0건 검사와 같은 방식). 버튼을 죽이면 사유가 드러나지 않는다. 고른 유형은 마킹 완료 시 마킹과 함께 저장된다.

[검증 질문 선택] 그 영상의 검증 이벤트 유형에 등록된 질문 목록을 정렬순서 오름차순으로 싣고 첫 번째를 기본으로 선택해 둔다. 고른 질문의 일련번호(vrfcEvntQstnSn)도 마킹과 함께 저장된다. 고를 질문이 하나도 없으면 이 드롭다운은 노출하지 않으며, 질문이 없다고 마킹이나 외부 위탁이 막히지는 않고 어노테이션의 질문 칸이 비어 있을 뿐이다. 선택값이 비었거나 그 유형에 속하지 않는 질문이면 서버가 그 유형의 첫 번째 질문으로 되돌리므로, 화면의 선택은 최종 판정이 아니라 서버 교정을 전제로 한 입력이다. 고른 질문의 전문은 드롭다운에서 잘릴 수 있어 아래에 그대로 보여 준다.

[작업자의 선택은 외부로 나간다] 고른 질문 문구는 외부 시계열 위탁 요청 본문에 그대로 실려 나가므로, 화면이 그 사실을 작업자에게 안내한다.

**references_apis**:

- API-047
- API-043

**references_features**:

_(empty)_

### 마킹 진입 차단 안내

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 비식별이 확정적으로 미완료일 때 마킹 편집기 대신 이 안내만 노출한다. 영상 상세 정보를 아직 받는 중이면 노출하지 않는다.
- **type**: Alert
- **label**: 비식별 완료 후 마킹이 가능합니다.

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 영상이 마킹 대기 상태가 아닐 때 마킹 편집기 대신 이 안내만 노출한다. 비식별 축 판정이 먼저라 두 축이 함께 걸리면 비식별 축 안내를 보여 준다. 영상 상세 정보를 아직 받는 중이면 노출하지 않는다.
- **type**: Alert
- **label**: 이미 다음 단계로 넘어간 영상이라 마킹할 수 없습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 마킹 편집기를 렌더할지 판정하는 영역. 영상 상세 정보로 두 축을 순서대로 확인해 하나라도 걸리면 편집기(영상 플레이어·마킹 타임라인·마킹 툴바·마킹 칩 목록·검증 이벤트 유형·질문 선택)를 렌더하지 않고 사유 안내만 보여 준다. 첫째 비식별 축 — 비식별이 확정적으로 미완료면 차단한다. 둘째 배치 단계 축 — 영상이 마킹 대기 상태가 아니면 차단한다. 서버도 마킹 대기 상태가 아닌 영상의 마킹 접수를 거부하므로, 화면이 선차단하지 않으면 작업자가 마크를 다 쌓은 뒤 제출 시점에야 거부된다. 두 축은 차단이라는 결과가 같아도 사유와 안내 문구가 달라 하나로 합치지 않는다. 판정 순서는 서버의 평가 순서와 같게 비식별 축이 먼저다 — 순서가 갈리면 같은 영상에 화면 안내와 서버 거부 사유가 달라진다. 영상 상세 정보를 아직 받는 중이면 어느 축도 차단하지 않는다. 확정되지 않은 값으로 차단하면 로딩 구간의 깜빡임이 정상 마킹을 막기 때문이다. 차단 화면에서는 비식별 누락 신고 버튼도 함께 노출하지 않는다 — 그 상태에서는 어차피 사유와 함께 비활성이던 버튼이고, 그 경로의 신고는 라벨링 화면이 담당한다.

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

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-120
- IMPREC-122
- IMPREC-124
- IMPREC-421

### progress

95

### subtasks

_(empty)_

### last_updated

2026-09-07T15:30:03.835Z

### module_paths

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

- **source_hash**: 05f209ad17c03105793c6347d80d6f747abfcc2c7bd2401fa05c204de0997304
- **generated_at**: 2026-09-07T15:25:32.726Z
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

- UC-019

## covered_by_acceptances

_(empty)_
