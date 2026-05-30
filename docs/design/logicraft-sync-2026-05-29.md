# LogiCraft ↔ 프로젝트 정합 비교 (Sync Plan)

> 생성: 2026-05-29 · 작성: 박찬기
> 대상: LogiCraft `KLID-저작도구`(`4ece2c3f-8e99-46f5-9580-71108a76e578`) ↔ 현재 코드/스키마
> 목적: 양측 차이를 식별 → ① 소스에 개발할 것 ② LogiCraft에 올릴 것 을 정해 싱크

## 0. 인벤토리 대조

| 측 | 보유 |
|----|------|
| **LogiCraft** | 브라운필드 ITEM **84종만**: domain 10 · domain_feature 40 · erd 9 · feature 7 · requirement 18. **screen_spec·api_endpoint·code_module·glossary·nfr·adr·infra_component = 0** (설계 레벨 모델링) |
| **코드** | 엔티티 46 · 컨트롤러 35 · Flyway 마이그레이션 52(V0~V53) · FE 페이지 26 · 완전 구현·빌드/테스트 GREEN(BE 966/FE 228)·라이브 검증 완료 |

→ **구조적 비대칭**: LogiCraft는 도메인/기능/요구/ERD 까지만, 코드는 API·화면·모듈까지 전부 구현. LogiCraft가 코드의 부분집합(설계도)이며, 코드가 PG·Gitea 변경까지 선반영해 **코드가 설계를 상회**한다.

## 1. 일치 (정합됨 — 조치 불필요)

대화 초반 + Phase 0 에서 정합 확인:
- 도메인 10종(활성 7 + 폐기 3: 프로젝트관리·생성형AI·게시판), 역할 3종(REVIEWER/WORKER/PORTAL_USER)
- 2차 스코프: 작업단위=영상, 마킹 신규(DFEAT-039), Export/데이터마트 범위외, 생성형AI/VLM/영상합성 외부화, 검수 1·2차→단일, 캔버스 블러→외부 비식별
- FEAT/REQ 7+18종의 요구사항 매핑 (SFR-06~09)

## 2. 불일치 카탈로그 (방향 + 조치)

방향: **→LC** = LogiCraft에 반영 / **→CODE** = 소스에 개발 / **결정** = 정책 판단 필요

| # | 영역 | LogiCraft (설계) | 코드 (현재) | 방향 | 조치 |
|---|------|-----------------|------------|:---:|------|
| D1 | DB 엔진 | ERD 9종 `dbms: "mysql"`, 전 테이블 `legacy_dbms: "MariaDB"`, 컬럼타입 `bigint(20)`·`datetime`·`int(11)` | PostgreSQL (IDENTITY·timestamp·integer·text) | →LC | 활성 ERD 7종 `dbms: postgresql` + 컬럼타입 PG 표기. `legacy_dbms: MariaDB`는 1차 이력이므로 유지 |
| D2 | 버전관리 방식 | FEAT-002 diff_summary "**Gitea 기반** 라벨 버전관리·diff·롤백" | DB 전체 스냅샷(앱 계산) | →LC | diff_summary·description "DB 스냅샷 기반(외부 VCS 미사용)"으로 수정 |
| D3 | LS_LABEL_VERSION 테이블 | **ERD 9종 어디에도 모델링 없음** | 핵심 테이블 (label_payload TEXT, version_hash, version_no, active_yn, save_reason_cd, data_raw_sn, data_src_sn, reg_id, reg_dt; UNIQUE(data_src_sn,version_hash)) | →LC | 라벨링 ERD(ERD-008)에 테이블 신규 추가 |
| D4 | LS_DATA_LBL_HSTRY 설명 | "...**Gitea 커밋과 함께** 버전 이력 보존" | DB 스냅샷(LS_LABEL_VERSION) 기반 | →LC | description 정정 |
| D5 | LS_GITEA_FALLBACK_QUEUE | (모델링 없음) | V53에서 DROP (코드 0) | — | LogiCraft 미모델이라 조치 불필요 (확인 완료) |
| D6 | LS_DATA_LBL/META/ISSUE 의 `PJT_SN`(프로젝트 FK) | ERD-008에 잔존 | **코드에 PJT 컬럼 없음**(프로젝트 폐기로 제거) | →LC + 결정 | ERD에서 PJT_SN 제거 또는 deprecated 표기. "LS_PJT* DB레벨 폐기/존속" 미결정 사항의 실제 코드 결론을 반영 |
| D7 | 구현 진척 | 전 ITEM `status: draft`, `implementation: planned, progress 0%` | 전부 구현·검증 완료 | →LC(선택) | mark_implementation / implementation 갱신으로 implemented + 코드 모듈 매핑 |
| D8 | API/화면/모듈 모델링 | api_endpoint·screen_spec·code_module = 0 | 컨트롤러 35·FE 페이지 26·엔티티 46 | →LC(선택) | 모델링 범위 결정 — 설계도구로 코드까지 추적할지(대규모) vs 도메인/ERD 레벨 유지 |

