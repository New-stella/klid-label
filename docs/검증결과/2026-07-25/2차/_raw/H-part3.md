# H 클러스터 part3 (H-3 LabelingPage) 2차 검증 결과

> 검증 대상: `docs/test-cases/H-frontend-e2e.md` `## H-3. LabelingPage (라벨링 캔버스)` — TC-FE-033~087(55건) + TC-FE-197~202(신규 6건) = **61건**
> 방법: 소스 실측(Read/Grep, `frontend/src/pages/label/LabelingPage.tsx` 등) + BE 실호출(dev 토큰, 라이브 DB, `localhost:18081` HEAD `ca3c712b`) + 테스트 커버 확인(`_raw/test-baseline.md` 1691건 GREEN 대조, 실행 안 함)
> 폐기(취소선) 행: H-3 섹션 내 0건(전건 검증 대상)
> 브라우저 자동화 도구 없음 — 이 섹션의 61건은 전부 상태/로직 단언(캔버스 픽셀 렌더링 케이스 없음)이라 정적 코드 대조 + 기존 jsdom 컴포넌트 테스트(vitest, baseline 통과 확인) + BE 실호출로 판정 가능했다. **BLOCKED 0건.**

## 집계

| 섹션 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| H-3 | 61 | 60 | 0 | 1 | 0 | 0 | 0 |

- PARTIAL 1건(TC-FE-056) — SAM2 Track mock-blind 자동적용(C-ISSUE-61)의 FE 종단 확인. 상세는 아래 전용 절 + 이슈 상세.
- self-fill 의심: 0건. 프레임 이미지·라벨·SAM2 track 전부 BE 응답을 그대로 반영(자체 채움 없음).
- 검증 중 BE 상태변경 호출 2건 수행 — ①`PUT /v1/frames/65/labels`(고의로 낡은 `labelVersion=1` 전송, BE 409 확인) ②`POST /v1/labels/68/deident-report`(파생영상 신고 시도, BE 412 확인). **둘 다 BE 가 요청을 거부**해 실제 데이터 변경 없음(사후 `labelVersion` 재조회로 불변 확인). 다른 에이전트가 참조 중인 rawSn=126/129 데이터는 원상 유지됨.

## ★mock 자동적용 차단 실측 (Segment vs Track 비대칭 — C-ISSUE-61 FE 종단 확인)

C-part4.md `C-ISSUE-61`(HIGH)이 지적한 "ai-server SAM2 Track mock 폴백 신호가 BE→FE 4계층에서 소멸"을 화면(LabelingPage) 관점에서 이어서 확인했다.

| 경로 | Detect/Segment(일반) | Track(추적) |
|---|---|---|
| FE 처리 코드 | `LabelingPage.tsx:713-716` — `if (res.message) { pushToast(warning); return; }`(mergeAutoLabels 미호출, 자동적용 차단) | `LabelingPage.tsx:634-677 handleTracked` — **mock/message 분기 없음**. `forCurrent`/`forFuture` 를 무조건 `mergeAutoLabels`/`stashPendingTracks` 하고, `partial===false` 면 항상 `"AI 추적 완료 (N프레임)"` **성공** 토스트(`:664-666`) |
| 대응 테스트 | `LabelingPageAutolabelToast.test.tsx:98 message있으면_mock경고_토스트로_자동적용_차단` — 존재 | **grep 0건** — `handleTracked`/`onTracked`/"mock"+"추적" 조합을 검증하는 테스트가 프로젝트 전체에 없음(`grep -rln "handleTracked" **/*.test.tsx` → 0) |
| 근거 | `runAiTool` 의 `res.message` 분기가 BE `ApiResponse.message`(모델 미로드 시 세팅)를 신뢰해 게이팅 | `useSam2Track`(`hooks/useSam2Track.ts`)의 `Sam2TrackedItem`/`Sam2TrackResponse` 타입에 `mock`/`score` 신뢰도 필드 자체가 없음(C-part4 실측: BE DTO `Sam2TrackResponse.java`가 3필드 미선언 → Jackson 이 ai-server 의 `mock`/`source`/`mock_reason` 을 조용히 버림) — FE 는애초에 판단할 재료를 받지 못한다 |

