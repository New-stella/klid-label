# H클러스터 3차 검증 — part6 (H-2 라우터구조/코드스플리팅 · H-4 useLabelStore · H-5 좌표변환/캔버스유틸 · H-7 BatchStageIndicator)

담당 라인범위: `docs/test-cases/H-frontend-e2e.md` 52~67행(H-2) · 217~235행(H-4) · 236~248행(H-5) · 274~283행(H-7). 총 38건.
검증 방법: **정적 대조 위주**(코드 Read + Grep) + 기존 자동 테스트 파일 존재·내용 확인. 빌드/테스트 실행 없음(지시에 따름). BatchStageIndicator 는 backend `BatchPipelineConfig.java`(List.of 선언 순서)와 FE `STAGE_LABEL` 매핑 순서를 직접 대조.

---

## H-2. 라우터 구조/코드스플리팅 (11건)

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-023 | PASS | [정적] `router/index.tsx:228` `{ index: true, element: <Navigate to="/dashboard" replace /> }` — 정확 일치 |
| TC-FE-024 | PASS | [정적] `router/index.tsx:213-219` `/label/:id` 가 `/` AppLayout 트리 밖에서 별도 매칭(LNB/GNB 미포함), `InternalRoute allow={internalAllRoles}` 게이팅 유지 |
| TC-FE-025 | PASS | [정적] `router/index.tsx:280`(`path:'review'`)·`330`(`path:'overall'`, internalReviewerOnly)·`340`(`path:'augment'`)·`370`(`path:'manage'`) 4개 라인 전부 정확 일치. WORKER 는 `internalAllRoles` 미포함 라우트라 진입 시 RoleGuard 가 forbidden 처리 |
| TC-FE-026 | PASS | [정적] `router/index.tsx:446` `{ path: '*', element: <AppErrorPage status={404} /> }` — 정확 일치 |
| TC-FE-027 | PASS | [정적] `router/index.tsx:412-419` manage `path:'*'` placeholder 가 `internalReviewerOnly` 로 게이팅 — 정확 일치 |
| TC-FE-028 | PASS | [정적] `router/index.tsx:163-176` `isDevLoginEnabled()` false 면 `devOnlyRoutes` 배열이 비어 `/dev/login` lazy import 자체가 if 블록 안에 있어 dead-code 제거(빌드 실행 없이 코드 구조로 확인 — Vite tree-shaking 은 실측 안 함, BLOCKED 아님: 정적 판정으로 충분한 케이스) |
| TC-FE-029 | PASS | [정적] `router/index.tsx:178-198` `isDevUploadEnabled()` true 일 때만 `dev/autolabel-test` 라우트 push, `InternalRoute allow={internalReviewerOnly}`(line 193)로 REVIEWER 전용 확인 |
| TC-FE-030 | PASS | [정적] `router/index.tsx:155-161` `PortalRoute` = `ChannelGuard channel="PORTAL"` + `RoleGuard allow={portalOnly}` 이중가드, 코드 정확 일치 |
| TC-FE-031 | PASS | [정적] `router/lazyWithRetry.ts` 전체 확인 — 1차 실패 시 300ms 지연 후 1회 재시도, 재시도도 실패하면 세션당 1회 `window.location.reload()`, reload 중엔 영구 pending Promise 로 Suspense fallback 유지. 기대결과("재시도(Suspense fallback)")와 일치. ⚠ **coverage 관찰**: 이 유틸 전용 단위테스트 파일이 없음(`grep -rn lazyWithRetry --include=*.test.*` 0건) — 로직 자체는 정확하나 회귀 가드 부재. FAIL 아님(카탈로그가 테스트 존재를 주장하지 않음), 관찰 기록만 |
| TC-FE-032 | PASS | [정적] `router/index.tsx:122-128` `PageFallback` 컴포넌트 `<Spinner label="페이지 로딩" />` — 정확 일치 |
| TC-E2E-002 | PASS | [정적] `router/__tests__/{deepLinkHydrationGuard,manageGuard,portalGuard,reviewGuard,augmentExportGuard}.test.tsx` 5개 파일 전부 존재(총 429줄) 확인. `manageGuard.test.tsx` 내용 열람 — `RoleGuard`+`MemoryRouter` 조합으로 REVIEWER 전용 라우트 접근/차단을 렌더 테스트로 검증하는 구조 확인 |

