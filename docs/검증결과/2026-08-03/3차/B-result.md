# B클러스터 결과 — 테스트케이스 전수 검증 3차 (2026-08-03)

## 0. 판정 집계 요약 (7개 파트 재합산 — 각 파트 원본 표를 직접 재검수한 값)

> 파트별 자체 요약 문구가 아니라 `_raw/B-part*.md` 의 **표 각 행을 직접 grep/대조**해 재계산했다. 그 결과 **part3(B-10/B-11)의 파트 헤더 요약(PASS 47/PARTIAL 3)이 실제 표(PASS 48/PARTIAL 2)와 어긋나 있어 정정**했고, part5(B-1/B-6)는 "FAIL 2건"이 실제로는 테스트케이스 FAIL이 아니라 "카탈로그 자체의 결함 2건(이슈 개수)"이었음을 파트5 서술("실제 프로덕션 동작은 전건 기대대로 확인됨")로 확인해 PASS로 재분류했다.

| 파트 | 담당 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기(분모제외) |
|---|---|--:|--:|--:|--:|--:|--:|--:|--:|
| part1 | B-2 선두비식별 브릿지+러너(8) · B-3 DeidentifyStep(16) · B-4 BatchOrchestrator 상태전이(16) | 40 | 38 | 0 | 1 | 0 | 1 | 0 | 0 |
| part2 | B-5 마킹완료 브릿지(13) · B-7 VLM 위탁/콜백/보류재개(36) | 49 | 46 | 1 | 1 | 1 | 0 | 0 | 0 |
| part3 | B-10 비디오 스트리밍(22) · B-11 비식별 누락 신고(30, 폐기 2건 포함) | 52 | 48 | 0 | 2 | 0 | 0 | 0 | 2 |
| part4 | B-12 재처리/재시도 큐(19) · B-13 KPST 위탁·폴링(34) · B-14 Quartz/인프라/헬스(10) | 63 | 61 | 0 | 2 | 0 | 0 | 0 | 0 |
| part5 | B-1 관제 학습용 적재(25) · B-6 MarkingService(27) | 52 | 51 | 0 | 1 | 0 | 0 | 0 | 0 |
| part6 | B-8 FfmpegFrameExtractor · B-9 YOLO/SAM2/Interpolate(49, TC-BATCH-119 결번) | 49 | 49 | 0 | 0 | 0 | 0 | 0 | 0 |
| part7 | B-15 트랜잭션 경계(11) · B-16 오토라벨 일괄저장(6) · B-17 FK V146(8) · B-18 영상목록 검색필터(18) | 43 | 43 | 0 | 0 | 0 | 0 | 0 | 0 |
| **합계** | | **348** | **336** | **1** | **7** | **1** | **1** | **0** | **2** |

- **PASS율**: 336/346(폐기 2건 제외) = **97.1%**
- **신규 FAIL 1건**: TC-VLM-032(part2, 1차 B-ISSUE-61 미해소 → B-ISSUE-22)
- **PARTIAL 7건**: TC-BATCH-045(part1→B-ISSUE-01) · TC-VLM-039(part2→B-ISSUE-21) · TC-STREAM-B12(part3→B-ISSUE-46) · TC-DEID-045(part3→B-ISSUE-42) · TC-BATCH-164(part4→B-ISSUE-61) · TC-BATCH-177(part4→B-ISSUE-62) · TC-BATCH-008 관련(part5→B-ISSUE-83, 표에는 개별 판정셀 없이 서술로만 존재해 PARTIAL 1건으로 편입)
- **BLOCKED 1건**: TC-BATCH-055(part2 — 동일 rawSn 동시 마킹은 단일 노드에서 V142 부분 유니크가 먼저 409로 막아 재현 불가, 2노드 필요)
- **N/A 1건**: TC-BATCH-035(part1 — `process(Long, Map)` 오버로드 호출자 0건, 실동작 검증 대상 없음)
- **폐기 2건**: TC-DEID-034·038(part3 — 라벨 스냅샷/삭제 정책 폐기로 코드 자체가 존재하지 않음, 분모 제외)

## 1. 카탈로그 정정 총계 — `git diff docs/test-cases/B-batch-deidentify.md` 실측

`git diff --stat` = **85 lines changed(85 insertions/85 deletions, 표 행 단위 치환)**. 변경된 행의 TC-ID를 파트별로 귀속하면:

| 파트 | 정정 행수 | 비고 |
|---|--:|---|
| part1 | 11 | TC-DEID-001/006/022/025 + TC-BATCH-035/036/037/038/040/042/045 |
| part2 | 11 | TC-BATCH-050/051/052/057/058/059 + TC-VLM-009/022/032/034/039 |
| part3 | 30 | TC-DEID-030~058(폐기 034·038 포함, 29행) + TC-STREAM-B17 |
| part4 | 6 | TC-BATCH-164/166/174/177/178 + TC-DEID-083 |
| part5 | 21 | TC-BATCH-001~005(5) + TC-BATCH-010~025(16) |
| part6 | 3 | TC-BATCH-114/115/135 |
| part7 | 3 | TC-BATCH-180/183 + TC-VIDEO-006 |
| **합계** | **85** | 7개 파트 합산이 `git diff` 실측 85건과 정확히 일치(중복 귀속 0건) |

> ⚠ 사전 전달받은 파트별 자체보고 총계(part1 8 · part2 11 · part4 6 · part5 21 · part6 3 · part7 3 = 52건 내외, **part3 미포함**)는 **part3의 정정 30건이 집계에서 누락되어 있었다** — part3(`_raw/B-part3.md` §7)가 B-11 전 항목(29행) + TC-STREAM-B17(1행)을 담당 라인범위 내에서 직접 Edit했으나 이 수치가 상위 보고 문구에 반영되지 않았다. **실제 diff 기준 85건**이 정본이다. part1/part2/part4/part6/part7 은 자체보고와 정확히 일치했고, part5 는 "22개 행"이라 보고했으나 diff 실측은 21건(1건 과다 보고 — 사유 불명, 담당 범위 내 정정 자체는 정확).

## 2. 심각도별 신규 이슈 집계 (24건)

| 심각도 | 건수 | ID |
|---|--:|---|
| CRITICAL | 0 | — |
| HIGH | 2 | B-ISSUE-01, B-ISSUE-42 |
| MEDIUM | 6 | B-ISSUE-21, B-ISSUE-22, B-ISSUE-41(온프렘 배포형상 한정 HIGH), B-ISSUE-61, B-ISSUE-62, B-ISSUE-81 |
| LOW | 16 | B-ISSUE-02,03,04,05,23,24,25,43,44,45,46,63,64,65,82,83 |
| **합계** | **24** | |

상세 이슈 본문은 `ISSUES.md`(append)에 심각도순으로 수록. 아래는 원본 파트 결과 병합(part1~part7, 내용 그대로).

---

## 원본 파트 병합 (part1~part7, 내용 그대로)

---


# B-part1.md

# B 클러스터 part1 — B-2 선두 비식별 브릿지+러너 / B-3 DeidentifyStep / B-4 BatchOrchestrator 상태 전이

- 검증일: 2026-08-03 (3차)
- 대상: `docs/test-cases/B-batch-deidentify.md` **B-2**(TC-DEID-001~008, 8건) + **B-3**(TC-DEID-010~025, 16건) + **B-4**(TC-BATCH-030~045, 16건) = **40건**
- 검증 워크트리: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0803` (branch `qa-0803`)
- 스택: `_raw/stack-bringup.md` 기준 재빌드 완료본(Flyway V163). `DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_BASE_URL=http://klid-mock-server:9400` — **비식별은 mock-server(KPST 대역) 실왕복 경로**
- 코드/설정 파일 **일절 수정 없음**. 카탈로그(`B-batch-deidentify.md`) 담당 라인범위(50~104행) 정정 8건만 수행 — §"카탈로그 정정" 참조

## 0. 이번 회차에 실제로 구동한 시나리오 (실동작 근거의 원천)

코드/설정 변경 없이 **신규 영상 3건**을 관제 인입 → 선두 비식별 → 마킹 → 배치까지 실제로 태웠다.
(`rawSn=101` 은 다른 클러스터 공용 데이터라 **읽지도 바꾸지도 않았다**.)

| 검체 | vms_clip_id | 원본 | 용도 |
|---|---|---|---|
| **rawSn=102** | `QA3RD-B2-DEID-001` | `/app/storage/raw/seed/clip-9102.mp4` (정상 mp4) | B-2 정상 흐름 · B-4 진입 가드 매트릭스 |
| **rawSn=108** | `QA3RD-B4-RACE-001` | `/app/storage/raw/seed/clip-9103.mp4` (정상 mp4) | B-4 동시성 반증 · 작업상태 row 부재 |
| **rawSn=114** | `QA3RD-B3-BADSRC-001` | `qa3-badclip.mp4` (11바이트 텍스트, 비-비디오) | 비식별 fail-closed 반증 |

수행 방법: `LS_DATA_INGEST` INSERT(관제가 하는 행위 대행) → `POST /v1/dev/batch/scan` → 이후 전 구간은 백엔드가 자동.
토큰: `POST /v1/dev/tokens` (REVIEWER, userNo=1001 시드 계정).

### 0-1. 인입 → 선두 비식별 실왕복 (backend 로그 원문)

```
00:25:14.489 [http-nio-8080-exec-5] TrainingVideoIngestTx  - [TrainingIngest] ingested rcptnSn=2 rawSn=102
00:25:14.492 [http-nio-8080-exec-5] IngestDeidentifyBridge - [IngestDeidentifyBridge] video ingested rawSn=102 — triggering deidentify
00:25:14.492 [batch-async-1]        AsyncDeidentifyRunner  - [AsyncDeidentifyRunner] starting deidentify rawSn=102
00:25:14.496 [batch-async-1]        AsyncDeidentifyRunner  - [AsyncDeidentifyRunner] deidentify submitted (deferred) rawSn=102 — MARKING_READY 는 폴링 완료 시 전이
00:25:14.500 [kpst-submit-1]        KpstDeidentTxService   - [KpstDeid] submitted rawSn=102 prjId=2
00:25:50.117 [Scheduler_Worker-2]   KpstDeidentTxService   - [KpstDeid] completed rawSn=102
```

mock-server(:9400) 인바운드:
```
15:25:14,498 [MOCK][KPST] project created prj_id=2 name=raw102 creator=authoring
INFO: 172.20.0.5:36508 - "POST /project HTTP/1.1" 200 OK
15:25:14,645 [MOCK][KPST] watermark burned — 'MOCK 비식별 완료' 우측하단, 길이·무결성 검증 통과 file=clip-9102-mask.mp4
INFO: 172.20.0.5:36514 - "GET /retrieve_progress HTTP/1.1" 200 OK
```

DB 상태 전이(실측 폴링):
```
t+0s   raw_sn=102  data_stts_cd=PENDING        de_ident_yn=N     ← 제출 직후, 미전이
t+35s  raw_sn=102  data_stts_cd=MARKING_READY  de_ident_yn=Y     ← 폴링 완료가 단일 전이 지점
```

### 0-2. self-fill 반증 (핵심 관심사)

산출물이 **우리가 만든 것이 아니라 외부(mock KPST)가 만든 것**임을 파일 실측으로 확인:

```
$ docker exec klid-backend md5sum /app/storage/raw/seed/clip-9102.mp4 /app/storage/raw/seed/102/deid/*.mp4
ce714a1d4512a650720e18e2391fbadd  /app/storage/raw/seed/clip-9102.mp4          (20,590 bytes)
c786236386a7e27df86ca56cd1f84d35  /app/storage/raw/seed/102/deid/clip-9102-mask.mp4 (50,854 bytes)
```

- 파일명이 mock 자체복사 상수 `deidentified.mp4` 가 **아니라** KPST 규약 `{stem}-mask{ext}` → mock-mode 자체복사 경로를 타지 않았음이 증명된다.
- 해시·크기가 원본과 다름(워터마크 재인코딩) → 원본 복사(self-fill) 아님.
- `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM = /app/storage/raw/seed/102/deid/clip-9102-mask.mp4` — 조합·추측이 아니라 응답값 기록(VERIFY-PROMPT §11 함정 회피 확인).

**판정: self-fill 결함 0건.**

### 0-3. fail-closed 반증 (rawSn=114, 비-비디오 원본)

```
$ psql -c "SELECT raw_sn, data_stts_cd, de_ident_yn FROM ls_data_raw WHERE raw_sn=114"
 114 | PENDING | F
$ psql -c "SELECT proc_stts_cd, err_cd, err_msg_cn FROM ls_deident_proc_log WHERE data_raw_sn=114"
 FAILED | DEIDENT_INCOMPLETE | deid file invalid
$ psql -c "SELECT * FROM ls_bat_rty_wtng WHERE raw_sn=114"   → 0 rows
```

- 산출물이 유효한 비디오가 아니면 **`'Y'` 위장 없이 `'F'`** 로 마감. `DATA_STTS_CD` 는 `PENDING` 유지 → **MARKING_READY 로 절대 전이하지 않음**(마킹 조기 진입 차단).
- **재시도 큐 행이 생성되지 않음** — TC-DEID-006 의 "`BatchRetryQueue` 의존 자체 없음" 이 실동작으로 확인됨(설계 결정 3).

---

## 1. B-2. 선두 비식별 브릿지 + 러너 (8건)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-001 | PASS | [실동작] 적재 커밋(`ingested rawSn=102` @00:25:14.489) **직후** 같은 http 스레드에서 `[IngestDeidentifyBridge] video ingested rawSn=102 — triggering deidentify`(@.492) → `[batch-async-1] AsyncDeidentifyRunner starting`(@.492). rawSn=102/108/114 3검체 모두 동일 순서 재현. 근거 `IngestDeidentifyBridge.java:26-31`(카탈로그 26-30, +1 드리프트 정정) |
| TC-DEID-002 | PASS | [정적] `IngestDeidentifyBridge.java:26` `@TransactionalEventListener(phase = AFTER_COMMIT)` — `fallbackExecution` 미지정 = 기본 `false`. 롤백 시 AFTER_COMMIT 동기화 콜백 자체가 호출되지 않는다(Spring 계약). ⚠ **실동작 반증 미수행** — 적재 tx 를 publish 이후 시점에 롤백시킬 진입점이 코드에 없어 재현 불가하고, 회귀 테스트도 부재(`IngestDeidentifyBridgeTest` 는 위임 1건뿐) → **B-ISSUE-05** 로 기록 |
| TC-DEID-003 | PASS | [정적] `AsyncDeidentifyRunner.java:79-83` `loadRaw(...).orElse(null)` → null 이면 WARN `raw not found rawSn={} — skip` + `return`(파이프라인 진입 전). 테스트 `AsyncDeidentifyRunnerTest:132 raw_없으면_무처리` (baseline 실패 0건) |
| TC-DEID-004 | PASS | [정적] `AsyncDeidentifyRunner.java:92-94` `if (ctx.isDeidentCompleted()) batchTransitionService.markRawDataMarkingReady(rawSn)`. ⚠ 이 환경은 `DEIDENTIFY_MOCK_MODE=false`(의도적 — self-fill 우회 금지 정책)라 mock 동기완료 분기를 라이브로 태울 수 없음. 테스트 `AsyncDeidentifyRunnerTest:71 mock_모드_비식별_동기완료시_run직후_MARKING_READY로_전이` + IT `DeidentifyStepExecutePersistenceIntegrationTest:67` 로 대체 |
| TC-DEID-005 | PASS | [실동작] rawSn=102/108/114 전건에서 제출 직후 `data_stts_cd=PENDING / de_ident_yn=N` 유지(로그 `deidentify submitted (deferred) … MARKING_READY 는 폴링 완료 시 전이`). 폴링 완료(`[KpstDeid] completed rawSn=102`) 이후에만 `MARKING_READY/Y` 로 전이 → **완료 폴링이 단일 전이 지점**임이 실증. 반증: 실패 검체 114 는 폴링이 실패로 끝나 `PENDING/F` 에 머무름(전이 없음) |
| TC-DEID-006 | PASS | [실동작+정적] ①재시도 큐 미사용 — 비식별 실패한 rawSn=114 에 `ls_bat_rty_wtng` 행 0건(실측). 생성자(`AsyncDeidentifyRunner.java:67-74`)가 pipeline/transitionService/videoRepository 3개만 받아 `BatchRetryQueue` 를 **구조적으로** 주입받지 않음. ②예외 삼킴 — `:99-106` catch(RuntimeException) → WARN(`cause=` 예외 클래스명만, 원문·경로 미노출). 테스트 `AsyncDeidentifyRunnerTest:119`. ⚠ Phase C-2 이후 이 catch 에 도달하는 실패는 **제출 이전**뿐(제출 이후는 `KpstSubmitOutcomeRecorder`/폴링이 별도 REQUIRES_NEW 로 `'F'` 기록) — 라이브 114 도 폴링 경로로 종결돼 catch 는 미도달. 카탈로그에 이 범위 축소를 명시(정정) |
| TC-DEID-007 | PASS | [정적] `AsyncDeidentifyRunner.java:112-117` `if (rawSn == null) return Optional.empty()`. ⚠ 부수 발견: 이 메서드의 `@Transactional(REQUIRES_NEW, readOnly)` 는 **`protected` + 자기호출(:79)** 이라 무효(1차 B-ISSUE-05 미해소) → **B-ISSUE-02** 로 이월 |
| TC-DEID-008 | PASS | [실동작+정적] `BatchPipelineConfig.java:50-52` `new BatchPipeline(List.of(deid))` — 정확 일치. 실동작 교차확인: 적재~비식별 구간 로그에 `MarkingLoadStep`/`VlmTimeseriesStep`/`FfmpegFrameExtractor` 가 **한 줄도 없고** 오직 `AsyncDeidentifyRunner`→KPST submit 만 발생. 테스트 `BatchPipelineConfigTest:59 선두_비식별_파이프라인은_DEIDENTIFY_단계만` |

**B-2 소계: PASS 8 / FAIL 0 / PARTIAL 0**

---

## 2. B-3. DeidentifyStep (16건)

> ⚠ 환경 제약(결함 아님): 이 스택은 정책상 `DEIDENTIFY_MOCK_MODE=false` 다(내부 self-fill 우회 금지 — VERIFY-PROMPT §1-2). 따라서 **mock 경로(TC-DEID-010~015, 018~021)는 라이브로 태울 수 없다.** 설정을 켜는 것은 §10-1 금지사항(검증 대상 동작을 바꿈)이라 수행하지 않았고, 대신 **코드 정적 대조 + 해당 경로 전용 테스트(baseline 실패 0건)** 로 판정했다. KPST 경로(016)와 execute 브릿지(023/024)는 라이브로 검증했다.

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-010 | PASS | [정적] `DeidentifyStep.java:182-190`(`@PostConstruct initBasePath` → `if (mockMode) assertMockAllowedProfile()`) + `:205-221`. allowlist 상수 `ALLOWED_MOCK_PROFILES = Set.of("local","dev","stg")`(`:95`) — prd 는 `activeHasDisallowed` 로 `IllegalStateException`. 테스트 5건(`prd_프로파일에서_mock활성시_부팅거부`, `prd와_dev가_섞인_active프로파일이면_거부`, `local과_prd가_섞인…`, `비표준_환경라벨(production)이면_거부`, `ENV가_PRD_대문자여도_거부`) |
| TC-DEID-011 | PASS | [정적] `:210` `boolean noActiveProfile = active.length == 0` → `:216-221` throw. 메시지에 `activeProfiles`/`ENV` 만 노출(PII·경로 없음, CWE-209 준수). 테스트 `active프로파일_미설정이면_거부` |
| TC-DEID-012 | PASS | [정적] `:223-227` `localActive` 아니면 WARN 1줄. dev/stg 는 `ALLOWED` 라 throw 를 통과. 테스트 3건(dev/stg/local 허용) + `dev_프로파일에서_mock활성_부팅시_비식별경고_WARN로그를_1줄_남긴다` |
| TC-DEID-013 | PASS | [정적] `:213-214` `envNormalized = env.trim()` → `.toLowerCase(Locale.ROOT)` allowlist 대조 → `:216-221` throw. 반증 케이스 커버: 대문자 `PRD`·공백 포함 ` prd `·비표준 `production` 전부 거부(테스트 3건). ENV 미설정/blank 는 허용(프로파일만으로 판정) — fail-open 아님(프로파일 축이 별도로 fail-closed) |
| TC-DEID-014 | PASS | [정적] `:270-272` `if (raw == null) throw new CustomException(INVALID_INPUT, "raw 가 null 입니다.")`. 테스트 `raw_null이면_INVALID_INPUT` |
| TC-DEID-015 | PASS | [정적] `:275-277` mock 분기가 KPST 분기(`:280`)보다 **앞**. `return DeidentResult.completed(runMock(raw))` — 외부 클라이언트 호출 없음. 테스트 `mock모드_원본존재시_외부호출없이_비식별경로로_복사되고_DE_IDNTF_YN이_Y로_전이된다` |
| TC-DEID-016 | PASS | [실동작] `:280-283` `kpstDeidentService.submit(raw); return DeidentResult.deferred();`. 라이브: rawSn=102/108/114 전건이 이 분기로 흘러 mock-server `POST /project` 200 왕복(로그 원문 §0-1) + 러너가 `deferred` 로 인지해 미전이. 원본 실재 검증은 `KpstDeidentService.java:286 verifySourceOrFail` 이 submit 내부에서 수행함을 코드로 확인 |
| TC-DEID-017 | PASS | [정적] `:284-288` — mock 도 KPST 도 아니면 `ERROR` 로그(rawSn·플래그만) 후 `CustomException(INTERNAL_ERROR, "비식별 경로가 구성되지 않았습니다.")` 고정 메시지. 레거시 폴백 분기 부재 확인(메서드 전체 3분기뿐). 테스트 2건(`KPST_disabled_이고_mock도_아니면_레거시폴백_없이_설정오류_예외`, `kpstEnabled_true이지만_서비스_미주입이면_설정오류_예외`) |
| TC-DEID-018 | PASS | [정적] `:309-314` `if (source == null \|\| !Files.isRegularFile(source))` → `batchTransitionService.recordDeidentFailure(...)`(**별도 빈**의 REQUIRES_NEW, `BatchTransitionService.java:217-235`) 후 `EXTERNAL_API_ERROR` throw → `runMock` 의 REQUIRES_NEW 는 롤백되지만 `'F'` 는 독립 커밋. MARKING_READY 미전이(러너가 예외를 받아 전이 스킵). IT `DeidentifyStepFailurePersistenceIntegrationTest:67,86` |
| TC-DEID-019 | PASS | [정적] `:330-335` `catch (IOException e)` → `recordDeidentFailure(rawSn, MOCK_ERROR_CODE, e.getClass().getSimpleName())` + `INTERNAL_ERROR`. 예외 원문·경로 미로깅(CWE-209). ⚠ **copy IOException 전용 테스트는 부재**(원본부재·base거부 경로만 IT 보유) — 기능 결함 아닌 커버리지 갭이므로 이슈 미기록, 비고로만 남김 |
| TC-DEID-020 | PASS | [정적] `:345-358` `managed.markDeidentified("Y")` + `procLog.succeed(target)` + `workLockService.releaseRaw`(잠금 시) + `deidentReportService.resolveOpenReports` + `notificationService.notifyReviewersOnLockRelease` + `:363` `streamMetaCacheEvictor.evictAfterCommit`. 테스트 `mock모드_재비식별_잠금영상_성공시_releaseRaw_+_resolveOpenReports_+_알림`, `mock모드_비식별완료시_stream-meta_캐시가_실제로_무효화된다`. 교차확인: KPST 경로도 동일 부수효과를 라이브에서 관측(`[Notification] lock-released rawSn=102`) |
| TC-DEID-021 | PASS | [정적] `:385-398 copyAtomically` — `createDirectories` → **쓰기 직전 base 재계산·실경로 재검증**(`verifyRealPathUnder`, TOCTOU/CWE-367·59 방어) → tmp 복사 → `:401-407 moveAtomically`(ATOMIC_MOVE+REPLACE_EXISTING, `AtomicMoveNotSupportedException` 시 replace 폴백) → `finally Files.deleteIfExists(tmp)`. 테스트 `mock모드_재실행시_atomic_move로_멱등하게_덮어쓰고_손상되지_않는다`. 라이브 부수확인: KPST 경로에서도 `.mock-tmp` 임시 디렉터리가 `drwx------` 로 생성돼 잔여물이 남지 않음 |
| TC-DEID-022 | PASS | [정적] **카탈로그 기대값이 코드와 불일치했고 이번 회차에 정정함**(§카탈로그 정정 ①). 실제: `VideoArtifactRootResolver.resolveUnder(:424-441)` 가 **세그먼트 위반 → `INVALID_INPUT`(:432)** / **base 이탈·실경로 이탈 → `FORBIDDEN`(:438,:441)** 2축. 양쪽 다 기본 루트 폴백 없이 fail-secure 이므로 **코드 결함 아님**. `resolveSafeTargetPath(:418-421)` 가 넘기는 세그먼트는 `rawSn:Long` 과 상수 `deidentified.mp4` 뿐이라 실제 도달 불가한 방어심도 분기라는 카탈로그의 판단은 정확. → 1차 **B-ISSUE-25 해소**(카탈로그 정정으로) |
| TC-DEID-023 | PASS | [실동작+정적] `:239-250 execute` 에 `@Transactional` **없음**(확인) + `:245-246` `selfProvider.getObject().run(ctx.getRaw())` 프록시 경유. 라이브: 무트랜잭션 호출자(`AsyncDeidentifyRunner.runAsync`)에서 실행돼 `LS_DEIDENT_PROC_LOG` REQUESTED 행이 **실제로 커밋**됨(rawSn=102 procLogSn=68) — 프록시가 REQUIRES_NEW 를 실제로 열었다는 증거. IT `DeidentifyStepExecutePersistenceIntegrationTest` |
| TC-DEID-024 | PASS | [실동작] `:249` `ctx.markDeidentCompleted(result.completed())`. 라이브: KPST 경로에서 `completed()=false` 가 브릿지돼 러너가 `deidentify submitted (deferred)` 분기로 감(로그). 반대 축(=true)은 `AsyncDeidentifyRunnerTest:106 execute가_완료신호를_안주면_기본_false라_MARKING_READY_미전이` 로 기본값 방어까지 커버 |
| TC-DEID-025 | PASS | [정적] `BatchStepTransactionBoundaryTest.java:53` `BOUNDARY_EXEMPT = Set.of(DeidentifyStep.class, MarkingLoadStep.class)`, `:88-90` 에서 continue. 면제 사유는 `DeidentifyStep.java:116-126`(`selfProvider` javadoc)에 명문화됨. 가드 자체는 나머지 스텝에 `REQUIRES_NEW` 를 강제(`:97-100`) |

**B-3 소계: PASS 16 / FAIL 0 / PARTIAL 0** (mock 경로 9건은 `[정적]` 근거 — 사유는 절 머리말)

---

## 3. B-4. BatchOrchestrator 상태 전이 (16건)

### 3-0. 진입 가드 매트릭스 — 라이브 반증 실행 결과

동일 영상(rawSn=102)의 작업상태만 바꿔가며 `POST /v1/dev/batch/trigger?rawSn=102` 를 5회 발사한 실측:

| 작업상태 입력 | 응답 finalStage | 소요 | LS_DATA_RAW | 작업상태 | 프레임수 |
|---|---|---|---|---|---|
| PENDING | **SKIPPED** | 1ms | COMPLETED(불변) | PENDING(불변) | 2(불변) |
| IN_REVIEW | **SKIPPED** | 2ms | COMPLETED(불변) | IN_REVIEW(불변) | 2(불변) |
| APPROVED | **SKIPPED** | 2ms | COMPLETED(불변) | APPROVED(불변) | 2(불변) |
| REJECTED | **SKIPPED** | 2ms | COMPLETED(불변) | REJECTED(불변) | 2(불변) |
| **ASSIGNED** | FAILED | 79ms | **FAILED** | **FAILED** | 2 |

