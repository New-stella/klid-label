# 배치 F — 대시보드·통계·공지 축 (SCREEN-011/020/021/030/031)

## 요약
- 발견: ERR 15 / STALE 3 / GAP 6 / CONFLICT 2 (일부 항목은 중복 분류 — 아래 각 항목의 주 분류 기준)
- 총 22건 (분류 중복 태그 포함)

## 교차 확인 메모 (읽기 전에 참고)
이 배치의 5개 화면 모두 실제 구현이 **두 곳**(납품 FE `klid-label-frontend`, 테스트베드 `upload-ui/frontend`) 존재한다. 대조 결과 이 정의서군은 **테스트베드 쪽 옛 설계와 일치하고, 납품(실서비스) FE 는 그 이후 재설계(누적/승인 병기 정책 2026-08-03, KRDS 정합 리팩토링 등)를 거쳐 갈라져 있다.** 즉 정의서만 보고 제3자가 구현하면 "테스트베드 시절 설계"가 나오며, 이는 현재 실서비스로 나가는 화면과 다르다. 이 사실 자체를 GAP-F00 으로 별도 기록하고, 개별 항목에도 "테스트베드 일치 / 납품FE 불일치" 여부를 표기했다.

### [GAP-F00] 정의서가 참조하는 기준 구현이 명시돼 있지 않다
- **정의서 서술**: 각 화면 purpose 에 컴포넌트명(예: DashboardPage, WorkerStatPage)만 적혀 있고 어느 저장소/브랜치를 정본으로 삼는지 서술 없음.
- **실제**: `klid-label-frontend`(외부팀 실제 납품)와 `upload-ui/frontend`(내부 테스트베드) 두 구현이 존재하며 이 배치 5개 화면 전부에서 서로 다른 UI 패턴을 보인다.
- **근거**: `klid-label-frontend/src/pages/DashboardPage.tsx` vs `upload-ui/frontend/src/pages/DashboardPage.tsx`(NowClock 유무) · `klid-label-frontend/src/pages/StatsWorkerPage.tsx` vs `upload-ui/frontend/src/pages/WorkerStatPage.tsx`(자동폴백 유무) · `klid-label-frontend/src/components/stats-overall/ProcessingSummary.tsx` vs `upload-ui/frontend/src/pages/OverallStatPage.tsx`(5카드 그리드 vs 스택바)
- **조치 제안**: 화면정의서 상단에 "정본 구현 = klid-label-frontend(납품)" 를 명시하고, 테스트베드는 참고용임을 분리 기술. 정의서 갱신 시 납품 FE 기준으로 재동기화.
- **확신도**: high

---

## SCREEN-011 (대시보드)

### [ERR-F01] 데이터 개수 카드의 주 수치 서술이 확정 정책과 반대
- **위치**: sections[2] "데이터 개수 카드" > components[0]/[1] "이미지/영상 데이터 개수"
- **정의서 서술**: "'이미지 데이터 개수'(cumulativeImageCount장) + ... 영상은 eventDistribution. 모두 /stats/summary 응답." — 카드 주 수치를 **전체(cumulative) 기준**으로만 서술.
- **실제**: 2026-08-03 사용자 확정 정책(`stats-approved-vs-total-separation`)에 따라 카드 **주 수치는 검수완료(APPROVED) 기준**(`approvedImageCount`/`approvedVideoCount`)이고 전체(cumulative)는 `완료율 N%` 보조 문구로만 병기된다. 두 실제 구현(납품 FE·테스트베드) 모두 이 정책을 따른다.
- **근거**: `klid-label-frontend/src/components/dashboard/SummarySection.tsx` · `klid-label-frontend/src/components/dashboard/DataCountCard.tsx(DataCountCard)` · `klid-label-frontend/src/components/common/ApprovedRatioNote.tsx` · `upload-ui/frontend/src/pages/DashboardPage.tsx(computeApprovedRatio)`
- **조치 제안**: description 을 "주 수치=approvedImageCount/approvedVideoCount(검수완료 기준), 보조=cumulative*(전체, 완료율 N% 텍스트 병기)" 로 정정.
- **확신도**: high

