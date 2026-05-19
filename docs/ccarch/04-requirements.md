# REQUIREMENT 노드 정의

> ccarch type: `REQUIREMENT` / 필수: `title`, `content` / 옵션: `priority`, `category`(FUNCTIONAL|NON_FUNCTIONAL)
> 본 사업 저작도구가 실제 책임지는 범위를 명확히 한 정제된 요구사항. `DERIVES_FROM` 으로 SOURCE_REQUIREMENT에 매핑한다.

## 1. 기능 요구사항 — 영상 수집·중계

### REQ-001. VLM 1차 필터 영상 정합성 검증
```json
{
  "type": "REQUIREMENT",
  "title": "VLM 1차 필터로 클립영상-메타 정합성을 검증한다",
  "content": "재난안전 이벤트 6종(침수/화재(산불)/쓰러짐/폭력/교통사고/납치(유괴)) 클립영상이 중계서버에서 수집되면, VLM 모델이 영상 내용과 클립 메타데이터(카메라 ID, 이벤트 코드, 좌표, 타임스탬프) 간 정량적 일치도를 산출한다. 일치도 임계치 이상인 영상만 후속 파이프라인(프레임 추출·라벨링)으로 진입시키고, 불일치 영상은 분석용 별도 영역에 분류·보관한다.\n\n**상세 책임 경계**\n- 본 사업 저작도구: VLM 호출·결과 수신·정합성 판정·후속 라우팅\n- 외부: VLM 모델 자체의 학습·하이퍼파라미터 튜닝\n- 6종 이벤트는 확장 가능한 형태로 코드/카테고리 관리\n\n**DERIVES_FROM**: sr-sfr-01\n**REALIZES (대상)**: comp-batch-orchestrator, comp-ai-server, if-vlm-verify",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-vlm-filter"
}
```

### REQ-002. 오탐·중복 클립영상 수집 방지
```json
{
  "type": "REQUIREMENT",
  "title": "동일 CCTV에서 반복되는 오탐 클립영상을 중복 수집 차단한다",
  "content": "같은 CCTV에서 짧은 주기로 반복되는 화재·쓰러짐 등 동일형태 이벤트(예: 붉은 라이트를 불로 오인)는 1차 사업의 이벤트 코드만으로는 변별이 불가능하므로, 클립영상의 해시·인접 시간대 그룹·시각 유사도 기반의 중복 검증 룰을 마련해 후속 수집을 차단한다.\n\n**기준**\n- 동일 CCTV + 동일 이벤트 코드 + N분 이내 + 시각 유사도 임계치 이상 → 중복 판정\n- 중복 판정 시 관제지원시스템 내 별도 분류·관리 (영구 폐기 금지)\n- 오탐 유형은 운영 중 학습되도록 통계화\n\n**DERIVES_FROM**: sr-sfr-02\n**REALIZES (대상)**: comp-video-ingest-service",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-dedup-ingest"
}
```

### REQ-003. 지자체 중계시스템 모니터링
```json
{
  "type": "REQUIREMENT",
  "title": "지자체 중계서버 자원·연계상태·오류를 중앙에서 실시간 모니터링한다",
  "content": "영상수집 지자체의 중계서버 자원 사용률(CPU/MEM/DISK), 연계모듈 상태, 이벤트 수집 현황, 오류 로그를 중앙 관제지원시스템에서 실시간 수집한다. 연계모듈 비정상 상태 시 중앙에서 조치(재시작·재연동) 가능하며 조치 이력을 관리한다. 연계 대상 지자체 추가 시 확장 가능한 구조로 설계.\n\n**DERIVES_FROM**: sr-sfr-04\n**REALIZES (대상)**: comp-center-monitor-service",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-center-monitor"
}
```

### REQ-004. 이기종 VMS 표준 변환 모듈
```json
{
  "type": "REQUIREMENT",
  "title": "VMS 유형별 데이터 구조를 표준 스키마로 변환하는 모듈 체계를 제공한다",
  "content": "1차 사업의 표준 데이터 구조 위에, 신규 지자체 VMS 유형별로 독립 변환 모듈(SDK/API 호출 → 표준 형태 매핑)을 라이브러리로 개발한다. 카메라 ID·이벤트 코드·좌표 체계·영상 포맷 매핑 규칙과 매핑 테이블 관리 기능 포함. 동일 유형 VMS 추가 시 별도 개발 없이 재사용 가능해야 한다. 신규 연동 시 변환 모듈 개발 가이드 제공.\n\n**DERIVES_FROM**: sr-sfr-05\n**REALIZES (대상)**: comp-vms-adapter",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-vms-adapter"
}
```

