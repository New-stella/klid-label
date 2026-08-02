# B 클러스터 part6 — B-11 비식별 누락 신고 / B-12 재처리·재시도 큐 / B-15 배치 스텝 트랜잭션 경계

> 검증일 2026-08-01 · 회차 1차 · 대상 60건(B-11 30 + B-12 19 + B-15 11)
> 코드 기준 `qa-0801` @ `56d30478` · 실행 스택 backend V146(환경 버전 격차 — `stack-bringup.md` 하단 참조)
> 판정 근거: **[실동작]** = 기동 스택에 실제 요청/DB 조회/로그 관측 · **[정적]** = file:line 대조

## 0. 검증 환경·수단

| 항목 | 값 |
|---|---|
| backend | `http://localhost:18081/api` (context-path `/api`), local 프로파일 |
| DB | `klid-postgres` / `klid_system` / 스키마 **`public`** |
| 토큰 | `POST /api/v1/dev/tokens` — REVIEWER(sub=1001) · WORKER(sub=2001) |
| 신규 투입 테스트 데이터 | 영상 `rawSn=27`(업로드→선두비식별→AUTO마킹→프레임 5건 301~305→라벨 3건→개인정보 3필드), `rawSn=29`(비식별만), 합성 부모/파생 쌍 `rawSn=9301('F')`/`9302('Y', ORGNL=9301)` + 프레임 99301/99302 + 라벨 2건, 합성 재시도큐 stale 행 3건(9301/9302/9303) |
| ⚠ 기존 데이터 상태 변화(불가피) | ①`rprtSn=7`(rawSn 6) resolve 200 — TC-DEID-044 확인 중 **의도치 않게 해소됨**(원인은 B-ISSUE-102, 아래) → rawSn 6 `'F'→'Y'`, 락 해제 ②`rawSn=9109` 배치 재처리 2회 기동(재시도 큐 행 교체) ③`rawSn=26` 신고→resolve 왕복(최종 `'Y'`/APPROVED 원복, export `v2` 추가 생성 + 관제 재통지) |
| ⚠ 병행 세션 간섭 | 검증 중 타 에이전트가 같은 스택에서 rawSn 28·30~38 을 생성·구동함. 본 문서의 근거는 **본 세션이 직접 만든 rawSn(26/27/29/9301~9303)** 관측치만 사용 |

---

