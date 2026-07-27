# VLM 목업 배선 활성화 결과 — 2026-07-25 1차

## 변경 전 설정
- `.env` (37): `VLM_CLIENT_ENABLED=false`
- `VLM_SERVICE_URL` 미설정 → `application.yml` 기본값 `vlm.client.url=http://localhost:9400` 사용
  (backend 컨테이너 관점에서 `localhost`는 mock-server 가 아닌 자기 자신 — 어차피 도달 불가)
- `vlm.client.timeout-seconds=10` (application.yml:384, ★참고: 임무 설명의 "45s"는 KPST 비식별
  폴링 타임아웃과 혼동으로 보임 — VLM describe 는 코드상 10s)
- 콜백 경로: `POST /v1/vlm/callback` (webhook.hmac.secret 대상에서 제외됨, application.yml:395~396)

## 변경한 설정 (파일:라인, before → after)
`/Users/ck/Documents/workspace/klid/klid-label/.env` (37~38)
```
- VLM_CLIENT_ENABLED=false
+ VLM_CLIENT_ENABLED=true
+ VLM_SERVICE_URL=http://klid-mock-server:9400
```
(docker-compose 로컬 override의 KPST 예시와 동일 패턴 — mock-server 는 `klid-net` 브리지 네트워크에
`klid-mock-server` 호스트명(172.18.0.4)으로 존재)

**결과: 부트 실패 확인 후 원복.** 최종 `.env` 상태는 `VLM_CLIENT_ENABLED=false` (원복 + 원인 주석 추가).

## mock-server VLM 엔드포인트 3종 (경로·메서드·스키마)
파일: `mock-server/app/routers/vlm.py`, `mock-server/app/schemas/vlm.py`

| 메서드 | 경로 | 요청 스키마 | 응답 |
|---|---|---|---|
| POST | `/v1/videovlm/verify` | `VerifyRequest{request_id?, event_type(enum), media{type,source_type,path,frame_policy}, callback_url}` | 즉시 `{request_id, status:"accepted"}`, 지연 후 callback_url 로 verify 결과 콜백 |
| POST | `/v1/videovlm/describe` | `DescribeRequest{request_id?, media{...}, callback_url}` (event_type 없음) | 즉시 `{request_id, status:"accepted"}`, 지연 후 describe 결과 콜백 |
| GET  | `/v1/videovlm/status` | 없음 | `{"status":"ok","service":"videovlm"}` |

- `request_id` 누락/공백이면 서버가 UUID 방어 발급(echo 일관성).
- 실패 트리거: `request_id` 가 "fail"로 시작 or `media.path` 에 "fail" 포함 → 동기 응답은 accepted 유지, 콜백만 `status=failed`.
- 콜백 지연은 `callback_delay_seconds` 설정값(BackgroundTasks).

## backend VlmClient 호출 경로 (file:line) — mock 과 일치 여부
- `backend/src/main/java/kr/co/cudo/authoring/common/client/VlmClient.java:53` — `DESCRIBE_PATH = "/v1/videovlm/describe"`
- `VlmClient.java:100-102` — `webClient.post().uri(DESCRIBE_PATH).bodyValue(enriched)...`
- 요청 바디 `VlmTimeseriesRequest`(`backend/.../dto/VlmTimeseriesRequest.java:38-59`)의 필드
  (`request_id`, `media.type/source_type/path/frame_policy.mode/framerate`, `callback_url`)는
  mock의 `DescribeRequest`/`Media`/`FramePolicy` 스키마와 **1:1 일치**. 응답 `VlmTimeseriesResponse`도
  `request_id` echo + `status` 검증(`VlmClient.java:154-169`)이 mock의 `AcceptedResponse`와 일치.
- **verify** (`/v1/videovlm/verify`) 경로는 backend `VlmClient`에 호출 코드가 없음 — `VlmVerifyRequest`/
  `VlmVerifyResponse` DTO(`common/client/dto/VlmVerifyRequest.java`, `VlmVerifyResponse.java`)만 존재하고
  실제 호출부(WebClient 발신)가 없다. **describe 경로만 실제 배선됨** (계약 불일치는 아니고 미구현 상태).
- 결론: describe 경로는 mock과 완전 일치. verify 경로는 BE 쪽에 호출 트리거 자체가 없어 검증 대상 자체가 아님.

## ★ 실증 결과

### 유발 방법(실행한 명령)
```
# .env 수정: VLM_CLIENT_ENABLED=true, VLM_SERVICE_URL=http://klid-mock-server:9400
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d klid-backend
```

### backend 로그 (부트 시점)
```
10:35:30.997 WARN  k.c.c.a.c.config.KpstWebClientConfig - [Kpst] 평문 HTTP 전송 — 내부망 격리 전제. baseUrl scheme=http host=klid-mock-server:9400
10:35:31.152 WARN  o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization ...
  Error creating bean with name 'vlmClient' ... Error creating bean with name 'vlmWebClient' ...
  Factory method 'vlmWebClient' threw exception with message:
  vlm.client.url 은 HTTPS 스키마만 허용됩니다 (현재: http). CWE-319 cleartext 차단.
10:35:31.183 ERROR o.s.boot.SpringApplication - Application run failed
Caused by: java.lang.IllegalStateException: vlm.client.url 은 HTTPS 스키마만 허용됩니다 (현재: http). CWE-319 cleartext 차단.
	at kr.co.cudo.authoring.common.config.WebClientConfig.validateExternalUrl(WebClientConfig.java:130)
	at kr.co.cudo.authoring.common.config.WebClientConfig.vlmWebClient(WebClientConfig.java:69)
```
컨테이너는 `Restarting (1)` 크래시 루프로 진입(재부팅마다 동일 예외 반복).

