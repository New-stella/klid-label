# F 클러스터(포털) part3 — 3차 전수 검증 결과

- **대상**: `docs/test-cases/F-portal.md` **F-3**(58~84행, 활성 16 + 폐기 4) + **F-8**(180~218행, 32건) = **52건**
- **회차**: 2026-08-03 3차 / 검증 시각 2026-08-04 02:15~02:35 (KST)
- **환경**: `_raw/stack-bringup.md` 기준 풀스택 기동 상태 (backend `localhost:18081` context-path `/api`, PostgreSQL `public` 스키마, mock-server :9400) — 재빌드된 HEAD `e065da42` 이미지
- **사용 데이터**: rawSn 905(APPROVED·`DE_IDENT_YN='Y'`, srcSn 459~463) / rawSn 900(APPROVED·**`'F'` 신고구간**, srcSn 429~433) / rawSn 173(미승인, srcSn 613~617) / 포털 업로드 프레임 `uldFrmeSn=1`(owner `portal-qa-1`)
- **토큰**: `POST /v1/dev/tokens` 로 PORTAL_USER 발급 (`3001`, `portal-qa-1`, `qa3-f3a`, `qa3-f3b`, `qa3-f3c`, `qa3-rl`, `qa3-tus`, `qa3-tus2`)
- **프로덕션 코드 미수정** (검증 전용). 카탈로그는 담당 라인범위 내 1건 정정.

---

## 0. ★★ 최우선 — 2차 HIGH #2 (F-ISSUE-64, CWE-436) 해소 여부: **해소 확인(라이브 반증 실패 = 방어 성립)**

2차에서 `PortalLabelBodySizeFilter` 가 **원시 URI 정규식**으로 경로를 판정해 `%6Cabels` 한 글자 인코딩만으로 본문 상한·chunked 가드가 통째로 무력화됐던 결함을, **3차에서 직접 라이브 재현 시도**했다. **전 변형에서 우회에 실패**했다.

현행 구현(`PortalLabelBodySizeFilter.java:181-200`)은 자체 디코딩을 버리고 **MVC 와 같은 `ServletRequestPathUtils.parseAndCache(request).pathWithinApplication()`** + `PathPattern` 으로 판정한다(후행 슬래시 변형 패턴 동시 등록).

### 0-1. 라벨 PUT (`/v1/portal/uploads/frames/1/labels`, 3,600,116 byte JSON, 상한 2MB)

| 요청 경로 | 결과 | 판정 |
|---|---|---|
| `/labels` (정규) | **413** `time_total=0.0053` | 파싱 전 차단 |
| `/%6Cabels` (2차 우회 벡터) | **413** `0.0022` | **우회 실패 = 해소** |
| `/label%73` | **413** `0.0017` | 해소 |
| `/labels/` (trailing slash) | **413** `0.0018` | 해소 |
| `/./labels` | **413** `0.0014` | 해소 |
| `/frames/%31/labels` (경로변수 인코딩) | **413** `0.0014` | 해소 |
| `/%256Cabels` (이중 인코딩) | 401 (컨트롤러 미도달) | 우회 아님 |
| `/labels;x=y` (matrix) | 401 (컨트롤러 미도달) | 우회 아님 |

chunked 재현: `/labels`·`/%6Cabels`·`/labels/` **전부 411 LENGTH_REQUIRED** (2차에는 인코딩 경로에서 `{"success":true}` 200 으로 라벨이 실제 교체됐었다).

### 0-2. 사용자 라벨 POST (`/v1/portal/user-labels`) — 3차 신규 적용분

| 요청 경로 | huge body | chunked |
|---|---|---|
| `/v1/portal/user-labels` | **413** `0.0049` | **411** |
| `/v1/portal/%75ser-labels` | **413** `0.0037` | **411** |
| `/v1/portal/user-label%73` | **413** | - |
| `/v1/portal/user-labels/` | **413** | - |
| `/v1/portal/./user-labels` | **413** | - |
| `PUT` 메서드 오지정 | **413** (더 매칭 = 무해) | - |

**라우팅 대조(핵심 반증 절차)**: 작은 본문(`{}`)으로 각 변형의 **컨트롤러 도달 여부**를 먼저 확정했다 — 도달(400 검증오류): `user-labels`, `%75ser-labels`, `./user-labels` → 이 3개 전부 huge body 에서 **413**. 미도달(401/404): `%2575ser-labels`, `//user-labels`, `user-labels;a=b`, `user-labels/`, `USER-LABELS`. 즉 **"컨트롤러에 도달하는데 필터만 스킵되는" 조합이 0건**이다.

### 0-3. 추가 적대 변형 (전부 차단, 200 = 우회 신호 0건)