### [ERR-F02 / STALE] "이벤트 6종 고정" 슬롯 서술은 폐기된 설계
- **위치**: sections[2] > components[2] "이벤트 6종 분포"
- **정의서 서술**: "type=List... options=['FALL','VIOLENCE','TRAFFIC_ACCIDENT','ABNORMAL_BEHAVIOR','FLOOD','WILDFIRE']" — 6종 고정 카테고리.
- **실제**: CLAUDE.md 「★이벤트유형 필터는 "표시명 그룹" 축이다」(2026-08-05 확정)에 따라 이벤트 유형은 관제 수신 코드를 표시명으로 그룹핑한 **가변 개수** 카테고리다. 두 실제 구현 모두 서버가 내려주는 분포(`approvedImageDistribution`/`approvedEventDistribution`)를 그대로 순회 렌더하며, 코드 주석이 "실제 이벤트 유형 수와 무관"이라고 명시한다. 실측 팔레트는 6종이 아니라 10개 항목(침수·산사태·화재·쓰러짐·파손·교통사고·싸움·흉기소지·납치·그 외)까지 존재한다.
- **근거**: `klid-label-frontend/src/components/dashboard/BreakdownList.tsx` (`SKELETON_ROWS` 주석 "실제 이벤트 유형 수와 무관한 표시용 값") · `klid-label-frontend/src/components/stats-overall/EventTypeChartSection.tsx(DISTRIBUTION_PALETTE)` · CLAUDE.md 「★이벤트유형 필터는 "표시명 그룹" 축이다」
- **조치 제안**: "6종 고정" 서술과 코드값 나열을 제거하고 "서버 응답(approvedImageDistribution/approvedEventDistribution)을 개수 무관 그대로 렌더, 그룹핑은 EventTypeService 정책 따름"으로 정정.
- **확신도**: high

### [ERR-F03] NowClock(실시간 1초 갱신) — 납품 FE 에는 존재하지 않음
- **위치**: sections[0] > components[1] "실시간 현재시각 1s 갱신 (NowClock)"
- **정의서 서술**: "dayjs YYYY-MM-DD HH:mm:ss, 1초 간격 setInterval 갱신"
- **실제**: 납품 FE 는 `RefreshButton` 공용 컴포넌트를 쓰며 date-fns 로 **마지막 새로고침 시각을 1회성으로 표시**할 뿐 매초 갱신되지 않는다(setInterval 없음). 테스트베드에는 정의서 그대로의 `NowClock`(dayjs, setInterval 1000ms)이 존재한다 — 납품 FE 만 이 기능이 빠져 있다.
- **근거**: `klid-label-frontend/src/components/common/RefreshButton.tsx` (no setInterval, `date-fns format`) · `klid-label-frontend/src/pages/DashboardPage.tsx` vs `upload-ui/frontend/src/pages/DashboardPage.tsx(NowClock)`
- **조치 제안**: 납품 FE 기준으로 "1초 갱신 실시간 시계"를 "새로고침 시각 스냅샷(정적, date-fns)"으로 정정하거나, 두 구현 중 정본을 택해 그에 맞게 기술.
- **확신도**: high

### [ERR-F04] "최근 완료 영상" 테이블 정렬축 서술 오류 — 필수 필터 누락
- **위치**: sections[3] "최근 완료 영상 테이블" > components[0]
- **정의서 서술**: "useVideos size=5, /videos. capturedAt desc 정렬 상위 5건"
- **실제**: 실제 정렬축은 `capturedAt` 이 아니라 `reviewCompletedAt` 이며, **`reviewStatusCd=APPROVED` 필터가 반드시 함께 전송**돼야 한다(코드 주석: 필터 없이 정렬만 보내면 BE 가 조인 쿼리 전용 정렬키를 무시하고 미검수 영상까지 섞인다). "최근 *완료* 영상"이라는 화면 의도상 `capturedAt`(적재/촬영 시각) 정렬은 완료 여부와 무관해 의미가 다르다. 두 실제 구현 모두 `reviewCompletedAt,desc` + `reviewStatusCd:'APPROVED'` 조합을 쓴다.
- **근거**: `klid-label-frontend/src/components/dashboard/VideosTable.tsx` · `upload-ui/frontend/src/pages/DashboardPage.tsx`(useVideos 호출부 주석)
- **조치 제안**: "정렬=reviewCompletedAt desc, 필수 필터 reviewStatusCd=APPROVED (누락 시 미검수 영상 혼입)"으로 정정. 제3자가 정의서만 보고 `sort=capturedAt,desc` 로만 구현하면 완료되지 않은 영상까지 노출되는 결함이 생긴다.
- **확신도**: high

