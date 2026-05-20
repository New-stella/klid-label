# USECASE 노드 정의 — 저작도구 직접 책임 시나리오

> 등록 후 `DERIVES_FROM`(→ REQUIREMENT), `PERFORMED_BY`(→ ACTOR), `BELONGS_TO`(→ FEATURE), `REALIZES`(→ COMPONENT|INTERFACE) 링크 작성.
>
> 영상 인입(중계서버 송신·1차 이벤트 인입·VLM 영상 정제·중복 방지)은 본 도구 책임 외이므로 USECASE 로 등록하지 않는다. 본 도구는 LS_DATA_RAW 에 PENDING 으로 적재된 영상부터 처리한다.

## UC-01. 영상 자동 처리 파이프라인 진행
```json
{
  "type": "USECASE",
  "title": "Quartz 가 1건/분 영상을 PENDING 부터 COMPLETED 까지 자동 처리",
  "content": "**전제**: LS_DATA_RAW 에 영상이 PENDING 상태로 적재됨 (영상 인입 메커니즘은 외부 책임).\n**기본 흐름**\n1. Quartz 트리거로 PENDING 영상 1건 선점\n2. FrameExtractStep — FFmpeg 로 프레임 추출 → `LS_DATA_SRC` 적재\n3. DeidentifyStep — 모든 영상 무조건 호출 → ext-deidentify-sw\n4. YoloAutolabelStep / Sam2SegmentStep — comp-ai-server 호출\n5. VlmTimeseriesStep — ext-vlm-service 호출 → 시계열 메타 적재\n6. COMPLETED 전환\n**대안**: 실패 시 FAILED + 재시도 큐 (max=3, exp backoff)\n\n**PERFORMED_BY**: actor-batch-system\n**BELONGS_TO**: feat-auto-labeling\n**DERIVES_FROM**: req-batch-orchestrator, req-yolo-sam2, req-vlm-timeseries, req-deidentify-mandatory",
  "attrs": {"usecaseId": "uc-auto-pipeline", "primaryActors": ["actor-batch-system"]},
  "_handle": "uc-auto-pipeline"
}
```

## UC-02. 비식별 처리 (모든 영상 비동기 위탁)
```json
{
  "type": "USECASE",
  "title": "모든 영상에 대해 외부 비식별 솔루션에 비동기 작업 위탁 + 결과 수신 시 원본/비식별본 동시 저장",
  "content": "1. BATCH_SYSTEM 의 DeidentifyStep 이 영상에 대해 `DeidentifyClient` 로 비동기 작업 등록 (요청 ID 발급)\n2. 외부 비식별 솔루션이 처리 완료 시 결과 메시지를 본 도구에 통지 (큐/콜백/폴링은 운영 결정)\n3. 본 도구가 원본 + 비식별본을 별도 경로에 동시 저장\n4. 처리 결과/이력은 `LS_DEIDENT_REPORT` 에 프레임 단위 기록\n**대안**: 미응답·실패 → `DE_IDNTF_YN='F'` 마킹 + 재등록 큐 (원본 절대 삭제 금지). 동일 요청 ID 재인계 시 idempotency 처리.\n\n**PERFORMED_BY**: actor-batch-system, ext-deidentify-sw\n**BELONGS_TO**: feat-auto-labeling\n**DERIVES_FROM**: req-deidentify-mandatory",
  "attrs": {"usecaseId": "uc-deidentify", "primaryActors": ["actor-batch-system", "ext-deidentify-sw"]},
  "_handle": "uc-deidentify"
}
```

## UC-03. 비식별 결과 검토
```json
{
  "type": "USECASE",
  "title": "REVIEWER 가 비식별 처리 결과·이력을 관제지원시스템에서 확인",
  "content": "1. REVIEWER 가 비식별 목록 화면(`/deident`) 진입\n2. 영상별 비식별 진행/실패 상태 조회\n3. 상세 화면에서 프레임별 비식별 결과 확인\n4. 비식별 솔루션 옵션(블러 강도·대상 객체 등) 조정 UI 제공\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-auto-labeling\n**DERIVES_FROM**: req-deidentify-mandatory",
  "attrs": {"usecaseId": "uc-deident-review", "primaryActors": ["actor-reviewer"]},
  "_handle": "uc-deident-review"
}
```

