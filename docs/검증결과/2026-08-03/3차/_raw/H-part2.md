# H 클러스터 part2 — H-3. LabelingPage(라벨링 캔버스) 앞 1/3 (TC-FE-033~063, 31건)

- 담당 범위: `docs/test-cases/H-frontend-e2e.md` **68~104행** (H-3 섹션 앞부분 31건)
- 검증 일자: 2026-08-03 / 3차
- 검증 대상 커밋: `e065da42` (워크트리 `qa-0803`), 컨테이너 이미지 동일 커밋으로 재빌드됨(`_raw/stack-bringup.md`)
- 실동작 환경: frontend `localhost:13000`, backend `localhost:18081`(context-path `/api`), 파이프라인 데이터 **rawSn=101 / srcSn 468~477**(`_raw/pipeline-drive.md`), WORKER=2001 / REVIEWER=1001
- 사용 도구: Playwright(실브라우저 Chromium) + curl(BE 직접)
- ⚠ **실브라우저는 병렬 검증 에이전트와 세션을 공유**해 세션 도중 다른 에이전트가 토큰·URL 을 바꾸는 간섭이 반복 발생했다. 이 때문에 일부 케이스는 실동작 대신 정적+기존 자동테스트 근거로 판정했다(각 행에 `[정적]` 표기).

---

## 0. 착수 전 대전제 확인 — 라벨링 캔버스 비식별 서빙 (★ 반증 필수 항목)

CLAUDE.md "라벨링 캔버스는 비식별 프레임을 서빙한다"(2026-07-30 확정)를 **실동작으로 반증 시도**했다.

| 요청 | 응답 | 크기 | md5 |
|---|---|---|---|
| `GET /api/v1/frames/468/image` (WORKER) | 200 `image/jpeg`, `Cache-Control: no-store` | 13,164 | `efadbd02…` |
| `GET /api/v1/frames/468/image?raw=true` (**WORKER**) | 200 | 13,164 | `efadbd02…` ← **DEID 와 동일 = raw 무시됨** |
| `GET /api/v1/frames/468/image?raw=true` (**REVIEWER**) | 200 | **9,441** | `be89bcc1…` ← 원본(다른 바이트) |
| `GET /api/v1/frames/468/image` (REVIEWER) | 200 | 13,164 | `efadbd02…` |

- **WORKER 의 `raw=true` 는 무시되고 DEID 가 강제**된다(md5 동일). REVIEWER 만 원본을 받는다 → 정책 준수 **PASS**.
- `GET /v1/frames/468/labels` 응답 `"frameImageType":"DEID"` → 화면 헤더 뱃지도 실브라우저에서 `DEID` 로 렌더 확인.
- FE 호출부 `LabelingPage.tsx:160` 은 `useImageBlob(data?.srcSn, { portalMode })` 로 **`raw` 옵션을 아예 넘기지 않는다** → `useImageBlob.ts:77-81` 이 `params` 자체를 붙이지 않음. 즉 **캔버스에는 REVIEWER 원본 열람 동선이 FE 에 존재하지 않는다**(CLAUDE.md "REVIEWER 원본 열람 동선은 FE 에 두지 않는다" 와 정합).
- 게이트가 걸린 미디어 응답의 `Cache-Control: no-store` 실측 확인.

## 0-2. konva.js 렌더링 실측

`/label/468` 진입 후 DOM 실측:
```
canvas 3개 (konva Stage 레이어), 각 1280×720 (attribute w/h == style w/h → DPR 스케일 왜곡 없음)
.konvajs-content 존재, getBoundingClientRect w=1280 h=720
프레임 스트립 썸네일 img 10개, 전부 blob: URL, naturalWidth/Height = 320×240 (비식별 프레임 실측 해상도)
```
- 캔버스 레이어 마운트·이미지 blob 로드 모두 정상. 라벨 `person #1 BBOX` 가 객체 목록·속성 패널에 렌더됨(라벨 좌표 `[[61,61],[211,211]]`, 이미지 320×240 범위 내).
- 참고(케이스 범위 밖): MCP 기본 뷰포트 1200px 에서 stage 가 1280px 로 측정되어 좌측으로 `x=-156` 오버플로했다. 데스크톱 폭에서는 재현되지 않으며 H-13(반응형/a11y) 소관이라 이 파트에서는 결함으로 집계하지 않고 사실만 기록한다.

---

## 1. 케이스별 판정