### [ERR-F05] 빈 상태 문구 불일치
- **위치**: sections[3] > 설명 "빈 상태 '완료된 영상이 없습니다.'"
- **정의서 서술**: "완료된 영상이 없습니다."
- **실제**: 납품 FE 는 "영상이 없습니다."(`VideosTable.tsx` emptyMessage)
- **근거**: `klid-label-frontend/src/components/dashboard/VideosTable.tsx`
- **조치 제안**: 문구 정정 또는 확인 후 정합.
- **확신도**: medium

---

## SCREEN-020 (작업자 통계)

### [ERR-F06] REVIEWER 미선택 시 자동 폴백 서술 — 납품 FE 는 폴백 없이 빈 상태 화면
- **위치**: sections[0] "헤더·작업자 선택" 설명 "대상 workerId 결정: REVIEWER=selectedWorkerId ?? workers[0].id, WORKER=myId."
- **정의서 서술**: REVIEWER 가 아무것도 선택하지 않으면 작업자 목록의 첫 번째(`workers[0].id`)로 자동 폴백해 즉시 통계가 뜬다.
- **실제**: 납품 FE 는 `selectedWorkerId` 초기값이 `""`이고 자동 폴백이 없다. 대상 workerId 가 없으면 쿼리 자체가 `enabled:false`로 비활성화되고 화면은 **"작업자를 선택하세요" 빈 상태**(EmptyState)를 보여준다 — 사용자가 직접 선택해야 조회가 시작된다. 테스트베드는 정의서 그대로 `selectedWorkerId ?? workers[0]?.id` 자동 폴백을 구현한다 — 납품 FE 만 다르다.
- **근거**: `klid-label-frontend/src/pages/StatsWorkerPage.tsx` (`targetWorkerId = isReviewer ? selectedWorkerId : claims?.sub`, `enabled: !!targetWorkerId`) vs `upload-ui/frontend/src/pages/WorkerStatPage.tsx`(`selectedWorkerId ?? workers[0]?.id`)
- **조치 제안**: 납품 FE 기준 정본이면 "자동 폴백 없음 + 미선택 시 EmptyState('작업자를 선택하세요')" 로 정정하고 그 빈 상태를 컴포넌트 목록에 추가.
- **확신도**: high

### [GAP-F07] 미선택 빈 상태(EmptyState)가 정의서에 없음
- **위치**: 화면 전체 — 대응 섹션 없음
- **실제**: 납품 FE 는 REVIEWER 가 작업자를 고르기 전까지 KPI/차트/표 전체 대신 "작업자를 선택하세요 / 상단에서 작업자를 선택하면 해당 작업자의 통계가 표시됩니다" EmptyState 하나만 렌더한다.
- **근거**: `klid-label-frontend/src/pages/StatsWorkerPage.tsx`
- **조치 제안**: 새 섹션(role=main, layout=stack)으로 EmptyState 컴포넌트 추가 서술.
- **확신도**: high

### [ERR-F08] 조회 대상 작업자명 서브타이틀(WorkerNameSub)·아이콘 — 납품 FE 에 없음
- **위치**: sections[0] > components[0]/[1] "Heading 작업자 통계" / "WorkerNameSub"
- **정의서 서술**: "BarChart2 아이콘 + '작업자 통계' 제목, 하단에 조회 대상 workerName 표시(binds_to: workerStat.workerName)"
- **실제**: 납품 FE 는 아이콘이 없고(`PageTitle` 은 아이콘 prop 없음), `workerStat.workerName` 을 화면 어디에도 렌더하지 않는다(타입엔 필드가 있으나 소비처 0건). 대신 REVIEWER/WORKER 에 따라 **제목 자체가 달라진다**: REVIEWER="작업자 통계", WORKER="나의 통계"(부제도 다름) — 이 역할별 제목 분기도 정의서에 없음.
- **근거**: `klid-label-frontend/src/pages/StatsWorkerPage.tsx` · `grep workerName components/stats-worker` 결과 0건
- **조치 제안**: WorkerNameSub 컴포넌트 서술 삭제(또는 정본 확인), 역할별 제목 분기("작업자 통계"/"나의 통계") 추가.
- **확신도**: high

