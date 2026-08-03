# H 클러스터 part2 — H-2(라우터/코드스플리팅) + H-4(useLabelStore) + H-5(좌표변환/캔버스 유틸) 검증 결과

> 대상: `docs/test-cases/H-frontend-e2e.md` `## H-2`(11건: TC-FE-023~032, TC-E2E-002) + `## H-4`(14건: TC-FE-088~101) + `## H-5`(8건: TC-FE-102~109) = **33건**
> 회차: 2026-08-02 2차 · 이슈 ID 범위: H-ISSUE-21~40
> 코드 기준: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801`
> frontend `localhost:13000`(docker `klid-frontend`, Vite dev 모드) · backend `localhost:18081`(docker `klid-backend`)

## 0. 검증 환경 실측

| 항목 | 실측 |
|---|---|
| 스택 | `docker ps` — frontend/backend/ai-server/mock-server/postgres 전부 `Up 19h (healthy)` |
| frontend 접근 | `curl -o /dev/null -w '%{http_code}' http://localhost:13000/` → **200** |
| dev 토큰 발급 | `POST http://localhost:18081/api/v1/dev/tokens` (REVIEWER/sub=1001) → 200, JWT 정상 발급 |
| baseline | `_raw/test-baseline.md`(2026-08-01): frontend `npx vitest run` **1,951 tests / 1,951 pass / 0 fail**(332 파일) |
| 브라우저 관찰 | Playwright MCP 로 `http://localhost:13000/ingress` 진입 → **`/dashboard`로 즉시 redirect 실동작 확인**(TC-FE-023). 단, 이 브라우저 인스턴스가 **다른 병렬 QA 서브에이전트와 세션/스토리지를 공유**하는 정황(새 탭에서도 동일 `klid_jwt` 토큰 관찰, 다른 파트가 연 페이지의 네트워크 로그가 섞여 관찰됨)을 확인해 **상호 오염 방지를 위해 대화형 브라우저 조작은 읽기 전용 1건(redirect 확인)으로 제한**하고, 이후 판정은 **정적 코드 대조 + 기존 FE 단위테스트(격리된 vitest 프로세스, 오염 없음)** 중심으로 수행함 — 근거확인 컬럼에 [실동작]/[정적] 명시 |

---

