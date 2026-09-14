import { AlertCircle, Lock, Unlock } from 'lucide-react';
import { useMemo, useState } from 'react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { Tabs } from '@/components/common/Tabs';
import { AdminSessionDialog } from '@/features/adminSession/components/AdminSessionDialog';
import {
  ADMIN_SESSION_TTL_MINUTES_HINT,
  useAdminSessionWindow,
} from '@/features/adminSession/hooks/useAdminSessionWindow';
import { ApiError } from '@/lib/api/errors';
import { useUiStore } from '@/stores/useUiStore';

import {
  useAiServers,
  useChangeAiServerStatus,
  useCreateAiServer,
  useDeleteAiServer,
  useUpdateAiServer,
} from '../hooks/useAiServers';
import type { AiServerCreateForm } from '../schemas';
import {
  AiSrvrStatus,
  AiSrvrType,
  AI_SRVR_TYPE_LABEL,
  AI_SRVR_USAGE_LABEL,
  type AiSrvr,
} from '../types';

import { AiServerFormDialog } from './AiServerFormDialog';
import { AiServerStatusBadge } from './AiServerStatusBadge';
import { AiServerStatusDialog } from './AiServerStatusDialog';

/** 표 헤더 셀 — DS-001 표 표면 관례(14px/600 토큰 + 대문자화). th 에 직접 건다. */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

const TABS = [
  { value: AiSrvrType.INFERENCE, label: AI_SRVR_TYPE_LABEL[AiSrvrType.INFERENCE] },
  { value: AiSrvrType.TIMESERIES, label: AI_SRVR_TYPE_LABEL[AiSrvrType.TIMESERIES] },
];

/**
 * 유형마다 «부하»가 무엇을 센 값인지 다르다 — 그 사실을 탭마다 적는다.
 *
 * 같은 열에 같은 이름으로 놓이면 두 수치가 같은 축으로 읽히는데, 실제로는 하나는 상대 장비의
 * 큐 길이이고 다른 하나는 우리 원장의 미결 위탁 수다.
 */
const LOAD_AXIS_NOTE: Record<AiSrvrType, string> = {
  [AiSrvrType.INFERENCE]:
    '추론은 동기 호출이라 상대 장비의 큐 길이가 곧 부하입니다. 상태점검이 주기적으로 읽어 옵니다.',
  [AiSrvrType.TIMESERIES]:
    '외부 시계열 분석은 위탁 후 콜백이라 상대의 큐를 볼 수 없습니다. 우리 원장의 미결 위탁 수로 셉니다.',
};

/** 유효창이 없어 미뤄 둔 조작. 확인을 마치면 이 조작을 그대로 이어서 연다. */
type PendingAction =
  | { kind: 'create' }
  | { kind: 'edit'; server: AiSrvr }
  | { kind: 'status'; server: AiSrvr }
  | { kind: 'delete'; server: AiSrvr };

function formatDateTime(value: string | null): string {
  if (!value) return '-';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? '-' : parsed.toLocaleString('ko-KR');
}