`X-HTTP-Method-Override: PUT` 로 POST → **405** / form `_method=PUT` → **405** / `frames/1%2Flabels` → **400** / `labels%20` → **404** / `labels%00` → **400** / `frames/1/x/..%2flabels` → **400**.

> **결론: 2차 HIGH #2 = 해소.** 회귀 가드도 실재한다 — `PortalLabelBodySizeFilterTest`(19 테스트, `%6Cabels`·chunked·matrix·trailing slash·context-path·**이중인코딩 우회불가 파리티**·servlet-path-prefix 포함), baseline(`_raw/test-baseline.md`) 상 backend 실패 0건.

---

## 1. F-3 결과표 — 데이터마트 사용자 라벨 저장 (활성 16 / 폐기 4)

| TC-ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-PORTAL-040 | PASS | [실동작] `POST` 201(userLblSn=21) + **`ls_data_lbl` 128건 불변**(저장 전후 동일), `ls_portal_user_label` 만 증가. `GET /v1/portal/datamart/labels?rawSn=905` 응답에 사용자 라벨 미포함. `pg_views` 중 `portal_user_label` 참조 뷰 **0건**(데이터마트 단방향 격리 실증). 근거 라인 `PortalLabelService.java:262-271` 일치 |
| TC-PORTAL-041 | PASS | [실동작] 미승인 rawSn=173 → **403** `FORBIDDEN`, 미존재 rawSn=999999 도 **403**(존재 오라클 차단, CWE-209). `:246-249` 일치 |
| ~~TC-PORTAL-042~045~~ | N/A | [실동작] 폐기 타당 확인 — `lblTypeCd=SKELETON` 은 저장 자체가 400. `PortalKeypointRemovedTest.java` 실재(7 테스트) |
| TC-PORTAL-046 | PASS | [실동작] `points='[]'` → 400 "points 좌표가 비어있습니다", `'[[]]'` → 400 |
| TC-PORTAL-058 | PASS | [실동작] SKELETON/SEGMENT/TRACK/POINT/MASK/`zzz` **전부 400**(저장 미수행). BBOX·POLYGON 은 201 정상 저장(회귀 없음). 소문자 `bbox`/`BbOx` 는 대문자 정규화 후 개수 검증으로 진행(적재값은 대문자 — 우회 아님). `validateAndNormalizeType():284-293` |
| TC-PORTAL-059 | PASS | [실동작] DB 에 레거시 삼중값 row(`lbl_type_cd='SKELETON'`, `point_cn='[[1,2,0.9],[3,4,0.8]]'`) 직접 적재 → `GET /v1/portal/frames/460/labels` **200**, 해당 항목만 스킵되고 정상 BBOX 1건만 반환(500 미발생). `parsePoints():460-472` |
| TC-PORTAL-060 | PASS | [실동작] POLYGON 201점 → 400, 2점 → 400, 200점 → 201, BBOX 3점/1점 → 400. 형제 상수(`PortalUploadLabelService.BBOX_POINT_COUNT`/`POLYGON_*`) 재사용 확인 |
| TC-PORTAL-061 | PASS | [실동작] rawSn=900(`DE_IDENT_YN='F'`) 저장 → **412** `PRECONDITION_FAILED`, 행 미생성. `accessGuard.requireNotUnderDeidentReport():250` |
| TC-PORTAL-062 | PASS | [실동작] 동일 사용자 320회 연속 POST → **403×300 + 429×20, 최초 429 = 301번째**(config `portalUserLabel` 300/1m 과 정확히 일치). 다른 사용자는 같은 시각에 정상 처리(403) = **per-user 격리 성립**. `PortalLabelController.acquireSavePermit():167-175` |
| TC-PORTAL-047 | PASS | [실동작] points 누락/공백/`null` **전부 400** `points: points 는 필수입니다`. `PortalUserLabelRequest.java:18` 일치 |
| TC-PORTAL-048 | PASS | [실동작] 사용자B 가 저장한 `B-SECRET` 라벨이 사용자A 의 `GET /v1/portal/user-labels?rawSn=905` 응답에 **미포함**(A 는 자기 3건만, B 는 자기 1건만). `:369-382` 일치 |
| TC-PORTAL-049 | PASS | [실동작] `point_cn IS NULL` row 적재 후 프레임 라벨 Load → 해당 항목 제외. `:432-434` 일치 |
| TC-PORTAL-050 | PASS | [실동작] `point_cn='{not-json'` row 적재 후 Load → **200**, 항목 제외, 500 미발생. `:460-472` 일치 |
| TC-PORTAL-054 | PASS | [실동작] `GET /v1/portal/user-labels` — rawSn=173(미승인) **403**, rawSn=999999 **403**, rawSn=900(`'F'`) **412**, rawSn=905 **200**. 형제 경로(datamart labels)와 동일 순서·동일 컴포넌트 확인 |
| TC-PORTAL-055 | PASS | [실동작] §0-2 표 참조 — 413/411 + 인코딩·trailing slash·메서드 변형 전부 적용, **체인 미진행**(응답 0.002~0.005s = 3.6MB 본문 미판독). `PortalLabelBodySizeFilter.java:81-124` 일치 |
| TC-PORTAL-056 | PASS | [실동작] `points` 66,014자(공백 패딩, 유효 JSON·BBOX 2점) → **400** `points 는 65536자 이하여야 합니다`; 65,014자는 201 통과하되 **정규형 재직렬화**로 `[[1.0,1.0],[2.0,2.0]]` 만 적재(개수캡+길이캡 2층 실효). `PortalUserLabelRequest.java:17-40` 일치 |
| TC-PORTAL-057 | PASS | [정적+단위] `pathWithinApp()`(`:181-200`)이 `RequestPath.parse(uri, ctx)` 자체 파싱을 제거하고 **`ServletRequestPathUtils.parseAndCache` 단일 규약**만 사용 — 형제 `WebhookProtectedPaths.java:244-279`(실경로 `common/security/webhook/`)도 **동일 코드**로 정합. 단위 회귀 `PortalLabelBodySizeFilterTest#servletPathPrefixAware`(`/api2` + `setServletPath` → 413, chain null 단언) + `WebhookProtectedPathsServletPrefixTest` 실재. ⚠ **라이브 재구성은 수행하지 않음** — `spring.mvc.servlet.path=/api2` 는 공유 backend 컨테이너 재기동(또는 동일 DB·Quartz 를 물는 2번째 인스턴스 기동)을 요구해 병렬 검증 중인 타 클러스터 환경을 오염시킨다. 라이브로는 **contextPath(`/api`) 축**만 실증됨(§0 전 표가 `/api` 하위 요청) |

