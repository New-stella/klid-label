# DOMAIN-010 라벨링 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(155건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

비식별 프레임 위에 객체를 표시해 학습데이터를 만드는 핵심 도메인. 캔버스는 konva.js 기반이다.

[라벨 형태] 바운딩박스·폴리곤·세그멘테이션·스켈레톤(COCO-17 키포인트)·SAM2 분할(클릭·박스 프롬프트)·SAM2 Track(박스→N프레임 추적 전파).

[도구] 캔버스 도구(이동·회전·확대·그리드·밝기·투명도), 객체·메타·이슈 탭, 단축키, 전체 복사/붙여넣기, 프레임 설명, 트랙 편집(병합·분할·삭제)과 선형보간.

[★비식별은 이 도메인 밖이다] 구 본문의 '블러(비식별) 처리 — 다른 라벨링 전 반드시 먼저 수행'은 폐기됐다(ADR-006). 비식별은 외부 솔루션이 파이프라인 선두에서 자동 수행하며, 라벨러는 이미 비식별된 프레임을 본다. 라벨러의 잔존 책임은 누락 발견 시 신고뿐이다.

[★2계층 저장 — 혼동 금지] ①작업 임시저장: 라벨러 저장 시 현재 작업본을 LS_DATA_LBL 에 full-replace 로 영속하고 되돌리기는 FE undo/redo(세션)로 처리한다. 학습데이터 버전이 아니다. ②학습데이터 버전: 검수 승인 시점에만 스냅샷을 LS_LABEL_VERSION 에 쌓는다 — 승인은 영상 1건을 한 트랜잭션으로 확정하지만 행은 라벨을 가진 프레임마다 1건씩이며, 같은 스냅샷인지는 프레임과 페이로드 해시의 짝으로 식별한다. 롤백은 새 버전을 적층하지 않고 대상 스냅샷 행을 재활성화하며, 라벨 본문을 작업본으로 복원하되 LBL_SN·AI 메타·TRCK_ID 까지 보존한다(PK 재발급하면 diff 가 '전량 교체'로 오분류된다).

[라벨 마스터 단일 진실원] 수동 라벨 선택은 라벨 마스터(LS_LABEL) 전체를 쓰고, 프리셋은 오토라벨 전용이다. 프리셋은 라벨명·형태를 스냅샷하지 않고 LBL_ID 로 마스터를 실시간 join 한다.

