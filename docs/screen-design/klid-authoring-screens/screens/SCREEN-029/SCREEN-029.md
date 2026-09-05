---
logicraft_item: SCREEN-029
type: screen_spec
version: 48
last_updated_at: 2026-09-02T09:18:38.189Z
domain: DOMAIN-000
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T01:33:13.472Z
sync_session: 34
stale: true
status: CHANGED
prev_version: 41
raw: ./_raw/SCREEN-029.json
wireframe: ./wireframe.html
links:
  consumes_apis: ["[[API-024]]", "[[API-082]]", "[[API-110]]", "[[API-111]]", "[[API-140]]", "[[API-149]]", "[[API-154]]", "[[API-155]]", "[[API-234]]", "[[API-235]]", "[[API-236]]", "[[API-237]]"]
  required_roles: ["[[ROLE-003]]"]
  realizes_use_cases: ["[[UC-024]]", "[[UC-027]]"]
  acceptance: ["[[AC-1068]]", "[[AC-1069]]", "[[AC-1070]]"]
---

> ⚠️ **버전 변경 감지 — logicraft v41 → v48**
> change_summary: 정적 HTML 와이어프레임 자동 생성 — 1440×auto (24.7KB)
> ↳ 요약/구현 노트 재검토 후 작성된 코드에 반영. 직전 요약은 git diff 확인.

# 포털 라벨링 화면

## route

/portal/label/:id

## title

포털 라벨링 화면

## device

responsive

## status

draft

## purpose

포털 사용자(PORTAL_USER)가 프레임을 라벨링하고 저장하는 화면. 대상은 두 출처다 — 데이터마트에서 불러온 영상의 프레임과, 본인이 올린 영상에서 추출된 프레임이다. 포털의 라벨링 화면은 이 하나뿐이라 자산 출처에 따라 다른 화면으로 갈라지지 않는다. 진입은 세 갈래다 — 포털이 소유한 데이터마트 목록에서 대상을 인계받아 처음 여는 경우(새로쓰기), 저작도구의 「내 저장 작업 목록」에서 이어서 여는 경우(이어쓰기), 그리고 자산 목록 화면에서 준비가 끝난 본인 업로드 자산의 행을 눌러 여는 경우다. 이어쓰기는 마지막으로 저장한 프레임을 열며, 그 자리는 본인 저작 작업 목록 조회 응답이 알려 주므로 화면이 스스로 추측하지 않는다. 업로드 자산의 프레임도 데이터마트 자산의 프레임과 같은 원장에 앉아 식별자 체계가 같으므로, 두 출처를 이 경로 하나로 연다. 작도 도구는 바운딩 박스와 폴리곤 수동 작도만 제공한다. ★AI 보조는 포털 전 구간에서 제공하지 않는다 — 분할·추적·오토라벨 어느 것도 두지 않으며, 그 도구를 여는 버튼과 전용 경로도 만들지 않는다. ★시계열 메타는 이 묶음이 아니다 — 미제공인 것은 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)이고, 그 결과물을 화면에 표시하고 사람이 확인·수정·추가하는 것은 제공한다. 촬영환경·프레임 설명·개인정보 판정·시계열 메타와 이벤트 어노테이션 다섯 축을 Load 해 표시하며, 사용자가 고치거나 새로 추가한 값을 적재한다. ★이 다섯 축은 자산 출처를 가리지 않는다 — 본인이 올린 영상에도 촬영환경과 프레임 설명을 붙일 수 있어야 하므로 출처로 갈라 두지 않는다. 적재처는 라벨 저장과 같은 규칙을 따라 서버가 자산 출처로 판정한다 — 데이터마트 자산은 포털 전용 오버레이에만 쌓여 원본과 데이터마트 동결본을 수정하지 않는 단방향이고, 본인이 올린 자산은 그 자산에 그대로 쌓인다. 검수와 버전관리도 포털에 두지 않는다. ★저장처는 화면이 가르지 않는다 — 서버가 자산 출처로 판정한다. 데이터마트 자산의 작업 결과는 데이터마트 원본을 수정하지 않고 본인 작업 데이터로만 별도 적재하고(단방향이라 작업 결과가 데이터마트로 되돌아가지 않는다), 본인이 올린 자산의 작업 결과는 그 자산에 그대로 적재한다. 화면은 그 차이를 알 필요가 없다 — 어느 출처든 같은 저장 동작을 하고, 어디에 쌓을지는 서버가 정한다. 조회로 받은 라벨의 마스터 참조와 트랙 식별자는 화면에 표시하지 않고 그대로 보유했다가 저장 요청에 되돌려 보낸다 — 데이터마트 원본에서 불러온 라벨의 연결이 저장 왕복에서 끊기지 않게 하려는 것이며, 트랙 번호 변경·병합 수단은 포털에 두지 않는다. 본인 자산 업로드는 이 화면이 아니라 별도의 자산 목록 화면이 담당한다. 라벨 내보내기와 원본 파일 내려받기도 이 화면에 두지 않는다 — 그 자산 목록 화면의 자산별 액션이 담당한다. 반응형. 접근: PORTAL_USER.

