---
logicraft_item: CDIAG-011
type: class_diagram
version: 16
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-02T10:52:38.886Z
status: CHANGED
prev_version: 13
content_hash: abd42f9edc50974ba9fdb40c76a26bee2deb8fa0a9865d468fbd00a0df2e13e5
stale: true
raw: ./_raw/CDIAG-011.json
links:
  belongs_to_domain: ["[[DOMAIN-013]]"]
  depicts: ["[[DFEAT-043]]", "[[DFEAT-044]]", "[[DFEAT-053]]"]
  references: ["[[DFEAT-043]]", "[[DFEAT-044]]", "[[DFEAT-053]]"]
---

# 포털 사용자 라벨 도메인 모델

## theme

neutral

## title

포털 사용자 라벨 도메인 모델

## classes

### PortalUserLabel

- **kind**: aggregate_root

**methods**:

#### updateCoordinates

**params**:

_(empty)_

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: void

#### isOwnedBy

**params**:

- portalUserNo: String

- **is_static**: false
- **visibility**: public
- **is_abstract**: false
- **return_type**: boolean

**attributes**:

#### userLblSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **description**: 포털 작업 저장소가 이 행에 부여하는 식별자. 내려받기 산출물에서 어노테이션 항목의 식별자를 채우는 조달처이며, 이 저장소에 담긴 라벨(포털 사용자가 직접 저장한 것)이 산출될 때 그 값이 된다. 다만 문서 사이의 유일성은 보장하지 않는다 — 다른 프레임 문서가 우연히 같은 값을 가질 수 있다. 문서 안에서 항목을 가리키는 용도이지 영상 전체에서 유일한 키가 아니다.
- **is_readonly**: true

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### portalUserNo

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### srcRawSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### srcDataSrcSn

- **type**: Long
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### lblTypeCd

- **type**: LabelType
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### labelNm

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### pointCn

- **type**: String
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### regDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### mdfcnDt

- **type**: LocalDateTime
- **is_static**: false
- **visibility**: private
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### labelId

- **type**: Long
- **is_static**: false
- **visibility**: private
- **description**: 라벨 마스터를 가리키는 참조값. 데이터마트 원본에서 불러온 라벨이 갖고 있던 분류 연결을 저장 왕복에서 잃지 않기 위한 보존값이며, 내려받기 산출물에서 어노테이션 항목의 분류 식별자를 채우는 조달처다. 외래키를 두지 않는다 — 포털 전용 저장소는 내부 파이프라인과 분리 운영되고 라벨 마스터가 비활성화돼도 포털 사용자의 과거 작업이 지워지면 안 되기 때문이며, 유효성은 조회 시점 결합으로 확인한다. 활성 마스터에 실재하지 않는 참조는 그 값만 비워서 보관하고 저장 자체는 성공시킨다. 값이 없을 수 있고, 라벨명으로 유추해 채우지 않는다.
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

#### trackId

- **type**: String
- **is_static**: false
- **visibility**: private
- **description**: 라벨이 속한 트랙 식별자. 데이터마트 원본에서 불러온 라벨이 갖고 있던 트랙 연결을 저장 왕복에서 잃지 않기 위한 보존값이며, 내려받기 산출물에서 어노테이션 항목의 트랙 식별자를 채우는 조달처다. 데이터 라벨의 같은 이름 항목과 물리명·타입·크기를 그대로 재사용하고 길이 상한은 30자다. 외래키를 두지 않으며 유효성은 조회 시점 결합으로 확인한다. 값이 없을 수 있다 — 트랙에 묶이지 않은 라벨은 비운 채로 둔다. 포털 사용자에게 트랙 번호 변경·병합 수단을 주는 것이 아니다.
- **is_readonly**: false

**implementation**:

##### status

planned

##### modules

_(empty)_

##### records

_(empty)_

##### progress

0

##### subtasks

_(empty)_

##### module_paths

