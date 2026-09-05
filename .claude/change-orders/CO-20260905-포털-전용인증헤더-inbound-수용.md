# CO-20260905-포털-전용인증헤더-inbound-수용

| 항목 | 값 |
|---|---|
| CO 식별자 | CO-20260905-포털-전용인증헤더-inbound-수용 |
| 제목 | 포털 채널의 전용 인증 헤더 `x-access-token` 을 백엔드가 inbound 로 수용한다 |
| 대상 도메인 | 공유기반(`common/security`) · DOMAIN-001(인증) · (선택) 프론트 |
| 구현 상태 | ✅ 완료 (백엔드 QA fail→수정→pass · 프론트 QA pass_with_notes→정정 · 전체 회귀 BE 8,796 / FE 4,726, 실패 0*) |
| LogiCraft 설계반영 | 확정본 존재 — Phase 3.6 은 확인 수준 |
| 생성일 | 2026-09-05 |

---

## §1 배경

포털 채널은 저작도구 프론트를 포털 화면 안에서 실행하는 **임베딩**이라, 관제 채널처럼 같은 출처 브라우저 저장소로 토큰을 넘겨받는 전제가 성립하지 않는다. 그래서 `INT-013` 이 **Host 가 주입한 인계 창구에서 토큰을 얻어 전용 헤더 `x-access-token` 으로 싣는다**(Bearer 미사용)로 계약을 확정했다.

**프론트는 그 계약을 절반 이행하고 멈춰 서서 이유를 적어 두었다.** `features/auth/tokenHandoff.ts` 가 창구 넷(`getAccessToken`·`refresh`·`onUnauthorized`·`notifyActivity`)을 갖췄으나, 41행 주석이 *"전용 헤더로 바꾸는 것은 **백엔드가 그 헤더를 inbound 로 수용한 뒤**"* 라고 못박고 아직 `Authorization` 을 쓴다.

**백엔드 실측(2026-09-05 · main 머지 후)** — `x-access-token` 참조 9건이 전부 **바깥으로 나가는 쪽**이다: `common/config/WebClientConfig`(관제 통지 헤더) · `common/logging/LogMaskingPatterns`(로그 가림). **받는 쪽은 0건.**

## §2 변경 요지

`JwtAuthenticationFilter` 가 토큰을 **`x-access-token` 헤더에서도** 읽게 한다. 그 외 인증 동작은 전부 그대로다.

### 코드가 이미 답한 것 (설계에서 다시 정하지 않는다)

착수 전에 필터를 읽어 확인했다.

| 물음 | 실측 답 |
|---|---|
| 헤더로 채널을 가르는가 | **아니다.** 채널은 **JWT 클레임 `channel`** 이 가른다(`JwtAuthenticationFilter` 131~137행 — `INTERNAL` 기본, `PORTAL` 이면 역할 고정). 헤더는 **토큰이 실린 자리**일 뿐이다 |
| 검증 경로를 나눠야 하는가 | **아니다.** 두 채널이 같은 발급 서버라 이미 단일 필터·단일 파싱이다. **헤더만 늘고 검증은 공유**한다 |
| 추출 지점이 몇 곳인가 | **한 곳**이다 — 90행 `request.getHeader("Authorization")` + `Bearer ` 접두 |

⇒ **변경은 「토큰을 어디서 읽는가」 한 지점에 국한된다.** 채널 판정·역할 해석·`sub` 정규화·자동 등록은 손대지 않는다.

### 유일하게 남는 결정 — 두 헤더가 함께 왔을 때

관제 채널은 `Authorization: Bearer` 를 계속 쓰므로 둘이 공존한다. §7 게이트에서 확정한다.

## §3 도메인별 변경 상세

### 공유기반 — `common/security/JwtAuthenticationFilter`

**대상**: 토큰 추출 지점(90~92행) 한 곳.

**변경**: `Authorization: Bearer` 와 `x-access-token` 두 자리에서 토큰을 얻는다. 추출을 **작은 단일 지점**으로 뽑아 두 자리의 판정이 갈릴 수 없게 한다.