- SKIPPED 4건은 **1~2ms** 에 반환됐고 로그에 `MarkingLoadStep`·`VlmTimeseriesStep`·`FfmpegFrameExtractor` 가 **한 줄도 없다** → "step 을 한 건도 실행하지 않음" 이 실증됨(외부 VLM 호출 0건도 mock-server 로그로 확인).
- ASSIGNED 는 차단되지 않고 파이프라인이 실제 실행됨 → `REVIEW_OWNED_STATUSES` 4종 정의가 실동작과 일치.
- 백엔드 로그: `[BatchTransition] work status transition skipped (review-owned) rawSn=102 current=APPROVED target=PROCESSING` → `[BatchOrchestrator] skipped — review-owned work status rawSn=102`

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-030 | PASS | [정적+실동작] `BatchOrchestrator.java:101-103` `if (rawSn == null) throw INVALID_INPUT`. 컨트롤러 경유로는 `@Min(1)` 이 앞서 막음(라이브: `rawSn=0` → 400 `trigger.rawSn: must be greater than or equal to 1`, 파라미터 생략 → 400 `필수 파라미터가 누락되었습니다: rawSn`) → null 도달 자체가 REST 로는 불가하나 내부 호출자(`AsyncBatchRunner`/Quartz) 대비 방어는 유효 |
| TC-BATCH-031 | PASS | [정적] `:149-153` `videoRepository.findById(rawSn).orElseThrow(NOT_FOUND)`. 라이브에서는 컨트롤러 자체 사전조회가 먼저 404 를 냄(`rawSn=99999999` → 404 `rawSn=99999999 영상이 존재하지 않습니다.`)이라 orchestrator 분기는 그늘에 가림. ⚠ 부수 발견: `loadRaw` 가 `protected` + 자기호출(`:104`)이라 `@Transactional(REQUIRES_NEW, readOnly)` 무효(1차 B-ISSUE-24 미해소) → **B-ISSUE-03** 이월 |
| TC-BATCH-032 | PASS | [실동작] rawSn=102/108 정상 완주. 로그: `marking check count=1` → `VlmTimeseries describe submit` → `FrameExtract frames=2` → `Yolo saved labels` → `Sam2 saved polygons` → `Interpolation` → `[BatchOrchestrator] completed rawSn=102`. DB: `LS_DATA_RAW=COMPLETED`, `LS_RAW_DATA_STATUS=ASSIGNED`, `ls_bat_rty_wtng` 행 삭제(=`retryQueue.clear`) |
| TC-BATCH-033 | PASS | [실동작] 프레임 재추출 시 `uk_ls_data_src_raw_frame` 위반 → `DataIntegrityViolationException`. 로그: `[BatchRetry] enqueued rawSn=102 attempt=1 delaySec=60` → `[BatchOrchestrator] failed rawSn=102 willRetry=true cause=DataIntegrityViolationException`. DB: `ls_bat_rty_wtng`(bat_rty_sn=63, rty_nmtm=1, max=3, PENDING, rty_prnmnt_dt=+60s) + `LS_DATA_RAW=FAILED` + 작업상태 `FAILED` |
| TC-BATCH-034 | PASS | [실동작] rawSn=108 동시 5요청 중 4건 실패로 attempt 1→2→3→4 소진: `[BatchRetry] max attempts exceeded -- exhausted rawSn=108 attempt=4 max=3` → `[BatchOrchestrator] failed rawSn=108 willRetry=false` → 응답 `finalStage=FAILED` |
| TC-BATCH-035 | **N/A** | [정적] 토글을 넘기는 오버로드 `process(Long, Map)` 의 **프로덕션·dev 호출자 0건**(`grep -rn "orchestrator.process("` → 5곳 전부 `process(rawSn)` 단일 인자). REST/Quartz 어느 진입점으로도 태울 수 없어 실동작 검증 대상이 존재하지 않는다. 1차 **B-ISSUE-23 미해소** → **B-ISSUE-04** 이월. 카탈로그에 도달불가 표기 추가(정정 ③) |
| TC-BATCH-036 | PASS | [정적+실동작] `BatchContext.java:77-84 isStageEnabled` — 키 미존재/명시 null → `true`. `:70-72` 생성자가 `stageToggles == null` 이면 `Map.of()` 로 정규화. 라이브: `process(rawSn)`(=토글 null) 경로에서 MARKING/VLM/FRAME/YOLO/SAM2/INTERPOLATE 6단계 전부 실행 로그 확인. 근거 라인 78-85 → **77-84 로 정정**(정정 ②) |
| TC-BATCH-037 | PASS | [실동작] **두 테이블 책임 분리의 직접 증거.** rawSn=102 배치 완료 시 `LS_DATA_RAW.DATA_STTS_CD=COMPLETED` / `LS_RAW_DATA_STATUS.DATA_STTS_CD=ASSIGNED`(ver=2). ver 궤적 0(BATCH_QUEUED insert)→1(PROCESSING)→2(ASSIGNED) 가 `markRawDataCompleted` 가 COMPLETED 로 점프하지 않았음을 증명 |
| TC-BATCH-038 | PASS | [실동작] 위 037 로 배치 완료가 작업상태를 `ASSIGNED` 로 되돌리는 것이 확인됐고, 그 상태에서 `ASSIGNED→PENDING`(검수 제출)이 정상 동작함은 동일 스택·동일 회차의 `_raw/pipeline-drive.md §1`(rawSn=101: 배정 ASSIGNED(ver3) → WORKER 제출 PENDING(ver4) → IN_REVIEW(ver5) → APPROVED(ver6))로 실증. 작업 `COMPLETED` 는 `ReviewService.approve` 전용 경로임을 코드로 재확인 |
| TC-BATCH-039 | PASS | [실동작] rawSn=102(ASSIGNED 입력) 실패 실행 후 `LS_DATA_RAW=FAILED` + 작업상태 `FAILED` 동시 전이 확인 — `MARKING_READY` 고착 없음. `BatchTransitionService.java:184-197`(정확 일치) |
| TC-BATCH-040 | PASS | [실동작] rawSn=102 정상 배치에서 작업상태 row 가 `BATCH_QUEUED`(ver0)로 생성된 뒤 **ver1=PROCESSING**, ver2=ASSIGNED 로 2회 UPDATE 됨 → `markRawDataProcessingBlocked` 가 false 를 반환하며 두 컬럼을 함께 PROCESSING 으로 전이했음을 ver 궤적으로 증명(동기 구간이라 중간 상태 직접 관측은 불가, 낙관적 버전이 대체 증거). 근거 라인 110-124 → **110-122 로 정정** |
| TC-BATCH-041 | PASS | [실동작] rawSn=108 의 `ls_raw_data_status` 행을 삭제 후 트리거 → 로그 `[BatchTransition] raw data status not found rawSn=108 target=PROCESSING` … `target=ASSIGNED` **2회 WARN**, 예외 없이 파이프라인 완주(`finalStage=COMPLETED`, 프레임 2건 재생성). 작업상태 row 는 끝까지 0건 유지(파생 RAW 형상 재현) |
| TC-BATCH-042 | PASS | [실동작] §3-0 매트릭스 4행. SKIPPED + 1~2ms + step 로그 0줄 + `LS_DATA_RAW` 불변 + 프레임 불변 + 외부 호출 0건 전부 확인 |
| TC-BATCH-043 | PASS | [실동작+정적] `BatchTransitionService.java:77-81` 4종 상수 확인 + §3-0 5행 매트릭스가 **PENDING/IN_REVIEW/APPROVED/REJECTED 만 차단되고 ASSIGNED 는 통과**함을 실측으로 증명(집합 경계 반증 완료) |
| TC-BATCH-044 | PASS | [정적] `:151-154`(markRawDataCompleted) · `:189-193`(markRawDataFailed) 둘 다 `if (transitionRawDataStatus(...)) return;` 로 `LS_DATA_RAW` 미변경. ⚠ 진입 가드(`:112-115`)가 먼저 SKIPPED 로 끊어 `process()` 경로에서는 **도달 불가한 방어심도**이며, 코드 주석도 그렇게 명시(`:190`). §3-0 매트릭스가 이를 뒷받침(APPROVED 입력 시 `LS_DATA_RAW` 불변) |
| TC-BATCH-045 | **PARTIAL** | [실동작] 진입점 축은 충족 — `grep` 으로 5개 호출자(`BatchDevTriggerController:119`, `BatchRetryQuartzJob:55`, `AsyncBatchRunner:25`, `BatchQuartzJob:51`, `BatchReprocessService:87`)가 **전부 `process(Long)` 단일 관문**을 지남을 확인. **그러나 가드가 원자 클레임이 아니라 동시 실행 축에서는 "무증상 오염 차단"이 성립하지 않는다** — 동일 rawSn 동시 5요청이 전부 가드를 통과해 파이프라인 5벌이 병렬 실행됐다(§4 B-ISSUE-01). 1차 **B-ISSUE-22 미해소** |

**B-4 소계: PASS 14 / PARTIAL 1 / N/A 1 / FAIL 0**

---

## 4. 이슈 기록

### [B-ISSUE-01] TC-BATCH-045 — `BatchOrchestrator.process()` 진입 가드가 원자 클레임이 아니어서 동일 rawSn 동시 실행이 전혀 차단되지 않는다 (1차 B-ISSUE-22 미해소 · 심각도 상향)

- **심각도**: **HIGH** *(1차 MEDIUM → 상향. 근거: 이번 회차에 ①외부 벤더 5중 위탁 ②재시도 예산 즉시 소진 ③종단 상태 비결정성 3가지 신규 실증)*
- **기대 동작(기대효과)**: `BatchOrchestrator` 클래스 Javadoc 이 **"영상 단위 직렬 호출 보장 — `process(Long)` 는 단일 영상(rawSn) 에 대해 단일 스레드에서 호출된다"**(`BatchOrchestrator.java:50-51`)고 단언하고, TC-BATCH-045 는 진입 가드를 "APPROVED 영상에 AUTO 라벨이 새로 적재되는 무증상 오염 차단" 관문으로 규정한다. 파이프라인은 프레임 추출·AUTO 라벨 적재·**외부 VLM 위탁** 같은 비멱등 부수효과를 수행하므로, 같은 rawSn 에 두 실행이 겹치면 안 된다. 2노드 Active-Active 형상에서는 JVM 락이 방어가 되지 않아 DB 수준 클레임이 필요하다(`CLAUDE.md`: "Quartz 클러스터링은 트리거 중복만 막고 잡 내부 레이스는 원자 클레임이 별도로 막는다").
- **현재 동작(이슈 내용)**: 진입부의 조건부 UPDATE 는 **"검수 소유 상태인가"만** 판정하고 `PROCESSING` 은 차단 집합에 없어, **이미 실행 중인 영상도 그대로 통과**한다.
  ```java
  // BatchOrchestrator.java:112-115
  if (transitionService.markRawDataProcessingBlocked(rawSn)) {   // 검수 소유 4상태만 차단
      log.warn("[BatchOrchestrator] skipped — review-owned work status rawSn={}", rawSn);
      return BatchStage.SKIPPED;
  }
  ```
  ```java
  // BatchTransitionService.java:77-81  (PROCESSING·BATCH_QUEUED·ASSIGNED·FAILED 는 차단 대상 아님)
  public static final Set<String> REVIEW_OWNED_STATUSES = Set.of(
          STTS_PENDING, STTS_IN_REVIEW, STTS_APPROVED, STTS_REJECTED);
  ```
  **라이브 실측 (2026-08-03, rawSn=108 · 마킹 1건 보유 · 프레임 사전 삭제 후 동시 5요청)**:
  ```
  응답: COMPLETED 1건 / FAILED 4건  (409·SKIPPED 0건)

  backend 로그 (같은 밀리초에 5개 스레드가 동시 진입)
  00:30:12.329 [exec-6]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.330 [exec-8]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.332 [exec-7]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.332 [exec-10] MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.333 [exec-5]  MarkingLoadStep - marking check rawSn=108 count=1
  → ★외부 VLM 5중 위탁 (서로 다른 request_id 5개, mock-server 가 5회 수락)
  00:30:12.334 VlmTimeseriesStep - describe submit rawSn=108 request_id=55763134-…
  00:30:12.334 VlmTimeseriesStep - describe submit rawSn=108 request_id=94981639-…
  00:30:12.336 VlmTimeseriesStep - describe submit rawSn=108 request_id=3478cb28-…
  00:30:12.337 VlmTimeseriesStep - describe submit rawSn=108 request_id=51813285-…
  00:30:12.338 VlmTimeseriesStep - describe submit rawSn=108 request_id=92c7134a-…
  → ★재시도 예산이 단 1회 사고로 전량 소진
  00:30:12.469 BatchRetryQueue - enqueued rawSn=108 attempt=1 delaySec=60
  00:30:12.470 BatchRetryQueue - enqueued rawSn=108 attempt=2 delaySec=120
  00:30:12.472 BatchRetryQueue - enqueued rawSn=108 attempt=3 delaySec=240
  00:30:12.473 BatchRetryQueue - max attempts exceeded -- exhausted rawSn=108 attempt=4 max=3

  DB: ls_raw_data_status.ver  2 → 12   (=5×PROCESSING + 5×종단 = 10회 UPDATE)
  ```
  - **① 외부 벤더 5중 위탁** — 1차에서는 검체에 마킹이 없어 관측되지 않았던 신규 피해다. 동일 영상에 대해 VLM `describe` 가 5회 나가고 5개 콜백이 되돌아와 `LS_DATA_META` 를 순서 없이 덮어쓴다(로그: `result applied … updated=1` ×5). 외부 과금·자원 낭비 + 마지막 콜백이 이기는 비결정 결과.
  - **② 재시도 예산 즉시 소진** — 동시 실패 4건이 같은 `rty_nmtm` 카운터를 경쟁 증가시켜 **단일 사고로 `max-attempts=3` 가 그 자리에서 EXHAUSTED** 된다. 이후 진짜 재시도가 필요한 실패에 재시도가 남아 있지 않다.
  - **③ 종단 상태 비결정** — 이번엔 우연히 COMPLETED 스레드가 마지막에 끝나 `LS_DATA_RAW=COMPLETED` 로 남았지만, 순서가 뒤집히면 파이프라인이 성공했는데도 `FAILED` 로 마감된다(마지막 쓰기 승).
  - 개별 진입점의 클레임(`tryClaimBatchQueued` / 재시도 큐 CAS / `tryClaimReprocessFromFailed`)은 **서로 다른 락**이라 진입점이 다르면 교차 방어가 되지 않고, dev 트리거의 `PROCESSING` 사전 검사(`BatchDevTriggerController.java:83-86`)는 read-then-act TOCTOU 다(위 실측에서 5건 전부 통과).
- **재현/확인 경로**:
  ```bash
  # 마킹이 있는 영상(rawSn=N)에 대해
  psql -c "DELETE FROM ls_data_src WHERE raw_sn=N;"      # 재추출 가능 상태로
  for i in 1 2 3 4 5; do (curl -sX POST ".../v1/dev/batch/trigger?rawSn=N" -H "Authorization: Bearer $TOK") & done; wait
  psql -c "SELECT ver FROM ls_raw_data_status WHERE raw_data_id=N;"   # 10 증가
  docker logs klid-backend | grep "describe submit rawSn=N" | wc -l   # 5
  ```
- **영향**: 데이터 정합(프레임·AUTO 라벨 중복 적재, 상태 전이 경합) · **외부 연동 계약 위반**(동일 작업 다중 위탁) · 가용성(재시도 예산 소진) · CWE-362(Race Condition) · CWE-1223(비원자 check-then-act) · CWE-770(외부 호출 무제한). 2노드 Active-Active + Quartz 클러스터링 형상에서 정확히 미방어인 구간.
- **수정 방향(제안)**: `markRawDataProcessingBlocked` 를 **"진입 클레임"** 으로 승격 — `LS_DATA_RAW.DATA_STTS_CD` 가 `PROCESSING` 이 **아닐 때만** `PROCESSING` 으로 바꾸는 조건부 UPDATE(영향 행수 1 인 호출만 진행, 0 이면 `SKIPPED`). 정상 종료 3경로(COMPLETED/FAILED/예외)에서 해제 보장 + **stale `PROCESSING` 회수 스윕**(`BatchRetryStaleReclaimSweeper` 동일 패턴, 노드 사멸 시 영구 고착 방지) 동반 필요. ⚠ 구현은 하지 않음.

### [B-ISSUE-02] TC-DEID-007 — `AsyncDeidentifyRunner.loadRaw` 의 `@Transactional(REQUIRES_NEW, readOnly)` 가 `protected`+자기호출로 무효 (1차 B-ISSUE-05 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 근거와 코드 주석이 "REQUIRES_NEW readOnly 로 영상 메타를 조회한다"(`AsyncDeidentifyRunner.java:109`)고 선언하면 실제로 그 경계가 적용돼야 한다. `CLAUDE.md` 도 "자기호출로 프록시를 우회하지 말 것"을 별도 구속 규칙으로 두고 있다(프레임 이미지 서빙 절).
- **현재 동작(이슈 내용)**: 1차 지적 이후 코드 무변경.
  ```java
  // AsyncDeidentifyRunner.java:79
  LsDataRaw raw = loadRaw(rawSn).orElse(null);        // ← this.loadRaw (프록시 미경유)
  // :110-112
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected Optional<LsDataRaw> loadRaw(Long rawSn) { ... }
  ```
  두 가지 이유로 동시에 무효다 — ①자기호출(AOP 프록시 우회) ②`AnnotationTransactionAttributeSource` 기본 `publicMethodsOnly=true` 라 `protected` 는 어드바이스 대상 아님.
- **재현/확인 경로**: 정적. 런타임은 `logging.level.org.springframework.transaction=TRACE` 로 적재 트리거 시 `loadRaw` 구간에 `Creating new transaction` 이 없음을 확인(코드 수정 금지라 이번 회차 미수행).
- **영향**: 현재 실피해 없음(`@Async` 라 앰비언트 tx 없음 + `SimpleJpaRepository` 자체 readOnly tx + `LsDataRaw` 는 연관 매핑 없는 평면 엔티티). 위험은 미래 — pre-marking 파이프라인이 확장돼 `runAsync` 가 트랜잭션 안에서 호출되거나 `LsDataRaw` 에 연관이 붙는 순간 전제가 조용히 깨진다. 주석이 사실과 달라 후속 개발자를 오도한다.
- **수정 방향(제안)**: 조회를 별도 빈(`…LookupService`)의 `public @Transactional(readOnly)` 로 분리하거나 `DeidentifyStep` 이 이미 쓰는 `ObjectProvider<Self>` 패턴 적용. 둘 다 아니면 어노테이션을 제거하고 javadoc 을 실제 동작에 맞춰 정정(선언만 남기는 것이 가장 나쁘다). 재발 방지로 "`@Transactional` 이 `private`/`protected` 에 붙으면 실패"하는 정적 가드 추가. ⚠ 구현은 하지 않음.

### [B-ISSUE-03] TC-BATCH-031 — `BatchOrchestrator.loadRaw` 도 동일한 `protected`+자기호출 무효 패턴 (1차 B-ISSUE-24 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: `BatchOrchestrator.process` Javadoc 이 *"process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), 영상 메타 조회를 위한 짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리"*(`BatchOrchestrator.java:83-84`)라고 선언한다.
- **현재 동작(이슈 내용)**: 1차 지적 이후 코드 무변경.
  ```java
  // BatchOrchestrator.java:104
  LsDataRaw raw = loadRaw(rawSn);          // ← 자기호출(프록시 우회)
  // :147-149
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected LsDataRaw loadRaw(Long rawSn) { ... }   // ← protected + self-invoke = 사문화
  ```
  동일 패턴이 `AsyncDeidentifyRunner.java:110-117`(B-ISSUE-02), `DevPipelineRunner.java` 에도 있으며 주석에 *"BatchOrchestrator.loadRaw 패턴"* 이라 적혀 **의도적으로 복제 전파**됐다.
- **재현/확인 경로**: 정적(위 grep). 라이브 rawSn=102/108 정상 완주로 현재 기능 영향이 없음은 확인됨.
- **영향**: 현재 기능 영향 없음. 위험은 미래(연관 매핑 추가 시 `LazyInitializationException`, 조회 추가 시 커넥션 왕복 2회) + 주석-구현 불일치.
- **수정 방향(제안)**: B-ISSUE-02 와 동일 처방. 세 곳을 한 번에 정리하고 정적 가드로 재발 차단. ⚠ 구현은 하지 않음.

### [B-ISSUE-04] TC-BATCH-035 — stage 토글 오버로드 `process(Long, Map)` 의 호출자가 여전히 0건(도달 불가 경로) (1차 B-ISSUE-23 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: Javadoc 이 *"토글 대상 단계(FRAME_EXTRACT/YOLO/SAM2)가 off 면 … dev 단일 파이프라인 수렴 경로에서 사용한다"*(`BatchOrchestrator.java:91-99`)라고 용도를 명시하고, TC-BATCH-035 는 그 시나리오를 검증 대상으로 잡는다.
- **현재 동작(이슈 내용)**: 프로덕션·dev 통틀어 이 오버로드를 호출하는 코드가 **한 곳도 없다**(1차 이후 무변경).
  ```
  $ grep -rn "orchestrator.process(" backend/src/main/java
  BatchDevTriggerController.java:119:  orchestrator.process(rawSn);
  BatchRetryQuartzJob.java:55:         orchestrator.process(rawSn);
  AsyncBatchRunner.java:25:            orchestrator.process(rawSn);
  BatchQuartzJob.java:51:              orchestrator.process(rawSn);
  BatchReprocessService.java:87:       orchestrator.process(rawSn);
  ```
  `BatchContext.stageToggles`·`BatchStep.isEnabled` 오버라이드 3종·단위테스트(`BatchOrchestratorTest`, `BatchContextToggleTest`)만 남아 커버리지 지표상으로는 드러나지 않는다.
- **재현/확인 경로**: 위 grep. `BatchDevTriggerController.trigger` 는 `rawSn` 만 받는다(`:76-78`) — REST 로 토글을 넘길 수 없다.
- **영향**: 기능 영향 없음. 테스트만 존재하는 미사용 분기라 "지원되는 기능"으로 오인될 수 있고, TC-BATCH-035 는 실동작 검증 대상이 없다(이번 회차 **N/A**).
- **수정 방향(제안)**: ①dev 토글이 필요하면 `BatchDevTriggerController.trigger` 에 optional `stages` 파라미터를 붙여 진입점 복구(`@Profile("!prd")` 안이라 노출 위험 없음), 또는 ②불필요하면 오버로드·`stageToggles`·3개 `isEnabled` 오버라이드·관련 테스트를 함께 제거하고 카탈로그에서 TC-BATCH-035/036 을 폐기 표기. ⚠ 구현은 하지 않음.

### [B-ISSUE-05] TC-DEID-002 — 적재 롤백 시 비식별 미트리거를 보증하는 회귀 테스트·재현 경로가 없다

- **심각도**: LOW (커버리지 갭 — 현재 동작 결함 아님)
- **기대 동작(기대효과)**: TC-DEID-002 는 "적재 tx 롤백 → 리스너 미호출"을 P1 로 규정한다. 이 단언이 깨지면 **DB 에 없는 영상에 대해 외부 KPST 위탁이 나가고** 원장(`LS_DEIDENT_PROC_LOG`)에 고아 행이 쌓인다.
- **현재 동작(이슈 내용)**: 동작 자체는 Spring 계약으로 보장된다(`IngestDeidentifyBridge.java:26` — `fallbackExecution` 미지정 = 기본 `false` → 롤백 시 AFTER_COMMIT 콜백 미실행).
  ```java
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onVideoIngested(VideoIngestedEvent event) { ... }
  ```
  그러나 ①이를 고정하는 테스트가 없고(`IngestDeidentifyBridgeTest` 는 `VideoIngestedEvent_수신_시_AsyncDeidentifyRunner_위임` 1건뿐 — 리스너를 직접 호출하므로 트랜잭션 축을 전혀 검증하지 않는다) ②`fallbackExecution = true` 를 실수로 추가하거나 이벤트 발행이 tx 밖으로 옮겨져도 **아무 테스트도 실패하지 않는다**. 이번 회차에도 publish 이후 롤백을 유발할 진입점이 코드에 없어 라이브 반증을 수행하지 못했다.
- **재현/확인 경로**: 현재로선 없음. 검증하려면 `@SpringBootTest` + `TestTransaction`/의도적 예외로 `TrainingVideoIngestTx.ingestOne` 을 롤백시키고 `AsyncDeidentifyRunner` 스파이가 **0회 호출**됨을 단언하는 IT 가 필요하다.
- **영향**: 회귀 감지 불가(잠재). 실제 유출 시 외부 벤더에 존재하지 않는 영상이 위탁된다.
- **수정 방향(제안)**: `IngestDeidentifyBridgeTest` 에 트랜잭션 축 IT 1건 추가 — ①커밋 시 1회 호출 ②롤백 시 0회 호출 ③무트랜잭션 publish 시 0회 호출. 동일 패턴이 `MarkingBatchBridge`·`DatasetExportBridge` 등 AFTER_COMMIT 브릿지 전반에 적용 가능. ⚠ 구현은 하지 않음.

---

## 5. 카탈로그 정정 (담당 라인범위 50~104행, 총 8건)

| # | 대상 | 정정 내용 | 사유 |
|:--:|---|---|---|
| ① | **TC-DEID-022** | 기대결과를 *"base 이탈 → `INVALID_INPUT`"* 단일 축 → **2축**(세그먼트 위반 `INVALID_INPUT` / base·실경로 이탈 **`FORBIDDEN`**)으로 재기술. 근거를 `DeidentifyStep.java:408-420` → `DeidentifyStep.java:418-421 · VideoArtifactRootResolver.java:424-441` 로 교체 | **기대값이 코드와 불일치**(1차 B-ISSUE-25). 그대로 두면 차기 회차가 "FORBIDDEN 이 떴으니 FAIL" 로 오판 |
| ② | **TC-BATCH-036** | 근거 `BatchContext.java:78-85` → **`:77-84`** | 라인 드리프트(isStageEnabled 실제 위치) |
| ③ | **TC-BATCH-035** | 기대결과에 "⚠ **실동작 검증 불가** — `process(Long, Map)` 호출자 0건(1차 B-ISSUE-23 미해소)" 명시 | 전제가 도달 불가한데 표기가 없어 매 회차 실동작 시도가 반복됨 |
| ④ | **TC-DEID-025** | 근거 `BatchStepTransactionBoundaryTest.java:BOUNDARY_EXEMPT` → **`:53,88-90`** + 상수 실값 `Set.of(DeidentifyStep.class, MarkingLoadStep.class)` 명기 | 앵커만 있어 대조 비용이 큼 + 면제 대상이 2종임이 드러나지 않음 |
| ⑤ | **TC-DEID-001** | 근거 `:26-30` → **`:26-31 · AsyncConfig.java:35-50`**, 기대결과에 "리스너는 적재 스레드 동기 실행 / 비식별만 `batchAsyncExecutor`(core2·max4·queue50·CallerRunsPolicy)로 이관, 큐 포화 시 역압으로 적재 스레드가 직접 실행(의도된 설계)" 추가 | 라인 드리프트 + 실행 스레드 모델이 케이스에 없어 "비동기니까 적재가 안 막힌다"는 오독 소지 |
| ⑥ | **TC-DEID-006** | 기대결과에 "⚠ 이 catch 에 도달하는 실패는 Phase C-2 이후 **제출 이전**뿐(제출 이후는 `KpstSubmitOutcomeRecorder`/폴링이 별도 REQUIRES_NEW 로 `'F'` 기록)" 추가. 근거에 `:29-37`(실패 정책 javadoc) 병기 | Phase C-2 논블로킹 전환으로 케이스의 검증 범위가 실제로 좁아졌는데 표기 미반영 |
| ⑦ | **TC-BATCH-037/038/040/042** | 근거 라인 정정 — 037/038 `BatchTransitionService:146-160`/`147-160` → **`:146-158`**, 040 `:110-124` → **`:110-122`**, 042 `BatchOrchestrator:110-118` → **`:112-115`** / `BatchTransitionService:110-124` → **`:110-122`** | 메서드 실제 종료 라인과 불일치(각 ±2) |
| ⑧ | **TC-BATCH-045** | 기대결과에 "⚠ 이 가드는 클레임이 아니다 — `PROCESSING` 이 차단 집합 밖이라 동일 rawSn 동시 실행을 막지 못한다(3차 실측: 동시 5요청 → 파이프라인 5벌 + 외부 VLM 5중 위탁). '무증상 오염 차단'은 순차 경로에서만 성립(B-ISSUE-22 미해소)" 추가. 근거 `:110-118` → **`:112-115`** | 케이스가 가드를 무조건적 관문으로 단언해 **거짓 PASS 를 유도**하고 있었음 |

> 폐기(취소선) 처리한 케이스는 없다. 신규 케이스 추가도 없다(범위 밖).

---

## 6. 이전 회차 이슈 대조

| 이슈 | 내용 | 이번 회차 상태 |
|---|---|:--|
| **B-ISSUE-22**(1차) | `process()` 동시 중복 실행 미차단 | ❌ **미해소 — 심각도 상향(MEDIUM→HIGH)**. 외부 VLM 5중 위탁·재시도 예산 즉시 소진·종단 상태 비결정 3건 신규 실증 → **B-ISSUE-01** 로 이월 |
| **B-ISSUE-05**(1차) | `AsyncDeidentifyRunner.loadRaw` 자기호출 | ❌ 미해소(코드 무변경) → **B-ISSUE-02** 로 이월 |
| **B-ISSUE-21**(1차) | B-3 근거 라인 +10 일괄 드리프트 | ✅ **해소** — B-3 16건 중 15건이 정확히 일치(TC-DEID-010~021,023~024). 잔여는 TC-DEID-022 한 건뿐이며 이번 정정 ①로 처리 |
| **B-ISSUE-23**(1차) | `process(Long,Map)` 호출자 0건 | ❌ 미해소(grep 결과 동일) → **B-ISSUE-04** 로 이월. 카탈로그에 도달불가 표기 추가(정정 ③) |
| **B-ISSUE-24**(1차) | `BatchOrchestrator.loadRaw` 자기호출 | ❌ 미해소(코드 무변경) → **B-ISSUE-03** 으로 이월 |
| **B-ISSUE-25**(1차) | TC-DEID-022 기대 ErrorCode 불일치 | ✅ **해소** — 이번 회차 카탈로그 정정 ①로 2축 기술로 교체 |
| **2차 타겟재검증** | 마킹 영구고착 / 재처리 원자클레임 / 스트리밍 심링크 PII | ✅ **여전히 유효** — 이번 담당 범위와 직접 겹치지 않으나 교차 관측으로 회귀 없음 확인: ①마킹 고착 — rawSn=102/108 의 배치 skip 4회에서 마킹이 `SKIPPED` 종결되어 재마킹 409 없음 ②재처리 클레임 — `tryClaimReprocessFromFailed` 의 0행 원인 재판정 로직 코드상 존치(`BatchTransitionService.java:327-341` javadoc + 구현) ③스트리밍 — 이번 회차 미터치(B-10 담당 범위) |
| B-ISSUE-06/07(1차) | scan tick readOnly tx 커넥션 점유 / `PROCESSING` 좀비 | 담당 범위 밖(B-1). 단 B-ISSUE-06 은 **이번 회차 CallerRunsPolicy 관측과 결합 시 악화 가능** — 큐 포화 시 비식별이 scan tick 스레드에서 실행돼 외부 tx 점유 시간이 늘어난다(사실만 기록) |

---

## 7. 이번 회차가 남긴 테스트 데이터

