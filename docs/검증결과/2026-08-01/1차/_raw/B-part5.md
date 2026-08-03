# B 클러스터 part5 — B-8(FfmpegFrameExtractor 19건) + B-10(비디오 스트리밍 22건) = 41건

- 검증일 2026-08-01 · 코드 기준 `qa-0801` @ 56d30478 · 스택 backend V146(구버전, 사용자 승인 하 진행)
- 검증 방식: **실동작 최우선** — 실제 파이프라인 구동(신규 rawSn 34·36 업로드→비식별→마킹→프레임추출), curl 실요청(Range/서명URL/경로조작/심링크 치환), DB 실측, 컨테이너 파일시스템 실측
- 환경 버전 격차 영향: B-8/B-10 은 `LS_DATA_INGEST` 리팩터(b2b44f0e~56d30478) 범위 밖 → **BLOCKED 없음**

## 판정 집계

| 판정 | 건수 |
|---|--:|
| PASS | 39 |
| PARTIAL | 2 |
| FAIL | 0 |
| BLOCKED | 0 |
| **합계** | **41** |

> ⚠ PARTIAL 2건(TC-STREAM-B04 · TC-STREAM-B20)은 **동일한 하나의 결함**(B-ISSUE-81, 스트리밍 경로 심링크 우회)에서 나온 것이며 실동작으로 **원본(비식별 이전) 영상이 200 서빙되는 것을 재현**했다. 심각도는 HIGH.

---

