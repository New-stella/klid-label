# DOMAIN-007 데이터 증강 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(77건) · 버전은 `version-master.md`.
> ⚠ **아래 「빌드 순서」·「구현 현황」·「ITEM 인덱스」 세 표는 2026-08-18 스코프로 얼어 있다.** 키트 다운로더는 이 파일을 재생성하지 않아 SYNC 를 돌려도 따라오지 않는다 — 그 뒤 pin 에 편입된 ITEM(`ROLE-004`·`ADR-055` 등)이 표에 없고, 2026-08-31 재번호로 폐기된 3자리 AC 링크가 남아 있다. **있는 항목은 참이나 전수는 아니므로, 스코프 판정은 `.kit-scope.json` 으로 한다.** 본문 산문은 2026-09-01 에 폐기 결정 기준으로 정정했다.

## 도메인 (bounded context)

원본(비식별) 영상에서 파생영상을 만들어 학습데이터를 늘리는 도메인.

[증강 2계열]
· 외부 생성형 AI 위탁 3종 — WINTER / NIGHT / RAIN. 이미지-to-이미지라 영상(비디오)을 재생성하지 않고 부모의 비식별 영상 파일을 복사한 뒤 프레임 이미지만 변환한다. 해상도가 같으므로 라벨 좌표는 그대로 복사한다.
· 해상도 변경 3종 내부 파생 — RESL_1080P / RESL_720P / RESL_480P. 외부 위탁이 전혀 없는 내부 ffmpeg 리스케일이며 라벨 좌표를 배율(scaleX/scaleY)로 재계산해 적재한다. 업스케일도 허용한다.

[저장모델 통합] 해상도 파생은 전용 테이블을 두지 않고 증강과 같은 LS_DATA_AUG + LS_DATA_AUG_LBL_MAP 에 적재한다. 판별자는 AUG_TYPE_CD 값 RESL_* 이고, 중복 방지는 부분 유니크 UK_LS_DATA_AUG_RESL 이다. 구 전용 테이블(LS_RESOLUTION_EXPORT·LS_RESOLUTION_LBL_MAP)은 V126 에서 백필 후 폐기됐다.

[★파생은 새 영상이다] 증강·해상도 모두 새 RAW_SN 을 만들고 ORGNL_RAW_SN 으로 부모를 참조한다. 파생 깊이는 1 로 고정되어 파생본에서는 어떤 파생도 만들 수 없다(ADR-023). 파생에는 '원본영상'이 없고 비식별본만 있으며, 유일한 소비자는 관제서버다.

[★두 개의 상태축을 합치지 말 것] LS_DATA_AUG.AUG_PROC_STTS_CD 는 생성 결과 전용(웹훅 소유: ACCEPTED/REJECTED/CANCELED)이고, REVIEWER 의 사용·폐기 결정은 LS_DATA_AUG_RVW.RVW_STTS_CD 가 단독으로 소유한다. 작업목록 등재 게이트의 판정축도 리뷰 행이다(생성 성공만으로 통과시키면 기능이 무의미해진다). 해상도 파생은 검수 대상이 아니라 게이트 통과 예외를 명시적으로 둔다.

[★중복 요청 허용] 같은 (영상 × 종류)를 몇 번이든 다시 요청할 수 있다(ADR-044) — 생성 결과가 매번 달라 동일 조건 재요청이 정당한 운영 동선이기 때문이다. BE 차단·속도제한을 두지 않으며 연타 방어는 FE 책임이다. 결과물 구분축은 PROMPT_CN(요청 시 생성조건 5필드 원문)이다.

[미사용 파생 폐기] 반려된 파생은 작업 대상에서 빠지고 유예 7일 후 배치가 DB 행과 파일까지 실삭제한다(ADR-045). 삭제 대상은 파생·반려·유예경과 3조건 동시 충족만이며 조건을 최종 DELETE SQL 에 리터럴로 박는다.