## 1. B-11. 비식별 누락 신고 (srcSn / rawSn, resolve) — 30건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-030 | PASS | [실동작] `POST /v1/labels/301/deident-report` body `{"reason":"   "}` → **400** `INVALID_INPUT` "reason: 신고 사유는 필수입니다."; body `{}`(reason 누락) → 동일 400. `@Valid`+`@NotBlank`(DeidentReportRequest.java:18) 선차단. [정적] 서비스 `requireReason`(DeidentReportService.java:158-162)이 우회 호출 2차 방어 |
| TC-DEID-031 | PASS | [실동작] WORKER(2001, 배정=raw 26·27) → `POST /v1/labels/75/deident-report`(raw 17) **403** `FORBIDDEN` "본인에게 배정되지 않은 영상입니다."; `POST /v1/videos/17/deident-report` 도 **403**. [정적] `report()`→`accessGuard.verifyAndGet`(:122), `reportByVideo()`→`verifyRawAccess`(:151) — **영상 조회보다 먼저** 평가해 존재 여부 미노출 |
| TC-DEID-032 | PASS | [실동작] rawSn 29 에 5요청 동시 발사 → 201 1건 / 409 4건, `ls_deident_report` 1행 · `ls_auth_work_lock` 1행 · `de_ident_yn='F'` (중복 신고·중복 락 0). [정적] `doReport`가 `videoRepository.findByRawSnForUpdate`(DeidentReportService.java:178) → `@Lock(PESSIMISTIC_WRITE)`(VideoRepository.java:37-39)로 부모 RAW 행 직렬화 |
| TC-DEID-033 | PASS | [실동작] rawSn 27 신고 201 직후 srcSn 302 재신고 → **409** "이미 비식별 재처리 중인 영상입니다."(DeidentReportService.java:188-191) |
| ~~TC-DEID-034~~ | N/A | 폐기(2026-07-30) — 검증 대상 아님. [정적] 실제로 스냅샷·삭제 코드가 없음(:197-205 주석 블록만 잔존) |
| TC-DEID-035 | PASS | [실동작] 신고 전 srcSn 301/302/303 = `Y/N/N` → 신고 후 3필드 전부 **NULL**(304/305 원래 NULL 유지). 백엔드 로그 `privacyReset=5`(=영상 전 프레임 벌크 UPDATE 영향행) |
| TC-DEID-036 | PASS | [실동작] TC-DEID-032 와 동일 시행 — 동시 5건 중 4건이 예외 전파 없이 **409**(`DataIntegrityViolationException`→CONFLICT 변환, :237-241). 500/스택트레이스 노출 0 |
| TC-DEID-037 | PASS | [실동작] APPROVED 영상 rawSn 26 신고 201 → `ls_mon_noti_acml` 신규 행 `chg_dtl_cn={"video":{"*":["META_UPDATED"]}}` STTS=PENDING → 디바운스 flush 후 `[ControlNotify] TASK_MODIFIED sent rawSn=26 frames=0 videoLevel=1` + mock-server `POST /api/data-set/v2/jobs/26/notify-updated` **202**. `LABEL_DELETED` 아님 확인 |
| ~~TC-DEID-038~~ | N/A | 폐기(2026-07-30) — 검증 대상 아님 |
| TC-DEID-039 | PASS | [실동작] 토큰 없이 `POST /v1/deident-reports/1/resolve` → **401** `UNAUTHORIZED`. [정적] 시큐리티 필터가 선차단하고 서비스 `actor==null` 가드(:354-356)가 2차 방어 |
| TC-DEID-040 | PASS | [실동작] `POST /v1/deident-reports/99999999/resolve` → **404** "신고를 찾을 수 없습니다." |
| TC-DEID-041 | PASS | [실동작] `rprtSn=7` resolve 성공(200) 직후 재호출 → **409** "이미 처리된 신고입니다."(:364-366) |
| TC-DEID-042 | PASS | [실동작] rawSn 27 신고(19:11:18) 상태에서 resolve → **409** "비식별 산출물이 확인되지 않습니다…". 직후 DB 재확인: `report_stts_cd=OPEN` · `de_ident_yn=F` · LOCKED 락 1건 **전부 유지**(fail-closed 롤백). 로그 `resolve blocked — deident artifact not verified rawSn=27`(내부 경로 미노출) |
| TC-DEID-043 | PASS | [실동작] 비식별 산출물 mtime 갱신 후 WORKER(본인배정) resolve → **200**; DB: `RESOLVED`+`resolved_dt` 기록 · `de_ident_yn='Y'` · LOCKED 락 0건 · **라벨 3건 그대로**. 이어서 `GET /v1/frames/301/labels` **200**(보존된 기존 라벨 재사용), `GET /v1/videos/27/stream` **200** — 게이트 자동 해제 |
| TC-DEID-044 | PARTIAL | [실동작] `rprtSn=2`(rawSn 11 — 최신 SUCCESS procLog 없음/경로 blank) resolve → **409** 정상 거부. 그러나 같은 시행에서 `rprtSn=7`(rawSn 6)이 **200 으로 통과** — 산출물이 신고 이전 파일(mtime이 신고시각보다 **38ms 이전**)인데 60초 스큐 관용에 흡수됐다 → **B-ISSUE-102** |
| TC-DEID-045 | PARTIAL | [실동작] rawSn 27: procLog `rspns_dt=19:09:51`·파일 mtime `19:09:21` < 신고 `19:11:18` → **거부(409)** 확인(주 단언 충족). 단 경계값 반증 결과 `신고시각-60s < mtime ≤ 신고시각` 구간은 **통과**한다(rawSn 6 실측) → **B-ISSUE-102** |
| TC-DEID-046 | PASS | [실동작] rawSn 27 은 신고 시점 `LS_DATA_RAW.DATA_STTS_CD=COMPLETED`. 신고 후에도 `COMPLETED`, resolve 후에도 `COMPLETED` 유지 — `de_ident_yn` 만 `F→Y`. 배치 단계 역행 없음(CWE-664) |
| TC-DEID-047 | PASS | [정적] `resolveOpenReports`(:443-462) — OPEN 일괄 `resolve()` + `workLockService.releaseRaw` + `streamMetaCacheEvictor.evictAfterCommit`, `opens` 비어있으면 이벤트 미발행(멱등). 호출부 2곳 = `DeidentifyStep.java:354` · `KpstDeidentTxService.java:358`. [실동작 미도달] local 은 `authoring.batch.enabled=false` 라 OPEN 신고 보유 영상의 자동 비식별 재성공 경로를 만들지 못함 — 커버는 `DeidentReportServiceTest` 다수(baseline 전량 PASS) |
| TC-DEID-048 | PASS | [실동작] `?status=X` → 400 / `?status=OPEN' OR 1=1--`(URL 인코딩) → 400 / `?status=open`(소문자) → 400(컨트롤러 `@Pattern` 이 서비스 `toUpperCase` 정규화보다 먼저·더 엄격) / 무파라미터 → 200 기본 OPEN 3건. SQLi 문자열이 쿼리에 도달하지 않음 |
| TC-DEID-049 | PASS | [실동작] WORKER(본인 배정 raw 27) `POST /v1/labels/301/deident-report` → **201** body=`9`(rprtSn). REVIEWER 로도 201(rawSn 26·29) |
| TC-DEID-050 | PASS | [실동작] WORKER 2001 이 타인 영상 신고(rprtSn 5 / rawSn 9112) resolve → **403**; 본인 배정 rawSn 27(rprtSn 9) resolve → **200**; REVIEWER 는 rprtSn 7·11 모두 resolve 200 |
| TC-DEID-051 | PASS | [실동작] 신고 전/후 DB 대조 — `ls_data_lbl` **3→3**(lbl_sn 585/586/587 동일), `ls_label_version` **0→0**, `ls_data_src.lbl_ver` 301=1·302=1 **불변**(bump 없음). 로그 `labelsPreserved=true` |
| TC-DEID-052 | PASS | [실동작] 3필드 보유 프레임 3건(301/302/303)에 대해 `ls_data_lbl_hstry` 정확히 **3행** 신규(lbl_hstry_sn 34/35/36), `chg_dtl_cn={"event":"PRIVACY_META_RESET","deidentReportSn":9,"changes":[]}`, `add/mdfcn/del=0`, `reg_id=2001`. 값이 없던 304/305 는 미기록(= 리셋 **직전** 대상 확정). `V_COMPLETED_LABEL_CHANGE` 조회 결과 이 3행 미노출(V139 `ADD+MDFCN+DEL>0` 필터, 뷰 전체 24행 중 0-count 0건) |
| TC-DEID-053 | PASS | [실동작] 파생 rawSn 18(ORGNL=4) 및 합성 파생 9302(ORGNL=9301) 양쪽에 srcSn 경로·rawSn 경로 모두 **412** `PRECONDITION_FAILED`. 응답 메시지에 **부모 rawSn 문자열 미포함**(grep 결과 0건), `ls_deident_report` 신규 행 0, REVIEWER 알림 로그 없음. 사유는 WARN 감사 로그로만 보존되며 개행/CR 주입 시도(`\n2026-01-01 00:00:00 FAKE INJECTED LINE\r`)가 **한 줄로 정제**되어 위조 로그 라인 0건(CWE-117) |
| TC-DEID-054 | PASS | [실동작] `de_ident_yn='N'` 인 rawSn 9104 신고 → **412** "아직 비식별 처리가 완료되지 않은 영상입니다." + `'N'` 그대로(전이 없음). `'F'`(rawSn 27 신고 후) 재신고는 412 아닌 **409**(기존 작업락 경로) — 계약 유지 확인 |
| TC-DEID-055 | PASS | [실동작] `POST /v1/videos/26/deident-report`(REVIEWER) **201** rprtSn=11, `POST /v1/videos/29/deident-report` **201**. 마킹 단계(프레임 없는 rawSn 29)에서도 정상 접수 — UNCERTAINTIES #2 해소 확인 |
| TC-DEID-056 | PASS | [실동작] 두 진입점 부수효과 동일성 실측: 파생 412(18/9302 양 경로) · 비식별 미수행 412(9104) · 작업락 409(27) · `'F'` 전이(26·27·29) · 개인정보 3필드 리셋(27) · 스트림 404 게이트. [정적] 두 메서드가 `doReport`(:169) 단일 본체로 수렴(:125, :154) — 분기점은 인가 축과 `srcSnForNotify` 뿐 |
| TC-DEID-057 | PASS | [실동작] **미승인** 영상 rawSn 27(작업상태 `ASSIGNED`) resolve → 로그 `[VlmResumeBridge] deident gate reopened rawSn=27 — checking withheld VLM submit` 발화 = `DeidentGateReopenedEvent` 무조건 발행 확인 |
| TC-DEID-058 | PASS | [실동작] **APPROVED** 영상 rawSn 26 resolve → `[DatasetExportBridge] deident report resolved rawSn=26 — re-triggering withheld export/notify` → export `version=2` SUCCEEDED(`ls_dataset_export` export_sn=14) + 관제 재통지. 반면 미승인 rawSn 27 resolve 에서는 `DatasetExportBridge` 로그 **미발화** → 승인 조건부 발행 확인 |
| TC-DEID-059 | PASS | [실동작] 부모 9301=`'F'` / 파생 9302=`'Y'` 합성 쌍에서 `GET /v1/frames/99301/labels`→**412**, `GET /v1/frames/99302/labels`→**200**(라벨 반환). 즉 게이트는 자기 rawSn 행만 판정하며 `ORGNL_RAW_SN` 을 보지 않는다. [정적] `DeidentReportGate.isUnderDeidentReport`(:66-71)는 `findDeIdntfYnByRawSn` **단일 컬럼 projection 1회** — 조상 순회·자손 팬아웃 코드 0. ※ ★1 확정 정책의 귀결(파생 경유 열람)은 **결함 아님** |

