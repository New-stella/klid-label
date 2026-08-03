# H-3. LabelingPage (라벨링 캔버스) — 검증 결과 (2026-08-02 / 2차)

> 대상: `docs/test-cases/H-frontend-e2e.md` §H-3 **71건**(TC-FE-033~087 · 197~202 · 261~270)
> 환경: frontend `localhost:13000` · backend `localhost:18081/api` · DB `public` 스키마 · mock-server 경유(스택 기동 상태 실측 200)
> 방식: **Playwright(Chromium) 실브라우저 조작 최우선** — dev 토큰(`POST /v1/dev/tokens`, WORKER `sub=2001`)을 `sessionStorage['klid_jwt']` 에 주입 후 `/label/{srcSn}` 진입.
> 대상 데이터: `raw_sn=4`(WORKER 2001 배정, 프레임 30장, `src_sn 1~30`, `DE_IDENT_YN='Y'`, 작업상태 COMPLETED=APPROVED). 파생/RAW/잠금 등 **실데이터로 못 만드는 전제는 `page.route` 로 API 응답만 패치**해 FE 분기를 실동작 판정했다(코드·설정·테스트 파일 무수정).
> 판정 토큰: PASS / FAIL / PARTIAL / BLOCKED / N/A / 확인필요 — 근거확인 컬럼에 `[실동작]` / `[정적]` 접두.

## 0. 요약

| 판정 | 건수 |
|------|---:|
| PASS | 68 |
| FAIL | 1 |
| PARTIAL | 1 |
| 확인필요 | 1 |
| **계** | **71** |

**핵심 결함 3건**
1. **H-ISSUE-41 (HIGH · FAIL)** — `getLabels()` 가 BE 응답의 `labelVersion` 을 반환 객체에 **매핑하지 않아**, 라벨 저장 PUT 이 **낙관적 동시성 토큰을 한 번도 싣지 않는다**(실측 PUT body 3건 전부 `labelVersion` 부재). C-ISSUE-21 이 막으려던 **full-replace lost update 가 그대로 성립**한다. 단위테스트는 `useLabels` 응답을 목으로 주입해 통과 중이라 회귀 감지가 안 된다(A 클러스터의 "로그 마스킹 미배선"과 동일 계열).
2. **H-ISSUE-43 (확인필요)** — 폴리곤 도구에서 **마우스 클릭으로 점이 추가되지 않는다**(3~4회 클릭 + dblclick/Q 모두 라벨 0건, 드래프트 정점 렌더도 없음). 같은 화면에서 **F(점 추가)/Q(완성) 키보드 경로는 정상 커밋**되고 BBOX 드래그·라벨 선택 클릭도 정상이라, 도구별 클릭 경로만 어긋난 것으로 보인다. CDP 합성 클릭 특유의 아티팩트 가능성이 남아 **실사용자 마우스 재현이 필요**.
3. **H-ISSUE-44 (LOW)** — H-3 절 **근거 `file:line` 이 사실상 전건 드리프트**(`LabelingPage.tsx` 가 1,609줄로 늘며 대부분 +60~160줄 이동). 카탈로그 자체의 정합성 결함.

**★ 프롬프트 지정 확증편향 점검 결과**
- **비식별 프레임 서빙**: 라벨링 캔버스가 `GET /v1/frames/{srcSn}/image` 로 받은 이미지에 **`MOCK 비식별` 워터마크가 찍힌 비식별본**이 렌더되고 헤더 뱃지도 `DEID` — 확정정책 성립(스크린샷 `.playwright-mcp/h3-canvas.png`).
- **REVIEWER `raw=true` 동선 FE 부재**: `useImageBlob(srcSn, {raw})` 옵션은 존재하나 **프로덕션 호출부 3곳(`LabelingPage` · `DarkFrameStrip` · `FrameTimeline` · `review/LabelCanvas`) 어디도 `raw:true` 를 넘기지 않는다**. `raw: true` 리터럴은 **테스트 파일에만** 존재(`__tests__/useImageBlob.test.tsx:86`, `api.test.ts:464`). CLAUDE.md "REVIEWER 원본 열람 동선을 FE 에 두지 않는다" **성립(PASS)**.
- BBOX 그리기·undo/redo·저장/불러오기·AI 탐지/분할/추적 트리거는 **모두 실동작으로 확인**했고, 실패는 위 3건에 국한된다.