### [ERR-F09] KPI 라벨 텍스트 미세 불일치
- **위치**: sections[1] "KPI 4종" > components[0]/[2]
- **정의서 서술**: "완료 작업", "진행 중"
- **실제**: 납품 FE 라벨은 "완료", "진행중"(공백 없음).
- **근거**: `klid-label-frontend/src/components/stats-worker/WorkerStatHeadline.tsx(KPI_ITEMS)`
- **조치 제안**: 라벨 텍스트 정정.
- **확신도**: medium

---

## SCREEN-021 (전체 구축 현황)

### [CONFLICT-F10] "% 텍스트 절대 미노출" 회귀 방지 규칙 — 사용자가 이미 폐기한 정책이 그대로 남아 있음
- **위치**: sections[1] "누적 학습데이터 2카드" 설명 "UI/UX §4-11 회귀 방지: ProgressBar·progressbar role·% 텍스트 절대 미노출 — 숫자 카드만."
- **정의서 서술**: 누적 카드 영역에 `%` 텍스트를 절대 노출하면 안 된다는 회귀 방지 규칙을 근거(UI/UX §4-11)와 함께 명시.
- **실제**: 2026-08-03 사용자가 이 규칙을 **명시적으로 폐기**했다 — 근거로 인용되던 "UI/UX §4-11" 자체가 레포에 실존하지 않는 문서(코드 주석·테스트에만 있던 자체 규칙)임이 확인됐고, 현재는 승인/전체 병기 정책에 따라 각 누적 카드 아래 "검수완료 기준 · 전체 N장 (완료율 24%)" 형태로 **의도적으로 % 텍스트를 노출**한다(살아있는 가드는 `role="progressbar"`/`<progress>` 미렌더로 범위가 좁혀짐). 정의서가 이미 폐기된 옛 규칙을 근거 문서까지 포함해 그대로 서술하고 있다.
- **근거**: `klid-label-frontend/src/components/common/ApprovedRatioNote.tsx`(완료율 텍스트 렌더) · auto-memory `stats-approved-vs-total-separation.md` §"⚠ 폐기된 가드 — 되살리지 말 것"
- **조치 제안**: 해당 문장을 "누적 카드는 주 수치(승인)+ '전체 N (완료율 X%)' 텍스트를 병기한다. 단 `role=progressbar`/`<progress>`/시각적 진행바 요소는 렌더하지 않는다"로 전면 교체.
- **확신도**: high

### [ERR-F11] 누적 카드 주 수치 서술 — SCREEN-011 과 동일 패턴 반복
- **위치**: sections[1] > components[0]/[1]
- **정의서 서술**: "cumulativeImageCount / cumulativeVideoCount 표시"
- **실제**: 주 수치는 `approvedImageCount`/`approvedVideoCount`(검수완료), `cumulative*`는 보조. 라벨도 "이미지 학습데이터 (장)"이 아니라 "누적 이미지"/"누적 영상".
- **근거**: `klid-label-frontend/src/components/stats-overall/OverallStatHeadline.tsx(CUMULATIVE_ITEMS)`
- **조치 제안**: ERR-F01 과 동일 정정.
- **확신도**: high

### [ERR-F12] "처리 현황 5카드 grid-cols-5" — 납품 FE 는 완전히 다른 UI(스택형 진행 바 1개 + 4항목 범례)
- **위치**: sections[2] "처리 현황 5카드" 전체
- **정의서 서술**: "processing 집계를 5개 카드로 표시: 전체/완료(approved)/처리중(inProgress+reviewPending)/실패(rejected)/대기(pending). data-testid=processing-cards, grid-cols-5 고정."
- **실제**: 납품 FE 는 5개의 개별 카드가 아니라 **카드 1개 안에** ①헤더에 "전체 N건" 텍스트 ②가로 스택형 진행 바(완료/처리중/대기/실패 4구간, 폭%) ③4항목 범례 리스트(각 항목에 건수+% 텍스트)로 구성된다 — "전체"는 별도 카드가 아니라 헤더 텍스트이며 세그먼트도 5개가 아니라 **4개**(전체는 세그먼트 값이 아니라 합계). `data-testid="processing-cards"`/`grid-cols-5`는 납품 FE 어디에도 없다(테스트베드에는 정의서 그대로 존재). 이 구조로 정의서만 보고 구현하면 실제 배포 화면과 완전히 다른 레이아웃이 만들어진다.
- **근거**: `klid-label-frontend/src/components/stats-overall/ProcessingSummary.tsx` vs `upload-ui/frontend/src/pages/OverallStatPage.tsx`(`data-testid="processing-cards"`, `grid-cols-5`)
- **조치 제안**: 정본을 납품 FE 로 확정한다면 섹션 설명을 "처리현황 카드 1개(헤더=전체 건수, 스택 바 4구간 완료/처리중/대기/실패, 각 구간 건수+%범례)"로 재작성. 테스트베드가 정본이면 현행 서술 유지.
- **확신도**: high