**F-3 소계: PASS 16 · N/A(폐기 확인) 4 · FAIL 0 · PARTIAL 0**

---

## 2. F-8 결과표 — 포털 영상 TUS 업로드 (32건)

실파일 `clip.mp4`(20,590 byte, 정상 mp4 `ftypisom`) 기준 **세션 생성 → 반분할 청크 → 재개(HEAD) → 완료검증 → 관제/프레임추출** 전 구간을 실제 HTTP 로 구동했다.

| TC-ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-TUS-001 | PASS | [실동작] `OPTIONS` → **204** + `Tus-Resumable: 1.0.0` / `Tus-Version: 1.0.0` / `Tus-Extension: creation,termination` / **`Tus-Max-Size: 5368709120`**. `:67-76` 일치 |
| TC-TUS-002 | **PARTIAL** | [실동작] 201 + `Location: /v1/portal/uploads/tus/0a39529d-…` 이나 **context-path `/api` 누락** → 표준 tus 클라이언트가 그대로 따라가면 404. 2차 **F-ISSUE-82 미해소**(§3-1). `:80-122` 라인 일치 |
| TC-TUS-003 | PASS | [실동작] `Upload-Length` 누락 → **400**. `:88-90` |
| TC-TUS-004 | PASS | [실동작] `Upload-Length: 5368709121` → **413**. `:87-90` (⚠ 오류 응답에 `Tus-Resumable` 부재 = 2차 F-ISSUE-84 미해소, §3-4) |
| TC-TUS-005 | PASS | [실동작] `evil.exe` → **400**. 확장자 미검출(`filename` 부재/점 없음/8자 초과/비영숫자)도 `""` → allowlist 밖 → 400 (fail-closed, `resolveExtension():233-248`) |
| TC-TUS-006 | PASS | [실동작] IN_PROGRESS 3건 보유 상태에서 4번째 생성 → **429**. `:96-102` |
| TC-TUS-007 | PASS | [실동작] `Upload-Metadata: filename ../../../../etc/passwd.mp4` 로 생성 → 저장 실경로 `/app/storage/raw/portal/tus-video/16e5a866-….mp4`(**UUID 강제**), DB `file_path_nm` 동일. `:104-107` |
| TC-TUS-008 | PASS | [실동작] 2,000자 초과 메타 → **413**. `:182-184` |
| TC-TUS-009 | PASS | [실동작] `filename @@@notbase64@@@` → **400**. `:194-200` |
| TC-TUS-010 | PASS | [실동작] `Tus-Resumable: 0.2.2` → **412**. ⚠ **헤더 부재 시 검사 통째로 스킵 → 201**(2차 F-ISSUE-83 미해소, §3-2). `:163-168` |
| TC-TUS-011 | PASS | [실동작] 소유 세션 HEAD → **204** + `Upload-Offset: 0`→(청크 후)`10295` + `Upload-Length: 20590` + **`Cache-Control: no-store`**. ⚠ CANCELLED 세션 HEAD 는 204+offset 0 반환(2차 F-ISSUE-85 미해소, §3-3). `:102-116` |
| TC-TUS-012 | PASS | [실동작] 타 사용자(`qa3-tus2`) HEAD/PATCH/DELETE **전부 403**. `:130-132` |
| TC-TUS-013 | PASS | [실동작] `expry_dt` 를 1시간 전으로 갱신 후 HEAD **410**, PATCH **410**. `:133-135` |
| TC-TUS-014 | **PARTIAL** | [실동작] 정상 PATCH 는 **204 + `Upload-Offset: 10295`** ✓. 그러나 `Content-Type` 불일치(`application/json`)·부재 시 **500 INTERNAL_ERROR + `HttpMediaTypeNotSupportedException` 스택트레이스 ERROR 로그** (415 여야 함) — 2차 **F-ISSUE-81 미해소**(§3-5). `PortalVideoUploadTxService.java:68-124` |
| TC-TUS-015 | PASS | [실동작] `Upload-Offset` 누락 → **400**. `:130-132` |
| TC-TUS-016 | PASS | [실동작] offset=0(서버 10295) → **409**, offset=10300 → **409**. `TxService:98-100` |
| TC-TUS-017 | PASS | [실동작] offset=-1 → **400**, offset=L+1 → **400**. `TxService:95-97` |
| TC-TUS-018 | PASS | [실동작] 16MB+1 청크 → **413**. `TxService:91-94` |
| TC-TUS-019 | PASS | [실동작] `offset+len > length` → **400**. `TxService:101-103` |
| TC-TUS-020 | PASS | [실동작] 동일 세션 4-스레드 동시 PATCH → **204 1건 + 409 3건**, 이후 HEAD offset=10000 으로 **정합 유지**(파손 없음). ⚠ 실제 직렬화는 비관적 락이 담당하고 `truncateTo` 낙관락 복원 분기는 도달 불가(2차 F-ISSUE-87 미해소, LOW, §3-6). `TxService:109-115` |
| TC-TUS-021 | PASS | [실동작] DELETE 후 PATCH → **409** "취소된 업로드 세션입니다". `TxService:81-83` |
| TC-TUS-022 | **PARTIAL** | [실동작] 완료 세션에 마지막 청크 재전송 → **204 + `Upload-Offset: 20590`**(중복 ULD 미생성 = 멱등 ✓). 그러나 **응답에 `uldSn` 이 없다**(서비스 `PortalTusPatchResult.uldSn` 을 컨트롤러가 폐기) — 2차 **F-ISSUE-88 미해소**(§3-7). `TxService:84-87` |
| TC-TUS-023 | PASS | [실동작] `NOTAVIDEO…`(900B, `.mp4` 명) 완료 → **400** "유효한 영상 컨테이너가 아닙니다", 세션 **CANCELLED** 영속, 임시파일 삭제 확인. `:161-165` |
| TC-TUS-024 | PASS | [실동작] 헤더 32B 만 정상이고 이후 전량 `0xFF` 인 손상 mp4 → **400** "영상을 확인할 수 없습니다", CANCELLED. `:167-175` |
| TC-TUS-025 | PASS | [실동작] `ffmpeg -f lavfi -i sine … -c:a aac` 로 만든 **오디오 전용 mp4**(18,802B) → **400** "비디오 스트림이 없는 파일입니다". `:176-179` |
| TC-TUS-026 | PASS | [실동작] 최종 청크 처리 **도중**(검증 구간) 보낸 DELETE 가 **즉시 204 로 성공**(20·30ms 지연 시행) → 완료 검증이 행 잠금/트랜잭션 밖에서 수행됨을 실증. `:146-186` |
| TC-TUS-027 | PASS | [실동작] `stts_cd='COMPLETED'` 세션 **9건** ↔ `ls_portal_uld` 행 **9건**(1:1). 멱등 재전송·동시 완료에도 ULD 중복 0. `TxService:132-157` |
| TC-TUS-028 | PASS | [정적] `finalizeCompleted` 의 조건부 UPDATE 0행 → ULD 보상 삭제 + 409 (`TxService:141-152`). ⚠ 라이브 강제 실패 — 지연 20~160ms 스윕 결과 창이 **30~40ms 사이 ~10ms** 로 좁고, 그보다 이르면 400(파일 부재로 매직바이트 실패)·늦으면 완료 후 no-op(204) 로 갈린다. 자동 테스트도 0건(§3-8 커버리지 갭) |
| TC-TUS-029 | PASS | [실동작] 진행중 세션 DELETE → **204**, `stts_cd='CANCELLED'`, `tus-video/{uuid}.mp4` 삭제 확인. `:190-206` |
| TC-TUS-030 | PASS | [실동작] 완료 세션 DELETE → **204** + 영구 파일 `59d13ded-….mp4`(20,590B) **잔존**(no-op). `:198-201` |
| TC-TUS-031 | PASS | [실동작] 16MB 청크 PATCH(62ms) 진행 중 +50ms 에 DELETE 발사 → DELETE 가 **13ms 대기 후 63ms 에 완료**(= PATCH 트랜잭션 커밋까지 블록) → 동일 락 경로 직렬화 실증. `:192-194` |
| TC-TUS-032 | PASS | [실동작+정적] 저장 파일명이 UUID 로 강제돼 사용자 입력이 경로에 도달하지 않음(TC-007 실증) + `resolveSafe()` 가 `storageRoot` 외부를 403 으로 차단. `:210-218` |