### mock-server 인바운드 로그
없음. **backend 가 Spring 컨텍스트 초기화(빈 생성) 단계에서 실패해 애플리케이션이 아예 기동하지 못했으므로
HTTP 요청 자체가 발신되지 않았다.** mock-server 로그에 인바운드 요청이 찍힐 여지가 없었다(요청 발신 이전
단계에서 완전 차단).

### 콜백 수신 여부
확인 불가 (describe 요청 자체가 나가지 않아 mock이 콜백을 발사할 트리거가 없음).

### 판정: 목업 경유 확인 — **X**
로컬 설정(`VLM_CLIENT_ENABLED`/`VLM_SERVICE_URL`) 변경만으로는 mock-server 경유가 불가능함을
실측으로 확인. 원인은 아래 "발견된 결함" 참조.

## 발견된 결함
### [결함] VlmClient 전용 WebClient가 HTTPS-only + private-IP 차단을 강제해 로컬 mock-server 배선이 원천 불가능
- **심각도**: HIGH (로컬 목업 전수검증 구속조건 "로컬은 무조건 목업서버를 바라본다"를 VLM 경로만 위반 — 코드 수정 없이는 준수 불가능한 구조적 결함)
- **기대 동작**: KPST 비식별 클라이언트(`KpstWebClientConfig`)처럼 `enabled=true`일 때 http/https 스킴을
  모두 허용하고, http면 WARN 로그만 남기고 통과시켜(내부망/로컬 mock 전제) mock-server(`klid-mock-server:9400`,
  평문 HTTP, TLS 미지원)로 정상 연결되어야 한다.
- **현재 동작**: `WebClientConfig.vlmWebClient`(`backend/src/main/java/kr/co/cudo/authoring/common/config/WebClientConfig.java:63-76`)가
  `enabled=true`일 때 `validateExternalUrl()`(같은 파일 116-162)을 호출하며, 이 메서드가:
  1. **HTTPS 스킴만 허용** (WebClientConfig.java:129-132) — `http` 이면 즉시 `IllegalStateException`으로
     빈 생성 실패 → Spring 컨텍스트 부트 자체가 실패(크래시 루프).
  2. 설령 https로 우회해도 host가 loopback/site-local/link-local(사설 IP, 예: docker 브리지 172.16.0.0/12
     대역)이면 SSRF 차단으로 동일하게 부트 실패(WebClientConfig.java:150-155).
  - 반면 mock-server(`mock-server/Dockerfile:28`)는 `uvicorn app.main:app --host 0.0.0.0 --port 9400`으로
    **TLS 미지원**(plain HTTP 전용)이고, docker 브리지 네트워크상 `klid-mock-server` 호스트명은
    `172.18.0.4`(site-local)로 resolve됨(`docker exec klid-mock-server hostname -i` 실측) — 두 조건 모두
    구조적으로 통과 불가능.
- **재현 경로**: `.env`에 `VLM_CLIENT_ENABLED=true` + `VLM_SERVICE_URL=http://klid-mock-server:9400` 설정 →
  `docker compose ... up -d klid-backend` → 컨테이너가 `Restarting`으로 크래시 루프, 로그에 위 스택트레이스 반복.
- **영향**: VLM 관련 테스트케이스(describe 위탁 → mock 콜백 수신 전 구간)가 **로컬 목업 기준 실동작 검증 자체가
  불가능**. KPST(비식별)는 동일 목적을 위해 http+사설IP를 명시적으로 허용하는 `KpstWebClientConfig`가 있어
  정상 경유하는 것과 대비된다(두 클라이언트의 보안 정책이 불일치 — VLM만 dev/stg/prd 전용 강도의 검증을
  local에도 무조건 적용).
- **수정 방향(제안, 미실행)**: `WebClientConfig.vlmWebClient`의 SSRF/HTTPS 검증을 `KpstWebClientConfig`와
  동일하게 스킴 기반 분기(http 허용 + WARN, https는 기존 검증 유지)로 정정하거나, **local 프로파일에서만**
  검증을 완화하는 조건(`@Profile`/`Environment` 분기)을 추가한다. 이 결정은 코드 변경이 필요하므로 본 임무
  범위(설정만 변경) 밖 — 사용자/담당 개발 확인 필요.

## 재기동 후 스택 상태
`.env`를 원복(`VLM_CLIENT_ENABLED=false`)하고 `docker compose ... up -d klid-backend` 재기동 완료.
```
NAMES              STATUS
klid-backend       Up (healthy)
klid-postgres      Up 35 hours (healthy)
klid-frontend      Up 42 hours (healthy)
klid-ai-server     Up 3 days (healthy)
klid-mock-server   Up 3 days
```
전체 스택 정상 기동 상태로 복원됨. VLM은 기존과 동일하게 `enabled=false`(NO-OP, mock 미경유) 상태로 되돌아감.