**B-11 소계**: PASS 26 · PARTIAL 2 · N/A(폐기) 2 · FAIL 0

---

## 2. B-12. 재처리 / 재시도 큐 (2노드 안전 · stale 회수) — 19건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-150 | PASS | [실동작] `POST /v1/videos/0/batch/retry` → **400** "retryBatch.rawSn: rawSn 은 1 이상이어야 합니다."(컨트롤러 `@Min(1)` 선차단). [정적] 서비스 null 가드(BatchReprocessService.java:67-69) 방어심도 |
| TC-BATCH-151 | PASS | [실동작] `POST /v1/videos/88888888/batch/retry` → **404** "영상을 찾을 수 없습니다."(:71-73) |
| TC-BATCH-152 | PASS | [실동작] rawSn 9109(raw FAILED) 재처리 → 200 + 로그 `[BatchReprocess] manual retry claimed rawSn=9109` → `clearIfIdle`(PENDING 행 삭제) → `orchestrator.process` 실행(MarkingLoadStep→…) 관측 |
| TC-BATCH-153 | FAIL | [실동작] rawSn 9109 에 **동시 5요청 → 200 이 2건**(409 3건). 로그상 서로 다른 스레드가 같은 밀리초(19:17:39.920/.921)에 `manual retry claimed rawSn=9109` 를 찍고 **파이프라인 2벌이 동시 실행**됐다(재시도 큐도 attempt=1·attempt=2 로 이중 증가). 원인: `tryClaimReprocessFromFailed` 가 RAW 클레임 실패(0행) 시 **작업상태 컬럼으로 폴백**하는데, 정상 배치 실패는 두 컬럼이 **함께 FAILED** 라 두 호출자가 각각 다른 컬럼을 선점한다 → **B-ISSUE-101** |
| TC-BATCH-154 | PARTIAL | [실동작] raw 우선 클레임은 동작(첫 요청이 RAW 컬럼 선점). 두 UPDATE 모두 조건부(`WHERE …=FAILED`)임은 [정적] 확인(VideoRepository.java:66 · LsRawDataStatusRepository.java:109). 다만 "우선"이 **배타**가 아니어서 상호배제가 성립하지 않음(TC-BATCH-153 / B-ISSUE-101). 근거 라인 드리프트: 카탈로그 `BatchTransitionService.java:334-362` → 실제 `334-347` |
| TC-BATCH-155 | PASS | [실동작] rawSn 9109 최초 실패 시 `ls_bat_rty_wtng` 신규 행 `rty_nmtm=1 stts_cd=PENDING`(로그 `[BatchRetry] enqueued rawSn=9109 attempt=1 delaySec=60`). [정적] `insertIfAbsent`(ON CONFLICT DO NOTHING) + `findByRawSnForUpdate`(BatchRetryQueue.java:72-74) |
| TC-BATCH-156 | PASS | [실동작] 위 동시 2벌 실행이 같은 밀리초에 enqueue 를 2회 수행했으나 `DataIntegrityViolationException` 전파 0 · UK 위반 로그 0 · 행은 1건 유지(attempt 1→2 순차 증가). PG tx-abort 함정 회피 확인 |
| TC-BATCH-157 | PASS | [실동작] attempt=1 → `delaySec=60`, attempt=2 → `delaySec=120` 로그 실측(=`60×2^(n-1)`). [정적] shift 30 캡(BatchRetryQueue.java:86-87) |
| TC-BATCH-158 | PASS | [실동작] `ls_bat_rty_wtng` 에 `stts_cd=EXHAUSTED` 행 상존 — bat_rty_sn 24(rawSn 9202, `rty_nmtm=4 > max 3`), 13(rawSn 9203, `rty_nmtm=8`). **삭제되지 않고 이력 보존**됨. [정적] `markExhausted`+`save`(:79-83) |
| TC-BATCH-159 | BLOCKED | 실동작 불가 — local 프로파일 `authoring.batch.enabled=false`(application-local.yml:61-62)라 `BatchRetryTriggerConfig`(`@ConditionalOnProperty`)가 미등록. `qrtz_triggers` 실측 3건(kpstDeidentPoll/datasetExportFailureRecovery/datasetExportPendingSweep)에 **batchRetryTrigger 부재** 확인. [정적] `pollReady`(:101-112)가 후보 순회 + `claimAtomically`(PENDING→RETRYING CAS, LsBatRtyWtngRepository.java:63-66) 1행만 반환. 커버: `BatchRetryQueueIT#재시도_동시_폴링시_한_노드만_클레임한다`(baseline PASS) |
| TC-BATCH-160 | PASS | [실동작] 큐가 DB 영속임을 실측 — `ls_bat_rty_wtng` 에 **2026-07-31 세션에 등록된 행이 08-01 까지 잔존**(bat_rty_sn 9·10·11). [정적] `BatchRetryQueue` 에 인메모리 컬렉션 필드 0(repository 만 보유, :40-50) |
| TC-BATCH-161 | PASS | [실동작] rawSn 9109 수동 재처리 시 기존 `PENDING` 행(bat_rty_sn 9) 삭제 후 새 행 생성 확인. [정적] `deleteIdleByRawSn` = `DELETE … WHERE rawSn=:rawSn AND sttsCd <> 'RETRYING'`(LsBatRtyWtngRepository.java:151-153) — RETRYING 보존 |
| TC-BATCH-162 | PASS | [정적] `BatchOrchestrator.java:133` 성공 경로에서 `retryQueue.clear(rawSn)` → `deleteByRawSn`(무조건 삭제, BatchRetryQueue.java:125-131). [실동작] 성공 완주한 rawSn 26·27 에 `ls_bat_rty_wtng` 행 0건 |
| TC-BATCH-163 | PASS | [실동작] rawSn 15(raw `FAILED` + 작업상태 `APPROVED`) 재처리 → **409** "검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다." + `ls_data_raw.data_stts_cd` **FAILED 유지**(PROCESSING 고착 없음). 2회 반복해도 동일 409/FAILED — 보상 롤백 정상(BatchReprocessService.java:95-100 · BatchTransitionService.java:364-386) |
| TC-BATCH-164 | PASS | [실동작] 합성 stale 행 투입(`mdfcn_dt = now-500분`, 임계 180분) 후 스윕 tick(19:31:22) 관측 → rawSn 9301(`rty_nmtm=1 < max 3`)이 `RETRYING → **PENDING**`, `rty_nmtm 1→**2**`(죽은 시도 1회 계상), `rty_prnmnt_dt = 19:32:22`(= now+`initial-delay-sec` 60s 고정 지연). 대조군 rawSn 9303(`mdfcn_dt = now-5분`, 임계 미달)은 **RETRYING 그대로 유지** — 정상 처리 중 항목 오회수 없음. 로그 `stale RETRYING reclaimed=1 exhausted=1 staleTimeoutMinutes=180` |
| TC-BATCH-165 | PASS | [실동작] 같은 tick 에서 rawSn 9302(`rty_nmtm=3 == max_rty_nmtm 3`)는 복귀하지 않고 `**EXHAUSTED**` 로 종결(`rty_prnmnt_dt=NULL`, `rty_nmtm` 미증가) — 무한 부활 차단. [정적] 두 UPDATE 조건이 `rtyNmtm < maxRtyNmtm` / `>=` 로 상호배타(LsBatRtyWtngRepository.java:117·135)이며 양쪽 모두 `sttsCd='RETRYING' AND mdfcnDt <= :cutoff` 를 UPDATE 조건에 재실어 2노드 동시 회수 시 한쪽만 1행(CAS) |
| TC-BATCH-166 | PASS | [정적] `MIN_STALE_TIMEOUT_MINUTES=30`(BatchRetryStaleReclaimSweeper.java:64) + 생성자 clamp `staleTimeoutMinutes<1 ? 180 : max(30, v)`(:94-96) — 0/음수는 180, 5는 30 으로 상향. [실동작] 기동 로그 `staleTimeoutMinutes=180`(기본값 경로) |
| TC-BATCH-167 | PASS | [실동작] **`authoring.batch.enabled=false` 인 local 에서도** 기동 로그 `[BatchRetry][Reclaim] stale reclaim scheduled intervalMs=900000 staleTimeoutMinutes=180` + 전용 데몬 스레드 `batch-retry-stale-reclaim` 로 tick 관측 → 자기 토글만 본다는 계약 실증. [정적] `@ConditionalOnProperty` 없이 `@Value(...stale-reclaim.enabled:true)` + `Executors.newSingleThreadScheduledExecutor`(:106-111), `@Scheduled` 미사용 |
| TC-BATCH-168 | PASS | [정적] `run()` 이 `catch (Throwable)` 로 삼키고 `e.getClass().getSimpleName()` 만 ERROR 로그 후 0 반환(:142-157) — 메시지·스택트레이스 미노출, `scheduleWithFixedDelay` 사멸 방지 |