### REQ-005. 망연계 솔루션 연동
```json
{
  "type": "REQUIREMENT",
  "title": "통합관제센터(폐쇄망) ↔ 중앙(개방망) 망연동 체계를 구축한다",
  "content": "통합관제센터 중계서버에 저장된 클립영상을 중앙으로 전송하기 위해 발주기관이 SW 직접구매로 제공하는 망연계 솔루션을 활용한 안전한 연계체계를 구축한다. 솔루션을 통해 관제지원시스템과의 연계 기능을 개발하고, 5개 신규 연동 지자체에 망연계 환경을 구축한다.\n\n**HW 규격(분리발주)**: x86 서버(CPU 16 core, MEM 768GB, SSD 960GB×4 + 2.4TB HDD×8, GPU L40 이상), L2 스위치(10G/1G), 망연계 SW 5식\n\n**DERIVES_FROM**: sr-sfr-05\n**REALIZES (대상)**: comp-network-bridge",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-network-bridge"
}
```

## 2. 기능 요구사항 — 자동 라벨링 파이프라인

### REQ-010. 영상 자동 처리 파이프라인 (Quartz Orchestrator)
```json
{
  "type": "REQUIREMENT",
  "title": "Quartz Job으로 영상 처리 파이프라인을 1건/분 자동 수행한다",
  "content": "수집된 영상은 Quartz 트리거(interval-sec=60)로 1건/분 간격 처리한다. 상태 머신: PENDING → FRAME_EXTRACT → DEIDENTIFY → YOLO → SAM2 → TRACK_INTERPOLATION → VLM_META → COMPLETED. 실패 시 max-attempts=3, 지수 백오프(1s × 2^n). 단계별 상태와 오류는 `LS_BATCH_PROC_LOG`에 기록한다. local 프로파일은 자동 배치 비활성(수동 트리거), dev/stg/prd는 자동 활성.\n\n**DERIVES_FROM**: sr-sfr-01, sr-sfr-08\n**REALIZES (대상)**: comp-batch-orchestrator, ent-data-raw, ent-batch-log",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-batch-orchestrator"
}
```

### REQ-011. 조건부 비식별 처리
```json
{
  "type": "REQUIREMENT",
  "title": "영상의 PRVC_TYPE_CD에 따라 비식별 솔루션을 조건부 호출한다",
  "content": "`LS_DATA_RAW.PRVC_TYPE_CD` 값에 따라 분기:\n- `PRVC`(개인정보 포함) 또는 `PSDO`(가명) → 비식별 솔루션(`DeidentifyClient`) 호출하여 원본+비식별본 별도 경로 동시 저장\n- `ANONY`(익명) → 원본만 저장, 비식별 호출 없음\n\n비식별 API 실패 시 영상 상태를 `DE_IDNTF_YN='F'`로 마킹하고 재시도 큐에 적재. **원본은 절대 삭제 금지**. 비식별 처리 결과/이력은 프레임 단위로 `LS_DEIDENT_REPORT`에 기록. 비식별 솔루션 옵션은 관제지원시스템 UI로 노출.\n\n**DERIVES_FROM**: sr-sfr-09\n**REALIZES (대상)**: comp-deidentify-client, ent-deident-report",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-conditional-deident"
}
```

### REQ-012. YOLO/SAM2 자동 라벨링
```json
{
  "type": "REQUIREMENT",
  "title": "YOLO 객체 감지 + SAM2 세그멘테이션으로 1차 라벨을 생성한다",
  "content": "ai-server FastAPI 라우터를 통해 YOLO(`/infer/yolo/predict`, `/infer/yolo/track`)와 SAM2(`/infer/sam2/segment`, `/infer/sam2/track`)를 호출하여 프레임 단위 자동 라벨을 생성한다. AI 서버는 Stateless 추론 전용으로 인증·DB 없음. 호출은 `AiServerClient` Bean으로 단일화, timeout 60s + Resilience4j CircuitBreaker(failure-rate 50%, window 10) 적용. AI_MOCK_MODE 또는 가중치 미존재 시 mock 응답이 오면 BE는 `mock=true` 감지하여 WARN 로그 발행.\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: comp-ai-server, comp-ai-server-client, if-yolo, if-sam2",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-yolo-sam2"
}
```