**결론**: C-ISSUE-61 의 배선 누락(BE DTO 필드 미선언)이 FE 까지 그대로 전파되어, 라벨링 화면에서 **가짜(mock fallback) 추적 결과가 실추론과 동일하게 병합되고 "AI 추적 완료" 성공 토스트가 뜬다.** 사용자는 신뢰할 수 없는 결과를 신뢰할 수 있는 결과로 오인한다. → `H-ISSUE-41`(TC-FE-056 PARTIAL 로 반영, 근본 수정은 BE `Sam2TrackResponse` DTO 필드 추가가 선행돼야 하므로 C-ISSUE-61 과 동일 근본원인으로 묶어 기록).

## H-3 결과표 (61건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|---|---|---|
| TC-FE-033 | 잘못된 ID(NaN) 다크 에러 | PASS | [정적] `LabelingPage.tsx:926-944` `Number.isNaN(numericId)` → "잘못된 프레임 ID" + 뒤로가기 버튼 | 근거 드리프트(카탈로그 911-930 → 실제 926-944, +15) |
| TC-FE-034 | 로딩 상태 스피너 | PASS | [정적] `:947-960` `isLoading` → `Spinner label="라벨 로딩"` + "라벨 로딩 중..." | 드리프트(932-945→947-960) |
| TC-FE-035 | 포털 403 → graceful 차단화면 | PASS | [정적] `:965-992` `isPortalForbidden`(status===403 \|\| errorCode==='FORBIDDEN') → "접근할 수 없는 영상입니다" | 드리프트(950-977→965-992) |
| TC-FE-036 | 일반 에러 → "라벨 조회 실패" | PASS | [정적] `:994-1014` 일반 error 분기 → "라벨 조회 실패" + `{error.message}`(=BE message, `extractBeMessage` 아닌 원본 노출이나 GlobalExceptionHandler 가 내부정보 미노출 보장) | 드리프트(979-999→994-1014) |
| TC-FE-037 | siblings 비면 현재 프레임 단건 폴백 | PASS | [정적] `:220-247` `siblings.length===0` → 현재 프레임 1건 배열 반환 | 드리프트(202-219→220-247) |
| TC-FE-038 | 저장 성공 토스트 "저장됨" | PASS | [정적] `:507-541 handleSave` → `updateLabels`+`clearDirty`+`pushToast('저장됨')`(:520-525). [실동작] `PUT /v1/frames/65/labels` 로 BE 계약(`items`+`labelVersion`) 실측(아래 TC-FE-197 절 참조) | 드리프트(492-510→507-541) |
| TC-FE-039 | 저장 중복 제출 차단 | PASS | [정적] `:511` `if (saving) return;`(useMutation isPending 가드) | 드리프트(496→511) |
| TC-FE-040 | 잠금 영상 저장 차단 | PASS | [정적] `:512-518` `isLocked` → 에러 토스트, `updateLabels` 미호출(return) | 드리프트(497-503→512-518) |
| TC-FE-041 | 저장 실패 에러 토스트(BE 문구 우선) | PASS | [정적] `:536-539` `extractBeMessage(e,'저장 실패')`. `lib/api/extractBeMessage.ts` 확인 — `userMessage`→`message`→axios raw→fallback 순 | 드리프트(521-525→536-539) |
| TC-FE-042 | 포털 모드 저장 경로 분기 | PASS | [정적] `:493-506` `updateLabels = portalMode ? savePortalLabels : updateInternalLabels` | 드리프트(486-491→493-506) |
| TC-FE-043 | 프레임 이동 dirty 가드 모달 | PASS | [정적] `:307-317 requestJumpTo` → `dirtyCount>0` 시 `setNavGuardTarget(idx)`(FrameNavGuardModal 트리거) | 드리프트(292-302→307-317) |
| TC-FE-044 | 같은 프레임 이동 no-op | PASS | [정적] `:311` `if (target.srcSn === data.srcSn) return;` | 드리프트(296→311) |
| TC-FE-045 | 저장 후 이동 — 실패 시 취소 | PASS | [정적] `:319-342 handleNavSaveAndMove` — catch 시 에러 토스트 + `setNavGuardTarget(null)`(이동 미실행, 현재 프레임 유지) | 드리프트(304-327→319-342) |
| TC-FE-046 | 저장 안 함 이동 — dirty 폐기 | PASS | [정적] `:344-350 handleNavDiscardAndMove` — `clearDirty()` 후 `performJump` | 드리프트(329-336→344-350) |
| TC-FE-047 | 프레임 전환 시 setLabels 전체 교체 | PASS | [정적] `:364-377` `frameChanged` 시 `setLabels(nextLabels)` | 드리프트(348-362→364-377) |
| TC-FE-048 | 같은 프레임 refetch는 dirty 있으면 미덮음 | PASS | [정적] `:373-376` `useLabelStore.getState().dirtyLabels.size===0` 일 때만 `setLabels` | 드리프트(355-361→373-376) |
| TC-FE-049 | 보류 추적 drain 병합 | PASS | [정적] `:383-396` `drainPendingTracks`+`mergeAutoLabels`+`pushToast('보류된 AI 추적 N건 적용됨')` | 드리프트(364-381→383-396) |
| TC-FE-050 | 언마운트 시 store reset | PASS | [정적] `:399-403` `useEffect(() => () => reset(), [reset])` | 드리프트(384-388→399-403) |
| TC-FE-051 | AI 탐지 팝업 매핑 라벨만 선택 | PASS | [정적] `AiToolModal.tsx:148(mappedCandidates),184(canRun),270("미매핑")` — 근거 그대로 일치(드리프트 없음). [테스트] `AiToolModal.test.tsx:33 미매핑_라벨은_표시되지만_선택_불가`, `:70 매핑된_라벨이_없으면_일반_실행이_비활성이다` | |
| TC-FE-052 | AI 탐지 mock 응답 자동적용 차단 | PASS | [정적] `LabelingPage.tsx:712-716` `if (res.message) { pushToast(warning); return; }`(mergeAutoLabels 미호출). [테스트] `LabelingPageAutolabelToast.test.tsx:98 message있으면_mock경고_토스트로_자동적용_차단` | 드리프트(698-701→712-716) |
| TC-FE-053 | AI 탐지 결과 작업본 병합(중복 스킵) | PASS | [정적] `:717-723` `mergeAutoLabels(detected)` + `"${kind} ${added}건 적용됨"`. [테스트] `LabelingPageAutolabelMerge.test.tsx:120 오토라벨_직후_PUT저장이_호출되지_않는다_그리고_기존라벨_유지한채_병합` | 드리프트(706-708→717-723) |
| TC-FE-054 | 폴리곤 shape → "AI 분할" 라벨 | PASS | [정적] `:722` `shape === 'POLYGON' ? 'AI 분할' : 'AI 탐지'` | 드리프트(707→722) |
| TC-FE-055 | 트랙 모드 — 객체 선택 유도 | PASS | [정적] `:688-706` `mode==='track'` → `setActiveTool(TRACK)`+`setTrackShape`+안내 토스트("추적할 객체를 선택한 뒤...") | 드리프트(673-690→688-706) |
| TC-FE-056 | 추적 결과 현재/미래 프레임 분리 | PARTIAL | [정적] `:634-677 handleTracked` — 기대결과(현재=즉시병합, 미래=stash) 자체는 정확히 일치. 단 **mock/신뢰도 분기가 전혀 없이 무조건 병합 + 성공 토스트**(`C-ISSUE-61` FE 종단 확인, 위 전용 절 참조) | 카탈로그 기대결과는 충족하나 도메인 안전성 결함을 노출하는 케이스라 PARTIAL로 하향. `H-ISSUE-41` 참조. 드리프트(619-660→634-677) |
| TC-FE-057 | 부분 추적 실패 경고 | PASS | [정적] `:658-663` `partial` 시 `"${applied}/${total} 프레임만 추적됨 (일부 실패)"` | 드리프트(643-648→658-663) |
| TC-FE-058 | nextSrcSns 계산(현재 이후) | PASS | [정적] `:624-627` `frames.slice(frameIdx + 1).map(f => f.srcSn)` | 드리프트(608-612→624-627) |
| TC-FE-059 | SAM2 추적 청크 50개 상한 정합 | PASS | [정적] `api.ts:612 SAM2_TRACK_CHUNK_SIZE=50`, `:698-701` 청크 분할. [테스트] `sam2-track.test.tsx:96 상수는_BE_Size_상한과_정합한다`, `:100 120개_후속프레임은_50/50/20/3청크로_분할되고_폴리곤이_체인된다`, `:179 정확히_50개_후속프레임은_단일_청크` | BE `@Size(max=50)` 는 C-part4/C-part5 에서 실호출로 확정(51→400/50→200) |
| TC-FE-060 | SAM2 추적 stale 가드(프레임 전환) | PASS | [정적] `useSam2Track.ts:59` `if (context?.requestedSrcSn !== currentSrcSnRef.current) return;` | |
| TC-FE-061 | SAM2 부분 실패 성공분만 병합 | PASS | [정적] `useSam2Track.ts:62-69` `Sam2TrackChunkError` catch → `!stale && err.partial.length>0` 이면 `onTracked(err.partial, true)` | |
| TC-FE-062 | BBOX 추적 시드 외접박스 4점 확장 | PASS | [정적] `api.ts:620-639 toSeedPolygon` — 2점→4점 폐곡선(min=3 검증 대응). [테스트] `sam2-track.test.tsx:427 BBOX_추적_50프레임초과시_2번째청크_prevPolygon이_4점폐곡선으로_확장된다` | |
| TC-FE-063 | 트랙 rename/삭제/분할 포털 차단 | PASS | [정적] `LabelingPage.tsx:738,759,783` 3개 핸들러 선두 `if (portalMode) return;` | 드리프트(721,745,769→738,759,783, +17) |
| TC-FE-064 | 잠금 영상 트랙 편집 차단 | PASS | [정적] `:741,762,786` 각 핸들러 `isLocked` 체크 → 에러 토스트 + return | 드리프트(726,747,771→741,762,786, +15) |
| TC-FE-065 | 트랙 rename 성공 후 invalidate | PASS | [정적] `:745-748` `mergeTracks` 성공 시 `LABEL_KEYS.byVideo(rawSn)` invalidate + 성공 토스트 | 드리프트(731-737→745-748) |
| TC-FE-066 | 비식별 신고 성공 → 잠금+reset+무효화 | PASS | [정적] `:480-488 handleDeidentReportSuccess` — `setReportedLock(true)`+`reset()`+`invalidateQueries(LABEL_KEYS.byVideo(srcSn))`. [실동작] `POST /v1/labels/68/deident-report`(파생영상, 아래 TC-FE-200/201 절)로 신고→412 게이트 경로 확인. 재조회 시 `GET /v1/frames/{srcSn}/labels` 는 신고 게이트로 412 가 되어 `:994-1014` 일반 에러 분기("라벨 조회 실패"+BE 안내문)로 렌더 — 카탈로그가 명시한 "빈 라벨 화면 아님" 요건 충족 | ⚠ 문서 드리프트(비이슈): 컴포넌트 주석(`:472-474`)이 "BE 는 신고 접수 시 라벨을 전체 삭제하고 잠근다"고 서술하나 CLAUDE.md 최신 정책(2026-07-27)은 "라벨 보존, 조회만 412 차단"이다. 실제 동작(reset+invalidate→412 재조회)은 최신 정책과 정합하므로 기능 결함 아님, 주석만 낡음 |
| TC-FE-067 | 잠금 배너 노출 | PASS | [정적] `:1094-1104` `isLocked` → `role="status"` 배너 "비식별 재처리 중인 영상입니다..." | 드리프트(1079-1090→1094-1104) |
| TC-FE-068 | 비식별 신고 버튼 — RAW 프레임 disabled | PASS | [정적] `:1050` `disabled={isLocked \|\| data.frameImageType === 'RAW'}` | 드리프트(1031-1040→1046-1055) |
| TC-FE-069 | 비식별 신고 버튼 포털 미노출 | PASS | [정적] `:115` `canReportDeident = !portalMode && (isWorker \|\| isReviewer)`(라인 정확 일치), `:1046-1054` `canReportDeident && ... ? <DeidentReportButton/> : null` | 115는 드리프트 없음, 1031-1032→1046-1054 |
| TC-FE-070 | 검수제출 버튼 WORKER만 | PASS | [정적] `:1056-1091` `isWorker && data ? (...) : null` | 드리프트(1041-1042→1056-1091) |
| TC-FE-071 | 상태별 제출 차단(REVIEW_PENDING/REVIEWING) | PASS | [정적] `:167-199` `SUBMITTABLE_STATUSES`+`submitBlockedByStatus`, 버튼 `disabled={... \|\| submitBlockedByStatus}`(:1075) + `title={submitStatusHint}` | 드리프트(151-176→167-199) |
| TC-FE-072 | APPROVED 재검수 라벨 | PASS | [정적] `:188` `isResubmitOfApproved = workStatus === 'COMPLETED'` → `:189` "재검수 제출" | |
| TC-FE-073 | 제출 취소 버튼 REVIEW_PENDING만 | PASS | [정적] `:186` `canCancelSubmit = isWorker && workStatus === 'REVIEW_PENDING'`, `:1059-1071` 조건부 렌더 | 드리프트(171,1044→186,1059) |
| TC-FE-074 | 검수제출 성공 → /task 이동+토스트 | PASS | [정적] `:154-160` `onSuccess: () => { pushToast('검수 제출 완료'); navigate('/task'); }` | 드리프트(139-145→154-160) |
| TC-FE-075 | X 닫기 dirty 시 3옵션 모달 | PASS | [정적] `:809-815 handleClose` → `dirtyCount>0` 시 `setCloseConfirmOpen(true)`, 모달 3버튼(`:1109-1143`: 취소/저장 없이 닫기/저장 후 닫기) | 드리프트(792-800→809-815) |
| TC-FE-076 | beforeunload dirty 경고 | PASS | [정적] `:846-855` `dirtyCount>0` 이면 `beforeunload` 리스너 등록, `e.preventDefault()`+`returnValue=''` | 드리프트(831-839→846-855) |
| TC-FE-077 | 우측 탭 — 메타/이슈 내부 채널만 | PASS | [정적] `:428` `showMeta = !portalMode`, `:426` `showIssues = !portalMode && issueRawSn!==undefined`, 탭 렌더 `:1232-1301` 조건부 | 드리프트(406→428, 1205-1270→1232-1301) |
| TC-FE-078 | 이슈 탭 미해소 배지 카운트 | PASS | [정적] `:1289-1297` `unresolvedInquiries>0` → `data-testid="issue-tab-badge"` + `aria-label="미해소 문의 N건"` | 드리프트(1260-1266→1289-1297) |
| TC-FE-079 | 메타 탭 — 촬영환경/개인정보/설명/VLM/이벤트 패널 | PASS | [정적] `:1321-1330` `EnvironmentMetaPanel`+`FramePrivacyMetaPanel`+`FrameDescriptionPanel`+`TimeseriesSidePanel`+`EventAnnotationPanel` 5개 확인 | 드리프트(1290-1310→1313-1331) |
| TC-FE-080 | 뷰(zoom/pan) 유지 vs 리셋 | PASS | [정적] `:277-289 handleImageSize` → `shouldResetView(viewKeyRef.current, next)` false 면 `resetView()` 미호출(유지) | 드리프트(262-274→277-289) |
| TC-FE-081 | 붙여넣기 실측 dims clamp | PASS | [정적] `:890-908 onPasteLabels` → `imageWidth: frameNaturalSize?.width, imageHeight: frameNaturalSize?.height` | 드리프트(875-890→890-908) |
| TC-FE-082 | 복사 — 빈 선택 no-op 토스트 | PASS | [정적] `:881-888` `n===0` → `"복사할 라벨이 없습니다."` | 드리프트(866-872→881-888) |
| TC-FE-083 | 잠금 영상 붙여넣기 차단 | PASS | [정적] `:890-894` `isLocked` → 에러 토스트 + return(paste 미실행) | 드리프트(876-879→890-894) |
| TC-FE-084 | 저장 되돌리기 확인 모달 | PASS | [정적] `:556-558 handleRevertRequest` → `setRevertTarget(item)`, `ConfirmDialog`(`:1426-1434`) `open={revertTarget!==null}` | 드리프트(541-545→556-558) |
| TC-FE-085 | 되돌릴 항목 없음 경고 | PASS | [정적] `:561-581 confirmRevert` → `reverted===0` 시 `pushToast('되돌릴 항목이 현재 작업본에 없습니다.')` | 문구가 카탈로그 표기("되돌릴 항목이 없습니다")와 정확 문자열은 다르나 의미 동일(카탈로그 축약 표기로 판단, 결함 아님). 드리프트(546-566→561-581) |
| TC-FE-086 | 캔버스 lazy 마운트(konva 분리) | PASS | [정적] `:74-76` `lazy(() => import('.../CanvasShell')...)`(드리프트 없음), `:1190-1211` `<Suspense fallback={<Spinner label="캔버스 로딩"/>}>` | 1175-1196→1190-1211 |
| TC-FE-087 | 히스토리 인라인 패널 내부만 | PASS | [정적] `:1398-1410` `historyOpen && !portalMode && data?.srcSn!==undefined` 조건부 렌더 | 드리프트(1369-1381→1398-1410) |
| TC-FE-197 | 저장 409 → 충돌 다이얼로그(작업 보존) (신규) | PASS | [정적] `:530-535` `e instanceof ApiError && e.status===409` → `setSaveConflictMessage(extractBeMessage(...))`(dirty 미소거), `ConfirmDialog`(`:1414-1423`) confirm=`handleReloadAfterConflict`(clearDirty+refetch), cancel=`setSaveConflictMessage(null)`(dirty 유지). [실동작] `srcSn=65`(rawSn=126) 현재 `labelVersion=2` 확인 → `PUT /v1/frames/65/labels`에 고의로 `labelVersion=1` 전송 → **실측 409** `{"errorCode":"CONFLICT","message":"다른 사용자가 먼저 저장했습니다. 최신 라벨을 불러온 뒤 다시 저장하세요."}`(BE 메시지가 FE fallback 문구와 문자열까지 일치). 재조회로 `labelVersion` 여전히 2(데이터 불변) 확인 | 신규 기능, BE-FE 계약 실측 완료 |
| TC-FE-198 | 저장 요청에 labelVersion 동봉 (신규) | PASS | [정적] `api.ts:296-307 putLabels` — `labelVersion != null` 일 때만 필드 포함(생략 시 하위호환 skip). [실동작] 위 TC-FE-197 실호출로 `labelVersion` 필드가 실제 전송·BE 인식됨을 확인 | |
| TC-FE-199 | 연속 저장 시 캐시 버전 우선(자기 409 방지) (신규) | PASS | [정적] `useUpdateLabels.ts:50-70` — `mutationFn` 이 렌더 클로저 `options.labelVersion` 대신 `qc.getQueryData(...).labelVersion` 우선 사용, `onSuccess` 가 `setQueryData` 로 즉시 캐시 갱신(동기). [테스트] `useUpdateLabels.test.tsx:98 H12_같은_사용자의_연속_저장이_자기자신과_409가_나지_않는다 — 새_labelVersion_즉시반영` | |
| TC-FE-200 | 파생영상 — 비식별 신고 버튼 사전 비활성 (신규) | PASS | [정적] `LabelingPage.tsx:127-132` `videoDetail?.derivative` → `deidentReportUnsupportedReason` 설정(원본 유도 없음, 부모 rawSn 미표시), `:1046-1054` `unsupportedReason` prop 전달. `DeidentReportButton.tsx:136-140` `disabled={... \|\| Boolean(unsupportedReason)}` + `title={unsupportedReason}`. [실동작] `GET /v1/videos/129` → `"derivative": true` 확인(WINTER 증강 파생, 부모 rawSn=126). [테스트] `DeidentReportButton.test.tsx:173 파생영상이면_버튼이_비활성화되고_사유가_툴팁으로_보인다` | |
| TC-FE-201 | 신고 412 — 서버 안내문 그대로 노출 (신규) | PASS | [정적] `DeidentReportButton.tsx:107-116` `status===412` → `setServerError(resolveApiMessage(e, fallback))`(BE 안내문 그대로). [실동작] `srcSn=68`(rawSn=129, derivative) 로 `POST /v1/labels/68/deident-report` 실제 호출 → **실측 412** `{"errorCode":"PRECONDITION_FAILED","message":"이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상(증강·해상도 변환본)이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다."}` — 부모 rawSn 미노출 확인. [테스트] `DeidentReportButton.test.tsx:185 412_응답시_서버_안내문이_그대로_노출된다` (테스트의 mock 메시지가 실측 BE 메시지와 동일) | |
| TC-FE-202 | 이벤트 어노테이션 cot 객체형 정규화 (신규) | PASS | [정적] `api/eventAnnotation.ts:26-29 normalizeCot` — 배열/객체 양형→배열, `Object.values()`로 키 순서 유지. `components/eventAnnotationForm.ts:59-68 toCaptionRows` — `COT_STEPS`(3) 고정 배열로 정규화. [테스트] `eventAnnotationForm.test.ts:15 객체형_cot_값을_키순서대로_배열화`, `:27 객체형_cot_도_3단계_폼행으로_정규화` | |

