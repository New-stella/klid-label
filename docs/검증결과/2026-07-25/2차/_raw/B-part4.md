# B 클러스터 part4 (B-9·B-10) 2차 검증 결과

> 대상: `docs/test-cases/B-batch-deidentify.md` §B-9(30건) · §B-10(22건) = **52건**
> 검증 시각: 2026-07-31 03:17~03:30 KST · backend `localhost:18081`(HEAD `ca3c712b` 재빌드본) · ai-server `:19300` · mock-server `:9400` · postgres `public` 스키마
> 참조 데이터: `pipeline-drive.md` §3 (rawSn 126 완주 / 129 증강파생 / 130·131 해상도파생 / 132 검수대기 / 133 신고 OPEN / 127·128 비식별 실패)
> ⚠ 두 섹션에 `~~취소선~~` 폐기 행은 **0건** — 52건 전량이 검증 대상이다(집계 제외분 없음).
> ⚠ 검증 중 다른 에이전트가 rawSn 134~144 를 계속 생성했다. 아래 수치·로그는 조회 시점 스냅샷이다.
> ⚠ 컨테이너 재시작·재빌드·빌드/테스트 실행 0건. 소스/설정 수정 0건(본 파일 1개만 신규 작성).

## 집계

| 섹션 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-9 (오토라벨·좌표 정규화) | 30 | 29 | 0 | 1 | 0 | 0 | 0 |
| B-10 (비디오 스트리밍) | 22 | 22 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **52** | **51** | **0** | **1** | **0** | **0** | **0** |

근거 구성: **실동작 판정 24건** · 정적+기존테스트 판정 28건.
신규 이슈 **4건**(PARTIAL 1건 + 정보성 3건). 근거 드리프트 **15건**(전부 라인/경로/기대값 표기 문제, 동작 결함 아님).

---

## 1차 이슈 해소 대조

| 1차 이슈 | 1차 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **1차 B-ISSUE-61** — 해상도 파생영상 스트리밍 전면 403 | 파생 비식별본이 raw base 밑에 기록되는데 가드는 deid base 만 허용 | **해소** | [실동작] 파생 3건 전부 206: 증강 `129`·해상도 `130`(720P)·`131`(480P), `Range: bytes=0-99`. DB 실측 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 이 **`/app/storage/deidentified/videos/{augment,resolution}/126/{129,130,131}/*.mp4`** 로 **deid base 하위로 이동**(1차 수정방향 ①정공법 채택). 여기에 2-way allowlist(`VideoArtifactRootResolver.readableDeidVideoBases`)가 더해졌다. ⚠ 1차가 경고한 "raw base 통째 허용" 우회는 **채택되지 않았다** — 허용 co-locate 디렉터리는 `dirname(원본)/{rawSn}/deid` 로 좁혀져 있어 원본(`/app/storage/raw/seed/sample-cctv-1080p.mp4`)·export 원본 프레임(`.../126/v1/orgnl/`)은 범위 밖이다 |
| **1차 B-ISSUE-63** — `/stream` 영상 단위 배정 인가 부재(IDOR) | 역할만 검사, rawSn 소유/배정 검증 없음. 미배정 WORKER(2002) → 206 | **해소 (A-part1 결론과 일치 — 재현 불가 확인)** | ★아래 별도 절 참조 |
| **1차 B-ISSUE-42** — 오토라벨 일괄저장(루프 내 개별 save) | IDENTITY PK 라 `batch_size` 무효 | **부분 해소 유지** | `AutoLabelBatchPersister.saveAll`(프레임 단위 라벨 saveAll 1회 + AI메타 saveAll 1회)이 YOLO(`YoloAutolabelStep.java:328-329`)·SAM2(`Sam2SegmentStep.java:207-208`) 양쪽에 배선됨. 그러나 `LsDataLbl.java:55`·`LsDataLblAiInfo.java:30` 은 **여전히 `GenerationType.IDENTITY`** → JDBC 배치는 구조적으로 비활성. 헬퍼 javadoc 이 이 한계를 명시(`AutoLabelBatchPersister.java:22-29`). 즉 **왕복 감소만 달성, INSERT 묶음은 미달** = 1차 기록 그대로 |

### ★ B-ISSUE-63 교차 확인 결론 — **해소됐다. A-part1 이 재현하지 못한 것이 맞다.**

1차 이슈가 지목한 지점(`VideoController.java:220` 의 역할 전용 `@PreAuthorize`)에 **영상 단위 인가가 추가 배선**됐다.
- `VideoController.java:244` `@PreAuthorize("hasAnyRole('REVIEWER','WORKER') or hasAuthority('STREAM_SIGNED')")` — 역할 게이트는 그대로.
- `VideoController.java:253` **`labelAccessGuard.verifyRawAccess(rawSn, actor);`** ← 신규(주석에 `B-ISSUE-63` 명시). `/stream-url` 도 동일(`:210`), 형제 우회 경로 3곳(`GET /{rawSn}`:135 · `/{rawSn}/labels/auto`:156 · `/{rawSn}/frames/{frameNo}/image`:302)까지 함께 잠갔다(주석 `DEV_FIX H-1`).
- `VideoStreamService.java:140-141,156-157` javadoc 도 "컨트롤러가 `verifyRawAccess` 로 영상 단위 인가를 먼저 강제한다"로 갱신.

실측(전건 curl):

| 케이스 | 토큰 | 대상 | 실측 |
|---|---|---|:--:|
| 미배정 WORKER → 스트리밍 | worker2(2002, `ls_task_assignment` 에 126 없음) | `GET /v1/videos/126/stream` | **403** |
| 미배정 WORKER → 서명 URL | worker2 | `GET /v1/videos/126/stream-url` | **403** |
| 미배정 WORKER → 파생영상 | worker2 | `GET /v1/videos/129/stream` | **403** |
| 배정 WORKER → 스트리밍 | worker1(2001, 126 배정) | `GET /v1/videos/126/stream` (Range) | **206** |
| REVIEWER → 배정 무관 | reviewer1 | 126/129/130/131 | **200/206** |
| PORTAL_USER | portal1 | 126 | **403** |
| 무토큰 | — | 126 | **401** |

**추가 반증(열거 오라클 점검)**: 미배정 WORKER 가 *존재하는* 영상(126)과 *존재하지 않는* 영상(999999)을 요청했을 때 **둘 다 403 + 동일 본문**(`{"errorCode":"FORBIDDEN","message":"권한이 없습니다."}`)이다 → 응답 코드로 영상 존재 여부를 관측할 수 없다(CWE-209 회피). 신고 구간 영상(133)도 미배정 WORKER 에게는 403(게이트 이전에 인가가 끝남)이라 신고 상태도 새지 않는다.

