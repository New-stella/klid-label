# C-1. TC-MARK — 마킹(자동/수동) 46건 검증 결과

> 담당: C 클러스터 part1 · 검증일 2026-08-01 · 회차 1차
> 대상: `docs/test-cases/C-marking-labeling.md` → `## C-1. TC-MARK — 마킹 (자동/수동)` (TC-MARK-01~46, 폐기 0건)

## 검증 환경 (실측)

| 항목 | 값 |
|---|---|
| backend | `localhost:18081` (context-path `/api`) — `klid-backend` healthy, `/actuator/health` UP |
| frontend / ai-server / mock-server | `13000` / `19300` / `9400` — 전부 healthy (`/health` ok) |
| DB | `klid-postgres`, DB `klid_system`, 스키마 `public` |
| Flyway | **V158 까지 적용 성공** (`flyway_schema_history`) — 이번 스택은 pipeline-drive.md 가 기록한 "V146 구 빌드" 환경 갭이 **해소된 최신 빌드**다 |
| 소스 정합 | 스택 빌드 소스 `klid-label@56d30478` 의 `.../authoring/marking/**` 와 판정 기준 워크트리 `qa-0801@b2b44f0e` 의 동 디렉터리 **`diff -rq` 차이 0건** → 코드 읽기와 실동작 대상이 동일함을 확인 |
| V142 부분 유니크 | `\d ls_marking` → `uk_ls_marking_raw_actvtn UNIQUE, btree (raw_sn) WHERE stts_cd = ANY ('PENDING','VLM_REQUESTED')` **실재 확인** |
| 자동테스트 baseline | `_raw/test-baseline.md` — BE 4,755 tests / 실패 0 / skip 5 (마킹 관련 실패 0건) |

### 검증용 데이터 (신규 INSERT — 기존 rawSn 26/28/30/31/32 등 무변경)

`ls_data_raw` 신규 `raw_sn=41~55, 58~71` (`vms_clip_id` 전부 `QA0801-C1-*` 프리픽스), `ls_marking` 신규 `marking_sn=40~48,53~66`,
`ls_task_assignment` 신규 `37/38`(raw49 LABELER+REVIEWER) · `39`(raw65 REVIEWER-타입만), `ls_raw_data_status` 신규 `48/67`(APPROVED), `ls_data_meta` 신규 `video.fps` 5건(raw58~62).
**기존 행 UPDATE/DELETE 0건, 코드·설정·테스트 파일 수정 0건, 빌드/테스트 실행 0건.**

---

## 판정 요약

| 판정 | 건수 |
|---|--:|
| PASS | 44 |
| PARTIAL | 2 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **46** |

근거 확인 내역: **[실동작] 33건 / [정적] 13건** (정적 13건은 전부 기존 자동테스트가 커버하며 baseline 통과 확인)

---

## 케이스별 결과

| ID | 판정 | 근거 확인 | 실측/근거 |
|---|:--:|---|---|
| TC-MARK-01 | PASS | [실동작] | `POST /v1/videos/42/markings {"mode":"AUTO","intervalFrames":30}` → **201**, `markingSn=41`, marks 10건, `status=PENDING`, `batchTriggered=true`. 이벤트 발행 근거: 로그 `[MarkingBatchBridge] handling marking completed rawSn=42` (AFTER_COMMIT 리스너 발화 = `MarkingCompletedEvent` 발행). DB `ls_marking(41, raw_sn=42, stts_cd='PENDING')`. 코드 `MarkingService.java:105-143`(오케스트레이션)·`:167-241`(persist) 근거 일치 |
| TC-MARK-02 | PASS | [실동작] | `MANUAL marks=[{329}]` → 201 `markingSn=40`, DB `mark_cn=[{"frameIndex":329,"timestamp":null}]` 직렬화 저장, `fps=30` pin. `MarkingService.java:203-209` 일치 |
| TC-MARK-03 | PASS | [실동작] | `mode:""` → **400** `"mode: mode 는 필수입니다."` (`MarkingRequest.java:20` `@NotBlank`) |
| TC-MARK-04 | PASS | [실동작] | `mode:"XYZ"` → **400** `"mode 는 AUTO 또는 MANUAL 이어야 합니다."`. 반증 추가: `"auto"`(소문자)·`" AUTO "`(공백패딩) 도 동일 400 — 대소문자/트림 우회 없음 (`:210-212`) |
| TC-MARK-05 | PASS | [실동작] | `AUTO` + `intervalFrames` 미전송 → **400** `"자동 모드에서 intervalFrames 는 1 이상이어야 합니다."` (`:196-198`) |
| TC-MARK-06 | PASS | [실동작] | `intervalFrames=0` → 400, `=-1` → 400. 상한: `=2147483647` → **201**(marks 1건, B-ISSUE-23 이월 = 현재 동작 고정), `=2147483648` → 400(정수 파싱). ⚠ 파생 결함 **C-ISSUE-02**(검증 순서) 참조 |
| TC-MARK-07 | PASS | [실동작] | `MANUAL marks=[]` → 400, `marks` 미전송(null) → 400, 둘 다 `"수동 모드에서 marks 는 필수입니다."` (`:204-206`) |
| TC-MARK-08 | PASS | [실동작] | raw54(`VDO_LEN_SEC=NULL` + `video.duration_ms` 메타 없음 + 파일 부재) AUTO → **400** `"영상 길이를 확인할 수 없어 자동 마킹을 생성할 수 없습니다."`. 3단 폴백 전부 실패 확인: 로그 `[Video][Probe] ffprobe empty output path=9bc9c203`(경로 해시 마스킹 = CWE-209 방어 동작) (`:269-273`) |
| TC-MARK-09 | PASS | [실동작] | raw70(`VDO_LEN_SEC=0`)·raw71(`=-5`) AUTO → **둘 다 400**. 단, `VideoDurationResolver.java:82`(및 `:116`)가 `>0` 만 채택하므로 ≤0 은 resolver 에서 null 로 떨어지고 `generateAutoMarks` 의 `<=0` 백스톱은 방어적 이중화. 단위테스트 `MarkingServiceTest#자동마킹_주입_durationSec_0이하면_INVALID_INPUT` 이 백스톱 직접 커버 |
| TC-MARK-10 | PASS | [실동작] | dur=10·fps=30·interval=30 → `totalFrames=300`, 응답 marks **10건 = frameIndex 0,30,…,270**. **300 미포함**(끝 경계 프레임 배제) 확인 (`:276-281`) |
| TC-MARK-11 | PARTIAL | [실동작] | `ls_data_meta(raw58,'video.fps','29.97')` 주입 후 AUTO interval=30 → `totalFrames=Math.round(10×29.97)=300` → marks 10건(0~270), timestamp `00:00`~`00:09`(`frameIndex/fps` 정수초 절단), DB `ls_marking.fps=29.97` pin. **실동작은 기대결과와 정확히 일치.** 다만 케이스가 기대결과에 명시한 "29.97 직접 계산 테스트 부재"는 여전히 사실 — `grep -rn "29.97" backend/src/test` 결과 `VideoFpsResolverTest`(파싱)·`VideoMetaServiceTest`(적재)만 존재하고 `MarkingService` 의 marks 산출 경로 테스트는 25/30/60fps 뿐 → **C-ISSUE-05** |
| TC-MARK-12 | PASS | [실동작] | 반증 3종 전부 30 폴백 확인 — 메타 없음(raw42) / `video.fps='0'`(raw59) / `'not-a-number'`(raw60) / `'-30'`(raw62) → `ls_marking.fps=30`, marks 10건 동일. 대조군 `'25'`(raw61) → `fps=25`, `totalFrames=250` → marks 9건(0~240) (`:191`, `VideoFpsResolver:48-74`) |
| TC-MARK-13 | PASS | [실동작] | DB 실측: AUTO(raw58)·MANUAL(raw41) 모두 `LS_MARKING.FPS` 에 해석값 저장(29.97 / 30). 추출 단계가 재조회하지 않는 구조는 `LsMarking.java:102`(필드)·`:162`/`:213`(대입) 정적 확인 |
| TC-MARK-14 | PASS | [실동작] | Authorization 헤더 없이 POST → **401** `"인증이 필요합니다."`. 존재 확인(404) 이전에 거부 (`MarkingGuards.java:53-55` + SecurityConfig) |
| TC-MARK-15 | PASS | [실동작] | 미배정 WORKER(sub=2001) → raw41 **403**, **미존재 raw999999 도 403**(404 아님 = 존재 은닉 계약 유지). 반증 추가: `task_type_cd='REVIEWER'` 로만 배정된 WORKER(raw65) → **403**(LABELER 배정만 인정, `:60-61`) |
| TC-MARK-16 | PASS | [실동작] | REVIEWER(sub=1001) 가 **배정 이력 없는** raw41~48/58~71 전부에 마킹 성공/정상 가드 통과 (`:56-58`) |
| TC-MARK-17 | PASS | [실동작] | REVIEWER + raw999999 → **404** `"영상을 찾을 수 없습니다."` (`:80-82`) |
| TC-MARK-18 | PASS | [실동작] | `de_ident_yn='N'`(raw50) → **412**, `='F'`(raw51, 신고/실패) → **412**. 반증 추가: `='y'` 소문자(raw68) → **412**(대소문자 우회 없음, `DEIDENTIFIED.equals` 정확일치) (`:83-86`) |
| TC-MARK-19 | PASS | [실동작] | `DATA_STTS_CD='COMPLETED'`(raw53) → 412, `='FAILED'`(raw41) → 412, `='PENDING'`(raw32) → 412(비식별 가드 선발화). 메시지 `"이미 처리된 영상은 재마킹할 수 없습니다."` (`:87-90`). ※"반려 후 재마킹" 동선 부재는 확정 정책(결함 아님) |
| TC-MARK-20 | PASS | [실동작] | `EVNT_TYPE_CD=NULL`(raw52) → **400** `"이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다."` (`:91-95`) |
| TC-MARK-21 | PASS | [실동작] | 미배정 WORKER 가 **길이 미상 영상(raw55, ffprobe 필요 조건)** 에 AUTO 요청 → 403, 직후 `docker logs klid-backend --since 20s | grep -icE "ffprobe|Video]\[Probe"` = **0** → 프로브 미트리거 확증. 대조군: 인가 통과 상태의 raw54 AUTO 는 같은 조건에서 프로브 로그 발생 (`MarkingService.java:110-126`) |
| TC-MARK-22 | PASS | [정적] | `MarkingPrecheckReader.java:44` `@Transactional(readOnly=true, propagation=REQUIRES_NEW)` + 별도 빈 분리(자기호출 프록시 우회 회피) 확인. 런타임 커넥션 반납 관측은 도구 부재로 미수행이나, TC-MARK-21 의 "프로브 시점 커넥션 미보유" 전제인 비트랜잭션 오케스트레이션(`create` 에 `@Transactional` 없음)은 정적 확인 |
| TC-MARK-23 | PASS | [정적] | persist 트랜잭션이 `MarkingGuards.requireAssignedOrReviewer`(`:170`) → `requirePreconditions`(`:174`) → `requireNoActiveMarking`(`:178`) 를 **precheck 와 동일 헬퍼·동일 순서**로 재강제. 두 호출지점이 같은 `MarkingGuards` 정적 메서드를 쓰므로 규칙 드리프트 구조적 불가. 테스트 `MarkingServiceTest`(persist 경로 단독 호출) 전건이 동일 상태코드 검증 |
| TC-MARK-24 | PASS | [실동작] | 요청 바디에 `eventName` 필드 자체가 없음(`MarkingRequest` 3필드). 응답 `eventName:"EV02000201"` = `LS_DATA_RAW.EVNT_TYPE_CD` 값과 일치 (`:182`) |
| TC-MARK-25 | PASS | [정적] | `LsMarking.java:140-153` rawSn null / eventName blank / intervalFrames≤0 / videoPath blank → `IllegalArgumentException`. 테스트 `LsMarkingEntityTest#createAuto_intervalFrames_0이하_예외`·`#createAuto_rawSn_null_예외` |
| TC-MARK-26 | PASS | [정적] | `LsMarking.java:194-205` 동일 3종 검증. 테스트 `LsMarkingEntityTest#createManual_eventName_null_예외`·`#createManual_videoPath_blank_예외` |
| TC-MARK-27 | PASS | [정적] | `LsMarking.java:231-240` — `STATUS_PENDING` 일 때만 `VLM_REQUESTED` 전이 후 `true`. 테스트 `LsMarkingEntityTest#markVlmRequested_상태전이`. 실동작 방증: `pipeline-drive.md` 4단계에서 rawSn=26/30 마킹이 `PENDING→VLM_REQUESTED→VLM_COMPLETED` 로 실제 전이 관측됨(본 세션의 합성 데이터는 비식별 산출물이 없어 배치가 프레임추출에서 FAILED, VLM 단계 미도달) |
| TC-MARK-28 | PASS | [정적] | 동 `:231-235` — 비-PENDING(`VLM_COMPLETED` 포함) 은 `false` 반환 no-op(durable 역행 차단). 테스트 `LsMarkingEntityTest#PENDING에서만_VLM_REQUESTED로_전이_그외_no_op` |
| TC-MARK-29 | PASS | [정적] | `MarkingBatchBridge.java:81-82` `SKIP_BATCH_STAGES={PROCESSING,COMPLETED}` → `:109-114` 스킵 + `REASON_STAGE_ALREADY_RUN`. API 경유로는 `requirePreconditions` 가 MARKING_READY 를 먼저 요구해 도달 불가(=정상적 이중 방어). 테스트 `MarkingBatchBridgeTest#배치_COMPLETED_영상에_마킹이벤트_재발생시_배치_재트리거되지_않고…`·`#배치_PROCESSING_중…` |
| TC-MARK-30 | PASS | [정적] | `:118-124` `deIdntfYn≠'Y'` → 스킵 + `REASON_NOT_DEIDENTIFIED`. 테스트 `MarkingBatchBridgeTest#비식별_미완료영상은_배치트리거_안함`·`#비식별_실패영상은_배치트리거_안함`·`#비식별_미완료_스킵도_사유가_리포트된다` |
| TC-MARK-31 | PASS | [실동작] | raw63 은 `ls_raw_data_status` 행 **0건**(미배정) 상태로 REVIEWER 직접 마킹 → 201 `batchTriggered=true`, 로그 `[MarkingBatchBridge] enqueued rawSn=63`, 사후 `ls_raw_data_status(63)` **신규 생성 확인** (tx1 claim false → tx2 `tryCreateBatchQueuedRow`) (`:136-146`) |
| TC-MARK-32 | PASS | [정적] | `:136-146` tx2 의 `DataIntegrityViolationException` 을 AFTER_COMMIT(활성 tx 없음) 컨텍스트에서 catch → `claimed=false` 로 조용히 스킵. 테스트 `MarkingBatchBridgeTest#동시_row생성경합_tx2가_DataIntegrityViolationException_던지면_잡아서_스킵`·`#동일_rawSn_마킹이벤트_2회_동시발생시_tx1이_1건만_true` |
| TC-MARK-33 | PASS | [정적] | `:98-104` `rawOpt.isEmpty()` → 스킵 + `REASON_VIDEO_NOT_FOUND`. 테스트 `MarkingBatchBridgeTest#영상_행_미존재시_스킵` |
| TC-MARK-34 | PASS | [정적] | `:166-168` `sanitize()` 가 `\n`/`\r` 제거, 적용지점 `:111`(`raw.getDataSttsCd()`)·`:120`(`raw.getDeIdntfYn()`) 확인 — DB 유래 값만 로그에 나가고 둘 다 sanitize 통과. 실주입 재현은 불가(두 컬럼이 `varchar(20)`/`char(1)` 이며 가드가 정확일치를 요구해 CRLF 값이 이 로그 라인에 도달할 수 없음) |
| TC-MARK-35 | PASS | [실동작] | PORTAL_USER 토큰(sub=3001, channel=PORTAL) → **403** `"권한이 없습니다."` (`MarkingController.java:51` `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")`) |
| TC-MARK-36 | PASS | [실동작] | raw43(`stts_cd='PENDING'` 활성 마킹 보유, stage 는 MARKING_READY 유지) 재요청 → **409** `"이미 진행 중인 마킹이 있습니다…"`. raw44(`VLM_REQUESTED`) 도 409. 거부 시점이 ffprobe·쓰기 이전임은 TC-MARK-39 로 별도 확증 (`MarkingGuards.java:109-114`) |
| TC-MARK-37 | PASS | [실동작] | raw47 에 **동시 5요청**(`&` 병렬) → **201×1(markingSn=48) / 409×4**, DB `ls_marking WHERE raw_sn=47` **1행**. DB 최종 방어 경유 확증: 로그에 `ERROR: duplicate key value violates unique constraint "uk_ls_marking_raw_actvtn"` ×4 → `[Marking] concurrent duplicate rejected rawSn=47` ×4 (`MarkingService.java:226-233`, `V142__add_ls_marking_active_unique.sql`) |
| TC-MARK-38 | PASS | [실동작] | 종결 상태만 보유한 raw45(`VLM_FAILED`)·raw46(`VLM_COMPLETED`) → **둘 다 201**(재마킹 허용). 술어 문자 일치 확증: `LsMarking.java:65 ACTIVE_STATUSES=List.of("PENDING","VLM_REQUESTED")` ↔ `\d ls_marking` 의 `WHERE stts_cd = ANY ('PENDING','VLM_REQUESTED')` **동일** |
| TC-MARK-39 | PASS | [실동작] | raw55(길이 미상 = 프로브 필요 + 활성 마킹 보유) AUTO 요청 → **409**, 직후 프로브 로그 grep **0건** → precheck 단계에서 409 확정, ffprobe 미트리거 (`MarkingPrecheckReader.java:49`) |
| TC-MARK-40 | PASS | [실동작] | `marks=[{"frameIndex":-5}]` → **400** `"marks[0].frameIndex: frameIndex 는 0 이상이어야 합니다."` — **`@Valid` 전파 실동작 확인**(구 결함 회귀 없음). 추가: `frameIndex` 생략 → 400 `"marks[0].frameIndex: frameIndex 는 필수입니다."` (`MarkItem.java:23-24`, `MarkingRequest.java:30`) |
| TC-MARK-41 | PASS | [실동작] | `[{0},{0}]` → **400** `"중복된 마킹 시점입니다: frameIndex=0"`. 길이 미상 영상(raw55)에서도 중복 검증은 유지됨(`[{7},{7}]` → 400) (`MarkingService.java:312-315`) |
| TC-MARK-42 | PASS | [실동작] | `frameIndex=999999999` → **400** `"영상 길이를 벗어난 마킹 시점입니다: frameIndex=999999999 (허용 상한 330 프레임 미만)"`. 상한 = `round(10×30)+ceil(30)=330` **배타** 검증: `frameIndex=329` → **201 통과**, `=330` → 400 (`:322-329`, `:350-352`) |
| TC-MARK-43 | PASS | [실동작] | raw54(길이 3단 폴백 전부 실패) MANUAL `frameIndex=999999` → **201 통과**(상한만 skip) + 로그 `[Marking] duration unknown — manual mark upper-bound check skipped rawSn=54 marks=1` **WARN 실측**. 동일 조건에서 중복(`[{7},{7}]`)·하한(`-5`) 은 그대로 400 (전부 스킵 아님) (`:317-321`) |
| TC-MARK-44 | PASS | [실동작] | `timestamp:"99:99"` → 400, `"-1:00"` → 400 (둘 다 `@Pattern` 메시지). `timestamp` 생략(null) → **201 통과**, DB `mark_cn` 에 `"timestamp":null` 저장 (`MarkItem.java:27-28`) |
| TC-MARK-45 | PARTIAL | [실동작] | marks **20001건** → **400** `"marks: 한 번에 처리 가능한 마킹 수 초과 (최대 20000)"` (`MarkingRequest.java:31` `@Size`), 20000건은 `@Size` 통과 후 상한검증에서 400 → **MANUAL 경로 방어는 기대대로 동작**. 그러나 같은 CWE-770 표면인 **AUTO 경로에는 상한이 없다** — 실측 108,000 marks / 4.4MB 생성·저장 성공 → 방어 불완전 **C-ISSUE-01** |
| TC-MARK-46 | PASS | [실동작] | raw48(stage=`MARKING_READY`, work=`APPROVED`) 마킹 → **201** + `"batchTriggered":false, "batchSkipReason":"검수 진행/완료(또는 반려) 상태이거나 이미 배치가 큐잉되어 배치를 시작하지 않았습니다."` (무음 스킵 제거 확인). 스레드로컬 초기화(`MarkingService.java:108 begin()`)는 동일 스레드 풀에서 이어진 후속 요청들이 `batchTriggered=true/null` 로 올바르게 갈린 것으로 간접 확인. ⚠ 이때 생성된 `PENDING` 마킹이 종결되지 않아 raw48 은 이후 영구 409 — **기 등록 B-ISSUE-41 재현**(중복 등록 안 함) |

---

## 반증(확증편향 차단) 수행 내역

프롬프트가 지정한 4개 축 + 자체 추가 축을 **실제 요청으로** 반증 시도했다. 결과는 위 표에 반영.

| 반증 축 | 시도한 우회 | 결과 |
|---|---|---|
| **자동 마킹 intervalFrames 경계** | `null` / `0` / `-1` / `2147483647` / `2147483648`(int 오버플로) | 0·음수·null 400, 오버플로 400(파싱). **MAX_INT 는 201 + marks 1건** — 기 등록 B-ISSUE-23(상한 미검증, 현재 동작 고정) 재확인, 신규 이슈 아님 |
| **자동 마킹 영상길이 경계** | `VDO_LEN_SEC` = null / 0 / -5, 메타 없음 + 파일 부재 | 전부 400(퇴화 방지). 0·음수는 resolver 가 null 로 정규화 |
| **off-by-one / 분수 fps** | dur=10·fps=30/29.97/25, interval=30 | 끝 경계 프레임 미포함, `Math.round` 정확, 25fps 는 9건으로 정확히 줄어듦 |
| **수동 마킹 입력** | `frameIndex` 음수/null/중복/999999999/329(경계)/330(경계), `timestamp` `99:99`·`-1:00`·null, marks 20001건 | 전부 기대대로. `@Valid` 중첩 전파 실동작 확인 |
| **미배정 WORKER 인가(IDOR)** | 타 영상 / 미존재 rawSn / **REVIEWER-타입으로만 배정된 WORKER** | 전부 403(미존재도 403 = 존재 은닉). LABELER 배정만 인정 |
| **비식별 미완료 차단** | `de_ident_yn` = `N` / `F` / **`y`(소문자)** | 전부 412. 대소문자 우회 없음 |
| **mode 파싱 우회** | `"auto"` / `" AUTO "` | 둘 다 400 (equals 정확일치) |
| **경로 파라미터** | `rawSn=99999999999999999999`(Long 초과) | 400 `"파라미터 형식이 올바르지 않습니다: rawSn"` (500 아님) |
| **동시성** | 동일 rawSn 동시 5요청 | 201×1 / 409×4, DB 1행. **DB 부분 유니크 인덱스가 실제로 발화**(로그로 확증) |
| **프로브 리소스 표면** | 인가 실패·중복 409 시 ffprobe 트리거 여부 | 미트리거(0건). **단, 입력검증 실패(intervalFrames=0) 시에는 트리거됨 → C-ISSUE-02** |
| **PII/경로 노출** | 응답 `videoPath` 값 | 원본(비식별 전) NAS 절대경로가 WORKER 응답에 노출 → **C-ISSUE-03** |

---

## 이슈

### [C-ISSUE-01] TC-MARK-45 — AUTO 마킹의 marks 개수에 상한이 없어 MANUAL 의 CWE-770 방어(20000)를 그대로 우회한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `MarkingRequest.marks` 의 `@Size(max=20000)` 는 "한 요청이 만들어낼 수 있는 마킹 수"를 제한해 과대 요청 DoS(CWE-770)를 막는 방어다(`MarkingRequest.java:26-31` 주석이 명시). 마킹 1건은 곧 **프레임 추출 1회(원본+비식별 2벌) + 이미지 파일 2개 + `LS_DATA_SRC` 1행**이 되므로, 생성 경로가 AUTO 든 MANUAL 이든 **동일한 상한이 적용돼야** 방어가 성립한다.
- **현재 동작(이슈 내용)**: 상한은 **요청 바디의 `marks` 배열에만** 걸려 있고, AUTO 가 서버에서 생성하는 marks 에는 어떤 상한도 없다.
  ```java
  // MarkingService.java:274-283  (generateAutoMarks) — 개수 상한 없음
  List<MarkItem> marks = new ArrayList<>();
  int totalFrames = (int) Math.round(durationSec * fps);
  for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) {
      marks.add(new MarkItem(frameIndex, formatTimestamp((int)(frameIndex / fps))));
  }
  return serializeMarks(marks);   // → LS_MARKING.MARK_CN(TEXT) 한 컬럼에 전량 저장 + 응답에 전량 반환
  ```
  하류 소비자에도 상한이 없다 — `FfmpegFrameExtractor.java:245` 는 `for (int i = 0; i < marks.size(); i++)` 로 marks 전건을 그대로 추출한다.
  **실측(raw67, `VDO_LEN_SEC=3600`, fps 30 폴백, `intervalFrames=1`)**:
  ```
  POST /v1/videos/67/markings {"mode":"AUTO","intervalFrames":1}
    → 201, 응답 size_download = 4,425,325 bytes, marks 108,000 건, 소요 0.2s
  DB : select length(mark_cn) from ls_marking where raw_sn=67;  →  4,424,891
  ```
  `intervalFrames` 는 사용자 입력이고 작을수록 결과가 커진다(1이 최댓값 조건). 24시간 영상이면 동일 요청으로 약 2,592,000 marks(≈106MB)가 한 컬럼·한 응답에 실린다.
- **재현/확인 경로**:
  ```sql
  INSERT INTO ls_data_raw (vms_clip_id,vms_cctv_id,evnt_type_cd,prvc_type_cd,prvc_yn,de_ident_yn,raw_file_path_nm,vdo_len_sec,data_stts_cd)
  VALUES ('C1-BIG','CCTV','EV02000201','ANONY','N','Y','/app/storage/raw/seed/clip-9103.mp4',3600,'MARKING_READY');
  ```
  ```bash
  curl -s -w '%{size_download}\n' -X POST http://localhost:18081/api/v1/videos/{rawSn}/markings \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' \
    -d '{"mode":"AUTO","intervalFrames":1}' -o /dev/null
  ```
- **영향**: CWE-770(Allocation of Resources Without Limits) / OWASP API4. ①단일 요청으로 수 MB~수백 MB 문자열을 힙에 구성 후 TEXT 컬럼 저장 + 응답 전송 ②그 마킹이 배치를 트리거하면 marks 건수만큼 ffmpeg seek 과 이미지 파일 2배가 발생해 **NAS·CPU 증폭**이 훨씬 크다(108,000 marks = 216,000 파일). 인증된 WORKER/REVIEWER 면 누구나 호출 가능하며, 오타 한 번(`intervalFrames:1`)으로도 발생하는 **운영 사고 표면**이다.
- **수정 방향(제안)**: `MarkingService.generateAutoMarks` 에 MANUAL 과 동일한 상한(20000)을 적용해 초과 시 `INVALID_INPUT`(또는 `intervalFrames` 를 상향하도록 안내)으로 거부한다. 상수는 `MarkingRequest` 의 `@Size` 값과 **한 곳에서 공유**(예: `MarkingLimits.MAX_MARKS`)해 두 경로가 드리프트하지 않게 한다. 병행으로 `FfmpegFrameExtractor` 에 방어적 상한을 두는 것도 검토. ⚠ 구현은 하지 않는다.

### [C-ISSUE-02] TC-MARK-06/21 파생 — `intervalFrames` 입력검증이 ffprobe **이후**에 수행돼, 명백히 무효한 입력에도 프로브 서브프로세스가 실행된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `MarkingService` 는 "고비용 ffprobe 는 **인가·프리컨디션 통과 이후에만**" 실행한다는 불변식을 Javadoc(`:39-44`, `:94-98`)으로 명문화하고 `MarkingPrecheckReader` 로 구현했다(HIGH — CWE-862/400 회귀 봉인, TC-MARK-21). 같은 취지라면 **요청 자체가 무효라 어차피 400 이 될 입력**은 프로브보다 먼저 걸러져야 한다 — 프로브 결과를 쓸 곳이 없기 때문이다.
- **현재 동작(이슈 내용)**: `intervalFrames` 에는 Bean Validation 이 없고(`MarkingRequest.java:23` — `Integer intervalFrames,` 만 선언, `@Positive`/`@Min` 부재), 검증은 **persist 트랜잭션 안**에서야 수행된다(`MarkingService.java:196-198`). 그런데 프로브는 그보다 앞선 오케스트레이션 2단계에서 실행된다.
  ```java
  // MarkingService.java:121-126  — mode 만 보고 프로브를 먼저 태운다(intervalFrames 유효성 미확인)
  Integer durationSec = null;
  if (MODE_AUTO.equals(req.mode())) {
      durationSec = durationResolver.resolveDurationSec(rawSn);   // ← ffprobe 폴백 가능
  } else if (MODE_MANUAL.equals(req.mode())) { ... }
  // :196-198 — 여기서야 400
  if (req.intervalFrames() == null || req.intervalFrames() <= 0) { throw new CustomException(INVALID_INPUT, ...); }
  ```
  **실측(raw69: `VDO_LEN_SEC=NULL` + 메타 없음 → 3단계 프로브 도달 조건)**:
  ```
  POST /v1/videos/69/markings {"mode":"AUTO","intervalFrames":0}   → 400
  로그: [Video][Probe] ffprobe empty output path=9bc9c203   ← 400 을 내기 전에 프로브가 실행됨
  ```
  (대조군: 인가 실패·활성 중복 409 케이스에서는 프로브 로그 0건 — TC-MARK-21/39 확인. 즉 **입력 유효성 축만 이 보호에서 빠져 있다**.)