## UC-04. 외부 VLM 시계열 비동기 위탁 및 결과 적재
```json
{
  "type": "USECASE",
  "title": "본 도구가 외부 VLM 서비스에 시계열 분석을 비동기 위탁하고 결과를 검토 대상으로 적재",
  "content": "1. BATCH_SYSTEM 이 자동 파이프라인 VlmTimeseriesStep 진입\n2. `VlmClient` 가 외부 VLM 서비스(ext-vlm-service) 에 시계열 분석 작업 비동기 등록 (요청 ID 발급)\n3. 외부 VLM 서비스 처리 완료 시 결과 메시지를 본 도구에 통지 (큐/콜백/폴링은 운영 결정)\n4. 결과를 `LS_DATA_META`(META_TYPE_CD='VLM') 에 적재\n5. `LS_DATA_META_REVIEW` 생성 (RVW_STTS_CD='AUTO_GENERATED' 또는 'PENDING')\n6. REVIEWER 검토 큐에 노출\n**대안**: 미응답·실패 → 재등록 큐. 동일 요청 ID 재인계 시 idempotency 처리.\n\n**PERFORMED_BY**: actor-batch-system, ext-vlm-service\n**BELONGS_TO**: feat-meta-pipeline\n**DERIVES_FROM**: req-vlm-timeseries",
  "attrs": {"usecaseId": "uc-vlm-timeseries-request", "primaryActors": ["actor-batch-system", "ext-vlm-service"]},
  "_handle": "uc-vlm-timeseries-request"
}
```

## UC-05. 메타 검토·승인·반려
```json
{
  "type": "USECASE",
  "title": "REVIEWER 가 시계열 메타(VLM 또는 EXTERNAL)를 프레임 단위로 검토·승인·반려",
  "content": "1. REVIEWER 가 MetaReviewPage(`/auto/:videoId/meta`) 진입\n2. GET `/v1/frames/{srcSn}/meta` — frame 단위 자연어 설명·객체·환경 조건 조회\n3. 수정 후 PUT (또는 승인/반려 API)\n4. `LS_DATA_META_REVIEW.RVW_STTS_CD` 가 APPROVED 또는 REJECTED 로 전이\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-meta-pipeline\n**DERIVES_FROM**: req-vlm-timeseries",
  "attrs": {"usecaseId": "uc-meta-review", "primaryActors": ["actor-reviewer"]},
  "_handle": "uc-meta-review"
}
```

## UC-06. 라벨 조회·편집
```json
{
  "type": "USECASE",
  "title": "WORKER 가 본인 배정 프레임의 라벨을 편집한다",
  "content": "**전제**: WORKER 가 배정된 작업의 라벨링 페이지(`/label/:id`) 에 진입\n**기본 흐름**\n1. GET `/v1/frames/{srcSn}/labels` — 라벨 목록 조회 (LabelAccessGuard 본인 검증)\n2. 도구 선택: BBOX / POLYGON / MASK_BRUSH / SAM2_TRACK\n3. konva.js 캔버스에서 라벨 작성·수정\n4. useLabelStore.dirtyLabels 에 변경분 누적\n5. PUT `/v1/frames/{srcSn}/labels` (LabelBulkUpsertRequest) → 커밋 시 Gitea 자동 커밋\n**대안**: 본인 배정 외 프레임 접근 시 403 (IDOR)\n\n**PERFORMED_BY**: actor-worker\n**BELONGS_TO**: feat-labeling-authoring\n**DERIVES_FROM**: req-labeling-canvas, req-label-precision",
  "attrs": {"usecaseId": "uc-label-edit", "primaryActors": ["actor-worker"]},
  "_handle": "uc-label-edit"
}
```

