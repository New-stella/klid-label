# 검수 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-005 검수 |
| last sync | 2026-08-16T12:51:39.492Z (session 6) |
| 화면 수 | 2개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/domain-check/docs/screen-design/검수-DOMAIN-005 |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-018]] | 검수 목록 화면 | CHANGED | ✅ | [[API-008]], [[API-138]] | [[ROLE-001]] |
| [[SCREEN-019]] | 검수 상세 화면 | CHANGED | ✅ | [[API-009]], [[API-010]], [[API-011]], [[API-013]], [[API-014]], [[API-015]], [[API-021]], [[API-132]], [[API-066]], [[API-102]], [[API-103]], [[API-104]], [[API-105]] | [[ROLE-001]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 144 |
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
| 1 | [[SCREEN-018]] — 검수 목록 화면 | screens/SCREEN-018/SCREEN-018.md | wireframe.html | uc/ | ac/ |
| 2 | [[SCREEN-019]] — 검수 상세 화면 | screens/SCREEN-019/SCREEN-019.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[AC-009]] | acceptance | CHANGED (v12→v15) |
| [[AC-022]] | acceptance | CHANGED (v6→v7) |
| [[API-008]] | api_endpoint | CHANGED (v8→v11) |
| [[API-009]] | api_endpoint | CHANGED (v5→v8) |
| [[API-010]] | api_endpoint | CHANGED (v3→v4) |
| [[API-011]] | api_endpoint | CHANGED (v3→v4) |
| [[API-013]] | api_endpoint | CHANGED (v4→v6) |
| [[API-014]] | api_endpoint | CHANGED (v8→v9) |
| [[API-015]] | api_endpoint | CHANGED (v4→v7) |
| [[API-021]] | api_endpoint | CHANGED (v4→v7) |
| [[API-066]] | api_endpoint | CHANGED (v3→v4) |
| [[API-102]] | api_endpoint | CHANGED (v8→v13) |
| [[API-103]] | api_endpoint | CHANGED (v5→v9) |
| [[API-104]] | api_endpoint | CHANGED (v5→v12) |
| [[API-105]] | api_endpoint | CHANGED (v3→v7) |
| [[API-132]] | api_endpoint | CHANGED (v1→v2) |
| [[API-138]] | api_endpoint | CHANGED (v2→v4) |
| [[ROLE-001]] | permission_role | CHANGED (v8→v10) |
| [[SCREEN-018]] | screen_spec | CHANGED (v23→v24) |
| [[SCREEN-019]] | screen_spec | CHANGED (v28→v29) |
| [[UC-009]] | use_case | CHANGED (v16→v19) |
| [[UC-023]] | use_case | CHANGED (v19→v22) |

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

> ⚠️ 여러 화면이 공유하는 UC/AC 2건은 화면 폴더마다 같은 파일명으로
> 복제돼 있어 wikilink 가 어느 사본을 가리킬지 모호하다(구현엔 영향 없음):
> - UC-023 — SCREEN-018, SCREEN-019
> - AC-022 — SCREEN-018, SCREEN-019

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