**H-2 소계**: 11 PASS / 0 FAIL / 0 PARTIAL / 0 BLOCKED / 0 N/A / 0 확인필요

---

## H-4. useLabelStore (Zustand) (14건)

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-088 | PASS | [정적] `stores/useLabelStore.ts:486-495` `setLabels` — `dirtyLabels/undoStack/redoStack/selectedLabelId/hiddenLabelIds/lockedLabelIds` 전부 초기화, 라인 정확 일치 |
| TC-FE-089 | PASS | [정적] `stores/useLabelStore.ts:497-503` `addLabel` — `dirty.add(label.id)` 확인, 라인 정확 일치 |
| TC-FE-090 | PASS | [정적] `stores/useLabelStore.ts:66-89` `offsetShape` — BBOX(4꼭짓점 dx/dy)·POLYGON(짝/홀 인덱스 dx/dy)·KEYPOINT(전체 keypoints dx/dy) 타입별 분기 확인, 라인 정확 일치 |
| TC-FE-091 | PASS | [정적] `stores/useLabelStore.ts:92-117` `clampShape` — `cx/cy` 헬퍼가 `w`/`h` undefined 시 `Math.max(v,0)`(하한 0만), 지정 시 `Math.min(Math.max(v,0),w)`(양쪽 clamp). "미지정 시 하한 0만 적용" 기대결과와 정확 일치 |
| TC-FE-092 | PASS | [정적+테스트] `mergeAutoLabels`(:636-653) `mergeDetections` 로 중복(같은 클래스+IoU) 스킵 후 신규분만 병합, `merged.length` 반환. `useLabelStore.mergeAuto.test.ts` 파일 존재 확인 |
| TC-FE-093 | PASS | [정적+테스트] `revertSaveEvent`(:655-734) UPDATED/ADDED/DELETED 3종 분기로 역적용, `{reverted, skipped}` 반환 구조 확인. `useLabelStore.revert.test.ts` 존재 |
| TC-FE-094 | PASS | [정적+테스트] `stashPendingTracks`/`drainPendingTracks`(:736-758) — drain 은 해당 srcSn 만 제거하고 반환, `setLabels` 호출에도 보존됨(테스트 `setLabels_프레임전환에도_보류는_보존된다` 확인). 호출측 `pages/label/LabelingPage.tsx:437`에서 프레임 진입 시 `drainPendingTracks(srcSn)` 호출 확인 — "진입 시 drain 병합" 실배선 확인 |
| TC-FE-095 | PASS | [정적+테스트] `toggleLabelVisibility`(:567-572) — 새 Set 생성(불변성), `dirtyLabels` 미접촉. 테스트 파일 `useLabelStore.visibility.test.ts` 열람 — `toggle는_불변성_유지_새_Set_생성` 케이스로 확인됨 |
| TC-FE-096 | PASS | [정적+테스트] `updateLabel`(:505-514) 진입부 `if (get().lockedLabelIds.has(id)) return;` 로 no-op. 테스트 `useLabelStore.lock.test.ts` — `잠금_라벨은_updateLabel이_no_op이다`(dirty/undo 미변화 단언 포함) 확인 |
| TC-FE-097 | PASS | [정적+테스트] `copyLabels`(:582-596)/`pasteLabels`(:598-634) — 개수 반환, `sourceRawSn` 비교로 crossVideo 판정 시 `trackId=null`. `useLabelStore.clipboard.test.ts` — 딥클론·onlySelected·빈 케이스 0반환 전부 확인 |
| TC-FE-098 | PASS | [정적+테스트] `features/label/types.ts` `KeypointShape.keypoints`(x/y/v 삼중값) 확인. `useLabelStore.keypoint.test.ts`의 `makeKps()`가 17개 keypoint 생성(COCO-17, `keypointHelpers.ts:1` 주석 "COCO-17 휴먼 포즈" 확인) + undo 시 keypoints 배열까지 딥클론 복원 검증 |
| TC-FE-099 | PASS | [정적+테스트] `setImageAdjust`(:561-562)/`resetImageAdjust`(:564) — zustand store 에 `persist` 미들웨어 없음(`grep persist` 0건) → 세션 전용 확인. 테스트 `조절값_세션전용_영속안됨_reset시_default복원` 통과 구조 확인 |
| TC-FE-100 | PASS | [정적+테스트] `activeLabelId`(초기 null) + `setActiveLabelId`. `useLabelStore.activeLabel.test.ts`로 store 측 확인. `resolveDefaultLabel`(`features/label/canvas/layers/resolveDefaultLabel.ts`)이 `OverlayLayer.tsx`에서 `activeLabelId` 를 받아 프리셋 기본값 파생 — 별도 `resolveDefaultLabel.test.ts` 존재(카탈로그 미인용이나 실사용처 확인됨). 부가 관찰: 카탈로그가 인용한 테스트 파일은 store setter 만 다루고 `resolveDefaultLabel` 자체 로직은 별도 파일이 커버 — 기능은 정상, 인용 범위만 다소 좁음(FAIL 아님) |
| TC-FE-101 | PASS | [정적] `stores/useLabelStore.ts:4`(import `clampPan as clampPanByScale`) + `:386-406`(`clampPan` 함수, `clampPanByScale` 위임) + `:534`(`setPan: (x, y) => set({ panX: x, panY: y })` — raw setter, 클램프 없음) 확인. `CanvasShell.tsx:301-320`(`handleMouseMove`) 에서 드래그 좌표 계산 후 `clampPan(...)` 호출해 clamp 결과를 `setPan`에 전달 — 라인 정확 일치, 위임 구조 실배선 확인 |

