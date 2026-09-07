---
logicraft_item: SCREEN-033
type: screen_spec
version: 38
domain: DOMAIN-013
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-07T13:24:58.241Z
status: CHANGED
prev_version: 34
content_hash: 43ba7150b415c73c202a5ec49b6b3e2347d7e8d5054594b358ff73cd9e94c138
stale: true
raw: ./_raw/SCREEN-033.json
links:
  based_on: ["[[ADR-013]]"]
  belongs_to_domain: ["[[DOMAIN-013]]"]
  consumes: ["[[API-140]]", "[[API-142]]", "[[API-151]]", "[[API-157]]", "[[API-159]]", "[[API-161]]", "[[API-163]]", "[[API-166]]", "[[API-169]]", "[[API-171]]", "[[API-231]]"]
  covered_by: ["[[AC-1070]]", "[[AC-1071]]"]
  implements: ["[[IMPREC-024]]"]
  realizes: ["[[UC-027]]"]
  references: ["[[API-140]]", "[[API-157]]", "[[API-159]]", "[[API-231]]"]
  requires: ["[[ROLE-003]]"]
  applies_to_backward: ["[[SHELL-002]]"]
  designs_backward: ["[[SD-026]]"]
  granted_on_backward: ["[[ROLE-003]]"]
  navigates_to_backward: ["[[NAV-002]]"]
  realizes_backward: ["[[MOD-017]]", "[[MOD-021]]"]
  references_backward: ["[[ADR-061]]", "[[SEQ-019]]", "[[UC-027]]"]
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

PORTAL_USER가 본인 소유 영상 자산을 직접 업로드하는 화면. 데이터마트 라벨링과 갈라진 별도 파이프라인이며 관제 학습용 배치·오토라벨링·검수·버전관리를 전혀 거치지 않는다. 영상은 기존 TUS 재개 가능 업로드 엔진을 포털 전용 endpoint(/portal/uploads/tus)로 재사용해 mp4/mov/avi, 최대 5GB까지 청크 업로드한다. 업로드 자산은 UPLOADED→PROCESSING→READY|FAILED 상태로 전이한다. 영상은 마킹을 마쳐야 그 지점으로 프레임이 추출되므로 UPLOADED 는 추출을 기다리는 상태가 아니라 마킹을 기다리는 상태이며, 목록은 그 뜻이 드러나도록 「마킹 대기」로 표기한다 — 표기만 바뀌고 상태값 자체는 그대로다. 목록에서 상태 배지 + 만료 예정일 + 마킹 대기 영상 자산의 마킹 진입(/portal/uploads/:uldSn/marking) + 준비 완료 자산의 라벨링 진입(/portal/label/:id — 데이터마트 자산과 같은 라벨링 화면이다) + 삭제(PROCESSING 중이면 BE가 409로 거부)를 제공한다. 라벨링 진입 경로가 받는 값은 자산이 아니라 프레임 식별자이므로, 목록에서 이동할 때 자산 상세를 조회해 프레임 요약 목록의 첫 프레임 식별자를 얻어 그 프레임으로 이동한다 — 목록이 가진 자산 식별자로 프레임 식별자를 추측하지 않는다. 자산의 라벨 내보내기와 원본 파일 내려받기도 이 목록의 자산별 액션이다 — 내려받기는 라벨링 화면에 두지 않고 목록이 담당한다. 이 경로의 자산은 비식별 처리를 거치지 않아 가공되지 않은 개인정보가 그대로 보관되므로 보존기간을 짧게 두고, 기간이 지나면 저장 행과 파일을 함께 삭제한다. 사용자가 삭제 시점을 예측할 수 있도록 목록의 각 자산에 만료 예정일을 날짜까지 표기한다. 업로드한 영상 자산에 한해 AI 증강 연동을 요청할 수 있으며, 요청 자리는 목록의 자산별 액션이다 — 서버가 외부로 보내는 비동기 위탁이라 포털 사용자가 외부 추론 엔드포인트를 직접 호출하지 않는다. 이 증강은 저작도구가 자체 수행한다 — 대상 자산 선택·생성 조건 지정·외부 위탁까지 저작도구가 처리하며, 저작도구를 품는 외부 채널이 제공하는 증강 요청 창구를 호출하지 않는다. 요청 이후의 현황과 결과는 증강 요청 현황·결과 화면에서 확인하며, 이 화면에는 그 화면으로 가는 진입만 둔다 — 증강 결과물의 결과 확인·후속 작업·내려받기는 이 화면이 담당하지 않는다. 이 화면이 담당하는 내려받기는 업로드 자산 자체의 것이다. 증강 결과물도 이 목록에 함께 뜬다 — 결과물이 업로드 자산과 같은 계보에 앉기 때문이다. 다만 그 행에는 증강 요청 액션을 두지 않는다. 증강으로 만들어진 자산을 다시 증강하지 않는다. 증강 요청은 버튼 하나로 끝나지 않는다 — 목록에서 요청을 누르면 생성 조건을 입력하는 요청 폼이 화면 안 확인 창으로 열리고, 시간대·계절·날씨·지형·심각도 다섯 항목을 전부 고른 뒤에야 요청을 보낼 수 있다. 값은 닫힌 목록에서 고르며, 하나라도 비거나 목록 밖 값이면 접수 창구가 입력 오류로 거부한다. 자유 지시문은 생성 조건과 분리한 별개 입력이며 선택이다. 이벤트 유형과 세부 유형을 고르는 입력칸은 두지 않는다 — 요청자가 고르지 않고 서버가 중립 값으로 고정해 보낸다. 요청 대상은 준비 완료된 본인 업로드 영상이며 증강 결과물에는 요청을 걸지 않는다. 미제공은 오토라벨링(YOLO)·SAM2 인터랙티브 분할·자동추적·키포인트·검수·버전관리이며, 시계열 축의 미제공은 외부 시계열 분석 서버로 나가는 위탁 연동(호출·콜백)을 뜻한다. 접근: PORTAL_USER.