| 테이블 | 추가/변경 | 비고 |
|---|---|---|
| `ls_data_ingest` | `rcptn_sn=2,8,14` (`QA3RD-B2-DEID-001`/`QA3RD-B4-RACE-001`/`QA3RD-B3-BADSRC-001`) | 전부 DONE/종결 |
| `ls_data_raw` | `raw_sn=102`(COMPLETED/Y), `108`(COMPLETED/Y), `114`(PENDING/**F** — 의도된 실패 검체) | `evnt_type_cd='INTRUSION'` 은 102/108 만 수동 세팅(관제 인입 컬럼 부재 갭, 기존 사항) |
| `ls_raw_data_status` | `raw_data_id=102`(FAILED), `108` **행 없음**(TC-BATCH-041 실험으로 삭제) | |
| `ls_data_src` | 102: 2건, 108: 2건 | |
| `ls_bat_rty_wtng` | `bat_rty_sn=63`(raw_sn=102, PENDING attempt=1) | 60초 뒤 자동 재시도 예정 |
| 파일시스템 | `/app/storage/raw/seed/qa3-badclip.mp4`(11B), `102/deid/`, `108/deid/`, `114/…` | |

> **rawSn=101 은 조회조차 하지 않았다**(다른 클러스터 공용 데이터 보호).

---

# B-part2.md

# B 클러스터 part2 — B-5(마킹 완료 브릿지) + B-7(VLM 위탁·콜백·보류/재개) 검증 결과

- 회차: 2026-08-03 3차 · 담당 범위: `docs/test-cases/B-batch-deidentify.md` **B-5(TC-BATCH-050~062, 13건)** + **B-7(TC-VLM-001~040, 36건)** = **49건**
- 검증 방식: 풀스택 실동작(backend :18081/api · mock-server :9400 · PostgreSQL) + 코드 정적 대조. 빌드/테스트 미실행, 프로덕션 코드 미수정.
- 신규 구동 데이터(이번 회차 생성): rawSn **103**(B-5 skip) · **104**(신고→보류→해소 재위탁) · **105**(미결 회수/readOnly 재현) · **106**(정상 VLM 왕복 + 콜백 케이스) · **109**(신고 구간 재처리) · **113**(비식별 경로 부재 fail-closed) · **115**(ASSIGNED 클레임). rawSn=101/102 는 조회만.

## 0. 판정 집계

| 판정 | 건수 |
|---|---:|
| PASS | 46 |
| FAIL | 1 |
| PARTIAL | 1 |
| BLOCKED | 1 |
| 합계 | **49** |

- FAIL 1건 = TC-VLM-032(1차 **B-ISSUE-61 미해소** — 실동작 재현)
- PARTIAL 1건 = TC-VLM-039(1차 **B-ISSUE-66 미해소** — 실동작 재현)
- BLOCKED 1건 = TC-BATCH-055(2노드 동시 INSERT 경합 필요)
- 카탈로그 정정: **11건**(B-5 6건 · B-7 5건, 아래 §4)

---

## 1. B-5. 마킹 완료 브릿지 (13건)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-050 | PASS | [정적] `MarkingBatchBridge.java:113-119` findById empty → WARN + `skip()`. [실동작] 미존재 rawSn 마킹은 API 가 먼저 `NOT_FOUND`(REVIEWER) 로 막아 브릿지 가드는 방어심도 — `POST /v1/videos/99999999/markings` → `{"errorCode":"NOT_FOUND"}` |
| TC-BATCH-051 | PASS | [실동작] stage=COMPLETED 인 rawSn=115 재마킹 → **412** `이미 처리된 영상은 재마킹할 수 없습니다`(프리컨디션 선차단). [정적] `:91-92,125-130` SKIP_BATCH_STAGES={PROCESSING,COMPLETED} 가드 존재 |
| TC-BATCH-052 | PASS | [실동작] rawSn=103 을 `de_ident_yn='N'` 로 두고 마킹 → **412** `비식별이 완료된 영상에서만 마킹할 수 있습니다`(선차단, 원복 완료). [정적] `:134-139` deid 가드 + `skip(REASON_NOT_DEIDENTIFIED)` |
| TC-BATCH-053 | PASS | [실동작] rawSn=115(작업상태 **ASSIGNED**) 마킹 → 응답 `batchTriggered=true`, 로그 `[MarkingBatchBridge] enqueued rawSn=115` (tx1 `tryClaimBatchQueued` 성공 경로) |
| TC-BATCH-054 | PASS | [실동작] rawSn=106(**미배정 — 작업상태 row 부재**) 마킹 → `batchTriggered=true`, `ls_raw_data_status` 신규 행 생성 후 배치 진행(tx1 false → tx2 `tryCreateBatchQueuedRow`) |
| TC-BATCH-055 | BLOCKED | 단일 노드에서는 재현 불가 — 같은 rawSn 의 동시 마킹은 V142 부분 유니크가 먼저 409 로 막아 **이벤트가 1건만 발생**한다. `saveAndFlush` + 호출부 `catch(DataIntegrityViolationException)`(`:154-160`)은 정적 확인 |
| TC-BATCH-056 | PASS | [실동작] 클레임 SQL(`transitionToBatchQueuedIfNotSkipped`, `LsRawDataStatusRepository:63-68`)을 rawSn=115 에 그대로 2회 실행 → **UPDATE 1 / UPDATE 0**(check-and-set 직렬화 확인, ASSIGNED 로 원복) |
| TC-BATCH-057 | PASS | [실동작] rawSn=103(작업상태 **PENDING**=검수 소유) 마킹 → 201 + `batchTriggered=false`, `batchSkipReason="검수 진행/완료(또는 반려) 상태이거나 …"`, 로그 `batch already claimed/in-progress or review-owned rawSn=103 — skipping` |
| TC-BATCH-058 | PASS | [실동작] 같은 103 경로에서 tx1 false → tx2 `existsById`=true → false(멱등 스킵) 후 skip 로그 1회. `BatchTransitionService.java:299-314`(근거 드리프트 정정) |
| TC-BATCH-059 | PASS | [실동작] rawSn=106 에서 신규 row `saveAndFlush` 생성 성공(즉시 flush). UK 위반 전파 분기는 TC-BATCH-055 와 같은 사유로 2노드 필요(정적 확인) |
| TC-BATCH-060 | PASS | [정적] `sanitize()` `:196-198` 존재. **주입 표면 없음**을 실측 확인 — `de_ident_yn` 은 `character(1)`, `data_stts_cd` 는 비교 통과 시 고정 상수(PROCESSING/COMPLETED)라 CR/LF 값이 로그로 흐를 수 없다(방어심도) |
| TC-BATCH-061 | PASS | [정적] `MarkingBatchBridge:76-81` 이 `BatchTransitionService.REVIEW_OWNED_STATUSES`(`:77-81`)를 그대로 합성. [실동작] 103(PENDING)이 입구에서 차단된 것이 그 근거 |
| TC-BATCH-062 | PASS | [실동작] skip 시 `batchTriggered=false`+사유, 정상 시 `batchTriggered=true`+`batchSkipReason=null`. `MarkingService.java:108,135-142` |

### ★ 1차 B-ISSUE-41(영구 고아 활성 마킹 → 재마킹 409 영구 잠금) — **해소 확인**

```
POST /v1/videos/103/markings  → 201 markingSn=71, batchTriggered=false
로그 : [MarkingBatchBridge] batch already claimed/in-progress or review-owned rawSn=103 — skipping
       [MarkingSkip] marking terminated as SKIPPED rawSn=103 markingSn=71
DB   : ls_marking(71, raw_sn=103, stts_cd='SKIPPED')      ← 활성 집합에서 제거됨
POST /v1/videos/103/markings (재시도) → 201 markingSn=72  ← 구 동작은 영구 409
```
`MarkingBatchBridge.skip()`(`:187-190`) → `MarkingSkipTxService.terminateSkipped`(REQUIRES_NEW, PENDING 한정)가 신설되어 **모든 skip 분기**가 마킹을 종결한다. 1차 수정 제안 ②(출구 회수)가 채택된 형태이며 실동작으로 확인했다.

---

## 2. B-7. VLM 위탁 Step + 콜백 + 보류/재개 (36건)

### 2-1. Step (TC-VLM-001~013, 031~033, 036~040)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-VLM-001 | PASS | [실동작] rawSn=106 `[Batch][VlmTimeseries] describe submit rawSn=106 request_id=13523b3c… hasMarking=true` (`execute` → `runWithMarking(markings.get(0))`) |
| TC-VLM-002 | PASS | [실동작] rawSn=104/105(마킹 0건) dev trigger·재개 → `hasMarking=false` (run 분기) |
| TC-VLM-003 | PASS | [정적] `:269-273` + `BatchStatusService:58-62`. 로컬 실효값이 `VLM_CLIENT_ENABLED=true` 라 live 미검증(설정 변경은 §10 금지). ⚠ 이 경로도 재개(run)에서는 B-ISSUE-21 의 read-only 문제에 걸린다 |
| TC-VLM-004 | PASS | [정적] `:261-263`. 외부 진입점이 rawSn 을 항상 채워 실경로 도달 불가 |
| TC-VLM-005 | PASS | [정적] `:276-278`. dev trigger 는 오케스트레이터가 먼저 NOT_FOUND |
| TC-VLM-006 | PASS | [실동작] rawSn=113 의 최신 SUCCEEDED procLog 경로를 NULL 로 두고 dev trigger → `ls_batch_proc_log` `VLM/FAILED/"비식별 영상 경로가 없어 VLM describe 위탁을 진행할 수 없습니다 rawSn=113"`, **mock-server 로 113 관련 describe 0건**(원본 경로 전송 코드 부재) |
| TC-VLM-007 | PASS | [정적] `:318-325` recordIssued 실패 → EXTERNAL_API_ERROR abort(전송 이전). 예외 주입 불가로 live 미검증 |
| TC-VLM-008 | PASS | [실동작] 원장 행 `reg_dt=00:27:45.913` < submit 로그 `00:27:45.916` — describe **전** 독립 커밋 확인. 콜백이 그 키로 역조회 성공(`result applied … rawSn=106`) |
| TC-VLM-009 | PASS | [정적] `switchIfEmpty(Mono.error(...))` `:364-365` → err 핸들러 `:369-370` → `VlmSubmitOutcomeRecorder.onSubmitFailed:104-123`. 빈 응답 주입은 mock 수정이 필요해 미재현(근거 라인 정정 §4) |
| TC-VLM-010 | PASS | [실동작] **논블로킹 확증** — `00:27:45.916 [batch-async-2] describe submit` → `.919 [vlm-submit-1] accepted`(전용 풀 스레드) → `.953 [batch-async-2]` 프레임 추출 계속 → `.259` 파이프라인 completed, 벤더 콜백은 **2초 뒤** `00:27:47.993` 도착. 소스에 `BLOCK_TIMEOUT`/`.block(` 없음 |
| TC-VLM-011 | PASS | [실동작] 제출 전 선커밋 — 콜백 수신부 로그 `markingsTransitioned=1`, 마킹 최종 `VLM_COMPLETED`. `:327-337` · `VlmMarkingTxService:49-59` |
| TC-VLM-012 | PASS | [실동작] rawSn=106(마킹 `VLM_COMPLETED`) 재트리거 → 재제출은 되지만 마킹 상태는 **VLM_COMPLETED 유지**(no-op, durable 역행 없음) |
| TC-VLM-013 | PASS | [실동작] mock 로그 `describe accepted … callback_url=http://klid-backend:8080/api/v1/vlm/callback` — 고정 base + `PATH_VLM`, 사용자 입력 미반영 |
| TC-VLM-031 | PASS | [실동작] rawSn=104/105/109 신고 구간 → `withheld — deident report open` + `[Batch] stage skipped recorded stage=VLM`, `ls_batch_proc_log` `VLM/SKIPPED/"비식별 누락 신고 구간 — VLM 위탁 보류(재비식별 대기)"`, **외부 호출 0건**(경로 해석 이전 게이트) |
| TC-VLM-032 | **FAIL** | [실동작] rawSn=109(마킹 보유·`'F'`) 재처리 → VLM 은 보류(예외 0)했으나 `FRAME_EXTRACT/FAILED/"비식별이 완료되지 않은 영상입니다 rawSn=109"` → `[BatchRetry] enqueued rawSn=109 attempt=1 delaySec=60` + 배치 FAILED. **1차 B-ISSUE-61 미해소** → [B-ISSUE-22] |
| TC-VLM-033 | PASS | [정적] `:302-303` — `isUnderDeidentReport` 는 조회 실패를 삼키지 않고 전파(`DeidentReportGate` javadoc 명시), 호출부에 catch 없음 |
| TC-VLM-034 | PASS | [실동작] rawSn=104 resolve → `[VlmResumeBridge] deident gate reopened rawSn=104` → `[VlmResume] resuming withheld VLM submit rawSn=104` → `describe submit … hasMarking=false` → `[vlm-submit-1] accepted` (재위탁 성공) |
| TC-VLM-035 | PASS | [실동작] rawSn=109/106 → `[VlmResume] resume skipped — timeseries meta already present rawSn=109 count=1` (멱등) |
| TC-VLM-036 | PASS | [정적] `RESUMABLE_SKIP_REASONS`(`:150-152`)에 `SKIP_REASON_DISABLED` 미포함. [실동작] `SKIP_REASON_ACK_MISSING` 기록 건(105/106)이 재개 대상으로 실제 트리거됨 → 3종 확장 확인 |
| TC-VLM-037 | PASS | [실동작] rawSn=105 재개가 `JpaSystemException` 으로 실패했으나 `WARN [VlmResume] withheld VLM resume failed rawSn=105 cause=JpaSystemException` 만 남고 예외 미전파(@Async best-effort) |
| TC-VLM-038 | PASS | [실동작] `ls_batch_proc_log(106).resp_payload_cn = {"requestId":"13523b3c…","status":"accepted"}` — 스텝 tx 밖(`vlm-submit-1`)의 REQUIRES_NEW 로 커밋됨 |
| TC-VLM-039 | **PARTIAL** | [정적] REQUIRED 유지 자체는 코드와 일치(`:58-62`). 그러나 근거로 든 "위험 부재"가 재개 경로에서 성립하지 않음 — `run()` 의 `readOnly=true` 와 결합해 **INSERT 실패**(실동작 재현) → [B-ISSUE-21] |
| TC-VLM-040 | PASS | [실동작] 오케스트레이터가 프록시 `execute` 호출 → 파이프라인 경로의 보류 감사 행이 정상 커밋(104/105/109). 내부 run/runWithMarking 은 자기호출이라 중첩 없음 |

### 2-2. 콜백 수신 (TC-VLM-014~025, 030)

전부 실 HTTP 호출(`POST /api/v1/vlm/callback`)로 검증.

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-VLM-014 | PASS | 미발급 request_id → **401** `{"errorCode":"UNAUTHORIZED","message":"발급되지 않은 request_id 입니다."}` |
| TC-VLM-015 | PASS | PROCESSED 키(`13523b3c…`) 재전송 → 200 `{"applied":false}`, 메타 미증가 |
| TC-VLM-016 | PASS | `raw_sn=NULL` 원장(`QA3RD-CB-NORAW`) → **401** `request_id 에 매핑된 rawSn 이 없습니다.` |
| TC-VLM-017 | PASS | `status:"done"` → **400**(DTO `@Pattern` 1차 차단). 해당 원장 키는 **ISSUED 유지**(멱등 미마킹 → 재전송 허용) 확인 |
| TC-VLM-018 | PASS | `status:"failed"` → 200 applied=true, 원장 **PROCESSED**, `[Webhook][Vlm] describe failed … code=E1` 기록 |
| TC-VLM-019 | PASS | [정적] `:112-115`. 실경로 도달 불가 — 원장 FK(`fk_ls_webhook_idempotency_raw`, ON DELETE SET NULL)라 영상 삭제 시 rawSn 이 null 이 되어 401 로 먼저 걸린다(방어심도) |
| TC-VLM-020 | PASS | 한 콜백 내 `0-5` 2건 → **400** `한 콜백 내 중복 구간(start_sec-end_sec)은 허용되지 않습니다.` |
| TC-VLM-021 | PASS | 기존 `0-5` 갱신 + 신규 `5-10` → `ls_data_meta` 0-5 값만 `UPDATED-BY-QA` 로 변경, 신규 meta_sn=262 생성. `ls_data_meta_review` 는 **신규 meta 에만** PENDING 행 추가(기존 metaSn 225 의 검수행 중복 생성 없음) |
| TC-VLM-022 | PASS | [실동작] `markingsTransitioned=1`. [정적] 조회 범위는 `ACTIVE_STATUSES`+`issuedAt` 필터(카탈로그 기대결과 보완 §4) |
| TC-VLM-023 | PASS | [정적] `:63` 단일 `@Transactional` + `:164` `markProcessedInTx`(REQUIRED). 중간 실패 주입 불가로 live 미검증 |
| TC-VLM-024 | PASS | [실동작] **동일 request_id 3건 동시 전송** → `applied` **true 1건 / false 2건**, `ls_data_meta` 신규 1행, 원장 PROCESSED 1행(비관적 락 직렬화) |
| TC-VLM-025 | PASS | [실동작] `error.message="line1\nINJECTED-FAKE-LOG\r\tTAB"` → 로그 `message=line1_INJECTED-FAKE-LOG__TAB`(CR/LF/tab 치환) |
| TC-VLM-030 | PASS | [실동작] HMAC 없이 콜백 직접 호출 시 발급 게이트가 401 로 차단(TC-VLM-014 와 동일 응답) |

### 2-3. ★ 반증 항목 — 미결 스위퍼·원자 클레임·디스패치 (카탈로그 케이스 없음, 실동작 확인)

1. **미결 스위퍼가 실제로 회수한다** — `ls_webhook_idempotency` 에 `ISSUED`+`mdfcn_dt` 경과 행을 심고 스윕 tick 대기:
   ```
   00:44:20.011 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=105 stage=VLM
   00:44:20.017 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=106 stage=VLM
   00:44:20.021 [vlm-submit-reclaim] WARN [Vlm][Reclaim] pending VLM submit reclaimed=2 ackWindowMin=30 callbackWindowMin=360
   ```
   `ls_batch_proc_log` 에 `VLM/SKIPPED/"VLM describe 수락 응답·콜백 미수신 — 미결 회수 후 재개"` 2행 적재, 원장 행은 `ISSUED→FAILED`(회수 표식)로 전이됨. **ACK 창 30분 / 콜백 창 360분이 서로 다른 설정키**(`stale-timeout-minutes` / `callback-timeout-minutes`)로 로그에 그대로 노출된다(`VlmSubmitPendingSweeper:118-124,192-200`).
2. **원자 클레임이 동시 재위탁을 막는다** — 회수 클레임 SQL(`claimStale`: `... WHERE idmp_key=? AND stts_cd='ISSUED' AND mdfcn_dt<=cutoff`)을 같은 키에 2회 실행 → **UPDATE 1 / UPDATE 0**. 콜백이 먼저 도착해 PROCESSED 가 되면 0행이 되어 도착한 결과를 덮지 않는다(`claimAccepted` 도 `sttsCd='ACCEPTED'` 조건 동봉).
3. **완료 신호는 `publishOn` 이 아니라 명시적 디스패치로 전용 풀에서 실행된다** — ACK/실패 기록 로그의 스레드가 항상 **`vlm-submit-1`**(`AsyncConfig:77-88` `threadNamePrefix="vlm-submit-"`, AbortPolicy)이고 reactor-netty 이벤트 루프(`reactor-http-nio-*`)나 `batch-async-*` 가 아니다. 호출부는 `SubmitSignalDispatch.run(...)`(`:45-58`)으로 감싸며 풀 거부 시 기록을 포기하고 스위퍼에 위임한다.
4. **ISSUED→ACCEPTED 전이 존재** — `VlmSubmitOutcomeRecorder.onAccepted:65-86` 이 `ledger.recordAckReceived`(조건부 UPDATE `stts_cd='ISSUED'`)를 호출. 정상 왕복 건은 최종 `PROCESSED` 로 관측됐고(콜백이 즉시 도착하는 mock 특성), ACK-만-수신 상태의 `ACCEPTED` 잔존은 mock 이 2초 내 콜백을 보내 재현되지 않았다(콜백 창 패스는 정적 확인).

---

## 3. 이슈

### [B-ISSUE-21] TC-VLM-039 / TC-VLM-003 — 재개 경로의 `run()` 이 `readOnly=true` 라 보류 감사 기록이 read-only INSERT 로 실패한다 (1차 B-ISSUE-66 **미해소**, 3차 실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개 러너/미결 스위퍼가 부른 위탁이 **다시 보류**(게이트가 아직 닫힘)되거나 비활성으로 끝나면 그 사실이 `LS_BATCH_PROC_LOG` 에 사유와 함께 남아야 한다(B-ISSUE-24 규약 — "재처리 대상 식별이 애플리케이션 로그 보존기간에 종속되면 운영에서 복구 불가"). `VlmWithheldResumeRunner` javadoc(`:50`)도 "게이트가 아직 닫혀 있으면 스스로 다시 보류된다"를 전제한다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.run` 이 `@Transactional(REQUIRES_NEW, readOnly = true)`(`VlmTimeseriesStep.java:236`)인데 그 안에서 부르는 `BatchStatusService.recordVlmSkipped` 는 `REQUIRED`(`BatchStatusService.java:58-62`)라 read-only 트랜잭션에 참여해 PostgreSQL 이 INSERT 를 거부한다.
  ```java
  // VlmTimeseriesStep.java:236
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public VlmTimeseriesResponse run(Long rawSn) { return doSubmit(rawSn, null); }
  // → doSubmit:271(DISABLED) · :305(DEIDENT_REPORT) 에서 recordVlmSkipped(REQUIRED, INSERT)
  ```
  **3차 실동작 재현**(rawSn=105, `DE_IDNTF_YN='F'` 유지 + 미결 스위퍼가 재개 트리거):
  ```
  00:44:20.011 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=105 stage=VLM   ← 스위퍼(REQUIRES_NEW) 성공
  00:44:20.018 [batch-async-2]      [VlmResume] resuming withheld VLM submit rawSn=105
  00:44:20.020 [batch-async-2]      [Batch][VlmTimeseries] withheld — deident report open rawSn=105
  00:44:20.021 [batch-async-2] ERROR ERROR: cannot execute INSERT in a read-only transaction
  00:44:20.022 [batch-async-2] WARN  [VlmResume] withheld VLM resume failed rawSn=105 cause=JpaSystemException
  ```
  `ls_batch_proc_log(105)` 에는 스위퍼가 쓴 `ACK_MISSING` 행만 있고 **재보류(`DEIDENT_REPORT`) 행은 적재되지 않았다**.
- **재현/확인 경로**:
  ```sql
  -- 게이트가 닫힌(F) 영상 + 시계열 메타 0건 + ISSUED 원장(ACK 창 경과)을 만든 뒤 스윕 tick(기본 15분) 대기
  insert into ls_webhook_idempotency (idmp_key,chnl_cd,stts_cd,raw_sn,reg_dt,mdfcn_dt)
    values ('QA-RO','VLM','ISSUED', <rawSn>, now()-interval '90 minutes', now()-interval '90 minutes');
  select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn=<rawSn>;
  ```
- **영향**: 데이터정합/운영. ①재보류 사실이 DB 에 남지 않아 "로그 보존기간 비종속" 보장이 이 경로에서만 깨진다 ②ERROR 스택이 남아 실장애와 구분이 어렵다 ③`vlm.client.enabled=false` 인 dev/stg/prd 기본 형상에서는 **모든** 재개(run 분기)가 이 예외로 끝나 `SKIP_REASON_DISABLED` 행도 남지 않는다. 기존 보류 기록 + "메타 0건" 조건이 남아 다음 회수에서 재시도되므로 영구 정체는 아니다(`max-reclaims=3` 소진까지).
- **수정 방향(제안)**: ①`run` 의 `readOnly=true` 제거(= `runWithMarking` 과 경계 속성 일치) 또는 ②`doSubmit` 의 `recordVlmSkipped` 호출을 이미 존재하는 `recordVlmSkippedInNewTx`(`BatchStatusService:78-82`)로 교체. ②가 비동기 완료 핸들러·스위퍼·`ledger.recordIssued` 와 규약이 같다. 회귀 가드는 **프록시 경유 `run` 호출 시 SKIPPED 감사 행이 실제로 커밋된다**는 IT(현행 `VlmTimeseriesStepTest` 는 mock 기반이라 이 경계를 못 잡는다).

### [B-ISSUE-22] TC-VLM-032 — 신고 구간 배치 재처리가 VLM 은 보류하지만 FRAME_EXTRACT 에서 실패해 배치 FAILED + 재시도 예산을 소진한다 (1차 B-ISSUE-61 **미해소**, 3차 실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 비식별 누락 신고는 정책적 차단이므로 "실패가 아니라 보류"여야 한다. TC-VLM-032 는 "예외 미발생 → 배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진"을 요구한다.
- **현재 동작(이슈 내용)**: VLM 은 규약대로 보류하지만 바로 다음 단계가 같은 `'F'` 를 예외로 처리한다.
  ```java
  // FfmpegFrameExtractor.java:185-188
  if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {   // "Y" 만 통과 → 'F' 도 실패
      throw new CustomException(ErrorCode.INVALID_INPUT, "비식별이 완료되지 않은 영상입니다 rawSn=" + ...);
  }
  ```
  **3차 실동작**(rawSn=109 — 정상 배치 1회 완료 → 신고 접수 → 재처리):
  ```
  00:30:38.330 [Batch][VlmTimeseries] withheld — deident report open rawSn=109     ← 보류(정상)
  00:30:38.331 [Batch] stage skipped recorded rawSn=109 stage=VLM
  00:30:38.335 [BatchRetry] enqueued rawSn=109 attempt=1 delaySec=60                ← 기대 위반
  00:30:38.336 [BatchOrchestrator] failed rawSn=109 willRetry=true cause=CustomException
  ls_batch_proc_log: FRAME_EXTRACT / FAILED / "비식별이 완료되지 않은 영상입니다 rawSn=109"
  ```
- **재현/확인 경로**: `POST /v1/videos/{rawSn}/deident-report` → `POST /v1/dev/batch/trigger?rawSn={rawSn}` → `select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn={rawSn}` + `select * from ls_bat_rty_wtng where raw_sn={rawSn}`.
- **영향**: 기능/운영. 신고 구간 자동 재시도가 반드시 실패하며 예산(3회, 60/120/240초)을 태우고, 영상이 FAILED 로 표시돼 장애로 오인된다. 해소 시 자동 복구는 VLM 재개뿐이라 프레임추출 이후 단계는 수동 `batch/retry` 가 필요하다. 외부 유출은 없다(전송은 정상 차단).
- **수정 방향(제안)**: `FfmpegFrameExtractor` 의 비식별 선행 가드를 `'N'` 만 실패로 두고 `'F'` 는 VLM 과 동일하게 보류(SKIPPED + 사유 적재)로 분기하거나, `BatchStep.isEnabled(ctx)` 로 신고 구간에서 post-marking 단계를 통째로 건너뛰고 재시도 큐에 넣지 않는다. 어느 쪽이든 해소 시 `DeidentGateReopenedEvent` 소비자를 늘려 프레임추출 이후도 재개돼야 한다.

### [B-ISSUE-23] 카탈로그 정합 — B-5 skip 케이스 4건이 1차 B-ISSUE-41 해소(마킹 `SKIPPED` 종결)를 반영하지 않았다
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 기대결과가 현재 구현의 계약을 담아야, 다음 회차가 "skip 인데 마킹이 PENDING 으로 남는다"를 결함으로 재발견하거나 반대로 종결 누락을 놓치지 않는다.
- **현재 동작(이슈 내용)**: TC-BATCH-050/051/052/057 의 기대결과가 "WARN, 미트리거"에 머물러 있었고, 신설된 `MarkingBatchBridge.skip()`(`:187-190`) → `MarkingSkipTxService.terminateSkipped`(REQUIRES_NEW·PENDING 한정) 계약이 어디에도 없었다. 또 050~052 가 마킹 API 경로에서 **프리컨디션에 선차단되어 도달 불가**(방어심도)라는 사실도 미기재였다.
- **재현/확인 경로**: `docs/test-cases/B-batch-deidentify.md` 109~121행 대조 · 위 §1 실동작 로그.
- **영향**: 카탈로그 정합(다음 회차 오판 위험).
- **수정 방향(제안)**: **이번 회차에서 정정 완료**(TC-BATCH-050/051/052/057 기대결과 + 근거 `MarkingBatchBridge.java:187-190`·`MarkingSkipTxService` 추가). `UNCERTAINTIES.md` 및 1차 ISSUES 의 B-ISSUE-41 을 **해소 처리**할 것.

### [B-ISSUE-24] 카탈로그 정합 — B-7 근거·기대결과 드리프트 4건(TC-VLM-009 라인 · TC-VLM-022 범위 · TC-BATCH-058/059 라인)
- **심각도**: LOW
- **기대 동작(기대효과)**: `file:line` 근거로 다음 회차가 대조 가능해야 하고, 기대결과가 실제 판정 로직을 담아야 한다.
- **현재 동작(이슈 내용)**: ①TC-VLM-009 근거 `VlmTimeseriesStep.java:373-377` 은 **동기 조립 실패 catch** 구간이고 케이스가 말하는 `switchIfEmpty(Mono.error(...))` 는 `:364-365`, err 핸들러는 `:369-370` 이다. ②TC-VLM-022 기대결과가 "VLM_REQUESTED→VLM_COMPLETED"로만 적혀 있어, 실제 조회 범위인 **`ACTIVE_STATUSES`(PENDING 포함) + 발급시각 이후 생성 PENDING 제외(L6)** 를 담지 못했다(`VlmResultService:152-161,186-208`) — 이 상태로는 "콜백 선행 레이스" 회귀를 잡을 수 없다. ③TC-BATCH-058/059 근거 `:299-320`/`:299-332` 가 다음 메서드 javadoc 까지 걸쳐 있어 실제 메서드(`:299-314`)와 어긋났다.
- **재현/확인 경로**: 각 파일 해당 라인 Read 대조.
- **영향**: 카탈로그 정합.
- **수정 방향(제안)**: **이번 회차에서 정정 완료**(4건 모두). ⚠ **`## 변경 이력` 표의 회차 행(5회차)은 병렬 파트 에이전트 간 번호 충돌을 피하려 추가하지 않았다** — 병합 단계에서 B 클러스터 정정 총계를 합산해 1행으로 추가할 것.

### [B-ISSUE-25] 커버리지 갭 — 미결 스위퍼(`VlmSubmitPendingSweeper`) 기계에 대응하는 케이스가 B-7 에 0건 (1차 B-ISSUE-65 부분 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 논블로킹 제출의 **유일한 회수 경로**(노드 사망 시 in-flight subscription 유실 대비)가 카탈로그로 검증 가능해야 한다. 1차 B-ISSUE-65 는 근거 라인 재산출과 함께 `TC-VLM-041~` 신규 케이스 추가를 제안했고, 라인은 4회차에서 재산출됐으나 **케이스 추가는 이뤄지지 않았다**.
- **현재 동작(이슈 내용)**: B-7 36건 어디에도 ①ACK 창(`stale-timeout-minutes` 30분)과 콜백 창(`callback-timeout-minutes` 360분)의 **2종 임계 분리** ②하한 clamp(`MIN_STALE_TIMEOUT_MINUTES=10` / `MIN_CALLBACK_TIMEOUT_MINUTES=60`) ③원자 클레임(`claimStale`/`claimStaleAccepted`) ④회수 예산(`max-reclaims=3`) ⑤`SubmitSignalDispatch` 풀 거부 시 기록 포기 ⑥원장 `ISSUED→ACCEPTED` 전이(`recordAckReceived`)에 대응하는 행이 없다. 이번 회차에 이 경로가 **실제로 동작함**을 확인했다(§2-3) — 즉 검증 가능한 산 코드인데 카탈로그만 비어 있다.
- **재현/확인 경로**: `grep -c 'TC-VLM-0(4[1-9])' docs/test-cases/B-batch-deidentify.md` → 0 · `ls backend/src/test/java/kr/co/cudo/authoring/batch/vlm/`(`VlmSubmitAckWindowIT`·`VlmSubmitPendingSweeperTest`·`VlmSubmitReclaimAtomicClaimIT` 등 기존 테스트 자산 존재).
- **영향**: 커버리지 갭 — 회수 임계를 잘못 좁히면 "정상 분석 중인 위탁을 뺏어 같은 비식별 영상을 중복 위탁"(H1)하는 회귀가 카탈로그로 감지되지 않는다.
- **수정 방향(제안)**: `TC-VLM-041~048` 을 신설해 위 ①~⑥ + "예산 초과 시 재개 억제(기록만)" + "rawSn 매핑 없는 원장 행 제외"를 덮는다. 기존 `batch/vlm/*` 테스트와 1:1 매핑 가능. (본 파트는 담당 라인범위 밖 행 추가를 하지 않아 제안만 남긴다.)

---

## 4. 카탈로그 정정 내역 (이번 회차 직접 수정, 11건)

| 행 | 수정 내용 |
|---|---|
| TC-BATCH-050 | 기대결과에 마킹 `SKIPPED` 종결 추가 · 근거 `:113-119` → `:113-119,187-190` |
| TC-BATCH-051 | 기대결과에 마킹 종결 + "마킹 API 경로 도달 불가(방어심도)" 명시 |
| TC-BATCH-052 | 동상 · 근거 `:134-139` → `:134-139,187-190` |
| TC-BATCH-057 | 기대결과에 **1차 B-ISSUE-41 해소**(마킹 `SKIPPED` 종결 → 재마킹 409 잠금 방지) + 201 응답 필드 명시 · 근거에 `:187-190`·`MarkingSkipTxService` 추가 · 계층 unit → integration, 우선 P1 → **P0**(고아 마킹이 영상을 영구 잠그던 회귀의 가드) |
| TC-BATCH-058 | 근거 `BatchTransitionService.java:299-320` → `:299-314` |
| TC-BATCH-059 | 근거 `:299-332` → `:299-314` |
| TC-VLM-009 | 근거 `VlmTimeseriesStep.java:373-377` → `:361-377`(switchIfEmpty `:364-365` · 동기 catch `:371-377`) |
| TC-VLM-022 | 케이스명·기대결과를 `ACTIVE_STATUSES` + `issuedAt` 이후 PENDING 제외(L6)로 보완 · 근거 `:158-161` → `:152-161,186-208` |
| TC-VLM-032 | 기대결과에 "**1차 B-ISSUE-61 미해소**(3차 실동작 재현) — 파손 지점 `FfmpegFrameExtractor:185-188`" 경고 추가 |
| TC-VLM-034 | 근거 `VlmResumeBridge.java:34-38` → `:34-40` |
| TC-VLM-039 | 기대결과에 "재개 경로에서 전제가 깨짐(B-ISSUE-21)" 경고 + `VlmTimeseriesStep.java:236` 근거 추가 |

> 합계 11행(기대결과 실질 변경 6건 + 근거 `file:line`-only 5건). ⚠ `## 변경 이력` 표의 회차 행은 **병렬 파트 간 번호 충돌 방지를 위해 추가하지 않았다** — 병합 단계에서 B 클러스터 전체 정정 총계를 합산해 5회차 1행으로 기재할 것.

## 5. 이전 회차 이슈 대조

| 이슈 | 상태 | 근거 |
|---|---|---|
| 1차 **B-ISSUE-41**(브릿지 skip → 영구 고아 마킹 → 재마킹 409 영구 잠금) | **✅ 해소** | `MarkingSkipTxService.terminateSkipped` 신설, rawSn=103 실동작(마킹 71/72 모두 `SKIPPED`, 재마킹 201) |
| 1차 **B-ISSUE-61**(신고 구간 재처리 FRAME_EXTRACT 실패 → FAILED + 재시도 소진) | ❌ 미해소(이월) | rawSn=109 실동작 재현 → [B-ISSUE-22] |
| 1차 **B-ISSUE-66**(`run()` readOnly + REQUIRED recordVlmSkipped) | ❌ 미해소(이월) | rawSn=105 실동작 재현 → [B-ISSUE-21] |
| 1차 **B-ISSUE-65**(B-7 근거 드리프트 34/36 + 신규 기계 케이스 부재) | 🔶 부분 해소 | 근거 라인은 4회차 최신화로 대체로 정합(잔여 4건 이번 회차 정정). **신규 케이스 추가는 미이행** → [B-ISSUE-25] |
| 1차 **B-ISSUE-43**(브릿지 가드 4종 마킹 API 경로 도달 불가) | 사실 유지 | 050/051/052 모두 프리컨디션 412/404 선차단 실측(방어심도로 카탈로그에 명시함) |
| 1차 **B-ISSUE-62/63/64**(TC-VLM-009/010/011 카탈로그 무효) | ✅ 해소 | 4회차 최신화로 기대결과가 Phase C-1 기준으로 재작성됨(이번 회차 실동작과 일치) |

## 6. 부수 관측 (본 파트 범위 밖 — 기록만)

- **완료 영상 재처리 시 FRAME_EXTRACT 중복키 실패**: rawSn=106(COMPLETED) 을 dev trigger 로 재실행하면 `FRAME_EXTRACT/FAILED/"duplicate key value violates ..."` + 재시도 큐 등록. VLM 은 정상 재제출됐다. B-8/B-4 담당 범위.
- **`POST /v1/deident-reports/{rprtSn}/resolve` 가 재비식별 없이 통과한 사례**: rawSn=109 는 비식별 산출물을 갱신하지 않았는데 resolve 200(1차 B-ISSUE-102 의 스큐 관용). rawSn=104 는 같은 조건에서 409 로 막혔고 `touch` 후 통과 — B-11/B-12 담당 범위.
- **수동 삽입 원장 행의 시각 함정**: DB `now()` 는 UTC, 애플리케이션은 KST `LocalDateTime` 을 쓴다. 수동으로 `now()` 를 넣은 원장 행은 앱 기준 9시간 과거로 보여 스위퍼가 즉시 회수 대상으로 잡는다(이번 회차에서 rawSn=106 의 `QA3RD-CB-DUP` 이 그 사례 — 테스트 아티팩트이며 결함 아님).

## 7. 남긴 검증 데이터 (정리 참고)

| 대상 | 키 |
|---|---|
| `ls_data_ingest` | rcptn_sn 3~6, 9, 13, (B5-CLAIM) — 전부 DONE |
| `ls_data_raw` | 103(QA3RD-B5-SKIP) · 104(QA3RD-B7-GATE) · 105(QA3RD-B7-RO, `'F'` 유지) · 106(QA3RD-B7-NORMAL) · 109(QA3RD-B7-WITHHELD) · 113(QA3RD-B7-NOPATH, procLog 경로 NULL) · 115(QA3RD-B5-CLAIM) |
| `ls_webhook_idempotency` | `QA3RD-B7-RO-STALE` · `QA3RD-CB-NORAW` · `QA3RD-CB-DUP` · `QA3RD-CB-UPSERT` · `QA3RD-CB-FAILED` · `QA3RD-CB-RACE` · `QA3RD-CB-LOGINJ` · `QA3RD-CAS-DEMO` |
| `ls_deident_report` | rprtSn 24(rawSn=104, RESOLVED) · 25(rawSn=105, **OPEN 유지**) · 27(rawSn=109, RESOLVED) |
| 원복 완료 | 103 `de_ident_yn` 'N'→'Y' · 115 작업상태 BATCH_QUEUED→ASSIGNED |

> rawSn=101/102 및 그 이전 데이터는 일절 변경하지 않았다.

---

# B-part3.md

# B 클러스터 part3 — B-10 비디오 스트리밍 / B-11 비식별 누락 신고 (3차, 2026-08-03)

- 담당 범위: `docs/test-cases/B-batch-deidentify.md` **B-10**(255~281행, TC-STREAM-B01~B22 22건) + **B-11**(282~316행, TC-DEID-030~059 30건) = **총 52건**
- 폐기 2건(TC-DEID-034·038) 제외 → **검증 대상 50건**
- 방식: **실동작 최우선**. 풀스택(docker compose, HEAD `e065da42` 재빌드분) 위에서 curl + DB 실조회 + 컨테이너 파일시스템 조작(심링크/mtime)으로 반증 시도. 빌드/테스트는 실행하지 않았다.
- 사용 데이터(실측 선정): `rawSn=101`(정상 'Y', co-locate 비식별) · `901`('N' 비식별 미수행) · `900`('F' 신고구간) · `88`(파생, 부모 900='F') · `98`(파생) · `4`(APPROVED + 라벨 10건 — 신고/해소 전 사이클) · `103`(미승인 PENDING — 이벤트 분기 대조) · `107`(동시 신고 경쟁) · `905`/`906`(경로 가드 조작 대상) · `66`(성공 procLog 부재)
- **환경 원복 완료**: 조작한 `LS_DEIDENT_PROC_LOG` 경로 2건(procLogSn 64·65), `LS_DATA_RAW.DE_IDENT_YN`(905), `LS_DATA_SRC` 개인정보 3필드(srcSn 1·2), 임시 심링크 디렉터리(`/app/storage/deidentified/videos/qa3rd*`) 전부 원복·삭제. 신고 4건(rprtSn 26·28·29·30)은 전부 RESOLVED 로 종결하고 작업락 RELEASED, 대상 영상 `DE_IDENT_YN='Y'` 복원 확인.

---

## 1. 판정 집계

| 구분 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 | 폐기(분모 제외) |
|---|--:|--:|--:|--:|--:|--:|--:|--:|
| B-10 스트리밍 | 22 | 20 | 0 | 2 | 0 | 0 | 0 | 0 |
| B-11 신고/해소 | 30 | 27 | 0 | 1 | 0 | 0 | 0 | 2 |
| **합계** | **52** | **47** | **0** | **3** | **0** | **0** | **0** | **2** |

> PASS율 = 47/50 = **94.0%**. **PII 원본 노출은 이번 전수 검증에서 1건도 재현되지 않았다** (아래 §3 반증 로그 참조). 다만 온프렘 기본 형상 전제의 잔여 노출 경로 1건(B-ISSUE-41)과 1차 이월 미해소 2건(B-ISSUE-42=구 B-ISSUE-102, B-ISSUE-46=구 B-ISSUE-82)이 남는다.

---

## 2. B-10. 비디오 스트리밍 (Range, 비식별본만 서빙)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-STREAM-B01 | PASS | [실동작] `GET /v1/videos/901/stream`(DE_IDENT_YN='N', 성공 procLog **있음**) → `404 NOT_FOUND "비식별 처리 미완료"`. 플래그 게이트가 procLog 존재보다 우선함을 확인(원본 미노출). 로그 `[VideoStream] deident not valid rawSn=901 deIdntfYn=N — refusing stream` |
| TC-STREAM-B02 | PASS | [실동작] `GET /v1/videos/999999/stream` → `404 "영상을 찾을 수 없습니다."` / [정적] `VideoStreamService.java:550-551` |
| TC-STREAM-B03 | PASS | [실동작] `'N'`(901)·`'F'`(900) 양쪽 404. `resolveDeidLocation` 이 `!"Y".equals` 로 단일 판정(`:554-558`) |
| TC-STREAM-B04 | PASS | [실동작] procLog 경로를 `/etc/passwd` · `/app/storage/raw/seed/clip-9101.mp4`(raw base 안·deid base 밖) · `/app/storage/deidentified/../../etc/passwd` 3종으로 바꿔 요청 → **전부 404** + 로그 `deid path outside all allowed bases (lexical or realpath)`. 응답·로그 어디에도 경로 원문 없음(CWE-209). 카탈로그의 "FORBIDDEN 아님, NOT_FOUND" 정정이 실제와 일치 |
| TC-STREAM-B05 | PASS | [실동작] deid base 안의 존재하지 않는 파일로 지정 → 404 + `deid file stat failed … NoSuchFileException` → `deid file not found or not a regular file`. 예외라 캐시 미적재(직후 정상 경로 복원 시 즉시 200) |
| TC-STREAM-B06 | PASS | [실동작] Range 헤더 없이 rawSn=101 → `200`, `Accept-Ranges: bytes`, `Content-Length: 50854`, 본문 md5 = 비식별본 md5(`c786236386a7e27df86ca56cd1f84d35`) ≠ 원본 md5(`ce714a1d4512a650720e18e2391fbadd`) |
| TC-STREAM-B07 | PASS | [실동작] `bytes=0-` → 206 `Content-Range: bytes 0-50853/50854` / `bytes=100-200` → 206 101바이트, 본문 md5 가 **비식별본의 해당 구간**과 일치(`0b5b74fc…`). 청크 상한 8MB 는 파일(50KB)보다 커서 `min()` 이 rangeEnd 선택 |
| TC-STREAM-B08 | PASS | [실동작] `bytes=999-0` · `bytes=abc` · `items=0-10` → 전부 `416` + `Content-Range: bytes */50854`, 본문 0바이트 |
| TC-STREAM-B09 | PASS | [실동작] `bytes=99999999-` · `bytes=50854-`(=total) → 416. 경계 `bytes=50853-` 은 206 1바이트(정상) |
| TC-STREAM-B10 | PASS | [정적] `effectiveChunkSize()` `:323-328` — `streamChunkSize < 1MB`(미설정 0·음수 포함) → `DEFAULT_CHUNK_SIZE`(8MB). 실효 설정값 `application.yml:200 = ${STORAGE_STREAM_CHUNK_SIZE:8388608}`(컨테이너 env 미지정 → 8MB). 회귀 가드 `VideoStreamServiceTest:347,371` |
| TC-STREAM-B11 | PASS | [정적] `Math.min(streamChunkSize, MAX_CHUNK_SIZE=67_108_864)` `:68,327`. `start + chunk - 1` long 오버플로 차단(CWE-190). 회귀 가드 `VideoStreamServiceTest:395,408` |
| TC-STREAM-B12 | PARTIAL | [실동작] `'N'`(901)·`'F'`(900) → `stream-url` 404 (기대 충족). **그러나 `DE_IDENT_YN='Y'` 인데 성공 procLog 가 0건인 영상(rawSn=66)은 `stream-url` 200 발급 / `stream` 404** — 1차 **B-ISSUE-82 미해소 이월**(→ B-ISSUE-46) |
| TC-STREAM-B13 | PASS | [정적] `:195-201` — `!streamUrlSigner.isConfigured()` → `SERVICE_UNAVAILABLE`(503) fail-closed, 스택/경로 미노출. 실환경은 `STREAM_SIGN_SECRET` 설정돼 있어 미설정 상태를 만들려면 재기동이 필요(비파괴 원칙상 미수행). 회귀 가드 `VideoStreamServiceTest:631` |
| TC-STREAM-B14 | PASS | [실동작] 발급 URL + HttpOnly nonce 쿠키 동반 → 200. **쿠키 없이 URL 만** → 401. `u=1001→2001` / `sig` 말미 변조 / `rawSn 101→906` / `exp` 상향 → **전부 401**. 인코딩 우회도 fail-closed: 경로 `10%31`·`101/../906`·`;a=b`·트레일링 슬래시·`//` 전부 **401**(필터 정규식이 raw URI 기준이라 매칭 실패 → 인증 컨텍스트 미설정). `0101`(제로패딩)만 200 이나 동일 rawSn 이라 우회 아님 |
| TC-STREAM-B15 | PASS | [실동작] 무인증 → 401 / WORKER(2001) **미배정** rawSn=906 → `403 "본인에게 배정되지 않은 영상입니다."` / WORKER 배정 rawSn=101 → 200. `VideoController.java:279`(@PreAuthorize) + `:288`(`labelAccessGuard.verifyRawAccess`) 진입부 배선 확인. 1차 B-ISSUE-63 해소 상태 유지 |
| TC-STREAM-B16 | PASS | [실동작] rawSn=905 를 `'N'` 으로 내려 404 → 즉시 `'Y'` 복원 → **바로 200**. `unless="#result == null"`(`:357`)로 null 이 캐시되지 않아 stale NOT_FOUND 고정 없음 |
| TC-STREAM-B17 | PASS | [실동작] rawSn=4 를 200 으로 warm 시킨 뒤 신고 접수 → 즉시 404, resolve 직후 → 즉시 200(옛 메타 재사용 없음). [정적] `DeidentReportService.java:275`(신고) · `:423`(수동 해소) · `:482`(자동 해소) 3곳 `evictAfterCommit(rawSn)`, 파생 캐시는 건드리지 않음(확정 정책). ※ 카탈로그 근거 `:244-250` 은 드리프트 — 정정함 |
| TC-STREAM-B18 | PASS | [실동작] 200 전체 응답·206 부분 응답 **양쪽** 헤더에 `Cache-Control: no-store` 실측(`Range` 유/무, `bytes=-100`·`bytes=0-0` 등 포함). `max-age` 계열 잔존 없음 |
| TC-STREAM-B19 | PASS | [실동작] **결정적 반증**: rawSn=905 를 200 으로 warm → `evictAfterCommit` 을 태우지 않고 **DB 만 직접 `DE_IDENT_YN='F'`** 로 변경 → 다음 요청 **즉시 404**. 게이트가 `stream-meta` 캐시 **앞**(`:147-152`, `stream()` 진입 첫 줄 `:225`)에서 평가됨이 캐시 무효화와 독립적으로 확인됨 |
| TC-STREAM-B20 | PASS | [실동작] 구 위치(`/app/storage/deidentified/videos/…`, rawSn=906·88·98) 와 co-locate(`/app/storage/raw/seed/101/deid/…`, rawSn=101) **둘 다 200**. [정적] `allowedDeidBases`(`:590-600`) → `VideoArtifactRootResolver.readableDeidVideoBases(:337-348)` 단일 축(프레임 추출기와 동일) |
| TC-STREAM-B21 | PASS | [실동작] rawSn=900(`'F'`) → `/stream` **404**, `/stream-url` **404**(412 아님). 같은 영상의 라벨/프레임 경로는 412 로 갈리지만 스트리밍만 404 — 응답 코드가 상태 오라클이 되지 않도록 기존 규약 유지(CWE-209) |
| TC-STREAM-B22 | PASS | [실동작] rawSn=88(파생, `ORGNL_RAW_SN=900`, 자기 값 `'Y'`) — 부모 900 이 `'F'` 인 상태에서 `/stream` **200 (50,854 bytes)**, `/stream-url` **200**. 확정 정책 ★1(자기 rawSn 만 판정)대로이며 **결함 아님**. `DeidentReportGate.java:66-71` 이 `ORGNL_RAW_SN` 을 조회하지 않음 확인 |

---

## 3. ★ PII 반증 시도 로그 (B-10 집중 — 이번 회차 최우선 과제)

1차 **B-ISSUE-81**(심링크 치환으로 원본 200 서빙, HIGH)이 2차에서 타겟 재검증 PASS 였다. 이번엔 **전수·실동작으로 재확인**했다.

| # | 공격 시나리오 | 결과 | 근거 |
|:--:|---|:--:|---|
| 1 | 비식별 파일(cold cache)을 **원본 mp4 심링크**로 치환 후 `/stream` | **404** | `deid path realpath check failed for a base` → `outside all allowed bases`. 바이트 0 유출 |
| 2 | **캐시 warm(200) 이후** 실파일 → 원본 심링크 치환 후 `/stream` | **404** | `cached deid path failed realpath re-check` — `revalidateOpenTarget` 3중 재검증(`:425-443`)이 TTL 5분 유출창을 닫음 |
| 3 | 같은 치환 상태에서 **Range 요청**(`bytes=0-1000`) | **404** | 시크 재요청 경로도 동일 차단 |
| 4 | **중간 디렉터리 세그먼트**를 base 밖 디렉터리 심링크로 치환 | **404** | `realOrNearest` 가 조상까지 접어 판정 |
| 5 | 비식별 경로를 `/etc/passwd`·raw base 파일·`../../etc/passwd` 로 조작 | **404** ×3 | 응답/로그에 경로 원문 없음 |
| 6 | 서명 URL 경로 인코딩 우회(`10%31`, `101/../906`, `;a=b`, `/`, `//`) | **401** ×5 | 필터 정규식이 raw URI 기준 → 매칭 실패 시 컨텍스트 미설정(fail-closed) |
| 7 | Range 문법 조작 9종(`-100`·다중범위·`00…001-5`·공백·`items=` 등) | 정상/416 | 경로 유추·본문 유출 없음. Range 는 경로 결정에 관여하지 않음(CWE-22 표면 없음) |
| 8 | 신고 구간(`'F'`)에서 라벨·프레임 이미지·deid 이미지·rawSn+frameNo 이미지 조회 | **412** ×4 (REVIEWER·WORKER 동일) | 스트리밍만 404, 나머지 412 로 일관 |
| 9 | **deid base 안 → deid base 안** 심링크(cold cache) | **200 (통과)** | 이 형상에서는 원본이 deid base 밖이라 무해하나, **온프렘 기본(`STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH = /nas-storage`)에서는 원본이 같은 base 안이라 통과한다** → **B-ISSUE-41** |

> 결론: **B-ISSUE-81 은 완전 해소**(cold·warm·Range·중간세그먼트 4경로 전부 fail-closed, 바이트 유출 0). 잔여는 §9번의 **동일 base 형상 한정** 경로 하나뿐이다.

---

## 4. B-11. 비식별 누락 신고 (srcSn / rawSn, resolve)

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-030 | PASS | [실동작] `{"reason":"  "}` · `{}` 양쪽 `400 INVALID_INPUT "reason: 신고 사유는 필수입니다."`(@Valid 선차단). 서비스 `requireReason`(`:162-166`)이 이중 방어 |
| TC-DEID-031 | PASS | [실동작] WORKER(2001)가 **본인 미배정** rawSn=5(=2002 배정) 신고 → `403 "본인에게 배정되지 않은 영상입니다."`. 본인 배정 rawSn=4 는 201(대조군 확보 — 무조건 403 이 아님) |
| TC-DEID-032 | PASS | [실동작] 동일 rawSn=107 에 **동시 5요청** → 정확히 **1건 201 + 4건 409**, `LS_DEIDENT_REPORT` 1행·`LS_AUTH_WORK_LOCK` 1행. `findByRawSnForUpdate`(`:182`)로 직렬화됨 |
| TC-DEID-033 | PASS | [실동작] 이미 잠긴 rawSn=4 재신고 → `409 "이미 비식별 재처리 중인 영상입니다."`, 신고행 미증가 |
| ~~TC-DEID-034~~ | — | 폐기(분모 제외). 코드에 스냅샷/삭제 로직 부재 재확인 — `SAVE_REASON='DEIDENT_REPORT'` 상수 grep 0건 |
| TC-DEID-035 | PASS | [실동작] 신고 전 `LS_DATA_SRC` 개인정보 3필드 보유 프레임 1건 → 신고 후 **0건**. 로그 `privacyReset=30`(벌크 JPQL 이 영상 전 프레임 30행 대상) |
| TC-DEID-036 | PASS | [실동작] 위 동시 5요청에서 4건이 409 로 수렴, 500/스택트레이스 0건. 락 UNIQUE 경쟁이 `DataIntegrityViolationException`→409 로 변환(`:264-268`) |
| TC-DEID-037 | PASS | [실동작] APPROVED 영상(rawSn=4) 신고 → `LS_MON_NOTI_ACML` 적재 → 디바운스 flush 로 `[ControlNotify] TASK_MODIFIED sent rawSn=4 frames=0 videoLevel=1` 실송신. 라벨 삭제가 없으므로 `LABEL_DELETED` 아님(META_UPDATED 경로) |
| ~~TC-DEID-038~~ | — | 폐기(분모 제외) |
| TC-DEID-039 | PASS | [실동작] 무토큰 resolve → `401 UNAUTHORIZED` |
| TC-DEID-040 | PASS | [실동작] `POST /v1/deident-reports/9999999/resolve` → `404 "신고를 찾을 수 없습니다."` |
| TC-DEID-041 | PASS | [실동작] 이미 RESOLVED 된 rprtSn=26 재-resolve → `409 "이미 처리된 신고입니다."` |
| TC-DEID-042 | PASS | [실동작] 옛 산출물(mtime 2026-07-30) 상태로 resolve → `409 "비식별 산출물이 확인되지 않습니다…"`. 직후 DB: `DE_IDENT_YN='F'` · report `OPEN` · lock `LOCKED` **전부 유지**(트랜잭션 롤백 = fail-closed) |
| TC-DEID-043 | PASS | [실동작] 파일 touch 후 resolve → 200, `DE_IDENT_YN='Y'`, report `RESOLVED`, lock `RELEASED(MANUAL_DEIDENT_DONE)`. **라벨 10건·라벨버전 4건 불변** → 보존된 라벨 그대로 재사용. 게이트 4경로(stream/labels/image/deid-image) 즉시 200 복귀 |
| TC-DEID-044 | PASS | [정적+실동작] `procLogRepository.findLatestSuccessByDataRawSn` 부재/경로 blank → `deidentNotVerified`(`:570-583`). 실동작으로는 경로 유효·시간 미충족 케이스만 재현(409 동일) |
| TC-DEID-045 | **PARTIAL** | [실동작] mtime 을 **신고시각 -30초**로 맞추자 **resolve 200 통과** → `'F'→'Y'` 로 모든 게이트가 일괄 해제됐다. 코드는 `mtime > 신고시각 - 60s`(`:601`)라 카탈로그 문구("스큐 60s")와는 일치하나, **"신고를 유발한 그 비식별본을 배제한다"는 보안 의도가 60초 창 안에서 무너진다** → 1차 **B-ISSUE-102 미해소 이월**(→ B-ISSUE-42) |
| TC-DEID-046 | PASS | [실동작] rawSn=4 는 신고 전후·해소 후 모두 `LS_DATA_RAW.DATA_STTS_CD='COMPLETED'` 유지. 복원되는 값은 `DE_IDENT_YN` 뿐(상태 역행 없음, CWE-664) |
| TC-DEID-047 | PASS | [정적] `DeidentifyStep.java:354` · `KpstDeidentTxService.java:358` 두 자동 경로가 `resolveOpenReports` 호출 → `resolve()` 일괄 + `releaseRaw` + `evictAfterCommit`, `opens` 비어 있으면 이벤트 미발행(멱등). 회귀 가드 `DeidentReportServiceTest:1172,1191`. **실동작 미재현 사유**: 자동 재비식별(DeidentifyStep 재실행)을 수동 트리거할 API 가 없어 `'F'` 영상에 대해 자동 경로를 태울 방법이 없음(수동 경로는 별도 검증 완료) |
| TC-DEID-048 | PASS | [실동작] `status=` 미지정 → 200(기본 OPEN, totalElements=4) / `OPEN`·`RESOLVED` → 200 / `open`(소문자)·`X`·`DROP`·`OPEN' OR 1=1--` → **전부 400 INVALID_INPUT**. @Pattern(컨트롤러) + `normalizeStatus`(`:453-466`) 이중 방어. SQLi 문자열이 쿼리로 흘러가지 않음(JPQL 파라미터 바인딩) |
| TC-DEID-049 | PASS | [실동작] `POST /v1/labels/1/deident-report`(WORKER 본인 배정) → **201**, rprtSn=29 |
| TC-DEID-050 | PASS | [실동작] WORKER(본인 배정 rawSn=4) resolve → 200 / REVIEWER resolve(rprtSn 26·28·30) → 200 / 이미 처리 → 409 / 미인증 → 401. WORKER 타인 영상 403 은 TC-DEID-031 과 동일 가드(`verifyRawAccess`) |
| TC-DEID-051 | PASS | [실동작] 신고 직후 rawSn=4: `LS_DATA_LBL` **10건 불변**, `LS_LABEL_VERSION` **4건 불변**(신규 스냅샷 0), 라벨셋 버전 bump 없음. 노출 차단은 조회 게이트 412 가 담당함을 같은 시점에 확인 |
| TC-DEID-052 | PASS | [실동작] 3필드 보유 프레임 1건 → `LS_DATA_LBL_HSTRY` 에 정확히 **1행** 추가: `src_sn=1, add_cnt=0, mdfcn_cnt=0, del_cnt=0, chg_dtl_cn={"event":"PRIVACY_META_RESET","deidentReportSn":26,"changes":[]}`. 라벨 델타 0 이라 V139 필터로 `V_COMPLETED_LABEL_CHANGE` 미노출. 영상 축 3필드가 전부 NULL 이던 영상은 `LS_TASK_EVENT_LOG` 행을 만들지 않음(`videoPrivacyReset=false`, 없는 사실 미기록) — 별도 케이스로 `DeidentReportServiceTest:231` 커버 |
| TC-DEID-053 | PASS | [실동작] 파생 rawSn=98(`ORGNL_RAW_SN=906`) 신고 → **412 PRECONDITION_FAILED**. 응답 문구에 **부모 rawSn 없음**, 원본 유도 없음. 신고행 미생성, REVIEWER 알림 없음. 사유에 `\n` 을 넣었더니 WARN 로그에 `reason=QA3 파생 테스트개행포함`(개행 제거)으로 정제 기록 — `LogSanitizer` 실적용 확인(CWE-117) |
| TC-DEID-054 | PASS | [실동작] rawSn=901(`DE_IDENT_YN='N'`) 신고 → **412** `"아직 비식별 처리가 완료되지 않은 영상입니다…"`, `'N'→'F'` 전이 없음(DB 재확인). `'F'` 는 이 게이트를 통과해 기존 409(작업락) 경로로 감(rawSn=4 재신고 실측) |
| TC-DEID-055 | PASS | [실동작] `POST /v1/videos/4/deident-report` → **201**(REVIEWER·WORKER 양쪽 성공 확인). UNCERTAINTIES #2("마킹단계 rawSn 신고 미구현") 해소 상태 유지 |
| TC-DEID-056 | PASS | [실동작] 같은 영상(rawSn=4)에 **rawSn 경로**와 **srcSn 경로**를 각각 태워 부수효과 대조 — 양쪽 모두 ①`DE_IDENT_YN 'Y'→'F'` ②작업락 LOCKED ③개인정보 3필드 리셋 + `LS_DATA_LBL_HSTRY` 감사 1행 ④스트림 캐시 무효화(즉시 404) ⑤라벨 보존 ⑥APPROVED 통지. 차이는 통지의 `srcSn` 유무뿐(설계대로) |
| TC-DEID-057 | PASS | [실동작] **승인 영상**(rawSn=4) resolve → `[VlmResumeBridge] deident gate reopened rawSn=4` / **미승인 영상**(rawSn=103, 워크플로 PENDING) resolve → **`VlmResumeBridge` 만 발화**. 승인 여부와 무관하게 항상 발행됨을 양쪽에서 확인 |
| TC-DEID-058 | PASS | [실동작] rawSn=4(APPROVED) → `[DatasetExportBridge] deident report resolved rawSn=4 — re-triggering withheld export/notify` → export v11 성공(원본 30 + 비식별 30 = 60 파일) → **export 성공 후** `TASK_MODIFIED` 송신. rawSn=103(미승인) → `DatasetExportBridge` **미발화**(불필요한 v1 미생성) |
| TC-DEID-059 | PASS | [실동작+정적] rawSn=88(파생, 부모 900='F') 스트리밍 200 = 조상 미순회. `DeidentReportGate.java:66-71` 이 `findDeIdntfYnByRawSn` 단일 컬럼 projection 1회만 수행하고 `ORGNL_RAW_SN` 을 읽지 않음. 조상/자손 전파 코드 잔재 grep 0건 |

---

## 5. 이전 회차 이슈 대조

| 이전 이슈 | 내용 | 이번 회차 판정 |
|---|---|:--|
| **B-ISSUE-81** (1차 HIGH) | 스트리밍 비식별 경로 가드 lexical 전용 → 심링크로 원본 200 서빙 | **✅ 해소 확정.** 전수 실동작 4경로(cold/warm-cache/Range/중간세그먼트) 전부 404, 바이트 유출 0. 2차 타겟 재검증 PASS 를 3차 전수로 재확인 |
| **B-ISSUE-84** (1차 LOW, 카탈로그) | TC-STREAM-B15 비고 무효 · B04 기대값 · file:line 4건 | **✅ 해소.** B15 비고·B04 기대값 모두 현재 카탈로그에 정정 반영돼 있고 실동작과 일치 |
| **B-ISSUE-82** (1차 LOW) | 성공 procLog 없어도 `stream-url` 200 발급 / `stream` 404 | **❌ 미해소 이월** → **B-ISSUE-46**. rawSn=66 실측 재현 |
| **B-ISSUE-83** (1차 LOW) | 캐시가 사라진 파일을 가리키면 404 아닌 500 | **✅ 해소.** `revalidateOpenTarget` 이 매 요청 NOFOLLOW stat 을 하므로 파일 소실도 404 로 정규화(§3 시나리오 1·2 에서 부수 확인) |
| **B-ISSUE-85** (1차 LOW) | 200 전체 응답에도 `Content-Range` 부착(RFC 7233 비적합) | **❌ 미해소 이월** → **B-ISSUE-43**. `Content-Range: bytes 0-50853/50854` 가 200 응답에 그대로 실측 |
| **B-ISSUE-102** (1차) | resolve 60초 스큐 관용이 "재비식별하지 않은 옛 산출물"을 통과 | **❌ 미해소 이월** → **B-ISSUE-42**. mtime = 신고시각 -30s 로 실제 통과 재현 |

---

## 6. 이슈 기록

### [B-ISSUE-41] TC-STREAM-B04 / TC-STREAM-B20 — 원본·비식별 base 가 동일한 **온프렘 기본 형상**에서는 심링크 가드가 원본 노출을 막지 못한다
- **심각도**: MEDIUM (해당 배포 형상에서는 HIGH 로 승격 — 아래 전제 참조)
- **기대 동작(기대효과)**: `GET /v1/videos/{rawSn}/stream` 은 **어떤 배포 형상에서도** 비식별본만 내보내야 한다. B-ISSUE-81 수정이 세운 규약은 *"판정이 돌려준 실경로를 그대로 열고, 실경로가 허용 base 밖이면 거부"* 인데, 이 규약의 실효성은 **"원본이 비식별 base 밖에 있다"** 는 전제에 전적으로 의존한다.
- **현재 동작(이슈 내용)**: 허용 base 목록의 첫 원소가 **`deidentified-path` 전체**다.
  ```java
  // common/storage/VideoArtifactRootResolver.java:337-347
  public List<Path> readableDeidVideoBases(long rawSn, String rawFilePathNm) {
      Set<Path> bases = new LinkedHashSet<>();
      bases.add(deidentifiedBase);              // ← 좁히지 않은 전체 base
      try { bases.addAll(readableDeidVideoDirs(rawSn, rawFilePathNm)); } catch (RuntimeException e) { }
      return List.copyOf(bases);
  }
  ```
  `resolveSafe`(`VideoStreamService.java:633-656`)는 후보 base 중 **하나라도** 실경로 판정을 통과하면 그 실경로를 그대로 연다. 따라서 실경로가 `deidentifiedBase` 하위이기만 하면 통과한다.
  온프렘 기본 설정은 두 base 가 **같은 디렉터리**다:
  ```
  deploy/onprem/config/backend/env.template:106-107
  STORAGE_RAW_PATH=/nas-storage
  STORAGE_DEIDENTIFIED_PATH=/nas-storage
  ```
  즉 그 형상에서는 **원본(마스킹 전) 영상도 허용 base 안**이므로, 비식별 산출 디렉터리에 원본을 가리키는 심링크를 심으면 lexical 검사도 realpath 검사도 통과한다. `revalidateOpenTarget` 의 ②(실경로 동일성)도 최초 판정 자체가 그 실경로를 채택하므로 걸리지 않는다.
- **재현/확인 경로**: 로컬 스택은 두 base 가 분리돼 있어 **PII 유출 자체는 재현되지 않는다**. 대신 **기전**을 실동작으로 확증했다 — 비식별 base **안**의 심링크가 같은 base **안**의 다른 파일을 가리키면 그대로 따라가 200 서빙된다:
  ```bash
  docker exec klid-backend ln -s /app/storage/deidentified/videos/qa-e5/stale.mp4 \
        /app/storage/deidentified/videos/qa3rd/inner.mp4
  # LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM(rawSn=905) := .../qa3rd/inner.mp4
  curl .../v1/videos/905/stream   # → HTTP 200, 50854 bytes (링크 대상 그대로)
  ```
  같은 조작에서 링크 대상이 base **밖**(원본)일 때만 404 였다. 따라서 원본이 같은 base 안이 되는 순간(=온프렘 기본) 404 조건이 사라진다.
  (참고: 심링크가 아니어도 성립한다 — `DE_IDNTF_FILE_PATH_NM` 에 원본 경로가 잘못 적재되면 동일 형상에서 base 가드가 걸러내지 못한다. 이 프로젝트에는 *"KPST `retrieve_progress` 의 `fileName` 은 원본 입력 경로"* 라는 알려진 혼동 함정이 있어 오적재 가능성이 이론적이지 않다.)
- **영향**: **CWE-59(Link Following) + CWE-359(개인정보 노출)**. 위협 모델은 B-ISSUE-81 과 동일(외부 비식별 벤더가 직접 쓰는 공유 마운트에 대한 쓰기 능력)인데, 그 이슈는 HIGH 로 평가됐다. 자동 테스트에도 이 축이 없다 — `VideoStreamServiceTest`(44건)의 심링크 케이스 3건은 전부 **링크 대상이 base 밖**인 형상만 다룬다(`비식별파일이_원본영상_심링크면_NOT_FOUND` 외 2건).
- **수정 방향(제안)**: ①`readableDeidVideoBases` 의 첫 원소를 `deidentifiedBase` 전체가 아니라 **`{deidentifiedBase}/videos/{rawSn}` · `{deidentifiedBase}/frames/deid/{rawSn}` 등 실제 산출 서브트리로 좁힌다**(현재 `readableDeidVideoDirs` 가 이미 그 형태를 만들고 있으므로 광역 base 만 제거하면 된다 — 단 배포 전 산출물·파생본 경로가 이 축에 들어오는지 전수 확인 필요). ②추가로 기동 가드에서 `STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH` 인 경우 **광역 base 폴백을 금지**(fail-closed)하고 로그로 경고. ③회귀 가드로 `VideoStreamServiceTest` 에 "두 base 가 동일 경로일 때 비식별 디렉터리의 원본 심링크는 404" 1건 추가. ⚠ 구현은 하지 않았다.

---

### [B-ISSUE-42] TC-DEID-045 — resolve 산출물 검증의 **60초 스큐 관용**이 "신고 이전 산출물"을 통과시켜 모든 게이트를 일괄 해제한다 (1차 B-ISSUE-102 미해소 이월)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `resolveManually` 의 시간 조건은 *"신고를 유발한 그 비식별본으로 `'F'→'Y'` 를 복원하지 못하게 한다"* 가 목적이다(`verifyDeidentArtifact` javadoc). `'F'→'Y'` 복원은 라벨 조회(412)·프레임 이미지(412)·스트리밍(404)·export 보류를 **한꺼번에** 여는 단일 지점이므로, 여기를 통과시키면 마스킹 실패 픽셀이 즉시 재노출된다.
- **현재 동작(이슈 내용)**: mtime 비교에 60초 관용이 **감산 방향으로만** 적용돼, 신고 직전 60초 안에 마지막 수정된 파일이 "신고 이후 재비식별"로 인정된다.
  ```java
  // label/service/DeidentReportService.java:595-601
  private static final long CLOCK_SKEW_TOLERANCE_SECONDS = 60L;   // :532
  ...
  fileAfterReport = mtime.isAfter(reportTime.minusSeconds(CLOCK_SKEW_TOLERANCE_SECONDS));
  ```
- **재현/확인 경로** (2026-08-03 실측, rawSn=4 · rprtSn=26 · 검증 후 정상 종결):
  ```bash
  # 신고 시각 dclr_dt = 2026-08-04 00:30:38 (KST, JVM -Duser.timezone=Asia/Seoul)
  docker exec klid-backend touch -d '2026-08-03 15:30:08 UTC' \
        /app/storage/raw/seed/4/deid/clip-9101-mask.mp4      # = 신고시각 -30초
  curl -X POST .../v1/deident-reports/26/resolve -H "Authorization: Bearer $REVIEWER"
  # → HTTP 200  {"success":true}
  # DB: report_stts_cd=RESOLVED, ls_data_raw.de_ident_yn='Y'
  # 직후: /stream 200, /v1/frames/1/labels 200, /v1/frames/1/image 200  (게이트 전부 해제)
  ```
  대조군: 같은 파일의 mtime 이 60초보다 더 과거(2026-07-30)일 때는 정상적으로 409 + 롤백(OPEN·락·`'F'` 유지)이었다.
- **영향**: **CWE-345(불충분한 데이터 진정성 검증) + CWE-359**. 신고와 거의 동시에(60초 이내) 어떤 이유로든 비식별본이 touch 되면 — 예: 백업/rsync/스토리지 스냅샷 복원, 벤더 배치의 무해한 재기록, 혹은 공격자의 `touch` — **실제 재비식별 없이** 신고가 해제된다. 자동 테스트는 이 창을 다루지 않는다(`DeidentReportServiceTest:1054` "신고이전_비식별본만_존재시_resolve_거부된다" 는 mtime 이 창 밖인 형상만 검증).
- **수정 방향(제안)**: 스큐 관용을 **파일 mtime 비교에서 제거**하고(파일시스템·앱은 같은 노드이므로 스큐 근거가 약하다), 굳이 남긴다면 **가산 방향**(`reportTime.plusSeconds(...)` 로 더 **엄격**하게)으로 바꾼다. 더 견고한 대안은 시각 비교 대신 **콘텐츠 동일성**을 판정하는 것 — 신고 시점에 비식별본 해시(또는 size+mtime 튜플)를 `LS_DEIDENT_REPORT` 에 기록해 두고 resolve 시 **달라졌는지**를 본다(시계에 의존하지 않아 스큐 논쟁 자체가 소멸). 회귀 가드로 "mtime 이 신고시각 -30초면 409" 1건 추가. ⚠ 구현은 하지 않았다.

---

### [B-ISSUE-43] TC-STREAM-B18 파생 — 200 전체 응답에도 `Content-Range` 가 붙는다 (1차 B-ISSUE-85 미해소 이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: `Content-Range` 는 206/416 에서만 의미가 있다(RFC 7233 §4.2). 200 응답의 `Content-Range` 는 엄격한 프록시/플레이어가 오해할 수 있다.
- **현재 동작(이슈 내용)**: Range 헤더 없는 요청에도 부착된다. 전체 파일도 `ResourceRegion(resource, 0, contentLength)` 으로 감싸 반환(`VideoStreamService.java:285-291`)하기 때문에 `ResourceRegionHttpMessageConverter` 가 write 시점에 `Content-Range` 를 add 한다.
  ```
  HTTP/1.1 200
  Accept-Ranges: bytes
  Cache-Control: no-store
  Content-Range: bytes 0-50853/50854      ← 200 인데 부착됨
  Content-Length: 50854
  ```
- **재현/확인 경로**: `curl -D - -H "Authorization: Bearer $REVIEWER" .../v1/videos/101/stream`(Range 헤더 없음).
- **영향**: 기능/호환성만. 보안·PII 영향 없음. 현재 브라우저에서는 문제가 관측되지 않았다.
- **수정 방향(제안)**: Range 가 없을 때는 `ResourceRegion` 대신 `Resource` 를 직접 반환하는 별도 분기를 두거나(반환 타입 변경 영향 검토 필요), 컨버터 대신 직접 `Content-Length` 만 세팅한다. ⚠ 구현은 하지 않았다.

---

### [B-ISSUE-44] 카탈로그 정합 — B-11 전 항목 + TC-STREAM-B17 의 근거 `file:line` 대량 드리프트 (**이번 회차에 정정 완료**)
- **심각도**: LOW (카탈로그 자체의 정합성 결함 — 오탐/누락 유발)
- **기대 동작(기대효과)**: 케이스의 근거 `file:line` 이 현재 구현과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**: B-11(TC-DEID-030~058)의 근거가 **전 항목 +5 ~ +30행 드리프트**했고, B-10 에서는 TC-STREAM-B17 1건이 어긋났다. 대표 예:
  - TC-DEID-036 `DeidentReportService.java:237-241` → 그 위치는 현재 **영상 축 개인정보 리셋 블록**이고, `DataIntegrityViolationException`→409 변환은 **`:264-268`**.
  - TC-DEID-037 `:230-234` → `TaskModifiedEvent` 발행은 **`:257-260`**.
  - TC-DEID-042/045 `:542-590` → `verifyDeidentArtifact` 는 **`:570-609`**.
  - TC-STREAM-B17 `DeidentReportService.java:244-250` → `evictAfterCommit` 는 **`:275`**(+`:423`, `:482`).
  - TC-DEID-049 `DeidentReportController.java:95-102` → **`:111-119`** / TC-DEID-050 `:145-150` → **`:161-168`** / TC-DEID-055 `:122-129` → **`:138-146`**.
- **재현/확인 경로**: 해당 `file:line` 을 Read 하면 무관한 코드가 나온다. 실제 위치는 Grep 으로 확인.
- **영향**: 근거를 신뢰해 정적 대조만 하는 회차에서 **정상 동작을 FAIL 로 오판**하거나, 반대로 검증하지 않은 채 PASS 를 남길 수 있다.
- **수정 방향(제안)**: **이번 회차에 담당 라인 범위(255~316행) 안에서 직접 정정했다**(별도 조치 불필요). 다음 회차에서 재대조 요망.

---

### [B-ISSUE-45] 카탈로그·문서 표기 `DE_IDNTF_YN` vs 실제 물리 컬럼 `DE_IDENT_YN` 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: DB 컬럼 물리명은 문서·케이스·코드가 하나여야 SQL 기반 검증이 성립한다.
- **현재 동작(이슈 내용)**: 실제 컬럼은 **`LS_DATA_RAW.DE_IDENT_YN`** 이다(`\d ls_data_raw` 실측, 엔티티 `LsDataRaw.java:118 @Column(name = "DE_IDENT_YN")`). 그런데 카탈로그(TC-STREAM-B21·TC-DEID-054·TC-DEID-059 등)와 루트 `CLAUDE.md` 는 `DE_IDNTF_YN` 과 `DE_IDENT_YN` 을 **혼용**한다(자바 필드명은 `deIdntfYn` 이라 혼동을 키운다).
- **재현/확인 경로**: `select de_idntf_yn from ls_data_raw;` → `ERROR: column "de_idntf_yn" does not exist / HINT: Perhaps you meant "ls_data_raw.de_ident_yn"`.
- **영향**: 검증·운영 SQL 이 그대로 실패한다(이번 검증에서도 첫 쿼리가 실패). 데이터 손상 위험은 없다.
- **수정 방향(제안)**: 표준용어 관점에서 어느 쪽이 정본인지 확정한 뒤(비식별=DE_IDNTF 가 표준단어 조합에 가깝다) **①물리명을 바꾸지 않고 문서·케이스 표기를 `DE_IDENT_YN` 으로 통일**하거나, ②컬럼 rename 마이그레이션을 하되 뷰·엔티티·온프렘 SQL 동반 수정. ①이 저비용이며 이번 회차에서는 **카탈로그를 건드리지 않았다**(내 담당 범위 밖 파일에도 같은 표기가 퍼져 있어 일괄 결정이 필요). ⚠ 구현은 하지 않았다.

---

### [B-ISSUE-46] TC-STREAM-B12 — 성공 procLog 가 없는 영상에도 서명 스트림 URL 이 발급된다 (1차 B-ISSUE-82 미해소 이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: 서명 URL 발급은 "재생 가능한 영상"에 대해서만 성공해야 한다(`issueSignedUrl` javadoc: *"노출본 대상 URL 발급을 사전 차단한다"*).
- **현재 동작(이슈 내용)**: `issueSignedUrl`(`VideoStreamService.java:170-211`)은 `DE_IDENT_YN=='Y'` 와 신고 게이트만 확인하고 **`LS_DEIDENT_PROC_LOG` 성공 행 존재는 확인하지 않는다**. 반면 `stream` 은 `resolveDeidLocation`(`:562-566`)에서 procLog 부재 시 null → 404.
  ```
  rawSn=66 (de_ident_yn='Y', SUCCEEDED procLog 0건)
    GET /v1/videos/66/stream-url → 200 {"url":"…?exp=…&u=1001&sig=…","ttlSeconds":60}
    GET /v1/videos/66/stream     → 404
  ```
- **재현/확인 경로**: 위 curl 2줄(실측 2026-08-03). 대상 선별 SQL: `select raw_sn from ls_data_raw r where r.de_ident_yn='Y' and not exists (select 1 from ls_deident_proc_log p where p.data_raw_sn=r.raw_sn and p.proc_stts_cd='SUCCEEDED');`
- **영향**: 보안 취약점 아님(재생은 404 로 막힘). FE 가 URL 을 `<video>` 에 물린 뒤 원인 불명 재생 실패로 보인다.
- **수정 방향(제안)**: `issueSignedUrl` 에서 `resolveDeidPath(rawSn) == null` 이면 `stream` 과 **같은 메시지**로 404(상태 오라클 방지). 또는 카탈로그에 "발급은 플래그 축, 재생은 산출물 축"이 의도된 분리임을 명문화. ⚠ 구현은 하지 않았다.

---

## 7. 카탈로그 정정 내역 (담당 라인 255~316 내 Edit)

| 대상 | 정정 전 | 정정 후 |
|---|---|---|
| TC-STREAM-B17 근거 | `DeidentReportService.java:244-250` | `label/service/DeidentReportService.java:275,423,482` |
| TC-DEID-030 근거 | `DeidentReportController.java:@Valid · DeidentReportRequest` | `DeidentReportController.java:115,142 · DeidentReportService.java:162-166` |
| TC-DEID-031 근거 | `:118-133` | `:122-130` |
| TC-DEID-032 근거 | `:178-180` | `:182-183` |
| TC-DEID-033 근거 | `:188-191` | `:193-195` |
| TC-DEID-034 근거(폐기행) | `:196-204` | `:201-209` |
| TC-DEID-035 근거 | `:218-219` | `:222-223` |
| TC-DEID-036 근거 | `:237-241` | `:264-268` |
| TC-DEID-037 근거 | `:230-234` | `:257-260` |
| TC-DEID-038 근거(폐기행) | `:196-204` | `:201-209` |
| TC-DEID-039 근거 | `:353-360` | `:382-384` |
| TC-DEID-040 근거 | `:353-365` | `:385-386` |
| TC-DEID-041 근거 | `:365-370` | `:392-394` |
| TC-DEID-042 근거 | `:372,542-590` | `:400,570-609` |
| TC-DEID-043 근거 | `:372-400` | `:400-419` |
| TC-DEID-044 근거 | `:542-570` | `:570-583` |
| TC-DEID-045 근거 | `:542-590` | `:570-609` |
| TC-DEID-046 근거 | `:380-400` | `:412-419` |
| TC-DEID-047 근거 | `:443-470` | `:471-490` |
| TC-DEID-048 근거 | `:418-440` | `:445-466` |
| TC-DEID-049 근거 | `DeidentReportController.java:95-102` | `DeidentReportController.java:111-119` |
| TC-DEID-050 근거 | `:145-150` | `:161-168` |
| TC-DEID-051 근거 | `:196-204` | `:201-209` |
| TC-DEID-052 근거 | `:218-227` | `:222-253` |
| TC-DEID-053 근거 | `:182-183,295-310` | `:187,323-334` |
| TC-DEID-054 근거 | `:185-186,329-340` | `:190,357-365` |
| TC-DEID-055 근거 | `Controller:122-129 · Service:147` | `Controller:138-146 · Service:151` |
| TC-DEID-056 근거 | `:118,147,169` | `:122,151,173` |
| TC-DEID-057 근거 | `:480-491` | `:515-523` |
| TC-DEID-058 근거 | `:483-493` | `:520-522` |
| TC-DEID-045 기대결과 | (스큐 60s 서술만) | + "⚠ 60초 창 안의 산출물은 실제로 통과한다(B-ISSUE-42)" 주석 |

> `TC-STREAM-B01~B16·B18~B22` 및 `TC-DEID-059` 의 근거는 **실측 결과 정확**해 손대지 않았다.

---

## 8. 테스트 커버리지 대조 (`_raw/test-baseline.md`: backend 실패 0건)

| 케이스군 | 테스트 파일:건수 | 비고 |
|---|---|---|
| TC-STREAM-B01~B22 | `video/VideoStreamServiceTest.java` **44건** | 심링크 3건 + 캐시히트 치환 2건 + no-store 2건 + 청크 클램프 5건 포함. **동일 base 심링크(B-ISSUE-41) 케이스는 0건** |
| TC-STREAM-B15 | `video/VideoStreamAssignmentAuthorizationTest.java` **17건** | B-ISSUE-63 회귀 가드 |
| TC-STREAM-B12~B14 | `video/StreamSignedUrlControllerTest.java` **19건** | |
| TC-STREAM-B17·B19·B21·B22 | `video/DeidentReportStreamGateIT.java` 2건 · `video/service/DeidentReportGateTest.java` 5건 | |
| TC-DEID-030~058 | `label/service/DeidentReportServiceTest.java` **48건** | 파생 412·미수행 412·rawSn 진입점·동시 409·resolve 시간조건·이벤트 분기 전부 커버. **60초 창(B-ISSUE-42) 케이스는 0건** |
| 서빙 링크팔로우 아키텍처 가드 | `FileServingLinkFollowGuardTest` 1건 | 서빙 경로의 link-follow 재구현 방지 |

---

## 9. 부수 관찰 (이슈 미등록 — 사실만 기록)

1. **다중 Range(`bytes=0-100,200-300`)는 첫 범위만 206 으로 반환**한다(멀티파트 미지원). RFC 7233 상 허용 범위이고 브라우저는 단일 범위만 보내므로 결함으로 보지 않았다.
2. **`status=`(빈 문자열)은 400**이다 — 서비스 `normalizeStatus` 는 blank 를 OPEN 으로 정규화하지만 컨트롤러 `@Pattern` 이 먼저 거른다. 파라미터를 **생략**하면 정상 200(기본 OPEN)이라 FE 동선에는 영향이 없다.
3. **`privacyReset=30` vs `privacyResetAudited=1`** — 벌크 JPQL 은 영상 전 프레임(30행)을 UPDATE 하지만 감사 행은 **실제 값이 있던 프레임(1건)** 에만 남는다. 코드 주석의 의도대로이며(없는 사실 미기록) 결함 아님.
4. **신고 접수 시 `TASK_MODIFIED` 가 export 없이 즉시 디바운스 송신**된다(rawSn=4 실측). 신고 구간에는 export 가 보류되므로 "통지는 export 성공 후" 원칙과 형식상 어긋나 보이나, `CLAUDE.md` 가 신고 접수를 **관제 재픽업 신호**로 명시한 별도 경로라 판정에 반영하지 않았다(D 클러스터 소관).
5. **JVM 타임존은 `-Duser.timezone=Asia/Seoul`**, 컨테이너 OS·PostgreSQL 은 UTC 다. `LocalDateTime.now()`(엔티티)와 `LocalDateTime.ofInstant(mtime, ZoneId.systemDefault())`(검증)가 **같은 KST 축**을 쓰므로 B-ISSUE-42 의 원인은 타임존 스큐가 **아니라** 명시적 60초 관용이다(별도 확인).
6. `resolve` 성공 시 export 가 새 버전으로 재산출된다(rawSn=4 → `version=11`, 원본 30 + 비식별 30 = 60 파일). 확정 정책("승인 후 수정 경로는 `v{n+1}` 전량 재생성")대로다.

---

# B-part4.md

# B 클러스터 part4 — B-12 재처리/재시도 큐 · B-13 KPST 위탁·폴링 · B-14 Quartz/인프라/헬스

- **회차**: 2026-08-03 3차 · **담당 범위**: `docs/test-cases/B-batch-deidentify.md` 317~394행
- **케이스 수**: **63건**(B-12 19 · B-13 34 · B-14 10, 폐기 0건 — `grep -cE '^\| *~*TC-'` 실측)
- **판정 집계**: **PASS 61 · PARTIAL 2 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0**
- **이슈**: `B-ISSUE-61` ~ `B-ISSUE-65` (5건 — MEDIUM 2 · LOW 3)
- **카탈로그 정정**: 6셀(기대값 오류 2 · 근거 드리프트 3 · 계약 보강 1)

---

## 0. 검증 환경 · 실동작 수행 내역

| 항목 | 실측 |
|---|---|
| 스택 | `stack-bringup.md` 기준 5컨테이너 healthy(HEAD `e065da42` 재빌드분). backend `localhost:18081/api`, DB `klid_system`(스키마 `public`) |
| 공용 정상 데이터 | `pipeline-drive.md` rawSn=101 (상태 변경 없음 — 본 검증은 **rawSn 46·47·15·49·58·96 및 procLog 8** 만 사용) |
| 인증 | `POST /v1/dev/tokens` REVIEWER(userNo=1001) JWT |
| **live 재현 수행** | ① 재처리 동시 5요청(rawSn=46) ② 백오프/소진(rawSn=47) ③ SKIPPED 보상(rawSn=15) ④ stale 스윕 tick 관측(rawSn 46·49·58) ⑤ 클레임 CAS 동시 2트랜잭션(retry queue · procLog) ⑥ `ON CONFLICT DO NOTHING` 동시 최초 INSERT(rawSn=96) ⑦ KPST 폴링 실왕복(mock-server 로그 + `LS_DEIDENT_PROC_LOG`) ⑧ `/actuator/health` deidentify |
| 상태 원복 | 전건 원복 완료(§6) |

### ⚠ 이 스택의 구조적 제약 (판정에 반영)

- **`authoring.batch.enabled=false`(application-local.yml:62)** → `BatchTriggerConfig`·`BatchRetryTriggerConfig` 미등록. `qrtz_job_details` 실측 결과 등록 잡은 `bootstrapJob`·`kpstDeidentPollJob`·`datasetExportFailureRecoveryJob`·`datasetExportPendingSweepJob`·`controlTrainingVideoScanJob` 5개뿐이고 **`batchRetryJob`/`batchPipelineJob` 은 없다**. 의도된 local 격리이며 결함 아님.
  → 재시도 **폴러 발화 자체**는 이 스택에서 재현 불가하므로, `pollReady()` 의 핵심인 **조건부 원자 UPDATE(CAS)** 를 동일 술어의 SQL 로 **동시 2트랜잭션 실동작 재현**해 대체 검증했다(§1 TC-BATCH-159).
- **stale 회수 스윕(`BatchRetryStaleReclaimSweeper`)은 Quartz 가 아닌 전용 데몬 스케줄러**라 batch 토글과 무관하게 **실제로 돌고 있다** — 기동 로그 + `batch-retry-stale-reclaim` 스레드 + 15분 tick 실관측으로 확인(TC-BATCH-164·165·167 은 완전 실동작 판정).
- **stg/prd 프로파일 전제 케이스**(TC-BATCH-171·173·174·175)는 local 스택에서 실기동 불가 → 지시대로 **정적 코드 판정 + 로컬 실효값 관측**으로 판정했다(`QuartzClusteringGuard` 기동 로그 실관측).

---

## 1. B-12. 재처리 / 재시도 큐 (2노드 안전 · stale 회수) — 19건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-150 | PASS | [실동작] `POST /v1/videos/0/batch/retry` → **400**(`@Min(1)` 선차단). [정적] 서비스 null 가드 `BatchReprocessService.java:67-69` 존재(방어심도) — 근거 라인 일치 |
| TC-BATCH-151 | PASS | [실동작] `rawSn=999999` → **404** `{"errorCode":"NOT_FOUND","message":"영상을 찾을 수 없습니다."}`. `BatchReprocessService.java:71-73` 일치 |
| TC-BATCH-152 | PASS | [실동작] rawSn=46(raw FAILED/work FAILED) → **200** `{"rawSn":46,"stage":"FAILED"}`. 로그 `[BatchReprocess] manual retry claimed rawSn=46` → `MarkingLoadStep` → `BatchOrchestrator failed` 순서 관측. `:77-87` 일치 |
| TC-BATCH-153 | PASS | [실동작] **동시 5요청 → 200 정확히 1건 / 409 4건**(§5 재현). 409 메시지 = 기대 문구. `:77-81` 일치 |
| TC-BATCH-154 | PASS | [실동작] 409 4건 각각에 `[BatchTransition] reprocess claim rejected — raw stage owned by another caller rawSn=46` WARN — **RAW 0행 시 원인 재판정(PROCESSING/FAILED → 폴백 금지)** 경로가 실제로 탔다. [정적] `BatchTransitionService.java:352-374` 일치(2차 B-ISSUE-101 수정분 전수 재확인) |
| TC-BATCH-155 | PASS | [실동작] rawSn=46 최초 실패 → 신규 행 `bat_rty_sn=58, rty_nmtm=1, stts_cd=PENDING`. 로그 `[BatchRetry] enqueued rawSn=46 attempt=1 delaySec=60`. `BatchRetryQueue.java:67-92` 일치 |
| TC-BATCH-156 | PASS | [실동작] 동일 `RAW_SN`(96)에 **동시 2트랜잭션 `INSERT … ON CONFLICT (RAW_SN) DO NOTHING`** → A `INSERT 0 1` / B는 A 커밋까지 대기 후 **`INSERT 0 0`(예외 0건)**. §11 "PG unique 위반이 tx 전체를 abort" 함정 회피 실증. `:72-74` 일치 |
| TC-BATCH-157 | PASS | [실동작] `rty_nmtm=2` 시드 후 재실패 → `attempt=3 delaySec=**240**`, DB `rty_prnmnt_dt - mdfcn_dt = 240s`. 60→120→240 지수백오프 + shift 30 캡(`:85-88`) 일치 |
| TC-BATCH-158 | PASS | [실동작] `rty_nmtm=3`(=max) 시드 후 재실패 → `[BatchRetry] max attempts exceeded -- exhausted rawSn=47 attempt=4 max=3`, DB `stts_cd=EXHAUSTED, rty_nmtm=4, rty_prnmnt_dt=NULL`, **행 삭제 안 됨**(이력 보존). `:79-84` 일치 |
| TC-BATCH-159 | PASS | [실동작-대체] 폴러 자체는 local batch off 로 미등록(§0). `claimAtomically` 와 **동일 술어**를 동시 2트랜잭션으로 재현 → A `UPDATE 1` / B `UPDATE 0`(B는 행 락 대기 후 갱신된 버전으로 WHERE 재평가). `:101-112` 일치. 노드 사멸분 회수는 TC-BATCH-164 가 실동작으로 뒷받침 |
| TC-BATCH-160 | PASS | [실동작] `LS_BAT_RTY_WTNG` 에 **2026-08-01 등록분 12행이 08-03 컨테이너 재빌드·재기동 후에도 잔존**(인메모리 큐 부재의 직접 증거). 코드에도 `ConcurrentHashMap` 큐 없음(`:16-34`) |
| TC-BATCH-161 | PASS | [실동작] `stts_cd='RETRYING'` 시드 상태에서 수동 재처리 호출 → **행(bat_rty_sn=59) 보존**(삭제 안 됨) + 이후 enqueue 가 같은 행을 갱신. `deleteIdleByRawSn` 의 `sttsCd <> 'RETRYING'` 술어 일치(`:141-147`) |
| TC-BATCH-162 | PASS | [정적] `clear` → `deleteByRawSn`(`:126-131`). 호출부는 `BatchOrchestrator.java:133`(성공 경로) — 성공 시 전체 삭제 확인 |
| TC-BATCH-163 | PASS | [실동작] rawSn=15(raw **FAILED** / work **APPROVED**) → **409** `"검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다."`. 로그 3줄(`work status transition skipped (review-owned)` → `BatchOrchestrator skipped` → `reprocess claim compensated (PROCESSING->FAILED)`) 관측. **재호출도 409 이고 raw 는 FAILED 유지 → 영구 PROCESSING 고착 없음.** `:89-101` · `BatchTransitionService.java:391-413` 일치 |
| TC-BATCH-164 | **PARTIAL** | [실동작] 스윕 tick 에서 `[BatchRetry][Reclaim] stale RETRYING reclaimed=2 exhausted=1 staleTimeoutMinutes=180`, 대상 행이 `RETRYING → PENDING` + `RTY_NMTM+1` + `rty_prnmnt_dt=now+60s` 로 복귀. **회수 기전은 기대대로 동작.** ⚠ 그러나 기대결과의 "정상 처리 중 항목은 cutoff 를 넘지 않아 대상 아님"은 **무조건 성립하지 않는다** — 처리 중 `MDFCN_DT` heartbeat 부재 + `BatchOrchestrator.process` 의 rawSn 단위 재진입 가드 부재 → **B-ISSUE-61** |
| TC-BATCH-165 | PASS | [실동작] `rty_nmtm=3 = max_rty_nmtm` 인 stale 행(bat_rty_sn=48) → 복귀 아닌 **`EXHAUSTED`**(`exhausted=1`), `rty_prnmnt_dt=NULL`. `reclaimStaleRetrying`(`RTY_NMTM < MAX`)/`exhaustStaleRetrying`(`>=`) 상호배타 술어 확인 |
| TC-BATCH-166 | PASS | [정적] 클램프 존재·안전 방향 확인. **단 카탈로그 기대값이 코드와 달라 정정함**(§4-①) — `<1`(0/음수)은 30 이 아니라 `DEFAULT_STALE_TIMEOUT_MINUTES`(**180**) 폴백, `1~29` 만 30 clamp (`BatchRetryStaleReclaimSweeper.java:94-97`) |
| TC-BATCH-167 | PASS | [실동작] 기동 로그 `[BatchRetry][Reclaim] stale reclaim scheduled intervalMs=900000 staleTimeoutMinutes=180` + 15분 주기 tick 이 **`batch-retry-stale-reclaim` 데몬 스레드**에서 실행됨을 로그로 관측. 코드에 `@Scheduled`/`@EnableScheduling` 없음(`:42-50,84,102-110`), 자기 토글 `authoring.batch.retry.stale-reclaim.enabled`(기본 true)만 참조 — `authoring.batch.enabled=false` 인 local 에서도 정상 가동한 것이 토글 독립의 실증 |
| TC-BATCH-168 | PASS | [정적] `run()` 이 `catch (Throwable)` 로 삼키고 `log.error(... e.getClass().getSimpleName())` 후 0 반환 — Error 계열도 스케줄러를 죽이지 않음, 예외 원문/스택 미노출(CWE-209). `:142-156` 일치 |

---

## 2. B-13. KPST 비식별 위탁·폴링 — 34건

> 이 스택에서 KPST 는 **mock-server(:9400) 실HTTP 연동**이며(`DEIDENTIFY_MOCK_MODE=false`, `KPST_DEID_BASE_URL=http://klid-mock-server:9400`), 검증 중 `POST /project` → `GET /retrieve_progress` → 산출물 회수가 **실제로 왕복**했다(§5-④). self-fill 0건.

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-DEID-060 | PASS | [실동작] `LS_DEIDENT_PROC_LOG` 신규 행이 **`REQ_DT == REG_DT`(원장 선커밋 시각)** 로 먼저 생기고 `DE_IDNTF_PJT_ID` 는 이후 ACK 로 채워짐(proc_log 74~77 실측). mock 로그 `project created prj_id=N name=rawN`. [정적] `KpstDeidentService.java:258-273,288,375-431` · `KpstSubmitOutcomeRecorder.java:44-57` · `KpstDeidentTxService.java:102-113`(`recordSubmitAck` 실제 :103-115) 모두 일치. `markKpstSubmitted` 메서드 부재 확인 |
| TC-DEID-061 | PASS | [정적] `buildProjectRequest` 의 `parent == null` → `INVALID_INPUT`(`:319-323`), 외부 호출 이전 동기 실패. CWE-22 근거 주석 일치 |
| TC-DEID-062 | PASS | [정적] 사전조건 실패만 `submit()` 동기 전파(`:292-303`, `failSubmit` 선커밋 후 `EXTERNAL_API_ERROR`). 실제 `createProject` 실패는 `subscribeSubmit` 의 `err ->` 핸들러 → `onSubmitFailed`(`KpstSubmitOutcomeRecorder.java:69-73`) → `failSubmit`(`KpstDeidentTxService.java:164-`) 로 **비동기 처리, 호출자 무예외** |
| TC-DEID-063 | PASS | [정적] `cleanExportDir`(`:488-527`) — ①리졸버 재계산 경로와 `equals` 일 때만 진행 ②`Files.list` 비재귀 ③`isRegularFile(NOFOLLOW_LINKS)` 로 심링크·디렉터리 제외 ④미존재 no-op ⑤IOException 시 경로 원문 미노출 후 위탁 진행 — 5중 가드 전부 확인 |
| TC-DEID-064 | PASS | [실동작] mock 완료 응답 후 `poll_stts_cd=DOWNLOADED`, `proc_stts_cd=SUCCEEDED`, `de_idntf_file_path_nm` 적재(proc_log 74~77). [정적] `allDatasetsCompleted`(AND, `:966-978`) → `downloadResult` → `finishDownloadAndComplete`(`:600-664`) |
| TC-DEID-065 | PASS | [정적] `anyDatasetFailed`(`:587`)가 `allDatasetsCompleted`(`:600`) **앞에서** 평가. `PROC_STATE_TERMINAL_FAILED`(`:97`) = {3,4,99} → `failPolling`/`failRedeidentCompletion` 즉시 종결(타임아웃 대기 없음) |
| TC-DEID-066 | PASS | [정적] `PROC_STATE_TERMINAL_FAILED` 집합에 99 포함(`:97-98`), null procState 는 실패로 보지 않음(`:956`) |
| TC-DEID-067 | PASS | [정적] `prjId == null` 분기(`:541-560`)가 `markTimeoutIfExpired` 가 **아니라** `withinSubmitAckGrace`(`:679-684`) 판정 → 유예 내면 외부 호출·카운터 소모 0, 만료 시 `txService.failSubmit(..., ACK_MISSING_CODE, ...)`. 유예값·`REQ_DT` null 시 fail-closed(즉시 회수) 확인 |
| TC-DEID-068 | PASS | [정적] `retrieveProgress` catch 경로(`:565-575`)에서도 `markTimeoutIfExpired` 호출 → 무기한 stuck 차단. 로그에 예외 클래스명만(CWE-209) |
| TC-DEID-069 | PASS | [정적] `downloadResult` 예외 → REDEIDENT `failRedeidentCompletion`(락 해제 포함) / 비-REDEIDENT `failPolling` 분기(`:611-625`) |
| TC-DEID-070 | PASS | [정적] `isUsableDeidFile` false → `failPolling`/`failRedeidentCompletion`, **`Y` 전이 없음**(`:634-648`). `finishDownloadAndComplete` 도 진입 전 `verifyDeidFile` 재확인 |
| TC-DEID-071 | PASS | [실동작] `poll_atmpt_cnt` 증가 관측(진행중 건). [정적] `recordPollingProgress` + `markTimeoutIfExpired`(`:666-668`) |
| TC-DEID-072 | PASS | [실동작] **§11 함정 직접 반증** — `orgnl_file_path_nm=/app/storage/raw/seed/clip-9101.mp4`(원본 입력 경로) ↔ `de_idntf_file_path_nm=/app/storage/raw/seed/111/deid/clip-9101-**mask**.mp4`. mock-server 코드(`routers/deid.py:104` `"fileName": ds.name`, 주석 "실서버 계약대로 원본 입력파일 경로")로 계약 일치 확인. **완료를 실패로 오종결하는 혼동 없음.** `toMaskName` 재부여 방지(`:812-822`)도 확인 |
| TC-DEID-073 | PASS | [정적] `scanSingleUsable`(`:833-857`) — 1개=회수(+`startsWith(dir)` 재단언) / 0개=null / 2개↑=`INVALID_INPUT`(모호 → 호출측 terminal) |
| TC-DEID-074 | PASS | [정적] `sanitizeFileName`(`:869-890`) — `Paths.get` 파싱 실패(NUL 등)를 `INVALID_INPUT` 으로 정규화(원문 미노출), `getFileName()` basename 추출 후 `/`·`\`·`..` 잔존 재거부 |
| TC-DEID-075 | PASS | [실동작] 폴링 대상 0인 tick 에서 mock-server 인바운드 0건(로그 대조). [정적] `KpstDeidentPollJob.java:93-98` `targets.isEmpty()` → DEBUG 후 return |
| TC-DEID-076 | PASS | [정적] 건별 `try/catch (RuntimeException)`(`:110-114`), 로그에 `errType=클래스명`만 |
| TC-DEID-077 | PASS | [정적] `@DisallowConcurrentExecution`(`:43`) + 클래스 javadoc(`:26-40`)이 "같은 노드 한정"을 명시하고 노드 간 방어를 `tryClaimPoll` 로 위임. 실제 배선 일치 |
| TC-DEID-078 | PASS | [실동작] 컨테이너 재기동(08-03 23:54) 이후에도 폴링이 DB 조회로 재개되어 신규 건을 완료 처리(proc_log 71~77). [정적] `findByPollSttsCdIn(statuses, page)`(`:93-94` · `LsDeidentProcLogRepository.java:55`), 인메모리 상태 없음 |
| TC-DEID-079 | PASS | [정적] `completeDeidentification` catch 에서 비-REDEIDENT 한정 `txService.markRawDeidentFailed`(REQUIRES_NEW, `KpstDeidentTxService.java:270-273`) 후 예외 재전파 — 메인 tx 롤백에 휩쓸리지 않음 |
| TC-DEID-080 | PASS | [정적] `@ConditionalOnProperty(prefix="kpst.deid", name="enabled", havingValue="true")`(`:69`) — 서비스·`KpstSubmitOutcomeRecorder`(`:33`)·`KpstDeidentPollTriggerConfig`(`:20`) 동일 조건. ⚠ 기본값 비대칭은 **B-ISSUE-64**(LOW) |
| TC-DEID-081 | PASS | [정적] `submit` 선두 `verifySourceOrFail`(`:286`) → `recordDeidentFailure`(REQUIRES_NEW 'F' 커밋) 후 `INVALID_INPUT`(`:446-466`). `createProject` 는 원장 발급 이전이라 미호출. 로그·예외에 경로 원문 없음 |
| TC-DEID-082 | PASS | [정적] `verifySourceExists=false` 시 `log.warn("... source existence guard disabled — 원본 미검증 위탁 rawSn={}")` 1줄 후 통과(`:447-450`) |
| TC-DEID-083 | PASS | [정적] `isUsableDeidFile` → `DeidentArtifactIntegrity.isValidVideoArtifact`(`:692-694`). 판정 = `isRegularFile(NOFOLLOW_LINKS)` + `size >= MIN_VIDEO_BYTES(512)` + 컨테이너 시그니처(ISO-BMFF box allowlist 8종 / MPEG-TS sync 3회 / RIFF·Matroska 등) → **18B 텍스트 스텁은 크기 하한에서 이미 false**. 근거 라인 정정함(§4-③) |
| TC-DEID-084 | PASS | [정적] `recheckAfterGrace`(`:707-730`) — **후보 파일이 존재할 때만** 유예(`:709` `!fileExists(candidate)` → 즉시 null), `Math.min(resultRecheckDelayMs, MAX_RESULT_RECHECK_DELAY_MS=5_000)` 상한, 1회 재판정 |
| TC-DEID-085 | PASS | [실동작] `claimForPoll` 과 **동일 술어 SQL** 을 동시 2트랜잭션으로 재현: 리스 만료 상태 → **`UPDATE 1`**, 즉시 재클레임 → **`UPDATE 0`**(리스 유효). [정적] `KpstDeidentPollJob.java:101-109` · `LsDeidentProcLogRepository.java:57-92` · `KpstDeidentTxService.java:61-66`(REQUIRES_NEW) 일치. `FOR UPDATE SKIP LOCKED` 미채택 근거(외부 HTTP·파일 I/O 가 tx 밖) 주석 확인 |
| TC-DEID-086 | PASS | [실동작] `POLL_STTS_CD='DOWNLOADED'` 로 전이시킨 뒤 클레임 → **`UPDATE 0`**(fail-closed). `LsDeidentProcLogRepository.java:84-89`(`IN ('WAITING','POLLING')`) 일치 |
| TC-DEID-087 | PASS | [정적] `leaseSeconds() = max(1, pollIntervalSec - 5)`(`:125-128`), 기본 주기 30s(`application.yml:509`) → 리스 25s < 주기 → 단일 노드 매 틱 재클레임. 경계 한계(리스보다 긴 배치)도 javadoc(`:57-74`)에 명시 |
| TC-DEID-088 | PASS | [실동작] `claimDownloadCompletion` 술어로 DOWNLOADED 행 재호출 → **`UPDATE 0`**(멱등). [정적] `LsDeidentProcLogRepository.java:94-125` + `KpstDeidentTxService.java:207-` 가 0행 시 후처리 전체 skip → 프레임 이중 attach 창 없음 |
| TC-DEID-089 | PASS | [정적] `finishDownloadAndComplete` 가 단일 REQUIRES_NEW — 후처리(`applyCompletion`) 예외 시 클레임 UPDATE 도 함께 롤백되어 재폴링 대상 유지 |
| TC-DEID-090 | PASS | [정적] `pollPage()`(`:118-124`) — `pollBatchSize < 1` 이면 `DEFAULT_BATCH_SIZE(200)`, 정렬 `pollLastDt asc nullsFirst` + `procLogSn asc`(기아 방지·결정적). `application.yml:513` 기본 200 |
| TC-DEID-091 | PASS | [정적] `recoveryDirs`(`:797-802`) — `deidVideoDirQuietly`(신 위치, 실패해도 예외 없음) + `{deid_base}/videos/{rawSn}`(구 위치), `LinkedHashSet` 로 순서·중복 제거 |
| TC-DEID-092 | PASS | [정적] 폴백 회수 성공 시 `log.warn("[KpstDeid] primary mask path miss — recovered by fallback scan rawSn={}")`(`:785`) — rawSn 만 노출. [실동작] 이번 회차 mock 산출물은 1차 경로가 적중해 해당 WARN 미발생(정상) |
| TC-DEID-093 | PASS | [정적] `DeidentFrameAttacher.isUsable` → `DeidentArtifactIntegrity.isValidVideoArtifact`(`:151-162`) — KPST 회수 경로와 **동일 단일 판정기** |

---

## 3. B-14. Quartz 클러스터링 / 인프라 / 헬스 — 10건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-170 | PASS | [실동작] `QRTZ_*` 테이블이 `klid_system`(controlDataSource) 에 실재하고 잡 5개가 실제 등록·발화 중(`qrtz_job_details`/`qrtz_triggers` 조회). [정적] `QuartzConfig.java:30-37` `SchedulerFactoryBeanCustomizer` 로 `@Qualifier("controlDataSource")` 명시 주입 + `application.yml:85-86` `driverDelegateClass=PostgreSQLDelegate`, `useProperties='true'` |
| TC-BATCH-171 | PASS | [정적] 공통 `application.yml:95` = `${QUARTZ_CLUSTERED:false}`, `application-stg.yml:11` / `application-prd.yml:13` = `${QUARTZ_CLUSTERED:true}` — 근거 라인 3건 모두 **정확**(드리프트 없음). ⚠ 실제 2노드 중복 발화 억제는 stg/prd 실기동이 필요해 이 스택에서 재현 불가(§0 지시에 따른 정적 판정). 기대결과의 "클러스터링은 트리거 중복만 막는다" 단서는 TC-BATCH-177 로 별도 검증 |
| TC-BATCH-172 | PASS | [정적] `AsyncBatchRunner.runAsync`(`:21-35`) `catch (Exception)` → `log.error` 후 정상 종료. SKIPPED 도 WARN 으로 가시화(무증상 종료 방지) |
| TC-BATCH-173 | PASS | [정적] `QuartzClusteringGuard.check()`(`@PostConstruct`, `:63-74`) → `verify(...)`(`:84-94`)가 `IllegalStateException` throw → 기동 실패. 메시지에 원인·해제법 포함, WARN 아님. [실동작] local 기동 로그 `[Quartz] 단일 노드 모드(클러스터링 off) — 활성 프로파일=[local]` 로 allowlist 통과 경로 관측 |
| TC-BATCH-174 | PASS | [정적] `DeployedEnvironmentDetector.isDeployed`(`:63-70`) = `!NON_DEPLOYED_PROFILES.containsAll(activeProfiles)` + 빈 목록/null → 배포. **반증 4케이스 전부 엄격 확인**: `local,prd`→containsAll false→거부 / `prd1`→미포함→거부 / `LOCAL`→대소문자 불일치→거부 / 미지정→`isEmpty()`→거부. 근거 라인 정정함(§4-②) |
| TC-BATCH-175 | PASS | [정적] `deployedEnvMarker` 가 `ENV` 를 trim+lowercase 후 `{stg,prd}` 매칭, `isDeployed` 가 **프로파일보다 먼저**(`:64-66`) 평가 → `SPRING_PROFILES_ACTIVE=dev` + `ENV=prd` 는 거부. `QuartzClusteringGuard.java:30-32,84-103` 이 규칙을 복제하지 않고 detector 에 위임(드리프트 방지) |
| TC-BATCH-176 | PASS | [실동작] 기동 로그가 실효값 기반으로 분기(local=off). [정적] `environment.getProperty(KEY_CLUSTERED, Boolean.class, FALSE)`(`:49,65`) — Quartz 실 프로퍼티 키를 직접 읽어 별도 미러 상수를 두지 않음 → `QUARTZ_CLUSTERED` 변경이 즉시 반영, 설정 우회 불가 |
| TC-BATCH-177 | **PARTIAL** | [정적] 기대결과 자체("두 방어는 서로 대체하지 않는다")는 **성립**하며 KPST 폴링(`tryClaimPoll`)·재시도 큐(`claimAtomically`)·export sweep 모두 원자 클레임을 보유. ⚠ 그러나 **배치 파이프라인 진입점 5곳이 서로 다른 자원을 클레임**하고 `BatchOrchestrator.process(rawSn)` 자체에 rawSn 단위 재진입 가드가 없어 전 진입점에 걸친 상호배제는 성립하지 않는다 → **B-ISSUE-62** |
| TC-BATCH-178 | PASS | [실동작] `batch-async-1` 스레드에서 rawSn=102 배치가 실행되는 것을 로그로 관측(단일 공유 풀 확인). [정적] `AsyncConfig.java:35-51` core2/max4/queue50/`CallerRunsPolicy`, `@Async("batchAsyncExecutor")` 소비자 다수. 근거 라인 정정함(§4-④) |
| TC-BATCH-179 | PASS | [실동작] `GET /api/actuator/health`(인증) → `"deidentifyHealth":{"status":"UP","details":{"service":"deidentify","mode":"kpst"}}`, 동시에 **mock-server 로그에 `"GET / HTTP/1.1" 200 OK`**(루트 핑) 기록 — `/health` 가 아닌 **루트** 핑 계약 실증. [정적] 3분기(mock UP / kpst 핑 / 미구성 DOWN fail-closed) + catch 에서 `e.getClass().getSimpleName()` 만 노출(`:13-40,75-113`, 파일 총 114줄 — 카탈로그 비고 정확) |

---

## 4. 카탈로그 정정 (담당 라인범위 317~394 내, 6셀)

| # | 대상 | 구 내용 | 정정 내용 | 유형 |
|:--:|---|---|---|---|
| ① | TC-BATCH-166 | 기대결과 "`stale-timeout-minutes=0/음수/5` → **`MIN_STALE_TIMEOUT_MINUTES`(30) 로 clamp**", 근거 `:64,96` | **`<1`(0·음수) → `DEFAULT_STALE_TIMEOUT_MINUTES`(180) 폴백 / `1~29` → 30 clamp** 2단 구조로 정정, 근거 `:59,64,94-97` | **기대값 오류** |
| ② | TC-BATCH-174 | 근거 `QuartzClusteringGuard.java:53,97-103` | `:55,96-103`(`SINGLE_NODE_PROFILES` 는 55행, `singleNodeAllowed` 는 96-103행) | 근거 드리프트 |
| ③ | TC-DEID-083 | 근거 `DeidentArtifactIntegrity.java:43-108` | `:49,87-110`(`MIN_VIDEO_BYTES` 49행, `isValidVideoArtifact` 87-110행) | 근거 드리프트 |
| ④ | TC-BATCH-178 | 근거 `AsyncConfig.java:32-47` | `:35-51`(`batchAsyncExecutor` 빈 정의 실제 범위) | 근거 드리프트 |
| ⑤ | TC-BATCH-164 | 기대결과 "정상 처리 중 항목은 cutoff 를 넘지 않아 대상 아님"(무조건 단언) | "**처리 시간 < 임계(기본 180분)일 때만 성립**" 조건 명시 + heartbeat/재진입 가드 부재 경고(B-ISSUE-61) | **기대값 오류(조건 누락)** |
| ⑥ | TC-BATCH-177 | 기대결과에 진입점별 클레임 자원 차이 미기재 | "배치 파이프라인은 진입점마다 서로 다른 자원을 클레임 — 전 진입점 상호배제 미성립(B-ISSUE-62)" 단서 추가, 근거에 `BatchTransitionService.java:111-122` 병기 | 계약 보강 |

> 나머지 근거 라인(±1~2행 편차: TC-BATCH-158 `:79-84`, TC-BATCH-161 `:141-147`, TC-DEID-060 `KpstDeidentTxService:102-113`, TC-BATCH-170 `QuartzConfig:31-38`)은 **인용 범위 안에 실제 코드가 들어 있어** 정정하지 않았다.

---

## 5. 이슈

### [B-ISSUE-61] TC-BATCH-164 — stale RETRYING 회수에 heartbeat 가 없어, 임계(기본 180분)를 넘긴 **정상 처리 중** 항목이 오회수된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: stale 회수는 **죽은 노드가 남긴** RETRYING 만 되살려야 한다. 살아서 처리 중인 항목을 뺏으면 같은 rawSn 파이프라인이 두 노드에서 동시에 돈다(라벨 이중 적재·재시도 카운터 이중 증가·프레임 중복 추출). 카탈로그 기대결과도 "정상 처리 중 항목은 cutoff 를 넘지 않아 대상 아님"을 단언한다.
- **현재 동작(이슈 내용)**: 회수 대상 판정축은 `MDFCN_DT`(= `claimAtomically` 가 찍는 **클레임 시각**)뿐이고, **처리가 진행되는 동안 이 값을 갱신하는 heartbeat 가 없다.**
  ```java
  // batch/retry/LsBatRtyWtngRepository.java:90-98 (후보 조회)
  SELECT q.BAT_RTY_SN FROM LS_BAT_RTY_WTNG q
   WHERE q.STTS_CD = 'RETRYING' AND q.MDFCN_DT <= :cutoff   // ← 클레임 시각 하나로만 판정
   ORDER BY q.MDFCN_DT ASC LIMIT :limit
  // batch/retry/BatchRetryStaleReclaimSweeper.java:144
  LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleTimeoutMinutes); // 정적 180분
  ```
  즉 안전성은 **"배치 1건 최장 처리 시간 < 180분"이라는 정적 가정**에만 의존한다(코드 주석도 `:33-35` 에서 이 가정을 명시). 가정이 깨지면 회수가 일어나고, 그 뒤를 받쳐줄 2차 가드도 없다 — `BatchOrchestrator.process` 진입 가드 `markRawDataProcessingBlocked`(`BatchTransitionService.java:111-122`)는 **검수 소유 작업 상태(PENDING/IN_REVIEW/APPROVED/REJECTED)만** 차단하고, `LS_DATA_RAW.DATA_STTS_CD` 가 이미 `PROCESSING` 이어도 그대로 통과시킨다(`LsDataRaw.markProcessing` 도 상태 머신 거부 없음).
- **재현/확인 경로**:
  ```sql
  -- 처리 중(RETRYING)인 항목의 MDFCN_DT 가 임계를 넘는 상황을 재현
  UPDATE ls_bat_rty_wtng SET stts_cd='RETRYING', rty_nmtm=1,
         mdfcn_dt = now() - interval '200 minutes' WHERE raw_sn = <처리중 rawSn>;
  -- 다음 스윕 tick(기본 15분) 대기 → 로그:
  --   [BatchRetry][Reclaim] stale RETRYING reclaimed=N ...   (실측 2026-08-04 00:29:20, reclaimed=2)
  -- 결과: STTS_CD='PENDING', RTY_NMTM+1 → 다른 노드가 즉시 같은 rawSn 을 재클레임 가능
  ```
  (본 검증에서 회수 자체는 실동작으로 확인했고, "처리 중 heartbeat 부재"는 코드 전수 조회로 확인 — `MDFCN_DT` 를 갱신하는 지점은 `claimAtomically`·`scheduleNext`·`markExhausted`·`@PreUpdate` 뿐이며 스텝 진행 중 호출되는 경로가 없다.)
- **영향**: 데이터 정합(라벨/프레임 이중 생성) + 자원(동일 영상 2벌 추론). CWE-362(Race Condition). 발생 조건은 "1건 처리 > 180분"이라 상시적이지는 않으나, 장시간 영상 + GPU 경합 + 외부 비식별 지연이 겹치면 도달 가능하며 **발생 시 조용히 진행**된다(경고 로그 없음).
- **수정 방향(제안)**: ①스텝 경계마다 `MDFCN_DT` 를 갱신하는 heartbeat(조건부 UPDATE, `STTS_CD='RETRYING'` + 소유 노드 식별자 일치)를 추가하고 임계를 heartbeat 주기의 배수로 잡는다. 또는 ②`BatchOrchestrator.process` 진입부에 rawSn 단위 재진입 가드(B-ISSUE-62 와 공통 해법)를 두어 회수가 오작동해도 이중 실행이 성립하지 않게 한다. ⚠ 구현은 하지 않았다.

### [B-ISSUE-62] TC-BATCH-177 — 배치 파이프라인 진입점이 **서로 다른 자원**을 클레임해, rawSn 단위 상호배제가 전 진입점에 걸쳐 성립하지 않는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: "클러스터링은 트리거 중복 발화만 막고, 잡 내부 레이스는 원자 클레임이 막는다"가 성립하려면, **같은 rawSn 파이프라인을 두 주체가 동시에 실행할 수 없어야** 한다.
- **현재 동작(이슈 내용)**: `orchestrator.process(rawSn)` 진입점은 5곳이고 각자 **다른 자원**을 선점한다.
  | 진입점 | 클레임 자원 |
  |---|---|
  | `BatchReprocessService.retry` (수동) | `LS_DATA_RAW.DATA_STTS_CD` FAILED→PROCESSING |
  | `BatchRetryQuartzJob.execute` (자동 재시도) | `LS_BAT_RTY_WTNG` **큐 행** PENDING→RETRYING |
  | `MarkingBatchBridge`→`AsyncBatchRunner` | 작업상태 `BATCH_QUEUED`(`tryClaimBatchQueued`) |
  | `BatchQuartzJob` | 자체 큐 dequeue |
  | `BatchDevTriggerController` (dev) | 없음 |

  자원이 다르므로 서로를 배제하지 못한다. 구체적 창:
  1. 자동 폴러가 큐 행을 `RETRYING` 으로 커밋한 직후(`BatchRetryQuartzJob.java:48`) ~ `markRawDataProcessing` 커밋 전(`BatchOrchestrator.java:112`) 사이에 REVIEWER 가 수동 재처리를 호출하면, `LS_DATA_RAW` 는 아직 `FAILED` 라 **수동 클레임이 성공**한다. 수동 경로의 `retryQueue.clearIfIdle` 은 `RETRYING` 을 **보존**하므로(설계상 정당) 폴러도 계속 진행 → **같은 rawSn 파이프라인 2벌 동시 실행**.
  2. B-ISSUE-61 의 오회수가 발생한 경우에도 동일 결과.
- **재현/확인 경로**: 창이 수~수십 ms 라 수작업 재현은 비결정적이다. 코드 경로로 확정 가능하다 — `BatchReprocessService.retry`(`:77`, `:84`)와 `BatchRetryQuartzJob.execute`(`:48`, `:55`)가 각각 다른 테이블에 CAS 를 걸고, 공통 관문인 `markRawDataProcessingBlocked`(`BatchTransitionService.java:111-122`)는 `PROCESSING` 을 차단 사유로 보지 않는다:
  ```java
  if (transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_PROCESSING)) { return true; } // 검수 소유만 차단
  videoRepository.findById(rawSn).ifPresentOrElse(LsDataRaw::markProcessing, ...);      // PROCESSING→PROCESSING 허용
  return false;                                                                          // → 진행
  ```
- **영향**: 데이터 정합(AUTO 라벨·프레임 이중 적재, `LS_LABEL_VERSION`/이력 오염) + 외부 위탁 중복(VLM 제출 2회). CWE-362. 단일 노드에서도 발생 가능(Quartz 클러스터링으로는 못 막는 축).
- **수정 방향(제안)**: `BatchOrchestrator.process` 진입부에 **rawSn 단위 실행권 클레임**을 단일 관문으로 추가한다 — 예: `LS_DATA_RAW.DATA_STTS_CD` 를 `PROCESSING` 으로 바꾸는 조건부 UPDATE 를 "현재 PROCESSING 이 아닐 때만" 성립시키고(0행이면 SKIPPED 반환), 기존 진입점별 클레임은 그대로 두어 방어심도를 유지한다. ⚠ 기존 SKIPPED 보상 경로(`releaseReprocessClaim`)와의 상호작용 검토 필요. 구현은 하지 않았다.

### [B-ISSUE-63] JVM(Asia/Seoul)과 DB(UTC)가 같은 `timestamp without time zone` 컬럼에 **9시간 다른 시각**을 쓴다
- **심각도**: LOW (현재 활성 결함 아님 — 잠재)
- **기대 동작(기대효과)**: stale/lease/backoff 판정은 전부 시각 비교다. 같은 컬럼을 읽고 쓰는 모든 주체가 **동일 기준 시계**여야 판정이 성립한다.
- **현재 동작(이슈 내용)**: 컨테이너 OS·DB 는 UTC 인데 JVM 만 KST 다.
  ```
  backend 컨테이너: date → "Mon Aug  3 03:30:24 PM UTC 2026", TZ 미설정
  DB:               SHOW timezone → Etc/UTC ; now() → 2026-08-03 15:30:24+00
  앱이 쓴 값:        SELECT max(mdfcn_dt) FROM ls_bat_rty_wtng → 2026-08-04 00:29:32   ← +9h
  ```
  원인: `backend/Dockerfile:42` `-Duser.timezone=Asia/Seoul` + `application.yml:48` `jdbc.time_zone: Asia/Seoul`. 반면 `LS_BAT_RTY_WTNG.REG_DT/MDFCN_DT`·`LS_DEIDENT_PROC_LOG.REQ_DT/REG_DT` 는 **`DEFAULT CURRENT_TIMESTAMP`(UTC)** 이고, 네이티브 쿼리 `LsBatRtyWtngRepository.insertIfAbsent`(`:45-48`)도 `CURRENT_TIMESTAMP` 를 직접 쓴다.
  현재는 무해하다 — `insertIfAbsent` 직후 같은 트랜잭션에서 `incrementAttempt()`/`scheduleNext()` 가 JVM 시각으로 덮어쓰고, `LsDeidentProcLog` 의 `reqDt` 도 Java 에서 `LocalDateTime.now()` 로 명시 세팅된다(`:127`). 그래서 **DB 기본값이 살아남는 경로가 현재는 없다.**
- **재현/확인 경로**: 위 3줄. 검증 중 실제로 이 스큐 때문에 "방금 클레임한 대조군 행"이 스윕 대상이 되어 **검증자가 오판할 뻔했다**(psql `now()` 로 시드 → JVM 기준 9시간 과거로 해석됨).
- **영향**: 잠재 — DB 기본값/네이티브 `CURRENT_TIMESTAMP` 로 채워진 행이 하나라도 JVM 판정에 노출되면 **항상 임계 초과(9시간 과거)** 로 판정된다. stale 회수·ACK 유예(`withinSubmitAckGrace`)·폴링 리스 전부 같은 축이다. 운영 SQL 로 수동 점검·복구할 때도 오판 위험이 상시 존재한다.
- **수정 방향(제안)**: ①컨테이너 `TZ=Asia/Seoul` 을 함께 지정해 DB 세션·OS·JVM 기준을 맞추거나, ②시각 컬럼을 `timestamptz` 로 전환하거나, ③네이티브 쿼리·DDL 기본값의 `CURRENT_TIMESTAMP` 를 전부 파라미터 바인딩(JVM 시각)으로 통일한다. ⚠ ②는 마이그레이션 범위가 커 별도 판단 필요. 구현은 하지 않았다.

### [B-ISSUE-64] `kpst.deid.enabled` 기본값이 빈 조건과 헬스 인디케이터에서 **비대칭**이다
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 프로퍼티를 읽는 두 지점의 미설정 기본값이 같아야, 설정 누락 시 "빈은 없는데 헬스는 있다고 판단"하는 어긋남이 생기지 않는다.
- **현재 동작(이슈 내용)**:
  ```java
  // batch/service/KpstDeidentService.java:69  (KpstSubmitOutcomeRecorder:33, KpstDeidentPollTriggerConfig:20 동일)
  @ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")   // matchIfMissing 없음 → 미설정 = false
  // observability/health/DeidentifyHealthIndicator.java:67
  @Value("${kpst.deid.enabled:true}") private boolean kpstEnabled;                        // 미설정 = true
  ```
  `application.yml:500` 이 `${KPST_DEID_ENABLED:true}` 로 항상 값을 주입하므로 실환경에서는 드러나지 않는다.
- **재현/확인 경로**: `kpst.deid.enabled` 프로퍼티를 완전히 제거한 컨텍스트에서 기동 → 서비스/WebClient 빈 미등록 + 헬스는 `kpstEnabled=true` 로 판단. 결과는 `kpstWebClient == null` 분기로 흘러 **DOWN(mode=unconfigured)** 이라 fail-closed 는 지켜진다.
- **영향**: 기능 영향 없음(fail-closed 로 수렴). 다만 "헬스 판정이 실행 경로와 동일 기준"이라는 클래스 javadoc(`:29-31`)의 단언이 문자 그대로는 성립하지 않아, 향후 분기가 늘 때 어긋날 표면이 된다.
- **수정 방향(제안)**: `DeidentifyHealthIndicator` 의 기본값을 `${kpst.deid.enabled:false}` 로 맞추거나, 조건부 빈 쪽에 `matchIfMissing = true` 를 명시해 한쪽으로 통일. ⚠ 구현은 하지 않았다.

### [B-ISSUE-65] 카탈로그 정합 — 기대값 오류 2건 + 근거 드리프트 3건 + 계약 누락 1건
- **심각도**: LOW (카탈로그 자체의 정합성 결함 — 오탐/거짓 PASS 유발)
- **기대 동작(기대효과)**: 케이스의 기대결과·근거가 현재 구현과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**: §4 표의 6건. 특히 ①·⑤ 는 **기대값 자체가 코드와 다르다**:
  - TC-BATCH-166: `0`/음수 입력의 실제 결과는 30 이 아니라 **180**(`BatchRetryStaleReclaimSweeper.java:94-97` `staleTimeoutMinutes < 1 ? DEFAULT_STALE_TIMEOUT_MINUTES : Math.max(MIN_STALE_TIMEOUT_MINUTES, ...)`). 기대값대로 검증하면 정상 코드가 FAIL 로 오판된다.
  - TC-BATCH-164: "정상 처리 중 항목은 대상 아님"이 무조건 단언이라, B-ISSUE-61 의 실제 리스크가 카탈로그상 **검증 대상에서 빠져 있었다**.
- **재현/확인 경로**: §4 표의 file:line 을 Read 하여 대조.
- **영향**: 다음 회차에서 정상 동작을 FAIL 로 오판(①) 하거나, 실재하는 동시성 리스크를 PASS 로 덮을 수 있다(⑤).
- **수정 방향(제안)**: **본 검증에서 담당 라인범위(317~394) 내 6셀을 직접 정정 완료**(§4). 프로덕션 코드는 수정하지 않았다.

---

## 6. 검증 중 만든 상태 변경 (전부 원복)

| 대상 | 변경 | 원복 |
|---|---|---|
| rawSn=46 | 재처리 5회 호출 → 파이프라인 1회 실행(FAILED 유지) + 재시도 큐 행 신규 생성(`bat_rty_sn=58`) | 큐 행 **DELETE**. raw/work 상태는 원래대로 `FAILED/FAILED` |
| rawSn=47 | 재처리 3회 호출 + `rty_nmtm` 시드(2→3, 3→4) → 큐 행 `bat_rty_sn=59` EXHAUSTED | 큐 행 **DELETE**. raw 상태 `FAILED` 유지 |
| rawSn=15 | 재처리 2회 호출(둘 다 409, 보상 롤백 관측) | 상태 변경 없음(raw `FAILED` / work `APPROVED` 그대로 — 보상 롤백이 스스로 원복) |
| 큐 행 `bat_rty_sn=48`(rawSn=49) | stale 시드(RETRYING·`rty_nmtm=3`·`mdfcn_dt` 과거) → 스윕이 EXHAUSTED 종결 | 원래 값(`PENDING`, `rty_nmtm=1`, `rty_prnmnt_dt=2026-08-01 23:20:07.12056`, `mdfcn_dt=2026-08-01 23:19:07.120698`)으로 **UPDATE 복원** |
| 큐 행 `bat_rty_sn=49`(rawSn=58) | 대조군 시드 → 스윕이 PENDING 복귀시킴(TZ 스큐로 인한 검증자 시드 오류, B-ISSUE-63) + CAS 테스트로 RETRYING 전이 | 원래 값(`PENDING`, `rty_nmtm=1`, `rty_prnmnt_dt=2026-08-01 23:21:13.112377`, `mdfcn_dt=2026-08-01 23:20:13.112464`)으로 **UPDATE 복원** |
| rawSn=96 | `ON CONFLICT` 동시 INSERT 테스트로 큐 행 1건 생성 | **DELETE** 완료(원래 없었음) |
| `proc_log_sn=8` | 클레임 술어 테스트 — **`BEGIN … ROLLBACK`** 트랜잭션 안에서만 수행 | 커밋 없음(변경 0) |
| rawSn=999888 | FK 위반으로 INSERT 실패 | 잔재 없음 |

- **rawSn=101(공용 정상 데이터)은 일절 건드리지 않았다.** 프로덕션·테스트·설정 코드 수정 0건(카탈로그 `docs/test-cases/B-batch-deidentify.md` 의 담당 라인범위 6셀만 정정).

---

## 7. 이전 회차 이슈 대조

| 이슈 | 출처 | 이번 회차 전수 재확인 결과 |
|---|---|---|
| **B-ISSUE-101 재처리 원자 클레임(CWE-362)** | 2차 타겟재검증 PASS(`targeted-B-DE.md` §2) | **해소 유지 — 전수 실동작으로 재확인.** 동시 5요청 → 200 정확히 1건, 나머지 4건 409 + `raw stage owned by another caller` WARN. "①이 0행일 때 원인 재판정 → 폴백 금지" 로직이 실제로 발화. 2차의 정적 판정을 실동작이 뒷받침함 |
| **B-ISSUE-83 stale RETRYING 무음 중단** | 1차 → 수정분 | **해소 확인** — `BatchRetryStaleReclaimSweeper` 실가동(15분 tick), 회수 2건·종결 1건 실관측. 단 heartbeat 부재라는 **새 잔여 리스크**를 이번에 식별(B-ISSUE-61) |
| **B-ISSUE-82 KPST 폴링 원자 클레임** | 1차 → 수정분 | **해소 확인** — `claimForPoll`(리스) / `claimDownloadCompletion`(완료=클레임) 술어를 동시 트랜잭션으로 실동작 재현, 1/0 확인 |
| **B-ISSUE-84 폴백 회수 관측성** | 1차 | **해소 확인** — `primary mask path miss — recovered by fallback scan` WARN 배선(`:785`). 이번 회차 산출물은 1차 경로 적중이라 미발화(정상) |
| **B-ISSUE-81(#8 Quartz 클러스터링)** | 1차 UNCERTAINTIES #8 | **해소 유지** — 공통 false / stg·prd true / `QuartzClusteringGuard` fail-closed. 근거 라인 3건 전부 정확 |
| B-ISSUE-81(스트리밍 심링크)·82·85 | 1차 | 본 파트 담당 범위(B-12~14) 밖 — part 다른 담당이 다룸 |

---

# B-part5.md

# B클러스터 part5 — B-1(관제 학습용 적재) + B-6(MarkingService) 검증 결과

- 담당 범위: `docs/test-cases/B-batch-deidentify.md` B-1(18~49행, TC-BATCH-001~025) + B-6(123~154행, TC-BATCH-070~096) — 총 52건
- 검증 환경: 기동된 로컬 스택(`_raw/stack-bringup.md` 참조, HEAD `e065da42`) — backend `localhost:18081`, DB `klid_system`(스키마 `public`)
- 방법: 실동작 최우선 — 신규 rawSn(107, 110, 111, 112 + 미도착/거부 테스트용 rcptn_sn 15/16)을 직접 `LS_DATA_INGEST` INSERT + `POST /v1/dev/batch/scan`/60초 자동 스캔으로 적재 → 마킹 API 실호출. **rawSn=101(pipeline-drive.md 공용 데이터)은 건드리지 않음.**
- 카탈로그는 검증 도중 다른 병렬 에이전트가 동시 수정 중이었음(Edit 시 "파일이 디스크에서 변경됨" 경고 2회) — 재-Read 후 내 담당 라인 범위(B-1/B-6)만 대조해 적용, 충돌 없음 확인.

## 판정 집계

| 구분 | 건수 |
|---|---:|
| PASS | 47 |
| FAIL(카탈로그 결함, 정정함) | 2 (TC-BATCH-021 기대값 오류, TC-BATCH-001~005/010~025 근거드리프트 20건 묶음) |
| 확인 필요/이월 | 1 (TC-BATCH-008 — 코드 자체의 잔존 결함, B-ISSUE-83) |
| BLOCKED | 0 |
| N/A | 0 |
| **합계** | 52 |

> FAIL 2건은 모두 **카탈로그 자체의 결함**(근거 드리프트·기대값 오류)이며 담당 라인범위 안이라 Edit로 직접 정정했다. 실제 프로덕션 동작은 전건 기대대로 확인됨(PASS 47건에 포함).

## B-1. 관제 학습용 적재 — 상세 근거

### 실동작 확인 (신규 rawSn=107)

1. **적재**: `INSERT INTO ls_data_ingest(...) vms_clip_id='QA3RD-B-PART5-DEDUP-001'` → `POST /v1/dev/batch/scan`(REVIEWER) → `ls_data_raw.raw_sn=107` 생성 확인. 매핑값 실측: `evnt_type_cd=NULL`(TC-BATCH-020 PASS), `vdo_len_sec=5`(입력 그대로, ms→초 변환 없음 — TC-BATCH-022 PASS), `prvc_type_cd='PRVC'`(**TC-BATCH-021 기대값과 불일치, 아래 참조**), `src_type='ORIGINAL'`(TC-BATCH-024 PASS), `de_ident_yn='N'`→비식별 후 `'Y'`.
2. **중복 방지(반증 필수 항목)**: rcptn_sn=7(위 행)을 `proc_stts_cd='PENDING'`으로 수동 되돌려 재스캔 유발 → 로그 `[TrainingIngest] clip already ingested — skip rcptnSn=7` → `ls_data_ingest.raw_sn`은 그대로 107, **`ls_data_raw`에 신규 행 생성 안 됨**(`count(*)=1, array_agg={107}`) — TC-BATCH-014 멱등 1차 로직 실동작 확인.
3. **경로 REJECTED(CWE-22)**: `raw_file_path_nm='/etc/passwd'`(허용 루트 밖) 인입 → 스캔 후 `proc_stts_cd='FAILED'`, `err_msg='원본 영상 경로가 허용 저장 루트 밖이거나 유효하지 않음'` — TC-BATCH-015 PASS(카탈로그 기대 메시지와 완전 일치).
4. **경로 NOT_ARRIVED**: 존재하지 않는 파일 경로 인입 → `proc_stts_cd='PENDING'` 유지(FAILED 아님), `prcs_dt` 앵커 스탬프 + `next_rtry_dt = prcs_dt + 60초`(`MIN_NOT_ARRIVED_BACKOFF`), `rty_cnt=0`(증가 안 함) — TC-BATCH-017·025 PASS.
5. **1회성 WARN 게이트**: 전체 컨테이너 로그에서 `EVNT_TYPE_CD 는 관제 인입에 컬럼이 없어...` WARN 문자열이 **정확히 1회**, 이후 동일 DEBUG 로그 13회 — TC-BATCH-021의 "프로세스 생애 1회만 WARN" 부분은 PASS.

### 카탈로그 결함 발견 및 정정 (담당 범위 내 Edit 적용)

**[B-ISSUE-81] TC-BATCH-021 — 기대값 오류: `prvcTypeCd` 기대값이 `ANONY`로 적혀 있으나 실제 코드는 `PRVC`**
- 심각도: MEDIUM(카탈로그 정합성 — 실제 런타임 결함 아님)
- 기대 동작: 카탈로그 기대결과가 실제 구현과 일치해야 다음 회차가 회귀를 정확히 판정할 수 있다.
- 현재 동작(정정 전): `TC-BATCH-021` 행이 "`prvcTypeCd` 는 전체 비식별 정책상 항상 ANONY"라고 기술. 그러나 `TrainingVideoIngestTx.java:131` `private static final String DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_PRVC;`이며, 동 파일 110~130행 javadoc에 "2026-07-31 사용자 확정"으로 `PRVC`(fail-closed)를 채택하고 **`ANONY`로 두면 안 되는 이유**(원본 폴백으로 마스킹 전 프레임이 서빙되는 CWE-359)를 명시. 실측 DB(`ls_data_raw.raw_sn=107`)도 `prvc_type_cd='PRVC'`.
- 재현/확인 경로: `SELECT prvc_type_cd FROM ls_data_raw WHERE raw_sn=107;` → `PRVC`. `grep -n "DEFAULT_PRVC_TYPE" backend/.../TrainingVideoIngestTx.java`
- 영향: 카탈로그를 근거로 다음 회차가 "ANONY 여야 하는데 PRVC라 결함"이라고 오판(false FAIL)할 위험. 반대로 실제 코드가 ANONY로 회귀하면 이 카탈로그로는 감지되지 않음(위험 방향이 뒤집혀 있었음).
- 조치: `docs/test-cases/B-batch-deidentify.md` TC-BATCH-021 행의 기대결과·제목·근거(file:line)를 실제 코드에 맞춰 정정함(케이스명에 "★2026-08-03 실측 정정" 표기 추가, 정정 사유 인라인 기술).

**[B-ISSUE-82] TC-BATCH-001~005·010~025(20건) — `TrainingVideoIngestService.java`/`TrainingVideoIngestTx.java` 근거 `file:line` 대량 드리프트(+20~70행)**
- 심각도: LOW(카탈로그 정합성)
- 기대 동작: 근거 라인이 실제 코드 위치를 가리켜야 함.
- 현재 동작: 2026-08-02(2차) 재작성 시점 이후 두 파일에 방대한 javadoc(트랜잭션 규약·CWE 근거 설명)이 추가되며 대부분의 참조 라인이 실제 위치보다 20~70행 앞선 값으로 고정되어 있었음(예: TC-BATCH-001 `:69-74`→실제 `:127-133`, TC-BATCH-022 `:505-521`→실제 `:577-590`). 같은 라운드의 "근거 file:line 전수 재확인"(회차 이력 4번째 행)이 명시한 드리프트 원인 파일 목록에 이 두 파일이 빠져 있어 놓친 것으로 추정(git log 상 `0449e9e7` "MNG_* 로직 검토 후속조치" PR이 유력 원인 커밋).
- 재현/확인 경로: `grep -n "no pending ingest rows to scan" backend/.../TrainingVideoIngestService.java` → 131 (카탈로그 구값 69-74와 불일치했던 사례).
- 영향: 다음 회차 검증자가 근거를 열람할 때 엉뚱한 코드를 보게 됨(라인 자체는 여전히 파일 안에 존재하는 다른 javadoc 문단이라 당장 오판까지는 안 가지만, 신뢰도 저하 + 검증 시간 낭비).
- 조치: 25건 중 20건(TC-BATCH-001~005, 010~025)의 근거 라인을 grep으로 재확인해 정정함. TC-BATCH-006~009(ControlTrainingVideoScanJob/TriggerConfig)는 드리프트가 3~5행 이내로 허용 오차 범위라 정정하지 않음.

### 이월 이슈 — 코드 자체의 미해소 결함 (정정 아님, 보고만)

**[B-ISSUE-83] TC-BATCH-008 관련 — Quartz JobDetail 설명 문구가 폐기된 구 스킴(`JOB_DMND_YN='Y'`)을 그대로 유지**
- 심각도: LOW
- 기대 동작: B-1이 관제 인입 픽업(`LS_DATA_INGEST`)으로 전환된 만큼(B-ISSUE-01, 2026-08-02 해소) 운영 메타데이터도 갱신되어야 QRTZ 테이블을 조회하는 운영자가 오인하지 않는다.
- 현재 동작: `ControlTrainingVideoScanTriggerConfig.java:33` — `.withDescription("관제 학습용 영상(JOB_DMND_YN='Y') 픽업 적재")`. 이 조건은 이미 폐지된 구 `MNG_CLIP_MASTER` 스캔 방식의 조건이며 현재는 `LS_DATA_INGEST.PROC_STTS_CD='PENDING'` 픽업이다. 이 문자열은 `QRTZ_JOB_DETAILS` 테이블에 그대로 적재된다.
- 재현/확인 경로: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT job_name, description FROM qrtz_job_details WHERE job_name='controlTrainingVideoScanJob';"` (또는 소스 grep으로 확인 — 위 라인).
- 영향: 기능 결함 아님(잡 동작 자체는 정상). 다만 2026-08-01(1차) 회차의 B-ISSUE-04와 동일 사안으로, 이월표(UNCERTAINTIES.md "미해소 이월 이슈")에 등재되지 않아 놓칠 뻔했음 — **미해소 상태이며 이번 회차에도 재확인됨**.
- 수정 방향(제안): `.withDescription("관제 학습용 영상(LS_DATA_INGEST PENDING) 픽업 적재")` 로 문구만 교체(동작 변경 없음). 프로덕션 코드이므로 이번 검증에서는 수정하지 않음.

