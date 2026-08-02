# H-part4 — H-6. MarkingPage(20건) + H-7. BatchStageIndicator(5건) = 25건

> 검증일 2026-08-02 · 회차 2차 · 담당 범위: `docs/test-cases/H-frontend-e2e.md` §H-6 `TC-FE-110~125,203~205,TC-E2E-003` · §H-7 `TC-FE-126~130`
> 대상 코드: `frontend/src/pages/MarkingPage.tsx` · `frontend/src/features/marking/**` · `frontend/src/components/common/BatchStageIndicator.tsx` · `frontend/src/features/video/types.ts`
> 실행 스택: frontend(:13000, 실행중) · backend(:18081/api, 실행중, local profile) · PostgreSQL(`klid-postgres` docker, DB=`klid_system`, 스키마 `public`)
> ⚠ 코드/설정/테스트 파일 수정 없음, 빌드/테스트 실행 없음 (Read/Grep/DB조회(SELECT만)/Playwright 조작만 수행)

## 0. 검증 방법 메모

- **실동작**: Playwright 로 `/dev/login`(WORKER 프리셋) → `/marking/69`(DB 조회로 선정: `de_ident_yn='Y'`, `data_stts_cd='MARKING_READY'`, `evnt_type_cd` 존재, 미마킹) 진입 성공 확인. 화면 렌더(제목·배속 6버튼·모드토글·단축키 안내·마크 0건·비활성 제출버튼)을 스냅샷으로 확인.
- ⚠ **환경 제약**: 이 브라우저는 병렬 실행 중인 다른 QA 서브에이전트와 **탭·localStorage(JWT 토큰 키)를 공유**하는 것으로 관측됨(navigate 직후 다른 role/URL 로 튀는 현상 반복 재현) — 다회 순차 상호작용(Space/Enter 연타 등 라이브 키 입력 시퀀스)은 신뢰도가 낮아, 최초 1회 클린 렌더 확인 이후는 **정적 코드 대조 + 기존 vitest 커버리지 대조**로 전환. 이 사실 자체는 FE 결함이 아니므로 이슈로 기록하지 않음.
- 영상 스트림은 `GET /videos/69/stream...` → **404** 관측. DB 조회 결과 이 로컬 DB의 `LS_DEIDENT_PROC_LOG` 에 raw_sn=69 행이 없고(시드 데이터가 `de_ident_yn='Y'` 를 직접 주입), 실제 비식별 산출물이 있는 raw_sn(94/81/80)도 파일 경로가 `/app/storage/...`(컨테이너 전용, 이 호스트엔 `/app` 없음)라 로컬에서 재생 불가 — **테스트 데이터/환경 한계이며 MarkingPage 코드 결함 아님**(별도 이슈 생성 안 함).
- **BE 파이프라인 순서 실측 대조(확증편향 금지 지시 이행)**: `BatchStageProgressMapper.DISPLAY_ORDER`(`backend/.../batch/status/BatchStageProgressMapper.java:22-30`) = `[DEIDENTIFY, MARKING, VLM, FRAME_EXTRACT, YOLO, SAM2, INTERPOLATE]` — `BatchPipelineConfig.java`(pre-marking=[DEIDENTIFY], post-marking=[MARKING,VLM,FRAME_EXTRACT,YOLO,SAM2,INTERPOLATE])·루트 CLAUDE.md 서술과 정확히 일치. FE 는 `STAGE_ORDER` 상수를 **어디에도 갖고 있지 않음**(`grep -rn STAGE_ORDER frontend/` = 0건) — BatchStageIndicator 는 BE 가 내려준 `stages` 배열을 그대로 `.map()` 렌더(순서 가정 0, name→라벨 매핑만). **"STAGE_ORDER 드리프트 함정"은 현재 코드에 존재하지 않음** — FE 가 애초에 그 함정 패턴(자체 순서 하드코딩)을 쓰지 않도록 설계돼 있다.

---