## sections

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

- **note**: UPLOADED 는 「마킹 대기」로 표기한다 — 마킹을 마쳐야 프레임이 추출되므로 이 자리에서 알려야 할 것은 업로드가 끝났다는 사실이 아니라 다음에 무엇을 해야 하는가이다. 표기만 그렇게 하고 상태값 자체는 바뀌지 않는다.
- **type**: Badge
- **label**: 상태 배지 (마킹 대기/처리중/준비 완료/실패)

**columns**:

_(empty)_

**options**:

- UPLOADED
- PROCESSING
- READY
- FAILED

#### [3]

- **note**: 준비 완료 자산만 노출 → /portal/label/:id (데이터마트 자산과 같은 라벨링 화면). 그 경로가 받는 값은 자산이 아니라 프레임 식별자다. 목록에서 이동할 때 자산 상세(API-140)를 조회해 프레임 요약 목록의 첫 프레임 식별자를 얻어 그 프레임으로 이동한다 — 목록이 가진 자산 식별자로 프레임 식별자를 추측하지 않는다. 그 목록은 프레임 순번 오름차순이라 첫 원소가 그 자산의 첫 프레임이다.
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

- **note**: 준비 완료 영상 자산 행에만 노출한다. 증강으로 만들어진 결과물 행에는 두지 않는다 — 증강한 것을 다시 증강하지 않는다. 누르면 생성 조건을 입력하는 요청 폼이 화면 안 확인 창으로 열린다 — 이 버튼 자체가 요청을 보내지 않는다.
- **type**: Button
- **label**: AI 증강 요청

**columns**:

_(empty)_

**options**:

_(empty)_

#### [7]

- **note**: 목록 상단에 한 번 둔다 — 자산별 액션이 아니다. 증강 요청 현황·결과 화면으로 이동하며, 결과 확인·후속 작업·내려받기는 그 화면이 담당한다.
- **type**: Link
- **label**: 증강 요청 현황·결과

