# 백엔드 application*.yml 프로퍼티 정리 — 조사 리포트

- 일자: 2026-07-27
- 대상: `backend/src/main/resources/application{,-local,-dev,-stg,-prd}.yml` + `backend/src/test/resources/application-local.yml` (732줄)
- 대조 근거: main 소스 전역 `@Value` / `@ConfigurationProperties` / `@ConditionalOnProperty` / `Environment.getProperty` 참조 전수 grep
- 범위 제외(합의): ai-server / frontend / docker-compose / .env, 시크릿 환경변수화(발견 시 보고만)

> 판정 등급 — **확정**: 코드 참조 실측으로 단정 가능 / **검증필요**: 정황은 명확하나 실행/기동 확인 권장

---

## A. 실제 동작에 영향 있는 결함 (우선 수정 대상)

### A1. `kpst.augment.external.mode` 오배치 — local 증강 시뮬레이터가 죽어 있음 · **확정 · HIGH**

| 항목 | 내용 |
|---|---|
| 위치 | `application-local.yml:103-105` |
| 현상 | yml 은 `kpst.augment.external.mode: dev` 로 선언 |
| 코드 | `authoring.augment.external.mode` 를 읽음 — `DevAugmentCallbackSimulator`(`havingValue="dev"`), `NoopExternalAugmentClient`(`havingValue="noop", matchIfMissing=true`) |
| 결과 | local 에서 시뮬레이터가 **한 번도 활성화된 적 없음**. `matchIfMissing=true` 로 Noop 이 로드되어 외부 증강 콜백 자족 플로우가 무동작. `kpst.augment.*` 는 아무도 안 읽는 유령 키 |
| 수정 | `authoring.augment.external.mode` 로 이동 |

> 같은 파일 주석(70-77행)에 "과거 kpst 하위 오배치 → cors/dev 를 authoring 으로 교정" 이력이 남아 있다. **augment 만 교정에서 누락**된 잔여 케이스.

### A2. ffprobe 경로 프로퍼티 키 분열 · **확정 · HIGH**

| 키 | 정의(yml) | 참조(코드) |
|---|---|---|
| `authoring.ffmpeg.ffprobe-binary` | ✅ `application.yml:194` (`${FFPROBE_BIN:ffprobe}`) | `PortalVideoProbeFfprobe`, `DevAutolabelTestService`, `DurationProbeFfprobe` |
| `authoring.ffprobe.binary` | ❌ **미정의** | `BrampVideoProbe:45` (기본값 `ffprobe`) |

`FFPROBE_BIN` 으로 절대경로를 주입해도 `BrampVideoProbe` 만 반영되지 않아 PATH 에 ffprobe 가 없는 배포에서 이 경로만 실패한다. → `authoring.ffmpeg.ffprobe-binary` 로 통일.

### A3. prd HikariCP 풀 설정이 바인딩되지 않을 가능성 · **검증필요 · HIGH**

- `ControlDataSourceConfig`/`PortalDataSourceConfig` 는 `DataSourceBuilder.create().type(HikariDataSource.class).build()` + `@ConfigurationProperties(prefix="spring.datasource.control")` → **HikariDataSource 에 직접 바인딩**(Spring Boot 오토컨피그의 `spring.datasource.hikari.*` 규약이 적용되지 않음).
- 이 형식과 정합한 것은 테스트 yml: `spring.datasource.control.maximum-pool-size: 2` (중첩 없음).
- 그런데 **prd 만** `spring.datasource.control.hikari.maximum-pool-size: 20` 중첩 형식(`application-prd.yml:8-9, 16-18`). `HikariDataSource` 에 `hikari` 속성이 없어 **unknown field 로 조용히 무시**될 것으로 보이며, 그렇다면 운영 풀은 의도한 20/5 가 아니라 기본값(10)으로 동작한다.
- 검증 방법: prd(또는 동일 형식 재현)에서 기동 후 `HikariPool-*` 로그 또는 actuator 로 실제 `maximumPoolSize` 확인. 확인되면 `hikari:` 중첩 제거.

### A4. stg 프로파일 KPST 공백 → 기동 실패 가능 · **검증필요 · HIGH**

공통 기본값: `kpst.deid.enabled=true`, `base-url=https://localhost:9201`, `ca-cert-path=`(빈값).
`KpstWebClientConfig` 는 base-url 이 **https 이고 ca-cert 가 비었으면 빈 생성 실패(fail-closed, CWE-295)**.

| 프로파일 | kpst 블록 | 결과 |
|---|---|---|
| local | `base-url: http://<벤더-KPST-호스트>:9989` override | http → ca-cert 불요, 기동 OK |
| dev | 동일 override | 기동 OK |
| **stg** | **블록 자체 없음** | 공통 https 기본값 상속 → `KPST_DEID_BASE_URL`(또는 CA 경로) 환경변수 주입이 없으면 **부팅 차단** |
| prd | 블록 없음 | 동일 조건 (운영은 환경변수 주입 전제) |

