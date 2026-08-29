# 저작도구 화면 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | DOMAIN-000 저작도구 화면 |
| last sync | 2026-08-29T01:27:56.588Z (session 37) |
| 화면 수 | 37개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | docs/screen-design/klid-authoring-screens/ |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-001]] | 세션 인계 진입 화면 | UNCHANGED | ✅ |  |  |
| [[SCREEN-002]] | 관리자 등록 화면 | UNCHANGED | ✅ | [[API-007]] |  |
| [[SCREEN-003]] | 접근 거부 화면 | UNCHANGED | ✅ |  |  |
| [[SCREEN-004]] | 개발용 로그인 화면 | UNCHANGED | ✅ | [[API-153]] |  |
| [[SCREEN-005]] | 라벨링 캔버스 화면 | UNCHANGED | ✅ | [[API-018]], [[API-019]], [[API-020]], [[API-021]], [[API-024]], [[API-032]], [[API-066]], [[API-067]], [[API-102]], [[API-103]], [[API-104]], [[API-105]], [[API-123]], [[API-124]], [[API-125]], [[API-126]], [[API-127]], [[API-128]], [[API-129]], [[API-132]], [[API-134]], [[API-133]], [[API-135]], [[API-093]], [[API-182]], [[API-012]], [[API-178]], [[API-022]], [[API-023]], [[API-168]], [[API-170]], [[API-172]], [[API-173]], [[API-183]], [[API-184]], [[API-177]], [[API-195]], [[API-196]], [[API-197]], [[API-034]], [[API-035]], [[API-036]], [[API-193]], [[API-204]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-006]] | 마킹 화면 | UNCHANGED | ✅ | [[API-047]], [[API-043]], [[API-091]], [[API-114]], [[API-084]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-008]] | 영상 처리 현황 화면 | UNCHANGED | ✅ | [[API-042]], [[API-047]], [[API-068]], [[API-070]], [[API-071]], [[API-181]], [[API-212]], [[API-214]] | [[ROLE-001]] |
| [[SCREEN-009]] | 영상 상세 화면 | UNCHANGED | ✅ | [[API-021]], [[API-043]], [[API-044]], [[API-167]], [[API-198]], [[API-201]] | [[ROLE-001]] |
| [[SCREEN-010]] | 로드 버전 선택 | UNCHANGED | ✅ | [[API-197]], [[API-182]], [[API-195]], [[API-034]], [[API-035]], [[API-036]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-011]] | 대시보드 화면 | CHANGED | ✅ | [[API-042]], [[API-055]], [[API-072]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-012]] | 작업 목록 화면 | CHANGED | ✅ | [[API-001]], [[API-002]], [[API-070]], [[API-071]], [[API-072]], [[API-073]], [[API-136]], [[API-137]], [[API-116]], [[API-187]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-018]] | 검수 목록 화면 | CHANGED | ✅ | [[API-008]], [[API-138]] | [[ROLE-001]] |
| [[SCREEN-019]] | 검수 상세 화면 | UNCHANGED | ✅ | [[API-009]], [[API-010]], [[API-011]], [[API-013]], [[API-014]], [[API-015]], [[API-021]], [[API-132]], [[API-066]], [[API-102]], [[API-103]], [[API-104]], [[API-105]], [[API-168]], [[API-183]], [[API-128]], [[API-172]] | [[ROLE-001]] |
| [[SCREEN-020]] | 작업자 통계 화면 | UNCHANGED | ✅ | [[API-001]], [[API-056]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-021]] | 전체 구축 현황 화면 | CHANGED | ✅ | [[API-057]], [[API-058]] | [[ROLE-001]] |
| [[SCREEN-022]] | 증강 요청 화면 | UNCHANGED | ✅ | [[API-042]], [[API-059]], [[API-060]], [[API-092]], [[API-179]] | [[ROLE-001]] |
| [[SCREEN-023]] | 증강 결과 화면 | UNCHANGED | ✅ | [[API-061]], [[API-062]], [[API-063]], [[API-188]], [[API-189]], [[API-190]], [[API-175]] | [[ROLE-001]] |
| [[SCREEN-024]] | 사용자 관리 화면 | UNCHANGED | ✅ | [[API-001]], [[API-004]], [[API-194]] | [[ROLE-004]] |
| [[SCREEN-025]] | 시스템 설정 화면 | UNCHANGED | ✅ | [[API-068]], [[API-069]], [[API-090]] | [[ROLE-001]] |
| [[SCREEN-026]] | 프리셋 관리 화면 | UNCHANGED | ✅ | [[API-037]], [[API-038]], [[API-039]], [[API-040]], [[API-185]] | [[ROLE-001]] |
| [[SCREEN-027]] | 파일 업로드 | UNCHANGED | ✅ | [[API-043]], [[API-152]], [[API-156]], [[API-158]], [[API-160]], [[API-162]], [[API-164]], [[API-216]], [[API-217]], [[API-218]], [[API-194]] | [[ROLE-004]] |
| [[SCREEN-028]] | 포털 홈 화면 | UNCHANGED | ✅ | [[API-115]], [[API-203]] | [[ROLE-003]] |
| [[SCREEN-029]] | 포털 라벨링 화면 | UNCHANGED | ✅ | [[API-024]], [[API-082]], [[API-110]], [[API-111]] | [[ROLE-003]] |
| [[SCREEN-030]] | 공지 목록 화면 | UNCHANGED | ✅ | [[API-095]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-031]] | 공지 상세 화면 | UNCHANGED | ✅ | [[API-096]], [[API-099]], [[API-100]], [[API-101]], [[API-107]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-032]] | 비식별 신고 관리 화면 | CHANGED | ✅ | [[API-094]], [[API-109]], [[API-202]] | [[ROLE-001]] |
| [[SCREEN-033]] | 포털 업로드 화면 | UNCHANGED | ✅ | [[API-139]], [[API-142]], [[API-151]], [[API-163]], [[API-166]], [[API-169]], [[API-171]], [[API-161]] | [[ROLE-003]] |
| [[SCREEN-034]] | 포털 업로드 라벨링 화면 | UNCHANGED | ✅ | [[API-140]], [[API-149]], [[API-154]], [[API-155]], [[API-157]], [[API-159]] | [[ROLE-003]] |
| [[SCREEN-035]] | 라벨 관리 화면 | UNCHANGED | ✅ | [[API-024]], [[API-025]], [[API-026]], [[API-027]], [[API-028]], [[API-029]], [[API-030]], [[API-031]] | [[ROLE-001]] |
| [[SCREEN-036]] | 공지 작성 화면 | UNCHANGED | ✅ | [[API-097]] | [[ROLE-001]] |
| [[SCREEN-037]] | 공지 수정 화면 | UNCHANGED | ✅ | [[API-096]], [[API-098]], [[API-106]], [[API-108]] | [[ROLE-001]] |
| [[SCREEN-038]] | 이벤트유형 관리 화면 | UNCHANGED | ✅ | [[API-185]], [[API-186]], [[API-219]], [[API-220]] | [[ROLE-001]] |
| [[SCREEN-039]] | 산출물 가져오기 | UNCHANGED | ✅ | [[API-205]], [[API-206]], [[API-207]], [[API-208]], [[API-209]], [[API-210]], [[API-211]], [[API-215]], [[API-221]], [[API-222]] | [[ROLE-004]] |
| [[SCREEN-040]] | 관리자 페이지 진입 화면 | UNCHANGED | ✅ | [[API-194]] | [[ROLE-004]] |
| [[SCREEN-041]] | 관리자 패스워드 교체 | UNCHANGED | ✅ | [[API-223]], [[API-194]] | [[ROLE-004]] |
| [[SCREEN-042]] | 연동 서버 주소 관리 화면 | UNCHANGED | ✅ | [[API-068]], [[API-069]], [[API-194]] | [[ROLE-004]] |
| [[SCREEN-043]] | 위험 작업 화면 | UNCHANGED | ✅ |  | [[ROLE-004]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 144 |
| app_shell + nav | _shared/shell-nav.md | 4 |
| api_endpoint | _shared/api/ | 202 |
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
| 1 | [[SCREEN-001]] — 세션 인계 진입 화면 | screens/SCREEN-001/SCREEN-001.md | wireframe.html | uc/ | ac/ |
| 2 | [[SCREEN-002]] — 관리자 등록 화면 | screens/SCREEN-002/SCREEN-002.md | wireframe.html | uc/ | ac/ |
| 3 | [[SCREEN-003]] — 접근 거부 화면 | screens/SCREEN-003/SCREEN-003.md | wireframe.html | uc/ | ac/ |
| 4 | [[SCREEN-004]] — 개발용 로그인 화면 | screens/SCREEN-004/SCREEN-004.md | wireframe.html | uc/ | ac/ |
| 5 | [[SCREEN-005]] — 라벨링 캔버스 화면 | screens/SCREEN-005/SCREEN-005.md | wireframe.html | uc/ | ac/ |
| 6 | [[SCREEN-006]] — 마킹 화면 | screens/SCREEN-006/SCREEN-006.md | wireframe.html | uc/ | ac/ |
| 7 | [[SCREEN-008]] — 영상 처리 현황 화면 | screens/SCREEN-008/SCREEN-008.md | wireframe.html | uc/ | ac/ |
| 8 | [[SCREEN-009]] — 영상 상세 화면 | screens/SCREEN-009/SCREEN-009.md | wireframe.html | uc/ | ac/ |
| 9 | [[SCREEN-010]] — 로드 버전 선택 | screens/SCREEN-010/SCREEN-010.md | wireframe.html | uc/ | ac/ |
| 10 | [[SCREEN-011]] — 대시보드 화면 | screens/SCREEN-011/SCREEN-011.md | wireframe.html | uc/ | ac/ |
| 11 | [[SCREEN-012]] — 작업 목록 화면 | screens/SCREEN-012/SCREEN-012.md | wireframe.html | uc/ | ac/ |
| 12 | [[SCREEN-018]] — 검수 목록 화면 | screens/SCREEN-018/SCREEN-018.md | wireframe.html | uc/ | ac/ |
| 13 | [[SCREEN-019]] — 검수 상세 화면 | screens/SCREEN-019/SCREEN-019.md | wireframe.html | uc/ | ac/ |
| 14 | [[SCREEN-020]] — 작업자 통계 화면 | screens/SCREEN-020/SCREEN-020.md | wireframe.html | uc/ | ac/ |
| 15 | [[SCREEN-021]] — 전체 구축 현황 화면 | screens/SCREEN-021/SCREEN-021.md | wireframe.html | uc/ | ac/ |
| 16 | [[SCREEN-022]] — 증강 요청 화면 | screens/SCREEN-022/SCREEN-022.md | wireframe.html | uc/ | ac/ |
| 17 | [[SCREEN-023]] — 증강 결과 화면 | screens/SCREEN-023/SCREEN-023.md | wireframe.html | uc/ | ac/ |
| 18 | [[SCREEN-024]] — 사용자 관리 화면 | screens/SCREEN-024/SCREEN-024.md | wireframe.html | uc/ | ac/ |
| 19 | [[SCREEN-025]] — 시스템 설정 화면 | screens/SCREEN-025/SCREEN-025.md | wireframe.html | uc/ | ac/ |
| 20 | [[SCREEN-026]] — 프리셋 관리 화면 | screens/SCREEN-026/SCREEN-026.md | wireframe.html | uc/ | ac/ |
| 21 | [[SCREEN-027]] — 파일 업로드 | screens/SCREEN-027/SCREEN-027.md | wireframe.html | uc/ | ac/ |
| 22 | [[SCREEN-028]] — 포털 홈 화면 | screens/SCREEN-028/SCREEN-028.md | wireframe.html | uc/ | ac/ |
| 23 | [[SCREEN-029]] — 포털 라벨링 화면 | screens/SCREEN-029/SCREEN-029.md | wireframe.html | uc/ | ac/ |
| 24 | [[SCREEN-030]] — 공지 목록 화면 | screens/SCREEN-030/SCREEN-030.md | wireframe.html | uc/ | ac/ |
| 25 | [[SCREEN-031]] — 공지 상세 화면 | screens/SCREEN-031/SCREEN-031.md | wireframe.html | uc/ | ac/ |
| 26 | [[SCREEN-032]] — 비식별 신고 관리 화면 | screens/SCREEN-032/SCREEN-032.md | wireframe.html | uc/ | ac/ |
| 27 | [[SCREEN-033]] — 포털 업로드 화면 | screens/SCREEN-033/SCREEN-033.md | wireframe.html | uc/ | ac/ |
| 28 | [[SCREEN-034]] — 포털 업로드 라벨링 화면 | screens/SCREEN-034/SCREEN-034.md | wireframe.html | uc/ | ac/ |
| 29 | [[SCREEN-035]] — 라벨 관리 화면 | screens/SCREEN-035/SCREEN-035.md | wireframe.html | uc/ | ac/ |
| 30 | [[SCREEN-036]] — 공지 작성 화면 | screens/SCREEN-036/SCREEN-036.md | wireframe.html | uc/ | ac/ |
| 31 | [[SCREEN-037]] — 공지 수정 화면 | screens/SCREEN-037/SCREEN-037.md | wireframe.html | uc/ | ac/ |
| 32 | [[SCREEN-038]] — 이벤트유형 관리 화면 | screens/SCREEN-038/SCREEN-038.md | wireframe.html | uc/ | ac/ |
| 33 | [[SCREEN-039]] — 산출물 가져오기 | screens/SCREEN-039/SCREEN-039.md | wireframe.html | uc/ | ac/ |
| 34 | [[SCREEN-040]] — 관리자 페이지 진입 화면 | screens/SCREEN-040/SCREEN-040.md | wireframe.html | uc/ | ac/ |
| 35 | [[SCREEN-041]] — 관리자 패스워드 교체 | screens/SCREEN-041/SCREEN-041.md | wireframe.html | uc/ | ac/ |
| 36 | [[SCREEN-042]] — 연동 서버 주소 관리 화면 | screens/SCREEN-042/SCREEN-042.md | wireframe.html | uc/ | ac/ |
| 37 | [[SCREEN-043]] — 위험 작업 화면 | screens/SCREEN-043/SCREEN-043.md | wireframe.html | uc/ | ac/ |

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[API-001]] | api_endpoint | CHANGED (v6→v6) |
| [[API-002]] | api_endpoint | CHANGED (v2→v2) |
| [[API-003]] | api_endpoint | CHANGED (v5→v5) |
| [[API-005]] | api_endpoint | CHANGED (v5→v5) |
| [[API-006]] | api_endpoint | CHANGED (v10→v10) |
| [[API-008]] | api_endpoint | CHANGED (v12→v12) |
| [[API-009]] | api_endpoint | CHANGED (v9→v9) |
| [[API-010]] | api_endpoint | CHANGED (v4→v4) |
| [[API-011]] | api_endpoint | CHANGED (v4→v4) |
| [[API-012]] | api_endpoint | CHANGED (v6→v6) |
| [[API-013]] | api_endpoint | CHANGED (v6→v6) |
| [[API-014]] | api_endpoint | CHANGED (v12→v12) |
| [[API-015]] | api_endpoint | CHANGED (v8→v8) |
| [[API-016]] | api_endpoint | CHANGED (v3→v3) |
| [[API-017]] | api_endpoint | CHANGED (v5→v5) |
| [[API-019]] | api_endpoint | CHANGED (v8→v8) |
| [[API-020]] | api_endpoint | CHANGED (v14→v14) |
| [[API-022]] | api_endpoint | CHANGED (v4→v4) |
| [[API-023]] | api_endpoint | CHANGED (v4→v4) |
| [[API-025]] | api_endpoint | CHANGED (v8→v8) |
| [[API-026]] | api_endpoint | CHANGED (v5→v5) |
| [[API-027]] | api_endpoint | CHANGED (v4→v4) |
| [[API-029]] | api_endpoint | CHANGED (v4→v4) |
| [[API-030]] | api_endpoint | CHANGED (v4→v4) |
| [[API-031]] | api_endpoint | CHANGED (v3→v3) |
| [[API-032]] | api_endpoint | CHANGED (v8→v8) |
| [[API-034]] | api_endpoint | CHANGED (v9→v9) |
| [[API-035]] | api_endpoint | CHANGED (v11→v11) |
| [[API-036]] | api_endpoint | CHANGED (v11→v11) |
| [[API-045]] | api_endpoint | CHANGED (v2→v2) |
| [[API-055]] | api_endpoint | CHANGED (v6→v6) |
| [[API-057]] | api_endpoint | CHANGED (v6→v6) |
| [[API-058]] | api_endpoint | CHANGED (v3→v3) |
| [[API-061]] | api_endpoint | CHANGED (v10→v10) |
| [[API-062]] | api_endpoint | CHANGED (v7→v7) |
| [[API-063]] | api_endpoint | CHANGED (v9→v9) |
| [[API-065]] | api_endpoint | CHANGED (v21→v21) |
| [[API-067]] | api_endpoint | CHANGED (v8→v8) |
| [[API-070]] | api_endpoint | CHANGED (v8→v8) |
| [[API-071]] | api_endpoint | CHANGED (v7→v7) |
| [[API-072]] | api_endpoint | CHANGED (v10→v10) |
| [[API-073]] | api_endpoint | CHANGED (v9→v9) |
| [[API-074]] | api_endpoint | CHANGED (v7→v7) |
| [[API-075]] | api_endpoint | CHANGED (v8→v8) |
| [[API-076]] | api_endpoint | CHANGED (v9→v9) |
| [[API-081]] | api_endpoint | CHANGED (v6→v6) |
| [[API-090]] | api_endpoint | CHANGED (v4→v4) |
| [[API-091]] | api_endpoint | CHANGED (v12→v12) |
| [[API-093]] | api_endpoint | CHANGED (v14→v14) |
| [[API-094]] | api_endpoint | CHANGED (v9→v9) |
| [[API-095]] | api_endpoint | CHANGED (v6→v6) |
| [[API-096]] | api_endpoint | CHANGED (v7→v7) |
| [[API-097]] | api_endpoint | CHANGED (v7→v7) |
| [[API-098]] | api_endpoint | CHANGED (v7→v7) |
| [[API-099]] | api_endpoint | CHANGED (v6→v6) |
| [[API-100]] | api_endpoint | CHANGED (v8→v8) |
| [[API-101]] | api_endpoint | CHANGED (v8→v8) |
| [[API-105]] | api_endpoint | CHANGED (v7→v7) |
| [[API-106]] | api_endpoint | CHANGED (v9→v9) |
| [[API-107]] | api_endpoint | CHANGED (v7→v7) |
| [[API-108]] | api_endpoint | CHANGED (v6→v6) |
| [[API-111]] | api_endpoint | CHANGED (v3→v3) |
| [[API-112]] | api_endpoint | CHANGED (v5→v5) |
| [[API-113]] | api_endpoint | CHANGED (v3→v3) |
| [[API-116]] | api_endpoint | CHANGED (v8→v8) |
| [[API-117]] | api_endpoint | CHANGED (v5→v5) |
| [[API-118]] | api_endpoint | CHANGED (v2→v2) |
| [[API-119]] | api_endpoint | CHANGED (v3→v3) |
| [[API-120]] | api_endpoint | CHANGED (v3→v3) |
| [[API-121]] | api_endpoint | CHANGED (v3→v3) |
| [[API-122]] | api_endpoint | CHANGED (v2→v2) |
| [[API-124]] | api_endpoint | CHANGED (v10→v10) |
| [[API-125]] | api_endpoint | CHANGED (v2→v2) |
| [[API-126]] | api_endpoint | CHANGED (v2→v2) |
| [[API-127]] | api_endpoint | CHANGED (v2→v2) |
| [[API-128]] | api_endpoint | CHANGED (v3→v3) |
| [[API-129]] | api_endpoint | CHANGED (v5→v5) |
| [[API-136]] | api_endpoint | CHANGED (v4→v4) |
| [[API-137]] | api_endpoint | CHANGED (v6→v6) |
| [[API-138]] | api_endpoint | CHANGED (v4→v4) |
| [[API-139]] | api_endpoint | CHANGED (v3→v3) |
| [[API-141]] | api_endpoint | CHANGED (v2→v2) |
| [[API-143]] | api_endpoint | CHANGED (v3→v3) |
| [[API-144]] | api_endpoint | CHANGED (v3→v3) |
| [[API-145]] | api_endpoint | CHANGED (v3→v3) |
| [[API-146]] | api_endpoint | CHANGED (v5→v5) |
| [[API-147]] | api_endpoint | CHANGED (v2→v2) |
| [[API-148]] | api_endpoint | CHANGED (v3→v3) |
| [[API-149]] | api_endpoint | CHANGED (v3→v3) |
| [[API-150]] | api_endpoint | CHANGED (v3→v3) |
| [[API-151]] | api_endpoint | CHANGED (v2→v2) |
| [[API-153]] | api_endpoint | CHANGED (v4→v4) |
| [[API-154]] | api_endpoint | CHANGED (v4→v4) |
| [[API-155]] | api_endpoint | CHANGED (v2→v2) |
| [[API-156]] | api_endpoint | CHANGED (v3→v3) |
| [[API-157]] | api_endpoint | CHANGED (v3→v3) |
| [[API-159]] | api_endpoint | CHANGED (v4→v4) |
| [[API-160]] | api_endpoint | CHANGED (v4→v4) |
| [[API-161]] | api_endpoint | CHANGED (v3→v3) |
| [[API-162]] | api_endpoint | CHANGED (v6→v6) |
| [[API-163]] | api_endpoint | CHANGED (v3→v3) |
| [[API-164]] | api_endpoint | CHANGED (v3→v3) |
| [[API-165]] | api_endpoint | CHANGED (v7→v7) |
| [[API-166]] | api_endpoint | CHANGED (v3→v3) |
| [[API-168]] | api_endpoint | CHANGED (v2→v2) |
| [[API-169]] | api_endpoint | CHANGED (v6→v6) |
| [[API-170]] | api_endpoint | CHANGED (v3→v3) |
| [[API-171]] | api_endpoint | CHANGED (v3→v3) |
| [[API-172]] | api_endpoint | CHANGED (v4→v4) |
| [[API-173]] | api_endpoint | CHANGED (v6→v6) |
| [[API-174]] | api_endpoint | CHANGED (v6→v6) |
| [[API-175]] | api_endpoint | CHANGED (v2→v2) |
| [[API-176]] | api_endpoint | CHANGED (v3→v3) |
| [[API-178]] | api_endpoint | CHANGED (v8→v8) |
| [[API-181]] | api_endpoint | CHANGED (v7→v7) |
| [[API-182]] | api_endpoint | CHANGED (v4→v4) |
| [[API-187]] | api_endpoint | CHANGED (v3→v3) |
| [[API-193]] | api_endpoint | CHANGED (v5→v5) |
| [[API-202]] | api_endpoint | CHANGED (v2→v2) |
| [[SCREEN-011]] | screen_spec | CHANGED (v21→v21) |
| [[SCREEN-012]] | screen_spec | CHANGED (v47→v47) |
| [[SCREEN-018]] | screen_spec | CHANGED (v29→v29) |
| [[SCREEN-021]] | screen_spec | CHANGED (v28→v28) |
| [[SCREEN-032]] | screen_spec | CHANGED (v23→v23) |

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