**H-4 소계**: 14 PASS / 0 FAIL / 0 PARTIAL / 0 BLOCKED / 0 N/A / 0 확인필요

---

## H-5. 좌표 변환/캔버스 유틸 (canvas scale 함정) (8건)

> **함정 재현 시도 결과**: "canvas scale 함정"(줌/팬 시 좌표 오차)을 반증하기 위해 `coordinateTransformer.test.ts`의 라운드트립 테스트를 스케일 0.5·오프셋(top20/left40)·회전각(30°) 조합으로 확인했다. `translateToCanvas`→`translateFromCanvas` 역변환이 오차 4~5자리 소수점 이내로 원좌표를 복원(`toBeCloseTo(original.x, 4~5)`) — **함정이 실제로 열려있지 않고 방어돼 있음**을 실측 확인. `canvasGeometry.ts`(`buildGeometry`)가 fit-scale × zoom 을 단일 `scale` 값으로 합성해 `coordinateTransformer.Geometry`에 주입하는 구조라 두 좌표계가 분리되지 않는다.

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-102 | PASS | [정적+테스트] `coordinateTransformer.ts:43-51` `translateToCanvas` — `x: rx*scale+left`(회전 적용 후). 테스트 `translateToCanvas_scale_offset_적용`에서 `100*0.5+20`/`200*0.5+10` 정확 일치 단언 확인 |
| TC-FE-103 | PASS(카탈로그 라인 정정) | [정적+테스트] `coordinateTransformer.ts:57-65` `translateFromCanvas` — 카탈로그 인용 `:57-61`은 함수의 앞 절반(cx/cy/rx/ry 계산)만 가리키고 핵심인 역회전+return 문(62-65)이 라인범위 밖이었음. **본 회차에서 `:57-65`로 정정**(H-frontend-e2e.md:241, 내 라인범위 내). 로직 자체는 정상 — 회전 각도 30° 포함 라운드트립 테스트(`회전_각도_있는_상태에서도_라운드트립_유지`)로 오차 4자리 이내 복원 확인 |
| TC-FE-104 | PASS | [정적+테스트] `coordinateTransformer.ts:132` `clampToImage` — `clamp(x,0,image.width)`/`clamp(y,0,image.height)`. 별도 테스트(`clamp` describe 블록: 하한/상한/범위내 3케이스) 확인 |
| TC-FE-105 | PASS | [정적+테스트] `coordinateTransformer.ts:106` `computeWrappingBox` — min/max 순회로 외접박스 계산, 테스트로 3점 세트·단일점 케이스 모두 확인 |
| TC-FE-106 | PASS | [정적+테스트] `coordinateTransformer.ts:71` `rotate2DPoints` — 90°/360°/180° 회전 테스트 전부 기대값과 일치(`toBeCloseTo`) |
| TC-FE-107 | PASS(카탈로그 제목 정정) | [정적+테스트] `maskRleConverter.ts` 전체 열람 — `maskToRle`/`rleToMask`/`imageDataToRLE`/`rleToImageData`/`isEmptyRle` 5개 함수만 존재, **Polygon 변환 함수는 파일 전체에 0건**(`grep -n Polygon` 무결과). 테스트 파일 자체 헤더 주석도 "CVAT MASK ↔ RLE 변환 테스트"로 Polygon 언급 없음. `frontend/src` 전체에서 RLE↔Polygon 상호변환 함수 검색(`grep -rln rleToMask\|maskToRle`)해도 `MaskBrushTool.tsx`/`MaskEraserTool.tsx` 2곳만 이 모듈을 소비할 뿐 Polygon 변환 경로 없음. **카탈로그 표제 "MASK↔RLE↔Polygon 변환"이 미구현 기능을 포함한 오류로 판단 — 본 회차에서 "MASK↔RLE 변환"으로 정정**(H-frontend-e2e.md:245, 내 라인범위 내). 실제 구현(MASK↔RLE·imageData↔RLE 왕복)은 랜덤 마스크 1000회 부하 테스트까지 포함해 정상 동작 확인 |
| TC-FE-108 | PASS | [정적+테스트] `trackInterpolation.ts` — `interpolateBBox`(선형보간, t clamp [0,1])·`interpolateTrack`(범위 밖 extrapolation 없이 가장 가까운 끝값, 구간 탐색 후 t 계산). `trackInterpolation.test.ts` 존재 확인 |
| TC-FE-109 | PASS | [정적] `pages/label/LabelingPage.tsx:287-319` `frameNaturalSize` state + `handleImageSize` — 주석 "하드코딩 1920×1080 제거", `CanvasShell`이 이미지 onload 시 실측 naturalWidth/Height 통지 → `viewKeyRef`로 영상/해상도 변경 시에만 `resetView()`(동일 영상·해상도면 zoom/pan 유지, 스트레치 없음) |