부수: stg 는 `deidentify.mock-mode` override 도 없어 공통 기본 `false` — 실제 KPST 위탁 경로로 동작한다(의도 확인 필요).
부수: `KpstWebClientConfig` javadoc 은 "기본 false" 라고 적혀 있으나 실제 yml 기본값은 `true` — 주석 드리프트.

---

## B. 미사용·사문화 프로퍼티 (제거 후보)

| # | 키 | 위치 | 코드 참조 | 비고 |
|---|---|---|---|---|
| B1 | `authoring.jwt.algorithm` | `application.yml:125` | 0건 | HS256 은 코드 상수로 고정된 것으로 보임 — 제거 전 JWT 검증 경로 1건 재확인 |
| B2 | `authoring.batch.rate-per-min` | `:181` | 0건 | 배치 정책 주석만 남은 사문화 값 |
| B3 | `authoring.batch.frame-interval-sec` | `:182` | 0건 | `portal.upload.frame-interval-sec`(DB 설정키 `ConfigKeys`)와 무관한 별개 유령 키 |
| B4 | `authoring.integration.deidentify.timeout-ms` | `:172` | 0건 | 타임아웃 실 출처는 `KpstDeidentifyClient` 상수 |
| B5 | `authoring.integration.ai-server.timeout-ms` | `:179` | 0건 | 동일 |
| B6 | `kpst.augment.external.mode` | `local:103` | 0건 | A1 — 이동(제거 아님) |

> 제거는 **키 1건씩 grep 재확인 후** 진행한다(테스트 `@TestPropertySource`·relaxed binding 경유 참조 가능성).

---

## C. 환경별 드리프트

### C1. multipart 블록 3중 복붙
`local:14-18` / `dev:15-19` / `stg:15-19` 가 **완전 동일**(500MB / 1200MB). prd 만 공통값(21MB / 1100MB) 사용.
→ 값이 3벌 복제되어 한 곳만 고치면 갈라진다. 공통으로 승격하고 prd 에서 조이는 방향이 정석(단, 공통 기본을 500MB 로 올리면 prd 는 반드시 명시 override 필요).

### C2. prd 필수 환경변수 강제(`${VAR:?}`)가 1건뿐
현재 `WEBHOOK_HMAC_SECRET_AUGMENT` 만 미설정 시 부팅 차단. 아래는 **미설정이어도 조용히 기동한 뒤 런타임에 기능이 죽는다**:

| 환경변수 | 미설정 시 동작 |
|---|---|
| `STREAM_SIGN_SECRET` | 빈값 → 스트림 서명 URL 발급·검증 전면 fail-closed (영상 재생 불가) |
| `ADMIN_CLAIM_PASSWORD_HASH` | 빈값 → 관리자 role-claim 불가 |
| `CORS_ALLOWED_ORIGINS` | 빈값 → 외부 origin 전면 차단 |
| `KPST_DEID_BASE_URL` / `KPST_DEID_CA_CERT_PATH` | A4 — 기동 차단(이건 fail-closed 로 정상 방향) |

→ prd 에서 `${VAR:?메시지}` 강제 대상을 넓히는 것이 fail-fast 원칙에 부합. (시크릿 값 자체는 이번 범위 밖 — 강제 여부만 다룸)

### C3. dev 프로파일에 내부 IP 기본값 커밋
`application-dev.yml:4` — `${CONTROL_DB_HOST:192.168.x.x}`(관제 내부망 IP 평문), `${CONTROL_DB_NAME:klid_system}`.
stg/prd 는 기본값 없이 `${CONTROL_DB_HOST}`(미설정 시 실패)로 일관. dev 만 내부 IP 가 리포지토리에 평문으로 남는다. → 기본값 제거 권장.
같은 파일 `kpst.deid.base-url` 기본값 `http://<벤더-KPST-호스트>:9989`(local/dev 공통)도 외부 벤더 IP 평문 커밋.

### C4. `connection-init-sql` 이 control 에만 적용
`application.yml:43` 의 `SET lock_timeout='30000'` 은 control 데이터소스에만 있고 portal 에는 없다. Flyway 는 control 만 쓰므로 의도적일 수 있으나 **명시 주석이 없어 누락과 구분되지 않음**.

