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
