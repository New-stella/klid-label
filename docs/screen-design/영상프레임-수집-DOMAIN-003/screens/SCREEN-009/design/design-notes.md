---
screen: SCREEN-009
generated_at: 2026-08-11T00:00:00.000Z
screen_design_item: SD-004
design_render_urls:
  main: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-004/main.html
  main_css: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-004/main.css
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-009 영상 상세 화면 디자인 노트

## § 변경 로그

- 2026-08-11: SCREEN-009 screen_spec v29 반영 — '재비식별 요청' 버튼(및 확인 다이얼로그) 제거.

## § 디자인 결정 (와이어프레임 대비 추가분)

- **상태별**:
  - 상태 배지(EventTypeBadge/StatusBadge): pill 배지에 색상 tint 배경 + 진한 텍스트 + 작은 dot 아이콘을 병기(색만으로 구분하지 않음). 이벤트 유형(쓰러짐)은 warn 계열, 배치 상태(배치 완료)는 success 계열로 의미를 분리.
  - BatchStageIndicator: 7단계(비식별→마킹→VLM→프레임추출→AI탐지→AI분할→보간)를 원형 점+연결선으로 표현. 점은 상태와 무관하게 동일 크기이므로 상태 구분은 색상과 함께 각 스텝 하단 캡션 텍스트("비식별 완료" 등)가 전담. 화면에는 보이지 않는 `aria-live="polite"` 요약 문구를 함께 두어 스크린리더에도 진행 현황을 전달.
  - 오토라벨 결과 탭 하단에 "상태 참고" 패널(골격 외 보조 문서화, 아래 §역등록 기록 앞 설명 참조)로 로딩 스켈레톤 / 에러(ErrorState) / 빈 상태(EmptyState) 3종을 나란히 시각화. 실제 화면에서는 조회 상태에 따라 이 중 하나가 본문 대신 표시됨을 캡션으로 명시.
- **데이터 밀도**: 프레임 그리드는 6열 × 실측 가능 데이터(총 12개 중 12개 표시)로 채우고, 2개 타일에 이슈 dot을 배치해 실제 사용 시나리오를 재현. 라벨별 분포는 상위 5종 + "기타 5종"으로 묶어 10종 요구를 만족하면서도 카드 높이를 절제. 긴 라벨명(`motorcycle` 등)은 `text-overflow: ellipsis`로 잘림 처리.
- **시각 위계**: 헤더 카드(hero) → 탭 영역 순으로 여백(`--sp-lg` 24px) 리듬을 두어 절차를 구분. 헤더의 1차 액션은 없음(조회 화면 특성상 액션 버튼을 두지 않음). 뒤로가기는 ghost variant로 톤다운.
- **컴포넌트 디테일**: 버튼 3종(ghost/secondary/primary) 모두 hover 배경 전환(120ms) + focus-visible 3px outline/2px offset 적용. 프레임 타일은 hover/focus 시에만 "상세 보기" 버튼이 그라디언트 위로 드러나는 방식으로 상시 노출 시 생기는 시각 잡음을 줄임(포커스 시에도 동일하게 노출되도록 `:focus-within` 병행 — 키보드 접근성 보장).

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 뒤로가기 | UI-001 Button (variant=ghost) | typography.button(17px/500), spacing.md | |
| 페이지 제목/브레드크럼 | UI-012 PageHeader 패턴 재사용 · UI-013 Breadcrumb 패턴 | typography.title-lg(22px/700), neutral-8/6 | ⚠️ UI-012/013 의 `referenced_by_screen_ids`에 SCREEN-009가 없음 — 화면 상단 공통 헤더 관례를 신규 재사용. `mc-logi-screen-implement` 단계에서 실제 PageHeader 컴포넌트로 배선 권장 |
| 영상 헤더 카드(hero) | UI-011 Card | radius.lg(8px), shadow.sm, spacing.lg | Card 자체는 하위 컴포넌트 조립형이나 hero 레이아웃은 화면 전용 커스텀 배치 |
| 썸네일 | UI-041 AuthImage | neutral-9(미디어 매트, 다크 표면 유일 예외) | 실제 이미지는 JWT blob 로드 — 정적 목업은 대표 아이콘+캡션으로 표현 |
| CCTV명 | Heading(spec) | typography.title-md(18px/600) | |
| 이벤트 유형 배지 | UI-016 EventTypeBadge | warn 스케일(bg=warn-0, text=warn-7) | 색상은 예시(쓰러짐=warn 계열); 실제 매핑은 EV코드→라벨 맵을 따름 |
| 상태 배지 | UI-014 StatusBadge | success 스케일(bg=success-0, text=success-6) | data-status 속성으로 원본 상태값 노출(구현 시 배선) |
| 탭 네비게이션 | UI-009 Tabs (세로형 배치) | typography.nav-link(17px/500), primary-5/0 | ⚠️ 카탈로그 Tabs는 가로형 role=tablist 전제 설명 — 이 화면은 좌측 168~200px 레일에 세로로 배치(와이어프레임 골격 보존). 구현 시 방향 변형이 필요할 수 있음(신규 컴포넌트 후보 아님, Tabs 의 세로 variant로 흡수 권장) |
| 메타 정보 그리드 | ⚠️ 미정 — 카탈로그에 KeyValue/DescriptionList 급 컴포넌트 없음 | typography.label(14px/600)=dt, typography.body-sm(15px/400)=dd | raw `<dl>` 그리드로 조립(신규 컴포넌트 후보 — 아래 참조) |
| 처리 단계 표시 | UI-018 BatchStageIndicator | success/primary/error-5, neutral-2/3(연결선) | stages 배열 그대로 렌더(BE 순서 신뢰) |
| 프레임 썸네일 그리드 | UI-041 AuthImage(각 타일) | neutral-8(미디어 매트), error-5(이슈 dot) | |
| 프레임 상세 보기 → 라이트박스 | UI-004 Modal(size=xl 상당) | shadow.lg, radius.lg | `:target` CSS-only 구현 — 스크립트 제거 대비 |
| 처리 정보 4종 | UI-010 KpiCard 패턴(수치 3종) + UI-014 StatusBadge(상태 1종) | typography.display-sm(26px/700) | KpiCard는 "장식 아이콘 없음" 규칙 그대로 준수 |
| 신뢰도 분포 막대 | UI-076 ConfidenceDistributionChart | success-5/warn-4/error-5 | 3구간 색상 규약(고=success/중=warning/저=danger) 그대로 반영 |
| 라벨별 분포 바 | UI-040 SimpleBarChart(가로 트랙 variant로 재해석) | primary-5(막대), neutral-0(트랙) | ⚠️ UI-040 은 세로 막대+recharts 전제(`referenced_by_screen_ids`에도 SCREEN-009 없음) — 이 화면은 라벨 10종 텍스트 길이 문제로 가로 트랙형으로 표현. 구현 단계에서 SimpleBarChart를 그대로 쓸지, 별도 가로 바 variant가 필요한지 판단 필요 |
| 빈/에러 상태 | UI-021 ErrorState, UI-020 EmptyState(참고 패널) | error-5(아이콘), neutral-6(본문) | 참고 패널에 시각화(§상태별 참조) |
| 로딩 스켈레톤 참고 | UI-033 Skeleton | neutral-1(배경) + 스윕 애니메이션 | `prefers-reduced-motion` 고려는 구현 단계 과제로 남김(⚠️ 미반영) |

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

