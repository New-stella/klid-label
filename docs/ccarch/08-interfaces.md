# INTERFACE 노드 정의

> ccarch type: `INTERFACE` / 필수: `title`, `content` / 옵션: `protocol`(REST|GRAPHQL|GRPC|EVENT)
> 본 사업 저작도구의 외부 노출·내부 호출 계약. INTERFACE → COMPONENT 또는 USECASE → REALIZES → INTERFACE 형태로 연결.

## I-01. POST /v1/assignments (작업 배정)

```json
{
  "type": "INTERFACE",
  "title": "POST /v1/assignments (작업 배정)",
  "content": "**요청** AssignmentCreateRequest { videoId: Long, workerId: Long }\n**응답** 201 + AssignmentResponse\n**가드** @PreAuthorize(\"hasRole('REVIEWER')\") + requireReviewer(actor) 이중\n**부수 효과** LS_TASK_ASSIGNMENT INSERT, LsRawDataStatus 'ASSIGNED' 전환\n**에러**: 409(이미 배정) / 403(권한) / 404(영상 없음)\n**구현**: backend assignment 도메인",
  "attrs": {"protocol": "REST"},
  "_handle": "if-assignment-api"
}
```

## I-02. GET /v1/tasks/board (검수자 작업 보드)

```json
{
  "type": "INTERFACE",
  "title": "GET /v1/tasks/board (REVIEWER 통합 작업 보드)",
  "content": "**쿼리** status=COMPLETED&page=0&size=20 (max-page-size:100, status allowlist 검증)\n**응답** Page<TaskBoardItemResponse>\n**가드** @PreAuthorize(\"hasRole('REVIEWER')\") + requireReviewer(actor)\n**enrich**: video 메타(cctvName, eventName, eventTypeCd, frameCount[batch GROUP BY], capturedAt) + optional task (workerId/Name, reviewerId/Name, status, assignedAt, firstSrcSn)\n**성능**: 페이지당 추가 쿼리 ≤ 5건\n**구현**: backend assignment 도메인 — TaskBoardController + TaskBoardService (Phase 3)",
  "attrs": {"protocol": "REST"},
  "_handle": "if-task-board-api"
}
```

## I-03. GET/PUT /v1/frames/{srcSn}/labels (라벨 CRUD)

```json
{
  "type": "INTERFACE",
  "title": "GET/PUT /v1/frames/{srcSn}/labels (라벨 조회·일괄 upsert)",
  "content": "**GET** → 라벨 목록 + 마스터 정보(color 등)\n**PUT** LabelBulkUpsertRequest { labels: [...] } → 일괄 upsert + Gitea 자동 커밋 트리거\n**가드** REVIEWER|WORKER + LabelAccessGuard (본인 배정만, CWE-639)\n**검증**: 좌표 점 1000개 한도, autoLblYn 무시(Mass Assignment 방어)\n**응답**: 201 + LabelResponse[]\n**구현**: backend label 도메인",
  "attrs": {"protocol": "REST"},
  "_handle": "if-label-api"
}
```

## I-04. /v1/reviews/* (검수 API 묶음)

```json
{
  "type": "INTERFACE",
  "title": "GET /v1/reviews + 상세 + frames + issues + decision",
  "content": "**GET /v1/reviews** (목록, 페이징, eventName/eventTypeCd enrich)\n**GET /v1/reviews/{id}** (상세)\n**GET /v1/reviews/{id}/frames** (프레임 캐러셀)\n**GET/POST /v1/reviews/{id}/issues** (이슈 코멘트)\n**POST /v1/reviews/{id}/decision** { verdict: 'APPROVED'|'REJECTED', reason? }\n**가드**: hasRole('REVIEWER'). @Version 낙관적 잠금 (409 Conflict 응답).\n**구현**: backend review 도메인 — ReviewResponse 7-arg",
  "attrs": {"protocol": "REST"},
  "_handle": "if-review-api"
}
```

## I-05. /v1/versions/* (버전 관리 API)

```json
{
  "type": "INTERFACE",
  "title": "GET /v1/versions + /diff + /rollback (라벨 버전 관리)",
  "content": "**GET /v1/versions?rawSn=** — 라벨링 커밋 목록 (한글 메시지)\n**GET /v1/versions/{commit}/diff** — 라벨 단위 diff (BE 응답 형식 정합)\n**POST /v1/versions/{commit}/rollback** — 특정 커밋으로 복원\n**가드**: 인증 + REVIEWER\n**부수 효과**: Gitea API 호출 → LS_DATA_LBL 복원 → FE VERSION_KEYS/TASK_BOARD_KEYS invalidate\n**구현**: backend version 도메인",
  "attrs": {"protocol": "REST"},
  "_handle": "if-version-api"
}
```

## I-06. /v1/augments/* (증강 API)

```json
{
  "type": "INTERFACE",
  "title": "GET /v1/augments + /request + /accept + /reject (증강)",
  "content": "**GET /v1/augments** (목록)\n**POST /v1/augments/request** { videoId, augType } — 검수 완료 영상만 허용 (BE 가드)\n**POST /v1/augments/{id}/accept** — 학습데이터 인계\n**POST /v1/augments/{id}/reject** { reason }\n**가드**: REVIEWER\n**구현**: backend augment 도메인",
  "attrs": {"protocol": "REST"},
  "_handle": "if-augment-api"
}
```

