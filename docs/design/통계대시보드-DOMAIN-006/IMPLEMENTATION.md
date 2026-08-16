# DOMAIN-006 통계·대시보드 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(39건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

작업자·검수자 대시보드와 영상/작업 통계를 제공하는 도메인. 월별·일별 작업량, 전체·권한별·상태별 집계, 일일 진행률을 다룬다.

[★주수치는 검수완료 기준] 산출물 목표(이미지 10만장·영상 5,000건)는 확정된 학습데이터 기준이므로 검수완료 수치를 주수치로 쓰고 전체(진행중 포함) 수치를 병기한다. 둘을 섞어 하나로 보이면 달성률이 부풀려진다.

[★집계는 BE 에서 전체 기준으로] 필터·집계를 FE 클라이언트 필터로 대체하지 않는다 — 현재 페이지 단위 집계는 오답이다. 목록 정렬은 시간축 단일 기준이고 '지금 처리할 것'은 필터·KPI 카드로 표현한다(ADR-038).

[상태 축] 배정·작업중·완료·반려 기준으로 집계한다. 구 상태 '확인요청'은 관리자 역할 통합(ADR-003)으로 폐기됐다.

[포함 범위] 증강·해상도 파생영상도 집계에 포함된다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 진행률 | 영상/작업 단위 완료 비율 |
| 상태별 통계 | 배정/작업중/완료/반려 건수 집계 |
| 검수완료 기준 수치 | 학습데이터로 확정된 분만 센 주수치. 전체 수치와 함께 병기한다 |
| 일별 작업량 | 일자별 처리 건수 추이 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-003]] · [[ADR-019]] · [[ADR-038]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | API 경계 계약 | [[API-001]] · [[API-042]] · [[API-055]] · [[API-056]] · [[API-057]] · [[API-058]] · [[API-072]] |
| 4 | DFEAT 비즈니스 로직 | [[DFEAT-026]] · [[DFEAT-027]] · [[DFEAT-028]] |
| 5 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 6 | SCREEN 화면 | [[SCREEN-011]] · [[SCREEN-020]] · [[SCREEN-021]] |
| 7 | CDIAG 클래스 구조 | [[CDIAG-009]] |
| 8 | SD 고충실 시안 | [[SD-014]] · [[SD-030]] · [[SD-031]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값, ERD 데이터 계층, EVT 이벤트 계약, SEQ 흐름 배선, UC 검증, AC 수용, TEST 통합시험, C4 컴포넌트, INT 외부 연동, FEAT 상위 기능 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 17 |
| implemented | 12 |
| (미기재) | 9 |
| in_progress | 1 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-001]] | api_endpoint | implemented | 100 |
| [[API-042]] | api_endpoint | implemented | 100 |
| [[API-055]] | api_endpoint | implemented | 100 |
| [[API-057]] | api_endpoint | implemented | 100 |
| [[API-058]] | api_endpoint | implemented | 100 |
| [[API-072]] | api_endpoint | implemented | 100 |
| [[DFEAT-026]] | domain_feature | implemented | 100 |
| [[DFEAT-027]] | domain_feature | implemented | 100 |
| [[DFEAT-028]] | domain_feature | implemented | 100 |
| [[SCREEN-011]] | screen_spec | implemented | 100 |
| [[SCREEN-020]] | screen_spec | implemented | 100 |
| [[SCREEN-021]] | screen_spec | implemented | 100 |
| [[API-056]] | api_endpoint | in_progress | 77 |
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

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (4)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-019]] — 오토라벨 프리셋↔검출라벨 매칭축을 마스터 라벨명에서 COCO 검출클래스(DTCT_TYPE_CD)로 일원화
- [[ADR-038]] — 목록 화면(작업목록/검수목록) 정렬·필터 정책 — 시간축 단일 정렬 + 엔드포인트별 차등 응답

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

### api_endpoint (7)
- [[API-001]] — GET /v1/users
- [[API-042]] — GET /v1/videos
- [[API-055]] — GET /v1/stats/summary
- [[API-056]] — GET /v1/stats/worker
- [[API-057]] — GET /v1/stats/overall
- [[API-058]] — GET /v1/stats/report
- [[API-072]] — GET /v1/assignments

### domain_feature (3)
- [[DFEAT-026]] — 작업자/검수자 대시보드 (월별·일별 통계)
- [[DFEAT-027]] — 검수자 대시보드
- [[DFEAT-028]] — 작업 통계 (전체·권한별·상태별·일일 진행률)

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (3)
- [[SCREEN-011]] — 대시보드 화면
- [[SCREEN-020]] — 작업자 통계 화면
- [[SCREEN-021]] — 전체 구축 현황 화면

### class_diagram (1)
- [[CDIAG-009]] — 통계·대시보드 도메인 모델 (개념 모델 — ERD 없음)

### screen_design (3)
- [[SD-014]] — SCREEN-011 대시보드 화면
- [[SD-030]] — SCREEN-020 작업자 통계 화면
- [[SD-031]] — SCREEN-021 전체 구축 현황 화면