| ID | 케이스명 | 판정 | 근거 확인 |
|----|---------|:--:|------|
| TC-FE-033 | 잘못된 ID(NaN) 다크 에러 | PASS | [실동작] `/label/abc` 진입 → 스냅샷 `paragraph "잘못된 프레임 ID"` + `button "뒤로 가기"`. 정적 `LabelingPage.tsx:1076-1095`(카탈로그 1075-1094 → **+1 드리프트, 정정함**) |
| TC-FE-034 | 로딩 상태 스피너 | PASS | [정적] `LabelingPage.tsx:1097-1110` → `<Spinner label="라벨 로딩" />` + "라벨 로딩 중...". ⚠ 카탈로그 기대결과 `"라벨 로딩" data-testid` 는 **오류** — `Spinner.tsx:9-23` 은 `role=status`+`aria-live=polite`+`aria-label`+`sr-only` 만 부여하고 data-testid 는 없다(페이지 컨테이너만 `data-testid="labeling-page"`). 기대결과 **정정함** |
| TC-FE-035 | 포털 403 → graceful 차단화면 | PASS | [정적] `LabelingPage.tsx:1115-1142` `isPortalForbidden`(status===403 \|\| errorCode==='FORBIDDEN') → `data-testid="portal-forbidden-screen"` + "접근할 수 없는 영상입니다". 테스트 `features/portal/__tests__/LabelingPagePortalForbidden.test.tsx:55 포털_미승인_영상_403시_접근불가_안내_화면`(baseline 전건 통과). 근거 **+1 정정** |
| TC-FE-036 | 일반 에러 → "라벨 조회 실패" | PASS | [실동작] `/label/999999` → `"라벨 조회 실패"` + BE 문구 `"프레임을 찾을 수 없습니다."` + 뒤로 가기(HTTP 404 실측). 근거 `LabelingPage.tsx:1144-1164`(**+1 정정**). ⚠ 인접 이슈 → **H-ISSUE-21** |
| TC-FE-037 | siblings 비면 현재 프레임 단건 폴백 | PASS | [정적] `LabelingPage.tsx:236-263` `siblings.length===0` → 현재 1건 배열. 실동작에서는 BE 가 항상 siblings 10건을 주어 폴백 경로 미발생(스트립 10건 렌더 확인 = else 분기). 폴백 분기는 `LabelingPageBusyWiring.test.tsx:206`(`siblings: []` fixture)이 간접 커버 — **전용 단언 테스트는 없음**. 근거 **+1 정정** |
| TC-FE-038 | 저장 성공 토스트 "저장됨" | PASS | [실동작] `/label/468`에서 Ctrl+S → `PUT /api/v1/frames/468/labels` 1건 발생, 바디 `{"items":[…],"labelVersion":5}`, 화면 텍스트에 `저장됨` 관측. 서버 `labelVersion` 4→5 증가 확인. 근거 `:559-597`(**+1 정정**) |
| TC-FE-039 | 저장 중복 제출 차단 | PASS | [실동작] Ctrl+S **3연타** → 캡처된 PUT **1건**(XHR open 후킹). 근거 `:563` `if (saving) return;`(**+1 정정**). 테스트 `features/review/__tests__/LabelingPageSaveGuard.test.tsx:75` |
| TC-FE-040 | 잠금 영상 저장 차단 | PASS | [정적] `:565-571` `isLocked` → error 토스트 `'비식별 재처리 중인 영상은 저장할 수 없습니다.'` 후 `return` (updateLabels 미호출 = PUT 미발생). 테스트 `LabelingPageDeidentReport.test.tsx:108 lockSttsCd_LOCKED_FOR_REDEIDENT_시_배너_표시_+_저장_버튼_비활성`. 근거 **+1 정정** |
| TC-FE-041 | 저장 실패 에러 토스트(BE 문구 우선) | PASS | [정적] `:592-595` `extractBeMessage(e,'저장 실패')`. 409 는 `:586-591` 에서 별도 다이얼로그로 분기(카탈로그 "409 외" 전제와 일치). `lib/api/extractBeMessage.ts:8-26` 이 userMessage→message→response.data.message→fallback 순. 근거 **+1 정정** |
| TC-FE-042 | 포털 모드 저장 경로 분기 | PASS | [정적] `:552-558` `useSavePortalLabels` + `const updateLabels = portalMode ? savePortalLabels : updateInternalLabels`. 테스트 `features/portal/__tests__/PortalLabelingDataPath.test.tsx:91 포털_모드_라벨로드는_portal_API만_호출_내부API_미호출`. 근거 **+1 정정** |
| TC-FE-043 | 프레임 이동 dirty 가드 모달 | PASS | [실동작] 라벨 1건 삭제(dirty=1) 후 프레임1 썸네일 클릭 → 모달 `"저장 안 한 변경사항이 있습니다 / 1개 객체에 미저장 변경이 있습니다… / 취소 · 저장 안 함 · 저장 후 이동"`, URL 은 `/label/468` 유지. 근거 `:340-352`(**+1 정정**) |
| TC-FE-044 | 같은 프레임 이동 no-op | PASS | [실동작] 현재 선택 프레임(`aria-selected=true`) 옵션 클릭 → URL 불변, 가드 모달 미출현. 근거 `:346`(**+1 정정**) |
| TC-FE-045 | 저장 후 이동 — 실패 시 취소 | PASS | [정적] `:353-382` catch → error 토스트 + `setNavGuardTarget(null)`(이동 취소). 추가로 `saved === null`(폐기) 시에도 이동 안 함. 테스트 `LabelingPageFrameNavGuard.test.tsx:205 저장후이동_save실패시_이동취소_현재프레임유지`. 근거 **+1 정정** |
| TC-FE-046 | 저장 안 함 이동 — dirty 폐기 | PASS | [실동작] 모달에서 "저장 안 함" → `/label/469` 로 이동, **PUT 0건**(XHR 후킹), 객체수 0개. 근거 `:383-400`(**+1 정정**). ⚠ 현행 구현은 카탈로그 기대결과보다 **더 강하다** — 이동 가능 여부(`isEditBlocked`)를 clearDirty **앞에서** 판정해 "파기만 되고 못 떠나는" 경로를 차단(`:389-396`). 기대결과와 모순 아님 |
| TC-FE-047 | 프레임 전환 시 setLabels 전체 교체 | PASS | [실동작+정적] 468(1건)→469 이동 후 객체수 0개로 교체 확인. `:404-427` `frameChanged` 분기에서 `setLabels(nextLabels)`(내부에서 dirty/undo/redo/selection 초기화). 테스트 `LabelingPageFrameSwitch.test.tsx:92`. 근거 **+1 정정** |
| TC-FE-048 | 같은 프레임 refetch는 dirty 있으면 미덮음 | PASS | [정적] `:423-426` `if (useLabelStore.getState().dirtyLabels.size === 0) setLabels(...)` — `getState()` 로 최신 dirty 를 읽어 stale 판정 회피. 테스트 `LabelingPageAutolabelMerge.test.tsx:154 refetch가_와도_dirty편집이_유실되지_않는다`. 근거 **+1 정정** |
| TC-FE-049 | 보류 추적 drain 병합 | PASS | [정적] `:433-446` — `drainPendingTracks(srcSn)` → `mergeAutoLabels` → `pushToast(info, "보류된 AI 추적 ${added}건 적용됨")`. deps 가 `data?.srcSn` 하나라 같은 프레임 refetch 로 이중 병합 없음. 근거 **+1 정정** |
| TC-FE-050 | 언마운트 시 store reset | PASS | [정적] `:449-453` cleanup-only `reset()`(deps `[reset]`) — data 변경마다 reset 되던 회귀를 막는 구조. 근거 **+1 정정** |
| TC-FE-051 | AI 탐지 팝업 매핑 라벨만 선택 | PASS | [실동작] AI 탐지 모달 실측 — 매핑 6종(`person·car·bicycle·motorbike·bus·truck`) `disabled=false`, 미매핑 4종(`fire·smoke·water·c2a-race-lbl`) `disabled=true` + `미매핑` 뱃지, 실행 버튼 라벨 `전체 (6종)`, `일반`/`트랙` 활성(= `canRun = mappedCount>0`). 근거 `AiToolModal.tsx:147,183,250-272` **드리프트 없음**. ⚠ 인접 이슈 → **H-ISSUE-22** |
| TC-FE-052 | AI 탐지 mock 응답 자동적용 차단 | PASS | [실동작] `/label/470`에서 AI 탐지 "일반" 실행 → BE 가 `ApiResponse.message = "AI 모델 미로드 — 결과 신뢰 불가"` 반환 → FE 가 **경고 토스트만** 띄우고 객체수 `0개 → 0개`(병합 0건). `:695-698` 조기 return 실효 확인. self-fill 없음(값은 전부 BE 응답 경유). 근거 **+1 정정** |
| TC-FE-053 | AI 탐지 결과 작업본 병합(중복 스킵) | PASS | [정적] `:700-707` `mergeAutoLabels(detected)` + `` `${kind} ${added}건 적용됨` ``, PUT/invalidate 없음. ⚠ **실동작 성공경로는 이 환경에서 미재현** — ai-server 에 YOLO 가중치가 미로드라 항상 mock 분기(TC-FE-052)로 빠진다. 테스트 `LabelingPageAutolabelMerge.test.tsx:120 오토라벨_직후_PUT저장이_호출되지_않는다_그리고_기존라벨_유지한채_병합` + `LabelingPageAutolabelToast.test.tsx:120`. 근거 **+1 정정** |
| TC-FE-054 | 폴리곤 shape → "AI 분할" 라벨 | PASS | [정적] `:706` `BUSY_KIND_NAME[ctx.shape === 'POLYGON' ? 'AI_SEGMENT' : 'AI_DETECT']`, `busyPolicy.ts:20-26` `AI_SEGMENT: 'AI 분할'` / `AI_DETECT: 'AI 탐지'` — 문구 단일 소스. 근거 **+1 정정** |
| TC-FE-055 | 트랙 모드 — 객체 선택 유도 | PASS | [실동작] 모달 "트랙" 클릭 → 모달 닫힘, 툴바 `AI 추적` 버튼 `aria-pressed="true"`, info 토스트 `"추적할 객체를 선택한 뒤 속성 패널에서 자동추적을 실행하세요."`. 근거 `:818-836`(**+1 정정**) |
| TC-FE-056 | 추적 결과 현재/미래 프레임 분리 | PASS | [정적] `:764-807` — `forCurrent`(=== data.srcSn) 즉시 `mergeAutoLabels`, `forFuture` 는 srcSn 별 그룹화 후 `stashPendingTracks`. 미래분 frameNo 는 `frames.find(...)`로 해석. 근거 **+1 정정** |
| TC-FE-057 | 부분 추적 실패 경고 | PASS | [정적] `:788-796` warning 토스트 `` `${applied}/${total} 프레임만 추적됨 (일부 실패)` ``, `total = nextSrcSns.length \|\| tracked.length`. 카탈로그 기대문구 "N/total만 추적됨" 은 실제 문자열과 달라 **정정함**. 근거 **+1 정정** |
| TC-FE-058 | nextSrcSns 계산(현재 이후) | PASS | [정적] `:753-757` `frames.slice(frameIdx + 1).map(f => f.srcSn)` (useMemo deps `[frames, frameIdx]`). 근거 **+1 정정** |
| TC-FE-059 | SAM2 추적 청크 50개 상한 정합 | PASS | [정적] `features/label/api.ts:632` `SAM2_TRACK_CHUNK_SIZE = 50`, `:715-718` slice 분할. 테스트 `features/label/__tests__/sam2-track.test.tsx:96 상수는_BE_Size_상한과_정합한다` / `:100 120개_후속프레임은_50_50_20_3청크` / `:179 정확히_50개는_단일_청크`. 실동작 미재현(대상 영상 프레임 10건뿐이라 청크 1개). 드리프트 없음 |
| TC-FE-060 | SAM2 추적 stale 가드(프레임 전환) | PASS | [정적] `useSam2Track.ts:61`(runExclusiveOrNotify), `:72-73`(isAlive 통과 시에만 onTracked), `:80-81`(부분실패도 isAlive 검사) + `useBusyTask.ts:113-118`(프레임 전환 시 이전 busy cancel), `:127-139`(시작 시점 stale 차단 + `isAlive` = 토큰축 ∧ 렌더축). 카탈로그의 "onSuccess 비교 아님" 정정 표기가 **현행과 일치**. 테스트 `useSam2Track.invalidate.test.tsx:100 추적_응답이_프레임전환후_도착하면_폐기된다`. 드리프트 없음 |
| TC-FE-061 | SAM2 부분 실패 성공분만 병합 | PASS | [정적] `useSam2Track.ts:78-84` catch → `Sam2TrackChunkError.partial.length>0` 이면 `onTracked(err.partial, true)` 후 rethrow. 테스트 `useSam2Track.invalidate.test.tsx:65 추적_부분실패시_성공분만_병합하고_경고한다` / `:84 완전실패(partial_0)면_onTracked_미호출`. 드리프트 없음 |
| TC-FE-062 | BBOX 추적 시드 외접박스 4점 확장 | PASS | [정적] `api.ts:640-659` `toSeedPolygon` — 2점만 4모서리 폐곡선으로 확장, 3점 이상은 그대로. 비유한 좌표는 WARN + 0 폴백(침묵 실패 가시화). 테스트 `sam2-track.test.tsx:478 BBOX_추적_50프레임초과시_2번째청크_prevPolygon이_4점폐곡선으로_확장된다`. 드리프트 없음 |
| TC-FE-063 | 트랙 rename/삭제/분할 포털 차단 | PASS | [정적] `:859`(rename) / `:881`(delete) / `:906`(split) 각각 `if (portalMode) return;` — 모두 `isEditBlockedNow` 가드 **직후, rawSn 조회 앞**에 위치해 우회 경로 없음. 테스트 `features/portal/__tests__/LabelingPageTrackRenameGuard.test.tsx:110 포털모드_트랙_rename_버튼_미노출_및_mergeTracks_미호출`(delete/split 은 전용 테스트 없음 — 동일 패턴 정적 확인). 근거 **+1 정정** |

