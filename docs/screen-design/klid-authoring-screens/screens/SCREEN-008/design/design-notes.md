---
screen: SCREEN-008
generated_at: 2026-08-13T00:00:00+09:00
screen_design_item: (Phase 5 미실행 — SD 역등록은 사용자 확인 후 메인 세션에서 수행)
design_render_urls: (Phase 5 미실행)
surfaces: [main]
ds: DS-001 KRDS Public (v8)
source_wireframes: [wireframe.html, wireframe.css]
generated_by: mc-logi-screen-design (Phase 2~3만 수행)
---

# SCREEN-008 영상 처리 현황 화면 — 디자인 노트

와이어프레임의 6개 섹션(①페이지 헤더 ②검색·필터 ③일괄 작업 바 ④영상 목록 테이블+페이지네이션
⑤작업자 배정 모달 ⑥마킹 진입 팝업)을 **하나도 더하거나 빼지 않고** 그대로 유지하고,
그 위에 시각 위계·상태 표현·여백 리듬만 입혔다. 색·크기·간격·radius·font 는 전부
`_shared/design-system.md` 의 토큰 값이며 사용처에 raw hex 는 0건이다.

**기존 시안 관례 승계**: 클래스 네이밍·토큰 별칭 블록·`.screen-root` / `.page-head` / `.card` /
`.btn` / `.badge` / `.state-box` / `.skeleton` / `.reference-panel` 골격·타이포 유틸(`.t-*`) 은
**SCREEN-009 시안을 그대로 따랐고**, DS do_rule 이 지정한 행 hover 표면 토큰(`--row-hover-bg`)은
**SCREEN-005 시안의 선언 방식**(값 + DS 근거 주석)을 그대로 가져왔다. 새 언어를 만들지 않았다.

---

## § 디자인 결정 (와이어프레임 대비 추가분)

### 레이아웃·시각 위계

- **③일괄 작업 바 + ④테이블을 한 덩어리로 묶었다.** 와이어프레임이 두 카드를 `gap:0` 으로 붙여 둔
  의도를 살려, 일괄 바는 위쪽만 라운드(`radius-lg 8px`)·아래 테두리 없음, 테이블 카드는 아래쪽만
  라운드로 만들어 **"선택 → 그 선택에 대한 작업"** 이 한 표면으로 읽히게 했다. 두 개의 독립 카드로
  두면 선택 상태와 표의 관계가 끊긴다.
- **페이지 헤더는 제목 블록(좌) / 새로고침(우) 2열**이고 아래 `border-bottom` 1px 로 화면 헤더와
  본문을 가른다. 브레드크럼(영상 › 영상 처리 현황)은 SCREEN-009 관례이며 경로는 `shell-nav.md` 의
  실제 LNB 라벨('영상' 그룹 → '영상 처리 현황')에서 가져왔다.
- **여백 리듬**: 섹션 간 `sp-lg 24px`, 카드 안쪽 `sp-lg 24px`(DS `card_padding_px` 24), 화면 좌우
  `sp-xl 40px`, 표 셀 `sp-sm/sp-md`. DS `whitespace_principle` 의 "밀집 영역은 8~16px 까지 좁히되
  44px 타깃 유지"를 표에 적용했다.
- **1차/보조 액션 구분**: primary(채움)는 화면당 의미 있는 진행 액션에만 — `조회` · `N건 일괄 배정` ·
  `자동 마킹 시작`. 새로고침·초기화·재배정·일괄 재시작은 secondary(테두리), 마킹 설정은 outline
  (primary 색 테두리 — 행 안에서 "다음 단계로 들어가는" 액션임을 알리되 채움 버튼과 경쟁하지 않게),
  상세는 ghost. DS `color_usage` 의 "강조는 면적이 아니라 위계로 만든다"를 따랐다.

### 상태별 표현

- **처리 단계 배지 6종**을 상태 의미에 맞는 semantic 토큰으로 배정했다 — 배치 완료=success,
  처리중/비식별 진행중=info, 마킹 대기=warn, 배치 실패/비식별 실패=error, 대기=neutral.
  **비식별 우선 표시 규칙**(deidentStatus 가 IN_PROGRESS·FAILED 면 배치 단계 배지보다 앞선다)을
  4행(비식별 진행중)·7행(비식별 실패)으로 실제 표에 재현했다. 실패 2종은 같은 error 톤이지만
  **한글 라벨이 서로 다르므로** DS 의 "색만으로 구분하지 않는다"를 만족한다.
