# DOMAIN-004 AI 보조 라벨링 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(101건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

라벨러의 수작업을 AI 추론으로 보조하는 도메인. 추론은 상태 없는 ai-server(FastAPI)가 수행하고 Spring Boot 가 오케스트레이션을 맡는다.

[구성]
· Auto Labeling — YOLOX(onnxruntime) 객체 탐지. ★ultralytics(AGPL-3.0)를 쓰지 않고 permissive 백엔드로 단일화했다(ADR-041).
· AI Tool — SAM2 클릭/박스 프롬프트 분할(폴리곤 + 신뢰도 반환).
· SAM2 Track — 박스를 N 프레임에 전파하는 VOS 추적. 추적 출력 형태는 선택한 객체의 형태를 따른다(ADR-040).
· 트랙 모드 — 프레임 간 좌표 선형보간(CVAT 알고리즘 포팅).

[★매칭축은 COCO 검출클래스다 (ADR-019)] 프리셋↔검출 라벨 매칭을 마스터 라벨명(한글)이 아니라 LS_LABEL.DTCT_TYPE_CD(COCO 80 클래스명)로 일원화했다. 한글 라벨명과 COCO 영문명이 1:1 대응하지 않기 때문이다. 배치·온라인·SAM2·프리셋 토글 4경로가 모두 같은 축을 쓴다. 매핑이 없는 라벨은 화면에 보이되 선택 불가이며, 강제는 BE 가 화이트리스트 교집합으로 건다 — FE 요청을 신뢰하지 않는다.

[★2경로의 저장 시맨틱이 다르다] 배치 오토라벨은 결과를 DB 에 저장하고, 온라인 오토라벨은 반환만 하고 저장하지 않는다(사용자가 캔버스에서 확인 후 저장).

[실행 대상] YOLO/SAM2 는 원본 이미지에만 실행하고 해상도가 같으므로 비식별본과 좌표를 공유한다(별도 실행 없음). 추론 서버 호출은 단일 클라이언트로 통일하고 타임아웃·서킷브레이커를 적용한다.

[범위 밖] 모델 학습·파인튜닝은 외부 책임이다. FE 문구에는 YOLO/SAM2 같은 기술 모델명을 노출하지 않는다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| AI Tool | 객체 일부를 클릭하거나 박스를 그리면 경계를 자동 분할해 주는 도구(SAM2) |
| Auto Labeling | 사람·차량 등을 자동 탐지해 라벨을 생성하는 도구(YOLOX) |
| DTCT_TYPE_CD | 라벨 마스터의 COCO 검출클래스 매핑 컬럼. 오토라벨 매칭의 단일 진실원이다 |
| SAM2 Track | 박스 하나로 N 프레임까지 추적을 전파하는 VOS 기능 |
| 선형보간 | 트랙 프레임 간 좌표를 Linear Interpolation 으로 채우는 알고리즘 |
| 온라인 오토라벨 | 사용자가 화면에서 즉시 돌리는 추론. 결과를 반환만 하고 저장하지 않는다 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-003]] · [[ADR-019]] · [[ADR-026]] · [[ADR-031]] · [[ADR-035]] · [[ADR-039]] · [[ADR-040]] · [[ADR-041]] · [[ADR-046]] · [[ADR-047]] · [[ADR-052]] · [[ADR-053]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | CONST 상수값 | [[CONST-002]] |
| 4 | ERD 데이터 계층 | [[ERD-032]] |
| 5 | API 경계 계약 | [[API-020]] · [[API-043]] · [[API-065]] · [[API-093]] · [[API-113]] · [[API-119]] · [[API-120]] · [[API-121]] · [[API-122]] · [[API-123]] · [[API-124]] · [[API-125]] · [[API-126]] · [[API-127]] · [[API-152]] · [[API-156]] · [[API-158]] · [[API-160]] · [[API-162]] · [[API-164]] · [[API-177]] · [[API-204]] · [[API-216]] · [[API-217]] · [[API-218]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-018]] · [[DFEAT-019]] · [[DFEAT-020]] |
| 7 | SEQ 흐름 배선 | [[SEQ-005]] · [[SEQ-006]] · [[SEQ-007]] · [[SEQ-023]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-005]] · [[SCREEN-025]] · [[SCREEN-027]] |
| 10 | UC 검증 | [[UC-004]] · [[UC-005]] · [[UC-006]] · [[UC-034]] · [[UC-037]] |
| 11 | AC 수용 | [[AC-004]] · [[AC-005]] · [[AC-006]] · [[AC-038]] · [[AC-039]] · [[AC-040]] · [[AC-099]] · [[AC-100]] · [[AC-101]] · [[AC-102]] · [[AC-103]] · [[AC-104]] · [[AC-105]] · [[AC-106]] |
| 12 | CDIAG 클래스 구조 | [[CDIAG-005]] |
| 13 | C4 컴포넌트 | [[CMP-008]] |
| 14 | FEAT 상위 기능 | [[FEAT-001]] · [[FEAT-007]] · [[FEAT-009]] |
| 15 | SD 고충실 시안 | [[SD-033]] |