### 집계

| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|---|---|---|---|---|---|
| **31** | **31** | 0 | 0 | 0 | 0 | 0 |

- 실동작(`[실동작]`) 판정 **12건**, 정적+자동테스트(`[정적]`) 판정 19건.
- 케이스 자체는 전건 PASS. 다만 **인접 결함 2건**(H-ISSUE-21·22)과 **카탈로그 정합성 결함 1건**(H-ISSUE-23, 정정 완료)을 아래에 기록한다.

---

## 2. 이슈

### [H-ISSUE-21] TC-FE-036 인접 — 라벨링 에러 화면이 `resolveApiMessage` 정책을 우회해 `error.message` 를 그대로 렌더한다 (2차 H-ISSUE-06 **미해소 이월**)
- **심각도**: LOW
- **기대 동작(기대효과)**: `lib/api/resolveApiMessage.ts:4,16-19` 가 "400/409/412 만 서버 문구 노출, 401/403/5xx·비-ApiError 는 fallback" 을 **단일 지점에서** 강제한다(CWE-209 정보 노출 방어). 에러 표시 경로가 이 유틸을 통과해야 정책이 실효를 갖는다.
- **현재 동작(이슈 내용)**: 2차 H-ISSUE-06 이 지목한 `LabelingPage.tsx:1144` 는 라인만 밀렸을 뿐 **그대로 남아 있다**.
  ```tsx
  // frontend/src/pages/label/LabelingPage.tsx:1151-1153
  <div className="text-center">
    <p className="text-lg font-semibold mb-2">라벨 조회 실패</p>
    <p className="text-sm text-gray-400 mb-4">{error.message}</p>
  ```
  같은 파일의 저장 실패 토스트(`:594`)도 `extractBeMessage()` 를 쓰는데, 이 유틸은 `lib/api/extractBeMessage.ts:12-23` 에서 **상태코드를 보지 않고** `userMessage → message → response.data.message` 순으로 무조건 서버 문구를 채택한다. 즉 라벨링 화면에는 정책 우회 경로가 **표시(1153)와 토스트(594) 두 축**에 있다.
