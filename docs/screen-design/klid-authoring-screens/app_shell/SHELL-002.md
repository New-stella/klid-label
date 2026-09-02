---
logicraft_item: SHELL-002
type: app_shell
version: 8
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-02T10:53:44.079Z
status: CHANGED
prev_version: 5
content_hash: d02de90932afb3aecce0118dd23c29b1f4d61bf34e002425b9af7cd9460619d8
stale: true
raw: ./_raw/SHELL-002.json
links:
  applies_to: ["[[SCREEN-028]]", "[[SCREEN-029]]", "[[SCREEN-033]]", "[[SCREEN-044]]", "[[SCREEN-045]]"]
  references: ["[[NAV-002]]"]
  references_backward: ["[[NAV-002]]"]
---

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