- **행 상태 3종을 서로 다른 축으로 분리**했다 — 선택(`p-0` 표면 + 좌측 `p-5` 3px 액센트 + 체크박스
  채움) / hover(`--row-hover-bg`, DS do_rule 지정값) / 기본(흰 표면). 선택과 hover 가 겹치면
  선택이 이긴다(`:not([data-selected]):hover`).
- **대체 상태 3종(로딩 스켈레톤 5행 · ErrorState · EmptyState)** 은 표 자리를 대신하는 화면이라
  본문에 동시에 그릴 수 없다. SCREEN-009 가 쓴 **참고 패널(점선 테두리 + '참고' 배지)** 관례로
  나란히 정적 노출했다. 스켈레톤은 표와 **같은 8열 그리드**로 맞춰 전환 시 레이아웃이 흔들리지 않게
  했다(UI-007 이 명시한 요구).
- **액션 3갈래 분기**(UI-094)를 표에서 전부 실증했다 — ①재배정만(2행: 배정됨·승인 전) ②마킹 설정만
  (3·10행: 미배정 + 마킹 대기 + 비식별 차단 아님) ③상세만(1행 승인 완료 / 4행 비식별 진행중 /
  5·6·7·8행). 배정자 셀에 보조 캡션(검수 승인 완료 / 라벨링 진행)을 달아 **왜 그 행에 재배정
  버튼이 없는지**가 화면에서 읽히게 했다.

### 데이터 밀도

