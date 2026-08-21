---
logicraft_item: SCREEN-008
type: screen_spec
version: 40
domain: DOMAIN-003
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T00:40:10.691Z
status: CHANGED
prev_version: 39
content_hash: abd5ded4241ed26f0c45fff4b12e5baa8d033cfd8a0701e7d86b64ce46e526fd
stale: true
raw: ./_raw/SCREEN-008.json
links:
  belongs_to_domain: ["[[DOMAIN-003]]"]
  consumes: ["[[API-042]]", "[[API-047]]", "[[API-068]]", "[[API-070]]", "[[API-071]]", "[[API-181]]", "[[API-212]]", "[[API-214]]"]
  covered_by: ["[[AC-049]]", "[[AC-050]]"]
  realizes: ["[[UC-018]]"]
  references: ["[[API-042]]", "[[API-047]]", "[[API-068]]", "[[API-070]]", "[[API-071]]", "[[API-181]]", "[[API-199]]", "[[API-212]]", "[[API-214]]"]
  requires: ["[[ROLE-001]]", "[[ROLE-002]]"]
  applies_to_backward: ["[[SHELL-001]]"]
  designs_backward: ["[[SD-013]]"]
  granted_on_backward: ["[[ROLE-001]]", "[[ROLE-002]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  realizes_backward: ["[[MOD-003]]"]
  references_backward: ["[[TEST-001]]", "[[UC-011]]", "[[UC-018]]"]
---

# 영상 처리 현황 화면

## route

/video/status

## title

영상 처리 현황 화면

## device

desktop

## status

draft

## purpose

영상 목록을 조회하고 REVIEWER 가 마킹 진입/작업자 재배정/일괄 배정하는 화면. 조회는 REVIEWER/WORKER 공통, 마킹·배정 동선은 REVIEWER 전용(WORKER 는 조회만). 미배정 영상 중 배치 단계가 마킹 대기이고 비식별이 확정적으로 미완료가 아닌 행의 액션은 '마킹 설정' 버튼으로 자동/수동 방식을 선택한다 — 자동은 프레임 간격(1 이상 정수, 화면에서는 상한을 두지 않고 서버가 백스톱) 입력 후 POST /v1/videos/{rawSn}/markings(API-047)를 즉시 트리거하고, 수동은 팝업을 닫고 작업자 배정 흐름(assign 모드)으로 전환된다. 기존 배정 영상의 '재배정' 버튼은 별도로 유지된다. 영상 목록에는 개인정보 유무 컬럼을 표시하지 않는다. 시계열 위탁 전체 건너뛰기 스위치가 켜져 있으면 그 사실을 제목 아래 배너로 상시 알리고, 검색·필터에는 시계열 건너뜀 조건을 두어 벤더 연동이 확정된 뒤 회수 대상을 모을 수 있게 한다. 접근: REVIEWER/WORKER.

## sections

### 페이지 헤더 + 새로고침

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 영상 처리 현황

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 조회 결과 재조회 + 영상 목록 쿼리 무효화. 타임스탬프는 표시하지 않는다.
- **type**: Button
- **label**: 새로고침

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [3]

- **note**: 시계열 위탁 전체 건너뛰기 스위치가 켜져 있을 때만 노출한다. 설정에 적힌 사유를 본문에 함께 싣고 시스템 설정 화면으로 가는 길을 둔다. 경고 톤이며 색상만으로 구분하지 않고 아이콘과 문구를 함께 쓴다.
- **type**: Custom
- **label**: 시계열 전체 건너뛰기 켜짐 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: AlertBanner

**description**:

제목 '영상 처리 현황' + 설명 '관제서버에서 인계받은 영상의 배치 처리 상태와 단계를 확인합니다.' 우측에 새로고침 버튼.

★시계열 위탁 전체 건너뛰기 스위치가 켜져 있으면 그 사실을 알리는 배너를 제목 아래에 상시 표시한다. 켜져 있는 동안 들어오는 영상은 전건이 시계열 없이 확정되는데, 그 사실이 어디에도 드러나지 않으면 아무도 모르는 사이에 학습데이터가 시계열 없이 쌓인다. 배너에는 설정에 적힌 사유를 함께 싣고 시스템 설정 화면으로 가는 길을 둔다 — 끄는 것을 잊으면 벤더 연동이 끝난 뒤에도 계속 건너뛰기 때문이다. 스위치가 꺼져 있으면 배너를 두지 않는다.

**references_apis**:

- API-042
- API-068

**references_features**:

_(empty)_

### 검색·필터

- **role**: filter
- **layout**: form

**components**:

#### [1]

- **note**: maxLength 100
- **type**: Input
- **label**: CCTV명 / 영상ID

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: values.cctvNameKeyword
- **placeholder**: CCTV명 / 영상ID를 입력하세요.

#### [2]

- **note**: BE 데이터 상태코드 5종(완료/처리중/마킹 대기/대기/실패) + 전체 상태. 마킹 대기가 빠지면 적재부터 마킹까지의 구간에 있는 영상을 상태로 좁힐 수 없다.
- **type**: Select
- **label**: 상태

**columns**:

_(empty)_

**options**:

- 전체 상태
- 완료
- 처리중
- 마킹 대기
- 대기
- 실패

- **binds_to**: values.dataSttsCd

#### [3]

- **note**: GET /v1/event-types 서버 조회 동적 옵션 — value=categoryKey(그룹 대표코드 = 그룹 내 최소 유형코드), 표시=label. 로딩 중에는 disabled + aria-busy. 표시명이 같은 유형코드들은 옵션 1건으로 접히고, 필터 파라미터는 표시명이 아니라 이벤트 코드를 유지한다.
- **type**: Select
- **label**: 이벤트 유형

**columns**:

_(empty)_

**options**:

- 전체 이벤트
- (서버 조회 동적 옵션)

- **binds_to**: values.eventTypeCd

#### [4]

- **note**: 날짜 입력. 종료일과 상호 min/max 제약
- **type**: Input
- **label**: 시작일

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: values.from

#### [5]

- **note**: 날짜 입력. 시작일과 상호 min/max 제약
- **type**: Input
- **label**: 종료일

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: values.to

#### [6]

- **note**: 지금 시계열 묶음이 건너뛴 상태인 영상만 남긴다. 이미 되살린 영상은 남지 않는다. 벤더 연동이 확정된 뒤 회수 대상을 모으는 자리다.
- **type**: Select
- **label**: 시계열 건너뜀

**columns**:

_(empty)_

**options**:

- 전체
- 시계열 건너뜀

- **binds_to**: values.skippedStage

#### [7]

- **type**: Button
- **label**: 조회

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-042

#### [8]

- **note**: 필터 활성 시에만 enabled
- **type**: Button
- **label**: 초기화

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

**description**:

검색·필터 바. 한 줄 그리드 폼으로 배치한다. 조회 시 입력값을 URL 로 확정하고 page=0 로 리셋한다(공백만 남은 값은 트림 후 필터 미적용). 초기화 시 입력·URL 전체 리셋.

★상태 옵션은 데이터 상태코드 5종(완료/처리중/마킹 대기/대기/실패)과 1:1 이고 여기에 '전체 상태'가 더해져 6개다 — 마킹 대기가 빠지면 적재부터 마킹까지의 구간에 있는 영상을 상태로 좁힐 수 없다(그 상태의 영상이 목록에 실제로 존재한다).

★이벤트 유형 옵션은 서버 조회 동적 옵션이다(GET /v1/event-types) — 하드코딩 목록은 쓰지 않는다. value=categoryKey(그룹 대표코드 = 그룹 내 최소 유형코드), 표시=label 이며 로딩 중에는 disabled + aria-busy 로 표기한다. 표시명이 같은 유형코드들은 옵션 1건으로 접힌다(관제가 이벤트 코드만 송신해 같은 카테고리의 상세 유형들이 같은 이름으로 보였기 때문이다). 필터 파라미터는 표시명이 아니라 이벤트 코드를 유지한다 — 표시명은 운영자가 바꿀 수 있어 북마크와 저장된 프리셋이 깨진다. 대표코드로 들어오든 비대표 코드로 들어오든 그룹 전체로 확장해 매칭하며, 미등록·비규격 코드도 자기 혼자 그룹이 되어 옵션에 남는다(제거하면 그 영상이 필터로 도달 불가능해진다).

시작일·종료일은 날짜 입력이며 서로 min/max 제약을 건다.

★시계열 건너뜀 필터를 함께 둔다. 벤더 연동이 확정된 뒤 건너뛴 영상을 모아 되살리려면 그 대상을 목록에서 골라낼 수 있어야 하는데, 일괄 요청이 한 번에 받는 건수에 상한이 있어 필터가 없으면 회수가 성립하지 않는다. 지금 건너뛴 상태인 영상만 남기며 이미 되살린 영상은 남지 않는다. 오토라벨 건너뜀은 옵션에 두지 않는다 — 일괄 축이 시계열 하나인 것과 같은 이유다.

**references_apis**:

- API-042
- API-181

**references_features**:

_(empty)_

### 일괄 작업 바 (REVIEWER 전용)

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 선택된 건수>0 일 때 노출. WORKER 는 행 선택 체크박스 자체가 없어 선택이 항상 0건이다 — 사실상 REVIEWER 전용.
- **type**: Custom
- **label**: 선택 N건 → N건 일괄 배정

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: BulkActionBar
- **triggers_api**: API-070

#### [2]

- **note**: 실패 상태로 걸러 고른 영상이 1건 이상일 때 노출한다. 서버는 가능한 건만 재기동하고 거부된 건은 사유와 함께 건별로 돌려주므로, 화면은 성공 건수와 실패 건수를 함께 보여주고 실패한 영상은 사유를 알 수 있게 한다. 한 번에 보낼 수 있는 건수에는 상한이 있다.
- **type**: Custom
- **label**: 선택 N건 → N건 일괄 재시작

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: BulkActionBar
- **triggers_api**: API-199

#### [3]

- **note**: 그 묶음이 실패한 영상을 1건 이상 골랐을 때 노출한다. 실패 상태가 아닌 영상이 섞여 들어오면 그 건만 건별 실패로 돌아온다. 사유 입력을 받아 대상 전건에 같은 값으로 남긴다 — 사유는 비울 수 없고 보이지 않는 문자만으로 채울 수도 없으며, 위반이면 건별 실패가 아니라 요청 전체가 거부된다.
- **type**: Custom
- **label**: 선택 N건 → N건 시계열 일괄 건너뛰기

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: BulkActionBar
- **triggers_api**: API-212

#### [4]

- **note**: 그 영상에서 건너뛴 적이 있는 묶음이 대상이다 — 건너뛴 상태와 해제된 상태를 모두 받으므로 해제를 먼저 누를 필요가 없다. 검수가 완료된 영상도 시계열은 받으므로 연동이 늦어져 건너뛴 채 승인된 영상을 나중에 회수할 수 있다. 실패해도 영상 상태를 훼손하지 않으므로 벤더 미연동 상태에서 눌러도 안전하다.
- **type**: Custom
- **label**: 선택 N건 → N건 시계열 일괄 재수행

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: BulkActionBar
- **triggers_api**: API-214

**description**:

REVIEWER 전용 일괄 배정 바. 선택된 영상이 1건 이상일 때 'N건 일괄 배정' 버튼 노출 → 작업자 배정 모달 bulk 모드 오픈. 일괄 배정은 선택 videoIds 각각에 POST /v1/assignments 로 작업자 배정하며, 마킹 진입 팝업을 거치지 않는 별도 동선이다. ★같은 바에서 일괄 재시작도 제공한다. 처리 상태를 실패로 걸러 여러 건을 고른 뒤 한 번에 다시 돌리는 동선이며, 배치가 한 번 멈추면 여러 건이 동시에 실패하므로 상세 화면을 건건이 여는 대신 목록에서 처리한다. 일괄 재시작은 일부가 거부돼도 나머지를 진행하고 건별 결과를 돌려준다 — 재기동은 실패 상태를 먼저 선점하는 쪽이 이기는 방식이라 다른 검수자가 그중 한 건을 방금 눌렀다는 이유로 나머지를 전부 되돌리면 사용자가 선택을 반복하게 되기 때문이다. 전체 성공 또는 전체 실패로 처리하는 일괄 배정과는 의도적으로 다른 정책이다.
★같은 바에서 시계열 묶음의 일괄 건너뛰기·재수행도 제공한다. 건너뛰기 대상은 그 묶음이 실패한 영상뿐이다 — 벤더 장애로 여러 건이 한꺼번에 실패했을 때 쓰는 자리이며, 정상 영상을 미리 골라 건너뛰는 길은 두지 않는다. 미연동 구간을 통째로 덮는 몫은 시스템 설정의 전체 건너뛰기 스위치가 맡는다. 사유는 요청당 하나로 대상 전건에 같은 값으로 남는다. 대상 묶음은 시계열 하나이며 오토라벨은 상세 화면에서 건건이 다룬다 — 산출물이 라벨이라 대량으로 건너뛸 수 있게 열면 품질 축이 느슨해진다. 연동이 확정된 뒤의 회수는 일괄 재수행 하나로 끝나 별도의 해제 버튼을 두지 않는다 — 재수행이 건너뛴 상태를 직접 수락하고 해제 표식까지 함께 남긴다. 회수 대상은 검색·필터의 시계열 건너뜀 조건으로 모은다. 결과 표시는 일괄 재시작과 같다 — 성공·실패 건수를 함께 보이고 거부된 건은 사유를 알 수 있게 하며 한 번에 보낼 수 있는 건수에 상한이 있다.

**references_apis**:

- API-070
- API-199
- API-212
- API-214

**references_features**:

_(empty)_

### 영상 목록 테이블 + 페이지네이션

- **role**: main
- **layout**: list

**components**:

#### [1]

- **note**: REVIEWER 에게만 렌더
- **type**: Checkbox
- **label**: 전체 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: REVIEWER 는 선택 체크박스+배정자 컬럼이 추가 노출된다. 액션은 세 갈래 — 재배정/마킹 설정/영상 상세. 개인정보 유무 컬럼은 두지 않는다.
- **type**: Table
- **label**: 영상 목록

**columns**:

- CCTV명
- 이벤트
- 녹화일
- 길이
- 처리 단계
- 배정자(REVIEWER 전용)
- 액션

**options**:

_(empty)_

- **binds_to**: rows
- **triggers_api**: API-042

#### [3]

- **note**: 기존 배정(workerId 있음) && 검수 승인 완료 아님(assignStatus≠COMPLETED) 인 행에만 노출. REVIEWER 필수.
- **type**: Custom
- **label**: 재배정 버튼

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: RowReassignAction
- **triggers_api**: API-071

#### [4]

- **note**: 미배정(workerId==null)이고 REVIEWER인 행 중, 배치 단계가 '마킹 대기'(MARKING_READY)이며 비식별이 확정적으로 미완료 상태가 아닌 행에만 노출된다(canMark 판정 — status===MARKING_READY && 비식별 차단 아님). 조건을 충족하지 않으면 미배정이어도 버튼이 노출되지 않는다. 클릭 시 마킹 진입 팝업 오픈(자동/수동 선택).
- **type**: Custom
- **label**: 마킹 설정 버튼

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: RowMarkAction
- **triggers_api**: API-047

#### [5]

- **type**: Custom
- **label**: 영상 상세 버튼(항상 노출)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: RowDetailAction

#### [6]

- **note**: 총 페이지 1 초과일 때만 렌더. 이전 · 페이지 번호 · 다음 순서로 배치하고, 페이지 번호는 양끝(첫·마지막)과 현재 앞뒤 1칸만 노출하며 그 사이는 말줄임으로 접는다.
- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Pagination

#### [7]

- **type**: Custom
- **label**: 조회된 영상이 없습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EmptyState

#### [8]

- **type**: Custom
- **label**: 영상 목록을 불러오지 못했습니다.

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ErrorState

#### [9]

- **type**: Skeleton
- **label**: 로딩 스켈레톤(5행)

**columns**:

_(empty)_

**options**:

_(empty)_

**description**:

GET /v1/videos 페이징 목록. REVIEWER 는 전체선택+행 체크박스 컬럼, 배정자 컬럼(workerName, 없으면 '미배정' italic)이 추가로 노출된다. 컬럼: CCTV명(#id 4자리 패딩 병기)/이벤트(EventTypeBadge, 명칭 표시)/녹화일(capturedAt)/길이(durationSec 포맷)/처리단계(COMPLETED·FAILED 는 StageBadge, 그 외는 상태 배지 — 단, 비식별 진행 상태(deidentStatus)가 IN_PROGRESS 또는 FAILED 이면 배치 처리단계 배지보다 비식별 진행중/실패 배지를 우선 표시)/[REVIEWER]배정자/액션. 액션은 — ①재배정(workerId 있음 && assignStatus≠COMPLETED, REVIEWER) ②마킹 설정(workerId==null 이고 배치 단계가 MARKING_READY 이며 비식별이 확정적으로 미완료가 아닐 때, REVIEWER) ③영상 상세(항상). 행 클릭도 상세(/video/{id}) 이동. 로딩=Skeleton 5행, 에러=ErrorState(재시도 버튼), 빈 목록=EmptyState.

★개인정보 유무 컬럼은 표시하지 않는다 — 관제서버가 개인정보 유무를 실제로 보내지 않기 때문이다(인입 원장의 개인정보 관련 3필드가 실측상 전부 비어 있고, 화면이 보던 값은 관제가 보낸 값이 아니라 적재 시점에 고정되는 별개 구분값이었다). 응답 필드 자체는 하위호환으로 존치한다.

**references_apis**:

- API-042
- API-047
- API-071

**references_features**:

_(empty)_

### 작업자 배정 모달 (REVIEWER 전용)

- **role**: modal
- **layout**: stack

**components**:

#### [1]

- **note**: mode: assign(단건 신규, 마킹 설정 팝업에서 '수동' 선택 시 진입) / reassign(기존 배정 재배정) / bulk(선택 N건 일괄). 대상이 바뀌면 폼을 초기화한다.
- **type**: Custom
- **label**: 작업자 배정 모달

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: AssignModal
- **triggers_api**: API-071

- **description**: REVIEWER 전용 작업자 배정 모달. 3모드 — assign(마킹 설정 팝업에서 '수동' 선택 시 진입, POST /v1/assignments), reassign(PATCH /v1/assignments/{assignmentId}), bulk(선택 videoIds 각각 POST /v1/assignments). assign 모드는 이 화면에서 직접 열리지 않고 마킹 설정 팝업의 '수동' 경로로만 진입한다. 성공 시 모달을 닫고 선택 해제 + 영상 목록과 배정 목록을 갱신해 행의 배정자명이 즉시 반영된다.

**references_apis**:

- API-070
- API-071

**references_features**:

_(empty)_

### 마킹 진입 팝업 (REVIEWER 전용)

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Dialog
- **label**: 마킹 방식 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 자동 간격 입력 단계로 전환
- **type**: Button
- **label**: 자동

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [3]

- **note**: 팝업을 닫고 작업자 배정 모달(assign 모드)로 전환
- **type**: Button
- **label**: 수동

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [4]

- **note**: 1 이상 정수만 검증하고 화면에서는 상한을 두지 않는다(상한은 서버가 백스톱). 기본값 300
- **type**: Input
- **label**: 프레임 간격

**columns**:

_(empty)_

**options**:

_(empty)_

- **placeholder**: 예: 300 (300 프레임마다)

#### [5]

- **note**: mutate({mode:AUTO, intervalFrames}) — POST /v1/videos/{rawSn}/markings. 성공 시 마킹이 즉시 생성되고 관련 목록 갱신 + 안내 토스트.
- **type**: Button
- **label**: 자동 마킹 시작

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-047

- **description**: 영상 목록의 '마킹 설정' 버튼 클릭 시 열리는 팝업. 1단계 '마킹 방식 선택'에서 자동/수동을 고른다 — 자동은 2단계로 전환해 프레임 간격 입력 후 '자동 마킹 시작'으로 트리거하고(성공 시 마킹이 즉시 생성된다), 수동은 팝업을 닫고 작업자 배정 모달(assign 모드)로 전환한다. 이벤트명은 요청에 포함하지 않는다(서버가 영상 evntTypeCd 에서 소싱). 대상이 바뀌면 단계·입력을 초기화하며, 팝업을 닫아도 단계·입력이 초기화된다.

**references_apis**:

- API-047
- API-070

**references_features**:

_(empty)_

## brownfield

### status

modified

### change_kind

- redesign

### diff_summary

화면 목적은 유지하되 세부 구성을 검색·필터 + 영상 목록 테이블 + 작업자 배정 + 마킹 진입 방식으로 재설계한다. 배치 처리 KPI 집계 + 실시간 폴링 테이블 방식은 채택하지 않는다.

## surface_kind

web

## consumes_apis

- API-042
- API-047
- API-068
- API-070
- API-071
- API-181
- API-212
- API-214

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-037
- IMPREC-039

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-20T03:15:53.438Z

## required_roles

- ROLE-001
- ROLE-002

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-008/main.html
- **label**: 영상 처리 현황 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: a776f32e5d56bb9275c7eca074cadb3d6fc60af2acad58d2ed7162c10630746b
- **generated_at**: 2026-08-19T23:52:45.177Z
- **generated_by**: sections-deterministic-generator

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-018

## covered_by_acceptances

- AC-049
- AC-050
