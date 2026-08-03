# 2차 재검증 — B / D+E 클러스터 타깃 (1차 CRITICAL·HIGH 수정 재검증)

- 검증일: 2026-08-02
- 대상 워킹트리: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801` (branch `qa-0801`, HEAD `56d30478`)
- 검증 방식: **정적 코드 대조 + 기존 테스트 결과 증거(XML) 참조** (테스트/빌드 직접 실행 없음, 코드 수정 없음)

## 0. 환경 사실관계 (BLOCKED 판정 근거)

| 항목 | 실측 |
|---|---|
| docker `klid-postgres` 최신 적용 마이그레이션 | **V158** (`installed_rank=153`, 2026-08-01 23:10) |
| 워킹트리 마이그레이션 최신 | **V160** (`V159`·`V160` 은 **untracked 신규 파일**, 미커밋) |
| docker `v_completed_video` 정의 | 구 정의 — `ex.export_stts_cd::text = 'SUCCEEDED'::text` (PARTIAL 미노출) |
| 수정 코드 커밋 상태 | 전부 **워킹트리 미커밋** (`git status` 79건 변경 — 5-Phase 수정분) |

→ **docker 이미지/DB는 수정 미반영 상태**다. 따라서 실행 중인 컨테이너 대상 실동작 검증(HTTP 요청·DB 뷰 조회)은 **BLOCKED**.
다만 아래 "실행 증거" 항목의 테스트 결과 XML 이 **2026-08-02 15:55 타임스탬프**로 574건 존재하며,
Testcontainers 기반 IT(`DatamartViewSlimIT` = `@SpringBootTest` + PostgresContainer, Flyway 전량 적용)가
**V160 이 실제 적용된 실 PostgreSQL 에서** 통과한 기록이 있어 뷰 축은 **실동작 등가 검증**으로 인정한다.

### 실행 증거 (build/test-results/test — 전부 failures=0 errors=0, mtime 2026-08-02 15:55)

| 테스트 클래스 | tests | 결과 |
|---|---:|---|
| `MarkingSkipTxServiceTest` | 4 | PASS |
| `MarkingBatchBridgeTest` | 20 | PASS |
| `BatchReprocessClaimConcurrencyIT` | 3 | PASS |
| `VideoStreamServiceTest` | 44 | PASS |
| `DatasetExportNotifyGateTest` | 8 | PASS |
| `ControlNotifyWebClientAuthHeaderTest` | 4 | PASS |
| `AsyncDatasetExportRunnerTest` | 21 | PASS |
| `DatamartViewSlimIT` | 17 | PASS |
| `FileServingLinkFollowGuardTest` | 1 | PASS |

---

## 1. TC-BATCH-057/062 인접 — 마킹 배치 skip 시 영구 고착 (B-ISSUE-41)

**판정: PASS**

### 정적 대조

- `marking/entity/LsMarking.java`
  - `STATUS_SKIPPED = "SKIPPED"` 신설 (종결 상태). `VLM_FAILED` 재사용을 명시적으로 배제(재위탁 대상 오독 방지) — 판단 타당.
  - `markSkipped()` 전이 메서드 추가. **`PENDING` 한정 가드** — 그 외 상태면 `false` no-op → 진행 중 `VLM_REQUESTED` 사이클/종결 사실을 덮지 않음.
  - `ACTIVE_STATUSES = List.of(STATUS_PENDING, STATUS_VLM_REQUESTED)` 불변 → `SKIPPED` 는 자동으로 활성 집합에서 제외.
- `marking/service/MarkingSkipTxService.java` (신규)
  - `@Transactional(controlTransactionManager, REQUIRES_NEW)` 별도 빈. AFTER_COMMIT 컨텍스트(활성 tx 없음)에서 dirty checking 이 안 되고 자기호출은 프록시 우회라는 점을 정확히 회피.
  - `markingSn == null` / 행 부재 / 비-PENDING → 조용한 no-op. 종결 실패가 마킹 API 201 응답에 영향 없음.
- `marking/listener/MarkingBatchBridge.java`
  - **skip 분기 4종 전부** `skip(rawSn, markingSn, reason)` 경유로 통일:
    `REASON_VIDEO_NOT_FOUND` / `REASON_STAGE_ALREADY_RUN` / `REASON_NOT_DEIDENTIFIED` / `REASON_ALREADY_CLAIMED`.
    누락 분기 없음(diff 상 구 `MarkingBatchTriggerReport.skipped(...)` 직접 호출은 전부 치환됨).
  - `MarkingCompletedEvent(Long rawSn, Long markingSn)` — 이벤트에 `markingSn` 이 실재하므로 종결 대상 식별 가능.
- 정합성: `V142` 부분 유니크 인덱스 술어 `WHERE STTS_CD IN ('PENDING','VLM_REQUESTED')` 와 `ACTIVE_STATUSES` 가 **정확히 일치** → `SKIPPED` 종결 시 인덱스 활성 집합에서 빠져 재마킹 409 해소.
- `V159__terminate_orphan_skipped_markings.sql` — 기존 고아 `PENDING` 마킹을 검수소유 상태(`PENDING/IN_REVIEW/APPROVED/REJECTED`) 영상에 한해 `SKIPPED` 로 백필. 기존 고착 데이터 회수 경로 존재.

### 테스트 커버리지

- `MarkingBatchBridgeTest` — skip 4종 전부 종결 확인 + **"정상 트리거시에는 마킹을 종결하지 않는다"** 과잉차단 회귀 가드 포함(20건 PASS).
- `MarkingSkipTxServiceTest` — PENDING 종결 / VLM_REQUESTED 미덮음 / 종결상태 미역행 / null·행부재 no-op (4건 PASS).

### 잔여 리스크

- `V159` 는 docker DB **미적용**(V158까지). 운영/개발 DB에 기존 고아 마킹이 남아 있으면 배포 시점까지 409 잠금 지속. 배포 시 마이그레이션 적용으로 해소 예정.

---

## 2. TC-BATCH-153/154 — 재처리 원자 클레임 (B-ISSUE-101, CWE-362)

**판정: PASS**

### 정적 대조

- `batch/status/BatchTransitionService.tryClaimReprocessFromFailed`
  - RAW 조건부 UPDATE(①)가 0행일 때 **곧바로 작업상태 폴백(②)하지 않고** `videoRepository.findDataSttsCdByRawSn(rawSn)` 로 **원인을 재판정**:
    - `PROCESSING` → 타 호출자 선점 → `false` (폴백 금지)
    - `FAILED` → UPDATE 0행인데 여전히 FAILED = 모순(재전이 레이스) → **fail-closed `false`**
    - 그 외(row 부재·COMPLETED·MARKING_READY) → "RAW 는 애초에 클레임 대상 아님" 예외 형상에 한해 ② 허용
  - 결함의 근본 원인("①이 0행"의 지배적 원인이 *남이 선점* 인데 *RAW 는 대상 아님* 으로 오독)을 정확히 겨냥.
  - 두 컬럼이 **함께 FAILED** 인 정상 실패 형상에서 A=RAW / B=작업상태를 각각 선점해 둘 다 `true` 를 받던 경로가 차단됨.
- `video/repository/VideoRepository.findDataSttsCdByRawSn` — `@Query("SELECT r.dataSttsCd FROM LsDataRaw r WHERE r.rawSn = :rawSn")` 단일 컬럼 projection. 존재 확인.
- 격리 정합: 메서드는 `REQUIRES_NEW` 로 즉시 커밋되며, READ COMMITTED 하에서 조건부 UPDATE 가 경쟁자 커밋까지 row lock 대기 → 해제 후 0행 → 재조회 시 `PROCESSING` 관측. 논리 성립.
- 로그는 고정 문자열 + `rawSn` 만 출력(CWE-117/209 준수).

### 테스트 커버리지

- `BatchReprocessClaimConcurrencyIT` (3건 PASS)
  - "두 컬럼이 함께 FAILED 인 정상실패 영상에 **동시 5요청 → 정확히 1건만 클레임**" ← 결함 재현 시나리오 직격
  - "작업상태 행이 없는 영상에 동시 5요청 → 정확히 1건" (무회귀 대조군)
  - "작업상태만 FAILED 인 예외형상은 여전히 폴백으로 1건 클레임" ← **폴백을 죽이지 않았음** 회귀 가드

---

## 3. TC-STREAM-B04/B20 — 스트리밍 심링크 PII 유출 (B-ISSUE-81, CWE-59/367/359)

**판정: PASS**

### 정적 대조

- `common/storage/VideoArtifactRootResolver`
  - `resolveRealPathUnder(target, base)` 신설 — 기존 `verifyRealPathUnder` 와 **동일 판정기**이되 통과한 **실경로를 반환**. `verifyRealPathUnder` 는 이 메서드에 위임 → 판정 로직 단일화(복제 없음).
- `video/service/VideoStreamService`
  - `resolveSafe(...)` 반환 타입을 `Path` → `VerifiedDeidFile(realPath, base)` 로 변경. lexical `startsWith` 통과 후 **`resolveRealPathUnder` 실경로 검증**을 추가하고, 2-way allowlist 특성상 한 base 실패 시 다음 후보 시도(전부 실패 시 `NOT_FOUND` 정규화).
  - **`UrlResource` 제거 → `NoFollowFileResource`** (신규 `AbstractResource` 구현). `getInputStream()` 이 `FrameImageService.openNoFollow(path)` — 프레임 4경로와 **동일 단일 헬퍼** 재사용. 심링크면 open 자체 실패 → **바이트 0 유출(fail-closed)**.
  - 존재 확인·크기 산출을 `Files.readAttributes(..., NOFOLLOW_LINKS)` 로 통일 → **판정 대상 == 응답 대상**.
  - **캐시 TTL 유출창 폐쇄**: `StreamMeta` 에 `base` 를 함께 실어, 캐시 히트 경로에서도 `revalidateOpenTarget()` 이 **매 요청** ①실경로가 base 하위인가 ②캐시 판정 당시 실경로와 동일한가 ③NOFOLLOW stat 이 정규 파일인가 **3중 판정**. 실패 시 `NOT_FOUND` (경로 원문 미노출, CWE-209).
    - ②가 핵심 — `STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH` 형상에서는 **같은 base 안**의 원본을 가리키는 링크가 가능하므로 ①만으로 불충분. 이 점을 정확히 인지·처리함.
  - 재검증은 캐시된 값만 사용 → **DB 재조회 없음**(성능 회귀 없음).
- 응답 코드: 수정 전 원본 **200 서빙** → 수정 후 **404(NOT_FOUND)**. 이 엔드포인트의 기존 규약(비식별 무효 시 404)과 일치하며 412 로 갈리지 않아 오라클(CWE-209)도 만들지 않음.

### 테스트 커버리지 (`VideoStreamServiceTest` 44건 PASS)

- `비식별파일이_원본영상_심링크면_NOT_FOUND` (**B04 직격**)
- `co_locate_비식별파일이_원본영상_심링크여도_NOT_FOUND` — 벤더(KPST) 공유 마운트 신뢰경계
- `중간_디렉터리_세그먼트가_심링크로_base밖을_가리켜도_NOT_FOUND`
- `★캐시히트후_비식별파일이_원본심링크로_치환되면_NOT_FOUND` — **TTL 5분 유출창(B20) 직격**
- `★캐시히트후_Range요청도_심링크_치환되면_차단된다` — 시크 재요청 경로
- `스트리밍_Resource는_심링크를_따라_열지_않는다` — openNoFollow 규약
- 무회귀: `캐시히트_정상파일은_그대로_200이고_본문은_비식별본이다`, `206_부분응답_본문이_비식별본의_해당구간과_동일하다`, `정상_비식별파일은_심링크가드_도입후에도_그대로_200`
- `FileServingLinkFollowGuardTest` (아키텍처 가드, 1건 PASS) — 서빙 경로의 link-follow 재구현 방지

---

## 4. TC-NOTIFY-040 / 001 / 041 — export FAILED 인데 통지 발송 (D-ISSUE-61, CRITICAL)

**판정: PASS**

### 정적 대조

- `dataset/export/DatasetExportOutcome.java` (신규 enum, 7종 + `notifiable()`)
  - 통지 O: `COMPLETED` · `PARTIAL` · `IDEMPOTENT_SKIP`
  - 통지 X: `FAILED` · `VERSION_EXHAUSTED` · `NO_INPUT` · `DEIDENT_BLOCKED`
  - `metricTag()` 가 기존 `dataset.export.result{outcome}` 태그 문자열 7종을 **그대로 승계** → 관측 대시보드 계약 불변 + **관측과 통지 판정이 어긋날 수 없음**.
- `DatasetExportService.export(long, boolean)` 시그니처 `void` → `DatasetExportOutcome`.
  - **무예외 실패 4경로 전부** enum 반환으로 치환 확인:
    ① `loadPreparation` empty → `NO_INPUT`
    ② `resolveVideoRoot` 거부(`markBaseRejected`) → `FAILED`
    ③ `insertWithRetry == null` (버전 채번 소진) → `VERSION_EXHAUSTED`
    ④ 산출물 0건(`markFailed`) → `FAILED`
    (추가) 쓰기 중 런타임 예외 → `FAILED`, 신고 게이트 → `DEIDENT_BLOCKED`
  - fail-secure 초기값 `outcome = FAILED` 유지 → 판정 누락 시에도 통지 보류.
- `AsyncDatasetExportRunner.doExport`
  - `if (outcome == null || !outcome.notifiable()) { WARN; return false; }` — **`null` 도 fail-closed**.
  - 통지 판정이 `notifiable()` **단일 근거**로 수렴.
- 호출자 전수 확인: `exportService.export(...)` 호출부는 `AsyncDatasetExportRunner:136` **1곳뿐** → 판정 누락 경로 없음.
- 회수 경로 정합: `DatasetExportFailureRecoverer` 는 `runner.runApprovalAsync(rawSn)` 로 재산출하며 성공 시 완료 이벤트 재발행 → 통지는 **유실이 아니라 성공 시점으로 지연** (CLAUDE.md 구속 정책과 일치).

### 테스트 커버리지 (`DatasetExportNotifyGateTest` 8건 PASS)

- 무예외 실패 4경로 각각 **완료 통지 미발행** 확인 (산출물0건 / base거부 / 버전채번소진 / NO_INPUT)
- 승인 후 **수정 통지(TASK_MODIFIED) 콜백도 미실행** 확인
- 과잉차단 회귀 방지 3건: 정상 완료 / PARTIAL / 멱등 skip → 통지 **발행**
- `AsyncDatasetExportRunnerTest` 21건 PASS (기존 계약 무회귀)

---

## 5. TC-NOTIFY-051 — 통지 인증 헤더 누락 (D-ISSUE-62)

**판정: PASS**

### 정적 대조

- `common/config/WebClientConfig.controlNotifyWebClient`
  - `CONTROL_NOTIFY_TOKEN_HEADER = "x-access-token"` 상수 + `builder.defaultHeader(CONTROL_NOTIFY_TOKEN_HEADER, token.trim())` 로 **실제 부착 확인**.
  - 토큰 소스: `@Value("${authoring.control-notify.token:}")` ← 환경변수. **코드/yml 평문 상수 없음**(CWE-798).
  - 로그에 **토큰 값 미출력** — 존재 여부/`tokenLength` 만 (CWE-532 준수).
  - `enabled=true` && 토큰 공백 → 기동 WARN(차단 아님). local 목서버 호환 유지 근거 타당.
  - **추가 하드닝**: `http://` 평문 엔드포인트에 토큰 설정 시 CWE-319 WARN.
  - `trim()` 적용 — 환경변수 개행/공백 혼입 방어.

