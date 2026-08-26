# 게시판·공지 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-009 게시판·공지 |
| last sync | 2026-08-26T05:08:41.839Z (session 13) |
| 화면 수 | 4개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/portal/docs/screen-design/게시판공지-DOMAIN-009 |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-030]] | 공지 목록 화면 | NEW | ✅ | [[API-095]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-031]] | 공지 상세 화면 | NEW | ✅ | [[API-096]], [[API-099]], [[API-100]], [[API-101]], [[API-107]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-036]] | 공지 작성 화면 | NEW | ✅ | [[API-097]] | [[ROLE-001]] |
| [[SCREEN-037]] | 공지 수정 화면 | NEW | ✅ | [[API-096]], [[API-098]], [[API-106]], [[API-108]] | [[ROLE-001]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 144 |
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
| 1 | [[SCREEN-030]] — 공지 목록 화면 | screens/SCREEN-030/SCREEN-030.md | wireframe.html | uc/ | ac/ |
| 2 | [[SCREEN-031]] — 공지 상세 화면 | screens/SCREEN-031/SCREEN-031.md | wireframe.html | uc/ | ac/ |
| 3 | [[SCREEN-036]] — 공지 작성 화면 | screens/SCREEN-036/SCREEN-036.md | wireframe.html | uc/ | ac/ |
| 4 | [[SCREEN-037]] — 공지 수정 화면 | screens/SCREEN-037/SCREEN-037.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[API-095]] | api_endpoint | NEW |
| [[API-096]] | api_endpoint | NEW |
| [[API-097]] | api_endpoint | NEW |
| [[API-098]] | api_endpoint | NEW |
| [[API-099]] | api_endpoint | NEW |
| [[API-100]] | api_endpoint | NEW |
| [[API-101]] | api_endpoint | NEW |
| [[API-106]] | api_endpoint | NEW |
| [[API-107]] | api_endpoint | NEW |
| [[API-108]] | api_endpoint | NEW |
| [[SHELL-001]] | app_shell | NEW |
| [[DS-001]] | design_system | NEW |
| [[NAV-001]] | navigation_tree | NEW |
| [[ROLE-001]] | permission_role | NEW |
| [[ROLE-002]] | permission_role | NEW |
| [[SD-007]] | screen_design | NEW |
| [[SD-008]] | screen_design | NEW |
| [[SD-010]] | screen_design | NEW |
| [[SD-011]] | screen_design | NEW |
| [[SCREEN-030]] | screen_spec | NEW |
| [[SCREEN-031]] | screen_spec | NEW |
| [[SCREEN-036]] | screen_spec | NEW |
| [[SCREEN-037]] | screen_spec | NEW |
| [[UI-001]] | ui_component | NEW |
| [[UI-002]] | ui_component | NEW |
| [[UI-003]] | ui_component | NEW |
| [[UI-004]] | ui_component | NEW |
| [[UI-005]] | ui_component | NEW |
| [[UI-006]] | ui_component | NEW |
| [[UI-007]] | ui_component | NEW |
| [[UI-008]] | ui_component | NEW |
| [[UI-009]] | ui_component | NEW |
| [[UI-010]] | ui_component | NEW |
| [[UI-011]] | ui_component | NEW |
| [[UI-012]] | ui_component | NEW |
| [[UI-013]] | ui_component | NEW |
| [[UI-014]] | ui_component | NEW |
| [[UI-015]] | ui_component | NEW |
| [[UI-016]] | ui_component | NEW |
| [[UI-017]] | ui_component | NEW |
| [[UI-018]] | ui_component | NEW |
| [[UI-019]] | ui_component | NEW |
| [[UI-020]] | ui_component | NEW |
| [[UI-021]] | ui_component | NEW |
| [[UI-022]] | ui_component | NEW |
| [[UI-023]] | ui_component | NEW |
| [[UI-024]] | ui_component | NEW |
| [[UI-025]] | ui_component | NEW |
| [[UI-026]] | ui_component | NEW |
| [[UI-027]] | ui_component | NEW |
| [[UI-028]] | ui_component | NEW |
| [[UI-029]] | ui_component | NEW |
| [[UI-030]] | ui_component | NEW |
| [[UI-031]] | ui_component | NEW |
| [[UI-032]] | ui_component | NEW |
| [[UI-033]] | ui_component | NEW |
| [[UI-034]] | ui_component | NEW |
| [[UI-035]] | ui_component | NEW |
| [[UI-036]] | ui_component | NEW |
| [[UI-037]] | ui_component | NEW |
| [[UI-038]] | ui_component | NEW |
| [[UI-039]] | ui_component | NEW |
| [[UI-040]] | ui_component | NEW |
| [[UI-041]] | ui_component | NEW |
| [[UI-042]] | ui_component | NEW |
| [[UI-043]] | ui_component | NEW |
| [[UI-044]] | ui_component | NEW |
| [[UI-045]] | ui_component | NEW |
| [[UI-046]] | ui_component | NEW |
| [[UI-047]] | ui_component | NEW |
| [[UI-048]] | ui_component | NEW |
| [[UI-049]] | ui_component | NEW |
| [[UI-050]] | ui_component | NEW |
| [[UI-051]] | ui_component | NEW |
| [[UI-052]] | ui_component | NEW |
| [[UI-053]] | ui_component | NEW |
| [[UI-054]] | ui_component | NEW |
| [[UI-055]] | ui_component | NEW |
| [[UI-056]] | ui_component | NEW |
| [[UI-057]] | ui_component | NEW |
| [[UI-058]] | ui_component | NEW |
| [[UI-059]] | ui_component | NEW |
| [[UI-060]] | ui_component | NEW |
| [[UI-061]] | ui_component | NEW |
| [[UI-062]] | ui_component | NEW |
| [[UI-063]] | ui_component | NEW |
| [[UI-064]] | ui_component | NEW |
| [[UI-065]] | ui_component | NEW |
| [[UI-066]] | ui_component | NEW |
| [[UI-067]] | ui_component | NEW |
| [[UI-068]] | ui_component | NEW |
| [[UI-069]] | ui_component | NEW |
| [[UI-070]] | ui_component | NEW |
| [[UI-071]] | ui_component | NEW |
| [[UI-072]] | ui_component | NEW |
| [[UI-073]] | ui_component | NEW |
| [[UI-074]] | ui_component | NEW |
| [[UI-075]] | ui_component | NEW |
| [[UI-076]] | ui_component | NEW |
| [[UI-077]] | ui_component | NEW |
| [[UI-078]] | ui_component | NEW |
| [[UI-079]] | ui_component | NEW |
| [[UI-080]] | ui_component | NEW |
| [[UI-081]] | ui_component | NEW |
| [[UI-082]] | ui_component | NEW |
| [[UI-083]] | ui_component | NEW |
| [[UI-084]] | ui_component | NEW |
| [[UI-085]] | ui_component | NEW |
| [[UI-086]] | ui_component | NEW |
| [[UI-087]] | ui_component | NEW |
| [[UI-088]] | ui_component | NEW |
| [[UI-089]] | ui_component | NEW |
| [[UI-090]] | ui_component | NEW |
| [[UI-091]] | ui_component | NEW |
| [[UI-092]] | ui_component | NEW |
| [[UI-093]] | ui_component | NEW |
| [[UI-094]] | ui_component | NEW |
| [[UI-095]] | ui_component | NEW |
| [[UI-096]] | ui_component | NEW |
| [[UI-097]] | ui_component | NEW |
| [[UI-098]] | ui_component | NEW |
| [[UI-099]] | ui_component | NEW |
| [[UI-100]] | ui_component | NEW |
| [[UI-101]] | ui_component | NEW |
| [[UI-102]] | ui_component | NEW |
| [[UI-103]] | ui_component | NEW |
| [[UI-104]] | ui_component | NEW |
| [[UI-105]] | ui_component | NEW |
| [[UI-106]] | ui_component | NEW |
| [[UI-107]] | ui_component | NEW |
| [[UI-108]] | ui_component | NEW |
| [[UI-109]] | ui_component | NEW |
| [[UI-110]] | ui_component | NEW |
| [[UI-111]] | ui_component | NEW |
| [[UI-112]] | ui_component | NEW |
| [[UI-113]] | ui_component | NEW |
| [[UI-114]] | ui_component | NEW |
| [[UI-115]] | ui_component | NEW |
| [[UI-116]] | ui_component | NEW |
| [[UI-117]] | ui_component | NEW |
| [[UI-118]] | ui_component | NEW |
| [[UI-119]] | ui_component | NEW |
| [[UI-120]] | ui_component | NEW |
| [[UI-121]] | ui_component | NEW |
| [[UI-122]] | ui_component | NEW |
| [[UI-123]] | ui_component | NEW |
| [[UI-124]] | ui_component | NEW |
| [[UI-125]] | ui_component | NEW |
| [[UI-126]] | ui_component | NEW |
| [[UI-127]] | ui_component | NEW |
| [[UI-128]] | ui_component | NEW |
| [[UI-129]] | ui_component | NEW |
| [[UI-130]] | ui_component | NEW |
| [[UI-131]] | ui_component | NEW |
| [[UI-132]] | ui_component | NEW |
| [[UI-133]] | ui_component | NEW |
| [[UI-134]] | ui_component | NEW |
| [[UI-135]] | ui_component | NEW |
| [[UI-136]] | ui_component | NEW |
| [[UI-137]] | ui_component | NEW |
| [[UI-138]] | ui_component | NEW |
| [[UI-139]] | ui_component | NEW |
| [[UI-140]] | ui_component | NEW |
| [[UI-141]] | ui_component | NEW |
| [[UI-142]] | ui_component | NEW |
| [[UI-143]] | ui_component | NEW |
| [[UI-144]] | ui_component | NEW |

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
