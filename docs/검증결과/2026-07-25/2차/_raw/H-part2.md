# H 클러스터 part2 (H-6·H-7·H-8) 2차 검증 결과

> 검증 대상: `docs/test-cases/H-frontend-e2e.md` H-6(20)·H-7(5)·H-8(21) = 46건
> 방법: 소스 실측(Read/Grep) + BE 실호출(dev 토큰, 라이브 DB) + 테스트 커버 확인(baseline 대조, 실행 안 함)
> 폐기(취소선) 행: 이 3개 섹션에는 없음(전건 검증 대상)
> 환경: frontend dev서버 :13000(Vite), backend :18081(HEAD `ca3c712b`). stack-bringup.md §2의 backend 11커밋 뒤처짐은 이 3섹션(MarkingPage/BatchStageIndicator/ReviewPage — 경로순회·심링크·배치 트랜잭션 이슈와 무관한 화면)에는 실질 영향 없음.
> ⚠ 검증 도중 DB 데이터가 stack-bringup.md/pipeline-drive.md 기록과 달라져 있었다(다른 에이전트의 동시 파이프라인 구동) — rawSn=123 은 소멸, 대신 rawSn=126~133 이 살아있는 실측 참조였다. 아래 실호출은 이 최신 데이터로 수행.

## 집계

| 섹션 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| H-6 | 20 | 20 | 0 | 0 | 0 | 0 | 0 |
| H-7 | 5 | 5 | 0 | 0 | 0 | 0 | 0 |
| H-8 | 21 | 19 | 1 | 0 | 1 | 0 | 0 |
| **합계** | **46** | **44** | **1** | **0** | **1** | **0** | **0** |

- FAIL 1건(TC-FE-146) — ReviewPage 에 증강여부/종류 표시가 실제로는 존재하지 않음(카탈로그 근거 파일 자체에 관련 코드 0건). 상세는 이슈 상세 참조.
- BLOCKED 1건(TC-E2E-004) — `e2e/specs/review-flow.spec.ts` 는 Playwright 스펙이라 vitest 제외 대상(`vitest.config.ts:20`)이며 이 세션엔 브라우저 자동화 도구가 없음. 반면 TC-E2E-003(`pages/__tests__/MarkingPage.test.tsx`) 은 RTL(jsdom) 컴포넌트 테스트로 baseline(1691건)에 포함돼 실행·통과가 확인되므로 PASS 처리(H-part1 의 TC-E2E-002 선례와 동일 판단 기준).
- H-7(BatchStageIndicator)은 실 BE 응답으로 직접 대조했다 — 아래 별도 절 참조. 드리프트 없음.

## ★BE↔FE 상수 드리프트 대조 (STAGE_ORDER 등)

**결론: 드리프트 없음 — 오히려 "드리프트가 구조적으로 불가능하게" 재설계돼 있다.**

- FE `components/common/BatchStageIndicator.tsx` 에는 `STAGE_ORDER`라는 이름의 상수가 **존재하지 않는다**(전체 저장소 grep 0건). 대신 `STAGE_LABEL`(name→한글라벨 매핑)만 있고, 렌더링은 `stages.map(...)`로 **BE 가 내려준 배열 순서를 그대로** 사용한다(정렬·재배치 로직 없음, `BatchStageIndicator.tsx:70`).
- 실 BE 응답 대조: `GET /api/v1/videos/133`(REVIEWER 토큰, 실측 rawSn=133, 배치 전단계 DONE)의 `stages` 필드가 정확히 다음 순서로 왔다.
  ```json
  [{"name":"DEIDENTIFY","status":"DONE"},{"name":"MARKING","status":"DONE"},{"name":"VLM","status":"DONE"},
   {"name":"FRAME_EXTRACT","status":"DONE"},{"name":"YOLO","status":"DONE"},{"name":"SAM2","status":"DONE"},
   {"name":"INTERPOLATE","status":"DONE"}]
  ```
  이는 CLAUDE.md 가 선언한 canonical 순서(`DEIDENTIFY → MARKING → VLM → FRAME_EXTRACT → YOLO → SAM2 → INTERPOLATE`)와 정확히 일치한다.
