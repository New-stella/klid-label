-- =============================================================================
-- V174: V_COMPLETED_VIDEO 재작성 — 38 → 30컬럼 (@req R5 뷰 계약 · @req R6 별칭 표준화)
--
-- 정본: docs/관제-저작도구-데이터연동-규격서-20260805.md §5-1 (30컬럼의 이름·순서·타입·NULL 여부)
--       내부 기준: docs/관제연동-저작도구-개발항목-20260805.md §2-1~2-3 · §6-1
--
-- 목적:
--   관제지원시스템이 통지를 받고 이 뷰를 <SELECT 1회> 해서 datasets(16컬럼)·dataset_versions(21컬럼)를
--   채우고 산출물을 픽업할 수 있게 한다. 현행 38컬럼은 ①관제 적재 대상이 아닌 값(CCTV명·좌표·코덱·
--   해상도 등 26개)을 싣고 ②정작 관제가 필요한 값(이벤트 분류/카테고리·지자체명·생성형AI여부·
--   개인정보 3필드·데이터셋명·구축년도/용량·라벨유형/형식 등 18개)이 없었다.
--
-- ★ 이 마이그레이션이 <관제 연동 계약면(뷰 출력명)을 실제로 바꾸는> 변경이다.
--   V172/V173 의 원장 rename 은 계약면을 건드리지 않았다 — PostgreSQL 은 RENAME COLUMN 시 뷰 <본문>만
--   추종하고 출력 컬럼명은 자동 별칭으로 보존하기 때문이다(e.OUTPUT_PATH_NM AS export_path_nm).
--   여기서 처음으로 출력명이 바뀐다. 안전한 근거는 셋이다:
--     ① 이 뷰를 SELECT 하는 <프로덕션 코드가 0건>이다(소비자는 외부 관제뿐, 코드 참조는 전부 javadoc).
--     ② 관제와 <아직 연동 전>이다(완료 통지 토글 off — 지금 켜면 페이로드 필드 부족으로 전량 422).
--     ③ 30컬럼은 이미 규격서로 <전달·확정>된 계약이다.
--   회귀 가드: DatamartViewRebuildIT(30컬럼 이름·순서 + 구 컬럼 부재) ·
--             V174CompletedVideoViewContractIT(값 계약) · V173...IT(뷰 출력명 축 갱신)
--
-- ★ CREATE OR REPLACE 가 아니라 DROP + CREATE 인 이유
--   OR REPLACE 는 <기존 컬럼 이름·순서·타입 보존 + 맨 끝 추가>만 허용한다. 이번엔 26개 제거와
--   6건 이름 변경이 있어 REPLACE 로는 불가능하다.
--   ⚠ CASCADE 는 쓰지 않는다 — 이 뷰에 의존하는 객체가 있으면 <조용히 함께 지워지는> 대신
--     마이그레이션이 실패해서 드러나야 한다. (사전 조사: 다른 3개 마트 뷰
--     V_COMPLETED_FRAME·V_COMPLETED_LABEL_CHANGE·V_COMPLETED_META 는 모두 기저 테이블을 직접
--     SELECT 하며 이 뷰를 참조하지 않는다.)
--
-- ────────────────────────────────────────────────────────────────────────────
-- 구성 (검산: 38 − 26 = 12 유지 · 12 + 18 신규 = 30)
--
-- [유지 12]  RAW_SN · ORGNL_RAW_SN · EVNT_TYPE_CD · LCLGV_CD · DE_IDNTF_YN ·
--            DE_IDNTF_FILE_PATH_NM + 별칭 정정 6건(@req R6)
--              DURATION_SEC        → VDO_LEN_SEC        (영상길이초)
--              FRAME_CNT           → FRME_CNT           (프레임=FRME 표준약어. FRM 은 표준에서 형식)
--              REVIEW_COMPLETED_AT → RVW_CMPTN_DT       (검토=RVW·완료=CMPTN·일시=DT)
--              ORIGINAL_VIDEO_PATH → ORGNL_VDO_PATH_NM  (원본=ORGNL·영상=VDO·경로명=PATH_NM)
--              EXPORT_PATH_NM      → OUTPUT_PATH_NM     (EXPORT 표준 미등록 → 산출물=OUTPUT, V173 원장과 동일)
--              EXPORT_STTS_CD      → OUTPUT_STTS_CD
--
-- [제거 26]  VMS_CLIP_ID · VMS_CCTV_ID · CCTV_NM · WGS84_LAT · WGS84_LOT · SIDO_NM · SGG_NM ·
--            FILE_FMT · VDO_CDC · FPS · BIT_RT · ASPRT_RT · RESL · VDO_WDTH · VDO_HGT · FILE_SZ ·
--            DAY_NGT_CD · SESN_CD · WTHR_NM · EVNT_NM · CAPTURED_AT · PRVC_TYPE_CD · PRVC_YN ·
--            BATCH_STTS_CD · REVIEW_STTS_CD · REVIEW_VERSION
--            제거 기준 = 관제 적재 대상이 없다 + 대부분 <관제가 인입으로 보낸 값>이라 되돌려줄 실익이
--            없다. 상태 3종(BATCH/REVIEW_STTS_CD·REVIEW_VERSION)은 뷰 자체가 APPROVED 게이트라
--            상수이거나 저작도구 내부 상태다.
--            ⚠ DE_IDNTF_YN 은 <제거 금지> — "비식별 누락 신고 구간에도 관제가 자체 판단할 수 있게
--              노출한다"는 확정 정책(CLAUDE.md 데이터마트 View 절)에 걸려 있다.
--
-- [신규 18]  EVNT_CLSF_CD · EVNT_CTGRY_CD · LCLGV_NM · GEN_AI_YN · DATST_NM · DATST_EXPLN ·
--            IMG_YN · VDO_YN · ANONY_INCL_YN · PSDO_INCL_YN · PRVC_INCL_YN ·
--            SRC_ANONY_INCL_YN · SRC_PSDO_INCL_YN · SRC_PRVC_INCL_YN ·
--            DATA_ETBL_YR · DATA_ETBL_CPCT · LBL_TYPE · LBL_FMT
--
-- ────────────────────────────────────────────────────────────────────────────
-- ★ 설계결정 D1 — 인입 신규 노출값은 동결 스냅샷에 넣지 않고 <뷰에서 LATERAL 조인>한다
--
--   EVNT_CLSF_CD·EVNT_CTGRY_CD·LCLGV_NM·SRC_*_INCL_YN 3종은 LS_DATASET_VIDEO_META 에 컬럼을
--   만들지 않는다.
--     · 인입(LS_DATA_INGEST)은 <관제가 보낸 수신 원장이라 불변>이다. 저작도구가 쓰는 통로는 내부
--       업로드 1곳뿐이고 LsDataIngestWriteGuardTest 가 기계적으로 고정한다 → 동결과 라이브 조인의
--       결과가 같다.
--     · TrainingVideoIngestTx javadoc 이 "관제 읽기전용 사실을 가변 마스터에 복사하지 않는다,
--       조회 시 조인" 을 확정 설계로 못박고 있다.
--     · 스냅샷에 넣으면 DatasetMetaSourceRepository + DatasetVideoMetaSnapshotService(freeze/hash/
--       payload) + LabelContentHasher 를 세트로 고쳐야 하고 기존 동결 행 백필 문제까지 생긴다.
--
--   조인 규칙은 앱의 단일 진실원 IngestSourceLink.SQL_LATERAL_JOIN 과 <같은 규칙>이다:
--       i.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)  +  ORDER BY RCPTN_SN DESC LIMIT 1
--   파생영상(증강·해상도)은 자기 인입 행이 없으나 파생 깊이가 1 로 고정돼 있어(CLAUDE.md 구속 정책)
--   1단계 폴백이면 충분하다 — <재귀 순회를 만들지 말 것>.
--   ⚠ 앱 상수(자바)와 이 뷰(SQL)는 표현이 둘이라 <규칙이 갈릴 수 있다>. 한쪽만 바꾸면
--     V174CompletedVideoViewContractIT.뷰_인입조인은_IngestSourceLink_규칙과_동일하다 가 깨진다.
--
--   ⚠ 조인 조건이 두 갈래다 — 한 조인 + CASE 로 처리한다.
--     · EVNT_CLSF_CD·EVNT_CTGRY_CD·LCLGV_NM : 파생에서도 <부모 값이 유효>하므로 그대로 노출.
--     · SRC_*_INCL_YN 3종           : 파생은 <NULL 이어야 한다>(규격서 §5-2). 파생본은 부모의
--       비식별 영상을 복사해 만든 것이라 대응하는 <원천 영상이 존재하지 않는다>. 부모의 원천 판정을
--       끌어오면 "비식별 전 영상"에 대한 판정을 비식별본 파생에 붙이는 셈이라 사실이 아니다.
--       (V166 마이그레이션 주석이 이 CASE 식을 이미 예고해 뒀다.)
--
-- ★ 설계결정 D2 — GEN_AI_YN 은 동결 m.AI_CRT_YN 이 아니라 r.SRC_TYPE 에서 <직접 도출>한다
--   m.AI_CRT_YN 은 도출식 결함(ORGNL_RAW_SN != null 로만 계산)으로 <기존 행에 잘못된 값이 남아 있다>
--   — SRC_TYPE='GENERATED' 인 관제 인입 원본이 'N' 으로 오동결됐다. 뷰가 그 값을 읽으면 백필이
--   필요해진다. SRC_TYPE 은 적재 후 불변이고 r 은 이미 INNER JOIN 돼 있어 조인 추가도 없다.
--   (AI_CRT_YN 컬럼 자체의 정정은 별도 Phase 소관 — 이 마이그레이션은 손대지 않는다.)
--   판정식은 완료 통지 페이로드 gen_ai_yn 과 <같은 규칙>이어야 한다: SRC_TYPE IN ('GENERATED','AUGMENTED').
--     GENERATED = 관제가 AI 생성물로 인입한 원본 / AUGMENTED = 저작도구 증강·해상도 파생.
--
-- ★ 개인정보 3필드(비식별 축)의 프리필 상수는 <ExportPrivacyPolicy.DEID_DEFAULT_* 와 동기화 대상>이다.
--   Y / N / N (익명 / 가명 / 개인정보). SQL 이라 자바 상수를 참조할 수 없어 <값을 맞춰 적는다>.
--   이 저장소는 상수를 복제했다가 화면과 export 산출물이 어긋난 사고 이력이 있다(2026-08-03).
--   한쪽을 바꾸면 반드시 다른 쪽도 바꾼다 — 회귀 가드:
--   V174CompletedVideoViewContractIT.개인정보_기본값은_ExportPrivacyPolicy_상수와_같다
--   NULLIF(BTRIM(...)) 로 감싼 이유는 그 정책의 normalize 와 동일하게 <공백=미입력>으로 보기 위해서다
--   (CHAR(1) 은 빈 문자열이 공백 1자로 패딩된다).
--
-- ★ FRME_CNT 는 미산출 시 0 이다(NULL 아님). 규격서 §5-1 이 이 컬럼을 NOT NULL 로 공표했고
--   관제 datasets.img_nocs · dataset_versions.data_etbl_nocs 로 그대로 들어간다. 산출이 없으면
--   프레임도 0 장이므로 0 이 사실이며 IMG_YN='N' 과도 정합한다. 반면 DATA_ETBL_CPCT 는 규격서가
--   "미산출 시 NULL" 로 공표했으므로 <COALESCE 하지 않는다>(0 바이트와 미산출은 다르다).
--
-- ★ 신고 구간(DE_IDNTF_YN='F') 필터를 <넣지 않는다>. "검수 완료·통지 건에 대한 관제 접근은 무조건
--   보장한다"가 확정 정책이며(CLAUDE.md), 관제가 보던 행이 예고 없이 사라지거나 경로가 비워지면
--   관제 배치가 삭제·오류로 오인한다. 대신 DE_IDNTF_YN 을 그대로 노출해 관제가 자체 판단하게 둔다.
--
-- 표준용어/표준도메인 (정본 CSV 대조 — 표준용어/산업용어/사업표준{단어,용어}.csv ·
--   표준용어/공공 표준용어/공통표준{단어,도메인}.csv. MCP 검색은 200건 캡이라 정본이 아니다):
--   신규 <물리 테이블/컬럼> 생성은 없다 — 뷰 출력 별칭뿐이고, 원천 컬럼이 있는 값은 <별칭을 두지
--   않고 물리명을 그대로 승계>한다(V138/V160 방침).
--
--   ★ 표준 우선순위 = ① 행안부 공통표준 → ② 사업표준 (2026-08-05 사용자 확정).
--     아래 분류는 이 순서로 판정한 결과다. 공통표준에 등록이 있으면 그것을 쓰고, 없을 때만 사업표준을
--     쓴다. (이번 라운드에서 이 순서로 재판정해 바뀐 항목: DATST_NM/DATST_EXPLN 길이 → 명V200.
--     IMG_YN 은 공통표준용어 '이미지여부'(여부C1) 등록분과 이미 일치한다.)
--
--   [공통표준에 등록이 없어 사업표준<용어>를 쓰는 것]
--     생성형AI여부 GEN_AI_YN · 데이터구축년도 DATA_ETBL_YR · 데이터구축용량 DATA_ETBL_CPCT ·
--     라벨유형 LBL_TYPE · 라벨형식 LBL_FMT · 영상길이초 VDO_LEN_SEC · 이벤트분류코드 EVNT_CLSF_CD ·
--     이벤트카테고리코드 EVNT_CTGRY_CD · 지방자치단체명 LCLGV_NM · 개인정보포함여부 PRVC_INCL_YN ·
--     비식별파일경로명 DE_IDNTF_FILE_PATH_NM
--     ※ 라벨형식은 <단어> 축으로는 형식=FRM 이지만 <용어> 축에 LBL_FMT 로 등록돼 있고 관제
--       dataset_versions.lbl_fmt 와도 일치한다 — 용어 등록분이 우선한다.
--
--   [용어 미등록 — 표준<단어> 조합으로 구성]
--     이미지여부 IMG_YN(이미지 IMG + 여부 YN) · 영상여부 VDO_YN(영상 VDO + 여부 YN) ·
--     익명정보포함여부 ANONY_INCL_YN · 가명정보포함여부 PSDO_INCL_YN(가명 PSDO + 포함 INCL + 여부 YN) ·
--     출처 접두 SRC_*(출처 SRC) · 산출물경로명 OUTPUT_PATH_NM · 산출물상태코드 OUTPUT_STTS_CD ·
--     원본영상경로명 ORGNL_VDO_PATH_NM(원본 ORGNL + 영상 VDO + 경로 PATH + 명 NM) ·
--     검토완료일시 RVW_CMPTN_DT(검토 RVW + 완료 CMPTN + 일시 DT)
--
--   [기존 계약·확정 결정을 승계하는 것 — 단어 정본과 어긋나 보이지만 의도된 것]
--     · ANONY(익명) — 공통표준<단어>는 ANMT 이나 프로젝트 확정으로 ANONY 를 쓴다(LS_DATA_RAW ·
--       LS_DATA_INGEST 물리명이 이미 ANONY_*). 배포분 사전(KLID-BM)은 건드리지 않는다.
--     · FRME_CNT(프레임수) — 사업표준<용어>에는 FRM_CNT 로 등록돼 있으나 FRM 은 <형식(Form)>이라
--       "형식수"로 읽힌다. 프레임=FRME(공통 8차)로 V172/V173 이 이미 정정했고 그 원장 물리명을
--       승계한다.
--     · DE_IDNTF_YN(비식별여부) — 용어 등록은 DE_IDENT_YN 이나 뷰 출력명은 V85 이래의 기존 계약이다.
--
--   ★★ [DATST_NM / DATST_EXPLN — 두 컬럼의 <길이가 서로 다른 것이 정상>이다]
--      (2026-08-05 사용자 확정: <표준 우선순위는 ① 행안부 공통표준 → ② 사업표준>)
--
--      정본 CSV 실측(레포 내 docs/LogiCraft-* 가 유일 정본. 레포 밖 사본으로 판정하지 말 것):
--        · 데이터셋명   — 공통표준용어 DATST_NM      도메인 명V200(VARCHAR 200)   ← <채택>
--                         사업표준용어 DATA_SET_NM   도메인 명V100(VARCHAR 100)   ← 미채택(행안부 1순위)
--        · 데이터셋설명 — 공통표준용어 <미등록(0건)>
--                         사업표준용어 DATA_SET_EXPLN 도메인 내용V4000(VARCHAR 4000) ← <채택>(②순위)
--      따라서 DATST_NM=200 · DATST_EXPLN=4000 이다. 물리명은 둘 다 대외 확정 규격서 §5-1 로 이미
--      전달한 이름과 일치한다(같은 사업 사전의 DATST_NOCS · DE_IDNTF_DATST_ID 도 DATST 계열).
--
--      ⚠ <길이가 갈리는 것을 "모순"으로 보고 통일하지 말 것> — 되돌림 방지 근거 3가지:
--        ① 명칭(명)과 내용(내용)은 <도메인 그룹 자체가 다르다>. 두 컬럼의 값이 지금 우연히 같다는
--           사실과, 도메인이 같아야 한다는 주장은 별개 축이다.
--        ② 관제 스키마도 갈라 놨다 — datasets.name=varchar(500) / datasets.description=text.
--        ③ 이 저장소 확정 규칙: <등록 도메인 크기를 "실제 최대치"로 임의 축소하지 않는다>. 축소 자체가
--           드리프트 지적이고, PostgreSQL varchar 는 가변장이라 상한을 크게 잡아도 저장 비용이 없다.
--        (구 서술 "두 컬럼은 같은 값이니 200 으로 통일한다"는 <폐기>. 그 근거였던 'DATA_SET_EXPLN 은
--         명V100' 은 DATA_SET_NM 의 도메인을 잘못 읽은 것이었다.)
--
--      ⚠⚠ 조용한 절단 — <DATST_NM 에만 해당한다>
--        원천 m.EVNT_NM 은 VARCHAR(255)라 결과 문자열은 최대 263자(255 + 접미사 8)가 될 수 있는데,
--        <명시 캐스팅은 에러 없이 자른다>(SQL 표준: 명시 cast 는 절단, 대입은 에러).
--        접미사 ' 데이터셋 구축' 은 <8자>이므로 경계는 다음과 같다(PostgreSQL varchar 길이는 바이트가
--        아니라 <문자> 기준이라 한글도 1자로 센다):
--            이벤트명 192자 → DATST_NM 200자, <전량 보존>
--            이벤트명 193자 → DATST_NM 200자로 <절단>(접미사 '축'부터 잘려나간다)
--        즉 <이벤트명이 193자를 넘으면 관제에 잘린 데이터셋명이 나가고 아무도 모른다>. 실데이터의
--        이벤트명은 '교통사고' 수준이라 현실 위험은 낮지만, 경계 자체를 회귀 테스트로 고정해 둔다
--        (V174CompletedVideoViewContractIT.DATST_NM_은_이벤트명_192자까지_보존된다 /
--         ..._193자부터_200자로_절단된다). 규격서 §5-3 에도 이 절단 규칙을 명시했다.
--        반면 DATST_EXPLN 은 상한 4000 > 최대 263 이라 <절단이 구조적으로 일어나지 않는다>.
--
-- 인덱스: 신규 인덱스를 만들지 않는다. 추가된 두 조인은 기존 인덱스로 커버된다 —
--   인입 IX_LS_DATA_INGEST_* 는 부분 인덱스라 이 조인에는 안 쓰이지만 UK(VMS_CLIP_ID) 테이블 규모가
--   영상 수와 같아 부담이 없고, 라벨 집계는 IX_LS_DATA_SRC_RAW(RAW_SN) + IX_LS_DATA_LBL_SRC(SRC_SN)
--   가 그대로 탄다. 관제는 통지 수신 후 RAW_SN 단건으로 조회하므로 전량 스캔 경로가 아니다.
--
-- 멱등/안전: DROP VIEW IF EXISTS + CREATE VIEW — 재실행 안전. 데이터 변경 0(뷰 정의뿐).
-- LS_* 전용 뷰라 관제팀 스키마 협의 불요(MNG_* 미변경). PostgreSQL 표준 문법.
-- =============================================================================

DROP VIEW IF EXISTS V_COMPLETED_VIDEO;

CREATE VIEW V_COMPLETED_VIDEO AS
SELECT
    -- ===== (a) 영상 식별·분류 → 관제 datasets (13) =====
    m.RAW_SN,                                                              --  1 원시일련번호 = job_id
    m.ORGNL_RAW_SN,                                                        --  2 파생영상의 부모(원본이면 NULL)
    m.EVNT_TYPE_CD,                                                        --  3 이벤트유형코드
    i.EVNT_CLSF_CD,                                                        --  4 이벤트분류코드   (D1 — 인입)
    i.EVNT_CTGRY_CD,                                                       --  5 이벤트카테고리코드(D1 — 인입)
    m.LCLGV_CD,                                                            --  6 지자체코드
    i.LCLGV_NM,                                                            --  7 지자체명         (D1 — 인입, V172 개명)
    CASE WHEN r.SRC_TYPE IN ('GENERATED', 'AUGMENTED')                     --  8 생성형AI여부     (D2)
         THEN 'Y' ELSE 'N' END::CHAR(1)                 AS GEN_AI_YN,
    (m.EVNT_NM || ' 데이터셋 구축')::VARCHAR(200)         AS DATST_NM,      --  9 데이터셋명  (명V200)
    (m.EVNT_NM || ' 데이터셋 구축')::VARCHAR(4000)        AS DATST_EXPLN,   -- 10 데이터셋설명(내용V4000)
    m.VDO_LEN_SEC,                                                         -- 11 영상길이초  (@req R6 구 DURATION_SEC)
    COALESCE(e.FRME_CNT, 0)                             AS FRME_CNT,       -- 12 프레임수    (@req R6 구 FRAME_CNT)
    m.RVW_CMPL_DT                                       AS RVW_CMPTN_DT,   -- 13 검토완료일시(@req R6 구 REVIEW_COMPLETED_AT)

    -- ===== (b) 버전 속성 → 관제 dataset_versions (12) =====
    CASE WHEN COALESCE(e.FRME_CNT, 0) > 0                                  -- 14 이미지여부 (§5-4)
         THEN 'Y' ELSE 'N' END::CHAR(1)                 AS IMG_YN,
    CASE WHEN d.DE_IDNTF_FILE_PATH_NM IS NOT NULL                          -- 15 영상여부   (§5-4)
         THEN 'Y' ELSE 'N' END::CHAR(1)                 AS VDO_YN,
    -- 16~18 비식별 기준(dataset_versions 적재 대상) — 수동값 우선, 미입력이면 정책 기본값.
    --       상수는 ExportPrivacyPolicy.DEID_DEFAULT_* 와 동기화 대상(위 주석 참조).
    COALESCE(NULLIF(BTRIM(r.ANONY_INCL_YN), ''), 'Y')::CHAR(1) AS ANONY_INCL_YN,
    COALESCE(NULLIF(BTRIM(r.PSDO_INCL_YN),  ''), 'N')::CHAR(1) AS PSDO_INCL_YN,
    COALESCE(NULLIF(BTRIM(r.PRVC_INCL_YN),  ''), 'N')::CHAR(1) AS PRVC_INCL_YN,
    -- 19~21 원천 기준(참고 제공) — 관제 인입값 그대로. 파생영상은 NULL(§5-2, D1).
    CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.ANONY_INCL_YN END AS SRC_ANONY_INCL_YN,
    CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.PSDO_INCL_YN  END AS SRC_PSDO_INCL_YN,
    CASE WHEN r.ORGNL_RAW_SN IS NULL THEN i.PRVC_INCL_YN  END AS SRC_PRVC_INCL_YN,
    -- 22 데이터구축년도 = 검수완료 연도. 결과 타입은 to_char 의 text 이며 <이번엔 캐스팅하지 않는다>:
    --    관제 컬럼은 varchar(100) 인데 사업표준용어 '데이터구축년도' 의 도메인은 내용V1000 이라
    --    등록값 자체가 비현실적이다(연도 4자에 1000자). 규격서 §7-H 로 회신 대기 중이며, 회신 후
    --    길이를 확정해 정리한다. 지금 임의 캐스팅하면 회신값과 어긋날 뿐이고 절단 위험만 새로 만든다.
    to_char(m.RVW_CMPL_DT, 'YYYY')                      AS DATA_ETBL_YR,
    e.DATA_ETBL_CPCT,                                                      -- 23 데이터구축용량(미산출 NULL, V173 신설)
    -- 24 라벨유형(콤마 구분, 미라벨 NULL). string_agg 결과는 text 로 추론되므로 규격서 §5-1 표기와
    --    맞도록 명시 캐스팅한다 — 사업표준용어 '라벨유형' 도메인 명V256(공통표준용어 미등록 → ②순위).
    --    ⚠ 명시 cast 라 256자 초과 시 <에러 없이 절단>된다. 실제 값은 라벨 형태 코드의 조합
    --    (BBOX,POLYGON,SEGMENTATION,...)이라 초과는 사실상 불가능하지만, 형태 코드가 대량 추가되면
    --    조용히 잘릴 수 있다는 점은 인지된 사항이다(DATST_NM 절단과 같은 성질).
    l.LBL_TYPE::VARCHAR(256)                            AS LBL_TYPE,
    'NIA-COCO-JSON'::VARCHAR(256)                       AS LBL_FMT,        -- 25 라벨형식(고정 — 산출 JSON 포맷명)

    -- ===== (c) 산출물 픽업 (5) =====
    e.OUTPUT_PATH_NM,                                                      -- 26 산출물경로명(영상 루트, 미산출 NULL)
    e.OUTPUT_STTS_CD,                                                      -- 27 산출물상태코드(SUCCEEDED|PARTIAL)
    d.DE_IDNTF_FILE_PATH_NM,                                               -- 28 비식별영상 경로 — 적재값 그대로(§5-6)
    m.RAW_FILE_PATH_NM                                  AS ORGNL_VDO_PATH_NM, -- 29 원본영상경로명(파생은 NULL, §5-5)
    m.DE_IDENT_YN                                       AS DE_IDNTF_YN     -- 30 비식별여부(Y|F) — 제거 금지
FROM LS_DATASET_VIDEO_META m
INNER JOIN LS_DATA_RAW        r ON r.RAW_SN      = m.RAW_SN
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = m.RAW_SN
-- 최신 산출 1건 — 통지가 나간 산출(PARTIAL 포함)은 반드시 뷰에서 보인다(V160 / E-ISSUE-81).
-- FAILED·PENDING 은 산출물이 실재하지 않으므로 계속 배제한다.
LEFT JOIN LATERAL (
    SELECT ex.OUTPUT_PATH_NM, ex.OUTPUT_STTS_CD, ex.FRME_CNT, ex.DATA_ETBL_CPCT
    FROM LS_DATASET_EXPORT ex
    WHERE ex.DATA_RAW_SN     = m.RAW_SN
      AND ex.OUTPUT_STTS_CD IN ('SUCCEEDED', 'PARTIAL')
    ORDER BY ex.OUTPUT_VER_NO DESC
    LIMIT 1
) e ON TRUE
-- 최신 비식별 성공 이력 1건 — 파일명은 외부(mock/KPST)가 정하므로 적재값을 그대로 싣는다(§5-6).
LEFT JOIN LATERAL (
    SELECT pl.DE_IDNTF_FILE_PATH_NM
    FROM LS_DEIDENT_PROC_LOG pl
    WHERE pl.DATA_RAW_SN = m.RAW_SN
      AND pl.PROC_STTS_CD = 'SUCCEEDED'
      AND pl.DE_IDNTF_FILE_PATH_NM IS NOT NULL
    ORDER BY pl.REQ_DT DESC, pl.PROC_LOG_SN DESC   -- 앱 조회(findSuccessHistory: REQ_DT DESC)와 동일 기준
    LIMIT 1
) d ON TRUE
-- 관제 인입 평면값 1건 — 앱의 단일 진실원 IngestSourceLink.SQL_LATERAL_JOIN 과 같은 규칙(D1).
-- LS_DATA_INGEST.RAW_SN 에는 UNIQUE 가 없어(VMS_CLIP_ID 만 UK) 수기 정정으로 2행이 생길 수 있다 —
-- LIMIT 1 이 영상 1건 = 1 row 불변식을 지키고, RCPTN_SN DESC 가 같은 입력에 같은 출력을 보장한다.
LEFT JOIN LATERAL (
    SELECT ig.EVNT_CLSF_CD, ig.EVNT_CTGRY_CD, ig.LCLGV_NM,
           ig.ANONY_INCL_YN, ig.PSDO_INCL_YN, ig.PRVC_INCL_YN
    FROM LS_DATA_INGEST ig
    WHERE ig.RAW_SN = COALESCE(r.ORGNL_RAW_SN, r.RAW_SN)
    ORDER BY ig.RCPTN_SN DESC
    LIMIT 1
) i ON TRUE
-- 이 영상에서 실제로 사용된 라벨 형태 집합(BBOX,POLYGON,...). 집계라 GROUP BY 없이 <항상 1행>이며
-- 라벨이 없으면 NULL 이다(행 증식 없음). ORDER BY 로 같은 입력에 같은 문자열을 낸다.
LEFT JOIN LATERAL (
    SELECT string_agg(DISTINCT lb.LBL_TYPE_CD, ',' ORDER BY lb.LBL_TYPE_CD) AS LBL_TYPE
    FROM LS_DATA_LBL lb
    INNER JOIN LS_DATA_SRC sc ON sc.SRC_SN = lb.SRC_SN
    WHERE sc.RAW_SN = m.RAW_SN
) l ON TRUE
WHERE m.ACTIVE_YN = 'Y'
  AND s.DATA_STTS_CD = 'APPROVED';   -- V95/V102 불변식: 뷰 노출 ⇔ 현재 라이브 APPROVED

COMMENT ON VIEW V_COMPLETED_VIDEO IS
    '데이터마트 적재용 — 검수 승인(APPROVED) 영상 1건 = 1 row, 30컬럼(V174, 규격서 §5-1). 관제가 datasets·dataset_versions 를 채우고 산출 폴더·비식별 영상을 픽업하는 계약면이다. 비식별 누락 신고 구간(DE_IDNTF_YN=''F'')에도 행을 감추거나 경로를 비우지 않는다(확정 정책) — 관제가 DE_IDNTF_YN 으로 자체 판단한다.';
