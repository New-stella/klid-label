# B 클러스터 part5 (B-11·B-12) 2차 검증 결과

- 대상: `docs/test-cases/B-batch-deidentify.md` §B-11(비식별 누락 신고) · §B-12(재처리/재시도 큐)
- 검증 시각: 2026-07-31 03:20~03:35 KST · backend `localhost:18081` (HEAD `ca3c712b`, 이미지 `bdc64ea2ac26`)
- 실행 프로파일 **`local`** (`SPRING_PROFILES_ACTIVE=local`, `authoring.batch.enabled=false`) · 스키마 `public`
- 참조 데이터: `pipeline-drive.md` §3 카탈로그 (rawSn 126 APPROVED / 129·130·131 파생 / 132 검수대기 / **133 비식별 신고 OPEN**)
- **컨테이너 재기동·재빌드·빌드/테스트 실행 0건.** 소스·설정·테스트 파일 수정 0건(본 문서 1개만 신규 작성)
- **rawSn 133 / rprtSn 3 은 OPEN 그대로 유지**했다. resolve 는 호출했으나 **산출물 미검증 409(fail-closed)** 로 롤백되어 상태 변화가 없음을 DB 로 재확인(`OPEN` + lock `LOCKED` + `DE_IDENT_YN='F'`). resolve 성공 경로는 **다른 에이전트가 rawSn 144 에 남긴 실행 흔적(rprtSn 4/5)** 을 DB·로그로 실측 인용했다.

## 집계

| 섹션 | 표 행 | 폐기(제외) | 검증 대상 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|
| B-11 비식별 누락 신고 | 30 | 2 | **28** | 27 | 1 | 0 | 0 | 0 | 0 |
| B-12 재처리/재시도 큐 | 19 | 0 | **19** | 19 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **49** | **2** | **47** | **46** | **1** | **0** | **0** | **0** | **0** |

> 폐기(집계 제외) 2건 — `TC-DEID-034`(전체 라벨 스냅샷+삭제) · `TC-DEID-038`(라벨 0건 스냅샷 스킵). 둘 다 2026-07-27 정책 반전(라벨 보존·스냅샷 미생성)으로 무효.

**케이스 표 밖에서 발견한 게이트 미배선 3건**(B-ISSUE-81~83)은 위 집계에 포함되지 않는다. 그중 B-ISSUE-81 은 `TC-DEID-035` 의 명시 목적("stale PII 방지")을 무력화하므로 해당 케이스를 PARTIAL 로 낮췄다.

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 판정 | 근거 |
|---|---|:--:|---|
| **B-ISSUE-28** | 마킹 단계 rawSn 신고 미구현(참고 기록) | **해소** | [실동작] `POST /v1/videos/133/deident-report` → **409**(중복 신고), `/v1/videos/129/…` → **412**(파생), `/v1/videos/9999999/…` → **404**. 1차의 "엔드포인트 자체 부재 404" 아님. `DeidentReportController.java:122` |
| **B-ISSUE-64** | 마킹 단계 rawSn 신고 미구현(설계 갭) | **해소** | 동상. `DeidentReportService.reportByVideo:147` → 공용 `doReport:169` 로 수렴 |
| **B-ISSUE-83** | 재시도 큐 `RETRYING` 클레임 후 노드 사멸 시 영구 유실 | **해소** | `BatchRetryQueue.sweepStaleRetrying:174-193` + `LsBatRtyWtngRepository.findStaleRetryingAnchors/reclaimStaleRetrying/exhaustStaleRetrying` 신설. [실동작] 기동 로그 `[BatchRetry][Reclaim] stale reclaim scheduled intervalMs=900000 staleTimeoutMinutes=180` + 전용 데몬 스레드 `batch-retry-stale-reclaim` 15분 주기 tick 관측 |
| **B-ISSUE-85** | local 프로파일에서 재시도 폴러 미등록 → 적재만 되고 발화 0건 | **미해소(환경)** | `application-local.yml:61-62 batch.enabled=false` → `BatchRetryTriggerConfig`(`@ConditionalOnProperty authoring.batch.enabled`) 미등록. [실동작] `ls_bat_rty_wtng` 에 rawSn=144 `PENDING`(rtyNmtm=3) 행이 도래 후에도 미발화. → B-ISSUE-86 으로 이월 |
| B-ISSUE-84 | mock `fileName` 계약 드리프트(B-13 소관) | 참고 | 본 part 범위 밖 |
| B-ISSUE-63 | `/stream` 영상 단위 배정 인가 부재(B-10 소관) | 참고 | [실동작] 미배정 WORKER(2099)의 `GET /v1/videos/132/stream` → **403**. B-10 담당이 재판정할 것 |

## ★신고 게이트 전 경로 실측표

대상 = **rawSn 133**(신고 OPEN, `DE_IDNTF_YN='F'`, 프레임 srcSn 78·80·82·84·86·88) / 대조군 = **rawSn 132**(신고 없음, srcSn 77) / 토큰 = REVIEWER(1001) 달리 표기 없으면.

