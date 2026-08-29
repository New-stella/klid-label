---
logicraft_item: SCREEN-033
type: screen_spec
version: 23
last_updated_at: 2026-08-27T10:18:16.290Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T01:19:52.362Z
sync_session: 36
stale: false
status: UNCHANGED
prev_version: null
raw: ./_raw/SCREEN-033.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-139]]", "[[API-142]]", "[[API-151]]", "[[API-163]]", "[[API-166]]", "[[API-169]]", "[[API-171]]", "[[API-161]]"]
  required_roles: ["[[ROLE-003]]"]
  realizes_use_cases: ["[[UC-027]]"]
---

# 포털 업로드 화면

## route

/portal/uploads

## title

포털 업로드 화면

## device

responsive

## status

draft

## purpose

PORTAL_USER가 본인 소유 이미지·영상 자산을 직접 업로드하는 화면. 데이터마트 라벨링과 완전 분리된 별도 파이프라인(LS_PORTAL_* 전용)이며 관제 학습용 배치·오토라벨링·검수·버전관리를 전혀 거치지 않는다. 이미지는 다중 선택 후 클라이언트 사전검증(jpg/jpeg/png, 20MB/장, 50장/요청)을 거쳐 multipart 업로드하고, 영상은 기존 TUS 재개 가능 업로드 엔진을 포털 전용 endpoint(/portal/uploads/tus)로 재사용해 mp4/mov/avi, 최대 5GB까지 청크 업로드한다. 업로드 자산은 UPLOADED→PROCESSING→READY|FAILED 상태로 전이하며(영상은 고정 간격 프레임 추출), 목록에서 상태 배지 + 만료 예정일 + READY 자산의 라벨링 진입(/portal/uploads/{uldSn}/label) + 삭제(PROCESSING 중이면 BE가 409로 거부)를 제공한다. 이 경로의 자산은 비식별 처리를 거치지 않아 가공되지 않은 개인정보가 그대로 보관되므로 보존기간을 짧게 두고, 기간이 지나면 저장 행과 파일을 함께 삭제한다. 사용자가 삭제 시점을 예측할 수 있도록 목록의 각 자산에 만료 예정일을 날짜까지 표기한다. 업로드한 영상 자산에 한해 AI 증강 연동을 요청할 수 있으며, 요청 자리는 목록의 자산별 액션이다 — 서버가 외부로 보내는 비동기 위탁이라 포털 사용자가 외부 추론 엔드포인트를 직접 호출하지 않는다. 미제공은 오토라벨링(YOLO)·SAM2 인터랙티브 분할·자동추적·키포인트·검수·버전관리이며, 시계열 축의 미제공은 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)을 뜻한다. 접근: PORTAL_USER.

## sections

### 이미지 업로드

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: accept=image/jpeg,image/png,.jpg,.jpeg,.png, multiple
- **type**: Input
- **label**: 이미지 파일 (다중 선택)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 정책 안내 (20MB/장 · 50장/요청)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: Alert
- **label**: 검증 오류 목록
- **state**: error

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 선택 0건 또는 업로드 중이면 disabled
- **type**: Button
- **label**: 이미지 업로드

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 다중 파일 선택(accept image/jpeg,image/png) → 클라이언트 사전검증(validateImageFiles: 확장자·개수 50장·크기 20MB/장) 실패 시 role=alert 에러 목록. 검증 통과분만 '이미지 업로드' 버튼으로 multipart POST(엔드포인트: /portal/uploads/images). 업로드 중 진행률(%) 표시, 성공 시 선택 초기화.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 영상 업로드 (TUS)

- **role**: main
- **layout**: form

**components**:

#### [1]

- **note**: accept=video/mp4,video/quicktime,video/x-msvideo,.mp4,.mov,.avi. 업로드 중 disabled
- **type**: Input
- **label**: 영상 파일

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Text
- **label**: 정책 안내 (mp4/mov/avi · 최대 5GB · 재개 가능 업로드)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: Progress
- **label**: 업로드 진행률

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 파일 미선택 또는 업로드 중이면 disabled, 진행률 텍스트 병기
- **type**: Button
- **label**: 영상 업로드

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 단일 영상 파일 선택(accept mp4/mov/avi) 후 '영상 업로드' 클릭 시 TUS 재개 가능 업로드(endpointBase=/portal/uploads/tus)으로 청크 업로드 시작. 진행 중 progressbar(0~100%) + 취소 불가 표시, 실패 시 에러 메시지, 업로드 중 파일 input 비활성.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 업로드 자산 목록