- FE `STAGE_LABEL` 키 7종(`DEIDENTIFY/MARKING/VLM/FRAME_EXTRACT/YOLO/SAM2/INTERPOLATE`)이 이 실측 7종과 1:1 대응하며, `YOLO→"AI 탐지"`/`SAM2→"AI 분할"`로 기술 모델명이 화면에 새지 않는다(`BatchStageIndicator.tsx:21-22`, FE 문구 규칙 준수). 미매핑 코드는 `"처리중"` 폴백(`:26-28`)이라 향후 BE 가 8번째 단계를 추가해도 크래시 없이 안전.
- 증강 파생 영상(rawSn=129, `derivative:true`)의 `stages` 는 `[]`(빈 배열) — `BatchStageIndicator` 는 이 경우 `null` 반환(`:66`, TC-FE-126)로 렌더 자체를 생략해 상위 배지로 폴백한다. 실측·소스 일치.
- `features/video/types.ts:103-108` 의 `BatchStageStatus`(`DONE|PROGRESS|PENDING|FAIL`)도 BE `StageStatusDto` 와 1:1 정합 주석이 있고, 실측 응답의 `status` 값(`"DONE"`)과 타입이 일치.

## H-6 결과표 (MarkingPage, 20건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-110 | 잘못된 rawSn 에러 | PASS | [정적] MarkingPage.tsx:154-156 정확 일치 | |
| TC-FE-111 | 비식별 미완료 진입 차단(백스톱) | PASS | [정적][실동작] MarkingPage.tsx:158-173 정확 일치, `MarkingPage.test.tsx` `비식별_미완료_영상_직접진입시_마킹차단_백스톱_안내` 통과(baseline) | |
| TC-FE-112 | 스트림 항상 비식별본('N'/'F' 차단) | PASS | [정적][실동작] `features/video/types.ts:isMarkingBlocked` 로직 확인 + **실 HTTP 검증**: rawSn=133(`deIdntfYn='F'`)에 `GET /v1/videos/133/stream-url` 호출 → `404 {"errorCode":"NOT_FOUND","message":"비식별 처리 미완료"}` 실측 확인(원본 노출 없음) | |
| TC-FE-113 | Space → 현재시각 마킹 | PASS | [정적] MarkingPage.tsx:139-141 정확 일치 | |
| TC-FE-114 | Del/Backspace → 선택 마크 삭제 | PASS | [정적] MarkingPage.tsx:142-144 정확 일치 | |
| TC-FE-115 | Enter → 제출 | PASS | [정적] MarkingPage.tsx:145-147 정확 일치 | |
| TC-FE-116 | INPUT/TEXTAREA/SELECT 포커스 시 단축키 억제 | PASS | [정적] MarkingPage.tsx:136-137 정확 일치 — 3종 태그 모두 조기 return 확인 | 키보드 이벤트 전용 단위테스트는 없음(로직은 `e.target.tagName` 순수 분기라 정적 판정으로 충분) |
| TC-FE-117 | AUTO intervalFrames<1 제출 차단 | PASS | [정적] MarkingPage.tsx:118-119 정확 일치 | |
| TC-FE-118 | MANUAL 마크 0건 제출 차단 | PASS | [정적] MarkingPage.tsx:124-125 정확 일치 | |
| TC-FE-119 | 제출 중복 방지(Enter 연타) | PASS | [정적][실동작] MarkingPage.tsx:116 정확 일치, `MarkingPage.test.tsx` `마킹_제출_pending_중_Enter_재호출시_추가_POST_미발생` 통과 | |
| TC-FE-120 | 제출 성공 → clearMarks+/task | PASS | [정적][실동작] MarkingPage.tsx:61-77 정확 일치, `MarkingPage.test.tsx` `마킹_제출_성공시_토스트_표시_및_task_목록으로_이동` 통과 | |
| TC-FE-121 | 이벤트 유형 없는 영상 실패 토스트 | PASS | [정적][실동작] MarkingPage.tsx:81-89 정확 일치, `MarkingPage.test.tsx` `이벤트유형없는영상_400응답시_에러토스트_표시_및_이동안함` 통과 | |
| TC-FE-122 | 스트림 401 만료 1회 재발급 | PASS | [정적] MarkingPage.tsx:49-58 정확 일치 — `streamRetriedRef` 로 1회 제한, finally 에서 재허용(무한루프 방지) 로직 확인 | 이 분기(onSrcError 401 재시도) 전용 테스트는 없음(`I3` 테스트는 최초 서명 URL 주입만 검증) — 테스트 커버 갭 |
| TC-FE-123 | 영상 변경 시 마크 reset+duration fallback | PASS | [정적] MarkingPage.tsx:92-97 정확 일치 | |
| TC-FE-124 | 배치단계 인디케이터 stages 있으면 노출 | PASS | [정적] MarkingPage.tsx:181-187 정확 일치 — `videoDetail?.stages && length>0` 가드 확인 | |
| TC-FE-125 | 마크 목록 칩 선택 하이라이트 | PASS | [정적] MarkingPage.tsx:221-241 정확 일치 | |
| TC-FE-203 | frameIndex 는 서버 fps 로 계산 | PASS | [정적][실동작] `features/marking/markingFps.ts:21-31` 정확 일치(`resolveMarkingFps`), `VideoPlayer.tsx:13,54-55` 정확 일치. `markingFps.test.ts` 5건(폴백30·25fps 끝구간·30fps하드코딩 회귀재현·29.97fps·상한밖거부) 전부 통과. 실측 rawSn=133 `fps=29.97002997002997` 로 소수 fps 도 그대로 전달됨을 실 API로 확인 | |
| TC-FE-204 | 마킹 저장됐으나 배치 미시작 안내 | PASS | [정적] MarkingPage.tsx:63-75 정확 일치 — `batchTriggered===false` 시 error 토스트(성공 문구 아님)+navigate 확인. BE `MarkingResponse.java` 에 `batchTriggered` 필드 실존 확인(grep) | 이 분기 전용 FE 테스트는 없음(테스트 커버 갭) — 소스 로직 자체는 명확 |
| TC-FE-205 | 배속 6단(0.25/0.5/1/1.5/2/4) 이산 선택 | PASS | [정적][실동작] `VideoPlayer.tsx:34`(`SPEED_OPTIONS`)·`:46-49`(changeSpeed)·`:125-140`(렌더) 정확 일치, 기본 `playbackRate=1`(:42), `min-h-11` 확인(:133). `VideoPlayer.test.tsx` `배속_버튼_6개_모두_렌더링됨`·`초기_배속은_1x_활성상태`·`배속_버튼_클릭시_playbackRate_변경` 3건 통과 | UNCERTAINTIES #23 확정대로 자유 입력 없어 클램프 케이스 불성립 — 그렇게 판정 |
| TC-E2E-003 | MarkingPage 통합(스트림/배속/단축키) | PASS | [실동작] `pages/__tests__/MarkingPage.test.tsx` 는 RTL(jsdom) 컴포넌트 테스트이며 `e2e/**` 가 아니라 vitest baseline(1691건 GREEN)에 포함 — 실행·통과 증거 있음(H-part1 TC-E2E-002 와 동일 판단 기준, Playwright 아님) | |

