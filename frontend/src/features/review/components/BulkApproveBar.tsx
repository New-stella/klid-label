// 검수 목록 — 일괄 검수완료 실행줄(SCREEN-018 §일괄 검수완료 실행줄).
//
// 목록 표 바로 위에 두며 **한 건도 고르지 않았으면 보이지 않는다**.
// 검수완료(승인)만 다루고 반려는 두지 않는다 — 반려는 건마다 사유가 달라 묶을 수 없다.
//
// [@design SCREEN-018] [@design API-250] [@design AC-1117]

import { Button } from '@/components/common/Button';

export interface BulkApproveBarProps {
  /** 고른 건수. 0 이면 이 줄 전체를 그리지 않는다. */
  selectedCount: number;
  /**
   * 한 번에 보낼 수 있는 최대 건수 — **서버가 목록 응답에 실어 보낸 값**이다.
   *
   * ★화면이 숫자를 스스로 갖지 않는다(`AC-1117`). 배포 설정값이라 하드코딩하면 설정을 바꿔도
   * 화면만 옛 숫자로 막는다. 아직 못 받았으면 `undefined` 이고 그때는 **막지 않는다** —
   * 실제 강제는 일괄 승인 창구가 그대로 하므로, 모른다고 미리 잠그면 되는 것까지 막힌다.
   */
  limit?: number;
  /** 확인 창을 연다(곧바로 처리하지 않는다). */
  onRequestApprove: () => void;
  /** 고른 것을 모두 푼다. */
  onClearSelection: () => void;
  /** 전송 중 — 두 버튼을 잠근다. */
  isPending?: boolean;
}

export function BulkApproveBar({
  selectedCount,
  limit,
  onRequestApprove,
  onClearSelection,
  isPending = false,
}: BulkApproveBarProps) {
  if (selectedCount <= 0) return null;

  // 상한을 모르면(응답에 아직 없음) 막지 않는다 — 위 prop 문서 참조.
  const overLimit = limit !== undefined && selectedCount > limit;

  return (
    <div
      className="flex flex-wrap items-center gap-3 rounded-lg border border-gray-200 bg-secondary-50 px-4 py-3"
      data-testid="bulk-approve-bar"
    >
      <span className="text-body-md font-medium text-gray-800">
        선택한 {selectedCount.toLocaleString('ko-KR')}건
      </span>

      <Button
        variant="primary"
        size="sm"
        onClick={onRequestApprove}
        disabled={overLimit || isPending}
        data-testid="bulk-approve-run"
      >
        일괄 검수완료
      </Button>

      <Button
        variant="ghost"
        size="sm"
        onClick={onClearSelection}
        disabled={isPending}
        data-testid="bulk-approve-clear"
      >
        선택 해제
      </Button>

      {/* 비활성 사유는 disabled 속성만으로 전달되지 않는다 — 문구로 함께 알린다.
          상한 숫자는 박아 두지 않고 그때그때 받은 값을 보인다(AC-1117).

          ★색 단계는 **이 줄의 배경 위에서** 골랐다. `text-danger`(DEFAULT)는 흰 배경에서는
          4.56:1 로 통과하지만 이 실행줄 배경(`bg-secondary-50`) 위에서는 **4.06:1 로 AA 미달**이다
          — 「값을 회색 표면에 넣어 흰 배경에서 통과하던 대비를 잃는」 이 저장소의 반복 결함이다.
          `danger-700` 은 같은 배경에서 7.98:1. 되돌리지 말 것(대비 가드가 값으로 고정한다). */}
      {overLimit && (
        <p
          className="text-caption text-danger-700"
          role="alert"
          data-testid="bulk-approve-limit-alert"
        >
          한 번에 검수완료할 수 있는 건수({limit?.toLocaleString('ko-KR')}건)를 넘었습니다. 선택을
          줄여 주세요.
        </p>
      )}
    </div>
  );
}
