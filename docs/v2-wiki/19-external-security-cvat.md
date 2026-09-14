# 19. 외부 시스템 · 보안 · CVAT 포팅 · 설계 문서

> 출처: CLAUDE.md, R1 NFR, `.claude/rules/security.md`, 코드(`common/client`, `webhook`, `common/util`), `docs/archive/frozen-20260815/design/`(⚠ 2026-08-15 동결·이관됨 — 원래 경로 `docs/design/*.md` 는 더 이상 CBD 산출물을 담지 않는다. 현재 `docs/design/` 에는 LogiCraft 구현 키트 디렉터리만 있다. 상세 → [§19.4](#194-설계-문서-카탈로그))
> 관련: 전체 페이지

## 19.1 외부 시스템

### A. 연동 외부 시스템 (호출/통신)

| 외부 시스템 | 연동 | 코드 |
|------------|------|------|
| **관제서버** | JWT 발급, TASK_COMPLETED/MODIFIED 통지 수신, 조회 API + View SELECT [폐기 표기 — 아래 참조] | `ControlNotifyClient`, `controlnotify/` → [15](15-control-notify.md) |
| **포털 서버** | ⚠ **[폐기]** 포털 DB 공유 — 저작도구가 메타를 내보내는 단방향 복제. **2026-08-31 상호 DB 미접근 확정(구속)으로 폐기**됐고 **같은 날 코드에서 철거**됐다. 데이터 교환은 API 로 설계한다(판정 정본 `INT-009`). **되살리지 말 것.** 포털 라벨 화면의 조회가 저작도구 DB 를 쓴다는 것은 원래부터 사실이며 그대로 유효 | `PortalDataSourceConfig`(복제 축), `portal/` → [16](16-portal.md) |
| **비식별화 서버** | 영상 비식별 위탁 + **완료 감지 폴링**(콜백 아님) [폐기 표기 — 아래 참조] | `KpstDeidentifyClient`, `batch/scheduler/KpstDeidentPollJob` → [08](08-deidentification.md) |
| **외부 VLM 서비스** | 시계열 메타 호출 + 콜백 | `VlmClient`, `VlmTimeseriesStep`, `webhook/VlmResultController` → [09](09-vlm-timeseries.md) |
| **외부 증강/생성 시스템** | 증강 위탁 + 콜백 [폐기 표기 — 아래 참조] | `augment/integration/ExternalAugmentClient`, `webhook/GenAiCallbackController`(+`webhook/service/AugmentResultService`) → [14](14-augmentation.md) |

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 위 표 3개 행을 정정한다.
> 1. **관제서버 "MNG_* 소유"** — 관제 공유 테이블 `MNG_*` 는 현재 코드에 **하나도 남아 있지 않다**(적재 주체 반전 ADR-042, `V167` 이 `MNG_CLIP_MASTER`/`MNG_CLIP_EVNT_LST`/`MNG_RESOURCE_CCTV`/`MNG_EX_LOCAL_GOV` 4종을 DROP). 근거: `architecture/MngControlMasterTableRemovalTest.java` → [02 §2.5](02-architecture.md#25-듀얼-데이터소스).
> 2. **비식별화 서버 "콜백"** — KPST 비식별 완료는 **웹훅 콜백을 받지 않는다.** `batch/scheduler/KpstDeidentPollJob`(Quartz, `@DisallowConcurrentExecution`)이 `WAITING`/`POLLING` 상태 위탁 건을 주기적으로 `retrieve_progress` 폴링해 완료(state=2)를 감지·다운로드한다. `webhook/DeidentifyResultController` 라는 클래스는 **존재하지 않으며**, `webhook/` 패키지의 실제 파일 목록에도 비식별 콜백 컨트롤러는 없다(VLM `webhook/VlmResultController` · 증강 `webhook/GenAiCallbackController` 둘뿐). 웹훅 인증 절(아래 §19.1 「웹훅 인증」)이 보호하는 경로도 `WebhookProtectedPaths` allowlist상 `/v1/vlm/**`·`/v1/genai/**` 뿐이고 비식별 경로는 없다 — 이 자체가 비식별이 콜백 모델이 아님을 뒷받침한다. 근거: `KpstDeidentPollJob.java`(클래스 javadoc), `find backend/src/main/java/kr/co/cudo/authoring/webhook -name "*.java"`.
> 3. **외부 증강/생성 시스템 코드 표기** — `ExternalAugmentClient` 는 `common.client` 가 아니라 **`augment/integration/`** 패키지에 있다. 콜백을 실제로 받는 컨트롤러는 `webhook/AugmentResultController`(존재하지 않음)가 아니라 **`webhook/GenAiCallbackController`**(`@RequestMapping("/v1/genai")`)이며, 결과 반영 로직은 `webhook/service/AugmentResultService` 가 담당한다. 근거: `find backend/src/main/java -iname "ExternalAugmentClient.java"`, `webhook/GenAiCallbackController.java`(클래스 선언부).

> **ai-server(YOLO/SAM2/VLM 추론)는 외부 아님** — 모노레포 내부 별도 프로세스(`AiServerClient`). → [11](11-ai-assisted.md)

### B. 범위 외 시스템 (책임 위임)

데이터마트 · 생성형 AI 본체 · VLM 모델 본체 · 영상 합성 모델 본체 · 학습데이터 Export → 외부 책임. 저작도구는 연동/검수만. → [01 §1.2](01-system-overview.md#12-책임-범위)

### 공통 연동 인프라
- **Resilience4j** — 모든 외부 호출 타임아웃/재시도/서킷 [폐기 표기 — 아래 참조]
- **웹훅 멱등성** — `webhook/` + `LS_WEBHOOK_IDEMPOTENCY` (In-Memory/Persistent Ledger), HMAC + idempotencyKey
- **Fallback 큐** — `LS_CONTROL_NOTIFY_FALLBACK` [폐기 표기 — 아래 참조]

> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — 두 항목을 정정한다.
> 1. **"VLM 45s, ai-server 60s, 비식별 ~70s"** — ai-server 는 60s(`AiWaitBudgetPolicy.PER_CALL_TIMEOUT`)로 맞다. 그러나 **VLM 45s 는 폐지된 구 모델의 수치**다 — VLM·KPST 위탁은 더 이상 단순 동기 호출 타임아웃이 아니라 **논블로킹 제출**(선커밋 → `subscribe()` 후 즉시 반환)이며, 실제 설정값은 `vlm.client.timeout-seconds: 10`(제출 자체의 HTTP 호출 타임아웃)이고, 그 뒤의 수락(ACK)·결과 대기는 별도의 두 창으로 관리된다 — **ACK 창** `authoring.batch.vlm.submit-reclaim.stale-timeout-minutes`(기본 **30분**) / **콜백 창** `authoring.batch.vlm.submit-reclaim.callback-timeout-minutes`(기본 **360분**), 미수신 시 `batch/vlm/VlmSubmitPendingSweeper` 가 회수한다. 비식별(KPST)의 "~70s"도 근거를 찾지 못했다 — 실측된 값은 WebClient 응답 안전망 `common/config/KpstWebClientConfig.java(RESPONSE_TIMEOUT)` **60초** + 위탁 전체 진행 타임아웃 `kpst.deid.poll-timeout-minutes`(기본 **180분**, `KPST_DEID_POLL_TIMEOUT_MINUTES`)이며, 완료 감지는 위 표에서 정정한 대로 **폴링**(콜백 아님)이다. 근거: `application.yml`(`vlm.client.timeout-seconds`·`kpst.deid.poll-timeout-minutes`), `VlmSubmitPendingSweeper.java`(`@Value` 기본값), `KpstWebClientConfig.java(RESPONSE_TIMEOUT)`.
> 2. **`LS_GITEA_FALLBACK_QUEUE`** — 이 테이블은 **코드 어디에도 없다**(`grep -a -rn "LS_GITEA_FALLBACK_QUEUE\|GITEA" backend/src/main/resources/db/migration backend/src/main/java` → 0건). 이 프로젝트는 Gitea 연동을 갖지 않는다(무관한 프로젝트의 잔재로 추정). 실재하는 fallback 큐는 `LS_CONTROL_NOTIFY_FALLBACK`(`controlnotify/fallback/LsControlNotifyFallback.java`) **하나뿐**이다.

### 연동 서버 주소 설정 (R11, 2026-08-10)

외부 연동 주소를 **재기동 없이** 운영 화면(「연동 서버 주소」 카드)에서 바꾼다.

★**축이 둘로 갈렸다 (2026-09-08 정정)** — 아래 표는 **설정 키 한 칸으로 바꾸는 축**이다.
**AI 추론 서버와 외부 시계열 분석 벤더는 이 축이 아니라 「장비 원장」 축**이며, 그 화면은
한 칸이 아니라 **장비 목록**(조회·등록·수정·상태 전이·삭제)이다.

| 대상 | 설정 키 |
|------|--------|
| 비식별 서버 | `kpst.deid.base-url` |
| 관제 통지 수신처 | `authoring.control-notify.url` (**통지 전용** — 세션 중계는 쓰지 않는다) |
| 관제 계정 창구 | `authoring.control-account.url` (**2026-09-14 신설** — 관제 채널 세션 연장·로그아웃 중계 전용. 통지 수신처와 별개이고 **폴백 없음** · 배포 기본값 **비움**(관제 채널 배포본은 반드시 설정). ⚠ 관제는 계정 창구(`/api/account/`)와 데이터셋 창구(`/api/data-set/`)를 **서로 다른 WAS** 에 둔다 — 한 주소를 공유하면 WAS 직접 연결 시 한쪽이 404 이고 현장에서 실제로 세션 연장이 전부 실패했다. ⚠ **잔여 위험(인지·수용, `NFR-013`)** — 이 주소를 바꾸면 사용자 세션 자격증명이 그 주소로 전송된다) |
| 외부 증강 벤더 | `authoring.augment.external.base-url` (**2026-09-08 신설** — ⚠ 구 서술 폐기: *「교체 창구에 아직 미등록 — 채워야 할 잔여」*. 그 잔여가 닫혔다. ⚠ **비어 있는 것이 「아직 연동 안 됨」의 유일한 표현**이라 미리 채우지 말 것 · **콜백을 받을 출처 허용 목록과 짝**이라 주소만 채우면 위탁은 나가는데 결과를 못 받는다) |

⚠ **구 서술 폐기(2026-09-08)** — 이 표는 *"외부 연동 **4종**"* 으로 **AI 추론 서버
(`authoring.integration.ai-server.base-url`)와 외부 시계열 분석 벤더(`vlm.client.url`)를 함께**
싣고 있었다. 두 축의 **주소 진실원은 장비 원장으로 옮겨갔다** — 배포 설정값은 **그 유형의 장비가
하나도 없을 때 원장에 첫 행을 심는 최초 1회 씨앗**일 뿐이고, 그 뒤로는 원장이 이긴다.
⚠ **「원장에 행이 없으면 설정값으로 대신 호출한다」는 폴백은 두지 않는다** — 두면 진실원이 둘이 된다.
⚠ **개수 표기를 되살리지 말 것**(구 *"4종"*) — 대상이 하나 늘 때마다 그 숫자를 인용한 모든 층이
동시에 틀린다. **열거로 적는다.**

- **설정 키 = 애플리케이션 속성명**. 별도 키명을 만들면 "설정 키 ↔ 속성명" 매핑표가 두 번째 진실원이 되어, 한쪽만 갱신되는 순간 화면에서 바꾼 주소가 엉뚱한 연동에 반영된다. 이름을 같게 두면 **"설정에 있으면 설정, 없으면 배포 기본값"** 이 표 없이 성립한다.
- **시드하지 않는다.** 설정 행이 없는 것이 정상이며 그때는 배포 기본값(`@Value`)이 쓰인다. 그래서 이 설정 키들은 **최초 저장 시 행을 새로 만든다**(선언 타입 STRING). 목록 조회에도 저장 전에는 나타나지 않는다.
- **판정 단일 지점** — `IntegrationEndpointResolver`. 호출처마다 우선순위를 배선하지 않는다.
- **★즉시 반영** — `WebClient` 의 `baseUrl` 은 빈 생성 시점에 고정되므로, 설정만 바꾸면 **재기동 전까지 옛 주소로 계속 호출**된다. `IntegrationEndpointExchangeFilter` 가 **매 호출 시점**에 주소를 다시 읽어 요청 URL 을 고쳐 쓴다. override 가 없으면 필터는 아무것도 하지 않아 기존 형상 영향이 0 이다. 다른 노드는 설정 캐시 TTL(최대 60초) 뒤에 반영된다.
  - **★override 가 없는 상태도 캐시된다 (2026-08-10 정정)** — 리졸버는 `SystemConfigService.findString`(`Optional`)을 쓴다. 구 동작은 `getString` 이라 **행이 없는 정상 상태에서 예외**가 났고 **Spring 캐시는 예외를 캐시하지 않아** 엔트리가 영영 만들어지지 않았다(외부 호출마다 DB 왕복 + 예외 생성). "Caffeine TTL 60s 가 이미 막는다"는 설명이 **정상 상태에서 성립하지 않았다** — 영향 경로에 라벨링 캔버스의 온라인 오토라벨·SAM2 처럼 사용자 클릭당 발생하는 대화형 핫패스가 있다. `getString` 의 계약(행 없으면 404)은 그대로다. 저장 시 캐시 무효화가 `allEntries` 라 새 키도 함께 비워져 **즉시 반영은 불변**이다. 근거 `SystemConfigService.java(findString)` · `IntegrationEndpointResolver.java(readOverride)`.
  - **base 경로 접두는 세그먼트 경계로 판정한다** — `/api` 기본값에 `/apix/foo` 요청이 오면 접두가 아니다. 구 동작은 `startsWith` 뿐이라 조각(`x/foo`)이 남아 **호스트가 `newhostx` 로 변조**될 수 있었다(현재 4종 기본값이 모두 경로 없는 형태라 미도달이었으나, 기본값에 경로가 붙는 순간 활성화). 새 주소에서 authority 를 못 뽑으면 **재작성을 포기**한다. 근거 `IntegrationEndpointExchangeFilter.java(rewrite)`.
- **값 검증 = 스키마와 형식뿐** — `http`/`https` 스키마, URI 파싱 가능, host 존재, 길이 상한. 위반은 400.
  - **언더스코어 호스트는 통과한다** — `URI#getHost()` 는 `http://my_host:9400` 에 `null` 을 주므로 그것만 보면 **도커 컴포즈 서비스명을 주소로 넣을 수 없다**. `SafeUrl.hostOf` 의 authority 폴백을 비식별 신뢰 가드와 공유한다. 근거 `SafeUrl.java(hostOf)` · `IntegrationEndpointUrlValidator.java(validateForSave)`.
  - **★주소에 자격증명을 끼워 넣을 수 없다 (CWE-532)** — `http://user:pass@host` 는 **400**. 이 값은 감사 로그에 원문으로 남는데 로그 마스킹은 **키워드(`password=`) 기반**이라 이 형태를 잡지 못한다(실측: IP·언더스코어 호스트에서는 비밀번호가 통째로 평문 기록). 로깅 직전 `SafeUrl.maskUserInfo` 로 한 번 더 가려 **이중 방어**한다. ⚠ 이것은 **대역 판정이 아니다** — 주소가 어디를 가리키는지는 여전히 보지 않는다. 근거 `SafeUrl.java(maskUserInfo)` · `SafeUrlTest.java(logMaskingAloneDoesNotCatchUserInfo)`.
- **★IP 대역으로는 막지 않는다 (2026-08-10 사용자 확정, 구속)** — 사설·링크로컬·루프백 차단은 **폐지**됐다. 근거: 이 연동 4종은 **실제로 내부망의 별도 서버에 있을 가능성이 높아** 대역으로 막으면 정당한 대상을 막는다. 아웃바운드·인바운드 통제는 **인프라 계층이 담당**한다. ⚠ 같은 이유로 **요청 전송 직전 재검증(DNS rebinding 방어)도 폐지**됐다 — 막을 대역이 없으면 재검증할 내용이 없다. **"막는다"고 적지 말 것.**
- **★비식별 주소만 「목/시뮬레이터 호스트」 축을 하나 더 지난다** — `DeidentifyEndpointTrustGuard` 는 위조 비식별본(원본을 그대로 복사한 "비식별본")이 학습데이터·외부 통지로 흘러가는 것을 prd 에서 fail-closed 로 막는데, 그 판정이 **기동 시 `@Value` 배포값 1회**뿐이라 **화면에서 목 주소를 저장하면 통째로 우회**됐다. 이제 저장 시점에도 **같은 판정 함수**를 태운다 — 운영(prd 프로파일 또는 `ENV=prd`)이면 **400**, 그 외 프로파일은 **WARN 후 저장**(dev/stg 의 목 서버 연동이 정상 경로라 기동 시 강도와 동일). ⚠ 축은 **호스트명**(`localhost`·`127.0.0.1`·`::1`·`0.0.0.0`·`mock` 포함 호스트)이지 IP 대역이 아니다 — 사설망 주소는 운영에서도 저장된다. 근거 `DeidentifyEndpointTrustGuard.java(verifyForSave · untrustedReason)` · `SystemConfigService.java(doUpdate)`.
- **★주소가 바뀌면 정적 자격증명은 따라가지 않는다 (CWE-522)** — `WebClient.defaultHeader` 는 빈 생성 시점 고정이라, URL 만 바꾸면 **원 수신처에 발급된 토큰이 새 호스트로 그대로 전송**됐다(VLM 인증 헤더 · 관제 통지 `x-access-token`). ⚠ **VLM 쪽 헤더는 2026-09-07 에 `Authorization: Bearer` → `X-API-Key` 로 바뀌었고 이 가드는 새 헤더에도 그대로 걸린다** → [09 §9.5](09-vlm-timeseries.md). 이제 **호스트가 배포 기본값과 다르면 그 헤더를 떼고 WARN** 한다 — 그 토큰은 다른 호스트에서 어차피 무효라 떼면 상대가 401 로 **시끄럽게 실패**하고, 안 떼면 **조용히 유출**된다. 판정 축은 **호스트**라 포트·경로만 바뀌는 정당한 구성 변경에서는 그대로 붙는다. WARN 에 **토큰 값·주소 원문을 싣지 않는다**. 스킴이 바뀌면(특히 `https→http` 평문 강등) 같은 자리에서 경고한다 — KPST 의 스킴 경고와 대칭. 근거 `IntegrationEndpointTransportGuards.java(stripCredentialOnHostChange · warnOnSchemeChange)`.
- **거부 응답에 입력 원문·호스트를 싣지 않는다 (CWE-117/209)** — 사유별 고정 문구만 나가고 원문은 서버 로그에도 남기지 않는다(사유 코드만).
- **관리자 단기 유효창** — 이 4개 키는 REVIEWER 권한에 더해 `POST /v1/manage/admin-session` 으로 연 짧은 창(기본 10분·상한 30분)에서만 저장된다. 상세는 [03 §인증](03-auth-roles.md). 그 토큰을 싣는 `X-Admin-Session` 은 **CORS `allowedHeaders` 에 등록돼 있어야** 한다 — 없으면 교차 출처 형상에서 preflight 가 거절돼 **저장이 브라우저에서만 조용히 실패**한다(서버 로그에 아무것도 남지 않는다). 근거 `SecurityConfig.java(corsConfigurationSource)` · `CorsAllowedHeadersTest.java(adminSessionHeaderIsAllowed)`.
- **감사** — 새 테이블을 두지 않는다. `LS_SYSTEM_CONFIG` 의 `MDFR_ID`·`MDFCN_DT` 가 누가·언제·어느 키·현재값을 남기고, 여기에 변경 사실을 INFO 로그로 더한다(**주소 값은 남기고 패스워드·토큰은 남기지 않는다**).

> **비식별 키가 `kpst.deid.base-url` 인 이유 (2026-08-10 확정)** — 구 설계는 `authoring.integration.deidentify.base-url` 을 지정했으나 그 속성이 구동하는 `deidentifyWebClient` 빈은 **주입 대상이 0건**이라 값을 바꿔도 위탁 주소가 달라지지 않았다. 실제 위탁은 `DeidentifyStep` → `KpstDeidentService` → `KpstDeidentifyClient` → `kpstDeidWebClient` 로 나간다. **실효 0 인 칸을 화면에 남기지 않는다** — 구 키는 설정 화이트리스트에서 제거했다(빈 자체는 존치). → [08](08-deidentification.md)
>
> ⚠ **진행조회만 저수준 클라이언트를 쓴다** — KPST 서버가 GET 에도 JSON 바디를 요구해 `WebClient` 로는 바디가 전송되지 않기 때문이다. 그 경로에는 필터 훅이 없어 **호출 시점에 절대 URI 를 만들어** 넘긴다. 이걸 빠뜨리면 주소 변경 후 **프로젝트는 새 서버에 생기고 진행조회는 옛 서버로 나가** 그 작업이 영원히 완료되지 않는다(부분 반영이 미반영보다 위험하다).
>
> ⚠ **TLS 구성은 주소를 따라가지 않는다 (한계)** — 자체 CA `SslContext` 주입 여부는 **기동 시점 base-url 의 스킴**으로 정해지고 필터는 URL 만 바꾼다. 따라서 ①`http`↔`https` 전환 ②새 호스트의 인증서가 그 자체 CA 로 서명돼 있지 않은 경우 는 **재기동(또는 인증서 재배포)이 필요**하다. 인증서 검증을 낮추지 않으며(CWE-295), 스킴이 갈리면 기동 후 1회 WARN 으로 드러낸다.

### 웹훅 인증 (2026-07-25 개편 — 1차 검증 CRITICAL 대응)

콜백 경로는 `SecurityConfig` 에서 `permitAll` 이라 **`HmacWebhookFilter` 가 유일한 인증 수단**이다.
1차 전수 검증에서 `POST /api/v1/%61ug/callback`(a→`%61`)이 필터를 건너뛰고 컨트롤러에 도달해
**무인증으로 증강행을 전이**시킨 사실이 실증되어(CWE-436 → CWE-288) 아래로 개편했다.

| 축 | 정책 |
|----|------|
| 경로 판정 | `WebhookProtectedPaths` **allowlist**(`/v1/vlm/**`, `/v1/genai/**`). Spring MVC 라우팅과 **동일한** `RequestPath`+`PathPattern` 사용(자체 디코딩 구현 금지). 판정 불가·예외 = **보호**(fail-closed) |
| 이중 게이트 | 필터가 통과 요청을 `WebhookGuardedRequest` 래퍼로 감싸고, `WebhookGateInterceptor` 가 컨트롤러 진입 직전 증거 유무를 재확인 → 증거 없으면 401 |
| 필터 등록 | Security 체인 1곳으로 고정(`WebhookGateConfig` 가 서블릿 자동 등록 비활성) |
| 시크릿 | `webhook.hmac.secret.augment` 32B 이상 필수(2026-07-27 Phase 7-A2 로 **등록된 서명 필수 경로는 0** 이 됐으나, 경로 판정 불가 요청은 여전히 서명 요구 분기로 들어가므로 시크릿은 유지한다). **빈 값이면 애플리케이션 기동 실패**(조용한 401 금지). 리포에 커밋된 **공개 placeholder 값은 local 외 프로파일에서 기동 차단**(공개 키 서명 위조 차단, CWE-1392/798). `.env.example`·compose 는 실값·기본값 폴백을 두지 않고 `openssl rand -hex 32` 주입을 요구 |
| replay 방지 | 서명 nonce 를 `LS_WHK_SIGN_USE` 에 1회성 소비(노드 공유). 키는 **정규화 경로**(`canonicalPath`) 기준 — 원시 URI 를 쓰면 `%61ug` 류 인코딩 변형마다 키가 갈라져 같은 서명이 전부 신규로 통과한다(CWE-294). **서명 검증 성공 이후에만** 기록(pre-auth write DoS 차단). 하류가 5xx/예외로 실패하면 **nonce 예약을 해제**해 동일-바이트 재전송이 다시 처리되게 한다(결과 영구 유실 차단, CWE-754). 중복은 **409**(인증 실패 401 과 구분), 저장소 장애는 **503**(fail-closed + 재시도 유도) |
| timestamp | 과거 방향 `webhook.hmac.timestamp-window-seconds`(기본 300s), 미래 방향은 시계 오차 30s 만 |
| rate limit | **진실원은 `LS_WHK_FAIL_NMTM` 공유 집계**, JVM-local 카운터는 DB 왕복을 줄이는 **캐시**(별도 임계가 아님). 실패는 **차단에 이르기까지 매번 공유에 기록**하고, 차단 이후에는 기록하지 않는다(pre-auth write 를 IP·창당 5회로 상한). `isLimited` 는 로컬 미차단 시 **반드시 공유를 조회**(현재+직전 분 버킷)해 다른 노드의 차단을 승계·로컬 각인한다. → **노드 A 5회 차단 시 노드 B 도 즉시 차단**(A-ISSUE-14). 명시적 해제는 **서명 검증 성공(HMAC 경로)에서만** — 무서명 경로의 하류 2xx 는 해제 신호로 쓰지 않는다(이미 처리된 `request_id` 하나로 카운터를 영구 0 으로 만들 수 있었음). 그 외에는 분 단위 창 만료로 자연 소멸. 만료 행은 `WebhookGuardPurgeJob` 이 주기 정리(조건부 DELETE 라 2노드 동시 실행 무해). 공유 저장소 장애 시 **fail-open** |
| clientIp | `webhook.trusted-proxy-cidrs` 안에서 들어온 요청만 `X-Forwarded-For` 해석(우측부터 신뢰 홉 제거, IPv4/IPv6 리터럴만 허용). 미설정 시 XFF 전면 무시(CWE-348 위조 차단). **prd 는 명시 필수** — 프록시 대역 CIDR 또는 직접 노출을 뜻하는 `none`, 미설정 시 기동 차단(미설정 상태의 LB IP 단일 키 집계로 정상 콜백까지 429 되는 것을 방지). **CIDR 형식 오류(오타·호스트명)도 기동 차단** — 조용히 무시하면 matcher 가 비어 방어가 꺼진 채 기동한다. **배포 템플릿·`.env.example` 은 `none` 을 기본값으로 제공하지 않는다**(안전하지 않은 답을 기본값으로 건네지 않음) |
| 생성형 AI 콜백 | 명세서 v1.3 **무서명** 규격(v1.3 §3.1 Header 에 서명 헤더가 없고 §3.3 이 *"V0에는 인증 계층이 없어"* 를 명시 · 2026-07-27 계약 교체 — 구 `/v1/aug/callback` + HMAC 제거). ①IP allowlist(`webhook.genai.allowed-ip-cidrs`) — VLM 과 달리 **미설정이면 전면 차단(fail-closed)**, 열려면 대역 명시 ②rate limit·size cap(**1MB** — `results[]` 100건 × 1KB 정상 최대치의 약 10배 헤드룸. VLM 4MB 를 물려받으면 무인증 상태에서 필요치의 40배를 버퍼링) ③`request_id` 발급 게이트(`LS_DATA_AUG_JOB.IDMP_KEY`) **3계층**. 하류 401/403 도 rate limit 에 집계 |
| VLM 콜백 | 사업자 확정 계약(**KLID 연동 API v1.2.0**, 2026-09-07 — 구 표기 `v2.0.1` 은 대체된 규격이라 **폐기**) **무서명** 규격이라 HMAC 미적용. ⚠ v1.2.0 이 신설한 인증 헤더 **`X-API-Key` 는 우리가 나가는 방향(위탁 요청) 전용**이며 들어오는 콜백에는 걸리지 않는다. ①IP allowlist(`webhook.vlm.allowed-ip-cidrs`, **prd 명시 필수** — 미적용이면 `none`) ②rate limit·size cap(4MB) ③`request_id` 발급 게이트 **3계층**으로 보호. rate limit 은 필터 단계 실패뿐 아니라 **서비스 계층 인증 실패(미발급 `request_id` → 401)까지 집계**한다(위조 request_id 로 비관적 락 SELECT 를 무제한 유발하는 경로 차단). 단 **클라이언트 IP 를 발신자로 귀속할 수 없으면**(신뢰 프록시 미설정인데 XFF 관측 = 프록시 뒤 설정 누락) 하류 실패 집계를 **비활성**하고 WARN 만 남긴다 — 그 상태에서 집계하면 공격자 5회 실패가 정상 벤더 콜백 전건을 차단해 시계열 메타가 유실된다(가용성 우선) |
| 관측 | 인증 실패 시 `webhook.auth.failed{path,reason}` 카운터(Micrometer). `path` 태그는 `aug`/`vlm`/`other` **저카디널리티 상수** — 원시 URI 를 태그로 쓰면 공격자가 영구 보존 Meter 를 무한 생성한다(CWE-770) |

## 19.2 보안

기준: [`.claude/rules/security.md`](../../.claude/rules/security.md) (OWASP Top 10 2025, CWE Top 25, Fortify/CodeQL).

| 영역 | 정책 |
|------|------|
| 인증 | 관제/포털 JWT 인계(`JwtAuthenticationFilter`), 독립 로그인 없음, `alg:none` 금지 |
| 인가 | `@PreAuthorize` 역할 분기, IDOR 차단(`LabelAccessGuard`, 본인 배정 외 403). **`/v1/**` 포괄 매처는 채널(CHANNEL_INTERNAL) + 역할(REVIEWER/WORKER) 결합** — 역할 미배정(role=null) 사용자는 조회 API 도 403 (예외: `/v1/me`·`/v1/auth/**` 온보딩 경로, 서명 스트림 `STREAM_SIGNED`) |
| 입력 검증 | `@Valid`, 시스템 설정 화이트리스트 키(CWE-20), Mass Assignment 방지(DTO 분리) |
| SSRF (CWE-918) | 사용자 입력으로 URL 을 **구성**하지 않는다(경로는 상수). 연동 4종 주소만 운영 화면에서 **통째로 교체**할 수 있으며(R11) 그 값은 **스킴 allowlist + 형식** 검증만 거친다. ⚠ **IP 대역 차단은 하지 않는다**(2026-08-10 확정 — 대상이 내부망에 있을 수 있어 정당한 대상을 막게 된다). 망 통제는 **인프라 계층** 책임 → [§19.1 연동 서버 주소 설정](#연동-서버-주소-설정-r11-2026-08-10) |
| 자격증명 이전 (CWE-522) | 연동 주소를 바꿔도 **정적 토큰은 새 호스트로 따라가지 않는다** — 호스트가 배포 기본값과 다르면 인증 헤더를 떼고 WARN(값 미출력). 주소에 **userinfo(`user:pass@`)를 넣을 수 없다**(400 + 로그 마스킹 이중 방어) → [§19.1](#연동-서버-주소-설정-r11-2026-08-10) |
| 경로 순회 (CWE-22) | 비식별 출력 `STORAGE_DEIDENTIFIED_PATH` 하위 강제, `Path.normalize` |
| 정보 노출 (CWE-209) | 응답에 내부 파일 경로·스택트레이스 미포함 |
| 민감정보 (NFR-005) | PII·토큰 로그 출력 금지(Logback MaskingPatternLayout), 영상 암호화 저장, 통지 페이로드 PII 미포함 |
| 파일 업로드 | 확장자 allowlist + 크기 제한 + MIME 검증 |
| DoS (CWE-770) | RLE 변환 MAX_PIXELS 제한, 이미지 20MB 상한, diff 500건 상한 |

### 인증·인가 표면 (2026-07-25 개편 — 1차 검증 A/B 이슈 대응)

| 축 | 정책 |
|----|------|
| 권한 자가부여(`POST /v1/auth/role-claim`) | 자가부여 가능 역할은 **`ADMIN` 하나뿐**(`RoleClaimService.allowedClaimRoles()` → `List.of(BOOTSTRAP_ROLE)`)이고 이 창구는 **관리자가 0명일 때만** 열리는 부트스트랩 전용이다. 부여 역할이 고정이라 요청 바디의 `role` 은 결과를 바꾸지 못한다. 검증 순서는 **① 역할 화이트리스트 → ② 채널·기보유역할 → ③ rate limit → ④ 패스워드 → ⑤ upsert** (잘못된 role 시도가 정상 사용자 쿼터를 소모하지 않도록) |

> ⚠ **구 서술 폐기(2026-08-28 코드 실측)** — *"자가부여 가능 역할은 WORKER·REVIEWER 둘 다"* 는 낡은 정책이다. `ADR-055` 가 관리자 역할을 신설하면서 이 창구를 **관리자 부트스트랩 전용**으로 좁혔다 — 화이트리스트는 `ADMIN` 하나뿐이고, 관리자가 한 명이라도 생기면 창구가 닫히며(409), 그 뒤의 역할 부여는 관리자가 사용자 관리 화면에서 한다. 일반 사용자는 진입 시 **작업자로 자동 등록**된다. **되살리지 말 것** — WORKER·REVIEWER 를 화이트리스트에 되돌리면 관리자 공유 패스워드 하나로 다시 검수자가 되어, 역할 획득과 관리자 유효창이 같은 비밀에 매달리던 상태로 돌아간다(ADR-055 가 갈라놓은 바로 그 지점). ⚠ **목록 구조 자체는 유지**한다 — 값만 좁혔고, 상수 비교로 바꾸면 `Role` enum 확장 시 새 역할이 자동으로 자가부여 대상이 되는 것을 막는 방어가 사라진다. 구 서술이 근거로 들었던 2026-08-04 확정(V169, REVIEWER 자가부여 개방)은 **관리자 역할이 없던 시절의 것**이라 대체됐다 — 그때의 문제(신규 설치에 최초 부트스트랩 경로가 없음)는 관리자 부트스트랩이 대신 푼다. 상세·최신 정본은 → [03 §3.2](03-auth-roles.md#32-역할). 근거: `RoleClaimService.java(allowedClaimRoles · BOOTSTRAP_ROLE)` · `ADR-055`.
| 자가부여 rate limit | **계정 축 + 엔드포인트 전역 축**(`authoring.auth.role-claim.account-attempts-per-minute` 5 / `global-attempts-per-minute` 50)을 `LS_AUTHRT_GRANT_ATMPT` 공유 집계로 강제 → 무권한 계정 A/B/C 를 번갈아 쓰는 증폭·2노드 임계 2배를 함께 차단(CWE-307). 로컬 카운터는 Caffeine **TTL 10분 + maximumSize 1만** 으로 회수(구 `ConcurrentHashMap` 영구 잔존 = CWE-770). **저장소 장애 시에도 로컬 카운터가 임계를 강제**(완전 fail-open 금지). IP 축은 두지 않는다 — 신뢰 프록시 파싱 없이 IP 를 키에 넣으면 프록시 뒤 전 사용자가 한 IP 로 수렴해 한 명의 실패가 전원을 잠근다 |
| 영상 스트림 인가 | `/v1/videos/{rawSn}/stream`·`/stream-url` 진입부에서 `LabelAccessGuard.verifyRawAccess`(REVIEWER 전체 / WORKER 본인 배정) — 구 동작은 역할만 검사해 미배정 WORKER 가 타인 영상을 206 재생(CWE-639). `@Cacheable` 내부가 아니라 **진입부** 판정이라 캐시 히트가 인가를 건너뛰지 않는다 |
| 서명 스트림 URL | `sig = HMAC(secret, "{rawSn}.{exp}.{userNo}.{nonce}")`. `nonce` 는 발급 응답의 **HttpOnly·SameSite=Lax 쿠키**로만 전달되고 URL 에 없다 → **URL 만 유출된 제3자는 재생 불가**(CWE-294). 쿠키는 TTL 동안 재사용 가능(브라우저가 같은 URL 로 다수 Range 요청을 보내므로 1회용 소비는 재생을 깨뜨림). 발급 시 기존 쿠키가 있으면 값을 유지(다중 영상 동시 재생 보호). `StreamSignatureFilter` 는 principal 에 **실제 발급자 sub + 재조회 역할**을 채우되 `ROLE_*` authority 는 부여하지 않는다(서명 컨텍스트 확대 금지) |
| dev 토큰(`/v1/dev/tokens`) | 인증 없이 임의 `userNo`/`role` 토큰을 발급하는 경로. **local/dev 만 ON, stg 는 OFF**. prd 에서 `authoring.dev.login.enabled=true` 면 **부팅 거부**(`DevToggleProfileGuard` — WARN 로그가 아닌 설정 fail-fast). 빈 등록(`@ConditionalOnProperty`)과 SecurityConfig permitAll 매처가 **동일 프로퍼티 단일 판정** |
| 로그 마스킹 | local 프로파일 CONSOLE appender 를 `LayoutWrappingEncoder` + `MaskingPatternLayout` 으로 배선(구 설정은 Layout 을 `conversionRule converterClass` 로 잘못 등록해 **어떤 패턴도 참조하지 못하는 죽은 코드**였고 실제 출력은 평문 `%msg` — CWE-532). dev/stg/prd 는 JSON 인코더 `MaskingJsonValueMasker`. 검증은 로직 단위가 아니라 **appender 출력 종단**(`LocalLogMaskingIT`) |
| 프로파일 가드 | `LocalProfileGuard` — ENV=dev/stg/prd 인데 active profile 에 local 이 포함되면 부팅 거부(커밋된 개발용 기본값 운영 유입 차단, CWE-798). 판정 본체를 `verify(String envName)` 로 추출해 회귀 테스트 보유 |

## 19.3 CVAT 포팅

CVAT(Django+TS) → 본 프로젝트(Spring Boot+TS). 전체 fork 아닌 **9개 독립 모듈 Phase별 포팅**.

> ⚠ **구 서술 폐기(2026-08-19 실측)** — 분석 원본 `docs/analysis/portable-modules/01~09` 는 **저장소에 더 이상 존재하지 않는다**(`ls docs/analysis/` → `1cha-json-format-and-mapping.md`, `nia-v3.5-quality-to-review-mapping.md` 2개 파일만 남음). 아래 표의 "portable-modules/NN" 표기는 **코드 javadoc 이 여전히 인용하는 옛 문서 번호**를 참고용으로 남긴 것일 뿐, 해당 경로로 링크를 걸어도 파손된다. 또한 아래 표는 CLAUDE.md가 규정하는 9개 모듈 중 **3개가 통째로 빠져 있었다**(좌표 변환/회전 유틸·AI 함수 핸들러 템플릿·품질 충돌 감지) — 9개 전량으로 재구성한다. 근거: `reports/wiki-align-20260819/facts/F5-aiserver-common.md` §2.

| # | 모듈 | 구현 | 위치 | 배선 상태 |
|---|------|------|------|-----------|
| 01 | 트랙 보간 알고리즘 | `TrackInterpolator` (선형 보간) | `batch/interpolation/` → [11](11-ai-assisted.md) | **구현+배선됨** — `batch/step/TrackInterpolationStep` 실사용 |
| 02 | MASK ↔ RLE ↔ Polygon 변환 | `MaskRleConverter` (CWE-770 방어) | `common/util/` | 단위 구현만 — 자기 테스트 외 **프로덕션 호출부 0건**(export/오토라벨 경로에서 미사용) |
| 03 | TUS 재개 가능 업로드 | `TusUploadController`·`TusUploadService`·`TusChunkStore` | `upload/` → [05](05-video-management.md) | 구현됨. **단 1차 적재 경로 아님**(CLAUDE.md 확정 — 관제 학습용 설정 기반 적재로 대체, 폐지 예정) |
| 04 | manifest.jsonl 포맷 | `ManifestJsonlWriter` | `common/util/` | **구현+배선됨** — `batch/step/FfmpegFrameExtractor` 실사용 |
| 05 | AI 함수 핸들러 템플릿 | ai-server 라우터 구조(`app/routers/{yolo,sam2,vlm}.py`) | ai-server → [11](11-ai-assisted.md) | **구현됨**(ai-server 라우터 패턴으로 대체) |
| 06 | 좌표 변환/회전 유틸 | `CoordinateTransformer` | `common/util/` | 단위 구현만 — 자기 테스트 외 **프로덕션 호출부 0건** |
| 07 | YOLO/COCO 변환 | `YoloCocoConverter`, `CocoJson` 등 | `common/util/` | 단위 구현만 — 자기 테스트 외 **프로덕션 호출부 0건** |
| 08 | RQ Worker → Quartz Job 매핑 | Quartz `@DisallowConcurrentExecution` 잡 13개(`ControlTrainingVideoScanJob`·`KpstDeidentPollJob`·`WorkLockSweepJob` 등) | → [07](07-batch-pipeline.md) | **구현+배선됨**(application.yml Quartz JDBC JobStore 등록) |
| 09 | 품질 충돌 감지(GT Job) | `QualityConflictDetector` | `common/util/` | **구현+배선됨** — `quality/service/QualityCheckService` 실사용 |

- 캔버스 드로잉 패턴(konva.js, CVAT `analysis/canvas-drawing.md` 기반, 9모듈 외 별도 포팅 항목): `frontend/src/features/label/canvas/*` → [10](10-labeling.md). `package.json` 에 konva/react-konva 실사용 확인.
- ⚠ **`common/util/TrackInterpolator.java` + `common/util/Keyframe.java` 는 위 01번과 동명의 별개 클래스이며 고아(dead code)다** — `grep -rn "authoring.common.util.TrackInterpolator" backend/src/main/java backend/src/test/java` 결과 0건, main/test 어디서도 참조되지 않는다. 실제 배선은 `batch/interpolation/TrackInterpolator` 쪽이다. 위키·코드 리뷰에서 이 이름으로 검색할 때 두 클래스를 혼동하지 말 것.
- `cvat/`는 `.gitignore` 참조용. 소스 직접 import 금지 — 분석 문서 기반 Java 재구현(단, 그 분석 문서 자체가 위와 같이 저장소에서 소실됐다).

## 19.4 설계 문서 카탈로그

> ⚠⚠ **구 서술 폐기(2026-08-19 실측) — 링크 경로 전량 파손 + 문서 동결 상태 반영 필요.** 아래 표의 `../design/R1-사용자요구사항정의서.md` 등 8개 링크는 **모두 깨져 있다.** `docs/design/` 은 2026-08-15 사용자 확정으로 **동결**됐고, R1~D9 이름의 CBD 산출물 11종(+백업)은 `docs/archive/frozen-20260815/design/`(파일명도 `KLID_AT_*.md` 로 개명)로 이관됐다. `docs/design/` 은 현재 **LogiCraft 구현 키트**(`docs/design/{도메인슬러그}-{DOMAIN-ID}/` — 예: `docs/design/사용자권한-DOMAIN-001/`)만 담고 있어, 위 옛 파일명으로는 그 디렉터리에서 아무것도 찾을 수 없다.
>
> **★이 카탈로그를 판정 근거로 쓰지 말 것** — 동결 사유가 "낡아서 판정에 끼어들면 안 되기 때문"(예: 이 산출물의 요구사항추적표 주1이 "비식별 옵션은 저작도구 전용 화면 없이 외부 솔루션에서 설정"이라 적고 있으나 실제로는 `frontend/src/features/sysconfig/components/DeidentConfigCard.tsx` 전용 화면이 존재)이며, 감사 결함 수정이 전부 끝나기 전까지는 개별 정정도 하지 않기로 확정됐다. **현재 설계 진실원은 LogiCraft ITEM + 로컬 구현 키트**다(프로젝트 CLAUDE.md 「Logicraft 구현 키트」 절 참조). 아래 표는 **과거 ID 체계를 참고하는 용도로만** 남기고, 링크만 실제 소재지로 갱신한다.

| 문서 | 내용 | ID 체계 | 현재 위치(동결) |
|------|------|---------|---------|
| R1 사용자요구사항정의서 (v1.17) | SFR 14건(06-03·07·08·09) + NFR 7건 | `RQ-SFR-NN-NN` | [`archive/frozen-20260815/design/KLID_AT_사용자요구사항정의서.md`](../archive/frozen-20260815/design/KLID_AT_사용자요구사항정의서.md) |
| R2 유스케이스명세서 | 10 서브시스템·9 액터·13 유스케이스 | `KLID-AT-SS/UC/ACT-*` | [`archive/frozen-20260815/design/KLID_AT_유스케이스명세서.md`](../archive/frozen-20260815/design/KLID_AT_유스케이스명세서.md) |
| R3 요구사항추적표 | SFR→UC→SC→CO 매핑 | - | [`archive/frozen-20260815/design/KLID_AT_요구사항추적표.md`](../archive/frozen-20260815/design/KLID_AT_요구사항추적표.md) |
| D1 클래스설계서 | 클래스 | `KLID-AT-CL-*` | [`archive/frozen-20260815/design/KLID_AT_클래스설계서.md`](../archive/frozen-20260815/design/KLID_AT_클래스설계서.md) |
| D2 사용자인터페이스설계서 | 화면 23개 | `KLID-AT-SC-*` | [`archive/frozen-20260815/design/KLID_AT_사용자인터페이스설계서.md`](../archive/frozen-20260815/design/KLID_AT_사용자인터페이스설계서.md) |
| D3 컴포넌트설계서 | 4 UCD별 컴포넌트 | `KLID-AT-CO-*` | [`archive/frozen-20260815/design/KLID_AT_컴포넌트설계서.md`](../archive/frozen-20260815/design/KLID_AT_컴포넌트설계서.md) |
| D8 엔티티관계모형설계서 | ERD | - | [`archive/frozen-20260815/design/KLID_AT_엔티티관계모형설계서.md`](../archive/frozen-20260815/design/KLID_AT_엔티티관계모형설계서.md) |
| D9 데이터베이스설계서 | 테이블 명세 | - | [`archive/frozen-20260815/design/KLID_AT_데이터베이스설계서.md`](../archive/frozen-20260815/design/KLID_AT_데이터베이스설계서.md) |

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
SFR-06-03(해상도 변경) · SFR-07-01~03(증강) · SFR-08-01~05(라벨링·정밀도·버전) · SFR-09-01~05(비식별). 상세 → [R1(동결, §19.4 경로 참조)](../archive/frozen-20260815/design/KLID_AT_사용자요구사항정의서.md).
