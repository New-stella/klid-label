# LogiCraft ITEM 정합성 검토 — 현재 방향 대비

> 생성일: 2026-06-01 / 작성: claude
> 대상 프로젝트: LogiCraft `KLID-저작도구` (project_id `4ece2c3f-8e99-46f5-9580-71108a76e578`, program `db8083f7-…`)
> 검토 기준(현재 방향): `CLAUDE.md` + LogiCraft kickoff(brownfield_modernization, modular_monolith, single_rdb=PostgreSQL)
> 방법: 5개 타입 ITEM 전수 인벤토리(title·status·change_summary) + 핵심 ERD 표본 상세(ERD-002/009) + 실제 Flyway 마이그레이션 대조

> **[후기 2026-06-01 — 재정비 완료]** 본 문서는 재정비 **착수 전** 진단이다. 이후 ERD 재정비(1차 ERD-001~009 deprecated + 고도화 신규 ERD-010~021), domain 정비(DOMAIN-002/008/009 deprecated·DOMAIN-007 title 정정), domain_feature 정비(deprecated 13·title 3·stale 4)가 완료되어 갭 **I-1(ERD)·I-3(domain status)·I-4(title)·I-5(stale) 해소**됨. 잔여: I-6(전체 draft→approved 미전이), BM 메타정정 4건, DE_IDNTF_YN 고도화 용어.

## 0. 검토 범위 / 한계

- **대상 타입**: domain(16) · erd(9) · feature(7) · domain_feature(40) · api_endpoint(89) = **총 161 ITEM**
- **정밀 상세 확인**: ERD-002·ERD-009(전체 data) + 마이그레이션 CREATE/DROP TABLE 전수 grep.
- **표본 기반**: 나머지 ERD·도메인기능은 title + change_summary 로 1차 판정. 완전 검증(각 ITEM data ↔ 코드 컬럼 대조)은 후속 과제로 남김. 본 문서는 **"방향 정합성 1차 진단 + 우선 조치 목록"** 이다.

## 1. 현재 방향(기준) 요약 — CLAUDE.md

| # | 현재 방향(지향점) |
|---|---|
| D1 | **프로젝트 단위 개념 폐기** → 작업 단위 = 영상 1건(`LS_DATA_RAW.RAW_SN`) |
| D2 | **생성형 AI 본체 외부화** — 저작도구는 외부 증강 결과 검수만 |
| D3 | **학습데이터 내보내기(Export) 범위 외** — 라벨링·검수·버전관리까지만 |
| D4 | **2차 검수 폐기** → 단일 검수(REVIEWER 1인 승인) |
| D5 | **ADMIN 역할 없음** → 모든 관리권한 REVIEWER 통합, UI 호칭 '검수자', 관리 URL `/manage/*` |
| D6 | **관제 M2M 양방향 deprecated** → 단방향 outbound 통지(TASK_COMPLETED/MODIFIED) + inbound 조회 API만 |
| D7 | **비식별 = 외부 솔루션 연동** — 캔버스 수동 블러 폐기 |
| D8 | **마킹 도메인 신규**(자동/수동 이벤트 식별) |
| D9 | **DB = PostgreSQL 단일 RDB**, LS_* 자체 소유 / MNG_*·QRTZ_* 공유(validate) |

## 2. 인벤토리 요약 + status 분포

| 타입 | 개수 | status 분포 | 비고 |
|---|---:|---|---|
| domain | 16 | approved 5 / draft 11 | deprecated 도메인 3종이 draft로 활성 잔존 |
| erd | 9 | draft 9 | **전부 draft** — 승인 0 |
| feature | 7 | approved 1 / draft 6 | 방향 정합도 높음 |
| domain_feature | 40 | draft 40 | deprecated 12+종 draft 잔존, stale 4건 |
| api_endpoint | 89 | draft 89 | **전부 draft**, 전부 version 1 — 영상단위 기반(정합도 최상) |

> **공통 이슈**: 161개 중 거의 전부 `draft`. D7/D8 산출물까지 작성됐는데 **approved 전이가 거의 안 됨** → 그래프상 "확정된 설계"가 아직 없음.

## 3. 타입별 정합성 검토

### 3.1 ERD (9) — ★ 최우선 이슈: 프로젝트 단위 잔재

현재 방향 D1(영상 단위)과 가장 크게 어긋나는 영역.