[범위 외] 데이터마트 구축·검색·다운로드는 저작도구 책임이 아니다(ADR-020). 학습데이터셋 export 산출은 저작도구 범위 안이지만 이 도메인이 아니라 검수 승인 경로가 담당한다(ADR-020 이 내보내기를 범위 외로 두던 ADR-005 를 대체한다). 생성형 AI 모델 본체도 외부 시스템 책임이다(ADR-004).

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 증강(파생영상) | 원본의 비식별 영상을 복사하고 프레임 이미지만 변환해 만든 새 영상. 새 RAW_SN 을 가지며 ORGNL_RAW_SN 으로 부모를 참조한다 |
| 외부 증강 3종 | WINTER/NIGHT/RAIN — 생성형 AI 에 위탁하는 이미지-to-이미지 변환 |
| 해상도 파생 3종 | RESL_1080P/720P/480P — 외부 위탁 없는 내부 ffmpeg 리스케일. 라벨 좌표를 배율로 재계산한다 |
| 생성 조건(prompt) | REVIEWER 가 입력하는 time/season/weather/terrain/severity 5필드. 가공 없이 외부로 전송되고 PROMPT_CN 에 원문 보관된다 |
| 생성 결과축 | AUG_PROC_STTS_CD — 웹훅이 소유. 생성 성공/실패/취소만 나타낸다 |
| 검수 결정축 | LS_DATA_AUG_RVW.RVW_STTS_CD — REVIEWER 의 사용·폐기 결정. 등재 게이트의 판정축 |
| 파생 깊이 1 고정 | 파생본은 증강 요청 대상이 될 수 없다. 모든 파생의 부모는 항상 원본이다 |

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-004]] · [[ADR-018]] · [[ADR-020]] · [[ADR-022]] · [[ADR-023]] · [[ADR-031]] · [[ADR-044]] · [[ADR-045]] · [[ADR-055]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-011]] |
| 4 | EVT 이벤트 계약 | [[EVT-011]] |
| 5 | API 경계 계약 | [[API-042]] · [[API-059]] · [[API-060]] · [[API-061]] · [[API-062]] · [[API-063]] · [[API-092]] · [[API-165]] · [[API-175]] · [[API-179]] · [[API-188]] · [[API-189]] · [[API-190]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-029]] · [[DFEAT-030]] |
| 7 | SEQ 흐름 배선 | [[SEQ-002]] · [[SEQ-003]] · [[SEQ-004]] · [[SEQ-011]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-022]] · [[SCREEN-023]] |
| 10 | UC 검증 | [[UC-001]] · [[UC-002]] · [[UC-003]] · [[UC-010]] |
| 11 | AC 수용 | [[AC-001]] · [[AC-002]] · [[AC-003]] · [[AC-018]] · [[AC-021]] |
| 12 | TEST 통합시험 | [[TEST-003]] |
| 13 | CDIAG 클래스 구조 | [[CDIAG-010]] |
| 14 | C4 컴포넌트 | [[CMP-007]] |
| 15 | INT 외부 연동 | [[INT-006]] · [[INT-008]] |
| 16 | FEAT 상위 기능 | [[FEAT-001]] · [[FEAT-004]] |
| 17 | SD 고충실 시안 | [[SD-028]] · [[SD-029]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| planned | 28 |
| implemented | 26 |
| (미기재) | 16 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[API-042]] | api_endpoint | implemented | 100 |
| [[API-059]] | api_endpoint | implemented | 100 |
| [[API-060]] | api_endpoint | implemented | 100 |
| [[API-061]] | api_endpoint | implemented | 100 |
| [[API-062]] | api_endpoint | implemented | 100 |
| [[API-063]] | api_endpoint | implemented | 100 |
| [[API-092]] | api_endpoint | implemented | 100 |
| [[API-165]] | api_endpoint | implemented | 100 |
| [[API-175]] | api_endpoint | implemented | 100 |
| [[API-179]] | api_endpoint | implemented | 100 |
| [[DFEAT-029]] | domain_feature | implemented | 100 |
| [[DFEAT-030]] | domain_feature | implemented | 100 |
| [[EVT-011]] | domain_event | implemented | 100 |
| [[EXTSYS-004]] | external_system | implemented | 100 |
| [[FEAT-001]] | feature | implemented | 100 |
| [[FEAT-004]] | feature | implemented | 100 |
| [[INT-006]] | integration_point | implemented | 100 |
| [[INT-008]] | integration_point | implemented | 100 |
| [[SCREEN-022]] | screen_spec | implemented | 100 |
| [[SCREEN-023]] | screen_spec | implemented | 100 |
| [[SEQ-002]] | diagram_sequence | implemented | 0 |
| [[SEQ-003]] | diagram_sequence | implemented | 0 |
| [[SEQ-004]] | diagram_sequence | implemented | 100 |
| [[SEQ-011]] | diagram_sequence | implemented | 0 |
| [[UC-003]] | use_case | implemented | 100 |
| [[UC-010]] | use_case | implemented | 100 |
| [[AC-001]] | acceptance | planned | 0 |
| [[AC-002]] | acceptance | planned | 0 |
| [[AC-003]] | acceptance | planned | 0 |
| [[AC-018]] | acceptance | planned | 0 |
| [[AC-021]] | acceptance | planned | 0 |
| [[API-188]] | api_endpoint | planned | 0 |
| [[API-189]] | api_endpoint | planned | 0 |
| [[API-190]] | api_endpoint | planned | 0 |
| [[ERD-011]] | erd | planned | 0 |
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
| [[UC-001]] | use_case | planned | 0 |
| [[UC-002]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (10)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-055]] — 역할 4종 + 계층(ROLE_ADMIN > ROLE_REVIEWER) — ADR-003·ADR-043 supersede
- [[ADR-004]] — 생성형 AI 본체 외부화 — 저작도구는 증강 결과 검수만
- [[ADR-018]] — 해상도 변경(SFR-06-03)을 증강 파생영상 모델(LS_DATA_AUG/RESL_*)로 통합
- [[ADR-020]] — 검수 승인 학습데이터 export(NIA JSON) 산출을 저작도구 범위로 포함 (ADR-005 supersede)
- [[ADR-022]] — 비식별 신고 게이트 아키텍처 — 판정범위 자기행 단일원천·엔드포인트별 응답코드·해소시 복구범위
- [[ADR-023]] — 파생영상(증강·해상도) 데이터 모델 — 원본영상 없음·자기 비식별사본만·신고체계 바깥
- [[ADR-031]] — VLM·증강 콜백 무서명 규격 전환 + 3계층 방어(IP allowlist·rate limit·request_id)
- [[ADR-044]] — 증강 중복 요청 허용 — '요청 1회 = 파생영상 1건' dedup 계약 철회 (ADR-028 supersede)
- [[ADR-045]] — 증강 '생성 결과' 축과 '검수 결정' 축 분리 + 미사용 파생 폐기(소프트삭제 → 유예 후 실삭제)

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