- **재현/확인 경로**: `/label/999999` 진입 → 화면 `"라벨 조회 실패 / 프레임을 찾을 수 없습니다."`(404 문구가 그대로 노출, 실측). 네트워크 차단 상태로 진입하면 axios 원문(`Network Error` / `timeout of 30000ms exceeded`)이 그대로 노출된다.
- **영향**: 보안(정보 노출, **잠재**) — 현재 BE 는 500 에 `ErrorCode.INTERNAL_ERROR.defaultMessage()` 고정값만 내려주므로 실제 유출은 없다. 다만 어느 예외 핸들러가 상세 메시지를 담기 시작하면 이 경로들만 조용히 새고, 정책이 한 곳에 모이지 않아 감사가 어렵다. CWE-209.
- **수정 방향(제안)**: `LabelingPage.tsx:1153` 을 `resolveApiMessage(error, '라벨을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.')` 로 교체. `extractBeMessage` 는 상태코드 필터를 내장하거나(400/409/412 만 서버 문구), `resolveApiMessage` 위임형으로 재작성. 2차 제안대로 ESLint `no-restricted-syntax`(JSX 자식/속성으로 `*.message` 직접 사용 금지, 폼 `errors.*.message` 예외) 도입 검토. ⚠ 2차에서 지목된 나머지 3곳(`HistoryPanel.tsx:194,254`)도 함께 확인 필요 — `HistoryPage.tsx` 는 2026-08-03 결정2(`b27b3108`)로 삭제되어 해당 없음.

