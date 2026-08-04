# B. 배치 파이프라인 + 비식별화 — 테스트 케이스

> 350 케이스(표 행 실측) · 계층: unit / integration / security · 우선순위 P0(Critical)~P2 · [← README](README.md)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|---|:--:|:--:|:--:|---|
| 1 | 2026-07-30 | 217건(근거 재확인 포함 · 기대결과·전제 실질 변경 32건) | 111건 | 2건 | 배치 스텝 트랜잭션 경계 `execute` 이동(5스텝)+정적 가드 신설 · co-locate 비식별 프레임 2벌 추출(2-way base + 심링크 방어) · 스캔 미적재 필터/Pageable/IN 배치화 · 오토라벨 좌표 정규화 단일 규칙(`DetectionBoxNormalizer`) · 오토라벨 일괄저장(`AutoLabelBatchPersister`) · VLM 위탁 신고 보류/재개 배선 · KPST 원본 실재 가드 + 무결성 판정 단일 원천 + 리스 기반 원자 클레임 · Quartz 클러스터링 stg/prd fail-closed · 재시도 stale RETRYING 회수 · 신고 게이트 = 자기 rawSn 행 하나(전파 철회, 파생 412) · 라벨 보존 정책 반전 · `POST /v1/videos/{rawSn}/deident-report` 신설 · V146 FK 27개 |
| 2 | 2026-08-02 | 25건(B-1 전량) | 0건 | 0건 | **B-ISSUE-01** — B-1 절이 구 `MNG_CLIP_MASTER.JOB_DMND_YN='Y'` 스캔 구현(커밋 `11c3e1b8`) 기준으로 남아 있어 관제 2차 적재 소스 교체(커밋 `6c8a5303`, V147~V148 `LS_DATA_INGEST`)를 반영하지 못한 것을 발견 — B-1 25건 전량을 현재 구현 기준으로 재작성. 정정 핵심: **ms→초 단위변환 폐지**(인입 `VDO_LEN_SEC` 는 이미 초 단위 — 구 "30500→31 변환" 기대값 삭제) · `EVNT_TYPE_CD` 매핑 폐지(인입에 유형코드 컬럼 없음, 항상 null) · `SHT_DT` 의 `CRT_DT` 폴백 폐지(결손 시 null 유지) · 원자 클레임(`claimForProcessing`, 0/1 반환) · 미도착 3분기(READY/NOT_ARRIVED/REJECTED, 판정축이 "동일 디렉터리"→"허용 루트 하위"로 전환) · 대기상한+backoff(`NEXT_RTRY_DT`)+가역적 재큐(`requeueFailedForRetry`/`requeueFailedBatch`) · `SRC_TYPE` allowlist fail-closed · 관제 계약 갭 WARN 1회성 신설 반영 |
| 3 | 2026-08-03 | 0건 | 18건 | 0건 | **결정 5 — 영상 처리 현황 목록(`GET /v1/videos`) 검색·필터 4종 신설 + 표시 축 정정**(2026-08-03 사용자 확정, 커밋 대기) → **B-18 신설**(`TC-VIDEO-001~018`, 신규 프리픽스). ①`cctvNameKeyword`(CCTV명 부분일치 **OR** 영상ID 일치, LIKE 메타문자 `!` 이스케이프)·`eventTypeCd`(**카테고리 키**를 서버가 EV-코드 집합으로 변환)·`from`/`to`(**기준 `LS_DATA_RAW.SHT_DT`**, 양끝 경계 포함) ②★**오류 처리 비대칭이 의도된 것**: 날짜 형식·`from>to`·검색어 100자 초과는 **400** / **미등록 `eventTypeCd` 는 0건**(코드 하나로 목록이 죽으면 북마크·뒤로가기 진입이 막힘) ③저장소 쿼리 3개를 통합 쿼리 `searchOriginals` 하나로 교체(검수상태 조인 INNER→LEFT 이나 **결과 동치**) ④`VideoSummaryResponse.capturedAt` 을 `REG_DT`→**`SHT_DT`** 로 정정(폴백 없음) ⑤파생영상 제외(`ORGNL_RAW_SN IS NULL`)·정렬 allowlist·lenient 폴백은 **불변**. FE 분은 [H-18](H-frontend-e2e.md) |
| 4 | 2026-08-03 | 약 166건(라인드리프트 대다수 + 기대결과 실질변경 11건) | 0건 | 0건 | **근거 `file:line` 전수 재확인 회차** — 348행 전수 대조. VLM(B-7)·KPST(B-13)가 각각 Phase C-1/C-2 논블로킹 재설계(`.block(45s)` 폐지, `VlmMarkingTxService`/`VlmSubmitOutcomeRecorder`/`KpstSubmitOutcomeRecorder` 신설, 마킹 전이가 제출 성공 후→제출 **전** 선커밋으로 반전, 재개 대상 사유가 신고 1종→`RESUMABLE_SKIP_REASONS` 3종으로 확장)를 거쳐 근거·기대결과가 실질적으로 달라짐(TC-VLM-009/010/011/012/036, TC-DEID-060/062/067). 스트리밍(B-10)에서 `resolveSafe` 거부 응답이 구서술 FORBIDDEN→실제 **NOT_FOUND** 로 정정(TC-STREAM-B04, 주석 드리프트) + 영상 단위 배정 인가(`labelAccessGuard.verifyRawAccess`) 신설로 B-ISSUE-63 해소 반영(TC-STREAM-B15). `DeidentifyHealthIndicator.java:118` 사전 확정 결함(114줄뿐)도 정정. 라인 드리프트는 YOLO/SAM2/FfmpegFrameExtractor/BatchOrchestrator/MarkingBatchBridge/KpstDeidentService 등 다수 파일에 주석 삽입으로 인한 코드 하향 이동이 원인. 폐기 신규 0건(기존 TC-DEID-034/038 폐기 표기 유지 확인) |

| 5 | 2026-08-04 | 0건 | 2건 | 0건 | **신고자 표시명 노출** — `GET /v1/deident-reports` 응답에 `reporterName`(`MNG_ACCT_USER.USER_NM`) 추가(`TC-DEID-094/095`). 기존 `reporterNo`(`USER_NO` 원값)는 하위호환으로 유지되므로 **정정·폐기 0건**(필드 추가만). 이름 해석은 페이지 단위 **단일 IN 쿼리**이고, 마스터에 없는 신고자(탈퇴 등)는 목록을 깨뜨리지 않고 `null` 로 내린다(fail-soft). ⚠ 같은 커밋의 공지 작성자 표시명(`NoticeResponse.writerName`)은 **본 카탈로그에 게시판(공지) BE 클러스터가 아예 없어** 반영하지 못했다 — 신설은 별건(README 클러스터 표 갱신 필요) |

> **판정 기준**: 현재 코드(브랜치 `tc-update`, HEAD `11c3e1b8`)가 유일한 진실원. ★ 표시된 항목은 루트 `CLAUDE.md` 의 구속 정책이며 결함으로 재분류하지 않는다.

---

## B-1. 관제 학습용 적재 (스캔 → 적재 → 이벤트)

> **2026-08-02 전면 재작성(B-ISSUE-01)** — 관제 2차 적재 주체 반전(커밋 `6c8a5303`, V147~V148)으로 적재 소스가 `MNG_CLIP_MASTER` 직접 스캔에서 관제가 직접 INSERT 하는 `LS_DATA_INGEST` 픽업으로 교체됐다. 아래 25건은 그 구현(`TrainingVideoIngestService`/`TrainingVideoIngestTx`/`LsDataIngestRepository`) 기준이다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-001 | 스캔: 적재 후보 0건 | `findPendingReadyForPolling` 결과 null/empty | `scanAndIngest()` | 0 반환, `ingestOne` 미호출, DEBUG 로그(`no pending ingest rows to scan`) | unit | P2 | TrainingVideoIngestService.java:69-74 |
| TC-BATCH-002 | 스캔: 폴링 후보 조회 — PENDING + backoff 미도래 + FIFO + tick상한 | `LS_DATA_INGEST` 에 `PROC_STTS_CD='PENDING'` N건(일부 `NEXT_RTRY_DT` 미래 예정) | `scanAndIngest()` | `NEXT_RTRY_DT IS NULL OR <= now` 인 행만 `RCPTN_DT ASC, RCPTN_SN ASC` 순으로 최대 `INGEST_SCAN_LIMIT`(100)건 조회 — 후보 250건이면 100건만 처리, 잔여는 다음 tick | integration | P0 | LsDataIngestRepository.java:50-63 · TrainingVideoIngestService.java:58,69-71 |
| TC-BATCH-003 | 스캔: 동시 중복 적재 race 흡수(UnexpectedRollbackException) | 한 행이 `ingestOne` 커밋 시점에 UK(VMS_CLIP_ID) 위반으로 tx abort | `scanAndIngest()` | ERROR 아닌 INFO 로그(`ingest tx rolled back (duplicate clip race) — retried next tick`), 예외 미전파, 나머지 행 계속 처리 | integration | P0 | TrainingVideoIngestService.java:82-91 |
| TC-BATCH-004 | 스캔: 1건 RuntimeException 흡수가 다른 행을 막지 않음 | `ingestOne` 중 1건 임의 RuntimeException | 여러 인입 행 | ERROR 로그(`rcptnSn`+`causeType` 만, 인입 자유텍스트 미포함), 나머지 행 계속 적재 | integration | P0 | TrainingVideoIngestService.java:92-98 |
| TC-BATCH-005 | 스캔: 상한 도달 시 이월 로그 | 후보 ≥100건(`INGEST_SCAN_LIMIT`) | `scanAndIngest()` | `scan finished scanned=100 ingested=… limit=100 carriedOver=true` — 잔여분은 다음 tick 이 이어서 처리(종결된 행이 폴링 술어에서 빠지며 커서 전진) | integration | P1 | TrainingVideoIngestService.java:100-104 |
| TC-BATCH-006 | 스캔 잡: 동일 노드 내 동시 tick 차단 | 같은 노드에서 tick 중첩 | `ControlTrainingVideoScanJob` | `@DisallowConcurrentExecution` 로 직렬화. ⚠ **노드 간 중복 발화는 막지 못한다** — 그 축은 Quartz 클러스터링(B-14)이, 잡 내부 레이스는 `TrainingVideoIngestTx` 의 원자 클레임이 담당(서로 대체하지 않는다) | integration | P1 | ControlTrainingVideoScanJob.java:27-28,38-45 |
| TC-BATCH-007 | 스캔 잡: 예기치 못한 실패 안전망 | `ingestService.scanAndIngest()` 예외 전파 | `execute()` | catch → ERROR(예외 클래스명만), 미전파(misfire 방지) | unit | P2 | ControlTrainingVideoScanJob.java:46-50 |
| TC-BATCH-008 | 스캔 트리거: 60초 간격 등록 | `authoring.control.training-scan.enabled` 기본 true | 트리거 구성 | boot+30초 후 첫 발화, `interval-sec`(기본 60) 반복 | integration | P2 | ControlTrainingVideoScanTriggerConfig.java:25-26,38-47 |
| TC-BATCH-009 | 스캔 트리거: enabled=false 시 미등록 | `authoring.control.training-scan.enabled=false` | 부트 | `@ConditionalOnProperty` 미충족 → 잡/트리거 빈 미등록 | integration | P2 | ControlTrainingVideoScanTriggerConfig.java:20-22 |
| TC-BATCH-010 | 적재: rcptnSn null → 즉시 skip | `candidate` null 이거나 `rcptnSn` null | `ingestOne(candidate)` | WARN 로그, false 반환, `claimForProcessing` 미호출 | unit | P2 | TrainingVideoIngestTx.java:217-221 |
| TC-BATCH-011 | 적재: 원자 클레임 실패(0 반환) → skip | 다른 실행이 이미 클레임했거나 그 사이 종결됨 | `claimForProcessing(rcptnSn)` → 0 | DEBUG 로그(`claim lost — skip`), false 반환, 이후 로직 미실행(엔티티 재조회 안 함) | integration | P0 | TrainingVideoIngestTx.java:223-227 · LsDataIngestRepository.java:147-154 |
| TC-BATCH-012 | 적재: 클레임 성공 후 재조회 시 행 소실 | 클레임 반환 1이나 `findById(rcptnSn)` empty(외부 삭제 등 비정상) | `ingestOne` | ERROR 로그(`claimed row disappeared`), false 반환 — 인입 행 영구보존 정책상 도달 불가 시나리오임을 명시 | unit | P2 | TrainingVideoIngestTx.java:228-234 |
| TC-BATCH-013 | 적재: 식별자 blank → markFailed(순서: VMS_CLIP_ID→VMS_CCTV_ID→RAW_FILE_PATH_NM) | 셋 중 하나 null/blank | `ingestClaimed` | 첫 번째로 비어있는 컬럼명으로 `markFailed("{컬럼} 누락 — 적재 불가")`, false, save 없음 | unit | P1 | TrainingVideoIngestTx.java:241-246,398-409 |
| TC-BATCH-014 | 적재: 멱등 1차 — 이미 적재된 clipId | `findByVmsClipId(vmsClipId)` 존재 | `ingestClaimed` | `markDone(기존 rawSn)`, false 반환, 중복 INSERT 없음(무한 재조회 방지) | integration | P0 | TrainingVideoIngestTx.java:249-255 |
| TC-BATCH-015 | 적재: 경로 REJECTED — 허용 루트 밖/손상 경로 | 허용 루트(`authoring.storage.raw-mount-roots`) 밖 절대경로 또는 `InvalidPathException` | `verifyPath` | `markFailed("원본 영상 경로가 허용 저장 루트 밖이거나 유효하지 않음")`, false(CWE-22) | integration | P0 | TrainingVideoIngestTx.java:256-261,431-441 |
| TC-BATCH-016 | 적재: 경로 REJECTED — 허용 루트 안 파일의 최종 심링크가 루트 밖을 가리킴 | 파일 실경로가 `allowedRoots()` 어디에도 속하지 않음(판정축이 구 "동일 디렉터리"에서 "허용 루트 하위"로 전환) | `verifyPath` → `isUnderAllowedRoot` | REJECTED, false(CWE-59) — 반대로 같은 허용 루트 안의 다른 하위 디렉터리를 가리키는 심링크(예 `/nas/videos/2026/07/clip.mp4`)는 더 이상 오탐 거부되지 않는다 | integration | P0 | TrainingVideoIngestTx.java:421-425,457-460,471-478 |
| TC-BATCH-017 | 적재: 경로 NOT_ARRIVED — 파일 미존재/권한오류/깨진 심링크 | `Files.exists`=false 또는 `toRealPath()` IOException 또는 `isRegularFile`=false | `verifyPath` | NOT_ARRIVED(영구 거부 아님) → `handleNotArrived` 진입, 다음 주기 재시도 대상 | integration | P0 | TrainingVideoIngestTx.java:262-264,442-456 |
| TC-BATCH-018 | 적재: 정상 → LsDataRaw 저장 + 이벤트 발행 + 종결 | 경로 READY, 식별자 정상 | `persistRaw` | `LsDataRaw` 신규 저장 + `VideoIngestedEvent(rawSn)` 발행(AFTER_COMMIT 비식별 선두 트리거) + `ingest.markDone(rawSn)`, true 반환 | integration | P0 | TrainingVideoIngestTx.java:265,329-343 |
| TC-BATCH-019 | 적재: 멱등 2차 — 동시 UK(VMS_CLIP_ID) race | `save()` 시점 `DataIntegrityViolationException` | `persistRaw` | catch→DEBUG 로그, false 반환 — PostgreSQL 은 제약위반 시 트랜잭션 전체를 abort 하므로 이 트랜잭션의 클레임도 함께 롤백되어 행이 PENDING 복귀, 다음 tick 이 1차 멱등(`findByVmsClipId`)으로 DONE 종결 | integration | P0 | TrainingVideoIngestTx.java:344-351 |
| TC-BATCH-020 | 적재 매핑: EVNT_TYPE_CD 항상 null + SHT_DT 폴백 폐지 | 인입 행에 이벤트유형코드 컬럼 자체가 없음 / `SHT_DT` 미수신 | `persistRaw` | `EVNT_TYPE_CD_UNAVAILABLE`(=null) 고정 영속(구 evntLst 매핑 완전 삭제), `SHT_DT` 는 관제 미수신 시 null 유지(구 `CRT_DT` 폴백 미복원 — 대용값 금지) | unit | P1 | TrainingVideoIngestTx.java:114-123,332-334,361-364 |
| TC-BATCH-021 | 적재 매핑: 관제 계약 갭 WARN 1회성 + prvcTypeCd=ANONY 고정 | EVNT_TYPE_CD/SHT_DT 결손 다건 연속 적재 | `warnControlContractGaps`(매 건 호출) | 프로세스 생애 1회만 WARN, 이후 동일 갭은 DEBUG 로만 기록(로그 폭주 방지); `prvcTypeCd` 는 전체 비식별 정책상 항상 ANONY | unit | P2 | TrainingVideoIngestTx.java:111,369-387 |
| TC-BATCH-022 | durationSec 변환: **단위 변환 폐지**(이미 초 단위) | `VDO_LEN_SEC=30500`(NUMERIC(10), 초 단위) | `toDurationSec` | 반올림만 적용해 **30500 그대로 영속** — 구 ms→초(`÷1000`) 변환은 삭제됨(적용하면 600초 영상이 0.6→null 이 되어 길이가 사실상 사라진다) | unit | P1 | TrainingVideoIngestTx.java:505-521,528-529 |
| TC-BATCH-023 | durationSec 변환: null/1초미만/INT범위초과 → null | `VDO_LEN_SEC`=null / 0.4(반올림 시 0) / `Integer.MAX_VALUE` 초과 | `toDurationSec` | 셋 다 null 영속(1초미만은 "길이 0" 오값 방지, 범위초과는 WARN+null) — 적재 후 ffprobe back-fill(`VideoMetaService`)이 실제 길이로 채움 | unit | P1 | TrainingVideoIngestTx.java:516-527 |
| TC-BATCH-024 | SRC_TYPE allowlist: 허용값 통과 / 미매칭 fail-closed | `SRC_TYPE` ∈ {ORIGINAL,RELAY,USER_ULD,GENERATED,AUGMENTED} 또는 미지정 임의값 | `allowedSrcType` | 허용값은 그대로 복사, 미매칭은 WARN(정제된 값 40자 절단)+null(복사 안 함, fail-closed) — 화면표시·파생판별 분기축이라 미지값을 넣지 않는다 | unit | P1 | TrainingVideoIngestTx.java:151-152,493-503 |
| TC-BATCH-025 | 미도착: 대기상한 이내 backoff 복귀 / 초과 시 가역적 종결 | 파일 미도착 반복 관측(`PRCS_DT` 앵커 경과) | `handleNotArrived` | 상한(기본 24h, 최소 1h clamp) 이내면 `PENDING`+`NEXT_RTRY_DT` backoff(1분~1시간 clamp, 경과만큼 증가) 복귀·`RTY_CNT` 미증가; 상한 초과 시 `markFailed`(가역 — `requeueFailedForRetry`/`requeueFailedBatch` 로 되살리면 다음 스캔이 재집음, `ERR_MSG`·`PRCS_DT`·`NEXT_RTRY_DT` 리셋) | integration | P0 | TrainingVideoIngestTx.java:287-326 · LsDataIngestRepository.java:209-220,264-275 |

