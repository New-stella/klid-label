-- =====================================================================
-- v1 → v2 영상·라벨 이관 검증.
-- 실행: psql ... -v raw_off=1000000 -v src_off=10000000 -v lbl_off=100000000 \
--               -v asgn_off=200000000 -v issue_off=300000000 -v aug_off=400000000 -f 03_verify.sql
-- 모든 행이 OK 여야 한다. FAIL 이면 02 트랜잭션을 롤백(또는 cleanup) 후 원인 교정.
-- 선행조건: 대상 DB 에 **V9 가 적용돼 있어야 한다.** 이 스크립트는 표준용어 개명 이후의 물리명
--           (ls_task_altmnt · ls_task_evnt_log)을 쓴다. V8 이하 형상에 돌리면 relation 부재로 실패한다.
-- =====================================================================
\pset footer off

-- 1) 적재 건수 (영상/프레임/라벨/라벨클래스)
\echo '== [1] 이관 적재 건수 =='
SELECT 'ls_data_raw'  AS t, count(*) FROM ls_data_raw WHERE raw_sn >= :raw_off
UNION ALL SELECT 'ls_data_src', count(*) FROM ls_data_src WHERE src_sn >= :src_off
UNION ALL SELECT 'ls_data_lbl', count(*) FROM ls_data_lbl WHERE lbl_sn >= :lbl_off
UNION ALL SELECT 'ls_label(MIGRATION)', count(*) FROM ls_label WHERE reg_id = 'MIGRATION';

-- 2) 고아 라벨 0건 — 모든 라벨의 src_sn / lbl_id 가 실제 존재해야 함
\echo '== [2] 고아 라벨 검사 (0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: orphan src_sn '||count(*) END
FROM ls_data_lbl l WHERE l.lbl_sn >= :lbl_off
  AND NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.src_sn = l.src_sn);
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: orphan lbl_id '||count(*) END
FROM ls_data_lbl l WHERE l.lbl_sn >= :lbl_off
  AND NOT EXISTS (SELECT 1 FROM ls_label c WHERE c.lbl_id = l.lbl_id);

-- 3) 고아 프레임 0건 — 모든 프레임의 raw_sn 존재
\echo '== [3] 고아 프레임 검사 (0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: orphan raw_sn '||count(*) END
FROM ls_data_src s WHERE s.src_sn >= :src_off
  AND NOT EXISTS (SELECT 1 FROM ls_data_raw r WHERE r.raw_sn = s.raw_sn);

-- 4) POINT 변환 정합 — point_cn 이 JSON 배열(=좌표쌍 배열)인지
\echo '== [4] POINT 변환 검사 (non-array 0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: non-array point '||count(*) END
FROM ls_data_lbl
WHERE lbl_sn >= :lbl_off AND point_cn IS NOT NULL AND jsonb_typeof(point_cn::jsonb) <> 'array';

-- 5) BBOX 좌표쌍 2개 / POLYGON ≥3 검사 (형상 sanity) — OK/FAIL 자동 판정
\echo '== [5] 형상 sanity (BBOX=2점, POLYGON>=3점, 위반 0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: malformed shape '||count(*) END
FROM ls_data_lbl
WHERE lbl_sn >= :lbl_off AND point_cn IS NOT NULL
  AND NOT (
    (lbl_type_cd='BBOX'    AND jsonb_array_length(point_cn::jsonb)=2) OR
    (lbl_type_cd='POLYGON' AND jsonb_array_length(point_cn::jsonb)>=3)
  );

-- 6) UK 충돌 0 — vms_clip_id 중복 없어야
\echo '== [6] vms_clip_id 유니크 검사 (0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: dup vms_clip_id '||count(*) END
FROM (SELECT vms_clip_id FROM ls_data_raw GROUP BY vms_clip_id HAVING count(*)>1) d;

-- 7) lbl_type_cd/lbl_nm 비정규화 정합 — 라벨의 타입/이름이 클래스와 일치
\echo '== [7] 라벨 비정규화 정합 (0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: denorm mismatch '||count(*) END
FROM ls_data_lbl l JOIN ls_label c ON c.lbl_id = l.lbl_id
WHERE l.lbl_sn >= :lbl_off AND (l.lbl_type_cd <> c.lbl_type_cd OR l.lbl_nm <> c.lbl_nm);

-- =====================================================================
-- 확장 스코프 검증 (상태 A · 배정 B · 이슈 D · 증강 C)
-- =====================================================================

-- 8) 확장 스코프 적재 건수
\echo '== [8] 확장 스코프 적재 건수 =='
SELECT 'ls_raw_data_status' AS t, count(*) FROM ls_raw_data_status WHERE raw_data_id >= :raw_off
UNION ALL SELECT 'ls_task_altmnt', count(*) FROM ls_task_altmnt WHERE assignment_id >= :asgn_off
UNION ALL SELECT 'ls_data_issue',      count(*) FROM ls_data_issue      WHERE data_issue_sn >= :issue_off
UNION ALL SELECT 'ls_issue_comment',   count(*) FROM ls_issue_comment   WHERE issue_comment_sn >= :issue_off
UNION ALL SELECT 'ls_data_aug',        count(*) FROM ls_data_aug        WHERE data_aug_sn >= :aug_off;

