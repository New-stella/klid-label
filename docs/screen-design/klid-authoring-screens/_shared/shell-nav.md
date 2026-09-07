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
- **label**: 역할 배지 — 관리자/검수자/작업자/포털, 역할이 아직 없으면 미배정(경고 아이콘 병기)으로 보이며 네 값 중 하나로 임의로 채우지 않는다. 모르는 값은 비우지 않고 받은 값을 그대로 중립 색으로 노출한다. 역할별 색상 구분, 읽기 전용

#### [3]

- **note**: 이니셜은 표시하는 이름의 첫 글자이므로 이름과 같은 조달원을 따른다. 이름이 「내 정보」 조회 응답으로 정해지면 이니셜도 그 이름의 첫 글자로 함께 바뀐다. 이름을 어디에서도 얻지 못해 대체 표기로 내려간 경우에만 그 대체 표기의 첫 글자를 쓴다.
- **type**: Avatar
- **align**: end
- **label**: 사용자 아바타 — 이름 첫 글자 원형 이니셜

#### [4]

- **note**: 표시하는 이름의 진실원은 세션 진입 시 조회하는 「내 정보」 응답이며, 인계 토큰의 이름 클레임은 보조 조달원이다. 토큰에 이름이 실려 오지 않아도 「내 정보」가 알려 준 이름을 표시한다. 그러려면 진입 처리가 그 응답에서 역할만 취하지 않고 이름도 함께 보관해야 한다. 두 조달원 모두에서 이름을 얻지 못했을 때만 대체 표기 '사용자'로 내려간다 — 이름이 정말 없을 수 있으므로 이 대체 표기 자체는 유지하되, 서버가 이름을 아는 상태에서 이 표기가 나타나면 결함이다. 인가의 진실원을 토큰 클레임이 아니라 저작도구가 보관한 값으로 두는 역할 표시와 같은 축이다.
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

## attached_files

_(empty)_

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

false

### enabled

false

### components

_(empty)_

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

외부 포털 채널(포털 회원)의 공통 셸. 이 셸이 감싸는 화면은 외부 채널이 자기 화면 안에서 저작도구 화면을 실행하는 임베딩 환경에 놓인다. 그 안에서 화면 경계가 갈린다 — 머리 영역과 좌측 주 메뉴는 둘 다 외부 채널이 그리고, 저작도구는 그 아래 본문만 그린다.

따라서 이 셸은 자기 머리 영역을 그리지 않는다. 로고와 사용자 신원 표시를 두지 않는다 — 외부 채널의 머리 영역이 이미 보여 주므로 같은 것을 두 번 말하게 된다. 역할 배지도 두지 않는다 — 포털 회원은 역할이 하나뿐이라 배지가 정보를 더하지 않는다.

머리 영역 아래는 본문만 그린다. 좌측 주 메뉴를 두지 않는다 — 외부 채널이 이미 좌측 주 메뉴를 그리므로 저작도구가 자기 것을 더하면 왼쪽 레일이 둘이 되어 외부 채널 화면과 부딪힌다. 목적지 사이의 이동은 본문 안 상단 이동 탭이 맡는다. 탭에 걸리는 항목의 구성은 이 셸이 정하지 않는다 — 포털 채널 내비게이션 정의(NAV-002)를 따른다. 셸과 메뉴 정의 두 곳에 같은 목록을 적으면 한쪽만 갱신돼 어긋난다.

라벨링 편집 화면과 마킹 화면에서는 이 이동 탭을 노출하지 않는다 — 편집 도중 이탈을 부르고, 캔버스와 영상 재생이 쓸 세로 공간을 뺏는다. 두 화면 모두 이 셸이 감싸는 대상이며, 탭을 노출하지 않는 처리는 적용 화면 목록에서 빼는 것이 아니라 이 서술로 한다 — 머리 영역과 좌측 주 메뉴를 두지 않는다는 경계는 그 화면에도 똑같이 적용되기 때문이다. 두 화면으로 들어가는 자리는 목록에서 행을 누르는 동선이다.

이 셸은 반응형이다 — 좁은 폭에서 본문 좌우 여백을 줄이고 넓은 폭에서 늘린다. 포털은 외부 이용자가 개인 기기로 접근하므로 모바일·태블릿을 전제에 포함한다. 데스크톱을 전제하는 내부 채널 셸과 다른 점이다.