/**
 * 추론·외부 시계열 분석 장비 목록. [@design SCREEN-042]
 * [@design API-226] [@design API-227] [@design API-228] [@design API-229] [@design API-230]
 * [@design AC-1088] [@design AC-1089] [@design AC-1090] [@design AC-1091]
 * [@design ADR-046] [@design ADR-057]
 *
 * <h3>왜 이 자리인가</h3>
 * 장비 주소의 진실원이 설정값에서 <b>노드 원장</b>으로 옮겨졌다. 그래서 이 화면은 «주소 한 칸을
 * 고치는 화면»이 아니라 «장비 목록을 관리하는 화면»이 된다. 위의 주소 칸(비식별 서버 · 외부 증강
 * 벤더 · 관제 통지 수신처 · 관제 계정 창구)은 <b>한 칸 그대로</b>이며, 여기서 다루는 것은 추론·시계열 둘뿐이다.
 *
 * <h3>★ 이름을 「AI 장비 목록」으로 되돌리지 말 것</h3>
 * 외부 증강도 AI 위탁이라 그 이름이면 <b>여기 있어야 할 것처럼 읽힌다</b>(실제로 그 질문이 나왔다).
 * 이 목록이 담는 것은 AI 서버 전부가 아니라 «장비를 여러 대 두고 골라 보내는 계통»이며, 가르는
 * 축은 「AI 인가」가 아니라 <b>「고를 대상이 여럿인가」</b>다. 증강은 보낼 곳이 한 곳뿐이라
 * 고를 일이 없어 위 주소 칸에 앉는다.
 *
 * <h3>조회는 열려 있고 쓰기에만 유효창이 가산된다</h3>
 * 이 화면(`/admin/endpoints`)은 통째로 관리자 전용이라 여기까지 들어온 사람은 이미 관리자다.
 * 그 위에 <b>관리자 확인</b>이 한 겹 더 붙는 것은 잘못 누르면 그 유형의 처리가 통째로 멈추는
 * 조작이기 때문이다. 유효창이 없어도 목록은 그대로 보인다 — 보이지 않으면 장비 상태를 확인할
 * 길이 자체가 없어진다.
 *
 * <h3>두 유형을 한 표에 섞지 않는다</h3>
 * 부하를 세는 축이 유형마다 달라, 섞으면 같은 열의 두 수치가 같은 뜻으로 읽힌다.
 */
