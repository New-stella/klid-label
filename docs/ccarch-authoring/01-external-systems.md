# 외부 시스템 ACTOR 노드 (role=SYSTEM)

> 본 도구가 **직접 호출하거나, 본 도구를 호출하는** 외부 시스템을 ACTOR로 모델링한다.
> 영상 수집·중계·1차 사업 이벤트 시스템·VMS 변환·망연계 등 영상 인입 경로의 외부 시스템은 본 도구 책임 외이므로 ACTOR 로 등록하지 않는다. 본 도구는 LS_DATA_RAW 에 PENDING 으로 적재된 영상부터 처리한다.
>
> **ai-server 는 본 도구 영역**이므로 본 파일이 아닌 `08-components.md`에서 컴포넌트로 다룬다.

## EXT-01. 관제서버 (Inbound API + DB 공유)

```json
{
  "type": "ACTOR",
  "title": "관제서버 (Control Server)",
  "content": "**관계**\n- Inbound (API): 본 도구를 호출 — JWT 발급 + 사용자 인계\n- DB 공유: 본 도구와 동일한 `klid_system` MariaDB 인스턴스에 접근. 학습데이터 외부 전달 시 관제서버가 DB 를 직접 조회한다 (본 도구는 API 를 노출하지 않음).\n\n**책임**\n- 본 도구의 내부 채널(INTERNAL) 사용자(REVIEWER/WORKER)에게 JWT(HS256) 발급\n- 사용자를 본 도구의 `/ingress` 경로로 redirect (토큰 인계)\n- 세션 만료 시 본 도구가 관제서버 로그인 페이지로 다시 redirect\n- 본 도구가 적재·검수 완료한 학습데이터를 공유 DB 에서 조회하여 외부 학습데이터 시스템으로 전달\n\n**본 도구 측 처리**\n- `JwtAuthenticationFilter`가 관제서버 발급 JWT 검증\n- `TokenClaims.channel='INTERNAL'`, `role` 클레임으로 권한 분기\n- 학습데이터 export 용 API/SPI 는 본 도구 측에서 노출하지 않음\n\n**상호작용 시나리오**: uc-jwt-ingress\n**환경변수**: `JWT_SECRET`, `JWT_ISSUER`",
  "attrs": {"role": "SYSTEM"},
  "_handle": "ext-control-server"
}
```

## EXT-02. 포털 서버 (Inbound — JWT 발급 + 사용자 인계)

```json
{
  "type": "ACTOR",
  "title": "포털 서버 (Portal Server)",
  "content": "**관계**: Inbound — 본 도구를 호출\n\n**책임**\n- 포털 자체 기능(회원가입·로그인·인증·마이페이지·탈퇴, 영상 업로드, 학습데이터 다운로드, 공지사항·FAQ·매뉴얼, 통계) 전부\n- 인증된 PORTAL_USER 를 본 도구의 `/portal` 경로로 인계 (JWT 발급 + redirect)\n\n**본 도구 측 처리**\n- 동일한 `JwtAuthenticationFilter` 로 검증 (단일 검증 로직)\n- `TokenClaims.channel='PORTAL'`, `role='PORTAL_USER'`\n- 채널 가드 + 역할 가드 (`ChannelGuard('PORTAL') + RoleGuard([PORTAL_USER])`)\n- 본 도구는 진입한 PORTAL_USER 에게 저작도구 기능(간편 라벨링)만 제공\n\n**상호작용 시나리오**: uc-jwt-ingress, uc-portal-label",
  "attrs": {"role": "SYSTEM"},
  "_handle": "ext-portal-server"
}
```

## EXT-03. Deidentify 솔루션 (Outbound — 발주기관 SW 직접구매)

```json
{
  "type": "ACTOR",
  "title": "영상 비식별화 솔루션 (Deidentify SW)",
  "content": "**관계**: Outbound — 본 도구가 호출\n\n**도입 형태**: 발주기관 SW 직접구매 (분리발주, 본 사업 책임 아님)\n\n**연동 방식**: 비동기 — 본 도구가 작업 등록 후 결과를 별도 채널로 수신 (큐/콜백/폴링은 운영 결정)\n\n**본 도구 측 호출자**\n- `DeidentifyClient` (Spring Boot, 비동기 워커)\n- 요청 ID 기반 idempotency, dead-letter, 재등록 큐\n- 처리 실패·미응답 시 `LS_DATA_RAW.DE_IDNTF_YN='F'` 마킹 + 재등록 큐 (원본 절대 삭제 금지)\n- 비식별 옵션은 본 도구 UI(REVIEWER용)로 노출\n\n**호출 정책**: 수집된 모든 영상에 대해 비식별 작업을 위탁한다. 원본 영상과 비식별본을 별도 경로로 동시 저장하며, 분기·예외 없음.\n\n**환경변수**: `DEIDENTIFY_API_URL`\n\n**상호작용 시나리오**: uc-deidentify, uc-deident-review",
  "attrs": {"role": "SYSTEM"},
  "_handle": "ext-deidentify-sw"
}
```

## EXT-04. Gitea (Outbound — 버전 저장소)