- **재현/확인 경로**:
  ```bash
  # VDO_LEN_SEC NULL + video.duration_ms 메타 없음 + 파일 부재인 rawSn 준비 후
  curl -s -X POST http://localhost:18081/api/v1/videos/{rawSn}/markings -H "Authorization: Bearer $REVIEWER" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":0}'
  docker logs klid-backend --since 10s | grep -E "Probe|ffprobe"
  ```
- **영향**: CWE-770 / OWASP API4(Unrestricted Resource Consumption). 인증된 사용자가 `intervalFrames:0` 같은 상수 바디를 반복 전송해 **거부될 요청마다 ffprobe 서브프로세스(최대 수십 초)** 를 띄울 수 있다. TC-MARK-21 이 봉인한 표면(미인가 트리거)과 동일한 자원 축이며, 방어가 인가 축에만 적용돼 **불완전**하다. 심각도는 인증 필요·요청당 1프로세스라는 점에서 MEDIUM.
- **수정 방향(제안)**: `MarkingRequest.intervalFrames` 에 `@Positive`(또는 `@Min(1)`) 를 붙여 컨트롤러 `@Valid` 단계(=프로브 이전)에서 400 이 나가게 한다. 서비스의 `:196-198` 백스톱은 직접 호출 경로 보호용으로 **유지**한다(TC-MARK-05/06 기대 메시지 보존을 위해 메시지 정합 필요). 동일 취지로 `MANUAL` 도 `marks` 빈값 검증을 `@NotEmpty` 로 앞당기는 것을 함께 검토. ⚠ 구현은 하지 않는다.

### [C-ISSUE-03] TC-MARK-01/02 파생 — `LS_MARKING.VIDEO_FILE_PATH_NM` 이 **원본(비식별 전) 절대경로**를 저장·응답 노출하며 소비자가 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: `CLAUDE.md` 는 "마킹은 **비식별 영상** 대상"으로 못박고 있고, 마킹 화면 스트리밍도 항상 비식별본만 서빙한다(비식별 미완료 시 404). 따라서 마킹 레코드가 보관·노출하는 영상 경로도 비식별본이어야 하며, 어느 쪽도 아니라면 **아예 응답에 싣지 않아야** 한다(내부 스토리지 경로 비노출, CWE-200/209).
- **현재 동작(이슈 내용)**: 저장·응답 값이 `LS_DATA_RAW.RAW_FILE_PATH_NM`(= 원본 경로)이다.
  ```java
  // MarkingService.java:217-218
  LsMarking marking = "AUTO".equals(req.mode())
      ? LsMarking.createAuto(rawSn, eventName, req.intervalFrames(), raw.getRawFilePathNm(), marksJson, actorNo, fps)
      : LsMarking.createManual(rawSn, eventName, raw.getRawFilePathNm(), marksJson, actorNo, fps);
  // MarkingResponse.java:54 — 그대로 응답 필드 videoPath 로 반환
  ```
  **실측(배정 WORKER 토큰, raw49)**: `{"markingSn":55,…,"videoPath":"/app/storage/raw/seed/clip-9103.mp4",…}` — 마스킹 없는 서버 내부 절대경로.
  또한 `grep -rn "getVideoFilePathNm" backend/src/main/java` 결과 **소비자는 `MarkingResponse:54` 하나뿐**이다. VLM 위탁은 이 값을 쓰지 않고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 에서 별도로 도출한다(`VlmTimeseriesStep.java:309, :391` — 비식별본 계약은 정상 유지). 즉 이 컬럼은 **응답 에코 전용 사실상 dead field** 다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/videos/{배정된rawSn}/markings -H "Authorization: Bearer $WORKER" \
    -H 'Content-Type: application/json' -d '{"mode":"AUTO","intervalFrames":30}' | python3 -m json.tool | grep videoPath
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select video_file_path_nm from ls_marking order by marking_sn desc limit 1;"
  ```
- **영향**: 보안 LOW(CWE-200 — 내부 스토리지 레이아웃 노출. 원본 파일 자체는 별도 인가로 보호되므로 직접 읽기는 불가). 설계 정합 관점에서는 "마킹 = 비식별본 대상" 규칙과 저장값이 어긋나, 향후 이 컬럼을 신뢰해 소비하는 코드가 붙으면 **원본 픽셀 경로가 파이프라인에 유입**될 수 있는 잠재 함정이다(VLM 이 이미 별도 경로를 쓰는 것이 그 방증).
- **수정 방향(제안)**: 셋 중 택1 — ①저장값을 비식별본 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)로 교체(단 파일명이 벤더마다 달라 조합 금지, 값 조회 필수) ②응답 `videoPath` 필드 제거(FE 소비 여부 확인 후) ③컬럼 자체 폐기. 어느 쪽이든 "이 컬럼은 신뢰 소스가 아니다"를 Javadoc 에 명시. ⚠ 구현은 하지 않는다.

### [C-ISSUE-04] TC-MARK-40/44/45 — 근거 `file:line` 드리프트 3건 (카탈로그 정합)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 근거 `file:line` 이 실제 위치를 가리켜야 다음 회차 판정·수정 작업이 성립한다.
- **현재 동작(이슈 내용)**: 실제 라인과 1~2줄 어긋난다(동작 결함 아님, 판정에는 영향 없음).

  | 케이스 | 카탈로그 근거 | 실제 위치 |
  |---|---|---|
  | TC-MARK-40 | `MarkItem.java:26-27` (`@Min(0)`) | `@NotNull` = `:23`, **`@Min(0)` = `:24`** (`:26-27` 은 공백줄 + `@Pattern` 시작) |
  | TC-MARK-44 | `MarkItem.java:29-30` (`@Pattern`) | **`@Pattern` = `:27-28`** (`:29` 는 `String timestamp`, `:30` 은 `)`) |
  | TC-MARK-45 | `MarkingRequest.java:32` (`@Size`) | **`@Size` = `:31`** (`:32` 는 `List<MarkItem> marks`) |
- **재현/확인 경로**: `sed -n '22,30p' backend/src/main/java/kr/co/cudo/authoring/marking/dto/MarkItem.java`
- **영향**: 카탈로그 정합만. 그 외 TC-MARK-01~39·41~43·46 의 근거 43건은 **전량 실제 위치와 일치**함을 확인했다.
- **수정 방향(제안)**: `C-marking-labeling.md` 의 해당 3행 근거 컬럼을 위 실제 위치로 정정. ⚠ 구현은 하지 않는다.

### [C-ISSUE-05] TC-MARK-11 — 29.97fps marks 산출 회귀 테스트가 여전히 부재 (이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: 분수 fps 는 `Math.round(dur×fps)` 반올림 정책이 걸린 유일한 경로이고 마킹↔프레임추출 정합의 근거이므로, 이 계산에 회귀 가드가 있어야 한다.
- **현재 동작(이슈 내용)**: 실동작은 정확하다(본 회차 실측: raw58, `video.fps='29.97'` → `totalFrames=300`, marks 10건, `ls_marking.fps=29.97` pin). 그러나 `grep -rn "29.97" backend/src/test` 결과 관련 테스트는 `VideoFpsResolverTest#분수fps_29.97_정확파싱`(파싱만)과 `VideoMetaServiceTest`(적재만)뿐이고, **`MarkingService` 의 marks 산출 경로를 29.97 로 검증하는 테스트는 없다** — `MarkingServiceTest` 의 fps 케이스는 25 / 30(폴백) / 60 뿐이다(`#자동마킹_실fps25…`, `#M3_자동마킹_fps미상_30폴백…`, `#자동마킹_실fps60…`).
- **재현/확인 경로**: `grep -rn "29.97" backend/src/test` · `grep -n '@DisplayName' backend/src/test/java/kr/co/cudo/authoring/marking/MarkingServiceTest.java`
- **영향**: 테스트 커버리지 갭. `totalFrames` 반올림 정책(예: `round`→`floor` 변경)이 바뀌어도 CI 가 못 잡는다.
- **수정 방향(제안)**: `MarkingServiceTest` 에 `자동마킹_실fps29_97_totalFrames와_타임스탬프_정확계산` 추가(dur=10 → totalFrames 300, frameIndex 0/30/…/270, timestamp `00:00`~`00:09`). ⚠ 구현은 하지 않는다.

---

## 비고 (신규 이슈로 등록하지 않은 관측)

1. **B-ISSUE-41 재현(중복 등록 안 함)** — TC-MARK-46 경로에서 생성된 `PENDING` 마킹이 종결되지 않아 raw48 은 이후 모든 마킹 요청이 영구 409 다(실측: 재요청 → 409). 기 등록 이슈와 동일 현상이므로 판정 참고만 하고 신규 이슈를 만들지 않았다.
2. **B-ISSUE-23 재확인(중복 등록 안 함)** — `intervalFrames=2147483647` 이 201 + marks 1건으로 통과한다(상한 미검증). `UNCERTAINTIES.md` 의 "미해소 이월 · 현재 동작 고정(TC-BATCH-096)" 그대로다.
3. **본 세션 합성 데이터의 배치는 대부분 FAILED** — 내가 INSERT 한 영상들은 비식별 산출물(`LS_DEIDENT_PROC_LOG`)·비식별 프레임이 없어 배치가 프레임추출에서 실패한다. 이는 마킹 API 판정에 영향이 없으며(마킹은 커밋 후 AFTER_COMMIT 으로 배치를 넘긴다), VLM 상태전이(TC-MARK-27/28)는 `pipeline-drive.md` 의 정상 경로 실측(rawSn 26/30)을 근거로 삼았다.
4. **폐기 케이스 0건** — C-1 구간에 `~~취소선~~` 행은 없다. `grep -cE '^\| *~*TC-MARK-'` = 46 으로 헤더 표기(46건)와 실측 일치.
# C 클러스터 검증 결과 — part2 (TC-LABEL 라벨 CRUD 기본: TC-LABEL-01~46)

- **담당 범위**: `docs/test-cases/C-marking-labeling.md` `## C-2. TC-LABEL` 섹션 앞부분 (파일 라인 73~120)
- **대상 케이스**: TC-LABEL-01 ~ 37, 40 ~ 46 = **44건** (표에 38·39 행은 존재하지 않음 — 카탈로그 번호 결번)
- **검증 커밋**: `56d30478` (V158) · 워크트리 `qa-0801`
- **검증 일시**: 2026-08-01 23:10~23:25 KST
- **환경**: backend `localhost:18081/api`(UP) · mock-server `:9400`(ok) · frontend `:13000`(200) · DB `klid-postgres/klid_system/public`
- **방식**: **실동작 우선** — 실 스택에 curl 요청 + DB 실측 + backend 컨테이너 로그 대조. 정적 대조로 근거 file:line 확인.

> ⚠ **동시 검증 간섭 기록**: 검증 도중(23:18:18) 다른 병렬 에이전트가 `raw_sn=27` 에 비식별 신고를 접수해
> 작업락 + `DE_IDENT_YN='F'` 가 걸렸다. 이후 테스트는 `raw_sn=34`(src 313/314, REVIEWER 토큰)로 이전해 수행했다.
> 판정에는 영향 없음(간섭 전 완료 케이스는 raw 27 기준, 이후는 raw 34 기준으로 각각 실측).

---

## 판정 요약

| 판정 | 건수 |
|------|-----:|
| PASS | 43 |
| PARTIAL | 1 |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **44** |

신규 이슈: **C-ISSUE-21** 1건(MEDIUM). 근거 드리프트: 3건.

---

## 케이스별 판정

| ID | 케이스명 | 판정 | 근거 확인 | 실측 근거 |
|---|---|:--:|---|---|
| TC-LABEL-01 | 프레임 라벨 조회 정상 | PASS | [실동작] | `GET /v1/frames/301/labels` (WORKER 2001, raw27) → 200. 응답 `{srcSn:301, videoId:27, frameImageType:"DEID", lockSttsCd:null, labelVersion:1, siblings:list[5], items:list[2]}`. siblings 는 `{srcSn,frameNo,hasLabel}` 형태로 반환(`hasLabel:true`). aiInfo enrich(`autoLblYn/confScore/lblSrcCd`)·lsLabel enrich(`labelName/color`) 필드 모두 응답에 존재. `labelVersion` 포함 확인. 정적: `LabelService.java:187-213` 일치 |
| TC-LABEL-02 | 조회 IDOR: 타 영상 프레임 | PASS | [실동작] | WORKER 2001 → `GET /v1/frames/75/labels`(raw17, 2002 배정) → **403** `{"errorCode":"FORBIDDEN","message":"본인에게 배정되지 않은 영상입니다."}`. 정적 `LabelAccessGuard.java:62-69` 일치. **추가 반증**: 같은 프레임에 `PUT`(쓰기)도 403 — 조회만 막고 저장이 새는 비대칭 없음 |
| TC-LABEL-03 | 조회: 미인증 | PASS | [실동작] | 토큰 없이 `GET /v1/frames/301/labels` → **401** `{"errorCode":"UNAUTHORIZED"}`. 정적 `LabelAccessGuard.java:48-50` 일치 |
| TC-LABEL-04 | 조회: 프레임 미존재 | PASS | [실동작] | `GET /v1/frames/999999/labels` → **404** `"프레임을 찾을 수 없습니다."`. `srcSn=abc`(비정수) → 400 `"파라미터 형식이 올바르지 않습니다: srcSn"`(500 아님). srcSn=null 방어는 `LabelAccessGuard.java:54-58` 정적 확인 |
| TC-LABEL-05 | frameImageType RAW(REVIEWER+raw) | PASS | [실동작] | REVIEWER + `?raw=true` → `frameImageType="RAW"`. REVIEWER + raw 미지정 → `"DEID"`. 정적 `LabelService.java:229-234` 일치 |
| TC-LABEL-06 | frameImageType DEID(WORKER raw무시) | PASS | [실동작] | WORKER + `?raw=true` → `frameImageType="DEID"` (강제). 역할 기반 분기가 쿼리파라미터를 신뢰하지 않음 |
| TC-LABEL-07 | bulkUpsert 신규 INSERT(수동) | PASS | [실동작] | `PUT /v1/frames/303/labels` `{lblTypeCd:BBOX,label:"사람",points:[[10,10],[50,50]],source:"MANUAL"}` → 200. DB `ls_data_lbl` lbl_sn=588, `reg_user_no=2001`, `ls_data_lbl_ai_info` 0건 → 응답 `autoLblYn="N"`. `ls_data_lbl_hstry` #37 = `[{"lblSn":588,"kind":"ADDED","after":{...}}]`. `lbl_ver` 0→1 |
| TC-LABEL-08 | bulkUpsert 기존 UPDATE | PASS | [실동작] | id=588 좌표 `[[10,10],[50,50]]`→`[[11,11],[51,51]]` → 이력 #38 `{"kind":"UPDATED","before":{"pointCn":"[[10.0,10.0],[50.0,50.0]]"},"after":{"pointCn":"[[11.0,11.0],[51.0,51.0]]"}}` — before 스냅샷이 `updateUserContent` **전** 값으로 정확히 캡처됨 |
| TC-LABEL-09 | full-replace: 빠진 라벨 삭제 | PASS | [실동작] | 기존 2건(588,589) 중 588만 전송 → DB 에 588만 잔존, 589 **실삭제**. 이력 #39 `[{"lblSn":589,"kind":"DELETED","before":{...},"after":null}]`. 589 의 `ls_data_lbl_ai_info` 자식도 0건으로 동반 삭제 |
| TC-LABEL-10 | full-replace: 빈 items=전량 삭제 | PASS | [실동작] | `{"items":[]}` → 200, `select count(*) from ls_data_lbl where src_sn=303` = **0**. 이력 #40 DELETED + before 스냅샷. `lbl_ver` 3→4 |
| TC-LABEL-11 | 삭제 순서 FK고아 방지 | PASS | [실동작]+[정적] | 삭제 대상 588 에 **실 FK 자식** `ls_data_lbl_attr_val`(atrb_vl_id=2, `fk_ls_data_lbl_attr_lbl`) 을 사전 INSERT 후 `items:[]` 저장 → **200**(FK 위반 500 없음), 자식 0건. 정적 `LabelService.java:390-392` = `attrValRepository.deleteByLblSnIn` → `aiInfoRepository.deleteByDataLblSnIn` → `labelRepository.deleteAllByIdInBatch` 순서 확인 |
| TC-LABEL-12 | 작업락 시 저장 차단 | PASS | [실동작] | `ls_auth_work_lock` 에 `(RAW, raw_sn=27, LOCKED)` INSERT → PUT → **409** `"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."`. 락 상태에서 **조회는 200 + `lockSttsCd:"LOCKED"`**(FE 사전 인지용, 정책 일치). RELEASED 로 되돌리자 저장 200 복귀 |
| TC-LABEL-13 | dedupById 동일 id 중복 | PASS | [실동작] | 같은 id 를 `label:"first"` / `label:"last"` 로 2회 전송 → 응답 items **1건**, 값은 `label:"last", points:[[3,3],[4,4]]` = last-value-wins. DB 도 1행 |
| TC-LABEL-14 | 좌표 검증: 빈 points | PASS | [실동작] | `points:[]` → 400 `"items[0].points: must not be empty"` — 기대대로 **DTO `@NotEmpty`(LabelItemDto.java:42)가 먼저 발화**하고 서비스 메시지(`LabelService.java:707-709`)는 도달하지 않음 |
| TC-LABEL-15 | 좌표 검증: [x,y] 형식 위반 | **PARTIAL** | [실동작] | 명시 입력(`pair size≠2`, 예 `[[1,2,3]]`)은 **400** `"좌표는 [x, y] 형태여야 합니다."` 로 정상. **그러나 형식검증 방어가 불완전** — size==2 이면서 원소가 null 인 `[[null,5],[10,10]]` 은 **500 INTERNAL_ERROR**(NPE). SKELETON 경로는 같은 입력을 400 으로 거부하므로 경로 간 비대칭. → **C-ISSUE-21** |
| TC-LABEL-16 | 좌표 검증: 음수 | PASS | [실동작] | `[[-1,5],[10,10]]` → 400 `"좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)"`. ★3(UNCERTAINTIES) 대로 **사용자 저장 경로는 거부(클램프 아님)** — AI 검출 clamp 정책과의 비대칭은 확정 정책이라 결함 미보고 |
| TC-LABEL-17 | 좌표 검증: 0 허용(경계) | PASS | [실동작] | `[[0,0],[10,10]]` → **200** 저장 성공(`labelVersion` 증가). 0 은 음수가 아니므로 통과 |
| TC-LABEL-18 | 신규 라벨 >1000점 차단(DoS) | PASS | [실동작] | 신규(id=null) POLYGON 1001점 → **400** `"라벨당 좌표 개수 초과 (최대 1000 점)"`. **경계 1000점은 200 저장 성공**(DB `point_cn` 길이 11801) — off-by-one 없음 |
| TC-LABEL-19 | 기존 라벨 >1000점 simplify | PASS | [실동작] | 기존 id 지정 + 1001점 → **400 아님, 200**. 응답 `points` **141점**, DB `point_cn` 길이 1611 → `capPoints`(Douglas-Peucker, `LabelService.java:838`·`:907-912`) 로 ≤1000 축약 확인 |
| TC-LABEL-20 | labelId 미존재 | PASS | [실동작] | `labelId:999999` → **404** `"라벨 마스터를 찾을 수 없습니다: labelId=999999"` |
| TC-LABEL-21 | labelId USE_YN='N' → 신규 부여에만 409 | PASS | [실동작] | 4분기 전수 실측(C-ISSUE-25 수정 검증): ①신규(id=null)에 비활성 `labelId:10` → **409** `"사용 중지된 라벨입니다"` ②**기존 라벨이 같은 비활성 labelId(10) 유지 → 200 통과**(프레임 저장 영구차단 회귀 없음) ③기존 라벨의 labelId 를 다른 비활성(11)로 **변경** → 409 ④**우회 시도**: 타 프레임(314)에 다른 프레임 라벨 id(594)+비활성 labelId(10) 전송 → 409(신규 취급). "id 만 붙이면 통과" 우회 불성립 |
| TC-LABEL-22 | Mass Assignment: autoLblYn 무시 | PASS | [실동작]+[정적] | 수동 라벨 UPDATE 요청에 `"autoLblYn":"Y"` 첨부 → 응답 `autoLblYn="N"`, `ls_data_lbl_ai_info` 0건. 정적: `ls_data_lbl` 스키마에 `auto_lbl_yn` 컬럼 **자체가 없음**(psql `\d` 확인), `grep -rn 'autoLblYn()' src/main/java` → `LabelService` 에서 요청값을 **읽는 코드 0건**(version 롤백 경로만 별도 DTO 사용). 구조적으로 바인딩 불가 |
| TC-LABEL-23 | provenance AUTO_YOLO→AI_INFO | PASS | [실동작] | 신규 `{source:"AUTO_YOLO",confScore:0.87,algorithm:"YOLO"}` → 응답 `autoLblYn="Y", confScore=0.87, lblSrcCd="YOLO"`. DB `ls_data_lbl_ai_info` 행 생성: `data_lbl_sn=589, data_raw_sn=27, data_src_sn=303, lbl_src_cd=YOLO, conf_score=0.87000, auto_lbl_yn=Y, reg_id=2001` |
| TC-LABEL-24 | provenance 화이트리스트 위반 | PASS | [실동작] | `source:"HACK"` → 400 `"items[0].source: source 는 MANUAL/AUTO_YOLO/AUTO_SAM2 중 하나여야 합니다."`(`LabelItemDto.java:44-45`) |
| TC-LABEL-25 | confScore 범위 초과 | PASS | [실동작] | `confScore:1.5` → 400 `"items[0].confScore: confScore 는 1.0 이하여야 합니다."`(`@DecimalMax(1.0)`) |
| TC-LABEL-26 | items >500 상한(DoS) | PASS | [실동작] | items 501건 → 400 `"items: 한 번에 처리 가능한 라벨 수 초과 (최대 500)"`. 근거 라인 드리프트 있음(아래 참조) |
| TC-LABEL-27 | 무변경 재저장 이력 미발행(R7) | PASS | [실동작] | 동일 좌표·라벨명 재전송 → 응답 `labelVersion` 9→**9**(미증가), `max(lbl_hstry_sn)` 45→**45**(이력 미생성). 로그도 `labelVersion=9->9` |
| TC-LABEL-28 | R7 정규화 비교(5 vs 5.0) | PASS | [실동작] | 요청은 정수 `[[3,3],[4,4]]`, 저장값은 `"[[3.0,3.0],[4.0,4.0]]"` — 표현만 다른데 **무변경 판정**(이력·버전 불변). `pointsEqual` 수치 정규화 동작 확인 |
| TC-LABEL-29 | R7 레거시 3포맷 흡수 | PASS | [실동작] | DB 값을 직접 레거시 포맷으로 교체 후 정규 포맷 동일값 재저장: ①**평탄** `[3,3,4,4]` → 이력 45→45(무변경) ②**객체배열** `[{"x":3,"y":3},{"x":4,"y":4}]` → 이력 45→45(무변경). `LabelPointSerializer.fromJson` 3포맷 흡수 실증 |
| TC-LABEL-30 | R7 손상 JSON fail-safe | PASS | [실동작] | DB `point_cn='{corrupt'` 로 교체 후 재저장 → 200 + 이력 45→**46**(= '변경됨' 처리, 이력 유실 없음). ⚠ 카탈로그 주석대로 **전용 회귀 테스트는 여전히 부재**(C-ISSUE-23 이월) — `grep` 결과 `pointsEqual`/`normalizePoints` 손상값 회귀 테스트 0건 (`LabelServiceFullReplaceIntegrationTest` 의 "손상된_diff_JSON…" 은 이력 **조회** diff 파싱 테스트로 별건) |
| TC-LABEL-31 | TASK_MODIFIED: APPROVED만 | PASS | [실동작] | APPROVED 영상(raw26, src296)에 라벨 추가 → `ls_mon_noti_acml` #31 `raw_sn=26, stts_cd=PENDING, export_rprcs_yn='Y', chg_dtl_cn={"frames":{"296":["LABEL_ADDED"]}}`. 이어서 삭제 저장 → 같은 행이 `["LABEL_ADDED","LABEL_DELETED"]` 로 누적 = **변경 종류별 발행 + 디바운스 합류 + `exportRegenerated=true`** 3요소 모두 실증 |
| TC-LABEL-32 | TASK_MODIFIED: 검수전 미발행 | PASS | [실동작] | ASSIGNED 영상(raw27) 에 10+회 저장 수행 — `ls_mon_noti_acml` 에 raw27 행 **0건**(전체 테이블에 raw26 1행뿐) |
| TC-LABEL-33 | 이력 조회 IDOR | PASS | [실동작] | WORKER 2001 → `GET /v1/frames/75/label-history` → **403** `"본인에게 배정되지 않은 영상입니다."` |
| TC-LABEL-34 | 이력 페이지 상한 클램프 | PASS | [실동작] | `?size=500` → 응답 `size=100`(클램프). `?size=0` 도 500 없이 200(기본 20) |
| TC-LABEL-35 | 이력 임의 sort 무시(500 차단) | PASS | [실동작] | `?sort=badfield,desc` → **200**(PropertyReferenceException 500 없음), 서버 고정 정렬(첫 행 `lblHstrySn=46` = 최신). **SQLi 반증**: `?sort=regDt;DROP TABLE ls_data_lbl--` → 200 + `ls_data_lbl` 83행 그대로 |
| TC-LABEL-36 | 동시 저장 lost update 차단 | PASS | [실동작] | ①stale 버전 첨부(`labelVersion=현재-1`) → **409** `"다른 사용자가 먼저 저장했습니다…"` + 로그 `[Label] stale label version rejected srcSn=303 requested=9 current=10` ②일치 버전 → 200 ③**미첨부(null) → 200**(하위호환 유지) ④**실 병렬 race**: 동일 baseVersion=3 으로 2세션 동시 PUT → A=409 / B=200, DB 에 `sessionB` 만 생성되고 기존 라벨(597) **삭제되지 않음**, `lbl_ver`=4. 침묵 삭제 폐기 확인 ⑤`labelVersion:-1` → 400(`@PositiveOrZero`) |
| TC-LABEL-37 | 삭제 감사 로깅 PII 미출력 | PASS | [실동작] | 대량 삭제 시 로그: `[Label] bulkUpsert srcSn=303 actor=2001 existing=2 saved=1 deleted=1 labelVersion=2->3` — **카운트 + 버전 + actor 번호만**, 좌표·라벨명·PII 미출력. `LabelService.java:408-409` 일치 |
| TC-LABEL-40 | 마스터 생성 정상 | PASS | [실동작] | `POST /v1/manage/labels` (REVIEWER) → **201** `{labelId:21,name:"qa-c2-lbl",color:"#AABBCC",type:"BBOX",sortNo:900,useYn:"Y",dtctTypeCd:null}`. WORKER 로 동일 요청 → 403 |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | PASS | [실동작] | 활성 `"qa-c2-lbl"` 존재 상태에서 `" Qa-C2-Lbl "` 생성 → **409** `"이미 사용 중인 라벨 이름입니다."`(LOWER(TRIM) 정규화 비교) |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | PASS | [실동작] | 동일 이름 `qa-c2-race` 로 **6요청 병렬 발사** → 201 **정확히 1건**, 409 5건. DB 에 1행만. 인덱스 실재 확인: `uk_ls_label_nm_ci UNIQUE, btree (lower(TRIM(BOTH FROM lbl_nm))) WHERE use_yn = 'Y'`(V120) |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | PASS | [실동작] | `dtctTypeCd:"human"` → **400** `"지원하지 않는 검출 클래스입니다."`(`CocoClasses` 80종 allowlist) |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | PASS | [실동작] | `person` 은 lbl_id=1 에 이미 매핑 → 신규 라벨에 `person` 매핑 시 **409** `"이미 사용 중인 검출 클래스 매핑입니다."`. DB 이중가드 실재: `uk_ls_label_dtct_type UNIQUE btree (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | PASS | [실동작] | `dtctTypeCd:""`(blank) 로 수정 → 200, 응답 `dtctTypeCd:null` 저장. 생성 시 미지정(null)도 `dtctTypeCd:null` |
| TC-LABEL-46 | color 소문자 hex 거부 | PASS | [실동작] | `"#ffffff"` → 400 `"color: color 는 대문자 hex (#RRGGBB) 형식이어야 합니다."`(`LabelMasterRequest.java:33` `@Pattern("^#[0-9A-F]{6}$")`) |

---

## 추가 반증 시도 (케이스 표에 없는 경계·우회 — 전부 방어 확인)

| 반증 시나리오 | 결과 | 비고 |
|---|---|---|
| PUT(쓰기) IDOR — 미배정 프레임 저장 | 403 | 조회만 막고 쓰기가 새는 비대칭 없음 |
| PORTAL_USER 로 라벨 조회 | 403 | `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` |
| 타 프레임 라벨 id 를 실은 full-replace | 원 프레임 라벨 **무손상** | src314 에 id=594(src313 소유) 전송 → 새 라벨 596 생성, 594 는 src313 에 그대로 잔존. 삭제 델타가 `findBySrcSn(srcSn)` 로 한정됨을 실증 |
| 과대 좌표 `1e308` | 400 | 경계 상한 검증(`이미지=320x240`)이 실제 동작 — `FrameBoundsResolver` 활성 |
| `label` 81자 / `lblTypeCd:"MASK"` / `items:null` | 각 400 | DTO 검증 정상 |
| 이력 `page=-1`, `size=0` | 200 | 500 없음 |
| 이력 sort SQL 인젝션 | 200 + 테이블 무손상 | 서버 고정 정렬로 원천 차단 |
| **소프트삭제 마스터(use_yn='N') 참조 라벨 조회** | `labelName`/`color` 정상 반환 | `resolveLsLabelMap` 은 `findAllById`(use_yn 무필터). 라벨 CRUD 응답은 **역사적 라벨명 보존**이 맞고, "미연결 표시" 규칙은 `LS_LABEL_PRESET_CODE`(프리셋) 전용이므로 **정책 위반 아님**. C-ISSUE-25(soft delete=신규 사용중지)와도 정합 → 결함 미보고 |

---

## 테스트 커버 대조 (`_raw/test-baseline.md`: backend 4,755 tests / 실패 0 / skip 5)

| TC | 커버 테스트 (`backend/src/test/java/kr/co/cudo/authoring/label/…`) |
|---|---|
| 01·05·06 | `LabelControllerTest#LabelController_라벨_조회_GET_정상` · `…frameImageType_WORKER는_DEID_응답` · `…REVIEWER_raw_true_쿼리시_frameImageType_RAW_응답` · `…WORKER_raw_true_쿼리는_무시되고_frameImageType_DEID` |
| 02·33 | `LabelControllerTest#LabelController_본인_배정_아닌_프레임_편집시_403` · `LabelServiceFullReplaceIntegrationTest#타인_배정_프레임_저장은_403` · `LabelHistoryControllerTest#타인_프레임_히스토리_조회시_403` |
| 07·08 | `LabelHistoryControllerTest#라벨_신규저장시_ADDED_저장이벤트가_기록된다` · `LabelServiceFullReplaceIntegrationTest#수정_객체는_이전값과_새값이_모두_기록된다` |
| 09·10·11 | `LabelServiceFullReplaceIntegrationTest#저장시_요청에_빠진_라벨은_실제_삭제된다` · `#삭제된_라벨의_속성값과_AI정보도_함께_제거된다` · `#기존라벨_전체삭제시_모두_삭제되고_DELETED만_기록되고_delCnt_N` · `#다건_라벨_동시삭제시_delCnt와_DB_고아가_정확하다` |
| 13 | `LabelServiceFullReplaceIntegrationTest#중복된_id가_오면_last_value_wins로_한번만_처리된다` |
| 16·18·19 | `LabelControllerTest#…좌표_음수_입력시_INVALID_INPUT_400` · `LabelServicePolygonCapTest`(3건) |
| 20·21 | `LabelControllerTest#…존재하지_않는_labelId_시_NOT_FOUND_404` · `LabelSaveGuardsIT#사용중지된_마스터를_참조하는_기존라벨이_있어도_프레임_저장이_가능함` · `#사용중지된_마스터를_신규로_부여하면_409` |
| 22·23·24·25 | `LabelControllerTest#…오토_라벨_수정시_AUTO_LBL_YN은_Y_유지` · `#온라인_오토라벨_저장시_AUTO_LBL_YN_Y와_신뢰도가_보존된다` · `#confScore가_범위밖_1_5이면_400` · `#provenance는_민감필드_role등을_바인딩하지_않는다` |
| 27·28·29 | `LabelServiceFullReplaceIntegrationTest#무변경_재저장시_이력도_통지도_생기지_않는다` · `#평탄포맷_레거시라벨_무변경_재저장시_이력_통지_없음` · `#객체배열포맷_레거시라벨_무변경_재저장시_이력_통지_없음` |
| 31·32 | `LabelServiceTaskModifiedGuardTest`(6건 전부 — ADDED/DELETED/UPDATED/혼합/미검수 2종) |
| 34·35 | `LabelHistoryControllerTest#히스토리_조회_페이징_기본size_20_최대100` · `#히스토리조회_임의_sort파라미터는_무시되고_최신순_고정정렬된다` |
| 36 | `LabelSaveGuardsIT#두_작업자가_동시에_라벨_저장시_stale_버전은_409_이고_앞선_라벨이_삭제되지_않음` · `#락_대기중_타_트랜잭션이_커밋해도_stale_저장은_409_로_거부되고_앞선_라벨이_살아남음` · `#버전_미첨부_요청은_기존대로_저장되어_하위호환이_유지됨` |
| 40~46 | `LabelMasterServiceTest`(27건) · `LabelMasterControllerTest`(11건) · `LabelNameCiUniqueMigrationIT`(7건, V120 인덱스 실재·CI 중복 거부·soft delete 제외) |
| **미커버** | **TC-15 null 원소 형식위반(→C-ISSUE-21)** · TC-30 `pointsEqual` 손상값 fail-safe(C-ISSUE-23 이월) · TC-37 감사 로깅 PII 미출력(전용 테스트 0건, 실동작으로만 확인) |