**B-12 소계**: PASS 16 · FAIL 1 · PARTIAL 1 · BLOCKED 1

---

## 3. B-15. 배치 스텝 트랜잭션 경계 (5스텝 + 정적 드리프트 가드) — 11건

> 전제 재확인: 호출자 `BatchOrchestrator.process`·`AsyncDeidentifyRunner.runAsync` 둘 다 무-트랜잭션.
> **[실동작 공통 근거]** 컨테이너 40시간 가동 로그 전체에서 `Executing an update/delete query` · `TransactionRequiredException` **0건**, 그 사이 FRAME_EXTRACT/YOLO/SAM2/INTERPOLATE 단계가 rawSn 26·27·31·38 등에서 정상 완주.

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-180 | PASS | [정적] `YoloAutolabelStep.java:154-157` — `@Override @Transactional(controlTransactionManager, REQUIRES_NEW) public void execute(BatchContext)`, 내부는 `ctx.setHints(run(...))` 자기호출. `bumpLabelVersionIn` 은 이 경계 안에서 수행. [실동작] 배치 YOLO 단계가 예외 없이 완주(단 이 환경 ai-server 가 `weights_missing` mock 응답이라 검출 0건 → `LBL_VER` 증가 자체는 미관측). 커버: `YoloStepTransactionBoundaryIntegrationTest#결함1_무트랜잭션_호출자에서_YOLO_execute가_프록시경유로_트랜잭션을_열어_LBL_VER를_증가시킨다`(baseline PASS) |
| TC-BATCH-181 | PASS | [정적] `Sam2SegmentStep.java:133-135` REQUIRES_NEW. [실동작] `[Batch][Sam2] saved polygons rawSn=… count=0` 정상 커밋 종료(예외 0) |
| TC-BATCH-182 | PASS | [정적] `TrackInterpolationStep.java:103-105` REQUIRES_NEW. [실동작] `[Batch][Interpolation] no interpolation candidates` 정상 종료 |
| TC-BATCH-183 | PASS | [정적] `VlmTimeseriesStep.java:218-220` — `@Transactional(REQUIRES_NEW)` 이며 **`readOnly` 미지정(=쓰기 가능)**. 분기 `runWithMarking`(:252, 쓰기) / `run`(:236, readOnly) 은 자기호출이라 상위 경계 상한을 따름. ⚠ 근거 드리프트: 카탈로그 `128-135` → 실제 `218-220` |
| TC-BATCH-184 | PASS | [정적] `FfmpegFrameExtractor.java:128-130` REQUIRES_NEW. [실동작] rawSn 27 배치가 `ls_data_src` 5행(301~305, 원본/비식별 2경로 컬럼 모두 채움) 커밋 — 무-트랜잭션이면 불가능 |
| TC-BATCH-185 | PASS | [정적] 5스텝 모두 `execute` 안에서 `this.run(...)`/`this.extractByMarks(...)` **자기호출**(YoloAutolabelStep.java:156 · FfmpegFrameExtractor.java:141 · Sam2/Interpolate/Vlm 동형) → 어드바이스 미적용 → REQUIRES_NEW 1회. 주석에 "프록시 경유로 바꾸면 중첩" 규약 명시 |
| TC-BATCH-186 | PASS | [정적] typed 진입점의 `@Transactional(REQUIRES_NEW)` 보존 확인 — `YoloAutolabelStep.java:167`, `VlmTimeseriesStep.java:236·252`, `FfmpegFrameExtractor.java:160·175`, `Sam2SegmentStep.java:146`, `TrackInterpolationStep.java:115`. ⚠ 근거 드리프트: 카탈로그 `VlmTimeseriesStep.java:150-166` → 실제 `236·252` |
| TC-BATCH-187 | PASS | [정적] `DeidentifyStep.execute`(:239-240) **무애노테이션** + `selfProvider.getObject()` 프록시 경유 `run()` 호출(:245). 테스트 `BatchStepTransactionBoundaryTest.BOUNDARY_EXEMPT`(:53)에 등재 + `selfProxyStepsMustNotAnnotateExecute` 가 애노테이션 **부재를 단언**(중첩 방지). [실동작] 선두 비식별이 rawSn 27·29 에서 `de_ident_yn='Y'`+procLog SUCCEEDED 를 커밋 |
| TC-BATCH-188 | PASS | [정적] `MarkingLoadStep.execute`(:49-58) 은 조회 + `parseMarks` JSON 파싱만 — DML 0건. `BOUNDARY_EXEMPT` 등재. 근거 라인 소폭 드리프트(카탈로그 50-58 → 실제 49-58) |
| TC-BATCH-189 | PASS | [정적] `BatchStepTransactionBoundaryTest.java:55-105` — `ClassPathScanningCandidateComponentProvider`+`AssignableTypeFilter(BatchStep)` 로 `kr.co.cudo.authoring` 전체 스캔, 스캔 결과 `hasSizeGreaterThanOrEqualTo(6)` 공허 단언 방지, 면제 밖 구현에 `@Transactional` non-null + `REQUIRES_NEW` + `value="controlTransactionManager"` 3중 단언. `execute(BatchContext)` **직접 선언이 없으면 `executeMethod`가 AssertionError**(:71-78) → 상속으로 숨겨도 실패. baseline PASS |
| TC-BATCH-190 | PASS | [정적] `LsDataSrcRepository.java:292-295` — `@Modifying` + `@Query(native UPDATE)` 뿐, `@Transactional` **없음**. 리포 전체 `@Modifying` 4종 모두 동일 규약(:198·224·292·305). 사유는 :283-291 주석에 명문화(스텝 원자성·프레임 락 결합·경계 누락 무증상화 방지) |

