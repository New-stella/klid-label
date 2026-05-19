# FEATURE 노드 정의

> ccarch type: `FEATURE` / 필수 필드: `title`, `content` / 옵션: `description`, `owner`
> FEATURE는 USECASE의 그룹핑 단위. USECASE → BELONGS_TO → FEATURE 링크로 묶는다.

## F1. 영상 수집·중계 (Video Ingest)

```json
{
  "type": "FEATURE",
  "title": "영상 수집·중계 (Video Ingest)",
  "content": "지자체 통합관제센터(217개)의 클립영상을 중계서버를 통해 중앙 관제지원시스템으로 수집한다.\n\n**포함 유스케이스**\n- VLM 1차 필터로 영상-메타 일치도 검증 후 수집 (SFR-01)\n- 중복·오탐 이벤트 영상 제외 (SFR-02)\n- VMS 유형별 표준 변환 모듈을 통한 이기종 연동 (SFR-05)\n- 망연계 솔루션을 통한 폐쇄망-중앙망 데이터 전송\n- 영상 전송 내역 확인·승인 (관리자)\n- 영상중계 서버 자원/오류 실시간 모니터링 (SFR-04)\n\n**소속 유스케이스**: uc-video-ingest, uc-video-dedup, uc-vms-adapter, uc-center-monitor",
  "attrs": {"owner": "저작도구팀 (Video/Batch 도메인)"},
  "_handle": "feat-video-ingest"
}
```

## F2. 자동 라벨링 파이프라인 (Auto-Labeling)

```json
{
  "type": "FEATURE",
  "title": "자동 라벨링 파이프라인 (Auto-Labeling)",
  "content": "수집된 영상의 프레임 추출 → 비식별 → YOLO → SAM2 → 트랙 보간을 자동 수행하여 1차 라벨을 생성한다. Quartz Job + Orchestrator + 7 Step 구조.\n\n**포함 유스케이스**\n- 영상 프레임 자동 추출 (FFmpeg)\n- 개인정보 조건부 비식별 처리 (SFR-09)\n- YOLO 자동 객체 감지·추적 (SFR-08)\n- SAM2 세그멘테이션\n- BoT-SORT 트랙 보간 (CVAT portable-modules/01)\n- 배치 실패 시 재시도·서킷 브레이커\n\n**소속 유스케이스**: uc-auto-pipeline, uc-deidentify, uc-yolo-detect, uc-sam2-segment, uc-track-interpolate",
  "attrs": {"owner": "저작도구팀 (Batch/AI-Server 연동)"},
  "_handle": "feat-auto-labeling"
}
```

## F3. 라벨링 저작 (Labeling Authoring)

```json
{
  "type": "FEATURE",
  "title": "라벨링 저작 (Labeling Authoring)",
  "content": "WORKER가 자동 라벨 결과를 검토·수정하고 사람 손으로 정밀 라벨을 작성하는 핵심 기능군. konva.js 기반 캔버스, BBox/Polygon/Mask/SAM2 Track 도구, 좌표·마스크 유틸리티.\n\n**포함 유스케이스**\n- 프레임 라벨 조회·수정 (BBox/Polygon/Segmentation)\n- SAM2 Track 도구로 다음 프레임 자동 전파\n- 라벨 정밀도 조절·외곽 밀착\n- 객체 추적·자동 갱신\n- 라벨 일괄 저장 (commit 시 Gitea 자동 커밋)\n- 라벨 마스터·속성 관리 (CVAT-Like 풀)\n\n**구현 매핑**: frontend/src/features/label, backend label/version 도메인, CVAT portable-modules/01,02,06\n**소속 유스케이스**: uc-label-edit, uc-sam2-track, uc-label-commit, uc-label-master",
  "attrs": {"owner": "저작도구팀 (Label/Version 도메인)"},
  "_handle": "feat-labeling-authoring"
}
```

## F4. 시계열 메타데이터 (Time-series Meta)

