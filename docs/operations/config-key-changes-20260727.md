# 백엔드 설정 키 변경 대장 (Phase 1 · Phase 2 · Phase 3 · Phase 4)

- 일자: 2026-07-27 (Phase 3·4 추가: 2026-07-28)
- 대상: `backend/src/main/resources/application{,-local,-dev,-stg,-prd}.yml` + 설정을 읽는 소량 Java + `docker-compose{,.local}.yml`
- 조사 정본: `docs/operations/config-property-audit-20260727.md` (A/B/C/D/E 섹션)
- 계획 정본: `.claude-plan.md` — Phase 1(A1~A4) / Phase 2(B, C1·C3·C4·C6·C7) / Phase 3(D1·D2) / Phase 4(R6·E1~E3)
- 범위 밖(미반영): C2 prd `${VAR:?}` 강제 확대 · C5 test 프로파일 분리 · D3/D4 prefix 재편 · E4 증강 실서버 HTTP 클라이언트 신규 구현

> **배포 조치 필요**: 리포지토리 변경만으로 끝나지 않고 **서버 `.env`/컨테이너 환경변수 주입이 있어야
> 기동 또는 정상 동작하는 항목**. 하단 "배포 체크리스트" 참조.

---

## 1. Phase 1 — 동작 결함 수정 (A1~A4)

| # | 키 | 변경 종류 | 영향 프로파일 | 대응 환경변수 | 배포 조치 |
|---|---|---|---|---|:---:|
| A1 | `kpst.augment.external.mode` | **제거**(오배치 원본) | local | — | 불요 |
| A1 | `authoring.augment.external.mode` | **이동**(신규 위치, 값 동일) | local | `AUGMENT_EXTERNAL_MODE` | 불요 |
| A2 | `authoring.ffprobe.binary` | **제거**(코드가 읽던 미정의 키) | 코드(`BrampVideoProbe`) | — | 불요 |
| A2 | `authoring.ffmpeg.ffprobe-binary` | 사용처 확대(단일 진실원) | 공통 | `FFPROBE_BIN` | **확인** |
| A3 | `spring.datasource.control.hikari.*` | **형식변경**(중첩 제거 → prefix 직하) | prd | — | **확인** |
| A3 | `spring.datasource.portal.hikari.*` | **형식변경**(동일) | prd | — | **확인** |
| A4 | `authoring.integration.deidentify.mock-mode` | **신규**(의도 명시, 값=공통과 동일 false) | stg | `DEIDENTIFY_MOCK_MODE` | 불요 |
| A4 | `kpst.deid.base-url` | **신규**(기본값 없는 필수 형태) | stg | `KPST_DEID_BASE_URL` | **필수** |

### 동작이 실제로 바뀌는 항목(주의)

- **A2 `FFPROBE_BIN`**: 지금까지 `BrampVideoProbe` 만 이 주입을 무시하고 PATH 의 `ffprobe` 를 썼다.
  수정 후 **주입값이 이 경로에도 반영**된다 → `FFPROBE_BIN` 에 잘못된 절대경로가 들어가 있던 환경이라면
  기존에 "우연히 동작하던" 이 경로가 함께 실패하게 된다. 배포 전 서버의 `FFPROBE_BIN` 실값 확인 필요.
- **A3 prd 커넥션 풀**: 중첩 키가 조용히 무시돼 운영 풀이 기본값(10)으로 돌고 있었다.
  수정 후 **의도한 20/5 로 실제 바인딩**된다 → prd DB 의 `max_connections` 여유를 확인할 것
  (앱 2노드 Active-Active × control/portal 2풀 × 20 = 최대 80 커넥션).
- **A4 stg `KPST_DEID_BASE_URL`**: 기본값이 없으므로 **미주입 시 stg 기동 실패**(의도된 fail-fast).

---

## 2. Phase 2 — 미사용 키 제거 (B)

`main` + `test` + `docs` 전 범위 grep(하이픈/캐멀케이스/환경변수 형태, `@ConfigurationProperties` prefix 경유,
`@TestPropertySource` 포함) 으로 **참조 0건**을 확인하고 제거했다.

