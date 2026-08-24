# 앱 셸 + 내비게이션

<!-- SHELL-001 (app_shell) -->

# 저작도구 내부 채널 셸

## title

저작도구 내부 채널 셸

## device

desktop

## footer

### enabled

false

### components

_(empty)_

## header

### sticky

true

### enabled

true

### components

#### [1]

- **type**: Logo
- **align**: start
- **label**: 학습데이터 저작도구

#### [2]

- **type**: Badge
- **align**: end
- **label**: 역할 배지 — 검수자/작업자/포털, 역할별 색상 구분, 읽기 전용

#### [3]

- **type**: Avatar
- **align**: end
- **label**: 사용자 아바타 — 이름 첫 글자 원형 이니셜

#### [4]

- **type**: Text
- **align**: end
- **label**: 사용자 이름

## status

draft

## sidenav

### enabled

true

### position

left

### collapsible

false

### top_components

_(empty)_

### bottom_components

_(empty)_

## description

관제서버와 동일 도메인으로 진입하는 내부 채널(검수자·작업자)의 공통 셸. 상단 고정 헤더(높이 56px) + 좌측 고정 주 메뉴(폭 240px) + 본문으로 구성한다. 주 메뉴는 접거나 숨기는 기능을 두지 않는다 — 항상 같은 폭으로 노출된다. 이 셸은 데스크톱·태블릿을 전제하며 좁은 폭에서 메뉴를 서랍으로 바꾸는 분기를 두지 않는다(내부 작업자용 도구이고 라벨링·검수가 넓은 화면을 요구한다).

메뉴 항목의 구성은 이 셸이 정하지 않는다 — 내부 채널 내비게이션 정의를 그대로 따른다. 셸과 메뉴 정의 두 곳에 같은 목록을 적으면 한쪽만 갱신돼 어긋난다.

브레드크럼은 이 셸의 구성 요소가 아니다 — 본문 상단 페이지 헤더가 선택적으로 노출하며 셸이 모든 화면에 강제하지 않는다.

이 셸을 쓰지 않는 화면이 있다. 라벨링 캔버스는 화면 전체를 작업 영역으로 쓰는 풀스크린이라 셸 밖에서 자체 헤더를 둔다. 로드 버전 선택도 이 셸이 감싸지 않는다 — 독립 페이지가 아니라 라벨링 캔버스에 들어올 때 그 위에 열리는 모달이라, 이미 셸 밖에 있는 캔버스의 화면 영역을 그대로 쓰기 때문이다. 세션 인계·역할 클레임·접근 거부·개발용 로그인은 인증이 끝나기 전이거나 메뉴로 이동할 대상이 없어 셸을 두지 않는다. 반면 마킹 화면과 검수 상세 화면은 자체 상단 헤더를 갖지만 이 셸 안에서 렌더한다 — 셸 밖으로 빼지 않는다.

하단 푸터는 두지 않는다 — 노출 여부와 문안(근거법령·운영기관·문의처)이 아직 확정되지 않았고, 확정 전에 임시 문구를 내보내면 그 자리표시 값이 실제 정보인 것처럼 읽힌다. 확정되면 이 항목을 다시 켠다. 푸터가 없는 것은 결손이 아니라 이 결정의 결과이므로 정합 점검에서 누락으로 보고하지 않는다.

## nav_regions

### [1]

- **key**: lnb
- **slot**: left
- **label**: 주 메뉴
- **style**: persistent
- **width**: 240px
- **device**: desktop
- **orientation**: vertical
- **source_nav_id**: NAV-001

## applies_to_screens

- SCREEN-006
- SCREEN-008
- SCREEN-009
- SCREEN-011
- SCREEN-012
- SCREEN-018
- SCREEN-019
- SCREEN-020
- SCREEN-021
- SCREEN-022
- SCREEN-023
- SCREEN-024
- SCREEN-025
- SCREEN-026
- SCREEN-027
- SCREEN-030
- SCREEN-031
- SCREEN-032
- SCREEN-035
- SCREEN-036
- SCREEN-037
- SCREEN-038
- SCREEN-039

## default_navigation_id

NAV-001


---

<!-- SHELL-002 (app_shell) -->

# 포털 채널 셸