## H-7 결과표 (BatchStageIndicator, 5건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-126 | stages 비면 null 렌더(하위호환) | PASS | [정적][실동작] BatchStageIndicator.tsx:66 정확 일치. 실측 rawSn=129(증강 파생, `derivative:true`)의 `stages=[]` 확인(§ 상수 드리프트 대조 참조). 테스트 `stages가_빈배열이면_아무것도_렌더하지_않는다_배지폴백` 통과 | |
| TC-FE-127 | 7종 단계명 매핑(DEIDENTIFY~INTERPOLATE) | PASS | [정적][실동작] BatchStageIndicator.tsx:16-24 정확 일치, 실측 rawSn=133 응답 7종과 1:1 대응 확인. 테스트 `BE_name과_FE_라벨키가_일치해_status_룩업이_정상_렌더된다` 통과 | |
| TC-FE-128 | 미지 코드 폴백 "처리중"(기술코드 미노출) | PASS | [정적] BatchStageIndicator.tsx:26-28,81 정확 일치 | |
| TC-FE-129 | BE 배열 순서 그대로(FE 순서 가정 없음) | PASS | [정적][실동작] BatchStageIndicator.tsx:12-15(주석),70(map) 정확 일치. FE 코드 전체에 `STAGE_ORDER` 상수 0건(grep) — 재정렬 로직 자체가 없어 드리프트가 구조적으로 불가능. 테스트 `stages가_있으면_BE순서대로_단계라벨을_렌더한다` 통과 | |
| TC-FE-130 | 상태별 아이콘(DONE/PROGRESS/FAIL/PENDING) | PASS | [정적] BatchStageIndicator.tsx:30-57 정확 일치 — 4개 상태 분기 + `aria-hidden` 확인 | |