```json
{
  "type": "FEATURE",
  "title": "시계열 메타데이터 검토 (Time-series Meta Review)",
  "content": "VLM이 생성한 프레임 단위 자연어 설명·객체/행동 인식·환경 조건 메타데이터를 검수자가 확인·수정한다.\n\n**범위 주의 (V1.7)**: 본 사업 저작도구는 **검토 UI(SCR-AUTO-002)만** 보유. 메타 자동 생성·VLM 시계열 분석 본체는 외부 시스템(SFR-03) 책임.\n\n**포함 유스케이스**\n- 외부 생성 메타 조회 (frame 단위)\n- 메타 수동 수정·확정\n\n**소속 유스케이스**: uc-meta-review, uc-meta-update",
  "attrs": {"owner": "저작도구팀 (Meta 도메인)"},
  "_handle": "feat-meta-review"
}
```

## F5. 검수 워크플로우 (Review Workflow)

```json
{
  "type": "FEATURE",
  "title": "검수 워크플로우 (Review Workflow)",
  "content": "REVIEWER가 WORKER의 라벨을 검토·승인/반려하는 상태 머신. 낙관적 잠금(@Version)으로 동시성 보호.\n\n**상태 머신**: PENDING → ASSIGNED → IN_REVIEW → APPROVED/REJECTED\n\n**포함 유스케이스**\n- 작업자 배정·재배정 (완료 작업은 재배정 차단)\n- 배정 이력 조회 (LS_TASK_ASSIGN_HISTORY)\n- 검수 상세 화면 — 프레임·이슈·라벨 diff 확인\n- 검수 승인·반려, 반려 시 이슈 코멘트\n- 검수 완료 일시 표시(reviewCompletedAt)\n\n**소속 유스케이스**: uc-assign-task, uc-reassign-task, uc-review-decision, uc-review-issue",
  "attrs": {"owner": "저작도구팀 (Assignment/Review 도메인)"},
  "_handle": "feat-review-workflow"
}
```

## F6. 버전 관리 (Version Control)

```json
{
  "type": "FEATURE",
  "title": "버전 관리 (Version Control via Gitea)",
  "content": "라벨 저장 이벤트를 Gitea에 자동 커밋하여 변경 이력·diff·롤백을 지원한다. 커밋 메시지에 frame·변화 카운트 enrichment 적용.\n\n**포함 유스케이스**\n- 라벨 저장 → Gitea 자동 커밋 (한글 커밋 메시지)\n- 작업 이력 조회 (LS_DATA_LBL_HSTRY)\n- 커밋 간 diff 비교 (라벨 단위)\n- 특정 버전으로 롤백\n\n**비기능**: Resilience4j CircuitBreaker(failure-rate 50%, window 10), stg/prd 에서는 fallback 큐 활성화\n**소속 유스케이스**: uc-version-commit, uc-version-diff, uc-version-rollback",
  "attrs": {"owner": "저작도구팀 (Version 도메인)"},
  "_handle": "feat-version-control"
}
```

## F7. 데이터 증강 (Data Augmentation)

```json
{
  "type": "FEATURE",
  "title": "데이터 증강 (Generative Augmentation Review)",
  "content": "검수 완료 영상에 대해 WINTER/NIGHT/RAIN/RESOLUTION 등 조건을 적용한 증강 결과를 검토·승인한다.\n\n**범위 주의 (V1.5)**: 본 사업 저작도구는 **증강 결과 검수(SCR-AUG-002)만** 보유. 생성형 AI 모델(QWEN IMAGE, WAN2.2) 본체·프롬프트 UI는 외부 시스템(SFR-06, SFR-11) 책임. 라벨 무결성(증강 전·후 라벨 보존)은 본 도구 책임.\n\n**포함 유스케이스**\n- 증강 요청 등록 (검수 완료 영상만 허용)\n- 증강 결과 수신·검토\n- 증강 결과 승인/반려 → 학습데이터 인계\n\n**소속 유스케이스**: uc-augment-request, uc-augment-review, uc-augment-decide",
  "attrs": {"owner": "저작도구팀 (Augment 도메인)"},
  "_handle": "feat-augmentation"
}
```

## F8. 침수 탐지 시범운영 (Flood Detection Pilot)