### 테스트 커버리지 (`ControlNotifyWebClientAuthHeaderTest` 4건 PASS)

- 헤더 **실제 요청 부착** 검증(설정만 확인이 아니라 실제 요청 관측) / 공백 trim / 빈 토큰 시 헤더 생략 / null 토큰 기동 무실패

### 잔여 리스크 (운영 배포 체크 항목)

- `CONTROL_NOTIFY_TOKEN` 환경변수가 dev/stg/prd 배포 형상에 **실제로 주입되는지**는 이 검증 범위 밖(코드는 정상). 미주입 시 기동 WARN 만 나고 실환경 401 이 재현되므로 **배포 전 확인 필요**.

---

## 6. TC-EXPORT-005 / 019 — PARTIAL export 뷰 배제 (E-ISSUE-81)

**판정: PASS** (실동작은 Testcontainers 실 PostgreSQL 기준. docker `klid-postgres` 상 검증은 **BLOCKED — V160 미적용**)

### 실측 (docker `klid-postgres`)

```
ex.export_stts_cd::text = 'SUCCEEDED'::text   ← 구 정의 유지, EXPORT_STTS_CD 출력 컬럼 없음
```
→ V160 미적용 상태 확인. **컨테이너 기준 실동작 검증 BLOCKED.**

### 정적 대조 — `V160__include_partial_export_in_completed_video_view.sql`

