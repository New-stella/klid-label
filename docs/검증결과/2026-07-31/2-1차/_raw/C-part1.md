# C-1. TC-MARK — 마킹(자동/수동) 검증 결과 (2-1차 · 2026-07-31)

- **담당 범위**: `docs/test-cases/C-marking-labeling.md` §C-1 전체 **46건** (TC-MARK-01~46)
- **이슈 ID 대역**: C-ISSUE-01~15 (2-1차 part1 배정 번호대 — 1차 회차의 동명 ID 와 별개)
- **환경**: backend `http://localhost:18081/api` (context-path `/api`), DB `public` 스키마, mock-server :9400.
  `_raw/stack-bringup.md` · `_raw/pipeline-drive.md` 기동/구동 상태 위에서 검증.
- **토큰**: `POST /v1/dev/tokens` 로 REVIEWER(1001,INTERNAL) / WORKER(2001,INTERNAL) / PORTAL_USER(3001,PORTAL) 발급.

## 검증용 테스트 데이터 (본 검증에서 생성 — 코드/설정 무수정)

기존 rawSn 4~7 은 병렬 에이전트가 사용 중이라 **충돌 회피용 전용 행 `raw_sn=9101~9114`** 를
`ls_data_raw` / `ls_raw_data_status` / `ls_data_meta` 에 INSERT 해 사용했다(삭제 없음, 기존 행 무변경).

| rawSn | 용도 | 상태 |
|--:|---|---|
| 9101 | 입력검증 400 계열 + MANUAL 성공 | MARKING_READY / deid Y / INTRUSION / 30s |
| 9102 | AUTO 성공(TC-MARK-01) | 동상 |
| 9104 | 비식별 미완료 412 | deid **N** |
| 9105 | 이벤트유형 미지정 400 | evntTypeCd **NULL** |
| 9106 | 재마킹 차단 412 | data_stts_cd **COMPLETED** |
| 9107 / 9112 / 9113 | 길이 미상(VDO_LEN_SEC NULL + 파일 부재) | AUTO backstop·MANUAL 상한 skip·프로브 미트리거 |
| 9108 / 9114 | 검수소유 작업상태(APPROVED) → 배치 미트리거 경로 | 활성중복 409 / fps 29.97 |
| 9109 | 동시 5요청 유니크 경합 | — |
| 9111 | AUTO intervalFrames 상한 부재 실증(1200초) | — |
| 9114 | `ls_data_meta(video.fps='29.97')` 주입 | 분수 fps 실계산 |

---

## 판정 표