baseline 대비 실패 0건 — 위 커버 테스트는 모두 통과 상태.

---

## 근거 드리프트 (카탈로그 정합성)

| TC | 카탈로그 근거 | 실제 위치 | 성격 |
|---|---|---|---|
| TC-LABEL-22 | `LabelService.java:133-148, :436` | `LabelItemDto.java:43`(응답 전용 선언) + `LabelService.java` 전체에 `item.autoLblYn()` 참조 **0건**(구조적 미바인딩) | 인용 라인(`resolveAiInfoMap`)은 응답 `autoLblYn` **파생 경로**일 뿐 방어 지점이 아님 — 방어 근거로는 부정확 |
| TC-LABEL-26 | `LabelBulkUpsertRequest.java:26` | `LabelBulkUpsertRequest.java:24` (`@Size(max = 500…)`) | 2줄 오프셋(26 은 레코드 닫는 괄호) |
| TC-LABEL-43·45 | `LabelMasterService.java:122-134` | `LabelMasterService.java:126-135` (`validateAndNormalizeDtctType`) | 소폭 오프셋(122-125 는 javadoc) |

그 외 TC-01~21·23~25·27~37·40~42·44·46 의 `file:line` 은 **실제 코드 위치와 일치**(드리프트 없음).

카탈로그 구조 관찰: 표에 **TC-LABEL-38·39 행이 존재하지 않는다**(37 → 40 로 점프). 폐기 취소선 표기도 없어 의도적 결번인지 누락인지 불명 — 카탈로그 유지보수 시 확인 권장.

---

## 이슈

### [C-ISSUE-21] TC-LABEL-15 — 좌표 배열 원소가 null 이면 형식 검증을 통과해 NPE 500 (2-튜플 경로에만 방어 부재)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 좌표 **형식 위반은 전부 400(INVALID_INPUT)** 이어야 한다. `LabelService.validateSkeletonPoints` 는 이 위험을 코드 주석으로 명시하고 있다 — *"JSON-valid 하지만 원소가 null 인 경우(`[[10,20,null],...]`)는 Double→double 언박싱 NPE(→GlobalExceptionHandler catch-all 500)를 유발한다. 언박싱 전에 400 으로 fail-secure 거부하여 계약(형식 위반=400)과 일치시킨다."* 즉 **의도된 계약은 400** 이며, 이는 CWE-20 입력 방어선 + OWASP A10:2025(Mishandling of Exceptional Conditions, fail-secure) 요구이기도 하다.
- **현재 동작(이슈 내용)**: SKELETON 경로에만 null 원소 가드가 있고, **BBOX/POLYGON/SEGMENT/TRACK 이 타는 2-튜플 경로에는 없다.** `pair.size() != 2` 만 검사한 뒤 곧바로 언박싱한다.

  `backend/src/main/java/kr/co/cudo/authoring/label/service/LabelService.java:719-728`
  ```java
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) {
          throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 [x, y] 형태여야 합니다.");
      }
      double x = pair.get(0);   // ← pair.get(0) == null 이면 언박싱 NPE
      double y = pair.get(1);
      if (x < 0 || y < 0) { ... }
  ```
  대조군 — SKELETON 경로 `LabelService.java:805-811` 는 동일 상황을 400 으로 거부한다.

  **실동작 근거**
  ```
  PUT /api/v1/frames/313/labels
  {"items":[{"lblTypeCd":"BBOX","label":"n","points":[[null,5],[10,10]]}]}
  → 500 {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}

  (대조) lblTypeCd:"SKELETON" + [[10,20,null], …x17]
  → 400 {"errorCode":"INVALID_INPUT","message":"키포인트 좌표에 null 원소가 있습니다."}
  ```
  backend 컨테이너 로그:
  ```
  2026-08-01 23:22:27.292 ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  java.lang.NullPointerException: Cannot invoke "java.lang.Double.doubleValue()"
      because the return value of "java.util.List.get(int)" is null
  ```
  신규(`id=null`)·기존(`id` 지정) **양쪽 경로 모두 500**(기존 경로도 `validatePoints` 를 거친다).
- **재현/확인 경로**
  ```bash
  TOKEN=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens \
    -H 'Content-Type: application/json' \
    -d '{"sub":"1001","role":"REVIEWER","channel":"INTERNAL"}' -o /tmp/t.json; \
    python3 -c "import json;print(json.load(open('/tmp/t.json'))['data']['token'])")

  # {srcSn} 은 배정/REVIEWER 접근 가능한 아무 프레임 (예: 313)
  curl -i -X PUT http://localhost:18081/api/v1/frames/313/labels \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","label":"n","points":[[null,5],[10,10]]}]}'
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR  (기대: 400 INVALID_INPUT)
  ```
- **영향**
  - **계약 위반**: API 규약상 입력 형식 오류는 400 인데 500 이 나가 FE 가 "서버 장애"로 오분류한다(재시도 유발 가능).
  - **CWE-20**(Improper Input Validation) / **CWE-248**(Uncaught Exception) / OWASP **A10:2025** fail-secure 위반. `points` 는 인증된 내부 채널(REVIEWER/WORKER)에서만 오므로 외부 익명 DoS 는 아니나, 단순 요청으로 반복 500 + ERROR 스택 로그를 유발할 수 있어 **로그 오염·알람 노이즈**로 이어진다(운영 ERROR 레벨은 알림 대상).
  - 응답 본문에 스택트레이스는 노출되지 않아 CWE-209 정보노출은 없음(`GlobalExceptionHandler` 가 일반 메시지로 마스킹).
  - 유입 경로: FE 캔버스 버그·부분 직렬화·외부 도구로 만든 라벨 임포트 등에서 실제로 발생 가능.
- **수정 방향(제안)** — ⚠ 구현하지 않음
  `LabelService.validatePoints`(`LabelService.java:719-728`)의 2-튜플 루프에서 **언박싱 전에 null 원소를 400 으로 거부**한다. SKELETON 경로(`:805-811`)와 동일한 메시지 체계·동일한 fail-secure 순서를 쓴다.
  ```java
  Double xBox = pair.get(0);
  Double yBox = pair.get(1);
  if (xBox == null || yBox == null) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "좌표에 null 원소가 있습니다.");
  }
  double x = xBox; double y = yBox;
  ```
  더 근본적으로는 두 경로가 같은 실수를 반복하지 않도록 **null-safe 언박싱 헬퍼 1개로 통합**하는 편이 낫다(현재는 SKELETON 만 고쳐진 비대칭이 그대로 남아 있다).
  회귀 테스트 제안: `LabelControllerTest` 또는 `LabelSaveGuardsIT` 에
  `"좌표_배열_원소가_null이면_400"`(BBOX/POLYGON 각 1건, 신규·기존 id 양 분기) 추가.
  ※ `validateWithinBounds`(`:756-763`)는 이미 null 을 `continue` 로 흘리므로 추가 수정 불필요.

---

## 미해소 이월 확인

| 이슈 | 상태 | 확인 근거 |
|---|---|---|
| **C-ISSUE-23** (R7 손상 JSON 회귀 테스트 부재) | **미해소 이월** | `grep -rln "손상\|corrupt\|malformed" src/test/java` 결과 중 `pointsEqual`/`normalizePoints` 손상 `point_cn` 을 다루는 테스트 0건. `LabelServiceFullReplaceIntegrationTest#손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다` 는 **이력 조회 diff 파싱** 테스트로 별건. 동작 자체는 이번에 실동작으로 정상 확인(TC-LABEL-30 PASS)이나 회귀 가드는 여전히 없음 |

---

## 검증 중 생성/정리한 테스트 데이터

기존 데이터는 변경하지 않았고, 검증용으로 만든 것은 아래와 같이 정리했다.

| 항목 | 처리 |
|---|---|
| `ls_data_lbl` src 313·314 테스트 라벨(594·596·597·598) | API `items:[]` 로 전량 삭제 완료 |
| `ls_label` 마스터 21(`qa-c2-lbl`)·22(`qa-c2-race`) | API DELETE(soft delete, `use_yn='N'`) 완료 |
| `ls_auth_work_lock` `lck_id='qa-c2-lock-test'`(raw27) | `lck_stts_cd='RELEASED'`, `rmv_rsn='QA_C2_CLEANUP'` 로 해제 완료 |
| `ls_data_lbl_attr_val` atrb_vl_id=2 (TC-11 용) | 대상 라벨 삭제 시 애플리케이션이 동반 삭제 |
| src 303 잔존 라벨 592(`chg32`) | **잔존** — raw27 이 타 에이전트의 비식별 신고로 작업락 상태라 API 정리 불가. `lbl_id` 는 null 로 원복 |
| src 296(raw26, APPROVED) | 라벨 추가 후 `items:[]` 로 원상복구(라벨 0건). 단 `ls_mon_noti_acml` #31 및 export 재생성 트리거는 발생함 |
# C 클러스터 — part2b (경계 누락분 보정)

> **대상**: `docs/test-cases/C-marking-labeling.md` **152~166행 = TC-LABEL-91 ~ TC-LABEL-105 (15건)**
> 이전 3개 병렬 에이전트의 라인 구간 분할 경계에서 누락된 구간이다.
> **검증일**: 2026-08-01 · **회차**: 1차 · **코드 기준**: qa-0801 (56d30478, V158)
> **환경**: docker compose 풀스택 기동(backend :18081 `/api` · postgres · ai-server · mock-server · frontend) — 5컨테이너 모두 healthy 실측
> **주 근거**: `backend/src/main/java/kr/co/cudo/authoring/label/service/DeidentReportService.java`
> **검증 방식**: 실 HTTP 호출(curl) + DB 실측 + pg_locks/pg_stat_activity 관측 + 동시요청 재현. **코드·설정·테스트 파일 수정 0건, 빌드/테스트 실행 0건.**

---

## 0. 검증 환경·데이터

| 항목 | 값 |
|---|---|
| JWT | `JWT_SECRET` 컨테이너 실효값으로 HS256 자체 서명. `sub`=userNo, `channel`=INTERNAL. 역할은 BE 가 `LS_USER_ROLE` 에서 해석 |
| 계정 | REVIEWER=1001 · WORKER=2001 · WORKER=2002 · PORTAL_USER=3001 |
| 주 검증 영상 | **rawSn 27** (`DE_IDENT_YN='Y'`, `COMPLETED`, 비파생, WORKER 2001 LABELER 배정, 프레임 srcSn 301~305, 최신 SUCCEEDED procLog 존재) |
| 승인 영상 | **rawSn 26** (`LS_RAW_DATA_STATUS='APPROVED'`, WORKER 2001 배정, 프레임 296~300) — TC-LABEL-98 용 |
| 미배정 대조 | WORKER 2002 (raw 26·27 미배정) |
| JVM TZ | `-Duser.timezone=Asia/Seoul` (Dockerfile:42) 실측 — DB `LocalDateTime` 과 `ZoneId.systemDefault()` 기반 mtime 변환이 같은 축이라 TC-LABEL-105 시각 비교가 성립함을 사전 확인 |

**데이터 원복 확인**: 검증 종료 시점 `rawSn 26/27` 모두 `DE_IDENT_YN='Y'` · `DATA_STTS_CD='COMPLETED'` · 활성 작업락 0건 · 생성한 신고 4건(19~22) 전부 `RESOLVED` 로 종결. 임시 변경한 `LS_DEIDENT_PROC_LOG(proc_log_sn=26)` 의 `PROC_STTS_CD`/`DE_IDNTF_FILE_PATH_NM`/`RSPNS_DT` 원값 복원 완료. 컨테이너 `/tmp` 프로브 파일 삭제 완료. (변경된 것은 비식별 산출 파일의 **mtime** 뿐 — 외부 솔루션 재비식별 시뮬레이션이며 파일 내용·경로 불변.)

---

## 1. 판정 요약

| 판정 | 건수 |
|---|---:|
| PASS | **15** |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **15** |

**근거 드리프트**: 0건 — 카탈로그의 `file:line` 15건 전부 현재 코드와 일치(`DeidentReportService.java` 158-162 / 122 / 189-191 / 178-180 / 237-241 / 218-219 / 230-233 / 354-356 / 357-358 / 361 / 364-366 / 542-581 / 504·563-580, `DeidentReportRequest.java:19`).

> ⚠ 판정은 전건 PASS 지만 **결함 없음이 아니다** — 케이스가 단언하지 않는 인접 표면에서 이슈 3건(C-ISSUE-121~123)을 발견해 아래에 기록한다.

---

## 2. 케이스별 결과

| ID | 판정 | 근거 확인 | 실증 내용 |
|---|:--:|---|---|
| TC-LABEL-91 | PASS | [실동작] | `POST /v1/labels/301/deident-report` — `{"reason":""}` → **400** `INVALID_INPUT` "reason: 신고 사유는 필수입니다." / 공백만 `"   "` → 400 / 필드 자체 부재 `{}` → 400. 3가지 모두 컨트롤러 `@NotBlank`(`DeidentReportRequest.java:18`)가 차단. 서비스 백스톱 `requireReason`(`DeidentReportService.java:158-162`)은 두 진입점(`report`:119 · `reportByVideo`:148) **모두**에서 **인가 검사보다 먼저** 호출되며 `null`·`isBlank()` 를 동일 400 으로 수렴 — @Valid 우회(내부 직접 호출) 시에도 방어. 부작용(신고행·락) 0건 DB 확인 |
| TC-LABEL-92 | PASS | [실동작] | 1001자(`'가'×1001`) → **400** "reason: 신고 사유는 1000자 이하여야 합니다."(`@Size(max=1000)`). **경계 반증**: 정확히 1000자는 검증을 통과해 다음 단계(인가)로 진행됨을 확인(미배정 WORKER 로 호출 → 403 이 나옴 = 400 이 아님) → off-by-one 없음. DB 컬럼 `RSN VARCHAR(1000)` 과 상한 일치(절단·오버플로 없음) |
| TC-LABEL-93 | PASS | [실동작] | **IDOR 6종 반증**: ①미배정 WORKER 2002 → srcSn 301(raw27) **403** ②미배정 WORKER 2002 → srcSn 296(raw26) **403** ③무토큰 **401** ④PORTAL_USER 토큰 **403** ⑤영상 진입점 `POST /v1/videos/27/deident-report` (W2002) **403** ⑥`GET /v1/deident-reports`(REVIEWER 전용) W2001 **403**. 인가는 `LabelAccessGuard.verifyAndGet`(`:122`) → `existsByUserNoAndTaskTypeCdAndRawDataId(selfNo, 'LABELER', rawSn)` 로 **본인 LABELER 배정만** 통과. 403 응답 후 신고행·작업락·`DE_IDENT_YN` 변화 0건 DB 확인(부작용 없는 거부) |
| TC-LABEL-94 | PASS | [실동작] | rawSn 27 신고 접수(rprtSn=19) 후 **①같은 프레임 301 재신고 → 409** **②다른 프레임 302 재신고 → 409** **③영상 진입점(REVIEWER) 재신고 → 409** — 3경로 모두 `"이미 비식별 재처리 중인 영상입니다."` `CONFLICT`. `workLockService.isRawLocked(rawSn)` 선점검(`:189-191`)이 **프레임이 아니라 영상 단위**로 판정하므로 같은 영상의 다른 프레임을 우회 경로로 쓸 수 없음을 실증. 재신고 후 `LS_DEIDENT_REPORT` 추가행 0건 |
| TC-LABEL-95 | PASS | [실동작] | **FOR UPDATE 실증(핵심)**. psql 별도 세션에서 `BEGIN; SELECT … FROM ls_data_raw WHERE raw_sn=27 FOR UPDATE; pg_sleep(20);` 로 부모 RAW 행을 선점한 상태에서 신고 API 호출 → `pg_stat_activity` 에 백엔드 세션이 **`wait_event_type=Lock` / `wait_event=transactionid`** 로 대기, 그때의 `query` 가 `select ldr1_0.RAW_SN,…ldr1_0.DE_IDENT_YN,… ` = **SELECT 문**임을 관측. **일반 SELECT 는 MVCC 상 행락을 기다리지 않으므로 이는 `@Lock(PESSIMISTIC_WRITE)`(`VideoRepository.java:37-39`)가 read 시점에 실제로 걸린 증거**다. holder 롤백 후 요청 완료(`201`, elapsed **18.09초** = 대기 시간과 일치). 잠금은 `@Transactional("controlTransactionManager")`(readOnly 아님) 커밋까지 유지 |
| TC-LABEL-96 | PASS | [실동작] | **동시 8요청**(srcSn 301~305 5건 + 영상 진입점 3건, 전부 rawSn 27) 동시 발사 → **201 정확히 1건 / 409 정확히 7건**. DB: `LS_AUTH_WORK_LOCK` LOCKED 1행, `LS_DEIDENT_REPORT` 신규 1행(rprtSn=21), `DE_IDENT_YN='F'` 1회 전이. 부분상태·고아 락 0건. ※실경합에서 7건은 `isRawLocked` 선점검에서 409 가 났다 — TC-LABEL-95 의 부모행 `FOR UPDATE` 가 같은 rawSn 요청을 **직렬화**해 후행 tx 가 선행 커밋 결과(LOCKED)를 READ COMMITTED 로 관측하기 때문. 부분 유니크 인덱스 `ux_ls_auth_work_lock_raw_active (DATA_RAW_SN) WHERE LCK_TARGET_CD='RAW' AND LCK_STTS_CD='LOCKED'` 는 DB 에 실재하며(`pg_indexes` 확인) `DataIntegrityViolationException → 409` 변환(`:237-241`)과 함께 **최후 방어로 남아 있다**(이 엔드포인트 경유로는 도달 불가 = 심층방어 정상) |
| TC-LABEL-97 | PASS | [실동작] | rawSn 26 프레임에 개인정보 3필드를 사전 세팅(296·297 = 3필드 전부, 298 = `PRVC_INCL_YN` 1필드만, 299·300 = 전부 NULL) 후 신고 → **5개 프레임 전부 3필드 NULL** 확인. 로그 `privacyReset=5 privacyResetAudited=3`. 감사 이력 `LS_DATA_LBL_HSTRY` 에 **값이 있던 3프레임(296·297·298)만** 신규 3행 — `{"event":"PRIVACY_META_RESET","deidentReportSn":20,"changes":[]}`, `REG_ID=2001`(신고자), add/mdfcn/del=0. 이미 NULL 이던 299·300 은 감사행 없음(`findSrcSnsWithPrivacyMeta` 선별 정상 — 감사 잡음 억제). **파생본 캐스케이드 없음 반증**: 자식 rawSn 18/19/20 의 프레임 3필드 비NULL 건수 = 0/0/0 유지 → 카탈로그가 명시한 "대상 rawSn 프레임만, 기존 파생본 캐스케이드 없음(의도된 경계)" 그대로 |
| TC-LABEL-98 | PASS | [실동작] | **APPROVED 영상(rawSn 26) 신고 → `LS_MON_NOTI_ACML` 신규 1행: `{"frames":{"296":["META_UPDATED"]}}`, STTS=PENDING, EXPORT_RPRCS_YN='N'** — 기대값 `META_UPDATED` 정확 일치(구 `LABEL_DELETED` 아님). **음성 대조**: 같은 시각대에 신고한 **미승인 영상 rawSn 27**(`ASSIGNED`)은 `LS_MON_NOTI_ACML` 행 **0건** → `isReviewApproved` 게이팅(`:230-233`) 실동작 확인. 통지가 PENDING/`EXPORT_RPRCS_YN='N'` 로 대기하는 것은 "export 성공 후 발송" 확정 정책과 정합(신고 구간이라 export 보류) |
| TC-LABEL-100 | PASS | [실동작] | `POST /v1/deident-reports/19/resolve` — 토큰 없음 → **401** `UNAUTHORIZED`, 서명 위조 토큰 → **401**. Spring Security 가 서비스 도달 전 차단하며, `resolveManually` 진입부 `actor==null` 백스톱(`:354-356`)이 내부 호출 경로를 이중 방어 |
| TC-LABEL-101 | PASS | [실동작] | `rprtSn=999999` resolve → **404** "신고를 찾을 수 없습니다."(REVIEWER·WORKER 양쪽). `findById(...).orElseThrow`(`:357-358`) |
| TC-LABEL-102 | PASS | [실동작] | **IDOR**: WORKER 2002(raw27 미배정) → rprtSn 19(raw27) resolve → **403** "본인에게 배정되지 않은 영상입니다."(`accessGuard.verifyRawAccess`, `:361`). PORTAL_USER → **403**. REVIEWER 1001 → 통과(다른 케이스에서 200 확인). 403 후 신고 상태 OPEN·락 LOCKED·`'F'` 전부 불변 DB 확인 |
| TC-LABEL-103 | PASS | [실동작] | 이미 `RESOLVED` 인 rprtSn 18(raw34) resolve(REVIEWER) → **409** "이미 처리된 신고입니다."(`:364-366`). 별도로 TC-LABEL-104 시퀀스 중 이미 해소된 신고 재호출도 동일 409 재현 |
| TC-LABEL-104 | PASS | [실동작] | **산출물 gate 8변형 전수 반증** — mtime 을 신고 이후로 맞춰 **시간조건을 먼저 통과시킨 뒤**(즉 실패 원인이 산출물 판정 하나로 고정된 상태에서) 각 변형을 시험: ①최신 SUCCEEDED procLog 없음(`PROC_STTS_CD='FAILED'`) → **409** ②경로 blank(`''`) → **409** ③경로 NULL → **409** ④**18바이트 목 스텁**(`MOCK_DEIDENTIFIED\n`) → **409**(회귀 차단 확인) ⑤미존재 경로 → **409** ⑥유효 mp4 를 가리키는 **심볼릭 링크** → **409**(`NOFOLLOW_LINKS`) ⑦디렉터리 경로 → 거부. 전 변형에서 **fail-closed 확인**: `REPORT_STTS_CD='OPEN'`·`RESOLVED_DT=NULL`·작업락 `LOCKED`·`DE_IDENT_YN='F'` 전부 유지(트랜잭션 롤백). 로그 `[DeidentReport] resolve blocked — deident artifact not verified rawSn=27` + 응답에 **내부 경로 미노출**(CWE-209). 판정이 `DeidentArtifactIntegrity.isValidVideoArtifact` **단일 지점**(`:553`)에 위임돼 있고 서비스 안에 자체 판정 재구현이 없음(정적 확인). ⚠ 잔여 표면 1건 → **C-ISSUE-122** |
| TC-LABEL-105 | PASS | [실동작] | **시간조건 4점 실측**(신고 rprtSn 21/22, 파일 mtime 을 `touch -d @epoch` 로 정밀 조작): ①procLog `RSPNS_DT`(19:09) · mtime(23:21) **둘 다 신고(23:40) 이전** → **409** ②mtime = 신고−90초 → **409** ③**mtime = 신고−61초(관용 경계 바로 밖) → 409** ④**mtime = 신고−30초(60초 관용 안쪽) → 200** — `CLOCK_SKEW_TOLERANCE_SECONDS=60`(`:504`)이 mtime 비교에만 적용됨을 경계 양측에서 확증. ⑤**procLog 분기 독립 확인**: mtime 을 신고 이전으로 둔 채 `RSPNS_DT = 신고+10초` 로 올리면 **200**(`procTime.isAfter(reportTime)` 엄격 비교, `:563-565`) → 두 조건이 OR 로 각각 독립 동작. 409 시 전건 fail-closed(OPEN·락·`'F'` 유지) |

---

## 3. 테스트 커버 대조

`_raw/test-baseline.md` 기준 **backend/frontend/ai-server 실패 0건** — 아래 자동 테스트는 전부 통과 상태다.

