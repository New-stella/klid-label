# DOMAIN-003 영상·프레임 수집 — 구현 진입점

> 이 문서는 결정적 생성물이다. ITEM 본문은 각 `[[ID]]` 파일이 진실원이며 여기 옮겨 적지 않는다.
> 스코프 정본은 `.kit-scope.json`(119건) · 버전은 `version-master.md`.

## 도메인 (bounded context)

관제 영상을 저작도구로 들여오고 프레임을 추출해 라벨링 원천을 만드는 도메인.

[★적재 주체 반전 (ADR-042)] 저작도구가 관제 공유 테이블을 스캔하던 구조를 폐기한다. 관제서버가 학습용으로 설정한 영상을 LS_DATA_INGEST 에 직접 INSERT 하고, 저작도구 폴링 배치가 미처리 행을 원자 클레임해 LS_DATA_RAW 로 적재한다. 영상 관련 정보는 전부 이 인입 테이블에서 평면으로 받으며 관제 공유 마스터 조인은 하지 않는다. 이름값(CCTV명·지자체명·이벤트명·파일포맷)은 인입 행을 LEFT JOIN 해 얻으며, 파생영상은 자기 인입 행이 없으므로 ORGNL_RAW_SN 1단계 폴백으로 부모 행을 참조한다(파생 깊이가 1 로 고정돼 재귀가 필요 없다).

[파이프라인 순서] 적재(PENDING) → ★비식별화(선두, 전체 영상 자동) → MARKING_READY → 마킹(비식별 영상 대상) → VLM 시계열 → 프레임 추출 → YOLO → SAM2 → 트랙 보간.

[프레임 추출] FFmpeg 기반 배치(1건/분)이며 ★마킹 위치를 기준으로 원본·비식별 2벌을 추출한다(frames/raw|deid/{rawSn} 로 분기 저장돼 원본 덮어쓰기가 없다). 오토라벨링은 원본에만 실행하고 동일 해상도이므로 비식별본과 좌표를 공유한다.

[라벨링 캔버스 서빙] 프레임 이미지는 기본이 비식별본이며 원본은 REVIEWER 가 명시적으로 요청할 때만 나간다. 비식별 프레임을 여는 4경로는 동일 판정기로 검증하고 그 실경로를 NOFOLLOW 로 열어야 한다(심링크 교체로 마스킹 전 픽셀이 새는 것을 막는다).

[범위 밖] 포털 사용자 본인 자산 업로드는 LS_PORTAL_* 전용 경로로 본 도메인·데이터마트와 완전 분리된다. 구 관리화면 TUS 자체 업로드는 1차 적재 경로가 아니며 폐지 예정이다.

## Ubiquitous Language

| 용어 | 뜻 |
|---|---|
| 인입(LS_DATA_INGEST) | 관제가 직접 INSERT 하는 평면 수신 테이블. 영상 관련 정보의 단일 창구이며 행을 삭제하는 코드가 없어 영구 보존된다 |
| 원천(원시)데이터 | LS_DATA_RAW — 프레임 추출·라벨링의 원천이 되는 영상 단위. 작업 식별자 RAW_SN 이 곳 PK 다 |
| 프레임 | FFmpeg 으로 추출된 개별 이미지. 원본·비식별 2벌이 각기 다른 경로에 저장된다 |
| 파생영상 | 증강·해상도 변환으로 만들어진 새 영상. ORGNL_RAW_SN 으로 부모를 참조하며 깊이는 1 로 고정된다. 파생에는 원본영상이 없고 비식별본만 있다 |
| 폴링 적재 | 인입 테이블의 미처리 행을 주기적으로 원자 클레임해 LS_DATA_RAW 로 옮기는 배치. 2노드 동시 적재를 조건부 UPDATE 로 막는다 |
| 자동 분류 | 이벤트 유형·위치 등 인입 메타 기반 분류. 촬영환경(날씨·시간대·계절)은 자동 파생이 아니라 수동 입력이다 |

## ⚠ 이번 동기화 변경 알림 (2026-08-19) — 코드 재반영 필요

