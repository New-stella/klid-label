/**
 * 포털 업로드 자산 상태 배지 — 알약 + **다음에 무슨 일이 일어나는지** 한 줄.
 *
 * <h3>배지 하나로 끝내지 않는 이유</h3>
 * `마킹 대기`·`처리중` 은 이용자가 지금 아무것도 할 수 없는 상태다. 배지만 있으면 기다려야 하는지
 * 잘못된 것인지 알 수 없어, 상태 아래에 다음 일을 한 줄로 덧붙인다.
 *
 * ★**`UPLOADED` 는 「마킹 대기」다** — 마킹을 마쳐야 그 지점으로 프레임이 추출되므로, 이 자리에서
 *   알려야 할 것은 업로드가 끝났다는 사실이 아니라 다음에 무엇을 해야 하는가이다. 표기만 그렇게
 *   하고 상태값 자체는 바뀌지 않는다. [@design SCREEN-033]
 *   ⚠ 시안(SD-026)은 이 자리를 「업로드됨 / 처리 차례를 기다립니다」로 그렸으나 그 시안은 골격
 *     v11 기준이라 마킹 단계(2026-09-02 신설) 이전이다. **골격 v38 을 따른다** — 시안으로
 *     되돌리면 확정 사양이 뒤집힌다.
 *
 * ★**색만으로 구분하지 않는다** — 배지마다 한글 라벨이 있고, 실패에는 아이콘이 함께 붙는다.
 *
 * @design DS-002
 * @design SCREEN-033
 */

import { CircleAlert } from 'lucide-react';

import { cn } from '@/lib/cn';
import { PortalUploadStatus } from '@/features/portal/uploads/types';

/** 상태값 → 표기·설명. 상태값 자체는 서버 계약이라 바꾸지 않는다. */
const STATUS = {
  [PortalUploadStatus.UPLOADED]: {
    label: '마킹 대기',
    sub: '마킹을 마치면 그 지점으로 프레임을 뽑습니다',
    skin: 'border-gray-300 bg-gray-100 text-gray-800',
  },
  [PortalUploadStatus.PROCESSING]: {
    label: '처리중',
    sub: '프레임을 뽑는 중입니다',
    skin: 'border-info-200 bg-info-50 text-info-700',
  },
  [PortalUploadStatus.READY]: {
    label: '준비 완료',
    sub: undefined,
    skin: 'border-success-200 bg-success-50 text-success-700',
  },
  [PortalUploadStatus.FAILED]: {
    label: '실패',
    sub: '지운 뒤 다시 올려 주세요',
    skin: 'border-danger-200 bg-danger-50 text-danger-700',
  },
} as const satisfies Record<string, { label: string; sub?: string; skin: string }>;

/** 알 수 없는 상태값도 자리를 비우지 않는다 — 값 자체를 보여 주는 편이 «빈 칸» 보다 낫다. */
const UNKNOWN = {
  sub: undefined,
  skin: 'border-gray-300 bg-gray-100 text-gray-800',
} as const;

interface PortalUploadStatusBadgeProps {
  status: string;
  /** 목록 행처럼 설명 한 줄이 들어갈 자리가 있을 때만 켠다. */
  withSub?: boolean;
  className?: string;
}

export function PortalUploadStatusBadge({
  status,
  withSub = false,
  className,
}: PortalUploadStatusBadgeProps) {
  const known = STATUS[status as keyof typeof STATUS] as
    | { label: string; sub?: string; skin: string }
    | undefined;
  const label = known?.label ?? status;
  const sub = known?.sub ?? UNKNOWN.sub;
  const skin = known?.skin ?? UNKNOWN.skin;
  const isFailed = status === PortalUploadStatus.FAILED;

  return (
    <span className={cn('flex flex-col items-start gap-tight', className)}>
      <span
        className={cn(
          'inline-flex items-center gap-1 rounded-pill border px-2.5 py-0.5 text-caption font-medium',
          skin,
        )}
      >
        {isFailed && <CircleAlert className="size-3.5" strokeWidth={2} aria-hidden />}
        {label}
      </span>
      {withSub && sub !== undefined && (
        <span className="text-caption text-pretty text-gray-500">{sub}</span>
      )}
    </span>
  );
}

/**
 * 상태가 알리는 **다음에 무슨 일이 일어나는가** 한 줄 — 배지와 따로 놓을 때 쓴다.
 *
 * 행 카드에서는 배지가 제목 옆에 서고 이 줄은 본문으로 내려간다. 배지 옆에 붙이면 제목 줄이
 * 길어져 파일명과 자리를 다툰다. 문구의 단일 원천은 위 {@link STATUS} 이며 여기서 복제하지 않는다.
 *
 * 할 말이 없는 상태(준비 완료)에서는 **아무것도 그리지 않는다** — 빈 줄이 남으면 카드마다
 * 높이가 어긋난 이유를 알 수 없다.
 */
export function PortalUploadStatusNote({ status }: { status: string }) {
  const known = STATUS[status as keyof typeof STATUS] as { sub?: string } | undefined;
  const sub = known?.sub;
  if (sub === undefined) return null;
  return <span className="text-body-sm text-pretty text-gray-600">{sub}</span>;
}