| # | 키 | 변경 종류 | 영향 프로파일 | 참조 수 | 실제 값의 출처(제거해도 되는 근거) |
|---|---|---|---|:---:|---|
| B1 | `authoring.jwt.algorithm` | **제거** | 공통 | 0 | 알고리즘은 코드 상수 — 발급 `Jwts.SIG.HS256`(`RoleClaimService`/`DevTokenService`), 검증 `SecretKeyResolver` 의 HMAC 키 |
| B2 | `authoring.batch.rate-per-min` | **제거** | 공통 | 0 | 배치 주기는 `authoring.batch.interval-sec`(Quartz 트리거) |
| B3 | `authoring.batch.frame-interval-sec` | **제거** | 공통 | 0 | 포털 프레임 간격은 별개 DB 설정키 `portal.upload.frame-interval-sec`(`LS_SYSTEM_CONFIG`) |
| B4 | `authoring.integration.deidentify.timeout-ms` | **제거** | 공통 | 0 | `KpstDeidentifyClient` 상수(업/다운로드 10m, 그 외 45s) |
| B5 | `authoring.integration.ai-server.timeout-ms` | **제거** | 공통 | 0 | `resilience4j.timelimiter.instances.ai`(60s) |
| B6 | `kpst.augment.*` | 제거 완료(Phase 1 A1) | local | 0 | 잔여 없음 확인 |

> 제거된 키는 `ConfigPropertyKeyGuardTest#제거된_설정키는_코드에서_참조되지_않는다` 가 **재유입을 차단**한다.
> 배포 조치는 **전부 불요** — 아무도 읽지 않던 값이라 환경변수 대응물도 없다.

---

## 3. Phase 2 — 환경 드리프트 정리 (C)

| # | 키 | 변경 종류 | 영향 프로파일 | 대응 환경변수 | 배포 조치 |
|---|---|---|---|---|:---:|
| C1 | `spring.servlet.multipart.max-file-size` | 값 변경 21MB → **500MB** | 공통 | — | 불요 |
| C1 | `spring.servlet.multipart.max-request-size` | 값 변경 1100MB → **1200MB** | 공통 | — | 불요 |
| C1 | `spring.servlet.multipart.*` | **제거**(공통 상속) | local·dev·stg | — | 불요 |
| C1 | `spring.servlet.multipart.max-file-size` | **신규 override 21MB** | **prd** | — | 불요 |
| C1 | `spring.servlet.multipart.max-request-size` | **신규 override 1100MB** | **prd** | — | 불요 |
| C3 | `CONTROL_DB_HOST` 기본값(내부 IP) | **형식변경**(기본값 제거) | dev | `CONTROL_DB_HOST` | **필수** |
| C3 | `CONTROL_DB_PORT` 기본값(5432) | **형식변경**(기본값 제거) | dev | `CONTROL_DB_PORT` | **필수** |
| C3 | `CONTROL_DB_NAME` 기본값(`klid_system`) | **형식변경**(기본값 제거) | dev | `CONTROL_DB_NAME` | **필수** |
| C3 | `kpst.deid.base-url` 기본값(벤더 IP) | **형식변경**(기본값 제거) | dev | `KPST_DEID_BASE_URL` | **필수** |
| C4 | `spring.datasource.control.connection-init-sql` | 주석만 추가(동작 불변) | 공통 | — | 불요 |
| C6 | `authoring.dev.login.enabled` | **형식변경** 리터럴 `true` → `${DEV_LOGIN_ENABLED:true}` | dev·stg | `DEV_LOGIN_ENABLED` | 불요(기본 동작 동일) |
| C6 | `authoring.dev.upload.enabled` | **형식변경** 리터럴 `true` → `${DEV_UPLOAD_ENABLED:true}` | dev·stg | `DEV_UPLOAD_ENABLED` | 불요(기본 동작 동일) |
| C7 | `logging.level.kr.co.cudo.authoring` | **신규**(`INFO`) | stg | — | 불요 |

### C1 multipart 최종 한도 (★ 상향 금지 게이트)

| 프로파일 | max-file-size | max-request-size | 출처 |
|---|---|---|---|
| 공통(local·dev·stg 상속) | 500MB | 1200MB | `application.yml` |
| **prd** | **21MB** | **1100MB** | `application-prd.yml` **명시 override** |

> prd override 를 삭제하면 운영 업로드 한도가 21MB → 500MB 로 **조용히 상향**된다(자원 소진, OWASP API4).
> `ConfigProfileDriftGuardTest#prd_프로파일_multipart_한도는_21MB_1100MB로_유지된다` 가 값 + "공통 상속이 아닌
> 명시 선언" 두 가지를 모두 고정해 회귀를 차단한다.

### C6 dev 토글 — 기본 동작은 불변

`dev`/`stg` 는 여전히 기본 ON 이다(기존 `@Profile("!prd")` 동작 유지). 달라진 것은 **끌 수 있게 된 것**뿐이다
(`DEV_LOGIN_ENABLED=false` 주입 시 dev 로그인 엔드포인트 미노출). 공통(`application.yml`)의 fail-closed
기본값 `false` 는 그대로 유지 — prd 로는 새지 않는다.

---

## 3-2. Phase 3 — 암묵 기본값 yml 명시 (D1)

