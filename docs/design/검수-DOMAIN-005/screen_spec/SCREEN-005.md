---
logicraft_item: SCREEN-005
type: screen_spec
version: 102
domain: DOMAIN-010
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-28T11:36:58.349Z
status: CHANGED
prev_version: 100
content_hash: 46868136b2a5da13d3a8e74b77b3efa07eaf9244692fcdfa688b87b438182d19
stale: true
raw: ./_raw/SCREEN-005.json
links:
  belongs_to_domain: ["[[DOMAIN-010]]"]
  consumes: ["[[API-012]]", "[[API-018]]", "[[API-019]]", "[[API-020]]", "[[API-021]]", "[[API-022]]", "[[API-023]]", "[[API-024]]", "[[API-032]]", "[[API-034]]", "[[API-035]]", "[[API-036]]", "[[API-066]]", "[[API-067]]", "[[API-093]]", "[[API-102]]", "[[API-103]]", "[[API-104]]", "[[API-105]]", "[[API-123]]", "[[API-124]]", "[[API-125]]", "[[API-126]]", "[[API-127]]", "[[API-128]]", "[[API-129]]", "[[API-132]]", "[[API-133]]", "[[API-134]]", "[[API-135]]", "[[API-168]]", "[[API-170]]", "[[API-172]]", "[[API-173]]", "[[API-177]]", "[[API-178]]", "[[API-182]]", "[[API-183]]", "[[API-184]]", "[[API-193]]", "[[API-195]]", "[[API-196]]", "[[API-197]]", "[[API-204]]"]
  implements: ["[[IMPREC-021]]"]
  migrated_from: ["[[LEGACY-004]]"]
  realizes: ["[[UC-004]]", "[[UC-005]]", "[[UC-006]]", "[[UC-007]]", "[[UC-008]]", "[[UC-021]]", "[[UC-022]]", "[[UC-034]]"]
  references: ["[[API-018]]", "[[API-019]]", "[[API-020]]", "[[API-021]]", "[[API-024]]", "[[API-032]]", "[[API-066]]", "[[API-067]]", "[[API-093]]", "[[API-102]]", "[[API-103]]", "[[API-104]]", "[[API-105]]", "[[API-123]]", "[[API-124]]", "[[API-125]]", "[[API-126]]", "[[API-127]]", "[[API-128]]", "[[API-129]]", "[[API-132]]", "[[API-133]]", "[[API-134]]", "[[API-135]]", "[[API-182]]", "[[API-195]]", "[[API-196]]", "[[API-197]]", "[[FEAT-007]]"]
  requires: ["[[ROLE-001]]", "[[ROLE-002]]"]
  designs_backward: ["[[SD-002]]"]
  granted_on_backward: ["[[ROLE-002]]"]
  navigates_to_backward: ["[[NAV-001]]"]
  realizes_backward: ["[[MOD-006]]", "[[MOD-010]]", "[[MOD-021]]"]
  references_backward: ["[[ADR-015]]", "[[TEST-002]]", "[[UC-004]]", "[[UC-005]]", "[[UC-006]]", "[[UC-007]]", "[[UC-008]]", "[[UC-021]]", "[[UC-022]]", "[[UC-034]]"]
---

# 라벨링 캔버스 화면

## route

/label/:id

## title

라벨링 캔버스 화면

## device

desktop

## status

draft

## purpose

라벨링 캔버스 화면(경로 변수 srcSn=프레임 일련번호). 바운딩 박스·폴리곤·세그멘테이션·AI 추적 편집 + COCO-17 키포인트(포즈 스켈레톤) 편집, 속성 입력, AI 보조(AI 탐지·AI 분할·AI 추적), 프레임 설명(NIA image.description) 입력, 시계열 메타(VLM) 편집, 이벤트 어노테이션(VQA/CoT) 편집, 프레임 폐기·복원, 트랙 편집(삭제/분할/머지), 정밀도 조절. 좌측은 그리기 도구와 보기 조작, 중앙은 캔버스, 우측 고정 패널은 '객체/메타/이슈' 3탭으로 구성한다 — 객체 탭은 객체 목록·속성·이미지 보정·라벨링 투명도, 메타 탭은 촬영환경·개인정보(영상축/프레임축)·프레임 설명·시계열 메타·이벤트 어노테이션을 세로로 나열하고, 이슈 탭은 검수자↔작업자 이슈 소통 창구다(미해소 문의 건수 배지 병기, 영상 정보가 없으면 이용 불가 안내). AI 탐지는 검출 형태(박스/폴리곤)와 대상 라벨(마스터 중 AI 검출 클래스 매핑분만 선택 가능)을 지정해 실행한다(API-124). 검수 승인으로 만들어진 산출 버전이 둘 이상이면 화면에 들어올 때 어느 버전에서 편집을 시작할지 고르는 모달을 먼저 띄운다 — 고른 버전은 영상 전체가 화면에 올라오기만 하고 저장을 눌러야 확정된다. 접근: REVIEWER/WORKER.

## sections

### 라벨링 헤더 바

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: 화면을 벗어나는 모든 경로(닫기·프레임 이동·새로고침)는 미저장 변경이 있으면 공통 확인 절차를 거친다.
- **type**: Button
- **label**: × 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [2]

- **type**: Heading
- **label**: CCTV명 / 프레임명

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 이벤트가 있을 때만 노출
- **type**: Badge
- **label**: 이벤트 유형

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 저장 상태만 나타내는 텍스트다(버튼 아님).
- **type**: Text
- **label**: ● 편집 중 / ✓ 저장됨

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 내부 채널의 WORKER/REVIEWER만. 사유 입력 후 신고한다. 파생영상과 한번이라도 검수가 완료된 영상에는 버튼을 비활성화하고 사유를 안내한다. 승인 여부는 지금 상태가 아니라 이력으로 본다 — 검수 완료 뒤 다시 제출해 상태가 내려간 구간에도 비활성이다.
- **type**: Button
- **label**: 비식별 누락 신고

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-032

#### [6]

- **note**: WORKER만. 미저장 변경이 있으면 저장 후 제출/무시하고 제출/취소 중 하나를 고르는 확인 절차를 먼저 거친다.
- **type**: Button
- **label**: 검수제출

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

#### [7]

- **note**: 작업자 본인. 검수 제출 후 검수가 아직 시작되지 않은(검수 대기) 상태에서만 노출되며, 누르면 제출을 취소하고 작업 상태로 되돌린다. 검수가 시작되었거나 이미 완료·반려된 상태에서는 노출하지 않는다.
- **type**: Button
- **label**: 제출 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

#### [8]

- **note**: 이미 검수가 제출되어 대기 중이거나 검수가 진행 중이면 제출 버튼을 비활성화하고 사유를 안내한다. 검수 완료된 영상을 다시 제출하는 경우에는 버튼 문구가 '재검수 제출'로 바뀌어 완료본을 다시 건드린다는 것을 알린다.
- **type**: Text
- **label**: 검수제출 상태 안내

**columns**:

_(empty)_

**options**:

_(empty)_

#### [9]