## 1. H-2. 라우터 구조/코드스플리팅 (11건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-FE-023 | PASS | [실동작]+[정적] | Playwright 로 `/ingress` 진입(인증 상태) → **`/dashboard`로 replace redirect** 즉시 확인. 코드: `router/index.tsx:233` `{ index: true, element: <Navigate to="/dashboard" replace /> }` — 카탈로그 라인과 정확히 일치 |
| TC-FE-024 | PASS | [정적] | `router/index.tsx:218-224` `/label/:id` 는 `path:'/'`(AppLayout) 트리 **바깥**에 최상위로 정의돼 LNB/GNB(AppLayout) 미적용. `InternalRoute` 인가 가드는 유지 |
| TC-FE-025 | PASS | [정적] | `internalReviewerOnly=[Role.REVIEWER]`(line 149) 사용처 grep 결과 review index=**290**, stat/overall=**337**, augment index=**350**, manage/users=**380** — 카탈로그 근거 라인 4곳 전부 **정확히 일치**(drift 없음) |
| TC-FE-026 | PASS | [정적] | `router/index.tsx:459` `{ path: '*', element: <AppErrorPage status={404} /> }` — `/` 트리(AppLayout) children 최하단, 라인 정확히 일치 |
| TC-FE-027 | PASS | [정적] | `router/index.tsx:417-424` manage 하위 `path:'*'` → `PlaceholderPage title="관리"` + `InternalRoute allow={internalReviewerOnly}`, 라인 정확히 일치. react-router v6 splat(`*`)은 빈 나머지 세그먼트도 매칭하므로 `/manage` 단독 진입도 커버 |
| TC-FE-028 | PASS | [정적] | `lib/devLogin.ts:isDevLoginEnabled()` = `import.meta.env.DEV \|\| VITE_DEV_LOGIN_ENABLED==='true'`. Vite 는 `import.meta.env.DEV`/env 값을 빌드타임 리터럴로 치환하므로 두 값 모두 false 인 prod 빌드는 `if(isDevLoginEnabled())` 블록이 상수 false 로 접혀 tree-shaking 대상이 된다. **이번 라운드에 실제 `npm run build` 를 재실행해 청크 부재를 재확인하지는 않음**(빌드 실행 금지 규칙) — 동일 패턴의 자매 플래그(`isDevUploadEnabled`)가 `UNCERTAINTIES.md` #25 에서 이미 "prod 빌드 기준 dead-code 확정"으로 정리돼 있어 그 판례를 그대로 적용 |
| TC-FE-029 | PASS | [정적] | `router/index.tsx:183-203` 주석·코드 정확히 일치. `isDevUploadEnabled()`(`lib/devUpload.ts`) 판정 로직도 TC-FE-028 과 대칭. `UNCERTAINTIES.md` #25 에서 **이미 "✅ 확정"** — 재검증으로 반증 시도(라우트 배열 조건문 재확인) 결과 동일 결론 |
| TC-FE-030 | PASS | [정적] | `router/index.tsx:160-166` `PortalRoute` = `ChannelGuard channel="PORTAL"` + `RoleGuard allow=[PORTAL_USER]}` 이중 래핑, 라인 정확히 일치 |
| TC-FE-031 | PASS | [정적] | `router/lazyWithRetry.ts` 코드 전문 검토: 1차 실패 → 300ms 지연 후 1회 재시도 → 그래도 실패 시 `sessionStorage` 플래그로 **버스트당 1회만** `window.location.reload()`, 성공 시 플래그 제거(다음 실패 버스트에 다시 reload 가능). **전용 자동테스트 파일 없음**(카탈로그 근거도 파일명만 명시하고 테스트 파일을 인용하지 않아 정합) — 코드 검증만으로 판정 |
| TC-FE-032 | PASS | [정적] | `router/index.tsx:127-137` `PageFallback`(`Spinner label="페이지 로딩"`) + `withSuspense` 정확히 일치 |
| TC-E2E-002 | PASS | [정적] | 5개 테스트 파일 전부 실존 확인: `deepLinkHydrationGuard.test.tsx`(hydration 대기 중 미redirect), `manageGuard.test.tsx`, `portalGuard.test.tsx`, `reviewGuard.test.tsx`, `augmentExportGuard.test.tsx`. 테스트는 `RoleGuard`/`ChannelGuard` 를 자체 `MemoryRouter` 래퍼로 감싸 검증하는 방식이라 **실제 라우터 배선과 allow 목록이 일치하는지 별도 대조** 필요 — `router/index.tsx` 의 `internalReviewerOnly`(manage/review/augment/overall) · `internalAllRoles`(video/task/dashboard/stat/notice/history) · `portalOnly` 조합이 테스트 wrapper 의 `Role.REVIEWER`/`portalOnly` 등과 **동일하게 일치**함을 확인 — wrapper 와 실배선 간 드리프트 없음. baseline(`test-baseline.md`) 상 FE 전체 1,951/1,951 통과에 포함 |

### H-2 반증 시도 기록

1. **manage 인덱스 없는 진입** — `/manage` 단독 URL(하위 세그먼트 없음)이 react-router v6 splat(`*`) 매칭 규칙상 catch-all `PlaceholderPage`(REVIEWER 게이팅)로 떨어지는지 정적 확인 — v6 splat 은 "0개 이상" 세그먼트를 매칭하므로 성립, 미인가 우회 없음.
2. **dev 플래그 조합 우회** — `isDevLoginEnabled`/`isDevUploadEnabled` 둘 다 `import.meta.env.DEV`(Vite 자동 주입, 사용자가 조작 불가) 우선 OR 조건이라 **prod 빌드에서 임의 문자열/미설정은 전부 false**(fail-closed) 로 떨어짐 — 화이트리스트 반대의 "명시적 true 문자열만 허용" 패턴이라 우회 여지 없음.
3. **lazyWithRetry 무한루프 가능성** — 재시도 실패 후 reload 플래그를 `sessionStorage`에 남기므로 새 세션(플래그 없음)마다 다시 1회 reload 가능 — 무한 reload 루프는 "같은 세션 내 재실패"로 제한되어 방지됨(코드 47-79행 로직 확인).

