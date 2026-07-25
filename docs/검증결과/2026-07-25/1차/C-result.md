# C. 마킹 / 라벨링 — 1차 검증 결과

> 검증일 2026-07-25 · 기준: **실동작**(풀스택+목업서버, backend V130 재빌드 후, rawSn=26 완주 시나리오 위에서 판정)

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

# C-2 (전반). TC-LABEL-01 ~ 51 — 라벨 CRUD / full-replace / 마스터

> 대상: `docs/test-cases/C-marking-labeling.md` 45~97행 (TC-LABEL-01~37, 40~51 — 38·39 결번, 총 **49건**)
> 검증일: 2026-07-25 · 회차 1차 · 검증자: C-part2 에이전트

## 검증 환경 · 실동작 근거 확보 경로

- 스택: `klid-backend`(재빌드 후 healthy, `local`) / `klid-postgres` / `klid-ai-server` / `klid-mock-server:9400` / `klid-frontend` 전부 기동.
- 토큰: `POST /v1/dev/tokens` — REVIEWER(sub=1001) / WORKER(sub=2001) / PORTAL_USER.
- **쓰기 시험 대상(기존 검증 데이터 비파괴 선정)**:
  - `srcSn=1`(rawSn=4, WORKER 2001 배정, 작업락 RELEASED, **라벨 0건**) — CRUD·full-replace·검증 본진
  - `srcSn=350`(rawSn=12, **작업락 LOCKED**) — TC-12 전용(409 로 무변경)
  - `srcSn=359`(rawSn=13, **APPROVED**, 라벨 0건) — TC-31 전용
  - `srcSn=432`(rawSn=18, WORKER **미배정**) — IDOR 전용(읽기만)
  - `rawSn=26`(참조 완주 데이터)·`rawSn=11/13/14/17/19` 의 **라벨은 일절 건드리지 않음**
- **DB 변경 내역(복구 상태)**: ①`srcSn=1`·`srcSn=359` 라벨 생성/삭제 반복 → **최종 0건으로 원상 복구**(시험 전과 동일). ②`ls_data_lbl_hstry` 에 시험 저장이벤트 행이 남음(감사 이력이라 삭제하지 않음). ③`ls_label` 에 `TCVERIFY-A`(497), `TCVERIFY-RACE`(498), `TCVERIFY-A`(503) 3건 생성 → **전부 soft delete(`USE_YN='N'`) 처리**하여 활성 라벨 목록 미오염.

## 판정표

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-LABEL-01 | 프레임 라벨 조회 정상 | PASS | [실동작] `GET /v1/frames/350/labels`(WORKER) 200 — `siblings` 8건 + `hasLabel` + `frameImageType` + `lockSttsCd` 반환. TC-07 응답에 `labelName`/`color`(LS_LABEL enrich) + `confScore`/`lblSrcCd`(AI_INFO enrich) 확인. [정적] LabelService.java:182-200 | LabelServiceSiblingHasLabelTest(5) | enrich 2종 모두 실측 |
| TC-LABEL-02 | 조회 IDOR: 타 영상 프레임 | PASS | [실동작] WORKER→srcSn=432(rawSn=18 미배정) **403 FORBIDDEN** "본인에게 배정되지 않은 영상입니다." [정적] LabelAccessGuard.java:48-54 | FrameImageControllerTest 등 다수 | |
| TC-LABEL-03 | 조회: 미인증 | PASS | [실동작] 토큰 없이 GET → **401 UNAUTHORIZED**. [정적] LabelAccessGuard.java:40-42 | — | |
| TC-LABEL-04 | 조회: 프레임 미존재 | PASS | [실동작] srcSn=999999(REVIEWER) → **404** "프레임을 찾을 수 없습니다." [정적] LabelAccessGuard.java:43-44 | — | |
| TC-LABEL-05 | frameImageType RAW(REVIEWER+raw) | PASS | [실동작] `?raw=true`+REVIEWER → `"frameImageType":"RAW"`. [정적] LabelService.java:216-221 | — | |
| TC-LABEL-06 | frameImageType DEID(WORKER raw무시) | PASS | [실동작] `?raw=true`+WORKER → `"frameImageType":"DEID"` (강제). [정적] :216-221 | — | 원본 노출 차단 실측 |
| TC-LABEL-07 | bulkUpsert 신규 INSERT(수동) | PASS | [실동작] 3건 신규 저장 → 200, `autoLblYn:"N"`, `ls_data_lbl_hstry` **ADD_CNT=3**. [정적] :308-324 | FullReplaceIT `firstSaveAllAdded` | |
| TC-LABEL-08 | bulkUpsert 기존 UPDATE | PASS | [실동작] id 지정 좌표 변경 → 이력 `MDFCN_CNT=1`, `chg_dtl_cn` 에 `before {[[1,1],[20,20]]}` / `after {[[5,5],[25,25]]}` 스냅샷 기록. [정적] :288-307 | FullReplaceIT `updatedRecordsBeforeAndAfter` | |
| TC-LABEL-09 | full-replace: 빠진 라벨 삭제 | PASS | [실동작] 기존 3건 중 2건만 전송 → DB 잔존 2건, 이력 `DEL_CNT=1` + `kind:"DELETED"` before 스냅샷(`bus`) 보존. [정적] :328-352 | FullReplaceIT `omittedLabelIsPhysicallyDeleted` | 삭제 전 before 스냅샷 확인 |
| TC-LABEL-10 | full-replace: 빈 items=전량 삭제 | PASS | [실동작] `{"items":[]}` → 200, `select count(*) from ls_data_lbl where src_sn=1` **=0**. [정적] :334-352 | FullReplaceIT `allExistingDeleted` | |
| TC-LABEL-11 | 삭제 순서 FK고아 방지 | PASS | [실동작] AI_INFO 보유 라벨(lbl_sn=381) 삭제 → FK 위반 없이 200, `ls_data_lbl_ai_info` 잔존 **0건**. [정적] :346-351 (ATTR_VAL→AI_INFO→LBL) | FullReplaceIT `deletedLabelChildrenRemovedWithoutFkViolation`, `multiDeleteNoOrphans` | |
| TC-LABEL-12 | 작업락 시 저장 차단 | PASS | [실동작] srcSn=350(rawSn=12 `ls_auth_work_lock` LOCKED) PUT → **409 CONFLICT** "비식별 재처리 중인 영상은…". [정적] :242-245 | — | 락 실데이터 존재 확인 |
| TC-LABEL-13 | dedupById 동일 id 중복 | PASS | [실동작] 동일 id 2회(`[7,7]` 후 `[9,9]`) → DB `[[9.0,9.0],[29.0,29.0]]` last-value-wins. [정적] :424-442 | FullReplaceIT `duplicateIdDeduped` | |
| TC-LABEL-14 | 좌표 검증: 빈 points | PASS | [실동작] `points:[]` → **400** `items[0].points: must not be empty`. **주의**: DTO `@NotEmpty`(LabelItemDto:42) 가 먼저 잡아 서비스 :576-578 메시지("points 가 비어있습니다")는 도달 불가 | — | 결과 동일(400) |
| TC-LABEL-15 | 좌표 검증: [x,y] 형식 위반 | PASS | [실동작] `[[1,2,3],[4,5,6]]` → **400** "좌표는 [x, y] 형태여야 합니다." [정적] :588-591 | — | |
| TC-LABEL-16 | 좌표 검증: 음수 | PASS | [실동작] `[[-1,5],…]` → **400** "좌표는 0 이상이어야 합니다 (x=-1.0, y=5.0)". [정적] :592-597 | YoloTrackServiceTest·AutolabelOnlineServiceTest 음수 | |
| TC-LABEL-17 | 좌표 검증: 0 허용(경계) | PASS | [실동작] `[[0,0],[10,10]]` → 200 저장, DB `[[0.0,0.0],[10.0,10.0]]`. [정적] :592-597 | LabelServiceKeypointTest `v0_미표기_점_x_y_0_허용` | |
| TC-LABEL-18 | 신규 라벨 >1000점 차단(DoS) | PASS | [실동작] 1001점 신규 → **400** "라벨당 좌표 개수 초과 (최대 1000 점)"; **경계 1000점은 200 통과**(DB 1000점 그대로). [정적] :584-587 | PolygonCapTest `신규_초과_폴리곤…400_거부` | 경계 양측 확인 |
| TC-LABEL-19 | 기존 라벨 >1000점 simplify | PASS | [실동작] id 지정 1001점 → **200**(400 아님), DB 저장 점수 **51**(≤1000). [정적] :255, 722-732 capPoints/Douglas-Peucker | PolygonCapTest(3) | |
| TC-LABEL-20 | labelId 미존재 | PASS | [실동작] labelId=99999 → **404** "라벨 마스터를 찾을 수 없습니다: labelId=99999". [정적] :553-558 | — | |
| TC-LABEL-21 | labelId USE_YN='N' 사용중지 | PASS | [실동작] labelId=7(animal, `use_yn='N'`) → **409** "사용 중지된 라벨입니다: labelId=7". [정적] :559-562 | — | 부작용은 C-ISSUE-25 참조 |
| TC-LABEL-22 | Mass Assignment: autoLblYn 무시 | PASS | [실동작] 요청에 `"autoLblYn":"Y"` 포함 저장 → 응답 `autoLblYn:"N"`. **추가 반증**: `ls_data_lbl` 에 `auto_lbl_yn` **컬럼 자체가 없음**(실제 컬럼 10개 실측) — 값이 AI_INFO row 존재 여부에서 파생되어 구조적으로 바인딩 불가. [정적] :228-234 | — | 방어가 스펙보다 강함 |
| TC-LABEL-23 | provenance AUTO_YOLO→AI_INFO | PASS | [실동작] `source:AUTO_YOLO, confScore:0.87, algorithm:YOLO` → `ls_data_lbl_ai_info` 행 `lbl_src_cd=YOLO, conf_score=0.87000` 적재, 응답 `autoLblYn:"Y"`. [정적] :310-317 | — | |
| TC-LABEL-24 | provenance 화이트리스트 위반 | PASS | [실동작] `source:"HACK"` → **400** `source 는 MANUAL/AUTO_YOLO/AUTO_SAM2 중 하나`. [정적] LabelItemDto.java:44-45 | — | |
| TC-LABEL-25 | confScore 범위 초과 | PASS | [실동작] `confScore:1.5` → **400** `confScore 는 1.0 이하`. [정적] LabelItemDto.java:46-47 | — | |
| TC-LABEL-26 | items >500 상한(DoS) | PASS | [실동작] 501건 → **400** "한 번에 처리 가능한 라벨 수 초과 (최대 500)". [정적] LabelBulkUpsertRequest.java:15 | — | |
| TC-LABEL-27 | 무변경 재저장 이력 미발행(R7) | PASS | [실동작] 동일 3건 그대로 재전송 → 200, `ls_data_lbl_hstry` count **4→4 불변**. [정적] :301-307 | FullReplaceIT `noChangeNoHistory`, `noChangeResaveNoHistoryNoNotification` | |
| TC-LABEL-28 | R7 정규화 비교(5 vs 5.0) | PASS | [실동작] `[20,20]`→`[20.0,20.0]` 표현만 변경 재전송 → 이력 **4→4 불변**(무변경 판정). [정적] :470-508 | — | |
| TC-LABEL-29 | R7 레거시 3포맷 흡수 | PASS | [정적] :491-508 `LabelPointSerializer.fromJson` 3포맷 + raw 폴백 | FullReplaceIT `flatLegacyPointsNoChangeResave`, `objectArrayLegacyPointsNoChangeResave` | 레거시 데이터 부재로 실동작 미수행 |
| TC-LABEL-30 | R7 손상 JSON fail-safe | PARTIAL | [정적] :479-483 `normalizePoints` 양쪽 null → `false`(변경됨) fail-safe 확인. **전용 테스트 부재**(FullReplaceIT `corruptDiffJsonGracefulFallback` 은 이력 diff JSON 손상이지 `pointCn` 손상이 아님) | (간접만) | C-ISSUE-23 |
| TC-LABEL-31 | TASK_MODIFIED: APPROVED만 | PARTIAL | [실동작] APPROVED 영상(rawSn=13/srcSn=359) 라벨 편집 **200 허용** 확인. 그러나 `CONTROL_NOTIFY_ENABLED=false` 로 `ControlNotifyEventListener` 빈이 미생성(`@ConditionalOnProperty`)이라 **발행 자체를 런타임 관측 불가**. [정적] :365-371 | LabelServiceTaskModifiedGuardTest `검수완료_발행`(rawSn/srcSn/changeType 검증) | C-ISSUE-24(환경 제약) |
| TC-LABEL-32 | TASK_MODIFIED: 검수전 미발행 | PASS | [정적] :365-371 `isReviewApproved` 게이트 | LabelServiceTaskModifiedGuardTest `검수전_미발행`(ASSIGNED) + `상태없음_미발행` | |
| TC-LABEL-33 | 이력 조회 IDOR | PASS | [실동작] WORKER→`GET /v1/frames/432/label-history` **403**. [정적] :404-405 | LabelHistoryControllerTest `타인_프레임_히스토리_조회시_403` | |
| TC-LABEL-34 | 이력 페이지 상한 클램프 | PASS | [실동작] `?size=500` → 응답 `size=100`. [정적] :516-520 | — | |
| TC-LABEL-35 | 이력 임의 sort 무시(500 차단) | PASS | [실동작] `?sort=badfield,desc` → **200**(500 아님), `regDt DESC` 서버 고정 정렬로 최신 이벤트 반환. [정적] :510-520 | — | |
| TC-LABEL-36 | 동시 저장 델타 TOCTOU | 확인필요 | [실동작] **lost update 재현됨** — 백엔드 로그 `existing=0 saved=1 deleted=0`(A 저장) 직후 `existing=1 saved=1 **deleted=1**`(B 저장) → A 가 만든 라벨이 오류·경고 없이 물리 삭제. 병렬 동시 PUT(같은 순간)에선 양쪽 `deleted=0` 로 우연히 살아남음. [정적] :331-361 (`@Version`/비관락 없음, 주석이 "단일 WORKER 배정" 가정 명시) | — | **C-ISSUE-21** / UNCERTAINTIES #9 |
| TC-LABEL-37 | 삭제 감사 로깅 PII 미출력 | PASS | [실동작] 로그 실측 `[Label] bulkUpsert srcSn=1 actor=2001 existing=3 saved=0 deleted=3` — 카운트·식별자만, 좌표/라벨명/토큰 미출력. [정적] :359-361 | — | |
| TC-LABEL-40 | 마스터 생성 정상 | PASS | [실동작] `POST /v1/manage/labels`(REVIEWER) → **201**, `labelId=497` 반환. [정적] LabelMasterService.java:73-95 | LabelMasterServiceTest `create_정상` 외 | |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | PASS | [실동작] 활성 `car` 존재 상태에서 `" Car "` 생성 → **409** "이미 사용 중인 라벨 이름입니다." [정적] :76-78 + LsLabelRepository `existsActiveByNormalizedName`(LOWER(TRIM)) | LabelMasterServiceTest `대소문자_근사중복`, `공백_근사중복`, `앞뒤공백_trim_저장` | |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | PASS | [실동작] 동일 이름 **5병렬 POST → 201 1건 + 409 4건**. DB 인덱스 실존 확인: `uk_ls_label_nm_ci UNIQUE (lower(btrim(lbl_nm))) WHERE use_yn='Y'`(V120). [정적] :70-78 | — | 앱 선판정+DB유니크 이중가드 실증 |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | PASS | [실동작] `dtctTypeCd:"human"` → **400** "지원하지 않는 검출 클래스입니다." [정적] :126-135 `CocoClasses.isValid` | LabelMasterServiceTest `create_미지원_COCO_400` | |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | PASS | [실동작] `person`(lbl_id=1 이 이미 보유) 재매핑 → **409** "이미 사용 중인 검출 클래스 매핑입니다." [정적] :81-83 | LabelMasterServiceTest `create_중복_COCO매핑_409`, `update_중복_COCO매핑_409` | |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | PASS | [실동작] `dtctTypeCd:""` 생성 → 201, 응답/DB `dtctTypeCd=null`. [정적] :127-129 | LabelMasterServiceTest `create_매핑없음_성공` | |
| TC-LABEL-46 | color 소문자 hex 거부 | PASS | [실동작] `#ffffff` → **400** "color 는 대문자 hex (#RRGGBB) 형식". [정적] LabelMasterRequest.java:33 | — | |
| TC-LABEL-47 | type allowlist | PASS | [실동작] `type:"FOO"` → **400** `BBOX/POLYGON/POINT/SKELETON 중 하나`. [정적] LabelMasterRequest.java:37 | LabelMasterServiceTest SKELETON/기존타입 회귀 | |
| TC-LABEL-48 | sortNo 음수 | PASS | [실동작] `sortNo:-1` → **400** "sortNo 는 0 이상". [정적] LabelMasterRequest.java:40 | — | |
| TC-LABEL-49 | 마스터 수정 미존재 | PASS | [실동작] `PUT /v1/manage/labels/999999` → **404** "라벨을 찾을 수 없습니다." [정적] :100-101 | LabelMasterServiceTest `update_없는라벨_NOT_FOUND` | |
| TC-LABEL-50 | 마스터 수정 근사중복(자기제외) | PASS | [실동작] 497→`"CAR"`(활성 `car` 충돌) **409**; 동일 건을 자기 이름 `TCVERIFY-A` 유지 수정은 **200** (자기제외 정상). [정적] :104-106 | LabelMasterServiceTest `update_이름중복_CONFLICT`, `update_자기자신_중복검사_제외` | 양방향 확인 |
| TC-LABEL-51 | 마스터 삭제 soft delete | PASS | [실동작] `DELETE /v1/manage/labels/497` → **204**, DB `use_yn='N'`(행 잔존=hard delete 아님). 추가: soft delete 후 **동일 이름 재생성 201 허용**(부분 유니크 `WHERE use_yn='Y'` 와 정합). [정적] :189-195 | LabelMasterServiceTest `delete_soft`, `create_soft_delete된_이름…` | |

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-21] TC-LABEL-36 — 라벨 full-replace 동시 저장 lost update (stale 작업본이 타인 라벨을 침묵 삭제)
- **심각도**: HIGH (데이터 소실 · 무경고)
- **기대 동작(기대효과)**: 두 사용자(또는 두 브라우저 탭)가 같은 프레임을 편집할 때, 나중 저장이 앞선 저장의 라벨을 소리 없이 지우면 안 된다. 최소한 충돌 감지(409/낙관적 락) 또는 병합 정책이 있어야 한다.
- **현재 동작**: `LabelService.bulkUpsert` 는 `existing = labelRepository.findBySrcSn(srcSn)`(:265) 를 읽고 요청의 non-null id 집합(`reqIds`)에 **없는 모든 기존 라벨을 물리 삭제**한다(:334-352). `@Version`·비관적 락·ETag 어느 것도 없고, 코드 주석(:331-333)이 방어를 "프레임은 단일 WORKER 배정이라 구조적으로 제한"이라는 **가정**에 의존한다고 명시한다.
  실측 로그(2026-07-25 11:05:54, srcSn=1):
  ```
  11:05:54.770 [Label] bulkUpsert srcSn=1 actor=2001 existing=0 saved=1 deleted=0   ← A 가 라벨 X 저장
  11:05:54.796 [Label] bulkUpsert srcSn=1 actor=2001 existing=1 saved=1 deleted=1   ← B(stale 작업본) 저장 → X 삭제
  ```
  B 는 A 의 저장을 모르는 상태(빈 작업본)에서 자기 라벨만 보냈고, 응답은 **200 정상**이었으며 A 의 라벨은 사라졌다. 사용자에게 어떤 경고도 없다.
  **가정이 깨지는 실제 경로(확인됨)**: ①`LabelAccessGuard` 는 REVIEWER 를 무조건 통과시키므로(LabelAccessGuard.java:45-47) REVIEWER 와 배정 WORKER 가 **동시에 같은 프레임을 편집 가능** — 실제로 REVIEWER 토큰과 WORKER 토큰으로 같은 srcSn 을 동시 PUT 해 둘 다 200 을 받았다. ②동일 WORKER 의 다중 탭. ③재배정 직후 이전 담당자의 열린 화면.