- **note**: 비식별 누락 신고 접수 등으로 영상이 재비식별 처리 대기 상태가 되면 헤더 아래에 상시 안내 배너를 띄운다. 처리가 끝날 때까지 라벨 수정·저장이 제한된다.
- **type**: Text
- **label**: 재비식별 대기 안내 배너

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 풀스크린 상단 56px 높이의 라이트 톤 헤더바(밝은 배경 — 다른 화면 요소와 동일한 톤을 쓰고 어두운 배색은 쓰지 않는다). 좌: 닫기 + CCTV명/프레임명 + 이벤트 배지. 중앙: 저장 상태 표시(● 편집 중 / ✓ 저장됨). 우: 비식별 누락 신고 버튼(내부 채널의 WORKER/REVIEWER만) + 검수제출 버튼(WORKER만). 저장은 두 갈래다 — 평상시에는 프레임 단위로 작업본만 갱신하고(API-019) 버전 스냅샷은 만들지 않는다(스냅샷은 검수 승인 시점에만 만든다). 로드 버전을 불러온 뒤에는 영상 전체를 한 번에 확정한다(API-196) — 일부 프레임만 저장하면 한 영상 안에 서로 다른 시점의 프레임이 섞이기 때문이다. 어느 쪽이든 낙관적 동시성 토큰(labelVersion)을 함께 보낸다 — 보내지 않으면 전량 교체 저장이라 다른 사용자의 라벨이 조용히 삭제될 수 있다. 프레임 폐기와 복원도 이 저장에 함께 묶인다 — 누르면 화면 표시만 바뀌고 저장을 눌러야 확정되며 저장하지 않고 떠나면 되돌아간다. 비식별 누락 신고 구간(DE_IDNTF_YN='F')에서는 라벨 저장을 412 로 차단하며, 이 차단은 작업락 상태와 무관하게 항상 적용한다(작업락은 일정 시간 후 자동 해제되지만 신고 상태는 해소 절차 전까지 유지되기 때문). 재비식별 대기 잠금 상태에서는 저장을 비활성화한다.

**references_apis**:

- API-019
- API-032
- API-196

**references_features**:

_(empty)_

### 좌측 도구바

- **role**: navigation
- **layout**: stack

**components**:

#### [1]

- **note**: 클릭으로 전환. 별도 단축키 없음.
- **type**: Button
- **label**: 선택 / 이동

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Button
- **label**: 바운딩 박스 (B)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **type**: Button
- **label**: 폴리곤 (P)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 클릭/박스 프롬프트 분할. 내부 채널만 제공.
- **type**: Button
- **label**: AI 분할 (G)

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-093

#### [5]

- **note**: COCO-17 포즈 스켈레톤.
- **type**: Button
- **label**: 스켈레톤 (K)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 현재 프레임 전체 객체검출 대상 선택 다이얼로그를 연다.
- **type**: Button
- **label**: AI 탐지

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-124

- **description**: 좌측 세로 아이콘 도구바(라이트 톤 — 어두운 배색을 쓰지 않는다). 그리기 도구는 선택/이동, 바운딩 박스(B), 폴리곤(P), AI 분할(G), 키포인트(K)다. 바운딩 박스·폴리곤·AI 분할은 클릭하면 먼저 라벨 선택 모달을 열고, 라벨을 고른 뒤에야 해당 도구 모드로 들어간다 — 라벨 없이 그리기가 시작되지 않는다. 모달은 검색창 + 목록(색상 표시 + 라벨명 + 앞 9개 항목의 숫자 단축키 배지)으로 구성하고, 모달이 열려 있는 동안 숫자키 1~9 로 즉시 선택할 수 있다. AI 분할 모달 하단에는 조작 안내와 경계 세밀함(정밀도) 조절을 함께 둔다. 도구 아래 별도 액션으로 'AI 탐지'(현재 프레임 전체 객체검출 대상 선택 다이얼로그, API-124)를 둔다. 이어서 보기 조작(좌/우 90° 회전 — 회전 중에는 그리기 도구를 잠근다, 화면 맞춤, 영역 확대)과 그리드 표시 토글을 두고, 맨 아래 고정 위치에 단축키 안내를 둔다(그룹별 단축키를 호버로 보여준다 — '단축키 안내' 섹션 참조). 도구 전환 자체는 클라이언트 상태만 바꾼다(API 호출 없음) — AI 분할은 캔버스 클릭/박스 프롬프트 시 API-093 을 호출한다. 삭제·실행 취소·다시 실행·저장은 캔버스 상단 옵션바와 단축키로 제공한다('캔버스 상단 옵션바' · '단축키 안내' 섹션 참조).

**references_apis**:

- API-124
- API-093
- API-020

**references_features**:

_(empty)_

### 라벨 선택 모달

- **role**: side
- **layout**: list

**components**:

#### [1]

- **note**: 모달 안의 라벨 목록이다. 각 행은 색상 표시 + 라벨명으로 구성하고 앞 9개 항목에는 숫자 단축키 배지(1~9)를 붙인다.
- **type**: List
- **label**: 라벨 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-024

- **description**: 도형 도구(바운딩 박스/폴리곤/AI 분할)를 클릭하는 시점에 여는 라벨 선택 모달이다(조작 순서: 도구 클릭 → 라벨 선택 → 드로잉). 모달은 활성 라벨만 정렬순으로 나열하고(GET /v1/manage/labels, useYn='Y' 필터, sortNo asc), 색상 표시 + 라벨명 + 앞 9개 항목의 숫자 단축키(1~9)를 함께 보여주며, 검색창으로 좁힐 수 있다(라벨 마스터 전체가 노출돼 개수가 많을 수 있음). 라벨을 고르면 모달이 닫히고 해당 도구 모드로 들어가며, 모달 바깥을 클릭해 취소하면 도구를 활성화하지 않고 이전 상태로 되돌아간다.

**references_apis**:

- API-024

**references_features**:

_(empty)_

### 라벨링 캔버스

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **note**: ImageLayer + LabelsLayer + OverlayLayer
- **type**: Custom
- **label**: 라벨링 캔버스

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: KonvaCanvasStage
- **triggers_api**: API-021

#### [2]

- **note**: 선택한 객체를 다음 프레임들에 형태 변경 없이 전파한다. 우측 패널 '객체' 탭에서 대상 객체를 펼쳤을 때 노출하는 버튼으로 실행한다.
- **type**: Custom
- **label**: AI 추적

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: AiTrackAction
- **triggers_api**: API-020

#### [3]

- **note**: 저장·불러오기·AI 탐지/분할/추적처럼 시간이 걸리는 작업이 진행되는 동안 캔버스 위에 무엇이 진행 중인지 보여주고 그 자리에서 즉시 취소할 수 있다. 아주 짧게 끝나는 작업에는 표시가 깜빡이지 않도록 약간의 지연 후에 나타난다. 작업명·경과 시간과 함께 그 실행의 최대 대기 상한을 보여 끝을 가늠하게 한다 — 상한은 서버가 작업 종류별로 내려준 값에서 구하며 화면이 자체 상수로 정하지 않는다. 퍼센트는 만들지 않는다. 추론 서버가 중간 진행을 알려주지 않아 지어낸 값이 되기 때문이다. 뒤따르는 프레임을 훑는 작업은 요청이 끝날 때마다 처리한 프레임 수와 전체를 갱신해 보여준다. 취소는 진행 중일 때만 나타나며 전송을 끊고 서버에도 멈추라고 알린다. 사용자 취소는 오류가 아니라 정상 종료라 실패 안내를 띄우지 않고, 취소한 뒤 같은 작업을 다시 실행할 수 있다. 대기 상한을 넘긴 실행은 결과를 조용히 버리지 않고 실패로 알린 뒤 편집 잠금을 풀어 다시 실행할 수 있게 한다.
- **type**: Custom
- **label**: 작업 진행 표시

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ProgressOverlay

#### [4]

