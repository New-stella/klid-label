# 학습데이터 저작도구 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-000 학습데이터 저작도구 |
| last sync | 2026-08-10T12:08:03.894Z (session 1) |
| 화면 수 | 2개 |
| ui_component 카탈로그 | populated 100건 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/issue-0810/docs/screen-design/klid-authoring-screens |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| SCREEN-005 | 라벨링 캔버스 화면 | NEW | ✅ | API-018, API-019, API-020, API-021, API-024, API-032, API-034, API-035, API-036, API-066, API-067, API-102, API-103, API-104, API-105, API-123, API-124, API-125, API-126, API-127, API-128, API-129, API-132, API-134, API-133, API-135, API-093, API-182, API-012, API-178, API-022, API-023, API-168, API-170, API-172, API-173, API-183, API-184, API-177 | ROLE-001, ROLE-002 |
| SCREEN-010 | 라벨 이력 화면 | NEW | ✅ | API-034, API-035, API-036, API-176 | ROLE-001, ROLE-002 |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 100 |
| app_shell + nav | _shared/shell-nav.md | 2 |
| api_endpoint | _shared/api/ | 39 |
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
| 2 | SCREEN-010 — 라벨 이력 화면 | screens/SCREEN-010/SCREEN-010.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| API-012 | api_endpoint | NEW |
| API-018 | api_endpoint | NEW |
| API-019 | api_endpoint | NEW |
| API-020 | api_endpoint | NEW |
| API-021 | api_endpoint | NEW |
| API-022 | api_endpoint | NEW |
| API-023 | api_endpoint | NEW |
| API-024 | api_endpoint | NEW |
| API-032 | api_endpoint | NEW |
| API-034 | api_endpoint | NEW |
| API-035 | api_endpoint | NEW |
| API-036 | api_endpoint | NEW |
| API-066 | api_endpoint | NEW |
| API-067 | api_endpoint | NEW |
| API-093 | api_endpoint | NEW |
| API-102 | api_endpoint | NEW |
| API-103 | api_endpoint | NEW |
| API-104 | api_endpoint | NEW |
| API-105 | api_endpoint | NEW |
| API-123 | api_endpoint | NEW |
| API-124 | api_endpoint | NEW |
| API-125 | api_endpoint | NEW |
| API-126 | api_endpoint | NEW |
| API-127 | api_endpoint | NEW |
| API-128 | api_endpoint | NEW |
| API-129 | api_endpoint | NEW |
| API-132 | api_endpoint | NEW |
| API-133 | api_endpoint | NEW |
| API-134 | api_endpoint | NEW |
| API-135 | api_endpoint | NEW |
| API-168 | api_endpoint | NEW |
| API-170 | api_endpoint | NEW |
| API-172 | api_endpoint | NEW |
| API-173 | api_endpoint | NEW |
| API-178 | api_endpoint | NEW |
| API-182 | api_endpoint | NEW |
| API-183 | api_endpoint | NEW |
| API-184 | api_endpoint | NEW |
| SCREEN-005 | screen_spec | NEW |
| SCREEN-010 | screen_spec | NEW |
| AC-004 | acceptance | NEW |
| AC-005 | acceptance | NEW |
| AC-006 | acceptance | NEW |
| AC-007 | acceptance | NEW |
| AC-008 | acceptance | NEW |
| AC-017 | acceptance | NEW |
| AC-020 | acceptance | NEW |
| AC-021 | acceptance | NEW |
| AC-023 | acceptance | NEW |
| AC-024 | acceptance | NEW |
| API-177 | api_endpoint | NEW |
| SHELL-001 | app_shell | NEW |
| DS-001 | design_system | NEW |
| NAV-001 | navigation_tree | NEW |
| ROLE-001 | permission_role | NEW |
| ROLE-002 | permission_role | NEW |
| UI-002 | ui_component | NEW |
| UI-008 | ui_component | NEW |
| UI-010 | ui_component | NEW |
| UI-014 | ui_component | NEW |
| UI-016 | ui_component | NEW |
| UI-017 | ui_component | NEW |
| UI-018 | ui_component | NEW |
| UI-024 | ui_component | NEW |
| UI-026 | ui_component | NEW |
| UI-030 | ui_component | NEW |
| UI-045 | ui_component | NEW |
| UI-046 | ui_component | NEW |
| UI-053 | ui_component | NEW |
| UI-055 | ui_component | NEW |
| UI-057 | ui_component | NEW |
| UI-091 | ui_component | NEW |
| UI-100 | ui_component | NEW |
| UC-004 | use_case | NEW |
| UC-005 | use_case | NEW |
| UC-006 | use_case | NEW |
| UC-007 | use_case | NEW |
| UC-008 | use_case | NEW |
| UC-021 | use_case | NEW |
| UC-022 | use_case | NEW |
| UI-003 | ui_component | NEW |
| UI-007 | ui_component | NEW |
| UI-011 | ui_component | NEW |
| UI-015 | ui_component | NEW |
| UI-019 | ui_component | NEW |
| UI-020 | ui_component | NEW |
| UI-021 | ui_component | NEW |
| UI-022 | ui_component | NEW |
| UI-023 | ui_component | NEW |
| UI-027 | ui_component | NEW |
| UI-028 | ui_component | NEW |
| UI-029 | ui_component | NEW |
| UI-032 | ui_component | NEW |
| UI-033 | ui_component | NEW |
| UI-034 | ui_component | NEW |
| UI-035 | ui_component | NEW |
| UI-036 | ui_component | NEW |
| UI-037 | ui_component | NEW |
| UI-038 | ui_component | NEW |
| UI-039 | ui_component | NEW |
| UI-040 | ui_component | NEW |
| UI-041 | ui_component | NEW |
| UI-047 | ui_component | NEW |
| UI-048 | ui_component | NEW |
| UI-049 | ui_component | NEW |
| UI-050 | ui_component | NEW |
| UI-052 | ui_component | NEW |
| UI-056 | ui_component | NEW |
| UI-064 | ui_component | NEW |
| UI-074 | ui_component | NEW |
| UI-075 | ui_component | NEW |
| UI-080 | ui_component | NEW |
| UI-083 | ui_component | NEW |
| UI-084 | ui_component | NEW |
| UI-093 | ui_component | NEW |
| UI-095 | ui_component | NEW |
| UI-096 | ui_component | NEW |
| UI-097 | ui_component | NEW |
| UI-098 | ui_component | NEW |
| UI-099 | ui_component | NEW |
| UI-001 | ui_component | NEW |
| UI-004 | ui_component | NEW |
| UI-005 | ui_component | NEW |
| UI-006 | ui_component | NEW |
| UI-009 | ui_component | NEW |
| UI-012 | ui_component | NEW |
| UI-013 | ui_component | NEW |
| UI-025 | ui_component | NEW |
| UI-031 | ui_component | NEW |
| UI-042 | ui_component | NEW |
| UI-043 | ui_component | NEW |
| UI-044 | ui_component | NEW |
| UI-051 | ui_component | NEW |
| UI-054 | ui_component | NEW |
| UI-058 | ui_component | NEW |
| UI-059 | ui_component | NEW |
| UI-060 | ui_component | NEW |
| UI-061 | ui_component | NEW |
| UI-062 | ui_component | NEW |
| UI-063 | ui_component | NEW |
| UI-065 | ui_component | NEW |
| UI-068 | ui_component | NEW |
| UI-070 | ui_component | NEW |
| UI-071 | ui_component | NEW |
| UI-072 | ui_component | NEW |
| UI-073 | ui_component | NEW |
| UI-076 | ui_component | NEW |
| UI-077 | ui_component | NEW |
| UI-078 | ui_component | NEW |
| UI-079 | ui_component | NEW |
| UI-081 | ui_component | NEW |
| UI-082 | ui_component | NEW |
| UI-085 | ui_component | NEW |
| UI-086 | ui_component | NEW |
| UI-087 | ui_component | NEW |
| UI-088 | ui_component | NEW |
| UI-089 | ui_component | NEW |
| UI-090 | ui_component | NEW |
| UI-092 | ui_component | NEW |
| UI-094 | ui_component | NEW |
| UI-066 | ui_component | NEW |
| UI-067 | ui_component | NEW |
| UI-069 | ui_component | NEW |

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
