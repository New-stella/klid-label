-- 오토라벨 결과 조회(findAutoLabelInfoByRawSn) 의 LATERAL 서브쿼리 술어 가속용 복합 인덱스.
-- 술어: WHERE DATA_LBL_SN = ? AND AUTO_LBL_YN = 'Y' ORDER BY MDFCN_DT/REG_DT/PK
-- 기존 IDX_LS_DATA_LBL_AI_INFO_LBL(DATA_LBL_SN) 만으로는 AUTO_LBL_YN='Y' 가 post-filter 였다.
-- LS_* 전용 테이블이라 자체 관리(관제서버팀 협의 불필요). PostgreSQL 표준 문법.
CREATE INDEX IF NOT EXISTS idx_ls_data_lbl_ai_info_lbl_auto
    ON LS_DATA_LBL_AI_INFO (DATA_LBL_SN, AUTO_LBL_YN);
