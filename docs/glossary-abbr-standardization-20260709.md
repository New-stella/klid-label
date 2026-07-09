# 용어 약어 표준화 변경 리스트 (2026-07-09)

> 목적: 폐쇄망 산출물(용어정의서·DB설계서·인터페이스정의서 등) 수정용 참조.
> 범위: 프로그램 용어사전 약어 표준화(31건) + 이 저장소(KLID-저작도구) 물리 컬럼 rename(6건) + LogiCraft ERD 동기화(4건).

---

## ① 용어정의서 / 표준단어 문서 — 약어 변경 31건

> `term_en_abbr`(논리 표준 약어) 변경. 한글 논리명·의미는 불변.

### gov(행안부) 표준단어 정합 (11 용어)
| 한글 용어 | 구 약어 | 신 약어 | 표준 근거 |
|---|---|---|---|
| 수신대역폭bps | RCV_BNW_BPS | RCPTN_BNW_BPS | 수신=RCPTN |
| 수신메타건수 | RCV_META_NOCS | RCPTN_META_NOCS | 수신=RCPTN |
| 수신자산건수 | RCV_ASST_NOCS | RCPTN_ASST_NOCS | 수신=RCPTN |
| 본체수신일시 | BODY_RCV_DT | BODY_RCPTN_DT | 수신=RCPTN |
| 업로드일시 | UPLD_DT | ULD_DT | 업로드=ULD |
| 승인완료일시 | APRV_CMPL_DT | APRV_CMPTN_DT | 완료=CMPTN |
| 요청페이로드 | RQST_PYLD | DMND_PYLD | 요청=DMND |
| 요청아이디 | RQST_ID | DMND_ID | 요청=DMND |
| 요청아이피 | RQST_IP | DMND_IP | 요청=DMND |
| 시간버킷종류 | TIME_BCKT_KIND | TIME_BCKT_KND | 종류=KND |
| 통계이벤트키 | STAT_EVNT_KEY | STATS_EVNT_KEY | 통계=STATS |

### 자체표준(gov 미등재, 로안워드/도메인어) (9 용어)
| 한글 용어 | 구 약어 | 신 약어 | 표준 근거 |
|---|---|---|---|
| 사용교재상용서면여부 | TXTBK_COMM_DOC_YN | TXTBK_CMCL_DOC_YN | 상용=CMCL |
| 사용교재상용동영상여부 | TXTBK_COMM_VIDEO_YN | TXTBK_CMCL_VIDEO_YN | 상용=CMCL |
| 사용교재상용온라인강좌여부 | TXTBK_COMM_ONLINE_YN | TXTBK_CMCL_ONLINE_YN | 상용=CMCL |
| 스냅샷아이디 | SNPSHT_ID | SNPSH_ID | 스냅샷=SNPSH |
| 실행전스냅샷 | EXCN_BFR_SNPSHT | EXCN_BFR_SNPSH | 스냅샷=SNPSH |
| 실행후스냅샷 | EXCN_AFT_SNPSHT | EXCN_AFT_SNPSH | 스냅샷=SNPSH |
| 코덱 | CODEC | CDC | 코덱=CDC |
| 최종푸시시도일시 | LAST_PUSH_TRY_DT | LAST_PUSH_ATMPT_DT | 시도(試圖)=ATMPT |
| 조회시도일시 | FETCH_TRY_DT | FETCH_ATMPT_DT | 시도(試圖)=ATMPT |

### 전수감사 A그룹 정합 (11 용어)
| 한글 용어 | 구 약어 | 신 약어 | 표준 근거 |
|---|---|---|---|
| 라벨코드 | CODE | LBL_CD | 라벨=LBL, 코드=CD |
| 응답코드 | RESP_STATUS | RESP_CD | 코드=CD |
| 코드일련번호 | CODE_SN | CD_SN | 코드=CD |
| 목표해상도코드 | TARGET_RES_CD | GOAL_RES_CD | 목표=GOAL |
| 이벤트발생일시 | EVNT_OCCUR_DT | EVNT_OCRC_DT | 발생=OCRC |
| 발행일시 | PUB_DT | PBLCN_DT | 발행=PBLCN |
| 발행상태 | PUB_STTS_CD | PBLCN_STTS_CD | 발행=PBLCN |
| 액세스만료일시 | ACS_EXPRY_DT | ACS_EXPD_DT | 만료=EXPD |
| 리프레시만료일시 | RFSH_EXPRY_DT | RFSH_EXPD_DT | 만료=EXPD |
| 응답일시 | RES_DT | RESP_DT | 응답=RESP |
| 응답건수 | RESP_CNT | RESP_NOCS | 건수=NOCS |

