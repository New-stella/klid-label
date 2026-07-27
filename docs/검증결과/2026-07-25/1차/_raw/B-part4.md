# B-10 / B-11 검증 결과 (docs/test-cases/B-batch-deidentify.md:194-241)

> 검증일 2026-07-25 · 로컬 풀스택 실동작 기준(klid-backend :18081, klid-postgres, klid-mock-server)
> DB 스키마는 `klid_at` 아닌 **`public`**.
> ⚠ **환경 주의**: 실행 중 `klid-backend` jar 는 `V129` 시점 빌드(Jul 23 17:10)로 **HEAD(27b6bb0d) 미반영**. 상세는 [B-ISSUE-62].
> ⚠ **본 검증이 남긴 상태 변경(API 경유)**: `rawSn=12` 에 신고 1건(rprtSn=6) 등록 → `DE_IDENT_YN='F'` + 작업락 LOCKED. 라벨 0건 영상을 골라 라벨 파괴는 없음. resolve 는 산출물 미검증(fail-closed)으로 거부되어 상태 유지 중.

## B-10. 비디오 스트리밍 (Range, 비식별본만 서빙)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-STREAM-B01 | 비식별 미완료 → NOT_FOUND(원본 차단) | PASS | **실동작**: `GET /v1/videos/{22,20}/stream`(`DE_IDENT_YN='N'`) → **404**. 신고 직후 `rawSn=12`('F') → **404**. 정적 `VideoStreamService.java:156-160` | VideoStreamServiceTest:121 `비식별_미완료시_NOT_FOUND` | 원본 바이트 누출 0건 |
| TC-STREAM-B02 | 영상 미존재 → NOT_FOUND | PASS | **실동작**: `rawSn=9999` → **404**. 정적 `:314-316` | VideoStreamServiceTest:156 | |
| TC-STREAM-B03 | `deIdntfYn='F'/'N'` → null→NOT_FOUND | PASS | **실동작**: 'N'(22,20,21)→404, 'F'(12, 신고 후)→404. 정적 `:318-323` | VideoStreamServiceTest:476,493 | 신고본(노출본) 재서빙 차단 실증 |
| TC-STREAM-B04 | 비식별 경로 base 이탈 → FORBIDDEN(CWE-22) | PASS | **실동작**: `rawSn={15,16,18,19}` → **403**(경로가 `/app/storage/raw/...`). 정적 `:266-267,345-357` | VideoStreamServiceTest:172 | 가드는 정상 작동. 단 이 403 이 **해상도 파생영상 재생 전면 차단**을 유발 → [B-ISSUE-61] |
| TC-STREAM-B05 | 비식별 파일 부재 → NOT_FOUND | PASS(정적/테스트) | 정적 `:270-273`. 실동작 케이스 부재 — `DE_IDENT_YN='Y'` 13건 전부 파일 실존(컨테이너 `ls` 확인) | VideoStreamServiceTest:138 | |
| TC-STREAM-B06 | Range 없음 → 200 전체+Accept-Ranges | PASS | **실동작**: Range 미지정 → **200**, `Accept-Ranges: bytes`, `Content-Length: 18` | VideoStreamControllerTest:90 | 200 응답에도 `Content-Range` 가 함께 붙음(컨버터 동작) — 무해 |
| TC-STREAM-B07 | Range 유효 → 206 + 청크 상한 | PASS | **실동작**(28MB 파일 rawSn=12): `bytes=0-` → 206 `bytes 0-8388607/28220079`, `bytes=0-27000000` → 동일 8MB 상한, `bytes=20000000-` → `20000000-28220078` | VideoStreamControllerTest:99,111 | |
| TC-STREAM-B08 | Range 문법 오류 → 416 | PASS | **실동작**: `bytes=999-0` → **416** `Content-Range: bytes */18`; `bytes=abc` → **416** | VideoStreamServiceTest:399 | fail-secure |
| TC-STREAM-B09 | start≥total → 416 | PASS | **실동작**: `bytes=99999999999-` → **416** `bytes */18` | VideoStreamServiceTest:375 | |
| TC-STREAM-B10 | 청크 상한 미설정/<1MB → 8MB | PASS | **실동작**: 기본 설정에서 `bytes=0-` 반환 8,388,608B = 8MB 확인. 정적 `:223-228` | VideoStreamServiceTest:289,313 | |
| TC-STREAM-B11 | >64MB → 64MB 클램프(CWE-190) | PASS(정적/테스트) | 정적 `:64,224-227` (`Math.min(streamChunkSize, MAX_CHUNK_SIZE)`) | VideoStreamServiceTest:337,350 | 런타임 설정 변경 불가로 live 미실행 |
| TC-STREAM-B12 | 서명 URL: 비식별 무효 → NOT_FOUND | PASS | **실동작**: `GET /v1/videos/22/stream-url` → **404**; 신고 직후 `12/stream-url` → **404**. 정적 `:119-125` | — | |
| TC-STREAM-B13 | 시크릿 미설정 → 503 | PASS(정적/테스트) | 정적 `:127-133`. 로컬은 `STREAM_SIGN_SECRET` 설정됨(64자) → live 미실행 | VideoStreamServiceTest:507 | |
| TC-STREAM-B14 | userNo 바인딩(재사용 차단) | PASS | **실동작 반증 8종 전부 차단**: u 변조(2001)→401 / u 누락→401 / sig 변조→401 / exp 연장(+3600)→401 / 타 rawSn(24) 재사용→401 / 파라미터 중복 `u=9999&u=1001`→401. 통과(정상 동치)만 200: u URL 인코딩(`%31%30%30%31`), sig 대문자, `exp=+…`, `rawSn=025` | StreamSignedUrlControllerTest:190,202,178,153,165 | 인코딩/정규화 우회 없음 — A 클러스터의 HMAC 인코딩 우회와 동종 결함 **미발견** |
| TC-STREAM-B15 | 스트림 인가: STREAM_SIGNED 또는 REVIEWER/WORKER | **PARTIAL** | **실동작**: 미인증→**401**, PORTAL_USER(PORTAL 채널)→**403**, 유효 서명(Authorization 없음)→**200**. 정적 `VideoController.java:214-225` … 그러나 **배정 무관 WORKER(2002, 배정 0건)가 임의 영상 스트리밍 206** | StreamSignedUrlControllerTest:122,129,229 | 역할/채널 게이트는 설계대로 동작. 영상 단위 인가 부재 → [B-ISSUE-63] |
| TC-STREAM-B16 | 스트림 메타 캐시: null 미캐싱 | PASS | 정적 `:257` `@Cacheable(unless="#result == null")` — 예외·null 미캐싱. 간접 실동작: 'F' 전이 후 즉시 404, 이전 200 결과가 고정되지 않음 | — | |
| TC-STREAM-B17 | 캐시 무효화: 신고('F') 후 즉시 | PASS | **실동작(핵심)**: 신고 직전 `rawSn=12` 스트림 **206**(캐시 warm) → `POST /v1/labels/350/deident-report` **201** → 동일 요청 즉시 **404**. 정적 `DeidentReportService.java:166-168` + `StreamMetaCacheEvictor.evictAfterCommit` | — | 옛 노출본 재서빙 창 **미관측** |