**columns**:

_(empty)_

**options**:

_(empty)_

#### [8]

- **note**: 서버가 페이지로 내려주고 페이지 크기에 상한이 걸려 있어, 옮길 수단이 없으면 첫 쪽에 있는 자산에만 도달한다. 그 뒤의 자산은 라벨링·삭제·증강 요청 어느 것도 할 수 없다.
- **type**: Pagination
- **label**: 업로드 자산 목록 페이지

**columns**:

_(empty)_

**options**:

_(empty)_

- **description**: 본인 업로드 자산 목록(GET /portal/uploads). 각 행: 원본 파일명(텍스트 노드, XSS 방어) + 상태 배지(마킹 대기/처리중/준비 완료/실패, PROCESSING은 폴링) + 타입·크기·프레임수 + 만료 예정일. READY 자산만 '라벨링' 링크(/portal/label/:id) 노출 — 데이터마트 자산과 같은 화면이다. 삭제 버튼은 PROCESSING 중이면 title 툴팁과 함께 비활성(BE 409 정합), 확인 후 요청. 만료 예정일은 응답의 expiresAt 을 '만료: YYYY-MM-DD' 형식으로 날짜까지만 표기한다(시각은 표기하지 않는다). expiresAt 이 없는 자산은 만료 예정일 자리를 비운다 — 처리중 자산만 아직 삭제 대상이 아니라 값이 내려오지 않는다. 만료 예정일이 내려오는 것은 마킹 대기·준비 완료·실패 자산이며, 마킹 대기 자산의 기산점은 등록일이다 — 올린 뒤 마킹하지 않고 두면 그 시점부터 보존기간이 흐른다. 이 값은 조회 시점의 보존기간 설정으로 계산되어 응답에 실려 오므로 화면이 따로 보관하지 않고 받은 값을 그대로 표시하며, 매 조회마다 갱신한다. 만료가 가까운 자산을 색·아이콘으로 강조하는 표기는 두지 않는다. 준비 완료 상태인 영상 자산 행에는 「AI 증강 요청」 액션을 함께 둔다 — 아직 준비되지 않은 영상에는 두지 않는다(라벨링 링크와 같은 규칙이라, 그 자산에서 할 수 없는 액션은 비활성으로 두지 않고 아예 노출하지 않는다). 본인이 올린 자산이면 요청할 수 있으며 검수를 통과했는지는 묻지 않는다 — 이 경로에는 검수가 없다. 요청 이후의 현황·결과는 목록 상단의 「증강 요청 현황·결과」 진입에서 확인한다 — 증강 결과물의 결과 확인·후속 작업·내려받기는 그 화면이 담당한다. 「AI 증강 요청」을 누르면 곧바로 요청이 나가지 않고 생성 조건을 입력하는 요청 폼이 화면 안 확인 창으로 열린다 — 그 창에서 시간대·계절·날씨·지형·심각도 다섯 항목을 모두 고른 뒤에야 요청을 보낼 수 있다.

**references_apis**:

- API-140
- API-231

**references_features**:

_(empty)_

### 마킹 진입 (마킹 대기 자산 행)

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 마킹 대기 영상 자산 행에만 노출한다. 노출 규칙은 이 화면의 라벨링 링크·AI 증강 요청과 같다 — 그 자산에서 할 수 없는 액션은 비활성으로 두지 않고 아예 노출하지 않는다. 누르면 그 자산의 마킹 화면(/portal/uploads/:uldSn/marking)으로 이동한다.
- **type**: Link
- **label**: 마킹

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary

