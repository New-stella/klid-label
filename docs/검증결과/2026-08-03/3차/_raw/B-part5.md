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
