# COMPONENT 노드 정의

> ccarch type: `COMPONENT` / 필수: `title`, `content` / 옵션: `layer`(PRESENTATION|APPLICATION|DOMAIN|INFRA)
> Backend + ai-server + Frontend 내부 모듈 + 외부 호출 클라이언트. ai-server 는 본 도구 영역.

## C-01. BatchOrchestrator (Quartz 파이프라인 조정자)
```json
{
  "type": "COMPONENT",
  "title": "BatchOrchestrator (Quartz 파이프라인 조정자)",
  "content": "**책임**: 영상 자동 처리 파이프라인의 단계별 조정. Quartz Job 이 1건/분 PENDING 영상 선점 → 각 Step 순차 실행 → 상태 전이.\n\n**Step**\n- FrameExtractStep (FFmpeg → LS_DATA_SRC 적재)\n- DeidentifyStep (모든 영상 무조건 호출)\n- YoloAutolabelStep / Sam2SegmentStep (comp-ai-server 호출)\n- VlmTimeseriesStep (외부 VLM 서비스 호출)\n\n**의존**\n- comp-ai-server-client, comp-deidentify-client, comp-vlm-client\n- ent-data-raw, ent-batch-aux\n\n**프로파일**: local 비활성(수동 트리거) / dev·stg·prd 자동\n**구현 위치**: `backend/src/main/java/kr/co/cudo/authoring/batch/*`",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-batch-orchestrator"},
  "_handle": "comp-batch-orchestrator"
}
```

## C-02. DeidentifyClient (비식별 솔루션 어댑터)
```json
{
  "type": "COMPONENT",
  "title": "DeidentifyClient (비식별 솔루션 비동기 어댑터)",
  "content": "**책임**\n- 비식별 솔루션(ext-deidentify-sw) 에 비동기 작업 위탁 (if-deidentify-spi)\n- 모든 영상에 대해 무조건 위탁, 결과 수신 시 원본+비식별본 동시 저장\n- 비식별 처리 결과를 ent-batch-aux(LS_DEIDENT_REPORT) 에 기록\n- 요청 ID 기반 idempotency, dead-letter, 재등록 큐\n- 미응답/실패 시 DE_IDNTF_YN='F' 마킹 + 재등록 큐 (원본 절대 삭제 금지)\n\n**구현 위치**: `backend/.../common/client/DeidentifyClient.java`",
  "attrs": {"layer": "INFRA", "componentId": "comp-deidentify-client"},
  "_handle": "comp-deidentify-client"
}
```

## C-03. AiServer (FastAPI Stateless 추론 서버 — YOLO/SAM2)
```json
{
  "type": "COMPONENT",
  "title": "AiServer (FastAPI Stateless 추론 — YOLO/SAM2)",
  "content": "**책임**: Stateless 추론 — 인증/DB/상태 없음. Spring Boot 가 호출하는 본 도구 내부 마이크로서비스.\n\n**라우터**\n- POST `/infer/yolo/predict`, `/infer/yolo/track`\n- POST `/infer/sam2/segment`, `/infer/sam2/track`\n- GET `/health`\n\n**특이**\n- AI_MOCK_MODE=true 또는 가중치 미존재 시 mock 응답 (응답에 `mock=true`)\n- Lifespan 훅 startup 시 모델 워밍업\n- RequestIdMiddleware + CORS\n- VLM 시계열 분석은 본 컴포넌트에서 수행하지 않음 — 외부 VLM 서비스가 담당\n\n**구현 위치**: `ai-server/app/main.py` + `ai-server/app/routers/{yolo,sam2}.py`",
  "attrs": {"layer": "INFRA", "componentId": "comp-ai-server"},
  "_handle": "comp-ai-server"
}
```

## C-04. AiServerClient (Spring Boot → ai-server 호출)
```json
{
  "type": "COMPONENT",
  "title": "AiServerClient (Spring Boot 측 ai-server 호출 클라이언트)",
  "content": "**책임**\n- ai-server REST 호출 단일화 (predictYolo / predictYoloTrack / predictSam2Segment / predictSam2Track)\n- WebClient 32MB 버퍼 (이미지 페이로드 대응)\n- timeout 60s + CircuitBreaker + Retry\n- 응답 `mock=true` 감지 시 WARN 로그\n\n**구현 위치**: `backend/.../common/client/AiServerClient.java`",
  "attrs": {"layer": "INFRA", "componentId": "comp-ai-server-client"},
  "_handle": "comp-ai-server-client"
}
```

## C-05. VlmClient (외부 VLM 서비스 호출 어댑터 — 시계열)
```json
{
  "type": "COMPONENT",
  "title": "VlmClient (외부 VLM 서비스 비동기 어댑터 — 시계열 분석 한정)",
  "content": "**책임**\n- 외부 VLM 서비스(ext-vlm-service) 에 비동기 작업 위탁 (if-vlm-timeseries-spi) — 시계열 메타 생성용\n- 영상 단위 일반 메타는 위탁 대상 아님 (영상 인입 경로에서 외부가 적재)\n- 객체 단위 정합성 검증 같은 프레임 단위 VLM 위탁도 다루지 않음\n- 요청 ID 기반 idempotency, dead-letter, 재등록 큐\n- 결과 수신 시 LS_DATA_META(META_TYPE_CD='VLM') 적재 후 LS_DATA_META_REVIEW 검토 큐 진입\n\n**구현 위치**: `backend/.../common/client/VlmClient.java` (또는 동등)",
  "attrs": {"layer": "INFRA", "componentId": "comp-vlm-client"},
  "_handle": "comp-vlm-client"
}
```

## C-06. GiteaClient (버전 저장소 클라이언트)
```json
{
  "type": "COMPONENT",
  "title": "GiteaClient (Gitea Contents API 동기 클라이언트)",
  "content": "**책임**\n- 라벨 저장 이벤트마다 Gitea(ext-gitea) Contents API 동기 호출 (if-gitea-contents)\n- 외부 시스템 연동 중 본 클라이언트만 동기 처리 (나머지 비식별/VLM/생성형 AI 는 비동기)\n- 한글 커밋 메시지 + frame·변화 카운트 enrichment\n- timeout 70s + CircuitBreaker(50%/window10/minCalls5/wait30s) + Retry max=3+exp backoff\n- 정상 흐름은 동기, 외부 Gitea 장애 시에만 fallback 큐로 비동기 대응 (stg/prd 에서 활성화)\n- 커밋 hash 는 LS_DATA_LBL_HSTRY 에 저장\n\n**구현 위치**: `backend/.../common/client/GiteaClient.java`",
  "attrs": {"layer": "INFRA", "componentId": "comp-gitea-client"},
  "_handle": "comp-gitea-client"
}
```

## C-07. LabelCanvas (FE 라벨링 캔버스)
```json
{
  "type": "COMPONENT",
  "title": "LabelCanvas (React + Konva 캔버스)",
  "content": "**책임**: 라벨 시각화·편집 도구 패턴, 좌표·마스크 유틸, dirty 추적.\n\n**구성**\n- CanvasShell / ImageLayer / LabelsLayer / OverlayLayer\n- 도구 7종 (SELECT/BBOX/POLYGON/MASK_BRUSH/MASK_ERASER/SAM2_TRACK/PAN)\n- coordinateTransformer, polygonHelpers, maskRleConverter\n- useLabelStore (Zustand): activeTool, dirtyLabels, zoom/pan/undo\n- SaveCommitFlow → commit API\n\n**구현 위치**: `frontend/src/features/label/canvas/*`",
  "attrs": {"layer": "PRESENTATION", "componentId": "comp-label-canvas"},
  "_handle": "comp-label-canvas"
}
```

## C-08. LabelService (BE 라벨링 서비스)
```json
{
  "type": "COMPONENT",
  "title": "LabelService (라벨 CRUD + 검증)",
  "content": "**책임**\n- 라벨 일괄 upsert (LabelBulkUpsertRequest)\n- LabelAccessGuard 로 본인 배정만 접근 (CWE-639 IDOR 방어)\n- Mass Assignment 방어 (record DTO + autoLblYn 무시)\n- 저장 후 GiteaClient.commit 호출 (SaveCommitFlow)\n\n**구현 위치**: `backend/.../label/service/LabelService.java`\n**의존**: ent-data-lbl, ent-label-master, comp-gitea-client",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-label-service"},
  "_handle": "comp-label-service"
}
```

## C-09. AssignmentService + TaskBoardService (배정·작업 보드)
```json
{
  "type": "COMPONENT",
  "title": "AssignmentService + TaskBoardService (작업 배정·재배정 + 통합 보드)",
  "content": "**책임**\n- REVIEWER 권한 가드 (`@PreAuthorize` + `requireReviewer(actor)` 이중)\n- 배정 생성/조회/재배정\n- 완료 작업 재배정 차단 가드\n- TaskBoardService — 검수자용 통합 목록 (페이지당 추가 쿼리 ≤ 5건, frameCount batch GROUP BY)\n\n**의존**: ent-task-assignment, ent-raw-data-status\n**구현 위치**: backend assignment 도메인",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-assignment-service"},
  "_handle": "comp-assignment-service"
}
```

## C-10. ReviewService (검수 서비스)
```json
{
  "type": "COMPONENT",
  "title": "ReviewService (검수 워크플로우)",
  "content": "**책임**\n- 검수 목록 enrich (cctvName, workerName, labelCount, eventName 페이지당 lookup ≤ 5건)\n- 검수 상세 (frames, labels, issues)\n- 결정 처리 (APPROVED/REJECTED) + @Version 낙관적 잠금\n- reviewCompletedAt 기록\n- reviewStatusCd 필터 지원\n\n**의존**: ent-raw-data-status, ent-data-issue\n**구현 위치**: backend review 도메인",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-review-service"},
  "_handle": "comp-review-service"
}
```

## C-11. VersionService (라벨 버전 관리)
```json
{
  "type": "COMPONENT",
  "title": "VersionService (라벨 버전 관리)",
  "content": "**책임**\n- 라벨 저장 시 Gitea 자동 커밋 트리거 (comp-gitea-client 위임)\n- 작업 이력 조회 (LS_DATA_LBL_HSTRY)\n- 커밋 간 diff (라벨 단위 응답 형식 정합)\n- 롤백 지원\n\n**의존**: ent-label-version, comp-gitea-client\n**구현 위치**: backend version 도메인",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-version-service"},
  "_handle": "comp-version-service"
}
```

## C-12. AugmentService (증강 결과 검수)
```json
{
  "type": "COMPONENT",
  "title": "AugmentService + AugmentIntegrityCalculator + AugmentReviewService",
  "content": "**책임**\n- 증강 요청 외부 비동기 인계 (검수 완료 영상만 허용, 요청 ID 발급)\n- 외부 시스템(ext-generative-ai) 증강 결과 비동기 수신 (if-augment-result-handover)\n- 요청 ID 기반 idempotency 매핑\n- 증강 결과 검수 (accept/reject)\n- 라벨 무결성 계산 (LS_DATA_AUG_LBL_MAP)\n\n**의존**: ent-data-aug, if-augment-result-handover",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-augment-service"},
  "_handle": "comp-augment-service"
}
```

## C-13. MetaService (시계열·외부 메타 검토)
```json
{
  "type": "COMPONENT",
  "title": "MetaService (시계열·외부 메타 검토 백엔드)",
  "content": "**책임**\n- VLM 자동 생성 메타 + 외부 시스템 인계 메타에 대해 REVIEWER 검토 상태 관리\n- GET/PUT `/v1/frames/{srcSn}/meta` — REVIEWER 권한\n- LS_DATA_META_REVIEW 상태 전이 (AUTO_GENERATED → PENDING → APPROVED/REJECTED)\n\n**의존**: ent-data-meta, ent-data-meta-review",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-meta-service"},
  "_handle": "comp-meta-service"
}
```

## C-14. PresetService (라벨 프리셋)
```json
{
  "type": "COMPONENT",
  "title": "PresetService (라벨 프리셋 관리)",
  "content": "**책임**\n- 이벤트 타입 1:1 매핑 프리셋 CRUD + clone\n- Aggregate Root = LabelPreset\n- REVIEWER 권한\n\n**의존**: ent-label-preset",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-preset-service"},
  "_handle": "comp-preset-service"
}
```

## C-15. PortalSuite (포털 진입 사용자 라벨링)
```json
{
  "type": "COMPONENT",
  "title": "PortalSuite (포털 진입 사용자 간편 라벨링)",
  "content": "**책임**\n- 포털 서버 인증 후 진입한 PORTAL_USER 에게 간편 라벨링(`/portal/label`) 제공\n- 본인 데이터 라벨 조회·수정 (소유자 검증)\n- 채널 가드 = PORTAL + 역할 가드 = PORTAL_USER\n\n**책임 외**: 회원가입·로그인·업로드·다운로드·공지사항·사용 통계 등 포털 자체 기능\n**구현 위치**: frontend portal 영역 + backend portal 도메인 (간편 라벨링 한정)",
  "attrs": {"layer": "APPLICATION", "componentId": "comp-portal-suite"},
  "_handle": "comp-portal-suite"
}
```

## C-17. ControlNotifyClient (관제서버 outbound 통지 어댑터 — V1.8 신규)
```json
{
  "type": "COMPONENT",
  "title": "ControlNotifyClient (관제서버 작업 완료/수정 통지 비동기 어댑터)",
  "content": "**책임**\n- 관제서버(ext-control-server) inbound SPI 로 영상 단위 작업의 완료/수정 이벤트 비동기 push (if-control-notify-spi)\n- 이벤트 타입 2종: `TASK_COMPLETED` (검수 APPROVED → COMPLETED 전이 시) / `TASK_MODIFIED` (COMPLETED 영상의 라벨/메타 수정 시)\n- 동일 작업 ID(`LS_DATA_RAW.RAW_SN`) 유지 — 버전 업·새 ID 발급 없음\n- 디바운서로 같은 작업 ID 의 다중 수정을 짧은 시간(운영 결정 — 기본 60s) 내 1회로 통합. 프레임 단위 변경 항목을 누적\n- 페이로드 빌더:\n  - **TASK_COMPLETED**: 메타만 (작업 ID + 영상 메타 + 검수 완료 일시 + 프레임 개수 + 결과 요약 카운트)\n  - **TASK_MODIFIED**: 변경 프레임 목록 — 각 항목 `{ srcSn, changeKind(LABEL_ADDED|LABEL_UPDATED|LABEL_DELETED|META_UPDATED), labels(변경 후 라벨 본문), meta(변경 후 메타 본문) }`\n- 페이로드 보안 제약: PII·토큰·원본 비-비식별 이미지 미포함. 비식별 처리된 라벨 좌표·종류·메타 본문은 전달\n- 대용량 페이로드 분할 송신 (같은 요청 ID + `chunkIndex/chunkTotal`). 임계값은 운영 결정\n- 요청 ID 기반 idempotency, dead-letter, 재등록 큐\n- Resilience4j: timeout + Retry max=3 + exp backoff + CircuitBreaker. stg/prd 에서 fallback 큐 활성화\n- 송신 이력 감사 로그\n\n**의존**: ReviewService(COMPLETED 전이 이벤트), LabelService/MetaService(COMPLETED 영상 수정 이벤트 — 변경 프레임 ID 와 변경 종류 포함)\n**구현 위치**: `backend/.../common/client/ControlNotifyClient.java`",
  "attrs": {"layer": "INFRA", "componentId": "comp-control-notify-client"},
  "_handle": "comp-control-notify-client"
}
```

## C-16. JwtAuthFilter + SecurityConfig + RoleHierarchy
```json
{
  "type": "COMPONENT",
  "title": "JwtAuthenticationFilter + SecurityConfig + RoleHierarchy",
  "content": "**책임**\n- 관제서버(ext-control-server) / 포털 서버(ext-portal-server) 발급 JWT 검증(HS256)\n- TokenClaims 로 role/channel 분리 → 라우트 가드\n- RoleHierarchy: REVIEWER 가 시스템 설정·사용자 관리·프리셋 관리 권한 보유\n- ChannelGuard + RoleGuard 단일 검증 로직\n\n**구현 위치**: backend common.security",
  "attrs": {"layer": "INFRA", "componentId": "comp-jwt-filter"},
  "_handle": "comp-jwt-filter"
}
```

---

## 등록 순서

ENTITY 등록 후 COMPONENT 일괄 등록 → USECASE → REALIZES → COMPONENT 링크 + COMPONENT → DEPENDS_ON → ENTITY/INTERFACE 링크.

1. comp-batch-orchestrator
2. comp-deidentify-client
3. comp-ai-server
4. comp-ai-server-client
5. comp-vlm-client
6. comp-gitea-client
7. comp-label-canvas
8. comp-label-service
9. comp-assignment-service
10. comp-review-service
11. comp-version-service
12. comp-augment-service
13. comp-meta-service
14. comp-preset-service
15. comp-portal-suite
16. comp-jwt-filter
17. comp-control-notify-client (V1.8 신규)
