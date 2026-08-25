| 항목 | 값 |
|---|---|
| CO 번호 | CO-010 |
| 제목 | 영상 스트림 nonce 쿠키의 `Secure` 를 프로파일 기반 → 배포 설정 토글로 (기본 OFF) |
| 대상 도메인 | DOMAIN-003(영상·프레임 수집) — 대상 파일은 `common/security/` |
| 구현 상태 | 📝 작성 |
| LogiCraft 설계반영 | 🎨 완료 (API-114 v2 · API-084 v8) |
| 생성일 | 2026-08-25 |

---

## §1 배경

**증상** — cudo_246(dev)에서 마킹 화면의 영상이 재생되지 않는다. 재생 길이가 `00:00` 으로 뜨고 화면은 검다.

**실측**
- `GET /v1/videos/{rawSn}/stream?exp&u&sig` 가 **전건 401**, `video.error.code=4`(SRC_NOT_SUPPORTED)
- 앱이 stream-url ↔ 401 을 **8초에 336회** 재시도하는 폭주가 동반된다
- 같은 파일을 `Authorization: Bearer` 헤더로 요청하면 **`206 video/mp4`** 로 정상 전송된다
  → 파일·경로·NAS 마운트는 정상이며, 실패는 **인증 단계**다

**근본 원인** — `<video src>` 태그는 Authorization 헤더를 붙일 수 없어, 이 화면은 단기 서명 URL 로 재생한다.
서명 검증에는 URL 에 없는 **nonce 가 필요하고 그 값은 쿠키로 전달**된다. 그런데 그 쿠키에 `Secure` 가 붙는다:

```
Set-Cookie: klid_stream_nonce=...; Path=/api/v1/videos; Max-Age=3600; Secure; HttpOnly; SameSite=Lax
```

246 은 평문 HTTP(`http://192.168.102.246:13000`)로 서비스되므로 브라우저가 **`Secure` 쿠키를 저장하지 않는다**
(Playwright 브라우저 컨텍스트 쿠키 조회 결과 `(none)`). nonce 없음 → 서명 검증 실패 → 401.
서버는 대상 파일을 열어보지도 못하고 거부한다.

판정 축이 `.secure(!localProfile)` 이라 **local 프로파일만 예외**인데, 246 은 `SPRING_PROFILES_ACTIVE=dev`(컨테이너 확인)다.
코드 주석은 이 예외의 취지를 *"평문 HTTP 개발 서버에서 쿠키가 버려지지 않게 하기 위함"* 이라고 적어 두었다 —
**의도한 예외가 이미 있는데 "평문 HTTP 개발 서버 = local 프로파일"이라고 가정한 탓에 dev 로 도는 246 이 그 예외에서 빠진 것**이다.
설계 이견이 아니라 배포 형상과 가정의 어긋남이다.

**설계서 쪽 공백(이번에 함께 드러남)** — `API-114`(서명 URL 발급)에 nonce 쿠키 언급이 **0건**이고,
`API-084`(스트림)는 서명 호출 모드를 *"Authorization 헤더 없음"* 으로만 적어 **쿠키 없이도 되는 것처럼 읽힌다.**
서명 검증의 필수 입력이 계약에서 통째로 빠져 있던 것이 이 결함이 오래 눈에 띄지 않은 이유다.

## §1-1 ★이 결함은 dev 한정이 아니었다 — 온프렘 운영도 같이 깨져 있었다 (2026-08-25 실측)

CO 작성 시점에는 "246(dev)에서만 나는 문제"로 보였으나, 배포 산출물을 실측하니 **온프렘 운영도 동일 조건**이다.

| 항목 | 값 | 근거 |
|---|---|---|
| 프로파일 | `prd` | `deploy/onprem/config/backend/env.template:16` |
| 프록시 | **`listen 80`** (평문 HTTP) | `deploy/onprem/config/frontend/nginx.conf.template:13` |
| TLS 정책 | *"폐쇄망이라 TLS 는 걸지 않는다. 외부 노출 시 사내 TLS 종단을 앞단에."* | `deploy/onprem/docs/04-configuration.md:275` |

