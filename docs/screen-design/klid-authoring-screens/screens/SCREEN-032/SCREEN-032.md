---
logicraft_item: SCREEN-032
type: screen_spec
version: 23
last_updated_at: 2026-08-16T07:12:37.508Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-28T22:54:49.352Z
sync_session: 33
stale: true
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-032.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-094]]", "[[API-109]]", "[[API-202]]"]
  required_roles: ["[[ROLE-001]]"]
  realizes_use_cases: ["[[UC-016]]"]
---

# 비식별 신고 관리 화면

## route

/manage/deident-reports

## title

비식별 신고 관리 화면

## device

desktop

## status

draft

## purpose

REVIEWER 전용(/manage/deident-reports). 라벨링·마킹 중 작업자가 개인정보 노출(비식별 누락)을 신고하면 영상이 잠기고(작업락 + DE_IDNTF_YN='F') 그 구간 동안 라벨 조회·저장·프레임 이미지·버전 diff·롤백·개인정보 메타 PUT 이 412 로 차단되고 스트리밍은 404 가 된다. ★라벨은 삭제되지 않으며(스냅샷도 남기지 않는다) 개인정보 3필드 판정도 보존된다. REVIEWER 는 본 화면에서 미처리 신고를 확인하고, 외부 솔루션으로 수동 비식별화를 완료한 뒤 '해소 처리'로 작업락을 해제한다(POST /v1/deident-reports/{rprtSn}/resolve). 해소로 'F'→'Y' 가 복원되면 게이트가 자동 해제되어 보존된 기존 라벨을 그대로 재사용한다(별도 복원 API 없음). 자동 재비식별 큐는 두지 않는다. ★목록은 '영상 #{rawSn}' 단위로 표시하며, 각 행에 신고 단계(마킹/라벨링/미상) 배지를 함께 표시한다 — 마킹 단계에서 접수된 신고인지 라벨링 단계에서 접수된 신고인지, 그 신고를 해소하면 무엇이 재개되는지를 REVIEWER 가 목록에서 바로 알 수 있게 한다. 서버는 신고 단계를 보유하며 해소 후 재개 지점이 그 단계로 갈린다. 접근: REVIEWER — 화면 진입 시점과 서버 요청 시점 양쪽에서 역할을 이중으로 검사한다.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 비식별 신고 관리

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: PageHeader. '비식별 신고 관리' 제목 + description('라벨링·마킹 중 신고된 비식별 누락 건을 확인하고 외부 수동 비식별화 완료 후 해소 처리합니다.').

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 신고 상태 필터 탭

- **role**: filter
- **layout**: stack

**components**:

#### [1]

- **type**: Custom
- **label**: 상태 탭 (미처리 / 처리완료)

**columns**:

_(empty)_

**options**:

- 미처리
- 처리완료

- **custom_name**: StatusTabs

#### [2]

- **type**: Custom
- **label**: 탭별 건수 배지

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: reports.totalElements
- **custom_name**: CountBadge

- **description**: 상태 탭 2종(미처리 기본 / 처리완료). 탭 전환 시 페이지가 처음으로 리셋되고 상태별 건수가 다시 조회된다. 각 탭 오른쪽에 해당 상태의 건수 배지.

**references_apis**:

- API-109

**references_features**:

_(empty)_

### 신고 목록 테이블

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: Table
- **label**: 신고 목록

**columns**:

- 신고 번호
- 영상
- 신고자
- 사유
- 신고일시
- 신고 단계
- 상태
- 처리

**options**:

_(empty)_

#### [2]

- **note**: 단계별 툴팁으로 해소 시 무엇이 재개되는지 안내한다. 단계 기록이 없는 레거시 신고는 '미상'으로 표시되며 해소해도 자동 재개가 없다는 안내를 별도 툴팁으로 준다.
- **type**: Custom
- **label**: 신고 단계 배지 (마킹/라벨링/미상)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DeidentStageBadge

#### [3]

- **type**: Custom
- **label**: 신고 상태 배지 (미처리/처리완료)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DeidentStatusBadge

#### [4]

- **note**: 미처리 상태 행에만 노출. 클릭 시 비식별 산출물 선택 다이얼로그가 열리며, 거기서 고른 파일명을 실어 해소 처리를 요청한다. 서버 안전장치 — ①선택한 파일이 요청 시점에 다시 열거한 후보 목록에 있어야 하고 저장 서브트리 실경로·산출물 무결성·신고 시각 이후 수정 검증을 모두 통과해야 해소가 성립하며 실패 시 미처리 상태가 그대로 유지된다 ②동일 신고 2인 동시 해소 요청 시 하나만 성공하고 나머지는 실패로 처리된다(중복 실행 차단) ③성공한 요청만 작업락을 해제하고 선택한 산출물 경로를 비식별 처리 이력에 새 성공 행으로 적재한 뒤 신고 단계에 따라 재개 동작을 수행한다.
- **type**: Button
- **label**: 해소 처리

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-094

#### [5]