export function AiServerListCard() {
  const pushToast = useUiStore((s) => s.pushToast);
  const session = useAdminSessionWindow();

  const [type, setType] = useState<AiSrvrType>(AiSrvrType.INFERENCE);
  const [sessionDialogOpen, setSessionDialogOpen] = useState(false);
  /**
   * 유효창이 없어서 미뤄 둔 조작 — 확인을 마치면 <b>그 조작을 이어서</b> 연다.
   *
   * ★ 잠겼을 때 버튼을 비활성으로 만들지 않는다. 그러면 왜 눌리지 않는지 알 길이 없고, 확정
   *   사양이 «유효창이 없으면 눌렀을 때 이 화면의 관리자 확인 창이 열린다»로 못박고 있다.
   */
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);

  /** 등록/수정 창 — `undefined` 면 닫힘, `null` 이면 등록, 값이면 그 장비 수정. */
  const [formTarget, setFormTarget] = useState<AiSrvr | null | undefined>(undefined);
  const [statusTarget, setStatusTarget] = useState<AiSrvr | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<AiSrvr | null>(null);

  const [formError, setFormError] = useState<string | undefined>();
  const [statusError, setStatusError] = useState<string | undefined>();
  /** 표 아래에 남는 안내 — 삭제처럼 창이 닫힌 뒤 사유를 보여야 하는 경우. */
  const [listError, setListError] = useState<string | undefined>();

  const { data, isLoading, error } = useAiServers(type);
  const servers = useMemo(() => data ?? [], [data]);
  const availableCountOfType = servers.filter(
    (s) => s.srvrSttsCd === AiSrvrStatus.AVAILABLE,
  ).length;

  const create = useCreateAiServer();
  const update = useUpdateAiServer();
  const changeStatus = useChangeAiServerStatus();
  const remove = useDeleteAiServer();

  /**
   * 쓰기 실패를 문구로 옮긴다.
   *
   * ★<b>409 를 뭉뚱그리지 않는다</b> — 서버가 «이미 그 상태다 / 허용되지 않는 전이다 / 마지막
   * 가용 장비다 / 배정 이력이 있다»를 문구로 갈라 주므로 그 문구를 그대로 보여준다. 화면이
   * 자기 문구로 덮으면 넷이 하나로 뭉개진다.
   * ★400 도 서버 문구를 그대로 쓴다 — 서버는 거부 사유에 입력 원문·호스트·해석 결과를 싣지
   * 않으므로 그대로 노출해도 내부망이 드러나지 않는다.
   */
  const describeFailure = (e: unknown): string => {
    if (e instanceof ApiError) {
      if (e.status === 403) {
        return '관리자 확인이 만료되었습니다. 다시 확인한 뒤 이어서 진행해 주세요.';
      }
      return e.userMessage || '요청을 처리할 수 없습니다.';
    }
    return '요청을 처리할 수 없습니다.';
  };

  /** 서버가 이미 만료로 본 경우 화면 상태를 서버 판정에 맞추고, 다시 확인할 창을 연다. */
  const syncExpiredSession = (e: unknown) => {
    if (e instanceof ApiError && e.status === 403) {
      session.lock();
      setSessionDialogOpen(true);
    }
  };

  /** 창을 실제로 여는 자리 — 잠겨 있으면 확인 창을 먼저 열고 이 조작을 기억해 둔다. */
  const openAction = (action: PendingAction) => {
    setFormError(undefined);
    setStatusError(undefined);
    setListError(undefined);
    if (action.kind === 'create') setFormTarget(null);
    else if (action.kind === 'edit') setFormTarget(action.server);
    else if (action.kind === 'status') setStatusTarget(action.server);
    else setDeleteTarget(action.server);
  };

  const requestAction = (action: PendingAction) => {
    if (session.unlocked) {
      openAction(action);
      return;
    }
    setPendingAction(action);
    setSessionDialogOpen(true);
  };

  /** 확인 성공 시 미뤄 둔 조작을 이어서 연다(확정 사양: 「누르던 것을 이어서 수행한다」). */
  const confirmAdmin = async (adminPassword: string) => {
    const ok = await session.open(adminPassword);
    if (ok && pendingAction) {
      openAction(pendingAction);
      setPendingAction(null);
    }
    return ok;
  };

  const submitForm = async (values: AiServerCreateForm) => {
    setFormError(undefined);
    try {
      if (formTarget) {
        await update.mutateAsync({
          srvrId: formTarget.srvrId,
          // 유형·식별자는 이 창구가 받지 않는다 — 보내지 않는 것이 계약이다.
          body: { srvrNm: values.srvrNm.trim(), srvrAddr: values.srvrAddr.trim() },
          adminSessionToken: session.token,
        });
        pushToast({ variant: 'success', message: 'AI 장비 정보를 저장했습니다' });
      } else {
        await create.mutateAsync({
          body: {
            srvrId: values.srvrId.trim(),
            srvrNm: values.srvrNm.trim() || undefined,
            srvrAddr: values.srvrAddr.trim(),
            srvrTypeCd: values.srvrTypeCd,
          },
          adminSessionToken: session.token,
        });
        pushToast({ variant: 'success', message: 'AI 장비를 등록했습니다' });
        // 방금 등록한 유형이 보고 있던 탭과 다르면 그 탭으로 옮긴다 — 등록했는데 목록에 없으면
        // 실패한 것으로 읽힌다.
        setType(values.srvrTypeCd);
      }
      setFormTarget(undefined);
    } catch (e) {
      setFormError(describeFailure(e));
      syncExpiredSession(e);
    }
  };

  const submitStatus = async (next: AiSrvrStatus) => {
    if (!statusTarget) return;
    setStatusError(undefined);
    try {
      await changeStatus.mutateAsync({
        srvrId: statusTarget.srvrId,
        srvrSttsCd: next,
        adminSessionToken: session.token,
      });
      pushToast({ variant: 'success', message: '장비 상태를 바꿨습니다' });
      setStatusTarget(null);
    } catch (e) {
      setStatusError(describeFailure(e));
      syncExpiredSession(e);
    }
  };

  const submitDelete = async () => {
    if (!deleteTarget) return;
    setListError(undefined);
    try {
      await remove.mutateAsync({
        srvrId: deleteTarget.srvrId,
        adminSessionToken: session.token,
      });
      pushToast({ variant: 'success', message: 'AI 장비를 삭제했습니다' });
      setDeleteTarget(null);
    } catch (e) {
      setListError(describeFailure(e));
      setDeleteTarget(null);
      syncExpiredSession(e);
    }
  };

  return (
    <>
      <section
        aria-labelledby="ai-server-list-heading"
        data-testid="ai-server-list-section"
        className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-5 shadow-sm"
      >
        <div className="flex flex-wrap items-center justify-between gap-2 border-b border-gray-100 pb-3">
          <div className="flex items-center gap-2">
            <h3 id="ai-server-list-heading" className="text-title-sm font-semibold text-gray-700">
              추론·외부 시계열 분석 장비 목록
            </h3>
            {session.unlocked ? (
              <span
                className="flex items-center gap-1 text-caption text-primary-600"
                data-testid="ai-server-session-remaining"
              >
                <Unlock className="h-3.5 w-3.5" aria-hidden="true" />
                수정 가능 {session.remainingLabel} 남음
              </span>
            ) : (
              <span className="flex items-center gap-1 text-caption text-gray-500">
                <Lock className="h-3.5 w-3.5" aria-hidden="true" />
                조회만 가능
              </span>
            )}
          </div>
          {/*
            ★여기에 「관리자 확인」 버튼을 따로 두지 않는다 — 이 화면 위쪽 연동 주소 카드가 이미
              같은 이름의 버튼을 갖고 있어, 하나 더 두면 <b>한 화면에 같은 접근 이름의 버튼이 둘</b>이
              되어 보조기술 사용자가 어느 것을 누르는지 알 수 없다(유효창 저장소는 어차피 공용이라
              어느 쪽으로 열어도 이 목록이 함께 풀린다).
              잠긴 상태에서 아래 조작을 누르면 확인 창이 열리므로 진입 경로도 끊기지 않는다.
          */}
          <Button
            type="button"
            variant="primary"
            size="sm"
            onClick={() => requestAction({ kind: 'create' })}
          >
            장비 등록
          </Button>
        </div>

        <p className="text-caption text-gray-600">
          추론·외부 시계열 분석 장비를 유형마다 여러 대 둘 수 있습니다. 여기 등록한 주소가 실제
          호출에 쓰이며, 배포 설정값은 원장이 비어 있을 때 최초 한 번만 쓰입니다.
        </p>

        {/*
          ★목록을 Tabs 의 **자식**으로 둔다 — 밖에 두면 탭 버튼의 `aria-controls` 가 존재하지 않는
            패널을 가리켜, 보조기술이 «이 탭이 무엇을 여는가»를 따라갈 수 없다(자동 감사가 잡는다).
        */}
        <Tabs
          items={TABS}
          value={type}
          ariaLabel="장비 유형"
          onChange={(v) => {
            setType(v as AiSrvrType);
            setListError(undefined);
          }}
        >
          <p className="text-caption text-gray-600" data-testid="ai-server-load-axis-note">
            {LOAD_AXIS_NOTE[type]}
          </p>

          {isLoading && (
            <div className="space-y-2">
              {Array.from({ length: 2 }).map((_, i) => (
                <Skeleton key={i} height={44} />
              ))}
            </div>
          )}

          {error && <ErrorState title="장비 목록을 불러올 수 없습니다" />}

          {data && servers.length === 0 && (
            <EmptyState message={`${AI_SRVR_TYPE_LABEL[type]} 유형으로 등록된 장비가 없습니다.`} />
          )}

          {data && servers.length > 0 && (
            <div className="overflow-hidden rounded-lg border border-gray-200">
              <table className="w-full text-body-md" data-testid="ai-server-table">
                <thead>
                  <tr className="border-b border-gray-200 bg-secondary-50">
                    <th className={TH_CLASS}>식별자</th>
                    <th className={TH_CLASS}>이름</th>
                    <th className={TH_CLASS}>주소</th>
                    <th className={TH_CLASS}>상태</th>
                    <th className={TH_CLASS}>최근 점검</th>
                    <th className={TH_CLASS}>연속 실패</th>
                    <th className={TH_CLASS}>용도별 대기</th>
                    <th className={TH_CLASS}>조작</th>
                  </tr>
                </thead>
                <tbody>
                  {servers.map((server) => (
                    <tr
                      key={server.srvrId}
                      data-testid={`ai-server-row-${server.srvrId}`}
                      className="border-b border-gray-100 align-top transition-colors hover:bg-rowHover"
                    >
                      <td className="px-3 py-2 text-gray-700">
                        <span className="font-mono text-mono">{server.srvrId}</span>
                      </td>
                      <td
                        className="max-w-[180px] truncate px-3 py-2 text-gray-700"
                        title={server.srvrNm ?? ''}
                      >
                        {server.srvrNm || '-'}
                      </td>
                      <td
                        className="max-w-[240px] truncate px-3 py-2 text-gray-700"
                        title={server.srvrAddr}
                      >
                        {server.srvrAddr}
                      </td>
                      <td className="px-3 py-2 text-gray-700">
                        <AiServerStatusBadge status={server.srvrSttsCd} />
                      </td>
                      <td className="px-3 py-2 text-gray-600">{formatDateTime(server.chckDt)}</td>
                      <td className="px-3 py-2 text-gray-700">
                        {server.chckFailNocs}회
                        <span className="block text-caption text-gray-600">
                          연속 성공 {server.chckScsNocs}회
                        </span>
                      </td>
                      {/*
                      ⚠ 관측된 적 없는 용도는 응답에 **아예 없다**. 0 으로 채우면 가장 한가한
                        장비로 오해하므로 «관측 없음»이라고 적는다.
                    */}
                      <td className="px-3 py-2 text-gray-700">
                        {server.loads.length === 0 ? (
                          <span
                            className="text-gray-600"
                            data-testid={`ai-server-loads-none-${server.srvrId}`}
                          >
                            관측 없음
                          </span>
                        ) : (
                          <ul className="flex flex-col gap-0.5">
                            {server.loads.map((load) => (
                              <li key={load.usgTypeCd}>
                                {AI_SRVR_USAGE_LABEL[load.usgTypeCd] ?? load.usgTypeCd} 대기{' '}
                                {load.wtngNocs}
                                <span className="block text-caption text-gray-600">
                                  처리중 {load.prcsNocs} · 실효 {load.effectiveLoad}
                                </span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                      <td className="px-3 py-2">
                        <div className="flex flex-wrap gap-1">
                          {/*
                          ⚠ 행 액션의 접근 이름에 **식별자를 함께 넣는다** — 넣지 않으면 행 수만큼
                            같은 이름의 버튼이 생겨 보조기술 사용자가 어느 장비를 다루는지 알 수
                            없다(이 저장소에서 실제로 겪은 결함이다).
                        */}
                          <Button
                            size="sm"
                            variant="ghost"
                            aria-label={`${server.srvrId} 수정`}
                            onClick={() => requestAction({ kind: 'edit', server })}
                          >
                            수정
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            aria-label={`${server.srvrId} 상태 바꾸기`}
                            onClick={() => requestAction({ kind: 'status', server })}
                          >
                            상태
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            aria-label={`${server.srvrId} 삭제`}
                            onClick={() => requestAction({ kind: 'delete', server })}
                          >
                            삭제
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Tabs>

        {listError && (
          <p
            className="flex items-center gap-1 text-caption text-danger"
            role="alert"
            data-testid="ai-server-list-error"
          >
            <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            {listError}
          </p>
        )}
      </section>

      <AiServerFormDialog
        open={formTarget !== undefined}
        target={formTarget ?? null}
        defaultType={type}
        isSubmitting={create.isPending || update.isPending}
        error={formError}
        onClose={() => setFormTarget(undefined)}
        onSubmit={submitForm}
      />

      <AiServerStatusDialog
        open={statusTarget !== null}
        target={statusTarget}
        availableCountOfType={availableCountOfType}
        isSubmitting={changeStatus.isPending}
        error={statusError}
        onClose={() => setStatusTarget(null)}
        onSubmit={submitStatus}
      />

      <ConfirmDialog
        open={deleteTarget !== null}
        title="AI 장비를 삭제할까요?"
        description={
          deleteTarget
            ? `${deleteTarget.srvrId} 를 원장에서 지웁니다. 배정 이력이 남아 있는 장비는 지울 수 없습니다.`
            : ''
        }
        warning="그 유형의 마지막 가용 장비는 지울 수 없습니다. 교체하려면 새 장비를 먼저 등록하세요."
        confirmLabel="삭제"
        variant="danger"
        loading={remove.isPending}
        onConfirm={submitDelete}
        onCancel={() => setDeleteTarget(null)}
      />

      <AdminSessionDialog
        open={sessionDialogOpen}
        onClose={() => {
          setSessionDialogOpen(false);
          setPendingAction(null);
        }}
        onSubmit={confirmAdmin}
        isSubmitting={session.isOpening}
        error={session.error}
        ttlMinutesHint={ADMIN_SESSION_TTL_MINUTES_HINT}
        unlockTargetLabel="AI 장비 등록·수정"
      />
    </>
  );
}