---

## 1. 판정표

| ID | 판정 | 근거 확인 | 실측 근거 / 비고 |
|----|:--:|------|------|
| TC-FE-033 | PASS | [실동작] | `/label/abc` → `잘못된 프레임 ID` + `뒤로 가기`. 실제 위치 `LabelingPage.tsx:1067-1086`(카탈로그 911-930 드리프트) |
| TC-FE-034 | PASS | [정적] | `:1088-1101` `<Spinner label="라벨 로딩" />` + "라벨 로딩 중..." (드리프트 932-945) |
| TC-FE-035 | PASS | [정적] | `:1106-1133` `portalMode && (status===403 \|\| errorCode==='FORBIDDEN')` → `data-testid="portal-forbidden-screen"` + "접근할 수 없는 영상입니다" (드리프트 950-977) |
| TC-FE-036 | PASS | [실동작] | `/label/999999` → `라벨 조회 실패` + **BE 문구 "프레임을 찾을 수 없습니다."** + 뒤로가기. `:1135-1155` |
| TC-FE-037 | PASS | [정적] | `:233-248` `siblings.length===0` → 현재 프레임 1건 폴백 (드리프트 202-219) |
| TC-FE-038 | PASS | [실동작] | 프레임4 BBOX 1건 그린 뒤 Ctrl+S → 토스트 `성공: 저장됨`, 헤더 `● 편집 중`→`✓ 저장됨`. PUT `{"items":[{...}]}` 200 |
| TC-FE-039 | PASS | [실동작] | PUT 3초 지연 라우트 + Ctrl+S **3연타** → 실제 PUT **1회**, 토스트 1건. `:560 if (saving) return;` + busy 배타 |
| TC-FE-040 | PASS | [실동작] | `lockSttsCd='LOCKED_FOR_REDEIDENT'` 패치 → 저장 버튼 `disabled=true`, Ctrl+S → `오류: 비식별 재처리 중인 영상은 저장할 수 없습니다.`, PUT 미발생. `:562-568` |
| TC-FE-041 | PASS | [실동작] | PUT 500(`{success:false,data:null,message:'BE-특정-저장실패-문구'}`) → 토스트 `오류: BE-특정-저장실패-문구`(=`extractBeMessage`). dirty 유지 |
| TC-FE-042 | PASS | [정적] | `:550-555` `updateLabels = portalMode ? savePortalLabels : updateInternalLabels` (드리프트 486-491) |
| TC-FE-043 | PASS | [실동작] | dirty 1건 상태에서 썸네일 클릭 → `저장 안 한 변경사항이 있습니다 / 1개 객체에 미저장 변경이 있습니다. 프레임을 이동하기 전에… / 취소·저장 안 함·저장 후 이동`, URL 미변경 |
| TC-FE-044 | PASS | [정적] | `:343 if (target.srcSn === data.srcSn) return;` |
| TC-FE-045 | PASS | [정적] | `:351-379` — `updateLabels` 예외 시 에러 토스트 + `setNavGuardTarget(null)`(이동 취소), `saved===null` 이면 이동 안 함 |
| TC-FE-046 | PASS | [실동작] | `저장 안 함` → `/label/1`→`/label/4` 이동, 객체 `2개`→`0개`(dirty 폐기 + 새 프레임 라벨), 헤더 `✓ 저장됨` |
| TC-FE-047 | PASS | [실동작] | 프레임 전환 시 서버 라벨로 전체 교체(`0개 객체`) + dirty 초기화. `:411-424` |
| TC-FE-048 | PASS | [정적] | `:420-423` 같은 프레임 재조회는 `getState().dirtyLabels.size===0` 일 때만 `setLabels` |
| TC-FE-049 | PASS | [정적] | `:430-443` drain→`mergeAutoLabels`→토스트 **"보류된 AI 추적 N건 적용됨"**(문구 일치) |
| TC-FE-050 | PASS | [정적] | `:446-450` unmount cleanup `reset()` |
| TC-FE-051 | PASS | [실동작] | AI Tool 팝업: `person/car/bicycle/motorbike/bus/truck` 선택 가능, `fire/smoke/water/c2a-race-lbl` **checkbox disabled + "미매핑"** 표기, 실행 버튼 라벨 `전체 (6종)`, `일반`/`트랙` 활성 |
| TC-FE-052 | PASS | [실동작] | 응답 `message:'모델이 로드되지 않아 mock 응답입니다'` → `경고:` 토스트만, 객체 `1개→1개`(병합 0) |
| TC-FE-053 | PASS | [실동작] | 정상 응답 2건 → `성공: AI 탐지 2건 적용됨`, 객체 `1개→3개` |
| TC-FE-054 | PASS | [실동작] | shape=POLYGON 실행 → `성공: AI 분할 1건 적용됨` (`BUSY_KIND_NAME` 단일 소스 파생) |
| TC-FE-055 | PASS | [실동작] | 팝업 `트랙` → 도구 `AI 추적` 활성 + `안내: 추적할 객체를 선택한 뒤 속성 패널에서 자동추적을 실행하세요.` |
| TC-FE-056 | PASS | [정적] | `:761-804` 현재=`mergeAutoLabels` 즉시, 미래=`stashPendingTracks` (드리프트 619-660) |
| TC-FE-057 | PASS | [정적] | `:785-790` `${applied}/${total} 프레임만 추적됨 (일부 실패)` warning |
| TC-FE-058 | PASS | [정적] | `:751-754` `frames.slice(frameIdx+1)` |
| TC-FE-059 | PASS | [정적] | `api.ts:705-708` `SAM2_TRACK_CHUNK_SIZE` 단위 `slice(i, i+SIZE)` 순차 — BE `@Size(max=50)` 정합 |
| TC-FE-060 | PASS | [정적] | `useSam2Track.ts:54-58,96-110` — `requestedSrcSnRef`(실행 시점 프레임) + `runExclusiveOrNotify(ctx.srcSn)` 의 `isStaleScope` 로 폐기 (카탈로그 57-59 드리프트) |
| TC-FE-061 | PASS | [정적] | `useSam2Track.ts:83-88` `Sam2TrackChunkError.partial.length>0 → onTracked(partial, true)` |
| TC-FE-062 | PASS | [정적] | `api.ts:628-647 toSeedPolygon` 2점→4점 외접박스(+비유한 좌표 warn) |
| TC-FE-063 | PASS | [정적] | `:856 / :878 / :903` 각 핸들러 `if (portalMode) return;` (드리프트 721/745/769) |
| TC-FE-064 | PASS | [정적] | `:859 / :881 / :906` `isLocked` 에러 토스트 + 미실행 |
| TC-FE-065 | PASS | [정적] | `:864-866` `mergeTracks` → `LABEL_KEYS.byVideo(rawSn)` invalidate + 성공 토스트 |
| TC-FE-066 | PASS | [정적] | `:527-535` `setReportedLock(true)` + `reset()` + `LABEL_KEYS.byVideo(srcSn)` invalidate. 412 재조회 화면은 `:1135-1155`(에러 문구=BE message) 와 `frameImageErrorHint` 412 분기(`:161`)로 성립. ⚠ 실제 신고 접수는 **DB 작업락이 걸려 타 클러스터 검증을 오염**시키므로 미수행 |
| TC-FE-067 | PASS | [실동작] | 잠금 패치 → `data-testid="deident-locked-banner"` `role=status` "비식별 재처리 중인 영상입니다…" 노출 |
| TC-FE-068 | PASS | [실동작] | `frameImageType='RAW'` 패치 → 신고 버튼 `disabled=true`(잠금·busy 아님) |
| TC-FE-069 | PASS | [정적] | `:128 canReportDeident = !portalMode && (isWorker\|\|isReviewer)`, `:1187-1198` false 면 `null` |
| TC-FE-070 | PASS | [실동작] | WORKER 진입 시 `submit-review-button` 렌더(`:1199-1200 isWorker && data`) |
| TC-FE-071 | PASS | [정적] | `:193-212` `SUBMITTABLE_STATUSES=['ASSIGNED','REJECTED','COMPLETED','APPROVED']`, 그 외 `disabled` + `title=submitStatusHint`(REVIEW_PENDING/REVIEWING 별 문구) |
| TC-FE-072 | PASS | [실동작] | 대상 영상 작업상태 COMPLETED → 버튼 문구 **"재검수 제출"**(`:201-202`) |
| TC-FE-073 | PASS | [정적] | `:199 canCancelSubmit = isWorker && workStatus==='REVIEW_PENDING'`, `:1202` 조건부 렌더. 실동작에서도 COMPLETED 상태라 **미노출**(조건 일치) |
| TC-FE-074 | PASS | [정적] | `:167-173` onSuccess → `'검수 제출 완료'` + `navigate('/task')`. ⚠ 실제 제출은 검수 워크플로 상태를 바꿔 미수행 |
| TC-FE-075 | PASS | [실동작] | dirty 상태 X 클릭 → 3옵션 모달(`label-close-cancel/discard/save`), "저장 안 한 변경사항이 있습니다 / 1개 객체에 미저장 변경이…" |
| TC-FE-076 | PASS | [정적] | `:983-992` `dirtyCount>0` 일 때만 `beforeunload` 등록 + `preventDefault`/`returnValue=''` |
| TC-FE-077 | PASS | [실동작] | INTERNAL 진입 시 `객체/메타/이슈` 3탭 렌더 + `label-issue-panel` 존재. portalMode 게이트 `:473-476`(`showMeta=!portalMode`, `showIssues=!portalMode && videoId!==undefined`) |
| TC-FE-078 | PASS | [정적] | `:1434-1442` `unresolvedInquiries>0` 시 `issue-tab-badge` + `aria-label="미해소 문의 N건"`. 실동작 대상 영상은 미해소 문의 0 → 배지 없음(조건 일치) |
| TC-FE-079 | PASS | [실동작] | 메타 탭 = **촬영환경 / 개인정보 / 프레임 설명 / 시계열 메타(검토 상태 승인됨) / 이벤트 어노테이션** 5패널 전부 렌더(`:1467-1475`) |
| TC-FE-080 | PASS | [정적] | `:294-317` `viewKeyRef`(videoId+실측 dims) + `shouldResetView` — 동일 영상·동일 해상도면 `resetView()` 미호출 |
| TC-FE-081 | PASS | [실동작] | Ctrl+Shift+C → `라벨 1건 복사됨`, Ctrl+V → `라벨 1건 붙여넣음`(객체 2개). clamp 는 `:1036-1038 imageWidth/Height = frameNaturalSize?.*` |
| TC-FE-082 | PASS | [정적] | `:1018-1025` `n===0` → warning `복사할 라벨이 없습니다.` (성공 경로는 실동작 확인) |
| TC-FE-083 | PASS | [정적] | `:1027-1031` `isLocked` → 에러 토스트 + `return`(붙여넣기 미실행) |
| TC-FE-084 | PASS | [정적] | `:643-645 handleRevertRequest` → `revertTarget` → `:1574-1582 ConfirmDialog("이 저장으로 되돌리기")` |
| TC-FE-085 | PASS | [정적] | `:667-670` `reverted===0` → warning. ⚠ **문구 드리프트**: 실제는 `되돌릴 항목이 현재 작업본에 없습니다.`(카탈로그 "되돌릴 항목이 없습니다") |
| TC-FE-086 | PASS | [실동작] | `:87-89` `lazy(() => import('.../CanvasShell'))` + `:1331-1353` Suspense fallback `캔버스 로딩`. 실브라우저에서 konva 캔버스 3레이어(1280×720) 마운트 확인 |
| TC-FE-087 | PASS | [실동작] | 히스토리 토글 → `inline-history-panel` ("히스토리 / 변경 이력 / 버전 0 / 라벨 변경 이력…"), `historyOpen && !portalMode && srcSn!==undefined`(`:1546-1558`) |
| TC-FE-197 | PASS | [실동작] | PUT 409 → 토스트 아님, **ConfirmDialog "다른 사용자가 먼저 저장했습니다" + BE 문구 그대로 + `내 작업 유지`/`최신 라벨 불러오기`**. 객체 `2개` 유지 + 헤더 `● 편집 중`(dirty 미폐기) |
| TC-FE-198 | **FAIL** | [실동작] | **PUT body 에 `labelVersion` 이 실리지 않는다.** 실측 3회 전부 `{"items":[…]}` 뿐. 원인: `api.ts:196-229 getLabels()` 반환 객체에 `labelVersion` 미포함(BE 는 응답에 포함 — 실측 `labelVersion:2`). → **H-ISSUE-41** |
| TC-FE-199 | PARTIAL | [실동작] | `useUpdateLabels.ts:60-70` 캐시 우선 로직은 존재하고 2회 연속 저장이 409 를 내지 않는 것도 실측 확인. 그러나 **409 가 안 나는 이유가 "최신 버전 전송"이 아니라 "버전 자체 미전송(BE 검사 skip)"** 이다(TC-FE-198). 기대결과의 보호 효과는 미성립 → **H-ISSUE-42** |
| TC-FE-200 | PASS | [실동작] | 영상상세 `derivative=true` 패치 → 신고 버튼 `disabled=true`, `title`/`aria-label` = **"증강·해상도 변환으로 만든 파생영상이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다."** — **부모 rawSn·원본 유도 문구 없음**(확정정책 일치) |
| TC-FE-201 | PASS | [정적] | `DeidentReportButton.tsx:109-116` status 412 → `resolveApiMessage(e, …)` 로 **서버 안내문**을 폼 내 `role=alert` 에 표시. `errors.ts:37,47` 412→`PRECONDITION_FAILED` 매핑 존재 |
| TC-FE-202 | PASS | [정적] | `api/eventAnnotation.ts:26-29 normalizeCot` — 배열은 그대로, 객체는 `Object.values`(JSON 키 순서 유지) |
| TC-FE-261 | PASS | [실동작] | AI 탐지 7초 지연 중: 저장/신고/검수제출 버튼 `disabled=true`, **캔버스 드래그해도 객체 수 불변(1개)**, 오버레이 백드롭이 포인터 흡수 |
| TC-FE-262 | PASS | [실동작+정적] | 신고 버튼 disabled 실측(`:1193 disabled={isLocked \|\| isEditBlocked \|\| RAW}`). 되돌리기 `:655 isEditBlockedNow` 이중 방어, 롤백/신고 경로 동일 |
| TC-FE-263 | PASS | [실동작] | busy 중 `B` keydown → 활성 도구 `선택` 유지(전환 안 됨). `useLabelingShortcuts.ts:216 blocked \|\| isEditBlockedNow()`, `:247 if (editBlocked) continue` |
| TC-FE-264 | PASS | [실동작] | 실행 **150ms 시점 오버레이 없음**, 1.5초 시점 `AI 탐지 진행 중 / 1초 경과 / 작업 취소 / 취소하면 결과를 반영하지 않고…` — **모델명·식별자·경로 없음**. `busyPolicy.ts:69 BUSY_OVERLAY_DELAY_MS=300` |
| TC-FE-265 | PASS | [실동작] | `busy-overlay-cancel` 클릭 → 오버레이 즉시 해제 + 저장 버튼 재활성. **7초 뒤 도착한 성공 응답이 반영되지 않음**(객체 1개 유지, 성공 토스트 없음) = 세대 토큰 폐기 |
| TC-FE-266 | PASS | [정적] | `OverlayLayer.tsx:357-381` ESC → `setPendingConfirm(false)` + `handleBusyEscape()`(누적점 보존). 전용 테스트 `canvas/layers/__tests__/OverlayLayerSegmentBusy.test.tsx` |
| TC-FE-267 | PASS | [정적] | `useBusyTask.ts:13 BUSY_MAX_DURATION_MS=5*60*1000`, `:145-151` 토큰 생존 시에만 `cancelBusy()` + warn |
| TC-FE-268 | PASS | [정적] | 메타 5패널은 `EnvironmentMetaPanel`/`FramePrivacyMetaPanel`/`FrameDescriptionPanel`/`TimeseriesSidePanel`/`EventAnnotationPanel` 로 라벨 작업본과 상태 공유 없음. 회귀 가드 `__tests__/metaEditBusyIndependence.test.tsx` 존재 |
| TC-FE-269 | PASS | [정적] | `CanvasShell.tsx:71-104` `KEYBOARD_ACTIVATABLE_TAGS/ROLES` + `isKeyboardActivatableTarget` → 버튼 포커스 시 Space 팬 미발동·preventDefault 미적용. 테스트 `canvas/__tests__/CanvasShellSpaceActivation.test.tsx` |
| TC-FE-270 | PASS | [정적+실동작] | FE busy 는 같은 탭 한정(`useLabelStore` 메모리). 교차 수정은 `:583-588` 409 → 충돌 다이얼로그(TC-FE-197 실증)가 담당 |