-- 9) 상태 FK/도메인 검사 — 모든 상태행의 raw_data_id 가 이관 영상에 존재 + 코드 유효
\echo '== [9] 상태(A) 정합 (0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: orphan status raw_data_id '||count(*) END
FROM ls_raw_data_status s WHERE s.raw_data_id >= :raw_off
  AND NOT EXISTS (SELECT 1 FROM ls_data_raw r WHERE r.raw_sn = s.raw_data_id);
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: bad data_stts_cd '||count(*) END
FROM ls_raw_data_status s WHERE s.raw_data_id >= :raw_off
  AND s.data_stts_cd NOT IN ('APPROVED','IN_REVIEW','ASSIGNED','REJECTED','PENDING','FAILED');
-- 영상당 상태 1행(중복 수렴 검증)
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: dup status per raw '||count(*) END
FROM (SELECT raw_data_id FROM ls_raw_data_status WHERE raw_data_id >= :raw_off
      GROUP BY raw_data_id HAVING count(*)>1) d;

-- 10) 배정(B) 정합 — 모든 배정의 raw_data_id 존재 + (raw,user,type) 유일
\echo '== [10] 배정(B) 정합 (0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: orphan assign raw '||count(*) END
FROM ls_task_altmnt a WHERE a.assignment_id >= :asgn_off
  AND NOT EXISTS (SELECT 1 FROM ls_data_raw r WHERE r.raw_sn = a.raw_data_id);
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: dup assign '||count(*) END
FROM (SELECT raw_data_id, user_no, task_type_cd FROM ls_task_altmnt WHERE assignment_id >= :asgn_off
      GROUP BY raw_data_id, user_no, task_type_cd HAVING count(*)>1) d;
-- (정보) 배정 누락 영상 — 라벨은 있으나 작성자 user_id 미등록으로 배정 안 된 영상 수
\echo '== [10b] (정보) 배정 없는 이관영상 수 — user_id 미매칭/REG_ID NULL 영향, 손실 아님 =='
SELECT count(*) AS unassigned_migrated_videos
FROM ls_data_raw r WHERE r.raw_sn >= :raw_off
  AND NOT EXISTS (SELECT 1 FROM ls_task_altmnt a WHERE a.raw_data_id = r.raw_sn);

-- 11) 이슈(D) 정합 — raw 존재 + 답글의 부모 루트 존재(고아 댓글 0)
\echo '== [11] 이슈(D) 정합 (0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: orphan issue raw '||count(*) END
FROM ls_data_issue i WHERE i.data_issue_sn >= :issue_off
  AND NOT EXISTS (SELECT 1 FROM ls_data_raw r WHERE r.raw_sn = i.data_raw_sn);
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: orphan comment(parent missing) '||count(*) END
FROM ls_issue_comment c WHERE c.issue_comment_sn >= :issue_off
  AND NOT EXISTS (SELECT 1 FROM ls_data_issue i WHERE i.data_issue_sn = c.data_issue_sn);

-- 12) 증강(C) 정합 — 모든 증강의 src_sn 이 이관 프레임에 존재(고아 0)
\echo '== [12] 증강(C) 정합 (0 이어야 OK) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: orphan aug src '||count(*) END
FROM ls_data_aug g WHERE g.data_aug_sn >= :aug_off
  AND NOT EXISTS (SELECT 1 FROM ls_data_src s WHERE s.src_sn = g.src_sn);

-- 13) 데이터마트 노출 sanity — APPROVED 상태 이관영상 수(데이터마트 View 노출 대상)
\echo '== [13] (정보) 데이터마트 노출(APPROVED) 이관영상 수 =='
SELECT count(*) AS approved_migrated_videos
FROM ls_raw_data_status s WHERE s.raw_data_id >= :raw_off AND s.data_stts_cd='APPROVED';

-- 14) vdo_len_sec 단위 sanity — ms 미변환(÷1000 누락) 적재 탐지
--     참 길이 최대 ~152,200초(시간단위 CCTV). 1,000,000초(≈278h) 초과 = ms 그대로 들어온 것
\echo '== [14] vdo_len_sec 단위 sanity (0 이어야 OK — 초과 시 ÷1000 누락 의심) =='
SELECT CASE WHEN count(*)=0 THEN 'OK' ELSE 'FAIL: vdo_len_sec ms 미변환 의심 '||count(*) END
FROM ls_data_raw WHERE raw_sn >= :raw_off AND vdo_len_sec > 1000000;

\echo '== 검증 끝. [2]~[7],[14] 핵심 + [9]~[12] 확장 이 모두 OK 여야 정상. (라벨 손실은 02 의 5-b fail-closed 가드가 사전 차단) =='
