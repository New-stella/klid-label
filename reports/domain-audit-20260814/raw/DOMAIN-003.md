# DOMAIN-003 영상·프레임 수집 — 감사 결과

- 10차원 완주 (전 차원 검증 대상 있음 — SKIP 0)
- 원시 갭 **101건** — P0 18 / P1 58 / P2 25
- 차원별: coverage 11 · links 12 · schema 16 · content 12 · diagram 5 · stale 22 · policy 8 · acceptance 5 · requirement 4 · test_scenario 6

## ★ 이 도메인의 지배적 결함 — 「적재 주체 반전(ADR-042)」 cascade 미완

`ADR-016`(관제 공유 DB `MNG_*` 주기배치 스캔·픽업)이 `ADR-042`(관제 참조 전면 제거 + `LS_DATA_INGEST` 평면 수신)로 supersede 됐고
**`MNG_*` 9종은 실제 DROP 됐다.** 상위 ITEM(`DOMAIN-003`·`DFEAT-008`·`UC-018`·`CMP-010`)은 정합하나 **하위 4곳이 안 따라왔다.**

| ITEM | 상태 | 잔재 내용 | 검출 차원 |
|---|---|---|---|
| **`TEST-001`** | approved | **`SELECT ... FROM MNG_CLIP_MASTER WHERE JOB_DMND_YN='Y'`** — 삭제된 테이블 조회. objective·notes·steps 1~2 전량 구 모델. **시험 수행 자체가 불가능** | TST, STL |
| **`SEQ-001`** | approved·**stale=false** | mermaid ①적재 구간 + description 이 "공유 DB 학습용 설정 → 주기배치 픽업(1건/분)". 현행 폴링 적재 0줄 | DIAG, LINK, STL, POL |
| **`REQ-019`** | approved·**priority=must** | "공유 DB 주기배치 픽업으로 적재된 영상을…". change_summary 가 **superseded `ADR-016` 을 근거로 인용** | RQ |
| **`ERD-024`** | deprecated | description·테이블 설명이 `MNG_CLIP_MASTER` 스캔 적재를 **현재형** 서술. `brownfield.notes` 만 과거형 → 자기모순. title 에 `[폐기]` 표식 없음 | POL, STL |

> ⚠ **반대로, 잔재가 *없음*을 확인한 축** (links·schema·policy·acceptance 가 각각 독립 확인): `api_endpoint`(33건 중 표본)·`screen_spec` 활성 3건·`CMP-010`·`CDIAG-001`·AC 2건 본문·`domain_feature`/`use_case`/`erd` 의 `decided_by`. **잔재는 위 4곳에 국소화돼 있다.**

## ★ 두 번째 cascade 미완 — 「파이프라인 순서 반전」

현행 = **비식별(선두·pre-marking) → 마킹 → VLM → 프레임추출 → YOLO → SAM2 → 트랙보간**. 폐기 = "마킹 → VLM → 비식별 → 프레임추출".

| ITEM | 잔재 | 비고 |
|---|---|---|
| `ERD-020.description` | "(마킹→VLM→비식별→FFmpeg→YOLO→SAM2→보간)" | 2026-08-14 갱신됐는데 이 문장만 미정정 |
| `CMP-001.relationships` | `VLM=1단계` / `비식별=2단계` | **같은 ITEM 의 components·description 은 이미 정정** → 자기모순. v4 change_summary 가 *"나머지 3~6단계는 이미 정합이라 무변경"* 이라 **relationships 축을 안 봤음을 자인** |

## P0 (18건)