## 근거 드리프트

전 항목이 아래 **체계적 오프셋**으로 설명된다(개별 결함 아님) — `LabelingPage.tsx` 상단부에 파생영상 비식별 신고 판정 블록(`useVideoDetail`+`deidentReportUnsupportedReason`, 2026-07-30 회차 추가, 약 10줄)과 우측 패널 렌더 영역의 이슈 탭·배지 코드가 추가되며 파일 전체가 누적 밀렸다.

| 구간(카탈로그 기준 라인) | 오프셋 | 대상 케이스 |
|---|---:|---|
| 상단부(0~700줄대) | +14~15줄 | TC-FE-033~065 대부분 |
| 우측 패널(1200줄대 이후) | +21~31줄 | TC-FE-077~079, 087 |

카탈로그가 인용한 함수/조건문·문자열 자체는 **전건 실재 확인**됐다(내용 드리프트 0건, 라인 번호만 밀림). 다음 카탈로그 갱신 시 `frontend/src` 회차 diff 재확인 필요.

## 브라우저 자동화 필요 케이스 목록 (BLOCKED 사유별)

없음. H-3 61건 전부가 상태·로직·API 계약 단언(loading/error 분기, dirty 가드, mock 차단, 409/412 계약, 좌표 clamp 등)이라 정적 코드 대조 + 기존 vitest(jsdom) 컴포넌트 테스트(전건 baseline GREEN) + BE 실호출로 판정 가능했다. UNCERTAINTIES #22(캔버스 드로잉 픽셀 정확도, 미해소)에 해당하는 케이스(실제 마우스 드래그·픽셀 좌표 검증)는 H-3 61건 안에 없다(캔버스 lazy 마운트 여부만 다루는 TC-FE-086은 Suspense 렌더 확인이라 해당 없음).

