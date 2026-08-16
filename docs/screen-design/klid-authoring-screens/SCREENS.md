# 저작도구 화면 화면 키트 — SCREENS.md

> 이 파일이 화면 구현의 진입점이다. mc-logi-screen-implement 는 이 파일부터 읽는다.
> 키트는 read-only 산출물 — **직접 수정 금지**. 갱신은 mc-logi-screen-kit 재실행.

## 키트 현황

| 항목 | 값 |
|---|---|
| Domain | - 저작도구 화면 |
| last sync | 2026-08-16T12:52:08.845Z (session 13) |
| 화면 수 | 32개 |
| ui_component 카탈로그 | populated 144건 |
| 출력 루트 | /Users/ck/orca/workspaces/klid-label/domain-check/docs/screen-design/klid-authoring-screens |
| 생성 | download-kit.mjs + arrange-screen-kit.mjs (결정적, LLM 0) |

## 화면 목록

| SCREEN-ID | 화면명 | 상태 | 와이어프레임 | consumes_apis | required_roles |
|---|---|---|---|---|---|
| [[SCREEN-001]] | 세션 인계 진입 화면 | UNCHANGED | ✅ |  |  |
| [[SCREEN-002]] | 역할 클레임 화면 | CHANGED | ✅ | [[API-007]] |  |
| [[SCREEN-003]] | 접근 거부 화면 | UNCHANGED | ✅ |  |  |
| [[SCREEN-004]] | 개발용 로그인 화면 | CHANGED | ✅ | [[API-153]] |  |
| [[SCREEN-005]] | 라벨링 캔버스 화면 | CHANGED | ✅ | [[API-018]], [[API-019]], [[API-020]], [[API-021]], [[API-024]], [[API-032]], [[API-066]], [[API-067]], [[API-102]], [[API-103]], [[API-104]], [[API-105]], [[API-123]], [[API-124]], [[API-125]], [[API-126]], [[API-127]], [[API-128]], [[API-129]], [[API-132]], [[API-134]], [[API-133]], [[API-135]], [[API-093]], [[API-182]], [[API-012]], [[API-178]], [[API-022]], [[API-023]], [[API-168]], [[API-170]], [[API-172]], [[API-173]], [[API-183]], [[API-184]], [[API-177]], [[API-195]], [[API-196]], [[API-197]], [[API-034]], [[API-035]], [[API-036]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-006]] | 마킹 화면 | CHANGED | ✅ | [[API-047]], [[API-043]], [[API-091]], [[API-114]], [[API-084]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-008]] | 영상 처리 현황 화면 | CHANGED | ✅ | [[API-042]], [[API-047]], [[API-070]], [[API-071]], [[API-181]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-009]] | 영상 상세 화면 | UNCHANGED | ✅ | [[API-021]], [[API-043]], [[API-044]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-010]] | 로드 버전 선택 | CHANGED | ✅ | [[API-197]], [[API-182]], [[API-195]], [[API-034]], [[API-035]], [[API-036]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-011]] | 대시보드 화면 | CHANGED | ✅ | [[API-042]], [[API-055]], [[API-072]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-012]] | 작업 목록 화면 | CHANGED | ✅ | [[API-001]], [[API-002]], [[API-070]], [[API-071]], [[API-072]], [[API-073]], [[API-136]], [[API-137]], [[API-116]], [[API-187]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-018]] | 검수 목록 화면 | CHANGED | ✅ | [[API-008]], [[API-138]] | [[ROLE-001]] |
| [[SCREEN-019]] | 검수 상세 화면 | CHANGED | ✅ | [[API-009]], [[API-010]], [[API-011]], [[API-013]], [[API-014]], [[API-015]], [[API-021]], [[API-132]], [[API-066]], [[API-102]], [[API-103]], [[API-104]], [[API-105]] | [[ROLE-001]] |
| [[SCREEN-020]] | 작업자 통계 화면 | CHANGED | ✅ | [[API-001]], [[API-056]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-021]] | 전체 구축 현황 화면 | CHANGED | ✅ | [[API-057]], [[API-058]] | [[ROLE-001]] |
| [[SCREEN-022]] | 증강 요청 화면 | CHANGED | ✅ | [[API-042]], [[API-059]], [[API-060]], [[API-092]], [[API-179]] | [[ROLE-001]] |
| [[SCREEN-023]] | 증강 결과 화면 | CHANGED | ✅ | [[API-061]], [[API-062]], [[API-063]], [[API-188]], [[API-189]], [[API-190]], [[API-175]] | [[ROLE-001]] |
| [[SCREEN-024]] | 사용자 관리 화면 | CHANGED | ✅ | [[API-001]], [[API-004]] | [[ROLE-001]] |
| [[SCREEN-025]] | 시스템 설정 화면 | CHANGED | ✅ | [[API-068]], [[API-069]], [[API-090]], [[API-194]] | [[ROLE-001]] |
| [[SCREEN-026]] | 프리셋 관리 화면 | CHANGED | ✅ | [[API-037]], [[API-038]], [[API-039]], [[API-040]], [[API-041]], [[API-117]] | [[ROLE-001]] |
| [[SCREEN-027]] | 영상 업로드 | CHANGED | ✅ | [[API-043]], [[API-152]], [[API-156]], [[API-158]], [[API-160]], [[API-162]], [[API-164]] | [[ROLE-001]] |
| [[SCREEN-028]] | 포털 홈 화면 | CHANGED | ✅ | [[API-115]] | [[ROLE-003]] |
| [[SCREEN-029]] | 포털 라벨링 화면 | CHANGED | ✅ | [[API-024]], [[API-082]], [[API-110]], [[API-111]] | [[ROLE-003]] |
| [[SCREEN-030]] | 공지 목록 화면 | CHANGED | ✅ | [[API-095]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-031]] | 공지 상세 화면 | CHANGED | ✅ | [[API-096]], [[API-099]], [[API-100]], [[API-101]], [[API-107]] | [[ROLE-001]], [[ROLE-002]] |
| [[SCREEN-032]] | 비식별 신고 관리 화면 | CHANGED | ✅ | [[API-094]], [[API-109]], [[API-202]] | [[ROLE-001]] |
| [[SCREEN-033]] | 포털 업로드 화면 | CHANGED | ✅ | [[API-139]], [[API-142]], [[API-151]], [[API-163]], [[API-166]], [[API-169]], [[API-171]], [[API-161]] | [[ROLE-003]] |
| [[SCREEN-034]] | 포털 업로드 라벨링 화면 | CHANGED | ✅ | [[API-140]], [[API-149]], [[API-154]], [[API-155]], [[API-157]], [[API-159]] | [[ROLE-003]] |
| [[SCREEN-035]] | 라벨 관리 화면 | CHANGED | ✅ | [[API-024]], [[API-025]], [[API-026]], [[API-027]], [[API-028]], [[API-029]], [[API-030]], [[API-031]] | [[ROLE-001]] |
| [[SCREEN-036]] | 공지 작성 화면 | CHANGED | ✅ | [[API-097]] | [[ROLE-001]] |
| [[SCREEN-037]] | 공지 수정 화면 | CHANGED | ✅ | [[API-096]], [[API-098]], [[API-106]], [[API-108]] | [[ROLE-001]] |
| [[SCREEN-038]] | 이벤트유형 관리 화면 | UNCHANGED | ✅ | [[API-185]], [[API-186]] | [[ROLE-001]] |