**신규 설계 4건 — 코드 반영 완료 (2026-08-20 · 커밋 `23e52e57`).** 추적 기록 `IMPREC-026`~`030`.
⚠ `ADR-049` 는 구현 추적 대상 타입이 아니라 기록이 없다(정상) — 그 결정의 반영 상세는 `API-201`·`API-212`~`214` 기록에 있다.

| ITEM | 내용 |
|---|---|
| [[ADR-049]] | 외부 시계열 위탁의 비활성 토글을 폐지하고 사람이 결정하는 단계 스킵으로 대체한다 |
| [[API-212]] | 일괄 스킵 — `POST /v1/videos/batch/stages/{stage}/skip` |
| [[API-213]] | 일괄 해제 — `DELETE /v1/videos/batch/stages/{stage}/skip` |
| [[API-214]] | 일괄 재수행 — `POST /v1/videos/batch/stages/{stage}/rerun` |

일괄 3형제의 `stage` 는 **시계열 묶음만** 받는다(오토라벨은 단건 경로에만 남는다).
계약은 기존 일괄 재처리와 같다 — 상한 100 · 중복 1건 취급 · 순서 보존 · 건별 사유 부분 성공.

**계약이 바뀐 것 1건**

| ITEM | 무엇이 바뀌었나 |
|---|---|
| [[API-201]] | 승인 이력 영상의 재수행 거부를 **오토라벨 묶음 한정**으로 좁히고, **시계열 묶음은 승인 이력이 있어도 받는다**는 예외를 명시. 들어온 서술은 승인 완료 영상의 서술 갱신 시 재검수를 강제하는 규칙이 받는다 |

⚠ **구현 시 주의 — 「먼저 스킵」이 운영 지침이다.** 토글이 폐지되어 미연동이면 위탁이 실패하는데,
마킹 상태 전이는 위탁 대기 상태에서만 일어나므로 실패로 종결되면 이후 재수행해도 마킹 표시가
실패로 남는다(시계열 적재·검수 진입·산출물 재생성은 정상 동작한다). 벤더 미연동 구간에는
반드시 먼저 스킵해야 한다.

### 구현 중 확정 — ✅ 설계 반영 완료 (2026-08-20)

구현하면서 설계와 어긋나거나 설계에 없던 것이 드러났다. **코드가 정답인 쪽**과 **설계가 정답인 쪽**을 나눠 적는다.

| 대상 | 무엇이 어긋났나 | 판정 |
|---|---|---|
| [[API-198]] | 「이미 스킵 상태」 409 응답 블록 | **설계가 낡음** — 실제 단건 스킵은 append-only 라 항상 성공한다(사유가 바뀔 수 있고 그 변경 이력 자체가 감사 대상). 실제 컨트롤러 응답 정의에도 그 409 가 애초에 없다 |
| [[API-200]] | 「해제할 스킵 없음」 404 응답 블록 | **설계가 낡음** — 실제 단건 해제는 스킵 상태가 아니면 멱등 no-op 으로 끝난다(두 번 눌렀다고 의미 없는 이력이 쌓이지 않게) |
| [[API-212]] · [[API-213]] | 예시(example)의 실패 사유가 위 409/404 를 전제 | **설계가 낡음** — 본문 규범(「단건과 판정·기록 규칙이 같다」)을 따랐다. 예시대로 만들면 같은 조건에서 단건과 일괄이 갈리는 비대칭이 새로 생긴다 |
| [[API-212]] | 사유 검증 위치 | **설계에 없음** — 사유는 요청당 하나라 건별로 갈릴 수 없으므로 **요청 단위 400** 으로 확정했다(건별 실패 아님) |
| [[API-201]] · [[API-214]] | 승인 영상 재수행 시의 상태 취급 | **설계에 없음** — 면제는 「차단하지 않는다」이지 「전이한다」가 아니다. **작업 상태를 전이하지 않아** 승인 상태가 보존되고(데이터마트 뷰 이탈 방지), 대신 **배치 단계는 반드시 마감**해야 한다(안 그러면 완주해도 처리중에 영구 고착된다). |
| [[API-201]] | 면제 선언 형태 | **설계에 없음** — 허용목록으로 선언한다. 「오토라벨이 아니면 면제」 형태는 새 묶음이 자동으로 면제받는 열린 실패다 |

