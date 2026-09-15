// 포털 라벨링 — 확인 창 넷(닫기 전 · 프레임 이동 전 · 저장 충돌 · 진행 중).
//
// <h3>흐름은 원본 그대로</h3>
// 걸음이 부르는 핸들러는 전부 라벨링 화면(`LabelingPage`)이 이미 가진 것이다 — 저장 후 닫기/이동은
// 같은 저장 축(`persistPendingWork`)을 타고, 진행 중 창의 「작업 취소」는 스토어 busy 취소다.
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 작은 창(`Dialog`) 문법이다 — 「취소」·「머무르기」를 따로 두지 않고 오른쪽 위 X 가 머무는 길이다
// (원본 창의 「취소」 버튼 자리). 걸음이 둘이면 서브가 먼저 · 메인이 오른쪽 끝이다.
//
// <h3>진행 중 창</h3>
// 원본은 캔버스 위 흰 상자(`BusyOverlay`)였고 포털 화면은 같은 내용을 작은 창으로 세운다.
// 표시 규칙은 원본 그대로 옮겼다 — 짧은 작업은 깜빡이지 않게 **지연 창**(`BUSY_OVERLAY_DELAY_MS`)이
// 지난 뒤에만 뜨고, 경과 시간을 세며, 대기 상한을 모르면 「최대」 를 말하지 않는다.
// 나가는 길은 취소 하나라 X 도 취소와 같다.
//
// <h3>★ 닫힌 창은 화면에 두지 않는다</h3>
// 포털 창(킷 `Modal`)은 닫혀도 `role="dialog" aria-modal="true"` 요소를 숨긴 채 남긴다. 라벨링 단축키는
// 「열린 창이 있는가」를 그 요소로 판정하므로(`hasOpenModalDialog`), 닫힌 창을 남기면 **창이 하나도 안 떠
// 있어도 단축키가 전부 막힌다.** 그래서 창마다 열릴 때만 세운다(닫는 순간의 사라지는 움직임은 없다).

import { useEffect, useState } from 'react';

import { Alert, Dialog } from '@portal/components/custom';
import { BUSY_KIND_PROGRESS_LABEL, BUSY_OVERLAY_DELAY_MS } from '@/features/label/busyPolicy';
import type { BusyKind } from '@/stores/useLabelStore';

export interface PortalCloseConfirm {
  open: boolean;
  dirtyCount: number;
  closing: boolean;
  onSaveAndClose: () => void;
  onDiscardAndClose: () => void;
  onStay: () => void;
}

export interface PortalNavGuard {
  open: boolean;
  dirtyCount: number;
  saving: boolean;
  onSaveAndMove: () => void;
  onDiscardAndMove: () => void;
  onCancel: () => void;
}

export interface PortalSaveConflict {
  open: boolean;
  onReload: () => void;
  onKeep: () => void;
}

export interface PortalBusy {
  kind: BusyKind | null;
  startedAt?: number;
  limitMs?: number;
  onCancel: () => void;
}