### REQ-013. 트랙 보간 (BoT-SORT)
```json
{
  "type": "REQUIREMENT",
  "title": "CVAT 트랙 보간 알고리즘을 Java로 포팅하여 프레임 간 BBox를 자동 보간한다",
  "content": "CVAT 원본의 트랙 보간 모듈(`docs/analysis/portable-modules/01-track-interpolation.md`)을 Java로 재구현한다. YOLO/BoT-SORT가 부여한 trackId 기반으로 연속 프레임 간 BBox 위치·크기를 선형 보간. 보간된 라벨은 `lblSrcCd='INTERPOLATED'`로 표시되어 후속 검수 시 식별 가능.\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: comp-track-interpolation",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-track-interpolate"
}
```

## 3. 기능 요구사항 — 라벨링 저작도구

### REQ-020. 라벨링 캔버스 (BBox/Polygon/Segmentation/Track)
```json
{
  "type": "REQUIREMENT",
  "title": "konva.js 기반 라벨링 캔버스로 4종 라벨 타입을 지원한다",
  "content": "**도구**: SELECT / BBOX / POLYGON / MASK_BRUSH / MASK_ERASER / SAM2_TRACK / PAN\n**캔버스 레이어**: ImageLayer / LabelsLayer / OverlayLayer (Konva Stage)\n**유틸**: 좌표 변환(이미지 ↔ 캔버스, zoom/pan 반영), polygon 기하학, MASK ↔ RLE ↔ Polygon 변환 (CVAT portable-modules/02), 충돌 감지\n**dirty 추적**: useLabelStore의 dirtyLabels Set → SaveCommitFlow → commit API\n**SAM2 Track 도구**: 선택 폴리곤을 다음 프레임에 자동 전파 (ai-server `/infer/sam2/track` 호출)\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: comp-label-canvas, if-label-api",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-labeling-canvas"
}
```

### REQ-021. 라벨 정확도 향상 — 외곽 밀착·정밀도 조절
```json
{
  "type": "REQUIREMENT",
  "title": "사용자 지정 객체를 추적해 위치·경계를 자동 갱신하고 외곽 밀착·정밀도를 조절한다",
  "content": "**핵심**\n- 객체 추적 자동 갱신 (BoT-SORT trackId 기반 위치·경계)\n- 외곽 경계 자동 밀착 (Snap to edge)\n- 라벨링 정밀도 조절 (vertex 수, 스무딩 강도)\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: comp-label-canvas (도구 옵션)",
  "attrs": {"priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-label-precision"
}
```

### REQ-022. 라벨 마스터 풀 (CVAT-Like)
```json
{
  "type": "REQUIREMENT",
  "title": "라벨 마스터 풀(LS_LABEL)을 통해 라벨 종류·색상·속성을 중앙 관리한다",
  "content": "CVAT-Like 라벨 풀 구조 (Flyway V31~V37): `LS_LABEL` (마스터) → 1:N `LS_LABEL_ATTR` (속성 정의) → 1:N `LS_DATA_LBL_ATTR_VAL` (값). 라벨 마스터에는 color(#RRGGBB), 카테고리, 부모 라벨 관계, 활성 여부 등 보유. 라벨 작성 시 데이터-라벨 FK 마이그레이션(best-effort).\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: ent-label-master, ent-label-attr",
  "attrs": {"priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-label-master-pool"
}
```

## 4. 기능 요구사항 — 검수 워크플로우

### REQ-030. 작업자 배정·재배정
```json
{
  "type": "REQUIREMENT",
  "title": "REVIEWER가 WORKER에게 라벨링 작업을 배정·재배정한다",
  "content": "**규칙**\n- REVIEWER만 배정 권한 보유 (V1.3에서 ADMIN 권한이 REVIEWER에 흡수)\n- `LS_TASK_ASSIGNMENT` (TASK_TYPE_CD='LABELER') INSERT, 재배정 시 `LS_TASK_ASSIGN_HISTORY` 기록\n- **완료된 작업의 재배정 차단** (FE UI에서 disabled + BE에서 가드)\n- 배정 이력 조회 권한도 REVIEWER 보유\n\n**API**: POST `/v1/assignments`, PATCH `/v1/assignments/{id}`\n\n**DERIVES_FROM**: sr-sfr-14, sr-sfr-08\n**REALIZES (대상)**: comp-assignment-service, ent-task-assignment, if-assignment-api",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-assign-task"
}
```

