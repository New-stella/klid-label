# 19. 외부 시스템 · 보안 · CVAT 포팅 · 설계 문서

> 출처: CLAUDE.md, R1 NFR, `.claude/rules/security.md`, 코드(`common/client`, `webhook`, `common/util`), docs/design
> 관련: 전체 페이지

## 19.1 외부 시스템

### A. 연동 외부 시스템 (호출/통신)

| 외부 시스템 | 연동 | 코드 |
|------------|------|------|
| **관제서버** | JWT 발급, TASK_COMPLETED/MODIFIED 통지 수신, 조회 API + View SELECT, MNG_* 소유 | `ControlNotifyClient`, `controlnotify/` → [15](15-control-notify.md) |
| **포털 서버** | 포털 DB 공유(데이터마트 영상 Load) | `PortalDataSourceConfig`, `portal/` → [16](16-portal.md) |
| **비식별화 서버** | 영상 비식별 위탁 + 콜백 (발주기관 SW 직접구매) | `DeidentifyClient`, `webhook/DeidentifyResultController` → [08](08-deidentification.md) |
| **외부 VLM 서비스** | 시계열 메타 호출 + 콜백 | `VlmClient`, `VlmTimeseriesStep`, `webhook/VlmResultController` → [09](09-vlm-timeseries.md) |
| **외부 증강/생성 시스템** | 증강 위탁 + 콜백 | `ExternalAugmentClient`, `webhook/AugmentResultController` → [14](14-augmentation.md) |

> **ai-server(YOLO/SAM2/VLM 추론)는 외부 아님** — 모노레포 내부 별도 프로세스(`AiServerClient`). → [11](11-ai-assisted.md)

### B. 범위 외 시스템 (책임 위임)