| ID | 판정 | 근거 확인 | 상세 |
|---|:--:|---|---|
| TC-MARK-01 | PASS | [실동작] | `POST /v1/videos/9102/markings {"mode":"AUTO","intervalFrames":300}` → **201**, `marks=[{0,"00:00"},{300,"00:10"},{600,"00:20"}]`, `status:"PENDING"`, `batchTriggered:true`. 로그 `[Marking] created rawSn=9102 mode=AUTO markingSn=13` → `[MarkingBatchBridge] handling marking completed rawSn=9102` → `enqueued` (MarkingCompletedEvent 발행·AFTER_COMMIT 수신 확인). `MarkingService.java:105-143,:167-241` 일치 |
| TC-MARK-02 | PASS | [실동작] | `9101 {"mode":"MANUAL","marks":[{"frameIndex":1}]}` → 201 markingSn=8, 응답 marks 직렬화 왕복 정상, DB `LS_MARKING.FPS=30`(pin). `MarkingService.java:203-209` |
| TC-MARK-03 | PASS | [실동작] | `mode:""` → **400** `"mode: mode 는 필수입니다."` (`MarkingRequest.java:20` @NotBlank 발화) |
| TC-MARK-04 | PASS | [실동작] | `mode:"XYZ"` → **400** `INVALID_INPUT "mode 는 AUTO 또는 MANUAL 이어야 합니다."` (`MarkingService.java:210-212`) |
| TC-MARK-05 | PASS | [실동작] | AUTO + intervalFrames 누락 → 400 `"자동 모드에서 intervalFrames 는 1 이상이어야 합니다."` (`:196-198`) |
| TC-MARK-06 | PASS | [실동작] | intervalFrames=0 / -1 둘 다 동일 400. ※상한은 미검증 — 부가 발견 **C-ISSUE-02** |
| TC-MARK-07 | PASS | [실동작] | MANUAL + `marks:[]` 및 marks 키 누락 둘 다 400 `"수동 모드에서 marks 는 필수입니다."` (`:204-206`) |
| TC-MARK-08 | PASS | [실동작] | 9107(VDO_LEN_SEC NULL + meta 없음 + 파일 부재로 ffprobe 실패) AUTO → **400** `"영상 길이를 확인할 수 없어 자동 마킹을 생성할 수 없습니다."` (`:269-273` backstop 도달 확인) |
| TC-MARK-09 | PASS | [정적] | `generateAutoMarks` 가 `durationSec == null \|\| <= 0` 을 동일 분기로 거부(`:269-273`). 실경로에서는 `VideoDurationResolver.msToSecondsOrNull` 이 1초 미만을 null 로 접어 0/음수가 도달하지 않으므로 API 재현 불가 = 순수 backstop. 단위테스트 `MarkingServiceTest#자동마킹_주입_durationSec_0이하면_INVALID_INPUT` 커버 |
| TC-MARK-10 | PASS | [실동작] | ①기존 rawSn=4(dur 30s·fps 10·interval 10): `MARK_CN` 에 `290` 존재, `300` **부재**(totalFrames=300 미포함). ②9111(dur 1200s·fps 30·interval 1): marks **36,000**건, last `frameIndex=35999`(totalFrames=36000 미포함). `:276-281` off-by-one 정상 |
| TC-MARK-11 | PARTIAL | [실동작] | **동작은 정확**: 9114 에 `video.fps='29.97'` 주입 후 AUTO(interval 300, dur 30s) → totalFrames=round(30×29.97)=**899**, marks `0/300/600`(900 미포함), timestamp `00:00/00:10/00:20`(300÷29.97=10.01→절단). 그러나 **29.97 직접 계산 자동테스트는 여전히 부재**(테스트는 30 폴백·25fps·60fps만) → 1차 지적(B-ISSUE-27) 미해소 이월 → **C-ISSUE-01** |
| TC-MARK-12 | PASS | [실동작] | 9101/9102 는 `video.fps` 메타 없음 → `LS_MARKING.FPS=30` 저장, marks 타임스탬프도 30fps 기준(`frameIndex 300→"00:10"`). `VideoFpsResolver.DEFAULT_FPS=30.0` (`MarkingService.java:191`) |
| TC-MARK-13 | PASS | [실동작] | DB 실측 `ls_marking.fps`: 9101=30, 9108=30, 9114=**29.97**, 9102=30 — AUTO/MANUAL 모두 pin 저장(`LsMarking.java:102,:162,:213`). 추출단계 재조회 불필요 |
| TC-MARK-14 | PASS | [실동작] | Authorization 헤더 없음 → **401** `UNAUTHORIZED "인증이 필요합니다."`; 변조 토큰(`Bearer xx.yy.zz`)도 401. 영상 존재확인 이전 거부 |
| TC-MARK-15 | PASS | [실동작] | WORKER(2001) → 미배정 9102 = **403**, **미존재 999999 도 403**(NOT_FOUND 아님 → 존재 은닉 유지). `MarkingGuards.java:56-64` |
| TC-MARK-16 | PASS | [실동작] | REVIEWER 는 배정 없는 9101/9102/9107/9108/9109/9112/9114 전부 통과해 마킹 생성. `MarkingGuards.java:56-58` |
| TC-MARK-17 | PASS | [실동작] | REVIEWER + rawSn=999999 → **404** `"영상을 찾을 수 없습니다."` (`MarkingGuards.java:80-82`) |
| TC-MARK-18 | PASS | [실동작] | 9104(de_ident_yn='N') → **412** `PRECONDITION_FAILED "비식별이 완료된 영상에서만 마킹할 수 있습니다."` (`:83-86`) |
| TC-MARK-19 | PASS | [실동작] | 9106(DATA_STTS_CD=COMPLETED) → **412** `"이미 처리된 영상은 재마킹할 수 없습니다."` (`:87-90`). 배치 PROCESSING 영상도 동일 확인(9101 배치 진행 중 재요청 412). ※`FAILED` 단계도 동일 차단됨 → 부가 발견 **C-ISSUE-03** |
| TC-MARK-20 | PASS | [실동작] | 9105(EVNT_TYPE_CD NULL) → **400** `INVALID_INPUT "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다."` (`:91-95`) |
| TC-MARK-21 | PASS | [실동작] | 9113(길이 미상 → AUTO 라면 반드시 ffprobe 필요)에 **미배정 WORKER** AUTO 요청 → **403**. 동 시각 backend 로그에 `BrampVideoProbe` 항목 **0건**(직전 프로브 로그는 03:15:36, 요청은 그 이후) → 프로브 미트리거 확증. `MarkingService.java:110-126` |
| TC-MARK-22 | PASS | [정적] | `MarkingPrecheckReader.java:44` `@Transactional(readOnly=true, propagation=REQUIRES_NEW)` 확인. 별도 빈으로 분리해 self-invocation 프록시 우회 회피(:23-24 Javadoc) |
| TC-MARK-23 | PASS | [정적] | persist 트랜잭션이 `MarkingGuards.requireAssignedOrReviewer` → `requirePreconditions` → `requireNoActiveMarking` 을 **precheck 와 동일 헬퍼·동일 순서**로 재호출(`MarkingService.java:170,:174,:178`). 사전확인은 `MarkingPrecheckReader.precheck:46-49` — 두 지점 코드 동형 |
| TC-MARK-24 | PASS | [실동작] | 요청 DTO 에 eventName 필드 자체가 없고(`MarkingRequest.java:19-33`), 응답 `eventName:"INTRUSION"` = `ls_data_raw.evnt_type_cd`. `MarkingService.java:182` |
| TC-MARK-25 | PASS | [정적] | `LsMarking.java:142-153` — rawSn null / eventName blank / intervalFrames<=0 / videoPath blank 각각 `IllegalArgumentException`. `LsMarkingEntityTest` 4건 커버 |
| TC-MARK-26 | PASS | [정적] | `LsMarking.java:196-204` — rawSn / eventName / videoPath 3종 검증(intervalFrames 없음이 정상). `LsMarkingEntityTest#createManual_*` 커버 |
| TC-MARK-27 | PASS | [정적]+[실동작] | `LsMarking.java:231-238` PENDING 에서만 전이. 파이프라인 실구동(rawSn=4)에서 마킹이 PENDING→VLM_REQUESTED→VLM_COMPLETED 로 진행됨을 `_raw/pipeline-drive.md` 4단계·DB(`stts_cd=VLM_COMPLETED`)로 확인 |
| TC-MARK-28 | PASS | [정적] | `:232-234` — 비-PENDING 이면 즉시 `false` 반환(상태 유지). `LsMarkingEntityTest#PENDING에서만_VLM_REQUESTED로_전이_그외_no_op` |
| TC-MARK-29 | PASS | [정적] | `MarkingBatchBridge.java:81-82`(SKIP_BATCH_STAGES=PROCESSING/COMPLETED) + `:109-114` 스킵 + `REASON_STAGE_ALREADY_RUN` 기록. 실동작 미도달 — 마킹 API 가드(TC-MARK-19)가 선행 412 로 막아 브리지까지 못 감(=이중 방어). `MarkingBatchBridgeTest` 2건 커버 |
| TC-MARK-30 | PASS | [정적] | `:118-123` deid≠'Y' → 스킵 + `REASON_NOT_DEIDENTIFIED`. 실동작 미도달(마킹 가드 412 선행). `MarkingBatchBridgeTest#비식별_미완료영상은_배치트리거_안함`, `#비식별_실패영상은…(deIdntfYn=F)` 커버 |
| TC-MARK-31 | PASS | [실동작] | 9101/9102/9107/9109 는 마킹 전 `ls_raw_data_status` 행이 **없었고**, 마킹 후 4건 모두 행이 생성됨(현재 FAILED — 배치 실패 후 값). tx1 claim false → tx2 `tryCreateBatchQueuedRow` 경로 실증(`:136-146`) |
| TC-MARK-32 | PASS | [정적] | `:138-144` `DataIntegrityViolationException` catch → `claimed=false` 조용히 스킵. 실동작 재현 불가 — 동시 5요청은 **마킹 유니크(V142)** 에서 먼저 4건이 잘려 브리지 자체가 1회만 진입. `MarkingBatchBridgeTest#동시_row생성경합…정확히1건만_트리거(CWE-362)` 커버 |
| TC-MARK-33 | PASS | [정적] | `:98-103` rawOpt empty → 스킵 + `REASON_VIDEO_NOT_FOUND`. `LS_MARKING.RAW_SN` FK(`fk_ls_marking_raw`) 때문에 실동작 재현 불가. `MarkingBatchBridgeTest#영상_행_미존재시_스킵` 커버 |
| TC-MARK-34 | PASS | [정적] | `sanitize()` (`:166-168`)가 DB 유래 두 값에 적용됨 — `:111`(dataSttsCd), `:120`(deIdntfYn). 그 외 로그 인자는 Long rawSn 뿐. 실주입 재현 불가(`de_ident_yn` char(1), `data_stts_cd` varchar(20) + 정확일치 비교) |
| TC-MARK-35 | PASS | [실동작] | PORTAL_USER 토큰(channel=PORTAL) → **403** `"권한이 없습니다."` (`MarkingController.java:51` `hasAnyRole('REVIEWER','WORKER')`) |
| TC-MARK-36 | PASS | [실동작] | 9108/9112 에 활성(PENDING) 마킹 보유 상태에서 재요청 → **409** `CONFLICT "이미 진행 중인 마킹이 있습니다…"`. 로그에 ffprobe·INSERT 흔적 없음(1선 거부). `MarkingGuards.java:109-114` |
| TC-MARK-37 | PASS | [실동작] | 9109 에 **동시 5요청** → `201 ×1` / `409 ×4`. backend 로그: `Detail: Key (raw_sn)=(9109) already exists.` ×4 + `[Marking] concurrent duplicate rejected rawSn=9109` ×4 (`MarkingService.java:226-233`). DB `ls_marking` 에 9109 행 **1건**. PG tx abort 로 동일 tx 재시도 없음 확인 |
| TC-MARK-38 | PASS | [실동작] | 9112: ①PENDING 마킹 존재 시 재마킹 409 → ②`stts_cd='VLM_COMPLETED'` 로 종결시킨 뒤 재마킹 **201**(markingSn=20). `LsMarking.ACTIVE_STATUSES = ('PENDING','VLM_REQUESTED')`(`LsMarking.java:65`) ↔ `V142__add_ls_marking_active_unique.sql:44` `WHERE STTS_CD IN ('PENDING','VLM_REQUESTED')` **문자 일치** 확인 |
| TC-MARK-39 | PASS | [실동작] | 9112(길이 미상 → AUTO 면 ffprobe 필수) + 활성 마킹 보유 상태에서 `mode=AUTO` → **409**, 동 시각 로그에 `BrampVideoProbe` 0건 → precheck(`MarkingPrecheckReader.java:49`) 단계에서 잘려 프로브 미트리거 |
| TC-MARK-40 | PASS | [실동작] | `frameIndex:-5` → **400** `"marks[0].frameIndex: frameIndex 는 0 이상이어야 합니다."`, `frameIndex` 키 누락 → 400 `"marks[0].frameIndex: frameIndex 는 필수입니다."` → `MarkingRequest.java:30` `@Valid` 전파 + `MarkItem.java:23-25` 발화 확인(구 결함 해소 유지) |
| TC-MARK-41 | PASS | [실동작] | `[{0},{0}]` → 400 `"중복된 마킹 시점입니다: frameIndex=0"` (`MarkingService.java:312-315`) |
| TC-MARK-42 | PASS | [실동작] | 9101(dur 30·fps 30) `frameIndex:999999999` → 400 `"영상 길이를 벗어난 마킹 시점입니다: frameIndex=999999999 (허용 상한 **930** 프레임 미만)"`. 930 = round(30×30)+ceil(30) — `manualFrameIndexLimit(:350-352)` 계산 일치. 경계 실측: `frameIndex=930` 도 거부(**배타** 상한 확인) |
| TC-MARK-43 | PASS | [실동작] | 9107(길이 미상) MANUAL `frameIndex:999999` → **201** + WARN 로그 `[Marking] duration unknown — manual mark upper-bound check skipped rawSn=9107 marks=1`. 9112(길이 미상)에서 중복 `[{5},{5}]` → 400, 음수 `-3` → 400 → **상한만 skip, 중복·하한은 유지** 확인(`:317-321`) |
| TC-MARK-44 | PASS | [실동작] | `"99:99"` / `"-1:00"` → 400 `"timestamp 는 mm:ss 또는 mm:ss:ff 형식이어야 합니다."`; `timestamp` 미지정(null) → **201**(허용). `MarkItem.java:27-29` |
| TC-MARK-45 | PASS | [실동작] | marks 20,001건 → **400** `"marks: 한 번에 처리 가능한 마킹 수 초과 (최대 20000)"`; 20,000건은 @Size 통과(이후 상한 검증에서 별개 400) → 경계 정확(`MarkingRequest.java:31`) |
| TC-MARK-46 | PASS | [실동작] | 9108(작업상태 APPROVED) AUTO → **201** + `"batchTriggered":false, "batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 이미 배치가 큐잉되어 배치를 시작하지 않았습니다."`. 로그 `[MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=9108 — skipping`. 정상 경로(9102)는 `batchTriggered:true, batchSkipReason:null` → 스레드로컬 `begin()` 초기화(`MarkingService.java:108`) 오염 없음 재확인. `REVIEW_OWNED_STATUSES`= PENDING/IN_REVIEW/APPROVED/REJECTED(`BatchTransitionService.java:77-81`) |

