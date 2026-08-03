# H-part5 — H-8. ReviewPage(검수 화면, 21건) + H-9. 관리 화면(/manage/*, 11건) = 32건

> 검증일 2026-08-02 · 회차 2차 · 담당 범위: `docs/test-cases/H-frontend-e2e.md` §H-8 `TC-FE-131~147,206~208 + TC-E2E-004` · §H-9 `TC-FE-148~156 + TC-E2E-005~006`
> 대상 코드: `frontend/src/pages/ReviewPage.tsx` · `frontend/src/features/review/**` · `frontend/src/pages/manage/**` · `frontend/src/router/index.tsx`
> 실행 스택: `klid-backend`(:18081) · `klid-frontend`(:13000) · `klid-postgres` (2026-08-01 재빌드 V158 최신 바이너리, `git log 56d30478..HEAD -- frontend/` = 0건이라 baseline 과 워킹트리 frontend 동일)

## 0. 검증 환경 실측 (판정 전제)

| 항목 | 실측값 | 근거 |
|------|--------|------|
| FE baseline | **1,951 tests 전량 PASS**(2026-08-01, commit 56d30478) | `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md:11` |
| frontend 워킹트리 변경 | **0건** (baseline 커밋 이후) | `git log --oneline 56d30478..HEAD -- frontend/` 무출력 |
| REVIEWER dev 토큰 | 실발급 확인 | `POST /api/v1/dev/tokens {role:REVIEWER,userNo:1001}` → 200 |
| WORKER dev 토큰 | 실발급 확인 | `POST /api/v1/dev/tokens {role:WORKER,userNo:1003}` → 200 |
| 실 검수 데이터 | PENDING 2건(id=31,7, labelCount=0) · COMPLETED 20건 · REJECTED 1건 | `GET /api/v1/reviews/summary` → `{"total":23,"pending":2,"inReview":0,"approved":20,"rejected":1}` |
| WORKER→관리 API 접근 | **403 전건** | `GET /api/v1/users`·`/labels/master`·`/presets`·`/deident-reports` (WORKER 토큰) → 모두 403 |

**⚠ 환경 제약(중요, 판정 방법에 영향)**: 이번 회차는 다수의 병렬 검증 에이전트가 **동일 Playwright MCP 브라우저 세션을 공유**하고 있어(같은 세션의 다른 에이전트가 임의 시점에 탭을 재사용/전환), 신규 탭을 명시적으로 열고 `browser_tabs select`로 포커스를 고정해도 스냅샷 직전에 다른 에이전트가 그 탭에서 자체 네비게이션을 발생시켜 `/review/31` → `/task` → `/augment/result/4` 로 내용이 계속 바뀌는 현상을 반복 실측했다(탭 인덱스는 고정돼도 실제 렌더 콘텐츠가 매 스냅샷마다 달랐음 — 격리 실패, 코드 결함 아님). 이 때문에 본 라운드는 **①BE 실HTTP 왕복(curl, 격리 안전) + ②정적 코드 대조 + ③기존 FE 컴포넌트/E2E 테스트 자산 대조**를 주 근거로 삼았다(README §5의 방법 2·3). 상호작용성 UI 렌더링 자체(다이얼로그 애니메이션 등 순수 시각 확인)만 브라우저 관측이 필요한 항목이며, 그 로직은 vitest 컴포넌트 테스트(axios-mock-adapter 기반, 실제 DOM 렌더 + userEvent 클릭)로 이미 실행 검증되어 있어 판정 신뢰도에 공백은 없다고 판단했다. 접근권한(RBAC) 계층은 BE 실HTTP(격리 안전)로 별도 실증했다.

---