## 이슈 상세

### [H-ISSUE-41] TC-FE-056 — SAM2 Track mock 폴백 결과가 라벨링 화면에서 신뢰 가능한 결과와 동일하게 자동 병합·성공 토스트됨

- **심각도**: HIGH
- **기대 동작(기대효과)**: SAM2 분할(Segment)과 동일하게, ai-server 가 mock 폴백(실모델 추론 실패)으로 응답한 추적 결과는 화면에 **자동 적용되지 않고** 경고로 표시돼야 한다(CLAUDE.md SFR-08-01: "SAM2 분할(클릭/박스→폴리곤+신뢰도, mock 응답은 FE 자동적용 차단)"). Track 도 동일 계약 대상이다(C-ISSUE-61 근거).
- **현재 동작(이슈 내용)**: `frontend/src/pages/label/LabelingPage.tsx:634-677 handleTracked` 가 `tracked` 결과를 mock 여부·신뢰도와 무관하게 무조건 `mergeAutoLabels`/`stashPendingTracks` 하고, `partial===false` 이면 항상 성공 토스트를 띄운다.
  ```tsx
  const handleTracked = useCallback(
    (tracked: Sam2TrackedItem[], partial: boolean) => {
      ...
      let applied = 0;
      if (forCurrent.length > 0) {
        applied += mergeAutoLabels(
          forCurrent.map((t) => trackedItemToLabel(t, currentFrame.frameNo)),
        );
      }
      ...
      if (partial) {
        pushToast({ variant: 'warning', message: `${applied}/${total} 프레임만 추적됨 (일부 실패)` });
      } else {
        pushToast({ variant: 'success', message: `AI 추적 완료 (${applied}프레임)` }); // mock 여부 무관
      }
    }, [...]);
  ```
  근본 원인은 BE 계약 단절이다 — `Sam2TrackResponse.java`(BE→FE DTO)가 ai-server 가 실제로 보내는 `mock`/`source`/`mock_reason` 필드를 선언하지 않아 Jackson 이 조용히 버린다(C-part4.md `C-ISSUE-61` 실측: `prevPolygon` 역전 좌표로 mock 폴백을 유도했을 때 BE 가 **200** + `message:null` 로 실추론과 구분 불가능한 응답을 내려줌). FE `useSam2Track`/`handleTracked` 는 애초에 판단 재료를 받지 못하므로 분기를 만들 수 없는 상태다.