| TC | 커버 테스트 (`파일:@DisplayName`) |
|---|---|
| 91 | `DeidentReportServiceTest`:`reason_누락_빈문자열은_INVALID_INPUT_400` · `마킹단계_rawSn_신고_사유가_비면_400` / `DeidentReportControllerTest`:`사유_누락_400_INVALID_INPUT` · `마킹단계_rawSn_신고_사유_누락_400` |
| 92 | `DeidentReportControllerTest`:`사유_1000자_초과_400` |
| 93 | `DeidentReportControllerTest`:`WORKER_타인_영상_신고시_403_FORBIDDEN` · `마킹단계_rawSn_신고_WORKER_타인_영상_403` / `DeidentReportServiceTest`:`마킹단계_rawSn_신고는_본인배정_아닌_WORKER를_403으로_차단한다` |
| 94 | `DeidentReportServiceTest`:`이미_잠금_영상_신고시_CONFLICT_409_+_저장_없음_+_잠금_재획득_없음` · `이미_잠금_영상_신고_거부_시_부작용이_없다` · `마킹단계_rawSn_신고_이미_잠금이면_409` / `DeidentReportControllerTest`:`이미_잠금_영상_재신고시_409_CONFLICT` |
| 95 | **직접 단언 테스트 없음** — `findByRawSnForUpdate` 사용 여부만 간접 커버(모킹 기반). Testcontainers 실 DB 에서 잠금 대기를 관측하는 테스트는 부재 → 본 검증의 pg 관측이 유일한 실증 |
| 96 | `DeidentReportServiceTest`:`동시_신고_unique_위반시_409` (모킹으로 `DataIntegrityViolationException` 주입 — 실경합 재현은 아님) |
| 97 | `DeidentReportServiceResetIT`:`신고_report실행후_커밋조회시_프레임개인정보3필드_NULL_및_RAW_DE_IDENT_YN_F_실제반영_라벨삭제` · `개인정보_3필드_리셋이_감사_가능하게_기록된다` / `DeidentReportServiceTest`:`비식별누락신고_처리후_프레임_개인정보값_초기화` |
| 98 | `DeidentReportServiceTest`:`APPROVED_영상_신고시_TASK_MODIFIED_통지_발행` · `미승인_영상_신고시_통지_미발행` · `마킹단계_rawSn_신고도_APPROVED_영상이면_TASK_MODIFIED_통지가_발행된다` |
| 100 | `DeidentReportServiceTest`:`미인증_사용자_resolve_요청시_401` / `DeidentReportControllerTest`:`resolve_인증_없음_401` |
| 101 | `DeidentReportServiceTest`:`존재하지_않는_신고_resolve_요청시_NOT_FOUND_404` |
| 102 | `DeidentReportServiceTest`:`타인_배정_영상_신고_WORKER가_resolve_요청시_403` · `REVIEWER는_모든_신고_resolve_가능` / `DeidentReportControllerTest`:`타인_배정_영상_신고_WORKER_resolve_403` |
| 103 | `DeidentReportServiceTest`:`OPEN이_아닌_신고_resolve_요청시_409` / `DeidentReportControllerTest`:`OPEN이_아닌_신고_재_resolve_409` |
| 104 | `DeidentReportServiceTest`:`비식별파일_없이_resolve시_409_거부되고_deIdntfYn은_F유지_report는_OPEN유지` · `procLog_기록없이_resolve시_거부된다` · `18바이트_스텁으로는_비식별_신고가_해제되지_않는다` · `시그니처가_없는_파일로는_복원되지_않는다` · `해제_실패시_deIdntfYn_은_F_로_유지된다` / `DeidentArtifactIntegrityTest` 11건 |
| 105 | `DeidentReportServiceTest`:`신고이전_비식별본만_존재시_resolve_거부된다` · `신고이후_파일교체시_resolve_성공한다` · `신고이후_자동재비식별_procLog가_있으면_성공한다` |

> **커버 갭 2건(참고)**: ①TC-LABEL-95 의 실 DB 행 잠금 검증 부재 ②TC-LABEL-96 의 실 동시요청 재현 부재(모킹 대체). 둘 다 본 회차 실동작 검증으로 보완했으나, 회귀 가드로는 남아 있지 않다.

---

## 4. 이슈

### [C-ISSUE-121] TC-LABEL-93 / TC-LABEL-101 — 신고·resolve 진입점의 리소스 존재 오라클(인가보다 조회가 먼저)
- **심각도**: LOW
- **기대 동작(기대효과)**: 인가되지 않은 사용자에게는 **리소스 존재 여부 자체를 알려주지 않아야** 한다. 같은 프로젝트의 영상 단위 진입점 `reportByVideo` 는 이 원칙을 명시적으로 채택했다 — `DeidentReportService.java:150-151` 주석 *"영상 조회보다 먼저 평가해 미인가자에게 존재 여부를 흘리지 않는다"*. 응답 코드 차이가 상태를 알려주는 오라클이 되면 안 된다는 것은 이 프로젝트가 이미 신고 게이트 응답코드 설계(CWE-209)에서 채택한 원칙이기도 하다.
- **현재 동작(이슈 내용)**: **프레임 단위 신고**와 **resolve** 두 진입점은 조회를 먼저 하고 인가를 나중에 해, 미인가 사용자에게 404/403 이 갈린다.
  ```java
  // DeidentReportService.java:118-125  (report — srcSn 경로)
  public Long report(Long srcSn, String reason, TokenClaims actor) {
      requireReason(reason);
      LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);   // ← 내부에서 findById 먼저(404), 그 뒤 배정검사(403)
  // LabelAccessGuard.verifyAndGet:  findById(srcSn).orElseThrow(NOT_FOUND)  →  이후 WORKER 배정검사 FORBIDDEN

  // DeidentReportService.java:357-361  (resolveManually)
  LsDeidentReport report = reportRepository.findById(rprtSn)
          .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));
  accessGuard.verifyRawAccess(report.getRawSn(), actor);        // ← 인가는 조회 뒤
  ```
  실측(WORKER 2002 = raw26·27 미배정):
  | 요청 | 응답 |
  |---|---|
  | `POST /v1/labels/301/deident-report` (존재하는 타인 프레임) | **403** |
  | `POST /v1/labels/999999/deident-report` (미존재 프레임) | **404** |
  | `POST /v1/videos/27/deident-report` (존재하는 타인 영상) | **403** |
  | `POST /v1/videos/999999/deident-report` (미존재 영상) | **403** ← 오라클 없음(정상 설계) |
  | `POST /v1/deident-reports/19/resolve` (타인 영상 신고) | **403** |
  | `POST /v1/deident-reports/999999/resolve` (미존재 신고) | **404** |
- **재현/확인 경로**:
  ```bash
  # 403 = 존재 / 404 = 미존재 로 SRC_SN·RPRT_SN 순차 열거 가능
  for s in 296 301 999999; do
    curl -s -o /dev/null -w "$s %{http_code}\n" -X POST \
      "http://localhost:18081/api/v1/labels/$s/deident-report" \
      -H "Authorization: Bearer $WORKER2002" -H 'Content-Type: application/json' \
      -d '{"reason":"probe"}'
  done
  ```
- **영향**: **CWE-204(관측 가능한 응답 불일치) / CWE-639 인접**. 인증된 내부 WORKER 가 자신에게 배정되지 않은 프레임·신고의 **존재 범위(순차 PK 구간)** 를 열거할 수 있다. 라벨/픽셀 본문은 여전히 차단되므로 정보 유출량은 "어떤 ID 가 실재하는가"에 국한되며, 미인증자는 401 로 막혀 도달하지 못한다 → 실질 위험 LOW. 다만 **같은 파일 안에서 두 진입점의 정책이 갈리는 비정합**이라 이후 유지보수 때 어느 쪽이 정본인지 혼동을 만든다.
- **수정 방향(제안)**: `report(srcSn,…)` 는 `LabelAccessGuard.verifyAndGet` 이 조회·인가를 함께 수행하는 구조라 순서를 바꾸려면 "srcSn→rawSn 해석 실패도 FORBIDDEN 으로 수렴" 같은 정책 결정이 선행돼야 한다(단, 정상 사용자에게 404 가 403 으로 바뀌면 화면 안내가 나빠진다). `resolveManually` 는 상대적으로 단순 — `findById` 결과가 없을 때도 미인가 role 이면 403 을 반환하도록 정렬 가능. **어느 쪽이든 "존재 오라클을 감수한다"를 명시 결정으로 고정**하는 편이 낫다(현 상태는 결정이 아니라 우연으로 보인다). ⚠ 구현하지 않음 — 사용자 정책 확정 대상.

### [C-ISSUE-122] TC-LABEL-104 — MPEG-TS 시그니처 판정을 만족하는 비-영상 파일이 비식별 산출물 gate 를 통과
- **심각도**: LOW
- **기대 동작(기대효과)**: `DeidentArtifactIntegrity.isValidVideoArtifact` 는 "실제 비식별 영상이 회수됐다"를 보증하는 **단일 판정 지점**이다. 이 판정 통과가 곧 `DE_IDNTF_YN 'F'→'Y'` 복원이고, 그 복원은 **라벨 조회·프레임 이미지·영상 스트리밍·export 게이트를 한꺼번에 여는** 지점이다(클래스 javadoc 이 스스로 명시). 따라서 비-영상 파일이 통과하면 안 된다(CWE-345).
- **현재 동작(이슈 내용)**: **2,000바이트 전부 `0x47`('G') 인 텍스트 파일**이 판정을 통과해 resolve 가 **200** 으로 성공했고, `DE_IDNTF_YN` 이 `'F'→'Y'` 로 복원되고 작업락이 해제됐다(실측).
  ```java
  // DeidentArtifactIntegrity.java:184-192
  private static boolean hasMpegTsSyncPattern(byte[] header) {
      for (int i = 0; i < TS_SYNC_REPEATS; i++) {          // 3회
          int offset = i * TS_PACKET_SIZE;                  // 0 / 188 / 376
          if (offset >= header.length || (header[offset] & 0xFF) != 0x47) return false;
      }
      return true;    // ← 오프셋 0·188·376 이 모두 0x47 이면 통과. 그 외 바이트는 보지 않는다
  }
  ```
  실측 응답: `{"success":true,...}` **HTTP=200** → DB `LS_DATA_RAW(raw_sn=27).DE_IDENT_YN='Y'`, `LS_AUTH_WORK_LOCK` RELEASED, 신고 RESOLVED.
  **반증 대조**: javadoc 이 표적으로 삼은 *현실적* 오탐 후보인 access 로그(`GET /v1/... ` 200줄, 11,519바이트)는 offset 188=`0x74`, 376=`0x73` 이라 **정상 거부**됨을 확인 → 강화 자체는 유효하며, 남은 것은 "동일 바이트 반복" 같은 퇴화 입력이다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend sh -c 'head -c 2000 /dev/zero | tr "\0" "G" > /tmp/x.ts; touch /tmp/x.ts'
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "UPDATE ls_deident_proc_log SET de_idntf_file_path_nm='/tmp/x.ts' WHERE data_raw_sn=<신고영상>;"
  curl -X POST http://localhost:18081/api/v1/deident-reports/<OPEN신고>/resolve -H "Authorization: Bearer $REVIEWER"
  # → 200, DE_IDENT_YN 'F'→'Y'
  ```
- **영향**: **CWE-345(불충분한 데이터 진정성 검증)**. 다만 이 경로를 실제로 밟으려면 ①`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 을 쓸 수 있거나 ②그 경로에 파일을 쓸 수 있어야 한다 — 둘 다 가능한 주체는 이미 유효한 mp4 헤더를 위조할 수도 있으므로 **공격 난이도를 낮추지는 않는다**. 실질 위험은 "외부 비식별 솔루션이 반복 바이트 패딩 산출물을 남기는" 우발 케이스로 한정되며 현실성이 낮다. TC-LABEL-104 가 단언한 **18바이트 스텁 회귀 차단은 충족**되므로 케이스 판정은 PASS 유지.
- **수정 방향(제안)**: MPEG-TS 분기에 **다양성 조건**을 덧붙이는 정도로 충분하다 — 예: 선두 377바이트의 서로 다른 바이트 값이 N종 미만이면 거부, 또는 TS 패킷 헤더의 PID/adaptation 필드가 전부 동일한 상수인 퇴화 패턴 배제. 관대함(오탐 거부 = 운영 사고)이 이 클래스의 설계 원칙이므로 **엄격 파싱은 도입하지 말 것**. ⚠ 구현하지 않음.

### [C-ISSUE-123] TC-LABEL-90/91 인접 — Swagger 문서가 폐기된 "라벨 삭제" 정책을 그대로 광고 (문서/코드 드리프트)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 2026-07-27 사용자 확정으로 **비식별 신고는 라벨을 삭제하지 않고 보존**한다(구 "전체 라벨 스냅샷 후 전량 삭제 + `SAVE_REASON='DEIDENT_REPORT'`" 폐기, TC-LABEL-90·99 에 반영). 이 API 를 소비하는 FE·관제·외부 통합자가 보는 **OpenAPI 설명이 실제 동작과 일치**해야 한다.
- **현재 동작(이슈 내용)**: 서비스 본체는 정책 반전이 반영돼 있으나(`DeidentReportService.java:197-205` 주석 + 실제 삭제 코드 부재, 실동작에서도 라벨 보존 확인), **컨트롤러의 Swagger 문구는 구 정책 그대로**다.
  ```java
  // DeidentReportController.java:44-48  @Tag(description = …)
  "신고 즉시 영상이 잠기고(LOCKED_FOR_REDEIDENT) 현재 작업(영상 전체 라벨)을 " +
  "복원 가능 스냅샷 기록 후 삭제한다. 재처리는 외부 솔루션 수동 비식별화 흐름으로 진행되며, "

  // DeidentReportController.java:82-85  @Operation(description = …)  — POST /v1/labels/{srcSn}/deident-report
  "신고 시 해당 영상 전체 라벨을 복원 가능 스냅샷으로 기록 후 삭제하고 영상을 잠근다. "
  ```
  실동작 반증: rawSn 26/27 신고 후 로그가 `labelsPreserved=true` 를 남기고 `LS_LABEL_VERSION` 신규 스냅샷 0건 — **문서만 틀렸다**. 참고로 영상 단위 진입점(`:107-111`)의 설명은 최신 정책으로 갱신돼 있어 **같은 컨트롤러 안에서 두 엔드포인트 설명이 서로 모순**된다.
- **재현/확인 경로**: `GET /api/v3/api-docs` 또는 Swagger UI 에서 `DeidentReport` 태그 설명과 `POST /v1/labels/{srcSn}/deident-report` 설명 확인 → "스냅샷 기록 후 삭제" 문구 노출.
- **영향**: 기능·보안 결함은 아니나 **API 계약 오도**다. 이 설명을 믿는 소비자는 신고 후 라벨이 사라진다고 가정해 방어적 재조회·재작성 로직을 넣거나(불필요), 반대로 "삭제됐을 테니 안전하다"고 오판할 수 있다. 또한 `CLAUDE.md` 의 문서 동기화 규칙(기능 변경 시 같은 커밋에서 문서 갱신) 위반 잔재다.
- **수정 방향(제안)**: `DeidentReportController` 의 `@Tag(description)`(`:44-48`)과 srcSn 경로 `@Operation(description)`(`:82-85`)을 현행 정책으로 교체 — "라벨은 **보존**하며 신고 구간 동안 라벨 조회가 412 로 차단되고, resolve 시 보존된 라벨을 그대로 재사용한다 / 부수효과 = 작업락 + `DE_IDNTF_YN='F'` + 개인정보 3필드 리셋 + APPROVED 영상 `TASK_MODIFIED(META_UPDATED)` 통지". 이미 최신인 영상 단위 진입점(`:107-111`) 문구를 기준으로 정렬하면 된다. ⚠ 구현하지 않음.

---

## 5. 확정 정책 대조 (재보고 금지 항목 준수 확인)

| 항목 | 본 구간에서의 관측 | 처리 |
|---|---|---|
| **★1 신고 게이트 = 자기 rawSn 행 하나** | TC-LABEL-97 에서 파생본(18/19/20)에 개인정보 리셋이 전파되지 않음을 실측 | **정상**(확정 정책의 명시적 경계) — 결함 미보고 |
| **★1 귀결 '파생 경유 열람'** | 본 구간 케이스가 아님 | 미보고 |
| UNCERTAINTIES #3 TASK_COMPLETED payload | TC-LABEL-98 은 `TASK_MODIFIED` 축이라 무관. 관측된 `changed_items` 페이로드는 DB 실측 기반이며 self-fill 없음 | 미보고 |

**self-fill 결함**: 본 구간에서 **0건**. 비식별 산출물 판정은 전부 `LS_DEIDENT_PROC_LOG` 에 적재된 실제 값 + 파일시스템 실측(크기·시그니처·mtime)에서 오며, 코드가 값을 만들어 채우는 경로가 없다(경로 문자열 조합·추측도 없음 — `DE_IDNTF_FILE_PATH_NM` 을 그대로 읽는다). resolve 실패 시에도 하드코딩 기본값으로 통과시키지 않고 fail-closed 로 수렴함을 8변형에서 확인.
# C 클러스터 part3 — TC-LABEL 중간부(라벨 마스터 join · 온라인 오토라벨 · DTCT_TYPE_CD 매칭)

- **담당 범위**: `docs/test-cases/C-marking-labeling.md` offset 120~167 (TC-LABEL-46 ~ TC-LABEL-80). 폐기 케이스(TC-LABEL-70)는 검증 대상 제외. TC-LABEL-90~106(비식별 신고)은 part4 담당이라 본 문서에서 다루지 않음.
- **검증 일시**: 2026-08-01 23:15~23:25 KST
- **스택**: `klid-backend`(:18081/api, Flyway **V158** 적용 확인) · `klid-ai-server`(:19300, `AI_MOCK_MODE=false` 이나 **모델 가중치 미탑재 → mock 폴백**) · `klid-postgres`(`public` 스키마) · `klid-mock-server`(:9400)
- **사용 토큰**: `POST /v1/dev/tokens` — REVIEWER(sub=1001) · WORKER(sub=2001)
- **사용 데이터**: rawSn=26(srcSn 296~300) / 27(301~305) / 31(329~330) / 33(311~312) — 모두 WORKER 2001 배정. 라벨 마스터 활성 매핑 6종(`person·car·bicycle·motorcycle·bus·truck`), 미매핑 활성 3종(`fire·smoke·water`)

> ⚠ **본 검증에서 수행한 DB 변경**은 모두 원복 완료: ①`ls_auth_work_lock` 임시 락 1행 INSERT→DELETE(잔여 0) ②`ls_data_raw(raw_sn=33).de_ident_yn` `Y`→`F`→`Y` 복원 확인 ③테스트 라벨 `c3-tmp`(lbl_id=20) 생성 후 soft delete. 코드·설정·테스트 파일은 일절 수정하지 않았음.

---

## 판정 표

| TC ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-LABEL-46 | PASS | [실동작] `POST /v1/manage/labels {"color":"#ffffff"}` → **400** `INVALID_INPUT` `"color 는 대문자 hex (#RRGGBB) 형식이어야 합니다."` | `LabelMasterRequest.java:33` `@Pattern(^#[0-9A-F]{6}$)` — 라인 일치 |
| TC-LABEL-47 | PASS | [실동작] `type:"FOO"` → **400** `"type 은 BBOX/POLYGON/POINT/SKELETON 중 하나여야 합니다."` | `LabelMasterRequest.java:37` 일치 |
| TC-LABEL-48 | PASS | [실동작] `sortNo:-1` → **400** `"sortNo 는 0 이상이어야 합니다."` | `LabelMasterRequest.java:40` 일치 |
| TC-LABEL-49 | PASS | [실동작] `PUT /v1/manage/labels/999999` → **404** `NOT_FOUND` `"라벨을 찾을 수 없습니다."` | `LabelMasterService.java:100-101` 일치 |
| TC-LABEL-50 | PASS | [실동작] lbl_id=20(`c3-tmp`)을 `" PERSON "` 로 수정 → **409** `CONFLICT`. 같은 이름(`c3-tmp`)으로 수정 → **200**(자기 제외 동작) | `LabelMasterService.java:104-105` + `LsLabelRepository.java:42-47` `LOWER(TRIM())` 정규화 비교 — 대소문자·공백 무시 근사중복 실증 |
| TC-LABEL-51 | PASS | [실동작] `DELETE /v1/manage/labels/20` → **204**, DB `select use_yn from ls_label where lbl_id=20` → **`N`**(행 존속 = hard delete 없음) | `LabelMasterService.java:190-195` 일치 |
| TC-LABEL-52 | PASS | [실동작] WORKER 토큰 `POST /v1/manage/labels` → **403** `FORBIDDEN` `"권한이 없습니다."` | `LabelMasterController.java:82-83` `@PreAuthorize("hasRole('REVIEWER')")` 일치 |
| TC-LABEL-53 | PASS | [실동작] WORKER 토큰 `GET /v1/manage/labels` → **200**, `GET /v1/manage/labels/detect-candidates` → **200**(mapped=true/false 동반) | `LabelMasterController.java:57` 일치 |
| TC-LABEL-54 | PASS | [정적] `LabelMasterService.java:152-154` — `cocoLabel == null \|\| isBlank()` 시 `Optional.empty()` **조기 반환, repository 미호출**. 근거 라인 표기 `:151-156` 와 실제(151-157) 사실상 일치 | 단위테스트 커버(baseline 전량 통과) |
| TC-LABEL-55 | PASS | [실동작] DB 직접 `INSERT ls_label(dtct_type_cd='person', use_yn='Y')` → **`duplicate key value violates unique constraint "uk_ls_label_dtct_type"`**. 인덱스 실측 `UNIQUE btree (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` | `LsLabelRepository.java:84` `findByDtctTypeCdAndUseYn` → `Optional` 반환이 안전(NonUniqueResult 불가). 앱단 선검증(409)도 실동작 확인: `dtctTypeCd:"person"` 중복 생성 → **409 `"이미 사용 중인 검출 클래스 매핑입니다."`** |
| TC-LABEL-60 | PARTIAL | [실동작] `POST /v1/frames/296/autolabel` → **200** `{srcSn:296, detectedCount:0, savedCount:0, labels:[]}`. **미저장 정책은 실증**: 약 50회 호출 후에도 `ls_data_lbl` (rawSn=26) **0건**. 단 **좌표 반환·`lblSn=null` 은 미확인** — ai-server 가 `weights_missing` mock 이라 검출 0건 | 미검증분은 `AutolabelOnlineServiceTest:132 "온라인_오토라벨은_DB에_저장하지_않고_좌표만_반환한다"` 로 커버(baseline 통과). 근거 라인 `:206-280` 일치 → **C-ISSUE-44** |
| TC-LABEL-61 | PASS | [실동작] WORKER(2001) 가 타인(2002) 배정 프레임 `srcSn=75` 호출 → **403** `FORBIDDEN` `"본인에게 배정되지 않은 영상입니다."`. ai-server 호출 로그 없음 | `AutolabelOnlineService.java:210` `accessGuard.verifyAndGet` 최우선 — 라인 일치 |
| TC-LABEL-62 | PASS | [실동작] `ls_auth_work_lock`(RAW, rawSn=33, LOCKED) 삽입 후 `srcSn=311` 호출 → **409** `CONFLICT` `"작업이 잠긴 영상입니다."`. 락 제거 후 200 복귀 | `AutolabelOnlineService.java:411-414` 라인 일치. **추가 실증**: 락 + `de_ident_yn='F'` 동시 → **409**(락 우선), 락 해제 + `'F'` 만 → **412** `PRECONDITION_FAILED` — javadoc(:404-406)의 "작업락 먼저" 순서 규약 그대로 |
| TC-LABEL-63 | PASS | [실동작] 동일 프레임(296) 40 동시요청 → **200×1 / 409×39**. 직후 단건 재요청 **200** = `finally` in-flight 해제 확인. 502(AI실패)·429(bulkhead) 경로 뒤에도 재요청 200 확인 | `AutolabelOnlineService.java:217-219`, `:277-279` 라인 일치 |
| TC-LABEL-64 | PASS | [실동작] `{"classes":["dog","zebra","toothbrush"]}` → **200** `detectedCount:0` + message `"검출할 수 있는 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해 주세요."`. **ai-server `POST /infer/yolo/track` 누적 호출수 변화 0** (15→15) = ai 미호출 실증. 백엔드 로그 `[Autolabel] no mapped detect classes srcSn=296 rawSn=26 requested=3` | `:228-235`, `:457-461` 라인 일치. `zebra`/`toothbrush` 는 유효 COCO 이지만 **마스터 미매핑**이라 차단됨 = allowlist 축이 COCO 가 아니라 `LS_LABEL.DTCT_TYPE_CD` 임을 실증 |
| TC-LABEL-65 | PASS | [실동작] `{"classes":["person","hack","dog","PERSON"," car "]}` → 백엔드 WARN 3건 `drop unmapped detect class=hack` / `=dog` / `=PERSON`. `person`·`" car "`(trim 후 `car`) 만 통과 → ai-server 1회 호출 | `:465-475` 라인 일치. trim 정규화(`c.trim()`)·로그 위조 방지(`LogSanitizer`) 동작 확인 |
| TC-LABEL-66 | PASS | [실동작] 위 65·64 조합으로 **FE 요청이 그대로 통과하는 경로 없음** 확인 — 미매핑 요청은 전량 drop, 전부 미매핑이면 ai 미호출. `callYolo(..., effectiveClasses, ...)`(`:239`)가 원본 `classes` 가 아닌 재구성 리스트만 전달 | `:457-476` 라인 일치. ⚠ 응답측(ai-server → BE) 재검증은 없음 → 방어심층 관점 관찰 **C-ISSUE-41**(정책 위반 아님) |
| TC-LABEL-67 | PASS | [실동작] ai-server 실효 mock(`weights_missing`) 상태에서 **200 + `detectedCount:0` + `labels:[]` + message `"AI 모델 미로드 — 결과 신뢰 불가"`**. 백엔드 로그 `[Autolabel] mock response — skip detection srcSn=296 source=mock reason=weights_missing`. ai-server 로그 `[DETECT:yolox][MOCK] returning mock track ... reason=weights_missing` | `:246-251` 라인 일치. 좌표 미반환 = 학습데이터 오염 차단 실증 |
| TC-LABEL-68 | PARTIAL | [정적] `DetectionBoxNormalizer.java:51-53` 개수≠4 → `IllegalArgumentException` → `AutolabelOnlineService.java:565-570` 에서 `INVALID_INPUT`(400) 로 **all-or-nothing** 승격(부분 반환 없음). 실동작 미도달(mock 단락) | 테스트 커버: `DetectionBoxNormalizerTest:106 "좌표개수가_4개가_아니면_거부한다"`, `AutolabelOnlineServiceTest:360 "형식위반은_여전히_all_or_nothing..."`(baseline 통과). 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-69 | PARTIAL | [정적] `DetectionBoxNormalizer.java:54-58` — **`Double.isFinite` 가드가 clamp 이전**에 위치(51~58 → 59~64). `NaN<0=false` 로 음수검사를 통과하던 회귀가 구조적으로 차단됨 | 테스트: `DetectionBoxNormalizerTest:115 "NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지"`, `AutolabelOnlineServiceTest:325/336`. 라인 일치 → **C-ISSUE-44** |
| ~~TC-LABEL-70~~ | — | 폐기 케이스(2026-07-30, 좌표정책 반전) — 검증 대상 아님. 대체 TC-137/138 은 본 담당 범위 밖 | `AutolabelOnlineService.validateBbox` 부재 확인(Grep 무결과) = 폐기 사유 실측 정합 |
| TC-LABEL-71 | PARTIAL | [정적] `:258-264` — `detections.isEmpty()` 시 `reCheckLock(rawSn)` 후 빈 결과. 실동작에서는 mock 분기(`:246-251`)가 **더 앞에서** 단락해 이 분기에 도달하지 못함 | 테스트: `AutolabelPolygonServiceTest:289 "YOLO_박스0개면_SAM호출없이_빈결과_반환한다"`, `AutolabelOnlineServiceTest:415`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-72 | PARTIAL | [정적] `:296-301` `limit=min(detected, maxBoxes)`, `truncated=detected>maxBoxes`, `buildPolygonMessage`(:370-387) 로 고지. 설정 상한은 `ConfigKeys` 범위 `[1,100]` 강제(`AUTOLABEL_POLYGON_MAX_BOXES`)라 무제한 설정 불가 | 테스트: `AutolabelPolygonServiceTest:221`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-73 | PARTIAL | [정적] `:303` 단일 wall-clock deadline, `:313-319` 잔여 예산 ≤0 시 `truncated=true` + break(부분 반환). `callSam`(:522-524)이 `min(remaining, 60s)` 로 개별 block 상한 | 테스트: `AutolabelPolygonServiceTest:344`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-74 | PARTIAL | [정적] `:321-348` 박스별 try/catch — `seg==null \|\| polygon==null \|\| mock` 이면 `skipped++` + WARN, 성공분만 반환. `:370-387` `anyExcluded = anyMock \|\| skipped>0` 로 **비-mock 실패도 고지** | 테스트: `AutolabelPolygonServiceTest:238/257/272`. 근거 라인 `:370-380` → 실제 메서드 370-387(경미 드리프트) → **C-ISSUE-44** |
| TC-LABEL-75 | PARTIAL | [정적] `:338-343` `if (e.getErrorCode()==TOO_MANY_REQUESTS) throw e;` — 스킵 흡수 전에 즉시 전파. `:529-533` `callSam` 의 `BulkheadFullException` → 429 매핑. **간접 실증**: 같은 bulkhead(`aiOnline`, `max-concurrent-calls=4`)가 BBOX 경로에서 429 를 실제로 반환(TC-77) | 테스트: `AutolabelPolygonServiceTest:318 "폴리곤_배치중_bulkhead429는_즉시_전파되고_삼켜지지_않는다"`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-76 | PARTIAL | [정적] `:308-312` 루프 매 반복 `requireNotBlocked(rawSn)`, `:351-352` 응답 조립 직전 `reCheckLock`. **게이트 자체는 실동작 실증**: 락→409 / `'F'`→412 (TC-62 참조). 폴리곤 배치 *중간* 진입은 mock 때문에 미도달 | 테스트: `AutolabelPolygonServiceTest:303 "폴리곤_배치중_작업락걸리면_409로_차단한다"`, `AutolabelOnlineServiceTest:169/185`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-77 | PASS | [실동작] 14개 서로 다른 프레임 × 3회 = 42 동시요청 → **200×4 / 409×29 / 429×9**. `application.yml:664-666` `aiOnline.max-concurrent-calls=4`, `max-wait-duration=0`(즉시 거부) | `:499-503` 라인 일치. bulkhead 가 실제로 fail-fast 함을 실증 |
| TC-LABEL-78 | PASS | [실동작] `docker pause klid-ai-server` 상태에서 호출 → **502** `{"success":false,"message":"YOLO 오토라벨 호출 실패","errorCode":"EXTERNAL_API_ERROR"}` — **스택·내부경로·URL 미노출**. 서버 로그에만 `err=Timeout on blocking read for 70000000000 NANOSECONDS`(sanitize 적용). unpause 후 즉시 200 복귀 = in-flight 락 해제 확인 | `:504-509` 라인 일치. CWE-209 방어 실증 |
| TC-LABEL-79 | PASS | [실동작] `confThreshold:0.9` → **400** `"인식 민감도는 0.80 이하여야 합니다."`, `0.1` → **400** `"인식 민감도는 0.25 이상이어야 합니다."` | `AutolabelRequest.java:39-40` 라인 일치 |
| TC-LABEL-80 | PASS | [실동작] `classes` 101개 → **400** `"클래스는 최대 100개까지 지정할 수 있습니다."`. 추가 반증: 원소 51자 → **400** `"classes[0]: 클래스명이 너무 깁니다."`(원소 단위 `@Size(max=50)` 도 실효) | `AutolabelRequest.java:36-37` 라인 일치 |