## B-11. 비식별 누락 신고 (라벨링 단계 srcSn, resolve)

| ID | 케이스명 | 판정 | 근거 확인(실동작/정적) | 기존 테스트 | 비고 |
|----|---------|:--:|----------------------|-----------|------|
| TC-DEID-030 | reason blank → INVALID_INPUT | PASS | **실동작**: `{"reason":""}`·`{"reason":"  "}` → **400** `INVALID_INPUT` | DeidentReportControllerTest:189,201 / ServiceTest:486 | @Valid 단계에서 선차단 |
| TC-DEID-031 | WORKER 본인 배정만(IDOR) | PASS | **실동작**: WORKER(2002, 미배정)가 srcSn=350 신고 → **403** "본인에게 배정되지 않은 영상입니다." PORTAL_USER → 403, 미인증 → 401. 정적 `:116-117` | ControllerTest:139 | |
| TC-DEID-032 | 부모 RAW PESSIMISTIC_WRITE 락(PII TOCTOU) | PASS(정적/테스트) | 정적 `:120-128` `videoRepository.findByRawSnForUpdate` + 대칭 게이트 `ResolutionReservationPersister.java:63-74` | AugmentDeidentConcurrencyIT | 동시 증강 콜백 재현은 live 불가 |
| TC-DEID-033 | 이미 잠금 → CONFLICT | PASS | **실동작**: 잠금 상태 rawSn=8(srcSn=4) 신고 → **409**. 정적 `:131-134` | ControllerTest:168 / ServiceTest:452 | |
| TC-DEID-034 | 전체 라벨 스냅샷+삭제+'F' | PASS(정적/테스트) | **실동작**(라벨 0건 rawSn=12): 201 + `DE_IDENT_YN='F'` + `LS_AUTH_WORK_LOCK` LOCKED(owner 2001) DB 확인. 라벨 보유 영상 파괴 회피로 스냅샷/삭제 경로는 정적 `:140-164` + IT 로 확인 | ServiceTest:172,220,287 / ResetIT:67 | 스냅샷·삭제는 단일 tx(부분 실패 시 전체 롤백) |
| TC-DEID-035 | 개인정보 3필드 리셋(stale PII 방지) | **BLOCKED** | **실행 중 jar 에 코드 부재** — `grep -a resetPrivacyMetaByRawSn /app/app.jar` → 0건, DB 에 `ls_data_src.anony_incl_yn/psdo_incl_yn/prvc_incl_yn` 컬럼 없음(V130 미적용, flyway 최신 129). HEAD 소스에는 구현됨(`:151-155`, `LsDataSrcRepository:70-72`) | ServiceTest:199 / ResetIT:67 | 환경 stale — [B-ISSUE-62] |
| TC-DEID-036 | 락 UNIQUE 위반 → CONFLICT(동시) | PASS | **실동작**: 동일 영상 2차 신고(srcSn=351) → **409**. 정적 `:159-163` | ServiceTest:431 | 선점검(`:131-134`)이 먼저 걸림 — UNIQUE 경로는 unit 커버 |
| TC-DEID-037 | APPROVED → TASK_MODIFIED(LABEL_DELETED) | PASS(정적/테스트) | 정적 `:144-148` (`snapshotted && isReviewApproved` 조건). live 대상(rawSn 13/14/17/19, APPROVED+라벨 보유)은 파괴 회피로 미실행 | ServiceTest:391,413 | |
| TC-DEID-038 | 라벨 0건이면 스킵 | PASS | **실동작**: rawSn=12(라벨 0건) 신고 201, 라벨/이력 생성 없음. 정적 `:140-149` | ServiceTest:261,354 / VersionServiceDeidentSnapshotTest:117 | |
| TC-DEID-039 | resolve: actor null → UNAUTHORIZED | PASS | **실동작**: 무토큰 resolve → **401**. 정적 `:193-195` | ServiceTest:831 / ControllerTest:294 | |
| TC-DEID-040 | resolve: 신고 없음 → NOT_FOUND | PASS | **실동작**: `rprtSn=999999` → **404** "신고를 찾을 수 없습니다." | ServiceTest:840 | |
| TC-DEID-041 | resolve: OPEN 아님 → CONFLICT | PASS | **실동작**: `rprtSn=1`(RESOLVED) → **409** "이미 처리된 신고입니다." | ServiceTest:560 / ControllerTest:258 | |
| TC-DEID-042 | 산출물 미검증 → CONFLICT(fail-closed) | PASS | **실동작**: `rprtSn=2`(OPEN, rawSn=8) REVIEWER resolve → **409**. 직후 DB 확인 — report OPEN 유지 / `DE_IDENT_YN='F'` 유지 / lock LOCKED 유지 (롤백 확인). 정적 `:207-211,332-376` | ServiceTest:666,691 | 실제 재비식별 없이 'Y' 복원되는 경로 **미발견** |
| TC-DEID-043 | resolve 성공 → RESOLVED+락해제+'F'→'Y' | PASS(정적/테스트) | 정적 `:213-230`. live 는 파일 mtime 조작(파일 수정)이 필요해 제약상 미실행 | ServiceTest:544,604,622,709,768,798 | |
| TC-DEID-044 | verifyArtifact: 경로/파일 부재 → 거부 | PASS(정적/테스트) | 정적 `:332-351` | ServiceTest:666,691 | |
| TC-DEID-045 | 시간조건(신고 후 재비식별) 미충족 → 거부 | PASS | **실동작**: `rprtSn=6`(신고 2026-07-25 10:47) resolve → **409**. 해당 비식별본 mtime = 2026-07-15 → 옛 비식별본 배제 실증. `rprtSn=2` 도 동일. 정적 `:353-375` | ServiceTest:735,768,798 | 스큐 관용 60초 |
| TC-DEID-046 | 배치단계 역행 안 함(CWE-664) | PASS | **실동작**: rawSn=12 신고 후 `DATA_STTS_CD` = `COMPLETED` 유지(변경 없음). 정적 `:222-230` | ServiceTest:642 | |
| TC-DEID-047 | resolveOpenReports(자동) 일괄 RESOLVED | PASS(정적/테스트) | 정적 `:278-294` | ServiceTest:855,874 | `opens` 비어도 `releaseRaw`·evict 무조건 호출 — 멱등이라 무해 |
| TC-DEID-048 | 목록: status allowlist 밖 → 400 | PASS | **실동작**: `status=X` → **400**, `status=OPEN' OR 1=1--` → **400**(SQLi 차단), 무지정 → 200(기본 OPEN), WORKER → 403, 미인증 → 401. 정적 `:260-273` + Controller `@Pattern` | ControllerTest:353,361,322,372 | 이중 방어(@Pattern + 서비스 정규화) |
| TC-DEID-049 | 컨트롤러: srcSn 신고(구현됨) | PASS | **실동작**: `POST /v1/labels/350/deident-report` (WORKER 본인 배정) → **201** `data=6`. 정적 `DeidentReportController.java:91-99` | ControllerTest:113,156 | 마킹단계 rawSn 신고는 미구현 → [B-ISSUE-64] |
| TC-DEID-050 | 컨트롤러: resolve — WORKER 본인/REVIEWER 전체 | PASS | **실동작**: WORKER 본인배정 → 409(산출물 게이트, 인가는 통과) / WORKER 타인 → **403** / REVIEWER → 409(동일 게이트) / RESOLVED 재호출 → 409. 정적 `:114-121` | ControllerTest:241,273,284 | 200 성공 경로는 TC-DEID-043 와 동일 제약으로 정적 커버 |