| gap | 차원 | 대상 | 내용 |
|---|---|---|---|
| D003-TST-001 | test_scenario | TEST-001, SCREEN-007 | steps 2·7 이 폐기 화면 `SCREEN-007` 을 `screen_ref` 로 검증 — 도달 불가 |
| D003-STL-004 | stale | TEST-001, ERD-024 | 삭제된 `MNG_CLIP_MASTER` 조회 SQL 을 검증 단계로 보유 |
| D003-POL-001 / D003-STL-003 | policy·stale | SEQ-001, UC-026 | 폐기 적재 모델 + **deprecated `UC-026` 를 유일 realizes** |
| D003-LINK-001 | links | SEQ-001, UC-026 | 동일 (링크 축) |
| D003-LINK-002 / D003-STL-002 | links·stale | UC-018, SCREEN-007 | 현행 적재 UC 의 `related_screens` 가 **폐기 화면 1건뿐** |
| D003-STL-001 | stale | MOD-003 | `realizes_screens` 에 deprecated `SCREEN-007`·`SCREEN-014` 잔존 |
| **D003-POL-006** | **policy** | **API-084, API-046** | **`ADR-025`(게이트 미디어 `no-store`) 위반 — 200 응답이 `private, max-age=3600`.** ADR 근거가 *"신고 직후 캐시가 최대 5분 마스킹 실패 콘텐츠 재노출"* 인데 현 계약은 **1시간**. `API-046` 은 412 만 `no-store` 라 **자기모순** |
| D003-POL-003 / D003-STL-014 | policy·stale | DFEAT-009 | "진행률·상태를 **송신시스템에 응답**" → `ADR-007`(양방향 M2M 폐기) 위반 |
| D003-POL-004 / D003-STL-013 / D003-CNT-001 | policy·stale·content | DFEAT-007 | "프로젝트에 배정" → `ADR-001` 위반. **`brownfield.diff_summary` 는 "프로젝트 배정 제거"라 적혀 자기모순** |
| D003-CNT-002 | content | ERD-020 | 폐기 파이프라인 순서 현재형 서술 |
| D003-CNT-003 | content | API-042 | **응답이 존재하지 않는 상태코드 공표** — `BATCH_PROCESSING`/`BATCH_COMPLETED`/`BATCH_FAILED`. 실제 enum 6종. **소비 화면이 필수라 명시한 `MARKING_READY` 가 계약에 없음** |
| D003-CNT-004 | content | CMP-001 | components ↔ relationships 단계 번호 자기모순 |
| D003-COV-001 | coverage | API 33건 | **전건 orphan** — DFEAT 5건의 `implemented_by_endpoints` 전부 `[]` |
| D003-COV-002 | coverage | UC-018, SEQ-001 | 활성 UC 에 happy/error path SEQ 0건 |
| D003-RQ-001 | requirement | REQ-019 | must REQ 가 폐기 적재 모델 서술 + superseded ADR 인용 |

## P1 (58건) — 축별 요약

**추적성 단절 (양방향 동시)** — `DFEAT.implemented_by_endpoints`·`persists_in_tables`·`invokes_apis`·`triggers` 전건 `[]` / `API.implements_features` 전건 `[]` / `SCREEN.realizes_use_cases` 전건 `[]` / `SEQ-001.invokes_apis`·`publishes_events`·`participants`·`messages` 전부 `[]`
→ D003-LINK-003·004·005·006·010 · D003-SCH-001·002 · D003-COV-005·006·007

**표준용어·표준도메인 위반 (schema 13건 중 핵심)**
| 위반 | 내용 |
|---|---|
| 표준약어 | `UPD_DT`→`MDFCN_DT`(같은 ERD 안에서 갈림) · `HSTRY_SEQ`→`HSTRY_SN` · `LOCK_OWNER_ID`→`LCK_OWNR_ID`(같은 테이블 다른 컬럼은 `LCK_`) · `QUE_SN`→`QUEUE_SN` |
| `PRCS` vs `PROC` | 「처리상태코드」가 `ERD-012`=`PRCS_STTS_CD`(V172 개명) / `ERD-020`=`PROC_STTS_CD`(미개명). 표준단어상 처리=`PRCS`, 프로세스=`PROC` 로 **뜻이 다름** |
| 미등록 12종 | `WTHR_NM`·`DAY_NGT_CD`·`FRM_EXPLN`·`LBL_VER`·`MAIN_SURV_PAN_ANG`·`SRC_SN`·`DATA_SRC_SN`·`JOB_TYPE_CD`·`LAST_ERR_MSG_CN`·`EVNT_NM`·`OPTR_INDCT_NM`·`EVNT_CTGRY_NM` |
| 논리용어 중복 | `PRVC_YN`(파생값)·`PRVC_INCL_YN`(사람 판정)이 **같은 논리용어 「개인정보포함여부」** 보유 |
| 임의 크기·타입 | `PRVC_TYPE_CD varchar(16)`(등록 도메인은 코드V10/V20뿐) · `VDO_LEN_SEC` 가 `integer`(RAW) vs `numeric(10)`(INGEST) **한 ERD 안에서 상충** · `EVNT_CLSF_CD`/`EVNT_CTGRY_CD` 가 `ERD-012`=char(2)/char(4) vs `ERD-025`=varchar(20) **조인 키인데 타입 상충** |

**물리명·심볼 드리프트 (실측 대조로 확정)**
- `PROC_STTS_CD` → **`PRCS_STTS_CD`** 4개 ITEM (`UC-018`·`AC-025`·`AC-026`·`ERD-012`). 근거: `schema.sql:468` + `LsDataIngest.java:225`
- `AC-025/026.notes` 의 판정 지점 **`ControlIngestPollJob` 이 실재하지 않음** (실재 `ControlTrainingVideoScanJob`)
- `CDIAG-001` 의 `DataIngest.rgnNm` 실재하지 않음 (실재 `lclgvNm`)
- `ERD-012` 메타에 폐지 물리명 `PARENT_RAW_SN`·`DURATION_SEC`·`FRAME_NO`·`NEXT_RTRY_DT`
- **`ERD-012` 에 컬럼 누락** — `schema.sql` 의 `vrfc_evnt_type_cd`(43번째)가 ERD 42컬럼에 없음