코드가 `${key:default}` / `@ConditionalOnProperty` 로만 읽어 **yml 어디에도 흔적이 없던** 손잡이를
공통 `application.yml` 에 명시했다. **값은 코드 기본값 그대로** 옮겼으므로 동작 변경은 0 이며,
얻는 것은 "발견 가능성 + 환경변수 override 수단"뿐이다.

| 키 | 명시한 값 | 대응 환경변수 | 코드상 출처 | 배포 조치 |
|---|---|---|---|:---:|
| `authoring.control-notify.retry.interval-ms` | 300000 | `CONTROL_NOTIFY_RETRY_INTERVAL_MS` | `ControlNotifyFallbackRetryJob:38` | 불요 |
| `authoring.control-notify.retry.initial-delay-ms` | 60000 | `CONTROL_NOTIFY_RETRY_INITIAL_DELAY_MS` | `ControlNotifyFallbackRetryJob:39` | 불요 |
| `authoring.meta-replication.enabled` | true | `META_REPLICATION_ENABLED` | `MetaReplicationJobConfig:22`(matchIfMissing=true) | 불요 |
| `authoring.meta-replication.interval-sec` | 60 | `META_REPLICATION_INTERVAL_SEC` | `MetaReplicationJobConfig:26` | 불요 |
| `authoring.meta-replication.batch-size` | 100 | `META_REPLICATION_BATCH_SIZE` | `MetaReplicationWorker:53` | 불요 |
| `authoring.meta-replication.max-retry` | 5 | `META_REPLICATION_MAX_RETRY` | `MetaReplicationOutboxService:29` | 불요 |
| `authoring.resolution.resize-max-concurrent` | 2 | `RESOLUTION_RESIZE_MAX_CONCURRENT` | `ResizeConcurrencyGate:31` | 불요 |
| `authoring.resolution.resize-acquire-timeout-sec` | 5 | `RESOLUTION_RESIZE_ACQUIRE_TIMEOUT_SEC` | `ResizeConcurrencyGate:32` | 불요 |
| `authoring.sam2.max-image-bytes` | 20971520 | `SAM2_MAX_IMAGE_BYTES` | `Sam2SegmentService:77` | 불요 |
| `authoring.upload.tus.cleanup.interval-ms` | 3600000 | `TUS_CLEANUP_INTERVAL_MS` | `TusUploadCleanupJob:41` | 불요 |
| `authoring.upload.tus.cleanup.initial-delay-ms` | 600000 | `TUS_CLEANUP_INITIAL_DELAY_MS` | `TusUploadCleanupJob:42` | 불요 |
| `authoring.webhook.idempotency.in-memory` | false | `WEBHOOK_IDEMPOTENCY_IN_MEMORY` | `InMemoryWebhookIdempotencyLedger:19`(havingValue=true, matchIfMissing 없음 → 부재=false) | 불요 |
| `portal.upload.sweep.interval-ms` | 1800000 | `PORTAL_SWEEP_INTERVAL_MS` | `PortalUploadSweepJob:53` | 불요 |
| `portal.upload.sweep.initial-delay-ms` | 600000 | `PORTAL_SWEEP_INITIAL_DELAY_MS` | `PortalUploadSweepJob:54` | 불요 |

> `authoring.meta-replication.enabled` 는 공통에 선언이 없고 `application-local.yml` 에만 `false` 로
> 존재했다. 공통에 코드 기본값 `true` 를 명시하고 **local 의 `false` override 는 그대로 유지**했다
> (운영 dev/stg/prd 는 이전과 동일하게 활성).
>
> 값 고정은 `ImplicitDefaultConfigGuardTest#암묵_기본값을_yml에_명시해도_기존_동작이_바뀌지_않는다`
> 가 담당한다 — yml 값을 **main 소스에서 긁어온 placeholder 기본값과 직접 대조**하므로 한쪽만 바뀌면 깨진다.

## 3-3. Phase 3 — `portal.upload.*` 바인딩 통합 (D2)

같은 prefix 를 `PortalUploadProperties`(record) 와 흩어진 `@Value` 가 나눠 읽던 **이원화**를 없앴다.
**기본값은 1:1 이관**(아래 대조표) — 값이 하나라도 달라지면 조용한 동작 변경이므로 테스트로 고정한다.

| 키 | 이관 전(`@Value` 기본값) | 이관 후(`@DefaultValue`) | 사용처 |
|---|---|---|---|
| `portal.upload.max-chunk-bytes` | 16777216 | 16777216 | `PortalVideoUploadTxService` |
| `portal.upload.max-label-body-bytes` | 2097152 | 2097152 | `PortalLabelBodySizeFilter` |
| `portal.upload.probe-timeout-sec` | 30 | 30 | `PortalVideoProbeFfprobe` |
| `portal.upload.stuck-timeout-minutes` | 30 | 30 | `PortalUploadSweepJob` |
| `portal.upload.sweep.interval-ms` | 1800000 | **이관 불가** — placeholder 유지 | `PortalUploadSweepJob` `@Scheduled` |
| `portal.upload.sweep.initial-delay-ms` | 600000 | **이관 불가** — placeholder 유지 | `PortalUploadSweepJob` `@Scheduled` |