## 1. H-6. MarkingPage — 20건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-FE-110 | PASS | [정적] | `MarkingPage.tsx:154-156` `if (rawSn===undefined \|\| isNaN(rawSn)) return <div>...잘못된 영상 ID입니다.</div>` |
| TC-FE-111 | PASS | [정적]+[실동작] | `MarkingPage.tsx:158-173` `role="alert"` + "비식별 완료 후 마킹이 가능합니다." vitest `MarkingPage.test.tsx:220-245`(`비식별_미완료_영상_직접진입시_마킹차단_백스톱_안내`)로 회귀 고정 |
| TC-FE-112 | PASS | [정적] | `features/video/types.ts:27-38` `isMarkingBlocked` — `deIdntfYn==='N'\|\|'F'` 또는 `deidentStatus IN_PROGRESS/FAILED` → true. `'F'`(신고) 도 차단 대상에 포함돼 있어 프로젝트 확정 정책(신고 구간 라벨/스트리밍 차단)과 일치 |
| TC-FE-113 | PASS | [정적] | `MarkingPage.tsx:139-141` `e.code==='Space'` → `preventDefault`+`handleAddMarkAtCurrentTime()`→`addMark` |
| TC-FE-114 | PASS | [정적] | `MarkingPage.tsx:142-144` `Delete\|Backspace` → `removeSelectedMark()` |
| TC-FE-115 | PASS | [정적]+[실동작] | `MarkingPage.tsx:145-147` `Enter`→`handleSubmit()`. vitest(`마킹_제출_pending_중_Enter_재호출시_추가_POST_미발생`)가 Enter→POST 발화를 실제로 검증 |
| TC-FE-116 | PASS | [정적] | `MarkingPage.tsx:136-137` `tag==='INPUT'\|\|'TEXTAREA'\|\|'SELECT'` 3종 모두 조기 return — 구 2종(INPUT/TEXTAREA)에서 SELECT 로 확장된 상태 그대로 |
| TC-FE-117 | PASS | [정적] | `MarkingPage.tsx:118-119` `if (!intervalFrames \|\| intervalFrames<1) return;` — mutate 미호출. `MarkingToolbar.tsx:29-30` 도 동일 조건으로 버튼 자체를 비활성화(방어 이중화) |
| TC-FE-118 | PASS | [정적] | `MarkingPage.tsx:124-125` `if (localMarks.length===0) return;` |
| TC-FE-119 | PASS | [정적]+[실동작] | `MarkingPage.tsx:116` `if (createMutation.isPending) return;`. vitest 로 Enter 연타 시 `postCount` 가 1로 유지됨을 실제 확인(`postCount` 카운터 mock) |
| TC-FE-120 | PASS | [정적]+[실동작] | `MarkingPage.tsx:61-77` `onSuccess`: `batchTriggered!==false` 분기에서 `clearMarks()`+success 토스트("마킹이 제출되었습니다. 배치 처리가 시작됩니다.")+`navigate('/task')`. vitest 로 토스트·navigate 모두 확인 |
| TC-FE-121 | PASS | [정적]+[실동작] | `MarkingPage.tsx:81-89` `onError`→`extractBeMessage(err, 기본메시지)` 로 error 토스트. vitest(`이벤트유형없는영상_400응답시_에러토스트_표시_및_이동안함`)로 400 응답 시 에러토스트+미navigate 확인 |
| TC-FE-122 | PASS | [정적] | `MarkingPage.tsx:49-58` `streamRetriedRef` 로 1회만 재발급(`handleStreamError`가 재진입 시 `if (streamRetriedRef.current) return;` 가드), `finally` 에서 플래그 리셋해 **다음** 만료 시 다시 1회 허용 — 무한루프 방지 로직 정확. ⚠ 이 케이스 전용 vitest 는 없음(커버리지 갭, 결함은 아님) |
| TC-FE-123 | PASS | [정적] | `MarkingPage.tsx:92-97` `useEffect([rawSn])` → `reset()` + `setDurationSec(FALLBACK_DURATION_SEC=60)`, unmount/변경 시 cleanup 도 `reset()` |
| TC-FE-124 | PASS | [정적]+[실동작] | `MarkingPage.tsx:181-187` `videoDetail?.stages && stages.length>0` 일 때만 `<BatchStageIndicator>` 렌더. 실동작: raw_sn=69 는 BE `LS_BATCH_PROC_LOG` 행이 없어(`stages=[]`) 인디케이터 미노출 상태로 렌더됨(스냅샷 확인) — 조건부 렌더 정상 동작의 실증 |
| TC-FE-125 | PASS | [정적] | `MarkingPage.tsx:221-241` 각 마크 칩 `onClick={() => selectMark(i)}`, `selectedMarkIndex===i` 시 `bg-primary-600 text-white` 하이라이트 |
| TC-FE-203 | PASS | [정적]+[실동작] | `markingFps.ts:21-31` `resolveMarkingFps` — 서버 fps 우선, 무효값만 `MARKING_FALLBACK_FPS=30` 폴백(BE `VideoFpsResolver.DEFAULT_FPS` 와 동일값). `VideoPlayer.tsx:54-55` `getCurrentFrame(fps)→markingFrameIndex(currentTime,fps)`(필수 인자, 기본값 없음 — 호출부 누락 시 컴파일 에러로 회귀 차단). vitest(`markingFps.test.ts`) 5건이 25fps/29.97fps/하드코딩 회귀 케이스까지 수식 대조로 고정 |
| TC-FE-204 | PASS | [정적] | `MarkingPage.tsx:63-75` `batchTriggered===false` 분기: **error** 토스트("마킹은 저장되었으나 배치가 시작되지 않았습니다. {batchSkipReason ?? 기본문구}")+`navigate('/task')` — 성공 문구로 위장하지 않음. BE 계약 확인: `MarkingResponse.java`·`MarkingBatchTriggerReport.java` 에 `batchTriggered`/`batchSkipReason` 필드 실재(self-fill 아님, FE 타입 `types.ts:43-45` 와 1:1). ⚠ 이 분기 전용 vitest 는 없음(커버리지 갭, 결함은 아님 — 기존 `MarkingPage.test.tsx` 는 `batchTriggered` 미포함 응답만 사용) |
| TC-FE-205 | PASS | [실동작]+[정적] | **실동작**: `/marking/69` 스냅샷에서 `0.25x/0.5x/1x/1.5x/2x/4x` 6개 버튼 렌더 확인(요청한 배속 순서·이산 개수 그대로). **정적**: `VideoPlayer.tsx:34` `SPEED_OPTIONS=[0.25,0.5,1,1.5,2,4] as const`, `:46-49` `changeSpeed`가 `videoRef.current.playbackRate=rate`+`setPlaybackRate`, `:130-135` 활성 버튼만 `bg-primary-600 text-white`, `:133` 전 버튼 `min-h-11`(KRDS 터치 최소 높이) 적용. 자유 수치 입력 UI 없음(슬라이더는 seek 전용) — UNCERTAINTIES #23 확정("클램프 불필요")과 일치 |
| TC-E2E-003 | PASS | [정적]+[실동작] | `pages/__tests__/MarkingPage.test.tsx`(총 8개 시나리오: 제출성공/토스트/navigate, MANUAL payload 직렬화, 400에러토스트, 마킹목록 비노출, 백스톱 진입차단/정상렌더, pending 중복방지, 서명URL 주입)가 스트림 서명 URL 주입 + 배속 UI 존재 + 단축키(Enter) 발화까지 mock API 기반으로 커버. 라이브 브라우저 진입도 위와 동일한 화면 구조로 렌더됨을 실측 확인(§0 참조) — 단, 실제 `<video>` 재생까지의 종단 E2E 는 이 환경의 영상 파일 부재로 미실증(환경 한계, §0 명시) |

