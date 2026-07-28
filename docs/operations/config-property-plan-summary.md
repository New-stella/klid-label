# 백엔드 설정 프로퍼티 정리 + dev 프로파일 정상화

## Context

`backend/src/main/resources/application*.yml` 6개 파일(732줄)을 코드의 실제 참조(`@Value`/`@ConfigurationProperties`/`@ConditionalOnProperty`)와 전수 대조한 결과, **설정이 쓰여 있는데 안 먹는 곳**과 **사문화된 키**, **환경 간 드리프트**가 확인됐다. 조사 정본은 `docs/operations/config-property-audit-20260727.md`(A~E 섹션, 근거·파일:라인 기재).

특히 두 가지가 실제 장애 수준이다:

1. **`docker-compose.yml:61`이 `SPRING_PROFILES_ACTIVE: local`로 고정** — cudo_246(dev)은 이 파일 단독으로 뜨므로 **`application-dev.yml`이 한 번도 로드된 적이 없다.** dev가 local 설정을 우회 사용해 왔고, 그 결과 비식별이 내부 self-fill(원본 복사)로, VLM은 `SKIPPED`로 동작해 **실서버 연동이 검증되지 않았다.**
2. **`application-local.yml`의 `kpst.augment.external.mode` 오배치** — 코드는 `authoring.augment.external.mode`를 읽으므로 local 증강 시뮬레이터가 한 번도 활성화된 적이 없다.

의도한 결과: 설정이 실제로 먹는 상태로 되돌리고, dev를 `dev` 프로파일로 전환해 **내부 self-fill 없이 mock-server(:9400)를 실제 HTTP로 바라보게** 한다.

> 상세 Phase별 실행 계획(파일 표·시그니처·테스트 케이스)은 `.claude-plan.md`에 있다. 이 문서는 승인용 요약이다.

## 작업 위치 (Critical)

**모든 구현은 `property` 워크트리에서 수행한다** — `/Users/ck/Documents/workspace/klid/klid-label-worktrees/property` (브랜치 `property`, 현재 main과 동일 커밋 `afa8c528`).

main 워크트리에는 코드 변경을 만들지 않는다. 착수 시 아래를 property 워크트리로 이관한다:

| 산출물 | 현재 위치(main) | 이관 후 |
|---|---|---|
| 조사 리포트 | `docs/operations/config-property-audit-20260727.md` | property 워크트리 동일 경로 |
| 실행 계획 | `.claude-plan.md` | property 워크트리 동일 경로 |

이관 후 main의 사본은 제거해 두 워크트리에 갈라진 정본이 남지 않게 한다. 이후 Phase 1~4의 모든 파일 수정·빌드·테스트는 property 워크트리 기준 경로로 진행한다.

## 범위

**포함**: 백엔드 `application*.yml` 5개 + test yml, 설정을 읽는 소량 Java(`BrampVideoProbe`, `PortalUploadProperties`+사용처), `docker-compose.yml`, `backend/.env.example`, 관련 문서

**제외**(합의):
- 증강 실서버 HTTP 클라이언트 신규 구현 — mock-server에 `POST /v1/augment` 스텁은 있으나 BE엔 noop(가짜 성공)과 자기콜백 시뮬레이터뿐. 관제 "생성형 AI API 연동명세서 v1.1" 계약 정합이 필요해 별도 작업 (**단 A1 키 이동은 포함**)
- C2 prd `${VAR:?}` 강제 확대 / C5 test 프로파일 분리 / D3·D4 prefix 재편 / 시크릿 값 자체의 환경변수화 / ai-server·frontend 설정

## Phase 1 — 동작 결함 4건

| # | 내용 |
|---|---|
| A1 | `application-local.yml`의 `kpst.augment.external.mode` → `authoring.augment.external.mode` 이동 |
| A2 | `BrampVideoProbe`가 읽는 미정의 키 `authoring.ffprobe.binary` → `authoring.ffmpeg.ffprobe-binary`로 일원화 (현재 `FFPROBE_BIN` 주입이 이 경로만 무시됨) |
| A3 | prd의 `spring.datasource.{control,portal}.hikari.*` 중첩 — **먼저 실측 검증** 후 바인딩 안 되면 중첩 제거. 데이터소스는 `DataSourceBuilder`+`@ConfigurationProperties` 직접 바인딩이라 test yml 형식(중첩 없음)이 정답으로 보임 |
| A4 | stg에 `kpst.deid` 블록 신설 — 현재 공통 `https://localhost:9201`+빈 ca-cert를 상속해 `KpstWebClientConfig` fail-closed 기동 차단 조건 |

신규 테스트: `DataSourcePoolBindingIT`(A3 실측), `PropertyKeyConsistencyTest`(코드 참조 키 ↔ yml 정의 대조 회귀 방지)

## Phase 2 — 미사용 키 제거 + 드리프트 정리