## UC-07. SAM2 Track 다음 프레임 자동 전파
```json
{
  "type": "USECASE",
  "title": "선택한 폴리곤을 SAM2 로 다음 N 프레임에 자동 전파",
  "content": "1. WORKER 가 라벨 선택 후 SAM2_TRACK 도구 활성화\n2. 본 도구 ai-server `/infer/sam2/track` 호출 (시작 폴리곤 + 후속 프레임 ID)\n3. 응답으로 받은 프레임별 폴리곤을 캔버스에 시각화\n4. WORKER 가 수동 조정 후 일괄 저장\n\n**PERFORMED_BY**: actor-worker\n**BELONGS_TO**: feat-labeling-authoring\n**DERIVES_FROM**: req-labeling-canvas, req-yolo-sam2",
  "attrs": {"usecaseId": "uc-sam2-track", "primaryActors": ["actor-worker"]},
  "_handle": "uc-sam2-track"
}
```

## UC-08. 라벨 마스터·속성 관리
```json
{
  "type": "USECASE",
  "title": "REVIEWER 가 라벨 마스터(LS_LABEL)와 속성(LS_LABEL_ATTR)을 관리한다",
  "content": "1. REVIEWER 가 라벨 마스터 화면 진입\n2. 신규 라벨 추가: 이름·색상·카테고리·부모 라벨\n3. 속성 추가: 라벨별 추가 메타(예: 차종, 신뢰도)\n4. 활성 여부 토글\n**제약**: 사용 중인 라벨은 비활성만 가능, 삭제 불가\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-labeling-authoring\n**DERIVES_FROM**: req-label-master-pool",
  "attrs": {"usecaseId": "uc-label-master", "primaryActors": ["actor-reviewer"]},
  "_handle": "uc-label-master"
}
```

## UC-09. 라벨 프리셋 관리·clone
```json
{
  "type": "USECASE",
  "title": "REVIEWER 가 이벤트 타입별 라벨 프리셋을 등록·clone·수정",
  "content": "1. PresetListPage 진입 → 이벤트 타입별 1:1 프리셋 목록\n2. 신규 프리셋 작성 시 기존 프리셋 clone 후 수정 가능\n3. 프리셋 코드(라벨/속성 매핑) 편집\n4. 영향받는 작업 추적 표시 후 적용\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-portal-and-admin\n**DERIVES_FROM**: req-label-preset",
  "attrs": {"usecaseId": "uc-preset-manage", "primaryActors": ["actor-reviewer"]},
  "_handle": "uc-preset-manage"
}
```

## UC-10. 작업자 배정·재배정 (완료 작업 차단)
```json
{
  "type": "USECASE",
  "title": "REVIEWER 가 WORKER 에게 작업을 배정하고 필요 시 재배정 — 완료 작업은 차단",
  "content": "**기본 흐름**\n1. REVIEWER 가 작업 보드(`/v1/tasks/board`)에서 미배정 영상 선택\n2. POST `/v1/assignments` (videoId, workerId)\n3. `LS_TASK_ASSIGNMENT` INSERT + LsRawDataStatus 'ASSIGNED' 전환\n**재배정**\n4. PATCH `/v1/assignments/{id}` 로 작업자 변경\n5. `LS_TASK_ASSIGN_HISTORY` 에 변경 이력 기록\n**대안**: 완료 작업(COMPLETED|APPROVED)은 재배정 거절 (FE disabled + BE 가드)\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-review-workflow\n**DERIVES_FROM**: req-assign-task",
  "attrs": {"usecaseId": "uc-assign-task", "primaryActors": ["actor-reviewer"]},
  "_handle": "uc-assign-task"
}
```