데이터마트 · 생성형 AI 본체 · VLM 모델 본체 · 영상 합성 모델 본체 · 학습데이터 Export → 외부 책임. 저작도구는 연동/검수만. → [01 §1.2](01-system-overview.md#12-책임-범위)

### 공통 연동 인프라
- **Resilience4j** — 모든 외부 호출 타임아웃/재시도/서킷 (VLM 45s, ai-server 60s, 비식별 ~70s)
- **웹훅 멱등성** — `webhook/` + `LS_WEBHOOK_IDEMPOTENCY` (In-Memory/Persistent Ledger), HMAC + idempotencyKey
- **Fallback 큐** — `LS_CONTROL_NOTIFY_FALLBACK`, `LS_GITEA_FALLBACK_QUEUE`

### 웹훅 인증 (2026-07-25 개편 — 1차 검증 CRITICAL 대응)

콜백 경로는 `SecurityConfig` 에서 `permitAll` 이라 **`HmacWebhookFilter` 가 유일한 인증 수단**이다.
1차 전수 검증에서 `POST /api/v1/%61ug/callback`(a→`%61`)이 필터를 건너뛰고 컨트롤러에 도달해
**무인증으로 증강행을 전이**시킨 사실이 실증되어(CWE-436 → CWE-288) 아래로 개편했다.

| 축 | 정책 |
|----|------|
| 경로 판정 | `WebhookProtectedPaths` **allowlist**(`/v1/aug/**`, `/v1/vlm/**`). Spring MVC 라우팅과 **동일한** `RequestPath`+`PathPattern` 사용(자체 디코딩 구현 금지). 판정 불가·예외 = **보호**(fail-closed) |
| 이중 게이트 | 필터가 통과 요청을 `WebhookGuardedRequest` 래퍼로 감싸고, `WebhookGateInterceptor` 가 컨트롤러 진입 직전 증거 유무를 재확인 → 증거 없으면 401 |
| 필터 등록 | Security 체인 1곳으로 고정(`WebhookGateConfig` 가 서블릿 자동 등록 비활성) |
| 시크릿 | `webhook.hmac.secret.augment` 32B 이상 필수. **빈 값이면 애플리케이션 기동 실패**(조용한 401 금지). 리포에 커밋된 **공개 placeholder 값은 local 외 프로파일에서 기동 차단**(공개 키 서명 위조 차단, CWE-1392/798). `.env.example`·compose 는 실값·기본값 폴백을 두지 않고 `openssl rand -hex 32` 주입을 요구 |
| replay 방지 | 서명 nonce 를 `LS_WHK_SIGN_USE` 에 1회성 소비(노드 공유). 키는 **정규화 경로**(`canonicalPath`) 기준 — 원시 URI 를 쓰면 `%61ug` 류 인코딩 변형마다 키가 갈라져 같은 서명이 전부 신규로 통과한다(CWE-294). **서명 검증 성공 이후에만** 기록(pre-auth write DoS 차단). 하류가 5xx/예외로 실패하면 **nonce 예약을 해제**해 동일-바이트 재전송이 다시 처리되게 한다(결과 영구 유실 차단, CWE-754). 중복은 **409**(인증 실패 401 과 구분), 저장소 장애는 **503**(fail-closed + 재시도 유도) |
| timestamp | 과거 방향 `webhook.hmac.timestamp-window-seconds`(기본 300s), 미래 방향은 시계 오차 30s 만 |
| rate limit | **진실원은 `LS_WHK_FAIL_NMTM` 공유 집계**, JVM-local 카운터는 DB 왕복을 줄이는 **캐시**(별도 임계가 아님). 실패는 **차단에 이르기까지 매번 공유에 기록**하고, 차단 이후에는 기록하지 않는다(pre-auth write 를 IP·창당 5회로 상한). `isLimited` 는 로컬 미차단 시 **반드시 공유를 조회**(현재+직전 분 버킷)해 다른 노드의 차단을 승계·로컬 각인한다. → **노드 A 5회 차단 시 노드 B 도 즉시 차단**(A-ISSUE-14). 명시적 해제는 **서명 검증 성공(HMAC 경로)에서만** — 무서명 경로의 하류 2xx 는 해제 신호로 쓰지 않는다(이미 처리된 `request_id` 하나로 카운터를 영구 0 으로 만들 수 있었음). 그 외에는 분 단위 창 만료로 자연 소멸. 만료 행은 `WebhookGuardPurgeJob` 이 주기 정리(조건부 DELETE 라 2노드 동시 실행 무해). 공유 저장소 장애 시 **fail-open** |
| clientIp | `webhook.trusted-proxy-cidrs` 안에서 들어온 요청만 `X-Forwarded-For` 해석(우측부터 신뢰 홉 제거, IPv4/IPv6 리터럴만 허용). 미설정 시 XFF 전면 무시(CWE-348 위조 차단). **prd 는 명시 필수** — 프록시 대역 CIDR 또는 직접 노출을 뜻하는 `none`, 미설정 시 기동 차단(미설정 상태의 LB IP 단일 키 집계로 정상 콜백까지 429 되는 것을 방지). **CIDR 형식 오류(오타·호스트명)도 기동 차단** — 조용히 무시하면 matcher 가 비어 방어가 꺼진 채 기동한다. **배포 템플릿·`.env.example` 은 `none` 을 기본값으로 제공하지 않는다**(안전하지 않은 답을 기본값으로 건네지 않음) |
| VLM 콜백 | 벤더 확정 계약(v2.0.1) **무서명** 규격이라 HMAC 미적용. ①IP allowlist(`webhook.vlm.allowed-ip-cidrs`, **prd 명시 필수** — 미적용이면 `none`) ②rate limit·size cap(4MB) ③`request_id` 발급 게이트 **3계층**으로 보호. rate limit 은 필터 단계 실패뿐 아니라 **서비스 계층 인증 실패(미발급 `request_id` → 401)까지 집계**한다(위조 request_id 로 비관적 락 SELECT 를 무제한 유발하는 경로 차단). 단 **클라이언트 IP 를 발신자로 귀속할 수 없으면**(신뢰 프록시 미설정인데 XFF 관측 = 프록시 뒤 설정 누락) 하류 실패 집계를 **비활성**하고 WARN 만 남긴다 — 그 상태에서 집계하면 공격자 5회 실패가 정상 벤더 콜백 전건을 차단해 시계열 메타가 유실된다(가용성 우선) |
| 관측 | 인증 실패 시 `webhook.auth.failed{path,reason}` 카운터(Micrometer). `path` 태그는 `aug`/`vlm`/`other` **저카디널리티 상수** — 원시 URI 를 태그로 쓰면 공격자가 영구 보존 Meter 를 무한 생성한다(CWE-770) |

## 19.2 보안

기준: [`.claude/rules/security.md`](../../.claude/rules/security.md) (OWASP Top 10 2025, CWE Top 25, Fortify/CodeQL).

| 영역 | 정책 |
|------|------|
| 인증 | 관제/포털 JWT 인계(`JwtAuthenticationFilter`), 독립 로그인 없음, `alg:none` 금지 |
| 인가 | `@PreAuthorize` 역할 분기, IDOR 차단(`LabelAccessGuard`, 본인 배정 외 403). **`/v1/**` 포괄 매처는 채널(CHANNEL_INTERNAL) + 역할(REVIEWER/WORKER) 결합** — 역할 미배정(role=null) 사용자는 조회 API 도 403 (예외: `/v1/me`·`/v1/auth/**` 온보딩 경로, 서명 스트림 `STREAM_SIGNED`) |
| 입력 검증 | `@Valid`, 시스템 설정 화이트리스트 키(CWE-20), Mass Assignment 방지(DTO 분리) |
| SSRF (CWE-918) | 외부 연동 base-url 고정, 사용자 입력 URL 구성 금지 |
| 경로 순회 (CWE-22) | 비식별 출력 `STORAGE_DEIDENTIFIED_PATH` 하위 강제, `Path.normalize` |
| 정보 노출 (CWE-209) | 응답에 내부 파일 경로·스택트레이스 미포함 |
| 민감정보 (NFR-005) | PII·토큰 로그 출력 금지(Logback MaskingPatternLayout), 영상 암호화 저장, 통지 페이로드 PII 미포함 |
| 파일 업로드 | 확장자 allowlist + 크기 제한 + MIME 검증 |
| DoS (CWE-770) | RLE 변환 MAX_PIXELS 제한, 이미지 20MB 상한, diff 500건 상한 |

### 인증·인가 표면 (2026-07-25 개편 — 1차 검증 A/B 이슈 대응)

| 축 | 정책 |
|----|------|
| 권한 자가부여(`POST /v1/auth/role-claim`) | 자가부여 가능 역할은 **WORKER 단일**(`RoleClaimService.allowedClaimRoles()` 화이트리스트를 `claim()` 이 실제 참조). REVIEWER(사실상 관리자)는 공유 정적 패스워드로 부여 불가 — 기존 REVIEWER 의 `/manage` 경로가 담당(CWE-269/1392). 검증 순서는 **① 역할 화이트리스트 → ② 채널·기보유역할 → ③ rate limit → ④ 패스워드 → ⑤ upsert** (잘못된 role 시도가 정상 사용자 쿼터를 소모하지 않도록) |
| 자가부여 rate limit | **계정 축 + 엔드포인트 전역 축**(`authoring.auth.role-claim.account-attempts-per-minute` 5 / `global-attempts-per-minute` 50)을 `LS_AUTHRT_GRANT_ATMPT` 공유 집계로 강제 → 무권한 계정 A/B/C 를 번갈아 쓰는 증폭·2노드 임계 2배를 함께 차단(CWE-307). 로컬 카운터는 Caffeine **TTL 10분 + maximumSize 1만** 으로 회수(구 `ConcurrentHashMap` 영구 잔존 = CWE-770). **저장소 장애 시에도 로컬 카운터가 임계를 강제**(완전 fail-open 금지). IP 축은 두지 않는다 — 신뢰 프록시 파싱 없이 IP 를 키에 넣으면 프록시 뒤 전 사용자가 한 IP 로 수렴해 한 명의 실패가 전원을 잠근다 |
| 영상 스트림 인가 | `/v1/videos/{rawSn}/stream`·`/stream-url` 진입부에서 `LabelAccessGuard.verifyRawAccess`(REVIEWER 전체 / WORKER 본인 배정) — 구 동작은 역할만 검사해 미배정 WORKER 가 타인 영상을 206 재생(CWE-639). `@Cacheable` 내부가 아니라 **진입부** 판정이라 캐시 히트가 인가를 건너뛰지 않는다 |
| 서명 스트림 URL | `sig = HMAC(secret, "{rawSn}.{exp}.{userNo}.{nonce}")`. `nonce` 는 발급 응답의 **HttpOnly·SameSite=Lax 쿠키**로만 전달되고 URL 에 없다 → **URL 만 유출된 제3자는 재생 불가**(CWE-294). 쿠키는 TTL 동안 재사용 가능(브라우저가 같은 URL 로 다수 Range 요청을 보내므로 1회용 소비는 재생을 깨뜨림). 발급 시 기존 쿠키가 있으면 값을 유지(다중 영상 동시 재생 보호). `StreamSignatureFilter` 는 principal 에 **실제 발급자 sub + 재조회 역할**을 채우되 `ROLE_*` authority 는 부여하지 않는다(서명 컨텍스트 확대 금지) |
| dev 토큰(`/v1/dev/tokens`) | 인증 없이 임의 `userNo`/`role` 토큰을 발급하는 경로. **local/dev 만 ON, stg 는 OFF**. prd 에서 `authoring.dev.login.enabled=true` 면 **부팅 거부**(`DevToggleProfileGuard` — WARN 로그가 아닌 설정 fail-fast). 빈 등록(`@ConditionalOnProperty`)과 SecurityConfig permitAll 매처가 **동일 프로퍼티 단일 판정** |
| 로그 마스킹 | local 프로파일 CONSOLE appender 를 `LayoutWrappingEncoder` + `MaskingPatternLayout` 으로 배선(구 설정은 Layout 을 `conversionRule converterClass` 로 잘못 등록해 **어떤 패턴도 참조하지 못하는 죽은 코드**였고 실제 출력은 평문 `%msg` — CWE-532). dev/stg/prd 는 JSON 인코더 `MaskingJsonValueMasker`. 검증은 로직 단위가 아니라 **appender 출력 종단**(`LocalLogMaskingIT`) |
| 프로파일 가드 | `LocalProfileGuard` — ENV=dev/stg/prd 인데 active profile 에 local 이 포함되면 부팅 거부(커밋된 개발용 기본값 운영 유입 차단, CWE-798). 판정 본체를 `verify(String envName)` 로 추출해 회귀 테스트 보유 |

## 19.3 CVAT 포팅

CVAT(Django+TS) → 본 프로젝트(Spring Boot+TS). 전체 fork 아닌 **9개 독립 모듈 Phase별 포팅** (`docs/analysis/portable-modules/`).

| 모듈 | 구현 | 위치 |
|------|------|------|
| 트랙 보간 알고리즘 | `TrackInterpolator` (선형 보간) | `batch/interpolation/` → [11](11-ai-assisted.md) |
| MASK ↔ RLE ↔ Polygon 변환 | `MaskRleConverter` (CWE-770 방어) | `common/util/` |
| Polygon 단순화 | `PolygonSimplifier` (Ramer-Douglas-Peucker) | `common/util/` |
| YOLO/COCO 변환 | `YoloCocoConverter`, `CocoJson` 등 | `common/util/` |
| manifest.jsonl / TUS 업로드 | 프레임 매니페스트 / 재개 업로드 | → [05](05-video-management.md) |
| 캔버스 드로잉 패턴 | konva.js | `frontend label/canvas` → [10](10-labeling.md) |
| RQ Worker → Quartz Job 매핑 | 배치 스케줄 | → [07](07-batch-pipeline.md) |

> `cvat/`는 `.gitignore` 참조용. 소스 직접 import 금지 — 분석 문서 기반 Java 재구현.

## 19.4 설계 문서 카탈로그

| 문서 | 내용 | ID 체계 |
|------|------|---------|
| [R1 사용자요구사항정의서](../design/R1-사용자요구사항정의서.md) (v1.17) | SFR 14건(06-03·07·08·09) + NFR 7건 | `RQ-SFR-NN-NN` |
| [R2 유스케이스명세서](../design/R2-유스케이스명세서.md) | 10 서브시스템·9 액터·13 유스케이스 | `KLID-AT-SS/UC/ACT-*` |
| [R3 요구사항추적표](../design/R3-요구사항추적표.md) | SFR→UC→SC→CO 매핑 | - |
| [D1 클래스설계서](../design/D1-클래스설계서.md) | 클래스 | `KLID-AT-CL-*` |
| [D2 사용자인터페이스설계서](../design/D2-사용자인터페이스설계서.md) | 화면 23개 | `KLID-AT-SC-*` |
| [D3 컴포넌트설계서](../design/D3-컴포넌트설계서.md) | 4 UCD별 컴포넌트 | `KLID-AT-CO-*` |
| [D8 엔티티관계모형설계서](../design/D8-엔티티관계모형설계서.md) | ERD | - |
| [D9 데이터베이스설계서](../design/D9-데이터베이스설계서.md) | 테이블 명세 | - |

### 서브시스템 (KLID-AT-SS, R2)
SS-001 사용자/권한 · SS-002 마킹 · SS-003 배치 파이프라인 · SS-004 비식별화 · SS-005 시계열 메타 · SS-006 라벨링 · SS-007 검수 · SS-008 버전관리 · SS-009 데이터 증강 · SS-010 포털.

### NFR
| NFR | 내용 | 목표 |
|-----|------|------|
| NFR-001 | 배치 파이프라인 처리량 | ≥ 1건/분 (Quartz 단일) |
| NFR-002 | 이미지 학습데이터 규모 | ≥ 100,000장 |
| NFR-003 | 영상 학습데이터 규모 | ≥ 5,000건 |
| NFR-004 | 외부 API 연동 복원력 | Resilience4j 100% |
| NFR-005 | 민감정보 보호 | PII/토큰 노출 0건 |
| NFR-006 | 포털 웹 접근성 | WCAG 2.1 AA |
| NFR-007 | 메트릭·추적성 | 메트릭 수집 100%, traceId 전파 |

### SFR (요구사항)
SFR-06-03(해상도 변경) · SFR-07-01~03(증강) · SFR-08-01~05(라벨링·정밀도·버전) · SFR-09-01~05(비식별). 상세 → [R1](../design/R1-사용자요구사항정의서.md).