- **재현/확인 경로**:
  ```bash
  B=http://localhost:18081/api; WK=<WORKER 토큰>
  # 1) A 탭이 라벨 X 저장
  curl -X PUT $B/v1/frames/1/labels -H "Authorization: Bearer $WK" -H 'Content-Type: application/json' \
    -d '{"items":[{"id":null,"lblTypeCd":"BBOX","labelId":1,"label":"tabA-X","points":[[1,1],[11,11]]}]}'
  # 2) B 탭(A 저장 전에 화면을 열어둔 stale 작업본)이 자기 라벨만 저장
  curl -X PUT $B/v1/frames/1/labels -H "Authorization: Bearer $WK" -H 'Content-Type: application/json' \
    -d '{"items":[{"id":null,"lblTypeCd":"BBOX","labelId":2,"label":"tabB-Y","points":[[50,50],[60,60]]}]}'
  # 3) 확인 — tabA-X 는 사라져 있음
  docker exec -e PGPASSWORD=<pw> klid-postgres psql -U klid_user -d klid_system \
    -c "select lbl_sn,lbl_nm from ls_data_lbl where src_sn=1;"
  docker logs klid-backend --since 5m | grep '\[Label\] bulkUpsert'   # deleted=1 로 소실 확인
  ```
- **영향**: CWE-362(Race Condition) / lost update. 라벨 작업 결과가 통보 없이 사라진다. 삭제 자체는 `LS_DATA_LBL_HSTRY` 에 before 스냅샷으로 남으므로(:339-345) **사후 복구 근거는 존재**하나, 사용자·UI 는 소실을 인지하지 못한다. 검수 승인(APPROVED) 전 단계에서 발생하면 `LS_LABEL_VERSION` 스냅샷도 아직 없어 안전망이 이력뿐이다.
- **수정 방향(제안)**: (a) `LS_DATA_SRC` 또는 프레임 라벨 세트에 낙관적 버전(요청에 `baseVersion`/ETag 동반, 불일치 시 409)을 도입, 또는 (b) 프레임 단위 편집 세션 락(기존 `ls_auth_work_lock` 의 `DATA_SRC_SN` 컬럼이 이미 존재하나 미사용), 또는 (c) 최소 조치로 `deleted>0` 이면서 요청이 기존 id 를 하나도 포함하지 않는 저장을 경고·확인 요구로 승격. **UNCERTAINTIES #9 는 "기대 동작 미확정" 이므로 정책 확정이 선행되어야 함 — 본 보고서는 사실만 기록.**

### [C-ISSUE-22] 좌표 이미지 경계 초과 저장 무검증 (UNCERTAINTIES #10)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프레임 이미지 해상도를 벗어난 좌표(예: 640×480 프레임에 x=999999)는 학습데이터로 무의미하므로 저장 시점에 거부되거나 클램프되어야 한다.
- **현재 동작**: `validatePoints`(LabelService.java:575-599)는 **빈 배열·[x,y] 형식·음수·개수 상한**만 검증하고 **상한 좌표(이미지 폭/높이) 검증이 전혀 없다**. 실측:
  ```
  PUT /v1/frames/1/labels {"points":[[999999,888888],[1000000,999000]]}
  → HTTP 200, DB point_cn = [[999999.0,888888.0],[1000000.0,999000.0]] 그대로 저장
  ```
  `LabelItemDto` javadoc(LabelItemDto.java:19)은 "좌표 검증은 Service 에서 — 음수/**이미지 경계 초과** 차단"이라고 적혀 있어 **주석과 구현이 불일치**한다.
- **재현/확인 경로**: 위 PUT 그대로. (본 시험에서 저장한 초과 좌표 라벨은 후속 정리 단계에서 삭제 완료 — 잔존 없음)
- **영향**: CWE-20(입력 검증). 캔버스 클램프가 유일한 방어선이라 API 직접 호출·FE 버그·오토라벨 좌표 변환 오류가 그대로 학습데이터·export JSON(COCO bbox)에 유입된다. 보안 침해보다는 **데이터 품질 오염**이 주 리스크.
- **수정 방향(제안)**: `LS_DATA_SRC` 에 프레임 해상도가 있으면 그 값으로 상한 검증, 없으면 영상 해상도(`LS_DATA_RAW`) 기준 검증. 정책이 "클램프 허용"이면 최소한 위 javadoc 문구를 실제 구현에 맞게 정정. **UNCERTAINTIES #10 미확정이므로 사실만 기록.**

### [C-ISSUE-23] TC-LABEL-30 — R7 손상 좌표 JSON fail-safe 전용 테스트 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: `pointCn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 '변경됨'으로 판정해 이력이 유실되지 않아야 하고, 그 동작이 회귀 테스트로 고정되어야 한다.
- **현재 동작**: 구현(LabelService.java:479-483)은 `normalizePoints` 가 3포맷 + raw 폴백 모두 실패하면 `null` → `false`(변경됨) 반환으로 fail-safe 가 **정상 구현**되어 있다. 그러나 `backend/src/test/**` 전수 grep 결과 `pointCn` 손상 케이스를 직접 겨냥한 테스트가 없다. 유사 이름의 `LabelServiceFullReplaceIntegrationTest:277 corruptDiffJsonGracefulFallback` 은 **이력 diff JSON(`CHG_DTL_CN`) 손상** 케이스로 대상이 다르다.
- **재현/확인 경로**: `grep -rn "pointsEqual\|normalizePoints" backend/src/test` → 0건.
- **영향**: 회귀 위험만. 현재 동작 결함 아님.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `pointCn` 을 손상값(`"{{{"`)으로 덮어쓴 뒤 무변경 재저장 시 UPDATED 이력이 남는지 확인하는 케이스 1건 추가.