_(empty)_

- **description**: 포털 사용자의 간편 라벨링 작업 데이터. 원본 미수정, 사용자별 적재(데이터마트 미정합). PORTAL_USER_NO로 본인 데이터만 접근. (LS_PORTAL_USER_LABEL) 라벨 마스터 참조(labelId)와 트랙 식별자(trackId)는 포털 사용자에게 새 편집 수단을 주려는 것이 아니라, 데이터마트 원본에서 불러온 라벨이 갖고 있던 연결이 저장 왕복에서 끊기지 않도록 보존하기 위한 것이다. 트랙 번호 변경·병합은 포털에 두지 않는다. 두 값 모두 비어 있을 수 있고 외래키를 두지 않으므로 라벨 마스터와 실선 연관으로 묶지 않고 식별자 보유만으로 표현하며, 참조 무결성은 조회 시점 결합으로 확인한다.

**enum_values**:

_(empty)_

**stereotypes**:

_(empty)_

### LabelType

- **kind**: enum

**methods**:

_(empty)_

**attributes**:

_(empty)_

- **description**: 포털 라벨 도형 유형 코드 — BBOX·POLYGON 만 둔다. 서버 allowlist 가 그 외 값을 400 으로 거부하므로 이 열거에 다른 값을 두지 않는다. 구 값 SEGMENT·TRACK 은 내부 라벨 도메인의 값이며 포털에는 두지 않는다 — TRACK 은 2026-08-03 보안 판정으로 포털에서 제거된 축이다(ADR-013). 구 서술 "내부 label 도메인과 동일 체계"는 폐기한다: 두 축의 값 집합은 같지 않다.

**enum_values**:

- BBOX
- POLYGON

**stereotypes**:

_(empty)_

## description

포털 도메인은 서로 다른 두 저장모델을 갖는다.

[경로 A — 데이터마트 영상 라벨 작업] 포털 회원이 관제 제공 데이터마트 영상을 선택해 기존 라벨을 확인·수정·저장하되 원본(LS_DATA_LBL)을 수정하지 않고 사용자 작업분을 LS_PORTAL_USER_LABEL 에 별도 적재한다. PORTAL_USER_NO 를 IDOR 차단 키로 본인 데이터만 접근한다. ERD-018 기반.

[★경로 B — 본인 자산 업로드 (ADR-013 예외, 2026-07-17 — 이전에 미등재)] 포털 사용자가 직접 올린 자산은 채널 전용 테이블을 따로 두지 않고 공용 원장에 함께 앉는다(ADR-058) — 업로드 마스터는 영상 원장(LS_DATA_RAW), 추출 프레임은 프레임 원장(LS_DATA_SRC), 수동 라벨(BBOX/POLYGON 만)은 라벨 원장(LS_DATA_LBL), 재개 업로드 세션은 공용 업로드 세션 원장(LS_TUS_UPLOAD)이 담는다. 흡수 전 전용 테이블군과의 대응은 ERD-028 이 갖는다. 원장을 공유해도 내부 파이프라인(비식별→마킹→배치→검수)·데이터마트 View 와 섞이지 않는다. ⚠ 다만 그 근거가 더는 「테이블이 다르다」가 아니다 — 근거는 셋이다. ① 출처·소유자로 가른다: 영상 원장의 출처 유형이 PORTAL_ULD 이고 소유자는 PORTAL_USER_NO 다. ② 배치가 발동하지 않는다: 배치는 데이터베이스 트리거가 아니라 앱 이벤트로 시작하는데 포털은 관제 인입과 별개인 자기 이벤트를 쓰므로 같은 원장에 앉아도 파이프라인이 집어가지 않는다. ③ 데이터마트 View 에 도달하지 않는다: 그 뷰의 드라이버는 검수 승인 시점에만 생기는 동결 메타인데 포털 경로에는 검수가 없어, 필터로 막는 것이 아니라 구조적으로 도달 불가다. 흡수 대상은 이 경로의 네 저장소뿐이며 경로 A 의 오버레이 저장소(LS_PORTAL_USER_LABEL)는 흡수하지 않는다 — 그 저장소의 존재 이유가 「저장해도 원본과 데이터마트를 고치지 않는다」는 단방향 보장이라, 라벨 원장에 합치면 소유자 구분을 한 번 놓치는 순간 남의 오버레이가 정본 라벨로 읽힌다. 업로드 상태는 UPLOADED→PROCESSING→READY|FAILED.
★신규 접수는 영상뿐이다 — 이미지 자산의 신규 접수 경로는 닫혔고 이 모델로 새 이미지 자산이 들어오는 입구는 없다(ADR-013). ★자산 종류의 값역에서 이미지 값을 없애지 않는다 — 없애면 이미 적재된 이미지 자산의 행이 판독 불가가 되기 때문이며, 기존 이미지 자산의 조회·다운로드·삭제는 그대로 유지된다. 값역을 소유하는 것은 업로드 마스터 축이라 이 다이어그램에 그 열거를 두지 않으나 같은 이유가 여기에도 적용된다 — 이 모델을 근거로 값역을 정리하지 말 것.