**B-15 소계**: PASS 11 · FAIL 0

---

## 4. 판정 집계

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| B-11 (신고/resolve) | 30 | 26 | 0 | 2 | 0 | 2(폐기) | 0 |
| B-12 (재처리/재시도) | 19 | 16 | 1 | 1 | 1 | 0 | 0 |
| B-15 (트랜잭션 경계) | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **60** | **53** | **1** | **3** | **1** | **2** | **0** |

> 폐기 2건(TC-DEID-034·038) 제외 시 판정 대상 58건 · PASS율 91.4%(53/58).
> **실동작 근거 비율**: 60건 중 44건이 [실동작] 1차 근거(기동 스택 요청/DB/로그), 나머지는 [정적]+baseline 테스트 커버.

---

## 5. 이슈

### [B-ISSUE-101] TC-BATCH-153 / TC-BATCH-154 — 배치 수동 재처리 원자 클레임이 2컬럼 폴백 때문에 상호배제에 실패(동일 rawSn 파이프라인 이중 실행)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `POST /v1/videos/{rawSn}/batch/retry` 는 동시 다중 요청·자동 폴러와 경합해도 **정확히 1건만** FAILED→PROCESSING 을 선점하고 나머지는 409 로 거부되어야 한다(`BatchReprocessService` javadoc "이중 파이프라인 실행 차단", CWE-362). 같은 영상의 파이프라인이 2벌 동시에 돌면 프레임 재추출·오토라벨 중복 INSERT·외부(ai-server/VLM) 중복 위탁·재시도 예산 이중 소모가 발생한다.
- **현재 동작(이슈 내용)**: `BatchTransitionService.tryClaimReprocessFromFailed`(BatchTransitionService.java:335-347)가 RAW 컬럼 클레임이 0행이면 **원인을 구분하지 않고** 작업상태 컬럼 클레임으로 폴백한다.
  ```java
  int rawClaimed = videoRepository.claimReprocessFromFailed(rawSn, FAILED, PROCESSING);
  if (rawClaimed == 1) { return true; }
  int statusClaimed = rawDataStatusRepository.claimReprocessFromFailed(rawSn, FAILED, PROCESSING);
  return statusClaimed == 1;   // ← 0행의 이유가 "남이 방금 선점" 이어도 여기로 내려온다
  ```
  정상 배치 실패는 `LS_DATA_RAW.DATA_STTS_CD` 와 `LS_RAW_DATA_STATUS.DATA_STTS_CD` 를 **함께 FAILED** 로 두므로, 호출자 A 가 RAW 컬럼을, 호출자 B 가 작업상태 컬럼을 각각 선점해 **둘 다 true** 를 받는다.
  실측(2026-08-01 19:17:39, rawSn 9109 · 5요청 동시): 응답 **200 2건 / 409 3건**, 로그가 서로 다른 스레드에서 같은 밀리초에
  ```
  19:17:39.920 [http-nio-8080-exec-7]  [BatchReprocess] manual retry claimed rawSn=9109
  19:17:39.921 [http-nio-8080-exec-11] [BatchReprocess] manual retry claimed rawSn=9109
  19:17:39.925 (exec-7)  [BatchOrchestrator] marking check rawSn=9109 count=1
  19:17:39.925 (exec-11) [BatchOrchestrator] marking check rawSn=9109 count=1
  19:17:39.929 (exec-7)  [BatchRetry] enqueued rawSn=9109 attempt=1 delaySec=60
  19:17:39.929 (exec-11) [BatchRetry] enqueued rawSn=9109 attempt=2 delaySec=120
  ```
  를 남겨 **파이프라인 2벌 동시 실행 + 재시도 카운터 이중 증가**가 확인된다.