## 공유 자산 인덱스

| type | 파일 | 건수 |
|---|---|---|
| design_system | _shared/design-system.md | 1 |
| ui_component | _shared/ui-catalog.md | 144 |
| app_shell + nav | _shared/shell-nav.md | 4 |
| api_endpoint | _shared/api/ | 181 |
| constant | _shared/constant/ | 2 |
| permission_role | _shared/role/ | 3 |
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
| 2 | [[SCREEN-002]] — 역할 클레임 화면 | screens/SCREEN-002/SCREEN-002.md | wireframe.html | uc/ | ac/ |
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
| 21 | [[SCREEN-027]] — 영상 업로드 | screens/SCREEN-027/SCREEN-027.md | wireframe.html | uc/ | ac/ |
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

## 변경 알림 (코드 재반영 필요)

| ITEM | type | 상태 |
|---|---|---|
| [[AC-001]] | acceptance | CHANGED (v5→v9) |
| [[AC-002]] | acceptance | CHANGED (v6→v8) |
| [[AC-003]] | acceptance | CHANGED (v6→v7) |
| [[AC-004]] | acceptance | CHANGED (v5→v7) |
| [[AC-005]] | acceptance | CHANGED (v5→v7) |
| [[AC-006]] | acceptance | CHANGED (v5→v9) |
| [[AC-008]] | acceptance | CHANGED (v10→v11) |
| [[AC-009]] | acceptance | CHANGED (v12→v15) |
| [[AC-010]] | acceptance | CHANGED (v6→v7) |
| [[AC-011]] | acceptance | CHANGED (v7→v9) |
| [[AC-013]] | acceptance | CHANGED (v6→v8) |
| [[AC-016]] | acceptance | CHANGED (v7→v10) |
| [[AC-017]] | acceptance | CHANGED (v4→v5) |
| [[AC-019]] | acceptance | CHANGED (v5→v6) |
| [[AC-022]] | acceptance | CHANGED (v6→v7) |
| [[AC-025]] | acceptance | CHANGED (v3→v6) |
| [[AC-026]] | acceptance | CHANGED (v3→v5) |
| [[AC-027]] | acceptance | CHANGED (v3→v5) |
| [[AC-028]] | acceptance | CHANGED (v3→v5) |
| [[API-001]] | api_endpoint | CHANGED (v2→v3) |
| [[API-003]] | api_endpoint | CHANGED (v3→v4) |
| [[API-004]] | api_endpoint | CHANGED (v3→v5) |
| [[API-005]] | api_endpoint | CHANGED (v4→v5) |
| [[API-006]] | api_endpoint | CHANGED (v5→v8) |
| [[API-007]] | api_endpoint | CHANGED (v4→v6) |
| [[API-008]] | api_endpoint | CHANGED (v8→v11) |
| [[API-009]] | api_endpoint | CHANGED (v5→v8) |
| [[API-010]] | api_endpoint | CHANGED (v3→v4) |
| [[API-011]] | api_endpoint | CHANGED (v3→v4) |
| [[API-012]] | api_endpoint | CHANGED (v4→v6) |
| [[API-013]] | api_endpoint | CHANGED (v4→v6) |
| [[API-014]] | api_endpoint | CHANGED (v8→v9) |
| [[API-015]] | api_endpoint | CHANGED (v4→v7) |
| [[API-016]] | api_endpoint | CHANGED (v2→v3) |
| [[API-017]] | api_endpoint | CHANGED (v2→v4) |
| [[API-020]] | api_endpoint | CHANGED (v6→v7) |
| [[API-021]] | api_endpoint | CHANGED (v4→v7) |
| [[API-023]] | api_endpoint | CHANGED (v3→v4) |
| [[API-024]] | api_endpoint | CHANGED (v4→v5) |
| [[API-025]] | api_endpoint | CHANGED (v5→v8) |
| [[API-026]] | api_endpoint | CHANGED (v2→v5) |
| [[API-027]] | api_endpoint | CHANGED (v2→v4) |
| [[API-029]] | api_endpoint | CHANGED (v2→v4) |
| [[API-030]] | api_endpoint | CHANGED (v2→v4) |
| [[API-031]] | api_endpoint | CHANGED (v2→v3) |
| [[API-032]] | api_endpoint | CHANGED (v6→v8) |
| [[API-034]] | api_endpoint | CHANGED (v7→v8) |
| [[API-035]] | api_endpoint | CHANGED (v8→v10) |
| [[API-036]] | api_endpoint | CHANGED (v8→v10) |
| [[API-037]] | api_endpoint | CHANGED (v3→v5) |
| [[API-038]] | api_endpoint | CHANGED (v5→v7) |
| [[API-039]] | api_endpoint | CHANGED (v4→v6) |
| [[API-040]] | api_endpoint | CHANGED (v2→v3) |
| [[API-041]] | api_endpoint | CHANGED (v3→v4) |
| [[API-042]] | api_endpoint | CHANGED (v4→v5) |
| [[API-043]] | api_endpoint | CHANGED (v11→v12) |
| [[API-046]] | api_endpoint | CHANGED (v4→v5) |
| [[API-047]] | api_endpoint | CHANGED (v4→v10) |
| [[API-055]] | api_endpoint | CHANGED (v2→v4) |
| [[API-056]] | api_endpoint | CHANGED (v4→v5) |
| [[API-057]] | api_endpoint | CHANGED (v3→v5) |
| [[API-058]] | api_endpoint | CHANGED (v2→v3) |
| [[API-060]] | api_endpoint | CHANGED (v6→v10) |
| [[API-061]] | api_endpoint | CHANGED (v8→v10) |
| [[API-062]] | api_endpoint | CHANGED (v4→v6) |
| [[API-063]] | api_endpoint | CHANGED (v4→v8) |
| [[API-065]] | api_endpoint | CHANGED (v11→v15) |
| [[API-066]] | api_endpoint | CHANGED (v3→v4) |
| [[API-067]] | api_endpoint | CHANGED (v3→v5) |
| [[API-068]] | api_endpoint | CHANGED (v3→v4) |
| [[API-069]] | api_endpoint | CHANGED (v5→v8) |
| [[API-070]] | api_endpoint | CHANGED (v5→v8) |
| [[API-071]] | api_endpoint | CHANGED (v4→v7) |
| [[API-072]] | api_endpoint | CHANGED (v4→v10) |
| [[API-073]] | api_endpoint | CHANGED (v4→v9) |
| [[API-074]] | api_endpoint | CHANGED (v5→v7) |
| [[API-075]] | api_endpoint | CHANGED (v5→v8) |
| [[API-076]] | api_endpoint | CHANGED (v4→v8) |
| [[API-081]] | api_endpoint | CHANGED (v4→v5) |
| [[API-082]] | api_endpoint | CHANGED (v5→v7) |
| [[API-083]] | api_endpoint | CHANGED (v3→v4) |
| [[API-084]] | api_endpoint | CHANGED (v3→v6) |
| [[API-090]] | api_endpoint | CHANGED (v3→v4) |
| [[API-091]] | api_endpoint | CHANGED (v6→v10) |
| [[API-092]] | api_endpoint | CHANGED (v6→v7) |
| [[API-093]] | api_endpoint | CHANGED (v7→v9) |
| [[API-094]] | api_endpoint | CHANGED (v7→v9) |
| [[API-095]] | api_endpoint | CHANGED (v3→v6) |
| [[API-096]] | api_endpoint | CHANGED (v3→v7) |
| [[API-097]] | api_endpoint | CHANGED (v3→v7) |
| [[API-098]] | api_endpoint | CHANGED (v3→v7) |
| [[API-099]] | api_endpoint | CHANGED (v3→v6) |
| [[API-100]] | api_endpoint | CHANGED (v4→v8) |
| [[API-101]] | api_endpoint | CHANGED (v4→v8) |
| [[API-102]] | api_endpoint | CHANGED (v8→v13) |
| [[API-103]] | api_endpoint | CHANGED (v5→v9) |
| [[API-104]] | api_endpoint | CHANGED (v5→v12) |
| [[API-105]] | api_endpoint | CHANGED (v3→v7) |
| [[API-106]] | api_endpoint | CHANGED (v6→v9) |
| [[API-107]] | api_endpoint | CHANGED (v3→v7) |
| [[API-108]] | api_endpoint | CHANGED (v3→v6) |
| [[API-109]] | api_endpoint | CHANGED (v3→v5) |
| [[API-110]] | api_endpoint | CHANGED (v2→v3) |
| [[API-111]] | api_endpoint | CHANGED (v2→v3) |
| [[API-112]] | api_endpoint | CHANGED (v3→v4) |
| [[API-113]] | api_endpoint | CHANGED (v2→v3) |
| [[API-116]] | api_endpoint | CHANGED (v3→v8) |
| [[API-117]] | api_endpoint | CHANGED (v1→v5) |
| [[API-118]] | api_endpoint | CHANGED (v1→v2) |
| [[API-119]] | api_endpoint | CHANGED (v1→v3) |
| [[API-120]] | api_endpoint | CHANGED (v2→v3) |
| [[API-121]] | api_endpoint | CHANGED (v1→v3) |
| [[API-123]] | api_endpoint | CHANGED (v3→v6) |
| [[API-124]] | api_endpoint | CHANGED (v5→v6) |
| [[API-132]] | api_endpoint | CHANGED (v1→v2) |
| [[API-133]] | api_endpoint | CHANGED (v2→v3) |
| [[API-134]] | api_endpoint | CHANGED (v3→v4) |
| [[API-135]] | api_endpoint | CHANGED (v2→v3) |
| [[API-136]] | api_endpoint | CHANGED (v1→v4) |
| [[API-137]] | api_endpoint | CHANGED (v3→v6) |
| [[API-138]] | api_endpoint | CHANGED (v2→v4) |
| [[API-139]] | api_endpoint | CHANGED (v2→v3) |
| [[API-140]] | api_endpoint | CHANGED (v1→v3) |
| [[API-141]] | api_endpoint | CHANGED (v1→v2) |
| [[API-142]] | api_endpoint | CHANGED (v1→v2) |
| [[API-143]] | api_endpoint | CHANGED (v2→v3) |
| [[API-144]] | api_endpoint | CHANGED (v1→v3) |
| [[API-145]] | api_endpoint | CHANGED (v1→v3) |
| [[API-146]] | api_endpoint | CHANGED (v3→v5) |
| [[API-147]] | api_endpoint | CHANGED (v1→v2) |
| [[API-148]] | api_endpoint | CHANGED (v2→v3) |
| [[API-149]] | api_endpoint | CHANGED (v2→v3) |
| [[API-150]] | api_endpoint | CHANGED (v2→v3) |
| [[API-151]] | api_endpoint | CHANGED (v1→v2) |
| [[API-152]] | api_endpoint | CHANGED (v2→v4) |
| [[API-153]] | api_endpoint | CHANGED (v2→v4) |
| [[API-154]] | api_endpoint | CHANGED (v3→v4) |
| [[API-155]] | api_endpoint | CHANGED (v1→v2) |
| [[API-156]] | api_endpoint | CHANGED (v1→v2) |
| [[API-157]] | api_endpoint | CHANGED (v2→v3) |
| [[API-158]] | api_endpoint | CHANGED (v2→v3) |
| [[API-159]] | api_endpoint | CHANGED (v2→v3) |
| [[API-160]] | api_endpoint | CHANGED (v2→v3) |
| [[API-161]] | api_endpoint | CHANGED (v2→v3) |
| [[API-162]] | api_endpoint | CHANGED (v2→v5) |
| [[API-163]] | api_endpoint | CHANGED (v2→v3) |
| [[API-164]] | api_endpoint | CHANGED (v1→v2) |
| [[API-165]] | api_endpoint | CHANGED (v2→v6) |
| [[API-166]] | api_endpoint | CHANGED (v2→v3) |
| [[API-167]] | api_endpoint | CHANGED (v9→v10) |
| [[API-169]] | api_endpoint | CHANGED (v4→v6) |
| [[API-171]] | api_endpoint | CHANGED (v2→v3) |
| [[API-177]] | api_endpoint | CHANGED (v3→v4) |
| [[API-178]] | api_endpoint | CHANGED (v5→v8) |
| [[API-179]] | api_endpoint | CHANGED (v2→v5) |
| [[API-181]] | api_endpoint | CHANGED (v3→v7) |
| [[API-187]] | api_endpoint | CHANGED (v1→v2) |
| [[API-188]] | api_endpoint | CHANGED (v1→v2) |
| [[API-189]] | api_endpoint | CHANGED (v1→v2) |
| [[API-190]] | api_endpoint | CHANGED (v2→v4) |
| [[API-193]] | api_endpoint | CHANGED (v1→v2) |
| [[API-194]] | api_endpoint | CHANGED (v2→v4) |
| [[API-196]] | api_endpoint | CHANGED (v6→v8) |
| [[API-202]] | api_endpoint | NEW |
| [[ROLE-001]] | permission_role | CHANGED (v8→v10) |
| [[SCREEN-002]] | screen_spec | CHANGED (v14→v17) |
| [[SCREEN-004]] | screen_spec | CHANGED (v8→v11) |
| [[SCREEN-005]] | screen_spec | CHANGED (v70→v75) |
| [[SCREEN-006]] | screen_spec | CHANGED (v37→v41) |
| [[SCREEN-008]] | screen_spec | CHANGED (v34→v35) |
| [[SCREEN-010]] | screen_spec | CHANGED (v34→v37) |
| [[SCREEN-011]] | screen_spec | CHANGED (v16→v18) |
| [[SCREEN-012]] | screen_spec | CHANGED (v34→v39) |
| [[SCREEN-018]] | screen_spec | CHANGED (v23→v24) |
| [[SCREEN-019]] | screen_spec | CHANGED (v28→v29) |
| [[SCREEN-020]] | screen_spec | CHANGED (v26→v28) |
| [[SCREEN-021]] | screen_spec | CHANGED (v23→v25) |
| [[SCREEN-022]] | screen_spec | CHANGED (v33→v36) |
| [[SCREEN-023]] | screen_spec | CHANGED (v35→v39) |
| [[SCREEN-024]] | screen_spec | CHANGED (v20→v22) |
| [[SCREEN-025]] | screen_spec | CHANGED (v24→v30) |
| [[SCREEN-026]] | screen_spec | CHANGED (v22→v26) |
| [[SCREEN-027]] | screen_spec | CHANGED (v24→v26) |
| [[SCREEN-028]] | screen_spec | CHANGED (v12→v15) |
| [[SCREEN-029]] | screen_spec | CHANGED (v31→v34) |
| [[SCREEN-030]] | screen_spec | CHANGED (v20→v23) |
| [[SCREEN-031]] | screen_spec | CHANGED (v22→v29) |
| [[SCREEN-032]] | screen_spec | CHANGED (v22→v23) |
| [[SD-011]] | screen_design | CHANGED (v4→v6) |
| [[SD-012]] | screen_design | CHANGED (v7→v8) |
| [[SCREEN-033]] | screen_spec | CHANGED (v11→v13) |
| [[SCREEN-034]] | screen_spec | CHANGED (v13→v15) |
| [[SCREEN-035]] | screen_spec | CHANGED (v15→v16) |
| [[SCREEN-036]] | screen_spec | CHANGED (v7→v8) |
| [[SCREEN-037]] | screen_spec | CHANGED (v7→v8) |
| [[UC-001]] | use_case | CHANGED (v10→v12) |
| [[UC-002]] | use_case | CHANGED (v11→v13) |
| [[UC-003]] | use_case | CHANGED (v9→v11) |
| [[UC-004]] | use_case | CHANGED (v11→v13) |
| [[UC-005]] | use_case | CHANGED (v8→v10) |
| [[UC-006]] | use_case | CHANGED (v7→v9) |
| [[UC-007]] | use_case | CHANGED (v9→v12) |
| [[UC-008]] | use_case | CHANGED (v10→v13) |
| [[UC-009]] | use_case | CHANGED (v16→v19) |
| [[UC-010]] | use_case | CHANGED (v11→v13) |
| [[UC-011]] | use_case | CHANGED (v11→v12) |
| [[UC-016]] | use_case | CHANGED (v19→v20) |
| [[UC-018]] | use_case | CHANGED (v12→v16) |
| [[UC-019]] | use_case | CHANGED (v13→v16) |
| [[UC-021]] | use_case | CHANGED (v14→v20) |
| [[UC-022]] | use_case | CHANGED (v14→v17) |
| [[UC-023]] | use_case | CHANGED (v19→v22) |
| [[UC-024]] | use_case | CHANGED (v10→v13) |
| [[UC-027]] | use_case | CHANGED (v7→v10) |
| [[UC-029]] | use_case | CHANGED (v5→v9) |
| [[UC-030]] | use_case | CHANGED (v4→v7) |
| [[UC-031]] | use_case | CHANGED (v5→v7) |
| [[UC-032]] | use_case | CHANGED (v4→v6) |

## Obsidian 볼트로 보기

이 키트 루트를 볼트로 열면 화면↔API↔ROLE↔UC↔AC 관계가 그래프로 보인다
(frontmatter `links:` 가 `[[ID]]` wikilink). 그래프뷰 → 필터 → *Existing files only* 를
켜면 키트 밖 ITEM 의 유령 노드가 사라진다.

> ⚠️ 여러 화면이 공유하는 UC/AC 17건은 화면 폴더마다 같은 파일명으로
> 복제돼 있어 wikilink 가 어느 사본을 가리킬지 모호하다(구현엔 영향 없음):
> - UC-006 — SCREEN-005, SCREEN-025
> - UC-008 — SCREEN-005, SCREEN-010
> - UC-011 — SCREEN-008, SCREEN-009
> - UC-016 — SCREEN-009, SCREEN-032
> - UC-023 — SCREEN-018, SCREEN-019
> - UC-024 — SCREEN-028, SCREEN-029
> - UC-027 — SCREEN-033, SCREEN-034
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

## git 권장

`docs/screen-design/` 를 git 으로 함께 버전관리 권장.
