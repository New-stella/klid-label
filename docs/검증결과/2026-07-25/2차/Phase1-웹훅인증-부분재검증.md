# Phase 1 부분 재검증 — 웹훅 인증 우회 차단

> 대상: `A-ISSUE-13` · `E-ISSUE-01`(CRITICAL) · `E-ISSUE-04` · `A-ISSUE-12` · `A-ISSUE-14` · `A-ISSUE-15` · `B-ISSUE-25` (7건)
> 기준: **실동작** — 로컬 스택(5서비스) + 실HTTP 프로브. 정적 대조로 갈음하지 않음
> 일자: 2026-07-25 | 백엔드 이미지 `2026-07-25T06:02` (REDESIGN 반영)

## 1. 실HTTP 검증 결과 (PM 직접 실행)

| # | 검증 | 기대 | 실측 | 판정 |
|:-:|---|---|---|:--:|
| 1 | 무인증 `POST /api/v1/aug/callback` | 401 | **401** | PASS |
| 2 | **무인증 `POST /api/v1/%61ug/callback`** (1차 CRITICAL 침투 경로) | 401 | **401** | **PASS** |
| 3 | 무인증 `/api/v1/a%75g/callback` | 401 | **401** | PASS |
| 4 | 무인증 `/api/v1/aug/%63allback` | 401 | **401** | PASS |
| 5 | 유효 서명 콜백 | 200 + 상태전이 | **200** `applied:true`, `data_aug_sn=21` PENDING→REJECTED | PASS |
| 6 | **동일 서명 → `%61ug` 재전송** | 409 | **409** | **PASS** |
| 7 | 동일 서명 → `a%75g` 재전송 | 409 | **409** | PASS |
| 8 | 명백히 다른 서명 | 401 | **401** `"시그니처가 일치하지 않습니다"` | PASS |
| 9 | 만료 timestamp(-10분) | 401 | **401** `"허용 시간 윈도우를 벗어났습니다"` | PASS |
| 10 | VLM 무서명 콜백 (벤더 계약) | 서명 미요구 | **401** `"발급되지 않은 request_id"` = request_id 게이트 동작, 서명 요구 없음 | PASS |
| 11 | 위조 `request_id` 15연타 | 6회째부터 429 | **401×5 → 429×10** | PASS |
| 12 | **XFF 부착 + 위조 15연타** | 6회째부터 429 | **401×5 → 429×10** | **PASS** |
| 13 | nonce 저장 경로 | 정규화 경로 | `ls_whk_sign_use.whk_path_nm = /v1/aug/callback` (원시 URI 아님) | PASS |
| 14 | 위조 시도의 nonce 쓰기 | 0건 | `ls_whk_sign_use` **1행**(정상 1건만) = pre-auth write 차단 | PASS |
| 15 | 공유 실패 카운터 | 버킷당 상한 | `ls_whk_fail_nmtm` 버킷당 정확히 **5**에서 정지 | PASS |
| 16 | 시크릿 fail-closed 후 로컬 기동 | 정상 기동 | 백엔드 컨테이너 **healthy** | PASS |
| 17 | V131 마이그레이션 | 적용 | `flyway_schema_history` 131 success=true, `ls_whk_sign_use`·`ls_whk_fail_nmtm` 생성 | PASS |

### 1차 대비 변화 (동일 프로브)

| 프로브 | 1차 | 2차 |
|---|---|---|
| `POST /api/v1/%61ug/callback` 무인증 | **200** (증강행 4건 전이 + `raw_sn=29·31` 생성) | **401** |
| 동일 서명 인코딩 변형 재전송 | (nonce 키가 원시 URI라 신규 취급) | **409** |
| XFF 부착 위조 `request_id` 15연타 | — (DEV_FIX 2차 시점 **15회 전부 401**) | **6회째부터 429** |

## 2. 자동 테스트

| 시점 | 총 | 성공 | 실패 | 스킵 |
|---|---:|---:|---:|---:|
| baseline(Phase 1 착수 전) | 3013 | — | 0 | — |
| 구현 후 | 3042 | 3037 | 0 | 5 |
| DEV_FIX 1차 | 3066 | 3061 | 0 | 5 |
| DEV_FIX 2차 | 3072 | 3067 | 0 | 5 |
| **REDESIGN(최종)** | **3080** | **3075** | **0** | **5** |

전 구간 **회귀 0**. 스킵 5건은 `MockServerLiveIntegrationIT`(python venv 자체 기동 게이트, `@EnabledIf`) — 이번 판정과 무관.

## 3. 이슈별 최종 판정

| 이슈 | 판정 | 근거 |
|---|:--:|---|
| `A-ISSUE-13` 경로 정확일치 우회 | **해소** | 필터 판정이 MVC 와 동일 `RequestPath`+`PathPattern`, 판정 불가=보호. 실HTTP #2~4 |
| `E-ISSUE-01` HMAC 필터 우회(CRITICAL) | **해소** | 실HTTP #2 (200→401). 이중 게이트(`WebhookGuardedRequest` 래퍼 증거) 병행 |
| `E-ISSUE-04` 빈 시크릿 전건 401 | **해소** | 빈값·32B미만·커밋 placeholder·미해석 `?…` 전부 기동 차단. 실HTTP #5·#16 |
| `A-ISSUE-12` replay 시간창뿐 | **해소** | nonce 1회성 소비(V131), 키를 **정규화 경로**로 산출. 실HTTP #6·#7·#13 |
| `A-ISSUE-14` 2노드 임계 2배 | **부분해소** | 노드간 차단 승계 성립(`adoptRemoteBlock`). 잔존: 버킷 4+1 분산 시 최대 9회 통과(영구 회피 불가 — 지속 회피 조건이 임계보다 낮음) |
| `A-ISSUE-15` 프록시 뒤 가용성 | **부분해소** | XFF 신뢰체인 정확 구현 + prd·stg 미설정 시 기동 차단. 잔존: 차단 지속 최대 W+180s(아래 잔여 ①) |
| `B-ISSUE-25` VLM 무인증 자원소모 | **해소** | 3계층(IP allowlist·request_id 게이트·rate limit+size cap). 실HTTP #10~12. 벤더 무서명 계약 유지 |