✅ **위 표는 전부 ITEM 본문에 반영됐다** — `API-198`(v5) · `API-200`(v3) · `API-201`(v5) · `API-212`(v2) · `API-213`(v2) · `API-060`(v14).
전파로 함께 고친 것: **`SCREEN-022`(v40)** 증강 요청 거부 목록에 미연동 거부 추가 + 순서 정정 · **`SEQ-002`(v7)** 흐름에 미연동 단계 삽입 + 뒤 번호 밀기.
본문 영향이 없어 확인만 기록한 것: `SCREEN-009`(v47) · `DFEAT-029`(v12) · `TEST-003`(v15). 잔여 stale 0.

⚠ **`analyze_impact` 는 이 전파를 0건으로 보고했다** — 실제 역참조는 `get_neighbors` 로만 드러났고 서버의 stale 전파는 정상 동작했다. 이 도구의 0건을 「영향 없음」으로 읽지 말 것.

### ✅ 화면 축 결정 완료 (2026-08-20 사용자 확정)

| 결정 | 반영 |
|---|---|
| **일괄 3 API 는 영상 처리 현황 화면에 붙인다** | `SCREEN-008`(v36) — 이미 REVIEWER 전용 행 선택·일괄 배정·일괄 재시작이 있는 액션바에 시계열 일괄 건너뛰기·되돌리기·재수행을 더했다. 선택 모델·부분 성공 표시·상한 안내를 그대로 재사용한다. **오토라벨은 이 바에 두지 않는다**(대량으로 건너뛸 수 있게 열면 품질 축이 느슨해진다 — 오토라벨은 상세에서 건건이). |
| **승인 영상의 재수행은 묶음별로 갈라 보인다** | `SCREEN-009`(v48) — 시계열은 그대로 누르고 오토라벨은 **비활성 + 사유 툴팁**. 되돌릴 수 없는 영구 조건이라 파생영상 차단과 같은 방식으로 미리 알린다(사유를 다 적고 누른 뒤에야 거부되는 동선을 만들지 않는다). |

⇒ `API-212`·`API-213`·`API-214` 의 역참조 공백이 닫혔다(전부 `SCREEN-008`).

그 밖 변경: [[ADR-042]] [[EVT-005]] [[ERD-012]] [[NFR-018]] [[SCREEN-005]] [[SCREEN-006]]
[[SCREEN-009]] [[SCREEN-022]] [[SCREEN-038]] [[UC-018]] — 이번 작업 축과는 별개다.

## 빌드 순서 (제약 → 데이터 → 계약 → 로직 → 화면 → 검증)

