# INTERFACE 노드 정의 — 외부 시스템과의 비동기 계약

> ccarch INTERFACE 정의: "**컴포넌트가 외부에 제공하는 계약(API/이벤트/SPI)**".
>
> 본 도구 내부 호출(FE ↔ BE, BE ↔ ai-server)은 INTERFACE 노드로 등록하지 않는다 — 본 도구의 구현 세부사항이며 COMPONENT 간 `DEPENDS_ON` 관계로 충분히 표현된다.
> 세션·JWT 인계는 storage(쿠키/localStorage/url) 공유 방식이므로 별도 API endpoint 가 아니며 INTERFACE 노드로 등록하지 않는다.
>
> **외부 시스템 연동은 원칙적으로 비동기**로 처리한다 — 작업 등록 + 결과 수신 채널(큐/콜백/폴링은 운영 환경에서 결정)로 분리. **단 Gitea 라벨 커밋은 예외로 동기 REST**(timeout + CircuitBreaker + Retry, 장애 시 fallback 큐). 결과적으로 본 폴더는 **외부 시스템과의 계약 5개**를 INTERFACE 노드로 등록한다 — 동기 1(Gitea) + 비동기 4(비식별/VLM/생성형 AI/관제 완료·수정 통지).
>
> **V1.8 신규**: `if-control-notify-spi` — 저작도구 → 관제서버 단방향 outbound 완료/수정 통지(영상 단위). 양방향 M2M 통합은 여전히 deprecated.

## I-01. if-augment-result-handover (Inbound 결과 인계)

```json
{
  "type": "INTERFACE",
  "title": "외부 생성형 AI 증강 결과 인계 (Inbound 비동기)",
  "content": "**소유자**: 본 도구 (Inbound SPI)\n**연동 방식**: 비동기 — 외부 생성형 AI 시스템(ext-generative-ai)이 본 도구에 결과 메시지를 비동기 push (이벤트 또는 콜백)\n\n**흐름**\n1. 본 도구가 ext-generative-ai 에 증강 요청을 비동기 등록 (요청 ID 생성)\n2. 외부 시스템이 처리 완료 시 본 인터페이스로 결과 인계\n3. 본 도구는 요청 ID 로 매핑하여 `LS_DATA_AUG` 적재 → 검수 큐 진입\n\n**페이로드**\n- 요청 ID (idempotency 키)\n- 증강 결과 영상·이미지 (또는 저장소 위치)\n- 원본↔증강 라벨 매핑 (라벨 무결성)\n- 증강 메타 (augType: WINTER/NIGHT/RAIN/RESOLUTION, 원본 영상 ID)\n\n**신뢰성**\n- 동일 요청 ID 재인계 시 idempotency 처리\n- 비정상 페이로드는 dead-letter 영역에 보관 후 알림\n\n**보안**: 인계 토큰 또는 IP 화이트리스트로 보호 (운영 환경 결정)\n**protocol**: EVENT (transport·큐·콜백 구체 방식은 운영 결정)",
  "attrs": {
    "protocol": "EVENT",
    "interfaceNo": "if-augment-result-handover",
    "senderSystem": "ext-generative-ai",
    "receiverSystem": "authoring-tool (Backend)",
    "direction": "Inbound"
  },
  "_handle": "if-augment-result-handover"
}
```

## I-02. if-deidentify-spi (Outbound 비동기 작업 위탁)

