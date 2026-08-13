---
screen: SCREEN-018
generated_at: 2026-08-11T06:20:00.000Z
screen_design_item: SD-001
design_render_urls:
  main: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-001/main.html (css: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-001/main.css)
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-018 디자인 노트

> Phase 0~5(입력 합성 → 로컬 디자인 작성 → 검증 → 키트 반영 → screen_design 역등록)까지 수행했다.
> Phase 6(ui_component 보강 권고)는 사용자 확인 후 별도 진행.

## § 디자인 결정 (와이어프레임 대비 추가분)

- **상태별**:
  - 상태 뱃지(StatusBadge) 4종을 색+한글 라벨로 구분했다 — 검수 대기=info(파랑 계열), 검수중=warn(주황 계열), 승인=success(초록), 반려=error(빨강). color_usage의 "warn=검토 중/주의가 필요한 상태" 정의가 "검수중(진행 중, 주의 관찰)"과 더 부합해 warn을 검수중에, "대기 중/큐에 있음"은 info에 배정했다(스크린 정의서에 색 매핑이 없어 이 화면에서 내린 결정 — implement 단계에서 실제 BE 상태 enum과의 매핑을 재확인할 것).
  - "재검토 필요"는 StatusBadge(필 형태)와 시각적으로 구분되도록 아웃라인 칩 + 경고 아이콘 + "재검토 필요" 텍스트로 별도 표현했다(색상 단독 구분 금지 — do_rules 준수). REVIEW_PENDING 상태와 나란히 배치해 "재검수 대상으로 되돌아온 건"이라는 R13 시맨틱을 반영했다.
  - 로딩 스켈레톤 · 에러 · 빈 상태는 실제 화면에서 동시에 나타나지 않는 전이 상태이므로, ④ 검수 목록 테이블 섹션 카드 내부 최하단에 "참고 — 컴포넌트 상태 예시" 부록으로 3종을 나란히 시각화했다(점선 구분선으로 본문과 분리). 골격(4개 섹션·순서)은 그대로 두고 섹션 내부에 문서화 정보만 덧붙인 것— 새 섹션을 추가한 것이 아니다.
  - RefreshingNotice(갱신 중 안내)는 info 톤의 인라인 배너 + CSS 전용 스피너(무 JS, `@keyframes`)로 표현해 "이전 결과를 보여주는 중"이라는 과도기 상태를 알린다.