## title

포털 채널 셸

## device

responsive

## footer

### enabled

false

### components

_(empty)_

## header

### sticky

true

### enabled

true

### components

#### [1]

- **type**: Logo
- **align**: start
- **label**: AI 학습데이터 포털

#### [2]

- **type**: Text
- **align**: end
- **label**: 사용자 이름

## status

draft

## sidenav

### enabled

false

### position

left

### collapsible

false

### top_components

_(empty)_

### bottom_components

_(empty)_

## description

외부 포털 채널(포털 회원)의 공통 셸. 상단 헤더 + 본문으로 구성하며 좌측 주 메뉴를 두지 않는다 — 포털이 보유한 화면이 적어 메뉴 트리가 필요 없다.

헤더는 내부 채널과 두 가지가 다르다. 스크롤과 무관하게 고정하지 않고 문서 흐름을 따라 상단에 붙어 따라오게 한다. 그리고 역할 배지를 두지 않고 사용자 이름만 표시한다 — 포털 회원은 역할이 하나뿐이라 배지가 정보를 더하지 않는다.

이 셸은 반응형이다 — 좁은 폭에서 본문 좌우 여백을 줄이고 넓은 폭에서 늘린다. 포털은 외부 이용자가 개인 기기로 접근하므로 모바일·태블릿을 전제에 포함한다. 데스크톱을 전제하는 내부 채널 셸과 다른 점이다.

메뉴 항목의 구성은 이 셸이 정하지 않는다 — 포털 채널 내비게이션 정의를 따른다.

하단 푸터는 두지 않는다 — 노출 여부와 문안(근거법령·운영기관·문의처)이 아직 확정되지 않았고, 확정 전에 임시 문구를 내보내면 그 자리표시 값이 실제 정보인 것처럼 읽힌다. 확정되면 이 항목을 다시 켠다. 푸터가 없는 것은 결손이 아니라 이 결정의 결과이므로 정합 점검에서 누락으로 보고하지 않는다.

## nav_regions

_(empty)_

## applies_to_screens

- SCREEN-029

## default_navigation_id

NAV-002


---

<!-- NAV-001 (navigation_tree) -->

# 저작도구 내부 메뉴 (INTERNAL)

## nodes

### [1]

- **key**: dashboard
- **icon**: LayoutDashboard
- **kind**: link
- **label**: 대시보드
- **route**: /dashboard
- **screen_id**: SCREEN-011

### [2]

- **key**: video
- **kind**: group
- **label**: 영상

**children**:

#### [1]

- **key**: video-completed
- **kind**: link
- **label**: [폐기] 영상 목록
- **visible_when**: 폐기 — 영상 처리 현황과 같은 화면을 두 번 기록한 것이라 메뉴에 두지 않는다. 영상 그룹에서 주 메뉴로 노출하는 항목은 영상 처리 현황 하나다

#### [2]

- **key**: video-status
- **kind**: link
- **label**: 영상 처리 현황
- **route**: /video/status
- **screen_id**: SCREEN-008

#### [3]

- **key**: video-detail
- **kind**: link
- **label**: 영상 상세
- **route**: /video/:id
- **screen_id**: SCREEN-009
- **visible_when**: 목록 행 클릭 진입 (LNB 미노출)

#### [4]

- **key**: marking
- **kind**: link
- **label**: 마킹
- **route**: /marking/:rawSn
- **screen_id**: SCREEN-006
- **visible_when**: 영상 진입 후 마킹 (LNB 미노출)

### [3]

- **key**: task
- **kind**: group
- **label**: 작업

**children**:

#### [1]

- **key**: task-list
- **kind**: link
- **label**: 작업 목록
- **route**: /task
- **screen_id**: SCREEN-012

#### [2]

- **key**: review-list
- **kind**: link
- **label**: 검수 목록
- **route**: /review
- **screen_id**: SCREEN-018
- **required_role**: ROLE-001

#### [3]

- **key**: label-canvas
- **kind**: link
- **label**: 라벨링 캔버스
- **route**: /label/:id
- **screen_id**: SCREEN-005
- **visible_when**: 작업 진입 후 풀스크린 라벨링 (LNB 미노출)

#### [4]

