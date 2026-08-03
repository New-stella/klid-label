# 2차 재검증 (targeted) — A/C 클러스터

- 일시: 2026-08-02
- 대상 워크트리: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801` (HEAD `56d30478` + **미커밋 워킹트리**)
- 검증자: qa 에이전트 (읽기 전용 — 코드/설정/테스트 무수정, gradle 미실행)

## 0. 환경 판정 — 실동작 검증 BLOCKED

```
$ docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT MAX(version) FROM flyway_schema_history;"
 max
-----
 99
```

컨테이너 스택은 **V99** 로, 지시된 기준선 **V160 미만**이다. 즉 이번 5-Phase 버그수정이 반영되지 않은
구 이미지가 기동 중이며, curl 실동작 재현은 **BLOCKED (사유: 워크트리 코드가 컨테이너에 미반영)**.

또한 수정 코드는 `klid-label` 본체가 아니라 **이 워크트리(`qa-0801`)의 미커밋 워킹트리**에 존재한다
(`klid-label` 은 `56d30478` clean, 미추적 docs 디렉터리 1개뿐). 아래 판정은 **정적 대조 + 기존 테스트
실행 결과(XML)** 를 근거로 한다.

### 테스트 실행 증거 (기존 산출물 — 재실행 안 함)

`backend/build/test-results/test/` (575 XML, 타임스탬프 **Aug 2 15:55:55 2026**)

| 테스트 클래스 | tests | skipped | failures | errors |
|---|---:|---:|---:|---:|
| `JwtAuthenticationFilterTest` | 9 | 0 | 0 | 0 |
| `SortAllowlistTest` | 24 | 0 | 0 | 0 |
| `LogMaskingPatternsTest` | 27 | 0 | 0 | 0 |
| `LocalLogMaskingIT` | 3 | 0 | 0 | 0 |
| `AiMockMetaTest` | 5 | 0 | 0 | 0 |
| `Sam2TrackServiceTest` | 17 | 0 | 0 | 0 |
| `ListApiBackwardCompatibilityIT` | 16 | 0 | 0 | 0 |

---

## 1. TC-AUTH-005 — **PASS** (정적 + 단위) / 실동작 BLOCKED

> ⚠ 카탈로그 원문(`docs/test-cases/A-auth-common.md:23`)의 TC-AUTH-005 는 "만료 토큰(exp **과거**) 거부"다.
> 이번 재검증 지시는 그 인접 결함인 **"exp 클레임 **부재** 토큰"**(A-ISSUE-01)을 대상으로 한다.
> 아래는 **양쪽 모두** 판정한다.

### 근거 — `common/security/JwtAuthenticationFilter.java:72-84`

```java
// A-ISSUE-01 (HIGH, CWE-613 Insufficient Session Expiration) — exp 클레임 필수화.
if (body.getExpiration() == null) {
    log.debug("[Auth] rejected token without exp claim");
    SecurityContextHolder.clearContext();
    chain.doFilter(request, response);
    return;
}
```

- issuer 게이트(L65-70)와 **동일한 fail-closed 패턴** — `SecurityContext` 를 비우고 필터체인을 계속
  진행시키므로 이후 인가 단계에서 401 로 종결된다.
- 주석이 근본원인을 정확히 기술: jjwt 는 `exp` 가 **없으면 만료 검사를 통째로 건너뛴다**. 저작도구는
  토큰을 발급하지도 폐기(revocation)하지도 않으므로 수명 상한이 `exp` 뿐이고, 발급 주체가 외부
  (관제/포털)라 "항상 exp 를 넣는다"는 계약을 검증 없이 신뢰할 수 없다.
- 게이트가 `channel` 파싱(L86) **앞**에 있어 INTERNAL·PORTAL 양 채널 모두에 적용된다.
- 후속 `TokenClaims` 생성(L102-107)의 `body.getExpiration().getTime()` NPE 도 함께 제거됨.

### 근거 — `JwtAuthenticationFilterTest` (9/9 통과)

| 테스트 | 대상 |
|---|---|
| `exp_클레임이_없는_JWT는_401_반환` | 수정 대상(A-ISSUE-01) 직접 검증 |
| `exp_없는_PORTAL_채널_토큰도_401` | PORTAL 채널 우회 차단 (채널별 분기 누락 방지) |
| `만료된_JWT로_요청시_401_반환` | 카탈로그 원문 TC-AUTH-005 회귀 |

**판정: PASS** — 수정 반영 확인, 신규 테스트 2건(exp 부재 INTERNAL/PORTAL) + 기존 회귀 1건 전부 통과.
실동작(curl) 재현은 BLOCKED.

---

## 2. TC-SORT-006 — **PASS** (정적 + 단위) / 실동작 BLOCKED

> ⚠ 카탈로그 원문(`A-auth-common.md:230`)의 TC-SORT-006 은 "컨트롤러 allowlist 사본 금지 —
> `SortAllowlist.VIDEO` 단일 진실원"이다. 이번 지시는 인접 결함
> **A-ISSUE-61 (`/v1/deident-reports` 등 미배선 엔드포인트 → 500)** 이다. 양쪽 모두 판정한다.

### 2-1. A-ISSUE-61 — 미배선 엔드포인트 3종 배선 완료

`common/util/SortAllowlist.java` 에 allowlist 3종 신설:

| 상수 | 엔드포인트 | 모드 |
|---|---|---|
| `DEIDENT_REPORT` (L159-168) | `GET /v1/deident-reports` | strict → **400** |
| `PORTAL_UPLOAD` (L180-187) | `GET /v1/portal/uploads` | strict → **400** |
| `PORTAL_UPLOAD_FRAME` (L198-203) | `GET /v1/portal/uploads/{uldSn}/frames` | strict → **400** |

컨트롤러 배선 실측:
- `label/controller/DeidentReportController.java:91-93`
  → `SortAllowlist.apply(pageable, SortAllowlist.DEIDENT_REPORT, DEFAULT_REPORT_SORT)`
- `portal/controller/PortalUploadController.java:165,173`
  → `SortAllowlist.apply(..., PORTAL_UPLOAD | PORTAL_UPLOAD_FRAME, ...)`

`resolve()`(strict, L226-249) 는 미등록 키를 `CustomException(ErrorCode.INVALID_INPUT)` = **400** 으로
거부한다. 즉 기존 `PropertyReferenceException` → **500 + 내부 엔티티명/JPQL 로그 노출(CWE-209)** 경로가
차단됐다. 개수 상한(`maxOrders`, L310-312 — allowlist 고유 엔티티 필드 수에서 파생)도 함께 적용되어
CWE-770(쿼리 플랜 캐시 오염)도 닫혔다.

부수 보안 결정도 확인:
- `DEIDENT_REPORT` 에서 **신고 사유(`rsn`, 자유서술)·신고자(`reporterNo`) 의도적 제외** — 정렬 순서로
  본문 접두 추론 / 신고 주체 열거를 막음.
- `PORTAL_UPLOAD` 에서 **`orgnlFileNm`(사용자 원본 파일명) 제외** — PII 가 실릴 수 있음.
- 예외 메시지에 입력값·내부 필드명 미포함(L237, L242 — CWE-209).
- **기본 정렬키 포함 검증**: `reportDt`(`@PageableDefault` 값)가 allowlist 에 있어 파라미터 없는
  기존 호출이 400 이 되지 않는다.

### 2-2. 카탈로그 원문 (SortFieldMapper 단일 진실원)

`common/web/SortFieldMapper.java:59,69` 가 `SortAllowlist.resolveLenient(...)` 에 위임하고
allowlist 사본을 보유하지 않음을 확인 — 원문 TC 도 유지.

### 근거 — 테스트

`SortAllowlistTest` (24/24 통과) 중 관련:
- `신규_allowlist_3종_모두_미등록키를_400으로_거부한다`
- `신규_allowlist_예외메시지에_입력값과_내부필드명이_실리지_않는다`
- `신고목록_allowlist는_기본정렬키_reportDt를_반드시_포함한다`
- `신고목록_allowlist는_자유서술_사유와_신고자를_정렬축으로_열지_않는다`
- `포털_업로드_allowlist는_원본파일명을_정렬축으로_열지_않는다`
- `포털_프레임_allowlist는_frmeNo_별칭을_같은_엔티티필드로_매핑한다`

`ListApiBackwardCompatibilityIT` (16/16 통과) — strict/lenient 이원 정책 회귀 가드가 살아 있음
(`동일한_미등록_정렬키라도_작업목록은_400_검수목록은_200_으로_정책이_의도적으로_다르다`).
CLAUDE.md 의 "두 정책을 일관성을 이유로 통일하지 말 것" 구속이 유지됨을 확인.

**판정: PASS** — 500 경로 제거 확인(400 으로 전환), 하위호환(기본 정렬키·lenient 엔드포인트) 무파손.
실동작(curl) 재현은 BLOCKED.

---

## 3. TC-LOG-001 — **PASS** (정적 + 단위) / 실동작 BLOCKED

> ⚠ 카탈로그 원문(`A-auth-common.md:220`)의 TC-LOG-001 은 "LogSanitizer — C0/DEL/C1 제어문자 제거"다.
> 이번 지시는 인접 결함 **A-ISSUE-62 (전화번호/이메일/JSON형/bare JWT 평문 기록)** 이다. 양쪽 판정.

### 3-1. A-ISSUE-62 — 마스킹 규칙 단일 원천화 + 신규 규칙 5종

신설 `common/logging/LogMaskingPatterns.java` 가 **단일 원천**이고, 두 마스커가 위임한다:
- `MaskingPatternLayout.java:24` → `LogMaskingPatterns.mask(input)` (local, PatternLayout)
- `MaskingJsonValueMasker.java:30` → `LogMaskingPatterns.mask(input)` (dev/stg/prd, JSON 인코더)
- `logback-spring.xml:15, 41-42` 로 양 프로파일 배선 확인 → **드리프트 구조적 제거**

`maskWithin()` (L307-318) 적용 순서 실측 — 지시된 4종이 모두 존재:

| # | 규칙 | 지시 대상 | 위치 |
|---|---|---|---|
| 1 | `JSON_CREDENTIAL_PATTERN` | **JSON형** `"password":"x"` | L125-129 |
| 2 | `HEADER_COLON_PATTERN` | `X-Access-Token` 등 Authorization 외 토큰 헤더 | L135-138 |
| 3 | `COOKIE_HEADER_PATTERN` | 쿠키 세션토큰 (줄 끝까지) | L147-149 |
| 4 | `KV_PATTERN` | 기존 `key=value` (유지) | L163-167 |
| 5 | `BEARER_PATTERN` | `Bearer <값>` | L170-172 |
| 6 | `BARE_JWT_PATTERN` | **bare JWT 전문** `eyJ….….…` | L185-187 |
| 7 | `RRN_PATTERN` | 주민번호형 13자리 | L193-195 |
| 8 | `PHONE_PATTERN` | **전화번호** (`010-****-5678`) | L201-203 |
| 9 | `EMAIL_PATTERN` | **이메일** (로컬파트 1글자만 잔존) | L221-224 |

전화번호는 `rules/security.md` 표기 규약(`010-****-1234`)대로 가운데 자리만 가린다(L315).
이메일은 도메인 보존 + 로컬파트 마스킹(L316) — 진단 가치와 PII 보호 균형.

### 3-2. 방어적 하드닝(동반 확인 — 회귀 위험 지점)

새 정규식이 hot path(모든 로그 문자열 + 스택트레이스, 동기 어펜더)에 들어가므로 DoS 방어가
동반됐고, 각각 **실측 수치 기반 회귀 테스트**가 붙어 있다:
- **ReDoS(CWE-1333/400)** — 경계 lookbehind + possessive + `{0,64}` 반복 상한(L88, L110, L116)
  + `MAX_MASK_LENGTH` 64KB 상한(L75).
- **스택 고갈(CWE-674)** — 이메일 도메인 라벨 반복에 `{1,10}` 명시 상한(L94, L223).
  무제한 `+` 는 Java 가 재귀 Loop 노드로 컴파일해 8KB 입력에서 `StackOverflowError` → 로깅 스레드
  사망(실측 재현). `Error` 는 Logback `catch (Exception)` 에 안 걸림.
- **절삭 누출** — `safeCutIndex`(L271-287)가 공백 경계 + 따옴표 균형으로 되감아
  `{"password":"SE...[truncated]` 형태의 반토막 누출을 차단(fail-secure: 초과분은 **폐기**).

### 3-3. 카탈로그 원문 (LogSanitizer 제어문자)

`common/util/LogSanitizer.java:96-99` — `Character.getType()` 기반 `CONTROL`/`LINE_SEPARATOR`/
`PARAGRAPH_SEPARATOR` 제거(치환 아님) 유지 확인.

### 근거 — 테스트

`LogMaskingPatternsTest` (27/27 통과) 중 지시 대상 직접 검증:
- `A_ISSUE_62_실동작_누출_케이스_6종_전부_평문_미잔존` ← **핵심 회귀 가드**
- `전화번호_가운데자리_마스킹` / `이메일_로컬파트_마스킹` / `JSON_형태_자격증명_마스킹`
- `접두어_없는_JWT_전문_마스킹` / `Authorization_이외_토큰_헤더_XAccessToken_마스킹`
- `쿠키_헤더는_줄_끝까지_마스킹되어_세션토큰이_남지_않음`
- 하드닝 회귀 4건(ReDoS 1~3차, StackOverflow) + 절삭 누출 2건

`LocalLogMaskingIT` (3/3 통과) — local 프로파일 실배선 검증.

**판정: PASS** — 4종(전화번호/이메일/JSON형/bare JWT) 모두 마스킹 규칙 존재 + 배선 + 회귀 테스트 통과.
실동작(로그 파일 확인) 재현은 BLOCKED.

---

## 4. SAM2 Track mock 차단 (TC-SAM2-14/24/26 인접) — **PASS**

> 카탈로그(`docs/test-cases/C-marking-labeling.md:238,248,250`)의 TC-SAM2-14/24/26 은 정상경로
> (POLYGON / BBOX 외접박스 / shape 기본값)이고 **mock 전용 케이스는 카탈로그에 없었다**.
> 이번 지시대로 인접 신규 케이스로 판정한다 (C-ISSUE-81).

### 4-1. 판정 규약 — 부정 신호가 아니라 **긍정 증명**

신설 `common/client/dto/AiMockMeta.java:35-37`:

```java
public static boolean untrusted(boolean mock, String source) {
    return mock || !SOURCE_MODEL.equals(source);   // SOURCE_MODEL = "model"
}
```

지시의 조건(`mock=true` **또는** `source≠"model"`)과 **정확히 일치**한다. 근본원인 기술도 정확:
DTO 의 `mock` 이 primitive `boolean` 이라 **필드가 생략된 JSON** 을 받으면 Jackson 이 `false` 로 채워
`mock()` 만 보는 코드는 fail-open 이 된다. `source=null`·오타·미래 값도 전부 불신(fail-closed).

`Sam2TrackResponse.java:48-50` 이 `untrusted()` 를 노출하고, 호출부가 이를 사용:
`label/service/Sam2TrackService.java:134` → `if (aiRes.untrusted()) { ... }` (해당 프레임 결과 제외).
서비스 주석(L46-49)이 **왜 위험한지** 기록: ai-server `_mock_track` 은 **시드 폴리곤을 그대로 복사**하고
`score=0.9` 를 부여하므로, 거르지 않으면 "N 프레임 추적"이 조용히 "시드 폴리곤 N개 복제"가 되고
점수가 높아 FE 저신뢰 분기에도 안 걸린다.

### 4-2. 안내 메시지

`Sam2TrackService.java:89` 가 `anyMock` 을 누적하고, L170 이
`Sam2TrackOutcome.of(new Sam2TrackResponseDto(tracked), anyMock)` 로 반환해 상위에서 안내 메시지로
전달된다(SAM2 세그·YOLO 오토라벨과 동일 규약). 로그는 `LogSanitizer` 로 정제(L136-138, L168-169 —
`trackId` 가 클라이언트 원문이라 CWE-117 대응).

### 근거 — 테스트

`AiMockMetaTest` (5/5 통과) — 판정 규약 + 3 DTO(track/segment/yolo) 의 필드 생략 PoC + 실모델 회귀.
`Sam2TrackServiceTest` (17/17 통과) 중:
- `추적_ai가_mock응답이면_해당프레임을_결과에서_제외하고_전량안내를_준다`
- `추적_일부프레임만_mock이면_실결과는_유지하고_부분안내를_준다`
- `추적_ai가_mock메타를_생략하면_신뢰하지_않고_프레임을_제외한다` ← **fail-open 변종 가드**
- `추적_실모델(mock아님)_응답은_그대로_자동적용되고_안내가_없다` ← 과차단 회귀 가드
- 정상경로 회귀 3건 유지: `추적_shape가_폴리곤이면…`(TC-SAM2-14) /
  `추적_shape가_박스면_폴리곤_외접bbox로…`(TC-SAM2-24) / `추적_shape미지정이면_POLYGON_기본`(TC-SAM2-26)

**판정: PASS** — mock 및 메타 생략 응답 모두 자동적용 차단 + 안내 전달, 실모델 경로 과차단 없음.

---

## 5. 판정 요약

| TC-ID | 대상 | 판정 | 근거 |
|---|---|:---:|---|
| TC-AUTH-005 | exp 클레임 부재 JWT → 401 (A-ISSUE-01) | **PASS** | `JwtAuthenticationFilter.java:79-84` fail-closed 게이트 + `JwtAuthenticationFilterTest` 9/9 (exp 부재 INTERNAL/PORTAL 2건 신규) |
| TC-AUTH-005 (원문) | 만료 토큰(exp 과거) 거부 | **PASS** | `만료된_JWT로_요청시_401_반환` 통과 |
| TC-SORT-006 | 미등록 정렬키 500 → 4xx (A-ISSUE-61) | **PASS** | `SortAllowlist` allowlist 3종 신설 + 컨트롤러 3곳 strict 배선 → 400. `SortAllowlistTest` 24/24, `ListApiBackwardCompatibilityIT` 16/16 |
| TC-SORT-006 (원문) | SortAllowlist.VIDEO 단일 진실원 | **PASS** | `SortFieldMapper.java:59,69` 위임, 사본 없음 |
| TC-LOG-001 | 전화번호/이메일/JSON형/bare JWT 마스킹 (A-ISSUE-62) | **PASS** | `LogMaskingPatterns` 단일 원천 + 규칙 9종 + 양 프로파일 배선. `LogMaskingPatternsTest` 27/27, `LocalLogMaskingIT` 3/3 |
| TC-LOG-001 (원문) | LogSanitizer 제어문자 제거 | **PASS** | `LogSanitizer.java:96-99` 유지 |
| TC-SAM2 mock (14/24/26 인접) | mock/`source≠model` 자동적용 차단 + 안내 | **PASS** | `AiMockMeta.untrusted()` 긍정증명 규약 + `Sam2TrackService.java:134` 배선. `AiMockMetaTest` 5/5, `Sam2TrackServiceTest` 17/17 |
| **(전 항목 공통)** | **스택 실동작(curl) 재현** | **BLOCKED** | 컨테이너 flyway V99 < V160 — 워크트리 코드가 컨테이너에 미반영 |

**정적 대조 + 단위/통합 테스트 기준 7/7 PASS. 실동작 검증은 전 항목 BLOCKED.**

## 6. 후속 권고 (이번 재검증 범위 밖)

1. **실동작 재검증 필요** — 수정분이 커밋·이미지 반영(V160+)된 뒤 4개 TC 를 curl 로 재확인해야
   BLOCKED 가 해소된다. 특히 TC-LOG-001 은 실제 로그 파일 관측이 정적 대조를 대체하지 않는다
   (인코더 배선은 확인했으나 런타임 어펜더 조합은 미관측).
2. **테스트 카탈로그 갱신 필요** — 이번 수정 4건 중 3건(exp 부재 / A-ISSUE-61 / A-ISSUE-62)이
   기존 TC-ID 의 **인접 결함**이라 카탈로그에 전용 행이 없다. SAM2 mock 차단도 마찬가지다.
   `docs/test-cases/A-auth-common.md`·`C-marking-labeling.md` 에 신규 TC 행 추가 권고.
3. **정보성** — 수정 코드가 `qa-0801` 워크트리에 미커밋 상태다. 커밋 전 워크트리 유실 위험.