## H-8 결과표 (ReviewPage, 21건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-FE-131 | 잘못된 검수 ID 에러 | PASS | [정적] 실제 조건문은 ReviewPage.tsx:229-238(카탈로그 232-241) — "잘못된 검수 ID" 문구 확인 | 근거 드리프트 -3줄 |
| TC-FE-132 | 로딩 상태 | PASS | [정적] 실제 블록은 ReviewPage.tsx:240-255(카탈로그 247-256) — Spinner label="검수 로딩" 확인 | 근거 드리프트 |
| TC-FE-133 | 에러/review 없음 | PASS | [정적] 실제 블록은 ReviewPage.tsx:257-266(카탈로그 260-269) — "검수 정보를 불러올 수 없습니다" 확인 | 근거 드리프트 -3줄 |
| TC-FE-134 | 진입 시 자동 startReview | PASS | [정적] ReviewPage.tsx:179-183 정확 일치 — `REVIEW_PENDING && !didStart` 가드 확인. 테스트 `검수_시작_버튼_클릭시_상태_REVIEWING_전이`(진입 자동시작 포함) 통과 | |
| TC-FE-135 | 캔버스 읽기 전용(좌표 마커 미사용) | PASS | [정적][실동작] ReviewPage.tsx:294-308(카탈로그 294-311) "읽기 전용" 배지 확인. 테스트 `검수_화면_캔버스_좌표_마커_컴포넌트_미사용` 통과 | 근거 드리프트(끝 -3줄) |
| TC-FE-136 | 프레임 로딩 중 스피너 | PASS | [정적][실동작] ReviewPage.tsx:306 정확 일치(`loading={framesLoading}`). 테스트 `검수화면_프레임_로딩중_스피너_표시되고_프레임없음_문구_미표시` 통과 | |
| TC-FE-137 | 승인 확인 다이얼로그 | PASS | [정적] ReviewPage.tsx:206-208 정확 일치 | |
| TC-FE-138 | 승인 성공 → /review 이동+토스트 | PASS | [정적][실동작] ReviewPage.tsx:155-160 정확 일치. 테스트 `승인시_상태_COMPLETED_전이` 통과 | |
| TC-FE-139 | 승인 실패 토스트(BE 문구) | PASS | [정적] ReviewPage.tsx:174 정확 일치 — `extractBeMessage(err,'승인 실패')` 확인 | |
| TC-FE-140 | 반려 사유 합성(의견+이슈) | PASS | [정적][실동작] ReviewPage.tsx:65-84(`composeRejectReason`) 정확 일치. 테스트 3건(`composeRejectReason_사용자입력만…`/`전체의견포함`/`이슈_라벨id_없을때_괄호_생략`) 통과 | |
| TC-FE-141 | 반려 성공 → /review 이동 | PASS | [정적][실동작] ReviewPage.tsx:380 정확 일치. 테스트 `ReviewPage_반려시_reason_에_검수의견과_pending_이슈_합쳐_전송` 통과 | |
| TC-FE-142 | 프레임 상태색(미해소 문의=빨강) | PASS | [정적][실동작] ReviewPage.tsx:126-139(`inquirySrcSns`) 정확 일치. 테스트 `ReviewPage가_미해소문의_srcSn집합을_산출해_FrameTimeline에_전달` 통과 | |
| TC-FE-143 | 저장 프레임=연두(labels>0) | PASS | [정적] ReviewPage.tsx:141-149(`savedSrcSns`) 정확 일치 | |
| TC-FE-144 | currentFrameIdx 범위 밖 → 0 reset | PASS | [정적] ReviewPage.tsx:187-192(카탈로그 186-192) 정확 일치 | |
| TC-FE-145 | 언마운트 store reset | PASS | [정적] ReviewPage.tsx:195-200(카탈로그 194-200) 정확 일치 | |
| TC-FE-146 | 증강여부/종류 표시 | **FAIL** | [정적] 카탈로그 근거 파일(`ReviewMetaPanel.tsx`·`ReviewHeader.tsx`) 전문을 확인했으나 증강 관련 필드(`orgnlRawSn`/`augType`/`augmented`/`vmsClipId` 파생 표시)가 **0건**. FE `Review` 타입(`features/review/types.ts:9-22`)에도 해당 필드가 없고, BE `ReviewResponse.java`(record 정의) 전체·실 HTTP 응답(`GET /v1/reviews/132`, `GET /v1/reviews?...`)에도 증강 관련 필드가 전무함을 실측 확인. 상세는 이슈 상세(H-ISSUE-21) | **FAIL** — 이슈 등록 |
| TC-FE-147 | 이슈 스레드 검수자 모드 | PASS | [정적] ReviewPage.tsx:355 정확 일치(`mode="reviewer"`), `IssueThreadPanel.tsx` 에 `mode==='reviewer'` 분기 실존 확인 | |
| TC-FE-206 | 라벨 0건 승인 — 명시 확인 다이얼로그 | PASS | [정적][실동작] ReviewPage.tsx:170-172(errorCode 분기),220-227(handleNoLabelApprove),386-397(ConfirmDialog) 정확 일치. BE `ErrorCode.REVIEW_NO_LABEL`(409) 실존 확인. 테스트 `H6_라벨0건_409는_errorCode_REVIEW_NO_LABEL_로_구분해_확인_다이얼로그를_띄운다` 통과 | |
| TC-FE-207 | 409 라도 사유가 다르면 다이얼로그 미노출 | PASS | [정적][실동작] ReviewPage.tsx:161-175 정확 일치 — `err.errorCode==='REVIEW_NO_LABEL'` 문자열 매칭 아닌 errorCode 분기 확인. 테스트 `H6_동시승인충돌_409는_라벨없음_다이얼로그를_띄우지_않는다` 통과 | |
| TC-FE-208 | 검수 메타 cot 객체형 렌더 크래시 없음 | PASS | [정적] `ReviewMetaPanel.tsx` `CaptionReadonly` 가 `normalizeCot(cand.cot).filter(...)` 사용 확인(구 `(cand.cot??[]).filter` 직접 호출 아님) | ReviewPageMetaPanel.test.tsx 에 cot 객체형 전용 케이스는 미확인(0/4건이 event_annotation 값 렌더·시계열·버튼미노출·빈상태) — 테스트 커버 갭 가능성, 소스 로직은 정상 |
| TC-E2E-004 | 검수 플로우: 대기목록→시작→승인 | **BLOCKED** | `e2e/specs/review-flow.spec.ts` 존재 확인(5,12행). Playwright 스펙이며 `vitest.config.ts:20` 에서 `e2e/**` 제외 — baseline(1691건) 미포함, 이 세션엔 브라우저 자동화 도구 없음 | **사유: 브라우저 자동화 필요.** 근거 체인 자체(startReview/approve 각 단계)는 TC-FE-134/137/138 로 개별 PASS 확인됨 |