### erd (1)
- [[ERD-011]] — 데이터 증강 ERD (고도화, PostgreSQL)

### domain_event (1)
- [[EVT-011]] — AugmentRequestedItem

### api_endpoint (13)
- [[API-042]] — GET /v1/videos
- [[API-059]] — GET /v1/augments
- [[API-060]] — POST /v1/augments/request
- [[API-061]] — GET /v1/augments/{jobId}/result
- [[API-062]] — POST /v1/augments/{id}/accept
- [[API-063]] — POST /v1/augments/{id}/reject
- [[API-092]] — POST /v1/videos/{rawSn}/resolution
- [[API-165]] — POST /v1/genai/callback
- [[API-175]] — GET /v1/frames/{srcSn}/deid-image
- [[API-179]] — GET /v1/videos/{rawSn}/resolution
- [[API-188]] — GET /v1/augments/{id}/progress
- [[API-189]] — POST /v1/augments/{id}/cancel
- [[API-190]] — POST /v1/augments/{id}/restore

### domain_feature (2)
- [[DFEAT-029]] — 생성형 AI 외부 증강(WINTER/NIGHT/RAIN) + 해상도 변경 내부 파생(증강 저장모델 통합)
- [[DFEAT-030]] — 증강 상태 흐름 — 생성 결과와 검수자 활용 결정

### diagram_sequence (4)
- [[SEQ-002]] — 증강 영상 생성 요청 — REVIEWER 요청부터 외부 SFR-07 위임까지
- [[SEQ-003]] — 증강 결과 수신·등록 — 외부 콜백부터 새 영상 생성까지
- [[SEQ-004]] — 해상도 변경 — 표준 3종 파생영상 생성·라벨 좌표 재계산
- [[SEQ-011]] — 증강 영상 활용 여부 검수 — 결과 조회부터 사용/폐기 결정·복구까지

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (2)
- [[SCREEN-022]] — 증강 요청 화면
- [[SCREEN-023]] — 증강 결과 화면

### use_case (4)
- [[UC-001]] — 증강 영상 생성 요청
- [[UC-002]] — 증강 결과 수신·등록
- [[UC-003]] — 해상도 변경 수행
- [[UC-010]] — 증강 영상 활용 여부 검수

### acceptance (5)
- [[AC-001]] — 증강 영상 생성 요청 수용
- [[AC-002]] — 증강 결과 수신·새 영상 등록
- [[AC-003]] — 해상도 변경 파생영상 생성·좌표 재계산
- [[AC-018]] — 다양한 환경 증강 영상 확보
- [[AC-021]] — 생성된 영상 라벨링으로 학습데이터셋 편입

### test_scenario (1)
- [[TEST-003]] — 외부 생성형 AI 증강 위탁·콜백 수신·활용 검수 정상 흐름

### class_diagram (1)
- [[CDIAG-010]] — 데이터 증강 도메인 모델

### diagram_c4_component (1)
- [[CMP-007]] — 데이터 증강·영상 해상도 컴포넌트 (새 영상·콜백·리사이즈)

### integration_point (2)
- [[INT-006]] — 증강 결과 콜백 수신
- [[INT-008]] — 증강 생성 위탁 요청

### feature (2)
- [[FEAT-001]] — AI 보조 라벨링 (객체 추적·분할(VOS)·외곽 경계 밀착)
- [[FEAT-004]] — 영상 증강 연동·검수 + 해상도 변경

### screen_design (2)
- [[SD-028]] — SCREEN-022 증강 요청 화면
- [[SD-029]] — SCREEN-023 증강 결과 화면