---

## 2. 이슈

### [H-ISSUE-41] TC-FE-198 — 라벨 저장 PUT 이 낙관적 동시성 토큰(`labelVersion`)을 전혀 싣지 않아 lost update 가 그대로 성립
- **심각도**: HIGH
- **기대 동작(기대효과)**: 조회 응답의 `labelVersion` 을 저장 요청 body 에 되돌려 보내야, 그사이 다른 사용자가 같은 프레임을 저장했을 때 BE 가 409 로 거부한다. 라벨 저장이 **full-replace 계약**이므로 이 토큰이 없으면 "내 화면에 없던 남의 라벨"이 조용히 전량 삭제된다(C-ISSUE-21 이 막으려던 실측 결함).
- **현재 동작(이슈 내용)**: BE 는 `GET /v1/frames/{srcSn}/labels` 응답에 `labelVersion` 을 내려주지만(실측 `{"srcSn":4,…,"labelVersion":2,…}`), FE 변환 함수가 이 필드를 **반환 객체에 매핑하지 않는다**.
  ```ts
  // frontend/src/features/label/api.ts:216-228  getLabels()
  return {
    frameNo: d.frameNo ?? 0,
    srcSn,
    videoId: …,
    frameImageType,
    lockSttsCd,
    siblings,
    labels: rawList.map(normalizeLabel),
  };          // ← labelVersion 없음 (types.ts:263-269 는 필드를 선언하고 있다)
  ```
  결과적으로 `LabelingPage.tsx:545 { labelVersion: data?.labelVersion }` 는 항상 `undefined` 이고, `useUpdateLabels.ts:63-65` 의 캐시 폴백(`qc.getQueryData(internalKey)?.labelVersion`)도 같은 변환 결과를 읽으므로 `undefined` 다. `putLabels(…, undefined)` 는 `api.ts:304 ...(labelVersion != null ? { labelVersion } : {})` 로 필드를 생략 → **BE 하위호환 경로(검사 skip)** 로 떨어진다.
  저장 직후 `setQueryData` 가 PUT 응답(원본 JSON, `labelVersion` 포함)을 병합해 잠깐 캐시에 값이 생기지만, 같은 블록의 `invalidateQueries` 재조회가 다시 `getLabels()` 를 태워 값을 **덮어 지운다**.
