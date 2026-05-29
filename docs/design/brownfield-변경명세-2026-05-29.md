# Brownfield 변경명세서 — KLID 학습데이터 저작도구 (2차)

> 출처: LogiCraft `generate_brownfield_report` (project: KLID-저작도구) + 본 작업 요약
> 생성일: 2026-05-29 · 작성: 박찬기 · K-DOC '데이터 변경 명세서' / 운영팀 인계 자료 입력용
>
> **현행화 메모(2026-05-29)**: 버전관리 방식이 **Gitea → DB 스냅샷**(라벨 전체 스냅샷 DB 저장, diff·rollback 앱 계산)으로, 사용 DB가 **MariaDB → PostgreSQL**로 확정됨. 아래 표의 일부 diff_summary는 이 결정에 맞게 정정함. LogiCraft 동기화는 1차 정합 후 추후 반영 예정이라 LogiCraft 원본과는 일시적으로 차이가 있을 수 있음.

## 0. 작업 요약

### 모델링 단계
| 단계 | 산출 |
|------|------|
| Pass 1 | 1차 baseline 도메인 10종 (preserved) |
| Step 1 | 레거시 카탈로그 129 (모듈6·역할6·테이블30·API17·화면70) |
| Step 2 | preserved DFEAT 38 (도메인별 1차 기능) |
| Step 3 | physical ERD 9 (LS_ 30테이블, 옵션D) |
| Step 4 | Pass 2 brownfield 태깅 + 마킹 신규(DFEAT-039) |
| 보완 | 1차 기능 이관 완결성 audit (연습장 누락 보완) |
| **2차 스코프 확정** | 아래 폐기/변경 반영 |

### 2차 스코프 축소(폐기) 결정 — 본 세션 확정
| 대상 | 결정 | 반영 |
|------|------|------|
| **게시판·공지** | 2차 미제공 | DOMAIN-009 + DFEAT-037/038 + ERD-006 `deprecated` |
| **내보내기(Export)·데이터마트** | 외부 제공 시스템 책임 | DFEAT-031 `deprecated` + DOMAIN-007 `modified`(증강만 잔존) |
| **프로젝트 관리** | 프로젝트 개념 폐기(작업 단위=영상 1건) | DOMAIN-002 `deprecated`, DFEAT-004/005 `deprecated`, 배정(006)→**검수**·영상관리(007)→**영상·프레임수집** 재배치 |
| **생성형 AI 연동** | 생성 본체 외부, 저작도구 미보유 | DOMAIN-008 + DFEAT-032~036 + ERD-005 `deprecated` |
| **검수 1·2차 구분** | 검수자 1인이 승인까지 반복 | DFEAT-021 `modified`(단일 반복 검수), DFEAT-022 `deprecated`(통합) |
| **캔버스 수동 블러** | 외부 영상비식별 솔루션 연동으로 대체 | DFEAT-013 `deprecated` (FEAT-005가 대체) |
| **연습장** | 2차 폐기 | DFEAT-040 `deprecated` |

### 역할 모델 (V1.3 확정)
- **작업자 = WORKER** (LEGACY-007), **검수자 = REVIEWER** (검수자1·2차·슈퍼관리자·프로젝트담당자 통합 → LEGACY-008/009/011/012 deprecated), **포털사용자 = PORTAL_USER** (업로더 LEGACY-010). DFEAT-002에 반영.

### 2차 활성 도메인 (7개)
사용자·권한 / 영상·프레임 수집(+영상관리·마킹) / AI 보조 라벨링 / 검수(+배정) / 통계·대시보드 / 데이터 증강 / 라벨링
→ 폐기 도메인 3개: 프로젝트 관리 · 생성형 AI 연동 · 게시판·공지