## 3. 조치 목록

### A. 소스(코드)에 개발할 것 — `→CODE`  ✅ audit 완료 (2026-05-29)
**DFEAT 40 + FEAT 7 ↔ 코드 1:1 audit 결과 — 코드 GAP 없음:**
- 활성 DFEAT 27종 **전부 구현**(Controller→Service→Entity 완결), 폐기 DFEAT 13종 **코드 잔존 0**(폐기 정책 준수).
- 2차 FEAT 7종 중 FEAT-001~006 구현 완료. **FEAT-007(라벨링 정밀도 조절)만 placeholder/부분 구현** — 유일한 코드 개발 후보(우선순위 협의 필요).
- D6: 프로젝트 폐기에 따른 PJT 컬럼은 코드(마이그레이션)에서 이미 제거됨 → 코드 조치 불필요, LogiCraft 정리만 남음.

→ **싱크는 LogiCraft 단방향 반영(아래 B)이 본체.** (코드가 설계 완전 구현 + PG/Gitea 선반영)

### B. LogiCraft에 올릴 것 — `→LC` (싱크의 본체) — 진행현황 (2026-05-29)
1. ✅ **FEAT-002 (D2)**: Gitea→DB 스냅샷 표현 수정 (v4).
2. ✅ **LS_LABEL_VERSION 테이블 추가 (D3)**: ERD-008에 신규 테이블 + 관계(N:1 LS_DATA_SRC) (v2).
3. ✅ **LS_DATA_LBL_HSTRY 설명 (D4)**: Gitea 언급 제거 (ERD-008 v2).
4. ✅ **ERD dbms (D1 일부)**: 활성 ERD 7종(001·002·003·004·007·008·009) 전부 `dbms: postgresql`. 폐기 005·006 제외.
5. ✅ **ERD 컬럼타입 정밀 (D1)** (ERD-008 2026-05-29 v4 + 6종 2026-05-30 전수확인): 활성 ERD 7종 모두 `dbms=postgresql` + PG 타입(`bigint(20)→bigint`·`datetime→timestamp`·`int(11)→integer`·`tinyint→smallint/boolean`·`longtext→text`). 6 ERD(001·002·003·004·007·009)는 2026-05-30 직접 read 로 전수 확인(ERD-007·009는 2차 신규 PG only). `legacy_dbms: MariaDB`는 1차 이력 보존.
6. ✅ **PJT_SN 정리 (D6)** (ERD-008 2026-05-29 v4): 결정=deprecated 표기. ERD-008 `LS_DATA_LBL/META/ISSUE`의 `PJT_SN` 3컬럼 `brownfield.status: deprecated` + notes 적용(삭제 안 함). PJT_SN은 ERD-008에만 존재.
7. ✅ **(선택) 구현 상태 (D7)** (2026-05-30 완료): feature 7종 implemented(FEAT-001~006 2026-05-29, FEAT-007 2026-05-30 — 코드 b04b1e8). **활성 domain_feature 27종 전부 implemented/100%**(coverage: implemented 27/40, planned 13 = 폐기 13종과 정확히 일치). domain(도메인)은 logicraft implementation tracking **비대상**(`E_NOT_TRACKABLE`) — 하위 기능에서 롤업. DFEAT-007(영상관리 modified)·021(검수 단일통합)은 권위 데이터상 활성이라 사용자 승인 후 마킹.
8. 🔶 **(선택) API/화면 모델링 (D8)** (2026-05-30 api_endpoint+screen_spec 완료): **신규 도메인 6종**(DOMAIN-011 마킹·012 비식별화·013 포털·014 시스템설정·015 작업배정·016 관제통지) + **api_endpoint 83종**(API-001~083, coverage 83/83 implemented) + **screen_spec 29종**(SCR-001~029, FE 화면 29[pages 25+auth 4], coverage 29/29 implemented, consumes_apis/implements_features 링크 정상·unresolved 0) 등록. 운영 엔드포인트·화면을 16개 도메인에 매핑, FEAT·API 링크 연결. ⏳ 잔여: **code_module(MOD)** 미반영(다음 세션) + api_endpoint 일부(포털 TUS 5·영상 stream 1) + brownfield legacy_source/ADR 보완(날조 금지로 비움).