### [C-ISSUE-24] TC-LABEL-31 — TASK_MODIFIED 발행 런타임 관측 불가 (로컬 환경 제약)
- **심각도**: LOW (환경 제약 — 코드 결함 아님)
- **기대 동작(기대효과)**: APPROVED 영상의 라벨을 수정하면 `TaskModifiedEvent(LABEL_UPDATED)` 가 발행되고 디바운스를 거쳐 관제 outbound 통지로 이어져야 한다.
- **현재 동작**: 실동작으로 확인한 것은 **APPROVED 영상 라벨 편집이 차단되지 않고 200 으로 허용된다**는 사실까지다(rawSn=13/srcSn=359). 발행 여부는 `klid-backend` 컨테이너 env `CONTROL_NOTIFY_ENABLED=false` 이고 `ControlNotifyEventListener` 가 `@ConditionalOnProperty(name="authoring.control-notify.enabled", havingValue="true")`(ControlNotifyEventListener.java:21) 라 **리스너 빈 자체가 생성되지 않아** 로그·DB 어디에도 흔적이 남지 않는다. 발행 지점(LabelService.java:365-371)과 게이트(`isReviewApproved`)는 정적으로 정상이며, `LabelServiceTaskModifiedGuardTest:143 검수완료_발행` 이 rawSn/srcSn/changeType 을 단위 수준에서 검증한다.
- **재현/확인 경로**: `docker exec klid-backend env | grep CONTROL_NOTIFY_ENABLED` → `false`.
- **영향**: 검증 커버리지 공백. self-fill 결함 아님(값을 만들어낸 것이 아니라 기능이 설정으로 비활성).
- **수정 방향(제안)**: `CONTROL_NOTIFY_ENABLED=true` + 관제 inbound 를 mock-server 로 스텁한 별도 프로파일에서 재검증. (본 임무는 코드·설정 수정 금지라 미수행)

### [C-ISSUE-25] 사용 중 마스터 라벨을 soft delete 하면 해당 프레임의 라벨 저장이 전면 차단됨
- **심각도**: MEDIUM (참조 무결성 · 작업 차단 — 반증 시도 중 발견, 원 TC 목록 외)
- **기대 동작(기대효과)**: 마스터 라벨 soft delete 는 "신규 사용 중지"를 뜻해야 하며, 이미 그 라벨을 참조하는 기존 프레임의 편집·저장까지 막아서는 안 된다(막는다면 최소한 명시적 정책이어야 한다).
- **현재 동작**: `validateAndLoadLabels`(LabelService.java:541-564)는 요청 items 의 **모든** `labelId` 에 대해 `USE_YN='N'` 이면 409 를 던진다. 저장 계약이 **full-replace(프레임 전체 세트 전송)** 이므로, 프레임에 soft-delete 된 마스터를 참조하는 라벨이 1건이라도 있으면 그 프레임의 **모든 저장이 409 로 막힌다** — 그 라벨을 지우지 않는 한 다른 라벨 수정도 불가능하다. 실 데이터에 해당 상황이 이미 존재한다:
  ```
  ls_label:    lbl_id=248 'pose-skeleton'  use_yn='N'   (soft delete 됨)
  ls_data_lbl: lbl_sn=29  src_sn=11  lbl_type_cd=SKELETON  lbl_id=248  (참조 잔존)
  ```
  `USE_YN='N'` 라벨이 409 를 유발한다는 것은 TC-LABEL-21 에서 `labelId=7`(animal, `use_yn='N'`)로 **실동작 확증**했다.
- **재현/확인 경로**: (파괴 위험이 있어 srcSn=11 실저장은 **수행하지 않음** — 성공 시 rawSn=11 의 라벨 54건이 full-replace 로 손상될 수 있어 의도적으로 회피)
  ```sql
  -- 상황 확인 (읽기 전용)
  select l.lbl_sn, l.src_sn, l.lbl_id, m.lbl_nm, m.use_yn
    from ls_data_lbl l join ls_label m on m.lbl_id = l.lbl_id
   where m.use_yn = 'N';
  ```
  기전 확증(무해): `PUT /v1/frames/1/labels` 에 `labelId:7` 포함 → 409 "사용 중지된 라벨입니다: labelId=7".
- **영향**: 운영자가 사용 중인 라벨을 정리(soft delete)하는 순간 해당 라벨이 붙은 모든 프레임의 라벨링 작업이 잠긴다. 작업자는 원인을 알 수 없는 409 를 만난다.
- **수정 방향(제안)**: `USE_YN='N'` 검증을 **신규 부여(id==null)에만** 적용하고, 기존 라벨의 labelId 유지(변경 없음)는 통과시키는 방향. 또는 마스터 삭제 시 참조 건수를 사전 안내하고 대체 라벨 지정을 요구.

## UNCERTAINTIES 확인 결과

- **#9 동시저장 race (라벨 full-replace)**: **lost update 가 실제로 발생함을 실동작으로 재현**했다(C-ISSUE-21). `@Version`·비관락·ETag 전부 부재(LabelService.java:331-333 주석이 부재를 명시). "단일 WORKER 배정" 가정은 **깨진다** — `LabelAccessGuard` 가 REVIEWER 를 무조건 통과시키므로(LabelAccessGuard.java:45-47) REVIEWER+WORKER 동시 편집이 가능하고, 실제로 두 토큰으로 같은 프레임에 동시 PUT 해 둘 다 200 을 받았다. 다중 탭·재배정 직후도 동일. 다만 **삭제 before 스냅샷이 `LS_DATA_LBL_HSTRY` 에 남아 사후 복구 근거는 존재**한다. 기대 동작(충돌 시 409 냐 병합이냐 last-write-wins 수용이냐)이 미확정이라 판정은 `확인필요`.
- **#10 좌표 이미지 경계 초과**: **경계 초과 좌표가 검증 없이 그대로 저장됨을 실동작으로 확인**했다(C-ISSUE-22). `[[999999,888888],[1000000,999000]]` → HTTP 200 + DB 원값 저장. 저장 경로에는 음수·[x,y] 형식·개수 상한 검증만 존재하고 상한 좌표 검증은 코드에 없다. `LabelItemDto.java:19` javadoc 은 "이미지 경계 초과 차단"을 주장해 **문서-구현 불일치**가 함께 확인되었다. 정책 미확정으로 사실만 기록.

## 추가 확인 사항 (반증 시도 결과)

- **알려진 미해결 HIGH(대소문자 근사중복 → `findLabelIdByName` 2행 → 오토라벨 배치 크래시)**: **해소된 것으로 확인**. ①`findLabelIdByName` 은 코드베이스에 더 이상 존재하지 않으며 `findLabelIdByDtctType`(COCO 매핑 축, LabelMasterService.java:151-157)로 대체됨. ②이름 기반 잔여 경로(`findByLabelNmIgnoreCaseAndUseYn`, LsLabelRepository.java:67-70)는 `PageRequest.of(0,1)` 방어적 top-1 이라 다중 결과에도 예외를 던지지 않음. ③DB 에 부분 유니크 인덱스 2종 실존 확인:
  `uk_ls_label_nm_ci UNIQUE (lower(btrim(lbl_nm))) WHERE use_yn='Y'` (V120),
  `uk_ls_label_dtct_type UNIQUE (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` (V129).
  ④활성 중복 매핑 실측 0건(`group by dtct_type_cd having count(*)>1` → 0 rows). ⑤5병렬 동일 이름 생성에서 정확히 1건만 201(TC-42).
- **채널 격리**: PORTAL_USER 토큰으로 `GET /v1/frames/1/labels` → **403** (내부 채널 전용 확인). 반면 `GET /v1/manage/labels` 는 **200**(TC-53 설계대로).
- **Mass Assignment 추가 반증**: `ls_data_lbl` 실제 컬럼 = `lbl_sn, src_sn, lbl_type_cd, lbl_nm, point_cn, trck_id, reg_user_no, reg_dt, mdfcn_dt, lbl_id` — `auto_lbl_yn` 컬럼 자체가 없어 요청으로 오염시킬 대상이 구조적으로 존재하지 않음.
- **`clearAutomatically` 사용 여부(PII 방어 무력화 우려)**: full-replace 삭제 경로는 `attrValRepository.deleteByLblSnIn` → `aiInfoRepository.deleteByDataLblSnIn` → `labelRepository.deleteAllByIdInBatch`(LabelService.java:349-351) 를 사용하며, 삭제 후 재조회가 없어 stale 1차 캐시 리스크 없음. 실동작에서도 삭제 직후 응답·DB 가 일치했다.
- **근거 라인 드리프트**: 검증한 49건의 `근거(file:line)` 전부 실제 코드 위치와 일치(±2행 이내). **드리프트 0건**.
- **self-fill 결함**: 본 구간에서 외부 응답 없이 값을 자체 생성해 실측값처럼 보이게 한 지점 **0건**. `confScore=0.87`·`lbl_src_cd=YOLO` 는 요청 provenance 를 그대로 보존한 값이고, `autoLblYn` 은 AI_INFO row 존재에서 파생되는 정당한 계산값이다.

## 요약

- 총 **49건** / PASS **46** / FAIL **0** / PARTIAL **2**(TC-30, TC-31) / BLOCKED **0** / N/A **0** / 확인필요 **1**(TC-36)
- 근거 라인 드리프트: **0건** / self-fill 결함: **0건**
- 신규 이슈 **5건**: C-ISSUE-21(HIGH, lost update 재현) · C-ISSUE-22(MEDIUM, 좌표 경계 무검증) · C-ISSUE-23(LOW, 테스트 공백) · C-ISSUE-24(LOW, 환경 제약) · C-ISSUE-25(MEDIUM, soft delete 참조 차단 — TC 목록 외 반증 발견)
- 실동작 검증 비율: 49건 중 **44건이 실제 HTTP 요청 + DB/로그 조회**로 확인됨(TC-29/30/32 는 정적+기존 테스트, TC-31 은 부분 실동작)

# C-2 (후반) 검증 결과 (docs/test-cases/C-marking-labeling.md:98-143)

> 검증일 2026-07-25 · 로컬 풀스택 실동작 기준(klid-backend :18081, klid-postgres, klid-ai-server :19300, klid-mock-server :9400)
> DB 스키마는 `klid_at` 아닌 **`public`**.
> ✅ **환경 정정**: 착수 시 `klid-backend` 재빌드 완료 확인 — `/app/app.jar` Jul 25 01:59, 내장 마이그레이션 `V130__add_manual_env_privacy_meta` 포함, `flyway_schema_history` 최신 = **130**. B-part4 의 [B-ISSUE-62](stale jar) 는 **해소**되었고, 그로 인해 BLOCKED 였던 TC-DEID-035(=TC-LABEL-97)를 이번 회차에 **실동작으로 재검증**했다.
> ⚠ **본 검증이 남긴 상태 변경(전부 API 경유)**:
> - 라벨 마스터: `tcprobe`(493)·`tcmass`(494)·`tcrace`(495) 생성 후 **전부 soft delete(USE_YN='N')** 처리 — 활성 라벨 풀·COCO 매핑 allowlist 는 검증 전과 동일. 라벨 9(smoke)에 동일값 PUT 1회(값 불변).
> - 프레임 개인정보 메타: `src 369/370/371`(rawSn=7)에 Y/N/N 설정 → 신고로 NULL 리셋됨(검증 전 상태 = NULL 이므로 **원상복귀**).
> - 비식별 신고: **라벨 0건 영상 rawSn=7** 에만 2건(rprtSn 7·8) 등록 후 **둘 다 RESOLVED 처리** → `DE_IDENT_YN='Y'` 복원, 작업락 RELEASED. 라벨 파괴 **0건**(검증 데이터 rawSn=26 등 라벨 보유 영상은 신고 대상에서 제외).
> - `/app/storage/deidentified/videos/7/deidentified.mp4` mtime 을 `touch` 2회(외부 재비식별 시뮬레이션). 파일 내용 불변(24182B 동일).
> - 오토라벨 온라인 호출 다수 — **DB 미저장 경로**이므로 라벨 데이터 변화 0건(반증 확인함).