## 근거 드리프트

전부 "카탈로그 범위가 함수 선언부/주석/공백 줄을 포함해 실제 조건문·JSX 시작줄과 1~7줄 차이" 수준의 경미한 드리프트다. 함수·로직 자체의 오귀속은 없음.

| TC-ID | 카탈로그 근거 | 실제 위치 | 드리프트 |
|---|---|---|---|
| TC-FE-131 | ReviewPage.tsx:232-241 | ReviewPage.tsx:229-238(`if (Number.isNaN(numericId))` 블록) | -3줄 |
| TC-FE-132 | ReviewPage.tsx:247-256 | ReviewPage.tsx:240-255(`if (isLoading)` 블록) | 시작 -7줄 |
| TC-FE-133 | ReviewPage.tsx:260-269 | ReviewPage.tsx:257-266(`if (error \|\| !review)` 블록) | -3줄 |
| TC-FE-135 | ReviewPage.tsx:294-311 | ReviewPage.tsx:294-308(`<main>` 블록, `</main>`까지) | 끝 -3줄 |

## 브라우저 자동화 필요 케이스 목록

### 사유: Playwright e2e 스펙 미실행 (이 세션에 브라우저 자동화 도구 없음)

- **TC-E2E-004** — `e2e/specs/review-flow.spec.ts`: 실제 브라우저로 검수 대기목록 진입→자동 시작→승인까지의 페이지 전환·네트워크 왕복을 확인해야 함. `vitest.config.ts:20` 이 `e2e/**` 를 명시 제외해 1차·2차 baseline 모두 미실행. 근거 체인 자체(각 단계 로직)는 이미 컴포넌트 테스트로 개별 PASS 확인됨 — 실패 예상 지점은 아니나 실제 브라우저 내비게이션 결과는 미확인.