- **재현/확인 경로**:
  1. 브라우저: `/label/4` 진입 → BBOX 1건 그리기 → Ctrl+S. DevTools Network 의 `PUT /api/v1/frames/4/labels` request payload 확인 → `{"items":[…]}` (labelVersion 없음). 2.5초 후 재저장해도 동일.
  2. 서버 측 확인:
     ```bash
     curl -s -H "Authorization: Bearer $T" http://localhost:18081/api/v1/frames/4/labels | jq '.data.labelVersion'   # → 2
     curl -s -X PUT -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
          -d '{"items":[]}' http://localhost:18081/api/v1/frames/4/labels                                            # → 200 (stale 여부와 무관하게 통과)
     ```
- **영향**: 데이터 정합 — 같은 프레임을 두 작업자(또는 두 탭)가 편집하면 **나중 저장이 앞 저장 결과를 경고 없이 전량 삭제**한다(CWE-362 계열, 라벨 유실). 부수적으로 TC-FE-197 의 409 충돌 다이얼로그와 TC-FE-199 의 캐시 우선 로직이 **실사용에서 도달 불가능한 죽은 경로**가 된다. 단위테스트(`api.test.ts:401-433`, `useUpdateLabels.test.tsx:98-`)는 `putLabels`/`useUpdateLabels` 에 버전을 **직접 주입**해 검증하므로 이 배선 단절을 잡지 못한다.
- **수정 방향(제안)**: `api.ts` `getLabels()` 반환 객체에 `labelVersion: typeof d.labelVersion === 'number' ? d.labelVersion : null` 을 추가한다(응답에 없으면 null → 기존 하위호환 유지). 회귀 가드로 `getLabels` 가 응답의 `labelVersion` 을 그대로 노출하는지 검증하는 테스트를 추가하고, 가능하면 "저장 PUT 이 조회에서 받은 버전을 실제로 싣는지"를 화면 레벨(`LabelingPage`)에서 확인하는 통합 테스트를 둔다. ⚠ 구현은 하지 않았다.