## B-6. MarkingService — 상세 근거 (전건 근거 라인 드리프트 없음 — 실동작 위주 확인)

MarkingGuards.java/MarkingService.java/VideoDurationResolver.java/MarkingController.java의 근거 `file:line` 27건 **전부 grep 대조 결과 정확**(오차 0~1행). 아래는 실동작으로 재확인한 핵심 항목.

| TC-ID | 실동작 확인 | 결과 |
|---|---|---|
| TC-BATCH-070 | Authorization 헤더 없이 `POST /v1/videos/107/markings` | `401 UNAUTHORIZED` |
| TC-BATCH-072 | 미배정 WORKER(userNo=2002) 토큰으로 요청 | `403 FORBIDDEN "본인에게 배정된 영상의 마킹만..."` |
| TC-BATCH-089 | PORTAL_USER 토큰으로 요청 | `403 FORBIDDEN` (`@PreAuthorize` 컨트롤러 레벨) |
| TC-BATCH-078 | AUTO intervalFrames=0/-5/null(REVIEWER) | 3건 모두 `400 INVALID_INPUT` |
| TC-BATCH-080/081/082/085/086/087 | AUTO intervalFrames=30, dur=5s·fps=10(실측 메타) | `201`, marks=`[{0,"00:00"},{30,"00:03"}]`(totalFrames=50, off-by-one 미포함 확인), `eventName="INTRUSION"`(자동소싱), `batchTriggered=true` |
| TC-BATCH-090/091 | 동일 rawSn(110)에 **동시(백그라운드 병렬) 2회** POST | 1건 `201`, 1건 `409 CONFLICT`("이미 진행 중인 마킹이...") — 500 아님, 부분 저장 없음 확인 |
| TC-BATCH-083 | MANUAL marks=[] | `400 INVALID_INPUT "수동 모드에서 marks 는 필수입니다."` |
| TC-BATCH-084 | mode="BOGUS", mode="auto"(소문자) | 둘 다 `400 INVALID_INPUT "mode 는 AUTO 또는 MANUAL..."` — 대소문자 관용 없음(fail-closed) 확인 |
| TC-BATCH-092 | MANUAL marks에 동일 frameIndex=5 2회 | `400 INVALID_INPUT "중복된 마킹 시점입니다: frameIndex=5"` |
| TC-BATCH-093 | 상한(dur=5s,fps=10→limit=60) 기준 frameIndex=999999999(far-over)/60(경계값)/59(경계 통과) | 999999999→400, 60→400("허용 상한 60 프레임 미만"), 59→**201** — `manualFrameIndexLimit=round(5×10)+ceil(10)=60` 공식과 정확히 일치 |
| TC-BATCH-096 | AUTO intervalFrames=999999999(상한 미검증 확인) | `201`, marks=`[{frameIndex:0}]` 1건뿐 — **B-ISSUE-23(미해소, 현재 동작 고정) 재확인, 신규 결함 아님** |

