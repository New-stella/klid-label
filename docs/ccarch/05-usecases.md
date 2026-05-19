# USECASE 노드 정의

> ccarch type: `USECASE` / 필수: `title`, `content` / 옵션: `actor`
> USECASE는 REQUIREMENT 다음, ENTITY/COMPONENT/INTERFACE 이전에 등록.
> 등록 후 `DERIVES_FROM`(→ REQUIREMENT), `PERFORMED_BY`(→ ACTOR), `BELONGS_TO`(→ FEATURE) 링크를 작성한다.

## 1. 영상 수집·중계 (feat-video-ingest)

### UC-01. VLM 1차 필터 영상 수집
```json
{
  "type": "USECASE",
  "title": "VLM 1차 필터로 영상-메타 일치도 검증 후 수집",
  "content": "**전제**: 지자체 중계서버가 클립영상 + 메타(카메라 ID, 이벤트 코드, 좌표, 타임스탬프)를 송신했다.\n**기본 흐름**\n1. BATCH_SYSTEM이 ai-server `/infer/vlm/verify-objects`를 호출\n2. VLM이 영상과 메타의 정합성 점수(0~1) 산출\n3. 임계치 ≥ 0.7 → LS_DATA_RAW에 적재, 다음 파이프라인 진입\n4. 임계치 < 0.7 → 별도 분류 영역으로 보내고 분석 큐에 적재\n**대안**: ai-server 응답에 `mock=true` → WARN 로그 발행, 일시적으로 임계치 우회(local 한정)\n\n**PERFORMED_BY**: actor-batch-system\n**BELONGS_TO**: feat-video-ingest\n**DERIVES_FROM**: req-vlm-filter",
  "attrs": {"actor": "BATCH_SYSTEM"},
  "_handle": "uc-video-ingest"
}
```

### UC-02. 오탐·중복 영상 차단
```json
{
  "type": "USECASE",
  "title": "동일 CCTV의 오탐·중복 영상을 후속 수집에서 차단",
  "content": "**전제**: 영상이 수집 큐에 들어왔다.\n**기본 흐름**\n1. 동일 CCTV + 동일 이벤트 코드 + N분 이내 + 시각 유사도 ≥ 임계치 → 중복 판정\n2. 중복 영상은 별도 분류 영역에 보관 (영구 폐기 금지)\n3. 운영 통계에 오탐 유형 학습\n\n**PERFORMED_BY**: actor-batch-system\n**BELONGS_TO**: feat-video-ingest\n**DERIVES_FROM**: req-dedup-ingest",
  "attrs": {"actor": "BATCH_SYSTEM"},
  "_handle": "uc-video-dedup"
}
```

### UC-03. VMS 표준 변환 모듈을 통한 신규 지자체 연동
```json
{
  "type": "USECASE",
  "title": "신규 지자체 VMS 연동 시 변환 모듈을 추가하여 표준 스키마로 수집",
  "content": "**액터**: REVIEWER(연동 요청) + BATCH_SYSTEM(변환 처리)\n**기본 흐름**\n1. REVIEWER가 신규 지자체 정보 + VMS 유형을 등록\n2. 해당 VMS 유형의 독립 변환 모듈(라이브러리)을 호출하여 카메라 ID·이벤트 코드·좌표·영상 포맷을 표준 스키마로 매핑\n3. 동일 유형 VMS는 별도 개발 없이 같은 모듈 재사용\n4. 신규 유형은 변환 모듈 개발 가이드에 따라 라이브러리 추가\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-video-ingest\n**DERIVES_FROM**: req-vms-adapter",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-vms-adapter"
}
```

### UC-04. 중계서버 모니터링·조치
```json
{
  "type": "USECASE",
  "title": "지자체 중계서버를 실시간 모니터링하고 비정상 시 중앙에서 조치",
  "content": "**기본 흐름**\n1. 중앙 시스템이 지자체 중계서버의 자원/연계상태/오류 로그를 폴링\n2. 비정상 상태 감지 시 알림 + 조치 버튼 노출\n3. REVIEWER가 재시작·재연동 등 조치 실행\n4. 조치 이력 기록 (`LS_RELAY_MONITOR_HISTORY`)\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-video-ingest\n**DERIVES_FROM**: req-center-monitor",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-center-monitor"
}
```

## 2. 자동 라벨링 (feat-auto-labeling)