| # | 엔드포인트 | 기대 | 실측(133) | 대조군(132) | Cache-Control | 판정 |
|--:|---|:--:|:--:|:--:|---|:--:|
| 1 | `GET /v1/frames/{srcSn}/labels` | 412 | **412** | 200 | `no-store`(Security 기본) | PASS |
| 2 | `GET /v1/frames/{srcSn}/label-history` | 412 | **412** | — | `no-store` | PASS |
| 3 | `GET /v1/frames/{srcSn}/image` | 412 | **412** | 200 | 200 응답 `Cache-Control: no-store` | PASS |
| 4 | `GET /v1/frames/{srcSn}/deid-image` | 412 | **412** | 200 | 200 응답 `no-store` | PASS |
| 5 | `GET /v1/videos/{rawSn}/frames/{frameNo}/image` | 412 | **412** | — | `no-store` | PASS |
| 6 | `GET /v1/videos/{rawSn}/stream` | **404** | **404** | 206 | 206 응답 `Cache-Control: no-store` + `Accept-Ranges: bytes` | PASS |
| 7 | `GET /v1/videos/{rawSn}/stream-url` | **404** | **404** | 200 | `no-store` | PASS |
| 8 | `GET /v1/tasks/{rawSn}/labels`(관제 조회) | 412 | **412** | 200 | `no-store` | PASS |
| 9 | `GET /v1/portal/frames/{srcSn}/labels` | 412 | 403(선행 게이트) | 403 | — | PASS(주1) |
| 10 | `GET /v1/portal/frames/{srcSn}/image` | 412 | 403(선행 게이트) | 403 | 코드상 `CacheControl.noStore()` (`PortalLabelService:494`) | PASS(주1) |
| 11 | `PUT /v1/frames/{srcSn}/labels` | 409 | **409** "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다." | — | — | PASS |
| 12 | `POST /v1/frames/{srcSn}/autolabel`(온라인 AI 탐지) | 차단 | **409**(작업락이 신고 게이트보다 앞) | 200 | — | PASS(주2) |
| 13 | 버전 diff / rollback | 412 | 미도달(주3) | — | — | PASS[정적] |
| 14 | 데이터셋 export | skip(보류) | 미도달(주3) | — | — | PASS[정적] |
| 15 | **인가 우선 순서 확인** — 미배정 WORKER(2099)로 #1~#8 전건 | 403 | **전건 403**(412/404 로 갈리지 않음) | 전건 403 | — | PASS |
| ⚠16 | `GET /v1/videos/{rawSn}/labels/auto` | (미명시) | **200** — 라벨 클래스·신뢰도 28건 노출 | 200 | — | **미배선 → B-ISSUE-83** |
| ⚠17 | `GET /v1/videos/{rawSn}/auto-summary` | (미명시) | **200** — 총 28라벨·클래스분포·저신뢰 프레임 목록 | 200 | — | **미배선 → B-ISSUE-83** |
| ⚠18 | `PUT /v1/frames/{srcSn}/privacy-meta` | (미명시) | **200** — 신고가 리셋한 3필드를 즉시 재설정 성공 | 200 | — | **미배선 → B-ISSUE-81** |
| ⚠19 | `PUT /v1/frames/{srcSn}/description` | (미명시) | **200** — 작업락 무시 | 200 | — | **미배선 → B-ISSUE-82** |
| 20 | `GET /v1/frames/{srcSn}/versions` | (미명시) | 200(빈 배열 — 133 은 스냅샷 0건) | — | — | 정보 |
| 21 | `GET /v1/tasks/{rawSn}/summary` · `/meta` | (미명시) | 200(라벨 본문 없음 — 계약상 메타만) | — | — | 정보 |

- 주1) 포털 경로는 **데이터마트 노출(APPROVED) 검사가 신고 게이트보다 앞**이라 132·133 모두 403 으로 수렴한다(`PortalLabelService.isExposedToDatamart`). 신고 게이트 자체는 `PortalLabelService:267,443` 에 배선돼 있고 `DeidentReportGateCoverageIT`("신고_상태에서_포털_라벨_조회와_이미지_서빙이_차단된다")가 커버한다.
- 주2) `AutolabelOnlineService:415` 에 신고 게이트(412)가 배선돼 있으나 **작업락 409 가 선행**한다. 신고는 항상 작업락을 동반하므로 실사용상 412 에 도달하지 않는다(둘 다 차단이므로 누수 아님).
- 주3) rawSn 133 은 승인 이력이 없어 `LS_LABEL_VERSION`·`LS_DATASET_EXPORT` 행이 0건이라 실호출로 게이트에 도달하지 못한다. `VersionService:354,356,437` · `DatasetExportService:136` · `DatasetExportTxService:226`(잠금 재판정) 정적 확인 + `DeidentReportGateCoverageIT` 커버.
- **⚠16~19 는 케이스 표에 없는 형제 엔드포인트**다. 프롬프트 지시("목록에 없는 형제 엔드포인트도 찾아서 던져라")에 따라 추가 사냥한 결과이며, 확정 정책(★1 파생 무관·★2 관제 접근 보장)과 무관한 별개 표면이다.