- **note**: 프레임 이미지를 불러오지 못하면 캔버스 위에 실패 사실과 원인 힌트(권한 없음·파일 없음·비식별 재처리 대기 등)를 알린다. 이미지가 없어도 캔버스와 도구는 계속 조작 가능한 상태로 둔다.
- **type**: Text
- **label**: 프레임 이미지 로드 실패 안내

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 폐기한 프레임에서는 캔버스 위에 폐기 상태임을 알리고 그리기와 편집 도구를 잠근다. 라벨은 지우지 않고 그대로 보여주며, 복원하면 다시 편집할 수 있다.
- **type**: Text
- **label**: 폐기 프레임 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 메인 라벨링 캔버스. 컨테이너 크기를 자동 측정해 캔버스 크기에 반영한다. 현재 프레임 이미지는 프레임 이미지 조회(GET /v1/frames/{srcSn}/image)를 인증 헤더를 실어 받아 표시한다 — 주소를 이미지 태그에 바로 물리면 인증이 실리지 않아 401 이 난다. ★그 응답은 기본이 비식별본이다 — 원본은 검수자가 raw=true 를 명시할 때만 나가고, 작업자가 raw=true 를 보내면 서버가 무시해 비식별본을 강제한다. 화면에는 원본 열람 동선을 두지 않는다(raw=true 는 API 로만 존재). 해상도·증강 파생 프레임은 원본 픽셀이 실재하지 않아 비식별 전용 경로 GET /v1/frames/{srcSn}/deid-image 를 쓴다(원본 폴백 없음, 없으면 404). 비식별 누락 신고 구간에는 프레임 이미지·라벨 조회가 412 로 끊기고, 게이트가 걸린 미디어 응답은 Cache-Control: no-store 다. 활성 도구(선택 / 바운딩 박스 / 폴리곤 / AI 분할 / 키포인트)로 객체를 그려 목록에 추가한다. 라벨 좌표·색상은 API-018 응답에서 정규화한다(BBOX / POLYGON / MASK, 출처 MANUAL / AUTO_YOLO / AUTO_SAM2, 보간 lblSrcCd). AI 추적은 선택한 객체를 다음 N프레임에 전파한다(API-020, 상한 있음, 저장하지 않고 좌표만 반환). 우측 패널 '객체' 탭에서 대상 객체를 펼쳤을 때 노출되는 버튼으로 실행한다. AI 작업의 취소는 두 갈래를 함께 쓴다 — 화면은 전송을 끊고, 요청에 실어 보낸 취소 식별자로 서버에 별도 취소 요청을 보내 진행 중인 추론을 멈추게 한다(API-204). 연결을 끊는 것만으로는 서버가 그것을 알 수 없어 추론이 끝까지 돌기 때문이다. 뒤따르는 프레임을 나눠 보낼 때는 응답이 밝힌 값으로 이어 보내기를 판단하고 안내 문구를 파싱하지 않으며, 이어 실을 값은 서버가 준 것을 그대로 쓴다. 진행이 없는 응답이면 되풀이를 끊는다.

**references_apis**:

- API-018
- API-021
- API-020

**references_features**:

_(empty)_

### 우측 객체·속성·메타 패널

- **role**: side
- **layout**: stack

**components**:

#### [1]

- **note**: 라벨명 그룹화 트리 + 선택/삭제
- **type**: Custom
- **label**: 객체 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ObjectClassTree
- **triggers_api**: API-018

#### [2]

- **note**: 행을 펼쳤을 때 노출하는 라벨 변경 드롭다운(마스터 팔레트 옵션).
- **type**: Select
- **label**: 라벨

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-024

#### [3]

- **note**: 오토라벨 객체에만 표시.
- **type**: Custom
- **label**: 신뢰도

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ConfidenceBar

#### [4]

- **note**: BBOX 객체의 X/Y/W/H — 이미지 경계로 clamp.
- **type**: Input
- **label**: X/Y/W/H 좌표

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 밝기·대비·라벨 투명도·작업(선택 중인 객체) 투명도를 실시간으로 조절하는 슬라이더와 초기화 버튼이다. 값은 화면 표시에만 적용되고 저장되지 않으며(세션 동안만 유지), 라벨 데이터 자체를 바꾸지 않는다. 포털 채널에서도 동일하게 제공된다.
- **type**: Custom
- **label**: 이미지·라벨 표시 조절

**columns**:

_(empty)_

**options**:

- brightness
- contrast
- labelOpacity
- activeOpacity

- **custom_name**: DisplayAdjustPanel

#### [6]

- **note**: 메타 탭 안에 함께 나열되는 패널이다. 편집 가능한 항목(API-066 items 목록, 최대 2000자)만 이 필드에서 수정한다.
- **type**: Textarea
- **label**: 시계열 메타 — 서술 전문

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-066

#### [7]

- **note**: 메타 탭 안에 함께 나열되는 패널이다. 서술 전문(items)만 저장 대상이며, 영상 기술 정보는 저장 요청에 포함하지 않는다(포함 시 400).
- **type**: Button
- **label**: 시계열 메타 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-067

#### [8]

- **note**: API-066 technicalMeta 목록(video.* ffprobe 기술메타) — 편집 불가, 참고용 표시만.
- **type**: KeyValue
- **label**: 영상 기술 정보(읽기전용)

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 우측 고정 패널은 '객체 / 메타 / 이슈' 3탭으로 구성한다(이슈 탭은 영상 정보가 있을 때 온전히 동작한다 — '이슈 소통 패널' 섹션 참조). 이 섹션은 '객체' 탭의 내용을 다룬다. (1) 객체 목록: 목록 상단에 현재 프레임의 객체 수를 표시한다. 라벨명으로 그룹화, 그룹 펼치기/접기, 행별 출처 아이콘(보간/자동/수동), 형태(BBOX/POLYGON) 표기, 선택·가리기·잠금·복사·삭제(메뉴). 트랙을 가진 객체는 메뉴에 트랙 편집(번호변경·분할·삭제) 항목을 추가한다('트랙 편집' 섹션 참조). 키포인트 인스턴스는 같은 목록 하단에 별도 그룹으로 편입한다. (2) 행을 펼치면 객체 속성을 인라인으로 노출한다 — 라벨 변경 드롭다운, 생성출처+낮은신뢰도 배지, 신뢰도 막대(오토라벨만), BBOX 좌표 편집(이미지 경계로 clamp) 또는 폴리곤 정점 수, AI 추적 실행 버튼(선택 객체를 다음 프레임들에 전파). (3) 객체 탭 하단에는 이미지 보정(밝기/대비, 보기 전용)과 라벨링 옵션(라벨/작업 투명도, 보기 전용)을 고정 노출한다. ★라벨 표시 색상의 판정 순서 — ①서버가 내려준 라벨 색상(유효한 형식일 때만) ②마스터 팔레트에서 라벨 식별자(labelId)로 매칭한 색상 ③매칭 실패 시 고정 폴백색. 그룹 헤더의 대표색은 배열 순서와 무관하게 정한다 — 라벨 식별자가 가장 작은 항목을 대표로 삼고, 식별자가 없는 미연결 항목은 뒤로 두며, 식별자가 같거나 모두 미연결이면 객체 식별자 오름차순으로 결정한다. 그룹핑 키가 라벨명(문자열)이라 같은 라벨명을 가리키는 서로 다른 라벨 식별자가 한 그룹에 섞일 수 있는데, 첫 항목을 대표로 쓰면 정렬·재조회로 순서만 바뀌어도 대표색이 흔들리기 때문이다. 라벨을 만들거나 분류를 바꾸는 모든 경로는 라벨 식별자를 함께 저장해야 하며, 식별자 없이 저장되면 색상 매칭이 실패해 고정 폴백색으로 표시된다.

**references_apis**:

- API-018
- API-024
- API-066
- API-067

**references_features**:

_(empty)_

### 로드 버전 선택 모달

- **role**: modal
- **layout**: list

**components**:

#### [1]

- **note**: 버전 번호와 승인 일시와 승인자를 한 줄로 보여주고 가장 마지막 버전에 최신 표시를 단다. 최신순으로 나열하며 기본 선택은 가장 마지막 버전이다.
- **type**: List
- **label**: 산출 버전 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-197

#### [2]

- **note**: 고른 버전과 현재 작업본의 차이를 보여준다. 변경이 없으면 빈 목록 대신 변경 없음을 알리고, 조회에 실패하면 실패로 표시해 변경 없음과 구분한다.
- **type**: Custom
- **label**: 변경 내용 미리보기

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DiffViewer
- **triggers_api**: API-182

#### [3]

- **note**: 고른 버전을 영상 전체 범위로 화면에 올린다. 서버에는 아무것도 쓰지 않으며 저장을 눌러야 확정된다.
- **type**: Button
- **label**: 이 버전으로 시작

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-195

#### [4]