- **데이터 밀도**: 테이블에 6개 행의 그럴듯한 실제 데이터(영상명+video-NNNN id, 6종 이벤트 중 4종 + 이벤트 없음(`-`) 1건, 작업자명, 제출일시, 라벨 수, 상태)를 채웠다. 영상명 중 1건은 의도적으로 긴 파일명("2026년_동계_상습침수구역_야간감시_하천범람_원본영상.mp4")과 또 1건("야간_이상행동_감지_유괴의심_CCTV_원본_다운로드본_A동_3층_복도.mp4")을 넣어 `text-overflow: ellipsis` 처리를 시각적으로 확인 가능하게 했다(`title` 속성으로 전체 파일명 접근성 보완).
- **시각 위계**: 4개 섹션 사이에 `--sp-xl`(40px) 여백을 둬 절차를 구분했다. 조회(primary)/초기화(ghost, 비활성)로 1차·보조 액션을 구분했고, 검수 시작(primary)/이어서 검수(secondary)/결과 보기(ghost)로 행 액션의 우선도를 상태별로 차등화했다. KPI 4카드는 진입 기본 필터(검수요청=검수 대기)에 대응해 "검수 대기" 카드만 `aria-pressed="true"` 로 선택 강조(2px 프라이머리 보더 + 프라이머리 틴트 배경)해 KpiCard의 필터 토글 시맨틱을 보여준다.
- **컴포넌트 디테일**: 카드는 `shadow.sm`만 사용(do_rule 준수). 버튼·입력·Select에 focus-visible 3px 링 + 2px 오프셋, hover 시 배경/보더 전환(200ms standard easing), 초기화 버튼은 `opacity:0.5 + pointer-events:none`으로 비활성 상태를 표현했다. 표 행은 `--row-hover(#FFFBEB)` 로 hover를 표시한다(do_rule 명시값).

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 헤더(제목/부제/새로고침) | PageHeader(UI-012) 패턴 + Button(UI-001, variant=secondary) | title-lg(22/700), body-md(17/400, `--n-60`) | breadcrumb 없음 — screen_spec 골격에 Breadcrumb 컴포넌트가 선언돼 있지 않아 추가하지 않았다 |
| 검수 현황 KPI 4카드 | KpiCard(UI-010, onClick+selected) | display-sm(26/700) 값 + label(14/600) 라벨, `--p-50`/`--p-0`(선택), `shadow.sm` | KpiCard의 필터 토글 variant 그대로 사용 |
| 영상명/작업자명 검색 | Field(UI-099) + Input(UI-002) | body-md(17/400), radius-md(6px), `--n-20` 보더 | Field가 label[for] 연결 전담(UI-002는 label prop 없음) |
| 상태 select | Field(UI-099) + Select(UI-003) | 동일 | SelectTrigger size=default(44px) |
| 조회/초기화 버튼 | Button(UI-001, primary / ghost) | button(17/500) | 초기화는 진입 기본값과 동일해 disabled |
| 검수 목록 표 | DataTable(UI-007) | body-md(17/400) 셀, label(14/600) 헤더, `--s-0`/`--s-70`(표 헤더, do_rule) | sortable(로컬 정렬) 미사용 — 서버 정렬 축이라 헤더에 정렬 아이콘만 직접 구성(`aria-sort` 부여) |
| 영상명 + id | (DataTable 셀 커스텀 렌더) | body-md 파일명(ellipsis) + mono 14px(`--n-50`) id | `--n-50`은 흰 배경 4.51:1로 AA 통과(design-system known_gaps 명시값과 일치) |
| 이벤트 뱃지 | EventTypeBadge(UI-016) | label(14/600), semantic `-0`/`-70` 조합 4종 | eventType 없음(`-`) 케이스 1건 포함 |
| 상태 뱃지 | StatusBadge(UI-014) | label(14/600), info/warn/success/error `-0`/`-70` | 4종 상태 전부 시각화 |
| 재검토 필요 칩 | (screen_spec type=Badge, custom — StatusBadge와 시각 구분 위해 아웃라인+아이콘으로 별도 스타일) | caption(14/500), `--warn-0`/`--warn-70`/`--warn-50` 보더 | ⚠️ 미정 — ui-catalog에 전용 컴포넌트 없음(§ 하단 참고) |
| 행 액션 버튼 | Button(UI-001, primary/secondary/ghost) | button(17/500) | 상태별 라벨·variant 분기(screen_spec 지정대로) |
| 페이지네이션 | Pagination(UI-008) | label(14/500), `--p-50`(현재 페이지) | 양끝+현재 앞뒤1칸 + 말줄임, 처음/마지막 전용 버튼 없음(카탈로그 규약 그대로) |
| RefreshingNotice | (screen_spec type=Custom, custom_name=RefreshingNotice) | caption(14/400), `--info-0`/`--info-70` | ⚠️ 미정 — ui-catalog에 없음(§ 하단 참고), CSS 전용 스피너로 구현 |
| 상태 쇼케이스(로딩/에러/빈) | Skeleton(UI-033) / ErrorState(UI-021) / EmptyState(UI-020) | 각 컴포넌트 정의 그대로 | 참고용 부록 — 실제 화면 동시 노출 아님 |

### ⚠️ 토큰·컴포넌트 미정 목록 (Phase 6 대상 후보 — 이번 배치는 미실행)