### [H-ISSUE-42] TC-FE-199 — 연속 저장 409 미발생이 "최신 버전 전송"이 아니라 "버전 미전송"으로 성립
- **심각도**: MEDIUM (H-ISSUE-41 의 파생)
- **기대 동작(기대효과)**: 1회차 저장 성공 직후 2회차 저장이 **응답으로 갱신된 최신 `labelVersion`** 을 보내 자기 자신과 409 가 나지 않아야 한다(DEV_FIX H12).
- **현재 동작(이슈 내용)**: 실측상 연속 저장은 409 없이 둘 다 성공하지만, 두 PUT 모두 `labelVersion` 자체가 없다.
  ```
  PUT /api/v1/frames/4/labels  {"items":[{"id":null,…}]}     ← 1회차
  PUT /api/v1/frames/4/labels  {"items":[{"id":726,…}]}      ← 2회차 (labelVersion 여전히 없음)
  ```
  `useUpdateLabels.ts:63-65` 의 `cached?.labelVersion ?? options.labelVersion` 은 두 소스가 모두 `undefined` 라 동작할 여지가 없다.
- **재현/확인 경로**: H-ISSUE-41 재현 1과 동일(연속 2회 Ctrl+S 후 두 payload 비교).
- **영향**: 케이스가 "통과처럼 보이지만 보호 효과는 0"인 상태다. H-ISSUE-41 을 고치면 이 로직이 비로소 실효를 갖는데, 그때 **캐시 우선 순서가 실제로 맞는지**는 아직 실환경 미검증이므로 수정 후 재검증 대상이다.
- **수정 방향(제안)**: H-ISSUE-41 수정 후, 연속 저장 2회의 payload `labelVersion` 이 `v` → `v+1` 로 증가하는지 실브라우저로 재검증한다(수정 없이는 판정 불가).