### 집계

| 판정 | 건수 |
|---|--:|
| PASS | **45** |
| PARTIAL | **1** (TC-MARK-11) |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **46** |

- 실동작 판정 **34건** / 정적+테스트커버 판정 12건(TC-MARK-09·22·23·25·26·28·29·30·32·33·34, 27은 정적+실동작 혼합).
- **근거 `file:line` 드리프트 0건** — 46건의 근거 라인을 전수 대조했고 모두 현재 코드 위치와 일치(±2행 이내, 메서드 시그니처 기준).
- **self-fill 결함 0건** — C-1 은 외부 연동 경유 구간이 아니며, 마킹이 만드는 값(marks·fps·eventName)은 전부 DB 실측값(`VDO_LEN_SEC`/`video.fps` 메타/`EVNT_TYPE_CD`)에서 유도. fps 30 폴백은 명시적 문서화된 fail-safe 이고 pin 값으로 DB 에 기록되어 추적 가능.

---

## 이슈

### [C-ISSUE-01] TC-MARK-11 — 분수 fps(29.97) 자동 마킹 계산의 회귀 테스트 여전히 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: `generateAutoMarks` 는 `totalFrames = Math.round(durationSec × fps)` 로 분수 fps 를 지원해야 하고(29.97 은 NTSC 표준으로 실환경 최빈값), 이 계산이 **자동 테스트로 고정**되어야 마킹↔프레임추출 정렬(TOCTOU 불변식)이 회귀로 깨지지 않는다. 1차 검증(B-ISSUE-27)에서 동일 지적이 나왔다.
- **현재 동작(이슈 내용)**: 실동작은 정확하다 — `ls_data_meta(raw_sn=9114, meta_key='video.fps', meta_vl='29.97')` 주입 후
  `POST /v1/videos/9114/markings {"mode":"AUTO","intervalFrames":300}` → `marks=[{0,"00:00"},{300,"00:10"},{600,"00:20"}]`
  (totalFrames=round(30×29.97)=899 → 900 미포함), `LS_MARKING.FPS=29.97` pin.
  그러나 테스트 자산에는 분수 fps 케이스가 없다. `MarkingServiceTest` 의 fps 관련 `@DisplayName` 전수:
  `M3_자동마킹_fps미상_30폴백…` / `자동마킹_실fps25…` / `자동마킹_fps미상이면_30폴백으로_기존과_동일_무회귀` /
  `자동마킹_해석한실fps25가_마킹레코드에_pin된다` / `자동마킹_실fps60…` — **정수 fps 만**.
  `MarkingManualValidationTest` 도 25fps 만 사용. 즉 `Math.round` 를 `(int)` 절단으로 바꾸는 회귀(899→899 는 같지만 예: dur=10·fps=29.97 → round=300 vs 절단=299)를 잡아내는 테스트가 없다.
