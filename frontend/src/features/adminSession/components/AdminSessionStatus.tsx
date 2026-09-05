import { Lock, Unlock } from 'lucide-react';

import { Button } from '@/components/common/Button';

interface Props {
  unlocked: boolean;
  /** 남은 시간 표시(`M:SS`). 잠겨 있으면 빈 문자열이다. */
  remainingLabel: string;
  /** 관리자 확인 창을 연다. */
  onReauthenticate: () => void;
  /** 상태 줄 앞에 붙일 설명 — 화면마다 무엇이 열리는지 다르다. */
  scopeLabel?: string;
}

/**
 * 관리자 유효창 상태 표시 — 열림/잠김 + 남은 시간 + 다시 열기. [@design SCREEN-042] [@design SCREEN-043]
 *
 * <h3>왜 남은 시간을 보여주나</h3>
 * 긴 입력을 마치고 저장을 눌렀을 때 비로소 만료를 알게 되는 일을 없애기 위해서다. 만료를 거부
 * 응답으로만 알리고 끝내지 않는다.
 *
 * <h3>색만으로 알리지 않는다</h3>
 * 상태는 **아이콘 + 한글 문구 + 남은 시간 텍스트** 세 가지로 함께 나른다. 시안의 게이지 막대는
 * 보조 표현이라 생략했다 — 정본은 분·초 텍스트다.
 *
 * <h3>이 표시는 안내일 뿐이다</h3>
 * 유효 여부의 판정은 서버가 소유한다. 화면이 스스로 「아직 유효하다」고 정하지 않으며, 서버가
 * 거부하면 그 판정에 화면을 맞춘다.
 */
export function AdminSessionStatus({
  unlocked,
  remainingLabel,
  onReauthenticate,
  scopeLabel,
}: Props) {
  return (
    <div
      data-testid="admin-session-status"
      className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-gray-200 bg-white px-4 py-3"
    >
      <div className="flex min-w-0 flex-col gap-1">
        {unlocked ? (
          <span
            className="flex items-center gap-1.5 text-label font-medium text-primary-700"
            data-testid="admin-session-remaining"
          >
            <Unlock className="h-4 w-4 shrink-0" aria-hidden="true" />
            관리자 확인됨 — {remainingLabel} 남음
          </span>
        ) : (
          <span className="flex items-center gap-1.5 text-label font-medium text-gray-700">
            <Lock className="h-4 w-4 shrink-0" aria-hidden="true" />
            관리자 확인 필요 — 조회만 가능합니다
          </span>
        )}
        <span className="text-caption text-gray-600">
          {scopeLabel ? `${scopeLabel} ` : ''}관리자 확인은 저장에만 더해지며 역할을 바꾸지
          않습니다. 검수자 권한은 그대로 필요합니다.
        </span>
      </div>
      <Button type="button" variant="secondary" size="sm" onClick={onReauthenticate}>
        {unlocked ? '관리자 다시 확인' : '관리자 확인'}
      </Button>
    </div>
  );
}