- **description**: 업로드가 끝나 아직 마킹하지 않은 영상 자산 행에 마킹 진입을 둔다 — 마킹이 프레임을 어느 지점에서 뽑을지 정하므로 이 상태에서 사용자가 해야 할 다음 일이다. 노출 규칙은 이 화면의 라벨링 링크·AI 증강 요청과 같다: 마킹 대기 자산 행에만 두고, 그 자산에서 할 수 없는 액션은 비활성으로 두지 않고 아예 노출하지 않는다. 처리중·준비 완료·실패 자산 행에는 두지 않는다 — 마킹은 한 번뿐이고 이미 저장한 자산의 재마킹은 제공하지 않으므로, 두면 눌러 봐야 거절되는 자리가 되어 회복 경로를 잘못 안내한다. 이미 마킹한 자산을 다시 마킹하려면 그 자산을 지우고 다시 올려야 하며, 그 안내는 마킹 화면이 담당한다. 영상이 아닌 자산 행에는 두지 않는다 — 이벤트 구간이라는 개념이 없다. 마킹 화면으로 들어가는 자리는 이 목록뿐이다 — 라벨링 화면에는 두지 않는다.

**references_apis**:

_(empty)_

**references_features**:

_(empty)_

### 자산별 내려받기 (준비 완료 자산 행)

- **role**: main
- **layout**: stack

**components**:

#### [1]

- **note**: 준비 완료 자산 행에만 노출한다. 증강 결과물 행에는 두지 않는다. 라벨만 받으므로 곧바로 끝나 취소를 두지 않는다.
- **type**: Button
- **label**: 라벨 내보내기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [2]

- **note**: 준비 완료 자산 행에만 노출한다. 증강 결과물 행에는 두지 않는다. 자산 원본을 그대로 받으므로 영상이면 매우 커질 수 있고, 받는 중에는 취소할 수 있어야 한다.
- **type**: Button
- **label**: 원본 파일 내려받기

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

#### [3]

- **note**: 원본 파일을 받는 중일 때만 '원본 파일 내려받기' 자리에 나타난다. 누르면 전송을 멈추고 다시 받을 수 있는 상태로 되돌린다. 사용자가 스스로 멈춘 것이므로 실패 안내를 띄우지 않는다.
- **type**: Button
- **label**: 내려받기 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: outline

- **description**: 업로드 자산의 라벨 내보내기와 원본 파일 내려받기를 목록의 자산 행에 둔다 — 라벨링 화면이 아니라 이 목록이 담당한다. 노출 규칙은 라벨링 링크·AI 증강 요청과 같다: 준비 완료 자산 행에만 두고, 그 자산에서 할 수 없는 액션은 비활성으로 두지 않고 아예 노출하지 않는다. 준비되기 전에 두지 않는 근거는 규칙 일치만이 아니다 — 라벨 내보내기는 프레임과 그 프레임의 라벨을 묶어 내보내므로 프레임 추출이 끝나기 전에는 내보낼 알맹이가 없고, 원본 파일 내려받기는 저장 경로가 확정되지 않은 자산에서 없는 파일을 가리켜 실패한다. 증강 결과물 행에는 두지 않는다 — 그 내려받기는 증강 요청 현황·결과 화면이 요청 단위로 이미 담당하므로 같은 행위의 진입이 둘이 된다(증강 요청 액션을 그 행에 두지 않는 것과 같은 규칙이다). 원본 파일 내려받기는 자산 파일을 그대로 받는 경로라 영상이면 매우 커질 수 있다. 요청 제한시간은 그 크기를 끝까지 받아낼 수 있는 값이어야 한다 — 일반 조회와 같은 짧은 제한시간을 쓰면 큰 자산은 받을 방법이 없다. 받는 중에는 같은 자리에서 전송을 멈출 수 있게 하고, 취소하면 전송을 중단해 다시 받을 수 있는 상태로 되돌린다. 받다 만 파일은 남기지 않는다. 사용자가 누른 취소는 오류가 아니라 정상 종료이므로 실패 안내를 띄우지 않는다 — 전송이 끊겨 실패한 경우와 한 갈래로 묶으면 스스로 멈춘 사용자에게 연결을 확인하라고 권하게 된다. 라벨 내보내기에는 취소를 두지 않는다. 두 내려받기는 받는 동안 서로 비활성으로 둔다.