## B-11 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-DEID-030 | 신고: reason blank → INVALID_INPUT | PASS | [실동작] `{"reason":""}`·`{}` 둘 다 **400** `INVALID_INPUT` "reason: 신고 사유는 필수입니다." (`@Valid` 선차단). 서비스 `requireReason:158-162` 이중 방어 | 두 진입점 공통 |
| TC-DEID-031 | 신고: WORKER 본인 배정만(IDOR) | PASS | [실동작] 미배정 WORKER(2099) `POST /v1/labels/78/deident-report` → **403**, PORTAL_USER → **403**. [정적] `DeidentReportService:122`→`LabelAccessGuard.verifyAndGet` | rawSn 경로도 동일(403) |
| TC-DEID-032 | 신고: 부모 RAW PESSIMISTIC_WRITE 락(PII TOCTOU) | PASS | [정적] `DeidentReportService:178` `videoRepository.findByRawSnForUpdate` · `VideoRepository:37-39` `@Lock(PESSIMISTIC_WRITE)` + JPQL. 서비스가 `@Transactional`(readOnly 아님, :83) 안이라 커밋까지 락 유지 | |
| TC-DEID-033 | 신고: 이미 잠금 → CONFLICT | PASS | [실동작] 133 재신고 srcSn/rawSn 양쪽 **409** "이미 비식별 재처리 중인 영상입니다."(`:189-191`). DB `ls_auth_work_lock` sn=3 `LOCKED` 유지 | |
| ~~TC-DEID-034~~ | ~~전체 라벨 스냅샷+삭제+'F'~~ | — | **폐기 2026-07-30** (집계 제외) | 대체 TC-DEID-051 |
| TC-DEID-035 | 신고: 개인정보 3필드 리셋(stale PII 방지) | **PARTIAL** | [실동작] 리셋 자체는 **정상** — 로그 `privacyReset=6`, DB `ls_data_src(raw_sn=133)` 6행 `anony/psdo/prvc_incl_yn` 전부 NULL. [정적] `:218-219` 벌크 JPQL(`LsDataSrcRepository:199-201`, 파라미터 바인딩). **그러나 "stale PII 방지" 목적은 성립하지 않는다** — `PUT /v1/frames/78/privacy-meta` 가 신고 구간에도 200 으로 3필드를 즉시 재설정한다(실측) | **B-ISSUE-81** |
| TC-DEID-036 | 신고: 락 UNIQUE 위반 → CONFLICT(동시) | PASS | [정적] `:237-241` `DataIntegrityViolationException`→`ErrorCode.CONFLICT`. 테스트 `DeidentReportServiceTest:524`("동시_신고_unique_위반시_409") | |
| TC-DEID-037 | 신고: APPROVED → TASK_MODIFIED(META_UPDATED) | PASS | [정적] `:230-233` `isReviewApproved` → `TaskModifiedEvent(rawSn, srcSnForNotify, ChangeType.META_UPDATED, reporterNo)`. 테스트 `:487`("APPROVED_영상_신고시_TASK_MODIFIED_통지_발행")·`:508`(미승인 미발행)·`:394`(rawSn 경로도 발행) | 실동작 미수행 — APPROVED 대상 126 을 신고하면 완주 기준 영상이 오염되어 회피 |
| ~~TC-DEID-038~~ | ~~라벨 0건이면 스냅샷 스킵~~ | — | **폐기 2026-07-30** (집계 제외) | |
| TC-DEID-039 | resolve: actor null → UNAUTHORIZED | PASS | [실동작] 토큰 없이 `POST /v1/deident-reports/3/resolve` → **401**. [정적] `:354-356` | |
| TC-DEID-040 | resolve: 신고 없음 → NOT_FOUND | PASS | [실동작] `rprtSn=999999` → **404** "신고를 찾을 수 없습니다."(`:357-358`) | |
| TC-DEID-041 | resolve: OPEN 아님 → CONFLICT | PASS | [실동작] 이미 RESOLVED 인 `rprtSn=4` → **409** "이미 처리된 신고입니다."(`:364-366`) | |
| TC-DEID-042 | resolve: 산출물 미검증 → CONFLICT(fail-closed) | PASS | [실동작] `rprtSn=3`(REVIEWER) → **409** "비식별 산출물이 확인되지 않습니다…". 호출 **후** DB 재확인: report `OPEN` · lock `LOCKED` · `DE_IDENT_YN='F'` **전부 유지**(트랜잭션 롤백). 로그 `resolve blocked — deident artifact not verified rawSn=133`(경로 원문 미노출) | |
| TC-DEID-043 | resolve: 성공 → RESOLVED+락해제+'F'→'Y' | PASS | [실동작] 타 에이전트 실행분 rawSn **144** 실측 — `ls_deident_report` sn=4 `RESOLVED`(resolved_dt 03:20:38) · `ls_auth_work_lock` sn=4 `RELEASED`/`MANUAL_DEIDENT_DONE` · `ls_data_raw(144).de_ident_yn='Y'` · 로그 `resolved-manually rprtSn=4 rawSn=144 actor=1001`. [정적] `:374-401` | 보존 라벨 재사용은 `DeidentReportLabelPreservationIT:167` 커버 |
| TC-DEID-044 | verifyArtifact: 경로 없음/파일 부재 → 거부 | PASS | [정적] `:544-555` — 최신 SUCCESS procLog 부재 → 거부 / `DE_IDNTF_FILE_PATH_NM` null·blank → 거부 / `DeidentArtifactIntegrity.isValidVideoArtifact` false → 거부. 테스트 `:802`·`:827`·`:876`(18B 스텁)·`:906`(시그니처 없음) | |
| TC-DEID-045 | verifyArtifact: 시간조건(신고 후 재비식별) 미충족 | PASS | [실동작] rawSn 133 — 신고 03:00:33 / procLog `rspns_dt` 02:57:32(=신고 이전) / 비식별 파일 mtime 02:57:26 KST(`17:57:26 UTC`) < 신고−60s(02:59:33) → 두 조건 모두 false → **409**. [정적] `:557-580` 스큐 60s(`CLOCK_SKEW_TOLERANCE_SECONDS:504`). 테스트 `:993`·`:1025`·`:1055` | 이 케이스가 rprtSn 3 을 OPEN 으로 유지시킨 장치다 |
| TC-DEID-046 | resolve: 배치단계 역행 안 함(CWE-664) | PASS | [정적] `:384-391` — `markDeidentified("Y")` 만 수행, `DATA_STTS_CD` 미변경(주석에 CWE-664 명시). 테스트 `:778`("APPROVED_영상_신고_해소시_배치단계가_되감기지_않는다") | rawSn 144 실측도 `data_stts_cd=FAILED` 유지(resolve 가 건드리지 않음) |
| TC-DEID-047 | resolveOpenReports(자동): OPEN 일괄 RESOLVED | PASS | [정적] `:443-462` — resolve + `releaseRaw(rawSn,"system","DEIDENT_SUCCEEDED")` + `streamMetaCacheEvictor.evictAfterCommit`. 호출부 2곳 `DeidentifyStep:354` · `KpstDeidentTxService:358`. 테스트 `:1111`·`:1130`(null 안전) | `opens.isEmpty()` 면 복구 이벤트 미발행(멱등) |
| TC-DEID-048 | 신고 목록: status allowlist 밖 → 400 | PASS | [실동작] `?status=X` · `?status=OPEN' OR 1=1--` **400**. `?status=OPEN`·`RESOLVED` 200. WORKER → 403 | ⚠ `?status=open`(소문자)도 **400** — 컨트롤러 `@Pattern`(`:74`)이 대문자만 허용하므로 서비스 `normalizeStatus` 의 `toUpperCase`(`:430`)는 HTTP 경로에서 도달 불가. "이중 방어"는 성립하나 관대성 방향이 다름(정보) |
| TC-DEID-049 | 컨트롤러: 라벨링 단계 srcSn 신고 | PASS | [실동작] `pipeline-drive.md` §1-20 — `POST /v1/labels/78/deident-report` → **201**, `ls_deident_report` sn=3 생성(rawSn 133, reporter 2001). 본 검증에서 재호출 시 409(중복)로 진입 확인 | |
| TC-DEID-050 | 컨트롤러: resolve — WORKER 본인/REVIEWER 전체 | PASS | [실동작] 200=rprtSn 4(REVIEWER, rawSn 144) / 403=미배정 WORKER(2099) rprtSn 3 / 409=rprtSn 3(산출물 미검증)·rprtSn 4(이미 처리) | |
| TC-DEID-051 | ★라벨 보존 — 신고 후 라벨·이력 불변 | PASS | [실동작] rawSn 133 — `ls_data_lbl` **28행 유지**(`pipeline-drive.md` 기록치와 동일), `ls_label_version` 신규 **0행**, 로그 `labelsPreserved=true`. [정적] `:197-205` 주석(구 스냅샷·삭제 로직 완전 제거). IT `DeidentReportLabelPreservationIT:138` | 라벨셋 버전 bump 도 없음 |
| TC-DEID-052 | 개인정보 3필드 리셋의 행 단위 감사 | PASS | [정적] `:218-227` — 리셋 **직전** `findSrcSnsWithPrivacyMeta`(`LsDataSrcRepository:212-215`, non-null 필터)로 대상 확정 → `LsDataLblHstry.recordPrivacyMetaResetEvent`(:132) 프레임당 1행. 델타 0건이라 V139 필터로 `V_COMPLETED_LABEL_CHANGE` 미노출. IT `DeidentReportServiceResetIT:127` | [실동작] rawSn 133 은 신고 시점 3필드가 이미 전부 NULL 이라 감사 대상 0건(`privacyResetAudited=0`) — 감사행 생성은 미관측. 리셋 벌크는 6행 갱신(`privacyReset=6`)이라 두 수치의 의미 차이가 설계대로 나타남 |
| TC-DEID-053 | ★파생영상 신고는 412 로 거부 | PASS | [실동작] `POST /v1/labels/68/deident-report`(파생 129 프레임) · `POST /v1/videos/129/deident-report` · `POST /v1/videos/130/deident-report`(해상도 파생) **전부 412**. 응답 문구에 **부모 rawSn 없음**·원본 유도 없음. `ls_deident_report` 신규 행 0. 로그 `WARN … rejected — derivative video is out of the report workflow rawSn=129 orgnlRawSn=126 reason=파생 신고 테스트`(LogSanitizer 정제, REVIEWER 알림 없음). [정적] `:182-183`,`:295-306` | 확정 정책 ★1 정합 |
| TC-DEID-054 | 비식별 미수행('N'/null) 신고 412 | PASS | [정적] `:185-186`,`:329-337` — 판정을 `LsDataRaw.hasDeidentArtifact()` 단일 원천에 위임하므로 `'F'`(신고·실패)는 **통과**해 기존 409 경로로 흘러간다(`:326-327` 주석 명시). 테스트 `:246`·`:273`, 컨트롤러 테스트 `:308` | [실동작] 불가 — 현 DB 에 `DE_IDENT_YN` 이 `'N'`/null 인 영상이 **0건**(전체 Y 47 / F 3) |
| TC-DEID-055 | ★마킹 단계 rawSn 신고 신설 | PASS | [실동작] `POST /v1/videos/{rawSn}/deident-report` 존재 확인 — 133→409, 129/130→412, 9999999→**404**(엔드포인트 부재 404 아님, 본문 `"영상을 찾을 수 없습니다."`), 미배정 WORKER→403. 1차 B-ISSUE-28/64 해소 | `Controller:122-129` · `Service:147` |
| TC-DEID-056 | rawSn·srcSn 두 경로가 동일 본체(doReport) | PASS | [정적] `report:118` · `reportByVideo:147` → 둘 다 `doReport:169` 로 수렴. 인가축(`verifyAndGet` vs `verifyRawAccess`)·통지 `srcSn`(null) 두 가지만 다름. [실동작] 두 경로 응답코드 일치 — 파생 412·중복 409·미인가 403·미존재 404·사유누락 400 | |
| TC-DEID-057 | resolve 성공 시 `DeidentGateReopenedEvent` 항상 발행 | PASS | [정적] `publishResolvedForExportRecovery:487-495` — `DeidentGateReopenedEvent` 는 무조건(`:491`), 승인 여부 판정 밖. 테스트 `:679`("미승인_영상_해소시_게이트_재개방만_발행되고_export_재산출은_없다 — VLM 보류 재개 경로 보존") | |
| TC-DEID-058 | resolve 성공 + APPROVED 일 때만 `DeidentReportResolvedEvent` | PASS | [정적] `:492-494` `if (isReviewApproved(rawSn))` 안에서만 발행. 테스트 `:659`(승인=자기 rawSn 만 재트리거)·`:679`(미승인 미발행) | |
| TC-DEID-059 | ★게이트 판정 범위 = 자기 rawSn 행 하나 | PASS | [정적] `DeidentReportGate:66-71` — `videoRepository.findDeIdntfYnByRawSn`(`VideoRepository:50-51` 단일 컬럼 projection) 1회 조회, `ORGNL_RAW_SN` 미참조. 잠금판정 `:93-100` 도 자기 행 1개만. 테스트 `DeidentReportGateTest:88`("★원본이_신고중이어도_파생영상은_막히지_않는다") · `DeidentReportStreamGateIT:194`. [실동작] 파생 129 의 `GET /v1/frames/68/labels` 200 · `GET /v1/videos/129/stream-url` 200 | 조상/자손 전파 코드 **부재** 재확인(grep 0건) — 확정 정책 ★1 정합 |

