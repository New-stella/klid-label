# D-8. 데이터마트 View (TC-MARTVIEW-001~023) — 3차 검증 (D-part5)

검증일: 2026-08-04(스택 기동은 2026-08-03 3차 §3-1/§3-3 산출물 재사용) · 담당: D-8 데이터마트 View 23건
방법: `docker exec klid-postgres psql`로 4종 View(V_COMPLETED_VIDEO/FRAME/LABEL_CHANGE/META)를 실제 SELECT.
공용 정상데이터 rawSn=101(APPROVED, export SUCCEEDED) 재사용 + 기존 DB에 이미 존재하던 반증용 실데이터(rawSn=94 PARTIAL export 이력, rawSn=26/5 ACTIVE_YN 혼재, rawSn=18 해상도파생, rawSn=15/8/9 비식별신고 APPROVED)를 최대한 활용. 부족한 반증 케이스(ACTIVE_YN=N 단독, video.codec 노출, 동일경로 프레임, 신고구간 export 보존, 레거시 파생 환경값)는 `BEGIN...ROLLBACK` 트랜잭션으로 격리된 시드 데이터를 삽입해 실제 뷰로 SELECT 후 즉시 롤백(DB에 영구 흔적 없음, 병렬 실행 중인 다른 파트 에이전트 데이터와 충돌 없음). 빌드/테스트 미실행, 결과 파일 외 수정 없음(단 카탈로그 정정 3건은 지시에 따라 담당 라인범위 235~268행 내에서 Edit 수행).

## 판정 요약

| 판정 | 건수 | TC-ID |
|---|--:|---|
| PASS | 23 | 001~023 전건 |
| FAIL/PARTIAL/BLOCKED/확인필요 | 0 | — |

**전건 PASS.** 단 TC-MARTVIEW-004는 카탈로그의 기존 기대값("SUCCEEDED만 조인, PARTIAL 제외")이 2026-08-02 E-ISSUE-81 수정(V160)으로 이미 폐기된 정책이라 **근거 드리프트로 판정하고 카탈로그를 실제 동작(PARTIAL 포함)에 맞춰 정정**했다 — 시스템 결함이 아니라 문서가 낡았던 것.

## 결과표

