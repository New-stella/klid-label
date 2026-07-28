## C-1. TC-MARK — 마킹 (자동/수동)

> ★사전 확인: `docs/test-cases/C-marking-labeling.md` 5~44행(TC-MARK-01~35)의 근거 파일(`MarkingService.java`,
> `MarkingGuards.java`, `LsMarking.java`, `MarkingBatchBridge.java`, `MarkingController.java`,
> `MarkingPrecheckReader.java`, `MarkingRequest.java`)은 `_raw/B-part2.md`(B-5/B-6, TC-BATCH-050~089)가 검증한
> **동일 코드 경로**다. 본 클러스터는 ①B-part2 라이브 결과를 인용하고 ②B-part2가 다루지 않은 케이스(MANUAL 정상,
> fps 폴백, LsMarking 검증 메서드, 프리체크 REQUIRES_NEW)를 신규 실동작/코드 확인으로 보강했다.
> 백엔드는 재빌드 완료 후 `/api/actuator/health/liveness`·`/readiness` 모두 UP 상태에서 검증 수행.

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-MARK-01 | AUTO 마킹 정상 생성 | PASS | [실동작] rawSn=24/26 AUTO 201, marks 배열 검증(B-part2 TC-BATCH-080). [정적] `MarkingService.java:98-191` 라인 일치 | MarkingServiceTest#createAutoMode, #자동마킹_끝경계프레임_미포함_off_by_one_검증 | B-part2 인용 |
| TC-MARK-02 | MANUAL 마킹 정상 생성 | PASS | [실동작] rawSn=27 REVIEWER `{"mode":"MANUAL","marks":[{0,"00:00"},{150,"00:05"}]}` → 201, 응답 marks 직렬화 그대로, DB `ls_marking.fps=30` pin 확인(`MarkingService.java:169-183` 라인 일치) | MarkingServiceTest#createManualMode, #수동마킹도_해석한fps가_마킹레코드에_pin된다 | 본 세션 신규 실측(B-part2 미실측) |
| TC-MARK-03 | mode 누락(blank) | PASS | [정적] `MarkingRequest.java:18` `@NotBlank` 라인 일치 | MarkingControllerTest(blank mode 400 계열) | |
| TC-MARK-04 | mode 비AUTO/MANUAL | PASS | [실동작] rawSn25 `"X"`/`"auto"`/`" AUTO "` 전부 400(B-part2 TC-BATCH-084). [정적] `MarkingService.java:174-176` 일치 | MarkingServiceTest(모드 오류 계열) | B-part2 인용 |
| TC-MARK-05 | AUTO intervalFrames null | PASS | [실동작] rawSn15 null→400(B-part2 TC-BATCH-078). [정적] `:162-164` 일치 | MarkingServiceTest#자동모드_intervalFrames_0이하_INVALID_INPUT | B-part2 인용 |
| TC-MARK-06 | AUTO intervalFrames 0/음수 | PASS | [실동작] rawSn15 0→400, -5→400(B-part2 TC-BATCH-078) | 동일 | B-part2 인용 |
| TC-MARK-07 | MANUAL marks 빈/null | PASS | [실동작] rawSn25 `marks:[]`→400, 생략→400(B-part2 TC-BATCH-083). [정적] `:170-172` 일치 | (서비스 단위테스트 미확인, B-part2 기록과 동일) | B-part2 인용 |
| TC-MARK-08 | AUTO durationSec null(backstop) | PASS | [정적] `MarkingService.java:219-223` 일치 | MarkingServiceTest#자동마킹_주입_durationSec_null…backstop_INVALID_INPUT | live는 VDO_LEN_SEC 상시 존재해 도달 안 함(B-part2 동일 관찰) |
| TC-MARK-09 | AUTO durationSec 0/음수 | PASS | [정적] `:219-223` 동일 분기 | MarkingServiceTest#자동마킹_주입_durationSec_0이하면_INVALID_INPUT | |
| TC-MARK-10 | generateAutoMarks off-by-one | PASS | [실동작] rawSn24(dur=30s,fps=30,interval=30) `json_array_length=30`, 마지막 `frameIndex=870`, totalFrames=900 미포함(B-part2 TC-BATCH-080). [정적] `:226-231` 일치 | MarkingServiceTest#자동마킹_끝경계프레임_미포함_off_by_one_검증 | B-part2 인용 |
| TC-MARK-11 | 분수 fps 반올림(29.97) | PARTIAL | [정적] `Math.round(durationSec×fps)` 확인(`:226`). generateAutoMarks 를 29.97로 직접 검증하는 테스트는 없음(fps 25/30/60만) — B-part2 B-ISSUE-27 항목과 동일 갭 | VideoFpsResolverTest#분수fps_29.97_정확파싱(파싱만, 계산 자체는 커버 안 됨) | B-part2 [B-ISSUE-27] 인용, 본 클러스터에서 별도 이슈 미신설(중복) |
| TC-MARK-12 | fps 미상 → 30 폴백 | PASS | [정적] `MarkingService.java:157` `resolveFps` 호출 확인, javadoc(`:199` 부근)에 폴백 30.0 서술 | MarkingServiceTest#M3_자동마킹_fps미상_30폴백…, #자동마킹_fps미상이면_30폴백으로_기존과_동일_무회귀 | 본 세션 코드 재확인. live로는 항상 fps 적재돼 있어(30) 폴백 경로 자체는 unit 커버리지에 의존 |
| TC-MARK-13 | fps pin 저장(TOCTOU) | PASS | [실동작] rawSn24~30 `ls_marking.fps` 전부 30 저장(B-part2 TC-BATCH-082) + 본 세션 rawSn27(fps=30), rawSn28 재확인. [정적] `LsMarking.java:83-84` 일치 | MarkingServiceTest#자동마킹_해석한실fps25가_마킹레코드에_pin…, #수동마킹도…pin된다 | |
| TC-MARK-14 | 인가: 미인증 actor=null | PASS | [실동작] 무토큰 POST → 401(B-part2 TC-BATCH-070). [정적] `MarkingGuards.java:50-52` 일치 | MarkingControllerTest#미인증_요청_401 | B-part2 인용 |
| TC-MARK-15 | 인가: 미배정 WORKER(IDOR) | PASS | [실동작] WORKER(2001)→rawSn15 403, 미존재 99999도 403(NOT_FOUND 아님, 존재 은닉 확인)(B-part2 TC-BATCH-072). [정적] `:53-61` 일치 | MarkingControllerTest#I4_타인배정_영상…403 | B-part2 인용 |
| TC-MARK-16 | 인가: REVIEWER 전체 허용 | PASS | [실동작] REVIEWER(1001)가 미배정 rawSn15/16/25/27/28 전부 접근 성공(B-part2 TC-BATCH-071 + 본 세션 rawSn27/28 MANUAL 생성). [정적] `:53-55` 일치 | MarkingServiceTest#비식별_완료_영상_마킹생성_정상_가드_통과 | |
| TC-MARK-17 | 프리컨디션: 영상 미존재 | PASS | [실동작] REVIEWER+99999 → 404(B-part2 TC-BATCH-074). [정적] `:77-79` 일치 | MarkingControllerTest#POST_미존재_영상_마킹_404 | B-part2 인용 |
| TC-MARK-18 | 프리컨디션: 비식별 미완료 | PASS | [실동작] rawSn9(de_ident_yn=F) → 412(B-part2 TC-BATCH-075). [정적] `:80-83` 일치. 본 세션 grep 결과 `LsMarking.createAuto/createManual` 직접 호출처는 `MarkingService` 단 1곳뿐 — 가드 우회 경로 없음 재확인 | MarkingServiceTest#비식별_미완료_영상…PRECONDITION_FAILED | 가드 우회 반증 시도 결과: 없음 |
| TC-MARK-19 | 프리컨디션: MARKING_READY 아님(재마킹 차단) | PASS | [실동작] rawSn19/26(COMPLETED) → 412(B-part2 TC-BATCH-076). 본 세션 rawSn27도 배치 완주 후 COMPLETED 전이 확인 | MarkingServiceTest#이미_COMPLETED…, #이미_PROCESSING… | |
| TC-MARK-20 | 프리컨디션: evntTypeCd 미지정 | PASS | [정적] `:88-92` 일치(live는 전 영상이 evnt_type_cd 보유해 재현 불가, B-part2 동일 관찰) | MarkingServiceTest#이벤트유형_null…, #이벤트유형_blank… | |
| TC-MARK-21 | 프로브-이전 사전확인(리소스) | PASS | [정적] `MarkingService.java:99-108` precheck→AUTO만 duration 해석. [실동작 간접] 미배정 WORKER AUTO 즉시 403(수 ms, B-part2 TC-BATCH-073) | MarkingServiceOrchestrationTest#미배정_WORKER_AUTO요청_프로브_미트리거되고_FORBIDDEN | ffprobe 호출여부는 HTTP로 직접 관측 불가 |
| TC-MARK-22 | 프리체크 REQUIRES_NEW 격리 | PASS | [정적] `MarkingPrecheckReader.java:42` `@Transactional(value="controlTransactionManager", readOnly=true, propagation=Propagation.REQUIRES_NEW)` 라인 일치 확인 | (전용 격리 테스트 미확인 — 오케스트레이션 테스트로 간접 커버) | 본 세션 코드 재확인, 라인 드리프트 없음 |
| TC-MARK-23 | persist 이중화 방어 | PASS | [정적] `MarkingService.java:139-144` `requireAssignedOrReviewer`(140)+`requirePreconditions`(144) 재호출 확인 — 사전확인과 동일 `MarkingGuards` 헬퍼 재사용 | MarkingServiceOrchestrationTest, VlmMarkingTransitionPersistenceIntegrationTest(유사 패턴, B-part2 TC-BATCH-087) | |
| TC-MARK-24 | 이벤트명 자동소싱(API-047) | PASS | [실동작] rawSn24 `evnt_type_cd='INTRUSION'`→응답 `eventName:"INTRUSION"`(B-part2 TC-BATCH-086), 본 세션 rawSn27/28 `EV02000201`→응답 동일 확인. [정적] `:148` 일치 | (응답 단언 간접) | |
| TC-MARK-25 | LsMarking.createAuto 검증 | PASS | [정적] `LsMarking.java:124-135` rawSn null/eventName blank/intervalFrames≤0 → IllegalArgumentException 라인 일치 | LsMarkingEntityTest#createAutoInvalidIntervalSec, #createAutoNullRawSn | 본 세션 unit 커버리지 재확인(단위 레벨이라 HTTP 도달 불가 — Guards가 상위에서 이미 차단) |
| TC-MARK-26 | LsMarking.createManual 검증 | PASS | [정적] `LsMarking.java:178-186` rawSn null/eventName blank → IllegalArgumentException 일치 | LsMarkingEntityTest#createManualNullEventName, #createManualBlankVideoPath | 동일 |
| TC-MARK-27 | markVlmRequested PENDING만 전이 | PASS | [정적] `LsMarking.java:213-220` 일치. live로는 `VLM_CLIENT_ENABLED=false`라 전이 자체가 발생하지 않음(B-part2 TC-VLM-011 — `ls_marking` 전 행 PENDING 확증, self-fill 아님) | LsMarkingEntityTest#markVlmRequestedTransition, #markVlmRequestedGuard | |
| TC-MARK-28 | markVlmRequested 이미 완료 no-op | PASS | [정적] `:214-216` false 반환 로직 일치 | LsMarkingEntityTest#markVlmRequestedGuard(no-op 분기 포함) | |
| TC-MARK-29 | 배치브릿지: PROCESSING/COMPLETED 스킵 | PASS | [실동작 간접] rawSn19/26(COMPLETED) 마킹요청이 412로 상위가드 선차단(B-part2 TC-BATCH-051). [정적] `MarkingBatchBridge.java:72-76` 일치 | MarkingBatchBridgeTest#onMarkingCompleted_rawDataCompleted_skips 등 | |
| TC-MARK-30 | 배치브릿지: 비식별 미완료 스킵 | PASS | [실동작 간접] rawSn9(de_ident_yn=F)→412(B-part2 TC-BATCH-052). [정적] `:80-84` 일치 | MarkingBatchBridgeTest#_notDeidentified_skips | |
| TC-MARK-31 | 배치브릿지: row 부재 시 생성 | PASS | [실동작] rawSn15/16 row 부재 상태에서 REVIEWER 직접 마킹 → row 생성+enqueued+배치기동(B-part2 TC-BATCH-054, FIX B 실증). [정적] `:96-105` 일치 | MarkingBatchBridgeTest#_unassignedRowAbsent_tx2Creates_triggers | B-part2 인용 |
| TC-MARK-32 | 배치브릿지: 동시 2노드 유니크 경합 | PASS | [실동작] rawSn16 3동시 POST → 201×3, `enqueued` 1회만, `concurrent row creation…skipping` 2회, 배치 스레드 1개(B-part2 TC-BATCH-055). [정적] `:98-108` 일치 | BatchTransitionServiceRowCreationIT#concurrentRowCreation_exactlyOneClaims… | B-part2 인용(★핵심 동시성 반증 통과) |
| TC-MARK-33 | 배치브릿지: 영상 미존재 스킵 | PASS | [정적] `:62-66` findById empty→warn+return 일치. 실동작 재현 불가(마킹 생성 자체가 영상 존재를 전제) — 논리적 도달 불가 방어층(B-part2 TC-BATCH-050 동일 관찰) | MarkingBatchBridgeTest#onMarkingCompleted_noRawVideo_skips | |
| TC-MARK-34 | 로그 인젝션 방어 | PASS | [정적] `:120-122` `sanitize()` CR/LF 제거 일치. 적용처 :74/:82 확인 | **없음**(회귀 테스트 부재 — B-part2 [B-ISSUE-26] 기록) | 신규 이슈 미중복 신설(B-part2 인용) |
| TC-MARK-35 | 컨트롤러 권한 매핑 | PASS | [실동작] PORTAL_USER(3001) → 403 `{"message":"권한이 없습니다."}`(B-part2 TC-BATCH-089). [정적] `MarkingController.java:51` `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 일치 | MarkingControllerTest#I4_본인배정_영상_마킹생성_WORKER_201 | B-part2 인용(채널격리 CHANNEL_PORTAL 동시 확인) |

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-01] TC-MARK-02(연장) — MANUAL marks 항목에 콘텐츠 검증 부재(중복 시점·영상길이 초과·음수 frameIndex 전부 통과)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: MANUAL 모드의 각 mark는 최소한 `frameIndex ≥ 0`, 중복 시점 배제, (가능하면) 영상 실제 프레임 수 이내로 검증돼야 한다. 그래야 이후 프레임 추출·라벨링 단계가 존재하지 않는 프레임을 참조하는 사고를 막는다.
- **현재 동작(이슈 내용)**: `MarkItem.java`(`backend/src/main/java/kr/co/cudo/authoring/marking/dto/MarkItem.java`)는 `frameIndex`에 `@NotNull`만 걸려 있고 하한(`@Min`)·상한·timestamp 형식 검증이 전혀 없다. `MarkingService.create`(MANUAL 분기, `:169-173`)도 `marks.isEmpty()`만 검사하고 개별 항목 유효성·중복은 전혀 보지 않는다.
  - 실측: rawSn=28(MARKING_READY, de_ident_yn=Y)에 REVIEWER가 `{"mode":"MANUAL","marks":[{"frameIndex":0,"timestamp":"00:00"},{"frameIndex":0,"timestamp":"00:00"},{"frameIndex":999999999,"timestamp":"99:99"},{"frameIndex":-5,"timestamp":"-1:00"}]}` 요청 → **201** 그대로 성공, `ls_marking.mark_cn`(marking_sn=32)에 4개 항목(중복 0, 초과값 999999999, 음수 -5, 형식파괴 timestamp "99:99"/"-1:00") 그대로 영속.
- **재현/확인 경로**:
  ```
  curl -s -X POST http://localhost:18081/api/v1/videos/28/markings \
    -H "Authorization: Bearer $REVIEWER_TOKEN" -H "Content-Type: application/json" \
    -d '{"mode":"MANUAL","marks":[{"frameIndex":0,"timestamp":"00:00"},{"frameIndex":0,"timestamp":"00:00"},{"frameIndex":999999999,"timestamp":"99:99"},{"frameIndex":-5,"timestamp":"-1:00"}]}'
  # → 201, marking_sn=32 (실측 완료, rawSn=28)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select mark_cn from ls_marking where marking_sn=32;"
  ```
- **영향**: 음수/과대 frameIndex 마크가 저장되면 후속 프레임 추출(FfmpegFrameExtractor)이나 라벨링 화면이 존재하지 않는 프레임을 참조해 예외를 던지거나(방어적이면) 조용히 스킵할 가능성. AUTO 모드는 이미 상한 미검증(B-part2 B-ISSUE-23, LOW)이 보고돼 있으나, MANUAL은 하한·중복까지 전무해 더 넓은 표면이다. TC-MARK-02/07 케이스 표에는 이 경계값이 명시돼 있지 않아 사각지대였다.
- **수정 방향(제안)**: `MarkItem`에 `@Min(0)` 추가, 서비스단에서 `frameIndex` 중복 제거/거부 및 (durationSec·fps로 계산한) 상한 초과 시 400 또는 클램프. (구현 금지 — 제안만)

## 요약
- 총 **35건** (TC-MARK-01~35)
- PASS **34** / FAIL **0** / PARTIAL **1**(TC-MARK-11, B-part2 [B-ISSUE-27]과 동일 갭 — 신규 이슈 아님) / BLOCKED **0** / N/A **0** / 확인필요 **0**
- 근거 라인 드리프트: **0건** — `MarkingService.java`/`MarkingGuards.java`/`LsMarking.java`/`MarkingBatchBridge.java`/`MarkingController.java`/`MarkingPrecheckReader.java`/`MarkingRequest.java` 전 근거 라인을 재확인했고 케이스 표기 라인과 실제 코드 라인이 전부 일치(반증 시도 결과 드리프트 미발견)
- self-fill 결함: **0건** — 본 클러스터는 마킹 생성·전이 로직이며 외부 응답을 채우는 지점이 없음(VLM 전이는 B-part2가 이미 self-fill 없음 확증)
- 신규 이슈: **1건**(C-ISSUE-01, MEDIUM — MANUAL marks 콘텐츠 검증 부재, 본 세션 신규 실측 발견)
- **B-part2 와 중복된 케이스**: TC-MARK-01,03~10,14~21,24,27~35 (총 27건)이 B-part2의 TC-BATCH-050~089/TC-VLM-011,012와 **동일 코드 경로·동일 실동작 근거**를 공유함. 판정은 전부 **일치(PASS)** — 반증 시도(라인 재대조, 가드 우회 경로 grep, MANUAL 신규 실측)에도 기존 B-part2 판정이 무너지지 않았고, 오히려 B-part2가 다루지 않은 MANUAL marks 콘텐츠 검증 공백(C-ISSUE-01)을 신규로 확인함.