> **sweep 2키가 record 로 못 가는 이유**: `@Scheduled(fixedDelayString=...)` 는 어노테이션 속성이라
> **상수 표현식만** 허용한다(빈 필드 참조 불가). 따라서 placeholder 를 유지하고 yml 명시(D1)만 적용했다.
> record 에 sweep 필드를 두면 같은 키를 두 경로가 읽는 이원화가 재발하므로 일부러 두지 않았고,
> `PortalUploadPropertiesBindingTest#sweep_주기_설정이_yml에_명시되고_record_이관_대상에서_제외된다`
> 가 이 결정을 고정한다.

> 신규 환경변수 `PORTAL_MAX_CHUNK_BYTES` / `PORTAL_MAX_LABEL_BODY_BYTES` / `PORTAL_PROBE_TIMEOUT_SEC` /
> `PORTAL_STUCK_TIMEOUT_MINUTES` 는 **전부 기본값이 있어 미주입이어도 기존과 동일하게 동작**한다(배포 조치 불요).

---

## 4. 배포 체크리스트 — 서버 `.env` 갱신

> Phase 4(dev 프로파일 전환 + mock-server 배선)에서 확정될 항목을 여기에 이어서 채운다.

### 4-1. 지금(Phase 1·2) 기준으로 확정된 항목

**stg 배포 시 — 미주입이면 기동 실패**

| 키 | 사유 |
|---|---|
| `KPST_DEID_BASE_URL` | A4 — stg 에 기본값 없는 필수 형태로 신설. 미주입 시 부팅 차단(조용한 오연동 방지) |
| `KPST_DEID_CA_CERT_PATH` | 위 주소가 **https 인 경우에만** 필수. http 내부망 주소면 불요(CWE-295 fail-closed) |

**dev 프로파일로 기동하는 경우 — 미주입이면 기동 실패** (C3 로 기본값 제거)

| 키 | 사유 |
|---|---|
| `CONTROL_DB_HOST` / `CONTROL_DB_PORT` / `CONTROL_DB_NAME` | 내부 IP·DB 명 기본값 제거 |
| ~~`KPST_DEID_BASE_URL`~~ | Phase 2 시점엔 필수였으나 **Phase 4 에서 목 서버 기본값(`http://klid-mock-server:9400`)이 생겨 필수 아님**. 벤더 실서버 전환 시에만 주입 |

> ⚠ Phase 2 시점의 `docker-compose.yml` 은 `SPRING_PROFILES_ACTIVE: local` 이라 **`application-dev.yml` 이
> 로드되지 않았다**(조사 리포트 E-부수). Phase 4 에서 dev 로 전환했으므로 위 항목은 **지금부터 실제로 필수**다.
> **dev 필수 환경변수 정본은 §4-2 (1)** 이다 — 이 표는 이력용이며, 실제 배포 체크는 §4-2 를 따른다.

**운영 점검(주입이 아니라 확인 사항)**

| 항목 | 확인 내용 |
|---|---|
| `FFPROBE_BIN` (전 환경) | A2 — 이제 `BrampVideoProbe` 에도 반영된다. 실값이 유효한 경로인지 확인 |
| prd DB `max_connections` | A3 — 운영 풀이 실제 20/5 로 바인딩된다(기존 10). 2노드 × 2풀 × 20 여유 확인 |

### 4-2. Phase 4 — dev 프로파일 전환 + mock-server 실연동 배선

> 이 절이 **cudo_246 배포 인수인계 정본**이다. 리포지토리 변경만으로는 끝나지 않는다 —
> 서버 `.env` 는 리포지토리에 없는 별개 파일이므로 아래 목록을 사람이 반영해야 한다.

#### (1) dev 필수 환경변수 — 서버 `.env` 갱신 체크리스트

프로파일이 `local` → `dev` 로 바뀌면 **`application-local.yml` 의 개발용 기본값을 더 이상 상속하지 않는다.**
아래 두 부류를 구분해서 본다. **A 는 즉시 부팅 실패라 눈에 띄지만, B 는 기동에 성공한 뒤 해당 기능만
조용히 죽으므로 더 위험하다.**

<!-- DEV_REQUIRED_ENV:START -->
**A. 미주입 시 부팅 실패** (기본값 없는 placeholder — 조용한 오연동 대신 즉시 실패시키는 설계)