### C5. 테스트 프로파일이 `local` 이름을 재사용 — main-local 을 상속하지 않고 **가린다**
`backend/src/test/resources/application-local.yml` 은 main 의 `application-local.yml` 과 **같은 리소스 이름**이라 테스트 classpath 가 우선되어 main 파일을 **대체**한다(병합 아님).
결과: 테스트는 main-local 의 CORS·multipart·kpst base-url·augment 설정을 상속하지 않는다. 현재 동작은 의도대로일 수 있으나, "local 을 고쳤는데 테스트에 반영 안 됨" 이라는 착시를 구조적으로 만든다.
→ `application-test.yml` + `@ActiveProfiles("test")` 로 분리 권장(영향 범위가 테스트 전역이므로 별도 결정 필요).

### C6. dev 토글이 dev/stg 에 하드코딩 `true`
`dev:24-27`, `stg:23-27` 의 `authoring.dev.login.enabled / upload.enabled` 가 **환경변수 없이 리터럴 true**. 공통은 `${DEV_LOGIN_ENABLED:false}` 로 fail-closed 설계인데 프로파일이 이를 무력화하고 override 수단도 없앤다(stg 에 dev 로그인 상시 개방). → 최소 `${DEV_LOGIN_ENABLED:true}` 형태로 되돌려 override 가능하게. **보안 표면 사안이라 정책 판단 필요 — 보고만.**

### C7. logging 레벨 분포
local/dev/test 동일(`root: INFO` + `authoring: DEBUG` + `hibernate.SQL: DEBUG`), stg 는 `root: INFO` 만(애플리케이션 로그가 INFO 로 떨어짐 — dev 보다 조용), prd 는 `WARN`/`INFO`. stg 만 `kr.co.cudo.authoring` 레벨 미지정 → 의도 확인 필요.

---

## D. 구조·네이밍 재편 후보

### D1. 코드는 읽지만 yml 에 없는 "암묵 기본값" 12+개
운영자가 조정 가능한 손잡이인데 설정 파일에 흔적이 없어 발견 불가:

```
authoring.control-notify.retry.initial-delay-ms / .interval-ms
authoring.meta-replication.enabled / .interval-sec / .batch-size / .max-retry
authoring.resolution.resize-max-concurrent / .resize-acquire-timeout-sec
authoring.sam2.max-image-bytes
authoring.upload.tus.cleanup.interval-ms / .initial-delay-ms
authoring.webhook.idempotency.in-memory
portal.upload.max-chunk-bytes / .max-label-body-bytes / .probe-timeout-sec
portal.upload.stuck-timeout-minutes / .sweep.interval-ms / .sweep.initial-delay-ms
```
(`authoring.meta-replication.enabled` 는 local/test 에서만 `false` 로 등장 — 공통에 기본값 선언이 없다.)

> **[2026-07-28 해소]** Phase 3 에서 전 키를 공통 `application.yml` 에 **코드 기본값 그대로** 명시했다.
> 키·값·출처 대조표는 `docs/operations/config-key-changes-20260727.md` §3-2 참조.

### D2. `portal.upload.*` 바인딩 이원화
`PortalUploadProperties`(record, 7필드 생성자 바인딩) + 흩어진 `@Value` 6+개가 **같은 prefix** 를 나눠 읽는다. record 로 통합하면 타입 안전 + yml 문서화가 한 번에 해결된다.

> **[2026-07-28 해소]** Phase 3 에서 `@Value` 4개를 record 필드로 흡수(기본값 1:1 이관). 단
> `portal.upload.sweep.*` 2키는 `@Scheduled` 어노테이션 속성(상수 표현식만 허용)이라 이관 불가 —
> placeholder 유지 + yml 명시로 처리했다. 상세는 `docs/operations/config-key-changes-20260727.md` §3-3.

### D3. 최상위 prefix 난립 — 외부 연동 위치 원칙 불일치
`authoring.integration.{deidentify, ai-server}` 는 `authoring` 하위인데, 같은 성격의 `kpst.*` / `vlm.*` / `webhook.*` / `portal.*` 은 최상위. 동일 축(외부 연동)이 두 계층에 갈려 있다.

### D4. 개념 중복 prefix
| 개념 | 흩어진 위치 |
|---|---|
| 스트리밍 | `authoring.stream.*`(서명URL) vs `authoring.storage.stream-chunk-size`(청크 크기) |
| webhook | `webhook.hmac.*`(최상위) vs `authoring.webhook.callback-base-url` |
| ffprobe | A2 (`authoring.ffmpeg.ffprobe-binary` vs `authoring.ffprobe.binary`) |

> D3·D4 의 prefix 이동은 **코드 `@Value` 문자열 + 배포 환경변수 매핑 + 문서**를 동시에 바꿔야 하므로, A/B/C 수정과 분리해 별도 결정하는 것을 권장한다(운영 배포와 동시 릴리스 필요).

---

## 요약