**F-8 소계: PASS 29 · PARTIAL 3 · FAIL 0**

---

## 3. 이슈 (3차 신규 0건 · 2차 미해소 이월 7건)

> ⚠ **채번 안내**: 본 회차 part3 배정 시작번호는 `F-ISSUE-41` 이나, **신규 결함이 0건**이라 신규 채번은 발생하지 않았다. 아래는 **2차(2026-08-02) 이슈 중 이번 담당 범위에서 재현된 미해소 건**이며 **2차 원 ID 를 그대로 유지**한다(수정 추적 연속성 확보 — 새 번호를 붙이면 같은 결함이 두 ID 로 갈린다).

### [F-ISSUE-64] TC-PORTAL-055 / TC-PORTALUP-051·052 — 라벨 본문 상한 URL 인코딩 우회 → ✅ **해소(CLOSED)**
- **재확인 결과**: §0 전 표. `%6Cabels`·`%75ser-labels`·`label%73`·trailing slash·`./`·경로변수 인코딩 **전부 413**, chunked 전부 411, 컨트롤러 도달 조합 중 상한 미적용 **0건**.
- **해소 커밋 구현**: `PortalLabelBodySizeFilter` 가 `ServletRequestPathUtils.parseAndCache` + `PathPattern`(후행 슬래시 변형 동시 등록, 판정 불가 시 fail-closed) 로 전환. 자체 디코딩 루프를 넣지 않아 이중 인코딩에서도 MVC 와 어긋나지 않음.
- **잔여 없음**. 회귀 가드 19 테스트 실재(baseline 실패 0).