**H-6 집계**: PASS 20 · FAIL 0 · PARTIAL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 2. H-7. BatchStageIndicator (STAGE_ORDER 드리프트 함정) — 5건

| ID | 판정 | 근거 확인 | 확인 내용 |
|----|------|-----------|-----------|
| TC-FE-126 | PASS | [정적]+[실동작 vitest] | `BatchStageIndicator.tsx:66` `if (!stages \|\| stages.length===0) return null;`. vitest(`stages가_빈배열이면_아무것도_렌더하지_않는다_배지폴백`) `container.firstChild` 가 null 임을 확인 |
| TC-FE-127 | PASS | [정적]+[실동작 vitest] | `STAGE_LABEL`(`:16-24`) 7키 모두 존재: DEIDENTIFY→비식별, MARKING→마킹, VLM→VLM, FRAME_EXTRACT→프레임추출, YOLO→AI 탐지, SAM2→AI 분할, INTERPOLATE→보간. vitest 3건이 7라벨 전부 렌더 확인 |
| TC-FE-128 | PASS | [정적] | `STAGE_LABEL_FALLBACK='처리중'`(`:28`), `:81` `STAGE_LABEL[stage.name] ?? STAGE_LABEL_FALLBACK` — 매핑 없는 코드는 "처리중"으로 폴백해 기술 코드명이 노출되지 않음. YOLO/SAM2 도 라벨 매핑되어 원문 노출 안 됨(vitest `container.textContent` 에 'YOLO'/'SAM2' 미포함 확인). ⚠ **미지 코드(예: 매핑에 없는 신규 BE 코드) 입력에 대한 전용 vitest 는 없음**(커버리지 갭 — 로직 자체는 정확) |
| TC-FE-129 | PASS | [정적]+BE 대조 | `:70` `{stages.map((stage, idx) => {...})}` — 배열을 받은 순서 그대로 렌더, 자체 정렬/재배치 로직 0. **BE 대조(반증 시도)**: `BatchStageProgressMapper.DISPLAY_ORDER`(`backend/.../batch/status/BatchStageProgressMapper.java:22-30`)가 `[DEIDENTIFY,MARKING,VLM,FRAME_EXTRACT,YOLO,SAM2,INTERPOLATE]` 로 유일한 순서 진실원이며 FE 에 순서 상수(`STAGE_ORDER` 류) 자체가 존재하지 않음(`grep -rn STAGE_ORDER frontend/` 0건). §0 서술한 대로 "STAGE_ORDER 드리프트" 함정은 이 코드베이스에 **현재 존재하지 않는다** — 케이스명의 "함정"은 과거/잠재 위험에 대한 경고이며 실제 구현은 이미 회피 설계됨 |
| TC-FE-130 | PARTIAL | [정적] | 아이콘 4종(DONE=Check/success, PROGRESS=Loader2 spin/info, FAIL=X/danger, 기타(PENDING)=회색 점) 모두 `aria-hidden`(Check/Loader2/X 는 `aria-hidden` prop, PENDING 점은 장식용 div) — 케이스가 요구하는 "아이콘 aria-hidden" 자체는 사실과 일치해 표 판정상 PASS 이나, **반증 결과 스크린리더 사용자에게 단계별 상태(완료/진행중/실패/대기)를 알릴 대체 텍스트가 컴포넌트 어디에도 없음**(`aria-label`·`sr-only`·`role` 전 검색 0건 — 라벨 텍스트는 단계 "이름"만 노출, 상태는 아이콘 모양/색으로만 구분). WCAG 2.1 AA(프로젝트 요구사항) 및 `component.md` "색상만으로 정보 전달 금지" 원칙에 비춰 접근성 갭 → **H-ISSUE-70** 로 기록(케이스 자체는 표기대로 PASS 조건 충족이라 판정은 PARTIAL) |