## B-12 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-BATCH-150 | 재처리: rawSn null → INVALID_INPUT | PASS | [실동작] `POST /v1/videos/0/batch/retry` → **400** "retryBatch.rawSn: rawSn 은 1 이상이어야 합니다."(`@Min`). [정적] 서비스 방어심도 `BatchReprocessService:67-69` | |
| TC-BATCH-151 | 재처리: 영상 미존재 → NOT_FOUND | PASS | [실동작] `rawSn=9999999` → **404** "영상을 찾을 수 없습니다."(`:71-73`) | |
| TC-BATCH-152 | 재처리: FAILED→PROCESSING 원자 클레임 성공 | PASS | [실동작] rawSn **128**(raw FAILED) 재처리 → 클레임 성공(=이어지는 SKIPPED 보상 경로에 도달했다는 것이 클레임 성공의 증거) → `clearIfIdle` → `orchestrator.process`(`:77-87`). 테스트 `BatchReprocessServiceTest:64`·컨트롤러 `:117` | 완전 성공 경로(파이프라인 완주)는 타 에이전트 데이터 오염 우려로 미수행 |
| TC-BATCH-153 | 재처리: FAILED 아님/이미 클레임 → CONFLICT | PASS | [실동작] rawSn 20035(COMPLETED) → **409** "배치가 실패(FAILED)한 영상만 재처리할 수 있으며, 이미 재처리가 진행 중일 수 있습니다."(`:77-81`). WORKER → 403 | |
| TC-BATCH-154 | 재처리 클레임: raw 우선, 없으면 status FAILED→PROCESSING | PASS | [정적] `BatchTransitionService:335-349` — `videoRepository.claimReprocessFromFailed` 우선, 1행 아니면 `rawDataStatusRepository.claimReprocessFromFailed`. 둘 다 조건부 UPDATE, `REQUIRES_NEW` 즉시 커밋 | **테스트 커버 부재** — `claimReprocessFromFailed` 를 직접 단언하는 테스트 0건(상위 서비스에서 mock 으로만) → **B-ISSUE-85** |
| TC-BATCH-155 | 재시도 등록: 최초 실패 PENDING(ON CONFLICT DO NOTHING) | PASS | [정적] `BatchRetryQueue:71-75` `insertIfAbsent`(native `ON CONFLICT (RAW_SN) DO NOTHING`) → `findByRawSnForUpdate`(PESSIMISTIC_WRITE). [실동작] 로그 `[BatchRetry] enqueued rawSn=128 attempt=1 delaySec=60` (RTY_NMTM=1). IT `BatchRetryQueueIT:90` | |
| TC-BATCH-156 | 재시도 등록: 동시 최초 실패 UK 경쟁 흡수 | PASS | [정적] `:72-74` — 원자 upsert 로 PG tx abort 함정 회피(같은 tx 내 예외 재시도 없음). IT `BatchRetryQueueIT:153`("동시_최초등록시_UK위반이_전파되지_않는다") | |
| TC-BATCH-157 | 재시도: 지수백오프(60,120,240…) shift 30 캡 | PASS | [실동작] 로그 3연속 `delaySec=60` → `120` → `240`(rawSn 128, 03:13:27). [정적] `:85-88` `shift = min(attempt-1, 30)`, `delaySec = initialDelaySec * (1L << shift)` | |
| TC-BATCH-158 | 재시도: max 초과 → EXHAUSTED false | PASS | [실동작] 로그 `WARN [BatchRetry] max attempts exceeded -- exhausted rawSn=128 attempt=4 max=3`, DB 행이 **삭제되지 않고** `STTS_CD='EXHAUSTED'` 로 관측됨(`3|128|5|3|EXHAUSTED`). [정적] `:78-84` | ⚠ 소진 후 재호출도 `incrementAttempt()` 를 먼저 하므로 `RTY_NMTM` 이 max 를 넘어 계속 증가한다(실측 5>3). 매회 `markExhausted` 이므로 동작상 무해하나 카운터 의미가 "시도 횟수"에서 이탈(정보) |
| TC-BATCH-159 | 폴링 클레임: 2노드 동시 폴링 직렬화 | PASS | [정적] `:100-112` `pollReady` — 후보 10건 순회하며 `claimAtomically`(`LsBatRtyWtngRepository` `UPDATE … SET sttsCd='RETRYING' WHERE batRtySn=? AND sttsCd='PENDING'`) 영향행수 1인 첫 건만 반환. IT `BatchRetryQueueIT:104`("재시도_동시_폴링시_한_노드만_클레임한다"). 클레임 후 사멸분은 TC-BATCH-164 스윕이 회수 | [실동작] 불가 — local `batch.enabled=false` 로 `BatchRetryTriggerConfig` 미등록(B-ISSUE-86). `ls_bat_rty_wtng` rawSn=144 PENDING 행이 도래 후에도 미발화 |
| TC-BATCH-160 | 재시도 등록 노드 ≠ 발화 노드(DB 영속) | PASS | [정적] `BatchRetryQueue` 전 메서드가 `LsBatRtyWtngRepository`(테이블 `LS_BAT_RTY_WTNG`)만 사용 — **인메모리 맵 필드 0개**(클래스 필드는 repository/maxAttempts/initialDelaySec 뿐, `:40-42`). 전 메서드 `REQUIRES_NEW` 즉시 커밋. IT `BatchRetryQueueIT:90`("재시도큐_등록후_다른_노드_폴링이_항목을_회수한다") | |
| TC-BATCH-161 | clearIfIdle: RETRYING 보존, PENDING/EXHAUSTED만 삭제 | PASS | [정적] `:140-146` → `deleteIdleByRawSn` = `DELETE … WHERE rawSn=? AND sttsCd <> 'RETRYING'`. `BatchReprocessServiceTest:60,76,95`(클레임 성공 시에만 호출) | 리포지토리 쿼리 자체를 단언하는 테스트는 없음 |
| TC-BATCH-162 | clear: 성공 시 전체 삭제 | PASS | [정적] `:125-131` `deleteByRawSn`(상태 무관). IT `BatchRetryQueueIT:178` | |
| TC-BATCH-163 | 재처리 진입 가드 SKIPPED → 보상 롤백 + 409 | PASS | [실동작] ★ rawSn **128**(raw `FAILED` + 작업상태 `PENDING`=검수 소유) 재처리 → **409** "검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다." + 호출 후 DB 재확인 `ls_data_raw(128).data_stts_cd='FAILED'` **복귀**(PROCESSING 고착 없음). [정적] `BatchReprocessService:89-101` · `BatchTransitionService.releaseReprocessClaim:365-379`(두 컬럼 모두 조건부 되돌림). IT `BatchReviewOwnedEntryGuardIT:187` | 재호출도 계속 409 로 재현 가능(고착 없음 확인) |
| TC-BATCH-164 | ★stale RETRYING 회수 — PENDING 복귀 | PASS | [정적] `:173-193` → `findStaleRetryingAnchors`(`STTS_CD='RETRYING' AND MDFCN_DT<=cutoff`, `LIMIT`) → `reclaimStaleRetrying`(같은 조건을 UPDATE 에 재탑재 + `RTY_NMTM+1` + `RTY_NMTM < MAX_RTY_NMTM`). IT `BatchRetryStaleReclaimIT:114`·`:133`(정상 처리중 미회수)·`:173`(2노드 동시 1회만) | 1차 B-ISSUE-83 해소 |
| TC-BATCH-165 | stale 회수: 상한 도달분은 EXHAUSTED 종결 | PASS | [정적] `:187-190` `exhaustStaleRetrying`(`RTY_NMTM >= MAX_RTY_NMTM`) — reclaim 과 조건 상호배타. IT `BatchRetryStaleReclaimIT:151`("재시도_상한을_넘긴_항목은_무한_부활하지_않는다") | 근거 라인 `186-191` → 실제 `187-190` |
| TC-BATCH-166 | stale 임계 하한 clamp 30분 | PASS | [정적] `BatchRetryStaleReclaimSweeper:64` `MIN_STALE_TIMEOUT_MINUTES=30`, `:94-97` `staleTimeoutMinutes<1 ? 180 : max(30, v)`. [실동작] 기동 로그 `staleTimeoutMinutes=180`(기본값 적용) | **전용 테스트 부재** — `staleTimeoutMinutes()` 접근자를 단언하는 테스트는 `VlmSubmitPendingSweeperTest` 뿐(다른 클래스) → **B-ISSUE-84** |
| TC-BATCH-167 | stale 스윕은 자기 토글만 본다 + 전용 daemon executor | PASS | [실동작] ★ 현 환경은 `authoring.batch.enabled=false`(local) 인데도 스윕이 기동됨 — 로그 `[BatchRetry][Reclaim] stale reclaim scheduled …` + 전용 데몬 스레드 `batch-retry-stale-reclaim` 의 15분 주기 tick 2회 이상 관측(02:48/03:03/03:18). [정적] `:84` `@Value("${authoring.batch.retry.stale-reclaim.enabled:true}")`, `:100-114` `@PostConstruct` + `Executors.newSingleThreadScheduledExecutor`(daemon), `@Scheduled`/`@EnableScheduling` 미사용 | **전용 테스트 부재**(`isScheduled()` 단언은 `AugmentJobExpirySweeperTest` 뿐) → B-ISSUE-84 |
| TC-BATCH-168 | stale 스윕 예외는 삼킴(Throwable) | PASS | [정적] `:142-157` `try { … } catch (Throwable e) { log.error("… reason={}", e.getClass().getSimpleName()); return 0; }` — Error 계열까지 포착, 클래스명만 노출(CWE-209) | **전용 테스트 부재** → B-ISSUE-84 |

