# Phase 2 부분 재검증 — 권한·인가 표면 정리

> 대상: `A-ISSUE-17`(HIGH) · `A-ISSUE-18` · `A-ISSUE-02` · `A-ISSUE-05` · `A-ISSUE-11` · `A-ISSUE-21` · `A-ISSUE-24` · `B-ISSUE-63` (8건)
> 기준: **실동작** — 로컬 스택(5서비스) + 실HTTP 프로브. 정적 대조로 갈음하지 않음
> 일자: 2026-07-25 | 백엔드 이미지: DEV_FIX 2차 반영본

## 1. 실HTTP 검증 결과 (PM 직접 실행)

참조 데이터 `rawSn=17` — 배정 WORKER `2001` / REVIEWER `1001` / **미배정 WORKER `2002`** / 프레임 12건.

### 1-1. `B-ISSUE-63` + 형제 엔드포인트 (IDOR)

| 경로 | 미배정 W | 배정 W | REVIEWER | 판정 |
|---|:--:|:--:|:--:|:--:|
| `GET /v1/videos/17/stream` | **403** | 206 | 206 | PASS |
| `GET /v1/videos/17/stream-url` | **403** | 200 | 200 | PASS |
| `GET /v1/videos/17` | **403** | 200 | 200 | PASS |
| `GET /v1/videos/17/labels/auto` | **403** | 200 | 200 | PASS |
| `GET /v1/frames/408/image` | **403** | — | 200 | PASS |

**회귀 대조군** — 미배정 W(`2002`)는 *본인 배정* 영상 `rawSn=4` 에 `200`. 인가 추가가 정상 흐름을 막지 않음.

미배정 W 의 403 응답 본문은 `"본인에게 배정되지 않은 영상입니다"` 로, 신규 배정 가드가 발동한 것임을 확인(경로 가드 등 타 원인과 구분됨).

### 1-2. `A-ISSUE-11` 서명 URL 클라이언트 바인딩

| 검증 | 실측 | 판정 |
|---|---|:--:|
| 발급 URL 에 nonce 노출 | `?exp=…&u=2001&sig=…` — **nonce 없음** | PASS |
| 쿠키 보유 + 무헤더 재생 | **206** | PASS |
| **쿠키 없이 URL 재사용** | **401** | PASS |
| 봉인 없는 위조 쿠키 | **401** | PASS |
| Set-Cookie 속성 | `HttpOnly` · `Path=/api/v1/videos` · 값 `{nonce}.{HMAC}` | PASS |

### 1-3. `A-ISSUE-02` role=null(LS_USER_ROLE 미등록) INTERNAL

| 경로 | 실측 | 기대 |
|---|:--:|---|
| `GET /v1/videos` | **403** | 업무데이터 차단 |
| `GET /v1/event-types` | **403** | 업무데이터 차단 |
| `GET /v1/videos/17` | **403** | 업무데이터 차단 |
| `GET /v1/me` | 200 | 온보딩 예외(본인 클레임 반향뿐) |

### 1-4. `A-ISSUE-17`·`A-ISSUE-18` role-claim

| 검증 | 실측 |
|---|---|
| REVIEWER 자가부여 | **403** `"해당 역할은 자가 부여할 수 없습니다"` |
| 8연타 rate limit | **401×5 → 429×3** |
| 공유 카운터 | `ACCOUNT\|9911\|8` · `GLOBAL\|GLOBAL\|5` (계정·전역 **양축** 기록) |

### 1-5. 마이그레이션

`flyway_schema_history` — V131(webhook guard) · **V132(role claim attempt)** 모두 `success=t`.

## 2. ★ Phase 1 보안 회귀 — 발생·검출·복구 전 과정

**이 Phase 에서 가장 중요한 사건이다.** PM 이 `A-11` 의 `Secure` 쿠키 문제를 고치라며 **불필요한 설정을 함께 지시**해 Phase 1 이 닫은 CRITICAL 방어를 열었다.

| 시점 | XFF 회전 12연타 | 공유 실패 카운터 |
|---|---|---|
| Phase 1 완료 시 | 401×5 → 429 | 실제 소스 IP |
| **DEV_FIX 1차 후(회귀)** | **401×12 (429 없음)** | **`198.51.100.1~12` — 공격자 지정값이 그대로 적재** |
| REDESIGN 후(복구) | **401×5 → 429×7** | `192.168.65.1\|5` (실제 소스 IP 1건) |
| DEV_FIX 2차 후(재확인) | **401×5 → 429×7** | 동일 |

**뿌리**: `server.forward-headers-strategy: framework` → `ForwardedHeaderFilter` 가 `Ordered.HIGHEST_PRECEDENCE` 로 모든 보안 필터 앞에 서서 `getRemoteAddr()` 을 XFF 첫 토큰으로 **무검증 치환**. `ClientIpResolver` 의 신뢰 CIDR 대조가 공격자가 고른 값을 대조하게 되고, 그 필터가 XFF 헤더를 숨겨 홉 벗기기·`isValidIp` 가드가 죽은 코드가 됐다.

