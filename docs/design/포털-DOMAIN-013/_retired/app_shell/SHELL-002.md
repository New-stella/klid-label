---
logicraft_item: SHELL-002
type: app_shell
version: 4
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-17T01:37:01.950Z
status: NEW
prev_version: null
content_hash: bdd680a5acd43faf7b54a5c8a0c30620a2a180ea398d82244cc9925ce1930f7a
stale: true
raw: ./_raw/SHELL-002.json
links:
  applies_to: ["[[SCREEN-029]]"]
  references: ["[[NAV-002]]"]
---

# 포털 채널 셸

## title

포털 채널 셸

## device

responsive

## footer

### enabled

true

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

외부 포털 채널(포털 회원)의 공통 셸. 상단 헤더 + 본문 + 하단 푸터로 구성하며 좌측 주 메뉴를 두지 않는다 — 포털이 보유한 화면이 적어 메뉴 트리가 필요 없다.

헤더는 내부 채널과 두 가지가 다르다. 스크롤과 무관하게 고정하지 않고 문서 흐름을 따라 상단에 붙어 따라오게 한다. 그리고 역할 배지를 두지 않고 사용자 이름만 표시한다 — 포털 회원은 역할이 하나뿐이라 배지가 정보를 더하지 않는다.

이 셸은 반응형이다 — 좁은 폭에서 본문 좌우 여백을 줄이고 넓은 폭에서 늘린다. 포털은 외부 이용자가 개인 기기로 접근하므로 모바일·태블릿을 전제에 포함한다. 데스크톱을 전제하는 내부 채널 셸과 다른 점이다.

메뉴 항목의 구성은 이 셸이 정하지 않는다 — 포털 채널 내비게이션 정의를 따른다.

## nav_regions

_(empty)_

## applies_to_screens

- SCREEN-029

## default_navigation_id

NAV-002
