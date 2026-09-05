import { StatusBadge } from '@/components/common/StatusBadge';

import { AiSrvrStatus, AI_SRVR_STATUS_LABEL } from '../types';

/**
 * 장비 상태 배지 넷. [@design SCREEN-042] [@design API-229]
 *
 * <h3>이용불가와 비활성을 같은 것으로 보이게 하지 않는다</h3>
 * 둘 다 「지금 안 쓰는 장비」지만 <b>누가 그렇게 만들었는가</b>가 다르다 — 이용불가는 상태점검
 * 연속 실패로 <b>기계가</b> 배제한 것이고, 비활성은 <b>사람이</b> 내려 둔 것이다. 운영자가 손을
 * 써야 하는 대상이 서로 달라서, 색만이 아니라 <b>글자로도</b> 갈라 놓는다.
 *
 * <p>색은 공용 상태 배지의 톤을 그대로 쓴다(연한 배경 + 같은 계열 700 단). 700 단인 것은
 * `bg-{color}/10` 위에서 DEFAULT 단이 AA(4.5:1) 미달이기 때문이며 되돌리지 말 것.
 *
 * <p>⚠ 색 단독으로 구분하지 않는다 — 배지가 항상 한글 라벨을 함께 보여주고, 그 아래 한 줄로
 * «누가 그렇게 만들었는가»를 덧붙인다(KRDS 색상 단독 구분 금지).
 */
const TONE: Record<AiSrvrStatus, string> = {
  [AiSrvrStatus.AVAILABLE]: 'bg-success/10 text-success-700',
  [AiSrvrStatus.UNAVAILABLE]: 'bg-danger/10 text-danger-700',
  [AiSrvrStatus.DRAINING]: 'bg-warning/10 text-warning-700',
  [AiSrvrStatus.DISABLED]: 'bg-gray-100 text-gray-700',
};

/**
 * 상태 아래 한 줄 — «누가 그렇게 만들었는가».
 *
 * 가용에는 없다. 정상 상태에 설명을 붙이면 목록 전체가 설명으로 덮여 예외 상태가 묻힌다.
 */
const ORIGIN_NOTE: Partial<Record<AiSrvrStatus, string>> = {
  [AiSrvrStatus.UNAVAILABLE]: '상태점검 실패로 자동 배제',
  [AiSrvrStatus.DRAINING]: '관리자가 정비 지정 · 신규 배정만 중단',
  [AiSrvrStatus.DISABLED]: '관리자가 내려 둠',
};

export function AiServerStatusBadge({ status }: { status: AiSrvrStatus }) {
  const note = ORIGIN_NOTE[status];
  return (
    <span className="flex flex-col items-start gap-1">
      <StatusBadge
        status={status}
        label={AI_SRVR_STATUS_LABEL[status] ?? status}
        className={TONE[status]}
      />
      {note && <span className="text-caption text-gray-600">{note}</span>}
    </span>
  );
}