**H-5 소계**: 8 PASS / 0 FAIL / 0 PARTIAL / 0 BLOCKED / 0 N/A / 0 확인필요 (카탈로그 정정 2건: TC-FE-103 라인범위, TC-FE-107 표제)

---

## H-7. BatchStageIndicator (STAGE_ORDER 드리프트 함정) (5건)

> **드리프트 함정 재현 시도 결과**: BE `backend/.../batch/pipeline/BatchPipelineConfig.java`의 `List.of(markingLoad, vlm, frame, yolo, sam2, interp)`(post-marking) + `List.of(deid)`(pre-marking) 선언 순서(DEIDENTIFY→MARKING→VLM→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE, CLAUDE.md 서술과 일치)와 FE `BatchStageIndicator.tsx:16-24` `STAGE_LABEL` 맵의 키 순서를 1:1 대조 — **완전 일치, 드리프트 없음**. 더 중요한 것은 FE 렌더 로직(`:70` `stages.map`)이 이 맵 순서에 의존하지 않고 **BE가 내려준 `stages` 배열 순서를 그대로 순회**한다는 점(`:12-15` 주석 "FE 는 순서를 가정하지 않고 배열을 그대로 렌더") — 즉 향후 BE가 `BatchPipelineConfig`의 `List.of(...)` 순서를 재배치해도 FE 는 하드코딩된 순서 가정이 없어 자동으로 정합 유지. 함정이 이름과 달리 **현재는 방어돼 있음**.

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-FE-126 | PASS | [정적] `BatchStageIndicator.tsx:66` `if (!stages \|\| stages.length === 0) return null;` — 정확 일치 |
| TC-FE-127 | PASS | [정적] `BatchStageIndicator.tsx:16-24` `STAGE_LABEL` 7종 매핑(DEIDENTIFY→'비식별', MARKING→'마킹', VLM→'VLM', FRAME_EXTRACT→'프레임추출', YOLO→'AI 탐지', SAM2→'AI 분할', INTERPOLATE→'보간') — 기대결과 문구와 정확 일치 |
| TC-FE-128 | PASS | [정적] `BatchStageIndicator.tsx:26-28`(`STAGE_LABEL_FALLBACK = '처리중'`) + `:81`(`STAGE_LABEL[stage.name] ?? STAGE_LABEL_FALLBACK`) — YOLO/SAM2 같은 기술 코드명이 화면에 노출되지 않고 매핑된 한글 라벨("AI 탐지"/"AI 분할")로만 표시됨. 미지 코드는 "처리중" 폴백 |
| TC-FE-129 | PASS | [정적+BE 대조] `BatchStageIndicator.tsx:12-15`(주석)+`:70`(`stages.map((stage, idx) => {`) — FE 순서 가정 없이 배열 순회. BE `BatchPipelineConfig.java` 의 `List.of(...)` 선언 순서와 FE 매핑 키 순서 대조 결과 완전 일치(위 서두 참조) |
| TC-FE-130 | **PARTIAL(2차 H-ISSUE-70 재확인 — 미해소)** | [정적] `BatchStageIndicator.tsx:30-57` `StageIcon` — DONE(Check)/PROGRESS(Loader2)/FAIL(X) 아이콘 전부 `aria-hidden`(:34,41,48), PENDING 은 장식용 `<div>`(:52-56). `grep -n "aria-label\|sr-only\|role="` 전체 파일 0건 — 상태(완료/진행중/실패/대기)를 스크린리더가 인지할 방법이 없음. **2026-08-02 2차 검증에서 이미 `H-ISSUE-70`(MEDIUM)으로 보고된 것과 동일 결함이며, 이번 회차 코드에도 수정 미반영(회귀 아님, 단순 미해소)** — 아래 이슈로 재기록(신규 ID 부여, 원 이슈 참조 명시) |

