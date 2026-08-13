---
screen: SCREEN-037
generated_at: 2026-08-11T07:32:09.106Z
screen_design_item: SD-011
design_render_urls:
  main: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-011/main.html (css: /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-011/main.css)
surfaces: [main]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe.html]
generated_by: mc-logi-screen-design
---

# SCREEN-037 디자인 노트

> Phase 0~5(입력 합성 → 로컬 디자인 작성 → 검증 → 키트 반영 → screen_design 역등록)까지 수행했다.
> Phase 6(ui_component 보강 권고)은 표로 안내만 하고 실제 등록은 보류했다(이번 배치 작업 범위 — 사용자 동의 후 별도 진행).

## § 디자인 결정 (와이어프레임 대비 추가분)

- **상태별**:
  - 와이어프레임 골격의 ② "로딩·오류 상태" 섹션은 실제 화면에서 ③④(수정 폼·첨부파일 관리) 대신 택일 렌더되는 **과도기 상태**다(동시 노출 아님). 섹션 수·순서는 그대로 두되, 점선 보더 + 회색(`--n-0`) 표면의 컴팩트한 "참고" 카드로 표현해 실제 화면 비중을 낮추고, 조회 완료 후의 폼(③④)을 시각적 주 콘텐츠로 강조했다(design-prompt §디자인 지시 3항 "시각 위계").
  - 로딩 스켈레톤은 실제 폼 필드 구조(입력 44px 1개 + 텍스트영역 88px 1개)를 흉내낸 두 블록으로, 에러는 아이콘+제목+본문+"다시 시도" 버튼으로 구성된 배너로 나란히 배치해 색상만으로 구분하지 않고 아이콘·텍스트를 병기했다(do_rules "에러는 색만 쓰지 말고 아이콘과 텍스트를 함께 표시").
  - "중요 공지" 체크박스는 상세 조회로 이미 체크된 상태(`checkbox--checked`, primary 배경 + 흰 체크 아이콘)로 시작해 "수정 중"이라는 화면 목적을 반영했다.
  - 첨부파일 0건 상태도 목록과 배타적으로 노출되는 실제 분기이므로, 로딩·오류 참고 카드와 동일한 시각 패턴(점선 보더 참고 카드)으로 별도 문서화했다.