| # | 단계 | 이번 키트 ITEM |
|---|---|---|
| 1 | ADR 결정·제약 | [[ADR-001]] · [[ADR-003]] · [[ADR-004]] · [[ADR-006]] · [[ADR-010]] · [[ADR-018]] · [[ADR-032]] · [[ADR-042]] · [[ADR-049]] · [[ADR-050]] |
| 2 | NFR 예산 | [[NFR-008]] · [[NFR-009]] · [[NFR-010]] · [[NFR-011]] · [[NFR-012]] · [[NFR-013]] · [[NFR-014]] · [[NFR-015]] · [[NFR-016]] · [[NFR-017]] · [[NFR-018]] · [[NFR-019]] · [[NFR-020]] · [[NFR-021]] |
| 3 | ERD 데이터 계층 | [[ERD-012]] · [[ERD-020]] · [[ERD-025]] |
| 4 | EVT 이벤트 계약 | [[EVT-002]] · [[EVT-005]] |
| 5 | API 경계 계약 | [[API-021]] · [[API-042]] · [[API-043]] · [[API-044]] · [[API-045]] · [[API-046]] · [[API-047]] · [[API-068]] · [[API-070]] · [[API-071]] · [[API-084]] · [[API-092]] · [[API-114]] · [[API-143]] · [[API-144]] · [[API-145]] · [[API-146]] · [[API-148]] · [[API-150]] · [[API-156]] · [[API-158]] · [[API-160]] · [[API-162]] · [[API-164]] · [[API-167]] · [[API-168]] · [[API-170]] · [[API-172]] · [[API-173]] · [[API-174]] · [[API-181]] · [[API-185]] · [[API-186]] · [[API-191]] · [[API-192]] · [[API-198]] · [[API-199]] · [[API-200]] · [[API-201]] · [[API-212]] · [[API-213]] · [[API-214]] |
| 6 | DFEAT 비즈니스 로직 | [[DFEAT-007]] · [[DFEAT-008]] · [[DFEAT-009]] · [[DFEAT-010]] · [[DFEAT-011]] · [[DFEAT-029]] · [[DFEAT-045]] · [[DFEAT-051]] |
| 7 | SEQ 흐름 배선 | [[SEQ-001]] · [[SEQ-004]] |
| 8 | ROLE 인가 | [[ROLE-001]] · [[ROLE-002]] · [[ROLE-003]] |
| 9 | SCREEN 화면 | [[SCREEN-005]] · [[SCREEN-006]] · [[SCREEN-008]] · [[SCREEN-009]] · [[SCREEN-011]] · [[SCREEN-022]] · [[SCREEN-025]] · [[SCREEN-027]] · [[SCREEN-038]] |
| 10 | UC 검증 | [[UC-011]] · [[UC-016]] · [[UC-018]] |
| 11 | AC 수용 | [[AC-025]] · [[AC-026]] · [[AC-049]] · [[AC-050]] · [[AC-051]] · [[AC-055]] |
| 12 | TEST 통합시험 | [[TEST-001]] |
| 13 | CDIAG 클래스 구조 | [[CDIAG-001]] |
| 14 | C4 컴포넌트 | [[CMP-001]] · [[CMP-010]] |
| 15 | FEAT 상위 기능 | [[FEAT-004]] |
| 16 | SD 고충실 시안 | [[SD-004]] · [[SD-013]] · [[SD-023]] |

> ⚠ 이번 키트에 **0건**인 단계: CONST 상수값, INT 외부 연동 — 해당 축은 설계가 없거나 `domain_id` 미설정이다.

## 상수 값 (매직넘버 단일 진실원 — 인라인 추정 금지)

> 이번 키트에 CONST 가 없다.

## 구현 현황 (ITEM 의 implementation 필드 — 설계 쪽 주장)

| status | 건수 |
|---|---|
| implemented | 64 |
| planned | 30 |
| (미기재) | 25 |