---

## 이슈 상세 (FAIL / PARTIAL / BLOCKED / 확인필요 전건)

### [B-ISSUE-61] TC-STREAM-B04 — 해상도 파생영상 스트리밍이 전면 403(FORBIDDEN)으로 차단됨
- **심각도**: HIGH (기능 차단, 보안 회귀 위험 동반)
- **기대 동작(기대효과)**: 해상도 파생영상(`AUG_TYPE_CD=RESL_*`)도 파생영상으로서 마킹/라벨링/검수 화면에서 재생되어야 한다(CLAUDE.md — 파생영상은 기존 RAW_SN 파이프라인을 그대로 탄다).
- **현재 동작(이슈 내용)**: 파생영상의 비식별 경로가 **raw 저장소 밑**에 기록되는데, 스트리밍 가드는 **deidentified 저장소 base 만** 허용한다.
  - `backend/.../video/service/ResolutionReservationPersister.java:82-84`
    ```java
    Path base = Paths.get(storageRawPath).toAbsolutePath().normalize();
    String derivativeVideoPath = resolveSafeDir(base,
        "resolution/" + parent.getRawSn() + "/" + preset.name() + "/video/" + preset.name() + ".mp4").toString();
    ```
  - `backend/.../video/service/ResolutionPersistService.java:143-146` — 이 경로를 그대로 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 으로 기록(`procLog.succeed(videoDst)`).
  - `backend/.../video/service/VideoStreamService.java:266-267` — `baseDir = Paths.get(deidentifiedPath)` 로만 검증 → `resolveSafe` 가 `:353-354` 에서 FORBIDDEN.
  - 런타임 env: `STORAGE_RAW_PATH=/app/storage/raw`, `STORAGE_DEIDENTIFIED_PATH=/app/storage/deidentified` (서로 다름).
  - **실측**: `DE_IDENT_YN='Y'` 인 파생 4건(rawSn 15,16,18,19) 전부 403. 비파생 Y 영상 9건은 전부 206.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT r.raw_sn, r.de_ident_yn, l.de_idntf_file_path_nm FROM public.ls_data_raw r
     JOIN public.ls_deident_proc_log l ON l.data_raw_sn=r.raw_sn AND l.proc_stts_cd='SUCCEEDED'
     WHERE r.orgnl_raw_sn IS NOT NULL;"
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $RT" \
    -H 'Range: bytes=0-9' http://localhost:18081/api/v1/videos/18/stream   # -> 403
  ```
- **영향**: SFR-06-03 해상도 파생 결과물을 화면에서 확인·마킹·검수 불가. 더 위험한 것은 **성급한 수정 방향**이다 — 가드에 rawPath base 를 통째로 추가(과거 export 경로에서 실제로 채택된 우회, memory `cudo246-realtest-issue-batch` ★E)하면 `/app/storage/raw` 아래 **진짜 원본**까지 스트리밍 허용 범위에 들어와 PII 유출(CWE-22/CWE-359)로 전환된다.
- **수정 방향(제안)**: ① 파생영상 비디오 산출물을 **deidentified base 하위**(`{deidPath}/videos/{newRawSn}/…`)에 생성하도록 `ResolutionReservationPersister` 경로 기준을 바꾸는 것이 정공법. ② 불가피하게 raw base 를 허용해야 한다면 base 전체가 아니라 **`{rawPath}/resolution/` 서브트리로 한정한 allowlist** 로 좁히고, 원본 파일 경로(`RAW_FILE_PATH_NM`)와의 동일성 검사를 추가해 원본 직접 서빙을 차단할 것.

### [B-ISSUE-62] TC-DEID-035 — 실행 중 백엔드가 HEAD 미반영(V130 미적용)이라 개인정보 3필드 리셋을 실동작 검증 불가
- **심각도**: MEDIUM (검증 환경 결함 — 기능 결함 아님)
- **기대 동작(기대효과)**: 실동작 검증 대상 스택은 HEAD 소스와 동일 빌드여야 한다.
- **현재 동작(이슈 내용)**:
  - 실행 jar `/app/app.jar` 타임스탬프 `Jul 23 17:10`, 내장 마이그레이션 최대 = `V129__ls_label_add_dtct_type` (`V130__add_manual_env_privacy_meta` 부재).
  - DB `public.flyway_schema_history` 최신 = 129. `public.ls_data_src` 에 `anony_incl_yn/psdo_incl_yn/prvc_incl_yn` 컬럼 **없음**.
  - `grep -a resetPrivacyMetaByRawSn /app/app.jar` → 0건 (HEAD `27b6bb0d` 에서 추가된 `DeidentReportService:151-155` 코드가 배포본에 없음).
  - HEAD 커밋 `27b6bb0d` 는 `DeidentReportService.java`·`LsDataSrc.java`·`LsDataSrcRepository.java` 를 변경했으므로 **B-11 의 5-1 단계는 실행 중 백엔드에 존재하지 않는다**. (반면 `VideoStreamService`/`StreamUrlSigner`/`StreamSignatureFilter`/`VideoController` 는 해당 커밋 미변경 → B-10 live 결과는 HEAD 와 동치.)
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend sh -c "ls -l /app/app.jar; grep -a -o 'V1[23][0-9]__[a-z_]*' /app/app.jar | sort -u | tail -3"
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT version FROM public.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;"
  docker exec klid-postgres psql -U klid_user -d klid_system -c "\d public.ls_data_src" | grep -i incl_yn
  ```
