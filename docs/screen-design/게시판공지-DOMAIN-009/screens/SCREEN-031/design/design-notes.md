---
screen: SCREEN-031
generated_at: 2026-08-11T00:00:00Z
screen_design_item: SD-010
design_render_urls:
  - /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-010/main.html
  - /uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-010/delete-confirm.html
surfaces: [main, delete-confirm]
ds: DS-001 (KRDS Public)
source_wireframes: [wireframe-main.html, wireframe-delete-confirm.html]
generated_by: mc-logi-screen-design
---

# SCREEN-031 공지 상세 화면 디자인 노트

## § 디자인 결정 (와이어프레임 대비 추가분)

- **상태별**:
  - 로딩(Skeleton): "게시글 본문" 카드 안에 실제 로드된 콘텐츠와 별도로, 참고용 "상태 참고" 하위 블록에 제목·본문 3줄 스켈레톤 바를 두어 실제 화면 전환 시(로딩 중) 이 영역 전체가 이 형태로 대체됨을 보여준다. 와이어프레임이 Skeleton(state=loading)·Alert(state=error)를 같은 섹션의 컴포넌트로 나열한 것과 동일한 방식(같은 카드 안에 나열)을 따랐다 — 새 섹션을 추가하지 않았다.
  - 에러(ErrorState): 같은 "상태 참고" 블록에 AlertTriangle 아이콘 + 제목 "공지를 불러올 수 없습니다" + 보조문 + "목록으로" outline 버튼으로 구성. 아이콘+텍스트 병기(색상 단독 구분 금지).
  - 상태 뱃지: "중요"(pinned) 배지는 warn 톤(연한 배경 + 짙은 글자) + Pin 아이콘, "발행" 배지는 success 톤(연한 배경 + 짙은 글자). 두 배지 모두 텍스트 라벨을 항상 병기해 색상 단독 구분을 피했다. ("작성중"/DRAFT 배지는 이 데모 데이터에 없어 렌더하지 않았으나 `.badge-draft`(neutral 톤) 클래스로 CSS에 정의해 두었다.)
  - 첨부 다운로드 중 상태: 두 번째 첨부 행을 "다운로드 중"으로 렌더 — 파일명/크기 텍스트를 옅게, 다운로드 버튼을 `aria-busy` + `disabled` + Spinner 아이콘으로 표시(재클릭 방지).
- **데이터 밀도**: 본문은 실제 업무 시나리오(정기 점검 안내) 기준 4문단 + 불릿 목록으로 구성한 현실적인 텍스트로 채웠다. 첨부파일 파일명은 실제 있을 법한 긴 이름(`라벨링_캔버스_점검_영향범위_안내.pdf`)으로 두고 `.attachment-name`에 `text-overflow: ellipsis`를 적용해 좁은 폭에서 잘리도록 했다.
- **시각 위계**: 상단 액션 바(목록으로=ghost / 발행취소=outline)와 하단 관리 액션(수정=secondary / 삭제=danger)을 서로 다른 카드로 분리해 두 액션 그룹이 하나의 액션바가 아님을 명확히 했다(SCREEN-031.md 설명 그대로). 본문 카드 내부에서는 배지 → 제목 → 메타 → 구분선 → 본문 순으로 시각 흐름을 배치하고 구분선(`article-divider`) 위아래 여백을 `--space-lg`로 통일했다.
- **컴포넌트 디테일**: 카드는 `shadow.sm` 한 겹만 사용(모달만 `shadow.lg`), 버튼 hover/active/disabled 상태를 각 variant별로 정의(예: `btn-primary` hover → primary-600, active → primary-700), 첨부 행 hover 시 배경 tint, 포커스 가능한 모든 요소에 `focus-visible` 3px 링 + 2px 오프셋을 공통 규칙으로 적용.

## § 컴포넌트 · 토큰 매핑