### REQ-031. 검수 상태 머신 + 낙관적 잠금
```json
{
  "type": "REQUIREMENT",
  "title": "검수 워크플로우 상태 머신을 낙관적 잠금(@Version)으로 보호한다",
  "content": "**상태**: PENDING → ASSIGNED → IN_REVIEW → APPROVED / REJECTED\n**동시성**: `LsRawDataStatus.@Version` 으로 OptimisticLockException 방지 — 두 검수자가 동시에 같은 작업에 진입해 상태 전이 시 두 번째는 409 Conflict 반환\n**검수 완료 일시**: `reviewCompletedAt` 필드 별도 기록\n**검수 상태 필터**: `reviewStatusCd`로 영상 목록 조회 가능\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: ent-raw-data-status, comp-review-service",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-review-state-machine"
}
```

### REQ-032. 검수 상세 화면 + 이슈 코멘트
```json
{
  "type": "REQUIREMENT",
  "title": "검수 상세 화면에서 프레임·라벨·이슈·diff를 조회하고 승인/반려 결정한다",
  "content": "**화면 책임**: ReviewListPage(검수 대기 목록), ReviewPage(검수 상세 — 프레임 캐러셀, 라벨 시각화, 이슈 코멘트, 결정 버튼)\n**API**: GET `/v1/reviews` (페이징, eventName/eventTypeCd enrich), GET `/v1/reviews/{id}/frames`, POST `/v1/reviews/{id}/decision`, GET/POST `/v1/reviews/{id}/issues`\n**DTO**: ReviewResponse(7-arg, eventName/eventTypeCd 포함, BE에서 enrich)\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: comp-review-service, ent-data-issue, if-review-api",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-review-detail"
}
```

## 5. 기능 요구사항 — 버전 관리

### REQ-040. Gitea 자동 커밋·diff·롤백
```json
{
  "type": "REQUIREMENT",
  "title": "라벨 저장 이벤트마다 Gitea에 자동 커밋하고 diff·롤백을 제공한다",
  "content": "**커밋**: SaveCommitFlow 종료 시 `GiteaClient.commit()` 호출, frame·변화 카운트 enrichment + 한글 커밋 메시지. `LS_DATA_LBL_HSTRY`에 commit hash 저장.\n**diff**: GET `/v1/versions/{commit}/diff` — 라벨 단위로 BE 응답 형식 정합\n**rollback**: POST `/v1/versions/{commit}/rollback`\n**Resilience4j**: timeout 70s, CircuitBreaker(failure-rate 50%, window 10), stg/prd 에서 Gitea fallback 큐 활성화\n\n**FE 캐시 무효화**: VERSION_KEYS / TASK_BOARD_KEYS invalidate 필수\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: comp-version-service, comp-gitea-client, if-version-api",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-version-gitea"
}
```

## 6. 기능 요구사항 — 데이터 증강

### REQ-050. 검수 완료 영상만 증강 요청 허용
```json
{
  "type": "REQUIREMENT",
  "title": "검수 완료된 영상에 한해 데이터 증강 요청을 허용한다",
  "content": "FE의 AugmentRequestPage는 `useVideos({dataSttsCd: 'COMPLETED'})` 로만 영상 목록을 조회 (size:20 페이징). BE는 증강 요청 시 영상 상태가 COMPLETED 인지 재확인하여 그 외는 거절. 증강 후 라벨 무결성(증강 전·후 라벨 보존)은 본 도구 책임.\n\n**범위 주의(V1.5)**: 증강 결과 검수(SCR-AUG-002)만 보유. 생성형 AI 모델(QWEN IMAGE, WAN2.2)·프롬프트 UI는 외부 책임.\n\n**API**: POST `/v1/augments/request`, GET `/v1/augments`, POST `/v1/augments/{id}/accept|reject`\n\n**DERIVES_FROM**: sr-sfr-06, sr-sfr-07\n**REALIZES (대상)**: comp-augment-service, ent-data-aug, if-augment-api",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-augment-completed-only"
}
```

## 7. 기능 요구사항 — 시계열 메타 / VLM 객체 검증