## C-2 (후반). TC-LABEL-52 ~ 109 — 권한 / 마스터 / 온라인 오토라벨 / 비식별신고

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-LABEL-52 | 관리 권한: WORKER POST 차단 | PASS | **[실동작]** WORKER POST/PUT/DELETE → 전부 **403**, PORTAL_USER POST → 403, 미인증 → 401, `PATCH` → 403, `X-HTTP-Method-Override: POST`+GET → **200 GET 그대로(생성 0건)**. 정적 `LabelMasterController.java:83,101,117` | LabelMasterControllerTest:149 | 우회 경로 4종 전부 차단. ★부가 발견: 토큰 `role=REVIEWER` 라도 **userNo=2001(DB상 WORKER)** 이면 403 — 역할이 토큰 클레임만이 아니라 서버측으로 재확인됨(권한 자가부여 방어) |
| TC-LABEL-53 | 조회 권한: WORKER/PORTAL 허용 | PASS | **[실동작]** WORKER GET 200, PORTAL_USER GET 200, 미인증 → **401**. `detect-candidates` 도 WORKER/PORTAL 200. 정적 `:57,69` | LabelMasterControllerTest:87,224 | **비활성(USE_YN='N') 라벨 미노출** 반증 확인 — animal·fallen-person·pose-skeleton·tcprobe·tcmass 응답 0건. 내부코드 누출 없음 |
| TC-LABEL-54 | findLabelIdByDtctType null/blank | PASS | **[정적]** `LabelMasterService.java:151-157` — null/blank 시 repository 미호출 `Optional.empty()`. **[실동작 등가]** 미매핑 요청 경로에서 labelId null 반환 확인 | LabelMasterServiceTest:303 | |
| TC-LABEL-55 | V129 유니크 최대1 | PASS | **[실동작]** `pg_indexes` 에 `uk_ls_label_dtct_type UNIQUE (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` 실존. `person`/`" person "` 중복 매핑 시도 → 둘 다 **409**. 정적 `LsLabelRepository.java:84` | LabelMasterServiceTest:271,283,311 | NonUniqueResult 재현 불가(활성 1건 강제됨) |
| TC-LABEL-40 | 마스터 생성 정상 | PASS | **[실동작]** REVIEWER POST → **201** `labelId=493` | LabelMasterServiceTest:51 | 범위 밖이나 전제로 실행 |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | PASS | **[실동작]** `"  Car  "` → **409**, `"PERSON"` → **409**. 정적 `:76-78` | LabelMasterServiceTest:129,142 | |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | PASS | **[실동작]** 동일명 5병렬 POST → **201×1 / 409×4**, DB 활성 행 **1건**. 정적 `:70-78` | LabelMasterServiceTest:99 | 앱 선판정+`uk_ls_label_nm_ci` 이중 방어 |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | PASS | **[실동작]** `"human"`→400, `"Person"`(대문자)→400, `"person' OR 1=1--"`→**400**(SQLi 차단). 정적 `:126-135` | LabelMasterServiceTest:338 | |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | PASS | **[실동작]** `person`·`" person "` → 409. 정적 `:81-83` | LabelMasterServiceTest:349,374 | |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | PASS | **[실동작]** `""`·`"   "`·필드누락 → 200 + DB `dtct_type_cd IS NULL` 확인. `zebra` 매핑→재해제 왕복 확인. 정적 `:127-129` | LabelMasterServiceTest:361 | |
| TC-LABEL-46 | color 소문자 hex 거부 | PASS | **[실동작]** `#ffffff` → 400 "color 는 대문자 hex". 정적 `LabelMasterRequest.java:33` | LabelMasterControllerTest:174 | |
| TC-LABEL-47 | type allowlist | PASS | **[실동작]** `FOO` → 400. 정적 `:37` | LabelMasterControllerTest:134 | |
| TC-LABEL-48 | sortNo 음수 | PASS | **[실동작]** `-1` → 400. +name 65자 → 400(@Size). +Mass assignment 반증: `useYn:"N"`·`labelId:1`·`regId:"hacker"` 주입 → **전부 무시**(useYn=Y, 신규 id 발번). 정적 `:40` | — | `@JsonIgnoreProperties(ignoreUnknown=true)` + record 필드 화이트리스트 |
| TC-LABEL-49 | 마스터 수정 미존재 | PASS | **[실동작]** PUT/DELETE `id=999999` → **404**. 정적 `:100-101,191-192` | LabelMasterServiceTest:173 | |
| TC-LABEL-50 | 마스터 수정 근사중복(자기제외) | PASS | **[실동작]** 라벨8(fire)→`"CAR"` → **409**. 라벨9(smoke)→`" smoke "`(자기이름) → **200**(자기제외 정상). 정적 `:104-106` | LabelMasterServiceTest:227,239 | |
| TC-LABEL-51 | 마스터 삭제 soft delete | PASS | **[실동작]** DELETE 494/493 → **204**, DB 행 **잔존 + `use_yn='N'`**(hard delete 아님). 정적 `:189-195` | LabelMasterServiceTest:183 | |
| TC-LABEL-60 | autolabel BBOX 정상 | **PARTIAL** | **[실동작]** src447 → **200**, `lblSn:null` 좌표만. **미저장 반증 통과**: 호출 전후 `ls_data_lbl` count=8·`max(reg_dt)` 완전 동일(3회 반복). **그러나 rawSn=26 프레임 5건 중 4건(446/448/449/450)이 400** — 실모델 음수 좌표로 전량 거부 → **[C-ISSUE-41]** | AutolabelOnlineServiceTest:127,146 | 미저장 정책은 PASS, 정상 검출 성공률이 20%(1/5) |
| TC-LABEL-61 | autolabel IDOR | PASS | **[실동작]** WORKER 2002(미배정) → **403** "본인에게 배정되지 않은 영상입니다.", PORTAL → 403, 미인증 → 401, 미존재 프레임 → 404. 정적 `AutolabelOnlineService.java:185` | AutolabelControllerTest:121,128,136 | ai 호출 전 최우선 게이트 |
| TC-LABEL-62 | autolabel 작업락 | PASS | **[실동작]** 잠긴 rawSn=8(src 4)·rawSn=12(src 350) → **409** "작업이 잠긴 영상입니다.". 정적 `:189-191` | AutolabelOnlineServiceTest:200 | |
| TC-LABEL-63 | autolabel inFlight 중복 | PASS | **[실동작]** 동일 프레임 5병렬 → **200×1 / 409×4**, 직후 재요청 **200**(finally 락해제 확인). 정적 `:194-196,254-256` | AutolabelOnlineServiceTest:372,180 | |
| TC-LABEL-64 | resolveDetectClasses 매핑 0건 게이팅 | PASS | **[실동작 등가]** 요청이 전부 미매핑이면 동일 분기(`effectiveClasses.isEmpty()`) 진입 — ai **미호출**·0건 + `NO_MAPPED_CLASS_MESSAGE` 확인. 전 라벨 매핑 해제(마스터 6건 파괴)는 회피. 정적 `:205-212` | AutolabelOnlineServiceTest:471,460 | |
| TC-LABEL-65 | resolveDetectClasses 화이트리스트 교집합 | PASS | **[실동작]** `["person","zebra"]` → **person 3건만** 반환(전체호출 4건 = person3+bus1 대비 bus·zebra 제외). `["  person  "]` 도 trim 후 통과. 정적 `:413-427` | AutolabelOnlineServiceTest:404,446 | 매핑 allowlist 교집합만 ai 전달 실증 |
| TC-LABEL-66 | resolveDetectClasses 우회 시도 | PASS | **[실동작] 우회 4종 전부 차단** — `["zebra"]`(유효 COCO·미매핑)·`["dog","cat"]`·`["hack","<script>"]`·`["PERSON"]`(대소문자) 모두 **0건 + NO_MAPPED 메시지**, ai 미호출. 정적 `:408-427` | AutolabelOnlineServiceTest:460 | **FE 요청 불신 성립** — 미매핑 라벨이 검출된 사례 0건 |
| TC-LABEL-67 | autolabel mock 응답 차단 | PASS | **[정적]** `:221-228` — `resp.mock()` 시 좌표 미반환·빈 결과. live 는 ai-server 실모델(mock=false)이라 미발화 | AutolabelMockMessageWiringTest:34 / AutolabelOnlineServiceTest:323 | |
| TC-LABEL-68 | validateBbox 4좌표 아님 | PASS | **[정적]** `:499-503` | AutolabelOnlineServiceTest:290 | 외부 응답 주입 불가로 live 미실행 |
| TC-LABEL-69 | validateBbox NaN/Infinity | PASS | **[정적]** `:504-508` (`Double.isFinite`) | AutolabelOnlineServiceTest:257,268 | |
| TC-LABEL-70 | validateBbox 음수/순서위반 | PASS | **[실동작]** 실모델 음수 좌표로 **실제 400 발화**(`"YOLO 응답 좌표는 0 이상이어야 합니다."`) — 가드 자체는 스펙대로 동작. 정적 `:509-518` | AutolabelOnlineServiceTest:246,279 | 가드는 PASS 이나 **부작용이 [C-ISSUE-41]** |
| TC-LABEL-71 | 검출 0건 SAM 스킵 | PASS | **[실동작]** `["truck"]`·`["bicycle"]`(매핑됐으나 화면에 부재) → **200 / detectedCount=0 / message=null** — NO_MAPPED(0건 안내) 와 정상 0건이 구분됨. 정적 `:236-241` | AutolabelPolygonServiceTest:285 / OnlineServiceTest:345 | |
| TC-LABEL-72 | POLYGON maxBoxes 상한 | PASS | **[정적]** `:273-278`, 설정 `autolabel.polygon.max-boxes=20`. live 미발화 — 실데이터 최대 검출 **19건/프레임 < 20** | AutolabelPolygonServiceTest:217 | |
| TC-LABEL-73 | POLYGON wall-clock 예산 소진 | PASS | **[정적]** `:280-296`(기본 60s). live 폴리곤 4박스 소요 7.7s | AutolabelPolygonServiceTest:339 | |
| TC-LABEL-74 | POLYGON 박스별 실패 스킵 | PASS | **[정적]** `:298-325` | AutolabelPolygonServiceTest:234,253,268 | |
| TC-LABEL-75 | POLYGON bulkhead 429 즉시전파 | PASS | **[실동작]** POLYGON 6병렬 → **200×4 / 429×2**, 429 가 스킵으로 **흡수되지 않고** 응답으로 전파됨(부분 결과 아님). 정적 `:315-320` | AutolabelPolygonServiceTest:314 | |
| TC-LABEL-76 | POLYGON TOCTOU 중간 잠금 | PASS | **[정적]** `:286-289` + 배치 후 `reCheckLock` `:329`. live 재현은 배치 중 신고 = 라벨 파괴 필요로 회피 | AutolabelPolygonServiceTest:299 / OnlineServiceTest:164 | |
| TC-LABEL-77 | YOLO bulkhead 초과 429 | PASS | **[실동작]** 서로 다른 8프레임 동시 → **200×4 / 429×4** — `max-concurrent-calls: 4`(application.yml:349) 와 정확히 일치, `max-wait-duration:0` fail-fast. 정적 `:450-454` | — | |
| TC-LABEL-78 | ai-server 호출 실패 502 | PASS | **[정적]** `:455-460` — `EXTERNAL_API_ERROR`, 스택트레이스 미노출(CWE-209) | AutolabelOnlineServiceTest:305 | ai-server 중단은 타 검증 영향으로 미실행 |
| TC-LABEL-79 | AutolabelRequest conf 범위 | PASS | **[실동작]** 0.9 → 400, 0.1 → 400. 정적 `AutolabelRequest.java:39-40` | AutolabelRequestPrecisionValidationTest:37,45 | +`simplifyTolerance:60` → 400 |
| TC-LABEL-80 | classes 100개 초과 | PASS | **[실동작]** 101개 → 400, 원소 51자 → **400**(`classes[0]`). 정적 `:36-37` | AutolabelControllerTest:207 | +`shape:"CIRCLE"` → 400(역직렬화 차단) |
| TC-LABEL-90 | 신고 정상+전체 삭제 | PASS | **[실동작]**(라벨 0건 rawSn=7) → **201** `rprtSn=7`, `DE_IDENT_YN='Y'→'F'`, `LS_AUTH_WORK_LOCK` LOCKED(owner 1001) DB 확인. 라벨 스냅샷·삭제 경로는 라벨 보유 영상 파괴 회피로 정적 `:140-164` + IT | DeidentReportServiceTest:172,220,287 | B-part4 TC-DEID-034 와 **판정 일치** |
| TC-LABEL-91 | 신고 reason 누락 | PASS | **[실동작]** `""`·`"   "`·필드누락 → 전부 **400**. 정적 `:112-114` | ControllerTest:189 / ServiceTest:486 | B-part4 TC-DEID-030 일치 |
| TC-LABEL-92 | 신고 reason 1000자 초과 | PASS | **[실동작]** 1001자 → 400 "신고 사유는 1000자 이하". 정적 `DeidentReportRequest.java:19` | — | |
| TC-LABEL-93 | 신고 IDOR | PASS | **[실동작]** WORKER 2002(미배정) → 403, PORTAL → 403, 미인증 → 401. 정적 `:117` | ControllerTest:139 | B-part4 TC-DEID-031 일치 |
| TC-LABEL-94 | 신고 이미 잠금 | PASS | **[실동작]** rawSn=7 신고 직후 동일 영상 타 프레임(370) 재신고 → **409**. 정적 `:132-134` | ControllerTest:168 | B-part4 TC-DEID-033 일치 |
| TC-LABEL-95 | 신고 PII TOCTOU(FOR UPDATE) | PASS | **[정적]** `:127-128` `findByRawSnForUpdate` + 대칭 게이트 `ResolutionReservationPersister:63-74` | AugmentDeidentConcurrencyIT | B-part4 TC-DEID-032 일치 |
| TC-LABEL-96 | 신고 동시 락 유니크 | PASS | **[실동작]** 동일 영상 5병렬 신고 → **201×1 / 409×4**, DB 신고행 **1건**·작업락 **1건**. 정적 `:132-134,159-163` | ServiceTest:431 | B-part4 TC-DEID-036 일치(이번엔 병렬 실측) |
| TC-LABEL-97 | 신고 개인정보 3필드 리셋 | PASS | **[실동작] ★B-part4 BLOCKED 해소** — V130 적용된 재빌드 스택에서 재검증. `PUT /v1/frames/{369,370,371}/privacy-meta` 로 Y/N/N 설정 → 신고 후 3프레임 `anony_incl_yn·psdo_incl_yn·prvc_incl_yn` **전부 NULL**. 앱 로그 `privacyReset=3`. 정적 `:151-155` | ServiceTest:199 / ResetIT:67 | B-part4 TC-DEID-035(BLOCKED) → **PASS 로 승격** |
| TC-LABEL-98 | 신고 APPROVED→TASK_MODIFIED | PASS | **[정적]** `:144-149`(`snapshotted && isReviewApproved`). **[실동작 음성확인]** rawSn=7(work_stts=ASSIGNED, 라벨 0건) 신고 시 `labelsRemoved=false` 로 통지 **미발행** — 조건 게이트 동작 확인. APPROVED+라벨 보유 영상은 파괴 회피 | ServiceTest:391,413 | B-part4 TC-DEID-037 일치 |
| TC-LABEL-99 | 신고 라벨 0건 스킵 | PASS | **[실동작]** 로그 `labelsRemoved=false`, `LS_DATA_LBL_HSTRY`(rawSn=7) **0건**, `LS_LABEL_VERSION` 0건. 정적 `:140-149` | ServiceTest:261,354 | B-part4 TC-DEID-038 일치 |
| TC-LABEL-100 | resolve 미인증 | PASS | **[실동작]** 무토큰 → **401**. 정적 `:193-195` | ServiceTest:831 / ControllerTest:294 | B-part4 TC-DEID-039 일치 |
| TC-LABEL-101 | resolve 신고 미존재 | PASS | **[실동작]** `rprtSn=999999` → **404**. 정적 `:196-197` | ServiceTest:840 | B-part4 TC-DEID-040 일치 |
| TC-LABEL-102 | resolve IDOR | PASS | **[실동작]** WORKER 2002 가 rprtSn=2(rawSn=8, 2001 배정) resolve → **403**. WORKER 2001(본인배정) → 409(산출물 게이트 = 인가 통과), PORTAL → 403. 정적 `:200` | ControllerTest:241,273 | B-part4 TC-DEID-050 일치 |
| TC-LABEL-103 | resolve OPEN 아님 | PASS | **[실동작]** `rprtSn=1`(RESOLVED) → **409** "이미 처리된 신고입니다.". 정적 `:203-205` | ServiceTest:560 | B-part4 TC-DEID-041 일치 |
| TC-LABEL-104 | resolve 산출물 미검증 gate | PASS | **[실동작]** `rprtSn=2`(rawSn=8) → **409** + DB 재확인: report OPEN 유지·`DE_IDENT_YN='F'` 유지·락 LOCKED 유지(롤백 성립). 정적 `:207-211,332-376` | ServiceTest:666,691 | B-part4 TC-DEID-042/044 일치 |
| TC-LABEL-105 | resolve 시간조건(신고 이후 재비식별) | PASS | **[실동작] ★직접 A/B 대비** — rprtSn=7(신고 11:07:37), 비식별본 mtime `Jul 22 02:15` 상태 resolve → **409**. 동일 파일 `touch`(mtime→Jul 25 02:08 UTC = 11:08 KST) 후 재호출 → **200**. 옛 비식별본 배제가 실제로 작동. 정적 `:353-375` | ServiceTest:735,768,798 | B-part4 TC-DEID-045 일치(이번엔 통과/거부 양방 실측) |
| TC-LABEL-106 | resolve 정상+'F'→'Y' 복원 | PASS | **[실동작] ★B-part4 정적판정 → live 승격** — resolve 200 후 DB: `rprt_stts_cd=RESOLVED`(resolved_dt 기록), `LS_AUTH_WORK_LOCK` **RELEASED/`MANUAL_DEIDENT_DONE`**, `LS_DATA_RAW.DE_IDENT_YN='F'→'Y'`. 정적 `:213-231` | ServiceTest:544,604,622,709 | B-part4 TC-DEID-043 (정적) 과 **모순 없음, 강화** |
| TC-LABEL-107 | resolve 배치상태 역행 금지 | PASS | **[실동작]** 신고 전/신고 후/resolve 후 rawSn=7 `DATA_STTS_CD` = **COMPLETED 불변**. 정적 `:223-231` | ServiceTest:642 | B-part4 TC-DEID-046 일치 |
| TC-LABEL-108 | listReports status allowlist | PASS | **[실동작]** `status=X` → 400, `status=OPEN' OR 1=1--` → **400**(SQLi 차단), `status=resolved`(소문자) → 400. WORKER 목록 조회 → 403. 정적 `:260-273` + Controller `@Pattern` | ControllerTest:353,361 | B-part4 TC-DEID-048 일치 |
| TC-LABEL-109 | listReports 기본 OPEN | PASS | **[실동작]** status 미지정 → 200, OPEN 5건만 반환. `size=500` → 응답 `size:100` **클램프**. 정적 `:261-263` | ControllerTest:322 | 페이징 강제됨(전체조회 없음) |