- **표 셀 2줄 구조**: CCTV명(body-md 17px) + 영상ID(mono 14px, `#0042`), 녹화일(17px) + 시각
  (caption 14px). 정보를 옆으로 늘리는 대신 아래로 쌓아 열 수를 줄였다(DS: "좁으면 크기를 낮추지
  말고 열 수를 줄인다"). 식별자에 mono 를 쓴 것은 ladder 의 mono step 용도 정의(식별자·좌표) 그대로다.
- **숫자 열은 `font-variant-numeric: tabular-nums`** 로 자릿수를 정렬했다(길이·날짜·총 건수).
  길이는 mono(14px)가 아니라 **body-md 17px + tabular** 로 했다 — 사람이 읽는 값이라 본문 17px
  하한을 지키면서 정렬만 얻는 쪽을 택했다.
- **긴 CCTV명은 말줄임**(`text-overflow: ellipsis`), 배지·버튼은 `white-space: nowrap`.
- **표는 `max-height: 60vh` 스크롤 + `thead` sticky**(UI-007 명세). 정적 렌더가 잘리지 않도록
  샘플 행은 8행으로 맞췄다(총 128건 · 8건/페이지 · 16페이지 · 현재 3페이지로 일관).

### 컴포넌트 디테일

- **밀집 표의 행 액션 버튼은 시각 높이 36px, hit area 는 44px**. `.btn-sm::after { inset: -4px 0 }`
  로 보이지 않는 타깃만 위아래 4px 씩 넓혔다 — DS 의 44px 하한을 지키면서 행 높이를 키우지 않는다.
  **글자 크기는 줄이지 않았다**(button step 17px 유지) — DS `iteration_guide` 가 밀집 영역에서도
  크기 예외를 두지 말라고 못박기 때문이다.
- **focus-visible 3px + offset 2px** 를 a/button/input/select 전역에 적용(DS do_rule).
- **모달은 CSS-only `:target`** — 마킹 팝업 1단계('자동' → 2단계 / '수동' → 배정 모달)와 2단계
  ('이전' → 1단계)가 실제로 오간다. JS 없이 동선을 검증할 수 있게 했고, 렌더가 `<script>` 를
  제거해도 무해하다.
- **마킹 팝업에 2단계 진행 표시(1 방식 선택 → 2 간격 입력)** 를 넣었다. 와이어프레임은 버튼 4개를
  평면으로 나열했지만 스펙상 명확히 2단계 흐름이므로, 지금 어디인지 보이지 않으면 '이전'의 의미가
  서지 않는다. 1단계의 자동/수동은 **설명 한 줄을 단 선택 카드**로 만들어 두 갈래가 무엇을 하는지
  누르기 전에 알 수 있게 했다(수동이 배정 흐름으로 빠지는 것이 화면에 드러난다).
- **일괄 바에 "선택한 영상 중 실패 2건만 재시작 대상입니다" 안내**를 넣었다. 일괄 배정(전체 성공
  또는 전체 실패)과 일괄 재시작(건별 결과)의 대상 집합이 다른데, 버튼 라벨의 숫자가 3 과 2 로
  갈리는 이유가 없으면 오해를 산다.
- **비활성 상태는 색이 아니라 텍스트로도 알린다** — 배정 모달의 `배정` 버튼은 disabled + 푸터에
  "작업자를 고르면 저장할 수 있습니다."(UI-083 접근성 노트 요구).
- **모달 미리보기(참고 패널)는 `aria-hidden="true"`** 로 보조기술 중복 낭독을 막았다 — 같은 내용이
  실제 `:target` 모달에 한 번 더 있기 때문. SCREEN-009 와 같은 처리다.

### 명시적으로 하지 않은 것

- **개인정보 유무 컬럼을 두지 않았다**(스펙 ★ 항목).
- **이벤트 유형 배지 색을 유형별로 나누지 않았다** — 아래 §토큰 매핑의 `⚠️ 미정` 참조.
- **행 전체 클릭 이동(`onRowClick`)** 은 시각 표현이 hover 표면뿐이라 별도 장식을 넣지 않았다.

---

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| ① 페이지 제목·설명 | UI-012 PageHeader · UI-013 Breadcrumb | typography `title-lg` 22/700 · `body-sm` 15/400 · color `neutral 10/6` · spacing `xs/md` | breadcrumb 경로는 shell-nav 의 '영상' 그룹 |
| ① 새로고침 | UI-001 Button `variant=secondary` | color `neutral 0/4/9` · radius `md` · min-height 44px | 아이콘 + 라벨 동반 |
| ② 검색어 입력 | UI-002 Input · UI-099 Field | typography `label` 14/600 · `body-md` 17/400 · color `neutral 4`(테두리) `neutral 5`(placeholder) | maxLength 100 |
| ② 상태·이벤트 유형 select | UI-003 Select · UI-095 VideoFilters | 위와 동일 + 화살표 `neutral 6` | 이벤트 옵션은 서버 조회(로딩 시 disabled+aria-busy) |
| ② 시작일·종료일 | UI-002 Input(`type=date`) — UI-028 DatePicker 는 미사용 | 동일 | 스펙이 'Input(날짜 입력)'으로 규정 · 상호 min/max |
| ② 조회 / 초기화 | UI-001 Button `primary` / `secondary` | color `primary 5→6`(hover) · `neutral 0/4/9` | |
| ③ 일괄 배정 / 일괄 재시작 / 선택 해제 | ⚠️ 미정 `BulkActionBar` (카탈로그 미등록) | surface `primary 0` · border `primary 2` · text `primary 7` · `neutral 7` | 배정은 UI-083 AssignModal `bulk` 모드로 연결 |
| ④ 표 골격·sticky 헤더·행 선택 | UI-007 DataTable | 헤더 배경 `secondary 0`(DS do_rule) · 헤더 텍스트 `neutral 7` `title-sm` 17/600 · 셀 `body-md` 17/400 · 구분선 `neutral 1` | max-height 60vh 스크롤 |
| ④ 전체선택 / 행 선택 | UI-024 Checkbox | `primary 5`(채움) · `neutral 4`(테두리) · radius `sm` | 44px 히트영역 |
| ④ 이벤트 배지 | UI-016 EventTypeBadge | `secondary 0` 배경 / `secondary 7` 텍스트 · radius `full` · `label` 14/600 | ⚠️ 유형별 색 매핑은 카탈로그에 값이 없어 **단일 톤으로 통일** — 아래 미정 항목 |
| ④ 처리 단계 배지 | UI-014 StatusBadge · UI-017 StageBadge | success/warn/error/info/neutral 각 `0`(배경) + `6~7`(텍스트) | 완료·실패는 StageBadge, 그 외는 StatusBadge |
| ④ 행 액션 3갈래 | UI-094 VideoActions | `btn-sm` 36px + 44px 히트영역 · outline=`primary 5` 테두리 | 재배정→UI-083, 마킹 설정→⑥ |
| ④ 페이지네이션 | UI-008 Pagination | 현재=`primary 5` 채움 + 흰 글자 · 기본 `neutral 7` · 비활성 `neutral 5` | 양끝+현재±1, 사이는 말줄임 |
| ④ 로딩 | UI-033 Skeleton | `neutral 1` + sweep 애니메이션(`ease-standard`) | 5행 · 표와 같은 8열 |
| ④ 조회 실패 | UI-021 ErrorState | icon `error 5` · 제목 `error 7` · 재시도=UI-001 secondary | role=alert |
| ④ 결과 0건 | UI-020 EmptyState | icon `neutral 4` · 제목 `neutral 8` | role=status |
| ⑤ 작업자 배정 모달 | UI-083 AssignModal (UI-004 Modal 합성) | shadow `lg` · 스크림 `neutral 10` 60% · 푸터 `neutral 0` | bulk 모드(칩 3건 + 안내) |
| ⑥ 마킹 진입 팝업 | UI-005 ConfirmDialog 계열 + UI-002 Input | choice 테두리 `primary 5` · hover `primary 0` · 단계 배지 `success 5`/`primary 5`/`neutral 1` | ⚠️ 2단계 진행 표시는 카탈로그 미등록 |
| 공통 카드 표면 | UI-011 Card | border `neutral 2` · radius `lg` 8px · shadow `sm` | DS: 카드에는 shadow.sm 만 |

### ⚠️ 미정 (카탈로그·토큰에 없어 임의 등록하지 않음)

1. **`BulkActionBar`** — 스펙이 `custom_name` 으로만 지칭하고 `_shared/ui-catalog.md` 에 항목이 없다.
   본 시안은 `primary 0` 표면 + `primary 2` 테두리의 선택 상태 바로 그렸다. 신규 ui_component 등록
   여부는 사용자 판단 사항.
2. **`RowReassignAction` / `RowMarkAction` / `RowDetailAction`** — 카탈로그에는 이 세 갈래를 합친
   **UI-094 VideoActions** 만 있다. 본 시안은 UI-094 로 매핑했고 개별 컴포넌트를 새로 만들지 않았다.
3. **EventTypeBadge 의 유형별 색 매핑** — 카탈로그는 "해석된 한글 라벨 기준 매핑, 미매핑은 회색
   폴백"이라고만 적고 **실제 매핑 표를 싣지 않았다.** 지어내지 않았고, 대신 DS `color_usage` 를
   근거로 **secondary 단일 톤**으로 통일했다(semantic 색은 의미가 맞을 때만 쓰라는 규칙 — 이벤트
   유형은 상태 의미가 아니다). 실제 매핑 표가 확인되면 그 값으로 교체해야 한다.
4. **마킹 팝업의 2단계 진행 표시(steps)** — 카탈로그에 대응 컴포넌트가 없다. 이 화면 전용 표현이며
   토큰(success 5 / primary 5 / neutral 1)만으로 조립했다.
5. **`#ffffff`(페이지 표면)** — DS `tokens.colors` 팔레트에 흰색 항목이 별도로 없다. SCREEN-005·
   SCREEN-009 가 모두 `--bg-page: #ffffff` 로 선언한 관례를 그대로 따랐다.

---

## § 접근성 검증 (WCAG 대비)

`scripts/contrast_checker.py` 로 **실제 사용한 텍스트×배경 조합 전부**를 검사했다.
본문 AA 4.5:1 / 큰글씨·비텍스트 UI 3:1 기준.

### 텍스트 — 전부 AA 통과 (32/32)

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 |
|---|---|---|---|
| 표 셀 본문 × 흰 배경 | neutral 9 × #ffffff | 16.18:1 | AAA |
| 페이지 제목 × 흰 배경 | neutral 10 × #ffffff | 18.43:1 | AAA |
| 상태 문구·칩 × 흰 배경 | neutral 8 × #ffffff | 12.10:1 | AAA |
| 폼 라벨·페이지 번호 × 흰 배경 | neutral 7 × #ffffff | 8.68:1 | AAA |
| 보조 텍스트(영상ID·시각·미배정) × 흰 배경 | neutral 6 × #ffffff | 6.30:1 | AA |
| placeholder·구분자·말줄임 × 흰 배경 | neutral 5 × #ffffff | 4.51:1 | AA (하한 근접) |
| outline 버튼·선택 카드 × 흰 배경 | primary 6 × #ffffff | 6.83:1 | AA |
| 필수 표시(*) × 흰 배경 | error 6 × #ffffff | 5.95:1 | AA |
| 에러 상태 제목 × 흰 배경 | error 7 × #ffffff | 8.97:1 | AAA |
| primary 버튼 글자 × primary 5 | #ffffff × primary 5 | 4.55:1 | AA (DS known_gaps 인지) |
| 단계표시 완료 글자 × success 5 | #ffffff × success 5 | 4.57:1 | AA |
| 참고패널 보조 텍스트 × 회색 표면 | neutral 6 × neutral 0 | 5.77:1 | AA |
| 대상 박스 본문 × 회색 표면 | neutral 9 × neutral 0 | 14.82:1 | AAA |
| 모달 푸터 안내 × 회색 표면 | neutral 6 × neutral 0 | 5.77:1 | AA |
| 단계번호(대기) × neutral 1 | neutral 7 × neutral 1 | 7.07:1 | AAA |
| **표 헤더 × 표 헤더 배경** | neutral 7 × secondary 0 | 7.72:1 | AAA |
| **행 hover 위 본문** | neutral 9 × #FFFBEB | 15.60:1 | AAA |
| **행 hover 위 보조** | neutral 6 × #FFFBEB | 6.08:1 | AA |
| **선택 행 본문** | neutral 9 × primary 0 | 14.40:1 | AAA |
| **선택 행 보조** | neutral 6 × primary 0 | 5.61:1 | AA |
| 일괄바 선택 건수 × primary 0 | primary 7 × primary 0 | 9.42:1 | AAA |
| 일괄바 안내 × primary 0 | neutral 7 × primary 0 | 7.73:1 | AAA |
| 배지 배치 완료 | success 6 × success 0 | 5.26:1 | AA |
| 배지 마킹 대기 | warn 7 × warn 0 | 8.43:1 | AAA |
| 배지 배치 실패·비식별 실패 | error 7 × error 0 | 8.01:1 | AAA |
| 배지 처리중·비식별 진행중 | info 7 × info 0 | 6.82:1 | AA |
| 배지 대기 | neutral 7 × neutral 0 | 7.95:1 | AAA |
| 배지 이벤트 유형 | secondary 7 × secondary 0 | 10.01:1 | AAA |
| 안내 배너(info) | info 7 × info 0 | 6.82:1 | AA |
| 안내 배너(warn) | warn 7 × warn 0 | 8.43:1 | AAA |

> **조치한 것**: 페이지네이션 비활성 색을 `neutral 4`(3.08:1, 본문 AA 미달)에서 **`neutral 5`(4.51:1)**
> 로 올렸다. DS 의 "회색 표면 위 보조 텍스트는 60단 이상" 규칙에 따라 회색·컬러 표면 위 보조
> 텍스트는 전부 `neutral 6` 이상으로 잡았다(`text-faint`=neutral 5 는 **흰 배경에서만** 사용).

### 비텍스트 UI (1.4.11 · 3:1)

| 요소 | 토큰 | 대비 | 판정 · 근거 |
|---|---|---|---|
| 입력·secondary 버튼 테두리 × 흰 배경 | neutral 4 × #ffffff | 3.08:1 | PASS |
| 포커스 링 · 체크박스 채움 × 흰 배경 | primary 5 × #ffffff | 4.55:1 | PASS |
| 에러 상태 아이콘 × 흰 배경 | error 5 × #ffffff | 4.56:1 | PASS |
| 빈 상태 아이콘 × 흰 배경 | neutral 4 × #ffffff | 3.08:1 | PASS |
| 선택 행 좌측 액센트 × 흰 배경 | primary 5 × #ffffff | 4.55:1 | PASS — **선택 상태의 식별 근거** |
| 카드·표 구분선 × 흰 배경 | neutral 2 × #ffffff | 1.54:1 | 3:1 미달 — **장식적 구분선**이며 컴포넌트 식별에 필요한 정보가 아니다(입력 테두리는 neutral 4 로 별도 통과) |
| 표 헤더 표면 × 흰 배경 | secondary 0 × #ffffff | 1.12:1 | 3:1 미달 — **DS do_rule 이 지정한 값**. 헤더 식별은 600 굵기 텍스트 + `secondary 2` 하단선이 담당 |
| 행 hover 표면 × 흰 배경 | #FFFBEB × #ffffff | 1.04:1 | 3:1 미달 — **DS do_rule 이 지정한 값**. 포인터 추종 표시이며 hover 는 1.4.11 이 요구하는 지속 상태가 아니다 |
| 선택 행 표면 × 흰 배경 | primary 0 × #ffffff | 1.12:1 | 3:1 미달 — 표면 단독으로는 부족하나 **좌측 액센트(4.55:1) + 체크박스 채움(4.55:1)** 이 함께 알린다 |
| 일괄바 테두리 × 일괄바 표면 | primary 2 × primary 0 | 1.43:1 | 3:1 미달 — 장식 테두리이며 바의 식별은 내용(텍스트·버튼)이 담당 |

> **⚠️ DS 토큰 한계 (화면 차원에서 해소 불가 — 보강 필요 시 DS 개정 사안)**
> 표 헤더 표면(`secondary 0`)·행 hover 표면(`#FFFBEB`)은 **DS do_rules 가 직접 지정한 값**이라
> 화면이 임의로 더 진한 색으로 바꾸지 않았다. 두 값 모두 흰 배경 대비 1.1:1 내외이므로 **표면
> 색만으로는** 열 구조·hover 행을 구분하지 못하는 사용자가 있을 수 있다. 현재 시안은 굵기·테두리·
> 액센트로 이를 보완한다. DS 에 대비를 확보한 대체 토큰이 생기면 교체 대상이다.
> (DS `known_gaps` 가 이미 "행 hover 표면 #FFFBEB 는 KRDS 정본 팔레트에 없는 채택값"이라고 기록해 둔 항목)

### 그 밖의 접근성 처리

- 모든 상태·이벤트를 **색 + 한글 라벨** 2축으로 표기(색 단독 구분 없음).
- 표 헤더 `scope="col"`, `caption`(visually-hidden), 체크박스 `aria-label`, 페이지네이션
  `aria-current="page"`, 에러 `role="alert"` / 빈 상태 `role="status"`.
- 폼은 전부 `label[for]` 연결 + 도움말 `aria-describedby`.
- 클릭 대상은 모두 `button` 또는 `a`(div 클릭 없음), 최소 44px 히트영역.
- 참고 패널의 모달 미리보기는 `aria-hidden="true"`(실제 모달과 중복 낭독 방지).

---

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | `design-main.html` | `static_renders.main` (surface=page, width 1440) — 목록 페이지 + 오버레이 |

- 공유 스타일: `design.css`
- 스크린샷: 없음(브라우저 렌더로 육안 확인만 수행 — 1440×1000 뷰포트, 전체 페이지)
- 파일 크기: `design-main.html` 36.3KB · `design.css` 25.8KB
- ⚠️ **CSS 파일명은 스킬 규격의 `design.css`** 를 따랐다. 형제 시안(SCREEN-005·SCREEN-009)은
  `design-main.css` 를 쓰고 있어 **키트 안에서 CSS 파일명 관례가 갈린다.** 시각 결과에는 영향이
  없으나 Phase 5 역등록 시 `css` 파라미터로 넘길 파일을 혼동하지 않도록 기록해 둔다.

### 검증 수행 결과 요약

| 항목 | 결과 |
|---|---|
| 사용처 raw hex(`:root` 선언 밖) | **0건** |
| HTML 내 색상 hex | **0건** (grep 이 잡은 `#0042` 등 9건은 전부 영상 ID 표기 문자열) |
| 인라인 `style=` 속성 | **0건** (스켈레톤 높이는 `.sk-line`/`.sk-box` 클래스로 이전) |
| `<script>` | **0건** |
| ladder 밖 font-size | **0건** (14/15/17/18/19/22/26 만 사용 — 13px 1건은 caption 14px 로 정정) |
| DS 미선언 font-weight | **0건** (body 400/500, label·title 600, title-lg/display 700) |
| HTML 태그 균형 | 오류 0건 (미닫힌 태그 0) |
| 브라우저 렌더 | 정상 (콘솔 오류는 favicon 404 1건뿐) |

---

## § 역등록 기록 (Phase 5)

**미실행.** 본 작업은 Phase 2(디자인 작성) + Phase 3(검증·노트)까지만 수행했다.
`screen_design(SD)` ITEM 역등록(`upload_design_render`, render_id=`main` / surface=`page`,
css 분리 전달)은 사용자 확인 후 메인 세션에서 진행한다.


## 게시본 안전 마크업 전환 (재업로드 회차)

LogiCraft 게시 sanitizer 가 일부 태그·속성을 떨어뜨려 게시 프리뷰가 로컬과 달라지던 것을 바로잡았다.
**서버가 저장한 CSS 는 바이트 단위로 그대로 통과하는 것을 실측**했으므로, 잘리는 표현은 모두 CSS 로 옮겼다.
로컬 렌더가 달라지지 않았는지는 전환 전후 **전체 화면 픽셀 대조**로 확인했다.

- `<colgroup>`/`<col>` 열 정의 → 열 클래스를 **첫 행 `<th>` 로 옮겼다**(`table-layout: fixed` 라 첫 행 셀 폭이 열 폭을 정한다).
  `.col-*` CSS 는 그대로 재사용하므로 폭 값은 한 곳에만 남는다.
- SVG `stroke-linecap`·`stroke-linejoin` 52건 → CSS(이 화면은 26개 아이콘이 모두 같은 값이라 규칙 하나로 끝난다).
- 픽셀 대조 결과 **차이 0**.

### 지어낸 화면 문구 제거

- `이벤트 유형은 서버에서 내려받은 목록이며, 표시명이 같은 유형은 하나로 묶여 표시됩니다.` (필터 하단)
  — 사양이 구현자에게 준 지시(하드코딩 목록 금지·표시명 같은 유형 접기)를 사용자 문구로 옮긴 것이다.
  사용자에게는 목록이 어디서 왔는지가 아니라 목록 자체가 필요하다. 죽은 `.filter-note` 규칙도 함께 지웠다.


---

## § 2026-08-20 추가 — 시계열 일괄 축 (session 20 이후)

### 무엇을 얹었나
- **일괄 액션바에 세 버튼 추가** — 시계열 건너뛰기 · 해제 · 재수행. 기존 「일괄 재시작」과 「일괄 배정」 사이에 구분자(`bulk-sep`)로 묶어 **배치 조작 그룹 / 배정 / 선택 해제** 세 덩어리로 읽히게 했다. 버튼 종류·크기는 기존과 같은 `btn-secondary btn-sm` 이라 위계가 흔들리지 않는다(배정만 primary 유지).
- **선택 힌트 보강** — 재시작은 실패 건만 대상인데 시계열 일괄은 선택 전건이 대상이라, 두 규칙이 한 바에 공존하는 것을 힌트 문장에서 구분했다.
- **건너뛰기 사유 모달(`#modal-skip-vlm`) 신설** — 사유가 필수인 유일한 일괄 조작이라 확인 단계를 둔다.

### 왜 이렇게 했나
- **사유 입력 표현을 단건과 맞췄다** — 영상 상세의 단건 건너뛰기 대화상자와 같은 구성(대상 표시 → 설명 → 필수 사유 → 도움말 + 글자 수)을 그대로 썼다. 일괄만 다른 모양이면 같은 일을 하는 두 화면이 갈린다.
- **사유 도움말에 「요청 전체가 거부된다」를 명시** — 이 계약이 건별 부분 성공과 다른 유일한 지점이라, 사용자가 「몇 건만 실패하겠지」로 오해하지 않게 문장으로 못박았다.
- **오토라벨 버튼을 두지 않았다** — 산출물이 라벨이라 대량으로 건너뛰면 품질 축이 조용히 느슨해진다. 상세 화면에서 건건이 다룬다.

### 컴포넌트 · 토큰 매핑
| 요소 | 매핑 |
|---|---|
| 시계열 3버튼 | UI-001 action: Button (`btn-secondary btn-sm`) — 신규 없음 |
| 사유 모달 | UI-004 overlay: Modal — 기존 배정 모달과 같은 골격 |
| 사유 입력 | UI-027 input: Textarea — 이 화면에 처음 쓰여 `.textarea`·`.field-foot`·`.char-count` 규칙을 추가했다. **값은 전부 DS 토큰**이며 영상 상세의 같은 규칙과 동일하다 |

### 검증
- **raw hex 0건** — 추가분에 색상 hex 없음(전부 토큰 변수).
- **골격 보존** — 게시본 대비 차이가 `insert` 3건뿐이고 `replace`·`delete` 0건이다(순수 삽입).
- **한글 손상 0** — 알려진 오타 grep + 희귀 음절 전 출현 문맥 검사.
- **역등록** — SD-013 `main`/`page` 교체. 게시본 본문 재대조에서 `replace` 0 · 비공백 차이 0 · CSS 원문 해시 일치.