- **role**: main
- **layout**: list

**components**:

#### [1]

- **type**: List
- **label**: 업로드 자산 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: uploads

#### [2]

- **type**: Badge
- **label**: 상태 배지 (업로드됨/처리중/준비 완료/실패)

**columns**:

_(empty)_

**options**:

- UPLOADED
- PROCESSING
- READY
- FAILED

#### [3]

- **note**: READY 자산만 노출 → /portal/uploads/{uldSn}/label
- **type**: Link
- **label**: 라벨링

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [4]

- **note**: PROCESSING이면 비활성 + 툴팁. window.confirm 후 요청, BE 409(처리중)는 안내 문구로 매핑
- **type**: Button
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [5]

- **note**: 상태 배지 옆에 행마다 표기. 응답 expiresAt 을 날짜까지만 표기하고 시각은 생략. 값이 없으면 빈칸. 임박 강조 없음. 화면이 보관하지 않고 매 조회 값을 그대로 표시.
- **type**: Text
- **label**: 만료 예정일 (만료: YYYY-MM-DD)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: uploads[].expiresAt

#### [6]

- **note**: 영상 자산만 노출 — 이미지 자산에는 두지 않는다
- **type**: Button
- **label**: AI 증강 요청

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 본인 업로드 자산 목록(GET /portal/uploads). 각 행: 원본 파일명(텍스트 노드, XSS 방어) + 상태 배지(업로드됨/처리중/준비 완료/실패, PROCESSING은 폴링) + 타입·크기·프레임수 + 만료 예정일. READY 자산만 '라벨링' 링크(/portal/uploads/{uldSn}/label) 노출. 삭제 버튼은 PROCESSING 중이면 title 툴팁과 함께 비활성(BE 409 정합), 확인 후 요청. 만료 예정일은 응답의 expiresAt 을 '만료: YYYY-MM-DD' 형식으로 날짜까지만 표기한다(시각은 표기하지 않는다). expiresAt 이 없는 자산은 만료 예정일 자리를 비운다 — 업로드됨·처리중 자산은 아직 삭제 대상이 아니라 값이 내려오지 않는다. 만료 예정일이 내려오는 것은 준비 완료·실패 자산이다. 이 값은 조회 시점의 보존기간 설정으로 계산되어 응답에 실려 오므로 화면이 따로 보관하지 않고 받은 값을 그대로 표시하며, 매 조회마다 갱신한다. 만료가 가까운 자산을 색·아이콘으로 강조하는 표기는 두지 않는다. 준비 완료 상태인 영상 자산 행에는 「AI 증강 요청」 액션을 함께 둔다 — 이미지 자산은 대상이 아니고, 아직 준비되지 않은 영상에도 두지 않는다(라벨링 링크와 같은 규칙이라, 그 자산에서 할 수 없는 액션은 비활성으로 두지 않고 아예 노출하지 않는다). 본인이 올린 자산이면 요청할 수 있으며 검수를 통과했는지는 묻지 않는다 — 이 경로에는 검수가 없다. 이 요청은 서버가 외부로 보내는 비동기 위탁이라 누른 즉시 결과가 나오지 않으며, 포털 사용자가 외부 추론 엔드포인트를 직접 호출하지 않는다.

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

신규 화면 — 포털 사용자 본인 자산(이미지/영상) 업로드. 내부 파이프라인·데이터마트와 완전 분리(LS_PORTAL_* 전용), 오토라벨링·검수·버전관리 미제공.

## surface_kind

web

## consumes_apis

- API-139
- API-142
- API-151
- API-163
- API-166
- API-169
- API-171
- API-161

## implementation

### status

implemented

### modules

- MOD-017
- MOD-021

### records

- IMPREC-024

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-17T22:48:30.685Z

### module_paths

_(empty)_

## required_roles

- ROLE-003

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-033/main.html
- **label**: 포털 업로드 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: ba40f2b84ce9b297dad36434508fcc0ba6b24b4c8e3c7e23293aabd6b28975de
- **generated_at**: 2026-08-27T10:04:44.706Z
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