| ERD | title | 정합성 판정 |
|---|---|---|
| **ERD-002** | 검수 ERD (**LS_PJT_DATA_STTS**) | 🔴 **불일치**. 메인 테이블이 프로젝트단위 `LS_PJT_DATA_STTS`(PJT_SN PK). `implementation.status=implemented(100%)` 인데, **실제 코드의 영상단위 검수 테이블 `LS_RAW_DATA_STATUS`(V36 신설)가 ERD에 없음.** PJT_SN/PJT_PATH_SN 은 deprecated 마킹돼 있으나 테이블 골격 자체가 옛 모델. |
| **ERD-009** | 프로젝트 관리 ERD (**LS_PJT** 외 10종) | 🔴 **불일치**. `LS_PJT`는 코드에서 **V35 `DROP TABLE LS_PJT`로 제거됨.** deprecated 마킹은 있으나 ITEM이 draft 활성. 프로젝트 관리 도메인(DOMAIN-002)은 deprecated인데 ERD는 살아있음. |
| **ERD-003** | 통계 ERD (**LS_PJT_DATA_STATS, LS_PJT_JOB_STATS**) | 🟠 **title 잔재 의심**. PJT 네이밍. 상세 미확인(표본 외) — 영상단위 통계 테이블과의 정합 확인 필요. |
| ERD-005 | 생성형 AI·업로드 ERD (LS_DATA_USER) | 🟡 deprecated 처리됨(D2 정합). 단 ITEM은 draft 잔존. |
| ERD-006 | 게시판·공지 ERD | 🟡 활성 유지(brownfield.status로만 폐기 추적). |
| ERD-001 / 004 / 007 / 008 | 사용자권한 / 증강 / 영상수집 / 라벨링 | 🟢 네이밍상 영상단위 정합(LS_USER_*, LS_DATA_RAW/SRC, LS_DATA_LBL 등). 상세 대조는 후속. |

**코드 마이그레이션 실측 (현재 스키마)**:
- 영상단위 신규 존재: `LS_RAW_DATA_STATUS`, `LS_RAW_DATA_ENROLLMENT`, `LS_TASK_ASSIGNMENT`, `LS_TASK_ASSIGN_HISTORY`, `LS_TASK_EVENT_LOG`, `LS_MARKING`, `LS_CONTROL_NOTIFY_FALLBACK`, `LS_DEADLINE` (V36~)
- DROP됨: `LS_PJT`(V35), `LS_GITEA_FALLBACK_QUEUE`(V53)
- **잔존 PJT 테이블(코드에 CREATE된 채)**: `LS_PJT_DATA_STTS`, `LS_PJT_DATA_MPNG`, `LS_PJT_DDLN`, `LS_PJT_META`, `LS_PJT_TASK_EVENT_LOG`, `LS_PJT_USER_AUTHRT(_HSTRY)` → **코드 자체도 과도기**(영상단위 신규 + PJT 잔재 공존). ERD/코드/방향 3자 모두 정리 미완.

> **핵심 갭**: 영상단위 핵심 테이블 `LS_RAW_DATA_STATUS`·`LS_TASK_ASSIGNMENT`·`LS_MARKING` 등이 **어느 ERD ITEM에도 메인으로 등록돼 있지 않음**(마킹 도메인은 DOMAIN-011 신규지만 대응 ERD 부재). ERD 계층이 영상단위 전환을 따라오지 못함.

### 3.2 domain (16) — deprecated 처리 일관성

| 상태 | 도메인 | 판정 |
|---|---|---|
| deprecated(방향 정합) | DOMAIN-002 프로젝트관리, DOMAIN-008 생성형AI연동 | 🟢 D1·D2 정합. 단 status=draft 로 잔존(retire 안 됨) |
| modified | DOMAIN-007 데이터 증강·**내보내기** | 🟠 내보내기 폐기(D3) 반영했으나 **title에 "내보내기" 잔재** |
| 신규(방향 정합) | DOMAIN-011 마킹, DOMAIN-016 관제통지 | 🟢 D8·D6 정합 |
| 활성 | DOMAIN-009 게시판·공지 | 🟡 게시판 폐기를 brownfield.status로만 추적, ITEM 활성 — 판단 필요 |
| 활성 | DOMAIN-001/003/004/005/006/010/012/013/014/015 | 🟢 현재 도메인 구성과 정합 |

### 3.3 domain_feature (40) — deprecated 잔존 + title 잔재

- 🟢 **방향 정합 deprecated 처리**(D1~D4·D7): DFEAT-004/005(프로젝트 생성/관리), DFEAT-022(2차검수), DFEAT-031(내보내기), DFEAT-032~035(생성형AI Text2Image 등 4종), DFEAT-036(업로더), DFEAT-013(수동 블러), DFEAT-040(연습장) — change_summary로 폐기 사유 명시됨. **단 전부 status=draft 로 활성 잔존.**
- 🟠 **title에 폐기 개념 잔재**:
  - DFEAT-007 "영상/이미지 관리·**프로젝트 배정**" (change_summary는 "프로젝트배정 제거(modified)" — title 미정리)
  - DFEAT-028 "**프로젝트** 통계" (D1 위반 호칭)
  - DFEAT-027 "**관리자**/담당자 대시보드" (D5: ADMIN 폐기·REVIEWER 통합인데 '관리자' 호칭)
- 🟠 **stale=true 4건**: DFEAT-018(SAM), DFEAT-019(YOLO), DFEAT-020(트랙), DFEAT-029(증강 5종) — 상위 ITEM 변경 후 재검토 미반영.