회귀 가드: `video/VideoStreamAssignmentAuthorizationTest.java` 17건(미배정 403 / 배정 206 / REVIEWER 무관 / 서명경로 3건 포함).

---

## ★ 스트리밍 게이트 실측

전건 `curl` 실행(2026-07-31 03:17~03:29 KST). `Cache-Control` 은 응답 헤더 원문.

| rawSn | DB 상태 | 역할 | 요청 | 기대 | **실측 코드** | Cache-Control |
|---:|---|---|---|:--:|:--:|---|
| 126 | `Y` / procLog SUCCEEDED / co-locate 경로 | REVIEWER | `/stream` (Range 없음) | 200 | **200** | `no-store` |
| 126 | 〃 | REVIEWER | `/stream` `Range: bytes=0-` | 206 | **206** (`Content-Range: bytes 0-8388607/34654319`) | `no-store` |
| 126 | 〃 | REVIEWER | `Range: bytes=0-0` | 206 | **206** (`0-0/34654319`, len 1) | `no-store` |
| 126 | 〃 | REVIEWER | `Range: bytes=-100` (suffix) | 206 | **206** (`34654219-34654318/…`) | `no-store` |
| 126 | 〃 | REVIEWER | `Range: bytes=999-0` (역전) | 416 | **416** (`Content-Range: bytes */34654319`) | `no-cache, no-store, …` |
| 126 | 〃 | REVIEWER | `Range: bytes=abc` / `byte=0-10` / `bytes=5-2` / `bytes=-0` | 416 | **416** ×4 | 〃 |
| 126 | 〃 | REVIEWER | `Range: bytes=34654319-` (=total) | 416 | **416** | 〃 |
| 126 | 〃 | REVIEWER | `Range: bytes=99999999999-` | 416 | **416** | 〃 |
| 126 | 〃 | REVIEWER | `Range: bytes=0-10, 20-30` (다중) | — | **206** (첫 range 만, `0-10`) | `no-store` |
| 126 | 〃 | REVIEWER | `Range: bytes=` (빈 값) | — | **200** 전체 | `no-store` |
| **127** | **`F`**(KPST 실패) / procLog FAILED | REVIEWER | `/stream` | 404 | **404** | — |
| **133** | **`F`**(신고 OPEN) / **procLog SUCCEEDED + 파일 실재** | REVIEWER | `/stream` | 404 | **404** | — |
| 133 | 〃 | REVIEWER | `/stream-url` | 404 | **404** | — |
| 133 | 〃 | WORKER(배정자) | `/stream` | 404 | **404** | — |
| 20031 | `Y` / **procLog 없음**(seed 합성) | REVIEWER | `/stream` | 404 | **404** | — |
| 999999 | 미존재 | REVIEWER | `/stream` · `/stream-url` | 404 | **404** ×2 | — |
| **129** | `Y` / 증강 파생(`ORGNL_RAW_SN=126`) | REVIEWER | `/stream` Range | 206 | **206** | `no-store` |
| **130 / 131** | `Y` / 해상도 파생 720P·480P | REVIEWER | `/stream` Range | 206 | **206** ×2 | `no-store` |

**게이트 발화가 매 요청 일어나는 증거**(backend 로그) — 03:00·03:09·03:17 세 시점 모두 동일 WARN 이 재출력됐다. 즉 `stream-meta` 캐시가 채워져 있어도 게이트를 건너뛰지 않는다:
```
03:00:33 WARN [VideoStream] blocked — deident report open on this video rawSn=133
03:09:54 WARN [VideoStream] deident not valid rawSn=133 — refusing signed url
03:17:36 WARN [VideoStream] blocked — deident report open on this video rawSn=133   (×2)
03:17:36 WARN [VideoStream] blocked — deident report open on this video rawSn=127
03:17:36 WARN [VideoStream] deidentify not completed rawSn=20031 — refusing raw exposure
```
메시지가 갈리는 것도 코드 순서와 정합한다 — `/stream` 은 `requireNotUnderDeidentReport`(`VideoStreamService.java:221`)가 먼저, `/stream-url` 은 `!"Y".equals(deIdntfYn)` 인라인 검사(`:174-177`)가 먼저다.

**원본 유출 반증 (CWE-359)** — 실측 3축 모두 차단 확인:
1. `133` 은 **성공 procLog + 비식별 파일이 실재**(`/app/storage/raw/seed/133/deid/sample-cctv-1080p-mask.mp4`)하는데도 404 → `procLog` 만으로 서빙하지 않고 `DE_IDNTF_YN` 게이트가 앞선다.
2. 허용 base 는 `deidentified-path` ∪ `{deid_base}/videos/{rawSn}` ∪ `dirname(원본)/{rawSn}/deid` 세 갈래뿐(`VideoArtifactRootResolver.java:308-320,332-341`). 실측 co-locate 값 `/app/storage/raw/seed/126/deid/` 는 통과하지만 **같은 디렉터리 상위의 원본 `/app/storage/raw/seed/sample-cctv-1080p.mp4`, export 원본 프레임 `/app/storage/raw/seed/126/v1/orgnl/`** 는 어느 base 에도 속하지 않는다 → `resolveSafe` 404.
3. 스트리밍 경로에는 **원본 폴백 분기가 존재하지 않는다** — `resolveDeidLocation` 이 null 이면 곧바로 NOT_FOUND 이며 `RAW_FILE_PATH_NM` 은 co-locate base **도출**에만 쓰이고 서빙 대상이 되지 않는다(`VideoStreamService.java:409-428`).

---