## UC-11. 검수 승인·반려 (낙관적 잠금)
```json
{
  "type": "USECASE",
  "title": "REVIEWER 가 라벨을 검수해 승인/반려 결정",
  "content": "1. REVIEWER 가 검수 상세(`/review/:id`) 진입 — frames + labels + issues 조회\n2. 프레임별 라벨 확인 → 이슈 코멘트 작성 가능\n3. 승인: POST `/v1/reviews/{id}/decision` (verdict=APPROVED)\n4. 반려: POST `/v1/reviews/{id}/decision` (verdict=REJECTED + 사유)\n5. LsRawDataStatus.@Version → OptimisticLock\n**대안**: 동시 접근으로 OptimisticLockException → 409 Conflict 응답, FE 재조회 안내\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-review-workflow\n**DERIVES_FROM**: req-review-state-machine",
  "attrs": {"usecaseId": "uc-review-decision", "primaryActors": ["actor-reviewer"]},
  "_handle": "uc-review-decision"
}
```

## UC-12. 라벨 일괄 커밋 → Gitea 동기 자동 커밋
```json
{
  "type": "USECASE",
  "title": "라벨 저장 시 Gitea 에 동기 자동 커밋하여 버전 이력을 남긴다",
  "content": "1. WORKER 가 SaveCommitFlow 트리거\n2. BE 가 라벨을 `LS_DATA_LBL` 에 upsert\n3. `GiteaClient` 가 외부 Gitea Contents API 동기 호출 (한글 메시지 + frame·변화 카운트 enrichment)\n4. 수신된 커밋 hash 를 `LS_DATA_LBL_HSTRY` 에 기록\n5. FE 응답에 커밋 결과 포함 → VERSION_KEYS · TASK_BOARD_KEYS invalidate\n**대안**\n- 외부 Gitea 장애로 CircuitBreaker open 시 fallback 큐 적재 후 비동기 재시도 (stg/prd 에서만 활성화)\n- 라벨은 DB 우선 저장되므로 fallback 적용 시에도 데이터 유실 없음\n- 사용자에게 커밋 지연 안내\n\n**PERFORMED_BY**: actor-worker, ext-gitea\n**BELONGS_TO**: feat-version-control\n**DERIVES_FROM**: req-version-gitea",
  "attrs": {"usecaseId": "uc-label-commit", "primaryActors": ["actor-worker", "ext-gitea"]},
  "_handle": "uc-label-commit"
}
```

## UC-13. 버전 diff·롤백
```json
{
  "type": "USECASE",
  "title": "REVIEWER 가 특정 커밋의 diff 를 조회하고 필요 시 롤백",
  "content": "1. GET `/v1/versions` — 라벨링 커밋 목록 (한글 메시지, frame·변화 카운트 표시)\n2. 특정 커밋 선택 → GET `/v1/versions/{commit}/diff` (라벨 단위 diff)\n3. 롤백 필요 시 POST `/v1/versions/{commit}/rollback`\n4. Gitea 에서 해당 커밋의 라벨을 현재 상태로 복원\n5. VERSION_KEYS · TASK_BOARD_KEYS invalidate\n\n**PERFORMED_BY**: actor-reviewer, ext-gitea\n**BELONGS_TO**: feat-version-control\n**DERIVES_FROM**: req-version-gitea",
  "attrs": {"usecaseId": "uc-version-diff-rollback", "primaryActors": ["actor-reviewer", "ext-gitea"]},
  "_handle": "uc-version-diff-rollback"
}
```

