# FEATURE 노드 정의

> 저작도구 서브시스템이 직접 책임지는 기능군. USECASE → BELONGS_TO → FEATURE 링크로 그룹핑.
>
> 영상 수집·중계·CCTV 연동·관제지원시스템 자체 기능·침수 시범은 본 도구 책임 외이므로 FEATURE 로 등록하지 않는다.

## F1. 자동 라벨링 파이프라인 (Auto-Labeling)

```json
{
  "type": "FEATURE",
  "title": "자동 라벨링 파이프라인 (Auto-Labeling)",
  "content": "LS_DATA_RAW 에 적재된 영상에 대해 Quartz Job 이 프레임 추출 → 비식별 → YOLO → SAM2 를 자동 수행하여 1차 라벨을 생성한다. YOLO/SAM2 추론은 본 도구의 ai-server(Stateless FastAPI)에서 수행된다.\n\n**포함 유스케이스**\n- 영상 자동 처리 파이프라인 진행 (uc-auto-pipeline)\n- 비식별 처리 (uc-deidentify) — 모든 영상에 대해 외부 비식별 솔루션 호출, 원본+비식별본 동시 저장\n- 비식별 처리 결과 검토 (uc-deident-review)",
  "attrs": {"owner": "Batch / ai-server"},
  "_handle": "feat-auto-labeling"
}
```

## F2. 라벨링 저작 (Labeling Authoring)

```json
{
  "type": "FEATURE",
  "title": "라벨링 저작 (Labeling Authoring)",
  "content": "WORKER 가 자동 라벨 결과를 검토·수정하고 사람 손으로 정밀 라벨을 작성하는 핵심 기능군. konva.js 캔버스 + BBox/Polygon/Mask/SAM2 Track 도구 + 라벨 마스터 풀.\n\n**포함 유스케이스**\n- 라벨 조회·편집 (uc-label-edit)\n- SAM2 Track 다음 프레임 자동 전파 (uc-sam2-track)\n- 라벨 마스터·속성 관리 (uc-label-master)",
  "attrs": {"owner": "Label 도메인"},
  "_handle": "feat-labeling-authoring"
}
```

## F3. 시계열 메타 생성·검토 (VLM Time-series)

```json
{
  "type": "FEATURE",
  "title": "시계열 메타 생성·검토 (VLM Time-series)",
  "content": "본 도구가 외부 VLM 서비스를 호출하여 시계열 메타를 생성하고, REVIEWER 가 결과를 검토·승인/반려한다. 영상 단위 일반 메타는 본 도구 책임 외이므로 본 기능 대상이 아니다.\n\n**포함 유스케이스**\n- 외부 VLM 시계열 호출 (uc-vlm-timeseries-request)\n- VLM 자동 생성 메타 적재 (LS_DATA_META.META_TYPE_CD='VLM' + LS_DATA_META_REVIEW.RVW_STTS_CD='AUTO_GENERATED|PENDING')\n- REVIEWER 메타 검토·승인·반려 (uc-meta-review)\n- 외부 시스템 인계 메타(META_TYPE_CD='EXTERNAL')가 있는 경우 동일 검토 흐름 사용",
  "attrs": {"owner": "Meta 도메인"},
  "_handle": "feat-meta-pipeline"
}
```

## F4. 검수 워크플로우 (Review Workflow)