---

## 2. H-4. useLabelStore (Zustand) (14건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-FE-088 | PASS | [정적] | `setLabels`(실제 위치 `stores/useLabelStore.ts:486-495`, 카탈로그는 401 — **근거 드리프트 +85줄**) — `labels`/`dirtyLabels`/`undoStack`/`redoStack`/`selectedLabelId` 전부 초기화 + `hiddenLabelIds`/`lockedLabelIds`도 함께 초기화(기대결과 "전부 초기화"와 일치, 오히려 더 넓게 초기화). 직접 테스트 `useLabelStore.test.ts:71` `setLabels_시_dirty_undo_초기화` |
| TC-FE-089 | PASS | [정적] | `addLabel`(실제 497-503, 카탈로그 412 — **드리프트 +85줄**) — `dirtyLabels.add(label.id)` 확인. 직접 테스트 `useLabelStore.test.ts:41` `addLabel_시_dirtyLabels에_추가` |
| TC-FE-090 | PASS | [정적] | `offsetShape`(66-89, 카탈로그와 **정확히 일치**) — BBOX(4필드 평행이동)/POLYGON(짝수인덱스 dx, 홀수인덱스 dy)/KEYPOINT(x,y 각각 이동) 타입별 분기 확인. 간접 테스트: `useLabelStore.clipboard.test.ts` `완전동일_좌표_존재_시_offset_적용`(BBOX 기준 +10 오프셋 검증) |
| TC-FE-091 | PASS | [정적] | `clampShape`(92-117, **정확히 일치**) — `cx=(v)=> w!=null ? clamp(v,0,w) : max(v,0)` — **w/h 미지정 시 하한 0만 적용**(상한 무시) 정확히 일치. 테스트 `useLabelStore.clipboard.test.ts`: `offset_후_이미지_경계_clamp`(상한 15로 clamp) + `붙여넣기_실측_dims_미확정시_상한_미적용_하한만`(imageWidth/Height 미전달 시 상한 미적용, "1080 하드코딩" 회귀 아님을 명시 검증) |
| TC-FE-092 | PASS | [정적] | `mergeAutoLabels`(636-653) — `mergeDetections` 로 중복(같은 클래스+IoU) 스킵, `merged.length` 반환. 테스트 파일 `useLabelStore.mergeAuto.test.ts` 실존 |
| TC-FE-093 | PASS | [정적] | `revertSaveEvent`(655-734) — UPDATED/ADDED/DELETED 3분기 + `reverted`/`skipped` 카운트 반환. 테스트 `useLabelStore.revert.test.ts` 실존 |
| TC-FE-094 | PASS | [정적] | `stashPendingTracks`/`drainPendingTracks`(736-758) — srcSn 별 누적 stash, drain 시 해당 키만 제거(불변). 테스트 `useLabelStore.pendingTracks.test.ts` 실존 |
| TC-FE-095 | PASS | [정적] | `toggleLabelVisibility`(567-572) — 새 `Set` 생성(불변성 준수), `dirtyLabels` 미영향(별개 상태). 테스트 `useLabelStore.visibility.test.ts` 실존 |
| TC-FE-096 | PASS | [정적] | `updateLabel`/`removeLabel` 진입부에서 `lockedLabelIds.has(id)` 체크 후 조기 return(507,518) — dirty/undoStack 변화 없음(no-op) 확인. 테스트 `useLabelStore.lock.test.ts` 실존 |
| TC-FE-097 | PASS | [정적] | `copyLabels`/`pasteLabels`(582-634) — `sourceRawSn` 기준 크로스영상 판정(`crossVideo`)해 `trackId` 제거. 테스트 `useLabelStore.clipboard.test.ts` 16건 케이스(크로스영상/동일영상/undo/클립보드 유지 등) 실존 |
| TC-FE-098 | PASS | [정적] | `KeypointShape.keypoints`(`types.ts:114-117`) — COCO-17 삼중값 `{x,y,v}` 배열. 테스트 `useLabelStore.keypoint.test.ts` 실존 |
| TC-FE-099 | PASS | [정적] | `imageAdjust`(`DEFAULT_IMAGE_ADJUST` 상수로 세션 초기화, `setImageAdjust`/`resetImageAdjust`) — zustand 인메모리 상태라 영속 계층 없음(스토어 정의에 persist 미들웨어 없음, plain `create()`). 테스트 `useLabelStore.imageAdjust.test.ts` 실존 |
| TC-FE-100 | PASS | [정적] | `activeLabelId` 필드 자체는 단순 setter(store 레벨, `useLabelStore.activeLabel.test.ts` 로 초기값 null/set/reset 검증). 파생 프리셋 기본값 로직(`resolveDefaultLabel`, null 시 sortNo 최소 활성 라벨)은 별도 순수함수 `canvas/layers/resolveDefaultLabel.ts` + 전용 테스트 `resolveDefaultLabel.test.ts` 로 분리 검증 — 두 테스트를 합쳐 기대결과 "activeLabelId → resolveDefaultLabel" 흐름 성립 확인 |
| TC-FE-101 | PASS | [정적] | `useLabelStore.ts:4` `import { clampPan as clampPanByScale } from '.../canvasGeometry'` + store `export function clampPan(...)`(실제 386-406, 카탈로그 319-335 — **드리프트 +67줄**)이 `canvasGeometry.clampPan` 에 위임. `canvasGeometry.test.ts` 의 `clampPan_letterbox축은_중앙유지_pan0`/`clampPan_줌인축은_초과분_절반까지만_허용` + `useLabelStoreView.test.ts` 로 scale 기반 clamp 회귀 검증 |