### UC-10. 영상 자동 처리 파이프라인 진행
```json
{
  "type": "USECASE",
  "title": "Quartz가 1건/분 영상을 PENDING → COMPLETED 까지 자동 처리",
  "content": "**전제**: LS_DATA_RAW에 영상이 PENDING 상태로 적재됨.\n**기본 흐름**\n1. Quartz 트리거(interval-sec=60)로 PENDING 영상 1건 선점\n2. FrameExtractStep (FFmpeg) → 프레임 추출, `LS_DATA_SRC` 적재\n3. DeidentifyStep (조건부) → PRVC/PSDO 만 호출\n4. YoloAutolabelStep → AiServerClient.predictYoloTrack\n5. Sam2SegmentStep → AiServerClient.predictSam2Track\n6. TrackInterpolationStep → 트랙 보간\n7. VlmMetaStep → 객체 검증 (V1.7 한정)\n8. COMPLETED 전환\n**대안**\n- 실패 시 `FAILED` + 재시도 큐 (max=3, exp backoff)\n- CircuitBreaker open 시 다음 슬롯으로 연기\n\n**PERFORMED_BY**: actor-batch-system\n**BELONGS_TO**: feat-auto-labeling\n**DERIVES_FROM**: req-batch-orchestrator, req-yolo-sam2",
  "attrs": {"actor": "BATCH_SYSTEM"},
  "_handle": "uc-auto-pipeline"
}
```

### UC-11. 조건부 비식별 처리
```json
{
  "type": "USECASE",
  "title": "영상의 PRVC_TYPE_CD에 따라 비식별 솔루션 조건부 호출",
  "content": "**기본 흐름**\n1. 영상 PRVC_TYPE_CD 확인\n2. PRVC|PSDO → DeidentifyClient.deidentify() 호출 → 원본+비식별본 동시 저장\n3. ANONY → 비식별 호출 없이 원본만 저장\n4. 처리 결과/이력은 LS_DEIDENT_REPORT 에 프레임 단위 기록\n**대안**: 비식별 API 실패 → `DE_IDNTF_YN='F'` 마킹 + 재시도 큐 (원본 절대 삭제 금지)\n\n**PERFORMED_BY**: actor-batch-system\n**BELONGS_TO**: feat-auto-labeling\n**DERIVES_FROM**: req-conditional-deident",
  "attrs": {"actor": "BATCH_SYSTEM"},
  "_handle": "uc-deidentify"
}
```

