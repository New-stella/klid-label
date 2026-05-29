# Brownfield 변경명세서 — KLID 학습데이터 저작도구 (2차)

> 출처: LogiCraft `generate_brownfield_report` (project: KLID-저작도구) 자동 생성 + 본 작업 요약 추가
> 생성일: 2026-05-29 · 작성: 박찬기 · K-DOC '데이터 변경 명세서' / 운영팀 인계 자료 입력용

## 0. 작업 요약 (5단계 brownfield 모델링)

| 단계 | 내용 | 산출 |
|------|------|------|
| Pass 1 | 1차 baseline 도메인 10종 식별 (preserved) | DOMAIN-001~010 |
| Step 1 | 레거시 카탈로그 잔여 등록 | API 17(LEGACY-043~059) + 화면 70(LEGACY-060~129) |
| Step 2 | 도메인별 1차 기능 모델링 | DFEAT 38종 (preserved) |
| Step 3 | LS_ 테이블 ERD (옵션D physical) | ERD 9종 (LS_ 30테이블) |
| Step 4 | Pass 2 고도화 brownfield 태깅 | modified/new/deprecated + 마킹 신규(DFEAT-039) |
| 보완 | 1차 기능 이관 완결성 audit | 연습장(DFEAT-040) deprecated 추가 |
| Step 5 | 본 변경명세 리포트 | — |

### 소스 정정 사항 (중요)
- **화면 수 66 → 70 정정**: 분석 MD(`KLID_IM_UI-UX설계서_분석.md` §7.1)는 65개만 나열하고 제목은 "66개"로 표기했으나, **원본 PDF**(`KLID_IM_UI, UX설계서_학습 저작도구 시스템.pdf`)에는 **70개 고유 화면 ID** 존재. 누락분 5종 = 사용자 관리(03-05-01) + 게시판 관리(03-06-01~04). `03-04-02`는 원본에도 없는 결번.
- **API ~17**: 인터페이스규격서 D4의 II-001~007(7) + UI설계서 §5.1 REST 패턴(10, 재구성). REST 10건은 실제 1차 구현 코드 미확인(notes 명기).

### 1차 기능 이관 완결성 audit (통합설계서 §4~12 교차검증)
- 통합설계서 §4~12의 1차 기능은 DFEAT로 **전수 매핑** 확인.
- **연습장(SKKLID-UI-02-04-01)**: 분석 MD·통합설계서 모두 누락됐던 1차 기능 → DFEAT-040 등록 후 **2차 폐기(deprecated)** 결정.
- **데이터 자동 배정(통합설계서 §4.4, 보유 10개↓ 시 1건 자동 보충 pull)**: 2차에서 **기능으로 두지 않음** → DFEAT 미생성(의도적 제외).
- **프로젝트 단위 → 영상 단위(RAW_SN) 라벨링 작업 구조 변경(V1.8)**: DOMAIN-002 + DFEAT-004/005/006 `modified` 로 반영.

### 거버넌스 메모
- **정식 ADR 부재**: 모든 modified/deprecated 항목의 `decided_by`(ADR)가 비어 있음 → 변경 근거는 `diff_summary`의 버전 표기(V1.3~V2.0)로 대체. ADR 정형화는 추후 보완 필요.
- **legacy_source 충돌 8건**: 한 1차 화면/테이블이 복수 2차 ITEM의 출처인 정당한 다대일 추적(예: SKKLID-UI-02-02-05 → DFEAT-018·FEAT-001·REQ-007·REQ-008). 매핑 오류 아님.
- **도메인 status**: stale 정리 과정에서 DOMAIN-001~005·007·008(7종)이 `approved`로 전환됨(collaborates_with 양방향 staleness 해소 — status-only 변경만 ripple 없음). DOMAIN-006·009·010은 draft 유지.
- **미태깅**: rfp_item 4종은 brownfield 미태깅(설계 ITEM 실질 커버리지 ≈ 95%).

---

# Brownfield Report — KLID-저작도구

- **Project**: `4ece2c3f-8e99-46f5-9580-71108a76e578`
- **Generated**: 2026-05-29T07:44:51Z
- **Items with brownfield**: 84

## 📊 Status Distribution by ITEM Type