[★신고 구간 차단] 비식별 누락 신고(DE_IDNTF_YN='F') 중인 영상은 라벨 조회·이력·버전 diff/롤백이 412 로 차단된다(역할 무관). 라벨 좌표 자체가 PII 위치 특정 정보라 스트리밍만 막는 것으로는 부족하기 때문이다. 라벨은 삭제하지 않고 보존하며 해소 시 그대로 재사용한다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 바운딩박스 | 2회 클릭으로 완성하는 객체 탐지용 사각 영역 |
| 폴리곤 | 다점 클릭으로 그리는 정밀 객체 영역 분할 |
| 스켈레톤 | 사람 관절 포즈 탐지용 키포인트 집합(COCO-17) |
| 트랙(TRCK_ID) | 연속 프레임을 가로지르는 동일 객체의 식별자. 병합·분할·삭제 편집이 가능하다 |
| 작업 임시저장 | LS_DATA_LBL 에 full-replace 로 영속되는 작업본. 학습데이터 버전이 아니다 |
| 학습데이터 버전 | 검수 승인 시점에 만들어지는 영상 단위 전체 스냅샷(LS_LABEL_VERSION) |
| 객체 탭 | 우측 패널의 라벨 변경·속성·잠금·뷰어·삭제·복사 관리 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-002]] · [[ADR-003]] · [[ADR-004]] · [[ADR-006]] · [[ADR-007]] · [[ADR-009]] · [[ADR-010]] · [[ADR-019]] · [[ADR-020]] · [[ADR-022]] · [[ADR-032]] · [[ADR-033]] · [[ADR-034]] · [[ADR-036]] · [[ADR-040]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | CONST 상수값 | [[CONST-001]] · [[CONST-002]] |
| 4 | ERD 데이터 계층 | [[ERD-010]] · [[ERD-019]] |
| 5 | EVT 이벤트 계약 | [[EVT-004]] |
| 6 | API 경계 계약 | [[API-012]] · [[API-018]] · [[API-019]] · [[API-020]] · [[API-021]] · [[API-022]] · [[API-023]] · [[API-024]] · [[API-025]] · [[API-026]] · [[API-027]] · [[API-028]] · [[API-029]] · [[API-030]] · [[API-031]] · [[API-032]] · [[API-034]] · [[API-035]] · [[API-036]] · [[API-037]] · [[API-038]] · [[API-039]] · [[API-040]] · [[API-041]] · [[API-066]] · [[API-067]] · [[API-093]] · [[API-102]] · [[API-103]] · [[API-104]] · [[API-105]] · [[API-117]] · [[API-123]] · [[API-124]] · [[API-125]] · [[API-126]] · [[API-127]] · [[API-128]] · [[API-129]] · [[API-132]] · [[API-133]] · [[API-134]] · [[API-135]] · [[API-168]] · [[API-170]] · [[API-172]] · [[API-173]] · [[API-174]] · [[API-175]] · [[API-176]] · [[API-177]] · [[API-178]] · [[API-182]] · [[API-183]] · [[API-184]] · [[API-193]] · [[API-195]] · [[API-196]] · [[API-197]] |
| 7 | DFEAT 비즈니스 로직 | [[DFEAT-012]] · [[DFEAT-014]] · [[DFEAT-015]] · [[DFEAT-016]] · [[DFEAT-017]] · [[DFEAT-020]] · [[DFEAT-048]] · [[DFEAT-050]] · [[DFEAT-051]] · [[DFEAT-052]] |
| 8 | SEQ 흐름 배선 | [[SEQ-008]] · [[SEQ-009]] · [[SEQ-010]] · [[SEQ-014]] · [[SEQ-020]] · [[SEQ-021]] · [[SEQ-022]] · [[SEQ-023]] |
| 9 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 10 | SCREEN 화면 | [[SCREEN-005]] · [[SCREEN-009]] · [[SCREEN-010]] · [[SCREEN-019]] · [[SCREEN-023]] · [[SCREEN-026]] · [[SCREEN-029]] · [[SCREEN-035]] |
| 11 | UC 검증 | [[UC-004]] · [[UC-005]] · [[UC-006]] · [[UC-007]] · [[UC-008]] · [[UC-021]] · [[UC-022]] · [[UC-023]] · [[UC-028]] · [[UC-032]] |
| 12 | AC 수용 | [[AC-007]] · [[AC-008]] · [[AC-017]] · [[AC-020]] · [[AC-021]] · [[AC-023]] · [[AC-024]] |
| 13 | TEST 통합시험 | [[TEST-002]] · [[TEST-004]] |
| 14 | CDIAG 클래스 구조 | [[CDIAG-004]] · [[CDIAG-015]] |
| 15 | C4 컴포넌트 | [[CMP-004]] · [[CMP-006]] |
| 16 | FEAT 상위 기능 | [[FEAT-002]] · [[FEAT-005]] · [[FEAT-007]] · [[FEAT-009]] |
| 17 | SD 고충실 시안 | [[SD-002]] · [[SD-006]] · [[SD-022]] · [[SD-032]] |

> ⚠ 이번 키트에 **0건**인 단계: INT 외부 연동 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 상수 값 (매직넘버 단일 진실원 — 인라인 추정 금지)

| CONST | name | value | kind | 사용처 |
|---|---|---|---|---|
| [[CONST-001]] | COCO17_KEYPOINT_SKELETON | {"keypointNames":["nose","left_eye","right_eye","left_ear","right_ear","left_shoulder","right_shoulder","left_elbow","ri | enum | [[API-019]] |
| [[CONST-002]] | CocoClasses.LABELS | ["person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light","fire hydrant","stop sign | enum | [[ERD-019]] |

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| implemented | 77 |
| planned | 49 |
| (미기재) | 29 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-012]] | api_endpoint | implemented | 100 |
| [[API-018]] | api_endpoint | implemented | 100 |
| [[API-019]] | api_endpoint | implemented | 100 |
| [[API-020]] | api_endpoint | implemented | 100 |
| [[API-021]] | api_endpoint | implemented | 100 |
| [[API-022]] | api_endpoint | implemented | 100 |
| [[API-023]] | api_endpoint | implemented | 100 |
| [[API-024]] | api_endpoint | implemented | 100 |
| [[API-025]] | api_endpoint | implemented | 100 |
| [[API-026]] | api_endpoint | implemented | 100 |
| [[API-027]] | api_endpoint | implemented | 100 |
| [[API-028]] | api_endpoint | implemented | 100 |
| [[API-029]] | api_endpoint | implemented | 100 |
| [[API-030]] | api_endpoint | implemented | 100 |
| [[API-031]] | api_endpoint | implemented | 100 |
| [[API-032]] | api_endpoint | implemented | 100 |
| [[API-034]] | api_endpoint | implemented | 100 |
| [[API-035]] | api_endpoint | implemented | 100 |
| [[API-036]] | api_endpoint | implemented | 100 |
| [[API-037]] | api_endpoint | implemented | 100 |
| [[API-038]] | api_endpoint | implemented | 100 |
| [[API-039]] | api_endpoint | implemented | 100 |
| [[API-040]] | api_endpoint | implemented | 100 |
| [[API-041]] | api_endpoint | implemented | 100 |
| [[API-066]] | api_endpoint | implemented | 100 |
| [[API-067]] | api_endpoint | implemented | 100 |
| [[API-093]] | api_endpoint | implemented | 100 |
| [[API-102]] | api_endpoint | implemented | 100 |
| [[API-103]] | api_endpoint | implemented | 100 |
| [[API-104]] | api_endpoint | implemented | 100 |
| [[API-105]] | api_endpoint | implemented | 100 |
| [[API-117]] | api_endpoint | implemented | 100 |
| [[API-123]] | api_endpoint | implemented | 100 |
| [[API-124]] | api_endpoint | implemented | 100 |
| [[API-125]] | api_endpoint | implemented | 100 |
| [[API-126]] | api_endpoint | implemented | 100 |
| [[API-127]] | api_endpoint | implemented | 100 |
| [[API-128]] | api_endpoint | implemented | 100 |
| [[API-129]] | api_endpoint | implemented | 100 |
| [[API-168]] | api_endpoint | implemented | 100 |
| [[API-170]] | api_endpoint | implemented | 100 |
| [[API-172]] | api_endpoint | implemented | 100 |
| [[API-173]] | api_endpoint | implemented | 100 |
| [[API-174]] | api_endpoint | implemented | 100 |
| [[API-175]] | api_endpoint | implemented | 100 |
| [[API-176]] | api_endpoint | implemented | 100 |
| [[API-177]] | api_endpoint | implemented | 100 |
| [[API-178]] | api_endpoint | implemented | 100 |
| [[API-182]] | api_endpoint | implemented | 100 |
| [[API-193]] | api_endpoint | implemented | 100 |
| [[DFEAT-012]] | domain_feature | implemented | 100 |
| [[DFEAT-014]] | domain_feature | implemented | 100 |
| [[DFEAT-015]] | domain_feature | implemented | 100 |
| [[DFEAT-016]] | domain_feature | implemented | 100 |
| [[DFEAT-017]] | domain_feature | implemented | 100 |
| [[DFEAT-020]] | domain_feature | implemented | 100 |
| [[DFEAT-051]] | domain_feature | implemented | 100 |
| [[DFEAT-052]] | domain_feature | implemented | 100 |
| [[EVT-004]] | domain_event | implemented | 100 |
| [[FEAT-002]] | feature | implemented | 100 |
| [[FEAT-005]] | feature | implemented | 100 |
| [[FEAT-007]] | feature | implemented | 100 |
| [[SCREEN-005]] | screen_spec | implemented | 100 |
| [[SCREEN-009]] | screen_spec | implemented | 100 |
| [[SCREEN-010]] | screen_spec | implemented | 100 |
| [[SCREEN-019]] | screen_spec | implemented | 100 |
| [[SCREEN-023]] | screen_spec | implemented | 100 |
| [[SCREEN-026]] | screen_spec | implemented | 100 |
| [[SCREEN-029]] | screen_spec | implemented | 100 |
| [[SEQ-008]] | diagram_sequence | implemented | 0 |
| [[SEQ-009]] | diagram_sequence | implemented | 0 |
| [[SEQ-010]] | diagram_sequence | implemented | 100 |
| [[UC-004]] | use_case | implemented | 100 |
| [[UC-005]] | use_case | implemented | 100 |
| [[UC-006]] | use_case | implemented | 100 |
| [[UC-007]] | use_case | implemented | 100 |
| [[UC-008]] | use_case | implemented | 100 |
| [[AC-007]] | acceptance | planned | 0 |
| [[AC-008]] | acceptance | planned | 0 |
| [[AC-017]] | acceptance | planned | 0 |
| [[AC-020]] | acceptance | planned | 0 |
| [[AC-021]] | acceptance | planned | 0 |
| [[AC-023]] | acceptance | planned | 0 |
| [[AC-024]] | acceptance | planned | 0 |
| [[API-132]] | api_endpoint | planned | 0 |
| [[API-133]] | api_endpoint | planned | 0 |
| [[API-134]] | api_endpoint | planned | 0 |
| [[API-135]] | api_endpoint | planned | 0 |
| [[API-183]] | api_endpoint | planned | 0 |
| [[API-184]] | api_endpoint | planned | 0 |
| [[API-195]] | api_endpoint | planned | 0 |
| [[API-196]] | api_endpoint | planned | 0 |
| [[API-197]] | api_endpoint | planned | 0 |
| [[DFEAT-048]] | domain_feature | planned | 0 |
| [[DFEAT-050]] | domain_feature | planned | 0 |
| [[ERD-010]] | erd | planned | 0 |
| [[ERD-019]] | erd | planned | 0 |
| [[FEAT-009]] | feature | planned | 0 |
| [[NFR-008]] | nfr | planned | 0 |
| [[NFR-009]] | nfr | planned | 0 |
| [[NFR-010]] | nfr | planned | 0 |
| [[NFR-011]] | nfr | planned | 0 |
| [[NFR-012]] | nfr | planned | 0 |
| [[NFR-013]] | nfr | planned | 0 |
| [[NFR-014]] | nfr | planned | 0 |
| [[NFR-015]] | nfr | planned | 0 |
| [[NFR-016]] | nfr | planned | 0 |
| [[NFR-017]] | nfr | planned | 0 |
| [[NFR-018]] | nfr | planned | 0 |
| [[NFR-019]] | nfr | planned | 0 |
| [[NFR-020]] | nfr | planned | 0 |
| [[NFR-021]] | nfr | planned | 0 |
| [[ROLE-001]] | permission_role | planned | 0 |
| [[ROLE-002]] | permission_role | planned | 0 |
| [[ROLE-003]] | permission_role | planned | 0 |
| [[SCREEN-035]] | screen_spec | planned | 0 |
| [[SEQ-014]] | diagram_sequence | planned | 0 |
| [[SEQ-020]] | diagram_sequence | planned | 0 |
| [[SEQ-021]] | diagram_sequence | planned | 0 |
| [[SEQ-022]] | diagram_sequence | planned | 0 |
| [[SEQ-023]] | diagram_sequence | planned | 0 |
| [[UC-021]] | use_case | planned | 0 |
| [[UC-022]] | use_case | planned | 0 |
| [[UC-023]] | use_case | planned | 0 |
| [[UC-028]] | use_case | planned | 0 |
| [[UC-032]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (16)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-002]] — 검수 워크플로우 단일화 — 2차 검수 폐기
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-004]] — 생성형 AI 본체 외부화 — 저작도구는 증강 결과 검수만
- [[ADR-006]] — 비식별 처리 외부 솔루션 연동 — 캔버스 수동 블러 폐기
- [[ADR-007]] — 관제서버 연동 — 양방향 M2M 폐기, 단방향 outbound 통지 채택
- [[ADR-009]] — 라벨 버전관리 Gitea 제거 — DB 스냅샷 기반 전환
- [[ADR-010]] — DBMS MariaDB에서 PostgreSQL로 전환
- [[ADR-019]] — 오토라벨 프리셋↔검출라벨 매칭축을 마스터 라벨명에서 COCO 검출클래스(DTCT_TYPE_CD)로 일원화
- [[ADR-020]] — 검수 승인 학습데이터 export(NIA JSON) 산출을 저작도구 범위로 포함 (ADR-005 supersede)
- [[ADR-022]] — 비식별 신고 게이트 아키텍처 — 판정범위 자기행 단일원천·엔드포인트별 응답코드·해소시 복구범위
- [[ADR-032]] — 촬영환경·개인정보 메타 수동입력 신설(+self-fill 자동파생 폐기)
- [[ADR-033]] — 라벨 변경이력 재설계 — 저장이벤트=diff + full-replace + 되돌리기
- [[ADR-034]] — 라벨 프리셋 = 라벨 마스터 단일 진실원(LBL_ID FK)
- [[ADR-036]] — event_annotation(VLM VQA/CoT) 메타탭 수동편집 + 승인시 export 동결
- [[ADR-040]] — AI 추적(SAM2/YOLO Track) 출력 형태 = 선택 객체 형태 고정