하단 푸터는 두지 않는다 — 노출 여부와 문안(근거법령·운영기관·문의처)이 아직 확정되지 않았고, 확정 전에 임시 문구를 내보내면 그 자리표시 값이 실제 정보인 것처럼 읽힌다. 확정되면 이 항목을 다시 켠다. 푸터가 없는 것은 결손이 아니라 이 결정의 결과이므로 정합 점검에서 누락으로 보고하지 않는다.

## nav_regions

### [1]

- **key**: portal-content-tabs
- **slot**: inline_tabs
- **label**: 본문 상단 이동 탭
- **style**: tabs
- **device**: all

**levels**:

#### to

1

#### from

1

- **orientation**: horizontal
- **source_nav_id**: NAV-002

## attached_files

_(empty)_

## applies_to_screens

- SCREEN-028
- SCREEN-029
- SCREEN-033
- SCREEN-044
- SCREEN-045

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
- **required_role**: ROLE-001

#### [3]

- **key**: video-detail
- **kind**: link
- **label**: 영상 상세
- **route**: /video/:id
- **screen_id**: SCREEN-009
- **visible_when**: 목록 행 클릭 진입 (LNB 미노출)
- **required_role**: ROLE-001

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
- **route**: /review/pending
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
- **route**: /augment/request
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

- **key**: upload
- **kind**: group
- **label**: [폐기] 업로드

**children**:

#### [1]

- **key**: upload-imports
- **kind**: link
- **label**: [폐기] 산출물 가져오기
- **route**: /manage/imports
- **screen_id**: SCREEN-039
- **visible_when**: 폐기 — 관리자 그룹의 admin-imports 로 옮겨갔다. 주소도 /admin/imports 로 바뀐다
- **required_role**: ROLE-001

- **visible_when**: 폐기 — 유일한 항목이던 산출물 가져오기가 관리자 페이지로 옮겨가 그룹이 비었다. 이동 사실을 남기기 위해 표기만 남기며 메뉴에 두지 않는다
- **required_role**: ROLE-001

### [8]

- **key**: manage
- **kind**: group
- **label**: 관리

**children**:

#### [1]

- **key**: manage-settings
- **kind**: link
- **label**: 시스템 설정
- **route**: /manage/settings
- **screen_id**: SCREEN-025
- **required_role**: ROLE-001

#### [2]

- **key**: manage-labels
- **kind**: link
- **label**: 라벨 관리
- **route**: /manage/labels
- **screen_id**: SCREEN-035
- **required_role**: ROLE-001

#### [3]

- **key**: manage-presets
- **kind**: link
- **label**: 프리셋 관리
- **route**: /manage/presets
- **screen_id**: SCREEN-026
- **required_role**: ROLE-001

#### [4]

- **key**: manage-deident
- **kind**: link
- **label**: 비식별 신고
- **route**: /manage/deident-reports
- **screen_id**: SCREEN-032
- **required_role**: ROLE-001

#### [5]

- **key**: manage-event-types
- **kind**: link
- **label**: 이벤트유형 관리
- **route**: /manage/event-types
- **screen_id**: SCREEN-038
- **required_role**: ROLE-001

- **required_role**: ROLE-001

### [9]

- **key**: admin
- **kind**: group
- **label**: 관리자

**children**:

#### [1]

- **key**: admin-entry
- **kind**: link
- **label**: [폐기] 관리자 페이지
- **route**: /admin
- **screen_id**: SCREEN-040
- **visible_when**: 폐기 — 진입 화면은 메뉴에 두지 않는다. 눌러서 가는 곳이 아니라 유효창이 없을 때 대신 열리는 자리다. 관리 항목을 감출 축이 역할밖에 없던 시점에 이 링크를 두었으나, 관리자 역할이 생겨 그 이유가 사라졌다. 이동 사실을 남기기 위해 표기만 남긴다
- **required_role**: ROLE-004

#### [2]

- **key**: admin-users
- **kind**: link
- **label**: 사용자 관리
- **route**: /admin/users
- **screen_id**: SCREEN-024
- **visible_when**: 관리자에게만 LNB 에 보인다. 유효창이 없는 상태로 열면 진입 화면이 대신 뜨고, 확인을 통과하면 원래 가려던 자리로 되돌아온다
- **required_role**: ROLE-004