| Type | preserved | modified | new | deprecated | split | merged | Total |
|---|---|---|---|---|---|---|---|
| domain | 8 | 2 | 0 | 0 | 0 | 0 | 10 |
| domain_feature | 28 | 5 | 1 | 6 | 0 | 0 | 40 |
| erd | 9 | 0 | 0 | 0 | 0 | 0 | 9 |
| feature | 0 | 3 | 4 | 0 | 0 | 0 | 7 |
| requirement | 0 | 5 | 12 | 1 | 0 | 0 | 18 |

## 📦 1차 Legacy Repo 분포

| Legacy Repo | ITEM count |
|---|---|
| KLID-AI-PF-001 | 37 |
| KLID-AI-PF-005 | 4 |
| KLID-AI-PF-004 | 4 |
| KLID-AI-PF-002 | 1 |
| KLID-AI-PF-003 | 1 |

## ⚠️ Legacy Source Conflicts (identifier 중복)

동일 1차 식별자가 여러 2차 ITEM 에 매핑된 경우. table-split 같은 의도적 분리이거나 매핑 오류일 수 있음. (아래는 모두 정당한 다대일 추적)

| Legacy Identifier | Mapped ITEMs |
|---|---|
| `KLID-AI-PF-001` | `DFEAT-001`, `ERD-005`, `ERD-006` |
| `KLID-AI-PF-005` | `DFEAT-010`, `ERD-007` |
| `SKKLID-UI-02-02-04` | `DFEAT-013`, `DFEAT-014`, `FEAT-005` |
| `SKKLID-UI-02-02-05` | `DFEAT-018`, `FEAT-001`, `REQ-007`, `REQ-008` |
| `SKKLID-UI-02-02-06` | `DFEAT-019`, `REQ-006` |
| `SKKLID-UI-02-02-16` | `DFEAT-021`, `DFEAT-022`, `DFEAT-023` |
| `LS_DATA_AUG` | `DFEAT-029`, `DFEAT-030`, `FEAT-004`, `REQ-001`, `REQ-003` |
| `KLID-AI-PF-004` | `ERD-002`, `ERD-003`, `ERD-008`, `ERD-009` |

## 📋 Brownfield Mapping Table