- **재현/확인 경로**: `grep -n "29.97\|29\\.97" backend/src/test/java/kr/co/cudo/authoring/marking/*.java` → 0건.
- **영향**: 기능 결함 아님(현재 동작 정상). 회귀 방어 공백 — `MarkingService.generateAutoMarks` 의 반올림 정책이 바뀌면 마킹 frameIndex 와 `FfmpegFrameExtractor` seekMillis 가 어긋나 **프레임 오정렬**(라벨 좌표가 다른 프레임에 붙음)이 무증상으로 발생한다.
- **수정 방향(제안)**: `MarkingServiceTest` 에 `자동마킹_실fps29_97_totalFrames_반올림_및_타임스탬프_정확계산` 케이스 1건 추가 — dur=10s·fps=29.97·interval=100 으로 totalFrames=300(round) 경계와 `frameIndex=200 → "00:06"`(200÷29.97=6.67→6) 절단을 함께 단언. 구현 변경 불필요.

### [C-ISSUE-02] TC-MARK-06 부가 발견 — AUTO `intervalFrames` 하한만 있고 상한/결과 개수 상한이 없어 MANUAL(20000) 과 방어가 비대칭 (이월 B-ISSUE-23 실증)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: MANUAL 경로는 `MarkingRequest.marks` 에 `@Size(max=20000)`(CWE-770 방어, `MarkingRequest.java:31`)를 걸어 과대 요청을 400 으로 자른다. AUTO 경로도 **서버가 생성하는 marks 개수**에 동등한 상한이 있어야 한다 — 그렇지 않으면 요청 바이트는 수십 바이트인데 서버가 수십 MB 를 만들어 응답·`LS_MARKING.MARK_CN`(TEXT) 에 적재한다.
- **현재 동작(이슈 내용)**: `MarkingRequest.intervalFrames` 에는 Bean Validation 이 **전혀 없고**(`MarkingRequest.java:23` — 순수 `Integer`), 서비스는 하한만 본다:
  ```java
  // MarkingService.java:196-198
  if (req.intervalFrames() == null || req.intervalFrames() <= 0) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "자동 모드에서 intervalFrames 는 1 이상이어야 합니다.");
  }
  ```
  `generateAutoMarks`(`:274-282`) 는 `totalFrames/intervalFrames` 개의 `MarkItem` 을 무제한 누적한다.
  실측: `raw_sn=9111`(VDO_LEN_SEC=1200, fps 폴백 30) + `intervalFrames=1` →
  `HTTP=201 bytes=1465323`, **marks 36,000건**(MANUAL 상한 20,000 의 1.8배), 응답 1.4MB.
  선형 외삽 시 2시간 영상(7200s·30fps)은 216,000건 ≒ 9MB / 1요청.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code} %{size_download}\n' -X POST \
    http://localhost:18081/api/v1/videos/9111/markings \
    -H "Authorization: Bearer $REVIEWER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"mode":"AUTO","intervalFrames":1}'      # → 201 1465323
  ```
- **영향**: 보안 — **CWE-770 Allocation of Resources Without Limits**(OWASP API4:2023 Unrestricted Resource Consumption). 인증된 WORKER/REVIEWER 1인이 저비용 요청 반복으로 힙·응답대역·`MARK_CN` TEXT 컬럼을 팽창시킬 수 있다. 부수적으로 후속 배치(프레임추출)가 마킹 수만큼 ffmpeg seek 을 수행하므로 GPU/디스크까지 증폭된다.
- **수정 방향(제안)**: `MarkingRequest.intervalFrames` 에 `@Min(1) @Max(...)` 를 붙이는 것만으로는 부족(작은 interval 이 문제) — `MarkingService.generateAutoMarks` 에서 산출 개수를 먼저 계산해 `MarkingRequest` 의 marks 상한 상수(20000)와 **같은 상수**로 400 거부하고, 상수를 `MarkingRequest`/서비스가 공유하도록 한 곳에 둔다. 1차 이월 이슈 **B-ISSUE-23** 과 동일 사안이므로 함께 처리 권장.

### [C-ISSUE-03] TC-MARK-19 부가 발견 — 배치가 `FAILED` 로 소진된 영상은 재마킹 API 가 영구 412 라 복구 동선이 없다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 배치 재시도(`authoring.batch.retry.max-attempts:3`)가 모두 실패해 `LS_DATA_RAW.DATA_STTS_CD='FAILED'` 로 고정된 영상은, 원인 해소 후 **작업자/검수자가 다시 마킹해 파이프라인을 재개**할 수 있어야 한다(마킹이 배치의 유일한 트리거이므로).
- **현재 동작(이슈 내용)**: 프리컨디션이 `MARKING_READY` **정확 일치**만 허용한다.
  ```java
  // MarkingGuards.java:87-90
  if (!LsDataRaw.DATA_STTS_MARKING_READY.equals(raw.getDataSttsCd())) {
      throw new CustomException(ErrorCode.PRECONDITION_FAILED, "이미 처리된 영상은 재마킹할 수 없습니다.");
  }
  ```
  실측: 9101/9102/9107/9109 는 마킹 후 배치가 실패해 `data_stts_cd='FAILED'` 가 됐고, 이후 어떤 마킹 요청도
  **412 `"이미 처리된 영상은 재마킹할 수 없습니다."`** 로 거부된다(메시지도 사실과 불일치 — "처리됨"이 아니라 "실패함").
  재시도 소진 경로는 `BatchRetryQueue.java:78-82`(`attempt > maxAttempts` → 큐 등록 거부 + `EXHAUSTED`) / `BatchRetryQuartzJob.java:21,:79`("FAILED 고정")로 확인. 한편 `MarkingBatchBridge.SKIP_STATUSES` 는 **작업 상태** `FAILED` 를 재마킹 허용 대상으로 남겨 두었으므로(`:43-45` Javadoc "FAILED(실패 후 재마킹)"), 브리지의 설계 의도와 마킹 가드의 실제 동작이 **서로 어긋난다**.
- **재현/확인 경로**:
  ```sql
  -- 배치가 실패해 FAILED 로 굳은 영상 확인
  SELECT raw_sn, data_stts_cd FROM ls_data_raw WHERE data_stts_cd='FAILED';
  ```
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/videos/9102/markings \
    -H "Authorization: Bearer $REVIEWER_TOKEN" -H 'Content-Type: application/json' \
    -d '{"mode":"AUTO","intervalFrames":10}'   # → 412
  ```