### UC-12. 비식별 처리 결과 검토
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 비식별 처리 결과·이력을 관제지원시스템에서 확인",
  "content": "**기본 흐름**\n1. REVIEWER가 비식별 목록 화면(`/deident`) 진입\n2. 영상별 비식별 처리 진행/실패 상태 조회\n3. 상세 화면(`/deident/:videoId`)에서 프레임별 비식별 결과 확인\n4. 비식별 솔루션 옵션(블러 강도, 대상 객체 등) 조정 UI\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-auto-labeling\n**DERIVES_FROM**: req-conditional-deident",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-deident-review"
}
```

## 3. 라벨링 저작 (feat-labeling-authoring)

### UC-20. 라벨 조회·편집
```json
{
  "type": "USECASE",
  "title": "WORKER가 본인 배정 프레임의 라벨을 편집한다",
  "content": "**전제**: WORKER가 배정된 작업의 라벨링 페이지(`/label/:id`)에 진입.\n**기본 흐름**\n1. GET `/v1/frames/{srcSn}/labels` → 라벨 목록 조회 (LabelAccessGuard로 본인 검증)\n2. 도구 선택: BBOX / POLYGON / MASK_BRUSH / SAM2_TRACK\n3. konva.js 캔버스에서 라벨 작성·수정\n4. useLabelStore의 dirtyLabels에 변경분 누적\n5. PUT `/v1/frames/{srcSn}/labels` (LabelBulkUpsertRequest) → 커밋 시 Gitea 자동 커밋\n**대안**: 본인 배정 외 프레임 접근 시 403 (IDOR 방어)\n\n**PERFORMED_BY**: actor-worker\n**BELONGS_TO**: feat-labeling-authoring\n**DERIVES_FROM**: req-labeling-canvas",
  "attrs": {"actor": "WORKER"},
  "_handle": "uc-label-edit"
}
```

### UC-21. SAM2 Track 다음 프레임 자동 전파
```json
{
  "type": "USECASE",
  "title": "선택한 폴리곤을 SAM2로 다음 N프레임에 자동 전파",
  "content": "**기본 흐름**\n1. WORKER가 라벨 선택 후 SAM2_TRACK 도구 활성화\n2. ai-server `/infer/sam2/track` 호출 (시작 폴리곤 + 후속 프레임 ID)\n3. 응답으로 받은 프레임별 폴리곤을 캔버스에 시각화\n4. WORKER가 수동 조정 후 일괄 저장\n**비기능**: timeout 60s + CircuitBreaker로 응답 보호\n\n**PERFORMED_BY**: actor-worker\n**BELONGS_TO**: feat-labeling-authoring\n**DERIVES_FROM**: req-labeling-canvas, req-yolo-sam2",
  "attrs": {"actor": "WORKER"},
  "_handle": "uc-sam2-track"
}
```

### UC-22. 라벨 일괄 커밋 → Gitea 자동 커밋
```json
{
  "type": "USECASE",
  "title": "라벨 저장 시 Gitea에 자동 커밋하여 버전 이력을 남긴다",
  "content": "**기본 흐름**\n1. WORKER가 SaveCommitFlow 트리거\n2. BE가 라벨을 `LS_DATA_LBL` 에 upsert\n3. GiteaClient.commit() — frame·변화 카운트 enrichment 포함 한글 커밋 메시지\n4. 커밋 hash를 `LS_DATA_LBL_HSTRY` 에 기록\n5. FE는 VERSION_KEYS·TASK_BOARD_KEYS invalidate\n**대안**\n- Gitea 호출 실패 + stg/prd → fallback 큐로 적재 후 재시도\n- CircuitBreaker open → 사용자에게 안내, 라벨은 DB 우선 저장\n\n**PERFORMED_BY**: actor-worker\n**BELONGS_TO**: feat-labeling-authoring\n**DERIVES_FROM**: req-version-gitea",
  "attrs": {"actor": "WORKER"},
  "_handle": "uc-label-commit"
}
```

### UC-23. 라벨 마스터 풀 관리
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 라벨 마스터(LS_LABEL)와 속성(LS_LABEL_ATTR)을 관리",
  "content": "**기본 흐름**\n1. REVIEWER가 라벨 마스터 화면 진입\n2. 신규 라벨 추가: 이름·색상·카테고리·부모 라벨\n3. 속성 추가: 라벨별 추가 메타(예: 차종, 신뢰도)\n4. 활성 여부 토글\n**제약**: 사용 중인 라벨은 비활성만 가능, 삭제 불가\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-labeling-authoring\n**DERIVES_FROM**: req-label-master-pool",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-label-master"
}
```

## 4. 검수 워크플로우 (feat-review-workflow)

### UC-30. 작업자 배정·재배정
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 WORKER에게 작업을 배정하고 필요 시 재배정",
  "content": "**기본 흐름**\n1. REVIEWER가 작업 보드(`/v1/tasks/board`)에서 미배정 영상 선택\n2. POST `/v1/assignments` (videoId, workerId)\n3. `LS_TASK_ASSIGNMENT` INSERT + LsRawDataStatus 'ASSIGNED' 전환\n**재배정**\n4. PATCH `/v1/assignments/{id}` 로 작업자 변경\n5. `LS_TASK_ASSIGN_HISTORY` 에 변경 이력 기록\n**대안**: 완료 작업(status=COMPLETED|APPROVED)은 재배정 거절 (FE disabled + BE 가드)\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-review-workflow\n**DERIVES_FROM**: req-assign-task",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-assign-task"
}
```

### UC-31. 검수 승인·반려 (낙관적 잠금)
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 라벨을 검수해 승인/반려 결정",
  "content": "**기본 흐름**\n1. REVIEWER가 검수 상세(`/review/:id`) 진입 — frames + labels + issues 조회\n2. 프레임별 라벨 확인 → 이슈 코멘트 작성 가능\n3. 승인: POST `/v1/reviews/{id}/decision` (verdict=APPROVED)\n4. 반려: POST `/v1/reviews/{id}/decision` (verdict=REJECTED + 사유)\n5. LsRawDataStatus.@Version → OptimisticLock\n**대안**: 동시 접근으로 인한 OptimisticLockException → 409 Conflict 응답, FE는 재조회 안내\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-review-workflow\n**DERIVES_FROM**: req-review-state-machine, req-review-detail",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-review-decision"
}
```

