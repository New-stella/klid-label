---
screen: SCREEN-024
generated_at: 2026-08-11T07:31:00Z
screen_design_item: SD-009 (designs → SCREEN-024)
design_render_urls:
  - /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-009/main.html
  - /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-009/main.css
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-024 디자인 노트

## § 디자인 결정 (와이어프레임 대비 추가분)

- **상태별**:
  - 상태 배지(활성/비활성) — 아이콘 없이 색+한글 라벨. 색만으로 구분하지 않는다는 DS do_rule 은 배지 라벨 텍스트 자체가 충족.
  - 역할 배지(검수자/작업자/포털/미배정) — 미배정만 warning 아이콘(삼각형 경고) + 한글 라벨 병기(색만으로 "주의가 필요하다"는 의미를 전달하지 않기 위함).
  - 로딩 스켈레톤 / 빈 결과 / 조회 실패 3종을 "상태 참고" 스트립으로 나란히 시각화(§구현 안내 참고 — 실사용 시 표는 이 세 상태 + 목록을 항상 배타적으로 분기하며 동시 노출되지 않는다).
  - 사용자 정보 수정 모달에 "미배정 사용자를 열었을 때" 대체 상태(저장 버튼 disabled + 안내 문구)를 참고용 서브 카드로 병기.
- **데이터 밀도**:
  - 이름 컬럼에 아바타(이니셜 1자, 원형, primary tint) + 이름 병기.
  - 긴 이메일은 `text-overflow: ellipsis` + `title` 속성으로 전체 값 유지(박지민 행으로 시연).
  - 이메일 없는 행(관제 인계 시 이메일 미제공)은 loginId 를 대체 표시 + "(로그인ID)" 캡션으로 출처를 구분(최윤서 행으로 시연).
