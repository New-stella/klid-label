# 시계열 일괄 축 화면 구현 — 플랜

> 스펙: `docs/superpowers/specs/2026-08-20-vlm-bulk-axis-screen-design.md`
> 브랜치: 현재 `feat/vlm-toggle-abolish-bulk-skip-0819` 를 이어 쓴다 (같은 축의 연속 작업).

---

## Task 0 — 공유 자산 (api · types · hook)

**파일**
- `frontend/src/features/video/types.ts`
- `frontend/src/features/video/api.ts`
- `frontend/src/features/video/hooks/useBatchRecovery.ts`

**할 일**
1. `types.ts` — **새 결과 타입을 만들지 않는다.** BE `BatchStageBulkResponse` 가
   `BatchBulkRetryResult`(`successCount`/`failureCount`/`results[]`)와 필드까지 같다(실측).
   사유 상한만 상수로 추가: `SKIP_REASON_MAX = 500` (BE `ManualStageSkip.REASON_MAX_LENGTH`).
2. `api.ts` — 함수 3개. 기존 `retryBatchBulk` 패턴을 그대로 따르고
   `assertStageBundle` 로 경로 세그먼트를 검증한다(CWE-22).
   - `skipBatchStageBulk(rawSns, reason)` → `POST /videos/batch/stages/VLM/skip`
   - `clearBatchStageSkipBulk(rawSns)` → `DELETE /videos/batch/stages/VLM/skip`
   - `rerunBatchStageBulk(rawSns)` → `POST /videos/batch/stages/VLM/rerun`
   - 셋 다 `Array.from(new Set(rawSns))` 로 중복 제거(서버와 같은 정규화 — 갈리면 건수 표시가 어긋난다).
   - ⚠ `DELETE` + 본문: axios 는 `delete(url, { data })` 형태여야 한다. 기존 단건 해제는 본문이 없으므로
     이 한 건은 패턴이 다르다 — 실측으로 확인하고 쓴다.
3. `useBatchRecovery.ts` — 뮤테이션 훅 3개를 기존 배치에 더한다.

**AC 근거**: `AC-049` · `AC-050`

---

## Task 1 — SCREEN-008 일괄 액션바 3버튼 + 사유 모달

**파일**
- `frontend/src/pages/VideoListPage.tsx`
- `frontend/src/features/video/components/BulkSkipReasonModal.tsx` (신규 — 사유 입력 전용)
- `frontend/src/features/video/components/BulkRetryResultModal.tsx` (title prop 추가)

**할 일**
1. 액션바에 버튼 3개. 라벨은 설계 그대로 —
   `{n}건 시계열 건너뛰기` · `{n}건 시계열 건너뛰기 해제` · `{n}건 시계열 재수행`.
   기존 `btn-secondary` 위계 유지(배정만 primary).
2. 사유 모달 — 대상 칩 목록 + 사유 textarea + 글자수(**라벨 줄 오른쪽**, `<label>` 밖).
   빈 값·공백만이면 제출 버튼 비활성 + 안내(서버 400 을 받기 전에 화면이 먼저 막는다).
3. 결과는 `BulkRetryResultModal` 재사용 — `title` prop 만 열고 기본값은 기존 문구.
4. 상한 100건 — 기존 `BULK_RETRY_MAX` · `exceedsBulkRetryLimit` 재사용, 안내 문구도 같은 패턴.

**⚠ 하지 않을 것**: 오토라벨 묶음 버튼(설계가 명시적으로 배제), 확인 창(해제·재수행은 파괴적이지 않다).

**시안**: `screens/SCREEN-008/design/design.html` (`.bulk-bar` · `#modal-skip-vlm`)
**AC 근거**: `AC-049` · `AC-050`

---

## Task 2 — SCREEN-009 승인 영상 재수행 분기

**파일**
- `frontend/src/features/video/components/BatchFailurePanel.tsx`

**할 일**
1. `video.everApproved && bundle === 'AUTOLABEL'` 이면 재수행 버튼 `disabled` + 사유.
2. 기존 조건(`busy || processing`)을 **대체하지 않고 더한다**.
3. 사유는 `title` + 영역 하단 설명(`aria-describedby`) — 기존 방식 그대로. 새 Tooltip 안 만든다.

**시안**: `design.html` 참고 변형 ⑤
**AC 근거**: `AC-051`

---

## Task 3 — 테스트 + 카탈로그

1. 컴포넌트 테스트 — 접근성 이름 조회 관례를 따른다.
   - 일괄 3버튼 노출·상한·중복 제거·부분 성공 표시
   - 사유 빈 값/공백만 → 제출 차단
   - 승인 영상: 시계열 활성 / 오토라벨 비활성 + 사유 읽힘
2. `docs/test-cases/H-frontend-e2e.md` 갱신 (저장소 구속 규칙 — 같은 커밋).
   근거는 `파일명(심볼명)` 형식, 라인번호 금지.

---

## Task 4 — 회귀 · 커밋 · 추적

1. 전체 회귀 1회. ⚠ `cc-build-validator` 는 기동 불가 → `general-purpose` 에 실행 규칙을 실어 위임.
2. 선별 커밋(`git add -A` 금지 — 무관 미추적 31건).
3. IMPREC 기록 + `register_module`/`link_ui_component_to_module` 역링크.
4. `mc-logi-update` 권고: AC 의 `verifies`(REQ) 연결 미확인.