- **영향**: 기능/운영 — 외부 원인(ai-server 가중치 부재, ffmpeg 실패 등)으로 배치가 소진되면 해당 영상은 **DB 를 직접 손대지 않는 한 영구 정체**한다. 이번 회차 파이프라인 구동에서 YOLO 가중치 부재로 배치가 실패하는 조건이 실제로 존재했으므로(`_raw/pipeline-drive.md` §특이사항 2) 가상 시나리오가 아니다.
- **수정 방향(제안)**: ①`MarkingGuards.requirePreconditions` 의 허용 집합을 `{MARKING_READY, FAILED}` 로 넓히고(정책 확정 필요), ②거부 메시지를 상태별로 분기(`PROCESSING/COMPLETED` = "이미 처리됨" / 그 외 = 실제 상태 표기)해 오인을 없앤다. ③또는 재구동 전용 관리 API 를 두고 마킹 가드는 그대로 둔다. **어느 쪽이든 정책 결정 사항이므로 구현 전 확정 필요** — 본 검증에서는 결정하지 않는다.

### [C-ISSUE-04] TC-MARK-01/02 부가 발견 — 마킹 레코드·응답의 `videoPath` 가 비식별본이 아닌 **원본** NAS 경로다
- **심각도**: LOW
- **기대 동작(기대효과)**: `CLAUDE.md` 상 마킹은 **비식별 영상**을 대상으로 하고(스트리밍도 항상 비식별본만 서빙), 마킹 레코드의 "영상경로"는 그 작업 대상과 일치해야 의미가 맞는다. 또한 API 응답에 서버 내부 스토리지 절대/상대 경로를 노출하지 않는 편이 낫다.
- **현재 동작(이슈 내용)**: `MarkingService.java:217-218` 이 `raw.getRawFilePathNm()`(= `LS_DATA_RAW.RAW_FILE_PATH_NM`, **원본** 경로)를 그대로 `LsMarking.videoFilePathNm` 에 저장하고, `MarkingResponse.java:54` 가 이를 `videoPath` 로 응답한다. 실측 응답:
  `"videoPath":"./storage/raw/seed/clip-9103.mp4"` (원본 경로).
  다만 **PII 유출은 아니다** — 하류 소비자를 전수 확인한 결과 `getVideoFilePathNm()` 참조처는 `MarkingResponse` 한 곳뿐이고, 외부로 나가는 VLM 요청은 별도로 비식별 경로를 도출한다(`VlmTimeseriesStep.java:309, :390-399` — `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`). 즉 **저장·응답 필드의 의미 불일치 + 내부 경로 노출**에 한정된 문제다.