#### [3]

- **key**: admin-endpoints
- **kind**: link
- **label**: 연동 서버 주소
- **route**: /admin/endpoints
- **screen_id**: SCREEN-042
- **visible_when**: 관리자에게만 LNB 에 보인다. 유효창이 없는 상태로 열면 진입 화면이 대신 뜨고, 확인을 통과하면 원래 가려던 자리로 되돌아온다
- **required_role**: ROLE-004

#### [4]

- **key**: admin-imports
- **kind**: link
- **label**: 산출물 가져오기
- **route**: /admin/imports
- **screen_id**: SCREEN-039
- **visible_when**: 관리자에게만 LNB 에 보인다. 유효창이 없는 상태로 열면 진입 화면이 대신 뜨고, 확인을 통과하면 원래 가려던 자리로 되돌아온다
- **required_role**: ROLE-004

#### [5]

- **key**: admin-uploads
- **kind**: link
- **label**: 파일 업로드
- **route**: /admin/uploads
- **screen_id**: SCREEN-027
- **visible_when**: 빌드 설정으로 노출이 갈린다 — 이 항목을 포함하지 않은 산출물에는 메뉴에도 주소에도 없다(그 자리와 주소는 같은 판정을 쓴다). 온프렘 표준 배포 산출물에는 포함되어 기본 배포에서는 노출된다. 관리자에게만 LNB 에 보인다. 유효창이 없는 상태로 열면 진입 화면이 대신 뜨고, 확인을 통과하면 원래 가려던 자리로 되돌아온다
- **required_role**: ROLE-004

#### [6]

- **key**: admin-password
- **kind**: link
- **label**: 패스워드 교체
- **route**: /admin/password
- **screen_id**: SCREEN-041
- **visible_when**: 관리자에게만 LNB 에 보인다. 유효창이 없는 상태로 열면 진입 화면이 대신 뜨고, 확인을 통과하면 원래 가려던 자리로 되돌아온다
- **required_role**: ROLE-004

#### [7]

- **key**: admin-maintenance
- **kind**: link
- **label**: 위험 액션
- **route**: /admin/maintenance
- **screen_id**: SCREEN-043
- **visible_when**: 관리자에게만 LNB 에 보인다. 유효창이 없는 상태로 열면 진입 화면이 대신 뜨고, 확인을 통과하면 원래 가려던 자리로 되돌아온다
- **required_role**: ROLE-004

- **required_role**: ROLE-004

## title

저작도구 내부 메뉴 (INTERNAL)

## status

draft

## audience

internal

## description

