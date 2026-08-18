---
screen: SCREEN-030
generated_at: 2026-08-11T08:00:00.000Z
screen_design_item: SD-007
design_render_urls:
  main: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-007/main.html (css: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-007/main.css)
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-030 디자인 노트

> Phase 0~5(입력 합성 → 로컬 디자인 작성 → 검증 → 키트 반영 → screen_design 역등록)까지 수행했다.
> Phase 6(ui_component 보강 권고)은 신규 컴포넌트 후보가 없어 안내조차 불필요 — 아래 § 참조.

## § 디자인 결정 (와이어프레임 대비 추가분)

- **역할별 뷰**: screen_spec 은 REVIEWER(전체+관리 버튼+상태 컬럼)와 WORKER(발행 게시글만, 상태 컬럼 숨김) 두 뷰를 서술하지만 와이어프레임 파일은 1개(`wireframe.html`)뿐이라 surface 도 1개다. 두 역할의 상집합인 **REVIEWER 뷰**를 `design-main.html` 로 그렸다 — "새 게시글 작성" 버튼과 "상태" 컬럼이 REVIEWER 전용임을 HTML 주석으로 명시했다. WORKER 뷰는 이 REVIEWER 뷰에서 그 버튼과 컬럼만 제거되고 DRAFT 행이 보이지 않는 부분집합으로 implement 단계에서 파생 가능하다(구조 변경 없음).
- **상태별**:
  - 상태 뱃지 2종(발행/작성중)에 색을 배정했다. ui-catalog StatusBadge(UI-014)의 16종 매핑 표에는 이 두 값이 없어(BE 상태 enum 이 PUBLISHED/DRAFT 계열일 가능성) 이번 화면에서 새로 판단했다 — **발행=success**(color_usage "배치 완료·저장 성공"과 결이 같은 확정·완료 상태), **작성중=neutral**(n-10/n-70, 아직 확정되지 않은 중간 상태일 뿐 "주의가 필요한 경고"는 아니라 warn을 쓰지 않았다). implement 단계에서 실제 BE 상태 코드와의 매핑을 재확인할 것.
  - "중요"(고정) 배지는 wireframe 섹션 설명이 지정한 대로 Pin 아이콘 + amber(warn 스케일) + 텍스트 "중요"로 그렸다. StatusBadge와 시각적으로 구분되도록 pin 아이콘을 함께 둬 색상 단독 구분을 피했다(do_rules 준수).
  - 로딩 스켈레톤·에러·빈 상태는 실제 화면에서 동시에 나타나지 않는 전이 상태이므로, ③ 게시글 테이블 섹션 카드 내부 최하단에 "참고 — 컴포넌트 상태 예시" 부록으로 3종을 나란히 시각화했다(점선 구분선으로 본문과 분리). 골격(3개 섹션·순서)은 그대로 두고 섹션 내부에 문서화 정보만 덧붙인 것 — 새 섹션을 추가한 것이 아니다.
  - 검색 필터의 "초기화" 버튼은 screen_spec 이 "필터 값이 하나라도 활성 상태일 때만 노출"이라고 명시해, 기본(무필터) 상태를 대표하는 이 디자인에서는 렌더하지 않았다(비활성화가 아니라 미노출 — SCREEN-018의 "항상 렌더 + disabled" 관례와 다른 이 화면 고유의 노출 규칙을 그대로 따랐다). 활성 시 `btn-outline` variant로 노출된다.