### REQ-060. 외부 생성 시계열 메타 검토 UI
```json
{
  "type": "REQUIREMENT",
  "title": "외부 시스템이 생성한 시계열 메타데이터를 SCR-AUTO-002 화면에서 검토·수정한다",
  "content": "**책임 경계(V1.7)**\n- 외부: VLM 시계열 분석으로 프레임 단위 자연어 설명·객체/행동·환경 조건 생성\n- 본 도구: 생성된 메타를 frame 단위로 조회·수정·확정하는 UI 제공\n\n**API**: GET/PUT `/v1/frames/{srcSn}/meta`\n**DTO**: MetaResponse (frame별 자연어 설명·객체 카테고리·환경조건)\n\n**DERIVES_FROM**: sr-sfr-03, sr-sfr-12\n**REALIZES (대상)**: comp-meta-service, ent-data-meta, if-meta-api",
  "attrs": {"priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-meta-review"
}
```

### REQ-061. VLM 기반 객체 검증 (한정 범위)
```json
{
  "type": "REQUIREMENT",
  "title": "YOLO/SAM2가 감지한 객체의 분류 정합성을 VLM으로 검증한다",
  "content": "ai-server `/infer/vlm/verify-objects` 엔드포인트로 YOLO/SAM2 결과 객체의 분류 정합성을 사후 검증한다. 검증 결과는 라벨의 confidence·source 메타에 반영되며, 시계열 메타 자동 추출(자연어 설명 등 광범위 VLM 메타)은 본 도구 범위 외.\n\n**DERIVES_FROM**: sr-sfr-12\n**REALIZES (대상)**: comp-ai-server, if-vlm-verify",
  "attrs": {"priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-vlm-verify-objects"
}
```

## 8. 기능 요구사항 — 침수 시범운영

### REQ-070. 침수 탐지 시범 대시보드
```json
{
  "type": "REQUIREMENT",
  "title": "4개 시범기관 침수 탐지 결과를 모니터링 대시보드로 제공한다",
  "content": "**시범기관(4)**: 서울 관악구, 경기 안양시, 강원 원주시, 제주특별자치도\n**대시보드 화면**\n- 침수 탐지 목록 테이블: 탐지 시간 / 침수 단계(1/2/3) / CCTV ID / 탐지 위치\n- 상단 요약 카드: 전체 탐지 건수, 침수 단계별 건수\n- 행 클릭 → 탐지 영상 재생 팝업\n- 웹 브라우저 전용 (모바일 미지원)\n**HW**: 시범기관에 기 도입된 x86/Linux 비식별 서버에 모델 배포\n**가이드**: 영상학습 포털 공개용 활용 가이드 작성·배포\n\n**DERIVES_FROM**: sr-sfr-10\n**REALIZES (대상)**: comp-flood-pilot, if-flood-api",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-flood-pilot"
}
```

## 9. 기능 요구사항 — 관제지원시스템 (관리자/통계)

### REQ-080. GIS 기반 CCTV 자원 시각화 + 사각지대 분석
```json
{
  "type": "REQUIREMENT",
  "title": "전국 지자체 CCTV 좌표·방향각을 GIS 시각화하고 설치 위치·사각지대를 분석한다",
  "content": "**핵심**\n- CCTV 좌표·방향각(상하/좌우) GIS 시각화\n- 설치 위치 분석 + 사각지대 식별\n- 재난안전 통계정보(침수흔적도, 인명피해 우려지역) 지도 기반 시각화\n- 예측정보(도시 침수, 하천범람지도) 지도 기반\n- 학습데이터 분포 GIS 시각화 (CCTV별 이벤트명/건수 집계)\n\n**DERIVES_FROM**: sr-sfr-14\n**REALIZES (대상)**: comp-stat-gis-service",
  "attrs": {"priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-gis-cctv"
}
```

### REQ-081. 모바일 공무원증 로그인 + IP 접근 제어
```json
{
  "type": "REQUIREMENT",
  "title": "내부사용자 인증을 모바일 공무원증으로 확장하고 사용자별 접속 가능 IP를 제어한다",
  "content": "**핵심**\n- 모바일 공무원증 인증 방식 로그인 적용 검토·개발 (관제서버 발급 JWT와 병행)\n- 광역담당자/기초담당자 권한 세분화\n- 사용자별 접속 가능 IP 화이트리스트 설정\n- 시스템 소개·매뉴얼·도움말·FAQ 신규 추가\n- 내부 사용자 서비스 요청 관리\n\n**DERIVES_FROM**: sr-sfr-14, sr-ser-01\n**REALIZES (대상)**: comp-jwt-filter, comp-user-service",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-mobile-cert-login"
}
```