- **재현/확인 경로**:
  ```bash
  # 전제: raw 배치상태와 작업상태가 둘 다 FAILED 인 영상 (정상 배치 실패의 기본형)
  psql -c "select r.data_stts_cd, s.data_stts_cd from ls_data_raw r
             left join ls_raw_data_status s on s.raw_data_id=r.raw_sn where r.raw_sn=9107"   # FAILED | FAILED
  for i in 1 2 3 4 5; do (curl -s -o /dev/null -w "%{http_code} " \
      -X POST http://localhost:18081/api/v1/videos/9107/batch/retry -H "Authorization: Bearer $REVIEWER") & done; wait
  # 실측: 409 409 409 200 200   ← 200 이 2건
  docker logs klid-backend | grep -c "manual retry claimed rawSn=9107"   # 2
  ```
  **대조군**(원인 격리): 작업상태 행이 없는 rawSn 11(`FAILED | (null)`)에 동일 시행 → `409 409 409 409 200`, claimed 로그 **1건**. 즉 "두 컬럼이 모두 FAILED" 일 때만 이중 클레임이 성립한다.
- **영향**: 동시성 결함(CWE-362, OWASP API6 — 민감 비즈니스 플로우 무제한 접근). ①동일 영상 프레임 추출/오토라벨의 중복 INSERT·경합 ②ai-server·mock/실 VLM 로의 중복 외부 위탁(비용·PII 전송량 증가) ③재시도 예산(`RTY_NMTM`)이 1회 실행당 2회 소모되어 조기 EXHAUSTED ④두 실행 중 늦게 끝난 쪽이 상태를 덮어써 배치 단계 상태가 비결정. REVIEWER 권한이 필요하지만 **버튼 더블클릭·프론트 재전송만으로도 자연 발생**한다.
- **테스트 사각지대**: `BatchReprocessServiceTest#배치재처리_동시요청시_한쪽만_기동된다`(:81-97)는 `transitionService.tryClaimReprocessFromFailed` 를 **mock 으로 false 고정**해 검증하므로, 실제 2컬럼 폴백 로직을 한 번도 실행하지 않는다. 회귀 가드가 없다.
- **수정 방향(제안)**: (구현하지 않음)
  1. `tryClaimReprocessFromFailed` 에서 RAW 클레임 0행일 때 **폴백 전에 RAW 상태를 재판정**한다 — RAW 가 이미 `PROCESSING`(=남이 방금 선점) 이면 즉시 `false` 를 반환하고, RAW 가 `FAILED` 가 아닌 다른 상태(작업상태만 FAILED 인 예외 형상)일 때만 작업상태 폴백을 허용.
  2. 또는 클레임 자체를 **단일 권위 컬럼**(LS_DATA_RAW)으로 일원화하고, 작업상태만 FAILED 인 형상은 별도 조건부 UPDATE 한 문장(두 테이블을 한 트랜잭션에서 `SELECT … FOR UPDATE` 후 전이)으로 처리.
  3. 회귀 가드로 **실 DB 동시성 IT**(Testcontainers, 두 스레드가 실제 `tryClaimReprocessFromFailed` 를 호출)를 추가해 "성공 1건" 을 단언 — 현행 mock 기반 단위 테스트로는 재발을 못 잡는다.

