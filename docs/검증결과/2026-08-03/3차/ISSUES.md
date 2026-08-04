# ISSUES.md — 테스트케이스 전수 검증 3차 (2026-08-03) 이슈 대장

## A클러스터

> 심각도순(CRITICAL→HIGH→MEDIUM→LOW) 정렬. CRITICAL·HIGH 신규 결함 0건.

### MEDIUM

### [A-ISSUE-01] TC-AUTH-016 — JWT `sub` 정규화가 인증 필터와 인가 가드에서 서로 달라, 같은 토큰이 인증은 통과하고 인가에서 401 로 갈린다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `sub`(=userNo)는 **인가 판정의 주체 식별자이자 감사 컬럼의 값**이다(`LS_TASK_ASSIGNMENT` 배정 대조, `LS_AUTH_WORK_LOCK` 소유자, `REG_ID`/`MDFCN_ID` 등). 따라서 "이 토큰의 주체는 누구인가"에 대한 답은 **모든 소비 지점에서 하나여야** 한다. 파싱 규칙이 지점마다 다르면 ①한 사람이 두 신원으로 기록되어 감사 추적·작업락 소유권이 깨지고 ②역할은 받았는데 리소스 접근은 401 인 "반쯤 인증된" 상태가 만들어진다.
- **현재 동작(이슈 내용)**: 인증 필터는 **trim + 부호 허용**으로 관대하게 파싱하고, 하류 가드 2곳은 **원문 그대로** 엄격 파싱한다.
  ```java
  // common/security/JwtAuthenticationFilter.java:140-148 — 관대
  private static Long parseUserNo(String sub) {
      if (sub == null || sub.isBlank()) return null;
      try { return Long.parseLong(sub.trim()); }      // ← trim, "+1001" 도 허용
      catch (NumberFormatException e) { return null; }
  }

  // label/service/LabelAccessGuard.java:149-155 — 엄격
  public Long parseUserNo(String sub) {
      try { return Long.parseLong(sub); }             // ← trim 없음
      catch (NumberFormatException e) { throw new CustomException(ErrorCode.UNAUTHORIZED, ...); }
  }
  // user/service/UserService.java:159-165 — 동일하게 엄격
  ```
  또한 `TokenClaims.sub` 에는 **정규화되지 않은 원문**이 담겨 `actor.sub()` 를 문자열 그대로 쓰는 지점(작업락 소유자 `TrackEditService:97`·`TrackMergeService:95`, 감사 컬럼 `LabelMasterController:87,105,120`·`LabelAttrController:81,100,117`, `VideoController:247,372`, 포털 `LsPortalUserLabel.create` 등 20+곳)에 그대로 흘러든다.
  실동작 근거 (WORKER 2001, 배정 영상 srcSn=5):
  ```
  sub="2001"   → /v1/me role=WORKER   · GET /v1/frames/5/labels → 200
  sub=" 2001"  → /v1/me role=WORKER   · GET /v1/frames/5/labels → 401 {"message":"토큰 subject 형식이 올바르지 않습니다."}
  sub="2001 "  → /v1/me role=WORKER   · GET /v1/frames/5/labels → 401
  sub="+1001"  → /v1/me role=REVIEWER · userId 응답값은 "+1001"(원문 그대로)
  ```
  즉 **카탈로그 기대결과 "INTERNAL sub 비숫자면 무권한"이 부분적으로만 성립**한다 — `" 2001"`·`"+1001"` 은 엄밀히 비숫자인데 역할을 획득한다.
- **재현/확인 경로**:
  ```bash
  # SECRET = backend 컨테이너 JWT_SECRET
  P=$(printf '{"sub":" 2001","iss":"klid-auth","exp":9999999999}' | base64 | tr -d '=' | tr '/+' '_-')
  curl -s -H "Authorization: Bearer $H.$P.$S" http://localhost:18081/api/v1/me            # role=WORKER (인증 통과)
  curl -s -H "Authorization: Bearer $H.$P.$S" http://localhost:18081/api/v1/frames/5/labels # 401 (인가 거부)
  ```
  ```sql
  -- 두 신원으로 갈릴 수 있는 컬럼 확인
  select distinct lock_owner_id from ls_auth_work_lock;
  select distinct reg_id from ls_label;
  ```
- **영향**: 보안/데이터정합 — **CWE-1289(Improper Validation of Unsafe Equivalence in Input)** / CWE-178(Improper Handling of Case·Whitespace) 계열, 파급으로 CWE-863. 즉시 권한 상승은 아니다(불일치가 **fail-closed 방향인 401**로 나타난다). 실질 위험 2가지: ①발급 시스템(관제/포털)이 `sub` 에 공백·부호를 붙이는 순간 **해당 사용자의 모든 라벨링·검수 기능이 401 로 죽는다**(가용성, 원인 규명이 매우 어려움 — `/me` 는 정상이라 "로그인은 되는데 작업만 안 됨"으로 보인다) ②`actor.sub()` 원문을 그대로 쓰는 작업락 소유자·감사 컬럼에 **같은 사람의 두 표기가 공존**해 락 해제 실패·이력 추적 단절이 생긴다. `sub` 값은 저작도구가 통제하지 못하는 **외부 발급 계약**이라 "발급자가 항상 canonical 하게 넣는다"는 가정을 검증 없이 신뢰하는 구조다(A-ISSUE-01(1차) 의 `exp` 와 동일한 패턴).
- **수정 방향(제안)**: 정규화 지점을 **하나로** 모은다. `JwtAuthenticationFilter` 가 `sub` 를 파싱하는 그 자리에서 **canonical 문자열(`Long.toString(parsed)`)로 `TokenClaims.sub` 를 채워** 이후 모든 소비 지점이 같은 값을 보게 하거나, 반대로 filter 의 `trim()` 을 없애 **엄격 파싱으로 3곳을 통일**한다(전자를 권장 — 후자는 공백 포함 토큰을 발급하던 환경에서 로그인 자체가 막힌다). 어느 쪽이든 `LabelAccessGuard.parseUserNo`·`UserService.parseUserNo` 는 **filter 와 같은 헬퍼를 재사용**하도록 추출한다. 회귀 가드로 `JwtAuthenticationFilterTest` 에 "sub 공백/부호 변형이 filter·guard 에서 동일 판정" 케이스를 추가한다. ⚠ **어느 쪽을 정책으로 삼을지 사용자 확정이 선행돼야 하므로 카탈로그 `TC-AUTH-016` 은 이번 회차에 고치지 않았다.** (⚠ 구현은 하지 않음)


### [A-ISSUE-22] TC-CLAIM-016 — role-claim 전역 축(50회/분)을 단일 계정이 소진해 전 사용자 온보딩을 차단할 수 있다(미해소 이월)
- **심각도**: MEDIUM *(케이스 판정은 PASS — "교차계정 증폭 차단"이라는 기대결과 자체는 정확히 동작. 그 설계의 반대편 실패 모드)*
- **기대 동작(기대효과)**: 전역 축은 계정 A/B/C 를 번갈아 쓰는 무차별 대입 증폭 차단이 목적이며, 동시에 **정상 신규 사용자의 온보딩은 가용해야 한다** — role-claim 은 role=null 사용자가 시스템에 진입하는 유일한 경로다(TC-AUTHZ-024/025).
- **현재 동작(이슈 내용)**: 전역 축은 엔드포인트 단일 카운터(`GLOBAL_IDNTFR="GLOBAL"`)이고 **거부된 시도에도 계속 증가**한다(증가 후 판정).
  ```java
  // RoleClaimRateLimiter.java:112-127
  int local = localCounters.get(cacheKey, k -> new AtomicInteger()).incrementAndGet();
  int shared = sharedStore == null ? UNAVAILABLE : sharedStore.recordAttempt(...);
  int effective = Math.max(local, shared);
  if (effective > limit) { log.warn(...); throw new CustomException(ErrorCode.TOO_MANY_REQUESTS, ...); }
  ```
  실측(3차): 1분 내 60회 호출 후 **전혀 다른 신규 계정(sub=9999001)의 첫 시도가 429**. 로그는 `axis=GLOBAL attempts=51..61 limit=50` 으로 임계 초과 후에도 상승했고 DB `atmpt_nmtm=61` 로 확정됐다. 추가 관측 2건: ①**429 응답에 `Retry-After` 헤더가 없다**(정상 사용자가 재시도 시점을 알 수 없음) ②`rate-limited axis=GLOBAL` 로그에 **소진 주체 식별자가 없어** 운영자가 원인 계정을 특정할 수 없다.
- **재현/확인 경로**:
  ```bash
  # 서로 다른 sub 60개(또는 동일 sub)로 1분 내 호출 — pw 는 틀려도 무방(rate limit 이 pw 앞)
  for t in $(cat toks.txt | head -60); do curl -s -o /dev/null -w "%{http_code} " -X POST \
    -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
    -d '{"role":"WORKER","adminPassword":"xxxx"}' http://localhost:18081/api/v1/auth/role-claim; done
  # → 50회 401, 51회째부터 429. 이후 신규 계정 첫 시도도 429
  ```
  ```sql
  select atmpt_se_cd, atmpt_idntfr, bgng_dt, atmpt_nmtm from ls_authrt_grant_atmpt
   where atmpt_se_cd='GLOBAL' order by bgng_dt desc limit 3;   -- 51 초과 후에도 61까지 증가
  ```
- **영향**: 가용성 — 신규/역할미배정 사용자 온보딩 전면 차단(CWE-770, OWASP API4:2023). 인증된 저권한 계정 1개면 성립. 보안 강도(무차별 대입 억제)는 손상되지 않는다.
- **수정 방향(제안)**: (구현하지 않음) ①임계 초과가 확정된 축은 카운터를 더 올리지 않고 즉시 거절(고착·DB 쓰기 증폭 동시 완화) ②전역 축을 차단이 아니라 경보+지수 백오프로 낮추고 실제 차단은 계정 축이 담당 ③`ATMPT_IDNTFR` 별 상위 소비자를 집계해 소진 계정만 장기 차단 + 로그에 축별 상위 소비자 노출 ④최소한 429 에 `Retry-After: 60` 부착(`HmacWebhookFilter` 는 이미 그렇게 한다 — 동일 프로젝트 내 비대칭).


### [A-ISSUE-24] TC-AUTHZ-009 / TC-AUTHZ-010 — `channel` 클레임을 생략한 포털 발급 토큰이 INTERNAL 로 승격되어 채널 격리를 우회(미해소 이월, 1차 A-ISSUE-02)
- **심각도**: MEDIUM *(케이스 판정은 PASS — 카탈로그가 기술한 `CHANNEL_PORTAL` 토큰 시나리오는 정확히 403. 그 전제를 무너뜨리는 인접 경로를 기록)*
- **기대 동작(기대효과)**: R5-1 채널 격리는 "외부(포털) 채널 토큰이 내부 업무 API 에 도달하지 못한다"를 보장해야 한다. 두 채널이 **동일 시크릿·동일 issuer allowlist** 를 공유하므로(CLAUDE.md "인증·진입"), 채널 판정은 클레임 유무가 아니라 **발급자와 결합**돼야 한다.
- **현재 동작(이슈 내용)**: `channel` 클레임이 없으면 발급 issuer 와 무관하게 INTERNAL 로 기본값 처리된다(`JwtAuthenticationFilter.java:86-87`, `SecurityConfig.java:141-146` 주석이 "기존 내부 사용자 토큰 호환"으로 의도 명시). 실측:
  ```
  iss=klid-portal, channel 클레임 없음, sub=2001(LS_USER_ROLE=WORKER)
   → GET /v1/me  : {"role":"WORKER","channel":"INTERNAL"}
   → GET /v1/videos : 200   (내부 업무 API 통과)
  iss=klid-portal, channel 없음, sub=1001(REVIEWER)
   → GET /actuator/metrics : 200
  ```
  **완화 요인(실측)**: 역할은 여전히 `LS_USER_ROLE` 에서 오므로, 실제 포털 사용자(sub=3001, LS=PORTAL_USER)가 같은 짓을 하면 `CHANNEL_INTERNAL + ROLE_PORTAL_USER` 가 되어 `/v1/**` 403 이다(자기 포털 API 도 403 이 되어 오히려 기능만 깨진다). 따라서 성립 조건은 **"sub 를 내부 userNo 로 지정한 토큰을 얻을 수 있는가"** = 포털 발급 서버의 오작동·침해 또는 공유 시크릿 유출이다.
- **재현/확인 경로**:
  ```bash
  # JWT_SECRET 으로 {"iss":"klid-portal","sub":"2001"} (channel 클레임 없음) 서명
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $T" http://localhost:18081/api/v1/videos   # → 200
  ```
- **영향**: 보안 — 불완전한 채널 격리(CWE-863) · 신뢰 경계 혼동(CWE-501). 단독 취약점이 아니라 "포털 발급 서버 침해 시 저작도구 내부 API 까지 즉시 확산"이라는 **폭발 반경 확대** 성격이다.
- **수정 방향(제안)**: (구현하지 않음) ①`channel` 을 **필수 클레임으로 승격**하고 미보유 토큰을 거부(하위호환이 필요하면 이행 기간 동안 WARN 후 차단) ②또는 issuer→channel 매핑표(`klid-portal → PORTAL`)를 두고 클레임과 불일치 시 거부 ③장기적으로 채널별 서명 키 분리. ⚠ ①은 기존 관제 토큰 호환을 깨뜨릴 수 있어 관제팀 협의 대상.


### [A-ISSUE-25] TC-AUTHZ-021 — HSTS 가 앱에서도 edge 에서도 실제로 부여되지 않는다(미해소 이월, 1차 A-ISSUE-06 / UNCERTAINTIES #28)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 운영 도메인 응답에 `Strict-Transport-Security` 가 부착돼 SSL stripping·평문 다운그레이드를 차단해야 한다(security.md "Security 응답 헤더 필수").
- **현재 동작(이슈 내용)**: `SecurityConfig.java:64-67` 에 `httpStrictTransportSecurity(includeSubDomains, maxAge=31536000)` 가 설정돼 있으나 Spring Security 의 `SecureRequestMatcher` 때문에 **HTTPS 요청에만** 부착된다. 3차 실측에서 HTTP 응답(200·401·403 전부)에 헤더가 없음을 재확인했고, `nosniff`·`X-Frame-Options: DENY` 는 정상 부착됐다. 로컬 스택에 TLS 종단이 없어 **운영 edge(nginx/Caddy) 부여 여부는 이 회차에서도 확인 불가**.
- **재현/확인 경로**:
  ```bash
  curl -s -D - -o /dev/null -H "Authorization: Bearer $T_REV" http://localhost:18081/api/v1/videos | grep -i strict
  # → 출력 없음 (nosniff / X-Frame-Options 는 출력됨)
  ```
- **영향**: 보안 — 전송 계층 다운그레이드 방어 부재(OWASP A02:2025 Security Misconfiguration).
- **수정 방향(제안)**: (구현하지 않음) ①운영 edge(nginx/Caddy) 설정에 HSTS 를 명시하고 그 설정을 `deploy/onprem/` 문서에 고정 ②또는 앱이 `X-Forwarded-Proto` 를 신뢰하도록 `server.forward-headers-strategy` 를 켜서 프록시 뒤 HTTPS 요청을 인식하게 함(신뢰 프록시 CIDR 전제 필요) ③어느 쪽이든 **배포 체크리스트에 실응답 헤더 확인 항목**을 넣어 "설정은 있는데 안 나감"이 재발하지 않게 함.


### [A-ISSUE-81] TC-ACT-002 — `/actuator/health` 상세 노출이 REVIEWER 전용 의도를 벗어나 인증된 모든 역할(WORKER 포함)에 노출됨
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `SecurityConfig.java`(85-86행 permitAll `/actuator/health`·`/actuator/info`, 118행 `.requestMatchers("/actuator/**").hasRole(Role.REVIEWER.name())`)의 주석("actuator metrics/prometheus 는 REVIEWER 만")과 `.claude/rules/observability.md`("운영 환경에서 민감 엔드포인트 노출 금지")의 취지는 **actuator 내부 상세 정보는 REVIEWER(관리자 동급)만 봐야 한다**는 것. `application.yml:140` 의 `show-details: when-authorized` 도 이 의도를 뒷받침하는 설정으로 보인다.
- **현재 동작(이슈 내용)**: Spring Boot 의 `when-authorized` 는 기본적으로 `management.endpoint.health.roles` 가 **비어 있으면 "인증 여부"만 확인**하고 **역할(role)은 보지 않는다**. 이 프로젝트는 `management.endpoint.health.roles` 를 설정하지 않았다(`grep -n "roles" application*.yml` → 0건). 그 결과 `/actuator/health` 는 SecurityConfig 에서 permitAll 이라 REVIEWER 게이트(`hasRole(REVIEWER)`)를 아예 거치지 않고, Boot 자체 판정도 "인증되면 상세 노출"이라 **WORKER 역할 토큰으로도 REVIEWER 전용으로 의도된 내부 상세가 전부 노출**된다.
  실동작 근거(WORKER, userNo=2001, role=WORKER 토큰):
  ```
  GET /api/actuator/health (WORKER 토큰)
  → 200 {"status":"UP","components":{
      "aiServerHealth":{...,"details":{"service":"ai-server"}},
      "controlNotifyHealth":{...,"details":{"service":"control-notify"}},
      "db":{...,"components":{"controlDataSource":{...,"details":{"database":"PostgreSQL","validationQuery":"isValid()"}},"portalDataSource":{...}}},
      "deidentifyHealth":{...,"details":{"service":"deidentify","mode":"kpst"}},
      "diskSpace":{...,"details":{"total":485473984512,"free":...,"path":"/app/.","exists":true}},
      ...}}
  ```
  같은 WORKER 토큰으로 `GET /api/actuator/metrics` 는 정상적으로 `403 FORBIDDEN`(hasRole(REVIEWER) 게이트가 실제로 동작) — 즉 **actuator 내에서 `/health` 만 역할 게이트를 우회**하는 비대칭이 실측됐다.
- **재현/확인 경로**:
  ```bash
  # WORKER(userNo=2001) HS256 토큰으로
  curl -H "Authorization: Bearer $WORKER_TOKEN" http://localhost:18081/api/actuator/health   # → 200 + 전체 컴포넌트 상세
  curl -H "Authorization: Bearer $WORKER_TOKEN" http://localhost:18081/api/actuator/metrics   # → 403 (대조군, 정상)
  ```
- **영향**: CWE-200(민감하지 않은 수준이지만 내부 아키텍처 정보 노출) / CWE-862(의도된 인가 경계 미스매치). 노출 정보 자체(컨테이너 내부 경로 `/app/.`, DB 벤더/검증쿼리, 외부연동 모드 `kpst`/`mock` 등)는 비밀·PII 는 아니라 즉각적 악용 난이도는 낮으나, "actuator 상세=REVIEWER 전용" 이라는 코드 스스로의 의도(SecurityConfig 주석)를 무력화하고, WORKER 도 이를 통해 내부 배치/외부연동 형상(비식별이 mock 인지 kpst 인지 등)을 정찰할 수 있다. **local/dev/stg 에서만 유효** — prd 는 `show-details: never` 로 이미 차단(application-prd.yml:100).
- **수정 방향(제안)**: `application.yml` 의 `management.endpoint.health.roles: REVIEWER` 를 명시해 Boot 의 `when-authorized` 판정축을 REVIEWER 역할로 좁히거나(Spring Security `SecurityContext.isUserInRole` 기반), 그게 어렵다면 `SecurityConfig` 에서 `/actuator/health` 도 REVIEWER 게이트 뒤로 옮기고 헬스체크(k8s liveness/readiness) 용은 별도 경량 엔드포인트로 분리. prd 는 이미 `never` 라 영향 없음.


---

### LOW

### [A-ISSUE-02] TC-AUTH-005 — `exp` 상한이 없어 "수명 상한은 exp 로만 강제한다"는 A-ISSUE-01(1차) 수정의 전제가 절반만 성립

- **심각도**: LOW
- **기대 동작(기대효과)**: A-ISSUE-01(1차) 수정의 근거는 *"저작도구는 토큰을 발급·폐기하지 않으므로 베어러 토큰의 수명 상한은 오직 `exp` 로만 강제된다"* 였다(`JwtAuthenticationFilter.java:72-78` 주석). 이 근거가 실효를 가지려면 `exp` **존재**뿐 아니라 **합리적 최대 수명**(예: 발급 후 24h 이내)까지 강제돼야 한다. 그래야 유출 시 노출 창이 실제로 유한해진다.
- **현재 동작(이슈 내용)**: 게이트는 `exp` 의 **존재 여부만** 본다. 값의 상한 검사가 없어 임의로 먼 미래를 넣으면 사실상 영구 토큰이 된다.
  ```java
  // JwtAuthenticationFilter.java:79-84
  if (body.getExpiration() == null) { ... return; }   // ← 존재만 검사, 상한 없음
  ```
  실동작 근거:
  ```
  exp=9999999999999 (서기 318857년)  → GET /api/v1/me       200  {"role":"REVIEWER"}
                                     → GET /api/v1/videos   200
  exp=9223372036854775807 (Long.MAX) → 401 (Date 변환 오버플로로 우연히 fail-closed)
  ```
  즉 `Long.MAX` 만 우연히 막히고, 그보다 작은 임의 대형 값은 전부 통과한다.
- **재현/확인 경로**:
  ```bash
  P=$(printf '{"sub":"1001","iss":"klid-auth","exp":9999999999999}' | base64 | tr -d '=' | tr '/+' '_-')
  curl -s -H "Authorization: Bearer $H.$P.$S" http://localhost:18081/api/v1/videos?page=0&size=1   # 200
  ```
- **영향**: 보안 — **CWE-613(Insufficient Session Expiration)** 잔여분. 성립 조건이 "발급 시스템이 과대한 `exp` 를 넣는다"이고 발급자는 서명 키를 가진 신뢰 주체이므로 **즉시 악용 가능한 결함은 아니다**(그래서 LOW). 다만 A-ISSUE-01(1차)이 지적한 "외부 발급 계약을 검증 없이 신뢰"라는 구조는 **그대로 남아 있다** — 발급자 측 버그(예: 초 단위 자리에 밀리초를 넣는 흔한 실수 → 1000배 수명)가 조용히 통과한다.
- **수정 방향(제안)**: `:79-84` 게이트에 상한 검사를 덧붙인다 — `exp` 가 `now + authoring.jwt.max-lifetime`(기본 24h 권장) 을 초과하면 issuer 게이트와 동일하게 `clearContext()` + early return. 설정값으로 두어 관제/포털 계약에 맞게 조정 가능하게 하고, 기동 시 값 로깅. 회귀 가드로 "과대 exp 401" 케이스 추가. (⚠ 구현은 하지 않음)


### [A-ISSUE-03] TC-AUTH-003/006/007/011/022 · TC-STREAM-006/007/011 — 인증·서명 필터의 fail-closed 분기 8건에 회귀 가드가 없다

- **심각도**: LOW
- **기대 동작(기대효과)**: 이 8개 분기는 전부 **"조건이 어긋나면 거부"** 하는 fail-closed 게이트다. 이런 분기는 리팩토링 중 조용히 뒤집혀도 정상 경로 테스트가 전부 통과하므로(거부 경로를 아무도 안 밟는다) **전용 회귀 가드 없이는 결함이 배포까지 간다.** 실제로 이 저장소는 같은 패턴의 사고 이력이 있다(로그 마스킹은 테스트는 통과하는데 파이프라인엔 미배선, 웹훅 필터 URL 인코딩 우회).
- **현재 동작(이슈 내용)**: `backend/src/test/java` 전수 grep 결과 아래 분기에 대응하는 테스트 메서드가 0건이다.
  | 케이스 | 미커버 분기 | 코드 위치 |
  |---|---|---|
  | TC-AUTH-003 | `Bearer ` 대소문자 정확 매칭 | `JwtAuthenticationFilter.java:28,56` |
  | TC-AUTH-006 | `alg=none` 거부 | `:59-62` |
  | TC-AUTH-007 | malformed 토큰 거부 | `:127-130` |
  | TC-AUTH-011 | issuer 목록 공백 제거·전건 거부(fail-closed) | `JwtIssuerValidator.java:16-19` |
  | TC-AUTH-022 | 생성자 인자 null → 빈 생성 거부 | `JwtAuthenticationFilter.java:37-48` |
  | TC-STREAM-006 | GET/경로 아니면 미동작 | `StreamSignatureFilter.java:84-89` |
  | TC-STREAM-007 | rawSn `\d{1,18}` 정규식 | `:50,116-122` |
  | TC-STREAM-011 | sign-secret 미설정 시 봉인키 랜덤(전건 불통과) | `StreamNonceCookie.java:183-190` |
  (`JwtAuthenticationFilterTest` 9건 · `JwtIssuerValidatorTest` 2건 · `StreamSignedUrlControllerTest` 19건 · `StreamNonceCookieSecureFlagTest` 3건 전수 확인. TC-STREAM-011 공백은 1차에서도 지적됐고 그대로 남아 있다.)
- **재현/확인 경로**:
  ```bash
  cd backend/src/test/java
  grep -rn "alg\":\"none\|BEARER \|new JwtAuthenticationFilter(null\|d{1,18}\|sign-secret" . | wc -l   # → 0
  ```
- **영향**: 기능/보안 회귀 감지 실패. 8건 모두 **현재는 실동작으로 정상 확인**됐으므로 지금 뚫린 구멍은 아니다. 위험은 미래 시점 — 예컨대 `BEARER_PREFIX` 를 `equalsIgnoreCase` 로 "관대하게" 바꾸거나 `shouldNotFilter` 의 GET 조건을 넓히는 변경이 **어떤 테스트도 깨뜨리지 않고** 머지된다.
- **수정 방향(제안)**: 신규 테스트 파일을 만들지 말고 기존 3개 파일에 케이스를 덧붙인다 — `JwtAuthenticationFilterTest` 에 4건(대소문자 접두사 / alg=none / garbage / 생성자 null), `JwtIssuerValidatorTest` 에 1건(`" klid , , x "` → `{klid,x}`, 전부 공백 → 전건 false), `StreamSignedUrlControllerTest` 에 2건(POST·19자리 rawSn), `StreamNonceCookieSecureFlagTest` 또는 신규 `StreamNonceCookieSealTest` 에 1건(빈 시크릿 생성자 → 임의 봉인값 `unseal` = null). 전부 단위/슬라이스라 회귀 시간 증가는 미미하다. (⚠ 구현은 하지 않음)

---


### [A-ISSUE-02(1차)] TC-AUTH-012 — `channel` 클레임 부재 시 무조건 INTERNAL 승격 (issuer 와 채널이 무결합)

- **심각도**: MEDIUM (1차 판정 유지)
- **3차 재확인**: 코드 무변경(`JwtAuthenticationFilter.java:86-87`), 실동작도 1차와 동일.
  ```
  payload = {"sub":"1001","iss":"klid-portal","exp":<미래>}     ← channel 클레임 없음
  GET /api/v1/me → 200 {"userId":"1001","role":"REVIEWER","channel":"INTERNAL"}
  (대조군) 같은 토큰에 "channel":"PORTAL" 추가 → role=PORTAL_USER, /v1/videos 403 으로 정상 격리
  ```
  런타임 실효 `JWT_ALLOWED_ISSUERS=klid-auth,klid,klid-portal` 로 **포털 발급자가 여전히 allowlist 에 포함**되어 있고 서명 키도 단일(`JWT_SECRET`)이라 성립 조건이 그대로다.
- **기대 동작·영향·수정 방향**: 1차 `ISSUES.md` `[A-ISSUE-02]` 블록과 동일(issuer↔channel 바인딩 또는 `channel` 필수화 2안). 재기술 생략 — **대장에서 해당 블록을 그대로 이월**할 것.


### [A-ISSUE-41(1차)] TC-STREAM-009 — 동명 nonce 쿠키 shadowing 시 스트림이 영구 401 (가용성)

- **심각도**: LOW (1차 판정 유지)
- **3차 재확인**: 코드 무변경(`StreamNonceCookie.java:120-124` 첫 매칭 즉시 `return unseal(...)`), 실동작 재현됨.
  ```
  Cookie: klid_stream_nonce=<유효>                          → 200
  Cookie: klid_stream_nonce=<위조>; klid_stream_nonce=<유효> → 401   ← 정상 값이 가려짐
  Cookie: klid_stream_nonce=<유효>; klid_stream_nonce=<위조> → 200
  ```
  쿠키 `Path=/api/v1/videos` 이므로 더 긴 path(예: `/api/v1/videos/4`)로 동명 쿠키를 심으면 RFC 6265 §5.4 상 **위조 쿠키가 항상 앞에 온다**. 본 프로젝트는 관제서버와 동일 도메인 운영이라 인접 앱 경유 주입 표면이 존재한다.
- **기대 동작·영향·수정 방향**: 1차 `ISSUES.md` `[A-ISSUE-41]` 블록과 동일(루프를 "매칭 전부 순회, unseal 성공값 채택, 개수 상한"으로 변경). 재기술 생략 — **대장에서 그대로 이월**할 것.

---


### [A-ISSUE-21] TC-CLAIM-001/005/009/010/011/012/014 — 검증 스택 `ADMIN_CLAIM_PASSWORD_HASH` 공란이 3회차째 유지되어 role-claim 성공 경로가 여전히 미검증
- **심각도**: LOW *(코드 결함 아님 — 검증 환경 구성 이슈. 단 1차 권고 후 2회차가 지나도록 반영되지 않아 승격 없이 재기록)*
- **기대 동작(기대효과)**: 권한 자가부여의 성공 경로(WORKER 부여 → `LS_USER_ROLE` upsert → AFTER_COMMIT 캐시 evict → 새 INTERNAL 토큰 발급)는 **수직 권한 상승과 직결된 유일한 온보딩 경로**이므로 VERIFY-PROMPT §1(실동작 기준)에 따라 실제 요청으로 확인돼야 한다.
- **현재 동작(이슈 내용)**: 실행 중 backend 의 실효 환경변수가 빈 문자열이라 `RoleClaimService` 가 해시를 `""` 로 유지하고 모든 요청을 pw 게이트에서 종결한다.
  ```
  $ docker exec klid-backend printenv ADMIN_CLAIM_PASSWORD_HASH
  (빈 줄)
  ```
  ```java
  // RoleClaimService.java:137-143
  if (adminPasswordHash.isEmpty() ||
          !passwordEncoder.matches(req.adminPassword(), adminPasswordHash)) {
      log.warn("[RoleClaim] denied userNo={} role={} reason=invalid_password", ...);
      throw new CustomException(ErrorCode.UNAUTHORIZED, "관리자 패스워드가 일치하지 않습니다.");
  ```
  `application-local.yml:35` 의 기본 해시는 **환경변수가 "존재하되 빈 값"이라 적용되지 않는다**(Spring 은 이를 `""` 로 해석). 결과적으로 pw 이후 5개 분기(사용자 404 · LS 이중게이트 409 · sub 파싱 400 · upsert · evict · 토큰발급)가 전부 도달 불가다. 부수적으로 **`BCryptPasswordEncoder.matches` 상수시간 비교 경로(TC-CLAIM-005)도 단락 평가로 건너뛴다**.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend printenv ADMIN_CLAIM_PASSWORD_HASH   # → 빈 값
  T=<role=null INTERNAL JWT>
  curl -s -X POST -H "Content-Type: application/json" -H "Authorization: Bearer $T" \
    -d '{"role":"WORKER","adminPassword":"admin1234"}' http://localhost:18081/api/v1/auth/role-claim
  # → 401 (어떤 평문으로도 동일)
  ```
- **영향**: 검증 커버리지 — A-5 21건 중 **7건이 정적+단위테스트 근거에 머문다**. 운영 관점으로는 이 설정이 그대로 배포되면 role-claim 온보딩이 **조용히 항상 실패**한다(기동 경고 없음).
- **수정 방향(제안)**: (구현하지 않음) ①다음 회차 스택 기동 시 `docker-compose.local.yml` 에 로컬 전용 BCrypt(cost 12) 해시를 주입해 성공 경로를 live 대상에 포함 ②코드 측으로는 `RoleClaimService` 생성자에서 공란 감지 시 기동 WARN(`DevToggleStartupWarner` 동형)을 남겨 fail-closed 는 유지하되 **무음 상태를 제거**.


### [A-ISSUE-23] TC-AUTHZ-002 / TC-AUTHZ-010 / TC-AUTHZ-024 — `/v1/manage/labels/**` GET 예외가 PORTAL 채널·역할미배정에게 라벨 마스터·COCO 검출 매핑·속성 정의를 노출(미해소 이월)
- **심각도**: LOW *(케이스 판정은 PASS — 카탈로그가 "의도된 예외"로 명시하고 실동작이 일치. 예외의 정당화 근거가 `/v1/me` 대비 약하다는 점을 재기록)*
- **기대 동작(기대효과)**: 채널 격리(R5-1)와 role=null fail-open 차단(A-ISSUE-02 수정)의 예외는 **업무 데이터가 없는 경로로 한정**되어야 한다. `SecurityConfig.java:72-83` 이 `/v1/me` 예외를 정당화한 근거가 정확히 그것이다.
- **현재 동작(이슈 내용)**: `/v1/manage/labels` 와 하위 전체가 GET 에 한해 `authenticated()` 다.
  ```java
  // SecurityConfig.java:126
  .requestMatchers(HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
  ```
  3차 실측 — PORTAL 채널 토큰·role=null INTERNAL 토큰·WORKER 모두 아래 3경로가 **200 + 실데이터**:
  `GET /v1/manage/labels`(라벨 마스터 전량 `labelId/name/color/type/dtctTypeCd/useYn`) · `GET /v1/manage/labels/detect-candidates`(**COCO 검출 클래스 매핑** — 포털은 ADR-013 상 오토라벨링 미제공이라 불필요) · `GET /v1/manage/labels/{labelId}/attrs`(속성 정의). 같은 `/v1/manage` 하위의 `configs`·`presets`·`health` 는 정상적으로 403 이라 **노출 범위는 labels 3경로로 한정**됨을 재확인했다.
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $T_PORTAL" http://localhost:18081/api/v1/manage/labels/detect-candidates
  curl -s -H "Authorization: Bearer $T_NOROLE" http://localhost:18081/api/v1/manage/labels/1/attrs
  # → 둘 다 200 + 실데이터
  ```
- **영향**: 정보 노출(CWE-200) · 불완전한 채널 격리(CWE-863 부분). 유출물은 PII 가 아닌 라벨 taxonomy·AI 검출 클래스 매핑·속성 스키마.
- **수정 방향(제안)**: (구현하지 않음) ①`detect-candidates` 를 `hasAnyRole(REVIEWER, WORKER)` 로 분리(포털에 불필요) ②와일드카드 대신 **필요한 GET 서브리소스만 명시 매처로 나열**(신규 GET 이 자동으로 예외에 편입되는 구조 제거) ③role=null 은 예외에서 제외(채널 조건만 완화) ④`/v1/me` 예외 주석과 같은 불변식 주석을 이 매처에도 명시.


### [A-ISSUE-26] TC-ROLE-001 — `RoleHierarchy` 빈이 빈 계층 + 참조 0건으로 사실상 no-op(UNCERTAINTIES #6 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 등록된 스프링 빈은 실제 인가 판정에 관여하거나, 관여하지 않는다면 제거돼 **"역할 상속이 설정돼 있다"는 오독**을 만들지 않아야 한다.
- **현재 동작(이슈 내용)**:
  ```java
  // RoleHierarchy.java:10-13
  @Bean
  public org.springframework.security.access.hierarchicalroles.RoleHierarchy authoringRoleHierarchy() {
      return RoleHierarchyImpl.fromHierarchy("");     // 상속 규칙 0건
  }
  ```
  `grep -rn RoleHierarchy backend/src` 결과 **정의 파일 외 참조 0건**(3차 재확인). 실동작으로도 상속 부재가 확인된다 — WORKER 는 `/v1/system/**`·`/v1/manage/**`(쓰기)·`/actuator/**`·`/v1/users/**`·`/v1/reviews/*/approve` 전건 403.
- **재현/확인 경로**: `grep -rn 'RoleHierarchy' backend/src` → 1개 파일만 매칭.
- **영향**: 유지보수 — 후속 개발자가 "역할 계층이 이미 배선돼 있다"고 오해해 상속 문자열만 채우면 **아무 효과 없이 통과**한다(무음 실패).
- **수정 방향(제안)**: (구현하지 않음) 정책 결정 필요 — ①빈을 제거하고 "명시 역할 나열" 정책을 주석으로 고정, 또는 ②실제로 계층을 쓸 계획이면 `@EnableMethodSecurity` 표현식 핸들러·`authorizeHttpRequests` 에 배선하고 회귀 테스트를 추가. 현 상태(정의만 존재)는 둘 다 아니다.


### [A-ISSUE-27] TC-CLAIM-015 — REVIEWER 상승은 막혔으나 공유 정적 패스워드 자체의 잔여 통제(감사 원장·회전·만료)가 여전히 없다
- **심각도**: LOW *(케이스 판정은 PASS — 1차 A-ISSUE-17 의 핵심 요구 ①②는 해소됨. 미이행 항목 ③만 잔존)*
- **기대 동작(기대효과)**: 1차 A-ISSUE-17 수정 제안 ③ — 공유 패스워드 기반 자가부여를 유지한다면 **부여 이력 전용 원장**(요청자·역할·시각·IP·요청ID)과 **패스워드 회전·만료 정책**이 있어야 한다(CWE-1392 Use of Shared Credentials, CWE-778 Insufficient Logging).
- **현재 동작(이슈 내용)**: 부여 성공의 유일한 흔적은 애플리케이션 로그 1줄이다.
  ```java
  // RoleClaimService.java:185
  log.info("[RoleClaim] granted userNo={} role={}", userNo, req.role().name());
  ```
  `ls_authrt_grant_atmpt` 는 **rate limit 카운터 전용**(축/식별자/버킷/횟수)이라 감사 원장이 아니고 IP·요청ID·성공 여부를 담지 않는다. 패스워드는 `authoring.auth.admin-claim-password-hash` 단일 값으로 회전 주기·만료·직전 값 무효화 개념이 없다.
- **재현/확인 경로**: `\d ls_authrt_grant_atmpt` → 컬럼 `atmpt_se_cd/atmpt_idntfr/bgng_dt/atmpt_nmtm/expd_dt` 뿐. `grep -rn "granted userNo" backend/src/main` → 로그 1곳.
- **영향**: 감사·사후추적 — 공유 패스워드가 유출돼 다수 계정이 WORKER 를 자가부여해도 **누가·언제·어디서 받았는지 DB 로 조회할 수 없다**(로그 보존기간에 의존). WORKER 는 라벨 수정·검수 제출 권한이라 데이터 무결성 영향이 있다.
- **수정 방향(제안)**: (구현하지 않음) ①부여 성공 시 전용 이력 테이블에 (userNo, role, 시각, clientIp, traceId, 결과) 적재 — 표준용어·표준도메인 준수 필요 ②패스워드 회전 절차와 만료 주기를 운영 문서(`deploy/onprem/docs/`)에 고정 ③선택적으로 부여 발생 시 기존 REVIEWER 통지.

---


### [A-ISSUE-41] TC-HMAC-017 / TC-HMAC-018 / TC-HMAC-043 — 무서명 경로의 411/413 이 rate limit 은 올리면서 `webhook.auth.failed` 메트릭은 올리지 않음 (관측 사각) 〔1차 A-ISSUE-43 **이월 · 미해소**〕

- **심각도**: LOW
- **기대 동작(기대효과)**: `webhook.auth.failed` 는 웹훅 가드가 요청을 거절한 사유를 운영자가 관측·알람하는 단일 지표다. **rate limit 카운터를 올리는(=429 backoff 를 유발할 수 있는) 모든 거절 사유**는 이 지표에도 남아야, "왜 정상 벤더 콜백이 429 를 맞고 있는가"를 메트릭만으로 추적할 수 있다.
- **현재 동작(이슈 내용)**: 무서명 경로의 `411`(Content-Length 누락/chunked)·`413`(본문 상한 초과)은 `rateLimiter.recordFailure(clientIp)` 는 호출하지만 `countAuthFailure(...)` 를 호출하지 않는다. `writeLengthRequired`/`writePayloadTooLarge` 가 `fail()`(:604-608, 메트릭 증가 지점)을 경유하지 않기 때문이다. 서명 경로의 413(:327-331)도 동일.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/HmacWebhookFilter.java:506-518`
  ```java
  if (contentLength < 0) {
      rateLimiter.recordFailure(clientIp);
      writeLengthRequired(response);            // ← countAuthFailure 없음
      return;
  }
  if (contentLength > maxBytes) {
      rateLimiter.recordFailure(clientIp);
      writePayloadTooLarge(response, maxBytes); // ← countAuthFailure 없음
      return;
  }
  ```
  **3차 실측 재확인** — 이번 회차에 411 을 14회, 413 을 5회 실제로 발생시킨 뒤 `/actuator/prometheus`:
  ```
  webhook_auth_failed_total{path="genai",reason="downstream_unauthorized"} 1.0
  webhook_auth_failed_total{path="vlm",reason="downstream_unauthorized"} 16.0
  ```
  → `reason="length_required"` / `"payload_too_large"` 시리즈가 **여전히 존재하지 않는다**. 같은 요청들이 `ls_whk_fail_nmtm` 은 정상적으로 올렸다(172.20.0.2 버킷 5 도달 → 6회째 429 관측).
- **재현/확인 경로**:
  ```bash
  # 411 유발 (5회 미만으로 유지)
  printf '{"a":1}' | curl -s -o /dev/null -w "%{http_code}\n" -X POST \
    http://localhost:18081/api/v1/vlm/callback -H 'Content-Type: application/json' \
    -H 'Transfer-Encoding: chunked' --data-binary @-        # → 411
  # 메트릭 확인 (REVIEWER 토큰 필요 — userNo 는 LS_USER_ROLE 에 REVIEWER 로 있는 값, 예: 1001)
  curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"userNo":1001,"role":"REVIEWER","channel":"INTERNAL"}' -o /tmp/t.json
  curl -s -H "Authorization: Bearer $(python3 -c "import json;print(json.load(open('/tmp/t.json'))['data']['token'])")" \
    http://localhost:18081/api/actuator/prometheus | grep webhook_auth_failed
  # → length_required / payload_too_large 계열 시리즈 없음
  ```
- **영향**: 보안 통제 자체는 정상 동작(차단·집계 모두 유효). 영향은 **관측/알람** — chunked·대용량 본문 플러딩으로 벤더 IP 가 429 backoff 에 빠져 **정상 콜백이 유실되는 상황**이 발생해도 `webhook.auth.failed` 알람이 전혀 울리지 않아 원인 추적이 로그 grep 에 의존한다. CWE-778(Insufficient Logging) 계열.
- **수정 방향(제안)**: `handleGuardOnly` 의 411/413 분기(및 서명 경로 413 분기 `:327-331`)에서 `countAuthFailure(path.tag(), "length_required")` / `"payload_too_large"` 를 호출한다 — reason 은 **상수 문자열**이라 태그 카디널리티(TC-HMAC-043)를 해치지 않는다. 더 견고하게는 `recordFailure` + 메트릭 증가를 한 헬퍼(`countedFailure(path, reason)`)로 묶어 두 축이 구조적으로 어긋나지 못하게 한다. ⚠ 구현은 하지 않음.


### [A-ISSUE-42] TC-HMAC-036 — `trusted-proxy-cidrs=none` 을 명시해도 "설정이 비어 있다"고 경고 (오해 유발 운영 신호) 〔1차 A-ISSUE-42 **이월 · 미해소**〕

- **심각도**: LOW
- **기대 동작(기대효과)**: `webhook.trusted-proxy-cidrs` 는 "빈 값(미설정)"과 "`none`(프록시 없음을 **의식적으로** 선택)"을 구분하는 것이 설계 규약이다(`ClientIpResolver.NO_PROXY` 주석 `:67-68`, `WebhookIpAllowlist:62-67` 은 실제로 두 경우 로그를 구분한다). `none` 을 명시한 운영자에게는 "설정 누락" 경고가 나가면 안 된다 — 이미 규약대로 설정한 사람이 조치를 찾게 되고, 반대로 **진짜 누락 환경의 경고가 노이즈에 묻힌다**.
- **현재 동작(이슈 내용)**: 판정이 `trustedProxies.isEmpty()` 하나뿐이라 `none` 명시와 미설정이 **같은 상태로 접힌다**. `isNoProxy` 결과가 필드로 보존되지 않는다.

  `backend/src/main/java/kr/co/cudo/authoring/common/security/webhook/ClientIpResolver.java:105-107, 207-216`
  ```java
  this.trustedProxies = isNoProxy(trustedProxyCidrs)
          ? List.of()                                  // ← "none 이었다" 는 사실이 소실
          : WebhookCidrParser.parseStrict(...);
  ...
  private void warnIfUnconfiguredProxy(HttpServletRequest request) {
      if (!trustedProxies.isEmpty() || unconfiguredProxyWarned.get()) return;   // ← none 도 여기 걸림
      ... log.warn("... webhook.trusted-proxy-cidrs 가 비어 있습니다 ... '{}' 을 명시하세요.", NO_PROXY);
  }
  ```
  **3차 실측 재확인** — `WEBHOOK_TRUSTED_PROXY_CIDRS=none` 인 현재 스택에서 XFF 부착 요청 직후:
  ```
  2026-08-04 00:09:37.714 WARN k.c.c.a.c.s.webhook.ClientIpResolver -
    [Webhook] X-Forwarded-For 가 관측됐으나 webhook.trusted-proxy-cidrs 가 비어 있습니다.
    XFF 는 폐기하고 remoteAddr 로 집계합니다. ... 직접 노출이면 'none' 을 명시하세요. (이 경고는 프로세스당 1회만 출력)
  ```
  → 이미 `none` 인데 "`none` 을 명시하세요"라고 안내한다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep TRUSTED_PROXY          # → WEBHOOK_TRUSTED_PROXY_CIDRS=none
  curl -s -o /dev/null -X POST http://localhost:18081/api/v1/vlm/callback \
    -H 'Content-Type: application/json' -H 'X-Forwarded-For: 203.0.113.9' --data '{"a":1}'
  docker logs klid-backend 2>&1 | grep "trusted-proxy-cidrs 가 비어"
  ```
- **영향**: 보안 통제 자체는 정상(XFF 폐기 + remoteAddr 집계가 기대대로 동작함을 이번 회차에 실증). 영향은 **운영 관측 품질** — 정상 설정에 대한 오경보로 진짜 설정 누락(프록시 뒤 배포인데 미설정)을 놓칠 수 있다. `WebhookIpAllowlist` 와의 **정책 표현 비대칭**이기도 하다.
- **수정 방향(제안)**: `ClientIpResolver` 에 `private final boolean explicitNoProxy = isNoProxy(trustedProxyCidrs);` 를 보존하고 `warnIfUnconfiguredProxy` 조건에 `&& !explicitNoProxy` 를 추가한다(또는 `none` 명시일 때 기동 시 INFO 1회만 남기는 `WebhookIpAllowlist:64-67` 방식으로 통일). ⚠ 구현은 하지 않음.


### [A-ISSUE-43] TC-HMAC-002 / 004~011 / 020 / 025~028 — HMAC **서명 검증 경로 전체가 네트워크로 도달 불가**(라이브 무검증 구간) 〔3차 신규 · 관측/확인필요〕

- **심각도**: LOW (동작 결함 아님 — 검증 가능성·회귀 탐지력의 문제)
- **기대 동작(기대효과)**: 카탈로그가 서명 경로에 13건의 보안 케이스(상수시간 비교·timestamp 창·nonce replay·503 fail-closed·5xx 시 nonce 해제 등)를 두고 있다면, 그 코드는 **실환경에서 한 번이라도 실행되는 경로**여야 회귀가 드러난다. 실행되지 않는 방어는 "있다고 믿는" 상태로 조용히 썩는다.
- **현재 동작(이슈 내용)**: Phase 7-A2 이후 `SIGNATURE_REQUIRED = List.of()`(`WebhookProtectedPaths.java:88`)라 서명 필수 경로가 0개다. 서명 분기(`HmacWebhookFilter.handleSignatureRequired`, :299-432)로 들어가는 유일한 조건은 `requiresSignature(request)==true`, 즉 `pathWithinApplication(request)==null`(경로 판정 불가, fail-closed)뿐이다.
  그런데 **경로 판정을 깨뜨리는 요청은 Tomcat 이 필터보다 먼저 400 으로 끊는다** — 3차 실측:
  ```
  POST /api/v1/vlm/callback%       → 400 (Tomcat HTML)
  POST /api/v1/vlm/%zz             → 400 (Tomcat HTML)
  POST /api/v1/vlm/%00x            → 400 (Tomcat HTML)
  POST /api/v1/vlm/..%2fcallback   → 400 (Tomcat HTML)
  ```
  교차 확인: 이번 회차 60여 건의 웹훅 요청 후에도 `SELECT count(*) FROM ls_whk_sign_use` → **0**(서명 nonce 가 한 번도 소비된 적 없음).
  또한 실효 설정된 `WEBHOOK_HMAC_SECRET_AUGMENT`(64자 난수)는 **어떤 정상 트래픽에서도 사용되지 않는다**.
  단위 테스트도 이 사실을 인정하고 있다 — `HmacWebhookFilterTest:1577-1582` 가 `getRequestURI()` 가 예외를 던지는 `MockHttpServletRequest` 를 만들어야만 이 분기에 들어간다.
- **재현/확인 경로**:
  ```bash
  for P in "/v1/vlm/callback%" "/v1/vlm/%zz" "/v1/vlm/%00x" "/v1/vlm/..%2fcallback"; do
    curl -s -o /dev/null -w "$P → %{http_code}\n" -X POST --path-as-is \
      "http://localhost:18081/api$P" -H 'Content-Type: application/json' --data '{"a":1}'
  done   # 전부 400 (필터 로그 [Webhook] 미출력)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT count(*) FROM ls_whk_sign_use;"  # → 0
  ```
- **영향**: 보안 약화는 **아니다**(오히려 Tomcat 이 1차 방어선으로 동작). 영향은 **검증 가능성** — ①TC-HMAC-002/004~011/020/025~028 13건이 매 회차 "정적+단위테스트로만" 판정될 수밖에 없어 회차별 신뢰 근거가 약하다 ②`HmacSigner`·nonce 저장소·`releaseNonce` 등의 회귀가 실환경에서 드러나지 않는다 ③운영이 의미 없는 시크릿을 관리·회전하게 된다(설정 위생).
- **수정 방향(제안)**: 셋 중 하나를 **정책으로 확정**할 것 (구현은 하지 않음).
  ① 서명 필수 웹훅을 하나라도 다시 등록할 계획이면 현 상태를 유지하고, 카탈로그에 "라이브 재현 불가" 를 명시(이번 회차에 §A-4 머리말로 반영함).
  ② 재등록 계획이 없으면 `SIGNATURE_REQUIRED`/`secretAugment`/nonce 저장소를 **폐지 대상으로 선언**하고 `LS_WHK_SIGN_USE`·`WEBHOOK_HMAC_SECRET_AUGMENT` 도 함께 정리한다(죽은 보안 설정 제거).
  ③ 유지하되 회귀 탐지력을 올리려면 `@Profile("local\|dev")` 서명 필수 테스트 엔드포인트(예: `/v1/dev/webhook/echo`)를 등록해 **서명 경로를 라이브로 태울 수 있게** 한다.
  ⚠ ②는 "철회된 정책 재시도" 이력이 있는 영역이므로 반드시 사용자 확정을 거칠 것.

---


## B클러스터

> 심각도순(CRITICAL→HIGH→MEDIUM→LOW) 정렬. 신규 이슈 24건(CRITICAL 0 · HIGH 2 · MEDIUM 6 · LOW 16). 판정 집계·카탈로그 정정 총계는 `docs/검증결과/2026-08-03/3차/B-result.md` §0~§2 참조.

### ★★ 최우선 확인 대상 3건 (요청에 따라 최상단 강조 — 본문은 아래 CRITICAL/HIGH 절에도 그대로 수록됨)

| 이슈 | 파트 | 심각도 | 한 줄 요약 |
|---|---|:--:|---|
| **B-ISSUE-01** (아래 HIGH 절에 전문 수록) | part1 | HIGH | 배치 진입 가드가 원자 클레임이 아니라 동시요청 시 파이프라인 중복실행(동시 5요청 → 외부 VLM 5중 위탁 실증, 1차 B-ISSUE-22 미해소·심각도 상향) |
| **B-ISSUE-41** (아래 MEDIUM 절에 전문 수록) | part3 | MEDIUM(온프렘 배포형상 한정 HIGH) | `STORAGE_RAW_PATH=STORAGE_DEIDENTIFIED_PATH` 동일 설정(온프렘 기본값) 시 비식별 base 허용 목록이 원본까지 포함해 심링크 검증 우회 기전을 실증 |
| **B-ISSUE-42** (아래 HIGH 절에 전문 수록) | part3 | HIGH | 신고 해소(resolve) 산출물 검증의 60초 시계오차 관용이 감산 방향으로만 적용돼 재비식별 없이 mtime=신고시각-30초 파일로 게이트가 일괄 해제됨을 재현(1차 B-ISSUE-102 미해소) |

---

### HIGH

### [B-ISSUE-01] TC-BATCH-045 — `BatchOrchestrator.process()` 진입 가드가 원자 클레임이 아니어서 동일 rawSn 동시 실행이 전혀 차단되지 않는다 (1차 B-ISSUE-22 미해소 · 심각도 상향)

- **심각도**: **HIGH** *(1차 MEDIUM → 상향. 근거: 이번 회차에 ①외부 벤더 5중 위탁 ②재시도 예산 즉시 소진 ③종단 상태 비결정성 3가지 신규 실증)*
- **기대 동작(기대효과)**: `BatchOrchestrator` 클래스 Javadoc 이 **"영상 단위 직렬 호출 보장 — `process(Long)` 는 단일 영상(rawSn) 에 대해 단일 스레드에서 호출된다"**(`BatchOrchestrator.java:50-51`)고 단언하고, TC-BATCH-045 는 진입 가드를 "APPROVED 영상에 AUTO 라벨이 새로 적재되는 무증상 오염 차단" 관문으로 규정한다. 파이프라인은 프레임 추출·AUTO 라벨 적재·**외부 VLM 위탁** 같은 비멱등 부수효과를 수행하므로, 같은 rawSn 에 두 실행이 겹치면 안 된다. 2노드 Active-Active 형상에서는 JVM 락이 방어가 되지 않아 DB 수준 클레임이 필요하다(`CLAUDE.md`: "Quartz 클러스터링은 트리거 중복만 막고 잡 내부 레이스는 원자 클레임이 별도로 막는다").
- **현재 동작(이슈 내용)**: 진입부의 조건부 UPDATE 는 **"검수 소유 상태인가"만** 판정하고 `PROCESSING` 은 차단 집합에 없어, **이미 실행 중인 영상도 그대로 통과**한다.
  ```java
  // BatchOrchestrator.java:112-115
  if (transitionService.markRawDataProcessingBlocked(rawSn)) {   // 검수 소유 4상태만 차단
      log.warn("[BatchOrchestrator] skipped — review-owned work status rawSn={}", rawSn);
      return BatchStage.SKIPPED;
  }
  ```
  ```java
  // BatchTransitionService.java:77-81  (PROCESSING·BATCH_QUEUED·ASSIGNED·FAILED 는 차단 대상 아님)
  public static final Set<String> REVIEW_OWNED_STATUSES = Set.of(
          STTS_PENDING, STTS_IN_REVIEW, STTS_APPROVED, STTS_REJECTED);
  ```
  **라이브 실측 (2026-08-03, rawSn=108 · 마킹 1건 보유 · 프레임 사전 삭제 후 동시 5요청)**:
  ```
  응답: COMPLETED 1건 / FAILED 4건  (409·SKIPPED 0건)

  backend 로그 (같은 밀리초에 5개 스레드가 동시 진입)
  00:30:12.329 [exec-6]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.330 [exec-8]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.332 [exec-7]  MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.332 [exec-10] MarkingLoadStep - marking check rawSn=108 count=1
  00:30:12.333 [exec-5]  MarkingLoadStep - marking check rawSn=108 count=1
  → ★외부 VLM 5중 위탁 (서로 다른 request_id 5개, mock-server 가 5회 수락)
  00:30:12.334 VlmTimeseriesStep - describe submit rawSn=108 request_id=55763134-…
  00:30:12.334 VlmTimeseriesStep - describe submit rawSn=108 request_id=94981639-…
  00:30:12.336 VlmTimeseriesStep - describe submit rawSn=108 request_id=3478cb28-…
  00:30:12.337 VlmTimeseriesStep - describe submit rawSn=108 request_id=51813285-…
  00:30:12.338 VlmTimeseriesStep - describe submit rawSn=108 request_id=92c7134a-…
  → ★재시도 예산이 단 1회 사고로 전량 소진
  00:30:12.469 BatchRetryQueue - enqueued rawSn=108 attempt=1 delaySec=60
  00:30:12.470 BatchRetryQueue - enqueued rawSn=108 attempt=2 delaySec=120
  00:30:12.472 BatchRetryQueue - enqueued rawSn=108 attempt=3 delaySec=240
  00:30:12.473 BatchRetryQueue - max attempts exceeded -- exhausted rawSn=108 attempt=4 max=3

  DB: ls_raw_data_status.ver  2 → 12   (=5×PROCESSING + 5×종단 = 10회 UPDATE)
  ```
  - **① 외부 벤더 5중 위탁** — 1차에서는 검체에 마킹이 없어 관측되지 않았던 신규 피해다. 동일 영상에 대해 VLM `describe` 가 5회 나가고 5개 콜백이 되돌아와 `LS_DATA_META` 를 순서 없이 덮어쓴다(로그: `result applied … updated=1` ×5). 외부 과금·자원 낭비 + 마지막 콜백이 이기는 비결정 결과.
  - **② 재시도 예산 즉시 소진** — 동시 실패 4건이 같은 `rty_nmtm` 카운터를 경쟁 증가시켜 **단일 사고로 `max-attempts=3` 가 그 자리에서 EXHAUSTED** 된다. 이후 진짜 재시도가 필요한 실패에 재시도가 남아 있지 않다.
  - **③ 종단 상태 비결정** — 이번엔 우연히 COMPLETED 스레드가 마지막에 끝나 `LS_DATA_RAW=COMPLETED` 로 남았지만, 순서가 뒤집히면 파이프라인이 성공했는데도 `FAILED` 로 마감된다(마지막 쓰기 승).
  - 개별 진입점의 클레임(`tryClaimBatchQueued` / 재시도 큐 CAS / `tryClaimReprocessFromFailed`)은 **서로 다른 락**이라 진입점이 다르면 교차 방어가 되지 않고, dev 트리거의 `PROCESSING` 사전 검사(`BatchDevTriggerController.java:83-86`)는 read-then-act TOCTOU 다(위 실측에서 5건 전부 통과).
- **재현/확인 경로**:
  ```bash
  # 마킹이 있는 영상(rawSn=N)에 대해
  psql -c "DELETE FROM ls_data_src WHERE raw_sn=N;"      # 재추출 가능 상태로
  for i in 1 2 3 4 5; do (curl -sX POST ".../v1/dev/batch/trigger?rawSn=N" -H "Authorization: Bearer $TOK") & done; wait
  psql -c "SELECT ver FROM ls_raw_data_status WHERE raw_data_id=N;"   # 10 증가
  docker logs klid-backend | grep "describe submit rawSn=N" | wc -l   # 5
  ```
- **영향**: 데이터 정합(프레임·AUTO 라벨 중복 적재, 상태 전이 경합) · **외부 연동 계약 위반**(동일 작업 다중 위탁) · 가용성(재시도 예산 소진) · CWE-362(Race Condition) · CWE-1223(비원자 check-then-act) · CWE-770(외부 호출 무제한). 2노드 Active-Active + Quartz 클러스터링 형상에서 정확히 미방어인 구간.
- **수정 방향(제안)**: `markRawDataProcessingBlocked` 를 **"진입 클레임"** 으로 승격 — `LS_DATA_RAW.DATA_STTS_CD` 가 `PROCESSING` 이 **아닐 때만** `PROCESSING` 으로 바꾸는 조건부 UPDATE(영향 행수 1 인 호출만 진행, 0 이면 `SKIPPED`). 정상 종료 3경로(COMPLETED/FAILED/예외)에서 해제 보장 + **stale `PROCESSING` 회수 스윕**(`BatchRetryStaleReclaimSweeper` 동일 패턴, 노드 사멸 시 영구 고착 방지) 동반 필요. ⚠ 구현은 하지 않음.


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


---


### MEDIUM

### [B-ISSUE-21] TC-VLM-039 / TC-VLM-003 — 재개 경로의 `run()` 이 `readOnly=true` 라 보류 감사 기록이 read-only INSERT 로 실패한다 (1차 B-ISSUE-66 **미해소**, 3차 실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개 러너/미결 스위퍼가 부른 위탁이 **다시 보류**(게이트가 아직 닫힘)되거나 비활성으로 끝나면 그 사실이 `LS_BATCH_PROC_LOG` 에 사유와 함께 남아야 한다(B-ISSUE-24 규약 — "재처리 대상 식별이 애플리케이션 로그 보존기간에 종속되면 운영에서 복구 불가"). `VlmWithheldResumeRunner` javadoc(`:50`)도 "게이트가 아직 닫혀 있으면 스스로 다시 보류된다"를 전제한다.
- **현재 동작(이슈 내용)**: `VlmTimeseriesStep.run` 이 `@Transactional(REQUIRES_NEW, readOnly = true)`(`VlmTimeseriesStep.java:236`)인데 그 안에서 부르는 `BatchStatusService.recordVlmSkipped` 는 `REQUIRED`(`BatchStatusService.java:58-62`)라 read-only 트랜잭션에 참여해 PostgreSQL 이 INSERT 를 거부한다.
  ```java
  // VlmTimeseriesStep.java:236
  @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public VlmTimeseriesResponse run(Long rawSn) { return doSubmit(rawSn, null); }
  // → doSubmit:271(DISABLED) · :305(DEIDENT_REPORT) 에서 recordVlmSkipped(REQUIRED, INSERT)
  ```
  **3차 실동작 재현**(rawSn=105, `DE_IDNTF_YN='F'` 유지 + 미결 스위퍼가 재개 트리거):
  ```
  00:44:20.011 [vlm-submit-reclaim] [Batch] stage skipped recorded rawSn=105 stage=VLM   ← 스위퍼(REQUIRES_NEW) 성공
  00:44:20.018 [batch-async-2]      [VlmResume] resuming withheld VLM submit rawSn=105
  00:44:20.020 [batch-async-2]      [Batch][VlmTimeseries] withheld — deident report open rawSn=105
  00:44:20.021 [batch-async-2] ERROR ERROR: cannot execute INSERT in a read-only transaction
  00:44:20.022 [batch-async-2] WARN  [VlmResume] withheld VLM resume failed rawSn=105 cause=JpaSystemException
  ```
  `ls_batch_proc_log(105)` 에는 스위퍼가 쓴 `ACK_MISSING` 행만 있고 **재보류(`DEIDENT_REPORT`) 행은 적재되지 않았다**.
- **재현/확인 경로**:
  ```sql
  -- 게이트가 닫힌(F) 영상 + 시계열 메타 0건 + ISSUED 원장(ACK 창 경과)을 만든 뒤 스윕 tick(기본 15분) 대기
  insert into ls_webhook_idempotency (idmp_key,chnl_cd,stts_cd,raw_sn,reg_dt,mdfcn_dt)
    values ('QA-RO','VLM','ISSUED', <rawSn>, now()-interval '90 minutes', now()-interval '90 minutes');
  select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn=<rawSn>;
  ```
- **영향**: 데이터정합/운영. ①재보류 사실이 DB 에 남지 않아 "로그 보존기간 비종속" 보장이 이 경로에서만 깨진다 ②ERROR 스택이 남아 실장애와 구분이 어렵다 ③`vlm.client.enabled=false` 인 dev/stg/prd 기본 형상에서는 **모든** 재개(run 분기)가 이 예외로 끝나 `SKIP_REASON_DISABLED` 행도 남지 않는다. 기존 보류 기록 + "메타 0건" 조건이 남아 다음 회수에서 재시도되므로 영구 정체는 아니다(`max-reclaims=3` 소진까지).
- **수정 방향(제안)**: ①`run` 의 `readOnly=true` 제거(= `runWithMarking` 과 경계 속성 일치) 또는 ②`doSubmit` 의 `recordVlmSkipped` 호출을 이미 존재하는 `recordVlmSkippedInNewTx`(`BatchStatusService:78-82`)로 교체. ②가 비동기 완료 핸들러·스위퍼·`ledger.recordIssued` 와 규약이 같다. 회귀 가드는 **프록시 경유 `run` 호출 시 SKIPPED 감사 행이 실제로 커밋된다**는 IT(현행 `VlmTimeseriesStepTest` 는 mock 기반이라 이 경계를 못 잡는다).


---

### [B-ISSUE-22] TC-VLM-032 — 신고 구간 배치 재처리가 VLM 은 보류하지만 FRAME_EXTRACT 에서 실패해 배치 FAILED + 재시도 예산을 소진한다 (1차 B-ISSUE-61 **미해소**, 3차 실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 비식별 누락 신고는 정책적 차단이므로 "실패가 아니라 보류"여야 한다. TC-VLM-032 는 "예외 미발생 → 배치 FAILED 미전이, 재시도 큐 미등록, 시도 상한 미소진"을 요구한다.
- **현재 동작(이슈 내용)**: VLM 은 규약대로 보류하지만 바로 다음 단계가 같은 `'F'` 를 예외로 처리한다.
  ```java
  // FfmpegFrameExtractor.java:185-188
  if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {   // "Y" 만 통과 → 'F' 도 실패
      throw new CustomException(ErrorCode.INVALID_INPUT, "비식별이 완료되지 않은 영상입니다 rawSn=" + ...);
  }
  ```
  **3차 실동작**(rawSn=109 — 정상 배치 1회 완료 → 신고 접수 → 재처리):
  ```
  00:30:38.330 [Batch][VlmTimeseries] withheld — deident report open rawSn=109     ← 보류(정상)
  00:30:38.331 [Batch] stage skipped recorded rawSn=109 stage=VLM
  00:30:38.335 [BatchRetry] enqueued rawSn=109 attempt=1 delaySec=60                ← 기대 위반
  00:30:38.336 [BatchOrchestrator] failed rawSn=109 willRetry=true cause=CustomException
  ls_batch_proc_log: FRAME_EXTRACT / FAILED / "비식별이 완료되지 않은 영상입니다 rawSn=109"
  ```
- **재현/확인 경로**: `POST /v1/videos/{rawSn}/deident-report` → `POST /v1/dev/batch/trigger?rawSn={rawSn}` → `select proc_step_cd, proc_stts_cd, err_msg_cn from ls_batch_proc_log where data_raw_sn={rawSn}` + `select * from ls_bat_rty_wtng where raw_sn={rawSn}`.
- **영향**: 기능/운영. 신고 구간 자동 재시도가 반드시 실패하며 예산(3회, 60/120/240초)을 태우고, 영상이 FAILED 로 표시돼 장애로 오인된다. 해소 시 자동 복구는 VLM 재개뿐이라 프레임추출 이후 단계는 수동 `batch/retry` 가 필요하다. 외부 유출은 없다(전송은 정상 차단).
- **수정 방향(제안)**: `FfmpegFrameExtractor` 의 비식별 선행 가드를 `'N'` 만 실패로 두고 `'F'` 는 VLM 과 동일하게 보류(SKIPPED + 사유 적재)로 분기하거나, `BatchStep.isEnabled(ctx)` 로 신고 구간에서 post-marking 단계를 통째로 건너뛰고 재시도 큐에 넣지 않는다. 어느 쪽이든 해소 시 `DeidentGateReopenedEvent` 소비자를 늘려 프레임추출 이후도 재개돼야 한다.


---

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


---

### [B-ISSUE-61] TC-BATCH-164 — stale RETRYING 회수에 heartbeat 가 없어, 임계(기본 180분)를 넘긴 **정상 처리 중** 항목이 오회수된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: stale 회수는 **죽은 노드가 남긴** RETRYING 만 되살려야 한다. 살아서 처리 중인 항목을 뺏으면 같은 rawSn 파이프라인이 두 노드에서 동시에 돈다(라벨 이중 적재·재시도 카운터 이중 증가·프레임 중복 추출). 카탈로그 기대결과도 "정상 처리 중 항목은 cutoff 를 넘지 않아 대상 아님"을 단언한다.
- **현재 동작(이슈 내용)**: 회수 대상 판정축은 `MDFCN_DT`(= `claimAtomically` 가 찍는 **클레임 시각**)뿐이고, **처리가 진행되는 동안 이 값을 갱신하는 heartbeat 가 없다.**
  ```java
  // batch/retry/LsBatRtyWtngRepository.java:90-98 (후보 조회)
  SELECT q.BAT_RTY_SN FROM LS_BAT_RTY_WTNG q
   WHERE q.STTS_CD = 'RETRYING' AND q.MDFCN_DT <= :cutoff   // ← 클레임 시각 하나로만 판정
   ORDER BY q.MDFCN_DT ASC LIMIT :limit
  // batch/retry/BatchRetryStaleReclaimSweeper.java:144
  LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleTimeoutMinutes); // 정적 180분
  ```
  즉 안전성은 **"배치 1건 최장 처리 시간 < 180분"이라는 정적 가정**에만 의존한다(코드 주석도 `:33-35` 에서 이 가정을 명시). 가정이 깨지면 회수가 일어나고, 그 뒤를 받쳐줄 2차 가드도 없다 — `BatchOrchestrator.process` 진입 가드 `markRawDataProcessingBlocked`(`BatchTransitionService.java:111-122`)는 **검수 소유 작업 상태(PENDING/IN_REVIEW/APPROVED/REJECTED)만** 차단하고, `LS_DATA_RAW.DATA_STTS_CD` 가 이미 `PROCESSING` 이어도 그대로 통과시킨다(`LsDataRaw.markProcessing` 도 상태 머신 거부 없음).
- **재현/확인 경로**:
  ```sql
  -- 처리 중(RETRYING)인 항목의 MDFCN_DT 가 임계를 넘는 상황을 재현
  UPDATE ls_bat_rty_wtng SET stts_cd='RETRYING', rty_nmtm=1,
         mdfcn_dt = now() - interval '200 minutes' WHERE raw_sn = <처리중 rawSn>;
  -- 다음 스윕 tick(기본 15분) 대기 → 로그:
  --   [BatchRetry][Reclaim] stale RETRYING reclaimed=N ...   (실측 2026-08-04 00:29:20, reclaimed=2)
  -- 결과: STTS_CD='PENDING', RTY_NMTM+1 → 다른 노드가 즉시 같은 rawSn 을 재클레임 가능
  ```
  (본 검증에서 회수 자체는 실동작으로 확인했고, "처리 중 heartbeat 부재"는 코드 전수 조회로 확인 — `MDFCN_DT` 를 갱신하는 지점은 `claimAtomically`·`scheduleNext`·`markExhausted`·`@PreUpdate` 뿐이며 스텝 진행 중 호출되는 경로가 없다.)
- **영향**: 데이터 정합(라벨/프레임 이중 생성) + 자원(동일 영상 2벌 추론). CWE-362(Race Condition). 발생 조건은 "1건 처리 > 180분"이라 상시적이지는 않으나, 장시간 영상 + GPU 경합 + 외부 비식별 지연이 겹치면 도달 가능하며 **발생 시 조용히 진행**된다(경고 로그 없음).
- **수정 방향(제안)**: ①스텝 경계마다 `MDFCN_DT` 를 갱신하는 heartbeat(조건부 UPDATE, `STTS_CD='RETRYING'` + 소유 노드 식별자 일치)를 추가하고 임계를 heartbeat 주기의 배수로 잡는다. 또는 ②`BatchOrchestrator.process` 진입부에 rawSn 단위 재진입 가드(B-ISSUE-62 와 공통 해법)를 두어 회수가 오작동해도 이중 실행이 성립하지 않게 한다. ⚠ 구현은 하지 않았다.


---

### [B-ISSUE-62] TC-BATCH-177 — 배치 파이프라인 진입점이 **서로 다른 자원**을 클레임해, rawSn 단위 상호배제가 전 진입점에 걸쳐 성립하지 않는다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: "클러스터링은 트리거 중복 발화만 막고, 잡 내부 레이스는 원자 클레임이 막는다"가 성립하려면, **같은 rawSn 파이프라인을 두 주체가 동시에 실행할 수 없어야** 한다.
- **현재 동작(이슈 내용)**: `orchestrator.process(rawSn)` 진입점은 5곳이고 각자 **다른 자원**을 선점한다.
  | 진입점 | 클레임 자원 |
  |---|---|
  | `BatchReprocessService.retry` (수동) | `LS_DATA_RAW.DATA_STTS_CD` FAILED→PROCESSING |
  | `BatchRetryQuartzJob.execute` (자동 재시도) | `LS_BAT_RTY_WTNG` **큐 행** PENDING→RETRYING |
  | `MarkingBatchBridge`→`AsyncBatchRunner` | 작업상태 `BATCH_QUEUED`(`tryClaimBatchQueued`) |
  | `BatchQuartzJob` | 자체 큐 dequeue |
  | `BatchDevTriggerController` (dev) | 없음 |

  자원이 다르므로 서로를 배제하지 못한다. 구체적 창:
  1. 자동 폴러가 큐 행을 `RETRYING` 으로 커밋한 직후(`BatchRetryQuartzJob.java:48`) ~ `markRawDataProcessing` 커밋 전(`BatchOrchestrator.java:112`) 사이에 REVIEWER 가 수동 재처리를 호출하면, `LS_DATA_RAW` 는 아직 `FAILED` 라 **수동 클레임이 성공**한다. 수동 경로의 `retryQueue.clearIfIdle` 은 `RETRYING` 을 **보존**하므로(설계상 정당) 폴러도 계속 진행 → **같은 rawSn 파이프라인 2벌 동시 실행**.
  2. B-ISSUE-61 의 오회수가 발생한 경우에도 동일 결과.
- **재현/확인 경로**: 창이 수~수십 ms 라 수작업 재현은 비결정적이다. 코드 경로로 확정 가능하다 — `BatchReprocessService.retry`(`:77`, `:84`)와 `BatchRetryQuartzJob.execute`(`:48`, `:55`)가 각각 다른 테이블에 CAS 를 걸고, 공통 관문인 `markRawDataProcessingBlocked`(`BatchTransitionService.java:111-122`)는 `PROCESSING` 을 차단 사유로 보지 않는다:
  ```java
  if (transitionRawDataStatus(rawSn, LsRawDataStatus.STTS_PROCESSING)) { return true; } // 검수 소유만 차단
  videoRepository.findById(rawSn).ifPresentOrElse(LsDataRaw::markProcessing, ...);      // PROCESSING→PROCESSING 허용
  return false;                                                                          // → 진행
  ```
- **영향**: 데이터 정합(AUTO 라벨·프레임 이중 적재, `LS_LABEL_VERSION`/이력 오염) + 외부 위탁 중복(VLM 제출 2회). CWE-362. 단일 노드에서도 발생 가능(Quartz 클러스터링으로는 못 막는 축).
- **수정 방향(제안)**: `BatchOrchestrator.process` 진입부에 **rawSn 단위 실행권 클레임**을 단일 관문으로 추가한다 — 예: `LS_DATA_RAW.DATA_STTS_CD` 를 `PROCESSING` 으로 바꾸는 조건부 UPDATE 를 "현재 PROCESSING 이 아닐 때만" 성립시키고(0행이면 SKIPPED 반환), 기존 진입점별 클레임은 그대로 두어 방어심도를 유지한다. ⚠ 기존 SKIPPED 보상 경로(`releaseReprocessClaim`)와의 상호작용 검토 필요. 구현은 하지 않았다.


---

**[B-ISSUE-81] TC-BATCH-021 — 기대값 오류: `prvcTypeCd` 기대값이 `ANONY`로 적혀 있으나 실제 코드는 `PRVC`**
- 심각도: MEDIUM(카탈로그 정합성 — 실제 런타임 결함 아님)
- 기대 동작: 카탈로그 기대결과가 실제 구현과 일치해야 다음 회차가 회귀를 정확히 판정할 수 있다.
- 현재 동작(정정 전): `TC-BATCH-021` 행이 "`prvcTypeCd` 는 전체 비식별 정책상 항상 ANONY"라고 기술. 그러나 `TrainingVideoIngestTx.java:131` `private static final String DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_PRVC;`이며, 동 파일 110~130행 javadoc에 "2026-07-31 사용자 확정"으로 `PRVC`(fail-closed)를 채택하고 **`ANONY`로 두면 안 되는 이유**(원본 폴백으로 마스킹 전 프레임이 서빙되는 CWE-359)를 명시. 실측 DB(`ls_data_raw.raw_sn=107`)도 `prvc_type_cd='PRVC'`.
- 재현/확인 경로: `SELECT prvc_type_cd FROM ls_data_raw WHERE raw_sn=107;` → `PRVC`. `grep -n "DEFAULT_PRVC_TYPE" backend/.../TrainingVideoIngestTx.java`
- 영향: 카탈로그를 근거로 다음 회차가 "ANONY 여야 하는데 PRVC라 결함"이라고 오판(false FAIL)할 위험. 반대로 실제 코드가 ANONY로 회귀하면 이 카탈로그로는 감지되지 않음(위험 방향이 뒤집혀 있었음).
- 조치: `docs/test-cases/B-batch-deidentify.md` TC-BATCH-021 행의 기대결과·제목·근거(file:line)를 실제 코드에 맞춰 정정함(케이스명에 "★2026-08-03 실측 정정" 표기 추가, 정정 사유 인라인 기술).


---


### LOW

### [B-ISSUE-02] TC-DEID-007 — `AsyncDeidentifyRunner.loadRaw` 의 `@Transactional(REQUIRES_NEW, readOnly)` 가 `protected`+자기호출로 무효 (1차 B-ISSUE-05 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스 근거와 코드 주석이 "REQUIRES_NEW readOnly 로 영상 메타를 조회한다"(`AsyncDeidentifyRunner.java:109`)고 선언하면 실제로 그 경계가 적용돼야 한다. `CLAUDE.md` 도 "자기호출로 프록시를 우회하지 말 것"을 별도 구속 규칙으로 두고 있다(프레임 이미지 서빙 절).
- **현재 동작(이슈 내용)**: 1차 지적 이후 코드 무변경.
  ```java
  // AsyncDeidentifyRunner.java:79
  LsDataRaw raw = loadRaw(rawSn).orElse(null);        // ← this.loadRaw (프록시 미경유)
  // :110-112
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected Optional<LsDataRaw> loadRaw(Long rawSn) { ... }
  ```
  두 가지 이유로 동시에 무효다 — ①자기호출(AOP 프록시 우회) ②`AnnotationTransactionAttributeSource` 기본 `publicMethodsOnly=true` 라 `protected` 는 어드바이스 대상 아님.
- **재현/확인 경로**: 정적. 런타임은 `logging.level.org.springframework.transaction=TRACE` 로 적재 트리거 시 `loadRaw` 구간에 `Creating new transaction` 이 없음을 확인(코드 수정 금지라 이번 회차 미수행).
- **영향**: 현재 실피해 없음(`@Async` 라 앰비언트 tx 없음 + `SimpleJpaRepository` 자체 readOnly tx + `LsDataRaw` 는 연관 매핑 없는 평면 엔티티). 위험은 미래 — pre-marking 파이프라인이 확장돼 `runAsync` 가 트랜잭션 안에서 호출되거나 `LsDataRaw` 에 연관이 붙는 순간 전제가 조용히 깨진다. 주석이 사실과 달라 후속 개발자를 오도한다.
- **수정 방향(제안)**: 조회를 별도 빈(`…LookupService`)의 `public @Transactional(readOnly)` 로 분리하거나 `DeidentifyStep` 이 이미 쓰는 `ObjectProvider<Self>` 패턴 적용. 둘 다 아니면 어노테이션을 제거하고 javadoc 을 실제 동작에 맞춰 정정(선언만 남기는 것이 가장 나쁘다). 재발 방지로 "`@Transactional` 이 `private`/`protected` 에 붙으면 실패"하는 정적 가드 추가. ⚠ 구현은 하지 않음.


---

### [B-ISSUE-03] TC-BATCH-031 — `BatchOrchestrator.loadRaw` 도 동일한 `protected`+자기호출 무효 패턴 (1차 B-ISSUE-24 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: `BatchOrchestrator.process` Javadoc 이 *"process 자체는 트랜잭션을 시작하지 않으나(NOT_SUPPORTED), 영상 메타 조회를 위한 짧은 readOnly 트랜잭션은 필요 → 별도 메서드로 격리"*(`BatchOrchestrator.java:83-84`)라고 선언한다.
- **현재 동작(이슈 내용)**: 1차 지적 이후 코드 무변경.
  ```java
  // BatchOrchestrator.java:104
  LsDataRaw raw = loadRaw(rawSn);          // ← 자기호출(프록시 우회)
  // :147-149
  @Transactional(value = "controlTransactionManager", readOnly = true,
          propagation = Propagation.REQUIRES_NEW)
  protected LsDataRaw loadRaw(Long rawSn) { ... }   // ← protected + self-invoke = 사문화
  ```
  동일 패턴이 `AsyncDeidentifyRunner.java:110-117`(B-ISSUE-02), `DevPipelineRunner.java` 에도 있으며 주석에 *"BatchOrchestrator.loadRaw 패턴"* 이라 적혀 **의도적으로 복제 전파**됐다.
- **재현/확인 경로**: 정적(위 grep). 라이브 rawSn=102/108 정상 완주로 현재 기능 영향이 없음은 확인됨.
- **영향**: 현재 기능 영향 없음. 위험은 미래(연관 매핑 추가 시 `LazyInitializationException`, 조회 추가 시 커넥션 왕복 2회) + 주석-구현 불일치.
- **수정 방향(제안)**: B-ISSUE-02 와 동일 처방. 세 곳을 한 번에 정리하고 정적 가드로 재발 차단. ⚠ 구현은 하지 않음.


---

### [B-ISSUE-04] TC-BATCH-035 — stage 토글 오버로드 `process(Long, Map)` 의 호출자가 여전히 0건(도달 불가 경로) (1차 B-ISSUE-23 미해소)

- **심각도**: LOW
- **기대 동작(기대효과)**: Javadoc 이 *"토글 대상 단계(FRAME_EXTRACT/YOLO/SAM2)가 off 면 … dev 단일 파이프라인 수렴 경로에서 사용한다"*(`BatchOrchestrator.java:91-99`)라고 용도를 명시하고, TC-BATCH-035 는 그 시나리오를 검증 대상으로 잡는다.
- **현재 동작(이슈 내용)**: 프로덕션·dev 통틀어 이 오버로드를 호출하는 코드가 **한 곳도 없다**(1차 이후 무변경).
  ```
  $ grep -rn "orchestrator.process(" backend/src/main/java
  BatchDevTriggerController.java:119:  orchestrator.process(rawSn);
  BatchRetryQuartzJob.java:55:         orchestrator.process(rawSn);
  AsyncBatchRunner.java:25:            orchestrator.process(rawSn);
  BatchQuartzJob.java:51:              orchestrator.process(rawSn);
  BatchReprocessService.java:87:       orchestrator.process(rawSn);
  ```
  `BatchContext.stageToggles`·`BatchStep.isEnabled` 오버라이드 3종·단위테스트(`BatchOrchestratorTest`, `BatchContextToggleTest`)만 남아 커버리지 지표상으로는 드러나지 않는다.
- **재현/확인 경로**: 위 grep. `BatchDevTriggerController.trigger` 는 `rawSn` 만 받는다(`:76-78`) — REST 로 토글을 넘길 수 없다.
- **영향**: 기능 영향 없음. 테스트만 존재하는 미사용 분기라 "지원되는 기능"으로 오인될 수 있고, TC-BATCH-035 는 실동작 검증 대상이 없다(이번 회차 **N/A**).
- **수정 방향(제안)**: ①dev 토글이 필요하면 `BatchDevTriggerController.trigger` 에 optional `stages` 파라미터를 붙여 진입점 복구(`@Profile("!prd")` 안이라 노출 위험 없음), 또는 ②불필요하면 오버로드·`stageToggles`·3개 `isEnabled` 오버라이드·관련 테스트를 함께 제거하고 카탈로그에서 TC-BATCH-035/036 을 폐기 표기. ⚠ 구현은 하지 않음.


---

### [B-ISSUE-05] TC-DEID-002 — 적재 롤백 시 비식별 미트리거를 보증하는 회귀 테스트·재현 경로가 없다

- **심각도**: LOW (커버리지 갭 — 현재 동작 결함 아님)
- **기대 동작(기대효과)**: TC-DEID-002 는 "적재 tx 롤백 → 리스너 미호출"을 P1 로 규정한다. 이 단언이 깨지면 **DB 에 없는 영상에 대해 외부 KPST 위탁이 나가고** 원장(`LS_DEIDENT_PROC_LOG`)에 고아 행이 쌓인다.
- **현재 동작(이슈 내용)**: 동작 자체는 Spring 계약으로 보장된다(`IngestDeidentifyBridge.java:26` — `fallbackExecution` 미지정 = 기본 `false` → 롤백 시 AFTER_COMMIT 콜백 미실행).
  ```java
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onVideoIngested(VideoIngestedEvent event) { ... }
  ```
  그러나 ①이를 고정하는 테스트가 없고(`IngestDeidentifyBridgeTest` 는 `VideoIngestedEvent_수신_시_AsyncDeidentifyRunner_위임` 1건뿐 — 리스너를 직접 호출하므로 트랜잭션 축을 전혀 검증하지 않는다) ②`fallbackExecution = true` 를 실수로 추가하거나 이벤트 발행이 tx 밖으로 옮겨져도 **아무 테스트도 실패하지 않는다**. 이번 회차에도 publish 이후 롤백을 유발할 진입점이 코드에 없어 라이브 반증을 수행하지 못했다.
- **재현/확인 경로**: 현재로선 없음. 검증하려면 `@SpringBootTest` + `TestTransaction`/의도적 예외로 `TrainingVideoIngestTx.ingestOne` 을 롤백시키고 `AsyncDeidentifyRunner` 스파이가 **0회 호출**됨을 단언하는 IT 가 필요하다.
- **영향**: 회귀 감지 불가(잠재). 실제 유출 시 외부 벤더에 존재하지 않는 영상이 위탁된다.
- **수정 방향(제안)**: `IngestDeidentifyBridgeTest` 에 트랜잭션 축 IT 1건 추가 — ①커밋 시 1회 호출 ②롤백 시 0회 호출 ③무트랜잭션 publish 시 0회 호출. 동일 패턴이 `MarkingBatchBridge`·`DatasetExportBridge` 등 AFTER_COMMIT 브릿지 전반에 적용 가능. ⚠ 구현은 하지 않음.

---


---

### [B-ISSUE-23] 카탈로그 정합 — B-5 skip 케이스 4건이 1차 B-ISSUE-41 해소(마킹 `SKIPPED` 종결)를 반영하지 않았다
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 기대결과가 현재 구현의 계약을 담아야, 다음 회차가 "skip 인데 마킹이 PENDING 으로 남는다"를 결함으로 재발견하거나 반대로 종결 누락을 놓치지 않는다.
- **현재 동작(이슈 내용)**: TC-BATCH-050/051/052/057 의 기대결과가 "WARN, 미트리거"에 머물러 있었고, 신설된 `MarkingBatchBridge.skip()`(`:187-190`) → `MarkingSkipTxService.terminateSkipped`(REQUIRES_NEW·PENDING 한정) 계약이 어디에도 없었다. 또 050~052 가 마킹 API 경로에서 **프리컨디션에 선차단되어 도달 불가**(방어심도)라는 사실도 미기재였다.
- **재현/확인 경로**: `docs/test-cases/B-batch-deidentify.md` 109~121행 대조 · 위 §1 실동작 로그.
- **영향**: 카탈로그 정합(다음 회차 오판 위험).
- **수정 방향(제안)**: **이번 회차에서 정정 완료**(TC-BATCH-050/051/052/057 기대결과 + 근거 `MarkingBatchBridge.java:187-190`·`MarkingSkipTxService` 추가). `UNCERTAINTIES.md` 및 1차 ISSUES 의 B-ISSUE-41 을 **해소 처리**할 것.


---

### [B-ISSUE-24] 카탈로그 정합 — B-7 근거·기대결과 드리프트 4건(TC-VLM-009 라인 · TC-VLM-022 범위 · TC-BATCH-058/059 라인)
- **심각도**: LOW
- **기대 동작(기대효과)**: `file:line` 근거로 다음 회차가 대조 가능해야 하고, 기대결과가 실제 판정 로직을 담아야 한다.
- **현재 동작(이슈 내용)**: ①TC-VLM-009 근거 `VlmTimeseriesStep.java:373-377` 은 **동기 조립 실패 catch** 구간이고 케이스가 말하는 `switchIfEmpty(Mono.error(...))` 는 `:364-365`, err 핸들러는 `:369-370` 이다. ②TC-VLM-022 기대결과가 "VLM_REQUESTED→VLM_COMPLETED"로만 적혀 있어, 실제 조회 범위인 **`ACTIVE_STATUSES`(PENDING 포함) + 발급시각 이후 생성 PENDING 제외(L6)** 를 담지 못했다(`VlmResultService:152-161,186-208`) — 이 상태로는 "콜백 선행 레이스" 회귀를 잡을 수 없다. ③TC-BATCH-058/059 근거 `:299-320`/`:299-332` 가 다음 메서드 javadoc 까지 걸쳐 있어 실제 메서드(`:299-314`)와 어긋났다.
- **재현/확인 경로**: 각 파일 해당 라인 Read 대조.
- **영향**: 카탈로그 정합.
- **수정 방향(제안)**: **이번 회차에서 정정 완료**(4건 모두). ⚠ **`## 변경 이력` 표의 회차 행(5회차)은 병렬 파트 에이전트 간 번호 충돌을 피하려 추가하지 않았다** — 병합 단계에서 B 클러스터 정정 총계를 합산해 1행으로 추가할 것.


---

### [B-ISSUE-25] 커버리지 갭 — 미결 스위퍼(`VlmSubmitPendingSweeper`) 기계에 대응하는 케이스가 B-7 에 0건 (1차 B-ISSUE-65 부분 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 논블로킹 제출의 **유일한 회수 경로**(노드 사망 시 in-flight subscription 유실 대비)가 카탈로그로 검증 가능해야 한다. 1차 B-ISSUE-65 는 근거 라인 재산출과 함께 `TC-VLM-041~` 신규 케이스 추가를 제안했고, 라인은 4회차에서 재산출됐으나 **케이스 추가는 이뤄지지 않았다**.
- **현재 동작(이슈 내용)**: B-7 36건 어디에도 ①ACK 창(`stale-timeout-minutes` 30분)과 콜백 창(`callback-timeout-minutes` 360분)의 **2종 임계 분리** ②하한 clamp(`MIN_STALE_TIMEOUT_MINUTES=10` / `MIN_CALLBACK_TIMEOUT_MINUTES=60`) ③원자 클레임(`claimStale`/`claimStaleAccepted`) ④회수 예산(`max-reclaims=3`) ⑤`SubmitSignalDispatch` 풀 거부 시 기록 포기 ⑥원장 `ISSUED→ACCEPTED` 전이(`recordAckReceived`)에 대응하는 행이 없다. 이번 회차에 이 경로가 **실제로 동작함**을 확인했다(§2-3) — 즉 검증 가능한 산 코드인데 카탈로그만 비어 있다.
- **재현/확인 경로**: `grep -c 'TC-VLM-0(4[1-9])' docs/test-cases/B-batch-deidentify.md` → 0 · `ls backend/src/test/java/kr/co/cudo/authoring/batch/vlm/`(`VlmSubmitAckWindowIT`·`VlmSubmitPendingSweeperTest`·`VlmSubmitReclaimAtomicClaimIT` 등 기존 테스트 자산 존재).
- **영향**: 커버리지 갭 — 회수 임계를 잘못 좁히면 "정상 분석 중인 위탁을 뺏어 같은 비식별 영상을 중복 위탁"(H1)하는 회귀가 카탈로그로 감지되지 않는다.
- **수정 방향(제안)**: `TC-VLM-041~048` 을 신설해 위 ①~⑥ + "예산 초과 시 재개 억제(기록만)" + "rawSn 매핑 없는 원장 행 제외"를 덮는다. 기존 `batch/vlm/*` 테스트와 1:1 매핑 가능. (본 파트는 담당 라인범위 밖 행 추가를 하지 않아 제안만 남긴다.)

---


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


---

### [B-ISSUE-45] 카탈로그·문서 표기 `DE_IDNTF_YN` vs 실제 물리 컬럼 `DE_IDENT_YN` 불일치
- **심각도**: LOW
- **기대 동작(기대효과)**: DB 컬럼 물리명은 문서·케이스·코드가 하나여야 SQL 기반 검증이 성립한다.
- **현재 동작(이슈 내용)**: 실제 컬럼은 **`LS_DATA_RAW.DE_IDENT_YN`** 이다(`\d ls_data_raw` 실측, 엔티티 `LsDataRaw.java:118 @Column(name = "DE_IDENT_YN")`). 그런데 카탈로그(TC-STREAM-B21·TC-DEID-054·TC-DEID-059 등)와 루트 `CLAUDE.md` 는 `DE_IDNTF_YN` 과 `DE_IDENT_YN` 을 **혼용**한다(자바 필드명은 `deIdntfYn` 이라 혼동을 키운다).
- **재현/확인 경로**: `select de_idntf_yn from ls_data_raw;` → `ERROR: column "de_idntf_yn" does not exist / HINT: Perhaps you meant "ls_data_raw.de_ident_yn"`.
- **영향**: 검증·운영 SQL 이 그대로 실패한다(이번 검증에서도 첫 쿼리가 실패). 데이터 손상 위험은 없다.
- **수정 방향(제안)**: 표준용어 관점에서 어느 쪽이 정본인지 확정한 뒤(비식별=DE_IDNTF 가 표준단어 조합에 가깝다) **①물리명을 바꾸지 않고 문서·케이스 표기를 `DE_IDENT_YN` 으로 통일**하거나, ②컬럼 rename 마이그레이션을 하되 뷰·엔티티·온프렘 SQL 동반 수정. ①이 저비용이며 이번 회차에서는 **카탈로그를 건드리지 않았다**(내 담당 범위 밖 파일에도 같은 표기가 퍼져 있어 일괄 결정이 필요). ⚠ 구현은 하지 않았다.

---


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


---

### [B-ISSUE-63] JVM(Asia/Seoul)과 DB(UTC)가 같은 `timestamp without time zone` 컬럼에 **9시간 다른 시각**을 쓴다
- **심각도**: LOW (현재 활성 결함 아님 — 잠재)
- **기대 동작(기대효과)**: stale/lease/backoff 판정은 전부 시각 비교다. 같은 컬럼을 읽고 쓰는 모든 주체가 **동일 기준 시계**여야 판정이 성립한다.
- **현재 동작(이슈 내용)**: 컨테이너 OS·DB 는 UTC 인데 JVM 만 KST 다.
  ```
  backend 컨테이너: date → "Mon Aug  3 03:30:24 PM UTC 2026", TZ 미설정
  DB:               SHOW timezone → Etc/UTC ; now() → 2026-08-03 15:30:24+00
  앱이 쓴 값:        SELECT max(mdfcn_dt) FROM ls_bat_rty_wtng → 2026-08-04 00:29:32   ← +9h
  ```
  원인: `backend/Dockerfile:42` `-Duser.timezone=Asia/Seoul` + `application.yml:48` `jdbc.time_zone: Asia/Seoul`. 반면 `LS_BAT_RTY_WTNG.REG_DT/MDFCN_DT`·`LS_DEIDENT_PROC_LOG.REQ_DT/REG_DT` 는 **`DEFAULT CURRENT_TIMESTAMP`(UTC)** 이고, 네이티브 쿼리 `LsBatRtyWtngRepository.insertIfAbsent`(`:45-48`)도 `CURRENT_TIMESTAMP` 를 직접 쓴다.
  현재는 무해하다 — `insertIfAbsent` 직후 같은 트랜잭션에서 `incrementAttempt()`/`scheduleNext()` 가 JVM 시각으로 덮어쓰고, `LsDeidentProcLog` 의 `reqDt` 도 Java 에서 `LocalDateTime.now()` 로 명시 세팅된다(`:127`). 그래서 **DB 기본값이 살아남는 경로가 현재는 없다.**
- **재현/확인 경로**: 위 3줄. 검증 중 실제로 이 스큐 때문에 "방금 클레임한 대조군 행"이 스윕 대상이 되어 **검증자가 오판할 뻔했다**(psql `now()` 로 시드 → JVM 기준 9시간 과거로 해석됨).
- **영향**: 잠재 — DB 기본값/네이티브 `CURRENT_TIMESTAMP` 로 채워진 행이 하나라도 JVM 판정에 노출되면 **항상 임계 초과(9시간 과거)** 로 판정된다. stale 회수·ACK 유예(`withinSubmitAckGrace`)·폴링 리스 전부 같은 축이다. 운영 SQL 로 수동 점검·복구할 때도 오판 위험이 상시 존재한다.
- **수정 방향(제안)**: ①컨테이너 `TZ=Asia/Seoul` 을 함께 지정해 DB 세션·OS·JVM 기준을 맞추거나, ②시각 컬럼을 `timestamptz` 로 전환하거나, ③네이티브 쿼리·DDL 기본값의 `CURRENT_TIMESTAMP` 를 전부 파라미터 바인딩(JVM 시각)으로 통일한다. ⚠ ②는 마이그레이션 범위가 커 별도 판단 필요. 구현은 하지 않았다.


---

### [B-ISSUE-64] `kpst.deid.enabled` 기본값이 빈 조건과 헬스 인디케이터에서 **비대칭**이다
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 프로퍼티를 읽는 두 지점의 미설정 기본값이 같아야, 설정 누락 시 "빈은 없는데 헬스는 있다고 판단"하는 어긋남이 생기지 않는다.
- **현재 동작(이슈 내용)**:
  ```java
  // batch/service/KpstDeidentService.java:69  (KpstSubmitOutcomeRecorder:33, KpstDeidentPollTriggerConfig:20 동일)
  @ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")   // matchIfMissing 없음 → 미설정 = false
  // observability/health/DeidentifyHealthIndicator.java:67
  @Value("${kpst.deid.enabled:true}") private boolean kpstEnabled;                        // 미설정 = true
  ```
  `application.yml:500` 이 `${KPST_DEID_ENABLED:true}` 로 항상 값을 주입하므로 실환경에서는 드러나지 않는다.
- **재현/확인 경로**: `kpst.deid.enabled` 프로퍼티를 완전히 제거한 컨텍스트에서 기동 → 서비스/WebClient 빈 미등록 + 헬스는 `kpstEnabled=true` 로 판단. 결과는 `kpstWebClient == null` 분기로 흘러 **DOWN(mode=unconfigured)** 이라 fail-closed 는 지켜진다.
- **영향**: 기능 영향 없음(fail-closed 로 수렴). 다만 "헬스 판정이 실행 경로와 동일 기준"이라는 클래스 javadoc(`:29-31`)의 단언이 문자 그대로는 성립하지 않아, 향후 분기가 늘 때 어긋날 표면이 된다.
- **수정 방향(제안)**: `DeidentifyHealthIndicator` 의 기본값을 `${kpst.deid.enabled:false}` 로 맞추거나, 조건부 빈 쪽에 `matchIfMissing = true` 를 명시해 한쪽으로 통일. ⚠ 구현은 하지 않았다.


---

### [B-ISSUE-65] 카탈로그 정합 — 기대값 오류 2건 + 근거 드리프트 3건 + 계약 누락 1건
- **심각도**: LOW (카탈로그 자체의 정합성 결함 — 오탐/거짓 PASS 유발)
- **기대 동작(기대효과)**: 케이스의 기대결과·근거가 현재 구현과 일치해야 다음 회차 판정이 성립한다.
- **현재 동작(이슈 내용)**: §4 표의 6건. 특히 ①·⑤ 는 **기대값 자체가 코드와 다르다**:
  - TC-BATCH-166: `0`/음수 입력의 실제 결과는 30 이 아니라 **180**(`BatchRetryStaleReclaimSweeper.java:94-97` `staleTimeoutMinutes < 1 ? DEFAULT_STALE_TIMEOUT_MINUTES : Math.max(MIN_STALE_TIMEOUT_MINUTES, ...)`). 기대값대로 검증하면 정상 코드가 FAIL 로 오판된다.
  - TC-BATCH-164: "정상 처리 중 항목은 대상 아님"이 무조건 단언이라, B-ISSUE-61 의 실제 리스크가 카탈로그상 **검증 대상에서 빠져 있었다**.
- **재현/확인 경로**: §4 표의 file:line 을 Read 하여 대조.
- **영향**: 다음 회차에서 정상 동작을 FAIL 로 오판(①) 하거나, 실재하는 동시성 리스크를 PASS 로 덮을 수 있다(⑤).
- **수정 방향(제안)**: **본 검증에서 담당 라인범위(317~394) 내 6셀을 직접 정정 완료**(§4). 프로덕션 코드는 수정하지 않았다.

---


---

**[B-ISSUE-82] TC-BATCH-001~005·010~025(20건) — `TrainingVideoIngestService.java`/`TrainingVideoIngestTx.java` 근거 `file:line` 대량 드리프트(+20~70행)**
- 심각도: LOW(카탈로그 정합성)
- 기대 동작: 근거 라인이 실제 코드 위치를 가리켜야 함.
- 현재 동작: 2026-08-02(2차) 재작성 시점 이후 두 파일에 방대한 javadoc(트랜잭션 규약·CWE 근거 설명)이 추가되며 대부분의 참조 라인이 실제 위치보다 20~70행 앞선 값으로 고정되어 있었음(예: TC-BATCH-001 `:69-74`→실제 `:127-133`, TC-BATCH-022 `:505-521`→실제 `:577-590`). 같은 라운드의 "근거 file:line 전수 재확인"(회차 이력 4번째 행)이 명시한 드리프트 원인 파일 목록에 이 두 파일이 빠져 있어 놓친 것으로 추정(git log 상 `0449e9e7` "MNG_* 로직 검토 후속조치" PR이 유력 원인 커밋).
- 재현/확인 경로: `grep -n "no pending ingest rows to scan" backend/.../TrainingVideoIngestService.java` → 131 (카탈로그 구값 69-74와 불일치했던 사례).
- 영향: 다음 회차 검증자가 근거를 열람할 때 엉뚱한 코드를 보게 됨(라인 자체는 여전히 파일 안에 존재하는 다른 javadoc 문단이라 당장 오판까지는 안 가지만, 신뢰도 저하 + 검증 시간 낭비).
- 조치: 25건 중 20건(TC-BATCH-001~005, 010~025)의 근거 라인을 grep으로 재확인해 정정함. TC-BATCH-006~009(ControlTrainingVideoScanJob/TriggerConfig)는 드리프트가 3~5행 이내로 허용 오차 범위라 정정하지 않음.

### 이월 이슈 — 코드 자체의 미해소 결함 (정정 아님, 보고만)


---

**[B-ISSUE-83] TC-BATCH-008 관련 — Quartz JobDetail 설명 문구가 폐기된 구 스킴(`JOB_DMND_YN='Y'`)을 그대로 유지**
- 심각도: LOW
- 기대 동작: B-1이 관제 인입 픽업(`LS_DATA_INGEST`)으로 전환된 만큼(B-ISSUE-01, 2026-08-02 해소) 운영 메타데이터도 갱신되어야 QRTZ 테이블을 조회하는 운영자가 오인하지 않는다.
- 현재 동작: `ControlTrainingVideoScanTriggerConfig.java:33` — `.withDescription("관제 학습용 영상(JOB_DMND_YN='Y') 픽업 적재")`. 이 조건은 이미 폐지된 구 `MNG_CLIP_MASTER` 스캔 방식의 조건이며 현재는 `LS_DATA_INGEST.PROC_STTS_CD='PENDING'` 픽업이다. 이 문자열은 `QRTZ_JOB_DETAILS` 테이블에 그대로 적재된다.
- 재현/확인 경로: `docker exec klid-postgres psql -U klid_user -d klid_system -c "SELECT job_name, description FROM qrtz_job_details WHERE job_name='controlTrainingVideoScanJob';"` (또는 소스 grep으로 확인 — 위 라인).
- 영향: 기능 결함 아님(잡 동작 자체는 정상). 다만 2026-08-01(1차) 회차의 B-ISSUE-04와 동일 사안으로, 이월표(UNCERTAINTIES.md "미해소 이월 이슈")에 등재되지 않아 놓칠 뻔했음 — **미해소 상태이며 이번 회차에도 재확인됨**.
- 수정 방향(제안): `.withDescription("관제 학습용 영상(LS_DATA_INGEST PENDING) 픽업 적재")` 로 문구만 교체(동작 변경 없음). 프로덕션 코드이므로 이번 검증에서는 수정하지 않음.


---


## C클러스터

> 심각도순(CRITICAL→HIGH→MEDIUM→LOW) 정렬. CRITICAL 0건 · HIGH 2건(모두 신규) · MEDIUM 7건 · LOW 17건 = 총 26건.
> C클러스터 6개 파트(C-1 TC-MARK · C-2 TC-LABEL(3분할) · C-3 TC-SAM2 · C-4 TC-KEYPOINT · C-5 TC-TRACK · C-6 TC-PRESET) 병합.
> part1(TC-MARK)은 1차 이슈(C-ISSUE-01~05, 1차 번호)를 표 형식으로만 미해소 대조했고 본 회차 신규 이슈 블록을 생성하지 않았다 — 그중 C-ISSUE-01(1차, AUTO 마킹 marks 개수 무상한)·C-ISSUE-02(1차, ffprobe가 입력 검증보다 먼저 실행)·C-ISSUE-03(1차, 원본 절대경로 노출)은 3차에서도 재현 확인된 **미해소 결함**이니 참고(원문은 `_raw/C-part1.md` "이전 회차 이슈 해소 여부 대조" 절 참조).

### ★★ HIGH 신규 결함 2건 (최우선 강조 — 본문은 아래 HIGH 절에도 그대로 수록됨)

- **[C-ISSUE-22]** TC-LABEL-12 인접 — 라벨 **저장** 경로에 비식별 신고 게이트가 없고 작업락(6h 만료)만 유일 방어라, 락이 sweep 으로 회수된 뒤에는 "조회 412 / 저장 200" 비대칭이 열린다. 실동작으로 `items:[]` PUT → 200 + 기존 라벨 전량 삭제(데이터 유실)까지 재현됨. 저장 응답에 좌표가 그대로 실려 412 열람 차단도 우회 가능(PII 노출).
- **[C-ISSUE-61]** TC-LABEL-113/116 인접 — 존재하지 않는 `id` 를 붙이면 좌표 경계 상한(400 거부, ★3 확정 정책) 검증이 통째로 우회된다. 실동작으로 프레임 밖 좌표(`[[999999,888888],...]`)가 `id` 첨부만으로 200 저장·`LS_DATA_LBL` 실제 적재까지 재현됨 — 오염 좌표가 검수 스냅샷·export·데이터마트로 전파 가능. 3차 회차에서 회귀 가드 케이스 **TC-LABEL-150 신설**(현재 상태 FAIL로 카탈로그에 직접 기록).

---

### HIGH

### [C-ISSUE-22] TC-LABEL-12 (인접) — 신고 구간 라벨 **저장** 방어가 작업락 하나뿐이라, 락 6h 만료 후 "조회 412 ↔ 저장 200" 비대칭이 열린다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 비식별 누락 신고(`DE_IDNTF_YN='F'`) 구간에는 그 영상의 라벨을 **읽지도 쓰지도 못해야** 한다. `CLAUDE.md` 는 *"신고 구간 동안 라벨 조회를 차단한다(412) … 저장·수정은 기존 작업락으로 409 차단"* 이라고 두 축을 나눠 선언하는데, 이 분업이 성립하려면 **작업락의 수명이 신고 구간과 같아야** 한다.
- **현재 동작(이슈 내용)**: 두 수명이 다르다.
  - 신고 락은 **6시간 만료**로 생성된다 — `backend/src/main/java/kr/co/cudo/authoring/auth/entity/LsAuthWorkLock.java:84`
    ```java
    public static LsAuthWorkLock lockRawForRedeident(Long rawSn, String ownerId) {
        ...
        lock.expireDt = lock.lockDt.plusHours(6);
    ```
  - `WorkLockSweepJob` → `WorkLockService.sweepExpiredLocks()` 가 만료 락을 **자동 회수(RELEASED)** 한다.
  - 그러나 `DE_IDNTF_YN='F'` 는 **외부 솔루션 수동 재비식별 후 `resolve` 호출까지** 남는다(정책상 자동 재비식별 큐 없음 → 6시간 초과가 정상 동선).
  - `LabelService.bulkUpsert` 는 `accessGuard.requireNotUnderDeidentReport(...)` 를 **호출하지 않는다**(`LabelService.java:250-258` — 락 검사만). 반면 `getByFrame:193` / `getHistory:482` 는 호출한다.

  **3차 실동작 재현 (rawSn=110 에 `DE_IDNTF_YN='F'` + 작업락 0건 상태를 만들어 확인 → 즉시 원복)**
  ```
  DB: ls_data_raw.de_ident_yn='F' (raw_sn=110),  ls_auth_work_lock LOCKED 0건

  GET  /api/v1/frames/515/labels        → 412 PRECONDITION_FAILED
       {"message":"비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요."}
  PUT  /api/v1/frames/515/labels  {"items":[]}
                                        → 200 OK, labelVersion 5→6
  DB:  select count(*) from ls_data_lbl where src_sn=515;  → 0   (기존 라벨 전량 삭제됨)
  ```
- **재현/확인 경로**:
  ```sql
  -- 운영 재현 조건: 신고 접수 후 6시간이 지나 sweep 이 락을 회수했고 아직 resolve 안 된 영상
  SELECT r.raw_sn, r.de_ident_yn,
         (SELECT count(*) FROM ls_auth_work_lock l
           WHERE l.data_raw_sn = r.raw_sn AND l.lck_stts_cd='LOCKED') AS locked
    FROM ls_data_raw r
   WHERE r.de_ident_yn = 'F';
  -- locked = 0 인 행이 이 창에 들어간 영상
  ```
  ```bash
  # 해당 rawSn 의 프레임에 대해
  curl -i -X GET $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T"   # → 412
  curl -i -X PUT $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T" \
       -H 'Content-Type: application/json' -d '{"items":[]}'                # → 200 (전량 삭제)
  ```
- **영향**:
  - **데이터 유실(HIGH)**: 저장 계약이 full-replace 인데 **조회는 412 로 막혀 있어** 작업자/FE 가 현재 라벨 세트를 읽을 수 없다. 그 상태에서 저장이 통과하므로 **불완전한(또는 빈) 세트가 전량 교체로 커밋**되어 기존 라벨이 조용히 사라진다. 실동작에서 `items:[]` 로 전량 삭제가 실제로 성공했다.
  - **정책 우회(CWE-863 — Incorrect Authorization / OWASP A10:2025 fail-open)**: 신고 게이트의 목적(재비식별 완료 전 라벨 축 접근 차단)이 시간 경과만으로 반쯤 무력화된다. 게이트가 "조회만 막고 쓰기는 연다"는 상태는 어느 정책 문서에도 없다.
  - **PII 간접 노출**: 저장 응답(`LabelResponse`)에는 저장 결과 items 의 **좌표가 그대로 실린다**. 즉 412 로 막힌 좌표를 **PUT 으로 우회 열람**할 수 있다(무변경 재전송이면 부작용 없이 조회 효과만 얻음). 라벨 좌표는 CLAUDE.md 가 "PII 위치 특정 정보"로 규정한 대상이다(CWE-359).
  - **역할 무관**: WORKER(본인 배정)·REVIEWER 모두 해당.
- **수정 방향(제안)**: 아래 중 하나. 조합이 가장 안전하다.
  1. **(권장) `LabelService.bulkUpsert` 에도 게이트를 건다** — `accessGuard.verifyAndGet` 직후, 락 검사 앞뒤 어디든 `accessGuard.requireNotUnderDeidentReport(current.getRawSn())` 추가. 게이트는 이미 인가 이후 평가되는 프리컨디션이고 `DeidentReportGate` 단일 원천이라 판정 중복이 생기지 않는다. 단 **응답 코드 정합** 검토 필요 — 저장 경로의 기존 신고 차단은 409(작업락)였으므로, 412 로 바꾸면 FE 분기가 늘어난다. 락 있으면 409·없으면 412 로 갈리는 것도 상태 오라클이 되므로 **저장 경로는 412 로 통일**을 권장.
  2. 신고 락에 **만료를 두지 않는다**(`expireDt = null`) — 신고 락만 sweep 대상에서 제외. 다만 락 누수 시 영구 차단이 되므로 resolve 경로가 유일 해제라는 점을 확인해야 한다.
  3. `sweepExpiredLocks` 회수 시 `DE_IDNTF_YN='F'` 인 RAW 의 `REASON=REDEIDENT` 락은 **만료 연장**(재획득)한다.

  ⚠ 어느 안이든 **★1 확정 정책(게이트 판정 범위 = 자기 rawSn 행 하나, 조상/자손 전파 금지)** 을 건드리지 않는다 — 본 이슈는 전파 범위가 아니라 **동일 rawSn 내 읽기/쓰기 축 비대칭**이다. ⚠ **구현은 하지 않음.**

---

### [C-ISSUE-61] TC-LABEL-113/116 인접 — 존재하지 않는 `id` 를 붙이면 좌표 경계 상한(400) 검증이 통째로 우회된다

- **심각도**: HIGH
- **기대 동작(기대효과)**: ★3(확정 정책) — **사용자 저장 경로의 좌표는 이미지 경계를 넘으면 400 으로 거부**한다(클램프 아님, 사용자 확정). 이유는 "작업자가 의도한 위치가 조용히 왜곡되면 안 된다"이며, 그 결과 `LS_DATA_LBL` 에는 프레임 밖 좌표가 들어가지 않아야 한다(export JSON·데이터마트로 그대로 흘러가는 값이다). 신규 라벨 판정은 "그 프레임에 실재하지 않는 라벨"이면 전부 신규여야 한다 — TC-LABEL-120 이 `USE_YN` 축에서 이미 이 원칙("id 붙이면 통과" 우회 차단)을 명시적으로 구현하고 있다.
- **현재 동작(이슈 내용)**: 상한 검증은 **`item.id() == null` 일 때만** 수행되는데, 저장 분기는 `id != null` 이어도 **그 프레임에 없는 id 면 신규 라벨로 만든다.** 두 판정 기준이 어긋나 상한 검증이 전혀 걸리지 않는 창이 생긴다.
  ```java
  // LabelService.java:288-292  (사전 검증)
  validatePoints(item.lblTypeCd(), item.points(), item.id() == null);
  if (item.id() == null) {                       // ← id 가 있으면 상한 검증 자체를 건너뜀
      validateWithinBounds(item.lblTypeCd(), item.points(), bounds);
  }

  // LabelService.java:331-335  (UPDATE 분기 — idIndex 에 있을 때만)
  if (item.id() != null && idIndex.containsKey(item.id())) {
      if (!pointsEqual(found.getPointCn(), pointsJson)) { validateWithinBounds(...); }
  } else {
      // LabelService.java:349-365 — 신규 생성. validateWithinBounds 호출 없음.
      created = labelRepository.save(LsDataLbl.createManual(srcSn, ..., pointsJson, actorNo));
  }
  ```
  대조 `LabelService.java:685-695`(`isNewLabelAssignment`)는 **같은 상황을 "신규 취급"으로 올바르게 처리**한다 — 즉 USE_YN 축은 막혀 있고 좌표 축만 뚫려 있다(설계 의도가 아니라 누락으로 판단).
  **실측**(WORKER 2001, srcSn 509, 프레임 320x240):
  ```
  PUT /v1/frames/509/labels
    {"items":[{"id":99999999,"lblTypeCd":"BBOX","labelId":1,"label":"bypass-oob",
               "points":[[999999,888888],[999999,888889]]}]}
    → 200 OK   (id 를 빼면 동일 요청이 400 "좌표가 이미지 경계를 벗어났습니다 … 이미지=320x240")
  DB : select lbl_sn, point_cn from ls_data_lbl where lbl_nm='bypass-oob';
       1257 | [[999999.0,888888.0],[999999.0,888889.0]]     ← 경계 밖 좌표가 실제로 적재됨
  ```
- **재현/확인 경로**: 위 curl 2줄(같은 payload 를 `id` 유/무로 각각 전송해 200/400 대조). 사후 정리: 해당 라벨은 full-replace 델타로 삭제됨.
- **영향**: ①확정 정책(★3)이 무력화되어 프레임 밖 좌표가 `LS_DATA_LBL` → 검수 승인 스냅샷(`LS_LABEL_VERSION`) → export JSON → 데이터마트까지 그대로 전파된다(학습데이터 오염, 데이터 정합) ②입력 검증 우회(CWE-20 / CWE-1287 Improper Validation of Specified Type of Input) ③TC-LABEL-113·116 의 회귀 가드가 "지정된 입력에서만" 성립해 방어가 실질적으로 없는 상태를 통과로 보이게 한다.
- **수정 방향(제안)**: 상한 검증의 판정 기준을 **저장 분기와 동일한 술어**로 맞춘다 — 사전 검증 루프에서 `item.id() == null` 대신 `isNewLabelAssignment` 와 같은 축(즉 `item.id() == null || !idIndex.containsKey(item.id())`)을 쓰거나, `else`(신규 생성) 분기 안에서 `validateWithinBounds` 를 호출한다. 후자가 안전하다(사전 루프는 `idIndex` 적재 이전에 돌기 때문). 동일 술어를 `validatePoints(..., enforceMaxPoints)` 에도 적용해야 C-ISSUE-62 가 함께 닫힌다. 회귀 가드로 TC-LABEL-150 신설(본 회차 카탈로그에 추가함).

---

### MEDIUM

### [C-ISSUE-21] TC-LABEL-15 — 좌표 배열 **원소**가 null 이면 2-튜플 경로에서 언박싱 NPE → 500 (1차 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 좌표 **형식 위반은 전부 400(INVALID_INPUT)** 이어야 한다. SKELETON 경로는 같은 상황을 명시적으로 400 으로 막고 있고 그 주석이 계약을 선언한다(*"언박싱 전에 400 으로 fail-secure 거부하여 계약(형식 위반=400)과 일치시킨다"*). CWE-20 입력 방어선 + OWASP A10:2025(fail-secure) 요구.
- **현재 동작(이슈 내용)**: `pair.size() != 2` 만 검사한 뒤 곧바로 `Double → double` 언박싱한다. `pair.get(0)` 이 null 이면 NPE → `GlobalExceptionHandler` catch-all → **500**.

  `backend/src/main/java/kr/co/cudo/authoring/label/service/LabelService.java:719-728`
  ```java
  for (List<Double> pair : points) {
      if (pair == null || pair.size() != 2) {
          throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 [x, y] 형태여야 합니다.");
      }
      double x = pair.get(0);   // ← pair.get(0) == null 이면 언박싱 NPE
      double y = pair.get(1);
  ```
  대조군 `LabelService.java:805-811`(SKELETON)은 동일 상황을 400 으로 거부한다.

  **3차 실동작 재현 (2026-08-04 01:00 KST)**
  ```
  PUT /api/v1/frames/510/labels  (WORKER 2001, 배정 프레임)
  {"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"x","points":[[null,5],[10,10]]}]}
  → HTTP 500 {"success":false,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}

  (대조) points:[null,[10,10]]          → 400 INVALID_INPUT "좌표는 [x, y] 형태여야 합니다."
  (대조) points:[[1,2,3],[4,5,6]]       → 400 INVALID_INPUT (동일 메시지)
  ```
- **재현/확인 경로**
  ```bash
  B=http://localhost:18081/api
  curl -s -X POST $B/v1/dev/tokens -H 'Content-Type: application/json' \
    -d '{"userNo":"2001","role":"WORKER","channel":"INTERNAL"}' -o /tmp/t.json
  T=$(python3 -c 'import json;print(json.load(open("/tmp/t.json"))["data"]["token"])')
  curl -i -X PUT $B/v1/frames/510/labels -H "Authorization: Bearer $T" \
    -H 'Content-Type: application/json' \
    -d '{"items":[{"lblTypeCd":"BBOX","labelId":1,"label":"x","points":[[null,5],[10,10]]}]}'
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR  (기대: 400 INVALID_INPUT)
  ```
- **영향**
  - **계약 위반(CWE-20 / CWE-248)**: 입력 형식 오류가 500 으로 나가 FE 가 "서버 장애"로 오분류 → 무의미한 재시도 유발.
  - **운영 알람 노이즈**: 매 요청마다 ERROR 레벨 스택트레이스가 로그에 남아 알림 대상이 된다.
  - 응답 본문에 스택트레이스는 없어 CWE-209 정보노출은 없음.
  - 유입 경로: FE 캔버스 부분 직렬화 버그, 외부 도구로 만든 라벨 임포트, SAM2/YOLO 결과 후처리 누락 등.
  - **자동테스트 공백**: backend 5,203건 전건 통과인데도 이 경로가 잡히지 않는다(회귀 가드 부재).
- **수정 방향(제안)**: `LabelService.validatePoints` 의 2-튜플 루프에서 `pair.get(0)`/`pair.get(1)` 을 `Double` 로 먼저 받아 null 검사 후 언박싱한다(SKELETON `validateSkeletonPoints:805-811` 과 동일 패턴). 메시지는 SKELETON 과 대칭으로 `"좌표에 null 원소가 있습니다."` 권장. 회귀 테스트는 `LabelControllerTest` 또는 `LabelSaveGuardsIT` 에 BBOX/POLYGON/SEGMENT/TRACK 4형태 × 신규/기존 2경로로 추가. ⚠ **구현은 하지 않음.**

---

### [C-ISSUE-41] TC-LABEL-60(및 68~76 파생) — ai-server 모델 가중치 미탑재로 검출 의존 분기가 여전히 실환경에서 한 번도 실행되지 않는다 (1차 C-ISSUE-44 **미해소 이월**)
- **심각도**: MEDIUM (검증 커버리지 결함 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 온라인 오토라벨의 핵심 위험 구간(좌표 clamp/퇴화 스킵/형식 위반 400, 폴리곤 상한·예산·부분실패·TOCTOU)은 **실제 ai-server 검출 응답 위에서** 판정돼야 한다. 이 프로젝트의 실패 모드는 "코드가 있으니 PASS"이므로, 검출이 0건이면 그 뒤 분기는 반증 시도 자체가 불가능하다. 특히 ai-server 응답 스키마(필드명·좌표 순서·`source`/`mock` 메타)의 **계약 드리프트**는 단위테스트(mock 주입)로는 절대 잡히지 않는다.
- **현재 동작(이슈 내용)**: `docker exec klid-ai-server ls /app/weights` → 0 files. 모든 온라인 오토라벨 호출이 mock 으로 폴백한다.
  ```
  backend  : [Autolabel] mock response — skip detection srcSn=468 source=mock reason=weights_missing
  ai-server: WARNING:app.routers.yolo:[DETECT:yolox][MOCK] returning mock track … reason=weights_missing
  응답     : {"srcSn":468,"detectedCount":0,"savedCount":0,"labels":[]}  message="AI 모델 미로드 — 결과 신뢰 불가"
  ```
  BE 는 mock 을 정상 차단하므로(`AutolabelOnlineService.java:247-252`, TC-67 PASS) **그 뒤에 있는** `normalizeDetections`(`:257`)·빈검출 분기(`:260`)·`polygonAutolabel`(`:268-269`) 전부가 도달 불가 코드가 된다.
- **재현/확인 경로**: `curl -X POST localhost:18081/api/v1/frames/468/autolabel -H "Authorization: Bearer <WORKER>" -d '{}'` → 항상 `detectedCount:0`. `docker logs klid-ai-server | grep weights_missing`.
- **영향**: 기능/검증. TC-LABEL-60·68·69·71~76 **7건이 단위테스트 커버리지만으로 PASS** 판정된다(본 회차 판정에도 그대로 반영). 실 ai-server 응답 계약 회귀 감지력 0.
- **수정 방향(제안)**: ①`docker-compose.local.yml` 에 YOLOX ONNX 가중치 마운트/다운로드 스텝 추가, 또는 ②mock 플래그 없이 **결정적 고정 좌표**를 돌려주는 검증 전용 ai-server 스텁 모드 도입(그래야 BE 의 mock 차단을 통과해 하류 분기를 실제로 태울 수 있다). ⚠ 구현하지 않음.

---

### [C-ISSUE-42] TC-LABEL-60/72 인접 — `findLabelIdByDtctType` 를 검출 건수만큼 반복 호출(N+1) (1차 C-ISSUE-42 **미해소 이월**)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨링 화면 툴바에서 프레임마다 호출되는 대화형 경로다. `rules/performance.md` 는 반복 단건 조회를 금지한다. 검출 N건이어도 매핑 조회는 1회로 끝나야 한다. 이 서비스는 스스로 "**비트랜잭셔널** — AI 블로킹 호출이 control HikariCP 커넥션을 점유하지 않도록"(`AutolabelOnlineService.java:48-50`)이라고 선언하는데, 응답 조립 단계에서 커넥션을 N회 재획득하면 그 의도를 부분 상쇄한다.
- **현재 동작(이슈 내용)**: 1차 지적 이후 코드 변경 없음.
  ```java
  // AutolabelOnlineService.java:425-433  toItems (BBOX)
  for (YoloResponse.Detection d : detections) {
      Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);   // ← 검출 1건마다
  // AutolabelOnlineService.java:336      polygonAutolabel (POLYGON) — 박스마다 동일 호출
      Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);
  ```
  `findLabelIdByDtctType` 는 `@Transactional(readOnly=true)`(`LabelMasterService.java:150-157`)라 호출마다 트랜잭션·커넥션 획득 + `SELECT … WHERE dtct_type_cd=? AND use_yn='Y'` 1회가 발생한다. 캐시 없음. 같은 요청에서 이미 `mappedDetectClasses()`(`:460`)로 **전체 매핑을 읽었는데도** 재사용하지 않는다.
- **재현/확인 경로**: 가중치 탑재된 ai-server 로 `POST /v1/frames/{srcSn}/autolabel` 후 `hibernate.SQL` DEBUG 로 `ls_label` 조회 횟수 = 검출 건수인지 확인. 현 스택은 검출 0건이라 실측 불가(C-ISSUE-41 종속, 정적 판정).
- **영향**: 성능/자원(커넥션 풀). BBOX 는 검출 수 상한이 없어 혼잡 프레임이면 수십~수백 회, POLYGON 은 `maxBoxes`(최대 100)까지.
- **수정 방향(제안)**: `mappedDetectClasses()` 를 `Map<String,Long>`(COCO코드→labelId) 반환으로 확장하거나 `LsLabelRepository.findByDtctTypeCdInAndUseYn(...)` 로 1회 일괄 조회 후 루프에서 맵 조회. ⚠ 구현하지 않음.

---

### [C-ISSUE-67] TC-LABEL-129 인접 — `'F'` 인데 작업락이 없는 영상(비식별 **실패**분)이 신고를 받아 영구 잠금될 수 있다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `requireDeidentAttempted` 의 설계 근거는 *"이미 `'F'` 인 영상은 통과해 기존 409(재비식별 진행 중) 경로 유지"* 다(`DeidentReportService.java:351-355` javadoc, 카탈로그 TC-129 기대결과). 즉 `'F'` 통과는 **"이미 신고돼 잠겨 있으니 409 가 받는다"** 를 전제로 한다.
- **현재 동작(이슈 내용)**: `'F'` 는 의미가 둘인데(①신고 — 락 있음·산출물 있음 ②**비식별 API 실패** — 락 없음·산출물 없음, `CLAUDE.md` 가 명시), 신고 접수 경로는 **락 존재 여부만** 보고 산출물 실재를 보지 않는다.
  ```java
  // DeidentReportService.java:190  (판정은 'Y'|'F' 통과)
  requireDeidentAttempted(raw);
  // DeidentReportService.java:193-195
  if (workLockService.isRawLocked(rawSn)) { throw CONFLICT("이미 비식별 재처리 중인 영상입니다."); }
  ```
  `'F'` 를 락 없이 만드는 경로가 실재한다 — `KpstDeidentTxService.java:172,:233,:256,:271` 과 `BatchTransitionService.java:223` 의 실패 전이(`markDeidentified("F")`).
  **실측**(rawSn 900 — `de_ident_yn='F'`, `LOCKED` 락 0건):
  ```
  POST /v1/videos/900/deident-report {"reason":"중복 신고"}
    → 201 {"data":34}      (기대 서술대로면 409)
  ```
  접수되면 작업락이 걸리는데, 해소 경로 `verifyDeidentArtifact`(`:570-609`)는 **SUCCEEDED procLog + 실파일 + 신고 이후 갱신**을 요구한다. 비식별이 한 번도 성공하지 않은 영상은 이 조건을 영원히 만족할 수 없어 `resolve` 가 상시 409(`"비식별 산출물이 확인되지 않습니다…"`)로 fail-closed 된다 — 실측으로 같은 409 를 rawSn 115 에서 확인(파일 mtime 갱신 전).
- **재현/확인 경로**:
  ```sql
  -- 비식별 실패 영상 재현: 성공 procLog 가 없고 de_ident_yn='F' 이며 LOCKED 락이 없는 rawSn 선정
  select r.raw_sn from ls_data_raw r
   where r.de_ident_yn='F'
     and not exists (select 1 from ls_auth_work_lock l where l.data_raw_sn=r.raw_sn and l.lck_stts_cd='LOCKED')
     and not exists (select 1 from ls_deident_proc_log p where p.data_raw_sn=r.raw_sn and p.proc_stts_cd='SUCCEEDED');
  ```
  → `POST /v1/videos/{rawSn}/deident-report` 201 → `POST /v1/deident-reports/{rprtSn}/resolve` 항상 409.
- **영향**: 기능/가용성 — 비식별이 **실패**한 영상에 신고가 접수되면 작업락이 걸린 채 해소 수단이 없다(관리자 DB 개입 필요). `LS_DEIDENT_REPORT` 에 해소 불가 OPEN 행이 누적되어 REVIEWER 신고 관리 화면(TC-108/109)의 기본 목록을 오염시킨다. 파생·해상도 게이트의 판정 원천(`hasDeidentArtifact()`)이 "산출물 있음"으로 거짓 응답하는 문제와 같은 뿌리다.
- **수정 방향(제안)**: `requireDeidentAttempted` 를 **플래그 단독 판정에서 산출물 실재 확인으로 승격**한다 — `'F'` 인 경우 `LsDeidentProcLogRepository.findLatestSuccessByDataRawSn` 이 존재하고 경로가 비지 않았을 때만 통과시키고, 아니면 412(신고 대상 아님 — 비식별 실패 재처리 대기)로 거부. 이는 `CLAUDE.md` 가 이미 채택한 "산출물 실재 검증이 fail-closed 로 뒤를 받친다" 패턴(해상도 Phase A/B)을 신고 입구에도 적용하는 것이며, 기존 "신고 중복 → 409" 계약은 그대로 유지된다. ⚠ 카탈로그 TC-129 기대결과에 "락이 있을 때"라는 전제를 명시하는 정정도 함께 필요(본 회차에서는 정정하지 않음 — 정책 확정 대상).

---

### [C-ISSUE-81] TC-SAM2-01 (부수) — `Sam2SegmentService` 의 `@Transactional` 이 여전히 남아 AI 블로킹 호출 내내 DB 커넥션을 점유한다 (1차 C-ISSUE-83 **미해소 이월**)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SAM2 분할은 **DB write 가 없는 stateless 프록시**다(클래스 javadoc `Sam2SegmentService.java:41` — "BE 는 DB 저장하지 않는 stateless 프록시"). 파일 I/O + `ImageIO.read` + base64 + **최대 60s 블로킹 AI 호출** 구간에서 control HikariCP 커넥션을 쥐면 안 된다. 형제 서비스 `Sam2TrackService` 는 이미 이 이유로 트랜잭션을 제거했고 근거를 코드에 남겼다(`Sam2TrackService.java:38-39`: *"비트랜잭셔널(F-1 커넥션풀 고갈 방지) … AI 블로킹 호출 구간이 control HikariCP 커넥션을 점유하지 않는다"*). 프로젝트 규약도 동일하다(`CLAUDE.md` — *"프레임 이미지 서빙은 트랜잭션 밖에서 파일 I/O 를 한다 … 커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아로 간다 — 전례 있음"*).
- **현재 동작(이슈 내용)**: segment 만 트랜잭션이 남아 있고, 1차 지적 이후 코드가 바뀌지 않았다.
  ```java
  // backend/.../label/service/Sam2SegmentService.java:62-66
  @Slf4j
  @Service
  @RequiredArgsConstructor
  @Transactional(value = "controlTransactionManager", readOnly = true)   // ← 여전히 존재
  public class Sam2SegmentService {
      public Sam2SegmentResponse segment(...) {          // :91  프록시 경유 public
          accessGuard.verifyAccess(req.srcSn(), actor);  // :93  DB 접근 → 커넥션 획득
          …
          aiRes = aiServerClient.segment(aiReq).block(); // :130 최대 60s 블로킹
  ```
  **3차 실측(반증 재현)** — segment 6건 동시 호출 중 `pg_stat_activity` 샘플링:
  ```
   state               | count
   active              |     1
   idle                |     4
   idle in transaction |     6     ← 6건 전부 AI 호출 동안 커넥션 점유 (1차와 동일)
  ```
  대조군인 track 은 `idle in transaction` 0건이다(1차 실측, 코드 무변경).
- **재현/확인 경로**:
  ```bash
  W=$(<worker.token)
  for i in 468 469 470 471 472 473; do curl -s -o /dev/null -X POST \
    -H "Authorization: Bearer $W" -H 'Content-Type: application/json' \
    -d "{\"srcSn\":$i,\"points\":[[50,50]]}" \
    localhost:18081/api/v1/frames/$i/sam2-segment & done
  sleep 1.2
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select state, count(*) from pg_stat_activity where datname='klid_system' group by 1;"
  ```
- **영향**: 가용성/성능 — CWE-400 Uncontrolled Resource Consumption · OWASP API4:2023. 운영 풀은 `application-prd.yml` `maximum-pool-size: 20`(control/portal 각각). 라벨링 작업자 20명이 동시에 클릭 분할하면 control 풀이 소진되고, ai-server 가 느려질수록(60s 타임아웃) 점유 시간이 그대로 늘어나 **SAM2 와 무관한 API(라벨 저장·검수·배치)까지 동반 지연/실패**한다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. 클래스 `@Transactional` 제거 → DB 조회(`accessGuard.verifyAccess` + `srcRepository.findById` + 신고게이트 조회)만 별도 `@Transactional(readOnly)` 조회 빈으로 분리하고 **파일 I/O·`ImageIO.read`·base64·AI 호출은 트랜잭션 밖**에서 수행한다. 같은 목적의 선례 `FrameImageLookupService` 패턴을 그대로 따르면 된다. ⚠ 자기호출(self-invocation)로 프록시를 우회하면 효과가 없다. 회귀 가드는 `FrameImageServingHardeningTest` 와 동형으로 "SAM2 분할 빈에 `@Transactional` 이 없다"를 구조 단언으로 고정.

---

### [C-ISSUE-82] TC-SAM2-06 (부수) — `Sam2SegmentService` 클래스 javadoc 과 **공개 Swagger 설명**이 "SAM2 는 원본 이미지에만 실행"이라 실제 동작(비식별 우선)과 정반대다 (1차 C-ISSUE-84 ② **미해소 이월** + 신규 표면 1건)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 2026-07-30 확정 정책 — **라벨링 캔버스는 비식별 프레임을 서빙**하며 온라인(사용자 트리거) SAM2 도 같은 픽셀을 대상으로 해야 좌표가 맞는다. 문서·주석은 이 동작을 그대로 서술해야 한다. 특히 Swagger 설명은 **OpenAPI 로 외부에 나가는 계약 문서**라, 여기에 "원본 이미지에만 실행"이 적혀 있으면 관제/연동 상대가 저작도구가 비식별 전 픽셀을 AI 로 보낸다고 오해한다(개인정보 처리 방침 오기).
- **현재 동작(이슈 내용)**: 1차에서 dead code `resolveSafe` 삭제와 경로가드 javadoc(`:53-56`) 정정은 반영됐으나, **이미지 소스 정책 서술 2곳은 그대로 남았다.**
  ```java
  // Sam2SegmentService.java:43-45  (클래스 javadoc)
  * <p>이미지 소스 정책: CLAUDE.md "오토라벨링" 규칙에 따라 SAM2 는 <b>원본 이미지에만 실행</b>한다.
  * 원본과 비식별본은 동일 해상도이므로 좌표를 공유하며, 별도 비식별 추론은 수행하지 않는다.
  * (기존 {@link Sam2TrackService} 와 동일하게 {@code srcFilePathNm} = 원본 프레임 경로 사용.)

  // LabelController.java:155  (Swagger @Operation description — 신규 발견 표면)
  + "path srcSn 과 body srcSn 불일치 시 400 (CWE-345). 정책상 원본 이미지에만 실행."
  ```
  실제 코드는 정반대다 — `Sam2SegmentService.java:112` → `FrameImageEncoder.resolveFrameImageForInference` → `resolveFrameImageWithoutGate`(`:98-105`)가 **`DE_IDNTF_SRC_FILE_PATH_NM` 을 먼저** 검증·반환하고, 없을 때만 원본으로 폴백한다.
  **실동작 반증(결정적)**: srcSn 45(rawSn 18 파생 프레임)는 `SRC_FILE_PATH_NM` 이 **NULL** 인데 segment 가 **200 + 9점 폴리곤**을 반환했다 — 원본 경로만 쓴다면 물리적으로 불가능하다.
  ```
  psql> SELECT src_sn, src_file_path_nm IS NULL FROM ls_data_src WHERE src_sn=45;  →  45 | t
  curl POST /v1/frames/45/sam2-segment {"srcSn":45,"points":[[50,50]]}
    → 200 {"polygon":[[76,9],…],"score":0.9652,"empty":false}
  ```
- **재현/확인 경로**: `grep -n "원본 이미지에만" backend/src/main/java/kr/co/cudo/authoring/label/service/Sam2SegmentService.java backend/src/main/java/kr/co/cudo/authoring/label/controller/LabelController.java` → 2건. 위 srcSn 45 curl. Swagger 는 `GET /api/v3/api-docs` 의 `/v1/frames/{srcSn}/sam2-segment` description 에서 확인.
- **영향**: 유지보수·감사(주석이 개인정보 처리 동작을 잘못 서술 — 감리 지적 가능) + **외부 계약 문서 오기**(OpenAPI). 런타임 보안 영향은 없다(실동작은 정책 준수 방향). `CLAUDE.md` 가 경고한 *"컨트롤러가 정책 판정을 복제 보유하면 정책 갱신 때 조용히 뒤처진다"* 의 문서판이며, 다음 검증자가 "원본을 쓴다"를 믿고 비식별 서빙 회귀를 놓칠 위험이 있다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① `Sam2SegmentService.java:43-45` 를 "**비식별 우선 해석**(`FrameImageEncoder.resolveFrameImageForInference`) — 캔버스가 보여주는 픽셀과 분할 대상이 같아야 좌표가 맞는다. 원본 폴백은 비식별 경로 부재 시에만"으로 정정. ② `LabelController.java:155` 의 "정책상 원본 이미지에만 실행" 문구를 삭제하고 "비식별 프레임 기준 실행"으로 교체. ③ `CLAUDE.md` "오토라벨링" 절에 **온라인(사용자 트리거) SAM2 = 비식별본 / 배치 YOLO·SAM2 = 원본** 구분을 명시(현재 절은 배치 기준만 서술).

---

### [C-ISSUE-102] TC-TRACK-12~23 — CoordinateTransformer·MaskRleConverter 프로덕션 미사용 (1차 C-ISSUE-101 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md CVAT 포팅 전략에 명시된 "MASK↔RLE↔Polygon 변환"·"좌표 변환/회전 유틸"은 실제 라벨링 파이프라인에서 소비돼야 포팅 목적이 달성된다.
- **현재 동작(이슈 내용)**: 3차 재확인 — `grep -rn "MaskRleConverter" backend/src/main` / `grep -rn "CoordinateTransformer" backend/src/main` 모두 **자기 자신 파일 1건뿐**(호출부 0건). 두 클래스 모두 대응 Test 클래스에서만 참조(`MaskRleConverterTest` 12건, `CoordinateTransformerTest` 10건 — 클래스 자체 품질은 양호하나 소비처가 없음). SAM2 분할 결과는 여전히 `Sam2SegmentService`가 polygon 을 직접 응답하고 RLE 경로를 타지 않으며, 라벨 엔티티에 회전각 필드가 없어 서버측 회전 재계산 지점도 없다.
  - 부수 결함(미해소): `MaskRleConverter.maskToRle`에 `height>0,width==0`(예: `new boolean[3][0]`) 입력 시 `mask.length==0` 가드를 통과한 뒤 `flat=new boolean[0]`에서 `flat[0]` 접근으로 **미제어 ArrayIndexOutOfBoundsException**(다른 가드는 전부 IAE로 통일된 것과 대비). 호출부가 없어 악용 경로는 없음(dead code 한정 결함).
- **재현/확인 경로**: `cd backend/src/main && grep -rn "MaskRleConverter\|CoordinateTransformer" .` → 자기 파일만.
- **영향**: 기능 결함은 아님(호출부 없어 런타임 영향 0). 문서-코드 드리프트(유지보수 혼동) + 향후 실연동 시 width==0 극단값 결함이 활성화될 잠재 리스크.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① RLE 저장·회전이 실제로 불필요하면 두 클래스+테스트를 정리하거나 CLAUDE.md 포팅 전략 표에 "구현만 되고 미연동" 명시. ② 연동 계획이 있다면 `maskToRle`의 `width==0` 가드를 다른 가드와 동일하게 `IllegalArgumentException`으로 통일.

---

### LOW

### [C-ISSUE-23] TC-LABEL-30 — `pointsEqual` 손상 JSON fail-safe 전용 회귀 테스트 부재 (이월, 동작은 정상)
- **심각도**: LOW
- **기대 동작(기대효과)**: `point_cn` 이 파싱 불가한 손상값일 때 `pointsEqual` 이 **'변경됨'(false)** 으로 처리해 이력을 남겨야 한다(이력 유실 방지 fail-safe). 이 동작이 반대로 회귀하면 손상 라벨의 수정 이력·`TASK_MODIFIED` 통지가 조용히 사라진다 — 관제 동기화 요구에 직결된다.
- **현재 동작(이슈 내용)**: 동작 자체는 정상이다. 3차 실동작에서 `point_cn='{not-json'` 라벨을 재저장하니 UPDATED 이력이 before `"{not-json"` 과 함께 기록됐다(`LabelService.java:556-559, :582-584`). 그러나 이 경로를 고정하는 **전용 테스트가 없다**.
  - `LabelServiceFullReplaceIntegrationTest:277` `"손상된_diff_JSON이_있어도_조회는_200과_빈changes를_반환한다"` 는 **`LS_DATA_LBL_HSTRY.CHG_DTL_CN` 조회 폴백** 테스트로, 대상 필드도 코드 경로도 다르다.
  - `LabelServiceFullReplaceIntegrationTest` 의 레거시 포맷 테스트 2건(평탄/객체배열)은 **정상 파싱 성공** 경로만 덮는다.
  - backend 자동테스트 5,203건 전건 통과 상태에서도 이 분기는 미커버.
- **재현/확인 경로**
  ```sql
  UPDATE ls_data_lbl SET point_cn = '{not-json' WHERE lbl_sn = {lblSn};
  ```
  ```bash
  curl -X PUT $B/v1/frames/{srcSn}/labels -H "Authorization: Bearer $T" \
    -H 'Content-Type: application/json' \
    -d '{"items":[{"id":{lblSn},"lblTypeCd":"BBOX","labelId":1,"label":"broken","points":[[1,1],[10,10]]}]}'
  # 기대: LS_DATA_LBL_HSTRY 에 UPDATED 1건 (현재 정상 동작)
  ```
- **영향**: 기능 영향 없음(현재 정상). 회귀 가드 부재로 향후 `normalizePoints` 리팩터링 시 fail-safe 가 조용히 반전될 수 있고, 그 결과는 **이력·관제 통지 유실**이라 사후 발견이 어렵다.
- **수정 방향(제안)**: `LabelServiceFullReplaceIntegrationTest` 에 `"손상된_point_cn_라벨_재저장시_변경됨으로_판정되어_UPDATED_이력이_남는다"` 추가. 대조군으로 `"레거시_평탄포맷은_무변경으로_판정된다"`(이미 존재)를 같은 클래스에 두어 fail-safe 와 흡수 로직의 경계를 고정한다. ⚠ **구현은 하지 않음.**

---

### [C-ISSUE-43] TC-LABEL-100~103 인접 — `resolveManually` 가 미인가자에게 **신고 존재 여부를 403/404 로 흘린다** (같은 서비스의 `reportByVideo` 와 정반대)
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 서비스가 영상 단위 신고 진입점에서는 이 원칙을 **명시적으로 코드 주석에 못박고 지키고 있다** — `DeidentReportService.java:154`: *"인가 — 영상 단위(IDOR, CWE-639). **영상 조회보다 먼저 평가해 미인가자에게 존재 여부를 흘리지 않는다.**"* resolve 도 동일 축(rawSn 기반 `verifyRawAccess`)이므로 같은 규칙이 적용돼야 한다.
- **현재 동작(이슈 내용)**: resolve 는 **조회 → 인가** 순서라 응답 코드가 존재 여부 오라클이 된다.
  ```java
  // DeidentReportService.java:385-389
  LsDeidentReport report = reportRepository.findById(rprtSn)
          .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));  // ← 인가 이전에 404
  accessGuard.verifyRawAccess(report.getRawSn(), actor);                                          // ← 그 다음 403
  ```
  **실측(WORKER 2001 토큰, 본인 배정 아님)**
  ```
  POST /v1/deident-reports/31/resolve        → 403   (신고가 존재함을 알려줌)
  POST /v1/deident-reports/99999999/resolve  → 404   (존재하지 않음을 알려줌)
  대조군 — 영상 단위 신고 진입점(주석대로 동작):
  POST /v1/videos/905/deident-report         → 403
  POST /v1/videos/99999999/deident-report    → 403   (구분 불가 ✅)
  ```
- **재현/확인 경로**: 위 4줄 curl(WORKER 토큰).
- **영향**: 보안(정보 노출) — CWE-209 / OWASP API1:2023. 인증된 WORKER 가 `rprtSn` 을 순회해 **다른 작업자 영상의 비식별 신고 발생 여부·PK 분포**를 열거할 수 있다. 신고 존재 자체가 "그 영상에 개인정보 노출이 있었다"는 민감 신호다. 실질 피해는 낮으나(본문·rawSn 미노출) 같은 파일 안에서 규칙이 갈라져 있는 것 자체가 회귀 위험이다.
- **수정 방향(제안)**: `resolveManually` 도 존재/미존재를 **같은 코드로 수렴**시킨다 — 조회 실패와 인가 실패를 모두 404 로 내거나(리소스 은닉), 최소한 `reportByVideo` 와 동일하게 인가 실패를 403 으로 통일. 어느 쪽을 택하든 **카탈로그 TC-LABEL-101(404)·102(403) 기대값을 함께 갱신**해야 한다(현 카탈로그는 지금 동작을 정본으로 적고 있어 그대로 두면 다음 회차에 "회귀"로 오판된다). ⚠ 구현하지 않음.

---

### [C-ISSUE-44] TC-LABEL-53 인접 — `GET /v1/manage/labels/detect-candidates`(AI 탐지 후보)가 **PORTAL_USER 에게도 열려 있다** + 카탈로그 미수록
- **심각도**: LOW
- **기대 동작(기대효과)**: 포털은 오토라벨링·SAM2·VLM 미제공이 확정 정책이다(ADR-013, CLAUDE.md "포털(외부 채널)"). 이 엔드포인트는 스스로 *"라벨링 화면 'AI 탐지' 팝업이 소비한다"*(`LabelMasterService.java:57`)고 선언하는 **내부 전용** 조회다. 같은 클러스터의 `/v1/frames/{srcSn}/deid-image` 는 정확히 이 이유로 `hasAnyRole('REVIEWER','WORKER')` 로 좁혀 놓았고(TC-LABEL-143 이 그 비대칭을 **의도된 것**으로 명시), 그 판단 기준을 그대로 적용하면 여기도 내부 역할로 좁혀야 한다.
- **현재 동작(이슈 내용)**: `GET /v1/manage/labels/**` 가 통째로 `.authenticated()` 라 나중에 추가된 하위 경로가 자동으로 포털에 노출됐다.
  ```java
  // SecurityConfig.java:126
  .requestMatchers(HttpMethod.GET, "/v1/manage/labels", "/v1/manage/labels/**").authenticated()
  // LabelMasterController.java:69-72  — 메서드 @PreAuthorize 없음
  @GetMapping("/detect-candidates")
  public ApiResponse<List<DetectCandidateResponse>> detectCandidates() { … }
  ```
  **실측**: PORTAL_USER 토큰 `GET /v1/manage/labels/detect-candidates` → **200**(활성 라벨 전체 + COCO 매핑 여부 `mapped`). WORKER → 200(정상).
- **재현/확인 경로**: `curl -s localhost:18081/api/v1/manage/labels/detect-candidates -H "Authorization: Bearer <PORTAL_USER>"` → 200.
- **영향**: 보안(정보 노출, CWE-200) — 외부 채널 사용자가 내부 AI 검출 매핑 구성(어떤 라벨이 어떤 COCO 클래스에 물려 있는지)을 열람한다. PII 는 없으나 내부 파이프라인 구성 노출이며 ADR-013 경계를 침범한다. 부수적으로 **카탈로그 결함**: 이 엔드포인트를 다루는 케이스가 C 클러스터에 한 건도 없다(TC-LABEL-53 은 `list()` 만 다룬다).
- **수정 방향(제안)**: ①`detectCandidates()` 에 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 추가(`/deid-image` 와 동일 패턴), 또는 SecurityConfig 의 GET 예외를 `/v1/manage/labels` 와 `/v1/manage/labels/*/attrs` 로 **좁혀서** 열거. ②카탈로그에 `detect-candidates` 권한 케이스 신설. ⚠ 구현하지 않음(카탈로그 신설은 내 담당 라인범위 밖이라 미반영 — 병합 담당자 처리 필요. **본 병합 세션에서도 시간·범위상 카탈로그 케이스 신설은 수행하지 않았다** — 후속 회차 대상).

---

### [C-ISSUE-45] TC-LABEL-90/91 — Swagger 설명이 **폐기된 "라벨 삭제" 정책을 계속 광고**하고 412 응답을 문서화하지 않는다 (1차 C-ISSUE-123 **미해소 이월** + 신규 하위 결함)
- **심각도**: LOW
- **기대 동작(기대효과)**: 2026-07-27 사용자 확정으로 비식별 신고는 **라벨을 보존**한다(구 "스냅샷 후 전량 삭제" 폐기). 이 API 를 소비하는 FE·관제·외부 통합자가 보는 OpenAPI 설명이 실제 동작과 일치해야 한다(CLAUDE.md 문서 동기화 규칙).
- **현재 동작(이슈 내용)**: 서비스 본체는 정책 반전이 반영됐는데(실동작 실측: 라벨 3건 보존, `LS_LABEL_VERSION` 0건, 로그 `labelsPreserved=true`) **컨트롤러 Swagger 문구는 1차 지적 이후 그대로**다.
  ```java
  // DeidentReportController.java:48   @Tag(description = …)
  "복원 가능 스냅샷 기록 후 삭제한다. 재처리는 외부 솔루션 수동 비식별화 흐름으로 진행되며, "
  // DeidentReportController.java:99   @Operation(description = …)  POST /v1/labels/{srcSn}/deident-report
  "신고 시 해당 영상 전체 라벨을 복원 가능 스냅샷으로 기록 후 삭제하고 영상을 잠근다. "
  ```
  **추가로 발견(신규)**: srcSn 경로의 `@ApiResponses`(`:103-110`)에 **412 가 없다**. 실제로는 파생영상(`requireReportableVideo`)·비식별 미수행(`requireDeidentAttempted`) 두 프리컨디션이 412 를 낸다(TC-LABEL-131). 영상 단위 진입점(`:107-111`) 설명은 최신 정책으로 갱신돼 있어 **같은 컨트롤러 안에서 두 엔드포인트 설명이 서로 모순**된다.
- **재현/확인 경로**: `GET /api/v3/api-docs` 또는 Swagger UI 의 `DeidentReport` 태그 · `POST /v1/labels/{srcSn}/deident-report` 설명.
- **영향**: API 계약 오도. 소비자가 "신고하면 라벨이 사라진다"고 가정해 불필요한 방어 로직을 넣거나, 412 를 처리하지 않아 FE 가 알 수 없는 오류로 처리한다.
- **수정 방향(제안)**: `@Tag(description)`(`:45-49`)과 srcSn 경로 `@Operation(description)`(`:97-102`)을 현행 정책으로 교체(라벨 **보존** + 신고 구간 라벨 조회 412 + resolve 시 자동 해제 + 부수효과 5종), `@ApiResponses` 에 412 추가. 이미 최신인 영상 단위 진입점 문구를 기준으로 정렬하면 된다. ⚠ 구현하지 않음.

---

### [C-ISSUE-62] TC-LABEL-113 인접 — 같은 우회로 `MAX_POINTS_PER_LABEL`(1000) 상한도 무력화된다

- **심각도**: LOW
- **기대 동작(기대효과)**: 신규 라벨의 좌표 개수 상한 1000점은 과대 좌표 DoS(CWE-770) 방어로 명시돼 있다(`LabelService.java:700-717` javadoc). 기존 라벨만 상한 없이 통과시키고 저장 직전 simplify 하는 것이 설계다.
- **현재 동작(이슈 내용)**: `validatePoints(..., item.id() == null)` 이므로 존재하지 않는 `id` 를 붙이면 신규 라벨인데도 `enforceMaxPoints=false` 가 되어 상한 검사를 건너뛴다.
  ```
  PUT /v1/frames/509/labels  {"items":[{"id":99999998, ... ,"points": 1500점}]}   → 200
  PUT /v1/frames/509/labels  {"items":[{           ... ,"points": 1500점}]}       → 400 "라벨당 좌표 개수 초과 (최대 1000 점)"
  ```
- **재현/확인 경로**: 위 2줄 대조.
- **영향**: 데이터 영향은 없다 — 저장 직전 `capPoints`(Douglas-Peucker)가 실제 적재를 줄인다(실측: 1500점 요청 → 저장 29점). 남는 것은 **요청 파싱·직렬화·simplify 연산이 상한 없이 수행**되는 자원 소모 표면뿐이며 `@Size(max=500)` items 상한과 요청 바디 크기 제한이 뒤를 받친다. 그래서 LOW.
- **수정 방향(제안)**: C-ISSUE-61 과 **같은 한 줄**로 닫힌다(판정 술어를 저장 분기와 일치시키면 `enforceMaxPoints` 도 함께 정상화). 별도 수정 불필요.

---

### [C-ISSUE-63] TC-LABEL-143 — 기대결과 "`/image` = 통과"가 성립하지 않음 (1차 C-ISSUE-62 이월 → 본 회차 카탈로그 정정)

- **심각도**: LOW (제품 결함 아님 — 실동작이 기대보다 **더 엄격**)
- **기대 동작(기대효과)**: 카탈로그는 PORTAL_USER 토큰에 대해 `/deid-image`=403 / `/image`=통과 로 "두 형제 경로의 역할 집합이 의도적으로 다름"을 검증하려 한다.
- **현재 동작(이슈 내용)**: 실측 **양쪽 모두 403**(`{"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}`). PORTAL_USER 토큰은 `channel=PORTAL` 이라 `SecurityConfig` 의 내부 채널 격리(`/v1/portal/**` 외 `/v1/**` = `CHANNEL_INTERNAL`)에서 `@PreAuthorize` 도달 **이전에** 차단된다. `FrameImageController.java:80` 의 `hasAnyRole(..., 'PORTAL_USER')` 는 현 배선상 **도달 불가 권한 표기**다. 1차에서 동일 결론이 났으나 카탈로그가 갱신되지 않아 이월됐다.
- **재현/확인 경로**: `POST /v1/dev/tokens {"userNo":"3001","role":"PORTAL_USER","channel":"PORTAL"}` → `GET /v1/frames/508/image` 403 / `GET /v1/frames/508/deid-image` 403.
- **영향**: 보안상 더 강함(영향 없음). 다만 기대값이 틀린 채로 두면 다음 회차에 위양성 FAIL 을 유발하고, 컨트롤러의 죽은 권한 표기가 정책 오독을 부른다.
- **수정 방향(제안)**: ①**카탈로그 기대결과는 본 회차에서 정정 완료**(양쪽 403 + 채널 격리 선행 명시) ②코드 쪽은 `FrameImageController` `/image` 의 `PORTAL_USER` 표기를 제거하거나 "채널 격리로 실효 없음" 주석을 다는 것을 검토(동작 변경 아님). 2회 연속 같은 지적이 반복됐으므로 코드 쪽 정리를 권장.

---

### [C-ISSUE-64] TC-LABEL-148 — 기대결과의 사유 문구가 폐기된 동작을 서술한다 (본 회차 카탈로그 정정)

- **심각도**: LOW (제품 결함 아님 — 문서/코드 드리프트)
- **기대 동작(기대효과)**: 케이스가 검증하려는 것은 `GET /v1/frames/{srcSn}/image` 응답의 `Cache-Control: no-store` 이며, 이는 **성립한다**.
- **현재 동작(이슈 내용)**: 기대결과 문장이 *"이 경로는 비식별 판정 없이 **원본 프레임**을 서빙하므로"* 라고 사유를 적었는데, 2026-07-30 확정 정책(`CLAUDE.md` "라벨링 캔버스는 비식별 프레임을 서빙한다")으로 이 경로는 `serveBySrcSn`→`serveFrame` 공용 판정기를 타 **기본 DEID** 를 서빙하도록 바뀌었다. 실측: WORKER 요청 `Content-Length: 13164`(비식별 파일 크기) — 원본은 9441 이고 REVIEWER `raw=true` 일 때만 9441 이 나온다. 응답 JSON 의 `frameImageType` 도 `DEID`.
- **재현/확인 경로**: `GET /v1/frames/508/image`(WORKER) 와 `GET /v1/videos/115/frames/0/image?raw=true`(REVIEWER) 의 `Content-Length` 대조 + `docker exec klid-backend ls -l` 로 두 파일 크기 확인.
- **영향**: 검증자가 "원본이 서빙되는 경로"로 오독해 다음 회차에 결함으로 오분류할 위험(이 저장소의 "폐기된 동작을 계속 검증 대상으로 들고 있는" 반복 패턴).
- **수정 방향(제안)**: **본 회차에서 카탈로그 사유 문구 정정 완료.** 코드 수정 불필요.

---

### [C-ISSUE-65] TC-LABEL-106~134 — 근거 `file:line` 드리프트 15건 (본 회차 카탈로그 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 file:line 이 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: V163(영상 개인정보 메타) 도입으로 `DeidentReportService` 가 +20~30줄 밀리면서 신고/해소 계열 근거가 일제히 어긋났다(1차에서는 "TC-106~140 근거 전건 유효"였으므로 **본 회차 신규 드리프트**).
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 106 | `:374-391` | `:402-419` |
  | 107 | `:384-391` | `:412-419` |
  | 108 | `:426-438` | `:454-466` |
  | 109 | `:427-429` | `:455-457` |
  | 121·126 | `LabelAccessGuard.java:135-141` | `:139-146` |
  | 124 | `:197-205` | `:201-209` |
  | 125 | `:218-226` | `:222-253` |
  | 126 | `:387-391` | `:415-419` |
  | 127 | `Controller:122-131` · `Service:147-155` | `Controller:138-146` · `Service:151-159` |
  | 128 | `:183, :295-306` | `:187, :323-334` |
  | 129 | `:186, :329-337` | `:190, :357-365` |
  | 130 | `:151` | `:155` |
  | 131 | `:125, :169-186` | `:126, :173-190` |
  | 132 | `:487-495` | `:515-523` |
  | 133 | `:492-494` | `:520-522` |
  | 134 | `:248, :395` | `:275, :423` |
  | 111 | `LabelBulkUpsertRequest.java:26-32` | `:24-31` |
  | 135 | `AutolabelOnlineService.java:411-419` | `:414-419` |
  (TC-110·112~120·122·123·136~149 의 근거는 전건 유효.)
- **재현/확인 경로**: 해당 파일 Read 대조.
- **영향**: 검증 비용 증가·오판 위험.
- **수정 방향(제안)**: **본 회차에서 카탈로그 갱신 완료.**

---

### [C-ISSUE-66] TC-LABEL-125 인접 — 신고 감사 로그의 `privacyReset` 이 "실제로 지워진 프레임 수"가 아니다 (1차 C-ISSUE-64 미해소 이월)

- **심각도**: LOW
- **기대 동작(기대효과)**: 개인정보 표기를 되돌리는 행위의 감사 로그는 **실제 변경 건수**를 말해야 한다(OWASP A09). 행 단위 감사(`privacyResetAudited`)와 집계 로그가 다른 수를 말하면 운영자가 어느 쪽을 믿을지 알 수 없다.
- **현재 동작(이슈 내용)**: `srcRepository.resetPrivacyMetaByRawSn(rawSn)`(`DeidentReportService.java:223`)은 `where s.rawSn = :rawSn` 만으로 전 프레임을 무조건 update 하므로 반환값이 곧 "프레임 총수"다.
  ```
  [DeidentReport] created rprtSn=32 rawSn=115 ... privacyReset=6 privacyResetAudited=2 videoPrivacyReset=true
  ```
  실제 3필드 값을 보유한 프레임은 2건(508·509)인데 `privacyReset=6`(rawSn 115 의 전체 프레임 수)이다. 1차 실측(`privacyReset=5 / audited=0~2`)과 동일 양상 — **미해소**.
- **재현/확인 경로**: 개인정보 3필드가 전부 NULL 인 영상에 신고 접수 → 로그의 `privacyReset` 이 프레임 수와 같음을 확인. 실측 대조군: rawSn 906 `privacyReset=2 privacyResetAudited=0`.
- **영향**: 감사 로그 오독(실제 변경이 없었는데 "6건 리셋"으로 읽힘). 데이터 정합·보안 자체에는 영향 없음(행 단위 감사 `LS_DATA_LBL_HSTRY` 는 정확하다).
- **수정 방향(제안)**: 로그 필드명을 `privacyResetScanned`(또는 `frameCount`)로 바꾸거나, 값을 `privacyResetSrcSns.size()`(=실제 변경분)로 통일. 벌크 update 자체는 멱등이라 조건 추가는 불필요.

---

### [C-ISSUE-83] TC-SAM2-09 (부수) — mock 안내 메시지가 사유와 무관하게 "AI 모델 미로드"로 고정돼 오진단을 유도한다 (1차 C-ISSUE-86 **미해소 이월**)

- **심각도**: LOW
- **기대 동작(기대효과)**: 사용자·운영자에게 나가는 안내는 실제 사유와 일치해야 한다. ai-server 는 사유를 4종으로 구분해 내려준다(`mock_reason` ∈ `env_mock` | `weights_missing` | `load_failed` | `empty_mask`)이며 BE DTO 도 이미 파싱한다(`Sam2Response.java:23`, `Sam2TrackResponse.java:31`).
- **현재 동작(이슈 내용)**: BE 는 사유를 무시하고 단일 문자열을 세팅한다.
  ```java
  // label/dto/Sam2SegmentResponse.java:25
  public static final String MOCK_UNAVAILABLE_MESSAGE = "AI 모델 미로드 — 결과 신뢰 불가";
  // LabelController.java:179-181 — res.isEmpty() 이면 무조건 위 문자열
  // label/dto/Sam2TrackOutcome.java:20 — track 도 같은 상수를 재사용
  ```
  **3차 실측(1차보다 강한 증거)**: 이번 스택은 SAM2 가 **HF 캐시에서 정상 로드**돼 있다(같은 프레임에 정상 프롬프트를 주면 `score 0.9652` 실결과 반환). 그런데 마스크가 안 잡히는 프롬프트를 주면:
  ```
  POST /v1/frames/468/sam2-segment {"srcSn":468,"box":[100,100,10,10]}
  → 200 {"polygon":[],"score":0.0,"empty":true}  message:"AI 모델 미로드 — 결과 신뢰 불가"
  ```
  ai-server 실제 사유는 `empty_mask`(`ai-server/app/routers/sam2.py:328-331`)이고 **모델은 로드돼 있다.** 실제 의미는 "프롬프트 위치에 객체가 없음"이다. track 도 동일(`_prev_polygon_fallback` reason=`empty_mask` → 같은 문구).
- **재현/확인 경로**: 위 curl 2줄. 대조로 `{"points":[[50,50]]}` 을 주면 같은 프레임에서 실결과 200 이 나온다(= 모델은 살아 있음).
- **영향**: 운영/UX. 작업자가 "서버 장애"로 오인해 불필요한 에스컬레이션을 하거나, 반대로 진짜 `weights_missing`(현재 YOLO 가 그 상태다) 상황이 "늘 뜨는 메시지"로 묻힌다. 자동적용 차단 동작 자체는 정상이므로 데이터 오염 위험은 없다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `mockReason` 을 사유별 메시지로 매핑 — `empty_mask` → "선택 지점에서 객체를 찾지 못했습니다 — 다른 위치를 시도해 주세요", `weights_missing`/`load_failed`/`env_mock` → 현행 문구. **빈 폴리곤(자동적용 차단) 동작은 두 경우 모두 유지**한다. 매핑은 segment·track 이 공유하도록 `Sam2SegmentResponse`/`Sam2TrackOutcome` 상수 대신 공용 헬퍼로 뺀다. FE(`OverlayLayer.onMockWarning`·`useSam2Track`)는 `message` 를 그대로 표시하므로 BE 만 고치면 된다.

---

### [C-ISSUE-84] TC-SAM2-11 / TC-SAM2-14 — SAM2 **track** 응답의 `score` 가 클램프·반올림 없이 원본 그대로 나간다 (1차 C-ISSUE-82 ③ **잔여**)

- **심각도**: LOW
- **기대 동작(기대효과)**: "외부 응답 불신" 원칙상 신뢰도도 계약 범위 [0,1] 로 강제돼야 한다. segment 는 이미 그렇게 한다 — `Sam2SegmentService.clampScore`(`:242-246`)가 `NaN→0.0`, `[0,1]` 클램프, 4자리 반올림을 적용한다. FE 는 이 값으로 저신뢰 분기(`SAM_LOW_CONFIDENCE_THRESHOLD`)를 태운다.
- **현재 동작(이슈 내용)**: track 은 ai 원본값을 그대로 싣는다.
  ```java
  // Sam2TrackService.java:160-166 — clampScore 호출 없음
  tracked.add(new Sam2TrackResponseDto.TrackedItem(
          nextSrcSn, aiRes.trackId(), req.label(), bbox, aiRes.score(), …));
  ```
  **실측**:
  ```
  POST /v1/frames/468/sam2-track … → 200
  {"tracked":[{"srcSn":469,…,"score":0.9834924936294556,"shapeType":"POLYGON"}, …]}
  ```
  segment 는 같은 스택에서 `"score":0.9652`(4자리)로 나간다. ai-server 가 계약 밖 값(음수·>1·NaN)을 주면 track 만 그대로 통과한다.
- **재현/확인 경로**: 위 두 엔드포인트 응답의 `score` 자릿수 비교. 계약 위반값 재현은 ai-server 스텁 필요(단위 레벨).
- **영향**: 기능/UX(FE 저신뢰 임계 판정의 입력이 계약 밖 값일 수 있음) + 표현 비일관. 보안 영향 없음. ⚠ **이미지 경계 상한 미적용**은 별개 축으로, 카탈로그 TC-SAM2-11 이 "track·오토라벨은 **의도적** 미적용"으로 고정하고 있으므로 본 이슈에 포함하지 않는다(다만 그 비고의 교차참조 번호가 틀려 정정함 — 본문 §3).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `clampScore` 를 `Sam2SegmentService` 의 private 에서 공용 유틸(`Sam2CoordinateValidator` 또는 신설 `Sam2ScorePolicy`)로 올려 track 의 두 `tracked.add` 지점에 적용. 단위테스트로 `NaN`·`-0.1`·`1.5` 3케이스 고정.

---

### [C-ISSUE-85] TC-SAM2-31 (부수) — 포털 SAM2 제거 후 `encodeDeidentifiedFrameForInference` 가 **프로덕션 호출부 0건 dead code** 이고, 클래스 javadoc 이 폐지된 포털 SAM2 를 여전히 소비자로 서술한다

- **심각도**: LOW
- **기대 동작(기대효과)**: "단일 진입점" 클래스의 javadoc 은 **현재 실제 소비자**를 정확히 나열해야 한다. 이 클래스는 그 정확성 자체가 보안 통제(다음 사람이 "여기만 보면 된다"고 믿는 근거)이며, 클래스 스스로도 그렇게 경고한다(`:47-48`: *"**\"모든 전송이 이 클래스를 통과한다\"고 쓰지 말 것** — 사실이 아니고, 다음 사람이 그 문장을 믿고 검사를 생략한다"*).
- **현재 동작(이슈 내용)**: 포털 SAM2 컨트롤러·서비스가 제거됐는데(회귀 가드 `portal/PortalSam2RemovedTest.java` — "포털(외부 채널) SAM2 도구 **완전 제거**") javadoc 과 메서드는 남았다.
  ```java
  // FrameImageEncoder.java:34-36
  * <b>사용자 요청으로 실행되는</b> 프레임 전송 경로는 예외 없이 {@link #resolveFrameImageForInference}
  * / {@link #encodeFrame} / {@link #encodeDeidentifiedFrameForInference} 를 거친다 — 내부 채널의
  * SAM2 분할·SAM2 추적·YOLO 추적·온라인 오토라벨, 외부 채널의 포털 SAM2 분할/추적이 전부 해당한다.
  //                                              ^^^^^^^^^^^^^^^^^^^^^^ 이미 제거된 경로
  // :138-148
  * <b>비식별본 전용</b> 외부 추론 전송 진입점 … <p>포털(외부 채널)용이다.
  public String encodeDeidentifiedFrameForInference(LsDataSrc frame) { … }
  ```
  호출부 실측:
  ```
  $ grep -rn "encodeDeidentifiedFrameForInference" backend/src/main backend/src/test
  main/.../FrameImageEncoder.java:35   (javadoc)
  main/.../FrameImageEncoder.java:148  (정의)
  test/.../FrameImageEncoderTest.java:103  (테스트만)
  ```
  → **프로덕션 호출부 0건.**
- **재현/확인 경로**: 위 grep. 포털 SAM2 부재는 `PortalSam2RemovedTest` + `grep -rn "sam2" frontend/src/features/portal` 로 교차 확인.
- **영향**: 유지보수/검증 신뢰도. TC-SAM2-31 의 구조 단언("게이트 없는 base64 오버로드 부재")은 여전히 성립하나, **public 이면서 호출부 없는 진입점**은 향후 누군가 "포털용이 있네" 하며 다시 외부 채널을 여는 진입 유혹이 된다(이 클래스가 이미 겪은 사고 유형이다). 런타임 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. ① 메서드를 제거하거나(테스트 동반 삭제) 최소한 package-private 로 좁히고 `@Deprecated` + "현재 소비자 없음" 명시 ② `:34-36` 의 "외부 채널의 포털 SAM2 분할/추적" 문구 삭제 ③ 판단 근거를 남길 것 — 포털이 다시 SAM2 를 갖게 되는 일은 ADR-013 상 없다.

---

### [C-ISSUE-86] TC-SAM2-32 — `FrameImageEncoder` javadoc 의 "유일한 **패키지 외부** 소비자" 서술이 사실과 다르다 (1차 C-ISSUE-84 ③ **미해소 이월**, 카탈로그 측은 이번에 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: `resolveFrameImageWithoutGate` 를 package-private 으로 좁힌 이유가 주석에 정확히 남아야 한다. 이 케이스의 단언은 "**패키지 밖 소비자가 0건**"이며, 그래야 게이트 없는 해석기가 외부로 새지 않는다는 보장이 성립한다.
- **현재 동작(이슈 내용)**:
  ```java
  // FrameImageEncoder.java:86-88
  * <p>현재 유일한 패키지 외부 소비자는 {@code FrameBoundsResolver} — 라벨 좌표 정규화를 위해
  * 이미지 <b>치수만</b> 읽고 픽셀을 밖으로 내보내지 않으므로 게이트 대상이 아니다.
  ```
  실제로는 두 클래스가 같은 패키지다:
  ```
  FrameImageEncoder.java:1    package kr.co.cudo.authoring.label.service;
  FrameBoundsResolver.java:1  package kr.co.cudo.authoring.label.service;
  ```
  package-private 메서드는 애초에 패키지 밖에서 호출될 수 없으므로 "패키지 외부 소비자"라는 표현 자체가 성립하지 않는다.
- **재현/확인 경로**: `head -1 backend/src/main/java/kr/co/cudo/authoring/label/service/{FrameImageEncoder,FrameBoundsResolver}.java`
- **영향**: 유지보수/검증 신뢰도(LOW). 실제 접근제어는 정상이며 런타임 영향 없음. 다만 이 문장을 근거로 "패키지 밖에도 소비자가 있다 → 그러면 public 으로 올려도 되겠다"는 오판이 나올 수 있다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. "현재 유일한 소비자는 **동일 패키지의** `FrameBoundsResolver` 이며 **패키지 밖 소비자는 0건**이다"로 정정. 구조 회귀 가드로 "이 메서드에 public 접근제어자가 없다"를 ArchUnit 류로 고정하는 것도 검토.

---

### [C-ISSUE-87] TC-SAM2-06 (부수) — `Sam2SegmentService` 에 미사용 `@Value` 필드 `storageRawPath` 잔존 (1차 C-ISSUE-84 ① **부분 미해소**)

- **심각도**: LOW
- **기대 동작(기대효과)**: 1차 지적의 취지는 "이 서비스는 경로를 스스로 조립하지 않는다"를 코드로도 참으로 만드는 것이었다. dead 메서드 `resolveSafe` 는 삭제됐으나 그 메서드가 쓰던 설정 주입 필드가 남아, 여전히 "이 클래스가 스토리지 base 를 안다"는 신호를 준다.
- **현재 동작(이슈 내용)**:
  ```java
  // Sam2SegmentService.java:84-85
  @Value("${authoring.storage.raw-path:./storage/raw}")
  private String storageRawPath;      // ← 클래스 내 참조 0건
  ```
  ```
  $ grep -n "storageRawPath" .../Sam2SegmentService.java
  85:    private String storageRawPath;      ← 선언 1건뿐
  ```
- **재현/확인 경로**: 위 grep.
- **영향**: 코드 위생/유지보수(LOW). 런타임 영향 없음(빈 필드 주입 1회). 다만 `resolveSafe` 를 다시 만들고 싶어지는 "재료"가 남아 있어 판정 지점 이중화(=정책 드리프트) 재발 유혹이 된다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. 필드와 `@Value` 삭제, `import org.springframework.beans.factory.annotation.Value` 정리.

---

### [C-ISSUE-88] TC-SAM2-22 — `Sam2TrackService:101-102` 의 후속 프레임 404 는 **도달 불가**하다 (근거 드리프트 — 카탈로그는 이번에 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그 근거 `file:line` 은 **실제로 실행되는 방어 지점**을 가리켜야 한다. 그래야 다음 수정자가 그 줄을 지웠을 때 회귀를 인지한다.
- **현재 동작(이슈 내용)**: 루프 선두의 인가 검사가 먼저 404 를 낸다.
  ```java
  // Sam2TrackService.java:97-102
  for (Long nextSrcSn : req.nextSrcSns()) {
      accessGuard.verifyAccess(nextSrcSn, actor);            // :99  ← 여기서 404
      LsDataSrc nextSrc = srcRepository.findById(nextSrcSn)
              .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                      "후속 프레임을 찾을 수 없습니다: " + nextSrcSn));   // :102 ← 도달 불가
  ```
  `LabelAccessGuard.verifyAndGet`(`:55`, `:58`)이 존재하지 않는 `srcSn` 에 대해 `"프레임을 찾을 수 없습니다."` 로 먼저 던진다.
  **실측**:
  ```
  POST /v1/frames/468/sam2-track {"nextSrcSns":[999999], …}
  → 404 {"message":"프레임을 찾을 수 없습니다.","errorCode":"NOT_FOUND"}
      (":102" 의 "후속 프레임을 찾을 수 없습니다: 999999" 가 아님)
  ```
- **재현/확인 경로**: 위 curl 의 응답 message 문자열 비교.
- **영향**: 카탈로그 정합성(검증 자체의 신뢰도). 동작은 기대대로 404 이므로 기능·보안 영향 없음. `:101-102` 는 방어심층으로 유지할 가치가 있으나 "여기가 404 의 출처"라는 서술은 틀리다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. **카탈로그는 본 파트에서 이미 정정**(§3-2). 코드 측은 선택 — `:102` 메시지를 가드와 통일하거나, 주석으로 "가드가 먼저 판정하므로 방어심층"임을 명시.

---

### [C-ISSUE-89] TC-SAM2-23/34/36 (구조 관찰) — track 은 **응답 좌표 검증(:128) → mock 판정(:136)** 순서이고 segment 는 **mock 판정(:145) → 응답 검증(:151)** 로 반대다

- **심각도**: LOW (현재 도달 불가 — 예방적 기록)
- **기대 동작(기대효과)**: mock 응답은 "외부가 잘못 준 좌표"가 아니라 "신뢰할 수 없는 모드"이므로, 두 경로 모두 **mock 을 먼저 판정해 우아하게 제외/빈 결과**로 마감하는 편이 일관적이다. 검증을 먼저 두면 mock 응답이 502/400 오류로 나가 "부분 추적 + 안내"라는 설계된 UX 가 깨진다.
- **현재 동작(이슈 내용)**:
  ```java
  // Sam2TrackService.java:127-136
  validateResponsePolygon(aiRes.polygon(), "ai-server polygon");  // :128  502/400
  …
  if (aiRes.untrusted()) { anyMock = true; … continue; }          // :136  mock 제외
  ```
  ```java
  // Sam2SegmentService.java:145-151  — 반대 순서
  if (aiRes.untrusted()) { return Sam2SegmentResponse.empty(); }  // :145
  validatePolygon(aiRes.polygon(), imgWidth, imgHeight);          // :151
  ```
- **재현/확인 경로**: 현재는 **도달 불가**임을 확인했다 — ai-server 의 track mock 두 경로(`_mock_track` `sam2.py:443-455` / `_prev_polygon_fallback` `:351-366`)가 모두 **BE 가 보낸 `prev_polygon` 을 되돌려주고**, 그 값은 요청 축(`@Size(min=3)`) 또는 직전 응답 축(`validateResponseMinPoints`)으로 이미 3점 이상·유한·비음수임이 보장되기 때문이다. 단 `_echo_polygon`(`:99-117`)이 변환 불가 원소를 **버리므로**, ai-server 가 향후 좌표를 가공하면 mock 응답이 2점으로 줄어 **502 로 나갈 수 있다**(설계 의도는 "빈 결과 + 안내").
- **영향**: 현재 0. 계약 드리프트 시 UX 저하(안내 대신 502). 보안·데이터 정합 영향 없음.
- **수정 방향(제안)**: ⚠ 구현하지 않음. track 의 `untrusted()` 판정을 `validateResponsePolygon` **앞으로** 이동해 segment 와 순서를 맞춘다(둘 다 "신뢰 판정 → 내용 검증"). 회귀 가드로 "mock=true + 2점 폴리곤 응답 → 502 가 아니라 프레임 제외 + 안내"를 단위테스트로 고정.

---

### [C-ISSUE-101] TC-KEYPOINT-07/09/11/13 — 자동테스트 공백 (1차 C-ISSUE-85 미해소 이월)
- **심각도**: LOW
- **기대 동작(기대효과)**: SKELETON 은 `LBL_TYPE_CD` 기반 type-route 로 격리된 분기라(`KeypointSerializer` javadoc:14-16) 회귀 테스트로 방어가 고정돼야 한다.
- **현재 동작(이슈 내용)**: 3차 실측 재확인 — 1차(2026-08-01) 지적 이후 코드/테스트 변경 없음.
  - `KeypointSerializerTest` 여전히 4건(`roundTrip`/`toJsonTripletFormat`/`emptyInputs`/`rejectsTwoTuple`) — 비배열(TC-09)·숫자아님(TC-11) 케이스 미추가.
  - `LabelServiceKeypointTest` 10건 중 음수 좌표(TC-07) 케이스 없음.
  - `grep -rn "normalizePoints" backend/src/test` → 0건(TC-13, R7 폴백 회귀 가드 없음).
- **재현/확인 경로**: `grep -n DisplayName backend/src/test/java/kr/co/cudo/authoring/common/util/KeypointSerializerTest.java`(4건) · `.../label/LabelServiceKeypointTest.java`(10건, 음수 케이스 0) · `grep -rn "normalizePoints" backend/src/test`(0건).
- **영향**: 회귀 감지력 저하. 특히 TC-13 무커버 상태에서 `normalizePoints` 폴백이 사라지면 SKELETON 라벨이 매 저장마다 "변경됨"으로 오판정되어 `LS_DATA_LBL_HSTRY`/`TASK_MODIFIED` 통지가 무한 증식할 수 있다(1차 분석과 동일, 운영 영향 조용하고 큼).
- **수정 방향(제안)**: ⚠ 구현하지 않음. `KeypointSerializerTest`에 비배열(`"{}"`)·비숫자(`"[[\"a\",2,1]]"`) 2건, `LabelServiceKeypointTest`에 음수좌표 400 1건, R7 무변경 폴백은 "SKELETON 동일값 재저장 시 이력 0건" 통합테스트 1건 추가.

---

## C클러스터 판정 집계 (병합 담당자 산출 — §7 표기 규칙 준수, 각 파트 원본 표 재합산)

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | 273 | part1 44 + part2 44 + part3 42 + part4 44 + part5 37 + part6 62 |
| PARTIAL | 4 | part1 2(TC-MARK-11·36) + part2 1(TC-LABEL-15) + part3 1(TC-LABEL-60) |
| FAIL | 1 | TC-LABEL-150(신설, C-ISSUE-61 회귀 가드 — 현재 우회 성립 상태로 카탈로그에 직접 기록) |
| BLOCKED | 0 | |
| N/A | 0 | |
| 확인필요 | 0 | |
| **검증대상 소계** | **278** | 273+4+1 |
| 폐기(분모 제외) | 2 | TC-LABEL-70·99 |
| **원시 총 행수** | **280** | `grep -cE '^\| *~*TC-' docs/test-cases/C-marking-labeling.md` 실측(2026-08-04) |

- 3차 회차 신설 케이스 3건: TC-LABEL-150(part4, C-ISSUE-61/62 회귀 가드) · TC-SAM2-36·37(part5, track mock 게이트 회귀 가드).
- 회차 착수 전(1·2차 누적) 원시 행수는 277(=280−3 신설)이었고, 그중 폐기 2건을 제외한 검증대상은 275건 — `docs/test-cases/VERIFY-PROMPT.md`가 명시한 "2026-08-03 최신화 시점 실측 C 275" 와 정합.
- PASS율 = 273/278 = **98.2%**.

## D클러스터

> 심각도순(HIGH→MEDIUM→LOW) 정렬. **CRITICAL 신규 결함 0건.** 신규/이월 이슈 **16건**(HIGH 1 · MEDIUM 9 · LOW 6). 판정 집계·카탈로그 정정 총계는 본 섹션 말미 표 및 `docs/검증결과/2026-08-03/3차/D-result.md` 참조.

> ⚠ **이슈 번호 재사용 주의 (3차 신규채번)** — 아래 `D-ISSUE-01~03`(part1, D-1절)·`D-ISSUE-21~26`(part2, D-5절)·`D-ISSUE-41~42`(part3, D-6/D-7절)·`D-ISSUE-61~65`(part4, D-2/D-3/D-4절)는 **5개 파트가 각자 이번 3차 회차에 독자적으로 새로 매긴 번호**다(파트별 시작번호를 나눠 부여하는 §7 규칙에 따름). **1차·2차 회차 `ISSUES.md`의 동일 번호와는 별개의 이슈**다 — 예컨대 1차의 `D-ISSUE-02`(export FAILED인데 TASK_COMPLETED 발송, CRITICAL)와 1차의 `D-ISSUE-61`(export 실패해도 통지 발송, CRITICAL)은 **모두 해소되어 본 회차엔 존재하지 않으며**, 3차의 `D-ISSUE-02`·`D-ISSUE-61`은 **전혀 다른 신규 결함**에 재사용된 번호다. 번호만으로 회차 간 대조하지 말고 각 블록 제목의 "(N차 이월/신규)" 괄호와 대응 TC-ID로 식별할 것.

### ★★ 이번 회차 최우선 확인사항

1. **[D-ISSUE-21] (HIGH, 신규 — part2/D-5)** — 동시 롤백 시 write skew 로 `LS_LABEL_VERSION` 의 ACTIVE 행이 **2건 잔존**한다(재현 2/2). `VersionService.findActiveForUpdate` 가 프레임 행 락(`lockAndReadLabelVersion`) 취득보다 **먼저** 실행되고 술어가 `activeYn='Y'` 인 행만 잠그므로, 경쟁 트랜잭션이 그 사이 `'N'→'Y'` 로 새로 활성화한 행은 잠금 대상에서 빠진다(CWE-362). `GET /v1/frames/{srcSn}/versions` 가 `isCurrent:true` 를 **2건 동시에** 반환해 검수자가 "현재 버전"을 판별할 수 없게 된다. 라벨 본문 교체 자체는 프레임 행 락으로 직렬화돼 데이터 손상은 없다(TC-DIFF-018/024, 상세는 아래 이슈 블록).
2. **[D-ISSUE-02] (MEDIUM, 신규 — part1/D-1, 원 심각도 표기 그대로 유지)** — `submit`·`startReview` 는 검수 상태전이 5경로(`submit`/`cancelSubmit`/`startReview`/`approve`/`reject`) 중 낙관적 잠금(`@Version`) 경합 시 409 로 응답해야 하는 3경로(`cancelSubmit`/`approve`/`reject`)와 달리 `flush()`+`OptimisticLockingFailureException` catch 배선이 **빠져 있어**, 동시 요청의 패자가 **500**(`ObjectOptimisticLockingFailureException` unhandled)을 받는다. 데이터 정합은 유지되나(상태는 정확히 한 번만 전이) FE 가 "충돌"과 "서버 장애"를 구분할 수 없다(상세는 아래 이슈 블록).
3. **✅ 1차 CRITICAL 2건 해소 확인** — ①1차 `D-ISSUE-02`(TC-REVIEW-011, export FAILED 인데 `TASK_COMPLETED` 발송)와 ②1차 `D-ISSUE-61`(TC-NOTIFY-040/001/041, export 가 FAILED/무예외 실패로 마감돼도 `TASK_COMPLETED`/`TASK_MODIFIED` 발송) **양쪽 모두 실동작으로 해소가 재확인됐다.** `DatasetExportOutcome.notifiable()` 단일 판정(+`null` 도 fail-closed)으로 통일된 결과이며, 3차 실측(rawSn=115 승인 시 `export succeeded`(28.383)→`TASK_COMPLETED sent`(28.386) 순서, rawSn=147 재현 실험에서 `NO_INPUT` 종결 시 통지 0건)으로 재확인됐다. 단 그 복구 경로 중 `NO_INPUT` 종결만은 회수기가 못 집어 통지가 영구 유실되는 **별개의 신규 결함**이 발견됐다(D-ISSUE-41, MEDIUM — "해소"의 반례가 아니라 해소된 설계의 한 구석에 남은 구멍).

---

### HIGH

### [D-ISSUE-21] TC-DIFF-018 / TC-DIFF-024 — 동시 롤백 시 ACTIVE 버전 행이 2건 남아 "현재 버전"이 다중 표시된다 (신규)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `LS_LABEL_VERSION` 은 `(rawSn, srcSn)` 당 **정본 active 1건**이어야 한다. `findActiveForUpdate` 가 List 를 돌려주고 `deactivateOthers` 자기치유가 존재하는 이유가 "부분 유니크가 없어 2건 이상 존재할 수 있으니 정리한다"는 것이고(TC-DIFF-024), 그 정리가 **동시 롤백이라는 바로 그 조건**에서 성립해야 한다. 그렇지 않으면 검수자가 버전 목록에서 어느 버전이 현재 작업본인지 판별할 수 없어 "버전 비교 및 복구" 요구가 성립하지 않는다.
- **현재 동작(이슈 내용)**: active 집합 조회가 **프레임 행 락 취득보다 앞서** 수행돼, 경쟁 트랜잭션이 그 사이 새로 활성화한 행을 목록에 담지 못한다. `activeYn = :activeYn` 술어로 `FOR UPDATE` 를 걸면 **스캔 시점에 `'Y'` 인 행만** 잠기므로, 상대가 `'N'→'Y'` 로 바꿀 행은 애초에 잠금 대상이 아니다(전형적 write skew).
  ```java
  // VersionService.java:448-449  ← 여기서 읽은 목록이 stale 이 된다
  List<LsLabelVersion> activeVersions = labelVersionRepository.findActiveForUpdate(
          raw.getRawSn(), src.getSrcSn(), LsLabelVersion.ACTIVE_YES);
  // VersionService.java:472  ← 실제 직렬화 앵커(프레임 행 락)는 그 '뒤'에 잡힌다
  srcRepository.lockAndReadLabelVersion(src.getSrcSn()) ...
  // VersionService.java:557-563  deactivateOthers 는 위 stale 목록만 순회한다
  ```
  ```java
  // version/repository/LsLabelVersionRepository.java:29-34
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select v from LsLabelVersion v where v.dataRawSn = :rawSn and v.dataSrcSn = :srcSn and v.activeYn = :activeYn")
  List<LsLabelVersion> findActiveForUpdate(...);
  ```
  **실측(2/2 재현)** — active 를 정확히 1건(`lbl_version_sn=26`, v6)으로 정규화한 뒤 서로 다른 해시로 동시 롤백 2건 발사:
  ```
  rb(H3)=200  rb(H4)=200
  select lbl_version_sn,ver_no,actvtn_yn from ls_label_version where data_src_sn=301 and actvtn_yn='Y';
   21|3|Y
   22|4|Y            ← active 2건
  GET /v1/frames/301/versions →
   [('ff050b5',False),('a146e90',False),('a0dcd35',True),('ca03614',True), …]   ← isCurrent 가 2건
  ```
  이때 실제 작업본 라벨은 v4 내용인데 v3 행도 `isCurrent=true` 로 노출된다(활성 포인터와 본문 불일치).
- **재현/확인 경로**:
  ```sql
  update ls_label_version set actvtn_yn='N' where data_src_sn=:srcSn;
  update ls_label_version set actvtn_yn='Y' where lbl_version_sn=:anyOne;
  ```
  ```bash
  ( curl -s -X POST "$API/v1/versions/$H_A/rollback" -H "Authorization: Bearer $REV" \
      -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}" & \
    curl -s -X POST "$API/v1/versions/$H_B/rollback" -H "Authorization: Bearer $REV" \
      -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}" & wait )
  ```
  ```sql
  select lbl_version_sn, ver_no, actvtn_yn from ls_label_version
   where data_src_sn=:srcSn and actvtn_yn='Y';     -- 2건이면 재현
  ```
- **영향**: 데이터 정합/기능(CWE-362 write skew). ①버전 목록(`VersionItem.isCurrent`)이 현재 버전을 2건 이상 표시해 검수자가 롤백 판단 근거를 잃는다 ②활성 행 중 하나가 실제 작업본과 다른 페이로드를 가리켜 "이 버전이 현재"라는 표시 자체가 거짓이 된다 ③같은 패턴이 `commitApproved`(`:288-300` → `saveActiveVersion:1008-1018`)에도 있어 **검수 승인과 롤백이 동시에 일어나도** 동일하게 잉여 active 가 남는다 ④다음 롤백/승인이 우연히 자기치유하기 전까지 상태가 지속된다(자동 회복 시점 보장 없음).
  ※ 라벨 본문 교체 자체는 프레임 행 락으로 직렬화되므로 **본문 유실·부분 적용은 없다**(TC-DIFF-025 는 정상).
- **수정 방향(제안)**: `srcRepository.lockAndReadLabelVersion(srcSn)` 으로 프레임 행 락을 잡은 **직후에 `findActiveForUpdate` 를 다시 실행**해 신선한 active 목록으로 `frameLabelsMatch` 판정과 `deactivateOthers` 를 수행한다(첫 조회는 기존 락 순서 VERSION→SRC 유지를 위해 남겨도 되고, 재조회분은 이미 락을 보유한 행이라 추가 대기가 없다). 대안은 `(DATA_SRC_SN) WHERE ACTVTN_YN='Y'` 부분 유니크 인덱스로 DB 가 불변식을 강제하는 것이나, 기존 잉여 active 데이터 정리가 선행돼야 한다. 회귀 가드: "서로 다른 해시로 동시 롤백 후 active 행 수 = 1" 단언을 `VersionRollbackRestoreIT` 에 추가(⚠ 이 케이스는 현재 **테스트 0건**이다 — `VersionServiceTest` 는 단일 스레드 mock 기반이라 이 조합을 만들 수 없다). ⚠ **구현은 하지 않았다.**

---

### MEDIUM

### [D-ISSUE-01] TC-REVIEW-017 — 승인 스냅샷의 **DTO 구성 단계 실패는 프레임 스킵이 아니라 승인 전체 500 + 롤백** (1차 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 승인은 라벨 본문 산출물의 문제로 막히면 안 된다. `VersionService.snapshotFrameOnApprove` 는 스냅샷 산출 실패를 **해당 프레임만 SKIPPED** 로 처리하고 승인은 성공시키며, `ReviewService` 가 `approved with snapshot skips` WARN(496-501)으로 운영자에게 가시화한다. 이래야 프레임 1건의 손상 데이터가 영상 전체의 검수 종결(=학습데이터 확정·관제 통지)을 막지 않는다.
- **현재 동작(이슈 내용)**: `try` 블록이 **직렬화 호출만** 감싸고 있고, 그 앞의 **DTO 구성이 try 밖**이다.
  ```java
  // VersionService.java:262-283
  private FrameSnapshotOutcome snapshotFrameOnApprove(...) {
      LabelResponse snapshot = LabelResponse.of(frame, siblings, labels, "DEID", null,
              aiInfoBySn, objectMapper);           // ← 264-265: try 밖. 여기서 던지면 승인 전체가 죽는다
      String payload;
      try {
          payload = serializeSnapshotWithSimplification(snapshot, frame.getSrcSn());
      } catch (CustomException e) { ... return SKIPPED; }
        catch (Exception e)       { ... return SKIPPED; }
  ```
  `LabelResponse.of` → `Item.from` → `parsePoints` → `LabelPointSerializer.fromJson`(common/util:  `throw new IllegalArgumentException("좌표 역직렬화 실패", e)`) 경로라, `LS_DATA_LBL.POINT_CN` 이 손상되면 `IllegalArgumentException` 이 `commitApproved` → `approve` 를 그대로 관통해 `GlobalExceptionHandler` 의 unhandled 분기로 떨어진다.
- **재현/확인 경로** (3차 실측 재현):
  ```sql
  UPDATE ls_data_lbl SET point_cn='[[1.0,1.0],[8.0' WHERE lbl_sn=1254;      -- rawSn=110 의 유일 라벨
  UPDATE ls_raw_data_status SET data_stts_cd='IN_REVIEW' WHERE raw_data_id=110;
  ```
  ```
  POST /api/v1/reviews/110/approve  (REVIEWER)
  → 500 {"errorCode":"INTERNAL_ERROR","message":"서버 내부 오류가 발생했습니다."}
  backend log: java.lang.IllegalArgumentException: 좌표 역직렬화 실패
  DB 재조회: ls_raw_data_status=IN_REVIEW(전이 안 됨), ls_label_version(raw 110) 0행 → 전체 롤백
  ```
  좌표를 정상값으로 되돌리면 같은 호출이 200 + 스냅샷 1행 생성으로 성공(대조군 확인).
- **영향**: 기능. 한 프레임의 손상 좌표 1건이 **영상 전체의 검수 종결을 영구 차단**한다(승인 불가 → 버전 스냅샷·export·관제 `TASK_COMPLETED` 통지까지 연쇄 중단). 운영자에겐 원인 불명의 500 만 보인다(클라이언트에 내부 정보는 노출되지 않아 CWE-209 는 아님).
- **수정 방향(제안)**: `LabelResponse.of(...)` 호출을 기존 `try` 블록 안으로 옮겨 같은 `catch (Exception)` 이 `SKIPPED` 를 반환하게 한다(`VersionService.java:264-283`). 회귀 가드로 "손상 `POINT_CN` 프레임이 섞인 영상의 승인은 200 + `skipped>0` + WARN" IT 를 추가한다. ⚠ **구현은 하지 않았다.**

### [D-ISSUE-02] TC-REVIEW-051 / TC-REVIEW-001 / TC-REVIEW-009 — `submit`·`startReview` 는 낙관적 잠금 패자가 409 가 아니라 **500** 이다 (전이 5경로 중 2경로 미배선) (신규)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수 상태 전이는 5경로(`submit`/`cancelSubmit`/`startReview`/`approve`/`reject`) 모두 `LS_RAW_DATA_STATUS.VER` 낙관적 잠금으로 보호된다. 경합에서 진 쪽은 **409 CONFLICT + "이미 …처리됐다"** 를 받아야 한다 — FE 가 "다른 사람이 먼저 처리함 → 새로고침 후 재시도" 를 안내할 수 있어야 하고, 정상적인 동시성 경합이 서버 장애 알림으로 오탐되면 안 된다(api-design.md: 409=충돌).
- **현재 동작(이슈 내용)**: 5경로 중 **3경로만** `flush()` + `OptimisticLockingFailureException` catch 가 배선돼 있다.
  ```java
  // cancelSubmit 431-436 / approve 487-492 / reject 591-596 — 배선 있음
  try { reviewRepository.flush(); }
  catch (OptimisticLockingFailureException e) { throw new CustomException(ErrorCode.CONFLICT, "…"); }

  // submit 394-406 — flush/catch 없음 (transitionTo 후 바로 이벤트로그 save → return)
  // startReview 444-452 — flush/catch 없음
  ```
  그 결과 커밋 시점에 `ObjectOptimisticLockingFailureException` 이 `GlobalExceptionHandler` 의 unhandled 분기로 떨어진다.
- **재현/확인 경로** (3차 실측):
  ```bash
  # rawSn=7 을 ASSIGNED 로 둔 뒤 동일 WORKER 로 submit 2발 동시
  curl -X POST .../v1/reviews/7/submit -H "Authorization: Bearer $WORKER" &
  curl -X POST .../v1/reviews/7/submit -H "Authorization: Bearer $WORKER" &
  → 하나 200 / 하나 500
  # rawSn=7 을 PENDING 으로 둔 뒤 start 2발 동시
  curl -X POST .../v1/reviews/7/start -H "Authorization: Bearer $REVIEWER" &  (×2)
  → 하나 200 / 하나 500
  ```
  ```
  backend log:
  org.hibernate.StaleStateException: Batch update returned unexpected row count from update [0]; expected: 1;
    statement: update LS_RAW_DATA_STATUS set DATA_STTS_CD=?,…,VER=? where RAW_DATA_ID=? and VER=?
  org.springframework.orm.ObjectOptimisticLockingFailureException  ← GlobalExceptionHandler "unhandled exception"
  ```
  대조군: 같은 조건의 `cancel-submit` vs `start` 는 6라운드 전부 `409 / 200` 로 결정적(TC-REVIEW-051).
- **영향**: 기능 + 운영. ①데이터 정합은 유지된다(잠금이 제 역할을 해 상태는 한 번만 전이됨 — 데이터 손상 없음) ②그러나 FE 는 "충돌"과 "서버 장애"를 구분할 수 없어 재시도 안내가 불가능하고 ③정상적인 동시 클릭·더블 서브밋이 500 으로 집계돼 알림/에러버짓을 오염시킨다 ④TC-REVIEW-051 의 "패자 409" 단언이 **경합 쌍이 바뀌면 성립하지 않는다**.
- **수정 방향(제안)**: `ReviewService.submit`(394-406)·`startReview`(444-452)에 나머지 3경로와 **동일한** `flush()` + `OptimisticLockingFailureException → CustomException(CONFLICT)` 블록을 넣는다(메시지는 `cancelSubmit` 의 `"이미 검수가 시작되었거나 상태가 변경되었습니다."` 계열로 통일). 5경로 공통 헬퍼로 추출하면 다음 경로 추가 시 누락이 재발하지 않는다. 회귀 가드는 기존 `ReviewCancelSubmitConcurrencyIT` 옆에 submit/start 동시 경합 IT 를 추가. ⚠ **구현은 하지 않았다.**

### [D-ISSUE-22] TC-DIFF-002 — SKELETON diff 응답이 가시성 v 를 버리고 shape 타입을 POLYGON 으로 왜곡한다 (1차 D-ISSUE-41 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SKELETON 은 삼중값 `[x,y,v]` 이고 v(가시성)만 바꾸는 편집이 실제 작업 동선이다. `readPoints` 에 삼중값 비교를 넣어 `MODIFIED` 로 감지하도록 만든 이상, 응답 `before`/`after` 에도 그 차이가 드러나야 검수자가 diff 를 근거로 롤백을 판단할 수 있다.
- **현재 동작(이슈 내용)**: 감지는 되지만 렌더링에서 v 가 탈락하고 타입도 왜곡된다.
  ```java
  // version/dto/LabelDiffDto.java:67-75  ShapeDto.fromPoints
  List<Double> flat = new java.util.ArrayList<>(points.size() * 2);
  for (List<Double> pt : points) {
      if (pt.size() >= 2) { flat.add(pt.get(0)); flat.add(pt.get(1)); }   // ← pt.get(2)(v) 유실
  }
  return new ShapeDto("POLYGON", null, null, null, null, flat);            // ← 타입 왜곡
  ```
  **실측**(v3→v4, 첫 키포인트의 v 만 `2→0`):
  `{"type":"MODIFIED","objectId":"1260","before":{"type":"POLYGON","points":[34개]},"after":{"type":"POLYGON","points":[34개]}}`
  → 파이썬 비교 `before == after` → **True**. 반면 롤백 복원 경로는 v 를 정상 보존한다(TC-DIFF-019 PASS) — **비교 화면만 눈이 먼 상태**.
- **재현/확인 경로**:
  ```bash
  # SKELETON 라벨의 키포인트 v 만 바꿔 재승인해 두 버전을 만든 뒤
  curl -s "$API/v1/versions/{H_new}/diff?compareWith={H_old}" -H "Authorization: Bearer $REV"
  # → before.points == after.points, type 은 양쪽 "POLYGON"
  ```
- **영향**: 기능(버전 비교 신뢰성). "변경됐다는데 뭐가 변경됐는지 안 보이는" 상태라 diff 를 근거로 한 복구 판단이 불가능하다. FE 가 `type` 으로 shape 렌더러를 고르면 SKELETON 이 폴리곤으로 잘못 그려진다. 보안 영향 없음.
- **수정 방향(제안)**: `LabelDiffDto.ShapeDto` 에 SKELETON 분기를 추가해 `type="SKELETON"` + 삼중값 보존 표현(3-stride flat 또는 `keypoints:[[x,y,v]…]`)으로 내려보내고 FE `LabelDiff` 타입을 동반 확장한다. 회귀 가드로 "v 만 바뀐 두 버전의 diff 응답에서 `before != after`" 단언 추가.

### [D-ISSUE-23] TC-DIFF-008 — 롤백 복원이 `LS_DATA_LBL.REG_USER_NO` 를 NULL 로 소실시켜 통계가 수동 라벨을 "자동 라벨"로 오분류한다 (1차 D-ISSUE-43 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 롤백은 "그 시점 작업본으로 되돌리는 것"이므로 복원된 라벨의 귀속(누가 만든 라벨인가)이 보존돼야 한다. 이 컬럼은 통계에서 **수동/자동 라벨 판별 프록시**로 쓰여 값이 바뀌면 지표가 틀어진다.
- **현재 동작(이슈 내용)**: 스냅샷 페이로드(`LabelResponse.Item`)에 `regUserNo` 가 없고, 명시 PK 복원 INSERT 도 그 컬럼을 쓰지 않는다.
  ```java
  // batch/repository/LsDataLblRepositoryImpl.java:26-29
  "INSERT INTO LS_DATA_LBL (LBL_SN, SRC_SN, LBL_TYPE_CD, LBL_ID, LBL_NM, POINT_CN, TRCK_ID, REG_DT) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, now()) ON CONFLICT (LBL_SN) DO NOTHING";   // ← REG_USER_NO 없음
  ```
  **실측**(롤백 직전/직후, srcSn=301):
  ```
  before  1260|2001   1261|2001
  after   1260|(null) 1261|(null)     ← TRCK_ID·AI메타는 보존됐는데 작성자만 소실
  ```
  1차 실측(“복원된 900001·900002 전부 NULL”)과 동일 패턴이며, **값을 갖고 있던 라벨까지 NULL 로 덮인다**는 점이 이번에 추가로 확인됐다. 승인 스냅샷 payload 실측에도 `regUserNo` 키 자체가 없다(`id/lblTypeCd/label/labelId/points/autoLblYn/confScore/trackId/lblSrcCd` 만 존재).
- **재현/확인 경로**:
  ```sql
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- 2001
  ```
  ```bash
  curl -s -X POST "$API/v1/versions/{hash}/rollback" -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d "{\"srcSn\":$SRC}"
  ```
  ```sql
  select lbl_sn, reg_user_no from ls_data_lbl where src_sn = :srcSn;   -- NULL
  ```
- **영향**: 데이터 정합/감사. ①라벨 작성자 귀속 소실(원복 수단 없음 — 값이 어디에도 남지 않는다) ②`StatsQueryRepository`(`:150-166`)가 `regUserNo IS NULL` 을 자동 라벨 프록시로 쓰므로 작업자 통계의 자동라벨 비율이 롤백된 프레임만큼 부풀려진다. 롤백을 반복할수록 누적된다.
- **수정 방향(제안)**: 스냅샷 페이로드에 `regUserNo` 를 실어 왕복 복원하는 것이 권장안(AI 메타·`TRCK_ID` 와 동일 처리). 최소한 `INSERT_SQL`/`createRestored` 에 컬럼을 추가한다. 함께 통계의 "regUserNo IS NULL = 자동" 프록시를 `LsDataLblAiInfo` 조인 기반으로 교체하는 것이 근본책. 회귀 가드: `VersionRollbackRestoreIT` 에 "롤백 후 `REG_USER_NO` 보존" 단언 추가.

### [D-ISSUE-24] TC-DIFF-026 — 동일 `version_hash` 다중 매칭 시 `matches.get(0)` 이 비결정적이라 diff 결과가 조용히 뒤바뀐다 (1차 D-ISSUE-27/45 이월 · 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 같은 입력(두 해시)에 대해 diff 는 항상 같은 결과를 돌려주거나, 모호하면 명시적으로 거부해야 한다. `VERSION_HASH` 의 UNIQUE 는 `(DATA_SRC_SN, VERSION_HASH)` 복합이므로 **서로 다른 프레임이 같은 해시를 갖는 것은 정상**이다(라벨 0~1건인 단순 프레임에서 현실적으로 충돌).
- **현재 동작(이슈 내용)**: 전역 해시 조회에 정렬이 없고 첫 행을 그대로 쓴다.
  ```java
  // VersionService.java:1020-1026
  private LsLabelVersion findByHashOrThrow(String versionHash, String notFoundMessage) {
      List<LsLabelVersion> matches = labelVersionRepository.findByVersionHash(versionHash);
      if (matches.isEmpty()) { throw new CustomException(ErrorCode.NOT_FOUND, notFoundMessage); }
      return matches.get(0);      // ← 어느 프레임의 버전인지 비결정
  }
  ```
  ```java
  // version/repository/LsLabelVersionRepository.java:47  (ORDER BY 없음)
  List<LsLabelVersion> findByVersionHash(String versionHash);
  ```
  **실측**: src303 에 H5 와 같은 해시(payload 는 `{"items":[]}`)를 추가 → 동일 diff 요청 5회 모두 `data` 4건(정상). 이어서 `UPDATE` 로 heap 순서를 반전(`ctid (5,3)→(5,7)`, src303 행이 앞으로) 하자 **같은 요청이 3회 모두 `data:[]`(변경 없음)** 을 반환. 오류 없이 결과만 뒤바뀐다.
- **재현/확인 경로**:
  ```sql
  insert into ls_label_version (lbl_version_sn,data_raw_sn,data_src_sn,ver_no,save_reason_cd,actvtn_yn,reg_id,reg_dt,lbl_payload,version_hash)
  values (990103, :rawSn, :otherSrcSn, 92, 'APPROVED','N','1001', now(), '{"items":[]}', '<기존해시>');
  update ls_label_version set reg_id = reg_id where lbl_version_sn = <원본행>;  -- heap 순서 반전
  select ctid, lbl_version_sn, data_src_sn from ls_label_version where version_hash='<기존해시>' order by ctid;
  ```
  ```bash
  curl -s "$API/v1/versions/{H_to}/diff?compareWith={충돌해시}" -H "Authorization: Bearer $REV"   # 결과가 뒤바뀜
  ```
- **영향**: 데이터 정합/기능. ①검수자가 "변경 없음"을 보고 잘못된 승인·복구 판단을 내린다 ②VACUUM·UPDATE·인덱스 스캔 전환으로 재현이 산발적이라 장애 분석이 어렵다 ③`requireFrameScoped`/`accessGuard` 가 **선택된 그 행 기준**으로 평가돼 인가 대상 프레임까지 요청마다 달라진다(권한 검사 자체는 각 행에 대해 정상 수행되므로 인가 우회는 아니다).
- **수정 방향(제안)**: 근본책은 **diff 진입점을 srcSn 스코프로 정렬** — 요청에 `srcSn` 을 받아 `findByDataSrcSnAndVersionHash`(이미 존재, `rollback` 이 사용)로 단건 조회하면 모호성과 D-ISSUE-25 오라클이 함께 사라진다. 하위호환이 필요하면 차선책으로 ①`findByVersionHash` 에 `order by dataSrcSn, labelVersionSn` 부여 ②`matches.size() > 1` 이면 `INVALID_INPUT`(400, "해시가 여러 프레임에 매칭됨 — srcSn 을 지정하세요")로 명시 거부. 다중 매칭 회귀 테스트가 여전히 **0건**이므로 함께 추가한다.

### [D-ISSUE-41] TC-NOTIFY-041 — `NO_INPUT` 종결은 export 행을 남기지 않아 회수기가 못 집고, 디바운스 윈도우는 이미 삭제돼 TASK_MODIFIED 가 **영구 유실**된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: CLAUDE.md 구속 정책 — *"export 가 실패하면 통지를 보류하고 `DatasetExportFailureRecoverer` 가 재산출 성공 후 통지를 재개한다(**통지 유실이 아니라 성공 시점으로 지연**)"*. 즉 보류된 통지는 **반드시 되살아나는 경로**가 있어야 한다. 그래야 관제가 승인 후 수정을 영원히 모르는 상태가 되지 않는다.
- **현재 동작(이슈 내용)**: 보류는 되지만 **되살아나지 않는 종결이 있다**.
  - `DatasetExportTxService.java:101-106` — 프레임 0건(또는 활성 메타 0건)이면 **`LS_DATASET_EXPORT` 행을 만들지 않고** `Optional.empty()` 로 skip → `DatasetExportOutcome.NO_INPUT`.
    ```java
    List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
    if (frames.isEmpty()) {
        log.info("[DatasetExport] no frames — skip export rawSn={}", rawSn);
        return Optional.empty();     // ← FAILED 행도 남기지 않는다
    }
    ```
  - `DatasetExportFailureRecoverer.java:125` — `exportRepository.findRetryableFailedAnchors(...)` 는 **FAILED 행만** 스캔한다. 행이 없으므로 회수 대상이 되지 않는다.
  - `ControlNotifyDebouncer.java:344-351` — 재생성 윈도우는 `exportRunner.runReExportThenNotify`(`@Async`) 에 **위임만 하고 즉시 반환**하므로, 호출부 `claimAndSendIsolated`(303-306)가 예외 없음으로 판단해 `store.complete(acmlSn)` → **윈도우 행 DELETE**. 축적분(변경 프레임·변경종류)도 함께 사라진다.
  - 폴백 큐(`ls_control_notify_fallback`)에도 들어가지 않는다 — 통지 함수(`sendModified`)가 아예 호출되지 않았기 때문.
- **재현/확인 경로** (이번 회차 실측, rawSn=147):
  ```bash
  # 전제: rawSn=147 은 LS_RAW_DATA_STATUS='APPROVED', DE_IDENT_YN='Y', LS_DATA_SRC 0건
  curl -X PUT 'http://localhost:18081/api/v1/videos/147/environment-meta' \
       -H "Authorization: Bearer $REVIEWER_JWT" -H 'Content-Type: application/json' \
       -d '{"weather":"비","timeOfDay":"NGT","season":"WINTER"}'      # 200
  # 60s 디바운스 후 backend 로그
  #   [DatasetExport] async re-export(+notify) starting rawSn=147 forceRegenerate=true
  #   [DatasetExport] no frames — skip export rawSn=147
  #   [DatasetExport] async export not notifiable — notify withheld rawSn=147 outcome=NO_INPUT
  ```
  ```sql
  SELECT count(*) FROM ls_control_notify_fallback WHERE raw_sn=147;  -- 0  (통지 흔적 없음)
  SELECT count(*) FROM ls_dataset_export         WHERE data_raw_sn=147; -- 0 (회수 anchor 없음)
  SELECT count(*) FROM ls_mon_noti_acml          WHERE raw_sn=147;   -- 0  (윈도우 삭제됨)
  ```
  대조군 rawSn=115(프레임 6건)는 같은 조작으로 export v3 SUCCEEDED → `TASK_MODIFIED` 정상 발송(queue_sn 83).
- **영향**: 데이터 정합 — 관제가 승인 후 메타/라벨 수정을 **영원히 인지하지 못한다**(CLAUDE.md *"데이터마트 학습데이터셋의 라벨링 정보 동기화"* 요구 위반). 현재 확인된 트리거는 "프레임 0건 / 활성 동결메타 0건인 APPROVED 영상"이라 발생 폭은 좁지만, `NO_INPUT` 판정은 **활성 메타 부재**로도 진입하므로(동결 스냅샷이 비활성화된 영상) 정상 데이터에서도 도달 가능하다. 보안 영향 없음.
- **수정 방향(제안)**: ⓐ `runReExportThenNotify` 가 **비통지 종결(`notifiable()==false`)일 때 디바운스 윈도우를 되돌리거나**(complete 를 러너 콜백 이후로 이동) ⓑ `NO_INPUT` 도 `LS_DATASET_EXPORT` 에 종결 행(예: `NO_INPUT`/`FAILED`)을 남겨 회수기 스캔 대상이 되게 하거나 ⓒ 최소 조치로 **보류된 통지를 폴백 큐에 `PAYLOAD_REBUILD_REQUIRED` 로 적재**해 재시도 잡이 살리게 한다. ⓐ는 D-ISSUE-61 수정 때 함께 지적된 "러너의 성공/실패를 상위가 알 수 있게 한다"(1차 §3072)와 같은 축이다. **구현은 하지 않는다.**

### [D-ISSUE-42] TC-NOTIFY-051 — 통지 활성인데 인증 토큰이 비면 **WARN 만 내고 무인증 전송**(fail-open), 로컬 실효값이 실제로 빈 토큰
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 관제 inbound SPI 계약(API-251/API-285)은 `x-access-token` 을 요구한다. 통지가 활성(`authoring.control-notify.enabled=true`)인데 토큰이 없으면, 실환경에서 **전 통지가 401 로 거부 → 폴백 큐가 재시도 상한을 소진 → dead-letter 고착 → 관제 동기화 전면 중단**이 확정적으로 발생한다. 보안 통제의 미설정은 fail-closed(기동 차단 또는 최소한 통지 비활성)로 다뤄야 조기에 드러난다.
- **현재 동작(이슈 내용)**: `WebClientConfig.java:104-116`
  ```java
  if (token != null && !token.isBlank()) { ... builder.defaultHeader(CONTROL_NOTIFY_TOKEN_HEADER, token.trim()); }
  else if (enabled) {
      log.warn("[ControlNotify] 통지가 활성화됐으나 인증 토큰(...)이 비어 있습니다 — 관제 SPI 가 {} 를 요구하면 전 통지가 401 로 거부됩니다.", ...);
  }
  return builder.build();   // ← 헤더 없이 그대로 기동·전송
  ```
  이번 회차 실효값 실측: `docker exec klid-backend env | grep CONTROL_NOTIFY` → `CONTROL_NOTIFY_ENABLED=true` · `CONTROL_NOTIFY_URL=...` 뿐이고 **`CONTROL_NOTIFY_TOKEN` 자체가 없다**. 기동 로그 `2026-08-03 23:54:19.600 [main] WARN ... 인증 토큰 ... 비어 있습니다`. 그 상태로 이번 회차 통지 8건이 전부 발송·수락됐다(mock 이 인증을 검사하지 않기 때문).
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep -c CONTROL_NOTIFY_TOKEN     # 0
  docker logs klid-backend | grep '인증 토큰'                      # 기동 WARN 1건
  docker logs klid-mock-server | grep -c 'notify-completed HTTP/1.1" 202'  # 무인증인데 수락됨
  ```
- **영향**: 보안 — 인증 없는 outbound 통지(CWE-306 Missing Authentication / CWE-1188 insecure default). 실질 피해는 stg/prd 배포 시 **통지 전면 401 → dead-letter 고착**(가용성·데이터 정합). 또한 WARN 은 배포 로그에 묻혀 미설정이 무증상으로 지나간다 — 이 저장소가 `QuartzClusteringGuard`·`VlmUrlPolicy`·`GenAiIntegrationWiringGuard` 에서 이미 채택한 "배포 환경 fail-closed 기동 차단" 패턴과 어긋난다.
- **수정 방향(제안)**: `QuartzClusteringGuard` 골격을 재사용해 **stg/prd(및 `ENV` 배포 표식) + `control-notify.enabled=true` + 토큰 공백** 조합이면 `@PostConstruct` 에서 **기동 실패**시킨다. local/dev(목 서버, 인증 미요구)는 현행 WARN 유지. 대안으로 통지만 자동 비활성화(빈 미등록)하되, 그 경우 "통지가 조용히 안 나가는" 상태가 되므로 기동 차단이 더 낫다. 병행으로 `.env.example`·`deploy/onprem/env.template` 에 `CONTROL_NOTIFY_TOKEN` 항목을 명시한다(⚠ 빈값 기입은 `${KEY:default}` 무력화 사고 이력 참조 — 주석으로 필수 표기). **구현은 하지 않는다.**

### [D-ISSUE-62] TC-ASSIGN-006 / TC-ASSIGN-021 — 배정 API가 FK 위반·PK 충돌을 전부 "중복 배정 409"로 오분류 (1차 D-ISSUE-07 / 2차 D-ISSUE-21 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 존재하지 않는 영상(rawSn)에 대한 배정 시도는 404/400 계열이어야 하고, 상태행 PK 충돌(동시 배정 경합)은 실제 원인에 맞는 메시지를 안내해야 한다. 서로 다른 원인을 같은 메시지로 뭉개면 운영자가 실제로 존재하지 않는 "중복 배정"을 찾아 헤맨다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:101-103`의 `catch (DataIntegrityViolationException)`이 UK 충돌·FK 위반·PK 충돌을 구분 없이 삼켜 동일 문구로 409를 낸다.
  ```java
  // AssignmentService.java:101-103
  } catch (DataIntegrityViolationException e) {
      log.warn("[Assignment] duplicate assignment detected workerId={}", req.workerId());
      throw new CustomException(ErrorCode.CONFLICT, "이미 동일 작업자에게 배정된 영상이 있습니다.");
  }
  ```
  `assign()` 진입부(`:76-84`)는 workerId/reviewerId 존재만 검증하고 `rawDataIds` 자체의 존재 여부는 검증하지 않으므로, 존재하지 않는 rawSn을 배정 시도하면 INSERT 시 FK 위반이 나고 위 catch가 이를 "중복 배정"으로 안내한다. `upsertDataStts`(`:663-666`)의 동시 INSERT PK 충돌(TC-ASSIGN-021, 서로 다른 작업자 동시 배정 경합)도 같은 경로로 유입되어 동일하게 오분류된다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:18081/api/v1/assignments -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"workerId":2001,"rawDataIds":[999999]}'
  # → 409 {"errorCode":"CONFLICT","message":"이미 동일 작업자에게 배정된 영상이 있습니다."}
  # DB 확인: select count(*) from ls_data_raw where raw_sn=999999;  -- 0
  ```
- **영향**: 기능/운영. API 계약과 어긋난 에러 메시지(리소스 없음=404 원칙 미준수), 진단 혼선. 데이터 정합은 트랜잭션 롤백으로 보존되어 보안 영향은 없음.
- **수정 방향(제안)**: `assign()` 진입부에 `videoRepository.findAllById(rawDataIds)`로 존재 검증(단일 IN 쿼리)을 선행 추가해 미존재 시 404/400을 먼저 던진다. `catch (DataIntegrityViolationException)`에서 제약명(`uk_...` vs `fk_...` vs `..._pkey`)으로 분기하거나, 상태행 upsert를 조건부 INSERT로 분리해 UK 충돌만 "중복 배정" 문구를 쓰게 한다. ⚠ 구현하지 않음.

### [D-ISSUE-63] TC-ASSIGN-001 / TC-ASSIGN-004 / TC-ASSIGN-005 / TC-ASSIGN-007 / TC-ASSIGN-009 — 배정 대상 사용자의 역할·활성 여부를 검증하지 않는다 (1차 D-ISSUE-06 / 2차 D-ISSUE-22 이월, 미해소)
- **심각도**: MEDIUM (CWE-863 Incorrect Authorization)
- **기대 동작(기대효과)**: `CLAUDE.md` "작업 배정" 규칙 — "REVIEWER가 WORKER에게 배정". `LS_TASK_ASSIGNMENT.TASK_TYPE_CD='LABELER'` 행의 `USER_NO`는 WORKER 역할 + 활성(`USE_YN='Y'`) 사용자여야 하고, `'REVIEWER'` 행은 REVIEWER여야 한다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:76-81`(assign)·`:255-257`(reassign)이 `userRepository.findByUserNo(...)`로 **존재 여부만** 확인한다.
  ```java
  // AssignmentService.java:76-81
  if (userRepository.findByUserNo(req.workerId()).isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "존재하지 않는 작업자입니다.");
  }
  if (req.reviewerId() != null && userRepository.findByUserNo(req.reviewerId()).isEmpty()) { ... }
  ```
  `findByUserNo`는 `USE_YN` 필터도, `LS_USER_ROLE` 조인도 없다. 실제로 작업자 선택 드롭다운을 채우는 `UserRepository.findAllWorkersWithTaskCount()`는 `LsUserRole.roleCd='WORKER' AND u.useYn='Y'`를 이미 걸고 있어(FE 목록은 필터링됨), API는 무검증인 전형적 FE-BE 비대칭이다.
- **재현/확인 경로**: `AssignmentServiceTest.java` 전수 Grep(30개 `@DisplayName` 확인) 결과 역할/활성 검증 테스트 없음(코드 정적 확인만으로 재현 가능 — 1차 회차 실측 재현 기록: REVIEWER 계정을 LABELER로 배정 시 201 성공).
- **영향**: ①PORTAL_USER/REVIEWER에게 배정된 영상은 그 사용자가 내부 워크플로 API를 호출할 수 없어 워크플로가 정체된다(배정 취소 API 부재 — 재배정으로만 회수) ②작업자별 배정 카운트·작업목록·이력에 비-WORKER가 섞여 집계가 오염된다.
- **수정 방향(제안)**: `assign`/`reassign`의 사용자 검증을 역할·활성 확인으로 승격 — `LsUserRoleRepository`로 workerId가 WORKER(+useYn='Y')인지, reviewerId가 REVIEWER인지 검사. 드롭다운 쿼리(`findAllWorkersWithTaskCount`)와 같은 술어를 공유하는 단일 판정 지점으로 추출. ⚠ 구현하지 않음.

---

### LOW

### [D-ISSUE-03] TC-REVIEW-050 — 검수목록 페이징 파라미터의 **하한 미검증**으로 `size=0`·`size=-1`·`page=-1` 이 500 (형제 목록과 비대칭) (신규)
- **심각도**: LOW
- **기대 동작(기대효과)**: 목록 API 의 잘못된 페이징 파라미터는 **400 INVALID_INPUT**(또는 형제 엔드포인트처럼 클램프 후 200)이어야 한다. `.claude/rules/api-design.md` 는 "입력값 검증 실패 = 400" 을 규정하고, 이 컨트롤러는 이미 상한(`size>100`)에 대해서만 400 을 낸다. 같은 축의 하한만 500 이 되면 계약이 갈라진다.
- **현재 동작(이슈 내용)**: 상한 가드만 있고 하한 가드가 없어 `PageRequest.of` 의 `IllegalArgumentException` 이 그대로 새어 나간다.
  ```java
  // ReviewController.java:92-102
  if (size > MAX_PAGE_SIZE) {                       // 상한만 검증
      throw new CustomException(ErrorCode.INVALID_INPUT, "size 한도 초과 (max=" + MAX_PAGE_SIZE + ")");
  }
  Pageable pageable = PageRequest.of(page, size,    // ← size<1 또는 page<0 이면 IllegalArgumentException
          SortAllowlist.resolveLenient(sort, SortAllowlist.REVIEW, DEFAULT_REVIEW_SORT));
  ```
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/reviews?size=0'   -H "Authorization: Bearer $REVIEWER"  # 500
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/reviews?size=-1'  -H "Authorization: Bearer $REVIEWER"  # 500
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/reviews?page=-1'  -H "Authorization: Bearer $REVIEWER"  # 500
  # 형제 목록 — 같은 입력에 200
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/tasks/board?size=0' -H "Authorization: Bearer $REVIEWER"  # 200
  curl -s -o /dev/null -w '%{http_code}\n' 'http://localhost:18081/api/v1/videos?size=0'      -H "Authorization: Bearer $REVIEWER"  # 200
  ```
  로그: `java.lang.IllegalArgumentException: Page size must not be less than one` / `Page index must not be less than zero` → `GlobalExceptionHandler "unhandled exception"`.
- **영향**: 기능/운영(보안 영향 없음 — 응답 본문에 내부 정보가 노출되지 않아 CWE-209 아님, 인증도 선행됨). 다만 **인증된 사용자가 URL 파라미터 하나로 500 을 임의 생성**할 수 있어 에러 로그·알림을 오염시킬 수 있고(CWE-20 입력 검증 부재), FE 가 `size=0` 이 담긴 URL 로 진입하면 검수목록이 통째로 죽는다. ⚠ 이 엔드포인트는 UNCERTAINTIES ★2 에서 "미등록 정렬 키는 200 으로 관용" 정책을 택한 곳이라, **페이징 하한만 500 인 것은 그 정책 취지와도 어긋난다.**
- **수정 방향(제안)**: `ReviewController.list`(92-94)의 가드를 `if (size < 1 || size > MAX_PAGE_SIZE || page < 0) → INVALID_INPUT` 로 넓히거나, 형제 엔드포인트와 동일하게 **클램프 후 200**(`size` 1..100, `page` max(0,page))으로 맞춘다. ★2 의 lenient 취지를 따르면 후자가 일관적이다. 어느 쪽이든 `/v1/tasks/board`·`/v1/videos` 와 정책을 맞추고 회귀 IT(`ListApiBackwardCompatibilityIT`)에 경계값 3건을 추가한다. ⚠ **구현은 하지 않았다.**

### [D-ISSUE-25] TC-DIFF-006 — 인가보다 해시 조회가 선행해 버전 존재 여부 오라클이 성립한다 (1차 D-ISSUE-42 이월 · 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 인가 실패자가 시스템 내부 상태(특정 버전 해시의 존재 여부)를 응답 코드 차이로 알아낼 수 없어야 한다(CWE-209 / OWASP A01:2025).
- **현재 동작(이슈 내용)**: `diff` 는 전역 해시 조회를 먼저 하고 인가를 나중에 한다.
  ```java
  // VersionService.java:338-349
  LsLabelVersion fromVersion = findByHashOrThrow(fromHash, "from 버전을 찾을 수 없습니다."); // ← 404
  LsLabelVersion toVersion   = findByHashOrThrow(toHash,   "to 버전을 찾을 수 없습니다.");
  requireFrameScoped(fromVersion, "from");
  LsDataSrc fromSrc = accessGuard.verifyAndGet(fromVersion.getDataSrcSn(), actor);          // ← 403
  ```
  **실측**(미배정 WORKER=2002 토큰): 존재하는 해시 → **403** `본인에게 배정되지 않은 영상입니다.` / 존재하지 않는 해시 → **404** `from 버전을 찾을 수 없습니다.`
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/{존재해시}/diff?compareWith={존재해시}" -H "Authorization: Bearer $WK_UNASSIGNED"  # 403
  curl -s -o /dev/null -w "%{http_code}\n" "$API/v1/versions/deadbeef/diff?compareWith=deadbeef"     -H "Authorization: Bearer $WK_UNASSIGNED"  # 404
  ```
- **영향**: 정보 노출(CWE-209). 실효 위험은 낮다 — 해시가 SHA-256 이라 무작위 추측이 불가능하고, 판별하려면 이미 페이로드를 알고 있어야 한다. 그럼에도 "특정 라벨 상태가 승인된 적 있는가"를 인가 없이 확인할 수 있는 채널이다.
- **수정 방향(제안)**: 우선순위가 낮아 **현행 유지도 수용 가능**. 정정한다면 `diff` 를 `rollback` 과 같은 **srcSn 스코프 진입점**으로 정렬하는 것이 근본책이며(요청에 `srcSn` → `accessGuard.verifyAndGet` 선행 → `findByDataSrcSnAndVersionHash`), 이는 D-ISSUE-24 도 동시에 해소한다.

### [D-ISSUE-26] TC-DIFF-020 — `isCommittable` 이 프로덕션 호출자 0건인 dead code 로 남아 있다 (1차 D-ISSUE-28 이월 · 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 정책 판정 메서드는 실제 판정 지점에 배선돼 있거나, 배선하지 않기로 했다면 제거돼야 한다. 남아 있으면 "PORTAL 은 이 함수로 차단된다"는 오해를 만들어 실제 차단 지점(`@PreAuthorize`)을 손댈 때 안전망이 있다고 착각하게 된다.
- **현재 동작(이슈 내용)**:
  ```java
  // VersionService.java:1260-1262
  public static boolean isCommittable(TokenClaims actor) {
      return actor != null && actor.channel() != Channel.PORTAL;
  }
  ```
  전 소스 `grep -rn "isCommittable" backend/src frontend/src` 결과: 정의 1건 + **테스트 참조 3건**(`VersionServiceTest.java:313-317`)뿐, 프로덕션 호출자 **0건**. 실제 PORTAL 차단은 `VersionController` 의 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` 가 담당하며 실측 PORTAL 토큰 diff/rollback 모두 **403** `권한이 없습니다.` 로 거부됐다.
- **재현/확인 경로**: `grep -rn "isCommittable" backend/src frontend/src`
- **영향**: 코드 품질(도달 불가 코드) + 테스트가 실제로 보호하지 않는 것을 보호한다고 표시하는 **거짓 커버리지**. 기능·보안 영향은 없다(차단 자체는 성립).
- **수정 방향(제안)**: 둘 중 하나로 확정한다 — ①제거(+ 해당 단위테스트 제거, 채널 차단 검증은 컨트롤러 IT 로 이관) ②`commitApproved`/`rollback` 진입부에 실제 배선하고 PORTAL 요청에 대한 응답 코드를 확정(현행 403 유지 권장). ⚠ 본 검증에서는 구현하지 않는다.

### [D-ISSUE-61] TC-REVIEW-034 — `ReviewStateMachine.verify(null, to)`는 여전히 NPE(500) — 400 미보장 (1차 D-ISSUE-03 이월, 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: 상태머신은 어떤 입력에도 fail-closed 로 동작해 알 수 없는/누락된 출발 상태를 `INVALID_INPUT(400)`으로 거부해야 한다. 카탈로그(TC-REVIEW-034)도 이를 "기대값은 400"으로 명시한다.
- **현재 동작(이슈 내용)**: `ReviewStateMachine.java:43-51`의 `ALLOWED`는 `Map.of(...)`(JDK `ImmutableCollections.MapN`)이다. `:59` `ALLOWED.get(from)`에서 `from=null`이면 내부적으로 키 해시를 계산하려다 `NullPointerException`이 발생하며, `:60-63`의 `allowed == null` 분기(400 처리)에 도달하지 못한다.
  ```java
  // ReviewStateMachine.java:53-64
  public void verify(String from, String to) {
      if (LsRawDataStatus.STTS_APPROVED.equals(from) && !LsRawDataStatus.STTS_PENDING.equals(to)) {
          throw new CustomException(ErrorCode.CONFLICT, ...);
      }
      Set<String> allowed = ALLOWED.get(from);   // from==null → NPE
      if (allowed == null || !allowed.contains(to)) {
          throw new CustomException(ErrorCode.INVALID_INPUT, ...);
      }
  }
  ```
  COMPLETED/PROCESSING(맵 미등록 키)은 `ALLOWED.get(...)`이 정상적으로 `null`을 반환해 400이 되므로 이 두 값은 정상 동작한다 — 오직 `from=null`만 NPE.
- **재현/확인 경로**: HTTP로는 도달 불가(`LS_RAW_DATA_STATUS.DATA_STTS_CD`는 NOT NULL이고 모든 `transitionTo` 호출자가 null을 넣지 않음). 단위 호출 `new ReviewStateMachine().verify(null, "PENDING")`으로만 재현. `ReviewStateMachineTest.java`(전체 8개 테스트 확인) 전수 Grep 결과 null 케이스 테스트 없음.
- **영향**: 현재 실경로 미도달이라 운영 영향 없음(이론적 결함). 향후 상태 컬럼이 nullable로 바뀌거나 이 클래스가 다른 컨텍스트(외부 인입 등)에서 재사용되면 500 + 예외 처리 미흡(OWASP A10:2025)이 표면화된다.
- **수정 방향(제안)**: `verify` 진입부에 `if (from == null) throw new CustomException(INVALID_INPUT, ...)` 가드 추가 + `ReviewStateMachineTest`에 null 케이스 회귀 테스트 신설. ⚠ 구현하지 않음.

### [D-ISSUE-64] TC-ASSIGN-026 — IN_REVIEW 영상 재배정 허용 여부 정책 미확정 (1차 D-ISSUE-05 이월, 확인필요)
- **심각도**: LOW (확인필요 — 의도된 정책일 수 있음)
- **기대 동작(기대효과)**: 카탈로그가 이미 "현행 허용(APPROVED만 차단) — D-ISSUE-05 미해소, 정책 확정 필요"로 명시하고 있어, 이 항목은 결함이 아니라 **정책 확정 대상**이다.
- **현재 동작(이슈 내용)**: `AssignmentService.java:235-240`의 가드는 `STTS_APPROVED` 한 값만 차단한다.
  ```java
  // AssignmentService.java:235-240
  dataSttsRepository.findByRawDataIdForShare(prev.getRawDataId()).ifPresent(stts -> {
      if (LsRawDataStatus.STTS_APPROVED.equals(stts.getDataSttsCd())) {
          throw new CustomException(ErrorCode.ASSIGNMENT_ALREADY_COMPLETED, "완료된 작업은 재배정할 수 없습니다.");
      }
  });
  ```
  IN_REVIEW(검수 진행 중) 영상도 재배정 API가 통과시킨다 — 배정 작업자만 바뀌고 검수 상태(IN_REVIEW)는 그대로 유지된다.
- **재현/확인 경로**: 코드 정적 확인(1차 실측 기록: rawSn이 IN_REVIEW인 상태에서 `PATCH /v1/assignments/{id}` → 200 성공, 상태는 IN_REVIEW 유지, 기존 작업자만 교체됨).
- **영향**: 상태 일관성/운영 혼선(데이터 손상 없음). "검수 중인데 배정 작업자만 바뀐" 상태가 만들어질 수 있음.
- **수정 방향(제안)**: 가드를 `APPROVED` 단일 값에서 검수 진행 상태 집합(`PENDING`/`IN_REVIEW`/`APPROVED`)으로 확장할지, 아니면 현행(검수 중에도 작업자 교체 허용)이 의도된 정책인지 사용자 확정 필요. ⚠ 구현하지 않음.

### [D-ISSUE-65] TC-VERSION-007 — `commitApproved`의 `rawSn==null` 가드가 처리되지 않은 예외로 이어져 500 응답 (2차 D-ISSUE-81 이월, 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: `VersionService.commitApproved(rawSn, actor)`는 `rawSn==null`일 때 의미 있는 4xx로 실패해야 한다. 카탈로그 기대결과("IllegalArgument/UNAUTHORIZED(401)")도 명확한 클라이언트 오류 응답을 전제한다.
- **현재 동작(이슈 내용)**: `VersionService.java:160-162`가 순수 `IllegalArgumentException`을 던지는데, `GlobalExceptionHandler`에는 이를 처리하는 `@ExceptionHandler`가 없다(전수 Grep 재확인: `CustomException`/`MethodArgumentNotValidException`/`ConstraintViolationException`/`HttpMessageNotReadableException`/`MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException`/`DataIntegrityViolationException`/`AccessDeniedException`/`AuthenticationException`/`MaxUploadSizeExceededException`/`NoResourceFoundException`/`HttpRequestMethodNotSupportedException`/`Exception` 뿐). 따라서 제네릭 `@ExceptionHandler(Exception.class)`로 떨어져 500이 된다.
  ```java
  // VersionService.java:160-165
  if (rawSn == null) {
      throw new IllegalArgumentException("rawSn 은 필수입니다.");
  }
  if (actor == null) {
      throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
  }
  ```
- **재현/확인 경로**: `commitApproved`는 REST로 직접 노출되지 않고 `ReviewService.approve()`가 `stts.getRawDataId()`(항상 non-null FK)로만 호출하는 내부 전용 메서드라 현재 HTTP 요청으로는 도달 불가. `VersionServiceTest.java` 전수 Grep 결과 `commitApproved(null, ...)` 직접 호출 테스트 없음.
- **영향**: 기능 영향 낮음(공개 API 경로 도달 불가). 향후 `commitApproved`가 다른 컨텍스트(관리자 수동 재스냅샷 API 등)에서 재사용되면 이 가드가 그대로 500을 낼 잠재 함정. OWASP A10:2025(Mishandling of Exceptional Conditions) 관점에서 "의미 있는 4xx" 원칙과 어긋남.
- **수정 방향(제안)**: `IllegalArgumentException`을 `CustomException(ErrorCode.INVALID_INPUT, ...)`으로 교체(로컬 교체가 전역 핸들러 신설보다 영향범위가 좁아 안전). ⚠ 구현하지 않음.

---

## D클러스터 판정 집계 (병합 담당자 산출 — §7 표기 규칙 준수, 각 파트 원본 표 재합산)

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | 179 | D-1(part1) 47 + D-2/3/4(part4) 40 + D-5(part2) 19 + D-6/7(part3) 50 + D-8(part5) 23 |
| PARTIAL | 13 | D-1 1(TC-REVIEW-017) + D-2/3/4 4(TC-REVIEW-034·TC-ASSIGN-006·TC-ASSIGN-021·TC-VERSION-007) + D-5 5(TC-DIFF-006·008·018·020·024) + D-6/7 3(TC-NOTIFY-028·041·051) + D-8 0 |
| FAIL | 2 | D-5 2건(TC-DIFF-002·026) — 나머지 전 파트 0건 |
| BLOCKED | 0 | |
| N/A | 2 | D-2/3/4(part4) 2건 — TC-VERSION-011/012(2026-07-30 폐기 확정, 신고 스냅샷 경로 제거) |
| 확인필요 | 1 | D-2/3/4(part4) 1건 — TC-ASSIGN-026(IN_REVIEW 재배정 허용 여부, 정책 미확정) |
| **합계** | **197** | 48(D-1)+4(D-2)+26(D-3)+17(D-4)+26(D-5)+45(D-6)+8(D-7)+23(D-8) — `grep -cE '^\| *~*TC-' docs/test-cases/D-review-version-notify.md` 실측(197) 및 카탈로그 헤더 표기("197 케이스")와 정확히 일치 |

- PASS율 = 179/197 = **90.9%**. PASS+PARTIAL(사실상 통과) = 192/197 = **97.5%**.
- **신규 결함**: HIGH 1건(D-ISSUE-21, 동시 롤백 write skew) · **신규 심각도 재산정 없이 원 표기 유지**. 나머지는 1~2차 이월 미해소(D-ISSUE-01·22·23·24·25·26·61·62·63·65) 또는 확인필요(D-ISSUE-64) 또는 이번 회차 처음 드러난 신규(D-ISSUE-02·03·41·42).
- **✅ 1차 CRITICAL 2건 해소 확인**(상세는 위 "★★ 이번 회차 최우선 확인사항" 참조): 1차 `D-ISSUE-02`(export FAILED인데 TASK_COMPLETED 발송) · 1차 `D-ISSUE-61`(export 실패해도 통지 발송) 양쪽 모두 `DatasetExportOutcome.notifiable()` 단일 판정으로 실동작 재확인됨.
- **카탈로그 정정**: `docs/test-cases/D-review-version-notify.md` 담당 라인범위(16~268행) 내 **총 42개 TC 행**을 5개 파트가 나눠 `file:line` 드리프트·기대결과 보강으로 정정(part1 10행·part2 5행·part3 21행·part4 1행·part5 5행, `git diff` 실측 unique TC-ID 42건과 일치). 자기소개 요약치("정정 49건" 등 부분합)와 실제 diff 결과가 다른 경우 **실측 diff(42건)를 정본으로 채택**했다(아래 변경이력 표 정정 참조).
- **폐기(분모 영향 없음)**: TC-VERSION-011/012 는 카탈로그 자체에 `~~취소선~~`+`[폐기 2026-07-30]` 로 이미 표기돼 있으나 ID 열에는 취소선이 없어 `grep -cE '^\| *~*TC-'` 원시 카운트(197)에는 포함된다. part4 가 이를 N/A 로 판정해 분모(197)에는 포함하되 PASS/FAIL 대상에서는 제외했다 — 카탈로그 헤더의 "197 케이스" 표기와 정합.

---

## E클러스터

> 심각도순(HIGH→MEDIUM→LOW) 정렬. 담당: `docs/test-cases/E-augment-resolution-export-meta.md`(E-1~E-9, 7개 파트). 신규 HIGH 3건 · MEDIUM 14건 · LOW 13건 = 총 30건. 판정 자체는 전 파트 FAIL 극소수(E-4/E-5 각 1건)이고 나머지는 카탈로그 정합·미해소 이월·환경 제약 성격이 대부분이다.

### ★★ 이번 회차 최우선 확인사항

1. **[E-ISSUE-61] HIGH·FAIL — 파생 프레임 목적 파일명 충돌(무경고 확정)**: 해상도 파생의 목적 프레임 파일명이 부모 비식별 프레임 경로의 **basename에서 그대로 파생**되어, 부모 프레임들이 서로 다른 디렉터리에 있으면서 파일명이 같으면 목적 경로가 충돌한다. 실측(부모 rawSn=906, 프레임 2건이 `frames/deid/26/frame-0.jpg` · `frames/deid/27/frame-0.jpg`)에서 파생 163/165/98 모두 `LS_DATA_SRC` 2행이 같은 파일 1개를 가리키고 디스크에도 1개만 존재 — 뒤 프레임이 앞 프레임을 **경고 없이 덮어쓰고** 그대로 확정(COMPLETED/ACCEPTED)된다. 결과적으로 **픽셀↔라벨 불일치가 조용히 관제·데이터마트로 유출**될 수 있다(CWE-706). 상세는 아래 HIGH 섹션 참조.
2. **[E-ISSUE-101 / E-ISSUE-121] HIGH·카탈로그 정합 — 개인정보 3필드(anonymity/pseudonymity/privacy_included) 정책반전(V163, 커밋 `0d290c4e`, 2026-08-03)이 카탈로그에 미반영 상태였다.** E-part6(E-7/E-8, TC-EXPORT-022/023/030)과 E-part7(E-9, TC-META-030/034)이 **독립적으로 같은 반전을 발견**해 정합 방향까지 일치하게 정정 완료했다 — `ORIGINAL` 산출물은 3필드 모두 **판정하지 않음(null)**, `DEIDENTIFIED` 산출물만 **수동값 우선**(미입력 시 기본상수 Y/N/N). 방치했다면 **정상 동작 3~6건이 다음 회차에 거짓 FAIL로 재발견**될 뻔했다 — 이번 회차에 정정 완료(§카탈로그 정정 참조).
   - ⚠ 단, 같은 라운드에서 E-part7이 발견한 **[E-ISSUE-122] MEDIUM — 실제 제품 결함**은 카탈로그 문제가 아니라 코드 결함이다: 영상 축(video) 수동값을 입력하지 않으면 기본상수(`privacy_included=N`)가 들어가는데, 프레임 축(image)에는 사람이 실제로 `Y`(개인정보 포함)를 선언한 상시 조합(`video=N`/`image=Y`)이 성립해 **영상 단위로는 "개인정보 없음"으로 과소 신고**될 수 있다. 정책이 스스로 "성립 불가"라 규정한 역방향 조합이 일반 영상에서 상시 재현되므로 그대로 이슈로 유지한다(수정하지 않음, 사용자 확정 필요).
3. **[E-ISSUE-21] MEDIUM — 파생영상 `video.*` 기술메타가 부모와 실제로 다르다(문서 vs 실동작 불일치, 관제 협의 필요).** `CLAUDE.md`("★ 파생영상에는 원본영상이 없다" 절)는 *"video.\* 기술메타는 부모와 동일한 것이 정상… `DerivedMetaCopier`의 부모 값 복사는 결함이 아니다"*라고 명시하지만, 실측 결과 `DerivedMetaCopier`의 복사 직후 `AsyncVideoMetaRunner`가 **파생 비식별 사본을 ffprobe로 재측정해 덮어쓰기** 때문에 최종값은 항상 ffprobe 실측치다(부모의 `video.*`는 `LS_DATA_INGEST` 인입 선언값 우선이라 실제 파일과 다를 수 있음). 실측: 부모 rawSn=101(인입 선언 640x480/30fps/10000ms) vs 파생 158(ffprobe 실측 320x240/10fps/5000ms). **CLAUDE.md는 건드리지 않았다** — 정책을 어느 축(부모 동일 유지 vs 사본 실측 유지)으로 확정할지 관제 협의 및 사용자 결정이 선행되어야 한다.
4. **✅ 1차 CRITICAL 이슈 해소 확인** — ① **1차 D-ISSUE-61(CRITICAL, export가 예외 없이 실패로 마감돼도 TASK_COMPLETED/MODIFIED 발송)**: `DatasetExportOutcome.notifiable()` 단일 판정 + `null` fail-closed로 이번 회차 실동작 3회(NO_INPUT·FAILED-base거부·DEIDENT_BLOCKED) 재확인 — 모두 통지 보류(`notify withheld`) 확인됨. ② **1차 E-ISSUE-81(HIGH, PARTIAL export가 `V_COMPLETED_VIDEO` 뷰에서 배제)**: `pg_get_viewdef` 실측으로 `export_stts_cd = ANY (ARRAY['SUCCEEDED','PARTIAL'])`(V160) 확인 — PARTIAL 산출물도 뷰에 정상 노출되며 `export_stts_cd` 컬럼도 신설되어 관제가 부분산출을 식별 가능. 둘 다 **해소 확정**(E-part5 §2 참조).

---

### HIGH

### [E-ISSUE-61] TC-RESL-041 / TC-RESL-044 — 파생 프레임 목적 파일명이 부모 basename 에서 파생돼 **서로 다른 프레임이 한 파일로 덮어써진다**(무경고 확정)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 부모 프레임 N건 → 파생 프레임 파일도 N건이어야 한다. 파생영상의 유일한 소비자는 관제서버이고(`CLAUDE.md` "파생영상에는 원본영상이 없다"), 관제는 `V_COMPLETED_FRAME`/export 폴더로 프레임 이미지를 픽업한다. 프레임 이미지가 서로 뒤바뀌면 **라벨 좌표는 프레임 A 것인데 픽셀은 프레임 B** 인 학습데이터가 마트로 나간다.
- **현재 동작(이슈 내용)**: 목적 경로가 `frames/deid/{newRawSn}/` + **부모 비식별 프레임의 basename** 으로 조립된다. 부모 프레임들이 서로 다른 디렉터리에 있고 파일명이 같으면 목적 경로가 충돌한다.
  ```java
  // ResolutionSnapshotService.java:205-206
  Path fdst = resolveSafeDir(base,
          StorageSubtreePolicy.deidFramesDir(newRawSn) + "/" + fileNameOf(deidFrameSrc, pf));
  // :231-234
  private static String fileNameOf(String frameSrc, LsDataSrc frame) {
      Path name = Paths.get(frameSrc).getFileName();
      return name != null ? name.toString() : (frame.getFrameNo() + ".jpg");
  }
  ```
  중복 방지는 `seenFrameKeys`(videoFrameNo, `:194-199`)뿐이고 **목적 파일명 중복은 검사하지 않는다**. Phase B 는 `imageResizer.resize(f.deidSrc(), f.dst(), …)` 를 순차 실행하므로 뒤 프레임이 앞 프레임을 덮어쓰고, Phase C 는 그 사실을 모른 채 `LS_DATA_SRC` N행을 모두 같은 경로로 INSERT + `markDeidentified('Y')` + `markCompleted()` + aug `ACCEPTED` 로 **정상 확정**한다. 경고 로그조차 없다(`[B] materialized rawSn=163 frames=2` 는 스펙 개수를 셀 뿐).
- **재현/확인 경로**:
  ```bash
  # 부모 906: 프레임 2건의 deid 경로가 서로 다른 디렉터리 + 동일 basename
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT src_sn, frm_no, de_idntf_src_file_path_nm FROM ls_data_src WHERE raw_sn=906 ORDER BY frm_no;"
  #  464 | 0 | /app/storage/deidentified/frames/deid/26/frame-0.jpg
  #  465 | 1 | /app/storage/deidentified/frames/deid/27/frame-0.jpg

  curl -s -X POST localhost:18081/api/v1/videos/906/resolution -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"presets":["RESL_1080P"]}'   # -> 201 rawSn=165

  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT raw_sn, count(*) rows, count(DISTINCT de_idntf_src_file_path_nm) paths
       FROM ls_data_src WHERE raw_sn IN (98,163,165) GROUP BY 1;"
  #  98|2|1   163|2|1   165|2|1     <-- 2행이 같은 1경로
  docker exec klid-backend ls /app/storage/deidentified/frames/deid/165/
  #  frame-0.jpg                    <-- 파일도 1개뿐 (정상 파생 171 은 frame-0..4 5개)
  ```
- **영향**: 데이터 무결성 — 파생영상의 프레임 픽셀↔라벨 불일치가 **조용히** 관제/데이터마트로 유출. 손실된 프레임의 원본 픽셀은 파생본에 존재하지 않는다(복구 불가, 재생성만 가능). 현재 부모 프레임은 대개 `frames/deid/{parentRawSn}/frame-N.jpg` 단일 디렉터리라 일상 경로에서는 충돌하지 않지만, **재비식별·재추출로 프레임이 다른 디렉터리에 흩어진 부모**(실환경에 이미 존재)에서 발생한다. CWE-706(경로 이름 부적절 해석) 계열.
- **수정 방향(제안)**: 목적 파일명을 **부모 파일명이 아니라 파생 자신의 프레임 키**로 만든다 — 예 `frame-{frameNo}.{ext}` 또는 `{videoFrameNo}.{ext}`(부모 프레임 유일성 검사와 같은 축). 확장자만 소스에서 취한다. 추가로 `buildFrameSpecs` 에 **목적 경로 중복 fail-fast**(`seenFrameKeys` 와 같은 방식으로 `seenDst`)를 넣어 규약 위반이 다시 생겨도 확정 전에 멈추게 한다. 기존 충돌 파생(98·163·165 등)은 재생성 대상 식별 쿼리(`count(*) <> count(DISTINCT de_idntf_src_file_path_nm)`)로 뽑아 별도 정리.


### [E-ISSUE-101] TC-EXPORT-022/023/030 — 카탈로그가 2026-08-03 폐기된 개인정보 3필드 정책을 검증 대상으로 담고 있었다 (정정 완료)
- **심각도**: HIGH (카탈로그 정합 — 다음 회차 거짓 FAIL 위험이었음, 이번 회차에 정정 완료)
- **기대 동작(기대효과)**: 카탈로그는 현재 확정 정책만 검증 대상으로 담아야 한다. 폐기된 정책을 남겨두면 다음 검증자가 "수동값이 override됐다 → 결함"으로 오판할 위험이 있다.
- **현재 동작(이슈 내용)**: 카탈로그(2026-07-30 최신화 기준)는 "ORIGINAL=N/DEIDENTIFIED=Y 고정, 수동 override 금지"를 기대결과로 담고 있었으나, 커밋 `0d290c4e`(2026-08-03, "export 개인정보 3필드 정책 반전")가 이를 정반대로 바꿨다. 신설 `ExportPrivacyPolicy` 클래스(`backend/src/main/java/kr/co/cudo/authoring/dataset/export/ExportPrivacyPolicy.java`)의 javadoc에 "★ 확정 정책(2026-08-03 사용자 확정 — 구 정책 전면 반전)" 표와 "폐기된 구 정책과 그 경위(되돌리지 말 것)" 절이 명시돼 있다.
  ```java
  // ExportPrivacyPolicy.resolve()
  private static String resolve(ExportKind kind, String manualYn, String deidDefault) {
      if (kind != ExportKind.DEIDENTIFIED) { return null; }             // ORIGINAL=항상 null
      return (manualYn == null || manualYn.isBlank()) ? deidDefault : manualYn.trim(); // DEID=수동값 우선
  }
  ```
- **재현/확인 경로**:
  ```bash
  curl -X PUT localhost:18081/api/v1/frames/{srcSn}/privacy-meta -H "Authorization: Bearer $WORKER" \
    -d '{"srcSn":{srcSn},"anonymity":"N","pseudonymity":"Y","privacyIncluded":"Y"}'
  # 재export 후 v{n}/orgnl/*.json → image.anonymity=null (판정 안 함)
  # 재export 후 v{n}/deid/*.json  → image.anonymity="N" (수동값 그대로, override 됨)
  ```
- **영향**: 기능 영향 없음(현행 동작이 최신 정본과 일치). 검증 프로세스 영향 — 카탈로그를 정정하지 않았다면 다음 회차에서 거짓 FAIL 3건 발생 위험.
- **수정 방향(제안)**: 완료됨 — TC-EXPORT-022/023/030을 이번 회차에 직접 정정(위 "카탈로그 정정 내역" 참조). `UNCERTAINTIES.md` ★ 확정 정책 절에 6번째 항목으로 "개인정보 3필드(anonymity/pseudonymity/privacy_included) = `ExportPrivacyPolicy` 단일판정, ORIGINAL 판정 안 함/DEID 수동값 우선" 등재를 권장(향후 "일관성 없다"는 재검토·되돌리기 방지 — `ExportPrivacyPolicy` 클래스 주석이 이미 이 경고를 담고 있으나 UNCERTAINTIES에도 반영하면 검증자가 더 빨리 확인 가능).


### [E-ISSUE-121] TC-META-034 / TC-META-030 — 카탈로그가 2026-08-03 반전 **이전** 정책을 기대값으로 들고 있었다 (정정 완료)
- **심각도**: HIGH (카탈로그 정합 — 다음 전수 검증에서 정상 동작이 "결함"으로 재발견될 축)
- **기대 동작(기대효과)**: 카탈로그 기대결과는 확정 정책과 일치해야 한다. 어긋나면 ①검증자가 정상 동작을 FAIL 로 올리고 ②그 "수정"이 폐기된 정책을 되살린다(이 저장소의 반복 사고 패턴).
- **현재 동작(이슈 내용)**: 정정 전 TC-META-034 = *"화면·기록용만 — export `image.anonymity` 는 `ExportKind` 파생 유지"*, TC-META-030 = *"미저장 시 파생(`PRVC_TYPE_CD=ANONY→Y` 등)"*. 실제는 정확히 반대·소멸:
  - `ExportPrivacyPolicy.resolve()` (92-97) — `kind != DEIDENTIFIED` → `null`, 아니면 `manualYn` 우선 + 기본상수 폴백
  - `FramePrivacyMetaService.toEffective()` (190-196) — `firstNonBlank(src.getAnonyInclYn(), ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY)`; 서비스가 **영상 행을 더 이상 읽지 않는다**(`VideoRepository` 의존 제거)
- **재현/확인 경로**: (실행함) `PUT /v1/frames/468/privacy-meta {N,Y,Y}` → 디바운스 flush 후 `docker exec klid-backend cat /app/storage/raw/seed/101/v4/deid/0000.json` → `image = N/Y/Y`, `v4/orgnl/0000.json` → 3필드 `null`.
- **영향**: 문서 정합. 방치 시 "수동값이 export 를 덮는다"를 결함으로 오판 → 억제 로직 부활 위험.
- **수정 방향(제안)**: **이번 회차에 E-9 절에서 직접 정정 완료**(TC-META-030·034 기대결과 재작성, 절 머리말에 확정 정책 4줄 추가). 남은 조치는 파일 상단 `## 변경 이력` 표에 3차 행 추가인데, 이는 본 파트 담당 라인범위(309행~) 밖이라 **병합 담당이 반영**해야 한다. (같은 회차 part6 이 TC-EXPORT-022/023 을 동일 방향으로 이미 정정했음을 확인 — 두 정정은 서로 정합한다.)


---

### MEDIUM

### [E-ISSUE-21] TC-AUG-120 — 파생영상의 `video.*` 기술메타가 부모와 다르다 (두 메타 소스가 충돌하고, 복사분이 즉시 덮어써진다)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 파생영상의 비디오 파일은 부모 비식별본의 **바이트 동일 사본**이므로, `LS_DATA_META` 의 `video.*`(`RESL`/`FPS`/`duration_ms`/`codec`/`bit_rate`)와 그로부터 동결되는 `LS_DATASET_VIDEO_META`·`V_COMPLETED_VIDEO` 값이 부모와 **일치**해야 한다는 것이 `CLAUDE.md`("★ 파생영상에는 '원본영상'이 없다" 절 — *"video.\* 기술메타는 부모와 동일한 것이 정상 … `DerivedMetaCopier` 의 부모 값 복사는 결함이 아니다"*)와 TC-AUG-120 ⚠ 주석의 명시 계약이다. 관제는 이 값으로 파생 1행을 UPSERT 하므로 어느 쪽이 진실인지 확정돼 있어야 한다.
- **현재 동작(이슈 내용)**: 두 기전이 순차로 겹쳐 **최종값이 항상 ffprobe 실측값**이 된다.
  1. `DerivedMetaCopier.java:29-30` — *"메타 값 전체 복사 — `video.*` 기술메타도 **포함**. 파생영상의 비디오 파일은 원본(비식별) 복사본이라 `video.*`={원본값}이 정합적이다(구 `isTechnicalKey` skip 해제)"* → 부모 값을 복사한다.
  2. `AsyncAugmentFrameRunner.java:106-121` — Phase C 확정 이후 `AsyncVideoMetaRunner` 를 기동해 **파생 사본을 ffprobe** 하고 같은 키를 덮어쓴다.
  3. 부모 쪽 `video.*` 는 `VideoMetaService.java:37-54` 규약상 **`LS_DATA_INGEST` 인입 선언값 우선**(`bit_rate` 만 ffprobe 전용)이다.
  → 인입 선언이 실제 파일과 다르면 부모≠파생이 **구조적으로** 발생한다.
- **재현/확인 경로** (본 검증 실측):
  ```sql
  SELECT raw_sn, meta_key, meta_vl FROM ls_data_meta
   WHERE raw_sn IN (101,158) AND meta_key LIKE 'video%' ORDER BY meta_key, raw_sn;
  -- 101 video.resolution 640x480   | 158 video.resolution 320x240
  -- 101 video.fps        30        | 158 video.fps        10.0
  -- 101 video.duration_ms 10000    | 158 video.duration_ms 5000
  -- 101 video.codec      H264      | 158 video.codec      h264
  SELECT wdth, vrtc, resl, fps, vdo_len_sec FROM ls_data_ingest WHERE raw_sn=101;  -- 640|480|640x480|30|10  ← 인입 선언
  ```
  ```bash
  # 파생 158 의 비디오는 101 비식별본의 바이트 동일 사본인데도 값이 다르다
  docker exec klid-mock-server ffprobe -v error -show_entries stream=width,height,r_frame_rate -of csv=p=0 \
    /app/storage/deidentified/videos/augment/101/158/WINTER.mp4   # 320,240,10/1
  docker exec klid-mock-server ffprobe ... /app/storage/raw/seed/clip-9101.mp4      # 320,240,10/1  (부모 실제 파일도 동일)
  ```
- **영향**: 데이터 정합 + 외부 계약. ①관제가 같은 영상 트리(부모/파생)에서 **서로 다른 해상도·fps·길이**를 받는다 — 파생 산출물이 부모의 사본이라는 계약과 모순되어 관제 측 정합 검사·통계가 어긋난다. ②`CLAUDE.md` 가 관제에 명시하라고 한 *"`RESL` 은 비디오 파일 기준"* 규칙이 부모 행에서는 **성립하지 않는다**(부모는 인입 선언값). ③`DerivedMetaCopier` 의 `video.*` 복사는 즉시 덮어써지는 **죽은 작업**이며, 주석은 그 사실을 반영하지 않아 다음 수정자가 "복사가 최종값"으로 오독한다. 보안 노출 없음.
- **수정 방향(제안)**: 정책을 **한 축으로 확정**한다. ⓐ"파생 = 사본 실측"으로 간다면 `DerivedMetaCopier` 의 `video.*` 복사를 제거(죽은 작업 정리)하고 `CLAUDE.md`·TC-AUG-120 서술을 "파생 사본을 실측한 값"으로 고친다. 이때 **부모 쪽도 비식별본 기준으로 재측정할지**를 함께 정해야 부모/파생 비교가 성립한다. ⓑ"파생 = 부모와 동일"로 간다면 파생 경로에서 `AsyncVideoMetaRunner` 기동을 빼고 복사값을 최종으로 둔다(단 인입 선언이 틀린 경우 오류가 파생으로 전파된다). 어느 쪽이든 **관제 협의 대상**이다. ⚠ 구현은 하지 않았다. 카탈로그 TC-AUG-120 의 ⚠ 주석은 이번 회차에 사실 기준으로 정정했다.


### [E-ISSUE-25] [이월·미해소] 만료 스윕의 클레임과 롤업이 같은 트랜잭션이라 특정 job 이 **영구 회수 불가**(15분마다 무한 실패 반복)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 만료 스윕은 "비종결 job 은 반드시 종결된다"를 보장하는 최후 회수 장치다(`LsDataAugJobRepository.claimExpired` javadoc·`GenAiCallbackService` 주석이 "무한 대기는 없다"를 계약으로 명시). 회수 자체가 반복 실패하면 dead-letter 로 종결되거나 최소한 격리돼야 한다.
- **현재 동작(이슈 내용)**: 1차(2026-08-01) E-ISSUE-25 가 **그대로 남아 있고 이번 회차에도 실제로 반복 중**이다. `AugmentJobExpiryTxService.java:62-88` 에서 클레임 UPDATE(71-72)와 롤업(84)이 같은 `@Transactional` 이라, 롤업이 던지면 클레임까지 롤백된다.
  ```java
  @Transactional("controlTransactionManager")
  public boolean expire(Long augJobSn, Long dataAugSn, LocalDateTime cutoff) {
      if (augRepository.findByDataAugSnForUpdate(dataAugSn).isEmpty()) { ... return false; }
      if (jobRepository.claimExpired(augJobSn, cutoff, ...) == 0) return false;   // ← 이 클레임이
      ...
      AugmentApplyResult rolledUp = rollup.rollUpIfAllTerminal(dataAugSn, jobs, externalJobIdOf(jobs)); // ← 여기서 throw 하면 함께 롤백
  ```
  실측(이번 회차 backend 로그, 15분 간격으로 **7회** 반복):
  ```
  2026-08-04 00:44:19.819 ERROR AugmentJobExpirySweeper - [Augment][Expiry] expire failed augJobSn=770004 reason=CustomException
  2026-08-04 00:59:19.811 ERROR ... augJobSn=770004
  2026-08-04 01:14:19.796 ERROR ... augJobSn=770004
  2026-08-04 01:29:20.100 ERROR ... augJobSn=770004
  ```
  ```
  aug_job_sn=770004 | data_aug_sn=770003 | job_stts_cd=RECEIVED | err_cd=(null)   ← 회수 안 됨
  data_aug_sn=770003 | aug_proc_stts_cd=PENDING | dead_letter_at=(null)           ← 증강도 PENDING 고착
  ```
  근본 데이터 이상도 그대로다 — `LS_DATA_AUG_JOB.OTSD_JOB_ID` 에 UNIQUE 가 없어 같은 값이 두 job 에 존재한다:
  ```sql
  SELECT otsd_job_id, count(*) FROM ls_data_aug_job WHERE otsd_job_id IS NOT NULL
   GROUP BY otsd_job_id HAVING count(*)>1;   -- qa0802jobD | 2
  ```
  그 결과 롤업이 `requireJobIdNotOwnedByOtherAug` 에서 409 를 던지고(`AugmentResultService.java:271-285`), 스윕은 `RuntimeException` 을 잡아 ERROR 만 남기고 넘어간다(`AugmentJobExpirySweeper.java:225-228`) → 다음 tick 후보 쿼리에 다시 잡힌다.
- **재현/확인 경로**: 위 SQL 로 중복 `OTSD_JOB_ID` 확인 후 `docker logs klid-backend | grep "Expiry] expire failed"`. 신규 재현은 1차 이슈 블록의 INSERT 스크립트 그대로.
- **영향**: 기능/데이터 정합(liveness). 그 증강은 사람이 DB 를 손대기 전까지 PENDING 에서 못 나오고, 15분마다 실패 트랜잭션 1건 + ERROR 로그를 영구 생성한다. 같은 구조상 롤업이 던지는 **모든** 예외(`DataIntegrityViolationException` 포함)가 동일 결과를 낳는다. 보안 노출 없음.
- **수정 방향(제안)**: 1차 제안 그대로 유효 — ①`expire()` 에서 클레임 커밋과 롤업을 분리(클레임을 `REQUIRES_NEW` 로 먼저 커밋하면 롤업이 실패해도 job 은 종결로 남아 다음 tick 이 재선정하지 않는다) ②또는 회수 실패 횟수를 누적해 임계 초과 시 dead-letter ③부수적으로 `LS_DATA_AUG_JOB.OTSD_JOB_ID` UNIQUE 검토(단 벤더가 같은 job_id 를 중복 반환하면 위탁이 깨지므로 트레이드오프 확인 필요). ⚠ 구현하지 않았다.


### [E-ISSUE-41] TC-RESL-012 — 프레임 해상도 실측 실패가 400 이 아니라 500 으로 나가고, 400 가드는 도달 불가 사문화 (1차 E-ISSUE-41 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 원본 프레임 이미지가 손상/미지원 포맷/부재라 해상도를 확인할 수 없으면 **400 INVALID_INPUT `"원본 프레임 해상도를 확인할 수 없습니다."`** 로 응답해야 한다. 서버 장애가 아니라 요청 대상 데이터 상태의 전제 불충족이므로 4xx 여야 FE 가 "이 영상은 프레임이 깨져 해상도 변경 불가"로 안내할 수 있고 5xx 알람(운영 오탐)을 만들지 않는다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.java:110-112`
  ```java
  int[] dim = measureFirstFrame(rawSn);
  int srcW = dim[0]; int srcH = dim[1];
  if (srcW <= 0 || srcH <= 0) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "원본 프레임 해상도를 확인할 수 없습니다.");
  }
  ```
  실측기 `Java2DImageResizer.readImage`(80-98행 부근)는 `ImageIO.read` 가 IOException 이거나 null 이면 **그 자리에서** `INTERNAL_ERROR("프레임 이미지를 읽을 수 없습니다.")` 를 던진다 — `dim<=0` 을 반환하는 경로가 없다. 따라서 위 400 분기는 어떤 입력으로도 도달 불가능한 사문화 코드다.
  2026-08-03 3차 재실증(rawSn=101, frm_no=0 의 `de_idntf_src_file_path_nm` 을 mp4 경로로 임시 변경):
  ```
  HTTP/1.1 500
  {"success":false,"data":null,"message":"프레임 이미지를 읽을 수 없습니다.","errorCode":"INTERNAL_ERROR"}
  ```
  테스트 직후 원본 경로(`/app/storage/deidentified/frames/deid/101/frame-0.jpg`)로 원복함.
- **재현/확인 경로**:
  ```sql
  UPDATE ls_data_src SET de_idntf_src_file_path_nm='/app/storage/deidentified/videos/101/clip-9101-mask.mp4'
   WHERE src_sn=468; -- rawSn=101 frm_no=0 (허용 base 안, 확장자만 비이미지)
  ```
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/101/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{}'
  # → HTTP 500 "프레임 이미지를 읽을 수 없습니다."
  ```
- **영향**: 기능/운영. 데이터 상태 문제가 서버 오류로 분류되어 5xx 알람·SLO 오염, FE 표준 4xx/5xx 분기에서 "일시 장애"로 오안내, 회귀 테스트 커버 갭(400 분기가 원천적으로 테스트 불가). 보안 등급 아님(경로·스택 미노출 유지 확인됨).
- **수정 방향(제안)**: `Java2DImageResizer.readImage` 의 실패를 호출부에서 구분 가능하게 한다 — 포트 계약을 `Optional<int[]>` 로 넓히거나 실측 실패를 `INVALID_INPUT` 전용 예외로 승격해 `measureFirstFrame` 에서 400 메시지로 재던진다. `srcW<=0` 사문화 분기는 제거하거나 실제 도달 가능하게 배선하고 "손상 프레임 → 400" 테스트를 추가한다. (⚠ 구현은 하지 않는다)


### [E-ISSUE-42] TC-RESL-013 — `presets` enum 이 숫자(ordinal)로도 바인딩돼 문자열 화이트리스트 계약을 우회한다 (1차 E-ISSUE-42 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `presets` 원소는 `RESL_1080P`/`RESL_720P`/`RESL_480P` 세 문자열만 허용되고 그 외 값·형식은 Jackson 역직렬화 단계에서 400 으로 거부돼야 한다(`ResolutionChangeRequest.java` 의 명시 계약, "자유 입력 해상도 차단 CWE-20").
- **현재 동작(이슈 내용)**: Jackson 기본 동작상 JSON 정수는 enum ordinal 로 해석된다. 프로젝트에 `fail-on-numbers-for-enums` 류 하드닝 설정이 없고(`application*.yml` 에 `spring.jackson` 블록 없음) `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리도 없다.
  2026-08-03 3차 재실증(rawSn=101, 이미 3종 파생 존재하는 상태에서):
  ```
  {"presets":[0]}   -> HTTP 500 "요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다."  ← 역직렬화 통과 + createOne 시도까지 도달(중복이라 실패로 관측되나 "도달했다"는 것 자체가 증거)
  {"presets":[99]}  -> HTTP 400 "요청 본문이 올바르지 않습니다." (ordinal 범위초과만 거부)
  {"presets":["RESL_240P"]} -> HTTP 400 (문자열 축은 정상)
  ```
- **재현/확인 경로**:
  ```bash
  curl -i -X POST http://localhost:18081/api/v1/videos/{approvedRawSn}/resolution \
    -H "Authorization: Bearer $REVIEWER" -H 'Content-Type: application/json' -d '{"presets":[2]}'
  # → 파생이 없는 원본이면 RESL_480P 파생이 실제로 생성됨(문서화되지 않은 표현)
  ```
- **영향**: 입력검증/계약(CWE-20). 값 공간이 넓어지지는 않으므로(0~2=동일 3개 프리셋) 권한상승·자유해상도 주입은 아니다. 실질 위험: ①계약 밖 표현이 허용돼 OpenAPI·연동규격과 실제 수용 입력이 어긋남 ②순서 의존 취약 — 향후 `ResolutionPreset` 에 상수를 앞/중간에 추가하면 기존 숫자 페이로드가 조용히 다른 프리셋으로 재매핑(무증상 데이터 오류) ③"enum 화이트리스트 강제" 보안 주장이 부분적으로만 성립.
- **수정 방향(제안)**: 전역 `spring.jackson.deserialization.fail-on-numbers-for-enums: true`(영향범위 넓어 전 DTO 회귀 확인 필요) 또는 국소적으로 `ResolutionPreset` 에 `@JsonCreator` 문자열 전용 팩토리(미지값 → `IllegalArgumentException`→400)를 둔다. `{"presets":[0]}` → 400 케이스를 테스트에 추가한다. (⚠ 구현은 하지 않는다)

### [E-ISSUE-62] TC-RESL-062 / TC-RESL-067 — cleanup 이 파생 비디오 **파일만** 지우고 디렉터리를 남기며, 그 잔존을 `clean=true` 로 오판한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 확정 실패 파생의 Phase B 산출물은 흔적 없이 정리돼야 한다. 특히 `cleanup()` 의 반환값은 러너가 "RAW 행(유일한 DB 포인터)을 지워도 되는가"를 판단하는 근거(`AsyncResolutionRunner.java:155-158`)이므로, 잔존물이 있으면 `false` 여야 한다.
- **현재 동작(이슈 내용)**: 프레임 디렉터리는 `deleteRecursivelyQuietly` 로 재귀 삭제되지만, 파생 비디오는 파일 1개만 삭제되고 그 부모 디렉터리(`videos/resolution/{parentRawSn}/{newRawSn}/`)가 남는다. 잔존 판정도 파일만 본다.
  ```java
  // ResolutionFileMaterializer.java:141-152
  if (videoDst != null) {
      try {
          deleteFileQuietly(videoDst);
          if (Files.exists(videoDst)) { clean = false; }   // 파일만 확인 — 상위 디렉터리는 미검사
      } catch (RuntimeException e) { clean = false; ... }
  }
  ```
  결과적으로 `clean=true` → 러너가 `deleteFailedDerivativeRaw` 로 RAW 행까지 지워 **DB 어디서도 참조되지 않는 빈 디렉터리**가 영구 누적된다.
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend find /app/storage/deidentified/videos/resolution -mindepth 2 -maxdepth 2 -type d -empty
  # /app/storage/deidentified/videos/resolution/905/168
  # /app/storage/deidentified/videos/resolution/905/170
  # /app/storage/deidentified/videos/resolution/905/97
  # /app/storage/deidentified/videos/resolution/94/100        (4건)
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT count(*) FROM ls_data_raw WHERE raw_sn IN (168,170,97,100);"   # -> 대응 RAW 없음
  ```
- **영향**: PII 노출은 없다(빈 디렉터리). 다만 NAS inode 무한 누적 + 운영자가 "이 디렉터리는 뭐지"를 추적할 DB 포인터가 없다. 5,000건 영상 × 3프리셋 재시도 규모에서 축적된다.
- **수정 방향(제안)**: `videoDst` 삭제 후 **부모 디렉터리가 비어 있으면 함께 제거**(`Files.deleteIfExists(videoDst.getParent())`, 단 `videos/resolution/{parentRawSn}` 루트까지 올라가지 않도록 파생 RAW_SN 세그먼트 1단만). 그리고 그 디렉터리 잔존도 `clean` 판정에 포함해 `deleteFailedDerivativeRaw` 보호(TC-RESL-067)와 일관되게 한다. 기존 4건은 일회성 스크립트로 정리(`-type d -empty` + DB 미참조 확인 후).


### [E-ISSUE-63] TC-RESL-066 — 고아 파생 RAW `DELETE` SQL 에 `DE_IDENT_YN <> 'Y'` 가 빠져 "검사 조건 = SQL 조건" 계약이 성립하지 않는다 (1차 E-ISSUE-64 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그·javadoc 이 명시한 대로 "잠금 후 4조건 재확인 + **최종 DELETE 문에도 동일 조건 동봉**"이어야 검사~삭제 사이 창이 닫힌다. `LS_DATA_SRC.RAW_SN` 에 FK 가 없어 DB 가 대신 막아주지 않으므로 SQL 조건이 마지막 방어선이다.
- **현재 동작(이슈 내용)**: 서비스는 4조건을 검사하는데 SQL 은 3조건뿐이다.
  ```java
  // ResolutionPersistService.java:246 — 서비스 선검사에는 있다
  if ("Y".equals(raw.getDeIdntfYn()) || !LsDataRaw.DATA_STTS_FAILED.equals(raw.getDataSttsCd())) { ... return false; }
  ```
  ```sql
  -- VideoRepository.java:495-501 — SQL 에는 없다
  DELETE FROM LS_DATA_RAW
   WHERE RAW_SN = :rawSn
     AND ORGNL_RAW_SN IS NOT NULL
     AND DATA_STTS_CD = 'FAILED'
     AND NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = :rawSn)
  ```
- **재현/확인 경로**: 정적. `sed -n '494,502p' backend/src/main/java/kr/co/cudo/authoring/video/repository/VideoRepository.java`. 실동작 삭제 경로 자체는 정상(166·167·168·170 삭제 확인) — 결함은 **경합 창**에서만 드러난다: 검사 통과 후 DELETE 직전에 승자 Phase C 가 `markDeidentified('Y')` + `markCompleted()` 를 커밋하면 `DATA_STTS_CD` 가 `COMPLETED` 로 바뀌어 실제로는 SQL 이 0건 삭제하므로 **현 상태 머신에서는 사고가 나지 않는다**(`'Y'` + `FAILED` 조합이 만들어지지 않기 때문). 즉 위험은 잠재적이고, 문제는 **문서화된 계약과 코드의 불일치**다.
- **영향**: 현재 데이터 손실 위험은 낮음(위 근거). 그러나 향후 `'Y'` 와 `FAILED` 가 공존하는 전이(예: 확정 후 후처리 실패로 FAILED 표기)가 생기면 **확정된 파생 RAW 가 삭제**된다. 방어 심층화 위반.
- **수정 방향(제안)**: DELETE 문에 `AND (DE_IDENT_YN IS NULL OR DE_IDENT_YN <> 'Y')` 를 추가해 서비스 선검사와 1:1로 맞춘다(`VideoRepository.java:495-501`). 회귀 가드로 `ResolutionPersistServiceTest.고아행_정리_직전_상태가_바뀐_행은_삭제되지_않는다` 에 "`'Y'` 로 바뀐 FAILED 행" 케이스를 추가. 카탈로그 TC-RESL-066 에는 이번 회차에 실측 주석을 병기해 두었다.


### [E-ISSUE-64] TC-RESL-054 — `releaseReservedAug` 의 "승자 보호"가 **부모 라벨 0건 영상에서 무효** (1차 E-ISSUE-62 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 동시 finalize 에서 패자의 실패 정리가 **승자의 예약 aug 행을 지우면 안 된다**. 지우면 승자의 파생 RAW 는 확정돼 있는데 `LS_DATA_AUG`(RESL_*) 행이 사라져 ①증강 이력(`GET /v1/augments`)에서 사라지고 ②`new_raw_sn` 매핑이 끊겨 파생 식별 근거가 없어지며 ③같은 (부모, 프리셋) 재요청이 통과해 중복 파생이 생긴다.
- **현재 동작(이슈 내용)**: 보호 근거가 `LS_DATA_AUG_LBL_MAP` 참조 존재 **하나뿐**인데, 그 매핑은 부모에 라벨이 있어야만 만들어진다.
  ```java
  // ResolutionPersistService.java:205-210
  List<LsDataAugLblMap> refs = lblMapRepository.findAllByDataAugSn(dataAugSn);
  if (!refs.isEmpty()) { ...skip... }        // 라벨 0건이면 refs 는 항상 비어 있다
  // :386-389 — 승자조차 매핑을 만들지 않는다
  List<LsDataLbl> parentLabels = lblRepository.findBySrcSnIn(parentSrcToNewSrc.keySet());
  if (parentLabels.isEmpty()) { return 0; }
  ```
- **재현/확인 경로**:
  ```bash
  # 부모 906 은 라벨 0건 -> 파생 163/165 확정 후에도 매핑 0행
  docker exec klid-postgres psql -U klid_user -d klid_system -c \
    "SELECT (SELECT count(*) FROM ls_data_lbl l JOIN ls_data_src s ON s.src_sn=l.src_sn WHERE s.raw_sn=906) parent_lbl,
            (SELECT count(*) FROM ls_data_aug_lbl_map WHERE data_aug_sn IN (59,60)) map_rows;"
  # parent_lbl=0, map_rows=0   -> 이 aug 는 releaseReservedAug 가 무조건 삭제한다
  ```
  실제 동시 finalize 는 트리거가 단일 AFTER_COMMIT 러너라 현 배선에서는 재현되지 않는다(잠재 결함).
- **영향**: 데이터 정합 — 라벨이 아직 없는(막 검수 승인된, 오토라벨 0건인) 영상의 파생에서 승자 예약행 소실. 파생 등재 게이트(`CLAUDE.md` "등재 게이트의 축은 리뷰 행")의 해상도 예외 판정 근거인 매핑 행이 사라지는 것도 부작용.
- **수정 방향(제안)**: 보호 근거를 라벨맵이 아니라 **파생 RAW 확정 상태**로 바꾼다 — `releaseReservedAug(dataAugSn)` 진입 시 `aug.getNewRawSn()` 으로 파생 RAW 를 조회해 `deIdntfYn='Y' || DATA_STTS_CD=COMPLETED` 이면 삭제 skip. 라벨맵 검사는 보조로 유지. 또는 `aug.augProcSttsCd == ACCEPTED`(확정 전이 완료)면 skip — `markResolutionGenerated` 가 승자 커밋에서만 일어나므로 라벨 유무와 무관한 판정축이 된다.


### [E-ISSUE-66] TC-RESL-033 — 중복/경합 예약 패자가 API 표면에서 **409 가 아니라 500** 으로 응답된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 예약 계층은 정확히 409 CONFLICT("동일 영상에 해당 해상도 파생 결과가 이미 존재합니다.")를 던진다. 단일 프리셋 요청에서 그 원인이 **클라이언트가 고칠 수 있는 상태 충돌**이라면 API 도 4xx 로 알려줘야 FE 가 "이미 있음"과 "서버 오류"를 구분해 안내·재시도 정책을 나눌 수 있다. 특히 `CLAUDE.md` 는 파생 중복의 **연타 방어를 FE 단독 책임**으로 두고 있어, FE 가 응답으로 원인을 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `VideoResolutionService.changeResolution` 이 프리셋별 실패를 격리하며 예외를 삼키고, 전부 실패하면 일괄 500 을 낸다.
  ```
  WARN  VideoResolutionService - [Video][Resolution] derivative creation failed rawSn=906 preset=RESL_720P reason=CustomException
        at ResolutionReservationPersister.reserveAndCreate(ResolutionReservationPersister.java:107)   <-- 내부는 CONFLICT
  ERROR VideoResolutionService - [Video][Resolution] all presets failed rawSn=906 attempted=1
  ```
  ```json
  HTTP 500 {"success":false,"message":"요청한 모든 해상도 프리셋의 파생영상 생성에 실패했습니다.","errorCode":"INTERNAL_ERROR"}
  ```
- **재현/확인 경로**:
  ```bash
  # (1) 순차 중복
  curl -s -w '\n%{http_code}\n' -X POST localhost:18081/api/v1/videos/906/resolution \
    -H "Authorization: Bearer $REV" -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}'
  # -> 500 INTERNAL_ERROR  (내부 원인은 409 CONFLICT)

  # (2) 동시 경합 3발
  for i in 1 2 3; do curl -s -o /tmp/c$i -w "req$i %{http_code}\n" -X POST \
    localhost:18081/api/v1/videos/905/resolution -H "Authorization: Bearer $REV" \
    -H 'Content-Type: application/json' -d '{"presets":["RESL_720P"]}' & done; wait
  # -> req3 201 / req1 500 / req2 500 ... aug 행은 정확히 1건(직렬화 자체는 정상)
  ```
- **영향**: 기능/UX. FE 가 `errorCode=INTERNAL_ERROR` 만 보고는 "장애"로 오인해 재시도 루프를 돌거나 사용자에게 잘못된 안내를 한다. 500 은 모니터링 알람도 오염시킨다(정상 중복 클릭이 서버 에러로 집계). 보안 영향은 없다.
- **수정 방향(제안)**: `VideoResolutionService` 의 롤업 규칙을 **원인 코드 보존형**으로 바꾼다 — 시도한 프리셋이 전부 실패했고 그 실패가 **모두 동일한 4xx `CustomException`** 이면 그 코드/메시지를 그대로 전파하고, 혼재·5xx 포함일 때만 현재의 500 롤업을 유지한다. E-4 의 "1건 이상 성공=201 / 전부 실패=500 / 전부 스킵=400" 계약과 충돌하지 않도록 카탈로그(E-4)도 함께 갱신 필요 — **이 판단은 E-4 담당 범위와 겹치므로 병합 시 조정 요망**.


### [E-ISSUE-81] TC-EXPORT-016 / TC-EXPORT-019 — export 가 보류·실패해도 디바운스 윈도우가 `complete` 처리돼 **그 수정 통지의 변경 프레임 목록이 영구 유실**된다 (1차 E-ISSUE-83 이월, 실동작 재확인)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ControlNotifyDebouncer.claimAndSendIsolated` 의 자체 계약 — *"실패한 윈도우는 `complete` 를 호출하지 않아 저장소에 FLUSHING 으로 남는다 … 통지가 **소실되지 않고 지연**된다"*(`ControlNotifyDebouncer.java:284-286`). 또 `CLAUDE.md` 는 TASK_MODIFIED 페이로드에 **변경 프레임 목록**(`SRC_SN` + 변경 종류)을 담도록 규정한다. 즉 export 가 보류되면 그 윈도우의 축적분도 함께 보류됐다가 재개돼야 한다.
- **현재 동작(이슈 내용)**: 재생성 동반 윈도우(`exportRegenerated=true`)의 실제 산출·통지는 `@Async` 러너에 위임되고 **즉시 반환**하므로, 그 뒤의 export 차단/실패가 `send()` 의 예외로 관측되지 않는다. 결과적으로 윈도우가 무조건 `complete` 로 마감돼 축적분이 사라진다.
  ```java
  // ControlNotifyDebouncer.java:344-351  (send)
  if (window.exportRegenerated()) {
      Runnable notifyCallback = notifyService == null ? null
              : () -> notifyService.sendModified(rawSn, frameChanges, videoLevelChangeTypes, true);
      exportRunner.runReExportThenNotify(rawSn, true, notifyCallback);   // @Async — 즉시 반환
      return;
  }
  // ControlNotifyDebouncer.java:303-306  (claimAndSendIsolated)
  send(window);
  store.complete(window.acmlSn());   // ← export/통지 결과와 무관하게 마감
  ```
  **실측(2026-08-04, rawSn=94)**:
  ```
  01:45:06.170 [ControlNotifyDebounce] flush rawSn=94 regen=true frames=448=[META_UPDATED]
  01:45:06.171 WARN [DatasetExport] export blocked — deident report open rawSn=94
  01:45:06.171 WARN [DatasetExport] async export failed rawSn=94 cause=CustomException
  ```
  ```sql
  -- flush 직후: 해당 rawSn 의 축적 윈도우가 상태 불문 0건 (FLUSHING 잔존 아님 = 재클레임 경로 없음)
  SELECT * FROM ls_mon_noti_acml WHERE raw_sn = 94;   -- (0 rows)
  ```
  신고 해소(01:45:40) 시 나간 통지는 M1 재트리거(`runApprovalAsync`)의 **영상 단위 `TASK_COMPLETED`**(mock: `notify-completed accepted job_id=94`)라 `frames=448` 변경 목록을 담지 않는다. **base 거부(FAILED) 경로도 동일** — `01:48:46.358 export base rejected` 직후 rawSn=94 윈도우 0건.
- **재현/확인 경로**:
  ```bash
  # APPROVED 영상에 신고 접수 → 신고 구간에 승인 후 수정 → 60초 뒤 flush
  curl -X POST localhost:18081/api/v1/labels/{srcSn}/deident-report -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"reason":"probe"}'
  curl -X PUT  localhost:18081/api/v1/frames/{srcSn}/description   -H "Authorization: Bearer $REV" \
       -H 'Content-Type: application/json' -d '{"srcSn":{srcSn},"description":"x"}'
  # 로그: flush(regen=true, frames=…) → export blocked → async export failed
  ```
  ```sql
  SELECT noti_acml_sn, stts_cd, chg_dtl_cn FROM ls_mon_noti_acml WHERE raw_sn = :rawSn;  -- 0 rows = 축적분 소실
  ```
- **영향**: 기능/데이터정합 — 관제가 받는 수정 통지에서 **어느 프레임이 바뀌었는지**가 빠진다(관제는 전량 재조회로만 복구 가능). 영상 단위 정합 자체는 M1 재통지·회수기 재산출로 회복되므로 CRITICAL 은 아니나, 디바운서가 문서화한 "지연될 뿐 소실 없음" 보증이 **재생성 경로에서만 깨져 동작이 자기 문서와 다르다**. 통지 토글이 꺼진 형상(dev/stg/prd 기본)에서는 `notifyCallback` 이 애초에 null 이라 영향 없음.
- **수정 방향(제안)**: ⓐ `runReExportThenNotify` 가 결과를 상위에 돌려주도록 하고(콜백 `onFailure` 또는 `CompletableFuture`), **비통지 종결(`notifiable()==false`)이면 윈도우를 `complete` 하지 않고 FLUSHING 으로 재개방**해 임차 만료 후 재클레임되게 한다 — 이미 존재하는 lease 복구 machinery 를 그대로 재사용하므로 신규 메커니즘이 필요 없다. 또는 ⓑ 재생성 윈도우는 러너 스레드에서 `complete` 를 호출하도록 소유권을 넘긴다. 최소 조치로 ⓒ 차단·실패 시 WARN 에 유실된 `frameChanges` 요약을 남겨 감사 가능하게 한다. D-part3 의 **D-ISSUE-41**(NO_INPUT 보류분 복구 경로 단절)과 **같은 축**("러너의 성공/실패를 상위가 알 수 있게 한다")이므로 함께 처리하는 것이 좋다. **구현은 하지 않는다.**


### [E-ISSUE-83] TC-EXPORT-006 / TC-EXPORT-004 — 실패한 export 가 남긴 `v{n}` 디렉터리가 정리되지 않는데, 뷰에 **버전 식별자가 없어** 관제가 최신 유효 버전을 판별할 수 없다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` — *"`EXPORT_PATH_NM` 은 **영상 루트**를 가리킨다 — 관제가 `v1`·`v2` 를 한 경로 아래에서 보고 골라야 비교·복구가 가능하다"*. 즉 관제는 영상 루트 아래에서 **어느 `v{n}` 이 이번 통지가 가리키는 산출물인지**를 판별할 수 있어야 한다. 또 실패로 마감된 산출은 관제가 집을 수 있는 상태로 남아서는 안 된다.
- **현재 동작(이슈 내용)**: 두 사실이 겹친다.
  1. **뷰에 버전 컬럼이 없다.** `v_completed_video` 실측 컬럼 41개 중 export 관련은 `export_path_nm`(영상 루트)·`frame_cnt`·`export_stts_cd` 뿐이고 **`export_ver_no` 가 없다**. 통지 페이로드(6필드 평면: `job_id`·`event_type_cd`·`lclgv_cd`·`lclgv_nm`·`duration_sec`·`image_count`)에도 버전이 없다. 따라서 관제는 디렉터리명을 스캔해 `max(v)` 를 고르는 수밖에 없다.
  2. **실패 종결이 `v{n}` 디렉터리를 남긴다.** `DatasetExportWriter.java:83-89` 가 프레임 루프 **이전에** `Files.createDirectories(dir)` 를 무조건 수행한다.
     ```java
     Path dir = pathResolver.resolve(rawSn, rawFilePathNm, kind, version);
     createExportDir(dir, rawSn, kind, version);          // ← 프레임 0건이어도 생성됨
     …
     for (FrameContext frameCtx : frames) { … }
     ```
     그런데 `DatasetExportService.java:209-214`(산출 0건 → FAILED)와 `:238-246`(쓰기 중 예외 → FAILED) 어느 쪽도 `purgeThisRunVersionDir` 를 호출하지 않는다 — 삭제는 **신고 차단 경로(`:252-259`)에만** 배선돼 있다. retention 정리 잡도 없다(TC-EXPORT-043 확정 정책).
  - 결과: 최신 SUCCEEDED/PARTIAL 이 `v10` 인데 그 뒤 실패한 산출이 **비어 있거나 반쯤 찬 `v11`** 을 남기면, `max(v)` 로 고르는 관제는 **깨진 폴더를 최신 학습데이터로 픽업**한다. 뷰의 `frame_cnt`(=v10 기준)와 디스크 실체(v11)가 어긋나도 관제가 검출할 단서가 없다.
  - ⚠ 이번 실동작에서 남은 FAILED(rawSn=94 v8·v11)는 **base 거부** 유형이라 `pathResolver.resolve` 단계에서 막혀 디렉터리가 생성되지 않았다(`ls -d …/94/v*` → v1 v2 v4 v6 v7 v9 v10, v8·v11 없음). 즉 **base 거부는 안전하고, 산출 0건·쓰기 중 예외 두 유형만 해당**한다.
- **재현/확인 경로**:
  ```sql
  -- 1) 뷰에 버전 식별자가 없음
  \d+ v_completed_video      -- export_path_nm / frame_cnt / export_stts_cd 만, export_ver_no 없음
  -- 2) 산출 0건 유도: APPROVED 영상의 전 프레임 이미지 경로를 실재하지 않는 값으로 바꾼 뒤 재승인
  UPDATE ls_data_src SET src_file_path_nm = '/app/storage/raw/nope.jpg',
                         de_idntf_src_file_path_nm = '/app/storage/deidentified/nope.jpg'
   WHERE raw_sn = :rawSn;
  ```
  ```bash
  # 로그에 "nothing produced — marked FAILED rawSn=… version=N" 후
  docker exec klid-backend ls -R {영상루트}/v{N}     # orgnl/ deid/ 빈 디렉터리 잔존
  ```
- **영향**: 데이터정합 — 관제/데이터마트가 **깨진(빈·부분) 버전 폴더를 최신 학습데이터로 픽업**할 수 있다(사업 요구 *"데이터마트 학습데이터셋의 라벨링 정보 동기화"* 미충족). 부수적으로 빈 디렉터리가 무한 누적된다(retention 미구현 확정 정책이라 자연 정리되지 않음). 보안 영향은 없다(신고 차단 경로는 이미 purge 배선됨).
- **수정 방향(제안)**: ⓐ **뷰에 `EXPORT_VER_NO` 를 노출**(`V_COMPLETED_VIDEO` LATERAL 조인에 컬럼 추가)해 관제가 `max(v)` 추정 대신 명시값을 쓰게 한다 — 관제 계약 변경이므로 협의 필요하나 가장 근본적이다. ⓑ 실패 종결(`totalWritten==0`·쓰기 중 예외) 분기에서도 `purgeThisRunVersionDir(rawSn, prep.rawFilePathNm(), inserted.version())` 를 호출해 이번 실행이 만든 폴더만 정리한다(신고 차단 경로와 동일한 3중 경로가드를 그대로 재사용 — 신규 메커니즘 0). ⓒ 최소 조치로 `DatasetExportWriter` 가 **첫 프레임 쓰기 직전에** 디렉터리를 만들도록 지연시켜 산출 0건이면 디렉터리 자체가 생기지 않게 한다. **ⓐ+ⓑ 조합 권장. 구현은 하지 않는다.**


### [E-ISSUE-122] TC-META-047 — 영상 축 미입력 시 기본상수(`privacy_included=N`)가 프레임 수동값(`Y`)과 **모순**되어 영상 단위 과소 신고가 나간다
- **심각도**: MEDIUM (데이터 정합 / 개인정보 과소 선언 — CWE-359 인접)
- **기대 동작(기대효과)**: 커밋 `0d290c4e` 와 `CLAUDE.md`(358행)가 명시한 불변식 — *"`image="Y"` / `video="N"` 은 정책이 정당화한 방향의 **역방향**이라 논리적으로 성립할 수 없는 조합이며 실질은 개인정보 잔존의 **과소 신고**"*. 그래서 파생영상에 대해 `LsDataRaw.copyPrivacyMetaFrom` 을 도입했다.
- **현재 동작(이슈 내용)**: 그 불변식은 **파생 계승 경로에서만** 닫혔고, 일반 영상에서는 열려 있다. 영상 축을 입력하지 않으면 `ExportPrivacyPolicy` 가 기본상수(`privacy_included=N`, `anonymity=Y`)를 넣기 때문에, 프레임 축에 `Y` 를 선언해도 video 블록은 "개인정보 없음"으로 나간다.
  ```
  # v5/deid/0000.json (raw101, video 축 수동값 삭제 · 프레임 468 = N/Y/Y)
  video: {'anonymity': 'Y', 'pseudonymity': 'N', 'privacy_included': 'N'}   ← 기본상수
  image: {'anonymity': 'N', 'pseudonymity': 'Y', 'privacy_included': 'Y'}   ← 사람이 선언한 사실
  ```
  같은 조합이 **파생에서도 재발**한다: raw165(부모 906) — 프레임 601/602 는 부모 프레임값 `N/Y/Y` 를 계승했으나 부모 영상 축이 NULL 이라 영상 축도 NULL → 산출 시 video 는 다시 기본상수 N.
- **재현/확인 경로**:
  ```bash
  # 영상 축은 비우고 프레임 축만 Y 선언
  curl -X PUT .../v1/videos/101/privacy-meta -d '{"anonymity":null,"pseudonymity":null,"privacyIncluded":null}'
  curl -X PUT .../v1/frames/468/privacy-meta -d '{"srcSn":468,"anonymity":"N","pseudonymity":"Y","privacyIncluded":"Y"}'
  # 60초 디바운스 후
  docker exec klid-backend cat /app/storage/raw/seed/101/v5/deid/0000.json | jq '{video:.video.privacy_included, image:.image.privacy_included}'
  # → {"video":"N","image":"Y"}
  ```
- **영향**: 관제/데이터마트가 영상 단위 1행을 UPSERT 하므로, 프레임 단위로 "개인정보 잔존"이 선언된 영상이 **영상 단위로는 '없음'** 으로 집계된다. 정책이 스스로 "성립 불가"로 규정한 조합이 상시 발생 가능하다.
- **수정 방향(제안)**: 셋 중 택1 — ⓐ 영상 축 미입력일 때 기본상수 대신 **프레임 축 수동값의 OR 집계**(어느 프레임이든 `privacy_included=Y` 면 video 도 Y)로 폴백 ⓑ 프레임 축 저장 시 영상 축이 미입력이면 **화면에서 영상 축 입력을 요구**(FE 게이트) ⓒ 정책상 허용으로 확정한다면 `CLAUDE.md`·`ExportPrivacyPolicy` 주석의 "성립 불가능한 조합" 문장을 "파생 계승 한정"으로 좁혀 기술한다. **구현은 하지 않는다** — 어느 쪽이든 사용자 확정 필요.


### [E-ISSUE-123] TC-META-042 — 프레임 축 개인정보 선언 **변경**에는 행 단위 감사가 없다 (영상 축·신고 리셋과 비대칭)
- **심각도**: MEDIUM (OWASP A09 — 감사 부재)
- **기대 동작(기대효과)**: 같은 라운드가 세운 기준 — *"PII 표기를 되돌리는 행위이므로 **행 단위 감사**가 필요하다. 로그만으로는 부족하다"*(`VideoPrivacyMetaService.auditPrivacyMetaUpdate` javadoc, `DeidentReportService` 5-1 주석). 그 기준대로 ①영상 축 PUT → `LS_TASK_EVENT_LOG(PRIVACY_META_UPDATE)` ②신고에 의한 프레임 축 리셋 → `LS_DATA_LBL_HSTRY` 프레임당 1행 ③신고에 의한 영상 축 리셋 → `LS_TASK_EVENT_LOG(PRIVACY_META_RESET)` 이 남는다.
- **현재 동작(이슈 내용)**: **사람이 프레임 축 값을 바꾸는 경로만 행 단위 이력이 없다.** `FramePrivacyMetaService.applyAndNotify`(170-183)·`updateBulk`(124-166) 는 `log.info` 한 줄뿐이고 이력 테이블에 쓰지 않는다.
  ```java
  src.updatePrivacyMeta(anonymity, pseudonymity, privacyIncluded);
  srcRepository.save(src);
  log.info("[FramePrivacyMeta] updated srcSn={} rawSn={}", src.getSrcSn(), rawSn);   // ← 이게 전부
  ```
  실측: raw101 에 프레임 PUT 을 여러 번 했으나 `ls_task_event_log`(raw_data_id=101)에는 영상 축 `PRIVACY_META_UPDATE` 1행만 존재, `ls_data_lbl_hstry` 에도 대응 행 없음.
- **재현/확인 경로**: `PUT /v1/frames/468/privacy-meta` 후 `select * from ls_task_event_log where raw_data_id=101;` / `select * from ls_data_lbl_hstry where src_sn=468 order by 1 desc limit 5;` → 변경 이력 없음.
- **영향**: "누가 언제 이 프레임을 '개인정보 없음'으로 선언했는가"를 사후 추적할 수 없다. 리셋(자동)은 추적되는데 선언(수동)은 추적 안 되는 비대칭이라 감사 목적 자체가 반쪽이다.
- **수정 방향(제안)**: 프레임 축 PUT/벌크에서 `LsDataLblHstry.recordPrivacyMetaResetEvent` 와 같은 축의 "변경" 이벤트(라벨 델타 0건, `V139` 뷰 필터로 관제 미노출)를 프레임당 1행 남긴다. 판단값(Y/N)은 담지 않고 actor·시각·changed 여부만(영상 축과 동일 기준). 벌크는 행 수가 커질 수 있으므로 `saveAll` 배치.


### [E-ISSUE-124] TC-META-030 — 프레임 응답에 **출처(MANUAL/DERIVED)가 없어** 프리필 상수가 사람의 판정으로 승격될 수 있다 (self-fill 축)
- **심각도**: MEDIUM (§1-3 self-fill 금지 원칙 — 외부/사람 입력 없이 상수가 산출물에 사실처럼 실림)
- **기대 동작(기대효과)**: 조회 프리필은 "아직 판정 안 함"을 뜻하고, export 에 사실로 실리는 값은 사람이 고른 값이어야 한다. 영상 축은 이 위험을 인지해 응답에 `anonymitySource`/`pseudonymitySource`/`privacyIncludedSource`(`MANUAL`|`DERIVED`)를 실어 FE 가 구분할 수 있게 했다.
- **현재 동작(이슈 내용)**: 프레임 축 응답은 값 3개뿐이다 — `{"srcSn":468,"anonymity":"Y","pseudonymity":"N","privacyIncluded":"N"}`. FE 는 이 `Y/N/N` 이 **저장값인지 상수 프리필인지 구분할 수단이 없고**, 폼을 그대로 되돌려 보내면 상수가 `MANUAL` 로 굳어 `deid/*.json` 의 `image` 블록에 사실처럼 실린다(반전 이후 프레임 수동값이 export 를 덮으므로 위험도가 반전 전보다 커졌다). `FramePrivacyMetaResponse` 에 source 필드 없음 — 커밋 `0d290c4e` 자신이 "미해소 — 프레임 패널의 항목별 출처(MANUAL/DERIVED) 뱃지 부재, 응답 계약 변경 필요"로 기재.
- **재현/확인 경로**: `GET /v1/frames/468/privacy-meta` 응답(위)과 `GET /v1/videos/101/privacy-meta` 응답(`...Source` 3필드 포함) 대조.
- **영향**: 화면을 열고 저장만 해도 전 프레임이 "익명=Y, 개인정보=N" 으로 확정 선언된다(과소 신고). BE 는 전송값의 출처를 알 수 없어 막지 못한다.
- **수정 방향(제안)**: `FramePrivacyMetaResponse` 에 영상 축과 동일한 3개 source 필드 추가(응답 추가는 하위호환) + FE 가 `DERIVED` 항목은 null 로 전송. 근본 차단이 필요하면 요청에 출처 축을 추가하는 계약 변경이 필요하며 이는 영상 축과 함께 결정할 사안.


### [E-ISSUE-126] 카탈로그 커버리지 갭 — 영상 단위 개인정보 메타 API(V163 신규)에 테스트케이스가 **0건**
- **심각도**: MEDIUM (카탈로그 커버리지)
- **기대 동작(기대효과)**: 신설 화면·API 는 경계·오류·하위호환 케이스와 함께 카탈로그에 들어와야 한다(`CLAUDE.md` 문서 동기화 규칙).
- **현재 동작(이슈 내용)**: `GET/PUT /v1/videos/{rawSn}/privacy-meta`(`VideoPrivacyMetaController`/`VideoPrivacyMetaService`, V161·V163)는 이번 반전의 **핵심 신설물**인데 E 클러스터 어디에도 케이스가 없다(`grep -n 'videos/.*privacy-meta' docs/test-cases/` → 0건). 이번 검증에서 실동작으로 확인된 것만 해도: 프리필+`...Source` 3필드(MANUAL/DERIVED), 전체 교체 PUT, `Y/N` 화이트리스트 400(필드명만 노출), 미존재 404, WORKER IDOR 403 / 미인증 401, 신고 구간 412, APPROVED 후 수정 시 `TaskModifiedEvent(videoLevel)` → export 재생성 후 통지(로그 `frames=0 videoLevel=1 reExport=true`), `LS_TASK_EVENT_LOG(PRIVACY_META_UPDATE, rsn='영상 개인정보 선언 변경')` 감사 1행, 파생영상 계승(raw164 가 부모 raw101 의 `N/Y/Y` 를 계승), 잠금 순서(`raw 행락 → advisory`, `FOR SHARE` 금지 — `LockOrderGuardTest` 정적 가드).
- **재현/확인 경로**: 위 항목 전부 이번 회차에 실호출로 관측(본 문서 §1·§3).
- **영향**: 반전의 절반(영상 축)이 회차 검증 대상 밖에 있어, 회귀가 나도 카탈로그로는 잡히지 않는다.
- **수정 방향(제안)**: E-9 다음에 **E-10 "영상 개인정보 메타(VideoPrivacyMetaService — 영상 단위)"** 절을 신설하고 위 12축을 케이스화(ID 는 TC-META-060~ 대역 권장). 본 파트는 담당 라인범위 밖이라 신설하지 않고 제안만 한다.


---

### LOW

### [E-ISSUE-22] TC-AUG-057 — "부모 프레임 0건" 분기가 라이브 도달 불가능한 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 케이스는 실동작으로 검증 가능해야 한다. 불가능하면 그 사실이 케이스에 명시돼 회차마다 "확인 못 함"이 반복되지 않아야 한다(1차 E-ISSUE-21 과 동일 유형).
- **현재 동작(이슈 내용)**: `AugmentResultService.evaluateParentGate`(379-398)는 부모를 origin 프레임에서 역산한다.
  ```java
  LsDataSrc originSrc = srcRepository.findById(aug.getSrcSn()).orElse(null);      // 없으면 "origin frame not found"
  LsDataRaw parentRaw = videoRepository.findByRawSnForUpdate(originSrc.getRawSn()).orElse(null);
  ...
  if (srcRepository.findByRawSnOrderByFrameNoAsc(parentRaw.getRawSn()).isEmpty()) return ParentGate.fail("parent has no frames");
  ```
  `findByRawSnOrderByFrameNoAsc` 는 필터 없는 파생 쿼리(`LsDataSrcRepository.java:17`)이므로, `originSrc` 가 존재하는 한 그 행 자신이 최소 1건 반환된다 → 이 분기는 실행될 수 없다. 실제로 `de_ident_yn='Y'` 이면서 프레임 0건인 영상(raw_sn 64~69)이 DB 에 존재하지만, 그 영상에 매달 origin 프레임 자체가 없으므로 aug 행을 만들 수 없다.
- **재현/확인 경로**: `SELECT r.raw_sn FROM ls_data_raw r WHERE r.de_ident_yn='Y' AND NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.raw_sn=r.raw_sn);` → 프레임 0건 영상은 있으나 `LS_DATA_AUG.SRC_SN` 이 가리킬 프레임이 없다.
- **영향**: 기능/보안 영향 없음(fail-closed 다층 방어로는 정당). **카탈로그의 검증 불가 항목**이라 회차마다 판정이 흔들린다.
- **수정 방향(제안)**: 코드는 그대로 둔다. 카탈로그 기대결과에 "라이브 도달 불가 — mock 단위테스트(`AugmentResultServiceTest.부모_프레임이_없으면_실패로_확정되고_영상과_프레임러너_미트리거`)로만 검증"을 명시한다 → **이번 회차에 정정 반영함**.


### [E-ISSUE-23] TC-AUG-065 — IP allowlist fail-closed(403)를 현재 환경에서 실동작 검증할 수 없다
- **심각도**: LOW (환경 제약)
- **기대 동작(기대효과)**: `webhook.genai.allowed-ip-cidrs` 미설정/`none` 이면 콜백이 전건 403 이어야 한다(VLM 과 반대로 fail-closed). 이는 무서명 웹훅의 1계층 방어라 실동작 확인이 바람직하다.
- **현재 동작(이슈 내용)**: 코드는 명확하다 — `GenAiWebhookIpAllowlist.java:44-55` 가 빈값/`none` 이면 `allowed=List.of()` 로 두고 `isAllowed()`(63-77)가 **항상 false** 를 돌려준다. 그러나 로컬 형상은 `WEBHOOK_GENAI_ALLOWED_IP_CIDRS=0.0.0.0/0`(전면 허용 명시)이라 403 경로가 실행되지 않는다. 설정 변경 + 재기동은 §10-1(검증 중 코드·설정 불변) 위반이라 시도하지 않았다.
  다만 **필터가 이 경로에 실제로 배선돼 있다**는 사실은 확인했다:
  ```
  WARN k.c.c.a.c.security.HmacWebhookFilter - [Webhook] downstream auth rejected ip=172.20.0.1 path=/api/v1/genai/callback status=401
  ```
- **재현/확인 경로**: `docker exec klid-backend env | grep WEBHOOK_GENAI_ALLOWED_IP_CIDRS` → `0.0.0.0/0`.
- **영향**: 검증 커버리지 공백. 기능 영향 없음(커버: `GenAiWebhookIpAllowlistTest`).
- **수정 방향(제안)**: 다음 회차에 **allowlist 를 `none` 으로 둔 임시 프로파일**(별도 compose override)로 기동해 403 을 1회 실측하고, 그 뒤 원복한다. 코드 수정 불요.


### [E-ISSUE-24] TC-AUG-100 — 100장 청크 분할을 실동작으로 검증할 데이터가 없다 (최대 프레임 30)
- **심각도**: LOW (환경 제약)
- **기대 동작(기대효과)**: 프레임 250장 영상이 3청크(100/100/50)로 분할되고 `LS_DATA_AUG_JOB` 3행 + `LS_DATA_AUG_JOB_FILE` 250행이 생겨야 한다. 분할은 외부 계약(§4.1 input_files 상한 100)의 핵심이라 실왕복 확인 가치가 높다.
- **현재 동작(이슈 내용)**: DB 최다 프레임 영상이 30건이라(아래 쿼리) 라이브에서는 **항상 1청크**만 나간다. 실측한 위탁은 전부 `inputs=10`.
  ```sql
  SELECT r.raw_sn, count(s.src_sn) FROM ls_data_raw r JOIN ls_data_src s ON s.raw_sn=r.raw_sn
   GROUP BY r.raw_sn ORDER BY 2 DESC LIMIT 3;   -- 18|30, 4|30, 75|30
  ```
  상한 자체(`clampChunkSize` → `Math.min(configured, 100)`)와 `request_id={augIdmpKey}-{jobSeq}` 형식은 정적·라이브 모두 확인됐다.
- **재현/확인 경로**: 위 SQL.
- **영향**: 검증 커버리지 공백. 기능 영향 없음(커버: `AugmentJobSubmitServiceTest` 분할 4건).
- **수정 방향(제안)**: 다음 회차 §3-3 파이프라인 구동 시 **프레임 250장 이상 영상 1건**을 시드에 포함하거나(마킹 간격을 촘촘히), 30초 이상 영상을 사용해 분할 왕복을 1회 실측한다.


### [E-ISSUE-26] [이월·미해소] 앱(KST)과 DB DEFAULT(UTC)의 9시간 시계 드리프트
- **심각도**: LOW
- **기대 동작(기대효과)**: 만료 스윕은 `LocalDateTime.now() - idleTimeoutMinutes` 를 `MDFCN_DT`/`REG_DT` 와 직접 비교하므로 두 값이 같은 시계여야 임계(기본 360분)가 의미를 갖는다.
- **현재 동작(이슈 내용)**: 1차 E-ISSUE-26 그대로다. 이번 회차 실측:
  ```
  DB : show timezone → Etc/UTC ;  current_timestamp → 2026-08-03 16:41:21+00
  APP: 같은 순간 JPA 가 적재한 ls_data_aug_job.reg_dt → 2026-08-04 01:40:15   (Δ = 9h)
  ```
  본 검증에서도 이 함정을 우회하기 위해 시드 INSERT 를 전부 `now() at time zone 'Asia/Seoul'` 로 명시해야 했다(컬럼 DEFAULT 로 두면 생성 즉시 만료 대상이 된다).
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "show timezone; select current_timestamp;"` + `docker exec klid-backend env | grep -i tz` / `Dockerfile:42` `-Duser.timezone=Asia/Seoul`.
- **영향**: 데이터 정합(시각 축). 앱 경로는 항상 `LocalDateTime.now()` 를 명시 대입하므로 현재 운영 흐름에서 관측된 오작동은 없다. 노출은 DB DEFAULT 에 의존하는 경로(Flyway 백필·운영 SQL·수동 INSERT)로 한정된다.
- **수정 방향(제안)**: ①DB 세션/컨테이너 TZ 를 `Asia/Seoul` 로 통일 ②또는 컬럼 DEFAULT 제거로 시각 기록 주체를 앱 하나로 못박음(권장) ③장기적으로 `timestamptz` 검토. ⚠ 변경하지 않았다.


### [E-ISSUE-27] [이월·미해소] `LS_DATA_RAW.DE_IDNTF_YN` 에 소문자 `'y'` 데이터 잔존 — 파생 생성 게이트가 대소문자 민감
- **심각도**: LOW
- **기대 동작(기대효과)**: 비식별 산출물 보유 판정(`LsDataRaw.hasDeidentArtifact()`)이 증강·해상도 파생 생성의 단일 진실원이므로 컬럼 값 도메인이 `Y`/`F`/`N` 으로 닫혀 있어야 한다.
- **현재 동작(이슈 내용)**: 1차 E-ISSUE-24 그대로다.
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE de_ident_yn NOT IN ('Y','F','N');  -- 68 | y
  ```
  판정기는 `"Y".equals(...) || "F".equals(...)` 라 `'y'` 행은 "비식별 산출물 없음"으로 판정돼 증강 콜백이 오면 `ParentGate.FAIL`(REJECTED + dead-letter)로 끝난다. 방향은 fail-closed(안전)지만 운영자에게는 "비식별 완료된 영상인데 증강이 계속 실패"로 보인다. 컬럼에 DB 레벨 CHECK 제약이 없어 같은 오염이 재발할 수 있다.
- **재현/확인 경로**: 위 SQL.
- **영향**: 데이터 정합. 보안 노출 없음(안전한 방향으로 실패).
- **수정 방향(제안)**: ①오염 행 정정 ②`CHECK (DE_IDNTF_YN IN ('Y','F','N'))` 제약 추가 검토(Flyway). ⚠ 본 검증에서 수정하지 않았다.


### [E-ISSUE-28] TC-AUG-108 — 근거 `file:line` 이 실제 실패 기록 주체를 가리키지 않는다 (근거 드리프트)
- **심각도**: LOW
- **기대 동작(기대효과)**: `근거(file:line)` 를 열면 기대결과의 단언(여기서는 "job `FAILED(SUBMIT_FAILED)` + 사유 기록")을 곧바로 대조할 수 있어야 한다.
- **현재 동작(이슈 내용)**: 근거가 `AugmentJobSubmitService.java:339-354` 뿐이었는데, 그 구간은 `onErrorResume` 에서 **핸들러에 위임**만 한다.
  ```java
  outcomeRecorder.onSubmitFailed(event.originAugSn(), augJobSn, jobSeq, jobCount, err);
  ```
  실제 `SUBMIT_FAILED` 마킹은 `AugmentSubmitOutcomeRecorder.java:67-72` → `AugmentJobRecorder.java:84 markSubmitFailed`(조건부 원자 UPDATE — 지각 신호가 콜백 상태를 강등하지 못한다)에 있다. Phase C-3 논블로킹 전환 때 이관된 것이 반영되지 않았다.
- **재현/확인 경로**: `grep -n "onSubmitFailed\|markSubmitFailed" backend/src/main/java/kr/co/cudo/authoring/augment/service/AugmentSubmitOutcomeRecorder.java`
- **영향**: 기능 영향 없음. 다음 회차 검증 비용 + 오판(로직이 사라진 것으로 오인) 위험.
- **수정 방향(제안)**: 근거 컬럼에 두 파일을 추가 → **이번 회차에 정정 반영함**. (그 외 E-3/E-3B 근거 라인은 **전건 실제와 일치** — 1차 E-ISSUE-23(13건 드리프트)은 3차 카탈로그 최신화로 해소됐다.)


### [E-ISSUE-29] TC-AUG-055 ② — "job 은 있으나 aug 행 없음 = 404" 도 도달 불가 (1차 E-ISSUE-21 카탈로그 미반영)
- **심각도**: LOW
- **기대 동작(기대효과)**: 1차(2026-08-01)에 이미 "FK CASCADE 로 도달 불가 — 방어 코드로만 존치" 로 카탈로그를 강등하라는 수정 방향이 나왔다. 카탈로그는 회차 간 대조의 입력 문서이므로 반영돼야 한다.
- **현재 동작(이슈 내용)**: 3차 카탈로그(2026-08-03 근거 전수 재확인 회차)에도 여전히 `② 404` 가 검증 대상처럼 남아 있었다. 실제 스키마:
  ```
  "fk_ldaj_data_aug" FOREIGN KEY (data_aug_sn) REFERENCES ls_data_aug(data_aug_sn) ON DELETE CASCADE
  ```
  aug 행이 지워지면 job 행도 같이 지워지므로 `GenAiCallbackService.java:103-105` 의 `orElseThrow`(404)는 실행될 수 없다.
- **재현/확인 경로**: `docker exec klid-postgres psql -U klid_user -d klid_system -c "\d ls_data_aug_job"` → FK CASCADE 확인.
- **영향**: 기능/보안 영향 없음. 카탈로그 정합성 + 회차 간 판정 재현성.
- **수정 방향(제안)**: TC-AUG-055 기대결과에 ②의 도달 불가 사유를 명시 → **이번 회차에 정정 반영함**.


### [E-ISSUE-65] TC-RESL-049 — Phase C stale 게이트의 `IOException` 폴백 주석이 **이미 폐기된 신고 게이트**를 안전 근거로 든다 (1차 E-ISSUE-63 이월)
- **심각도**: LOW (주석/추적성 — 동작 영향 없음)
- **기대 동작(기대효과)**: fail-open 분기(`stat` 실패 시 통과)의 주석은 "왜 통과시켜도 안전한가"의 **현재 유효한** 근거를 제시해야 한다. 폐기된 게이트를 근거로 들면 다음 사람이 "다른 게이트가 막아주니 괜찮다"고 오판한다.
- **현재 동작(이슈 내용)**:
  ```java
  // ResolutionPersistService.java:355-359
  } catch (IOException e) {
      // stat 실패는 결정적 게이트(①②)가 이미 통과했으므로 보수적으로 통과(로그만). PII 는 ②신고 게이트로 닫힘.
      log.warn("[Video][ResolutionDerivative][C] deident mtime check skipped ...");
  }
  ```
  "②신고 게이트" 는 2026-07-30 에 **제거**됐다(같은 파일 `:312-314` 이 "구 조건 *capturedAt 이후 신고 이력 존재* 는 순수 신고 결합이라 제거됐다"고 명시). 또한 `:344` 의 두 번째 조건 주석이 `// ①` 로 번호가 잘못 붙어 있어(앞선 `:328` 도 `①`) 두 조건이 구분되지 않는다.
- **재현/확인 경로**: 정적. `sed -n '344,359p' backend/src/main/java/kr/co/cudo/authoring/video/service/ResolutionPersistService.java`
- **영향**: 추적성/유지보수. 이 저장소의 "철회된 정책 재시도" 사고 패턴(조상/자손 전파 4라운드)과 같은 뿌리 — 폐기된 정책이 주석에 살아 있으면 되살아난다.
- **수정 방향(제안)**: 주석을 실제 근거로 교체 — *"경로 게이트(①)가 결정적으로 통과했고, 파생 RAW 는 이 시점까지 `deIdntfYn='N'` 이라 미서빙이므로 mtime 미확인을 보수적으로 통과시킨다"*. 두 번째 조건 번호를 `②` 로 정정.


### [E-ISSUE-67] TC-RESL-061 / TC-RESL-066 — `handleFailure` 의 `markRawDataFailed` 가 무가드라 예외 시 고아 RAW 정리(④)가 건너뛰어진다
- **심각도**: LOW
- **기대 동작(기대효과)**: 실패 정리의 모든 단계는 best-effort 이며 "원래 실패를 가리지 않는다"가 이 메서드의 명시 계약(`AsyncResolutionRunner.java:90-91`). 앞 단계 실패가 뒤 단계를 통째로 건너뛰게 하면 안 된다.
- **현재 동작(이슈 내용)**: cleanup(`:128-140`)과 `releaseReservedAug`(`:144-149`)는 각각 try/catch 로 감싸져 있으나 `markRawDataFailed` 는 맨몸이다.
  ```java
  // AsyncResolutionRunner.java:150
  batchTransitionService.markRawDataFailed(newRawSn);
  // :159-164  ← 위에서 예외가 나면 이 블록에 도달하지 못한다
  try { persistService.deleteFailedDerivativeRaw(newRawSn); } catch (RuntimeException re) { ... }
  ```
- **재현/확인 경로**: 정적. 관련 관측 사실 — 파생 RAW 는 `LS_RAW_DATA_STATUS` 행이 없어 매 실패마다 WARN 이 뜬다: `[BatchTransition] raw data status not found rawSn=170 target=FAILED`(정상 폴백 경로이며 결함 아님, 다만 이 WARN 이 실패 로그에 늘 섞여 실제 이상 신호를 가린다).
- **영향**: 관측성/누적. `markRawDataFailed` 가 던지는 상황(락 타임아웃 등)에서 고아 파생 RAW 가 정리되지 않고 남는다. E-ISSUE-23(고아 무한 누적) 재발 경로.
- **수정 방향(제안)**: `markRawDataFailed` 호출도 try/catch 로 감싸고, 실패해도 ④ 로 진행한다. 더불어 파생 RAW 에 대한 `raw data status not found` WARN 은 **파생(ORGNL_RAW_SN != null)일 때 DEBUG 로 낮추거나 메시지에 "파생 — 정상"을 명시**해 실패 로그의 신호대잡음비를 높인다.


### [E-ISSUE-82] TC-EXPORT-042 — 카탈로그 근거(file:line)가 **다른 토글**을 가리킨다 (1차 E-ISSUE-82 미해소분, 이번 회차 정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스의 `근거` 컬럼은 그 단언을 실제로 보장하는 코드 위치를 가리켜야 한다(다음 회차 재검증·수정 작업의 진입점).
- **현재 동작(이슈 내용)**: TC-EXPORT-042("재export 트리거는 control-notify 토글과 무관")의 근거가 `DatasetExportBridge.java:29-30 (자체 토글만 참조)` 였는데, 이 라인은 `authoring.dataset-export.enabled` 토글(= TC-EXPORT-013 의 근거와 **동일 라인**)이며 `authoring.control-notify.enabled` 와의 무관성을 보장하는 코드가 아니다. 실제 보장 지점은 ①`TaskModifiedAccumulateListener.java:27-36`(조건부 어노테이션 0건 = 항상 활성) ②`ControlNotifyDebouncer.java:44-55`(HIGH-E javadoc "이 빈은 control-notify.enabled 로 게이팅하지 않는다") + `:85`(`@Component`) + `:334-357`(`send()` 가 `notifyService` null 여부와 무관하게 `runReExportThenNotify` 호출) ③`ControlNotifyEventListener.java:27`(반대로 통지 리스너만 토글 종속).
- **재현/확인 경로**: `docs/test-cases/E-augment-resolution-export-meta.md` TC-EXPORT-042 행과 위 파일들을 대조. 1차 ISSUES.md `E-ISSUE-82` 에 동일 지적이 있었으나 카탈로그에 반영되지 않은 채 남아 있었다.
- **영향**: 기능 영향 없음. 카탈로그 자체의 정합성 결함 — 근거를 따라가면 다른 토글을 검사하게 되어 **잘못된 PASS/FAIL 판정**을 유발할 수 있다(3차 검증 착수 시 실제로 혼선 발생).
- **수정 방향(제안)**: **이번 회차에 카탈로그를 직접 정정 완료**(§3-1). 추가 조치는 `## 변경 이력` 표에 회차 행 반영뿐. **프로덕션 코드 변경 불필요.**


### [E-ISSUE-102] EnvironmentMetaController Swagger 설명이 실제 재export 동작과 모순된다
- **심각도**: LOW (문서 전용 결함 — 실동작에는 영향 없음, API 소비자 오인 위험)
- **기대 동작(기대효과)**: OpenAPI/Swagger 설명은 실제 서버 동작과 일치해야 한다. TC-META-008/017이 검증하는 "APPROVED 후 촬영환경 수정 = export 새 버전 전량 재생성"은 실제로 그렇게 동작한다(이번 회차 실동작으로 재확인, v5 export에 새 촬영환경 값 정확 반영).
- **현재 동작(이슈 내용)**: `EnvironmentMetaController.java:66-68`의 PUT API `@Operation` description이 다음과 같이 **정반대 사실**을 적고 있다:
  > *"검수 완료 후 수정 시 동결 스냅샷만 재동결(데이터마트 뷰에 최신값 반영)되고 관제 TASK_MODIFIED(META_UPDATED) 통지가 발행된다. **편집은 export 파일 재생성을 트리거하지 않으며**(라벨 수정과 동일 정책), export 폴더는 다음 검수 승인 시점에 전량 재산출된다."*

  하지만 실제 서비스 코드(`EnvironmentMetaService.java:120-127`)는 `TaskModifiedEvent(rawSn, null, ChangeType.META_UPDATED, actorNo, **true**)`로 `exportRegenerated=true`를 실어 발행하며, 이는 CLAUDE.md의 "★ export 재생성·동기화 정책(2026-07-27 확정)"과 정확히 일치하는 현재 정책이다. 즉 컨트롤러의 Swagger 설명이 2026-07-27 이전 폐기된 구 정책("재생성 미트리거")을 그대로 남겨둔 상태다.
- **재현/확인 경로**:
  ```bash
  # Swagger UI에서 PUT /v1/videos/{rawSn}/environment-meta 설명 확인 — "export 파일 재생성을 트리거하지 않으며" 문구
  # 실제로는 재생성됨:
  curl -X PUT localhost:18081/api/v1/videos/101/environment-meta -H "Authorization: Bearer $REV" \
    -d '{"weather":"비","timeOfDay":"NGT","season":"WINTER"}'
  # 이후 docker logs klid-backend | grep DatasetExport → "async re-export(+notify) starting rawSn=101 forceRegenerate=true" 확인됨
  ```
- **영향**: 기능 영향 없음. API 문서를 신뢰하는 외부/내부 개발자(FE, 관제 연동 담당)가 "촬영환경만 고치면 export 파일은 안 바뀐다"고 오인해 별도 재산출을 기다리거나 잘못된 가정으로 연동 코드를 짤 위험(정보 정확성 문제).
- **수정 방향(제안)**: `EnvironmentMetaController.java`의 `update()` `@Operation` description에서 "편집은 export 파일 재생성을 트리거하지 않으며... 다음 검수 승인 시점에 전량 재산출된다" 문장을 삭제하고, "검수 완료 후 수정 시 export 폴더도 새 버전(v{n+1})으로 전량 재생성되며, 재생성 성공 후 통지가 발송된다"로 교체.


### [E-ISSUE-125] 문서/주석 드리프트 — 폐기된 구 정책을 참조하는 서술 2건
- **심각도**: LOW
- **기대 동작(기대효과)**: Swagger·코드 주석이 확정 정책과 같은 사실을 말해야 한다(이 저장소의 "주석이 정책 갱신에 뒤처짐" 반복 패턴).
- **현재 동작(이슈 내용)**:
  1. `FramePrivacyMetaController.java:57-58,74-75` Swagger 설명이 여전히 *"없으면 **파생값**(프리필)을 반환", "수동값이 삭제되어 **파생값으로 폴백**"* 이라고 기술 — 실제 폴백 원천은 파생이 아니라 `ExportPrivacyPolicy` 비식별 기본상수다(클래스 javadoc 은 이미 정정돼 있어 **같은 파일 안에서 서로 다른 말**을 한다).
  2. `AugmentExtractPersist.java:104-107` 주석 *"두 컬럼에 같은 값을 넣으면 … export orgnl 벌이 `anonymity="N"` 으로 오표기된다"* — 반전 이후 `ORIGINAL` 은 `anonymity` 를 **판정하지 않고 항상 null** 이라 이 근거는 성립하지 않는다(컬럼 분리 자체는 뷰 불변식 때문에 여전히 유효).
- **재현/확인 경로**: 위 file:line Read.
- **영향**: 후속 작업자가 "파생 폴백"을 되살리거나, orgnl anonymity 를 근거로 잘못된 결론을 낼 수 있다.
- **수정 방향(제안)**: Swagger `description` 을 "미저장 필드는 비식별 기본상수(Y/N/N) 프리필"로, 증강 주석의 근거를 "마트 뷰의 두 경로 상이 불변식"만 남기고 anonymity 문장 제거.


### [E-ISSUE-127] E-9 전 행 — 근거 `file:line` 드리프트 (정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 정확도가 이 카탈로그의 존재 이유다.
- **현재 동작(이슈 내용)**: 15행 중 **14행**의 라인 범위가 어긋나 있었다(신고 게이트 추가·프리필 재작성으로 서비스가 대폭 이동). 주요 예: TC-META-030 `66-70`→`77-80,190-196`, TC-META-031 `73-78`→`96-103`, TC-META-033 `85-88`→`93-96`, TC-META-035 `100-137`→`124-166`, TC-META-036 `101-118`→`137-142`, TC-META-037 `119-123`→`144-150`, TC-META-038 `99`→`124`, TC-META-039 `126-133,151-156`→`153-160`, TC-META-040 `104-107`→`111-116`·`19-25`→`20-25`, TC-META-041 `66-75`→`77-80,96-103`, TC-META-043 `151-156`→`177-182`, TC-META-044 `278-289`→`284-289`·`104-115`→`109-113`. 또 `NiaJsonBuilder.java` 는 경로가 `dataset/export/**json**/NiaJsonBuilder.java` 이고 개인정보 3필드는 `150-153` 이 아니라 `158-160` 이다.
- **재현/확인 경로**: `grep -n` 로 각 심볼 위치 확인(본 문서 작성 시 전 행 수행).
- **영향**: 근거 추적 실패 → 다음 회차 재검증 비용 증가.
- **수정 방향(제안)**: **정정 완료**(E-9 절 전 행 재작성).



---

## E클러스터 판정 집계 (병합 담당자 산출 — §7 표기 규칙 준수, 각 파트 원본 표 재합산)

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | 219 | E-1/E-2(part1) 43 + E-3/E-3B(part2) 42 + E-4(part3) 19 + E-5(part4) 37 + E-6(part5) 23 + E-7/E-8(part6) 37 + E-9(part7) 18 |
| FAIL | 2 | E-4(part3) TC-RESL-012 1건 + E-5(part4) TC-RESL-041 1건 — 나머지 전 파트 0건 |
| PARTIAL | 8 | E-3/E-3B(part2) 4건(TC-AUG-057·065·100·120) + E-4(part3) 1건(TC-RESL-013) + E-5(part4) 3건(TC-RESL-054·062·066) — 나머지 0건 |
| BLOCKED | 0 | |
| 확인필요 | 0 | |
| **검증 대상 계(폐기 제외 분모)** | **229** | 43(E-1/E-2)+46(E-3/E-3B)+21(E-4)+41(E-5)+23(E-6)+37(E-7/E-8)+18(E-9). PASS+FAIL+PARTIAL(219+2+8)과 정확히 일치 |
| N/A(폐기, 분모 별도) | 27 | E-1(part1) 4건(TC-AUG-003/012/013/014) + E-3/E-3B(part2) 5건(TC-AUG-054/056/058/059/062) + E-5(part4) 1건(TC-RESL-047) + E-5B(part4, 별도 폐기절) 16건(TC-RESL-080~095) + E-8(part6) 1건(TC-META-009) |
| **표 행 실측 총계** | **256** | `grep -cE '^\| *~*TC-' docs/test-cases/E-augment-resolution-export-meta.md` 실측(229 검증대상 + 27 폐기) |

- PASS율 = 219/229 = **95.6%**. PASS+PARTIAL(사실상 통과) = 227/229 = **99.1%**.
- ⚠ **파트별 자기보고 요약표와의 불일치 경고** — E-part1(E-1/E-2) 자체 요약행은 "PASS 42"로 적었으나 케이스별 결과표를 직접 재합산하면 43(19+24)이 정확하다(part1 자신의 요약 표 내부 계산 오류, 상세는 E-part1.md 판정 요약 절 대조). 이 병합에서는 **파트별 요약 프로즈가 아니라 케이스별 원본 표를 재합산한 값**을 정본으로 채택했다(지시서 §3 준수).
- **신규/이월 FAIL 2건**: TC-RESL-012(1차 E-ISSUE-41 이월, 프레임 해상도 실측 실패가 400 아닌 500) · TC-RESL-041(E-ISSUE-61, 파생 프레임 목적파일명 충돌 — 본 회차 신규 실측 FAIL, HIGH).
- **PARTIAL 8건 중 다수가 "라이브 도달 불가·환경 제약"류**(TC-AUG-057/065/100, TC-RESL-013)이며, 실제 데이터 정합 결함은 TC-RESL-054/062/066(E-ISSUE-64/62/63, MEDIUM) 3건과 TC-AUG-120(E-ISSUE-21, MEDIUM, 부모↔파생 메타 불일치)이다.
- **✅ 1차 CRITICAL/HIGH 해소 확인**: 1차 D-ISSUE-61(CRITICAL, export 실패 시에도 TASK_COMPLETED/MODIFIED 오발송) · 1차 E-ISSUE-81(HIGH, PARTIAL export가 `V_COMPLETED_VIDEO` 뷰에서 배제) 양쪽 모두 이번 회차 실동작으로 해소 재확인(상세는 위 "★★ 이번 회차 최우선 확인사항" 4번 및 E-part5 §2 참조).
- **카탈로그 정정**: `docs/test-cases/E-augment-resolution-export-meta.md`를 7개 파트가 나눠 정정 — **총 43건**(기존 케이스 근거·기대결과 정정 35건 + 신규 케이스 8건: TC-AUG-044~048 5건 + TC-META-045~047 3건). `git diff` 실측(파트별 자기보고 합산이 아니라 실제 diff 대조로 산출 — 파트별 자기보고 총합은 부분적으로 표기 오차가 있어 diff 실측을 정본으로 채택). 신규 폐기 0건(이번 회차는 기존 폐기 표기 재확인뿐).
- **총 케이스수 갱신**: 파일 헤더 표기 "232 케이스"는 이미 2026-08-03 이전 시점(카탈로그 changelog round 3, 이번 검증 세션과 무관한 사전 커밋)부터 실제 행수(248, `git show HEAD:...`)와 어긋나 있던 잔존 드리프트였다. 이번 회차 신규 8건 추가로 실제 행수는 248→**256**이 됐고, 헤더 표기도 256으로 정정했다(E-part7이 제안한 "232→235"는 자기 파트 범위만 반영한 부분추정치라 실측과 달라 채택하지 않았다).

## F클러스터

> 심각도순(CRITICAL→HIGH→MEDIUM→LOW) 정렬. **3차 신규 CRITICAL·HIGH 결함 0건** — 대신 2차 HIGH 3건 전부가 이번 회차 실동작 반증으로 **해소 확인**됐다(아래 최우선 강조 블록). 3차 신규/이월 결함은 전부 MEDIUM 이하.

### ★★★ 2차 HIGH 이슈 3건 — 전건 해소 확인 (맨 앞 강조, Critical)

#### [해소 확인 #1] 2차 F-ISSUE-01 — `GET /v1/portal/datamart/labels` APPROVED·비식별신고 게이트 전무(CWE-862/639/359) → **완전 해소**
- **2차 결함 요지**: 이 엔드포인트에만 게이트가 복제 누락돼 PORTAL_USER 가 rawSn 하나로 미승인(PENDING/REJECTED/FAILED)·신고구간(`DE_IDNTF_YN='F'`) 영상의 라벨 좌표를 전건 열람할 수 있었다.
- **3차 해소 확증(F-part2 §0, 실동작)**: rawSn 6종(PENDING 7·REJECTED 5·FAILED 6·미존재 999999·신고구간 900·정상 APPROVED 4)에 PORTAL_USER 토큰으로 직접 요청 → PENDING/REJECTED/FAILED/미존재 = **403**(미존재·미승인 응답 완전 동일 = 존재 오라클 차단, CWE-209 겸 방지), 신고구간 = **412**, 정상만 **200**. 수정 배선: `PortalLabelService.java:117-126` → `requireActor` → `isExposedToDatamart` 403 → `accessGuard.requireNotUnderDeidentReport` 412 → **그 다음에야** 라벨 조회(`:130`) — 거부될 요청은 **라벨 풀스캔조차 하지 않는다.**
- **반증(우회 시도) 결과**: `rawSn` 누락/비정수/`page`·`size` 극단값으로 게이트를 건너뛰는 조기 return 경로 탐색 — **우회 경로 미발견**(게이트가 clamp/subList 계산보다 코드상 앞).
- **회귀 가드**: `PortalUserLabelServiceTest`(미승인_PENDING_영상의_datamart_라벨조회는_403이다 / rawSn이_존재하지_않으면_예외없이_403이다 / 비식별신고구간_영상의_datamart_라벨조회는_412이다 / 신고_해제_후_datamart_라벨조회는_다시_200으로_복원된다) + backend baseline 5,203건 전건 통과.
- **판정**: TC-PORTAL-039·TC-PORTAL-051 PASS로 3차 카탈로그에 반영됨.

#### [해소 확인 #2] 2차 F-ISSUE-64 — 라벨 body-size 필터 URL 인코딩 우회(CWE-436) → **완전 해소**
- **2차 결함 요지**: `PortalLabelBodySizeFilter` 가 원시 URI 를 자체 정규식으로 판정해 `%6Cabels` 처럼 한 글자만 퍼센트 인코딩해도 2MB 본문 상한·chunked 가드가 통째로 무력화됐다(우회 시 200 으로 라벨이 실제 교체됨).
- **3차 해소 확증 — 양쪽 파트(F-part3 §0, F-part4 F-7 TC-PORTALUP-051) 독립 재확인**:
  - 라벨 PUT(`/v1/portal/uploads/frames/1/labels`, 3.6MB body) 8변형(정규·`%6Cabels`·`label%73`·trailing slash·`./`·경로변수 인코딩·이중인코딩·matrix) — **전부 413(우회 6건) 또는 401(컨트롤러 미도달 2건), 200 우회 0건**. chunked 재현도 `/labels`·`/%6Cabels`·`/labels/` **전부 411**(2차에는 인코딩 경로가 200 으로 실제 교체됨).
  - 사용자 라벨 POST(`/v1/portal/user-labels`) 6변형 + 라우팅 대조(작은 body 로 컨트롤러 도달 여부 선확정) — 도달하는 3개 변형(`user-labels`·`%75ser-labels`·`./user-labels`) 전부 huge body 에서 **413**. "컨트롤러엔 도달하는데 필터만 스킵" 조합 **0건**.
  - 추가 적대 변형(`X-HTTP-Method-Override`·form `_method`·`%2F`·공백·null byte 인코딩) 전부 405/400/404 — **200 우회 신호 0건**.
  - 현행 구현: `ServletRequestPathUtils.parseAndCache(request).pathWithinApplication()` + `PathPattern`(trailing slash 변형 패턴 동시 등록) — MVC 라우팅과 **동일 판정 기준**으로 전환, 자체 디코딩 루프 제거(이중 인코딩에서도 MVC 와 어긋나지 않음).
- **회귀 가드**: `PortalLabelBodySizeFilterTest` 19 테스트(퍼센트 인코딩·chunked·matrix·trailing slash·context-path·servlet-path-prefix 포함), backend baseline 실패 0건.
- **잔여**: 없음(양 파트 공통 결론).

#### [해소 확인 #3] 2차 F-ISSUE-41/42 — 포털 SAM2 노출 지속(ADR-013 위반) → **완전 해소(코드·배포본·실동작 3중 확증)**
- **2차 결함 요지**: `PortalSam2Controller`/`PortalSam2Service` 엔드포인트가 살아 있고(F-ISSUE-41), FE 도구바가 SAM2(AI 분할/추적)·스켈레톤을 노출하며 테스트가 그 노출을 기대값으로 고정(F-ISSUE-42)돼 있었다.
- **3차 해소 확증(F-part1)**:
  1. **소스**: `PortalSam2Controller.java`(91줄)·`PortalSam2Service.java`(294줄)가 커밋 `dcdbb827` 에서 삭제(`git log --diff-filter=D` 확인). `grep -rn "PortalSam2" backend/src` → main 0건(테스트 파일 5개뿐).
  2. **배포본**: `docker cp klid-backend:/app/app.jar` → `unzip -l | grep portal` → `PortalSam2*.class` 부재.
  3. **실동작**: 판별자(path≠body srcSn) 로 대조 — 포털 경로 **404**(핸들러 부재) vs 내부 경로 **400**(핸들러 존재, srcSn 불일치 검증). 경로 변형 **10종 전부 404**(대문자·trailing slash·퍼센트 인코딩·이름 변경 포함) — 되살아난 흔적 없음.
  4. **FE**: `PORTAL_HIDDEN_TOOLS = [SAM_SEGMENT, TRACK, KEYPOINT]`(`features/label/types.ts:215-219`) 단일 소스를 도구바·단축키 훅·단축키 안내 3곳이 공유. 컨테이너 `/app/src` 실물도 워크트리와 동일 소스.
- **회귀 가드**: `PortalSam2RemovedTest`(5케이스) + `DarkToolbar.test.tsx` + `useLabelingShortcuts.test.tsx` + `ShortcutCheatSheet.test.tsx` + `LabelingPagePortalRestrictions.test.tsx`.
- **UNCERTAINTIES.md #1 갱신 후보(병합 단계에서는 문서를 직접 고치지 않음)**: F-part1 은 "1차/2차 결함 → 3차(2026-08-03, `dcdbb827`)에서 BE 삭제 + FE 3중 게이팅으로 **✅ 해소**"로 원본 갱신을 제안했다. 이 저장소의 '철회된 정책 재시도' 차단 관례에 맞춰 "포털 SAM2 를 되살리지 말 것"을 **★확정 정책 절로 승격 검토**하는 것도 함께 제안한다. **다음 회차에서 UNCERTAINTIES.md 갱신을 검토할 것** — 이번 병합 에이전트는 UNCERTAINTIES.md 를 직접 수정하지 않았다(작업 범위 밖).

---

### MEDIUM

### [F-ISSUE-01] TC-PORTAL-060 / 061 / 062 — 카탈로그 ID 충돌: 같은 파일 안에서 `TC-PORTAL-060~062` 가 F-3 과 F-4 에 중복 채번됨
- **심각도**: MEDIUM *(카탈로그 정합성 결함 — 프로덕션 결함 아님)*
- **기대 동작(기대효과)**: 케이스 ID 는 카탈로그 전역에서 유일해야 한다. 회차 간 대조(`ISSUES.md` ↔ 결과표), 수정 커밋의 "어느 케이스를 고쳤나" 추적, 통과율 집계가 전부 ID 를 키로 삼기 때문이다. 중복되면 2차→3차 대조에서 **다른 케이스의 판정이 서로를 덮어쓴다.**
- **현재 동작(이슈 내용)**: `docs/test-cases/F-portal.md` 한 파일 안에서 세 ID 가 두 번씩 정의돼 있다.
  ```
  F-3 (:71) | TC-PORTAL-060 (신규) | **좌표 개수 상한(CWE-770)** ...
  F-3 (:72) | TC-PORTAL-061 (신규) | **저장 경로 비식별 신고 게이트** ...
  F-3 (:73) | TC-PORTAL-062 (신규) | **저장 per-user 속도 제한** ...
  F-4 (:98) | TC-PORTAL-060 (갱신) | 포털 SAM2 분할 엔드포인트 **미제공 확정** ...
  F-4 (:99) | TC-PORTAL-061 (갱신) | 포털 SAM2 추적 엔드포인트 **미제공 확정** ...
  F-4(:100) | TC-PORTAL-062 (갱신) | 포털 경로에 SAM2 핸들러 매핑 0건 ...
  ```
  F-3 절 말미(`:83`)의 자기 규칙과도 모순된다 — *"ID 채번 주의: TC-PORTAL-040~050 이 이미 사용 중이라 3차 QA 신규 케이스는 039 + 051~057 로 채번했다"* 라고 적어 두고 실제로는 058~062 까지 채번해 F-4 의 기존 060~062 를 침범했다.
- **재현/확인 경로**: `grep -n "TC-PORTAL-06[012]" docs/test-cases/F-portal.md` → 각 ID 가 2행씩 출력.
- **영향**: 회차 대조·집계 오류. 3차에서 F-3 담당 에이전트와 F-4(본 파트) 담당 에이전트가 동일 ID 로 서로 다른 판정을 기록하면 병합 시 충돌한다.
- **수정 방향(제안)**: F-3 의 058~062 를 미사용 대역(예: 080~084)으로 재채번하고, F-3 머리말의 채번 주의 문구를 실제 사용 대역으로 갱신한다. ⚠ 재채번은 F-3 소유 구간이라 F-part1 에서는 수정하지 않았고, F-4 머리말에 충돌 경고 블록만 추가했다(**병합 단계에서 확인 — F-3 담당(F-part3)이 채번 주의 노트만 갱신하고 실제 재채번은 미수행**. 다음 회차 정리 대상으로 이월).

### [F-ISSUE-21] TC-PORTAL-021 — 데이터마트 영상 목록의 `totalElements`/`totalPages` 오보 + 중간 빈 페이지(2차 F-ISSUE-02 미해소 이월)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털 홈 목록은 "진입 가능한 영상"만 세어야 한다. 프레임 0건 영상은 라벨링 진입 대상 프레임이 없어 의도적으로 제외되므로(서비스 주석 "MED 방어"), 페이지 메타(`totalElements`/`totalPages`)도 그 기준이어야 FE 페이저가 실제 데이터와 맞고, **뒤 페이지 영상이 사용자 눈에서 사라지지 않는다.**
- **현재 동작(이슈 내용)**: 필터는 페이지 **content 에만** 적용되고 total 은 필터 이전 값을 그대로 쓴다.
  ```java
  // PortalLabelService.java:177-189
  List<DatamartVideoResponse> content = rows.stream()
          .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)   // ← 페이지 안에서만 제외
          ...
  // 제외로 인해 페이지 size 보다 적어질 수 있으나 totalElements 는 원본(게이트 후) 기준 유지.
  return new PageImpl<>(content, pageable, page.getTotalElements());   // ← 30 (실제 노출 20)
  ```
  실측(`size=5`, 3001 토큰):
  ```
  page=0 total=30 pages=6 n=5  [880210,880200,159,115,110]
  page=1 total=30 pages=6 n=3  [101,94,81]        ← 5건 요청했는데 3건 (2건 조용히 증발)
  page=2 total=30 pages=6 n=5
  page=3 total=30 pages=6 n=5
  page=4 total=30 pages=6 n=0  []                 ← ★중간 빈 페이지
  page=5 total=30 pages=6 n=2  [18,4]
  ```
  전 페이지 합 20건 ≠ `totalElements` 30. `size=100` 으로 요청하면 우연히 20 으로 보이는데, 이는 서비스가 고친 게 아니라 **Spring `PageImpl` 이 "마지막 페이지면 offset+content.size() 로 total 을 재계산"** 하는 보정이 걸린 것뿐이다(작은 size 에서는 보정이 안 걸려 오보가 그대로 노출된다) — 오진 주의.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL","userNo":"3001"}' | jq -r .data.token)
  for p in 0 1 2 3 4 5; do curl -s -H "Authorization: Bearer $TOK" \
      "localhost:18081/api/v1/portal/datamart/videos?page=$p&size=5" \
    | jq -c '{p:.data.number,total:.data.totalElements,n:(.data.content|length)}'; done
  ```
  ```sql
  -- 프레임 0건 APPROVED 영상(= 제외 대상) 확인
  SELECT r.raw_sn FROM ls_data_raw r JOIN ls_raw_data_status s ON s.raw_data_id=r.raw_sn
   WHERE s.data_stts_cd='APPROVED'
     AND NOT EXISTS (SELECT 1 FROM ls_data_src d WHERE d.raw_sn=r.raw_sn);
  -- → 8,9,15,43,48,55,66,67,146,147 (10건)
  ```
- **영향**: 기능/데이터정합. ①FE 페이저가 실제보다 많은 페이지를 그려 빈 화면이 노출된다 ②**한 페이지가 통째로 비면 사용자는 "끝"으로 오인해 뒤 페이지(rawSn 18·4)의 영상에 접근하지 못한다**(무한스크롤 구현이면 더 확실히 멈춘다) ③"검수 완료 영상 N건" 카운트가 20% 이상 부풀려 보고된다. 보안 영향은 없다(노출되는 영상 자체는 APPROVED 게이트 통과분).
- **수정 방향(제안)**: ⚠ 구현하지 않음. 필터를 **쿼리로 내린다** — `findAllWithReviewStatus` 에 `AND EXISTS (SELECT 1 FROM LsDataSrc d WHERE d.rawSn = v.rawSn)` 를 추가하면 count 쿼리에도 같은 조건이 적용돼 total·page 수·페이지 채움이 동시에 정합해진다(현행 in-memory `filter` 는 제거). 프레임 유무는 `LS_DATA_SRC(RAW_SN)` 인덱스로 판정되므로 EXISTS 비용은 낮다. 대안(서비스에서 total 만 재계산)은 **중간 빈 페이지를 못 고치므로 부적절**하다.

### [F-ISSUE-61] TC-PORTALUP-004 — `files` 파트 부재 업로드가 400이 아니라 500(스택트레이스 로깅) — 2차 F-ISSUE-61 이월, 미해소
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 업로드할 이미지가 없는 요청은 클라이언트 입력 오류(400 INVALID_INPUT)로 거부되어야 한다. 5xx로 나가면 클라이언트가 서버 장애로 오인하고, `GlobalExceptionHandler`의 최종 `Exception` 핸들러가 ERROR 레벨 스택트레이스를 남겨 내부 필터 체인이 로그에 노출된다(CWE-209). 모니터링 5xx 알람도 오염된다.
- **현재 동작(이슈 내용)**: `MissingServletRequestPartException`이 `GlobalExceptionHandler`(`backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java`)에 여전히 매핑돼 있지 않다(76-93행에 `MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException` 핸들러만 존재, `MissingServletRequestPartException` 없음. 241행 이후 `Exception.class` catch-all(273-278행)로 낙하).
  - 실측(2026-08-04, 3차 재검증):
    ```
    curl -H "Authorization: Bearer $PORTAL_JWT" -F "dummy=x" http://localhost:18081/api/v1/portal/uploads/images
    → HTTP 500 {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}
    backend log: ERROR ... GlobalExceptionHandler - [Exception] unhandled exception
      org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.
    ```
  - `PortalUploadService.uploadImages`의 빈 목록 가드(`files == null || files.isEmpty()` → 400, 87-89행)는 컨트롤러 바인딩 단계에서 예외가 던져져 도달조차 하지 않는다.
- **재현/확인 경로**:
  ```bash
  curl -s -w "\nHTTP:%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -F "dummy=x" http://localhost:18081/api/v1/portal/uploads/images
  # → 500 INTERNAL_ERROR (기대: 400 INVALID_INPUT)
  ```
- **영향**: CWE-209(스택트레이스 ERROR 로깅) + 오류 분류 오염(4xx→5xx). 인증 필요 경로라 외부 무인증 공격면은 아니나 PORTAL_USER 누구나 5xx 알람을 유발 가능.
- **수정 방향(제안)**: `GlobalExceptionHandler`에 `@ExceptionHandler(MissingServletRequestPartException.class)`(또는 상위 `ServletRequestBindingException`)를 추가해 `ErrorCode.INVALID_INPUT`(400)으로 매핑. 컨트롤러 테스트에 "files 파트 없는 업로드 → 400" 케이스 추가(현재 `PortalUploadControllerTest`에 이 케이스 없음, 재확인함).

### [F-ISSUE-62] TC-PORTALUP-014 — 프레임 INSERT가 커밋 시점에 실패하면 업로드 파일이 고아로 영구 잔존 — 2차 F-ISSUE-62 이월, 미해소
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `uploadImages`는 all-or-nothing이다(클래스 Javadoc `#4`). DB가 롤백되면 디스크에도 아무것도 남지 않아야 한다.
- **현재 동작(이슈 내용)**: `LsPortalUldFrme`(`backend/src/main/java/kr/co/cudo/authoring/portal/entity/LsPortalUldFrme.java:36-39`) PK 전략이 여전히 `GenerationType.SEQUENCE`(`allocationSize=50`)라 `frmeRepository.save()`(`PortalUploadService.java:133-134`)는 INSERT를 큐에만 넣고 실제 실행은 트랜잭션 커밋 flush 시점에 일어난다. 그 실패는 `uploadImages`의 `try/catch`(138-147행) **밖**이라 `rollbackFiles(writtenThisRequest)`가 실행되지 않는다. 코드 변경 없음(2차 실측 재확인, 라인도 동일).
- **재현/확인 경로**: 2차와 동일(임시 CHECK 제약으로 커밋 시점 INSERT 실패 유발 → 파일 잔존, DB는 정상 롤백). 회수 스윕(`PortalUploadSweepJob`)도 `frames/{uldSn}/`(영상 프레임)만 정리하고 `portal/images/`(이미지 업로드 원본)는 대상이 아님(`PortalUploadSweepJob.java:91-114`, 여전히 미대상).
- **영향**: CWE-459(Incomplete Cleanup) + 저장소 고갈(OWASP API4). 소유 레코드 없는 사용자 업로드 원본 이미지가 무기한 잔존.
- **수정 방향(제안)**: 2차와 동일 — ①`TransactionSynchronization.afterCompletion(STATUS_ROLLED_BACK)` 등록 ②`saveAndFlush`로 INSERT를 메서드 내부로 끌어옴 ③스윕에 `portal/images/` ↔ `LS_PORTAL_ULD.FILE_PATH_NM` 고아 회수 추가.

### [F-ISSUE-81] TC-TUS-014 — PATCH 의 Content-Type 불일치/부재가 415 가 아니라 500 + 스택트레이스 (2차 이월, 미해소)
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

### [F-ISSUE-82] TC-TUS-002 — 세션 생성 `Location` 이 context-path `/api` 를 누락 (2차 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작**: TUS 1.0 클라이언트는 `Location` 을 그대로 후속 HEAD/PATCH/DELETE 대상 URL 로 쓴다. 배포 형상의 context-path(`/api`)가 포함돼야 재개 업로드가 성립한다.
- **현재 동작(실측)**: `POST /api/v1/portal/uploads/tus` → `201`, `Location: /v1/portal/uploads/tus/0a39529d-c142-4271-aa0f-ae6982c2a53a` (`/api` 없음).
  `PortalTusUploadController.java:96` — `.header(HttpHeaders.LOCATION, "/v1/portal/uploads/tus/" + uldId)` 로 **문자열 하드코딩**.
- **재현**: `curl -i -X POST -H 'Tus-Resumable: 1.0.0' -H 'Upload-Length: 20590' -H 'Upload-Metadata: filename Y2xpcC5tcDQ=' http://localhost:18081/api/v1/portal/uploads/tus`
- **영향**: 기능 — 표준 tus-js-client 계열이 Location 을 따라가면 404 로 업로드 재개 불가. 현재 FE 가 자체 경로 조립으로 우회하고 있어 표면화되지 않았을 뿐이며, 이는 계약 위반이 감춰진 상태다.
- **수정 방향(제안)**: `ServletUriComponentsBuilder.fromCurrentContextPath()`(또는 `request.getContextPath()`) 기반으로 Location 을 조립. 회귀 가드로 context-path 설정 하 `Location` 단언 테스트 추가. ⚠ 구현하지 않음.

### [F-ISSUE-83] TC-TUS-010 — `Tus-Resumable` **부재** 시 버전 검사를 통째로 건너뜀 (2차 이월, 미해소)
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

### [F-ISSUE-88] TC-TUS-022 / TC-TUS-027 — 완료 결과(`uldSn`)가 HTTP 응답에 노출되지 않음 (2차 이월, 미해소)
- **심각도**: MEDIUM
- **기대 동작**: 카탈로그 TC-TUS-022 기대결과 "완료 응답(uldSn 재반환)" — 클라이언트가 업로드 완료 직후 생성된 자산 식별자를 알아야 후속 화면 전이·목록 갱신이 가능하다.
- **현재 동작(실측)**: 최종 청크·재전송 모두 **`204 No Content` + `Upload-Offset` 만**. 서비스는 `PortalTusPatchResult(newOffset, completed, uldSn)` 로 uldSn 을 돌려주는데(`PortalVideoUploadService.java:183-185`) 컨트롤러(`PortalTusUploadController.java:139-142`)가 `result.newOffset()` 만 쓰고 **`completed`/`uldSn` 을 폐기**한다. DB 에는 정상 생성됨(`ls_portal_uld.uld_sn=83`, `uld_stts_cd=READY`, `frme_cnt=1`).
- **재현**: 정상 mp4 를 2청크로 업로드 → 최종 PATCH 응답 헤더/본문 확인.
- **영향**: 기능 — 업로드 완료 후 화면이 자산을 즉시 참조하지 못한다(폴링/목록 재조회 의존).
- **수정 방향(제안)**: 완료 시 커스텀 응답 헤더(예: `X-Portal-Uld-Sn`) 부착 또는 `Upload-Offset` 유지한 채 201/200 + 본문 반환(단 TUS 표준은 204 이므로 **헤더 방식 권장**). FE 의 완료 시 목록 무효화도 함께. ⚠ 구현하지 않음.

### [F-ISSUE-89] F-8 전반 — TUS 컨트롤러 계층 자동 테스트 0건 (2차 이월, 부분 미해소)
- **심각도**: MEDIUM
- **현재 동작**: F-8 자동 테스트는 여전히 `PortalVideoUploadServiceTest.java`(11 테스트, 서비스 계층 fake repo) 1 파일뿐이다(`@DisplayName` 실측 11건: 완료·중복이벤트·IDOR 2건·취소PATCH·매직바이트·오디오전용·CANCELLED영속·5GB·확장자·경로순회). **미커버**: TC-001·003·008·009·010·011·013·015·016·017·019·020·026·028·031(15건).
- ⚠ **이번 회차에서 위 15건 중 14건을 라이브로 직접 검증해 PASS 판정**했다(TC-028 만 라이브 재현 실패). 즉 "동작은 확인됐으나 회귀 가드가 없다"는 상태다.
- **영향**: 회귀 무방비 — 실제로 이번 회차에서 결함이 재현된 지점(F-ISSUE-81/82/83/84/85)이 전부 이 미커버 구간에 있다.
- **수정 방향(제안)**: `PortalTusUploadControllerTest`(`@WebMvcTest`)로 헤더 프로토콜(TC-001·003·008·009·010·011·015), `PortalTusUploadIT`(Testcontainers)로 재개·409·410·동시성·완료 멱등(TC-013·016·017·019·020·026·028·031). ⚠ 구현하지 않음.

### [F-ISSUE-85] TC-TUS-011 / TC-TUS-029 인접 — CANCELLED 세션 HEAD 가 204 + 존재하지 않는 offset 반환 (2차 이월, 미해소)
- **심각도**: LOW~MEDIUM
- **기대 동작**: 취소된 세션은 재개 대상이 아니므로 HEAD 는 404/410 로 마감돼야 한다(임시파일이 이미 삭제됐다).
- **현재 동작(실측)**: DELETE 로 취소한 세션 `7db52b17-…` 에 HEAD → **204 + `Upload-Offset: 0` + `Upload-Length: 20590`**. 클라이언트는 "offset 0 부터 재개 가능"으로 읽지만, 같은 세션에 PATCH 하면 409("취소된 업로드 세션입니다") 다. `PortalVideoUploadService.getForOwner():127-137` 이 `isCancelled()` 를 보지 않는다(만료만 검사).
- **재현**: 세션 생성 → `DELETE` → `HEAD` (동일 소유자).
- **영향**: 기능 — 재개 UX 가 어긋난다(offset 0 을 받고 처음부터 전송 시도 → 409). 데이터 파손은 없음.
- **수정 방향(제안)**: `getForOwner` 에 `isCancelled()` → 410(GONE) 분기 추가. HEAD/PATCH 응답 코드 일관성(409 vs 410) 은 함께 결정 필요. ⚠ 구현하지 않음.

---

### LOW

### [F-ISSUE-02] TC-PORTAL-078 — F-4 머리말이 활성 케이스 078 을 "폐기 범위"로 잘못 기재 (병합 단계에서 카탈로그 정정 완료)
- **심각도**: LOW *(카탈로그 정합성)*
- **기대 동작(기대효과)**: 머리말의 폐기 선언 범위와 표의 활성 행이 일치해야 한다. 어긋나면 다음 회차 검증자가 활성 케이스를 "폐기라 검증 대상 아님"으로 건너뛴다(VERIFY-PROMPT §2 는 폐기 케이스를 분모에서 제외하도록 지시하므로 **검증 누락이 조용히 발생**한다).
- **현재 동작(이슈 내용)**: 머리말 `:90` 이 *"구 TC-PORTAL-060~071·**075~078**(포털 SAM2 동작 케이스)은 대상 코드가 존재하지 않아 폐기한다"* 라고 적었으나, 바로 아래 표 `:111` 에 `TC-PORTAL-078 (신규)` 가 **활성 행**으로 존재한다(게이트 없는 `encodeToBase64(String)` 오버로드 부재 확인). 실제 폐기 대상은 `075~077` 뿐이며, 같은 머리말의 `:93` 도 "구 TC-PORTAL-075~077" 로 적고 있어 자기모순이다.
- **재현/확인 경로**: `sed -n '90p;93p;111p' docs/test-cases/F-portal.md`
- **영향**: 검증 누락(거짓 커버리지). TC-PORTAL-078 은 "원본 픽셀 유출 경로 삭제 확인"이라 누락 시 CWE-359 회귀를 놓친다.
- **수정 방향(제안)**: F-part1 이 이번 회차에 **정정 완료**(`075~078` → `075~077` + 정정 주석). 추가 조치 불필요.

### [F-ISSUE-03] TC-PORTAL-072 인접 — `handleAutolabel` 에만 `portalMode` 이중 안전 가드가 없다(방어심층 비대칭, 현재 도달 경로 없음)
- **심각도**: LOW *(현재 도달 가능한 우회 경로 없음 — 예방적)*
- **기대 동작(기대효과)**: 같은 파일의 형제 핸들러들(`handleRenameTrack`·`handleDeleteTrack`·`handleSplitTrack`)은 버튼 숨김에 더해 **콜백 본체에도 `if (portalMode) return;`** 을 두고, 주석으로 그 이유를 명시한다 — *"목록 패널의 버튼은 차단 중 감춰지지만, 콜백 자체도 막아 둔다(진입점이 늘어나도 새지 않게)"*(`LabelingPage.tsx:855-859`). ADR-013 미제공 기능은 같은 기준으로 이중화돼야 한다.
- **현재 동작(이슈 내용)**: `AI 탐지`(YOLO 오토라벨) 진입점만 **버튼 숨김 단일 층**이다.
  ```tsx
  // frontend/src/pages/label/LabelingPage.tsx:743-751
  const handleAutolabel = () => {
    if (!currentFrame) return;
    if (isEditBlocked || isEditBlockedNow(currentFrame.srcSn)) return;
    if (isLocked) { pushToast({ variant: 'error', message: '비식별 재처리 중인 영상은 AI 도구를 사용할 수 없습니다.' }); return; }
    setAutolabelModalOpen(true);   // ← portalMode 검사 없음
  };
  ```
  그리고 이 모달이 열리면 `runAiTool(mode='track')` → `setActiveTool(ToolType.TRACK)`(`:821`) 로 **PORTAL_HIDDEN_TOOLS 게이트를 우회해 TRACK 도구를 활성화**할 수 있는 구조다. `<AiToolModal>` 자체도 `portalMode` 조건 없이 무조건 렌더된다(`:1315-1327`).
- **재현/확인 경로**: 현재는 **재현 불가**(도달 가능한 트리거가 없음) — 확인한 사실만 기록:
  - 유일한 호출부는 `DarkToolbar` 의 `onAutolabel` 이며 그 항목은 `portalHidden: true`(`DarkToolbar.tsx:135`) 라 포털에서 렌더되지 않는다.
  - `SHORTCUT_KEYMAP`(`labelingKeymap.ts:40-80`)에 오토라벨 바인딩이 **없다** — 도구바가 표기하는 `'Y'` 는 키맵 미등록 고정 문자열이다(`DarkToolbar.tsx:126-127` 주석에 명시). 실제로 포털 세션에서 `Y` 를 눌러도 아무 일이 없다.
  - `activeTool` 은 persist 되지 않아 내부 세션 잔재로도 TRACK 이 남지 않는다.
- **영향**: 기능 범위(ADR-013). 지금은 무해하나, 향후 오토라벨 진입점이 하나라도 늘면(단축키 등록·컨텍스트 메뉴·툴팁 링크) 포털에서 SAM2 TRACK 도구가 되살아난다. 서버가 403 으로 막으므로 데이터 유출은 없고 **미제공 기능 광고 + 오류 UX** 수준.
- **수정 방향(제안)**: `handleAutolabel` 첫 줄에 `if (portalMode) return;` 를 추가하고(형제 3개 핸들러와 동일 주석 패턴), `<AiToolModal>` 렌더를 `{!portalMode && ...}` 로 감싼다. 겸사 `runAiTool` 의 `mode === 'track'` 분기에도 동일 가드를 둬 `setActiveTool(TRACK)` 이 `PORTAL_HIDDEN_TOOLS` 를 우회하지 못하게 한다. ⚠ 구현하지 않음.

### [F-ISSUE-22] TC-PORTALUP-027 — 포털 **영상** 업로드에서 추출된 프레임이 `application/octet-stream` 으로 서빙됨(자산 MIME 을 프레임 MIME 으로 오용)
- **심각도**: LOW
- **기대 동작(기대효과)**: 프레임 이미지 서빙은 **그 프레임 파일의 실제 형식**을 Content-Type 으로 선언해야 한다. `X-Content-Type-Options: nosniff` 를 함께 보내므로 선언이 틀리면 브라우저는 **정정할 수단이 없다**(sniffing 금지). 카탈로그 기대값 "Content-Type=저장MIME" 의 취지도 "확장자 추정 금지 = 저장 시점에 확정한 **그 파일의** MIME 사용"이다.
- **현재 동작(이슈 내용)**: 프레임의 MIME 을 **업로드 마스터(`LS_PORTAL_ULD.MIME_TYPE_NM`)** 에서 가져온다. 이미지 업로드는 마스터 MIME = 프레임 MIME 이라 맞지만, **영상(TUS) 업로드는 마스터가 `video/mp4`** 이고 추출된 프레임은 JPEG 이라 매핑이 어긋난다.
  ```java
  // PortalUploadService.java:210-223
  LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(frme.getUldSn(), portalUserNo)...
  MediaType mediaType = resolveStoredMediaType(uld.getMimeTypeNm());   // ← 자산 MIME (VIDEO 면 video/mp4)
  // :433-443  resolveStoredMediaType : JPEG/PNG 가 아니면 APPLICATION_OCTET_STREAM (fail-closed)
  ```
  실측:
  ```
  GET /v1/portal/uploads/frames/61/image   (IMAGE 자산, mime=image/png)
    → 200  Content-Type: image/png            ✅
  GET /v1/portal/uploads/frames/72/image   (VIDEO 자산, mime=video/mp4)
    → 200  Content-Type: application/octet-stream   ❌  (+ nosniff, Content-Disposition: inline)
  $ docker exec klid-backend head -c 4 /app/storage/raw/portal/frames/72/frame-0.jpg | od -An -tx1
     ff d8 ff e0        ← 실체는 JPEG
  ```
  `PortalFrameExtractRunner` 는 프레임을 항상 `.jpg` 로 떨어뜨리므로 **영상 업로드 자산의 모든 프레임이 이 경로에 해당**한다(실측 3001 소유 VIDEO 7건 전부).
- **재현/확인 경로**:
  ```bash
  curl -s -D - -o /dev/null -H "Authorization: Bearer $TOK" \
    localhost:18081/api/v1/portal/uploads/frames/72/image | grep -i content-type
  # → Content-Type: application/octet-stream / X-Content-Type-Options: nosniff
  ```
- **영향**: 기능(렌더링). 현재 FE 는 `useUploadFrameImage.ts:49-53` 이 XHR 로 blob 을 받아 `URL.createObjectURL` 로 그리므로 **지금은 화면이 깨지지 않는다**(blob: URL 은 ORB 대상이 아님). 다만 ①`Content-Disposition: inline` + octet-stream 조합이라 URL 직접 열람 시 표시 대신 다운로드가 되고 ②FE 가 성능상 `<img src>` 직결로 바꾸는 순간 **nosniff + 비이미지 타입 → Chrome ORB 로 차단**되어 영상 업로드 라벨링 캔버스가 백지가 된다(내부 라벨링에서 실제로 있었던 회귀 유형). 보안 영향은 없다(오히려 fail-closed 방향).
- **수정 방향(제안)**: ⚠ 구현하지 않음. 프레임의 MIME 을 **프레임 행 기준**으로 정한다 — ①단기: `serveFrameImage` 에서 자산이 `TYPE_VIDEO` 면 추출 포맷 상수(JPEG)를 쓰거나 `FrameImageService.resolveMediaType(path)`(확장자 allowlist 기반, 내부 서빙과 동일 헬퍼) 를 재사용 ②정공: `LS_PORTAL_ULD_FRME` 에 프레임 MIME 컬럼을 추가해 추출 시점에 확정 적재(표준용어 검토 필요). ①이라도 `application/octet-stream` fail-closed 기본값은 유지할 것.

### [F-ISSUE-23] TC-PORTAL-025 / TC-PORTAL-026 인접 — 포털 프레임 라벨 Load 응답에 항목·좌표 상한도 페이징도 없어 단일 프레임 응답이 500KB 를 넘음(2차 F-ISSUE-22 의 조회측 잔여)
- **심각도**: LOW
- **기대 동작(기대효과)**: 2차 [F-ISSUE-22] 의 수정 방향은 *"`saveUserLabel` 에 좌표 개수 상한 적용 + **`loadFrameLabels`/`listMyLabels` 응답에 항목 상한 또는 페이징 도입**"* 이었다. 저장을 막아도 **이미 적재된 행**과 **행 개수 축**이 남으므로 조회측 상한이 있어야 응답 크기가 유계가 된다.
- **현재 동작(이슈 내용)**: 저장측은 해소됐다(`validatePointCount` — BBOX 2점 / POLYGON 3~200점, `PortalLabelService.java:302-315`). 그러나 **조회측은 무제한 그대로**다 — `loadFrameLabels`(`:432-448`)·`listMyLabels`(`:380-381`) 어디에도 `Pageable`·항목 상한이 없다.
  ```
  GET /v1/portal/frames/1/labels        → 519,052 bytes
    labels = [{id:8, POLYGON, points:30000}, {id:7, "HACK"}, {id:6}, {id:5}, {id:1}]
  GET /v1/portal/user-labels?rawSn=4    → 460,428 bytes  (동일 무페이징)
  ```
  id=8 은 2차 검증이 상한 도입 전에 적재한 30,000점 레거시 행이다(신규 생성은 이제 400 으로 차단됨).
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $TOK" -o /dev/null -w '%{size_download}\n' \
       localhost:18081/api/v1/portal/frames/1/labels     # → 519052
  ```
  ```sql
  SELECT user_lbl_sn, lbl_type_cd, length(point_cn) FROM ls_portal_user_label WHERE src_data_src_sn=1;
  ```
- **영향**: 자원 소진(CWE-770 / OWASP API4) — **잔여 위험은 낮다**: ①신규 유입은 좌표 200점 + 본문 상한 + per-user RateLimiter(`PortalLabelController.acquireSavePermit`)로 3중 제한 ②남은 축은 "한 프레임에 쌓인 행 개수 × 200점"과 기존 레거시 행뿐. 다만 **라벨 삭제 API 가 없어 행은 단조 증가**하므로 장기적으로는 조회 응답이 계속 커진다. 보안 노출은 없다(전부 본인 소유분).
- **수정 방향(제안)**: ⚠ 구현하지 않음. ①`loadFrameLabels`/`listMyLabels` 에 `Pageable`(하드캡 100, 형제 `PortalUploadController.capped` 규약 재사용) 또는 프레임당 항목 상한을 도입 ②레거시 초과 행 정리 마이그레이션은 별건으로 분리(조회는 예외 없이 스킵/절단, 500 금지). ⚠ FE `PortalLabelingPage` 가 전량 로드를 전제하므로 계약 변경 시 FE 동반 수정 필요.

### [F-ISSUE-63] TC-PORTALUP-015 — write 도중 IOException 시 부분 기록분이 보상되지 않음 — 2차 F-ISSUE-63 이월, 미해소
- **심각도**: LOW
- **기대 동작(기대효과)**: 디스크 고갈 등으로 저장이 실패하면 이번 요청이 만든 파일은 하나도 남지 않아야 한다(클래스 Javadoc `#5`).
- **현재 동작(이슈 내용)**: `PortalUploadService.java:120-123` — `writeToDisk(v.file(), dst)`(122행) 실행 후에야 `writtenThisRequest.add(dst)`(123행)가 실행되는 순서가 그대로다. `writeToDisk`(365-372행) 내부 `Files.copy(in, dst)`가 중간에 IOException을 던지면 `dst`는 부분 기록된 채 남고 `writtenThisRequest`에는 추가되지 않아 `rollbackFiles`(139/143행)가 그 파일을 못 지운다. 코드 변경 없음(라인 동일).
- **재현/확인 경로**: 2차와 동일 — 디스크 고갈 시뮬레이션 또는 코드 순서만으로도 결정적 성립.
- **영향**: CWE-459. 보안 영향은 낮음(디스크 이미 고갈된 상황 잔여물).
- **수정 방향(제안)**: `writtenThisRequest.add(dst)`를 `writeToDisk` 호출 **전**으로 이동(rollback이 `deleteIfExists`라 미생성 파일에도 안전), 또는 `writeToDisk` catch에서 `Files.deleteIfExists(dst)` 수행 후 예외 재던짐.

### [F-ISSUE-84] TC-TUS-004 인접 — 오류 응답에 `Tus-Resumable` 헤더 부재 (2차 이월, 미해소)
- **심각도**: LOW
- **기대 동작**: TUS 명세상 서버 응답(오류 포함)에는 `Tus-Resumable` 이 포함돼야 한다.
- **현재 동작(실측)**: `Upload-Length: 5368709121` → `HTTP/1.1 413` 응답 헤더에 `Tus-Resumable` **없음**(성공 응답에만 컨트롤러가 수동 부착).
- **영향**: 프로토콜 정합. 엄격한 클라이언트가 오류 응답을 프로토콜 위반으로 처리할 수 있음.
- **수정 방향(제안)**: `/v1/portal/uploads/tus/**` 전용 `HandlerInterceptor`(또는 `ResponseBodyAdvice`)로 일괄 부착. F-ISSUE-81 과 동시 처리 권장. ⚠ 구현하지 않음.

### [F-ISSUE-87] TC-TUS-020 — 낙관적 락 복원(`truncateTo`) 분기가 비관적 락에 가려 도달 불가 (2차 이월, 미해소)
- **심각도**: LOW
- **기대 동작**: 카탈로그 기대결과의 "409 + truncate 복원" 중 **복원 경로가 실제로 동작**하거나, 아니면 그 분기가 불필요함이 명시돼야 한다.
- **현재 동작(실측)**: 4-스레드 동시 PATCH → 204 1건 + **409 3건**이며, 409 는 전부 `OptimisticLockingFailureException` 이 아니라 **offset 불일치 분기**(`TxService:98-100`)에서 나온다. 진입부가 `findByUldIdForUpdate`(PESSIMISTIC_WRITE, `:72`)라 트랜잭션이 직렬화돼 `saveAndFlush` 의 낙관락 충돌(`:111-115`)이 발생하지 않는다. 결과 offset(10000)은 정합 유지되므로 **실동작상 안전**하다.
- **영향**: 코드 위생(도달 불가 분기 + 그 분기를 전제로 한 테스트/문서 서술). 기능·보안 영향 없음.
- **수정 방향(제안)**: ①분기를 유지하되 "이중 안전망(방어적)" 주석 명시 + 카탈로그 기대결과에서 truncate 문구 완화, 또는 ②비관락 단일화로 낙관락 필드/분기 제거. ⚠ 구현하지 않음.

---

## F클러스터 판정 집계 (병합 담당자 산출 — §7 표기 규칙 준수, 각 파트 원본 표 재합산)

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | 164 | F-1/F-4(part1) 24 + F-2/F-6(part2) 34 + F-3/F-8(part3) 45 + F-5/F-7/F-9/F-10/F-11(part4) 61 |
| FAIL | 3 | F-5(part4) TC-PORTALUP-004·014·015 3건(전부 2차 F-ISSUE-61/62/63 이월, MEDIUM 2건 + LOW 1건) — 나머지 전 파트 0건 |
| PARTIAL | 5 | F-2(part2) 1건(TC-PORTAL-021) + F-6(part2) 1건(TC-PORTALUP-027) + F-8(part3) 3건(TC-TUS-002·014·022) — 나머지 0건 |
| BLOCKED | 0 | |
| 확인필요 | 0 | |
| **검증 대상 계(폐기 제외 분모)** | **172** | 24+34+45+61(PASS) + 0+0+0+3(FAIL) + 0+2+3+0(PARTIAL) = 172 |
| N/A(폐기, 분모 별도) | 5 | F-4(part1) 1행(TC-PORTAL-075~077, 3개 case ID 병합 표기) + F-3(part3) 4행(TC-PORTAL-042~045) |
| **표 행 실측 총계** | **177** | `grep -cE '^\| *~*TC-' docs/test-cases/F-portal.md` 실측(172 검증대상 + 5 폐기) — **VERIFY-PROMPT.md §2 실측치 "F 177"과 정확히 일치** |

- PASS율 = 164/172 = **95.3%**. PASS+PARTIAL(사실상 통과) = 169/172 = **98.3%**.
- 3차 신규 FAIL/PARTIAL **전건이 MEDIUM 이하**이며 전부 2차부터의 미해소 이월(F-5 이미지 업로드 3건, F-8 TUS 3건, F-2/F-6 데이터마트·자산 서빙 2건). 3차 신규 발견 결함 3건(F-ISSUE-01/02/03 — 카탈로그 정합성 2건 + FE 방어심층 비대칭 1건, 전부 LOW~MEDIUM)뿐.
- **카탈로그 정정**: `docs/test-cases/F-portal.md` 총 **8건**(§F-result.md "카탈로그 정정 총건수" 참조, `git diff` 실측 — 신규 케이스·신규 폐기 0건).
- **✅ 2차 HIGH 3건 전건 해소**: 상세는 위 "★★★ 2차 HIGH 이슈 3건 — 전건 해소 확인" 블록 참조.

---

## G클러스터

> 심각도순(CRITICAL→HIGH→MEDIUM→LOW) 정렬. **3차 신규 CRITICAL·HIGH 결함 0건** — 대신 2차 HIGH 3건 전부가 이번 회차 실동작 반증으로 **해소 확인**됐다(아래 최우선 강조 블록). 3차 신규 발견은 MEDIUM 1건(G-ISSUE-21, SSRF 대역 우회 — 실운영 이미지 프로브로 실증)이며, 나머지 이슈는 전부 2차 이월(MEDIUM 4건·LOW 12건) 또는 이월 승계다.

### ★★★ 2차 HIGH 이슈 3건 — 전건 해소 확인 (맨 앞 강조, Critical)

#### [해소 확인 #1] 2차 HIGH #4 — YOLO track mock폴백 시 입력검증 스킵, conftest 강제설정으로 거짓통과 → **완전 해소**
- **2차 결함 요지**: `weights_missing` 형상(운영 실형상)에서 `image_b64="!!!notb64"` 같은 깨진 입력이 `/infer/yolo/track` 에서 **200** 으로 수락됐다. 근인은 `_mock_track` 이 mock 사유에 따라 조건부로만 디코드를 수행했고, 2차 검증 당시 자동테스트는 `conftest.py:17` 의 `AI_MOCK_MODE=true` 강제설정 때문에 `env_mock` 분기만 타 이 결함을 **거짓 통과**시키고 있었다.
- **3차 해소 확증(G-part1 §0, 실동작+코드)**: `POST /infer/yolo/track` invalid base64 → **400 INVALID_IMAGE**(GIF도 400, 14.5MB PNG는 413) — `/predict` 와 상태코드 완전 대칭. 코드 레벨: `routers/yolo.py:175` `_track_yolox` 가 `decode_image_b64_pil(req.image_b64)` 를 **트래커 획득보다 먼저, mock 사유와 무관하게 선두 1회** 수행하도록 이동됐고(169-173 docstring 명시), `_mock_track`(234-261)에서 조건부 디코드는 제거됐다(242-243 주석: "입력 검증은 호출자가 사유와 무관하게 이미 수행했다").
- **거짓통과 근인 자체도 해소**: 신규 회귀테스트 `tests/test_yolo_track_input_validation.py` 가 **mock 사유를 monkeypatch 로 강제**해 `weights_missing`/`load_failed` 운영 형상에서도 검증되도록 막는다(6종: `:53`,`:67`,`:81`,`:96`,`:150`,`:182`).
- **반증(적대 fuzz)**: 두 엔드포인트에 경계·비정상 입력 18종 전송 — **500 응답 0건**(box x1>x2·points 극단 음수 -1e30·track_id 65자·개행 등).
- **카탈로그 반영**: TC-AIYOLO-49 근거를 `test_yolo_track.py:126` 단독 → 프로덕션 코드(`routers/yolo.py:175`) + monkeypatch 회귀테스트로 정정, 신규 TC-AIYOLO-56/57(대칭·순서) + TC-AIYOLO-58(clip_id 비대칭, 아래 G-ISSUE-01) 등재.
- **판정**: TC-AIYOLO-49 PASS.

#### [해소 확인 #2] 2차 HIGH #5 — SAM2 mock폴백이 잘못된 좌표에 재충돌해 500 → **완전 해소**
- **2차 결함 요지**: `points=[[5]]`(원소 1개) 같은 퇴화 좌표가 `_real_segment` 에서 IndexError 를 내고, 그 예외를 잡는 `_mock_segment` 폴백에서마저 같은 좌표로 **재폭발**해 최종 500 이 났다.
- **3차 해소 확증(G-part1 TC-AISAM2-13, 실동작+코드)**: `points=[[5]]` → **400 VALIDATION_ERROR** "List should have at least 2 items"(`points=[[1,2,3]]` 도 400) — **스키마 레벨에서 앞단 차단**돼 라우터 로직에 도달하지 않는다. 코드: `_mock_segment`(`sam2.py:426-427`)가 `_sanitize_coords`/`_sanitize_polygon` 으로 위임해 "비유한/원소부족/None/dict/str/1e400 → 버림" 을 계약으로 명시, 폴백 3곳(`_mock_segment`·`_mock_track`·`_prev_polygon_fallback`) 전부 "어떤 입력에도 예외를 던지지 않는다" docstring 계약 + 정규화 헬퍼 4종 단일 위임.
- **테스트 커버**: `tests/test_sam2_point_validation.py`(9건)·`tests/test_sam2_hardening.py`(24건) 신설.
- **카탈로그 반영**: 신규 TC-AISAM2-29(좌표 원소 정확히 2개 강제, "되돌리기 금지" 명시)·TC-AISAM2-30(비유한 좌표 거부) 등재.
- **판정**: TC-AISAM2-13 PASS.

#### [해소 확인 #3] 2차 HIGH #6 — SAM2 track bbox 계산이 try 블록 밖이라 폴백 없이 500 → **완전 해소**
- **2차 결함 요지**: `prev_polygon=[[1],[2],[3]]`(원소 부족) 이 bbox 유도 코드에서 IndexError 를 냈는데, 그 계산이 **try 블록 진입 전**에 있어 except 폴백이 개입하지 못하고 그대로 500 이 났다.
- **3차 해소 확증(G-part1 TC-AISAM2-17, 실동작+코드)**: `prev_polygon=[[1],[2],[3]]` → **400**(스키마 앞단 차단). 퇴화 폴리곤(`[[5,5]]×3`, 스키마는 통과하지만 면적 0)은 **200** `polygon=prev score=0.5 mock=true mock_reason=empty_mask`. 코드: bbox 유도가 `_polygon_bbox()`(`sam2.py:334-348`)로 분리돼 실패 시 예외 대신 **None 반환** → `_real_track`(`:369-373`) 선두에서 `if bbox is None: return _prev_polygon_fallback(...)` 로 **try 진입 전에도 fallback 이 존재**한다.
- **반증(적대 fuzz)**: 18종 경계·비정상 입력 → 500 응답 **0건**.
- **카탈로그 반영**: 신규 TC-AISAM2-31(프롬프트 배열 상한)·TC-AISAM2-32(track_id 패턴)·TC-AISAM2-33(track 응답 polygon 하한 비대칭 — 통일 금지 명시)·TC-AISAM2-34(mock 폴리곤 무클램프, 아래 G-ISSUE-02) 등재.
- **판정**: TC-AISAM2-17 PASS.

---

### ★ 3차 신규 발견 — G-ISSUE-21 (MEDIUM, SSRF 대역 우회, 실운영 이미지로 실증)

### MEDIUM

### [G-ISSUE-21] TC-AIMOCK-40 — 클라우드 메타데이터/내부 대역 차단이 IPv4 링크로컬에만 걸려 있어 AWS IPv6 IMDS·CGNAT 대역이 strict 정책에서도 통과한다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `ExternalUrlPolicy` 의 선언된 목적은 두 가지다 — ①relaxed 에서도 **"IMDS 는 어떤 환경에서도 정상 위탁 대상이 될 수 없다"** (`ExternalUrlPolicy.java:29-37` 보안 메모, `:136-137` javadoc) ②strict 에서 **loopback/사설/링크로컬/메타데이터 대역 전면 차단**(CWE-918 SSRF, `:168`). 외부 연동 base-url 이 클라우드 메타데이터 엔드포인트를 가리키면 어느 정책에서도 기동이 거부돼야 한다.
- **현재 동작(이슈 내용)**: 두 판정 모두 **Java `InetAddress` 의 IPv4 중심 술어**에만 의존해, IPv4 링크로컬(169.254/16)과 IPv6 링크로컬(fe80::/10)만 잡는다.
  ```java
  // ExternalUrlPolicy.java:148-152 (relaxed)
  if (resolved.isLinkLocalAddress() || resolved.getHostAddress().startsWith("169.254.")) { throw ... }
  // ExternalUrlPolicy.java:176-187 (strict)
  if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
      || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()) { throw ... }
  String ip = addr.getHostAddress();
  if (ip.startsWith("169.254.")) { throw ... }
  ```
  `Inet6Address.isSiteLocalAddress()` 는 **deprecated 된 `fec0::/10`** 만 판정하고 실제 IPv6 사설 대역인 **ULA `fc00::/7` 은 판정하지 않는다.** `isSiteLocalAddress()`(IPv4)도 10/8·172.16/12·192.168/16 만 보고 **CGNAT `100.64/10`** 은 보지 않는다. 그 결과:
  - **AWS IPv6 IMDS `fd00:ec2::254`** (ULA) — relaxed·strict 양쪽 통과
  - **`100.100.100.200`** (Alibaba Cloud 메타데이터 서버, CGNAT 대역) — strict 통과
  [실동작] 운영 이미지 프로브 3회(모두 DB 무효화로 공유 데이터 무접촉):
  ```
  relaxed + VLM_SERVICE_URL=http://[fd00:ec2::254]:9400
    → [ExternalUrl] 평문 HTTP 전송 … host=[fd00:ec2::254]:9400 → Started AuthoringApplication (통과)
  strict(VLM_ALLOW_INSECURE_URL=false) + https://[fd00:ec2::254]
    → 거부 로그 없음 → Started AuthoringApplication (통과)
  strict + https://100.100.100.200
    → 거부 로그 없음 → Started AuthoringApplication (통과)
  대조군: relaxed + http://169.254.169.254:9400 → exit=1 "링크로컬/클라우드 메타데이터 대역을 가리킵니다" (차단)
  ```
- **재현/확인 경로**:
  ```bash
  # 실행 중 backend 의 env 를 복제해 URL 만 바꾼 일회용 컨테이너로 재현(공유 DB 무접촉)
  docker inspect klid-backend --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -v '^$' > /tmp/be.env
  printf 'SPRING_QUARTZ_AUTO_STARTUP=false\nCONTROL_DB_HOST=no-such-db-host\nPORTAL_DB_HOST=no-such-db-host\n' >> /tmp/be.env
  sed -i '' 's|^VLM_SERVICE_URL=.*|VLM_SERVICE_URL=https://[fd00:ec2::254]|' /tmp/be.env
  sed -i '' 's|^VLM_ALLOW_INSECURE_URL=.*|VLM_ALLOW_INSECURE_URL=false|' /tmp/be.env
  docker run --rm --name klid-ssrf-probe --network klid-net --env-file /tmp/be.env klid-backend:latest 2>&1 | grep -E "IllegalState|Started Authoring"
  ```
- **영향**: **CWE-918 (SSRF)**. 트리거 조건은 "운영자가 base-url 환경변수를 그 값으로 지정" 이라 신뢰 경계 안쪽이고 사용자 입력 경유 경로가 아니므로 즉시 착취 가능한 취약점은 아니다. 다만 ①이 정책의 존재 이유가 **설정 사고(오타·복붙·공격자 제어 설정 주입)로 IMDS 를 치는 것을 기동 시점에 막는 것**인데 IPv6 환경의 AWS IMDS 에 대해서는 그 방어가 **성립하지 않는다**(자격증명 탈취로 직결되는 대역) ②KPST·증강·VLM 3개 연동이 같은 판정기를 공유하므로 갭도 3개 연동에 공통이다 ③IPv6 듀얼스택 배포로 전환하면 노출면이 그대로 활성화된다.
- **수정 방향(제안)**: `ExternalUrlPolicy` 의 대역 판정을 **명시적 CIDR 매칭 헬퍼**로 교체한다 — IPv4: `169.254/16`·`127/8`·`10/8`·`172.16/12`·`192.168/16`·**`100.64/10`**·`0/8`, IPv6: `::1`·`fe80::/10`·**`fc00::/7`**·`fec0::/10`(+ IPv4-mapped 는 언랩 후 IPv4 규칙 적용). relaxed 는 그중 **메타데이터·링크로컬 집합**(`169.254/16`, `fe80::/10`, `fd00:ec2::254`)만, strict 는 전체를 거부. `Inet6Address.isSiteLocalAddress()` 에 의존하지 말 것(deprecated 대역만 판정). 회귀 가드는 `VlmUrlPolicyTest.metadataRangeRejectedEvenWhenRelaxed`(현 `:160`) 옆에 IPv6 ULA·CGNAT 케이스를 추가.

### [G-ISSUE-01] TC-AIYOLO-46 인접 — `clip_id` 에 문자 패턴 제약이 없어 SAM2 `track_id` 하드닝과 비대칭 (Log Injection 잠재)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 상위 시스템(BE)이 전달하는 식별자 문자열은 로그 출력 전 개행(`\r`,`\n`)·제어문자가 배제돼야 한다(`rules/security.md` CWE-117, BE 는 `LogSanitizer` 로 이미 준수). 같은 라우터군의 SAM2 `track_id` 는 2026-08-03 하드닝에서 **스키마 패턴 + 출력시점 `_safe()` 이중 방어**를 받았으므로, 동일 성격의 `clip_id` 도 같은 계약이어야 한다.
- **현재 동작(이슈 내용)**: `clip_id` 는 길이 제약만 있고 문자 제약이 없으며, 정제 없이 로그 포맷 인자로 들어간다.
  ```python
  # ai-server/app/schemas.py:101-106  (제약 = 길이뿐)
  clip_id: str = Field(..., min_length=1, max_length=128, description="영상 식별자 ...")
  # 대조 — ai-server/app/schemas.py:205-211 (SAM2 track_id 는 패턴까지 강제)
  track_id: str = Field(..., min_length=1, max_length=64, pattern=TRACK_ID_PATTERN, ...)
  ```
  ```python
  # ai-server/app/routers/yolo.py:181-186, 196-199 — 원문 그대로 로그 인자
  logger.info("[YOLOX][MOCK] track reason=%s clip_id=%s frame_index=%d ...", reason, req.clip_id, ...)
  logger.info("[YOLOX] real track clip_id=%s frame_index=%d ...", req.clip_id, ...)
  # ai-server/app/models/yolox_loader.py:451,458,484-489 — 축출/생성 로그에도 clip_id 원문
  ```
  실측: `clip_id="a\nINJECT"` · `clip_id="../../etc/passwd"` 둘 다 **200 수락**.
  ⚠ 현재는 **앱 INFO 로그가 출력되지 않아 미발현**이다(`docker logs klid-ai-server | grep -c 'INFO:app\.'` → **0**, 2차 G-ISSUE-64 지속). 로그 레벨을 INFO 로 올리거나 로그 수집기를 붙이는 순간 활성화된다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/yolo/track -H 'Content-Type: application/json' \
    -d '{"image_b64":"<valid png b64>","clip_id":"a\nINJECT","frame_index":0}' -w "\nHTTP:%{http_code}\n"
  # 실측 200 (SAM2 동일 입력의 track_id 는 400)
  ```
- **영향**: CWE-117(Log Injection). 감사 추적 오염 — `[YOLOX] tracker created clip_id=...` 라인을 위조할 수 있다. 부수적으로 `clip_id` 는 트래커 캐시(LRU max 10)의 **키**이므로 무제약 문자열이 캐시 키 공간에 그대로 들어간다.
- **수정 방향(제안)**: `schemas.py:101-106` 의 `clip_id` 에 SAM2 와 동일한 `pattern=TRACK_ID_PATTERN`(또는 그 계열) 부여 + `routers/yolo.py` 로그 인자에 `sam2.py:38-46` 의 `_safe()` 와 동일한 공통 정제 헬퍼 적용. ⚠ 구현하지 않는다.

### [G-ISSUE-41] TC-AIYOLO-09 — `imgsz` 가 API 계약상 검증되지만 실추론에 전혀 반영되지 않음(640 고정) — **2차 G-ISSUE-03 미해소, 이월**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `imgsz: 320~1920`(기본 1280)을 검증까지 하면 그 값이 추론 입력 해상도에 반영돼야 한다.
- **현재 동작(이슈 내용)**: `ai-server/app/models/yolox_loader.py:53` `_DEFAULT_INPUT_SIZE: tuple[int, int] = (640, 640)`이 하드코딩되어 `_YoloxBackend.__init__`(:267)·`predict`(:301-314)·`track`(:316-326) 어디에서도 `params.imgsz`를 참조하지 않는다(`grep -n imgsz ai-server/app/models/yolox_loader.py` → 매치 0건, 3차 재확인). 반면 `schemas.py:41`은 `imgsz: int = Field(default=1280, ge=320, le=1920)`으로 범위를 강제한다.
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:19300/infer/yolo/predict -H 'Content-Type: application/json' \
    -d "{\"image_b64\":\"<png>\",\"imgsz\":1920}"   # 200 수용되지만 추론 해상도는 항상 640
  grep -n "params.imgsz\|\.imgsz" ai-server/app/models/yolox_loader.py   # 매치 0건
  ```
- **영향**: 기능/운영. 해상도 튜닝 수단(`LS_SYSTEM_CONFIG.YOLO_IMGSZ`)이 있는 것처럼 노출되나 죽은 설정. 소형 객체 검출률 저하를 운영자가 진단·튜닝할 수 없음.
- **수정 방향(제안)**: ⓐ`_YoloxBackend.predict/track`이 `params.imgsz`로 `input_size`를 동적 구성(ONNX 세션이 동적 shape를 지원하는 경우) 또는 ⓑ지원 불가 시 `schemas.py`의 `imgsz` description에 "현재 640 고정 — 값 무시됨" 명시 + BE 설정 화면에서 `YOLO_IMGSZ` 숨김. ⚠ 구현하지 않음.

### [G-ISSUE-42] TC-AIYOLO-05 — ai-server INFO 로그가 전량 유실되어 mock 사유·추론 파라미터가 관측 불가 — **2차 G-ISSUE-04/64 미해소, 이월**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `routers/yolo.py:140-143`의 `logger.info("[YOLOX][MOCK] predict reason=%s conf_threshold=%.2f imgsz=%d iou=%.2f image_size=%dx%d", ...)`가 요청마다 출력되어, 운영에서 mock 사유·추론 파라미터를 사후 추적할 수 있어야 한다(TC-AIYOLO-05 기대결과 "정상(로그 conf=0.40)"도 이 로그 출력을 전제).
- **현재 동작(이슈 내용)**: `ai-server/app/main.py`·`Dockerfile`에 `logging.basicConfig`/`dictConfig`/uvicorn `--log-level` 배선이 전혀 없어(`grep -rn "basicConfig\|dictConfig\|log_level" ai-server/app ai-server/Dockerfile*` → 매치 0건), Python 기본 root 레벨(WARNING)로 기동되어 INFO 로그가 전량 드롭된다.
  ```bash
  $ docker logs klid-ai-server 2>&1 | grep -c "INFO:app"
  0
  ```
  15회 이상 `/infer/yolo/predict` 호출(본 세션 포함)에도 `predict reason=` 로그는 단 한 줄도 없음(WARN 레벨의 mock-once 경고만 1회 출력됨 — TC-AIYOLO-26).
- **재현/확인 경로**: `docker logs --since 5m klid-ai-server | grep "predict reason="` → 0건, 대조로 `docker logs klid-ai-server | grep -c "returning mock prediction"`(WARNING) → 1건.
- **영향**: 운영/관찰가능성. mock 폴백 원인·추론 파라미터를 첫 1회 WARN 이후 추적 불가 → 학습데이터 품질 저하 원인 규명 불능. 이 이슈가 TC-AIYOLO-05 를 PARTIAL 로 만드는 직접 원인이다(값 자체는 맞으나 검증 수단인 로그가 성립하지 않음).
- **수정 방향(제안)**: `app/main.py`에 `logging.basicConfig(level=os.getenv("LOG_LEVEL","INFO"))`를 앱 생성 전에 배선하거나 Dockerfile CMD에 `--log-level info` 추가. ⚠ 구현하지 않음.

### [G-ISSUE-81] TC-AIMOCK-12 — `/v1/videovlm/status` 가 IntelliVIX v2.0.1 §2.7 규격과 불일치 (2차 G-ISSUE-84 승계, 코드 미수정)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 벤더 v2.0.1 §2.7(서버 상태 체크)은 `200 {"status":"ready"}`(처리 가능) / `200 {"status":"busy"}`(진행 중)만 규정하며 `service` 필드는 없다. 목업은 이 계약을 재현해야 향후 VLM 헬스 인디케이터를 로컬에서 검증할 수 있다.
- **현재 동작(이슈 내용)**: 코드가 2026-08-02(2차) 이후 전혀 변경되지 않아 동일하게 규격을 위반한다.
  ```python
  # mock-server/app/routers/vlm.py:242-244
  async def status_check() -> dict[str, str]:
      return {"status": "ok", "service": "videovlm"}
  ```
  실동작(2026-08-03 17:xx): `GET /v1/videovlm/status` → `200 {"status":"ok","service":"videovlm"}`.
- **재현/확인 경로**: `curl -s http://localhost:9400/v1/videovlm/status`
- **영향**: 기능 영향 현재 없음(BE `VlmClient` 가 이 EP 를 호출하지 않음, grep 확인). 다만 목업=계약 정본 전제가 이 EP 에서 깨져 있고, 카탈로그가 구현을 그대로 베껴 결함을 은폐하고 있었다(3차 회차에 카탈로그는 정정, 코드는 미정정). 이 이슈가 TC-AIMOCK-12 FAIL 의 직접 근거다.
- **수정 방향(제안)**: `vlm.py:status_check` 를 `{"status":"ready"}`/`{"status":"busy"}` 로 교체(진행 중 콜백 유무로 busy 판정 가능, `vlm_sim` 확장). **본 회차에서도 수정하지 않음**(검증 전용, VERIFY-PROMPT §10).

### [G-ISSUE-82] G-8 — VLM 콜백 SSRF 가드가 genai 가드보다 약함(자기참조·임의포트 허용, 2차 G-ISSUE-82 승계, 코드 미수정)
- **심각도**: MEDIUM(목 서버가 루프백 전용 발행이라 원격 노출 없음, 노출 시 HIGH)
- **기대 동작(기대효과)**: 같은 서버의 genai 가드는 host:port allowlist+경로접두사+자기참조 차단을 구현하는데(`genai_sim.is_allowed_url`), VLM 은 호스트 완전일치만 검사(`url_guard.is_allowed_callback`)해 방어 비대칭이 있다. 무인증 서버이므로 동등한 강도가 필요하다.
- **현재 동작(이슈 내용)**: 코드 미변경 확인(`url_guard.py` 전체 대조 — 2차 인용과 동일).
  ```python
  # mock-server/app/services/url_guard.py:28-33
  def is_allowed_callback(url, allowed_hosts) -> bool:
      host = callback_host(url)
      if host is None: return False
      return host in {h.lower() for h in allowed_hosts}
  ```
  기본 allowlist 에 `127.0.0.1`이 있어 **목 서버 자기 자신**이 항상 허용된다. 재현(2026-08-03 17:34:50~52):
  ```
  [MOCK][VLM] verify accepted request_id=qa3-ssrf callback_url=http://127.0.0.1:9400/api/genai/_mock/reset
  [MOCK][GENAI] store reset          ← 다른 벤더(genai)의 job 저장소가 통째로 초기화됨
  [MOCK][VLM] callback sent url=http://127.0.0.1:9400/api/genai/_mock/reset status=200
  ```
  대조로 **동일 URL 을 genai `callback_url` 로 넣으면 차단**됨을 확인: `{"code":"INVALID_PARAMETER","message":"...목 자신은 금지"}` (400).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST http://localhost:9400/v1/videovlm/verify -H 'Content-Type: application/json' \
    -d '{"request_id":"x","event_type":"fall","media":{"type":"video","source_type":"path","path":"/x.mp4"},"callback_url":"http://127.0.0.1:9400/api/genai/_mock/reset"}'
  # 200 accepted → 2초 후 docker logs 에 "[MOCK][GENAI] store reset"
  ```
- **영향**: CWE-918(SSRF) + 무인증 상태변경 유발. 검증 세션 중에도 **다른 클러스터의 genai 관측 이력이 이 테스트 하나로 사라진다**(3차에도 실제 발생). 검증 신뢰성 자체에 영향.
- **수정 방향(제안)**: `vlm.py:_assert_allowed_callback` 이 genai 와 동일 판정기(`is_allowed_url` 을 벤더 중립 모듈로 승격)를 쓰도록 통합. 최소한 `_mock/*` 보조 EP 는 콜백 대상에서 무조건 배제. **본 회차에서도 수정하지 않음**.

### LOW

### [G-ISSUE-02] TC-AISAM2-02/03 — mock/fallback 폴리곤 좌표 무클램프 (2차 G-ISSUE-43 이월 · **실모델 형상에서도 도달 확인**)
- **심각도**: LOW
- **기대 동작(기대효과)**: mock/fallback 응답도 **그 이미지 안의 유효한 폴리곤**이어야 한다. ai-server 는 `width,height` 를 이미 알고 있으므로(`sam2.py:123`,`:303`) 경계 clamp 와 최소 면적 보장이 가능하다. `CLAUDE.md` ★3("AI 검출 응답 = clamp + 퇴화 스킵")의 정신과 정합해야 한다.
- **현재 동작(이슈 내용)**: `_mock_segment` 가 요청 box/point 를 정규화만 하고 **경계 clamp·면적 검사 없이 그대로** 폴리곤으로 되돌린다.
  ```python
  # ai-server/app/routers/sam2.py:426-437
  box = _sanitize_coords(req.box)
  points = _sanitize_polygon(req.points)
  if len(box) == 4:
      x1, y1, x2, y2 = box              # ← 이미지 경계·면적 검증 없음
  elif points:
      cx, cy = points[0]
      half = min(width, height) * 0.1
      x1, y1, x2, y2 = cx - half, cy - half, cx + half, cy + half   # ← 음수 가능
  polygon = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]
  ```
  실측 — **2차와 달리 `AI_MOCK_MODE` 없이 실모델 형상에서 `empty_mask` fallback 경유로 도달**:
  | 입력(이미지) | 응답 폴리곤 |
  |---|---|
  | 1x1 PNG + `box=[10,10,60,60]` | `[[10,10],[60,10],[60,60],[10,60]]` — **이미지(1x1) 밖 60배** |
  | 1x1 PNG + `points=[[0,0]]` | `[[-0.1,-0.1],[0.1,-0.1],[0.1,0.1],[-0.1,0.1]]` — **음수 좌표** |
  | 100x80 + `box=[0,0,0,0]` | `[[0,0],[0,0],[0,0],[0,0]]` — **영면적(퇴화)** |
  track 측도 동일 — `prev_polygon=[[-1e30,-1e30],[1,1],[2,2]]` → fallback 이 `-1e+30` 을 그대로 반사.
- **재현/확인 경로**:
  ```bash
  # 1x1 PNG 는 SAM2 실모델이 마스크를 못 내 empty_mask fallback 으로 떨어진다
  curl -s -X POST http://localhost:19300/infer/sam2/segment -H 'Content-Type: application/json' \
    -d '{"image_b64":"<1x1 png b64>","box":[10,10,60,60]}'
  # 실측 200 mock=true mock_reason=empty_mask polygon=[[10,10],[60,10],[60,60],[10,60]]
  ```
- **영향**: 기능 영향은 제한적 — BE `Sam2SegmentService`(`backend/.../label/service/Sam2SegmentService.java`)가 `aiRes.untrusted()` 로 mock 응답을 빈 폴리곤 치환하고 `validatePolygon(polygon, imgWidth, imgHeight)` 로 상한도 검증한다. 다만 **2차 판정("AI_MOCK_MODE 전용")은 오판**이었고 운영 형상에서도 재현되므로, ai-server 를 직접 소비하는 다른 클라이언트에는 방어가 없다.
- **수정 방향(제안)**: `_mock_segment` 에서 `x1,y1,x2,y2` 를 `[0,width]`/`[0,height]` 로 clamp 하고, clamp 후 면적이 0 이면 중앙 사각(`sam2.py:436`) 분기로 폴백. `_prev_polygon_fallback`/`_echo_polygon` 도 동일 정책 적용 여부를 함께 결정(단, `_echo_polygon` 은 "받은 좌표를 그대로 돌려준다"가 명시 계약이라 변경 시 계약 갱신 필요). ⚠ 구현하지 않는다.

### [G-ISSUE-03] TC-AIYOLO-55 — `coco_id_from_label` 미지 라벨 id 가 프로세스마다 달라져 docstring 계약을 위반 (2차 G-ISSUE-07 이월, 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: `detector_backend.py:107-124` docstring 이 "같은 라벨은 항상 같은 정수가 되며 COCO id 범위(0~79)와 충돌하지 않는다"를 계약으로 선언한다. 트래커가 클래스별 트랙 공간을 분리하므로 이 id 가 흔들리면 재기동 전후 track_id 해석이 달라진다.
- **현재 동작(이슈 내용)**:
  ```python
  # ai-server/app/models/detector_backend.py:124
  return _UNKNOWN_LABEL_ID_BASE + (hash(label) & 0xFFFF)
  ```
  Python `str.__hash__` 는 `PYTHONHASHSEED` 로 프로세스마다 랜덤화되며 컨테이너에 해당 변수가 **미설정**이다.
  실측(동일 컨테이너, 별개 프로세스 2회):
  ```
  $ docker exec klid-ai-server python -c "from app.models.detector_backend import coco_id_from_label; print(coco_id_from_label('unknown_thing'))"
  26423
  75426     ← 재실행 시 다른 값
  ```
- **재현/확인 경로**: 위 명령 2회 실행. COCO 80종(person/car 등)은 `_LABEL2ID` 직접 조회라 영향 없음.
- **영향**: 기능(경미). 현재 검출 라벨은 COCO 80종으로 제한(`AutolabelOnlineService.resolveDetectClasses` 가 마스터 매핑 화이트리스트와 교집합만 전달)이라 실 파이프라인에서 미지 라벨이 도달할 경로가 사실상 없다. 다만 **문서화된 계약과 구현이 어긋난 상태**가 유지된다.
- **수정 방향(제안)**: `hash(label)` 대신 `zlib.crc32(label.encode())` 또는 `hashlib.blake2s(label.encode(), digest_size=2)` 같은 **프로세스 불변 해시**로 교체하거나, docstring 계약을 "프로세스 내에서만 안정"으로 하향 정정. ⚠ 구현하지 않는다.

### [G-ISSUE-04] TC-AIYOLO-52~55 — `bytetrack_util` 전용 자동테스트가 여전히 0건 (2차 G-ISSUE-08 이월, 미해소)
- **심각도**: LOW
- **기대 동작(기대효과)**: `track_id` 정규화(-1→None)·길이 불일치 WARN·WARN-once·클래스 분리는 track 응답 품질의 핵심이므로 회귀 가드가 있어야 한다. 실트래커 없이도 `sv.Detections`/tracker 를 monkeypatch 해 단위 검증이 가능하다.
- **현재 동작(이슈 내용)**: `ai-server/tests/` 17개 파일에 `test_bytetrack_util.py` 가 없다. `_apply_bytetrack` 을 직접 호출·검증하는 테스트도 0건 —
  - `test_yolox_loader.py:33,37` 은 `bytetrack_util.reset_tracker_unavailable_warned()` 만 호출(플래그 리셋).
  - `test_yolox_loader.py:389-412` 는 `_apply_bytetrack` 을 **가짜 함수로 교체**해 호출 여부만 본다(내부 로직 미검증).
  - `test_yolo_dispatch.py:84` 는 응답 키 집합만 단언.
- **재현/확인 경로**: `grep -rn '_apply_bytetrack\|tracker_id' ai-server/tests/` → 위 3곳만.
- **영향**: 테스트 커버리지. TC-AIYOLO-52/53/54 가 3차에서도 BLOCKED 로 남는 직접 원인 중 하나(다른 하나는 가중치 미배포). `-1 → None` 매핑이 깨져도 CI 가 못 잡는다.
- **수정 방향(제안)**: `tests/test_bytetrack_util.py` 신설 — ①`tracker.update` 를 stub 해 `tracker_id=[-1,3]` → `[None,3]` ②`tracker_id` 길이 < dets 길이 → WARN + 누락분 None ③`from trackers import ByteTrackTracker` 를 `ImportError` 로 monkeypatch → None 반환 + WARN 1회 ④`coco_id_from_label` 로 person/car 가 다른 `class_id` 를 받는지. ⚠ 구현하지 않는다.

### [G-ISSUE-05] TC-AIYOLO-44/48/52/53/54 — YOLOX ONNX 가중치 미배포로 G-3 실백엔드·env_mock 케이스 5건이 3차에서도 실동작 미검증 (2차 G-ISSUE-02 이월, 미해소)
- **심각도**: HIGH (이월)
- **기대 동작(기대효과)**: VERIFY-PROMPT §1 대전제("정상 시나리오 위에서 실동작 판정"). 오토라벨(YOLO)이 실검출을 내야 트랙 ID 연속성·NMS·트래커 리셋·ByteTrack 정규화 계약이 검증된다.
- **현재 동작(이슈 내용)**: `docker exec klid-ai-server ls /app/weights` → **0 files**(3차 stack-bringup §8 재확인). 전 요청이 `mock_reason=weights_missing`.
  ```python
  # ai-server/app/models/yolox_loader.py:471-473  (get_yolox_tracker)
  reason = get_yolox_mock_reason()
  if reason is not None:
      return None            # ← 트래커 자체가 생성되지 않아 리셋/LRU/ByteTrack 경로 전부 미도달
  ```
- **재현/확인 경로**: `docker exec klid-ai-server ls -la /app/weights` · `curl POST /infer/yolo/track` → `mock_reason=weights_missing`.
- **영향**: 기능/검증. ①오토라벨링(SFR-08 핵심)이 전 환경 무동작 ②본 회차 40건 중 **4건 BLOCKED + 2건(TC-AIYOLO-44/50) 정적 판정 강제** ③G-2 전 항목·G-3 실트래킹이 회차를 넘겨 계속 미검증.
- **수정 방향(제안)**: 2차 제안 유지 — ①`weights/yolox_s.onnx` 배포 산출물 포함 또는 기동 시 fetch 절차 명문화 + compose 두 파일(`docker-compose.yml:191` `./weights` vs `docker-compose.local.yml:107` `./ai-server/weights`) **마운트 소스 경로 통일** ②검증 환경에 가중치를 두어 다음 회차에 BLOCKED 해소. ⚠ 구현하지 않는다.

### [G-ISSUE-06] TC-AISAM2-20 인접 — `Sam2TrackResponse` 만 연동정의서 표준 래퍼 3필드(`success`/`message`/`error_code`)가 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: 같은 ai-server 의 성공 응답 스키마는 래퍼 필드 유무가 일관돼야 한다 — `YoloResponse`(`schemas.py:86-88`)·`YoloTrackResponse`(`:139-141`)·`Sam2SegmentResponse`(`:197-199`)는 세 필드를 모두 갖고 있고, 테스트도 "연동정의서 표준 래퍼 필드"로 단언한다(`test_mock_indicator.py:28-30,43-45`).
- **현재 동작(이슈 내용)**: `Sam2TrackResponse`(`schemas.py:219-232`)에는 세 필드가 없다.
  ```
  $ curl -s .../infer/sam2/segment ... | jq 'keys'
  ["error_code","message","mock","mock_reason","polygon","score","source","success"]
  $ curl -s .../infer/sam2/track ... | jq 'keys'
  ["mock","mock_reason","polygon","score","source","track_id"]        ← 3필드 부재
  ```
  `test_mock_indicator.py:49-62`(track)만 유일하게 래퍼 필드를 단언하지 않아 이 불일치를 통과시킨다.
- **재현/확인 경로**: 위 두 curl 의 응답 키 비교.
- **영향**: 계약 정합(경미). BE `Sam2TrackResponse`(`backend/.../common/client/dto/Sam2TrackResponse.java:25-32`)는 6필드만 역직렬화하므로 **현재 BE 파손은 없다.** 다만 연동정의서 표준을 근거로 다른 소비자가 `success` 를 읽으면 null 이 된다.
- **수정 방향(제안)**: `Sam2TrackResponse` 에 나머지 3필드를 추가(기본값 있으므로 하위호환)하거나, 반대로 "SAM2 track 은 래퍼 미적용"을 명시적 계약으로 문서화. 어느 쪽이든 `test_mock_indicator.py` 의 track 케이스에 단언을 추가해 고정. ⚠ 구현하지 않는다.

### [G-ISSUE-22] TC-AIMOCK-40/41 — (2차 G-ISSUE-65 이월) 호스트의 첫 번째 해석 주소만 대역 검사
- **심각도**: LOW
- **기대 동작(기대효과)**: 호스트명이 복수 주소로 해석될 때 **하나라도 위험 대역이면 거부**되어야 한다. 실제 커넥션이 어느 주소로 갈지는 JDK/OS 의 주소 선택에 달려 있어, 첫 주소만 검사하면 검사 대상과 접속 대상이 달라질 수 있다.
- **현재 동작(이슈 내용)**: 2차 지적 이후 코드 변경 없음.
  ```java
  // ExternalUrlPolicy.java:161-165 (relaxed)
  try { return InetAddress.getByName(normalized); } catch (UnknownHostException | SecurityException e) { return null; }
  // ExternalUrlPolicy.java:170-175 (strict)
  try { addr = InetAddress.getByName(host); } catch (UnknownHostException e) { throw new IllegalStateException(...); }
  ```
  `:148`·`:176-187` 이 그 **단일** `addr` 만 판정한다. 부수적으로 TC-AIMOCK-41 에서 실증한 "relaxed 는 해석 실패를 통과" 규약과 결합하면, 기동 시점에 NXDOMAIN 이던 호스트가 이후 IMDS 로 해석돼도 재검증 지점이 없다(DNS rebinding TOCTOU — 현 설계가 방어 대상으로 선언하지는 않음).
- **재현/확인 경로**: `grep -n "getByName" backend/src/main/java/kr/co/cudo/authoring/common/config/ExternalUrlPolicy.java` → `:162`, `:172` (`getAllByName` 0건).
- **영향**: CWE-918 잔여 표면. 트리거 조건이 설정값(환경변수)이라 신뢰 경계 안쪽이며 실착취 가능성은 낮다.
- **수정 방향(제안)**: `InetAddress.getAllByName(host)` 로 바꿔 해석된 전 주소에 대역 검사를 수행(하나라도 위험 대역이면 거부). relaxed 의 "해석 실패는 통과" 규약은 `getAllByName` 도 `UnknownHostException` 을 던지므로 동일 catch 로 유지 가능. **G-ISSUE-21 과 같은 메서드를 고치는 작업이므로 한 번에 처리할 것.**

### [G-ISSUE-23] TC-AIMOCK-38/39/43/44/45 — 근거 `file:line` 드리프트 5건 (카탈로그 정정 완료)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 `file:line` 은 판정자가 즉시 대조할 수 있는 실제 위치여야 한다. 어긋나면 엉뚱한 라인을 읽고 거짓 PASS/FAIL 이 난다.
- **현재 동작(이슈 내용)**: 아래 5건이 실제 위치와 불일치. **2차에서 정정한 2건(TC-AIMOCK-43·45)이 그 사이 코드 라인 이동으로 재드리프트**한 것이 포함된다.
  | ID | 카탈로그(정정 전) | 실제 위치 |
  |---|---|---|
  | TC-AIMOCK-38 | `ProfileGatedUrlPolicy.java:121-133,145-151` | `:124-136`(`check`/`policy`) · `:148-151`(`profileAllowsRelaxation`). `:121-123` 은 javadoc |
  | TC-AIMOCK-39 | `ProfileGatedUrlPolicy.java:78-91` | `:81-94`(`verifyRelaxationScope`, throw 는 `:90-93` — 프로브 스택트레이스가 `ProfileGatedUrlPolicy.java:93` 으로 확정). 판정 규칙 본체는 `DeployedEnvironmentDetector.java:63-70` |
  | TC-AIMOCK-43 | `application.yml:532-539` (2차 정정값) | circuitbreaker `vlmClient` 는 `:538-545`, **retry `vlmClient.ignore-exceptions` 는 `:612-618`**(2차 정정값이 retry 쪽을 누락) |
  | TC-AIMOCK-44 | `VlmTimeseriesStep.java` docstring `340-357` / `VlmSubmitPendingSweeper.java:118-124` | 폐지 주석은 `:346-357`(`:340-341` 은 `VlmTimeseriesRequest req = …` 코드). 스위퍼 두 임계 `@Value` 는 `:121-122`, cutoff 적용은 `:192-197` |
  | TC-AIMOCK-45 | `application-local.yml:147` (2차 정정값) | `:150 enabled: ${VLM_CLIENT_ENABLED:true}` (`:151` url, `:152` allow-insecure-url). 공통 기본값은 `application.yml:704` |
  정확했던 근거(대조 완료): `DeidentifyHealthIndicator.java:77-83`/`84-91`/`92-104`/`105-112`, `ExternalUrlPolicy.java:80-82`/`143-153`/`169-181`, `VlmClient.java:71-76`/`88-112`/`94-97`/`106`/`121-127`/`154-169`, `VlmTimeseriesStep.java:60-63`/`303-307`.
- **재현/확인 경로**: `sed -n '81,94p;124,136p;148,151p' backend/src/main/java/kr/co/cudo/authoring/common/config/ProfileGatedUrlPolicy.java` · `sed -n '538,545p;612,618p;704p' backend/src/main/resources/application.yml` · `sed -n '150,152p' backend/src/main/resources/application-local.yml` · `sed -n '121,122p;192,197p' backend/src/main/java/kr/co/cudo/authoring/batch/vlm/VlmSubmitPendingSweeper.java`
- **영향**: 검증 효율·신뢰도 저하(카탈로그 자체의 정합성 결함). 제품 동작 영향 없음. **2회 연속 재드리프트가 관측됐다** — 절대 라인 표기는 코드가 움직일 때마다 낡는다.
- **수정 방향(제안)**: 이번 회차에 5건 모두 카탈로그에 **직접 정정 반영 완료**(담당 라인범위 내 Edit). 재발 방지로는 근거 컬럼에 **라인 대신 심볼명**(`ProfileGatedUrlPolicy#verifyRelaxationScope`, `application.yml resilience4j.retry.instances.vlmClient`)을 병기하는 표기 규약을 검토할 것.

### [G-ISSUE-24] TC-AIMOCK-34 — 벤더 루트 핑이 2xx 가 아니면 전부 DOWN 이라, 실 KPST 루트가 401/403/404 를 주면 상시 DOWN 오탐이 재발한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 이 인디케이터를 KPST 축으로 옮긴 취지 자체가 **"헬스가 위탁 대상이 아닌 주소를 쳐서 DOWN 오탐이 났다"는 사고의 재발 방지**다(`DeidentifyHealthIndicator.java:16-27` javadoc, cudo_246 실측). 헬스는 "위탁 경로가 살아있는가"를 판정해야지 "루트 경로에 200 을 주는 앱인가"를 판정해서는 안 된다.
- **현재 동작(이슈 내용)**: 판정이 `retrieve().toBodilessEntity()` 라 **모든 non-2xx 응답이 예외 → DOWN** 이다.
  ```java
  // DeidentifyHealthIndicator.java:95-104
  kpstWebClient.get().uri("/").retrieve().toBodilessEntity().timeout(PING_TIMEOUT).block();
  return Health.up()....withDetail("mode", "kpst").build();
  ```
  javadoc 은 `:24-27` 에서 "벤더가 보장하는 루트(`/`)만 친다" 고 전제하지만, **루트가 헬스 계약으로 명시된 것이 아니다**(KPST 규격에 헬스 엔드포인트가 없다는 것이 이 설계의 출발점). mock-server 는 루트가 200 이라 로컬에서는 가려진다(실측 `curl localhost:9400/` → 200). 실벤더 루트가 인증 요구(401/403)나 라우트 부재(404)를 반환하면 **비식별 위탁은 정상인데 헬스만 상시 DOWN** 이 되고, 집계 `/actuator/health` 가 DOWN 이면 배포 readiness·모니터링 알림이 통째로 물린다.
- **재현/확인 경로**: 로컬로는 재현 불가(목업 루트가 200). 실벤더 대조 필요. 코드상 판정 분기는 위 인용부이며, 로컬에서 non-2xx 시 DOWN 됨은 `docker pause` 프로브(TC-AIMOCK-37)로 간접 확인됨.
- **영향**: 가용성 오탐(운영). 보안 영향 없음. UNCERTAINTIES #13 의 "IntelliVIX/KPST 실서버 대조 미완" 과 같은 뿌리 — 실벤더 응답 계약 확인 전까지 확정 불가.
- **수정 방향(제안)**: ①KPST 벤더에 **루트 응답 계약(상태코드)** 을 서면 확인하고 그 값을 javadoc·케이스에 명시, 또는 ②`.exchangeToMono(...)` 로 바꿔 **"응답을 받았다"(=TCP+HTTP 왕복 성립)** 를 UP 조건으로 삼고 5xx·연결 실패만 DOWN 으로 판정(4xx 는 `details` 에 상태코드만 남기고 UP). ②가 "위탁 경로 생존" 이라는 헬스의 목적에 더 맞는다. 어느 쪽이든 **실벤더 확인 없이 임의 확정하지 말 것.**

### [G-ISSUE-25] TC-AIMOCK-37 — DOWN 상세의 `error` 가 Reactor 래퍼 클래스명으로 뭉개져 장애 원인을 구분할 수 없다
- **심각도**: LOW
- **기대 동작(기대효과)**: 헬스의 `error` 디테일은 CWE-209 를 지키면서(스택트레이스·주소·내부경로 미노출) **운영자가 1차 분류를 할 수 있어야** 한다 — 타임아웃인지, DNS 실패인지, 연결 거부인지, 인증 실패인지.
- **현재 동작(이슈 내용)**: `.block()` 이 checked 예외를 `reactor.core.Exceptions$ReactiveException` 으로 감싸므로 `e.getClass().getSimpleName()` 이 **원인이 아니라 래퍼 이름**을 낸다.
  ```java
  // DeidentifyHealthIndicator.java:105-112
  } catch (Exception e) {
      return Health.down()....withDetail("error", e.getClass().getSimpleName()).build();
  ```
  [실동작] `docker pause klid-mock-server` → `{"status":"DOWN","details":{"service":"deidentify","mode":"kpst","error":"ReactiveException"}}`. 동일 현상이 `controlNotifyHealth` 에서도 관측됨(`"error":"ReactiveException"`). 실제 원인(`TimeoutException`)은 응답 어디에도 없다.
- **재현/확인 경로**:
  ```bash
  docker pause klid-mock-server
  curl -s -H "Authorization: Bearer <REVIEWER JWT>" http://localhost:18081/api/actuator/health   # → error: ReactiveException
  docker unpause klid-mock-server
  ```
- **영향**: 관측성 저하(운영 트리아지 지연). 케이스의 기대결과("예외 simpleName 만, 스택트레이스 미노출")는 충족하므로 TC-AIMOCK-37 판정 자체는 PASS. 보안 영향 없음(오히려 과소 노출).
- **수정 방향(제안)**: `Exceptions.unwrap(e)` 로 원인을 벗긴 뒤 그 클래스의 simpleName 을 노출하거나, `WebClientResponseException` 이면 상태코드만 함께 남긴다(`error=TimeoutException` / `error=WebClientResponseException(404)`). 메시지 본문·주소는 계속 노출하지 않는다. `controlNotifyHealth` 등 동일 패턴 인디케이터도 함께 정비.

### [G-ISSUE-61] TC-AIVLM-14 — `AiServerClient.verifyObjects` 프로덕션 호출부 0건(G-5 엔드포인트 전체가 도달 불가 표면) — 2차 G-ISSUE-62 이월
- **심각도**: LOW
- **기대 동작(기대효과)**: ai-server `/infer/vlm/verify-objects`는 "YOLO/SAM2 검출 라벨 정합성 검증" 목적으로 노출됐고, 카탈로그 §G-5 전제는 BE가 `AiServerClient.verifyObjects`로 이를 실제 호출하는 것이다. 노출된 추론 표면은 실사용되거나, 아니면 제거돼야 한다(OWASP API9:2023 Improper Inventory Management).
- **현재 동작(이슈 내용)**: 클라이언트 메서드는 여전히 정의만 있고 `src/main` 어디서도 호출되지 않는다.
  ```java
  // backend/.../common/client/AiServerClient.java:89-98
  public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) {
      return webClient.post().uri("/infer/vlm/verify-objects")...
  }
  ```
  `grep -rn "verifyObjects" backend/src/main backend/src/test` → `src/main` 정의부 1건뿐(호출 0건), `src/test`는 `AiInferenceDeidentReportGateTest.java:66` 주석 언급 1건뿐(실호출 아님). `grep -rn "infer/vlm" backend/src frontend/src` → BE 3건 전부 정의/주석/DTO 주석, FE 0건.
- **재현/확인 경로**: `grep -rn "verifyObjects" backend/src/main` (호출부 0건 재확인). `docker logs klid-ai-server 2>&1 | grep "verify-objects"`로 실제 호출 발신 IP를 보면 backend 컨테이너(172.20.0.5 등) 발신이 없음을 확인 가능(본 회차는 검증자 curl만 관측).
- **영향**: 기능 결함 아님(다른 파이프라인 동작에 영향 없음). ①인증 없는 추론 표면(ai-server는 무인증)이 사용처 없이 열려 있음 ②G-5 14개 케이스가 제품 동선에서 도달 불가능한 경로를 검증 중이라 검증 리소스 배분 왜곡. `AiInferenceDeidentReportGateTest.java:66` 주석은 "verifyObjects처럼 같은 이미지를 운반하는 다른 메서드"가 향후 배선 시 비식별 신고 게이트를 우회할 잠재 위험을 지적하는데 현재는 호출부가 없어 잠재 위험으로만 남음(CWE-359 배선 시 재확인 필요).
- **수정 방향(제안)**: 정책 결정 필요 — ①실사용 계획이 없으면 `AiServerClient.verifyObjects` + `ai-server/app/routers/vlm.py` 라우트 + 관련 스키마 제거(표면 축소) ②사용 계획이 있으면 배선 시 비식별 신고 게이트(`DeidentReportGate`)를 반드시 함께 적용. 어느 쪽이든 카탈로그 §G-5에 "현재 프로덕션 호출부 0건"을 명기해 다음 검증자가 도달 불가 경로임을 알게 한다. (2차 회차부터 2회 연속 관측 — 다음 회차에도 재확인 권장)

### [G-ISSUE-83] G-8 — VLM 요청 스키마가 벤더 조건부 필수 필드를 검증하지 않음(2차 G-ISSUE-85 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: 벤더 §3.1/§3.2 조건부 필수(`media.path`(source_type=path 시), `frame_policy.framerate`(Required=Y), `frame_policy.selected_frames`(mode=frame_selected 시))를 목업도 강제해야 BE 리팩터 회귀를 로컬에서 잡는다.
- **현재 동작(이슈 내용)**: `schemas/vlm.py` 전부 Optional, 코드 미변경 확인(2차 인용 라인과 동일 — `framerate`/`selected_frames`/`path` 모두 `Optional[...] = Field(default=None, ...)`).
- **재현/확인 경로**: 2차 문서 기록과 동일(`media.path` 누락, `frame_policy.framerate` 누락, `mode=frame_selected`인데 `selected_frames` 누락 — 3건 모두 200). 이번 회차는 코드 diff 0건 확인으로 대체(실동작 재실행 생략, 근거: `git log` 무커밋).
- **영향**: 계약 검증 공백, BE 리팩터 회귀를 로컬·CI 어디서도 못 잡음.
- **수정 방향(제안)**: `schemas/vlm.py` 에 `model_validator(mode="after")` 로 조건부 필수 3종 400 거부 추가. **본 회차에서도 수정하지 않음**.

### [G-ISSUE-84] G-7 — `GET /manual_deid_info` 가 KPST "프로젝트 상태=3(수동대상)" 축을 반영하지 않음(2차 G-ISSUE-86 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: KPST 명세(`22-deid-solution-api.md` §22.3.8/§22.4)는 이 EP 를 "`db_save=1` **이며** 프로젝트 상태=수동대상(state=3)" 데이터셋으로 정의.
- **현재 동작(이슈 내용)**: `deid.py:_manual_targets`(2차와 동일 라인대) 가 `db_save==1` 만으로 필터, 진행중/완료 구분 없이 반환. 코드 미변경 확인.
- **영향**: 기능 영향 없음(수동 비식별 연계는 `22-deid-solution-api.md` §22.6 이 "후속·미구현" 명시, BE 호출부 0건). 향후 워크플로 연결 시 로컬 검증 공백.
- **수정 방향(제안)**: `_manual_targets` 에 상태=수동대상 조건 추가. **본 회차에서도 수정하지 않음**.

### [G-ISSUE-85] G-7 — 산출 완료 후 `.mock-tmp/` 빈 디렉터리가 export_path 에 잔존(2차 G-ISSUE-88 승계, 코드 미수정)
- **심각도**: LOW
- **기대 동작(기대효과)**: BE 는 완료 후 export 디렉터리를 폴백 스캔한다. 목이 만드는 임시 디렉터리는 완료 후 정리돼 있어야 스캔 대상이 깨끗하다.
- **현재 동작(이슈 내용)**: 이번 회차 신규 생성한 `qa3-g5-p1/` 에서도 동일 재현.
  ```
  $ docker exec klid-mock-server ls -la /app/storage/deidentified/videos/qa3-g5-p1/
  drwx------ 2 app app 4096 Aug  3 17:35 .mock-tmp     ← 빈 디렉터리, 잔존
  -rw-r--r-- 1 app app 50854 Aug  3 17:35 af5780ea…-mask.mp4
  ```
- **영향**: 현재 무해(BE 회수는 1차 파일명 경로가 맞아 폴백 스캔에 도달 안 함). export 폴더 청결성 저하.
- **수정 방향(제안)**: `produce_deid_outputs` 종료부에서 빈 임시 디렉터리 `rmdir`(genai `discard_results` 와 동일 패턴). **본 회차에서도 수정하지 않음**.

---

## G클러스터 판정 집계 (병합 담당자 산출 — §7 표기 규칙 준수, 각 파트 원본 표 재합산)

| 판정 | 건수 | 비고 |
|---|--:|---|
| PASS | 154 | G-1/G-2(part3) 41 + G-3(part1) 7 + G-4(part1) 28 + G-5/G-6(part4) 34 + G-7(part5) 8 + G-8(part5) 3 + G-9(part5) 21 + G-10(part2) 12 |
| FAIL | 2 | G-1(part3) TC-AIYOLO-09(1건, imgsz 미반영, MEDIUM 이월) + G-8(part5) TC-AIMOCK-12(1건, 벤더규격 불일치, MEDIUM 이월) |
| PARTIAL | 3 | G-1(part3) TC-AIYOLO-05(1건, 로그유실로 검증수단 불성립) + G-3(part1) TC-AIYOLO-55(1건, 미지라벨 id 비결정적) + G-10(part2) TC-AIMOCK-40(1건, SSRF 대역 부분방어 — G-ISSUE-21) |
| BLOCKED | 4 | G-3(part1) TC-AIYOLO-48·52·53·54 — 전부 YOLOX ONNX 가중치 미배포(G-ISSUE-05) 단일 사유 |
| N/A | 0 | |
| 확인필요 | 0 | |
| **검증 대상 계(분모)** | **163** | 41+7+28+34+8+3+21+12(PASS) + 1+1(FAIL) + 1+1+1(PARTIAL) + 4(BLOCKED) = 163 |
| 신규 등재(미판정, 분모 밖) | 9 | TC-AIYOLO-56~58·TC-AISAM2-29~34 — 2차 HIGH 하드닝을 케이스화, 다음 회차 판정 대상 |
| **표 행 실측 총계(카탈로그)** | **172** | `grep -cE '^\| *~*TC-' docs/test-cases/G-ai-server.md` 실측(163 기존 판정 + 9 신규 등재) |

> ⚠ **병합 시 재합산 정정 1건**: part3(G-1/G-2) 자체 요약표는 "PASS 39·PARTIAL 2·FAIL 2"였으나, 43개 케이스별 판정 행을 병합 담당자가 직접 재카운트한 결과 **PASS 41·PARTIAL 1·FAIL 1**이 정확한 값이다(TC-AIYOLO-05 PARTIAL 1건, TC-AIYOLO-09 FAIL 1건만 실재 — part3 자체 요약표가 이중계산된 것으로 추정). 위 표·전체 합계는 정정된 값을 사용했다.

- PASS율 = 154/163 = **94.5%**. PASS+PARTIAL(사실상 통과) = 157/163 = **96.3%**.
- **카탈로그 정정**: `docs/test-cases/G-ai-server.md` 총 **9건**(TC-AIYOLO-27·49 / TC-AIMOCK-11·12·38·39·43·44·45 — 근거 라인 드리프트 7건 + 기대결과 변경 2건, 프로덕션 동작 변경 아님) + **신규 등재 9건**(TC-AIYOLO-56~58·TC-AISAM2-29~34, `git diff` 실측).
- **✅ 2차 HIGH 3건 전건 해소**: 상세는 위 "★★★ 2차 HIGH 이슈 3건 — 전건 해소 확인" 블록 참조.
- **★ 3차 신규 발견**: G-ISSUE-21(MEDIUM, CWE-918) — SSRF 대역 차단이 IPv6 ULA(`fc00::/7`)·CGNAT(`100.64/10`)를 놓쳐 relaxed/strict 양쪽 기동을 통과, 실운영 이미지(`klid-backend:latest`) 프로브 3회로 실증.
- **환경 부작용 고지**: G-ISSUE-82 반증(VLM 콜백 SSRF)이 이번 회차에도 genai mock 저장소를 재초기화시켰다(`POST /api/genai/_mock/reset` 트리거) — 코드 미수정 상태가 유지되는 한 G/genai 관련 검증 시 재발한다.

## H클러스터

> 심각도순(CRITICAL→HIGH→MEDIUM→LOW) 정렬. CRITICAL 0건 · **HIGH 2건(모두 3차 신규)** · MEDIUM 11건 · LOW 7건 = 총 20건.
> H클러스터 8개 파트(part1=H-1·H-14 / part2~part4=H-3 3분할 / part5=H-11·H-15 / part6=H-2·H-4·H-5·H-7 / part7=H-6·H-8·H-9·H-10 / part8=H-12·H-13·H-16·H-17·H-18) 병합.
> ⚠ **part2(21~)·part3(41~)·part4(61~)가 각자 독자 채번했다 — 3차 신규채번**이며 2차 이전 회차의 동일 번호 이슈와 무관하다. part7 은 신규 이슈 번호를 부여하지 않고 2차 `H-ISSUE-81`(TC-FE-146)을 "미해소 이월"로만 기록했는데, part5 가 3차 신규채번으로 별도 사안(TC-FE-146과 무관한 TC-E2E-011)에 동일 번호 `H-ISSUE-81`을 이미 사용했으므로 **병합 담당이 part7 건에 `H-ISSUE-121`(3차 신규채번, 병합 시 부여)을 배정**해 번호 충돌을 피했다.

### ★★★ 2차 HIGH 이슈 5건 — 4건 해소 확인 + 1건 3회차 연속 미해소 (맨 앞 강조, Critical)

#### [해소 확인 #1] 2차 HIGH #7 — 세션 만료/401 시 상위 로그인 복귀 URL(`next=`) 미주입 → **완전 해소**
`frontend/Dockerfile:40-48`(build-arg fail-closed 검증) · `docker-compose.yml:312-313,329-330` · `deploy/onprem/scripts/lib/common.sh:62-79`(`require_upstream_login_urls`) · `deploy/onprem/scripts/package/20-build-frontend.sh:39-41` · `deploy/onprem/scripts/install/build-from-source.sh:189-191` · `frontend/src/test/upstreamLoginUrlDeployWiring.test.ts`(3진입점 회귀 가드) 로 배포 배선이 3개 진입점 전부에 들어갔고, `redirectToUpstream.ts:21-29,48-52` `buildNextUrl()`이 복귀 URL 을 실제로 주입한다. **실동작 확증**(임시 dev 서버 :5199, 상위 도메인 스텁): `http://control.local/login?next=http%3A%2F%2F127.0.0.1%3A5199%2Fvideo%2Fcompleted%3Fpage%3D2` — 쿼리스트링까지 보존된 복귀 URL, `sessionStorage['klid_jwt']=null`(clear 동작). H-part1 §1.

#### [미해소 #2] 2차 HIGH #8 — `?token=` URL 인계 채널이 운영 기본값에서 여전히 활성 → **3회차 연속 미해소** (`H-ISSUE-01`)
`frontend/Dockerfile:33` `ARG VITE_TOKEN_INGRESS=all` · `docker-compose.yml:325` · `.env.example:141` · 온프렘 스크립트 2종 · 문서 3종이 전부 기본값 `all`. `docker inspect klid-frontend` 실효값도 `all`. **실브라우저 재현**: `GET /ingress?token=<REVIEWER JWT>` → `/dashboard` 정상 진입 + `sessionStorage['klid_jwt']` 적재 — URL 채널 단독으로 인증 성립(localStorage·cookie 채널도 각각 단독 성립). `CLAUDE.md` "URL 쿼리 파라미터(`?token=`) 방식 미사용" 위반이 그대로 유지. **CWE-598**. H-part1 §1·§5(`H-ISSUE-01`).

#### [해소 확인 #3] 2차 HIGH #9 — 라벨 저장 PUT 이 `labelVersion` 을 싣지 않아 낙관적 동시성(lost update)이 죽은 경로였던 것 → **완전 해소**
`frontend/src/features/label/api.ts` `getLabels()` 가 응답의 `labelVersion` 을 매핑하도록 수정(커밋 `dcdbb827`). **실동작 재현**(part3 §2 + part4 §1 두 파트가 독립적으로 교차 확인): ①저장 PUT body 에 `labelVersion` 실제 동봉(`{"items":[…],"labelVersion":5}`) ②연속 저장 시 렌더 클로저가 아니라 **캐시 최신 버전**을 전송(자기 409 없음) ③다른 세션이 먼저 저장해 버전이 밀리면 **409 + 충돌 다이얼로그**("다른 사용자가 먼저 저장했습니다" / "최신 라벨 불러오기" · "내 작업 유지") 노출, 취소해도 내 작업(dirty) 보존. BE 대조: `labelVersion=1(stale)`→409, `labelVersion=3(최신)`→200. H-part3 §2, H-part4 §1.

#### [해소 확인 #4] 2차 HIGH #10 — 전체 워크플로 E2E 픽스처(`WORKFLOW_VIDEO_ID=9035` 하드코딩) 부재로 검수종결 E2E 커버리지 0 → **하드코딩 제거 + 동적 해석으로 해소 (단, 완주 실행은 이번 회차 미확인)**
`frontend/e2e/fixtures/test-data.ts` 에서 구 상수 삭제 확인(`grep "WORKFLOW_VIDEO_ID\|WORKFLOW_SRC_SN"` 0건). 대체 메커니즘 `resolveWorkflowFixture()`(`test-data.ts:116-128`)가 공개 API(`/v1/assignments`·`/v1/reviews/{id}`·`/v1/frames/{srcSn}/labels`)만으로 배정·게이트·권한 후보를 실행시점에 동적 해석(테스트 백도어 없음). 전제 실동작 확인: `GET /v1/assignments`(WORKER 2001) → `totalElements=49`, `firstSrcSn` 보유 후보 존재. 스펙 자체도 4건→9건으로 보강(픽스처 유효성 단언 + 최종 완주 무조건 단언 신설). ⚠ **한계**: 이번 회차는 E2E 실행이 금지되어 **완주 자체는 미확인**이며, 로컬 하네스 기본 대상 포트(`8080`/`5174`)가 실제 compose 스택(`18081`/`13000`)과 어긋나(`H-ISSUE-84`) 즉시 실행도 불가하다 → TC-E2E-016~019 는 PARTIAL, 잔여 갭은 `H-ISSUE-86`. H-part5 §0.

#### [해소 확인 #5] 2차 HIGH #11 — 포털 데이터마트 라벨링 화면에 AI 분할·추적·스켈레톤(SAM2 등)이 노출되던 것(ADR-013 위반) → **완전 해소**
`frontend/src/features/label/types.ts:215-219` `PORTAL_HIDDEN_TOOLS=[SAM_SEGMENT,TRACK,KEYPOINT]` 단일 소스로 `DarkToolbar.tsx:148-153` 가 게이팅(YOLO 오토라벨 액션도 `portalHidden:true` 로 별도 숨김). **FE 실동작 2회 독립 재현**: `/portal/label/468`(데이터마트 영상) 도구바 = `선택/바운딩 박스/폴리곤/삭제/실행취소/화면맞춤/저장` — `AI 분할`·`AI 추적`·`스켈레톤`·`AI 탐지` **0건**. `/portal/uploads/80/label`(포털 업로드 자산)도 AI 진입점 0건. 회귀 가드 `LabelingPagePortalRestrictions.test.tsx:110-123` 존재, baseline 2,064건 전건 통과. `UNCERTAINTIES.md` #1(포털 SAM2 노출=결함 유지) 은 FE 축에서도 **해소** — ★확정 정책 절 승격을 F클러스터와 함께 권고. H-part5 §0.

---

### ★★ 3차 신규 HIGH 결함 2건 (최우선 강조 — 본문은 아래 HIGH 절에도 그대로 수록됨)

- **[H-ISSUE-41]** TC-FE-067/064/083 인접 — BE 가 내리는 잠금 코드(`"LOCKED"`)와 FE 판정값(`"LOCKED_FOR_REDEIDENT"`)이 달라 **서버 잠금이 라벨링 화면에 전혀 반영되지 않는다**(잠금 배너 0건, 저장·붙여넣기·되돌리기·신고 버튼 전부 활성 상태로 남음). BE 최종 방어(409)는 살아 있어 데이터 유실은 없으나, 작업자가 잠긴 줄 모르고 편집하다 저장 시점에야 거부당해 작업이 낭비된다.
- **[H-ISSUE-81]** TC-E2E-011 — 포털 업로드 E2E(`portal-upload.spec.ts`)가 업로드 UI 가 전혀 없는 `/portal`(홈)을 겨냥해 첫 단언이 확정 실패하고, 둘째 테스트는 `if (count>0)` 가드 안에 있어 공허 통과한다. 실제 업로드 입력(`#portal-image-input`/`#portal-video-input`)은 `/portal/uploads` 에만 존재 — 포털 자산 업로드(ADR-013 예외 경로)의 E2E 실질 커버리지가 **0**이다.

---

### HIGH

### [H-ISSUE-41] TC-FE-067 / TC-FE-064 / TC-FE-083 — BE 가 내리는 잠금 코드(`LOCKED`)와 FE 판정값(`LOCKED_FOR_REDEIDENT`)이 달라 서버 잠금이 화면에 전혀 반영되지 않는다 (3차 신규채번, part3)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 영상이 재비식별 잠금 상태면 라벨링 화면 진입 시 `lockSttsCd` 로 이를 인지해 ①잠금 배너(role=status) 노출 ②저장·트랙편집·붙여넣기·되돌리기·신고 버튼 비활성 — `LabelResponse.java:29` 주석("`null`/빈 문자열 = 잠금 없음, `\"LOCKED_FOR_REDEIDENT\"` = 비식별 재처리 중")이 이 계약을 명시한다.
- **현재 동작(이슈 내용)**: BE 는 **`"LOCKED"`** 문자열을 내려보낸다.
  ```java
  // backend/.../label/service/LabelService.java:200
  String lockSttsCd = workLockService.isRawLocked(current.getRawSn()) ? "LOCKED" : null;
  ```
  FE 는 계약대로 `LOCKED_FOR_REDEIDENT` 와만 비교한다.
  ```ts
  // frontend/src/pages/label/LabelingPage.tsx:516
  const isLocked = data?.lockSttsCd === 'LOCKED_FOR_REDEIDENT' || reportedLock;
  ```
  → 서버 잠금만 걸린 상태(비식별 신고 없이 잠금, 또는 다른 세션이 잠근 경우)에서는 `isLocked` 가 영영 false 다.
- **재현/확인 경로** (rawSn 115, `DE_IDENT_YN='Y'` 유지한 채 작업락만 LOCKED 로):
  ```sql
  UPDATE ls_auth_work_lock SET lck_stts_cd='LOCKED' WHERE work_lock_sn=41;  -- data_raw_sn=115
  ```
  ```
  GET /api/v1/frames/508/labels → 200, lockSttsCd = 'LOCKED'
  화면 /label/508 : 잠금 배너 0건 · 저장 버튼 enabled · 비식별 신고 버튼 enabled  ← 잠금 인지 실패
  PUT /api/v1/frames/508/labels → 409 {"message":"비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다."}  ← BE 는 정상 차단
  ```
  대조군(응답의 `lockSttsCd` 만 `LOCKED_FOR_REDEIDENT` 로 치환): 배너 1건 정상 표시 → 원인이 값 불일치임이 확정됨.
- **영향**: 데이터 유실은 없다(BE 가 409 로 최종 차단). 그러나 ①작업자는 잠긴 영상인 줄 모르고 편집을 계속하다 저장 시점에야 거부당해 작업이 낭비되고 ②`isLocked` 에 걸린 FE 가드(트랙편집·붙여넣기·되돌리기·신고 버튼)가 서버 경로에서 전부 무력하며 ③잠금 배너가 사실상 `reportedLock`(신고 직후 클라이언트 표식) 전용이 되는데, 그 경로는 곧바로 412 에러 화면으로 대체되어 배너를 볼 수 있는 창이 거의 없다. 이 값은 최근 회귀가 아니라 `6c40e02c`(테이블 분리 리팩터) 시점부터 이어진 장기 드리프트다.
- **수정 방향(제안)**: BE `LabelService:200` 의 리터럴을 `"LOCKED_FOR_REDEIDENT"` 로 맞추는 것이 최소 변경(FE 타입 `LockSttsCd` 와 `LabelResponse` javadoc 이 이미 그 값을 정본으로 선언). 반대로 FE 를 넓히면(둘 다 허용) 계약 문서와 어긋난 값이 고착된다. 어느 쪽이든 BE↔FE 값 동치를 고정하는 회귀 테스트(응답 DTO 상수 + FE 판정 상수 동일성)를 함께 둘 것. ⚠ 구현은 하지 않았다.
  - **인접 파생 이슈**: 같은 원인으로 [H-ISSUE-42](TC-FE-197, MEDIUM) — 잠금(409)까지 "다른 사용자가 먼저 저장했습니다" 낙관적 동시성 충돌 다이얼로그로 잘못 안내됨. 아래 MEDIUM 절 참조.

### [H-ISSUE-81] TC-E2E-011 — 포털 업로드 E2E 가 업로드 UI 가 없는 `/portal`(홈)을 겨냥해 첫 단언이 확정 실패한다 (2차 H-ISSUE-101 미해소 이월, 3차 신규채번)
- **심각도**: HIGH
- **기대 동작(기대효과)**: 포털 자산 업로드(ADR-013 예외 경로)는 외부 채널이 파일을 반입하는 유일한 입구다. 업로드 화면의 dropzone/파일 입력 노출과 실제 업로드 시도가 E2E 로 보장되어야 회귀(라우트 변경·컴포넌트 이동)가 잡힌다.
- **현재 동작(이슈 내용)**: 스펙이 `PortalHomePage` POM 을 써서 `/portal` 로 이동한 뒤 업로드 요소를 찾는다.
  ```ts
  // e2e/specs/portal-upload.spec.ts:10-18
  const home = new PortalHomePage(portalPage);   // pages/PortalHomePage.ts:19 → pushState('/portal')
  await home.goto();
  const visible = (await home.dropzone.count()) > 0 || (await home.fileInput.count()) > 0;
  expect(visible).toBe(true);
  ```
  실측(PORTAL_USER 세션, 브라우저):
  ```
  /portal          → input[type=file] 0개, [data-testid=upload-dropzone] 0개   ← 단언 실패
  /portal/uploads  → input#portal-image-input(multiple, jpg/jpeg/png)
                     input#portal-video-input(mp4/mov/avi)
  ```
  두 번째 테스트(`:20-38`)는 `if ((await home.fileInput.count()) > 0)` 안에서만 동작하므로 아무 것도 검증하지 않고 통과한다. fixture `e2e/fixtures/sample.jpg.txt` 도 첫 줄이 `e2e mock placeholder file (not a real jpg)` 라 BE 매직바이트 검증을 통과할 수 없다.
- **재현/확인 경로**: PORTAL_USER JWT 로 `/ingress?token=...` → `/portal` 에서 `document.querySelectorAll('input[type=file]').length` → `0`. `/portal/uploads` 로 이동하면 `2`.
- **영향**: 기능 — 포털 업로드 화면의 E2E 보장이 0 이다(첫 테스트는 실패, 둘째는 공허). CI 에 E2E 를 붙이면 첫 테스트가 상시 red 라 스위트 자체가 무시되기 쉽다.
- **수정 방향(제안)**: ①`PortalUploadPage` POM 신설(또는 스펙이 직접 `/portal/uploads` 로 이동) ②`#portal-image-input`/`#portal-video-input` 을 명시 로케이터로 사용 ③`if (count>0)` 가드 제거하고 무조건 단언 ④fixture 를 실제 최소 JPEG(매직바이트 `FFD8FF`)로 교체. ⚠ 구현은 하지 않았다.

---

### MEDIUM

### [H-ISSUE-01] TC-FE-016 인접 — 운영 빌드 기본값이 `VITE_TOKEN_INGRESS=all` 이라 URL `?token=` 인계 채널이 활성 (2차 H-ISSUE-01 미해소 이월, 3차도 실동작 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "인증·진입" 절 — "브라우저 스토리지(localStorage/sessionStorage) 공유로 JWT 전달. URL 쿼리 파라미터(`?token=`) 방식 미사용". 운영 산출물에서 URL 채널이 꺼져 있어야 JWT 가 웹서버 access log·프록시 로그·브라우저 주소창/방문기록/Referer 에 평문으로 남지 않는다(CWE-598).
- **현재 동작(이슈 내용)**: 모든 운영 배포 경로의 기본값이 여전히 `all`(url → localStorage → cookie 순 시도)이고, 실브라우저에서 URL 채널만으로 인증이 성립한다.
  ```
  frontend/Dockerfile:33                                   ARG VITE_TOKEN_INGRESS=all
  docker-compose.yml:325                                   VITE_TOKEN_INGRESS: ${VITE_TOKEN_INGRESS:-all}
  .env.example:141                                         VITE_TOKEN_INGRESS=all
  deploy/onprem/scripts/package/20-build-frontend.sh:32     :-all
  deploy/onprem/scripts/install/build-from-source.sh:185    :-all
  docker inspect klid-frontend → VITE_TOKEN_INGRESS=all    (실효값)
  ```
  ```ts
  // frontend/src/features/auth/tokenIngress.ts:23,130-132
  const v = (import.meta.env.VITE_TOKEN_INGRESS as string | undefined) ?? 'all';   // 미설정·오타 → all 폴백
  if (strategy === 'url' || strategy === 'both' || strategy === 'all') {
    candidates.push(params.urlToken);          // ← URL 채널 상시 개방
  }
  ```
- **재현/확인 경로**:
  ```
  브라우저 → http://localhost:13000/ingress?token=<REVIEWER JWT>
    → 최종 /dashboard, sessionStorage['klid_jwt'] 적재, 검수자 화면 정상 렌더
  # 대조: localStorage / cookie 채널도 각각 단독으로 인증 성립
  ```
- **영향**: 보안 — CWE-598(Sensitive Information in Query String). 인계 URL 이 리버스프록시/WAF access log·브라우저 히스토리·Referer 헤더에 JWT 원문을 남긴다. 정책 문서와 구현이 어긋난 상태가 **3개 회차 연속** 유지 중.
- **수정 방향(제안)**: ①운영 3경로(`Dockerfile:33`·`docker-compose.yml:325`·온프렘 스크립트 2종)의 기본값을 `localStorage` 로 바꾸고, URL 채널이 필요한 환경만 명시 opt-in ②`tokenIngress.ts:23` 의 미인식 값 폴백을 `all` → `localStorage` 로 바꾸고 `console.warn` 을 남긴다 ③`vite-env.d.ts:5` 의 타입을 실제 5종으로 정정 ④`upstreamLoginUrlDeployWiring.test.ts` 와 같은 방식의 배포 배선 회귀 가드 추가. ⚠ 구현하지 않음.

### [H-ISSUE-02] TC-FE-016 / TC-E2E-001 — 온프렘 운영 패키징 기본값 `VITE_DEV_LOGIN_ENABLED=true` 때문에 ①`/dev/login` 이 운영 산출물에 실리고 ②JWT 를 localStorage 에 영구 저장하며 ③미인증 진입이 상위 로그인으로 가지 않는다 (2차 H-ISSUE-03 미해소 이월 + 영향 확장)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: ①prod 빌드에서 `DevLoginPage` 청크가 dead-code 제거되어야 한다 ②TC-FE-016 "토큰 저장소=sessionStorage(XSS 노출면 축소)" 가 배포 산출물 전체에서 성립해야 한다 ③`CLAUDE.md` "세션 만료 시 각 상위 시스템 로그인 페이지로 리다이렉트".
- **현재 동작(이슈 내용)**:
  ```
  deploy/onprem/scripts/package/20-build-frontend.sh:33      export VITE_DEV_LOGIN_ENABLED="${VITE_DEV_LOGIN_ENABLED:-true}"
  deploy/onprem/scripts/install/build-from-source.sh:183-184 (VITE_DEV_LOGIN_ENABLED / VITE_DEV_UPLOAD_ENABLED 둘 다 :-true)
  ```
  ```ts
  // frontend/src/lib/devLogin.ts:11-13
  export function isDevLoginEnabled(): boolean {
    return import.meta.env.DEV || import.meta.env.VITE_DEV_LOGIN_ENABLED === 'true';
  }
  // frontend/src/features/auth/DevLoginPage.tsx:153
  localStorage.setItem(LOCAL_STORAGE_TOKEN_KEY, data.token);   // ← JWT 영속 저장
  ```
  실측: 미인증 `/dashboard` → `/ingress` → `/dev/login`(역할 3종 라디오 + "토큰 발급 + 진입" 버튼). 저장된 `localStorage['klid-jwt-token']` 은 이후 localStorage 채널로 자동 재인증된다. ⚠ RoleGuard 만료·401 경로는 영향 없음(그 두 경로는 `isDevLoginEnabled()` 를 거치지 않고 곧바로 `redirectToUpstream` 호출) — 이 이슈는 `/ingress` 진입 실패 경로 한정.
- **재현/확인 경로**:
  ```
  신규 시크릿 창 → http://localhost:13000/dashboard  → /dev/login 도달 (상위 로그인 아님)
  /dev/login 에서 토큰 발급 → localStorage['klid-jwt-token'] 에 JWT 원문 잔존
  # BE 최종 방어는 살아 있음(fail-closed): authoring.dev.login.enabled = ${DEV_LOGIN_ENABLED:false} (prd 미설정 → false)
  ```
- **영향**: 보안 — CWE-1188(안전하지 않은 기본값) + XSS 시 토큰 영속 탈취(CWE-522). 기능 — 운영에서 세션이 끊긴 사용자가 관제/포털 로그인으로 복귀하지 못하고 개발용 화면을 보게 된다.
- **수정 방향(제안)**: ①온프렘 두 스크립트의 기본값을 `false` 로 뒤집는다 ②`DevLoginPage` 의 `localStorage.setItem` 을 제거하고 `useAuthStore.setToken`(sessionStorage) 만 쓴다 ③배포 스크립트 dev 플래그 기본값 회귀 가드 추가. ⚠ 구현하지 않음.

### [H-ISSUE-22] TC-FE-051 / TC-FE-053 인접 — WORKER 라벨링 화면이 REVIEWER 전용 `/v1/manage/configs` 를 호출해 AI 정밀도 시스템 설정이 작업자에게 영영 반영되지 않는다 (3차 신규채번, part2)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `LabelingPage.tsx:722-725` 는 시스템 설정(`YOLO_CONF_THRESHOLD`, `POLYGON_SIMPLIFY_TOLERANCE`)을 AI 탐지 모달 슬라이더 기본값으로 프리필하기 위해 만들어졌다. 라벨링의 주 사용자는 WORKER 이므로, REVIEWER 가 설정한 값이 WORKER 화면에도 반영되어야 이 기능이 성립한다.
- **현재 동작(이슈 내용)**: 프리필 소스가 REVIEWER 전용 관리 API 하나뿐이라 WORKER 는 항상 403 → `sysConfigs===undefined` → 코드 상수로 폴백한다.
  ```ts
  // frontend/src/pages/label/LabelingPage.tsx:722-725
  const { data: sysConfigs } = useConfigs();
  const defaultConfThreshold =
    sysConfigs?.YOLO_CONF_THRESHOLD != null ? sysConfigs.YOLO_CONF_THRESHOLD / 100 : undefined;
  // → undefined → PrecisionSliders.tsx:14,20  SENSITIVITY_DEFAULT=0.25 / TOLERANCE_DEFAULT=1 폴백
  ```
  실측:
  ```
  GET /api/v1/manage/configs  (REVIEWER) → 200  YOLO_CONF_THRESHOLD=25, POLYGON_SIMPLIFY_TOLERANCE=1.0
  GET /api/v1/manage/configs  (WORKER)   → 403  {"errorCode":"FORBIDDEN"}
  ```
  현재는 코드 상수와 DB 값이 우연히 같아(25→0.25) 증상이 안 보이지만, REVIEWER 가 60 으로 바꾸면 REVIEWER 화면만 0.60, WORKER 화면은 계속 0.25 가 된다. 부수 효과로 라벨링 진입 1회당 403 이 2회 발생(`retry:1`).
- **재현/확인 경로**: WORKER 토큰으로 `/label/468` 진입 → Network `GET /api/v1/manage/configs` 403×2 → AI 탐지 모달 슬라이더 = 코드 상수 0.25. REVIEWER 토큰으로 `curl .../v1/manage/configs` 대조.
- **영향**: 기능(설정 무효화) — 운영자가 조정한 AI 정밀도 기본값이 실제 라벨링 작업자에게 도달하지 않는다. 보안 영향 없음(BE fail-closed 정상). 콘솔 403 노이즈가 실제 오류를 가림.
- **수정 방향(제안)**: ①BE 에 읽기 전용 프리셋 조회 엔드포인트(WORKER+REVIEWER 허용, AI 정밀도 키만 화이트리스트 노출) 신설 권장, 또는 ②`/v1/manage/configs` GET 만 WORKER 허용(키 화이트리스트 필요). 어느 쪽이든 `useConfigs()` 에 `retry:false` + 403 조용한 폴백 적용. ⚠ 구현은 하지 않는다.

### [H-ISSUE-42] TC-FE-197 — 잠금(409)까지 "다른 사용자가 먼저 저장했습니다" 낙관적 동시성 충돌 다이얼로그로 안내된다 (H-ISSUE-41 파생, 3차 신규채번, part3)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 409 라도 사유가 다르면 안내가 달라야 한다. 낙관적 잠금 충돌(다른 사용자 선저장)은 "최신 라벨 불러오기"가 해법이지만, 재비식별 잠금 409 는 최신 라벨을 불러와도 저장할 수 없다.
- **현재 동작(이슈 내용)**: `LabelingPage.tsx:586` 이 `status===409` 만 보고 분기한다. 잠금 사유 409 도 제목 "다른 사용자가 먼저 저장했습니다" + 확인 버튼 "최신 라벨 불러오기" 다이얼로그로 뜬다(설명문만 BE 메시지가 실려 사유는 맞지만 제목·행동유도는 어긋남).
- **재현/확인 경로**: H-ISSUE-41 재현으로 영상을 LOCKED 로 만든 뒤(현재는 H-ISSUE-41 때문에 FE 가 잠금을 몰라 저장이 시도됨) 화면에서 저장 → 409 → 위 다이얼로그.
- **영향**: 사용자가 "최신 라벨 불러오기"를 눌러 미저장 작업만 잃고 여전히 저장하지 못한다. H-ISSUE-41 을 고치면 저장 버튼이 애초에 비활성화돼 노출 빈도는 크게 줄지만, 진입 직후 다른 세션이 잠그는 경우는 남는다.
- **수정 방향(제안)**: 409 를 명시적 사유 코드(`CONFLICT_STALE_VERSION` vs `CONFLICT_LOCKED`)로 분기해 잠금 사유는 "불러오기" 유도 없이 안내 전용으로 처리. ⚠ 구현은 하지 않았다.

### [H-ISSUE-61] TC-FE-263 / 266 / 269 / 279 / 283 / 288 / 291 / 292 — 근거 `file:line` 드리프트 8건 (카탈로그 정합성, 3차 신규채번, part4)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그의 `근거` 컬럼은 다음 회차 검증자가 그 줄을 열어 기대결과와 대조하는 유일한 좌표다. 어긋나면 검증자가 무관한 함수를 읽고 "코드가 없다/다르다"로 오판하거나 대조 자체를 포기한다.
- **현재 동작(이슈 내용)**:

  | TC | 카탈로그 인용 | 실제 위치 | 성격 |
  |----|---|---|---|
  | TC-FE-263 | `useLabelingShortcuts.ts:216,223,247` | `196`(fail-closed OR 판정)·`203`(ESC)·`227`(키맵 차단) | -20행급, 인용 3개 전부 무관한 줄 |
  | TC-FE-266 | `OverlayLayer.tsx:357-381` | 핵심 코드(`setPendingConfirm(false)`)는 387행 — 인용 범위 밖 | 범위 부족 |
  | TC-FE-269 | `CanvasShell.tsx:71-104,185-193` | `66-99`·`180-187` | -5행 |
  | TC-FE-279/283/288/291/292 | `LabelingPage.tsx`/`labelingKeymap.ts` 각 인용 | 전부 -1행 | 사후 드리프트 |

  원인이 둘로 갈린다: ①`LabelingPage.tsx` 계열 -1행은 카탈로그 4회차(`9f99db50`) 이후 `0d290c4e`/`890894c5` 가 파일을 건드린 불가피한 사후 드리프트 ②`useLabelingShortcuts.ts`(-20행)·`CanvasShell.tsx`(-5행)·`labelingKeymap.ts`(-1행)는 인용 대상 커밋(`dcdbb827`·`d8a7a2cc`)이 4회차 이전인데도 어긋남 — 4회차가 "H-3 원본 절 TC-FE-033~087,197,199~201"만 재확인하고 라운드2·3 신설분(261~296)은 대조 대상에서 누락시킨 것.
- **재현/확인 경로**: `sed -n '196p;203p;227p' frontend/src/features/label/hooks/useLabelingShortcuts.ts` / `git merge-base --is-ancestor 890894c5 9f99db50` → not ancestor.
- **영향**: 기능 영향 없음(카탈로그 품질). 다음 회차 검증자가 어긋난 라인을 읽고 "구현 없음"으로 오판할 수 있다.
- **수정 방향(제안)**: **본 회차에서 8건 전부 Edit 로 정정 완료**(담당 라인범위 143~216 내부). 후속으로 회차 운영 규칙에 "신설 케이스도 다음 회차 근거 재확인 대상"을 명시할 것.

### [H-ISSUE-82] TC-E2E-014 — 라벨링 플로우 E2E 의 핵심 단언이 전부 조건부이고, 대상 프레임이 픽스처 사용자에게 403 이라 실질 커버리지가 0 이다 (2차 H-ISSUE-141 미해소 + 근본원인 신규, 3차 신규채번, part5)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WORKER 가 목록→캔버스→BBox 작성→저장→토스트까지 완주하는 것이 이 케이스의 보장 대상이다. 라벨링 캔버스는 핵심 화면이라 조건부가 아닌 확정 단언이 필요하다.
- **현재 동작(이슈 내용)**:
  ```ts
  // e2e/specs/labeling-flow.spec.ts:29-42
  if ((await labeling.bboxToolBtn.count()) > 0) { await labeling.bboxToolBtn.first().click(); }
  if ((await labeling.canvas.count()) > 0)      { await labeling.drawBoundingBox(...); }
  if ((await labeling.saveBtn.count()) > 0)     { await labeling.save(); await expect(...).toBeVisible(); }
  ```
  세 단계 모두 요소가 없으면 조용히 통과한다. 실제로 요소가 없다 — `workerPage`(userNo 1003)로 `srcSn=1` 에 진입하는데:
  ```
  GET /api/v1/frames/1/labels  (userNo 1003) → 403 {"errorCode":"FORBIDDEN"}
  GET /api/v1/frames/1/labels  (userNo 2001) → 200
  ```
  배정이 없는 사용자로 남의 프레임에 진입하므로 `canvas-shell` 대기가 `.catch(() => undefined)` 로 삼켜지고 이후 3개 `if` 가 모두 false 가 된다.
- **재현/확인 경로**: 위 curl 2줄. 또는 `E2E_BE_URL`·`BASE_URL` 을 맞춘 뒤 `npx playwright test labeling-flow --reporter=list` 로 "통과하지만 아무 것도 안 한" 상태 확인.
- **영향**: 기능 — "E2E 스펙 11개 보유" 통계가 실제 보장과 어긋난다. 라벨링 저장 회귀가 이 스펙으로는 절대 잡히지 않는다.
- **수정 방향(제안)**: ①`labelerPage`(userNo 2001, 실제 LABELER 배정 보유)로 픽스처 사용자를 교체하거나 `resolveWorkflowFixture()` 같은 동적 해석 사용 ②`if (count>0)` 가드 전부 제거하고 `toBeVisible()` + PUT 200 + 정확한 토스트 문구('저장됨')로 단언(`labeling-review-full-flow.spec.ts:94-120` 이 모범 사례). ⚠ 구현은 하지 않는다.

### [H-ISSUE-83] TC-E2E-015 — 바운딩박스 버튼 렌더 단언이 항상 참(`>=0`)인 공허한 단언이다 (2차 H-ISSUE-142 미해소 이월, 3차 신규채번, part5)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨링 진입 시 도구바에 '바운딩 박스' 버튼이 실제로 렌더되어야 한다(포털 게이팅 회귀의 대조군이기도 함).
- **현재 동작(이슈 내용)**:
  ```ts
  // e2e/specs/worker-labeling.spec.ts:32-34
  const cnt = await labeling.bboxToolBtn.count();
  expect(cnt).toBeGreaterThanOrEqual(0);   // Locator.count() 는 음수가 될 수 없다 → 항상 참
  ```
  주석의 이유("정확한 텍스트가 변경 가능")도 현재는 성립하지 않는다 — POM 이 이미 `getByRole('button',{name:'바운딩 박스',exact:true})` 로 접근성 이름을 확정해 쓰고 있다.
- **재현/확인 경로**: 해당 3줄 코드. 도구바를 통째로 제거해도 이 테스트는 통과한다.
- **영향**: 회귀 — 내부 라벨링 도구바가 사라져도 잡히지 않는다.
- **수정 방향(제안)**: `await expect(labeling.bboxToolBtn).toHaveCount(1)` 또는 `toBeVisible()` 로 교체. H-ISSUE-82 의 픽스처 사용자 문제(userNo 1003→403)도 함께 해결해야 실효가 생긴다. ⚠ 구현은 하지 않는다.

### [H-ISSUE-84] (H-15 전반) E2E 하네스의 기본 대상 주소가 로컬 compose 스택과 어긋나 있어 E2E 가 회귀 baseline 에 한 번도 포함된 적이 없다 (3차 신규채번, part5)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 검수 종결 워크플로를 유일하게 종단 검증하는 자산(TC-E2E-016~019)이 로컬 표준 스택 위에서 그대로 실행 가능해야 한다.
- **현재 동작(이슈 내용)**:
  ```
  e2e/fixtures/be-client.ts:48   export const BE_BASE = process.env.E2E_BE_URL || 'http://127.0.0.1:8080';
  playwright.config.ts:20        baseURL: process.env.BASE_URL || 'http://127.0.0.1:5174'
  ```
  실제 검증 스택은 backend `18081:8080`, frontend `13000:5174` 로 발행된다. 로컬에 8080 리스너 없음. 환경변수 없이 `npm run e2e` 를 돌리면 `issueDevToken` 이 ECONNREFUSED 로 죽는다. 3차 `_raw/test-baseline.md` 에도 backend·frontend vitest·ai-server 만 있고 Playwright 실행 기록이 없다 — 1~3차 어느 회차에도 E2E baseline 이 없다.
- **재현/확인 경로**: `E2E_BE_URL=http://127.0.0.1:18081 BASE_URL=http://127.0.0.1:13000` 을 주면 대상이 맞는다.
- **영향**: 기능/프로세스 — H-ISSUE-143(2차)을 고쳐 픽스처를 동적화했지만 한 번도 실행으로 확인된 적이 없다.
- **수정 방향(제안)**: ①compose 포트를 기본값으로 고정하는 `.env.e2e` 또는 스크립트 신설 ②`playwright.config.ts` 의 `webServer.command` 선택 가능화 ③검증 회차 baseline 에 Playwright 실행 항목 추가. ⚠ 구현은 하지 않는다.

### [H-ISSUE-101] TC-FE-130 — BatchStageIndicator 단계 상태가 스크린리더에 노출되지 않음 (2차 H-ISSUE-70 미해소 재확인, 3차 신규채번, part6)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: WCAG 2.1 AA 준수 요구 및 `component.md` "색상만으로 정보 전달 금지" 원칙에 따라, 배치 단계별 상태(완료/진행중/실패/대기)는 스크린리더 사용자도 알 수 있어야 한다.
- **현재 동작(이슈 내용)**: `BatchStageIndicator.tsx` 의 `StageIcon`(:30-57) 이 렌더하는 `Check`/`Loader2`/`X` 아이콘은 전부 `aria-hidden`(각 :34,41,48)이고 PENDING 상태는 장식용 `<div>`(:52-56)뿐이다. 단계 텍스트는 단계 이름만이며 상태를 나타내는 `aria-label`/`sr-only`/`role` 속성이 컴포넌트 전체에 0건이다(`grep` 재확인, 3차 시점도 동일). 2차 검증에서 동일 결함이 `H-ISSUE-70`으로 이미 보고됐으나 3차 시점 코드(HEAD `e065da42`)에도 수정 미반영.
- **재현/확인 경로**: `grep -n "aria-hidden\|aria-label" frontend/src/components/common/BatchStageIndicator.tsx` → aria-hidden 3건, aria-label 0건.
- **영향**: 접근성(WCAG 2.1 AA 1.1.1/4.1.2 상당) — 시각장애 사용자가 배치 처리 실패(FAIL) 여부를 화면에서 인지할 수 없다. 보안 영향 없음.
- **수정 방향(제안)**: 각 단계 아이콘 wrapper 또는 단계 컨테이너에 상태를 서술하는 `aria-label`/`sr-only` 텍스트 추가(아이콘 자체의 `aria-hidden` 은 유지). ⚠ 구현은 하지 않는다.

### [H-ISSUE-121] TC-FE-146 — 검수 화면이 증강/해상도 파생 표시를 지원하지 않는다 (2차 H-ISSUE-81 미해소 재확인, 병합 시 번호 부여 — part7 원문은 신규 번호 미부여)
- **심각도**: MEDIUM (2차와 동일 등급 유지)
- **기대 동작(기대효과)**: 검수 화면(`ReviewPage`)이 파생영상(증강/해상도 변환)을 검수할 때 원본 참조(`orgnlRawSn`)·파생 종류를 표시해 검수자가 "이것이 파생 결과물"임을 인지해야 한다.
- **현재 동작(이슈 내용)**: `backend/src/main/java/kr/co/cudo/authoring/review/dto/ReviewResponse.java` 전체 필드(id/cctvName/workerId/workerName/submittedAt/labelCount/status/videoId/dataSttsCd/version/updDt/eventName/eventTypeCd)에 `orgnlRawSn`/파생 종류 필드가 없다(3차 실측 재확인). FE `features/review/types.ts` 의 `Review` 인터페이스도 동일하게 파생 필드가 없다. `ReviewMetaPanel.tsx`·`ReviewHeader.tsx` 에 `orgnlRawSn`/`ORGNL_RAW_SN`/`vmsClipId`/증강 키워드 매치 0건. 이번 회차에도 관련 커밋 없음(`git log` 확인, review/dto 변경이력은 "이슈 댓글 작성자 이름 표시"(`a3dc1579`)뿐).
- **재현/확인 경로**: `GET /v1/reviews/{id}` 응답 필드 확인(`orgnlRawSn` 없음). `ReviewMetaPanel.tsx`/`ReviewHeader.tsx` grep.
- **영향**: 기능(검수 UX) — REVIEWER 가 검수 화면만으로는 대상이 파생영상인지 원본인지 구분할 수 없다. 보안 영향 없음.
- **수정 방향(제안)**: `ReviewResponse` 에 `orgnlRawSn`(nullable)·파생 종류 필드 추가, `ReviewMetaPanel`/`ReviewHeader` 에 배지 표시. ⚠ 구현은 하지 않는다.

### [H-ISSUE-141] TC-FE-242 인접 — WORKER KPI 4카드가 "전체 기준"이 아니라 현재 페이지 20행만 집계한다 (2차 H-ISSUE-146 미해소 이월, 3차 신규채번, part8)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` "목록 화면 정렬·필터 정책" 구속 규칙 — "필터·집계는 BE 에서 전체 기준으로 처리하며 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다." REVIEWER 5카드는 서버 집계(`/v1/tasks/board/summary`)를 쓰므로 이 규칙을 지킨다. WORKER 4카드도 헤더 "전체 N건"과 같은 집합을 말해야 한다.
- **현재 동작(이슈 내용)**:
  ```tsx
  // frontend/src/features/task/components/TaskWorkerKpiCards.tsx:20-21
  const count = (status: RowStatus) => rowStatuses.filter((s) => s === status).length;
  // frontend/src/pages/TaskListPage.tsx:484-487
  const workerRowStatuses = useMemo(() => pagedRows.map((r) => r.rowStatus), [pagedRows]);
  ```
  `pagedRows` 는 현재 페이지(최대 20건)만 담는다. "전체 작업" 카드는 21건 이상 배정된 WORKER 에게 항상 페이지 크기(20)로 고정되고, 헤더의 "전체 N건"(서버 `totalElements`)과 어긋난다. 소스 주석은 "REVIEWER 전용 집계 API(403)라 구 동작 그대로"라고 사유를 밝히나 정책이 금지한 페이지 단위 집계다.
- **재현/확인 경로**:
  ```
  # 시드 worker 2001 배정 49건(실측)으로 이미 재현 조건 충족
  curl -H "Authorization: Bearer $WORKER_TOKEN" "http://localhost:18081/api/v1/assignments?workerId=2001&page=0&size=20" → totalElements 49
  화면: KPI "전체 작업" 카드는 pagedRows.length = 20 으로 표시 → 헤더와 불일치
  ```
- **영향**: 데이터 정합/사용자 오판(보안 영향 없음). 작업자가 본인 잔여 작업량을 실제보다 적게 인식할 수 있다.
- **수정 방향(제안)**: ①BE 에 WORKER 도 호출 가능한 배정 집계 엔드포인트 신설 후 REVIEWER 5카드와 동일 "전체 기준" 축으로 통일하거나 ②카드 라벨을 "이 페이지 기준"으로 명시하고 "전체 작업" 값만 서버 `totalElements` 로 대체. ⚠ 구현은 하지 않는다.

---

### LOW

### [H-ISSUE-21] TC-FE-036 인접 — 라벨링 에러 화면이 `resolveApiMessage` 정책을 우회해 `error.message` 를 그대로 렌더한다 (2차 H-ISSUE-06 미해소 이월, 3차 신규채번, part2)
- **심각도**: LOW
- **기대 동작(기대효과)**: `lib/api/resolveApiMessage.ts:4,16-19` 가 "400/409/412 만 서버 문구 노출, 401/403/5xx·비-ApiError 는 fallback"을 단일 지점에서 강제한다(CWE-209 방어). 에러 표시 경로가 이 유틸을 통과해야 정책이 실효를 갖는다.
- **현재 동작(이슈 내용)**: 2차 H-ISSUE-06 이 지목한 `LabelingPage.tsx:1144` 는 라인만 밀렸을 뿐 그대로 남아 있다.
  ```tsx
  // frontend/src/pages/label/LabelingPage.tsx:1151-1153
  <p className="text-sm text-gray-400 mb-4">{error.message}</p>
  ```
  같은 파일의 저장 실패 토스트(`:594`)도 `extractBeMessage()` 를 쓰는데, 이 유틸은 상태코드를 보지 않고 무조건 서버 문구를 채택한다.
- **재현/확인 경로**: `/label/999999` 진입 → 화면 "라벨 조회 실패 / 프레임을 찾을 수 없습니다."(404 문구 그대로 노출).
- **영향**: 보안(정보 노출, 잠재) — 현재 BE 는 500 에 고정 문구만 내려주므로 실제 유출은 없다. 다만 어느 예외 핸들러가 상세 메시지를 담기 시작하면 이 경로들만 조용히 새고, 정책이 한 곳에 모이지 않아 감사가 어렵다. CWE-209.
- **수정 방향(제안)**: `LabelingPage.tsx:1153` 을 `resolveApiMessage(error, '라벨을 불러오지 못했습니다...')` 로 교체. ESLint `no-restricted-syntax` 도입 검토. ⚠ 구현은 하지 않는다.

### [H-ISSUE-23] (카탈로그 정합성) H-3 앞 1/3 근거 `file:line` 26건 +1 드리프트 + 기대결과 오류 2건 — 이번 회차에서 정정 완료 (3차 신규채번, part2)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 존재 이유가 "근거 정확도"이므로 `file:line` 이 실제 코드를 가리켜야 한다.
- **현재 동작(이슈 내용)**: 4회차(`9f99db50`)가 `e58aa086` 기준으로 H-3 근거를 전수 재확인했으나, 그 직후 커밋 `0d290c4e`(영상 단위 개인정보 메타 화면)가 `LabelingPage.tsx` 에 import 1줄(`:49`)을 추가해 파일 49행 이후 전체가 +1 밀렸다. 담당 범위 31건 중 `LabelingPage.tsx` 인용 26건 전부가 어긋났다. 추가로 TC-FE-034("data-testid" 오류)·TC-FE-057(추적 문구 오류) 서술 오류 2건.
- **재현/확인 경로**: `git diff e58aa086 HEAD -- frontend/src/pages/label/LabelingPage.tsx`.
- **영향**: 검증 효율 — 다음 회차 검증자가 어긋난 라인을 읽고 "구현 없음"으로 오판할 수 있다. 기능/보안 영향 없음.
- **수정 방향(제안)**: **이미 정정함** — 26건 +1 시프트 + 기대결과 2건 교체. 파일 상단 변경 이력 표 회차 행은 병합 담당(본 문서)이 5회차로 1회만 추가.

### [H-ISSUE-62] TC-FE-262 — 근거가 되돌리기·버전 롤백 축을 커버하지 않고, 롤백 busy 차단에 자동 테스트가 0건이다 (3차 신규채번, part4)
- **심각도**: LOW
- **기대 동작(기대효과)**: TC-FE-262 는 되돌리기·버전 롤백·비식별 신고 3축의 busy 차단을 보장한다. 롤백은 작업본을 바꾸는 편집이라, 저장 in-flight 중 실행되면 저장 성공의 `clearDirty()` 가 되돌린 분의 미저장 표식까지 지워 무음 소실이 난다.
- **현재 동작(이슈 내용)**: 구 인용 `editBlocking.test.tsx:259,280` 중 259 는 되돌리기·롤백 축이 아니다. `editBlocking.test.tsx` 13개 `it()` 어디에도 롤백/되돌리기 케이스가 없고, `LabelHistoryPanel.revert.test.tsx` 도 busy 를 세우지 않는다 — "버전 롤백은 busy 중 차단된다"에 자동 회귀 가드가 없다. 구현(`disabled={editBlocked}`)은 정상이나 누가 떼도 테스트가 못 잡는다.
- **재현/확인 경로**: 본 회차 실동작으로 정상 확인(busy 중 되돌리기 버튼 11개 `disabled=true`).
- **영향**: 회귀 방어 공백(기능 결함 아님).
- **수정 방향(제안)**: ①카탈로그 근거를 실제 축으로 교정(본 회차 완료) ②`LabelHistoryPanel.revert.test.tsx` 에 `beginBusy` 후 `disabled` 단언 1건 추가.

### [H-ISSUE-63] TC-FE-294 인접 — `LabelPanel.tsx` 가 어디서도 import 되지 않는 사(死)코드로 남았다 (`LabelSidebar` 폐지 잔재, 3차 신규채번, part4)
- **심각도**: LOW
- **기대 동작(기대효과)**: 결정 3(2026-08-03, `d8a7a2cc`)은 좌측 상시 라벨 패널을 컴포넌트·테스트째로 삭제했다. 표시명 경유 지점은 카탈로그가 "6곳"으로 못 박혀 있다.
- **현재 동작(이슈 내용)**: `features/label/components/LabelPanel.tsx:44` 가 `resolveLabelDisplayName(cls)` 를 호출하는 7번째 표시 지점으로 살아 있으나, 전역 grep 결과 자기 테스트 외 import 0건 — 렌더 경로가 없다.
- **재현/확인 경로**: `grep -rn 'LabelPanel' --include='*.tsx' src/ | grep -v __tests__ | grep -v 'components/LabelPanel.tsx'` → 출력 없음.
- **영향**: 기능 영향 0. 번들 잔존 가능성 + 다음 회차 검증자가 "표시 지점 7곳인데 카탈로그는 6곳"으로 재발견해 논의 반복.
- **수정 방향(제안)**: `LabelPanel.tsx` + `__tests__/LabelPanel.test.tsx` 삭제. 삭제 전까지는 카탈로그 주석(본 회차 추가 완료)으로 오인 방지.

### [H-ISSUE-85] (TC-E2E-014/015 인접) `TEST_VIDEO_WITH_LABEL=1` 하드코딩이 남아 픽스처 파일의 자기 선언과 모순된다 (3차 신규채번, part5)
- **심각도**: LOW
- **기대 동작(기대효과)**: `e2e/fixtures/test-data.ts:9-13` 이 스스로 "영상(rawSn)·프레임(srcSn) 은 하드코딩하지 않는다 — 시드가 재적재되면 PK 가 통째로 바뀌어 스펙 전체가 404 로 죽는다(H-ISSUE-143 실사고)"라고 선언했다. 다른 상수도 그 규칙을 따라야 한다.
- **현재 동작(이슈 내용)**: `worker-labeling.spec.ts` 가 `TEST_VIDEO_WITH_LABEL=1` 을 쓰는데 현 스택에서 srcSn=1 은 userNo 1003 에게 403(H-ISSUE-82). H-ISSUE-143 과 동일 실패 모드가 축소된 형태로 남아 있다.
- **재현/확인 경로**: `GET /api/v1/frames/1/labels` 를 userNo 1003/2001 토큰으로 각각 호출 → 403/200.
- **영향**: 회귀 — 시드가 바뀌면 같은 방식으로 다시 깨진다. 현재는 조건부 단언 때문에 실패조차 하지 않고 침묵한다.
- **수정 방향(제안)**: `resolveWorkflowFixture()` 방식으로 "라벨을 보유하고 현재 사용자가 접근 가능한 프레임"을 API 로 해석하는 `resolveLabelableFrame()` 헬퍼로 대체. ⚠ 구현은 하지 않는다.

### [H-ISSUE-86] TC-E2E-016/017/018/019 — 차단 결함은 해소됐으나 완주 실행이 이번 회차에서 확인되지 않았다 (검증 갭, 제품 결함 아님, 3차 신규채번, part5)
- **심각도**: LOW
- **기대 동작(기대효과)**: 라벨링→저장→제출→반려→롤백→재제출→승인 전 구간이 실제 스택에서 완주되어야 한다.
- **현재 동작(이슈 내용)**: 스펙·픽스처는 정상화됐고(2차 HIGH #10 해소) 전제도 실동작으로 확인됐다(WORKER 2001 배정 49건·`firstSrcSn` 보유). 그러나 ①본 회차 규칙이 테스트 실행을 금지하고 ②하네스 기본 대상 포트가 스택과 어긋나(H-ISSUE-84) 즉시 실행이 불가해 완주 여부는 미확인이다.
- **재현/확인 경로**: `E2E_BE_URL=http://127.0.0.1:18081 BASE_URL=http://127.0.0.1:13000 npx playwright test labeling-review-full-flow --reporter=list`(다음 회차 또는 별도 실행 사이클에서 1회 완주 필요).
- **영향**: 검증 신뢰도 — "검수 종결 E2E 커버리지 확보"가 코드 근거로만 성립하고 실행 근거가 없다.
- **수정 방향(제안)**: H-ISSUE-84 를 먼저 처리한 뒤 이 4건을 실행해 PASS 로 승격. 실행 결과를 `_raw/test-baseline.md` 에 E2E 섹션으로 추가.

### [H-ISSUE-87] (카탈로그 정합성) H-11·H-15 근거 `file:line` 드리프트 6건 + TC-E2E-016 전제 stale — 이번 회차에서 직접 정정 완료 (3차 신규채번, part5)
- **심각도**: LOW
- **기대 동작(기대효과)**: 카탈로그의 존재 이유가 근거 정확도다.
- **현재 동작(이슈 내용)**: TC-FE-165/166/169/170/275(근거 라인 드리프트) + TC-E2E-016(전제가 이미 폐기된 하드코딩 픽스처 전제를 들고 있어 다음 회차가 H-ISSUE-143 을 "미해소"로 오판할 소지) + TC-E2E-019(승인/최종 단언 라인 분리 표기) — 총 7행.
- **재현/확인 경로**: 각 파일 라인 직접 대조(part5 §4 표 참조).
- **영향**: 카탈로그 정합성.
- **수정 방향(제안)**: 이번 회차에서 담당 라인범위(H-11·H-15) 안에서 Edit 로 직접 정정 완료. 프로덕션 코드는 수정하지 않았다.

---

## H클러스터 판정 집계 (병합 담당자 산출 — §7 표기 규칙 준수, 각 파트 원본 표 재합산)

> part3 의 "조건부 PASS"·"부분 PASS(확인필요)" 비표준 라벨은 §7 6토큰 규칙에 맞춰 **PARTIAL**(064·067, 코드는 성립하나 실동작 진입조건 미충족) / **확인필요**(065·081, 좌표clamp·트랙rename API왕복 미검증)로 정규화해 재합산했다. 그 외 파트는 원 표기 그대로.

| 판정 | 건수 | 근거(파트별) |
|---|--:|---|
| PASS | 322 | part1 31 + part2 31 + part3 27 + part4 30 + part5 21 + part6 37 + part7 65 + part8 80 |
| PARTIAL | 9 | part1 2(TC-FE-016·TC-E2E-001) + part3 2(TC-FE-064·067, 원표기 "조건부 PASS"→정규화) + part5 4(TC-E2E-016~019) + part6 1(TC-FE-130) |
| FAIL | 4 | part5 3(TC-E2E-011·014·015) + part7 1(TC-FE-146) |
| BLOCKED | 0 | — |
| N/A | 0 | — |
| 확인필요 | 2 | part3 2(TC-FE-065·081, 원표기 "확인필요"/"부분 PASS(확인필요)") |
| **검증 대상 계(분모)** | **337** | 322+9+4+0+0+2 = 337 |
| **표 행 실측 총계(카탈로그)** | **337** | `grep -cE '^\| *~*TC-' docs/test-cases/H-frontend-e2e.md` 실측(분모와 일치 — 신규/폐기 0건) |

- PASS율 = 322/337 = **95.5%**. PASS+PARTIAL(사실상 통과) = 331/337 = **98.2%**.
- **카탈로그 정정**: `docs/test-cases/H-frontend-e2e.md` 근거 `file:line` 드리프트 + 기대결과 오류 **총 55건**(`git diff docs/test-cases/H-frontend-e2e.md` 실측 — 55 insertions/55 deletions, 변경된 고유 TC-ID 55개와 정확히 일치). 파트별 내역: part1 3(TC-FE-008·190·193) · part2 26(TC-FE-033~050·052~058·063) · part3 5(TC-FE-067·079·085·087·197) · part4 10(TC-FE-262·263·266·269·279·283·288·291·292·294) · part5 7(TC-FE-165·166·169·170·275·TC-E2E-016·019) · part6 2(TC-FE-103·107) · part7 1(TC-FE-149) · part8 1(TC-FE-224). 신규 케이스 0건·폐기 0건 — 카탈로그 총 케이스 수 337 불변. 변경 이력 표에 5회차 행 1개로 반영 완료(`docs/test-cases/H-frontend-e2e.md`).
- **✅ 2차 HIGH 5건 중 4건 해소**: 상세는 위 "★★★ 2차 HIGH 이슈 5건" 블록 참조. **#8(`?token=` URL 인계, CWE-598)만 3회차 연속 미해소**(`H-ISSUE-01`).
- **★ 3차 신규 HIGH 2건**: `H-ISSUE-41`(BE/FE 잠금상태 문자열 불일치로 잠금 배너 미표시, 데이터유실 없음) · `H-ISSUE-81`(포털 업로드 E2E 잘못된 라우트 겨냥, 실질 커버리지 0).
- **환경 부작용 고지**: 병렬 검증 중 Playwright MCP 공용 브라우저를 여러 part 가 동시 사용해 세션(localStorage/sessionStorage) 토큰이 서로 교체되는 위양성 FAIL 이 반복 관측됐다(part1·part3·part4·part5 전부 독립 언급). 전 파트가 독립 브라우저 컨텍스트 전환 또는 스토리지 정리 후 재현하여 최종 판정을 정정했으나, **다음 회차부터는 FE 실동작 검증 파트에 독립 브라우저 컨텍스트를 기본 배정**할 것을 권고.