- **데이터 밀도**: 첨부파일 목록에 3건의 그럴듯한 실제 파일(PDF 1.2MB·HWP 340KB·XLSX 58KB)을 채우고, 그중 1건은 의도적으로 긴 파일명("2026년_8월_정기점검_안내문_상세일정및영향범위.pdf")을 넣어 `text-overflow: ellipsis` 처리를 시각적으로 확인 가능하게 했다(`title` 속성으로 전체 파일명 접근성 보완). 공지 내용도 실제 정기 점검 안내문 형태의 3문단 텍스트로 채워 textarea의 실제 줄바꿈·여백을 확인할 수 있게 했다.
- **시각 위계**: 페이지 전체를 840px로 제한해(내부 저작도구의 목록·캔버스 화면과 달리 단일 폼 편집 화면이라 가독성 우선 — design-system.md layout.notes의 "콘텐츠 최대 폭 제한 없음" 지침은 목록·캔버스류에 대한 것으로 해석해 이 화면에서는 예외적으로 폭을 제한했다) 읽기 편한 폼 레이아웃을 만들었다. 수정 폼과 첨부파일 관리를 별도 카드(`shadow.sm`)로 분리해 두 영역의 책임을 구분했고, 폼 액션(취소/저장)은 카드 하단에 구분선을 두고 우측 정렬해 1차(저장, primary)·보조(취소, outline) 액션을 명확히 구분했다.
- **컴포넌트 디테일**: 카드는 `shadow.sm`만 사용(do_rule 준수). 버튼·입력·체크박스에 focus-visible 3px 링 + 2px 오프셋을 실제로 구현했고, 인풋 hover 시 보더가 `n-20`→`n-40`으로 전환되며(200ms standard easing), 필수 항목은 라벨 옆 빨간 별표(`*`, aria-hidden) + 스크린리더용 "(필수)" 텍스트를 병기했다(색상 단독 사용 금지 원칙). 모든 인터랙티브 요소(버튼·체크박스 히트 영역·첨부 삭제 아이콘 버튼)는 44×44px 이상을 실제로 구현했다 — 첨부 삭제처럼 시각 라벨이 없는 아이콘 전용 버튼에는 `aria-label`을 파일명과 함께 명시했다.

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 페이지 헤더(뒤로가기+제목) | Button(UI-001, variant=outline, leftIcon) + Heading(h1) | title-lg(22/700 `--n-100`), button(17/500) | screen_spec 골격이 PageHeader(UI-012)가 아니라 Button+Heading 조합으로 선언돼 있어 그대로 따름(breadcrumb·actions 없음) |
| 로딩·오류 상태 참고 카드 | Skeleton(UI-033) / ErrorState(UI-021) | caption(14/600, `--n-60`), body-sm(15/600, `--n-90`), `--n-0`/`--n-10` | 실제 화면은 조회 결과에 따라 택일 렌더 — 참고용 병렬 표기 |
| 제목/내용 입력 | Field(UI-099) + Input(UI-002) / Textarea(UI-027) | label(14/600 `--n-70`), body-md(17/400), caption(14/400 `--n-60`), `--radius-md`(6px) | Field가 label[for] 연결 전담(Input/Textarea는 label prop 없음) |
| 중요 공지 체크박스 | Checkbox(UI-024) + Field(UI-099, orientation=horizontal) | label(14/600), `--p-50`(체크 배경), `--n-40`(미체크 보더) | 라벨은 옆 label 요소가 담당(UI-024 자체는 라벨 미보유) |
| 취소/저장 버튼 | Button(UI-001, variant=outline / primary) | button(17/500) | 저장(primary)이 API-098 PUT을 트리거(screen_spec 지정) |
| 파일 추가 버튼 | Button(UI-001, variant=outline, leftIcon) | button(17/500) | API-106 업로드 트리거(screen_spec 지정) |
| 기존 첨부 목록 | (screen_spec type=List, custom — 카탈로그에 전용 List 컴포넌트 없음, 아래 ⚠️ 참조) | body-sm(15/400 `--n-90`), caption(14/400 `--n-60`), `--n-0` 행 배경 | 아이콘(Paperclip)+파일명(ellipsis)+크기+삭제(UI-001 ghost icon) 구성 |
| 첨부 삭제 버튼 | Button(UI-001, variant=ghost, size=icon) | `--error-0`/`--error-60`(hover) | 시각 라벨 없어 파일명 포함 aria-label 필수(UI-001 accessibility_notes) |
| 첨부 0건 참고 카드 | EmptyState(UI-020) 패턴(문구만 차용, 아이콘 없이 간소화) | caption(14/400 `--n-60`) | 목록과 배타적으로 노출되는 실제 분기를 참고 카드로 문서화 |

### ⚠️ 토큰·컴포넌트 미정 목록 (Phase 6 대상 후보 — 이번 배치는 등록 보류)