**불변 (하나라도 깨지면 중단하고 보고)**
- **관제 채널 `Authorization: Bearer` 경로 회귀 0** — 기존 시험 전건 통과.
- **채널 판정은 클레임이 소유한다** — 헤더로 채널을 추정하지 않는다. 헤더가 무엇이든 `channel` 클레임이 없으면 `INTERNAL` 기본이라는 현행 동작 그대로.
- **검증을 건너뛰는 조합이 생기지 않는다** — 어느 헤더로 들어와도 같은 서명·만료·발급자 검증을 탄다. **인증 입구가 느는 변경이라 이것이 최우선**이다.
- main 이 방금 넣은 관제 JWT 인계 로직(`sub`→`USER_ID` 조회 · `userNm` 폴백 · principal 정규화 · 자동 등록)은 **손대지 않는다**(`ADR-063`).
- **로그 마스킹 유지** — `LogMaskingPatterns` 가 이미 그 헤더를 가린다. **받는 쪽이 늘면 찍힐 자리도 느는데**, 그 가림이 새 경로에서도 유효한지 확인하고 시험으로 고정할 것.
- `Bearer` 접두는 `x-access-token` 에 **요구하지 않는다** — 계약이 Bearer 스킴 미사용이다. 접두가 붙어 오면 어떻게 할지는 구현 판단(관대 수용 시 그 사실을 시험으로 드러낼 것).

**수용기준**
- `x-access-token` 만 실린 요청이 인증을 통과한다
- `Authorization: Bearer` 만 실린 요청이 **종전과 동일하게** 통과한다
- 서명 위조·만료 토큰은 **어느 헤더로 와도** 거부된다
- 빈 값·공백만 있는 헤더는 「없음」과 같이 다룬다

### 프론트 — `features/auth/tokenHandoff.ts` (게이트에서 포함 여부 확정)

백엔드가 열린 뒤라야 성립한다(계약 의존). 요청 헤더를 `x-access-token` 으로 전환하고, `INT-013` 이 못박은 **매 호출 취득·캐시 금지**를 유지한다.

## §4 범위 밖

- 포털 메타·이벤트 어노 노출(`API-234~237`) — 별건, 이 회차 뒤
- Module Federation 빌드 설정 — `lib/remoteMount.ts` 가 계약값을 갖고 있으나 이 CO 는 인증 축만 다룬다
- 관제 채널 인계 로직 일체(`ADR-063` 소관)

## §5 공유기반 영향

**있다** — `common/security/JwtAuthenticationFilter` 는 도메인 소속이 아니다. 다만 변경이 **추출 지점 한 곳**이라 스키마·시그니처 변경이 없다. Flyway·설정 키 신설 0.

## §6 관련 설계 ITEM

**Phase 3.6 판정: 신규 ITEM 변경 없음 — 확정본이 이미 서 있다.**

| ITEM | 상태 | 관련 |
|---|---|---|
| `INT-013` v20 | draft · stale=false | `auth_detail.header_name = "x-access-token"` · `token_location = "header"` · `auth_type = "other"`(Bearer 미사용) |
| `ADR-012` v13 | approved · **stale=true** | 채널별 토큰 인계 결정. ⚠ **stale 사유는 무해하다** — `ADR-063` 참조 등재이며 *"본문은 바꾸지 않았다"* |
| `ADR-063` | (main 신규) | 인계받은 토큰의 **검증 규격**(iss 부재 허용·userId 식별·userNm 이름). 이 CO 는 **인계 수단** 축이라 두 결정이 공존한다 |

구현은 이 ITEM 들을 진실원으로 삼고 `@design INT-013` 으로 잇는다.

## §7 게이트에서 확정할 것

**두 헤더가 함께 온 요청을 어떻게 다루는가.**