---

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-41] TC-LABEL-60 / TC-LABEL-70 — 실모델 YOLO 가 반환하는 경계 음수 좌표 때문에 온라인 오토라벨이 프레임 대부분에서 400 으로 전량 거부됨 (배치 경로와 검증 정책 불일치)
- **심각도**: HIGH (핵심 기능 가용성 — 라벨링 화면 'AI 탐지' 버튼이 실데이터에서 80% 실패)
- **기대 동작(기대효과)**: 라벨링 화면에서 AI 탐지를 누르면 프레임의 검출 좌표가 반환되어 캔버스 작업본에 반영된다. 화면 경계에 걸친 객체(사람이 프레임 왼쪽 끝에 반쯤 걸림 등)는 CCTV 학습데이터에서 정상적인 다수 케이스이며, 배치 오토라벨 경로는 이를 정상 저장한다.
- **현재 동작(이슈 내용)**: 온라인 경로는 ai 응답 좌표를 **all-or-nothing** 으로 검증하며 **음수를 곧바로 전체 거부**한다.
  - `backend/.../label/service/AutolabelOnlineService.java:509-512`
    ```java
    if (v < 0) {
        throw new CustomException(ErrorCode.INVALID_INPUT,
                "YOLO 응답 좌표는 0 이상이어야 합니다.");
    }
    ```
    `:230-233` 에서 검출 전체를 순회하며 하나라도 위반이면 400 → **정상 검출분까지 전부 폐기**(부분 반환 금지가 설계 의도).
  - **실측(로컬 실모델 ai-server, rawSn=26)**: 5개 프레임 중 **4개(src 446/448/449/450)가 400**, 성공은 src 447 하나뿐.
  - **동일 모델·동일 프레임을 배치 경로는 그대로 저장**한다 — `LS_DATA_LBL` 에 음수 좌표 라벨이 실존:
    ```
    lbl_sn=205 src=446 bus    [[-1.5731448368773044, 2.556953126603844], [1261.30…, 707.91…]]
    lbl_sn=213 src=448 bus    [[11.71…, -2.573257235451649], [1241.05…, 611.50…]]
    lbl_sn=215 src=449 person [[1032.46…, -0.9496699098489216], [1279.68…, 719.26…]]
    ```
    rawSn 별 음수 좌표 라벨 수: 26→15건, 28→4건, 27→2건, 14~19→각 1건.
  - `backend/.../batch/step/YoloLabelPersister.java` 에는 음수·유한성 검증이 **없다**(길이 홀수만 상위 스텝에서 검증 — `YoloAutolabelStepTest:195`). 즉 **같은 외부 응답을 배치는 신뢰하고 온라인만 거부**하는 정책 분기가 존재한다.
- **재현/확인 경로**:
  ```bash
  RT=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"role":"WORKER","channel":"INTERNAL","userNo":"2001"}' -o /tmp/t.json; \
    grep -o '"token":"[^"]*"' /tmp/t.json | sed 's/"token":"//;s/"$//')
  for s in 446 447 448 449 450; do printf "$s "; curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST http://localhost:18081/api/v1/frames/$s/autolabel \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{}'; done
  # -> 446:400 447:200 448:400 449:400 450:400
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT s.raw_sn, count(*) FROM public.ls_data_lbl l JOIN public.ls_data_src s ON s.src_sn=l.src_sn
     WHERE l.point_cn ~ '-[0-9]' GROUP BY 1 ORDER BY 1;"   -- 배치가 저장한 음수 좌표 라벨
  ```
- **영향**: SFR-08 라벨링 편의(AI 탐지)의 실사용 불가에 가까운 가용성 저하. 사용자에게 노출되는 메시지가 `"YOLO 응답 좌표는 0 이상이어야 합니다."` 라는 내부 검증 문구여서 원인 파악도 어렵다. 보안 방향은 fail-closed(과다 거부)이므로 데이터 유출·오염 위험은 없으나, **배치 DB 에는 이미 음수 좌표가 적재되어 있어 "온라인만 엄격" 이라는 비대칭이 검증 목적을 달성하지 못한다**(CWE-20 과잉 적용 + 정책 일관성 결함).
- **수정 방향(제안)**: ① 이미지 경계 기준 **clamp(0 ≤ x ≤ imgWidth, 0 ≤ y ≤ imgHeight)** 후 반환 — 모델의 경계 초과는 정상 출력이며 캔버스도 클램프 전제(`UNCERTAINTIES #10`)다. ② clamp 를 택하지 않는다면 최소한 **음수는 부분 스킵(해당 검출만 제외)** 으로 완화하고 all-or-nothing 은 NaN/Infinity·좌표 개수 위반에만 적용. ③ 어느 쪽이든 **배치(`YoloLabelPersister`)와 온라인(`AutolabelOnlineService.validateBbox`)의 좌표 정규화 규칙을 단일 유틸로 통일**해 비대칭을 제거. ④ 상한(x2>imgWidth) 미검증도 함께 정리(현재 `1279.68`, `721.30` 등 초과값이 무검증 통과).

---

## 부수 관찰 (결함 아님 / 참고)

- **역할 검증이 토큰 클레임 단독이 아님**: `role=REVIEWER` 토큰이라도 `userNo=2001`(DB상 WORKER)이면 `/v1/manage/labels` POST 가 **403**. `userNo=1001` 이어야 201. A 클러스터에서 지적된 "권한 자가부여" 유형의 우회가 이 구간에는 **없다**는 반증 근거.
- **`AutolabelResponse.savedCount`**: 미저장 경로인데 필드명이 `savedCount` 이고 `detectedCount` 와 동일값으로 노출된다(의도된 하위호환 — `AutolabelOnlineServiceTest:359`). FE 가 "저장됨"으로 오해할 여지가 있으나 계약상 명시됨.
- **`GET /v1/deident-reports?status=resolved`(소문자) → 400**: 서비스(`normalizeStatus:265`)는 `toUpperCase()` 로 관용하지만 컨트롤러 `@Pattern` 이 먼저 대문자만 허용해 거부한다. 방어 방향(엄격)이라 무해하나 서비스 javadoc 과 실동작이 다르다.
- **`X-HTTP-Method-Override` 미지원**: Spring Boot 3.x 기본값으로 `HiddenHttpMethodFilter`/override 필터가 비활성 — 메서드 우회 표면 없음.
- **PORTAL_USER 가 라벨 마스터·detect-candidates 조회 가능**: 설계상 허용(`LabelMasterController` javadoc). 응답에 `dtctTypeCd`(COCO 내부 매핑)가 포함되나 비활성 라벨은 제외되며 민감정보 아님.
- **`autolabel.polygon.max-boxes=20` vs 실데이터 최대 검출 19건/프레임**: TC-72 상한 분기가 운영 데이터에서 사실상 미발화. 상한이 유효한지(너무 높은지) 운영 판단 참고.

## 근거 라인 드리프트

**0건.** 본 구간 근거 `file:line` 45개 참조를 HEAD 소스와 전수 대조 — `LabelMasterController.java`, `LabelMasterService.java`, `LabelMasterRequest.java`, `LsLabelRepository.java`, `AutolabelOnlineService.java`, `AutolabelRequest.java`, `DeidentReportService.java`, `DeidentReportRequest.java` 전부 일치.
(참고: TC-LABEL-41 의 `:75-78` 은 실제 중복검사 라인이 `:76-78`, TC-LABEL-55 의 `:80-84` 는 javadoc 80-83 + 메서드 84 — 둘 다 범위 내라 드리프트로 계수하지 않음.)

## 요약

- 총 **45건** / PASS **44** / FAIL **0** / PARTIAL **1**(TC-LABEL-60) / BLOCKED **0** / N/A **0** / 확인필요 **0**
  - (표에는 전제로 실행한 범위 밖 TC-LABEL-40~51 을 함께 기록했으나 집계에서는 제외)
  - PASS 44 중 **실동작 직접 확인 35건**, 정적+기존 테스트 커버 9건(외부 응답 주입·라벨 파괴·상한 초과 검출·ai-server 중단이 필요해 live 미실행)
- 근거 라인 드리프트: **0건** / **self-fill 결함: 0건**
  - self-fill 반증: 오토라벨 좌표는 전부 실 ai-server(:19300) 응답이며 좌표값이 프레임마다 상이(하드코딩 아님). 비식별 resolve 는 **파일 mtime 이 실제로 갱신되기 전에는 200 을 절대 내주지 않음**(A/B 실측) — 외부 산출물 없이 값을 자체 생성하는 경로 미발견.
- **인가·게이팅 우회 경로 미발견**: 마스터 쓰기(WORKER/PORTAL/미인증/PATCH/method-override 5종), Mass assignment(useYn·labelId·regId), 오토라벨 IDOR·작업락·화이트리스트 우회 4종, 신고/resolve IDOR 3종, status SQLi — **전부 fail-closed 차단**.
- **온라인 오토라벨 저장 정책 준수 확인**: BBOX·POLYGON 양 경로 모두 호출 전후 `ls_data_lbl` count·`max(reg_dt)` 불변 → 자동 저장(기존 라벨 덮어씀·이력 누락) **없음**.
- 신규 이슈 **1건**: HIGH 1 — [C-ISSUE-41] 실모델 음수 좌표로 온라인 오토라벨 80% 400 + 배치/온라인 검증 정책 비대칭.
- **B-part4 와 중복된 신고 케이스 (14건, 판정 일치 여부)**:

