---
logicraft_item: SCREEN-025
type: screen_spec
version: 46
last_updated_at: 2026-08-29T00:22:50.595Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-29T00:36:51.585Z
sync_session: 35
stale: false
status: CHANGED
prev_version: 45
raw: ./_raw/SCREEN-025.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-068]]", "[[API-069]]", "[[API-090]]"]
  required_roles: ["[[ROLE-001]]"]
  realizes_use_cases: ["[[UC-031]]", "[[UC-006]]", "[[UC-013]]"]
  acceptance: ["[[AC-055]]"]
---

> ⚠️ **버전 변경 감지 — logicraft v45 → v46**
> change_summary: 정적 HTML 와이어프레임 자동 생성 — 1440×auto (9.8KB)
> ↳ 요약/구현 노트 재검토 후 작성된 코드에 반영. 직전 요약은 git diff 확인.

# 시스템 설정 화면

## route

/manage/settings

## title

시스템 설정 화면

## device

desktop

## status

draft

## purpose

REVIEWER가 운영 파라미터(배치 처리, AI 탐지 추론, 라벨링 정밀도, 비식별 옵션)를 조회·수정하고 외부 연동 헬스를 실시간 모니터링하는 화면. 설정값은 서버가 타입별 범위를 검증하며, 화면은 보조적으로 입력을 범위 내로 보정해 전송한다. 사용자에게 노출되는 모든 문구는 기술 모델명을 쓰지 않는다(예: 'AI 탐지 추론 파라미터'). 접근: REVIEWER 전용.

## sections

### 페이지 헤더

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **type**: Heading
- **label**: 시스템 설정

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 제목 '시스템 설정' + 부제('배치 파라미터 · 외부 연동 헬스'). 검수자(REVIEWER) 전용 화면.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 편집 가능 — 설정 카드

- **role**: main
- **layout**: grid

**components**:

#### [1]

- **type**: Card
- **label**: 배치 처리

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-069

#### [2]

- **type**: Custom
- **label**: 처리 주기 (초)

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.BATCH_INTERVAL_SEC
- **validation**: 서버: 정수 10~3600초. 화면 슬라이더는 10~300초 범위만 노출하고 그 이상은 입력할 수 없다(서버 허용 범위와 화면 슬라이더 범위가 다름 — 화면에서는 300초를 넘는 값을 입력할 수 없으므로 이 불일치는 실제로 드러나지 않는다).
- **custom_name**: Slider

#### [3]

- **type**: Custom
- **label**: 동시 처리 수

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.BATCH_CONCURRENCY
- **validation**: 서버: 정수 1~10. 화면 슬라이더는 1~8 범위만 노출(위와 동일한 이유로 불일치가 드러나지 않는다).
- **custom_name**: Slider

#### [4]

- **type**: Custom
- **label**: 시계열 위탁 전체 건너뛰기

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.batch.vlm.skip-by-default
- **validation**: 켜고 끄는 스위치. 켜면 이후 배치가 외부 시계열 위탁 단계에 진입하기 직전 건너뜀 표식을 자동으로 남기고 외부 호출을 하지 않는다. 아래 사유가 비어 있으면 켤 수 없다. 켜져 있는 동안 들어오는 영상은 전건이 시계열 없이 확정되므로 켜짐 상태를 카드가 눈에 띄게 드러내고, 끄는 것을 잊으면 벤더 연동이 끝난 뒤에도 계속 건너뛴다는 사실을 보조 문구로 알린다. 이미 사람이 남긴 표식은 덮지 않는다.
- **custom_name**: Switch

#### [5]

- **type**: Input
- **label**: 건너뛰기 사유

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.batch.vlm.skip-by-default-reason
- **validation**: 필수. 공백만으로는 수락하지 않으며 비어 있으면 위 스위치를 켤 수 없다. 이 문구가 건너뜀 표식의 사유로 그대로 기록되어, 나중에 그 영상의 시계열이 왜 비어 있는지 되짚는 근거가 된다.
- **placeholder**: 예) 외부 시계열 분석 벤더 연동 전

#### [6]

- **type**: Card
- **label**: AI 탐지 추론 파라미터

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-069

#### [7]

- **type**: Custom
- **label**: Confidence Threshold 0.25~0.80

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.YOLO_CONF_THRESHOLD
- **validation**: 서버·화면 모두 정수 25~80(표시는 /100하여 0.25~0.80).
- **custom_name**: Slider

#### [8]

- **type**: Custom
- **label**: IoU 임계값

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.YOLO_IOU
- **validation**: 서버: 정수 30~80(표시는 0.30~0.80). 화면 슬라이더는 25~80으로 노출되어 25~29 구간을 입력할 수 있으나 이 구간은 서버가 거부한다(화면 표시 범위가 서버 허용 범위보다 넓은 불일치).
- **custom_name**: Slider

#### [9]

- **type**: Card
- **label**: 라벨링 정밀도

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-069

#### [10]

- **type**: Custom
- **label**: 인식 민감도 0.25~0.80

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.YOLO_CONF_THRESHOLD
- **validation**: 서버·화면 모두 정수 25~80(표시는 0.25~0.80). 값이 높을수록 확신도가 높은 객체만 인식한다.
- **custom_name**: Slider

#### [11]

- **type**: Custom
- **label**: 경계 세밀함 0.0~50.0px

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.POLYGON_SIMPLIFY_TOLERANCE
- **validation**: 서버: 소수 0.0~50.0(0.5 단위 입력). 값이 작을수록 폴리곤 경계가 원본에 가깝게 세밀해진다(점 수 증가).
- **custom_name**: Slider

#### [12]

- **note**: 카드별 독립 폼이며 값이 하나라도 변경되어야 활성화된다. 카드 내 여러 필드 중 실제로 변경된 키만 저장 요청에 포함된다.
- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-069