- **시각 위계**:
  - 페이지 헤더(제목+부제) → 필터 카드 → 목록 카드 순으로 섹션 사이 24px(카드 padding) + 40px(섹션 간 여백, DS whitespace_principle) 리듬 적용.
  - 필터 영역은 1차 액션(검색, primary)과 보조 액션(필터 초기화, ghost)을 시각적으로 구분.
  - 표 헤더 배경은 DS do_rule 대로 secondary 스케일 최옅단(#EEF2F7)을 사용해 열 구조를 페이지 배경과 분리.
- **컴포넌트 디테일**:
  - 입력/셀렉트/버튼 hover·focus-visible·disabled 상태를 CSS 로 구현(3px 포커스 링 + 2px 오프셋, DS do_rule).
  - 표 행 hover 는 DS do_rule 이 명시한 `#FFFBEB` 를 사용(팔레트 밖 지정값이나 임의색 아님).
  - 인라인 검증 문구(역할 select 아래 도움말, 검색 입력 아래 도움말)를 caption 스텝으로 병기.

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 페이지 헤더 | UI-012 PageHeader (title+description 만 사용, breadcrumb·actions 미사용 — 골격에 없음) | title-lg(22/700), body-md(17/400), neutral-90/60 | 헤딩은 title-lg, 부제는 muted(neutral-60) |
| 검색 입력 | UI-002 Input (+ UI-099 Field 조립: FieldLabel+Input+FieldDescription) | label(14/600), body-md(17/400), spacing sm/md, radius.md | placeholder 는 AA 확보 위해 neutral-60(6.3:1) 사용 |
| 역할 필터 셀렉트 | UI-003 Select 계열 | 동일 | SelectTrigger size='default'(44px) |
| 필터 초기화 버튼 | UI-001 Button(variant=ghost) | primary-60 텍스트, primary-05 hover | |
| 검색 버튼 | UI-001 Button(variant=primary) | primary-50 bg + white 텍스트(4.55:1) | |
| 사용자 목록 표 | UI-007 DataTable | secondary-05 헤더 배경, neutral-10/20 보더, row-hover #FFFBEB | sortable 미사용(서버 정렬 정책 대상 아님 — 이 화면은 필터·검색만) |
| 행 수정 버튼 | UI-001 Button(variant=ghost, size=sm) | | |
| 페이지네이션 | UI-008 Pagination | primary-50(현재 페이지), neutral-70/30 | |
| 계정 상태 배지(활성/비활성) | UI-014 StatusBadge (매핑에 없는 "활성/비활성" 은 도메인 자체 값으로 라벨 그대로 사용, pill+색만 이 화면 전용 톤) | success-05/70(활성), neutral-05/70(비활성) | 카탈로그 status 16종에 활성/비활성 자체는 없어 StatusBadge 의 "폴백은 원문 라벨" 규약으로 흡수 |
| 역할 배지(검수자/작업자/포털/미배정) | **⚠️ 미정 — 카탈로그에 없음(§역등록 참고 Phase 6 신규 후보)** | primary-05/60(검수자), secondary-05/70(작업자), neutral-05/80+border(포털), warn-05/70(미배정) | 임시로 StatusBadge 와 동일한 pill 시각언어를 재사용해 디자인했으나 컴포넌트 자체는 별도 |
| 이름 아바타 | **⚠️ 미정 — 카탈로그에 없음(§역등록 참고 Phase 6 신규 후보)** | primary-05 bg, primary-60 텍스트(6.09:1) | |
| 오류 배너("사용자 목록을 불러올 수 없습니다") | UI-021 ErrorState 시각 언어(아이콘+제목+본문) — 실제 컴포넌트는 표 영역 전체 대체 규약을 따름 | error-05/70, error-60(아이콘) | |
| 빈 결과 | UI-020 EmptyState | neutral-40(아이콘), neutral-60(문구) | |
| 로딩 자리표시 | UI-033 Skeleton | neutral-10, animate-pulse | |
| 사용자 정보 수정 모달 | UI-004 Modal(size=md 상당) | shadow.lg, radius.lg | title/description/footer 슬롯 구성과 대응 |
| 모달 취소/저장 버튼 | UI-001 Button(secondary/primary) | | |
| 모달 상태 읽기전용 배지 | UI-014 StatusBadge | success-05/70 | binds_to user.active, 편집 불가 안내 병기 |

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

> `scripts/contrast_checker.py` 로 실사용 조합을 검사했다. 토큰은 design-system.md 의 *기존* 스케일 값만 사용(신규 hex 없음).

| 조합 (텍스트/아이콘 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문 텍스트 × 페이지 배경 | neutral-90 × white | 16.18:1 | AAA | OK |
| 보조/설명 텍스트 × 배경(백/카드/틴트) | neutral-60 × white / neutral-tint | 6.3:1 / 5.77:1 | AA | OK — DS known_gaps 경고대로 neutral-50 대신 60단 사용 |
| 표 헤더 텍스트 × secondary tint | neutral-80 × #EEF2F7 | 10.76:1 | AAA | OK |
| primary 버튼 텍스트 × primary base | white × #256EF4 | 4.55:1 | AA(여유 적음) | DS known_gaps 인지값 그대로 수용, 텍스트를 그 위에 추가 배치하지 않음 |
| secondary/ghost 버튼·링크 텍스트 × white | primary-60(#0B50D0) × white | 6.83:1 | AAA | primary base(4.55) 대신 한 단계 진한 값 채택 |
| 계정상태 활성 배지 | success-70(#1F4727) × success-tint(#EAF6EC) | 9.5:1 | AAA | success base(4.12, FAIL)는 배지에 미채택 |
| 계정상태 비활성 배지 | neutral-70(#464C53) × neutral-tint | 7.95:1 | AAA | OK |
| 역할 배지(검수자) | primary-60 × primary-tint | 6.09:1 | AA | OK |
| 역할 배지(작업자) | secondary-70(#063A74) × secondary-tint | 10.01:1 | AAA | OK |
| 역할 배지(미배정, warn) | warn-70(#614100) × warn-tint(#FFF3DB) | 8.43:1 | AAA | warn base(4.23, FAIL)는 DS known_gaps 가 이미 경고한 값이라 미채택 |
| 오류 배너 텍스트 | error-70(#8A240F) × error-tint(#FDEFEC) | 8.01:1 | AAA | error base(4.07, FAIL) 미채택 |
| 오류 배너 아이콘(비텍스트 UI) | error-60(#BD2C0F) × error-tint | 5.31:1 | PASS(≥3:1) | |
| 입력/셀렉트/보조버튼 보더(비텍스트 UI) | neutral-50(#6D7882) × white | 4.51:1 | PASS(≥3:1) | neutral-30(2.01)·neutral-20(1.54) 은 WCAG 1.4.11 미달로 미채택, 같은 스케일 안에서 50단으로 승격 |
| 표 행 hover 배경 위 본문 | neutral-90 × #FFFBEB | 15.6:1 | AAA | DS do_rule 지정값 |

**결론**: 실사용 조합 전량 AA 이상. semantic base 색(success/warn/error/info base)을 tint 배경 위 텍스트로 쓰면 전부 4.0~4.2:1 로 AA 미달이라는 DS known_gaps 경고를 그대로 확인했고, 이 디자인은 전량 700 단(또는 secondary 70/80)으로 대체해 회피했다. 입력·보조버튼의 기본 보더도 neutral-30 대비 2.01:1 로 WCAG 1.4.11(비텍스트 UI 3:1) 미달이라 neutral-50 으로 승격했다(같은 neutral 스케일 안에서의 이동이라 iteration_guide 위반 아님).

## § raw hex 검출 (Phase 3 D4)

`grep -oE "#[0-9a-fA-F]{3,6}" design.css design-main.html` 로 전수 검출 — `:root` 토큰 선언부(전량 design-system.md 의 실제 scale 값) 를 제외하면 **잔존 raw hex 0건**. 최초 작성 시 `#ffffff` 5곳이 `:root` 밖에서 리터럴로 남아 있어 `var(--white)` 로 교체했고(§iteration_guide 준수), `.du-badge` 의 `gap: 4px` 와 `.du-alert` 아이콘/텍스트의 `margin-top: 2px` 도 스페이싱 토큰 원칙(4px 배수만) 을 어겨 `var(--sp-xs)` 로 치환하거나 flex 정렬로 대체했다. HTML 에는 raw hex 0건(전량 CSS 클래스 참조).

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 페이지 전체(page) — 페이지 헤더·검색/역할 필터·사용자 목록 테이블(+상태 참고)·사용자 정보 수정 모달(오버레이 프리뷰)을 한 문서에 포함 |

- 공유 스타일: design-main.css — 게시본(logicraft 렌더)과 바이트 동일한 로컬 미러이며 design-main.html 이 참조하는 정본이다. 구 작성본 design.css 는 이후 수정이 반영되지 않아 게시본과 어긋난 채 남아 있었으므로 삭제했다(2026-08-18).
- 스크린샷: (없음 — 로컬/렌더 URL 프리뷰로 대체)

## § 역등록 기록 (Phase 5 실행 완료)

- **SD-009** 생성(`create_item type=screen_design`, `designs_screen=SCREEN-024`, `brownfield.status=preserved`/`legacy_source.legacy_artifact_id=LEGACY-125`).
  - title / data.title = `"SCREEN-024 사용자 관리 화면"` (사용자 확정 명명 규칙 — "고충실 디자인" 접미 미포함).
- `upload_design_render(project_id=4ece2c3f-8e99-46f5-9580-71108a76e578, item_id="SD-009", render_id="main", surface="page", platform="web", width=1440, label="사용자 관리 화면 — 고충실도 디자인", css=<design.css 본문>)` → **action: add**, `render.url=/uploads/designs/.../SD-009/main.html`, `render.css_url=/uploads/designs/.../SD-009/main.css`.
  - render_id="main"·surface="page" 는 SCREEN-024.md 의 `static_renders.main`(surface="page")과 동일 — 와이어프레임과 비교 뷰에서 짝지어짐, wireframe.css 미주입.
- **회수 검증**: `get_design_render` 로 재조회한 CSS 는 로컬 `design.css` 와 **바이트 단위로 완전 일치**. HTML 은 서버가 `<!-- 주석 -->` 제거·`onsubmit` 속성 제거·`viewBox`→`viewbox` 소문자화 등 알려진 정규화를 거쳤으나, 한글 토큰(전체 119종, 이름/이메일/역할/상태/안내 문구 등) 을 로컬 원문(주석 제외)과 멀티셋 대조한 결과 **완전 일치 — 한글 손상 0건**.
- `get_item(SD-009)` 재조회로 title/description/render.label/render.description 의 한글도 known 오타 패턴(컴럼·곳바로 등) grep + 희귀 음절 스캔 결과 **이상 없음**.

## § 신규 컴포넌트 후보 (Phase 6 — 안내만, 등록 보류)

카탈로그(`_shared/ui-catalog.md`, 108건)에 없는 컴포넌트 2종을 이 디자인에서 사용했다. **동의 없이 등록하지 않았다** — 아래 표로 안내만 하고, 실제 `register_ui_components` 호출은 보류했다.

| name(제안) | category | variants | 쓰인 곳 | props(디자인 기준 초안) |
|---|---|---|---|---|
| Avatar | display | initial(이니셜 1자, 원형) | SCREEN-024 사용자 목록 표 이름 컬럼 | `initial: string`(필수), `size?: 'sm'\|'md'`(기본 md=36px) |
| RoleBadge | display | reviewer / worker / portal / unassigned | SCREEN-024 사용자 목록 표 역할 컬럼, 사용자 정보 수정 모달 참고 서브카드 | `role: 'REVIEWER'\|'WORKER'\|'PORTAL_USER'\|null`(필수), `label?: string`(기본 매핑 라벨 덮어쓰기) |

- 두 컴포넌트 모두 이 디자인에서 처음 필요해진 것이며, 기존 UI-014 StatusBadge(작업/배치 16종 상태 전용 매핑)로는 역할(REVIEWER/WORKER/PORTAL_USER/미배정) 축을 표현할 수 없어 별도가 필요했다(status 코드 축과 role 코드 축은 의미가 다르다).
- Phase 6 안내에 따라 사용자 동의 시 `mc-logi-screen-design` 또는 별도 요청으로 `register_ui_components` 실행 + `mc-logi-screen-kit` SYNC 필요(등록만 하고 SYNC 하지 않으면 로컬 카탈로그가 stale 로 남음).