```json
{
  "type": "FEATURE",
  "title": "검수 워크플로우 (Review Workflow)",
  "content": "REVIEWER 가 WORKER 의 라벨을 검토·승인/반려하는 상태 머신. 낙관적 잠금(@Version)으로 동시성 보호. 검수 완료(=작업 완료) 시점과 검수 완료 후 수정 발생 시 관제서버에 outbound 통지(영상 단위)를 발행한다.\n\n**상태 머신**: PENDING → ASSIGNED → IN_REVIEW → APPROVED/REJECTED → (COMPLETED 전이 시 TASK_COMPLETED 통지 발행) → (수정 시 TASK_MODIFIED 통지 발행)\n\n**작업 단위 정책**\n- 작업 단위 = 영상 1건. 프로젝트 단위 개념 사용하지 않는다.\n- 작업 식별자는 영상 단위 ID(`LS_DATA_RAW.RAW_SN`).\n- 완료 후 수정 시 동일 작업 ID 유지, 버전 업 금지 — 이벤트 타입(`TASK_MODIFIED`)으로 구분.\n\n**포함 유스케이스**\n- 작업자 배정·재배정 (uc-assign-task) — 완료 작업 재배정 차단\n- 검수 승인·반려 (uc-review-decision)\n- 검수 완료 통지 발행 (uc-task-completion-notify) — TASK_COMPLETED outbound\n- 검수 완료 후 수정 통지 발행 (uc-task-modification-notify) — TASK_MODIFIED outbound",
  "attrs": {"owner": "Assignment/Review 도메인"},
  "_handle": "feat-review-workflow"
}
```

## F5. 버전 관리 (Version Control via Gitea)

```json
{
  "type": "FEATURE",
  "title": "버전 관리 (Version Control via Gitea)",
  "content": "라벨 저장 이벤트마다 Gitea 에 동기 자동 커밋하여 변경 이력·diff·롤백을 지원한다. 커밋 메시지에 frame·변화 카운트 enrichment.\n\n**포함 유스케이스**\n- 라벨 저장 → Gitea 동기 자동 커밋 (uc-label-commit)\n- 작업 이력 조회 + 커밋 간 diff + 롤백 (uc-version-diff-rollback)\n\n**비기능**: 외부 Gitea 연동은 동기 REST — timeout 70s + CircuitBreaker(50%/window10) + Retry max=3+exp backoff. CircuitBreaker open 등 장애 시에만 fallback 큐로 비동기 재시도 (stg/prd 에서 활성화)",
  "attrs": {"owner": "Version 도메인"},
  "_handle": "feat-version-control"
}
```

## F6. 데이터 증강 결과 검수 (Augmentation Review)

```json
{
  "type": "FEATURE",
  "title": "데이터 증강 결과 검수 (Augmentation Review)",
  "content": "검수 완료 영상에 대한 증강 요청을 외부 생성형 AI 시스템에 인계하고, 결과를 수신해 검수한다. 생성형 AI 모델 본체·프롬프트 UI 는 외부 책임이며, 본 도구는 요청·수신·검수·라벨 무결성 확인을 담당한다.\n\n**포함 유스케이스**\n- 증강 요청 인계 + 결과 검수 (uc-augment-flow) — 검수 완료 영상만 허용",
  "attrs": {"owner": "Augment 도메인"},
  "_handle": "feat-augmentation"
}
```

## F7. 포털 + 관리자·통계 (Portal & Admin)

```json
{
  "type": "FEATURE",
  "title": "포털 + 관리자·통계 (Portal & Admin)",
  "content": "외부 채널 사용자가 본 도구에 진입한 후의 저작도구 기능과 내부 채널 REVIEWER 의 관리 기능을 함께 다룬다.\n\n**포털 사용자 (PORTAL_USER 대상)**\n- 본인 데이터 간편 라벨링 (uc-portal-label) — 회원가입·업로드·다운로드는 외부 포털 책임\n\n**관리자 (REVIEWER 대상)**\n- 라벨 프리셋 관리 (uc-preset-manage) — 이벤트 타입 1:1 + clone\n- 사용자/시스템 설정 화면 (`/manage/*`)\n\n**인증 인계 (사용자 진입점)**\n- 관제/포털 JWT 검증 후 채널·역할 분기 (uc-jwt-ingress)\n\n**비기능**: 반응형 웹(1024×768~1920×1080), WCAG 2.1, 멀티브라우저",
  "attrs": {"owner": "Portal / User / Sysconfig / Preset 도메인"},
  "_handle": "feat-portal-and-admin"
}
```

---

## 등록 순서

USECASE 보다 먼저 등록 (BELONGS_TO 링크의 target).

1. feat-auto-labeling
2. feat-labeling-authoring
3. feat-meta-pipeline
4. feat-review-workflow
5. feat-version-control
6. feat-augmentation
7. feat-portal-and-admin
