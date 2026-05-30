# Brownfield Report — KLID-저작도구

> LogiCraft `generate_brownfield_report` export (2026-05-30) — 한국 공공 SI K-DOC '데이터 변경 명세서' / 운영팀 인계 자료 입력용.
> Project: `4ece2c3f-8e99-46f5-9580-71108a76e578` · Items with brownfield: 229

## 📊 Status Distribution by ITEM Type

| Type | preserved | modified | new | deprecated | split | merged | Total |
|---|---|---|---|---|---|---|---|
| api_endpoint | 35 | 29 | 25 | 0 | 0 | 0 | 89 |
| code_module | 4 | 10 | 7 | 0 | 0 | 0 | 21 |
| domain | 6 | 3 | 4 | 3 | 0 | 0 | 16 |
| domain_feature | 21 | 5 | 1 | 13 | 0 | 0 | 40 |
| erd | 7 | 0 | 0 | 2 | 0 | 0 | 9 |
| feature | 0 | 3 | 4 | 0 | 0 | 0 | 7 |
| requirement | 0 | 5 | 12 | 1 | 0 | 0 | 18 |
| screen_spec | 7 | 14 | 8 | 0 | 0 | 0 | 29 |

## 📦 1차 Legacy Repo 분포

| Legacy Repo | ITEM count |
|---|---|
| KLID-AI-PF-001 | 37 |
| KLID-AI-PF-005 | 4 |
| KLID-AI-PF-004 | 4 |
| KLID-AI-PF-002 | 1 |
| KLID-AI-PF-003 | 1 |

## ⚠️ Legacy Source Conflicts (identifier 중복)

동일 1차 식별자가 여러 2차 ITEM 에 매핑된 경우. table-split 같은 의도적 분리이거나 매핑 오류일 수 있음.

| Legacy Identifier | Mapped ITEMs |
|---|---|
| `KLID-AI-PF-001` | `DFEAT-001` (domain_feature), `ERD-005` (erd), `ERD-006` (erd) |
| `KLID-AI-PF-005` | `DFEAT-010` (domain_feature), `ERD-007` (erd) |
| `SKKLID-UI-02-02-04` | `DFEAT-013` (domain_feature), `DFEAT-014` (domain_feature), `FEAT-005` (feature) |
| `SKKLID-UI-02-02-05` | `DFEAT-018` (domain_feature), `FEAT-001` (feature), `REQ-007` (requirement), `REQ-008` (requirement) |
| `SKKLID-UI-02-02-06` | `DFEAT-019` (domain_feature), `REQ-006` (requirement) |
| `SKKLID-UI-02-02-16` | `DFEAT-021` (domain_feature), `DFEAT-022` (domain_feature), `DFEAT-023` (domain_feature) |
| `LS_DATA_AUG` | `DFEAT-029` (domain_feature), `DFEAT-030` (domain_feature), `FEAT-004` (feature), `REQ-001` (requirement), `REQ-003` (requirement) |
| `KLID-AI-PF-004` | `ERD-002` (erd), `ERD-003` (erd), `ERD-008` (erd), `ERD-009` (erd) |

> 위 충돌은 대부분 의도적 — 1차 단일 화면/모듈이 2차에서 여러 ITEM(기능·요구·ERD)으로 분해됐거나, 1차 repo 모듈명이 여러 ERD의 출처인 경우. 매핑 오류 아님.

## 📋 Brownfield Mapping Table