### [H-ISSUE-22] TC-FE-051 / TC-FE-053 인접 — WORKER 라벨링 화면이 REVIEWER 전용 `/v1/manage/configs` 를 호출해 **AI 정밀도 시스템 설정이 작업자에게 영영 반영되지 않는다** (+ 매 진입마다 403 2회)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `LabelingPage.tsx:722-725` 는 시스템 설정(`YOLO_CONF_THRESHOLD`, `POLYGON_SIMPLIFY_TOLERANCE`)을 AI 탐지 모달 슬라이더 기본값으로 **프리필**하기 위해 만들어졌다("Phase 2 [FE] — AI 정밀도 프리필. 시스템 설정값을 슬라이더 기본값으로 사용"). 라벨링의 주 사용자는 **WORKER** 이므로, REVIEWER 가 설정한 값이 WORKER 화면에도 반영되어야 이 기능이 성립한다.
- **현재 동작(이슈 내용)**: 프리필 소스가 **REVIEWER 전용 관리 API** 하나뿐이라 WORKER 는 항상 403 → `sysConfigs === undefined` → 코드 상수로 폴백한다.
  ```ts
  // frontend/src/pages/label/LabelingPage.tsx:722-725
  const { data: sysConfigs } = useConfigs();
  const defaultConfThreshold =
    sysConfigs?.YOLO_CONF_THRESHOLD != null ? sysConfigs.YOLO_CONF_THRESHOLD / 100 : undefined;
  const defaultSimplifyTolerance = sysConfigs?.POLYGON_SIMPLIFY_TOLERANCE;
  // → undefined → PrecisionSliders.tsx:14,20  SENSITIVITY_DEFAULT=0.25 / TOLERANCE_DEFAULT=1 폴백
  ```
  실측:
  ```
  GET /api/v1/manage/configs  (REVIEWER) → 200  YOLO_CONF_THRESHOLD=25, POLYGON_SIMPLIFY_TOLERANCE=1.0
  GET /api/v1/manage/configs  (WORKER)   → 403  {"errorCode":"FORBIDDEN","message":"권한이 없습니다."}
  ```
  WORKER 실브라우저에서 AI 탐지 모달을 열면 "인식 민감도 **0.25**" 가 표시되는데, 이는 DB 값(25→0.25)이 아니라 **코드 상수**다. 현재는 두 값이 우연히 같아 증상이 보이지 않지만, REVIEWER 가 설정을 60 으로 바꾸면 **REVIEWER 화면만 0.60, WORKER 화면은 계속 0.25** 가 된다.
  부수 효과로 `lib/queryClient.ts:7` 의 `retry: 1` 때문에 **라벨링 화면 진입 1회당 403 이 2회** 발생하고 콘솔 에러가 누적된다(실측: 한 세션에 8건 관측).