구 코드 `secure(!localProfile)` 는 **`local` 이 아니면 무조건** `Secure` 를 붙인다.
따라서 온프렘(prd + 평문 HTTP)에서도 브라우저가 nonce 쿠키를 저장하지 못해
**마킹 화면의 영상이 운영에서도 재생되지 않았을 것**이다.

⇒ 이 CO 는 dev 편의 수정이 아니라 **납품 전에 드러난 잠복 운영 결함의 수정**이며,
「기본 OFF」 결정은 편의가 아니라 **이 배포 형상에 맞는 값**이다.
평문 HTTP 배포가 기본값이고, TLS 종단을 앞단에 두는 배포만 `STREAM_COOKIE_SECURE=true` 로 켜면 된다.

⚠ 246 에서 우연히 걸리지 않았다면 운영 배포 후에야 드러났을 결함이다.

## §2 변경 요지

`authoring.stream.cookie-secure`(boolean) 를 신설하고, nonce 쿠키의 `Secure` 부여를 이 값으로 결정한다.
**기본값은 `false`(미부여)** — 사용자 확정.

`Secure` 기능 자체는 **삭제하지 않고 토글로 보존**한다. 켜려면 배포 설정에서 `true` 로 준다.
판정 축은 「프로파일」에서 「배포 설정값」으로 바뀌지만, **둘 다 배포 시 고정되고 요청자가 조작할 수 없다** —
즉 MEDIUM-2 의 실질(요청·헤더에 의존하지 않는다)은 그대로 유지된다.

## §3 도메인별 변경 상세

### DOMAIN-003 (영상·프레임 수집) — `klid-d003-implementer`

> ⚠ 대상 파일이 이 도메인의 통상 `code_root`(`video/`·`upload/`) 밖인 `common/security/` 에 있다.
> 스트림 API 계약(API-084·API-114)의 소유 도메인이 DOMAIN-003 이므로 여기에 배정한다.
> **아래 열거한 파일 밖은 건드리지 말 것.**

- **대상 파일·심볼**
  - `backend/src/main/java/kr/co/cudo/authoring/common/security/StreamNonceCookie.java`
    - `:81` `private final boolean localProfile;`
    - `:83~90` 생성자 `StreamNonceCookie(Environment, @Value("${authoring.stream.sign-secret:}") String)`
    - `:218` `.secure(!localProfile)`
  - `backend/src/main/resources/application.yml` — `authoring.stream:` 절(현재 `sign-secret`·`url-ttl-seconds` 가 있는 `:455~457`)
  - `backend/.env.example` — `STREAM_SIGN_SECRET=` 인접(`:26`)
  - `backend/src/test/java/kr/co/cudo/authoring/common/security/StreamNonceCookieSecureFlagTest.java`

- **변경**
  1. `localProfile` 필드를 설정값 필드로 교체 — `@Value("${authoring.stream.cookie-secure:false}") boolean cookieSecure`
  2. 생성자에서 `environment.acceptsProfiles(Profiles.of("local"))` 제거.
     `Environment` 가 이 클래스에서 **다른 용도로 쓰이지 않음을 확인했으므로 의존과 import 를 함께 제거**한다
     (`Environment`·`Profiles` import 정리 포함)
  3. `:218` → `.secure(cookieSecure)`
  4. `application.yml` 의 `authoring.stream` 절에 `cookie-secure: ${STREAM_COOKIE_SECURE:false}` 추가.
     주석에 **기본 OFF 라는 사실 + prd 에서 켜려면 `true` 로** 를 명시
  5. `.env.example` 에 `STREAM_COOKIE_SECURE=` 추가
  6. javadoc(`buildCookie` 위 블록) 갱신 — 구 서술("local 프로파일이 아니면 무조건 부여")을 폐기 표기하고
     새 판정 축과 **기본 OFF 의 대가**를 적는다

- **불변(건드리면 안 되는 것)**
  - `HttpOnly` · `SameSite=Lax` · `Path`(contextPath + `/v1/videos`) · `Max-Age`(`COOKIE_TTL`) — **전부 무변경**
  - 쿠키 이름(`COOKIE_NAME`)·봉인(seal) 로직·`sealKey` 파생·`resolveOrIssue` 시그니처 무변경
  - `authoring.stream.sign-secret` · `url-ttl-seconds` 무변경
  - 서명 생성·검증 로직(`StreamUrlSigner`·`StreamSignatureFilter`) 무변경 — 이 CO 는 **쿠키 속성 한 줄**만 다룬다

