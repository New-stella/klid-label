import { useState } from 'react';

import { Alert } from '@/components/common/Alert';
import { PageHeader } from '@/components/common/PageHeader';
import { AdminSessionDialog } from '@/features/adminSession/components/AdminSessionDialog';
import { AdminSessionStatus } from '@/features/adminSession/components/AdminSessionStatus';
import {
  ADMIN_SESSION_TTL_MINUTES_HINT,
  useAdminSessionWindow,
} from '@/features/adminSession/hooks/useAdminSessionWindow';
import { DangerActions } from '@/features/sysconfig/components/DangerActions';

/**
 * 위험 작업 화면(`/admin/maintenance`). [@design SCREEN-043] [@design UI-090]
 *
 * <h3>왜 전용 화면인가</h3>
 * 되돌릴 수 없는 작업이라 다른 일을 하다 실수로 누르는 동선을 없앤다. 시스템 설정 화면 하단에
 * 있던 자리를 그대로 옮겨 왔다.
 *
 * <h3>★기능을 새로 만들지 않는다 — 완전한 자리표시다</h3>
 * {@link DangerActions} 는 확인 절차를 거친 뒤 <b>토스트만</b> 띄우며 서버를 부르지 않는다
 * (API 호출 0건). 이 변경의 범위는 <b>화면을 옮기는 것</b>이고, 실제 실행 창구는 아직 없다.
 * 여기에 임의로 요청을 붙이면 설계에 없는 파괴적 창구를 만들게 된다.
 *
 * <p>그래서 관리자 유효창은 <b>표시와 재확인 통로</b>로만 둔다 — 서버로 나가는 요청이 없으니
 * 실행이 유효창 만료로 거부되는 일도 아직 없다. 실행 창구가 생기면 그때 이 자리에서 토큰을
 * 실어 보내면 된다.
 */
export function AdminMaintenancePage() {
  const session = useAdminSessionWindow();
  const [dialogOpen, setDialogOpen] = useState(false);

  return (
    <section className="flex flex-col gap-6">
      <PageHeader
        title="위험 작업"
        description="시스템 초기화 · 배치 큐 초기화 · 캐시 삭제"
      />

      <div className="flex max-w-3xl flex-col gap-4">
        <Alert variant="error" title="여기 있는 작업은 되돌릴 수 없습니다">
          작업마다 실행 전에 확인 절차를 거칩니다. 이 안내가 그 절차를 대신하지 않습니다.
        </Alert>

        <AdminSessionStatus
          unlocked={session.unlocked}
          remainingLabel={session.remainingLabel}
          onReauthenticate={() => setDialogOpen(true)}
          scopeLabel="위험 작업 실행에는 관리자 확인이 필요합니다."
        />

        <DangerActions />
      </div>

      <AdminSessionDialog
        open={dialogOpen}
        onClose={() => setDialogOpen(false)}
        onSubmit={session.open}
        isSubmitting={session.isOpening}
        error={session.error}
        ttlMinutesHint={ADMIN_SESSION_TTL_MINUTES_HINT}
        unlockTargetLabel="위험 작업"
      />
    </section>
  );
}