---

### [F-ISSUE-81] TC-TUS-014 — PATCH 의 Content-Type 불일치/부재가 415 가 아니라 500 + 스택트레이스 (미해소)
- **심각도**: MEDIUM
- **기대 동작**: `@PatchMapping(consumes="application/offset+octet-stream")` 에 맞지 않는 Content-Type 은 **415 UNSUPPORTED_MEDIA_TYPE** 으로 마감돼야 한다. TUS 클라이언트 오구현·프록시의 헤더 변조는 **정상 운영 중 발생하는 입력 오류**이지 서버 장애가 아니다.
- **현재 동작(실측 2026-08-04 02:26)**:
  ```
  PATCH /api/v1/portal/uploads/tus/{uid}  Content-Type: application/json
  → 500 {"success":false,...,"errorCode":"INTERNAL_ERROR"}
  backend log: org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/json' is not supported
               at RequestMappingInfoHandlerMapping.handleNoMatch(...)   ← 전체 스택트레이스 ERROR 로 적재
  Content-Type 부재도 동일: "Content-Type is not supported" → 500
  ```
- **재현**: 위 curl/http 요청 그대로.
- **영향**: 기능(클라이언트가 재시도 가능 오류를 서버 장애로 오인) + 운영(정상 입력 오류가 ERROR 스택트레이스로 로그를 오염 → 실제 장애 탐지 저해, CWE-209 계열 로그 노이즈). 응답 본문 자체에 내부 정보 누출은 없음.
- **수정 방향(제안)**: `GlobalExceptionHandler` 에 `HttpMediaTypeNotSupportedException → 415`(+ `HttpRequestMethodNotSupportedException → 405`) 핸들러 추가, WARN 레벨로 강등. F-ISSUE-84(응답 `Tus-Resumable` 부착)와 같은 작업 단위로 처리하면 경제적. ⚠ 구현하지 않음.

---