### nfr (14)
- [[NFR-008]] — 학습데이터 단계별 품질관리 기준 (수집·제작·검수)
- [[NFR-009]] — 학습데이터 종류·제작방법별 품질관리 기준
- [[NFR-010]] — 학습데이터 값 검증·정합성 (공공데이터 품질진단 기준)
- [[NFR-011]] — 화면 응답시간 기준
- [[NFR-012]] — 시스템 자원 효율
- [[NFR-013]] — 세션·계정 보안대책
- [[NFR-014]] — 시큐어코딩 (SW 개발보안)
- [[NFR-015]] — 웹표준·크로스브라우징
- [[NFR-016]] — 데이터 표준 준수
- [[NFR-017]] — API 호출 규약 (Base URL /api · Bearer JWT 인증 · 채널 격리)
- [[NFR-018]] — 기능 수행 지연 사전 안내
- [[NFR-019]] — 오류 응답 속도
- [[NFR-020]] — 역할별 접근제어
- [[NFR-021]] — 취약점 점검·모의해킹

### constant (2)
- [[CONST-001]] — COCO-17 키포인트 스켈레톤 상수
- [[CONST-002]] — CocoClasses — COCO-80 검출 클래스 allowlist

### erd (2)
- [[ERD-010]] — 라벨링 ERD (고도화, PostgreSQL)
- [[ERD-019]] — 라벨 마스터·버전관리 ERD (고도화, PostgreSQL)