## I-07. GET/PUT /v1/frames/{srcSn}/meta (시계열 메타)

```json
{
  "type": "INTERFACE",
  "title": "GET/PUT /v1/frames/{srcSn}/meta (외부 메타 검토 UI)",
  "content": "**GET** → MetaResponse (description, objects, environment)\n**PUT** → 수정\n**가드**: REVIEWER\n**책임 경계(V1.7)**: 자동 생성·VLM 본체는 외부. 본 도구는 검토 UI만.\n**구현**: backend meta 도메인",
  "attrs": {"protocol": "REST"},
  "_handle": "if-meta-api"
}
```

## I-08. /v1/presets (프리셋 API)

```json
{
  "type": "INTERFACE",
  "title": "GET/POST/PUT/DELETE /v1/presets (라벨 프리셋)",
  "content": "이벤트 타입 1:1 프리셋 관리. POST 시 기존 프리셋 clone 가능. **가드**: REVIEWER\n**구현**: backend preset 도메인",
  "attrs": {"protocol": "REST"},
  "_handle": "if-preset-api"
}
```

## I-09. /v1/stats/* (통계 API)

```json
{
  "type": "INTERFACE",
  "title": "GET /v1/stats/workers + /overall (통계)",
  "content": "**GET /v1/stats/workers** — 작업자별 처리량·labelCount(실 집계)\n**GET /v1/stats/overall** — 월별 처리 영상·라벨 카운트\n**가드**: REVIEWER\n**구현**: backend stat 도메인",
  "attrs": {"protocol": "REST"},
  "_handle": "if-stat-api"
}
```

## I-10. AI 서버 추론 SPI (Spring Boot → ai-server)

```json
{
  "type": "INTERFACE",
  "title": "AI Server 추론 SPI (POST /infer/{yolo|sam2|vlm}/...)",
  "content": "**경로**\n- POST /infer/yolo/predict, /infer/yolo/track\n- POST /infer/sam2/segment, /infer/sam2/track\n- POST /infer/vlm/verify-objects (객체 검증 한정)\n- GET  /health\n\n**계약**\n- 요청: 이미지 페이로드(base64 또는 multipart) + 메타\n- 응답: 객체 목록(class, bbox/polygon, confidence) + `mock: bool`\n\n**클라이언트**: AiServerClient (Spring Boot)\n**비기능**: timeout 60s, CircuitBreaker 50%/window10, Retry max=3+exp backoff, 32MB 버퍼\n**Mock**: AI_MOCK_MODE=true 또는 가중치 미존재 시 mock 응답",
  "attrs": {"protocol": "REST"},
  "_handle": "if-ai-server-spi"
}
```

## I-11. Deidentify API (비식별 솔루션 SPI)

```json
{
  "type": "INTERFACE",
  "title": "Deidentify API (POST /api/v1/deidentify)",
  "content": "**요청**: 영상 또는 프레임 + 비식별 옵션(블러 강도, 대상 객체)\n**응답**: 비식별 결과 메타 + 처리 영역 좌표\n**클라이언트**: DeidentifyClient (Spring Boot)\n**비기능**: timeout 60s (30s 설정값 overwrite), CircuitBreaker 50%/window10, Retry max=3\n**실패 처리**: DE_IDNTF_YN='F' 마킹 + 재시도 큐 (원본 절대 삭제 금지)\n**비고**: 솔루션은 발주기관 SW 직접구매로 제공",
  "attrs": {"protocol": "REST"},
  "_handle": "if-deidentify-api"
}
```

## I-12. Gitea Contents API (버전 관리 SPI)

```json
{
  "type": "INTERFACE",
  "title": "Gitea Contents API (PUT/GET/DELETE /repos/{owner}/{repo}/contents/{path})",
  "content": "**용도**: 라벨 저장 시 자동 커밋 — 라벨 JSON 파일을 owner/repo 의 path 에 PUT\n**메시지**: 한글 + frame·변화 카운트 enrichment\n**클라이언트**: GiteaClient (Spring Boot, WebClient)\n**비기능**: timeout 70s, CircuitBreaker 50%/window10/minCalls 5/wait 30s, Retry max=3+exp backoff, stg/prd 에서 fallback 큐 활성화\n**환경변수**: GITEA_BASE_URL, GITEA_TOKEN, GITEA_OWNER, GITEA_REPO",
  "attrs": {"protocol": "REST"},
  "_handle": "if-gitea-contents"
}
```

---

## 등록 순서

INTERFACE는 ENTITY/COMPONENT 등록 후. COMPONENT → DEPENDS_ON → INTERFACE 링크 또는 USECASE → REALIZES → INTERFACE 링크 작성용.

권장: if-assignment-api → if-task-board-api → if-label-api → if-review-api → if-version-api → if-augment-api → if-meta-api → if-preset-api → if-stat-api → if-ai-server-spi → if-deidentify-api → if-gitea-contents