| 키 | 미주입 시 증상 |
|---|---|
| `JWT_SECRET` | 부팅 실패. 토큰 검증 키(>= 256bit) |
| `CONTROL_DB_HOST` | 부팅 실패 (컨테이너 배포는 compose 의 jdbc-url 지정이 우선하므로 실질 미사용 — 아래 (2) 참조) |
| `CONTROL_DB_PORT` | 〃 |
| `CONTROL_DB_NAME` | 〃 |
| `CONTROL_DB_USERNAME` | 부팅 실패 (jdbc-url 을 지정해도 계정은 별도 주입 필요) |
| `CONTROL_DB_PASSWORD` | 〃 |
| `PORTAL_DB_HOST` | 부팅 실패 (컨테이너 배포는 jdbc-url 지정이 우선) |
| `PORTAL_DB_PORT` | 〃 |
| `PORTAL_DB_NAME` | 〃 |
| `PORTAL_DB_USERNAME` | 부팅 실패 |
| `PORTAL_DB_PASSWORD` | 〃 |

**B. 기동은 되지만 기능이 조용히 죽음** (빈 기본값 = fail-closed)

| 키 | 미주입 시 증상 |
|---|---|
| `STREAM_SIGN_SECRET` | 영상 스트림 서명 URL 발급·검증 전면 차단 → 마킹/라벨링 화면에서 **영상 재생 불가** |
| `CORS_ALLOWED_ORIGINS` | 브라우저 요청 전면 403 → **FE 전 기능 차단** |
| `ADMIN_CLAIM_PASSWORD_HASH` | 관리자 role-claim 불가 |
| `WEBHOOK_HMAC_SECRET_AUGMENT` | 증강 콜백 401 → 증강 결과 수신 불가 |
<!-- DEV_REQUIRED_ENV:END -->

> 위 목록은 `DevProfileWiringGuardTest#dev_프로파일_필수_환경변수_목록이_문서와_일치한다` 가
> **yml 실값에서 추출한 집합과 이 표를 직접 대조**한다. 설정만 바뀌고 인수인계가 누락되면 테스트가 깨진다.

**부수 확인(주입이 아니라 값 점검)**

| 키 | 확인 사유 |
|---|---|
| `BATCH_ENABLED` / `TRAINING_SCAN_ENABLED` | local 프로파일 yml 이 리터럴 `false` 로 끄던 값이라 서버 `.env` 에 `false` 가 남아 있으면 **dev 로 전환해도 배치·자동 적재가 발화하지 않는다**. dev 는 기본 활성이므로 제거하거나 `true` 로 둘 것 |
| `SPRING_PROFILES_ACTIVE` | 서버 `.env` 에 `local` 이 남아 있으면 **전환이 무효**(compose 기본값 `dev` 를 덮어씀). 제거할 것 |
| `VLM_CLIENT_ENABLED` | `.env.example` 구버전에 `false` 가 박혀 있었다. `false` 면 VLM 단계가 통째로 SKIPPED |
| `DEIDENTIFY_MOCK_MODE` | `true` 가 남아 있으면 내부 self-fill 로 되돌아간다(외부 무접촉) |
| `FFPROBE_BIN` | Phase 1(A2) — 이제 모든 ffprobe 사용처에 반영된다. 실값이 유효 경로인지 확인 |

#### (1-2) 보안 권고 — dev 노출면 축소 (Phase 5, 서버 `.env`/방화벽 소관)

dev 프로파일은 개발·검수 편의 기능이 켜진 채로 뜬다. 리포지토리에서 끌 수 없는 항목(서버 설정)이라
아래는 **배포 시 사람이 확인할 권고 항목**이다. (프로파일 오배포 자체는 `DevProfileGuard` 가 `ENV=stg|prd`
일 때 부팅을 거부해 막는다 — 코드 게이트.)

| 권고 | 값 | 사유 |
|---|---|---|
| dev 로그인 토큰 발급 차단 | `DEV_LOGIN_ENABLED=false` | `POST /v1/dev/tokens` 는 SecurityConfig `permitAll` 이라 **인증 없이 REVIEWER JWT 를 발급**한다(CWE-306). 실사용자가 붙는 환경이면 반드시 `false`. 필요 시에만 한시 활성화 |
| backend 포트 바인딩 제한 | `18081` 을 **방화벽/보안그룹으로 사내망 한정** (또는 compose `ports` 를 `127.0.0.1:18081:8080` 로 좁히고 리버스 프록시 경유) | 기본 `18081:8080` 은 호스트 전체 인터페이스(0.0.0.0)에 열린다. dev 토글이 켜진 상태에서 외부 도달이 가능하면 위 토큰 발급 경로가 그대로 노출된다 |
| 목 서버 포트 | (조치 완료) `127.0.0.1:9400:9400` | mock-server 는 인증이 없고 콜백 outbound(SSRF 표면)를 가지므로 base compose 에서 루프백 전용으로 발행한다 |