| ITEM ID | Type | Title | Status | Legacy Identifier | change_kind | diff_summary |
|---|---|---|---|---|---|---|
| `DOMAIN-001` | domain | 사용자·권한 | preserved |  |  |  |
| `DOMAIN-002` | domain | 프로젝트 관리 | modified |  | scope-shrink | 1차 프로젝트 중심 → 2차 작업 단위=영상 1건(RAW_SN), 프로젝트 단위 개념 축소 (V1.8) |
| `DOMAIN-003` | domain | 영상·프레임 수집 | preserved |  |  |  |
| `DOMAIN-004` | domain | AI 보조 라벨링 | preserved |  |  |  |
| `DOMAIN-005` | domain | 검수 | preserved |  |  |  |
| `DOMAIN-006` | domain | 통계·대시보드 | preserved |  |  |  |
| `DOMAIN-007` | domain | 데이터 증강·내보내기 | preserved |  |  |  |
| `DOMAIN-008` | domain | 생성형 AI 연동 | modified |  | scope-shrink | 1차 생성 본체(Text2Image/Image2Image/Image2Video) 보유 → 2차 생성 본체 외부화(SFR-06/11), 저작도구는 외부 생성 데이터 검토·등록만 잔존 (V1.5) |
| `DOMAIN-009` | domain | 게시판·공지 | preserved |  |  |  |
| `DOMAIN-010` | domain | 라벨링 | preserved |  |  |  |
| `DFEAT-001` | domain_feature | 외부 JWT 인계 로그인 (독립 로그인 UI 없음) | preserved | KLID-AI-PF-001 |  |  |
| `DFEAT-002` | domain_feature | 역할별 메뉴·접근제어 | modified | LS_USER_MENU | role-merge, actor-change | 1차 6역할 → 2차 3역할(REVIEWER·WORKER·PORTAL_USER), ADMIN 권한 REVIEWER 흡수 (V1.3) |
| `DFEAT-003` | domain_feature | 사용자 관리 | preserved | SKKLID-UI-03-05-01 |  |  |
| `DFEAT-004` | domain_feature | 프로젝트 생성 (정보·단계·라벨·권한 설정) | modified | SKKLID-UI-03-02-04 | scope-shrink | 1차 프로젝트 단위 → 2차 작업 단위=영상 1건(RAW_SN) (V1.8) |
| `DFEAT-005` | domain_feature | 프로젝트 관리·상세 조회 | modified | SKKLID-UI-03-02-12 | scope-shrink | 1차 프로젝트 관리 → 2차 영상 단위 작업 관리 (V1.8) |
| `DFEAT-006` | domain_feature | 작업 배정·재배정·확인 | modified | SKKLID-UI-03-02-14 | actor-change | 1차 관리자/담당자 배정 → 2차 REVIEWER가 WORKER에게 배정(역할 단일화), 영상 단위 (V1.3/V1.8) |
| `DFEAT-007` | domain_feature | 영상/이미지 관리·프로젝트 배정 | preserved | SKKLID-UI-03-03-01 |  |  |
| `DFEAT-008` | domain_feature | 클립영상 수신·적재 | preserved | KLID-AI-II-001 |  |  |
| `DFEAT-009` | domain_feature | FFmpeg 프레임 자동 추출 (배치 1회/분) | preserved | KLID-AI-II-002 |  |  |
| `DFEAT-010` | domain_feature | 이미지 전처리 (리사이징·밝기/대비 보정) | preserved | KLID-AI-PF-005 |  |  |
| `DFEAT-011` | domain_feature | 메타데이터 기반 자동 분류 | preserved | LS_DATA_RAW |  |  |
| `DFEAT-012` | domain_feature | 도형 어노테이션 (바운딩박스·폴리곤·스켈레톤) | preserved | SKKLID-UI-02-02-07 |  |  |
| `DFEAT-013` | domain_feature | 블러 (비식별) 처리 | preserved | SKKLID-UI-02-02-04 |  |  |
| `DFEAT-014` | domain_feature | 캔버스 도구 (그리드·밝기·투명도·이동/회전/확대) | preserved | SKKLID-UI-02-02-04 |  |  |
| `DFEAT-015` | domain_feature | 객체·메타·이슈 탭 | preserved | SKKLID-UI-02-02-09 |  |  |
| `DFEAT-016` | domain_feature | 단축키·전체 복사/붙여넣기 | preserved | SKKLID-UI-02-02-11 |  |  |
| `DFEAT-017` | domain_feature | 학습데이터 저장 | preserved | LS_DATA_LBL |  |  |
| `DFEAT-018` | domain_feature | AI Tool (SAM 클릭 세그멘테이션) | preserved | SKKLID-UI-02-02-05 |  |  |
| `DFEAT-019` | domain_feature | Auto Labeling (YOLO 객체 탐지) | preserved | SKKLID-UI-02-02-06 |  |  |
| `DFEAT-020` | domain_feature | 트랙 모드 (선형보간 연속 프레임 추적) | preserved | SKKLID-UI-02-05-08 |  |  |
| `DFEAT-021` | domain_feature | 1차 검수 | preserved | SKKLID-UI-02-02-16 |  |  |
| `DFEAT-022` | domain_feature | 2차 검수 | preserved | SKKLID-UI-02-02-16 |  |  |
| `DFEAT-023` | domain_feature | 프레임 상태 색상 표기 (연두/주황/빨강) | preserved | SKKLID-UI-02-02-16 |  |  |
| `DFEAT-024` | domain_feature | 승인·반려·관리자 확인 요청 | preserved | SKKLID-UI-02-02-17 |  |  |
| `DFEAT-025` | domain_feature | 검수 이력 | preserved | SKKLID-UI-02-02-18 |  |  |
| `DFEAT-026` | domain_feature | 작업자/검수자 대시보드 (월별·일별 통계) | preserved | SKKLID-UI-02-01-01 |  |  |
| `DFEAT-027` | domain_feature | 관리자/담당자 대시보드 | preserved | SKKLID-UI-03-01-01 |  |  |
| `DFEAT-028` | domain_feature | 프로젝트 통계 (전체·권한별·상태별·일일 진행률) | preserved | SKKLID-UI-03-02-16 |  |  |
| `DFEAT-029` | domain_feature | 데이터 증강 5종 | preserved | LS_DATA_AUG |  |  |
| `DFEAT-030` | domain_feature | 증강 상태 흐름·자동 재처리 | preserved | LS_DATA_AUG |  |  |
| `DFEAT-031` | domain_feature | 학습데이터 내보내기·데이터마트 등록 | deprecated | LS_DATA_SET | scope-shrink | Export·데이터마트 구축 — 범위 외, 외부 제공 시스템 책임 (V1.4/V1.9) |
| `DFEAT-032` | domain_feature | Text2Image 생성 | deprecated | SKKLID-UI-02-05-03 | scope-shrink | 생성 본체 — 외부 생성 시스템 책임 (V1.5). 저작도구는 증강 결과 검수만 잔존 |
| `DFEAT-033` | domain_feature | Image2Image 생성 | deprecated | SKKLID-UI-02-05-04 | scope-shrink | 생성 본체 — 외부 시스템 책임 (V1.5) |
| `DFEAT-034` | domain_feature | Image2Video 생성 | deprecated | SKKLID-UI-02-05-05 | scope-shrink | 생성 본체 — 외부 시스템 책임 (V1.5/V1.11 영상합성 외부) |
| `DFEAT-035` | domain_feature | 메타데이터 기반 생성 | deprecated | SKKLID-UI-02-05-06 | scope-shrink | 생성 본체 — 외부 시스템 책임 (V1.5) |
| `DFEAT-036` | domain_feature | 업로더 생성·업로드 데이터 검토·등록 | modified | SKKLID-UI-02-05-07 | scope-shrink | 1차 생성+업로드 → 2차 생성은 외부, 저작도구는 증강 검수(SCR-AUG-002)·업로드 등록만 잔존 (V1.5) |
| `DFEAT-037` | domain_feature | 게시글 목록·상세 조회 | preserved | SKKLID-UI-02-03-01 |  |  |
| `DFEAT-038` | domain_feature | 게시글 작성·수정 (관리자) | preserved | SKKLID-UI-03-06-02 |  |  |
| `DFEAT-039` | domain_feature | 마킹 (자동/수동 이벤트 식별) | new |  | capability-add | V2.0 파이프라인 선두 마킹 단계 신규 — 1차 미존재. 마킹→VLM 시계열→비식별→프레임추출 트리거 |
| `DFEAT-040` | domain_feature | 연습장 (가공 작업 체험) | deprecated | SKKLID-UI-02-04-01 | scope-shrink | 1차 작업자 가공 작업 체험(연습장) — 2차 폐기 |
| `ERD-001` | erd | 사용자·권한 ERD | preserved | KLID-AI-PF-002 |  |  |
| `ERD-002` | erd | 검수 ERD (LS_PJT_DATA_STTS) | preserved | KLID-AI-PF-004 |  |  |
| `ERD-003` | erd | 통계·대시보드 ERD | preserved | KLID-AI-PF-004 |  |  |
| `ERD-004` | erd | 데이터 증강·내보내기 ERD | preserved | KLID-AI-PF-003 |  |  |
| `ERD-005` | erd | 생성형 AI·업로드 데이터 ERD | preserved | KLID-AI-PF-001 |  |  |
| `ERD-006` | erd | 게시판·공지 ERD | preserved | KLID-AI-PF-001 |  |  |
| `ERD-007` | erd | 영상·프레임 수집 ERD | preserved | KLID-AI-PF-005 |  |  |
| `ERD-008` | erd | 라벨링 ERD | preserved | KLID-AI-PF-004 |  |  |
| `ERD-009` | erd | 프로젝트 관리 ERD (LS_PJT 외 10종) | preserved | KLID-AI-PF-004 |  |  |
| `FEAT-001` | feature | AI 보조 라벨링 (객체 추적·외곽 경계 밀착) | modified | SKKLID-UI-02-02-05 | capability-add | 1차 AI Tool(SAM)+Auto Labeling(YOLO) → 2차 고도화 (SFR-08-01/02) |
| `FEAT-002` | feature | 라벨 버전관리·비교·복구 | new |  | capability-add | Gitea 기반 버전관리·diff·롤백 2차 신규 (SFR-08-04/05) |
| `FEAT-003` | feature | 데이터마트 라벨 동기화 통지 | new |  | capability-add | 관제서버 outbound TASK_COMPLETED/MODIFIED 통지 2차 신규 (V1.8, SFR-08-06) |
| `FEAT-004` | feature | 영상 증강 연동·검수 + 해상도 변경 | modified | LS_DATA_AUG | capability-add, component-replace | 1차 증강 5종 → 2차 외부 증강 연동·검수 + 해상도 변경 (V1.5/V2.0, SFR-07) |
| `FEAT-005` | feature | 개인정보 비식별 처리 (솔루션 연동·옵션 사용) | modified | SKKLID-UI-02-02-04 | component-replace | 1차 수동 블러 → 2차 외부 비식별 솔루션 연동 (SFR-09-01/02/04) |
| `FEAT-006` | feature | 비식별 결과 검토·이력 확인 | new |  | capability-add | 2차 신규 (SFR-09-03) |
| `FEAT-007` | feature | 라벨링 정밀도 조절 | new |  | capability-add | 2차 신규 (SFR-08-03, REQ-008 분리) |
| `REQ-001` | requirement | RQ-SFR-06-03 해상도 변경 등 영상 변형 | modified | LS_DATA_AUG | capability-add | 1차 증강 기반 → 2차 해상도 변경(저작도구 잔존, 본체 외부) (SFR-06-03) |
| `REQ-002` | requirement | RQ-SFR-06-04 생성 프롬프트 편의성 개선 | deprecated |  | scope-shrink | 외부 생성 시스템 책임으로 이관, 저작도구 범위 외 (V1.5) |
| `REQ-003` | requirement | RQ-SFR-07-01 생성형 AI 학습데이터 자동 생성 | modified | LS_DATA_AUG | capability-add | 1차 증강 5종 → 2차 생성형 AI 증강(WINTER/NIGHT/RAIN/RESOLUTION) 연동 (본체 외부) |
| `REQ-004` | requirement | RQ-SFR-07-02 생성 라벨링 데이터 무결성 | new |  | capability-add | 2차 신규 (SFR-07-02) |
| `REQ-005` | requirement | RQ-SFR-07-03 생성 데이터 학습 활용 선택 | new |  | capability-add | 2차 신규 (SFR-07-03) |
| `REQ-006` | requirement | RQ-SFR-08-01 라벨링 정확도 향상 | modified | SKKLID-UI-02-02-06 | capability-add | 1차 Auto Labeling(YOLO) → 2차 정확도 향상 (SFR-08-01) |
| `REQ-007` | requirement | RQ-SFR-08-02 객체 외곽 경계 자동 밀착 | modified | SKKLID-UI-02-02-05 | capability-add | 1차 AI Tool(SAM) → 2차 외곽 경계 자동 밀착 (SFR-08-02) |
| `REQ-008` | requirement | RQ-SFR-08-03 라벨링 정밀도 조절 | modified | SKKLID-UI-02-02-05 | capability-add | 1차 AI 보조 → 2차 정밀도 조절 추가 (SFR-08-03) |
| `REQ-009` | requirement | RQ-SFR-08-04 학습데이터 버전관리·이력추적 | new |  | capability-add | Gitea 기반 2차 신규 (SFR-08-04) |
| `REQ-010` | requirement | RQ-SFR-08-05 버전별 비교·복구 | new |  | capability-add | 2차 신규 (SFR-08-05) |
| `REQ-011` | requirement | RQ-SFR-08-06 데이터마트 라벨 동기화 | new |  | capability-add | 관제 통지 2차 신규 (SFR-08-06, V1.8) |
| `REQ-012` | requirement | RQ-SFR-09-01 클립영상 개인정보 비식별화 | new |  | capability-add | 2차 신규 (SFR-09-01) |
| `REQ-013` | requirement | RQ-SFR-09-02 비식별 솔루션 API 연동 | new |  | capability-add | 2차 신규 (SFR-09-02) |
| `REQ-014` | requirement | RQ-SFR-09-03 비식별 결과 검토·이력관리 | new |  | capability-add | 2차 신규 (SFR-09-03) |
| `REQ-015` | requirement | RQ-SFR-09-04 비식별 솔루션 옵션 설정 | new |  | capability-add | 2차 신규 (SFR-09-04) |
| `REQ-016` | requirement | RQ-SFR-09-05 비식별 결과 연동 확인 | new |  | capability-add | 2차 신규 (SFR-09-05) |
| `REQ-017` | requirement | RQ-SFR-09-06 개인정보 보호대책 | new |  | capability-add | 암호화·비정상 로그인 방지·개인정보 필터 2차 신규 (SFR-09-06) |
| `REQ-018` | requirement | RQ-SFR-09-07 개인정보 유형 분류체계 | new |  | capability-add | 2차 신규 (SFR-09-07) |

---

_LogiCraft `generate_brownfield_report` (FEAT-006 P3) 기반 + 본 작업 요약 추가._