| C-2 ID | B-part4 ID | B-part4 판정 | 이번 판정 | 일치 |
|---|---|:--:|:--:|:--:|
| TC-LABEL-90 | TC-DEID-034 | PASS | PASS | ✔ |
| TC-LABEL-91 | TC-DEID-030 | PASS | PASS | ✔ |
| TC-LABEL-93 | TC-DEID-031 | PASS | PASS | ✔ |
| TC-LABEL-94 | TC-DEID-033 | PASS | PASS | ✔ |
| TC-LABEL-95 | TC-DEID-032 | PASS(정적) | PASS(정적) | ✔ |
| TC-LABEL-96 | TC-DEID-036 | PASS | PASS(병렬 실측 보강) | ✔ |
| **TC-LABEL-97** | **TC-DEID-035** | **BLOCKED**(stale jar) | **PASS**(실동작) | ✔ 승격 — 환경 결함 해소로 모순 아님 |
| TC-LABEL-98 | TC-DEID-037 | PASS(정적) | PASS(정적+음성 실동작) | ✔ |
| TC-LABEL-99 | TC-DEID-038 | PASS | PASS | ✔ |
| TC-LABEL-100 | TC-DEID-039 | PASS | PASS | ✔ |
| TC-LABEL-101 | TC-DEID-040 | PASS | PASS | ✔ |
| TC-LABEL-102 | TC-DEID-050 | PASS | PASS | ✔ |
| TC-LABEL-103 | TC-DEID-041 | PASS | PASS | ✔ |
| TC-LABEL-104 | TC-DEID-042/044 | PASS | PASS | ✔ |
| TC-LABEL-105 | TC-DEID-045 | PASS | PASS(통과/거부 양방 실측) | ✔ |
| **TC-LABEL-106** | **TC-DEID-043** | **PASS(정적/테스트)** | **PASS(실동작)** | ✔ 강화 — 모순 없음 |
| TC-LABEL-107 | TC-DEID-046 | PASS | PASS | ✔ |
| TC-LABEL-108/109 | TC-DEID-048 | PASS | PASS | ✔ |

> **모순 0건.** B-part4 대비 달라진 두 건(TC-LABEL-97·106)은 모두 **판정 하향이 아니라 상향**이며, 원인은 ①백엔드 재빌드로 V130 반영(97) ②라벨 0건 영상 + 파일 mtime 갱신 시뮬레이션으로 성공 경로 실행 가능(106) — B-part4 가 기록한 제약이 이번 회차에 해소된 결과다.

## C-3. TC-SAM2 — SAM2 분할 / 트랙 프록시

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-SAM2-01 | segment 정상 | PASS | [실동작] REVIEWER 토큰으로 srcSn=446(rawSn=26, 실제 프레임) 클릭 프롬프트 실호출 → 200, 실 폴리곤 28점 + score=0.6892(mock 아님, ai-server AI_MOCK_MODE=false). Sam2SegmentService.java:80-149 로직과 일치 | Sam2SegmentServiceTest#클릭_프롬프트로_폴리곤과_신뢰도가_반환됨 | |
| TC-SAM2-02 | segment path/body srcSn 불일치 | PASS | [실동작] path=446,body=447 → 400 "path 의 srcSn 과 body 의 srcSn 이 다릅니다" 확인. LabelController.java:163-171 일치 | 없음(컨트롤러 로직, 서비스 테스트 아님) | |
| TC-SAM2-03 | points/box 배타 위반(둘 다) | PASS | [실동작] 둘 다 제공 → 400 "exactlyOnePrompt" 확인 | Sam2SegmentRequestValidationTest#동시_전달시_400 | |
| TC-SAM2-04 | points/box 배타 위반(둘 다 빈) | PASS | [실동작] srcSn만 제공 → 400 확인 | Sam2SegmentRequestValidationTest#포인트와_박스_모두_없으면_400 | |
| TC-SAM2-05 | segment IDOR | PASS | [실동작] WORKER(userNo=2002, rawSn=26 미배정) 토큰으로 srcSn=446 요청 → 403 "본인에게 배정되지 않은 영상입니다" 확인(assignment 테이블 조회로 2002 미배정 사전 확인) | Sam2SegmentServiceTest#타_배정_프레임_segment_요청시_403 | |
| TC-SAM2-06 | segment 경로순회 차단 | PASS | [정적] resolveSafe(baseDir, relativePath).normalize()+startsWith 검증 Sam2SegmentService.java:175-184 일치. srcFilePathNm 은 DB 값(요청 바디에 경로 필드 없음)이라 일반 HTTP 요청으로 ".." 를 직접 주입할 경로는 없음 — DB 오염/legacy 데이터에 대한 심층방어로 확인 | 없음(전용 유닛 미확인, 코드 존재) | 실제 API 표면으로는 트리거 불가한 방어선(정상) |
| TC-SAM2-07 | segment 이미지 미존재 | PASS | [실동작] 임시 ls_data_src 행(raw_sn=26,frm_no=9999, 존재하지 않는 파일 경로) 삽입 후 요청 → 404 "이미지 파일을 찾을 수 없습니다" 확인, 테스트 후 행 삭제(원복 완료) | 없음(Sam2SegmentServiceTest 에 전용 케이스 미확인) | 쓰기 수반 실동작 — 완료 후 정리함 |
| TC-SAM2-08 | segment 이미지 크기 초과 | PASS | [정적] maxImageBytes(기본 20MB) 초과 시 413 Sam2SegmentService.java:94-98 일치 | Sam2SegmentServiceTest#이미지_크기_상한_초과시_400 | 실동작 미실시(대용량 프레임 파일 준비 필요, 위험 대비 효익 낮음) |
| TC-SAM2-09 | segment mock→빈 폴리곤+메시지(자동적용 차단) | PASS | [정적]+[실동작 간접] Sam2SegmentService.java:118-123(mock=true→empty), LabelController.java:172-176(empty→MOCK_UNAVAILABLE_MESSAGE 세팅). FE OverlayLayer.tsx:406-412 `res.polygon.length===0` 시 `onMockWarning` 콜백 후 `return`(자동 적용 차단, 프리뷰도 해제) 확인. 현재 ai-server 는 AI_MOCK_MODE=false(실모델)라 실제 mock 경로는 강제 재현 안 함(환경 변경 없이 검증) | Sam2SegmentMockMessageWiringTest, Sam2SegmentServiceTest#Sam2Segment_내부mock이면_빈폴리곤과_안내메시지_반환 | mock=true 강제 재현은 환경설정 변경(AI_MOCK_MODE=true+재기동) 필요해 미실시 — 코드경로는 양방향(BE 판정→FE 차단) 모두 확인됨 |
| TC-SAM2-10 | segment 폴리곤 정점<3 | PASS | [정적] validatePolygon polygon.size()<3 → EXTERNAL_API_ERROR(502) Sam2SegmentService.java:156-159 일치 | Sam2SegmentServiceTest#3점_미만_폴리곤_응답시_오류 | 실 ai-server 가 3점 미만 응답하도록 강제 불가(정상 모델은 항상 폐곡선 반환) |
| TC-SAM2-11 | segment 좌표 경계초과 | PASS | [정적] validatePolygon 각 좌표 0≤x≤imgWidth, 0≤y≤imgHeight 미충족 시 502 Sam2SegmentService.java:160-172 일치. 실호출(TC-SAM2-01 등) 응답 좌표는 항상 상한 이내였음(모델이 정상 범위로 반환) | Sam2SegmentServiceTest#응답_폴리곤_좌표_이미지경계_초과시_오류 | |
| TC-SAM2-12 | segment simplifyTolerance 범위 | PASS | [실동작] simplifyTolerance=60 → 400 "경계 세밀함은 50.0 이하여야 합니다" 확인 | Sam2SegmentRequestValidationTest#simplifyTolerance_범위밖60_요청시_400 | |
| TC-SAM2-13 | segment 단순화 3점 미만→원본유지 | PASS | [정적] 단순화 결과<3점이면 aiRes.polygon() 원본 사용 Sam2SegmentService.java:140-143 일치 | Sam2SegmentServiceTest#AI분할_simplify_결과가_3점미만이면_원본폴리곤유지 | |
| TC-SAM2-14 | track 정상 POLYGON | PASS | [실동작] srcSn=446 시작, nextSrcSns=[447,448] 실호출 → 200, 프레임별 실 폴리곤(447: 7점 score=0.768, 448: 6점 score=0.900) 반환, DB 미저장 확인(ls_data_lbl 카운트 불변) | Sam2TrackServiceTest#배정된_WORKER는_sam2_track_정상동작하되_미저장, #추적은_DB에_저장하지_않고_좌표만_반환한다 | |
| TC-SAM2-15 | track path/body srcSn 불일치 | PASS | [실동작] path=446,body=447 → 400 확인. LabelController.java:137-142 일치 | 없음(컨트롤러 로직) | |
| TC-SAM2-16 | track nextSrcSns 50 초과(경계) | PASS | [실동작] nextSrcSns 51개 → 400 "size must be between 0 and 50" 확인. FE `SAM2_TRACK_CHUNK_SIZE`=50 청크 분할로 BE 계약과 정합(api.ts:596-605, 과거 "추적실패" 원인이던 무제한 전송 버그는 FE 청크 로직으로 해소됨 확인) | Sam2TrackRequest 값 객체 — 전용 유닛 미확인, DTO @Size(max=50) 검증 | 메모리(sam2-track-nextsrcsns-contract-bug) 지적 계약 불일치는 FE 청크 로직으로 해소되어 현재 재현 안 됨 |
| TC-SAM2-17 | track nextSrcSns 빈 | PASS | [실동작] nextSrcSns=[] → 400 "must not be empty" 확인 | 없음(DTO @NotEmpty) | |
| TC-SAM2-18 | track prevPolygon <3점 | PASS | [실동작] 2점 → 400 "size must be between 3 and 1000" 확인 | Sam2TrackServiceTest#sam2_track_prevPolygon_검증실패시_400(서비스단), DTO @Size(min=3) | |
| TC-SAM2-19 | track prevPolygon >1000점 | PASS | [실동작] 1001점 → 400 "size must be between 3 and 1000" 확인 | 없음(DTO @Size(max=1000)) | |
| TC-SAM2-20 | track trackId 64자 초과 | PASS | [실동작] 65자 trackId → 400 "size must be between 0 and 64" 확인 | 없음(DTO @Size(max=64)) | |
| TC-SAM2-21 | track IDOR 시작+후속 각각 | PASS | [실동작] ① WORKER(2002, rawSn=26 미배정) 시작프레임 요청 → 403. ② WORKER(2001, rawSn=26 배정됨이나 nextSrcSns 에 미배정 rawSn=5 프레임(srcSn=2) 포함) → 403 "본인에게 배정되지 않은 영상입니다"(후속 프레임도 개별 IDOR 적용 확인). Sam2TrackService.java:86-87 일치 | Sam2TrackServiceTest#미배정_WORKER가_sam2_track_호출시_403_FORBIDDEN_ai미호출 | 후속 프레임 개별 IDOR 는 실동작으로 별도 확인(시작만 배정된 케이스) |
| TC-SAM2-22 | track 후속 프레임 미존재 | PASS | [실동작] nextSrcSns=[999999] → 404 "프레임을 찾을 수 없습니다" 확인 | Sam2TrackServiceTest#sam2_track_후속프레임없으면_404 | 실제로는 LabelAccessGuard.verifyAccess(nextSrcSn,..)(87행)의 findById 가 먼저 404 를 던짐 — Sam2TrackService.java:89-90 의 "후속 프레임을 찾을 수 없습니다: " 메시지 분기는 이 경로에서는 도달 안 함(가드가 선점, 결과 404 는 동일하여 기능상 문제 없음) |
| TC-SAM2-23 | track ai 응답 좌표 검증 | PASS | [실동작+정적] validatePolygon 은 요청 prevPolygon 과 ai 응답 polygon 에 동일 로직 적용(Sam2TrackService.java:68,116,185-201). 실동작으로 prevPolygon=[[-5,1],...] → 400 "prevPolygon 좌표는 유한한 0 이상의 수여야 합니다" 확인(공유 검증함수이므로 ai 응답 경로도 동일 방어 확인됨) | Sam2TrackServiceTest#sam2_track_ai응답폴리곤_검증실패시_400 | |
| TC-SAM2-24 | track BBOX 외접박스 산출 | PASS | [실동작] shape="BBOX" 요청 → 200, points=[[296.0,0.0],[361.0,69.0]](외접 [[minX,minY],[maxX,maxY]] 형식) 확인. Sam2TrackService.java:168-182 일치 | Sam2TrackServiceTest#추적_shape가_박스면_폴리곤_외접bbox로_반환하고_선택라벨을_부여한다 | |
| TC-SAM2-25 | track 퇴화 bbox 프레임 스킵 | PASS | [정적] (maxX-minX)<1px 또는 (maxY-minY)<1px 이면 null→해당 프레임만 continue Sam2TrackService.java:126-131 일치 | Sam2TrackServiceTest#추적_박스형태_퇴화폴리곤은_해당프레임만_스킵한다 | 실모델이 퇴화 폴리곤을 반환하도록 강제 불가(정상 케이스에선 재현 안 됨) |
| TC-SAM2-26 | track shape 기본 POLYGON | PASS | [실동작] shape 필드 생략 요청(TC-SAM2-14) → 응답 shapeType="POLYGON" 확인. Sam2TrackRequest.java:37-39 일치. FE 측(ObjectAttributePanel.tsx:26-30, `shapeToDetectType(target.shape) ?? track.shape`)도 선택 객체 형태 우선 정책으로 구현되어 "shape 미지정→BE 기본값 POLYGON 으로 새는" 회귀가 재발하지 않음 확인 | Sam2TrackServiceTest#추적_shape미지정이면_POLYGON_기본 | |
| TC-SAM2-27 | track ai 호출 실패 502 | PASS | [정적] aiServerClient.track 예외 시 EXTERNAL_API_ERROR Sam2TrackService.java:103-111 일치 | Sam2TrackServiceTest#sam2_track_ai호출실패시_502 | |
| TC-SAM2-28 | track trackId 로그 sanitize | PASS | [실동작] trackId="evil\r\nFAKE LOG LINE INJECTED" 로 실제 track 호출 → 응답은 200(정상 처리, CRLF 는 API 응답 바디에는 그대로 echo 되나 이는 로그가 아님). docker logs 실측(`od -c`) 결과 로그 라인은 "trackId=evilFAKE LOG LINE INJECTED"(CR/LF 완전 제거)로 출력되어 LogSanitizer.sanitize 실동작 확인(CWE-117 방어 실증) | 없음(전용 유닛 미확인, 실동작으로 대체 확인) | |