### FEAT-007 (코드)
- 사용자 결정: **민감도(YOLO conf) + 세밀함(폴리곤 단순화 tolerance) 둘 다**. developer 에이전트로 구현 진행 중(sysconfig 2키 + 설정화면 컨트롤 + V54 시드).

## 4. 권장 실행 순서

**4-A. (먼저) DFEAT↔코드 구현 audit** — 진짜 코드 누락 유무 확정 (40 DFEAT 각각 대응 컨트롤러/서비스/엔티티 존재 확인). 누락 발견 시에만 →CODE 작업 발생.

**4-B. LogiCraft 반영** (LogiCraft 정책상 항목별 사용자 확인 후 등록):
1. 필수(현행 정확성): D2·D4(버전관리 표현) → D3(LS_LABEL_VERSION 추가) → D1(ERD PG 물리 갱신) → D6(PJT 정리)
2. 선택(완성도): D7(구현상태) → D8(API/화면 모델링)

**4-C. 양측 재대조** — 본 파일 갱신, 잔여 0 확인.

## 5. 완전 동기화 실행 계획 (확정 2026-05-29)

> 사용자 방침: "①1차에서 놓친 것 확인 → ②있으면 개발 → ③현황을 logicraft에 올림 ⇒ 누락 없이 개발+싱크 일치"
> 확정 옵션: Phase 1 = **화면/API 세부 대조**, Phase 3 = **D8 포함(완전 모델 패리티)**

### Phase 0 — FEAT-007 커밋
- FEAT-007(정밀도 조절) 코드 완료·검증(BE 982 GREEN/FE pass)·미커밋 → 커밋. (sysconfig POLYGON_SIMPLIFY_TOLERANCE + YOLO_CONF_THRESHOLD, PolygonSimplifier, V54, FE PrecisionConfigCard)

### Phase 1 — 1차 baseline 화면/API 세부 대조 (놓친 것 탐지) · 읽기전용 ✅ 완료 (2026-05-29)
- 1-1: logicraft **legacy_artifact 전량**(LEGACY-001~129: 모듈6·역할6·테이블30·API17·화면70) 조회 = 1차 원본 인벤토리
- 1-2: 1차 **화면 70 + API 17**을 현재 코드(FE 라우팅 페이지·BE 34컨트롤러·엔드포인트)와 1:1 대조. 각 항목 분류: (a)2차 폐기 결정 (b)코드 구현됨 (c)**폐기 아닌데 미구현=GAP**
- 1-3: 산출 = "놓친 1차 기능/화면/API" 목록 (DFEAT 레벨은 이미 GAP 0 — 이번엔 화면/API 세부)

