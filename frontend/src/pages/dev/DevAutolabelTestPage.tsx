import { useEffect, useMemo, useState } from 'react';

import { PageHeader } from '@/components/common/PageHeader';
import { AdminSessionDialog } from '@/features/adminSession/components/AdminSessionDialog';
import { AdminSessionStatus } from '@/features/adminSession/components/AdminSessionStatus';
import {
  ADMIN_SESSION_TTL_MINUTES_HINT,
  useAdminSessionWindow,
} from '@/features/adminSession/hooks/useAdminSessionWindow';
import { AutolabelResultCard } from '@/features/dev/components/AutolabelResultCard';
import { extractBeMessage, useAutolabelTest } from '@/features/dev/hooks/useAutolabelTest';
import { useAutolabelStatus } from '@/features/dev/hooks/useAutolabelStatus';
import { useEventTypes } from '@/features/eventType/hooks';
import { TusUploadPanel } from '@/features/upload/components/TusUploadPanel';
import { type AutolabelTestMeta, type AutolabelTestResult } from '@/features/dev/types';

/**
 * 파일 업로드 화면 (`/admin/uploads`). [@design SCREEN-027] [@design API-158] [@design API-152]
 *
 * <p>관리자 페이지 소속이라 진입에 관리자 패스워드 확인을 거친다. 업로드 <b>시작</b>에도 그
 * 유효창이 실린다 — 청크·취소에는 실리지 않아 대용량 영상이 유효창을 넘겨도 끊기지 않는다.
 *
 * <p>REVIEWER 전용. 입력 폼은 **한 벌**이고(`TusUploadPanel`) 최상단 «적재 경로» 라디오가 보내는
 * 곳과 그 뒤 흐름만 바꾼다:
 * <ul>
 *   <li><b>파이프라인 즉시 실행</b> — 올린 즉시 비식별을 거쳐 마킹 대기(MARKING_READY)에서 멈춘다.
 *       응답이 곧바로 영상 식별자를 주므로 결과 패널이 바로 뜨고, 이 페이지가 영상 상세를 2초 간격
 *       으로 polling 해 진행을 가시화한다(terminal = MARKING_READY 또는 FAILED).</li>
 *   <li><b>관제 인입 재현</b> — 인입 원장에 적재만 되고 영상 식별자는 주기 배치가 그 행을 가져간
 *       뒤에 생긴다. 그래서 그 경로에서는 결과 패널 대신 인입 대기 안내만 보인다.</li>
 * </ul>
 *
 * <p>이 페이지가 소유하는 것은 «폼 밖» 뿐이다 — 이벤트유형 마스터 조회, 즉시 실행 경로의 요청
 * (mutation)·오류 문구, 폴링, 결과 패널. 폼 상태와 인입 재현 경로의 청크 전송은 폼이 소유한다.
 *
 * <p>단계별 실행을 고르는 기능은 두지 않는다 — 즉시 실행의 흐름은 업로드 → 비식별 → 마킹 대기
 * 정지로 고정이며 비식별 단계는 예외 없이 항상 실행된다(출처유형 비식별 제외는 원본 복사로
 * 완료 — ADR-066).
 *
 * <p>보안: 오류 문구는 서버가 내려준 텍스트를 JSX 자동 이스케이프로 표시한다(XSS 방어). 사용자
 * 입력을 평문으로 `console` 에 남기지 않는다.
 */