- **재현/확인 경로**: `POST /v1/frames/{srcSn}/sam2-track` 요청에 실모델이 mask 를 못 찾는 입력(예: 극단 좌표)을 넣으면 ai-server 가 `[SAM2] track real returned no mask — prev polygon fallback` 경고 로그와 함께 입력 폴리곤을 그대로 반사한 가짜 추적 결과를 반환(C-part4 실측). 이 응답이 라벨링 화면의 `handleTracked` 를 그대로 통과해 병합 + "AI 추적 완료" 토스트로 이어진다. 회귀 방지 테스트도 0건(`grep -rln "handleTracked" **/*.test.tsx` → 0건, `LabelingPageAutolabelToast.test.tsx` 는 Detect 경로만 커버).
- **영향**: 사용자가 신뢰할 수 없는 자동 추적 결과(가짜 폴리곤)를 실제 검출로 오인해 검수 없이 그대로 저장할 위험. 데이터 품질 저하(오검출 라벨 유입) — CWE 분류 대상은 아니나 SFR-08-01 요구사항(VOS 신뢰도 게이팅) 미충족.
- **수정 방향(제안)**: (구현하지 않음) ①BE `Sam2TrackResponse`/`Sam2TrackResponseDto.TrackedItem` 에 `mock`/`source`/`score` 필드를 Segment 와 동일하게 선언·전파 ②`handleTracked` 에 Segment 의 `res.message` 분기와 동일한 mock 경고 분기 추가(자동 병합 차단 + 경고 토스트) ③회귀 테스트 신설(`handleTracked` mock 케이스).