## 근거 드리프트

케이스 표의 `근거(file:line)` 가 실제 코드와 어긋난 건. **전부 ±수 라인의 근접 드리프트**이며 지목 대상 로직이 바뀐 건은 없다(위치 이동만).

| ID | 표 기재 | 실측 | 성격 |
|---|---|---|---|
| TC-DEID-031 | `DeidentReportService.java:118-133` | `report()` = 118-126 (인가 위임 122) | 범위 초과 |
| TC-DEID-032 | `:178-180` | 178-179 | ±1 |
| TC-DEID-033 | `:188-191` | 189-191 | ±1 |
| TC-DEID-037 | `:230-234` | 230-233 | ±1 |
| TC-DEID-039 | `:353-360` | 353-356 | ±4 |
| TC-DEID-040 | `:353-365` | 357-358 | 범위 |
| TC-DEID-041 | `:365-370` | 364-366 | ±4 |
| TC-DEID-042 | `:372,542-590` | 372 ✓ / `verifyDeidentArtifact` 542-581 | ±9 |
| TC-DEID-043 | `:372-400` | 374-401 | ±2 |
| TC-DEID-044 | `:542-570` | 544-555 | ±15 |
| TC-DEID-045 | `:542-590` | 시간조건 557-580 | ±10 |
| TC-DEID-046 | `:380-400` | 384-391 | ±9 |
| TC-DEID-047 | `:443-470` | 443-462 | ±8 |
| TC-DEID-048 | `:418-440` | `listReports` 417-423 / `normalizeStatus` 426-438 | 범위 |
| TC-DEID-051 | `:196-204` | 197-205(주석 블록) | ±1 |
| TC-DEID-053 | `:182-183,295-310` | 182-183 ✓ / 295-306 | ±4 |
| TC-DEID-054 | `:185-186,329-340` | 185-186 ✓ / 329-337 | ±3 |
| TC-DEID-057 | `:480-491` | `publishResolvedForExportRecovery` 487-495(발행 491) | ±7 |
| TC-DEID-058 | `:483-493` | 492-494 | ±9 |
| TC-DEID-059 | `DeidentReportGate.java:12-39,66` | javadoc 8-47 / `isUnderDeidentReport` 66 ✓ | 범위 |
| TC-BATCH-154 | `BatchTransitionService.java:334-362` | `tryClaimReprocessFromFailed` 335-350 | 범위 |
| TC-BATCH-163 | `BatchTransitionService.java:364-380` | `releaseReprocessClaim` 365-379 | ±1 |
| TC-BATCH-165 | `BatchRetryQueue.java:186-191` | 187-190 | ±1 |
| TC-BATCH-160 | `BatchRetryQueue.java:16-34` | 클래스 javadoc 14-32 | ±2 |
| TC-BATCH-167 | `BatchRetryStaleReclaimSweeper.java:42-50,84,102-110` | javadoc 42-52 / `:84` ✓ / `start()` 100-114 | ±4 |