- **권고** — 둘 다 있고 **값이 다르면 401**(모호한 인증 상태를 거부, fail-closed). 값이 같으면 통과(무해).
- 대안 ①: `Authorization` 우선, `x-access-token` 무시
- 대안 ②: 둘 다 있으면 값과 무관하게 거부

⚠ 권고안의 대가 — 어떤 경유 장비가 `Authorization` 을 자동으로 붙이면 포털 요청이 전량 401 이 된다. 다만 그 경우 **즉시 드러나며**, 조용히 한쪽을 채택해 **의도치 않은 신원으로 동작하는 것보다 낫다**고 본다.

## §8 기준선

- backend **8,750 / 실패 0 / skipped 5 / XML 950**(머지 상태 실측)
- ⚠ **frontend 는 이미 1건 red 다** — `frontendRuntimeConfigDeployWiring.test.ts:275`. 배포 템플릿의 자리표시자를 실제 주소로 바꾼 main 변경이 시험을 함께 고치지 않아 생긴 것이고 **이 회차 기여는 0**이다. 그것을 근거로 fail 판정하지 말 것

## §9 구현 로그

| 일시 | 도메인 | 에이전트 | 결과 | QA | 커밋 |
|---|---|---|---|---|---|
| 2026-09-05 | 공유기반 | klid-d001-implementer | 추출을 `extractToken → TokenIngress` 단일 지점으로. **BE 8,779** | fail | — |
| 2026-09-05 | 공유기반 | klid-qa-verifier | **fail · HIGH 1** — 다듬기가 파싱 값까지 바꿔 관제 회귀. 보고는 「여분 공백 1건」인데 **실제 7형**(`trim()` 이 U+0020 이하 전 문자를 벗김: TAB·CR·**C0 제어문자**). ★**제안 대안을 직접 시뮬레이션해 `divergence 0 · 19건 green` 실측** — 회귀 0 과 목적이 양립함을 증명 | fail | — |
| 2026-09-05 | 공유기반 | klid-d001-implementer | 다듬기를 「비교·공백판정」으로 좁힘. ★**결과가 아니라 값으로 쟀다** — 7형 `extractToken().token()` == HEAD 모델 **BYTE-IDENTICAL**. 회귀를 계약으로 굳히던 시험을 **삭제하고 둘로 분할**. **BE 8,795** | pass | — |
| 2026-09-05 | 공유기반 | klid-qa-verifier | **pass · 지적 0** — 7형 divergence 0 · 변이 M1/M2/M3/M4 전건 재현 · 분할 시 커버리지 누수 0(삭제 시험의 입력·단언이 승계본에 문자 그대로) · `AdminSession` 429 를 **분 버킷 플레이크**로 근거 3개와 함께 별건 판정 | pass | — |
| 2026-09-05 | 프론트 | klid-web-implementer | 포털 채널 `x-access-token` 전환. 판정·조립 단일 지점 `buildAuthHeader`, 채널 판정은 기존 `isPortalEmbedChannel()` **재사용**. ★**변이 E 가 1차에서 생존한 것을 스스로 찾아** 가드 보강. **FE 4,726 / red 1**(기준선) | pass_with_notes | — |
| 2026-09-05 | 프론트 | klid-qa-verifier | **pass_with_notes** — 변이 **8종 전건 킬**(QA 추가 2 포함). ★**MEDIUM: CORS 허용목록에 그 헤더가 없다** — 원래 무해했으나 **프론트가 싣기 시작해 활성 위험**이 됐고, 같은 부류를 지키는 `CorsAllowedHeadersTest` 가 **그 헤더를 보지 않았다** | — | — |
| 2026-09-05 | 프론트 | klid-web-implementer | LOW 정정 — **참인 결론(401)을 거짓 근거(「충돌」)가 떠받치던 서술 4곳**. 시험 총계 불변(4,726)이 주석만 고쳤다는 부수 증거 | pass | — |
| 2026-09-05 | 공유기반 | klid-d001-implementer | CORS 허용목록에 상수 참조로 추가 + **값 축 가드 신설**. ★변이 M5 로 실증 — 그 헤더만 빼면 **신설 가드만 RED, 기존 3건 GREEN**(열거식 가드가 신규 누락을 못 잡음). 가시성 확대 0(같은 패키지). **BE 8,796** | (메인 직접 확인) | — |