```json
{
  "type": "INTERFACE",
  "title": "Deidentify 비동기 작업 위탁 계약",
  "content": "**소유자**: 외부 시스템 (ext-deidentify-sw) — 발주기관 SW 직접구매\n**본 워크스페이스 등록 사유**: ccarch 의 `DEPENDS_ON` 관계가 ACTOR 를 target 으로 허용하지 않아, 본 도구의 외부 의존성 추적성을 위해 본 워크스페이스에 등록한다.\n**연동 방식**: 비동기 — 본 도구가 작업 등록 후 결과를 별도 채널로 수신\n\n**흐름**\n1. 본 도구가 비식별 작업을 외부 솔루션에 비동기 등록 (영상·옵션 + 요청 ID)\n2. 외부 솔루션이 처리 완료 시 결과 메시지를 본 도구에 통지 (큐/콜백/폴링 — 운영 결정)\n3. 본 도구는 비식별본 경로 + 처리 영역 좌표를 `LS_DEIDENT_REPORT` 에 기록\n\n**호출 정책**: 모든 영상에 대해 무조건 위탁\n**신뢰성**\n- 요청 ID 기반 idempotency\n- 처리 실패·미응답 시 `LS_DATA_RAW.DE_IDNTF_YN='F'` 마킹 + 재등록 큐 (원본 절대 삭제 금지)\n- 비동기 재시도 정책 (운영 결정)\n\n**본 도구 측 호출자**: `DeidentifyClient` (comp-deidentify-client)\n**환경변수**: `DEIDENTIFY_API_URL` (또는 큐/메시지 브로커 주소)\n**protocol**: EVENT (작업 위탁 메시지 + 결과 수신 메시지)",
  "attrs": {
    "protocol": "EVENT",
    "interfaceNo": "if-deidentify-spi",
    "senderSystem": "authoring-tool (DeidentifyClient)",
    "receiverSystem": "ext-deidentify-sw",
    "direction": "Outbound"
  },
  "_handle": "if-deidentify-spi"
}
```

## I-03. if-gitea-contents (Outbound 동기 커밋)

```json
{
  "type": "INTERFACE",
  "title": "Gitea Contents API (PUT/GET/DELETE /repos/{owner}/{repo}/contents/{path})",
  "content": "**소유자**: 외부 시스템 (ext-gitea)\n**본 워크스페이스 등록 사유**: ccarch 의 `DEPENDS_ON` 관계가 ACTOR 를 target 으로 허용하지 않아, 본 도구의 외부 의존성 추적성을 위해 본 워크스페이스에 등록한다.\n**연동 방식**: 동기 REST — 라벨 저장 시 본 도구가 즉시 외부 Gitea 에 커밋 후 hash 응답을 받아 후속 처리. (외부 시스템 연동 중 본 인터페이스만 동기 처리, 나머지 비식별/VLM/생성형 AI 는 모두 비동기)\n\n**흐름**\n1. WORKER 가 라벨 저장 → BE 가 `LS_DATA_LBL` upsert\n2. `GiteaClient` 가 외부 Gitea Contents API 동기 호출 (PUT/GET/DELETE `/repos/{owner}/{repo}/contents/{path}`)\n3. 커밋 hash 수신 후 `LS_DATA_LBL_HSTRY` 에 즉시 기록\n4. FE 응답에 커밋 결과 포함 → VERSION_KEYS / TASK_BOARD_KEYS invalidate\n\n**메시지**: 한글 커밋 메시지 + frame·변화 카운트 enrichment\n**신뢰성**\n- 동기 호출: timeout 70s + CircuitBreaker(failure-rate 50%, window 10, minCalls 5, wait 30s) + Retry max=3 + exp backoff\n- 외부 Gitea 장애 시 fallback 큐에 적재 후 재시도 (stg/prd 에서 활성화) — 정상 흐름은 동기, 장애 대응만 큐\n- CircuitBreaker open 시 사용자에게 안내, 라벨은 DB 우선 저장됨\n\n**본 도구 측 호출자**: `GiteaClient` (comp-gitea-client)\n**환경변수**: `GITEA_BASE_URL`, `GITEA_TOKEN`, `GITEA_OWNER`, `GITEA_REPO`\n**protocol**: REST (동기)",
  "attrs": {
    "protocol": "REST",
    "interfaceNo": "if-gitea-contents",
    "senderSystem": "authoring-tool (GiteaClient)",
    "receiverSystem": "ext-gitea",
    "direction": "Outbound"
  },
  "_handle": "if-gitea-contents"
}
```

## I-05. if-control-notify-spi (Outbound 비동기 작업 완료/수정 통지) — V1.8 신규