## 이전 회차 이슈 해소 여부 (내 담당 범위 관련분)

| 이슈 | 상태 |
|---|---|
| B-ISSUE-01(B-1 카탈로그 전체 무효) | ✅ 해소 확인 — 2026-08-02(2차) 재작성 반영, 현재 코드(`LS_DATA_INGEST` 픽업)와 카탈로그 서술 일치 |
| B-ISSUE-04(Quartz JobDetail 구 스킴 문구 잔존, 1차 2026-08-01) | ❌ **미해소 재확인** — 위 B-ISSUE-83 참조. UNCERTAINTIES.md 이월표에 누락되어 있었음(주의 필요) |
| B-ISSUE-23(AUTO intervalFrames 상한 미검증, UNCERTAINTIES 이월표 등재) | 현재 동작 유지 확인(카탈로그 자체가 "고정 동작"으로 못박아 신규 결함 아님) |
| B-ISSUE-42/B-ISSUE-41(오토라벨 배치저장·고아 활성마킹) | 내 담당 범위(B-1/B-6) 밖 — 확인 안 함 |

## 근거 드리프트/카탈로그 정정 총 건수: **2건 이슈, 22개 행 정정**(TC-BATCH-021 기대값 1행 + 근거라인 21행, TC-BATCH-001~005/010~025)

