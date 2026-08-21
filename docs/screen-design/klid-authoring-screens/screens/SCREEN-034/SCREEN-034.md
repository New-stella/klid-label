---
logicraft_item: SCREEN-034
type: screen_spec
version: 21
last_updated_at: 2026-08-18T03:30:50.206Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T09:19:05.323Z
sync_session: 20
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-034.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-140]]", "[[API-149]]", "[[API-154]]", "[[API-155]]", "[[API-157]]", "[[API-159]]"]
  required_roles: ["[[ROLE-003]]"]
  realizes_use_cases: ["[[UC-027]]"]
---

# 포털 업로드 라벨링 화면

## route

/portal/uploads/:uldSn/label

## title

포털 업로드 라벨링 화면

## device

responsive

## status

draft

## purpose

PORTAL_USER가 본인 업로드 자산(이미지 1장 또는 영상에서 추출된 프레임)에 수동 라벨링만 수행하는 화면. 라벨링 코어(CanvasShell)를 props 조립으로 재사용하되 노출 도구는 선택/이동/바운딩 박스/폴리곤 4종뿐이며 SAM 분할·SAM 추적·키포인트·오토라벨(YOLO)은 제공하지 않는다(데이터마트 라벨링 화면 SCREEN-005와 도구 구성이 다름). 이미지 자산은 단일 프레임, 영상 자산은 프레임 좌우 네비게이션을 제공하며 저장은 현재 프레임 라벨 전체교체(PUT) 1회다. 라벨 분류는 자유 텍스트가 아니라 활성 라벨 마스터 목록에서 선택한다. 상단에서 라벨 JSON 내보내기와 원본 파일 다운로드를 제공한다. READY 상태가 아닌 자산은 안내만 표시하고 라벨링을 진행할 수 없다. 오토라벨링·SAM2·VLM·검수·버전관리는 여전히 제공하지 않는다. 접근: PORTAL_USER.

## sections

### 헤더 (파일명·프레임 카운트·내보내기/다운로드)

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 원본 파일명

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 프레임 N / 총 M · 이미지 1장

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 라벨 JSON 만 받는다. 작아서 곧바로 끝나므로 취소를 두지 않는다.
- **type**: Button
- **label**: 내보내기(JSON)

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [4]

- **note**: 자산 원본을 그대로 받는다. 영상이면 매우 커질 수 있어 일반 조회보다 긴 제한시간이 필요하고, 받는 중에는 취소할 수 있어야 한다.
- **type**: Button
- **label**: 원본 다운로드

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [5]

- **note**: 원본 파일을 받는 중일 때만 '원본 다운로드' 자리에 나타난다. 누르면 전송을 멈추고 다시 받을 수 있는 상태로 되돌린다. 사용자가 스스로 멈춘 것이므로 실패 안내를 띄우지 않는다.
- **type**: Button
- **label**: 다운로드 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

**description**:

자산 원본 파일명(텍스트 노드) + 프레임 카운트('프레임 N / 총 M' 또는 '이미지 1장'). 우측 '내보내기(JSON)'(라벨 JSON attachment 다운로드, 엔드포인트 GET /portal/uploads/{uldSn}/export) + '원본 다운로드'(GET /portal/uploads/{uldSn}/file) 버튼, 다운로드 중 상호 비활성.

'원본 다운로드'는 자산 파일을 그대로 받는 경로라 영상이면 매우 커질 수 있다. 요청 제한시간은 그 크기를 끝까지 받아낼 수 있는 값이어야 한다 — 일반 조회와 같은 짧은 제한시간을 쓰면 큰 자산은 받을 방법이 없다. 받는 중에는 같은 자리에서 전송을 멈출 수 있게 하고, 취소하면 전송을 중단해 다시 받을 수 있는 상태로 되돌린다. 받다 만 파일은 남기지 않는다. 사용자가 누른 취소는 오류가 아니라 정상 종료이므로 실패 안내를 띄우지 않는다 — 전송이 끊겨 실패한 경우와 한 갈래로 묶으면 스스로 멈춘 사용자에게 연결을 확인하라고 권하게 된다. '내보내기(JSON)'에는 취소를 두지 않는다. 라벨 JSON 은 작아서 취소할 수단이 나타나기 전에 끝나므로 둘 자리가 없다.

**references_apis**:

- API-157
- API-159

**references_features**:

_(empty)_

### 도구바 + 라벨 분류 + 저장

- **role**: navigation
- **layout**: stack

**components**:

#### [1]

- **type**: IconButton
- **label**: 선택

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: IconButton
- **label**: 이동

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: IconButton
- **label**: 바운딩 박스

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **type**: IconButton
- **label**: 폴리곤

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 활성 라벨 마스터 목록(자유 텍스트 아님)
- **type**: Select
- **label**: 라벨 분류

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: role=toolbar 4버튼(선택/이동/바운딩 박스/폴리곤) — SAM 분할·SAM 추적·키포인트·오토라벨 버튼은 렌더되지 않는다. 활성 라벨 마스터(useYn=Y) select(라벨 분류, 미지정=자동/기본). 우측 '저장' 버튼(현재 프레임 라벨 전체교체 PUT, 엔드포인트 PUT /portal/uploads/frames/{uldFrmeSn}/labels — body는 최상위 raw 배열, {items:[...]} 래퍼 아님).

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 라벨링 캔버스

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **type**: Custom
- **label**: 라벨링 캔버스

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: CanvasShell

#### [2]

- **type**: Progress
- **label**: 이미지 로딩 스피너
- **state**: loading

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: CanvasShell을 portalMode로 조립. 컨테이너 크기 측정(ResizeObserver 미의존) 후 프레임 이미지(GET /portal/uploads/frames/{uldFrmeSn}/image = API-149) + 프레임 라벨(GET .../labels)을 로드해 캔버스에 표시. 이미지 blob 로딩 중 중앙 스피너 오버레이.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 프레임 네비게이션 (영상 자산만)

- **role**: footer
- **layout**: stack

**components**:

#### [1]

- **type**: IconButton
- **label**: 이전 프레임

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: N / 총 프레임

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: IconButton
- **label**: 다음 프레임

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: totalFrames>1(영상 자산)일 때만 노출. 이전/다음 프레임 버튼 + '{index+1} / {totalFrames}' 표시, 경계에서 비활성.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 미준비/실패/오류 안내

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **type**: Alert
- **label**: 자산을 불러올 수 없습니다 / 준비 중입니다 / 잘못된 자산 주소입니다
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 자산 상세 로딩 실패(본인 자산 아님 포함) · 자산이 READY가 아닌 경우(FAILED='처리에 실패한 자산입니다', 그 외='아직 준비 중인 자산입니다') · 잘못된 자산 주소(uldSn 미양수)를 role=status 안내로 대체 표시, 캔버스는 렌더하지 않는다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-013

### diff_summary

신규 화면 — 포털 업로드 자산 수동 라벨링(BBOX/POLYGON만). SAM·키포인트·오토라벨·검수·버전관리 미제공(불변).

## surface_kind

web

## consumes_apis

- API-140
- API-149
- API-154
- API-155
- API-157
- API-159

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

- ROLE-003

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-034/main.html
- **label**: 포털 업로드 라벨링 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: 8bcbb4e3117b1eb48d3bc1d6686eb76158e857fd2e68b7bf02c49d3eb81c96b7
- **generated_at**: 2026-08-18T03:30:50.206Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-027

## covered_by_acceptances

_(empty)_