| ITEM | type | status | progress |
|---|---|---|---|
| [[AC-049]] | acceptance | implemented | 90 |
| [[AC-050]] | acceptance | implemented | 90 |
| [[AC-051]] | acceptance | implemented | 90 |
| [[AC-055]] | acceptance | implemented | 90 |
| [[API-021]] | api_endpoint | implemented | 100 |
| [[API-042]] | api_endpoint | implemented | 100 |
| [[API-043]] | api_endpoint | implemented | 100 |
| [[API-044]] | api_endpoint | implemented | 100 |
| [[API-045]] | api_endpoint | implemented | 100 |
| [[API-046]] | api_endpoint | implemented | 100 |
| [[API-047]] | api_endpoint | implemented | 100 |
| [[API-068]] | api_endpoint | implemented | 100 |
| [[API-070]] | api_endpoint | implemented | 100 |
| [[API-071]] | api_endpoint | implemented | 100 |
| [[API-084]] | api_endpoint | implemented | 100 |
| [[API-092]] | api_endpoint | implemented | 100 |
| [[API-114]] | api_endpoint | implemented | 100 |
| [[API-143]] | api_endpoint | implemented | 100 |
| [[API-144]] | api_endpoint | implemented | 100 |
| [[API-145]] | api_endpoint | implemented | 100 |
| [[API-146]] | api_endpoint | implemented | 100 |
| [[API-148]] | api_endpoint | implemented | 100 |
| [[API-150]] | api_endpoint | implemented | 100 |
| [[API-156]] | api_endpoint | implemented | 100 |
| [[API-158]] | api_endpoint | implemented | 100 |
| [[API-160]] | api_endpoint | implemented | 100 |
| [[API-162]] | api_endpoint | implemented | 100 |
| [[API-164]] | api_endpoint | implemented | 100 |
| [[API-167]] | api_endpoint | implemented | 100 |
| [[API-168]] | api_endpoint | implemented | 100 |
| [[API-170]] | api_endpoint | implemented | 100 |
| [[API-172]] | api_endpoint | implemented | 100 |
| [[API-173]] | api_endpoint | implemented | 100 |
| [[API-174]] | api_endpoint | implemented | 100 |
| [[API-181]] | api_endpoint | implemented | 100 |
| [[API-185]] | api_endpoint | implemented | 100 |
| [[API-186]] | api_endpoint | implemented | 100 |
| [[API-198]] | api_endpoint | implemented | 100 |
| [[API-200]] | api_endpoint | implemented | 100 |
| [[API-201]] | api_endpoint | implemented | 100 |
| [[API-212]] | api_endpoint | implemented | 100 |
| [[API-213]] | api_endpoint | implemented | 100 |
| [[API-214]] | api_endpoint | implemented | 100 |
| [[DFEAT-007]] | domain_feature | implemented | 100 |
| [[DFEAT-008]] | domain_feature | implemented | 100 |
| [[DFEAT-009]] | domain_feature | implemented | 100 |
| [[DFEAT-010]] | domain_feature | implemented | 100 |
| [[DFEAT-029]] | domain_feature | implemented | 100 |
| [[DFEAT-045]] | domain_feature | implemented | 100 |
| [[DFEAT-051]] | domain_feature | implemented | 100 |
| [[ERD-012]] | erd | implemented | 100 |
| [[EVT-002]] | domain_event | implemented | 100 |
| [[EVT-005]] | domain_event | implemented | 100 |
| [[FEAT-004]] | feature | implemented | 100 |
| [[SCREEN-005]] | screen_spec | implemented | 100 |
| [[SCREEN-008]] | screen_spec | implemented | 100 |
| [[SCREEN-009]] | screen_spec | implemented | 100 |
| [[SCREEN-011]] | screen_spec | implemented | 100 |
| [[SCREEN-022]] | screen_spec | implemented | 100 |
| [[SCREEN-025]] | screen_spec | implemented | 100 |
| [[SCREEN-027]] | screen_spec | implemented | 100 |
| [[SCREEN-038]] | screen_spec | implemented | 100 |
| [[SEQ-004]] | diagram_sequence | implemented | 100 |
| [[UC-016]] | use_case | implemented | 100 |
| [[AC-025]] | acceptance | planned | 0 |
| [[AC-026]] | acceptance | planned | 0 |
| [[API-191]] | api_endpoint | planned | 0 |
| [[API-192]] | api_endpoint | planned | 0 |
| [[API-199]] | api_endpoint | planned | 0 |
| [[DFEAT-011]] | domain_feature | planned | 0 |
| [[ERD-020]] | erd | planned | 0 |
| [[ERD-025]] | erd | planned | 0 |
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
| [[SCREEN-006]] | screen_spec | planned | 0 |
| [[SEQ-001]] | diagram_sequence | planned | 0 |
| [[STATE-002]] | diagram_state | planned | 0 |
| [[UC-011]] | use_case | planned | 0 |
| [[UC-018]] | use_case | planned | 0 |

> ⚠ 이 표는 **설계가 스스로 적은 주장**이다. 코드와 대조되지 않았다 — 그 대조가 `/mc-logi-implement-review` 의 몫이다.

## ITEM 인덱스

### adr (10)
- [[ADR-001]] — 작업 단위를 프로젝트에서 영상 1건(RAW_SN)으로 전환
- [[ADR-003]] — ADMIN 역할 폐기 — 관리 권한 REVIEWER 통합
- [[ADR-004]] — 생성형 AI 본체 외부화 — 저작도구는 증강 결과 검수만
- [[ADR-006]] — 비식별 처리 외부 솔루션 연동 — 캔버스 수동 블러 폐기
- [[ADR-010]] — DBMS MariaDB에서 PostgreSQL로 전환
- [[ADR-018]] — 해상도 변경(SFR-06-03)을 증강 파생영상 모델(LS_DATA_AUG/RESL_*)로 통합
- [[ADR-032]] — 촬영환경·개인정보 메타 수동입력 신설(+self-fill 자동파생 폐기)
- [[ADR-042]] — 관제 데이터 참조 전면 제거 — MNG_* 9종 삭제 + LS_DATA_INGEST 평면 수신
- [[ADR-049]] — 외부 시계열 위탁의 비활성 토글을 폐지하고 사람이 결정하는 단계 스킵으로 대체한다
- [[ADR-050]] — 시계열 위탁 건너뛰기의 입구를 전체 설정과 실패 후 판단 둘로 한정한다

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