---

# B-part6.md

# B클러스터 part6 — B-8(FfmpegFrameExtractor) · B-9(YOLO/SAM2/Interpolate) 검증 결과

- 대상: `docs/test-cases/B-batch-deidentify.md` 196~254행, TC-BATCH-100~149 (총 49건, TC-BATCH-119는 결번)
- 방법: 정적 대조(코드 Read/Grep) + 실동작(3차 §3-3 pipeline-drive.md rawSn=101 실구동 결과 재사용 — DB 조회·`docker logs klid-backend` 재확인) + 테스트 커버 대조(`_raw/test-baseline.md`: backend 5203 총/5198 성공/0 실패/0 에러/5 스킵(무관 클래스), `cleanTest test` 강제 실행 확인됨)
- 코드/설정/프로덕션 파일 수정 없음. 카탈로그(`docs/test-cases/B-batch-deidentify.md`) 라인 드리프트 2건은 담당 라인범위(196~254) 내에서 Edit로 직접 정정함(§카탈로그 정정 참조).

## 실동작 근거 요약 (rawSn=101, srcSn 468~477)

```sql
-- 프레임 경로 분기 실측
SELECT src_sn, src_file_path_nm, de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn=101;
-- src_file_path_nm=/app/storage/raw/frames/raw/101/frame-N.jpg
-- de_idntf_src_file_path_nm=/app/storage/deidentified/frames/deid/101/frame-N.jpg  (경로 분기 정상)

-- co-locate 비식별 영상 실경로
SELECT de_idntf_file_path_nm FROM ls_deident_proc_log WHERE data_raw_sn=101;
-- /app/storage/raw/seed/101/deid/clip-9101-mask.mp4   (dirname(원본)/{rawSn}/deid/ — co-locate 확인)

-- 라벨 결과 (오토라벨 0건 확인)
SELECT l.lbl_sn, l.src_sn, l.trck_id, a.auto_lbl_yn FROM ls_data_lbl l LEFT JOIN ls_data_lbl_ai_info a ON a.data_lbl_sn=l.lbl_sn WHERE l.src_sn BETWEEN 468 AND 477;
-- 729|468|(null)|(null)  ← WORKER 수동 BBOX 1건뿐, AI_INFO 없음(자동라벨 0건)
```