---

## ② DB 설계서 / ERD 문서 — 물리 컬럼 rename 6개 (저작도구 소스 실반영)

> 위 31건 중 **이 저장소(저작도구)에 실재하는 컬럼만** 물리 rename. Flyway `V80` 적용. **타입·사이즈 변경 없음**.

| 테이블 | 구 물리 컬럼 | 신 물리 컬럼 | 타입 | 비고 |
|---|---|---|---|---|
| LS_LABEL_PRESET_CODE | CODE_SN | CD_SN | BIGINT(PK) | 코드일련번호 |
| LS_LABEL_PRESET_CODE | CODE | LBL_CD | VARCHAR(32) | UK(PRESET_ID, LBL_CD) |
| LS_RESOLUTION_EXPORT | TARGET_RES_CD | GOAL_RES_CD | VARCHAR(16) | UK(DATA_RAW_SN, GOAL_RES_CD) |
| LS_NOTICE | PUB_STTS_CD | PBLCN_STTS_CD | VARCHAR(16) | IDX_LNT_PUB |
| LS_NOTICE | PUB_DT | PBLCN_DT | TIMESTAMP | |
| LS_DEIDENT_PROC_LOG | RES_DT | RESP_DT | TIMESTAMP | |

- 반영 위치: `backend/src/main/resources/db/migration/V80__rename_glossary_std_columns.sql`(ALTER RENAME ×6) + JPA 엔티티 4종 `@Column`.
- 커밋: `cf0c0b9 refactor(db): 용어사전 표준 약어 정합 — LS_* 물리 컬럼 6종 rename (V80)`.
- LogiCraft ERD 동기화 완료: ERD-019(라벨프리셋), ERD-012(해상도), ERD-022(공지), ERD-017(비식별로그).

---

## ③ 문서 수정 시 주의사항 (Critical)

1. **물리 컬럼명만 변경, API JSON 키·화면 필드명은 불변** — 인터페이스/화면 정의서의 응답 필드(JSON 키: `pubDt`, `pubStatus`, `code` 등)는 **그대로 유지**. 물리 컬럼명만 갱신.
2. **타입·사이즈 불변** — 사전값과 이미 일치. 문서의 타입/길이 컬럼 수정 불필요.
3. **①의 31건 vs ②의 6건 구분** — ①은 논리 표준약어(용어정의서 전체), ②는 이 저장소에서 물리 rename까지 완료된 것. 나머지 25건은 **KLID-2(중계서버)·KLID-PT(포털) 소관** — 각 시스템 문서/소스는 별도 반영.
4. **미대상 2건** — `codec`(DB 컬럼 아님), `occurred_at`(영어 물리명·PJT 레거시)은 rename 제외.
5. **혼용 잔존(의도)** — 통계(STAT_UNIT/STAT_YMD/STAT_ID)·완료(CMPT_DT 등) 초기 import(2026-05-28 배포) 용어는 프리즈로 **구약어 유지** → 문서에 STAT↔STATS·CMPT↔CMPTN 혼재. 정합 대상 아님.

---

## ④ 후속 정비 후보 (이번 미반영, 참고)

- 나머지 25 term(KLID-2 중계 / KLID-PT 포털) 소스·ERD: 각 프로젝트 소관.
- 사전 ERD 인덱스 드리프트: ERD-022 `IDX_LNT_PUB`의 `PIN_YN`(컬럼은 `UPEND_FIX_YN`), ERD-012 `UK_LS_DATA_SRC`의 `FRAME_NO`(컬럼은 `FRM_NO`).
- B그룹(값=VALUE↔VL, 속성=ATRB↔ATTR, 자산=ASST↔AST) 통일 방향 결정 후 일괄.
- 단어사전 결함: 단어 '식별'=`Identification`(약어 아닌 전체단어), '재시도'=`RTY`(gov `RTRY`).