**`Secure` 쿠키 문제는 프로파일 기반 `.secure(!localProfile)` 만으로 이미 해결**돼 있었다. 즉 이 설정은 **처음부터 불필요했다.**

**재도입 차단(DEV_FIX 2차)** — 신규 `ForwardedHeadersConfigGuard` 가 기동 시 차단. 운영 주입 경로인 환경변수로 실측:
```
docker compose run -e SERVER_FORWARD_HEADERS_STRATEGY=framework klid-backend
→ IllegalStateException: server.forward-headers-strategy 설정은 허용되지 않습니다(전 프로파일 공통) …
  웹훅 rate limit 이 헤더 한 줄 회전으로 완전히 우회됩니다(CWE-348/CWE-307)
→ 부팅 거부
```

| 재도입 축 | 차단 |
|---|:--:|
| `application.yml` | O |
| `application-{dev,stg,prd}.yml` | O |
| **환경변수 `SERVER_FORWARD_HEADERS_STRATEGY`** (온프렘 `env.template` 실주입 경로) | **O (실측)** |
| 시스템 프로퍼티 / 커맨드라인 | O |
| `server.tomcat.remoteip.*`(RemoteIpValve — 동일 위험) | O |

## 3. 자동 테스트

| 시점 | 총 | 실패 |
|---|---:|---:|
| Phase 1 완료 | 3080 | 0 |
| Phase 2 구현 | 3108 | 0 |
| DEV_FIX 1차 | 3130 | 0 |
| REDESIGN | 3136 | 0 |
| **DEV_FIX 2차(최종)** | **3143** | **0** |

전 구간 회귀 0. 스킵 5건은 `MockServerLiveIntegrationIT`(python venv 기동 게이트) — 판정 무관. FE 1515 전건 통과.

## 4. 이슈별 최종 판정

| 이슈 | 판정 | 근거 |
|---|:--:|---|
| `A-ISSUE-17` 공유 패스워드 REVIEWER 자가부여 | **해소** | `allowedClaimRoles()` 를 `claim()` 최상단에 실연결 + REVIEWER 제거(WORKER 단일). 실HTTP 403. 타 upsert 진입점(`UserService`)은 REVIEWER 전용이라 우회 없음 |
| `A-ISSUE-18` rate limit 계정 단위 in-memory | **해소** | 계정·전역 2축 × 공유 DB(노드) 집계, Caffeine TTL+cap 회수, 저장소 장애 시 로컬이 최종 방어선. 실HTTP 401×5→429 + 양축 카운터 확인. **IP 축은 의도적 미도입**(프록시 수렴 사고 회피) |
| `A-ISSUE-02` role=null 내부 조회 전건 통과 | **해소** | `/v1/**` 를 채널+역할 결합으로 상향. 실HTTP 403. `/v1/me` 만 온보딩 예외(본인 클레임 반향뿐) |
| `A-ISSUE-05` dev 토큰 permitAll | **해소** | stg 비활성 + **prd·stg 부팅 거부**(`DevToggleProfileGuard`). local 활성은 정상 |
| `A-ISSUE-11` 서명 URL userNo 바인딩 무의미 | **해소** | nonce 를 서명 canonical 에 포함 + HttpOnly 쿠키 전용 전달 + **서버 비밀·subject 봉인**(nonce fixation 차단). 실HTTP 4항목 |
| `A-ISSUE-21` LocalProfileGuard 테스트 전무 | **해소** | 새 의존성 없이 판정 로직 분리 후 테스트 신설 |
| `A-ISSUE-24` 마스킹 미배선 | **해소(IT 기준)** | 죽은 `conversionRule` 제거 → `LayoutWrappingEncoder`+`MaskingPatternLayout` 배선. `LocalLogMaskingIT` 가 appender 종단 캡처로 검증. ⚠ **런타임 프로브는 비결정적** — 아래 잔여 ③ |
| `B-ISSUE-63` `/stream` 영상 단위 인가 부재 | **해소** | `/stream`·`/stream-url` + **형제 5경로**에 `verifyRawAccess`(캐시 진입부). 실HTTP 전건 |

## 5. 잔여 (2차 이후 확인 대상)