[경계] 두 경로는 서로 참조하지 않는다.
★두 경로 모두 제공하지 않는 것 — 오토라벨링(YOLO)·SAM2 인터랙티브 분할·SAM2 자동추적·키포인트·트랙 번호 변경/병합·검수·버전관리, 그리고 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)이다(ADR-013). SAM2·키포인트 미제공은 2026-08-03 보안 판정으로 서버에서 제거된 축이라 그대로다.
[★제공 범위 확대 (ADR-013 v10, 2026-08-26 확정·구속)] 「미제공」의 축은 외부 서버 연동이지 화면 표시·사용자 수정까지 막는 것이 아니다. 데이터마트 로드분의 메타(촬영환경·프레임 설명·개인정보 판정·시계열 메타)와 이벤트 어노테이션은 포털 작업 화면에 표시하고 포털 사용자가 직접 수정·추가할 수 있으며, 그 결과는 포털 전용 저장소에만 적재되고 데이터마트·원본 동결본을 수정하지 않는다(단방향). 업로드 영상(경로 B)에 한해 이벤트구간 마킹과 AI 증강 연동을 제공한다(증강 요청·현황 조회·결과 확인 창구는 저작도구가 갖고, 파생물은 공용 영상 원장에 새 행으로 앉고 부모 참조가 요청 대상 영상을 가리키며 출처 유형과 소유자는 업로드 자산 것을 그대로 쓴다). ⚠ 구 서술 폐기 — 「VLM·메타 포함 전면 미제공」은 VLM 축을 너무 넓게 적은 것이라 폐기한다(그 서술을 근거로 화면 기능을 막지 말 것). 현재 코드에는 포털 메타·어노테이션 API 가 아직 없다(화면 미구현).
라벨 편집은 두 경로 모두 수동 라벨링(BBOX/POLYGON)만이며, 서버 allowlist(경로 A = PortalLabelService.validateAndNormalizeType, 경로 B = PortalUploadLabelService)가 그 외 도형을 400 으로 거부한다.
경로 B 는 추가로 비식별을 적용하지 않고 고정 간격 프레임 추출만 한다.

## module_name

Portal

## relationships

### [1]

- **to**: LabelType
- **from**: PortalUserLabel
- **kind**: dependency
- **label**: 라벨유형
- **to_multiplicity**: 1
- **from_multiplicity**: 1

## attached_files

_(empty)_

## depicts_dfeats

- DFEAT-043
- DFEAT-044
- DFEAT-053

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

### module_paths

_(empty)_

## referenced_items

_(empty)_

## realizes_features

- DFEAT-043
- DFEAT-044
- DFEAT-053