```json
{
  "type": "INTERFACE",
  "title": "관제서버 작업 완료/수정 통지 SPI (Outbound 비동기, 영상 단위)",
  "content": "**소유자**: 외부 시스템 (ext-control-server)\n**본 워크스페이스 등록 사유**: ccarch 의 `DEPENDS_ON` 관계가 ACTOR 를 target 으로 허용하지 않아, 본 도구의 외부 의존성 추적성을 위해 본 워크스페이스에 등록한다.\n**연동 방식**: 비동기 — 본 도구가 영상 단위 작업의 완료·수정 이벤트를 관제서버에 push (큐/콜백/HTTP push 는 운영 결정)\n\n**범위**\n- 작업 단위 = 영상 1건 (`LS_DATA_RAW.RAW_SN` = 작업 ID). 프로젝트 단위 개념 사용 안 함.\n- 통지 단위 = 영상 1건. 라벨/이미지/프레임 1장 단위 통지 금지.\n\n**이벤트 타입**\n- `TASK_COMPLETED` — REVIEWER 가 검수 APPROVED → `LsRawDataStatus.dataSttsCd='COMPLETED'` 전이 시 1회 발행\n- `TASK_MODIFIED` — COMPLETED 상태 영상의 라벨/메타 수정 시 발행 (동일 작업 ID 유지, 버전 업 없음)\n\n**디바운스**: 같은 작업 ID 의 다중 수정은 운영 결정 윈도우(기본 60s) 동안 1회로 통합\n\n**페이로드 (최소)**\n- 이벤트 타입 (TASK_COMPLETED | TASK_MODIFIED)\n- 작업 ID (RAW_SN — 영상 단위)\n- 영상 메타: 파일명, 길이, 채널 등\n- 검수 완료 일시 (TASK_COMPLETED) / 마지막 수정 일시 (TASK_MODIFIED)\n- 변경 요약 카운트: 라벨 N건, 메타 M건\n- 요청 ID (idempotency 키)\n- 본 도구 송신 일시\n\n**페이로드에 포함하지 않음**: 라벨 본문, 메타 본문, PII, 토큰. 관제서버가 본문이 필요하면 본 도구 API(`/v1/versions/{commit}/diff` 등) 또는 공유 DB·Gitea 조회로 보강.\n\n**신뢰성**\n- 요청 ID 기반 idempotency (동일 이벤트 재송 시 수신측 무시 또는 본 도구 송신 억제)\n- Resilience4j: timeout + Retry max=3 + exp backoff + CircuitBreaker\n- 송신 실패·미응답 시 dead-letter + 재등록 큐 (stg/prd 활성화)\n- 송신 이력 감사 로그 (운영 시점에 신규 또는 LS_BATCH_PROC_LOG 재사용)\n\n**보안**\n- 단방향 outbound 만. 양방향 M2M 인증 인프라는 부활하지 않음 (CLAUDE.md 인증·진입 정책 유지)\n- 인계 토큰 또는 IP 화이트리스트로 보호 (운영 결정)\n\n**본 도구 측 호출자**: `ControlNotifyClient` (comp-control-notify-client)\n**환경변수**: `CONTROL_NOTIFY_URL` (또는 큐/메시지 브로커 주소 — 운영 결정), 인계 토큰\n**protocol**: EVENT (push 메시지)",
  "attrs": {
    "protocol": "EVENT",
    "interfaceNo": "if-control-notify-spi",
    "senderSystem": "authoring-tool (ControlNotifyClient)",
    "receiverSystem": "ext-control-server",
    "direction": "Outbound"
  },
  "_handle": "if-control-notify-spi"
}
```

## I-04. if-vlm-timeseries-spi (Outbound 비동기 시계열 분석 위탁)

```json
{
  "type": "INTERFACE",
  "title": "외부 VLM 시계열 분석 비동기 위탁 계약",
  "content": "**소유자**: 외부 시스템 (ext-vlm-service)\n**본 워크스페이스 등록 사유**: ccarch 의 `DEPENDS_ON` 관계가 ACTOR 를 target 으로 허용하지 않아, 본 도구의 외부 의존성 추적성을 위해 본 워크스페이스에 등록한다.\n**연동 방식**: 비동기 — 본 도구가 시계열 분석 작업을 위탁 등록 후 결과를 별도 채널로 수신\n\n**흐름**\n1. 본 도구의 BATCH_SYSTEM (VlmTimeseriesStep) 이 영상·프레임 시퀀스에 대한 분석 작업을 외부 VLM 서비스에 비동기 등록 (요청 ID + 분석 옵션)\n2. 외부 VLM 서비스가 처리 완료 시 시계열 메타 결과 메시지를 본 도구에 통지 (큐/콜백/폴링 — 운영 결정)\n3. 본 도구가 응답을 `LS_DATA_META`(META_TYPE_CD='VLM') 적재 + `LS_DATA_META_REVIEW` 검토 큐 진입 (RVW_STTS_CD='AUTO_GENERATED' 또는 'PENDING')\n\n**대상 외**: 영상 단위 일반 메타·객체 단위 정합성 검증은 위탁 대상 아님\n\n**페이로드 (결과)**: 시계열 메타 (자연어 설명·객체·환경 K/V 페어)\n**신뢰성**\n- 요청 ID 기반 idempotency\n- 처리 실패·미응답 시 재등록 큐\n- 비동기 재시도 정책 (운영 결정)\n\n**본 도구 측 호출자**: `VlmClient` (comp-vlm-client)\n**protocol**: EVENT (작업 위탁 메시지 + 결과 수신 메시지)",
  "attrs": {
    "protocol": "EVENT",
    "interfaceNo": "if-vlm-timeseries-spi",
    "senderSystem": "authoring-tool (VlmClient)",
    "receiverSystem": "ext-vlm-service",
    "direction": "Outbound"
  },
  "_handle": "if-vlm-timeseries-spi"
}
```