| 구분 | 건수 | 성격 |
|---|---|---|
| A. 동작 결함 | 4 | A1·A2 확정(코드 대조), A3·A4 검증필요(기동 확인) |
| B. 미사용 키 | 6 | 제거 대상(1건씩 재확인 후) |
| C. 환경 드리프트 | 7 | C1·C3·C4 정리 / C2·C6 정책 판단 / C5 별도 결정 |
| D. 구조 재편 | 4 | D1·D2 저위험 / D3·D4 배포 동반 변경 |

---

## E. 부록 — 본 서비스 내부 "목업 설정부" 전수 인벤토리 (2026-07-27 추가 조사)

원칙: **내부 self-fill 금지 — 모든 외부 연동은 HTTP로 실서버(dev=mock-server)를 바라본다.**

| # | 스위치 | 구현 | 유형 | 현행 dev 값 | 목표 |
|---|---|---|---|---|---|
| E1 | `authoring.integration.deidentify.mock-mode` | `DeidentifyStep.runMock()` | **self-fill** — 외부 무접촉, 원본을 비식별 경로로 복사 | `true` | **`false`** |
| E2 | `kpst.deid.enabled` + `base-url` | `KpstDeidentService` / `KpstWebClientConfig` | 정상 외부 HTTP | `true` / 벤더 실서버 IP | `true` / `http://klid-mock-server:9400` |
| E3 | `vlm.client.enabled` | `VlmClient` | **NO-OP skip** — false면 외부 호출 없이 `SKIPPED` 반환, VLM 단계가 통째로 빔 | `false`(공통 기본) | `true` + url=mock-server |
| E4 | `authoring.augment.external.mode` | `NoopExternalAugmentClient`(@Primary, `matchIfMissing=true`) | **가짜 성공** — 외부 호출 0, 무조건 `true` 반환 | noop | **HTTP 구현 필요(아래)** |
| E5 | 〃 `=dev` | `DevAugmentCallbackSimulator` | **완전 self-fill** — 저작도구가 스스로 HMAC 서명 콜백을 자기 자신에게 POST해 결과를 생성 | 미사용(A1 오배치로 local조차 무동작) | dev에선 사용 안 함 |
| E6 | ai-server `AI_MOCK_MODE` / `weights_missing` | `ai-server/app/config.py` | ai-server 자체 mock 응답 | 가중치 있으면 실추론 | **실추론 유지** |
| E7 | mock 응답 방어장치 | `AutolabelOnlineService`·`Sam2SegmentService`·`PortalSam2Service`·`YoloAutolabelStep` | ai-server가 mock이면 좌표를 버리고 빈 결과+WARN | 활성 | **유지**(안전장치) |

### E-핵심: 증강(augment)만 "실서버를 바라볼 수단"이 없다

- mock-server에는 스텁이 **이미 있다** — `POST /v1/augment`, `GET /v1/augment/status`.
- 그러나 백엔드 `ExternalAugmentClient` 구현체는 **noop(가짜 성공)과 self-simulator 둘뿐**이고, **실제 HTTP를 쏘는 구현체가 존재하지 않는다.**
- 따라서 증강을 mock-server로 보내려면 `HttpExternalAugmentClient`(WebClient+Resilience4j) **신규 구현**이 필요하다 — 설정 정리 범위를 넘는 개발 작업이며, 관제 확정 "생성형 AI API 연동명세서 v1.1"과의 계약 정합(job_id 발급 주체·prompt 구조·results[] 다건)도 함께 결정해야 한다.

> **[2026-07-28 해소 — Phase 4]** E1·E2·E3 배선 완료(`application-dev.yml`: mock-mode 기본 false /
> `kpst.deid.base-url`·`vlm.client.{enabled,url}` = `http://klid-mock-server:9400`). E6·E7 은 원안대로 유지.
> **E4·E5(증강)는 미해소** — 실 HTTP 클라이언트가 없어 여전히 noop(가짜 성공)이다(아래 E-핵심).
> 배선값·배포 절차는 `docs/operations/config-key-changes-20260727.md` §4-2 참조.

### E-부수: dev 프로파일이 로드된 적 없음

> **[2026-07-28 해소 — Phase 4]** base compose 를 `${SPRING_PROFILES_ACTIVE:-dev}` 로 전환하고
> `mock-server` 서비스를 base 로 승격했다(local override 는 `SPRING_PROFILES_ACTIVE: local` 로 고정 +
> 볼륨 차이분만 유지). 회귀 차단은 `DevProfileWiringGuardTest` 가 담당한다.

`docker-compose.yml:61` = `SPRING_PROFILES_ACTIVE: local` → cudo_246(dev)은 base compose 단독 기동이므로 **`application-dev.yml`이 한 번도 적용된 적이 없다.** dev 배포에는 `mock-server` 서비스도 없고(local override 전용), backend env에 KPST/VLM/증강 배선도 없다. 도커는 local/dev 전용이고 stg/prd는 미사용이므로 base compose에 mock-server를 추가해도 운영 오염 위험은 없다.
