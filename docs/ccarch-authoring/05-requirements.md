# REQUIREMENT 노드 정의 — 저작도구 책임 정제

> 각 REQUIREMENT 는 `DERIVES_FROM` 으로 SOURCE_REQUIREMENT 에 연결된다.

## REQ-001. 영상 자동 처리 파이프라인 (Quartz Orchestrator)
```json
{
  "type": "REQUIREMENT",
  "title": "Quartz Job 이 영상 처리 파이프라인을 1건/분 자동 수행한다",
  "content": "**전제**: 영상이 LS_DATA_RAW 에 PENDING 상태로 적재되어 있다 (영상 인입 메커니즘은 외부 책임).\n\n**상태 머신**: PENDING → FRAME_EXTRACT → DEIDENTIFY → YOLO → SAM2 → VLM_TIMESERIES → COMPLETED\n\n- 재시도 max=3, 지수 백오프(1s × 2^n)\n- 단계별 상태와 오류는 `LS_BATCH_PROC_LOG` 기록\n- local 비활성(수동 트리거), dev/stg/prd 자동\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-batch-orchestrator", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-batch-orchestrator"
}
```

## REQ-002. 비식별 무조건 호출
```json
{
  "type": "REQUIREMENT",
  "title": "모든 영상에 대해 외부 비식별 솔루션을 호출하고 원본+비식별본을 동시 저장한다",
  "content": "- 호출 분기·예외 없이 모든 영상에 대해 `DeidentifyClient` → ext-deidentify-sw 호출\n- 원본과 비식별본을 별도 경로로 동시 저장\n- 실패 시 `LS_DATA_RAW.DE_IDNTF_YN='F'` 마킹 + 재시도 큐 (원본 절대 삭제 금지)\n- 비식별 처리 결과/이력은 프레임 단위로 `LS_DEIDENT_REPORT` 기록\n- 비식별 솔루션 옵션은 REVIEWER UI 로 노출\n\n**DERIVES_FROM**: sr-sfr-09",
  "attrs": {"requirementId": "req-deidentify-mandatory", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-deidentify-mandatory"
}
```

## REQ-003. YOLO/SAM2 자동 라벨링 (본 도구 ai-server)
```json
{
  "type": "REQUIREMENT",
  "title": "본 도구 ai-server 가 YOLO 객체 감지 + SAM2 세그멘테이션으로 1차 라벨을 생성한다",
  "content": "- ai-server FastAPI 라우터를 통해 YOLO/SAM2 추론 호출\n- 호출은 `AiServerClient` Bean으로 단일화\n- **본 도구 내부 마이크로서비스 간 호출**이므로 동기 패턴 적용: timeout 60s + Resilience4j CircuitBreaker(failure-rate 50%, window 10). 외부 시스템 비동기 정책 대상 아님\n- AI_MOCK_MODE 또는 가중치 미존재 시 mock 응답이 오면 BE 는 `mock=true` 감지하여 WARN 로그\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-yolo-sam2", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-yolo-sam2"
}
```

## REQ-004. 외부 VLM 호출로 시계열 메타 생성
```json
{
  "type": "REQUIREMENT",
  "title": "본 도구가 외부 VLM 서비스를 호출하여 시계열 메타를 생성·적재한다",
  "content": "- 자동 파이프라인 단계로 외부 VLM 서비스 호출 (시계열 분석)\n- 응답을 `LS_DATA_META`(META_TYPE_CD='VLM') 에 적재\n- `LS_DATA_META_REVIEW` 를 RVW_STTS_CD='AUTO_GENERATED' 또는 'PENDING' 으로 생성 — REVIEWER 검토 대상\n- 외부 시스템이 인계한 메타(META_TYPE_CD='EXTERNAL')가 있는 경우 동일 검토 흐름 사용\n- 영상 단위 일반 메타는 본 도구 책임 외 (외부에서 본 도구 DB 에 적재됨)\n\n**DERIVES_FROM**: sr-sfr-03",
  "attrs": {"requirementId": "req-vlm-timeseries", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-vlm-timeseries"
}
```

## REQ-005. 라벨링 캔버스 (BBox/Polygon/Segmentation/SAM2 Track)
```json
{
  "type": "REQUIREMENT",
  "title": "konva.js 기반 라벨링 캔버스로 BBox/Polygon/Mask/SAM2 Track 도구를 제공한다",
  "content": "- 도구: SELECT / BBOX / POLYGON / MASK_BRUSH / MASK_ERASER / SAM2_TRACK / PAN\n- 캔버스 레이어: ImageLayer / LabelsLayer / OverlayLayer\n- 좌표/마스크 유틸: coordinateTransformer, polygonHelpers, maskRleConverter\n- dirty 추적 (useLabelStore.dirtyLabels) → SaveCommitFlow → commit API\n- SAM2 Track 도구: 선택 폴리곤을 다음 프레임에 자동 전파 (ai-server `/infer/sam2/track` 호출)\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-labeling-canvas", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-labeling-canvas"
}
```