- **제거 6건**(키별 main+test+docs grep 0건 확인 후): `authoring.jwt.algorithm`, `batch.rate-per-min`, `batch.frame-interval-sec`, `integration.deidentify.timeout-ms`, `integration.ai-server.timeout-ms`, `kpst.augment.*`
- **C1 multipart 공통 승격**: local/dev/stg 3중 복붙(500MB/1200MB) → 공통으로 올리고 **prd는 21MB/1100MB 명시 override**(상향 방지가 핵심)
- **C3** dev의 관제 내부망(`192.168.x.x`)·벤더 IP 기본값 제거 / **C4** `connection-init-sql` control 전용 의도 주석 / **C6** dev·stg의 dev 토글 하드코딩 `true` → `${DEV_LOGIN_ENABLED:true}` / **C7** stg 로깅 레벨 정합
- 산출물: `docs/operations/config-key-changes-20260727.md`(제거·이동·신규 키 ↔ 배포 환경변수 매핑)

## Phase 3 — 암묵 기본값 명시 + portal 바인딩 통합

- **D1**: 코드만 읽고 yml엔 없는 운영 손잡이 12+개(`control-notify.retry.*`, `meta-replication.*`, `resolution.*`, `sam2.max-image-bytes`, `upload.tus.cleanup.*`, `webhook.idempotency.in-memory`)를 **현재 코드 기본값 그대로** yml에 명시 — 동작 변경 0
- **D2**: `portal.upload.*`가 `PortalUploadProperties` record 7필드 + 흩어진 `@Value` 6+개로 이원화 → record로 통합. 기존 `@Value` 기본값을 `@DefaultValue`로 1:1 이관하고 테스트로 고정(조용한 값 변경 방지)

## Phase 4 — dev 프로파일 전환 + mock-server 배선

- `docker-compose.yml`: `SPRING_PROFILES_ACTIVE: dev` + `mock-server` 서비스 base 승격(`docker-compose.local.yml:76` 정의 기준, storage 공유 볼륨 + `MOCK_OUTPUT_BASE`) + local override의 중복 정의 정리
- `application-dev.yml`: **E1** `deidentify.mock-mode` → `false` / **E2** `kpst.deid.base-url` → `http://klid-mock-server:9400` / **E3** `vlm.client.enabled` → `true` + url
- **유지**: ai-server 실추론(`AI_MOCK_MODE=false`), BE의 mock 응답 방어장치 4곳(`AutolabelOnlineService`·`Sam2SegmentService`·`PortalSam2Service`·`YoloAutolabelStep`)

### ⚠ dev 전환의 최대 리스크 — 필수 환경변수

local yml엔 기본값이 있으나 dev엔 없는 키들이 있어, **누락 시 기동은 되지만 기능이 조용히 죽는다**:

| 키 | 미주입 시 |
|---|---|
| `JWT_SECRET` | 부팅 실패 |
| `STREAM_SIGN_SECRET` | 영상 스트림 서명 전면 fail-closed(재생 불가) |
| `CORS_ALLOWED_ORIGINS` | FE 전면 403 |
| `ADMIN_CLAIM_PASSWORD_HASH` | 관리자 role-claim 불가 |
| `WEBHOOK_HMAC_SECRET_AUGMENT` | 증강 콜백 401 |
| `VLM_SERVICE_URL`(신규) | 기본 `localhost:9400`=컨테이너 자기 자신 → VLM 실패 |
| `KPST_DEID_BASE_URL`(신규) | 공통 https 기본값 → fail-closed |

루트 `.env`엔 앞 5개가 이미 있으나 **cudo_246 서버의 `.env`는 별개 파일**이므로 배포 체크리스트로 산출한다. 부수 영향: `DevSeedRunner`가 `@Profile("local")`이라 dev에선 시드가 돌지 않음(기존 DB엔 적재 완료 전제, 코드 변경은 범위 외).

## 검증

1. **단위/통합**: 백엔드 전체 테스트 GREEN(회귀 0). 신규 — 풀 바인딩 실측, 설정 키 정합, prd multipart 한도 불변, portal record 바인딩·기본값 고정
2. **local 실동작**: `docker compose -f docker-compose.yml -f docker-compose.local.yml up`(두 파일 동시 지정 필수) → 기동 + 기존 플로우 정상
3. **dev 실동작**(핵심): dev 프로파일 기동 → `/actuator/health/liveness` UP → 영상 1건 파이프라인 투입 → **mock-server 접근 로그에 KPST(`POST /project`)·VLM(`POST /v1/videovlm/describe`) 요청이 실제로 남는지** 확인. `DeidentifyStep.runMock()` 미진입 = self-fill 제거 확인
4. **산출물**: 변경 키 매핑 + 서버 `.env` 갱신 체크리스트, `.env.example`·`docs/v2-wiki` 동기화

> 정적 대조만으로는 검증을 대체하지 않는다 — 목업서버 + 배치 구동 실동작 시나리오가 기준이다.