### erd (3)
- [[ERD-012]] — 영상·프레임 수집 ERD (고도화, PostgreSQL)
- [[ERD-020]] — 배치·작업 인프라 ERD (고도화, PostgreSQL)
- [[ERD-025]] — 이벤트유형 마스터 ERD (고도화, PostgreSQL)

### domain_event (2)
- [[EVT-002]] — BatchQueued
- [[EVT-005]] — VideoIngested

### api_endpoint (42)
- [[API-021]] — GET /v1/frames/{srcSn}/image
- [[API-042]] — GET /v1/videos
- [[API-043]] — GET /v1/videos/{rawSn}
- [[API-044]] — GET /v1/videos/{rawSn}/labels/auto
- [[API-045]] — GET /v1/videos/{rawSn}/auto-summary
- [[API-046]] — GET /v1/videos/{rawSn}/frames/{frameNo}/image
- [[API-047]] — POST /v1/videos/{rawSn}/markings
- [[API-068]] — GET /v1/manage/configs
- [[API-070]] — POST /v1/assignments
- [[API-071]] — PATCH /v1/assignments/{assignmentId}
- [[API-084]] — GET /v1/videos/{rawSn}/stream
- [[API-092]] — POST /v1/videos/{rawSn}/resolution
- [[API-114]] — GET /v1/videos/{rawSn}/stream-url
- [[API-143]] — POST /v1/dev/batch/scan
- [[API-144]] — POST /v1/dev/batch/trigger
- [[API-145]] — POST /v1/dev/batch/trigger/next
- [[API-146]] — GET /v1/dev/batch/pending
- [[API-148]] — GET /v1/dev/dataset-video-meta/shooting-env-correction-targets
- [[API-150]] — POST /v1/dev/dataset-video-meta/shooting-env-corrections
- [[API-156]] — OPTIONS /v1/uploads
- [[API-158]] — POST /v1/uploads
- [[API-160]] — HEAD /v1/uploads/{uploadId}
- [[API-162]] — PATCH /v1/uploads/{uploadId}
- [[API-164]] — DELETE /v1/uploads/{uploadId}
- [[API-167]] — POST /v1/videos/{rawSn}/batch/retry
- [[API-168]] — GET /v1/videos/{rawSn}/environment-meta
- [[API-170]] — PUT /v1/videos/{rawSn}/environment-meta
- [[API-172]] — GET /v1/frames/{srcSn}/privacy-meta
- [[API-173]] — PUT /v1/frames/{srcSn}/privacy-meta
- [[API-174]] — PUT /v1/frames/privacy-meta
- [[API-181]] — GET /v1/event-types
- [[API-185]] — GET /v1/manage/event-types
- [[API-186]] — PATCH /v1/manage/event-types/{evntTypeCd}
- [[API-191]] — POST /v1/control-ingests/{rcptnSn}/requeue
- [[API-192]] — POST /v1/control-ingests/requeue
- [[API-198]] — POST /v1/videos/{rawSn}/batch/stages/{stage}/skip
- [[API-199]] — POST /v1/videos/batch/retry
- [[API-200]] — DELETE /v1/videos/{rawSn}/batch/stages/{stage}/skip
- [[API-201]] — POST /v1/videos/{rawSn}/batch/stages/{stage}/rerun
- [[API-212]] — POST /v1/videos/batch/stages/{stage}/skip
- [[API-213]] — DELETE /v1/videos/batch/stages/{stage}/skip
- [[API-214]] — POST /v1/videos/batch/stages/{stage}/rerun