### REQ-082. 라벨 프리셋 — 이벤트 타입 1:1 매핑
```json
{
  "type": "REQUIREMENT",
  "title": "이벤트 타입별 라벨 프리셋(LS_LABEL_PRESET)을 1:1로 관리하고 clone을 지원한다",
  "content": "**구조**\n- `LS_LABEL_PRESET` Aggregate Root → 1:N `LS_LABEL_PRESET_CODE`\n- 이벤트 타입(침수/화재/쓰러짐/폭력/교통사고/유괴 6종)별 1:1 매핑\n- 신규 프리셋 작성 시 기존 프리셋 clone 후 수정 가능\n- 프리셋 변경 시 영향받는 라벨링 작업 추적\n\n**API**: GET/POST/PUT/DELETE `/v1/presets`\n\n**DERIVES_FROM**: sr-sfr-08\n**REALIZES (대상)**: comp-preset-service, ent-label-preset",
  "attrs": {"priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-label-preset"
}
```

### REQ-083. 작업자 통계 + 월별 labelCount 실 집계
```json
{
  "type": "REQUIREMENT",
  "title": "작업자 통계와 월별 labelCount를 실 집계로 제공한다",
  "content": "**화면**: `/stat` (작업자 통계), `/stat/overall` (전체 통계)\n**특이**: labelCount는 가짜 카운트가 아닌 `LS_DATA_LBL` 실 집계 (직전 hotfix 사항 — 가짜 카운트는 제거)\n**API**: GET `/v1/stats/workers`, GET `/v1/stats/overall`\n\n**DERIVES_FROM**: sr-sfr-14\n**REALIZES (대상)**: comp-stat-service, if-stat-api",
  "attrs": {"priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-worker-stat"
}
```

### REQ-084. 시스템 설정 캐시 (Caffeine TTL 60s)
```json
{
  "type": "REQUIREMENT",
  "title": "시스템 설정(LS_SYSTEM_CONFIG)을 Caffeine TTL 60s로 캐싱한다",
  "content": "운영 중 자주 변경되지 않으나 빈번히 조회되는 시스템 설정값(예: 배치 간격, 라벨 색상 기본값, 비식별 옵션)을 Caffeine 캐시로 보호. TTL 60초, 변경 시 즉시 무효화. `/manage/sysconfig` 화면에서 REVIEWER가 수정.\n\n**DERIVES_FROM**: sr-sfr-14\n**REALIZES (대상)**: comp-sysconfig-service",
  "attrs": {"priority": "LOW", "category": "FUNCTIONAL"},
  "_handle": "req-sysconfig-cache"
}
```

## 10. 기능 요구사항 — 외부 포털

### REQ-090. 포털 회원가입·인증
```json
{
  "type": "REQUIREMENT",
  "title": "포털 회원가입은 이메일/SMS 인증 + 비밀번호 정책으로 처리한다",
  "content": "**핵심**\n- 회원가입 시 이메일 또는 휴대폰 인증 본인확인\n- 입력 폼: 이름, 소속 기관, 연락처, 이메일, 활용 목적\n- ID 중복확인, 비밀번호 정책(최소 9자, 영문+숫자+특수문자)\n- 비밀번호 찾기(이메일/SMS), ID 찾기, 마이페이지(연락처/소속/비밀번호 변경)\n- 회원 탈퇴 시 데이터 다운로드 이력·활용 정보 보존 후 개인정보 삭제\n- 관리자가 부적절 계정 삭제 가능\n\n**DERIVES_FROM**: sr-sfr-15, sr-ser-01\n**REALIZES (대상)**: comp-portal-auth-service",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-portal-signup"
}
```

### REQ-091. 포털 TUS 재개 가능 업로드 (5GB)
```json
{
  "type": "REQUIREMENT",
  "title": "포털 사용자 영상 업로드는 TUS 프로토콜로 5GB까지 재개 가능하게 처리한다",
  "content": "**핵심**\n- TUS 프로토콜 (CVAT portable-modules/03 포팅, tus-js-client FE)\n- 최대 5GB / 본인 데이터만 접근 가능\n- 확장자 allowlist + MIME 검증 + 파일명 정규화 (Path Manipulation 방어 CWE-22)\n- 업로드된 영상은 자동 라벨링 파이프라인에 진입 (오토라벨링 체험)\n- 포털 사용자에게는 VLM/버전관리/검수 미제공\n\n**DERIVES_FROM**: sr-sfr-15, sr-sfr-09\n**REALIZES (대상)**: comp-portal-upload-service, ent-portal-user-video",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-portal-tus-upload"
}
```