## B-9 결과표 (YOLO / SAM2 / Interpolate · 좌표 정규화)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-120 | YOLO: rawSn null → INVALID_INPUT | PASS | [정적] `YoloAutolabelStep.java:169-171` + `YoloAutolabelStepTest:415 rawSn_null이면_INVALID_INPUT` | |
| TC-BATCH-121 | YOLO: 프레임별 순차 track(clipId 격리, 0=리셋) | **PARTIAL** | [실동작] 배치 로그 `[Batch][Yolo] saved labels rawSn=132 clipId=132 …` / `rawSn=136 clipId=136` — clipId=rawSn 확인. [정적] `:185`(clipId) `:200`(frameIndex 0-base) `:208-211`(요청 적재) `:330`(frameIndex++), `findByRawSnOrderByFrameNoAsc` 순서 보장 + `YoloAutolabelStepTest:750` | ★요청 계약은 충족하나 **track 호출의 산출(track_id)이 실환경에서 전량 null** — DB `LS_DATA_LBL.TRCK_ID` non-null **1/298**. → **B-ISSUE-61**, 부수로 **B-ISSUE-62** |
| TC-BATCH-122 | YOLO: ai-server 호출 실패 → EXTERNAL_API_ERROR | PASS | [정적] `:212-216` + `YoloAutolabelStepTest:424` | |
| TC-BATCH-123 | YOLO: mock 응답 감지 WARN(CRLF 살균) | PASS | [정적] `:221-233` (`LogSanitizer.sanitize(resp.source()/mockReason())`) + 테스트 `:493`(WARN 출력·파이프라인 계속) `:521`(정상 응답 시 WARN 없음) | 실환경은 ai-server `AI_MOCK_MODE=false` 라 mock 응답 미발생 |
| TC-BATCH-124 | YOLO: DTCT_TYPE_CD 축 매핑 | PASS | [실동작] `LS_DATA_LBL` 실측 — YOLO 저장분 `person→LBL_ID=1`, `car→2`, `truck→6` 로 마스터 PK 귀속(미매칭 없음). [정적] `:277` `findLabelIdByDtctType(...).orElse(null)` + 테스트 `:862/:880/:1005/:1029` | |
| TC-BATCH-125 | YOLO: 프리셋 토글 필터(미매핑 fail-safe) | PASS | [실동작] 로그 `preset=(none)` 인데 검출 전량 저장(`yoloCount=18 bboxSaved=18`) = BOTH fail-safe. [정적] `:400-410` + 테스트 `:545/:840` | |
| TC-BATCH-126 | YOLO: 이미지 경로 순회 방어(CWE-22) | PASS | [정적] `:427-432` — `baseRawPath.resolve(rel).normalize()` 후 `startsWith(baseRawPath)` 위반 시 `INVALID_INPUT`(경로 원문 미노출). SAM2 도 동일(`Sam2SegmentStep.java:314-319`) | ⚠ 전용 회귀 테스트 **부재**(`batch/step/` 내 traversal 테스트는 Deident/Ffmpeg 쪽만) + lexical 검증이라 심링크 미고려 → **B-ISSUE-64**(정보) |
| TC-BATCH-127 | YOLO: conf/imgsz/iou 설정 fail-safe | PASS | [실동작] 로그 `conf=0.25 imgsz=1280 iou=0.5` — conf 가 코드 기본값 0.4 가 아닌 **SystemConfig 실값 0.25** 로 요청에 반영됨. [정적] `:179-181,358-386` + 테스트 `:658`(전달) `:682`(미설정 기본값) `:703`(조회 실패 폴백) | `imgsz` 는 ai-server 에서 무효(640 고정, UNCERTAINTIES #14 미해소) — BE 전달 자체는 확인됨 |
| TC-BATCH-128 | SAM2: (srcSn,label,trackId) dedup DB BBOX 우선 | PASS | [정적] `Sam2SegmentStep.java:241-257` — `LinkedHashMap` 에 DB BBOX 선등록 후 hint 는 `putIfAbsent` + 테스트 `:330/:409/:436/:461` | 실환경은 trackId 전량 null 이라 label 단위 dedup 으로 fallback(B-ISSUE-61) |
| TC-BATCH-129 | SAM2: polygon=false 라벨 skip | PASS | [정적] `:182-188`(WARN skip) + 테스트 `:273` | |
| TC-BATCH-130 | SAM2: 응답 폴리곤 상한 초과 → 단순화 | PASS | [실동작] `LS_DATA_LBL` POLYGON 5건 정상 저장(126: lblSn 129~133). [정적] `:196` + `capPolygon :365-383`(`PolygonSimplifier.simplifyToMax(pts,1.0,MAX_POINTS_PER_LABEL)`) + 테스트 `:243`(4192점→1000 이하) | |
| TC-BATCH-131 | SAM2: 호출 실패 → EXTERNAL_API_ERROR | PASS | [정적] `:226-234` | |
| TC-BATCH-132 | Interpolate: 프레임/후보 없음 → 0 | PASS | [실동작] 배치 로그 `[Batch][Interpolation] no interpolation candidates rawSn=126/132/133/135/136/137/138/143` — 예외 없이 0 반환. [정적] `:142-146`(frames empty) `:176-180`(candidates empty) + 테스트 `:113/:125` | ★실환경에서 **이 분기만** 타고 있다(B-ISSUE-61) |
| TC-BATCH-133 | Interpolate: 재실행 멱등 stale 선삭제 | PASS | [정적] `TrackInterpolationStep.java:157-172` — `findInterpolatedLblSnsByRawSn` → 프레임 bump(락 선점) → `aiInfoRepository.deleteByDataLblSnIn` → `lblRepository.deleteAllByIdInBatch` (자식→부모) + 테스트 `:476` | 실환경 미도달(보간 후보 0건, B-ISSUE-61) |
| TC-BATCH-134 | Interpolate: 트랙 부분실패 격리 | PASS | [정적] `:192-197` 트랙 단위 try/catch + WARN(무시 아님) + 테스트 `:450` | 동상 |
| TC-BATCH-135 | Interpolate: 혼재 타입 트랙 skip | PASS | [정적] `:353` `distinct().size()>1` → WARN skip + 테스트 `:433` + 단일트랙 IT `:251` | 동상 |
| TC-BATCH-136 | Interpolate: BBOX flat/nested 양포맷 | PASS | [정적] `:441 parseBbox`(`LabelPointSerializer.fromJson` 3변종 흡수) + 테스트 `:494` | |
| TC-BATCH-137 | Interpolate 단건: from+to stale 삭제 | PASS | [정적] `:259-328 interpolateSingleTrackTouched` — stale 대상이 `List.of(fromTrackId, toTrackId)` 양쪽(`:276-277`), `@Transactional` 미부착으로 caller tx 참여 + **예외 미포획(전파)**(`:311-313` 주석 명시) + IT `TrackInterpolationSingleTrackIntegrationTest:229/:268/:300` | 근거 드리프트: 메서드명·라인(아래 절) |
| TC-BATCH-138 | MarkingLoadStep: 마킹 로드+최신 markCn 파싱 | PASS | [실동작] 배치 로그 `[BatchOrchestrator] marking check rawSn=… count=…` 후 프레임 추출 정상. [정적] `batch/pipeline/MarkingLoadStep.java:52-62` + 테스트 `MarkingLoadStepTest:48/:66` | 근거 경로 드리프트 |
| TC-BATCH-139 | MarkingLoadStep: markCn 파싱 실패 → INTERNAL_ERROR | PASS | [정적] `:64-70` + 테스트 `:78` | |
| TC-BATCH-140 | 파이프라인 순서 검증 | PASS | [정적] `BatchPipelineConfig.java:32-43` `List.of(markingLoad, vlm, frame, yolo, sam2, interp)` + pre-marking `List.of(deid)` + 테스트 `BatchPipelineConfigTest:29/:59` | |
| TC-BATCH-141 | ★좌표 정규화 단일 규칙 — clamp | PASS | **[실동작] 결정적 증거** — `LS_DATA_LBL` lblSn=123(src 67, car, YOLO) `POINT_CN` 이 `[[0.0,683.089…],[396.114…,1017.736…]]` 로 **x1 이 정확히 0.0**(모델 음수 출력이 하한 clamp 됨). 온라인 경로도 동일 유틸 경유(`AutolabelOnlineService.java:256 normalizeDetections`). [정적] `DetectionBoxNormalizer.java:50-69` + `DetectionBoxNormalizerTest` 11건 | ★3 정책 그대로: AI 응답=clamp / 사용자 저장=400 거부 (통일 제안 없음) |
| TC-BATCH-142 | 유한성 가드가 clamp **이전** | PASS | [정적] `DetectionBoxNormalizer.java:54-58` — 개수 검사(`:51-53`) 직후, clamp(`:59-64`) **이전** 에 `!Double.isFinite(v)` → `IllegalArgumentException` + 테스트 `:115 NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지` `:130` | |
| TC-BATCH-143 | 퇴화 박스는 예외가 아니라 스킵 | PASS | [정적] `DetectionBoxNormalizer.java:65-67` `x2<=x1 \|\| y2<=y1 → Optional.empty()`, 호출부 `YoloAutolabelStep.java:263-268` `droppedDegenerate++ → continue` + 테스트 `:89/:99`(유틸) `:259`(배치 나머지 저장) | 실측 로그 `droppedDegenerate=0`(실모델이 퇴화 미출력) |
| TC-BATCH-144 | 형식 위반도 검출 단위 드롭 | PASS | [정적] `YoloAutolabelStep.java:259-275` — `IllegalArgumentException` 을 **검출 루프 안에서** catch → `droppedMalformed++ → continue`(영상 전체 실패 아님). 온라인 all-or-nothing 400 은 별도 유지(`:257-258` 주석) + 테스트 `:281/:309` | 실측 로그 `droppedMalformed=0` |
| TC-BATCH-145 | SAM box 프롬프트가 clamp 좌표를 공유 | PASS | [정적] `:316-325` — `hints.add(new BbHint(..., points, ...))` 의 `points` 가 `:259-269` 에서 정규화된 값(원본 `d.points()` 아님) + 테스트 `:356 폴리곤전용_프리셋의_hint좌표는_이미지_경계로_clamp된_값이다` | |
| TC-BATCH-146 | 퇴화 시 bbox·polygon 동시 스킵 | PASS | [정적] `:259-275` 의 `continue` 가 `toggle.bbox()`(`:280`)·`toggle.polygon()`(`:316`) **양쪽 앞**에 위치 + 테스트 `:332/:379` | |
| TC-BATCH-147 | 해상도 측정 불가 시 상한 생략(fail-open) | PASS | [정적] `DetectionBoxNormalizer.java:72-77 upperBound` — bounds null/길이≠2/0이하 → `Double.MAX_VALUE`(상한 없음), 하한 0 만 적용. 호출부 `YoloAutolabelStep.java:236-237` `frameBoundsResolver.resolve(src).orElse(null)` + 테스트 `:77/:138` | `FrameBoundsResolver.java:76-93` 도 실패를 캐시하지 않아 복구 시 되살아남 |
| TC-BATCH-148 | 검출 0건 프레임은 해상도 해석 자체를 안 함 | PASS | [정적] `YoloAutolabelStep.java:236-237` `resp.detections().isEmpty() ? null : frameBoundsResolver.resolve(...)` — 삼항 단축평가로 resolver 미호출 | 전용 단위 테스트 부재(`YoloAutolabelStepTest` 는 resolver 를 항상 stub) |
| TC-BATCH-149 | 라벨셋 버전 bump 범위 = 라벨이 실제 생성된 프레임만 | PASS | **[실동작] 결정적 증거** — rawSn 136(12프레임): 라벨 0건인 `src 93(frm 0)`·`src 96(frm 3)` 의 `LBL_VER=0`, 라벨 보유 10프레임은 `LBL_VER=2`(YOLO+SAM2 각 1회). rawSn 137(`yoloCount=0`)은 유일 프레임 `LBL_VER=0`. [정적] `:189-190,343-345 bumpLabelVersionIn(labeledFrames)` + `LsDataSrcRepository.java:292-295` | 구 `bumpLabelVersionByRawSn` 는 잔존하나 이 경로에서 미사용 |

---

## B-10 결과표 (비디오 스트리밍 — Range · 비식별본만 서빙)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-STREAM-B01 | 비식별 미완료 → NOT_FOUND(원본 차단) | PASS | [실동작] `20031`(`Y` 이나 성공 procLog 없음) → **404** + 로그 `deidentify not completed rawSn=20031 — refusing raw exposure`. `127`(`F`/FAILED) → **404**. [정적] `VideoStreamService.java:226-230` | |
| TC-STREAM-B02 | 영상 미존재 → NOT_FOUND | PASS | [실동작] `999999` `/stream`·`/stream-url` 모두 **404**. [정적] `:411-412` + 테스트 `VideoStreamServiceTest:164` | |
| TC-STREAM-B03 | deIdntfYn='F'/'N' → null(신고본/미수행 거부) | PASS | [실동작] `133` — **성공 procLog + 비식별 파일 실재**인데 **404**(`DE_IDNTF_YN='F'`). `127`(F) 404. [정적] `:414-419` + 테스트 `:534/:617` | procLog 우선 서빙이 아님이 실증됨 |
| TC-STREAM-B04 | 비식별 경로가 허용 base 전부의 밖 → FORBIDDEN(CWE-22) | PASS | [정적] `:357`(2-way allowlist 주입) `:451-461`(base 후보) `:470-485 resolveSafe` — 어느 base 에도 없으면 거부 + 테스트 `:180/:752 S6_구위치도_신위치도_아닌_제3의_경로는_차단된다` | ★**기대값 드리프트** — 현 코드는 FORBIDDEN 이 아니라 **NOT_FOUND 로 정규화**(`:466-468` "S7 … 구 FORBIDDEN"). 존재/권한 구분 노출 회피(CWE-209)를 위한 **의도된 강화**라 차단 자체는 성립 → PASS |
| TC-STREAM-B05 | 비식별 파일 부재 → NOT_FOUND(캐시 안 됨) | PASS | [정적] `:360-363` `Files.exists/isRegularFile` 위반 → 예외(= `@Cacheable` 미저장) + 테스트 `:146 비식별_파일이_물리적으로_없으면_NOT_FOUND` | 실환경에 "procLog SUCCEEDED + 파일 부재" 케이스가 없어 live 재현 불가(mock/KPST 산출물 전건 실재 확인) |
| TC-STREAM-B06 | Range 없음 → 200 전체+Accept-Ranges | PASS | [실동작] `200` + `Accept-Ranges: bytes` + `Content-Length: 34654319`(전체) | 200 응답에 `Content-Range` 도 함께 실린다 → **B-ISSUE-63**(정보, RFC 7233 비정합) |
| TC-STREAM-B07 | Range 유효 → 206 Partial+청크 상한 | PASS | [실동작] `bytes=0-` → **206**, `Content-Range: bytes 0-8388607/34654319`, `Content-Length: 8388608` = `min(start+chunk-1, rangeEnd)` 적용. `bytes=0-0`→`0-0`(1B), `bytes=-100`→ 말미 100B. [정적] `:247-272` | |
| TC-STREAM-B08 | Range 문법 오류 → 416 | PASS | [실동작] `bytes=999-0`·`bytes=abc`·`byte=0-10`·`bytes=5-2`·`bytes=-0` **전부 416** + `Content-Range: bytes */34654319`. [정적] `:239-245,433-439` | `bytes=`(빈 값)는 Spring `getRange()` 가 빈 목록을 돌려줘 **200 전체**(Range 무시) — RFC 상 허용 동작 |
| TC-STREAM-B09 | Range start≥total → 416 | PASS | [실동작] `bytes=34654319-`(=total)·`bytes=99999999999-` → **416** + 로그 `range out of bounds`. [정적] `:251-256` | |
| TC-STREAM-B10 | 청크 상한: 미설정/<1MB → 8MB | PASS | [실동작] env 에 `authoring.storage.stream-chunk-size` 미설정 → 실응답 청크 **8,388,608B**. [정적] `:314-319` + 테스트 `:347`(0 이하 폴백) `:371`(음수 폴백) | |
| TC-STREAM-B11 | 청크 상한: >64MB → 64MB(오버플로 방지) | PASS | [정적] `:64 MAX_CHUNK_SIZE=67_108_864` + `:318 Math.min(...)` + 테스트 `:395`(클램프) `:408 비정상_대형_chunkSize에도_long오버플로_없이_안전서빙` | 실환경 설정이 기본값이라 live 불가 |
| TC-STREAM-B12 | 서명 URL: 비식별 무효 → NOT_FOUND | PASS | [실동작] `133`(신고 F) `/stream-url` → **404** + 로그 `deident not valid rawSn=133 — refusing signed url`. `999999` → 404. [정적] `:174-177`(인라인) + `:189`(게이트 이중) | |
| TC-STREAM-B13 | 서명 URL: 시크릿 미설정 → 503 | PASS | [정적] `:191-197` `!streamUrlSigner.isConfigured() → SERVICE_UNAVAILABLE`(fail-closed, 내부정보 미노출) + `StreamUrlSigner.java:69-84`(미설정 시 `configured=false`, 32B 미만이면 **부팅 차단**) + 테스트 `:631` | 실환경 `STREAM_SIGN_SECRET` 설정(64자) — live 불가 |
| TC-STREAM-B14 | 서명 URL: userNo 바인딩(재사용 차단) | PASS | [실동작] 발급 URL `?exp=…&u=1001&sig=…` + `Set-Cookie: klid_stream_nonce=…; Path=/api/v1/videos; HttpOnly; SameSite=Lax`. 변조 실측 — **쿠키 있음+원본=200 / 쿠키 없음=401 / sig 1자 변조=401 / sig 삭제=401 / u→2002=401 / exp 미래로 위조=401 / exp 과거=401 / rawSn 126→132=401**. [정적] `:202-206` + `StreamUrlSigner.sign/verify`(canonical `{rawSn}.{exp}.{userNo}.{nonce}`, `MessageDigest.isEqual` 상수시간) + `StreamSignedUrlControllerTest` 19건 | sig 대문자화·URL 퍼센트 인코딩은 200 이나 **같은 값의 다른 표기**일 뿐 우회가 아님(`verify` 가 `toLowerCase()` 정규화, 서버 디코딩) |
| TC-STREAM-B15 | 스트림 인가: STREAM_SIGNED 또는 REVIEWER/WORKER | PASS | [실동작] 위 "B-ISSUE-63 교차 확인" 표 전건. [정적] `VideoController.java:244`(역할·서명) + **`:253 labelAccessGuard.verifyRawAccess`** + 테스트 `VideoStreamAssignmentAuthorizationTest` 17건 | ★**비고 드리프트** — 케이스표의 "⚠ 영상 단위 배정 검증은 없다(B-ISSUE-63 미해소 — 현재 동작 고정)"는 **폐기된 서술**이다. 현재는 배정 검증이 있고 미배정 WORKER 는 403 |
| TC-STREAM-B16 | 스트림 메타 캐시: null 미캐싱 | PASS | [정적] `:348 @Cacheable(cacheNames="stream-meta", key="#rawSn", unless="#result == null")` — 비식별 미완료(null)는 캐시되지 않아 stale 404 고정 없음. 예외(FORBIDDEN/NOT_FOUND)도 캐시 대상 아님. 비식별 완료 시 `DeidentifyStep.java:363 evictAfterCommit` 이 추가 무효화 | [실동작 간접] `20031` 반복 요청이 매번 `resolveStreamMeta` 에 도달(로그 `deidentify not completed` 재출력) |
| TC-STREAM-B17 | 스트림 메타 캐시 무효화: 신고('F') 후 즉시 | PASS | [정적] `label/service/DeidentReportService.java:244-248 streamMetaCacheEvictor.evictAfterCommit(rawSn)`(신고) `:395`(resolve) `:454` + `common/cache/StreamMetaCacheEvictor.java:73-92`(`afterCommit` 동기화, 롤백 시 미실행) + IT `DeidentReportStreamGateIT:226` | 대상은 **그 영상 하나** — 주석 `:246-247` 이 파생 캐시 미변경을 확정 정책으로 명시(★1 정합) |
| TC-STREAM-B18 | ★게이트 뒤 미디어 응답은 `Cache-Control: no-store` | PASS | **[실동작]** 200(Range 없음)·206(`bytes=0-`, `bytes=0-0`, `bytes=-100`, 다중 range) **전 응답 헤더가 `Cache-Control: no-store`**. [정적] `:271,281`(양 경로 `.cacheControl(noStoreForGatedMedia())`) `:302-304` + 테스트 `:222/:246` | `max-age` 잔존 0건 — 재생 중 신고 접수 시 클라이언트 캐시 우회(CWE-359/525) 경로 없음 |
| TC-STREAM-B19 | 신고 게이트가 캐시 **앞**(매 요청)에서 평가 | PASS | **[실동작]** 게이트 WARN 이 03:00·03:09·03:17(×2) 매 요청 재출력 = 캐시 히트가 게이트를 건너뛰지 않음. [정적] `:221 requireNotUnderDeidentReport(rawSn)` 이 `:226 resolveStreamMetaCached(rawSn)` **앞**. [테스트] `DeidentReportStreamGateIT:226 캐시가_먼저_채워진_뒤_신고해도_스트리밍이_차단된다` | |
| TC-STREAM-B20 | 비식별 base 2-way allowlist | PASS | **[실동작] 결정적 증거** — rawSn 126 의 `DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/126/deid/sample-cctv-1080p-mask.mp4` 는 `STORAGE_DEIDENTIFIED_PATH=/app/storage/deidentified` **밖**(co-locate)인데 **200/206 정상 서빙**. 동시에 파생 129~131 은 deid base 하위 경로로 서빙 → 구·신 두 위치 모두 통과. [정적] `:357,451-461` → `VideoArtifactRootResolver.java:332-341 readableDeidVideoBases`(프레임 추출기와 **동일 판정 축**) | 허용 폭 확인: co-locate 는 `dirname(원본)/{rawSn}/deid` **한 디렉터리**로 한정 — raw base 통째 허용 아님 |
| TC-STREAM-B21 | ★신고 구간 스트리밍만 404(412 아님) | PASS | [실동작] `133` `/stream`·`/stream-url` **404**(본 검증) ↔ 같은 영상 `GET /v1/frames/78/labels` **412**(pipeline-drive §1-20 실측). [정적] `:134-138` javadoc 이 CWE-209 오라클 회피를 명시, `:146` `ErrorCode.NOT_FOUND` | 확정 정책 — 비대칭을 결함으로 보고하지 않음 |
| TC-STREAM-B22 | ★파생영상은 자기 rawSn 게이트만 판정 | PASS | [정적] `DeidentReportGate.java:66-71` — `findDeIdntfYnByRawSn(rawSn)` 단일 컬럼, `ORGNL_RAW_SN` 미조회(javadoc `:23-37` 이 조상/자손 전파 폐기를 명시) + 테스트 `DeidentReportGateTest:88` · `VideoStreamServiceTest:558/:583` · `DeidentReportStreamGateIT:194`. [실동작 부분] 파생 129/130/131 → 206 | "부모가 신고 중"인 조합은 실데이터로 조성하지 않았다 — 133(신고 OPEN)에 파생이 없고, 파생 생성 API 는 APPROVED/신고 조건상 133 에 적용 불가하며, 126 에 신고를 걸면 다른 에이전트의 참조 데이터를 훼손하므로 **의도적으로 미수행** |

---

## 근거 드리프트

동작 결함이 아니라 **케이스표의 표기(라인/경로/메서드명/기대값)가 현행 코드와 어긋난** 건이다.

| ID | 표기된 근거·기대 | 실제 | 성격 |
|---|---|---|---|
| TC-STREAM-B04 | 기대결과 **FORBIDDEN** | `VideoStreamService.java:466-485` — **NOT_FOUND 로 정규화**("S7 … 구 FORBIDDEN") | ★기대값 드리프트(의도된 보안 강화) |
| TC-STREAM-B15 | 비고 "⚠ 영상 단위 배정 검증은 없다(B-ISSUE-63 미해소 — 현재 동작 고정)" / 근거 `VideoController.java:@PreAuthorize(stream)` | `VideoController.java:244`(@PreAuthorize) + **`:253 labelAccessGuard.verifyRawAccess`** — 배정 검증 존재 | ★비고 무효(해소 반영 필요) |
| TC-STREAM-B17 | `DeidentReportService.java:244-250` | `label/service/DeidentReportService.java:244-248`(패키지 경로 미기재) | 라인·경로 |
| TC-BATCH-137 | `TrackInterpolationStep.java:247-330`, 메서드 `interpolateSingleTrack` | `:259-328`, 메서드 **`interpolateSingleTrackTouched`**(`TouchedFrames` 반환) | 메서드명·라인 |
| TC-BATCH-138 | `MarkingLoadStep.java:50-58` | `batch/pipeline/MarkingLoadStep.java:52-62` | 라인·경로 |
| TC-BATCH-139 | `MarkingLoadStep.java:60-65` | `batch/pipeline/MarkingLoadStep.java:64-70` | 라인·경로 |
| TC-BATCH-140 | `BatchPipelineConfig.java:33-41` | `:32-43` | 라인 |
| TC-BATCH-121 | `:185,201-211` | `:185`(clipId) `:200`(frameIndex 초기화) `:208-211`(요청) `:330`(증가) | 라인 |
| TC-BATCH-125 | `:240-243,400` | `:240-244`, `:400-410` | 라인 |
| TC-BATCH-126 | `:427` | `:427-432` | 라인 |
| TC-BATCH-130 | `:196,365-380` | `:196`, `:365-383` | 라인 |
| TC-BATCH-131 | `:228-232` | `:226-234` | 라인 |
| TC-BATCH-132 | `:131-157` | `:142-146`(frames) + `:176-180`(candidates) | 라인 |
| TC-BATCH-133 | `:168` | `:157-172`(bump→AI_INFO→LBL 삭제 블록 전체) | 라인 |
| TC-BATCH-134 | `:190` | `:192-197` | 라인 |

### 참고 관측 (판정에 영향 없음)

- **온라인 오토라벨 응답의 `savedCount`** — `POST /v1/frames/66/autolabel` 응답이 `{"detectedCount":6,"savedCount":6}` 인데 **DB 는 전혀 변하지 않는다**(실측: `ls_data_lbl` 298→298, `ls_data_lbl_ai_info` 168→168, 모든 항목 `lblSn:null`). 필드명이 "저장됐다"로 읽혀 2경로 분리(온라인=미저장) 검증자를 오도할 수 있다. 실동작은 정상.
- **다중 Range** `bytes=0-10, 20-30` → `multipart/byteranges` 가 아니라 **첫 range 만 단일 206**. 널리 쓰이는 단순화이며 케이스 대상 아님.

---

## 이슈 상세

### [B-ISSUE-61] TC-BATCH-121 — 배치 오토라벨의 `TRCK_ID` 가 실환경에서 전량 NULL → 트랙 보간 단계가 항상 0건 산출

- **심각도**: MEDIUM (기능 미달 · 테스트 GREEN 뒤에 가려진 런타임 무산출)
- **기대 동작(기대효과)**: `predictYoloTrack` 은 `clipId`(영상 격리)·`frameIndex`(0=리셋)로 트래커 상태를 유지해 **같은 객체에 동일 `track_id`** 를 부여하고, 그 값이 `LS_DATA_LBL.TRCK_ID` 에 적재되어야 한다. 그래야 `TrackInterpolationStep`(`findAutoBboxWithTrackId` → trackId 그룹핑)이 키프레임 사이를 보간하고, SAM2 dedup 축(`(srcSn,label,trackId)`)·트랙 편집/병합 UI 가 성립한다(CVAT 트랙 보간 포팅 · SFR-08-01).
- **현재 동작(이슈 내용)**:
  - DB 실측 — `select count(*) from ls_data_lbl where trck_id is not null` = **1 / 298**(그 1건도 srcSn=63 의 레거시 `0`). 파이프라인이 완주한 rawSn 126·132·133·135·136·137·138·143 의 YOLO 저장분은 **전부 NULL**.
  - 그 결과 보간 단계가 매번 후보 0건으로 끝난다 — backend 로그:
    ```
    [Batch][Interpolation] no interpolation candidates rawSn=126
    [Batch][Interpolation] no interpolation candidates rawSn=132 / 133 / 135 / 136 / 137 / 138 / 143
    ```
    `select count(*) from ls_data_lbl_ai_info where lbl_src_cd='INTERPOLATE'` = **0**(YOLO 108 · SAM2 60).
  - BE 측 배선은 정상이다 — `YoloLabelPersister.java:82-84` 가 `trackId` 를 문자열로 변환해 `LsDataLbl.createAutoBbox(..., trackIdStr)` 로 넘긴다. 즉 **ai-server 가 `track_id: null` 을 돌려준다.**
  - ai-server 로그가 원인 축을 보여준다 — `app/models/bytetrack_util.py`:
    ```
    WARNING:app.models.bytetrack_util:[ByteTrack] tracker_id 길이 불일치 dets=6 tracker_ids=5 — 누락분 track_id=None 유지
    ```
    `_apply_bytetrack` 은 `det.track_id = int(tid) if tid is not None and int(tid) >= 0 else None`(`bytetrack_util.py:88-90`) 로 **-1(미확정 트랙)을 None 으로 정규화**한다. 프레임 추출은 마킹 간격 기반(`intervalFrames=300` ≈ 10초)이라 인접 추출 프레임 사이에 IoU 연관이 성립하지 않고, ByteTrack 이 트랙을 "확정" 상태로 올리지 못해 매 프레임이 미확정 → None 이 된다.
  - 단위 테스트는 통과한다 — `YoloAutolabelStepTest:727 YoloStep_predictYoloTrack_을_호출하고_trackId_를_LsDataLbl_에_저장`, `TrackInterpolationStepTest` 19건이 모두 **trackId 가 부여된 픽스처**를 전제로 하기 때문이다. 실파이프라인에서 전제가 성립하지 않는다는 사실은 어떤 테스트도 관측하지 않는다(memory `tests-green-runtime-broken-selfcall-tx` 와 동형 패턴).
- **재현/확인 경로**:
  ```sql
  select count(*) from ls_data_lbl where trck_id is not null;                       -- 1
  select lbl_src_cd, count(*) from ls_data_lbl_ai_info group by 1;                  -- YOLO/SAM2 만, INTERPOLATE 0
  ```
  ```bash
  docker logs klid-backend --since 60m 2>&1 | grep "\[Batch\]\[Interpolation\]"     # 전건 "no interpolation candidates"
  docker logs klid-ai-server --since 30m 2>&1 | grep ByteTrack                      # tracker_id 길이 불일치
  ```
- **영향**: ①트랙 보간(TC-BATCH-132~137)·트랙 편집/병합/분할(`TrackEditService`·`TrackMergeService`)이 실데이터에서 대상 0건으로 무의미 ②SAM2 dedup 이 `(label, trackId=null)` = 라벨 단위로 축소되어 같은 프레임의 서로 다른 동종 객체가 1건으로 합쳐짐(rawSn 126 실측: YOLO car 3건 → SAM2 car 폴리곤 1건) ③학습데이터셋에 객체 연속성 정보가 결손. 보안 영향 없음.
- **수정 방향(제안)**: (a) 프레임 추출 간격과 트래킹 전제를 정합시킨다 — 추적이 목적이면 마킹 구간 내 **연속 프레임**을 별도로 뽑아 트래커에 먹이거나, (b) ai-server 가 미확정 트랙에도 잠정 ID 를 부여(`min_hits=1` 상당)하도록 `ByteTrackTracker` 파라미터를 노출하거나, (c) 현재 샘플링 정책에서는 트랙 축이 성립하지 않음을 인정하고 보간 단계를 `isEnabled` 조건부로 낮춘다. ⚠ **구현하지 않는다.**

### [B-ISSUE-62] TC-BATCH-121 — ai-server ByteTrack 이 필터된 `tracker_id` 를 위치(zip)로 매칭해 `track_id` 오귀속 가능

- **심각도**: MEDIUM (잠재적 데이터 오염 — 현재는 B-ISSUE-61 때문에 표면화되지 않음)
- **기대 동작(기대효과)**: 트래커가 돌려준 `tracker_id` 는 **그 id 가 부여된 검출** 에 붙어야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/bytetrack_util.py:84-90`
  ```python
  tracked = tracker.update(sv_dets)
  tracker_ids = list(tracked.tracker_id) if tracked.tracker_id is not None else []
  if len(tracker_ids) != len(dets):
      logger.warning("[ByteTrack] tracker_id 길이 불일치 dets=%d tracker_ids=%d — 누락분 track_id=None 유지", ...)
  for det, tid in zip(dets, tracker_ids):  # 짧은 쪽 길이만큼만 매핑
  ```
  `tracker.update()` 는 **연관에 성공한 검출만 남긴 `sv.Detections`** 를 반환할 수 있는데(로그가 실제로 `dets=6 / tracker_ids=5`, `dets=3 / tracker_ids=2`, `dets=8 / tracker_ids=7` 로 매 호출 불일치를 보고한다), 코드는 원본 `dets` 와 **위치 순서로 zip** 한다. 필터링으로 인덱스가 밀리면 **5번째 트랙 id 가 원본 6개 중 앞 5개에 순서대로 붙어** 다른 객체에 귀속된다. 경고는 "누락분은 None 유지"라고만 말하고 **정렬 대응이 보장되지 않는다는 사실은 다루지 않는다**.
- **재현/확인 경로**: `docker logs klid-ai-server 2>&1 | grep "tracker_id 길이 불일치"` — 관측된 전 호출에서 발생. 단정하려면 `tracked.xyxy` 와 `dets[i].points` 를 대조해야 하는데 현재 코드가 그 대조를 하지 않는다.
- **영향**: `track_id` 가 실제로 부여되기 시작하면(B-ISSUE-61 해소 시) 보간이 **서로 다른 객체를 한 트랙으로 잇는** 오보간 산출물을 만들 수 있다(CWE 해당 없음 — 데이터 정확성).
- **수정 방향(제안)**: `tracker.update()` 결과를 위치가 아니라 **좌표(xyxy) 동치 또는 인덱스 맵**으로 원본 검출에 되매핑하고, 되매핑에 실패한 검출만 `None` 으로 남긴다. ⚠ **구현하지 않는다.**

### [B-ISSUE-63] TC-STREAM-B06 — Range 없는 200 응답에도 `Content-Range` 헤더가 부여됨 (RFC 7233 비정합, 정보)

- **심각도**: LOW (정보 — 케이스 기대결과는 충족)
- **기대 동작(기대효과)**: `Content-Range` 는 206(또는 416) 응답의 헤더다. 200 전체 응답에는 의미가 없다(RFC 7233 §4.2).
- **현재 동작(이슈 내용)**: [실동작]
  ```
  GET /api/v1/videos/126/stream   (Range 헤더 없음)
  HTTP/1.1 200
  Accept-Ranges: bytes
  Cache-Control: no-store
  Content-Range: bytes 0-34654318/34654319     ← 200 인데 부여됨
  Content-Length: 34654319
  ```
  원인은 200 경로도 `ResourceRegion` 을 반환하기 때문이다(`VideoStreamService.java:276-282`) — `ResourceRegionHttpMessageConverter` 가 write 시점에 `Content-Range` 를 직접 add 한다(`:262-265` 주석이 206 경로에서 이 동작을 이미 설명하고 있다).
- **재현/확인 경로**: `curl -s -D - -o /dev/null -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/126/stream`
- **영향**: 엄격한 중간 프록시/캐시가 200+`Content-Range` 를 부분 응답으로 오인할 여지. 브라우저 재생은 정상(실측). 보안 영향 없음.
- **수정 방향(제안)**: Range 부재 경로는 `ResourceRegion` 대신 `Resource`(또는 `ResponseEntity<Resource>`)를 반환하거나, 200 응답에서 `Content-Range` 를 제거한다. ⚠ **구현하지 않는다.**

### [B-ISSUE-64] TC-BATCH-126 — 배치 오토라벨 이미지 읽기가 lexical 경로 검증뿐이고 전용 회귀 테스트가 없다 (정보)

- **심각도**: LOW (정보 — 현재 입력원이 내부 DB 값이라 악용 경로 미확인)
- **기대 동작(기대효과)**: 프레임 이미지 open 은 `StorageSubtreePolicy`/`openNoFollow`(`NOFOLLOW_LINKS`) 계열의 realpath 기반 단일 판정기를 쓰는 것이 이 리포의 확립된 규약이다(CLAUDE.md "비식별 프레임을 파일로 여는 4경로는 단일 규약", CWE-59/367).
- **현재 동작(이슈 내용)**: `YoloAutolabelStep.java:427-440`(및 동일 코드 `Sam2SegmentStep.java:314-327`)
  ```java
  Path imagePath = baseRawPath.resolve(relativePath).normalize();
  if (!imagePath.startsWith(baseRawPath)) { throw new CustomException(ErrorCode.INVALID_INPUT, "이미지 경로 범위 초과"); }
  byte[] bytes = Files.readAllBytes(imagePath);
  ```
  ①`normalize()` 후 `startsWith` 는 **lexical** 판정이라 base 하위의 심링크가 밖을 가리키면 통과한다(검증 경로 == open 경로이므로 TOCTOU 창은 좁으나 심링크 자체는 걸러지지 않는다). ②`backend/src/test/java/kr/co/cudo/authoring/batch/step/` 에 이 두 클래스의 **경로 순회 회귀 테스트가 없다**(traversal 테스트는 `DeidentFrameAttacherTest:511`·`FfmpegFrameExtractorTest:265`·`DeidentifyStepFailurePersistenceIntegrationTest:161` 뿐).
- **재현/확인 경로**:
  ```bash
  grep -rn "이미지 경로 범위 초과" backend/src/main/java   # 2곳(Yolo·Sam2)
  grep -rn "Traversal\|\.\./" backend/src/test/java/kr/co/cudo/authoring/batch/step/  # Yolo/Sam2 테스트 0건
  ```
- **영향**: 현재 `LS_DATA_SRC.SRC_FILE_PATH_NM` 은 프레임 추출기가 쓴 내부 값이고 증강 반입 경로는 `VideoArtifactRootResolver.verifyIngestablePath` 로 별도 검증되므로 실악용 경로는 확인되지 않았다. 리스크는 **가드가 다른 축으로 분화되어 규약 갱신 시 조용히 뒤처지는 것**(memory `state-gate-single-entry-point-rule` 6번째 축과 동형).
- **수정 방향(제안)**: 두 스텝의 `readImageAsBase64` 를 공용 판정기(`StorageSubtreePolicy` + `openNoFollow`)로 통일하고, base 이탈·심링크 이탈 각각의 회귀 테스트를 추가한다. ⚠ **구현하지 않는다.**