export function DevAutolabelTestPage() {
  const session = useAdminSessionWindow();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<AutolabelTestResult | null>(null);
  const [terminalReached, setTerminalReached] = useState(false);

  // 이벤트유형 옵션 — 표시명이 같은 유형코드들은 서버가 한 옵션으로 접어 내려준다(표시명 그룹 축).
  const eventTypesQuery = useEventTypes();
  const eventOptions = useMemo(() => eventTypesQuery.data ?? [], [eventTypesQuery.data]);

  const mutation = useAutolabelTest({
    onSuccess: (data) => {
      setResult(data);
      setErrorMessage(null);
    },
    onError: (err) => {
      setErrorMessage(extractBeMessage(err, '업로드에 실패했습니다. 입력값을 확인해주세요.'));
    },
  });

  // 결과 영역 polling — rawSn 받은 이후, 영상 상태가 terminal(MARKING_READY/FAILED) 도달 전까지.
  // 고정 플로우라 파이프라인은 MARKING_READY 에서 정지하며, 비식별 실패 시 FAILED 로 끝난다.
  const pollingEnabled = useMemo(() => {
    if (!result) return false;
    return !terminalReached;
  }, [result, terminalReached]);

  const statusQuery = useAutolabelStatus(result?.rawSn ?? null, pollingEnabled);
  const videoDetail = statusQuery.data ?? null;
  // 성공 terminal: MARKING_READY — 고정 플로우의 정지점이다. 잔여 배치는 사용자가 마킹 화면에서
  // 마킹→완료할 때만 진행되므로 이 경로에서 영상은 COMPLETED 로 가지 않는다. MARKING_READY 도달 시
  // 마킹 대기 안내를 노출하고 폴링을 종료한다(무한 스피너 방지).
  const reachedMarkingReady = videoDetail?.status === 'MARKING_READY';
  const reachedTerminal = reachedMarkingReady || videoDetail?.status === 'FAILED';

  // terminal 도달 시 polling 즉시 중단 (불필요한 트래픽 차단)
  useEffect(() => {
    if (reachedTerminal && !terminalReached) {
      setTerminalReached(true);
    }
  }, [reachedTerminal, terminalReached]);

  const handleImmediateSubmit = (args: { file: File; meta: AutolabelTestMeta }) => {
    setErrorMessage(null);
    setResult(null);
    setTerminalReached(false);
    // 유효창이 없으면 요청을 보내기 전에 확인 창을 먼저 연다 — 보내 봐야 403 이고, 그 거부는
    // 화면에서 「이유를 알 수 없는 실패」로 보인다.
    //
    // 폼이 이미 같은 선처리를 하므로(두 적재 경로 공통) 여기까지 잠긴 채로 오지는 않는다. 그래도
    // 남겨 두는 것은 이 핸들러가 폼 밖에서도 불릴 수 있는 prop 이기 때문이다 — 판정은 한 곳
    // (`session.unlocked`)이고 여기서 다시 유도하지 않는다.
    if (!session.unlocked) {
      setDialogOpen(true);
      return;
    }
    mutation.mutate({ ...args, adminSessionToken: session.token });
  };

  const handleReset = () => {
    setErrorMessage(null);
    setResult(null);
    setTerminalReached(false);
  };

  return (
    <main className="space-y-6">
      <PageHeader
        title="파일 업로드"
        description="영상 파일과 메타데이터를 한 폼에서 입력해 올립니다. 적재 경로를 파이프라인 즉시 실행과 관제 인입 재현 중에서 고를 수 있으며, 입력 폼은 두 경로가 같습니다."
      />

      <AdminSessionStatus
        unlocked={session.unlocked}
        remainingLabel={session.remainingLabel}
        onReauthenticate={() => setDialogOpen(true)}
        scopeLabel="업로드 시작에는 관리자 확인이 필요합니다."
      />

      <TusUploadPanel
        eventOptions={eventOptions}
        adminSessionToken={session.token}
        adminSessionLocked={!session.unlocked}
        onAdminSessionRequired={() => setDialogOpen(true)}
        onImmediateSubmit={handleImmediateSubmit}
        immediatePending={mutation.isPending}
        onReset={handleReset}
        errorSlot={
          errorMessage && (
            <div
              role="alert"
              data-testid="autolabel-error"
              className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sub text-danger-700"
            >
              {errorMessage}
            </div>
          )
        }
      />

      {result && (
        <AutolabelResultCard
          result={result}
          status={videoDetail?.status ?? null}
          frameCount={videoDetail?.frameCount ?? null}
          reachedTerminal={reachedTerminal}
          reachedMarkingReady={reachedMarkingReady}
        />
      )}

      <AdminSessionDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onSubmit={session.open}
        isSubmitting={session.isOpening}
        error={session.error}
        ttlMinutesHint={ADMIN_SESSION_TTL_MINUTES_HINT}
        unlockTargetLabel="영상 업로드"
      />
    </main>
  );
}

export default DevAutolabelTestPage;