### UC-32. 검수 이슈 코멘트
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 프레임별 라벨에 이슈를 등록하고 WORKER가 확인·조치",
  "content": "**기본 흐름**\n1. REVIEWER가 검수 상세에서 라벨 클릭 → 이슈 작성 (LS_DATA_ISSUE)\n2. WORKER가 본인 작업의 이슈 목록 조회\n3. WORKER가 라벨 수정 후 이슈 해결 표시\n4. REVIEWER가 재검수\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-review-workflow\n**DERIVES_FROM**: req-review-detail",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-review-issue"
}
```

## 5. 버전 관리 (feat-version-control)

### UC-40. 버전 diff 조회·롤백
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 특정 커밋의 diff를 조회하고 필요 시 롤백",
  "content": "**기본 흐름**\n1. GET `/v1/versions` — 라벨링 커밋 목록 (한글 메시지, frame·변화 카운트 표시)\n2. 특정 커밋 선택 → GET `/v1/versions/{commit}/diff` (라벨 단위 diff)\n3. 롤백 필요 시 POST `/v1/versions/{commit}/rollback`\n4. Gitea에서 해당 커밋의 라벨을 현재 상태로 복원\n5. VERSION_KEYS·TASK_BOARD_KEYS invalidate\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-version-control\n**DERIVES_FROM**: req-version-gitea",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-version-diff-rollback"
}
```

## 6. 데이터 증강 (feat-augmentation)

### UC-50. 검수 완료 영상 증강 요청 → 검토 → 결정
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 검수 완료 영상에 증강을 요청하고 결과를 검토",
  "content": "**기본 흐름**\n1. REVIEWER가 AugmentRequestPage 진입 — 검수 완료 영상만 선택 가능 (dataSttsCd=COMPLETED)\n2. 증강 조건(WINTER/NIGHT/RAIN/RESOLUTION) 선택 후 POST `/v1/augments/request`\n3. 외부 생성형 AI 시스템이 증강 결과 생성 (V1.5 외부 책임)\n4. 결과 수신 후 AugmentResultPage 에서 검토\n5. POST `/v1/augments/{id}/accept` 또는 `/reject` (반려 사유)\n**라벨 무결성**: 증강 전·후 라벨 보존 검증 (`LS_DATA_AUG_LBL_MAP`)\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-augmentation\n**DERIVES_FROM**: req-augment-completed-only",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-augment-flow"
}
```

## 7. 시계열 메타 검토 (feat-meta-review)

### UC-60. 외부 메타 검토·수정
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 외부 시스템이 생성한 시계열 메타를 프레임 단위로 검토·수정",
  "content": "**기본 흐름**\n1. REVIEWER가 MetaReviewPage(`/auto/:videoId/meta`) 진입\n2. GET `/v1/frames/{srcSn}/meta` — frame 단위 자연어 설명·객체·환경 조건\n3. 수정 시 PUT `/v1/frames/{srcSn}/meta`\n**책임 경계(V1.7)**: 자동 생성·VLM 본체는 외부 시스템. 본 도구는 검토 UI만.\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-meta-review\n**DERIVES_FROM**: req-meta-review",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-meta-review"
}
```

## 8. 침수 시범운영 (feat-flood-pilot)

### UC-70. 침수 탐지 대시보드 + 영상 재생
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 4개 시범기관 침수 탐지 결과를 대시보드로 조회·확인",
  "content": "**기본 흐름**\n1. REVIEWER가 침수 시범 대시보드 진입\n2. 상단 카드: 전체 탐지 건수, 단계별(1/2/3) 건수\n3. 테이블: 탐지 시간 / 침수 단계 / CCTV ID / 탐지 위치\n4. 행 클릭 → 탐지 영상 재생 팝업\n**가이드 배포**: 외부 사용자용 침수탐지 모델 활용 가이드를 영상학습 포털을 통해 공개\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-flood-pilot\n**DERIVES_FROM**: req-flood-pilot",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-flood-dashboard"
}
```

## 9. 관리자/통계 (feat-admin-stat)

### UC-80. GIS 기반 CCTV 자원·사각지대 분석
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 GIS에서 CCTV 자원·사각지대·재난 통계를 시각 분석",
  "content": "**기본 흐름**\n1. REVIEWER가 GIS 화면 진입\n2. 전국 CCTV 좌표·방향각 시각화\n3. 설치 목적별 분류 표시\n4. 사각지대 자동 식별\n5. 재난안전 통계(침수흔적도·인명피해 우려지역) 레이어 오버레이\n6. 예측정보(도시 침수·하천범람) 레이어 오버레이\n7. 학습데이터 분포(CCTV별 이벤트/건수) 시각화\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-admin-stat\n**DERIVES_FROM**: req-gis-cctv",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-gis-view"
}
```