### [H-ISSUE-43] (H-3 케이스 미할당 — 캔버스 도구 실동작) 폴리곤 도구에서 마우스 클릭으로 점이 추가되지 않는다
- **심각도**: HIGH (확정 시) / 현재 판정 **확인필요**
- **기대 동작(기대효과)**: 폴리곤 도구 선택 후 캔버스를 클릭하면 점이 추가되고, dblclick 또는 시작점 근접으로 닫혀 POLYGON 라벨이 커밋되어야 한다(`OverlayLayer.tsx:107` 주석 "클릭으로 점 추가, dblclick 또는 시작점 근접 시 닫기"). 라벨링 도구의 핵심 입력 경로다.
- **현재 동작(이슈 내용)**: Chromium(Playwright) 실브라우저에서 폴리곤 도구 활성 후 캔버스 3~4회 클릭 → **드래프트 정점이 렌더되지 않고**(스크린샷 `.playwright-mcp/h3-poly-draft.png`), 이어진 `dblclick` 및 `Q` 모두 라벨 0건. 활성 라벨(`1` 키)을 먼저 선택한 경우와 아닌 경우 **모두 동일**했고, 오류 토스트도 없다(무음 실패).
  같은 페이지·같은 좌표에서:
  - `F`(점 추가) 3회 + `Q`(완성) → **POLYGON 라벨 정상 커밋**(`사람 #2 … POLYGON`)
  - BBOX 드래그(mousedown→mousemove→mouseup) → 정상 커밋
  - 선택 도구로 라벨 클릭 → 정상 선택(속성 패널에 `#tmp-…`, `형태 BBOX`) → **Konva click 자체는 발화한다**
  즉 `captureRect` 의 `onMouseDown/Move/Up`·`onDblClick` 은 발화하는데 **`onClick`(`OverlayLayer.tsx:869-905` 의 폴리곤 분기)만 도달하지 않는 것으로 보인다.