- **note**: totalPages>1 일 때 노출. 이전 · 페이지 번호 · 다음 순서로 배치하고, 페이지 번호는 양끝(첫·마지막)과 현재 앞뒤 1칸만 노출하며 그 사이는 말줄임으로 접는다. 페이지당 20건.
- **type**: Custom
- **label**: 페이지네이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Pagination

#### [6]

- **type**: Alert
- **label**: 신고 목록을 불러올 수 없습니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 신고 목록 테이블(신고 번호 · 영상 · 신고자 · 사유 · 신고일시 · 신고 단계 · 상태 · 처리). 영상은 '영상 #{rawSn}' 단위로 표시한다. 신고 단계 배지는 마킹(마킹 화면에서 접수)/라벨링(라벨링 화면에서 접수)/미상(이 기능 이전 접수, 기록 없음) 중 하나이며, 호버 시 해소하면 무엇이 재개되는지 안내 문구가 뜬다. 신고 상태 배지는 미처리/처리완료를 표시한다. 신고자 표시명은 페이지의 USER_NO 를 단일 IN 쿼리로 한 번에 해석한다(N+1 회피, 마스터에 없는 번호는 null). 미처리 행에만 '해소 처리' 버튼이 노출되며 해소 성공 시 그 영상의 스트림 메타 캐시가 커밋 후 무효화되고(외부 수동 재비식별로 비식별본이 교체됐을 수 있음) 신고 단계에 따라 재개 이벤트가 발행된다 — 마킹 단계 신고는 배치 단계를 되감고 활성 마킹을 종결해 재마킹을 열며, 라벨링 단계 신고는 프레임 이미지만 재추출해 라벨 좌표를 보존한 채 이어간다(단계 미상은 재개 이벤트 미발행). 목록 로딩 중에는 스켈레톤을, 탭별로 '미처리 신고가 없습니다'/'처리완료된 신고가 없습니다' 빈 상태 안내를 표시한다. 에러 시 ErrorState.

**references_apis**:

- API-094
- API-109

**references_features**:

_(empty)_

### 비식별 산출물 선택 다이얼로그

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: 비식별 산출물 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 각 후보는 파일명·크기·수정시각을 함께 보여준다. 내부 저장 경로는 표시하지 않는다. 현재 이력에 기록된 산출물에는 그 사실을 알리는 표시를 단다. 초기에는 아무것도 선택돼 있지 않다.
- **type**: Custom
- **label**: 산출물 후보 목록 (단일 선택)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DeidentArtifactCandidateList

#### [3]

- **note**: 후보 0건일 때만 노출. 외부 솔루션으로 비식별을 완료한 뒤 다시 시도하라고 안내하고 확인 조작은 비활성 상태로 둔다.
- **type**: Alert
- **label**: 선택할 수 있는 비식별 산출물이 없습니다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 후보를 고르기 전에는 비활성. 선택 후 클릭하면 고른 파일명을 실어 해소를 요청하고 성공 시 토스트 + 목록 최신화 후 닫힌다. 서버 검증에 실패하면 다이얼로그를 연 채 실패를 알리고 신고는 미처리로 남는다.
- **type**: Button
- **label**: 해소 처리
- **state**: disabled

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-094

#### [5]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: '해소 처리'를 누르면 열리는 다이얼로그. 서버가 그 영상의 비식별 산출 디렉터리를 다시 열거해 만든 후보 목록을 보여주고 REVIEWER 가 하나를 고른다. 초기 상태는 아무것도 선택되지 않은 상태이며, 고르기 전에는 확인 조작을 할 수 없다 — 서버가 기본값을 고르지 않으므로 사람이 명시적으로 선택해야 한다. 각 후보는 파일명과 함께 크기·수정시각을 보여준다. 외부 비식별 솔루션이 같은 이름으로 덮어쓰지 않고 다른 이름으로 산출할 수 있어, 어느 것이 이번에 새로 만들어진 산출물인지 사람이 판단할 근거가 필요하기 때문이다. 현재 비식별 처리 이력에 기록된 산출물에는 그 사실을 알리는 표시를 달아 구분한다. 화면에는 내부 저장 경로를 표시하지 않고 파일명만 보여준다. 후보가 0건이면 확인 조작을 비활성화하고 외부 솔루션으로 비식별을 완료한 뒤 다시 시도하라는 안내를 표시한다. 선택한 파일이 서버 검증을 통과하지 못하면 해소가 성립하지 않고 신고는 미처리 상태로 남는다.

**references_apis**:

- API-094

**references_features**:

_(empty)_

## brownfield

### status

new

### diff_summary

비식별 누락 신고 관리(수동 흐름) — REVIEWER 가 OPEN 신고를 외부 수동 비식별화 후 해소 처리하는 화면. v2 신규.

## surface_kind

web

## consumes_apis

- API-094
- API-109
- API-202

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

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-032/main.html
- **label**: 비식별 신고 관리 화면 — 와이어프레임
- **width**: 1440
- **surface**: page

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 42f0d937ccca7c29e936be9d685e077bb26017febb03caaa4aa03dab90edcedf
- **generated_at**: 2026-08-13T01:02:43.637Z
- **generated_by**: sections-deterministic-generator

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-016

## covered_by_acceptances

_(empty)_
