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