### REQ-092. 포털 간편 라벨링 + 본인 데이터 다운로드
```json
{
  "type": "REQUIREMENT",
  "title": "포털에서 본인 업로드 영상에 한해 간편 라벨링·기간 내 다운로드를 제공한다",
  "content": "**기능 범위**\n- 간편 라벨링(`/portal/label`) — YOLO+SAM2 오토라벨링 체험\n- 본인 업로드 데이터 기간 제한 내 다운로드\n- 기여도 점수 없음 (V1.1 정리)\n\n**범위 주의(V1.5)**: 학습데이터셋 다운로드 카드/D-day 배지/만료 처리 UI는 본 도구 mock에 미제공 → **외부 포털 자체 책임**\n\n**DERIVES_FROM**: sr-sfr-15\n**REALIZES (대상)**: comp-portal-label-service",
  "attrs": {"priority": "MED", "category": "FUNCTIONAL"},
  "_handle": "req-portal-simple-label"
}
```

## 11. 학습데이터 산출물 요구사항

### REQ-100. 이미지 학습데이터 10만장
```json
{
  "type": "REQUIREMENT",
  "title": "이미지 학습데이터 10만장을 수집-정제-가공-검수 프로세스로 구축한다",
  "content": "**산출 목표**: 10만장\n**가공 방식**: 바운딩박스/폴리곤/세그멘테이션 (이벤트 상황 주요 객체)\n**메타정보**: 환경정보·이벤트 정보 포함\n**개인정보**: 가명처리 (개보위 '가명정보 처리 가이드라인')\n**품질검사**: 외부전문 시험기관 샘플링 검사\n**재난분야**: 실 CCTV 영상이 있으면 활용, 없으면 생성형 AI 증강으로 확보\n\n**DERIVES_FROM**: sr-sfr-16\n**REALIZES (대상)**: feat-labeling-authoring (산출물)",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-image-100k"
}
```

### REQ-101. 영상 학습데이터 5,000건 (CoT 캡션)
```json
{
  "type": "REQUIREMENT",
  "title": "영상 학습데이터 5,000건을 CoT 캡션 데이터로 구축한다",
  "content": "**산출 목표**: 영상 5,000건 (이벤트 상황 포함 30초 이상 = 1건)\n**가공 방식**: 영상 클립당 VQA + CoT(Chain-of-Thought) 데이터 구성\n**메타정보**: 환경정보·이벤트 정보 포함\n**품질검사**: 외부전문 시험기관 샘플링 검사\n\n**DERIVES_FROM**: sr-sfr-17\n**REALIZES (대상)**: feat-labeling-authoring (산출물)",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "_handle": "req-video-5k"
}
```

## 12. 비기능 요구사항

### REQ-200. 페이지 응답시간 3초 + 동시처리 가용성
```json
{
  "type": "REQUIREMENT",
  "title": "페이지 응답시간 3초 이하 + 시스템 자원 사용률 80% 이하",
  "content": "**핵심**\n- 페이지별 응답속도 3초 이하 (초과 시 발주기관 협의)\n- 10초 이상 작업은 사전 알림(팝업, 프로그레스바)\n- CPU/MEM 평균 사용률 80% 이하 (본격 사용시기 모의수치 기준)\n- DB 커넥션 풀·메모리 누수 방지\n- 24/365 가용성, 백업절차 수립\n- 만 건 대비 size:999 패턴 금지 — BE 페이징(size 20) + enrich 패턴\n\n**DERIVES_FROM**: sr-per-02, sr-qur-03",
  "attrs": {"priority": "HIGH", "category": "NON_FUNCTIONAL"},
  "_handle": "req-nfr-performance"
}
```

### REQ-201. 반응형 웹 + WCAG 2.1 + 멀티브라우저
```json
{
  "type": "REQUIREMENT",
  "title": "반응형 웹(1024×768~1920×1080) + WCAG 2.1 + 멀티브라우저 지원",
  "content": "**핵심**\n- 모바일(1024×768)부터 1920×1080(권장 24인치)까지 반응형\n- 수평스크롤 미사용, CSS 단위 지정\n- WCAG 2.1 AA / 한국형 웹 콘텐츠 접근성 지침 / 전자정부 웹사이트 품질관리 지침 준수\n- 멀티플랫폼·멀티브라우저 (Active-X 불가)\n- HTML5 웹표준\n\n**DERIVES_FROM**: sr-sir-04",
  "attrs": {"priority": "HIGH", "category": "NON_FUNCTIONAL"},
  "_handle": "req-nfr-responsive"
}
```