## B-2. 선두 비식별 브릿지 + 러너 (VideoIngested → Deidentify)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-DEID-001 | 브릿지: AFTER_COMMIT에서만 발화 | 적재 tx 커밋 | VideoIngestedEvent | 커밋 후 `asyncDeidentifyRunner.runAsync` | integration | P0 | IngestDeidentifyBridge.java:26-30 |
| TC-DEID-002 | 브릿지: 적재 롤백 시 비식별 미트리거 | 적재 tx 롤백 | VideoIngestedEvent | `fallbackExecution` 기본 false → 리스너 미호출 | integration | P1 | IngestDeidentifyBridge.java:26 |
| TC-DEID-003 | 러너: raw 미존재 skip | rawSn 조회 empty | runAsync | WARN, 파이프라인 미실행 | unit | P1 | AsyncDeidentifyRunner.java:79-83 |
| TC-DEID-004 | 러너: 동기 완료(mock) → MARKING_READY 전이 | ctx.deidentCompleted=true | runAsync | markRawDataMarkingReady | integration | P0 | AsyncDeidentifyRunner.java:92-94 |
| TC-DEID-005 | 러너: KPST 지연(deferred) → 미전이 | ctx.deidentCompleted=false | runAsync | 전이 없음(폴링 완료가 단일 전이 지점) | integration | P0 | AsyncDeidentifyRunner.java:95-98 |
| TC-DEID-006 | 러너: 실패 시 예외 삼킴+재시도 큐 미사용 | step 예외 | runAsync(@Async) | WARN(예외 클래스명만), 전이 없음, `BatchRetryQueue` 의존 자체 없음 | unit | P1 | AsyncDeidentifyRunner.java:99-106 |
| TC-DEID-007 | 러너: rawSn null → loadRaw empty | rawSn null | loadRaw | Optional.empty, skip | unit | P2 | AsyncDeidentifyRunner.java:112-117 |
| TC-DEID-008 | 러너: preMarkingPipeline 은 DEIDENTIFY 1스텝 (신규) | 부트 | `@Qualifier("preMarkingPipeline")` | `List.of(deid)` — 선두 비식별만 | unit | P2 | BatchPipelineConfig.java:50-52 |

## B-3. DeidentifyStep (mock / KPST / 설정오류)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-DEID-010 | mock-mode 부트 게이트: prd 차단 | mockMode=true+prd | @PostConstruct | IllegalStateException (allowlist local/dev/stg) | security | P0 | DeidentifyStep.java:182-190,205-221 |
| TC-DEID-011 | mock-mode 부트 게이트: 프로파일 없음 거부 | mockMode=true, active 비움 | assertMockAllowedProfile | IllegalStateException(`noActiveProfile`) | security | P1 | DeidentifyStep.java:210,216-221 |
| TC-DEID-012 | mock-mode 부트 게이트: dev/stg 허용+비-local WARN | mockMode=true+dev | assertMockAllowedProfile | 통과, WARN 1줄 | unit | P1 | DeidentifyStep.java:223-227 |
| TC-DEID-013 | mock-mode 부트 게이트: ENV=prod 거부 | mockMode=true,ENV=production | assertMockAllowedProfile | IllegalStateException(trim+소문자 정규화 후 allowlist 대조) | security | P1 | DeidentifyStep.java:213-221 |
| TC-DEID-014 | run: raw null → INVALID_INPUT | raw=null | run() | INVALID_INPUT | unit | P2 | DeidentifyStep.java:269-272 |
| TC-DEID-015 | run: mock 경로 → completed | mockMode=true | run() | completed, KPST 분기보다 앞이라 외부 미접촉 | unit | P0 | DeidentifyStep.java:275-277 |
| TC-DEID-016 | run: KPST 위탁 → deferred | mockMode=false, kpst 주입 | run() | submit 호출, deferred. **원본 실재 검증은 submit 내부 `verifySourceOrFail` 가 수행**(TC-DEID-081) | integration | P0 | DeidentifyStep.java:280-283 |
| TC-DEID-017 | run: mock아님+KPST 미주입 → 설정오류 | kpstService=null | run() | INTERNAL_ERROR(레거시 폴백 없음, 고정 메시지) | unit | P1 | DeidentifyStep.java:284-288 |
| TC-DEID-018 | runMock: 원본 부재 → 'F' 별도 커밋 | source 미존재 | runMock | `recordDeidentFailure`(REQUIRES_NEW 별도 빈) + EXTERNAL_API_ERROR, MARKING_READY 미전이 | integration | P0 | DeidentifyStep.java:306-314 |
| TC-DEID-019 | runMock: 복사 IOException → 'F' | copy IOException | runMock | recordDeidentFailure + INTERNAL_ERROR | integration | P1 | DeidentifyStep.java:330-335 |
| TC-DEID-020 | runMock: 성공 → 'Y'+procLog+부수효과 | 원본 존재 | runMock | 'Y', procLog SUCCEEDED, 작업락 해제, OPEN 신고 RESOLVED, 알림 | integration | P0 | DeidentifyStep.java:345-358 |
| TC-DEID-021 | runMock: atomic move 멱등 | 재실행 | copyAtomically | tmp 복사 → ATOMIC_MOVE(+REPLACE_EXISTING), 불가 환경 replace 폴백, finally tmp 정리 | unit | P1 | DeidentifyStep.java:385-406 |
| TC-DEID-022 | 출력 경로 순회 방어(CWE-22) | base 이탈 | resolveSafeTargetPath | INVALID_INPUT (target 이 `{base}/videos/{rawSn:Long}/…` 로만 구성돼 실제 도달 불가한 방어심도 분기) | security | P1 | DeidentifyStep.java:408-420 |
| TC-DEID-023 | execute: self 프록시로 REQUIRES_NEW 획득 | 무트랜잭션 호출자 | execute() | **`execute` 에는 `@Transactional` 이 없다** — `selfProvider.getObject().run(raw)` 로 프록시 경유해 경계를 얻는다(단위테스트는 `this` 폴백). 애노테이션을 추가하면 REQUIRES_NEW 2중 개시 | integration | P0 | DeidentifyStep.java:239-249 |
| TC-DEID-024 | execute: completed를 ctx 브릿지 | run 반환 | execute() | ctx.markDeidentCompleted(result.completed()) | unit | P1 | DeidentifyStep.java:249 |
| TC-DEID-025 | DeidentifyStep 은 경계 가드의 명시 면제 대상 (신규) | 정적 스캔 | BatchStepTransactionBoundaryTest | `BOUNDARY_EXEMPT` 에 등재 — `execute` 무애노테이션이 정상이며 사유(자기참조 프록시)가 코드에 명문화 | unit | P1 | BatchStepTransactionBoundaryTest.java:BOUNDARY_EXEMPT |

## B-4. BatchOrchestrator 상태 전이 (정상/실패/진입 가드)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-030 | process: rawSn null → INVALID_INPUT | null | process | INVALID_INPUT | unit | P2 | BatchOrchestrator.java:101-103 |
| TC-BATCH-031 | process: 영상 미존재 → NOT_FOUND | loadRaw empty | process | NOT_FOUND | unit | P1 | BatchOrchestrator.java:149-153 |
| TC-BATCH-032 | process: 정상 전체 → COMPLETED | 마킹+비식별 완료 | process | 진입가드 통과 → step 루프 → markRawDataCompleted + markCompleted + retryQueue.clear → COMPLETED | integration | P0 | BatchOrchestrator.java:110-135 |
| TC-BATCH-033 | process: 단계 실패 → FAILED+재시도 큐 | step 예외 | process | markFailed + markRawDataFailed + enqueueIfRetryable | integration | P0 | BatchOrchestrator.java:136-144 |
| TC-BATCH-034 | process: 재시도 소진 후 FAILED 고정 | enqueueIfRetryable false | process | willRetry=false, FAILED 반환 | integration | P1 | BatchOrchestrator.java:140-143 |
| TC-BATCH-035 | process: disabled stage skip(dev 토글) | FRAME_EXTRACT=false | process | `step.isEnabled(ctx)` false → markStage·execute 둘 다 skip | integration | P1 | BatchOrchestrator.java:120-124 |
| TC-BATCH-036 | process: 토글 없음 → 전 stage enabled | stageToggles null | process(rawSn) | 모든 단계 실행 | unit | P1 | BatchContext.java:78-85 |
| TC-BATCH-037 | 두 테이블 분리: 배치완료 시 작업상태 ASSIGNED 복귀 | 배치 완료 | markRawDataCompleted | LS_DATA_RAW=COMPLETED, LS_RAW_DATA_STATUS=ASSIGNED | integration | P0 | BatchTransitionService.java:146-160 |
| TC-BATCH-038 | 작업상태 COMPLETED 점프 시 검수제출 차단 회귀 방지 | 배치완료 후 제출 | 상태검증 | ASSIGNED→PENDING 정상(작업 COMPLETED 는 ReviewService.approve 전용) | integration | P1 | BatchTransitionService.java:147-160 |
| TC-BATCH-039 | 배치실패: MARKING_READY 고착 방지 | 배치 실패 | markRawDataFailed | LS_DATA_RAW=FAILED, 작업상태 FAILED | integration | P0 | BatchTransitionService.java:184-197 |
| TC-BATCH-040 | 배치시작: 두 컬럼 PROCESSING 동시 전이 | 마킹완료 시작, 작업상태가 검수 소유 아님 | `markRawDataProcessingBlocked` | **false 반환**(=차단 아님) + 작업상태·LS_DATA_RAW 둘 다 PROCESSING | integration | P1 | BatchTransitionService.java:110-124 |
| TC-BATCH-041 | 상태 row 부재 시 WARN(진행 계속) | LS_DATA_RAW row 없음 / 작업상태 row 없음 | markRawDataProcessingBlocked · markRawDataMarkingReady | 예외 없이 WARN 후 진행(파생 RAW 는 작업상태 row 자체가 없다) | unit | P2 | BatchTransitionService.java:118-121,172-175,442-446 |
| TC-BATCH-042 | ★진입 가드: 검수 소유 상태면 SKIPPED + step 0건 (신규) | 작업상태 ∈ {PENDING,IN_REVIEW,APPROVED,REJECTED} | process(rawSn) | `markRawDataProcessingBlocked`=true → `BatchStage.SKIPPED` 반환, **step 을 한 건도 실행하지 않음**, LS_DATA_RAW 도 미변경 | integration | P0 | BatchOrchestrator.java:110-118 · BatchTransitionService.java:110-124 |
| TC-BATCH-043 | 진입 가드 집합 = REVIEW_OWNED_STATUSES 4종 (신규) | 상수 검증 | `BatchTransitionService.REVIEW_OWNED_STATUSES` | PENDING/IN_REVIEW/APPROVED/REJECTED 4종. **ASSIGNED 는 제외**(배치 완료 복귀 대상) | unit | P0 | BatchTransitionService.java:77-81 |
| TC-BATCH-044 | 완료/실패 전이도 검수 소유면 LS_DATA_RAW 미변경 (신규) | 작업상태 APPROVED | markRawDataCompleted / markRawDataFailed | `transitionRawDataStatus` true → 즉시 return. (work=APPROVED, stage=COMPLETED/FAILED) 불일치쌍을 만들지 않음 | integration | P0 | BatchTransitionService.java:151-154,191-193 |
| TC-BATCH-045 | 진입 가드는 전 진입점 공통 관문 (신규) | 마킹 브리지·dev 트리거·Quartz 큐·재시도 잡·수동 재처리 | 각 경로로 process 호출 | 모두 동일 가드를 통과 — APPROVED 영상에 AUTO 라벨이 새로 적재되는 무증상 오염 차단 | integration | P0 | BatchOrchestrator.java:110-118 |

## B-5. 마킹 완료 브릿지 (동시성·가드)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-050 | 브릿지: 영상 미존재 → skip | findById empty | MarkingCompletedEvent | WARN, 미트리거 | unit | P1 | MarkingBatchBridge.java:113-119 |
| TC-BATCH-051 | 브릿지: PROCESSING/COMPLETED 재트리거 차단 | dataSttsCd ∈ SKIP_BATCH_STAGES | 이벤트 | skip(역전 방지) | integration | P0 | MarkingBatchBridge.java:91-92,125-130 |
| TC-BATCH-052 | 브릿지: 비식별 미완료 트리거 차단 | deIdntfYn='N'/'F' | 이벤트 | WARN + `MarkingBatchTriggerReport.skipped(REASON_NOT_DEIDENTIFIED)`, 미트리거 | integration | P0 | MarkingBatchBridge.java:134-139 |
| TC-BATCH-053 | 브릿지: tx1 claim 성공 → 트리거 | 작업상태 row 존재·검수 소유 아님 | 이벤트 | tryClaimBatchQueued=true → asyncBatchRunner.runAsync | integration | P0 | MarkingBatchBridge.java:152,173 |
| TC-BATCH-054 | 브릿지: 미배정 REVIEWER — row 부재 시 생성 | 작업상태 row 없음 | 이벤트 | tx1 false → tryCreateBatchQueuedRow → 트리거 | integration | P0 | MarkingBatchBridge.java:153-160 |
| TC-BATCH-055 | 브릿지: 동시 노드 row 생성 경쟁 — 1건만 | saveAndFlush DataIntegrityViolation | 이벤트 | 별도 tx 밖에서 catch → `concurrent row creation … skipping`(1건만 트리거) | integration | P0 | MarkingBatchBridge.java:154-160 |
| TC-BATCH-056 | 브릿지: 동시 2 이벤트 중 1건만 BATCH_QUEUED | 거의 동시 이벤트 2건 | 이벤트×2 | 조건부 UPDATE(check-and-set)로 직렬화 | integration | P0 | BatchTransitionService.java:237-261 |
| TC-BATCH-057 | 브릿지: 이미 claimed면 skip | 두 클레임 모두 false | 이벤트 | `batch already claimed/in-progress or review-owned … skipping` | unit | P1 | MarkingBatchBridge.java:162-170 |
| TC-BATCH-058 | tryCreateBatchQueuedRow: row 존재 → false 멱등 | existsById=true | 호출 | false | unit | P1 | BatchTransitionService.java:299-320 |
| TC-BATCH-059 | tryCreateBatchQueuedRow: 할당형 PK saveAndFlush 즉시 flush | 신규 row | 호출 | INSERT flush, UK 위반 시 예외 전파(호출부가 별도 tx 밖에서 catch) | integration | P0 | BatchTransitionService.java:299-332 |
| TC-BATCH-060 | 로그 인젝션 방어(CWE-117) | dataSttsCd/deIdntfYn 에 CR/LF | sanitize | 개행 제거 후 로그(그 외 인자는 Long rawSn 이라 주입 표면 없음) | security | P1 | MarkingBatchBridge.java:196-198 |
| TC-BATCH-061 | 브릿지 skip 집합이 진입 가드와 동일 상수 공유 (신규) | 작업상태 검수 소유 | 이벤트 | tx1 클레임이 `REVIEW_OWNED_STATUSES` 를 skip 집합으로 받아 입구·본체·출구가 같은 기준으로 막힘 | integration | P0 | MarkingBatchBridge.java:76-81 · BatchTransitionService.java:77-81 |
| TC-BATCH-062 | 미트리거 사유가 마킹 응답에 실린다 (신규) | 브리지가 skip | POST markings | 201 응답에 `batchTriggered=false` + 사유. 브리지 미실행(판정 불가)이면 두 필드 null | integration | P1 | MarkingService.java:108,135-147 |

