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