### [ERR-F13 / STALE] "이벤트 6종 고정" 서술 — SCREEN-011 과 동일 결함
- **위치**: sections[4] "이벤트 유형 분포" 설명 "이벤트 6종 고정 슬롯(FALL/VIOLENCE/...)... 우측 6종 고정 가로막대 리스트(데이터 누락 시에도 6개 li 렌더, UI/UX §4-3)"
- **실제**: ERR-F02 와 동일 — 서버가 내려주는 가변 개수 카테고리를 그대로 순회 렌더한다(주석: "BE 카테고리 분포를 그대로 순회 렌더"). "6개 li 고정 렌더" 근거인 "UI/UX §4-3" 도 §4-11 과 마찬가지로 실존 여부가 의심된다(§4-11 이 이미 허위 인용으로 확인된 전례).
- **근거**: `klid-label-frontend/src/components/stats-overall/EventTypeChartSection.tsx` · `upload-ui/frontend/src/pages/OverallStatPage.tsx`(`EVENT_DIST = approvedEventDistribution.map(...)`)
- **조치 제안**: ERR-F02 와 동일하게 정정. "UI/UX §4-3" 인용의 실존 여부를 별도로 검증(문서 미실존 시 인용 자체 삭제).
- **확신도**: high

---

## SCREEN-030 (공지 목록)

### [ERR-F14 / CONFLICT] "새 공지 작성" 버튼이 모달을 연다는 서술 — 실제는 전용 라우트 이동
- **위치**: sections[0] "페이지 헤더" 설명 "'새 공지 작성' 버튼(NoticeEditModal 호출)"
- **정의서 서술**: 버튼 클릭 시 모달(NoticeEditModal)이 열린다.
- **실제**: 납품 FE 는 `navigate("/notice/new")` 로 **전용 페이지**(`NewNoticePage`)로 이동한다. 라우터에 `/notice/new`(REVIEWER 가드), `/notice/:id/edit`(REVIEWER 가드) 이 각각 별도 라우트로 등록돼 있다 — 모달이 아니라 4번째·5번째 화면이 실제로 존재하는데, LogiCraft 화면 카탈로그에는 SCREEN-030/031 두 건만 있고 이 두 라우트에 대응하는 SCREEN ITEM 이 없다.
- **근거**: `klid-label-frontend/src/components/notice/NewNoticeButton.tsx`(`navigate("/notice/new")`) · `klid-label-frontend/src/routes/index.tsx`(`/notice/new`, `/notice/:id/edit` 라우트) · `klid-label-frontend/src/pages/NewNoticePage.tsx` · `klid-label-frontend/src/pages/EditNoticePage.tsx`
- **조치 제안**: [GAP] "공지 작성 화면"·"공지 수정 화면"을 독립 SCREEN ITEM 으로 신설하거나(권장), 최소한 이 섹션 설명을 "모달"에서 "`/notice/new` 전용 페이지 이동"으로 정정.
- **확신도**: high

### [ERR-F15] 버튼 라벨 텍스트 불일치
- **위치**: sections[0] > components[1]
- **정의서 서술**: "+ 새 공지 작성"
- **실제**: "새 게시글 작성"(Plus 아이콘, "+" 문자 없음, "공지"→"게시글")
- **근거**: `klid-label-frontend/src/components/notice/NewNoticeButton.tsx`
- **조치 제안**: 라벨 텍스트 정정.
- **확신도**: high

### [ERR-F16] 목록 컬럼 "번호" 없음
- **위치**: sections[2] "게시글 테이블" 설명 "번호/제목(고정 배지 amber)/상태(REVIEWER만)/등록일 컬럼"
- **실제**: 납품 FE 컬럼은 제목(+고정 배지)/상태(REVIEWER 한정)/등록일 **3열**뿐이며 번호(순번) 컬럼이 없다.
- **근거**: `klid-label-frontend/src/components/notice/columns/index.tsx(createNoticeColumns)`
- **조치 제안**: "번호" 컬럼 서술 삭제 또는 실제 추가 여부 확인.
- **확신도**: high