## 이슈 상세

### [B-ISSUE-81] TC-DEID-035 / TC-DEID-052 — 프레임 개인정보 3필드 수정 API 가 신고 게이트·작업락 어디에도 배선되지 않아, 신고가 리셋한 PII 표기를 신고 구간에 즉시 되돌릴 수 있다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 비식별 누락 신고는 "그 판정은 비식별이 잘못된 영상에서 내려진 것이므로 재판정 대상"이라는 이유로 개인정보 3필드(익명/가명/PII 포함여부)를 NULL 로 리셋한다(CLAUDE.md · TC-DEID-035 "stale PII 방지"). 따라서 신고~해소 구간에는 **그 값을 다시 채울 수 없어야** 리셋이 의미를 갖는다. 같은 구간의 라벨 저장이 작업락 409 로 막히는 것과 동일한 취급이어야 한다.
- **현재 동작(이슈 내용)**: `PUT /v1/frames/{srcSn}/privacy-meta` 는 인가만 검사하고 **신고 게이트도 작업락도 보지 않는다.**
  ```java
  // backend/src/main/java/kr/co/cudo/authoring/dataset/service/FramePrivacyMetaService.java:74-75
  public FramePrivacyMetaResponse update(Long srcSn, FramePrivacyMetaUpdateRequest req, TokenClaims actor) {
      LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만. requireNotUnderDeidentReport / workLock 없음
  ```
  같은 클래스의 `updateBulk`(`:100-122`)도 `verifyRawAccess` 만 수행한다. 비교 대상인 `LabelService.getByFrame:193` · `getHistory:482` 는 `accessGuard.requireNotUnderDeidentReport(...)` 를 인가 직후에 호출한다.
- **재현/확인 경로** (rawSn 133 = 신고 OPEN, srcSn 78. 실제로 수행했고 즉시 NULL 로 원복함):
  ```bash
  # 신고 직후 상태: 3필드 전부 NULL
  docker exec klid-postgres psql -U klid_user -d klid_system -At -F'|' \
    -c "select src_sn,anony_incl_yn,psdo_incl_yn,prvc_incl_yn from ls_data_src where src_sn=78;"   # 78|||

  curl -s -X PUT http://localhost:18081/api/v1/frames/78/privacy-meta \
    -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}'
  # -> 200 {"success":true,"data":{"srcSn":78,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}}
  # DB -> 78|Y|N|N   (신고가 지운 값이 되살아남)
  ```
  대조: 같은 프레임의 `PUT /v1/frames/78/labels` 는 **409** "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."
- **영향**: CWE-359(민감정보 노출) / CWE-863(부정확한 인가). ①신고의 보안 부수효과가 무효화되어 "개인정보 없음(`prvcInclYn='N'`)" 같은 **stale 오표기가 신고 구간에 재삽입**될 수 있고, 그 값은 export JSON·데이터마트로 그대로 나간다. ②`LS_DATA_LBL_HSTRY` 감사(TC-DEID-052)는 **리셋만** 기록하고 이 재설정은 기록하지 않아, "누가 언제 PII 표기를 되돌렸는가"가 추적되지 않는다(OWASP A09). ③작업락이 걸린 영상에 쓰기가 통과하므로 락의 계약도 부분적으로 깨진다.
- **수정 방향(제안)**: `FramePrivacyMetaService.update`/`updateBulk` 진입부(인가 **이후**)에 `accessGuard.requireNotUnderDeidentReport(src.getRawSn())` 를 추가하고, 라벨 저장과 동일하게 작업락 409 가드를 적용한다. 판정은 반드시 `DeidentReportGate` 단일 원천을 재사용할 것(호출처마다 `"F".equals(...)` 를 재구현하지 않는다). ⚠ **구현하지 않는다.**