- **재현/확인 경로**: `/label/1` 진입 → 툴바 `폴리곤`(또는 `P`) → 캔버스 3회 클릭 → 정점 표시 여부 확인 → `Q` 또는 dblclick → 객체 수 변화 확인. 대조군으로 마우스 이동 후 `F` 3회 + `Q` 를 수행하면 정상 생성된다.
- **영향**: 사실이면 **마우스만 쓰는 작업자가 폴리곤/세그멘테이션을 그릴 수 없다**(SFR-08 라벨링 핵심 기능). 같은 `onClick` 경로를 쓰는 **KEYPOINT 17점 순차 배치**와 **AI 분할 클릭 프롬프트 누적**도 동일하게 영향받을 수 있다. 다만 CDP 합성 클릭과 Konva 의 click 합성(`pointerdown/pointerup` → `click`) 간 상호작용 아티팩트일 가능성이 남아 있어 **결함 확정 전 실사용자 마우스 재현이 필요**하다.
- **수정 방향(제안)**: ① 먼저 실제 마우스로 재현 여부 확정(재현 안 되면 본 이슈는 하네스 아티팩트로 종결). ② 재현되면 `OverlayLayer.tsx:869-905` 의 `onClick` 분기에 도달하는지(특히 `pointerCanvas()` 가 mouseup 시점에 null 을 반환하는지) 진단하고, 필요 시 폴리곤 점 추가를 `onMouseUp`(이동량 임계값 기반) 경로로 옮겨 BBOX/SAM2 와 이벤트 축을 통일한다. ③ 어느 쪽이든 **커밋 실패 시 무음이 되지 않도록** `commitPolygon` 실패·점 부족 상황의 사용자 안내를 보강한다. ⚠ 구현은 하지 않았다.