- LATERAL 조인 조건 `ex.EXPORT_STTS_CD IN ('SUCCEEDED', 'PARTIAL')` 로 확장.
- 출력 **맨 끝에** `e.EXPORT_STTS_CD` 1컬럼 추가 — 별칭 없이 원천 컬럼명·타입(`VARCHAR(20)` 코드값 표준도메인)·길이 100% 승계 → **신규 물리명 생성 없음**(표준용어·표준도메인 규칙 준수, V138 동일 방침).
- `ORDER BY ex.EXPORT_VER_NO DESC LIMIT 1` 유지 → **영상 1건 = 1 row 불변**.
- `CREATE OR REPLACE VIEW` + 기존 컬럼 이름·순서·타입 보존 + 끝에만 append → REPLACE 안전·재실행 멱등.
- 회수기(FAILED 앵커)는 **의도적으로 미변경** — PARTIAL 을 재시도 앵커에 넣으면 원천 이미지 영구 부재 영상이 max-attempts 소진까지 매 tick 새 버전 폴더 + 이미지 2벌을 재복사(디스크 누적). 판단 타당.
- 세 판정(뷰 / 통지 `DatasetExportOutcome.PARTIAL.notifiable()=true` / 멱등 baseline `SUCCEEDED+PARTIAL`)이 **PARTIAL 을 동일 취급**하도록 일치됨.