```
docker logs klid-backend (rawSn=101 관련):
[Batch][FrameExtract] mark-based extracted rawSn=101 frames=10                         ← WARN 없음(co-locate 채택 성공)
[Batch][YOLO] mock response detected ... rawSn=101 srcSn=468~477 source=mock mockReason=weights_missing  (×10)
[Batch][Yolo] saved labels rawSn=101 ... yoloCount=0 bboxSaved=0 hintsEmitted=0 droppedDegenerate=0 droppedMalformed=0
[Batch][Sam2] saved polygons rawSn=101 count=0
[Batch][Interpolation] no interpolation candidates rawSn=101
```
→ ai-server weights 미탑재(**기존 알려진 갭**, `stack-bringup.md`/`pipeline-drive.md` 4-3 기재)로 YOLO 빈 detections. mockReason=weights_missing 은 빈 detections 사유이며 self-fill 아님(CLAUDE.md/코드 주석: "weights_missing 등은 빈 detections" — env_mock 만 합성 박스를 만드는데 이 환경은 `AI_MOCK_MODE=false`라 도달 안 함). 로그 순서(FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE)는 `BatchPipelineConfig` 선언 순서와 실측 일치.

## 판정 표

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-BATCH-100 | PASS | [정적] FfmpegFrameExtractor.java:133-136 marks empty→INVALID_INPUT, 근거 라인 일치 | |
| TC-BATCH-101 | PASS | [정적] :144-147 frames empty→INTERNAL_ERROR, 일치 | |
| TC-BATCH-102 | PASS | [정적] :177-179 rawFilePathNm blank→INVALID_INPUT, 일치 | |
| TC-BATCH-103 | PASS | [정적][실동작] :185-188 deIdntfYn≠Y→INVALID_INPUT. 실측(pipeline-drive.md)도 비식별 완료 후에만 FRAME_EXTRACT 진행 확인 | |
| TC-BATCH-104 | PASS | [정적] :190-193 원본 부재→INVALID_INPUT, 일치 | |
| TC-BATCH-105 | PASS | [정적] :206-213(WARN, rawSn만) + :316-333(isUnderAllowedDeidBase), 경로 원문 미노출 확인. 테스트 `FfmpegFrameExtractorTest#MEDsec_비식별경로가_base밖이면...` 존재, baseline PASS | |
| TC-BATCH-106 | PASS | [정적] :216-225 RAW only WARN, 일치 | |
| TC-BATCH-107 | PASS | [실동작] rawSn=101 DB `de_idntf_src_file_path_nm` 전 10행 non-null 확인 — INSERT 시점(6-arg `LsDataSrc.create`, :148-149 시그니처 확인) 포함 실증. 근거 :262-277 일치 | |
| TC-BATCH-108 | PASS | [정적] :234,248,393-398 seekMillis=round(frameIndex*1000/fps), pin 우선 — 라인 일치(1차 회차 지적된 :266 드리프트는 이미 :248 로 정정되어 있음, 재확인) | |
| TC-BATCH-109 | PASS | [정적] :393-398 effectiveFps 폴백, 일치 | |
| TC-BATCH-110 | PASS | [정적] :377-384 resolveSafeOutputDir, base 이탈 시 INVALID_INPUT, 일치 | |
| TC-BATCH-111 | PASS | [실동작] rawSn=101 `frames/raw/101/*`·`frames/deid/101/*` 분기 확인(위 SQL). 근거 :377-384 일치 | |
| TC-BATCH-112 | PASS | [정적] :272-276 CREATED+DEID_ATTACHED 2건, 일치 | |
| TC-BATCH-113 | PASS | [실동작] rawSn=101 비식별 영상이 실제로 `dirname(원본)/{rawSn}/deid/`(co-locate)에서 채택됨(WARN 없음, DE_IDNTF_SRC_FILE_PATH_NM 채워짐). 근거 FfmpegFrameExtractor.java:316-333 일치, VideoArtifactRootResolver.java 참조는 정정(아래 카탈로그 정정 참조) | |
| TC-BATCH-114 | PASS | [정적] 구 위치(`{deid_base}/videos/{rawSn}/`)도 `readableDeidVideoDirs`(:308-320)가 계속 후보에 포함. **근거 드리프트 발견 및 정정**(아래) | 카탈로그 정정 |
| TC-BATCH-115 | PASS | [정적] `underBaseWithRealPath`(FfmpegFrameExtractor.java:356-367)가 `verifyRealPathUnder` 실패를 RuntimeException catch→false 로 흡수 확인. 테스트 `FfmpegFrameExtractorTest#CWE59_co_locate_허용경로의_심링크가_원본영상을_가리키면_거부되고_RAW만_추출된다` 존재, baseline PASS. **근거 드리프트 발견 및 정정**(아래 — 구 인용 458-467 은 javadoc 뿐, 실제 메서드는 469-471/487-497) | 카탈로그 정정 |
| TC-BATCH-116 | PASS | [정적] VideoArtifactRootResolver.java:474-497 `resolveRealPathUnder`(487-497)가 `realOrNearest` 로 중간 세그먼트 심링크까지 접어 재검증. 테스트 `CWE59_경로중간_세그먼트가_심링크로_base밖을_가리키면...` 존재 | |
| TC-BATCH-117 | PASS | [정적] :316-320 리졸버 null→구 동작(`underBaseWithRealPath(deidPath, baseDeidPath)`) 폴백, 일치 | |
| TC-BATCH-118 | PASS | [정적] :321-326 후보 도출 RuntimeException→구 동작 폴백, VideoArtifactRootResolver.java:341-346 대응 확인, 일치 | |
| TC-BATCH-120 | PASS | [정적] YoloAutolabelStep.java:180-183 rawSn null→INVALID_INPUT, 일치 | |
| TC-BATCH-121 | PASS | [실동작] rawSn=101 로그 clipId=101(=String.valueOf(rawSn)), 10프레임 순차 호출 확인(YOLO WARN 로그 10줄, srcSn 468→477 순서). 근거 :195-197,209-212 일치 | |
| TC-BATCH-122 | PASS | [정적] :219-227 RuntimeException→EXTERNAL_API_ERROR, 일치 (본 구동에서는 호출 자체는 200 OK라 실패 경로 미발동 — 정적 대조로 보완) | |
| TC-BATCH-123 | PASS | [실동작] rawSn=101 실로그 "mock response detected ... mockReason=weights_missing" 10건 — LogSanitizer 정제 문자열로 정상 출력(CRLF/제어문자 없는 클린 값). 근거 :244-248,270-275 일치 | |
| TC-BATCH-124 | PASS | [정적] :318-321 `findLabelIdByDtctType` 매핑, 일치(본 구동은 검출 0건이라 실제 매핑 호출은 없었음 — 정적 대조) | |
| TC-BATCH-125 | PASS | [정적] :467-477 resolveToggle, togglesOpt empty→BOTH, 일치 | |
| TC-BATCH-126 | PASS | [정적] :494-499 readImageAsBase64 경로 이탈→INVALID_INPUT, 일치 | |
| TC-BATCH-127 | PASS | [실동작] rawSn=101 로그 "conf=0.25 imgsz=1280 iou=0.5"(YOLO saved labels 라인) — SystemConfig 기본값이 실제 ai-server 요청 파라미터로 반영됨 확인(로그에 노출). 근거 :191-193,425-453 일치 | |
| TC-BATCH-128 | PASS | [정적] Sam2SegmentStep.java:304-320 buildJobs dedup, DB BBOX putIfAbsent 우선, 라인 정확 일치 | |
| TC-BATCH-129 | PASS | [정적] :195-201 polygon=false skip WARN, 일치 | |
| TC-BATCH-130 | PASS | [정적] :234,428-446 capPolygon, 상한 초과 시 simplifyToMax, 라인 정확 일치. 테스트 `Sam2Step_4192점_응답_폴리곤은_저장전_1000점_이하로_simplify되어_저장된다` 존재 | |
| TC-BATCH-131 | PASS | [정적] :289-297 callSam2 실패→EXTERNAL_API_ERROR, 라인 정확 일치 | |
| TC-BATCH-132 | PASS | [실동작] rawSn=101 실로그 "no interpolation candidates rawSn=101" — 후보 0건(YOLO 검출 0건→trackId 있는 BBOX 없음)에서 정상 0 반환 확인. 근거 :131-134,144-147,174-178 일치 | |
| TC-BATCH-133 | PASS | [정적] :158-172 stale 재실행 시 AI_INFO→LBL 순 삭제 후 재생성, 라인 정확 일치 | |
| TC-BATCH-134 | PASS | [정적] :190 catch(Exception ex) 트랙 단위 격리, 일치 | |
| TC-BATCH-135 | PASS | [정적] 혼재 타입 skip 실제 판정문은 :354-358(`if(types.size()>1)` WARN+return). **근거 드리프트 발견 및 정정**(카탈로그가 353 한 줄만 지목 — 그 줄은 types 계산일 뿐 분기문이 아님) | 카탈로그 정정 |
| TC-BATCH-136 | PASS | [정적] :441 parseBbox 메서드 시작 — flat/nested 양포맷 파싱(441-481) 확인, 일치 | |
| TC-BATCH-137 | PASS | [정적] :247-330 interpolateSingleTrack/Touched — stale는 from+to 양쪽(:274-275 `List.of(fromTrackId, toTrackId)`), 재보간 호출부(:313)에 try/catch 없어 예외가 caller(TrackMergeService)로 전파됨을 확인(원자성). 일치 | |
| TC-BATCH-138 | PASS | [정적] MarkingLoadStep.java:50-58 execute — setMarkings/setMarks, 일치 | |
| TC-BATCH-139 | PASS | [정적] :60-65 parseMarks 실패→INTERNAL_ERROR, 일치 | |
| TC-BATCH-140 | PASS | [실동작] rawSn=101 로그 타임스탬프 순서(FRAME_EXTRACT→YOLO→SAM2→INTERPOLATE, VLM 은 앞서 별도 제출)가 BatchPipelineConfig.java:33-41 `List.of(markingLoad, vlm, frame, yolo, sam2, interp)` 선언과 일치 | |
| TC-BATCH-141 | PASS | [정적] DetectionBoxNormalizer.java:41-70(실제 clamp 로직 50-69), 0≤x≤W/0≤y≤H clamp 확인. 테스트 `이미지_상한_초과좌표는_이미지_경계로_clamp된다` 등 baseline PASS | |
| TC-BATCH-142 | PASS | [정적] :54-58 유한성 가드가 clamp(:59-64) 이전에 위치, NaN<0=false 함정 회피 확인. 테스트 `NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지` 존재 | |
| TC-BATCH-143 | PASS | [정적] :60-70(정확 65-67 퇴화 판정) + YoloAutolabelStep.java:305-311,342-347 두 지점 모두 droppedDegenerate 로 스킵 확인. ★2축 정책(AI 검출=clamp+퇴화스킵) 정합 — 사용자 저장 400 거부와 혼동 없음 | |
| TC-BATCH-144 | PASS | [실동작+정적] YoloAutolabelStep.java:301-317 loop 내 catch(IllegalArgumentException)→droppedMalformed++·continue 확인(본 구동은 형식위반 0건이라 정적 대조 위주). 온라인 경로(`AutolabelOnlineService.normalizeDetections:562-575`) 는 동일 형식위반에 `throw new CustomException(INVALID_INPUT)`로 all-or-nothing 유지 확인 — ★3 좌표검증 2축 정책과 일치, "비일관"이 아님 | |
| TC-BATCH-145 | PASS | [정적] :358-366 BbHint 가 clamp 된 points 를 공유, 일치 | |
| TC-BATCH-146 | PASS | [정적] :301-317 정규화 실패/퇴화 시 continue 로 bbox 저장·hint 발행 둘 다 스킵, 일치(144 와 동일 라인 — 같은 분기가 두 효과를 겸함, 카탈로그상 정상) | |
| TC-BATCH-147 | PASS | [정적] DetectionBoxNormalizer.java:41-49,72-78 bounds null/비정상→상한 생략(Double.MAX_VALUE), YoloAutolabelStep.java:278-279 확인 | |
| TC-BATCH-148 | PASS | [실동작] rawSn=101: `resp.detections().isEmpty()` 분기로 인해(본 구동은 weights_missing→빈 detections) frameBoundsResolver 미호출 경로 실제로 탐. 근거 :278-279 일치 | |
| TC-BATCH-149 | PASS | [실동작] rawSn=101: 검출 0건이라 `labeledFrames` 비어 `bumpLabelVersionIn` 미호출(로그에 해당 UPDATE 실행 흔적 없음, saved labels 라인만 존재) — "라벨 생성된 프레임만 bump" 의 대우(생성 0건→bump 0회) 실증. 근거 :202,341,385-386 · LsDataSrcRepository.java:292-295(grep 재확인, 완전 일치) | |

