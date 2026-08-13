-- V135 — LS_DATA_SRC 라벨셋 버전 컬럼(LBL_VER) 추가 (C-ISSUE-21).
--
-- [결함] 프레임 라벨 저장(PUT /v1/frames/{srcSn}/labels)은 full-replace 계약이라 요청에 없는 기존 라벨을
--        물리 삭제한다. 그런데 @Version·비관적 락·ETag 어느 것도 없어, 같은 프레임을 동시에 연 두 편집
--        주체(같은 영상에 복수 LABELER 배정 가능 / REVIEWER 는 배정 무관 통과 / 같은 사용자 다중 탭)가
--        저장하면 뒤 요청이 앞 요청의 라벨을 조용히 삭제했다(실측: 26ms 간격 existing=0→1, deleted=1, 200 응답).
--
-- [수정] 프레임 단위 <b>라벨셋 버전</b>을 두고, 요청이 버전을 첨부하면 저장 직전 값과 대조해 불일치 시 409.
--        저장으로 라벨이 실제 변경되면 버전을 +1 한다. 버전 미첨부 요청은 검사 없이 통과(하위호환).
--        동시 저장 자체는 프레임 행 비관적 락(SELECT ... FOR UPDATE)으로 직렬화한다 — 2노드
--        Active-Active 배포라 JVM 락은 방어가 되지 않으므로 DB 수준으로만 해결한다.
--
-- [표준용어·표준도메인]
--   물리명 LBL_VER = 표준단어 '라벨'(LBL) + '버전'(VER) 조합.
--       · 라벨=LBL  : 사업표준단어 71행 등재(Label).
--       · 버전=VER  : 사업표준단어 98행 + 공통표준단어 540행 양쪽 등재(Version).
--   타입   BIGINT NOT NULL DEFAULT 0 = 리포 내 버전 컬럼 확정 선례와 동일
--          (LS_RAW_DATA_STATUS · LS_PORTAL_TUS_ULD · LS_DATA_ISSUE · LS_EVNT_ANNO_REVIEW ·
--           LS_TUS_UPLOAD · V134 LS_TASK_ASSIGNMENT 의 VER).
--   JPA @Version(엔티티 낙관적 잠금)이 아니라 <b>도메인 값</b>이므로 컬럼명을 VER 로 두지 않고
--   LBL_VER 로 구분한다(프레임 행의 다른 컬럼 변경 — 예: 프레임 설명 — 이 라벨셋 버전을 올리면 안 됨).
--
-- 멱등: IF NOT EXISTS + 기존 행 백필. Flyway 재실행/부분 적용 상태에서도 안전.

ALTER TABLE LS_DATA_SRC
    ADD COLUMN IF NOT EXISTS LBL_VER BIGINT NOT NULL DEFAULT 0;

-- 기존 행 백필 — 컬럼 추가 시 DEFAULT 0 이 채워지지만, 과거에 NULL 허용으로 선반영된 환경까지 방어한다.
UPDATE LS_DATA_SRC SET LBL_VER = 0 WHERE LBL_VER IS NULL;

COMMENT ON COLUMN LS_DATA_SRC.LBL_VER IS '라벨버전 - 프레임 라벨셋 변경 시마다 +1 (동시 저장 lost update 차단용, 0부터 시작)';