관제서버와 동일 도메인 SSO 로 진입하는 저작도구 내부 채널의 LNB 메뉴 트리. 그룹은 대시보드 / 영상 / 작업 / 데이터 / 통계 / 게시판 / 관리 / 관리자다. '영상' 그룹의 LNB 노출 항목은 영상 처리 현황(SCREEN-008) 하나이며 REVIEWER 에게만 보인다 — 이 화면과 거기서 행을 눌러 들어가는 영상 상세는 배치 처리 상태를 확인하는 데 그치지 않고 재시도·건너뛰기·재수행 같은 운영 조치를 제공하는 자리다. 파이프라인을 다시 돌리거나 단계를 건너뛰게 하는 것은 운영 행위이고, 라벨 수정과 검수 제출을 맡는 WORKER 의 역할 축이 아니다. 영상 목록은 같은 화면을 두 번 기록한 것이라 영상 처리 현황으로 흡수됐고, 그 노드는 폐기 사실을 남기기 위해 표기만 남기며 메뉴에 두지 않는다. 검수 목록은 별도 그룹이 아니라 '작업' 그룹 안의 항목으로 노출하며 REVIEWER 에게만 보인다. '게시판' 그룹의 LNB 노출 항목은 '게시판' 하나다. '업로드' 그룹은 폐기됐다 — 유일한 항목이던 산출물 가져오기가 관리자 페이지로 옮겨가 그룹이 비었다. 그 화면은 서버에 이미 있는 폴더를 데이터로 들여오면서 적재까지 실행하는 자리라 성격이 관리 행위이고, 내려받아 둔 파일을 올리는 파일 업로드가 이미 관리자 페이지에 있는 것과 같은 축이다. '관리' 그룹은 시스템 설정·라벨 관리·프리셋 관리·비식별 신고·이벤트유형 관리로 이뤄지며 REVIEWER 에게만 보인다. 사용자 관리는 관리자 페이지로 옮겨가 '관리자' 그룹에 있다. '관리자' 그룹은 사용자 관리·연동 서버 주소·산출물 가져오기·파일 업로드·패스워드 교체·위험 액션으로 이뤄지며 ADMIN 에게만 보인다 — 검수자에게는 이 그룹 자체가 나타나지 않는다. 이 화면들은 관리자 역할에 더해 관리자 패스워드를 확인해 연 단기 유효창을 함께 요구한다. 그중 파일 업로드는 빌드 설정으로 노출이 갈리며, 온프렘 표준 배포 산출물에는 포함되어 기본 배포에서는 노출된다. ★유효창을 메뉴 노출 조건으로 쓰지 않는다 — 유효창은 관리자 페이지에 들어가 패스워드를 넣어야 열리는데 그것을 노출 조건으로 삼으면 들어갈 길 자체가 사라진다. 노출을 가르는 축은 역할이다. 진입 화면(/admin)은 메뉴에 두지 않는다 — 눌러서 가는 곳이 아니라 유효창이 없을 때 대신 열리는 자리다. ⚠ 구 기재 폐기: '관리자 그룹은 진입 링크 하나만 두고 하위는 관리자 페이지 안에서 고른다'. 그 구성은 관리 항목을 감출 축이 역할밖에 없던 시점의 것이며, 관리자 역할이 생겨 더 이상 성립하지 않는다. 하위 화면 주소로 곧바로 들어오면 유효창이 없을 때 진입 화면이 대신 열리고, 확인을 마치면 원래 가려던 화면으로 돌아간다. 노출은 역할(ADMIN / REVIEWER / WORKER) 기반이며, 관리자는 검수자 권한을 물려받아 검수자에게 보이는 항목도 함께 본다. LNB 메뉴로는 노출되지 않지만 경로로 도달하는 화면(마킹·영상 상세·라벨링 캔버스·검수 상세·증강 결과·공지 상세·공지 작성·공지 수정)은 진입 맥락 노드로 보조 포함한다(visible_when = LNB 미노출). 공지 작성·공지 수정은 REVIEWER 만 진입할 수 있다. R1 활성 화면만 포함하며, 개발용·폐기 화면(개발 로그인·비식별 검토·포털 홈·작업 배정 SCREEN-013·오토라벨 요약 SCREEN-014·VLM 메타 검토 SCREEN-015)은 제외한다. ⚠ 구 기재 폐기: 제외 목록에 '오토라벨 테스트'가 들어 있었으나 그 화면은 운영에서 쓰는 '파일 업로드'가 되어 '관리자' 그룹에 노출된다 — 이름이 옛 용도에서 온 것이라 제외 대상으로 읽혔다.


---

<!-- NAV-002 (navigation_tree) -->

# 포털 메뉴 (PORTAL)

## nodes

### [1]

- **key**: portal-home
- **kind**: link
- **label**: 내 작업
- **route**: /portal
- **screen_id**: SCREEN-028
- **visible_when**: 포털 진입 시 기본 화면. 본인이 저장한 저작 작업 목록을 보여준다. 데이터마트 목록은 Host 가 소유하므로 이 메뉴에 두지 않는다.
- **required_role**: ROLE-003

### [2]

- **key**: portal-labeling
- **kind**: link
- **label**: 라벨링
- **route**: /portal/label/:id
- **screen_id**: SCREEN-029
- **visible_when**: [폐기] 메뉴 목적지로 두지 않는다 — 메뉴에 노출하지 않으며, 내 작업 목록에서 행을 눌러 들어가는 자리다. 화면과 경로 자체는 그대로 유지된다.
- **required_role**: ROLE-003

### [3]

- **key**: portal-uploads
- **kind**: link
- **label**: 내 업로드
- **route**: /portal/uploads
- **screen_id**: SCREEN-033
- **visible_when**: 포털 사용자가 본인 소유 영상을 직접 업로드하고 업로드 자산 목록·상태를 확인한다.
- **required_role**: ROLE-003