- **note**: 어느 버전도 불러오지 않고 지금 작업본 그대로 편집을 시작한다.
- **type**: Button
- **label**: 현재 작업본으로 시작

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 라벨링 화면에 들어올 때 한 번 띄우는 모달이다. 검수 승인으로 만들어진 산출 버전이 둘 이상일 때만 뜬다 — 하나도 없으면 고를 것이 없고 하나뿐이면 고를 것이 하나뿐이라 어느 쪽도 띄우지 않는다. 여기서 말하는 버전은 관제가 픽업하는 산출 폴더의 번호와 같은 것이며 영상 단위로 매겨진다. 기본 선택은 가장 마지막 버전이라 그대로 확정하면 이미 그 버전과 같은 작업본에는 아무 일도 일어나지 않는다. 버전을 고르면 그 버전과 현재 작업본의 차이를 먼저 보여줘 무엇이 되돌아가는지 알고 고르게 한다. 불러오기는 영상 전체를 화면에 올리기만 하고 서버에는 아무것도 쓰지 않는다 — 저장을 눌러야 확정되고 저장하지 않고 화면을 떠나면 작업본이 그대로 남는다. 버전을 불러온 뒤에는 저장이 프레임 하나가 아니라 영상 전체 단위다. 일부 프레임만 저장하면 한 영상 안에 서로 다른 시점의 프레임이 섞인 채로 확정되어 그대로 외부로 나가기 때문이다. 불러온 내용이 작업본과 다른 동안에는 아직 저장하지 않았음을 화면에 드러내고, 저장 없이 화면을 벗어나려 하면 확인을 거친다. 승인 버전이 하나뿐인 동안에는 그 버전으로 되돌릴 화면 동선이 없으며 두 건이 되면 열린다.

**references_apis**:

- API-197
- API-182
- API-195

**references_features**:

_(empty)_

### 하단 프레임 타임라인

- **role**: footer
- **layout**: list

**components**:

#### [1]

- **note**: siblings 기반 썸네일
- **type**: Custom
- **label**: 프레임 썸네일 strip

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DarkFrameStrip
- **triggers_api**: API-021

#### [2]

- **note**: 현재 인덱스/총 프레임 → jumpTo
- **type**: Custom
- **label**: 프레임 슬라이더

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DarkFrameSlider

#### [3]

- **note**: 썸네일 테두리 색으로 프레임 상태를 구분한다 — 지금 보고 있는 프레임(강조), 미해결 문의가 달린 프레임(빨강), 라벨이 저장된 프레임(연두), 그 외 기본색. 이슈가 달린 프레임에는 작은 깃발 아이콘도 함께 표시한다.
- **type**: Custom
- **label**: 썸네일 상태 표시

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FrameStatusIndicator

#### [4]

- **note**: 폐기한 프레임은 썸네일을 흐리게 낮추고 폐기 표식을 얹어 목록에서 바로 구분되게 한다. 목록에서 빼지는 않는다 — 빼면 복원할 자리를 찾을 수 없다.
- **type**: Custom
- **label**: 폐기 프레임 표시

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DiscardedFrameMark

- **description**: 하단 고정 영역. 위쪽은 영상 전체 프레임의 썸네일 띠다 — 목록은 라벨 조회 응답(API-018)에 함께 오는 형제 프레임 정보에서 얻고, 각 썸네일 이미지는 프레임 이미지 조회(API-021)로 개별로 받아 현재 프레임을 강조한다. 아래쪽은 프레임 슬라이더다. 둘 다 클릭·드래그로 프레임을 옮기며, 이동할 때 라벨링 주소를 교체하고 라벨을 다시 조회한다. 좌우 방향키로도 이전·다음 프레임으로 이동한다. 폐기한 프레임도 이 띠에서 빠지지 않고 표식만 달린다 — 목록에서 빼면 복원할 자리를 찾을 수 없기 때문이다.

**references_apis**:

- API-018
- API-021

**references_features**:

_(empty)_

### 확인·신고 모달

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **note**: 저장 후 닫기 / 저장 없이 닫기 / 취소
- **type**: Dialog
- **label**: 저장 안 한 변경사항

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Button
- **label**: 저장 후 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-019

#### [3]

- **note**: 사유 textarea + 신고하기
- **type**: Dialog
- **label**: 비식별 누락 신고

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 1~1000자
- **type**: Textarea
- **label**: 신고 사유

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: POST deident-report → 영상 잠금
- **type**: Button
- **label**: 신고하기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [6]

- **note**: 저장이 거부됐을 때 뜨며, 거부 사유에 따라 안내와 선택지를 다르게 준다. (1) 다른 사용자가 먼저 저장해 화면의 라벨이 낡았을 때 — '최신 라벨 불러오기'를 고르면 미저장 변경은 사라지고 최신 라벨로 갱신되며, '내 작업 유지'를 고르면 지금 화면을 그대로 둔다. (2) 비식별 누락 신고와 무관한 일시적 작업락(트랙 병합·재비식별 진행 중)으로 지금 저장할 수 없을 때 — 잠시 뒤 다시 시도하도록 안내한다. 화면에서 해소할 수단이 없으므로 최신 라벨 불러오기와 내 작업 유지는 제시하지 않는다. (3) 사용 중지된 라벨 마스터를 새로 부여했을 때 — 어느 라벨이 문제인지 짚어 주고 사용 중인 라벨로 바꾼 뒤 다시 저장하도록 안내한다. 고칠 대상이 지금 화면의 라벨이므로 미저장 변경을 버리는 선택지는 제시하지 않는다.
- **type**: Dialog
- **label**: 저장 충돌 안내

**columns**:

_(empty)_

**options**:

_(empty)_

#### [7]

- **note**: 미저장 변경이 있는 상태에서 다른 프레임으로 이동하려 할 때 뜬다. 저장 후 이동/저장 안 함/취소 중 하나를 고른다 — 닫기 시의 확인 절차와 같은 방식이며 문구만 이동 상황에 맞게 다르다.
- **type**: Dialog
- **label**: 프레임 이동 확인

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 저장에 폐기 상태 변경이 들어 있으면 몇 개 프레임이 학습데이터에서 빠지고 몇 개가 되돌아오는지 알리고 확인을 받는다. 폐기는 되돌릴 수 있지만 산출물에서 빠지는 결정이라 저장 전에 한 번 드러낸다.
- **type**: Dialog
- **label**: 폐기 프레임 저장 확인

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 구성은 아래 컴포넌트 목록이 정본이다. 저장 확인 모달: × 닫기 시 미저장 변경(dirtyCount>0)이 있으면 노출 — '저장 후 닫기'(API-019)/'저장 없이 닫기'/'취소' 3옵션. beforeunload 가드 동반. 비식별 누락 신고 모달(DeidentReportButton): 사유 textarea(1~1000자) 입력 후 POST /v1/labels/{srcSn}/deident-report(API-032) → 작업락 + DE_IDNTF_YN='F'. 응답 — 409(이미 재처리 중)/403(본인 배정 아님)/404(영상 없음)/412(파생영상·비식별 미수행 영상·검수가 승인된 영상). ★파생영상(videoDetail.derivative=true)은 버튼 자체를 비활성화하고 사유를 툴팁으로 안내한다('이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다') — 부모 rawSn 은 노출하지 않고 원본으로 유도하지도 않는다. 412 응답 시에도 동일 안내를 안전망으로 노출. ★검수가 승인된 영상도 같은 방식으로 버튼을 비활성화하고 사유를 툴팁으로 안내한다('검수가 완료된 영상은 비식별 누락을 신고할 수 없습니다'). ★신고 성공 후 라벨 캐시는 갱신 대상 표시만으로 끝내지 않고 완전히 제거한다 — 캐시가 남아 있으면 이탈 후 짧은 시간 내 재진입 시 재조회가 일어나지 않아 잠금 안내 없이 낡은 라벨 좌표가 그려질 수 있다(라벨 좌표는 개인정보 위치를 특정하는 정보). 판정 기준: 접근 자체가 막히는 변화는 캐시를 제거하고, 단순 정합성 갱신은 갱신 대상 표시로 충분하다.