1. **재검토 필요 칩** — ui-catalog(UI-001~UI-100)에 대응 컴포넌트가 없다. StatusBadge와 시각적으로 구분되는 아웃라인 칩으로 임시 스타일링했다. Phase 6에서 `ui_component` 신규 등록 후보로 권고 필요(가칭 `RecheckFlag` 또는 기존 StatusBadge의 보조 variant로 흡수).
2. **RefreshingNotice** — screen_spec에 이미 `type=Custom, custom_name=RefreshingNotice`로 선언돼 있어 화면 고유 커스텀 컴포넌트로 간주하고 카탈로그 매핑을 시도하지 않았다. 재사용 필요성이 확인되면 Phase 6에서 `ui_component` 등록 검토.
3. **`--surface-white`(#ffffff)** — design-system.md가 순백을 별도 색 토큰으로 선언하지 않는다(neutral 스케일의 "표면 배경" use_for는 `--n-0`처럼 옅은 회색을 가리킨다). 카드/입력/페이지 캔버스 기본 표면에 순백을 그대로 쓰고 `--surface-white` CSS 변수로 명시했다 — design.css `:root` 주석에 근거 기록. DS에 "surface/base" 토큰이 정식 등록되면 교체 필요.
4. **웹폰트 소스 미정** — design-system.md에 `typography.body.family=Pretendard GOV` 지정은 있으나 `@font-face` woff2 URL이 제공되지 않았다. `font-family` 선언 + 시스템 한글 폴백(Apple SD Gothic Neo, Noto Sans KR)만 적용했다(design-prompt 지시대로 임의 폰트 대체 없음).

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

> `scripts/contrast_checker.py` 로 실제 사용 조합을 검사. 본문 AA 4.5:1 / 큰글씨 3:1 하한.

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문 × 카드/페이지 배경 | `--n-90` × `--surface-white` | 16.18:1 | AAA | OK |
| 제목(h1) × 페이지 배경 | `--n-100` × `--surface-white` | 18.43:1 | AAA | OK |
| 부제/muted × 페이지 배경 | `--n-60` × `--surface-white` | 6.30:1 | AAA(large 기준) | OK |
| btn-primary 텍스트 | `--surface-white` × `--p-50` | 4.55:1 | AA | OK(여유 적음 — known_gaps 명시값과 일치) |
| btn-primary hover 텍스트 | `--surface-white` × `--p-60` | 6.83:1 | AA | OK |
| btn-outline 텍스트 | `--p-50` × `--surface-white` | 4.55:1 | AA | OK |
| KPI 라벨(선택) | `--p-70` × `--p-0` | 9.42:1 | AAA | OK |
| 필터 라벨 × 필터박스 배경 | `--n-70` × `--n-0` | 7.95:1 | AAA | OK |
| **입력 placeholder** | `--n-40` × `--surface-white` | 3.08:1 | **FAIL** | `--n-60`(6.30:1)로 교체 — 조치 완료 |
| 표 헤더 텍스트 | `--s-70` × `--s-0` | 10.01:1 | AAA | OK(do_rule: 표 헤더는 secondary 최옅단) |
| 표 셀 텍스트(hover 행 포함) | `--n-90` × `--surface-white` / `--row-hover` | 16.18:1 / 15.60:1 | AAA | OK |
| 영상 id(mono) | `--n-50` × `--surface-white` | 4.51:1 | AA(여유 적음) | OK — known_gaps 문서화값과 일치, 흰 배경 한정 사용 |
| 상태뱃지(검수대기/이벤트-침수) | `--info-70` × `--info-0` | 6.82:1 | AAA | OK |
| 상태뱃지(검수중)/재검토칩/이벤트(교통사고 등) | `--warn-70` × `--warn-0` | 8.43:1 | AAA | OK |
| 상태뱃지(승인) | `--success-70` × `--success-0` | 6.99:1 | AAA | OK |
| 상태뱃지(반려)/이벤트(산불) | `--error-70` × `--error-0` | 8.01:1 | AAA | OK |
| 이벤트뱃지(쓰러짐) | `--s-70` × `--s-0` | 10.01:1 | AAA | OK |
| 이벤트 없음("-") | `--n-50` × `--surface-white` | 4.51:1 | AA(여유 적음) | OK |
| 페이지네이션 현재페이지 | `--surface-white` × `--p-50` | 4.55:1 | AA | OK |
| 페이지네이션 비활성(이전 버튼) | `--n-30` × `--surface-white` | 2.01:1 | N/A | **WCAG 1.4.3 예외** — 비활성 UI 컴포넌트는 대비 기준 적용 대상 아님(조작 불가 + `aria-disabled`로 상태 전달) |
| 에러/빈 상태 제목·설명 | `--n-90`/`--n-70`/`--n-60` × `--surface-white` | 16.18/8.68/6.30 | AAA | OK |

**요약**: 최초 검증에서 입력 placeholder 1건이 AA 미달(3.08:1)로 확인되어 `--n-40` → `--n-60`으로 교체 후 6.30:1로 통과했다(게이트1 1회 반복). 그 외 전 조합 AA 이상 통과. 비활성(disabled) 버튼 텍스트 1건은 WCAG 예외 대상으로 조치 불필요.

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 검수 목록 페이지 (wireframe.html 1개 surface에 대응) |

- 공유 스타일: design.css
- 스크린샷: (없음 — 로컬 file:// 프리뷰로 확인)

## § 역등록 기록 (Phase 5 — 완료)

- **screen_design ITEM**: `SD-001` (title: "SCREEN-018 검수 목록 화면 — 고충실 디자인", domain_id: DOMAIN-005, status: draft, designs_screen: SCREEN-018)
- **렌더 업로드**: render_id=`main`, surface=`page` — `upload_design_render` 응답 `action: "add"`, `new_version: 2`
  - html: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-001/main.html`
  - css: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-001/main.css`
- 업로드 전 `design-main.html`에서 `<link rel="stylesheet" href="design.css" />` 1줄만 제거(인라인 `style` 속성은 원래 없었음). 본문·구조·데이터는 무변경.
- Phase 6(ui_component 보강 권고 — 재검토 필요 칩, RefreshingNotice, `--surface-white` 토큰 미정 등)은 이번 배치에서 수행하지 않음. 사용자 확인 후 별도 진행.
