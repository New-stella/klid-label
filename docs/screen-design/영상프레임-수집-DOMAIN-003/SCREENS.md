# 영상·프레임 수집 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-003 영상·프레임 수집 |
| last sync | 2026-09-08T00:21:16.809Z (session 18) |
| 화면 수 | 1개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | docs/screen-design/영상프레임-수집-DOMAIN-003 |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-009]] | 영상 상세 화면 | CHANGED | ✅ | [[API-021]], [[API-043]], [[API-044]], [[API-167]], [[API-198]], [[API-201]] | [[ROLE-001]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 144 |
| app_shell + nav | _shared/shell-nav.md | 2 |
| api_endpoint | _shared/api/ | 8 |
| constant | _shared/constant/ | 0 |
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
| 1 | [[SCREEN-009]] — 영상 상세 화면 | screens/SCREEN-009/SCREEN-009.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[API-043]] | api_endpoint | CHANGED (v25→v27) |
| [[API-112]] | api_endpoint | CHANGED (v5→v6) |
| [[API-167]] | api_endpoint | NEW |
| [[API-201]] | api_endpoint | CHANGED (v8→v9) |
| [[SHELL-001]] | app_shell | CHANGED (v11→v12) |
| [[ROLE-001]] | permission_role | CHANGED (v13→v14) |
| [[ROLE-003]] | permission_role | NEW |
| [[ROLE-004]] | permission_role | CHANGED (v4→v5) |
| [[SCREEN-009]] | screen_spec | CHANGED (v75→v76) |
| [[UI-035]] | ui_component | CHANGED (v6→v8) |
| [[UI-037]] | ui_component | CHANGED (v4→v6) |
| [[UI-043]] | ui_component | CHANGED (v4→v5) |
| [[UI-046]] | ui_component | CHANGED (v7→v8) |
| [[UI-071]] | ui_component | CHANGED (v4→v5) |
| [[UI-072]] | ui_component | CHANGED (v4→v5) |
| [[UI-096]] | ui_component | CHANGED (v5→v6) |
| [[UI-105]] | ui_component | CHANGED (v1→v3) |
| [[UI-129]] | ui_component | CHANGED (v1→v4) |
| [[UI-131]] | ui_component | CHANGED (v1→v2) |
| [[UI-132]] | ui_component | CHANGED (v1→v2) |
| [[UI-134]] | ui_component | CHANGED (v1→v2) |
| [[UI-138]] | ui_component | CHANGED (v1→v2) |
| [[UI-139]] | ui_component | CHANGED (v1→v2) |
| [[UI-140]] | ui_component | CHANGED (v1→v2) |
| [[UC-011]] | use_case | CHANGED (v12→v21) |
| [[UC-016]] | use_case | CHANGED (v24→v31) |

## RETIRED (_retired/ 이동)

- [[AC-011]] — 코드 제거 검토
- [[AC-016]] — 코드 제거 검토
- [[AC-019]] — 코드 제거 검토
- [[AC-023]] — 코드 제거 검토
- [[AC-051]] — 코드 제거 검토

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