### 3.4 feature (7) — 정합도 높음

FEAT-001~007 모두 라벨링/AI보조/비식별/증강연동/버전관리/데이터마트 통지로 현재 범위와 정합. 내보내기·생성형AI 본체 기능 없음(D2·D3 정합). FEAT-003 "데이터마트 라벨 동기화 통지"는 D6(outbound 통지) 정합. → **별도 정리 불요**, status 승인만 남음.

### 3.5 api_endpoint (89) — 정합도 최상

- 전부 **영상단위(`/v1/videos/{rawSn}`, `/v1/tasks/{rawSn}`, `/v1/reviews/{videoId}`)** 기반 → D1 정합. 프로젝트 API 없음.
- D6 정합: `/v1/tasks/{rawSn}/summary|labels|meta`(관제 inbound 조회), 단방향 통지 페이로드.
- D4 정합: `/v1/reviews/*` 단일 검수(2차 검수 엔드포인트 없음).
- D5 정합: 관리 기능 `/v1/manage/*`.
- D2·D3 정합: 생성형AI 본체·내보내기 API 없음. 증강은 `/v1/augments/*`(외부 연동+검수)만.
- D8 정합: `/v1/videos/{rawSn}/markings/*`, `/v1/videos/{rawSn}/stream`(Range).
- ⚠ 전부 status=draft, version 1 — 승인 전이 필요.

## 4. 주요 이슈 (우선순위)

| # | 심각도 | 이슈 | 해당 ITEM |
|---|---|---|---|
| I-1 | 🔴 High | **ERD 계층이 영상단위 전환 미반영**. 검수/통계/프로젝트 ERD가 LS_PJT_* 기반이고, 코드 신규 `LS_RAW_DATA_STATUS` 등 영상단위 테이블의 ERD가 부재 | ERD-002, ERD-003, ERD-009 |
| I-2 | 🔴 High | **코드 마이그레이션 자체가 과도기** — LS_PJT_* 잔존 테이블(STTS/MPNG/DDLN/META 등)이 DROP 안 됨. 방향(D1)대로면 정리 필요 | 코드 V1/V36/V37 |
| I-3 | 🟠 Med | deprecated ITEM이 retire 안 되고 status=draft로 활성 잔존 → 인벤토리상 "활성"으로 오인 | DOMAIN-002/008, DFEAT 12+종 |
| I-4 | 🟠 Med | **title에 폐기 개념 잔재** (프로젝트 배정/통계, 관리자, 내보내기) | DFEAT-007/027/028, DOMAIN-007 |
| I-5 | 🟠 Med | stale=true 4건 미해소 | DFEAT-018/019/020/029 |
| I-6 | 🟡 Low | 161 ITEM 거의 전부 draft — 승인된 설계 baseline 부재 | 전체 |

## 5. 권고 조치

1. **(I-1) ERD 영상단위 재정비** — 검수 ERD를 `LS_RAW_DATA_STATUS` 기준으로 갱신(또는 신규 ERD 분리). `LS_TASK_ASSIGNMENT`·`LS_MARKING`·`LS_CONTROL_NOTIFY_FALLBACK` 등 신규 영상단위 테이블의 ERD ITEM 신설. ERD-009(LS_PJT)는 deprecated 명시 + retire 검토.
2. **(I-2) 코드 PJT 잔재 처리는 별도 결정** — LS_PJT_* 잔존 테이블의 DROP 여부는 데이터 마이그레이션 영향이 있어 사용자/팀 결정 후. (본 검토 범위는 LogiCraft 정합 진단까지)
3. **(I-3) deprecated ITEM 일괄 정리** — retire 처리 또는 brownfield.status=deprecated 일관 적용 + 인벤토리 필터 합의.
4. **(I-4) title 정정** — "프로젝트 배정/통계"→영상/작업 단위 호칭, "관리자"→"검수자", DOMAIN-007 title에서 "내보내기" 제거.
5. **(I-5) stale 4건** — 상위 변경 반영 후 stale 해소.
6. **(I-6) 승인 워크플로** — 방향 정합 확인된 ITEM(특히 API 89, feature 7)부터 approved 전이.

## 6. 결론

- **API(89)·feature(7)** 는 현재 방향(영상단위·단일검수·외부연동·통지)과 **정합도 높음**. 승인만 남음.
- **domain(16)·domain_feature(40)** 는 폐기 사유는 추적되나 **draft 잔존·title 잔재**로 인벤토리 위생 정리 필요.
- **ERD(9)** 가 **가장 큰 갭** — 프로젝트단위(LS_PJT_*) 모델이 남아있고 영상단위 신규 테이블(LS_RAW_DATA_STATUS 등) ERD가 부재. 코드 마이그레이션도 과도기라 ERD·코드·방향 3자 정렬이 미완.

> 본 문서는 1차 방향 진단이다. ERD 9개 전체 data ↔ 코드 컬럼 정밀 대조, domain_feature 40 상세 검증은 후속 작업으로 권고한다.