(H-6의 TC-E2E-003 은 실제로는 Playwright 가 아니라 RTL 컴포넌트 테스트라 이 목록에서 제외 — 위 결과표에서 PASS 처리.)

## 이슈 상세

### [H-ISSUE-21] TC-FE-146 — ReviewPage 에 증강여부/종류(파생영상) 표시 기능이 실제로 존재하지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과 "증강여부/종류 표시" — 검수 화면에서 현재 검수 중인 영상이 증강/해상도 파생 영상인지, 어떤 종류(WINTER/NIGHT/RAIN/RESL_*)인지 검수자가 식별할 수 있어야 한다(근거로 명시된 `ORGNL_RAW_SN`/`VMS_CLIP_ID` 파생 판별 — CLAUDE.md "reviewpage-augmented-list-facts" 메모리 항목과 정합하는 동작).
- **현재 동작(이슈 내용)**:
  1. `frontend/src/features/review/components/ReviewMetaPanel.tsx`(244줄 전체 확인) — event_annotation + 시계열 메타만 읽기 표시. 증강/파생 관련 필드·문구 0건.
  2. `frontend/src/features/review/components/ReviewHeader.tsx`(93줄 전체) — cctvName/workerName/submittedAt/frame counter/StatusBadge 만 렌더. 증강 표시 없음.
  3. `frontend/src/features/review/types.ts:9-22` — `Review` 인터페이스에 `orgnlRawSn`/`augType`/`augmented`/`vmsClipId` 필드 자체가 없음:
     ```ts
     export interface Review {
       id: number; videoId: number; cctvName: string; workerId: number;
       workerName: string; submittedAt: string; labelCount: number;
       status: ReviewStatus; reviewerId?: number;
       eventName?: string | null; eventTypeCd?: string | null;
     }
     ```
  4. BE `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java`(117줄 전체) — record 필드에도 증강 관련 값이 없음(id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd 뿐).
  5. 실 HTTP 확인: `GET /api/v1/reviews/132`, `GET /api/v1/reviews?page=0&size=5` (REVIEWER 토큰) 응답 어디에도 증강 필드 없음(응답 예: `{"id":132,"cctvName":"CCTV-강남구-001",...,"eventTypeCd":"INTRUSION"}`).
  6. 참고로 **작업목록(`GET /v1/tasks/board`)에는 `augmented`/`augType` 필드가 실존**한다(예: `{"videoId":131,...,"augmented":true,"augType":"RESL_480P"}`) — 즉 이 정보 자체는 BE 도메인에 존재하지만 검수(`/v1/reviews*`) 경로로는 전달되지 않는다.
  7. 테스트 커버로도 재확인: `ReviewMetaPanel.test.tsx`(features/review/__tests__/ReviewPageMetaPanel.test.tsx) 4개 케이스(event_annotation 렌더/시계열 렌더/버튼 미노출/빈상태) 중 증강 관련 케이스 0건.