> ⚠ 이번 키트에 **0건**인 단계: EVT 이벤트 계약, TEST 통합시험, INT 외부 연동 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 상수 값 (매직넘버 단일 진실원 — 인라인 추정 금지)

| CONST | name | value | kind | 사용처 |
|---|---|---|---|---|
| [[CONST-002]] | CocoClasses.LABELS | ["person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light","fire hydrant","stop sign | enum | ⚠️ 미연결 |

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| implemented | 39 |
| planned | 37 |
| (미기재) | 25 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[AC-038]] | acceptance | implemented | 90 |
| [[AC-039]] | acceptance | implemented | 90 |
| [[AC-040]] | acceptance | implemented | 90 |
| [[API-020]] | api_endpoint | implemented | 100 |
| [[API-043]] | api_endpoint | implemented | 100 |
| [[API-065]] | api_endpoint | implemented | 100 |
| [[API-093]] | api_endpoint | implemented | 100 |
| [[API-113]] | api_endpoint | implemented | 100 |
| [[API-119]] | api_endpoint | implemented | 100 |
| [[API-120]] | api_endpoint | implemented | 100 |
| [[API-121]] | api_endpoint | implemented | 100 |
| [[API-122]] | api_endpoint | implemented | 100 |
| [[API-123]] | api_endpoint | implemented | 100 |
| [[API-124]] | api_endpoint | implemented | 100 |
| [[API-125]] | api_endpoint | implemented | 100 |
| [[API-126]] | api_endpoint | implemented | 100 |
| [[API-127]] | api_endpoint | implemented | 100 |
| [[API-152]] | api_endpoint | implemented | 100 |
| [[API-156]] | api_endpoint | implemented | 100 |
| [[API-158]] | api_endpoint | implemented | 100 |
| [[API-160]] | api_endpoint | implemented | 100 |
| [[API-162]] | api_endpoint | implemented | 100 |
| [[API-164]] | api_endpoint | implemented | 100 |
| [[API-177]] | api_endpoint | implemented | 100 |
| [[DFEAT-018]] | domain_feature | implemented | 100 |
| [[DFEAT-019]] | domain_feature | implemented | 100 |
| [[DFEAT-020]] | domain_feature | implemented | 100 |
| [[FEAT-001]] | feature | implemented | 100 |
| [[FEAT-007]] | feature | implemented | 100 |
| [[SCREEN-005]] | screen_spec | implemented | 100 |
| [[SCREEN-025]] | screen_spec | implemented | 100 |
| [[SCREEN-027]] | screen_spec | implemented | 100 |
| [[SEQ-005]] | diagram_sequence | implemented | 0 |
| [[SEQ-006]] | diagram_sequence | implemented | 0 |
| [[SEQ-007]] | diagram_sequence | implemented | 0 |
| [[UC-004]] | use_case | implemented | 100 |
| [[UC-005]] | use_case | implemented | 100 |
| [[UC-006]] | use_case | implemented | 100 |
| [[UC-034]] | use_case | implemented | 100 |
| [[AC-004]] | acceptance | planned | 0 |
| [[AC-005]] | acceptance | planned | 0 |
| [[AC-006]] | acceptance | planned | 0 |
| [[AC-099]] | acceptance | planned | 0 |
| [[AC-100]] | acceptance | planned | 0 |
| [[AC-101]] | acceptance | planned | 0 |
| [[AC-102]] | acceptance | planned | 0 |
| [[AC-103]] | acceptance | planned | 0 |
| [[AC-104]] | acceptance | planned | 0 |
| [[AC-105]] | acceptance | planned | 0 |
| [[AC-106]] | acceptance | planned | 0 |
| [[API-204]] | api_endpoint | planned | 0 |
| [[API-216]] | api_endpoint | planned | 0 |
| [[API-217]] | api_endpoint | planned | 0 |
| [[API-218]] | api_endpoint | planned | 0 |
| [[ERD-032]] | erd | planned | 0 |
| [[FEAT-009]] | feature | planned | 0 |
| [[INFRA-001]] | infra_component | planned | 0 |
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
| [[SEQ-023]] | diagram_sequence | planned | 0 |
| [[UC-037]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (13)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-019]] — 오토라벨 프리셋↔검출라벨 매칭축을 마스터 라벨명에서 COCO 검출클래스(DTCT_TYPE_CD)로 일원화
- [[ADR-026]] — AI 추론 프레임 전송은 비식별본만 사용 — 원본 폴백 금지 (포털 SAM2 전제는 폐기)
- [[ADR-031]] — VLM·증강 콜백 무서명 규격 전환 + 3계층 방어(IP allowlist·rate limit·request_id)
- [[ADR-035]] — SFR-08-01 해석 확정 = VOS(추적+분할)
- [[ADR-039]] — dev 로그인/dev 업로드 — prd 빌드 env 토글 허용(기본 OFF, fail-closed)
- [[ADR-040]] — AI 추적(SAM2/YOLO Track) 출력 형태 = 선택 객체 형태 고정
- [[ADR-041]] — ai-server 탐지 백엔드 = YOLOX 단일화(RT-DETRv2 제거)
- [[ADR-046]] — 연동 서버 주소를 운영 화면에서 재기동 없이 교체 — 저장 축 IP 대역 차단 폐지 + 관리자 단기 유효창
- [[ADR-047]] — 시작 객체 없는 다중 객체 자동 검출·추적을 1차 계승으로 둔다
- [[ADR-052]] — 외부 제공 이벤트 마킹은 업로드 시 예약하고 비식별 완료 후 적용한다
- [[ADR-053]] — 외부 마킹 산출물은 경로를 훑어 짝을 찾고 확인한 뒤 일괄로 받아들인다

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