**references_apis**:

- API-019

**references_features**:

_(empty)_

### 스켈레톤 인체 가이드

- **role**: modal
- **layout**: detail

**components**:

#### [1]

- **note**: COCO-17 관절 오버레이 — 관절명·스켈레톤 엣지 안내
- **type**: Custom
- **label**: 인체 포즈 가이드

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: KeypointGuide
- **triggers_api**: API-019

#### [2]

- **note**: 캔버스 오버레이와 별개로, 우측 패널 상단(어느 탭을 보고 있어도 항상 보이는 영역)에 작은 사람 형태 진행 안내 다이어그램을 둔다. 지금 찍어야 할 관절을 강조하고 이미 찍은 관절·아직 남은 관절을 구분해 보여주며, 관절 이름과 진행률(예: 5/17), 인물 기준 좌우 안내 문구를 함께 표시한다. 17개 관절을 모두 찍었거나 키포인트 도구를 쓰지 않을 때는 숨긴다.
- **type**: Custom
- **label**: 스켈레톤 배치 진행 가이드(상시 패널)

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: KeypointProgressGuide

- **description**: 키포인트(K) 도구를 고르면 캔버스에 인체 포즈 가이드를 겹쳐 표시한다. 17개 관절(코부터 발목까지)의 순서와 골격 연결선을 안내하고, 각 관절을 클릭해 세 값 [x, y, v] 을 입력한다(v=0 미표기 / 1 비가시 / 2 가시). 저장은 라벨 저장(API-019)이며 라벨 형태는 골격, 좌표는 관절 17개의 세 값 묶음이다.

**references_apis**:

- API-019

**references_features**:

_(empty)_

### 프레임 설명 패널

- **role**: side
- **layout**: form

**components**:

#### [1]

- **note**: 최대 1000자. 미입력/빈값 저장 시 삭제
- **type**: Textarea
- **label**: 프레임 설명

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-129

#### [2]

- **type**: Button
- **label**: 설명 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-129

- **description**: 이 패널은 우측 패널의 '메타' 탭 안에서 다른 메타 패널들과 함께 세로로 나열한다('메타' 탭으로 전환해야 보인다). 프레임 설명 입력 영역이다. 프레임 설명 조회(API-128)로 기존 설명을 불러오고, 최대 1000자까지 편집한 뒤 저장한다(API-129). 빈 값으로 저장하면 삭제다. 학습데이터 산출물의 이미지 설명 조달원이며, 검수 완료된 영상을 수정하면 그 영상은 재검수 대상이 되고 검수자가 다시 승인한 시점에 관제로 수정 통지가 발행된다.

**references_apis**:

- API-128
- API-129

**references_features**:

_(empty)_

### 트랙 편집 (삭제/분할/머지)

- **role**: side
- **layout**: stack

**components**:

#### [1]

- **note**: DELETE tracks/{trackId}?fromFrameNo — 지정 프레임 이후 삭제 후 재보간
- **type**: Button
- **label**: 트랙 삭제(이후 프레임)

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive
- **triggers_api**: API-126

#### [2]

- **note**: POST tracks/{trackId}/split — atFrameNo 이후 새 트랙 분리 후 재보간
- **type**: Button
- **label**: 트랙 분할

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-127

#### [3]

- **note**: POST tracks/merge — from→to 이관 후 재보간
- **type**: Button
- **label**: 트랙 병합

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary
- **triggers_api**: API-125

#### [4]

- **note**: POST yolo-track — 프레임별 추론, DB 미저장 화면에 드러나는 표기에는 내부 모델명을 쓰지 않으며, 선택한 객체 하나를 따라가는 AI 추적과 이름이 겹치지 않게 구분한다.
- **type**: Button
- **label**: AI 자동 추적

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline
- **triggers_api**: API-123

#### [5]

- **note**: 기본값은 검토 후 수락이다. 이 실행은 여러 객체를 여러 프레임에 걸쳐 한 번에 만들어 오검출의 영향 범위가 단일 객체 추적보다 크므로, 자동 반영은 사용자가 옵션으로 켜는 쪽이다. 검토 후 수락에서는 사용자가 수락한 결과만 객체 목록에 들어간다. 고른 방식은 그 실행에만 적용되고 다음 실행은 다시 기본값으로 시작한다 — 이전 선택을 기억하지 않는다. 오검출이 많은 영상에서 이전 선택이 남아 무심코 자동 반영되는 일을 막기 위한 것이다. 수락·제외의 단위는 같은 객체로 이어진 결과를 묶은 단위이며, 검출 하나하나를 따로 고르지 않는다 — 여러 프레임에 걸친 다중 객체 결과에서는 고를 항목이 너무 많아져 검토가 오히려 느려진다. 이 입도는 보류 스테이징이 쓰는 단위와 같다.
- **type**: RadioGroup
- **label**: 트랙 결과 적용 방식

**columns**:

_(empty)_

**options**:

- 검토 후 수락
- 자동 반영

#### [6]

- **note**: 라벨 마스터 식별자가 비어 있는 검출은 객체 목록에 반영하지 않고, 제외했다는 사실과 사유(대응 마스터 없음·검출 클래스 매핑 미지정)를 알린다.
- **type**: Alert
- **label**: 마스터 미연결 검출 제외 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: default

- **description**: 객체 목록(ObjectClassTree)에서 트랙 단위 편집. 트랙 삭제(지정 프레임 이후, API-126)·분할(API-127)·병합(API-125)은 배타 락 + 재보간 수행(수동/SEGMENT/SKELETON 트랙은 재보간 미적용). AI 자동 추적(API-123)은 프레임별 인터랙티브 추론(조회 전용, DB 미저장). REVIEWER/WORKER, 본인 배정 영상 IDOR 검증. ★온디맨드 트랙 결과의 적용 방식은 화면에서 고른다 — 검토 후 수락(기본값)과 자동 반영. 기본값을 검토 후 수락으로 두는 것은 이 실행이 여러 객체를 여러 프레임에 걸쳐 한 번에 만들어 오검출의 영향 범위가 단일 객체 추적(AI 추적)보다 크기 때문이다. 자동 반영은 사용자가 옵션으로 켜는 쪽이며, 어느 방식이든 결과는 작업 중인 객체 목록에만 들어가고 확정은 저장으로 한다. ★결과로 라벨을 만들 때는 응답에 실려 온 라벨 마스터 식별자(labelId)를 그대로 저장 요청(API-019)에 싣는다 — 화면이 검출 클래스명으로 마스터를 다시 찾아내지 않는다. 그 해석은 서버가 하며, 화면이 다시 판정하면 같은 규칙이 두 곳에 생겨 한쪽이 낡는다. 식별자가 비어 있는 검출(대응 마스터가 없거나 그 검출 클래스에 매핑이 지정되지 않은 경우)은 반영하지 않고 그 사실을 사용자에게 알린다 — 마스터 연결이 끊긴 라벨을 만들면 표시 색상뿐 아니라 라벨명과 속성 정의까지 함께 끊기고, 저장 전에는 정상으로 보이다가 다시 불러온 뒤에야 드러난다. ★실행 버튼 표기는 「AI 자동 추적」이며 화면에 드러나는 문구에는 내부 모델명을 쓰지 않는다 — 선택한 객체 하나를 따라가는 AI 추적과 이름이 겹치지 않게 구분한 것이다. 표기의 '자동'은 적용 방식과 다른 축이고 적용 방식의 기본값은 검토 후 수락이다. 고른 방식은 그 실행에만 적용되며 다음 실행은 다시 기본값으로 시작한다. 수락·제외의 단위는 같은 객체로 이어진 결과를 묶은 단위이며 검출 하나하나를 따로 고르지 않는다.

**references_apis**:

- API-125
- API-126
- API-127
- API-123
- API-019

**references_features**:

_(empty)_

### 이벤트 어노테이션 패널