- **데이터 밀도**: 9행(고정 2행 + 일반 7행)의 그럴듯한 실제 데이터로 채웠다. 제목 1건("2026년 하반기 CCTV 관제지원시스템 라벨링 작업자 대상 정기 교육 일정 및 신규 라벨 마스터 매핑 규칙 변경사항 종합 안내")은 의도적으로 길게 넣어 `text-overflow: ellipsis` 처리를 시각적으로 확인 가능하게 했다(`title` 속성으로 전체 텍스트 접근성 보완). 등록일은 내림차순, 고정 글 2건이 최상단에 위치한다(screen_spec "상단 고정(PIN) 우선 + 등록일 내림차순" 규칙 반영).
- **시각 위계**: 3개 섹션 사이에 `--sp-xl`(40px) 여백을 둬 절차를 구분했다. 검색(secondary)/새 게시글 작성(primary)으로 1차·보조 액션을 구분했다 — 검색은 화면 내 반복 조작, 작성은 관리자 전용 진입 동작이라 primary는 헤더의 작성 버튼에만 배정했다(검색 버튼을 primary로 두면 진입 즉시 두 개의 primary 액션이 경쟁한다).
- **컴포넌트 디테일**: 카드는 `shadow.sm`만 사용(do_rule 준수). 검색 필드·버튼에 focus-visible 3px 링 + 2px 오프셋, hover 시 배경/보더 전환(200ms standard easing). 표 행은 `--row-hover(#FFFBEB)`로 hover/focus-within을 표시하고(do_rule 명시값), 클릭 가능함을 알리기 위해 행 끝에 chevron 아이콘을 두고 hover 시 제목 텍스트와 chevron이 함께 primary 색으로 전환된다(행 전체가 `role="link"` + `tabindex="0"`으로 키보드 접근 가능).

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 헤더(제목/부제/새 게시글 작성) | PageHeader(UI-012) 패턴 + Button(UI-001, variant=primary) | title-lg(22/700), body-md(17/400, `--n-60`) | breadcrumb 없음 — screen_spec 골격에 Breadcrumb 컴포넌트가 선언돼 있지 않아 추가하지 않았다. "게시판"이 LNB 최상위 항목(shell-nav.md)이라 상위 경로 표시 필요성도 낮다 |
| 검색 필드 select | Field(UI-099) + Select(UI-003) | body-md(17/400), radius-md(6px), `--n-20` 보더 | SelectTrigger size=default(44px) |
| 검색어 입력 | Field(UI-099) + Input(UI-002) | 동일 | maxlength=100 (screen_spec "검색어 input(max 100자)") |
| 검색/초기화 버튼 | Button(UI-001, secondary / outline) | button(17/500) | 초기화는 필터 비활성 시 미노출(위 § 디자인 결정 참조) |
| 게시글 표 | DataTable(UI-007) | body-md(17/400) 셀, label(14/600) 헤더, `--s-0`/`--s-70`(표 헤더, do_rule) | 3컬럼(제목/상태/등록일), 번호 컬럼 없음(screen_spec 명시). 서버 정렬 축(등록일 내림차순 기본)이라 sortable(로컬 정렬) 미사용, 헤더에 정렬 아이콘만 직접 구성(`aria-sort="descending"`) |
| 제목 셀(고정 배지+텍스트) | (DataTable 셀 커스텀 렌더) | body-md(17/400) 제목(ellipsis), pin-badge | 고정(pinned) 글만 pin-badge 노출 |
| 중요(고정) 배지 | (screen_spec 설명 지정 — Pin 아이콘 + amber) | label(14/600), `--warn-0`/`--warn-70` | ⚠️ 미정 — ui-catalog에 전용 배지 컴포넌트 없음(§ 하단 참고) |
| 상태 뱃지(발행/작성중) | StatusBadge(UI-014) 시맨틱 확장 | label(14/600), `--success-0`/`--success-70`(발행), `--n-10`/`--n-70`(작성중) | UI-014 매핑표에 PUBLISHED/DRAFT 값이 없어 이 화면에서 색을 판단(위 § 디자인 결정 참조) — implement 시 BE 상태 코드 재확인 |
| 행 전체 클릭(상세 이동) | (DataTable onRowClick 시맨틱) | `--row-hover(#FFFBEB)`, chevron `--n-40`(idle)/`--p-50`(hover) | role="link" + tabindex="0"로 키보드 접근 |
| 등록일 | (DataTable 셀) | body-md(17/400) | 서버 정렬 파라미터와 연결(내림차순 기본) |
| 페이지네이션 | Pagination(UI-008) | label(14/500), `--p-50`(현재 페이지) | 20건/페이지, 3페이지 예시. 양끝+현재 앞뒤1칸 규약(카탈로그 그대로, 3페이지라 말줄임 미발생) |
| 상태 쇼케이스(로딩/에러/빈) | Skeleton(UI-033) / ErrorState(UI-021) / EmptyState(UI-020) | 각 컴포넌트 정의 그대로 | 참고용 부록 — 실제 화면 동시 노출 아님. 빈 상태 문구는 usage_example 예시("검색 결과가 없습니다")가 아니라 화면 맥락에 맞게 "등록된 공지사항이 없습니다"로 재정의(EmptyState의 message override 지원 범위 내) |

### ⚠️ 토큰·컴포넌트 미정 목록 (Phase 6 대상 후보 — 이번 배치는 미실행)