### constant (1)
- [[CONST-002]] — CocoClasses — COCO-80 검출 클래스 allowlist

### erd (1)
- [[ERD-032]] — 마킹 산출물 일괄 업로드 작업 ERD (고도화, PostgreSQL)

### api_endpoint (25)
- [[API-020]] — POST /v1/frames/{srcSn}/sam2-track
- [[API-043]] — GET /v1/videos/{rawSn}
- [[API-065]] — POST /v1/vlm/callback
- [[API-093]] — POST /v1/frames/{srcSn}/sam2-segment
- [[API-113]] — POST /infer/yolo/predict
- [[API-119]] — POST /infer/yolo/track
- [[API-120]] — POST /infer/sam2/segment
- [[API-121]] — POST /infer/sam2/track
- [[API-122]] — POST /infer/vlm/verify-objects
- [[API-123]] — POST /v1/frames/{srcSn}/yolo-track
- [[API-124]] — POST /v1/frames/{srcSn}/autolabel
- [[API-125]] — POST /v1/videos/{rawSn}/tracks/merge
- [[API-126]] — DELETE /v1/videos/{rawSn}/tracks/{trackId}
- [[API-127]] — POST /v1/videos/{rawSn}/tracks/{trackId}/split
- [[API-152]] — POST /v1/dev/upload
- [[API-156]] — OPTIONS /v1/uploads
- [[API-158]] — POST /v1/uploads
- [[API-160]] — HEAD /v1/uploads/{uploadId}
- [[API-162]] — PATCH /v1/uploads/{uploadId}
- [[API-164]] — DELETE /v1/uploads/{uploadId}
- [[API-177]] — GET /v1/manage/labels/detect-candidates
- [[API-204]] — POST /v1/ai-requests/{requestId}/cancel
- [[API-216]] — 마킹 산출물 폴더 검사
- [[API-217]] — 마킹 산출물 일괄 적재
- [[API-218]] — 일괄 적재 진행 조회