**stg/prd 전환 시 필수 — 비식별 위탁 주소(`KPST_DEID_BASE_URL`)**

`DeidentifyEndpointTrustGuard`(Phase 5)가 비식별 위탁 대상이 **목/시뮬레이터 호스트 또는 루프백**
(`klid-mock-server`, 호스트명에 `mock` 포함, `localhost`, `127.0.0.1`, `::1`, `0.0.0.0`)이거나
`DEIDENTIFY_MOCK_MODE=true` 이면 **prd 부팅을 거부**한다(dev/stg 는 WARN 만). 목 서버는 원본을 그대로
복사해 "비식별본"을 만들므로, 이 상태로 운영이 뜨면 원본(PII)이 비식별본으로 서빙·export·통지된다.

→ prd 는 `KPST_DEID_BASE_URL` 에 **벤더 실서버 주소**를 주입할 것(루프백/목 주소 금지).
공통 기본값(`https://localhost:9201`)은 어차피 `KPST_DEID_CA_CERT_PATH` 없이는 기동하지 않으므로,
운영은 원래부터 이 값을 명시 주입해야 한다.

#### (2) 리포지토리 변경 요약 (Phase 4)

| 대상 | 변경 |
|---|---|
| `docker-compose.yml` (base) | `SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-dev}` (기본 dev + 롤백 가능) |
| 〃 | 외부 연동 주입: `DEIDENTIFY_MOCK_MODE=false` / `KPST_DEID_ENABLED=true` / `KPST_DEID_BASE_URL` / `VLM_CLIENT_ENABLED=true` / `VLM_SERVICE_URL` (전부 mock-server) + `WEBHOOK_CALLBACK_BASE_URL=http://klid-backend:8080/api` |
| 〃 | `mock-server` 서비스 **base 승격**(build: `./mock-server`, `MOCK_OUTPUT_BASE=/app/storage/deidentified`, storage 공유, 포트 `127.0.0.1:9400`(루프백 전용 — Phase 5), healthcheck) |
| `docker-compose.local.yml` | `SPRING_PROFILES_ACTIVE: local` **고정**(base 가 dev 이므로 필수) + mock-server 중복 정의 제거(볼륨 차이분만) + 중복 외부연동 env 제거 |
| `application-dev.yml` | E1 `mock-mode: ${DEIDENTIFY_MOCK_MODE:false}` / E2 `kpst.deid.{enabled,base-url}` mock-server / E3 `vlm.client.{enabled,url}` mock-server |
| `application-local.yml` | 벤더 IP 기본값(`<벤더-KPST-호스트>:9989`) → mock-server 로 교체 |
| `backend/.env.example`, 루트 `.env.example` | dev 필수 키 + 신규 키 반영 |

> **DB 접속의 실제 승자**: compose 가 `SPRING_DATASOURCE_{CONTROL,PORTAL}_JDBC_URL` 을 직접 지정하므로
> 컨테이너 배포에서는 yml 의 `${CONTROL_DB_HOST}` 등이 **해석되지 않는다**(우선순위 높은 소스가 값 제공).
> 이는 서버 `.env` 에 남아 있을 수 있는 관제 DB IP 로 backend 가 붙어 Flyway 가 도는 사고를 막기 위한 의도적 고정이다.
> 외부 DB 로 옮기려면 compose 의 그 두 줄을 바꾼다.

#### (3) 배포 절차 (cudo_246)

```bash
cd /data/klid
git pull                                   # Phase 1~4 반영본

# 1) 서버 .env 갱신 — 위 (1) A/B 표 + 부수 확인 항목
vi .env

# 2) mock-server 이미지 빌드 (신규 서비스)
docker compose -f docker-compose.yml build mock-server
#    (또는 소스 직접 빌드: docker build -t klid-mock-server:latest mock-server/)

# 3) backend 재빌드 + 스택 재기동 (base 단독 — local override 미사용)
docker compose -f docker-compose.yml build klid-backend
docker compose -f docker-compose.yml up -d

# 4) 프로파일이 실제로 dev 인지 확인
docker compose -f docker-compose.yml config | grep SPRING_PROFILES_ACTIVE
docker logs klid-backend | grep "The following .* profile"
```

> ⚠ **로컬 개발 스택은 반드시 두 파일을 함께 지정**한다:
> `docker compose -f docker-compose.yml -f docker-compose.local.yml ...`
> (단일 파일 지정 시 `/data` 바인드 마운트로 재생성되어 로컬 스택이 어긋난다)

