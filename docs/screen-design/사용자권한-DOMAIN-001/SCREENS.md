# 사용자·권한 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-001 사용자·권한 |
| last sync | 2026-09-07T15:22:10.755Z (session 17) |
| 화면 수 | 5개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | docs/screen-design/사용자권한-DOMAIN-001 |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-001]] | 세션 인계 진입 화면 | UNCHANGED | ✅ |  |  |
| [[SCREEN-002]] | 관리자 등록 화면 | UNCHANGED | ✅ | [[API-007]], [[API-245]] |  |
| [[SCREEN-003]] | 접근 거부 화면 | UNCHANGED | ✅ |  |  |
| [[SCREEN-004]] | 개발용 로그인 화면 | UNCHANGED | ✅ | [[API-153]] |  |
| [[SCREEN-024]] | 사용자 관리 화면 | UNCHANGED | ✅ | [[API-001]], [[API-004]], [[API-194]] | [[ROLE-004]] |

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
| 1 | [[SCREEN-001]] — 세션 인계 진입 화면 | screens/SCREEN-001/SCREEN-001.md | wireframe.html | uc/ | ac/ |
| 2 | [[SCREEN-002]] — 관리자 등록 화면 | screens/SCREEN-002/SCREEN-002.md | wireframe.html | uc/ | ac/ |
| 3 | [[SCREEN-003]] — 접근 거부 화면 | screens/SCREEN-003/SCREEN-003.md | wireframe.html | uc/ | ac/ |
| 4 | [[SCREEN-004]] — 개발용 로그인 화면 | screens/SCREEN-004/SCREEN-004.md | wireframe.html | uc/ | ac/ |
| 5 | [[SCREEN-024]] — 사용자 관리 화면 | screens/SCREEN-024/SCREEN-024.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[AC-1098]] | acceptance | NEW |
| [[API-245]] | api_endpoint | NEW |

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

> ⚠️ 여러 화면이 공유하는 UC/AC 4건은 화면 폴더마다 같은 파일명으로
> 복제돼 있어 wikilink 가 어느 사본을 가리킬지 모호하다(구현엔 영향 없음):
> - UC-041 — SCREEN-001, SCREEN-002, SCREEN-003
> - AC-1016 — SCREEN-001, SCREEN-002, SCREEN-003
> - AC-1017 — SCREEN-001, SCREEN-002, SCREEN-003
> - AC-1098 — SCREEN-001, SCREEN-002, SCREEN-003

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