### domain_feature (8)
- [[DFEAT-007]] — 영상/이미지 관리
- [[DFEAT-008]] — 클립영상 수신·적재
- [[DFEAT-009]] — FFmpeg 프레임 자동 추출 (배치 1회/분)
- [[DFEAT-010]] — 이미지 전처리 (리사이징·밝기/대비 보정)
- [[DFEAT-011]] — 메타데이터 기반 자동 분류
- [[DFEAT-029]] — 생성형 AI 외부 증강(WINTER/NIGHT/RAIN) + 해상도 변경 내부 파생(증강 저장모델 통합)
- [[DFEAT-045]] — 시스템 설정 관리
- [[DFEAT-051]] — 촬영환경·개인정보 메타 수동입력

### diagram_sequence (2)
- [[SEQ-001]] — 영상수집 파이프라인 — 비식별·마킹부터 트랙 보간까지
- [[SEQ-004]] — 해상도 변경 — 표준 3종 파생영상 생성·라벨 좌표 재계산

### permission_role (3)
- [[ROLE-001]] — 검수자 (REVIEWER)
- [[ROLE-002]] — 라벨링 작업자 (WORKER)
- [[ROLE-003]] — 포털 회원 (PORTAL_USER)

### screen_spec (9)
- [[SCREEN-005]] — 라벨링 캔버스 화면
- [[SCREEN-006]] — 마킹 화면
- [[SCREEN-008]] — 영상 처리 현황 화면
- [[SCREEN-009]] — 영상 상세 화면
- [[SCREEN-011]] — 대시보드 화면
- [[SCREEN-022]] — 증강 요청 화면
- [[SCREEN-025]] — 시스템 설정 화면
- [[SCREEN-027]] — 수동 업로드
- [[SCREEN-038]] — 이벤트유형 관리 화면

### use_case (3)
- [[UC-011]] — 비식별 처리 요청
- [[UC-016]] — 비식별 처리 상태·이력 확인
- [[UC-018]] — 영상 적재 (관제 인입 테이블 직접 INSERT → 폴링 적재)

### acceptance (6)
- [[AC-025]] — 관제 인입 → 폴링 적재 정상 흐름 수용
- [[AC-026]] — 인입 중복 방어·파일 미도착 백오프
- [[AC-049]] — 시계열 일괄 건너뛰기 — 대상은 실패한 영상이고 사유는 요청 단위로 검증된다
- [[AC-050]] — 시계열 일괄 재수행은 부분 성공을 그대로 알린다
- [[AC-051]] — 검수가 완료된 영상은 재수행이 묶음별로 갈린다
- [[AC-055]] — 시계열 위탁 전체 건너뛰기 — 사유 없이 켤 수 없고 켜진 동안 외부 호출이 없다

### test_scenario (1)
- [[TEST-001]] — 관제 학습용 영상 적재 후 선두 비식별 처리(외부 위탁·폴링) 정상 흐름

### class_diagram (1)
- [[CDIAG-001]] — 영상·프레임 수집 도메인 모델

### diagram_c4_component (2)
- [[CMP-001]] — 배치 파이프라인 컴포넌트 (오케스트레이터·단계·러너)
- [[CMP-010]] — 영상 적재 컴포넌트 (관제 인입 테이블 폴링 적재)

### feature (1)
- [[FEAT-004]] — 영상 증강 연동·검수 + 해상도 변경

### screen_design (3)
- [[SD-004]] — SCREEN-009 영상 상세 화면
- [[SD-013]] — SCREEN-008 영상 처리 현황 화면
- [[SD-023]] — SCREEN-038 이벤트유형 관리 화면

### diagram_state (1)
- [[STATE-002]] — 영상 배치 단계 상태 전이 (LS_DATA_RAW.DATA_STTS_CD)

### legacy_artifact (7)
- [[LEGACY-003]] — [module] 저작도구 BATCH (PF-003)
- [[LEGACY-005]] — [module] 영상분할 (PF-005)
- [[LEGACY-021]] — [table] LS_DATA_RAW
- [[LEGACY-043]] — [api] 비디오 추출 요청 (VIDEO-PROC)
- [[LEGACY-044]] — [api] 비디오 추출 상태 응답 (VIDEO-PROC)
- [[LEGACY-046]] — [api] 오토라벨링 - 탐지 객체들 (AUTO-LABEL)
- [[LEGACY-119]] — [screen] SKKLID-UI-03-03-01 영상/이미지 관리 - 리스트