### 거버넌스 메모
- **정식 ADR 부재**: modified/deprecated 항목 `decided_by` 비어 있음 → 근거는 `diff_summary` 버전 표기(V1.3~V2.0). ADR 정형화 추후 보완.
- **table-level 미결정**: ERD-009(LS_PJT* 11테이블)는 프로젝트 폐기에도 배정(LS_PJT_USER_AUTHRT)·라벨(LS_PJT_LBL)·메타(LS_PJT_META) 등 데이터 존속 가능 → **DB 레벨 테이블 폐기/존속은 별도 결정 필요** (현재 preserved 유지).
- **소스 정정**: 1차 화면 수는 분석 MD "66" → 원본 PDF **70**이 정확(누락 5종: 사용자관리·게시판관리 4, 03-04-02 결번).
- **stale 0** 확인 (의존성 cascade는 단방향=no-op 재저장 / 도메인 collaborates_with=status-only로 정리).

---

# Brownfield Report — KLID-저작도구

- **Project**: `4ece2c3f-8e99-46f5-9580-71108a76e578`
- **Generated**: 2026-05-29T08:04Z
- **Items with brownfield**: 84

## 📊 Status Distribution by ITEM Type

| Type | preserved | modified | new | deprecated | Total |
|---|---|---|---|---|---|
| domain | 6 | 1 | 0 | 3 | 10 |
| domain_feature | 21 | 5 | 1 | 13 | 40 |
| erd | 7 | 0 | 0 | 2 | 9 |
| feature | 0 | 3 | 4 | 0 | 7 |
| requirement | 0 | 5 | 12 | 1 | 18 |

## 📦 1차 Legacy Repo 분포

| Legacy Repo | ITEM count |
|---|---|
| KLID-AI-PF-001 | 37 |
| KLID-AI-PF-005 | 4 |
| KLID-AI-PF-004 | 4 |
| KLID-AI-PF-002 | 1 |
| KLID-AI-PF-003 | 1 |

## 📋 Brownfield Mapping Table