## B-6. MarkingService (자동/수동, 경계값, 인가)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-070 | 인가: actor null → UNAUTHORIZED | precheck | create | 401(프로브 이전) | security | P0 | MarkingGuards.java:52-55 |
| TC-BATCH-071 | 인가: REVIEWER 전체 허용 | REVIEWER | requireAssignedOrReviewer | 통과 | unit | P1 | MarkingGuards.java:56-59 |
| TC-BATCH-072 | 인가: 미배정 WORKER → FORBIDDEN(존재 미노출) | WORKER 미배정 | requireAssignedOrReviewer | 403 — 미존재 rawSn 도 403(NOT_FOUND 아님) | security | P0 | MarkingGuards.java:44-64 |
| TC-BATCH-073 | 인가 우선: 미배정 시 ffprobe 미실행 | 미배정 WORKER AUTO | create | precheck 거부, durationResolver 미호출 | security | P1 | MarkingService.java:105-130 |
| TC-BATCH-074 | 프리컨디션: 영상 미존재 → NOT_FOUND | raw=null | requirePreconditions | NOT_FOUND | unit | P1 | MarkingGuards.java:80-82 |
| TC-BATCH-075 | 프리컨디션: 비식별 미완료 → PRECONDITION_FAILED | deIdntfYn≠Y | requirePreconditions | 412 | security | P0 | MarkingGuards.java:83-86 |
| TC-BATCH-076 | 프리컨디션: MARKING_READY 아님 → 412 | dataSttsCd≠MARKING_READY | requirePreconditions | 412(역전 차단) | security | P0 | MarkingGuards.java:87-90 |
| TC-BATCH-077 | 프리컨디션: 이벤트유형 미지정 → INVALID_INPUT | evntTypeCd blank | requirePreconditions | 400 | unit | P1 | MarkingGuards.java:91-95 |
| TC-BATCH-078 | AUTO: intervalFrames null/≤0 → INVALID_INPUT | AUTO, null/0/-5 | create | INVALID_INPUT | unit | P1 | MarkingService.java:196-198 |
| TC-BATCH-079 | AUTO: durationSec null/≤0 → INVALID_INPUT(backstop) | 전 폴백 실패 | generateAutoMarks | INVALID_INPUT(퇴화 방지) | unit | P0 | MarkingService.java:269-274 |
| TC-BATCH-080 | AUTO: 정상 marks(실 fps, off-by-one) | dur=10s,fps=30,interval=30 | generateAutoMarks | totalFrames=300, frameIndex<300 step 30 | unit | P0 | MarkingService.java:276-282 |
| TC-BATCH-081 | AUTO: 분수 fps(29.97) 반올림 | fps=29.97 | generateAutoMarks | `Math.round(dur×fps)` | unit | P1 | MarkingService.java:276 |
| TC-BATCH-082 | AUTO/MANUAL: fps pin 저장(TOCTOU 제거) | 마킹 생성 | create | `LsMarking.createAuto/createManual` 에 해석 fps pin | integration | P0 | MarkingService.java:191,216-218 |
| TC-BATCH-083 | MANUAL: marks 비면 INVALID_INPUT | MANUAL, empty/생략 | create | INVALID_INPUT | unit | P1 | MarkingService.java:204-207 |
| TC-BATCH-084 | mode 미지 → INVALID_INPUT | "X"/"auto"/" AUTO " | create | INVALID_INPUT(대소문자·trim 미허용, fail-closed) | unit | P2 | MarkingService.java:210-212 |
| TC-BATCH-085 | 생성 성공 → MarkingCompletedEvent 발행 | 정상 | create | publishEvent, 201 | integration | P0 | MarkingService.java:237 |
| TC-BATCH-086 | 이벤트명 자동소싱 = evntTypeCd | 정상 | create | eventName=raw.getEvntTypeCd() | unit | P2 | MarkingService.java:182 |
| TC-BATCH-087 | persist self 프록시(AFTER_COMMIT 브릿지 보존) | 컨테이너 실행 | create | `self.create(...)` 프록시 경유 → 커밋 후 리스너 발화 | integration | P1 | MarkingService.java:128-130 |
| TC-BATCH-088 | durationSec 3단 폴백(VDO_LEN→메타→ffprobe) | 길이 비어있음 | resolveDurationSec | 3단 폴백 해석, 트랜잭션 밖 수행(커넥션 미보유) | integration | P0 | VideoDurationResolver.java:74-97 |
| TC-BATCH-089 | 컨트롤러: WORKER/REVIEWER만 생성 | @PreAuthorize | POST markings | PORTAL_USER 등 403 | security | P1 | MarkingController.java:51 |
| TC-BATCH-090 | ★rawSn 당 활성 마킹 1건 — 순차 재요청 409 (신규) | 활성 마킹 존재 | POST markings 2회차 | `requireNoActiveMarking` → 409 CONFLICT | integration | P0 | MarkingService.java:178 · MarkingGuards.java:109 |
| TC-BATCH-091 | 동시 마킹 — V142 부분 유니크 위반이 409 (500 아님) (신규) | 동시 3요청 | POST markings ×3 | save+flush 를 감싼 catch 가 `DataIntegrityViolationException` → 409 로 변환. 1건만 생성, 부분 저장 없음 | integration | P0 | MarkingService.java:225-234 · V142__add_ls_marking_active_unique.sql |
| TC-BATCH-092 | MANUAL: 요청 내 중복 frameIndex → 400 (신규) | 동일 frameIndex 2회 | create | INVALID_INPUT(`validateManualMarks`) | unit | P1 | MarkingService.java:208 |
| TC-BATCH-093 | MANUAL: frameIndex 상한 = round(dur×fps)+ceil(fps) (신규) | 길이·fps 확보 | frameIndex=상한 이상 | 400. 1초 마진은 fps 불일치·정수초 절단 보정용이며 `999999999` 같은 과대값은 여전히 거부 | unit | P0 | MarkingService.java:350-352 |
| TC-BATCH-094 | MANUAL: 길이 미상이면 상한 검증만 skip (신규) | durationSec null | create | 중복·하한 검증은 그대로 적용, 상한만 건너뛰고 WARN | unit | P1 | MarkingService.java:301-330,317-321 |
| TC-BATCH-095 | MANUAL 길이 해석은 프로브 없이 (신규) | mode=MANUAL | create | `resolveDurationSecWithoutProbe` 사용 — 대화형 경로에 ffprobe 서브프로세스 미기동 | integration | P1 | MarkingService.java:125-126 |
| TC-BATCH-096 | AUTO intervalFrames 상한은 여전히 미검증 (신규) | dur×fps 보다 큰 interval | intervalFrames=999999999 | 201 + marks 1건(`frameIndex=0`). 하한(≥1)만 검증 — **B-ISSUE-23 미해소**(현재 동작 고정) | unit | P2 | MarkingService.java:196-198,276-282 |

## B-7. VLM 위탁 Step + 콜백 수신 + 신고 보류/재개

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-VLM-001 | Step: 마킹 있으면 runWithMarking(최신 1건) | ctx.markings 있음 | execute | `runWithMarking(markings.get(0))`. 활성 마킹 1건 제약(V142)으로 다건 동시 존재가 차단되어 "나머지 마킹 영구 PENDING"(B-ISSUE-22) 은 재발하지 않는다 | unit | P1 | VlmTimeseriesStep.java:219-227 |
| TC-VLM-002 | Step: 마킹 없으면 run | empty | execute | run(rawSn) | unit | P2 | VlmTimeseriesStep.java:219-227 |
| TC-VLM-003 | Step: enabled=false → SKIPPED **+ DB 기록** | vlm.enabled=false | doSubmit | SKIPPED 반환, 외부 호출 0, **`recordVlmSkipped(rawSn, SKIP_REASON_DISABLED)` 로 LS_BATCH_PROC_LOG 에 사유 적재**(B-ISSUE-24 해소 — 구 "무흔적" 폐기) | integration | P0 | VlmTimeseriesStep.java:269-273 · BatchStatusService.java:58-62 |
| TC-VLM-004 | Step: rawSn null → INVALID_INPUT | null | doSubmit | INVALID_INPUT | unit | P2 | VlmTimeseriesStep.java:261-263 |
| TC-VLM-005 | Step: 영상 미존재 → NOT_FOUND | existsById=false | doSubmit | NOT_FOUND(enabled 체크 이후) | unit | P1 | VlmTimeseriesStep.java:276-278 |
| TC-VLM-006 | Step: 비식별 경로 없음 → fail-closed | 성공 procLog 없음 | resolveDeidentifiedPath | EXTERNAL_API_ERROR(원본 경로 전송 코드 자체가 없음) | security | P0 | VlmTimeseriesStep.java:390-400 |
| TC-VLM-007 | Step: request_id 등록 실패 → describe 미호출 | recordIssued 예외 | doSubmit | EXTERNAL_API_ERROR abort(전송 이전) | integration | P0 | VlmTimeseriesStep.java:318-325 |
| TC-VLM-008 | Step: recordIssued 독립 커밋(REQUIRES_NEW) | describe 실패/롤백 | ledger | 매핑 durable 잔존 → 콜백 역조회 성공 | integration | P1 | VlmTimeseriesStep.java:319 · PersistentWebhookIdempotencyLedger.java:57-68 |
| TC-VLM-009 | Step: describe 응답 null → **비동기 제출실패로 기록**(동기 예외 아님) | vlmClient.submitTimeseries 가 빈 응답(onComplete only) | doSubmit(subscribe) | **Phase C-1 이후 동기 EXTERNAL_API_ERROR 가 아니다** — `switchIfEmpty(Mono.error(...))` 로 리액티브 에러 신호가 되어 전용 스케줄러 스레드의 `outcomeRecorder.onSubmitFailed` 가 비동기로 `SKIP_REASON_SUBMIT_FAILED` 기록 + 마킹 보상 전이만 수행한다. `doSubmit` 자체는 제출 개시 의미로 동기 반환(`submitted`) | unit | P1 | VlmTimeseriesStep.java:373-377 · VlmSubmitOutcomeRecorder.java:104-123 |
| TC-VLM-010 | Step: 블로킹 제출 구조 자체가 폐기됨(Phase C-1) — 45s 이중 타임아웃 서술 폐기 | vendor 지연 | doSubmit → subscribe | 구 `.block(45s)` 는 삭제됨(`BLOCK_TIMEOUT` 상수가 코드에 없음, 주석에만 "구 코드는" 으로 잔존) — 파이프라인 스레드(batch-async-/Quartz/Tomcat)를 더 이상 점유하지 않는다. 유일한 타임아웃은 단일 호출의 `vlm.client.timeout-seconds`(기본 10s)뿐이며 초과 시 리액티브 에러로 흘러 `onSubmitFailed` 가 비동기 기록 | integration | P1 | VlmTimeseriesStep.java:348-377 · VlmClient.java(timeout-seconds) |
| TC-VLM-011 | Step: **제출 전(pre-commit)** 마킹 PENDING→VLM_REQUESTED 선커밋(Phase C-1) | marking PENDING | persistVlmRequested | "위탁 성공 시"가 아니라 **제출 개시 직전** `markingTxService.persistVlmRequested(marking)` 로 독립 커밋한다 — 논블로킹 제출은 콜백이 ACK 보다 먼저 도착할 수 있어, 전이를 제출 후에 두면 콜백 수신부가 대상을 못 찾고 영구 고착된다. detached 마킹은 save(=merge)로 명시 영속 | integration | P0 | VlmTimeseriesStep.java:327-337 · VlmMarkingTxService.java:49-59 |
| TC-VLM-012 | Step: retry — 이미 전이된 마킹 no-op | VLM_COMPLETED | persistVlmRequested(VlmMarkingTxService) | Phase C-1 로 `VlmTimeseriesStep.persistMarkingTransition` 은 폐지·`VlmMarkingTxService.persistVlmRequested` 로 이관됐다 — `LsMarking.markVlmRequested()` 가 PENDING 아니면 false → save 미호출(durable 역행 차단), 동일 규약 유지 | unit | P1 | VlmMarkingTxService.java:49-59 |
| TC-VLM-013 | Step: 콜백 URL 고정 base(SSRF 차단) | callbackBaseUrl | resolveCallbackUrl | 고정 base + `HmacWebhookFilter.PATH_VLM`, 사용자 입력 미반영 | security | P1 | VlmTimeseriesStep.java:402-411 |
| TC-VLM-014 | 콜백: 미발급 request_id → UNAUTHORIZED | ledger lookup empty | handle | UNAUTHORIZED | security | P0 | VlmResultService.java:69-74 |
| TC-VLM-015 | 콜백: 이미 PROCESSED → 멱등 스킵 | state=PROCESSED | handle | false, 중복 적재 없음 | integration | P0 | VlmResultService.java:77-80 |
| TC-VLM-016 | 콜백: rawSn 매핑 없음 → UNAUTHORIZED | entry.rawSn null | handle | UNAUTHORIZED | unit | P1 | VlmResultService.java:83-88 |
| TC-VLM-017 | 콜백: 미지 status → INVALID_INPUT(멱등 미마킹) | status="done" | handle | INVALID_INPUT, PROCESSED 미마킹(재전송 허용). DTO `@Pattern` 이 1차로 400 | integration | P0 | VlmResultService.java:96-101 |
| TC-VLM-018 | 콜백: failed → error 기록+VLM_FAILED+멱등 | status=failed | handle | handleFailed → markProcessedInTx → true | integration | P0 | VlmResultService.java:105-109,211-232 |
| TC-VLM-019 | 콜백: completed+영상 미존재 → NOT_FOUND | existsById false | handle | NOT_FOUND | unit | P1 | VlmResultService.java:112-115 |
| TC-VLM-020 | 콜백: 한 콜백 내 중복 metaKey → INVALID_INPUT | results dup metaKey | dedupSegments | INVALID_INPUT | unit | P1 | VlmResultService.java:238-250 |
| TC-VLM-021 | 콜백: META upsert — 신규만 검수큐 PENDING | 신규+기존 혼재 | handle | 신규 create+PENDING, 기존 값 갱신만 | integration | P0 | VlmResultService.java:119-155 |
| TC-VLM-022 | 콜백: 마킹 VLM_REQUESTED→VLM_COMPLETED | 대상 마킹 존재 | handle | markVlmCompleted | integration | P1 | VlmResultService.java:158-161 |
| TC-VLM-023 | 콜백: 원자성 — 중간 실패 시 PROCESSED 롤백 | META 저장 중 예외 | handle | 단일 `@Transactional` + `markProcessedInTx`(REQUIRED) → 전체 롤백 | integration | P0 | VlmResultService.java:63,164 |
| TC-VLM-024 | 콜백: 동일 request_id 동시 콜백 직렬화 | 동시 2 콜백 | handle | `lookupForProcessing`(비관적 락) → 2번째 멱등 스킵 | integration | P0 | VlmResultService.java:69 |
| TC-VLM-025 | 콜백: 로그 마스킹(CR/LF/tab) | 제어문자 | safe() | 치환 후 로그 | security | P1 | VlmResultService.java:252-255 |
| TC-VLM-030 | VLM 콜백 무인증(HMAC 없음) — isIssued 게이트 차단 | 콜백 진입 | POST /v1/vlm/callback | request_id 발급 게이트(UUIDv4)로 차단 → 401 | security | P1 | VlmResultController.java:41-49 · WebhookProtectedPaths.java:137-143 · HmacWebhookFilter.java:470 · VlmResultService.java:69-74 |
| TC-VLM-031 | ★신고 구간 위탁 **보류** — 경로 해석 직전 게이트 (신규) | `DE_IDNTF_YN='F'` | doSubmit | `resolveDeidentifiedPath` **이전**에 `deidentReportGate.isUnderDeidentReport` → SKIPPED 반환, 외부 호출 0, `recordVlmSkipped(SKIP_REASON_DEIDENT_REPORT)` 로 사유 적재 | security | P0 | VlmTimeseriesStep.java:303-307 |
| TC-VLM-032 | 보류는 실패가 아니다 (신규) | 신고 구간 재처리 | BatchReprocessService.retry / Quartz 재큐 | 예외 미발생 → 배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진 | integration | P0 | VlmTimeseriesStep.java:303-307 |
| TC-VLM-033 | 게이트 조회 실패는 fail-closed (신규) | 게이트 DB 오류 | doSubmit | 예외 전파 → 위탁 미진행(비식별본이 나가지 않음) | security | P0 | VlmTimeseriesStep.java:302-303 |
| TC-VLM-034 | 해소 시 재위탁 배선 (신규) | 신고 resolve → `'F'→'Y'` | DeidentGateReopenedEvent(AFTER_COMMIT) | VlmResumeBridge → VlmWithheldResumeRunner.resumeAsync → run/runWithMarking 재호출 | integration | P0 | VlmResumeBridge.java:34-38 · VlmWithheldResumeRunner.java:62-77 |
| TC-VLM-035 | 재위탁 멱등: 시계열 메타 ≥1건이면 skip (신규) | LS_DATA_META 존재 | resumeAsync | `resume skipped — timeseries meta already present` 로그 후 미호출 | integration | P0 | VlmWithheldResumeRunner.java:94-110 |
| TC-VLM-036 | ★재위탁 조건: 신고 보류만이 아니라 RESUMABLE_SKIP_REASONS 3종 확장(Phase C-1) (신규) | SKIPPED 사유가 `SKIP_REASON_DISABLED` | resumeAsync | 재개 대상 아님(false) — Phase C-1 에서 신고 보류(`SKIP_REASON_DEIDENT_REPORT`) 외에 **비동기 제출 실패**(`SKIP_REASON_SUBMIT_FAILED`)·**ACK 미수신 회수**(`SKIP_REASON_ACK_MISSING`)도 재개 대상으로 추가됐다(사유 목록 단일 원천은 `RESUMABLE_SKIP_REASONS`). 구 "보류 사유가 신고일 때만" 서술 폐기 | unit | P1 | VlmWithheldResumeRunner.java:94-99 · VlmTimeseriesStep.java:150-152 · BatchStatusService.java:114-119 |
| TC-VLM-037 | 재위탁 실패는 삼킴(best-effort) (신규) | 재위탁 중 예외 | resumeAsync(@Async) | WARN(클래스명만), 보류 기록·메타0 조건 보존 → 다음 해소/재처리에서 재시도 | unit | P1 | VlmWithheldResumeRunner.java:78-83 |
| TC-VLM-038 | 감사 기록 분리: recordVlmTimeseriesResult = REQUIRES_NEW (신규) | 위탁 성공 후 스텝 롤백 | recordVlmTimeseriesResult | 별도 트랜잭션으로 커밋되어 "외부 위탁은 성공했는데 기록이 없다"를 방지(CWE-778) | integration | P1 | BatchStatusService.java:149-156 |
| TC-VLM-039 | recordVlmSkipped 는 REQUIRED 유지(의도) (신규) | 보류/비활성 경로 | recordVlmSkipped | 호출 직후 정상 반환하므로 호출자 트랜잭션에 참여 — REQUIRES_NEW 로 바꾸지 않는다 | unit | P2 | BatchStatusService.java:58-62,71-73 |
| TC-VLM-040 | VLM 스텝 트랜잭션 경계 = execute (신규) | 무트랜잭션 호출자 | 빈(프록시).execute(ctx) | REQUIRES_NEW(쓰기 가능) 개시, 내부 `run`/`runWithMarking` 은 자기호출이라 중첩 없음 | integration | P0 | VlmTimeseriesStep.java:219-227,236-255 |