- **영향**: TC-DEID-035 실동작 검증 불가(BLOCKED). 그 외 이번 회차의 다른 B-11 케이스는 변경 범위 밖 코드라 결과 유효하나, **후속 클러스터(특히 메타 수동입력·export 관련)는 stale 스택에서 위양성/위음성이 날 수 있음**.
- **수정 방향(제안)**: 검증 착수 전 `docker compose -f docker-compose.yml -f docker-compose.local.yml build klid-backend && up -d` 로 HEAD 재빌드 후 flyway 가 V130 을 적용했는지(`flyway_schema_history` 최신 버전) 확인하는 절차를 검증 프로토콜에 고정.

### [B-ISSUE-63] TC-STREAM-B15 — `/stream` 에 영상 단위 인가가 없어 배정되지 않은 WORKER 가 임의 영상을 재생할 수 있음
- **심각도**: MEDIUM (확인필요 — 의도된 광범위 허용인지 정책 확인 필요)
- **기대 동작(기대효과)**: 라벨링/검수 흐름과 동일하게 WORKER 는 **본인 배정 영상**만 열람 가능해야 한다(`LabelAccessGuard.verifyRawAccess` 와 동일 기준). 비식별본이라도 영상 열람은 개인정보 인접 자산이다.
- **현재 동작(이슈 내용)**: `backend/.../video/controller/VideoController.java:220`
  ```java
  @PreAuthorize("hasAnyRole('REVIEWER','WORKER') or hasAuthority('STREAM_SIGNED')")
  ```
  역할만 검사하고 `rawSn` 소유/배정 검증이 없다. `VideoStreamService.stream()` 내부에도 배정 검사 없음.
  - **실측**: 배정 이력이 전혀 없는 WORKER(sub=2002) 토큰으로 `rawSn=17`(다른 사용자 2001 배정) 스트리밍 → **206**. 배정 없는 25/24/23 도 206.
  - 서명 URL 경로도 동일 — `/stream-url` 은 role-gated 이므로 임의 WORKER 가 임의 rawSn 의 서명 URL 을 발급받을 수 있다(`VideoStreamService.java:114-141` 에 배정 검사 없음).