- **key**: review-detail
- **kind**: link
- **label**: 검수 상세
- **route**: /review/:id
- **screen_id**: SCREEN-019
- **visible_when**: 검수 목록 행 클릭 진입 (LNB 미노출)
- **required_role**: ROLE-001

### [4]

- **key**: data
- **kind**: group
- **label**: 데이터

**children**:

#### [1]

- **key**: augment-request
- **kind**: link
- **label**: 증강 요청
- **route**: /augment
- **screen_id**: SCREEN-022
- **required_role**: ROLE-001

#### [2]

- **key**: augment-result
- **kind**: link
- **label**: 증강 결과
- **route**: /augment/result/:rawSn
- **screen_id**: SCREEN-023
- **visible_when**: 증강 작업 카드 클릭 진입
- **required_role**: ROLE-001

- **required_role**: ROLE-001

### [5]

- **key**: stat
- **kind**: group
- **label**: 통계

**children**:

#### [1]

- **key**: stat-worker
- **kind**: link
- **label**: 작업자 통계
- **route**: /stat/worker
- **screen_id**: SCREEN-020

#### [2]

- **key**: stat-overall
- **kind**: link
- **label**: 전체 구축 현황
- **route**: /stat/overall
- **screen_id**: SCREEN-021
- **required_role**: ROLE-001

### [6]

- **key**: notice
- **kind**: group
- **label**: 게시판

**children**:

#### [1]

- **key**: notice-list
- **kind**: link
- **label**: 게시판
- **route**: /notice
- **screen_id**: SCREEN-030
- **visible_when**: 게시판 진입 (REVIEWER/WORKER)

#### [2]

- **key**: notice-detail
- **kind**: link
- **label**: 공지 상세
- **route**: /notice/:id
- **screen_id**: SCREEN-031
- **visible_when**: 공지 목록 행 클릭 진입 (LNB 미노출)

#### [3]

- **key**: notice-new
- **kind**: link
- **label**: 공지 작성
- **route**: /notice/new
- **screen_id**: SCREEN-036
- **visible_when**: 게시판의 새 게시글 작성 버튼 진입 (LNB 미노출)
- **required_role**: ROLE-001

#### [4]

- **key**: notice-edit
- **kind**: link
- **label**: 공지 수정
- **route**: /notice/:id/edit
- **screen_id**: SCREEN-037
- **visible_when**: 공지 상세의 수정 버튼 진입 (LNB 미노출)
- **required_role**: ROLE-001

### [7]

- **key**: manage
- **kind**: group
- **label**: 관리

**children**:

#### [1]

- **key**: manage-users
- **kind**: link
- **label**: 사용자 관리
- **route**: /manage/users
- **screen_id**: SCREEN-024
- **required_role**: ROLE-001

#### [2]

- **key**: manage-settings
- **kind**: link
- **label**: 시스템 설정
- **route**: /manage/settings
- **screen_id**: SCREEN-025
- **required_role**: ROLE-001

#### [3]

- **key**: manage-labels
- **kind**: link
- **label**: 라벨 관리
- **route**: /manage/labels
- **screen_id**: SCREEN-035
- **required_role**: ROLE-001

#### [4]

- **key**: manage-presets
- **kind**: link
- **label**: 프리셋 관리
- **route**: /manage/presets
- **screen_id**: SCREEN-026
- **required_role**: ROLE-001

#### [5]

- **key**: manage-deident
- **kind**: link
- **label**: 비식별 신고
- **route**: /manage/deident-reports
- **screen_id**: SCREEN-032
- **required_role**: ROLE-001

#### [6]

- **key**: manage-event-types
- **kind**: link
- **label**: 이벤트유형 관리
- **route**: /manage/event-types
- **screen_id**: SCREEN-038
- **required_role**: ROLE-001

#### [7]

- **key**: manage-imports
- **kind**: link
- **label**: 외부 산출물 이관
- **route**: /manage/imports
- **screen_id**: SCREEN-039
- **required_role**: ROLE-001

#### [8]

- **key**: manage-manual-upload
- **kind**: link
- **label**: 수동 업로드
- **route**: /dev/upload
- **screen_id**: SCREEN-027
- **required_role**: ROLE-001

- **required_role**: ROLE-001