## REQ-006. 라벨 정확도 향상 (객체 추적·외곽 밀착·정밀도 조절)
```json
{
  "type": "REQUIREMENT",
  "title": "사용자 지정 객체를 추적해 위치·경계를 자동 갱신하고 외곽 밀착·정밀도 조절을 지원한다",
  "content": "- 객체 추적 자동 갱신 (trackId 기반 위치·경계)\n- 외곽 경계 자동 밀착 (Snap to edge)\n- 라벨링 정밀도 조절 (vertex 수, 스무딩 강도)\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-label-precision", "priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-label-precision"
}
```

## REQ-007. 라벨 마스터 풀 (CVAT-Like)
```json
{
  "type": "REQUIREMENT",
  "title": "라벨 마스터 풀(LS_LABEL)을 통해 라벨 종류·색상·속성을 중앙 관리한다",
  "content": "- `LS_LABEL` (마스터) → 1:N `LS_LABEL_ATTR` (속성 정의) → 1:N `LS_DATA_LBL_ATTR_VAL` (값)\n- color(#RRGGBB), 카테고리, 부모 라벨 관계, 활성 여부\n- 라벨 작성 시 데이터-라벨 FK 마이그레이션\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-label-master-pool", "priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-label-master-pool"
}
```

## REQ-008. 라벨 프리셋 (이벤트 타입 1:1 매핑 + clone)
```json
{
  "type": "REQUIREMENT",
  "title": "이벤트 타입별 라벨 프리셋을 1:1로 관리하고 clone 으로 새 프리셋을 생성한다",
  "content": "- `LS_LABEL_PRESET` Aggregate Root → 1:N `LS_LABEL_PRESET_CODE`\n- 이벤트 타입별 1:1 매핑 (UK)\n- 신규 프리셋 작성 시 기존 프리셋 clone 후 수정 가능\n- 영향받는 라벨링 작업 추적\n\n**API**: GET/POST/PUT/DELETE `/v1/presets` (REVIEWER 권한)\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-label-preset", "priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-label-preset"
}
```

## REQ-009. 작업자 배정·재배정 (완료 작업 차단)
```json
{
  "type": "REQUIREMENT",
  "title": "REVIEWER 가 WORKER 에게 작업을 배정·재배정하며 완료된 작업의 재배정은 차단한다",
  "content": "- REVIEWER 권한 가드 (`@PreAuthorize('REVIEWER')` + `requireReviewer(actor)` 이중)\n- `LS_TASK_ASSIGNMENT` (TASK_TYPE_CD='LABELER') INSERT, 재배정 시 `LS_TASK_ASSIGN_HISTORY` 기록\n- **완료된 작업의 재배정 차단** (FE UI disabled + BE 가드)\n- 배정 이력 조회도 REVIEWER 권한\n\n**API**: POST `/v1/assignments`, PATCH `/v1/assignments/{id}`, GET `/v1/tasks/board`\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-assign-task", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-assign-task"
}
```

## REQ-010. 검수 상태 머신 + 낙관적 잠금
```json
{
  "type": "REQUIREMENT",
  "title": "검수 워크플로우 상태 머신을 낙관적 잠금(@Version)으로 보호한다",
  "content": "**상태**: PENDING → ASSIGNED → IN_REVIEW → APPROVED / REJECTED\n\n- `LsRawDataStatus.@Version` 으로 OptimisticLockException 방지\n- 두 검수자가 동시 진입 시 두 번째는 409 Conflict 반환\n- 검수 완료 일시(`reviewCompletedAt`) 별도 기록\n- 검수 상태 필터(`reviewStatusCd`) 로 영상 목록 조회 가능\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-review-state-machine", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-review-state-machine"
}
```

## REQ-011. Gitea 자동 커밋·diff·롤백
```json
{
  "type": "REQUIREMENT",
  "title": "라벨 저장 이벤트마다 Gitea 에 자동 커밋하고 diff·롤백을 제공한다",
  "content": "- 라벨 저장 시 `GiteaClient` 가 Gitea Contents API 동기 호출 (frame·변화 카운트 enrichment + 한글 커밋 메시지)\n- 수신된 커밋 hash 를 즉시 `LS_DATA_LBL_HSTRY` 에 저장\n- GET `/v1/versions/{commit}/diff` — 라벨 단위 diff\n- POST `/v1/versions/{commit}/rollback` — 특정 커밋으로 복원\n- 외부 Gitea 연동은 동기 REST: timeout 70s + CircuitBreaker(50%/window10) + Retry max=3+exp backoff\n- CircuitBreaker open 등 장애 시에만 fallback 큐로 비동기 재시도 (stg/prd 에서 활성화) — 라벨은 DB 우선 저장되어 데이터 유실 없음\n- FE 캐시 무효화: VERSION_KEYS / TASK_BOARD_KEYS invalidate\n\n**DERIVES_FROM**: sr-sfr-08",
  "attrs": {"requirementId": "req-version-gitea", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-version-gitea"
}
```