## B-8. FfmpegFrameExtractor (원본+비식별 2벌, co-locate, 경계)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-100 | execute: marks 비면 INVALID_INPUT | ctx.marks empty | execute | INVALID_INPUT | unit | P1 | FfmpegFrameExtractor.java:133-136 |
| TC-BATCH-101 | execute: 추출 0건 → INTERNAL_ERROR | frames empty | execute | INTERNAL_ERROR | unit | P1 | FfmpegFrameExtractor.java:144-147 |
| TC-BATCH-102 | extractByMarks: 영상 메타 blank → INVALID_INPUT | rawFilePathNm blank | extractByMarks | INVALID_INPUT | unit | P2 | FfmpegFrameExtractor.java:177-179 |
| TC-BATCH-103 | extractByMarks: 비식별 미완료 → INVALID_INPUT | deIdntfYn≠Y | extractByMarks | INVALID_INPUT(비식별 선행) | unit | P0 | FfmpegFrameExtractor.java:185-188 |
| TC-BATCH-104 | extractByMarks: 원본 미존재 → INVALID_INPUT | source 부재 | extractByMarks | INVALID_INPUT | unit | P1 | FfmpegFrameExtractor.java:190-193 |
| TC-BATCH-105 | ★비식별 경로가 **허용 base 전부**의 밖 → RAW only(fail-closed) | 외부/DB 오염 경로 | extractByMarks | WARN(rawSn 만, 경로 원문 미노출) + RAW only. **원본 폴백 없음**. 판정은 `readableDeidVideoBases`(구 `deidentified-path` 단독 판정 폐기) | security | P0 | FfmpegFrameExtractor.java:206-213,316-333 |
| TC-BATCH-106 | 비식별 경로 null/파일 부재 → RAW only | procLog 경로 없음/파일 없음 | extractByMarks | WARN, RAW only | integration | P1 | FfmpegFrameExtractor.java:216-225 |
| TC-BATCH-107 | 정상: 2벌 추출+deid 경로 INSERT 시점 저장 | deid 경로 유효 | extractByMarks | 비식별 프레임을 `create()` **이전에** 쓰고 6-arg `LsDataSrc.create` 에 deidPath 포함(PARTIAL 회귀 방지) | integration | P0 | FfmpegFrameExtractor.java:262-277 |
| TC-BATCH-108 | seekMillis=round(frameIndex×1000/fps), pin fps 우선 | pinnedFps 유효 | extractByMarks | pinnedFps 사용(재조회 없음) | unit | P0 | FfmpegFrameExtractor.java:234,248,393-398 |
| TC-BATCH-109 | pin fps null/비정상 → resolveFps 폴백 | null/NaN/≤0 | effectiveFps | resolveFps(미상 시 30.0) | unit | P1 | FfmpegFrameExtractor.java:393-398 |
| TC-BATCH-110 | 출력 경로 순회 방어(CWE-22) | base 이탈 | resolveSafeOutputDir | INVALID_INPUT | security | P1 | FfmpegFrameExtractor.java:377-384 |
| TC-BATCH-111 | frames raw/deid 서브세그먼트 분기(충돌 없음) | STORAGE_RAW==STORAGE_DEID | resolveSafeOutputDir | `{base}/frames/raw/{rawSn}` · `{base}/frames/deid/{rawSn}` 분기 | integration | P1 | FfmpegFrameExtractor.java:377-384 |
| TC-BATCH-112 | 이력: deid 채운 경우 CREATED+DEID_ATTACHED 2건 | deidAttached=true | extractByMarks | hstry 2건 | unit | P2 | FfmpegFrameExtractor.java:272-276 |
| TC-BATCH-113 | ★co-locate 산출 비식별 영상 채택 (신규) | 비식별본이 `dirname(원본)/{rawSn}/deid/` 에 있음 | extractByMarks | 비식별 벌이 실제로 추출되고 `DE_IDNTF_SRC_FILE_PATH_NM` 이 채워진다 — 구 결함(자기 산출물을 신뢰불가로 판정해 **항상** RAW only → export 비식별 벌 결손) 회귀 방지 | integration | P0 | FfmpegFrameExtractor.java:316-333 · VideoArtifactRootResolver.java:337-348 |
| TC-BATCH-114 | 구 위치도 계속 허용 — 2-way allowlist (신규) | 비식별본이 `{deid_base}/videos/{rawSn}/` 에 있음 | extractByMarks | 동일하게 채택(전환 전후 행이 섞여도 양쪽 다 읽힘) | integration | P0 | VideoArtifactRootResolver.java:308-327,337-348 |
| TC-BATCH-115 | 심링크 방어: 대상 파일이 원본을 가리키면 거부 (신규) | deid 파일이 원본 영상 심링크 | extractByMarks | `verifyRealPathUnder` 실패 → **예외가 아니라 false** → 기존 RAW only 분기로 흡수. 원본 프레임이 "비식별본"으로 적재되지 않음(CWE-59/CWE-359) | security | P0 | FfmpegFrameExtractor.java:356-368 · VideoArtifactRootResolver.java:458-467 |
| TC-BATCH-116 | 심링크 방어: 중간 세그먼트 심링크도 거부 (신규) | 디렉터리 세그먼트가 심링크로 base 밖을 가리킴 | extractByMarks | `realOrNearest` 로 접힌 실경로가 base 밖 → 거부, RAW only | security | P0 | VideoArtifactRootResolver.java:474-497 |
| TC-BATCH-117 | 리졸버 미주입(단위 수동 생성) → 구 동작 (신규) | artifactRootResolver=null | isUnderAllowedDeidBase | `deidentified-path` 단독 판정으로 폴백 — 허용 범위가 **넓어지지 않는다**(fail-secure) | unit | P1 | FfmpegFrameExtractor.java:316-320 |
| TC-BATCH-118 | 후보 도출 예외 시 구 동작 폴백 (신규) | readableDeidVideoBases RuntimeException | isUnderAllowedDeidBase | 구 위치만으로 판정(fail-secure), 파이프라인 미중단 | unit | P1 | FfmpegFrameExtractor.java:321-326 · VideoArtifactRootResolver.java:341-346 |

## B-9. YOLO / SAM2 / Interpolate (오토라벨 단계 · 좌표 정규화)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-120 | YOLO: rawSn null → INVALID_INPUT | null | run | INVALID_INPUT | unit | P2 | YoloAutolabelStep.java:180-183 |
| TC-BATCH-121 | YOLO: 프레임별 순차 track(clipId 격리, 0=리셋) | 다수 프레임 | run | clipId=String.valueOf(rawSn), frameIndex 0..N 순차 | integration | P1 | YoloAutolabelStep.java:195-197,209-212 |
| TC-BATCH-122 | YOLO: ai-server 호출 실패 → EXTERNAL_API_ERROR | 예외 | run | EXTERNAL_API_ERROR | integration | P0 | YoloAutolabelStep.java:219-227 |
| TC-BATCH-123 | YOLO: mock 응답 감지 WARN(CRLF 살균) | resp.mock()=true | run | WARN + LogSanitizer(source/mockReason) | security | P1 | YoloAutolabelStep.java:244-248,270-275 |
| TC-BATCH-124 | YOLO: DTCT_TYPE_CD 축 매핑 | 검출 라벨 | run | `findLabelIdByDtctType` 로 labelId 매핑(미매칭 null) | integration | P1 | YoloAutolabelStep.java:318-321 |
| TC-BATCH-125 | YOLO: 프리셋 토글 필터(미매핑 fail-safe) | togglesOpt empty | resolveToggle | BOTH 처리(전체 통과) | unit | P1 | YoloAutolabelStep.java:467-477 |
| TC-BATCH-126 | YOLO: 이미지 경로 순회 방어(CWE-22) | base 이탈 | readImageAsBase64 | INVALID_INPUT | security | P1 | YoloAutolabelStep.java:494-499 |
| TC-BATCH-127 | YOLO: conf/imgsz/iou 설정 fail-safe | 조회 실패 | readDoublePercent/readInt | 기본값 폴백, 정상 시 SystemConfig 값이 ai-server 요청에 반영 | unit | P2 | YoloAutolabelStep.java:191-193,425-453 |
| TC-BATCH-128 | SAM2: (srcSn,label,trackId) dedup DB BBOX 우선 | 동일 키 | buildJobs | 1회 호출, DB BBOX 좌표 우선(hint 는 putIfAbsent) | integration | P1 | Sam2SegmentStep.java:304-320 |
| TC-BATCH-129 | SAM2: polygon=false 라벨 skip | toggle.polygon false | run | WARN skip | unit | P2 | Sam2SegmentStep.java:195-201 |
| TC-BATCH-130 | SAM2: 응답 폴리곤 상한 초과 → 단순화 | polygon > MAX_POINTS_PER_LABEL | capPolygon | simplifyToMax 로 상한 이하 | unit | P1 | Sam2SegmentStep.java:234,428-446 |
| TC-BATCH-131 | SAM2: 호출 실패 → EXTERNAL_API_ERROR | 예외 | callSam2 | EXTERNAL_API_ERROR | integration | P1 | Sam2SegmentStep.java:289-297 |
| TC-BATCH-132 | Interpolate: 프레임/후보 없음 → 0 | empty | interpolate | 0 | unit | P2 | TrackInterpolationStep.java:131-134,144-147,174-178 |
| TC-BATCH-133 | Interpolate: 재실행 멱등 stale 선삭제 | 기존 INTERPOLATE row | interpolate | AI_INFO→LBL 순 삭제 후 재생성 | integration | P1 | TrackInterpolationStep.java:158-172 |
| TC-BATCH-134 | Interpolate: 트랙 부분실패 격리 | 한 트랙 예외 | interpolate | 트랙 단위 skip, 다른 트랙 정상 | integration | P1 | TrackInterpolationStep.java:190 |
| TC-BATCH-135 | Interpolate: 혼재 타입 트랙 skip | LBL_TYPE distinct>1 | interpolateTrack | WARN skip | unit | P2 | TrackInterpolationStep.java:353 |
| TC-BATCH-136 | Interpolate: BBOX flat/nested 양포맷 | 레거시+신규 | parseBbox | 둘 다 변환 | unit | P1 | TrackInterpolationStep.java:441 |
| TC-BATCH-137 | Interpolate 단건: from+to stale 삭제 | 트랙 병합 | interpolateSingleTrack | 양쪽 stale 삭제 후 toTrackId 재보간, **예외 전파**(머지 원자성) | integration | P1 | TrackInterpolationStep.java:247-330 |
| TC-BATCH-138 | MarkingLoadStep: 마킹 로드+최신 markCn 파싱 | 마킹 존재 | execute | setMarkings, setMarks | unit | P1 | MarkingLoadStep.java:50-58 |
| TC-BATCH-139 | MarkingLoadStep: markCn 파싱 실패 → INTERNAL_ERROR | 손상 JSON | parseMarks | INTERNAL_ERROR | unit | P2 | MarkingLoadStep.java:60-65 |
| TC-BATCH-140 | 파이프라인 순서 검증 | BatchPipelineConfig | postMarkingPipeline | MARKING→VLM→FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE | unit | P1 | BatchPipelineConfig.java:33-41 |
| TC-BATCH-141 | ★좌표 정규화 단일 규칙 — clamp (신규) | 화면 경계 밖 좌표 | DetectionBoxNormalizer.normalizeBbox | `0 ≤ x ≤ imgWidth`, `0 ≤ y ≤ imgHeight` 로 clamp. 4경로(배치/온라인/트랙/persist)가 이 유틸을 경유하며 각자의 `validateBbox` 는 폐기 | unit | P0 | DetectionBoxNormalizer.java:41-70 |
| TC-BATCH-142 | 유한성 가드가 clamp **이전** (신규) | 좌표에 NaN/Infinity | normalizeBbox | `IllegalArgumentException` — `NaN < 0` 이 false 라 음수 검사를 통과하는 함정을 clamp 전에 차단 | unit | P0 | DetectionBoxNormalizer.java:54-58 |
| TC-BATCH-143 | 퇴화 박스는 예외가 아니라 스킵 (신규) | clamp 후 폭·높이 ≤ 0 | normalizeBbox | `Optional.empty()` → YOLO 는 그 **검출만** 드롭(`droppedDegenerate`), 영상 전체 실패 없음 | unit | P0 | DetectionBoxNormalizer.java:60-70 · YoloAutolabelStep.java:305-311,342-347 |
| TC-BATCH-144 | 형식 위반도 검출 단위 드롭 (신규) | 좌표 개수≠4 / null / NaN | run | `IllegalArgumentException` 을 루프 안에서 catch → `droppedMalformed` 증가 후 continue. **영상 1건의 YOLO 단계 전체 실패 폐기**(온라인 경로의 all-or-nothing 400 은 유지) | integration | P0 | YoloAutolabelStep.java:301-317 |
| TC-BATCH-145 | SAM box 프롬프트가 clamp 좌표를 공유 (신규) | polygon=true | run | `BbHint` 에 정규화 좌표를 싣는다 — DB BBOX 가 없는 경우(bbox 스킵·폴리곤 전용 프리셋) hint 가 그대로 프롬프트가 되던 누수 차단 | integration | P0 | YoloAutolabelStep.java:358-366 |
| TC-BATCH-146 | 퇴화 시 bbox·polygon 동시 스킵 (신규) | 정규화 실패/퇴화 | run | `continue` 로 bbox 저장과 hint 생성을 **둘 다** 건너뜀 | unit | P0 | YoloAutolabelStep.java:301-317 |
| TC-BATCH-147 | 해상도 측정 불가 시 상한 생략(fail-open) (신규) | frameBoundsResolver empty | normalizeBbox(points, null) | 상한 clamp 생략, 하한(0) clamp 만 적용 — 치수 측정 실패가 정상 좌표를 잘라내지 않음 | unit | P1 | DetectionBoxNormalizer.java:41-49,72-78 · YoloAutolabelStep.java:278-279 |
| TC-BATCH-148 | 검출 0건 프레임은 해상도 해석 자체를 안 함 (신규) | detections empty | run | `frameBounds=null`(resolver 미호출) — 불필요한 이미지 stat 제거 | unit | P2 | YoloAutolabelStep.java:278-279 |
| TC-BATCH-149 | 라벨셋 버전 bump 범위 = 라벨이 실제 생성된 프레임만 (신규) | 일부 프레임만 검출 | run 종료 시 | `bumpLabelVersionIn(labeledFrames)` — 영상 전 프레임 bump(구 `bumpLabelVersionByRawSn`) 폐기 | integration | P1 | YoloAutolabelStep.java:202,341,385-386 · LsDataSrcRepository.java:292-295 |