**집계**: PASS 49 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0 (총 49건, TC-BATCH-119 결번은 분모 제외)

## 카탈로그 정정 (담당 라인범위 196~254행 내 Edit 완료, 3건)

1. **TC-BATCH-114** (214행) — `VideoArtifactRootResolver.java:308-327,337-348` → **`308-320,337-346`** 정정.
   - 실제 `readableDeidVideoDirs` 메서드는 308~320행(다음 메서드 `readableDeidVideoBases` 의 javadoc 이 322~336행에 별도 존재 — 구 범위가 이를 침범).
   - `readableDeidVideoBases` 메서드는 337~346행(구 범위 337-348 은 다음 메서드 javadoc 2줄까지 침범).
   - 이 드리프트는 2026-08-01 1차 회차에서 이미 한 번 지적됐으나(`docs/검증결과/2026-08-01/1차/ISSUES.md` 근거 드리프트 섹션) 카탈로그에 반영되지 않고 남아 있었다 — 이번에 정정 완료.
2. **TC-BATCH-115** (215행) — `VideoArtifactRootResolver.java:458-467` → **`469-471,487-497`** 정정.
   - 구 범위(458-467)는 `verifyRealPathUnder` 메서드의 **javadoc 전체**일 뿐 실제 코드가 아니다(468행 `*/` 로 javadoc 종료, 469행부터 실제 메서드 3줄).
   - 실질 판정 로직(`realOrNearest` 비교, FORBIDDEN 예외)은 `resolveRealPathUnder`(487-497)에 있어 이를 함께 인용하도록 정정.
   - FfmpegFrameExtractor.java 쪽 참조도 `356-368`→`356-367`로 미세 정정(367행이 `underBaseWithRealPath` 메서드의 실제 닫는 괄호, 368행은 공백줄).
3. **TC-BATCH-135** (239행) — `TrackInterpolationStep.java:353` → **`354-358`** 정정.
   - 353행은 `types` 리스트를 계산하는 문장일 뿐이고, "혼재 타입 skip" 을 실제로 판정·실행하는 코드(`if (types.size() > 1)` + WARN 로그 + `return List.of()`)는 354~358행이다.

## 확증편향 반증 메모

- **좌표 검증 2축 혼동 여부(지시 사항 반증 포인트)**: TC-BATCH-141~148(배치, clamp+퇴화스킵)과 TC-BATCH-144 온라인 경로(all-or-nothing 400)를 코드 레벨에서 직접 대조 — 배치는 `continue`(검출 단위 스킵), 온라인은 `throw CustomException(INVALID_INPUT)`(전체 400)로 실제로 분기가 다름을 확인. 두 경로 모두 같은 `DetectionBoxNormalizer.normalizeBbox` 를 호출하지만 **호출부가 결과를 다르게 소비**하는 것이 설계 의도(UNCERTAINTIES ★3)이며, "비일관"으로 잘못 보고할 소지를 코드로 직접 반증했다.
- **self-fill 여부**: YOLO mockReason=weights_missing 은 빈 detections 를 반환하며(ai-server 소스 근거는 이전 회차 확인, 본 회차는 backend 로그 `bboxSaved=0 hintsEmitted=0`로 재확인), `env_mock` 사유(합성 person 박스)와 달리 이 환경(`AI_MOCK_MODE=false`)에서는 도달하지 않는 경로임을 실측으로 재확인했다 — self-fill 아님.
- **기존 알려진 갭 재확인**: ai-server weights 미탑재(오토라벨 검출 결과 자체는 BLOCKED 대상)는 `stack-bringup.md`·`pipeline-drive.md` 에 이미 기록된 회차 공통 갭이며, 본 part6 에서는 신규 결함으로 집계하지 않았다(경로/계약/방어 로직 검증에는 영향 없음).

## 이전 회차(1차, 2026-08-01) 이슈 대조

- 본 담당 범위(B-8/B-9) 관련 1차 이슈 중 직접 대응되는 것은 **근거 드리프트 지적 1건**(TC-BATCH-114 관련, "TC-BATCH-108 :266→:248" 및 "TC-BATCH-114 :308-327→:308-320" 를 함께 언급한 메모)이며, TC-BATCH-108 쪽은 이미 정정되어 있었으나 **TC-BATCH-114 는 미정정 상태로 남아 있어 이번에 정정**했다(위 카탈로그 정정 1번).
- B-ISSUE-22/23/24/42/61/65/82/84/85 등 1차 주요 이슈는 B-1~B-7(배치 오케스트레이터·VLM·스트리밍) 및 B-11 이후 영역 소관이라 본 part6(B-8/B-9) 재검증 대상 밖이다 — 별도 이월 처리 불필요.

---

# B-part7.md

# B클러스터 part7 — B-15·B-16·B-17·B-18 검증 결과 (3차, 2026-08-03/04)

담당 라인범위: `docs/test-cases/B-batch-deidentify.md` 395행~파일끝(480행). 총 43건.
이슈 ID 시작: B-ISSUE-121(미사용 — 발견된 FAIL/PARTIAL 없음, 아래 참조).

## 방법
- B-15/B-17: 소스 Read/Grep + 실 baseline 테스트 결과(XML, HEAD `e065da42`, 2026-08-04 00:09 생성분) 대조. gradle 재실행 없음(지시 준수).
- B-17: 라이브 DB(`klid-postgres`, `klid_system`) information_schema 직접 쿼리로 FK 존재·개수·delete_rule 실측.
- B-18: 기동 중인 스택(backend :18081)에 실제 curl 요청 + DB 상태 대조. REVIEWER(userNo=1001) dev 토큰 발급 후 12개 필터 조합 실측.

## B-15. 배치 스텝 트랜잭션 경계 (TC-BATCH-180~190) — 11건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-180 | PASS | [정적] YoloAutolabelStep.execute (166-170) `@Transactional(controlTransactionManager, REQUIRES_NEW)` 확인. 근거 라인 155-158→166-170 드리프트, 카탈로그 정정함 |
| TC-BATCH-181 | PASS | [정적] Sam2SegmentStep.execute (146-150) 동일 애노테이션 확인 |
| TC-BATCH-182 | PASS | [정적] TrackInterpolationStep.execute (104-105) 확인, 근거 라인 거의 정확 |
| TC-BATCH-183 | PASS | [정적] VlmTimeseriesStep.execute (219-227) readOnly 미지정(쓰기 가능) 확인, runWithMarking 분기가 마킹 상태 전이. 근거 라인 205-227→219-227 드리프트, 카탈로그 정정함 |
| TC-BATCH-184 | PASS | [정적] FfmpegFrameExtractor.execute (129-130) 확인, 근거 라인 정확 |
| TC-BATCH-185 | PASS | [정적] 5개 스텝 모두 `execute`→typed 메서드 자기호출(this.run/extractByMarks), 프록시 미경유로 중첩 없음 확인 |
| TC-BATCH-186 | PASS | [정적] YoloAutolabelStep.run (179-180) · VlmTimeseriesStep.run (236-237) 별도 REQUIRES_NEW 유지 확인, 근거 정확 |
| TC-BATCH-187 | PASS | [실동작] `BatchStepTransactionBoundaryTest.selfProxyStepsMustNotAnnotateExecute` 실행 결과 PASS(baseline XML, 2 tests 0 failures). DeidentifyStep.execute 무애노테이션 확인 |
| TC-BATCH-188 | PASS | [정적] MarkingLoadStep.execute (49-58) DML 0건(조회+파싱만) 확인, 근거 정확 |
| TC-BATCH-189 | PASS | [실동작] `BatchStepTransactionBoundaryTest.everyStepExecuteHasRequiresNewBoundary` PASS. 클래스패스 스캔 결과 BatchStep 구현 7개(YOLO/SAM2/TrackInterpolation/VLM/FrameExtract/Deidentify/MarkingLoad) 확인 — `hasSizeGreaterThanOrEqualTo(6)` 단언 공허하지 않음 |
| TC-BATCH-190 | PASS | [정적] LsDataSrcRepository.bumpLabelVersionIn (295, `@Modifying`만 있고 `@Transactional` 없음) 확인, 근거 정확(282-295) |

**baseline 근거**: `build/test-results/test/TEST-kr.co.cudo.authoring.batch.pipeline.BatchStepTransactionBoundaryTest.xml` → `tests="2" failures="0" errors="0"` (2026-08-04 00:09 생성, HEAD e065da42 기준).

## B-16. 오토라벨 일괄저장 (TC-BATCH-191~196) — 6건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-191 | PASS | [정적+실동작] `AutoLabelBatchPersister.saveAll` (61-85) 프레임당 saveAll 2회(라벨→AI메타) 확인. YoloAutolabelStep.java:214/369-370, Sam2SegmentStep.java:187/244-245 호출부 확인(근거 거의 정확, ±1줄). `YoloAutolabelStepTest.한_프레임의_검출들은_라벨_saveAll_1회와_AI메타_saveAll_1회로_저장된다` PASS(45 tests 0 failures) |
| TC-BATCH-192 | PASS | [정적] saveAll 반환 리스트를 인덱스 순회하며 `label.getLblSn()/getSrcSn()`으로 AI메타 구성(77-82) 확인. PK 매칭 계약 주석(30-34) 확인 |
| TC-BATCH-193 | PASS | [정적] size 불일치 시 `ErrorCode.INTERNAL_ERROR`("자동 라벨 일괄 저장 결과 개수 불일치") 즉시 throw (73-76) 확인 |
| TC-BATCH-194 | PASS | [정적] `pending==null\|\|isEmpty()` 조기 반환 0 확인(65-67). 근거 라인 "55"는 doc comment 줄이고 실제 코드는 65-67 — 근거 드리프트(경미, 담당범위 내 정정은 생략, 아래 카탈로그 정정 로그 참조) |
| TC-BATCH-195 | PASS | [정적] `PendingLabel(label, score)` record(47-48)로 원본 신뢰도 별도 보관, AI메타 생성 시 `pending.get(i).score()` 사용(81) 확인 — 엔티티 clampScore 보정과 분리됨 |
| TC-BATCH-196 | PASS | [정적] IDENTITY 전략 + JDBC batch 미적용 한계 명시 주석(22-28) 확인. `Sam2SegmentStepTest` PASS(21 tests 0 failures) |

## B-17. LS_DATA_RAW 참조무결성 FK V146 (TC-BATCH-200~207) — 8건

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-BATCH-200 | PASS | [실동작] 라이브 DB `information_schema` 쿼리: `ls_data_raw` 참조 FK 총 29건(CASCADE 27 + SET NULL 2). V146 자체 신설분(child_specs 배열 카운트: 배치7·작업검수9·버전증강5·관제3·포털1·원장세션2=27, CASCADE 25/SET NULL 2)과 정확히 일치. 나머지 2건(CASCADE)은 기존 LS_EVNT_ANNO + V162 신설 LS_CLIP_SCHEDULE_QUE — 카탈로그 서술("V146 이 27개 생성")과 모순 없음(V146 신설분만 27개가 맞음) |
| TC-BATCH-201 | PASS | [정적+실동작] `LsDataRawChildFkCascadeIT.deletingRawCascadesToChildren` PASS(7 tests 0 failures, baseline). SQL 주석(10-14)에 RESTRICT 미채택 사유(ResolutionPersistService/TusUploadService 삭제 경로 보존) 명시 |
| TC-BATCH-202 | PASS | [실동작] `LsDataRawChildFkCascadeIT.원장_세션_참조는_SET_NULL로_끊기고_행_자체는_보존된다` PASS. SQL(72-74) SET NULL 대상 2건 확인 |
| TC-BATCH-203 | PASS | [정적] `view_feed_tables` 배열(77-85, 7개 테이블) + RAISE EXCEPTION 분기(112-117) 확인 |
| TC-BATCH-204 | PASS | [정적] `max_orphans CONSTANT BIGINT := 1000`(87) + 초과 시 RAISE EXCEPTION(107-110) 확인 |
| TC-BATCH-205 | PASS | [정적+실동작] V146 주석(16-25)에 MNG_CLIP_SCHEDULE_QUE·ORGNL_RAW_SN 제외 근거 확인. V162 실측: 라이브 DB에 `ls_clip_schedule_que`(개명 완료, `mng_clip_schedule_que` 테이블 0건) + `fk_ls_clip_schedule_que_raw` 존재 확인. `LsClipScheduleQueFkIT` PASS(5 tests 0 failures) |
| TC-BATCH-206 | PASS | [정적] SQL 3-패스 구조(1)고아조사(95-119) (2)정리(121-143) (3)FK생성(145-163) 확인 |
| TC-BATCH-207 | PASS | [정적+실동작] `IF NOT EXISTS (... pg_constraint ...)` 멱등 가드(157) 확인. 이미 V163까지 적용된 라이브 DB에서 정상 기동 확인(Flyway 재실행 시 skip 동작이 실제로 일어난 상태) |

## B-18. 영상 처리 현황 목록 검색·필터 (TC-VIDEO-001~018) — 18건 [전건 실동작 라이브 curl 검증]

전제: REVIEWER(userNo=1001) dev 토큰 발급, backend :18081, DB `ls_data_raw`: 전체 95건 / `orgnl_raw_sn IS NULL`(원본) 71건.

| ID | 판정 | 실동작 근거 |
|---|:--:|---|
| TC-VIDEO-001 | PASS | `GET /v1/videos?page=0&size=5` → HTTP 200, `totalElements=71` = DB `count(orgnl_raw_sn IS NULL)` 71 과 정확히 일치. 필터 미전송 시 기본 정렬로 정상 응답 |
| TC-VIDEO-002 | PASS | `?cctvNameKeyword=cctv-qa`(소문자) → HTTP 200, `total=14`. `MNG_RESOURCE_CCTV` 마스터에 매칭 없는 `vms_cctv_id`(CCTV-QA*)라도 폴백 매칭 확인, 대소문자 무시 확인 |
| TC-VIDEO-003 | PASS | `?cctvNameKeyword=107` → HTTP 200, `total=1`, `content[0].id=107`(rawSn 일치 매칭) |
| TC-VIDEO-004 | PASS | `?cctvNameKeyword=%25`(URL인코딩 `%`) → HTTP 200, `total=0`(전체 목록 아님 — 리터럴 이스케이프 확인, 71건 전체가 나왔다면 결함이었을 것) |
| TC-VIDEO-005 | PASS | 101자 검색어 → **HTTP 400** `"검색어는 100자 이하여야 합니다."` (`INVALID_INPUT`) — ★5 정책과 일치 |
| TC-VIDEO-006 | PASS | `?eventTypeCd=020002`(EV02000201의 카테고리 키) → HTTP 200, `total=51` = DB `count(evnt_type_cd='EV02000201')` 51과 정확히 일치. 근거 EventTypeService.java 라인 드리프트 발견·카탈로그 정정(157-180→175-198) |
| TC-VIDEO-007 | PASS | `?eventTypeCd=999999`(미등록) → **HTTP 200**, `total=0` — 400 아님, ★5 정책과 정확히 일치 |
| TC-VIDEO-008 | PASS | 21자 카테고리 키 → **HTTP 200**, `total=0`(400 아님) — TC-VIDEO-007과 동일 처리 확인 |
| TC-VIDEO-009 | PASS | `?eventTypeCd=020002&cctvNameKeyword=106`(rawSn=106은 evnt_type_cd='INTRUSION', 마스터 미등록) → HTTP 200, `total=0` — 미등록 EV코드 영상이 카테고리 필터에 안 잡힘 확인 |
| TC-VIDEO-010 | PASS | `?from=2026-08-01&to=2026-08-01&cctvNameKeyword=901`(sht_dt=2026-08-01 23:47:23) → HTTP 200, `total=1`, `id=901` — 당일 23:59:59.999 경계 포함 확인 |
| TC-VIDEO-011 | PASS | `?from=2026-08-05&to=2026-08-01`(역전) → **HTTP 400** `"시작일은 종료일보다 늦을 수 없습니다."` — ★5 정책과 일치 |
| TC-VIDEO-012 | PASS | `?from=abc`(형식 오류) → **HTTP 400** `"파라미터 형식이 올바르지 않습니다: from"`, 내부 경로·스택 미노출 확인 — ★5 정책과 일치 |
| TC-VIDEO-013 | PASS | `?reviewStatusCd=APPROVED&eventTypeCd=020002&size=100` → HTTP 200, `total=16`, `content.length=16`. DB 직접 카운트(`APPROVED` ∩ `evnt_type_cd='EV02000201'` ∩ `orgnl_raw_sn IS NULL`) = 16 과 정확히 일치 — 필터 조합이 전부 DB 조건으로 내려감을 실측 확인 |
| TC-VIDEO-014 | PASS | 파생영상 rawSn=78(orgnl_raw_sn=26) 등 24건(95-71) 존재 확인. `?cctvNameKeyword=78` → `total=0`(검색으로도 안 잡힘). 필터 조합(TC-013 등) 전체에서 파생 0건 혼입 확인 |
| TC-VIDEO-015 | PASS | rawSn=39(`sht_dt IS NULL`, `reg_dt`=2026-08-01 10:18:52 존재) 응답: `capturedAt=None`, `regDt=2026-08-01T10:18:52...` — REG_DT 폴백 없음 확인. 동일 rawSn을 `from/to=2026-08-01` 필터에 포함해도 `total=0`(기간 필터에도 안 잡힘) 확인 |
| TC-VIDEO-016 | PASS | DB: `ls_raw_data_status.data_stts_cd='APPROVED'` 22건 중 1건(rawSn=18)이 파생(`orgnl_raw_sn=4`). API `?reviewStatusCd=APPROVED` → `total=21`(22-1) — LEFT JOIN + 필터 조건 조합이 구 INNER JOIN과 동치임을 실측(파생 제외까지 함께 반영) |
| TC-VIDEO-017 | PASS | `?sort=reviewCompletedAt,desc`(reviewStatusCd 미지정) → **HTTP 200**, `total=71`(정상 목록, 에러 아님) — lenient 200 + 기본 정렬 폴백 확인(strict 400 아님) |
| TC-VIDEO-018 | PASS | 위 모든 날짜 미지정 요청(TC-001 등)이 전부 HTTP 200으로 정상 응답 — `$n IS NULL` 타입 추론 실패(PostgreSQL 확장 프로토콜 에러) 없이 플래그 방식이 실동작함을 간접 확인 |

## 카탈로그 정정 (담당 범위 내, Edit 적용 완료)

1. TC-BATCH-180: `YoloAutolabelStep.java:155-158` → `166-170`으로 정정(근거 드리프트, execute 애노테이션 실제 위치)
2. TC-BATCH-183: `VlmTimeseriesStep.java:205-227` → `219-227`로 정정
3. TC-VIDEO-006: `EventTypeService.java:157-180` → `175-198`로 정정 — **구 라인은 인접한 다른 메서드(`categoryKeyOf`)를 가리키고 있었음**(단순 줄밀림이 아니라 잘못된 메서드 지목, 정정 우선순위 높음)

경미하여 미정정(사유: 라인 오차 5줄 이내이거나 doc-comment vs code 시작줄 차이 수준, 결론에 영향 없음): TC-BATCH-181(139-150 vs 실제 146-150), TC-BATCH-194(AutoLabelBatchPersister.java:55 vs 실제 65-67), TC-BATCH-203(76-84,111-114 vs 실제 77-85,112-117).

## 이전 회차 이슈 해소 여부

`docs/검증결과/2026-08-01/1차/ISSUES.md`·`2026-08-02/2차/ISSUES.md`를 grep한 결과, **B-15/B-16/B-17/B-18에 대응하는 이전 회차 B-ISSUE 없음** — B-15~17은 이번 회차 이전에 이미 신규 섹션으로 추가돼 있었으나 기존 이슈 목록에 해당 TC-ID 매핑이 없었고(1차/2차 시점 카탈로그에 존재하지 않았거나 미검증), B-18은 2026-08-03 신설 섹션(과제 지시대로 신규 검증 취급). 따라서 "해소 여부 대조" 대상 자체가 없음 — 전건 신규 판정.

## 종합 판정 집계

| 섹션 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| B-15 | 11 | 11 | 0 | 0 | 0 | 0 |
| B-16 | 6 | 6 | 0 | 0 | 0 | 0 |
| B-17 | 8 | 8 | 0 | 0 | 0 | 0 |
| B-18 | 18 | 18 | 0 | 0 | 0 | 0 |
| **합계** | **43** | **43** | **0** | **0** | **0** | **0** |

결함(B-ISSUE) 신규 발견 없음. B-ISSUE-121 이슈번호는 사용되지 않음(발생 이슈 0건).

---