**결과 — 코드 GAP 0건**:
- 화면 70건: (a)폐기 47 / (b)구현 22 / (c)GAP 0 / 확인필요 1. 폐기 분포 = 프로젝트관리 21·생성형AI 9·게시판 7·연습장 1·2차검수 구분화면 등 = 2차 스코프 축소 결정과 정합.
- API 17건: (a)폐기 10(`/api/v1/projects/**` 프로젝트 CRUD 전량 — 영상 단위로 재설계) / (b)구현 7(비디오추출·오토라벨·트래킹 인터페이스 → 내부 파이프라인+webhook 흡수) / (c)GAP 0.
- **확인필요 1건** LEGACY-097(02-05-09 "파일 업로드"): 02-05 그룹은 생성형AI(폐기)인데 09만 순수 업로드일 수 있음. **단 어느 쪽이든 non-GAP** — 순수 업로드면 2차 포털 TUS/관리화면 업로드로 구현됨, 생성형AI 워크플로면 폐기. GAP 판정 불변(원본 PDF 맥락 확인은 완성도 차원의 선택).
- 결론: 폐기 대상 아닌데 미구현인 화면/API 없음 → **Phase 2(GAP 개발) 스킵**.

### Phase 2 — GAP 개발 (있을 때만) — ⏭ 스킵 (Phase 1 결과 GAP 0)
- 미구현 1차 기능을 `/cc-tdd`로 구현 → 코드 GAP 0 확정. 없으면 스킵

### Phase 3 — LogiCraft 현황 완전 업로드 (D8 포함 = 완전 패리티) — D1·D6·D7 완료 (2026-05-30), D8 잔여
> 진행 상세·정확한 현황은 위 §3 B항(라이브 트래커)을 정본으로 본다.
- 3-1 D7: ✅ feature 7/7 implemented(FEAT-007 b04b1e8 포함) + 활성 domain_feature 27/27 implemented. 도메인은 tracking 비대상(롤업).
- 3-2 D1: ✅ 활성 ERD 7종 전부 PG 타입(6 ERD 2026-05-30 전수확인 완료).
- 3-3 D6: ✅ PJT_SN deprecated 표기 — ERD-002·003·004·007·009·008 의 PJT_SN/PJT_PATH_SN 컬럼(삭제 안 함, 1차 이력 보존). 실제 PJT_SN 분포는 ERD-008 외에도 다수 ERD에 존재함을 확인.
- 3-4 ⏳ **D8 신규 모델링(대규모)**: api_endpoint(BE 컨트롤러 35 기반)·screen_spec(FE 26 + 1차 잔존 화면)·code_module·glossary/nfr(필요시) 를 logicraft active ITEM으로 등록. domain/feature/req↔api/screen 링크 연결
- ※ logicraft 정책상 ITEM 등록은 사용자 확인 기반 — 배치 단위로 진행·보고

### Phase 4 — 최종 재대조
- 본 파일 갱신, 코드↔logicraft 양측 잔여 0 확인. 커밋

### 규모/주의
- Phase 3-4(특히 D8)는 ~60+ 신규 ITEM 등록 + 링크 = 대규모. 배치로 분할 진행.
- 폐기 결정 항목(프로젝트/게시판/생성AI/연습장/Export/2차검수/캔버스블러)은 GAP·모델링 대상에서 제외.

## 부록: 확인 근거
- LogiCraft: `get_item FEAT-002`(diff_summary "Gitea 기반"), `get_item ERD-008`(dbms mysql, legacy_dbms MariaDB, LS_DATA_LBL_HSTRY "Gitea 커밋과 함께", PJT_SN 존재, LS_LABEL_VERSION 부재), `list_items`(screen_spec/api_endpoint/code_module/glossary/nfr/adr/infra = 0)
- 코드: 네이티브 PG(Flyway V0~V53) — ls_label_version{label_payload,version_hash} 보유·gitea_cmt_hash 없음, ls_data_lbl PJT 컬럼 없음, ls_gitea_fallback_queue 0. 컨트롤러35·엔티티46·마이그레이션52·FE페이지26