## B-10. 비디오 스트리밍 (Range, 비식별본만 서빙)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-STREAM-B01 | 비식별 미완료 → NOT_FOUND(원본 차단) | resolveStreamMeta null | stream | NOT_FOUND | security | P0 | VideoStreamService.java:230-234 |
| TC-STREAM-B02 | 영상 미존재 → NOT_FOUND | findById empty | resolveDeidLocation | NOT_FOUND | unit | P1 | VideoStreamService.java:550-551 |
| TC-STREAM-B03 | deIdntfYn='F'/'N' → null(신고본/미수행 거부) | deIdntfYn≠Y | resolveDeidLocation | null → NOT_FOUND | security | P0 | VideoStreamService.java:554-558 |
| TC-STREAM-B04 | ★비식별 경로가 허용 base 전부의 밖 → **NOT_FOUND**(CWE-22, 구 FORBIDDEN 서술 정정) | base 밖 | resolveSafe(allowedDeidBases) | `resolveSafe` 가 던지는 코드는 **NOT_FOUND**다 — "존재/권한 여부를 응답으로 구분해주지 않는 편이 원본 미노출 정책과 동급"이라는 판단(같은 메서드 javadoc). 클래스 상단 javadoc 등 일부 주석은 여전히 "FORBIDDEN" 으로 남아 있으나 코드와 불일치하는 구 주석이다 | security | P0 | VideoStreamService.java:633-656 |
| TC-STREAM-B05 | 비식별 파일 부재 → NOT_FOUND | 파일 미존재 | resolveStreamMeta | NOT_FOUND(캐시 안 됨) | integration | P1 | VideoStreamService.java:369-376 |
| TC-STREAM-B06 | Range 없음 → 200 전체+Accept-Ranges | 헤더 없음 | stream | 200, Accept-Ranges:bytes | unit | P1 | VideoStreamService.java:284-291 |
| TC-STREAM-B07 | Range 유효 → 206 Partial+청크 상한 | bytes=0- | stream | 206, `min(start+chunk-1, rangeEnd)` | unit | P0 | VideoStreamService.java:246-281 |
| TC-STREAM-B08 | Range 문법 오류 → 416 | bytes=999-0 / bytes=abc | stream | 416 + Content-Range */total | unit | P1 | VideoStreamService.java:246-254 |
| TC-STREAM-B09 | Range start≥total → 416 | start≥len | stream | 416 fail-secure | unit | P1 | VideoStreamService.java:259-264 |
| TC-STREAM-B10 | 청크 상한: 미설정/<1MB → 8MB | streamChunkSize<1MB | effectiveChunkSize | DEFAULT 8MB | unit | P1 | VideoStreamService.java:323-328 |
| TC-STREAM-B11 | 청크 상한: >64MB → 64MB(오버플로 방지) | 비정상 대형 | effectiveChunkSize | 64MB 클램프 | security | P1 | VideoStreamService.java:68,323-328 |
| TC-STREAM-B12 | 서명 URL: 비식별 무효 → NOT_FOUND | deIdntfYn≠Y / 신고 'F' | issueSignedUrl | NOT_FOUND | security | P1 | VideoStreamService.java:175-181 |
| TC-STREAM-B13 | 서명 URL: 시크릿 미설정 → 503 | !isConfigured | issueSignedUrl | SERVICE_UNAVAILABLE(fail-closed) | unit | P1 | VideoStreamService.java:195-201 |
| TC-STREAM-B14 | 서명 URL: userNo 바인딩(재사용 차단) | 정상 | issueSignedUrl | `sign(rawSn,userNo,nonce)` + `?u=` — u/exp/sig/rawSn 변조·인코딩 우회 전부 401 | security | P1 | VideoStreamService.java:202-206 |
| TC-STREAM-B15 | ★스트림 인가: 역할·채널 게이트 + **영상 단위 배정 검증**(B-ISSUE-63 해소, 구 "검증 없음" 서술 폐기) | @PreAuthorize | GET /stream | `@PreAuthorize`(STREAM_SIGNED 또는 REVIEWER/WORKER) 통과 후 `labelAccessGuard.verifyRawAccess(rawSn, actor)` 를 명시 호출 — 미배정 WORKER 는 이제 403 | security | P1 | VideoController.java:279,284-289 |
| TC-STREAM-B16 | 스트림 메타 캐시: null 미캐싱 | 비식별 완료 후 | `@Cacheable(unless="#result == null")` | stale NOT_FOUND 고정 안 됨 | integration | P1 | VideoStreamService.java:357 |
| TC-STREAM-B17 | 스트림 메타 캐시 무효화: 신고('F') 후 즉시 | deident report | evictAfterCommit | 옛 노출본 서빙 안 됨. 대상은 **그 영상 하나**(파생 캐시 미변경 — 확정 정책) | security | P0 | DeidentReportService.java:244-250 |
| TC-STREAM-B18 | ★게이트 뒤 미디어 응답은 `Cache-Control: no-store` (신규) | Range 유/무 | GET /stream | 200·206 **양쪽** 모두 `no-store`. 검증자(ETag/Last-Modified)가 없어 `no-cache` 로는 이득 없이 디스크 캐시 잔존 위험만 남으므로 `no-store` 로 통일 | security | P0 | VideoStreamService.java:280,290,311-313 |
| TC-STREAM-B19 | 신고 게이트가 캐시 **앞**(매 요청)에서 평가 (신규) | stream-meta 캐시 warm 상태에서 신고 접수 | GET /stream | `deidentReportGate.isUnderDeidentReport` 를 캐시 조회 전에 수행 → 즉시 404 | security | P0 | VideoStreamService.java:147-152 |
| TC-STREAM-B20 | 비식별 base 2-way allowlist (신규) | 비식별본이 co-locate 위치 | GET /stream | 구 위치 ∪ co-locate 모두 허용 — 프레임 추출기와 **동일 축**(가드 이원화 제거) | integration | P0 | VideoStreamService.java:580-600 · VideoArtifactRootResolver.java:337-348 |
| TC-STREAM-B21 | ★신고 구간 스트리밍만 404(412 아님) (신규) | `DE_IDNTF_YN='F'` | GET /stream · /stream-url | **404**. 라벨 조회·프레임 이미지 등은 412 인데 스트리밍만 404 인 것은 그 엔드포인트의 기존 규약이며, 코드가 갈리면 응답이 영상 상태 오라클이 된다(CWE-209) | security | P0 | VideoStreamService.java:147-152,178-181 |
| TC-STREAM-B22 | ★파생영상은 자기 rawSn 게이트만 판정 (신규) | 부모가 신고 구간, 파생은 `'Y'` | GET /videos/{파생rawSn}/stream | 정상 서빙(200/206). 조상 전파는 도입 후 철회됐고 이는 확정 정책의 필연적 귀결이다 — 결함으로 재분류하지 않는다 | security | P0 | DeidentReportGate.java:25-39,66 |

## B-11. 비식별 누락 신고 (srcSn / rawSn, resolve)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-DEID-030 | 신고: reason blank → INVALID_INPUT | null/blank | report | 400(@Valid 선차단) | unit | P2 | DeidentReportController.java:@Valid · DeidentReportRequest |
| TC-DEID-031 | 신고: WORKER 본인 배정만(IDOR) | WORKER 타인 srcSn | report | 403 | security | P0 | DeidentReportService.java:118-133 |
| TC-DEID-032 | 신고: 부모 RAW PESSIMISTIC_WRITE 락(PII TOCTOU) | 동시 증강 콜백 | doReport | `findByRawSnForUpdate` 로 직렬화 | security | P0 | DeidentReportService.java:178-180 |
| TC-DEID-033 | 신고: 이미 잠금 → CONFLICT | isRawLocked | doReport | 409 | integration | P1 | DeidentReportService.java:188-191 |
| TC-DEID-034 | ~~신고: 전체 라벨 스냅샷+삭제+'F'~~ | — | — | **[폐기 2026-07-30]** ★정책 반전(2026-07-27 사용자 확정) — 신고 시 **라벨을 삭제하지 않고 보존**하며 `LS_LABEL_VERSION` 스냅샷도 남기지 않는다. 신고 구간 PII 노출은 조회 게이트(412)로 차단한다. 대체 케이스 TC-DEID-051 | integration | — | DeidentReportService.java:196-204 |
| TC-DEID-035 | 신고: 개인정보 3필드 리셋(stale PII 방지) | 값 존재 | resetPrivacyMetaByRawSn | 익명/가명/PII 3필드 NULL 초기화(벌크 JPQL) | security | P1 | DeidentReportService.java:218-219 |
| TC-DEID-036 | 신고: 락 UNIQUE 위반 → CONFLICT(동시) | DataIntegrityViolation | doReport | 409 | security | P1 | DeidentReportService.java:237-241 |
| TC-DEID-037 | 신고: APPROVED → TASK_MODIFIED(**META_UPDATED**) | isReviewApproved | doReport | `TaskModifiedEvent(ChangeType.META_UPDATED)`. 라벨이 보존되므로 구 `LABEL_DELETED` 는 폐기 | integration | P1 | DeidentReportService.java:230-234 |
| TC-DEID-038 | ~~신고: 라벨 0건이면 스냅샷 스킵~~ | — | — | **[폐기 2026-07-30]** 스냅샷 로직 자체가 제거되어 스킵 분기가 존재하지 않는다(TC-DEID-034 와 동일 사유) | unit | — | DeidentReportService.java:196-204 |
| TC-DEID-039 | resolve: actor null → UNAUTHORIZED | null | resolveManually | UNAUTHORIZED | unit | P2 | DeidentReportService.java:353-360 |
| TC-DEID-040 | resolve: 신고 없음 → NOT_FOUND | empty | resolveManually | NOT_FOUND | unit | P2 | DeidentReportService.java:353-365 |
| TC-DEID-041 | resolve: OPEN 아님 → CONFLICT | RESOLVED/DISMISSED | resolveManually | CONFLICT | unit | P1 | DeidentReportService.java:365-370 |
| TC-DEID-042 | resolve: 산출물 미검증 → CONFLICT(fail-closed) | verify 실패 | resolveManually | CONFLICT, OPEN/락/'F' 전부 유지(롤백) | security | P0 | DeidentReportService.java:372,542-590 |
| TC-DEID-043 | resolve: 성공 → RESOLVED+락해제+'F'→'Y' | 검증 통과 | resolveManually | 'Y' 복원(게이트 자동 해제, 보존된 기존 라벨 그대로 재사용) | integration | P0 | DeidentReportService.java:372-400 |
| TC-DEID-044 | verifyArtifact: 경로 없음/파일 부재 → 거부 | null/파일 없음 | verifyDeidentArtifact | CONFLICT | security | P1 | DeidentReportService.java:542-570 |
| TC-DEID-045 | verifyArtifact: 시간조건(신고 후 재비식별) 미충족 | mtime ≤ 신고시각(스큐 60s) | verifyDeidentArtifact | 거부(옛 비식별본 배제) | security | P0 | DeidentReportService.java:542-590 |
| TC-DEID-046 | resolve: 배치단계 역행 안 함(CWE-664) | COMPLETED 후 신고 | resolveManually | 'Y' 만 복원, DATA_STTS_CD 유지 | integration | P1 | DeidentReportService.java:380-400 |
| TC-DEID-047 | resolveOpenReports(자동): OPEN 일괄 RESOLVED | 배치 비식별 성공 | resolveOpenReports | resolve + releaseRaw + 캐시 evict(멱등) | integration | P1 | DeidentReportService.java:443-470 |
| TC-DEID-048 | 신고 목록: status allowlist 밖 → 400 | "X" / SQLi 문자열 | listReports | INVALID_INPUT(@Pattern + 서비스 정규화 이중 방어) | unit | P2 | DeidentReportService.java:418-440 |
| TC-DEID-094 | ★신고 목록: 신고자 표시명 `reporterName` (신규 2026-08-04) | 신고자 `USER_NO` 가 `MNG_ACCT_USER` 에 존재 | `GET /v1/deident-reports` | 행마다 `reporterName`=`USER_NM` 채워짐. `reporterNo` 원값도 그대로 유지(하위호환 — 필드 추가만). 이름 해석은 페이지의 `USER_NO` **단일 IN 쿼리** 1회(`findByUserNoIn`) — 행마다 조회하는 N+1 금지 | integration | P1 | DeidentReportService.java:451-482 · DeidentReportListResponse.java:41-52 |
| TC-DEID-095 | 신고 목록: 마스터에 없는 신고자 → 표시명 null (fail-soft, 신규 2026-08-04) | 탈퇴·관제 계정 삭제로 `MNG_ACCT_USER` 행 부재 | `GET /v1/deident-reports` | **200 + `reporterName=null`** — 이름 조회 실패가 목록 조회 자체를 깨뜨리지 않는다. `reporterNo` 는 그대로 노출. `reporterNo` 가 null 인 레거시 행도 동일하게 null | security | P1 | DeidentReportService.java:456-458,467-482 |
| TC-DEID-049 | 컨트롤러: 라벨링 단계 srcSn 신고 | POST /v1/labels/{srcSn}/deident-report | report | 201, WORKER(본인 배정)/REVIEWER | integration | P1 | DeidentReportController.java:95-102 |
| TC-DEID-050 | 컨트롤러: resolve — WORKER 본인/REVIEWER 전체 | POST /v1/deident-reports/{rprtSn}/resolve | resolve | 200/403/409 | integration | P1 | DeidentReportController.java:145-150 |
| TC-DEID-051 | ★라벨 보존 — 신고 후 라벨·이력 불변 (신규) | 라벨 N건 보유 영상 | 신고 201 | `LS_DATA_LBL` 행 수·내용 불변, `LS_LABEL_VERSION` 신규 행 0, 라벨셋 버전 bump 도 없음. 신고 구간 노출은 조회 게이트(412)가 담당 | integration | P0 | DeidentReportService.java:196-204 |
| TC-DEID-052 | 개인정보 3필드 리셋의 행 단위 감사 (신규) | 3필드 값 보유 프레임 M건 | 신고 201 | 리셋 **직전**에 대상 srcSn 을 확정하고 `LS_DATA_LBL_HSTRY` 에 프레임당 1행(`recordPrivacyMetaResetEvent`). 라벨 델타 0건이라 V139 필터로 `V_COMPLETED_LABEL_CHANGE` 에는 미노출 | security | P0 | DeidentReportService.java:218-227 · V139__filter_zero_change_rows_from_change_view.sql |
| TC-DEID-053 | ★파생영상 신고는 412 로 거부 (신규) | `ORGNL_RAW_SN` non-null | POST 신고(srcSn/rawSn 양쪽) | **412 PRECONDITION_FAILED**. 신고 행 미생성, REVIEWER 알림 없음, **부모 rawSn 미노출**(원본으로 유도하지 않음). 사용자 사유는 `LogSanitizer` 정제 후 WARN 감사 로그로만 보존 | security | P0 | DeidentReportService.java:182-183,295-310 |
| TC-DEID-054 | 비식별 미수행('N'/null) 신고 412 (신규) | `DE_IDNTF_YN='N'`/null | POST 신고 | 412. `'F'`(신고·실패)는 별도 경로(작업락 409)로 처리 | security | P0 | DeidentReportService.java:185-186,329-340 |
| TC-DEID-055 | ★마킹 단계 rawSn 신고 신설 (신규) | 마킹 화면(프레임 컨텍스트 없음) | POST /v1/videos/{rawSn}/deident-report | 201, WORKER/REVIEWER. UNCERTAINTIES #2("미구현") 해소 | integration | P0 | DeidentReportController.java:122-129 · DeidentReportService.java:147 |
| TC-DEID-056 | rawSn·srcSn 두 경로가 동일 본체(`doReport`) (신규) | 두 진입점 | report / reportByVideo | 파생 412·비식별 미수행 412·작업락·`'F'` 전이·개인정보 3필드 리셋·스트림 캐시 무효화 **부수효과가 동일** | integration | P0 | DeidentReportService.java:118,147,169 |
| TC-DEID-057 | resolve 성공 시 `DeidentGateReopenedEvent` 항상 발행 (신규) | 미승인 영상 포함 | resolveManually | 승인 여부와 무관하게 발행 — 보류된 VLM 위탁 재개 신호가 미승인 영상에도 도달해야 하기 때문 | integration | P0 | DeidentReportService.java:480-491 |
| TC-DEID-058 | resolve 성공 + APPROVED 일 때만 `DeidentReportResolvedEvent` (신규) | 검수 승인 영상 | resolveManually | export 재산출·관제 재통지용. 미승인이면 미발행(불필요한 v1 생성 방지) | integration | P0 | DeidentReportService.java:483-493 |
| TC-DEID-059 | ★게이트 판정 범위 = 자기 rawSn 행 하나 (신규) | 부모/자손 트리 | `DeidentReportGate.isUnderDeidentReport(rawSn)` | `DE_IDNTF_YN` **단일 컬럼** projection 1회 조회. `ORGNL_RAW_SN` 을 보지 않는다 — 조상 체인 순회·자손 캐시 evict 팬아웃은 4라운드 시도 후 전면 철회됐다(재도입 금지) | security | P0 | DeidentReportGate.java:12-39,66 |