### domain_event (1)
- [[EVT-004]] — TaskModified

### api_endpoint (59)
- [[API-012]] — POST /v1/reviews/{videoId}/submit
- [[API-018]] — GET /v1/frames/{srcSn}/labels
- [[API-019]] — PUT /v1/frames/{srcSn}/labels
- [[API-020]] — POST /v1/frames/{srcSn}/sam2-track
- [[API-021]] — GET /v1/frames/{srcSn}/image
- [[API-022]] — GET /v1/labels/{lblSn}/attrs
- [[API-023]] — PUT /v1/labels/{lblSn}/attrs
- [[API-024]] — GET /v1/manage/labels
- [[API-025]] — POST /v1/manage/labels
- [[API-026]] — PUT /v1/manage/labels/{id}
- [[API-027]] — DELETE /v1/manage/labels/{id}
- [[API-028]] — GET /v1/manage/labels/{labelId}/attrs
- [[API-029]] — POST /v1/manage/labels/{labelId}/attrs
- [[API-030]] — PUT /v1/manage/labels/{labelId}/attrs/{attrId}
- [[API-031]] — DELETE /v1/manage/labels/{labelId}/attrs/{attrId}
- [[API-032]] — POST /v1/labels/{srcSn}/deident-report
- [[API-034]] — GET /v1/frames/{srcSn}/versions
- [[API-035]] — GET /v1/versions/{version}/diff
- [[API-036]] — POST /v1/versions/{version}/rollback
- [[API-037]] — GET /v1/manage/presets
- [[API-038]] — POST /v1/manage/presets
- [[API-039]] — PUT /v1/manage/presets/{id}
- [[API-040]] — DELETE /v1/manage/presets/{id}
- [[API-041]] — POST /v1/manage/presets/{id}/clone
- [[API-066]] — GET /v1/frames/{srcSn}/meta
- [[API-067]] — PUT /v1/frames/{srcSn}/meta
- [[API-093]] — POST /v1/frames/{srcSn}/sam2-segment
- [[API-102]] — POST /v1/videos/{rawSn}/issues
- [[API-103]] — GET /v1/videos/{rawSn}/issues
- [[API-104]] — POST /v1/issues/{issueSn}/comments
- [[API-105]] — POST /v1/issues/{issueSn}/resolve
- [[API-117]] — GET /v1/event-types/labels
- [[API-123]] — POST /v1/frames/{srcSn}/yolo-track
- [[API-124]] — POST /v1/frames/{srcSn}/autolabel
- [[API-125]] — POST /v1/videos/{rawSn}/tracks/merge
- [[API-126]] — DELETE /v1/videos/{rawSn}/tracks/{trackId}
- [[API-127]] — POST /v1/videos/{rawSn}/tracks/{trackId}/split
- [[API-128]] — GET /v1/frames/{srcSn}/description
- [[API-129]] — PUT /v1/frames/{srcSn}/description
- [[API-132]] — GET /v1/videos/{rawSn}/event-annotation
- [[API-133]] — POST /v1/videos/{rawSn}/event-annotation/approve
- [[API-134]] — PUT /v1/videos/{rawSn}/event-annotation
- [[API-135]] — POST /v1/videos/{rawSn}/event-annotation/reject
- [[API-168]] — GET /v1/videos/{rawSn}/environment-meta
- [[API-170]] — PUT /v1/videos/{rawSn}/environment-meta
- [[API-172]] — GET /v1/frames/{srcSn}/privacy-meta
- [[API-173]] — PUT /v1/frames/{srcSn}/privacy-meta
- [[API-174]] — PUT /v1/frames/privacy-meta
- [[API-175]] — GET /v1/frames/{srcSn}/deid-image
- [[API-176]] — GET /v1/frames/{srcSn}/label-history
- [[API-177]] — GET /v1/manage/labels/detect-candidates
- [[API-178]] — POST /v1/reviews/{videoId}/cancel-submit
- [[API-182]] — GET /v1/versions/{version}/diff-with-working
- [[API-183]] — GET /v1/videos/{rawSn}/privacy-meta
- [[API-184]] — PUT /v1/videos/{rawSn}/privacy-meta
- [[API-193]] — GET /v1/ai-defaults
- [[API-195]] — GET /v1/videos/{rawSn}/versions/{version}/labels
- [[API-196]] — PUT /v1/videos/{rawSn}/labels
- [[API-197]] — GET /v1/videos/{rawSn}/versions

