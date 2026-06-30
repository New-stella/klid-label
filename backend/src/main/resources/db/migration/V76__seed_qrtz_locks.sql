-- ============================================================
-- V76 — QRTZ_LOCKS 락 행 시딩 (Quartz 클러스터링 대비)
--
-- 배경: V2 는 QRTZ_LOCKS 테이블만 생성하고 락 행(TRIGGER_ACCESS/STATE_ACCESS)을
--   시딩하지 않았다. 단일 인스턴스(StdRowLockSemaphore lazy-insert)에서는 문제가
--   없었으나, 이중화(isClustered=true)에서는 두 노드가 동일 SCHED_NAME 의 락 행을
--   통해 트리거 발화를 조율하므로 락 행이 선재해야 경합/초기화 오류가 없다.
--
-- SCHED_NAME 은 org.quartz.scheduler.instanceName(application.yml) = 'KlidAuthoringScheduler'
--   과 반드시 일치해야 한다. instanceName 변경 시 본 시드도 함께 갱신할 것.
--
-- 멱등: PK (SCHED_NAME, LOCK_NAME) + ON CONFLICT DO NOTHING 으로 재실행 안전.
-- ============================================================

INSERT INTO QRTZ_LOCKS (SCHED_NAME, LOCK_NAME)
SELECT 'KlidAuthoringScheduler', v
  FROM (VALUES ('TRIGGER_ACCESS'), ('STATE_ACCESS')) AS x(v)
ON CONFLICT (SCHED_NAME, LOCK_NAME) DO NOTHING;