## B-12. 재처리 / 재시도 큐 (2노드 안전 · stale 회수)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-150 | 재처리: rawSn null → INVALID_INPUT | null | retry | INVALID_INPUT(HTTP 는 `@Min` 이 선차단, 서비스 가드는 방어심도) | unit | P2 | BatchReprocessService.java:67-69 |
| TC-BATCH-151 | 재처리: 영상 미존재 → NOT_FOUND | existsById false | retry | NOT_FOUND | unit | P1 | BatchReprocessService.java:71-73 |
| TC-BATCH-152 | 재처리: FAILED→PROCESSING 원자 클레임 성공 | 배치 FAILED | retry | claim=true → clearIfIdle → orchestrator.process | integration | P0 | BatchReprocessService.java:77-87 |
| TC-BATCH-153 | 재처리: FAILED 아님/이미 클레임 → CONFLICT | claimed=false | retry | 409(이중 실행 차단) | integration | P0 | BatchReprocessService.java:77-81 |
| TC-BATCH-154 | 재처리 클레임: raw 우선, 없으면 status FAILED→PROCESSING | 두 컬럼 분리 | tryClaimReprocessFromFailed | rawClaimed 우선, 둘 다 조건부 UPDATE | integration | P1 | BatchTransitionService.java:352-374 |
| TC-BATCH-155 | 재시도 등록: 최초 실패 PENDING(ON CONFLICT DO NOTHING) | 첫 실패 | enqueueIfRetryable | `insertIfAbsent` + `findByRawSnForUpdate`, RTY_NMTM=1 | integration | P0 | BatchRetryQueue.java:67-92 |
| TC-BATCH-156 | 재시도 등록: 동시 최초 실패 UK 경쟁 흡수 | 2노드 동시 | enqueueIfRetryable | 원자 upsert → 같은 tx 내 예외 재시도 없음(PG tx abort 함정 회피) | integration | P0 | BatchRetryQueue.java:72-74 |
| TC-BATCH-157 | 재시도: 지수백오프(60,120,240…) shift 30 캡 | attempt 증가 | enqueueIfRetryable | `delaySec=initial×2^min(attempt-1,30)` | unit | P1 | BatchRetryQueue.java:85-88 |
| TC-BATCH-158 | 재시도: max 초과 → EXHAUSTED false | attempt>max | enqueueIfRetryable | markExhausted(삭제 아님, 이력 보존) + false | integration | P0 | BatchRetryQueue.java:79-84 |
| TC-BATCH-159 | 폴링 클레임: 2노드 동시 폴링 직렬화 | 도래 후보 다수 | pollReady | `claimAtomically`(PENDING→RETRYING CAS) 1행만. **클레임 후 노드 사멸분은 stale 회수 스윕이 되살린다**(TC-BATCH-164 — B-ISSUE-83 해소) | integration | P0 | BatchRetryQueue.java:101-112 |
| TC-BATCH-160 | 재시도 등록 노드 ≠ 발화 노드(DB 영속) | 2노드 A-A | enqueue/poll | 인메모리 큐 없음 — 다른 노드가 DB 에서 클레임 | integration | P0 | BatchRetryQueue.java:16-34 |
| TC-BATCH-161 | clearIfIdle: RETRYING 보존, PENDING/EXHAUSTED만 삭제 | 수동 재처리 | clearIfIdle | 자동 폴러 부기 보존 | integration | P1 | BatchRetryQueue.java:141-147 |
| TC-BATCH-162 | clear: 성공 시 전체 삭제 | 배치 성공 | clear | deleteByRawSn | unit | P2 | BatchRetryQueue.java:126-131 |
| TC-BATCH-163 | 재처리 진입 가드 SKIPPED → 보상 롤백 + 409 (신규) | 작업상태 검수 소유 | retry | `process` 가 SKIPPED 반환 → `releaseReprocessClaim` 으로 LS_DATA_RAW PROCESSING 을 되돌리고 **409** 로 명시 거부. 보상이 없으면 stage 가 영구 PROCESSING 으로 고착돼 이후 재처리가 전부 409 | integration | P0 | BatchReprocessService.java:89-101 · BatchTransitionService.java:391-413 |
| TC-BATCH-164 | ★stale RETRYING 회수 — PENDING 복귀 (신규) | `MDFCN_DT < cutoff` 인 RETRYING | sweepStaleRetrying(cutoff, batchSize) | 조건부 UPDATE 로 PENDING 복귀 + `RTY_NMTM+1`(죽은 시도를 1회로 계상). 정상 처리 중 항목은 cutoff 를 넘지 않아 대상 아님 | integration | P0 | BatchRetryQueue.java:174-192 |
| TC-BATCH-165 | stale 회수: 상한 도달분은 EXHAUSTED 종결 (신규) | RTY_NMTM ≥ MAX_RTY_NMTM | sweepStaleRetrying | 복귀시키지 않고 EXHAUSTED — 무한 부활 차단 | integration | P0 | BatchRetryQueue.java:186-191 |
| TC-BATCH-166 | stale 임계 하한 clamp 30분 (신규) | stale-timeout-minutes=0/음수/5 | 부트 | `MIN_STALE_TIMEOUT_MINUTES`(30) 로 clamp — 과소 설정이 정상 처리 중 항목을 뺏어가지 않음 | unit | P0 | BatchRetryStaleReclaimSweeper.java:64,96 |
| TC-BATCH-167 | stale 스윕은 자기 토글만 본다 + 전용 daemon executor (신규) | `authoring.batch.retry.stale-reclaim.enabled` | 부트 | 기본 true, 자기 토글 off 면 미등록. `@Scheduled`/`@EnableScheduling` 을 쓰지 않아 게이팅 없는 남의 잡을 깨우지 않는다 | integration | P0 | BatchRetryStaleReclaimSweeper.java:42-50,84,102-110 |
| TC-BATCH-168 | stale 스윕 예외는 삼킴(Throwable) (신규) | 스윕 중 예외 | run() | ERROR 로그(클래스명만) 후 정상 반환 — 스케줄러 사멸 방지 | unit | P1 | BatchRetryStaleReclaimSweeper.java:142-156 |