```json
{
  "type": "FEATURE",
  "title": "침수 탐지 시범운영 (Flood Detection Pilot)",
  "content": "1차 사업의 침수 탐지 모델을 4개 지자체(서울 관악구, 경기 안양시, 강원 원주시, 제주특별자치도)에 시범 적용하고 결과 대시보드를 제공한다.\n\n**포함 유스케이스**\n- 침수 이벤트 자동 탐지 (지자체 침수관제 CCTV 연동)\n- 탐지 결과 대시보드 (탐지 시간/단계 1-2-3/CCTV ID/위치)\n- 탐지 영상 재생 팝업\n- 결과 모니터링 + 모델 활용 가이드 배포\n\n**RFP 매핑**: SFR-10\n**소속 유스케이스**: uc-flood-detect, uc-flood-dashboard, uc-flood-guide",
  "attrs": {"owner": "저작도구팀 + AI 모델팀"},
  "_handle": "feat-flood-pilot"
}
```

## F9. AI 영상학습 사용자 포털 (External Portal)

```json
{
  "type": "FEATURE",
  "title": "AI 영상학습 사용자 포털 (External Portal)",
  "content": "외부 기업·연구자 대상 회원 가입·데이터 업로드·간편 라벨링·다운로드 기능을 제공하는 반응형 웹 포털.\n\n**범위 주의 (V1.5)**: 학습데이터 다운로드 카드/D-day 배지/만료 처리 UI는 본 도구 mock에 미제공 → **외부 포털 자체 책임**.\n\n**포함 유스케이스**\n- 회원가입·로그인 (이메일/SMS 인증)\n- 본인 영상 TUS 업로드 (재개 가능)\n- 간편 라벨링(YOLO+SAM2 체험)\n- 본인 데이터 기간 내 다운로드\n- 공지사항·FAQ·매뉴얼\n- 사용 통계 (회원수, 다운로드 카운트)\n\n**비기능**: 반응형(PC/태블릿/모바일), WCAG 2.1 AA, 한국형 웹 콘텐츠 접근성 지침 준수\n**RFP 매핑**: SFR-15\n**소속 유스케이스**: uc-portal-signup, uc-portal-upload, uc-portal-label, uc-portal-download, uc-portal-notice",
  "attrs": {"owner": "저작도구팀 (Portal 도메인)"},
  "_handle": "feat-external-portal"
}
```

## F10. 관리자/통계 (Admin & Statistics)

```json
{
  "type": "FEATURE",
  "title": "관리자/통계 (Admin & Statistics)",
  "content": "REVIEWER가 사용하는 사용자 관리·시스템 설정·프리셋·통계·대시보드 기능.\n\n**포함 유스케이스**\n- 사용자 관리 (`/manage/users`)\n- 시스템 설정 (`/manage/sysconfig`) — Caffeine TTL 60s 캐시\n- 라벨 프리셋(`/manage/presets`) — 이벤트 타입별 1:1 매핑, clone 지원\n- 작업자 통계 (`/stat`) — labelCount 실 집계\n- 전체 통계 (`/stat/overall`) — 월별, 처리 현황, 다운로드 통계\n- GIS 기반 CCTV 자원 시각화·사각지대 분석 (SFR-14)\n- 모바일 공무원증 로그인·IP 접근 제어 (SFR-14)\n\n**RFP 매핑**: SFR-14 (관제지원시스템 기능 개발 및 고도화)\n**소속 유스케이스**: uc-user-manage, uc-sysconfig-manage, uc-preset-manage, uc-worker-stat, uc-overall-stat, uc-gis-view",
  "attrs": {"owner": "저작도구팀 (User/Sysconfig/Preset/Stat 도메인)"},
  "_handle": "feat-admin-stat"
}
```

---

## 등록 순서

FEATURE는 USECASE보다 먼저 등록한다 (BELONGS_TO 링크의 target).

1. feat-video-ingest
2. feat-auto-labeling
3. feat-labeling-authoring
4. feat-meta-review
5. feat-review-workflow
6. feat-version-control
7. feat-augmentation
8. feat-flood-pilot
9. feat-external-portal
10. feat-admin-stat