1. **첨부파일 행 목록(AttachmentList)** — ui-catalog(UI-001~UI-108)에 "아이콘+파일명+크기+삭제 버튼" 조합의 전용 리스트 컴포넌트가 없다. screen_spec도 `type=List`로 화면 고유 커스텀으로 선언해 카탈로그 매핑을 시도하지 않고 직접 스타일링했다. 공지 상세 화면(SCREEN-031)에도 동일한 첨부 목록 UI가 필요하므로 재사용성이 높아 Phase 6 신규 컴포넌트 후보로 권고한다.
2. **`--surface-white`(#ffffff)** — design-system.md가 순백을 별도 색 토큰으로 선언하지 않는다(SCREEN-018/030/031/036 디자인과 동일한 기존 미정 사항, 새로 발견된 것 아님). 카드/입력/페이지 캔버스 기본 표면에 순백을 그대로 쓰고 `--surface-white` CSS 변수로 명시했다.
3. **웹폰트 소스 미정** — design-system.md에 `typography.body.family=Pretendard GOV` 지정은 있으나 `@font-face` woff2 URL이 제공되지 않았다. `font-family` 선언 + 시스템 한글 폴백(Apple SD Gothic Neo, Noto Sans KR)만 적용했다(design-prompt 지시대로 임의 폰트 대체 없음, 다른 화면들과 동일 사항).

> Phase 6 안내(실등록 보류): 위 1번(AttachmentList) 1건이 이번 배치의 신규 컴포넌트 후보다. 사용자 동의 시 `register_ui_components`로 등록하고 반드시 `mc-logi-screen-kit` SYNC를 이어서 실행해야 한다(로컬 카탈로그 갱신 필수 — 스킬 규칙 7).

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

> `scripts/contrast_checker.py`로 실제 사용 조합을 검사. 본문 AA 4.5:1 / 큰글씨 3:1, 비텍스트 UI(컨트롤 경계) 3:1 하한.

| 조합 (텍스트/요소 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문 × 카드/페이지 배경 | `--n-90` × `--surface-white` | 16.18:1 | AAA | OK |
| 제목(h1) × 페이지 배경 | `--n-100` × `--surface-white` | 18.43:1 | AAA | OK |
| 보조/muted 텍스트 × 흰 배경 | `--n-60` × `--surface-white` | 6.30:1 | AA | OK |
| **참고 카드 라벨 × 회색 표면** | `--n-50` × `--n-0` | 4.13:1 | **FAIL**(일반 텍스트) | `--n-60`(5.77:1)로 교체 — 조치 완료(do_rules "회색 표면 위 보조 텍스트는 n-60 이상" 명시 위반 방지) |
| 첨부행 텍스트 × 회색 표면(`--n-0`) | `--n-90` × `--n-0` | 14.82:1 | AAA | OK |
| 첨부행 크기 표기 × 회색 표면 | `--n-60` × `--n-0` | 5.77:1 | AA | OK |
| btn-primary 텍스트 | `--surface-white` × `--p-50` | 4.55:1 | AA | OK(여유 적음 — design-system known_gaps 명시값과 일치) |
| btn-primary hover 텍스트 | `--surface-white` × `--p-60` | 6.83:1 | AA | OK |
| btn-outline 텍스트 | `--p-50` × `--surface-white` | 4.55:1 | AA | OK |
| btn-ghost 텍스트 | `--n-70` × `--surface-white` | 8.68:1 | AAA | OK |
| 입력 placeholder | `--n-60` × `--surface-white` | 6.30:1 | AA | OK(다른 화면과 동일하게 n-40 대신 n-60 적용) |
| 필수 표시(*) | `--error-50` × `--surface-white` | 4.56:1 | AA | OK |
| 첨부 삭제 아이콘 hover | `--error-60` × `--error-0` | 5.31:1 | AA | OK |
| 체크박스 체크 아이콘(비텍스트) | `--surface-white` × `--p-50` | 4.55:1 | Non-text UI 3:1 기준 PASS | OK |
| **체크박스 미체크 보더(비텍스트 UI)** | `--n-30` × `--surface-white` | 2.01:1 | **FAIL**(비텍스트 UI 3:1) | `--n-40`(3.08:1)로 교체 — 조치 완료 |
| 에러 배너 아이콘(비텍스트) | `--error-50` × `--surface-white` | 4.56:1 | Non-text UI 3:1 기준 PASS | OK |
| 첨부행 아이콘(비텍스트, aria-hidden) | `--n-50` × `--n-0` | 4.13:1 | Non-text UI 3:1 기준 PASS | OK(장식 아이콘이라 일반 텍스트 4.5:1 미적용) |

**요약**: 검증에서 2건이 최초 FAIL로 확인되어 토큰 교체로 조치했다 — ① 참고 카드 라벨(`--n-50`→`--n-60`, 회색 표면 위 보조 텍스트 규칙), ② 체크박스 미체크 보더(`--n-30`→`--n-40`, 비텍스트 UI 컨트롤 경계). 그 외 전 조합 AA 이상(또는 비텍스트 UI 3:1) 통과. 게이트1 반복 1회.

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 공지 수정 페이지 (wireframe.html 1개 surface에 대응) |

- 공유 스타일: design.css
- 스크린샷: (없음 — 로컬 http 서버 + Playwright 프리뷰로 확인, 별도 산출물 저장 안 함)

## § 역등록 기록 (Phase 5 — 완료)

- **screen_design ITEM**: `SD-011` (title: "SCREEN-037 공지 수정 화면" — "고충실 디자인" 접미 없음, domain_id: DOMAIN-009, status: draft, designs_screen: SCREEN-037)
- **렌더 업로드**: render_id=`main`, surface=`page` — `upload_design_render` 응답 `action: "add"`, `new_version: 2`
  - html: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-011/main.html`
  - css: `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-011/main.css`
- 업로드 전 `design-main.html`에서 `<link rel="stylesheet" href="design.css" />` 1줄만 제거. 본문·구조·데이터는 무변경.
- 링크 그래프: `create_item` 응답 `links.unresolved: 0` (belongs_to_domain + designs→SCREEN-037 2건 생성).