### domain_feature (10)
- [[DFEAT-012]] — 도형 어노테이션 (바운딩박스·폴리곤·스켈레톤)
- [[DFEAT-014]] — 캔버스 도구 (그리드·밝기·투명도·이동/회전/확대)
- [[DFEAT-015]] — 객체·메타·이슈 탭
- [[DFEAT-016]] — 단축키·전체 복사/붙여넣기
- [[DFEAT-017]] — 학습데이터 저장
- [[DFEAT-020]] — 트랙 모드 (선형보간 연속 프레임 추적)
- [[DFEAT-048]] — 비식별 누락 신고
- [[DFEAT-050]] — 이벤트 어노테이션(VQA/CoT) 검토·수정
- [[DFEAT-051]] — 촬영환경·개인정보 메타 수동입력
- [[DFEAT-052]] — 라벨 클래스·속성 정의 관리

### diagram_sequence (8)
- [[SEQ-008]] — 라벨 버전 저장·이력 추적 — 검수 승인 시점 스냅샷 생성
- [[SEQ-009]] — 버전 비교·복구 — DB 스냅샷 diff부터 롤백 active 복원까지
- [[SEQ-010]] — 데이터마트 수정 통지 — 검수완료 후 수정부터 TASK_MODIFIED push까지
- [[SEQ-014]] — 비식별 누락 신고·수동 해소 — 신고부터 RESOLVED까지
- [[SEQ-020]] — 라벨 편집·임시저장 시퀀스
- [[SEQ-021]] — 라벨 클래스·속성 정의 관리 시퀀스
- [[SEQ-022]] — 라벨 프리셋 CRUD 관리 시퀀스
- [[SEQ-023]] — VLM 시계열 메타 검토 — 결과 콜백 수신부터 검토·수정·승인 확정까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (8)
- [[SCREEN-005]] — 라벨링 캔버스 화면
- [[SCREEN-009]] — 영상 상세 화면
- [[SCREEN-010]] — 로드 버전 선택
- [[SCREEN-019]] — 검수 상세 화면
- [[SCREEN-023]] — 증강 결과 화면
- [[SCREEN-026]] — 프리셋 관리 화면
- [[SCREEN-029]] — 포털 라벨링 화면
- [[SCREEN-035]] — 라벨 관리 화면