## REQ-012. 검수 완료 영상만 증강 요청 허용
```json
{
  "type": "REQUIREMENT",
  "title": "검수 완료된 영상에 한해 외부 생성형 AI 시스템으로 증강 요청을 인계한다",
  "content": "- FE 는 검수 완료(`dataSttsCd='COMPLETED'`) 영상만 선택 가능\n- BE 가드: 증강 요청 시 영상 상태가 COMPLETED 인지 재확인하여 그 외는 거절\n- 외부 시스템이 송신한 결과 수신 후 `LS_DATA_AUG` 적재\n- 라벨 무결성(`LS_DATA_AUG_LBL_MAP`) 검증 후 REVIEWER 가 ACCEPTED/REJECTED 결정\n\n**DERIVES_FROM**: sr-sfr-06-07",
  "attrs": {"requirementId": "req-augment-completed-only", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-augment-completed-only"
}
```

## REQ-013. 포털 사용자 간편 라벨링 (저작도구 기능)
```json
{
  "type": "REQUIREMENT",
  "title": "포털 서버가 인증한 PORTAL_USER 에게 본인 데이터에 대한 간편 라벨링 기능을 제공한다",
  "content": "**본 도구 측 책임**\n- ext-portal-server 발급 JWT 검증 후 PORTAL 채널로 진입 처리 (`ChannelGuard('PORTAL') + RoleGuard([PORTAL_USER])`)\n- 간편 라벨링 화면(`/portal/label`) — YOLO+SAM2 오토라벨링 체험\n- 본인 데이터 라벨 조회·수정 (소유자 검증)\n\n**본 도구 책임 외**: 회원가입·로그인·업로드·다운로드·공지사항·통계 등 포털 자체 기능\n\n**DERIVES_FROM**: sr-sfr-15",
  "attrs": {"requirementId": "req-portal", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-portal"
}
```

## REQ-014. 학습데이터 산출물 (이미지 10만 + 영상 5천 CoT)
```json
{
  "type": "REQUIREMENT",
  "title": "이미지 10만장 + 영상 5,000건(CoT) 학습데이터를 정제·가공·검수한다",
  "content": "- 이미지 10만장: 바운딩박스/폴리곤/세그멘테이션 + 환경/이벤트 메타, 가명처리\n- 영상 5,000건: 이벤트 상황 포함 30초 이상 1건, **CoT 캡션 데이터** + VQA 구성\n- 수집-정제-가공-검수 프로세스 본 도구에서 수행\n- 외부전문 시험기관 샘플링 품질검사\n\n**DERIVES_FROM**: sr-sfr-16-17",
  "attrs": {"requirementId": "req-dataset-output", "priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-dataset-output"
}
```

## REQ-015. 핵심 비기능 (응답시간·반응형·보안)
```json
{
  "type": "REQUIREMENT",
  "title": "응답시간 3초 + 반응형 + 보안(시크릿 하드코딩 금지·암호화·접근통제)",
  "content": "**성능**: 페이지 응답 3초 이하, 10초 이상 작업은 사전 알림(팝업·프로그레스바), CPU/MEM 평균 80% 이하, 만 건 대비 size:999 패턴 금지(BE 페이징 size 20 + enrich)\n**반응형**: 1024×768~1920×1080, 수평스크롤 미사용, WCAG 2.1, 멀티브라우저(HTML5)\n**보안**\n- 로그인 5회 실패 시 차단, 동시 로그인 차단, 우회 접근 차단\n- 시크릿/비밀번호 소스코드 하드코딩 금지 → 환경변수 `${VAR}`\n- bcrypt cost 12+ 또는 Argon2id\n- gradle.lockfile 의존성 무결성\n- IDOR(CWE-639) — LabelAccessGuard\n- Race Condition(CWE-362) — @Version\n- DoS(CWE-770) — 페이지 size 100 한도, 좌표 점 1000 한도\n- Mass Assignment(CWE-915) — record DTO\n\n**DERIVES_FROM**: sr-nfr-core",
  "attrs": {"requirementId": "req-nfr-core", "priority": "HIGH", "category": "NON_FUNCTIONAL"},
  "_handle": "req-nfr-core"
}
```

---

## 등록 순서

USECASE 보다 먼저, SOURCE_REQUIREMENT 다음.

권장: req-batch-orchestrator → req-deidentify-mandatory → req-yolo-sam2 → req-vlm-timeseries → req-labeling-canvas → req-label-precision → req-label-master-pool → req-label-preset → req-assign-task → req-review-state-machine → req-version-gitea → req-augment-completed-only → req-portal → req-dataset-output → req-nfr-core