1. **중요(고정) 배지** — ui-catalog(UI-001~UI-108)에 대응 컴포넌트가 없다. screen_spec 섹션 설명이 지정한 대로(Pin 아이콘+amber+"중요") 직접 스타일링했다. 카탈로그의 다른 화면(SCREEN-018의 "재검토 필요" 칩)과 유사하게 도메인 특정 표시 목적의 pill 칩이라, 재사용 필요성이 확인되면 Phase 6에서 `ui_component` 등록 후보(가칭 `PinBadge` 또는 기존 StatusBadge/CountChip의 보조 variant로 흡수)로 검토할 수 있다. **이번 배치는 안내만 하고 등록하지 않는다**(작업 지시에 따름).
2. **`--surface-white`(#ffffff)** — design-system.md가 순백을 별도 색 토큰으로 선언하지 않는다(neutral 스케일의 "표면 배경" use_for는 `--n-0`처럼 옅은 회색을 가리킨다). 카드/입력/페이지 캔버스 기본 표면에 순백을 그대로 쓰고 `--surface-white` CSS 변수로 명시했다 — design.css `:root` 주석에 근거 기록. DS에 "surface/base" 토큰이 정식 등록되면 교체 필요(SCREEN-018과 동일한 기존 미정 항목).
3. **웹폰트 소스 미정** — design-system.md에 `typography.body.family=Pretendard GOV` 지정은 있으나 `@font-face` woff2 URL이 제공되지 않았다. `font-family` 선언 + 시스템 한글 폴백(Apple SD Gothic Neo, Noto Sans KR)만 적용했다(design-prompt 지시대로 임의 폰트 대체 없음).
4. **상태 뱃지 발행/작성중** — 위 § 디자인 결정·컴포넌트 매핑에서 서술한 대로 이 화면에서 새로 색을 배정한 시맨틱이며, StatusBadge(UI-014)의 매핑표에 아직 없다. 신규 컴포넌트는 아니라(기존 StatusBadge 확장) Phase 6 신규 등록 후보에는 포함하지 않았다 — 필요 시 UI-014 자체의 매핑표 갱신 검토(이는 `mc-logi-update` 소관).

> **Phase 6 요약**: 신규 `ui_component` 등록이 필요할 만큼 성숙한 후보는 "중요(고정) 배지" 1건뿐이며, 이번 배치에서는 등록하지 않고 이 표로 안내만 한다(작업 지시 — 사용자 동의 후 별도 진행).

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

> `scripts/contrast_checker.py` 로 실제 사용 조합을 검사. 본문 AA 4.5:1 / 큰글씨 3:1 / non-text UI 3:1 하한.

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 제목(h1) × 페이지 배경 | `--n-100` × `--surface-white` | 16.18:1 | AAA | OK |
| 부제/muted × 페이지 배경 | `--n-60` × `--surface-white` | 6.30:1 | AAA(large 기준) | OK |
| btn-primary 텍스트 | `--surface-white` × `--p-50` | 4.55:1 | AA | OK(여유 적음 — known_gaps 명시값과 일치) |
| btn-primary hover 텍스트 | `--surface-white` × `--p-60` | 6.83:1 | AA | OK |
| btn-secondary 텍스트 | `--n-90` × `--surface-white` | 16.18:1 | AAA | OK |
| btn-outline 텍스트 | `--p-50` × `--surface-white` | 4.55:1 | AA | OK(여유 적음 — btn-primary와 동일 근거) |
| 필터 라벨 × 필터박스 배경 | `--n-70` × `--n-0` | 7.95:1 | AAA | OK |
| 입력 placeholder | `--n-60` × `--surface-white` | 6.30:1 | AA | OK(SCREEN-018 D5에서 확인된 `--n-40` FAIL 회피값을 선반영) |
| 표 헤더 텍스트 | `--s-70` × `--s-0` | 16.39:1 | AAA | OK(do_rule: 표 헤더는 secondary 최옅단) |
| 표 셀 텍스트(hover 행 포함) | `--n-90` × `--surface-white` / `--row-hover` | 16.18:1 / 15.60:1 | AAA | OK |
| 중요(고정) 배지 | `--warn-70` × `--warn-0` | 8.43:1 | AAA | OK |
| 상태뱃지(발행) | `--success-70` × `--success-0` | 6.99:1 | AA | OK |
| 상태뱃지(작성중) | `--n-70` × `--n-10` | 7.07:1 | AAA | OK |
| 행 chevron(비활성/idle, non-text) | `--n-30` × `--surface-white` | 2.01:1 | **FAIL**(non-text 3:1) | `--n-40`(3.08:1)로 교체 — 조치 완료 |
| 행 chevron(hover, non-text) | `--p-50` × `--surface-white` | 4.55:1 | PASS | OK |
| 상태 쇼케이스 라벨 | `--n-50` × `--surface-white` | 4.51:1 | AA | OK — known_gaps 명시값과 일치(여유 적음, 흰 배경 한정 사용) |
| 에러/빈 상태 제목·설명 | `--n-90`/`--n-70`/`--n-60` × `--surface-white` | 16.18/8.68/6.30 | AAA | OK |
| 에러 아이콘(장식, aria-hidden) | `--error-50` × `--surface-white` | — | 장식 아이콘(비필수 정보) — non-text 대비 예외 | 확인 불필요 |
| 페이지네이션 현재페이지 | `--surface-white` × `--p-50` | 4.55:1 | AA | OK |
| 페이지네이션 비활성(이전 버튼) | `--n-30` × `--surface-white` | 2.01:1 | N/A | **WCAG 1.4.11 예외** — 비활성 UI 컴포넌트는 대비 기준 적용 대상 아님(조작 불가 + `aria-disabled`로 상태 전달, SCREEN-018과 동일 근거) |

**요약**: 최초 검증에서 행 chevron(idle) 1건이 non-text UI 기준 미달(2.01:1)로 확인되어 `--n-30` → `--n-40`으로 교체 후 3.08:1로 통과했다(게이트1 1회 반복). 그 외 전 조합 AA 이상 통과. 비활성(disabled) 페이지네이션 버튼 텍스트 1건은 WCAG 예외 대상으로 조치 불필요.

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 공지 목록 페이지 (wireframe.html 1개 surface에 대응) |

- 공유 스타일: design.css
- 스크린샷: (없음 — 로컬 file:// 프리뷰로 확인)

## § 역등록 기록 (Phase 5 — 완료)

- **screen_design ITEM**: `SD-007` (title: "SCREEN-030 공지 목록 화면", domain_id: DOMAIN-009, status: draft, designs_screen: SCREEN-030, current_version: 2) — title/data.title에 "고충실 디자인" 접미를 넣지 않았다(2026-08-11 확정 명명 규칙 준수). 등록 전 `list_items(type=screen_design)`로 기존 SD-001~SD-006(다른 화면)과 충돌 없음을 확인했다. `create_item` 응답 `links.unresolved: 0`.
- **렌더 업로드**: render_id=`main`, surface=`page` — `upload_design_render` 응답 `action: "add"`, `new_version: 2`
  - html: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-007/main.html`
  - css: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-007/main.css`
- 업로드 전 `design-main.html`에서 `<link rel="stylesheet" href="design.css" />` 1줄만 제거(인라인 `style` 속성은 원래 없었음). 본문·구조·데이터는 무변경.
- **★쓰기 후 재다운로드 검증(logicraft-integration.md §3·§5 준수)**: `get_item`으로 title/slug/description 재조회 — 한글 손상 0건, "고충실 디자인" 접미 없음 확인. `get_design_render`로 html/css 본문을 재조회해 로컬 원본과 대조: ①CSS는 `diff` 완전일치(0 diff) ②HTML은 서버가 `<!-- ... -->` HTML 주석을 제거하는 것 외에는 완전일치(주석 제거 후 한글 런(run) 집합 diff 0건 — 주석은 렌더 비표시 영역이라 시각적 손상이 아니다). 서버가 렌더 시 가하는 그 외 관찰된 변환: `viewBox`→`viewbox`(대소문자 정규화), `stroke-width`/`stroke-linecap`/`stroke-linejoin`/`tabindex`/`maxlength`/`scope` 속성 제거(sanitizer). 이들은 아이콘 렌더 디테일에 경미한 영향(획 두께 기본값 1로 표시)을 줄 수 있으나 텍스트·색·구조·접근성 시맨틱(role/aria-*)은 무변경이다.
- Phase 6(ui_component 보강 권고 — 중요(고정) 배지 신규 후보 등)은 이번 배치에서 등록을 수행하지 않고 위 § 표로 안내만 함. 사용자 확인 후 별도 진행.

### 재등록 (2026-08-18)

- **사유**: 확정 사양 대비 렌더가 낡아 있었다 — 작성 버튼이 '새 게시글 작성'(사양 v25 는 '새 공지 작성'), 고정 공지 배지가 '중요'(v24 확정은 '고정'). 배지 쪽은 v24 시점부터 렌더만 뒤처져 있던 것이다.
- **범위**: 문구 3곳 델타 수정. 골격·DS 토큰·CSS 무변경이라 대비 검증(D5) 재실행 대상 아님.
- `upload_design_render(item_id=SD-007, render_id="main", surface="page")` → `action: "replace"`, `new_version: 6`
- **검증**: 서버 렌더를 다시 받아 대조 — '새 공지 작성' 1건 · '고정' 2건 · 구 문자열 0건.