- **role**: side
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: 이벤트 어노테이션(VQA/CoT)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: PENDING/AUTO_GENERATED/APPROVED/REJECTED
- **type**: Badge
- **label**: 검토 상태

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-132

#### [3]

- **note**: 필수 입력. 예: 화재, 침입, 배회
- **type**: Input
- **label**: 이벤트 분류

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 편집 가능한 필드다. 저작도구가 보관하는 검증 이벤트 유형별 질문 문구로 미리 채워진다 — 마킹에서 고른 질문이 1순위이고, 없거나 그 유형의 질문이 아니면 그 유형의 첫 번째 질문을 쓴다. 유형이나 등록된 질문이 없으면 비어 있다.
- **type**: Textarea
- **label**: 질의

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 편집 가능한 필드다. 자동으로 채우지 않는 공란이며 사람이 확정한다.
- **type**: Textarea
- **label**: 답변

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 여러 건 추가/삭제 가능. 각 행은 캡션 본문 + 사고 단계(1·2·3단계)로 구성하며 전부 편집 가능하다. 첫 번째 후보만 미리 채워진다 — 캡션 본문은 추가 질문 응답 서술에서, 1단계는 묘사 전문의 「상황」 줄에서 오고 그 줄이 없으면 1단계는 비어 있다. 2·3단계와 나머지 후보는 공란이다.
- **type**: Custom
- **label**: 캡션 후보

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: CaptionCandidateRow
- **triggers_api**: API-134

#### [7]

- **note**: 자동으로 채우지 않는 공란이며 사람이 확정한다. 행 키는 순번(1번째, 2번째…)으로 자동 부여한다. 근거 텍스트 + 프레임 식별자 목록 + 객체 식별자/라벨/좌표 목록을 입력하며, '현재 프레임 추가' 버튼으로 지금 보고 있는 프레임을 프레임 식별자에 즉시 추가하고, '선택 객체 추가' 버튼으로 캔버스에서 선택된 객체 1건의 식별자·라벨·좌표를 자동으로 채운다(선택된 객체가 없으면 비활성). 객체 식별자·라벨 항목은 콤마로 구분된 여러 값을 담을 수 있다.
- **type**: Custom
- **label**: 근거 후보

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: EvidenceCandidateRow

#### [8]

- **type**: Button
- **label**: 어노테이션 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-134

#### [9]

- **note**: REVIEWER
- **type**: Button
- **label**: 승인

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-133

#### [10]

- **note**: REVIEWER. 반려 시 필수 입력.
- **type**: Textarea
- **label**: 반려 사유

**columns**:

_(empty)_

**options**:

_(empty)_

#### [11]

- **note**: REVIEWER, 사유 필수
- **type**: Button
- **label**: 반려

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive
- **triggers_api**: API-135

- **description**: 우측 패널 '메타' 탭에 위치하는 이벤트 어노테이션(VQA/CoT) 영역이다. 영상 단위로 조회하며(API-132) 최초 조회 시 자동 생성값을 1회 프리필한다. 미리 채워져 보이는 칸은 셋이다 — 질의 칸은 저작도구가 보관하는 검증 이벤트 유형별 질문 문구에서 오고(마킹에서 고른 질문이 1순위, 없거나 그 유형의 질문이 아니면 그 유형의 첫 번째), 첫 번째 캡션 후보의 캡션 본문은 외부 시계열 분석의 추가 질문 응답 서술에서, 같은 후보의 사고 단계 1단계는 묘사 전문의 「상황」 줄에서 온다. 유형이나 등록된 질문이 없으면 질의 칸은 비어 있고 「상황」 줄이 없으면 1단계도 비워 둔다. 답변·근거 후보·사고 단계 2단계·3단계는 자동으로 채우지 않는 공란이며 사람이 확정한다. ★모든 필드가 작업자·검수자의 직접 편집 대상이다 — 이벤트 분류는 필수 입력이고, 미리 채워진 값도 그대로 고칠 수 있으며, 값을 보여주기만 하고 편집을 막는 읽기 전용 필드는 두지 않는다. 캡션 후보와 근거 후보는 각각 여러 건을 추가/삭제할 수 있고 행 식별 키는 순번으로 자동 부여한다. 근거 후보 행에는 캔버스의 현재 프레임과 선택 객체를 자동으로 채워 넣는 보조 버튼을 둔다. 저장(API-134)은 upsert 다. 검수자(내부 채널)는 승인(API-133)·반려(API-135, 사유 필수)를 수행할 수 있으며 검토 상태 배지(대기/자동생성/승인/반려)를 함께 표시한다. 승인된 어노테이션만 검수 승인 시점의 학습데이터 산출물에 반영한다.

**references_apis**:

- API-132
- API-134
- API-133
- API-135

**references_features**:

_(empty)_

### AI 탐지 대상·정밀도 다이얼로그

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: AI 탐지

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 라벨 마스터 중 AI 검출 클래스가 매핑된 것만 선택 가능(미매핑은 표시하되 선택 불가). 선택 없이 실행하면 매핑된 전체를 탐지한다.
- **type**: Custom
- **label**: 검출 대상 선택

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: AiDetectTargetPicker

#### [3]

- **type**: Select
- **label**: 형태

**columns**:

_(empty)_

**options**:

- BBOX
- POLYGON

#### [4]

- **note**: 조절한 값만 요청에 실리고, 조절하지 않으면 서버 기본값을 쓴다.
- **type**: Custom
- **label**: 정밀도(민감도) 슬라이더

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: PrecisionControl

#### [5]

- **note**: 형태=폴리곤일 때만 노출.
- **type**: Input
- **label**: 폴리곤 단순화(세밀함)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 매핑된 라벨이 하나도 없으면 실행 비활성.
- **type**: Button
- **label**: 탐지 실행

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-124

#### [7]

- **note**: 이 다이얼로그에서 형태·대상 라벨을 고른 뒤 '트랙으로 실행'을 선택하면 AI 추적 도구가 그 설정을 기억한 채 켜진다. 이어서 캔버스(또는 객체 목록)에서 추적할 객체를 선택해야 실제 추적이 시작된다 — 다이얼로그 자체에서 즉시 실행되지 않는다. 후속 프레임이 없으면 이 실행은 비활성화된다.
- **type**: Button
- **label**: 트랙으로 실행

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 좌측 도구바 'AI 탐지' 클릭 시 여는 대상·정밀도 다이얼로그다. 좌측에 검출 형태(박스/폴리곤) + 검출 대상 라벨(마스터 중 AI 검출 클래스가 매핑된 라벨만 선택 가능, 다중 선택, 미선택 시 매핑된 전체 탐지)을, 우측에 정밀도(민감도) 조절과 폴리곤 선택 시에만 노출하는 세밀함(단순화 정도) 조절을 나란히 둔다. 정밀도 값은 조절했을 때만 요청에 싣고, 조절하지 않으면 서버 기본값을 쓴다. confThreshold(민감도)·simplifyTolerance(세밀함) 두 값만 요청 파라미터로 전달하며 iou 는 조정 대상이 아니다. 실행하면 현재 프레임에 대해 검출을 수행한다(API-124). 매핑된 라벨이 하나도 없으면 실행을 비활성화하고, 라벨 관리에서 AI 검출 클래스 매핑을 먼저 등록하도록 안내한다.

**references_apis**:

- API-124

**references_features**:

- FEAT-007

### 개인정보·촬영환경 메타 패널

- **role**: side
- **layout**: form

**components**:

#### [1]

- **note**: 프레임 단위(LS_DATA_SRC). GET/PUT /v1/frames/{srcSn}/privacy-meta
- **type**: Custom
- **label**: 개인정보 메타 (프레임 축)

**columns**:

_(empty)_

**options**:

- anonymity
- pseudonymity
- privacyIncluded

- **custom_name**: FrameMetaPanel

#### [2]

- **note**: 영상 단위(LS_DATA_RAW). GET/PUT /v1/videos/{rawSn}/privacy-meta. 프레임 축과 입도가 다른 별개 축
- **type**: Custom
- **label**: 개인정보 메타 (영상 축)