**H-7 소계**: 4 PASS / 0 FAIL / 1 PARTIAL / 0 BLOCKED / 0 N/A / 0 확인필요

---

## 이슈 목록

### [H-ISSUE-101] TC-FE-130 — BatchStageIndicator 단계 상태가 스크린리더에 노출되지 않음 (2차 H-ISSUE-70 미해소 재확인)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WCAG 2.1 AA 준수 요구(루트 CLAUDE.md 포털 섹션) 및 `component.md`의 "색상만으로 정보 전달 금지(아이콘/텍스트 병행)" 원칙에 따라, 배치 단계별 상태(완료/진행중/실패/대기)는 스크린리더 사용자도 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `frontend/src/components/common/BatchStageIndicator.tsx`의 `StageIcon`(:30-57)이 렌더하는 `Check`/`Loader2`/`X` 아이콘은 전부 `aria-hidden`(각 :34,41,48)이고 PENDING 상태는 장식용 `<div>`(:52-56)뿐이다. 단계마다 노출되는 텍스트(:77-82)는 단계 이름("비식별", "마킹" 등)만이며, 상태를 나타내는 `aria-label`/`sr-only`/`role` 속성이 컴포넌트 전체에 0건이다(`grep -n "aria-label\|sr-only\|role="` 결과 0건, 3차 시점 재확인). 2026-08-02 2차 검증에서 동일 결함이 `H-ISSUE-70`으로 이미 보고됐으나, 3차 시점 코드(HEAD `e065da42`)에도 수정이 반영되지 않았다.
- **재현/확인 경로**: `grep -n "aria-hidden\|aria-label" frontend/src/components/common/BatchStageIndicator.tsx` → aria-hidden 3건, aria-label 0건. 스크린리더(VoiceOver 등)로 마킹/영상상세 화면 진입 → `data-testid="batch-stage-indicator"` 영역 탐색 시 단계 이름만 낭독되고 상태 정보 낭독 없음.
- **영향**: 접근성(WCAG 2.1 AA 1.1.1/4.1.2 상당) — 시각장애 사용자가 배치 처리 실패(FAIL) 여부를 화면에서 인지할 수 없어 대응이 늦어질 수 있음. 보안 영향 없음.
- **수정 방향(제안)**: 각 단계 아이콘 wrapper 또는 단계 컨테이너에 상태를 서술하는 `aria-label`(예: `` `${label} — ${status==='DONE'?'완료':status==='PROGRESS'?'진행중':status==='FAIL'?'실패':'대기'}` ``) 또는 `sr-only` 텍스트를 추가. 아이콘 자체의 `aria-hidden`은 유지하되 상위 요소에 접근성 트리 정보를 부여. (2차 이슈와 동일 제안 — 재수록)