## C-4. TC-KEYPOINT — 17-keypoint COCO SKELETON

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-KEYPOINT-01 | SKELETON 정상 저장 | PASS | [실동작] 임시 프레임(srcSn=469)에 17개 [x,y,2] PUT /labels → 200, 응답 points 17개 삼중값 그대로 반환·DB point_cn 확인. 테스트 후 라벨+임시프레임 삭제(원복 완료) | LabelServiceKeypointTest#SKELETON_17개_삼중값_저장후_조회시_동일값_반환 | 쓰기 수반 실동작 — 완료 후 정리함 |
| TC-KEYPOINT-02 | 개수≠17 | PASS | [실동작] 16개 요청 → 400 "SKELETON 키포인트는 정확히 17 개여야 합니다" 확인 | LabelServiceKeypointTest#SKELETON_포인트_16개면_400 | |
| TC-KEYPOINT-03 | 삼중값 크기≠3 | PASS | [실동작] 1개 원소만 [x,y](2-tuple) → 400 "키포인트는 [x, y, v] 형태여야 합니다" 확인 | LabelServiceKeypointTest#SKELETON_원소가_2튜플이면_400 | |
| TC-KEYPOINT-04 | v null 원소(NPE 방어) | PASS | [실동작] [10,20,null] 포함 → 400 "키포인트 좌표에 null 원소가 있습니다"(500 NPE 아님, fail-secure 확인) | LabelServiceKeypointTest#SKELETON_원소에_null이면_400 | |
| TC-KEYPOINT-05 | v 범위 밖 | PASS | [실동작] v=3 → 400 "가시성 v 는 0/1/2 중 하나여야 합니다" 확인 | LabelServiceKeypointTest#SKELETON_가시성_v가_3이면_400 | |
| TC-KEYPOINT-06 | v=0 좌표 0 허용(경계) | PASS | [실동작] 17개 전부 [0,0,0] → 200 통과 확인 | LabelServiceKeypointTest#SKELETON_v0_미표기_점_x_y_0_허용 | |
| TC-KEYPOINT-07 | 음수 좌표(v>0) | PASS | [실동작] x=-1,v=2 → 400 "좌표는 0 이상이어야 합니다 (x=-1.0, y=20.0)" 확인 | 없음(LabelServiceKeypointTest 에 음수 전용 케이스 미확인, 06 케이스만 존재) | |
| TC-KEYPOINT-08 | KeypointSerializer toJson | PASS | [정적] toJson 결정적 직렬화(순서 보존, "[[1.5,2.5,2]]" 등) KeypointSerializer.java:46-60 일치 | KeypointSerializerTest#roundTrip, #toJsonTripletFormat | |
| TC-KEYPOINT-09 | fromJson 배열 아님 | PASS | [정적] root.isArray() 미충족 시 IllegalArgumentException KeypointSerializer.java:80-82 일치 | 없음(전용 테스트 미확인) | |
| TC-KEYPOINT-10 | fromJson 삼중값 크기위반 | PASS | [정적] triplet.size()!=3 시 IAE KeypointSerializer.java:84-87 일치 | KeypointSerializerTest#rejectsTwoTuple | |
| TC-KEYPOINT-11 | fromJson 숫자아님 | PASS | [정적] x/y/v 중 isNumber() 미충족 시 IAE KeypointSerializer.java:91-93 일치 | 없음(전용 테스트 미확인) | |
| TC-KEYPOINT-12 | fromJson 빈/[] | PASS | [정적] json.isBlank() 또는 "[]" → List.of() 즉시 반환 KeypointSerializer.java:70-72 일치 | KeypointSerializerTest#emptyInputs | |
| TC-KEYPOINT-13 | SKELETON R7 비교 폴백 | PASS | [정적] pointsEqual()→normalizePoints() 가 LabelPointSerializer.fromJson(2-튜플) 예외 시 raw List<List<Double>> 파싱으로 폴백 LabelService.java:491-508,455-484 일치. SKELETON 은 결정적 직렬화(KeypointSerializer)라 대부분 fast-path(문자열 동일 비교)로 처리되고, 재직렬화 표현차 발생 시에만 이 폴백 경로를 탐 | 없음(SKELETON 전용 R7 폴백 단위테스트 미확인, LabelServiceFullReplaceIntegrationTest 는 R7 언급하나 SKELETON 케이스 미포함) | |

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

전건 PASS — FAIL/PARTIAL/확인필요 항목 없음. 아래는 반증 시도 중 발견한 참고 관찰사항(카탈로그 결함 아님, 테이블 판정에 영향 없음).

### [C-ISSUE-61] (참고) SAM2 세그먼트 프롬프트 입력좌표 이미지 경계 사전검증 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: 반증 포인트 "프롬프트 좌표가 이미지 밖"에 대한 서버측 방어 확인 목적
- **현재 동작(관찰)**: `Sam2SegmentRequest.points/box` 및 `Sam2TrackRequest.prevPolygon` 은 요청 시점에 이미지 실측 width/height 상한 검증이 없다(오직 **응답** 폴리곤만 `Sam2SegmentService.validatePolygon`(160-172행)으로 이미지 경계 검증됨). 실동작으로 `points:[[99999,99999]]`(rawSn=26 프레임 실측 폭 약 1280px 대비 크게 초과)을 srcSn=446 에 요청한 결과 200 OK + 정상 형태의 폴리곤(score 0.9466)이 반환됨 — ai-server/모델이 내부적으로 좌표를 관대하게 처리한 것으로 보이나, BE 계약상 사전 차단은 없음
- **재현/확인 경로**: `curl -X POST http://127.0.0.1:18081/api/v1/frames/446/sam2-segment -H "Authorization: Bearer $TOKEN" -d '{"srcSn":446,"points":[[99999,99999]]}'`
- **영향**: 보안 취약점 아님(응답은 여전히 사후 검증됨, path traversal/injection 경로 없음). 잘못된 프롬프트 좌표를 외부 ai-server 로 그대로 전달하는 낭비성 호출/예측불가 결과 가능성 정도의 견고성 갭
- **수정 방향(제안)**: Sam2SegmentService 진입 시 이미지 실측 해상도 확보 이후(이미 100행에서 확보) points/box 좌표도 0≤x≤imgWidth, 0≤y≤imgHeight 로 사전 400 검증 추가 검토(구현 금지 — 제안만)