| 화면 영역 | 컴포넌트 (ui-catalog) | 주요 토큰 (design-system) | 비고 |
|---|---|---|---|
| 목록으로 버튼 | UI-001 Button (variant=ghost) | color.neutral-700, typography.button(17px/500) | ArrowLeft 아이콘 |
| 발행 / 발행취소 버튼 | UI-001 Button (variant=primary / outline) | color.primary-500/600, spacing.md | pubStatus 기준 상호 배타적 노출(디자인은 PUBLISHED→발행취소 상태를 렌더) |
| 수정 버튼 | UI-001 Button (variant=secondary) | color.neutral-300(border)/900(text) | Pencil 아이콘 |
| 삭제 버튼(본문/모달 공통) | UI-001 Button (variant=danger) | color.error-500/600/700 | Trash2 아이콘 |
| 삭제 확인 모달 | UI-005 ConfirmDialog (variant=danger, Modal size=sm 기반) | shadow.lg, radius.lg, spacing.lg | title/description/confirmLabel/cancelLabel props 그대로 매핑 |
| "중요" 배지 | ⚠️ 미정 — 카탈로그에 정확한 대응 없음(§신규 컴포넌트 후보 참조) | color.warn-50/700, typography.label(14px/600) | Pin 아이콘 + "중요" 텍스트 |
| "발행" 상태 배지 | ⚠️ 미정 — UI-014 StatusBadge는 워크플로 16종 코드 전용(PUBLISHED/DRAFT 미포함, 매핑 밖은 회색 폴백이라 의도한 success/neutral 구분이 나오지 않음) | color.success-50/700 | §신규 컴포넌트 후보 참조. 구현 시 StatusBadge 상태 enum 확장 검토를 권장(신규 컴포넌트가 아니라 기존 UI-014 개정 — mc-logi-update 소관) |
| 게시 메타(작성자/등록/수정) | UI-106 KeyValueGrid | typography.caption(dt)/body-sm(dd), color.neutral-600/700 | items:[{label,value}] 로 매핑, 가로 배치로 구성(원본은 2열 그리드지만 3쌍뿐이라 인라인 나열로 표현) |
| 본문 텍스트 | (프리미티브 — 카탈로그 컴포넌트 아님) | typography.body-md(17px/400/1.6) | whitespace-pre-wrap |
| 로딩 placeholder | UI-033 Skeleton | color.neutral-100, radius.sm | width/height/rounded props |
| 조회 실패 안내 | UI-021 ErrorState | color.error-50/700 | onRetry를 "목록으로" 네비게이션으로 재사용(설계 노트: SCREEN-031.md "ErrorState — error \|\| !notice 시 표시 + 목록으로 버튼") |
| 첨부파일 헤딩 | (Heading 프리미티브) | typography.title-sm(17px/600) | Paperclip 아이콘 + 개수 |
| 첨부 다운로드 목록 | ⚠️ 미정 — 카탈로그에 "파일명+크기+다운로드버튼" 리스트 전용 컴포넌트 없음(§신규 컴포넌트 후보 참조) | color.neutral-100(border)/50(hover), typography.body-sm/caption | 다운로드 아이콘 버튼은 UI-001 Button(variant=ghost, size=icon-sm)로 매핑 가능 |

## § 접근성 검증 (WCAG 대비 — Phase 3 D5)

스킬 동봉 `scripts/contrast_checker.py`로 실제 사용한 텍스트/배경 조합을 검사했다. 토큰은 모두 `_shared/design-system.md`의 기존 scale 값만 사용(신규 hex 없음).

| 조합 (텍스트 × 배경) | 토큰명 | 대비 | 판정 | 조치 |
|---|---|---|---|---|
| 본문 텍스트 × 카드배경 | neutral-900 × white | 16.18:1 | AAA | OK |
| 공지 제목(h1) × 카드배경 | neutral-950 × white | 18.43:1 | AAA | OK |
| 모달 본문 설명 × 모달배경 | neutral-800 × white | 12.1:1 | AAA | OK |
| KeyValue dd / 첨부파일명 × 배경 | neutral-700 × white | 8.68:1 | AAA | OK |
| KeyValue dt / caption 텍스트 × 배경 | neutral-600 × white | 6.3:1 | AA | OK |
| caption 텍스트 × 페이지 배경(neutral-50) | neutral-700 × neutral-50 | 7.95:1 | AAA | OK |
| caption 텍스트 × 페이지 배경(neutral-50) | neutral-600 × neutral-50 | 5.77:1 | AA | OK |
| Primary 버튼 텍스트 × 배경 | white × primary-500 | 4.55:1 | AA | OK(DS known_gaps가 명시한 여유 적은 조합 — 그대로 수용) |
| Primary 버튼 hover 텍스트 × 배경 | white × primary-600 | 6.83:1 | AA | OK |
| Outline 버튼 텍스트 × 배경 | primary-600 × white | 6.83:1 | AA | OK |
| Danger 버튼 텍스트 × 배경 | white × error-500 | 4.56:1 | AA | OK |
| Danger 버튼 hover 텍스트 × 배경 | white × error-600 | 5.95:1 | AA | OK |
| "중요" 배지 텍스트 × 배경 | warn-700 × warn-50 | 8.43:1 | AAA | OK — DS known_gaps가 지적한 base warn-600×warn-50(4.23:1, FAIL) 조합을 피해 한 단계 더 진한 warn-700을 채택 |
| "발행" 배지 텍스트 × 배경 | success-700 × success-50 | 6.99:1 | AA | OK |
| "작성중"(미렌더) 배지 텍스트 × 배경 | neutral-700 × neutral-100 | 7.07:1 | AAA | OK |
| 에러/경고 블록 텍스트 × 배경 | error-700 × error-50 | 8.01:1 | AAA | OK |
| 에러 아이콘(비텍스트) × 배경 | error-600 × error-50 | 5.31:1 | AA(비텍스트 3:1 기준 상회) | OK |
| 첨부 아이콘 · "다운로드 중" 텍스트 × 배경 | neutral-500 × white | 4.51:1 | AA(경계값, DS 문서상 "흰 배경에서만 통과" 케이스) | OK — 회색 표면(tint) 위에는 이 톤을 쓰지 않음(전부 white 카드 위에서만 사용) |
| 포커스 링(비텍스트) × 배경 | primary-500 × white | 4.55:1 | AA(비텍스트 3:1 기준 상회) | OK |