| ID | 판정 | 근거 확인 | 비고 |
|---|---|---|---|
| TC-MARTVIEW-001 | PASS | [실동작] `SELECT * FROM v_completed_video WHERE raw_sn=101` → 1행, APPROVED 확인. 뷰 전체 16행 = INNER JOIN(APPROVED) 결과와 일치 | |
| TC-MARTVIEW-002 | PASS | [실동작] rawSn=101 `export_path_nm=/app/storage/raw/seed/101, frame_cnt=20`(SUCCEEDED) · rawSn=94 최신버전(v6, PARTIAL) `frame_cnt=10` 노출 | 근거 인용을 V160으로 정정(카탈로그 수정) |
| TC-MARTVIEW-003 | PASS | [실동작] rawSn 15/8/9(export 0건) `export_path_nm`/`frame_cnt` 둘 다 NULL, 전체 뷰 `count(*)=count(distinct raw_sn)=16` | |
| TC-MARTVIEW-004 | PASS(카탈로그 정정) | [실동작] rawSn=94 export 이력 v1 SUCCEEDED→v2 PARTIAL→v3 FAILED→v4 PARTIAL→v5 FAILED→v6 PARTIAL 중 뷰는 최신 v6(PARTIAL)을 그대로 노출(FAILED는 skip). `pg_get_viewdef('v_completed_video')`가 V160 정의(`EXPORT_STTS_CD IN ('SUCCEEDED','PARTIAL')`)와 100% 일치 | **카탈로그 드리프트**: 구 기대값 "SUCCEEDED만 조인"은 2026-08-02 E-ISSUE-81 수정으로 폐기됨(2차 targeted-B-DE.md에서 Testcontainers로 이미 확인된 사실, 실 DB에도 반영 확인). 담당 범위 내 Edit로 정정 완료 |
| TC-MARTVIEW-005 | PASS | [실동작] rawSn=26(ACTIVE_YN N×2+Y×1, APPROVED)은 Y스냅샷 값(evnt_nm=쓰러짐)만 노출 · BEGIN/ROLLBACK 시드(ACTIVE_YN=N만 있는 신규 APPROVED raw)로 `count(*)=0` 확인 | |
| TC-MARTVIEW-006 | PASS | [실동작] 현재 DB에 원본=비식별 동일경로 결함행 0건(healthy) · BEGIN/ROLLBACK 시드로 동일경로 행 삽입 후 `v_completed_frame` count=0(게이트 작동) · rawSn=18 src_sn=45(한쪽만 NULL, 결측)는 정상 노출되어 "결측은 통과" 규칙도 확인 | |
| TC-MARTVIEW-007 | PASS | [정적+실동작] V133:47-50 DESCRIPTION 컬럼 확인, `v_completed_frame` SELECT 결과에 description 컬럼 존재(값은 비어있는 프레임도 노출) | |
| TC-MARTVIEW-008 | PASS | [실동작] `v_completed_label_change` 컬럼 = lbl_hstry_sn/raw_sn/src_sn/add_cnt/mdfcn_cnt/del_cnt/reg_id/reg_dt 8개뿐, CHG_DTL_CN 미노출 확인 | |
| TC-MARTVIEW-009 | PASS | [정적] V139:38-43 `EXISTS (... DATA_STTS_CD='APPROVED')` 확인 | |
| TC-MARTVIEW-010 | PASS | [정적+실동작] V107:124-146 정확 일치(라인 드리프트 없음). rawSn=101 `v_completed_meta`에 `mrev.rvw_stts_cd='APPROVED'`인 "0-5" 메타만 노출 | |
| TC-MARTVIEW-011 | PASS | [정적+실동작] V107:140 `NOT LIKE 'video.%'` 정확 일치. rawSn=101 raw `ls_data_meta`에는 video.fps/codec/duration_ms/filesize/resolution/bit_rate 6종이 있으나 `v_completed_meta`에는 0건 노출. BEGIN/ROLLBACK 시드(video.codec+APPROVED review)로도 재확인 count=0 | |
| TC-MARTVIEW-012 | PASS | [실동작] `\dv`로 뷰 목록 = v_completed_frame/label_change/meta/video 4개뿐. `SELECT * FROM v_completed_label` → `relation does not exist` 에러 확인 | |
| TC-MARTVIEW-013 | PASS | [정적+실동작] V138:28-31·V139:12-15·V160:35-37 모두 "REPLACE, 끝 추가만" 명시. 3차 stack-bringup에서 V159~V163 5건 Flyway 연속 적용 성공(에러 0) + `\d v_completed_video` 컬럼 순서/타입이 V160 정의와 정확히 일치 | |
| TC-MARTVIEW-014 | PASS | [정적] `DatasetMaterializeApproveRollbackIT.java` 159줄 정확 일치, `materializeFailure_rollsBackApproveAcrossAllTables` 테스트가 LsRawDataStatus(IN_REVIEW 원복)·LS_LABEL_VERSION(0건)·LS_DATASET_VIDEO_META(미적재)·LS_META_REPL_OUTBOX(0건) 4테이블 전부 단언 | |
| TC-MARTVIEW-015 | PASS | [실동작] rawSn=101 `de_idntf_file_path_nm=/app/storage/raw/seed/101/deid/clip-9101-mask.mp4` — pipeline-drive.md의 파일시스템 실측(`101/deid/clip-9101-mask.mp4`)과 정확히 일치, mock 파일명 규칙(`{stem}-mask{ext}`)도 그대로 반영(문자열 조합 아님, procLog 원문) | |
| TC-MARTVIEW-016 | PASS | [실동작] rawSn=18(orgnl_raw_sn=4) `original_video_path IS NULL` 확인 | 근거 라인(123-132,158→129-138,164) 드리프트 정정 완료 |
| TC-MARTVIEW-017 | PASS | [실동작] `ls_data_lbl_hstry`에 0/0/0 델타 행 다수 실존(lbl_hstry_sn 34/35/36/52/53 등, 롤백/개인정보리셋 감사 흔적) — 전부 `v_completed_label_change`에서 제외(JOIN count=0). 반면 실제 델타(add_cnt=1 또는 mdfcn_cnt=1)가 있는 rawSn=101의 두 행(119/156)은 정상 노출 | |
| TC-MARTVIEW-018 | PASS | [실동작] rawSn 15/8/9(APPROVED, DE_IDNTF_YN='F')가 뷰에서 행 자체는 사라지지 않음(전건 존재) 확인. 추가로 BEGIN/ROLLBACK 시드(F 상태 + 사전 SUCCEEDED export/deident 존재)로 `export_path_nm`/`frame_cnt`/`de_idntf_file_path_nm`이 신고 중에도 NULL로 비워지지 않고 그대로 노출됨을 강하게 실증(뷰 WHERE절에 DE_IDNTF_YN 필터 자체가 없음도 정적 확인) | |
| TC-MARTVIEW-019 | PASS | [실동작] rawSn=101(수동 입력 없음) `day_ngt_cd`/`sesn_cd`/`wthr_nm` 전부 NULL(sht_dt는 채워져 있음에도 NGT/SUMMER 규칙 파생 없음) — self-fill 부재 확인 | |
| TC-MARTVIEW-020 | PASS | [정적+실동작] `ENV_CORRECTION_PREDICATE`(라이브 raw NULL + 활성 스냅샷 non-null) 정확 확인, 현재 DB엔 해당 백로그 0건(healthy). BEGIN/ROLLBACK 시드(라이브 NULL + 스냅샷 NGT/SUMMER)로 예측식이 정확히 픽업함을 실증. `DatasetVideoMetaEnvCorrectionTx.correct()`(68-83줄, 인용 라인 정확) 로직도 확인 | |
| TC-MARTVIEW-021 | PASS | [정적] `@Profile("!prd")`(prd 미등록) + `@PreAuthorize("hasRole('REVIEWER')")`(GET/POST 양쪽) + 별도 sub-resource(쿼리파라미터 분기 없음) 확인. `BatchDevTriggerController`와 동일 3중 방어 패턴 재사용 | prd 프로파일 자체를 로컬에서 기동해 404를 실측하진 못함(BLOCKED 아님 — 동일 패턴이 프로젝트 전역에서 이미 검증된 기존 관례) |
| TC-MARTVIEW-022 | PASS | [정적] `correctDerivedShootingEnvironment()` — `envCorrectionMaxPerRun` 상한 + `ENV_CORRECTION_BATCH_SIZE` 페이징 + 시작/진행/완료 로그 + 잔여는 다음 실행에서 자연 이어짐(멱등) 확인 | |
| TC-MARTVIEW-023 | PASS | [실동작] BEGIN/ROLLBACK 시드 2건(① ACTIVE_YN=N 단독 APPROVED raw, ② video.codec + APPROVED review)으로 각각 `v_completed_video`/`v_completed_meta` count=0 확인 | |

