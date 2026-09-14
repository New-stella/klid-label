# 라벨링 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-010 라벨링 |
| last sync | 2026-09-14T05:34:14.125Z (session 18) |
| 화면 수 | 2개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | docs/screen-design/라벨링-DOMAIN-010 |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-005]] | 라벨링 캔버스 화면 | CHANGED | ✅ | [[API-018]], [[API-019]], [[API-020]], [[API-021]], [[API-024]], [[API-032]], [[API-066]], [[API-067]], [[API-102]], [[API-103]], [[API-104]], [[API-105]], [[API-123]], [[API-124]], [[API-125]], [[API-126]], [[API-127]], [[API-128]], [[API-129]], [[API-132]], [[API-134]], [[API-133]], [[API-135]], [[API-093]], [[API-182]], [[API-012]], [[API-178]], [[API-022]], [[API-023]], [[API-168]], [[API-170]], [[API-172]], [[API-173]], [[API-183]], [[API-184]], [[API-177]], [[API-195]], [[API-196]], [[API-197]], [[API-034]], [[API-035]], [[API-036]], [[API-193]], [[API-204]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-026]] | 프리셋 관리 화면 | CHANGED | ✅ | [[API-037]], [[API-038]], [[API-039]], [[API-040]], [[API-185]] | [[ROLE-001]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 144 |
| app_shell + nav | _shared/shell-nav.md | 2 |
| api_endpoint | _shared/api/ | 51 |
| constant | _shared/constant/ | 2 |
| permission_role | _shared/role/ | 4 |
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
| 1 | [[SCREEN-005]] — 라벨링 캔버스 화면 | screens/SCREEN-005/SCREEN-005.md | wireframe.html | uc/ | ac/ |
| 2 | [[SCREEN-026]] — 프리셋 관리 화면 | screens/SCREEN-026/SCREEN-026.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[AC-1061]] | acceptance | CHANGED (v6→v7) |
| [[API-032]] | api_endpoint | CHANGED (v8→v10) |
| [[API-102]] | api_endpoint | CHANGED (v14→v15) |
| [[API-104]] | api_endpoint | CHANGED (v14→v15) |
| [[API-132]] | api_endpoint | CHANGED (v5→v7) |
| [[API-134]] | api_endpoint | CHANGED (v5→v7) |
| [[SHELL-001]] | app_shell | CHANGED (v11→v18) |
| [[ROLE-002]] | permission_role | CHANGED (v9→v10) |
| [[ROLE-003]] | permission_role | CHANGED (v14→v15) |
| [[SD-006]] | screen_design | CHANGED (v5→v9) |
| [[SCREEN-005]] | screen_spec | CHANGED (v102→v106) |
| [[SCREEN-026]] | screen_spec | CHANGED (v35→v41) |
| [[UI-035]] | ui_component | CHANGED (v7→v8) |
| [[UI-043]] | ui_component | CHANGED (v4→v5) |
| [[UC-004]] | use_case | CHANGED (v18→v19) |
| [[UC-005]] | use_case | CHANGED (v13→v14) |
| [[UC-006]] | use_case | CHANGED (v11→v12) |
| [[UC-007]] | use_case | CHANGED (v15→v16) |
| [[UC-008]] | use_case | CHANGED (v17→v18) |
| [[UC-021]] | use_case | CHANGED (v22→v25) |
| [[UC-022]] | use_case | CHANGED (v24→v30) |
| [[UC-032]] | use_case | CHANGED (v14→v16) |

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
