import { Download, Paperclip, Trash2 } from 'lucide-react';

import { cn } from '@/lib/cn';

import { Button } from './Button';
import { Spinner } from './Spinner';

/**
 * 첨부파일 목록 (UI-112).
 *
 * 한 행 = 클립 아이콘 + 파일명(넘치면 ellipsis) + 파일 크기 + 액션 아이콘 버튼.
 * 액션은 화면에 따라 다운로드(SCREEN-031) 또는 삭제(SCREEN-037) 하나만 노출한다.
 *
 * 접근성:
 * - 파일명이 ellipsis 로 잘려도 전체 이름을 알 수 있게 `title` 을 단다(UI-112 variants.default).
 * - 아이콘 전용 버튼에는 **파일명을 포함한** `aria-label` 을 붙인다 — 목록에 버튼이 여러 개라
 *   "다운로드"만으로는 어느 파일인지 구분되지 않는다.
 * - 진행 중(`status: 'downloading'`) 행은 `aria-busy` + `disabled` + 스피너로 바꿔 재클릭을 막는다.
 */
export interface AttachmentListItem {
  /** 원본 파일명. */
  name: string;
  /** **이미 서식화된** 크기 문자열(예: `340.2 KB`). 바이트는 `formatFileSize` 로 변환해 넘긴다. */
  size: string;
  /** 항목별 진행 상태. 기본 `idle`. */
  status?: 'idle' | 'downloading';
  /** 호출부가 행을 식별할 키. 미지정 시 name + index 로 대체한다. */
  id?: string | number;
}

export interface AttachmentListProps {
  items: AttachmentListItem[];
  /** 행마다 노출할 액션 종류 — SCREEN-031=download, SCREEN-037=delete. */
  action: 'download' | 'delete';
  /** 액션 버튼 클릭 핸들러. 미지정이면 버튼을 비활성으로 그린다. */
  onAction?: (item: AttachmentListItem, index: number) => void;
  /** 첨부 0건일 때 표시할 안내 문구. */
  emptyMessage?: string;
  className?: string;
}

const ACTION_META = {
  download: { icon: Download, verb: '다운로드' },
  delete: { icon: Trash2, verb: '삭제' },
} as const;

export function AttachmentList({
  items,
  action,
  onAction,
  emptyMessage = '첨부파일이 없습니다.',
  className,
}: AttachmentListProps) {
  const { icon: ActionIcon, verb } = ACTION_META[action];

  // empty variant 는 목록과 **배타적**이다(UI-112 variants.empty) — 빈 목록 껍데기를
  // 남기지 않고 안내 카드로 대체한다.
  if (items.length === 0) {
    return (
      <p
        role="status"
        className={cn(
          'rounded-md border border-border bg-gray-50 px-4 py-3 text-body-sm text-gray-600',
          className,
        )}
      >
        {emptyMessage}
      </p>
    );
  }

  return (
    <ul className={cn('divide-y divide-gray-100', className)}>
      {items.map((item, index) => {
        const busy = item.status === 'downloading';
        return (
          <li
            key={item.id ?? `${item.name}-${index}`}
            aria-busy={busy || undefined}
            className="flex items-center gap-2 py-2"
          >
            <Paperclip className="h-4 w-4 shrink-0 text-gray-500" aria-hidden="true" />
            <span
              title={item.name}
              className={cn(
                'min-w-0 flex-1 truncate text-body-sm',
                busy ? 'text-gray-400' : 'text-gray-800',
              )}
            >
              {item.name}
            </span>
            <span
              className={cn('shrink-0 text-caption', busy ? 'text-gray-400' : 'text-gray-500')}
            >
              {item.size}
            </span>
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`${item.name} ${verb}`}
              aria-busy={busy || undefined}
              disabled={busy || !onAction}
              onClick={() => onAction?.(item, index)}
            >
              {busy ? (
                <Spinner size="sm" label={`${item.name} ${verb} 중`} />
              ) : (
                <ActionIcon className="h-4 w-4" aria-hidden="true" />
              )}
            </Button>
          </li>
        );
      })}
    </ul>
  );
}

export default AttachmentList;