#### (4) 기동 후 스모크 (사람이 직접 수행)

| # | 확인 | 방법 | 기대 |
|---|---|---|---|
| 1 | 앱 생존 | `curl -fsS localhost:18081/api/actuator/health/liveness` | `UP` |
| 2 | 프로파일 적용 | `docker logs klid-backend \| grep -i "profile"` | dev 활성 로그 |
| 3 | 목 서버 생존 | `curl -fsS localhost:9400/health` | 200 |
| 4 | 영상 1건 파이프라인 | 관제 학습용 픽업 또는 개발용 업로드로 1건 투입 | 비식별 → 마킹 → VLM → 프레임추출 진행 |
| 5 | **비식별 실 HTTP** | `docker logs klid-mock-server \| grep -E "POST /project\|GET /retrieve_progress"` | 요청 기록 존재 |
| 6 | **self-fill 미진입** | `docker logs klid-backend \| grep -i "mock 비식별\|runMock"` | **기록 없음** |
| 7 | **VLM 실 HTTP + 콜백** | `docker logs klid-mock-server \| grep "POST /v1/videovlm/describe"` / backend 로그의 `/v1/vlm/callback` 수신 | 양방향 기록 존재 |
| 8 | 비식별 산출물 | 공유 스토리지 `deidentified/` 하위에 결과 파일 생성 | 크기 > 0 |

> 5·6·7 이 **이번 전환의 핵심 판정**이다. 요청 기록이 없고 파이프라인만 "성공"이면 여전히 내부 self-fill 이다.
>
> 스모크 8 이 실패하면 **목 컨테이너의 실행 유저(uid)가 공유 스토리지에 쓰지 못하는 경우**를 먼저 본다
> (`klid-storage-init` 가 `999:999`/`775` 로 맞춘다). `docker exec klid-mock-server id` 로 uid 확인.

#### (5) 롤백

| 상황 | 조치 |
|---|---|
| dev 전환 후 기동 실패·기능 이상 | 서버 `.env` 에 `SPRING_PROFILES_ACTIVE=local` 추가 → `docker compose -f docker-compose.yml up -d` (컨테이너 재생성만, 데이터 무손실) |
| 외부 연동만 되돌리고 싶음 | `.env` 에 `DEIDENTIFY_MOCK_MODE=true` (내부 self-fill 복귀) / `VLM_CLIENT_ENABLED=false` (VLM 단계 SKIP) |
| 목 서버만 내림 | `docker compose -f docker-compose.yml stop mock-server` — 단 비식별·VLM 은 연동 실패로 떨어진다(비식별 실패는 `DE_IDNTF_YN='F'` + 원본 보존이라 데이터 유실 없음) |

#### (6) 신규 환경에서의 시드(`DevSeedRunner`) — 알려진 영향

`DevSeedRunner` 는 `@Profile("local")` 이라 **dev 로 전환하면 실행되지 않는다.**

- cudo_246 은 이미 시드가 적재된 DB 를 사용하므로 이번 전환에서 추가 조치는 없다.
- **신규 dev 환경을 새로 구축할 때만** 초기 데이터가 비게 된다. 이때는 `backend/src/main/resources/db/seed/dev-seed.sql`
  을 DB 에 직접 적재한다(멱등):
  `docker exec -i klid-postgres psql -U <user> -d <db> < backend/src/main/resources/db/seed/dev-seed.sql`
- 프로파일 조건을 넓히는 코드 변경은 이번 범위 밖이다(시드 자동 적재가 dev 로 새면 운영 성격 환경에 개발
  데이터가 들어갈 표면이 생기므로, 별도 결정 사항으로 남긴다).

---

### 4-3. Phase 6 — local VLM 실연동 배선 + 목 서버 로깅 노출

로컬 도커 스모크에서 **비식별(KPST)만 실 HTTP 로 돌고 VLM 은 `skipped (disabled)` 로 통째로 비는**
상태가 확인됐다. `application-local.yml` 이 공통 기본값(`vlm.client.enabled=false`)을 그대로
상속했기 때문이며(Phase 4 의 E3 는 dev 에만 적용), 루트 `.env` 의 `VLM_CLIENT_ENABLED=false` 가
compose 기본값(`:-true`)까지 덮고 있었다.

| 대상 | 변경 |
|---|---|
| `application-local.yml` | `vlm.client.enabled: ${VLM_CLIENT_ENABLED:true}` / `vlm.client.url: ${VLM_SERVICE_URL:http://klid-mock-server:9400}` 신설(dev E3 와 동일 관례) |
| `WebClientConfig.vlmWebClient` | local/dev 프로파일에 한해 **평문 http + 내부 호스트 위탁 허용**(WARN 1회). stg/prd·프로파일 미지정은 기존 강제(HTTPS + 공인 호스트) 유지, `prd` 프로파일 또는 `ENV=prd` 표식이면 완화 미적용 |
| `mock-server/app/main.py` | `configure_logging()` — 앱 로거를 stdout 으로 연결 |