## B-8. FfmpegFrameExtractor (원본+비식별 2벌, co-locate, 경계) — 19건

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-BATCH-100 | PASS | [정적] `FfmpegFrameExtractor.java:133-136` — `marks.isEmpty()` → `INVALID_INPUT`. 커버: `FfmpegFrameExtractorTest#extractByMarks_emptyMarks_rejected` | 근거 라인 일치 |
| TC-BATCH-101 | PASS | [정적] `:144-147` — `frames.isEmpty()` → `INTERNAL_ERROR` | ⚠ 실제로는 **도달 불가한 심층방어**다. `extractByMarks` 는 mark 1건당 정확히 1행을 `saved` 에 넣고 실패 시 `IOException`→`CustomException` 으로 던지므로 "marks 非공 & 결과 0건" 상태가 만들어지지 않는다. ErrorCode 자체는 기대와 일치 |
| TC-BATCH-102 | PASS | [정적] `:177-179` — `raw==null / rawFilePathNm blank` → `INVALID_INPUT` | |
| TC-BATCH-103 | PASS | [정적] `:185-188` — `!"Y".equals(deIdntfYn)` → `INVALID_INPUT("비식별이 완료되지 않은 영상입니다")`. 커버: `..._notDeidentified_rejected` | 가드 순서 = 메타(177) → marks(181) → 비식별(185) → 원본존재(190) |
| TC-BATCH-104 | PASS | [정적] `:190-193` — `frameWriter.sourceExists(source)` false → `INVALID_INPUT` | |
| TC-BATCH-105 | **PASS** | **[실동작]** 신규 rawSn=34 를 파이프라인에 태우고 `LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM` 를 `/etc/passwd`(허용 base 전부의 밖)로 오염 후 AUTO 마킹 트리거. 로그: `WARN [Batch][FrameExtract] deid path rejected (base/realpath) rawSn=34 — RAW only` (**경로 원문 미노출, rawSn 만**). DB: `ls_data_src` 5행 전부 `de_idntf_src_file_path_nm=NULL`, `src_file_path_nm=/app/storage/raw/frames/raw/34/frame-N.jpg` → **원본 폴백 없음** 확인 | 판정축이 `readableDeidVideoBases`(`:322`)임도 정적 확인 |
| TC-BATCH-106 | PASS | [정적] `:216-225` — 파일 부재(`else if` 216) / procLog 경로 null(221-225) 양쪽 WARN + RAW only. 커버: `..._noDeidVideo_rawOnly`, `FfmpegFrameExtractorDeidPersistIT#execute_비식별_procLog없으면_RAW만_영속되고_비식별경로는_NULL_유지_무회귀` | 파일 부재 로그는 `maskName()` 해시로만 남김(`:219`) |
| TC-BATCH-107 | **PASS** | **[실동작]** rawSn=26(기존 드라이브)·30·33·34 실측. rawSn=26 `ls_data_src` 5행 **전부** `de_idntf_src_file_path_nm` non-null(`/app/storage/deidentified/frames/deid/26/frame-N.jpg`). 코드상 비식별 프레임을 `LsDataSrc.create` **이전에** 쓰고 6-arg create 에 경로 포함(`:260-271`) → PARTIAL 회귀 없음 | |
| TC-BATCH-108 | **PASS** | **[실동작]** rawSn=26: `vdo_frm_no` = 0/30/60/90/120(intervalFrames=30), `sht_dt` = 17:59:39→40→41→42→43 (정확히 1000ms 간격) = `round(frameIndex×1000/30)`. pin fps 우선 경로 `:234`(`effectiveFps(pinnedFps,…)`) → `:248` `Math.round(mark.frameIndex()*1000.0/fps)` | ⚠ 근거 드리프트: 카탈로그 `234,266,393-398` 중 **seek 계산 실위치는 `:248`**(266 아님) |
| TC-BATCH-109 | PASS | [정적] `:393-398` — `pinnedFps` null/NaN/Inf/≤0 → `fpsResolver.resolveFps`(미상 30.0). 커버: `..._fpsAbsent_fallback30_noRegression` | |
| TC-BATCH-110 | PASS | [정적] `:377-384` — `resolved.startsWith(base)` 위반 시 `INVALID_INPUT` | ⚠ 실제 입력이 `FrameKind` enum + `Long rawSn` 뿐이라 **현재 호출 형상에서는 도달 불가**한 심층방어. 가드 자체는 기대대로 존재 |
| TC-BATCH-111 | **PASS** | **[실동작]** 컨테이너 파일시스템 실측 — `/app/storage/raw/frames/raw/{4,17,26,27,30,31,33,34,35,36,38}` · `/app/storage/deidentified/frames/deid/{17..27,…}` 로 **디렉터리가 실제 분기**. DB rawSn=26 두 경로 상이 확인 → **원본 덮어쓰기 0** | |
| TC-BATCH-112 | **PASS** | **[실동작]** `ls_data_src_hstry` srcSn 296~300 각각 `CREATED` + `DEID_ATTACHED` **2건**(hstry_seq 259~268) | |
| TC-BATCH-113 | **PASS** | **[실동작]** rawSn=26 의 비식별 영상은 co-locate 위치 `/app/storage/raw/autolabel-test/26/deid/…-mask.mp4`(= `deidentified-path` **밖**)인데도 채택돼 `DE_IDNTF_SRC_FILE_PATH_NM` 5행 전부 채워짐 → 구 결함(자기 산출물을 신뢰불가 판정해 항상 RAW only) **재발 없음** | rawSn=34·36 도 동일 co-locate 형상에서 정상 채택(오염/심링크 시에만 거부) |
| TC-BATCH-114 | PASS | [실동작+정적] 구 위치 행(`/app/storage/deidentified/videos/augment/…`, rawSn 18·19·24·25)이 현재도 정상 소비됨(스트리밍 200 실측). 판정 코드 `VideoArtifactRootResolver:308-320`(readableDeidVideoDirs) + `:337-346`(readableDeidVideoBases, `deidentifiedBase` 를 무조건 포함) | ⚠ 드리프트: 카탈로그 `308-327` → 실제 `308-320` |
| TC-BATCH-115 | **PASS** | **[실동작]** rawSn=36 의 비식별 산출물 파일을 **원본 영상 심링크**로 치환(`…-mask.mp4 -> /app/storage/raw/autolabel-test/…mp4`) 후 마킹 트리거 → `WARN … deid path rejected (base/realpath) rawSn=36 — RAW only`, `ls_data_src` 5행 `de_idntf_src_file_path_nm=NULL`. **원본 프레임이 "비식별본"으로 적재되지 않음** 확인(CWE-59/359 방어 성립). 코드: `:356-368` `underBaseWithRealPath` → `VideoArtifactRootResolver.verifyRealPathUnder(:458-466)`, 실패는 예외가 아니라 `false` | ★ **동일 심링크를 스트리밍 경로로 요청하면 200 으로 원본이 나간다** → B-ISSUE-81 |
| TC-BATCH-116 | PASS | [정적] `VideoArtifactRootResolver.realOrNearest(:474-498)` — 미존재 대상은 **가장 가까운 실재 조상**을 `toRealPath()` 로 접은 뒤 판정하므로 중간 세그먼트 심링크가 걸린다. 해석 불가는 `FORBIDDEN`(fail-secure). 커버: `..._deidIntermediateSegmentSymlink_rejectedRawOnly` | |
| TC-BATCH-117 | PASS | [정적] `:316-320` — `artifactRootResolver == null` → `underBaseWithRealPath(deidPath, baseDeidPath)` 단독 판정. 허용 범위가 **넓어지지 않음**(구 동작 = 더 좁은 축) | |
| TC-BATCH-118 | PASS | [정적] `:321-326` — `readableDeidVideoBases` `RuntimeException` catch → 구 동작 폴백, 파이프라인 미중단. 리졸버 내부에서도 `:340-346` 이 조용히 후보 축소(fail-secure) | |