- **재현/확인 경로**:
  1. `POST /api/v1/dev/tokens {"userNo":2001,"role":"WORKER","channel":"INTERNAL"}` 로 WORKER 토큰 발급 → `sessionStorage.klid_jwt` 주입 → `/label/468` 진입.
  2. DevTools Network → `GET /api/v1/manage/configs` **403 × 2**.
  3. 툴바 "AI 탐지" 클릭 → 인식 민감도 슬라이더가 코드 상수 `0.25` 로 표시.
  4. 대조: REVIEWER 토큰(userNo=1001)으로 `curl -H "Authorization: Bearer $RT" localhost:18081/api/v1/manage/configs` → `YOLO_CONF_THRESHOLD=25`.
- **영향**: 기능(설정 무효화) — 운영자가 관리 화면에서 조정한 AI 정밀도 기본값이 **실제 라벨링 작업자에게 도달하지 않는다**. AI 탐지 결과 품질에 직결되며, "설정을 바꿨는데 왜 안 바뀌냐"는 형태로만 드러나 원인 추적이 어렵다. 보안 영향은 없다(BE fail-closed 는 정상 동작). 부차적으로 콘솔 403 노이즈가 실제 오류를 가린다.
- **수정 방향(제안)**: 두 갈래 중 택1 — ①BE 에 **읽기 전용 프리셋 조회 엔드포인트**(예: `GET /v1/labels/ai-presets`, WORKER+REVIEWER 허용, AI 정밀도 키만 화이트리스트 노출)를 신설하고 라벨링 화면이 그것을 보게 한다(권장 — 설정 단일 진실원 유지). ②`/v1/manage/configs` 의 **GET 만** WORKER 에 허용한다(다만 관리 설정 전체가 노출되므로 키 화이트리스트 필요). 어느 쪽이든 `useConfigs()` 에 `retry: false` 와 403 시 조용한 폴백(콘솔 에러 억제)을 함께 적용한다. ⚠ **구현은 하지 않는다.**