## title

저작도구 내부 메뉴 (INTERNAL)

## status

draft

## audience

internal

## description

관제서버와 동일 도메인 SSO 로 진입하는 저작도구 내부 채널의 LNB 메뉴 트리. 그룹은 대시보드 / 영상 / 작업 / 데이터 / 통계 / 게시판 / 관리 7개다. '영상' 그룹의 LNB 노출 항목은 영상 처리 현황(SCREEN-008) 하나다 — 영상 목록은 같은 화면을 두 번 기록한 것이라 영상 처리 현황으로 흡수됐고, 그 노드는 폐기 사실을 남기기 위해 표기만 남기며 메뉴에 두지 않는다. 검수 목록은 별도 그룹이 아니라 '작업' 그룹 안의 항목으로 노출하며 REVIEWER 에게만 보인다. '게시판' 그룹의 LNB 노출 항목은 '게시판' 하나다. '관리' 그룹은 사용자 관리·시스템 설정·라벨 관리·프리셋 관리·비식별 신고·이벤트유형 관리·외부 산출물 이관으로 이뤄지며 REVIEWER 에게만 보인다. 노출은 역할(REVIEWER / WORKER) 기반이다. LNB 메뉴로는 노출되지 않지만 경로로 도달하는 화면(마킹·영상 상세·라벨링 캔버스·검수 상세·증강 결과·공지 상세·공지 작성·공지 수정)은 진입 맥락 노드로 보조 포함한다(visible_when = LNB 미노출). 공지 작성·공지 수정은 REVIEWER 만 진입할 수 있다. R1 활성 화면만 포함하며, 개발용·폐기 화면(개발 로그인·오토라벨 테스트·비식별 검토·포털 홈·작업 배정 SCREEN-013·오토라벨 요약 SCREEN-014·VLM 메타 검토 SCREEN-015)은 제외한다.


---

<!-- NAV-002 (navigation_tree) -->

# 포털 메뉴 (PORTAL)

## nodes

### [1]

- **key**: portal-home
- **kind**: link
- **label**: 데이터마트 영상
- **route**: /portal
- **screen_id**: SCREEN-028
- **visible_when**: 포털 진입 시 기본 화면. 검수 완료된 데이터마트 영상 목록을 보여준다.
- **required_role**: ROLE-003

### [2]

- **key**: portal-labeling
- **kind**: link
- **label**: 라벨링
- **route**: /portal/label/:id
- **screen_id**: SCREEN-029
- **visible_when**: 포털 영상 선택 후 간편 라벨링 진입
- **required_role**: ROLE-003

### [3]

- **key**: portal-uploads
- **kind**: link
- **label**: 내 업로드
- **route**: /portal/uploads
- **screen_id**: SCREEN-033
- **visible_when**: 포털 사용자가 본인 소유 이미지·영상을 직접 업로드하고 업로드 자산 목록·상태를 확인한다.
- **required_role**: ROLE-003

### [4]

- **key**: portal-upload-labeling
- **kind**: link
- **label**: 업로드 자산 라벨링
- **route**: /portal/uploads/:uldSn/label
- **screen_id**: SCREEN-034
- **visible_when**: 업로드 자산이 준비 완료 상태일 때 목록에서 진입해 수동 라벨링한다.
- **required_role**: ROLE-003

## title

포털 메뉴 (PORTAL)

## status

draft

## audience

mobile

## description

외부 포털 채널(PORTAL_USER)의 메뉴. 포털에는 LNB 를 두지 않는다 — 데이터마트 영상 선택 홈과 간편 라벨링, 그리고 본인 자산 업로드와 그 업로드 자산의 수동 라벨링 화면을 보유한다. 포털 사용자는 본인 소유 이미지·영상을 직접 업로드해 수동 라벨링한 뒤 본인 데이터를 내려받을 수 있으며, 이 경로는 내부 파이프라인·데이터마트와 완전히 분리된 별도 경로다. 다만 관제 학습용 적재를 비롯한 내부 파이프라인 적재는 포털에서 제공하지 않으며, 오토라벨링·검수·버전관리도 포털 전 구간에서 제공하지 않는다. 반응형(PC / 태블릿 / 모바일)을 따른다.