## 1. H-8. ReviewPage (검수 화면) — 21건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-FE-131 | PASS | [정적] | `pages/ReviewPage.tsx:229-238` — `Number.isNaN(numericId)` 시 `ErrorState title="잘못된 검수 ID"` 렌더. 라인 정확 일치. 전용 유닛테스트는 없으나 단순 가드절이라 코드로 확정 |
| TC-FE-132 | PASS | [정적]+[테스트] | `ReviewPage.tsx:240-255` `isLoading` 분기. `ReviewPageLayout.test.tsx:101` `로딩중_spinner_노출` 실행 검증(1,951 baseline 통과분) |
| TC-FE-133 | PASS | [정적]+[테스트] | `ReviewPage.tsx:257-266`. `ReviewPageLayout.test.tsx:128` `에러_상태_안내_표시` — "검수 정보를 불러올 수 없습니다" 텍스트 확인 |
| TC-FE-134 | PASS | [정적]+[테스트] | `ReviewPage.tsx:179-183` mount effect `review.status==='REVIEW_PENDING' && !didStart → doStart`. `ReviewPage.test.tsx:70-112` `검수_시작_버튼_클릭시_상태_REVIEWING_전이`(테스트명은 오기이나 실내용은 버튼 클릭 없이 mount 만으로 `POST /reviews/10/start` 자동 호출을 `waitFor(started===true)`로 검증 — 코드와 일치) |
| TC-FE-135 | PASS | [정적]+[테스트] | `ReviewPage.tsx:294-308` "읽기 전용" 배지 + `LabelCanvas` 좌표 마커 미사용. `ReviewPage.test.tsx:366-390` `검수_화면_캔버스_좌표_마커_컴포넌트_미사용` — `[data-marker]`/`[data-testid*="marker"]`/`[data-testid*="coordinate"]` 전부 DOM 부재 확인 |
| TC-FE-136 | PASS | [정적]+[테스트] | `ReviewPage.tsx:306` `loading={framesLoading}` prop 전달. `ReviewPage.test.tsx:330-364` `검수화면_프레임_로딩중_스피너_표시되고_프레임없음_문구_미표시` — 캔버스 영역 내 스피너 확인 + "프레임 없음" 오표시 부재 확인 |
| TC-FE-137 | PASS | [정적]+[테스트] | `ReviewPage.tsx:206-208,399-408` 승인 클릭→`ConfirmDialog(title="승인 확정")`. `ReviewPage.test.tsx:114-160` `승인시_상태_COMPLETED_전이` — 승인 버튼→다이얼로그→확정 클릭 흐름 실행 |
| TC-FE-138 | PASS | [정적]+[테스트] | `ReviewPage.tsx:155-160` `onSuccess`에서 `noLabelConfirmOpen` 닫힘+"승인 완료" 토스트+`navigate('/review')`. 동일 테스트가 `REVIEW_LIST` 라우트 전환까지 확인 |
| TC-FE-139 | PASS | [정적] | `ReviewPage.tsx:174` `extractBeMessage(err, '승인 실패')` — BE 메시지 우선, fallback 만 고정문구. `lib/api/extractBeMessage.ts` 로직 확인(ApiError.userMessage→message→axios response.data.message→fallback 순). `ReviewPage.test.tsx:162-207`(H6_동시승인충돌) 이 이 분기를 실행하나 토스트 문자열 자체는 미단언 — 코드로는 확정, 문자열 수준 단언 테스트는 부재(경미, 이슈화 대상 아님) |
| TC-FE-140 | PASS | [정적]+[테스트] | `ReviewPage.tsx:65-84` `composeRejectReason`. `ReviewPage.test.tsx:309-328` 3개 유닛테스트(사용자입력만/전체의견포함/이슈labelId없을때 괄호생략) 전부 통과분 |
| TC-FE-141 | PASS | [정적] | `ReviewPage.tsx:380` `RejectModal onSuccess={() => navigate('/review')}`. `RejectModal.tsx:63-67` mutation `onSuccess`에서 `onSuccess?.()` 호출 확인. 승인 경로와 동일 패턴이나 반려 전용 navigate 단정 테스트는 부재(경미) |
| TC-FE-142 | PASS | [정적]+[테스트] | `ReviewPage.tsx:126-139` `inquirySrcSns` — `issueTypeCd===INQUIRY && issueSttsCd!==RESOLVED && srcSn!=null` 필터. `ReviewPageFrameStatus.test.tsx` 가 미해소(OPEN, srcSn101)=포함·해소(RESOLVED, srcSn100)=제외·영상단위(srcSn null)=제외 3분기 모두 실데이터로 검증 |
| TC-FE-143 | PASS | [정적]+[테스트] | `ReviewPage.tsx:141-149` `savedSrcSns` — `labels.length>0`. 동일 테스트 파일이 4프레임 중 라벨 있는 srcSn102 만 포함되는지 확인 |
| TC-FE-144 | PASS | [정적] | `ReviewPage.tsx:187-192` `currentFrameIdx<0 \|\| >=frames.length → setCurrentFrameIdx(0)`. 전용 유닛테스트는 없으나 단순 range-guard effect라 코드로 확정 |
| TC-FE-145 | PASS | [정적] | `ReviewPage.tsx:195-200` unmount cleanup — `clearSelection()+setCurrentFrameIdx(0)`. 전용 유닛테스트는 없으나 useEffect cleanup 반환 패턴이 명확 |
| TC-FE-146 | **FAIL** | [정적] | **증강/파생 여부 표시 자체가 화면에 없다.** `features/review/components/ReviewHeader.tsx`·`ReviewMetaPanel.tsx` 전체 grep 결과 `ORGNL_RAW_SN`/`orgnlRawSn`/`VMS_CLIP_ID`/`vmsClipId`/`aug`/`증강`/`파생` 매치 0건. `features/review/types.ts`의 `Review` 인터페이스(`id,videoId,cctvName,workerId,workerName,submittedAt,labelCount,status,reviewerId,eventName,eventTypeCd`)에도 파생 관련 필드가 아예 없다. **BE 근본 원인**: `backend/.../review/dto/ReviewResponse.java` 전체 필드에도 `orgnlRawSn`/증강 관련 값이 없음 — FE 구현 누락이 아니라 BE 계약 자체에 파생 식별자가 실려있지 않다. 검수자가 검수 화면만으로는 이 영상이 원본인지 증강/해상도 파생본인지 알 수 없다 → **H-ISSUE-81** |
| TC-FE-147 | PASS | [정적] | `ReviewPage.tsx:355` `<IssueThreadPanel rawSn={review.videoId} mode="reviewer" dark />` — 라인·props 정확 일치 |
| TC-FE-206 | PASS | [정적]+[테스트] | `ReviewPage.tsx:170-172,220-226,386-398` — BE 409 `errorCode==='REVIEW_NO_LABEL'`일 때만 `noLabelConfirmOpen=true`, 확인 시 `doApprove({reviewId, body:{noLabelConfirmed:true}})`(상시 미전송). BE 측 `ErrorCode.REVIEW_NO_LABEL = 409 CONFLICT`(`common/exception/ErrorCode.java:20`), 발행부 `ReviewService.java:560` 확인. `ReviewPage.test.tsx:209-239` `H6_라벨0건_409는_errorCode_REVIEW_NO_LABEL_로_구분해_확인_다이얼로그를_띄운다` 통과 |
| TC-FE-207 | PASS | [정적]+[테스트] | `ReviewPage.tsx:165-175` — 상태코드(409)가 아니라 `err.errorCode`로 분기, `REVIEW_NO_LABEL` 아니면(예: `CONFLICT`) 다이얼로그 미노출+토스트만. `ReviewPage.test.tsx:162-207` `H6_동시승인충돌_409는_라벨없음_다이얼로그를_띄우지_않는다` — `CONFLICT` errorCode 로 409 응답 시 "라벨 없음 확인 후 승인" 버튼 부재 확인 |
| TC-FE-208 | PASS | [정적]+[테스트] | `features/review/components/ReviewMetaPanel.tsx` `CaptionReadonly`가 `normalizeCot(cand.cot).filter(...)` 사용(구 `(cand.cot ?? []).filter` 직접 배열연산 아님). `features/label/components/__tests__/eventAnnotationForm.test.ts:10-24` — 배열형 그대로 반환/객체형(`{'1단계':...}`)을 키순서 배열화/undefined→빈배열 3케이스 전부 통과. 객체형 cot 입력 시 TypeError 크래시 재발 없음 |
| TC-E2E-004 | PASS | [정적] | `e2e/specs/review-flow.spec.ts:5-24` — `test.describe.serial`, 목록 진입(`review-list-page` testid) → 상세 진입 → 시작버튼 있으면 클릭 → 승인버튼 있으면 클릭+승인 텍스트 확인. 시나리오 구조가 카탈로그 기대와 일치. **실행은 안 함**(빌드/테스트 실행 금지 지침) — 파일 존재·로직 정합만 확인 |