**검증 산출물 부재**
- `DFEAT-009/010/011` 3건이 **어떤 AC 로도 미검증** (DFEAT-009 의 유일 realize UC 가 deprecated)
- 마킹 이후 구간(프레임추출·전처리·자동분류) **통합시험 0건**
- `NFR-008`(수집 단계 품질기준)·`NFR-012` **시스템시험 0건** (프로젝트 전체 `kind=system` 0건)

**책임 경계 드리프트**
- `DFEAT-011` 이 "시간대·개인정보 포함 여부 자동 분류" 선언 → 도메인 용어사전 *"촬영환경은 자동 파생이 아니라 수동 입력"* · `ADR-032`(self-fill 폐기) 위반. `DFEAT-051` 은 *"규칙기반 self-fill 은 오분류가 실증되어 제거"* 라 명시
- 배치 오케스트레이션(재시도·건너뛰기·재수행) 책임 DFEAT **부재** — `CMP-001`·`EVT-002`·API 5건은 실재하는데 기능이 없음. **`CMP-001.depicts_dfeats` 가 빈 것과 같은 뿌리**(가리킬 DFEAT 가 없다)
- 라벨링 캔버스 프레임 서빙(비식별 기본·원본 제한·NOFOLLOW) 책임 DFEAT **부재**

## P2 (25건) — 주요 항목

- `stale=true` **플래그만** 잔존, 본문 정합 → `SD-004`(트리거 8시간 후 재생성됨) 등
- **본문 오염**: `CMP-010` 브랜치명 `db-fix-0804`·"미커밋 상태" / `API-158` `CLAUDE.md` 파일명·"폐지 예정(코드 정리 후속)" / `ERD-012` 마이그레이션 버전 12건 + 저장소 경로 / `EVT-002` 날짜 붙은 자기 리비전 서술 / `EVT-005`·`ERD-025` 소스 파일명
- `API-143` 어휘 표류 — "학습용 설정 **픽업**"(현행은 "폴링 적재"). 기능은 유효
- `implementation.status` 축 갈림 — DFEAT 5건은 `implemented/100`, UC·AC·ERD 는 `planned/0`
- `EVT-002`(BatchQueued) 발행자·소비자 4필드 전부 공란 → 고아 이벤트
- `API-045` 의 유일 소비 화면이 deprecated `SCREEN-014`
- `TEST-001.related_domains` 공란 (**프로젝트 레벨 — 14도메인 중복 예상, dedupe 대상**)

## ⚠ 범위 밖 발견 — 별도 보고 대상

**`TEST-001.steps[3].note` 에 외부 연동 서버 IP·포트 하드코딩**
```
실 KPST 연동(base-url: dev/local http://222.118.130.251:9989)
```
설계 산출물 본문에 환경 고유 접속정보가 노출된 상태. content 오염이자 정보 노출 축.

## 감사 신뢰도 — auditor 간 상충 1건 (메인이 판정)

`D003-RQ-003` 의 근거 중 *"`AC-025` 는 `given`/`when`/`then` 이 전부 비어 있다(`data={}`)"* → **기각.**
requirement auditor 가 `fields=[given,when,then,...]` 로 부분 read 했으나 실제 필드명은 **`scenario.then`** 이라 빈 객체가 반환된 것이다.
acceptance auditor 는 같은 ITEM 의 `AC-025.scenario.then[0]` 을 **정상 인용**했다.
→ **`acceptance_criteria` 인라인 필드가 비었다는 나머지 절반만 유효.**
> 교훈: **부분 read 의 빈 결과를 "필드 부재"로 단정하면 안 된다.**

## 각 auditor 가 명시한 미확인 층

- **정적 렌더 미러**(`source_hash` 대조) — 전 차원 미확인
- `api_endpoint` 33건 중 본문 정독은 6~12건 — 나머지는 메타만 확인 (중첩 `responses[].description` 갭이 더 있을 수 있음)
- `SCREEN-009` sections 본문(v44)·`SCREEN-014`(폐기) 미조회
- ERD 3종의 entities 전수 ↔ 실제 DDL 대조 미수행 (단 `schema.sql` 부분 grep 은 수행)
- 1차 소스 grep — `legacy_grep_enabled=false` 로 전 차원 미수행
- policy 차원: ADR 원문을 직접 연 것은 `ADR-025`·`ADR-032`·`ADR-039` 3건, 나머지는 메인 제공 요약 기준