### [B-ISSUE-82] B-11 인접 — 프레임 설명 수정 API 가 작업락·신고 게이트 미배선으로 신고 구간에도 200 으로 기록된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 신고 구간(`DE_IDNTF_YN='F'` + 작업락 LOCKED)에는 그 영상에 대한 작업 산출물 수정이 차단되어야 한다(CLAUDE.md "저장·수정은 기존 작업락으로 409 차단"). 프레임 설명은 승인 후 수정 시 **export 전량 재생성 트리거**로 명시된 산출 대상이다(CLAUDE.md ★export 재생성 정책, `FrameDescriptionService`).
- **현재 동작(이슈 내용)**:
  ```java
  // backend/src/main/java/kr/co/cudo/authoring/label/service/FrameDescriptionService.java:45-46
  public FrameDescriptionResponse update(Long srcSn, String description, TokenClaims actor) {
      LsDataSrc src = guard.verifyAndGet(srcSn, actor);   // 인가만
  ```
  신고 게이트·작업락 검사가 없다.
- **재현/확인 경로** (수행 후 `description:null` 로 원복함):
  ```bash
  curl -s -X PUT http://localhost:18081/api/v1/frames/78/description \
    -H "Authorization: Bearer $WT" -H 'Content-Type: application/json' \
    -d '{"srcSn":78,"description":"gate probe"}'
  # -> 200 {"success":true,"data":{"srcSn":78,"description":"gate probe"}}
  ```
- **영향**: 잠긴 영상에 대한 작업이 계속되어 락 계약이 깨진다. 승인된 영상이라면 재export 브리지를 깨우는데, export 자체는 신고 게이트로 skip 되므로 **파일은 갱신되지 않은 채 트리거만 소모**된다(관제 통지도 보류). PII 직접 노출은 아니라 MEDIUM.
- **수정 방향(제안)**: `FrameDescriptionService.update` 에 `requireNotUnderDeidentReport` + 작업락 가드 추가. 조회(`get`)까지 막을지는 정책 결정 대상(설명 텍스트는 좌표·픽셀이 아니므로 조회 차단은 불필요하다는 판단도 가능). ⚠ **구현하지 않는다.**

### [B-ISSUE-83] B-11 인접 — `GET /v1/videos/{rawSn}/labels/auto` · `/auto-summary` 가 신고 구간에도 라벨 메타를 노출한다(게이트 미배선 형제 경로)
- **심각도**: LOW
- **기대 동작(기대효과)**: 신고 구간에는 라벨 조회 계열이 412 로 차단된다(`GET /v1/frames/{srcSn}/labels`·`label-history`·관제 `tasks/{rawSn}/labels` 전부 배선됨).
- **현재 동작(이슈 내용)**: 같은 영상의 **영상 단위 라벨 조회 2종**에는 게이트가 없다.
  ```
  GET /v1/videos/133/labels/auto   -> 200  data.objects[28]  {id,labelCode,labelName,color,confidence,createdBy}
  GET /v1/videos/133/auto-summary  -> 200  totalFrames=6 totalLabels=28 classDistribution=[car 11, truck 7, …]
                                           lowConfidenceFrames[].thumbnailUrl="/v1/frames/80/image"
  ```
  `grep -rn "requireNotUnderDeidentReport\|isUnderDeidentReport" VideoController/관련 서비스` → 해당 두 핸들러 경로에 0건.