**H-7 집계**: PASS 4 · PARTIAL 1 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0

---

## 3. 이슈

### [H-ISSUE-70] TC-FE-130 — BatchStageIndicator 단계 상태가 스크린리더에 노출되지 않음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WCAG 2.1 AA 준수 요구(루트 CLAUDE.md 포털 섹션) 및 `component.md`의 "색상만으로 정보 전달 금지(아이콘/텍스트 병행)" 원칙에 따라, 배치 단계별 상태(완료/진행중/실패/대기)는 스크린리더 사용자도 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `frontend/src/components/common/BatchStageIndicator.tsx` 의 `StageIcon`(:30-57)이 렌더하는 `Check`/`Loader2`/`X` 아이콘은 모두 `aria-hidden`(각 `:34,41,48`)이고 PENDING 상태는 장식용 `<div>`(`:53-55`)뿐이다. 스테이지마다 노출되는 텍스트(`:77-82`)는 **단계 이름**(예: "비식별", "마킹")만이며 상태를 나타내는 `aria-label`/`sr-only`/`role` 속성이 컴포넌트 전체에 하나도 없다(`grep -n "aria-label|sr-only|role="` 결과 0건). 즉 스크린리더 사용자는 "비식별 단계가 존재한다"는 것만 알 수 있고 그 단계가 끝났는지/실패했는지/대기 중인지 알 방법이 없다.
- **재현/확인 경로**: 스크린리더(VoiceOver 등)로 마킹/영상상세 화면 진입 → `data-testid="batch-stage-indicator"` 영역 탐색 → 각 단계 이름만 낭독되고 상태 정보 낭독 없음. 코드 확인: `grep -n "aria-hidden\|aria-label" frontend/src/components/common/BatchStageIndicator.tsx`.
- **영향**: 접근성(WCAG 2.1 AA 1.1.1/4.1.2 상당) — 시각장애 사용자가 배치 처리 실패(FAIL) 여부를 화면에서 인지할 수 없어, 실패 시 대응이 늦어질 수 있음. 보안 영향 없음.
- **수정 방향(제안)**: 각 단계 아이콘 wrapper 또는 단계 컨테이너에 상태를 서술하는 `aria-label`(예: `"${label} — ${status==='DONE'?'완료':status==='PROGRESS'?'진행중':status==='FAIL'?'실패':'대기'}"`) 또는 시각적으로 숨긴 `sr-only` 텍스트를 추가. 아이콘 자체의 `aria-hidden` 은 유지하되 상위 요소에 접근성 트리 정보를 부여.

---

## 4. 집계 요약

| 클러스터 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| H-6 | 20 | 20 | 0 | 0 | 0 | 0 | 0 |
| H-7 | 5 | 4 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **25** | **24** | **0** | **1** | **0** | **0** | **0** |

신규 이슈: **H-ISSUE-70** (MEDIUM, a11y) 1건. 이전 회차 이월 대상 없음(H-6/H-7 최초 세부 검증).