### 테스트 커버리지 (`DatamartViewSlimIT` 17건 PASS — `@SpringBootTest` + PostgresContainer, Flyway V160 전량 적용된 실 PostgreSQL)

- `V160_최초export가_PARTIAL이어도_EXPORT_PATH_NM이_노출된다` — `EXPORT_PATH_NM`/`FRAME_CNT` 채워짐 + `EXPORT_STTS_CD='PARTIAL'` 확인
- `V160_최신이_PARTIAL이면_구_SUCCEEDED가_아니라_최신_PARTIAL을_노출한다` — v1 SUCCEEDED(100) + v2 PARTIAL(98) → 1 row, `frame_cnt=98`
- `V160_FAILED_PENDING은_여전히_뷰에서_배제된다` — v2 FAILED · v3 PENDING 은 미노출, v1 SUCCEEDED(100) 유지 (과잉 개방 회귀 가드)

### 잔여 리스크

- docker 개발 DB 및 배포 대상 DB에 **V159/V160 미적용**. 배포 시 Flyway 적용 필요.

---

## 판정 요약

| TC-ID | 이슈 | 판정 | 근거 |
|---|---|:---:|---|
| TC-BATCH-057/062 인접 | B-ISSUE-41 마킹 영구고착 | **PASS** | `STATUS_SKIPPED`+`markSkipped()` PENDING 한정 · skip 4분기 전부 종결 배선 · V142 인덱스 술어 정합 · V159 백필 · 테스트 24건 PASS |
| TC-BATCH-153/154 | B-ISSUE-101 재처리 원자클레임 | **PASS** | 0행 원인 재판정(PROCESSING/FAILED → 폴백 금지, fail-closed) · `findDataSttsCdByRawSn` 확인 · 동시 5요청 IT 3건 PASS |
| TC-STREAM-B04 | B-ISSUE-81 심링크 PII유출 | **PASS** | `resolveRealPathUnder` + `NoFollowFileResource`(openNoFollow) + NOFOLLOW stat → 원본 200 → **404** 전환. 심링크 3종 테스트 PASS |
| TC-STREAM-B20 | B-ISSUE-81 캐시 TTL 유출창 | **PASS** | `revalidateOpenTarget` 매 요청 3중 재검증(base 하위/실경로 동일/정규파일), DB 재조회 없음. 캐시히트 치환 200·206 테스트 PASS |
| TC-NOTIFY-040 | D-ISSUE-61 export 실패 통지 | **PASS** | `DatasetExportOutcome.notifiable()` 단일 판정 · 무예외 실패 4경로 전부 enum 반환 · null fail-closed · 호출부 1곳 전수 |
| TC-NOTIFY-001 | D-ISSUE-61 (승인 TASK_COMPLETED) | **PASS** | 정상 완료/PARTIAL/멱등skip 통지 유지 확인(과잉차단 회귀 없음) |
| TC-NOTIFY-041 | D-ISSUE-61 (수정 TASK_MODIFIED) | **PASS** | 실패 시 수정 통지 콜백도 미실행 테스트 PASS |
| TC-NOTIFY-051 | D-ISSUE-62 인증헤더 누락 | **PASS** | `x-access-token` defaultHeader 부착 + 실제 요청 관측 테스트 PASS · 토큰 값 미로깅 · CWE-319 WARN 추가 |
| TC-EXPORT-005 | E-ISSUE-81 PARTIAL 뷰 배제 | **PASS**<br/>(컨테이너 실동작 BLOCKED) | V160 `IN ('SUCCEEDED','PARTIAL')` + `EXPORT_STTS_CD` 노출. Testcontainers IT PASS. docker DB 는 V158 로 미적용 |
| TC-EXPORT-019 | E-ISSUE-81 최신 PARTIAL 선택 | **PASS**<br/>(컨테이너 실동작 BLOCKED) | 최신 버전 우선 + FAILED/PENDING 배제 유지, IT PASS. docker DB 미적용 |

**총평: 재검증 대상 10개 TC 전부 PASS.** FAIL 없음.
BLOCKED 은 "코드 결함" 이 아니라 **환경 미반영**(docker DB V158 · 수정분 미커밋)에 기인하며, Testcontainers 실 PostgreSQL 검증으로 대체 충족했다.

## 후속 조치 (배포 전 필수)

1. 수정분 커밋 + docker 이미지 재빌드 → **V159/V160 Flyway 적용** 후 컨테이너 실동작 스모크(뷰 `EXPORT_STTS_CD` 컬럼 존재 + PARTIAL 노출).
2. `CONTROL_NOTIFY_TOKEN` 환경변수 dev/stg/prd 주입 확인 (미주입 시 기동 WARN 만 나고 실환경 401 재현).
3. 관제팀 통보: `V_COMPLETED_VIDEO` 에 `EXPORT_STTS_CD` 1컬럼 추가(하위호환) + 최신 산출이 PARTIAL 인 영상의 `EXPORT_PATH_NM`/`FRAME_CNT` 값이 구 SUCCEEDED/NULL → 최신 PARTIAL 로 변경됨.