---

## 공통: 외부 연동 패턴 요약

| INTERFACE | 패턴 | protocol | 주요 정책 |
|---|---|---|---|
| if-augment-result-handover | 비동기 (Inbound 결과 인계) | EVENT | 요청 ID idempotency, dead-letter |
| if-deidentify-spi | 비동기 (Outbound 작업 위탁) | EVENT | 요청 ID idempotency, 재등록 큐, 모든 영상 무조건 위탁 |
| if-vlm-timeseries-spi | 비동기 (Outbound 작업 위탁) | EVENT | 요청 ID idempotency, 재등록 큐 |
| **if-gitea-contents** | **동기 REST** | REST | timeout 70s + CircuitBreaker 50%/window10 + Retry max=3+exp backoff, 장애 시 fallback 큐(stg/prd) |
| **if-control-notify-spi** (V1.8) | **비동기 (Outbound 통지)** | EVENT | 영상 단위 TASK_COMPLETED/TASK_MODIFIED, 동일 작업 ID 유지, 디바운스 60s, idempotency + dead-letter + 재등록 큐 |

비동기 계약은 큐 처리 워커·idempotency·재등록 정책으로 신뢰성을 확보한다. Gitea 동기 호출은 라벨 저장 후 커밋 hash 즉시 응답이 필요한 흐름이라 예외적으로 동기로 유지하되, 장애 대응 큐만 비동기 적용된다.

## 등록되지 않는 인터페이스 (참고)

다음은 본 도구가 사용하지만 ccarch INTERFACE 노드로 등록하지 않는다.

| 인터페이스 | 등록 안 하는 이유 |
|---|---|
| 본 도구 내부 REST API (`/v1/assignments`, `/v1/tasks/board`, `/v1/frames/{srcSn}/labels`, `/v1/reviews/*`, `/v1/versions/*`, `/v1/augments`, `/v1/frames/{srcSn}/meta`, `/v1/presets`, `/portal/labels`) | FE → BE 본 도구 내부 호출. 구현 세부사항이며 COMPONENT 간 `DEPENDS_ON` 으로 충분히 표현 |
| BE → ai-server 호출 (`POST /infer/yolo/*`, `POST /infer/sam2/*`) | 본 도구 영역 내 두 마이크로서비스 간 호출. `comp-ai-server-client → DEPENDS_ON → comp-ai-server` 로 표현 |
| JWT 인계 (관제/포털 → 본 도구) | storage(쿠키/localStorage/url) 공유 방식. 별도 endpoint 가 아니며 `comp-jwt-filter` COMPONENT 의 동작 |
| 학습데이터 export | 관제서버가 klid_system 공유 DB 를 직접 조회. 본 도구가 노출하는 API 없음 |

## 등록 순서

ENTITY/COMPONENT 등록 후 INTERFACE 등록.

1. if-augment-result-handover (Inbound 비동기 — 본 도구 소유)
2. if-deidentify-spi (Outbound 비동기 — 외부 소유)
3. if-gitea-contents (Outbound 동기 — 외부 소유)
4. if-vlm-timeseries-spi (Outbound 비동기 — 외부 소유)
5. if-control-notify-spi (Outbound 비동기 — 외부 소유, V1.8 신규)