- **왜 코드 변경이 필요했나**: 기존 검증은 HTTPS 전용 + 사설/루프백 차단이라 목 서버
  (`http://klid-mock-server:9400`, TLS 미지원)를 가리키면 **빈 생성 실패 → 앱이 기동조차 못 했다**
  (2026-07-25 로컬 배선 시도 실측·원복). Phase 4 에서 dev yml 도 같은 값을 갖게 됐으므로 **dev 기동에도
  잠재해 있던 차단**이며, 이번에 함께 해소된다. 완화 범위는 스키마·호스트까지이고 빈 값·placeholder
  호스트 차단은 그대로다.
- **기본 호스트를 컨테이너명으로 둔 이유**: 로컬 주 사용 경로가 도커 스택(`-f docker-compose.yml -f
  docker-compose.local.yml`)이고 같은 파일의 `kpst.deid.base-url` 도 동일 관례다. 네이티브
  `bootRun` 은 컨테이너명을 해석하지 못하므로 `VLM_SERVICE_URL=http://localhost:9400` 을 주입한다
  (미주입 시 기동은 되고 호출 시점에만 실패 — 부팅은 막지 않는다).
- **서버 `.env` 조치**: dev/운영 서버는 추가 키 없음. 다만 `.env` 에 `VLM_CLIENT_ENABLED=false` 가
  남아 있으면 프로파일 기본값을 이겨 VLM 이 계속 비므로 **제거(또는 `true`)** 한다.
- **목 서버 로깅**: uvicorn 기본 설정은 root 로거를 구성하지 않아 앱 로거(`app.services.deid_sim` 등)
  INFO 가 버려지고 WARNING 도 포맷 없이 새어나갔다 — Phase 5 에서 넣은 경계 위반·콜백 거부 방어 로그가
  **동작해도 `docker logs` 에서 보이지 않던 관측성 결함**. `logging.basicConfig`(root 무핸들러일 때만
  적용)로 stdout 에 연결했고 uvicorn 자체 로거(`propagate=False`)와 중복되지 않는다.

#### 보안 재검토 반영(MED 3건, 2026-07-28)

- **완화 판정을 "local/dev 활성 AND stg/prd 비활성"으로 강화**: `acceptsProfiles` 가 OR 이라
  `SPRING_PROFILES_ACTIVE=dev,stg` 면 stg 도 평문 http 로 기동되던 구멍을 막았다. 프로파일과
  `ENV` 표식 모두 `stg`/`prd` 를 거부 목록으로 둔다(`DevProfileGuard.DEPLOYED_ENVS` 와 동일 기준).
- **완화 경로에도 메타데이터 대역 차단**: 호스트가 해석되면 링크로컬 대역(IPv4 RFC 3927 =
  클라우드 메타데이터 주소가 속한 대역, IPv6 `fe80::/10`)은 dev/local 에서도 거부한다.
  **해석 실패(컨테이너명 등)는 기존대로 통과**하므로 네이티브 기동은 그대로다.
- **평문 구간 토큰 경고**: 평문 http + `vlm.client.token` 설정 시 WARN(`tokenLength=` 만 출력, 값 미출력).
  개발 목 연동을 막지 않기 위해 거부가 아닌 경고다 — 운영은 HTTPS 강제라 해당 없음.

---

## 5. 요약

| 구분 | 건수 |
|---|:---:|
| 제거(사문화 키) | 6 (B1~B5 + kpst.augment) |
| 이동 | 1 (augment 모드) |
| 신규 | 6 (stg kpst·mock-mode, prd multipart 2, stg 로깅, local augment 위치) |
| 형식변경(기본값 제거·placeholder 화) | 8 (dev DB 3 + dev kpst 1 + dev/stg 토글 4) |
| 값 변경(공통 승격) | 2 (multipart 2키) |
| **D1 암묵 기본값 명시(신규 선언, 값 불변)** | **14** (authoring 12 + portal.upload.sweep 2) |
| **D2 `@Value` → record 통합** | **4** (+ sweep 2키는 스케줄러 제약으로 placeholder 유지) |
| **Phase 4 — 프로파일 전환·실연동 배선** | compose 2파일 + dev/local yml 2파일 (dev 필수 env **15키** 확정, §4-2) |
| **배포 조치 필요** | **stg 1~2키 · dev 15키 + 부수 확인 5건 + 운영 점검 2건** (D1·D2 는 전부 불요) |