---

## B-10. 비디오 스트리밍 (Range, 비식별본만 서빙) — 22건

> 실동작 기준선: rawSn=26(비식별 완료, co-locate deid, 파일 50,854 bytes) · 9104(`'N'`) · 9112(`'F'`) · 9114(`'Y'` + procLog 없음) · 18/24/25(파생 트리) · 9113(합성 procLog 주입용, 검증 후 원복)

| ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-STREAM-B01 | PASS | [실동작] rawSn=9104(`DE_IDENT_YN='N'`) → `404 {"errorCode":"NOT_FOUND","message":"비식별 처리 미완료"}`. 코드 `VideoStreamService:226-230` | 원본 노출 0 |
| TC-STREAM-B02 | PASS | [실동작] rawSn=99999 → `404 "영상을 찾을 수 없습니다."` (`:411-412`) | |
| TC-STREAM-B03 | PASS | [실동작] 9112(`'F'`) → 404 / 9104(`'N'`) → 404, 양쪽 동일 메시지(상태 오라클 없음). 코드 `:415-419` | |
| TC-STREAM-B04 | **PARTIAL** | [실동작] lexical 축은 **완전 차단** — procLog 경로를 ①`/etc/passwd` ②`/app/storage/deidentified/../../etc/passwd` ③`/app/storage/raw/seed/clip-9103.mp4`(원본) ④`…/augment/4/19/../../../../raw/seed/clip-9103.mp4` 로 오염해 4종 전부 `404`. **그러나 `resolveSafe(:470-485)` 는 `startsWith` lexical 검사만 하고 실경로 재검증이 없어, 허용 base 안의 심링크가 원본을 가리키면 통과한다 — 실증(아래 B-ISSUE-81)** | ⚠ **드리프트**: 기대 `FORBIDDEN` 이나 실제는 `NOT_FOUND`. 코드 주석(`:466-468`)이 "S7 — 존재/권한 구분 노출 방지" 로 **의도적 정규화**임을 명시 → 카탈로그 기대값을 정정할 것 |
| TC-STREAM-B05 | PASS | [실동작] 9113 procLog 를 base 안의 **부재 파일**(`…/augment/4/19/MISSING.mp4`)로 주입 → `404 "비식별 영상 파일이 존재하지 않습니다."`, 재요청도 404. 이후 실재 파일로 정정하니 **즉시 200** → 예외가 캐시되지 않음 확인 (`:359-363`) | |
| TC-STREAM-B06 | PASS | [실동작] Range 없이 GET → `200`, `Accept-Ranges: bytes`, `Content-Length: 50854`, body 50,854 bytes | |
| TC-STREAM-B07 | PASS | [실동작] `bytes=0-`→206/50854, `bytes=0-99`→206/100 `Content-Range: bytes 0-99/50854`, `bytes=100-199`→206/100, `bytes=50853-`→206/1, `bytes=-100`→206/`50754-50853`, `bytes=0-99999999`→206/전체(파일<청크상한) | 다중 Range(`bytes=0-99,200-299`)는 첫 범위만 206 반환 — `ranges.get(0)` 규약대로 |
| TC-STREAM-B08 | PASS | [실동작] `bytes=999-0`·`bytes=abc`·`bytes = 0-10`·`bytes=-0` → 전부 `416` + `Content-Range: bytes */50854` (`:240-245`) | |
| TC-STREAM-B09 | PASS | [실동작] `bytes=50854-`(=total)·`bytes=999999999-` → `416` (`:251-255`) | 경계 `50853-` 은 206 — off-by-one 없음 |
| TC-STREAM-B10 | PASS | [정적] `:314-319` — `<1MB`/0/음수 → `DEFAULT_CHUNK_SIZE` 8MB. 실효 설정 확인: `STORAGE_STREAM_CHUNK_SIZE` 미설정 → yml 기본 `8388608`. 커버: `VideoStreamServiceTest` 3건(0이하/음수/2MB) | 파일 50KB 라 상한 실도달은 미관측 |
| TC-STREAM-B11 | PASS | [정적] `:64,314-319` — `Math.min(streamChunkSize, MAX_CHUNK_SIZE=64MB)`. 커버: `effectiveChunkSize가_상한64MB로_클램프됨`, `비정상_대형_chunkSize에도_long오버플로_없이_안전서빙` | |
| TC-STREAM-B12 | PASS | [실동작] 9112(`'F'`)·9104(`'N'`) `GET /stream-url` → `404`(`:174-177`, `:189`) | ⚠ 부수 관찰: rawSn=9114(`'Y'` + 성공 procLog **없음**)는 `stream-url` **200**(서명 발급됨)인데 `stream` 은 404 → B-ISSUE-82 |
| TC-STREAM-B13 | PASS | [정적] `:191-196` — `!streamUrlSigner.isConfigured()` → `SERVICE_UNAVAILABLE`(503), 메시지에 내부정보 없음. 커버: `서명URL_시크릿_미설정시_503_SERVICE_UNAVAILABLE` | 로컬은 시크릿이 설정돼 있어 실동작 재현 불가(코드/설정 수정 금지) |
| TC-STREAM-B14 | **PASS** | **[실동작]** 발급 URL `?exp=…&u=1001&sig=…` + `Set-Cookie: klid_stream_nonce=…; HttpOnly; SameSite=Lax; Path=/api/v1/videos`. ①쿠키+무Bearer → **200** ②쿠키 없이 → **401** ③`u=1001→2001` → **401** ④`sig` 1자 변조 → **401** ⑤`exp` 연장 → **401** ⑥경로 rawSn 26→25/9112 스왑 → **401** ⑦파라미터명 대소문자 변형(`SIG=`) → **401** | 재사용 차단의 실효 축이 nonce 쿠키임이 실증됨 |
| TC-STREAM-B15 | PASS | [실동작] `@PreAuthorize("hasAnyRole('REVIEWER','WORKER') or hasAuthority('STREAM_SIGNED')")`(`VideoController:244`) 통과 + **영상 단위 배정 인가 실동작 확인** — WORKER(sub=2001): 배정된 26 → **200**, 미배정 25·19 → **403 "본인에게 배정되지 않은 영상입니다."**, 무토큰 → 401 | ⚠ **카탈로그 비고가 낡음** — "영상 단위 배정 검증은 없다(B-ISSUE-63 미해소)"는 사실이 아니며 `VideoController:210,253` 의 `labelAccessGuard.verifyRawAccess` 로 **해소됨**. B-ISSUE-84 |
| TC-STREAM-B16 | **PASS** | **[실동작]** rawSn=9114(procLog 없음) → 404. 그 상태에서 성공 procLog 1행을 INSERT 하자 **즉시 200/50854** → `unless="#result == null"`(`:348`)로 null 이 캐시되지 않아 stale NOT_FOUND 고착 없음. 검증 후 행 삭제 | |
| TC-STREAM-B17 | **PASS** | **[실동작]** 합성 영상 rawSn=36 을 먼저 스트리밍해 캐시 warm(200) → `POST /v1/videos/36/deident-report` 201 → **즉시 404**(`DE_IDENT_YN='F'`). resolve 후 다시 200/50854. 코드 `DeidentReportService.java:248,395,454` `streamMetaCacheEvictor.evictAfterCommit` | ⚠ 드리프트: 파일 경로가 `label/service/DeidentReportService.java`(카탈로그는 `DeidentReportService.java:244-250`, 실제 `:248`) |
| TC-STREAM-B18 | **PASS** | **[실동작]** Range 무 → `200` + `Cache-Control: no-store`, Range 유 → `206` + `Cache-Control: no-store`. 코드 `:271,281,302-304`. 416 응답도 Spring Security 기본 헤더로 `no-cache, no-store, max-age=0, must-revalidate` | ⚠ 부수: 200 응답에도 `Content-Range: bytes 0-50853/50854` 가 붙는다(RFC 7233 비적합) → B-ISSUE-85 |
| TC-STREAM-B19 | **PASS** | **[실동작]** rawSn=18 을 2회 스트리밍해 `stream-meta` 캐시 warm(200,200) → **DB 직접 `de_ident_yn='F'`** 로 전환(캐시 evict 없음) → `stream` **404**, `stream-url` **404**. 즉 게이트가 캐시 **앞**(매 요청 DB projection)에서 평가됨(`:221`, `:143-148`). 원복 후 200 | 캐시 우회 불가 확정 |
| TC-STREAM-B20 | **PARTIAL** | [실동작] 2-way allowlist 자체는 성립 — co-locate(26: `/app/storage/raw/autolabel-test/26/deid/…`) **200**, 구 위치(25: `/app/storage/deidentified/videos/augment/18/25/WINTER.mp4`) **200**. **그러나 "프레임 추출기와 동일 축(가드 이원화 제거)" 단언은 미성립** — 추출기는 `underBaseWithRealPath`(realpath 재검증)를 추가로 걸지만 스트리밍 `resolveSafe` 는 lexical 만이다. 같은 심링크 입력에 대해 추출기는 거부, 스트리밍은 200(실증) | B-ISSUE-81 |
| TC-STREAM-B21 | PASS | [실동작] `'F'` 상태에서 `GET /stream` → **404**(412 아님), `GET /stream-url` → **404**. `'N'`·부재 영상과 코드·메시지가 동일해 상태 오라클(CWE-209) 없음 | |
| TC-STREAM-B22 | **PASS** | **[실동작]** 부모 rawSn=18 을 `'F'` 로 두는 동안 파생 rawSn=24·25(`ORGNL_RAW_SN=18`, 자기 행 `'Y'`) → **200 서빙**. 게이트 쿼리 `VideoRepository:50-51 SELECT r.deIdntfYn … WHERE r.rawSn = :rawSn` 에 `ORGNL_RAW_SN` 없음 확인 → 확정 정책대로 결함 아님 | |

