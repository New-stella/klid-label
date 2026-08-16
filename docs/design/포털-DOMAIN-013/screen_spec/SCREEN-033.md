---
logicraft_item: SCREEN-033
type: screen_spec
version: 13
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:56.581Z
status: NEW
prev_version: null
content_hash: 4e60ccb733a9d0dd5458502600fd0d2a09e41e89cb8fe39f77451d4e34cfacb8
stale: false
raw: ./_raw/SCREEN-033.json
links:
  belongs_to_domain: ["[[DOMAIN-013]]"]
  consumes: ["[[API-139]]", "[[API-142]]", "[[API-151]]", "[[API-161]]", "[[API-163]]", "[[API-166]]", "[[API-169]]", "[[API-171]]"]
  realizes: ["[[UC-027]]"]
  requires: ["[[ROLE-003]]"]
  designs_backward: ["[[SD-026]]"]
  granted_on_backward: ["[[ROLE-003]]"]
  navigates_to_backward: ["[[NAV-002]]"]
  realizes_backward: ["[[MOD-017]]"]
  references_backward: ["[[SEQ-019]]", "[[UC-027]]"]
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

PORTAL_USER가 본인 소유 이미지·영상 자산을 직접 업로드하는 화면. 데이터마트 라벨링과 완전 분리된 별도 파이프라인(LS_PORTAL_* 전용)이며 관제 학습용 배치·오토라벨링·검수·버전관리를 전혀 거치지 않는다. 이미지는 다중 선택 후 클라이언트 사전검증(jpg/jpeg/png, 20MB/장, 50장/요청)을 거쳐 multipart 업로드하고, 영상은 기존 TUS 재개 가능 업로드 엔진을 포털 전용 endpoint(/portal/uploads/tus)로 재사용해 mp4/mov/avi, 최대 5GB까지 청크 업로드한다. 업로드 자산은 UPLOADED→PROCESSING→READY|FAILED 상태로 전이하며(영상은 고정 간격 프레임 추출), 목록에서 상태 배지 + READY 자산의 라벨링 진입(/portal/uploads/{uldSn}/label) + 삭제(PROCESSING 중이면 BE가 409로 거부)를 제공한다. 오토라벨링·SAM2·VLM·검수·버전관리는 이 경로에도 제공되지 않는다. 접근: PORTAL_USER.

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

- **description**: 본인 업로드 자산 목록(GET /portal/uploads). 각 행: 원본 파일명(텍스트 노드, XSS 방어) + 상태 배지(업로드됨/처리중/준비 완료/실패, PROCESSING은 폴링) + 타입·크기·프레임수. READY 자산만 '라벨링' 링크(/portal/uploads/{uldSn}/label) 노출. 삭제 버튼은 PROCESSING 중이면 title 툴팁과 함께 비활성(BE 409 정합), 확인 후 요청.

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

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-033/main.html
- **label**: 포털 업로드 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **source_hash**: cf74cd687e9c21a518f9548230348e16256d0dff8ce23ef981697848df64b705
- **generated_at**: 2026-08-16T09:35:10.864Z
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
