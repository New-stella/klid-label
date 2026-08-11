# 검수 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-005 검수 |
| last sync | 2026-08-11T06:32:45.230Z (session 2) |
| 화면 수 | 2개 |
| ui_component 카탈로그 | populated 108건 |
| 출력 루트 | /Users/chanki/Documents/workspace/klid-label-worktrees/design/docs/screen-design/검수-DOMAIN-005 |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| SCREEN-018 | 검수 목록 화면 | UNCHANGED | ✅ | API-008, API-138 | ROLE-001 |
| SCREEN-019 | 검수 상세 화면 | UNCHANGED | ✅ | API-009, API-010, API-011, API-013, API-014, API-015, API-021, API-132, API-066, API-102, API-103, API-104, API-105 | ROLE-001 |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 108 |
| app_shell + nav | _shared/shell-nav.md | 2 |
| api_endpoint | _shared/api/ | 15 |
| constant | _shared/constant/ | 0 |
| permission_role | _shared/role/ | 1 |
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
| 1 | SCREEN-018 — 검수 목록 화면 | screens/SCREEN-018/SCREEN-018.md | wireframe.html | uc/ | ac/ |
| 2 | SCREEN-019 — 검수 상세 화면 | screens/SCREEN-019/SCREEN-019.md | wireframe.html | uc/ | ac/ |

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

> Phase 2~4(로컬 고충실 디자인 작성) 결과 인덱스. `screens/SCREEN-NNN/design/` 참조.
> Phase 5(screen_design 역등록)는 별도 실행 여부에 따라 아래 상태가 갱신된다.

| SCREEN-ID | surface 수 | design/ | 역등록(SD) | 생성 시각 |
|---|---|---|---|---|
| SCREEN-018 | 1 (main) | ✅ design-main.html + design.css + design-notes.md | ✅ SD-001 | 2026-08-11T06:20:00.000Z |
| SCREEN-019 | 1 (main) | ✅ design-main.html + design.css + design-notes.md | ✅ SD-005 | 2026-08-11T06:27:22.476Z |

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