- **재현/확인 경로**: REVIEWER 로 `/review/{id}` 진입(또는 위 curl) — 어떤 영상(증강 파생이든 아니든)이어도 화면·응답에 증강 여부/종류 표시가 없음.
- **영향**: 기능 누락(보안 이슈 아님). 검수자가 증강/해상도 파생 영상을 원본과 구분 없이 검수하게 되어, CLAUDE.md 의 "파생영상도 기존 플로우와 동일하게 검수" 원칙은 지켜지지만 "무엇을 검수 중인지"에 대한 맥락 정보가 빠져 있다. 카탈로그가 이 정보의 화면 노출을 기대값으로 잡았다면 실제 구현과 불일치.
- **수정 방향(제안)**: ⚠ 구현하지 않는다 — `ReviewResponse`(BE)에 `orgnlRawSn`/`augType` 추가 후 `Review`(FE 타입)·`ReviewHeader`/`ReviewMetaPanel`에 배지 형태로 노출하는 방향이 board 목록과의 일관성 면에서 가장 자연스러워 보이나, 카탈로그 기대값 자체를 "현재 미구현"으로 재분류할지 BE/FE 를 보강할지는 정책 결정 필요.

---

## 카탈로그 외 추가 발견 사항 (참고, TC-ID 미부여)

검증 도중 도메인 규칙("마킹 중 비식별 누락 신고, rawSn 기준")과 대조하다 발견한 사항으로, H-6/H-7/H-8 어떤 TC-ID 도 이를 다루지 않아 정식 이슈로 등록하지 않고 참고로만 남긴다.

- BE 는 `POST /v1/videos/{rawSn}/deident-report`(마킹 단계 비식별 신고, `DeidentReportController.java:122`, B-ISSUE-28)를 보유하지만, `frontend/src/pages/MarkingPage.tsx` 및 `features/marking/**` 전체에 이 엔드포인트를 호출하는 코드가 **0건**(grep 확인)이다. FE 가 실제로 호출하는 것은 라벨링 단계용 `POST /v1/labels/{srcSn}/deident-report`(`features/label/api.ts:497,510`)뿐이다. 즉 마킹 화면에는 "비식별 누락 신고" 버튼/동선이 전혀 없다. H-6 카탈로그 20건 중 이를 다루는 TC-ID가 없어 PASS/FAIL 판정 대상은 아니지만, CLAUDE.md 가 명시한 "마킹 단계(rawSn 기준) + 라벨링 단계(srcSn 기준) 양쪽 가능"이라는 서술과 FE 구현 사이의 실측 간극으로 기록해 둔다.