### 집계 (담당 30건 — 폐기 TC-70 제외)

| PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|
| 20 | 0 | 10 | 0 | 0 | 0 |

- PARTIAL 10건(TC-60·68·69·71~76)은 **전부 동일 원인**: ai-server 모델 가중치 미탑재 → mock 단락으로 검출 결과 의존 분기에 실행이 도달하지 못함. 정적 대조 + 단위테스트(baseline 전량 통과) 로만 뒷받침되어 **거짓 PASS 를 피하기 위해 PARTIAL 로 낮춤**.
- **근거 file:line 드리프트**: 실질 드리프트 없음. 경미 2건 — TC-54 `:151-156`(실제 151-157), TC-74 `:370-380`(실제 370-387). 카탈로그 정정 불요 수준.

---

## 이슈

### [C-ISSUE-41] TC-LABEL-66 — ai-server 응답 클래스명은 매핑 화이트리스트로 재검증되지 않는다(방어심층 공백)
- **심각도**: LOW
- **기대 동작(기대효과)**: `resolveDetectClasses` 는 "FE 요청 불신"을 구현해 **요청측**을 재구성한다(실동작 PASS). 같은 서비스가 좌표에 대해서는 "외부(ai-server) 응답 불신"을 명시하고 정규화·거부까지 하는데(`normalizeDetections`, `validatePolygonPoints` javadoc `:543,:582`), **클래스명(label)** 만은 응답을 그대로 신뢰한다. 대칭을 맞추면 ai-server 버전 스큐·회귀로 필터가 무력화돼도 미매핑 클래스가 화면까지 흘러가지 않는다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:423-431 toItems()` 가 응답 detection 을 그대로 아이템화한다. 매핑 조회는 하지만 **미매핑이면 차단이 아니라 `labelId=null` 로 통과**시킨다.
  ```java
  for (YoloResponse.Detection d : detections) {
      Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);
      items.add(new AutolabelResponse.Item(
              null, labelId, d.label(), d.points(), clampScore(d.score()), d.trackId()));
  }
  ```
  폴리곤 경로 `:334-337` 도 동일. 현재는 ai-server 가 `yolo.py:44-57 _apply_class_filter` 로 한 번 더 걸러 실피해가 없다(`if not classes: return detections` — BE 는 항상 비어있지 않은 리스트를 보내므로 필터가 실효).
- **재현/확인 경로**: 코드 경로상만 재현 가능(현 스택에서는 ai-server 필터가 살아 있어 실동작 재현 불가). ai-server `_apply_class_filter` 를 우회/롤백한 버전이면 `POST /v1/frames/296/autolabel {"classes":["person"]}` 응답에 `{"labelId":null,"label":"dog",...}` 가 섞여 나올 수 있고, 저장 DTO `LabelItemDto.labelId` 가 nullable 이라 그대로 `LS_DATA_LBL` 에 적재될 수 있다.
- **영향**: CWE-20(불완전 입력 검증, 신뢰 경계 비대칭). 라벨 마스터 미등록 클래스가 학습데이터에 유입될 수 있는 잠재 경로. **CLAUDE.md 의 명문 정책("매핑 강제는 BE 가 담당 — FE 요청을 신뢰하지 않고 … ai-server 로 전달")은 요청측만 요구하므로 정책 위반은 아니다** → FAIL 이 아닌 관찰.
- **수정 방향(제안)**: `toItems`/폴리곤 루프 진입 전에 `effectiveClasses`(또는 `mappedDetectClasses()`) 를 응답 필터로 한 번 더 적용하고, 제외 건은 `drop unmapped detect class` 와 같은 WARN 으로 계상. 구현은 하지 않음.

### [C-ISSUE-42] TC-LABEL-60/72 — 검출 건수만큼 `findLabelIdByDtctType` DB 왕복(N+1), 캐시 없음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨링 화면 툴바에서 프레임마다 호출되는 대화형 경로다. `rules/performance.md` 는 반복 단건 조회를 금지하고 일괄 조회를 요구한다. 검출 N건이면 매핑 조회도 1회로 끝나야 한다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:423-431`(BBOX) 과 `:334`(POLYGON) 이 **검출 1건마다** `labelMasterService.findLabelIdByDtctType()` 를 호출한다. 이 메서드는 `@Transactional(readOnly=true)`(`LabelMasterService.java:150-157`) 라 **호출마다 트랜잭션·커넥션 획득 + `SELECT ... WHERE dtct_type_cd=? AND use_yn='Y'` 1회**가 발생한다. 캐시(`@Cacheable`) 없음. 같은 요청에서 이미 `mappedDetectClasses()`(`:166-168`)로 전체 매핑을 읽었는데도 재사용하지 않는다.
  - 상한: BBOX 는 YOLO 검출 수 제한이 없어 혼잡 프레임이면 수십~수백 회. POLYGON 은 `maxBoxes`(최대 100)까지.
- **재현/확인 경로**: 가중치 탑재된 ai-server 로 `POST /v1/frames/{srcSn}/autolabel` 호출 후 `p6spy`/`hibernate.SQL` DEBUG 로 `ls_label` 조회 횟수 = 검출 건수인지 확인. 현 스택은 검출 0건이라 실측 불가(정적 판정).
- **영향**: 성능/자원(커넥션 풀). 서비스가 `non-transactional` 을 택한 이유가 "AI 블로킹 호출 중 HikariCP 커넥션 미점유"(`:48-50`)인데, 응답 조립 단계에서 커넥션을 N회 붙잡아 그 의도를 부분 상쇄한다. 동시 라벨러 다수 + 혼잡 프레임에서 풀 압박.
- **수정 방향(제안)**: `LsLabelRepository` 에 `findByDtctTypeCdInAndUseYn(Collection<String>, String)` 를 추가해 검출 라벨 집합을 **1회 조회 → `Map<String,Long>`** 으로 만들고 루프에서 조회. 또는 `mappedDetectClasses()` 를 `Map<String,Long>`(코드→labelId) 반환으로 확장해 이미 읽은 결과를 재사용. 구현은 하지 않음.

### [C-ISSUE-43] TC-LABEL-64 — 매핑 0건이어도 프레임 이미지 base64 인코딩을 먼저 수행
- **심각도**: LOW
- **기대 동작(기대효과)**: "매핑된 라벨이 없으면 ai 미호출 + 빈 결과"(TC-LABEL-64) 는 **아무 작업도 하지 않고 즉시 반환**하는 것이 자연스럽다. 프레임 원본 이미지 읽기 + base64 인코딩은 이 경로에서 전혀 쓰이지 않는다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:222` 가 `resolveDetectClasses`(`:228`) 보다 **먼저** 실행된다.
  ```java
  String imageB64 = frameImageEncoder.encodeFrame(src);   // :222
  ...
  List<String> effectiveClasses = resolveDetectClasses(classes);   // :228
  if (effectiveClasses.isEmpty()) { ... return 빈 결과; }           // :229-235
  ```
  실동작 확인: `{"classes":["dog","zebra","toothbrush"]}` 요청이 ai 호출 0회로 끝났으나(로그 `no mapped detect classes`), 그 직전에 프레임 이미지 인코딩은 수행됨.
- **재현/확인 경로**: `curl -X POST http://localhost:18081/api/v1/frames/296/autolabel -H "Authorization: Bearer $WK" -d '{"classes":["dog"]}'` → 200 + `NO_MAPPED_CLASS_MESSAGE`. 응답 지연이 NAS I/O + 이미지 크기에 비례.
- **영향**: 성능/자원(불필요 NAS I/O + 힙 상 base64 문자열). 매핑이 하나도 없는 초기 운영 구간(마이그레이션 직후 전 라벨 `DTCT_TYPE_CD=NULL`)에서 전 요청이 이 경로를 타므로 체감 가능.
- **수정 방향(제안)**: `resolveDetectClasses` → 빈 결과 조기 반환 블록을 `encodeFrame` **앞으로** 이동. 단 `encodeFrame` 이 신고 게이트도 겸하므로(`:56-59` javadoc), 이동 시 `requireNotBlocked` 가 이미 `:214` 에서 선행 수행됨을 확인할 것. 구현은 하지 않음.