| # | 심각도 | 내용 |
|:-:|:--:|---|
| ① | **HIGH(범위 밖)** | **`GET/PUT /v1/labels/{lblSn}/attrs` IDOR** — `lblSn` 순회로 타 영상 라벨 속성 **읽기+수정** 가능. 이 경로는 포털도 사용하는데 `LabelAccessGuard` 는 `PORTAL_USER` 를 403 하므로 그대로 적용 시 포털이 깨진다. **내부/포털 축 분기 설계 후 별도 처리 필요.** Phase 2 의 8건에 없던 신규 발견이라 임의 편입하지 않음 |
| ② | MEDIUM(기존) | `GET /v1/videos/{rawSn}/frames/{n}/image` 가 **전원 403**(`"허용되지 않은 이미지 경로입니다"` — 기존 경로 가드). `FrameImageService` 는 이번에 diff 0 이므로 Phase 2 회귀 아님. FE 가 쓰는 `/v1/frames/{srcSn}/image` 는 정상 |
| ③ | MEDIUM | `A-24` 런타임 미확증 — 로그에 평문 토큰·패스워드 0건이지만 마스킹 마커(`***`)도 0건. "마스킹이 동작"이 아니라 "마스킹 대상 로그가 발생하지 않음". 배선은 IT 가 종단 검증 |
| ④ | MEDIUM | **HSTS 가 앱·edge 어디서도 부여되지 않음.** 앱은 프록시 뒤 `isSecure()=false`, `nginx.conf.template:22`·`Caddyfile.template:29` 모두 주석 처리. **이번 변경이 만든 회귀 아님**(`git log -S` 로 원상복귀 확인). `A-ISSUE-06` 연관 후속 |
| ⑤ | MEDIUM | 전역 rate limit 고정 키를 채워 **role-claim 온보딩을 최대 60초 잠그는 DoS** 가능. 계정 축 5/분 상한 + 분 버킷 자동 해제로 저위험 |
| ⑥ | LOW | `ClientIpResolver` 의 `unknown` sentinel 은 **XFF 우회를 막지 못한다**(유효 IP 리터럴은 통과). 실익은 비-IP 오염·DNS 조회 유입 차단 + 이상 신호 경고뿐 — 주석·테스트명을 이 수준으로 정정 완료 |
| ⑦ | LOW | `ForwardedHeadersConfigGuard` 는 **프로퍼티 축만** 차단. Java 코드로 `FilterRegistrationBean<ForwardedHeaderFilter>`·`RemoteIpValve` 를 직접 등록하면 우회 가능 |
| ⑧ | 운영 | `SPRING_PROFILES_ACTIVE` 누락 시 `application.yml` 기본값 `local` 로 부팅 가능. 백스톱 `LocalProfileGuard` 는 `ENV` 환경변수 의존인데 `env.template`·`docker-compose.yml` 에 `ENV` 정의 0건 → **발화 불가**. pre-existing |
| ⑨ | 운영 | `TaskQueryController` 가드는 관제가 REVIEWER 토큰을 쓴다는 전제. `control-notify.enabled=false` 라 현재 영향 없으나 **연동 활성화 전 관제팀 확인 필요** |

## 6. 이 Phase 의 과정 (재발 방지용)

구현 1 + QA 6종 + DEV_FIX 2 + REDESIGN 1 + 적대검증 3라운드 + 실HTTP 프로브 4회차.

| 라운드 | 결함 |
|---|---|
| 구현 | 형제 엔드포인트 누락(`B-63` 우회 잔존) · 서명 경로 테스트가 전부 REVIEWER 라 인가 게이트 no-op · purge 스케줄러 부재(Phase 1 교훈 미적용) · stg env override · 죽은 분기 |
| DEV_FIX 1차 | **PM 설계 오류** — `forward-headers-strategy` 로 Phase 1 XFF 방어 전면 무력화 |
| REDESIGN | 신규 HIGH 0 (재도입 안전망이 3축 중 1축만·문서 과장 MEDIUM) |
| DEV_FIX 2차 | 기동 차단 가드로 전 축 봉쇄 — 실측 확인 |

### 교훈 (이후 Phase 위임 프롬프트에 상시 포함)

1. **공격자가 제어할 수 있는 입력을 신뢰 축·통제 해제 조건으로 쓰지 않는다.** Phase 1 에 이어 **PM 이 두 번째로 위반**했다. 가용성은 설정 fail-fast 로 푼다.
2. **뿌리를 고칠 때 그 뿌리가 닿는 지점을 전부 열거한다.** 교훈을 문장으로 전달했는데도 또 빠뜨렸다 → **인벤토리 표를 산출물로 요구**하는 방식이 유효했다(보안 담당도 놓친 `/v1/tasks/{rawSn}/*` 를 이 표가 찾아냈다).
3. **새 테스트마다 "되돌리면 실패하는가" 를 실측한다.**
4. **단위 테스트가 필터 체인을 우회하면 GREEN 이 거짓 신호다.** `HmacWebhookFilterTest` 는 `MockHttpServletRequest` 로 필터를 직접 호출해 상위 `ForwardedHeaderFilter` 를 지나치므로, **3130건 전부 GREEN 인 채로 실배포가 뚫렸다.** 필터 체인·서블릿 계층 변경은 통합 레벨 회귀가 필요하다.
5. **"고쳤다" 로 끝내지 말고 "다시 못 넣는다" 까지 간다.** 수정만 하면 실주입 경로(환경변수)로 조용히 되돌아온다.