#### [13]

- **note**: 위탁 시 전송하는 비식별 처리 옵션. 전역 1벌
- **type**: Card
- **label**: 비식별 옵션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DeidentConfigCard

#### [14]

- **note**: 색상/모자이크/블러 셋 중 하나. 벤더 정의 밖의 값은 고를 수 없다
- **type**: Select
- **label**: 마스킹 방식

**columns**:

_(empty)_

**options**:

- 색상
- 모자이크
- 블러

- **binds_to**: configs.kpst.deid.masking-type

#### [15]

- **note**: 실수. 감지 영역을 얼마나 넓게 덮을지
- **type**: Custom
- **label**: 마스킹 범위 0.5~2.0

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.kpst.deid.masking-range

#### [16]

- **note**: 비식별 서버가 처리 프레임을 자기 DB 에 남길지
- **type**: Custom
- **label**: 프레임 저장 여부

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: configs.kpst.deid.db-save

- **description**: 설정 키-값 목록을 로드해 카드를 2열 그리드로 표시한다. 로딩 시 스켈레톤, 에러 시 에러 상태. 각 카드는 독립적인 폼으로 값이 바뀌었을 때만 저장 버튼이 활성화되며, 저장은 카드 안에서 실제로 바뀐 키에 한해서만 개별 요청된다. 서버에 일괄 저장 API 가 없어 키별로 별도 요청한다. ①배치 처리: 처리 주기(초)/동시 처리 수/시계열 위탁 전체 건너뛰기. ②AI 탐지 추론 파라미터: Confidence Threshold/IoU 임계값. ③라벨링 정밀도: 인식 민감도(Confidence Threshold와 같은 설정값을 공유)/경계 세밀함. ④비식별 옵션: 마스킹 방식(색상/모자이크/블러)/마스킹 범위(0.5~2.0)/프레임 저장 여부. 전역 1벌이며 저장한 값은 그 다음부터 새로 위탁하는 영상에 적용된다. 저장 실패 시 서버가 범위·타입 오류를 반환하며 화면은 범용 실패 토스트만 노출한다(서버 에러 메시지는 필드별로 구분되지 않는 공통 문구다). 성공 시 카드별 토스트. ⑤AI 최대 대기 상한 — ⚠**이 화면에 아직 카드가 없다**(값·저장 창구는 서버에 있고 조작 표면만 미구현이라 지금은 설정 API 로만 바꾼다). ①~④ 와 달리 화면 구성이 아니라 **채워지지 않은 요구**이며 그래서 컴포넌트 목록에도 없다 — 어긋나 보이는 것은 누락이 아니라 그 사실을 적어 둔 것이다. 배포 없이 운영자가 조정하라고 연 손잡이라 화면이 있어야 하므로 요구를 지우지 말 것. 규정: 라벨링 화면의 AI 보조 작업이 한 실행을 얼마나 기다릴지 정한다. 작업 종류(AI 탐지·AI 분할·AI 추적·AI 자동 추적)별로 고정분과 프레임당 가산분을 두고, 계산값의 절대 상한을 함께 둔다. 각 값의 하한은 서버가 그 작업에 정당하게 쓸 수 있는 최악 소요에서 정해지며 그보다 낮은 값은 저장되지 않는다. 절대 상한의 기본값은 앞단이 응답을 기다려 주는 시간과 같아, 늘리려면 앞단도 함께 늘려야 한다.

**references_apis**:

- API-068
- API-069

**references_features**:

- FEAT-007

### 실시간 모니터링 — 외부 연동 헬스

- **role**: side
- **layout**: list

**components**:

#### [1]

- **type**: List
- **label**: 외부 연동 상태 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **binds_to**: health.components

#### [2]

- **type**: Custom
- **label**: 정상/연결 끊김/서비스 중단

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: StatusBadge

#### [3]

- **note**: 컴포넌트 상세 정보에 URL 이 포함된 경우 보조 텍스트로 함께 표시한다. 없으면 표시하지 않는다.
- **type**: Custom
- **label**: 연동 상세 URL (있을 때만)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: HealthDetailUrl

#### [4]

- **note**: 컴포넌트별 상태 목록이 비어 있을 때 개별 항목 대신 전체 상태 1행으로 대체 표시한다.
- **type**: Custom
- **label**: 전체 상태 요약 (컴포넌트 목록 없을 때)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: OverallStatusRow

- **description**: GET /manage/health 를 5초 폴링한다(read-only). 응답이 돌려주는 컴포넌트(비식별 서버/AI 추론 서버/데이터베이스)별 상태(UP/DOWN 등)를 표시한다. 목록은 응답이 돌려준 컴포넌트를 그대로 그리며 화면이 점검 대상 목록을 따로 갖지 않는다. 컴포넌트 목록이 비어 있으면 전체 상태 요약 1행으로 대체 표시한다. 상태별 색상 배지(정상/연결 끊김/서비스 중단). 편집 불가, actuator/health 실시간 조회 안내 문구.

**references_apis**:

- API-090

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-046

### change_kind

- capability-add

### diff_summary

2차 신규 시스템 설정(정밀도 조절, FEAT-007)

## surface_kind

web

## consumes_apis

- API-068
- API-069
- API-090

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-054
- IMPREC-163

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-27T20:57:49.711Z

### module_paths

_(empty)_

## required_roles

- ROLE-001

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-025/main.html
- **label**: 시스템 설정 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 6c0d3f84085078e36f61b149e90d98f359d0fa86fa1233db775384e3dd5c5063
- **generated_at**: 2026-08-29T00:22:50.595Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-031
- UC-006
- UC-013

## covered_by_acceptances

- AC-055