### [C-ISSUE-44] TC-LABEL-60/68/69/71~76 — ai-server 모델 가중치 미탑재로 검출 의존 분기 10건 실동작 검증 불가
- **심각도**: MEDIUM (환경/검증 커버리지 결함 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 온라인 오토라벨의 핵심 위험 구간(좌표 정규화 400/clamp/스킵, 폴리곤 상한·예산·부분실패·TOCTOU)은 **실제 검출 결과 위에서** 판정돼야 한다. 이 프로젝트의 실패 모드는 "코드가 있으니 PASS"이므로, 검출이 0건이면 해당 단언들은 반증 시도 자체가 불가능하다.
- **현재 동작(이슈 내용)**: `klid-ai-server` 는 `AI_MOCK_MODE=false` 이나 YOLO 가중치 파일이 없어 mock 으로 폴백한다.
  ```
  ai-server: WARNING:app.routers.yolo:[DETECT:yolox][MOCK] returning mock track
             (model not loaded or AI_MOCK_MODE=true) reason=weights_missing
  backend  : [Autolabel] mock response — skip detection srcSn=296 source=mock reason=weights_missing
  ```
  BE 는 mock 을 정상적으로 차단(`:246-251`, TC-67 PASS)하므로 그 **뒤에 있는** `normalizeDetections`(`:256`) · 빈검출 분기(`:259`) · `polygonAutolabel`(`:293~`) 전부가 도달 불가 코드가 된다. `pipeline-drive.md` 의 배치 YOLO 도 같은 사유로 `yoloCount=0`.
- **재현/확인 경로**: `curl -X POST .../v1/frames/296/autolabel` → 항상 `detectedCount:0` + `"AI 모델 미로드 — 결과 신뢰 불가"`. `docker logs klid-ai-server | grep weights_missing`.
- **영향**: 기능/검증. 좌표 clamp·퇴화 스킵·all-or-nothing 400·폴리곤 예산/상한/부분실패/TOCTOU 등 **10개 케이스가 실환경에서 한 번도 실행된 적이 없다**. 단위테스트(mock 주입)로만 커버되므로 실제 ai-server 응답 스키마 변화(필드명·좌표 순서)에 대한 회귀 감지력이 없다 — 본 검증 기준의 "self-fill / 계약 불일치" 관심사와 직결.
- **수정 방향(제안)**: ①로컬 검증 스택에 YOLOX ONNX 가중치를 마운트하거나 다운로드 스텝을 `docker-compose.local.yml` 에 추가 ②또는 mock-server 처럼 **결정적 검출 결과를 돌려주는 ai-server 스텁 모드**(mock 플래그 없이 고정 좌표 반환)를 검증 전용으로 도입해 위 분기를 실제로 태울 것. 구현은 하지 않음.

### [C-ISSUE-45] TC-LABEL-65 — 검출 클래스 화이트리스트 매칭이 대소문자 구분이라 대문자 요청이 조용히 0건이 된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 화이트리스트 매칭은 fail-closed 여야 하고(현재 그러함), 동시에 **정상 사용자가 이유를 알 수 있어야** 한다. 요청 전부가 케이스 불일치면 사용자에게는 "매핑을 등록하라"는 안내가 뜨는데 실제 원인은 케이스 표기다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:466-473` 이 `trim()` 만 하고 `mapped.contains(trimmed)` 로 **정확 일치** 비교한다.
  ```java
  String trimmed = c == null ? null : c.trim();
  if (trimmed != null && mapped.contains(trimmed)) { allowed.add(trimmed); }
  else { log.warn("[Autolabel] drop unmapped detect class={}", LogSanitizer.sanitize(trimmed)); }
  ```
  실동작: `"PERSON"` → `drop unmapped detect class=PERSON`(WARN), `" car "` → trim 후 통과. 요청이 `["PERSON"]` 뿐이면 응답은 `NO_MAPPED_CLASS_MESSAGE`("라벨 관리에서 AI 검출 클래스를 매핑해 주세요") 로 **원인과 다른 안내**가 나간다.
  - 비교 대상 `LS_LABEL.DTCT_TYPE_CD` 는 저장 시 `CocoClasses` allowlist 로 정규화되므로 항상 소문자 canonical 이다(`LabelMasterService.java:126-135`).
- **재현/확인 경로**: `curl -X POST .../v1/frames/296/autolabel -d '{"classes":["PERSON"]}'` → 200 `detectedCount:0` + "검출할 수 있는 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해 주세요."
- **영향**: 기능/사용성. 보안 영향 없음(fail-closed). 정상 FE 는 `GET /v1/manage/labels/detect-candidates` 가 준 canonical 값을 그대로 보내므로 실사용 발생 확률은 낮으나, 외부 스크립트·수기 호출에서 오진단 유발.
- **수정 방향(제안)**: ①`mapped` 를 소문자 정규화 Set 으로 만들고 `trimmed.toLowerCase(Locale.ROOT)` 로 비교(통과 시 canonical 값을 ai-server 로 전달) 또는 ②drop 이 1건 이상인데 결과가 비면 `NO_MAPPED_CLASS_MESSAGE` 대신 "요청한 클래스가 매핑되지 않았습니다" 로 구분 안내. 구현은 하지 않음.

---

## 확증편향 반증 시도 기록 (PASS 로 남은 항목들의 반증 근거)

| 반증 가설 | 시도 | 결과 |
|---|---|---|
| FE 가 임의 클래스를 요청하면 ai-server 로 전달된다 | `["person","hack","dog","PERSON"," car "]` 전송 후 백엔드 WARN·ai-server 호출수 관찰 | **반증 실패(방어 유효)** — hack/dog/PERSON drop, 나머지만 전달 |
| 전부 미매핑이면 그래도 ai 를 호출한다 | `["dog","zebra","toothbrush"]` 전송 + ai-server 누적 호출수 delta 측정 | **반증 실패** — delta 0, ai 미호출 |
| COCO 유효 클래스면 마스터 미매핑이어도 통과한다 | `zebra`/`toothbrush`(유효 COCO, 마스터 미매핑) 전송 | **반증 실패** — drop. allowlist 축은 COCO 가 아니라 `DTCT_TYPE_CD` |
| 온라인 오토라벨이 DB 에 라벨을 남긴다(미저장 정책 위반) | 약 50회 호출 후 `ls_data_lbl` (rawSn=26) 카운트 | **반증 실패** — 0건 유지 |
| 신고 구간(`'F'`) 영상도 오토라벨이 실행된다 | rawSn=33 `de_ident_yn='F'` 로 전환 후 호출 | **반증 실패** — 412 `PRECONDITION_FAILED` |
| 락·신고 동시 성립 시 응답 코드가 뒤바뀐다(규약 변경) | 락+`'F'` 동시 → 409, 락만 해제 → 412 | **반증 실패** — javadoc 규약(작업락 우선) 그대로 |
| in-flight 락이 예외 경로에서 새어 영구 409 가 된다 | 429(bulkhead)·502(ai 다운)·409(중복) 직후 재요청 | **반증 실패** — 전부 200 복귀(`finally` 해제 유효) |
| bulkhead 가 설정만 있고 실효하지 않는다 | 14 프레임 × 3회 = 42 동시요청 | **반증 실패** — 429 9건 실제 발생 |
| ai-server 장애 시 스택/내부경로가 응답에 샌다 | `docker pause klid-ai-server` 후 호출 | **반증 실패** — 502 + 고정 문구만, 스택·URL 미노출 |
| 활성 라벨에 같은 COCO 매핑을 2건 심을 수 있다 | DB 직접 INSERT(`dtct_type_cd='person'`, `use_yn='Y'`) | **반증 실패** — 부분 유니크 인덱스가 차단 |
| 라벨 삭제가 hard delete 다 | `DELETE /v1/manage/labels/20` 후 행 조회 | **반증 실패** — 행 존속 + `use_yn='N'` |
| 라벨명 근사중복(대소문자·공백)이 통과한다 | `" PERSON "` 로 수정 시도 | **반증 실패** — 409 (`LOWER(TRIM())` 비교) |
| `classes` 원소 길이 제한이 리스트 크기 제한에 가려 무력하다 | 원소 1개·51자 전송 | **반증 실패** — `classes[0]` 단위 400 |
# C 클러스터 part4 — TC-LABEL-106 ~ TC-LABEL-149 (라벨 저장/좌표검증 + 비식별 신고·resolve + 프레임 이미지 서빙)

- 대상: `docs/test-cases/C-marking-labeling.md` L167~210 (44건, 폐기 0건)
- 검증일: 2026-08-01 / 1차
- 환경: backend `localhost:18081/api`(컨테이너 klid-backend, Flyway **V158** 적용 확인) · DB `public` 스키마 · mock-server 9400 · ai-server 19300
- 판정 근거: 대부분 **실동작(curl + DB + 컨테이너 로그)**. ai-server 가 `weights_missing` mock 폴백 상태라 **YOLO 검출 0건** → TC-136~140(검출 좌표 clamp)만 정적 대조.
- **확정 정책 준수**: ★1(신고 게이트 = 자기 rawSn 행 하나 / 파생 경유 열람은 결함 아님) · ★3(사용자 저장=400 거부 / AI 검출=clamp)은 결함으로 재보고하지 않았다.
- 테스트 데이터: 신규 raw(56·57)·src·label 을 INSERT 해 사용하고 **검증 종료 후 전량 삭제·원상복구**(raw 26/27/34 = `de_ident_yn='Y'`, `DATA_STTS_CD='COMPLETED'`, 신고 전량 RESOLVED, 심링크 제거). 프로덕션/테스트/설정 파일은 **일절 수정하지 않음**.

## 판정 요약

| 판정 | 건수 |
|---|---:|
| PASS | 43 |
| PARTIAL | 1 (TC-LABEL-143) |
| FAIL / BLOCKED / N-A / 확인필요 | 0 |

부수 발견(케이스 범위 밖 인접 결함) 1건: `GET /v1/deident-reports` 정렬 키 미검증 → **500** (C-ISSUE-61).

---

## 결과표

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-LABEL-106 | PASS | [실동작] rprtSn=15/16/17/18 전건 `POST /v1/deident-reports/{n}/resolve` → 200, `report_stts_cd=RESOLVED`+`resolved_dt` 기록, `ls_auth_work_lock` → `RELEASED/MANUAL_DEIDENT_DONE`, `ls_data_raw.de_ident_yn` `F`→`Y`. 산출물 미교체 상태 선행 호출은 **409 + 전부 롤백(OPEN·LOCKED·`F` 유지)** = fail-closed 확인. `DeidentReportService.java:353-402` |
| TC-LABEL-107 | PASS | [실동작] rawSn 27·34(배치 `COMPLETED`)·26(`COMPLETED`+검수 `APPROVED`) 3건 모두 report→resolve 후 `ls_data_raw.data_stts_cd=COMPLETED` 불변, `ls_raw_data_status.data_stts_cd` 도 불변(27=ASSIGNED). CWE-664 역행 없음. `:384-391` |
| TC-LABEL-108 | PASS | [실동작] `?status=X` → 400 `list.status: status 는 OPEN/RESOLVED/DISMISSED 만 허용됩니다.` 소문자 `resolved`·`OPEN%20`(공백)·중복 파라미터(`status=OPEN&status=X`, 역순 모두) 전부 400 — 컨트롤러 `@Pattern`(`DeidentReportController.java:74-75`)이 선행해 서비스 `toUpperCase` 관용 경로 도달 불가(카탈로그 주석과 일치). `:426-438` |
| TC-LABEL-109 | PASS | [실동작] status 미지정 → OPEN 3건(rprtSn 14·12·2)만 반환, `sort=reportDt DESC`. DB `report_stts_cd='OPEN'` 집합과 정확히 일치. ⚠ `?status=`(빈 문자열)은 서비스 blank→OPEN 관용이 아니라 `@Pattern` 400 — 실사용 영향 없음(미지정과 다른 경로) |
| TC-LABEL-110 | PASS | [실동작] src 305 저장(labelVersion 0→1) 후 다시 `labelVersion=0` 전송 → **409** "다른 사용자가 먼저 저장했습니다…", 라벨 미변경(재조회 시 직전 세트 그대로) |
| TC-LABEL-111 | PASS | [실동작] `labelVersion` 필드 없이 PUT → 200 저장, 버전 1→2 정상 증가(하위호환) |
| TC-LABEL-112 | PASS | [실동작] 동일 세트(id·좌표 동일) 재전송 → 200, 응답 `labelVersion` 1 유지(bump 없음) |
| TC-LABEL-113 | PASS | [실동작] `points=[[999999,888888],…]` → **400** `좌표가 이미지 경계를 벗어났습니다 (x=999999.0, y=888888.0, 이미지=320x240)` — 클램프 아님 |
| TC-LABEL-114 | PASS | [실동작] 실제 프레임 320x240 기준 경계값 `[[318,238],[320,240]]` → 200 저장 |
| TC-LABEL-115 | PASS | [실동작] DB 직접 주입한 경계 밖 레거시 라벨(`[[5000,5000],[5001,5001]]`)을 **좌표 그대로** 포함해 프레임 전체 재저장 → 200(프레임 영구 차단 회귀 없음), 라벨 3건 보존 |
| TC-LABEL-116 | PASS | [실동작] 기존 id 라벨의 좌표를 `[[318,238],[5000,5000]]` 로 이동 → 400(동일 메시지, 이미지=320x240) |
| TC-LABEL-117 | PASS | [실동작] 이미지 파일이 없는 프레임에 `[[99999,99999],…]` 저장 → **200**, 로그 `[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn=332`, 메트릭 `/actuator/metrics/label.bounds.skipped` COUNT=1.0 · tag `reason=unresolved` |
| TC-LABEL-118 | PASS | [실동작] SKELETON 17키포인트 중 첫 키포인트 `[0,0,0]`(v=0 자리표시자) 포함 → 200. (키포인트 개수 16개면 400 "정확히 17 개" — 개수 검증은 별도 유지) |
| TC-LABEL-119 | PASS | [실동작] 비활성 마스터(`ls_label.lbl_id=10, use_yn='N'`) 참조 라벨을 id+같은 labelId 로 재전송하면서 **다른 라벨도 수정** → 200(프레임 전체 409 차단 없음). 조회 시 해당 라벨은 `labelName='c2a-truck-x'` 로 표시만 됨 |
| TC-LABEL-120 | PASS | [실동작] 기존 라벨(id=605)의 labelId 를 비활성 10 으로 변경 → **409** "사용 중지된 라벨입니다: labelId=10". 신규 라벨에 부여해도 동일 409(“id 붙이면 통과” 우회 없음) |
| TC-LABEL-121 | PASS | [실동작] rawSn 27 신고(OPEN) 상태에서 `GET /v1/frames/301/labels` → **412** — **WORKER·REVIEWER 동일**. 미배정 WORKER 는 그 이전에 403(인가 선행). `LabelService.java:193` · `LabelAccessGuard.java:135-141` |
| TC-LABEL-122 | PASS | [실동작] `GET /v1/frames/301/label-history` → 412(동일 메시지). resolve 후 200 복귀. `LabelService.java:482` |
| TC-LABEL-123 | PASS | [실동작] 부모 raw 27=`F` 구간에서 파생본(raw 57, `ORGNL_RAW_SN=27`, `de_ident_yn='Y'`)의 프레임 라벨 조회 → **200**(+ `/deid-image` 200). 조상 체인 미추적 확인 = ★1 확정 정책의 귀결이며 결함 보고 대상 아님. `DeidentReportGate.java:23-47,:66-71` |
| TC-LABEL-124 | PASS | [실동작] 신고 전후 `ls_data_lbl` 4건 불변, `ls_label_version` where data_raw_sn=27 **0행 유지**, `ls_data_src.lbl_ver` 불변(301=1). 로그 `labelsPreserved=true` |
| TC-LABEL-125 | PASS | [실동작] 개인정보 메타 보유 프레임 2건(301·302)에서 신고 → 로그 `privacyResetAudited=2`, `ls_data_lbl_hstry` 에 프레임당 1행 `{"event":"PRIVACY_META_RESET","deidentReportSn":16,"changes":[]}` (actor `reg_id=2001`, 시각 기록, add/mdfcn/del=0). `V_COMPLETED_LABEL_CHANGE` 정의에 `WHERE (add_cnt+mdfcn_cnt+del_cnt) > 0` 확인 → 미노출. ※ 최초 관측(23:18)에서 `audited=0` 이 나온 것은 **동시 실행 중이던 다른 검증 에이전트가 같은 프레임의 개인정보 메타를 이미 비운 상태**였기 때문이며, 통제 재현(23:21)에서 정상 동작 확인 |
| TC-LABEL-126 | PASS | [실동작] resolve 후 `GET /v1/frames/301/labels` 200, 라벨 id·타입·좌표·autoLblYn·trckId 가 신고 **이전 응답과 완전 동일**(스크립트 비교 True), labelVersion 도 1 그대로. 별도 복원 API 없음 |
| TC-LABEL-127 | PASS | [실동작] `POST /v1/videos/34(·26)/deident-report` → **201**. 부수효과 srcSn 경로와 동일: 신고행 OPEN + `ls_auth_work_lock` LOCKED + `de_ident_yn='F'` + 개인정보 3필드 리셋 + 스트림 캐시 evict + REVIEWER 알림 로그. APPROVED 영상(26)에서 TASK_MODIFIED 축적 후 export 성공 시점에 발송 확인 |
| TC-LABEL-128 | PASS | [실동작] 파생 raw 18(WORKER 배정자)·25(REVIEWER) → **412**. 응답 메시지에 **부모 rawSn 미노출**(“원본 영상의 비식별 결과를 복사해 만든 파생영상…”), `ls_deident_report` 신규 행 0건, REVIEWER 알림 로그 없음, 사유는 WARN 감사로그에 **개행 제거된 한 줄**로 기록(`reason=C-part4 파생 신고 시도두번째줄 injection`, CWE-117 방어). `:183,:295-306` |
| TC-LABEL-129 | PASS | [실동작] `de_ident_yn='N'` 영상(raw 56) → **412** "아직 비식별 처리가 완료되지 않은 영상입니다", `'N'→'F'` 전이 없음(DB 확인). 이미 `'F'` 인 raw 40 은 게이트 통과 후 기존 **409**(이미 재비식별 중) 경로 유지. `:186,:329-337` |
| TC-LABEL-130 | PASS | [실동작] WORKER 미배정 rawSn 56 → **403** "본인에게 배정되지 않은 영상입니다"(412 아님 = 인가 선행). 존재하지 않는 rawSn 999999 도 WORKER 는 403 / REVIEWER 만 404 → 미인가자에게 존재 여부 미노출. `:151` · `LabelAccessGuard.java:83-100` |
| TC-LABEL-131 | PASS | [실동작] `POST /v1/labels/{srcSn}/deident-report` 에서 파생 프레임(src 45) → 412, 비식별 미수행 프레임(src 332) → 412 — rawSn 경로와 동일 문구·동일 코드(`doReport` 수렴). `:125,:169-186` |
| TC-LABEL-132 | PASS | [실동작] 미승인(ASSIGNED) raw 27 resolve 시 로그 `[VlmResumeBridge] deident gate reopened rawSn=27 — checking withheld VLM submit` 발화 = 승인 여부 무관 발행. `:487-495` |
| TC-LABEL-133 | PASS | [실동작] 같은 resolve 에서 raw 27 은 export 재산출 로그 **없음**. APPROVED raw 26 resolve 시에는 `[DatasetExportBridge] deident report resolved rawSn=26 — re-triggering withheld export/notify` → export v4 성공 → mock-server `POST /api/data-set/v2/jobs/26/notify-updated 202`(통지는 export 성공 후). `:492-494` |
| TC-LABEL-134 | PASS | [실동작] 신고 시 `[StreamMetaCache] evicted rawSn=27`, resolve 시 재차 evict. 로그상 파생(raw 57) 캐시 evict 없음 = 대상 1건. `:248,:395` |
| TC-LABEL-135 | PASS | [실동작] `'F'`+LOCKED(raw 40) → **409** "작업이 잠긴 영상입니다" / `'F'`+락없음(raw 32) → **412** "비식별 재처리 대기 중인 영상은 오토라벨을 실행할 수 없습니다" — 작업락 판정 선행 확인. `AutolabelOnlineService.java:411-421` |
| TC-LABEL-136 | PASS | [정적] `DetectionBoxNormalizer.java:59-68` `clamp(v,max)=max(0, min(max,v))` 로 상한 clamp 후 반환(예외 없음). 실동작은 ai-server `weights_missing` mock 폴백으로 검출 0건이라 불가(환경 갭 — baseline 기록됨) |
| TC-LABEL-137 | PASS | [정적] 동일 `clamp` 의 `Math.max(0.0, …)` 로 음수 → 0. 호출부 `AutolabelOnlineService.java:256` 은 형식 위반만 400 으로 승격, 음수는 400 아님 |
| TC-LABEL-138 | PASS | [정적] `:65-67` clamp 후 `x2<=x1||y2<=y1` → `Optional.empty()`, 호출부 `:570-576` 이 해당 검출만 `continue` + WARN(`detection dropped — box degenerate after clamp`), 나머지는 반환 |
| TC-LABEL-139 | PASS | [정적] `:71-77 upperBound()` — bounds null·길이≠2·0 이하면 `Double.MAX_VALUE`(상한 없음), 하한 0 clamp 만 적용. 호출부는 `frameBoundsResolver.resolve(src).orElse(null)` 로 측정 실패를 그대로 전달 |
| TC-LABEL-140 | PASS | [정적] `validatePolygonPoints`(`:580-620`)는 음수·비유한 좌표를 **거부**하며 clamp 하지 않음, 폴리곤 경로는 박스별 try/catch 부분 스킵. BBOX=clamp / SAM 폴리곤=거부 비대칭은 주석에 근거 명시(★3 정합) |
| TC-LABEL-141 | PASS | [실동작] `GET /v1/frames/301/deid-image` → 200 `Content-Type: image/jpeg`, `Content-Length: 13164`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline; filename="frame_deid_301.jpg"`(= srcSn+MIME 파생 확장자만, 파일명 유래 문자열 없음) |
| TC-LABEL-142 | PASS | [실동작] `DE_IDNTF_SRC_FILE_PATH_NM` null 이고 `SRC_FILE_PATH_NM` 은 채워진 프레임 → **404** "비식별 이미지 파일이 존재하지 않습니다"(원본 미서빙), 로그 `verdict=BLANK`. base 밖·존재하지 않는 경로도 각각 403/404 로 수렴 |
| TC-LABEL-143 | PARTIAL | [실동작] `/deid-image` + PORTAL_USER → **403** (기대 일치). 그러나 형제 경로 `/image` 도 **403** — `@PreAuthorize` 에는 PORTAL_USER 가 있으나(`FrameImageController.java:80`) 그 앞단 `SecurityConfig` 채널 격리(`/v1/**` = INTERNAL 채널 + REVIEWER/WORKER 역할)가 먼저 차단한다. 즉 "역할 집합이 다르다"는 애노테이션 계층에서만 참이고 **엔드투엔드로는 둘 다 403** → 기대결과 문구가 부정확(제품은 더 엄격, 보안 결함 아님). C-ISSUE-62 |
| TC-LABEL-144 | PASS | [실동작] 신고(OPEN) 중 raw 34 프레임: 미배정 WORKER → **403**(신고 전과 동일 응답 = 존재 탐색 불가), REVIEWER → **412**. 없는 srcSn → 404. 순서 ①인가 ②게이트 ③경로해석 확인 |
| TC-LABEL-145 | PASS | [실동작+정적] `frames/deid/27/qa-c4-link.jpg → /app/storage/raw/frames/raw/27/frame-0.jpg` 심링크를 DB 경로로 지정 → **403** `verdict=OUTSIDE_DEID_SUBTREE`(realpath 기준 판정). 판정~open 사이 교체(정확한 TOCTOU 창)는 재현 불가하나 `FrameImageService.openNoFollow`(`:362-366`)가 크기·스트림 모두 `LinkOption.NOFOLLOW_LINKS` 로 열고 실패 시 404 로 마감(fail-closed) |
| TC-LABEL-146 | PASS | [실동작] deid base 밖 절대경로(`/etc/hostname`) 및 `..` 경로순회 모두 **403** "허용되지 않은 이미지 경로입니다", 로그 `verdict=OUTSIDE_BASE`. 판정은 `StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기 위임(서비스 재구현 없음) |
| TC-LABEL-147 | PASS | [실동작] `/deid-image` 200 응답 헤더 `Cache-Control: no-store` |
| TC-LABEL-148 | PASS | [실동작] `/v1/frames/301/image` 200 응답 헤더 `Cache-Control: no-store` |
| TC-LABEL-149 | PASS | [실동작] `/v1/videos/27/frames/0/image` 200(REVIEWER) 응답 헤더 `Cache-Control: no-store` + `nosniff` + `filename="frame_27_0.jpg"` |

### 테스트 자산 대조 (`_raw/test-baseline.md`: backend 4,755 tests / 실패 0)

| 영역 | 커버 테스트 |
|---|---|
| 신고·resolve 본체 | `label/service/DeidentReportServiceTest.java`(46건 — 파생 412·비식별 미수행 412·rawSn 진입점 5건·resolve 산출물 검증 8건·상태 역행 금지·이벤트 발행 2건 포함) |
| 라벨 보존/게이트 | `DeidentReportLabelPreservationIT`(3) · `DeidentReportGateCoverageIT`(8) · `DeidentReportServiceResetIT`(2, 개인정보 리셋 감사) |
| 게이트 판정 단일화 | `video/service/DeidentReportGateTest` · `AiInferenceDeidentReportGateTest` · `DatasetExportDeidentReportGateIT` · `DeidentReportStreamGateIT` |
| 프레임 이미지 | `label/FrameImageControllerTest` · `FrameImageSrcSnPolicyTest` · `video/FrameImageRawFrameNoTest` · `portal/PortalFrameImageCacheControlTest` |
| 오토라벨 좌표 | `label/AutolabelOnlineServiceTest` · `AutolabelPolygonServiceTest` |

---

## 이슈

### [C-ISSUE-61] TC-LABEL-108/109 (인접) — `GET /v1/deident-reports` 정렬 키 미검증 → 미등록 키에 500 + 엔티티 필드 열거 오라클
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목록 API 의 정렬 키는 **allowlist 매핑으로만** 해석하고 개수 상한을 둔다(CLAUDE.md "목록 화면 정렬·필터 정책", CWE-89/CWE-770). 미등록 키는 그 엔드포인트의 확정 규약(strict 400 또는 lenient 200 폴백) 중 하나로 끝나야 하며, **사용자 입력으로 500 이 나면 안 된다**(오류 모니터링 오염 + 가용성).
- **현재 동작(이슈 내용)**: `DeidentReportController.java:76` 이 `@PageableDefault(...) Pageable` 을 **그대로** `DeidentReportService.listReports`(`:417-423`) → `reportRepository.findByReportSttsCd(normalized, pageable)` 로 넘겨, Spring Data 가 엔티티 프로퍼티를 직접 해석한다. 미등록 키면 `PropertyReferenceException` 이 `GlobalExceptionHandler` 의 unhandled 분기로 떨어져 **500**.
  ```
  GET /v1/deident-reports?sort=nosuchfield,desc   → 500 INTERNAL_ERROR
  GET /v1/deident-reports?sort=rawSn,desc         → 500  (엔티티 필드명은 dataRawSn)
  GET /v1/deident-reports?sort=rsn,desc           → 200  (신고 사유 컬럼으로 정렬됨)
  GET /v1/deident-reports?sort=reportSttsCd,asc   → 200
  로그: PropertyReferenceException: No property 'rsn;drop' found for type 'LsDeidentReport'
  ```
  200/500 차이가 **엔티티 내부 필드명 존재 여부를 알려주는 오라클**이 된다(CWE-209 계열). 응답 본문 자체에는 스택트레이스가 없어 유출은 없다.
  ※ ★2(작업목록 strict 400 vs 검수목록 lenient 200)는 `/v1/tasks/board*` 와 `/v1/reviews*`·`/v1/videos` 만 정의한다. 이 엔드포인트는 **두 정책 어느 쪽도 적용되지 않은 세 번째 목록 API** 이므로 "비일관성 재보고"가 아니라 **미배선 갭**이다.
- **재현/확인 경로**: 위 curl 4줄(REVIEWER 토큰). `size=100000` 은 100 으로 캡됨(=DoS 방어는 정상).
- **영향**: 사용자 입력으로 5xx 발생(가용성·모니터링 노이즈), 정렬 키를 통한 엔티티 스키마 열거, 정렬 대상이 allowlist 밖(예: 신고 사유 텍스트 `rsn`)까지 열려 인덱스 없는 컬럼 정렬 유발 가능.
- **수정 방향(제안)**: 다른 목록 API 와 동일하게 `sort` 를 **allowlist 매핑**(`reportDt`/`status`/`rawSn` → 엔티티 프로퍼티)으로 변환하는 정규화를 컨트롤러/서비스 경계에 추가하고, 이 엔드포인트는 **변경 전에도 `sort` 를 받아 500 이었던** 축이므로 ★2 기준상 **strict 400** 이 자연스럽다(검수목록형 lenient 폴백을 택할 경우 그 근거를 UNCERTAINTIES 에 명시). 추가로 `PropertyReferenceException` 을 `GlobalExceptionHandler` 에서 400 으로 매핑해 전역 fail-safe 를 둔다.

### [C-ISSUE-62] TC-LABEL-143 — 기대결과 "`/image` = 통과"가 엔드투엔드로 성립하지 않음(카탈로그 정정)
- **심각도**: LOW (제품 결함 아님 — 실제 동작이 기대보다 **더 엄격**)
- **기대 동작(기대효과)**: 카탈로그는 PORTAL_USER 토큰에 대해 `/deid-image`=403 / `/image`=통과 로 "두 형제 경로의 역할 집합이 의도적으로 다름"을 검증하려 한다.
- **현재 동작(이슈 내용)**: `/deid-image` 403(기대 일치)이지만 `/image` 도 **403**. PORTAL_USER 토큰은 `channel=PORTAL` 이라 `SecurityConfig`(`/v1/portal/**` 외 `/v1/**` = `CHANNEL_INTERNAL` + ROLE_REVIEWER|WORKER|STREAM_SIGNED)에서 `@PreAuthorize` 도달 전에 차단된다. `FrameImageController.java:80` 의 `hasAnyRole(..., 'PORTAL_USER')` 는 현재 배선상 **도달 불가 권한**이다.
- **재현/확인 경로**: `POST /v1/dev/tokens {"role":"PORTAL_USER","channel":"PORTAL"}` 발급 후 `GET /v1/frames/301/image` → 403 / `GET /v1/frames/301/deid-image` → 403.
- **영향**: 없음(보안상 더 강함). 다만 카탈로그 기대값이 틀려 다음 회차에 위양성 FAIL 을 유발할 수 있고, 컨트롤러의 죽은 권한 표기가 정책 오독을 부른다.
- **수정 방향(제안)**: ① 카탈로그 TC-LABEL-143 기대결과를 "`/deid-image`=403 / `/image`=403(채널 격리 선행). 역할 집합 차이는 애노테이션 계층 정책" 으로 정정 ② 코드 쪽은 `FrameImageController` `/image` 의 `PORTAL_USER` 표기를 제거하거나 "채널 격리로 실효 없음" 주석을 다는 것을 검토(동작 변경 아님).

### [C-ISSUE-63] TC-LABEL-141~149 — 근거 `file:line` 드리프트(카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 file:line 이 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: 프레임 이미지 서빙 계열 근거가 전부 어긋난다.
  | TC | 카탈로그 근거 | 실제 위치 |
  |---|---|---|
  | 141 | `FrameImageController.java:162-171` · `FrameImageService.java:214-269` | `FrameImageController.java:115-122` · `FrameImageService.java:283-334` |
  | 142 | `FrameImageService.java:223-234` | `FrameImageService.java:290-301` |
  | 143 | `FrameImageController.java:164 vs :90` | `:117 vs :80` |
  | 144 | `FrameImageService.java:214-226` | `:283-301`(+ 인가·게이트는 `FrameImageLookupService`) |
  | 145 | `FrameImageService.java:246-256` | `:313-320` + `openNoFollow :362-366` |
  | 146 | `FrameImageService.java:224-233` | `:291-301` |
  | 147 | `FrameImageService.java:263` | `:327` |
  | 148 | `FrameImageController.java:132` | 파일이 127줄로 **존재하지 않는 라인** — 실제는 `FrameImageService.java:252`(`serveFrame` 공용) |
  | 149 | `VideoController.java:277-295`(✅ 유효) · `FrameImageService.java:186` | no-store 는 `FrameImageService.java:252` |
  (TC-106~140 의 근거는 전건 유효 — `DeidentReportService`·`LabelAccessGuard`·`LabelService`·`DetectionBoxNormalizer` 라인 일치, `AutolabelOnlineService` 는 ±5줄 이내)
- **재현/확인 경로**: 해당 파일 Read 대조.
- **영향**: 검증 비용 증가·오판 위험.
- **수정 방향(제안)**: 카탈로그 근거 갱신(위 표 그대로 치환).

### [C-ISSUE-64] TC-LABEL-125 (인접) — 신고 감사 로그의 `privacyReset` 이 "실제로 지워진 프레임 수"가 아님
- **심각도**: LOW
- **기대 동작(기대효과)**: 개인정보 표기를 되돌리는 행위의 감사 로그는 **실제 변경 건수**를 말해야 한다(OWASP A09). 행 단위 감사(`privacyResetAudited`)와 집계 로그가 서로 다른 수를 말하면 운영자가 어느 쪽을 믿을지 알 수 없다.
- **현재 동작(이슈 내용)**: `resetPrivacyMetaByRawSn`(`LsDataSrcRepository.java:198-201`)은 `where s.rawSn = :rawSn` 만으로 **영상 전 프레임을 무조건 update** 하므로 반환값이 곧 "프레임 총수"다. 실측:
  ```
  [DeidentReport] created rprtSn=15 rawSn=27 ... privacyReset=5 privacyResetAudited=0
  [DeidentReport] created rprtSn=16 rawSn=27 ... privacyReset=5 privacyResetAudited=2
  ```
  같은 영상에서 값 보유 프레임이 0건일 때도 2건일 때도 `privacyReset=5` 로 동일하다.
- **재현/확인 경로**: 개인정보 3필드가 전부 NULL 인 영상에 신고 접수 → 로그의 `privacyReset` 이 프레임 수와 같음을 확인.
- **영향**: 감사 로그 오독(실제 변경이 없었는데 "5건 리셋"으로 읽힘). 데이터 정합·보안 자체에는 영향 없음.
- **수정 방향(제안)**: 로그 필드명을 `privacyResetScanned`(또는 `frameCount`)로 바꾸거나, 로그 값을 `privacyResetSrcSns.size()`(=실제 변경분)로 통일. 벌크 update 자체는 멱등이라 조건 추가는 불필요.

---

## 검증 중 발견한 환경/데이터 사실 (참고)

1. **ai-server 가중치 미탑재** — `[Batch][YOLO] mock response detected … mockReason=weights_missing` 로 온라인 오토라벨도 `detectedCount=0` + `message="AI 모델 미로드 — 결과 신뢰 불가"`. TC-136~140 의 실동작 판정을 막는 유일한 환경 갭(코드 결함 아님, baseline 기록과 일치).
2. **동시 검증 간섭** — 같은 시간대에 다른 C 파트 에이전트가 raw 26/27 프레임 라벨을 편집 중이어서, 신고 접수 시점 스냅샷이 흔들릴 수 있다(TC-125 최초 관측이 그 사례). 신고는 대상 영상을 잠그므로 **동시 검증 시 서로의 저장이 409 로 막힐 수 있음** — 다음 회차에는 파트별 전용 rawSn 을 배정하는 편이 안전하다.
3. **DB 컬럼명 주의** — 카탈로그·CLAUDE.md 표기는 `DE_IDNTF_YN` 이지만 실제 테이블 컬럼은 `ls_data_raw.de_ident_yn` 이다(프레임 쪽은 `de_idntf_src_file_path_nm` 으로 표기가 다름). SQL 로 검증할 때 혼동 주의.
# C-part5 — C-3. TC-SAM2 (33건) + C-4. TC-KEYPOINT (13건) 전수 검증 결과

> 대상: `docs/test-cases/C-marking-labeling.md` `## C-3. TC-SAM2 — SAM2 분할 / 트랙 프록시`(TC-SAM2-01~33, 33건) +
> `## C-4. TC-KEYPOINT — 17-keypoint COCO SKELETON`(TC-KEYPOINT-01~13, 13건) = **총 46건**
> 검증일: 2026-08-01 · 회차: 1차 · 코드 기준: qa-0801 워크트리(56d30478) · 실동작 기준: 기동 중 스택(klid-backend, flyway V146)
> ⚠ **이슈 ID 주의** — 본 파트의 `C-ISSUE-81~86` 은 이 파트 전용 신규 번호다. `UNCERTAINTIES.md` 미해소 이월표의 구 `C-ISSUE-82`(동명이인 dead `common/util/TrackInterpolator`)와는 **다른 항목**이다.

## 0. 검증 환경 실측 — ★ 착수 전 전제 1건 반증됨

| 항목 | 실측값 |
|---|---|
| backend | `http://localhost:18081/api` (context-path `/api`), profile `local` |
| 토큰 | HS256 JWT 직접 조립 (`JWT_SECRET` = `docker exec klid-backend printenv`). `sub=1001`→REVIEWER, `sub=2001`/`2002`→WORKER (`GET /v1/me` 로 검증) |
| DB | `klid-postgres` / `klid_system` / 스키마 `public` |
| 검증 데이터 | raw 4(30프레임, raw+deid 양쪽) · raw 18(해상도/증강 파생, `SRC_FILE_PATH_NM`=NULL, deid 전용) · raw 27(**`DE_IDENT_YN='F'` 신고 구간**, 5프레임) · raw 35(라벨 0건, 미배정 — 키포인트 쓰기 테스트용) |
| 프레임 해상도 | 320×240 (BE 오류 메시지 `이미지=320x240` 로 실측) |

### ★★ 착수 전 가정 반증 — **ai-server SAM2 는 mock 이 아니라 실모델 로드 상태다**

지시문의 "ai-server는 YOLO/SAM2 가중치 파일이 없어 mock/폴백 모드 → 실제 분할 정확도 케이스는 BLOCKED" 는 **SAM2 에는 해당하지 않는다.**

- `docker exec klid-ai-server printenv` → `AI_MOCK_MODE=false`
- `docker logs klid-ai-server` → **YOLO 만** `[DETECT:yolox][MOCK] … reason=weights_missing`. SAM2 는 mock WARN 없이 `torchvision` 텐서 변환 로그 후 `POST /infer/sam2/segment 200`
- BE 응답도 `mock` 분기(빈 폴리곤)가 아니라 실 폴리곤 + `score=0.967` 반환

→ **C-3 의 SAM2 케이스는 BLOCKED 가 아니라 실동작 검증 가능**했고, 실제로 33건 중 26건을 실동작으로 판정했다.
(반대로 mock 분기(TC-SAM2-09)는 `_real_segment` 의 `empty_mask` 폴백을 유도해 **실동작으로 재현**했다 — 아래 참조.)

### 스택 버전 격차 영향

`stack-bringup.md` 가 기록한 구버전(V146) 격차는 **LS_DATA_INGEST 적재(B)** 와 **증강 폐기/복구(E)** 에 한정된다.
본 파트 담당 구간(`Sam2SegmentService`/`Sam2TrackService`/`FrameImageEncoder`/`LabelService` 키포인트/`KeypointSerializer`)은 커밋 범위 `b2b44f0e~56d30478` 에 **전부 미포함**(`git log --oneline` 대조) → **BLOCKED 사유 해당 없음**.

---

## 1. C-3. TC-SAM2 케이스별 판정 (33건)

| ID | 판정 | 근거 확인 | 비고 |
|----|------|-----------|------|
| TC-SAM2-01 | PASS | [실동작] `POST /v1/frames/1/sam2-segment {"srcSn":1,"points":[[100,100]]}` (WORKER 2001) → **200** `{"polygon":[[90,0],[80,8],[81,178],[119,178],[119,0]],"score":0.967,"empty":false}`. **미저장 실증** — 호출 전후 `raw_sn=4` 라벨 수 10건 불변(`select count(*) … join ls_data_src`) | `Sam2SegmentService.java:84-153`(카탈로그 84-149, 경미 드리프트). 테스트 `Sam2SegmentServiceTest#segmentWithPointReturnsPolygonAndScore`(baseline 통과) |
| TC-SAM2-02 | PASS | [실동작] path `/frames/1/…` + body `srcSn=2` → **400** `{"message":"path 의 srcSn 과 body 의 srcSn 이 다릅니다.","errorCode":"INVALID_INPUT"}` | `LabelController.java:167-171`(카탈로그 161-172 범위 포함) |
| TC-SAM2-03 | PASS | [실동작] `points`+`box` 동시 → **400** `"exactlyOnePrompt: points 또는 box 중 정확히 하나만 제공해야 합니다."` | `Sam2SegmentRequest.java:46` `@AssertTrue isExactlyOnePrompt` (XOR) — 라인 정확 |
| TC-SAM2-04 | PASS | [실동작] `{"srcSn":1}` (둘 다 null) → **400** 동일 메시지. XOR 이라 둘 다 빈 경우도 false | `Sam2SegmentRequest.java:46` |
| TC-SAM2-05 | PASS | [실동작] WORKER `sub=2001` → `srcSn=31`(raw 5, LABELER 배정자는 2002) → **403** `"본인에게 배정되지 않은 영상입니다."` | `Sam2SegmentService.java:86` → `LabelAccessGuard.verifyAccess:39-72`. AI 호출 이전 차단(ai-server 로그에 해당 요청 없음) |
| TC-SAM2-06 | PASS | [정적] 경로순회 차단은 **`FrameImageEncoder`** 가 담당 — 비식별 분기 `StorageSubtreePolicy.verifyDeidentifiedFile`(`:181-216`, **realpath 기준 서브트리 판정 + 실경로 반환**), 원본 분기 `resolveSafe`(`FrameImageEncoder.java:220-229`, `resolve().normalize()+startsWith` → `..` 차단 400). 단위테스트 `FrameImageEncoderTest:64` `encodeFrame(frame("../../etc/passwd"))` 거부 | ⚠ **근거 드리프트**: 카탈로그의 `Sam2SegmentService.java:179` 는 **dead code** — 그 `resolveSafe(Path,String)` 는 `segment()` 에서 호출되지 않는다(→ **C-ISSUE-84**). ⚠ 원본 분기는 realpath/NOFOLLOW 미적용(비식별 분기와 비대칭) — 경로값이 DB 소유라 LOW |
| TC-SAM2-07 | PASS | [실동작] `srcSn=999999` → **404** `"프레임을 찾을 수 없습니다."` (인가 가드 단계). [정적] 파일 부재 분기는 `FrameImageEncoder.java:111-113` `Files.exists` false → NOT_FOUND, deid 분기는 `Verdict.MISSING`→원본 폴백→`:108` NOT_FOUND. 단위테스트 `FrameImageEncoderTest:72` | 카탈로그 근거 `:89, :96` 정확 |
| TC-SAM2-08 | PASS | [정적] `Sam2SegmentService.java:98-102` — `fileSize()` 로 **b64 인코딩·ImageIO 이전**에 `maxImageBytes`(기본 20MB) 초과 시 `PAYLOAD_TOO_LARGE`. `ErrorCode:24` = `HttpStatus.PAYLOAD_TOO_LARGE`(413). 단위테스트 `Sam2SegmentServiceTest#imageTooLargeRejected` | 로컬 프레임이 13KB 급이라 실동작 유도 불가(설정 변경 금지) |
| TC-SAM2-09 | PASS | [실동작] **mock 분기를 실제로 유도** — `{"srcSn":1,"box":[10000,10000,10001,10001]}` → ai-server `_real_segment` 가 마스크 미생성 → `mock=true, mock_reason=empty_mask` 폴백 → BE **200** `{"polygon":[],"score":0.0,"empty":true}` + `"message":"AI 모델 미로드 — 결과 신뢰 불가"`. 역박스(`[100,100,10,10]`)도 동일. **FE 차단 배선 확인** — `OverlayLayer.tsx:578-583` `if (res.polygon.length === 0) { setSegPreview(null); onMockWarning?.(res); return; }` (자동적용 이전, 저신뢰 분기보다 **선행**) | `Sam2SegmentService.java:124-127` + `LabelController.java:173-177`(카탈로그 174-178, off-by-1). ⚠ 메시지가 `empty_mask`(모델 로드됨) 경우에도 "모델 미로드"로 나감 → **C-ISSUE-86**(LOW) |
| TC-SAM2-10 | PASS | [정적] `Sam2SegmentService.java:159-163` `polygon.size() < MIN_POLYGON_POINTS(3)` → `EXTERNAL_API_ERROR`(`ErrorCode:28` = `BAD_GATEWAY` **502**). 단위테스트 `Sam2SegmentServiceTest#lessThanThreePointsRejected` | 실모델이 <3점을 반환하면 ai-server 가 mock 폴백하므로 실동작 유도 불가 |
| TC-SAM2-11 | PASS | [정적] `:164-175` — 원소 크기≠2 / null / 음수 / `x>imgWidth` / `y>imgHeight` 전부 502. 상한은 `readImageSize`(`:199-209`)로 **파일 실측**한 값 사용(요청값 아님). 단위테스트 `#polygonOutOfImageBoundsRejected` | 경계 정책: `x == width` 허용(`LabelService.validateWithinBounds` javadoc `:744-745` 와 동일 기준 — ★3 정합) |
| TC-SAM2-12 | PASS | [실동작] `simplifyTolerance:60` → **400** `"simplifyTolerance: 경계 세밀함은 50.0 이하여야 합니다."` / `-1` → **400** `"…0.0 이상이어야 합니다."` (하한도 함께 확인) | `Sam2SegmentRequest.java:35-36` 라인 정확 |
| TC-SAM2-13 | PASS | [정적] `Sam2SegmentService.java:144-147` — `outPolygon.size() < 3` 이면 `aiRes.polygon()` 원본으로 되돌림. 단위테스트 `#simplifyBelowMinKeepsOriginalPolygon` + `#simplifySystemConfigFailureFallsBack`(설정 조회 실패 시 상수 1.0 폴백) | ⚠ 근거 드리프트: 카탈로그 `:132-143` → 실제 단순화 블록 `131-147`, 3점미만 폴백은 `144-147` |
| TC-SAM2-14 | PASS | [실동작] `POST /frames/1/sam2-track` `nextSrcSns:[2,3]` → **200**, 프레임별 폴리곤 2건(`srcSn:2`, `srcSn:3`) + `shapeType:"POLYGON"`. **미저장 실증** — raw 4 라벨 수 10건 불변 | `Sam2TrackService.java:67-148`. ⚠ 이 경로에는 **mock 게이트가 없다** → **C-ISSUE-81**(케이스 자체의 단언은 충족하나 카탈로그 미커버 결함) |
| TC-SAM2-15 | PASS | [실동작] path 1 + body `srcSn=2` → **400** 동일 메시지 | `LabelController.java:138-142`(카탈로그 132-143 범위 포함) |
| TC-SAM2-16 | PASS | [실동작] `nextSrcSns` 51개 → **400** `"nextSrcSns: size must be between 0 and 50"`. **경계 실측** — 정확히 50개는 **200**(50프레임 전파 완료, 로그 `count=50`) | `Sam2TrackRequest.java:26` `@NotEmpty @Size(max=50)` |
| TC-SAM2-17 | PASS | [실동작] `nextSrcSns:[]` → **400** `"must not be empty"` | `Sam2TrackRequest.java:26` |
| TC-SAM2-18 | PASS | [실동작] `prevPolygon` 2점 → **400** `"prevPolygon: size must be between 3 and 1000"` | `Sam2TrackRequest.java:24` |
| TC-SAM2-19 | PASS | [실동작] 1001점 → **400**. **경계 실측** — 정확히 1000점은 **200** | `Sam2TrackRequest.java:24` |
| TC-SAM2-20 | PASS | [실동작] `trackId` 65자 → **400** `"trackId: size must be between 0 and 64"`. **경계 실측** — 64자는 200(응답 `trackId` 64자 그대로 에코) | `Sam2TrackRequest.java:23`. `TRCK_ID VARCHAR(30)` 컬럼보다 크지만 track 은 미저장 경로라 정합 문제 없음 |
| TC-SAM2-21 | PASS | [실동작] 시작 `srcSn=1`(배정됨) + `nextSrcSns=[31]`(raw 5, 미배정) → **403** `"본인에게 배정되지 않은 영상입니다."`. **AI 호출 이전 실증** — ai-server 로그에 해당 track 요청 0건(루프 진입 즉시 `verifyAccess(nextSrcSn)` 에서 종료) | `Sam2TrackService.java:69`(시작), `:90`(후속) — 라인 정확 |
| TC-SAM2-22 | PASS | [실동작] `nextSrcSns=[999999]` → **404** `"프레임을 찾을 수 없습니다."` | `Sam2TrackService.java:92-93`. ⚠ 메시지는 실제로 `"후속 프레임을 찾을 수 없습니다: 999999"` 가 아닌 가드 단계(`:90` verifyAccess) 의 문구 — 후속 프레임은 인가 검사가 먼저라 `:93` 이 도달 불가(무해, 코드 도달성 노트) |
| **TC-SAM2-23** | **FAIL** | [정적+실동작 반증] 기대 **502 EXTERNAL_API_ERROR** ↔ 실제 **400 INVALID_INPUT**. `Sam2TrackService.java:119` 가 요청 검증과 **동일한** `validatePolygon(…)`(`:188-204`)을 재사용하는데 이 메서드는 `ErrorCode.INVALID_INPUT`(=400) 을 던진다. 게다가 ①**정점 수 하한 미검증**(비어있지 않기만 하면 통과 — segment 의 `MIN_POLYGON_POINTS=3` 상당 검사 없음) ②**이미지 경계 상한 미검증**(segment 의 `x>imgWidth` 상당 없음) ③`aiRes.score()` **미클램프**(segment 는 `clampScore` 로 [0,1]+4자리 반올림). 실동작 근거: `POST /frames/1/sam2-track` 응답 `score=4.187454578641336E-6` 원본 그대로 노출. 프로젝트 테스트 자체가 현행을 고정 — `Sam2TrackServiceTest#aiPolygonInvalid400` | → **C-ISSUE-82** |
| TC-SAM2-24 | PASS | [실동작] `shape:"BBOX"` → `{"points":[[22.0,7.0],[79.0,107.0]],"shapeType":"BBOX"}` = `[[minX,minY],[maxX,maxY]]` | `Sam2TrackService.java:171-185` 라인 정확 |
| TC-SAM2-25 | PASS | [실동작] `shape:"BBOX"` + `prevPolygon:[[5,5],[5.2,5],[5.2,5.2]]`(퇴화 시드) + `nextSrcSns:[2,3]` → **200** `{"tracked":[]}` — 두 프레임 모두 스킵되고 **예외 없이 정상 종료**(전체 추적 미중단). 서버 로그 `[Sam2Track] degenerate bbox skipped nextSrcSn=…` | `Sam2TrackService.java:128-137`(스킵) + `:181-183`(`MIN_BBOX_EXTENT=1.0`) 라인 정확 |
| TC-SAM2-26 | PASS | [실동작] `"shape":null` 명시 → 200 `shapeType:"POLYGON"` / `shape` 키 생략 → 동일. 잘못된 문자열(`"CIRCLE"`)은 Jackson 이 **400** `"요청 본문이 올바르지 않습니다."` 로 차단 | `Sam2TrackService.java:73` `req.shapeOrDefault()` + `Sam2TrackRequest.java:39-41` |
| TC-SAM2-27 | PASS | [정적] `Sam2TrackService.java:108-114` — `catch (Exception e)` → 로그는 `LogSanitizer.sanitize(e.getMessage())` 로만 남기고 클라이언트에는 일반화 메시지 `"SAM2 track 호출에 실패했습니다."` + `EXTERNAL_API_ERROR`(502). CWE-209 정합. 단위테스트 `Sam2TrackServiceTest#aiCallFailure502` + `#aiPolygonNull502` | ai-server 를 죽이는 것은 병렬 에이전트 방해라 미수행 |
| TC-SAM2-28 | PASS | [실동작] `trackId:"tid\r\nINJECTED-LOG-LINE"` 전송 → 서버 로그 **한 줄**로 `trackId=tidINJECTED-LOG-LINE`(CRLF 제거, 로그 라인 위조 실패). `docker logs klid-backend` 원문 확인 | `Sam2TrackService.java:144-146` `LogSanitizer.sanitize` — 라인 정확 (CWE-117) |
| TC-SAM2-29 | PASS | [실동작] **신고 구간 실데이터**(raw 27, `DE_IDENT_YN='F'`, `LS_DEIDENT_REPORT` OPEN) `srcSn=301` → **412** `{"message":"비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다.","errorCode":"PRECONDITION_FAILED"}`. **REVIEWER 도 동일 412**(역할 무관 프리컨디션 실증). **ai-server 호출 0건**(로그 무발생) = 전송 후 폐기가 아니라 **읽기 전 차단** | `Sam2SegmentService.java:96` → `FrameImageEncoder.java:133-136`(게이트 먼저) → `:206-217` — 라인 정확 |
| TC-SAM2-30 | PASS | [실동작] ①시작 프레임이 신고 구간(`srcSn=301`) → **412** ②**시작은 정상(raw 4 `srcSn=1`) + 후속만 신고 구간(`srcSn=302`) → 412** = 프레임마다 게이트 평가 실증 ③**인가 우선 순서 실증** — 미배정 WORKER(2002)가 같은 `srcSn=301` 호출 시 412 가 아니라 **403** → 412 가 미인가자에게 영상 상태를 알려주는 오라클이 되지 않음(CWE-209) | `Sam2TrackService.java:86`(시작), `:95`(후속) → `FrameImageEncoder.java:178-180` — 라인 정확 |
| TC-SAM2-31 | PASS | [정적] 소스 전수 스캔 — `grep -rn "encodeToBase64" backend/src` 결과 **javadoc 주석 5건뿐, 메서드 정의·호출 0건**. `FrameImageEncoder.encode(Path)` 는 `private`(`:190`). 외부 진입점은 `LsDataSrc` 를 받는 3개(`resolveFrameImageForInference` / `encodeFrame` / `encodeDeidentifiedFrameForInference`)뿐이며 **전부 게이트 통과** | `FrameImageEncoder.java:182-197` 라인 정확. 실소비자 실측: `Sam2SegmentService:96`·`Sam2TrackService:86,95`·`YoloTrackService:116`·`AutolabelOnlineService:222`·`PortalSam2Service:115,152,167` — 게이트 없는 경로 0건 |
| TC-SAM2-32 | PASS | [정적] `FrameImageEncoder.java:94` `Path resolveFrameImageWithoutGate(LsDataSrc)` — **접근제어자 없음 = package-private** 확인. 소비자 전수 `grep` 결과 `FrameBoundsResolver.java:101` **1건뿐**이고, 그 사용처(`measure()`, `:100-120`)는 `ImageIO.read` 후 `img.getWidth()/getHeight()` **치수만** 반환(픽셀·경로 미유출) | ⚠ **카탈로그 서술 오류**: "유일한 **패키지 외** 소비자"라고 적혀 있으나 `FrameBoundsResolver` 는 **동일 패키지** `kr.co.cudo.authoring.label.service`(파일 1행 확인). 단언의 실질(package-private + 치수만 소비)은 성립 |
| TC-SAM2-33 | PASS | [실동작] 해상도/증강 파생 프레임 `srcSn=45`(raw 18, `SRC_FILE_PATH_NM` **NULL**, deid 경로만 존재) → **200** `{"polygon":[[76,9],…,[78,9]],"score":0.9652}`. 400 `"이미지 경로가 비어있습니다"` **미발생** | `FrameImageEncoder.java:94-115` — deid 우선 → `verifyDeidentifiedFile` ok → 반환. 라인 정확 |

### C-3 집계

| 판정 | 건수 |
|---|---:|
| PASS | 32 |
| FAIL | 1 (TC-SAM2-23) |
| PARTIAL / BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **33** |

근거 확인 내역: **[실동작] 24건 · [정적] 9건**(08·10·11·13·27·31·32 + 06·07 은 실동작+정적 혼합).

---

## 2. C-4. TC-KEYPOINT 케이스별 판정 (13건)

검증 경로: `PUT /api/v1/frames/318/labels`(raw 35, 라벨 0건·미배정 → REVIEWER 토큰). **검증 후 `{"items":[]}` 로 원복**하여 `ls_data_lbl` 0건 상태 복구 확인.

| ID | 판정 | 근거 확인 | 비고 |
|----|------|-----------|------|
| TC-KEYPOINT-01 | PASS | [실동작] `lblTypeCd=SKELETON` + `[[10,20,2]]×17` → **200**. DB 실측 `select point_cn from ls_data_lbl where src_sn=318` → `[[10.0,20.0,2],[10.0,20.0,2],…]` **17개 삼중값**(x·y=double, v=int). 응답 `labelVersion` 1→2 증가 | `LabelService.java:792-825`(검증) + `:834-838`(`serializePoints` SKELETON 분기 → `KeypointSerializer.toJson`). 라인 정확. 테스트 `LabelServiceKeypointTest#SKELETON_17개_삼중값_저장후_조회시_동일값_반환` |
| TC-KEYPOINT-02 | PASS | [실동작] 16개 → **400** `"SKELETON 키포인트는 정확히 17 개여야 합니다."`. **상한도 반증** — 18개도 동일 400, 3개도 동일 400 (`!=` 비교라 양방향 차단) | `LabelService.java:793-796` 라인 정확 |
| TC-KEYPOINT-03 | PASS | [실동작] `[[10,20]]×17`(2-튜플) → **400** `"키포인트는 [x, y, v] 형태여야 합니다."`. **초과도 반증** — `[10,20,2,9]`(4원소) 동일 400. `null` 원소(triplet 자체가 null) 도 동일 400 | `LabelService.java:797-801` 라인 정확 |
| TC-KEYPOINT-04 | PASS | [실동작] `[10,20,null]` → **400** `"키포인트 좌표에 null 원소가 있습니다."` — **500 아님**(언박싱 NPE 방어 성립). 역방향 반증: x·y 위치 null 도 같은 분기 | `LabelService.java:806-811` — `Double xBox/yBox/vBox` 를 언박싱 **이전**에 null 체크. 카탈로그 `:805-811` 정확 |
| TC-KEYPOINT-05 | PASS | [실동작] `v=3` → **400** `"가시성 v 는 0/1/2 중 하나여야 합니다."`. **추가 반증** — `v=1.5`(소수) 도 동일 400 (`int v=(int)vRaw; if (v != vRaw …)` 로 비정수 차단), `v=-1` 도 400 | `LabelService.java:815-819`(카탈로그 816-819) |
| TC-KEYPOINT-06 | PASS | [실동작] `[[0,0,0]]` + 나머지 16개 → **200** 저장 성공. DB `point_cn` 선두가 `[0.0,0.0,0]` 로 보존 | `LabelService.java:820-823` — `x<0\|\|y<0` 만 차단하므로 0 은 통과. 라인 정확 |
| TC-KEYPOINT-07 | PASS | [실동작] `[-1,20,2]` → **400** `"좌표는 0 이상이어야 합니다 (x=-1.0, y=20.0)"` | `LabelService.java:820-823`. ⚠ 실제 구현은 **v 값과 무관하게** 음수를 차단(카탈로그의 "v>0" 한정보다 엄격 — 안전 방향). ⚠ **테스트 공백** → C-ISSUE-85 |
| TC-KEYPOINT-08 | PASS | [실동작] DB 직렬화 결과가 `[[10.0,20.0,2],…]` 로 **결정적**(x·y 는 `double`, v 는 `int` — 타입 혼합이 아니라 `KeypointPoint` 레코드 타입 그대로). 단위테스트 `KeypointSerializerTest#toJsonTripletFormat` 이 `"[[1.5,2.5,2]]"` 정확 일치 단언 | `KeypointSerializer.java:46-60` — `List.of(kp.x(), kp.y(), kp.v())` 순서 고정. 라인 정확 |
| TC-KEYPOINT-09 | PASS | [정적] `KeypointSerializer.java:80-82` — `if (!root.isArray()) throw new IllegalArgumentException("키포인트는 배열이어야 합니다")`. 예외 메시지에 원본 JSON 미포함(CWE-117/209) | ⚠ **테스트 공백** — `KeypointSerializerTest` 4건 중 비배열 케이스 없음 → C-ISSUE-85 |
| TC-KEYPOINT-10 | PASS | [실동작+정적] `:85-87` `!triplet.isArray() \|\| triplet.size() != TRIPLET_SIZE` → IAE. 단위테스트 `#rejectsTwoTuple`(`"[[1.0,2.0]]"`). 서비스 경유 실동작으로도 2원소/4원소 모두 400 확인(TC-KEYPOINT-03) | 라인 정확 |
| TC-KEYPOINT-11 | PASS | [정적] `:88-93` — `x/y/v` 각각 `isNumber()` 검사. 문자열 원소·JSON `null` 원소(`NullNode.isNumber()==false`) 모두 IAE | ⚠ **테스트 공백** → C-ISSUE-85. 서비스 요청 표면은 `List<List<Double>>` 역직렬화라 문자열이 400 으로 먼저 차단됨(이 분기는 DB 적재값 재파싱 경로 방어) |
| TC-KEYPOINT-12 | PASS | [정적] `:70-72` — `json == null \|\| isBlank() \|\| "[]".equals(json.trim())` → `List.of()`. `"[ ]"` 같은 변형도 `readTree` → 빈 배열 → 루프 미실행 → 빈 리스트. 단위테스트 `#emptyInputs`(null·`"[]"` 양쪽) | 카탈로그 `:69-81` 범위 포함 |
| TC-KEYPOINT-13 | PASS | [실동작] 삼중값 저장(`lbl_sn=600`) 후 **동일 값 재저장** → 200 이지만 `labelVersion` **2 그대로**(미증가) 이고 `LS_DATA_LBL_HSTRY` 행 **추가 0건**(59·60 이후 신규 없음) = 무변경 판정 성립. 이어서 첫 점만 `[11,20,2]` 로 변경 → hstry `61` `kind:"UPDATED"` 생성 + before/after 삼중값 보존 | `LabelService.java:568-585` — `LabelPointSerializer.fromJson`(2-튜플 파서)이 삼중값을 거부 → `catch` → raw `List<List<Double>>` 폴백 비교. 라인 정확. ⚠ **테스트 공백** → C-ISSUE-85 |

### C-4 집계

| 판정 | 건수 |
|---|---:|
| PASS | 13 |
| FAIL / PARTIAL / BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **13** |

근거 확인 내역: **[실동작] 9건 · [정적] 4건**(09·11·12 + 10 은 혼합).

### C-4 추가 반증(카탈로그 외 경계값) — 전부 fail-secure 확인

| 반증 입력 | 결과 |
|---|---|
| `SKELETON` + `x=99999` (이미지 320×240) | **400** `"좌표가 이미지 경계를 벗어났습니다 (x=99999.0, y=20.0, 이미지=320x240)"` — 상한 검증이 삼중값 경로에도 적용됨(`LabelService.validateWithinBounds:738-742` 문서화된 SKELETON 분기 실증, ★3 정합) |
| `SKELETON` + `x=1e308` | **400** 동일(오버플로 우회 없음) |
| `SKELETON` + `x=NaN` | **400** `"요청 본문이 올바르지 않습니다."` — Jackson 이 `ALLOW_NON_NUMERIC_NUMBERS` 미활성이라 역직렬화 단계에서 차단. **NaN 이 `x<0`·`x>width` 를 둘 다 우회해 저장되는 시나리오는 성립하지 않음** |
| `BBOX` + 삼중값 좌표 | **400** `"좌표는 [x, y] 형태여야 합니다."` — 타입 라우팅 역방향 차단 |
| SAM2 track `prevPolygon` + `NaN` / segment `points` + `NaN` | 둘 다 **400**(Jackson 단계) |

---

## 3. 총계 (46건)

| 클러스터 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| C-3 TC-SAM2 | 33 | 32 | 1 | 0 | 0 | 0 | 0 |
| C-4 TC-KEYPOINT | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **46** | **45** | **1** | **0** | **0** | **0** | **0** |

> ⚠ **PASS 율이 높다는 사실 자체가 검증 품질의 근거가 아니다.** 카탈로그가 단언한 항목은 대부분 성립했으나, **카탈로그가 묻지 않은 축**에서 HIGH 1건(C-ISSUE-81) · MEDIUM 2건(C-ISSUE-82/83)이 나왔다. 아래 이슈 대장이 본 파트의 실질 산출물이다.

---

## 4. 이슈

### [C-ISSUE-81] TC-SAM2-14/24/26 (카탈로그 미커버 축) — SAM2 **Track** 경로에 mock 게이트가 없어 mock 좌표가 정상 결과로 FE 에 자동 적용된다

- **심각도**: **HIGH**
- **기대 동작(기대효과)**: ai-server 가 mock 응답(`mock=true`, 사유 `env_mock`/`weights_missing`/`load_failed`/`empty_mask`)을 반환하면 BE 가 이를 감지해 **좌표를 그대로 내려보내지 않아야** 한다. segment 경로가 이미 그렇게 한다(`Sam2SegmentService.java:124-127` → 빈 폴리곤 + `MOCK_UNAVAILABLE_MESSAGE` → `OverlayLayer.tsx:578-583` 자동적용 차단). 이 보호가 없으면 **모델이 없는 상태에서 만들어진 가짜 좌표가 학습데이터로 확정**된다(SFR-08 라벨링 정확도 근간 훼손).
- **현재 동작(이슈 내용)**: **BE 의 클라이언트 DTO 가 mock 신호를 아예 파싱하지 않는다.**
  ```java
  // backend/.../common/client/dto/Sam2TrackResponse.java:14-18  — mock/source/mock_reason 없음
  public record Sam2TrackResponse(
          @JsonProperty("track_id") String trackId,
          List<List<Double>> polygon,
          double score) {}
  ```
  ai-server 는 실제로 그 필드를 내보낸다(실측):
  ```
  GET http://localhost:19300/openapi.json
  → Sam2TrackResponse  ['track_id','polygon','score','mock','source','mock_reason']
    Sam2SegmentResponse ['polygon','score','mock','source','mock_reason','success','message','error_code']
  ```
  따라서 `Sam2TrackService.track()`(`:105-142`)·`PortalSam2Service.track()`(`:173-181`) 어디에도 `aiRes.mock()` 검사가 없다(`grep -rn "\.mock()" backend/src/main` → segment 2곳·online autolabel 2곳·YOLO 배치 1곳만 히트, **track 0곳**).
  ai-server 의 mock track 은 특히 위험하다 — `ai-server/app/routers/sam2.py:317-326`:
  ```python
  def _mock_track(req, reason="env_mock"):
      return Sam2TrackResponse(track_id=req.track_id,
          polygon=[list(p) for p in req.prev_polygon],   # 시드 폴리곤 그대로 복사
          score=0.9, mock=True, source="mock", mock_reason=reason)
  ```
  **score 0.9** 라 FE 의 저신뢰 분기(`SAM_LOW_CONFIDENCE_THRESHOLD`)에도 걸리지 않는다. 즉 SAM2 가중치가 없으면 "N 프레임 추적"이 **시드 폴리곤 N개 복제**로 조용히 둔갑한다.
- **재현/확인 경로**:
  1. 현 스택은 SAM2 가중치가 **있어서** 실모델로 동작한다. 그러나 **동일 컨테이너의 YOLO 는 이미 `weights_missing`** 이다(`docker logs klid-ai-server` → `[DETECT:yolox][MOCK] … reason=weights_missing`) — SAM2 가중치 누락도 동일하게 발생 가능한 배포 상태다.
  2. `ai-server` 를 `AI_MOCK_MODE=true` 로 기동하거나 SAM2 가중치를 제거한 뒤:
     `curl -X POST -H "Authorization: Bearer <WORKER>" -d '{"srcSn":1,"trackId":"t","prevPolygon":[[10,10],[100,10],[100,100]],"label":"person","nextSrcSns":[2,3]}' localhost:18081/api/v1/frames/1/sam2-track`
     → 기대: 빈 결과 + 경고 메시지 / 실제(예상): 200 + 시드 폴리곤 복제 2건 + `score 0.9`, 경고 없음.
  3. 대조군(현행 정상 동작): 같은 스택에서 segment 는 mock 유도 시 차단됨 — `-d '{"srcSn":1,"box":[10000,10000,10001,10001]}'` → `{"polygon":[],"empty":true}` + `"message":"AI 모델 미로드 — 결과 신뢰 불가"`.
- **영향**: 데이터 정합/학습데이터 오염(CWE-345 불충분한 데이터 진정성 검증). 라벨은 검수 승인 시 `LS_LABEL_VERSION` 스냅샷 + export + 관제 `TASK_COMPLETED` 로 흘러가므로 오염이 데이터마트까지 전파된다. 포털 SAM2 track(`PortalSam2Service.track`)도 동일 결함이며, 포털은 오토라벨 미제공 채널이라 사용자가 결과를 신뢰할 근거가 더 약하다.
- **수정 방향(제안)**: ⚠ 구현하지 않음.
  1. `common/client/dto/Sam2TrackResponse` 에 `mock` / `source` / `@JsonProperty("mock_reason") mockReason` 3필드 추가(`Sam2Response` 와 동일 형태 + 기존 3-arg 호환 생성자 유지).
  2. `Sam2TrackService.track()` 의 응답 검증 지점(`:115-119`)에 `if (aiRes.mock()) { … }` 을 넣어 **해당 프레임을 결과에서 제외**하고, 루프 종료 후 mock 이 1건이라도 있었으면 컨트롤러가 `Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE` 와 동등한 안내를 `ApiResponse.message` 에 세팅(= segment 규약과 동형).
  3. `PortalSam2Service.track()` 에도 같은 배선(현재 segment 만 `:125` 에 있음).
  4. 회귀 가드: "ai-server 가 내보내는 mock 메타를 BE DTO 가 전부 보유한다"는 계약 드리프트 테스트(기존 `CocoClassesDriftTest` 패턴 재사용).
  5. 카탈로그에도 대응 케이스 신설 권장 — `TC-SAM2-3x: track mock→결과 제외+안내 메시지`(현재 C-3 에 track mock 케이스가 **없다**).

---

### [C-ISSUE-82] TC-SAM2-23 — SAM2 track 의 **외부 응답** 검증이 요청 검증과 동일 코드라 400 으로 나가고, 정점 수·이미지 경계·score 를 검증하지 않는다

> ⚠ 이 번호는 본 파트 신규 번호다. `UNCERTAINTIES.md` 이월표의 구 `C-ISSUE-82`(dead `common/util/TrackInterpolator`)와 무관.

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과는 **502 EXTERNAL_API_ERROR**. 외부 시스템(ai-server)의 잘못된 응답을 **클라이언트 입력 오류(400)로 오귀속하면 안 된다** — FE·운영자가 "내가 잘못 보냈다"로 오진단해 실제 장애(모델 이상·계약 드리프트)를 놓친다. segment 경로는 이미 502 로 처리한다(`Sam2SegmentService.java:159-175`). 또한 "외부 응답 불신" 원칙상 **정점 수 하한·이미지 경계 상한·score 범위**를 segment 와 동등하게 검사해야 한다.
- **현재 동작(이슈 내용)**: 요청 검증과 응답 검증이 **같은 메서드**를 공유한다.
  ```java
  // Sam2TrackService.java:71   (요청)
  validatePolygon(req.prevPolygon(), "prevPolygon");
  // Sam2TrackService.java:119  (외부 응답) — 같은 메서드
  validatePolygon(aiRes.polygon(), "ai-server polygon");

  // Sam2TrackService.java:188-204
  private void validatePolygon(List<List<Double>> polygon, String fieldName) {
      if (polygon == null || polygon.isEmpty())            // ← 정점 수 하한(3) 검사 없음
          throw new CustomException(ErrorCode.INVALID_INPUT, …);   // ← 400
      … if (… x < 0 || y < 0) throw new CustomException(ErrorCode.INVALID_INPUT, …);  // ← 상한 검사 없음
  }
  ```
  결과 3가지 갭:
  ① **응답코드 불일치** — `ErrorCode.INVALID_INPUT` = `HttpStatus.BAD_REQUEST`(`ErrorCode.java:6`), 기대는 `EXTERNAL_API_ERROR` = `BAD_GATEWAY`(`ErrorCode.java:28`). 프로젝트 테스트가 현행을 고정 중 — `Sam2TrackServiceTest#aiPolygonInvalid400`.
  ② **정점 수 미검증** — ai-server 가 1~2점 폴리곤을 돌려줘도 통과해 FE 로 나간다(segment 는 `MIN_POLYGON_POINTS=3` 으로 502).
  ③ **경계 상한·score 미검증** — segment 는 `x>imgWidth` 502 + `clampScore` [0,1] 4자리 반올림. track 은 둘 다 없어 실측 응답이 `"score":4.187454578641336E-6` 로 원본 노출:
  ```
  POST /api/v1/frames/1/sam2-track → 200
  {"tracked":[{"srcSn":2,…,"score":4.187454578641336E-6,"shapeType":"POLYGON"}]}
  ```
- **재현/확인 경로**:
  - 코드 대조: `Sam2TrackService.java:119,188-204` vs `Sam2SegmentService.java:129,159-175`.
  - score 미클램프 실동작: `curl -X POST -H "Authorization: Bearer <WORKER>" -H 'Content-Type: application/json' -d '{"srcSn":1,"trackId":"t","prevPolygon":[[10,10],[100,10],[100,100]],"label":"p","nextSrcSns":[2]}' localhost:18081/api/v1/frames/1/sam2-track` → 응답 `score` 필드 확인.
  - ①②는 ai-server 응답 조작이 필요(스텁) — 단위테스트 레벨 재현.
- **영향**: 기능/운영(오진단 유도) + 데이터 정합(퇴화 폴리곤·경계 밖 좌표가 FE 작업본에 유입 → 이후 `PUT /labels` 저장 시 `validateWithinBounds` 가 400 을 내 사용자에게 "저장 실패"로 나타남 — 원인이 상류에 있는데 하류에서 터진다). 보안 관점은 낮음(좌표 자체는 인가된 프레임 것).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `validatePolygon(polygon, fieldName)` 을 **요청용/응답용 2개로 분리** — 응답용은 `ErrorCode.EXTERNAL_API_ERROR` + `MIN_POLYGON_POINTS(3)` + 이미지 실측 경계 상한(track 도 `FrameImageEncoder`/`FrameBoundsResolver` 로 치수 확보 가능) + `clampScore` 를 segment 와 동일 적용. 기존 `#aiPolygonInvalid400` 테스트는 기대값을 502 로 갱신. 판정 로직을 두 서비스가 공유하도록 공용 검증기로 추출하는 편이 재발 방지에 낫다.

---

### [C-ISSUE-83] TC-SAM2-01 (부수) — `Sam2SegmentService` 가 `@Transactional` 을 유지해 AI 블로킹 호출 내내 DB 커넥션을 점유한다 (track 은 이미 제거됨)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SAM2 분할은 **DB write 가 없는 stateless 프록시**다(클래스 javadoc `:40` "BE 는 DB 저장하지 않는 stateless 프록시"). 파일 I/O + base64 + **최대 60s 블로킹 AI 호출** 구간에서 control HikariCP 커넥션을 쥐고 있으면 안 된다. `Sam2TrackService` 는 이미 그렇게 고쳤고 그 이유를 코드에 남겼다 — `Sam2TrackService.java:34-36`: *"비트랜잭셔널(F-1 커넥션풀 고갈 방지): DB write 가 없으므로 @Transactional 을 제거했다. AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다."* 프로젝트 규약도 같다(`CLAUDE.md` — "프레임 이미지 서빙은 트랜잭션 밖에서 파일 I/O 를 한다", "커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아로 간다 — 전례 있음").
- **현재 동작(이슈 내용)**: segment 만 트랜잭션이 남아 있다.
  ```java
  // Sam2SegmentService.java:58-62
  @Service
  @RequiredArgsConstructor
  @Transactional(value = "controlTransactionManager", readOnly = true)   // ← 남아 있음
  public class Sam2SegmentService {
      public Sam2SegmentResponse segment(...) {   // :84  — 프록시 경유 public
          accessGuard.verifyAccess(...);          // :86  DB 접근 → 커넥션 획득
          …
          aiRes = aiServerClient.segment(aiReq).block();   // :114  최대 60s 블로킹 (AiServerClient.java:73)
  ```
  **실측(반증 시도로 확인)** — segment 6건 동시 호출 중 `pg_stat_activity` 샘플링:
  ```
  ### segment ×6 동시
   state               | count
   active              |     1
   idle                |     4
   idle in transaction |     6     ← 6건 전부 AI 호출 동안 커넥션 점유
  ### track ×6 동시 (대조군)
   state  | count
   active |     1
   idle   |    10                  ← idle in transaction 0건
  ```
  운영 풀은 `application-prd.yml:20,27` `maximum-pool-size: 20`(control/portal 각각). 라벨링 작업자 20명이 동시에 클릭 분할을 하면 control 풀이 소진되며, ai-server 가 느려질수록(60s 타임아웃) 점유 시간이 그대로 늘어난다.
- **재현/확인 경로**:
  ```bash
  for i in 1 2 3 4 5 6; do curl -s -o /dev/null -X POST -H "Authorization: Bearer <WORKER>" \
    -H 'Content-Type: application/json' -d "{\"srcSn\":$i,\"points\":[[50,50]]}" \
    localhost:18081/api/v1/frames/$i/sam2-segment & done
  sleep 1.2
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select state, count(*) from pg_stat_activity where datname='klid_system' group by 1;"
  ```
- **영향**: 가용성/성능(CWE-400 Uncontrolled Resource Consumption · OWASP API4:2023). 커넥션 기아 시 SAM2 와 무관한 API(라벨 저장·검수·배치)까지 동반 지연/실패한다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `Sam2SegmentService` 의 클래스 `@Transactional` 을 제거하고, DB 조회(`accessGuard.verifyAccess` + `srcRepository.findById` + `resolveFrameImageForInference` 의 게이트 조회)만 별도 `@Transactional(readOnly)` 조회 빈으로 분리한 뒤 **파일 I/O·base64·AI 호출은 트랜잭션 밖**에서 수행한다 — `FrameImageLookupService` 가 이미 같은 목적으로 존재하므로 그 패턴을 따르면 된다. ⚠ 자기호출(self-invocation)로 프록시를 우회하면 효과가 없다.

---

### [C-ISSUE-84] TC-SAM2-06 — `Sam2SegmentService` 의 경로가드가 dead code 이고 클래스 javadoc 이 실제 동작(비식별 프레임 사용)과 반대로 적혀 있다 (근거 드리프트 포함)

- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그·주석이 가리키는 방어 지점이 **실제로 실행되는 코드**여야 한다. 그렇지 않으면 다음 검증자·수정자가 "여기서 막고 있다"고 믿고 실제 방어선(`FrameImageEncoder`)의 회귀를 놓친다(프로젝트가 이미 겪은 패턴 — `CLAUDE.md`: *"컨트롤러가 정책 판정을 복제 보유하면 정책 갱신 때 조용히 뒤처진다"*).
- **현재 동작(이슈 내용)**: 3건.
  1. **dead code** — `Sam2SegmentService.java:179-188` `resolveSafe(Path baseDir, String relativePath)` 는 `segment()` 에서 호출되지 않는다(`grep -n "resolveSafe" Sam2SegmentService.java` → `:52`(javadoc) · `:179`(정의) 뿐). 함께 `@Value` 필드 `storageRawPath`(`:77-78`)도 미사용. 실제 경로가드는 `FrameImageEncoder`(비식별=`StorageSubtreePolicy.verifyDeidentifiedFile` realpath 판정 / 원본=`FrameImageEncoder.resolveSafe:220-229`).
  2. **javadoc 이 동작과 반대** — `:42-44`: *"SAM2 는 **원본 이미지에만 실행**한다 … `srcFilePathNm` = 원본 프레임 경로 사용"*. 실제 `:96` 은 `resolveFrameImageForInference` → `FrameImageEncoder:98-105` **비식별 경로 우선**이다.
     **실동작 반증(결정적)**: `raw_sn=4 frame-0` 은 원본/비식별 파일의 md5 가 다르다(`be89bcc1…` vs `efadbd02…`). ai-server 에 두 파일을 직접 넣어 비교하면
     - 원본 → `…[80.0,176.0],[81.0,177.0],[81.0,178.0],[95.0,178.0],[96.0,179.0],[97.0,178.0],[105…`
     - 비식별 → `…[80.0,176.0],[81.0,177.0],[81.0,178.0],[119.0,178.0],[119.0,6.0],[120.0,5.0],[120.0…`

       BE 응답은 `[[90,0],[80,8],[81,178],[119,178],[119,0]]` → **비식별본 계열과 일치**. 즉 SAM2 분할은 비식별 프레임으로 실행된다.
     (동작 자체는 2026-07-30 확정 정책 "라벨링 캔버스는 비식별 프레임을 서빙한다" 와 정합하며 **결함이 아니다** — 캔버스가 보여주는 픽셀과 분할 대상이 같아야 좌표가 맞는다. 문제는 **주석·`CLAUDE.md` "오토라벨링" 절이 갱신되지 않은 것**.)
  3. **근거 드리프트(카탈로그 정합성)** — TC-SAM2-06 의 `Sam2SegmentService.java:179` 는 dead code 지시. 부수적으로 TC-SAM2-32 서술 "유일한 **패키지 외** 소비자 `FrameBoundsResolver`" 는 사실과 다르다(`FrameBoundsResolver` 는 동일 패키지 `kr.co.cudo.authoring.label.service`).
  - 부가 관찰(별도 이슈 아님, LOW): `FrameImageEncoder` 의 **비식별** 분기는 realpath 기반(TOCTOU/심링크 방어, `StorageSubtreePolicy:201-215`)인데 **원본** 분기(`:220-229`)는 lexical 검증만 하고 `NOFOLLOW` 없이 연다. 경로값이 배치 소유(DB)라 현재 사용자 도달 경로는 없으나, `CLAUDE.md` 가 서빙 4경로에 요구하는 규약과 비대칭이다.
- **재현/확인 경로**: `grep -n "resolveSafe\|storageRawPath" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java` → 호출부 0건 확인. 비식별 사용 실증은 위 md5 + ai-server 직접 호출 대조.
- **영향**: 유지보수/검증 신뢰도(감사 지적 가능 — 주석이 보안 통제를 잘못 서술). 런타임 보안 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ①`Sam2SegmentService` 의 미사용 `resolveSafe`·`storageRawPath` 제거 ②클래스 javadoc `:42-44`·`:52` 를 "비식별 우선 해석 + 경로가드·신고게이트는 `FrameImageEncoder` 단일 위임"으로 정정 ③`CLAUDE.md` "오토라벨링" 절에 **온라인(사용자 트리거) SAM2 = 비식별본 / 배치 YOLO·SAM2 = 원본** 구분 명시 ④카탈로그 TC-SAM2-06 근거를 `FrameImageEncoder.java:220-229`(+`StorageSubtreePolicy.java:181-216`)로, TC-SAM2-32 서술을 "동일 패키지 유일 소비자"로 정정.

---

### [C-ISSUE-85] TC-KEYPOINT-07/09/11/13 — 자동테스트 공백 (동작은 정상, 회귀 가드 부재)

- **심각도**: LOW
- **기대 동작(기대효과)**: 키포인트는 `LBL_TYPE_CD` 기반 type-route 로 기존 2-튜플 경로와 격리된 분기라(`KeypointSerializer` javadoc `:14-16`), 분기 삭제·머지 시 조용히 깨지기 쉽다. 검증한 방어는 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: 실측 커버리지 대조 결과 4건 무커버.

  | 케이스 | 방어 위치 | 테스트 |
  |---|---|---|
  | TC-KEYPOINT-07 음수 좌표(SKELETON) | `LabelService.java:820-823` | **없음** (`LabelServiceKeypointTest` 11건 중 음수 케이스 0건) |
  | TC-KEYPOINT-09 `fromJson` 비배열 | `KeypointSerializer.java:80-82` | **없음** (`KeypointSerializerTest` 4건: roundTrip / toJsonTripletFormat / emptyInputs / rejectsTwoTuple) |
  | TC-KEYPOINT-11 `fromJson` 숫자 아님 | `KeypointSerializer.java:91-93` | **없음** |
  | TC-KEYPOINT-13 SKELETON R7 무변경 폴백 | `LabelService.java:568-585` | **없음** (`grep -rn "normalizePoints" backend/src/test` 무결과) |

  ※ 4건 모두 본 검증에서 실동작/정적으로 **정상 확인**됨 — 결함은 "동작"이 아니라 "가드 부재"다. `_raw/test-baseline.md` 기준 backend 실패 0건이라 이 공백은 baseline 으로 드러나지 않는다.
- **재현/확인 경로**: `grep -n "DisplayName" backend/src/test/java/kr/co/cudo/authoring/common/util/KeypointSerializerTest.java` (4건) · `.../label/LabelServiceKeypointTest.java` (11건) · `grep -rn "normalizePoints" backend/src/test` (0건).
- **영향**: 회귀 감지력. 특히 TC-KEYPOINT-13 은 무커버 상태에서 `normalizePoints` 폴백이 사라지면 **SKELETON 라벨이 매 저장마다 "변경됨"으로 오판정**되어 `LS_DATA_LBL_HSTRY` 와 `TASK_MODIFIED` 관제 통지가 무한 증식한다(운영 영향이 조용하고 크다).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `KeypointSerializerTest` 에 비배열(`"{}"`)·비숫자(`"[[\"a\",2,1]]"`)·null 원소(`"[[1,2,null]]"`) 3건 추가, `LabelServiceKeypointTest` 에 음수 좌표 400 1건 추가, R7 무변경 폴백은 "SKELETON 동일값 재저장 시 이력 0건·버전 미증가" 통합테스트로 추가(본 검증의 실동작 시나리오를 그대로 코드화하면 된다).

---

### [C-ISSUE-86] TC-SAM2-09 (부수) — mock 안내 메시지가 사유와 무관하게 "AI 모델 미로드"로 고정돼 오진단을 유도한다

- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자·운영자에게 나가는 안내는 실제 사유와 일치해야 한다. ai-server 는 사유를 4종으로 구분해 내려준다(`mock_reason` ∈ `env_mock` | `weights_missing` | `load_failed` | `empty_mask`).
- **현재 동작(이슈 내용)**: BE 는 사유를 무시하고 단일 문자열을 세팅한다.
  ```java
  // Sam2SegmentResponse.java:23
  public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";
  // LabelController.java:173-177 — res.isEmpty() 이면 무조건 위 문자열
  ```
  **실측**: 현 스택은 SAM2 **모델이 정상 로드**된 상태인데, 마스크가 안 잡히는 프롬프트(`box:[10000,10000,10001,10001]` 또는 역박스 `[100,100,10,10]`)를 주면 ai-server 가 `mock_reason=empty_mask` 로 폴백하고 FE 에는 **"AI 모델 미로드"** 가 표시된다. 실제로는 "프롬프트 위치에 객체가 없음"이다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST -H "Authorization: Bearer <WORKER>" -H 'Content-Type: application/json' \
    -d '{"srcSn":1,"box":[100,100,10,10]}' localhost:18081/api/v1/frames/1/sam2-segment
  # → {"data":{"polygon":[],"score":0.0,"empty":true},"message":"AI 모델 미로드 — 결과 신뢰 불가"}
  # 동시에 ai-server 는 모델 로드 상태(실추론 로그 존재, AI_MOCK_MODE=false)
  ```
- **영향**: 운영/UX. 작업자가 "서버 장애"로 오인해 불필요한 에스컬레이션을 하거나, 반대로 진짜 `weights_missing` 상황이 "늘 뜨는 메시지"로 묻힌다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `Sam2Response.mockReason()` 을 BE 가 이미 파싱하고 있으므로(`common/client/dto/Sam2Response.java:24`) 이를 사유별 메시지로 매핑한다 — `empty_mask` → "선택 지점에서 객체를 찾지 못했습니다 — 다른 위치를 클릭해 주세요", `weights_missing`/`load_failed`/`env_mock` → 현행 "AI 모델 미로드 — 결과 신뢰 불가". **자동적용 차단(빈 폴리곤) 동작은 두 경우 모두 그대로 유지**한다. FE `OverlayLayer.onMockWarning` 은 `res.message` 를 그대로 표시하므로 BE 만 고치면 된다.

---

## 5. 근거 드리프트 목록 (카탈로그 자체 정합성)

| 케이스 | 카탈로그 근거/서술 | 실제 |
|---|---|---|
| TC-SAM2-06 | `Sam2SegmentService.java:179` (`resolveSafe`) | **dead code** — 호출부 0건. 실제 가드는 `FrameImageEncoder.java:220-229` + `StorageSubtreePolicy.java:181-216` |
| TC-SAM2-23 | 기대결과 `502 EXTERNAL_API_ERROR` | 실제 **400 INVALID_INPUT** (→ C-ISSUE-82. 카탈로그 기대값이 옳고 구현이 어긋난 케이스) |
| TC-SAM2-32 | "유일한 **패키지 외** 소비자 `FrameBoundsResolver`" | `FrameBoundsResolver` 는 **동일 패키지**(`label.service`) |
| TC-SAM2-01 | `Sam2SegmentService.java:84-149` | `segment()` 는 `84-153` |
| TC-SAM2-09 | `LabelController.java:174-178` | `173-177` (off-by-1) |
| TC-SAM2-13 | `Sam2SegmentService.java:132-143` | 단순화 블록 `131-147`, 3점미만 폴백 `144-147` |
| TC-KEYPOINT-05 | `LabelService.java:816-819` | `815-819` |
| — (미커버) | C-3 에 **track mock 게이트 케이스가 없다** | segment 만 TC-SAM2-09 로 커버 → C-ISSUE-81 대응 케이스 신설 필요 |

> 그 외 TC-SAM2-02~05/07/08/10/11/12/14~22/24~31/33 및 TC-KEYPOINT-01~04/06~13 의 `file:line` 은 실측과 일치(드리프트 없음).

## 6. self-fill 관점 점검

- SAM2 분할/추적은 **좌표를 자체 생성하지 않는다** — 전량 ai-server 응답에서 온다(`Sam2SegmentService:114` / `Sam2TrackService:107`). 응답이 null/폴리곤 null 이면 502 로 마감(`:119-121` / `:115-117`), 하드코딩 폴백 좌표 없음. `score` 도 외부값 기반(segment 는 clamp, track 은 원본 — C-ISSUE-82).
- 단 **ai-server 내부의 mock 폴백**은 self-fill 성격을 갖는다(`_mock_segment`/`_mock_track` 이 좌표를 합성). segment 는 BE 가 이를 감지해 차단하므로 방어되고, **track 은 감지하지 못해 self-fill 결과가 그대로 통과한다 → C-ISSUE-81 이 본 파트의 self-fill 항목**이다.
- 키포인트는 전량 사용자 입력이며 서버 생성값 없음. 미지정 필드를 임의값으로 메우는 경로 없음(개수 17 강제).

## 7. 검증 중 발생시킨 상태 변경 (원복 완료)

| 대상 | 변경 | 원복 |
|---|---|---|
| `ls_data_lbl` (raw 35 / `src_sn=318`) | 키포인트 라벨 생성·수정(TC-KEYPOINT-01/06/13) | `PUT /v1/frames/318/labels {"items":[]}` → `select count(*) … where src_sn=318` = **0** (검증 전과 동일) |
| `ls_data_lbl_hstry` (`src_sn=318`) | 이력 3행(59/60/61) 적재 | append-only 감사 테이블이라 미삭제 — 원복 대상 아님(정상 동작의 산물) |
| raw 4 / raw 18 / raw 27 | SAM2 segment·track 호출만 (전부 미저장 경로) | 라벨 수 불변 확인(raw 4 = 10건 유지). 변경 없음 |
| `ls_deident_report` | **본 파트는 신고를 생성하지 않음** — 병렬 C-part4 에이전트가 만든 raw 27 의 OPEN 신고를 **읽기만** 하여 TC-SAM2-29/30 을 실동작 검증 | 해당 없음 |
# C클러스터 Part6 검증 결과 — C-5(TC-TRACK) + C-6(TC-PRESET)

- 담당 범위: `docs/test-cases/C-marking-labeling.md` `## C-5. TC-TRACK`(31건) + `## C-6. TC-PRESET`(18건) = 49건
- 검증 방식: 정적 대조(Read 소스+테스트) 전건. 빌드/테스트 실행 없이 `_raw/test-baseline.md`(2026-08-01, backend cleanTest 4,755/4,750 성공/0 실패) 결과를 근거로 대조.
- 대상 파일: `TrackInterpolator`(batch/interpolation, common/util 2종) · `PolyshapeMatcher` · `CoordinateTransformer` · `MaskRleConverter` · `YoloTrackService`/`YoloTrackRequest`/`DetectionBoxNormalizer`/`LabelController` · `preset/service/PresetService`(+엔티티/리포지토리/컨트롤러)

## C-5. TC-TRACK (31건)

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-TRACK-01 | PASS | [정적] batch/interpolation/TrackInterpolator.java:48-83; test `twoKeyframesInterpolateBetween`/`threeKeyframesInterpolatePerSegment` | 선형보간 수치 정확 확인(t=(f-k)/span) |
| TC-TRACK-02 | PASS | [정적] :53-55; test `emptyKeyframesProduceEmptyResult` | |
| TC-TRACK-03 | PASS | [정적] :57-64; test `singleKeyframeReturnsOnlyThatFrame` | |
| TC-TRACK-04 | PASS | [정적] :59-62; test `outsideKeyframeStopsProcessing` | |
| TC-TRACK-05 | PASS | [정적] :66-70; test `nextOutsideStoresCurrentOnlyAndStops` | |
| TC-TRACK-06 | PASS | [정적] :35-44(주석)/실제 로직은 마지막 키프레임 이후 loop 미진입; test `noPropagateAfterLastKeyframe` | |
| TC-TRACK-07 | PASS | [정적] :66-75 span≤0→continue, LinkedHashMap.put 재호출로 덮어씀; test `duplicateFrameKeyframeLastWins` | |
| TC-TRACK-08 | PASS | [정적] :50-52; test `negativeTotalFramesRejected` | |
| TC-TRACK-09 | PASS | [정적] :48-49 `Objects.requireNonNull`; test `nullKeyframesRejected` | |
| TC-TRACK-10 | PASS | [정적] :100-139 + PolyshapeMatcher.java:50-69(match 1회 계산·재사용); test `polygonVariableVertexCount` | 중간 프레임 정점수=max(a,b) 확인 |
| TC-TRACK-11 | PASS | [정적] closed/open 분기(:60-68, matchClosed/matchOpen); test `polylineAnchorInterpolated`·`polylineReversedDirectionInterpolated` | 개곡선 방향반전 감지(isReversed)까지 검증됨 |
| TC-TRACK-12 | PASS | [정적] CoordinateTransformer.java:21-41; test `rotate90Verified`·`rotateRoundtripIdentity` | ⚠아래 신규 이슈 참조 — 이 클래스는 프로덕션 미사용(호출부 0건) |
| TC-TRACK-13 | PASS | [정적] :22-27; test `rotateRejectsNullPoints`·`rotateRejectsNullCenter`·`rotateChecksPointsGuardBeforeCenterGuard` | **카탈로그 비고 정정 필요**: "전용 단위테스트 여전히 부재(C-ISSUE-81)"는 사실과 다름 — 해당 가드 테스트가 이미 존재(points→center 순서까지 검증). git log상 최초 커밋(2fd78c48)부터 존재해 최근 추가분도 아님. C-ISSUE-81 "가드 테스트 0%" 클레임은 CoordinateTransformer 한정으로는 **근거 드리프트**로 보임(카탈로그 정정 대상) |
| TC-TRACK-14 | PASS | [정적] :44-65; test `scaleRoundtripIdentity`·`translateRoundtripIdentity`·`scaleRejectsNullPoints`·`translateRejectsNullPoints` | |
| TC-TRACK-15 | PASS | [정적] MaskRleConverter.java:30-59(51-59 첫픽셀 on→0-run삽입); test `leadingOnInsertsZeroRun` `[0,2,1]` | |
| TC-TRACK-16 | PASS | [정적] :30-33; maskToRle(null)/maskToRle(new boolean[0][]) → `int[0]` | 전용 null 테스트는 rleToMask 쪽만 있고 maskToRle(null) 직접 단언 테스트는 없음(로직은 자명, 코드로 확인) |
| TC-TRACK-17 | PASS | [정적] :22,37-39; test `rejectsExcessivelyLargeMask`(1001x1001) | |
| TC-TRACK-18 | PASS | [정적] :44-47 row width 불일치 검사 | ⚠아래 신규 발견: `mask[0].length==0`(w=0, h>0)인 극단 입력은 이 가드를 통과해버리고 이후 `flat[0]` 접근에서 **ArrayIndexOutOfBoundsException**(비제어 예외) 발생 가능 — 단, 클래스가 프로덕션 미사용이라 실사용 경로 위험은 없음(C-ISSUE-101에 통합 기록) |
| TC-TRACK-19 | PASS | [정적] :93-95; test `rleToMaskRejectsNonPositiveWidth`·`rleToMaskRejectsNonPositiveHeight` | |
| TC-TRACK-20 | PASS | [정적] :88-92; test `rleToMaskRejectsExcessivePixels`(1001x1001) | |
| TC-TRACK-21 | PASS | [정적] :102-104; test `rleToMaskRejectsRunLengthOverflow`("초과 15") + 경계값 `rleToMaskAcceptsExactRunLength`(합계==total 정상) | |
| TC-TRACK-22 | PASS | [정적] :85-87; test `rleToMaskRejectsNullRle` | |
| TC-TRACK-23 | PASS | [정적/실동작급] test `rleRoundtripIntegrity1000` — Random(seed=42) 1000회 랜덤 mask 왕복 무손실 검증(픽셀별 assert) | 가장 강한 근거 — 실질적 fuzz 테스트 |
| TC-TRACK-24 | PASS | [정적] LabelController.java:198-203(path/body srcSn 불일치 400); test `YoloTrackControllerTest.pathBodySrcSnMismatch` | |
| TC-TRACK-25 | PASS | [정적] YoloTrackRequest.java:23 `@Size(max=50)`; test `tooManyNextSrcSns`(51개) | |
| TC-TRACK-26 | PASS | [정적] :23 `@NotEmpty`; test `emptyNextSrcSns` | |
| TC-TRACK-27 | PASS | [실동작급] YoloTrackService.java:158-182 + DetectionBoxNormalizer.java:50-69; test `negativePointsClampedNotRejected`(-1.57→0.0 clamp)·`pointsClampedToMeasuredFrameBounds`(실측 100x50 상한 clamp, 실제 PNG 파일 기반) | 400 아님을 확인(구 정책 폐기 실증) |
| TC-TRACK-28 | PASS | [실동작급] :168-172 + DetectionBoxNormalizer.java:54-57 `Double.isFinite`; test `nanPointsRejected` | NaN<0=false 회귀 방지 실증 |
| TC-TRACK-29 | PASS | [실동작급] :173-177; test `degenerateBoxSkippedWithoutDiscardingSequence`(퇴화 1건 스킵, 3프레임 전체 유지, 정상검출 "car" 보존) | |
| TC-TRACK-30 | PASS | [실동작급] :109-114(교차영상 rawSn 비교); test `nextFrameFromDifferentVideoRejected`(실 DB 영상 2건 시딩) | |
| TC-TRACK-31 | PASS | [정적] 프로덕션 참조 grep: `batch.interpolation.TrackInterpolator`→`TrackInterpolationStep.java`만 참조. `common.util.TrackInterpolator`는 main/test 전체에서 **자기 자신 외 참조 0건**(테스트 파일도 없음 — common/util 쪽은 전용 테스트조차 없음, batch 쪽만 테스트 존재) | 카탈로그 기술과 일치 — C-ISSUE-82 미해소 확인(dead code 잔존) |

## C-6. TC-PRESET (18건)

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-PRESET-01 | PASS | [정적] PresetService.java:62-73,164-172; test `PresetControllerTest.PresetController_POST_저장후_DB에는_labelId만_저장되고_LBL_CD는_null`(line 172) | |
| TC-PRESET-02 | PASS | [정적] :127-134; test `PresetController_저장시_구_EVT코드면_400`·`PresetServiceTest.validateEventTypeAgainstCategoryKeys` | |
| TC-PRESET-03 | PASS | [정적] :128-130 blank→return(허용); test 동일 메서드 내 `service.create("빈값","",List.of(10L),"")` → eventTypeCd null 정상 저장 | |
| TC-PRESET-04 | PASS | [정적] :65-67; test `duplicateNameConflict` | |
| TC-PRESET-05 | PASS | [정적] :144-160; test `unknownLabelIdRejected`·`softDeletedLabelIdRejected`·`negativeLabelIdRejected` | |
| TC-PRESET-06 | PARTIAL | [정적] :174-181 saveWithEventUniqueGuard가 DataIntegrityViolationException→CONFLICT 변환; test `createWithDuplicateEventThrowsConflict`는 **Mockito로 DIVE를 직접 throw하는 단위 목킹**이며 진짜 동시 INSERT 레이스를 재현하는 통합/동시성 테스트가 아님(카탈로그 계층=integration, 실제는 unit-mock) | DB 제약(`V15__alter_ls_label_preset_event.sql` UK_LS_LABEL_PRESET_EVNT UNIQUE(EVNT_TYPE_CD)) 자체는 존재해 race-safe하나, "동시 동일 이벤트" 입력 조건을 실제로 구동한 근거는 없음 — TC-PRESET-17(LsLabelPresetCodeLabelIdUniqueIT)과 달리 이 케이스엔 DB레벨 IT가 없음 |
| TC-PRESET-07 | PASS | [정적] :79-80; test `updateMissingPresetThrowsNotFound` | |
| TC-PRESET-08 | PASS | [정적] :81-83; test `PresetController_PUT_...` 자기제외 중복 검증 로직(`existsByPresetNmAndPresetIdNot`) | |
| TC-PRESET-09 | PASS | [정적] :88-93; test `updateWithDuplicateEventThrowsConflict` — 단, 이 역시 Mockito DIVE 직접 throw(TC-PRESET-06과 동일 성격) | |
| TC-PRESET-10 | PASS | [실동작급] :98-103; test `PresetControllerTest.PresetController_DELETE_존재_204_그리고_미존재도_204_idempotent` — 실제 MockMvc 2연속 DELETE로 멱등 실증 | |
| TC-PRESET-11 | PASS | [실동작급] :183-195; test `PresetController_POST_clone_정상_복사본이_생성되고_이름중복시_복사본2로_명명된다` — "(복사본)"→"(복사본 2)" 실제 시퀀스 확인 | |
| TC-PRESET-12 | PASS | [정적] :106-118 eventTypeCd=null로 createWithOptions; test `cloneDoesNotInheritEventMapping` | |
| TC-PRESET-13 | PASS | [정적] :183-195 CLONE_SUFFIX_MAX=50, i=2..50 루프 소진 시 CONFLICT throw — 로직 확인, **전용 단위/통합 테스트 없음**(50회 소진 실패 케이스 자체를 재현하는 테스트 미발견, grep으로 "복사본 50"/"CloneNameExceed" 등 0건) | 테스트 커버 갭(신규 발견) — 로직은 정적으로 타당하나 회귀 가드 부재 |
| TC-PRESET-14 | PASS | [정적] :239-244; test `unlinkedCodeShowsLegacyName`(labelId null) | |
| TC-PRESET-15 | PASS | [정적] :239-244(master==null 분기 공용); test `listWithLabelIdButDeletedMasterShowsUnlinked`(labelId=77, soft delete) | |
| TC-PRESET-16 | PASS | [정적] :246-248 + `LabelGeometry` enum(BBOX→bbox=T/polygon=F, POLYGON→반대, POINT/SKELETON→둘다 F); test `bboxMasterEnablesBboxOnly`·`polygonMasterEnablesPolygonOnly`·`pointAndSkeletonDisableBothToggles` | |
| TC-PRESET-17 | PASS | [실동작급] `LsLabelPresetCodeLabelIdUniqueIT.duplicateLabelIdRejectedByPartialUniqueIndex` — dedup 우회(raw JDBC INSERT)로 DB 부분유니크인덱스(`V119__preset_code_labelid_unique.sql` UK_LS_LABEL_PRESET_CODE_LBLID)가 실제 DataIntegrityViolationException을 던짐을 실증 | 49건 중 가장 강한 동시성 증거 |
| TC-PRESET-18 | PASS | [정적] :220-235 toViews가 labelId 전체 수집 후 `findActiveByIds` 1회 호출; test `listDoesNotTriggerNPlusOne`(Mockito verify times(1)) | |

## 신규 이슈

### [C-ISSUE-101] TC-TRACK-12~23 — CoordinateTransformer·MaskRleConverter 프로덕션 미사용(dead code)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md "CVAT 포팅 전략" Phase 6에 명시된 "MASK ↔ RLE ↔ Polygon 변환"(portable-modules/02)과 "좌표 변환/회전 유틸"(portable-modules/06)은 실제 라벨링 파이프라인(SAM2 분할 결과 저장, 캔버스 회전 등)에서 소비되어야 포팅 목적이 달성된다.
- **현재 동작(이슈 내용)**: `grep -rln "MaskRleConverter" backend/src/main` → 결과가 `MaskRleConverter.java` 자기 자신 1건뿐(호출부 0건). `grep -rln "CoordinateTransformer" backend/src/main` → 마찬가지로 자기 자신 1건뿐. 두 클래스 모두 `backend/src/test/.../common/util/{MaskRleConverterTest,CoordinateTransformerTest}.java` 에서만 참조된다. 실제 SAM2 분할 결과는 `Sam2SegmentService.java:119-152`에서 **polygon을 직접** 응답에 담아 반환하고 RLE 인코딩 경로를 거치지 않는다(`aiRes.polygon()` → `PolygonSimplifier.simplify` → 그대로 반환). 회전(rotate)은 프론트엔드 `frontend/src/features/label/canvas/utils/coordinateTransformer.ts`에 별도로 구현되어 있고, 백엔드는 라벨 엔티티에 회전각(angle/rotation) 필드 자체가 없어(grep 결과 0건) 서버측 회전 재계산이 필요한 지점이 없다.
- **재현/확인 경로**:
  ```
  cd backend/src/main && grep -rln "MaskRleConverter" .   # → 자기 파일만
  cd backend/src/main && grep -rln "CoordinateTransformer" .  # → 자기 파일만
  ```
- **영향**: 기능 결함은 아님(아무도 호출하지 않으므로 런타임 오류 없음). 다만 (1) 문서(CLAUDE.md)가 약속한 포팅 산출물이 실제로는 고아 코드로 남아 있어 유지보수 시 혼동 유발, (2) `MaskRleConverter`에는 미사용 코드 특유의 미검증 극단 입력 처리 결함이 존재 — `maskToRle`에서 `mask`가 `height>0, width==0`(예: `new boolean[3][0]`)인 경우 `mask.length==0` 조기 반환 가드를 통과한 뒤 `total=0`으로 `flat=new boolean[0]`가 되고, 이어지는 `flat[0]` 접근(:53,57)에서 **미제어 `ArrayIndexOutOfBoundsException`**이 발생한다(`IllegalArgumentException`이 아님 — 이 클래스의 나머지 가드는 전부 IAE로 통일돼 있는 것과 대비). 현재는 호출부가 없어 악용 경로가 없으나, 향후 이 클래스를 실제로 SAM2 마스크 저장에 연결하면 그대로 결함이 된다.
- **수정 방향(제안)**: ① 실제로 RLE 저장/회전 기능이 필요 없다면 두 클래스와 테스트를 정리(제거) 또는 카탈로그·CLAUDE.md 포팅 전략 표에서 "완료" 대신 "구현만 되고 미연동" 상태로 명시. ② 향후 연동 계획이 있다면 `MaskRleConverter.maskToRle`의 `width==0` 극단 케이스에 `IllegalArgumentException` 가드 추가(다른 가드와 일관되게).

## 카탈로그 근거 드리프트

- **TC-TRACK-13 비고 정정**: "※전용 단위테스트 여전히 부재(C-ISSUE-81)"는 `CoordinateTransformerTest.java`의 `rotateRejectsNullPoints`/`rotateRejectsNullCenter`/`rotateChecksPointsGuardBeforeCenterGuard` 3건과 불일치. git log 확인 결과 이 테스트 메서드들은 프로젝트 초기 커밋(2fd78c48, `feat(infra)`)부터 이미 존재했으며 이후 수정 이력이 없다. C-ISSUE-81("MaskRle/Coordinate 가드 테스트 0%")은 최소 CoordinateTransformer에 대해서는 사실이 아니며, MaskRleConverterTest 역시 null/DoS/경계값 가드 테스트를 풍부하게 보유(9개 가드 테스트 확인). C-ISSUE-81 클레임 자체의 재검증(또는 폐기) 권고.