### [ERR-F17] 고정 배지 라벨 텍스트
- **위치**: sections[2] 설명 "제목(고정 배지 amber)"
- **실제**: 배지 텍스트는 "고정"이 아니라 **"중요"**(Pin 아이콘 + `variant="warning"`/amber 색상은 일치). SCREEN-031 상세 화면과도 일관되게 "중요"로 렌더된다.
- **근거**: `klid-label-frontend/src/components/notice/columns/index.tsx`
- **조치 제안**: "고정 배지(텍스트: 중요, amber, Pin 아이콘)"로 정정. §4-3 류 문서 재확인 시 이 라벨명 근거도 함께 확인 권장.
- **확신도**: high

### [GAP-F18] 검색 초기화(리셋) 버튼 미서술
- **위치**: sections[1] "검색 필터"
- **정의서 서술**: 검색 필드 select + 검색어 input + 검색 버튼만 나열.
- **실제**: 공용 `FilterBar` 컨벤션상 검색 버튼과 짝을 이루는 **초기화 버튼**이 함께 렌더된다(`isResetEnabled` prop).
- **근거**: `klid-label-frontend/src/components/notice/NoticeFilters.tsx`(`FilterBar onReset`)
- **조치 제안**: 초기화 버튼을 components 목록에 추가.
- **확신도**: medium

### [ERR-F19] 헤더 제목 구성 불일치
- **위치**: sections[0] 설명 "제목 '게시판 / 공지사항'"
- **실제**: 제목("게시판")과 설명 문구("공지사항을 확인합니다.")로 **분리**된 `PageTitle` 패턴이며, "게시판 / 공지사항" 이라는 단일 문자열은 존재하지 않는다.
- **근거**: `klid-label-frontend/src/pages/NoticePage.tsx`
- **조치 제안**: title/description 필드로 나눠 정정.
- **확신도**: medium

---

## SCREEN-031 (공지 상세)

### [CONFLICT-F20] "NoticeEditModal(KLID-AT-SC-032)" 인용이 실제로는 다른 화면을 가리킴
- **위치**: sections[3] "공지 수정 모달 (REVIEWER)" 설명 "NoticeEditModal(KLID-AT-SC-032)."
- **정의서 서술**: 공지 수정 폼을 "KLID-AT-SC-032" 화면으로 인용.
- **실제**: 이 프로젝트 자체 문서(`docs/design-full/D2-사용자인터페이스설계서.md`)의 화면 ID 매핑상 KLID-AT-SC-032 = "게시판 작성/수정 화면"(이 인용과 일치)이지만, **LogiCraft 의 SCREEN-032 ITEM 은 전혀 다른 화면("비식별 신고 관리 화면", route=`/manage/deident-reports`)을 담고 있다.** D2 문서 기준으로는 비식별 신고 관리가 KLID-AT-SC-**033**이다. 즉 LogiCraft SCREEN-032 ITEM 의 번호가 이 프로젝트 고유 KLID-AT-SC 체계와 어긋나 있고, 그 결과 "공지 작성/수정" 화면 자체가 **LogiCraft 에 독립 SCREEN ITEM 으로 존재하지 않는다**(SCREEN-031 안의 인라인 서술이 유일한 출처).
- **근거**: `_raw/SCREEN-032.json`(`route:/manage/deident-reports`, `title:비식별 신고 관리 화면`) · `docs/design-full/D2-사용자인터페이스설계서.md`("게시판 작성/수정(KLID-AT-SC-032)" · "비식별 신고 관리(KLID-AT-SC-033)")
- **조치 제안**: (a) 공지 작성/수정을 위한 독립 SCREEN ITEM 을 신설해 KLID-AT-SC-032 와 정합시키고, (b) 현재 SCREEN-032 ITEM(비식별 신고 관리)의 번호를 KLID-AT-SC-033 체계에 맞게 재검토. LogiCraft ITEM 수정은 이 감사 범위 밖이므로 사용자 논의 후 별도 처리 필요.
- **확신도**: high