## 4. 잔여 (2차 이후 확인 대상)

| # | 심각도 | 내용 |
|:-:|:--:|---|
| ① | MEDIUM | `WebhookRateLimiter.adoptRemoteBlock` 첫 분기에서 각인 만료가 로컬 트래커 기준으로 남아 **차단이 최대 W+180s** 지속(javadoc 은 W+120s 단정). 가용성 이탈이며 보안 완화 아님 |
| ② | MEDIUM | `JdbcWebhookGuardStoreIT.liveRowsSurvivePurge_whenAppClockIsBehindDbClock` 이 `assumeTrue` 로 **초록 skip 가능** → R-6(시계 통일) 회귀를 놓칠 수 있음. **위양성 안전망 구멍** |
| ③ | LOW | `ClientIpResolver.isProxyAware()` 프로덕션 호출처 0(죽은 API) — "프록시 인지 여부로 분기해도 된다"는 오해 재유입 위험 |
| ④ | LOW | `WebhookRateLimiterTest` 3건이 분 경계에서 플레이키 |
| ⑤ | 후속 | 슬라이딩 윈도우 전환(고정 윈도우 과소·과대 오차 동시 해소) — 스키마·조회 계약 변경 수반, 별도 이슈 |
| ⑥ | 후속 | `EXPD_DT`/`BGNG_DT` **TIMESTAMPTZ 전환**(V132) — 2노드 JVM TZ 불일치 시 공유 집계 퇴화·타 노드 nonce 조기 purge. 동일 이미지 2노드에서는 미성립 |
| ⑦ | 환경 | `WEBHOOK_VLM_ALLOWED_IP_CIDRS`·`WEBHOOK_TRUSTED_PROXY_CIDRS` 실값 — 벤더 송신 대역·LB CIDR 확보 후 prd/stg 주입 필요(코드는 완비, 미설정 시 기동 차단) |

## 5. 오염 데이터

`docs/검증결과/2026-07-25/1차/_raw/hmac-bypass-contamination-inventory.md` 참조.
**변경하지 않음** — 침투로 생성된 `raw_sn=29·31` 은 FAILED·프레임0·export0·데이터마트 0행이고, 전이된 증강행 4건은 센티넬 `OTSD_JOB_ID`(`EXT-OK-1` 등)를 가진 **1차 검증 합성 픽스처**로 판명됐다. 되돌리면 배치가 살아있는 요청으로 오인할 위험이 있어 기록만 유지한다.

## 6. 이 Phase 의 과정 (재발 방지용)

구현 1회 + QA 5종 + DEV_FIX 2회 + REDESIGN 1회 + 적대검증 4라운드 + 실HTTP 프로브 5회차.

**매 라운드마다 결함이 나왔고, 매번 그 결함을 정상으로 고정하는 테스트가 동반됐다**(총 3건 폐기):

| 라운드 | 결함 형태 |
|---|---|
| 구현 | `B-25` 가 표면적으로만 충족(원 재현 명령에 그대로 뚫림) + 이를 덮는 위양성 테스트 |
| DEV_FIX 1차 | 두 수정이 서로를 무력화(전역 집계 사문화, 200 한 건으로 rate limit 전면 해제) |
| DEV_FIX 2차 | **PM 설계 오류** — 공격자 제어 헤더(XFF)를 보안 통제를 끄는 트리거로 삼아 fail-open |
| REDESIGN | 신규 HIGH 0 (자기 문서 이탈 MEDIUM 1 + 테스트 skip 가능 MEDIUM 1) |

**교훈 3가지 (이후 Phase 위임 프롬프트에 상시 포함)**
1. 공격자가 제어할 수 있는 입력을 조건으로 **보안 통제를 끄는 분기를 만들지 않는다.** 가용성 문제는 설정 강제(fail-fast)로 풀고 런타임 우회로 감추지 않는다.
2. 뿌리를 고칠 때 **그 뿌리가 닿는 지점을 전부 열거**한다. (경로 판정은 고쳤는데 nonce 키에 원시 URI가 남았던 사례)
3. 새로 쓰는 테스트마다 **"이 수정을 되돌리면 실패하는가"를 실측**한다. 위양성 3건을 이 방법으로 잡았다.

**추가 발견 — 세션 중 확인된 별건**
`${VAR:?}` 는 Spring 에서 fail-fast 가 아니라 문자열 `"?메시지"` 를 주입한다(46B라 길이 검사 통과). 이로 인해 **prd 도 커밋된 placeholder 로 기동하고 있었다.** `${VAR:}` + `"?"` 시작값 차단으로 수정했고, 현재 `backend/src/main/resources/*.yml` 에 webhook 외 `:?` 사용은 없다.