### domain_feature (3)
- [[DFEAT-018]] — AI Tool (SAM 클릭 세그멘테이션)
- [[DFEAT-019]] — Auto Labeling (YOLO 객체 탐지)
- [[DFEAT-020]] — 트랙 모드 (선형보간 연속 프레임 추적)

### diagram_sequence (4)
- [[SEQ-005]] — 객체 자동 추적 — SAM2 시드부터 트랙 전파·보간까지
- [[SEQ-006]] — 객체 외곽 경계 자동 밀착 — 시드 송신부터 폴리곤 밀착까지
- [[SEQ-007]] — 라벨링 정밀도 조절 — 설정 변경부터 SAM2 단순화 적용까지
- [[SEQ-023]] — VLM 시계열 메타 검토 — 결과 콜백 수신부터 검토·수정·승인 확정까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (3)
- [[SCREEN-005]] — 라벨링 캔버스 화면
- [[SCREEN-025]] — 시스템 설정 화면
- [[SCREEN-027]] — 수동 업로드

### use_case (5)
- [[UC-004]] — 객체 자동 추적
- [[UC-005]] — 객체 외곽 경계 자동 밀착
- [[UC-006]] — 라벨링 정밀도 조절
- [[UC-034]] — 온디맨드 AI 자동 추적
- [[UC-037]] — 마킹이 끝난 영상 일괄 올리기

### acceptance (14)
- [[AC-004]] — 객체 자동 추적(SAM2) 수행
- [[AC-005]] — 객체 외곽 경계 자동 밀착
- [[AC-006]] — 라벨링 정밀도(폴리곤 단순화) 조절
- [[AC-038]] — 온디맨드 AI 자동 추적 — 진입점 구분과 시작 객체 없는 실행
- [[AC-039]] — 온디맨드 AI 자동 추적 — 결과 적용 방식과 수락 입도, 확정 시점
- [[AC-040]] — 온디맨드 AI 자동 추적 — 라벨 마스터 식별자 전달
- [[AC-099]] — 검사는 아무것도 저장하지 않고 짝 목록만 돌려준다
- [[AC-100]] — 짝은 폴더 구조가 아니라 마킹 문서가 적어 둔 영상 이름으로 짓는다
- [[AC-101]] — 허용 범위 밖 경로와 바로가기는 읽지 않고 상한은 조용히 자르지 않는다
- [[AC-102]] — 일괄 적재 요청은 곧바로 반환하고 진행은 따로 조회한다
- [[AC-103]] — 한 건이 실패해도 나머지가 진행되고 사유가 남는다
- [[AC-104]] — 서버가 다시 떠도 처리 도중이던 항목이 방치되지 않는다
- [[AC-105]] — 적재한 영상의 마킹은 예약으로 만들어져 비식별이 끝난 뒤 활성화된다
- [[AC-106]] — 외부가 준 프레임 이미지는 적재하지 않고 속도가 어긋나면 멈춘다

### class_diagram (1)
- [[CDIAG-005]] — AI 보조 라벨링 도메인 모델

### diagram_c4_component (1)
- [[CMP-008]] — VLM 메타 컴포넌트 (시계열 위탁·콜백 적재·검토)

### feature (3)
- [[FEAT-001]] — AI 보조 라벨링 (객체 추적·분할(VOS)·외곽 경계 밀착)
- [[FEAT-007]] — 라벨링 정밀도 조절
- [[FEAT-009]] — VLM 시계열 메타 검토·수정

### screen_design (1)
- [[SD-033]] — SCREEN-027 영상 업로드

### app_shell (1)
- [[SHELL-001]] — 저작도구 내부 채널 셸

### infra_component (1)
- [[INFRA-001]] — ai-server (YOLO/SAM2 추론 서버, 내부)

### legacy_artifact (4)
- [[LEGACY-005]] — [module] 영상분할 (PF-005)
- [[LEGACY-066]] — [screen] SKKLID-UI-02-02-05 데이터 작업 - AI Tool 활용
- [[LEGACY-067]] — [screen] SKKLID-UI-02-02-06 데이터 작업 - 오토 라벨링 기능
- [[LEGACY-096]] — [screen] SKKLID-UI-02-05-08 업로드/생성 - 트래킹 기능

### model_usage (2)
- [[MODEL-001]] — 객체 탐지 모델 — YOLOX (ONNX Runtime)
- [[MODEL-002]] — 분할 모델 — Meta SAM2