## sections

### 라벨링 헤더 바

- **role**: header
- **layout**: stack

**components**:

#### [1]

- **note**: dirty>0 시 저장확인 모달, 아니면 navigate(-1)
- **type**: Button
- **label**: × 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: ghost

#### [2]

- **note**: 포털은 cctvName 대신 프레임 식별
- **type**: Heading
- **label**: 프레임 #srcSn

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: dirty 상태
- **type**: Text
- **label**: ● 편집 중 / ✓ 저장됨

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 풀스크린 상단 라이트 톤 바(LabelHeader). 좌: × 닫기(dirty 시 저장확인 모달) + 프레임 식별(#srcSn). 중앙: 저장상태(● 편집 중/✓ 저장됨). 포털 모드(channel=PORTAL)에서는 [검수제출]·[비식별 누락 신고]가 모두 미노출(검수제출 WORKER 전용, canReportDeident=!portalMode). 히스토리 진입은 채널과 무관하게 헤더에 두지 않는다 — 편집을 어느 버전에서 시작할지 고르는 일은 내부 채널의 라벨링 진입 시 띄우는 모달이 맡고, 포털에는 버전 관리 자체가 없다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 좌측 도구바

- **role**: navigation
- **layout**: stack

**components**:

#### [1]

- **note**: ToolType.SELECT
- **type**: Button
- **label**: 선택 (S)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: ToolType.BBOX
- **type**: Button
- **label**: 바운딩 박스 (B)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: ToolType.POLYGON
- **type**: Button
- **label**: 폴리곤 (P)

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 캔버스 확대·이동 상태를 초기화해 프레임 전체가 한 화면에 들어오도록 맞춘다. 단축키는 배정하지 않는다.
- **type**: Button
- **label**: 화면 맞춤

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 화면 표시만 회전하며 라벨 좌표는 바꾸지 않는다. 회전 중에는 그리기 도구를 잠근다.
- **type**: Button
- **label**: 왼쪽으로 90도 회전

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 화면 표시만 회전하며 라벨 좌표는 바꾸지 않는다. 회전 중에는 그리기 도구를 잠근다.
- **type**: Button
- **label**: 오른쪽으로 90도 회전

**columns**:

_(empty)_

**options**:

_(empty)_

#### [7]

- **note**: 끌어서 지정한 사각형이 화면을 채우도록 배율과 위치를 옮긴다. 되돌리기는 화면 맞춤이다.
- **type**: Button
- **label**: 영역 확대

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 캔버스에 격자를 겹쳐 표시할지 전환한다.
- **type**: Button
- **label**: 그리드 표시

**columns**:

_(empty)_

**options**:

_(empty)_

#### [9]

- **note**: 도구바 맨 아래 고정. 마우스를 올리면 단축키 표를 펼친다.
- **type**: Button
- **label**: 단축키 도움말 미리 보기

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 좌측 세로 아이콘 도구바. 선택(S) / 바운딩 박스(B) / 폴리곤(P) + 구분선 + 보기 조작(좌/우 90° 회전 — 회전 중에는 그리기 도구를 잠근다, 화면 맞춤, 영역 확대)과 그리드 표시 토글을 두고, 맨 아래 고정 위치에 단축키 도움말 미리 보기를 둔다. ★분할·추적을 비롯한 AI 보조 도구는 포털에 두지 않는다 — 이 화면의 도구는 사람이 직접 그리는 것만 제공하며 그 도구를 여는 버튼 자체를 렌더하지 않는다. 도구 선택은 캔버스 상태만 바꾸고 서버를 호출하지 않는다. 삭제·실행 취소·다시 실행·저장은 이 도구바가 아니라 캔버스 상단 옵션바에 둔다('캔버스 상단 옵션바' 섹션 참조).

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 라벨 마스터 사이드바

- **role**: side
- **layout**: list

**components**:

#### [1]

- **note**: 색상 박스 + 라벨명 + 단축키번호. 클릭 → activeLabelId
- **type**: List
- **label**: 라벨 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-024

- **description**: 도구바 우측의 라벨 마스터 목록. 라벨 마스터 조회(API-024 — 포털 사용자도 접근할 수 있다) 결과에서 사용 중인 라벨만 걸러 정렬 순서대로 표시한다. 각 항목은 색상 박스와 한글 라벨명, 단축키 번호로 구성한다. 항목을 고르면 이후 새로 그리는 객체의 기본 라벨이 된다.

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
- **triggers_api**: API-111

#### [2]

- **note**: GET /v1/portal/frames/{srcSn}/labels
- **type**: Custom
- **label**: 프레임 라벨 로더

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FrameLabelLoader
- **triggers_api**: API-110

- **description**: 메인 라벨링 캔버스. 조달 창구는 자산 출처로 갈린다 — 데이터마트 자산은 아래 창구를 쓰고, 본인이 올린 자산은 업로드 프레임 이미지 조회(API-149)와 업로드 프레임 라벨 조회(API-155)를 쓴다. 어느 출처든 화면이 그리는 방식과 도구 구성은 같다. 프레임 이미지는 포털 전용 이미지 조회(API-111)를 인증 헤더를 실어 받아 표시한다 — 주소를 이미지 태그에 바로 물리면 인증이 실리지 않아 401 이 난다. 라벨 좌표는 포털 전용 라벨 조회(API-110) 응답(데이터마트 원본 + 본인 작업분 병합)을 정규화해 표시한다. 같은 응답이 함께 싣는 라벨 마스터 참조와 트랙 식별자는 화면 어디에도 표시하지 않지만 버리지 않고 라벨마다 그대로 보유한다 — 저장 요청에 되돌려 보낼 값이라, 표시하지 않는다는 이유로 버리면 데이터마트 원본에서 불러온 라벨의 분류 연결과 트랙 연결이 저장 시점에 끊긴다. 두 값은 비어 있을 수 있고, 본인 작업분에서 왔든 데이터마트 원본에서 왔든 같은 형태로 다룬다. 활성 도구(바운딩 박스 / 폴리곤 / 선택)로 객체를 그리며 그린 결과는 본인 작업 데이터로만 저장한다(API-082) — 데이터마트 원본은 수정하지 않는다. ★분할·추적을 비롯한 AI 보조는 포털에 두지 않는다 — 자산 출처와 무관하다. 데이터마트 출처 자산은 데이터마트에 노출된(검수 승인) 영상만 허용한다. 본인이 올린 자산에는 그 조건을 적용하지 않는다 — 이 경로에는 검수가 없기 때문이며, 대신 프레임 추출이 끝나 준비가 완료된 자산만 허용한다.

**references_apis**:

- API-110
- API-111
- API-149
- API-155

**references_features**:

_(empty)_

### 우측 객체·속성·메타 패널

- **role**: side
- **layout**: stack

**components**:

#### [1]

- **note**: className 그룹화 트리 + 선택/삭제
- **type**: Custom
- **label**: 객체 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: ObjectClassTree
- **triggers_api**: API-110

#### [2]

- **note**: 선택 객체 라벨 변경 — 라벨 마스터 옵션
- **type**: Select
- **label**: 라벨

**columns**:

_(empty)_

**options**:

_(empty)_

- **triggers_api**: API-024

#### [3]

- **note**: BBOX 좌표 편집 — 경계 clamp
- **type**: Input
- **label**: X/Y/W/H 좌표

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 우측 고정 패널은 '객체 / 메타' 2탭으로 구성한다 — 내부 라벨링 화면과 같은 어휘이며 포털에는 이슈 탭이 없다. 이 섹션은 '객체' 탭의 내용을 다루고 '메타' 탭은 별도 섹션이 다룬다. (1) 객체 목록(ObjectClassTree): 목록 상단에 현재 프레임의 객체 수를 표시한다. 라벨을 className으로 그룹화, 펼치기/접기, 형태(BBOX/POLYGON), 행 선택(selectLabel)·삭제(removeLabel). (2) 객체 속성(ObjectAttributePanel): 선택 객체의 라벨 드롭다운(API-024 화이트리스트), BBOX X/Y/W/H 좌표 편집(경계 clamp). 라벨이 보유한 트랙 식별자는 이 패널에 표시하지 않으며 트랙 번호 변경·병합 수단도 두지 않는다 — 그 값은 저장 왕복에서 보존만 한다.

**references_apis**:

- API-024

**references_features**:

_(empty)_

### 메타 탭 — 메타·이벤트 어노테이션

- **role**: side
- **layout**: stack

**components**:

#### [1]

- **note**: 촬영환경·프레임 설명·개인정보 판정·시계열 메타. 본인이 고친 값은 원본과 구분해 표시한다
- **type**: Custom
- **label**: 메타 목록

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: PortalMetaPanel
- **triggers_api**: API-234

#### [2]

- **note**: 확인·수정·추가. 읽기 전용 메타와 기술 메타는 편집 수단을 두지 않는다
- **type**: Input
- **label**: 메타 값

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 포털 전용 오버레이에만 적재 — 원본·데이터마트 미수정(단방향)
- **type**: Button
- **label**: 메타 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-235

#### [4]

- **note**: 영상 단위 — 프레임을 옮겨도 같은 값이 선다
- **type**: Custom
- **label**: 이벤트 어노테이션

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: PortalEventAnnotationPanel
- **triggers_api**: API-236

#### [5]

- **note**: 포털 전용 오버레이에만 적재 — 원본 동결본 미수정
- **type**: Button
- **label**: 이벤트 어노테이션 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-237

- **description**: 우측 패널 '메타' 탭의 내용이다('객체' 탭에서 전환해야 보이며 항상 서 있는 독립 패널이 아니다). 내부 라벨링 화면과 같은 순서로 세로 나열한다 — 촬영환경 → 영상축 개인정보 → 프레임 설명 → 프레임축 개인정보 → 시계열 메타 → 이벤트 어노테이션. ★개인정보는 프레임 축과 영상 축의 입도가 달라 별개 축이다 — 두 값이 달라도 모순이 아니다(「영상 어딘가엔 있지만 이 프레임엔 없다」). 메타는 원본과 본인 오버레이를 병합해 보이며 본인이 고친 값은 원본과 구분해 표시한다. 영상 축 메타와 프레임 축 메타가 함께 서므로 어느 축인지 드러나야 하고, 저장할 때 그 축으로 되돌려 보낸다. ★이 다섯 축은 자산 출처를 가리지 않는다 — 데이터마트에서 불러온 영상과 본인이 올린 영상 모두에서 같은 순서로 서고 같은 방식으로 고친다. ★저장처는 화면이 가르지 않고 서버가 자산 출처로 판정한다 — 데이터마트 자산은 포털 전용 오버레이에만 쌓여 원본과 데이터마트 동결본을 수정하지 않으며 관제 통지·산출물 재생성을 일으키지 않고(라벨 저장과 같은 단방향 축이다), 본인이 올린 자산은 그 자산에 그대로 쌓인다. 읽기 전용 메타와 영상 기술 메타는 표시만 하고 고칠 수 없다 — 고치려 하면 창구가 거부한다. 이벤트 어노테이션은 영상 단위라 프레임을 옮겨도 같은 값이 선다. ⚠ 내부 화면과 달리 자동 생성값 프리필은 두지 않는다 — 포털은 외부 시계열 분석 위탁 연동을 갖지 않아 그 프리필의 원천(묘사 전문·추가 질문 응답)이 이 채널에 없다. 값은 Load 해 온 것과 사람이 쓴 것뿐이다.

**references_apis**:

- API-234
- API-235
- API-236
- API-237

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
- **triggers_api**: API-111

#### [2]

- **note**: 현재 인덱스/총 프레임 → jumpTo
- **type**: Custom
- **label**: 프레임 슬라이더

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: DarkFrameSlider

- **description**: 하단 고정 영역. 영상 전체 프레임의 썸네일 띠와 프레임 슬라이더로 구성한다. 썸네일 목록은 자산 출처로 갈려 조달한다 — 데이터마트 자산은 라벨 조회 응답(API-110)에 함께 오는 형제 프레임 정보에서 얻고, 본인이 올린 자산은 그 응답에 형제 프레임이 실려 오지 않으므로 업로드 자산 상세 조회(API-140)가 함께 주는 프레임 요약 목록에서 얻는다. 각 썸네일 이미지는 프레임 이미지 조회(데이터마트 API-111 · 업로드 자산 API-149)로 개별로 받아 현재 프레임을 강조한다. 썸네일을 클릭하거나 슬라이더를 끌어 프레임을 옮기며, 이동할 때 주소를 교체하고 라벨을 다시 조회한다. 좌우 방향키로도 이동한다. 이 프레임 이동은 자산 출처를 가리지 않는다 — 본인이 올린 영상에서 추출된 프레임도 같은 수단으로 앞뒤로 옮긴다. 프레임이 하나뿐인 자산에서는 옮길 자리가 없으므로 이 영역을 두지 않는다.

**references_apis**:

- API-110
- API-111
- API-140
- API-149

**references_features**:

_(empty)_

### 닫기 확인 모달 (dirty 가드)

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Dialog
- **label**: 저장 안 한 변경사항이 있습니다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [3]

- **type**: Button
- **label**: 저장 없이 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [4]

- **type**: Button
- **label**: 저장 후 닫기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-082

- **description**: ×(닫기) 클릭 시 미저장 변경(dirtyCount>0)이 있으면 표시되는 3-옵션 모달: [취소(머문)]·[저장 없이 닫기]·[저장 후 닫기]. 저장 후 닫기는 POST /v1/portal/user-labels(API-082) 후 navigate(-1). ESC/백드롭 = 머문.

**references_apis**:

- API-082

**references_features**:

_(empty)_

### 로딩·에러·잘못된 ID 상태

- **role**: main
- **layout**: detail

**components**:

#### [1]

- **type**: Custom
- **label**: 라벨 로딩

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: Spinner

#### [2]

- **type**: Alert
- **label**: 라벨 조회 실패

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: destructive

#### [3]

- **type**: Button
- **label**: 뒤로 가기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 풀스크린 라이트 톤 상태 화면. 라벨 로딩 중(Spinner), 라벨 조회 실패(에러 메시지+뒤로가기), 잘못된 프레임 ID(NaN 가드+뒤로가기). 프레임 라벨 조회(데이터마트 API-110 · 업로드 자산 API-155)의 로딩·오류 분기. 본인이 올린 자산은 준비 상태를 함께 가른다 — 자산 상세 조회(API-140)가 실패하거나(본인 자산이 아닌 경우를 포함한다) 자산이 아직 준비 완료가 아니면 안내만 표시하고 캔버스를 렌더하지 않는다. 처리에 실패한 자산과 아직 준비 중인 자산은 서로 다른 안내로 가른다 — 한쪽은 기다리면 되고 다른 쪽은 기다려도 되지 않기 때문이다.

**references_apis**:

- API-110
- API-140
- API-155

**references_features**:

_(empty)_

### 캔버스 상단 옵션바

- **role**: navigation
- **layout**: stack

**components**:

#### [1]

- **note**: 위치 표시와 이동을 단독으로 담당한다. 스크럽 슬라이더는 켜지 않는다.
- **type**: Custom
- **label**: 프레임 이동 컨트롤

**columns**:

_(empty)_

**options**:

_(empty)_

- **custom_name**: FrameNavigator

#### [2]

- **note**: 선택 객체를 지운다. 선택이 없으면 아무 일도 하지 않는다.
- **type**: Button
- **label**: 삭제

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 되돌리기 스택이 비면 비활성
- **type**: Button
- **label**: 실행 취소

**columns**:

_(empty)_

**options**:

_(empty)_

#### [4]

- **note**: 다시 실행 스택이 비면 비활성
- **type**: Button
- **label**: 다시 실행

**columns**:

_(empty)_

**options**:

_(empty)_

#### [5]

- **note**: 하한에 닿으면 비활성
- **type**: Button
- **label**: 축소

**columns**:

_(empty)_

**options**:

_(empty)_

#### [6]

- **note**: 상한에 닿으면 비활성
- **type**: Button
- **label**: 확대

**columns**:

_(empty)_

**options**:

_(empty)_

#### [7]

- **note**: 선택 객체의 표시 여부를 토글한다.
- **type**: Button
- **label**: 라벨 표시/숨김

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 현재 프레임의 라벨 전체를 본인 작업 데이터로 적재한다.
- **type**: Button
- **label**: 저장

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-082

- **description**: 캔버스 위에 고정된 가로 옵션바. 화면을 벗어나지 않고 삭제·실행 취소·다시 실행·프레임 이동·화면 배율·라벨 표시 여부·저장을 다루는 상시 컨트롤 모음이다. 삭제·실행 취소·다시 실행·저장은 좌측 도구바가 아니라 이 영역에 둔다 — 같은 동작의 진입점을 둘로 두지 않는다. 삭제는 선택 객체를 지우며 선택이 없으면 아무 일도 하지 않는다. 확대·축소는 한 번에 같은 배율만큼 바꾸고 상한·하한에 닿으면 그 방향 버튼을 비활성으로 둔다. 프레임 이동은 위치 표시와 이동을 단독으로 담당하고 스크럽 슬라이더는 하단 프레임 타임라인이 맡는다. 미저장 변경이 있는 상태로 프레임을 옮기면 저장 여부를 먼저 확인한다. 저장은 현재 프레임의 라벨 전체를 적재하며 창구가 자산 출처로 갈린다 — 데이터마트 자산은 본인 작업 데이터로 적재하고(API-082, 데이터마트 원본은 수정하지 않는다), 본인이 올린 자산은 그 자산의 프레임 라벨을 전체교체로 적재한다(API-154, 빈 목록은 그 프레임의 라벨을 모두 지우는 유효한 요청이다). 화면은 어느 쪽인지 판단하지 않는다. 저장 요청에는 조회로 받아 보유하던 라벨 마스터 참조와 트랙 식별자를 라벨마다 그대로 실어 보낸다. 저장 성공 응답도 두 값을 함께 돌려주는데, 그 값은 요청에 실려 보낸 값이 아니라 서버 판정을 거쳐 실제로 적재된 값이다 — 활성 라벨 마스터에 실재하지 않는 참조는 그 값만 비워서 적재되므로, 화면은 요청값을 그대로 유지하지 말고 응답값으로 갱신한다.

**references_apis**:

- API-082
- API-154

**references_features**:

_(empty)_

## brownfield

### status

new

### change_kind

- capability-add

### diff_summary

2차 포털 데이터마트 영상 수동 라벨링(BBOX/POLYGON) 화면 — AI 보조·검수·버전관리 미제공

## surface_kind

web

## consumes_apis

- API-024
- API-082
- API-110
- API-111
- API-140
- API-149
- API-154
- API-155
- API-234
- API-235
- API-236
- API-237

## attached_files

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

- IMPREC-129
- IMPREC-143

### progress

100

### subtasks

_(empty)_

### last_updated

2026-08-26T08:07:13.899Z

### module_paths

_(empty)_

## required_roles

- ROLE-003

## static_renders

### main

- **url**: /uploads/screens/4ece2c3f-8e99-46f5-9580-71108a76e578/SCREEN-029/main.html
- **label**: 포털 라벨링 화면 — 와이어프레임
- **width**: 1440
- **surface**: page
- **platform**: web

**sections**:

_(empty)_

- **description**: 자산 출처를 가리지 않는 통합 라벨링 화면. 데이터마트에서 불러온 영상과 본인이 올린 영상의 프레임을 같은 화면에서 라벨링한다.
- **source_hash**: e2ef1937a6fa41a4ef540ca88b180cdf5a4246adec8b2a2015d128317c2e404b
- **generated_at**: 2026-09-02T09:18:38.188Z
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

- UC-024
- UC-027

## covered_by_acceptances

- AC-1068
- AC-1069
- AC-1070