## UC-14. 증강 비동기 요청 인계 + 결과 수신 검수
```json
{
  "type": "USECASE",
  "title": "REVIEWER 가 검수 완료 영상에 증강을 비동기 요청하고 외부 결과를 수신해 검수",
  "content": "1. REVIEWER 가 AugmentRequestPage 진입 — 검수 완료 영상(dataSttsCd=COMPLETED)만 선택 가능\n2. 증강 조건(WINTER/NIGHT/RAIN/RESOLUTION) 선택 후 POST `/v1/augments/request` → 본 도구가 외부 생성형 AI 시스템(ext-generative-ai)에 비동기 작업 등록 (요청 ID 발급)\n3. 외부 시스템 처리 완료 시 결과를 본 도구의 `if-augment-result-handover` 로 push (인계 SPI)\n4. 본 도구가 요청 ID 매핑 후 `LS_DATA_AUG` 적재\n5. AugmentResultPage 에서 REVIEWER 검토\n6. POST `/v1/augments/{id}/accept` 또는 `/reject` (반려 사유)\n**라벨 무결성**: 증강 전·후 라벨 보존 검증 (`LS_DATA_AUG_LBL_MAP`)\n**신뢰성**: 요청 ID 기반 idempotency, 비정상 페이로드는 dead-letter\n\n**PERFORMED_BY**: actor-reviewer, ext-generative-ai\n**BELONGS_TO**: feat-augmentation\n**DERIVES_FROM**: req-augment-completed-only",
  "attrs": {"usecaseId": "uc-augment-flow", "primaryActors": ["actor-reviewer", "ext-generative-ai"]},
  "_handle": "uc-augment-flow"
}
```

## UC-15. 포털 사용자 본인 데이터 간편 라벨링
```json
{
  "type": "USECASE",
  "title": "포털 서버가 인증한 PORTAL_USER 가 본 도구에 진입하여 본인 데이터에 간편 라벨링",
  "content": "**전제**: PORTAL_USER 가 외부 포털 서버에서 회원가입·로그인·영상 업로드를 완료하고, 포털 서버 발급 JWT 로 본 도구의 `/portal` 진입.\n\n**기본 흐름**\n1. PORTAL 채널 가드 + 역할 가드 통과\n2. PortalLabelingPage(`/portal/label`) 진입\n3. 본인 데이터에 대한 YOLO+SAM2 오토라벨링 체험\n4. 라벨 조회·수정 (소유자 검증)\n\n**미제공**: 검수, 버전관리, VLM 메타, 회원가입, 업로드, 다운로드\n\n**PERFORMED_BY**: actor-portal-user\n**BELONGS_TO**: feat-portal-and-admin\n**DERIVES_FROM**: req-portal",
  "attrs": {"usecaseId": "uc-portal-label", "primaryActors": ["actor-portal-user"]},
  "_handle": "uc-portal-label"
}
```

## UC-16. JWT 사용자 인계 (관제/포털)
```json
{
  "type": "USECASE",
  "title": "관제서버/포털 서버가 발급한 JWT 를 본 도구가 검증하고 사용자를 채널·역할별로 진입시킨다",
  "content": "1. 관제서버 또는 포털 서버가 사용자에게 JWT 발급\n2. 사용자를 본 도구의 `/ingress` 로 redirect (토큰 전달)\n3. 본 도구의 `JwtAuthenticationFilter` 가 토큰 검증 (HS256)\n4. `TokenClaims.channel + role` 으로 ChannelGuard + RoleGuard 분기\n   - INTERNAL + REVIEWER/WORKER → 관제 채널 화면\n   - PORTAL + PORTAL_USER → 포털 채널 화면\n5. 세션 만료 시 각 상위 시스템 로그인 페이지로 redirect\n\n**PERFORMED_BY**: ext-control-server, ext-portal-server\n**BELONGS_TO**: feat-portal-and-admin\n**DERIVES_FROM**: req-nfr-core",
  "attrs": {"usecaseId": "uc-jwt-ingress", "primaryActors": ["ext-control-server", "ext-portal-server"]},
  "_handle": "uc-jwt-ingress"
}
```

---

## 등록 순서

REQUIREMENT, ACTOR, FEATURE 등록 후 USECASE 일괄 등록. 그 뒤 링크:
1. USECASE → DERIVES_FROM → REQUIREMENT
2. USECASE → PERFORMED_BY → ACTOR (사람 + 외부 시스템)
3. USECASE → BELONGS_TO → FEATURE
4. USECASE → REALIZES → COMPONENT|INTERFACE
