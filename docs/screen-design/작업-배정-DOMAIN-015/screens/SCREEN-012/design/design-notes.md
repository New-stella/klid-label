---
screen: SCREEN-012
generated_at: 2026-08-11T06:30:00.000Z
screen_design_item: SD-003
design_render_urls:
  main: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-003/main.html
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-012 디자인 노트 (작업 목록 화면)

## § 디자인 결정 (와이어프레임 대비 추가분)

- **상태별**: 작업 상태(미배정/대기/진행중/검수대기/완료/반려) 6종을 색+한글 라벨 병기 배지로 구분(색만으로 구분 안 함, DS dont_rules 준수). 오류 배너는 error 톤(아이콘+제목+본문+재시도)으로 상시 배치와 구분되게 표현. 로딩 스켈레톤(shimmer 애니메이션 3분할 바)·빈 상태(Inbox 아이콘+안내문)는 테이블 카드 하단에 "상태 예시" 참고 스트립으로 나란히 배치해 실제 화면에서 로딩/빈 상태/데이터가 동시에 나타나지 않음을 주석으로 명시하면서도 각 상태의 시각을 확인할 수 있게 함.
- **데이터 밀도**: 테이블 8행 실데이터(전북 지역 CCTV 영상명 예시) — 미배정 3행(2행은 일괄배정 선택 체크됨) · 배정완료(대기/진행중/검수대기/완료/반려) 5행. 영상명은 `max-width:260px` + `text-overflow:ellipsis` + `title` 툴팁으로 긴 파일명 처리, 영상 ID는 mono 폰트로 별도 줄에 병기.
- **시각 위계**: 헤더 → 오류배너 → 필터 카드 → KPI 5장 → (일괄액션바+테이블) → 오버레이 참고 순으로 40px(space-xl) 섹션 간격을 둬 절차를 구분. 조회(primary)·초기화(secondary), 저장(primary)·취소(outline) 등 1차/보조 액션을 variant로 명확히 구분. KPI 카드 중 "작업중" 카드를 `aria-pressed="true"` 선택 상태로 예시 표시(강조 테두리 2px + primary-0 배경 + 값 색 변경 — 색상 단독 아님, 테두리 두께로도 구분).
- **컴포넌트 디테일**: 카드는 shadow.sm + 1px 보더(DS elevation_levels), 오버레이(모달/드로어)는 shadow.lg로 격 구분. 버튼 hover(진한 톤 전환)·focus-visible(3px 링+2px 오프셋)·disabled(opacity 0.5) 상태를 CSS 순수 구현. 체크박스는 44×44 히트영역(`.checkbox-hit` 음수 마진 확장) 안에 20px 시각 체크박스, 이미 배정된 행은 `disabled` 처리(일괄 배정은 미배정 행 전용). 테이블 행 hover는 DS do_rules 명시값(#FFFBEB, 아래 접근성 절 참조)을 적용해 회색 계열 표면과 명확히 구분.

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 헤더/새로고침 버튼 | UI-012 PageHeader(자체 구성) + UI-001 Button(secondary) | typography title-lg 22px/700, spacing lg | breadcrumb 는 UI-013 패턴 참조(자체 마크업, nav aria-label) |
| 오류 안내 배너 | ⚠️ 미정 — 카탈로그에 범용 Alert/Banner 없음 | color.error 스케일(0/60/70/80) | `ErrorState`(UI-021)는 전체 영역 대체용이라 인라인 배너와 역할이 다름. 신규 컴포넌트 후보: `AlertBanner`(variant: info/warn/error, icon+title+desc+action) |
| 검색·필터 폼 | UI-085 TaskFilters(구성: UI-002 Input, UI-003 Select, UI-001 Button×2) | spacing md/lg, typography label 14px, radius md | 4입력 대기(draft) 상태 + 조회 버튼 일괄 적용 — 이번 디자인은 정적 값으로 표현 |
| KPI 카드 5장 | UI-010 KpiCard(onClick+selected) | typography display-sm 26px/700 값, label 14px, color.primary(선택 시) | "작업중" 카드 selected 예시. trend/unit prop 은 이 화면에 불필요해 미사용 |
| 일괄 배정 액션바 | 자체 구성(UI-001 Button×2) + ⚠️ 미정 개수 칩 | color.primary-0/10/70 | "N개 선택됨" 칩은 카탈로그에 범용 Badge/Chip 프리미티브가 없어 신규 후보: `CountChip`(텍스트 전용 pill) |
| 작업 목록 테이블 | UI-007 DataTable(제네릭 렌더 계층) + UI-024 Checkbox(선택 컬럼) | secondary-0 헤더 배경, spacing sm/md, radius lg(컨테이너) | 정렬은 서버 정렬(시간축 단일 기준) — `sortable` prop 미사용, 헤더에 `aria-sort` 직접 부여(컴포넌트 확장점 `columns[].meta.ariaSort` 활용) |
| 상태 배지(미배정/대기/진행중/검수대기/완료/반려) | UI-014 StatusBadge | neutral/warn/info/secondary/success/error 각 0(bg)+70(fg) | 톤 매핑은 카탈로그에 16종 상태의 정확한 색 배정이 명시돼 있지 않아 color_usage 절 의미(success=완료, error=반려, warn=대기, info=진행중, secondary=검수대기, neutral=미배정)로 이 디자인이 배정함 — 구현 시 컴포넌트 내부 매핑과 대조 필요 |
| 이벤트 배지 | UI-016 EventTypeBadge | secondary-0/70 | 6종 SFR 이벤트는 상태가 아니라 카테고리라 semantic 색 오용을 피해 secondary 고정 색으로 통일(색상은 보조 표시, 라벨 텍스트가 구분 담당) |
| 파생 유형 배지(겨울/야간/우천) | ⚠️ 미정 — 카탈로그에 전용 컴포넌트 없음 | neutral-0/70 + dashed border | EventTypeBadge 와 다른 시각(점선 보더)으로 "원본 아님"을 구분. 신규 후보: `DerivativeBadge` |
| 행 액션(배정/재배정/작업/마킹/이력) | UI-001 Button(ghost, sm 대응 축소 — btn-sm) | color.primary-60(ghost 텍스트) | 완료 행은 재배정 버튼 제외(이력만) |
| 페이지네이션 | UI-008 Pagination | radius md, color.primary-50(active) | 양끝+현재 페이지 앞뒤 1칸 규칙 반영, 첫/마지막 전용 버튼 없음 |
| 로딩 스켈레톤 참고 | UI-033 Skeleton(패턴 참조, shimmer) | neutral-0/10 그라디언트 | 실제로는 표 영역 전체를 대체 — 이 디자인에서는 나란히 배치한 참고 예시 |
| 빈 상태 참고 | UI-020 EmptyState | neutral-40(아이콘), neutral-60(설명) | DataTable 내부적으로 렌더하는 것과 동일 문구("배정된 작업이 없습니다") 재사용 |
| 작업 배정 모달 | UI-083 AssignModal(구성: UI-004 Modal + UI-002/003) | shadow.lg, radius lg, spacing lg | bulk 모드 예시(대상 2건, 칩 미리보기 2개 — 실제로는 최대 3건+외 N건 규칙) |
| 배정 이력 드로어 | UI-084 HistoryDrawer | color 8종 이벤트 tone(info/warn/success/error/neutral) | 반려 사유는 error-0 배경의 인라인 박스로 강조 |
| 검색·필터 카드, 테이블 카드 | UI-011 Card(단순화 — 이 디자인은 하위 컴포넌트 조합 대신 padding/shadow만 재현) | radius lg, shadow.sm, padding lg(24px) | CardHeader/CardTitle 등 세부 하위 컴포넌트 조합은 구현 단계에서 정합 필요 |

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

> 스킬 동봉 `scripts/contrast_checker.py` 로 실제 사용 조합을 검사했다. 모든 hex 는 design-system.md 팔레트 스케일에서 그대로 가져온 값이며, 표의 행 hover 배경 `#FFFBEB` 는 신규 색이 아니라 design-system.md `do_rules` 가 명시한 고정값을 그대로 인용한 것이다("표의 행 hover 표면은 #FFFBEB 를 쓴다").

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문 텍스트 × 카드 배경 | neutral-100 × white | 18.43:1 | AAA | OK |
| 본문 텍스트 × 페이지 배경 | neutral-100 × neutral-0 | 16.89:1 | AAA | OK |
| 보조 텍스트 × 카드 배경 | neutral-60 × white | 6.30:1 | AA(large 이상) | OK |
| 보조 텍스트 × 페이지 배경 | neutral-60 × neutral-0 | 5.77:1 | AA | OK |
| 영상 ID(mono, 12px) × 카드 배경 | neutral-60 × white | 6.30:1 | AA | OK |
| 행 hover 텍스트 × 행 hover 배경 | neutral-100 × #FFFBEB | 17.77:1 | AAA | OK — DS do_rules 지정값 |
| 테이블 헤더 텍스트 × 헤더 배경 | secondary-70 × secondary-0 | 10.01:1 | AAA | OK |
| 상태배지(미배정/파생칩) 텍스트 × 배경 | neutral-70 × neutral-0 | 7.95:1 | AAA | OK |
| 상태배지(대기) 텍스트 × 배경 | warn-70 × warn-0 | 8.43:1 | AAA | OK — 원래 base(warn-50) 는 known_gaps 대로 4.23:1 미달이라 -70 단계로 승격해 사용 |
| 상태배지(진행중) 텍스트 × 배경 | info-70 × info-0 | 6.82:1 | AA | OK |
| 상태배지(완료) 텍스트 × 배경 | success-70 × success-0 | 6.99:1 | AA | OK |
| 상태배지(반려) 텍스트 × 배경 | error-70 × error-0 | 8.01:1 | AAA | OK |
| Button primary 흰 글자 × primary-50 | white × primary-50 | 4.55:1 | AA | OK — DS known_gaps 명시대로 여유가 적어 본문 텍스트를 primary 배경에 올리지 않는 원칙 준수(버튼 라벨 용도로만 사용) |
| Button danger 흰 글자 × error-50 | white × error-50 | 4.56:1 | AA | OK |
| btn-ghost 텍스트 × 카드 배경 | primary-60 × white | 6.83:1 | AA | OK |
| 오류 배너 제목/본문 × 배경 | error-80/error-70 × error-0 | 11.78:1 / 8.01:1 | AAA | OK |
| 필터 절단 안내 × 카드 배경 | warn-70 × white | 9.27:1 | AAA | OK |
| KPI 선택 값 × 선택 배경 | primary-70 × primary-0 | 9.42:1 | AAA | OK |
| 일괄선택 칩 텍스트 × 칩 배경 | primary-70 × primary-10 | 8.34:1 | AAA | OK |
| breadcrumb 구분자(장식, aria-hidden) | neutral-60 × neutral-0 | 5.77:1 | AA | 원래 neutral-40(2.82:1, FAIL)로 초안했으나 장식 요소라도 시인성 확보를 위해 neutral-60 으로 교체 |

**결론**: 모든 실사용 조합이 AA 이상을 충족한다(경계 조합 2건은 Button primary/danger 흰 글자로 4.55~4.56:1 — DS 자체가 인지한 한계이며 본문 텍스트로는 쓰지 않는 원칙을 지켰다). WCAG AA 미달로 남겨둔 조합 없음.

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 작업 목록 화면 전체(단일 page surface — wireframe.html 1개에 대응) |

- 공유 스타일: design.css
- 스크린샷: (없음 — file:// 로컬 프리뷰로 확인)

## § 카탈로그 미정 매핑 (Phase 6 신규 컴포넌트 후보 — 이번 배치는 등록하지 않음)

와이어프레임/설계 골격에는 있으나 `_shared/ui-catalog.md`(100건) 에 정확히 대응하는 프리미티브가 없어, 이번 디자인은 DS 토큰 안에서 직접 새로 그렸다. Phase 6(이번 배치 범위 제외)에서 사용자 확인 후 `register_ui_components` 등록을 검토할 후보:

1. **AlertBanner** — 인라인 오류/안내 배너(아이콘+제목+본문+액션). `ErrorState`(UI-021)는 "영역 전체 대체"용이라 이 화면의 "목록은 유지하되 상단에 배너" 패턴과 역할이 다르다.
2. **CountChip** — "N개 선택됨"처럼 임의 텍스트를 담는 범용 pill. `StatusBadge`/`EventTypeBadge`/`StageBadge` 는 모두 특정 도메인 값 전용이라 이 용도에 맞지 않는다.
3. **DerivativeBadge** — 파생 영상(WINTER/NIGHT/RAIN, 해상도 프리셋) 표시 배지. `EventTypeBadge` 와 시각적으로 구분(점선 보더)되는 별도 의미축이라 재사용보다 신설이 맞다고 판단.

## § 역등록 기록 (Phase 5 — 완료)

- `create_item(type=screen_design, domain_id=DOMAIN-015, data.designs_screen="SCREEN-012")` → **SD-003** 생성(links.unresolved: 0).
- `upload_design_render(item_id=SD-003, render_id="main", surface="page", platform="web")` → `design-main.html`+`design.css` 업로드 성공(current_version: 2).
- 업로드 URL: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-003/main.html` (css: `main.css`)
- `get_item` 재조회로 `data.designs_screen="SCREEN-012"`, `data.renders[0].id="main"` 확정 확인.

```
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
item_id: SD-003
render_id: main
surface: page
```

### 재등록 (2026-08-18)

- **사유**: 목록 테이블 컬럼 구성이 확정 사양과 달랐다 — '영상 ID' 가 별도 컬럼이 아니라 영상명 셀 안에 겹쳐 있었고, '이벤트' 가 '촬영일시' 앞에 있었다. 정보가 빠진 것은 아니고 구조가 달랐다.
- **범위**: thead 재배치 + 8개 행의 영상 ID 셀 승격 + 이벤트/촬영일시 순서 교환. DS 토큰·CSS 무변경(`.video-id` 기존 스타일을 td 에 그대로 재사용)이라 대비 검증(D5) 재실행 대상 아님.
- **확정 컬럼**: 선택 / 영상명 / 영상 ID / 촬영일시 / 이벤트 / 상태 / 작업자 / 검수자 / 액션
- `upload_design_render(item_id=SD-003, render_id="main", surface="page")` → `action: "replace"`, `new_version: 8`
- **검증**: 서버 렌더를 다시 받아 헤더 9컬럼 순서와 `video-id` 셀 8건 확인.