**references_apis**:

- API-157
- API-159

**references_features**:

_(empty)_

### 증강 요청 폼

- **role**: modal
- **layout**: form

**components**:

#### [1]

- **type**: Heading
- **label**: AI 증강 요청

**columns**:

_(empty)_

**options**:

_(empty)_

#### [2]

- **note**: 목록에서 요청을 누른 그 자산을 보여 준다. 이 창에서 대상을 바꾸지 않는다.
- **type**: KeyValue
- **label**: 대상 영상

**columns**:

_(empty)_

**options**:

_(empty)_

#### [3]

- **note**: 필수. 닫힌 목록에서만 고르고 직접 적어 넣을 수 없다. 표시명과 보내는 코드의 대응 — 새벽=DAWN · 낮=DAY · 해질녘=DUSK · 밤=NIGHT.
- **type**: Select
- **label**: 시간대

**columns**:

_(empty)_

**options**:

- 새벽
- 낮
- 해질녘
- 밤

#### [4]

- **note**: 필수. 닫힌 목록에서만 고르고 직접 적어 넣을 수 없다. 표시명과 보내는 코드의 대응 — 봄=SPRING · 여름=SUMMER · 가을=AUTUMN · 겨울=WINTER.
- **type**: Select
- **label**: 계절

**columns**:

_(empty)_

**options**:

- 봄
- 여름
- 가을
- 겨울

#### [5]

- **note**: 필수. 닫힌 목록에서만 고르고 직접 적어 넣을 수 없다. 표시명과 보내는 코드의 대응 — 맑음=CLEAR · 흐림=CLOUDY · 비=RAIN · 눈=SNOW · 안개=FOG · 바람=WINDY.
- **type**: Select
- **label**: 날씨

**columns**:

_(empty)_

**options**:

- 맑음
- 흐림
- 비
- 눈
- 안개
- 바람

#### [6]

- **note**: 필수. 닫힌 목록에서만 고르고 직접 적어 넣을 수 없다. 표시명과 보내는 코드의 대응 — 도로=ROAD · 지하차도=UNDERPASS · 하천=RIVER · 도시=URBAN · 주거지=RESIDENTIAL · 농촌=RURAL · 산지=MOUNTAIN · 산림=FOREST.
- **type**: Select
- **label**: 지형

**columns**:

_(empty)_

**options**:

- 도로
- 지하차도
- 하천
- 도시
- 주거지
- 농촌
- 산지
- 산림

#### [7]

- **note**: 필수. 닫힌 목록에서만 고르고 직접 적어 넣을 수 없다. 표시명과 보내는 코드의 대응 — 낮음=LOW · 보통=MEDIUM · 높음=HIGH.
- **type**: Select
- **label**: 심각도

**columns**:

_(empty)_

**options**:

- 낮음
- 보통
- 높음

#### [8]

- **note**: 선택 입력이며 1000자까지 받는다. 비워 두어도 요청할 수 있고, 생성 조건 다섯 항목을 대신하지 않는다.
- **type**: Textarea
- **label**: 자유 지시문 (선택)

**columns**:

_(empty)_

**options**:

_(empty)_

- **placeholder**: 원본 시점과 구조를 유지한 채 바꾸고 싶은 점을 적는다

#### [9]

- **note**: 폼 안에 상시 안내로 둔다. 접수 창구가 입력 오류로 돌려보낸 사유도 이 자리에 띄운다.
- **type**: Alert
- **label**: 다섯 항목을 모두 고르지 않았거나 목록에 없는 값이면 요청이 거부된다

**columns**:

_(empty)_

**options**:

_(empty)_

#### [10]

- **note**: 다섯 항목을 모두 고르기 전에는 비활성. 누르면 접수만 이뤄지고 결과는 증강 요청 현황·결과 화면에서 확인한다.
- **type**: Button
- **label**: 요청

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: primary
- **triggers_api**: API-231

#### [11]