### H-4 반증 시도 기록 (canvas scale 함정 · Zustand 불변성 중점)

1. **불변성 위반 스캔** — `addLabel`/`updateLabel`/`removeLabel`/`toggleLabelVisibility`/`toggleLabelLock`/`pasteLabels`/`mergeAutoLabels` 전 액션이 **기존 배열/Set 을 직접 mutate 하지 않고 새 배열/Set 을 생성 후 `set()`** 하는 패턴을 소스 레벨로 전수 확인 — `frontend-coding-style.md` 불변성 규칙 위반 0건.
2. **MASK/SEGMENT 타입 좌표이동 갭(참고 관찰, 이슈 미승격)** — `offsetShape`/`clampShape` 는 `types.ts` 의 4개 Shape 중 **BBOX/POLYGON/KEYPOINT 만 명시 분기**하고 `MASK` 타입은 `return {...shape}`(no-op, dx/dy 미적용·clamp 미적용)로 폴백한다. `shapeEquals` 도 MASK 비교 시 `false` 고정이라 붙여넣기 시 중복판정·offset 이 MASK 에는 적용되지 않는다. 다만 **카탈로그 TC-FE-090 기대결과 자체가 "BBOX/POLYGON/KEYPOINT" 3종만 명시**하고 있어 케이스 범위 밖이며, `MASK` shape 는 `api.ts:98`(`return { type: 'MASK' }`, 좌표 없는 placeholder) · `revertSaveEvent` 주석의 "좌표복원 불가(SEGMENT 등)"과 일관되게 **BE SEGMENT 라벨을 FE 가 편집 불가 opaque 값으로 다루는 의도된 설계**로 판단 — 결함으로 승격하지 않음(H-3/캔버스 편집 클러스터에서 SEGMENT 편집 동작이 별도로 검증되면 교차 확인 권장).
3. **busyGeneration 토큰 0 falsy 함정** — `beginBusy` 반환값이 `{ok:false}` 판별 유니온이라 세션 첫 토큰(0)을 `if(!token)` 로 오판하지 않도록 타입 설계된 것을 확인(H-3 범위이나 H-4 파일 내 교차 확인) — 함정 없음.

---