```json
{
  "type": "ACTOR",
  "title": "Gitea (Git 버전관리 서버)",
  "content": "**관계**: Outbound — 본 도구가 호출\n\n**역할**: 라벨 저장 이벤트마다 본 도구가 라벨 JSON 을 동기 커밋하여 변경 이력 보존 + diff/롤백 제공\n**연동 방식**: 동기 REST — 본 도구가 외부 Gitea Contents API 즉시 호출 후 커밋 hash 응답 수신 (외부 연동 중 본 인터페이스만 동기, 비식별/VLM/생성형 AI 는 비동기)\n\n**본 도구 측 호출자**\n- `GiteaClient` (Spring Boot, WebClient)\n- Contents API: PUT/GET/DELETE `/repos/{owner}/{repo}/contents/{path}`\n- timeout 70s + CircuitBreaker(failure-rate 50%, window 10, minCalls 5, wait 30s) + Retry max=3+exp backoff\n- stg/prd 에서 fallback 큐 활성화 (외부 Gitea 장애 시에만 비동기 대응)\n- 커밋 메시지: 한글 + frame·변화 카운트 enrichment\n- 커밋 hash 는 `LS_DATA_LBL_HSTRY` 에 저장\n\n**환경변수**: `GITEA_BASE_URL`, `GITEA_TOKEN`, `GITEA_OWNER`, `GITEA_REPO`\n\n**상호작용 시나리오**: uc-label-commit, uc-version-diff-rollback",
  "attrs": {"role": "SYSTEM"},
  "_handle": "ext-gitea"
}
```

## EXT-05. 외부 생성형 AI 시스템 (Inbound — 증강 결과 인계)

```json
{
  "type": "ACTOR",
  "title": "외부 생성형 AI 시스템 (Generative AI System)",
  "content": "**관계**: Inbound — 본 도구로 결과 인계\n\n**책임 경계**: 본체(QWEN IMAGE, WAN2.2, Text-to-Image/Image-to-Image/Text-to-Video 모델 학습·운영·프롬프트 UI)는 외부 시스템 책임. 본 도구는 결과 영상·이미지를 수신해 검수/라벨링한다.\n**연동 방식**: 비동기 — 본 도구가 증강 요청 등록 후 외부가 결과 메시지를 본 도구의 inbound SPI 로 push\n\n**입력 (본 도구 → 외부 비동기 요청)**: 요청 ID + 영상 ID + 증강 타입(WINTER/NIGHT/RAIN/RESOLUTION) + 라벨 메타\n**출력 (외부 → 본 도구 비동기 결과)**: 요청 ID + 증강 영상·이미지 + 원본↔증강 라벨 매핑\n\n**본 도구 측 처리**\n- `AugmentService` → 증강 요청 비동기 등록 (검수 완료 영상만 허용)\n- if-augment-result-handover 로 결과 수신 → `LS_DATA_AUG` 적재\n- 요청 ID 기반 idempotency\n- 라벨 무결성(`LS_DATA_AUG_LBL_MAP`) 검증\n- REVIEWER 가 결과 검수 → ACCEPTED/REJECTED\n\n**상호작용 시나리오**: uc-augment-flow",
  "attrs": {"role": "SYSTEM"},
  "_handle": "ext-generative-ai"
}
```

## EXT-06. 외부 VLM 서비스 (Outbound — 시계열 분석)

```json
{
  "type": "ACTOR",
  "title": "외부 VLM 서비스 (External VLM Service for Time-series Analysis)",
  "content": "**관계**: Outbound — 본 도구가 호출\n\n**책임 경계**: VLM 모델 본체는 외부 서비스. 본 도구는 시계열 메타 생성이 필요한 시점에 외부 VLM 서비스에 비동기 작업을 위탁한다.\n**연동 방식**: 비동기 — 본 도구가 분석 작업 등록 후 결과를 별도 채널로 수신 (큐/콜백/폴링은 운영 결정)\n\n**호출 시점·범위**\n- 시계열 메타(프레임 시퀀스 단위 자연어 설명·객체/행동 변화·환경 조건 변화)만 외부 VLM 위탁 대상\n- 영상 단위 일반 메타는 본 도구 책임 외이므로 본 위탁 대상 아님\n- 객체 단위 정합성 검증 같은 프레임 단위 VLM 추론은 본 도구 책임 범위에서 다루지 않는다\n\n**본 도구 측 처리**\n- `VlmClient` (Spring Boot, 비동기 워커)\n- 요청 ID 기반 idempotency, dead-letter, 재등록 큐\n- 외부 VLM 서비스의 결과 메시지를 `LS_DATA_META` 에 적재 (META_TYPE_CD='VLM')\n- `LS_DATA_META_REVIEW` 생성 (RVW_STTS_CD='AUTO_GENERATED' 또는 'PENDING')\n- REVIEWER 가 GET/PUT `/v1/frames/{srcSn}/meta` 로 검토·승인·반려\n\n**상호작용 시나리오**: uc-vlm-timeseries-request, uc-meta-review",
  "attrs": {"role": "SYSTEM"},
  "_handle": "ext-vlm-service"
}
```

---

## 등록 순서

외부 시스템 ACTOR 는 USECASE 보다 먼저 등록한다.

1. ext-control-server
2. ext-portal-server
3. ext-deidentify-sw
4. ext-gitea
5. ext-generative-ai
6. ext-vlm-service

> **ai-server 는 본 도구 영역**이므로 외부 ACTOR 가 아닌 `08-components.md`에서 COMPONENT 로 다룬다.
> **외부 학습데이터 시스템·VMS·망연계·중계서버·1차 이벤트 시스템·생성형 AI 본체 등은 ACTOR 로 등록하지 않는다** — 본 도구가 직접 호출하거나 호출받는 인터페이스가 없거나, 영상 인입 경로처럼 본 도구 책임 외 영역이다.