### UC-81. 라벨 프리셋 관리·clone
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 이벤트 타입별 라벨 프리셋을 등록·clone·수정",
  "content": "**기본 흐름**\n1. PresetListPage 진입 → 이벤트 타입별 1:1 프리셋 목록\n2. 신규 프리셋 작성 시 기존 프리셋 clone 후 수정 가능\n3. 프리셋 코드(라벨/속성 매핑) 편집\n4. 영향받는 작업 추적 표시 후 적용\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-admin-stat\n**DERIVES_FROM**: req-label-preset",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-preset-manage"
}
```

### UC-82. 작업자/월별 통계 조회
```json
{
  "type": "USECASE",
  "title": "REVIEWER가 작업자별·월별 통계를 실 집계로 조회",
  "content": "**기본 흐름**\n1. `/stat/overall` 진입 — 월별 처리 영상 수, 라벨 카운트 실 집계\n2. `/stat` 진입 — 작업자별 처리량, labelCount 실 집계\n3. 차트 / 다운로드 (XLSX)\n\n**PERFORMED_BY**: actor-reviewer\n**BELONGS_TO**: feat-admin-stat\n**DERIVES_FROM**: req-worker-stat",
  "attrs": {"actor": "REVIEWER"},
  "_handle": "uc-stat-view"
}
```

## 10. 외부 포털 (feat-external-portal)

### UC-90. 포털 회원가입·로그인
```json
{
  "type": "USECASE",
  "title": "외부 사용자가 포털에 회원가입하고 로그인",
  "content": "**기본 흐름**\n1. 입력 폼(이름/소속/연락처/이메일/활용목적)\n2. 이메일 또는 SMS 인증\n3. ID 중복확인 + 비밀번호 정책(최소 9자, 영문+숫자+특수문자) 검증\n4. 가입 완료 → 로그인 → 마이페이지\n**대안**: 비밀번호 찾기(이메일/SMS), ID 찾기, 회원 탈퇴(데이터 다운로드 이력 보존 + 개인정보 삭제)\n\n**PERFORMED_BY**: actor-portal-user\n**BELONGS_TO**: feat-external-portal\n**DERIVES_FROM**: req-portal-signup",
  "attrs": {"actor": "PORTAL_USER"},
  "_handle": "uc-portal-signup"
}
```

### UC-91. 포털 TUS 업로드 + 본인 영상 라벨링
```json
{
  "type": "USECASE",
  "title": "포털 회원이 본인 영상을 TUS로 재개 가능 업로드하고 간편 라벨링",
  "content": "**기본 흐름**\n1. 포털 회원이 영상 업로드 (TUS 프로토콜, 최대 5GB, 재개 가능)\n2. 확장자 allowlist + MIME 검증\n3. 업로드 완료 시 자동 라벨링 파이프라인 진입 (YOLO+SAM2)\n4. PortalLabelingPage(`/portal/label`)에서 간편 라벨링 체험\n5. 본인 데이터만 접근 가능 (소유자 검증)\n**미제공**: 검수, 버전관리, VLM 메타\n\n**PERFORMED_BY**: actor-portal-user\n**BELONGS_TO**: feat-external-portal\n**DERIVES_FROM**: req-portal-tus-upload, req-portal-simple-label",
  "attrs": {"actor": "PORTAL_USER"},
  "_handle": "uc-portal-upload-label"
}
```

---

## 등록 순서

USECASE 22건은 REQUIREMENT, ACTOR, FEATURE 등록 후 일괄 등록한다. 그 뒤 링크 작성 순서:
1. `USECASE → DERIVES_FROM → REQUIREMENT`
2. `USECASE → PERFORMED_BY → ACTOR`
3. `USECASE → BELONGS_TO → FEATURE`

각 호출 시 새 UUID v4 idempotencyKey 전달.