### [F-ISSUE-82] TC-TUS-002 — 세션 생성 `Location` 이 context-path `/api` 를 누락 (미해소)
- **심각도**: MEDIUM
- **기대 동작**: TUS 1.0 클라이언트는 `Location` 을 그대로 후속 HEAD/PATCH/DELETE 대상 URL 로 쓴다. 배포 형상의 context-path(`/api`)가 포함돼야 재개 업로드가 성립한다.
- **현재 동작(실측)**: `POST /api/v1/portal/uploads/tus` → `201`, `Location: /v1/portal/uploads/tus/0a39529d-c142-4271-aa0f-ae6982c2a53a` (`/api` 없음).
  `PortalTusUploadController.java:96` — `.header(HttpHeaders.LOCATION, "/v1/portal/uploads/tus/" + uldId)` 로 **문자열 하드코딩**.
- **재현**: `curl -i -X POST -H 'Tus-Resumable: 1.0.0' -H 'Upload-Length: 20590' -H 'Upload-Metadata: filename Y2xpcC5tcDQ=' http://localhost:18081/api/v1/portal/uploads/tus`
- **영향**: 기능 — 표준 tus-js-client 계열이 Location 을 따라가면 404 로 업로드 재개 불가. 현재 FE 가 자체 경로 조립으로 우회하고 있어 표면화되지 않았을 뿐이며, 이는 계약 위반이 감춰진 상태다.
- **수정 방향(제안)**: `ServletUriComponentsBuilder.fromCurrentContextPath()`(또는 `request.getContextPath()`) 기반으로 Location 을 조립. 회귀 가드로 context-path 설정 하 `Location` 단언 테스트 추가. ⚠ 구현하지 않음.

---

### [F-ISSUE-83] TC-TUS-010 — `Tus-Resumable` **부재** 시 버전 검사를 통째로 건너뜀 (미해소)
- **심각도**: LOW~MEDIUM
- **기대 동작**: TUS 1.0 은 OPTIONS 를 제외한 모든 요청에 `Tus-Resumable` 을 요구하며, 미지원/부재 시 **412** 다.
- **현재 동작(실측)**: 헤더를 아예 빼고 `POST /api/v1/portal/uploads/tus` (Upload-Length 만) → **201**(세션 생성됨). `PortalTusUploadController.java:163-168`
  ```java
  private void requireTusVersion(String tusResumable) {
      if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) { ... 412 ... }
  }   // ← null 이면 검사 스킵
  ```
  버전 **불일치**(`0.2.2`) 는 정상적으로 412 이므로 TC-TUS-010 자체는 통과한다.
- **영향**: 프로토콜 정합(비표준 클라이언트가 버전 협상 없이 진입). 보안 영향은 없음.
- **수정 방향(제안)**: `null` 도 412 로 승격(단 `OPTIONS` 제외). 기존 FE 가 헤더를 보내는지 먼저 확인해 하위호환 파손 여부 판단 필요. ⚠ 구현하지 않음.

---

### [F-ISSUE-84] TC-TUS-004 인접 — 오류 응답에 `Tus-Resumable` 헤더 부재 (미해소)
- **심각도**: LOW
- **기대 동작**: TUS 명세상 서버 응답(오류 포함)에는 `Tus-Resumable` 이 포함돼야 한다.
- **현재 동작(실측)**: `Upload-Length: 5368709121` → `HTTP/1.1 413` 응답 헤더에 `Tus-Resumable` **없음**(성공 응답에만 컨트롤러가 수동 부착).
- **영향**: 프로토콜 정합. 엄격한 클라이언트가 오류 응답을 프로토콜 위반으로 처리할 수 있음.
- **수정 방향(제안)**: `/v1/portal/uploads/tus/**` 전용 `HandlerInterceptor`(또는 `ResponseBodyAdvice`)로 일괄 부착. F-ISSUE-81 과 동시 처리 권장. ⚠ 구현하지 않음.

---

### [F-ISSUE-85] TC-TUS-011 / TC-TUS-029 인접 — CANCELLED 세션 HEAD 가 204 + 존재하지 않는 offset 반환 (미해소)
- **심각도**: LOW~MEDIUM
- **기대 동작**: 취소된 세션은 재개 대상이 아니므로 HEAD 는 404/410 로 마감돼야 한다(임시파일이 이미 삭제됐다).
- **현재 동작(실측)**: DELETE 로 취소한 세션 `7db52b17-…` 에 HEAD → **204 + `Upload-Offset: 0` + `Upload-Length: 20590`**. 클라이언트는 "offset 0 부터 재개 가능"으로 읽지만, 같은 세션에 PATCH 하면 409("취소된 업로드 세션입니다") 다. `PortalVideoUploadService.getForOwner():127-137` 이 `isCancelled()` 를 보지 않는다(만료만 검사).
- **재현**: 세션 생성 → `DELETE` → `HEAD` (동일 소유자).
- **영향**: 기능 — 재개 UX 가 어긋난다(offset 0 을 받고 처음부터 전송 시도 → 409). 데이터 파손은 없음.
- **수정 방향(제안)**: `getForOwner` 에 `isCancelled()` → 410(GONE) 분기 추가. HEAD/PATCH 응답 코드 일관성(409 vs 410) 은 함께 결정 필요. ⚠ 구현하지 않음.