### [ERR-F21] 수정/삭제 버튼이 모달을 연다는 서술 — 실제는 전용 페이지/별도 위치
- **위치**: sections[0] "상단 액션 바" > components[1] "수정" 설명 "공지 수정 모달을 엶"
- **실제**: "수정" 버튼은 `navigate(\`/notice/${notice.id}/edit\`)` 로 전용 페이지(`EditNoticePage`)로 이동한다 — 모달이 아니다.
- **근거**: `klid-label-frontend/src/components/notice/NoticeManageActions.tsx`
- **조치 제안**: ERR-F14/CONFLICT-F20 과 함께 정정.
- **확신도**: high

### [ERR-F22] 액션 버튼 배치 구조 불일치 — "상단 액션 바 1그룹" vs 실제 "상단(발행류) + 하단(수정/삭제) 2그룹"
- **위치**: sections[0] "상단 액션 바" 전체 설명
- **정의서 서술**: 수정/발행/발행취소/삭제 4개 버튼이 모두 우측 상단 액션 그룹에 함께 있다고 서술.
- **실제**: 납품 FE 는 레이아웃이 둘로 나뉜다 — ①`BackButton` 아래 우측 정렬로 **발행/발행취소**(`NoticePublishToggle`)만 상단에 배치 ②본문(article) 하단에 `border-t pt-6` 로 구분된 별도 영역에 **수정/삭제**(`NoticeManageActions`)가 배치된다. "하나의 상단 액션 바"로 구현하면 실제 화면과 다른 레이아웃이 된다.
- **근거**: `klid-label-frontend/src/pages/NoticeDetailPage.tsx`
- **조치 제안**: 섹션을 2개로 분리(header: 발행/발행취소, footer: 수정/삭제)해 재서술.
- **확신도**: high

### [ERR-F23] 배지 라벨 텍스트 — "고정" vs "중요"
- **위치**: sections[1] > components[0] "Badge label=고정"
- **실제**: SCREEN-030 과 동일하게 실제 텍스트는 "중요"(Pin 아이콘, amber/warning 은 일치).
- **근거**: `klid-label-frontend/src/components/notice/NoticeDetailArticle.tsx`
- **조치 제안**: ERR-F17 과 함께 정정.
- **확신도**: high

### [GAP-F24] 발행 일시(pubDt) — 타입엔 있으나 실제 UI에 렌더 안 됨
- **위치**: sections[1] > components[3] "게시 메타" 설명 "발행=pubDt(PUBLISHED 일 때)"
- **실제**: API 응답 타입(`NoticeResponse`)에는 `pubDt` 필드가 존재하지만, 상세 화면 어디에도 `notice.pubDt` 를 렌더하는 코드가 없다(작성자/등록/수정만 표시).
- **근거**: `klid-label-frontend/src/api/notice/types.ts`(`pubDt` 필드 존재) · `klid-label-frontend/src/components/notice/NoticeDetailArticle.tsx`(pubDt 미사용, grep 결과 렌더 0건)
- **조치 제안**: 실제로 발행일시를 노출할 계획이면 화면 구현 보완, 아니면 정의서에서 "발행=pubDt" 항목 삭제.
- **확신도**: high

---

## 최중대 3건
1. **CONFLICT-F10** (SCREEN-021) — 사용자가 2026-08-03 이미 폐기하고 근거 문서(UI/UX §4-11)가 레포에 실존하지 않는다고 확인까지 마친 "% 텍스트 절대 미노출" 규칙이, 폐기된 근거 문서명을 인용한 채로 화면정의서에 그대로 남아 있다. 실제 화면은 그 규칙과 정반대로 "완료율 N%" 텍스트를 의도적으로 노출한다.
2. **CONFLICT-F20** (SCREEN-031) — 정의서가 인용한 "NoticeEditModal(KLID-AT-SC-032)"는 이 프로젝트 자체 ID 체계상 맞는 인용이지만, 정작 LogiCraft SCREEN-032 ITEM 은 전혀 무관한 "비식별 신고 관리 화면"을 담고 있다. 공지 작성/수정 화면은 LogiCraft 에 독립 ITEM 이 없다.
3. **ERR-F12** (SCREEN-021) — "처리 현황 5카드 grid-cols-5"로 서술된 섹션이 실제 납품 FE 에서는 완전히 다른 컴포넌트 구조(스택형 진행 바 1개 + 4항목 범례)로 재설계돼 있어, 정의서만 보고 구현하면 실제 배포 화면과 레이아웃이 근본적으로 다르다.