- **주의**
  - ❌ `request.isSecure()` 를 판정 축으로 되돌리지 말 것 — TLS 종단 LB 뒤에서 항상 false 라 DEV_FIX MEDIUM-2 로 이미 폐기된 방법이다
  - ❌ `server.forward-headers-strategy` 를 켜지 말 것 — 2026-07-25 REDESIGN 으로 되돌린 결정이고 `ForwardedHeadersConfigGuard` 가 `IllegalStateException` 으로 **기동을 거부**한다
  - ❌ `Secure` 를 삭제하지 말 것 — 기능은 보존하고 기본값만 OFF
  - ❌ **기본값을 `true` 로 되돌리지 말 것** — 아래 §4 의 수용된 대가 참조. 보안 리뷰가 이를 결함으로 재분류해도 사용자 확정 사항이다
  - `ConfigPropertyKeyGuardTest` 는 yml↔코드를 **자동 스캔**한다 — 별도 등록 목록이 없으므로 yml 과 코드 양쪽에 키를 두면 통과한다

- **수용기준**
  - `cookie-secure=false`(기본) → `Set-Cookie` 에 `Secure` **없음**
  - `cookie-secure=true` → `Set-Cookie` 에 `Secure` **있음**
  - 두 경우 모두 `HttpOnly`·`SameSite=Lax`·`Path`·`Max-Age` 는 동일
  - 프로파일(local/dev/stg/prd)이 무엇이든 **결과가 설정값에만 좌우**된다
  - `request.isSecure()`·`X-Forwarded-Proto` 가 무엇이든 결과가 바뀌지 않는다(요청 비의존 불변식 유지)

### 프론트 — 해당 없음

FE 변경 없음. 쿠키는 브라우저가 자동 처리하며 응답 스키마·호출 방식이 바뀌지 않는다.

### 공유기반 선처리 — 해당 없음

`common/security/` 파일이지만 **공유 시그니처·스키마·앱 진입점을 바꾸지 않는다**(생성자 파라미터만 바뀌고 그 빈의 주입처는 Spring 이 처리).
Flyway 마이그레이션 없음. 따라서 Phase 3.5 선처리 대상이 아니다.

## §4 영향·리스크

- **하위호환**: API 응답 스키마·상태코드·쿠키 이름/경로 무변경. 기존 호출자 영향 없음.
- **★수용된 대가(사용자 확정 — 되돌리지 말 것)**:
  **기본 OFF 이므로 prd·stg 도 `Secure` 가 빠진다.** 지금까지는 프로파일만으로 자동 부여됐으나,
  앞으로는 `authoring.stream.cookie-secure: true`(또는 `STREAM_COOKIE_SECURE=true`)를 명시해야 붙는다.
  prd(HTTPS)에서 평문 http 요청이 섞였을 때 nonce 쿠키가 평문으로 흐르는 것을 감수하는 선택이다.
  사용자가 트레이드오프를 제시받은 뒤 *"Secure 기능은 만들어놓고 기본은 OFF 로 하자"* 로 확정했다.
- **리스크**: 위 대가가 유일하다. nonce 는 단독으로는 쓸모가 없고 60초 TTL 서명 URL(`exp`/`u`/`sig`)이 함께 있어야 하며,
  평문 구간을 관측할 수 있는 상대에게는 영상 바이트 자체가 이미 노출된다.
- **되돌리기**: 설정 한 줄(`true`)로 종전 동작 복원. 코드 롤백 불필요.

## §5 검증

- `StreamNonceCookieSecureFlagTest` 재작성 — **켬/끔 양쪽** 케이스 + 요청 비의존 불변식 고정
  - 기존 테스트는 *"프로파일이 판정 축"* 을 고정하는 회귀 가드다. 그 가드의 실질(요청·헤더에 의존하지 않는다)은
    이번 변경 후에도 유지되므로 **삭제하지 말고 판정 축만 토글로 옮겨 재작성**한다.
    구 의도(MEDIUM-2)가 무엇이었고 왜 여전히 지켜지는지를 javadoc 에 남길 것.