---

## 카탈로그 정정 (내 라인범위 내 Edit로 직접 반영, 2건)

1. **TC-FE-103**(H-frontend-e2e.md:241) — 근거 라인 `coordinateTransformer.ts:57-61` → `:57-65`로 정정. 구 범위는 `translateFromCanvas` 함수의 역회전 계산 및 `return` 문(62-65줄)을 누락하고 있었다.
2. **TC-FE-107**(H-frontend-e2e.md:245) — 표제 "maskRleConverter MASK↔RLE↔Polygon" → "maskRleConverter MASK↔RLE 변환"으로 정정 + 사유 주석 추가. 실제 `maskRleConverter.ts`에는 Polygon 변환 함수가 전혀 없다(MASK↔RLE·imageData↔RLE 왕복만 제공). 테스트 파일 자체 설명도 "MASK ↔ RLE 변환"으로 Polygon을 언급하지 않는다 — 미구현 기능을 표제에 포함한 카탈로그 오류였다.

---

## 이전 회차(2026-08-02 2차) 이슈 해소 여부

내 담당 라인범위(H-2/H-4/H-5/H-7)에 걸리는 2차 이슈는 **H-ISSUE-70**(TC-FE-130, BatchStageIndicator a11y) 1건뿐이었다. **미해소** — 위 H-ISSUE-101로 재기록(동일 결함, 코드 변경 없음). 그 외 2차 ISSUES.md의 H 관련 이슈(H-ISSUE-02/41/43/103/143/01/03/42/70/81/101~102/141~147/44/82/104~105/144~145 등)는 H-1/H-3/H-6/H-8~H-18 섹션 소관으로 내 라인범위 밖이라 이번 part 에서 재검증하지 않았다.

---

## 종합 집계 (H-part6)

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| H-2 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| H-4 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| H-5 | 8 | 8 | 0 | 0 | 0 | 0 | 0 |
| H-7 | 5 | 4 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **38** | **37** | **0** | **1** | **0** | **0** | **0** |

신규/재기록 이슈: **H-ISSUE-101** 1건(MEDIUM, a11y, 2차 H-ISSUE-70 미해소 재확인). 카탈로그 정정 2건(TC-FE-103 라인범위, TC-FE-107 표제).