### [B-ISSUE-102] TC-DEID-044 / TC-DEID-045 — resolve 산출물 검증의 60초 스큐 관용이 "재비식별하지 않은 옛 산출물"을 통과시킨다(신고 게이트 일괄 해제)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `POST /v1/deident-reports/{rprtSn}/resolve` 는 **신고 이후 실제로 재비식별된** 산출물이 있을 때만 통과해야 한다. 이 지점의 통과는 `DE_IDNTF_YN` 을 `'F'→'Y'` 로 되돌려 라벨 조회 412·프레임 이미지 412·영상 스트리밍 404·export 보류 게이트를 **한꺼번에 여는** 단일 관문이므로, 위장 산출물 통과 = 마스킹 실패 픽셀 재노출이다(CWE-359, `verifyDeidentArtifact` javadoc 명시 목적).
- **현재 동작(이슈 내용)**: 시간 판정 (2)번 조건이 신고시각에서 **60초를 빼고** 비교한다.
  ```java
  private static final long CLOCK_SKEW_TOLERANCE_SECONDS = 60L;
  ...
  LocalDateTime mtime = LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
  fileAfterReport = mtime.isAfter(reportTime.minusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS));   // DeidentReportService.java:567-573
  ```
  따라서 **`신고시각-60s < mtime ≤ 신고시각`** 인 파일, 즉 *신고를 유발한 바로 그 비식별본* 도 "신고 이후 교체"로 인정된다. 실측(rawSn 6 / rprtSn 7): 파일 mtime `2026-07-31 03:23:21.127`, 신고 `dclr_dt=2026-07-31 03:23:21.165`(mtime 이 신고보다 **38ms 이르다**), 최신 SUCCESS procLog `rspns_dt=03:02:21`(신고 이전) — 두 조건 모두 "신고 이후 재비식별"이 아님에도 **resolve 200** 으로 통과했고 `de_ident_yn` 이 `'F'→'Y'`, 작업락 해제, `report_stts_cd=RESOLVED` 가 되었다.
  또한 `reportDt` 는 `LsDeidentReport.createReport` 의 `LocalDateTime.now()`(앱 JVM 시계)이고 mtime 도 같은 JVM 의 `ZoneId.systemDefault()` 로 환산되므로, **동일 호스트 배치에서는 보정할 스큐가 사실상 없다** — 관용치가 순수 손실로 남는다.
- **재현/확인 경로**:
  ```bash
  # 1) 비식별 산출물이 막 기록된 영상에서(=deid 파일 mtime ≈ now) 60초 안에 신고
  curl -X POST .../v1/videos/{rawSn}/deident-report -d '{"reason":"leftover face"}'
  # 2) 아무런 외부 재비식별 없이 즉시 resolve
  curl -X POST .../v1/deident-reports/{rprtSn}/resolve      # → 200
  psql -c "select de_ident_yn from ls_data_raw where raw_sn={rawSn}"   # → Y (게이트 전부 재개방)
  ```
  실증 데이터: `select r.dclr_dt from ls_deident_report r where deident_report_sn=7` = `03:23:21.165` vs `stat -c %y {deid파일}` = `2026-07-30 18:23:21.127 +0000`(=KST 03:23:21.127).