> `scripts/contrast_checker.py` 로 실제 사용 조합만 검사(팔레트 전체 조합이 아닌 선별 검사). 값은 모두 `_shared/design-system.md` 의 기존 토큰.

| 조합 (텍스트/그래픽 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문 × 백색 | neutral-9 × 백색 | 16.18:1 | AAA | OK |
| 보조텍스트 × 백색 | neutral-6 × 백색 | 6.30:1 | AA | OK |
| 희미텍스트 × 백색 | neutral-5 × 백색 | 4.51:1 | AA(경계) | OK — DS known_gaps 가 명시한 정확히 그 수치(4.51). "50단은 흰 배경에서만 AA 통과" 규칙에 따라 백색 배경 한정 사용만 적용, 회색 표면에는 미사용 확인 |
| 보조텍스트 × 회색표면(참고패널 bg) | neutral-6 × neutral-0 | 5.77:1 | AA | OK — do_rule "회색표면 위 보조텍스트는 60단 이상" 준수 |
| 배지-경고 텍스트 × warn 틴트 | warn-7 × warn-0 | 8.43:1 | AAA | OK |
| 배지-성공 텍스트 × success 틴트 | success-6 × success-0 | 5.26:1 | AA | OK |
| 배지-오류 텍스트 × error 틴트 | error-7 × error-0 | 8.01:1 | AAA | OK |
| 배지-중립 텍스트 × neutral 틴트 | neutral-7 × neutral-0 | 7.95:1 | AAA | OK |
| primary 버튼 텍스트(백색) × primary-5 | 백색 × primary-5 | 4.55:1 | AA(경계) | OK — DS known_gaps 가 명시한 "흰 글자 on primary 4.55:1" 그대로. 본문 텍스트를 primary 배경 위에 얹지 않는 규칙과 별개로 버튼 텍스트는 이 값으로 AA 충족 확인 |
| ghost 버튼 텍스트 × 백색 | neutral-7 × 백색 | 8.68:1 | AAA | OK |
| 탭 활성 텍스트 × primary 틴트 | primary-6 × primary-0 | 6.09:1 | AA | OK |
| 에러 상태 제목 × 백색 | error-7 × 백색 | 8.97:1 | AAA | OK |
| stage-dot 완료(비문자 UI) × 백색 | success-5 × 백색 | 4.57:1 | AA(≥3:1 하한 충분 충족) | OK |
| stage-dot 진행(비문자 UI) × 백색 | primary-5 × 백색 | 4.55:1 | AA(≥3:1 충족) | OK |
| stage-dot 실패/issue-dot(비문자 UI) × 백색 | error-5 × 백색 | 4.56:1 | AA(≥3:1 충족) | OK |
| 신뢰도차트 중간 구간 막대(비문자 UI) × 백색 | warn-4 × 백색 | 3.10:1 | Large-only(≥3:1 비문자 하한 충족) | OK — 텍스트가 아닌 그래픽 요소이며 막대 위/아래 수치·구간명 텍스트가 정보를 별도로 전달(색상 단독 의존 아님) |
| 라벨분포 bar-fill(비문자 UI) × 트랙(neutral-0) | primary-5 × neutral-0 | 4.17:1 | Large-only(≥3:1 충족) | OK — 수치가 우측에 텍스트로 병기됨 |
| stage-line 완료(장식적 연결선) × 백색 | success-4 × 백색 | 3.09:1 | ≥3:1 충족(경계) | OK — 연결선은 WCAG 1.4.11 "순수 장식" 예외에 해당(상태 정보는 dot+캡션이 전담), 여유는 적어 향후 토큰 변경 시 재검증 필요 |
| 썸네일 아이콘(비문자 UI) × 미디어 매트(다크) | neutral-4 × neutral-9 | 5.25:1 | 충족 | OK |

**요약**: 검사한 18개 조합 전부 최소 요구 기준(본문 AA 4.5:1 / 비문자 3:1) 충족. AA 실패 조합 없음 — Phase 2 재작성(게이트1) 없이 통과.

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 영상 상세 화면 전체(헤더 카드 + 탭 3종 + 라이트박스) |

- 공유 스타일: design.css
- 스크린샷: (없음 — 로컬 file:// 프리뷰로 확인)
- render_id 규약: `main` (SCREEN-009 static_renders 의 render_id=`main`, surface=`page` 와 동일하게 맞춰 두어 Phase 5 역등록 시 비교 뷰가 짝지어지도록 준비됨 — 이번 배치에서는 역등록 미실행)

## § 토큰 미정 매핑 (신규 컴포넌트 후보 — logicraft 등록은 미실행)

1. **KeyValue / DescriptionList** — 기본 정보 탭의 "메타 정보 그리드"가 바인딩하는 컴포넌트가 카탈로그에 없어 raw `<dl>`+CSS 그리드로 조립했다. 재사용 빈도가 높을 것으로 예상되는 영역(영상/작업 상세류 화면 공통)이라 `ui_component` 신규 등록 후보.
2. **세로형 Tabs 변형** — UI-009 Tabs는 가로 배치 전제 설명만 있고, 이 화면은 좌측 168~200px 레일에 세로 배치했다. 신규 컴포넌트라기보다 UI-009의 orientation variant로 흡수하는 편이 카탈로그 일관성에 유리해 보인다.
3. **가로 트랙형 분포 바(라벨별 분포)** — UI-040 SimpleBarChart는 세로 막대+recharts 전제라 라벨명 10종을 가독성 있게 넣기 위해 가로 트랙형(label + progress-track + count)으로 재구성했다. UI-040을 그대로 쓸지 별도 컴포넌트로 분리할지는 구현 단계 판단이 필요하다.

이상 3건은 Phase 6(ui_component 카탈로그 보강 권고) 대상이나, **이번 배치는 Phase 4까지만 수행**하도록 지시받아 등록·권고 절차는 진행하지 않았다. 후속 배치에서 사용자 확인 후 Phase 5(screen_design 역등록)·Phase 6(ui_component 보강)을 이어서 진행한다.

## § 역등록 기록 (Phase 5 — 완료)

2026-08-11, 별도 배치에서 Phase 5(logicraft screen_design 역등록)를 실행했다. Phase 6(ui_component 카탈로그 보강)은 이번 배치 범위에 포함하지 않았다(§토큰 미정 매핑 3건은 그대로 미등록 상태로 남음).

- `create_item(type=screen_design, title="SCREEN-009 영상 상세 화면 — 고충실 디자인", domain_id="DOMAIN-003", status="draft", data={title, designs_screen:"SCREEN-009", description, device:"desktop", designer:"claude-screen-design", design_source:{tool:"other", version:"mc-logi-screen-design 1.3.2"}})` → **SD-004** 생성 (links.unresolved=0, `belongs_to_domain`→DOMAIN-003 · `designs`→SCREEN-009 자동 해석).
- `upload_design_render(item_id="SD-004", render_id="main", surface="page", label="영상 상세 화면 — 고충실 디자인", uploaded_by="claude-screen-design", html=<design-main.html 본문에서 `<link rel="stylesheet" href="design.css">` 제거>, css=<design.css 본문 그대로>)` → 업로드 성공, `current_version=2`.
  - `main.html`: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-004/main.html`
  - `main.css`: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-004/main.css`
- 검증: `get_item(SD-004, fields=["designs_screen","renders"])` 재조회로 `designs_screen="SCREEN-009"` · `renders[0].id="main"` · `surface="page"` 확인. render_id/surface가 SCREEN-009 static_renders(render_id=`main`, surface=`page`)와 동일해 비교 뷰가 짝지어진다.
