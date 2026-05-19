# COMPONENT 노드 정의

> ccarch type: `COMPONENT` / 필수: `title`, `content` / 옵션: `layer`(PRESENTATION|APPLICATION|DOMAIN|INFRA)
> COMPONENT는 ENTITY 등록 후 등록한다. USECASE → REALIZES → COMPONENT, COMPONENT → DEPENDS_ON → INTERFACE/ENTITY 링크 작성용.

## C-01. comp-batch-orchestrator (Quartz Orchestrator)

```json
{
  "type": "COMPONENT",
  "title": "BatchOrchestrator (Quartz 파이프라인 조정자)",
  "content": "**책임**: 영상 자동 처리 파이프라인의 7-Step 조정. Quartz Job이 1건/분 PENDING 영상 선점 → 각 Step 순차 실행 → 상태 전이.\n\n**Step**\n- FrameExtractStep (FFmpeg → LS_DATA_SRC 적재)\n- DeidentifyStep (PRVC|PSDO 조건부)\n- YoloAutolabelStep\n- Sam2SegmentStep\n- TrackInterpolationStep (CVAT 포팅)\n- VlmMetaStep (객체 검증 한정 V1.7)\n\n**의존**\n- comp-ai-server-client, comp-deidentify-client\n- ent-data-raw, ent-batch-log\n\n**프로파일**: local 비활성(수동 트리거) / dev·stg·prd 자동\n\n**구현 위치**: `backend/src/main/java/kr/co/cudo/authoring/batch/*`",
  "attrs": {"layer": "APPLICATION"},
  "_handle": "comp-batch-orchestrator"
}
```

## C-02. comp-video-ingest-service (영상 수집 서비스)

```json
{
  "type": "COMPONENT",
  "title": "VideoIngestService (영상 수집·중복 차단)",
  "content": "**책임**\n- 중계서버 수신 영상의 LS_DATA_RAW 적재\n- 오탐·중복 검증 룰 적용\n- PRVC_TYPE_CD 라우팅\n\n**의존**: ent-data-raw, comp-vms-adapter\n**구현 위치**: backend video 도메인",
  "attrs": {"layer": "APPLICATION"},
  "_handle": "comp-video-ingest-service"
}
```

## C-03. comp-vms-adapter (VMS 표준 변환)

```json
{
  "type": "COMPONENT",
  "title": "VmsAdapter (이기종 VMS 표준 변환 모듈)",
  "content": "**책임**\n- VMS 유형별 독립 변환 라이브러리\n- 카메라 ID·이벤트 코드·좌표·영상 포맷을 표준 스키마로 매핑\n- 신규 VMS 추가 시 모듈만 추가 (기존 시스템 무수정)\n- 1차 사업 표준 API 구조 호환\n\n**구현 위치**: backend common.adapter (계획) 또는 신규 모듈",
  "attrs": {"layer": "INFRA"},
  "_handle": "comp-vms-adapter"
}
```

## C-04. comp-network-bridge (망연계 클라이언트)

```json
{
  "type": "COMPONENT",
  "title": "NetworkBridge (망연계 솔루션 클라이언트)",
  "content": "**책임**\n- 발주기관 SW 직접구매 망연계 솔루션과 연동\n- 폐쇄망(통합관제센터)↔개방망(중앙) 데이터 전송\n- 5개 신규 지자체 망연계 환경 구축\n\n**구현 위치**: backend common.client (계획)",
  "attrs": {"layer": "INFRA"},
  "_handle": "comp-network-bridge"
}
```

## C-05. comp-center-monitor-service (중계서버 모니터링)

```json
{
  "type": "COMPONENT",
  "title": "CenterMonitorService (지자체 중계서버 모니터링)",
  "content": "**책임**\n- 지자체 중계서버 자원/연계상태/오류 폴링\n- 비정상 시 알림 + 조치 인터페이스 노출\n- 조치 이력 기록\n\n**구현 위치**: backend common.monitor (계획) 또는 신규 모듈",
  "attrs": {"layer": "APPLICATION"},
  "_handle": "comp-center-monitor-service"
}
```

## C-06. comp-deidentify-client (비식별 솔루션 어댑터)

```json
{
  "type": "COMPONENT",
  "title": "DeidentifyClient (비식별 솔루션 HTTP 어댑터)",
  "content": "**책임**\n- 비식별 솔루션 REST API 호출 (영상 비식별화 솔루션은 발주기관 SW 직접구매)\n- 비식별 처리 결과 수신 + LS_DEIDENT_REPORT 기록\n- Resilience4j (timeout 60s, CircuitBreaker 50%/window10, Retry max=3)\n- 실패 시 DE_IDNTF_YN='F' 마킹 + 재시도 큐\n\n**구현 위치**: `backend/src/main/java/kr/co/cudo/authoring/common/client/DeidentifyClient.java`",
  "attrs": {"layer": "INFRA"},
  "_handle": "comp-deidentify-client"
}
```

## C-07. comp-ai-server (FastAPI 추론 서버)

```json
{
  "type": "COMPONENT",
  "title": "AiServer (FastAPI YOLO/SAM2/VLM 추론)",
  "content": "**책임**: Stateless 추론 — 인증/DB 없음. Spring Boot가 호출하는 마이크로서비스.\n\n**라우터**\n- `/infer/yolo/predict`, `/infer/yolo/track`\n- `/infer/sam2/segment`, `/infer/sam2/track`\n- `/infer/vlm/verify-objects` (객체 검증 한정 V1.7)\n- `/health`\n\n**특이**\n- AI_MOCK_MODE=true 또는 가중치 미존재 시 mock 응답 (응답에 `mock=true`)\n- Lifespan 훅 startup 시 모델 워밍업\n- RequestIdMiddleware + CORS\n\n**구현 위치**: `ai-server/app/main.py` + `ai-server/app/routers/{yolo,sam2,vlm}.py`",
  "attrs": {"layer": "INFRA"},
  "_handle": "comp-ai-server"
}
```

## C-08. comp-ai-server-client (Spring Boot → AI 서버)

```json
{
  "type": "COMPONENT",
  "title": "AiServerClient (Spring Boot 측 AI 서버 호출 클라이언트)",
  "content": "**책임**\n- ai-server REST 호출 단일화 (predictYolo / predictYoloTrack / predictSam2Segment / predictSam2Track / predictVlmVerify)\n- WebClient 32MB 버퍼 (이미지 페이로드 대응)\n- timeout 60s + CircuitBreaker + Retry\n- 응답 `mock=true` 감지 시 WARN 로그\n\n**구현 위치**: `backend/.../common/client/AiServerClient.java`",
  "attrs": {"layer": "INFRA"},
  "_handle": "comp-ai-server-client"
}
```

## C-09. comp-label-canvas (FE 라벨링 캔버스)

```json
{
  "type": "COMPONENT",
  "title": "LabelCanvas (React + Konva 캔버스)",
  "content": "**책임**: 라벨 시각화·편집 도구 패턴 (SELECT/BBOX/POLYGON/MASK_BRUSH/MASK_ERASER/SAM2_TRACK/PAN), 좌표·마스크 유틸, dirty 추적.\n\n**구성**\n- CanvasShell / ImageLayer / LabelsLayer / OverlayLayer\n- 도구 컴포넌트 7종\n- coordinateTransformer, polygonHelpers, maskRleConverter, trackInterpolation\n- useLabelStore (Zustand): activeTool, dirtyLabels, zoom/pan/undo\n- SaveCommitFlow → commit API\n\n**구현 위치**: `frontend/src/features/label/canvas/*`",
  "attrs": {"layer": "PRESENTATION"},
  "_handle": "comp-label-canvas"
}
```

## C-10. comp-label-service (BE 라벨링 서비스)

```json
{
  "type": "COMPONENT",
  "title": "LabelService (라벨 CRUD + 검증)",
  "content": "**책임**\n- 라벨 일괄 upsert (LabelBulkUpsertRequest)\n- LabelAccessGuard로 본인 배정만 접근 (CWE-639 IDOR 방어)\n- Mass Assignment 방어 (record DTO + autoLblYn 무시)\n- 저장 후 GiteaClient.commit 호출 (SaveCommitFlow)\n\n**구현 위치**: `backend/.../label/service/LabelService.java`\n**의존**: ent-data-lbl, ent-label-master, comp-gitea-client",
  "attrs": {"layer": "APPLICATION"},
  "_handle": "comp-label-service"
}
```

## C-11. comp-assignment-service (배정·재배정)

```json
{
  "type": "COMPONENT",
  "title": "AssignmentService + TaskBoardService (작업 배정·재배정)",
  "content": "**책임**\n- REVIEWER 권한 가드 (`@PreAuthorize` + `requireReviewer(actor)` 이중)\n- 배정 생성/조회/재배정\n- 완료 작업 재배정 차단 가드\n- TaskBoardService — 검수자용 통합 목록 (페이지당 추가 쿼리 ≤ 5건, frameCount batch GROUP BY)\n\n**의존**: ent-task-assignment, ent-raw-data-status",
  "attrs": {"layer": "APPLICATION"},
  "_handle": "comp-assignment-service"
}
```

## C-12. comp-review-service (검수 서비스)

```json
{
  "type": "COMPONENT",
  "title": "ReviewService (검수 워크플로우)",
  "content": "**책임**\n- 검수 목록 enrich (cctvName, workerName, labelCount, eventName 페이지당 lookup ≤ 5건)\n- 검수 상세 (frames, labels, issues)\n- 결정 처리 (APPROVED/REJECTED) + @Version 낙관적 잠금\n- reviewCompletedAt 기록\n- reviewStatusCd 필터 지원\n\n**의존**: ent-raw-data-status, ent-data-issue\n**구현 위치**: backend review 도메인",
  "attrs": {"layer": "APPLICATION"},
  "_handle": "comp-review-service"
}
```

## C-13. comp-version-service + comp-gitea-client

```json
{
  "type": "COMPONENT",
  "title": "VersionService + GiteaClient (라벨 버전 관리)",
  "content": "**책임**\n- 라벨 저장 시 Gitea 자동 커밋 (한글 메시지, frame·변화 카운트 enrichment)\n- 작업 이력 조회 (LS_DATA_LBL_HSTRY)\n- 커밋 간 diff (라벨 단위 응답 형식 정합)\n- 롤백 지원\n\n**GiteaClient**: REST 어댑터 — Resilience4j(timeout 70s, CB 50%/window10, Retry max=3), stg/prd 에서 fallback 큐 활성화\n\n**구현 위치**: backend version 도메인 + common/client/GiteaClient.java",
  "attrs": {"layer": "APPLICATION"},
  "_handle": "comp-version-service"
}
```

## C-14. comp-augment-service (증강)

```json
{
  "type": "COMPONENT",
  "title": "AugmentService + AugmentIntegrityCalculator + AugmentReviewService",
  "content": "**책임**\n- 증강 요청 (검수 완료 영상만 허용)\n- 외부 시스템 증강 결과 수신\n- 증강 결과 검수 (accept/reject)\n- 라벨 무결성 계산 (LS_DATA_AUG_LBL_MAP)\n\n**의존**: ent-data-aug",
  "attrs": {"layer": "APPLICATION"},
  "_handle": "comp-augment-service"
}
```

## (FE 도메인 도구·통계는 COMPONENT 묶음 처리)

### C-15. comp-meta-service / comp-preset-service / comp-stat-service / comp-sysconfig-service / comp-user-service / comp-portal-suite / comp-jwt-filter

```json
[
  {
    "type": "COMPONENT",
    "title": "MetaService (시계열 메타 검토 UI 백엔드)",
    "content": "외부 생성 시계열 메타 조회/수정 (PUT은 검수자만). MetaController + Service. V1.7 정리: 자동 생성·VLM 본체는 외부.",
    "attrs": {"layer": "APPLICATION"},
    "_handle": "comp-meta-service"
  },
  {
    "type": "COMPONENT",
    "title": "PresetService (라벨 프리셋 관리)",
    "content": "이벤트 타입 1:1 매핑 프리셋 CRUD + clone. Aggregate Root = LabelPreset. REVIEWER 권한.",
    "attrs": {"layer": "APPLICATION"},
    "_handle": "comp-preset-service"
  },
  {
    "type": "COMPONENT",
    "title": "StatService (작업자/전체 통계 + GIS)",
    "content": "월별 labelCount 실 집계, 작업자 처리량, GIS 기반 CCTV 자원·사각지대·재난 통계.",
    "attrs": {"layer": "APPLICATION"},
    "_handle": "comp-stat-service"
  },
  {
    "type": "COMPONENT",
    "title": "SystemConfigService (시스템 설정 + Caffeine 캐시 TTL 60s)",
    "content": "LS_SYSTEM_CONFIG CRUD + Caffeine TTL 60s. /manage/sysconfig 화면에서 REVIEWER가 수정. Phase 12 도입.",
    "attrs": {"layer": "APPLICATION"},
    "_handle": "comp-sysconfig-service"
  },
  {
    "type": "COMPONENT",
    "title": "UserService (사용자 + 권한 + IP 제어 + 모바일 공무원증)",
    "content": "REVIEWER만 접근. 사용자 관리, 권한 세분화(광역/기초 담당자), 사용자별 IP 화이트리스트, 모바일 공무원증 로그인 연동.",
    "attrs": {"layer": "APPLICATION"},
    "_handle": "comp-user-service"
  },
  {
    "type": "COMPONENT",
    "title": "PortalSuite (포털 도메인 묶음 — Auth/Upload/Label/Notice/Stat)",
    "content": "포털 회원가입·로그인·TUS 업로드·간편 라벨링·공지사항·통계. WCAG 2.1, 반응형. 채널 가드 = PORTAL.",
    "attrs": {"layer": "APPLICATION"},
    "_handle": "comp-portal-suite"
  },
  {
    "type": "COMPONENT",
    "title": "JwtAuthenticationFilter + SecurityConfig + RoleHierarchy",
    "content": "관제서버/포털이 발급한 JWT 검증(HS256). TokenClaims로 role/channel 분리 → 라우트 가드. RoleHierarchy: ADMIN→REVIEWER→WORKER (V1.3에서 ADMIN 권한 REVIEWER 흡수).",
    "attrs": {"layer": "INFRA"},
    "_handle": "comp-jwt-filter"
  }
]
```

---

## 추가: ARCHITECTURE 결정 참고 정보 (구현 매핑)

| 컴포넌트 | 모듈/위치 | 주요 의존 INTERFACE |
|---|---|---|
| comp-batch-orchestrator | backend/batch | if-yolo, if-sam2, if-vlm-verify, if-deidentify-api |
| comp-ai-server-client | backend/common/client | if-yolo, if-sam2, if-vlm-verify |
| comp-deidentify-client | backend/common/client | if-deidentify-api |
| comp-gitea-client | backend/common/client | if-gitea-contents |
| comp-label-service | backend/label | if-label-api |
| comp-review-service | backend/review | if-review-api |
| comp-assignment-service | backend/assignment | if-assignment-api, if-task-board-api |
| comp-version-service | backend/version | if-version-api |
| comp-label-canvas | frontend/features/label/canvas | if-label-api, if-version-api, if-sam2 (via BE) |

## 등록 순서

1. ENTITY 전부 완료 후
2. COMPONENT 등록 (위 14~20개)
3. 그 다음 USECASE → REALIZES → COMPONENT 링크
4. COMPONENT → DEPENDS_ON → ENTITY/INTERFACE 링크