- `ConfigPropertyKeyGuardTest` 통과(자동 스캔 — yml+코드 양쪽 존재로 충족)
- 보안 리뷰 포함 — 쿠키 속성 변경이므로
- 246 배포 후 수동 확인: 마킹 화면에서 영상이 재생되고 배속(0.25x~4x)이 동작 · 401 재시도 폭주 소멸

## §6 관련 설계 ITEM  ★구현 전에 먼저 확정한다

| ITEM | 타입 | 무엇을 어떻게 | 근거 |
|---|---|---|---|
| API-114 | api_endpoint | 응답 계약에 **nonce 쿠키 발급**을 신설 기술: 쿠키 이름·`HttpOnly`·`SameSite=Lax`·`Path`·`Max-Age`, 그리고 **`Secure` 는 배포 설정으로 결정되며 기본 미부여** | 현재 nonce/쿠키 언급 **0건**인데 서명 검증의 필수 입력이다. 계약에 없어 이번 결함이 오래 안 보였다 |
| API-084 | api_endpoint | 서명 쿼리 호출 모드의 전제에 **"API-114 가 발급한 nonce 쿠키 동반 필요"** 와 **쿠키 부재 시 401** 을 명시 | 현재 *"Authorization 헤더 없음"* 만 적혀 있어 쿠키 없이도 호출되는 것처럼 읽힌다 |

**cascade 예상 하위**: SCREEN-006(마킹 화면 — DOMAIN-003·DOMAIN-011 키트에 공존). 그 밖 AC·SEQ 는 `analyze_impact` 로 말단까지 조회해 판정할 것.

**확정: `API-114 v2` · `API-084 v8`** (2026-08-25, Phase 3.6)

- **API-114 v1→v2** — 200 응답 설명과 엔드포인트 설명에 nonce 쿠키(`klid_stream_nonce`) 발급 계약 신설.
  이름·봉인값·`HttpOnly`·`SameSite=Lax`·`Path` 한정·`Max-Age` 3600·**`Secure` 는 배포 설정으로 결정하며 기본 미부여**·재사용 규칙.
  발급측이라 `parameters[in=cookie]` 로 모델링하지 않았다(호출자가 채워 보내는 값으로 오독되는 것을 피함).
- **API-084 v7→v8** — 서명 쿼리 호출 모드의 전제로 nonce 쿠키 동반을 명시, `parameters` 에
  `in=cookie` `klid_stream_nonce` 신설(`required=false` — 헤더 직접 호출에는 불필요해 조건부 필수를 설명에 명시),
  401 사유에 쿠키 부재·불일치 추가. 기존 두 호출 방식 서술과 역할 집합 동일 서술은 보존.

**검증(메인 독립 재조회)**: 두 ITEM 모두 서버에서 원문을 다시 받아 대조 —
치환문자 0 · 알려진 오타 0 · 종성ㅁ 오전사 0 · 희귀음절 전 출현 문맥 정상.
`status=draft` 유지, 응답 코드 집합·기존 파라미터·태그 무손실.

**cascade 처리 결정 (2026-08-25 사용자 확정)**
- ✅ `SCREEN-006`(마킹 화면, v44 `stale=true`) — **반영한다.** 영상 재생 절차에 쿠키 전제가 없어
  이번 장애가 난 바로 그 구멍이다. 쿠키 속성 전문은 API-114 가 진실원이므로 화면에 복제하지 않는다.
- ⏸ `NFR-017` — **이번에 손대지 않는다(기록만).** 전역 호출 규약이 `Authorization: Bearer JWT` 를
  단일 전달 수단으로 규정하고 JWT 우회 경로를 webhook 2종만 열거해, 서명 쿼리 호출 모드가 빠져 있다.
  ⚠ **이번 변경이 만든 드리프트가 아니라 v7 이전부터의 선행 갭**이며, 전역 규약을 넓히는 결정이라
  별건으로 다룬다. 다음 감사가 이것을 신규 결함으로 재발견하지 않도록 여기 남긴다.