- **재현/확인 경로**: 임의의 MARKING_READY 영상에 마킹 생성 후 응답 `data.videoPath` 확인, 또는
  `SELECT video_file_path_nm FROM ls_marking;` 를 `ls_deident_proc_log.de_idntf_file_path_nm` 과 대조.
- **영향**: 보안(정보노출, CWE-200 경미) + 데이터 의미 정합. 향후 이 컬럼을 "마킹 대상 영상"으로 신뢰해 소비하는 코드가 추가되면 **원본 영상을 대상으로 후속 처리**하는 실수로 번질 수 있다(현재는 미발생).
- **수정 방향(제안)**: ①`LsMarking.videoFilePathNm` 에 비식별 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)를 저장하도록 바꾸거나, ②컬럼/필드명을 `originalVideoPath` 로 명확히 하고 `MarkingResponse` 에서는 경로 대신 rawSn 만 노출한다. 하류 소비자가 1곳뿐이라 변경 반경은 작다.

---

## 비고 / 판정 기준 적용 메모

- `UNCERTAINTIES.md` **★1~★3** 확정 정책은 C-1 에 직접 걸리는 케이스가 없어 재보고 항목 없음.
- 1차 이월 이슈 중 C-1 관련: **B-ISSUE-23**(AUTO intervalFrames 상한 미검증) → **미해소 확인**, 본 회차에서 실측 수치로 재기록(C-ISSUE-02). **B-ISSUE-27**(29.97 테스트 부재) → **미해소 확인**(C-ISSUE-01).
- **환경 제약으로 실동작 재현이 원천 불가한 케이스**(BLOCKED 로 올리지 않고 [정적]+테스트커버로 판정한 근거):
  TC-MARK-29/30(마킹 API 가드가 브리지보다 먼저 발화 — 브리지 가드는 정의상 TOCTOU 이중 방어),
  TC-MARK-33(FK 제약으로 raw 미존재 마킹 이벤트 생성 불가),
  TC-MARK-34(주입 대상 컬럼이 char(1)/varchar(20) + 정확일치 비교),
  TC-MARK-09(리졸버가 0/음수를 null 로 접어 backstop 만 도달),
  TC-MARK-32(마킹 유니크가 먼저 잘라 브리지 경합 미도달).
- 본 검증에서 만든 `raw_sn=9101~9114` 행은 **삭제하지 않고 유지**했다(FK CASCADE 로 병렬 에이전트 관측 데이터에 영향 줄 위험 회피). 후속 회차에서 정리 필요 시 `ls_data_raw WHERE raw_sn BETWEEN 9101 AND 9114` 삭제로 일괄 제거 가능.
- 코드·설정·다른 결과 파일 **무수정**, 컨테이너 **무재기동**, 빌드/테스트 **미실행** 준수.