### [H-ISSUE-23] (카탈로그 정합성) H-3 앞 1/3 근거 `file:line` 26건 +1 드리프트 + 기대결과 오류 2건 — **이번 회차에서 정정 완료**
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 존재 이유가 "근거 정확도"이므로 `file:line` 이 실제 코드를 가리켜야 한다(`CLAUDE.md` 문서 동기화 규칙 2026-08-03 확정: "라인 드리프트만 바뀐 것도 고친다").
- **현재 동작(이슈 내용)**: 4회차(`9f99db50`)가 `e58aa086` 기준으로 H-3 근거를 전수 재확인했으나, 그 **직후 커밋 `0d290c4e`(영상 단위 개인정보 메타 화면)** 가 `LabelingPage.tsx` 에 import 1줄(`:49 VideoPrivacyMetaPanel`)을 추가해 **파일 49행 이후 전체가 +1 밀렸다**.
  ```
  frontend/src/pages/label/LabelingPage.tsx | 5 ++++-  (e58aa086 → HEAD e065da42)
  +import { VideoPrivacyMetaPanel } from '@/features/label/components/VideoPrivacyMetaPanel';   ← :49
  ```
  결과적으로 내 담당 범위 31건 중 **`LabelingPage.tsx` 를 인용한 26건 전부**가 1줄씩 어긋나 있었다(TC-FE-033~050·052~058·063). 반면 `AiToolModal.tsx`·`useSam2Track.ts`·`useBusyTask.ts`·`api.ts` 를 인용한 5건(TC-FE-051·059~062)은 **전건 정확**했다.
  추가로 기대결과 서술 오류 2건:
  - TC-FE-034 `"라벨 로딩" data-testid` → 실제는 `Spinner.tsx:9-23` 의 `role=status`/`aria-live`/`aria-label`/`sr-only` 이며 **data-testid 는 존재하지 않는다**.
  - TC-FE-057 `"N/total만 추적됨"` → 실제 문자열은 `` `${applied}/${total} 프레임만 추적됨 (일부 실패)` ``.
- **재현/확인 경로**: `git diff e58aa086 HEAD -- frontend/src/pages/label/LabelingPage.tsx` / `grep -n "잘못된 프레임 ID" frontend/src/pages/label/LabelingPage.tsx` → 1084(카탈로그는 1075-1094 로 표기했었음).
- **영향**: 검증 효율 — 다음 회차 검증자가 어긋난 라인을 읽고 "구현 없음"으로 오판할 수 있다. 기능/보안 영향 없음.
- **수정 방향(제안)**: **이미 정정함** — `docs/test-cases/H-frontend-e2e.md` 74~104행 범위에서 `pages/label/LabelingPage.tsx:` 인용 26건을 +1 시프트하고 TC-FE-034·057 기대결과를 실제 코드 문구로 교체했다(프로덕션 코드는 무수정). ⚠ **파일 상단 `## 변경 이력` 표 회차 행 추가는 이 파트에서 하지 않았다** — 6~16행은 병렬 파트와 공유되는 구역이라 충돌 방지를 위해 손대지 않았다. **병합 담당이 H 클러스터 전체 정정 건수를 합산해 5회차 행을 1번만 추가**할 것.

---

## 3. 이전 회차(2026-08-02 2차) 이슈 해소 여부 — 이 파트 범위분