**요약**: 검사한 17개 조합 전부 AA(4.5:1 본문 / 3:1 큰글씨·비텍스트) 이상 통과. DS의 `known_gaps`가 사전에 지적한 "warn 배지 base 톤 미달" 문제는 warn-700으로 한 단계 진하게 조정해 회피했다(신규 hex 발명 없이 같은 scale 안에서 이동).

## § surface 파일 인덱스

| surface | 파일 | SCREEN surface 대응 |
|---|---|---|
| main | design-main.html | 공지 상세 본문 페이지(상단 액션바 · 게시글 본문 · 하단 관리 액션 · 첨부파일) |
| delete-confirm | design-delete-confirm.html | 삭제 확인 모달(overlay, main 위에 오버레이) |

- 공유 스타일: design.css
- 스크린샷: (없음 — 로컬 파일 프리뷰로 확인)

## § 역등록 기록 (Phase 5)

- **SD-010** (`SCREEN-031 공지 상세 화면`, `designs_screen: SCREEN-031`, `brownfield.status: new`) 신규 등록.
- render `main` — surface `page`, width 1440, action `add` (new_version 2) → `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-010/main.html` (css: `main.css`)
- render `delete-confirm` — surface `modal`, overlays `main`, width 480, action `add` (new_version 3) → `/uploads/designs/4ece2c3f-8e99-46f5-9580-71108a76e578/SD-010/delete-confirm.html` (css: `delete-confirm.css`)
- render_id·surface는 screen_spec(SCREEN-031)의 `static_renders`(main=page, delete-confirm=modal/overlays=main)와 동일하게 맞춰 비교 뷰에서 짝지어진다. `create_item` 응답 `links.unresolved: 0`(정상).

## § 신규 컴포넌트 후보 (Phase 6 — 등록은 보류, 안내만)

> 이번 배치 작업 지시에 따라 실제 `register_ui_components` 호출은 하지 않았다. 아래는 사용자 동의 시
> 등록을 권고하는 안내 표다.

| 후보명(제안) | category | 용도 | 근거 |
|---|---|---|---|
| Badge(범용, pinned/success/neutral variant) | display | "중요"(pinned) 표시 + "발행/작성중" 상태 표시처럼 워크플로 코드(UI-014 StatusBadge의 16종)가 아닌 자유 의미의 소형 pill 배지 | UI-014 StatusBadge는 고정 16개 워크플로 상태 코드 전용이라 PUBLISHED/DRAFT가 매핑 밖(회색 폴백)이고, UI-104 CountChip은 자유텍스트지만 색상 variant가 없어(primary tint 고정) 의도한 success/warn 구분을 낼 수 없다. 디자인은 임시로 `.badge-pinned`(warn 톤)/`.badge-published`(success 톤)/`.badge-draft`(neutral 톤) 클래스를 직접 정의해 사용했다. |
| AttachmentList(첨부 다운로드 목록) | data 또는 display | 파일 아이콘 + 원본 파일명(ellipsis) + 파일크기 + 다운로드 아이콘버튼 한 행, "다운로드 중" 비활성 상태 포함 | 카탈로그에 "파일명+크기+다운로드" 조합의 리스트 전용 컴포넌트가 없음(UI-098 FileInput은 업로드용). 디자인은 `.attachment-row` 등 raw 마크업으로 구현 — design-system.md do_rules("공통 컴포넌트가 있는 요소를 raw로 다시 만들지 않는다")를 향후 토큰 갱신 전파를 위해 컴포넌트화로 해소 권장. |

**참고**: "발행/작성중" 배지는 신규 컴포넌트보다 **기존 UI-014 StatusBadge의 상태 enum 확장**(PUBLISHED/DRAFT 추가)이 더 적합할 수 있다 — 이는 신규 등록이 아니라 기존 ITEM 개정이라 `mc-logi-update` 소관이며 이 스킬의 register_ui_components 범위 밖이다.
