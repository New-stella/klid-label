# 작업 배정 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-015 작업 배정 |
| last sync | 2026-09-14T07:37:27.585Z (session 19) |
| 화면 수 | 1개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | /Users/ck/Documents/workspace/klid/klid-label/docs/screen-design/작업-배정-DOMAIN-015 |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-012]] | 작업 목록 화면 | UNCHANGED | ✅ | [[API-001]], [[API-002]], [[API-070]], [[API-071]], [[API-072]], [[API-073]], [[API-136]], [[API-137]], [[API-116]], [[API-187]] | [[ROLE-001]], [[ROLE-002]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 144 |
| app_shell + nav | _shared/shell-nav.md | 2 |
| api_endpoint | _shared/api/ | 10 |
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
| 1 | [[SCREEN-012]] — 작업 목록 화면 | screens/SCREEN-012/SCREEN-012.md | wireframe.html | uc/ | ac/ |

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