**메인 직접 확인 (세 번째 QA 생략 근거)**

`SecurityConfig` 는 인가 판정의 1차 원천이라 변경 범위를 직접 실측했다 — 변경은 `corsConfigurationSource` 안 **8줄(주석 7 + 코드 1)뿐**, **인가 매처 변경 0**, 리터럴이 아닌 **상수 참조**, 상수는 `static final`(package-private)이라 **공개 표면 증가 0**. 변이 M5 가 신설 가드의 실효를 이미 증명했으므로 세 번째 QA 라운드는 과하다고 판단했다.

**QA 가 뒤집은 것 (근거 기록)**

- ★**「여분 공백 1건」이 실제로는 7형이었다** — 원인은 `String.trim()` 의 정의(U+0020 이하 전 문자)다. **「공백」이라는 낱말이 실제 범위를 가렸다.**
- ★**시험이 회귀를 「의도된 계약」으로 굳히고 있었다** — `★다듬기는_두_자리에_같이_적용된다`. 그대로 뒀으면 **코드를 고치는 순간 그 시험이 반대로 걸린다.** 이 저장소가 `AC-032`(폐기된 결함 동작을 수용기준이 정답으로 보유)에서 겪은 것과 **같은 형태**다.
- ★**같은 부류를 지키려고 만든 가드가 새 항목을 안 본다** — `CorsAllowedHeadersTest`. `controlVideosAreNeverSwept…` javadoc 이 거짓이던 것과 함께 **이 세션에서 세 번째**다.

**미반영·보류 항목**

- **`refresh`·`onUnauthorized`·`notifyActivity` 창구 미배선** — 특히 401 경로에 `onUnauthorized` 를 붙이는 것은 **Host 재로그인과 우리 리다이렉트가 경쟁**하므로 순서 규약 확정이 선행돼야 한다(근거는 `client.ts` 주석에 보존).
- **인터셉터가 반대 채널 헤더를 지우지 않는다** — QA 판정 「필수 아님」(현재 위험 0 — 운영 코드에서 `Authorization` 을 세팅하는 자리가 `client.ts` 밖에 0건). ⚠ 지우는 동작은 관제에서 `x-access-token` 을 지우게 되므로 **TUS·adminSession 헤더 영향**을 함께 봐야 한다.
- ★**CORS 목록의 구조적 취약** — 이번엔 한 항목을 막았으나 **새 요청 헤더가 생길 때마다 같은 구멍이 다시 열린다.** 구조적 해법은 「요청에 커스텀 헤더를 싣는 지점」과 목록을 잇는 가드인데, **그 목록의 단일 원천이 없어** 이번 범위에서 만들 수 없었다.
- 포털 API 주소를 절대 주소로 돌리면 **`allowed-origins` 설정도 함께** 필요하다(헤더 허용만으로는 origin 이 닫혀 있다).
- 중복 헤더 occurrence 사각 — javadoc + 시험으로 **고정만** 했다.
- **`AdminSessionServiceTest.★속도_제한을_넘기면_429` 분 버킷 플레이크** — 제한기가 이미 `Clock` 주입점을 「테스트 전용 — 분 버킷 산출 결정론화」로 갖고 있어 **고정 Clock 주입 한 줄로 닫힌다.** 별건.
- *"Bearer 자동 첨부"* 주석이 관제 전용 화면에 **6곳** 잔존(공유 경로 2곳만 중립화).

**\* 프론트 red 1건은 기준선이다** — `frontendRuntimeConfigDeployWiring.test.ts:275`. main 이 배포 템플릿의 자리표시자를 실제 주소로 바꾸며 시험을 함께 고치지 않아 생겼고, **실패한 두 파일 모두 origin/main 과 바이트 동일**이라 이 회차 기여 0.