**columns**:

_(empty)_

**options**:

- anonymity
- pseudonymity
- privacyIncluded

- **custom_name**: VideoPrivacyPanel

#### [3]

- **note**: 날씨/시간대/계절. PII 축이 아니라 신고 게이트 제외
- **type**: Custom
- **label**: 촬영환경 메타

**columns**:

_(empty)_

**options**:

- weather
- timeOfDay
- season

- **custom_name**: VideoMetaPanel

- **description**: 이 섹션의 패널들은 우측 패널의 '메타' 탭 안에서 촬영환경 → 영상축 개인정보 → 프레임 설명 → 프레임축 개인정보 → 시계열 메타 → 이벤트 어노테이션 순서로 다른 메타 패널들과 함께 세로로 나열한다('메타' 탭으로 전환해야 보이며, 항상 보이는 독립 패널이 아니다). ★개인정보 패널은 프레임 축(LS_DATA_SRC)과 영상 축(LS_DATA_RAW)이 입도가 다른 별개 축이다 — 두 값이 달라도 모순이 아니며('영상 어딘가엔 있지만 이 프레임엔 없다') 각각 export JSON 의 image / video 블록으로 나간다. GET 응답은 수동값 우선 + 기본상수 프리필 + *Source(MANUAL/DERIVED) 병기다(상수 원천은 BE 의 단일 정책 지점 — 화면이 하드코딩하지 않는다). ⚠ FE 는 사용자가 직접 고르지 않은 필드를 null 로 전송해야 한다 — DERIVED 프리필을 그대로 되돌려 보내면 기본상수가 사람의 판정으로 승격되며 BE 는 출처를 알 수 없어 막지 못한다. 비식별 누락 신고 구간에는 개인정보 PUT 이 412 로 차단된다(영상 축·프레임 축 양쪽, 단건+벌크 모두 — 한쪽만 막으면 비대칭을 옆으로 옮길 뿐이다). GET 은 차단하지 않는다(값이 PII 가 아니고 막으면 화면이 안 뜬다). 촬영환경(날씨/시간대/계절)은 PII 축이 아니라 신고 게이트 제외이다. 승인 후 수정은 그 영상을 재검수 대상으로 되돌리고, 검수자가 그 수정을 다시 승인한 시점에 export 를 새 버전으로 전량 재생성한 뒤 성공 이후 TASK_MODIFIED 를 발송한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 이슈 소통 패널

- **role**: side
- **layout**: stack

**components**:

#### [1]

- **note**: 반려 이력과 문의를 한 목록으로 최신순 나열한다.
- **type**: Custom
- **label**: 이슈 스레드 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: IssueThreadPanel
- **triggers_api**: API-103

#### [2]

- **note**: 목록 상단과 탭 라벨 옆에 동일하게 병기한다.
- **type**: Badge
- **label**: 미해결 문의 건수

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 작업자·검수자 모두. 클릭 시 문의 등록 폼을 연다.
- **type**: Button
- **label**: 문의

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 현재 프레임을 함께 태깅할 수 있다.
- **type**: Textarea
- **label**: 문의 등록

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-102

#### [5]

- **note**: 작업자·검수자 모두 각 스레드에 답글을 남길 수 있다.
- **type**: Textarea
- **label**: 댓글 작성

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-104

#### [6]

- **note**: REVIEWER만. 반려 이력(유형=반려) 스레드는 해결 처리 후에도 댓글 입력을 열어 둔다. 그 외 유형은 해결되면 댓글 입력을 잠근다.
- **type**: Button
- **label**: 해결 처리

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-105

- **description**: 우측 패널 '이슈' 탭 — 검수자↔작업자 통합 소통 창구다. 영상 정보가 없으면 탭은 노출하되 이용 불가 안내만 보여준다. 반려 이력과 문의를 한 목록으로 보여주며, 목록 상단과 탭 라벨 옆에 미해결 문의 건수 배지를 병기한다. 문의 등록과 댓글 작성은 역할을 가리지 않는다 — 작업자와 검수자가 모두 '문의' 버튼으로 새 문의를 작성(현재 프레임 태깅 가능)하고 각 스레드에 댓글로 답할 수 있다. 스레드를 '해결' 처리하는 것만 검수자 전용이다. 반려 이력 스레드는 해결 처리 후에도 댓글 입력을 열어 두어 후속 소통을 막지 않는다 — 그 외 유형(문의)은 해결되면 댓글 입력을 잠근다. 목록 조회, 문의 등록, 댓글 추가, 해결 처리 4개 동작을 각각 별도 API 로 수행한다. 각 스레드와 각 댓글에는 작성자를 '이름 (역할)' 형태로 표시한다 — 역할은 코드값이 아니라 한글 호칭(작업자·검수자)으로 바꿔 보여주고, 목록에 없는 값은 받은 값을 그대로 쓴다. 이름을 해석하지 못하면 사번으로 대신하고, 역할을 해석하지 못하면 빈 괄호를 남기지 않고 이름만 표시한다. ★스레드 작성자의 역할과 댓글 작성자의 역할은 기준 시점이 다르다 — 댓글은 작성 당시의 역할을 그대로 보존해 보여주고, 스레드는 조회하는 시점의 현재 역할을 보여준다. 따라서 문의를 낸 뒤 역할이 바뀐 사용자는 스레드에서는 바뀐 역할로, 그 사람이 그때 남긴 댓글에서는 당시 역할로 보인다.

**references_apis**:

- API-102
- API-103
- API-104
- API-105

**references_features**:

_(empty)_

### 단축키 안내

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **note**: 좌측 도구바 맨 아래 고정.
- **type**: Custom
- **label**: 단축키 안내 트리거

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ShortcutHelpTrigger

#### [2]

- **note**: 호버로 펼친다(클릭형 모달이 아니다).
- **type**: Custom
- **label**: 단축키 표

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ShortcutHelpTable

#### [3]

- **note**: 확대(+ 또는 =)·축소(-)로 캔버스 배율을 조절한다.
- **type**: Text
- **label**: 확대/축소 단축키

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 선택한 객체의 표시 여부를 전환한다(T). 저장 대상에는 영향을 주지 않는 화면 표시 전용 기능이다.
- **type**: Text
- **label**: 표시/숨김 단축키

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: AI 추적(트랙) 도구는 객체 목록의 실행 버튼으로 켜는 것 외에 별도 단축키로도 활성화할 수 있다.
- **type**: Text
- **label**: AI 추적 단축키

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 좌측 도구바 맨 아래 고정된 단축키 안내 버튼에 마우스를 올리면 단축키 표를 펼친다(클릭형 모달이 아니다). 그룹은 5개를 둔다 — 도구(바운딩 박스/폴리곤/AI 분할/키포인트/AI 탐지, 실제 버튼 표기와 동일한 글자를 그대로 보여준다), 프레임(첫/이전/다음/끝 프레임 이동), 폴리곤 편집(그리는 중 점 추가·자동 완성·점 수정·점 삭제), 편집(실행 취소/다시 실행/저장/삭제/복사/붙여넣기/전체복사/전체붙여넣기), 보기(그리기 취소). 라벨 선택(1~9)은 이 표에 싣지 않는다 — 전역 단축키가 아니라 라벨 선택 모달에서만 동작하므로, 그 모달 각 행의 숫자 배지가 직접 안내한다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 캔버스 상단 옵션바

- **role**: navigation
- **layout**: stack

**components**:

#### [1]

- **note**: 첫/이전/다음/끝 프레임으로 이동하는 버튼과 현재 프레임 번호·전체 프레임 수 표시를 함께 둔다. 첫(W)·이전(A 또는 ←)·다음(D 또는 →)·끝(S) 단축키로도 동일하게 이동한다. 미저장 변경이 있는 상태에서 다른 프레임으로 이동하려 하면 확인 절차를 거친다.
- **type**: Custom
- **label**: 프레임 이동 컨트롤

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FrameNavControl