---

## 이슈

### [B-ISSUE-81] TC-STREAM-B04 / TC-STREAM-B20 — 영상 스트리밍의 비식별 경로 가드가 lexical 전용이라 심링크 치환으로 **원본(비식별 이전) 영상이 그대로 서빙된다**
- **심각도**: **HIGH**
- **기대 동작(기대효과)**: `GET /v1/videos/{rawSn}/stream` 은 **항상 비식별 영상만** 내보내야 한다(`CLAUDE.md` — "비식별 미완료 시 NOT_FOUND 로 원본 노출 차단"). 프레임 이미지 4경로에 대해서는 이미 *"`StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기로 검증하고, 판정이 돌려준 실경로(`toRealPath()`)를 그대로 사용한다 — lexical 경로로 검증하고 lexical 경로로 여는 구현은 금지"* 가 구속 규칙으로 명문화돼 있다. **비식별 영상 파일도 동일 신뢰 경계**(외부 비식별 벤더 KPST 가 공유 마운트에 직접 산출물을 쓰는 디렉터리)에 있으므로 같은 규약이 적용돼야 한다.
- **현재 동작(이슈 내용)**: `VideoStreamService.resolveSafe` 가 `normalize()` + `startsWith` **lexical 검사만** 수행하고, 반환한 lexical 경로를 그대로 `UrlResource` 로 연다. 실경로 재검증(`toRealPath`)이 없다.
  ```java
  // backend/.../video/service/VideoStreamService.java:470-485
  static Path resolveSafe(List<Path> baseDirs, String filePath) {
      Path candidate = Paths.get(filePath);
      for (Path baseDir : baseDirs) {
          Path resolved = candidate.isAbsolute() ? candidate.normalize() : baseDir.resolve(candidate).normalize();
          if (resolved.startsWith(baseDir)) { return resolved; }   // ← 실경로 재검증 없음
      }
      throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
  }
  ```
  같은 DB 값(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`)을 읽는 형제 소비자 `FfmpegFrameExtractor` 는 `underBaseWithRealPath`(`:356-368`) → `VideoArtifactRootResolver.verifyRealPathUnder`(`:458-466`) 로 **실경로를 재검증**한다. 프레임 이미지 서빙도 `StorageSubtreePolicy.verifyDeidentifiedFile`(`:201-215`)이 `realResolved = resolved.toRealPath()` 후 서브트리를 재판정하고 **실경로를 돌려준다**. **스트리밍만 빠져 있다** — 즉 "가드가 갈라져 하나씩 샌다"가 이 경로에서 실제로 일어나고 있다.
- **재현/확인 경로** (2026-08-01 실측, 합성 영상 rawSn=36 · 검증 후 원복 완료):
  ```bash
  # 1) 비식별 산출물 파일을 원본 영상 심링크로 치환 (허용 base 안, lexical 경로 불변)
  D=/app/storage/raw/autolabel-test/36/deid/378d1b22-…-mask.mp4
  O=/app/storage/raw/autolabel-test/378d1b22-….mp4          # 원본(비식별 이전)
  docker exec klid-backend sh -c "mv $D $D.real && ln -s $O $D"

  # 2) 프레임 추출(배치) — 차단됨 ✅
  curl -X POST /api/v1/videos/36/markings -d '{"mode":"AUTO","intervalFrames":30}'
  # log: WARN [Batch][FrameExtract] deid path rejected (base/realpath) rawSn=36 — RAW only
  # DB : ls_data_src 5행 de_idntf_src_file_path_nm = NULL

  # 3) 같은 심링크를 스트리밍 — 통과됨 ❌
  curl -o out.bin -w '%{http_code} %{size_download}' /api/v1/videos/36/stream -H "Authorization: Bearer $REVIEWER"
  # → 200 20590   (= 원본 mp4 크기. 정상 비식별본은 50854)
  ```
  같은 우회는 `/stream-url` 로 발급한 서명 URL 경유(무인증 재생)에서도 성립한다.
- **영향**: **CWE-59(Link Following) + CWE-367(TOCTOU) + CWE-359(개인정보 노출)**. 비식별 산출 디렉터리는 외부 비식별 벤더(KPST)가 공유 마운트로 직접 쓰는 영역이고 co-locate 전환으로 판정 대상이 관제 NAS 하위까지 넓어졌다. 그 디렉터리에 심링크 1개를 심으면 **마스킹 전 원본 영상이 "비식별 영상"으로 마킹 화면·검수 화면에 재생**된다. 신고 게이트(`DE_IDNTF_YN`)는 이 경로를 `'Y'` 로 보므로 뒤에서 막아주지 않는다. 자동 테스트에도 이 축이 없다 — `FfmpegFrameExtractorTest` 는 CWE-59 케이스 3건을 갖고 있으나 `VideoStreamServiceTest`(37건)·`DeidentReportStreamGateIT` 에는 심링크 케이스가 **0건**이다.
- **수정 방향(제안)**: `VideoStreamService.resolveSafe` 가 lexical 통과 후 **`VideoArtifactRootResolver.verifyRealPathUnder(resolved, baseDir)`(이미 존재하는 같은 정적 판정기)를 호출**하고, **판정이 돌려준 실경로**로 `StreamMeta.path` 를 채우도록 한다(판정 대상과 사용 대상 일치 — TOCTOU 차단). 실패는 예외가 아니라 다음 base 후보로 넘어가고, 모든 후보 실패 시 기존대로 `NOT_FOUND`(경로 원문 미노출). 판정 결과가 `stream-meta` 캐시에 실리므로 **캐시 채우기(miss) 시점에 1회만** 수행되어 핫패스 비용도 없다. 회귀 가드로 `VideoStreamServiceTest` 에 "비식별 파일이 원본 심링크면 404" · "중간 세그먼트 심링크도 404" 2건을 추가(추출기 테스트와 동일 골격). ⚠ 구현은 하지 않았다.

### [B-ISSUE-82] TC-STREAM-B12 — 성공 procLog 가 없는 영상에도 서명 스트림 URL 이 발급된다(발급 200 / 재생 404 불일치)
- **심각도**: LOW
- **기대 동작(기대효과)**: 서명 URL 발급은 "재생 가능한 영상"에 대해서만 성공해야 한다. `issueSignedUrl` 의 주석도 *"노출본 대상 URL 발급을 사전 차단한다"* 를 목적으로 명시한다.
- **현재 동작(이슈 내용)**: `issueSignedUrl`(`VideoStreamService:166-206`)은 `DE_IDNTF_YN=='Y'` 와 신고 게이트만 확인하고 **`LS_DEIDENT_PROC_LOG` 성공 행 존재는 확인하지 않는다**. 반면 `stream` 은 `resolveDeidLocation`(`:423-427`)에서 procLog 부재 시 null → 404.
  ```
  rawSn=9114 (de_ident_yn='Y', 성공 procLog 0건)
    GET /v1/videos/9114/stream-url → 200 {"url":"…?exp=…&u=1001&sig=…"}
    GET /v1/videos/9114/stream     → 404 "비식별 처리 미완료"
  ```
- **재현/확인 경로**: 위 curl 2줄(실측 2026-08-01). DB: `select count(*) from ls_deident_proc_log where data_raw_sn=9114 and proc_stts_cd='SUCCEEDED'` → 0.
- **영향**: 보안 취약점 아님(재생 자체는 404 로 막힘). FE 가 URL 을 받아 `<video>` 에 물린 뒤 404 로 실패하므로 사용자에게 원인 불명 재생 실패로 보인다. 또한 발급 성공/실패가 procLog 유무를 노출하지 않는다는 점에서 정보 노출도 아님.
- **수정 방향(제안)**: `issueSignedUrl` 에서 `resolveDeidPath(rawSn) == null` 이면 `stream` 과 동일하게 `NOT_FOUND` 로 거부(같은 메시지 유지 — 상태 오라클 방지). 또는 카탈로그에 "발급은 플래그 축, 재생은 산출물 축"으로 의도된 분리임을 명문화. ⚠ 구현은 하지 않았다.

### [B-ISSUE-83] TC-STREAM-B05 파생 관찰 — `stream-meta` 캐시가 사라진 파일을 가리키면 404 가 아니라 **500 INTERNAL_ERROR**
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 파일이 실재하지 않으면 `NOT_FOUND`(404)로 정규화돼야 한다(`:359-363` 의 규약). 캐시 히트 여부가 응답 코드를 바꾸면 안 된다.
- **현재 동작(이슈 내용)**: 파일 존재 확인은 `resolveStreamMeta`(캐시 **뒤**) 안에서만 이뤄지므로, 캐시가 채워진 뒤 파일이 사라지면 `stream()` 은 그대로 `UrlResource` 를 열고 `ResourceRegionHttpMessageConverter` 가 write 시점에 실패해 500 이 된다.
  ```
  (rawSn=20, 캐시 warm 상태에서 대상 파일 제거)
  GET /v1/videos/20/stream → 500 {"errorCode":"INTERNAL_ERROR"}   … TTL 5분 경과 후 200 복귀
  ```
- **재현/확인 경로**: 캐시를 warm 한 뒤 `LS_DEIDENT_PROC_LOG` 경로가 가리키는 파일을 삭제/이동하고 5분 TTL 안에 재요청. (본 검증에서는 B-ISSUE-81 재현 과정의 부수 효과로 관측했으며 원복 완료.)
- **영향**: 기능 영향만. 정상 운영에서는 비식별본이 불변이므로 발생 빈도가 낮고, 신고/해소/재비식별 3경로는 `StreamMetaCacheEvictor.evictAfterCommit` 로 무효화된다. 다만 **외부 벤더가 파일을 교체/정리하는 형상**(공유 마운트)에서는 재현 가능하며, 5xx 는 모니터링 알람을 오염시킨다.
- **수정 방향(제안)**: `stream()` 에서 `region` 생성 직전에 `Files.isReadable(meta.path())` 를 1회 확인해 실패 시 캐시를 evict 하고 404 로 정규화하거나, `IOException` 을 `NOT_FOUND` 로 매핑. ⚠ 구현은 하지 않았다.

### [B-ISSUE-84] 카탈로그 정합 — TC-STREAM-B15 비고(B-ISSUE-63 미해소) · TC-STREAM-B04 기대값 · 근거 file:line 4건 드리프트
- **심각도**: LOW (카탈로그 자체의 정합성 결함 — 오탐/누락 유발)
- **기대 동작(기대효과)**: 케이스의 기대결과·근거가 현재 구현과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**:
  1. **TC-STREAM-B15 비고 무효** — "⚠ 영상 단위 배정 검증은 없다(B-ISSUE-63 미해소 — 현재 동작 고정)"는 사실이 아니다. `VideoController:210`(stream-url)·`:253`(stream) 에 `labelAccessGuard.verifyRawAccess(rawSn, actor)` 가 **진입부**(캐시 앞)에 배선돼 있고, 실동작으로 WORKER 미배정 영상 403 을 확인했다. `UNCERTAINTIES.md` "미해소 이월 이슈" 표의 B-ISSUE-63 행도 함께 해소 처리 필요.
  2. **TC-STREAM-B04 기대값 무효** — 기대 `FORBIDDEN` 이나 구현은 `NOT_FOUND` 로 **의도적 정규화**(`VideoStreamService:466-468` 주석: 존재/권한 구분을 응답으로 알려주지 않는 편이 원본 미노출 정책과 동급). 기대결과를 `NOT_FOUND` 로 정정할 것.
  3. **근거 드리프트**: TC-BATCH-108 `FfmpegFrameExtractor.java:266` → 실제 seek 계산은 `:248` / TC-BATCH-114 `VideoArtifactRootResolver.java:308-327` → 실제 `readableDeidVideoDirs` 는 `:308-320` / TC-STREAM-B17 `DeidentReportService.java:244-250` → 실제 파일은 `label/service/DeidentReportService.java` 이고 evict 호출은 `:248`(+`:395`,`:454`) / TC-STREAM-B15 근거가 `VideoController.java:@PreAuthorize(stream)` 로 라인 없음 → `:244`(+인가 `:253`).
- **재현/확인 경로**: 위 file:line Read 및 `curl -H "Authorization: Bearer $WORKER" /api/v1/videos/25/stream` → `403 "본인에게 배정되지 않은 영상입니다."`
- **영향**: 카탈로그를 근거로 재검증하는 다음 회차에서 **미해소 이슈를 이월 유지**하거나(B-ISSUE-63) **정상 동작을 FAIL 로 오판**(B04)할 수 있다.
- **수정 방향(제안)**: `docs/test-cases/B-batch-deidentify.md` 의 해당 4개 셀과 `UNCERTAINTIES.md` 이월 표를 정정. ⚠ 본 검증에서는 파일을 수정하지 않았다.

### [B-ISSUE-85] TC-STREAM-B18 파생 관찰 — 200 전체 응답에도 `Content-Range` 헤더가 붙는다(RFC 7233 비적합)
- **심각도**: LOW
- **기대 동작(기대효과)**: `Content-Range` 는 206(또는 416)에서만 의미가 있다. 200 응답의 `Content-Range` 는 RFC 7233 §4.2 상 무의미하며 일부 엄격한 클라이언트/프록시가 오해할 수 있다.
- **현재 동작(이슈 내용)**: Range 헤더 없는 요청 응답 헤더 실측 —
  ```
  HTTP/1.1 200
  Accept-Ranges: bytes
  Cache-Control: no-store
  Content-Range: bytes 0-50853/50854     ← 200 인데 부착됨
  Content-Length: 50854
  ```
  원인은 Range 무 경로(`VideoStreamService:276-282`)도 전체 파일을 `ResourceRegion` 으로 감싸 반환하고, `ResourceRegionHttpMessageConverter` 가 write 시점에 `Content-Range` 를 무조건 add 하기 때문.
- **재현/확인 경로**: `curl -D - -o /dev/null http://localhost:18081/api/v1/videos/26/stream -H "Authorization: Bearer $REVIEWER"`
- **영향**: 기능 영향 관측되지 않음(브라우저 재생 정상). 규격 적합성·프록시 캐시 상호작용 관점의 위생 이슈.
- **수정 방향(제안)**: Range 무 경로는 `ResourceRegion` 대신 `UrlResource` 자체를 body 로 반환(`ResourceHttpMessageConverter` 경유)하거나, 현 동작을 의도로 문서화. ⚠ 구현은 하지 않았다.

---

## 검증 중 만든 상태 변경 (전부 원복 또는 합성 데이터)

| 대상 | 변경 | 원복 |
|---|---|---|
| `ls_data_raw` rawSn=18 `de_ident_yn` | `'Y'`→`'F'`→`'Y'` (B19/B22 실동작) | ✅ `'Y'` |
| `ls_deident_proc_log` proc_log_sn=21 경로 | EVIL 심링크 경로로 오염 (B04 심링크 실증) | ✅ 원경로 복원, 심링크 삭제 |
| `ls_deident_proc_log` data_raw_sn=9113/9114 | 합성 행 INSERT (B05/B16) | ✅ 전량 DELETE |
| rawSn=34 procLog 경로 | `/etc/passwd` 오염 (TC-BATCH-105) | ✅ 원경로 복원 |
| rawSn=36 비식별 파일 | 원본 심링크로 치환 (TC-BATCH-115 / B-ISSUE-81) | ✅ 실파일 복원 |
| rawSn=36 신고 | `POST /deident-report` 201 → `POST /deident-reports/13/resolve` 200 (B17) | ✅ `'Y'` 복귀, 스트리밍 200 |
| 신규 rawSn 34·36 (`QA0801-B8-105`, `QA0801-B8-115`) | 합성 검증 영상 2건 신규 적재 | 잔존(합성 데이터). rawSn=36 의 `ls_data_src` 5행은 심링크 실험 결과 `de_idntf_src_file_path_nm=NULL` 로 남음 |

## 자동 테스트 커버 대조 (`_raw/test-baseline.md`: backend 4,755 tests / 실패 0 / skip 5)

| 파일 | 관련 케이스 | 비고 |
|---|---|---|
| `batch/step/FfmpegFrameExtractorTest.java` (30여 건) | TC-BATCH-100~118 대부분 | CWE-59 심링크 3건 포함, 전량 통과 |
| `batch/step/FfmpegFrameExtractorDeidPersistIT.java` (2건) | TC-BATCH-107 / 106 | 비식별 경로 실영속·NULL 무회귀 |
| `video/VideoStreamServiceTest.java` (37건) | TC-STREAM-B01~B14, B16, B18, B20~B22 | **심링크(CWE-59) 케이스 0건** ← B-ISSUE-81 의 미탐 원인 |
| `video/DeidentReportStreamGateIT.java` (2건) | TC-STREAM-B17, B19, B22 | 캐시 warm 후 신고 차단 커버 |
| `video/VideoStreamAssignmentAuthorizationTest.java` | TC-STREAM-B15 | B-ISSUE-63 해소 회귀 가드 |
| `video/StreamSignedUrlControllerTest.java` | TC-STREAM-B13, B14 | |
| `common/cache/StreamMetaCacheEvictorTest.java` | TC-STREAM-B16, B17 | |