- **영향**: 응답 DTO에 **좌표(`pointCn`)가 없어** PII 위치 특정 정보는 나가지 않고, 참조된 `thumbnailUrl` 도 호출하면 412 로 막힌다. 따라서 실질 노출은 "어떤 클래스가 몇 개 있는가"에 그친다. 다만 **차단 범위 목록이 엔드포인트 단위로 열거된 정책**에서 형제 경로만 빠진 형태라, 향후 이 DTO 에 좌표/썸네일 바이트가 추가되면 조용히 누수로 승격된다(이 리포의 반복 사고 패턴 — "컨트롤러가 정책 판정을 복제·누락").
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/133/labels/auto | head -c 300
  curl -s -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/133/auto-summary | head -c 300
  ```
- **수정 방향(제안)**: 두 핸들러의 서비스 진입부(인가 이후)에 `requireNotUnderDeidentReport(rawSn)` 을 추가하거나, "좌표 없는 집계는 게이트 대상 아님"을 `UNCERTAINTIES.md` 확정 정책으로 명문화해 판정 기준을 고정한다(둘 중 하나는 필요 — 현재는 의도인지 누락인지 문서로 판별 불가). ⚠ **구현하지 않는다.**

### [B-ISSUE-84] TC-BATCH-166 / 167 / 168 — stale 회수 스윕의 clamp·토글 독립·예외 격리에 전용 테스트가 없다
- **심각도**: LOW (커버리지 갭 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 케이스 3건이 각각 단언 대상(`MIN_STALE_TIMEOUT_MINUTES` 하한 clamp / 자기 토글 off 시 미등록 / `Throwable` 삼킴)을 회귀 테스트로 고정한다. 특히 clamp 는 "과소 설정이 정상 처리 중 항목을 뺏어가 이중 처리 전면화"를 막는 fail-safe 라 회귀 시 영향이 크다.
- **현재 동작(이슈 내용)**: `grep -rn "BatchRetryStaleReclaimSweeper" backend/src/test/` → **0건**. `BatchRetryStaleReclaimIT`(4건)는 `BatchRetryQueue.sweepStaleRetrying` 만 직접 호출하고 스위퍼 빈은 거치지 않는다. 동형 자산(`AugmentJobExpirySweeperTest` 의 `isScheduled()` 단언, `VlmSubmitPendingSweeperTest:121` 의 `staleTimeoutMinutes()` 하한 단언)이 존재하므로 패턴은 이미 있다.
- **재현/확인 경로**:
  ```bash
  grep -rn "BatchRetryStaleReclaimSweeper\|MIN_STALE" backend/src/test/java   # 0건
  grep -rn "isScheduled\|staleTimeoutMinutes" backend/src/test/java           # Augment/Vlm 스위퍼만 매칭
  ```
- **영향**: clamp 상수를 낮추거나 `@PostConstruct` 등록 조건을 바꿔도 테스트가 초록으로 통과한다. 현재 구현 자체는 정상(정적 확인 + 실기동 로그로 확인).
- **수정 방향(제안)**: `VlmSubmitPendingSweeperTest`/`AugmentJobExpirySweeperTest` 와 동형으로 `BatchRetryStaleReclaimSweeperTest` 신설 — ①`stale-timeout-minutes=0/-1/5` 로 생성 시 `staleTimeoutMinutes() >= 30` ②`enabled=false` 면 `isScheduled()==false` ③`retryQueue.sweepStaleRetrying` 가 `Error` 를 던져도 `run()` 이 0 을 반환. ⚠ **구현하지 않는다.**

### [B-ISSUE-85] TC-BATCH-154 — 재처리 클레임의 2컬럼(raw 우선 → work 폴백) 조건부 UPDATE 에 직접 테스트가 없다
- **심각도**: LOW (커버리지 갭)
- **기대 동작(기대효과)**: `tryClaimReprocessFromFailed` 가 ①`LS_DATA_RAW` FAILED→PROCESSING 을 우선 클레임하고 ②실패 시 `LS_RAW_DATA_STATUS` FAILED→PROCESSING 을 클레임한다는 두 갈래가 회귀 테스트로 고정된다.
- **현재 동작(이슈 내용)**: `grep -rn "claimReprocessFromFailed" backend/src/test/java` → **0건**. `BatchReprocessServiceTest` 는 `transitionService.tryClaimReprocessFromFailed(...)` 를 Mockito 로 stub 할 뿐 실제 UPDATE 를 실행하지 않는다. `BatchReviewOwnedEntryGuardIT:225` 는 `releaseReprocessClaim` 을 **정리 목적**으로만 호출한다.
- **재현/확인 경로**: 위 grep. 실동작으로는 raw 경로(rawSn 128, raw=FAILED)만 확인되었고 **work 폴백 경로는 미검증**이다(raw≠FAILED + work=FAILED 인 영상이 현 DB 에 없음).
- **영향**: 두 컬럼 중 한쪽 술어가 바뀌어도 감지되지 않는다. 이중 실행 차단(CWE-362)의 최종 방어라 회귀 시 영향이 크다.
- **수정 방향(제안)**: Testcontainers IT 로 ①raw=FAILED/work=null → true·raw 가 PROCESSING 으로 전이 ②raw=COMPLETED/work=FAILED → true·work 만 전이 ③둘 다 아님 → false·전이 0건 ④동시 2호출 시 1건만 true 를 단언. ⚠ **구현하지 않는다.**

### [B-ISSUE-86] TC-BATCH-159 / 160 — local 프로파일에서 재시도 폴러가 미등록이라 큐의 등록→발화 전 사이클을 실동작으로 검증할 수 없다 (1차 B-ISSUE-85 이월)
- **심각도**: LOW (환경 구성 — dev/stg/prd 무영향)
- **기대 동작(기대효과)**: 검증 환경에서 `enqueueIfRetryable` → `pollReady`(CAS 클레임) → `orchestrator.process` 전 사이클과 2노드 클레임 직렬화를 관측할 수 있다.
- **현재 동작(이슈 내용)**: `BatchRetryTriggerConfig:19` 가 `@ConditionalOnProperty(prefix="authoring.batch", name="enabled", havingValue="true", matchIfMissing=true)` 인데 `application-local.yml:61-62` 가 `batch.enabled: false` → Quartz 트리거 미등록. 반면 배치 본체는 `MarkingBatchBridge → AsyncBatchRunner`(@Async) 로 구동되어 **재시도 적재는 계속 일어난다.**
  ```
  ls_bat_rty_wtng: 8|144|3|3|PENDING|…    ← 도래했으나 발화 0건
  기동 로그: Scheduler KlidAuthoringScheduler_$_NON_CLUSTERED started  (batchRetryTrigger 등록 로그 없음)
  ```
  런타임 실측 `SPRING_PROFILES_ACTIVE=local`, `BATCH_ENABLED` 미설정.
- **영향**: TC-BATCH-159/160 이 IT·정적 근거로만 판정된다(본 리포트도 그렇게 처리). 1차 대비 상태 변화 없음. 단 **stale 회수 스윕은 자기 토글이라 이 영향을 받지 않는다**(TC-BATCH-167 이 실동작으로 확인) — 무관 토글 종속 회피 설계가 실제로 작동함이 확인된 셈이다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep -E "SPRING_PROFILES_ACTIVE|BATCH_ENABLED"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select * from ls_bat_rty_wtng;"
  docker logs klid-backend 2>&1 | grep -i "batchRetryTrigger"    # 0건
  ```
- **수정 방향(제안)**: 검증용 프로파일에서만 `BATCH_ENABLED=true` + 짧은 `BATCH_RETRY_INTERVAL_SEC` 로 기동하거나, 폴링 1 tick 을 강제하는 dev 트리거(`POST /v1/dev/batch/retry-tick`)를 두어 큐 사이클을 관측 가능하게 한다. ⚠ **구현하지 않는다.**

---

## 검증 중 발생시킨 상태 변화(전량 명시)

| 대상 | 무엇을 | 원복 여부 |
|---|---|---|
| `ls_data_src(78).anony/psdo/prvc_incl_yn` | B-ISSUE-81 재현을 위해 `Y/N/N` 로 설정 | **원복 완료** — 동일 API 로 3필드 전부 NULL 재설정, DB 확인 `78\|\|\|` |
| `ls_data_src(78)` 프레임 설명 | B-ISSUE-82 재현을 위해 `"gate probe"` 기록 | **원복 완료** — `description:null` 로 재설정, 응답 확인 |
| `ls_data_raw(128).data_stts_cd` | TC-BATCH-163 재현 — 클레임으로 `FAILED→PROCESSING`, 보상 롤백으로 복귀 | **자동 원복**(보상 롤백) — 최종 `FAILED` 확인 |
| `ls_deident_report(3)` / `ls_auth_work_lock(3)` / `ls_data_raw(133)` | resolve 409(fail-closed) 호출 | **변화 없음** — `OPEN` / `LOCKED` / `'F'` 유지 확인 |

그 외 `UPDATE`/`DELETE` 직접 실행 0건. 파생 412·중복 409·403·404·400 응답 경로는 정의상 상태를 만들지 않으며, `ls_deident_report` 신규 행이 생기지 않았음을 확인했다(sn 3·4·5 만 존재, 4·5 는 타 에이전트 생성분).