#### [2]

- **note**: 이 화면에서 라벨을 저장하는 유일한 진입점이다(Ctrl+S 단축키 동일 동작). 재비식별 처리 대기 중인 영상에서는 비활성화된다.
- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-019

#### [3]

- **note**: 직전 편집을 취소한다(Ctrl+Z).
- **type**: Button
- **label**: 실행 취소

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 취소한 편집을 다시 적용한다(Ctrl+Shift+Z).
- **type**: Button
- **label**: 다시 실행

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 확대·축소·이동 값을 초기화해 캔버스를 화면 크기에 맞춘다.
- **type**: Button
- **label**: 화면 맞춤

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 단축키(확대 +, 축소 -)로 캔버스를 확대·축소한다.
- **type**: Custom
- **label**: 확대/축소

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ZoomControl

#### [7]

- **note**: 선택한 객체를 캔버스에서 보이거나 안 보이게 전환한다(단축키 T). 저장 대상에는 영향을 주지 않는 화면 표시 전용 토글이다. 선택된 객체가 없으면 이 버튼은 비활성으로 두고 사유(객체를 먼저 선택해야 한다)를 알린다 — 눌리는 모양인데 아무 반응도 없으면 고장으로 읽히기 때문이다. 단축키는 선택이 없을 때 아무 일도 하지 않는다.
- **type**: Button
- **label**: 라벨 표시/숨김

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 지금 보고 있는 프레임을 학습데이터에서 빼거나 도로 넣는다. 누르면 화면 표시만 바뀌고 저장을 눌러야 확정된다. 폐기한 프레임은 읽기 전용이 되어 복원하기 전까지 라벨을 고칠 수 없다. 라벨과 이미지는 지우지 않고 그대로 둔다.
- **type**: Button
- **label**: 프레임 폐기 / 복원

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 화면을 벗어나지 않고 저장·편집 취소·프레임 이동·화면 배율·프레임 폐기를 다루는 상시 컨트롤 모음이다. 삭제·실행 취소·다시 실행·저장은 좌측 도구바가 아니라 이 영역에 둔다(좌측 도구바 절 참조). 저장은 이 화면의 유일한 저장 진입점이며 낙관적 동시성 토큰을 함께 보낸다(라벨링 헤더 바 절의 저장 규칙과 동일) — 평상시에는 프레임 단위이고 로드 버전을 불러온 뒤에는 영상 전체 단위다. 프레임 폐기와 복원도 같은 저장에 묶이므로 따로 확정하는 버튼을 두지 않는다. 한번이라도 검수가 완료된 영상에서는 폐기와 복원 조작을 비활성으로 두고 사유를 안내한다 — 이미 산출되어 외부로 나간 회차에서 프레임이 빠지거나 되살아나면 그 회차의 산출물과 어긋난다. 다만 과거 회차를 불러와 확정하는 경우는 막지 않는다: 그 회차의 폐기 상태는 이미 승인된 것이라 새로 바꾸는 것이 아니라 그 시점으로 되돌아가는 것이다. 폐기는 학습데이터 산출물과 데이터마트 노출에서만 빼는 것이라 프레임과 이미지와 라벨은 그대로 남으며, 전체 기준 프레임 수에서도 빼지 않는다 — 총량이 줄면 진행 상황이 왜 바뀌었는지 알 수 없기 때문이다. 다만 학습데이터로 확정된 분량을 뜻하는 검수완료 기준 통계 수치는 폐기한 프레임을 세지 않는다. 프레임 이동은 미저장 변경이 있으면 저장 후 이동/저장 안 함/취소 중 하나를 고르는 확인 절차를 거친다(확인·신고 모달 절 참조).

**references_apis**:

- API-019
- API-196

**references_features**:

_(empty)_

### 이관 원문 정보 패널

- **role**: side
- **layout**: detail

**components**:

#### [1]

- **note**: 메타 탭의 여섯 패널 뒤에 참고 정보로 붙는 영역의 제목이다. 이관으로 들어온 영상에서만 노출한다.
- **type**: Heading
- **label**: 이관 원문 정보(읽기전용)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: API-066 importedMeta 목록을 값만 표시한다 — 편집 불가, 참고용이다. 사람이 읽는 이름으로 라벨을 표시하고 원문 열쇠 문자열을 그대로 쓰지 않으며, 이름을 정하지 못한 열쇠는 버리지 않고 원문 열쇠 그대로 표시한다.
- **type**: KeyValue
- **label**: 이관 원문 항목

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-066

#### [3]

- **note**: 이관으로 들어오지 않은 영상에서는 목록이 비어 있는 것이 정상이라 오류로 안내하지 않는다. 이 경우 패널 자체를 노출하지 않는다.
- **type**: Text
- **label**: 이관 원문 없음 안내

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 이 패널은 우측 패널의 '메타' 탭 맨 아래에 참고 정보로 둔다 — 위의 여섯 패널이 모두 끝난 뒤 마지막에 나열하며 그 여섯의 나열 순서는 바꾸지 않는다. 외부 산출물 이관이 저작도구 스키마에 착지할 컬럼이 없어 원문 그대로 보관한 값을 보여준다(API-066 의 importedMeta 목록). 담기는 값은 좌표·위치·카메라 설치 높이·설치 방위·카메라 관리번호·데이터 출처·이벤트 기록·이벤트 상위 계층 이름·외부 영상 식별자·원천 축 개인정보 판정이다. ★읽기 전용이다 — 편집·저장 대상이 아니며 이 열쇠를 수정 요청하면 거부된다. '영상 기술 정보(읽기전용)' 과 같은 결로 값만 보여주고 입력 칸을 두지 않는다. ★표시는 사람이 읽는 이름으로 한다 — 원문 열쇠 문자열을 라벨에 그대로 쓰지 않는다. 이름을 정하지 못한 열쇠는 버리지 않고 원문 열쇠 그대로 표시한다(조용한 손실 금지). ★목록을 가르는 주체는 서버다 — 화면이 열쇠 접두를 파싱해 시계열 메타와 이관 원문을 스스로 나누지 않는다. 이관으로 들어온 영상에서만 표시하며, 그 밖의 영상에서는 목록이 비어 있고 그것이 정상이라 패널 자체를 감춘다.

**references_apis**:

- API-066

**references_features**:

_(empty)_

## brownfield

### status

modified

### change_kind

- capability-add

### diff_summary

1차 라벨링 캔버스 → 2차 SAM2 트랙·정밀도 고도화

### legacy_source

#### type

module

#### legacy_artifact_id

LEGACY-004

## surface_kind

web

## consumes_apis

- API-018
- API-019
- API-020
- API-021
- API-024
- API-032
- API-066
- API-067
- API-102
- API-103
- API-104
- API-105
- API-123
- API-124
- API-125
- API-126
- API-127
- API-128
- API-129
- API-132
- API-134
- API-133
- API-135
- API-093
- API-182
- API-012
- API-178
- API-022
- API-023
- API-168
- API-170
- API-172
- API-173
- API-183
- API-184
- API-177
- API-195
- API-196
- API-197
- API-034
- API-035
- API-036
- API-193
- API-204

## implementation

### status

implemented

### modules

- MOD-021
- MOD-006

### records

- IMPREC-021

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-17T22:48:30.260Z

### module_paths

_(empty)_

## required_roles

- ROLE-001
- ROLE-002

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-005/main.html
- **label**: 메인 페이지
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 
- **source_hash**: 20c15b160b04c754c002e0dc3cf67a2a186402491c0aeff56dbd31bb9b6a9887
- **generated_at**: 2026-08-27T09:24:04.255Z
- **generated_by**: generate-wireframes.py

**triggered_by**:

_(empty)_

## uses_constants

_(empty)_

## external_designs

_(empty)_

## realizes_use_cases

- UC-004
- UC-005
- UC-006
- UC-007
- UC-008
- UC-021
- UC-022
- UC-034

## covered_by_acceptances

_(empty)_