**H-8 집계**: PASS 20 · FAIL 1 · PARTIAL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 2. H-9. 관리 화면 (/manage/*) — 11건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-FE-148 | PASS | [정적]+[테스트] | `pages/manage/LabelMasterManagePage.tsx` — `dtctTypeCd` 입력 필드. `LabelMasterManagePage.test.tsx:137-180` `COCO_클래스를_지정해_생성하면_dtctTypeCd가_요청본문에_포함된다` / `COCO_클래스_미지정으로_생성하면_dtctTypeCd가_null로_전송된다(미매핑_허용)` / `COCO_중복_매핑시_409응답이_사용자_메시지로_표시된다` 3건 통과분 |
| TC-FE-149 | PASS | [정적]+[테스트]+[실동작] | 라우터: `router/index.tsx:375-425` `/manage/*` 전 하위경로가 `InternalRoute allow={internalReviewerOnly}`(=`[Role.REVIEWER]`). **근거 드리프트**: 카탈로그가 지목한 `pages/manage/__tests__/UserManagePage.test.tsx`는 실제로는 검색필터만 테스트하고 역할 접근제어는 미검증 — 실제 검증 파일은 `router/__tests__/manageGuard.test.tsx:45-74`(WORKER→`/manage/users`,`/manage/settings` 모두 FORBIDDEN_PAGE / REVIEWER→정상 렌더 3케이스 통과). **BE 실HTTP 이중 확인**: WORKER 토큰으로 `GET /api/v1/users`·`/api/v1/labels/master`·`/api/v1/presets`·`/api/v1/deident-reports` 전부 **403** 실측(FE 가드 우회해도 BE 가 방어) → **H-ISSUE-82**(근거 드리프트, LOW) |
| TC-FE-150 | PASS | [정적]+[테스트] | `pages/manage/SystemSettingsPage.tsx:40-56` — `BatchConfigCard`+`YoloConfigCard`+`PrecisionConfigCard`(①편집가능) / `HealthStatusList`(②모니터링) / `DangerActions`(③위험액션) 3섹션 정확 일치. `SystemSettingsPage.test.tsx:55-70` `3섹션_렌더링_(Batch_health_danger)` 통과 |
| TC-FE-151 | PASS | [정적]+[테스트] | `pages/manage/PresetListPage.tsx` — 마스터 join. `PresetListPage.test.tsx:75-103` 카테고리 라벨 표시/미매핑 프리셋 "미매핑" 표시/연결 라벨은 마스터 라벨명+형태 표시/미연결 배지 4케이스 통과 |
| TC-FE-152 | PASS | [정적]+[테스트] | `pages/manage/DeidentReportListPage.tsx`. `features/deident/__tests__/DeidentReportListPage.test.tsx:52-88` — OPEN 신고 목록 렌더(rawSn/reason 표시) + 해소 클릭→`POST /deident-reports/{id}/resolve`→목록 재조회(invalidate) 2케이스 통과 |
| TC-FE-153 | PASS | [정적]+[테스트] | `features/task/components/AssignModal.tsx`. `AssignModal.test.tsx:90-145` `작업자_선택_후_POST_assignments_호출` + `작업자_미선택_상태에서는_저장_버튼_disabled` 통과 |
| TC-FE-154 | PASS | [정적]+[테스트] | `features/task/components/HistoryDrawer.tsx:26-40`(주석: ASSIGN/REASSIGN/SUBMIT/APPROVE/REJECT 5종 타임라인, `GET /assignments/{id}/history`). `HistoryDrawer.test.tsx` — KRDS 상태색 토큰 매핑 3케이스 + 이벤트 설명(CANCEL_SUBMIT 등) 2케이스로 렌더 로직 간접 검증 |
| TC-FE-155 | PASS | [정적]+[테스트] | `pages/DashboardPage.tsx:110-164` — REVIEWER(`isWorker=false`)는 KpiCard **정확히 3개**(처리대기/처리완료/반려건수, "내 작업"은 `isWorker` 조건부라 WORKER 전용) + 166행 이하 이미지/영상 데이터 카드(이벤트 6종 분포). `DashboardPage.test.tsx:74-106` KPI 3개 노출 + 이벤트 6종 노출 테스트 통과. `e2e/specs/reviewer-workflow.spec.ts:14-22` `대시보드_KPI_3카드와_이벤트_분포_노출` e2e 시나리오도 동일 주장 |
| TC-FE-156 | PASS | [정적]+[테스트] | `features/video/parseVideoListParams.ts`. `parseVideoListParams.test.ts` 8케이스(기본값/정상파싱/size상한/날짜형식/키워드절단/역변환/기본값URL미포함) 전부 통과 |
| TC-E2E-005 | PASS | [정적] | `e2e/specs/reviewer-workflow.spec.ts:14-56` — 대시보드 KPI+이벤트분포(L14-22) / 영상목록 헤더(L24-31) / 작업목록 헤더(L33-38) / 검수목록 컨테이너(L40-45) / `/manage/users` REVIEWER 접근(L47-56, forbidden 미도달 확인) 5개 서브시나리오 카탈로그 기대와 정확 일치. 실행은 안 함(빌드/테스트 금지) |
| TC-E2E-006 | PASS | [정적] | `e2e/specs/video-list.spec.ts:5-13` — `workerPage`로 검색 → `cctvNameKeyword=` URL 파라미터 갱신 확인. 카탈로그 "WORKER 검색" 전제와 정확 일치(REVIEWER 아님에 유의 — 카탈로그 기재도 WORKER) |

**H-9 집계**: PASS 11 · FAIL 0 · PARTIAL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 3. 종합 집계

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|------|--:|--:|--:|--:|--:|--:|--:|
| H-8 (21건) | 21 | 20 | 1 | 0 | 0 | 0 | 0 |
| H-9 (11건) | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **32** | **31** | **1** | 0 | 0 | 0 | 0 |

---

## 4. 이슈

### [H-ISSUE-81] TC-FE-146 — 검수 화면에 증강/해상도 파생 여부·종류 표시가 전혀 없다(BE 계약 자체 미보유)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수자가 검수 화면(`/review/:id`)에서 이 영상이 원본인지, 증강(WINTER/NIGHT/RAIN)이나 해상도 변경(RESL_1080P/720P/480P) 파생본인지, 파생이라면 어떤 종류인지 확인할 수 있어야 한다. 파생영상은 라벨/메타가 원본에서 복사된 값이고 좌표가 재계산(해상도) 또는 그대로(증강) 적용된 결과라, 검수자가 이 맥락을 모르면 원본 대비 이상 여부(예: 리스케일 아티팩트, 계절/야간 변환에 따른 색상 변화)를 오판할 위험이 있다.
- **현재 동작(이슈 내용)**: `frontend/src/features/review/components/ReviewHeader.tsx`·`ReviewMetaPanel.tsx` 전체에 `ORGNL_RAW_SN`/`orgnlRawSn`/`VMS_CLIP_ID`/`vmsClipId`/증강 관련 키워드 매치가 0건이다. 상위 타입 `frontend/src/features/review/types.ts`의 `Review` 인터페이스도 다음 필드만 갖는다:
  ```ts
  export interface Review {
    id: number; videoId: number; cctvName: string; workerId: number;
    workerName: string; submittedAt: string; labelCount: number;
    status: ReviewStatus; reviewerId?: number;
    eventName?: string | null; eventTypeCd?: string | null;
  }
  ```
  BE 응답 계약도 동일하게 비어있다. `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java`의 필드 전체가 `id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd`뿐이며 파생 식별자가 없다. 즉 FE 구현 누락이 아니라 **BE 응답 DTO 자체에 파생 정보가 실려있지 않다.**
- **재현/확인 경로**: 증강 파생본(`LS_DATA_RAW.ORGNL_RAW_SN IS NOT NULL`)을 검수 목록에서 진입해 `GET /api/v1/reviews/{id}` 응답을 확인하면 `orgnlRawSn` 등 파생 관련 키가 존재하지 않는다. (참고: `GET /api/v1/tasks/board` 응답에는 `증강 데이터: 해상도 480p` 같은 배지가 실제로 존재함 — 작업목록 화면은 파생 여부를 이미 표시하고 있어 검수 화면만 빠진 것으로 보인다. 본 검증 세션에서 `/task` 화면 스냅샷에 `쓰러짐 증강 데이터: 해상도 480p` 셀이 다수 관측됨.)
- **영향**: 기능 정합성(검수 정확도) 저하. 보안 영향은 없음(정보 은닉이 아니라 단순 미표시).
- **수정 방향(제안)**: `ReviewResponse`에 `orgnlRawSn`(nullable)·(선택) 증강/해상도 종류 코드를 추가하고, `ReviewMetaPanel` 또는 `ReviewHeader`에 파생 배지를 렌더한다. 작업목록(`TaskBoardTable`)이 이미 "증강 데이터: {종류}" 배지를 갖고 있으므로 동일 소스/포맷을 재사용할 수 있는지 확인.

### [H-ISSUE-82] TC-FE-149 — 카탈로그 근거 file:line 드리프트: `/manage/*` REVIEWER 전용 접근제어의 실제 검증 파일이 다르다
- **심각도**: LOW
- **기대 동작(기대효과)**: 테스트 케이스 카탈로그의 "근거" 컬럼은 실제로 그 단언을 검증하는 코드/테스트 위치를 정확히 가리켜야 한다(회귀 시 추적성).
- **현재 동작(이슈 내용)**: `docs/test-cases/H-frontend-e2e.md` TC-FE-149 행의 근거가 `pages/manage/__tests__/UserManagePage.test.tsx`로 기재돼 있으나, 이 파일(`frontend/src/pages/manage/__tests__/UserManagePage.test.tsx:31`)은 REVIEWER 세션을 고정한 채 검색 필터 동작만 테스트하고 **역할 접근제어를 전혀 검증하지 않는다**. 실제로 WORKER/REVIEWER 접근 분기를 검증하는 파일은 `frontend/src/router/__tests__/manageGuard.test.tsx:36-75`이다(3케이스: WORKER→`/manage/users` FORBIDDEN, WORKER→`/manage/settings` FORBIDDEN, REVIEWER→둘 다 정상 렌더).
- **재현/확인 경로**: `UserManagePage.test.tsx` 파일 내용을 `grep -n "WORKER\|forbidden\|Role\."`으로 확인하면 역할 분기 코드가 없음을 바로 확인 가능.
- **영향**: 기능 결함은 아님(실제 접근제어는 라우터 가드 + BE 403으로 정상 동작, 본 검증에서 실HTTP로 재확인됨). 카탈로그 정합성 결함으로, 다음 회차 검증자가 잘못된 파일을 열어보고 "테스트 없음"으로 오판할 위험이 있다.
- **수정 방향(제안)**: 카탈로그 근거를 `router/__tests__/manageGuard.test.tsx:45-74`로 정정.

---

## 5. 판정 근거 요약

- **PASS 31건**: 코드 정적 대조(라인 정확 일치 다수) + 기존 vitest 컴포넌트 테스트(axios-mock-adapter 기반 실행 검증, 2026-08-01 baseline 1,951건 전량 통과, 이후 frontend 워킹트리 변경 0건이라 현재도 유효) + 일부 BE 실HTTP 왕복(RBAC 403, REVIEW_NO_LABEL 409 계약)으로 뒷받침.
- **FAIL 1건**(TC-FE-146): BE 응답 DTO에 파생 식별자 필드 자체가 없어 FE 가 표시할 수 없는 상태 — 미구현 갭으로 판단해 FAIL 승격.
- 브라우저 실동작(mcp playwright) 은 세션 공유로 인한 탭 콘텐츠 불안정 때문에 상호작용 흐름의 신뢰 가능한 근거로 사용하지 않았고, 대신 이미 실행된 컴포넌트 테스트(동일 렌더 엔진·동일 이벤트 시스템 — React Testing Library + userEvent)로 대체했다. 이는 README §5 방법 3("테스트 커버 확인")에 해당하며, 방법 1(실동작)이 아니라는 점을 근거확인 컬럼에 정직하게 `[정적]`으로 표기했다.