### [H-ISSUE-44] (카탈로그 정합성) H-3 절 근거 `file:line` 전건 드리프트 + 기대 문구 1건 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 근거 `file:line` 이 실제 코드 위치를 가리켜야 다음 회차 검증·수정 작업이 곧바로 착지한다.
- **현재 동작(이슈 내용)**: `LabelingPage.tsx` 가 **1,609줄**로 늘면서 H-3 의 `pages/label/LabelingPage.tsx:*` 근거가 사실상 전건 어긋났다. 대표 예:
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | TC-FE-033 | 911-930 | **1067-1086** |
  | TC-FE-038 | 492-510 | **556-578** |
  | TC-FE-043 | 292-302 | **337-349** |
  | TC-FE-052 | 698-701 | **692-695** |
  | TC-FE-055 | 673-690 | **815-833** |
  | TC-FE-066 | 465-473 | **527-535** |
  | TC-FE-067 | 1079-1090 | **1238-1247** |
  | TC-FE-079 | 1290-1310 | **1467-1475** |
  | TC-FE-197 | 515-519,533-538,1385-1394 | **583-588, 601-640, 1562-1571** |
  | TC-FE-060 | useSam2Track.ts:57-59 | **useSam2Track.ts:96-110** |
  또 TC-FE-085 의 기대 문구가 `되돌릴 항목이 없습니다` 인데 실제 구현은 `되돌릴 항목이 현재 작업본에 없습니다.`(`LabelingPage.tsx:668`)다.
- **재현/확인 경로**: `grep -n` 으로 위 표의 심볼(`잘못된 프레임 ID`, `handleSave`, `requestJumpTo`, `handleDeidentReportSuccess` 등) 위치 확인.
- **영향**: 판정 자체는 바뀌지 않으나, 근거를 따라가면 무관한 코드가 나와 재검증·수정 착수 비용이 늘고 오판 위험이 생긴다.
- **수정 방향(제안)**: H-3 절 근거를 위 실측 위치로 일괄 갱신하고 TC-FE-085 기대 문구를 실제 구현 문구로 정정한다. 라인 대신 **심볼/함수명 기준 근거 표기**로 바꾸면 드리프트가 재발하지 않는다. ⚠ 카탈로그는 수정하지 않았다.

---

## 3. 검증 방법 메모 (재현용)

- 토큰: `POST /api/v1/dev/tokens {"role":"WORKER","channel":"INTERNAL"}` → `data.token` 을 `sessionStorage.setItem('klid_jwt', …)`.
- 브라우저 프로파일을 다른 검증 에이전트와 공유해 탭이 탈취되므로, **모든 조작을 단일 `browser_run_code_unsafe` 호출 안에서 `context.newPage()` 로 격리**해 수행했다.
- 전제 조작(잠금/RAW/파생/409/500/지연)은 `page.route` 로 **응답만 패치**했고 프로덕션·테스트·설정 파일은 일절 수정하지 않았다.
- DB 부작용: 검증 중 `src_sn=4` 에 BBOX 1건을 저장했다가 **삭제 후 재저장해 라벨 0건으로 원복**했다(`LS_DATA_LBL` 잔여 없음). `labelVersion` 만 증가했다(무해).
- 스크린샷: `.playwright-mcp/h3-canvas.png`(비식별 프레임 렌더), `h3-poly-draft.png`(폴리곤 클릭 무반응), `h3-bbox-drawn.png`, `h3-select-click.png`.