## 이전 회차 이슈 해소 여부 대조

- **E-ISSUE-81 / D-ISSUE-61 계열 (PARTIAL export 뷰 배제, HIGH)**: 2차(2026-08-02) `targeted-B-DE.md`가 Testcontainers(`DatamartViewSlimIT`)로 "PASS(컨테이너 실동작 BLOCKED)"로 잠정 확인했던 건을, 이번 3차에서 **실제 운영 컨테이너 DB(`klid-postgres`)에 V160이 실제로 적용된 상태로 재확인** — rawSn=94의 실제 PARTIAL export 이력이 뷰에 정확히 반영됨을 라이브 데이터로 실증. **완전 해소 확인** (BLOCKED → 실동작 PASS로 격상).
- 1차(2026-08-01) ISSUES.md에는 D-8/MARTVIEW 관련 이슈가 등록된 바 없음(grep 결과 0건) — 이번 회차가 D-8 최초 전수 실동작 검증.
- 신규 결함 없음. 카탈로그 드리프트만 3건 발견·정정(TC-MARTVIEW-002/003/004 근거 인용을 V138→V160으로, TC-MARTVIEW-016 라인 번호 123-132,158→129-138,164, TC-MARTVIEW-013에 V160 근거 보강).

## 참고 — 반증용 임시 시드 방법론

모든 반증 데이터는 `docker exec -i klid-postgres psql ... <<'SQL' BEGIN; ... SELECT ...; ROLLBACK; SQL` 패턴으로 삽입 직후 즉시 롤백했다. 이는 프로덕션 코드·설정·영구 데이터를 전혀 건드리지 않으면서(§10 절대규칙 준수) 실제 뷰 정의(정적 재구현이 아닌 진짜 `V_COMPLETED_*` 뷰)를 SELECT해 게이트가 실제로 작동하는지 검증하는 방식이다. 트랜잭션 종료 후 `ROLLBACK` 확인 메시지로 데이터 잔존 없음을 매번 확인했다.