## 3. H-5. 좌표 변환/캔버스 유틸 (canvas scale 함정) (8건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-FE-102 | PASS | [정적] | `coordinateTransformer.ts:43-51` `translateToCanvas` — 이미지중심 회전 후 `*scale+left/top`, 카탈로그 라인 **정확히 일치**. 테스트 `coordinateTransformer.test.ts:37` `translateToCanvas_scale_offset_적용` |
| TC-FE-103 | PASS | [정적] | `translateFromCanvas`(57-65, 카탈로그 57-61 — 함수 종료 라인만 약간 드리프트, 시작 라인 일치) — `(canvasX-left)/scale` 후 `-angle` 역회전으로 정확한 역변환. 테스트 `coordinateTransformer.test.ts:26` 라운드트립 + `:45` **회전 각도 있는 상태 라운드트립**까지 검증(단순 왕복이 아니라 회전 포함 케이스로 canvas scale 함정 반증) |
| TC-FE-104 | PASS | [정적] | `clampToImage`(132-137, 카탈로그 132 — 시작 라인 일치) — `clamp(x,0,image.width)`/`clamp(y,0,image.height)`. 테스트 `coordinateTransformer.test.ts:98-106` 범위내/하한/상한 3케이스 |
| TC-FE-105 | PASS | [정적] | `computeWrappingBox`(106-120, 카탈로그 106 — 일치) — min/max 스캔으로 외접 사각형. 테스트 `:80` 다중점 + `:88` 단일점 |
| TC-FE-106 | PASS | [정적] | `rotate2DPoints`(71-87, 카탈로그 71 — 일치) — `(cx,cy)` 기준 각도 회전. 테스트 `:58` 90도 + `:64` 360도(원위치 복귀) + `:70` 다중점 |
| TC-FE-107 | PASS | [정적] | `maskRleConverter.ts` — `maskToRle`/`rleToMask`(2D mask↔RLE) + `imageDataToRLE`/`rleToImageData`(RGBA alpha↔RLE) 왕복 정합을 `maskRleConverter.test.ts` 8개 describe 블록(단순사각형/CVAT 첫run 규칙/빈mask/1픽셀/imageData/32×32 100회/16×16 1000회 부하/1M 픽셀 상한 보안)으로 광범위 검증. **참고**: 케이스명의 "↔Polygon" 부분은 FE 코드에 없음 — `CLAUDE.md` "CVAT 포팅 전략" 표가 MASK↔RLE↔Polygon 변환의 포팅 대상 언어를 **Java(BE)로 명시**하고 있어(portable-modules/02, Phase 6) FE 는 캔버스 픽셀 편집에 필요한 Mask↔RLE 구간만 구현하는 것이 설계 의도로 판단 — 갭 아님(카탈로그 케이스명이 module 원 출처명을 그대로 물려받아 다소 과잉 표기된 것으로 추정) |
| TC-FE-108 | PASS | [정적] | `trackInterpolation.ts:19-27` `interpolateBBox`(선형보간, t clamp[0,1]) + `:34-58` `interpolateTrack`(구간 탐색 + 범위밖 extrapolation 금지, 가장 가까운 끝값 반환). 테스트 `trackInterpolation.test.ts` 실존(파일 확인, BE `TrackInterpolator` 가 정답이고 FE 는 보조 미리보기 용도임을 파일 상단 주석에서 명시) |
| TC-FE-109 | PASS | [정적] | `LabelingPage.tsx` 프레임 목록 구성부(약 235-259, 카탈로그 242-274 — 실제 `handleImageSize`/`shouldResetView` 연동부는 293-320 부근으로 **드리프트 있음**, 기능은 동일 파일 내 인접 위치) — 주석 "imageWidth/Height 는 하드코딩하지 않는다 — 캔버스가 로드된 이미지의 실측 naturalWidth/Height 를 geometry 기준으로 사용" 명시. `handleImageSize` 콜백이 `shouldResetView(prev,next)`(`stores/useLabelStore.ts:415-422`: videoId 상이 또는 width/height 상이 시 true)로 뷰 리셋 여부 판정 |

### H-5 반증 시도 기록 (canvas scale 함정 중점 — 확증편향 금지 대상)