| ITEM ID | Type | Title | Status | Legacy | change_kind | diff_summary |
|---|---|---|---|---|---|---|
| `DOMAIN-001` | domain | 사용자·권한 | preserved |  |  |  |
| `DOMAIN-002` | domain | 프로젝트 관리 | **deprecated** |  | scope-shrink | 프로젝트 개념 폐기(작업 단위=영상 1건, V1.8) — 생성/관리 폐기, 배정→검수·영상관리→수집 재배치 |
| `DOMAIN-003` | domain | 영상·프레임 수집 | preserved |  |  |  |
| `DOMAIN-004` | domain | AI 보조 라벨링 | preserved |  |  |  |
| `DOMAIN-005` | domain | 검수 | preserved |  |  |  |
| `DOMAIN-006` | domain | 통계·대시보드 | preserved |  |  |  |
| `DOMAIN-007` | domain | 데이터 증강·내보내기 | **modified** |  | scope-shrink | 내보내기·데이터마트 폐기(외부 책임, V1.4/V1.9), 데이터 증강만 잔존 |
| `DOMAIN-008` | domain | 생성형 AI 연동 | **deprecated** |  | scope-shrink | 도메인 폐기 — 생성 본체 외부, 업로더 검토·등록도 미보유 (V1.5) |
| `DOMAIN-009` | domain | 게시판·공지 | **deprecated** |  | scope-shrink | 게시판·공지 기능 2차 폐기 |
| `DOMAIN-010` | domain | 라벨링 | preserved |  |  |  |
| `DFEAT-001` | domain_feature | 외부 JWT 인계 로그인 | preserved | KLID-AI-PF-001 |  |  |
| `DFEAT-002` | domain_feature | 역할별 메뉴·접근제어 | modified | LS_USER_MENU | role-merge, actor-change | 1차 6역할 → 2차 3역할(REVIEWER·WORKER·PORTAL_USER), ADMIN 흡수 (V1.3) |
| `DFEAT-003` | domain_feature | 사용자 관리 | preserved | SKKLID-UI-03-05-01 |  |  |
| `DFEAT-004` | domain_feature | 프로젝트 생성 | **deprecated** | SKKLID-UI-03-02-04 | scope-shrink | 프로젝트 개념 폐기(V1.8)로 2차 폐기 |
| `DFEAT-005` | domain_feature | 프로젝트 관리·상세 | **deprecated** | SKKLID-UI-03-02-12 | scope-shrink | 프로젝트 개념 폐기(V1.8)로 2차 폐기 |
| `DFEAT-006` | domain_feature | 작업 배정·재배정·확인 *(→검수 도메인)* | modified | SKKLID-UI-03-02-14 | actor-change | REVIEWER→WORKER 영상 단위 배정 (V1.3/V1.8) |
| `DFEAT-007` | domain_feature | 영상/이미지 관리 *(→수집 도메인)* | modified | SKKLID-UI-03-03-01 | scope-shrink | 영상 단위 관리(프로젝트 배정 제거), 영상·프레임 수집으로 이관 |
| `DFEAT-008` | domain_feature | 클립영상 수신·적재 | preserved | KLID-AI-II-001 |  |  |
| `DFEAT-009` | domain_feature | FFmpeg 프레임 자동 추출 | preserved | KLID-AI-II-002 |  |  |
| `DFEAT-010` | domain_feature | 이미지 전처리 | preserved | KLID-AI-PF-005 |  |  |
| `DFEAT-011` | domain_feature | 메타데이터 기반 자동 분류 | preserved | LS_DATA_RAW |  |  |
| `DFEAT-012` | domain_feature | 도형 어노테이션(박스·폴리곤·스켈레톤) | preserved | SKKLID-UI-02-02-07 |  |  |
| `DFEAT-013` | domain_feature | 블러(비식별) 처리 | **deprecated** | SKKLID-UI-02-02-04 | component-replace | 캔버스 수동 블러 → 외부 비식별 솔루션 연동(FEAT-005)으로 대체 |
| `DFEAT-014` | domain_feature | 캔버스 도구 | preserved | SKKLID-UI-02-02-04 |  |  |
| `DFEAT-015` | domain_feature | 객체·메타·이슈 탭 | preserved | SKKLID-UI-02-02-09 |  |  |
| `DFEAT-016` | domain_feature | 단축키·전체 복사/붙여넣기 | preserved | SKKLID-UI-02-02-11 |  |  |
| `DFEAT-017` | domain_feature | 학습데이터 저장 | preserved | LS_DATA_LBL |  |  |
| `DFEAT-018` | domain_feature | AI Tool (SAM 클릭 세그) | preserved | SKKLID-UI-02-02-05 |  |  |
| `DFEAT-019` | domain_feature | Auto Labeling (YOLO) | preserved | SKKLID-UI-02-02-06 |  |  |
| `DFEAT-020` | domain_feature | 트랙 모드 (선형보간) | preserved | SKKLID-UI-02-05-08 |  |  |
| `DFEAT-021` | domain_feature | 검수 (검수자 1인 승인까지 반복) | modified | SKKLID-UI-02-02-16 | merge, redesign | 1·2차 단계 폐기, 단일 검수자 반복 검수 (021←021+022) |
| `DFEAT-022` | domain_feature | 2차 검수 | **deprecated** | SKKLID-UI-02-02-16 | merge | 단일 검수(021)로 통합 |
| `DFEAT-023` | domain_feature | 프레임 상태 색상 표기 | preserved | SKKLID-UI-02-02-16 |  |  |
| `DFEAT-024` | domain_feature | 승인·반려·확인 요청 | modified | SKKLID-UI-02-02-17 | actor-change | 관리자 폐기→검수자 확인 (역할 단일화) |
| `DFEAT-025` | domain_feature | 검수 이력 | preserved | SKKLID-UI-02-02-18 |  |  |
| `DFEAT-026` | domain_feature | 작업자/검수자 대시보드 | preserved | SKKLID-UI-02-01-01 |  |  |
| `DFEAT-027` | domain_feature | 관리자/담당자 대시보드 | preserved | SKKLID-UI-03-01-01 |  |  |
| `DFEAT-028` | domain_feature | 프로젝트 통계 | preserved | SKKLID-UI-03-02-16 |  |  |
| `DFEAT-029` | domain_feature | 데이터 증강 5종 | preserved | LS_DATA_AUG |  |  |
| `DFEAT-030` | domain_feature | 증강 상태 흐름·자동 재처리 | preserved | LS_DATA_AUG |  |  |
| `DFEAT-031` | domain_feature | 학습데이터 내보내기·데이터마트 등록 | **deprecated** | LS_DATA_SET | scope-shrink | Export·데이터마트 범위 외 (V1.4/V1.9) |
| `DFEAT-032` | domain_feature | Text2Image 생성 | **deprecated** | SKKLID-UI-02-05-03 | scope-shrink | 생성 본체 외부 (V1.5) |
| `DFEAT-033` | domain_feature | Image2Image 생성 | **deprecated** | SKKLID-UI-02-05-04 | scope-shrink | 생성 본체 외부 (V1.5) |
| `DFEAT-034` | domain_feature | Image2Video 생성 | **deprecated** | SKKLID-UI-02-05-05 | scope-shrink | 생성 본체 외부 (V1.5) |
| `DFEAT-035` | domain_feature | 메타데이터 기반 생성 | **deprecated** | SKKLID-UI-02-05-06 | scope-shrink | 생성 본체 외부 (V1.5) |
| `DFEAT-036` | domain_feature | 업로더 생성·업로드 데이터 검토·등록 | **deprecated** | SKKLID-UI-02-05-07 | scope-shrink | 2차 미보유(생성 외부, 업로드 포털 책임) |
| `DFEAT-037` | domain_feature | 게시글 목록·상세 조회 | **deprecated** | SKKLID-UI-02-03-01 | scope-shrink | 게시판 2차 폐기 |
| `DFEAT-038` | domain_feature | 게시글 작성·수정(관리자) | **deprecated** | SKKLID-UI-03-06-02 | scope-shrink | 게시판 2차 폐기 |
| `DFEAT-039` | domain_feature | 마킹 (자동/수동 이벤트 식별) | **new** |  | capability-add | V2.0 파이프라인 선두 마킹 단계 신규 |
| `DFEAT-040` | domain_feature | 연습장 (가공 작업 체험) | **deprecated** | SKKLID-UI-02-04-01 | scope-shrink | 2차 폐기 |
| `ERD-001` | erd | 사용자·권한 ERD | preserved | KLID-AI-PF-002 |  |  |
| `ERD-002` | erd | 검수 ERD | preserved | KLID-AI-PF-004 |  |  |
| `ERD-003` | erd | 통계·대시보드 ERD | preserved | KLID-AI-PF-004 |  |  |
| `ERD-004` | erd | 데이터 증강·내보내기 ERD | preserved | KLID-AI-PF-003 |  |  |
| `ERD-005` | erd | 생성형 AI·업로드 데이터 ERD (LS_DATA_USER) | **deprecated** | KLID-AI-PF-001 | scope-shrink | 생성형 AI·업로더 버티컬 폐기 |
| `ERD-006` | erd | 게시판·공지 ERD (LS_NTC_BBS, LS_ATCH_FILE) | **deprecated** | KLID-AI-PF-001 | scope-shrink | 게시판 2차 폐기 |
| `ERD-007` | erd | 영상·프레임 수집 ERD | preserved | KLID-AI-PF-005 |  |  |
| `ERD-008` | erd | 라벨링 ERD | preserved | KLID-AI-PF-004 |  |  |
| `ERD-009` | erd | 프로젝트 관리 ERD (LS_PJT 외 10종) | preserved | KLID-AI-PF-004 |  | ※ LS_PJT* 테이블 폐기/존속 DB 레벨 별도 결정 |
| `FEAT-001` | feature | AI 보조 라벨링 | modified | SKKLID-UI-02-02-05 | capability-add | SFR-08-01/02 고도화 |
| `FEAT-002` | feature | 라벨 버전관리·비교·복구 | new |  | capability-add | DB 스냅샷 기반 버전관리 2차 신규 (SFR-08-04/05) — 라벨 전체 스냅샷 DB 저장, 외부 VCS 미사용 |
| `FEAT-003` | feature | 데이터마트 라벨 동기화 통지 | new |  | capability-add | 관제 통지 2차 신규 (V1.8, SFR-08-06) |
| `FEAT-004` | feature | 영상 증강 연동·검수 + 해상도 변경 | modified | LS_DATA_AUG | capability-add | 외부 증강 연동·검수 (V1.5/V2.0, SFR-07) |
| `FEAT-005` | feature | 개인정보 비식별 처리 (솔루션 연동) | modified | SKKLID-UI-02-02-04 | component-replace | 수동 블러 → 외부 비식별 솔루션 (SFR-09) |
| `FEAT-006` | feature | 비식별 결과 검토·이력 확인 | new |  | capability-add | 2차 신규 (SFR-09-03) |
| `FEAT-007` | feature | 라벨링 정밀도 조절 | new |  | capability-add | 2차 신규 (SFR-08-03) |
| `REQ-001` | requirement | RQ-SFR-06-03 해상도 변경 등 영상 변형 | modified | LS_DATA_AUG | capability-add | 해상도 변경(본체 외부) |
| `REQ-002` | requirement | RQ-SFR-06-04 생성 프롬프트 편의성 | **deprecated** |  | scope-shrink | 외부 생성 시스템 책임 (V1.5) |
| `REQ-003` | requirement | RQ-SFR-07-01 생성형 AI 학습데이터 자동 생성 | modified | LS_DATA_AUG | capability-add | 생성형 AI 증강 연동(본체 외부) |
| `REQ-004` | requirement | RQ-SFR-07-02 생성 라벨링 데이터 무결성 | new |  | capability-add | 2차 신규 |
| `REQ-005` | requirement | RQ-SFR-07-03 생성 데이터 학습 활용 선택 | new |  | capability-add | 2차 신규 |
| `REQ-006` | requirement | RQ-SFR-08-01 라벨링 정확도 향상 | modified | SKKLID-UI-02-02-06 | capability-add | YOLO 고도화 |
| `REQ-007` | requirement | RQ-SFR-08-02 객체 외곽 경계 자동 밀착 | modified | SKKLID-UI-02-02-05 | capability-add | SAM 고도화 |
| `REQ-008` | requirement | RQ-SFR-08-03 라벨링 정밀도 조절 | modified | SKKLID-UI-02-02-05 | capability-add | 정밀도 조절 추가 |
| `REQ-009` | requirement | RQ-SFR-08-04 버전관리·이력추적 | new |  | capability-add | DB 스냅샷 기반 버전관리 2차 신규 |
| `REQ-010` | requirement | RQ-SFR-08-05 버전별 비교·복구 | new |  | capability-add | 2차 신규 |
| `REQ-011` | requirement | RQ-SFR-08-06 데이터마트 라벨 동기화 | new |  | capability-add | 관제 통지 2차 신규 (V1.8) |
| `REQ-012` | requirement | RQ-SFR-09-01 클립영상 개인정보 비식별화 | new |  | capability-add | 2차 신규 |
| `REQ-013` | requirement | RQ-SFR-09-02 비식별 솔루션 API 연동 | new |  | capability-add | 2차 신규 |
| `REQ-014` | requirement | RQ-SFR-09-03 비식별 결과 검토·이력관리 | new |  | capability-add | 2차 신규 |
| `REQ-015` | requirement | RQ-SFR-09-04 비식별 솔루션 옵션 설정 | new |  | capability-add | 2차 신규 |
| `REQ-016` | requirement | RQ-SFR-09-05 비식별 결과 연동 확인 | new |  | capability-add | 2차 신규 |
| `REQ-017` | requirement | RQ-SFR-09-06 개인정보 보호대책 | new |  | capability-add | 2차 신규 |
| `REQ-018` | requirement | RQ-SFR-09-07 개인정보 유형 분류체계 | new |  | capability-add | 2차 신규 |

---

_LogiCraft `generate_brownfield_report` (FEAT-006 P3) 기반 + 본 작업 요약 추가._