## 요약
- 총 41건 (TC-SAM2 28 + TC-KEYPOINT 13) / PASS 41 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0
- 근거 라인 드리프트: 0건 / self-fill 결함: 0건
- 실동작(ai-server 실추론·실 DB) 기반 검증: SAM2 segment 9건, SAM2 track 12건, 키포인트 저장 7건(직접 API 호출) — 나머지는 정적 코드 대조 + 기존 유닛/통합 테스트로 보강
- 채널 격리 실동작 확인: PORTAL_USER 토큰으로 내부 `/v1/frames/{id}/sam2-segment` 호출 시 403 확인(UNCERTAINTIES #1 정책 위반 없음 — 포털 전용 경로는 별도 `/v1/portal/frames/**`)
- 쓰기 수반 실동작 2건(TC-SAM2-07 임시프레임, TC-KEYPOINT-01/02~07 임시프레임+라벨) — 테스트 후 전량 원복 확인(rawSn=26 최종 상태: ls_data_src 16건/ls_data_lbl 131건, 검증 전과 동일)

## C-5. TC-TRACK — 트랙 보간 / 좌표변환 / MASK↔RLE
| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-TRACK-01 | BBOX 보간 정상 | PASS | [정적] batch/interpolation/TrackInterpolator.java:48-83 일치(file:line 정확). [실동작] rawSn=26 실파이프라인이 트랙 보간 5건 실행·저장 완료(B-part3.md TC-BATCH-140 참조) | TrackInterpolatorTest#두_키프레임_5_10_사이_선형_보간(수치검산: t=0.4→(40,40,140,140) 일치) | 근거 file:line 은 `batch/interpolation/TrackInterpolator.java` 를 가리킴(정확). 동명 `common/util/TrackInterpolator.java` 는 미사용 dead code — C-ISSUE-82 |
| TC-TRACK-02 | 빈 키프레임 | PASS | [정적] :53-55 일치 | TrackInterpolatorTest#emptyKeyframesProduceEmptyResult | |
| TC-TRACK-03 | 단일 키프레임 | PASS | [정적] :57-64 일치 | TrackInterpolatorTest#singleKeyframeReturnsOnlyThatFrame | |
| TC-TRACK-04 | outside 마커 종료 | PASS | [정적] :59-62 일치 | TrackInterpolatorTest#outsideKeyframeStopsProcessing | |
| TC-TRACK-05 | 다음이 outside | PASS | [정적] :66-70 일치 | TrackInterpolatorTest#nextOutsideStoresCurrentOnlyAndStops | |
| TC-TRACK-06 | 마지막 이후 propagate 없음 | PASS | [정적] :36-44 일치 | TrackInterpolatorTest#noPropagateAfterLastKeyframe | |
| TC-TRACK-07 | 동일 frame 중복 뒤값 우선 | PASS | [정적] :71-75 일치 | TrackInterpolatorTest#duplicateFrameKeyframeLastWins | |
| TC-TRACK-08 | totalFrames 음수 | PASS | [정적] :50-52 일치 | TrackInterpolatorTest#negativeTotalFramesRejected | |
| TC-TRACK-09 | keyframes null | PASS | [정적] :49 일치 | TrackInterpolatorTest#nullKeyframesRejected | |
| TC-TRACK-10 | 폴리쉐이프 보간 정점수 다름 | PASS | [정적] :100-139 일치, PolyshapeMatcher.match() 대응쌍 1회 계산 후 재사용 확인 | TrackInterpolatorPolyshapeTest#polygonVariableVertexCount(3점↔4점→중간 4점, 좌표 유한) | |
| TC-TRACK-11 | 폴리쉐이프 closed/open | PASS | [정적] :100-127 일치 | TrackInterpolatorPolyshapeTest#polygonSameVertexCountInterpolated(closed) + #polylineAnchorInterpolated·#polylineReversedDirectionInterpolated(open, 방향반전 포함) | |
| TC-TRACK-12 | rotate 중심 기준 | PASS | [정적] CoordinateTransformer.java:21-41 일치 | CoordinateTransformerTest#rotate90Verified(수치검산:(10,0)+90도→(0,10) 일치)+#rotateRoundtripIdentity | |
| TC-TRACK-13 | rotate points/center null | PASS | [정적] :22-27 IAE 가드 확인 | 단위테스트 미커버(null 케이스) — 코드 리딩으로만 확인 | C-ISSUE-81 |
| TC-TRACK-14 | scale/translate | PASS | [정적] :44-65 일치 | CoordinateTransformerTest#scaleRoundtripIdentity·#translateRoundtripIdentity | |
| TC-TRACK-15 | maskToRle 첫픽셀 on | PASS | [정적] MaskRleConverter.java:51-59 일치 | MaskRleConverterTest#leadingOnInsertsZeroRun(수치검산:[1,1,0]→[0,2,1] 일치) | |
| TC-TRACK-16 | maskToRle 빈/null | PASS | [정적] :30-32 일치 | emptyMaskHandled 은 all-false 5x5(내용빈)만 커버, **null/길이0 배열 자체는 미테스트** | C-ISSUE-81 |
| TC-TRACK-17 | maskToRle >1M 픽셀(DoS) | PASS | [정적] :36-40 일치 | MaskRleConverterTest#rejectsExcessivelyLargeMask(1001x1001) | |
| TC-TRACK-18 | maskToRle 비직사각형 | PASS | [정적] :44-47 일치(row.length!=w 시 IAE) | 단위테스트 미커버 | C-ISSUE-81 |
| TC-TRACK-19 | rleToMask width/height≤0 | PASS | [정적] :93-95 일치(수치검산: width=0→즉시 IAE) | 단위테스트 미커버 | C-ISSUE-81 |
| TC-TRACK-20 | rleToMask >1M | PASS | [정적] :88-92 일치 | **단위테스트 미커버 — P1 보안(CWE-770)인데 회귀방지 테스트 없음** | C-ISSUE-81 |
| TC-TRACK-21 | rleToMask 길이합 초과 | PASS | [정적] :102-104 일치(수치검산: rle=[10,10],w=5,h=3,total=15→16번째 픽셀 idx=15에서 IAE) | 단위테스트 미커버 | C-ISSUE-81 |
| TC-TRACK-22 | rleToMask null | PASS | [정적] :85-87 일치 | 단위테스트 미커버 | C-ISSUE-81 |
| TC-TRACK-23 | round-trip mask→rle→mask | PASS | [정적] :30-113 알고리즘 확인 | MaskRleConverterTest#rleRoundtripIntegrity1000(랜덤 1000회 완전일치) | |
| TC-TRACK-24 | yolo-track path/body 불일치 | PASS | [실동작] `POST /api/v1/frames/446/yolo-track` body srcSn=447 → **400 INVALID_INPUT** "path 의 srcSn 과 body 의 srcSn 이 다릅니다." (LabelController.java:198-203 일치) | | |
| TC-TRACK-25 | yolo-track nextSrcSns 50 초과 | PASS | [실동작] nextSrcSns 51개 전송 → **400** "nextSrcSns: size must be between 0 and 50"(YoloTrackRequest.java:23 일치) | | |
| TC-TRACK-26 | yolo-track nextSrcSns 빈 | PASS | [실동작] nextSrcSns:[] 전송 → **400** "nextSrcSns: must not be empty" | | |

## C-6. TC-PRESET — 라벨 프리셋
| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-PRESET-01 | 생성 정상(labelId 기반) | PASS | [실동작] `POST /api/v1/manage/presets` labelIds:[1,2] → 201, 응답 `code:null`(문자열 미저장), labelName/labelType 마스터 join 파생 확인 | PresetServiceTest#create_정상_케이스는_eventTypeCd가_전달되어_저장된다 | 검증후 삭제로 정리 완료 |
| TC-PRESET-02 | 생성 이벤트타입 무효 | PASS | [실동작] eventTypeCd:"NOT_A_REAL_EVENT" → **400** "지원하지 않는 이벤트 타입입니다" | PresetServiceTest#프리셋_저장시_유효_categoryKey면_통과_미유효면_400 | |
| TC-PRESET-03 | 생성 이벤트타입 빈값 허용 | PASS | [실동작] eventTypeCd:"" → 201, 응답 eventTypeCd:null(정규화) | PresetServiceTest#eventTypeCd_빈_문자열은_null_로_정규화되어_저장된다 | |
| TC-PRESET-04 | 생성 이름 중복 | PASS | [실동작] 기존 이름 재사용 → **409** "이미 사용 중인 프리셋 이름입니다." | PresetServiceTest#동일_이름_프리셋은_CONFLICT_변경_없음 | |
| TC-PRESET-05 | 생성 labelId 미존재/비활성 | PASS | [실동작] labelId=999999 → **400** "존재하지 않거나 비활성 라벨입니다: labelId=999999" | PresetServiceTest#마스터에_없는_labelId로_코드추가시_400 | |
| TC-PRESET-06 | 생성 이벤트 유니크 경합 | PASS | [정적+실동작] DB `uk_ls_label_preset_evnt`(evnt_type_cd) 실제 존재 확인(psql) + saveAndFlush→DataIntegrityViolationException→CONFLICT 변환(PresetService.java:174-181). 실제 동시 요청 레이스는 재현 안 함(단일 세션 순차 검증) | PresetServiceTest#동일_이벤트가_다른_프리셋에_이미_매핑되어_있으면_CONFLICT | 진짜 동시성 경합은 미재현(순차 요청으로 동일 제약 위반만 확인) |
| TC-PRESET-07 | 수정 미존재 | PASS | [실동작] `PUT /manage/presets/999999` → **404** "프리셋을 찾을 수 없습니다." | PresetServiceTest#update_존재하지않는_프리셋이면_NOT_FOUND | |
| TC-PRESET-08 | 수정 이름 중복(자기제외) | PASS | [실동작] 타 프리셋명으로 수정 시도 → **409** | PresetServiceTest 유사 케이스 | |
| TC-PRESET-09 | 수정 이벤트 유니크 위반 | PASS | [실동작] 이미 매핑된 eventTypeCd 로 수정 → **409** "이미 다른 프리셋에 매핑된 이벤트입니다" | PresetServiceTest#update_시_이벤트_UNIQUE_위반은_CONFLICT_로_변환 | |
| TC-PRESET-10 | 삭제 멱등 | PASS | [실동작] `DELETE /manage/presets/999999` → **204**(no-op) | | |
| TC-PRESET-11 | 복제 이름 시퀀스 | PASS | [실동작] "교통사고 표준 프리셋" 복제 → "교통사고 표준 프리셋 (복사본)" | | |
| TC-PRESET-12 | 복제 이벤트 미상속 | PASS | [실동작] 원본 eventTypeCd="030001" 인 프리셋 복제 → 복제본 eventTypeCd:null | PresetServiceTest#clone_은_eventTypeCd를_상속하지_않고_코드를_복사한다 | |
| TC-PRESET-13 | 복제 이름 50회 초과 실패 | PASS | [실동작] 동일 프리셋 51회 연속 clone 실행 → 1~50회 200, **51번째 409** "복제 이름 생성에 실패했습니다." (PresetService.java:184-196 CLONE_SUFFIX_MAX=50 정합) | 단위테스트 미커버(50회 루프 없음) — 본 회차에 실동작으로 직접 확증 | |
| TC-PRESET-14 | 코드뷰 미연결(labelId null) | PASS | [정적] :239-245 일치. 시드 DB엔 labelId=null 행 없어 실동작 미재현 | PresetServiceTest#labelId가_null인_미연결코드는_linked_false와_legacy명으로_노출된다 | |
| TC-PRESET-15 | 코드뷰 마스터 미존재/soft delete | PASS | [실동작] LS_LABEL.lbl_id=1 을 USE_YN='N' 으로 임시 변경 → 프리셋 응답 linked:false, labelType:null, labelName="PERSON"(legacy 코드 폴백), 오류 없음. **검증 후 USE_YN='Y' 로 원복 완료** | PresetServiceTest#list_labelId있지만_soft_delete된_마스터는_linked_false로_노출된다 | 원복 확인: `select use_yn from ls_label where lbl_id=1` → Y |
| TC-PRESET-16 | 코드뷰 형태 마스터 파생 | PASS | [실동작] LS_LABEL.lbl_id=2(car) 를 `lbl_nm='car_VERIFYTEST', lbl_type_cd='POLYGON'` 로 임시 변경 → 프리셋 응답이 즉시 labelName="car_VERIFYTEST", labelType="POLYGON", bboxEnabled:false→polygonEnabled:true 로 반영(스냅샷 아님, 실시간 join 확증). **검증 후 `lbl_nm='car', lbl_type_cd='BBOX'` 로 원복 완료** | PresetServiceTest#마스터_라벨명_변경후_프리셋조회시_변경된_라벨명이_반영된다 | 원복 확인: `select lbl_nm,lbl_type_cd from ls_label where lbl_id=2` → car/BBOX |
| TC-PRESET-17 | 부분유니크 labelId 중복 방지 | PASS | [실동작] 라이브 DB에서 `INSERT INTO ls_label_preset_code(preset_id,lbl_id,lbl_cd) VALUES(1,2,NULL)`(이미 존재) 직접 실행(트랜잭션 ROLLBACK) → **`duplicate key value violates unique constraint "uk_ls_label_preset_code_lblid"`** 실제 발생 확인 | LsLabelPresetCodeLabelIdUniqueIT#동일_프리셋에_같은_labelId_코드_중복INSERT는_부분유니크인덱스로_거부된다 | DB만 조회/트랜잭션 롤백, 실데이터 변경 없음 |
| TC-PRESET-18 | N+1 회피 배치 조회 | PASS | [정적] PresetService.java:220-236 `toViews()` 가 전체 preset 의 labelId 를 LinkedHashSet 으로 모아 `labelMasterService.findActiveByIds()` 1회 배치 조회 확인 | PresetServiceTest#프리셋_목록_조회시_코드_라벨_join이_N플러스1을_유발하지_않는다 | |

> **인가(부가 확인)**: WORKER 토큰으로 `GET /v1/manage/presets` 호출 → [실동작] **403 FORBIDDEN** "권한이 없습니다." 확인(REVIEWER 전용 정합, `/v1/manage/**` + `@PreAuthorize`). 별도 TC ID 없어 표에는 미포함.

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

### [C-ISSUE-81] TC-TRACK-13/16/18/19/20/21/22 — MaskRleConverter·CoordinateTransformer 경계/보안 가드 단위테스트 커버리지 0%
- **심각도**: MEDIUM (TC-TRACK-20은 CWE-770 DoS 방어 P1 케이스라 회귀방지 공백은 실질적 리스크)
- **기대 동작(기대효과)**: 순수 알고리즘 클래스의 모든 가드절(특히 P1/security 우선순위 케이스)은 단위테스트로 회귀 방지되어야 한다.
- **현재 동작(이슈 내용)**: 코드 자체는 전부 정확함(수치 검산으로 확인 완료 — 예: TC-TRACK-21 `rleToMask([10,10], w=5,h=3)`→total=15, 20>15이므로 idx=15 도달 시점에 IAE 발생함을 직접 계산해 확인). 그러나 `MaskRleConverterTest.java`에는 `rleToMask` 계열 예외 가드(width/height≤0, null, 길이합 초과, DoS 상한)에 대한 테스트가 **하나도 없음**(maskToRle 쪽 DoS만 테스트됨 — 비대칭). `CoordinateTransformerTest.java`도 `rotate(null, ...)`/`rotate(points, angle, null)` null 가드 테스트가 없음.
- **재현/확인 경로**: `grep -c "rleToMask" backend/src/test/java/kr/co/cudo/authoring/common/util/MaskRleConverterTest.java` (roundtrip 호출 외 예외 케이스 assert 없음 확인 가능)
- **영향**: 향후 리팩터링 시 이 가드들이 조용히 깨져도(예: 상한 체크 순서 변경, null 체크 누락) CI가 잡아내지 못함. 기능 결함은 아님(현재는 정상).
- **수정 방향(제안)**: `rleToMask` 4종 예외 케이스(null/width≤0/height≤0/DoS상한/길이합초과) + `rotate` null 가드 2종에 대한 단위테스트 추가.

### [C-ISSUE-82] batch/interpolation/TrackInterpolator.java 와 동명이인 common/util/TrackInterpolator.java — 미사용 dead code 잔존
- **심각도**: LOW (기능 영향 없음 — 순수 housekeeping)
- **기대 동작(기대효과)**: 동일 클래스명이 프로덕션 경로 밖에 중복 존재하면 향후 유지보수자가 잘못된 파일을 참조/수정할 위험이 있어, 사용하지 않는 초안은 정리되어야 한다.
- **현재 동작(이슈 내용)**: `kr.co.cudo.authoring.common.util.TrackInterpolator`(POLYGON은 `UnsupportedOperationException`, 회전보간 `interpolateRotation` 포함)는 어떤 프로덕션 코드에서도 import 되지 않음(`grep -rn "common.util.TrackInterpolator"` 결과 0건). 실제 사용처는 `TrackInterpolationStep`이 참조하는 `kr.co.cudo.authoring.batch.interpolation.TrackInterpolator`(POLYGON 완전 지원)뿐. 본 문서(C-5)의 file:line 근거는 후자와 정확히 일치하므로 검증 자체엔 영향 없음.
- **재현/확인 경로**: `grep -rn "import kr.co.cudo.authoring.common.util.TrackInterpolator" backend/src/main/java` (결과 없음)
- **영향**: 없음(dead code). 라벨 도메인에 회전(rotation) 속성이 아예 존재하지 않아(`grep -rln rotation kr/co/cudo/authoring/label` 0건), dead 클래스의 회전보간 기능이 숨은 요구사항 갭도 아님.
- **수정 방향(제안)**: `common/util/TrackInterpolator.java` + 대응 테스트 삭제, 또는 명확히 "미사용 초안" 주석 강화.

## 요약
- 총 44건 / PASS 44 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0
- 근거 라인 드리프트: 0건 (전건 file:line 정확 일치 — 단, TrackInterpolator 동명 파일 2개 중 실제 사용 파일과의 일치를 확인함, C-ISSUE-82 참조)
- self-fill 결함: 0건
- 신규 이슈 2건(C-ISSUE-81 MEDIUM/테스트커버리지 갭, C-ISSUE-82 LOW/dead code)
- 실동작 검증에 사용한 마스터 데이터 임시변경(LS_LABEL lbl_id=1 USE_YN, lbl_id=2 lbl_nm/lbl_type_cd)은 전부 원상복구 확인 완료. 검증용으로 생성한 프리셋(id 2,3,4 및 clone 51건)은 전부 DELETE로 정리, 최종 DB는 preset_id=1(원본)만 잔존.

