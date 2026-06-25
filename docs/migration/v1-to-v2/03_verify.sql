-- =====================================================================
-- v1 → v2 영상·라벨 이관 검증. 실행: psql ... -v raw_off=1000000 -v src_off=10000000 -v lbl_off=100000000 -f 03_verify.sql
-- 모든 행이 OK 여야 한다. FAIL 이면 02 트랜잭션을 롤백(또는 cleanup) 후 원인 교정.
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

\echo '== 검증 끝. [2]~[7] 이 모두 OK 여야 정상. (라벨 손실은 02 트랜잭션의 5-b fail-closed 가드가 사전 차단) =='