---

### [F-ISSUE-87] TC-TUS-020 — 낙관적 락 복원(`truncateTo`) 분기가 비관적 락에 가려 도달 불가 (미해소)
- **심각도**: LOW
- **기대 동작**: 카탈로그 기대결과의 "409 + truncate 복원" 중 **복원 경로가 실제로 동작**하거나, 아니면 그 분기가 불필요함이 명시돼야 한다.
- **현재 동작(실측)**: 4-스레드 동시 PATCH → 204 1건 + **409 3건**이며, 409 는 전부 `OptimisticLockingFailureException` 이 아니라 **offset 불일치 분기**(`TxService:98-100`)에서 나온다. 진입부가 `findByUldIdForUpdate`(PESSIMISTIC_WRITE, `:72`)라 트랜잭션이 직렬화돼 `saveAndFlush` 의 낙관락 충돌(`:111-115`)이 발생하지 않는다. 결과 offset(10000)은 정합 유지되므로 **실동작상 안전**하다.
- **영향**: 코드 위생(도달 불가 분기 + 그 분기를 전제로 한 테스트/문서 서술). 기능·보안 영향 없음.
- **수정 방향(제안)**: ①분기를 유지하되 "이중 안전망(방어적)" 주석 명시 + 카탈로그 기대결과에서 truncate 문구 완화, 또는 ②비관락 단일화로 낙관락 필드/분기 제거. ⚠ 구현하지 않음.

---

### [F-ISSUE-88] TC-TUS-022 / TC-TUS-027 — 완료 결과(`uldSn`)가 HTTP 응답에 노출되지 않음 (미해소)
- **심각도**: MEDIUM
- **기대 동작**: 카탈로그 TC-TUS-022 기대결과 "완료 응답(uldSn 재반환)" — 클라이언트가 업로드 완료 직후 생성된 자산 식별자를 알아야 후속 화면 전이·목록 갱신이 가능하다.
- **현재 동작(실측)**: 최종 청크·재전송 모두 **`204 No Content` + `Upload-Offset` 만**. 서비스는 `PortalTusPatchResult(newOffset, completed, uldSn)` 로 uldSn 을 돌려주는데(`PortalVideoUploadService.java:183-185`) 컨트롤러(`PortalTusUploadController.java:139-142`)가 `result.newOffset()` 만 쓰고 **`completed`/`uldSn` 을 폐기**한다. DB 에는 정상 생성됨(`ls_portal_uld.uld_sn=83`, `uld_stts_cd=READY`, `frme_cnt=1`).
- **재현**: 정상 mp4 를 2청크로 업로드 → 최종 PATCH 응답 헤더/본문 확인.
- **영향**: 기능 — 업로드 완료 후 화면이 자산을 즉시 참조하지 못한다(폴링/목록 재조회 의존).
- **수정 방향(제안)**: 완료 시 커스텀 응답 헤더(예: `X-Portal-Uld-Sn`) 부착 또는 `Upload-Offset` 유지한 채 201/200 + 본문 반환(단 TUS 표준은 204 이므로 **헤더 방식 권장**). FE 의 완료 시 목록 무효화도 함께. ⚠ 구현하지 않음.

---

### [F-ISSUE-89] F-8 전반 — TUS 컨트롤러 계층 자동 테스트 0건 (부분 미해소)
- **심각도**: MEDIUM
- **현재 동작**: F-8 자동 테스트는 여전히 `PortalVideoUploadServiceTest.java`(11 테스트, 서비스 계층 fake repo) 1 파일뿐이다(`@DisplayName` 실측 11건: 완료·중복이벤트·IDOR 2건·취소PATCH·매직바이트·오디오전용·CANCELLED영속·5GB·확장자·경로순회). **미커버**: TC-001·003·008·009·010·011·013·015·016·017·019·020·026·028·031(15건).
- ⚠ **이번 회차에서 위 15건 중 14건을 라이브로 직접 검증해 PASS 판정**했다(TC-028 만 라이브 재현 실패). 즉 "동작은 확인됐으나 회귀 가드가 없다"는 상태다.
- **영향**: 회귀 무방비 — 실제로 이번 회차에서 결함이 재현된 지점(F-ISSUE-81/82/83/84/85)이 전부 이 미커버 구간에 있다.
- **수정 방향(제안)**: `PortalTusUploadControllerTest`(`@WebMvcTest`)로 헤더 프로토콜(TC-001·003·008·009·010·011·015), `PortalTusUploadIT`(Testcontainers)로 재개·409·410·동시성·완료 멱등(TC-013·016·017·019·020·026·028·031). ⚠ 구현하지 않음.