- **재현/확인 경로**:
  ```bash
  # 배정 0건 WORKER 토큰 발급
  curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"role":"WORKER","channel":"INTERNAL","userNo":"2002"}'
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $W2" \
    -H 'Range: bytes=0-100' http://localhost:18081/api/v1/videos/17/stream   # -> 206
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT user_no, raw_data_id, task_type_cd FROM public.ls_task_assignment WHERE user_no=2002;"  # -> 0 rows
  ```
- **영향**: 수평 권한 상승(IDOR, CWE-639 / OWASP API1:2023). 서빙 대상이 비식별본이라 원본 PII 직접 유출은 아니므로 CRITICAL 은 아니나, 내부 사용자 간 데이터 격리가 없다. `UNCERTAINTIES.md #4`(관제 조회 API 광범위 허용은 의도됨)와 동일한 정책 판단이 스트리밍에도 적용되는지 **확인 필요**.
- **수정 방향(제안)**: 의도된 허용이면 UNCERTAINTIES 에 명시 확정. 아니면 `/stream`·`/stream-url` 진입부에 `LabelAccessGuard.verifyRawAccess(rawSn, actor)` 를 적용(REVIEWER 전체 허용 / WORKER 본인 배정) 하고, 서명 URL 은 발급 시점 인가 결과가 서명에 이미 바인딩(userNo)되므로 추가 비용 없음.