/** 경과 초 → 「분:초」. 진행 중 창의 값 줄이다. */
function mmss(sec: number): string {
  const mm = String(Math.floor(sec / 60)).padStart(2, '0');
  const ss = String(sec % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

export function PortalLabelingDialogs({
  close,
  nav,
  conflict,
  busy,
}: {
  close: PortalCloseConfirm;
  nav: PortalNavGuard;
  conflict: PortalSaveConflict;
  busy: PortalBusy;
}) {
  return (
    <>
      {close.open && (
        <Dialog
          open
          onOpenChange={(open) => !open && close.onStay()}
          title="저장 안 한 변경사항이 있습니다"
          desc={`${close.dirtyCount}개 객체에 저장하지 않은 변경이 있습니다. 어떻게 할까요?`}
          sub={{ label: '저장 없이 닫기', onClick: close.onDiscardAndClose, disabled: close.closing }}
          main={{ label: '저장 후 닫기', onClick: close.onSaveAndClose, busy: close.closing }}
        />
      )}

      {nav.open && (
        <Dialog
          open
          onOpenChange={(open) => !open && nav.onCancel()}
          title="저장 안 한 변경사항이 있습니다"
          desc={`${nav.dirtyCount}개 객체에 저장하지 않은 변경이 있습니다. 프레임을 이동하기 전에 어떻게 할까요?`}
          sub={{ label: '저장 안 함', onClick: nav.onDiscardAndMove, disabled: nav.saving }}
          main={{ label: '저장 후 이동', onClick: nav.onSaveAndMove, busy: nav.saving }}
        />
      )}

      {/* 저장 충돌 — 최신 라벨을 불러오면 내 변경이 사라진다. 사라지는 쪽이 위험 색이다.
          창은 불러오기가 성공한 뒤에 화면이 닫는다(실패하면 다시 고를 수 있게 남긴다) */}
      {conflict.open && (
        <Dialog
          open
          onOpenChange={(open) => !open && conflict.onKeep()}
          title="다른 사용자가 먼저 저장했습니다"
          desc="최신 라벨을 불러온 뒤 다시 저장해 주세요. 작업 내용을 남기려면 「내 작업 유지」를 선택한 뒤 필요한 부분을 다시 확인해 주세요."
          alert={
            <Alert tone="warning" live="none">
              최신 라벨을 불러오면 저장하지 않은 변경은 사라집니다.
            </Alert>
          }
          sub={{ label: '내 작업 유지', close: true }}
          main={{ label: '최신 라벨 불러오기', tone: 'danger', onClick: conflict.onReload }}
        />
      )}

      {/* 창이 이미 떠 있으면(닫기 전 · 이동 전 확인의 「저장 후 …」) 진행 중 창을 겹쳐 띄우지 않는다 —
          그 창의 메인 걸음이 이미 도는 모양으로 진행을 말한다 */}
      <PortalBusyDialog {...busy} suppressed={close.open || nav.open} />
    </>
  );
}

function PortalBusyDialog({
  kind,
  startedAt,
  limitMs,
  onCancel,
  suppressed,
}: PortalBusy & { suppressed: boolean }) {
  const [visible, setVisible] = useState(false);
  const [elapsedSec, setElapsedSec] = useState(0);

  // 지연 표시 + 경과 시간 — 원본 진행 표시와 같은 규칙. 작업이 바뀔 때마다 지연을 다시 센다.
  useEffect(() => {
    setVisible(false);
    setElapsedSec(0);
    if (kind === null) return;
    const begin = startedAt ?? Date.now();
    const tick = () => setElapsedSec(Math.max(0, Math.floor((Date.now() - begin) / 1000)));
    const remain = Math.max(0, BUSY_OVERLAY_DELAY_MS - (Date.now() - begin));
    let interval: ReturnType<typeof setInterval> | undefined;
    const timer = setTimeout(() => {
      setVisible(true);
      tick();
      interval = setInterval(tick, 1000);
    }, remain);
    return () => {
      clearTimeout(timer);
      if (interval !== undefined) clearInterval(interval);
    };
  }, [kind, startedAt]);

  if (kind === null || !visible || suppressed) return null;

  // 상한을 모르면 끝을 말하지 않는다 — 지어낸 값은 거짓 약속이 된다(원본 규칙).
  const limitSec =
    typeof limitMs === 'number' && Number.isFinite(limitMs) && limitMs > 0
      ? Math.round(limitMs / 1000)
      : null;

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onCancel()}
      title={BUSY_KIND_PROGRESS_LABEL[kind]}
      desc="취소하면 요청을 중단하고 결과를 반영하지 않은 채 편집을 계속합니다."
      meta={mmss(elapsedSec)}
      caption={limitSec === null ? undefined : `(최대 ${limitSec}초)`}
      main={{ label: '작업 취소', onClick: onCancel }}
    />
  );
}