### REQ-202. 보안 — 시크릿 하드코딩 금지·암호화·접근통제
```json
{
  "type": "REQUIREMENT",
  "title": "JWT 시크릿·API 키·DB 비밀번호 하드코딩 금지 + 암호화·접근통제",
  "content": "**핵심**\n- 로그인 실패 5회 이상 차단, 동일 계정 동시 로그인 차단, 우회 접근 차단\n- 중요정보 전송 구간 암호화 (HTTPS, JWT HS256+)\n- **소스코드 내 하드코딩 금지** → 환경변수 `${VAR}` 주입 (JWT_SECRET, GITEA_TOKEN, DB_PASSWORD)\n- DB에 개인정보·중요정보 암호화 저장 (bcrypt cost 12+ 또는 Argon2id 비밀번호 해싱)\n- 관리자 페이지 비인가자 노출 차단, 권한별 페이지 구분\n- 개발/테스트 서버 외부 노출 차단\n- gradle.lockfile로 의존성 무결성 (OWASP A03:2025 Software Supply Chain)\n- IDOR(CWE-639) — LabelAccessGuard로 본인 배정 작업만 접근\n- Mass Assignment(CWE-915) — record DTO + autoLblYn 무시\n- DoS(CWE-770) — 페이지 size 100 한도, 좌표 점 1000 한도\n\n**DERIVES_FROM**: sr-ser-01, sr-sfr-18",
  "attrs": {"priority": "HIGH", "category": "NON_FUNCTIONAL"},
  "_handle": "req-nfr-security"
}
```

### REQ-203. 테스트 전략 (단위/통합/시스템/인수)
```json
{
  "type": "REQUIREMENT",
  "title": "단위/통합/시스템/인수 4단계 테스트 + 유스케이스 기반 커버리지",
  "content": "**핵심**\n- 단위(사업수행자) / 통합(사업수행자, 2회 이상) / 시스템(사업수행자) / 인수(자치단체+한국지역정보개발원+행정안전부)\n- 유스케이스 커버리지 = (시험대상 UC / 전체 UC) × 100\n- 정상처리·예외처리·오류 등 다양한 케이스 작성\n- 결함관리시스템으로 결함 추적, 오류 없을 때까지 반복\n- 통합테스트: 기능/성능 요구사항·설계사양·접근권한·대내외 연계 검증\n- 부하테스트: 성능검증도구로 응답속도 만족 검증\n- 인수테스트: 요구사항별 적합/부적합 판정\n\n**테스트 환경**: 단위·통합(개발 사업자 테스트 서버) / 시스템·인수(국가정보자원관리원, 통합관제센터 운영환경)\n\n**DERIVES_FROM**: sr-ter-01",
  "attrs": {"priority": "HIGH", "category": "NON_FUNCTIONAL"},
  "_handle": "req-nfr-test"
}
```

### REQ-204. 데이터 표준·구조·값 검증 (DAR)
```json
{
  "type": "REQUIREMENT",
  "title": "데이터 표준(단어/용어/도메인/코드) 수립·관리 + 모델 검증",
  "content": "**핵심**\n- 단어·용어·도메인·코드 정의 → 표준 사전 제정 (범정부 표준 + 공공기관 DB 표준화 지침 준수)\n- 표준관리 방안(변경이력) + 주기 점검\n- 데이터 구조 설계 — 개념·논리·물리 모델, 명명 규칙, DB Object 사용기준\n- 데이터 모델 검증 — 설계자·개발자·발주기관·전문가 참여\n- 데이터 값 검증 — 업무규칙(BR) 기반 오류 입력 방지\n- 메타데이터 현행화 — 발주기관 메타데이터 관리시스템 등록\n- 공공데이터 개방 — 파일/API 형태로 외부 제공 가능 설계 (단, 다운로드 외부 책임 V1.4)\n\n**DERIVES_FROM**: sr-dar-standards",
  "attrs": {"priority": "MED", "category": "NON_FUNCTIONAL"},
  "_handle": "req-nfr-dar"
}
```

---

## 등록 순서

REQUIREMENT는 SOURCE_REQUIREMENT보다 나중, USECASE보다 먼저 (DERIVES_FROM source / REALIZES source).

기능 요구사항(REQ-001 ~ REQ-101) → 비기능(REQ-200 ~ REQ-204) 순으로 등록 권장.