- **note**: 창을 닫고 입력한 값을 버린다. 요청을 보내지 않는다.
- **type**: Button
- **label**: 취소

**columns**:

_(empty)_

**options**:

_(empty)_

- **variant**: secondary

- **description**: 목록의 「AI 증강 요청」을 누르면 이 요청 폼이 화면 안 확인 창으로 열린다 — 브라우저가 자체로 띄우는 창을 쓰지 않는다. 폼은 생성 조건 다섯 항목을 전부 필수 선택으로 받는다. 값은 닫힌 목록에서 고르며 자유 입력을 허용하지 않는다: 시간대는 새벽·낮·해질녘·밤, 계절은 봄·여름·가을·겨울, 날씨는 맑음·흐림·비·눈·안개·바람, 지형은 도로·지하차도·하천·도시·주거지·농촌·산지·산림, 심각도는 낮음·보통·높음이다. 다섯을 모두 요구하는 것은 하나라도 비면 위탁받는 쪽이 어떤 기본값으로 채울지 이쪽에서 알 수 없어 같은 요청의 결과가 비결정적이 되기 때문이다. 자유 지시문은 생성 조건과 분리한 별개 입력이며 선택이다 — 1000자까지 받고 비워 두어도 요청할 수 있다. 다섯 항목을 모두 고르기 전에는 요청 버튼을 누를 수 없게 하고, 그럼에도 미입력이거나 목록 밖 값이 실려 나가면 접수 창구가 입력 오류로 거부하므로 그 사유를 이 창 안에서 안내한다. 이벤트 유형과 세부 유형을 고르는 입력칸은 두지 않는다 — 요청자가 고르지 않고 서버가 중립 값으로 고정해 보낸다. 이 증강은 이미 이벤트가 담긴 프레임을 겨울·야간·우천 등으로 바꾸는 것이라 무엇을 만들지 정할 자리가 없고, 우리 이벤트 체계는 넓은 데 비해 위탁받는 쪽의 허용값은 좁아 대응되지 않는 영상에서는 요청자가 사실과 다른 값을 고를 수밖에 없다. 요청자가 이벤트 유형을 고르는 형태로 되돌리지 않는다. 요청 대상은 준비 완료된 본인 업로드 영상이며 증강 결과물에는 이 폼을 열지 않는다. 요청을 보내면 접수 사실만 확인하고 창을 닫으며, 진행 상태와 결과는 증강 요청 현황·결과 화면에서 확인한다. 이 폼은 본문과 한 그림에 그리지 않고 본문 위에 뜨는 별도 표면으로 둔다. 항목별 표시명과 보내는 코드의 대응은 그 선택칸에 적으며, 코드 값역의 정본은 접수 창구(API-231)이고 이 화면은 그것을 인용한다 — 창구가 값을 넓히면 여기에 없는 항목·값도 감추지 않고 그대로 드러낸다.

**references_apis**:

- API-231

**references_features**:

_(empty)_

## brownfield

### status

new

### decided_by

ADR-013

### diff_summary

신규 화면 — 포털 사용자 본인 영상 자산 업로드. 내부 파이프라인·데이터마트와 갈라진 경로이며(자산은 공용 원장에 적재하고 출처 판별자로 가른다 — ADR-058), 오토라벨링·검수·버전관리 미제공.

## surface_kind

web

## consumes_apis

- API-140
- API-142
- API-151
- API-157
- API-159
- API-161
- API-163
- API-166
- API-169
- API-171
- API-231

## attached_files

_(empty)_

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

- **description**: 영상 업로드와 자산 목록. 마킹 대기 자산에서 마킹으로, 준비 완료 자산에서 라벨링·내려받기로 들어간다.
- **source_hash**: c461e8f322c5c3a9672b619ecd790ba81b5b5c15113db35007b0d7c2a55cb7be
- **generated_at**: 2026-09-02T23:25:56.193Z
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

- UC-027

## covered_by_acceptances

- AC-1070
- AC-1071
