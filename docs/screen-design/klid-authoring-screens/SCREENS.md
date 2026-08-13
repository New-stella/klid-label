# 저작도구 화면 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | - 저작도구 화면 |
| last sync | 2026-08-13T02:29:54.023Z (session 6) |
| 화면 수 | 7개 |
| ui_component 카탈로그 | populated 115건 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/r12-screen-design/docs/screen-design/klid-authoring-screens |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| SCREEN-005 | 라벨링 캔버스 화면 | CHANGED | ✅ | API-018, API-019, API-020, API-021, API-024, API-032, API-066, API-067, API-102, API-103, API-104, API-105, API-123, API-124, API-125, API-126, API-127, API-128, API-129, API-132, API-134, API-133, API-135, API-093, API-182, API-012, API-178, API-022, API-023, API-168, API-170, API-172, API-173, API-183, API-184, API-177, API-195, API-196, API-197 | ROLE-001, ROLE-002 |
| SCREEN-006 | 마킹 화면 | CHANGED | ✅ | API-047, API-043, API-091, API-114, API-084 | ROLE-001, ROLE-002 |
| SCREEN-008 | 영상 처리 현황 화면 | CHANGED | ✅ | API-042, API-047, API-070, API-071, API-181 | ROLE-001, ROLE-002 |
| SCREEN-009 | 영상 상세 화면 | CHANGED | ✅ | API-021, API-043, API-044 | ROLE-001, ROLE-002 |
| SCREEN-010 | 로드 버전 선택 | CHANGED | ✅ | API-197, API-182, API-195 | ROLE-001, ROLE-002 |
| SCREEN-011 | 대시보드 화면 | CHANGED | ✅ | API-042, API-055, API-072 | ROLE-001, ROLE-002 |
| SCREEN-025 | 시스템 설정 화면 | CHANGED | ✅ | API-068, API-069, API-090, API-194 | ROLE-001 |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 115 |
| app_shell + nav | _shared/shell-nav.md | 2 |
| api_endpoint | _shared/api/ | 63 |
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
| 1 | SCREEN-005 — 라벨링 캔버스 화면 | screens/SCREEN-005/SCREEN-005.md | wireframe.html | uc/ | ac/ |
| 2 | SCREEN-006 — 마킹 화면 | screens/SCREEN-006/SCREEN-006.md | wireframe.html | uc/ | ac/ |
| 3 | SCREEN-008 — 영상 처리 현황 화면 | screens/SCREEN-008/SCREEN-008.md | wireframe.html | uc/ | ac/ |
| 4 | SCREEN-009 — 영상 상세 화면 | screens/SCREEN-009/SCREEN-009.md | wireframe.html | uc/ | ac/ |
| 5 | SCREEN-010 — 로드 버전 선택 | screens/SCREEN-010/SCREEN-010.md | wireframe.html | uc/ | ac/ |
| 6 | SCREEN-011 — 대시보드 화면 | screens/SCREEN-011/SCREEN-011.md | wireframe.html | uc/ | ac/ |
| 7 | SCREEN-025 — 시스템 설정 화면 | screens/SCREEN-025/SCREEN-025.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| API-043 | api_endpoint | CHANGED (v9→v10) |
| API-178 | api_endpoint | CHANGED (v3→v4) |
| SD-002 | screen_design | CHANGED (v5→v7) |
| SD-004 | screen_design | CHANGED (v4→v6) |
| SD-012 | screen_design | NEW |
| SD-013 | screen_design | NEW |
| SD-014 | screen_design | NEW |
| SD-015 | screen_design | NEW |
| SCREEN-005 | screen_spec | CHANGED (v62→v63) |
| SCREEN-006 | screen_spec | CHANGED (v32→v34) |
| SCREEN-008 | screen_spec | CHANGED (v31→v32) |
| SCREEN-009 | screen_spec | CHANGED (v39→v43) |
| SCREEN-010 | screen_spec | CHANGED (v32→v33) |
| SCREEN-011 | screen_spec | CHANGED (v15→v16) |
| SCREEN-025 | screen_spec | CHANGED (v23→v24) |
| UI-018 | ui_component | CHANGED (v6→v7) |
| UI-101 | ui_component | NEW |
| UI-102 | ui_component | NEW |
| UI-103 | ui_component | NEW |
| UI-104 | ui_component | NEW |
| UI-105 | ui_component | NEW |
| UI-106 | ui_component | NEW |
| UI-107 | ui_component | NEW |
| UI-108 | ui_component | NEW |
| UI-109 | ui_component | NEW |
| UI-110 | ui_component | NEW |
| UI-111 | ui_component | NEW |
| UI-112 | ui_component | NEW |
| UI-113 | ui_component | NEW |
| UI-114 | ui_component | NEW |
| UI-115 | ui_component | NEW |

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
