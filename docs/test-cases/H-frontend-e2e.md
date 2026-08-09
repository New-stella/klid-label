# H. FE 화면/컴포넌트 + E2E — 테스트 케이스

> **614 케이스**(표 행 실측 — **폐기 행 포함**, 행을 지우지 않으므로. 변경 이력 표는 제외) · 계층: component / e2e / a11y / security · [← README](README.md) ※ 카운트 = `grep -cE '^\| ~*TC-'`(ID 취소선 폐기 행 포함, 2026-08-05 회차 12 실측 갱신 350 → 354 · 회차 13 에서 354 → 362, H-19 `TC-FE-320~327` 신설 · 회차 14 에서 362 → 369, H-20 `TC-FE-328~334` 신설 · 회차 15 에서 369 → 374, H-21 `TC-FE-335~339` 신설 · 회차 16 에서 374 → 382, H-22 `TC-FE-340~347` 신설 · 회차 17 에서 382 → 390, H-23 `TC-FE-348~355` 신설 · **회차 18 에서 390 → 393, H-21 `TC-FE-356~358` 추가 · 회차 19 에서 393 → 398, H-24 `TC-FE-359~363` 신설 · 회차 20 에서 398 → 413, H-25 `TC-FE-364~377`·`TC-A11Y-016` 신설 · 회차 21 에서 413 → 463, H-8a `TC-FE-378~389`(12) + H-26 `TC-FE-390~423`(34) + H-27 `TC-FE-424~427`(4) 신설 · 회차 22 에서 463 → 481, H-28 `TC-FE-428~441`(14) + H-29 `TC-FE-442~445`(4) 신설 · **회차 23 에서 실측 재계수 491 → 573, H-31~H-38 `TC-FE-456~537`(82) 신설** · 회차 24 에서 573 → 583, H-39 `TC-FE-538~542`(5) + H-40 `543~545`(3) + H-41 `546~547`(2) 신설 · 회차 25 에서 583 → 588, H-42 `TC-FE-548~552`(5) 신설 · 회차 26 에서 588 → 605, H-43 `TC-FE-553~555`(3) + H-44 `TC-FE-556~561`·`TC-A11Y-017`(7) + H-45 `TC-FE-562`(1) + H-46 `TC-FE-563~568`(6) 신설 · 회차 27 에서 605 → 609, H-47 `TC-FE-569~572`(4) 신설(당시 누계 미갱신) · 회차 28 에서 609 → 614, H-48 `TC-FE-573~577`(5) 신설. ⚠ 회차 22 까지의 누적 표기(481)는 **H-30 `TC-FE-446~455`(10) 신설분이 반영되지 않은 값**이었다 — 회차 23 착수 시점 실측이 491 이며 이 값부터 이어 센다**)
> ID: TC-FE(컴포넌트/상태) · TC-E2E(시나리오) · TC-A11Y(접근성)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 224건<br>(기대결과 실질 변경 21건) | 69건 | 0건 | 07-25 이후 `frontend/src` 73파일 변경 반영. ①**작업목록 전면 개편**(b23b8cbd — 상태 우선순위 정렬 폐기→등록일 최신순 시간축 단일, 필터·KPI 서버 이관, KPI 5카드 토글, 컬럼 헤더 정렬, 체크박스 페이지 이월 차단, `TaskBoardTable` 추출) → **H-16 신설** ②**검수목록 개편**(7ecbc66e — 진입 기본값 검수요청·FIFO 를 명시 전송+URL 기록, 상태 코드 역매핑 `Record` 강제, 상태 컬럼 정렬 제외, 300ms debounce) → **H-17 신설** ③**인증 이미지 blob 전환**(f4f2d9fe — `AuthImage` `srcSn\|path` 유니온 + 경로 화이트리스트 fail-closed, `authImageStore` refcount 공유) ④증강 결과 해상도 파생 비교 이미지·프레임 페이저·`reviewable` ⑤412 `PRECONDITION_FAILED` 매핑 + 파생영상 신고 버튼 사전 비활성 ⑥라벨 저장 409 충돌 다이얼로그·`labelVersion` 낙관적 토큰 ⑦검수 승인 `REVIEW_NO_LABEL` 확인 ⑧마킹 fps 서버 위임·`batchTriggered=false` 안내 ⑨`cot` 배열/객체 양형 정규화. 근거(파일) 전면 재확인 + 파일 경로를 `src/` 기준 상대경로로 정규화(구 파일명만 표기 → 실제 경로). UNCERTAINTIES #23·#25 는 코드로 확정 가능(하단 참조) |

| 2 | 2026-07-31 | 0건 | 16건 | 0건 | **라벨링 화면 장시간 작업(busy) 배타 실행** 반영 — ①편집 차단(캔버스·툴바·프레임 이동·실행 버튼·단축키·되돌리기·롤백·신고), **차단은 입력 단계에서** ②진행 오버레이(300ms 초과, 작업명+경과 초+취소, 모델명 미노출) ③**취소 = 클라이언트 결과 폐기이며 서버 처리 중단 아님**(마우스·Enter·Space·ESC) ④단축키 판정 fail-closed(렌더 값 OR 실시간 store) ⑤ESC 취소 시 AI 분할 확정 큐도 비움 ⑥메타 편집 5종은 **의도적 미차단** ⑦크로스탭 동시성은 범위 밖(서버 409 담당) ⑧**포털 업로드 라벨링에도 오버레이·취소 대칭 배선**. H-3 하위 절 + H-11(TC-FE-271~275) + H-13(TC-A11Y-015) |

| 3 | 2026-08-03 | 4건 | 28건 | 0건 | **2026-08-03 사용자 확정 5건** 반영(커밋 `80171828`·`b27b3108`·`d8a7a2cc`·`e58aa086` + 영상 목록 필터 미커밋분). ①**결정1 라벨링 '메타' 탭 시계열 메타 검토 블록 제거**(상태 배지·승인/반려·반려사유 삭제, 텍스트 수정·저장만) → 회귀 가드 3건 신설(H-3 하위 절) + TC-FE-079·268 정정 ②**결정2 영상 상세 '버전관리로 이동'·라이트박스 '라벨링 편집' 버튼 제거 + `/history/:videoId`(SC-010) 페이지·라우트 삭제** → H-18 신설(기능은 라벨링 인라인 `HistoryPanel` 이 전부 제공하므로 **버전·롤백 기능 케이스는 폐기하지 않고 전제만 정정**) ③**결정3 도형 도구 클릭 → 라벨 선택 모달 → 드로잉** 흐름 신설 + 좌측 상시 라벨 패널(`LabelSidebar`) 폐지 + **전역 1~9 단축키 제거**(모달 전용) + `KeypointGuide` 우측 패널 최상단 이동 → 14건 신설 ④**결정4 라벨명 = 라벨 마스터 등록명 그대로**(코드 사전 치환 폐지 — ③이 도입한 "한글 우선 표시"를 다음 커밋에서 **되돌린 것**) → 4건 신설 ⑤**결정5 영상 목록 검색 필터 4종 + `capturedAt` 축 정정** FE 분 → H-18. BE 분은 [B-18](B-batch-deidentify.md) |

| 4 | 2026-08-03 | 116건 | 0건 | 0건 | **근거 `file:line` 전수 재확인 회차** — `LabelingPage.tsx` 가 라운드2(busy, `81813bd1`)·라운드3(label picker, `d8a7a2cc`) 삽입으로 최대 +170줄 밀려 H-3 원본 절(TC-FE-033~087,197,199~201) 55건 라인 정정. 라운드3 자체가 신설한 시계열메타 검토제거 회귀가드(TC-FE-276~278)도 같은 날 후속 커밋(`5c10c0cd` — 세그먼트별 편집으로 재구현)에 밀려 3건 추가 정정(단일 textarea→세그먼트별 textarea 구조 변경 반영). `AugmentResultPage.tsx` 는 항목축 페이징 신설로 `FrameGrid12`/`SideBySideCompare`/`DecisionCard`/프레임페이저가 신규 `AugmentResultPanel.tsx`·`AugmentVideoSection.tsx` 로 전량 위임돼 TC-FE-159~161·209~211·213 7건 재작성(TC-FE-213 은 "총 처리 이미지=페이징 전 전체" 기대결과가 **반대로** 정정됨 — 코드 주석이 페이지 스코프임을 명시). `ReviewPage.tsx`(4건)·`router/index.tsx`(9건, `/history` 라우트 삭제로 라인 이동)·`useLabelStore.ts`(3건, `setPan` 은 clamp 를 안 하고 `CanvasShell` 이 호출측에서 클램프)·H-13 a11y(6건, 경로 오탈자 `components/ObjectAttributePanel.tsx`→`features/label/components/...` 1건 포함)도 정정. H-5·H-6·H-7·H-18 은 전수 대조 후 **드리프트 없음 확인**(round1 이후 미변경 파일) + H-1·11·12·15·16·17 절 보완 재확인(정정 27건 — H-11 2건·H-12 1건·H-15 6건·H-16 18건, H-1·H-17 은 18~26건 전건 정확 확인). **직전 담당의 "H-15·16·17 은 소스 미변경이라 행단위 재대조 생략" 판단이 틀렸음이 재확인됨**(PM 이 `eace1213`/`1bf06ce5`/`dcdbb827`/`d8a7a2cc`/`e58aa086`/`3f60bd3b` 6개 커밋이 실제로 인용 대상 14파일을 건드렸음을 `git log` 로 지적) — 핵심 발견 4건: ①H-11 TC-FE-275 "포털 라벨링(`/portal/label/:id`)은 AI 분할·추적 제공" 전제가 `dcdbb827`(SAM2 제거)로 이미 폐기됐는데 미반영 — `PORTAL_HIDDEN_TOOLS=[SAM_SEGMENT,TRACK,KEYPOINT]`(types.ts:215-219)가 포털 라벨링·업로드 라벨링 양쪽에 적용돼 BBOX/POLYGON만 제공 ②H-12 TC-FE-188 "통계 화면=recharts 별도 청크" 통칭이 부정확 — `OverallStatPage` 는 `f902e3d1`(07-25 이전) 이후 recharts 미사용(커스텀 `SimpleBarChart`/`SimplePieChart`), recharts 는 `WorkerStatPage`(`DailyCompletionChart`)에만 잔존(라인 인용이 없는 행이라 이전 라운드들이 전부 검증을 건너뜀) ③H-16 TC-FE-242 "WORKER 시각은 클라이언트 필터" 전제가 `eace1213`(필터·정렬·KPI 서버 이관) 이후 무효 — `buildAssignmentParams`(boardParams.ts:206-218)가 이미 검색어/상태/이벤트유형을 `/v1/assignments` 서버로 위임하고 `TaskListPage.tsx` 에는 `.filter(` 재필터 코드가 0건 ④H-15 TC-E2E-019 "COMPLETED 전이" 표현이 실제 최종 단언(`readStatus()` 가 `dataSttsCd==='APPROVED'` 확인)과 불일치. H-16 은 `boardParams.ts`/`TaskListPage.tsx`/`types.ts` 의 대규모 주석·함수 삽입(`eace1213`)으로 인용 라인이 완전히 다른 함수를 가리키는 드리프트가 17건(예: TC-FE-233 이 `buildBoardParams` 를 가리켜야 하는데 `buildBoardSummaryParams` 를 가리킴). H-17(`reviewListParams.ts`/`api.ts`/`ReviewListPage.tsx`/`ReviewKpiCards.tsx`/`ReviewListFilters.tsx`)은 `1bf06ce5` 변경에도 불구하고 18건 전건 정확 — 파일이 바뀌었다고 반드시 드리프트가 나는 것은 아님(대조 없이 넘기면 안 되는 이유이자, 대조 결과 자체는 케이스바이케이스) |
| 5 | 2026-08-04 | 2건 | 3건 | 0건 | **라벨링 우측 속성 패널 겹침 수정**(사용자 신고 — AI 분할 도구 활성 시 "AI 분할 정밀도" 카드가 잘려 슬라이더·`즉시 그리기` 조작 불가) 반영. 근본 원인은 `ObjectAttributePanel` 루트 `<aside>` 의 **두 분기 레이아웃 계약 비대칭** — `!target`(선택 객체 없음) 분기에만 `overflow-y-auto` 가 없어, flex 자동 최소 크기가 0 으로 풀리지 않아 `overflow-hidden` 조상이 잘라냈다. 하필 그 카드가 **선택 객체 없이도 뜨는 유일한 컨트롤**이라 스크롤 없는 쪽에만 콘텐츠가 얹힌 형태였다. 동반 결함 `h-full`(형제 헤더와 높이 다툼)·`w-72`+`border-l`(부모와 중복 → 가로 넘침·테두리 이중)도 함께 제거하고 공통 상수로 계약을 통일. **신규 3건** — TC-FE-304(계약 동일성 결박) · TC-FE-305(컨트롤 2종 조작 가능) · **TC-FE-306(⚠미해결 — 좌측 도구바 `저장` 버튼 잘림, 사용자 확정으로 별건)**. **정정 2건**은 라인 드리프트(TC-A11Y-002 `145,149`→`162,166` · TC-FE-294 `258,268`→`269,279` — 공통 상수 추출로 +15줄 밀림). ★TC-FE-306 은 **폐기가 아니라 미해결 등재**다 — 라벨링 화면 스윕에서 같은 계열 결함으로 발견됐으나 툴팁 portal 분리가 선행돼야 해 미수정으로 남겼고, '조용한 누락'과 구분하려 카탈로그·코드 주석 양쪽에 근거를 남긴다 |

| 5 | 2026-08-03 | 55건 | 0건 | 0건 | **테스트케이스 전수 검증 3차 회차**(`docs/검증결과/2026-08-03/3차/`, 8파트 병렬 실동작 검증(브라우저 Playwright + 실 API 왕복 + DB 실측) 후 병합, 실행 2026-08-03~08-04 KST) — **2차 HIGH 5건 중 4건 해소 확인**(#7 세션만료 복귀URL 미주입·#9 낙관적동시성 labelVersion·#10 E2E 픽스처 부재·#11 포털 라벨링 AI도구 노출), **#8(`?token=` URL 인계 활성, CWE-598)만 3회차 연속 미해소**(`H-ISSUE-01`). **신규 HIGH 2건**: `H-ISSUE-41`(서버 잠금상태 문자열 `LOCKED` vs FE 판정값 `LOCKED_FOR_REDEIDENT` 불일치로 잠금 배너 미표시 — BE 409 최종차단으로 데이터유실은 없음) · `H-ISSUE-81`(포털 업로드 E2E `portal-upload.spec.ts`가 업로드 UI 없는 `/portal` 홈을 겨냥해 실질 커버리지 0). 근거 `file:line` 드리프트 + 기대결과 오류 정정 **55건**(H-1 3·H-3 앞 26·H-3 중 5·H-5 2·H-9 1·H-11 7·H-16 1, `git diff` 실측 55행과 일치). 신규/폐기 케이스 0건. 이슈 전문은 `docs/검증결과/2026-08-03/3차/ISSUES.md` "## H클러스터" 참조 |

| 6 | 2026-08-04 | 1건 | 2건 | 0건 | **3차 HIGH 결함 2건 수정 반영**(`H-ISSUE-41` · `H-ISSUE-42`, BE+FE 변경). BE `LabelService.getByFrame` 이 락 <b>행</b> 상태값 `"LOCKED"` 를 응답 `lockSttsCd` 에 그대로 실어 FE 판정 정본(`LOCKED_FOR_REDEIDENT`)과 어긋나던 것을 **응답 계약 상수 `LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT` 로 정정**하고, FE 판정도 리터럴 대신 `LockSttsCd.LOCKED_FOR_REDEIDENT` 상수를 참조하도록 바꿨다(양쪽 리터럴 중복 제거). ①TC-FE-067 정정 — 구 기재 *"서버 잠금 경로에서는 배너가 영영 발화하지 않는다"* **폐기**, 서버 잠금·`reportedLock` 양쪽 발화 ②**TC-FE-067a 신설** — BE 응답 상수·실값 ↔ FE 판정 상수 동치를 양방향으로 고정(BE 테스트가 `frontend/src/features/label/types.ts` 를 읽어 대조 — 선례 `CocoClassesDriftTest`) ③**TC-FE-067b 신설**(H-ISSUE-42) — 잠금 저장 시도는 잠금 안내 토스트 + PUT 미발생이고 **409 저장충돌 다이얼로그가 아니다**(두 원인 UI 구분). BE 최종 방어(409·저장 차단)는 무변경 — 이번 수정은 표시값 정정이다. 실행: BE `kr.co.cudo.authoring.label.*` **598 tests / 0 failures**(57 classes), FE 전체 회귀 참조 |
| 7 | 2026-08-05 | 0건 | 4건(TC-FE-311~314) | 0건 | **마킹 화면 비식별 누락 신고 버튼 신설**(V171) — 지금까지 신고는 라벨링 화면에만 있어 마킹 중 개인정보 노출을 발견해도 라벨링까지 진행해야 했다. `DeidentReportButton` 을 **복제하지 않고 재사용**(rawSn prop 추가)하며, 파생영상·비-`MARKING_READY` 는 **버튼 단계에서 비활성 + 사유 툴팁**으로 미리 막는다(제출 후 412 를 보는 동선 제거). 모달 안내가 재개 지점(마킹부터 다시 / 이어서 작업)을 알려준다 ⚠ **머지 합류(2026-08-05)로 회차 라벨 `2026-08-05 회차` → `7` 부여** — main 의 회차 5·6 뒤에 이어 붙였다. |
| 8 | 2026-08-05 | 0건 | 3건(TC-FE-308~310) | 0건 | **신고 관리 화면(SC-033)에 「신고 단계」 열 추가**(사용자 요청) — 해소 후 재개 지점이 단계로 갈리는데 목록에 단계가 없어 REVIEWER 가 **해소 결과를 예측할 수 없었다.** 표기는 사용자 언어(`마킹`/`라벨링`)이며 **레거시 `null` 은 '미상'**(빈칸 금지 — 빈칸은 값 부재와 로딩 실패가 구분되지 않는다) + 툴팁으로 "단계별 재개가 없다"는 사실을 알린다. 구 응답(필드 부재)도 '미상' 으로 견딘다. BE 분은 [B TC-DEID-110~112](B-batch-deidentify.md) ⚠ **머지 합류(2026-08-05)로 회차 라벨 `2026-08-05 회차-2` → `8` 부여** — main 의 회차 5·6 뒤에 이어 붙였다. |
| 9 | 2026-08-05 | 1건(TC-FE-066 근거 라인) | 0건 | 0건 | **머지 후 주석 드리프트 정정(케이스 내용·기대결과 무변경 · FE 실행 코드·화면 문구 무변경)** — `LabelingPage.tsx` 의 `handleDeidentReportSuccess` 주석이 **폐기된 구 정책**("BE 는 신고 접수 시 라벨을 전체 삭제한다", 2026-07-27 사용자 확정으로 폐기)을 그대로 서술하고 있어 **라벨 보존 + 신고 구간 412 게이트 + resolve 시 자동 해제**로 정정했다. TC-FE-066 은 이미 올바른 기대결과(★BE 는 라벨을 삭제하지 않는다 / 재조회 412)를 들고 있어 **근거 라인만** `:529-537` → `:537-545`(핸들러) + `:524-536`(정책 주석) 로 갱신(주석 5줄 추가로 밀림). 같은 정정을 `pages/manage/DeidentReportListPage.tsx` jsdoc 에도 적용. **렌더링 문구는 원래부터 정확**했다(`DeidentReportButton` 모달 안내 = "기존 마킹과 라벨을 유지한 채 이어서 작업합니다") — 화면 텍스트 변경 0. TC 행 추가·삭제 0 → 머리말·README 합계 불변 |
| 10 | 2026-08-05 | 1건(TC-FE-066 근거·기대결과 상세화 — **테스트 코드 실변경 동반**) | 0건 | 0건 | **TC-FE-066 테스트 전제를 실계약에 정합**(프로덕션 코드 무변경 · 화면 렌더링 문구 무변경) — 회차 9 에서 보고했던 잔여 건. `LabelingPageDeidentReport.test.tsx` 의 신고 성공 케이스가 **폐기된 BE 모델**(신고 후 재조회 = `200 + 라벨 0건`)을 mock 전제로 들고 있어, 카탈로그 기대결과(412)와 테스트가 정반대를 검증하고 있었다. **수정**: 2차 재조회 mock 을 `412 + {success:false, errorCode:'PRECONDITION_FAILED', message:'비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.'}` 로 교체(값 출처 = `LabelAccessGuard.java:154-155`·`ErrorCode.java:27`·`ApiResponse.java`, 지어낸 값 아님). 단언을 **두 국면으로 분리** — ①신고 직후 `reportedLock`+store reset 으로 객체 0건·잠금 배너(라벨이 서버에서 삭제돼서가 아님) ②412 도착 후 **"라벨 조회 실패"+서버 안내문** 화면이며 빈 라벨 화면이 **아님**(상호배타 단언). 두 국면이 레이스가 되지 않도록 2차 응답을 promise 게이트로 붙잡아 결정론화. 테스트명도 `…객체_목록이_즉시_0건으로_갱신_+_라벨_재조회` → `…객체목록_즉시_0건_+_재조회는_412로_라벨조회_실패안내` 로 정정(412 국면이 이름에 드러나게). **RED 실증**: mock 을 구 모델(`200+0건`)로 되돌린 사본에서 `라벨 조회 실패` 단언만 정확히 FAIL(1 failed / 4 passed) → 가드가 실제로 문다. TC 행 추가·삭제 0 → 머리말·README 합계 불변 |
| 11 | 2026-08-05 | 1건(TC-FE-066 기대결과·근거) | 1건(TC-FE-315) | 0건 | **★신고 후처리 캐시 정책 반전 — `invalidateQueries` → `removeQueries`(사용자 확정, 프로덕션 변경)**. `useLabels` 는 `staleTime 30s` + `refetchOnWindowFocus:false` + `gcTime` 기본 5분이라 invalidate 로는 캐시 **항목이 남아**, 신고 직후 화면을 벗어났다 30초 안에 재진입하면 **서버를 때리기 전에 캐시된 라벨 좌표가 렌더**된다(30초~5분 구간은 캐시 렌더 후 백그라운드 412 — 짧은 노출). 라벨 좌표는 **PII 위치 특정 정보**(CWE-359)라 이 창이 서버 신고 게이트(412)를 그대로 우회했다. `handleDeidentReportSuccess` 의 **두 분기(`byVideo(srcSn)` · `all`) 모두** `removeQueries` 로 전환하고, "왜 invalidate 로는 부족한가"를 되돌림 방지 근거로 주석에 남겼다. **회귀 가드 TC-FE-315 신설**(재진입 케이스) — RED 실증: `invalidateQueries` 로 되돌린 사본에서 이 케이스만 FAIL(`expected {frameNo:0, srcSn:300, …} to be undefined`), 나머지 5건은 통과(활성 화면에서는 remove/invalidate 가 동일 결과라 기존 케이스로는 **절대 못 잡는다** — 그래서 재진입 가드가 필요하다). **영향 범위 확인**: 이 핸들러는 `DeidentReportButton onSuccess` 단일 호출부이며 프레임 이동 등 정상 동선은 타지 않아 `keepPreviousData` 깜빡임 방지에 영향 없음. 마킹 화면은 `onSuccess={() => navigate('/task')}` 로 라벨 캐시를 다루지 않고, 프레임 이미지 blob(`authImageStore`)은 refcount 0 시 즉시 revoke 하는 공유 참조라 대상 아님. **TC 1건 신설 → 머리말·README 합계 349 → 350 실측 갱신** |
| 12 | 2026-08-05 | 1건(TC-FE-301 축 정정) | 4건(TC-FE-316~319) | 0건 | **개인정보 유무 화면 노출 폐지(R1/R2) + 이벤트유형 필터 축 정정(R3~R5, TC-FE-301)** — ①`components/common/PrivacyBadge.tsx` 삭제, `VideoListPage`(목록 9→8컬럼)·`VideoDetailPage`(기본정보 항목) 에서 참조 제거(응답 필드 `privacyTypeCd` 는 하위호환 유지, 화면 노출만 제거). 이 카탈로그에 개인정보 배지의 화면 노출을 검증하던 기존 케이스가 없었으므로 **폐기가 아니라 신설**(TC-FE-316~319 — 컬럼 미노출·상세 항목 미노출·colSpan/스켈레톤 칸수 정합 3종). ②TC-FE-301 의 `eventTypeCd` 설명 "카테고리 키"는 **회차 3 작성 당시부터 이미 V168(PR #81, 카테고리→유형 축 전환)과 어긋나 있던 문구**임을 이번에 발견해 "이벤트유형코드(표시명 그룹 옵션)"로 정정(값·동작 자체는 무변경 — 코드는 원래부터 EV-코드를 그대로 전송하고 있었다). BE 표시명 그룹핑 계약은 [B-18](B-batch-deidentify.md) TC-VIDEO-006a·019~021, [D-3a/D-3b](D-review-version-notify.md) 소관. **TC 4건 신설 → 머리말·README 합계 350 → 354 실측 갱신** |
| **13** | **2026-08-05** | 1건 | **8건**(TC-FE-320~327) | 0건 | **★히스토리 '버전' 탭 — 커밋 1건 선택 = 현재 작업본과 비교(구 동작 폐기)** — 구 동작은 1건 클릭 시 목록상 **직전 버전**(`list[idx + 1]`)과 비교해서, 승인 버전이 **1건뿐인 프레임은 비교 대상이 `undefined` 라 조회가 아예 실행되지 않았다**(diff 를 볼 수 없음). 이제 항상 신규 BE 경로 `/versions/{hash}/diff-with-working` 로 **현재 작업본**과 비교한다. 커밋 2건 체크 = 두 버전 비교는 **그대로 유지**. 변경 0건이면 빈 목록이 아니라 **"변경 없음" 안내**(렌더 분기 순서 `미선택→로딩→에러→결과` 라 조회 실패가 "변경 없음"으로 가려지지 않음). 부수 수정: **프레임 전환 직후 stale diff 요청** — 선택 리셋이 `useEffect`(커밋 이후)라 한 렌더 동안 이전 프레임 hash 로 조회가 나가 **다른 프레임의 라벨 diff 가 잠깐 표시**됐다(뮤테이션에서 실제 2회 요청 관측). 현재 목록에 실재하는 hash 만 조회하도록 **단일 선택·두 버전 비교 두 축에 공용 헬퍼로 동일 적용**(한쪽만 고치면 비대칭이 코드에 흔적 없이 남는다). H-19 절 신설. BE 분은 [D-5a](D-review-version-notify.md) TC-DIFF-032~047. **TC 8건 신설 → 머리말 354 → 362 실측 갱신** |

| **14** | **2026-08-06** | 1건 | **7건**(TC-FE-328~334) | 0건 | **★라벨 색상 결함 핫픽스 — 판정원이 하드코딩 색상표에서 라벨 마스터로 이동(구 정책 폐기)** — 사용자 신고: *저장하면 객체 색이 바뀐다*. **인과 사슬**: 캔버스 신규 라벨 생성 4경로(BBOX·POLYGON·AI 분할·KEYPOINT)가 `classId`·`className` 만 채우고 **`labelId` 를 설정하지 않음** → `api.serializeLabel(labelId: lbl.labelId ?? null)` 이 `null` 로 직렬화 → BE 가 `LS_LABEL` 을 조인하지 못해 재조회 응답의 `color`/`label` 이 null → `getLabelDisplayColor` 가 마스터 색을 못 찾고 **3순위 `trackId` 해시색으로 낙하**. 저장 **전**에는 마스터 색이 보이고 저장 **후**에만 바뀌는 증상이 여기서 나온다. 수정: 생성 payload 를 **단일 진입점 `OverlayLayer.newLabelFrom`** 으로 모아 4도구가 같은 필드셋을 쓰게 하고(도구 추가 시 경로별 누락 재발 차단), 분류 드롭다운 변경(`ObjectAttributePanel.handleClassChange`)은 `classId`·`labelId`·`color` 를 **함께** 갱신하며(색은 `label.color` 가 최우선 참조라 안 덮으면 분류를 바꿔도 옛 색이 남는다), 라벨명만 돌려주는 SAM2 Track 응답은 `resolveLabelIdByName` 으로 **역해석**하되 **동명 활성 마스터가 0건·2건 이상이면 `null`(fail-closed)** — 이름은 유일성 보장이 없어 추측하면 **다른 분류로 저장**된다. **★구 정책 폐기**: 우측 객체 패널 **그룹 헤더 점**의 색 판정원이던 하드코딩 색상표 `labelColors.LABEL_CLASS_DEFS`(9종)를 폐지하고 **파일째 삭제**했다 — 마스터에서 색을 바꿔도 반영되지 않고 미등록 분류는 회색으로 떨어지던, **마스터와 어긋나는 두 번째 진실원**이었다. 같은 파일의 **표시명** 치환(`PERSON`→'사람')이 2026-08-03 회차 3 에서 *"마스터가 단일 진실원"* 이라는 **같은 이유로** 이미 폐지된 바 있어(TC-FE-293) 그 연장선이다. 이제 색 판정은 `utils/labelColor.getLabelDisplayColor` 한 곳뿐이다. **보존된 축**: 개별 항목 좌측 **막대는 `trackIdToColor` 유지**(트랙 시각화 의도 — 사용자 확정). 그룹은 분류축이므로 `useTrackFallback:false` 로 트랙색 유입을 차단하고, 두 축의 경계를 TC-FE-333 이 고정한다. **결정성 보강**(code-reviewer MEDIUM): 그룹핑 키가 `className` **문자열**이라 동명·다른 `labelId` 가 한 그룹에 섞일 수 있는데 대표를 `items[0]` 으로 고르면 정렬·필터·재조회로 **순서만 바뀌어도 그룹 색이 흔들린다**(구 하드코딩 표는 값이 틀렸을지언정 결정적이었으므로 이번 수정이 새로 들일 뻔한 불안정성) → `groupRepresentative` 가 **최소 `labelId`(미연결 null 은 뒤) → `id` 사전순**으로 순서 독립 선택. **백필하지 않는다**(사용자 확정) — 기존 `labelId=null` 행은 해당 프레임을 **재저장할 때 자연 복구**되며, BE·마이그레이션 변경은 0. **정정 1건** TC-FE-293(표시명 축이라 기대결과는 무변경 — 인용 심볼 `LABEL_CLASS_DEFS` 의 보유 파일이 이번에 삭제되어 그 사실만 명기 + 근거에 심볼 보강). ⚠ 그룹핑 키를 `className`→`labelId` 로 바꾸는 안은 **화면 구조 변경이라 이번 범위 밖**(별도 논의). **TC 7건 신설 → 머리말 362 → 369 실측 갱신** |

| **15** | **2026-08-06** | 0건 | **5건**(TC-FE-335~339) | 0건 | **★dev 업로드에 「검증이벤트유형」 select 신설(@req R7)** — 외부 VLM 검증(`POST /v1/videovlm/verify`)의 필수 `event_type` 은 관제가 인입(`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`)으로 보내주기로 확정됐으나 관제 반영이 협의 이후라, 그 전까지 **연동을 실제로 돌려볼 입력 수단이 0** 이었다(BE 계약은 열려 있는데 화면에 넣을 곳이 없었다). `/dev/autolabel-test`(SC-027) TUS 업로드 폼 '이벤트 · 관제일지' 항목에 **미지정(기본) + enum 6종** select 를 추가한다. ①옵션 라벨은 **한글 병기**(`화재 (fire)`)이되 전송값은 **벤더 규격 소문자 원문** — 라벨을 보내면 벤더가 거부한다 ②**미지정이 기본이고 필수가 아니다** — 값 없는 업로드는 "event_type 미수신 → 위탁 SKIPPED" 경로를 밟는 **정상 동선**이라 필수로 만들면 그 경로를 화면에서 검증할 수 없다 ③미지정이면 세션 생성 바디에 **키 자체를 보내지 않는다**(폼의 다른 선택 필드와 같은 "빈 값 = 키 부재" 관례 — `toPayload` 의 `text()` 재사용) ④6종 목록은 **FE 단일 진실원**(`tusUploadForm.ts(VRFC_EVNT_TYPES)`)에 두고 select 가 map 한다(리터럴을 화면에 흩으면 BE `LsDataIngest.VRFC_EVNT_TYPES` 와 갈라지는 세 번째 목록이 된다) ⑤★**select 는 UX 보조일 뿐 신뢰 경계가 아니다** — 최종 판정은 서버(`isVrfcEvntTypeAllowed` → 400)이고 FE 에 별도 검증 로직을 두지 않는다. **FE 전용 회차**(BE 무변경 — BE 분은 [B-20](B-batch-deidentify.md) TC-ULD-025~048 소관). **TC 5건 신설 → 머리말·README 합계 369 → 374 실측 갱신** |

| **16** | **2026-08-06** | 0건 | **8건**(TC-FE-340~347) | 0건 | **★라벨링 시계열 메타 패널 — 전문 1개 편집 + 일치도 읽기전용 + 레거시 병기(@req R8/R9)** — 외부 위탁이 `describe`(구간 배열) → `verify`(단일 서술 + 일치도)로 바뀌어 BE 조회가 `items`/`technicalMeta`/**`readOnlyMeta`** 3목록으로 분리됐는데(Phase 5), 화면 어댑터가 `readOnlyMeta` 를 읽지 않아 **일치도가 화면에서 사라져 있었다**. 이번 회차에 ①어댑터 수용(`toFrameMeta`) ②`vlm.description`·`manual-timeseries` 만 편집 슬롯(**알려진 키 화이트리스트** — 접두 파싱 금지, BE fail-closed 와 대칭) ③레거시 구간행은 **읽기 전용 병기**(삭제·숨김 금지 = 보존 확정) + `start_sec` **숫자** 정렬(문자열 정렬이면 구간 10개 초과 시 `10-18` 이 `8-16` 앞으로 온다) ④일치도 읽기 전용 렌더(백분율 환산, 미지의 읽기 전용 키도 깨지지 않게 일반 렌더) ⑤검수 화면(`ReviewMetaPanel`)에도 '참고 정보'로 표시. **편집 단위 = 저장 단위(`metaKey`)** 원칙은 2026-08-03 조용한 무동작 사고의 재발 방지라 그대로 유지된다. 기존 케이스 정정 0건 — 이 패널의 기존 카탈로그 행(TC-FE-079 계열)은 개인정보 메타 패널이라 **무관**하다. mutation 실증 4종(정렬 되돌리기 3 RED · 화이트리스트 무력화 4 RED · 읽기전용 전송 1 RED · 어댑터 passthrough 제거 1 RED). |

| **17** | **2026-08-06** | 0건 | **8건**(TC-FE-348~355) | 0건 | **`features/auto/metaKeys` 판정 단일 원천의 전용 단위 테스트 신설**(회차 15 코드 리뷰 LOW 항목 해소 · **프로덕션 코드 무변경**) — 이 모듈은 **라벨링 편집 패널·검수 읽기 패널·어댑터(`api.ts`) 세 곳이 공유**하는데 지금까지 소비자 컴포넌트 테스트로 **간접 커버**될 뿐이라 판정 자체의 경계가 고정돼 있지 않았다. 컴포넌트 테스트는 화면 계약을 보는 것이라 **비구간 키 혼재·동률·범위 밖 값·미지 키** 같은 판정 경계를 재현하기 어렵고, 판정이 바뀌어도 세 소비자 중 한 화면만 깨져 원인 추적이 그 화면으로 오도된다. 신설 파일 `features/auto/__tests__/metaKeys.test.ts`(**25 tests**)가 `startSecOf`·`compareByStartSec`·`isEditableMetaKey`·`editableMetaLabel`·`readOnlyMetaLabel`·`formatReadOnlyMetaValue` 6종을 직접 고정한다. **기존 간접 테스트는 중복이어도 유지**한다(화면 계약과 판정 계약은 서로 다른 축이라 한쪽이 다른 쪽을 대체하지 않는다). |
| **18** | **2026-08-06** | 1건(TC-FE-335) | **3건**(TC-FE-356~358) | 0건 | **★검증이벤트유형 입력이 「6종 프리셋 + 직접 입력」으로 열렸다**(사용자 확정 — "6+수동입력"). 같은 날 위탁 게이트가 **조달값을 그대로 실어 항상 위탁**으로 반전됐고 BE 도 6종 allowlist 를 **형식 검사**(소문자·숫자·밑줄 20자)로 좁혔는데, **우리 화면만 6종 select 에 갇혀 있어** 벤더가 enum 을 넓히거나 미지 값을 시험할 때 dev 업로드로 재현할 수 없었다(관제 인입분은 우리 코드를 거치지 않아 원래 어떤 값이든 들어온다). ①select 에 `직접 입력` 옵션 추가 — 센티넬 `__manual__` 은 **화면 모드 표식이라 전송되지 않는다** ②고르면 자유입력칸(label 연결·`maxLength=20`)이 열린다 ③**프리셋에서 전환하면 이전 값을 비운다** — 남겨두면 화면은 "직접 입력"인데 `fire` 가 전송되는 **조용한 오전송**이 된다 ④FE 에 형식 검증 로직을 두지 않는다(신뢰 경계는 서버 — 두면 BE 와 갈라지는 세 번째 규칙). BE 분은 [B-20](B-batch-deidentify.md) TC-ULD-038·044 정정 + 신설 |

| **19** | **2026-08-06** | 1건(TC-FE-306 — **미해결 → 해소**) | **5건**(TC-FE-359~363) | 0건(테스트 코드 1건 폐기 — 아래) | **★저장 진입점 일원화 + 도구바 잘림 결함 해소(사용자 확정)** — 헤더 우측 `[저장]` 과 좌측 도구바 저장 아이콘이 **같은 `handleSave` 를 부르는 중복 진입점**이었다. ⚠**순서를 뒤집어 진행**했다: 도구바에는 짧은 뷰포트에서 `저장` 이 잘려 **영구 클릭 불가**인 알려진 미해결 결함(TC-FE-306, 회차 5 등재)이 있었으므로, **그걸 먼저 고치고** 헤더를 지웠다 — 반대로 하면 알려진 결함 위에 유일한 대안을 없애는 셈이다. ①**TC-FE-306 해소**: 버튼 목록에 `TOOLBAR_SCROLL_CLASS`(`flex-1`+`min-h-0`+`overflow-y-auto`+`overflow-x-hidden`) + **툴팁 `createPortal` → body**(`position: fixed`). 툴팁을 상자 안에 두면 `overflow-y` non-visible 이 `overflow-x` 를 auto 로 강제해 툴팁이 함께 잘리므로 **portal 이 선행 조건**이었다(구 주석이 제시한 "버튼 목록만 스크롤" 단독 안은 툴팁이 그 상자 안에 있어 성립하지 않는다). ②헤더에는 **버튼이 아니라 상태**만 남긴다 — `저장 중...`(구 버튼 라벨에서 이관) / `● 편집 중` / `✓ 저장됨`, 우선순위 **진행 > 미저장 > 저장됨**. ③**잠금(`LOCKED_FOR_REDEIDENT`)은 편집 차단(busy)과 다른 축**이라 툴바가 자체 판정할 수 없어 `LabelingPage` 가 `saveDisabled` 로 전달한다(누락 시 조용히 샌다 → TC-FE-361). WORKER `[검수제출]`·`Ctrl+S`·`handleSave` 로직은 **무변경**. ④기존 저장 케이스는 셀렉터만 `label-header-save` → `label-toolbar-save` 로 바뀌고 기대결과 불변(`editBlocking`·`lockSttsCdContract`·`LabelingPageBusyWiring`·`LabelingPageDeidentReport`). ⚠ **jsdom 은 레이아웃을 계산하지 않아**(getBoundingClientRect 전부 0) 잘림을 폭·높이로 단언하면 항상 통과하는 **거짓 가드**가 된다 — 구조 계약만 고정하고 **mutation 2종으로 실증**(스크롤 클래스 제거 → 1건 FAIL / 툴팁 portal 대상을 스크롤 상자로 교체 → 1건 FAIL, 원복 후 9/9 PASS). ⚠ 코드 주석이 가리키던 `TC-FE-304` 는 **2026-08-05 머지 시 ID 재부여로 어긋난 표기**였다(실제 이 결함은 `TC-FE-306`) — 주석을 정정했다. **TC 5건 신설 → 393 → 398** |
| **20** | **2026-08-07** | **6건**(근거 파일명 TC-FE-275 · 306 / 서술·검증지점 TC-FE-359 · 361 · 362 / 기대값 폐기+정정 TC-FE-363) | **15건**(TC-FE-364~377 · TC-A11Y-016) | 0건 | **★캔버스 상단 옵션바 신설 — 편집 액션·프레임 이동 이관(H-25 신설, SCREEN-005 확정).** 사양이 **"삭제·실행취소·다시실행·저장은 좌측 도구바가 아니라 캔버스 상단 옵션바에 둔다"** 로 확정돼 편집 액션 4종 + 프레임 이동 컨트롤이 신규 `CanvasOptionBar` 로 모였다. ①**양쪽에 두지 않는 것이 핵심** — 같은 액션이 두 곳에 있으면 잠금·진행중 판정이 한쪽만 갱신돼 조용히 열린 구멍이 생긴다(직전 회차의 헤더/도구바 중복 저장이 그 사례). 옵션바 보유(TC-FE-364) ↔ 도구바 부재(TC-FE-365)를 **양방향으로** 결박하고, 이관 대상이 편집 액션 4종뿐임을 TC-FE-366 이 경계로 고정한다. ②**프레임 위치 표시·이동도 단일 표면** — 헤더에서 위치 표시를 폐지(TC-FE-368, 구 `frame-counter` 소멸)하고 `FrameNavigator` 가 단독 담당한다. 버튼·번호 입력·슬라이더 **3진입점이 단일 콜백 `onRequestGoTo` 로 수렴**해(TC-FE-369) 미저장 가드가 한 곳에서만 돈다 — 진입점마다 붙이면 한 곳이 샌다. 슬라이더는 **솎아내되 놓는 순간 flush**(TC-FE-372)라 마지막 위치가 누락되지 않는다. ③**개명** `DarkToolbar` → `ToolBar`(다크 폐지 후 이름만 남은 잔재, 테스트 파일 포함) — 동작 무변경이며 근거 파일명만 정정(TC-FE-275·306, [F](F-portal.md) TC-PORTAL-072·072c). ④**기존 3건은 검증 지점 이동 정정** — TC-FE-361(잠금 전달)·362(저장 스피너)는 좌측 도구바 → 옵션바로 옮겨졌고 **기대결과는 불변**이며, 361 이 함께 단언하던 "도구 버튼은 활성"은 **옵션바에 도구 버튼이 없어 성립하지 않아** TC-FE-366 으로 이관했다. TC-FE-363 은 **구 기대값 "마지막 버튼 = 저장" 이 폐기**되고(저장이 도구바를 떠났다) "마지막 버튼이 스크롤로 도달 가능"으로 좁혀졌다 — **스크롤 계약 자체는 도구 버튼이 넘칠 때를 위해 유지**한다. ⑤**바뀌지 않은 것** — 단축키·미저장 배지·잠금 시 저장 비활성·`handleSave` 절차·저장 버튼 `data-testid`(`label-toolbar-save`). 컴포넌트가 통째로 옮겨갔을 뿐이라 기존 저장 케이스의 셀렉터도 **또 바뀌지 않는다**. ⚠ **검수 화면 상단 바는 범위 밖** — `FrameNavigator` 사양은 두 화면 공유를 규정하지만 **현재 `ReviewPage` 에 배선돼 있지 않아** 케이스를 만들지 않았다(없는 동작을 검증 대상으로 들지 않는다) |
| **21** | **2026-08-08** | **1건**(TC-FE-184 — DataTable 조합형 전환 반영) | **50건**(H-8a `TC-FE-378~389` 12 · H-26 `TC-FE-390~423` 34 · H-27 `TC-FE-424~427` 4) | 0건 | **★검수 화면 헤더 통합·상단 프레임 이동 바·재검토 필요 재승인(D6 FE 배선) + 공통 컴포넌트 6종 조합형 전환 + KRDS 팔레트 완결.** ①**검수 헤더 액션 일원화(5b-2)** — 승인·반려는 `ReviewHeader` 단독 담당, 구 `ReviewActionBar`(하단 액션 바)는 삭제됐다. 기존 TC-FE-137~141 등은 핸들러 자체를 검증하는 행이라 트리거 위치 이동과 무관하게 유효해 재작성하지 않았다 ②**상단 프레임 이동 바 신설(5b-3, H-8a)** — 헤더 바로 아래 별도 표면에 라벨링 캔버스와 **동일한 `FrameNavigator`** 재사용. [H-25](#h-25-캔버스-상단-옵션바-신설--편집-액션프레임-이동-이관-screen-005--2026-08-07-신설) 말미의 "검수 화면엔 배선 안 됨" 잔여 갭 서술을 **정정**(폐기 아님 — 이제 배선됨) ③**재검토 필요 재승인 FE(Phase 7b, H-8a)** — `ReviewHeader` 가 `needsRecheck` prop 을 받아 `COMPLETED`+`needsRecheck=true` 일 때만 승인 버튼을 재활성화(TC-FE-387), 반려는 이 예외와 무관하게 비활성 유지(TC-FE-065 와 짝 — [D-1b](D-review-version-notify.md#d-1b-재승인d6-통지-트리거-반전--검수-승인-워크플로우-tc-review--2026-08-08-신설)). 검수 목록에도 상태 배지와 병기되는 배지 신설(TC-FE-389) ④**공통 컴포넌트 6종 조합형 전환(D3 축, H-26)** — `Field`(라벨·설명·오류 접근성 연결 8종) · `Card`(서브컴포넌트 6종, 구 콘텐츠 prop 전량 폐기) · `DataTable`(`@tanstack/react-table`, 페이지네이션·선택·스켈레톤 내장 제거 — TC-FE-184 정정) · `Select`(Radix, 빈 문자열 옵션은 내부 센티넬로 placeholder 와 구분 — `Select` 루트가 children 스캔) · `Checkbox`(Radix 3상태, `aria-checked=mixed`) · `Textarea`(`field-sizing:content` 자동확장, `rows` 기본값 폐지). 신규 의존성 3종(`@radix-ui/react-checkbox`·`@radix-ui/react-select`·`@tanstack/react-table`, 전부 MIT) ⑤**`UI-003` 사양 자기모순 해소** — `SelectTrigger` 기본 크기가 사양 `h-8`(32px)이면서 같은 `DS-001` 이 44px 터치 타깃 하한을 규정해 자기 디자인 시스템을 위반했다 → default 를 44px 로, `sm` 은 밀집 배치 전용 예외로 한정(TC-FE-409/410) ⑥**KRDS 팔레트 완결(D1, H-27)** — 구 `#0F4C97` 계열이 저장소 전체에서 0건이 됐고(TC-FE-425), 정본 주조색이 더 밝아 대비 여유가 줄어든 info 배지 텍스트를 스케일 내 진한 단계로 교정(TC-FE-426, 새 색 정의 0건). ⚠ **잔여 판단**: Select 의 빈 옵션 조건부 렌더 패턴은 앞으로 피할 것(TC-FE-413), Textarea 자동확장은 Safari 미지원(TC-FE-423) |

| **22** | **2026-08-08** | 0건 | **18건**(H-28 `TC-FE-428~441` 14 · H-29 `TC-FE-442~445` 4) | 0건 | **★검수 상세 우측 패널 3탭 구조(SCREEN-019) + AI 정밀도 기본값 전용 조회 경로 전환.** ①**우측 패널 3탭(H-28)** — 구 구현이 5개 패널을 세로로 나열하던 것을 사양의 **객체(기본)/메타/이슈** 탭 구성에 맞췄다. 이 종류 변경에서 실제로 위험한 것은 배치가 아니라 **조용한 소실**이라, 구 5패널 기능이 탭 어딘가에 전부 남아 있는지를 전수로 결박했다(TC-FE-439). **탭은 표시만 전환**하고 객체 선택·검수 의견·pending 이슈는 `useReviewSelectionStore` 가 소유하므로 탭을 옮겼다 돌아와도 선택·속성 패널이 복원된다(TC-FE-438 — 선택을 컴포넌트 지역 상태로 옮기면 여기서 깨진다). 접근성은 WAI-ARIA Tabs 전량(`aria-controls`↔`aria-labelledby` 상호 연결·roving tabIndex·←/→ 순환·Home/End) + 기존 객체 트리의 listbox/option 계약 유지 회귀(TC-FE-437). 승인·반려 헤더 단독은 [H-8a](#h-8a-검수-화면--헤더-액션-일원화--상단-프레임-이동-바--재검토-필요-재승인-screen-019--2026-08-08-신설) TC-FE-378 과 짝으로 aside 축에서 한 번 더 결박(TC-FE-440) ②**AI 정밀도 기본값 경로 전환(H-29)** — **구 동작 2단계가 모두 폐기**됐다: 검수자 전용 `GET /v1/manage/configs` 무조건 호출(WORKER 마다 403 누적) → `enabled: isReviewer` 게이팅(403 은 사라졌지만 **작업자가 기본값을 아예 못 받음**). 이제 공통 읽기 전용 경로 `GET /v1/ai-defaults` 를 역할 게이트 없이 호출한다. **가드가 두 축을 함께 센다** — 관리 영역 호출 0건 **+** 전용 경로 호출 1건 이상(한쪽만 세면 구 처방으로 조용히 되돌아간다). 기존 `LabelingPageConfigsRoleGate.test.tsx` 는 **테스트 제목·단언 축이 함께 바뀐 것**이라 정정이 아니라 신설로 등재했다(구 케이스가 카탈로그에 없었다 — 갭이었음). BE 계약·인가·응답 키 집합은 [A-8](A-auth-common.md) `TC-AIDEF-001~012` 소관 |
| **23** | **2026-08-08** | **4건**(TC-FE-114 · 125 · 183 · 301) | **82건**(H-31 `TC-FE-456~459` 4 · H-32 `460~467` 8 · H-33 `468~476` 9 · H-34 `477~485` 9 · H-35 `486~501` 16 · H-36 `502~516` 15 · H-37 `517~533` 17 · H-38 `534~537` 4) | 0건 | **★조작 수단 보강 + 사양 정합 9건(전부 FE).** ①**마킹 칩 개별 삭제(H-31)** — 구 동작은 삭제 수단이 `Del`/`Backspace` **단축키뿐**이라 마우스만 쓰는 사용자에게 개별 삭제 경로가 없었다. 칩마다 삭제 버튼을 두되 단축키는 **유지**(대체 아님) → TC-FE-114 정정(“유일 수단” 전제 폐기) · TC-FE-125 정정(칩이 단일 `<button>` → 선택+삭제 두 버튼 그룹) ②**영상 목록 기간 필터(H-32)** — 네이티브 date 입력 두 벌 직접 재구현을 사양 컴포넌트 `DateRangePicker`(UI-029, 그때까지 **소비처 0 이라 계약이 실행으로 확인된 적 없던** 컴포넌트)로 교체. **값 형식·URL 직렬화·상호 min/max 제약은 무변경**이라 절의 절반이 “바뀌지 않았음”을 고정하는 가드다. 늘어난 것은 `role="group"` 하나 → TC-FE-301 근거 정정 ③**검수 프레임 썸네일 스트립(H-33)** — 최하단 Footer → **캔버스 위**(사양 SCREEN-019)로 이동 + 접기/펼치기 신설(기본 펼침, 접힘은 `aria-hidden` 이 아니라 **DOM 제거** — 보이지 않는 버튼에 Tab 포커스가 갇힌다). ★접기 토글은 `role="toolbar"` **바깥**이다(툴바 안이면 토글 포커스 상태의 ←/→ 가 프레임을 바꿔 툴바 키보드 관례와 어긋난다). 이동 경로는 여전히 `handleGoToFrame` 하나로 수렴 ④**라벨 속성 정의 사이드 시트(H-34)** — 테이블 아래 인라인 `<section>`(사양 드리프트) → 우측 시트(SCREEN-035). **바깥 클릭으로 닫지 않는다**(작성 중 유실 방지) · 중첩 폼이 열려 있으면 ESC 는 폼만 닫는다(두 리스너가 같은 document 라 안쪽 `stopPropagation` 으로는 못 막는다) · **행 선택 강조 폐기**(사양) · portal 전환으로 XSS 가드 단언 축을 `container`→`document` 로 정정(그대로 뒀으면 **항상 통과하는 가짜 가드**) ⑤**캔버스 회전·격자(H-35)** — 회전은 **표시 전용**이라 저장 좌표 불변(`geometry.angle` 은 0 유지, 회전은 레이어 변환 축)이고 회전 중에는 편집을 봉인한다(포인터 좌표와 geometry 좌표계가 어긋나 **찍은 위치와 다른 좌표가 저장**되기 때문). 격자는 `listening=false` 순수 오버레이 ⑥**영역 확대(H-36)** — 드래그 영역으로 확대하되 **라벨을 만들지 않는다**(같은 좌클릭 드래그가 확대와 도형 생성 양쪽으로 발화하면 보기 조작이 데이터를 오염시킨다). 12px 미만 무시 · 배율은 **기존 줌 상·하한 공유** · 회전 중에도 동작(뷰 좌표 환산) · 캔버스 밖 종료는 취소 ⑦**페이지네이션 계약 전환(H-37)** — `size`+`totalElements` 되계산(두 번째 진실원) → **서버 `totalPages` 그대로**. ⚠ **사용자 가시 변화**: 총 건수 요약이 컨트롤에서 제거돼 화면이 자체 표기를 갖지 않은 **검수 목록·공지 목록·사용자 관리 3화면**에서 총 건수가 사라진다(증강 결과 프레임 페이저는 빈 페이지 안내에만 잔존) ⑧**목록 3화면 공용 수렴(H-37)** — 영상 처리 현황·작업 목록의 **고정 1~7 번호**, 증강 요청의 **번호 없는 이전/다음**은 8페이지 이상에서 **마지막 페이지 도달 경로가 아예 없었다**. 공용 컨트롤(양끝+현재±1 말줄임)로 수렴하며 번호 버튼 접근명 `N페이지`·터치 타깃 44px 을 함께 얻는다 → TC-FE-183 정정 ⑨**사용자 관리 저장 잠금(H-38)** — 사양의 “원래 값과 같으면 비활성” 이 미구현이라 **PATCH 없는 빈 왕복**이 가능했다. 두 사유(미선택/변경없음)를 합치지 않고 **왜 잠겼는지 화면이 말한다**. ⚠ **카운트 정정**: 헤더 누적 표기가 H-30(`TC-FE-446~455`) 신설분을 누락해 481 로 남아 있었다 — 착수 실측 491 을 기준으로 이어 센다 |
| 24 | 2026-08-09 | 0건 | 10건(H-39 `TC-FE-538~542` 5 · H-40 `543~545` 3 · H-41 `546~547` 2) | 0건 | **빈 상태 안내 + 시각 일관성 3건(전부 FE · BE 무변경).** ①**마킹 마크 0건 빈 상태(H-39)** — 칩 목록을 `localMarks.length > 0` 일 때만 렌더해 **0건이면 패널이 통째로 사라졌다**. 사용자가 "기능이 없다"와 "아직 마크가 없다"를 구분할 수 없어, 확정 사양대로 **빈 상태 안내를 노출**한다. ★안내는 **모드별로 다르다** — 자동 모드에서는 `Space` 가 아예 발화하지 않으므로(`mode !== 'MANUAL'` 조기 리턴) 수동 안내를 그대로 쓰면 **눌러도 아무 일이 없는 키를 알려주는 거짓 안내**가 된다. 칩 개별 삭제(H-31)·`Del`/`Backspace` 는 **유지**되며 마지막 마크 삭제 시 빈 상태로 되돌아가는 것까지 고정(TC-FE-542) ②**글리프 재유입 가드 등재(H-40)** — 이모지·기호 전량 아이콘 교체(`9ef40bdc`)는 이미 반영됐으나 **그 가드가 카탈로그에 등재되지 않아** 장부상 검증 대상 밖이었다. 기준선이 **빈 상태로 유지되는 것**과, 교체가 **의미를 잃지 않았음**(저장 상태는 아이콘이 아니라 문구로 낭독 · 색만으로 전달 금지)을 행으로 남긴다 — 신규 결함 발견이 아니라 **누락 등재** ③**폼 컨트롤 모서리 반경 통일(H-41)** — `Input` 만 `rounded-lg`(8px), 나머지 넷은 `rounded-md`(6px)라 같은 폼에서 텍스트 입력만 혼자 둥글었다. 다수에 맞춰 `Input` 을 내린다. 검사 명제는 "6px 이어야 한다"가 아니라 **"다섯이 같다"** — 토큰 일괄 변경은 기대값 한 줄로 흡수되고 **한 컨트롤만 갈라지는 것**만 실패한다. **RED 실증 4종**: `Input` 을 `lg` 로 되돌림 → 반경 가드 2건 FAIL(어긋난 파일·값 지목) · 칩 목록을 구 조건부 렌더로 되돌림 → 빈 상태 4건 FAIL · 모드 분기 제거 → 자동 모드 케이스만 FAIL · `LabelHeader` 아이콘을 `✓` 로 되돌림 → 글리프 가드가 파일·글리프 지목하며 FAIL. **TC 10건 신설 → 머리말·README 합계 573 → 583 실측 갱신** |
| 25 | 2026-08-09 | 0건 | 5건(H-42 `TC-FE-548~552`) | 0건 | **★이슈 스레드 작성자 역할 표기 — 검수자 문의 ↔ 작업자 문의 구분(구 이름만 표기 폐기)** — 검수자도 문의를 등록하게 된 뒤로 스레드 헤더가 이름·사번만 보여 **두 문의가 화면에서 구분되지 않았다**(댓글은 이미 역할을 병기하고 있어 같은 화면 안에서 비대칭이기도 했다). BE 신규 필드 `IssueThreadResponse.reportedUserRoleCd`([D-1c](D-review-version-notify.md#d-1c-문의-스레드-작성자-역할-노출-tc-review--2026-08-09-신설))를 **기존 표기 헬퍼 `issueAuthorLabel` 에 그대로 태운다** — 표기 로직을 복제하지 않는다(복제하면 한쪽만 갱신돼 갈린다). 헬퍼에는 **빈 역할 분기**를 추가해 역할 `null` 이면 `"홍길동 ()"` 같은 빈 괄호 없이 이름만 남기며, 이 분기는 댓글 축도 함께 보호한다. `IssueThreadPanel` 의 `resolveDisplayName` 직접 호출은 제거(헬퍼가 내부에서 같은 폴백을 수행). mutation 실증 2종(역할 미전달 → 3건 실패 · 빈 역할 분기 제거 → 2건 실패). **TC 5건 신설 → 머리말·README 합계 583 → 588 실측 갱신** |
| 26 | 2026-08-09 | 2건(TC-FE-118 · TC-FE-213) | 17건(H-43 `TC-FE-553~555` · H-44 `TC-FE-556~561`+`TC-A11Y-017` · H-45 `TC-FE-562` · H-46 `TC-FE-563~568`) | 0건 | **사양 정합 3건 + 교차 관심사 가드 1건(전부 FE · BE 무변경)** — ①**증강 결과 작업 요약 5칸 확정**(H-43): 요청일시를 더하면서 **6칸**이 됐고 사양에 없는 `증강 유형` 칸(항목 유형 **합집합**)이 남아 있었다. 유형은 **항목 단위** 정보라 항목 탭·비교 이미지 라벨이 항목마다 이미 보여 주며, 오히려 요약 쪽이 어느 항목의 유형인지 말하지 못했다. 그리드 열 수도 5로 되돌린다(칸만 지우면 빈 열이 남는다) — ⚠ 살아 있는 브레이크포인트는 **`md`·`xl` 뿐**이라 `sm:`/`lg:` 로 되돌리면 반응형이 사라진다. **칸 개수만 세지 않고 라벨 목록을 순서까지** 고정 → TC-FE-213 정정(구 6번째 칸 `증강 유형 (이 페이지)` 폐기 명시) ②**마킹 완료 버튼 항상 활성 + 0건 안내**(H-44): **구 정책 폐기** — 마크 0건에 버튼을 비활성화(툴바)하고 단축키는 조용한 early return(페이지)이라 **왜 진행되지 않는지를 화면이 말하지 않았고** 보조기술에는 아무 신호도 없었다. 확정 사양은 **항상 클릭 가능 + 누르면 토스트로 사유를 말하고 제출만 차단**이며, 그 토스트가 `role="alert"` 라 낭독된다(TC-A11Y-017 — **버튼을 항상 활성으로 두는 근거**). ★**저장 중 비활성은 별개 축이라 유지**(함께 걷어내면 in-flight 재클릭으로 POST 중복 발화) · 버튼 클릭과 `Enter` 두 진입점이 같은 안내를 내는지 · 1건 이상이면 안내 없이 정상 제출되는 **역방향**까지 고정 → TC-FE-118 정정(막는 **수단**이 바뀌었을 뿐 `mutate` 미발생은 유효) ③**팬 도구 컴포넌트 폐지**(H-45): 참조 0건 파일 제거. **열거값·표시명은 유지**한다 — 포털 업로드 라벨링이 그 값으로 `이동` 버튼을 실제 렌더하므로 함께 지우면 그 화면의 도구가 사라진다. 화면 이동 조작 자체는 캔버스 셸(스페이스+드래그·중클릭)이 담당 ④**목 자식 전달 계약 가드**(H-46 · 교차 관심사): 캔버스 계열 **67개 파일이 konva 목을 각자 복제**하는데 **그 계약을 검증하는 테스트가 한 건도 없었다** — 67벌을 동시에 끊고 전체를 돌리면 **4개 파일 18건만 실패하고 63개 파일은 전량 통과**한다(recharts 공용 목도 끊어도 38건 전부 통과). konva 는 **소스 스캔**(목이 각 파일 안에 복제돼 밖에서 실행 불가) · recharts 는 **동작 검증**(공용이라 실행 가능)이며 **두 방식을 통일하지 말 것**. 가드가 눈머는 것을 막는 장치 3종(대상 0건이면 실패 · 팩토리 추출 실패면 실패 · 양성 대조군) 포함. ⚠ **"아무것도 안 잡는 것처럼 보이는 것이 정상"** 이며 통과한다는 이유로 지우면 63개 파일이 파손을 다시 조용히 통과시킨다. **후속** — 근본 해소는 목 67벌을 **공유 모듈로 모으는 것**이고 그때 H-46 은 폐기 대상이 된다(67개 파일을 건드리는 별건) |
| 27 | 2026-08-09 | 0건 | 4건(H-47 `TC-FE-569~572`) | 0건 | **★dev 업로드에 이벤트유형코드 입력 신설(H-47) — 업로드만으로 파이프라인 완주가 불가능하던 갭 해소.** 실측(로컬 드라이브): dev 업로드 영상이 적재·비식별까지 정상 완주한 뒤 **마킹에서 400**(`이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다`)으로 멈췄다. 결함이 아니라 **입력 부재** — 폼·DTO 에 `evntTypeCd` 가 없어 인입 행의 그 컬럼이 항상 `NULL` 이었고 적재가 그것을 단독 조달원으로 삼는다(관제 미송신 상태와 동일). ①**축 분리 유지** — 같은 항목 안에 나란히 있는 `검증이벤트유형`(외부 검증 API 의 `event_type`)·`이벤트 ID`(식별자형)와 각각 다른 값이며 합치거나 서로 채우지 않는다 ②**프리셋 select 로 좁히지 않는다** — 관제 코드 체계는 우리 소유가 아니고 미등록 코드도 인입되며 서버가 자동 등록한다. 화면이 목록을 들면 두 번째 진실원이 된다(회차 25 가 검증이벤트유형에서 같은 이유로 6종 allowlist 를 폐기) ③**미입력은 키 부재** — 관제 미송신 상태를 그대로 재현할 수 있어야 하며 빈 문자열을 보내면 BE 형식 검증에 걸린다 ④**대문자 승격은 화면이 한다** — BE 는 표기를 바꾸지 않는다(관제 코드라 우리가 정하지 않는다). 소문자 입력은 표기 실수이지 다른 값이 아니다 ⑤`maxLength=20`(컬럼 폭). BE 분은 [B](B-batch-deidentify.md) B-25 `TC-ULD-057~064` |
| 28 | 2026-08-09 | 0건 | 5건(H-48 `TC-FE-573~577`) | 0건 | **★증강 3종 차별화 — 유형별 생성 조건 프리필(H-48). FE 전용이며 BE 무변경.** 실측: 외부 위탁 요청 바디에 **증강 유형 필드가 없다**(요청ID·채널·요청자·이벤트유형·작업유형·생성모드·입력파일·생성조건·콜백URL). 유형에 따라 달라질 수 있는 값은 **생성 조건 하나뿐**인데 그 조건을 사람이 직접 쓰게 되면서 유형과의 연결이 끊겨, 같은 조건으로 종류만 바꾸면 **완전히 동일한 요청**이 나갔다 — 세 종류를 나눈 의미가 사라진 상태였다. ①**유형이 기본값을 채운다** — 겨울은 계절·날씨, 야간은 시간대, 우천은 날씨 ②**강제가 아니라 기본값** — 검수자가 생성 조건을 조절할 수 있어야 한다는 확정 정책을 지키려면 수정 가능해야 하고, **이미 손댄 필드는 종류를 바꿔도 보존**돼야 한다 ③**판정 축은 '값'이 아니라 '편집 행위'** — 빈 값 여부로 판정하면 사용자가 **의도적으로 지운 필드**가 종류 변경 때 되살아난다. 오류 표시용 `touched`(blur 만으로 켜진다)와도 분리한다 ④**손대지 않은 필드는 새 유형 기준으로 갱신** — 남기면 '야간인데 계절=겨울' 처럼 사용자가 고른 적 없는 조건이 전송된다 ⑤**역방향은 만들지 않는다** — 생성 조건 값으로 증강 유형을 유추하면 자유 문자열이 산출물 경로·해상도 네임스페이스 판별로 새어 경로 순회가 열린다. 유형의 단일 원천은 카드 선택값이다 ⑥채우지 않는 축(지형·심각도)은 **비워 둔다**(시스템이 지어낸 조건이 검수자 확인 없이 외부로 나가지 않게) |
| 29 | 2026-08-09 | 0건 | 8건(H-49 `TC-FE-578~585`) | 0건 | **★회색 표면 위 보조 텍스트 대비 — 하한 60단 확정 + 가드를 요소 경계 너머로 확장(전부 FE · BE 무변경).** 중립색을 KRDS 정본으로 교체하자 보조 텍스트 단계(`text-gray-500`, neutral 50단)가 **흰 배경에서만 AA 를 통과**하고 (4.51) 회색 표면 위에선 미달(gray-50 4.13 · secondary-50 4.01 · primary-50 4.01)이 됐다. 페이지 배경 자체가 회색이라 흰 카드 밖 텍스트가 전부 이 축에 걸린다. DS-001 `do_rules` 에 **"회색 표면 위 보조 텍스트는 60단 이상"** 규칙이 확정돼 적용했고(70곳), `bg-gray-200` 위는 60단으로도 미달(4.10)이라 **70단**으로 올렸다 — "일괄 500→600" 으로 끝내면 남는 함정이다. ⚠ **이 회귀는 기존 가드가 구조적으로 못 보던 것**이다: 같은 className 문자열 안의 조합만 스캔했는데 실제 위반은 **전부** 배경과 글자색이 다른 요소에 있었다(표 헤더 `<tr>`↔`<th>`, 선택된 KPI 카드 래퍼↔내부 텍스트). 가드를 ①`bg-primary-50` 축 추가 ②조상 요소의 멀티라인 `className` 속성 구간까지 읽는 부모→자식 스캔 ③단계 고정이 아닌 실대비 계산 으로 확장했고, 되돌리는 변이 4종(단일 지점·gray-200 함정·축 제거·70곳 전량)이 **전부 실패로 잡히는 것**을 실측 확인했다. 오탐 방지도 함께 넣었다 — 흰 배경 조상·이미 닫힌 형제(진행률 바)·배타 분기는 위반이 아니다. ⚠ **가드가 못 보는 범위**(파일을 넘는 조합 · 페이지 배경 · 런타임 배경)는 H-49 말미에 명시했다 — 초록이 "대비 회귀 없음"의 증명이 아니다. |

> **ID 부여 규칙(이번 회차)**: 신규 케이스는 섹션 위치와 무관하게 **문서 전체 마지막 번호 다음**부터 이어서 부여했다(1회차 TC-FE-194~260, TC-A11Y-013~014 / 3회차 TC-FE-276~303). 섹션별로 이어 붙이면 뒤 섹션의 기존 ID 와 충돌하기 때문이다.
> **기준선**: FE 테스트 **338 files / 2,038 tests**(2026-08-03, 커밋 `e58aa086` 전체 회귀). 07-30 시점 1,674 → 07-25 시점 ~1,5xx. "기존 테스트 부분 커버" 서술은 이 수치로 읽는다.

## H-1. 인증/라우팅 가드

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-001 | RoleGuard 하이드레이션 대기 | 토큰 복원 전 | isHydrated=false | "인증 확인 중" 스피너 | component | High | router/guards.tsx |
| TC-FE-002 | RoleGuard claims 없음 → ingress | hydrated, claims=null | 보호 경로 | /ingress Navigate | component | High | router/guards.tsx |
| TC-FE-003 | RoleGuard exp 만료 → 상위 redirect | exp 과거 | 진입 | clear()+redirectToUpstream | security | High | router/guards.tsx |
| TC-FE-004 | RoleGuard role=null → role-claim | INTERNAL, role 미부여 | 진입 | /role-claim Navigate | component | High | router/guards.tsx |
| TC-FE-005 | RoleGuard 역할 불일치 → forbidden | WORKER, allow=[REVIEWER] | 진입 | /forbidden | security | High | router/guards.tsx |
| TC-FE-006 | RoleGuard 역할 일치 통과 | role∈allow | 진입 | children 렌더 | component | High | router/guards.tsx |
| TC-FE-007 | ChannelGuard 채널 불일치 → forbidden | PORTAL, INTERNAL 요구 | 진입 | /forbidden | security | High | router/guards.tsx |
| TC-FE-008 | AuthenticatedGuard role=null 통과 | 인증만 | /role-claim | children(무한 redirect 없음) | component | High | router/guards.tsx |
| TC-FE-009 | isExpired: exp=0/미지정은 만료 아님 | exp undefined/0 | 판정 | false | component | Med | router/guards.tsx |
| TC-FE-010 | JWT payload role 화이트리스트 | role="ADMIN" | setToken | claims=null | security | High | stores/useAuthStore.ts |
| TC-FE-011 | JWT role 빈값 허용(자가부여 대기) | role="" | setToken | role=null claims 유효 | component | Med | stores/useAuthStore.ts |
| TC-FE-012 | JWT channel/sub/exp 누락 무효 | 필수 부재 | decode | null | security | High | stores/useAuthStore.ts |
| TC-FE-013 | JWT 한글 name UTF-8 디코드 | 한글 name | decode | TextDecoder 정상 | component | Med | stores/useAuthStore.ts |
| TC-FE-014 | JWT parts≠3 무효 | 형식 오류 | decode | null | security | Med | stores/useAuthStore.ts |
| TC-FE-015 | hydrate: 만료 토큰 sessionStorage 제거 | exp 과거 | hydrate() | 제거+token=null | security | High | stores/useAuthStore.ts |
| TC-FE-016 | 토큰 저장소=sessionStorage | setToken | 저장 | sessionStorage만(XSS 노출면 축소) | security | High | stores/useAuthStore.ts |
| TC-FE-017 | axios 요청 인터셉터 Bearer 주입 | store token | 요청 | Authorization 헤더 | component | High | lib/api/client.ts |
| TC-FE-018 | ApiResponse 언랩 — data 추출 | 정상 응답 | 응답 | res.data=body.data, message 보존 | component | High | lib/api/client.ts |
| TC-FE-019 | ApiResponse success=false → ApiError | body.success=false | 응답 | ApiError.fromBody throw | component | High | lib/api/client.ts |
| TC-FE-020 | 401 토큰 레이스 1회 재시도 | 헤더 없이+토큰 존재 | 401 | Authorization 붙여 1회 재요청 | security | High | lib/api/client.ts |
| TC-FE-021 | 정상 401 → clear+상위 로그인 | 재시도 대상 아님 | 401 | clear()+redirectToUpstreamLogin() | security | High | lib/api/client.ts |
| TC-FE-022 | baseURL 환경변수만(Open Redirect 방어) | VITE_API_BASE_URL | 클라 생성 | env 값만 | security | High | lib/api/client.ts |
| TC-FE-194 | 412 → errorCode PRECONDITION_FAILED 매핑 (신규) | 본문 없는 412(blob 등) | ApiError.fromStatus(412) | errorCode='PRECONDITION_FAILED', 기본문구 "현재 상태에서는 수행할 수 없는 요청입니다."(구 INTERNAL_ERROR 대체 아님) | security | High | lib/api/errors.ts · lib/api/__tests__/errors.test.ts |
| TC-FE-195 | resolveApiMessage — 400/409/412만 서버 문구 노출 (신규) | ApiError | status 별 호출 | 400/409/412=userMessage, 401/403/5xx·비-ApiError=fallback(내부정보 미노출) | security | High | lib/api/resolveApiMessage.ts |
| TC-FE-196 | compactParams 빈 값 키 제거 · 0/false 보존 (신규) | 필터 조립 | `{status:'', page:0, sort:[]}` | status·sort 키 삭제, page=0 유지(→ `status=` 400 미발생) | component | High | lib/compactParams.ts · lib/__tests__/compactParams.test.ts |
| TC-E2E-001 | 토큰 없이 보호 경로 → 상위 로그인 | 미인증 | 보호 URL | 상위 시스템 로그인 | e2e | High | e2e/specs/login-redirect.spec.ts |

## H-2. 라우터 구조/코드스플리팅

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-023 | `/` → `/dashboard` redirect | 인증됨 | `/` | Navigate replace | component | Med | router/index.tsx |
| TC-FE-024 | `/label/:id`는 풀스크린 | WORKER/REVIEWER | 진입 | LNB/GNB 없음 | component | Med | router/index.tsx |
| TC-FE-025 | REVIEWER 전용 라우트 게이팅 | review/manage/augment/overall | WORKER 진입 | forbidden | security | High | router/index.tsx |
| TC-FE-026 | 알 수 없는 내부 경로 → 404 | 인증됨 | /xyz | AppErrorPage 404 | component | Med | router/index.tsx |
| TC-FE-027 | manage/* placeholder REVIEWER 게이팅 | 미정의 하위 | 진입 | REVIEWER만 | component | Low | router/index.tsx |
| TC-FE-028 | dev 라우트 플래그 OFF dead-code 제거 | VITE_DEV_LOGIN_ENABLED 미설정 | 빌드 | /dev/login 청크 미포함 | security | Med | router/index.tsx |
| TC-FE-029 | devUpload 라우트 REVIEWER 제한 | VITE_DEV_UPLOAD_ENABLED=true | 진입 | REVIEWER만(`dev/autolabel-test`) | security | Med | router/index.tsx |
| TC-FE-030 | PortalRoute 채널+역할 이중가드 | /portal/* | 진입 | ChannelGuard+RoleGuard | security | High | router/index.tsx |
| TC-FE-031 | lazyWithRetry 청크 로드 실패 재시도 | fetch 실패 | 재진입 | 재시도(Suspense fallback) | component | Med | router/lazyWithRetry.ts |
| TC-FE-032 | Suspense PageFallback 스피너 | 로딩 중 | 진입 | "페이지 로딩" | component | Low | router/index.tsx |
| TC-E2E-002 | 라우트 가드 스위트(deepLink/manage/portal/review/augment) | 각 역할 | 딥링크 | 정책대로 통과/차단 | e2e | High | router/__tests__/{deepLinkHydrationGuard,manageGuard,portalGuard,reviewGuard,augmentExportGuard}.test.tsx |

## H-3. LabelingPage (라벨링 캔버스)

> ★ 이번 회차 변경: 저장 실패 처리에 **409 충돌 다이얼로그**가 추가되고(기존 단일 에러 토스트에서 분기), 저장 요청이 **`labelVersion` 낙관적 토큰**을 싣는다. 비식별 신고 버튼은 **파생영상이면 사전 비활성**된다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-304 | ★우측 속성 패널 — 두 분기의 레이아웃 계약 동일 (신규 2026-08-04) | AI 분할(SAM_SEGMENT) 활성 | 선택 객체 **있음/없음** 각각 렌더 | 루트 `<aside>` 클래스가 `gap-*` 을 제외하고 **완전 일치**. `overflow-y-auto`·`flex-1`·`min-h-0`·`w-full` **보유**, `h-full`·`w-72` **미보유**(재도입 금지). 근본 결함이 "두 분기 비대칭"이었으므로 계약 동일성 자체를 결박한다 | component | High | features/label/components/ObjectAttributePanel.tsx · features/label/\_\_tests\_\_/ObjectAttributePanel.segmentTolerance.test.tsx |
| TC-FE-305 | ★선택 객체 없어도 AI 분할 컨트롤 2종 모두 조작 가능 (신규 2026-08-04) | AI 분할 활성 + 선택 객체 0건 | 슬라이더 조작 · 체크박스 클릭 | `경계 세밀함` 슬라이더 **와** `즉시 그리기` 체크박스가 둘 다 쿼리·enabled·콜백 발화. 구 결함에서는 체크박스가 **아예 렌더 영역 밖으로 잘려** 보이지 않았다 | component | High | features/label/components/ObjectAttributePanel.tsx · features/label/\_\_tests\_\_/ObjectAttributePanel.segmentTolerance.test.tsx |
| TC-FE-306 | ★**해소(2026-08-06)** — 좌측 도구바 하단 `저장` 버튼 잘림 (신규 2026-08-04 미해결 → 해소) | 짧은 뷰포트(≈690px 이하) | 라벨링 화면 진입 | 구 결함: 툴바가 `overflow-hidden` 조상 안의 flex 아이템인데 **자체 스크롤 계약이 없어** 하단 버튼이 잘리고 **영구 클릭 불가**(실측 700px 에서 여유 10px). **수정**: 버튼 목록에 `TOOLBAR_SCROLL_CLASS`(`flex-1`+`min-h-0`+`overflow-y-auto`+`overflow-x-hidden`) 적용 + **툴팁을 `createPortal` 로 body 분리**(`position: fixed`). 툴팁을 상자 안에 두면 `overflow-y` non-visible 이 `overflow-x` 를 auto 로 강제해 툴팁이 함께 잘리므로 portal 이 **선행 조건**이었다. ⚠ jsdom 은 레이아웃 미계산이라 폭·높이 단언은 거짓 통과 — 구조 계약(스크롤 클래스·저장 버튼이 상자 안·툴팁이 상자 밖)만 고정한다 | component | Med | features/label/components/ToolBar.tsx(`TOOLBAR_SCROLL_CLASS` · `ToolBar`) · features/label/\_\_tests\_\_/ToolBarScrollContract.test.tsx(`버튼_목록이_자체_스크롤_계약을_가진다_조상이_자르지_않는다` · `★툴팁은_스크롤_상자_밖_body_로_portal_되어_함께_잘리지_않는다`) |
| TC-FE-033 | 잘못된 ID(NaN) 다크 에러 | id 비숫자 | 진입 | "잘못된 프레임 ID"+뒤로가기 | component | High | pages/label/LabelingPage.tsx(⚠병합 후 라인드리프트 재확인 필요) |
| TC-FE-034 | 로딩 상태 스피너 | isLoading | 진입 | `Spinner label="라벨 로딩"` → `role=status`+`aria-live=polite`+`aria-label="라벨 로딩"`+sr-only 텍스트 (구 "data-testid" 표기는 오류 — 스피너에 data-testid 는 없고 페이지 컨테이너만 `data-testid="labeling-page"`) + "라벨 로딩 중..." 문구 | component | Med | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) · components/common/Spinner.tsx |
| TC-FE-035 | 포털 403 → graceful 차단화면 | portalMode+403 | 진입 | "접근할 수 없는 영상입니다" | security | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-036 | 일반 에러 → "라벨 조회 실패" | error | 진입 | 에러 문구(=BE message)+뒤로가기 | component | Med | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-037 | siblings 비면 현재 프레임 단건 폴백 | siblings=[] | 렌더 | 현재 1건 | component | Med | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-038 | 저장 성공 토스트 "저장됨" | dirty 라벨 | handleSave | updateLabels+clearDirty+'저장됨' | component | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-039 | 저장 중복 제출 차단 | saving in-flight | Ctrl+S 연타 | 두번째 무시 | component | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-040 | 잠금 영상 저장 차단 | isLocked | handleSave | 에러 토스트, PUT 미발생 | security | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-041 | 저장 실패 에러 토스트(BE 문구 우선) | 409 외 reject | handleSave | `extractBeMessage(e,'저장 실패')` 토스트 (구 `e.message` 직접 노출 아님) | component | Med | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-042 | 포털 모드 저장 경로 분기 | portalMode | handleSave | savePortalLabels(원본 미수정) | security | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-043 | 프레임 이동 dirty 가드 모달 | dirtyCount>0 | requestJumpTo | FrameNavGuardModal | component | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-044 | 같은 프레임 이동 no-op | target===현재 | requestJumpTo | 무시 | component | Med | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-045 | 저장 후 이동 — 실패 시 취소 | 저장 실패 | handleNavSaveAndMove | 에러+현 프레임 유지 | component | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-046 | 저장 안 함 이동 — dirty 폐기 | navGuardTarget | handleNavDiscardAndMove | clearDirty 후 이동 | component | Med | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-047 | 프레임 전환 시 setLabels 전체 교체 | srcSn 변경 | data effect | setLabels+dirty 초기화 | component | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-048 | 같은 프레임 refetch는 dirty 있으면 미덮음 | dirty>0, 백그라운드 | data effect | 서버 라벨로 안 덮음 | component | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-049 | 보류 추적 drain 병합 | pendingTracks | srcSn effect | mergeAutoLabels + info 토스트 **"보류된 AI 추적 N건 적용됨"** | component | High | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |
| TC-FE-050 | 언마운트 시 store reset | 이동 | unmount | reset() | component | Med | pages/label/LabelingPage.tsx(⚠라인드리프트 재확인 필요) |

> ⚠ **병합 라인드리프트 안내(2026-08-04, main PR #79 병합)**: 위 TC-FE-033~050 은 `qa-0803`(회차 6)의 검증된 기대결과 서술을 유지하되, `main` 의 표시명 노출(PR #79)·자동마킹 수정(PR #77) 이 같은 `LabelingPage.tsx` 를 별도로 바꿔 병합 후 실제 라인이 두 브랜치 어느 쪽 인용과도 정확히 일치하지 않는다. 다음 회차에서 `file:line` 전수 재확인 시 우선 정정 대상.
| TC-FE-051 | AI 탐지 팝업 매핑 라벨만 선택 | detectCandidates 혼합 | AiToolModal | 미매핑 disabled+"미매핑", canRun=mappedCount>0 | component | High | features/label/components/AiToolModal.tsx |
| TC-FE-052 | AI 탐지 mock 응답 자동적용 차단 | res.message 존재 | runAiTool detect | 경고 토스트, 병합 안 함 | security | High | pages/label/LabelingPage.tsx |
| TC-FE-053 | AI 탐지 결과 작업본 병합(중복 스킵) | 정상 응답 | runAiTool | mergeAutoLabels+"N건 적용됨" | component | High | pages/label/LabelingPage.tsx |
| TC-FE-054 | 폴리곤 shape → "AI 분할" 라벨 | POLYGON | runAiTool | 토스트 kind="AI 분할" | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-055 | 트랙 모드 — 객체 선택 유도 | mode=track | runAiTool | TRACK 도구+안내 | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-056 | 추적 결과 현재/미래 프레임 분리 | tracked 혼합 | handleTracked | 현재=즉시병합, 미래=stash | component | High | pages/label/LabelingPage.tsx |
| TC-FE-057 | 부분 추적 실패 경고 | partial=true | handleTracked | warning 토스트 `` `${applied}/${total} 프레임만 추적됨 (일부 실패)` `` (total=`nextSrcSns.length \|\| tracked.length`) | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-058 | nextSrcSns 계산(현재 이후) | frameIdx | useMemo | slice(frameIdx+1) | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-059 | SAM2 추적 청크 50개 상한 정합 | nextSrcSns>50 | sam2TrackAllChunks | 50개 이하 분할(BE @Size max=50) | security | High | features/label/api.ts:sam2TrackAllChunks |
| TC-FE-060 | SAM2 추적 stale 가드(프레임 전환) | 응답 전 전환 | 병합 직전 | requestedSrcSn≠현재면 폐기 (구현은 `onSuccess` 비교가 아니라 busy 토큰 `isAlive()` 로 판정 — 정정) | component | High | features/label/hooks/useSam2Track.ts · features/label/hooks/useBusyTask.ts |
| TC-FE-061 | SAM2 부분 실패 성공분만 병합 | ChunkError.partial | catch | 성공분 onTracked(partial=true) | component | Med | features/label/hooks/useSam2Track.ts |
| TC-FE-062 | BBOX 추적 시드 외접박스 4점 확장 | BBOX 청크 | seedPolygon | 2점→4점(@Size min=3) | component | High | features/label/api.ts:toSeedPolygon |
| TC-FE-063 | 트랙 rename/삭제/분할 포털 차단 | portalMode | handleRename/Delete/Split | 조기 return(403 방어) | security | High | pages/label/LabelingPage.tsx |
| TC-FE-064 | 잠금 영상 트랙 편집 차단 | isLocked | 각 핸들러 | 에러 토스트+미실행 | security | High | pages/label/LabelingPage.tsx |
| TC-FE-065 | 트랙 rename 성공 후 invalidate | 정상 | mergeTracks | byVideo invalidate+토스트 | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-066 | 비식별 신고 성공 → 잠금+reset+**캐시 제거** (정정 2026-08-05) | 신고 성공 | handleDeidentReportSuccess | reportedLock=true, store reset, `LABEL_KEYS.byVideo(srcSn)` **`removeQueries`**(구 `invalidateQueries` 폐기 — 재진입 노출 창은 TC-FE-315). **★BE 는 라벨을 삭제하지 않는다(2026-07-27 보존 정책 반전)** — 재조회는 신고 게이트로 **412**(`errorCode=PRECONDITION_FAILED` + 행위 중립 문구 "비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.") 가 되어 **"라벨 조회 실패"+서버 안내문 화면**이 뜬다(빈 라벨 화면 아님 — 두 화면은 상호배타). 국면 ①(즉시 0건)과 ②(412 안내)를 모두 관측한다. 활성 화면에서는 remove/invalidate 결과가 같다 | security | High | pages/label/LabelingPage.tsx(핸들러 · 정책 주석 — 왜 invalidate 로 부족한지 · 에러 화면 — "라벨 조회 실패"+`error.message`) · BE `LabelService.java`(인가 직후 게이트) · `LabelAccessGuard.java`(412+문구) · **테스트 `features/label/__tests__/LabelingPageDeidentReport.test.tsx`**(2026-08-05 실계약 정합 — 구 mock `200+0건` 폐기) · CLAUDE.md 비식별 누락 신고 |
| TC-FE-315 | **★신고 후 재진입 노출 창 차단 — 캐시에 라벨 좌표가 남지 않는다 (신규 2026-08-05, CWE-359)** | 신고 성공 직후 **재조회가 끝나기 전에** 화면 이탈 → `staleTime`(30s) 내 같은 프레임 재진입(동일 QueryClient) | handleDeidentReportSuccess → unmount → remount | ①`queryClient.getQueryData([...LABEL_KEYS.byFrame(srcSn,0),'internal'])` = **undefined**(캐시 데이터 잔존 0) ②재진입 시 **로딩 상태**("라벨 로딩 중...")로 진입하고 `객체 수` 요소가 **없음**(캐시된 라벨 미렌더) ③서버 재조회가 412 → "라벨 조회 실패"+서버 안내문. ⚠ `invalidateQueries` 로 되돌리면 stale 표식만 붙고 **데이터가 남아** 재진입 시 라벨 좌표가 먼저 렌더된다(`reportedLock` 은 컴포넌트 상태라 재마운트로 초기화 → 잠금 배너도 없음) — **RED 실증 완료**(단언 ①이 `expected {frameNo:0, srcSn:300, …} to be undefined` 로 FAIL). ⚠ 테스트는 `gcTime` 을 프로덕션 기본(5분)으로 둔 전용 QueryClient 를 쓴다 — 공용 `createTestQueryClient` 는 `gcTime:0` 이라 unmount 즉시 GC 되어 가드가 무력화된다 | security | High | pages/label/LabelingPage.tsx · features/label/hooks/useLabels.ts(staleTime 30s·keepPreviousData) · **테스트 `features/label/__tests__/LabelingPageDeidentReport.test.tsx`** |
| TC-FE-067 | 잠금 배너 노출 (정정 2026-08-04 · H-ISSUE-41 수정 반영) | LOCKED_FOR_REDEIDENT/reportedLock | 렌더 | role=status 배너 — **서버 잠금 경로·`reportedLock` 경로 양쪽 모두 발화**. ⚠ 구 기재 *"BE 가 `lockSttsCd=\"LOCKED\"` 를 내려 서버 잠금 경로에서는 배너가 영영 뜨지 않는다"* 는 **폐기** — `LabelService.getByFrame` 이 응답 계약 상수 `LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT`(=`"LOCKED_FOR_REDEIDENT"`)를 내려보내도록 정정됐고, FE 판정도 리터럴 대신 `LockSttsCd.LOCKED_FOR_REDEIDENT` 상수를 쓴다. 락 행 상태값(`LsAuthWorkLock.STATUS_LOCKED='LOCKED'`)은 내부 저장 모델이라 응답 계약과 축이 다르다 | component | Med | pages/label/LabelingPage.tsx · backend LabelService.java · LabelResponse.java |
| TC-FE-067a | 잠금 코드 BE↔FE 계약 동치 (신규 2026-08-04 · H-ISSUE-41 회귀 가드) | — | BE 응답 상수/실값 ↔ FE 판정 상수 대조 | `LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT` == `getByFrame` 응답 실값 == FE `types.ts` `LockSttsCd.LOCKED_FOR_REDEIDENT`. 어긋나면 서버 잠금이 화면에 반영되지 않으므로 **오탈자성 드리프트를 양방향으로 고정**(선례: `CocoClassesDriftTest` 의 ai-server 정본 대조) | component | High | backend LabelServiceLockSttsCdTest.java · frontend `__tests__/lockSttsCdContract.test.tsx` |
| TC-FE-067b | 잠금 저장 시도는 잠금 안내이며 저장충돌(409) 안내가 아니다 (신규 2026-08-04 · H-ISSUE-42 회귀 가드) | `lockSttsCd=LOCKED_FOR_REDEIDENT`(서버 단독 잠금) | Ctrl+S | `비식별 재처리 중인 영상은 저장할 수 없습니다.` 토스트 + **PUT 미발생** + "최신 라벨 불러오기"(409 충돌 다이얼로그) **미노출**. 잠금이 아닌 순수 낙관적 충돌(409)에서는 반대로 충돌 다이얼로그가 뜬다 — 두 원인이 서로 다른 UI 로 구분됨 | component | High | pages/label/LabelingPage.tsx · frontend `__tests__/lockSttsCdContract.test.tsx` |
| TC-FE-068 | 비식별 신고 버튼 — RAW 프레임 disabled | frameImageType='RAW' | 렌더 | disabled | security | Med | pages/label/LabelingPage.tsx |
| TC-FE-069 | 비식별 신고 버튼 포털 미노출 | portalMode | 렌더 | canReportDeident=false → null | security | High | pages/label/LabelingPage.tsx |
| TC-FE-070 | 검수제출 버튼 WORKER만 | isWorker+data | 렌더 | submitButton | component | High | pages/label/LabelingPage.tsx |
| TC-FE-071 | 상태별 제출 차단(REVIEW_PENDING/REVIEWING) | 비제출가능 | 렌더 | disabled+hint title | component | High | pages/label/LabelingPage.tsx |
| TC-FE-072 | APPROVED 재검수 라벨 | COMPLETED | 렌더 | "재검수 제출" 문구 | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-073 | 제출 취소 버튼 REVIEW_PENDING만 | canCancelSubmit | 렌더 | "제출 취소" | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-074 | 검수제출 성공 → /task 이동+토스트 | submitForReview | onSuccess | "검수 제출 완료"+navigate | component | High | pages/label/LabelingPage.tsx |
| TC-FE-075 | X 닫기 dirty 시 3옵션 모달 | dirtyCount>0 | handleClose | closeConfirm 모달 | component | High | pages/label/LabelingPage.tsx |
| TC-FE-076 | beforeunload dirty 경고 | dirtyCount>0 | 탭 닫기 | native 경고 | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-077 | 우측 탭 — 메타/이슈 내부 채널만 | portalMode | 렌더 | 메타·이슈 탭 미노출 | security | High | pages/label/LabelingPage.tsx |
| TC-FE-078 | 이슈 탭 미해소 배지 카운트 | unresolvedInquiries>0 | 렌더 | danger 배지+aria-label | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-079 | 메타 탭 — 촬영환경/개인정보(영상)/개인정보(프레임)/설명/시계열메타/이벤트 패널 | rightTab=meta, 내부 | 렌더 | **6개 패널**(순서: `EnvironmentMetaPanel`→`VideoPrivacyMetaPanel`→`FramePrivacyMetaPanel`→`FrameDescriptionPanel`→`TimeseriesSidePanel`→`EventAnnotationPanel`). *구 기대값 "5개 패널"은 폐기 — 커밋 `0d290c4e`(영상 단위 개인정보 메타 화면)로 `VideoPrivacyMetaPanel` 이 2번째에 신설됨. 3차 실측 헤딩: 촬영환경 / 개인정보(영상) / 개인정보(프레임) / 프레임 설명 / 시계열 메타 / 이벤트 어노테이션.* ★**시계열 메타 패널은 텍스트 수정·저장 전용**이며 검토(승인/반려) 표면이 없다(2026-08-03 확정, TC-FE-276~278). 승인/반려 UI 가 있는 것은 **이벤트 어노테이션 패널뿐** | component | High | pages/label/LabelingPage.tsx |
| TC-FE-080 | 뷰(zoom/pan) 유지 vs 리셋 | 동일영상+동일해상도 | handleImageSize | shouldResetView false → 유지 | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-081 | 붙여넣기 실측 dims clamp | frameNaturalSize | onPasteLabels | imageWidth/Height clamp | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-082 | 복사 — 빈 선택 no-op 토스트 | 라벨 없음 | onCopyLabels | "복사할 라벨이 없습니다." | component | Low | pages/label/LabelingPage.tsx |
| TC-FE-083 | 잠금 영상 붙여넣기 차단 | isLocked | onPasteLabels | 에러+미실행 | security | Med | pages/label/LabelingPage.tsx |
| TC-FE-084 | 저장 되돌리기 확인 모달 | 히스토리 카드 | handleRevertRequest | ConfirmDialog | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-085 | 되돌릴 항목 없음 경고 | reverted=0 | confirmRevert | warning 토스트 **"되돌릴 항목이 현재 작업본에 없습니다."** *(구 기대문구 "되돌릴 항목이 없습니다" 는 실제 문자열과 불일치 — 3차 실측 정정)* | component | Low | pages/label/LabelingPage.tsx |
| TC-FE-086 | 캔버스 lazy 마운트(konva 분리) | currentFrame | 렌더 | CanvasShell Suspense | component | Med | pages/label/LabelingPage.tsx |
| TC-FE-087 | 히스토리 인라인 패널 내부만 | historyOpen+!portalMode+srcSn 존재 | 렌더 | `inline-history-panel` 안에 `HistoryPanel`(변경이력·버전 탭). ★2026-08-03 부로 **버전·diff·롤백의 유일한 진입점**이다(구 전용 페이지 `/history/:videoId` 삭제 — TC-FE-299) | component | **High** | pages/label/LabelingPage.tsx |
| TC-FE-197 | 저장 409 → 충돌 다이얼로그(작업 보존) (신규) | 다른 사용자가 먼저 저장 | handleSave → ApiError status=409 | 에러 토스트가 아니라 **"다른 사용자가 먼저 저장했습니다"** ConfirmDialog. **dirty 유지**(내 작업 미폐기), 확인=최신 라벨 재조회(clearDirty+refetch), 취소="내 작업 유지" | component | High | pages/label/LabelingPage.tsx |
| TC-FE-198 | 저장 요청에 labelVersion 동봉 (신규) | 조회 응답 labelVersion 존재 | PUT /frames/{srcSn}/labels | body 에 `labelVersion` 포함(값 없으면 필드 자체 생략 → BE 하위호환 skip 경로) | security | High | features/label/api.ts:putLabels · features/label/types.ts:labelVersion |
| TC-FE-199 | 연속 저장 시 캐시 버전 우선(자기 409 방지) (신규) | 1회차 저장 성공 직후 2회차 | handleSave 연속 2회 | 성공 콜백이 `setQueryData` 로 캐시 버전을 동기 갱신 → 2회차는 **최신 버전** 전송(렌더 클로저 값 아님), 409 미발생 | component | High | features/label/hooks/useUpdateLabels.ts · features/label/hooks/__tests__/useUpdateLabels.test.tsx |
| TC-FE-200 | 파생영상 — 비식별 신고 버튼 사전 비활성 (신규) | `VideoDetailResponse.derivative=true` | 라벨링 진입 | 버튼 disabled + title/aria-label 에 "증강·해상도 변환으로 만든 파생영상이라 …" 사유. **원본으로 유도하지 않고 부모 rawSn 도 표시하지 않는다** | security | High | pages/label/LabelingPage.tsx · features/label/components/DeidentReportButton.tsx |
| TC-FE-201 | 신고 412 — 서버 안내문 그대로 노출 (신규) | 화면이 파생 여부를 모름(구 응답) | 신고 제출 → 412 | `resolveApiMessage` 로 **BE 안내문**을 폼 내 role=alert 에 표시(구: INTERNAL_ERROR 일반문구로 대체됨) | security | High | features/label/components/DeidentReportButton.tsx · features/label/__tests__/DeidentReportButton.test.tsx |
| TC-FE-202 | 이벤트 어노테이션 cot 객체형 정규화 (신규) | `cot={"1단계":"…","2단계":"…"}` | toCaptionRows | 배열/객체 양형 모두 3단계 배열로 정규화(크래시 없음, 키 순서 유지) | component | High | features/label/api/eventAnnotation.ts:normalizeCot · features/label/components/eventAnnotationForm.ts:toCaptionRows |

### 장시간 작업(busy) 편집 차단 · 진행 표시 · 취소 — 2026-07-31 신설

> 대상: AI 탐지 / AI 분할 / AI 추적 / 저장 / 불러오기 5종이 **단일 배타 축**. 규칙 전문 → [v2-wiki 10 §10.6](../v2-wiki/10-labeling.md). **차단은 요청 거부가 아니라 입력 차단**이며, **취소는 클라이언트 결과 폐기이지 서버 중단이 아니다**.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-261 | busy 중 캔버스·툴바·프레임이동·실행버튼 차단 (신규) | busy(SAVE/AI\*) 진행 중 | 캔버스 그리기·선택·삭제 / 슬라이더·필름스트립·버튼 / 저장·검수제출·AI 실행 | 전부 무반응(캔버스 readOnly, 버튼 disabled). **입력 단계에서** 막혀 드래그가 시작되지 않는다 | component | High | features/label/__tests__/editBlocking.test.tsx · stores/useLabelStore.ts:useIsEditBlocked |
| TC-FE-262 | 되돌리기·버전 롤백·비식별 신고도 차단 (신규) | busy 진행 중 | 각 버튼 | 실행되지 않음(신고 성공 시 `reset()` 이 진행 작업을 조용히 취소하던 경로 차단) | security | High | features/label/__tests__/editBlocking.test.tsx(신고 · ESC·취소) · features/label/components/LabelHistoryPanel.tsx(버전 롤백 — ⚠ 롤백 축은 자동 테스트 0건, 정적+실동작만) |
| TC-FE-263 | 단축키 차단은 fail-closed (신규) | busy 시작 커밋과 리렌더 **사이**(렌더 값은 아직 blocked=false) | `D`/`B`/`R`/`Ctrl+S` keydown | 전부 무시. 판정 = 렌더 값 **OR 실시간 store**(`isEditBlockedNow`) — 렌더 값 단독 판정이던 창을 닫음 | security | High | features/label/hooks/useLabelingShortcuts.ts(fail-closed OR 판정, ESC, 키맵 차단) · features/label/__tests__/shortcutsFailClosed.test.tsx |
| TC-FE-264 | 300ms 초과부터 진행 오버레이 (신규) | 저장/AI 작업 진행 | 지연 창 안 / 초과 | <300ms 미표시(즉시 그리기 깜빡임 방지), 초과 시 **작업명 + 경과 초 + 취소 버튼**. 문구에 모델명(YOLO/SAM/SAM2)·식별자·경로 없음 | component | High | features/label/components/BusyOverlay.tsx · features/label/busyPolicy.ts |
| TC-FE-265 | 취소 = 결과 폐기(서버 중단 아님) (신규) | 오버레이 표시 중 | 취소 버튼 클릭 / Enter·Space / ESC | busy 즉시 해제 + 편집 복귀. **취소 후 도착한 응답은 같은 프레임이어도 미반영**(세대 토큰), dirty 유지 | component | High | features/label/hooks/useBusyTask.ts · features/label/busyPolicy.ts |
| TC-FE-266 | ESC 취소는 AI 분할 확정 큐도 비운다 (신규) | 지연 창에서 Enter 로 확정 큐잉 후 ESC | busy 해제 | 큐잉된 확정이 **자동 발사되지 않는다**(취소와 정반대 동작 차단). 누적점은 보존 | component | High | features/label/canvas/layers/OverlayLayer.tsx(ESC 분기 · `setPendingConfirm(false)`) · .../__tests__/OverlayLayerSegmentBusy.test.tsx |
| TC-FE-267 | busy 5분 fail-safe 자동 해제 (신규) | 응답 누락 | 5분 경과 | busy 자동 해제(화면 영구 잠금 방지). 뒤늦게 도착한 결과는 토큰 사망으로 폐기 | component | Med | features/label/hooks/useBusyTask.ts |
| TC-FE-268 | 메타 편집은 busy 와 독립(의도 고정) | busy 진행 중 | 프레임 설명·촬영환경·개인정보 메타·이벤트 어노테이션·**시계열 메타 텍스트 수정·저장** | **차단되지 않고 편집·저장된다**. 라벨 작업본과 공유 상태가 없다 — 깨지면 회귀가 아니라 정책 변경. ⚠ 5번째 항목은 구 "시계열 메타 **검수 승인**"에서 **텍스트 수정·저장**으로 정정됐다(2026-08-03 검토 UI 제거, `POST /meta/{sn}/approve` 호출 자체가 사라짐) | component | High | features/label/__tests__/metaEditBusyIndependence.test.tsx |
| TC-FE-269 | 툴바 버튼 포커스 중 Space 는 팬이 아니다(표준 동작 고정) (신규) | 툴바 버튼 클릭 직후(포커스 유지) | Space | 팬 홀드 미발동 + **그 버튼이 활성화**된다(APG). 활성화가 포커스를 훔치지 않으며, 포커스가 버튼을 떠나면 Space 팬이 정상 복귀 | a11y | Med | features/label/canvas/CanvasShell.tsx(활성화 대상 판정, Space keydown 가드) · features/label/canvas/__tests__/CanvasShellSpaceActivation.test.tsx |
| TC-FE-270 | 크로스탭 동시성은 busy 범위 밖 (신규) | 다른 탭/사용자가 먼저 저장 | 저장 | FE busy 는 **같은 탭 한정**. 교차 수정은 서버 낙관적 잠금 409 → 충돌 다이얼로그(TC-FE-197)가 담당 | security | High | pages/label/LabelingPage.tsx 저장 catch(409 → setSaveConflictMessage) · docs/v2-wiki/10-labeling.md §10.6 |

### 메타 탭 시계열 메타 — 검토(승인/반려) UI 제거 — 2026-08-03 신설

> **결정 1 (2026-08-03 사용자 확정, 커밋 `80171828`)**: 라벨링 화면(SC-005) 우측 '메타' 탭의 시계열 메타 패널(`TimeseriesSidePanel`)에서
> **검토 상태 배지 · 승인/반려 버튼 · 반려 사유 입력을 제거**했다. 남은 것은 **텍스트 수정·저장뿐**이다.
>
> - **구 정책 → 폐기**: "REVIEWER 가 라벨링 화면에서 시계열 메타를 승인/반려한다"는 동선은 폐기됐다. 실사용상 승인 완료 영상은 검토행이 전부 `APPROVED` 라
>   "검토 상태 승인됨" 줄만 메타 개수만큼 반복됐고, `metaKey` 미표시로 어느 메타의 상태인지 식별조차 불가능했으며, 상태 배지에 역할 가드가 없어 WORKER 에게도 노출됐다.
> - **BE 는 존치 — 케이스를 지우지 말 것**: `POST /v1/meta/{metaReviewSn}/approve|reject`(`MetaController.java:81-106`)와 `LS_DATA_META_REVIEW` 는 그대로다. **FE 진입점만 없다.**
>   FE 클라이언트(`approveMetaReview`/`rejectMetaReview`)와 훅(`useMetaReview`)은 제거됐다.
> - 검토 상태 확정의 **유일한 경로는 영상 검수 승인 시 BE 자동 동결**(`MetaService.autoApproveOnVideoApproval` → [TC-REVIEW-016](D-review-version-notify.md))이며, 데이터마트 `V_COMPLETED_META` 의 `RVW_STTS_CD='APPROVED'` 게이트는 불변이다.
> - **검수 화면(SC-019)의 읽기 전용 `ReviewMetaPanel` 상태 배지는 유지**된다 — 이번 제거 대상이 아니다.
> - 규칙 전문 → [v2-wiki 09 §9.3](../v2-wiki/09-vlm-timeseries.md)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-276 | REVIEWER + PENDING 검토행이어도 검토 UI 미노출 (신규) | REVIEWER·INTERNAL, `dataMetaReviewSn` + `reviewStatus='PENDING'` 인 메타 | 메타 탭 렌더 | textarea·저장 버튼은 있고 `ts-review-actions`/`ts-review-status-*`/`ts-approve-*`/`ts-reject-*`/`ts-reject-reason-*`·"검토 상태"·"승인"·"반려" 는 **전부 없음**. 깨지면 회귀가 아니라 정책 변경. ⚠ 2026-08-03 후속 커밋(`5c10c0cd`)에서 패널이 **세그먼트(metaKey)별 textarea** 로 재구현됐으나(구 단일 textarea 폐기) 검토 UI 부재 결론은 불변 | component | High | features/label/components/TimeseriesSidePanel.tsx · features/label/components/\_\_tests\_\_/TimeseriesSidePanel.test.tsx |
| TC-FE-277 | APPROVED 검토행이어도 "승인됨" 배지 미노출 (신규) | 승인 완료 영상(실사용 대다수) | 메타 탭 렌더 | 배지 없음. 값은 그대로 편집 가능 — 배지만 메타 개수만큼 반복되던 표면 제거 | component | High | TimeseriesSidePanel.tsx · \_\_tests\_\_/TimeseriesSidePanel.test.tsx |
| TC-FE-278 | 검토행이 붙어 있어도 텍스트 수정·저장은 회귀 없음 (신규) | 검토행 보유 메타 | 텍스트 수정 → 저장 | `PUT /frames/{srcSn}/meta` 1회. **편집한 세그먼트(metaKey)만** 전송(0건이면 `manual-timeseries` 신규 슬롯), 원본과 같거나 공백만인 세그먼트는 저장 대상에서 제외 | component | High | TimeseriesSidePanel.tsx · \_\_tests\_\_/TimeseriesSidePanel.test.tsx |

### 도구 클릭 → 라벨 선택 모달 → 드로잉 · 좌측 라벨 패널 폐지 — 2026-08-03 신설

> **결정 3 (2026-08-03 사용자 확정, 커밋 `d8a7a2cc`)**: 라벨링 조작 흐름을 **"도형 도구 클릭 → 라벨 선택 모달 → 라벨 확정 후 드로잉"** 으로 바꿨다.
>
> - **구 UI → 폐기**: 좌측 **상시 라벨 패널(`LabelSidebar`)** 은 컴포넌트·테스트째로 삭제됐고, 거기 있던 **전역 1~9 라벨 선택 단축키도 `SHORTCUT_KEYMAP` 에서 제거**됐다(단축키 도움말에서도 빠짐).
>   패널이 없으면 전역 1~9 는 아무 시각 피드백 없이 "다음 도형의 라벨"을 바꾸는 조용한 상태 변경이 되기 때문이다. 1~9 는 **모달 안에서만** 동작한다.
> - **라벨 선택 목록의 출처는 라벨 마스터(`LS_LABEL`) 전체**다. **프리셋(`LS_LABEL_PRESET_CODE`)은 오토라벨링 전용**이라 이 목록에 쓰지 않는다 — 프리셋을 소스로 되돌리지 말 것.
> - **판정은 `useToolLabelPicker` 한 곳**(도구 전이 감시)이다. 툴바 클릭·키보드 단축키가 각자 모달을 띄우게 배선하면 한쪽이 반드시 뒤처진다(이 저장소의 "진입점마다 정책 복제" 결함 패턴 → [state-gate 단일 진입점 규칙]).
> - 규칙 전문 → [v2-wiki 10 §10.2.1](../v2-wiki/10-labeling.md) · [04 SC-005](../v2-wiki/04-screens-ia.md)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-279 | 도형 도구 클릭 시 라벨 선택 모달 노출 (신규) | 라벨링 진입(SELECT 활성) | 툴바에서 BBOX/POLYGON/AI분할/스켈레톤 클릭 | `LabelPickerModal` 오픈 + 안내 문구에 도구명. **라벨을 고르기 전에는 캔버스 드로잉이 시작되지 않는다** | component | High | features/label/hooks/useToolLabelPicker.ts · pages/label/LabelingPage.tsx · features/label/\_\_tests\_\_/LabelingPageLabelPicker.test.tsx |
| TC-FE-280 | 취소 시 도구 미활성 + 이전 도구 복귀 (신규) | 모달 열림 | 취소 버튼/ESC/닫기 | `activeTool` 이 직전 도구로 되돌아가고, **복귀 전이는 모달을 다시 띄우지 않는다**(`suppressRef` 1회 억제 — 이전 도구도 라벨 필요 도구일 수 있어 없으면 무한 재노출) | component | High | useToolLabelPicker.ts · LabelingPageLabelPicker.test.tsx |
| TC-FE-281 | 라벨 확정 시 도구 활성 + activeLabelId 기록 (신규) | 모달에서 라벨 클릭 | confirm | `setActiveLabelId(labelId)` + 모달 닫힘 + 해당 도구로 드로잉 가능 | component | High | useToolLabelPicker.ts · LabelingPageLabelPicker.test.tsx |
| TC-FE-282 | 같은 도구로 연속 드로잉 시 모달 재노출 없음 (신규) | 도구 유지 상태 | 도형을 여러 개 연속 작성 | 재노출 조건 = **도구 전이 1회**. 도형마다 뜨지 않고 마지막 선택 라벨이 유지된다 | component | High | useToolLabelPicker.ts · LabelingPageLabelPicker.test.tsx |
| TC-FE-283 | 같은 도구 **재클릭** 은 라벨 교체 동선 (신규) | 이미 활성인 도형 도구 | 그 툴바 버튼을 다시 클릭 | 전이가 없어 감시로는 못 잡히므로 `requestTool` 이 직접 모달을 연다(라벨을 바꿀 유일한 동선). 라벨 불필요 도구 재클릭은 no-op | component | High | useToolLabelPicker.ts · pages/label/LabelingPage.tsx |
| TC-FE-284 | 라벨을 만들지 않는 도구는 모달 미노출 (신규) | 선택/이동(팬)/AI 추적/마스크 브러시·지우개/삭제/실행취소 | 도구 전환·버튼 클릭 | 모달 없음. 대상은 `LABEL_REQUIRED_TOOLS` 4종(BBOX·POLYGON·SAM_SEGMENT·KEYPOINT)뿐 — `OverlayLayer` 가 `resolveDefaultLabel` 로 새 라벨 classId/className 을 확정하는 도구 집합과 동일 | component | High | useToolLabelPicker.ts · LabelingPageLabelPicker.test.tsx |
| TC-FE-285 | 진입 경로 무관 단일 판정(툴바 = 단축키) (신규) | 단축키 B/P/G/K 로 도구 전환 | keydown | 툴바 클릭과 **동일한 모달**이 뜬다. 판정은 `activeTool` 전이 감시 1곳이라 진입점이 늘어도 정책이 갈리지 않는다 | security | High | useToolLabelPicker.ts · LabelingPageLabelPicker.test.tsx |
| TC-FE-286 | 목록 = 활성 라벨 마스터 전체(프리셋 아님) (신규) | 활성 마스터 N건 + 프리셋 존재 | 모달 렌더 | `useLabelMasters`(`GET /v1/manage/labels`)만 조회하고 **프리셋 API 는 호출하지 않는다**. `useYn='Y'` 만, 정렬 `sortNo asc → labelId asc` | component | High | features/label/components/LabelPickerModal.tsx · components/\_\_tests\_\_/LabelPickerModal.test.tsx |
| TC-FE-287 | 이름 검색 필터 + 결과 0건 안내 + 재오픈 초기화 (신규) | 마스터 다수 | 검색어 입력 / 재오픈 | 표시명 부분일치(대소문자 무시) 필터, 0건이면 "검색 결과가 없습니다"(`aria-live`), 모달을 다시 열면 검색어 초기화(이전 검색어로 빈 목록처럼 보이는 것 방지) | component | Med | LabelPickerModal.tsx · LabelPickerModal.test.tsx |
| TC-FE-288 | 1~9 는 모달 전용 — 전역 키맵에서 제거 (신규) | 라벨링 화면(모달 닫힘) | 전역 `1` keydown | `activeLabelId` **불변**(전역 미발화). 전역 `SHORTCUT_KEYMAP` 에 숫자 바인딩이 0건이고, 순번 선택은 모달 자체 리스너가 처리 | security | High | features/label/hooks/labelingKeymap.ts · LabelPickerModal.tsx · features/label/\_\_tests\_\_/useLabelingShortcuts.numberKeys.test.tsx |
| TC-FE-289 | 모달 검색창 입력 중 숫자키는 선택으로 동작하지 않음 (신규) | 검색 input 포커스 | `1` 입력 / 범위 밖 숫자 | 검색어에 입력될 뿐 선택 미발화(INPUT/TEXTAREA/contentEditable 가드). 목록 범위 밖 숫자는 무시 | component | Med | LabelPickerModal.tsx · LabelPickerModal.test.tsx |
| TC-FE-290 | 마스터 색상은 `#RRGGBB` 검증 후에만 inline style 주입 (신규) | 색상값이 미검증 문자열 | 모달 렌더 | `safeHexColor` 통과 값만 `backgroundColor` 로 넘어간다(원문 문자열 직접 주입 없음). 검색어도 텍스트 노드/필터 값으로만 사용 — `dangerouslySetInnerHTML` 없음 | security | High | LabelPickerModal.tsx · features/label/utils/labelColor.ts · LabelPickerModal.test.tsx |
| TC-FE-291 | 좌측 상시 라벨 패널 폐지 (신규) | 라벨링 진입 | 화면 렌더 | 구 `LabelSidebar` 영역 없음(컴포넌트·테스트 파일 삭제). 라벨 선택 표면은 모달 하나 | component | High | pages/label/LabelingPage.tsx · LabelingPageLabelPicker.test.tsx |
| TC-FE-292 | KeypointGuide 는 우측 패널 최상단(탭 바깥) (신규) | 스켈레톤 배치 중 | 우측 탭 전환(객체↔메타↔이슈) | `keypoint-guide-slot` 이 탭 바 위 상시 영역이라 **어느 탭에서도 배치 가이드가 계속 보인다**(구 좌측 패널에서 이전) | component | Med | pages/label/LabelingPage.tsx · LabelingPageLabelPicker.test.tsx |

### 라벨명 표시 = 라벨 마스터 등록명 그대로 — 2026-08-03 신설

> **결정 4 (2026-08-03 사용자 재확정, 커밋 `e58aa086`)**: 라벨명은 **라벨 마스터(`LS_LABEL`)에 등록된 이름을 그대로** 표시한다.
>
> - ⚠ **이것은 결정 3의 일부를 뒤집은 것이다.** 직전 커밋 `d8a7a2cc` 는 `resolveLabelDisplayName` 이 **COCO 한글 사전(`COCO_LABEL_KO` 14건) + 레거시 `LABEL_CLASS_DEFS`** 로
>   라벨명을 한글로 치환하게 만들었으나, 다음 커밋에서 **치환 로직을 전부 걷어냈다**. 코드 사전은 마스터와 어긋나는 **두 번째 진실원**이 되고, 사전에 있는 라벨만 한글이라 화면이 오히려 뒤섞이기 때문.
>   한글로 보이길 원하면 **라벨 관리(SC-036)에서 마스터 이름을 한글로 등록**한다.
> - **구 정책 → 폐기**: "`person` 이 화면에 `사람` 으로 보인다" 류 기대결과는 무효다. `car`·`VEHICLE` 로 등록된 라벨이 **화면에도 그대로 보이는 것이 의도된 동작**이며, 결함으로 되돌려 사전 치환을 되살리지 말 것.
> - `COCO_LABEL_KO` 는 삭제하지 않았지만 **모듈 private 로 좁혀졌다** — 라벨 관리 화면의 COCO 매핑 select 옵션 표시 전용이다.
> - 함수 자체는 얇게 남겼다(지우면 표시 지점들이 각자 폴백·trim·필드 선택을 다시 정해 드리프트가 되살아남).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-293 | 마스터 등록명 그대로 표시(사전 치환 없음) (신규) | 마스터 이름이 `car`/`person`/`bus`/`traffic light` | 표시 | 화면에도 **동일 문자열**. 한글 사전·`LABEL_CLASS_DEFS`(`PERSON`→'사람' 등) 치환 **미적용**. 마스터에 한글로 등록된 이름은 그대로 한글. ⚠ 2026-08-06(회차 14) 부로 `LABEL_CLASS_DEFS` 를 보유하던 `features/label/labelColors.ts` 가 **파일째 삭제**되어 그 사전은 코드에 더 이상 존재하지 않는다(같은 이유 — 마스터가 단일 진실원 — 로 **색상** 축까지 폐지, TC-FE-332). 기대결과·표시 동작은 **무변경** | component | High | features/label/utils/labelDisplayName.ts(resolveLabelDisplayName) · features/label/utils/\_\_tests\_\_/labelDisplayName.test.ts |
| TC-FE-294 | 표시 지점 6곳이 같은 값을 보여준다 (신규) | 같은 라벨 | 각 화면 | 라벨 선택 모달·우측 '객체' 목록·객체 속성(드롭다운/읽기 필드)·AI 탐지 후보·라벨 변경 이력·포털 업로드 라벨링이 모두 `resolveLabelDisplayName` 경유 — 화면마다 다른 이름이 나오지 않는다. ⚠ 7번째 호출부 `components/LabelPanel.tsx:44` 가 있으나 **어디서도 import 되지 않는 사(死)코드**(구 `LabelSidebar` 잔재)라 화면 표시 지점 집계에서 제외한다 | component | High | LabelPickerModal.tsx · ObjectClassTree.tsx · ObjectAttributePanel.tsx · AiToolModal.tsx · LabelChangeDetail.tsx · pages/portal/PortalUploadLabelingPage.tsx |
| TC-FE-295 | 표시명이 저장·전송 payload 에 섞이지 않는다 (신규) | 라벨 저장 / SAM2 추적 요청 / 이벤트 어노테이션 | 요청 body | `obj_label`·Sam2Track `className` 등은 **원문 그대로**. 표시 전용 경계 유지(원래부터 치환 대상이 아니었고 이번에도 불변) | security | High | features/label/\_\_tests\_\_/labelDisplayNameNoPayloadLeak.test.tsx |
| TC-FE-296 | 빈 값만 `-` 로 표시 — 그 외는 임의 대체 없음 (신규) | null/undefined/공백 문자열 / `__proto__`·`constructor` 같은 이름 | resolve | 빈 값 → `-`, 앞뒤 공백은 trim. 프로토타입 속성명이어도 **원문 문자열만 반환**(사전 조회가 없어 프로토타입 오염 경로 자체가 소멸) | component | Med | labelDisplayName.ts · labelDisplayName.test.ts |

## H-4. useLabelStore (Zustand)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-088 | setLabels 시 dirty/undo/redo/선택 초기화 | 세팅 | setLabels | 전부 초기화 | component | High | stores/useLabelStore.ts |
| TC-FE-089 | addLabel dirty 마킹 | 추가 | addLabel | dirtyLabels 추가 | component | High | stores/useLabelStore.ts |
| TC-FE-090 | 좌표 이동 shape별 | dx/dy | offsetShape | 타입별 shift(BBOX/POLYGON/KEYPOINT) | component | Med | stores/useLabelStore.ts |
| TC-FE-091 | clampShape 경계 [0,w]/[0,h] | w/h | clampShape | clamp, 미지정 시 하한 0만 적용 | component | Med | stores/useLabelStore.ts |
| TC-FE-092 | mergeAutoLabels 중복 스킵+개수 | 기존 존재 | mergeAutoLabels | 신규만 병합, added 반환 | component | High | features/label/__tests__/useLabelStore.mergeAuto.test.ts |
| TC-FE-093 | revertSaveEvent 역적용 | 변경 이력 | revertSaveEvent | 역적용 개수 반환 | component | Med | features/label/__tests__/useLabelStore.revert.test.ts |
| TC-FE-094 | pendingTracks stash/drain | 미래 프레임 | stash→drain | 진입 시 drain 병합 | component | High | features/label/__tests__/useLabelStore.pendingTracks.test.ts |
| TC-FE-095 | 라벨 표시/숨김 토글(세션) | selected | toggleLabelVisibility | hiddenLabelIds(dirty 무영향) | component | Med | features/label/__tests__/useLabelStore.visibility.test.ts |
| TC-FE-096 | 라벨 잠금 편집 no-op | lockedLabelIds | update | dirty/undo 미변화 | component | Med | features/label/__tests__/useLabelStore.lock.test.ts |
| TC-FE-097 | 클립보드 copy/paste(sourceRawSn) | 복사 | copy/pasteLabels | 개수 반환 | component | Med | features/label/__tests__/useLabelStore.clipboard.test.ts |
| TC-FE-098 | 키포인트 17종 배치/편집 | KEYPOINT | 배치 | keypoints 삼중값 | component | Med | features/label/__tests__/useLabelStore.keypoint.test.ts |
| TC-FE-099 | imageAdjust 밝기/대비/투명도(세션) | 조절 | setImageAdjust | 영속 안 함 | component | Low | features/label/__tests__/useLabelStore.imageAdjust.test.ts |
| TC-FE-100 | activeLabel 파생 프리셋 기본 | 활성 라벨 | activeLabelId | resolveDefaultLabel | component | Low | features/label/__tests__/useLabelStore.activeLabel.test.ts |
| TC-FE-101 | clampPan 스케일 기반 뷰 제한 | zoom/pan 드래그 | CanvasShell 드래그→setPan | canvasGeometry clampPan 위임(클램프는 CanvasShell 이 store 유틸 `clampPan` 으로 계산해 `setPan` 에 결과를 넘긴다 — `setPan` 자체는 raw setter) | component | Med | stores/useLabelStore.ts · features/label/canvas/CanvasShell.tsx |

## H-5. 좌표 변환/캔버스 유틸 (canvas scale 함정)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-102 | translateToCanvas 이미지→캔버스 | geom.scale/left/top | 변환 | x*scale+left | component | High | features/label/canvas/utils/coordinateTransformer.ts |
| TC-FE-103 | translateFromCanvas 왕복 정합 | 동일 geom | to→from | 원 좌표 복원(오차 내) | component | High | features/label/canvas/utils/coordinateTransformer.ts(3차 정정 — 구 ``은 회전역변환·return 문 누락) |
| TC-FE-104 | clampToImage 경계 clamp | 캔버스 밖 | clampToImage | 경계로 clamp | component | Med | features/label/canvas/utils/coordinateTransformer.ts |
| TC-FE-105 | computeWrappingBox 외접박스 | points | compute | min/max 박스 | component | Med | features/label/canvas/utils/coordinateTransformer.ts |
| TC-FE-106 | rotate2DPoints 회전 유틸 | 각도 | rotate | 회전 좌표 | component | Low | features/label/canvas/utils/coordinateTransformer.ts |
| TC-FE-107 | maskRleConverter MASK↔RLE 변환(3차 정정 — 구 "MASK↔RLE↔Polygon"은 실제 미구현 기능을 표제에 포함한 카탈로그 오류. 실제로는 MASK↔RLE·imageData↔RLE 왕복만 제공, Polygon 변환 함수 없음 — 테스트 파일 자체 설명도 "MASK ↔ RLE 변환"이며 Polygon 언급 0건) | 마스크 | 변환 | 왕복 정합 | component | Med | features/label/canvas/utils/maskRleConverter.ts |
| TC-FE-108 | trackInterpolation 트랙 보간 | 두 키프레임 | interpolate | 중간 보간 | component | Med | features/label/canvas/utils/trackInterpolation.ts |
| TC-FE-109 | 캔버스 좌표 실측 naturalW/H 기준(하드코딩 제거) | 이미지 로드 | geometry | 실측 dims, 스트레치 없음 | component | High | pages/label/LabelingPage.tsx |

## H-6. MarkingPage (마킹 화면)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-110 | 잘못된 rawSn 에러 | NaN | 진입 | "잘못된 영상 ID입니다." | component | Med | pages/MarkingPage.tsx |
| TC-FE-111 | 비식별 미완료 진입 차단(백스톱) | isMarkingBlocked | 진입 | "비식별 완료 후 마킹이 가능합니다." role=alert | security | High | pages/MarkingPage.tsx |
| TC-FE-112 | 스트림 항상 비식별본('N'/'F' 차단) | deIdntfYn≠Y | 판정 | 마킹 차단 | security | High | features/video/types.ts:isMarkingBlocked |
| TC-FE-113 | Space → 현재시각 마킹 | MANUAL | Space | addMark | component | High | pages/MarkingPage.tsx |
| TC-FE-114 | Del/Backspace → 선택 마크 삭제 (★정정 2026-08-08 — 유일 수단 아님) | 선택 마크 | Delete / Backspace | `removeSelectedMark`. ⚠ **구 서술의 암묵 전제("삭제는 이 단축키뿐")는 폐기** — 칩마다 개별 삭제 버튼이 생겼다([H-31](#h-31-마킹-화면--마크-칩-개별-삭제-버튼-ui-045-markingpanel--2026-08-08-신설)). 단축키는 **대체가 아니라 유지**이며 이 행은 그 회귀 가드로 계속 유효하다 | component | Med | pages/MarkingPage.tsx(`MarkingPage` 키보드 핸들러) · features/marking/store.ts(`removeSelectedMark`) · pages/__tests__/MarkingPage.test.tsx(`삭제버튼_추가후에도_Del단축키_삭제가_동작한다`) |
| TC-FE-115 | Enter → 제출 | MANUAL | Enter | handleSubmit | component | High | pages/MarkingPage.tsx |
| TC-FE-116 | INPUT/TEXTAREA/**SELECT** 포커스 시 단축키 억제 | 포커스 | 키 | 무시(SELECT 포함 — 구 2종에서 확장) | component | Med | pages/MarkingPage.tsx |
| TC-FE-117 | AUTO intervalFrames<1 제출 차단 | 무효 | handleSubmit | mutate 미발생 | component | Med | pages/MarkingPage.tsx |
| TC-FE-118 | MANUAL 마크 0건 제출 차단 (★정정 2026-08-09 — 막는 **수단**이 바뀜) | localMarks=[] | handleSubmit | `mutate` 미발생은 **그대로 유효**하다. ⚠ **구 서술의 암묵 전제("완료 버튼을 죽여서 막는다" · 조용한 early return)는 폐기** — 버튼은 **항상 활성**이고 누르면 안내 토스트로 사유를 말하며 제출만 막는다([H-44](#h-44-마킹-화면--완료-버튼-항상-활성--마크-0건-안내--2026-08-09-신설)) | component | Med | pages/MarkingPage.tsx(`MarkingPage` — `handleSubmit` 의 `localMarks.length === 0` 분기) · pages/__tests__/MarkingPage.test.tsx(`수동모드_마크0건이면_완료버튼이_활성이고_눌러도_제출되지_않고_안내가_뜬다`) |
| TC-FE-119 | 제출 중복 방지(Enter 연타) | isPending | handleSubmit | 두번째 무시 | component | High | pages/MarkingPage.tsx |
| TC-FE-120 | 제출 성공 → clearMarks+/task | 성공(batchTriggered≠false) | onSuccess | "마킹이 제출되었습니다. 배치 처리가 시작됩니다."+navigate | component | High | pages/MarkingPage.tsx |
| TC-FE-121 | 이벤트 유형 없는 영상 실패 토스트 | BE 400 | onError | extractBeMessage 에러 | component | Med | pages/MarkingPage.tsx |
| TC-FE-122 | 스트림 401 만료 1회 재발급 | src 에러 | onSrcError | refetchStreamUrl 1회(무한루프 방지) | security | High | pages/MarkingPage.tsx |
| TC-FE-123 | 영상 변경 시 마크 reset+duration fallback | rawSn 변경 | effect | reset()+durationSec=60 | component | Med | pages/MarkingPage.tsx |
| TC-FE-124 | 배치단계 인디케이터 stages 있으면 노출 | videoDetail.stages | 렌더 | BatchStageIndicator | component | Med | pages/MarkingPage.tsx |
| TC-FE-125 | 마크 목록 칩 선택 하이라이트 (★정정 2026-08-08 — 칩 구조 변경) | localMarks | 칩의 **선택 버튼** 클릭 | `selectedMarkIndex` 갱신 + 칩 전체 배경이 강조로 전환. ⚠ **칩은 더 이상 단일 `<button>` 이 아니다** — 선택 버튼과 삭제 버튼 두 개를 나란히 담은 그룹이며, 칩 전체를 버튼으로 감싸면 삭제 버튼이 버튼 안의 버튼(중첩)이 된다([H-31](#h-31-마킹-화면--마크-칩-개별-삭제-버튼-ui-045-markingpanel--2026-08-08-신설)) | component | Low | pages/MarkingPage.tsx(`MarkingPage` 마킹 칩 렌더 블록) |
| TC-FE-203 | frameIndex 는 서버 fps 로 계산 (신규) | `VideoDetail.fps=25` | Space 마킹(55초 지점) | `round(55×25)=1375` 전송 (구 30fps 하드코딩 시 1650 → BE 상한 초과 400). fps 없으면 폴백 30(BE `VideoFpsResolver.DEFAULT_FPS` 와 동일) | component | High | features/marking/markingFps.ts · features/marking/__tests__/markingFps.test.ts · features/marking/components/VideoPlayer.tsx |
| TC-FE-204 | 마킹 저장됐으나 배치 미시작 안내 (신규) | 응답 `batchTriggered=false` | 제출 성공 | **error 토스트** "마킹은 저장되었으나 배치가 시작되지 않았습니다. {batchSkipReason}" + /task 이동 (성공 문구로 위장 금지) | component | High | pages/MarkingPage.tsx |
| TC-FE-205 | 배속 6단(0.25/0.5/1/1.5/2/4) 이산 선택 (신규) | 마킹 재생 | 배속 버튼 클릭 | `video.playbackRate` 반영 + 활성 버튼 강조. **자유 입력이 없어 클램프 불필요**(UNCERTAINTIES #23 확정), 기본 1x, 각 버튼 min-h-11(KRDS 터치 최소) | component | Med | features/marking/components/VideoPlayer.tsx |
| TC-FE-311 | ★마킹 화면 비식별 누락 신고 버튼 신설 (신규 · V171) | `MARKING_READY` 비파생 영상 | 신고 버튼 → 사유 입력 → 제출 | **영상 단위** `POST /v1/videos/{rawSn}/deident-report` 호출(`/labels/{srcSn}/...` 호출 0건). 라벨링 화면과 **같은 컴포넌트를 재사용**한다 — 복제하면 한쪽만 갱신되는 사고가 난다 | component | High | pages/MarkingPage.tsx · features/label/components/DeidentReportButton.tsx · features/label/api.ts(`reportDeidentMissByVideo`) · pages/__tests__/MarkingPage.test.tsx ⚠ **ID 충돌 정정(2026-08-05 머지)** — 구 `TC-FE-304`. main 이 같은 번호(우측 속성 패널 레이아웃, 2026-08-04)를 먼저 점유했다. |
| TC-FE-312 | 파생영상이면 신고 버튼 비활성 + 사유 툴팁 (신규) | `VideoDetail.derivative=true` | 버튼 클릭 | 버튼 `disabled` + `title` 에 사유, **모달이 열리지 않는다** — 사유를 다 적고 제출한 뒤에야 412 로 거부되는 동선을 없앤다(라벨링 화면과 동일 관례) | component | High | pages/MarkingPage.tsx(`deidentReportUnsupportedReason`) ⚠ **ID 충돌 정정(2026-08-05 머지)** — 구 `TC-FE-305`. main 이 같은 번호(우측 속성 패널 레이아웃, 2026-08-04)를 먼저 점유했다. |
| TC-FE-313 | 마킹 단계가 아니면 신고 버튼 비활성 (신규) | `VideoDetail.status !== 'MARKING_READY'` | 렌더 | 버튼 `disabled` + 툴팁 "라벨링 화면에서 신고" 안내. BE 는 412 로 거부한다(B [TC-DEID-094](B-batch-deidentify.md)) | component | High | pages/MarkingPage.tsx(`deidentReportUnsupportedReason`) ⚠ **ID 충돌 정정(2026-08-05 머지)** — 구 `TC-FE-306`. main 이 같은 번호(우측 속성 패널 레이아웃, 2026-08-04)를 먼저 점유했다. |
| TC-FE-314 | 신고 모달 안내 문구가 재개 지점을 알려준다 (신규) | 마킹/라벨링 각 화면 | 모달 열기 | 마킹 → "재처리가 끝나면 **마킹부터 다시**" · 라벨링 → "기존 마킹과 라벨을 유지한 채 **이어서 작업**". 기술 모델명·내부 테이블명 미노출 | a11y | Med | features/label/components/DeidentReportButton.tsx ⚠ **ID 충돌 정정(2026-08-05 머지)** — 구 `TC-FE-307`. main 이 같은 번호(우측 속성 패널 레이아웃, 2026-08-04)를 먼저 점유했다. |
| TC-E2E-003 | MarkingPage 통합(스트림/배속/단축키) | reviewer/worker | 마킹 플로우 | 정상 | e2e | Med | pages/__tests__/MarkingPage.test.tsx |

## H-7. BatchStageIndicator (STAGE_ORDER 드리프트 함정)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-126 | stages 비면 null 렌더(하위호환) | stages=[] | 렌더 | null(상위 배지 폴백) | component | Med | components/common/BatchStageIndicator.tsx |
| TC-FE-127 | 7종 단계명 매핑(DEIDENTIFY~INTERPOLATE) | canonical | 렌더 | 비식별/마킹/VLM/프레임추출/AI 탐지/AI 분할/보간 | component | High | components/common/BatchStageIndicator.tsx |
| TC-FE-128 | 미지 코드 폴백 "처리중"(기술코드 미노출) | 매핑 없음 | 렌더 | "처리중"(YOLO/SAM2 미노출) | security | High | components/common/BatchStageIndicator.tsx |
| TC-FE-129 | BE 배열 순서 그대로(FE 순서 가정 없음) | stages 순서 | 렌더 | 배열 순, name→라벨 매핑만 | component | High | components/common/BatchStageIndicator.tsx |
| TC-FE-130 | 상태별 아이콘(DONE/PROGRESS/FAIL/PENDING) | status | 렌더 | 아이콘 aria-hidden | a11y | Med | components/common/BatchStageIndicator.tsx |

## H-8. ReviewPage (검수 화면)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-131 | 잘못된 검수 ID 에러 | id NaN | 진입 | "잘못된 검수 ID" | component | Med | pages/ReviewPage.tsx |
| TC-FE-132 | 로딩 상태 | isLoading | 진입 | "검수 로딩" | component | Low | pages/ReviewPage.tsx |
| TC-FE-133 | 에러/review 없음 | error/!review | 진입 | "검수 정보를 불러올 수 없습니다" | component | Med | pages/ReviewPage.tsx |
| TC-FE-134 | 진입 시 자동 startReview | REVIEW_PENDING | mount effect | doStart(→REVIEWING) | component | High | pages/ReviewPage.tsx |
| TC-FE-135 | 캔버스 읽기 전용(좌표 마커 미사용) | 렌더 | 캔버스 | "읽기 전용" 배지 | component | High | pages/ReviewPage.tsx |
| TC-FE-136 | 프레임 로딩 중 스피너 | framesLoading | 렌더 | loading prop 스피너 | component | Med | pages/ReviewPage.tsx |
| TC-FE-137 | 승인 확인 다이얼로그 | 승인 클릭 | handleApproveClick | ConfirmDialog | component | High | pages/ReviewPage.tsx |
| TC-FE-138 | 승인 성공 → /review 이동+토스트 | 성공 | onSuccess | noLabelConfirm 닫힘+"승인 완료"+navigate | component | High | pages/ReviewPage.tsx |
| TC-FE-139 | 승인 실패 토스트(BE 문구) | REVIEW_NO_LABEL 아닌 실패 | onError | `extractBeMessage(err,'승인 실패')` 토스트 (구 고정문구 '승인 실패' 아님) | component | Med | pages/ReviewPage.tsx |
| TC-FE-140 | 반려 사유 합성(의견+이슈) | reviewComment+pendingIssues | composeRejectReason | 입력+[전체의견]+[이슈N건] | component | High | pages/ReviewPage.tsx |
| TC-FE-141 | 반려 성공 → /review 이동 | onSuccess | 반려 | navigate('/review') | component | Med | pages/ReviewPage.tsx |
| TC-FE-142 | 프레임 상태색(미해소 문의=빨강) | INQUIRY 미해소 | 렌더 | inquirySrcSns → 빨강 | component | Med | pages/ReviewPage.tsx |
| TC-FE-143 | 저장 프레임=연두(labels>0) | frames labels | 렌더 | savedSrcSns → 연두 | component | Low | pages/ReviewPage.tsx |
| TC-FE-144 | currentFrameIdx 범위 밖 → 0 reset | idx 초과 | effect | setCurrentFrameIdx(0) | component | Med | pages/ReviewPage.tsx |
| TC-FE-145 | 언마운트 store reset | 이탈 | unmount | clearSelection+idx=0 | component | Med | pages/ReviewPage.tsx |
| TC-FE-146 | 증강여부/종류 표시 | 증강 파생 | 렌더 | ORGNL_RAW_SN/VMS_CLIP_ID 파생 | component | Med | features/review/components/ReviewMetaPanel.tsx · features/review/components/ReviewHeader.tsx |
| TC-FE-147 | 이슈 스레드 검수자 모드 | reviewer | 렌더 | IssueThreadPanel mode=reviewer | component | Low | pages/ReviewPage.tsx |
| TC-FE-206 | 라벨 0건 승인 — 명시 확인 다이얼로그 (신규) | BE 409 `errorCode=REVIEW_NO_LABEL` | 승인 클릭 | "라벨이 없는 영상입니다" ConfirmDialog → 확인 시에만 `noLabelConfirmed:true` 로 재요청(평시 미전송) | component | High | pages/ReviewPage.tsx |
| TC-FE-207 | 409 라도 사유가 다르면 다이얼로그 미노출 (신규) | 동시 승인 충돌 409(코드≠REVIEW_NO_LABEL) | 승인 클릭 | 확인 다이얼로그 **미노출**, BE 문구 토스트만. **상태코드가 아니라 `errorCode` 로 분기**(문자열 매칭 금지) | security | High | pages/ReviewPage.tsx |
| TC-FE-208 | 검수 메타 cot 객체형 렌더 크래시 없음 (신규) | `cot` 가 `{"1단계":…}` 객체 | 메타 패널 렌더 | `normalizeCot` 로 배열화 후 필터 — 구 `(cand.cot ?? []).filter` TypeError 크래시 재발 없음 | component | High | features/review/components/ReviewMetaPanel.tsx:CaptionReadonly · features/label/api/eventAnnotation.ts:normalizeCot |
| TC-E2E-004 | 검수 플로우: 대기목록→시작→승인 | REVIEWER | serial | 진입→승인 완료 | e2e | High | e2e/specs/review-flow.spec.ts |

## H-8a. 검수 화면 — 헤더 액션 일원화 + 상단 프레임 이동 바 + 재검토 필요 재승인 (SCREEN-019) — 2026-08-08 신설

> **5b-2 헤더 액션 일원화** — 승인·반려는 `ReviewHeader` 가 단독으로 담당한다. 구 하단 `ReviewActionBar` 컴포넌트는 **삭제**됐다(카탈로그에 그 컴포넌트를 지목한 기존 행은 없었다 — TC-FE-137~141 등은 `pages/ReviewPage.tsx` 의 승인/반려 핸들러 자체를 검증하는 행이라 트리거 위치가 바뀌어도 여전히 유효하며 재작성 대상이 아니다). 같은 액션이 두 곳에 있으면 상태별 활성 조건·진행 표시 판정이 갈린다는 것이 통합 근거([H-25](#h-25-캔버스-상단-옵션바-신설--편집-액션프레임-이동-이관-screen-005--2026-08-07-신설)의 라벨링 캔버스 저장 중복 진입점과 같은 이유).
> **5b-3 상단 프레임 이동 바 신설** — 헤더 바로 아래 별도 `<nav data-testid="review-frame-nav-bar">` 에 `FrameNavigator`(라벨링 캔버스 옵션바와 **동일 컴포넌트 재사용**, 복제 0)를 배치한다. ⚠ **[H-25](#h-25-캔버스-상단-옵션바-신설--편집-액션프레임-이동-이관-screen-005--2026-08-07-신설) 절 말미의 "검수 화면 상단 바는 범위 밖 — `ReviewPage` 에 배선돼 있지 않다"는 이 회차로 정정한다(★정정)** — 이제 배선됐다.
> **7b 재검토 필요 재승인** — `ReviewHeader` 가 `needsRecheck` prop 을 받아, `status==='COMPLETED'` 이고 `needsRecheck===true` 일 때만 **승인 버튼을 다시 활성화**한다(반려는 이 예외와 무관하게 완료 상태에서 그대로 비활성 — [D-1b](D-review-version-notify.md#d-1b-재승인d6-통지-트리거-반전--검수-승인-워크플로우-tc-review--2026-08-08-신설) 의 BE 재승인 규약과 짝).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-378 | 헤더가 승인·반려를 단독 담당 — 하단 액션 바 없음 (신설) | 렌더 | `onApprove`/`onReject` 지정 | `review-header-actions` 컨테이너 안에 `review-action-approve`/`review-action-reject` 가 있고, 헤더 밖(하단)에는 동등한 승인·반려 트리거가 없다 | component | High | features/review/components/ReviewHeader.tsx · features/review/__tests__/ReviewHeader.test.tsx(`승인_반려_버튼이_헤더_안에_렌더된다`) |
| TC-FE-379 | 헤더에는 프레임 위치 표시가 없다 (신설) | 렌더 | — | `frame-counter`·"N / total" 류 텍스트가 헤더에 없다 — 프레임 이동 바가 위치 표시를 **단독** 담당(두 곳에 두면 어느 쪽이 진실인지 갈린다) | component | Med | features/review/components/ReviewHeader.tsx · features/review/__tests__/ReviewHeader.test.tsx(`헤더에_프레임_위치_표시가_없다`) |
| TC-FE-380 | 상단 프레임 이동 바는 헤더 바로 아래 별도 표면이고, 위치 표시가 그 안에 있다 (신설) | 렌더 | `review-frame-nav-bar` 조회 | 헤더 다음 순서로 렌더되고, 처음/이전/번호입력/다음/마지막 + 위치 슬라이더를 포함한다(라벨링 캔버스 옵션바와 동일 컴포넌트) | component | High | pages/ReviewPage.tsx(`FrameNavigator`) · features/review/__tests__/ReviewFrameNavBar.test.tsx(`상단바는_헤더_바로_아래에_있고_프레임_위치_표시가_그_안에_있다`) |
| TC-FE-381 | 처음·이전·다음·마지막·번호입력·슬라이더가 모두 같은 이동 경로로 수렴한다 (신설) | 각 컨트롤 조작 | `onRequestGoTo` | 진입점마다 다른 콜백을 쓰지 않는다 — 미저장 가드가 한 곳에서만 돌게 하기 위함([H-25](#h-25-캔버스-상단-옵션바-신설--편집-액션프레임-이동-이관-screen-005--2026-08-07-신설)의 라벨링 캔버스와 동일 원칙) | component | High | pages/ReviewPage.tsx(`handleGoToFrame`) · features/review/__tests__/ReviewFrameNavBar.test.tsx(`처음_이전_다음_마지막_번호입력_슬라이더가_모두_같은_이동_경로로_수렴한다`) |
| TC-FE-382 | 첫/마지막 프레임에서는 해당 방향 컨트롤이 비활성 (신설 · 경계) | `frameIndex=0` / `frameIndex=frameCount-1` | 렌더 | 각각 처음·이전 / 다음·마지막 버튼 비활성 | component | Med | features/review/__tests__/ReviewFrameNavBar.test.tsx(`첫_프레임에서는_처음_이전이_비활성이다` · `마지막_프레임에서는_다음_마지막이_비활성이다`) |
| TC-FE-383 | 범위 밖 번호 입력은 거부되고 현재 번호로 되돌린다 (신설 · 경계) | 번호 입력 필드에 범위 밖 값 | 확정 | `onRequestGoTo` 미호출 + 입력값이 현재 프레임 번호로 리셋 | component | Med | features/review/__tests__/ReviewFrameNavBar.test.tsx(`범위_밖_번호_입력은_거부되고_현재_번호로_되돌린다`) |
| TC-FE-384 | 프레임이 0건이면 상단바는 남고 모든 이동 컨트롤이 비활성 (신설 · 경계) | `frameCount=0` | 렌더 | `review-frame-nav-bar` 는 렌더되되(사라지지 않음) 컨트롤 전부 비활성 | component | Low | features/review/__tests__/ReviewFrameNavBar.test.tsx(`프레임이_0건이면_상단바는_남고_모든_이동_컨트롤이_비활성이다`) |
| TC-FE-385 | 상단바·번호입력에 접근 가능한 이름과 범위가 노출된다 (신설 · a11y) | 렌더 | 스크린리더 조회 | `nav[aria-label="프레임 이동 바"]` + 번호 입력의 접근 가능한 이름·min/max | a11y | High | pages/ReviewPage.tsx(`aria-label`) · features/review/__tests__/ReviewFrameNavBar.test.tsx(`상단바와_슬라이더_번호입력에_접근_가능한_이름과_범위가_노출된다`) |
| TC-FE-386 | 재검토 필요 배지가 상태 배지와 나란히 병기된다 (신설) | `needsRecheck=true` | 렌더 | `StatusBadge status="NEEDS_RECHECK"` 가 상태 배지 **대체가 아니라 병기**로 노출. `needsRecheck=false` 면 미노출 | component | High | features/review/components/ReviewHeader.tsx · features/review/__tests__/ReviewHeader.test.tsx(`needsRecheck_true면_재검토_필요_배지가_상태_배지와_나란히_노출된다` · `needsRecheck_false면_재검토_필요_배지가_렌더되지_않는다`) |
| TC-FE-387 | COMPLETED + needsRecheck=true 는 승인 버튼만 다시 활성화된다 — 반려는 그대로 비활성 (신설) | `status='COMPLETED'`, `needsRecheck=true` | 렌더 | 승인 버튼 활성 + 안내 문구가 "재검토가 필요합니다"로 전환. 반려 버튼은 **여전히 비활성**(이 예외와 무관 — 데이터마트 뷰가 라이브 APPROVED 만 게이트하므로 반려로 승인 상태를 내리면 안 된다) | component | **Critical** | features/review/components/ReviewHeader.tsx(`canReapprove`) · features/review/__tests__/ReviewHeader.test.tsx(`COMPLETED_이고_needsRecheck_true면_승인_버튼만_다시_활성화된다` · `재승인_가능_상태에서는_사유_안내_문구가_재검토_안내로_바뀐다`) |
| TC-FE-388 | needsRecheck=true 여도 REVIEWING 상태면 이 예외가 적용되지 않는다 — 검수중은 원래 활성 (신설 · 경계) | `status='REVIEWING'`, `needsRecheck=true` | 렌더 | 승인·반려 둘 다 원래 규칙대로 활성(재검토 예외는 `COMPLETED` 에만 적용) | component | Med | features/review/__tests__/ReviewHeader.test.tsx(`needsRecheck_true여도_REVIEWING_상태면_예외가_적용되지_않는다_검수중은_원래_활성`) |
| TC-FE-389 | 검수 목록의 재검토 필요 배지 — 상태 배지와 함께 병기 (신설) | 목록 응답에 `needsRecheck: true`/`false` 행 혼재 | 렌더 | `true` 행만 상태 배지 옆에 "재검토 필요" 배지가 함께 노출된다(대체가 아니라 병기) | component | High | pages/ReviewListPage.tsx · pages/__tests__/ReviewListPage.test.tsx(`needsRecheck_true인_행은_상태_배지_옆에_재검토_필요_배지가_함께_노출된다` · `needsRecheck_false인_행은_재검토_필요_배지가_노출되지_않는다`) |

## H-9. 관리 화면 (/manage/*)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-148 | 라벨 마스터 COCO 매핑 등록 | REVIEWER | dtctTypeCd 입력 | LabelMaster 매핑 반영 | component | High | pages/manage/LabelMasterManagePage.tsx |
| TC-FE-149 | 사용자/권한 관리 REVIEWER 전용 | REVIEWER | /manage/users | 목록/권한 관리 | security | High | router/__tests__/manageGuard.test.tsx(역할 분기 실검증. 구 근거 `UserManagePage.test.tsx` 는 REVIEWER 고정 검색필터 테스트라 접근제어 미검증 — H-ISSUE-82 반영 정정) |
| TC-FE-150 | 시스템 설정 CRUD(정밀도/YOLO conf·iou) | REVIEWER | /manage/settings | 설정 카드 | component | Med | features/sysconfig/components/{YoloConfigCard,PrecisionConfigCard}.tsx |
| TC-FE-151 | 프리셋 목록/편집(마스터 join) | REVIEWER | /manage/presets | 코드↔labelId | component | Med | pages/manage/PresetListPage.tsx |
| TC-FE-152 | 비식별 신고 관리 목록 | REVIEWER | /manage/deident-reports | OPEN/RESOLVED 목록 | component | Med | pages/manage/DeidentReportListPage.tsx |
| TC-FE-308 | ★신고 목록에 **신고 단계** 열 표시 (신규 · V171) | `stage='MARKING'` 행 + `stage='LABELING'` 행 | `/manage/deident-reports` 렌더 | 각 행에 **마킹** / **라벨링** 로 표시된다. **코드값 원문(`MARKING`/`LABELING`)·내부 컬럼명(`DCLR_STP_CD`)은 화면에 없다** — BE 는 코드를, FE 가 사용자 언어를 담당(상태 컬럼과 동일 관례). 툴팁이 해소 시 무엇이 일어나는지(마킹부터 다시 / 프레임만 재추출) 알린다 | component | High | pages/manage/DeidentReportListPage.tsx(`STAGE_DISPLAY`) · features/deident/__tests__/DeidentReportListPage.test.tsx(신고_단계가_사용자_언어로_표시된다) |
| TC-FE-309 | 단계 `null`(레거시) 은 **'미상'** 으로 표시하고 빈칸으로 두지 않는다 (신규) | `stage: null` | 렌더 | **'미상'** + 툴팁 "…해소해도 재마킹·프레임 재추출은 자동으로 진행되지 않습니다". 빈칸이면 **"값이 없다"와 "로딩 실패"가 구분되지 않는다.** '미상' 은 "단계가 없다"가 아니라 **"기록이 없다"** 는 뜻이다(그 신고도 어딘가에서 접수됐다) — 없는 단계를 지어내 표기하지 않는다 | component | High | pages/manage/DeidentReportListPage.tsx(`STAGE_UNKNOWN`) · features/deident/__tests__/DeidentReportListPage.test.tsx(단계가_null_인_레거시_신고는_미상으로_표시되고_빈칸이_아니다) |
| TC-FE-310 | `stage` 키 자체가 없는 **구 응답**도 '미상' 으로 표시 (신규 · 하위호환) | 필드 추가 이전 형태 응답 | 렌더 | 화면이 깨지지 않고 '미상'. 타입은 `stage?: … \| null` (optional) — BE 가 필드를 추가만 하므로 FE 도 부재를 견뎌야 배포 순서에 무관해진다 | component | Med | features/deident/reportTypes.ts(`DeidentReportRow.stage`) · features/deident/__tests__/DeidentReportListPage.test.tsx(stage_필드가_없는_구_응답도_미상으로_표시된다) |
| TC-FE-153 | 작업 배정/재배정 모달 | REVIEWER | AssignModal | WORKER 배정+이력 | component | High | features/task/components/AssignModal.tsx |
| TC-FE-154 | 재배정 이력 드로어 | REVIEWER | HistoryDrawer | 배정 이력 | component | Low | features/task/components/HistoryDrawer.tsx |
| TC-FE-155 | 대시보드 KPI 3카드+이벤트 분포 | 인증됨 | /dashboard | KpiCard 3종+차트 | component | Med | pages/DashboardPage.tsx |
| TC-FE-156 | 영상 현황 목록 검색/필터/URL 동기화 | 인증됨 | 검색어 | URL 쿼리 갱신. ★필터 값의 **실제 적용(서버 전송·결과 반영)** 은 2026-08-03 신설 → **[H-18](#h-18-영상-목록--영상-상세-sc-007--sc-009--2026-08-03-신설)** 참조(그 전에는 화면이 보내도 컨트롤러가 받지 않아 조용히 버려졌다) | component | Med | features/video/parseVideoListParams.ts |
| TC-E2E-005 | REVIEWER 워크플로우 전체 | REVIEWER | 각 진입 | 헤더/컨테이너 노출 | e2e | High | e2e/specs/reviewer-workflow.spec.ts |
| TC-E2E-006 | 영상 목록 검색어 URL 동기화 | WORKER | 검색 | URL `cctvNameKeyword=` 갱신 | e2e | Med | e2e/specs/video-list.spec.ts |

## H-10. 증강/해상도 파생 화면

> ★ 이번 회차: `GET /v1/augments/{jobId}/result` 가 **해상도 파생(`RESL_*`)에 한해** `results[]`·`framePairs[]` 를 채운다(좌=부모 비식별 / 우=파생 리스케일, 양쪽 비식별본). 이미지는 **API 경로 문자열**이라 인증 blob 로딩이 필수다. WINTER/NIGHT/RAIN 은 여전히 빈 `results`.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-157 | 증강 요청 화면(WINTER/NIGHT/RAIN+해상도) | REVIEWER | /augment | ProcessKindCard 4종(단일 선택) | component | Med | features/augment/types.ts · pages/AugmentRequestPage.tsx |
| TC-FE-158 | 해상도 파생 요청 mutation | rawSn | changeResolution | 파생영상+VIDEO_KEYS invalidate | component | High | features/video/hooks/useResolutionDerivative.ts |
| TC-FE-159 | 해상도 파생 증강 이력 배지 | resolutionTypes | JobCard | RESL 배지(info, 한글 해상도 라벨) | component | Med | features/augment/components/JobCard.tsx |
| TC-FE-160 | 해상도 파생 accept/reject 없음(비검수) | `result.reviewable=false` | AugmentResultPanel(항목 패널, `AugmentResultPage`→`AugmentVideoSection`→`AugmentResultPanel` 로 위임) | **DecisionCard 미렌더**(채택/거부 버튼 부재, `showDecision=!isResolution && (...)`). 잡 이력 카드(JobCard)에는 원래 결정 액션이 없다 — 판정 지점은 결과 항목 패널의 `reviewable` | component | High | features/augment/components/AugmentResultPanel.tsx |
| TC-FE-161 | 증강만 없고 파생만 → "파생" 태그 | types=[]+hasResolution | JobCard | 파생 구분자 | component | Med | features/augment/components/JobCard.tsx |
| TC-FE-162 | 증강 채택 → ACCEPTED | PENDING | DecisionCard accept | ACCEPTED | component | High | features/augment/components/DecisionCard.tsx |
| TC-FE-163 | 증강 반려 사유 모달 | reject | RejectReasonModal | 사유+거부 | component | Med | features/augment/components/RejectReasonModal.tsx |
| TC-FE-164 | 거부 사유 표시(XSS escape) | rejectReason | DecisionCard | React 자동 escape | security | Med | features/augment/components/DecisionCard.tsx |
| TC-FE-209 | 해상도 파생 비교 이미지 인증 blob 렌더 (신규) | `RESL_*` 결과 + framePairs | 결과 항목 패널 진입 | FrameGrid12·SideBySideCompare 가 `authImages` 로 **Bearer blob** 요청(raw `<img src>` 아님) → 401 로 전부 깨지던 경로 제거. `AugmentResultPage`→`AugmentVideoSection`→`AugmentResultPanel` 로 렌더 위임됨(구 `AugmentResultPage` 직접 렌더 아님) | security | High | features/augment/components/AugmentResultPanel.tsx · features/deident/components/FrameGrid12.tsx · features/deident/components/SideBySideCompare.tsx · features/augment/__tests__/AugmentResultPanel.*.test.tsx |
| TC-FE-210 | 12쌍 초과 시 프레임 페이저 노출 (신규) | `totalFramePairs>12` | 결과 항목 패널 | `augment-frame-pager` 노출, 다음 페이지로 나머지 쌍 접근(접근 불가 프레임 0). page/size 는 쿼리 파라미터로 전송(기본 `FRAME_PAGE_SIZE=12`) | component | High | features/augment/components/AugmentResultPanel.tsx · features/augment/api.ts:getAugmentResult |
| TC-FE-211 | 탭(항목) 전환 시 프레임 페이지 리셋 (신규) | 항목 A(15쌍) 2페이지 → 항목 B(10쌍) 전환 | 항목 탭 클릭(구 "종류별 탭"에서 **항목(id) 단위 탭**으로 변경 — 같은 종류 중복요청도 각각 별개 탭) | framePage=0 리셋 → 빈 그리드에 갇히지 않음. 마운트 시점에는 리셋 안 함(직전 activeId 와 비교). 안전망으로 `framePage>0` 이면 페이저 유지 | component | High | features/augment/components/AugmentVideoSection.tsx · features/augment/components/AugmentResultPanel.tsx · features/augment/__tests__/AugmentResultPage.tabPaging.test.tsx |
| TC-FE-212 | 해상도 타입 라벨 매핑(undefined 해소) (신규) | `type='RESL_720P'` | 탭·슬롯 라벨 | "해상도 720p" 등 사람이 읽는 문구. 미지 코드는 `'증강'`, `RESL_` 접두는 `resolutionDerivativeLabel` 폴백 — 기술코드 노출 0 | component | High | features/augment/augTypeLabel.ts |
| TC-FE-213 | 결과 본문 있으면 "외부연동 대기" 안내 미표시 (신규) | COMPLETED + results 존재 | 결과 화면 | `augment-result-completed-empty` 미노출, "증강 이미지 생성률" 카드 표시(구 "라벨 무결성" 명칭에서 변경 — 라벨을 검사한 값이 아님을 명확화). ⚠ **정정**: "비교 프레임 쌍"·생성률은 **현재 항목/프레임 페이지 스코프 값**이며 "페이징 전 전체 쌍 수"가 아니다(구 기대결과 반대로 정정 — `summary.pagePairs`/`loadedPairs` 는 페이지 스코프임을 코드 주석이 명시). ⚠ **2차 정정(2026-08-09)**: 이 행이 가리키는 작업 요약은 이제 **5칸**이며 구 6번째 칸 `증강 유형`(+ 범위 표기 `증강 유형 (이 페이지)`)은 **폐기**됐다 — 유형은 항목 단위 정보라 항목 탭·비교 이미지 라벨이 담당한다([H-43](#h-43-증강-결과-화면--작업-요약-5칸-확정구-6칸-폐지--2026-08-09-신설)) | component | Med | pages/AugmentResultPage.tsx(`AugmentResultPage` · `summarize`) |
| TC-E2E-007 | 증강 결과 PENDING 채택 → ACCEPTED | REVIEWER | 채택 | 상태 변경 | e2e | High | e2e/specs/augment-decision.spec.ts |

## H-11. 포털 화면 (자산 업로드+수동 라벨링)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-165 | 이미지 확장자 검증(jpg/jpeg/png) | 비허용 | validateImageFiles | 제외+정책 안내 | security | High | features/portal/uploads/validation.ts(`validateImageFiles` 확장자 분기) |
| TC-FE-166 | 이미지 20MB 초과 제외 | 대용량 | validate | "20MB 초과" | component | Med | features/portal/uploads/validation.ts(정정 — `MAX_IMAGE_BYTES` 는 , 구 `` 는 주석 줄) |
| TC-FE-167 | 이미지 50장 상한 초과 잘라냄 | 초과 | validate | slice(50)+안내 | component | Med | features/portal/uploads/validation.ts |
| TC-FE-168 | 이미지 업로드 성공 후 초기화 | 유효 | onUploadImages | selected/errors/input 초기화 | component | Med | pages/portal/PortalUploadPage.tsx |
| TC-FE-169 | 영상 TUS 재개 업로드(mp4/mov/avi) | 영상 | onVideoStart | tus.start(`/portal/uploads/tus`) | component | Med | pages/portal/PortalUploadPage.tsx · features/upload/hooks/useTusUpload.ts(정정 — `endpointBase` 옵션은 , 구 `` 은 JSDoc 줄) |
| TC-FE-170 | 삭제 확인+PROCESSING 버튼 비활성 | PROCESSING | 렌더 | disabled+title | component | Med | pages/portal/PortalUploadPage.tsx(정정 — `window.confirm` 은 , `disabled`/`title` 은 ) |
| TC-FE-171 | 삭제 409 처리중 안내(내부 미노출) | BE 409 | deleteErrorMessage | "처리 중 자산 삭제 불가" | security | Med | pages/portal/PortalUploadPage.tsx |
| TC-FE-172 | READY 자산만 라벨링 링크 | READY | 렌더 | /portal/uploads/:uldSn/label | component | Med | pages/portal/PortalUploadPage.tsx |
| TC-FE-173 | FAILED 자산 실패 사유 표시 | FAILED | 렌더 | failRsnCn | component | Low | pages/portal/PortalUploadPage.tsx |
| TC-FE-174 | 사용자 파일명 텍스트노드 렌더(XSS 방어) | 파일명 | 렌더 | 자동 escape | security | High | pages/portal/PortalUploadPage.tsx |
| TC-FE-175 | 포털 업로드 라벨링 BBOX/POLYGON만 | READY | 진입 | CanvasShell, 오토라벨 없음 | component | Med | pages/portal/PortalUploadLabelingPage.tsx |
| TC-FE-176 | 포털 홈 데이터마트 영상 선택 | PORTAL_USER | /portal | 영상 목록+선택 | component | Med | pages/portal/PortalHomePage.tsx |
| TC-FE-177 | 포털 라벨링 저장(원본 미수정, 본인 적재) | 저장 | useSavePortalLabels | LS_PORTAL_USER_LABEL | security | High | features/portal/hooks/useSavePortalLabels.ts |
| TC-FE-271 | 포털 업로드 — 저장 중 진행 오버레이 + 취소 (신규) | 저장 PUT in-flight 300ms 초과 | 렌더 / 취소 버튼 | "저장 중" 오버레이(경과 초 + 취소). 취소 시 즉시 편집 복귀 + 오버레이 소멸 + **dirty 유지**(저장됨으로 취급하지 않음). 내부 라벨링과 **대칭** | component | High | pages/portal/PortalUploadLabelingPage.tsx(BusyOverlay 배선) · pages/portal/__tests__/PortalUploadLabelingBusy.test.tsx |
| TC-FE-272 | 포털 업로드 — 취소 후 도착 응답 미반영 (신규) | 취소 후 PUT 응답 도착 | 응답 처리 | `clearDirty` 등 성공 후처리 미실행(클라이언트 폐기 — 서버 처리 중단 아님) | security | High | features/portal/uploads/hooks/useSaveUploadLabels.ts |
| TC-FE-273 | 포털 업로드 — 짧은 저장엔 오버레이 미표시 (신규) | 저장 <300ms | 렌더 | 오버레이 없음(지연 창) | component | Med | features/label/busyPolicy.ts · features/label/components/BusyOverlay.tsx |
| TC-FE-274 | 포털 업로드에는 AI busy 가 없다 (신규) | ADR-013 별도 경로(`LS_PORTAL_*`) | 화면 전체 | AI 탐지/분할/추적 진입점 자체가 없어 관측되는 busy 종류는 **SAVE 뿐**. 오버레이 문구도 "저장 중" | component | High | pages/portal/PortalUploadLabelingPage.tsx(UPLOAD_TOOLS) · pages/portal/__tests__/PortalUploadLabelingBusy.test.tsx |
| TC-FE-275 | "포털 라벨링" ≠ "포털 업로드 라벨링" 경계 (정정) | 두 화면 | 경로·데이터 | 전자=`/portal/label/:id`(데이터마트 영상), 후자=`/portal/uploads/:uldSn/label`(본인 업로드 자산) — **경로·데이터 출처는 다르지만 도구 구성은 둘 다 BBOX/POLYGON만**이다. ⚠ 구 기대결과("전자는 AI 분할·추적 제공")는 폐기: `PORTAL_HIDDEN_TOOLS`(SAM_SEGMENT/TRACK/KEYPOINT)가 `dcdbb827`(SAM2 제거) 이후 `/portal/label/:id` 에도 적용되어 AI 분할·추적·탐지 전부 미제공이다(ADR-013, `PortalLabelingPage.tsx` 주석 "포털 오토라벨링 미제공"). **차단·오버레이·취소는 양쪽 모두 적용** | component | High | features/label/types.ts · features/label/components/ToolBar.tsx(필터 블록 — 구 `DarkToolbar.tsx`, 2026-08-07 개명) · pages/portal/PortalLabelingPage.tsx · pages/portal/PortalUploadLabelingPage.tsx |
| TC-E2E-008 | 포털 홈 정상 진입 | PORTAL_USER | /portal | 홈 렌더 | e2e | High | e2e/specs/portal-channel-guard.spec.ts |
| TC-E2E-009 | 포털→내부 대시보드 차단 | PORTAL_USER | /dashboard | forbidden | security | High | e2e/specs/portal-channel-guard.spec.ts |
| TC-E2E-010 | 포털→내부 관리 화면 차단 | PORTAL_USER | /manage/* | 차단 | security | High | e2e/specs/portal-channel-guard.spec.ts |
| TC-E2E-011 | 포털 업로드 dropzone 노출+업로드 | PORTAL_USER | /portal/uploads | dropzone+mock 업로드 | e2e | Med | e2e/specs/portal-upload.spec.ts |

## H-12. 공통 컴포넌트/에러/상태

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-178 | ErrorBoundary 스택 콘솔만/사용자 미노출 | 자식 throw | catch | "오류가 발생했습니다" role=alert | security | High | components/common/ErrorBoundary.tsx |
| TC-FE-179 | ErrorBoundary custom fallback | fallback prop | throw | fallback 렌더 | component | Low | components/common/ErrorBoundary.tsx |
| TC-FE-180 | AppErrorPage 404/500 | status | 렌더 | 상태별 페이지 | component | Med | components/common/AppErrorPage.tsx |
| TC-FE-181 | EmptyState 빈 목록 | data=[] | 렌더 | 빈 상태 안내 | component | Low | components/common/EmptyState.tsx |
| TC-FE-182 | AuthImage — Bearer blob 로딩(401 회피) | `srcSn` 지정 | 렌더 | `/frames/{srcSn}/image` blob 요청 → objectURL `<img>`. 로딩/에러는 `<div>` 폴백이며 **data-\*/aria-\* 는 유지**(슬롯 식별 끊김 방지) | security | High | components/common/AuthImage.tsx · components/common/__tests__/AuthImage.test.tsx |
| TC-FE-183 | Pagination 페이지 이동 (★정정 2026-08-08 — prop 계약·구성 전환) | `page`(0-based) + **`totalPages`** | 번호 클릭 | `onChange(다음 페이지)` 호출. ⚠ **구 계약 `size`·`totalElements`·`onPageChange` 는 폐기** — 전체 페이지 수를 **서버 응답값 그대로** 받고 건수로 되계산하지 않으며, 컨트롤 안에 있던 **"n-m / 총 N건" 요약도 제거**됐다. 상세·경계는 [H-37](#h-37-페이지네이션-계약-전환--목록-3화면-공용-수렴-ui-008--2026-08-08-신설) | component | Low | components/common/Pagination.tsx(`Pagination` · `pageCountOf`) · components/common/__tests__/Pagination.test.tsx(`Pagination_페이지_번호를_누르면_그_페이지로_이동을_요청한다`) |
| TC-FE-184 | DataTable 정렬/렌더 (★정정 2026-08-08 — 조합형 전환) | `columns`(`ColumnDef[]`) + `data` | 렌더 | `@tanstack/react-table` 기반 조합형으로 전환됐다 — 컬럼별 `sortable` 지정 시에만 헤더가 버튼이 되어 클릭마다 정렬 방향이 토글되고 `aria-sort` 가 함께 갱신된다(`sortable=false` 기본값이면 헤더는 버튼이 아니다). 페이지네이션·로딩 스켈레톤·행 선택은 **내장이 아니라 호출부가 조합**한다(구 all-in-one prop API 폐기) — 상세는 [H-26](#h-26-공통-입력조합형-컴포넌트-전환--field·card·datatable·select·checkbox·textarea--2026-08-08-신설) | component | Low | components/common/DataTable.tsx · components/common/__tests__/DataTable.test.tsx(`DataTable_sortable_로컬_정렬_클릭시_방향_토글_및_aria_sort` · `DataTable_sortable_false(기본값)면_헤더가_버튼이_아니다`) |
| TC-FE-185 | Toast variant별 표시 | pushToast | 발화 | success/error/warning/info | component | Med | components/common/Toast.tsx |
| TC-FE-186 | 게시판(공지) 목록/상세 | 인증됨 | /notice | 목록/상세 | component | Low | pages/NoticeListPage.tsx · pages/NoticeDetailPage.tsx |
| TC-FE-187 | RoleClaimPage 권한 자가부여 | role=null | /role-claim | 권한 요청 폼 | component | Med | pages/RoleClaimPage.tsx |
| TC-FE-188 | 통계 화면 차트 (정정) | REVIEWER/WORKER | /stat | `WorkerStatPage`=recharts 기반 `DailyCompletionChart`(Bar만), `OverallStatPage`=**recharts 미사용** 커스텀 SVG `SimpleBarChart`/`SimplePieChart`. ⚠ 구 기대결과("recharts 별도 청크"로 두 화면 통칭)는 부정확 — recharts 는 WorkerStatPage 경로에만 남아 있고 OverallStatPage 는 자체 SVG 컴포넌트로 대체됨(`f902e3d1`, 07-25 이전부터) | component | Low | pages/WorkerStatPage.tsx · features/stat/components/DailyCompletionChart.tsx · pages/OverallStatPage.tsx · components/charts/SimpleBarChart.tsx · components/charts/SimplePieChart.tsx |
| TC-FE-214 | AuthImage `path` 화이트리스트 fail-closed (신규) | `path='https://evil/x'` · `'/v1/frames/../../x'` · 쿼리스트링 포함 | 렌더 | **요청을 보내지 않고** "이미지 없음" 폴백. 허용은 `^/v1/frames/[0-9]{1,19}/(deid-)?image$` 뿐 (CWE-918/22) | security | High | lib/api/imagePath.ts · components/common/__tests__/AuthImage.test.tsx |
| TC-FE-215 | authImageStore refcount 중복 페치 제거 (신규) | 같은 경로를 그리드 슬롯+좌우비교가 동시 사용 | 마운트 | XHR **1회**, objectURL 공유. 동시 실행 상한·대기열 없음(head-of-line blocking 제거 — 페이저 넘겨도 새 페이지가 즉시 발사) | component | High | lib/api/authImageStore.ts · components/common/__tests__/AuthImage.test.tsx |
| TC-FE-216 | 마지막 소비자 unmount 시 즉시 revoke (신규) | 공유 중 소비자 순차 unmount | refCount 0 | `URL.revokeObjectURL` 호출 + 엔트리 삭제. **영속 캐시 없음** → 재진입 시 BE 재호출(비식별 신고 412 게이트가 그대로 적용, CWE-359) | security | High | lib/api/authImageStore.ts · components/common/__tests__/AuthImage.test.tsx |
| TC-FE-217 | 실패 경로는 새 소비자 마운트 시 1회 재요청 (신규) | 첫 요청 실패 후 재마운트 | acquire | `failed` 엔트리를 재사용해 **acquire 시점에만** 재요청(자동 재시도 루프 없음, 세대 교차로 인한 조기 revoke 없음) | component | Med | lib/api/authImageStore.ts · components/common/__tests__/AuthImage.test.tsx |
| TC-FE-218 | 폴백 `<div>` 에 img 전용 속성 미전개 (신규) | width/height/loading 등 전달 | 로딩·에러 폴백 | React 무효 DOM 속성 경고 없음(IMG_ONLY_PROPS 제거 후 전개) | component | Low | components/common/AuthImage.tsx · components/common/__tests__/AuthImage.test.tsx |

## H-13. 접근성 (a11y)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-A11Y-001 | 우측 패널 탭 role=tab/tabpanel | 라벨링 | 렌더 | role=tablist/tab, aria-selected/controls | a11y | High | pages/label/LabelingPage.tsx |
| TC-A11Y-002 | AiToolModal 라디오/체크박스 label 연결 + AI 분할 정밀도 "즉시 그리기" 체크박스 label 연결 | 팝업 / AI 분할 도구 활성 | 렌더 | htmlFor↔id | a11y | High | features/label/components/AiToolModal.tsx · features/label/components/ObjectAttributePanel.tsx |
| TC-A11Y-003 | Modal 포커스 트랩+ESC+포커스 복귀 | 모달 열림 | ESC | 닫힘+포커스 복귀 | a11y | High | components/common/Modal.tsx |
| TC-A11Y-004 | 마킹 키보드 전 조작(Space/Del/Enter) | MANUAL | 키보드 | 마우스 없이 완결 | a11y | High | pages/MarkingPage.tsx |
| TC-A11Y-005 | 라벨링 단축키(W/S/F/Q/T/?/Ctrl+C/V) | 라벨링 | 키 | 이동/폴리곤/표시토글/치트시트/복붙 | a11y | High | pages/label/LabelingPage.tsx |
| TC-A11Y-006 | 잠금 배너 aria-live=polite | isLocked | 렌더 | role=status aria-live | a11y | Med | pages/label/LabelingPage.tsx |
| TC-A11Y-007 | 진행률 progressbar aria-valuenow | 업로드 | 렌더 | role=progressbar+aria-value* | a11y | Med | pages/portal/PortalUploadPage.tsx |
| TC-A11Y-008 | 이슈 배지 aria-label 카운트 | 미해소>0 | 렌더 | aria-label="미해소 문의 N건" | a11y | Med | pages/label/LabelingPage.tsx |
| TC-A11Y-009 | 삭제 버튼 aria-label(파일명) | 자산 목록 | 렌더 | aria-label="{파일명} 삭제" | a11y | Low | pages/portal/PortalUploadPage.tsx |
| TC-A11Y-010 | 아이콘 aria-hidden(중복 낭독 방지) | 아이콘 | 렌더 | aria-hidden | a11y | Low | components/common/BatchStageIndicator.tsx |
| TC-A11Y-011 | KRDS 포커스링 키보드 초점 | Tab | 포커스 | KRDS_FOCUS 가시 초점 | a11y | Med | lib/focusRing.ts |
| TC-A11Y-012 | 검수 캔버스 aria-label 읽기전용 | 검수 | 렌더 | aria-label="검수 캔버스 (읽기 전용)" | a11y | Low | pages/ReviewPage.tsx |
| TC-A11Y-013 | KPI 필터 카드 aria-pressed 토글 시맨틱 (신규) | 클릭형 KPI 카드 | 카드 선택/해제 | `<button aria-pressed>` 로 선택 상태 전달 + **테두리 두께**로도 구분(색상 단독 금지). `onClick` 없는 카드는 `aria-pressed` 자체가 붙지 않음 | a11y | High | components/common/KpiCard.tsx |
| TC-A11Y-014 | 정렬 가능 헤더 aria-sort + button (신규) | 작업목록 헤더 | 촬영일시/영상 ID 클릭 | `<th aria-sort=ascending\|descending\|none>` + 내부 `<button>`. 정렬 상태를 아이콘·색이 아니라 aria-sort 로 전달 | a11y | High | features/task/components/TaskBoardTable.tsx |
| TC-A11Y-015 | 진행 오버레이 상태 전달·포커스 정책 (신규) | busy 300ms 초과(내부·포털 업로드 공통) | 오버레이 표시 | `role="status" aria-live="polite" aria-busy="true"` + 취소 버튼으로 포커스 이동(**포커스 트랩 없음** — Tab 으로 빠져나갈 수 있어야 한다). **경과 초는 라이브 리전에서 제외**(`aria-hidden` — 매초 낭독 방지), 스피너는 장식. **모달이 열려 있으면 포커스를 가져가지 않는다**(모달 뒤 보이지 않는 버튼이 Enter/Space 로 눌리는 것 방지) | a11y | High | features/label/components/BusyOverlay.tsx · features/label/busyPolicy.ts |

## H-14. 보안 (XSS/토큰/용어정책)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-189 | 프레임 설명 script 입력 텍스트 렌더(XSS) | `<script>` | PUT 후 표시 | 텍스트 escape | security | High | e2e/specs/frame-description.spec.ts |
| TC-FE-190 | dangerouslySetInnerHTML 미사용 전수 | 전 컴포넌트 | 정적 검사 | **JSX 속성으로서의 사용 0건**. ⚠ 단순 grep 은 25건(2026-08-03 실측, 구 표기 22건) 매치되나 전부 "미사용" 을 명시한 **주석·테스트 문자열**이다 — `dangerouslySetInnerHTML={` 패턴으로 검사할 것 | security | High | (grep: `dangerouslySetInnerHTML={`) |
| TC-FE-191 | FE 문구에 YOLO/SAM2 금지 | AI 도구/배치단계 | 렌더 | "AI 탐지/AI 분할/AI 추적", 미지 단계는 "처리중". ⚠ 예외: 시스템 설정의 설정 **키 이름**(`YOLO_CONF_THRESHOLD` 등)은 코드 식별자로 화면 문구가 아님 | security | High | features/label/components/AiToolModal.tsx · components/common/BatchStageIndicator.tsx |
| TC-FE-192 | 에러 메시지 내부경로/스택 미노출 | BE 에러 | 렌더 | 사용자 문구만(CWE-209) | security | High | components/common/ErrorBoundary.tsx · lib/api/resolveApiMessage.ts |
| TC-FE-193 | 경로 파라미터 인코딩(IDOR/Path 방어) | id 경로 | 요청 | ⚠ **axios 는 템플릿 보간된 경로 세그먼트를 자동 인코딩하지 않는다**(구 기대결과 "axios 인코딩" 은 오류 — 2026-08-03 정정). 실제 방어 3층: ①숫자 ID(`srcSn`/`rawSn`/`id`)는 `Number()` 로 좁혀 보간 ②**문자열 ID 는 호출부에서 명시 `encodeURIComponent`**(`label/api.ts:986,1017` trackId · `sysconfig/api.ts:26` config key) ③BE `LabelAccessGuard` 소유권 재검증 | security | Med | features/label/api.ts · features/sysconfig/api.ts · features/review/api.ts |
| TC-E2E-012 | 프레임 설명 저장 실패 에러 표시 | PUT 실패 | 저장 | 에러 표시 | e2e | Med | e2e/specs/frame-description.spec.ts |
| TC-E2E-013 | 프레임 설명 기존값 표시+PUT 반영 | 프레임 선택 | 입력/저장 | 기존 표시+PUT | e2e | Med | e2e/specs/frame-description.spec.ts |

## H-15. E2E 전체 사용자 시나리오

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-E2E-014 | WORKER 라벨링: 목록→캔버스→BBox 저장 | WORKER | serial | 저장 토스트 | e2e | High | e2e/specs/labeling-flow.spec.ts(정정, describe.serial 전체 범위) |
| TC-E2E-015 | WORKER 라벨링 진입 도구바 렌더 | WORKER | 진입 | 바운딩박스 버튼 | e2e | Med | e2e/specs/worker-labeling.spec.ts(정정, describe 전체 범위) |
| TC-E2E-016 | 전체 워크플로우: 라벨링→저장→제출→반려→롤백→재제출→승인 | WORKER(userNo=2001 `labelerPage`)+REVIEWER | serial | 각 단계 통과. ⚠ **정정(2026-08-03 3차)**: 대상 영상·프레임은 더 이상 하드코딩되지 않는다 — `resolveWorkflowFixture()`(`e2e/fixtures/test-data.ts:116-128`)가 실행 시점에 공개 API 로 (배정 영상, 첫 프레임)을 해석하고 제출 가능 상태 정규화·롤백용 커밋 2건 적층까지 수행한다. 구 상수 `WORKFLOW_VIDEO_ID=9035`/`WORKFLOW_SRC_SN=241` 은 **삭제**됨(H-ISSUE-143 해소). 스펙 테스트 수도 4→9(픽스처 유효성 단언 + 최종 완주 단언 추가) | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts · e2e/fixtures/test-data.ts |
| TC-E2E-017 | WORKER 이력 패널 오픈 후 롤백 | 반려 후 | 롤백 | 버전 롤백 | e2e | Med | e2e/specs/labeling-review-full-flow.spec.ts |
| TC-E2E-018 | REVIEWER 반려 처리 | 검수 진입 | 반려 | 반려 상태 전이 | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts |
| TC-E2E-019 | REVIEWER 최종 승인 | 재제출 후 | 승인 | **APPROVED** 전이(구 "COMPLETED 전이" 정정 — 최종 단언은 `readStatus()` 가 `/v1/reviews/{videoId}` 의 `dataSttsCd` 를 읽어 `APPROVED` 를 확인한다) | e2e | High | e2e/specs/labeling-review-full-flow.spec.ts(승인 단계, 최종 `APPROVED` 단언) |

## H-16. 작업목록 필터·정렬·KPI (SCR-TASK-001, 서버 이관) — 신설

> ★ 구속 정책(루트 CLAUDE.md "목록 화면 정렬·필터 정책"): **정렬은 시간축 단일**(상태 우선순위 `ORDER BY CASE` 금지), **필터·집계는 BE 에서 전체 기준**, **미등록 정렬 키는 이 엔드포인트에서 strict 400**. 검수목록(H-17)의 lenient 정책과 **통일하지 말 것**.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-219 | 진입 기본 정렬 = 등록일 최신순 명시 전송 (신규) | REVIEWER, URL 에 sort 없음 | 목록 진입 | 요청 `sort=regDt,desc` **명시 전송**(BE 기본값 의존 금지). 상태 우선순위 정렬은 어디에도 없다 | component | High | features/task/boardSort.ts · features/task/boardParams.ts(정정) |
| TC-FE-220 | 정렬 키는 allowlist 매핑으로만 해석 (신규) | `?sort=priority,desc` 등 미등록 키 | 진입 | `parseBoardSort` 가 **조용히 제거** → BE 400 미발생. 매핑 대상은 regDt/shtDt(capturedAt)/rawSn(videoId) 뿐 | security | High | features/task/boardSort.ts · features/task/__tests__/boardSort.test.ts |
| TC-FE-221 | 배치 상태 축은 URL 로 못 바꾼다(COMPLETED 고정) (신규) | `?status=UNASSIGNED` (구 북마크) | 진입 | 요청 `status=COMPLETED` 고정. 구 URL 이 파이프라인 미완료 영상을 노출하고 부제("처리 완료된 영상만 표시")를 거짓으로 만들던 문제 차단 | security | High | features/task/boardParams.ts(정정) |
| TC-FE-222 | URL 키 `status` 는 워크플로 축으로 해석(하위호환) (신규) | `?status=REVIEW_PENDING` | 진입 | `workStatus=REVIEW_PENDING` 로 요청, 상태 select 도 동일 값 표시 | component | High | features/task/boardParams.ts(정정, `searchParamsToFilters`) · features/task/__tests__/boardParams.test.ts |
| TC-FE-223 | allowlist 밖 워크플로 값은 무필터로 폴백 (신규) | `?status=BOGUS` | 진입 | 필터 미적용(빈 문자열) — 400 없이 전체 표시 | security | Med | features/task/boardParams.ts |
| TC-FE-224 | WORKER 축 IN_PROGRESS — select 유지 + 서버로도 전송 (정정 2026-08-04) | WORKER 시각 URL `?status=IN_PROGRESS` | 진입 | select 값 유지(`asAssignmentWorkStatusParam` 허용값이라 `TaskListPage.tsx:93-101` 정규화에서 제거되지 않음) + 요청 `workStatus=IN_PROGRESS` **전송**(`asWorkStatusParam` 이 아니라 `asAssignmentWorkStatusParam` 이 처리 — REVIEWER 축(TC-FE-225)과 반대 방향). ⚠ 구 기대결과("서버 파라미터에서는 제외")는 REVIEWER 축 서술이 잘못 섞인 것이었다 — 폐기(3차 검증, H-ISSUE-144 후속). 실측: `GET /v1/assignments?workerId=2001&workStatus=IN_PROGRESS` → 200, totalElements 49→1(필터링 확인) | component | High | features/task/boardParams.ts(`asWorkStatusParam` vs `asAssignmentWorkStatusParam`) · pages/TaskListPage.tsx |
| TC-FE-225 | REVIEWER 진입 시 IN_PROGRESS 필터 제거 (신규) | REVIEWER + `?status=IN_PROGRESS` | 진입 | 상태 select 는 빈칸인데 목록만 전체인 어긋난 화면 방지 — 초기 state 에서 `workStatus=''` 로 정규화 | component | Med | pages/TaskListPage.tsx(정정) |
| TC-FE-226 | 정렬 키 3개 상한 + 서버키 중복 제거 (신규) | 헤더 4회 연속 클릭 / `videoId`+`rawSn` 동시 지정 | 정렬 | 최대 3개(가장 오래된 키 버림), 같은 서버 키는 1개만 → BE 400(개수 상한·중복) 미발생 | security | High | features/task/boardSort.ts |
| TC-FE-227 | 헤더 클릭 = 1순위 승격 + desc→asc 토글 (신규) | 기본 정렬 regDt,desc | 촬영일시 헤더 1회/2회 클릭 | 1회=`shtDt,desc` 가 **맨 앞**, 2회=`shtDt,asc`. 뒤에 붙이면 regDt 가 지배해 무효 클릭이 된다 | component | High | features/task/boardSort.ts |
| TC-FE-228 | URL 왕복 후에도 aria-sort 유지(서버키 비교) (신규) | `?sort=rawSn,desc` 로 재진입 | 영상 ID 헤더 | `aria-sort=descending` 유지(컬럼명이 videoId→rawSn 으로 바뀌어도 서버 키로 비교) | a11y | Med | features/task/boardSort.ts · features/task/components/TaskBoardTable.tsx |
| TC-FE-229 | 기본 정렬은 URL 에 기록하지 않음 (신규) | 기본 정렬 상태 | URL 동기화 | `sort` 파라미터 없음(왕복 시 기본값 복원 — 멱등). 비기본 정렬만 기록 | component | Med | features/task/boardParams.ts(정정, `filtersToSearchParams`) |
| TC-FE-230 | 초기화가 정렬까지 기본값 복원 (신규) | `?sort=regDt,asc` (헤더 없는 축) | "초기화" 클릭 | 필터 + **정렬** 모두 기본값. regDt 는 컬럼 헤더가 없어 토글로 되돌릴 수 없으므로 이 버튼이 유일한 복구 경로 | component | High | pages/TaskListPage.tsx(정정, `handleFiltersReset`) |
| TC-FE-231 | KPI 5카드 = 서버 집계 + 클릭 토글 (신규) | REVIEWER | 카드 클릭/재클릭 | 전체/미배정/작업중/검수요청/반려 5카드. 클릭=해당 `workStatus` 필터, 재클릭=해제(전체). 숫자는 `/v1/tasks/board/summary` 전체 기준(현재 페이지 20건 아님) | component | High | features/task/components/TaskBoardKpiCards.tsx · pages/TaskListPage.tsx(정정) |
| TC-FE-232 | KPI '작업중' = PENDING 축 (신규) | 배정됐고 검수 미제출인 영상 존재 | KPI 확인 | '작업중' 카드가 `summary.inProgress`(=BoardWorkStatus.PENDING 집계)를 표시하고 클릭 시 `workStatus=PENDING` 전송. **`IN_PROGRESS` 는 BE 가 반환하지도 허용하지도 않는다** → 구 결함(REVIEWER 시각 0 고정) 재발 없음 | component | High | features/task/components/TaskBoardKpiCards.tsx · features/task/types.ts(정정) |
| TC-FE-233 | KPI 집계 요청에서 workStatus 제외 (신규) | 카드 선택된 상태 | summary 요청 | `workStatus` 미전송(카드 자체가 선택지). 검색어/이벤트/작업자 필터는 반영 | component | High | features/task/boardParams.ts(정정, `buildBoardSummaryParams`) |
| TC-FE-234 | KPI 로딩/실패/정상 3상태 구분 (신규) | summary 로딩 · 실패 | 렌더 | 로딩=스켈레톤(`kpi-loading`), 실패=`kpi-error` "집계 정보를 불러오지 못했습니다. 목록은 정상 표시됩니다."(role=status). **페이지 레벨 에러는 목록 쿼리만 결정** — KPI 실패가 목록을 가리지 않음 | component | High | features/task/components/TaskBoardKpiCards.tsx · pages/TaskListPage.tsx(정정) |
| TC-FE-235 | REVIEWER 시각 클라이언트 재필터 금지 (신규) | 서버 필터 적용된 20건 | 렌더 | `visibleRows === allRows` (재필터 없음). 헤더 "전체 N건"=서버 `totalElements` — 목록/총건수/KPI 가 같은 집합을 말한다 | component | High | pages/TaskListPage.tsx(정정) |
| TC-FE-236 | 체크박스 선택이 페이지 전환 후 잔존하지 않음 (신규) | 1페이지에서 3건 선택 → 2페이지 이동 | 일괄 배정 | 선택 초기화(`clearSelection`) + 화면에 없는 선택은 effect 가 제거 → **두 페이지 영상이 섞여 배정되던 결함** 재발 없음 | security | High | pages/TaskListPage.tsx(정정) |
| TC-FE-237 | 필터·KPI·정렬 변경 시 page 0 + 선택 해제 (신규) | 3페이지에서 필터 변경 | 조회 | page=0 으로 리셋(같은 이벤트에서 처리 — 직전 페이지로 요청이 한 번 더 나가지 않음), 선택 해제 | component | High | pages/TaskListPage.tsx(정정) |
| TC-FE-238 | 총 페이지 축소 시 범위 복귀 (신규) | 5페이지 보다 결과가 2페이지로 감소 | 재조회 | `page`→마지막 페이지로 되돌림(빈 목록+페이지네이션 소실로 복구 불가해지는 상태 방지) | component | Med | pages/TaskListPage.tsx(정정) |
| TC-FE-239 | 이벤트유형 옵션 서버 조회 + truncated 안내 (신규) | 옵션 상한 초과 | 필터 렌더 | `{items,truncated}` **객체** 응답을 훅이 분해(배열 오인 `.map` 금지). `truncated=true` 면 "옵션이 많아 일부만 표시됩니다". 조회 실패해도 빈 옵션으로 폴백(목록은 유지) | component | Med | features/task/hooks/useTaskBoardEventTypes.ts · features/task/components/TaskFilters.tsx |
| TC-FE-240 | 목록 조회 실패 시 배정 액션 잠금 (신규) | board 쿼리 실패 | 렌더 | ErrorState + 체크박스/배정 버튼 disabled + title 안내(최신 아닌 목록으로 배정 방지). WORKER 에게는 "배정 기능" 안내 미노출 | security | High | pages/TaskListPage.tsx(정정) · features/task/components/TaskBoardTable.tsx |
| TC-FE-241 | 검색어 100자 상한(400 왕복 방지) (신규) | 101자 입력 | 조회 | input `maxLength=100` + 파라미터 `slice(100)` (BE `@Size(max=100)` 정합) | component | Med | features/task/boardParams.ts(정정) · features/task/components/TaskFilters.tsx(정정) |
| TC-FE-242 | WORKER 시각도 서버 필터(정렬만 미지원) + 4카드 유지 (정정) | WORKER | 검색/상태/이벤트 필터 | `buildAssignmentParams` 가 검색어/상태/이벤트유형을 `/v1/assignments` 로 위임(REVIEWER 와 동일 원칙) — **서버 미지원은 정렬뿐**(정렬 UI 자체가 없어 전송하지 않음). ⚠ 구 기대결과("클라이언트 필터")는 폐기: `TaskListPage.tsx` 에 `.filter(` 재필터 코드가 없고 `tasks` 는 `tasksPage?.content` 를 그대로 쓴다. 헤더 "전체 N건"=서버 `totalElements`. KPI 는 표시 전용 4카드(WORKER 는 `/v1/tasks/board/summary` 가 403 이라 **목록 결과를 클라이언트에서 집계**, 클릭 필터 없음) | component | Med | pages/TaskListPage.tsx · features/task/boardParams.ts · features/task/components/TaskWorkerKpiCards.tsx |

## H-17. 검수목록 진입 기본값·필터·정렬·KPI (SCR-REVIEW-001) — 신설

> ★ 이 엔드포인트는 **lenient** 다 — 미등록 정렬 키는 400 이 아니라 기본 정렬 폴백(200), 화이트리스트 밖 `status` 는 **빈 결과 200**. 그래서 잘못된 값이 에러가 아니라 "검수 대상 없음" 으로 위장한다. FE 가 allowlist·역매핑을 스스로 지키는 것이 유일한 방어다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-243 | 진입 기본값 = 검수요청 + 제출일 오래된순(FIFO) (신규) | REVIEWER, 파라미터 없는 진입 | 목록 요청 | `status=PENDING`(BE 코드) + `sort=submittedAt,asc` **명시 전송**. BE 기본 정렬은 최신순이라 의존하면 정반대가 된다 | component | High | features/review/reviewListParams.ts · features/review/api.ts |
| TC-FE-244 | 진입 기본값을 URL 에도 기록 (신규) | 진입 직후 | 주소창/새로고침/북마크 | `?status=REVIEW_PENDING&sort=submittedAt,asc&page=0&size=20` 이 URL 에 남고 새로고침해도 같은 화면. 내부 state 로만 들면 "필터 없는 전체 목록" 으로 조용히 되돌아간다 | component | High | features/review/reviewListParams.ts · pages/ReviewListPage.tsx |
| TC-FE-245 | URL 정규화는 값까지 교정하며 멱등 (신규) | `?status=BOGUS&sort=labelPayload,asc` | 진입 | 요청·표시뿐 아니라 **주소도** 정규값으로 `replace` 교정(히스토리 미오염). 정규값 재정규화는 no-op → 루프 없음 | component | High | pages/ReviewListPage.tsx |
| TC-FE-246 | "전체" 는 키 삭제가 아니라 `status=ALL` (신규) | 상태 select 에서 전체 선택 | 새로고침 | 전체가 유지된다. 키를 지우면 "기본값 미적용" 과 구분 불가라 검수요청으로 되돌아간다 | component | High | features/review/reviewListParams.ts |
| TC-FE-247 | FE→BE 상태 역매핑 Record 강제 (신규) | 정적 검사 | `REVIEW_STATUS_TO_BE` | `Record<ReviewStatus, ReviewStatusParam>` 이라 **FE 상태가 늘면 키 누락 = 컴파일 에러**. 매핑: REVIEW_PENDING→PENDING / REVIEWING→IN_REVIEW / COMPLETED→APPROVED / REJECTED→REJECTED | security | High | features/review/api.ts · features/review/__tests__/api.statusMapping.test.ts |
| TC-FE-248 | 매핑에 없는 status 는 필터 생략 (신규) | 수기 URL 조작 등 | listReviews | 잘못된 코드를 보내 **빈 결과 200 으로 위장**하는 대신 status 를 빼고 요청(목록이 사라지지 않는다) | security | High | features/review/api.ts |
| TC-FE-249 | 미등록 정렬 키는 기본 정렬로 정규화 (신규) | `?sort=labelCount,desc` | 진입 | `submittedAt,asc` 로 정규화 후 전송·표시(BE lenient 폴백에 화면이 끌려가 "눌렀는데 순서 그대로" 가 되지 않게) | component | High | features/review/reviewListParams.ts · features/review/__tests__/reviewListParams.test.ts |
| TC-FE-250 | 상태 컬럼은 정렬 대상에서 제외 (신규) | 진입 기본 화면 | 상태 헤더 | 정렬 버튼 없음(`sortable` 미부여). BE allowlist 엔 있지만 단일 상태로 수렴한 화면에서 1차 정렬이 무효가 되고 tie-break(영상 ID 역순)가 실질 정렬이 되어 **FIFO 가 조용히 뒤집힌다** | component | High | pages/ReviewListPage.tsx · features/review/reviewListParams.ts |
| TC-FE-251 | 제출일만 정렬 가능 | 목록 | 제출일 헤더 클릭 | `sort=submittedAt,{asc\|desc}` 로 URL·요청 갱신 + page 0 복귀 | component | Med | pages/ReviewListPage.tsx |
| TC-FE-252 | 검색 300ms debounce + 즉시 적용/취소 (신규) | 검색어 연속 입력 | 입력 → 300ms 대기 / 조회 / 초기화 | 입력이 멎은 뒤 1회만 요청. "조회"(Enter)는 대기 타이머를 취소하고 즉시 적용. **초기화는 대기 타이머를 명시 취소**(안 하면 초기화 직후 이전 검색어가 되살아난다) | component | High | features/review/components/ReviewListFilters.tsx |
| TC-FE-253 | KPI 4카드 서버 집계 + summary 에 status 미전송 (신규) | REVIEWER | 카드 클릭/재클릭 | 검수요청/검수중/승인/반려 4카드, 클릭=필터·재클릭=해제. `buildReviewSummaryParams` 는 **`q` 만** 허용(타입상 status 를 넣는 것 자체가 컴파일 에러) | component | High | features/review/components/ReviewKpiCards.tsx · features/review/reviewListParams.ts |
| TC-FE-254 | summary 실패가 목록을 막지 않음 (신규) | `/reviews/summary` 500 | 렌더 | 카드 영역만 `kpi-error`, 표는 정상. 페이지 레벨 error 는 목록 쿼리만 본다. 비 REVIEWER 는 `enabled=false` 로 403 스팸 차단 | component | High | pages/ReviewListPage.tsx · features/review/hooks/useReviewSummary.ts |
| TC-FE-255 | 목록 실패와 0건을 구분해 말한다 (신규) | 목록 쿼리 실패 | 렌더 | ErrorState 만 표시(표·"총 0건" 미표시). 실패를 "대상 0건" 으로 오독하는 화면 제거 | component | High | pages/ReviewListPage.tsx |
| TC-FE-256 | 재조회 중 "이전 조건 결과" 고지 (신규) | 필터 전환 왕복 중 | 렌더 | `aria-busy` + role=status "갱신 중… (아래 목록은 이전 조건의 결과입니다)". keepPreviousData 로 KPI·배지는 새 조건인데 행은 옛 조건인 구간을 알린다 | a11y | Med | pages/ReviewListPage.tsx |
| TC-FE-257 | page 범위 초과 복귀(응답 전 미판단) (신규) | 보던 중 총건수 감소 | 재조회 | 마지막 페이지로 되돌림. **응답이 없으면(로딩·실패) 판단하지 않는다** — 첫 로딩에 0페이지로 튕기지 않음 | component | Med | pages/ReviewListPage.tsx |
| TC-FE-258 | 초기화 = 진입 기본값 복귀 + 이미 기본이면 비활성 (신규) | 정렬만 바꾼 상태 | 초기화 버튼 | 활성(정렬도 판정에 포함) → 검수요청·오래된순으로 복귀. 필터만 보면 정렬 복구 경로가 사라진다 | component | High | pages/ReviewListPage.tsx · features/review/components/ReviewListFilters.tsx |
| TC-FE-259 | 행 액션 접근성 이름 = 표시 문구 (신규) | 상태별 행 | 액션 버튼 | 보이는 문구("검수시작"/"이어서 검수"/"결과보기")가 그대로 accessible name(WCAG 2.5.3). 장식 `▶` 는 `aria-hidden` 으로 이름에서 제외 | a11y | High | pages/ReviewListPage.tsx |
| TC-FE-260 | 빈 목록 문구가 현재 상태 필터를 반영 (신규) | status=REVIEW_PENDING, 결과 0건 | 렌더 | "검수요청 항목이 없습니다"(전체일 때만 "검수 항목이 없습니다") + 활성 필터 배지 "검수요청 상태만 표시 중" — 기본값이 필터임을 알려 "전체 중 0건" 오인 차단 | component | Med | pages/ReviewListPage.tsx · features/review/components/ReviewListFilters.tsx |

## H-18. 영상 목록 · 영상 상세 (SC-007 / SC-009) — 2026-08-03 신설

> **결정 2 (커밋 `b27b3108`) + 결정 5(FE 분, 커밋 대기)** 를 담는다. BE 검색·필터 계약은 [B-18](B-batch-deidentify.md) 이 소관이다.
>
> **결정 2 — 진입 버튼 제거 + `/history/:videoId` 페이지·라우트 삭제 (SC-010 폐지)**
> - 영상 상세(SC-009)에서 **"버전관리로 이동"** 버튼과 프레임 미리보기 라이트박스의 **"라벨링 편집"** 버튼을 제거했다. 라이트박스 푸터는 **"닫기" 단일 버튼**이다.
> - 버전관리 버튼은 `/history/:videoId` 의 **유일한 실사용 진입점**이었다. 버튼만 지우면 진입점 없는 orphan 페이지가 남으므로(SC-015 전례) `HistoryPage`·라우트를 함께 제거했고, `LabelHeader` 의 죽은 `/history` `Link` 폴백도 없앴다.
> - ⚠ **기능 손실 없음 — 버전·diff·롤백 기능 케이스를 폐기하지 말 것.** 라벨링 화면 인라인 `HistoryPanel`(TC-FE-087)이 변경이력·버전 탭을 모두 제공하고 `features/version/**` 은 전부 유지된다.
>   폐기된 것은 **"별도 페이지로 진입한다"는 전제**뿐이다 → [D-4/D-5 TC-VERSION·TC-DIFF](D-review-version-notify.md) 는 그대로 유효하며 진입 경로만 인라인 패널로 읽는다.
>
> **결정 5(FE) — 검색 필터 전송 + `capturedAt` 축 정정**
> - 화면은 예전부터 `cctvNameKeyword`/`eventTypeCd`/`from`/`to` 를 URL·요청에 실었지만 **BE 컨트롤러 시그니처에 없어 조용히 버려졌다**(상태 필터만 동작). 이번에 BE 가 받으면서 실제로 적용된다.
> - `capturedAt` 은 **촬영 시각(`SHT_DT`)** 이며 **수신 시각(`regDt`) 폴백을 FE·BE 양쪽에서 제거**했다. 화면 컬럼('녹화일')·정렬 키(`capturedAt→shtDt`)·기간 필터가 한 축이 된다.
> - 규칙 전문 → [v2-wiki 05 §5.5.3](../v2-wiki/05-video-management.md) · [04 화면 IA](../v2-wiki/04-screens-ia.md)
>
> **★2026-08-05 — 개인정보 유무 화면 노출 폐지 (R1/R2, 화면 노출만·응답 필드는 존치)**
> - 관제서버가 개인정보 유무를 실제로 보내지 않는다(dev DB 실측 — 인입 원장 `LS_DATA_INGEST.ANONY_INCL_YN`/`PSDO_INCL_YN`/`PRVC_INCL_YN` 40행 전부 NULL). 화면이 보여주던 값은 관제값이 아니라 적재 시 고정되는 레거시 컬럼 `PRVC_TYPE_CD` 였다.
> - **컬럼·응답 필드(`privacyTypeCd`)는 존치**하고 **화면 노출만 제거**한다 — `components/common/PrivacyBadge.tsx` 삭제, `VideoListPage.tsx`(목록 컬럼 9→8) · `VideoDetailPage.tsx`(기본정보 항목) 에서 참조 제거.
> - ⚠ **이 카탈로그에 개인정보 배지의 화면 노출을 검증하던 기존 케이스가 없었다** — 그래서 아래는 폐기가 아니라 **신설**이다(제거 자체를 고정하는 회귀 가드). 라벨링 화면의 **개인정보 메타 패널**(`VideoPrivacyMetaPanel`/`FramePrivacyMetaPanel`, TC-FE-079)은 이 변경과 **무관**하며 그대로 유지된다 — 혼동 금지.
> - 상세 → [v2-wiki 05 §5.4](../v2-wiki/05-video-management.md)
>
> **★2026-08-05 — `eventTypeCd` 축 정정(TC-FE-301)**: 값은 "카테고리 키"가 아니라 **이벤트유형코드이며 표시명 그룹으로 접힌 옵션**이다(구 문구는 B-18 이 회차 3 작성 당시부터 이미 V168 과 어긋나 있던 것을 이번에 함께 정정). 상세 → [v2-wiki 18 §18.4.1](../v2-wiki/18-database.md).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-297 | 영상 상세에 "버전관리로 이동" 버튼 미노출 (신규) | 정상 영상 상세 진입 | 렌더 | 해당 버튼·링크 모두 없음(회귀 가드). 버전 이력 확인은 라벨링 화면 히스토리 패널로 | component | High | pages/VideoDetailPage.tsx · features/video/\_\_tests\_\_/VideoDetailPage.test.tsx |
| TC-FE-298 | 프레임 라이트박스 푸터 = "닫기" 단일 버튼 (신규) | 프레임 미리보기 클릭 | 라이트박스 렌더 | "라벨링 편집" 버튼 없음, 푸터는 닫기만. `onNavigateLabel` prop 체인 제거 | component | High | pages/VideoDetailPage.tsx · VideoDetailPage.test.tsx |
| TC-FE-299 | `/history/:videoId` 라우트·페이지 제거 (신규) | 인증된 내부 사용자 | `/history/1` 직접 진입 | 라우트 미정의 → 404(AppErrorPage). `pages/HistoryPage.tsx` 및 그 테스트 2파일 삭제됨 | component | Med | router/index.tsx(=`/history` 라우트 부재) · [v2-wiki 04 deprecated 정리](../v2-wiki/04-screens-ia.md) |
| TC-FE-300 | `onHistoryClick` 미지정 시 히스토리 버튼 자체가 안 뜬다 (신규) | `LabelHeader` 에 `onHistoryClick` 미주입 | 렌더 | 버튼 미렌더(구 `/history` `Link` 폴백 제거 — 남겨두면 **죽은 링크**가 된다). 주입 시에는 토글 버튼 + `aria-expanded` | component | High | features/label/components/LabelHeader.tsx · features/label/\_\_tests\_\_/LabelHeader.test.tsx |
| TC-FE-301 | 검색·필터 5종이 목록 요청에 실린다 (정정 2026-08-05 — "카테고리 키"→"이벤트유형코드") | 영상 현황 목록 | 조회 버튼 | `cctvNameKeyword`(trim, 빈값이면 미전송)·`dataSttsCd`·`eventTypeCd`(이벤트유형코드, 표시명 그룹 옵션에서 고른 값 그대로 재전송)·`from`·`to` 전송 + `page=0` 복귀. 초기화는 5종 전부 비우고 `page=0,size` 만 남긴다. ⚠ 구 기대결과 "카테고리 키"는 B-18 작성 당시부터 V168(유형 축 전환)과 어긋나 있던 문구였다. ★**정정 2026-08-08(근거)** — `from`·`to` 두 입력은 이제 `VideoFilters` 가 직접 재구현하지 않고 사양 컴포넌트 `DateRangePicker`(UI-029)가 소유한다. **전송·URL 직렬화 형식(`yyyy-MM-dd`)과 상호 min/max 제약은 무변경**이며 상세는 [H-32](#h-32-영상-목록-기간-필터--사양-컴포넌트ui-029-daterangepicker-배선-screen-008--2026-08-08-신설) | component | High | features/video/components/VideoFilters.tsx(`VideoFilters`) · components/common/DateRangePicker.tsx(`DateRangePicker`) · features/video/api.ts(`listVideos`) |
| TC-FE-302 | 상태 드롭다운 = BE 배치 단계 5종 (신규) | 필터 렌더 | 상태 select | 전체/완료(`COMPLETED`)/처리중(`PROCESSING`)/**마킹 대기(`MARKING_READY`)**/대기(`PENDING`)/실패(`FAILED`). `MARKING_READY` 누락 시 적재~마킹 구간 영상을 상태로 좁힐 수 없다(그 상태 영상은 실제로 존재) | component | High | VideoFilters.tsx · features/video/\_\_tests\_\_/VideoFilters.test.tsx |
| TC-FE-303 | `capturedAt` 은 `regDt` 로 폴백하지 않는다 (신규) | 응답 `capturedAt=null`, `regDt` 존재 | `normalizeVideo` | `capturedAt=''` → 화면 `-`. **수신 시각으로 몰래 채우지 않는다**(구 `v.capturedAt ?? v.regDt` 폴백 제거). 수신 시각은 별도 필드로 계속 노출 | component | High | features/video/api.ts · features/video/\_\_tests\_\_/api.test.ts |
| TC-FE-316 | 영상 목록에 개인정보 컬럼이 렌더되지 않는다 (신설, R1 2026-08-05) | `privacyTypeCd='PRVC'` 포함 응답(BE 계약 무변경) | 목록 렌더 | 헤더 `columnheader('개인정보')` 미노출(헤더 8종 — CCTV명/이벤트/녹화일/길이/처리단계/배정자/액션+checkbox) + 배지 텍스트('개인정보'/'가명처리'/'비식별') 미노출. 응답 필드는 내려오되 화면에만 안 쓴다 | component | High | pages/VideoListPage.tsx(헤더 블록) · features/video/\_\_tests\_\_/VideoListPage.test.tsx |
| TC-FE-317 | 빈 목록 상태의 colSpan이 헤더 칸수와 같다 (신설, R1 2026-08-05) | 목록 0건 | 빈 상태 렌더 | `<td colSpan={8}>` = 실제 헤더 `columnheader` 개수(9→8). 컬럼 제거 시 헤더만 지우고 `colSpan` 을 안 고치면 빈 상태 셀이 테이블 폭을 못 채운다 | component | Med | pages/VideoListPage.tsx · features/video/\_\_tests\_\_/VideoListPage.test.tsx |
| TC-FE-318 | 로딩 스켈레톤 칸수가 헤더 칸수와 같다 (신설, R1 2026-08-05) | 목록 조회 pending | 로딩 렌더 | 스켈레톤 `<td>` 개수(9→8) = 헤더 개수. 컬럼 제거 시 스켈레톤 칸수를 안 고치면 로딩 중 레이아웃이 헤더와 어긋난다 | component | Med | pages/VideoListPage.tsx · features/video/\_\_tests\_\_/VideoListPage.test.tsx |
| TC-FE-319 | 영상 상세에 개인정보 항목이 렌더되지 않는다 (신설, R2 2026-08-05) | `privacyTypeCd='PRVC'` 포함 응답(BE 계약 무변경) | 상세 렌더 | 기본정보 목록에 "개인정보 분류" 라벨·배지 텍스트('개인정보'/'가명처리'/'비식별') 모두 미노출 | component | High | pages/VideoDetailPage.tsx(`InfoTab` metaRows, `PrivacyBadge` 항목 제거) · features/video/\_\_tests\_\_/VideoDetailPage.test.tsx |

---

> **불확실 항목 갱신 (2026-07-30)**
> - **#23 VideoPlayer 배속 — 코드로 확정**: `SPEED_OPTIONS=[0.25,0.5,1,1.5,2,4]` 이산 6버튼 + 기본 1x. **자유 수치 입력이 없어 클램프 로직 자체가 불필요**하다 → TC-FE-205 로 케이스화. (`features/marking/components/VideoPlayer.tsx:34,46-49,125-140`)
> - **#25 Dev 페이지/내부 TUS — 코드로 확정**: `pages/dev/DevAutolabelTestPage.tsx` 는 **존재**하며 `isDevUploadEnabled()` 빌드 플래그 안에서만 라우팅된다(`router/index.tsx:183-203`). 내부 TUS(`features/upload/*`, 기본 엔드포인트 `/uploads`)의 **유일한 소비자가 이 dev 페이지**이므로, 플래그 OFF 인 prod 빌드에서는 두 청크 모두 산출물에서 제거된다 → "dead-code 여부" 는 **prod 빌드 기준 dead-code 맞음 / dev 빌드에서는 살아있음**. 별도 폐지 작업 없이 TC-FE-028·029 의 플래그 게이팅만 검증하면 된다. (포털 TUS `/portal/uploads/tus` 는 별개이며 운영 경로다 — TC-FE-169)
> - **#22 캔버스 드로잉 픽셀 정확도** · **#24 반응형/WCAG 색대비**: 미확정 유지(런타임 브라우저 도구 필요).
> 상세는 [UNCERTAINTIES.md](UNCERTAINTIES.md)

## H-19. 히스토리 '버전' 탭 — 커밋 1건 선택 = 현재 작업본 비교 (SC-005 인라인 패널) — 2026-08-05 신설

> **구 동작(폐기)**: 커밋 1건 클릭 시 목록상 **직전 버전**(`list[idx + 1]`)과 비교했다. 그래서 승인 버전이 **1건뿐인 프레임은 비교 대상이 `undefined`** 라 `enabled:false` 로 조회가 아예 실행되지 않았고, diff 를 볼 수 없었다.
> **현 동작**: 커밋 1건 클릭 = 그 버전 ↔ **현재 작업본**(`/versions/{hash}/diff-with-working`). 커밋 2건 체크 = 두 버전 비교(`/diff?compareWith=`)는 **그대로 유지**.
> BE 계약·비교축(R7)은 [D-5a](D-review-version-notify.md) TC-DIFF-032~047 소관. 진입점은 라벨링 화면 인라인 `HistoryPanel`(TC-FE-087)이 유일하다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-320 | **커밋 1건 클릭 = 현재 작업본 diff 호출** (신규) | '버전' 탭, 커밋 목록 2건 이상 | 커밋 행 1건 클릭 | `/versions/{hash}/diff-with-working` **요청 URL** 로 조회 + `DiffViewer` 렌더. ⚠ "DiffViewer 가 렌더됐다"만 단언하면 구 경로와 구분되지 않는다 — **URL 로 단언할 것** | component | **Critical** | features/version/components/HistoryPanel.tsx · features/version/api.ts(getWorkingDiff) · features/version/\_\_tests\_\_/HistoryPanel.test.tsx(커밋_1건_클릭시_현재_작업본_diff_API_가_호출된다) |
| TC-FE-321 | **버전이 1건뿐인 프레임도 diff 표시** (신규) | 버전 목록 길이 **1** | 그 커밋 클릭 | diff 표시 + 미선택 안내 문구 미노출. **구 동작에서는 아무것도 뜨지 않았다** — 이 기능의 존재 이유 | component | **Critical** | features/version/components/HistoryPanel.tsx · features/version/\_\_tests\_\_/HistoryPanel.test.tsx(버전이_1건뿐이어도_클릭하면_diff_가_표시된다) |
| TC-FE-322 | 변경 0건 = "변경 없음" 안내 (신규) | 작업본 == 스냅샷(빈 배열 200) | 커밋 1건 클릭 | **"변경 없음 / 이 버전 이후 변경된 라벨이 없습니다."** 노출. 두 버전 비교의 기존 문구("두 버전이 동일합니다")와 **구분**된다 | component | High | features/version/components/DiffViewer.tsx(emptyTitle · emptyMessage) · features/version/\_\_tests\_\_/HistoryPanel.test.tsx(작업본과_동일하면_변경_없음_문구가_표시된다) |
| TC-FE-323 | **조회 실패는 "변경 없음"이 아니라 에러** (신규) | 412(비식별 재처리 대기) 등 | 커밋 1건 클릭 | "diff 조회 실패" 노출 + **"변경 없음" 텍스트 부재**. 렌더 분기 순서가 `미선택 → 로딩 → 에러 → 결과` 라 에러가 "변경 없음"으로 가려질 수 없다(가려지면 거짓 안내) | component | High | features/version/components/HistoryPanel.tsx · features/version/\_\_tests\_\_/HistoryPanel.test.tsx(작업본_diff_조회_실패시_에러_문구가_표시된다) |
| TC-FE-324 | 두 커밋 체크 = 기존 두 버전 diff 유지 (회귀 가드) | 체크박스 2건 선택 | 렌더 | `/versions/{to}/diff?compareWith={from}` 호출 + 작업본 diff 호출 **0건**(상호 배타) | component | High | features/version/components/HistoryPanel.tsx · features/version/\_\_tests\_\_/HistoryPanel.test.tsx(두_커밋_체크시_기존_두_버전_diff_API_가_호출된다) |
| TC-FE-325 | **단일 선택은 더 이상 직전 버전과 비교하지 않는다** (구 동작 폐기 고정) | 커밋 2건 이상, 1건 클릭 | 렌더 | 구 경로(`/versions/{직전hash}/diff`)에 200 을 심어놔도 **호출 0건**. `list[idx + 1]`·`singleDiffFrom`·`singleDiffTo` 코드 잔재 0 | component | High | features/version/components/HistoryPanel.tsx · features/version/\_\_tests\_\_/HistoryPanel.test.tsx(단일_선택은_더_이상_직전_버전과_비교하지_않는다) |
| TC-FE-326 | **프레임 전환 시 stale diff 미호출 — 두 축 모두** (신규) | 선택/체크가 있는 상태에서 `srcSn` 전환 | rerender | 추가 diff 요청 없음 + 안내 문구로 복귀. ⚠ 선택 리셋이 `useEffect`(커밋 이후)라 한 렌더 동안 이전 프레임 hash 가 남아 **다른 프레임의 라벨 diff 가 잠깐 표시**된다(가드 제거 시 요청 2회 실측). 현재 목록에 실재하는 hash 만 조회하도록 **단일 선택·두 버전 비교 두 축에 공용 헬퍼(`inList`)로 동일 적용** — 한쪽만 가드하면 비대칭이 코드에 흔적 없이 남는다 | component | High | features/version/components/HistoryPanel.tsx(inList) · features/version/\_\_tests\_\_/HistoryPanel.test.tsx(프레임_전환시_선택이_초기화되어_작업본_diff_가_호출되지_않는다 · 프레임_전환시_두_커밋_체크가_초기화되어_버전간_diff_가_호출되지_않는다) |
| TC-FE-327 | 캐시 키 분리 + 자동 갱신 배선 (신규) | — | queryKey 구조 | `VERSION_KEYS.workingDiff` 가 ①기존 `VERSION_KEYS.diff` 와 **키가 겹치지 않고**(겹치면 두 버전 diff 결과가 작업본 diff 자리에 표시) ②**`VERSION_KEYS.all` 하위**(라벨 저장 `useUpdateLabels`·롤백 `useRollback` 의 기존 `invalidateQueries({queryKey: VERSION_KEYS.all})` 로 자동 갱신 — 신규 무효화 배선 불필요) | component | Med | lib/queryKeys.ts(VERSION_KEYS.workingDiff) · features/label/hooks/useUpdateLabels.ts · features/version/hooks/useRollback.ts |

## H-20. 라벨 색상 — 마스터 연결(`labelId`) 보존 + 판정원 일원화 (SCR-LABEL-001) — 2026-08-06 신설

> **결함(사용자 신고)**: *저장하면 객체 색이 바뀐다.* 캔버스에서 만든 라벨이 `labelId` 없이 저장돼 BE 가 `LS_LABEL` 을 조인하지 못했고, 재조회 응답의 `color`/`label` 이 null 이라 `getLabelDisplayColor` 가 **3순위 `trackId` 해시색**으로 떨어졌다. 저장 전에는 마스터 색이 보이고 저장 후에만 바뀌는 증상이 여기서 나온다.
> **★구 정책 폐기**: 우측 객체 패널 **그룹 헤더 점**의 판정원이던 하드코딩 색상표 `labelColors.LABEL_CLASS_DEFS`(9종)를 폐지하고 **파일째 삭제**했다 — 마스터와 어긋나는 두 번째 진실원이었다. 같은 파일의 **표시명** 치환이 2026-08-03 에 같은 이유로 폐지된 전례(TC-FE-293)의 연장선이며, 색 판정의 단일 진실원은 이제 `utils/labelColor.getLabelDisplayColor` 하나다.
> **보존된 축**: 개별 항목 좌측 **막대는 `trackIdToColor` 유지**(트랙 시각화 의도 — 사용자 확정). 그룹=분류축 / 항목=트랙축의 경계를 TC-FE-333 이 고정한다.
> **백필 없음**(사용자 확정): 기존 `labelId=null` 행은 그 프레임을 **재저장할 때 자연 복구**된다. BE·마이그레이션 변경 0.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-328 | **신규 라벨 4도구 모두 `labelId` = 마스터 PK** (신규) | 활성 라벨 마스터 존재, 도구별 드로잉 확정 | BBOX 드래그 / POLYGON 확정 / AI 분할(SAM_SEGMENT) 적용 / KEYPOINT 17점 배치 | `onLabelAdd` payload 가 4경로 **모두** `labelId === def.labelId`(= `classId`) + `className === def.name`. 생성 payload 는 **단일 진입점 `newLabelFrom`** 을 거쳐 도구마다 필드셋이 어긋날 수 없다(도구 추가 시 재발 차단) | component | **Critical** | features/label/canvas/layers/OverlayLayer.tsx(newLabelFrom) · features/label/canvas/layers/resolveDefaultLabel.ts(resolveDefaultLabel) · features/label/canvas/layers/\_\_tests\_\_/OverlayLayerLabelIdWiring.test.tsx(BBOX\_드래그\_생성시\_labelId가\_라벨마스터PK로\_설정된다 · POLYGON\_생성시… · KEYPOINT\_17점\_생성시… · AI분할(SAM\_SEGMENT)\_폴리곤\_적용시…) |
| TC-FE-329 | 직렬화가 마스터 연결을 보존 + 레거시 `null` 하위호환 (신규) | 캔버스 생성 라벨 / `labelId` 없는 레거시 라벨 | `PUT /v1/frames/{srcSn}/labels` 저장 | 캔버스 생성분은 items[].`labelId` **non-null**(마스터 PK 그대로) — 이 값이 BE `LS_LABEL` 조인 키다. `labelId` 가 없는 라벨은 **`null` 로 직렬화**(필드 생략·임의 추정 없음)라 구 데이터도 그대로 저장된다 | component | **Critical** | features/label/api.ts(serializeLabel) · features/label/\_\_tests\_\_/api.test.ts(캔버스에서\_생성된\_라벨은\_labelId가\_non\_null\_로\_직렬화된다 · labelId\_없는\_라벨은\_null\_로\_직렬화된다\_하위호환) |
| TC-FE-330 | 분류 드롭다운 변경 = `classId`·`labelId`·`color` 동시 갱신 (신규) | 객체 선택 후 속성 패널 '분류' 변경 | `<select>` change | 세 필드가 **함께** 갱신된다. `labelId` 를 빼면 저장 왕복에서 마스터 조인이 끊기고(신규 생성부와 동일 결함), `color` 를 안 덮으면 `getLabelDisplayColor` 가 `label.color` 를 **최우선** 참조하므로 **분류를 바꿔도 캔버스 색이 옛 마스터 색으로 남는다** | component | **High** | features/label/components/ObjectAttributePanel.tsx(handleClassChange) · features/label/\_\_tests\_\_/ObjectAttributePanel.phase8.test.tsx(드롭다운\_변경시\_labelId와\_color도\_새\_마스터로\_함께\_갱신된다) |
| TC-FE-331 | **SAM2 Track 은 라벨명 역해석 — 모호하면 `null`(fail-closed)** (신규) | BE `TrackedItem` 은 `label` **문자열만** 돌려준다(마스터 PK 미포함) | 활성 마스터에 동명 1건 / 0건 / 2건 이상 / 비활성(`useYn='N'`)만 / 마스터 미로딩 | 정확히 **1건**일 때만 그 `labelId` 를 붙이고, **0건·2건 이상·비활성·미로딩은 `null`** 로 남긴다. 라벨명은 운영자가 바꿀 수 있고 유일성 보장이 없어, 추측해 붙이면 **다른 분류로 저장**된다(잘못된 귀속 > 미연결) | security | **High** | features/label/utils/labelMasterLookup.ts(resolveLabelIdByName) · features/label/api.ts(trackedItemToLabel) · pages/label/LabelingPage.tsx(labelIdOf) · features/label/utils/\_\_tests\_\_/labelMasterLookup.test.ts(동명\_활성\_마스터가\_2건\_이상이면\_null\_잘못된\_분류로\_저장되지\_않는다 · 비활성(useYn=N)\_마스터는\_매칭하지\_않는다) · features/label/\_\_tests\_\_/api.test.ts(SAM2\_추적결과는\_전달받은\_labelId로\_마스터에\_연결된다) |
| TC-FE-332 | **그룹 헤더 점 = 라벨 마스터 색상** (구 하드코딩 색상표 판정 폐기) | 마스터 `PERSON`(labelId=42) 색 `#123456` | 우측 '객체' 패널 렌더 | 그룹 점이 **마스터 색**. 구 표의 `PERSON` 색(`#EF4444`)이 **아니다**. BE 가 실어준 `label.color` 가 있으면 그 값 우선, 없으면 `labelId`→마스터 lookup. 미검증 문자열은 `safeHexColor` 로 정규화돼 inline style 로 새지 않는다 | component | **High** | features/label/components/ObjectClassTree.tsx(ObjectClassTree — 그룹 헤더 color) · features/label/utils/labelColor.ts(getLabelDisplayColor · safeHexColor) · features/label/\_\_tests\_\_/ObjectClassTreeGroupColor.test.tsx(그룹\_점은\_라벨마스터\_색상이며\_하드코딩\_색상표\_색이\_아니다 · BE가\_실어준\_label\_color가\_있으면\_그\_값을\_쓴다) |
| TC-FE-333 | **개별 항목 막대 = `trackId` 해시색 유지 + 그룹에는 트랙색 미유입** (의도된 동작 보존 가드) | 같은 분류·다른 `trackId` 2건 / 마스터 미로딩 상태 | 렌더 | ①항목 막대는 트랙별로 **서로 다른 색**이며 그룹 점 색과도 다르다(트랙 시각화 의도 — **제거 금지**) ②그룹 점은 `useTrackFallback:false` 라 마스터를 못 찾아도 **트랙 해시색으로 떨어지지 않고** 결정적 `#RRGGBB` fallback 으로만 간다(그룹은 분류축) | component | **High** | features/label/components/ObjectClassTree.tsx(barColor = trackIdToColor) · features/label/utils/trackColor.ts(trackIdToColor) · features/label/\_\_tests\_\_/ObjectClassTreeGroupColor.test.tsx(개별\_항목\_막대는\_여전히\_trackId\_해시색이다\_의도된\_동작\_보존 · 그룹\_점에는\_trackId\_해시색이\_새지\_않는다) |
| TC-FE-334 | **그룹 대표 색상은 배열 순서와 무관하게 결정적** (신규) | 그룹핑 키가 `className` **문자열**이라 동명·다른 `labelId` 가 한 그룹에 섞일 수 있다(마스터 이름에 유일성 제약 없음) | 같은 집합을 순서만 뒤집어 렌더 / `labelId` 전부 null 인 레거시 그룹 / 미연결+연결 혼재 | ①순서를 뒤집어도 그룹 점 색 **동일**(최소 `labelId` 항목이 대표) ②전부 미연결이어도 크래시 없이 동일(= `id` 사전순 tiebreak) ③연결된 항목이 미연결보다 대표 우선. 정렬·필터·재조회로 색이 흔들리지 않는다 — `items[0]` 대표는 **순서 의존**이라 회귀다 | component | **High** | features/label/components/ObjectClassTree.tsx(groupRepresentative · compareRepresentative · masterKeyOf) · features/label/canvas/layers/resolveDefaultLabel.ts(resolveDefaultLabel — `sortNo asc → labelId asc` 규약 정합) · features/label/\_\_tests\_\_/ObjectClassTreeGroupColor.test.tsx(동명\_다른labelId가\_섞인\_그룹에서\_배열\_순서를\_뒤집어도\_그룹\_점\_색이\_같다 · labelId가\_전부\_null인\_레거시\_그룹에서도\_순서\_독립이다 · 마스터\_연결된\_항목이\_미연결\_항목보다\_대표로\_우선한다) |

## H-21. dev 업로드 「검증이벤트유형」 select (SC-027 TUS 업로드 폼) — 2026-08-06 신설

> 외부 VLM 검증 API(`POST /v1/videovlm/verify`)의 필수 `event_type`(enum 6종)을 **dev 업로드에서 직접 지정**하기 위한 입력이다(@req R7). 원래 조달처는 관제 인입(`LS_DATA_INGEST.VRFC_EVNT_TYPE_CD`)이지만 관제 반영이 협의 이후라, 그 전까지 연동을 실제로 돌려볼 수단이 없었다.
> **FE 전용 범위** — BE 계약(DTO `@AssertTrue` · 서비스 2차 방어선 · 인입 적재)은 이미 열려 있고 이번 회차에 무변경이다. BE 분은 [B-20](B-batch-deidentify.md) TC-ULD-025~048 소관.
> ★ **이 입력은 UX 보조일 뿐 신뢰 경계가 아니다** — 최종 판정은 서버(400)가 한다. FE 에 별도 검증 로직을 두지 않는다(두면 BE 와 갈라지는 세 번째 규칙이 된다).
> ★ **2026-08-06 갱신 — 「6종 프리셋 + 직접 입력」으로 열렸다**(사용자 확정). 구 동작(6종 select 만)은 폐기다. 같은 날 위탁 게이트가 "조달값을 그대로 실어 항상 위탁"으로 반전됐고 BE 도 6종 allowlist 를 **형식 검사**(소문자·숫자·밑줄 20자)로 좁혀, 우리 화면만 6종에 갇히는 비대칭을 없앤 것이다. 센티넬 `__manual__` 은 **화면 모드 표식이라 전송되지 않는다**.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-335 | **select 에 미지정 + 프리셋 6종 + 직접 입력이 렌더된다** (2026-08-06 정정 — 구 기대 "6종만" 폐기) | dev 업로드 패널 렌더 | option 목록 | 값이 `['', 'fire', 'fall', 'violence', 'flooding', 'car_accident', 'kidnapping', '__manual__']` 이고 **기본 선택은 미지정(`''`)**. 목록은 FE 단일 진실원을 map 한 결과이며 화면에 리터럴을 복제하지 않는다. 직접 입력 칸은 그 옵션을 고르기 **전에는 없다** | component | **Critical** | features/upload/components/TusMetaFieldsets.tsx(EventFieldset) · features/upload/components/tusUploadForm.ts(VRFC\_EVNT\_TYPES) · features/upload/\_\_tests\_\_/tusUpload.test.tsx(검증이벤트유형_select에_6종_옵션과_직접입력이_렌더된다) |
| TC-FE-336 | **라벨은 한글 병기, 전송값은 벤더 enum 원문** (신규) | 동일 | option 라벨/값 대조 | 라벨 `화재 (fire)`·`교통사고 (car_accident)` 처럼 한글+enum 병기이고 **값은 소문자 원문 그대로**. ⚠ 라벨을 전송하면 벤더 검증에서 거부된다 — 표기를 대문자·한글로 정규화하지 않는다 | component | **Critical** | features/upload/components/tusUploadForm.ts(VRFC\_EVNT\_TYPES) · features/upload/\_\_tests\_\_/tusUpload.test.tsx(옵션_라벨은_한글병기이고_전송값은_영문_enum이다) |
| TC-FE-337 | **선택값이 세션 생성 바디에 `vrfcEvntTypeCd` 키로 담긴다** (신규) | `fire` 선택 + 파일 선택 후 업로드 시작 | `POST /uploads` 바디 | `{"vrfcEvntTypeCd":"fire", ...}`. ⚠ BE `InternalUploadCreateRequest` 는 `@JsonProperty` 없는 **record 라 자바 필드명이 곧 JSON 키**다 — 키 이름을 임의로 바꾸면 값이 조용히 버려지고 400 도 나지 않는다(미지정으로 적재됨) | component | **Critical** | features/upload/components/tusUploadForm.ts(toPayload) · features/upload/api/tusClient.ts(InternalUploadCreatePayload) · features/upload/\_\_tests\_\_/tusUpload.test.tsx(선택한_값이_업로드_세션_생성_바디에_담긴다) |
| TC-FE-338 | **미지정이면 키 자체를 전송하지 않는다 + 기존 필드 회귀 없음** (신규) | 아무것도 고르지 않은 기본 상태로 업로드 | `POST /uploads` 바디 | `'vrfcEvntTypeCd' in body === false` 이고 **400 이 아니다**(선택 입력). 같은 바디에 `cctvId`·`srcType` 등 기존 필드는 그대로 실린다. 미지정 업로드는 오류가 아니라 "event_type 미수신 → 위탁 SKIPPED" 경로를 밟는 정상 동선이라 **필수 필드로 만들지 않는다** | component | **Critical** | features/upload/components/tusUploadForm.ts(toPayload · text) · features/upload/\_\_tests\_\_/tusUpload.test.tsx(미지정이면_검증이벤트유형이_전송되지_않는다) |
| TC-FE-339 | select 가 label 과 연결돼 있다 (접근성) | 동일 | `getByLabelText('검증이벤트유형')` | SELECT 요소가 반환되고 `label[for=...]` 이 실재한다. 폼 필드는 label 연결 필수(`component.md`)이며 기존 `srcType` select 와 같은 `htmlFor`/`id` 패턴을 따른다 | a11y | High | features/upload/components/TusMetaFieldsets.tsx(EventFieldset) · features/upload/\_\_tests\_\_/tusUpload.test.tsx(select는_label과_연결되어_있다) |
| TC-FE-356 | **★직접 입력을 고르면 자유입력칸이 열리고 그 값이 전송된다** (신규) | select 에서 `직접 입력` 선택 | `earthquake` 입력 후 업로드 | `POST /uploads` 바디 `vrfcEvntTypeCd="earthquake"`. **센티넬 `__manual__` 이 전송되지 않는다** — 그건 화면 모드 표식이며, 전송되면 BE 형식 검사는 통과하지만(소문자·밑줄) 벤더에 의미 없는 값이 나간다 | component | **Critical** | features/upload/components/TusMetaFieldsets.tsx(VrfcEvntTypeField) · features/upload/\_\_tests\_\_/tusUpload.test.tsx(직접입력을_고르면_자유입력칸이_열리고_그_값이_전송된다) |
| TC-FE-357 | **프리셋 → 직접 입력 전환 시 이전 프리셋 값이 남지 않는다** (신규, 조용한 오전송 가드) | `fire` 선택 후 `직접 입력` 으로 전환 | 아무것도 타이핑하지 않고 업로드 | 자유입력칸 값이 **빈 문자열**이고 바디에 `'vrfcEvntTypeCd' in body === false`. 남겨두면 화면은 "직접 입력"인데 **`fire` 가 전송**된다(사용자가 인지할 수 없는 오전송) | component | **Critical** | TusMetaFieldsets.tsx(VrfcEvntTypeField — handleSelect) · tusUpload.test.tsx(프리셋에서_직접입력으로_바꾸면_이전_프리셋값이_남지_않는다) |
| TC-FE-358 | 자유입력칸은 20자를 넘겨 입력할 수 없다 (신규) | `직접 입력` 선택 | 30자 타이핑 | 입력값 길이 **20**(`maxLength`). 컬럼이 `VARCHAR(20)` 이라 초과분은 저장되지 않으며 BE 도 400 으로 막는다 — 화면에서 먼저 막아 400 왕복을 줄이되 **신뢰 경계는 여전히 서버**다 | component | High | TusMetaFieldsets.tsx(VrfcEvntTypeField) · tusUpload.test.tsx(직접입력칸은_20자를_넘겨_입력할_수_없다) |

## H-22. 라벨링 '메타' 탭 시계열 메타 패널 — 전문 1개 편집 + 일치도 읽기전용 + 레거시 병기 (SC-005) — 2026-08-06 신설

> 외부 위탁이 `describe`(구간 배열) → `verify`(단일 서술 + 일치도)로 바뀌면서 BE 조회 응답이 `items`(편집 가능) / `technicalMeta`(`video.*`) / **`readOnlyMeta`(일치도 등)** 3목록으로 분리됐다(→ [v2-wiki 09 §9.4-1](../v2-wiki/09-vlm-timeseries.md)). 이 절은 그 계약을 소비하는 **화면 동작**을 고정한다. BE 분류·`PUT` 400 거부는 D 클러스터 소관이며 이번 회차에 무변경이다.
> **분류의 진실원은 BE** 다 — FE 는 접두 문자열로 재해석하지 않는다. 다만 **편집 슬롯 판정**만 FE 화이트리스트(`EDITABLE_META_KEYS`)가 별도로 갖는데, 이는 BE 판정의 복제가 아니라 **BE 가 편집을 허용하는 레거시 구간 키를 화면에서만 더 좁히는** 것이다(R9 보존 정책).
> ★ **편집 단위 = 저장 단위(`metaKey`)** — 여러 키를 한 textarea 로 합치면 편집분을 키로 되돌릴 수 없어 **저장해도 아무것도 안 바뀌고 성공 토스트만 뜬다**(2026-08-03 실사고). verify 전환 후에도 레거시 구간이 남은 영상이 있어 원칙은 유효하다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-340 | **verify 서술이 편집 가능한 textarea 1개로 렌더** (신규) | `items=[vlm.description]` | 패널 렌더 | 라벨 "시계열 서술" textarea 1개(`disabled` 아님·`readonly` 아님), 값은 `metaVal` 원문. `maxLength=2000`(BE `META_VL` 길이 정합) | component | **Critical** | features/label/components/TimeseriesSidePanel.tsx · features/label/components/\_\_tests\_\_/TimeseriesSidePanel.test.tsx(verify_서술은_편집가능한_textarea로_렌더된다) |
| TC-FE-341 | **수정 후 저장 = 그 키로만 PUT** (신규, 조용한 무동작 회귀 가드) | 동일 | 텍스트 수정 → 저장 | `PUT` 바디 `items=[{metaKey:'vlm.description', metaVal: 편집값}]`. **원본 재전송 금지** — 편집하지 않은 슬롯은 보내지 않아 동시 수정 덮어쓰기도 없다 | component | **Critical** | TimeseriesSidePanel.tsx(dirtyItems) · TimeseriesSidePanel.test.tsx(수정_후_저장하면_해당_키로_PUT이_호출된다 · 편집슬롯_2건일때_편집한_슬롯만_전송된다_조용한무동작_아님) |
| TC-FE-342 | **일치도는 렌더되지만 편집할 수 없다** (신규) | `readOnlyMeta=[{vlm.accuracy:'0.92'}]` | 패널 렌더 | 라벨 **"일치도"** + 값 **`92%`**(0~1 → 백분율 환산). 그 행 안에 `textbox` 없음 + 편집 슬롯으로 승격되지 않음(화면 전체 `textbox` 1개). ⚠ 라벨에 `accuracy`·모델명 등 기술 용어 미노출(FE 문구 규칙) | component | **Critical** | features/auto/metaKeys.ts(readOnlyMetaLabel · formatReadOnlyMetaValue) · TimeseriesSidePanel.test.tsx(일치도는_렌더되지만_편집할_수_없다) |
| TC-FE-343 | **읽기 전용 목록의 미지 키도 깨지지 않고 렌더** (신규) | `readOnlyMeta` 에 미지 키 추가 | 패널 렌더 | 크래시 없이 값이 그대로 표시되고 편집 입력은 생기지 않는다. 라벨은 내부 네임스페이스 접두(`vlm.`)만 떼어 표시. **BE 가 fail-closed 로 읽기 전용 키를 늘려도 화면이 죽지 않아야 한다** | component | High | metaKeys.ts(readOnlyMetaLabel) · TimeseriesSidePanel.test.tsx(읽기전용_목록의_미지_키도_깨지지_않고_렌더된다) |
| TC-FE-344 | **레거시 구간행은 읽기 전용으로 병기되고 사라지지 않는다** (신규) | `items=[0-10, 10-20]`(구 describe 산출물) | 패널 렌더 | 두 행 모두 키·값이 표시되고(**삭제·숨김 금지** = 보존 확정) 그 안에 `textbox` 없음 + 해당 키의 편집 슬롯 미생성. 화면의 입력은 신규 등록 슬롯 1개뿐 | component | **Critical** | TimeseriesSidePanel.tsx(editableItems/legacyItems 단일 분할) · TimeseriesSidePanel.test.tsx(레거시_구간행은_읽기전용으로_병기되고_사라지지_않는다) |
| TC-FE-345 | **레거시 구간은 `start_sec` 숫자순 정렬** (신규, 10개 초과) | 구간 11건을 뒤섞어 반환 | 패널 렌더 · `vlmText` 결합 | 표시 순서가 `0-8 → 8-16 → 16-24 → … → 80-88`. **문자열 정렬이면 `10-18` 이 `8-16` 앞으로 와 시간축이 깨진다**. 구간형이 아닌 키(`manual-timeseries`·`vlm.description`)가 섞여도 깨지지 않는다(숫자 키 우선, 나머지 사전순 — 전순서) | component | High | metaKeys.ts(compareByStartSec · startSecOf) · features/auto/api.ts(toFrameMeta) · TimeseriesSidePanel.test.tsx(레거시_구간은_start_sec_숫자순으로_정렬된다) · features/auto/\_\_tests\_\_/api.test.ts(레거시_구간은_start_sec_숫자순으로_결합된다 · 구간형이_아닌_키가_섞여도_정렬이_깨지지_않는다) |
| TC-FE-346 | **읽기 전용 항목은 저장 요청에 포함되지 않는다** (신규) | `items=[vlm.description, 0-10]` + `readOnlyMeta=[vlm.accuracy]` + `technicalMeta=[video.fps]` | 서술만 수정 후 저장 | `PUT` 바디에 `vlm.description` **단 1건**. `vlm.accuracy`·`video.*`(BE 400 대상)·레거시 구간 키는 **구조적으로** 담기지 않는다(슬롯이 아닌 것은 payload 소스가 아님) | component | **Critical** | TimeseriesSidePanel.tsx(dirtyItems ← slots 만) · TimeseriesSidePanel.test.tsx(읽기전용_항목은_저장_요청에_포함되지_않는다) |
| TC-FE-347 | **편집 가능 항목이 0건이면 신규 등록 슬롯** (신규 — 메타 0건 · 레거시 전용 양쪽) | ①`items=[]` ②`items=[0-10,10-20]`(레거시뿐) | 입력 후 저장 | 둘 다 `manual-timeseries` 슬롯 1개 제공 → 저장 시 `items=[{metaKey:'manual-timeseries', ...}]`. **레거시 구간 값을 덮지 않는다**. 구 동작(메타 0건에서만 제공)의 상위집합 — 레거시 영상에서 전문 작성이 막히지 않게 한 것 | component | High | TimeseriesSidePanel.tsx(slots) · TimeseriesSidePanel.test.tsx(메타가_0건이면_신규등록_슬롯이_보인다 · 레거시_구간만_있으면_신규등록_슬롯으로_전문을_작성할_수_있다) |

> **검수 화면(SC-006 `ReviewMetaPanel`) 파급** — 같은 어댑터를 쓰므로 깨지지 않아야 하고, 이번 회차에 **일치도를 '참고 정보' 섹션으로 읽기 전용 표시**했다(검수자가 서술의 신뢰도를 판단할 근거). 라벨·값 표기는 라벨링 패널과 **같은 판정기**(`features/auto/metaKeys.ts`)를 재사용한다 — 복제하면 한쪽만 갱신돼 어긋난다(2026-08-03 프리필 상수 사고와 같은 뿌리). 회귀 가드: `features/review/components/__tests__/ReviewMetaPanel.test.tsx`(검수화면에도_일치도가_읽기전용으로_표시된다 · 일치도만_있어도_빈상태_문구가_뜨지_않는다 · readOnlyMeta_필드가_없는_응답에도_크래시하지_않는다).
>
> **기존 회귀 가드 유지** — 승인/반려 UI 미노출(2026-08-03 확정)은 그대로다: `TimeseriesSidePanel.test.tsx`(기존_승인반려_UI_미노출_가드가_유지된다 · APPROVED_검토행이_있어도_승인됨_배지가_노출되지_않는다). busy 와의 독립성도 유지(`features/label/__tests__/metaEditBusyIndependence.test.tsx` — 편집 키만 화이트리스트 키로 갱신, 검증 취지 불변).

## H-23. `features/auto/metaKeys` 판정 단일 원천 — 전용 단위 테스트 (공유 모듈) — 2026-08-06 신설

> 이 모듈은 **라벨링 편집 패널(`TimeseriesSidePanel`) · 검수 읽기 패널(`ReviewMetaPanel`) · 어댑터(`features/auto/api.ts`)** 세 곳이 공유하는 **판정 단일 원천**이다(복제 금지 — 복제하면 한쪽만 갱신돼 조용히 어긋난다).
> H-22 은 그 판정을 **화면 계약**으로 검증하고, 이 절은 **판정 자체**를 직접 고정한다. 두 축은 서로를 대체하지 않으므로 **H-22 의 간접 커버를 지우지 않는다.**
> 전 케이스 계층 = unit(`features/auto/__tests__/metaKeys.test.ts`, **25 tests**). 프로덕션 코드는 이번 회차에 무변경이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-348 | **`startSecOf` — 구간형 키에서 `start_sec` 를 숫자로 읽는다** (신규) | — | `'0-8'` · `'8-16'` · `'120-128'` · 순번형 `'0001'` · 앞뒤 공백 `'  10-18  '` | 각각 `0`·`8`·`120`·`1`·`10`. 순번형 단독 숫자도 구간 축으로 읽는다(구 describe 산출물 호환) | unit | High | features/auto/metaKeys.ts(startSecOf) · features/auto/\_\_tests\_\_/metaKeys.test.ts(구간형_키에서_start_sec를_숫자로_읽는다 · 앞뒤_공백은_무시한다) |
| TC-FE-349 | **`startSecOf` — 비구간·비정형·null 은 `null`(fail-closed)** (신규) | — | `manual-timeseries`·`vlm.description`·`vlm.accuracy`·`video.fps`·`''`·`'   '` · 음수 `'-5'`·`'-5-3'` · 소수 `'1.5-2.5'` · `'8-'`·`'8-16-24'`·`'8s-16s'` · `null`/`undefined`/숫자 | 전부 `null` 이고 **예외를 던지지 않는다**. 숫자로 오인해 정렬 앞으로 끌어오면 시간축이 깨진다 | unit | High | metaKeys.ts(startSecOf) · metaKeys.test.ts(구간형이_아닌_키는_null이다 · 음수_소수_비정형_구간은_null이다 · null_undefined_비문자열_입력에도_예외를_던지지_않는다) |
| TC-FE-350 | **`compareByStartSec` — `start_sec` 숫자 오름차순(문자열 정렬 아님)** (신규) | — | `['80-88','8-16','16-24','0-8','10-18']` 정렬 | `['0-8','8-16','10-18','16-24','80-88']`. **문자열 정렬이면 `10-18` 이 `8-16` 앞으로 와** 구간 10개 초과 시 시간축이 붕괴한다 | unit | **Critical** | metaKeys.ts(compareByStartSec) · metaKeys.test.ts(start_sec_숫자_오름차순으로_정렬한다_문자열정렬_아님) |
| TC-FE-351 | **`compareByStartSec` — 비구간 키 혼재·동률에도 전순서가 유지된다** (신규) | — | ①구간 + `manual-timeseries`/`vlm.description` 혼재 ②`start_sec` 동률 `'8-16'` vs `'8-20'` ③같은 집합을 다른 입력 순서로 2회 정렬 | ①숫자 키가 앞, 비구간 키는 뒤에 사전순 ②동률이면 키 사전순으로 결정(반대칭 · 자기 자신은 `0`) ③두 결과가 **동일**(전순서라 실행마다 흔들리지 않는다) | unit | High | metaKeys.ts(compareByStartSec) · metaKeys.test.ts(구간형이_아닌_키가_섞여도_숫자키가_앞_나머지는_사전순_뒤 · start_sec가_동률이면_키_사전순으로_결정한다_전순서 · 비구간_키끼리는_사전순이고_반대칭이다 · 숫자키와_비구간키의_비교는_방향이_대칭이다 · 정렬_결과가_입력_순서에_의존하지_않는다) |
| TC-FE-352 | **`isEditableMetaKey` — 화이트리스트 정확일치만 통과(유사 키 과대허용 없음)** (신규) | — | ①`vlm.description`·`manual-timeseries` ②`vlm.accuracy`·`video.fps`·`0-8`·`0001` ③유사 키 `vlm.description2`·`vlm.descriptio`·` vlm.description`·`vlm.description `·`VLM.DESCRIPTION`·`manual-timeseries-2` ④`null`/`undefined`/`''`/숫자 | ①만 `true`, ②③④는 전부 `false`. `EDITABLE_META_KEYS` 자체도 그 2키로 고정 — **접두 파싱이 아니라 정확 일치**라야 새 자동 생성 키가 편집 슬롯·저장 payload 로 새지 않는다(BE `MetaService` fail-closed 와 대칭) | unit | **Critical** | metaKeys.ts(isEditableMetaKey · EDITABLE_META_KEYS) · metaKeys.test.ts(화이트리스트_2키만_편집_가능하다 · 일치도_기술메타_레거시구간은_편집_대상이_아니다 · 유사_키를_과대허용하지_않고_정확일치만_통과한다 · null_undefined_비문자열은_편집_대상이_아니다) |
| TC-FE-353 | **`readOnlyMetaLabel` — 알려진 키 매핑 + 미지 키 폴백** (신규) | — | `vlm.accuracy` · `vlm.confidence` · `vlm.foo.bar` · `someUnknownKey` · `video.fps` · `vlm.` · `vlm.   ` | `일치도` / `confidence` / `foo.bar` / 원문 / 원문 / `vlm.`(접두를 떼면 비므로 원문 폴백) / `vlm.   `. **BE 가 fail-closed 로 `readOnlyMeta` 를 늘려도 라벨이 비지 않고**, 값·의미를 재해석하지 않는다(모델명·기술 용어 미노출) | unit | High | metaKeys.ts(readOnlyMetaLabel) · metaKeys.test.ts(일치도_키는_한국어_라벨로_매핑한다 · 미지의_vlm_키는_내부_네임스페이스_접두만_떼고_보여준다 · 접두가_없는_미지_키는_원문_그대로_보여준다 · 접두를_떼면_비는_키는_원문_키로_폴백한다) |
| TC-FE-354 | **`formatReadOnlyMetaValue` — 일치도는 백분율 환산(경계 0·1 포함)** (신규) | 키 = `vlm.accuracy` | `'0.923'` · `'0.92'` · `'0.9235'` · `'0'` · `'1'` · `' 1 '` | `92.3%` · `92%` · `92.4%`(소수 1자리 반올림) · `0%` · `100%` · `100%`(공백 무시). 0~1 문자열을 그대로 보이면 사람이 신뢰도로 읽기 어렵다 | unit | High | metaKeys.ts(formatReadOnlyMetaValue) · metaKeys.test.ts(일치도는_0~1을_백분율로_환산한다_소수1자리 · 경계값_0과_1도_환산한다) |
| TC-FE-355 | **`formatReadOnlyMetaValue` — 숫자 아님·범위 밖·비일치도 키는 원문 보존** (신규) | — | ①키=`vlm.accuracy` + `'N/A'`·`''`·`'   '`·`'NaN'`·`'Infinity'` ②키=`vlm.accuracy` + `'1.5'`·`'-0.1'` ③키=`vlm.confidence`+`'0.5'` · `video.fps`+`'30'` | 전부 **원문 그대로**. ①②는 **값을 지어내지 않는다**(범위 밖을 환산하면 `150%` 같은 잘못된 사실을 단언하게 된다) ③환산 규칙은 **일치도 전용**이라 다른 읽기 전용 키의 값을 왜곡하지 않는다 | unit | **Critical** | metaKeys.ts(formatReadOnlyMetaValue) · metaKeys.test.ts(숫자가_아니면_지어내지_않고_원문을_그대로_보여준다 · 0~1_범위를_벗어나면_원문을_그대로_보여준다 · 일치도가_아닌_키는_숫자여도_변환하지_않는다) |

> **`editableMetaLabel` 도 함께 고정한다** — `vlm.description` → `시계열 서술`, `manual-timeseries` → `시계열 메타`(화면 문구에 모델명·기술 용어 미노출). 라벨 문구가 바뀌면 H-22 의 화면 케이스(TC-FE-340)와 함께 갱신한다. 회귀 가드: `metaKeys.test.ts(서술_전문은_시계열_서술_나머지는_시계열_메타)`.

## H-24. 저장 진입점 일원화 — 헤더 `[저장]` 제거 + 도구바 잘림 해소 (SCR-LABEL-001) — 2026-08-06 신설

> 배경: 헤더 우측 `[저장]` 과 좌측 도구바 저장 아이콘이 **같은 `handleSave` 를 부르는 중복 진입점**이었다. 헤더를 없애 도구바 + `Ctrl+S` 로 일원화했다.
> ⚠ **2026-08-07 후속** — 저장은 좌측 도구바에서 **캔버스 상단 옵션바**로 다시 이관됐다([H-25](#h-25-캔버스-상단-옵션바-신설--편집-액션프레임-이동-이관-screen-005--2026-08-07-신설)). 이 절의 **판정(헤더에 저장 버튼을 두지 않는다)은 그대로 유효**하고 바뀐 것은 "그럼 어디에 있나"뿐이다. 아래 케이스 중 TC-FE-361·362·363 의 검증 지점이 옮겨졌다.
> ★**순서가 본질이다** — 도구바에는 짧은 뷰포트에서 `저장` 이 잘리는 **알려진 미해결 결함(TC-FE-306)** 이 있었으므로, 그걸 먼저 고치지 않고 헤더를 지우면 **알려진 결함 위에 유일한 대안을 없애는** 셈이 된다. 스크롤 계약·툴팁 portal 케이스는 TC-FE-306 참조.
> ⚠ 잠금(`LOCKED_FOR_REDEIDENT`)은 툴바가 자체 판정하는 편집 차단(busy)과 **다른 축**이라 호출부 전달이 없으면 조용히 샌다 — 그 배선을 TC-FE-361 이 고정한다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-359 | ★헤더에 저장 버튼이 없다 — 중복 진입점 제거 (신규) | 라벨링 헤더 렌더 | `dirty=true` | `label-header-save` **미존재** + 이름 `저장` 버튼 미존재. 되살리면 같은 중복이 재발한다. ⚠ **2026-08-07 정정** — 저장 진입점은 이제 **캔버스 상단 옵션바 + `Ctrl+S`** 다(구 서술 "도구바 + `Ctrl+S`" 는 폐기 — 저장이 좌측 도구바에서 옵션바로 **다시 이관**됐다, [H-25](#h-25-캔버스-상단-옵션바-신설--편집-액션프레임-이동-이관-screen-005--2026-08-07-신설)). 헤더에 두지 않는다는 이 케이스의 **판정 자체는 불변** | component | High | features/label/components/LabelHeader.tsx(`LabelHeader`) · features/label/components/\_\_tests\_\_/LabelHeader.test.tsx(`★헤더에는_저장_버튼이_없다_중복_진입점_제거_회귀가드`) |
| TC-FE-360 | ★`저장 중...` 진행 표시가 상태 문구로 이관됐다 (신규) | `saving=true`, `dirty=true` | 헤더 렌더 | `label-save-status` 가 `저장 중...` + `aria-live="polite"`. **진행 > 미저장 > 저장됨** 우선순위라 `● 편집 중` 이 아니다 — 저장은 항상 dirty 에서 시작하므로 dirty 를 먼저 보면 진행 표시가 **영영 뜨지 않는다**. 구 헤더 버튼 라벨(`saving ? '저장 중...' : '저장'`)이 담당하던 유일한 텍스트 피드백이라 제거하면 증발한다 | component | High | features/label/components/LabelHeader.tsx(`LabelHeader`) · features/label/components/\_\_tests\_\_/LabelHeader.test.tsx(`저장중이면_저장_중_문구를_보여준다_버튼_제거로_증발했던_진행_피드백` · `진행중_표시는_편집중보다_우선한다`) |
| TC-FE-361 | ★영상 잠금이 저장 버튼에 전달된다 — 축이 달라 자체 판정 불가 (정정 2026-08-07) | `locked` 전달(잠금 `LOCKED_FOR_REDEIDENT`) | **캔버스 상단 옵션바** 렌더 | `label-toolbar-save` **비활성** + 클릭해도 `onRequestSave` **미호출**. 전달이 누락되면 잠긴 영상에서 저장이 활성으로 보인다(`handleSave` 가 토스트로 막지만 "눌리는데 아무 일도 없는 화면"이 된다). ⚠ **구 서술 폐기** — 검증 지점이 좌측 도구바 → **옵션바**로 이동했고, 함께 단언하던 "`선택` 등 도구 버튼은 활성"은 **옵션바에 도구 버튼이 없어 성립하지 않는다**(도구 버튼 무회귀는 TC-FE-366 이 담당). ⚠ 잠금은 **삭제·실행취소 축은 막지 않는다** — 이관 전 동작 그대로이며 이번 변경이 차단 축을 늘리지 않았다 | component | High | features/label/components/CanvasOptionBar.tsx(`CanvasOptionBar`) · features/label/components/SaveCommitButton.tsx(`SaveCommitButton`) · pages/label/LabelingPage.tsx(`LabelingPage`) · features/label/\_\_tests\_\_/CanvasOptionBar.test.tsx(`★잠금이_전달되면_저장이_비활성이다_편집_차단과_다른_축이다` · `잠금이_없으면_저장은_활성이다`) |
| TC-FE-362 | 저장 진행 중에는 저장 버튼이 스피너·`aria-busy` + 중복 클릭 차단 (정정 2026-08-07) | `saving=true` | 저장 버튼 클릭 | `aria-busy="true"` + `disabled` + `onRequestSave` **미호출**. 버튼 라벨이 사라진 자리를 스피너가 대신한다(헤더 상태 문구 TC-FE-360 과 짝). ⚠ 검증 지점이 좌측 도구바 → **캔버스 상단 옵션바**로 이동(기대결과 불변) | component | Med | features/label/components/CanvasOptionBar.tsx(`CanvasOptionBar`) · features/label/components/SaveCommitButton.tsx(`SaveCommitButton`) · features/label/\_\_tests\_\_/CanvasOptionBar.test.tsx(`저장중이면_스피너와_aria_busy_로_알리고_중복_클릭을_막는다`) |
| TC-FE-363 | 도구바 스크롤 컨테이너의 **마지막 버튼**이 스크롤로 도달 가능하다 (정정 2026-08-07) | 도구바 렌더 | 버튼 목록 조회 | 목록의 **마지막 버튼**이 `label-toolbar-scroll` 내부에 있다 — 가장 먼저 잘리던 자리라 위치 자체를 고정한다(잘림 결함 TC-FE-306 의 재발 지점). ⚠ **구 기대값 "그 마지막 버튼은 `label-toolbar-save`" 는 폐기** — 저장이 캔버스 상단 옵션바로 이관돼 도구바에 없다(TC-FE-365). 스크롤 계약 자체는 **도구 버튼이 넘칠 때를 위해 유지**한다 | component | Med | features/label/components/ToolBar.tsx(`ToolBar` · `TOOLBAR_SCROLL_CLASS`) · features/label/\_\_tests\_\_/ToolBarScrollContract.test.tsx(`마지막_버튼은_스크롤_컨테이너_안에_있어_넘쳐도_스크롤로_도달한다`) |

> **폐기(행 삭제 아님)** — `LabelHeader_액션_primary_토큰`(구 `features/label/components/__tests__/LabelHeader.test.tsx`): **헤더 저장 버튼이 KRDS `primary` 토큰을 쓰는지** 검증하던 테스트다. 대상 버튼이 사라져 성립하지 않으므로 폐기했다. 공통 `Button` 의 토큰 계약은 `CommonControlFontSize.test.tsx` 가 계속 덮으므로 커버리지 손실이 아니다. 카탈로그에는 이 테스트에 대응하는 TC 행이 없었다(그래서 정정 대상 행도 없다).
>
> **저장 동작 자체는 무변경** — `handleSave`(중복 제출 가드 → 편집 차단 → 잠금 토스트 → PUT → 409 충돌 다이얼로그)와 `Ctrl+S` 바인딩(`edit.save`)은 손대지 않았다. 기존 저장 케이스(TC-FE-066·TC-FE-315·H-3 절)는 **셀렉터만** `label-header-save` → `label-toolbar-save` 로 바뀌었고 기대결과는 그대로다.
>
> ⚠ **2026-08-07 재확인** — 옵션바 이관 후에도 `handleSave`·`Ctrl+S` 는 **다시 한 번 무변경**이고, 저장 버튼의 `data-testid` 도 **`label-toolbar-save` 그대로**다(컴포넌트 `SaveCommitButton` 이 통째로 옮겨갔을 뿐). 따라서 위 저장 케이스들의 셀렉터·기대결과는 **또 바뀌지 않는다**.

---

## H-25. 캔버스 상단 옵션바 신설 — 편집 액션·프레임 이동 이관 (SCREEN-005) — 2026-08-07 신설

> 배경: 사양(SCREEN-005 §캔버스 상단 옵션바 / SCREEN-029 동일 배치)이 **"삭제·실행취소·다시실행·저장은 좌측 도구바가 아니라 캔버스 상단 옵션바에 둔다"** 로 확정됐다.
> 좌측 도구바(`ToolBar`)에는 **그리기 도구와 보기 조작만** 남고, 편집 액션 4종 + 프레임 이동 컨트롤이 **캔버스 상단 옵션바**(`CanvasOptionBar`)로 모인다.
>
> **★양쪽에 두지 않는 것이 핵심이다** — 같은 액션이 두 곳에 있으면 잠금·진행중 판정이 **한쪽만 갱신돼 조용히 열린 구멍**이 생긴다(직전 회차의 헤더/도구바 중복 저장이 정확히 그 사례였다 → [H-24](#h-24-저장-진입점-일원화--헤더-저장-제거--도구바-잘림-해소-scr-label-001--2026-08-06-신설)).
> **★프레임 위치 표시·이동도 단일 표면이다** — 헤더(라벨링·검수 양쪽)에서 프레임 위치 표시를 없애고 이동 컨트롤이 단독 담당한다.
>
> **개명** — 좌측 도구바 컴포넌트가 `DarkToolbar` → **`ToolBar`** 로 바뀌었다(다크 폐지 이후 이름만 남아 있던 잔재). 테스트 파일도 `DarkToolbar*.test.tsx` → `ToolBar*.test.tsx`. **동작 변경 없음** — 이 개명 때문에 정정된 기존 행은 TC-FE-275·TC-FE-306([F](F-portal.md) TC-PORTAL-072·072c 포함)이며 전부 근거 파일명만 바뀌었다.
>
> **바뀌지 않은 것** — 단축키(`Del`/`Ctrl+S`/`Ctrl+Z`/`Ctrl+Shift+Z`)·미저장 변경 배지·잠금 시 저장 비활성·`handleSave` 절차·저장 버튼 `data-testid`(`label-toolbar-save`).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-364 | **★편집 액션 4종이 모두 옵션바 안에 있다 (신설)** | 라벨링 화면 렌더 | `canvas-option-bar` 내부 조회 | **삭제 · 실행 취소 · 다시 실행 · 저장** 4개가 전부 이 컨테이너 안에 있다. 하나라도 밖에 있으면 잠금·진행중 판정이 갈린다 | component | High | features/label/components/CanvasOptionBar.tsx(`CanvasOptionBar`) · features/label/\_\_tests\_\_/CanvasOptionBar.test.tsx(`★삭제_실행취소_다시실행_저장_4개_액션이_모두_옵션바_안에_있다`) |
| TC-FE-365 | **★좌측 도구바에는 그 4종이 없다 — 중복 금지 (신설)** | 라벨링 화면 렌더 | `role=toolbar[name="라벨링 도구"]` 내부 조회 | `저장`·`삭제`·`실행 취소`·`다시 실행` **전부 부재** + `label-toolbar-save` 가 도구바 안에 **없다**. 되살리면 같은 중복 결함이 재발한다 | component | High | features/label/components/ToolBar.tsx(`ToolBar`) · features/label/\_\_tests\_\_/ToolBarScrollContract.test.tsx(`★저장_삭제_실행취소_다시실행_버튼이_도구바에_없다`) |
| TC-FE-366 | 보기 조작·그리기 도구는 도구바에 **그대로 남는다** (신설 · 이관 범위 경계) | 라벨링 화면 렌더 | 도구바 조회 | `화면 맞춤`·`선택`·`AI 탐지` 등이 **여전히 도구바에** 있다. 이관 대상은 **편집 액션 4종뿐**이며 보기 조작·그리기 도구까지 옮기면 사양 위반이다 | component | High | features/label/components/ToolBar.tsx(`ToolBar`) · features/label/\_\_tests\_\_/ToolBarScrollContract.test.tsx(`보기_조작(화면_맞춤)과_그리기_도구는_도구바에_그대로_남는다`) |
| TC-FE-367 | 프레임 이동 컨트롤이 옵션바 중앙에 있고 **위치 슬라이더는 두지 않는다** (신설) | 라벨링 화면 렌더 | 옵션바 조회 | `FrameNavigator` 가 옵션바 안에 있고 **슬라이더는 렌더되지 않는다**(`showSlider={false}`) — 하단 프레임 타임라인이 이미 스크럽을 제공하므로 같은 기능을 두 표면에 두지 않는다 | component | High | features/label/components/CanvasOptionBar.tsx(`CanvasOptionBar`) · features/label/\_\_tests\_\_/CanvasOptionBar.test.tsx(`프레임_이동_컨트롤이_옵션바_중앙에_있다` · `하단_타임라인이_스크럽을_담당하므로_옵션바에는_위치_슬라이더를_두지_않는다`) |
| TC-FE-368 | **★헤더에 프레임 위치 표시가 없다 — 옵션바와 중복 금지 (신설)** | 라벨링 헤더 렌더 | 헤더 조회 | 구 `Frame N / 총 M` 표시 **부재**(`frame-counter` 폐지). 위치 표시·이동은 `FrameNavigator` 가 **단독** 담당한다 — 두 곳에 두면 하나가 갱신되지 않아도 화면상 정상으로 보인다. ⚠ 기존 테스트가 쓰던 `frame-counter` 셀렉터는 `frame-number-input` 의 **값**으로 대체된다 | component | High | features/label/components/LabelHeader.tsx(`LabelHeader`) · features/label/components/\_\_tests\_\_/LabelHeader.test.tsx(`★헤더에는_프레임_위치_표시가_없다_옵션바와_중복_금지`) |
| TC-FE-369 | **★이동 진입점 3종이 단일 콜백으로 수렴한다 (신설)** | `FrameNavigator` 렌더 | ①처음/이전/다음/마지막 버튼 ②번호 입력 + Enter ③슬라이더 드래그 | 셋 다 **`onRequestGoTo` 하나만** 호출한다. 진입점마다 미저장 가드를 따로 붙이면 **한 곳이 샌다** — 미저장 상태에서 슬라이더를 주르륵 끌어도 확인 모달은 **한 번만** 뜨고 목적지는 최종 위치로 갱신된다 | component | High | features/label/components/FrameNavigator.tsx(`FrameNavigator`) · features/label/components/\_\_tests\_\_/FrameNavigator.test.tsx(`각_버튼이_단일_콜백_onRequestGoTo_로_수렴한다` · `번호_입력_후_Enter_로_이동한다_같은_단일_콜백을_부른다`) |
| TC-FE-370 | 이동 버튼 경계 — 양 끝에서 비활성 (신설) | 첫 프레임 / 마지막 프레임 | 버튼 상태 | 첫 프레임에서 `처음`·`이전` 비활성 / 마지막에서 `다음`·`마지막` 비활성 | component | Med | features/label/components/FrameNavigator.tsx(`FrameNavigator`) · features/label/components/\_\_tests\_\_/FrameNavigator.test.tsx(`첫_프레임에서는_처음_이전이_비활성이다` · `마지막_프레임에서는_다음_마지막이_비활성이다`) |
| TC-FE-371 | 번호 직접 입력의 오류 경계 — 이동하지 않고 **현재 번호로 되돌린다** (신설) | `frameCount=N` | ①범위 밖(0·N+1) ②빈 값 ③숫자 아님 ④확정 없이 포커스 이탈 | 네 경우 모두 `onRequestGoTo` **미호출** + 입력값이 현재 번호로 복원. 화면 표시는 1부터이고 전체 개수를 함께 보여준다 | component | Med | features/label/components/FrameNavigator.tsx(`FrameNavigator`) · features/label/components/\_\_tests\_\_/FrameNavigator.test.tsx(`범위_밖이면_이동하지_않고_현재_번호로_되돌린다` · `빈_값이면_이동하지_않고_현재_번호로_되돌린다` · `숫자가_아니면_이동하지_않는다` · `포커스를_잃으면_확정하지_않고_되돌린다`) |
| TC-FE-372 | **★슬라이더는 솎아내되 놓는 순간 마지막 위치를 반드시 반영한다 (신설 · release flush)** | 슬라이더 노출 화면 | 드래그(연속 변경) → 놓기 | 드래그 중에는 `THROTTLE_MS` 간격으로만 요청하고, **놓는 순간 대기 중이던 마지막 위치를 flush** 한다. 솎아내기만 하고 마무리하지 않으면 **마지막 위치가 누락**된다. 대기분이 없으면 놓아도 중복 요청하지 않는다 | component | High | features/label/components/FrameNavigator.tsx(`FrameNavigator` · `THROTTLE_MS`) · features/label/components/\_\_tests\_\_/FrameNavigator.test.tsx(`★끄는_동안_요청을_솎아내고_놓는_순간_마지막_위치를_반드시_반영한다` · `솎아내기_간격을_넘기면_드래그_중에도_다시_보낸다` · `대기분이_없으면_놓아도_중복_요청하지_않는다`) |
| TC-FE-373 | 옵션바 저장은 **화면이 소유한 저장 절차에 위임**한다 (신설) | 옵션바 렌더 | 저장 클릭 / `Ctrl+S` | 둘 다 같은 절차를 타고 **낙관적 동시성 토큰을 함께 전송**한다. 옵션바는 저장을 직접 수행하지 않고 `onRequestSave` 로 위임한다 — 낙관적 동시성 토큰·409 충돌 안내·포털/내부 라우팅을 화면이 소유하기 때문이며, 컴포넌트가 재구현하면 두 진입점이 갈린다 | component | High | features/label/components/CanvasOptionBar.tsx(`CanvasOptionBar`) · features/label/\_\_tests\_\_/CanvasOptionBarSaveWiring.test.tsx(`★옵션바_저장_버튼이_낙관적_동시성_토큰을_함께_보낸다` · `Ctrl_S_단축키도_같은_저장_절차를_탄다_이관_후에도_동일_동작`) |
| TC-FE-374 | 삭제는 선택 객체를 지우고 **선택이 없으면 조용히 no-op** (신설) | ①객체 선택됨 ②선택 없음 | 삭제 클릭 | ①해당 객체 제거 ②아무 일도 일어나지 않음(**비활성화하지 않는다**). ⚠ 선택 여부로 비활성 축을 늘리면 편집 차단 해제 검증(TC-FE-375)의 "모든 조작이 즉시 복구된다"가 선택 상태에 따라 갈려 **회귀 가드가 의미를 잃는다** — 이관 전 좌측 도구바 동작을 그대로 보존한 것이다 | component | Med | features/label/components/CanvasOptionBar.tsx(`handleDelete`) · features/label/\_\_tests\_\_/CanvasOptionBar.test.tsx(`선택_객체를_지운다` · `선택이_없으면_조용히_no_op_이다`) |
| TC-FE-375 | busy 중 옵션바 전체 비활성 + 해제 시 즉시 복구 (신설 · 회귀 가드) | 장시간 작업(busy) 진행 중 → 해제 | 옵션바 조회 | busy 중 **저장·삭제·실행취소·다시실행·프레임 이동이 모두 비활성**이고, 풀리면 **즉시 전부 복구**된다. 편집 차단 판정은 `useIsEditBlocked` 단일 원천이며 옵션바가 자체 판정을 만들지 않는다 | component | High | features/label/components/CanvasOptionBar.tsx(`CanvasOptionBar`) · features/label/\_\_tests\_\_/CanvasOptionBar.test.tsx(`busy_중에는_저장_삭제_실행취소_프레임이동이_모두_비활성이다` · `busy_가_풀리면_즉시_복구된다`) · features/label/\_\_tests\_\_/editBlocking.test.tsx |
| TC-FE-376 | 실행취소·다시실행 가능 여부는 **스택 길이로 판정**한다 (신설) | 스택 비어 있음 → 편집 1회 → 실행취소 | 버튼 상태·클릭 | 스택이 비면 둘 다 비활성 · 편집하면 실행취소 활성 · 클릭하면 **실제로 작업본이 되돌아가고 다시 적용**된다(활성 여부만이 아니라 결과까지 확인) | component | Med | features/label/components/CanvasOptionBar.tsx(`CanvasOptionBar`) · features/label/components/UndoRedoToolbar.tsx(`UndoRedoToolbar`) · features/label/\_\_tests\_\_/CanvasOptionBar.test.tsx(`스택이_비면_둘_다_비활성이고_편집하면_실행취소가_활성이_된다` · `실행취소_다시실행_클릭이_실제로_작업본을_되돌리고_다시_적용한다`) |
| TC-A11Y-016 | 옵션바·이동 컨트롤 접근성 (신설) | 옵션바 렌더 | 정적 검사 | 옵션바 컨테이너 `role="toolbar"` + `aria-label` · 저장 버튼에 `aria-label` · 이동 컨트롤 컨테이너 `role="group"` + 번호 입력에 이름 · **이동 버튼은 KRDS 최소 터치 타깃 44px 보장** · 편집 액션 툴팁의 단축키 표기가 **키맵(`SHORTCUT_KEYMAP`)에서 파생**돼 하드코딩 오표기가 없다 | a11y | Med | features/label/components/CanvasOptionBar.tsx(`CanvasOptionBar`) · features/label/components/FrameNavigator.tsx(`FrameNavigator`) · features/label/\_\_tests\_\_/CanvasOptionBar.test.tsx(`접근성_컨테이너는_role_toolbar_이고_저장_버튼에_aria_label_이_있다` · `편집_액션_툴팁의_단축키가_키맵과_일치한다`) · features/label/components/\_\_tests\_\_/FrameNavigator.test.tsx(`컨테이너는_role_group_이고_번호_입력에_이름이_있다` · `이동_버튼은_KRDS_최소_터치_타깃_44px_를_보장한다`) |
| TC-FE-377 | 포털 라벨링(`/portal/label/:id`)도 **같은 배치** (신설 · 경계) | PORTAL 채널 | 화면 렌더 | `PortalLabelingPage` 는 `LabelingPage` 를 **그대로 재사용**하므로 옵션바 배치가 자동으로 동일하다(별도 배선 없음 — 복제하면 한쪽만 갱신되는 사고가 난다). ⚠ **포털 업로드 라벨링(`/portal/uploads/:uldSn/label`)은 별개 화면**이라 이 옵션바를 쓰지 않는다(자체 저장 버튼 유지) — 두 화면을 혼동하지 말 것(TC-FE-275 경계와 동일) | component | Med | pages/portal/PortalLabelingPage.tsx(`PortalLabelingPage`) · pages/label/LabelingPage.tsx(`LabelingPage`) · pages/portal/PortalUploadLabelingPage.tsx(`PortalUploadLabelingPage` — 미사용) |

> ⚠ **jsdom 은 레이아웃을 계산하지 않는다**(`getBoundingClientRect` 전부 0). 배치를 폭·높이·좌표로 단언하면 **항상 통과하는 거짓 가드**가 되므로, 이 절은 전부 **구조 계약**(어느 컨테이너 안에 있는가 · 어떤 컨트롤이 없는가 · 어떤 콜백을 부르는가)으로 고정한다. H-24 의 도구바 잘림 케이스가 같은 이유로 구조 계약만 고정했다.
>
> ⚠ **[2026-08-08 정정]** — 위 문단의 "검수 화면 상단 바는 배선돼 있지 않다"는 **폐기**한다. `ReviewPage` 가 이제 같은 `FrameNavigator` 를 헤더 바로 아래 별도 상단바에 재사용해 배선한다(5b-3). 회귀 가드는 [H-8a](#h-8a-검수-화면--헤더-액션-일원화--상단-프레임-이동-바--재검토-필요-재승인-screen-019--2026-08-08-신설) `TC-FE-380~385` 참조 — 이 절(H-25)의 케이스는 라벨링 캔버스 전용으로 그대로 유지되며 중복 등재하지 않는다.

## H-26. 공통 입력·조합형 컴포넌트 전환 — Field·Card·DataTable·Select·Checkbox·Textarea — 2026-08-08 신설

> UI 카탈로그(`ui_component`) 사양이 shadcn 계열 **조합형(compound)** 패턴을 요구했고, 구현은 **내장형(all-in-one prop)** 이었다(D3 의 조합형↔내장형 축, 별도 라운드로 분리됐던 것이 이번에 반영됐다). 6개 공통 컴포넌트가 조합형으로 전환됐다 — **구 prop 기반 API 는 전량 폐기**되고 새 의존성 3종(`@radix-ui/react-checkbox`·`@radix-ui/react-select`·`@tanstack/react-table`, 전부 MIT)이 도입됐다. 이 카탈로그에는 전환 이전에 이 6개 컴포넌트를 지목한 전용 행이 [TC-FE-184](#h-12-공통-컴포넌트에러상태)(DataTable, 위에서 정정) 하나뿐이었다 — 나머지는 **신설**이다. 호출부 전환(Field 89곳/33파일 · Card 7파일 · Select 15곳/16파일)은 이 회차의 범위이며 개별 화면 행을 다시 쓰지 않는다(조합 계약 자체가 회귀 가드).

### Field — 라벨·설명·오류 연결 조합형

> `FieldLabel`/`FieldDescription`/`FieldError`/`FieldSet`/`FieldLegend`/`FieldGroup`/`FieldTitle`/`FieldSeparator` 8종. `Select`·`Textarea`·`Checkbox` 도 같은 컨텍스트로 연결된다(3-way 접근성 배선 단일화).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-390 | `FieldLabel` 은 `htmlFor` 로 입력과 연결된다 (신설) | `Field` 컨텍스트 안에 라벨+입력 | 렌더 | 라벨 클릭 시 입력에 포커스(암묵적 `id` 생성 + `htmlFor` 매칭). 호출부가 `id` 를 직접 명시해도 연결이 유지된다 | a11y | High | components/common/Field.tsx · components/common/__tests__/Field.test.tsx(`FieldLabel_은_htmlFor_로_입력과_연결된다` · `호출부가_입력에_id_를_명시해도_라벨_연결이_유지된다`) |
| TC-FE-391 | `FieldError` 는 `role=alert` 이고 입력과 `aria-describedby` 로 연결된다 (신설) | 오류 메시지 존재 | 렌더 | 스크린리더가 즉시 읽고, 입력에 포커스했을 때 오류가 함께 낭독된다. 오류가 없으면 **빈 alert 로 남지 않는다**(렌더 자체를 생략) | a11y | High | components/common/Field.tsx · components/common/__tests__/Field.test.tsx(`FieldError_는_role_alert_이며_입력과_aria_describedby_로_연결된다` · `FieldError_는_내용이_없으면_빈_alert_로_남지_않는다`) |
| TC-FE-392 | 오류와 설명이 함께 있으면 오류를 가리킨다 — 우선순위 (신설) | `FieldDescription`+`FieldError` 동시 존재 | 렌더 | `aria-describedby` 가 오류 쪽을 가리킨다(설명은 시각적으로 남되 낭독 우선순위는 오류가 위) | a11y | Med | components/common/Field.tsx · components/common/__tests__/Field.test.tsx(`오류와_설명이_함께_있으면_오류를_가리킨다`) |
| TC-FE-393 | `FieldError` 는 오류 2건 이상이면 목록으로, 중복 메시지는 1회만 (신설 · 경계) | 동일/상이 오류 메시지 다건 | 렌더 | 서로 다른 메시지 2건 이상 → 목록 렌더 / 같은 메시지 반복 → 1회만 | component | Med | components/common/__tests__/Field.test.tsx(`FieldError_는_서로_다른_메시지가_2건_이상이면_목록으로_렌더한다` · `FieldError_는_같은_메시지가_반복되면_한_번만_보여준다`) |
| TC-FE-394 | 호출부가 명시한 `aria-invalid` 가 Field 판정보다 우선한다 (신설) | 호출부가 `aria-invalid` 직접 전달 | 렌더 | Field 컨텍스트의 자동 판정을 덮어쓴다 — 호출부가 더 정확한 상태를 알 때의 탈출구 | component | Low | components/common/__tests__/Field.test.tsx(`호출부가_명시한_aria_invalid_는_Field_판정보다_우선한다`) |
| TC-FE-395 | `Field` 밖에서 `FieldLabel` 등을 단독으로 써도 크래시 없이 동작한다 (신설 · 하위호환) | `Field` 컨텍스트 없음 | 렌더 | 컨텍스트 없이도 렌더(레거시 호출부의 점진 전환을 막지 않는다) | component | Low | components/common/__tests__/Field.test.tsx(`Field_밖에서_쓰면_컨텍스트_없이도_동작한다`) |

### Card — 서브컴포넌트 6종 조합형

> `CardHeader`/`CardTitle`/`CardDescription`/`CardAction`/`CardContent`/`CardFooter`. 구 콘텐츠 prop 4종 + `padding` prop 은 **전량 제거**(children 조합으로 대체).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-396 | `Card` 는 children 을 그대로 렌더한다 — 콘텐츠 prop 없음 (신설 · Breaking) | — | 렌더 | 구 `title`/`description`/`footer`/`padding` prop 은 **존재하지 않는다**(타입 에러) — 서브컴포넌트 조합만 허용 | component | High | components/common/Card.tsx · components/common/__tests__/Card.test.tsx(`children_을_그대로_렌더한다_콘텐츠_prop_없음`) |
| TC-FE-397 | `CardAction` 유무로 헤더 그리드가 1열↔2열 자동 전환 (신설) | `CardHeader` 안에 `CardAction` 유무 | 렌더 | 없으면 1열 grid, 있으면 `1fr auto` 2열로 전환(액션 버튼 자리 확보) | component | Med | components/common/__tests__/Card.test.tsx(`CardHeader_는_기본적으로_1열_grid_이다` · `CardAction_이_있으면_CardHeader_가_1fr_auto_2열_그리드로_전환된다`) |
| TC-FE-398 | `CardFooter` 유무로 루트 하단 패딩이 동적으로 조정된다 (신설) | `CardFooter` 마운트/언마운트 | 렌더 | 있으면 루트 `pb-0`(푸터가 자체 패딩을 가짐), 없으면 해제 — 언마운트 시에도 즉시 반영 | component | Med | components/common/__tests__/Card.test.tsx(`CardFooter_가_없으면_Card_루트에_pb-0_이_없다` · `CardFooter_가_있으면_Card_루트의_하단_패딩이_0으로_줄어든다` · `CardFooter_가_언마운트되면_Card_루트의_pb-0_이_해제된다`) |
| TC-FE-399 | `CardTitle` 은 시맨틱 헤딩이 아니다 (신설 · a11y 경계) | 렌더 | DOM 검사 | `<h*>` 가 아닌 일반 텍스트 요소 — 사양이 제목과 컨테이너를 랜드마크로 연결하지 않기로 한 결정과 짝(이름 없는 랜드마크 회피) | a11y | Med | components/common/__tests__/Card.test.tsx(`CardTitle_은_시맨틱_헤딩이_아니라_일반_텍스트_요소이다`) |
| TC-FE-400 | 헤더 없이 `CardContent` 만 두는 자유 조합이 가능하다 (신설) | `CardHeader` 생략 | 렌더 | 정상 렌더(서브컴포넌트는 전부 선택적 조합) | component | Low | components/common/__tests__/Card.test.tsx(`헤더_없이_CardContent만_두는_자유_조합이_가능하다`) |

### DataTable — `@tanstack/react-table` 조합형 (TC-FE-184 보강)

> 페이지네이션·로딩 스켈레톤·선택 컬럼은 **내장 제거** — 호출부(검수목록·사용자관리)가 조합한다. 전체선택 체크박스는 `Checkbox` 3상태로 전환되어 네이티브 `el.indeterminate` 직접 조작이 **0건**이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-401 | 빈 `data` 는 colSpan 전체 셀에 `EmptyState` 를 렌더한다 (신설) | `data=[]` | 렌더 | 단순 텍스트 한 줄이 아니라 **전체 컬럼 폭을 가로지르는 EmptyState**(컬럼 수 변경에도 레이아웃이 안 흔들림) — `emptyMessage` 는 그 안의 문구가 된다 | component | Med | components/common/DataTable.tsx · components/common/__tests__/DataTable.test.tsx(`DataTable_빈_data_EmptyState_렌더` · `DataTable_emptyMessage_커스텀_문구_렌더`) |
| TC-FE-402 | 체크박스 클릭은 행 클릭(`onRowClick`)으로 전파되지 않는다 (신설 · 회귀 가드) | `onRowClick` 지정 + 선택 컬럼 존재 | 체크박스 클릭 | `onRowClick` 미호출(이벤트 전파 차단) — 선택과 행 이동이 같은 클릭에 섞이지 않는다 | component | High | components/common/__tests__/DataTable.test.tsx(`DataTable_체크박스_클릭은_행_이동으로_전파되지_않는다`) |
| TC-FE-403 | 전체선택은 부분선택 시 `Checkbox` 3상태(`indeterminate`)로 렌더된다 (신설) | 일부 행만 선택 | 렌더 | 전체선택 체크박스가 `aria-checked="mixed"`. 네이티브 `el.indeterminate` 직접 조작이 아니라 `Checkbox` 컴포넌트의 3상태 prop 경로다 | component | High | components/common/__tests__/DataTable.test.tsx(`DataTable_전체선택_체크박스는_부분선택시_indeterminate(mixed)이다` · `DataTable_전체선택_클릭시_전체_토글된다`) |
| TC-FE-404 | 선택 컬럼 체크박스도 44px 히트영역을 보장한다 (신설 · KRDS) | 선택 컬럼 존재 | 렌더 | 클릭 가능 영역 44×44px(시각 크기와 별개) | a11y | Med | components/common/__tests__/DataTable.test.tsx(`DataTable_선택_체크박스_44px_히트영역(KRDS)`) |
| TC-FE-405 | 컬럼 `meta.ariaSort` 로 서버 정렬 축을 직접 노출할 수 있다 (신설 · 접근성 확장점) | 서버 사이드 정렬 컬럼 | 렌더 | 로컬 `sortable` 과 별개로 `columns[].meta.ariaSort` 값을 그대로 `aria-sort` 에 반영 — 서버 정렬 목록에서 방향이 시각 아이콘으로만 전달되지 않는다 | a11y | Med | components/common/DataTable.tsx · components/common/__tests__/DataTable.test.tsx(`DataTable_컬럼_meta_ariaSort로_서버_정렬_축을_직접_노출할_수_있다`) |
| TC-FE-406 | `DataTableSkeleton` 은 별도 컴포넌트로 분리되고 기본 행수는 5 (신설) | 로딩 중 | 호출부가 `DataTableSkeleton` 조합 | 컬럼 수만큼 열 렌더 + 기본 `rowCount=5`(로딩 스켈레톤도 내장에서 호출부 조합으로 이동) | component | Low | components/common/__tests__/DataTable.test.tsx(`DataTableSkeleton_행_열_수만큼_렌더` · `DataTableSkeleton_기본_rowCount는_5`) |

### Select — Radix 조합형 + 빈 문자열 센티넬

> `SelectTrigger`/`SelectContent`/`SelectItem`/`SelectGroup`/`SelectLabel`/`SelectSeparator`. Radix 는 빈 문자열을 placeholder 예약값으로 써 `SelectItem value=""` 를 런타임 throw 하므로, `Select` 루트가 children 을 재귀 스캔해 **실제로 빈 옵션이 있을 때만** 내부 센티넬(`EMPTY_VALUE_SENTINEL`)로 양방향 매핑한다. URL 쿼리·상태·서버 요청에는 `''` 가 그대로 유지된다(북마크·뒤로가기 불변).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-407 | 빈 문자열 값 옵션도 선택 가능하다 — placeholder 와 구분 (신설) | `SelectItem value=""` 존재("전체" 등) | 선택 | `onValueChange('')` 로 호출자에게는 **원래의 빈 문자열**이 전달된다(내부 센티넬은 `Select` 밖으로 새지 않는다) | component | **Critical** | components/common/Select.tsx(`EMPTY_VALUE_SENTINEL`) · components/common/__tests__/Select.test.tsx(`빈_문자열_값_옵션을_선택_가능하다_placeholder와_구분된다`) |
| TC-FE-408 | 미선택 상태는 `placeholder` + `data-placeholder` 로 구분된다 (신설) | 값 미선택 | 렌더 | placeholder 텍스트 노출 + `data-placeholder` 속성 — "빈 문자열을 고름"과 "아직 아무것도 안 고름"이 시각·마크업 모두에서 구분된다 | component | High | components/common/__tests__/Select.test.tsx(`미선택_상태에서는_placeholder_가_보이고_data-placeholder_속성이_붙는다`) |
| TC-FE-409 | `SelectTrigger` 기본 크기는 44px KRDS 터치 타깃을 보장한다 (신설 · ★사양 자기모순 해소) | `size` 미지정(default) | 렌더 | 44px 이상(구 shadcn 기본값 32px 에서 이탈 — DS-001 의 44px 하한 규칙과 사양의 `h-8` 지정이 자기모순이었던 것을 44px 쪽으로 정정, `Input`·`Button` 과 동일 높이라 폼 한 줄 정렬이 성립) | a11y | **Critical** | components/common/Select.tsx · components/common/__tests__/Select.test.tsx(`SelectTrigger_는_기본(default) 크기에서_44px_KRDS_최소_터치_타깃을_보장한다`) |
| TC-FE-410 | `SelectTrigger size="sm"` 은 44px 미만을 허용하는 밀집 배치 전용 예외다 (신설 · 경계) | `size="sm"` | 렌더 | 44px 미만 허용 — 단 **단독 터치 타깃으로 쓰지 않는다**는 전제(밀집 작업 패널에서만, `Button.sm` 과 동일 성격의 예외) | a11y | Med | components/common/__tests__/Select.test.tsx(`SelectTrigger_size_sm_은_44px_미만을_허용하는_밀집_배치_예외다`) |
| TC-FE-411 | disabled `SelectItem` 은 선택되지 않는다 (신설) | 옵션 일부 disabled | 클릭 | `onValueChange` 미호출 | component | Med | components/common/__tests__/Select.test.tsx(`disabled_SelectItem_은_선택되지_않는다`) |
| TC-FE-412 | `Select` 는 라벨·오류 문구를 스스로 렌더하지 않는다 (신설 · 책임 경계) | 렌더 | — | `FieldLabel`/`FieldError` 조합에 위임 — `Select` 자체는 트리거·콘텐츠만 담당(Field 패턴과 일관) | component | Low | components/common/__tests__/Select.test.tsx(`Select_는_라벨_오류_문구를_스스로_렌더하지_않는다`) |
| TC-FE-413 | ★선택 가능한 빈 옵션을 조건부로 렌더하지 않는다 (신설 · 잔여 위험 명시) | 빈 값 옵션이 특정 조건에서만 나타나는 드롭다운 | 조건 토글 | 조건부 렌더 시 센티넬 적용 여부가 렌더마다 뒤집혀 매핑이 깨질 수 있다(스캔은 마운트 시점 children 기준) — 실측 1곳(`DevAutolabelTestPage` 로딩 안내 옵션)은 그 Select 가 `disabled` 라 실제 피해는 없으나, **앞으로 새 Select 에서 이 패턴을 피할 것**(정적 옵션만 조건부 렌더에 넣지 않는다) | component | Low | components/common/Select.tsx(children 재귀 스캔은 마운트 시점 1회) |

### Checkbox — Radix 3상태(indeterminate)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-414 | 3상태 전이와 `mixed` 노출 (신설) | `checked` 를 `false`→`true`→`'indeterminate'` 순으로 제어 | 렌더 | `'indeterminate'` 일 때 `aria-checked="mixed"`(불리언 2상태가 아니다) | component | High | components/common/Checkbox.tsx · components/common/__tests__/Checkbox.test.tsx(`Checkbox_3상태_전이와_mixed_노출`) |
| TC-FE-415 | 44px 히트영역은 유지하되 시각 사각형은 20px 로 커진다 (신설 · 시각 변화 인지) | 렌더 | 크기 측정 | 클릭 영역 44px 불변(TC-FE-... 기존 계약) + 시각 체크박스는 **16→20px**(사양의 "정사각 5" 준수) — 눈에 보이는 크기 변화이나 히트영역과는 별개 축 | component | Low | components/common/__tests__/Checkbox.test.tsx(`Checkbox_클릭영역_44px` · `Checkbox_시각_사각형은_히트영역보다_작다`) |
| TC-FE-416 | 라벨 없을 때도 44px 폭이 보장된다 (신설 · 경계) | `label` 미지정 | 렌더 | 44px 폭 유지(단독 체크박스도 터치 타깃 하한을 잃지 않는다) | a11y | Med | components/common/__tests__/Checkbox.test.tsx(`Checkbox_label_없을때_44px_폭_가드`) |
| TC-FE-417 | `disabled` 면 클릭해도 토글되지 않는다 (신설) | `disabled` | 클릭 | `onCheckedChange` 미호출 | component | Med | components/common/__tests__/Checkbox.test.tsx(`Checkbox_disabled_면_클릭해도_토글되지_않는다`) |
| TC-FE-418 | Space 키로 토글된다 (신설 · a11y) | 포커스 후 Space | 키 입력 | 토글 발생(마우스 전용이 아니다) | a11y | Med | components/common/__tests__/Checkbox.test.tsx(`Checkbox_Space_키로_토글된다`) |

### Textarea — 자동확장

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-419 | `rows` 기본값을 두지 않는다 — `field-sizing:content` 로 자동확장 (신설 · Breaking) | `rows` 미지정 | 렌더 | 구 고정 `rows` 기본값 폐지 + `[field-sizing:content]` 클래스로 내용에 따라 높이가 자동 늘어난다. 호출부가 `rows` 를 명시하면 **그대로 전달**된다(강제 덮어쓰기 아님) | component | Med | components/common/Textarea.tsx · components/common/__tests__/Textarea.test.tsx(`Textarea_rows_기본값을_두지_않는다` · `Textarea_호출부가_지정한_rows_는_그대로_전달된다`) |
| TC-FE-420 | 자동확장 선언과 상한(`max-h-[50vh]`)이 함께 적용된다 (신설 · 사양 일부 양보) | 긴 입력 | 렌더 | `field-sizing:content` 로 늘어나되 `max-h-[50vh]` 상한 클래스도 함께 존재 — 공용 `Modal` 이 자체 스크롤 컨테이너가 아니라, 상한 없이 무한 확장하면 하단 버튼에 도달할 수 없어진다(사양의 "스크롤 없이 전체가 보이도록"을 일부 양보) | component | Med | components/common/__tests__/Textarea.test.tsx(`Textarea_자동확장_선언과_상한이_함께_적용된다`) |
| TC-FE-421 | 최소 높이 44px (신설 · KRDS) | 렌더 | — | `min-h-11`(44px) 이상 | a11y | Low | components/common/__tests__/Textarea.test.tsx(`Textarea_min_44px`) |
| TC-FE-422 | 호출부 `className` 이 기본 최소높이를 이긴다 (신설 · 확장점) | 호출부가 다른 `min-h-*` 전달 | 렌더 | 호출부 지정이 우선 적용(`twMerge` 규약) | component | Low | components/common/__tests__/Textarea.test.tsx(`Textarea_호출부_className_이_기본_최소높이를_이긴다`) |
| TC-FE-423 | ⚠ Safari 는 `field-sizing` 미지원(2026-08 기준) — 그 브라우저에서는 자동확장이 동작하지 않는다 (신설 · 인지된 한계) | Safari 렌더 | — | `min-h` 고정 크기로 폴백(이전과 같은 크기이나 자동확장 없음) — 결함이 아니라 브라우저 지원 격차 | component | Low | components/common/Textarea.tsx(`[field-sizing:content]`) |

## H-27. KRDS 팔레트 전 스케일 전환 — 구 `#0F4C97` 계열 폐지 (DS-001) — 2026-08-08 신설

> **구 정책 → 폐기**: 테스트베드 FE 가 구 팔레트 `#0F4C97` 계열을 써 왔다(정본 `DS-001` 은 KRDS `#256EF4`, 납품 FE 는 이미 정합돼 있었다). 이 회차로 `tailwind.config.js` 의 `primary` 전 스케일이 KRDS 정본으로 교체됐고, 저장소 전체에 구 값(`#0F4C97`)이 **0건** 남았다. 라벨·차트 색상은 이 변경과 **무관**(memory `krds-design-system-applied` — 별도 판정축, 함께 바꾸지 않는다).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-424 | `primary` 토큰이 KRDS 정본 `#256EF4` 로 교체된다 (신설 · 회귀 가드) | `tailwind.config.js` | 빌드 설정 조회 | `colors.primary.DEFAULT`·`colors.primary['500']` 모두 `#256EF4` | unit | High | tailwind.config.js · src/test/designTokens.test.ts(`primary가_KRDS_정본_블루_256EF4로_교체된다`) |
| TC-FE-425 | 구 팔레트 값은 저장소 전체에서 0건이다 (신설 · 회귀 가드) | 전 소스 | 리터럴 grep | `#0F4C97`/`#0f4c97` 매칭 0건 — 되살아나면 이 가드가 먼저 깨진다 | unit | Med | tailwind.config.js(리터럴 부재 자체가 증거) |
| TC-FE-426 | `bg-info/10` 위 텍스트는 새 팔레트에서도 AA(4.5:1) 를 만족한다 (신설 · 대비 교정) | info 배지 텍스트 | 대비 계산 | 정본 주조색이 구 값보다 밝아 대비 여유가 줄어드는 지점(정보 배지)은 `text-info`→`text-info-700` 로 교정해 4.05:1→6.72:1 확보. 아이콘·대형 볼드는 3:1 기준이라 미변경(근거는 계산으로 제시, 새 색 정의 0건 — 기존 스케일 단계로 해결) | unit | High | src/test/wcagContrast.ts · src/test/contrastGuard.test.ts(`대비 회귀 가드 — bg-info/10 위 텍스트는 AA(4.5:1) 이상`) |
| TC-FE-427 | 대비 가드는 클래스 문자열이 아니라 `tailwind.config.js` 실값으로 재계산한다 (신설 · 가짜 가드 방지) | 임의 색 토큰 변경 | 가드 실행 | 소스에서 실제 클래스 토큰을 추출해 설정 파일의 실제 hex 값으로 WCAG 대비를 재계산 — 클래스 이름만 세는 가드는 색상값이 바뀌어도 통과하는 거짓 안전감을 준다 | unit | Med | src/test/wcagContrast.ts · src/test/contrastGuard.test.ts |

## H-28. 검수 상세 우측 패널 — 객체/메타/이슈 3탭 구조 (SCREEN-019) — 2026-08-08 신설

> **사양 정합** — 우측 패널은 **객체(기본) / 메타 / 이슈** 3개 탭으로 전환된다. 구 구현은 5개 패널을 세로로 나열하고 있었다. **기능은 하나도 줄지 않았다** — 전부 어느 탭 안에 그대로 있고, 그것을 TC-FE-439 가 전수로 결박한다(탭으로 접으면서 조용히 사라지는 것이 이 종류 변경의 실제 위험이다).
> **탭은 표시만 전환한다** — 객체 선택(`selectedLabelId`)·검수 의견·pending 이슈는 `useReviewSelectionStore` 가 소유하므로 탭을 옮겨도 값이 유지되고 캔버스와의 양방향 동기화가 끊기지 않는다(TC-FE-438).
> **접근성은 WAI-ARIA Tabs 패턴** — `aria-selected`·`aria-controls`↔`aria-labelledby` 상호 연결 + roving tabIndex + ←/→/Home/End. roving 이 없으면 키보드 사용자가 패널 본문에 닿기까지 탭을 3번 지나야 한다.
> **승인·반려는 헤더 단독**([H-8a](#h-8a-검수-화면--헤더-액션-일원화--상단-프레임-이동-바--재검토-필요-재승인-screen-019--2026-08-08-신설) 와 같은 축) — 탭 안에 두면 활성 조건·진행 표시 판정이 갈린다(TC-FE-440).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-428 | 우측 패널이 객체/메타/이슈 3개 탭으로 구성된다 (신설) | 검수 상세 렌더 | `review-side-tablist` 조회 | `role="tablist"` 안에 `role="tab"` 3개가 **사양 순서(객체 → 메타 → 이슈)** 로 존재 | component | High | features/review/components/ReviewSidePanelTabs.tsx(REVIEW_SIDE_TABS) · features/review/__tests__/ReviewPageSideTabs.test.tsx(`우측_패널이_객체_메타_이슈_3개_탭으로_구성된다`) |
| TC-FE-429 | 기본 선택 탭은 '객체'이며 그 탭패널만 렌더된다 (신설) | 진입 직후 | 렌더 | `review-tab-objects` 가 `aria-selected=true`, `review-panel-objects` 만 존재하고 메타·이슈 패널은 **DOM 에 없다**(숨김이 아니라 미렌더) | component | High | pages/ReviewPage.tsx(sideTab) · features/review/__tests__/ReviewPageSideTabs.test.tsx(`기본_선택_탭은_객체이며_객체_탭패널만_렌더된다`) |
| TC-FE-430 | 탭과 탭패널이 `aria-controls`↔`aria-labelledby` 로 상호 연결된다 (신설 · a11y) | 렌더 | 스크린리더 조회 | 탭 버튼 `aria-controls` = 패널 `id`, 패널 `aria-labelledby` = 탭 `id`(양방향) | a11y | High | features/review/components/ReviewSidePanelTabs.tsx(reviewTabId · reviewPanelId) · features/review/__tests__/ReviewPageSideTabs.test.tsx(`탭과_탭패널이_aria_controls_labelledby_로_상호_연결된다`) |
| TC-FE-431 | 선택된 탭만 `tabIndex=0`, 나머지는 `-1` (신설 · a11y) | 렌더 | 탭 버튼 `tabIndex` 조회 | roving tabIndex — Tab 한 번으로 탭 목록을 통과하고 목록 안에서는 방향키로 옮긴다 | a11y | High | features/review/components/ReviewSidePanelTabs.tsx · features/review/__tests__/ReviewPageSideTabs.test.tsx(`선택된_탭만_tabIndex_0_이고_나머지는_마이너스1_이다`) |
| TC-FE-432 | '메타' 탭 클릭 시 메타 패널이 뜨고 객체 패널은 사라진다 (신설) | 기본 상태 | `review-tab-meta` 클릭 | `review-panel-meta` 렌더 + `review-panel-objects` 미존재(한 번에 한 패널) | component | High | pages/ReviewPage.tsx · features/review/__tests__/ReviewPageSideTabs.test.tsx(`메타_탭_클릭하면_메타_패널이_뜨고_객체_패널은_사라진다`) |
| TC-FE-433 | '이슈' 탭 클릭 시 문의 스레드 패널이 뜬다 (신설) | 기본 상태 | `review-tab-issues` 클릭 | `review-panel-issues` 안에 `issue-thread-panel` 렌더 | component | High | pages/ReviewPage.tsx · features/review/__tests__/ReviewPageSideTabs.test.tsx(`이슈_탭_클릭하면_문의_스레드_패널이_뜬다`) |
| TC-FE-434 | ←/→ 방향키로 탭을 옮기고 포커스가 따라간다 (신설 · a11y) | 탭에 포커스 | `{ArrowRight}` → `{ArrowLeft}` | 선택 탭이 바뀌고 **그 탭에 포커스가 이동**한다. 마우스 클릭·초기 렌더에서는 포커스를 옮기지 않는다(사용자가 만지지 않은 요소로 튀지 않게) | a11y | High | features/review/components/ReviewSidePanelTabs.tsx(handleKeyDown · moveFocusTo) · features/review/__tests__/ReviewPageSideTabs.test.tsx(`방향키_오른쪽_왼쪽으로_탭을_이동하고_포커스가_따라간다`) |
| TC-FE-435 | 방향키 이동은 양끝에서 순환한다 (신설 · 경계) | 첫 탭 / 마지막 탭 | 첫 탭에서 `{ArrowLeft}` · 마지막 탭에서 `{ArrowRight}` | 각각 마지막 탭('이슈') · 첫 탭('객체') 로 감싼다 | a11y | Med | features/review/components/ReviewSidePanelTabs.tsx(handleKeyDown) · features/review/__tests__/ReviewPageSideTabs.test.tsx(`방향키_이동은_양끝에서_순환한다`) |
| TC-FE-436 | Home/End 로 첫 탭·마지막 탭으로 이동한다 (신설 · a11y) | 탭에 포커스 | `{End}` → `{Home}` | 각각 '이슈' · '객체' 선택 | a11y | Med | features/review/components/ReviewSidePanelTabs.tsx(handleKeyDown) · features/review/__tests__/ReviewPageSideTabs.test.tsx(`Home_End_키로_첫_탭_마지막_탭으로_이동한다`) |
| TC-FE-437 | 객체 트리 행은 listbox/option 접근성 계약을 유지한다 (신설 · a11y · 회귀) | 객체 탭 | 트리 행 조회 | 행이 `role="option"` + `aria-selected` 를 갖고 `role="listbox"` 조상 안에 있다 — 탭으로 감싸면서 이 계약이 깨지지 않았음을 고정 | a11y | Med | features/review/components/ObjectListPanel.tsx · features/review/__tests__/ReviewPageSideTabs.test.tsx(`객체_트리_행은_listbox_option_접근성_계약을_유지한다`) |
| TC-FE-438 | 탭을 옮겼다 돌아와도 객체 선택이 유지된다 (신설 · **핵심**) | 객체 트리에서 라벨 선택 | 메타 탭 → 객체 탭 복귀 | 스토어 `selectedLabelId` 보존 + 트리 행 `aria-selected=true` + 속성 패널 복원. 선택을 컴포넌트 지역 상태로 옮기면 여기서 깨진다 | component | **Critical** | pages/ReviewPage.tsx(useReviewSelectionStore) · features/review/__tests__/ReviewPageSideTabs.test.tsx(`탭을_옮겼다_돌아와도_객체_선택이_유지된다`) |
| TC-FE-439 | 구 5개 패널의 기능이 탭 어딘가에 전부 남아 있다 (신설 · **감소 방지**) | 세 탭 순회 | 각 탭패널 내부 조회 | 객체 탭 = 객체 목록·속성·검수 메모 / 메타 탭 = 메타 패널 / 이슈 탭 = 문의 스레드. **한 건이라도 사라지면 실패** — 탭 재구성에서 실제로 위험한 것은 배치가 아니라 조용한 소실이다 | component | **Critical** | pages/ReviewPage.tsx · features/review/__tests__/ReviewPageSideTabs.test.tsx(`구_5개_패널의_기능이_탭_어딘가에_전부_남아_있다`) |
| TC-FE-440 | 승인·반려는 헤더 단독이며 탭 안에는 없다 (신설 · 회귀 가드) | 렌더 | 헤더·aside 각각 조회 | 승인·반려 트리거가 `review-header` 안에만 있고 `review-aside` 안에는 없다. 구 하단 `review-action-bar` 도 부재([H-8a](#h-8a-검수-화면--헤더-액션-일원화--상단-프레임-이동-바--재검토-필요-재승인-screen-019--2026-08-08-신설) TC-FE-378 과 짝) | component | High | pages/ReviewPage.tsx · features/review/__tests__/ReviewPageSideTabs.test.tsx(`승인_반려_액션은_헤더_단독이며_탭_안에는_없다`) |
| TC-FE-441 | 미해소 문의가 있으면 '이슈' 탭에 건수 배지가 뜬다 (신설) | 미해소(`OPEN`) 문의 1건 | 렌더 | `review-tab-issues-badge` 에 건수 텍스트 + `aria-label="미해소 문의 1건"`. 배지는 **탭이 가린 신호를 되살리는 알림**이고 건수 자체는 이슈 탭 안이 계속 소유한다 | component | High | features/review/components/ReviewSidePanelTabs.tsx(unresolvedInquiries) · features/review/__tests__/ReviewPageSideTabs.test.tsx(`미해소_문의가_있으면_이슈_탭에_건수_배지가_뜬다`) |

## H-29. AI 정밀도 기본값 — 전용 조회 경로 전환 (SC-005) — 2026-08-08 신설

> **구 동작 2단계 → 폐기**: ①라벨링 진입 시 검수자 전용 `GET /v1/manage/configs` 를 조건 없이 호출해 **WORKER 마다 403 이 쌓였다** ②`enabled: isReviewer` 로 호출을 막았더니 **작업자가 저장된 기본값을 아예 받지 못했다**(문제를 옮긴 것이지 푼 것이 아니다). 지금은 검수자·작업자 공통 읽기 전용 경로 **`GET /v1/ai-defaults`** 를 역할 게이트 없이 호출한다.
> **가드는 두 축을 함께 센다** — 관리 영역 호출 **0건** + 전용 경로 호출 **1건 이상**. 한쪽만 세면 "작업자 403 은 없는데 값도 못 받는" 구 처방으로 조용히 되돌아간다.
> BE 계약·인가·응답 키 집합 케이스는 [A-8](A-auth-common.md) `TC-AIDEF-001~012` 소관.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-442 | 작업자 진입 시 관리 영역을 부르지 않고 전용 경로로 기본값을 받는다 (신설 · **핵심**) | WORKER 세션, 라벨링 화면 진입 | 프레임 목록 로드 완료까지 대기 | `/ai-defaults` 호출 1건 이상 + `/manage/configs` 호출 **0건**. 에러 표시 유무가 아니라 **요청 수**로 단언한다(실패를 삼키면 콘솔만 조용해지고 왕복은 남는다) | component | **Critical** | pages/label/LabelingPage.tsx(useAiDefaults) · features/label/__tests__/LabelingPageConfigsRoleGate.test.tsx(`WORKER_진입시_검수자전용_관리설정을_호출하지_않고_전용경로로_기본값을_받는다`) |
| TC-FE-443 | 검수자 진입 시에도 같은 전용 경로를 쓴다 — 관리 설정 조회로 되돌아가지 않는다 (신설 · 회귀 가드) | REVIEWER 세션, 라벨링 화면 진입 | 동일 | `/ai-defaults` 호출 1건 이상 + `/manage/configs` **0건**. 검수자라고 설정 전량 + 마지막 수정자를 담은 응답을 받을 이유가 없다(구 역할 분기 부활 차단) | component | High | frontend/src/features/sysconfig/hooks/useAiDefaults.ts · features/label/__tests__/LabelingPageConfigsRoleGate.test.tsx(`REVIEWER_진입시에도_같은_전용경로를_쓴다_관리설정_조회로_되돌아가지_않는다`) |
| TC-FE-444 | `getAiDefaults` 는 관리 영역 밖의 `/ai-defaults` 를 호출한다 (신설) | axios mock | `getAiDefaults()` | 요청 URL 이 정확히 `/ai-defaults` 이고 `ApiResponse` 래퍼에서 `data` 두 값을 꺼낸다 | component | High | features/sysconfig/api.ts(getAiDefaults) · features/sysconfig/__tests__/api.test.ts(`getAiDefaults_는_관리영역_밖의_ai_defaults_를_호출한다`) |
| TC-FE-445 | 생략된 항목은 `undefined` 로 남겨 화면 폴백을 허용한다 (신설 · 경계) | 응답 `data` 에 `simplifyTolerance` 만 존재 | `getAiDefaults()` | `confThreshold` 는 `undefined`(0 등으로 뭉개지 않는다), `simplifyTolerance` 는 값 그대로. 슬라이더가 컴포넌트 상수로 폴백할 수 있어야 한다 | component | High | features/sysconfig/types.ts(AiDefaults) · features/sysconfig/__tests__/api.test.ts(`getAiDefaults_는_생략된_항목을_undefined_로_남겨_화면_폴백을_허용한다`) |

## H-30. 통계 테이블 — 작업자별 라벨/진행/검수/오토라벨/반려율 6컬럼

> 확정 사양 구조: 작업자 / 라벨(labeled) / 진행(inProgress) / 검수(reviewed) / 오토라벨(autoLabelRate) / 반려율(100 - approvalRate).
>
> **픽셀 원칙 (Critical)**: 각 행의 수치가 **전부 서로 달라야** 렌더 필드를 단언으로 구분할 수 있다.
> 같은 값을 쓰면 두 컬럼이 같은 필드를 그리는 버그(예: 진행·라벨 컬럼이 둘 다 reviewed 를 그렸던 구 결함)가 그대로 통과한다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-446 | 표는_사양의_6컬럼을_순서대로_렌더한다 | `WorkerStatsTable` 렌더 | rows prop 3건 + 렌더 | 헤더 6개: 작업자·라벨·진행·검수·오토라벨·반려율이 **이 순서대로** 나타난다 | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(표는_사양의_6컬럼을_순서대로_렌더한다) |
| TC-FE-447 | 각_컬럼은_사양이_정한_필드값을_렌더한다 | 동일 | 작업자A 행 조회 | 라벨=labeled(10) · 진행=inProgress(7) · 검수=reviewed(30) · 오토라벨=autoLabelRate%(12.0) · 반려율=100-approvalRate%(10.0) 를 각 셀에서 읽는다 | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(각_컬럼은_사양이_정한_필드값을_렌더한다) |
| TC-FE-448 | 진행_컬럼과_검수_컬럼은_서로_다른_값을_렌더한다 | 동일 | 작업자B 행 조회(inProgress=3·reviewed=20) | `cells[2]`(진행)≠`cells[3]`(검수). 구 버그: 둘 다 reviewed 를 그려 같은 숫자가 나란히 나타났다 | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(진행_컬럼과_검수_컬럼은_서로_다른_값을_렌더한다) |
| TC-FE-449 | 라벨_헤더_클릭시_labeled_기준으로_재정렬된다 | 동일 + 기본 정렬은 labeled 내림차순 | 라벨 헤더(`name: /^라벨/`) 클릭 | 행 순서가 labeled 오름차순으로 뒤집힌다: A(10)<C(30)<B(50) | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(라벨_헤더_클릭시_labeled_기준으로_재정렬된다) |
| TC-FE-450 | 진행_헤더_클릭시_inProgress_기준으로_재정렬된다 | 동일 | 진행 헤더(`name: /^진행/`) 클릭 | 행 순서가 inProgress 내림차순으로 정렬: C(9)>A(7)>B(3) | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(진행_헤더_클릭시_inProgress_기준으로_재정렬된다) |
| TC-FE-451 | 검수_헤더_클릭시_reviewed_기준으로_재정렬된다 | 동일 | 검수 헤더(`name: /^검수/`) 클릭 | 행 순서가 reviewed 내림차순으로 정렬: A(30)>B(20)>C(10) | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(검수_헤더_클릭시_reviewed_기준으로_재정렬된다) |
| TC-FE-452 | 오토라벨_헤더_클릭시_autoLabelRate_기준으로_재정렬된다 | 동일 | 오토라벨 헤더(`name: /^오토라벨/`) 클릭 | 행 순서가 autoLabelRate 내림차순으로 정렬: B(64)>C(38)>A(12). 구 버그: 헤더에 정렬 버튼이 있었지만 비교값이 항상 0 이라 순서 변화 없음 | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(오토라벨_헤더_클릭시_autoLabelRate_기준으로_재정렬된다) |
| TC-FE-453 | 정렬_헤더는_자기가_표시하는_값을_기준으로_정렬한다 | 동일 | 라벨/진행/검수/오토라벨 각 헤더를 개별 클릭 | 각 클릭 후 헤더에 화살표(↓/↑)가 붙고, 셀에 보이는 **숫자값들**이 선택한 축 기준으로 단조 정렬돼 있다. 구 버그의 뿌리: 헤더는 A 기준 정렬을 표시하는데 셀은 B 를 그리는 축 불일치 | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(정렬_헤더는_자기가_표시하는_값을_기준으로_정렬한다) |
| TC-FE-454 | 오토라벨_값이_없으면_자리표시를_렌더한다 | `autoLabelRate: undefined` 1건 | 오토라벨 값 누락 후 렌더 | 해당 셀에 '—' 자리표시가 나타난다. 값이 실제로 없을 때만이며, 하드코딩 자리표시로 되돌아가면 위 렌더/정렬 테스트들이 먼저 깨진다 | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(오토라벨_값이_없으면_자리표시를_렌더한다) |
| TC-FE-455 | 행이_없으면_안내_문구를_렌더한다 | `rows: []` 빈 배열 | 렌더 | 테이블 대신 "작업자 통계가 없습니다" 안내 텍스트가 나타난다 | component | High | frontend/src/features/stat/__tests__/WorkerStatsTable.test.tsx(행이_없으면_안내_문구를_렌더한다) |

## H-31. 마킹 화면 — 마크 칩 개별 삭제 버튼 (UI-045 MarkingPanel) — 2026-08-08 신설

> **구 동작 → 폐기**: 개별 마킹을 지우는 수단이 **`Del`/`Backspace` 단축키뿐**이라 마우스만 쓰는 사용자에게는 삭제 경로가 **아예 없었다**(칩을 눌러 선택은 되지만 지울 방법이 없다). 칩마다 삭제 버튼을 둔다.
> **단축키는 대체가 아니라 유지다** — 추가된 것이지 옮긴 것이 아니며, 그 회귀 가드는 [TC-FE-114](#h-6-markingpage-마킹-화면)(정정)와 아래 TC-FE-458 이 함께 맡는다.
> **칩 구조가 바뀐다** — 칩 전체를 `<button>` 으로 감싸면 삭제 버튼이 버튼 안의 버튼(중첩)이 되므로, 칩은 **선택 버튼 + 삭제 버튼 두 개를 담은 그룹**이 된다([TC-FE-125](#h-6-markingpage-마킹-화면) 정정).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-456 | 칩마다 **어느 마킹인지 구분되는** 삭제 버튼이 있다 (신설 · a11y) | 수동 마킹 2건(F30·00:01 / F90·00:03) | 렌더 | 각 칩에 실제 `<button>` 이 있고 접근 가능한 이름이 `마킹 삭제 F30·00:01` / `마킹 삭제 F90·00:03` 로 **서로 구분**된다. 아이콘만 있는 버튼에 이름이 없으면 보조기술에 "버튼"으로만 읽혀 어느 것을 지우는지 알 수 없다 | a11y | High | pages/MarkingPage.tsx(`MarkingPage` 마킹 칩 렌더 블록) · pages/__tests__/MarkingPage.test.tsx(`마킹칩마다_어느마킹인지_알수있는_삭제버튼이_있다`) |
| TC-FE-457 | 삭제 버튼 클릭 시 **그 마킹만** 제거된다 (신설 · **핵심**) | 수동 마킹 3건(F30·F90·F150) | 가운데 칩의 삭제 버튼 클릭 (선택 조작 없이 곧바로) | `localMarks` 가 `[30, 150]` 로 남고 그 칩만 사라진다. **선택 → 삭제 2단계를 강요하지 않는다** — 선택 단계를 거치게 하면 마우스 사용자의 조작 수를 늘릴 뿐이다 | component | High | pages/MarkingPage.tsx(`MarkingPage`) · features/marking/store.ts(`removeMark`) · pages/__tests__/MarkingPage.test.tsx(`삭제버튼_클릭시_그_마킹만_제거된다`) |
| TC-FE-458 | 삭제 버튼 추가 후에도 `Del`·`Backspace` 삭제가 동작한다 (신설 · 회귀 가드) | 수동 마킹 2건 + 첫 칩 선택 | `Delete` → 재선택 → `Backspace` | 두 키 모두 선택된 마킹을 제거한다. **버튼이 단축키를 대체하지 않는다** — 기존 조작을 잃으면 키보드 사용자가 되레 손해를 본다 | component | High | pages/MarkingPage.tsx(`MarkingPage` 키보드 핸들러) · pages/__tests__/MarkingPage.test.tsx(`삭제버튼_추가후에도_Del단축키_삭제가_동작한다`) |
| TC-FE-459 | 삭제 버튼의 마킹 표기가 **타임라인 마커와 같은 함수**에서 나온다 (신설 · 표기 단일 원천) | 마킹 목록 + 타임라인 동시 렌더 | 접근 이름 비교 | 칩 삭제 버튼의 이름 뒷부분과 타임라인 마커의 `aria-label` 이 **같은 헬퍼**로 만들어진다 — 두 곳에서 각자 문자열을 조립하면 한쪽만 표기가 바뀌어 같은 마킹이 다른 이름으로 읽힌다 | a11y | Med | features/marking/components/MarkingTimeline.tsx(`markAriaLabel`) · pages/MarkingPage.tsx(`MarkingPage`) |

## H-32. 영상 목록 기간 필터 — 사양 컴포넌트(UI-029 DateRangePicker) 배선 (SCREEN-008) — 2026-08-08 신설

> **구 구현 → 폐기**: 화면이 라벨 + 네이티브 `type="date"` 입력을 **두 벌 직접 재구현**하고 상호 min/max 제약도 자기가 들고 있었다. 사양 컴포넌트 `DateRangePicker`(UI-029)가 있는데 **소비처가 없어 계약이 한 번도 실행으로 확인된 적이 없던** 상태였다.
> **★계약은 하나도 바뀌지 않았다** — 값 형식(`yyyy-MM-dd`) · URL 직렬화 · 상호 min/max 제약 · 빈 쪽 미전송이 전부 종전과 같다. 이 절의 절반은 **"바뀌지 않았음"을 고정하는 가드**다(교체가 조회 파라미터를 건드리면 북마크가 깨진다).
> **늘어난 것은 접근성 하나** — 두 입력이 하나의 `role="group"` 으로 묶여 "한 쌍"이라는 사실이 접근성 트리에도 드러난다.
> **한국어 병기는 끌 수 있게 됐다**(`showLocalizedDisplay`, 기본 표시). 한 줄 필터 바에서 값이 들어올 때 블록 높이가 자라 조회·초기화 버튼과 밑선이 어긋나기 때문이며, 병기는 입력값을 다시 말해 주는 보조 표시라 꺼도 값·라벨·제약 계약이 그대로다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-460 | 두 날짜 입력이 하나의 접근성 그룹으로 묶인다 (신설 · a11y) | 영상 목록 필터 렌더 | `role="group"` 조회 | 그룹이 시작일·종료일 입력을 **둘 다** 포함한다. 그룹 라벨을 주면 `group` 의 접근 이름이 된다 | a11y | High | components/common/DateRangePicker.tsx(`DateRangePicker`) · features/video/__tests__/VideoFilters.test.tsx(`사양_컴포넌트로_배선되어_두_날짜가_한_그룹으로_묶인다`) · components/common/__tests__/DateRangePicker.test.tsx(`그룹_라벨을_주면_두_입력이_이름있는_그룹으로_묶인다`) |
| TC-FE-461 | 조회 시 날짜가 **종전과 같은 형식**으로 상위에 전달된다 (신설 · **핵심 · 하위호환**) | 시작일 `2026-05-01` · 종료일 `2026-05-31` 입력 | 조회 클릭 | `onApply` 에 `{ from: '2026-05-01', to: '2026-05-31', page: 0 }` 이 실린다. 교체가 형식을 바꾸면 URL·북마크·BE 계약이 함께 깨진다 | component | **Critical** | features/video/components/VideoFilters.tsx(`VideoFilters`) · features/video/__tests__/VideoFilters.test.tsx(`조회하면_날짜가_종전과_같은_yyyy_MM_dd_형식으로_상위에_전달된다`) |
| TC-FE-462 | 한쪽만 채우면 그쪽만 필터로 전달된다 (신설 · 경계) | 시작일만 입력 | 조회 | `from='2026-05-01'` · `to=undefined`. **빈 문자열을 파라미터로 올리지 않는다** | component | High | features/video/components/VideoFilters.tsx(`VideoFilters`) · features/video/__tests__/VideoFilters.test.tsx(`한쪽만_채워도_그쪽만_필터로_전달된다`) |
| TC-FE-463 | 초기화하면 날짜 입력과 상위 필터가 함께 비워진다 (신설) | 두 날짜 입력 후 | 초기화 클릭 | 화면 입력값이 `''` 로 돌아가고 상위 필터도 함께 비워진다(화면만 비고 조회 조건이 남는 어긋난 상태가 없다) | component | High | features/video/components/VideoFilters.tsx(`VideoFilters`) · features/video/__tests__/VideoFilters.test.tsx(`초기화하면_날짜_입력이_비워진다`) |
| TC-FE-464 | 두 입력이 서로의 상한·하한이 된다 (신설 · 회귀 가드) | `from=2026-05-01` · `to=2026-05-31` | 속성 조회 | 시작일 `max='2026-05-31'` · 종료일 `min='2026-05-01'`. 사양 SCREEN-008 의 '상호 min/max 제약'은 이제 **컴포넌트가 소유**한다 | component | High | components/common/DateRangePicker.tsx(`DateRangePicker`) · components/common/__tests__/DateRangePicker.test.tsx(`두_입력은_서로의_상한_하한이_된다`) |
| TC-FE-465 | 반대편이 비어 있으면 제약 속성 자체를 붙이지 않는다 (신설 · 경계) | 값 없는 초기 상태 | 속성 조회 | 시작일에 `max` 없음 · 종료일에 `min` 없음. **빈 문자열 제약은 브라우저가 유효 제약으로 해석할 여지가 있다** | component | Med | components/common/DateRangePicker.tsx(`DateRangePicker`) · components/common/__tests__/DateRangePicker.test.tsx(`반대편이_비어있으면_제약_속성을_붙이지_않는다`) |
| TC-FE-466 | 한쪽을 바꿔도 반대편 값을 유지한 채 **한 쌍으로** 올린다 (신설 · 경계) | 한쪽만 값이 있는 상태 | 반대쪽 입력 변경 | `onChange` 가 `{from, to}` 를 함께 넘긴다 — 반대편을 흘리면 한쪽을 고칠 때마다 다른 쪽이 지워진다 | component | High | components/common/DateRangePicker.tsx(`DateRangePicker`) · components/common/__tests__/DateRangePicker.test.tsx(`시작일을_바꾸면_기존_종료일을_유지한_채_한_쌍으로_올린다` · `종료일을_바꾸면_기존_시작일을_유지한_채_한_쌍으로_올린다`) |
| TC-FE-467 | 한국어 병기는 기본 표시이고, 끄면 **표시만** 사라진다 (신설) | `showLocalizedDisplay` 기본 / `false` | 렌더 | 기본은 `2026년 5월 7일` 병기 표시 · 끄면 병기 요소가 렌더되지 않고 **입력 값·라벨·제약은 그대로**다 | component | Med | components/common/DateRangePicker.tsx(`DateRangePicker`) · components/common/__tests__/DateRangePicker.test.tsx(`한국어_병기는_기본으로_표시하고_끄면_렌더하지_않는다`) |

## H-33. 검수 화면 — 프레임 썸네일 스트립 캔버스 위 이동 + 접기/펼치기 (SCREEN-019) — 2026-08-08 신설

> **구 배치 → 폐기**: 스트립이 화면 **최하단 Footer** 였다. 사양은 *"그 아래 프레임 이동 컨트롤이 있는 상단바, **캔버스 위의** 프레임 썸네일 스트립"* 을 규정한다 → 헤더 → 프레임 이동 바 → **스트립** → 캔버스 순으로 옮겼다.
> **그리드 행은 `auto`(스트립) + `1fr`(캔버스)** — 스트립 행에 `1fr` 을 주면 썸네일 개수·스크롤 내용에 따라 캔버스 높이가 따라 흔들린다.
> **접기/펼치기는 신설이고 기본은 펼침**(사양이 스트립을 화면 구성 요소로 명시한다). **접힘은 `aria-hidden` 이 아니라 DOM 제거**다 — 감추기만 하면 보이지 않는 버튼에 Tab 포커스가 갇힌다.
> **★접기 토글은 `role="toolbar"` 바깥에 둔다** — toolbar 안에서 ←/→ 는 항목 사이 포커스 이동에 쓰이는 키인데 이 툴바는 그 키를 **프레임 이동**에 쓴다. 토글을 툴바 안에 두면 토글에 포커스가 있을 때 방향키가 포커스를 옮기는 대신 프레임을 바꾼다.
> **이동 경로는 여전히 하나다** — 상단 이동 바와 스트립이 같은 `handleGoToFrame` 으로 수렴한다([H-8a](#h-8a-검수-화면--헤더-액션-일원화--상단-프레임-이동-바--재검토-필요-재승인-screen-019--2026-08-08-신설) TC-FE-381 과 같은 축). **접힘은 표시 여부일 뿐 이동 수단을 갈라놓지 않는다.**

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-468 | 스트립이 **캔버스보다 앞선 DOM 순서**에 있다 (신설 · **핵심** · 구 Footer 배치 폐기) | 프레임 4건 검수 상세 | DOM 순서 조회 | 스트립 셀이 캔버스보다 문서 앞에 오고, **프레임 이동 바 바로 다음 칸**이 스트립이다(사이에 다른 칸이 끼지 않는다) | component | High | pages/ReviewPage.tsx(`ReviewPage` 그리드 배치) · features/review/__tests__/ReviewFrameStrip.test.tsx(`썸네일_스트립은_캔버스보다_앞선_DOM_순서에_있다`) |
| TC-FE-469 | 스트립 행은 자기 높이만 갖고 캔버스가 남은 높이를 차지한다 (신설) | 동일 | `gridTemplateRows` 조회 | `64px auto auto 1fr` — 헤더 / 이동 바 / **스트립(auto)** / 캔버스(1fr) | component | Med | pages/ReviewPage.tsx(`ReviewPage`) · features/review/__tests__/ReviewFrameStrip.test.tsx(`스트립_행은_자기_높이만_갖고_캔버스가_남은_높이를_차지한다`) |
| TC-FE-470 | 기본값은 펼침이라 진입 직후 썸네일이 보인다 (신설) | 진입 직후 | 렌더 | 토글 `aria-expanded="true"` + 썸네일 목록·첫 썸네일 존재 | component | High | features/review/components/FrameTimeline.tsx(`FrameTimeline`) · features/review/__tests__/ReviewFrameStrip.test.tsx(`기본값은_펼침이라_진입_직후_썸네일_목록이_보인다`) · features/review/__tests__/FrameTimeline.test.tsx(`FrameTimeline_기본은_펼침이고_토글에_aria_expanded_true`) |
| TC-FE-471 | 접으면 `aria-expanded=false` + 썸네일 목록이 **DOM 에서 사라진다** (신설 · a11y · **핵심**) | 펼침 상태 | 토글 클릭 | 목록·썸네일 노드가 **제거**된다(`aria-hidden` 으로 감추기만 하면 보이지 않는 버튼에 Tab 포커스가 갇힌다). 다시 누르면 되돌아온다 | a11y | High | features/review/components/FrameTimeline.tsx(`FrameTimeline`) · features/review/__tests__/FrameTimeline.test.tsx(`FrameTimeline_접으면_썸네일_목록이_DOM_에서_제거된다` · `FrameTimeline_접었다_펴면_썸네일과_현재표시가_되돌아온다`) |
| TC-FE-472 | 접혀도 진행률·카운터·토글은 남는다 (신설) | 10건 중 5번째 | 접기 | 카운터 `5 / 10` · 진행률 `aria-valuenow=5` · 토글이 그대로 보인다 — **현재 위치를 알 수 있고 다시 펼칠 수단이 유지된다** | component | High | features/review/components/FrameTimeline.tsx(`FrameTimeline`) · features/review/__tests__/FrameTimeline.test.tsx(`FrameTimeline_접혀도_진행률과_카운터는_남는다`) |
| TC-FE-473 | 접기 토글은 `role="toolbar"` **바깥**이라 방향키가 토글을 삼키지 않는다 (신설 · a11y · **핵심**) | 5건, 현재 3번째 | 토글에 포커스 → `{ArrowRight}` | 토글은 툴바의 자손이 **아니고**(썸네일만 툴바에 담긴다) 토글 포커스 상태의 방향키가 `onSelect` 를 부르지 않는다. 토글은 프레임 이동 컨트롤도 아니다 | a11y | High | features/review/components/FrameTimeline.tsx(`FrameTimeline`) · features/review/__tests__/FrameTimeline.test.tsx(`FrameTimeline_토글은_toolbar_바깥에_있어_방향키가_토글을_삼키지_않는다`) |
| TC-FE-474 | 접힌 상태에서도 toolbar 계약과 방향키 프레임 이동이 유지된다 (신설 · 경계) | 접힘 | 툴바 포커스 → `{ArrowRight}` | `role="toolbar"`·`aria-orientation="horizontal"` 유지 + `onSelect(다음 인덱스)` 호출. **툴바 컨테이너는 접혀도 남고 썸네일 버튼만 빠진다** | a11y | High | features/review/components/FrameTimeline.tsx(`FrameTimeline`) · features/review/__tests__/FrameTimeline.test.tsx(`FrameTimeline_접힌_상태에서도_toolbar_계약과_방향키_이동이_유지된다`) · features/review/__tests__/ReviewFrameStrip.test.tsx(`접기_펼치기_후에도_toolbar_접근성_계약이_유지된다`) |
| TC-FE-475 | 접었다 펴도 프레임 이동이 **같은 경로로 수렴**한다 (신설 · 양방향) | 4건 | ①접은 채 상단 이동 바의 '다음' ②펼친 뒤 4번째 썸네일 클릭 | ①스트립 카운터가 `2 / 4` ②상단 번호 입력이 `4` — 두 표면이 같은 상태를 본다. 진입점마다 범위 보정을 따로 두면 한 곳이 샌다 | component | **Critical** | pages/ReviewPage.tsx(`handleGoToFrame`) · features/review/__tests__/ReviewFrameStrip.test.tsx(`접었다_펴도_프레임_이동은_같은_경로로_수렴한다`) |
| TC-FE-476 | 프레임 0건이면 토글 없이 빈 안내만 남는다 (신설 · 경계) | `frames=[]` | 렌더 | 빈 안내(`frame-timeline-empty`)만 있고 접기 토글은 렌더되지 않는다 — 접을 대상이 없는데 컨트롤을 노출하지 않는다 | component | Med | features/review/components/FrameTimeline.tsx(`FrameTimeline` 빈 목록 분기) · features/review/__tests__/FrameTimeline.test.tsx(`FrameTimeline_빈_frames_면_접기_토글을_두지_않는다`) · features/review/__tests__/ReviewFrameStrip.test.tsx(`프레임이_0건이면_접기_토글_없이_빈_안내만_남는다`) |

## H-34. 라벨 마스터 — 속성 정의 사이드 시트 전환 (SCREEN-035) — 2026-08-08 신설

> **구 구현 → 폐기**: 속성 정의가 **테이블 아래 인라인 `<section>`** 으로 펼쳐졌다(사양 드리프트). 사양 SCREEN-035 「속성 정의 사이드 시트」대로 행의 '속성' 버튼을 트리거로 **우측에서 슬라이드로 열리는 시트**(공용 `Drawer` 재사용)로 바꿨다.
> **★행 선택 강조도 함께 폐기**된다(사양 「행 선택이나 강조 표시는 없다」) — 속성 정의가 시트로 분리돼 인라인 연동이 없으므로 선택 상태를 행 배경으로 알릴 대상이 없다. 현재 열린 대상은 **시트 제목**과 '속성' 버튼의 `aria-pressed` 가 알린다.
> **★닫기 정책이 이 전환의 위험 지점이다** — 인라인이던 시절에는 다른 곳을 눌러도 패널이 유지됐다. 시트로 바꾸면서 바깥 클릭 닫기를 켜면 **오조작 한 번에 작성 중이던 속성 정의가 사라지는 회귀**가 된다 → 백드롭 클릭으로는 닫지 않고, 닫기는 X 버튼 / ESC 라는 명시적 의도로만.
> **중첩 ESC** — 폼 모달·삭제 확인이 열려 있는 동안 시트의 ESC 를 끈다. 두 리스너가 같은 `document` 에 붙어 있어 안쪽의 `stopPropagation` 으로는 바깥 리스너를 막지 못한다(같은 노드의 리스너는 취소되지 않는다). 끄지 않으면 ESC 한 번에 폼과 시트가 함께 닫혀 입력이 사라진다.
> **`Drawer` 자체도 함께 손봤다** — 슬라이드 진입 전환(rAF 없는 환경에서는 즉시 진입시켜 **패널이 화면 밖에 남는 실패 모드**를 만들지 않는다) · `max-w-full`(좁은 뷰포트에서 고정 px 폭이 화면을 넘지 않게) · 머리말·꼬리말 `shrink-0` + 본문 `min-h-0 flex-1 overflow-y-auto`.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-477 | 행의 '속성' 버튼이 **인라인 패널이 아니라 사이드 시트**를 연다 (신설 · **핵심** · 구 인라인 폐기) | 라벨 마스터 목록 | '사람 속성 정의 관리' 클릭 | 열기 전에는 시트가 없고, 클릭하면 `dialog[name="사람 속성 정의"]` 가 열리며 **페이지 컨테이너 안에 인라인으로 들어가지 않는다**(portal). 시트의 '닫기'(X)로 닫힌다 | component | High | pages/manage/LabelMasterManagePage.tsx(`LabelMasterManagePage`) · features/label/components/LabelAttrDefPanel.tsx(`LabelAttrDefPanel`) · pages/manage/__tests__/LabelMasterManagePage.test.tsx(`속성_버튼은_인라인_패널이_아니라_사이드_시트를_연다`) |
| TC-FE-478 | 시트는 **이름을 가진 오버레이 dialog**이며 우측에 붙는다 (신설 · a11y) | 시트 열림 | 구조 조회 | 뷰포트 고정 백드롭 위에 얹힌 우측 패널(`right-0`)이고 `dialog` 의 접근 이름이 `{라벨명} 속성 정의` | a11y | High | components/common/Drawer.tsx(`Drawer`) · features/label/components/__tests__/LabelAttrDefPanel.test.tsx(`속성_패널은_인라인이_아니라_이름을_가진_오버레이_dialog_다`) |
| TC-FE-479 | 시트가 열리면 포커스가 시트 안으로 들어온다 (신설 · a11y) | 시트 열림 | `document.activeElement` 조회 | 포커스가 `body` 에 남지 않고 시트 내부로 이동한다 | a11y | High | components/common/Drawer.tsx(`Drawer`) · features/label/components/__tests__/LabelAttrDefPanel.test.tsx(`시트가_열리면_포커스가_시트_안으로_들어온다`) |
| TC-FE-480 | ESC 로 시트가 닫힌다 (신설) | 시트 열림(중첩 다이얼로그 없음) | `{Escape}` | `onClose` 호출 | component | High | features/label/components/LabelAttrDefPanel.tsx(`LabelAttrDefPanel` — `closeOnEsc`) · features/label/components/__tests__/LabelAttrDefPanel.test.tsx(`ESC_로_시트가_닫힌다`) |
| TC-FE-481 | **바깥(백드롭) 클릭으로는 닫히지 않는다** (신설 · **핵심** · 작성 중 유실 방지) | 시트 열림 | 백드롭 클릭 | `onClose` **미호출** + 시트 유지. 닫기는 X / ESC 라는 명시적 의도로만 | component | **Critical** | features/label/components/LabelAttrDefPanel.tsx(`LabelAttrDefPanel` — `closeOnBackdrop={false}`) · features/label/components/__tests__/LabelAttrDefPanel.test.tsx(`바깥_클릭으로는_닫히지_않는다_작성중_속성정의_유실_방지`) |
| TC-FE-482 | 중첩 폼이 열려 있는 동안 ESC 는 **폼만** 닫고, 폼이 닫힌 뒤 ESC 는 시트를 닫는다 (신설 · 경계) | 속성 추가 폼 열고 속성명 입력 | `{Escape}` → 다시 `{Escape}` | ①폼만 닫히고 `onClose` 미호출 ②그다음 ESC 는 시트를 닫는다(닫기 수단이 사라지지 않는다) | component | **Critical** | features/label/components/LabelAttrDefPanel.tsx(`nestedDialogOpen`) · features/label/components/__tests__/LabelAttrDefPanel.test.tsx(`추가_폼이_열려있는_동안_ESC_는_폼만_닫고_시트는_남는다`) |
| TC-FE-483 | 속성이 많아도 본문이 잘리지 않고 스크롤 영역에 담긴다 (신설 · 경계) | 속성 40건 | 구조 조회 | 시트는 `h-full` + 세로 flex(내용만큼 자라지 않음), 목록은 `flex-1` + **`min-h-0`** 인 `overflow-y-auto` 컨테이너 안. ⚠ `min-h-0` 이 빠지면 flex 아이템의 자동 최소 크기가 콘텐츠 높이라 `overflow` 가 발동하지 않고 내용이 그대로 잘린다(Modal 에서 실측된 결함과 같은 원인). jsdom 은 레이아웃을 계산하지 않으므로 **CSS 계약**으로 고정한다 | component | High | components/common/Drawer.tsx(`Drawer`) · features/label/components/__tests__/LabelAttrDefPanel.test.tsx(`속성이_많아도_시트_본문이_잘리지_않고_스크롤_영역에_담긴다`) |
| TC-FE-484 | 시트를 열어도 **행에 선택 강조가 붙지 않는다** (신설 · 구 강조 폐기) | 라벨 마스터 목록 | '속성' 클릭 전후 행 클래스 비교 | 행 클래스가 **그대로**이고 `bg-primary` 계열이 붙지 않는다. 열린 대상은 시트 제목과 '속성' 버튼의 `aria-pressed="true"` 가 알린다 | component | High | pages/manage/LabelMasterManagePage.tsx(`LabelMasterManagePage` 행 렌더) · pages/manage/__tests__/LabelMasterManagePage.test.tsx(`속성_시트를_열어도_행에_선택_강조가_붙지_않는다`) |
| TC-FE-485 | `valuesJson` XSS 가드는 **문서 전체** 기준으로 확인한다 (★정정 2026-08-08 — 가짜 통과 차단) | `valuesJson` 에 `<script>` 문자열 | 시트 렌더 | 스크립트 문자열이 **텍스트로** 렌더되고 `document.body` 어디에도 실제 `<script>` 엘리먼트가 없다. ⚠ 시트는 portal 로 `document.body` 에 붙으므로 `render` 의 `container` 만 보면 **항상 비어 있어 가짜 통과**가 된다(인라인 시절의 단언을 그대로 두면 가드가 무력화된다) | security | High | features/label/components/LabelAttrDefPanel.tsx(`parseValues`) · features/label/components/__tests__/LabelAttrDefPanel.test.tsx(`valuesJson_선택항목이_스크립트_문자열이어도_텍스트로_렌더된다`) |

## H-35. 라벨링 캔버스 — 회전(보기 전용)·격자 오버레이 (SCREEN-005 §좌측 도구바 / UI-046) — 2026-08-08 신설

> 사양이 좌측 도구바에 **좌·우 90° 회전**과 **그리드 표시** 토글을 규정한다. 둘 다 **보기 전용**이며 회전각·격자 표시 상태는 도구바가 아니라 **화면(`LabelingPage`)이 단독 보유**한다(도구바와 캔버스가 같은 값을 봐야 하므로 공통 상위가 유일 보유자여야 한다).
> **★회전은 저장 좌표를 건드리지 않는다** — 회전은 이미지 좌표계가 아니라 **캔버스(뷰) 좌표계**에 레이어 변환으로 걸리고, `geometry.angle` 은 계속 `0` 이다. 여기에 각도를 넣으면 라벨·오버레이가 각자 점을 회전시켜 축정렬 도형(BBox)의 렌더·드래그 계산이 어긋난다.
> **★회전 중에는 편집을 봉인한다** — 캔버스의 입력 경로는 전부 `getPointerPosition()` 의 **회전되지 않은 화면 좌표**를 geometry 로 환산해 이미지 좌표를 만든다. 회전은 그 전제를 깨므로, 그대로 두면 사용자가 찍은 위치와 **다른 좌표가 저장된다**(데이터 오염). 레이어 `listening` 차단 + 라벨 레이어 `readOnly` 2중 방어 + 화면 안내를 함께 둔다.
> **격자는 순수 오버레이다** — 자기 레이어에 그리고 레이어·선분 모두 `listening=false` 라 히트테스트(라벨 선택·드로잉)에 어떤 방식으로도 관여하지 않는다.
> **무회귀** — 새 props 를 주지 않은 기존 호출부는 변환 props 자체가 붙지 않아 **회전 도입 전과 렌더 트리가 동일**하고, 도구바에도 버튼이 늘지 않는다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-486 | 회전해도 **라벨 좌표가 한 글자도 바뀌지 않는다** (신설 · **핵심** · 저장축 무영향) | BBOX 라벨 1건 | `rotation` 0 → 90 | 캔버스로 넘어가는 라벨 값과 스토어의 라벨이 **동일**(`{left:10, top:20, right:60, bottom:80}` 그대로). 바뀌는 것은 표시(뷰 변환)뿐이라 geometry 배치만 달라진다 | component | **Critical** | features/label/canvas/CanvasShell.tsx(`CanvasShell`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`회전해도_라벨_좌표는_한_글자도_바뀌지_않는다_저장축_무영향`) |
| TC-FE-487 | 회전은 **레이어 변환으로만** 걸리고 `geometry.angle` 은 0 을 유지한다 (신설 · **핵심**) | `rotation=90` | 레이어 props 조회 | 이미지·라벨·오버레이 세 레이어에 `rotation:90` + Stage 중심 기준 `x/y`, 그러나 `geometry.angle === 0` | component | **Critical** | features/label/canvas/CanvasShell.tsx(`CanvasShell` — `viewTransform`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`회전은_레이어_변환으로만_걸린다_geometry_각도는_0을_유지한다`) |
| TC-FE-488 | 90/270° 에서는 뷰의 가로·세로가 뒤바뀐다 (신설 · 경계) | `rotation=90`, Stage `W×H` | 레이어 offset 조회 | `offsetX=H/2` · `offsetY=W/2` — geometry·팬 클램프가 모두 이 뒤바뀐 뷰 크기를 기준으로 계산된다 | component | High | features/label/canvas/CanvasShell.tsx(`CanvasShell` — `quarterTurned`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`회전은_레이어_변환으로만_걸린다_geometry_각도는_0을_유지한다`) |
| TC-FE-489 | 회전 중에는 편집 입력이 봉인되고 **왜 잠겼는지 화면이 알린다** (신설 · **핵심**) | `rotation=90` + 라벨 1건 | 렌더 | 라벨·오버레이 레이어 `listening=false` + 라벨 레이어 `readOnly=true`(2중 방어) + `canvas-rotation-notice` 에 "회전 보기 90°" + `data-rotation="90"` | component | **Critical** | features/label/canvas/CanvasShell.tsx(`CanvasShell` — `rotated`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`회전_중에는_편집_입력이_봉인된다_레이어_listening_off_읽기전용_안내노출`) |
| TC-FE-490 | 규격 밖 회전값은 **0(무회전)** 으로 떨어진다 (신설 · 경계 · fail-safe) | `undefined` · `45` · `NaN` · `360` · `-90` · `450` | 정규화 | 각각 `0`·`0`·`0`·`0`·`270`·`90`. 45° 를 넘겨도 화면이 죽지 않고 회전만 포기한다(보기 전용 값이라 예외로 화면을 죽이는 것보다 안전) | component | High | features/label/canvas/CanvasShell.tsx(`normalizeCanvasRotation`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`규격_밖_회전값은_0으로_떨어진다_보기전용이라_화면을_죽이지_않는다`) |
| TC-FE-491 | 포인터 역변환이 회전을 **정확히** 되돌린다 (신설 · 경계 · 부동소수) | 90/180/270° | 왕복 변환 | `rotateVectorClockwise(1,0,90)===[0,1]` 등 정수 성분 교환으로 계산되고, 회전 → 역변환 왕복이 원래 뷰 좌표와 **정확히 일치**한다. 삼각함수를 쓰면 `cos(π/2)` 가 정확히 0 이 아니라 찌꺼기가 좌표에 섞인다. 회전이 0 이면 손대지 않는다(무회귀) | component | High | features/label/canvas/CanvasShell.tsx(`rotateVectorClockwise` · `toViewPoint`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`포인터_역변환은_회전을_정확히_되돌린다_왕복_동일`) |
| TC-FE-492 | 회전 중 팬·휠 줌이 화면 방향과 일치한다 (신설) | 회전 상태에서 팬 드래그 / 휠 줌 | 조작 | 팬은 화면에서 끈 거리를 `-rotation` 만큼 되돌려 적용하고(되돌리지 않으면 90° 에서 오른쪽으로 끌 때 이미지가 아래로 움직인다), 휠 줌은 포인터를 회전 이전 뷰 좌표로 되돌려 **커서 아래 지점이 그대로 유지**된다 | component | High | features/label/canvas/CanvasShell.tsx(`CanvasShell` — `handleMouseMove` · `handleWheel` · `pointerInView`) |
| TC-FE-493 | 격자는 히트테스트에 관여하지 않는다 (신설 · **핵심**) | `showGrid` + 라벨 1건 | 렌더 | 격자 레이어와 **모든 선분**이 `listening=false` — 라벨 선택·드로잉 경로에 어떤 방식으로도 참여하지 않는다 | component | High | features/label/canvas/CanvasShell.tsx(`CanvasShell` — `gridLines`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`격자는_히트테스트에_관여하지_않는다_레이어와_선분_모두_listening_off`) |
| TC-FE-494 | 격자 토글이 라벨 레이어로 가는 값을 바꾸지 않는다 (신설 · 회귀 가드) | `showGrid` false → true | 라벨 레이어 props 비교 | `labels`·`geometry`·`readOnly` 가 동일하고 라벨·오버레이의 `listening` 도 `true` 그대로 — 표시 축이 편집 축에 새지 않는다 | component | High | features/label/canvas/CanvasShell.tsx(`CanvasShell`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`격자_토글은_라벨_레이어로_가는_값을_바꾸지_않는다_선택_드로잉_무영향`) |
| TC-FE-495 | 격자 간격은 **이미지 기준**이고 축당 선 개수에 상한이 있다 (신설 · 경계 · CWE-770) | 초대형 프레임 | 격자 산출 | 한 칸 100 이미지 px 기준이라 확대/축소하면 화면상 간격도 함께 변하고, 축당 선이 상한(200)을 넘지 않도록 칸을 넓힌다 — 상한이 없으면 선이 수천 개 생겨 렌더가 멎는다 | component | Med | features/label/canvas/CanvasShell.tsx(`GRID_CELL_IMAGE_PX` · `GRID_MAX_LINES_PER_AXIS`) |
| TC-FE-496 | 도구바 회전 버튼이 좌·우 90° 를 요청하고, **눌림 상태를 표기하지 않는다** (신설 · a11y) | `rotation=0` + `onRotate` 주입 | 좌/우 버튼 클릭 | `onRotate(-90)` / `onRotate(90)`. 아이콘 전용이지만 접근 이름(`왼쪽으로 90도 회전`·`오른쪽으로 90도 회전`)이 있고, **일회성 액션이라 `aria-pressed` 를 붙이지 않는다**(달면 보조기술이 토글 버튼으로 오안내). 현재 각도는 툴팁(`현재 0도`)이 알린다 | a11y | High | features/label/components/ToolBar.tsx(`ToolBar`) · features/label/__tests__/ToolBarViewControls.test.tsx(`회전_버튼은_좌우_90도를_요청한다_아이콘_전용이어도_접근가능한_이름을_갖는다`) |
| TC-FE-497 | 회전 중에는 **그리기 도구만** 잠긴다 — 선택 도구·보기 조작은 남는다 (신설 · **핵심**) | `rotation=90` | 도구바 조회 | 바운딩 박스·폴리곤·AI 분할·AI 추적·스켈레톤이 비활성 + 툴팁에 "회전 중에는 사용할 수 없습니다". **선택 도구**(회전을 풀었을 때 되돌아갈 기본 도구)와 **회전·그리드 버튼**(되돌릴 수단)은 활성. 잠그지 않으면 눌러도 아무 일이 없는 **죽은 버튼**이 된다 | component | High | features/label/components/ToolBar.tsx(`ToolBar` — `lockedByRotation`) · features/label/__tests__/ToolBarViewControls.test.tsx(`회전_중에는_그리기_도구를_잠근다_선택도구와_보기조작은_남긴다`) |
| TC-FE-498 | 그리드 토글은 눌림 상태를 노출하고 **키보드로 조작**된다 (신설 · a11y) | `showGrid=false` | 포커스 → `{Enter}` → `showGrid=true` 로 재렌더 | `aria-pressed` 가 `false` → `true`, 키보드만으로 조작 가능(마우스 전용이 아니다) | a11y | High | features/label/components/ToolBar.tsx(`ToolBar` — `pressed`) · features/label/__tests__/ToolBarViewControls.test.tsx(`그리드_토글은_눌림_상태를_노출하고_키보드로_조작된다`) |
| TC-FE-499 | 콜백 미지정 기존 호출부에는 버튼이 생기지 않는다 (신설 · 무회귀) | `onRotate`·`onToggleGrid`·`onToggleZoomArea` 모두 미지정 | 렌더 | 회전 좌/우·그리드·영역 확대 버튼이 **전부 부재**하고 기존 보기 조작(화면 맞춤)은 그대로 | component | High | features/label/components/ToolBar.tsx(`ToolBar`) · features/label/__tests__/ToolBarViewControls.test.tsx(`콜백_미지정_기존_호출부에는_회전_그리드_버튼이_생기지_않는다` · `회전이_0이면_그리기_도구가_정상_활성이다_무회귀`) |
| TC-FE-500 | 캔버스도 새 props 미지정 시 **변환·격자 레이어가 붙지 않는다** (신설 · 무회귀) | `rotation`·`showGrid` 미지정 | 렌더 | `data-rotation="0"`·`data-show-grid="false"`, 회전 안내 없음, 격자 레이어·선분 0개, 세 레이어에 `rotation`/`offsetX`/`offsetY` props 자체가 **미부착**, 편집 경로(`listening=true`·`readOnly=false`)는 그대로 열려 있다 | component | High | features/label/canvas/CanvasShell.tsx(`CanvasShell`) · features/label/canvas/__tests__/CanvasShellRotationGrid.test.tsx(`회전_격자_props_없으면_변환도_격자레이어도_붙지_않는다`) |
| TC-FE-501 | 회전하면 화면이 **선택 도구로 되돌린다** (신설 · 죽은 조작 방지) | 그리기 도구 활성 상태 | 회전 버튼 클릭 | 회전각이 4단계로 순환하면서 활성 도구가 선택 도구로 바뀐다 — 캔버스가 편집 입력을 봉인하는 구간에 그리기 도구가 켜진 채로 들어가면 "도구는 켜졌는데 아무 일도 안 일어나는" 상태가 된다 | component | Med | pages/label/LabelingPage.tsx(`handleRotate`) |

## H-36. 라벨링 캔버스 — 영역 확대(보기 조작) (SCREEN-005 §좌측 도구바) — 2026-08-08 신설

> 드래그한 사각형이 화면을 채우도록 배율·위치를 옮기는 **보기 조작**이다. 되돌리기는 같은 도구바의 **'화면 맞춤'**.
> **★이 기능의 유일한 위험은 보기 조작이 편집으로 새는 것이다** — 같은 좌클릭 드래그가 확대와 도형 생성 양쪽으로 발화하면 화면을 확대했을 뿐인데 **라벨이 만들어져 저장된다**(데이터 오염). 켜져 있는 동안 라벨·오버레이 레이어의 포인터 이벤트를 끊어 드로잉 경로 자체를 봉인한다(회전과 같은 축).
> **배율 한계는 기존 줌 정책을 그대로 쓴다** — 새 상·하한을 만들지 않고, 팬 클램프도 렌더 geometry 와 **같은 규칙**을 쓴다(별도 규칙을 두면 스토어 pan 과 실제 배치가 어긋난다).
> **오클릭 보호** — 두 변이 **모두** 12px 이상일 때만 확대한다. 한 축만 재면 얇은 띠 드래그(사실상 선긋기)가 통과하고, 임계가 없으면 클릭 한 번이 배율 상한까지 튀어 사용자가 자기가 보던 자리를 잃는다.
> **회전 중에도 동작한다** — 보기 조작은 회전 중에도 남긴다는 방침이고, 포인터를 회전 이전 뷰 좌표로 되돌린 뒤 계산하므로 회전각과 무관하게 같은 결과가 나온다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-502 | 영역 드래그가 **라벨을 만들지 않는다** (신설 · **핵심** · 데이터 오염 차단) | `zoomAreaMode` + 라벨 1건 | 좌클릭 드래그 | ①라벨 생성 콜백 **미호출** ②오버레이·라벨 레이어 `listening=false`(입구 자체가 닫힘) ③라벨 레이어 `readOnly=true`(2중 방어) ④스토어 라벨 불변 | component | **Critical** | features/label/canvas/CanvasShell.tsx(`CanvasShell` — `interactionSuppressed`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`영역_드래그는_라벨을_만들지_않는다_편집_입력이_봉인된다`) |
| TC-FE-503 | 선택 사각형은 순수 표시이며 드래그 방향과 무관하게 정규화된다 (신설) | 드래그 진행 중 | 사각형 조회 | 사각형 레이어 `listening=false`, 음수 폭 없이 정규화된 뷰 좌표 사각형이 그려지고, **드래그가 끝나면 사라진다**(잔상 없음) | component | High | features/label/canvas/CanvasShell.tsx(`rectBetween` · `zoom-area-layer`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`선택_사각형은_순수_표시다_히트테스트에_참여하지_않는다`) |
| TC-FE-504 | 드래그 후 배율과 중심이 그 영역에 맞춰진다 (신설 · **핵심**) | 뷰 200×200, 좌상단 50×50 영역 드래그 | 마우스 업 | 배율 4배 + 선택 영역의 두 모서리가 확대 후 화면의 두 모서리로 간다(= 영역이 화면을 채운다) | component | High | features/label/canvas/CanvasShell.tsx(`zoomToArea`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`드래그_후_배율과_중심이_그_영역에_맞춰진다`) |
| TC-FE-505 | 임계 미만 드래그는 **아무 일도 하지 않는다** (신설 · 경계 · 오클릭 보호) | ①한 축만 임계 초과(얇은 띠) ②이동 0(클릭만) | 마우스 업 | 두 경우 모두 zoom·pan 이 그대로다. **두 변 모두** 12px 이상이어야 확대한다 | component | High | features/label/canvas/CanvasShell.tsx(`ZOOM_AREA_MIN_DRAG_PX` · `zoomToArea`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`임계_미만_드래그는_아무_일도_하지_않는다_오클릭_보호`) |
| TC-FE-506 | 배율은 **기존 줌 정책의 상·하한**을 넘지 않고 팬도 같은 규칙으로 클램프된다 (신설 · 경계) | ①아주 작은 영역(산술 10배) ②캔버스보다 큰 영역 | 마우스 업 / 순수 함수 | ①상한에서 잘리고 ②하한 아래로 내려가지 않는다(축소는 이 도구의 일이 아니다). **상한에서 잘린 배율과 팬이 같은 배율 기준으로 계산**돼야 선택 영역이 화면 중앙에서 밀리지 않는다 | component | High | features/label/canvas/CanvasShell.tsx(`zoomToArea`) · features/label/canvas/utils/canvasGeometry.ts(`clampPan`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`배율은_기존_줌_정책의_상하한을_넘지_않는다`) |
| TC-FE-507 | 캔버스 밖에서 손을 떼면 확대하지 않는다 (신설 · 경계) | 드래그 중 캔버스 이탈 | `mouseleave` | zoom·pan 불변 + 선택 사각형 제거. **끝점을 알 수 없는 드래그로 화면을 통째로 바꾸느니 아무 일도 일어나지 않는 편이 안전하다** | component | High | features/label/canvas/CanvasShell.tsx(`handleMouseLeave` · `endZoomArea`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`캔버스_밖에서_손을_떼면_확대하지_않는다_끝점을_모르는_드래그는_버린다`) |
| TC-FE-508 | 회전 중에도 **같은 영역**을 확대한다 (신설 · **핵심**) | `rotation=90`, Stage 200×400(뷰 400×200) | 뷰 좌표 기준 같은 사각형이 되는 Stage 좌표로 드래그 | 회전 없이 같은 뷰 사각형을 끈 것과 **정확히 같은** zoom·pan. 역변환이 빠지면 어긋난다 | component | **Critical** | features/label/canvas/CanvasShell.tsx(`pointerInView` · `toViewPoint`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`회전_중_드래그는_회전_이전_뷰_좌표로_환산되어_적용된다`) |
| TC-FE-509 | 회전 중 선택 사각형도 뷰 좌표로 그려지고 **같은 회전 변환**을 받는다 (신설) | 동일 | 드래그 진행 중 | 사각형 좌표가 뷰 좌표이고 사각형 레이어에 같은 `rotation`/`offset` 이 얹혀, 화면에서는 포인터를 따라간 자리에 정확히 그려진다 | component | High | features/label/canvas/CanvasShell.tsx(`zoom-area-layer` · `viewTransform`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`회전_중_선택_사각형은_뷰_좌표로_그려지고_회전_변환을_함께_받는다`) |
| TC-FE-510 | 회전 중에도 보기 조작은 열려 있고 **편집만** 잠긴다 (신설 · 경계) | `rotation=90` + `zoomAreaMode` + 라벨 1건 | 드래그 | 라벨 생성 콜백 미호출 + 확대는 적용됨 + 라벨 레이어 `listening=false` | component | High | features/label/canvas/CanvasShell.tsx(`CanvasShell`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`회전_중에도_보기_조작은_열려_있고_편집만_잠긴다`) |
| TC-FE-511 | 모드를 끄면 진행 중이던 드래그를 버린다 (신설 · 유령 조작 방지) | 드래그 중 `zoomAreaMode` false 전환 | 이후 마우스 업 | 시작점·사각형이 함께 비워져 **끈 뒤 손을 떼도 확대되지 않는다** | component | Med | features/label/canvas/CanvasShell.tsx(`CanvasShell` — `zoomAreaMode` 해제 effect) |
| TC-FE-512 | 팬(스페이스 홀드·중클릭)이 우선이라 두 조작이 다투지 않는다 (신설 · 경계) | `zoomAreaMode` + 스페이스 홀드 / 중클릭 | 드래그 | 영역 확대가 아니라 평소대로 화면을 끈다 — 확대 모드 중에도 팬 경로가 살아 있다 | component | Med | features/label/canvas/CanvasShell.tsx(`handleMouseDown`) |
| TC-FE-513 | 모드가 켜지면 **왜 그리기가 안 되는지와 되돌리는 법**을 화면에서 알린다 (신설) | `zoomAreaMode` | 렌더 | `data-zoom-area="true"` + 안내에 되돌리기 수단('화면 맞춤')이 포함된다 — 조작 없이 도구만 눌러보다 "먹통"으로 오인하는 동선을 없앤다 | component | Med | features/label/canvas/CanvasShell.tsx(`canvas-zoom-area-notice`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`모드가_켜지면_왜_그리기가_안_되는지와_되돌리는_법을_화면에서_알린다`) |
| TC-FE-514 | 도구바 영역 확대는 **모드 토글**이라 눌림 상태를 노출하고 키보드로 조작된다 (신설 · a11y) | `zoomAreaMode=false` | 포커스 → `{Enter}` → `true` 로 재렌더 | `aria-pressed` 가 `false` → `true`. 일회성 액션인 회전 버튼과 달리 토글이다 | a11y | High | features/label/components/ToolBar.tsx(`ToolBar` — `pressed`) · features/label/__tests__/ToolBarViewControls.test.tsx(`영역_확대는_모드_토글이라_눌림_상태를_노출하고_키보드로_조작된다`) |
| TC-FE-515 | 영역 확대는 보기 조작이라 **회전 중에도 잠기지 않고**, 되돌릴 수단도 열려 있다 (신설 · 경계) | ①`rotation=90` ②`zoomAreaMode` | 도구바 조회 | ①영역 확대 버튼 활성(그리기 도구만 비활성) ②'화면 맞춤'·영역 확대 버튼 모두 활성 — 확대한 뒤 원래 배율로 돌아가는 경로가 같은 도구바 안에 있어야 한다(길 잃음 방지) | component | High | features/label/components/ToolBar.tsx(`ToolBar`) · features/label/__tests__/ToolBarViewControls.test.tsx(`영역_확대는_보기_조작이라_회전_중에도_잠기지_않는다` · `영역_확대_중에도_되돌릴_수단인_화면_맞춤은_열려_있다`) |
| TC-FE-516 | prop 미지정 시 좌클릭 드래그가 확대를 일으키지 않는다 (신설 · 무회귀) | `zoomAreaMode` 미지정 | 좌클릭 드래그 | zoom·pan 불변 + 사각형 레이어 부재 + 안내 부재 + `data-zoom-area="false"`, 편집 경로는 그대로 열려 있다. 화면 쪽도 **도구를 고르면 모드가 해제**돼 "도구는 켜졌는데 아무 일도 안 일어나는" 죽은 조작이 생기지 않는다 | component | High | features/label/canvas/CanvasShell.tsx(`CanvasShell`) · pages/label/LabelingPage.tsx(`handleSelectTool` · `handleToggleZoomArea`) · features/label/canvas/__tests__/CanvasShellZoomArea.test.tsx(`영역확대_prop_없으면_좌클릭_드래그가_확대를_일으키지_않는다`) |

## H-37. 페이지네이션 계약 전환 + 목록 3화면 공용 수렴 (UI-008) — 2026-08-08 신설

> **구 계약 → 폐기**: 공용 `Pagination` 이 `size`·`totalElements` 를 받아 **페이지 수를 되계산**하고 `onPageChange` 로 알렸다. 그러면 페이지 크기 규칙을 이 컨트롤이 또 알아야 하고 서버가 이미 준 `totalPages` 와 어긋날 수 있다(**두 번째 진실원**). 이제 `totalPages` 를 **그대로 받고** `onChange` 로 알린다.
> **★사용자 가시 변화 — 총 건수 요약이 컨트롤에서 사라졌다.** 구 컨트롤은 `1-10 / 총 245건` 을 자기 안에서 그렸는데, 그 표기는 총 건수와 페이지 크기를 함께 받아야만 성립해 새 prop 계약과 공존할 수 없다. **화면이 자체 표기를 갖지 않은 곳에서는 총 건수가 화면에서 사라진다** — 코드 실측 기준 **검수 목록 · 공지 목록 · 사용자 관리 3화면**이 여기에 해당하고(사용자 관리는 헤더 부제가 이미 고정 문구다), 증강 결과의 **프레임 쌍 페이저**는 "이 페이지에 쌍이 없다"는 안내에만 총량이 남는다. 프리셋 관리(`전체 N개` 머리글)·증강 결과 **항목 축**(요약 `N건`)·영상 목록·작업 목록·증강 요청은 화면이 자체 표기를 갖고 있어 영향이 없다.
> **구 인라인 페이저 → 폐기(3화면)**: 영상 처리 현황·작업 목록은 번호를 **항상 앞쪽 7칸만** 그려 페이지가 8개를 넘으면 **뒤쪽 페이지로 가는 번호가 아예 없었고**(다음 버튼을 반복해 누르는 길만 남았다), 증강 요청은 **번호 없이 이전/다음뿐**이라 마지막 페이지까지 그만큼 눌러야 했다. 공용 컨트롤은 양끝 + 현재 앞뒤 1칸을 남기고 접으므로 **어느 위치에서도 마지막 페이지로 한 번에 간다**.
> **덤으로 따라오는 것** — 번호 버튼 접근 이름이 `N페이지` 로 통일되고(구 인라인 페이저는 숫자만 읽혔다) 터치 타깃이 KRDS 하한 44px 로 올라간다(구 32px).
> **클라이언트 측 페이징**(프리셋 관리·증강 프레임 쌍)은 `pageCountOf` 로 페이지 수를 구해 넘긴다 — 호출부마다 `Math.ceil` 을 다시 쓰면 0건·나머지 처리가 갈린다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-517 | 전체 페이지 수를 **그대로 받아** 그린다 — 건수로 되계산하지 않는다 (신설 · **핵심** · 구 계약 폐기) | `totalPages=3` (건수·페이지 크기는 알지 못함) | 렌더 | 1~3페이지 번호만 노출되고 4페이지는 없다 | component | **Critical** | components/common/Pagination.tsx(`Pagination`) · components/common/__tests__/Pagination.test.tsx(`Pagination_전체_페이지_수를_그대로_받아_그린다_건수로_되계산하지_않는다`) |
| TC-FE-518 | 총 건수 요약은 이 컨트롤이 갖지 않는다 (신설 · 구 표기 폐기 · **사용자 가시 변화**) | `totalPages=25` | 렌더 | `총 …건` 표기가 **없다**. 건수 표기가 필요한 화면은 화면 쪽이 소유한다 | component | High | components/common/Pagination.tsx(`Pagination`) · components/common/__tests__/Pagination.test.tsx(`Pagination_총_건수_요약은_이_컨트롤이_갖지_않는다`) |
| TC-FE-519 | 0건이면 **1페이지로 세고** 컨트롤 자리는 유지된다 (신설 · 경계) | `totalPages=0`(서버 빈 목록 응답) | 렌더 | 1페이지 하나만 있고 이전·다음 모두 잠긴다. `0` 그대로 두면 노출할 번호가 없어 컨트롤이 통째로 사라지고 버튼 자리가 흔들린다 | component | High | components/common/Pagination.tsx(`Pagination` — `pageCount`) · components/common/__tests__/Pagination.test.tsx(`Pagination_0건이면_1페이지로_세고_컨트롤_자리는_유지된다`) |
| TC-FE-520 | 전체가 1페이지면 번호는 하나뿐이고 이동이 모두 잠긴다 (신설 · 경계) | `totalPages=1` | 렌더 | 2페이지 없음 + 이전·다음 비활성 | component | Med | components/common/Pagination.tsx(`Pagination`) · components/common/__tests__/Pagination.test.tsx(`Pagination_전체가_1페이지면_번호는_하나뿐이고_이동이_모두_잠긴다`) |
| TC-FE-521 | 범위를 벗어난 현재 페이지는 마지막 페이지로 가둔다 (신설 · 경계) | `page=99`, `totalPages=3`(보던 중 총건수 감소) | 렌더 | 3페이지가 `aria-current="page"` + 다음 비활성 — **존재하지 않는 번호를 현재로 표시하지 않는다** | component | High | components/common/Pagination.tsx(`Pagination` — `safePage`) · components/common/__tests__/Pagination.test.tsx(`Pagination_범위를_벗어난_현재_페이지는_마지막_페이지로_가둔다`) |
| TC-FE-522 | 마지막 페이지에서 다음이 잠기고 눌러도 요청이 나가지 않는다 (신설 · 경계) | `page=4`, `totalPages=5` | 다음 클릭 | 버튼 비활성 + `onChange` 미호출 | component | Med | components/common/Pagination.tsx(`Pagination` — `go`) · components/common/__tests__/Pagination.test.tsx(`Pagination_마지막_페이지에_도달하면_다음이_잠기고_이동_요청도_없다`) |
| TC-FE-523 | `pageCountOf` 가 클라이언트 측 페이징의 페이지 수 규칙을 **한 곳에서** 정한다 (신설) | 0건 / 나머지 있음 / 비정상 페이지 크기 | 호출 | 0건 → **1**(페이저의 빈 목록 규칙과 같은 값이라 "0페이지 중 1페이지" 가 생기지 않는다) · `11/10 → 2`·`245/10 → 25`(올림) · 크기 0·음수·`NaN` → **1**(0으로 나누지 않는다) | component | High | components/common/Pagination.tsx(`pageCountOf`) · components/common/__tests__/Pagination.test.tsx(`0건은_빈_1페이지로_센다_페이저_규칙과_같은_값이다` · `나머지가_있으면_올림한다` · `페이지_크기가_비정상이면_1페이지로_떨어뜨린다_0으로_나누지_않는다`) |
| TC-FE-524 | 영상 처리 현황 — 페이지가 많아도 **마지막 페이지로 가는 경로가 있다** (신설 · **핵심** · 구 1~7 고정 폐기) | 25페이지 | '25페이지' 클릭 | 한 번의 조작으로 `page=24` 가 조회된다. 구 인라인 페이저는 1~7 번호만 그려 25페이지 버튼이 **없었다** | component | **Critical** | pages/VideoListPage.tsx(`VideoListPage`) · pages/__tests__/ListPagesPagination.test.tsx(`페이지가_많아도_마지막_페이지로_가는_경로가_있다`) |
| TC-FE-525 | 영상 처리 현황 — 페이지 이동이 **기존 URL 형식 그대로** 반영된다 (신설 · 하위호환) | 필터가 걸린 상태(`cctvNameKeyword`·`dataSttsCd`) | '2페이지' 클릭 | URL 이 `?page=1&cctvNameKeyword=강남&dataSttsCd=COMPLETED`(키 이름·순서·기본값 생략 종전 그대로)이고 요청에도 필터가 함께 실린다 — 페이지만 남고 필터가 떨어지면 북마크가 깨진다 | component | High | pages/VideoListPage.tsx(`VideoListPage` — `updateParams`) · pages/__tests__/ListPagesPagination.test.tsx(`페이지_이동이_기존_URL_형식_그대로_반영된다`) |
| TC-FE-526 | 영상 처리 현황 — 전체 페이지 수를 **서버 응답값으로** 쓴다 (신설 · 회귀 가드) | 응답 `totalPages` | 렌더 | 건수÷페이지 크기 되계산을 하지 않는다 — 서버의 페이징 규칙을 화면이 한 번 더 갖게 되면 두 값이 어긋난다 | component | High | pages/VideoListPage.tsx(`VideoListPage` — `totalPages`) · pages/__tests__/ListPagesPagination.test.tsx(`페이지가_많아도_마지막_페이지로_가는_경로가_있다`) |
| TC-FE-527 | 작업 목록 — 마지막 페이지 도달 + **필터 축 유지** (신설 · **핵심**) | 25페이지, 배치 상태 축 필터 적용 | '25페이지' 클릭 | `page=24` 로 조회되고 배치 상태 축(`status`)이 그대로 실린다(축이 둘이라 한쪽만 남으면 목록이 갈린다) | component | **Critical** | pages/TaskListPage.tsx(`TaskListPage` — `handlePageChange`) · pages/__tests__/ListPagesPagination.test.tsx(`페이지가_많아도_마지막_페이지로_가는_경로가_있다`) |
| TC-FE-528 | 작업 목록 — 페이지 번호는 **URL 에 싣지 않는다**(기존 계약 유지) (신설 · 하위호환) | `?q=강남&status=UNASSIGNED` 로 진입 | 마지막 페이지로 이동 | URL 이 **그대로**다(페이지 번호가 새로 새어 들어가지도, 필터가 떨어지지도 않는다) + BE 파라미터 `workStatus` 도 유지 | component | High | pages/TaskListPage.tsx(`TaskListPage`) · pages/__tests__/ListPagesPagination.test.tsx(`페이지_이동이_필터_URL_형식을_바꾸지_않는다`) |
| TC-FE-529 | 증강 요청 — 마지막 페이지 도달 + 검수 완료 필터 유지 (신설 · 구 이전/다음뿐 폐기) | 25페이지 | '25페이지' 클릭 | `page=24` 로 조회되고 `dataSttsCd=COMPLETED`·`reviewStatusCd=APPROVED` 가 유지된다. 구 페이저는 이전/다음뿐이라 24번 눌러야 도달했다 | component | High | pages/AugmentRequestPage.tsx(`AugmentRequestPage`) · pages/__tests__/ListPagesPagination.test.tsx(`페이지가_많아도_마지막_페이지로_가는_경로가_있다`) |
| TC-FE-530 | 증강 요청 — 총 건수는 페이저가 아니라 **단계 머리글 배지**가 말한다 (신설 · 중복 제거) | 25페이지(500건) | 렌더 | `검수 완료 500건` 배지가 있고 구 페이저 문구 `전체 N건 (x/y 페이지)` 는 **없다**. 사양은 이동 컨트롤과 건수 배지를 별도 요소로 둔다 | component | Med | pages/AugmentRequestPage.tsx(`AugmentRequestPage`) · pages/__tests__/ListPagesPagination.test.tsx(`총건수는_페이저가_아니라_단계_머리글_배지가_말한다`) |
| TC-FE-531 | 3화면 공통 — 1페이지·0건이면 페이지네이션을 렌더하지 않는다 (신설 · 경계) | `totalPages=1` / 0건 | 렌더 | `navigation[name="페이지네이션"]` 부재. 0건 화면에서는 표 머리글의 `전체 0건` 이 사실을 말한다 | component | Med | pages/VideoListPage.tsx · pages/TaskListPage.tsx · pages/AugmentRequestPage.tsx · pages/__tests__/ListPagesPagination.test.tsx(`한_페이지뿐이면_페이지네이션을_렌더하지_않는다` · `0건이면_페이지네이션을_렌더하지_않는다`) |
| TC-FE-532 | 번호 버튼 접근 이름이 `N페이지` 로 통일된다 (★정정 반영 · a11y) | 목록 화면 | 스크린리더 조회 | 공용 컨트롤의 번호 버튼은 `1페이지`·`25페이지` 로 읽힌다. ⚠ 구 인라인 페이저는 **숫자만**(`1`) 이라 무슨 숫자인지 알 수 없었고, 그 셀렉터를 쓰던 기존 테스트는 함께 정정됐다 | a11y | High | components/common/Pagination.tsx(`Pagination`) · pages/__tests__/TaskListPage.board.test.tsx(`필터_초기화시_page_0_복귀`) |
| TC-FE-533 | 번호·이동 버튼이 KRDS 최소 터치 타깃(44px)을 만족한다 (신설 · a11y) | 목록 화면 | 버튼 크기 | 공용 컨트롤은 `h-11 min-w-11`. 구 인라인 페이저는 32px(`w-8 h-8`) 이라 하한 미달이었다 | a11y | Med | components/common/Pagination.tsx(`Pagination` — `btnBase`) |

## H-38. 사용자 관리 — 저장 잠금 조건에 '변경 없음' 추가 (SCREEN-024) — 2026-08-08 신설

> 사양 SCREEN-024: *"역할을 선택하지 않았거나 **원래 값과 같으면** 비활성화된다"*. 구 구현은 **미선택만** 막아, 아무것도 바꾸지 않은 채 저장을 눌러 **PATCH 없는 빈 왕복**을 만들 수 있었다(요청은 나가지 않고 모달만 닫혀, 눌렀는데 아무 일도 없는 것처럼 보인다).
> **두 사유를 하나의 불리언으로 합치지 않는다** — **왜 잠겼는지 화면이 말해야** 하기 때문이다. 이유 없이 잠긴 버튼은 고장으로 읽힌다.
> **판정은 "지금 값이 원래와 같은가"이지 "한 번이라도 건드렸는가"가 아니다** — dirty 플래그로 구현하면 되돌린 경우에 저장이 열린 채 남는다(TC-FE-536).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-534 | 원래 역할 그대로면 저장이 잠기고 **사유를 밝힌다** (신설 · **핵심** · 구 미선택 단독 판정 폐기) | 역할이 이미 `WORKER` 인 사용자 | 수정 모달만 열고 아무것도 바꾸지 않음 | 저장 비활성 + "변경된 내용이 없습니다…" 안내 노출 | component | High | pages/manage/UserManagePage.tsx(`saveBlockedReason`) · pages/manage/__tests__/UserManagePage.test.tsx(`원래_역할_그대로면_저장이_잠기고_사유를_밝힌다`) |
| TC-FE-535 | 다른 역할로 바꾸면 저장이 풀리고 안내가 사라진다 (신설) | 동일 | `WORKER` → `REVIEWER` | 저장 활성 + 안내 미노출 | component | High | pages/manage/UserManagePage.tsx(`saveBlockedReason`) · pages/manage/__tests__/UserManagePage.test.tsx(`다른_역할로_바꾸면_저장이_풀리고_안내가_사라진다`) |
| TC-FE-536 | 바꿨다가 **원래 역할로 되돌리면** 다시 잠긴다 (신설 · 경계 · dirty 플래그 방지) | 동일 | `WORKER` → `REVIEWER` → `WORKER` | 저장이 다시 비활성 + 안내 재노출. dirty 플래그로 구현하면 이 케이스에서 저장이 열린 채 남는다 | component | High | pages/manage/UserManagePage.tsx(`saveBlockedReason`) · pages/manage/__tests__/UserManagePage.test.tsx(`바꿨다가_원래_역할로_되돌리면_저장이_다시_잠긴다`) |
| TC-FE-537 | 미선택 차단은 그대로 동작하고 두 안내가 **겹치지 않는다** (신설 · 경계) | 역할 미배정 사용자 | 수정 모달 열기 | 저장 비활성 + **미배정 안내만** 노출(변경없음 안내는 미노출) — 두 사유가 동시에 뜨면 무엇을 해야 할지 알 수 없다 | component | High | pages/manage/UserManagePage.tsx(`saveBlockedReason`) · pages/manage/__tests__/UserManagePage.test.tsx(`미선택_차단은_그대로_동작하고_변경없음_안내와_겹치지_않는다`) |

## H-39. 마킹 화면 — 마크 0건 빈 상태 안내 (SCREEN-006 ④ 현재 마킹 칩 목록) — 2026-08-09 신설

> **구 동작 → 폐기**: 칩 목록을 `localMarks.length > 0` 일 때만 렌더해 **0건이면 패널이 통째로 사라졌다**. 그러면 사용자가 **"이 화면엔 그런 기능이 없다"**와 **"아직 마킹을 안 했다"**를 구분할 수 없다. 확정 사양은 0건이면 **빈 상태 안내를 노출**하는 것이다.
> **안내 문구는 모드별로 다르다** — 자동 모드에서는 `Space` 단축키가 아예 발화하지 않으므로(키보드 핸들러의 `mode !== 'MANUAL'` 조기 리턴) 수동 모드 안내를 그대로 보여주면 **눌러도 아무 일이 없는 키를 알려주는 거짓 안내**가 된다.
> **최근 추가된 조작을 잃지 않는다** — 칩 개별 삭제 버튼([H-31](#h-31-마킹-화면--마크-칩-개별-삭제-버튼-ui-045-markingpanel--2026-08-08-신설))과 `Del`/`Backspace` 단축키는 그대로이며, 마지막 마크를 지우면 빈 상태로 **되돌아간다**(TC-FE-542).
> 빈 상태 표면은 공용 `EmptyState`(role=status)를 재사용한다 — 화면마다 자체 문구·마크업을 만들면 표기가 갈린다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-538 | 마크 0건이면 패널이 **사라지지 않고** 빈 상태 안내가 뜬다 (신설 · **핵심** · 구 조건부 렌더 폐기) | 수동 모드 · `localMarks=[]` | 렌더 | 제목 `현재 마킹 (0건)` 이 남고 `추가한 마킹이 없습니다` 안내 노출. 칩(삭제 버튼)은 0개. **패널 자체가 사라지면 기능 부재와 구분되지 않는다** | component | High | pages/MarkingPage.tsx(`MarkingPage` 마킹 칩 목록 블록) · components/common/EmptyState.tsx(`EmptyState`) · pages/__tests__/MarkingPage.test.tsx(`마크가_0건이면_패널이_사라지지_않고_빈_상태_안내가_보인다`) |
| TC-FE-539 | 수동 모드 빈 상태는 **실제로 동작하는** 조작을 안내한다 (신설) | 수동 모드 · 0건 | 렌더 | "영상을 재생하다 이벤트 시점에서 Space 키를 누르면 마킹이 추가됩니다." 노출 | component | Med | pages/MarkingPage.tsx(`MarkingPage`) · pages/__tests__/MarkingPage.test.tsx(`수동모드_빈_상태는_Space_단축키를_안내한다`) |
| TC-FE-540 | 자동 모드 빈 상태는 `Space` 안내를 **하지 않는다** (신설 · 거짓 안내 방지) | 자동 모드 · 0건 | 모드 전환 후 렌더 | 안내는 계속 뜨되 문구가 "자동 모드는 간격(프레임)만 지정하면 되며…" 이고 **`Space` 안내는 미노출**. 자동 모드에서 그 키는 발화하지 않는다 | component | High | pages/MarkingPage.tsx(`MarkingPage` 키보드 핸들러의 `mode !== 'MANUAL'` 조기 리턴) · pages/__tests__/MarkingPage.test.tsx(`자동모드_빈_상태는_Space_안내를_하지_않는다`) |
| TC-FE-541 | 마크가 1건 이상이면 빈 상태 대신 **칩 목록**이 보인다 (신설 · 상호배타) | 수동 마킹 2건 | 렌더 | 제목 `현재 마킹 (2건)` + 삭제 버튼 2개, 빈 상태 안내는 **미노출**. 두 분기가 동시에 뜨지 않는다 | component | High | pages/MarkingPage.tsx(`MarkingPage`) · pages/__tests__/MarkingPage.test.tsx(`마크가_1건이상이면_빈_상태_대신_칩_목록이_보인다`) |
| TC-FE-542 | 마지막 마크를 지우면 **빈 상태로 되돌아간다** (신설 · 경계 · 조작 보존) | 수동 마킹 1건 | 그 칩의 삭제 버튼 클릭 | 패널이 사라지는 게 아니라 `현재 마킹 (0건)` + 빈 상태 안내로 전환. 삭제 버튼(H-31)이 빈 상태 전환과 함께 살아 있음을 고정한다 | component | High | pages/MarkingPage.tsx(`MarkingPage`) · features/marking/store.ts(`removeMark`) · pages/__tests__/MarkingPage.test.tsx(`마지막_마크를_지우면_빈_상태로_되돌아간다`) |

## H-40. 화면 글리프 — 이모지·기호 0건 유지 + 의미 보존 (교차 관심사) — 2026-08-09 신설

> **구 동작 → 폐기**: 증강 종류·확인요청 표식·라벨 출처·저장 상태·접힘 표시에 이모지와 기하 기호(`❄🌙🌧` · `🚩` · `🔗🤖✏️` · `●✓` · `▸▾`)를 썼다. 이모지는 **OS·폰트마다 모양과 폭이 달라** 정렬이 흔들리고, 보조기술이 **문자 이름**("인박스 트레이")을 읽으며, 아이콘 라이브러리의 크기·색 토큰과도 어긋난다. 전량 `lucide-react` 로 교체했다.
> **이 절이 고정하는 것은 "0건"이 아니라 "0건이 유지된다"** — 새 버튼에 이모지를 붙이는 편이 손쉬워 계속 재유입되는 종류의 결함이다. 기준선이 **비어 있는 상태**가 정상이며, 한 자만 들어와도 그 파일을 지목하며 실패한다.
> **의미를 잃지 않는 것이 교체의 조건이다** — 장식이면 `aria-hidden`, 의미를 나르면 접근 가능한 이름을 준다. 상태 표시는 **문구가 함께 있어** 색·모양만으로 정보를 전달하지 않는다(TC-FE-545).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-543 | 사용자 노출 코드에 이모지·기호 글리프가 **0건**이다 (신설 · 재유입 가드) | `src` 하위 비테스트 `.ts`/`.tsx` 전량 | 주석 제거 후 글리프 스캔 | 기준선(빈 객체)과 **파일 단위로 일치**. 새 파일 유입도 기존 파일의 글리프 추가도 함께 잡힌다 | component | High | test/uiWordingGuard.test.ts(`기준선에_없는_파일에는_이모지_기호_글리프가_없다`) |
| TC-FE-544 | 리포트 다운로드 버튼이 아이콘 라이브러리를 쓴다 (신설 · 대표 지점) | 전체 구축 현황 화면 | 소스 검사 | `leftIcon={Download}` 사용 + 라벨 문구 "리포트 다운로드" 유지(아이콘만 교체한 변경) | component | Med | test/uiWordingGuard.test.ts(`리포트_다운로드_버튼은_이모지가_아니라_아이콘_라이브러리를_쓴다`) · pages/OverallStatPage.tsx |
| TC-FE-545 | 저장 상태는 **아이콘이 아니라 문구**로 낭독된다 (신설 · a11y · 색만으로 전달 금지) | 라벨링 헤더 | `dirty` / 저장 완료 | 낭독 내용이 "편집 중" / "저장됨" 그대로이고 아이콘(`Circle`/`Check`)은 `aria-hidden`. **색(경고/성공)은 보조 축일 뿐** — 글리프를 아이콘으로 바꾸며 색만 남겼다면 색각 이상 사용자가 구분하지 못한다 | a11y | High | features/label/components/LabelHeader.tsx(`LabelHeader` 저장 상태 블록) · features/label/components/__tests__/LabelHeader.test.tsx(`저장중이_아니면_기존_편집중_저장됨_표시가_그대로다`) |

## H-41. 폼 컨트롤 모서리 반경 통일 (UI-002 계열) — 2026-08-09 신설

> **구 동작 → 폐기**: `Input` 만 `rounded-lg`(8px)이고 `Select`·`Textarea`·`FileInput`·`DatePicker` 넷은 `rounded-md`(6px)였다. 같은 폼에서 나란히 놓이면 텍스트 입력만 혼자 둥글어 보인다. **다수(md)에 맞춰 `Input` 을 내린다.**
> **검사하는 명제는 "6px 이어야 한다"가 아니라 "다섯이 같다"** 이다 — 토큰을 통째로 바꾸는 향후 결정은 기대값 한 줄만 고치면 되지만, **한 컨트롤만 몰래 갈라지는 것**은 반드시 실패해야 한다.
> 검사 대상은 **필드 본체**의 클래스다. 부속 오버레이(`Select` 드롭다운 패널·옵션 항목)는 반경 축이 달라 대상이 아니며, `FileInput` 은 시각적 필드가 `::file-selector-button` 이라 `file:` 접두 쪽을 본다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-546 | 폼 컨트롤 5종이 **같은 모서리 반경**을 쓴다 (신설 · **핵심**) | `Input`/`Textarea`/`Select`/`DatePicker`/`FileInput` | 각 필드 본체 클래스에서 반경 토큰 추출 | 다섯 모두 `rounded-md`. 어긋난 파일이 **값과 함께** 드러난다(파일 단위 비교) | component | Med | components/common/Input.tsx(`Input`) · test/formControlRadius.test.ts(`다섯_폼_컨트롤이_같은_모서리_반경을_쓴다`) |
| TC-FE-547 | `Input` 이 혼자 `lg` 로 되돌아가지 않는다 (신설 · 회귀 가드) | 동일 | `Input` 필드 줄의 반경 | `lg` 가 아니다. ⚠ **파일 전체 문자열 검색은 쓰지 않는다** — 구 값을 설명하는 **주석**에도 그 문자열이 있어 오탐이 난다(이 가드를 처음 쓸 때 실제로 걸렸다). 검사 대상은 언제나 필드 본체 줄이다 | component | Med | components/common/Input.tsx(`Input`) · test/formControlRadius.test.ts(`Input_이_혼자_lg로_되돌아가지_않는다`) |


## H-42. 이슈 스레드 — 작성자 역할 표기(검수자 문의 ↔ 작업자 문의 구분) (SCR-LABEL-001 / SCREEN-019 이슈 탭) — 2026-08-09 신설

> **구 동작 → 폐기**: 스레드 헤더가 `resolveDisplayName(이름, 사번)` 만 써서 **이름(또는 사번)만** 보여 줬다. 문의가 작업자→검수자 단방향이던 동안에는 작성자가 늘 작업자라 문제가 아니었지만, **검수자도 문의를 등록**하게 된 뒤로는 화면에서 두 문의가 **전혀 구분되지 않는다**. 댓글은 이미 "{이름} ({역할})" 로 표기하고 있었으므로, 같은 화면 안에서 **댓글은 역할이 보이고 스레드는 안 보이는** 비대칭이기도 했다.
> **표기 로직을 복제하지 않는다** — 스레드도 댓글과 **같은 헬퍼**(`issueAuthorLabel`)를 쓴다. 복제하면 한쪽만 갱신돼 두 표기가 갈린다(이 저장소의 반복 결함 패턴).
> **역할이 `null` 이면 이름만 보여 준다** — BE 는 역할 매핑이 없거나 사번이 비숫자면 `null` 을 내린다([D-1c](D-review-version-notify.md#d-1c-문의-스레드-작성자-역할-노출-tc-review--2026-08-09-신설)). 그때 `"홍길동 ()"` 처럼 **빈 괄호**를 남기면 값이 유실된 것처럼 보인다. 이 분기는 헬퍼 안에 있어 댓글 축도 함께 보호된다.
> ⚠ **역할 코드값(`WORKER`/`REVIEWER`)을 화면에 그대로 노출하지 않는다**(한글 라벨 치환) — 매핑에 없는 코드만 원문 폴백한다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-548 | **★검수자 문의와 작업자 문의가 스레드 헤더에서 역할로 구분된다 (신설 · 핵심 · 구 이름만 표기 폐기)** | `reportedUserRoleCd` 가 각각 `REVIEWER`·`WORKER` 인 문의 2건 | 렌더 | 헤더가 각각 `검수자1 (검수자)` · `작업자100 (작업자)`. 코드값 `REVIEWER`/`WORKER` 는 화면에 노출되지 않는다. **이 단언이 곧 "구분된다"의 정의다** | component | High | features/review/components/IssueThreadPanel.tsx(`ThreadCard` — `reporterLabel`) · features/review/issueLabels.ts(`issueAuthorLabel`) · features/review/__tests__/IssueThreadPanel.test.tsx(`검수자_문의와_작업자_문의가_스레드_헤더에서_역할로_구분된다`) |
| TC-FE-549 | 역할이 `null` 이면 **이름만** 보이고 빈 괄호가 남지 않는다 (신설 · 경계) | `reportedUserRoleCd: null`, 이름 있음 | 렌더 | 헤더 텍스트가 **정확히** `작업자100`. `작업자100 ()` 이면 실패 — 부분일치가 아니라 전체 일치로 단언해야 빈 괄호가 잡힌다 | component | High | features/review/issueLabels.ts(`issueAuthorLabel` — 빈 역할 분기) · features/review/__tests__/IssueThreadPanel.test.tsx(`스레드_역할이_null이면_이름만_보이고_빈_괄호가_남지_않는다`) |
| TC-FE-550 | 역할도 이름도 없으면 **사번만** 보인다 (신설 · 경계 · 하위호환) | 이름 `null` + `reportedUserRoleCd` **필드 자체가 없는** 응답(구 BE·캐시된 페이로드) | 렌더 | 헤더 텍스트가 정확히 `9999`. 필드가 없어도 화면이 깨지지 않는다(추가 필드라 optional) | component | High | features/review/types.ts(`IssueThread.reportedUserRoleCd`) · features/review/issueLabels.ts(`issueAuthorLabel`) · features/review/__tests__/IssueThreadPanel.test.tsx(`스레드_역할이_없고_이름도_없으면_사번만_보인다`) |
| TC-FE-551 | 알 수 없는 스레드 역할 코드는 원문으로 폴백한다 (신설) | `reportedUserRoleCd: 'PORTAL_USER'` | 렌더 | `외부사용자 (PORTAL_USER)` — 새 역할이 생겨도 빈칸이 되지 않는다(댓글 축의 기존 규약과 동일) | component | Med | features/review/issueLabels.ts(`issueAuthorRoleLabel`) · features/review/__tests__/IssueThreadPanel.test.tsx(`알_수_없는_스레드_역할코드는_원문으로_폴백한다`) |
| TC-FE-552 | 스레드 역할 표기가 **댓글 작성자 표기를 바꾸지 않는다** (신설 · 두 축 경계) | 스레드 작성자 `REVIEWER` + 댓글 작성자 `WORKER` | 렌더 | 헤더 `검수자1 (검수자)` · 댓글 `작업자100 (작업자)` 가 **동시에** 보인다. 같은 헬퍼를 쓰되 두 축은 독립이며, 한쪽 값이 다른 쪽을 덮으면 실패한다 | component | High | features/review/components/IssueThreadPanel.tsx(`ThreadCard`) · features/review/__tests__/IssueThreadPanel.test.tsx(`스레드_역할_표기가_댓글_작성자_표기를_바꾸지_않는다`) |

## H-43. 증강 결과 화면 — 작업 요약 5칸 확정(구 6칸 폐지) — 2026-08-09 신설

> **구 동작 → 폐기**: 작업 요약이 **6칸**이었고 그중 하나가 `증강 유형`(현재 항목 페이지에 실린 항목들의 유형 **집합**)이었다. 확정 사양의 칸은 **다섯**이다 — `작업 ID` · `대상 영상` · `결과 항목` · `비교 프레임 쌍` · `요청일시`([E-2B](E-augment-resolution-export-meta.md) `TC-AUG-122~129` 로 공급원이 생긴 값). 요청일시를 더하면서 6칸이 된 것이 드리프트였다.
> **유형 값이 사라지는 것이 아니다** — 유형은 **결과 항목 단위** 정보라 항목 탭 라벨과 비교 이미지 라벨이 **항목마다 항상** 보여 준다. 오히려 요약 쪽이 여러 항목의 유형을 **합집합으로 뭉쳐** 어느 항목의 유형인지 말하지 못했다(칸만 차지하고 정보량은 더 적었다).
> **그리드 열 수도 함께 되돌린다** — 칸만 지우고 `md:grid-cols-6` 을 남기면 마지막 열이 빈 채로 남아 정렬이 어긋난다. ⚠ 이 저장소의 살아 있는 브레이크포인트는 **`md`·`xl` 둘뿐**이다(`tailwind.config` `theme.screens`) — `sm`/`lg`/`2xl` 접두로 되돌리면 클래스가 생성되지 않아 반응형이 통째로 사라진다.
> **칸 개수만 세지 않는다** — 개수만 고정하면 어느 칸이 바뀌었는지 모른 채 통과한다. 라벨 목록을 **순서까지** 고정한다(TC-FE-553).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-553 | **★작업 요약은 5칸이고 `증강 유형` 칸을 두지 않는다 (신설 · 핵심 · 구 6칸 폐지)** | 항목 1건(= 전체, 범위 표기 없는 형상) | 결과 화면 진입 | `dt` 가 **5개**이고 텍스트가 순서대로 `작업 ID` · `대상 영상` · `결과 항목` · `비교 프레임 쌍` · `요청일시`. 요약 어디에도 `증강 유형` 문자열이 없다 | component | High | pages/AugmentResultPage.tsx(`AugmentResultPage` 작업 요약 `dl` · `ResultSummary` · `summarize`) · features/augment/\_\_tests\_\_/AugmentResultPage.summary.test.tsx(`작업_요약은_5칸이고_증강_유형_칸을_두지_않는다`) |
| TC-FE-554 | 요약 그리드 열 수가 칸 수와 같은 **5열**이다 (신설 · 경계 · 빈 열 방지) | 동일 | 요약 `dl` 의 클래스 | `md:grid-cols-5` 를 포함하고 `grid-cols-6` 을 **포함하지 않는다**. ⚠ 살아 있는 브레이크포인트는 `md`·`xl` 뿐이라 `sm:`/`lg:` 로 바꾸면 클래스가 생성되지 않는다 | component | Med | pages/AugmentResultPage.tsx(`AugmentResultPage` 작업 요약 `dl`) · features/augment/\_\_tests\_\_/AugmentResultPage.summary.test.tsx(`요약_그리드_열수는_칸수와_같은_5열이다`) |
| TC-FE-555 | 증강 유형은 요약이 아니라 **결과 항목 단위**로 보인다 (신설 · 정보 유실 방지) | `type='WINTER'` 항목 1건 | 결과 화면 진입 | 항목 탭 이름에 한글 유형(`겨울`)이 그대로 보인다. **요약에서 칸을 지운 것이 유형 정보를 화면에서 없앤 것이 아님**을 고정한다 | component | High | features/augment/resultView.ts(`buildItemTabLabels`) · features/augment/components/AugmentVideoSection.tsx(`AugmentVideoSection` 탭 렌더) · features/augment/augTypeLabel.ts(`augTypeLabel`) · features/augment/\_\_tests\_\_/AugmentResultPage.summary.test.tsx(`증강_유형은_요약이_아니라_결과_항목_단위로_보인다`) |

## H-44. 마킹 화면 — 완료 버튼 항상 활성 + 마크 0건 안내 — 2026-08-09 신설

> **구 정책 → 폐기**: 수동 모드에서 마크가 0건이면 **완료 버튼을 비활성화**했고(툴바), 단축키 경로는 **조용한 early return** 이었다(페이지). 그러면 사용자는 "눌리지 않는다" 또는 "눌렀는데 아무 일도 없다" 만 겪고 **왜인지를 화면이 말해 주지 않는다.** 스크린리더 사용자에게는 아무 신호도 남지 않는다.
> **확정 사양**: 완료 버튼은 **항상 클릭 가능**하고, 마크 0건으로 누르면 **안내(토스트)로 사유를 말하며 제출만 막는다.** 안내는 `role="alert"`(`aria-live`)로 노출되므로 보조기술에도 사유가 닿는다 — 버튼을 항상 활성으로 두면서 접근성을 유지하는 근거가 이 안내다.
> **저장 중 비활성은 별개 축이라 유지한다** — 0건 비활성만 걷어내는 것이며 함께 걷어내면 in-flight 중 재클릭으로 마킹 POST 가 **중복 발화**한다(TC-FE-559·560).
> **자동 모드는 이 검사 대상이 아니다** — 개별 마크를 쌓지 않으므로 `intervalFrames < 1` 만 본다(TC-FE-117 의 축은 그대로).
> **책임이 두 곳에 나뉜다** — 버튼의 활성 여부는 툴바(`MarkingToolbar`)가, 안내 발화는 호출부(`MarkingPage`)가 담당한다. 그래서 케이스도 두 레벨로 나눠 고정한다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-556 | **★수동 모드 완료 버튼은 마크 0건이어도 활성이다 (신설 · 핵심 · 구 비활성 정책 폐기)** | `mode='MANUAL'` · `markCount=0`(구 구현이 버튼을 죽이던 바로 그 조합) | 렌더 | 버튼 `마킹 완료` 가 **enabled**. 눌리지 않는 이유를 화면이 말하지 못하는 상태를 만들지 않는다 | component | High | features/marking/components/MarkingToolbar.tsx(`MarkingToolbar` — `disabled` 산출) · features/marking/components/\_\_tests\_\_/MarkingToolbar.test.tsx(`수동모드_완료버튼은_마크0건이어도_활성이다`) |
| TC-FE-557 | 마크 0건으로 누르면 **제출 대신 안내**가 뜬다 (신설 · 핵심 · 발화 축) | 수동 모드 · `localMarks=[]` | 완료 버튼 클릭 | 토스트 `재생하며 마킹을 1건 이상 쌓아 주세요.`(variant=warning) 노출 + **POST 0회** + 화면 이탈 없음. 세 가지를 함께 단언해야 "안내만 뜨고 제출도 나가는" 절반 구현이 잡힌다 | component | High | pages/MarkingPage.tsx(`MarkingPage` — `handleSubmit` 0건 분기) · pages/\_\_tests\_\_/MarkingPage.test.tsx(`수동모드_마크0건이면_완료버튼이_활성이고_눌러도_제출되지_않고_안내가_뜬다`) |
| TC-FE-558 | `Enter` 단축키 경로도 **같은 안내**를 낸다 (신설 · 진입 경로 대칭) | 수동 모드 · 0건 | `keydown` `Enter` | 버튼 클릭과 동일하게 안내 노출 + POST 0회. 두 진입점이 같은 `handleSubmit` 을 타므로 한쪽만 안내하면 정책이 갈린다 | component | High | pages/MarkingPage.tsx(`MarkingPage` 키보드 핸들러 → `handleSubmit`) · pages/\_\_tests\_\_/MarkingPage.test.tsx(`수동모드_마크0건_Enter단축키도_제출대신_안내한다`) |
| TC-FE-559 | 저장 중에는 완료 버튼이 **비활성**이다 (신설 · 별개 축 유지 · 중복 제출 차단) | 수동 모드 · `markCount=0` · `submitting` | 렌더 | 버튼 이름이 `저장 중...` 이고 **disabled**. 0건 비활성만 걷어낸 것이며 in-flight 재클릭 차단은 유지된다 | component | High | features/marking/components/MarkingToolbar.tsx(`MarkingToolbar` — `disabled = submitting \|\| ...`) · features/marking/components/\_\_tests\_\_/MarkingToolbar.test.tsx(`저장중에는_완료버튼이_비활성이다_마크0건_활성화와_별개축`) |
| TC-FE-560 | 저장 중 비활성은 **마크가 있어도 동일**하다 (신설 · 경계) | 수동 모드 · `markCount=3` · `submitting` | 렌더 | 동일하게 disabled. 저장 중 비활성이 마크 건수와 무관한 축임을 고정한다 | component | Med | features/marking/components/MarkingToolbar.tsx(`MarkingToolbar`) · features/marking/components/\_\_tests\_\_/MarkingToolbar.test.tsx(`저장중_비활성은_마크가_있어도_동일하다`) |
| TC-FE-561 | 마크 1건 이상이면 **안내 없이 정상 제출**된다 (신설 · 역방향 가드) | 수동 마킹 1건 | 완료 버튼 클릭 | POST **1회** 발생 + 0건 안내 토스트는 **뜨지 않는다**. 0건 안내가 정상 제출까지 막으면 기능이 죽는다 | component | High | pages/MarkingPage.tsx(`MarkingPage` — `handleSubmit`) · pages/\_\_tests\_\_/MarkingPage.test.tsx(`수동모드_마크1건이상이면_안내없이_정상_제출된다`) |
| TC-A11Y-017 | 마크 0건 안내가 `role="alert"` 로 읽힌다 (신설 · a11y · 활성 유지의 전제) | 수동 모드 · 0건 · 토스트 표시층(`ToastProvider`)까지 함께 렌더 | 완료 버튼 클릭 | `role="alert"` 요소의 텍스트가 `재생하며 마킹을 1건 이상 쌓아 주세요.`. **버튼을 항상 활성으로 두는 근거가 이 낭독**이라, 이 단언이 빠지면 시각 사용자에게만 사유가 전달되는 상태로 되돌아간다 | a11y | High | components/common/Toast.tsx(`Toast` — `role="alert"` · `aria-live="polite"`) · components/common/ToastProvider.tsx(`ToastProvider` — 표시층) · pages/\_\_tests\_\_/MarkingPage.test.tsx(`마크0건_안내는_role_alert_로_읽힌다`) |

## H-45. 라벨링 캔버스 — 팬 도구 컴포넌트 폐지(도구 열거값·표시명은 유지) — 2026-08-09 신설

> **구 동작 → 폐기**: `features/label/canvas/tools/` 에 아이콘 하나만 그리는 팬 도구 컴포넌트가 있었으나 **참조가 0건**이었다(실측). 파일을 두지 않는다.
> **도구 열거값과 표시명은 유지한다** — 폐기 대상은 그 컴포넌트뿐이다. 포털 업로드 라벨링 화면이 그 열거값으로 `이동` 버튼을 **실제로 렌더**하므로(자체 아이콘 사용) 열거값을 함께 지우면 그 화면의 도구가 사라진다.
> **화면 이동 조작 자체는 캔버스 셸이 담당한다** — 스페이스 홀드 + 드래그, 중클릭 드래그. 컴포넌트 폐지가 조작을 없앤 것이 아니다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-562 | 팬 도구 **컴포넌트**를 두지 않되 열거값·표시명·조작은 유지한다 (신설 · 폐기 경계) | 라벨링 코어 + 포털 업로드 라벨링 | 코드 실측 | ①전용 팬 도구 컴포넌트 파일·심볼이 **없다**(참조 0건이라 폐기) ②열거값 `ToolType.PAN` 과 표시명 `화면 이동` 은 **남는다** ③포털 업로드 화면이 그 값으로 `이동` 버튼을 렌더한다 ④캔버스 이동은 스페이스+드래그·중클릭 드래그로 계속 동작한다 | component | Med | features/label/types.ts(`ToolType.PAN` · `TOOL_DISPLAY_NAME`) · pages/portal/PortalUploadLabelingPage.tsx(`UPLOAD_TOOLS`) · features/label/canvas/CanvasShell.tsx(스페이스 홀드·중클릭 팬 핸들러) |

## H-46. 테스트 목 — 자식 전달 계약 가드(react-konva 67벌 + recharts 공용) — 2026-08-09 신설

> **왜 신설했나**: 캔버스 계열 테스트 **67개 파일이 `react-konva` 목을 각자 복제**해 두고 자식 트리를 통과시키는데, **그 계약을 검증하는 테스트가 한 건도 없었다**(실측). 67벌의 자식 전달을 **동시에 끊고 전체 스위트를 돌리면 4개 파일 18건만 실패**하고 나머지 **63개 파일은 전량 통과**한다 — 대다수가 레이어를 중첩 없이 직접 렌더하기 때문이다. `recharts` 공용 목도 같아, 자식 전달을 끊어도 그 목을 쓰는 통계 화면 테스트가 **전부 통과**했다(38건).
> **즉 지금은 무해하지만 신호가 0이다** — 목을 손대는 사람이 자식 전달을 떨어뜨리면 계약만 조용히 사라지고, 그 뒤 중첩 렌더가 필요한 테스트를 쓰는 사람은 자기 코드가 아니라 목이 원인이라는 데까지 한참을 돌아간다.
> **⚠ "아무것도 안 잡는 것처럼 보이는 것이 정상이다"** — 이 절의 케이스는 평시에 늘 통과한다. 통과한다는 이유로 지우면 위 63개 파일이 계약 파손을 다시 조용히 통과시킨다. 살아 있음은 **양성 대조군**(TC-FE-566)이 증명한다.
> **검사 방식이 축마다 다르다** — konva 목은 **각 테스트 파일 안에 복제**돼 있어 밖에서 실행할 수 없으므로 **소스 스캔**, recharts 목은 **공용 setup 한 곳**이라 실제로 렌더해 **동작 검증**한다(가능한 쪽은 더 강한 검사를 쓴다). 두 방식을 "일관성"을 이유로 통일하지 말 것.
> **가드 자신이 눈머는 것을 막는 장치가 셋** — 스캔 대상 0건이면 실패(TC-FE-563) · 팩토리 추출 실패면 실패(TC-FE-564) · 판정기 양성 대조군(TC-FE-566). "0건 = 위반 없음" 이 **"검사기가 눈이 멀었다"** 와 구분되지 않는 것이 이 저장소에서 이미 세 번 뚫린 지점이다.
> **⚠ 이 가드가 못 보는 것** — konva 축은 **소스 모양만** 본다(실행 시점 조건부 분기는 보지 못하고, 자식을 다른 이름에 담아 넘기는 변형은 오탐한다 — 실패 메시지가 그 사실을 안내한다). `data-konva` 속성명·prop 직렬화 등 나머지 목 계약은 각 테스트가 이미 단언하고 있어 중복 검사하지 않는다. konva 를 쓰지 않는 테스트는 대상이 아니다.
> **후속(근본 해소)**: 목 67벌을 **공유 모듈 한 곳으로 모으는 것**이 근본 해소이며 그때 이 절은 **폐기 대상**이 된다(계약을 공유 모듈이 직접 단언하게 되므로). 67개 파일을 건드리는 별건이라 이번 범위 밖이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-563 | **★검사할 konva 목이 실제로 존재한다 (신설 · 가드 공회전 방지)** | `src` 하위 테스트 소스 전량 | `vi.mock('react-konva'` 마커 스캔 | 스캔 결과가 **1건 이상**. 0건이면 아래 두 검사는 "위반 없음"으로 **항상 통과**하므로, 목이 사라졌거나 마커 표기가 바뀐 상태를 여기서 먼저 드러낸다 | component | High | test/mockChildrenPassthroughGuard.test.tsx(`★검사할_konva_목이_실제로_존재한다_가드_공회전_방지`) |
| TC-FE-564 | **모든 konva 목에서 팩토리를 잘라낼 수 있다 (신설 · 추출 실패 = 검사 사각)** | 동일 | 괄호 균형으로 `vi.mock` 호출 구간 추출 | 추출 실패 파일이 **0건**. 잘라내지 못한 파일은 아래 계약 검사에서 **그냥 빠지므로**(조용한 사각) 실패로 취급한다 | component | High | test/mockChildrenPassthroughGuard.test.tsx(`★모든_konva_목에서_팩토리를_잘라낼_수_있다_추출_실패는_검사_사각이다`) |
| TC-FE-565 | **★모든 konva 목이 자식을 그대로 통과시킨다 (신설 · 핵심)** | konva 목을 가진 테스트 소스 전량 | 각 목 팩토리에서 자식 전달 표기 탐색 | 위반 **0건**. 위반 파일은 **이름과 함께** 드러나고, 실패 메시지가 고치는 법(`createElement('div', props, children)`)과 오탐 시 대처(허용 표기 추가)를 함께 안내한다. ⚠ `{ children, ...rest }` 같은 **구조분해만 하고 넘기지 않는** 형태는 통과로 인정하지 않는다 — 그것이 정확히 잡으려는 결함이다 | component | High | test/mockChildrenPassthroughGuard.test.tsx(`★모든_konva_목이_자식을_그대로_통과시킨다`) |
| TC-FE-566 | 가드가 실제로 위반을 잡는다 (신설 · **양성 대조군**) | 판정기 `hasPassthrough` 단독 | 통과형 3종(`children` 인자 · `children as ReactNode` · JSX `{children}`) / 위반형 2종(인자 없음 · 구조분해만) | 통과형 3종은 `true`, 위반형 2종은 `false`. **판정기 자체를 고정**해 이 절이 "아무것도 안 잡는 테스트"로 굳는 것을 막는다 | component | High | test/mockChildrenPassthroughGuard.test.tsx(`가드가_실제로_위반을_잡는다_양성_대조군`) |
| TC-FE-567 | 괄호가 중첩된 팩토리도 끝까지 잘라낸다 (신설 · 경계 · 구 정규식 구현 폐기) | 팩토리 내부에 중첩 괄호가 있고 그 뒤에 무관한 코드가 이어지는 소스 | 추출 | 추출 결과가 팩토리 마지막 줄을 **포함**하고 팩토리 밖 코드는 **포함하지 않는다**. 정규식으로 끝을 찾던 구현은 중첩 괄호에서 잘려 **검사가 조용히 헛돌았다** | component | Med | test/mockChildrenPassthroughGuard.test.tsx(`괄호가_중첩된_팩토리도_끝까지_잘라낸다`) |
| TC-FE-568 | **★recharts 공용 목이 자식을 그대로 렌더한다 (신설 · 동작 검증 축)** | 공용 setup 의 `ResponsiveContainer` 목 | 자식 1개를 담아 실제 렌더 | 컨테이너와 **자식이 함께** 문서에 있다. 이 목이 자식을 떨어뜨리면 통계 화면 테스트는 **여전히 전부 통과하면서 차트만 사라진다** | component | High | test/setup.ts(`ResponsiveContainer` 목) · test/mockChildrenPassthroughGuard.test.tsx(`★ResponsiveContainer_목이_자식을_그대로_렌더한다`) |

## H-47. dev 업로드 — 이벤트유형코드 입력 (SCREEN-027) — 2026-08-09 신설

> **왜 신설했나**: dev 업로드로 올린 영상이 **적재·비식별까지 완주하고 마킹에서 400** 으로 멈추는 것이 실측(2026-08-09 로컬 드라이브)으로 확인됐다. 결함이 아니라 **입력 부재** — 폼에 이벤트유형코드 입력이 없어 인입 행의 `EVNT_TYPE_CD` 가 항상 비었고, 그 값이 마킹 진입 조건이다. 관제가 이 값을 보내기 전까지 dev 업로드가 파이프라인을 완주시키려면 이 입력이 필요하다.
>
> ★ **검증이벤트유형과 축이 다른 입력이다** — 같은 '이벤트 · 관제일지' 항목 안에 나란히 있지만, 이쪽은 **관제 코드 체계의 유형 식별자**(마킹 진입 조건)이고 저쪽은 **외부 검증 API 의 `event_type`** 이다. 한 입력으로 합치거나 한쪽에서 다른 쪽을 채우지 말 것. `이벤트 ID`(식별자형, 예 `ABA_0001`)와도 다르다 — 화면 힌트가 그 사실을 명시한다.
>
> **프리셋 select 로 좁히지 않는다** — 관제 코드 체계는 우리 소유가 아니고 미등록 코드도 실제로 인입되며 서버가 처음 보는 코드를 마스터에 자동 등록한다. 화면이 목록을 들면 그것이 **두 번째 진실원**이 된다(검증이벤트유형이 회차 25 에서 같은 이유로 6종 allowlist 를 폐기했다).

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-569 | **★이벤트유형코드가 입력되면 그대로 전송된다 (신설 · 핵심)** | dev 업로드 패널 | `EV01000101` 입력 후 업로드 | 세션 생성 바디 `evntTypeCd="EV01000101"`. BE record 에 `@JsonProperty` 가 없어 **자바 필드명이 곧 JSON 키**다. 검증이벤트유형 키는 **함께 실리지 않는다**(축 분리 회귀 가드) | component | High | features/upload/components/TusMetaFieldsets.tsx(`EventFieldset`) · features/upload/__tests__/tusUpload.test.tsx(`★이벤트유형코드가_입력되면_그대로_전송된다_마킹_진입_조건이다`) |
| TC-FE-570 | 소문자로 입력해도 대문자로 전송된다 (신설) | 동일 | `ev01000101` 입력 | `EV01000101` 전송. BE 형식 검증이 대문자·숫자·`_` 라 소문자 그대로면 400 인데, 관제 코드 체계가 대문자 표기라 **소문자 입력은 표기 실수이지 다른 값이 아니다** | component | Med | features/upload/components/tusUploadForm.ts(`toCreateBody`) · features/upload/__tests__/tusUpload.test.tsx(`★이벤트유형코드는_소문자로_입력해도_대문자로_전송된다_표기실수는_다른_값이_아니다`) |
| TC-FE-571 | **★미입력이면 키 자체를 보내지 않는다 (신설 · 관제 미송신 재현)** | 동일 | 아무것도 입력하지 않고 업로드 | 바디에 `evntTypeCd` 키 **부재**. 폼의 다른 선택 필드와 같은 "빈 값 = 키 부재" 관례이며, 빈 문자열을 보내면 BE 형식 검증에 걸린다. 관제 미송신 상태를 그대로 재현할 수 있어야 한다 | component | High | features/upload/components/tusUploadForm.ts(`toCreateBody`) · features/upload/__tests__/tusUpload.test.tsx(`★이벤트유형코드_미입력이면_키_자체를_보내지_않는다_관제_미송신_상태의_재현이다`) |
| TC-FE-572 | 입력이 label 과 연결되고 20자로 제한된다 (신설 · a11y + 경계) | 동일 | 렌더 | `getByLabelText('이벤트유형코드')` 로 도달 가능(label 연결 전제) + `maxLength=20`. 컬럼 폭이 `VARCHAR(20)` 이라 입구에서 막지 않으면 INSERT 시점 DB 오류가 된다 | a11y | Med | features/upload/components/TusMetaFieldsets.tsx(`EventFieldset`) · features/upload/__tests__/tusUpload.test.tsx(`★이벤트유형코드_입력은_label과_연결되고_20자로_제한된다_컬럼폭_VARCHAR(20)`) |

## H-48. 증강 요청 — 유형별 생성 조건 프리필 (SCREEN-022 §처리 종류 선택) — 2026-08-09 신설

> **왜 신설했나**: 외부 위탁 요청 바디에 **증강 유형 필드가 없다.** 유형에 따라 달라질 수 있는 값은 **생성 조건(prompt) 하나뿐**인데, 조건을 사람이 직접 입력하도록 열면서 유형과의 연결이 끊겼다. 그 결과 같은 조건으로 종류만 바꾸면 **바이트 단위로 동일한 요청**이 나가 세 종류를 나눈 의미가 없어졌다.
>
> ★ **프리필은 강제가 아니라 기본값이다** — 검수자가 생성 조건을 조절할 수 있어야 한다는 확정 정책을 되돌리지 않는다. 그래서 ①값을 수정할 수 있고 ②이미 사용자가 손댄 필드는 종류를 바꿔도 보존된다. **서버는 이 축에 관여하지 않는다**(BE 무변경).
>
> ★ **판정 축은 '값'이 아니라 '편집 행위'다** — 빈 값 여부로 판정하면 사용자가 **의도적으로 지운 필드**가 종류 변경 때 되살아난다. 오류 표시용 `touched` 와도 분리한다(그쪽은 입력칸을 지나가기만 해도 켜져 '사용자가 정한 값'의 근거가 되지 못한다).
>
> ★ **역방향(생성 조건 → 증강 유형)은 만들지 않는다** — 자유 문자열이 유형 판정에 흘러가면 산출물 경로·해상도 네임스페이스 판별로 새어 경로 순회가 열린다. 유형의 단일 원천은 카드 선택값이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-573 | 유형을 고르면 그 유형의 축이 채워진다 (신설) | 증강 요청 화면 | 겨울 / 야간 / 우천 카드 선택 | 겨울 → 계절 `겨울`·날씨 `눈` · 야간 → 시간대 `야간` · 우천 → 날씨 `비`. 그 밖의 축(지형·심각도)은 **비어 있다** — 영상마다 다른 값을 시스템이 지어내면 검수자가 확인하지 않은 조건이 외부로 나간다 | component | High | features/augment/types.ts(`AUGMENT_PROMPT_PRESET` · `createPromptPresetFor`) · features/augment/__tests__/AugmentRequestPage.prompt.test.tsx(`유형을_WINTER_로_고르면_계절과_날씨가_채워진다` · `유형을_NIGHT_로_고르면_시간대가_채워진다` · `유형을_RAIN_으로_고르면_날씨가_채워진다`) |
| TC-FE-574 | 유형을 바꾸면 이전 유형의 프리필 잔재가 남지 않는다 (신설) | 겨울 선택 상태(계절 `겨울`) | 야간으로 변경 | 시간대 `야간` · 계절·날씨는 **빈 값으로 갱신**. 사용자가 손대지 않은 값이므로 새 유형 기준을 따른다 — 남겨두면 '야간인데 계절=겨울' 이라는, 사용자가 고른 적 없는 조건이 전송된다 | component | High | pages/AugmentRequestPage.tsx(`handleSelectKind`) · features/augment/__tests__/AugmentRequestPage.prompt.test.tsx(`유형을_바꾸면_이전_유형의_프리필_잔재가_남지_않는다`) |
| TC-FE-575 | **★사용자가 손댄 필드는 유형을 바꿔도 덮어쓰지 않는다 (신설 · 핵심)** | 야간 선택 후 계절에 `초봄` 직접 입력 | 겨울로 변경(겨울 프리필은 계절 `겨울`) | 계절은 `초봄` 유지(사용자 입력이 이긴다) · 손대지 않은 날씨만 `눈` 적용. **의도적으로 지운 필드도 보존** — 겨울 프리필의 날씨를 지운 뒤 우천으로 바꿔도 빈 값이 유지된다("비었으니 채워도 된다"로 판정하면 여기서 되살아난다) | component | High | pages/AugmentRequestPage.tsx(`promptEdited` · `handlePromptChange`) · features/augment/__tests__/AugmentRequestPage.prompt.test.tsx(`사용자가_이미_입력한_필드는_유형을_바꿔도_덮어쓰지_않는다` · `사용자가_의도적으로_지운_필드는_유형을_바꿔도_다시_채우지_않는다`) |
| TC-FE-576 | 프리필된 값을 수정할 수 있고 화면을 오가도 보존된다 (신설) | 겨울 선택(계절 `겨울`) | 계절을 `늦겨울` 로 수정 → 같은 유형 재선택 / 해상도 변경을 거쳐 복귀 | 두 경우 모두 사용자 값 유지. 해상도 변경은 생성 조건 폼 자체가 없으므로(외부 위탁이 아니다) 그 종류에서는 값을 건드리지 않는다 — 비우면 증강으로 돌아왔을 때 입력해 둔 조건이 사라진다 | component | Med | pages/AugmentRequestPage.tsx(`handleSelectKind`) · features/augment/__tests__/AugmentRequestPage.prompt.test.tsx(`프리필된_값을_사용자가_수정할_수_있다` · `해상도_변경을_거쳐도_사용자_입력은_보존된다`) |
| TC-FE-577 | **★세 유형이 서로 다른 생성 조건으로 전송된다 (신설 · 이 변경의 존재 이유)** | 각 유형을 새 화면 진입에서 요청 | 유형과 무관하게 **같은 사용자 입력**(프리필이 비워 둔 칸만 동일 값으로 채움) | 세 요청의 생성 조건이 **서로 다르다**(3쌍 모두). 겨울 → 계절 `겨울` · 야간 → 시간대 `야간` · 우천 → 날씨 `비`. 유형(`types`)은 여전히 카드 선택값이며 생성 조건에서 파생되지 않는다 | component | High | features/augment/types.ts(`AUGMENT_PROMPT_PRESET`) · features/augment/__tests__/AugmentRequestPage.prompt.test.tsx(`세_유형이_서로_다른_prompt_로_전송된다`) |

## H-49. 회색 표면 위 보조 텍스트 대비 — 하한 60단 + 부모→자식 가드 (DS-001 `do_rules`) — 2026-08-09 신설

> **왜 신설했나**: 중립색을 KRDS 정본으로 교체하자 **일부 조합이 어두워지지 않고 밝아졌다.** 보조 텍스트로 가장 많이 쓰이는 `text-gray-500`(neutral 50단)은 **흰 배경에서만 AA 를 통과**(4.51)하고, 회색 표면 위에서는 미달이다(gray-50 위 4.13 · secondary-50 위 4.01 · primary-50 위 4.01). 페이지 배경 자체가 `bg-gray-50` 이라 흰 카드 밖 텍스트는 전부 이 축에 걸린다.
>
> ★ **색값은 조정 대상이 아니다** — KRDS 정본이다. 회피는 **사용 조합**을 바꿔서 한다(한 단계 진한 step). 의미색에서 `text-{color}-700` 으로 교정한 것과 같은 방식이다.
>
> ★ **"일괄 500→600" 으로 끝나지 않는다** — `bg-gray-200` 위는 **60단으로도 미달**(4.10)이라 70단이 필요하다. 실제로 라벨링 캔버스(`bg-gray-200`)가 이 함정에 걸려 있었다.
>
> ★ **통과하는 조합은 건드리지 않는다** — 흰 배경 위 단독 `text-gray-500`(4.51)은 그대로 둔다. 과잉 검출은 변경 범위만 키우고 회귀 위험을 만든다.
>
> ⚠ **이 축의 회귀는 브라우저 실측으로만 발견됐다** — 기존 가드는 **같은 className 문자열 안**의 조합만 봤는데, 실제 위반은 **전부** 배경과 글자색이 다른 요소에 있었다(표 헤더는 `<tr>` 이 배경, `<th>` 가 글자색). 같은 줄 스캔은 한 건도 잡지 못했다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|------|
| TC-FE-578 | **★보조 텍스트 하한은 60단이다 (신설 · DS-001 v8 규칙을 값으로 고정)** | 중립색 토큰 | 대비 계산 | 50단은 흰 배경 4.51(경계 통과)·gray-50 위 4.13·secondary-50 위 4.01·primary-50 위 4.01 로 **회색 표면에서 전부 미달**. 60단은 네 배경 모두 통과(5.13~6.30). 산문 규칙은 되돌려지므로 수치를 소수 둘째 자리까지 못박는다 | unit | High | src/test/contrastGuard.test.ts(`★DS_001_v8_보조텍스트_하한_60단_규칙이_값으로_성립한다`) |
| TC-FE-579 | `bg-gray-200` 위는 60단으로도 부족해 70단이 필요하다 (신설 · 함정 고정) | 회색 표면 중 가장 진한 단계 | 대비 계산 | gray-600 on gray-200 = **4.10(미달)** · gray-700 on gray-200 = 5.65(통과). "일괄 500→600" 으로 끝내면 이 조합이 남는다 | unit | High | src/test/contrastGuard.test.ts(`★연회색_배경별_최소_안전_전경_단계가_고정된다`) |
| TC-FE-580 | **★부모→자식(요소 경계를 넘는) 조합을 스캔한다 (신설 · 핵심)** | `.tsx` 전수 | 조상 체인 해석 | 조상 여는 태그의 **여러 줄 `className` 속성 구간까지** 읽어 배경을 찾는다(`cn(...)` 멀티라인 포함). 조상의 속성 줄은 조상 태그보다 **더 깊이** 들여쓰기되므로 단순 들여쓰기 역추적만 하면 조상 자신의 배경을 놓친다 — 선택된 KPI 카드가 실제로 이 방식으로 새어 나갔다 | unit | **Critical** | src/test/contrastGuard.test.ts(`★조상_요소의_연한_배경_위_text_gray_500_이_한_건도_없다` · `★스캔기_자체_검증_조상의_멀티라인_className_배경을_실제로_읽는다`) |
| TC-FE-581 | 이미 닫힌 형제의 배경을 상속하지 않는다 (신설 · 오탐 방지) | 트랙(`bg-gray-200`)과 퍼센트 라벨이 형제인 진행률 바 | 조상 체인 해석 | 자식 범위(`scopeEnd`)를 추적해, 닫힌 형제 요소의 배경을 물려받지 않는다. 추적하지 않으면 진행률 라벨을 트랙 안으로 오인해 오탐이 난다(실제로 났다) | unit | High | src/test/contrastGuard.test.ts(`★스캔기_자체_검증_이미_닫힌_형제의_배경을_상속하지_않는다`) |
| TC-FE-582 | 흰 배경 조상은 안전으로 판정한다 (신설 · 과잉 검출 방지) | 흰 카드 안의 보조 텍스트 | 조상 체인 해석 | 가장 가까운 배경이 `bg-white` 면 안전으로 보고 더 올라가지 않는다. `Card`·`Modal`·`Drawer`·`Popover` 는 자체적으로 흰 표면을 렌더하므로 흰 배경으로 취급 | unit | High | src/test/contrastGuard.test.ts(`★스캔기_자체_검증_흰_배경_조상은_안전으로_판정한다`) |
| TC-FE-583 | 스캔 축에 `bg-primary-50` 이 포함된다 (신설 · 스캔범위 자기검증) | 선택된 KPI 카드·선택된 목록 행 표면 | 정규식 매칭 | 축을 빼면(구 상태 복귀) 매칭이 0건이 되어 실패한다. 이 축이 없어서 선택 상태 회귀를 **가드가 구조적으로 못 보고** 실측으로만 잡혔다 | unit | High | src/test/contrastGuard.test.ts(`★스캔이_primary_50_축을_실제로_판정한다_스캔범위_자기검증`) |
| TC-FE-584 | 단계를 고정하지 않고 **실제 대비를 계산**한다 (신설 · 가짜 가드 방지) | 본문 글자 단계 500~950 | 가드 실행 | `text-gray-500` 만 보면 "일괄 500→600" 이후 `bg-gray-200` 조합이 남는데도 초록이 된다. 단계를 포착해 실값으로 계산하므로 60단이 미달인 배경도 잡는다. 300/400 은 플레이스홀더·비활성·장식 용도라 AA(1.4.3) 대상이 아니다 | unit | High | src/test/contrastGuard.test.ts(`TEXT_GRAY` · `BODY_TEXT_STEPS`) |
| TC-FE-585 | 예외는 사유가 적힌 것만 허용한다 (신설 · 가드 부식 방지) | 분기 배타성 오탐 | 예외 목록 검사 | 사유 30자 미만이거나 대상 코드가 이미 사라진 예외가 있으면 실패한다. 현재 예외 1건 — 배경이 `bg-primary-50` 이 되는 `selected` 분기에서 글자가 `text-primary-600` 이라 두 색이 동시에 렌더되지 않는 경우 | unit | Med | src/test/contrastGuard.test.ts(`★예외목록은_사유가_적힌_것만_허용한다`) · features/augment/components/ProcessKindCard.tsx |

> **가드가 못 보는 범위(정직성 명시)** — ①**파일을 넘는 조합**(부모가 다른 파일에서 배경을 주는 경우) ②**페이지 배경**(`AppLayout` 의 `bg-gray-50`. 조상 체인이 파일 안에서 끝난다) ③**분기 조건의 의미**(배타 분기 → 예외 목록으로 처리) ④`style={{ backgroundColor }}` 등 **런타임 배경** ⑤`.tsx` 만 스캔.
> ①②④ 는 구조적으로 정적 스캔 밖이며 **브라우저 시각 회귀 검사의 몫**이다 — 이 가드가 초록이라고 "대비 회귀 없음"이 증명되지 않는다.