### [B-ISSUE-64] 마킹 단계 rawSn 비식별 신고 미구현 (설계 대비 갭)
- **심각도**: LOW (기록 목적 — `UNCERTAINTIES.md #2` 확정에 따라 갭으로 기록)
- **기대 동작(기대효과)**: CLAUDE.md — 비식별 누락 신고는 **마킹 단계(rawSn 기준, `POST /v1/videos/{rawSn}/deident-report`)** 와 라벨링 단계(srcSn) 양쪽에서 가능해야 한다(마킹 단계는 "설계 타깃/planned" 로 표기됨).
- **현재 동작(이슈 내용)**: `POST /v1/videos/13/deident-report` → **404**. `grep -rn "videos/{rawSn}/deident-report" backend/src/main/java` → 0건. 신고 엔드포인트는 `DeidentReportController.java:91`(srcSn) 하나뿐.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:18081/api/v1/videos/13/deident-report \
    -H "Authorization: Bearer $RT" -H 'Content-Type: application/json' -d '{"reason":"x"}'   # -> 404
  ```
- **영향**: 마킹 화면(프레임 추출 이전 단계)에서 비식별 누락을 발견해도 신고 경로가 없어 작업자가 그대로 마킹을 진행하게 된다. 프레임 추출 후(srcSn 존재) 단계에서만 신고 가능.
- **수정 방향(제안)**: 구현 필요 여부를 R1 v1.14 대비로 확정한 뒤, 구현 시 `DeidentReportService.report` 를 rawSn 진입 오버로드로 분리(현재는 srcSn→rawSn 해석에 `LabelAccessGuard.verifyAndGet(srcSn)` 의존).

### [B-ISSUE-65] 증강 파생영상(WINTER/NIGHT/RAIN)이 `DE_IDENT_YN='N'` 으로 고착되어 스트리밍 불가
- **심각도**: LOW (기지의 planned 갭 재확인)
- **기대 동작(기대효과)**: 증강 파생영상도 비식별 원본 복사본이므로 재생 가능해야 한다.
- **현재 동작(이슈 내용)**: rawSn 20/21/22 (`ORGNL_RAW_SN=13`, 외부 증강 파생) 전부 `DE_IDENT_YN='N'`, `DATA_STTS_CD='FAILED'` → 스트리밍 **404**. CLAUDE.md 기재대로 "증강 적재 경로는 `VideoIngestedEvent` 미발행 — 선두 비식별 자동화 미연동(planned)" 상태가 데이터로 확인됨.
- **재현/확인 경로**:
  ```bash
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT raw_sn, orgnl_raw_sn, de_ident_yn, data_stts_cd FROM public.ls_data_raw WHERE orgnl_raw_sn=13;"
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $RT" http://localhost:18081/api/v1/videos/20/stream  # -> 404
  ```
- **영향**: 증강 결과 검수(SCR-AUG-002) 화면에서 영상 재생 불가. 단 fail-closed 방향(원본 노출 없음)이라 보안 위험은 없음.
- **수정 방향(제안)**: 증강 적재 경로에도 `VideoIngestedEvent` 발행 또는 파생 확정 시 `markDeidentified("Y")`(해상도 파생 `ResolutionPersistService:141` 과 동일 처리) 적용 여부를 결정.

---

## 부수 관찰 (결함 아님 / 참고)

- **과거 데이터 잔존**: rprtSn=1(rawSn=4)은 `RESOLVED` + 작업락 `RELEASED(MANUAL_DEIDENT_DONE)` 인데 `LS_DATA_RAW.DE_IDENT_YN` 이 여전히 `'F'`. 해당 resolve 는 2026-07-15 로, `'F'→'Y'` 복원 코드(`DeidentReportService:226-230`)가 도입되기 이전 데이터로 보임. 현행 코드 기준 회귀 아님.
- **`resolveOpenReports`**: OPEN 신고가 0건이어도 `workLockService.releaseRaw(...)` 와 캐시 evict 를 무조건 호출(`:287-289`). 멱등이라 무해하나 의도 확인 시 참고.
- **200 응답의 `Content-Range`**: Range 미지정 200 응답에도 `Content-Range: bytes 0-17/18` 이 붙는다(`ResourceRegionHttpMessageConverter` 동작). RFC 상 200 에 `Content-Range` 는 무의미하나 클라이언트 영향 미관측.

## 근거 라인 드리프트

**0건.** B-10/B-11 의 근거 `file:line` 38개 참조를 HEAD 소스와 전수 대조한 결과 모두 일치(`VideoStreamService.java`, `VideoController.java`, `DeidentReportService.java`, `DeidentReportController.java`).
단 `TC-STREAM-B16` 의 `VideoStreamService.java:252` 는 실코드가 아닌 javadoc 라인이고 실제 `unless` 절은 `:257` — 표기 자체는 `252,257` 로 되어 있어 드리프트로 계수하지 않음.

## 요약

- 총 **38건** / PASS **36** / FAIL **0** / PARTIAL **1**(TC-STREAM-B15) / BLOCKED **1**(TC-DEID-035, 환경 stale) / N/A 0 / 확인필요 0
  - PASS 36 중 **실동작 직접 확인 25건**, 정적+기존 테스트 커버 11건(런타임 설정 변경·파일 mtime 조작·라벨 파괴가 필요해 live 미실행)
- 근거 라인 드리프트: **0건**
- **PII 유출 경로 미발견** — 원본 노출 우회(비식별 미완료/'F'/'N'), 경로 이탈(CWE-22), 서명 우회(u·exp·sig·rawSn 변조 및 인코딩/중복 파라미터 8종), Range 경계 초과, resolve 무검증 통과를 모두 반증 시도했으나 전부 fail-closed 로 차단됨.
- 신규 이슈 **5건**: HIGH 1([B-ISSUE-61] 해상도 파생 스트리밍 403 전면 차단), MEDIUM 2([B-ISSUE-62] 검증 스택 stale, [B-ISSUE-63] `/stream` 영상 단위 인가 부재), LOW 2([B-ISSUE-64] 마킹단계 신고 미구현, [B-ISSUE-65] 증강 파생 `DE_IDENT_YN='N'` 고착)