> ⚠️ 여러 화면이 공유하는 UC/AC 53건은 화면 폴더마다 같은 파일명으로
> 복제돼 있어 wikilink 가 어느 사본을 가리킬지 모호하다(구현엔 영향 없음):
> - UC-006 — SCREEN-005, SCREEN-025
> - UC-008 — SCREEN-005, SCREEN-010
> - UC-011 — SCREEN-008, SCREEN-009
> - UC-016 — SCREEN-009, SCREEN-032
> - UC-023 — SCREEN-018, SCREEN-019
> - UC-024 — SCREEN-028, SCREEN-029
> - UC-027 — SCREEN-033, SCREEN-034
> - UC-032 — SCREEN-026, SCREEN-038
> - AC-006 — SCREEN-005, SCREEN-025
> - AC-008 — SCREEN-005, SCREEN-010
> - AC-011 — SCREEN-008, SCREEN-009
> - AC-016 — SCREEN-009, SCREEN-032
> - AC-018 — SCREEN-022, SCREEN-023
> - AC-019 — SCREEN-008, SCREEN-009, SCREEN-009, SCREEN-032
> - AC-021 — SCREEN-005, SCREEN-023
> - AC-022 — SCREEN-018, SCREEN-019
> - AC-023 — SCREEN-005, SCREEN-008, SCREEN-009
> - AC-024 — SCREEN-005, SCREEN-005
> - AC-032 — SCREEN-028, SCREEN-029
> - AC-033 — SCREEN-028, SCREEN-029
> - AC-034 — SCREEN-028, SCREEN-029
> - AC-035 — SCREEN-028, SCREEN-029
> - AC-036 — SCREEN-028, SCREEN-029
> - AC-037 — SCREEN-028, SCREEN-029
> - AC-041 — SCREEN-039, SCREEN-039
> - AC-042 — SCREEN-039, SCREEN-039
> - AC-043 — SCREEN-039, SCREEN-039
> - AC-044 — SCREEN-039, SCREEN-039
> - AC-045 — SCREEN-039, SCREEN-039
> - AC-046 — SCREEN-039, SCREEN-039
> - AC-047 — SCREEN-039, SCREEN-039
> - AC-092 — SCREEN-026, SCREEN-038
> - AC-093 — SCREEN-026, SCREEN-038
> - AC-094 — SCREEN-026, SCREEN-038
> - AC-095 — SCREEN-033, SCREEN-034
> - AC-096 — SCREEN-033, SCREEN-034
> - AC-097 — SCREEN-033, SCREEN-034
> - AC-098 — SCREEN-033, SCREEN-034
> - AC-099 — SCREEN-027, SCREEN-027
> - AC-100 — SCREEN-027, SCREEN-027
> - AC-101 — SCREEN-027, SCREEN-027
> - AC-102 — SCREEN-027, SCREEN-027
> - AC-103 — SCREEN-027, SCREEN-027
> - AC-104 — SCREEN-027, SCREEN-027
> - AC-105 — SCREEN-027, SCREEN-027
> - AC-106 — SCREEN-027, SCREEN-027
> - AC-107 — SCREEN-026, SCREEN-038
> - AC-108 — SCREEN-026, SCREEN-038
> - AC-109 — SCREEN-026, SCREEN-038
> - AC-110 — SCREEN-026, SCREEN-038
> - AC-111 — SCREEN-026, SCREEN-038
> - AC-112 — SCREEN-026, SCREEN-038
> - AC-120 — SCREEN-039, SCREEN-039

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