### use_case (10)
- [[UC-004]] — 객체 자동 추적
- [[UC-005]] — 객체 외곽 경계 자동 밀착
- [[UC-006]] — 라벨링 정밀도 조절
- [[UC-007]] — 라벨 버전 저장·이력 추적
- [[UC-008]] — 버전 비교·복구
- [[UC-021]] — 라벨 편집·임시저장
- [[UC-022]] — VLM 시계열 메타 검토
- [[UC-023]] — 검수 승인·반려
- [[UC-028]] — 라벨 클래스·속성 정의 관리
- [[UC-032]] — 라벨 프리셋 CRUD 관리

### acceptance (7)
- [[AC-007]] — 라벨 버전 스냅샷 저장·해시 식별
- [[AC-008]] — 버전 diff 비교·롤백 복구
- [[AC-017]] — 실영상 라벨링·메타 가공
- [[AC-020]] — 다양한 환경·산불 유형 학습데이터 제작
- [[AC-021]] — 생성된 영상 라벨링으로 학습데이터셋 편입
- [[AC-023]] — 이미지 학습데이터 가공(추출·라벨링·가명·검수)
- [[AC-024]] — 영상 학습데이터 가공(라벨링·메타·VLM 시계열 메타 검수)

### test_scenario (2)
- [[TEST-002]] — 외부 VLM 시계열 메타 위탁·콜백 수신 정상 흐름
- [[TEST-004]] — 검수 완료·수정에 따른 관제서버 단방향 통지 정상 흐름

### class_diagram (2)
- [[CDIAG-004]] — 라벨링 도메인 모델
- [[CDIAG-015]] — 버전관리 도메인 모델

### diagram_c4_component (2)
- [[CMP-004]] — 라벨링 컴포넌트 (라벨 편집·속성·SAM2 트랙)
- [[CMP-006]] — 버전관리 컴포넌트 (스냅샷·diff·롤백)

### feature (4)
- [[FEAT-002]] — 라벨 버전관리·비교·복구
- [[FEAT-005]] — 개인정보 비식별 처리 (솔루션 연동·옵션 사용)
- [[FEAT-007]] — 라벨링 정밀도 조절
- [[FEAT-009]] — VLM 시계열 메타 검토·수정

### screen_design (4)
- [[SD-002]] — SCREEN-005 라벨링 캔버스 화면
- [[SD-006]] — SCREEN-026 프리셋 관리 화면
- [[SD-022]] — SCREEN-035 라벨 관리 화면
- [[SD-032]] — SCREEN-010 로드 버전 선택