| 2차 이슈 | 대상 | 이번 회차 판정 | 근거 |
|---|---|---|---|
| **H-ISSUE-41** (HIGH) 라벨 저장 PUT 이 `labelVersion` 을 안 싣는다 | TC-FE-198(타 파트) — 단 내 실동작 경로에서 직접 관측 | **✅ 해소** | Ctrl+S 실측 PUT 바디 `{"items":[…],"labelVersion":5}` — `getLabels()` 가 `labelVersion` 을 매핑하고 `LabelingPage.tsx:543-549` 가 `{ labelVersion: data?.labelVersion }` 로 전달. 서버 버전 4→5 증가도 확인 |
| **H-ISSUE-42** (MEDIUM) 연속 저장 409 미발생이 "버전 미전송"으로 성립 | TC-FE-199(타 파트) | **🔶 부분 해소 — 재검증 필요** | 토큰 자체는 실려 나간다(위). 다만 이번 관측은 단발 저장 1회(4→5)뿐이라 **연속 2회 저장 시 `v → v+1` 로 갱신 전송되는지**는 미확인. 해당 케이스 담당 파트에서 확인 요망 |
| **H-ISSUE-06** (LOW) `error.message` 직접 렌더 4곳 | 그중 `LabelingPage.tsx:1144` 가 내 TC-FE-036 경로 | **❌ 미해소 이월** | 현재 `:1153` 에 그대로 존재 → **H-ISSUE-21** 로 재기록. 단 `HistoryPage.tsx:99` 건은 결정2(`b27b3108`)로 페이지가 삭제되어 자동 소멸 |
| **H-ISSUE-44** (카탈로그) H-3 절 근거 전건 드리프트 | 내 범위 전체 | **🔄 재발 → 재정정** | 4회차 정정 직후 `0d290c4e` 로 +1 재드리프트. 이번에 재정정 → **H-ISSUE-23** |
| **H-ISSUE-43** (HIGH/확인필요) 폴리곤 클릭 점 추가 안 됨 | 캔버스 드로잉 — **내 범위 밖**(H-3 뒤 2/3) | **미검증** | 도형 도구 → 라벨 선택 모달 흐름(2026-08-03 결정3)이 신설돼 재현 절차 자체가 바뀌었다. part3 담당 소관 |
| **H-ISSUE-103** (HIGH) 포털 SAM2 노출 | TC-FE-275 — 내 범위 밖 | **미검증** | 4회차 문서가 `dcdbb827` 로 `PORTAL_HIDDEN_TOOLS` 채워졌다고 기록. 해당 파트에서 확인 |

---

## 4. 반증 시도 기록 (확증편향 방지)

기대결과의 단언을 뒤집으려 시도한 항목과 결과:

| 반증 가설 | 시도 | 결과 |
|---|---|---|
| "캔버스가 사실은 원본을 서빙한다" | WORKER 토큰으로 `?raw=true` 강제 요청 후 DEID 응답과 md5 비교 | **반증 실패** — 바이트 동일(raw 무시). REVIEWER 만 다른 바이트 수신 |
| "FE 어딘가에서 `raw=true` 를 몰래 붙인다" | `grep -rn "raw" src/features/label/hooks/useImageBlob.ts` + 호출부 전수 | **반증 실패** — `LabelingPage.tsx:160` 은 `raw` 미전달, `useImageBlob.ts:77-81` 이 params 자체를 생략 |
| "중복 저장 차단이 실제로는 안 걸린다(디바운스 착시)" | XHR `open` 후킹 후 Ctrl+S 3연타 | **반증 실패** — PUT 정확히 1건 |
| "저장 안 함 이동이 몰래 저장한다" | 폐기 이동 중 PUT 카운트 | **반증 실패** — PUT 0건 |
| "같은 프레임 클릭도 가드 모달을 띄운다(no-op 미구현)" | dirty 없는 상태에서 현재 프레임 옵션 클릭 | **반증 실패** — URL 불변·모달 없음 |
| "AI mock 응답이 조용히 자동 병합된다(self-fill)" | 모델 미로드 상태에서 AI 탐지 실행 후 객체수 대조 | **반증 실패** — 0→0, 경고 토스트만. 값이 BE 응답 없이 채워지는 경로 없음 |
| "미매핑 라벨도 사실은 선택 가능하다" | 모달 체크박스 `disabled` 속성 전수 덤프 | **반증 실패** — 미매핑 4종 전부 `disabled=true` |
| "트랙 모드가 즉시 추적을 실행해버린다" | 모달 "트랙" 클릭 후 네트워크·툴 상태 관찰 | **반증 실패** — 도구만 TRACK 으로 전환 + 안내 토스트, 추적 요청 없음 |
| "포털 트랙 가드가 rename 에만 있다" | delete/split 핸들러 선두 라인 직접 확인 | **반증 실패** — `:881`·`:906` 에도 동일 `if (portalMode) return;` |
| "근거 라인이 맞다(4회차가 재확인했으니)" | `git diff e58aa086 HEAD` + 실제 grep 위치 대조 | **반증 성공** → H-ISSUE-23 |
| "AI 정밀도 프리필이 정상 동작한다" | WORKER 로 `/manage/configs` 호출 + 모달 슬라이더 값 대조 | **반증 성공** → H-ISSUE-22 |
