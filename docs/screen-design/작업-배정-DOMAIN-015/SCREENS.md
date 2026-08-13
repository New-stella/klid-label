# 작업 배정 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-015 작업 배정 |
| last sync | 2026-08-11T06:31:45.203Z (session 2) |
| 화면 수 | 1개 |
| ui_component 카탈로그 | populated 108건 |
| 출력 루트 | /Users/chanki/Documents/workspace/klid-label-worktrees/design/docs/screen-design/작업-배정-DOMAIN-015 |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| SCREEN-012 | 작업 목록 화면 | UNCHANGED | ✅ | API-001, API-002, API-070, API-071, API-072, API-073, API-136, API-137, API-116, API-187 | ROLE-001, ROLE-002 |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 108 |
| app_shell + nav | _shared/shell-nav.md | 2 |
| api_endpoint | _shared/api/ | 10 |
| constant | _shared/constant/ | 0 |
| permission_role | _shared/role/ | 2 |
| implementation_guideline | _shared/guideline/ | 0 |

## 빌드 순서 (mc-logi-screen-implement 참조)

> "공유 자산 먼저, 화면 단위 점진" 원칙.

### Phase 1 — 공유 자산
1. guideline/ → constant/ → design-system.md
2. ui-catalog.md (0건이면 Phase 0.5 선행)
3. shell-nav.md
4. api/ + role/

### Phase 4 — 화면별 점진

| 순서 | 화면 | screen_spec | 와이어프레임 | UC | AC |
|---|---|---|---|---|---|
| 1 | SCREEN-012 — 작업 목록 화면 | screens/SCREEN-012/SCREEN-012.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| UI-101 | ui_component | NEW |
| UI-102 | ui_component | NEW |
| UI-103 | ui_component | NEW |
| UI-104 | ui_component | NEW |
| UI-105 | ui_component | NEW |
| UI-106 | ui_component | NEW |
| UI-107 | ui_component | NEW |
| UI-108 | ui_component | NEW |

## 디자인 산출물 (mc-logi-screen-design)

> Phase 1~4(입력합성→로컬 디자인→검증→키트 반영) 결과. Phase 6(ui_component 보강)은 별도 진행. Phase 5(screen_design 역등록)는 화면별로 완료 여부가 다르므로 아래 SD-ID 열 참조.

| SCREEN-ID | design/ 유무 | surface 수 | 파일 | 생성 시각 | WCAG 검증 | SD-ID (Phase 5) | 비고 |
|---|---|---|---|---|---|---|---|
| SCREEN-012 | ✅ | 1 (main) | design-main.html, design.css, design-notes.md | 2026-08-11T06:30:00.000Z | 전 조합 AA 이상 통과 | SD-003 | 신규 컴포넌트 후보 3건(AlertBanner/CountChip/DerivativeBadge) — design-notes.md §카탈로그 미정 매핑 참조. |

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