## B-13. KPST 비식별 위탁·폴링 (원본 가드 · 무결성 · 원자 클레임)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-DEID-060 | ★위탁: **선커밋 후 비동기 dispatch**(Phase C-2, 구 "createProject → WAITING" 동기 서술 폐기) | 유효 raw | submit | `txService.issueSubmitLedger` 로 **WAITING(prjId=null) 을 먼저 독립 커밋**한 뒤 `createProject` 를 비동기로 dispatch(`dispatchSubmit`/`subscribeSubmit`) — prjId 는 이후 `KpstSubmitOutcomeRecorder.onAccepted`→`KpstDeidentTxService.recordSubmitAck` 가 비동기로 기록한다. `markKpstSubmitted` 라는 메서드는 이 흐름에 없다 | integration | P0 | KpstDeidentService.java:258-273,288,375-431 · KpstSubmitOutcomeRecorder.java:44-57 · KpstDeidentTxService.java:102-113 |
| TC-DEID-061 | 위탁: 원본경로 부모 없음 → INVALID_INPUT(CWE-22) | parent null | buildProjectRequest | INVALID_INPUT(전제조건 검증 — 외부 호출 이전 동기 실패) | security | P1 | KpstDeidentService.java:318-323 |
| TC-DEID-062 | ★위탁: **createProject 실패는 비동기 보상**(Phase C-2, 구 "예외 전파" 서술 폐기) | createProject 예외 | dispatchSubmit | 전제조건 실패(`buildProjectRequest`)만 `submit()` 호출자에게 동기 EXTERNAL_API_ERROR 로 전파된다(292-303). **실제 `createProject` 호출 실패는 `KpstSubmitOutcomeRecorder.onSubmitFailed`→`KpstDeidentTxService.failSubmit` 가 비동기 처리**하며 `submit()` 호출자에게 예외를 되돌리지 않는다(논블로킹 재설계의 의도된 지점) | integration | P0 | KpstDeidentService.java:292-303 · KpstSubmitOutcomeRecorder.java:69-73 · KpstDeidentTxService.java:163-184 |
| TC-DEID-063 | 위탁: export 디렉터리 정리(stale 오회수 방지) | 재위탁 | cleanExportDir | 리졸버로 **재계산한 경로와 정확히 일치**할 때만 진행, 바로 아래 정규파일만 비재귀 삭제, 심링크 미추종 | security | P1 | KpstDeidentService.java:488-527 |
| TC-DEID-064 | 폴링: procState=2 전체완료(AND) → 다운로드+완료 | 모든 데이터셋 완료 | pollOne | allDatasetsCompleted → downloadResult → finishDownloadAndComplete | integration | P0 | KpstDeidentService.java:600-664 |
| TC-DEID-065 | 폴링: 터미널 실패(3/4/99) 우선 → 즉시 'F' | procState∈{3,4,99} | pollOne | `anyDatasetFailed` 를 완료 판정 **앞에서** 평가 → failPolling(타임아웃 대기 안 함) | integration | P0 | KpstDeidentService.java:585-598 |
| TC-DEID-066 | 폴링: procState=99(오류 sentinel) 종결 | procState=99 | pollOne | 터미널 실패 종결(`PROC_STATE_TERMINAL_FAILED`) | integration | P1 | KpstDeidentService.java:96-98 |
| TC-DEID-067 | ★폴링: prjId null(위탁 미완) → **ACK 유예 판정**(Phase C-2, 구 "타임아웃 검사만" 서술 폐기) | prjId null | pollOne | `markTimeoutIfExpired` 가 아니라 `withinSubmitAckGrace`(ACK 대기 유예)를 확인하고, 유예 만료 시 `txService.failSubmit(..., ACK_MISSING_CODE, ...)` 로 종결한다 — 비동기 제출 재설계로 도입된 별도 메커니즘 | unit | P2 | KpstDeidentService.java:541-560 |
| TC-DEID-068 | 폴링: retrieveProgress 예외 → 타임아웃 평가 | 5xx/서킷오픈 | pollOne | 예외 경로에서도 markTimeoutIfExpired(무기한 stuck 차단) | integration | P0 | KpstDeidentService.java:565-575 |
| TC-DEID-069 | 폴링: 완료지만 fileName bad → 터미널 처리 | downloadResult 예외 | pollOne | REDEIDENT/비REDEIDENT 분기 terminal(락 해제 포함) | integration | P0 | KpstDeidentService.java:611-625 |
| TC-DEID-070 | 폴링: 산출물 무결성 실패 → 'F' | isUsableDeidFile false | pollOne | failPolling — 'Y' 위장 금지 | integration | P0 | KpstDeidentService.java:634-648 |
| TC-DEID-071 | 폴링: 진행중 → 시도 증가+타임아웃 검사 | 미완료 | pollOne | recordPollingProgress + markTimeoutIfExpired | integration | P1 | KpstDeidentService.java:666-668 |
| TC-DEID-072 | fileName 회수: {stem}-mask{ext} 변환(실측 계약) | fileName=원본 절대경로 | sanitizeFileName → toMaskName | basename 추출 후 `001.mp4` → `001-mask.mp4`. 이미 `-mask` 로 끝나면 재부여 안 함 | unit | P0 | KpstDeidentService.java:764-791,812-822 |
| TC-DEID-073 | fileName 회수 폴백: 단일 산출물 스캔 | 1차 경로 미사용 | scanSingleUsable | 1개=회수 / 0개=null / 2개↑=INVALID_INPUT(모호 → terminal) | integration | P1 | KpstDeidentService.java:833-857 |
| TC-DEID-074 | sanitizeFileName: basename만(CWE-22) | 절대경로·NUL·상위참조 | sanitizeFileName | basename 추출, `/`·`\`·`..` 잔존 시 INVALID_INPUT(원문 미노출) | security | P1 | KpstDeidentService.java:869-890 |
| TC-DEID-075 | 폴링 잡: 대상 없으면 noop | empty | KpstDeidentPollJob.execute | DEBUG, 외부 미호출 | unit | P2 | KpstDeidentPollJob.java:93-98 |
| TC-DEID-076 | 폴링 잡: 건별 try/catch 격리 | 한 건 예외 | execute | 다른 건 계속(CWE-209 클래스명만) | integration | P1 | KpstDeidentPollJob.java:110-114 |
| TC-DEID-077 | 폴링 잡: 동시 실행 금지는 **같은 노드 한정** | 동시 tick | @DisallowConcurrentExecution | 같은 스케줄러 인스턴스만 직렬화. 노드 간 중복 폴링은 **리스 기반 원자 클레임**(TC-DEID-085)이 막는다 — Quartz 설정에 의존하지 않는 방어 | integration | P1 | KpstDeidentPollJob.java:26-40,43 |
| TC-DEID-078 | 폴링 잡: 재기동 복원(DB 조회) | 인메모리 유실 | findByPollSttsCdIn(statuses, page) | WAITING/POLLING 을 DB 에서 재조회해 재개(인메모리 상태 없음) | integration | P1 | KpstDeidentPollJob.java:93-94 · LsDeidentProcLogRepository.java:55 |
| TC-DEID-079 | completeDeidentification: 파일무효 F-마킹 보정 | verifyDeidFile 실패 | completeDeidentification | REQUIRES_NEW `markRawDeidentFailed`(메인 tx 롤백에 휩쓸리지 않음) | integration | P1 | KpstDeidentService.java:898-911 · KpstDeidentTxService.java:269-273 |
| TC-DEID-080 | KPST 조건부 빈: kpst.deid.enabled=false 미등록 | 토글 off | @ConditionalOnProperty | 서비스·Tx서비스·트리거 빈 모두 미등록 | integration | P2 | KpstDeidentService.java:69 |
| TC-DEID-081 | ★위탁 전 원본 실재 가드 (신규) | rawFilePathNm 이 실재하지 않음 | submit → verifySourceOrFail | `verifySourceOrFail` → `recordDeidentFailure`(REQUIRES_NEW 로 'F' **커밋**) 후 INVALID_INPUT. createProject 미호출. 로그·예외에 경로 원문 없음. 구 결함(원본 없는 영상이 18B 스텁으로 'Y'+MARKING_READY) 회귀 방지 | security | P0 | KpstDeidentService.java:286,446-466 |
| TC-DEID-082 | 원본 가드 비활성 시 침묵 금지 (신규) | `verifySourceExists=false` | verifySourceOrFail | WARN 1줄(영상당 1회) 후 통과 — 미검증 위탁이 추적 가능 | unit | P1 | KpstDeidentService.java:447-450 |
| TC-DEID-083 | ★산출물 무결성 판정 단일 원천 (신규) | 18바이트 텍스트 스텁 | isUsableDeidFile | `DeidentArtifactIntegrity.isValidVideoArtifact` = 정규파일 + **≥512B** + 컨테이너 시그니처(ISO-BMFF box / MPEG-TS sync) → false. 구 "존재+>0바이트" 판정 폐기 | security | P0 | KpstDeidentService.java:692-694 · DeidentArtifactIntegrity.java:43-108 |
| TC-DEID-084 | 무결성 실패 시 1회 유예 재확인 (신규) | 후보 파일은 있는데 판정만 실패 | recheckAfterGrace | `kpst.deid.result-recheck-delay-ms`(상한 5s clamp) 대기 후 1회 재판정. **파일이 아예 없으면 유예 없이 즉시 종결** | integration | P1 | KpstDeidentService.java:628-632,707-730 |
| TC-DEID-085 | ★폴링 대상 원자 클레임(리스) (신규) | 2노드 동시 tick | tryClaimPoll(procLogSn, leaseCutoff) | 조건부 UPDATE 로 `POLL_LAST_DT` 를 갱신한 **1행만** 폴링. `FOR UPDATE SKIP LOCKED` 는 외부 HTTP·파일 I/O 가 tx 밖이라 보호 구간을 못 덮으므로 채택하지 않는다 | integration | P0 | KpstDeidentPollJob.java:101-109 · LsDeidentProcLogRepository.java:57-92 · KpstDeidentTxService.java:61-66 |
| TC-DEID-086 | 클레임 술어 fail-closed (신규) | 그 사이 DOWNLOADED/FAILED 전이 | claimForPoll | `POLL_STTS_CD IN ('WAITING','POLLING')` 위반 → 0행(클레임 실패) | integration | P0 | LsDeidentProcLogRepository.java:84-89 |
| TC-DEID-087 | 리스 길이 = 폴링 주기 − 5s(하한 1s) (신규) | 단일 노드 | leaseSeconds() | 리스가 주기보다 짧아 단일 노드는 매 틱 그대로 재클레임. 크래시 시에도 만료로 자동 회수(별도 잠금 컬럼·회수 잡 불요) | unit | P1 | KpstDeidentPollJob.java:57-74,125-128 |
| TC-DEID-088 | ★완료 전이 자체가 클레임 (신규) | 동시 2노드 완료 시도 | claimDownloadCompletion | 1행 얻은 호출만 후처리(프레임 attach·Y 전이·락 해제·알림). 재호출은 0행 → **프레임 이중 attach 창 없음** | integration | P0 | LsDeidentProcLogRepository.java:94-125 · KpstDeidentTxService.java:206-223 |
| TC-DEID-089 | 완료 후처리 실패 시 클레임도 롤백 (신규) | 후처리 예외 | finishDownloadAndComplete | 트랜잭션 롤백으로 클레임 해제 → 재폴링 대상 유지(fail-safe) | integration | P0 | KpstDeidentTxService.java:206-223 |
| TC-DEID-090 | 틱당 대상 상한 + 기아 방지 정렬 (신규) | 대기 건 누적 | pollPage() | `kpst.deid.poll-batch-size`(기본 200, 오설정 시 `DEFAULT_BATCH_SIZE`), 미폴링 우선 → `POLL_LAST_DT` 오름차순 | integration | P1 | KpstDeidentPollJob.java:54-55,88-89,118-124 |
| TC-DEID-091 | 회수 디렉터리 2-way (신규) | co-locate 산출 | recoveryDirs | 신 위치(`deidVideoDirQuietly`) 우선 + 구 위치(`{deid_base}/videos/{rawSn}`) 폴백. 신 위치 도출 실패해도 구 위치는 계속 시도 | integration | P0 | KpstDeidentService.java:797-802 |
| TC-DEID-092 | 1차 mask 경로 miss → 폴백 회수 시 WARN (신규) | 산출물 명명 드리프트 | downloadResult | `primary mask path miss — recovered by fallback scan rawSn=…` WARN(경로 원문 미노출). 계약 드리프트가 조용히 지나가지 않는다(B-ISSUE-84 관측성) | integration | P1 | KpstDeidentService.java:764-791 |
| TC-DEID-093 | DeidentFrameAttacher 도 동일 무결성 판정 사용 (신규) | 재비식별 프레임 attach | attachDeidentFrames | `DeidentArtifactIntegrity` 로 통일 — 무결성 판정이 두 벌로 갈라지지 않음 | integration | P0 | DeidentFrameAttacher.java:151-162 |

## B-14. Quartz 클러스터링 / 인프라 / 헬스

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-170 | Quartz JobStore = controlDataSource(PG) | AutoConfig exclude | QuartzConfig | controlDataSource 주입, `PostgreSQLDelegate` + `useProperties=true` | integration | P1 | QuartzConfig.java:31-38 · application.yml(quartz) |
| TC-BATCH-171 | ★2노드 A-A: 동일 잡 중복 발화 방지 | stg/prd 프로파일 | 스캔/폴링/sweep 트리거 | `isClustered` 기본값이 **stg/prd 는 true**(공통 yml 은 false) → 클러스터 전체에서 1회만 발화. ⚠ 클러스터링은 **트리거 중복 발화만** 막고 잡 내부 레이스는 원자 클레임이 별도로 막는다 | integration | P0 | application-stg.yml:11 · application-prd.yml:13 · application.yml:95-96 |
| TC-BATCH-172 | AsyncBatchRunner: 예외 삼킴(@Async) | orchestrator 예외 | runAsync | ERROR 로그, 정상 종료 | unit | P2 | AsyncBatchRunner.java:22-34 |
| TC-BATCH-173 | ★클러스터링 fail-closed — 기동 거부 (신규) | `ENV=prd` 또는 stg/prd 프로파일 + `QUARTZ_CLUSTERED=false` | 부트(@PostConstruct) | `IllegalStateException` 로 **기동 실패**. WARN 은 배포 로그에 묻히므로 경고가 아니라 차단이다 | security | P0 | QuartzClusteringGuard.java:64-95 |
| TC-BATCH-174 | 판정은 allowlist `containsAll` (신규) | 프로파일 `local,prd` / `prd1` / `LOCAL` / 미지정 | verify | 전부 엄격(거부) — denylist 가 아니므로 오타·대소문자·혼합·미지정이 자동으로 fail-closed | security | P0 | QuartzClusteringGuard.java:53,97-103 |
| TC-BATCH-175 | ENV 배포 표식이 독립 축 (신규) | `SPRING_PROFILES_ACTIVE=dev` + `ENV=prd` | verify | 거부 — 프로파일을 낮춰도 배포 표식이 이긴다(`DevProfileGuard.DEPLOYED_ENVS` 와 동일 기준) | security | P0 | QuartzClusteringGuard.java:30-32,84-103 · DeployedEnvironmentDetector.java:63-70 |
| TC-BATCH-176 | 값 출처는 Quartz 실 프로퍼티 (신규) | `QUARTZ_CLUSTERED` 변경 | verify | `spring.quartz.properties.org.quartz.jobStore.isClustered` 실효값을 직접 읽어 설정 우회 불가 | unit | P1 | QuartzClusteringGuard.java:49,65 |
| TC-BATCH-177 | 클러스터링 ≠ 잡 내부 레이스 방어 (신규) | 클러스터링 on | KPST 폴링·재시도 큐·export sweep | 각 잡의 원자 클레임(조건부 UPDATE)이 여전히 필요 — 두 방어는 서로 대체하지 않는다 | integration | P0 | QuartzClusteringGuard.java:39-40 |
| TC-BATCH-178 | ⚠ batchAsyncExecutor 는 공유 싱글턴 (신규) | 동시성 케이스 전제 | AsyncConfig | core2/max4/queue50/CallerRunsPolicy 단일 풀을 **비식별·배치·증강·export·해상도·VLM 재개·영상메타** 가 공유한다. "유휴" 판정은 풀 전체 대상이라 테스트 병렬화 시 서로 간섭한다 | integration | P0 | AsyncConfig.java:32-47 |
| TC-BATCH-179 | ★비식별 헬스체크 3분기 · root 핑 (근거 라인 범위 정정 — 파일은 114줄뿐) (신규) | mock / kpst / 미구성 | GET /actuator/health(deidentify) | mock=핑 0건 UP(mode=mock) / kpst=`kpstDeidWebClient` 로 **루트(`/`)** 핑(벤더가 `/health` 미제공) / 둘 다 아님=DOWN(fail-closed). 예외는 클래스명만 노출 | integration | P0 | DeidentifyHealthIndicator.java:13-40,75-113 |

## B-15. 배치 스텝 트랜잭션 경계 (5스텝 + 정적 드리프트 가드) — 신규 섹션

> ★ CRITICAL 회귀 방지. 호출자(`BatchOrchestrator.process`, `AsyncDeidentifyRunner.runAsync`)는 **둘 다 트랜잭션이 없다**.
> 구 결함: `execute` 가 `@Transactional` 이 붙은 typed 메서드를 **자기호출**해 프록시를 우회 → 단계 전체가 무-트랜잭션 →
> `@Modifying` 벌크 DML 이 "Executing an update/delete query" 로 실패(YOLO 단계 전량 FAILED) + dirty-update 조용히 유실.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-180 | YOLO execute 경계 — 빈 프록시 호출 시 트랜잭션 개시 (신규) | `@Transactional` 없는 IT | 빈(프록시).execute(ctx) | `bumpLabelVersionIn` 이 예외 없이 수행되어 `LBL_VER` +1. 무-트랜잭션이면 즉시 실패 | integration | P0 | YoloAutolabelStep.java:155-158 |
| TC-BATCH-181 | SAM2 execute 경계 (신규) | 동일 | 빈(프록시).execute(ctx) | REQUIRES_NEW 개시 후 폴리곤 저장 커밋 | integration | P0 | Sam2SegmentStep.java:139-150 |
| TC-BATCH-182 | TrackInterpolation execute 경계 (신규) | 동일 | 빈(프록시).execute(ctx) | REQUIRES_NEW 개시 후 stale 삭제 + 재생성 커밋 | integration | P0 | TrackInterpolationStep.java:104-106 |
| TC-BATCH-183 | VLM execute 경계는 **쓰기 가능**(readOnly 아님) (신규) | 마킹 보유 | 빈(프록시).execute(ctx) | 상위 경계가 `runWithMarking` 의 상한을 따라 readOnly 가 아니어야 마킹 전이 저장이 성립 | integration | P0 | VlmTimeseriesStep.java:205-227 |
| TC-BATCH-184 | FRAME_EXTRACT execute 경계 (신규) | 동일 | 빈(프록시).execute(ctx) | REQUIRES_NEW 개시 후 `LS_DATA_SRC` INSERT + 이력 커밋 | integration | P0 | FfmpegFrameExtractor.java:129-131 |
| TC-BATCH-185 | 내부 위임은 자기호출 — 중첩 없음 (신규) | execute → run/extractByMarks | 트랜잭션 수 관측 | 스텝 1건 = 트랜잭션 1건. 내부 위임을 프록시 경유로 바꾸면 REQUIRES_NEW 가 2회 열린다 | integration | P0 | BatchStep.java(경계 규약) · FfmpegFrameExtractor.java:111-131 |
| TC-BATCH-186 | typed 메서드의 REQUIRES_NEW 는 보존 (신규) | dev 트리거가 `run(rawSn)` 직접 호출 | 직접 호출 | 그 진입점도 트랜잭션을 얻는다 — 애노테이션을 제거하지 않는다 | integration | P1 | YoloAutolabelStep.java:179-180 · VlmTimeseriesStep.java:236-238 |
| TC-BATCH-187 | DeidentifyStep 면제(자기참조 프록시) (신규) | BOUNDARY_EXEMPT | 정적 스캔 | `execute` 무애노테이션이 정상. 추가하면 REQUIRES_NEW 2중 개시 | unit | P0 | BatchStepTransactionBoundaryTest.java:BOUNDARY_EXEMPT · DeidentifyStep.java:239-249 |
| TC-BATCH-188 | MarkingLoadStep 면제(DML 0건) (신규) | BOUNDARY_EXEMPT | 정적 스캔 | 조회 + JSON 파싱만 수행하므로 경계 불요 | unit | P1 | BatchStepTransactionBoundaryTest.java:BOUNDARY_EXEMPT · MarkingLoadStep.java:50-58 |
| TC-BATCH-189 | ★정적 드리프트 가드 — 신규 스텝의 경계 누락 차단 (신규) | 새 `BatchStep` 구현 추가 | 클래스패스 스캔(`kr.co.cudo.authoring`) | 면제 목록 밖 구현의 `execute(BatchContext)` 에 `@Transactional(REQUIRES_NEW)` 이 없으면 **테스트 실패**. `execute` 를 직접 선언하지 않아도 실패 | unit | P0 | BatchStepTransactionBoundaryTest.java:37-105 |
| TC-BATCH-190 | `bumpLabelVersionIn` 에 `@Transactional` 을 붙이지 않는다 (신규) | 리포지토리 규약 | 정적 확인 | 애노테이션을 붙이면 DML 마다 별도 tx 가 열려 ①스텝 원자성 붕괴 ②프레임 락과 분리 ③다음 경계 누락이 무증상화. 경계는 스텝이 제공한다 | unit | P0 | LsDataSrcRepository.java:283-295 |

## B-16. 오토라벨 일괄저장 (AutoLabelBatchPersister) — 신규 섹션

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-191 | 프레임 단위 2단 saveAll(라벨→AI메타) (신규) | 프레임 1건에 검출 N건 | saveAll(...) | `lblRepository.saveAll` 1회 + `aiInfoRepository.saveAll` 1회. 검출마다 개별 `save()` 호출 없음 | integration | P0 | AutoLabelBatchPersister.java:61-84 · YoloAutolabelStep.java:214,369-371 · Sam2SegmentStep.java:187,244-246 |
| TC-BATCH-192 | AI 메타는 저장된 라벨의 **자기 lblSn/srcSn** 사용 (신규) | saveAll 반환 목록 | 인덱스 순회 | 반환 순서 = 입력 순서, 각 원소가 PK 부여된 입력 인스턴스 — 잘못된 라벨에 AI 메타가 붙지 않음 | integration | P0 | AutoLabelBatchPersister.java:30-34,72-83 |
| TC-BATCH-193 | size 불일치 즉시 실패 (신규) | 반환 목록 크기 ≠ 입력 크기 | saveAll(...) | `INTERNAL_ERROR`("자동 라벨 일괄 저장 결과 개수 불일치") — 조용한 데이터 오염 대신 실패 | unit | P0 | AutoLabelBatchPersister.java:72-76 |
| TC-BATCH-194 | pending 비면 리포지토리 미호출 (신규) | 검출 0건 프레임 | saveAll(...) | no-op, 0 반환 | unit | P2 | AutoLabelBatchPersister.java:55,61-72 |
| TC-BATCH-195 | 신뢰도는 PendingLabel 로 별도 전달 (신규) | 엔티티 `clampScore` 보정 | PendingLabel(label, score) | AI 메타에는 **원본 신뢰도**가 적재된다(엔티티에서 되읽으면 보정값이 되는 기존 동작 보존) | unit | P1 | AutoLabelBatchPersister.java:41-48 |
| TC-BATCH-196 | IDENTITY PK 유지 — 실제 JDBC 배치는 여전히 비활성 (신규) | `LsDataLbl`/`LsDataLblAiInfo` | 저장 관측 | `hibernate.jdbc.batch_size` 는 이 엔티티에 적용되지 않는다. 실익은 **왕복 횟수 감소·저장 지점 단일화**이지 "INSERT 문 묶음"이 아니다(B-ISSUE-42 부분 해소) | integration | P1 | AutoLabelBatchPersister.java:22-28 |

## B-17. LS_DATA_RAW 참조 무결성 FK (V146) — 신규 섹션

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-BATCH-200 | FK 27개 생성(CASCADE 25 / SET NULL 2) (신규) | 마이그레이션 적용 | `\d ls_data_raw` 참조 확인 | `FK_{테이블}_RAW` 규칙으로 27개 생성 — 배치 7 · 작업/검수 9 · 버전/증강 5 · 관제 3 · 포털 1 · 원장/세션 2 | integration | P0 | V146__add_ls_data_raw_child_fk.sql:41-74 |
| TC-BATCH-201 | 부모 삭제 시 자식 CASCADE (신규) | 해상도 파생 실패 정리 / TUS 완료 경합 롤백 | `DELETE FROM ls_data_raw` | 자식 행이 함께 삭제되어 고아가 구조적으로 불가능. RESTRICT 로 하면 기존 삭제 경로 2곳이 깨지므로 채택하지 않는다 | integration | P0 | V146__add_ls_data_raw_child_fk.sql:10-16 |
| TC-BATCH-202 | 원장·세션은 SET NULL (신규) | `ls_tus_upload` / `ls_webhook_idempotency` | 부모 삭제 | 행은 살아남고 참조만 끊긴다(감사·멱등 원장 보존) | integration | P0 | V146__add_ls_data_raw_child_fk.sql:72-74 |
| TC-BATCH-203 | ★뷰 공급 7테이블 고아 시 **중단** (신규) | `ls_dataset_video_meta` 등에 고아 존재 | 마이그레이션 | 삭제 대신 `RAISE EXCEPTION` — "검수 완료·통지 건에 대한 관제 접근 보장" 구속 정책상 뷰 행을 조용히 줄이지 않는다 | security | P0 | V146__add_ls_data_raw_child_fk.sql:76-84,111-114 |
| TC-BATCH-204 | 한 테이블 고아 1000건 초과 시 중단 (신규) | 대량 고아 | 마이그레이션 | `max_orphans=1000` 초과면 `RAISE EXCEPTION` — 정상 운영의 잔여물이 아니라고 판정해 사람 판단을 요구 | security | P0 | V146__add_ls_data_raw_child_fk.sql:87,107-110 |
| TC-BATCH-205 | MNG_* · ORGNL_RAW_SN 제외 (신규) | 대상 판정 | 스펙 배열 | `MNG_CLIP_SCHEDULE_QUE`(관제 소유·선승인 필요) 제외, `LS_DATA_RAW.ORGNL_RAW_SN`·`LS_DATASET_VIDEO_META.ORGNL_RAW_SN`(계보/동결값) 제외. ⚠ **큐 제외는 V162 로 supersede** — 해당 테이블은 저작도구 자체 소유로 확인돼 `LS_CLIP_SCHEDULE_QUE` 개명 + `RAW_SN` FK 가 보강됐다(`LsClipScheduleQueFkIT`). `ORGNL_RAW_SN` 제외는 그대로 유효 | integration | P0 | V146__add_ls_data_raw_child_fk.sql:17-26 · V162__rename_mng_clip_schedule_que_to_ls.sql |
| TC-BATCH-206 | 3패스 구조(실태조사 → 고아 정리 → FK 생성) (신규) | 마이그레이션 | 실행 로그 | 1패스 `RAISE NOTICE` 로 고아 실태 기록 → 2패스 정리 → 3패스 멱등 FK 생성 | integration | P1 | V146__add_ls_data_raw_child_fk.sql:96-162 |
| TC-BATCH-207 | 멱등 — 재실행 시 기존 FK 재생성 안 함 (신규) | 이미 적용된 DB | 재실행 | 기존 제약 존재 시 skip, 오류 없음 | integration | P1 | V146__add_ls_data_raw_child_fk.sql:152-162 |

## B-18. 영상 처리 현황 목록 검색·필터 (`GET /v1/videos`) — 2026-08-03 신설

> **결정 5 (2026-08-03 사용자 확정, 커밋 대기)**: 영상 처리 현황 목록에 **검색어·이벤트 유형·촬영기간 필터를 신설**하고 `capturedAt` 표시 축을 정정했다.
> FE 분(드롭다운·전송·표시)은 [H-18](H-frontend-e2e.md) 소관이며, 여기서는 BE 계약만 다룬다.
>
> - **왜 이 클러스터인가**: 목록 소스가 `LS_DATA_RAW`(적재·배치 단계)이고 엔드포인트가 B-10 과 같은 `VideoController` 다. 정렬 allowlist 자체는 A-6 `TC-SORT` 가 계속 소관이다.
> - **★ 오류 처리 비대칭은 의도된 것이다 — "비일관"으로 보고하지 말 것**: `from>to`·날짜 형식 오류·검색어 100자 초과는 **400**, **미등록 `eventTypeCd` 는 400 이 아니라 0건**이다.
>   판단 기준은 [UNCERTAINTIES ★2](UNCERTAINTIES.md) 와 같은 축("변경 전에 그 요청이 200 이었는가")이며, 여기에 **"이 값 하나로 목록 전체가 죽는가"** 가 더해진다 —
>   날짜·길이 오류는 입력 오류를 알려야 하고(빈 목록이면 '검색 결과 없음'과 구분 불가), 이벤트 코드는 북마크·뒤로가기 URL 에 담겨 재전송되므로 400 이면 목록 진입 자체가 막힌다.
> - **하위호환 불변**: 신규 파라미터를 하나도 보내지 않으면 결과·정렬(`regDt DESC`)이 종전과 같다. 정렬 allowlist·미등록 정렬 키 lenient 폴백 정책도 불변(회귀 가드 `ListApiBackwardCompatibilityIT`).
> - **저장소 쿼리 3개 → 통합 쿼리 1개**로 교체됐다(`findAllByOrgnlRawSnIsNull` · `findAllByDataSttsCdAndOrgnlRawSnIsNull` · `findOriginalsWithReviewStatus` → `searchOriginals`). 조합 폭발을 막고 **파생영상 제외 조건을 한 곳에만** 두기 위함이다.
> - 규칙 전문 → [v2-wiki 05 §5.5.3](../v2-wiki/05-video-management.md)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-VIDEO-001 | 하위호환 — 신규 파라미터 미전송 시 결과·정렬 불변 (신규) | 원본 영상 다수 | `GET /v1/videos` (필터 없음) | 종전과 동일한 목록 + 기본 정렬 `regDt DESC`. 통합 쿼리의 모든 조건이 nullable/플래그 off 로 무력화 | integration | P0 | VideoRepository.java:170-183,197-210 · VideoQueryService.java:108-110 |
| TC-VIDEO-002 | 검색어 — CCTV 명 부분일치(대소문자 무시) (신규) | `MNG_RESOURCE_CCTV` 조인 대상 존재 | `?cctvNameKeyword=강남` | 부분일치 매칭. CCTV 명이 없거나 공백이면 `VMS_CCTV_ID` 로 폴백해 비교(= `VideoSummaryResponse.from` 의 **화면 표시 규칙과 동일**) | integration | P1 | VideoRepository.java:201,205-207 · VideoListSearchFilterIT.java:150-163 |
| TC-VIDEO-003 | 검색어가 숫자면 영상 ID(rawSn)로도 매칭 (신규) | rawSn=1234 영상 | `?cctvNameKeyword=1234` | CCTV 명 LIKE **OR** `rawSn=1234`. FE 입력 라벨이 "CCTV명 / 영상ID" 이기 때문. 숫자가 아니면 `keywordRawSn=null` 이라 OR 항이 UNKNOWN → CCTV 명만 본다 | integration | P1 | VideoQueryService.java:97-98,377-382 · VideoListSearchFilterIT.java:165-178 |
| TC-VIDEO-004 | LIKE 메타문자 이스케이프 — `%` 한 글자로 전체 매칭 불가 (신규) | 검색어에 `%`/`_`/`!` | `?cctvNameKeyword=%` | 리터럴로 취급되어 전체 목록이 나오지 않는다. 이스케이프 문자는 `!`(`ESCAPE '!'`) — 백슬래시는 JDBC·DB 설정마다 해석이 갈려 쓰지 않는다. 값은 전부 파라미터 바인딩(CWE-89) | security | P0 | VideoQueryService.java:86-89,352-370 · VideoRepository.java:206 · VideoListSearchFilterIT.java:180-192 |
| TC-VIDEO-005 | 검색어 길이 상한 초과 → 400 (신규) | 101자 | `?cctvNameKeyword={101자}` | `INVALID_INPUT` 400 "검색어는 100자 이하여야 합니다." FE `maxLength=100` 과 같은 값(CWE-20/770) | security | P1 | VideoQueryService.java:70-76,123-126 · VideoListSearchFilterIT.java:194-204 |
| TC-VIDEO-006 | 이벤트 필터 — 카테고리 키를 EV-코드 집합으로 펼쳐 비교 (신규) | 카테고리 `010001` 에 EV-코드 2건 등록 | `?eventTypeCd=010001` | 관제 마스터 역인덱스로 카테고리→EV-코드 변환 후 `IN` 비교. **축이 다르다** — FE 는 카테고리 키(`EVNT_CLS_CD+EVNT_CTGRY_CD`), 영상은 EV-코드(`LS_DATA_RAW.EVNT_TYPE_CD`). 그대로 비교하면 영원히 0건 | integration | P0 | EventTypeService.java:157-180 · VideoQueryService.java:384-402 · VideoListSearchFilterIT.java:208-222 |
| TC-VIDEO-007 | ★미등록 카테고리 키는 400 이 아니라 **0건** (신규) | 마스터에 없는 키 | `?eventTypeCd=999999` | 200 + 빈 목록. sentinel(`NO_EVENT_MATCH`)을 `IN` 에 넘겨 0건을 만든다(빈 컬렉션은 유효 SQL 로 렌더되지 않음). **코드 하나로 목록 전체가 죽으면 북마크·뒤로가기 진입이 막힌다** — 400 으로 바꾸지 말 것 | security | P0 | VideoQueryService.java:91-95,392-401 · VideoListSearchFilterIT.java:224-236 |
| TC-VIDEO-008 | 과대 길이 카테고리 키도 0건(400 아님) (신규) | 21자 이상 | `?eventTypeCd={21자}` | 코드값 표준도메인 `VARCHAR(20)` 초과 = **정의상 미등록** → TC-VIDEO-007 과 동일 처리 | security | P1 | VideoQueryService.java:78-83,396-399 |
| TC-VIDEO-009 | 관제 마스터에 없는 EV-코드 보유 영상은 이벤트 필터에 안 잡힌다 (신규) | 영상 `EVNT_TYPE_CD='EV99999999'`(마스터 미등록) | 임의 `?eventTypeCd=` | 어떤 카테고리 집합에도 속하지 않아 자동 제외(오류 아님) | integration | P1 | VideoQueryService.java:384-401 · VideoListSearchFilterIT.java:238-249 |
| TC-VIDEO-010 | 촬영기간 — `SHT_DT` 기준 **양끝 경계 포함** (신규) | 경계일 촬영 영상 | `?from=2026-08-01&to=2026-08-03` | `from` 은 `00:00:00`, `to` 는 `23:59:59.999999999` 까지 포함. **기준 컬럼은 `LS_DATA_RAW.SHT_DT`** — 정렬 키(`capturedAt→shtDt`)·표시 컬럼('녹화일')과 같은 축 | integration | P0 | VideoQueryService.java:404-424 · VideoRepository.java:209-210 · VideoListSearchFilterIT.java:253-268 |
| TC-VIDEO-011 | `from > to` → 400 (신규) | 역전 입력 | `?from=2026-08-05&to=2026-08-01` | `INVALID_INPUT` 400 "시작일은 종료일보다 늦을 수 없습니다." 빈 목록으로 두면 "검색 결과 없음"과 구분되지 않아 입력 오류를 알 수 없다. **이번에 신설된 파라미터라 파손될 기존 계약이 없어** strict 가 가능 | security | P1 | VideoQueryService.java:404-419 · VideoListSearchFilterIT.java:270-276 |
| TC-VIDEO-012 | 날짜 형식 오류 → 400 (신규) | `2026-13-99`·`abc` | `?from=abc` | 400(`@DateTimeFormat(ISO.DATE)` 바인딩 실패). 예외 메시지에 내부 경로·스택 미노출 | security | P1 | VideoController.java:108-113 · VideoListSearchFilterIT.java:278-284 |
| TC-VIDEO-013 | 필터 조합 정확성 + `totalElements` 도 필터 적용 후 건수 (신규) | 4필터 동시 지정 | 조합 요청 | 조건이 **전부 DB 로 내려가** 결과·총건수·페이지 수가 모두 필터 적용 후 전체 기준. 페이징 후 Java 필터 금지 | integration | P0 | VideoRepository.java:197-224(countQuery 동일 조건) · VideoListSearchFilterIT.java:288-308 |
| TC-VIDEO-014 | ★파생영상은 **어떤 필터 조합에서도** 노출되지 않는다 (신규) | 증강·해상도 파생(`ORGNL_RAW_SN` non-null) | 필터 조합 전수 | 항상 제외. 조건은 통합 쿼리 한 곳(`WHERE v.orgnlRawSn IS NULL`)에만 존재 — 조합마다 다시 적으면 하나 빠뜨렸을 때 파생이 샌다. 작업 목록(`TaskBoardQueryRepository`)에는 여전히 포함(R2, 분리 유지) | security | P0 | VideoRepository.java:202,216 · VideoListSearchFilterIT.java:310-333 |
| TC-VIDEO-015 | `capturedAt` = `SHT_DT`, 없으면 null (신규) | `SHT_DT` null 인 영상 | 목록 응답 | `capturedAt=null`(화면 `-`). **`REG_DT` 폴백 없음** — 표시값만 수신 시각이던 드리프트 정정. 수신 시각은 별도 필드 `regDt` 로 계속 나간다. 같은 이유로 그 영상은 기간 필터에도 안 잡힌다 | integration | P0 | VideoSummaryResponse.java:22,42-46,238-239 · VideoListSearchFilterIT.java:335-357 |
| TC-VIDEO-016 | 검수상태 조인 INNER→LEFT 전환이 결과 동치 (신규) | 상태행 없는 영상 혼재 | `?reviewStatusCd=APPROVED` / 미지정 | `LS_RAW_DATA_STATUS` PK 가 `RAW_DATA_ID`(영상당 1행)라 중복 행이 안 생기고, 필터 지정 시 `s.dataSttsCd=:reviewStatusCd` 가 상태행 없는 영상을 걸러 **구 INNER JOIN 과 같은 결과**. 미지정이면 조인이 결과에 영향 없음(구 무조인 분기와 동일) | integration | P0 | VideoRepository.java:146-151,200,204 |
| TC-VIDEO-017 | 정렬 계약 불변 — `reviewCompletedAt` 은 검수상태 필터 지정 시에만 허용 (신규) | 통합 쿼리가 조인을 항상 걸어 alias 는 늘 유효 | `?sort=reviewCompletedAt,desc` (필터 없이) | 기존 계약 유지 — 검수 완료 시각이 없는 영상이 정렬 축에 섞이지 않도록 `usesReviewStatusJoin` 판정을 그대로 둔다. 미등록 키는 **lenient 200 + 기본 정렬 폴백**([★2](UNCERTAINTIES.md)) | integration | P1 | VideoQueryService.java:308-322 · SortAllowlist.java:159-170 |
| TC-VIDEO-018 | 날짜 파라미터는 `IS NULL` 이 아니라 on/off 플래그로 조립 (신규) | PostgreSQL 확장 프로토콜 | 기간 미지정 요청 | `$n IS NULL` 로 두면 timestamp 파라미터 타입 추론 실패(`could not determine data type of parameter`). 플래그(`fromFilterOn`/`toFilterOn`) + 더미 경계값(`SHT_DT_FLOOR`/`CEILING`)이라 파라미터가 항상 컬럼과 비교되는 위치에만 등장 | integration | P1 | VideoRepository.java:185-196,209-210 |

---

> **불확실 항목 처리 결과(이번 회차)**
> - **#2 마킹 단계 rawSn 신고 미구현 → 해소**. `POST /v1/videos/{rawSn}/deident-report` 구현됨(TC-DEID-055/056).
> - **#8 Quartz 클러스터링 활성 여부 → 해소**. stg/prd 기본 `true` + `QuartzClusteringGuard` fail-closed 기동 거부(TC-BATCH-171/173~177). 공통 yml 기본값은 여전히 `false`(local/dev 단일 노드).
> - **#21 `KpstDeidentTxService` 상세 → 해소**. 폴링 대상 선점(`tryClaimPoll`)·완료 전이 클레임(`claimDownloadCompletion`) 두 축이 신설되어 1차 검증에서 "잔여 공백"으로 남았던 원자성 갭이 닫혔다(TC-DEID-085~089).
>
> 남은 미해소 항목: **B-ISSUE-23**(AUTO `intervalFrames` 상한 미검증 → TC-BATCH-096 으로 현재 동작 고정) · **B-ISSUE-63**(`/stream` 영상 단위 배정 인가 부재 → TC-STREAM-B15 비고).