**함께 드러난 것(이번 CO 범위 밖)**
- nonce 도입을 관장하는 ADR 이 없다(ADR 53건 전수에 서명·nonce 축 없음). 보안 결정이라 신설 대상인지 확정 필요
- `API-084.security` 는 여전히 `{jwt:[REVIEWER,WORKER]}` 하나뿐이라 서명 쿼리 스킴이 모델링돼 있지 않다
- `API-084` 401 설명에 `UNAUTHORIZED` 뒤 문장 구분 기호 누락(기준선에 없던 것 — 다음 편집 시 1자 보정)

## §6-1 층(layer) 점검 — 이 결정이 복제된 곳 전수

> 「정합 완료」는 7개 층을 전부 확인했을 때만 성립한다(`.claude/rules/logicraft-integration.md` §5 D-2).

| # | 층 | 상태 |
|---|---|---|
| 1 | ITEM 본문 | ✅ API-114 v2 · API-084 v8 · SCREEN-006 v45 (전건 메인 독립 재조회 검증) |
| 2 | **정적 렌더 미러** | ⚠ **stale** — `SCREEN-006/main.html` 의 `source_hash`(`7f128ef5…`)가 현재 `sections` 해시(`95b80a1b…`)와 불일치. ★**내 편집 이전부터 이미 낡아 있었다**(v44 해시 `7892a85c…` 도 저장값과 불일치) → 재생성 시 **검증 질문 섹션(v44) + 쿠키 전제(v45) 두 변경분을 함께** 반영해야 한다. overlay surface 없음(page 1건) |
| 3 | 링크·역참조 | ✅ `unresolved=0`, 순손실 0 (removed 1 은 description 자동추출 references 재생성) |
| 4 | **로컬 키트 스냅샷** | ⚠ **3곳 모두 v43** — `docs/design/영상프레임-수집-DOMAIN-003/` · `docs/design/마킹-DOMAIN-011/` · **`docs/screen-design/klid-authoring-screens/`**(통합 32화면 키트). 편집 전 서버가 v44 였으므로 이미 뒤처져 있었다 |
| 5 | 위키 `docs/v2-wiki/` | ✅ **충돌 없음** — `05-video-management.md`·`19-external-security-cvat.md` 가 nonce 쿠키를 기술하나 `HttpOnly`·`SameSite=Lax` 만 적고 **`Secure` 주장은 없다** |
| 6 | **테스트케이스 `docs/test-cases/`** | ⚠ **갱신 필요** — `A-auth-common.md` **TC-STREAM-010** 이 *"+ Secure(local 프로파일 제외). 판정축은 프로파일"* 을 직접 단언한다. **이번 변경으로 거짓이 되므로 같은 커밋에서 갱신**(구속 규칙). TC-STREAM-008·009 는 nonce 유무 축이라 무영향 |
| 7 | 코드 | 🔨 구현 중 |

## §6-2 이번에 손대지 않은 인접 드리프트 (다음 감사가 신규 결함으로 오인하지 않도록)

- **`UC-019`** — 재생을 *"GET /v1/videos/{rawSn}/stream 직접 스트리밍"* 으로 적어 **서명 URL 선행 발급 단계가 없다**(nonce·쿠키·서명·stream-url 각 0회). ⚠ **이번 변경이 만든 것이 아니라 선행 드리프트**다. UC 가 전송 계층을 어느 깊이까지 담을지는 정책 판단이라 사용자 확정 대상
- **`NFR-017`** — 위 §6 참조(선행 갭, 보류 확정)
- **`NAV-001`·`SHELL-001`** — 각각 `UC-032`·`SCREEN-026` 발 **다른 축의 미해결 stale 표식**을 갖고 있다. 누군가 이 둘을 편집하면 그 표식이 자동 해제되어 추적이 사라진다(rules §2-F)
- **nonce 도입 ADR 부재** — ADR 53건 전수에 서명·nonce 축 없음. 보안 결정이라 신설 대상인지 별도 확정 필요

## §7 구현 로그

| 일시 | 도메인 | 에이전트 | 결과 | QA | 커밋 |
|---|---|---|---|---|---|
| | | | | | |

**미반영·보류 항목**: (없음)