| ITEM ID | Type | Title | Status | Legacy Identifier | change_kind | diff_summary |
|---|---|---|---|---|---|---|
| `API-001` | api_endpoint | GET /v1/users | preserved |  |  | 1차 사용자·권한(LS_USER_*, ERD-001 preserved) 기반 사용자 목록 조회 |
| `API-002` | api_endpoint | GET /v1/users/workers | preserved |  |  | 1차 사용자·권한 기반 작업자 목록 (배정 화면 연계) |
| `API-003` | api_endpoint | GET /v1/users/{userNo} | preserved |  |  | 1차 사용자·권한 기반 사용자 상세 조회 |
| `API-004` | api_endpoint | PATCH /v1/users/{userNo} | preserved |  |  | 1차 사용자·권한 기반 사용자 정보 수정 |
| `API-005` | api_endpoint | GET /v1/users/me | preserved |  |  | 1차 사용자·권한 기반 본인 정보 조회 |
| `API-006` | api_endpoint | GET /v1/me | preserved |  |  | 외부 JWT 인계 로그인(DFEAT-001 preserved) 세션 조회 |
| `API-007` | api_endpoint | POST /v1/auth/role-claim | modified |  | redesign | 2차 role+channel 클레임 분기 (관제/포털 단일 검증 로직) |
| `API-008` | api_endpoint | GET /v1/reviews | modified |  | merge | 1차 1·2차 검수 → 2차 단일 검수(1인 승인까지 반복). 검수 대상 목록 |
| `API-009` | api_endpoint | GET /v1/reviews/{videoId} | modified |  | merge | 단일 검수 상세(영상 단위) |
| `API-010` | api_endpoint | GET /v1/reviews/{videoId}/frames | modified |  | merge | 프레임 단위 검수 조회 |
| `API-011` | api_endpoint | GET /v1/reviews/{videoId}/issues | modified |  | merge | 검수 이슈 조회 |
| `API-012` | api_endpoint | POST /v1/reviews/{videoId}/submit | modified |  | merge | 작업자 검수 제출 (영상 단위) |
| `API-013` | api_endpoint | POST /v1/reviews/{videoId}/start | modified |  | merge | 검수 시작(단일 검수) |
| `API-014` | api_endpoint | POST /v1/reviews/{videoId}/approve | modified |  | merge, redesign | 2차 검수 승인=작업 완료 → 관제 통지 발행 연계 |
| `API-015` | api_endpoint | POST /v1/reviews/{videoId}/reject | modified |  | merge | 검수 반려(단일 검수 반복) |
| `API-016` | api_endpoint | POST /v1/meta/{metaReviewSn}/approve | preserved |  |  | VLM 메타 검수 승인 (LS_DATA_META_REVIEW) |
| `API-017` | api_endpoint | POST /v1/meta/{metaReviewSn}/reject | preserved |  |  | VLM 메타 검수 반려 |
| `API-018` | api_endpoint | GET /v1/frames/{srcSn}/labels | preserved |  |  | 1차 라벨링(ERD-008) 기반 프레임 라벨 조회 |
| `API-019` | api_endpoint | PUT /v1/frames/{srcSn}/labels | preserved |  |  | 1차 라벨링 기반 프레임 라벨 일괄 저장 |
| `API-020` | api_endpoint | POST /v1/frames/{srcSn}/sam2-track | modified |  | capability-add | 1차 AI Tool(SAM) → 2차 SAM2 객체 추적·외곽 밀착 (FEAT-001) |
| `API-021` | api_endpoint | GET /v1/frames/{srcSn}/image | preserved |  |  | 1차 라벨링 기반 프레임 이미지 조회 |
| `API-022` | api_endpoint | GET /v1/labels/{lblSn}/attrs | preserved |  |  | 1차 라벨 속성(ERD-008) 기반 속성값 조회 |
| `API-023` | api_endpoint | PUT /v1/labels/{lblSn}/attrs | preserved |  |  | 1차 라벨 속성 기반 속성값 저장 |
| `API-024` | api_endpoint | GET /v1/manage/labels | preserved |  |  | 1차 라벨 마스터 코드 조회 |
| `API-025` | api_endpoint | POST /v1/manage/labels | preserved |  |  | 1차 라벨 마스터 관리 |
| `API-026` | api_endpoint | PUT /v1/manage/labels/{id} | preserved |  |  | 1차 라벨 마스터 관리 |
| `API-027` | api_endpoint | DELETE /v1/manage/labels/{id} | preserved |  |  | 1차 라벨 마스터 관리 |
| `API-028` | api_endpoint | GET /v1/manage/labels/{labelId}/attrs | preserved |  |  | 1차 라벨 속성 정의 조회 |
| `API-029` | api_endpoint | POST /v1/manage/labels/{labelId}/attrs | preserved |  |  | 1차 라벨 속성 정의 관리 |
| `API-030` | api_endpoint | PUT /v1/manage/labels/{labelId}/attrs/{attrId} | preserved |  |  | 1차 라벨 속성 정의 관리 |
| `API-031` | api_endpoint | DELETE /v1/manage/labels/{labelId}/attrs/{attrId} | preserved |  |  | 1차 라벨 속성 정의 관리 |
| `API-032` | api_endpoint | POST /v1/labels/{srcSn}/deident-report | new |  | capability-add | 2차 라벨링 중 비식별 영역 리포팅 [추정 — 1차 대응 확인 필요] |
| `API-033` | api_endpoint | POST /v1/frames/{srcSn}/commit | modified |  | redesign | 1차 Gitea 기반 → 2차 DB 스냅샷 버전관리 (FEAT-002) |
| `API-034` | api_endpoint | GET /v1/frames/{srcSn}/versions | modified |  | redesign | DB 스냅샷 버전 목록 (FEAT-002) |
| `API-035` | api_endpoint | GET /v1/versions/{version}/diff | modified |  | redesign | DB 스냅샷 앱 계산 diff (FEAT-002) |
| `API-036` | api_endpoint | POST /v1/versions/{version}/rollback | modified |  | redesign | DB 스냅샷 롤백 (FEAT-002) |
| `API-037` | api_endpoint | GET /v1/manage/presets | preserved |  |  | 1차 라벨링 프리셋 관리 |
| `API-038` | api_endpoint | POST /v1/manage/presets | preserved |  |  | 1차 라벨링 프리셋 관리 |
| `API-039` | api_endpoint | PUT /v1/manage/presets/{id} | preserved |  |  | 1차 라벨링 프리셋 관리 |
| `API-040` | api_endpoint | DELETE /v1/manage/presets/{id} | preserved |  |  | 1차 라벨링 프리셋 관리 |
| `API-041` | api_endpoint | POST /v1/manage/presets/{id}/clone | preserved |  |  | 1차 라벨링 프리셋 관리 |
| `API-042` | api_endpoint | GET /v1/videos | modified |  | scope-shrink | 1차 영상/이미지 관리 → 2차 영상 단위 목록(프로젝트 배정 제거, DFEAT-007) |
| `API-043` | api_endpoint | GET /v1/videos/{rawSn} | modified |  | scope-shrink | 영상 단위 상세 |
| `API-044` | api_endpoint | GET /v1/videos/{rawSn}/labels/auto | preserved |  |  | 오토라벨 결과 조회 |
| `API-045` | api_endpoint | GET /v1/videos/{rawSn}/auto-summary | preserved |  |  | 오토라벨 요약 조회 |
| `API-046` | api_endpoint | GET /v1/videos/{rawSn}/frames/{frameNo}/image | preserved |  |  | 영상 프레임 이미지 조회 |
| `API-047` | api_endpoint | POST /v1/videos/{rawSn}/markings | new |  | capability-add | 2차 신규 마킹 (DFEAT-039) |
| `API-048` | api_endpoint | GET /v1/videos/{rawSn}/markings | new |  | capability-add | 2차 신규 마킹 (DFEAT-039) |
| `API-049` | api_endpoint | GET /v1/videos/{rawSn}/markings/{markingSn} | new |  | capability-add | 2차 신규 마킹 (DFEAT-039) |
| `API-050` | api_endpoint | DELETE /v1/videos/{rawSn}/markings/{markingSn} | new |  | capability-add | 2차 신규 마킹 (DFEAT-039) |
| `API-051` | api_endpoint | GET /v1/deident | modified |  | component-replace | 1차 캔버스 수동 블러 → 2차 외부 솔루션 연동 결과 검토(FEAT-006) |
| `API-052` | api_endpoint | GET /v1/deident/{videoId} | modified |  | component-replace | 비식별 상세·이력(FEAT-006) |
| `API-053` | api_endpoint | POST /v1/deident/{videoId}/reprocess | modified |  | component-replace | 비식별 재처리(외부 솔루션, FEAT-005) |
| `API-054` | api_endpoint | POST /v1/deidentify/result | new |  | capability-add | 2차 외부 비식별 솔루션 콜백 수신(FEAT-005) |
| `API-055` | api_endpoint | GET /v1/stats/summary | preserved |  |  | 1차 통계·대시보드(ERD-003) 기반 요약 |
| `API-056` | api_endpoint | GET /v1/stats/worker | preserved |  |  | 1차 작업 통계(LS_PJT_JOB_STATS) 기반 |
| `API-057` | api_endpoint | GET /v1/stats/overall | preserved |  |  | 1차 전체 집계 통계 |
| `API-058` | api_endpoint | GET /v1/stats/report | preserved |  |  | 1차 통계 리포트 |
| `API-059` | api_endpoint | GET /v1/augments | modified |  | scope-shrink | 1차 증강·내보내기 → 2차 증강만 잔존(생성형 외부화, FEAT-004) |
| `API-060` | api_endpoint | POST /v1/augments/request | modified |  | redesign | 증강 요청(외부 연동, FEAT-004) |
| `API-061` | api_endpoint | GET /v1/augments/{jobId}/result | modified |  | redesign | 증강 결과 조회(FEAT-004) |
| `API-062` | api_endpoint | POST /v1/augments/{id}/accept | modified |  | redesign | 증강 수락 → 새 영상 생성(FEAT-004) |
| `API-063` | api_endpoint | POST /v1/augments/{id}/reject | modified |  | redesign | 증강 거부(FEAT-004) |
| `API-064` | api_endpoint | POST /v1/augments/result | new |  | capability-add | 2차 외부 증강 콜백 수신(FEAT-004) |
| `API-065` | api_endpoint | POST /v1/vlm/result | new |  | capability-add | 2차 외부 VLM 시계열 콜백 수신(FEAT-004) |
| `API-066` | api_endpoint | GET /v1/frames/{srcSn}/meta | preserved |  |  | 프레임 시계열 메타 조회 |
| `API-067` | api_endpoint | PUT /v1/frames/{srcSn}/meta | preserved |  |  | 프레임 시계열 메타 수정 |
| `API-068` | api_endpoint | GET /v1/manage/configs | new |  | capability-add | 2차 신규 시스템 설정 관리 |
| `API-069` | api_endpoint | PUT /v1/manage/configs/{key} | new |  | capability-add | 2차 신규 설정 수정(정밀도 조절 포함, FEAT-007) |
| `API-070` | api_endpoint | POST /v1/assignments | modified |  | scope-shrink | 1차 프로젝트 단위 → 2차 영상 단위 배정(DFEAT-006) |
| `API-071` | api_endpoint | PATCH /v1/assignments/{assignmentId} | modified |  | scope-shrink | 영상 단위 재배정(DFEAT-006) |
| `API-072` | api_endpoint | GET /v1/assignments | modified |  | scope-shrink | 영상 단위 배정 이력(DFEAT-006) |
| `API-073` | api_endpoint | GET /v1/tasks/board | modified |  | scope-shrink | 영상 단위 작업 보드(DFEAT-006) |
| `API-074` | api_endpoint | GET /v1/tasks/{rawSn}/summary | new |  | capability-add | 2차 신규 관제 조회 API(FEAT-003) |
| `API-075` | api_endpoint | GET /v1/tasks/{rawSn}/labels | new |  | capability-add | 2차 신규 관제 라벨 조회 API(FEAT-003) |
| `API-076` | api_endpoint | GET /v1/tasks/{rawSn}/meta | new |  | capability-add | 2차 신규 관제 메타 조회 API(FEAT-003) |
| `API-077` | api_endpoint | GET /v1/batch/status | modified |  | redesign | 2차 배치 파이프라인 상태 조회 [추정 — 1차 대응 확인 필요] |
| `API-078` | api_endpoint | GET /v1/portal/uploads | new |  | capability-add | 2차 외부 채널(포털) 신규 |
| `API-079` | api_endpoint | POST /v1/portal/autolabel | new |  | capability-add | 2차 포털 오토라벨 체험 |
| `API-080` | api_endpoint | POST /v1/portal/labels | new |  | capability-add | 2차 포털 간편 라벨링 |
| `API-081` | api_endpoint | GET /v1/portal/datamart/labels | new |  | capability-add | 2차 포털 데이터마트 Load |
| `API-082` | api_endpoint | POST /v1/portal/user-labels | new |  | capability-add | 2차 포털 사용자 라벨 저장 |
| `API-083` | api_endpoint | GET /v1/portal/user-labels | new |  | capability-add | 2차 포털 사용자 라벨 조회 |
| `API-084` | api_endpoint | GET /v1/videos/{rawSn}/stream | new |  | capability-add | 2차 마킹 화면용 영상 스트리밍(Range) — DFEAT-039 연계 |
| `API-085` | api_endpoint | OPTIONS /v1/portal/uploads | new |  | capability-add | 2차 포털 TUS 재개 업로드 (CVAT 포팅) |
| `API-086` | api_endpoint | POST /v1/portal/uploads | new |  | capability-add | 2차 포털 TUS 업로드 생성 |
| `API-087` | api_endpoint | HEAD /v1/portal/uploads/{fileId} | new |  | capability-add | 2차 포털 TUS 오프셋 조회 |
| `API-088` | api_endpoint | PATCH /v1/portal/uploads/{fileId} | new |  | capability-add | 2차 포털 TUS 청크 전송 |
| `API-089` | api_endpoint | DELETE /v1/portal/uploads/{fileId} | new |  | capability-add | 2차 포털 TUS 업로드 취소 |
| `MOD-001` | code_module | user 패키지 (사용자·권한) | preserved |  |  | 1차 사용자·권한 재플랫폼(Java) |
| `MOD-002` | code_module | auth 패키지 (인증) | preserved |  |  | 외부 JWT 인계 로그인(DFEAT-001 preserved) |
| `MOD-003` | code_module | video 패키지 (영상·프레임 수집) | modified |  | scope-shrink | 1차 영상/이미지 관리 → 2차 영상 단위(프로젝트 제거) |
| `MOD-004` | code_module | marking 패키지 (마킹) | new |  | capability-add | 2차 신규 마킹 (DFEAT-039) |
| `MOD-005` | code_module | deident 패키지 (비식별화) | modified |  | component-replace | 1차 캔버스 블러 → 2차 외부 솔루션 연동 |
| `MOD-006` | code_module | label 패키지 (라벨링) | modified |  | capability-add | 1차 라벨링 → 2차 SAM2·정밀도 고도화 |
| `MOD-007` | code_module | version 패키지 (버전관리) | modified |  | redesign | 1차 Gitea → 2차 DB 스냅샷 버전관리(FEAT-002) |
| `MOD-008` | code_module | preset 패키지 (프리셋) | preserved |  |  | 1차 라벨링 프리셋 관리 |
| `MOD-009` | code_module | review 패키지 (검수) | modified |  | merge | 1차 1·2차 검수 → 2차 단일 검수 |
| `MOD-010` | code_module | meta 패키지 (VLM 메타) | new |  | capability-add | 2차 외부 VLM 시계열 메타 검토(FEAT-004) |
| `MOD-011` | code_module | quality 패키지 (품질) | new |  | capability-add | 2차 CVAT 품질 충돌 감지 포팅 [추정 — 1차 대응 확인 필요] |
| `MOD-012` | code_module | stats 패키지 (통계) | preserved |  |  | 1차 통계·대시보드 기반 |
| `MOD-013` | code_module | augment 패키지 (데이터 증강) | modified |  | scope-shrink | 1차 증강·내보내기 → 2차 증강만(FEAT-004) |
| `MOD-014` | code_module | assignment 패키지 (작업 배정) | modified |  | scope-shrink | 1차 프로젝트 단위 → 2차 영상 단위 배정(DFEAT-006) |
| `MOD-015` | code_module | controlnotify 패키지 (관제 통지) | new |  | capability-add | 2차 신규 단방향 통지+조회(FEAT-003) |
| `MOD-016` | code_module | sysconfig 패키지 (시스템 설정) | new |  | capability-add | 2차 신규 시스템 설정(정밀도 조절, FEAT-007) |
| `MOD-017` | code_module | portal 패키지 (포털) | new |  | capability-add | 2차 외부 채널(포털) 신규 |
| `MOD-018` | code_module | webhook 패키지 (콜백 수신) | new |  | capability-add | 2차 외부 콜백 수신(VLM·증강) |
| `MOD-019` | code_module | common 패키지 (공통 인프라) | modified |  | redesign | 2차 공통 인프라 재구성(Java, Resilience4j, 듀얼 DataSource) |
| `MOD-020` | code_module | ai-server (Python 추론) | modified |  | component-replace | 1차 AI Tool → 2차 경량 추론 전용 ai-server(YOLO/SAM2/VLM) |
| `MOD-021` | code_module | frontend (React 앱) | modified |  | redesign | 1차 UI → 2차 React+Vite+TS 재구성(konva 캔버스) |
| `DOMAIN-001` | domain | 사용자·권한 | preserved |  |  |  |
| `DOMAIN-002` | domain | 프로젝트 관리 | deprecated |  | scope-shrink | 프로젝트 개념 폐기(작업 단위=영상 1건) — 프로젝트 생성/관리 폐기, 배정→검수·영상관리→영상·프레임 수집 도메인으로 재배치 |
| `DOMAIN-003` | domain | 영상·프레임 수집 | preserved |  |  |  |
| `DOMAIN-004` | domain | AI 보조 라벨링 | preserved |  |  |  |
| `DOMAIN-005` | domain | 검수 | preserved |  |  |  |
| `DOMAIN-006` | domain | 통계·대시보드 | preserved |  |  |  |
| `DOMAIN-007` | domain | 데이터 증강·내보내기 | modified |  | scope-shrink | 1차 '데이터 증강 + 내보내기' → 2차 내보내기(Export)·데이터마트 폐기(외부 제공 시스템 책임), 데이터 증강만 잔존 |
| `DOMAIN-008` | domain | 생성형 AI 연동 | deprecated |  | scope-shrink | 생성형 AI 연동 도메인 폐기 — 생성 본체 외부, 업로더 검토·등록도 미보유 |
| `DOMAIN-009` | domain | 게시판·공지 | deprecated |  | scope-shrink | 게시판·공지 기능 2차 폐기 (저작도구 미제공) |
| `DOMAIN-010` | domain | 라벨링 | preserved |  |  |  |
| `DOMAIN-011` | domain | 마킹 | new |  | capability-add | 2차 신규 도메인 (DFEAT-039 마킹). 영상 단위 이벤트 식별로 배치 파이프라인 시작점 제공 |
| `DOMAIN-012` | domain | 비식별화 | modified |  | component-replace | 1차 라벨링 캔버스 수동 블러 → 2차 외부 영상비식별 솔루션 연동으로 대체 |
| `DOMAIN-013` | domain | 포털 | new |  | capability-add | 2차 외부 채널 도메인 |
| `DOMAIN-014` | domain | 시스템 설정 | new |  | capability-add | 2차 신규 — 시스템 설정 키-값 관리 |
| `DOMAIN-015` | domain | 작업 배정 | modified |  | scope-shrink, redesign | 1차 프로젝트 단위 배정 → 2차 영상 단위 배정(프로젝트 폐기). DFEAT-006 과 정합 |
| `DOMAIN-016` | domain | 관제 통지 | new |  | capability-add | 2차 신규 — 단방향 outbound 통지 + inbound 조회 API. 1차 양방향 M2M 통합은 deprecated |
| `DFEAT-001` | domain_feature | 외부 JWT 인계 로그인 (독립 로그인 UI 없음) | preserved | KLID-AI-PF-001 |  |  |
| `DFEAT-002` | domain_feature | 역할별 메뉴·접근제어 | modified | LS_USER_MENU | role-merge, actor-change | 1차 6역할 → 2차 3역할(REVIEWER·WORKER·PORTAL_USER), ADMIN 권한 REVIEWER 흡수 |
| `DFEAT-003` | domain_feature | 사용자 관리 | preserved | SKKLID-UI-03-05-01 |  |  |
| `DFEAT-004` | domain_feature | 프로젝트 생성 (정보·단계·라벨·권한 설정) | deprecated | SKKLID-UI-03-02-04 | scope-shrink | 프로젝트 개념 폐기로 2차 폐기 |
| `DFEAT-005` | domain_feature | 프로젝트 관리·상세 조회 | deprecated | SKKLID-UI-03-02-12 | scope-shrink | 프로젝트 개념 폐기로 2차 폐기 |
| `DFEAT-006` | domain_feature | 작업 배정·재배정·확인 | modified | SKKLID-UI-03-02-14 | actor-change | 1차 관리자/담당자 배정 → 2차 REVIEWER가 WORKER에게 배정, 영상 단위 배정 |
| `DFEAT-007` | domain_feature | 영상/이미지 관리·프로젝트 배정 | modified | SKKLID-UI-03-03-01 | scope-shrink | 1차 영상/이미지 관리·프로젝트 배정 → 2차 영상 단위 관리(프로젝트 배정 제거) |
| `DFEAT-008` | domain_feature | 클립영상 수신·적재 | preserved | KLID-AI-II-001 |  |  |
| `DFEAT-009` | domain_feature | FFmpeg 프레임 자동 추출 (배치 1회/분) | preserved | KLID-AI-II-002 |  |  |
| `DFEAT-010` | domain_feature | 이미지 전처리 (리사이징·밝기/대비 보정) | preserved | KLID-AI-PF-005 |  |  |
| `DFEAT-011` | domain_feature | 메타데이터 기반 자동 분류 | preserved | LS_DATA_RAW |  |  |
| `DFEAT-012` | domain_feature | 도형 어노테이션 (바운딩박스·폴리곤·스켈레톤) | preserved | SKKLID-UI-02-02-07 |  |  |
| `DFEAT-013` | domain_feature | 블러 (비식별) 처리 | deprecated | SKKLID-UI-02-02-04 | component-replace | 1차 캔버스 수동 블러 → 2차 외부 비식별 솔루션(FEAT-005)으로 대체 |
| `DFEAT-014` | domain_feature | 캔버스 도구 (그리드·밝기·투명도·이동/회전/확대) | preserved | SKKLID-UI-02-02-04 |  |  |
| `DFEAT-015` | domain_feature | 객체·메타·이슈 탭 | preserved | SKKLID-UI-02-02-09 |  |  |
| `DFEAT-016` | domain_feature | 단축키·전체 복사/붙여넣기 | preserved | SKKLID-UI-02-02-11 |  |  |
| `DFEAT-017` | domain_feature | 학습데이터 저장 | preserved | LS_DATA_LBL |  |  |
| `DFEAT-018` | domain_feature | AI Tool (SAM 클릭 세그멘테이션) | preserved | SKKLID-UI-02-02-05 |  |  |
| `DFEAT-019` | domain_feature | Auto Labeling (YOLO 객체 탐지) | preserved | SKKLID-UI-02-02-06 |  |  |
| `DFEAT-020` | domain_feature | 트랙 모드 (선형보간 연속 프레임 추적) | preserved | SKKLID-UI-02-05-08 |  |  |
| `DFEAT-021` | domain_feature | 검수 (검수자 1인 승인까지 반복) | modified | SKKLID-UI-02-02-16 | merge, redesign | 1차 1·2차 단계 검수 → 2차 단계 구분 폐기, 검수자 1인 반복 검수 (021←021+022 통합) |
| `DFEAT-022` | domain_feature | 2차 검수 | deprecated | SKKLID-UI-02-02-16 | merge | 2차 검수 단계 폐기 — 단일 검수자 반복(DFEAT-021)로 통합 |
| `DFEAT-023` | domain_feature | 프레임 상태 색상 표기 (연두/주황/빨강) | preserved | SKKLID-UI-02-02-16 |  |  |
| `DFEAT-024` | domain_feature | 승인·반려·관리자 확인 요청 | modified | SKKLID-UI-02-02-17 | actor-change | 1차 관리자 확인 요청 → 2차 관리자 역할 폐기(REVIEWER 흡수)로 검수자 확인 |
| `DFEAT-025` | domain_feature | 검수 이력 | preserved | SKKLID-UI-02-02-18 |  |  |
| `DFEAT-026` | domain_feature | 작업자/검수자 대시보드 (월별·일별 통계) | preserved | SKKLID-UI-02-01-01 |  |  |
| `DFEAT-027` | domain_feature | 관리자/담당자 대시보드 | preserved | SKKLID-UI-03-01-01 |  |  |
| `DFEAT-028` | domain_feature | 프로젝트 통계 (전체·권한별·상태별·일일 진행률) | preserved | SKKLID-UI-03-02-16 |  |  |
| `DFEAT-029` | domain_feature | 데이터 증강 5종 | preserved | LS_DATA_AUG |  |  |
| `DFEAT-030` | domain_feature | 증강 상태 흐름·자동 재처리 | preserved | LS_DATA_AUG |  |  |
| `DFEAT-031` | domain_feature | 학습데이터 내보내기·데이터마트 등록 | deprecated | LS_DATA_SET | scope-shrink | 내보내기(Export)·데이터마트 구축 — 범위 외, 외부 제공 시스템 책임 |
| `DFEAT-032` | domain_feature | Text2Image 생성 | deprecated | SKKLID-UI-02-05-03 | scope-shrink | 생성 본체 — 외부 생성 시스템 책임 이관. 저작도구는 증강 결과 검수만 잔존 |
| `DFEAT-033` | domain_feature | Image2Image 생성 | deprecated | SKKLID-UI-02-05-04 | scope-shrink | 생성 본체 — 외부 시스템 책임 |
| `DFEAT-034` | domain_feature | Image2Video 생성 | deprecated | SKKLID-UI-02-05-05 | scope-shrink | 생성 본체 — 외부 시스템 책임(영상합성 모델 외부) |
| `DFEAT-035` | domain_feature | 메타데이터 기반 생성 (계절·날씨·시간) | deprecated | SKKLID-UI-02-05-06 | scope-shrink | 생성 본체 — 외부 시스템 책임 |
| `DFEAT-036` | domain_feature | 업로더 생성·업로드 데이터 검토·등록 | deprecated | SKKLID-UI-02-05-07 | scope-shrink | 2차 저작도구 미보유(생성 본체 외부, 업로드는 포털 자체 책임) |
| `DFEAT-037` | domain_feature | 게시글 목록·상세 조회 | deprecated | SKKLID-UI-02-03-01 | scope-shrink | 게시판 기능 2차 폐기 |
| `DFEAT-038` | domain_feature | 게시글 작성·수정 (관리자) | deprecated | SKKLID-UI-03-06-02 | scope-shrink | 게시판 기능 2차 폐기 |
| `DFEAT-039` | domain_feature | 마킹 (자동/수동 이벤트 식별) | new |  | capability-add | V2.0 파이프라인 선두 마킹 단계 신규 — 1차 미존재. 마킹→VLM→비식별→프레임추출 트리거 |
| `DFEAT-040` | domain_feature | 연습장 (가공 작업 체험) | deprecated | SKKLID-UI-02-04-01 | scope-shrink | 1차 작업자 가공 작업 체험(연습장) — 2차 폐기 |
| `ERD-001` | erd | 사용자·권한 ERD (LS_USER_*, LS_AUTHRT_MPNG) | preserved | KLID-AI-PF-002 |  |  |
| `ERD-002` | erd | 검수 ERD (LS_PJT_DATA_STTS) | preserved | KLID-AI-PF-004 |  |  |
| `ERD-003` | erd | 통계·대시보드 ERD | preserved | KLID-AI-PF-004 |  |  |
| `ERD-004` | erd | 데이터 증강·내보내기 ERD | preserved | KLID-AI-PF-003 |  |  |
| `ERD-005` | erd | 생성형 AI·업로드 데이터 ERD (LS_DATA_USER) | deprecated | KLID-AI-PF-001 | scope-shrink | 생성형 AI·업로더 버티컬 폐기 — LS_DATA_USER 미사용 |
| `ERD-006` | erd | 게시판·공지 ERD (LS_NTC_BBS, LS_ATCH_FILE) | deprecated | KLID-AI-PF-001 | scope-shrink | 게시판·공지 기능 2차 폐기 — 미사용 |
| `ERD-007` | erd | 영상·프레임 수집 ERD (LS_DATA_RAW/SRC + 이력) | preserved | KLID-AI-PF-005 |  |  |
| `ERD-008` | erd | 라벨링 ERD (LS_DATA_LBL/META/ISSUE + 이력) | preserved | KLID-AI-PF-004 |  |  |
| `ERD-009` | erd | 프로젝트 관리 ERD (LS_PJT 외 10종) | preserved | KLID-AI-PF-004 |  | (PJT_SN deprecated 표기 — 1차 이력 보존) |
| `FEAT-001` | feature | AI 보조 라벨링 (객체 추적·외곽 경계 밀착) | modified | SKKLID-UI-02-02-05 | capability-add | 1차 AI Tool(SAM)+Auto Labeling(YOLO) → 2차 객체 추적·외곽 경계 자동 밀착 고도화 (SFR-08-01/02) |
| `FEAT-002` | feature | 라벨 버전관리·비교·복구 | new |  | capability-add | DB 스냅샷 기반 버전관리·diff·롤백 2차 신규(versionHash=payload SHA-256). 외부 VCS 미사용 (SFR-08-04/05) |
| `FEAT-003` | feature | 데이터마트 라벨 동기화 통지 | new |  | capability-add | 검수 완료/수정 시 관제서버 outbound TASK_COMPLETED/MODIFIED 통지 2차 신규 (SFR-08-06) |
| `FEAT-004` | feature | 영상 증강 연동·검수 + 해상도 변경 | modified | LS_DATA_AUG | capability-add, component-replace | 1차 증강 5종 → 2차 외부 증강 연동·결과 검수 + 해상도 변경 (SFR-07) |
| `FEAT-005` | feature | 개인정보 비식별 처리 (솔루션 연동·옵션 사용) | modified | SKKLID-UI-02-02-04 | component-replace | 1차 라벨링 수동 블러 → 2차 외부 비식별 솔루션 연동·옵션 처리 (SFR-09-01/02/04) |
| `FEAT-006` | feature | 비식별 결과 검토·이력 확인 | new |  | capability-add | 비식별 처리 결과 검토·이력 확인 2차 신규 (SFR-09-03) |
| `FEAT-007` | feature | 라벨링 정밀도 조절 | new |  | capability-add | 라벨링 정밀도 조절 2차 신규 (SFR-08-03, REQ-008 분리) |
| `REQ-001` | requirement | RQ-SFR-06-03 이미지 확대·축소·해상도 변경 등 영상 변형 기법 | modified | LS_DATA_AUG | capability-add | 1차 증강 기반 → 2차 해상도 변경 등 영상 변형(저작도구 잔존, 본체 외부) |
| `REQ-002` | requirement | RQ-SFR-06-04 이미지·영상 생성 프롬프트 편의성 개선 | deprecated |  | scope-shrink | 외부 생성 시스템 책임 이관, 저작도구 범위 외 |
| `REQ-003` | requirement | RQ-SFR-07-01 생성형 AI를 활용한 학습데이터 자동 생성 | modified | LS_DATA_AUG | capability-add | 1차 증강 5종 → 2차 생성형 AI 증강(WINTER/NIGHT/RAIN/RESOLUTION) 연동, 본체 외부 |
| `REQ-004` | requirement | RQ-SFR-07-02 생성된 라벨링 데이터 무결성 유지 | new |  | capability-add | 2차 신규 |
| `REQ-005` | requirement | RQ-SFR-07-03 생성 데이터의 학습데이터 활용 여부 선택 | new |  | capability-add | 2차 신규 |
| `REQ-006` | requirement | RQ-SFR-08-01 라벨링 정확도(객체 위치·경계) 향상 | modified | SKKLID-UI-02-02-06 | capability-add | 1차 Auto Labeling(YOLO) → 2차 라벨링 정확도 향상 |
| `REQ-007` | requirement | RQ-SFR-08-02 객체 외곽 경계 자동 밀착 | modified | SKKLID-UI-02-02-05 | capability-add | 1차 AI Tool(SAM) → 2차 객체 외곽 경계 자동 밀착 |
| `REQ-008` | requirement | RQ-SFR-08-03 라벨링 정밀도 조절 | modified | SKKLID-UI-02-02-05 | capability-add | 1차 AI 보조 라벨링 → 2차 라벨링 정밀도 조절 추가 |
| `REQ-009` | requirement | RQ-SFR-08-04 학습데이터 버전관리 및 변경이력 추적 | new |  | capability-add | 2차 신규(DB 스냅샷 버전관리) |
| `REQ-010` | requirement | RQ-SFR-08-05 버전별 변경 내용 비교 및 복구 | new |  | capability-add | 2차 신규 |
| `REQ-011` | requirement | RQ-SFR-08-06 데이터마트 학습데이터셋 라벨링 정보 동기화 | new |  | capability-add | 2차 신규(관제 통지) |
| `REQ-012` | requirement | RQ-SFR-09-01 수집 클립영상 개인정보 비식별화 처리 | new |  | capability-add | 2차 신규 |
| `REQ-013` | requirement | RQ-SFR-09-02 비식별화 솔루션 제공 API·가이드 활용 연동 | new |  | capability-add | 2차 신규 |
| `REQ-014` | requirement | RQ-SFR-09-03 비식별화 처리 결과 검토 및 이력관리 | new |  | capability-add | 2차 신규 |
| `REQ-015` | requirement | RQ-SFR-09-04 비식별화 솔루션 옵션 설정 기능 | new |  | capability-add | 2차 신규 |
| `REQ-016` | requirement | RQ-SFR-09-05 비식별화 처리 결과 연동 확인 기능 | new |  | capability-add | 2차 신규 |
| `REQ-017` | requirement | RQ-SFR-09-06 개인정보 보호대책 | new |  | capability-add | 2차 신규(암호화·비정상 로그인 방지·개인정보 필터) |
| `REQ-018` | requirement | RQ-SFR-09-07 학습데이터 개인정보 유형 분류체계 구축 | new |  | capability-add | 2차 신규 |
| `SCREEN-001` | screen_spec | 세션 인계 진입 화면 | preserved |  |  | 외부 JWT 인계 로그인(DFEAT-001 preserved) 진입 |
| `SCREEN-002` | screen_spec | 역할 클레임 화면 | modified |  | redesign | 2차 role+channel 클레임 분기 화면 |
| `SCREEN-003` | screen_spec | 접근 거부 화면 | new |  | capability-add | 2차 권한 없음 안내 화면 |
| `SCREEN-004` | screen_spec | 개발용 로그인 화면 | new |  | capability-add | 2차 개발 환경 전용 로그인 (운영 미사용) |
| `SCREEN-005` | screen_spec | 라벨링 캔버스 화면 | modified |  | capability-add | 1차 라벨링 캔버스 → 2차 SAM2 트랙·정밀도 고도화 |
| `SCREEN-006` | screen_spec | 마킹 화면 | new |  | capability-add | 2차 신규 마킹 화면 (DFEAT-039) |
| `SCREEN-007` | screen_spec | 영상 목록 화면 | modified |  | scope-shrink | 1차 영상/이미지 관리 → 2차 영상 단위 목록(프로젝트 제거) |
| `SCREEN-008` | screen_spec | 영상 처리 현황 화면 | modified |  | redesign | 2차 배치 파이프라인 현황 [추정 — 1차 대응 확인 필요] |
| `SCREEN-009` | screen_spec | 영상 상세 화면 | modified |  | scope-shrink | 영상 단위 상세 |
| `SCREEN-010` | screen_spec | 라벨 이력 화면 | modified |  | redesign | 1차 Gitea → 2차 DB 스냅샷 이력(FEAT-002) |
| `SCREEN-011` | screen_spec | 대시보드 화면 | preserved |  |  | 1차 대시보드 기반 |
| `SCREEN-012` | screen_spec | 작업 목록 화면 | modified |  | scope-shrink | 1차 프로젝트 단위 → 2차 영상 단위 작업 목록 |
| `SCREEN-013` | screen_spec | 작업 배정 화면 | modified |  | scope-shrink | 1차 프로젝트 단위 → 2차 영상 단위 배정(DFEAT-006) |
| `SCREEN-014` | screen_spec | 오토라벨 요약 화면 | preserved |  |  | 오토라벨 결과 요약 |
| `SCREEN-015` | screen_spec | VLM 메타 검토 화면 | new |  | capability-add | 2차 외부 VLM 시계열 메타 검토(FEAT-004) |
| `SCREEN-016` | screen_spec | 비식별 목록 화면 | modified |  | component-replace | 1차 캔버스 블러 → 2차 외부 솔루션 결과 검토(FEAT-006) |
| `SCREEN-017` | screen_spec | 비식별 상세 화면 | modified |  | component-replace | 비식별 상세·재처리(FEAT-005/006) |
| `SCREEN-018` | screen_spec | 검수 목록 화면 | modified |  | merge | 1차 1·2차 검수 → 2차 단일 검수 목록 |
| `SCREEN-019` | screen_spec | 검수 상세 화면 | modified |  | merge | 단일 검수 상세(영상 단위) |
| `SCREEN-020` | screen_spec | 작업자 통계 화면 | preserved |  |  | 1차 작업 통계 기반 |
| `SCREEN-021` | screen_spec | 전체 통계 화면 | preserved |  |  | 1차 전체 집계 통계 |
| `SCREEN-022` | screen_spec | 증강 요청 화면 | modified |  | scope-shrink | 1차 증강·내보내기 → 2차 증강만(FEAT-004) |
| `SCREEN-023` | screen_spec | 증강 결과 화면 | modified |  | redesign | 증강 결과 수락·거부(FEAT-004) |
| `SCREEN-024` | screen_spec | 사용자 관리 화면 | preserved |  |  | 1차 사용자·권한 관리 |
| `SCREEN-025` | screen_spec | 시스템 설정 화면 | new |  | capability-add | 2차 신규 시스템 설정(정밀도 조절, FEAT-007) |
| `SCREEN-026` | screen_spec | 프리셋 관리 화면 | preserved |  |  | 1차 라벨링 프리셋 관리 |
| `SCREEN-027` | screen_spec | 오토라벨 테스트 화면 (개발) | new |  | capability-add | 2차 개발용 오토라벨 테스트 (비운영) |
| `SCREEN-028` | screen_spec | 포털 홈 화면 | new |  | capability-add | 2차 외부 채널(포털) 신규 |
| `SCREEN-029` | screen_spec | 포털 라벨링 화면 | new |  | capability-add | 2차 포털 간편 라벨링·체험 |

## 📌 1차 LEGACY 폐기 추적 (deprecation_status=removed, 31건)

2차 스코프 축소로 완전 제거된 1차 산출물:
- **프로젝트 관리 일체** (16): SKKLID-UI-03-02-01~20 (LEGACY-099,100,101,103,104,105,106,107,108,109,111,113,115,116,117,118) + 메인/작업 프로젝트 리스트 02-02-01·02 (LEGACY-062,063)
- **프로젝트 API** (6): /api/v1/projects** (LEGACY-050,052,054,055,058,059)
- **게시판** (3): SKKLID-UI-02-03-02·03, 03-06-01 (LEGACY-086,087,126)
- **생성형 AI/업로더 화면** (4): SKKLID-UI-02-05-01·02·09, 03-04-01 (LEGACY-089,090,097,123)

> 잔여(다음 세션): deprecated 후보 11(1차 오토라벨 API·보조화면·영상이미지관리)·확인필요 9는 미반영. 상세는 `logicraft-sync-handoff.md`.

---

_Generated by LogiCraft `generate_brownfield_report`. 한국 공공 SI K-DOC '데이터 변경 명세서' / 운영팀 인계 자료 입력용._