### [4]

- **key**: portal-upload-marking
- **kind**: link
- **label**: 업로드 영상 마킹
- **route**: /portal/uploads/:uldSn/marking
- **screen_id**: SCREEN-045
- **visible_when**: 메뉴에 노출하지 않는다 — 내 업로드 목록에서 마킹 대기 자산의 행을 눌러 들어가는 자리다. 특정 자산에 매인 화면이라 목적지가 될 수 없고, 영상 재생이 세로를 차지하는 몰입 편집 화면이라 이동 탭도 두지 않는다. 라벨링 화면에는 이 화면으로 되돌아가는 자리를 두지 않는다 — 라벨링 중이라는 것은 이미 프레임 추출이 끝났다는 뜻이고 그 시점의 재마킹은 제공하지 않으므로, 진입 자리를 두면 눌러 봐야 거절되는 자리가 된다.
- **required_role**: ROLE-003

### [5]

- **key**: portal-upload-labeling
- **kind**: link
- **label**: 업로드 자산 라벨링
- **route**: /portal/uploads/:uldSn/label
- **screen_id**: SCREEN-034
- **visible_when**: [폐기] 이 목적지를 두지 않는다 — 업로드 자산 라벨링 화면이 포털 라벨링 화면으로 합쳐져 별도 목적지가 없어졌다. 준비 완료 자산은 내 업로드 목록의 행을 눌러 그 통합 라벨링 화면(portal-labeling 노드)으로 들어간다. 이 노드는 어느 목적지가 없어졌는지를 남기기 위해 폐기 표기로 둔다.
- **required_role**: ROLE-003

### [6]

- **key**: portal-augment
- **kind**: link
- **label**: 증강
- **route**: /portal/augment
- **screen_id**: SCREEN-044
- **visible_when**: 본인이 올린 영상의 증강 요청 현황과 결과를 확인하고, 결과물의 후속 작업 진입과 내려받기를 한다.
- **required_role**: ROLE-003

## title

포털 메뉴 (PORTAL)

## status

draft

## audience

mobile

## shell_id

SHELL-002

## description

외부 포털 채널(PORTAL_USER)의 메뉴. 외부 채널(Host)이 머리 영역과 좌측 주 메뉴를 모두 소유하며, 저작도구는 그 아래 본문만 그린다 — 포털 채널의 저작 화면은 외부 채널이 자기 화면 안에서 저작도구 프론트를 실행하는 임베딩 환경에 놓인다. [폐기] 저작도구가 그리는 좌측 주 메뉴는 두지 않는다 — 왼쪽 레일이 둘이 되어 외부 채널 화면과 부딪힌다. 이 메뉴의 목적지 셋(내 작업 · 내 업로드 · 증강)은 저작도구 본문 상단 이동 탭으로 그린다. 외부 채널 메뉴에 이 메뉴의 하위 노드를 등재하지 않는다 — 등재하면 이동 수단이 둘이 되고, 목적지가 늘 때마다 외부 채널 배포가 필요해진다. 라벨링 화면은 메뉴에 노출하지 않는다 — 목록에서 행을 눌러 들어가는 자리다. 포털 사용자는 본인 소유 영상을 직접 업로드해 수동 라벨링한 뒤 본인 데이터를 내려받을 수 있으며, 이 경로는 내부 파이프라인·데이터마트로 흘러가지 않는 별도 경로다 — 갈리는 것은 진입 경로와 권한이며 저장 위치가 아니다(자산은 공용 원장에 적재하고 출처 판별자로 가른다 — ADR-058). 다만 관제 학습용 적재를 비롯한 내부 파이프라인 적재는 포털에서 제공하지 않으며, 오토라벨링·검수·버전관리도 포털 전 구간에서 제공하지 않는다. 반응형(PC / 태블릿 / 모바일)을 따른다. 데이터마트 영상을 고르는 목록은 Host 가 소유하므로 이 메뉴에 두지 않는다 — 거기서 저작 화면으로 오는 진입은 Host 가 링크로 연결한다. 그 데이터마트 영상 목록과 내 작업의 본인 저장 작업 목록은 서로 다른 목록이다.

## attached_files

_(empty)_