---

## 4. 카탈로그 정정 (담당 라인범위 내)

| 위치 | 정정 전 | 정정 후 | 사유 |
|---|---|---|---|
| `F-portal.md:83` (F-3 하단 채번 주의 노트) | "3차 QA 신규 케이스는 039 + 051~057 로 채번했다" | "…039 + 051~057, 이어서 058~062 로 채번했다" | 같은 섹션에 **TC-PORTAL-058~062 (신규) 5건이 실재**하는데 노트가 057 까지만 기술해 다음 회차가 062 를 중복 채번할 위험. 3차 실측 정정 |

그 외 **F-3(58~84행)·F-8(180~218행)의 근거 `file:line` 은 전건 실측 일치 — 드리프트 0건**:
- `PortalLabelService.java` 246-249 / 262-271 / 369-382 / 432-434 / 460-472 ✓
- `PortalUserLabelRequest.java` 18 / 17-40 ✓
- `PortalLabelBodySizeFilter.java` 81-124 / 163-200 ✓ · `WebhookProtectedPaths.java` 244-279 ✓(실경로 `common/security/webhook/`)
- `PortalTusUploadController.java` 67-76 / 88-90 / 102-116 / 130-132 / 163-168 / 182-184 / 194-200 ✓
- `PortalVideoUploadService.java` 80-122 / 87-90 / 91-95 / 96-102 / 104-107 / 130-132 / 133-135 / 146-186 / 161-165 / 167-175 / 176-179 / 190-206 / 192-194 / 198-201 / 210-218 ✓
- `PortalVideoUploadTxService.java` 68-124 / 81-83 / 84-87 / 91-94 / 95-97 / 98-100 / 101-103 / 109-115 / 132-157 / 141-152 ✓ (2차 노트의 **-1 이동 반영분이 현행과 일치**)
- 참조 테스트 실재 확인: `PortalKeypointRemovedTest`(7) · `PortalUserLabelServiceTest`(31) · `PortalUserLabelRequestValidationTest`(4) · `PortalLabelControllerRateLimitTest` · `PortalLabelBodySizeFilterTest`(19) · `WebhookProtectedPathsServletPrefixTest` · `PortalVideoUploadServiceTest`(11)

---

## 5. 판정 집계

| 구분 | 총 | PASS | PARTIAL | FAIL | N/A(폐기) | BLOCKED |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| F-3 (58~84행) | 20 | 16 | 0 | 0 | 4 | 0 |
| F-8 (180~218행) | 32 | 29 | 3 | 0 | 0 | 0 |
| **합계** | **52** | **45** | **3** | **0** | **4** | **0** |

- **3차 신규 이슈 0건**. PARTIAL 3건(TC-TUS-002·014·022)은 전부 **2차 이슈의 미해소 이월**이다.
- ★ **2차 HIGH #2 (F-ISSUE-64, 라벨 PUT/POST 본문상한 URL 인코딩 우회, CWE-436/770) = 해소 확인**(라이브 반증 8+6 변형 + 적대 6 변형 전부 차단).
- 실동작 판정 비율: 52건 중 **50건 [실동작]**, 2건([정적] TC-PORTAL-057 · TC-TUS-028)은 사유를 각 셀에 명시.

## 6. 이번 검증이 남긴 데이터 (원복하지 않음 — 후속 회차 참고)

- `ls_portal_user_label`: `qa3-f3a`(BBOX/POLYGON 정상 6건 내외) · `qa3-f3b`(`B-SECRET` 1건) · **`qa3-f3c` 4건**(user_lbl_sn 27=point_cn NULL, 28=손상 JSON, 29=레거시 SKELETON 삼중값, 30=정상) — **TC-PORTAL-049/050/059 의 물증이라 의도적으로 존치**
- `ls_portal_tus_uld`: `qa3-tus` COMPLETED 9 / CANCELLED 15+, `qa3-tus2` 0 · `ls_portal_uld`: `qa3-tus` VIDEO 9건(uld_sn 83~, READY, 프레임 추출 완료)
- `ls_data_raw` / `ls_data_lbl` / `ls_data_src` **무변경**(라벨 128건 불변 확인)
- 임시 조작: `uld_id=91e229b5-…` 의 `expry_dt` 를 과거로 UPDATE(TC-TUS-013 근거) — 해당 세션은 이후 CANCELLED 로 종결