1. **회전 상태에서의 좌표 왕복 정합** — `translateToCanvas`→`translateFromCanvas` 라운드트립을 `angle=0` 뿐 아니라 **회전각 있는 상태**로도 테스트(`coordinateTransformer.test.ts:45`)가 커버함을 확인. 역변환이 "역순으로 -angle 회전"을 정확히 수행(순변환은 회전→스케일/오프셋, 역변환은 오프셋제거/스케일제거→역회전 순서로 수학적으로 대칭) — 줌/팬/회전 복합 상태에서 좌표 어긋남 없음.
2. **letterbox 축 팬 트랩** — `canvasGeometry.clampPan`(H-4 TC-FE-101 경유 확인)이 `scaled ≤ canvas`(letterbox 축, 즉 캔버스보다 이미지가 작은 축)에서 `pan=0` 강제 — 이미지가 캔버스보다 작은 축에서 팬 여백이 생기는 함정을 원천 차단. 반대로 확대(zoom>1)축은 `|pan| ≤ (scaled-canvas)/2` 로 이미지가 캔버스를 항상 덮도록 제한 — 두 축을 **동일 함수에서 분기**해 한쪽만 처리되는 함정 없음.
3. **0-division/NaN 가드** — `buildGeometry`(`canvasGeometry.ts:49-51`)가 이미지 실측 크기 미확정(로드 전 0/음수)일 때 `scale=0` 폴백을 반환(NaN 전파 차단), 테스트 `이미지_미로드_0크기_가드_NaN없이_scale0_반환` 확인.
4. **clampToImage 가 회전을 무시하는지** — `clampToImage` 는 이미지 좌표계(회전 역변환 이후)에서만 동작하도록 설계돼 있어 캔버스 좌표를 직접 clamp 하지 않음(역변환 후 좌표에 적용하는 게 올바른 사용법) — 호출부(`LabelingPage.tsx` 등)가 이 계약을 지키는지는 H-3(캔버스 드로잉 도구) 범위라 이번 파트에서는 유틸 자체의 정확성만 확인.

---

## 4. 근거(file:line) 드리프트 요약 — 카탈로그 정합성 결함(기능 결함 아님)

| TC-ID | 카탈로그 근거 | 실제 위치 | 드리프트 |
|---|---|---|---|
| TC-FE-088 | `stores/useLabelStore.ts:401` | `:486-495` | +85줄 |
| TC-FE-089 | `stores/useLabelStore.ts:412` | `:497-503` | +85줄 |
| TC-FE-101 | `stores/useLabelStore.ts:319-335` | `:386-406` | +67줄 |
| TC-FE-109 | `pages/label/LabelingPage.tsx:242-274` | 프레임 구성부 235-259 / `handleImageSize` 실제 로직 293-320대 | 기능은 동일, 정확 라인 재확인 필요 |

원인 추정: 파일 상단부(busy 상태 관련 타입·필드·주석 블록 등)에 코드가 추가되며 하단 함수들의 라인이 일괄 밀린 것으로 보임(TC-FE-088/089 드리프트량이 +85줄로 동일). TC-FE-090/091/092~101 등 파일 앞쪽(순수 헬퍼 함수)이나 뒤쪽(액션 정의부이지만 088/089 이후 위치)은 오히려 일치하거나 드리프트가 없어, **088/089 두 건만 국소적으로 어긋남**.

---

## 5. 집계

| 구분 | 건수 |
|---|---:|
| 대상 케이스 | 33 |
| PASS | 33 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| 신규 이슈(H-ISSUE-21~40) | **0건 — 발견된 결함 없음** |

## 6. 결론

H-2(라우터/코드스플리팅)·H-4(useLabelStore)·H-5(좌표변환/캔버스 유틸) 33건 전건 **PASS**. 확증편향 금지 대상으로 지정된 3개 함정(canvas scale 좌표변환, 코드스플리팅 lazy load 실패 처리, Zustand 불변성)을 반증 관점에서 재확인했으나 결함을 찾지 못함:
- **canvas scale 함정**: 회전 포함 라운드트립 테스트, letterbox/줌인 축 분기 clamp, 0-division 가드가 모두 유닛테스트로 뒷받침됨.
- **코드스플리팅 lazy load 실패**: `lazyWithRetry` 가 재시도→1회성 reload→플래그 리셋의 3단 방어를 구현(전용 자동테스트는 없어 코드 리딩으로만 검증).
- **Zustand 불변성**: 전 액션이 새 배열/Set 생성 패턴 준수. MASK/SEGMENT 타입 좌표이동 미지원은 카탈로그 케이스 범위 밖이자 의도된 설계로 판단해 이슈 미승격(§H-4 반증 시도 기록 2번 참고).

근거 드리프트 4건(§4)은 카탈로그 정합성 문제로, 다음 카탈로그 갱신 시 라인 번호 재동기화 권장.