- **영향**: PII 재노출 창(CWE-359 / CWE-367). 특히 **마킹 화면 동선**이 위험하다 — 선두 비식별 완료(`MARKING_READY`) 직후 마킹을 시작해 60초 안에 누락을 신고하는 것은 정상 동선이고, 그 상태에서 resolve 를 누르면 재비식별 없이 신고가 닫히며 라벨·프레임·스트리밍·export 게이트가 모두 열린다. WORKER(본인 배정)도 resolve 권한이 있어 오·남용 표면이 넓다.
- **수정 방향(제안)**: (구현하지 않음)
  1. mtime 비교의 기준을 **완화 방향이 아니라 강화 방향**으로 바꾼다 — `mtime > reportTime` 엄격 비교로 두고, 스큐가 실제로 문제인 환경(NAS 가 다른 호스트 시계로 mtime 을 찍는 경우)만 별도 설정값으로 opt-in.
  2. 또는 "재비식별 사실"을 시각이 아니라 **콘텐츠 동일성**으로 판정한다 — 신고 시점에 그때의 비식별본 해시/크기/mtime 을 `LS_DEIDENT_REPORT` 에 스냅샷 기록하고, resolve 때 **스냅샷과 달라졌는지**를 본다(시계 의존 제거, 60초 창 소멸).
  3. 최소한 관용치를 `CLOCK_SKEW_TOLERANCE_SECONDS` 설정값으로 외부화하고 기본을 0 으로 낮춘 뒤, 실제 스큐가 관측되는 환경만 올린다.
  4. 회귀 가드: `DeidentReportServiceTest#신고이전_비식별본만_존재시_resolve_거부된다` 는 mtime 을 충분히 과거로 두어 통과하고 있다 — **경계값(`reportTime - 59s`) 케이스**를 추가해야 이 창이 드러난다.

---

## 6. 근거 드리프트 (카탈로그 정합성)

| 케이스 | 카탈로그 근거 | 실제 위치 | 비고 |
|---|---|---|---|
| TC-BATCH-183 | `VlmTimeseriesStep.java:128-135` | `VlmTimeseriesStep.java:218-220` | execute 위치 이동(약 90줄) — 실질 드리프트 |
| TC-BATCH-186 | `VlmTimeseriesStep.java:150-166` | `VlmTimeseriesStep.java:236`(run) · `252`(runWithMarking) | 실질 드리프트 |
| TC-BATCH-154 | `BatchTransitionService.java:334-362` | `334-347` | 범위 과대(포함은 함) |
| TC-BATCH-188 | `MarkingLoadStep.java:50-58` | `49-58` | 1줄 |
| TC-BATCH-167 | `BatchRetryStaleReclaimSweeper.java:42-50,84,102-110` | javadoc `42-52` · `@Value 84` · scheduler `106-111` | 소폭 |
| TC-DEID-057 | `DeidentReportService.java:480-491` | 발행문 `491`(javadoc 464-486) | 소폭 |
| TC-DEID-047 | `DeidentReportService.java:443-470` | `443-462` | 범위 과대 |
| TC-DEID-053 | `DeidentReportService.java:182-183,295-310` | `182-183` · `295-306` | 범위 과대 |
| TC-DEID-054 | `DeidentReportService.java:185-186,329-340` | `185-186` · `329-337` | 범위 과대 |

그 외 B-11/B-12/B-15 근거는 실제 위치와 일치.

---

## 7. 관측된 부수 사실 (결함 판정 아님 · 기록용)

1. **local 프로파일은 배치 Quartz 잡을 등록하지 않는다** — `application-local.yml:61-62` `authoring.batch.enabled: false`. `qrtz_triggers` 실측 3건(kpstDeidentPoll · datasetExportFailureRecovery · datasetExportPendingSweep)만 존재하고 `batchRetryTrigger` · `controlTrainingVideoScanTrigger` 는 부재. 그 결과 `ls_bat_rty_wtng` 의 도래한 PENDING 행(2026-07-31 등록분 포함)이 소진되지 않고 잔존한다. **의도된 로컬 설정**이며 TC-BATCH-159 BLOCKED 사유다.
2. **stale 회수 스윕은 배치 토글과 독립적으로 살아 있다** — 위 1번 상황에서도 `batch-retry-stale-reclaim` 데몬이 15분 주기로 tick 하며 회수를 수행했다(TC-BATCH-167 의 설계 의도 실증).
3. **`de_ident_yn` 컬럼 물리명 불일치** — 문서·주석은 `DE_IDNTF_YN` 을 쓰지만 실제 DDL/DB 컬럼은 **`DE_IDENT_YN`**(`LsDataRaw.java:118` `@Column(name = "DE_IDENT_YN")`). 코드는 일관되게 이 이름으로 매핑하므로 동작 결함은 없으나, 표준용어 관점(비식별=DE_IDNTF)과 문서 표기가 어긋나 SQL 직접 조회 시 혼동을 유발한다(본 검증에서도 최초 쿼리가 실패했다).
4. **`reExport=false`** — 신고 시 발행된 `TASK_MODIFIED`(META_UPDATED)는 `reExport=false` 로 전송된다. 신고 구간에는 export 가 게이트로 보류되므로 정합적이며, resolve 시 `DeidentReportResolvedEvent` → 재산출(`version=2`)로 이어지는 것을 실측했다(TC-DEID-058).
5. **개인정보 3필드 리셋의 벌크 UPDATE 영향행 ≠ 감사 행수** — rawSn 27 에서 `privacyReset=5`(영상 전 프레임 UPDATE) / `privacyResetAudited=3`(실제 값 보유 프레임). 감사 대상 선정이 "리셋 직전 값 보유 프레임" 이라는 계약과 일치한다(TC-DEID-052).
