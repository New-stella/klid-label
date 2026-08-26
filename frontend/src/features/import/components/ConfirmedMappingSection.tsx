import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { Pagination } from '@/components/common/Pagination';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { Skeleton } from '@/components/common/Skeleton';

import { useDisableImportMapping, useImportMappings } from '../hooks/useImportMappings';
import { MappingKind, type ImportMapping } from '../types';

/** 표 헤더 셀 — DS-001 표 표면 관례(14px/600 토큰 + 대문자화). th 에 직접 건다. */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

const PAGE_SIZE = 20;

/** 대응 종류 필터 — 값이 빈 문자열이면 둘 다. */
const KIND_FILTERS = [
  { value: '', label: '전체' },
  { value: MappingKind.LABEL, label: '라벨' },
  { value: MappingKind.EVNT_TYPE, label: '이벤트 유형' },
] as const;

const KIND_LABEL: Record<MappingKind, string> = {
  [MappingKind.LABEL]: '라벨',
  [MappingKind.EVNT_TYPE]: '이벤트 유형',
};

/**
 * 연결된 대상 표시 — 라벨 축은 라벨 이름, 이벤트 축은 유형 코드다.
 *
 * 연결이 비어 있거나 라벨 마스터에서 이름을 찾지 못한 행도 감추지 않는다. 감추면 왜 그 분류가
 * 계속 처음 보는 분류로 나오는지 알 수 없게 된다.
 */
function targetTextOf(mapping: ImportMapping): string {
  if (mapping.kind === MappingKind.LABEL) {
    if (mapping.labelName) return mapping.labelName;
    return mapping.labelId === null ? '미연결' : `미연결 (라벨 ${mapping.labelId})`;
  }
  return mapping.evntTypeCd ?? '미연결';
}

export interface ConfirmedMappingSectionProps {
  onDisabled?: () => void;
}

/**
 * 확정된 대응 — 조회하고 잘못 걸린 것을 해제한다.
 *
 * ★해제에는 확인 단계를 둔다. 되돌리면 그 분류가 다시 처음 보는 분류가 되어 다음 산출물을
 * 가져올 때 사람이 다시 확정하기 전까지 **적재가 막힌다** — 파괴적 조작에 준한다. 확인 문구는
 * 무엇이 되돌려지는지와 그 결과를 함께 말한다.
 *
 * 해제해도 행을 지우지 않고 쓰지 않음으로 표시하며, 이미 그 대응으로 적재된 라벨은 그대로 둔다.
 *
 * @design SCREEN-039
 * @design API-209
 * @design API-211
 */
export function ConfirmedMappingSection({ onDisabled }: ConfirmedMappingSectionProps) {
  const [kind, setKind] = useState<string>('');
  const [includeUnused, setIncludeUnused] = useState(false);
  const [page, setPage] = useState(0);
  /** 해제 확인 대상 — null 이면 확인 단계가 닫혀 있다. */
  const [pendingDisable, setPendingDisable] = useState<ImportMapping | null>(null);

  const params = {
    ...(kind ? { kind: kind as MappingKind } : {}),
    includeUnused,
    page,
    size: PAGE_SIZE,
  };
  const { data, isLoading, error } = useImportMappings(params);

  const { mutate: disable, isPending } = useDisableImportMapping({
    onSuccess: () => {
      setPendingDisable(null);
      onDisabled?.();
    },
    onError: () => setPendingDisable(null),
  });

  const rows = data?.items ?? [];
  const totalPages = Math.max(1, data?.totalPages ?? 1);
  const currentPage = data?.page ?? page;

  return (
    <section
      aria-labelledby="import-mappings-heading"
      data-testid="import-mappings-section"
      className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="import-mappings-heading" className="text-title-sm text-gray-900">
        확정된 분류 대응
      </h2>

      <div className="flex flex-wrap items-center gap-4">
        <div className="flex items-center gap-2">
          <label className="text-label font-medium text-gray-700" htmlFor="import-mapping-kind">
            대응 종류
          </label>
          <Select
            value={kind}
            onValueChange={(v) => {
              setKind(v);
              setPage(0);
            }}
          >
            <SelectTrigger id="import-mapping-kind" size="sm">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {KIND_FILTERS.map((o) => (
                <SelectItem key={o.value} value={o.value}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        {/* `Checkbox` 의 실체는 role=checkbox 인 button 이라 `<label for>` 로는 이름이 붙지 않는다
            (button 의 이름은 aria-labelledby → aria-label → 자기 서브트리 순으로 계산된다).
            클릭 토글을 위해 htmlFor 는 그대로 두고, 이름은 aria-labelledby 로 잇는다. */}
        <div className="flex items-center gap-2">
          <Checkbox
            id="import-mapping-include-unused"
            data-testid="import-mapping-include-unused"
            aria-labelledby="import-mapping-include-unused-label"
            checked={includeUnused}
            onCheckedChange={(v) => {
              setIncludeUnused(v === true);
              setPage(0);
            }}
          />
          <label
            id="import-mapping-include-unused-label"
            className="text-label font-medium text-gray-700"
            htmlFor="import-mapping-include-unused"
          >
            쓰지 않게 표시한 대응까지 보기
          </label>
        </div>

        <span className="ml-auto inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600">
          {data?.totalElements ?? 0}건
        </span>
      </div>

      {isLoading && (
        <div className="space-y-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} height={40} />
          ))}
        </div>
      )}

      {error && <ErrorState title="분류 대응을 불러올 수 없습니다" />}

      {data && rows.length === 0 && <EmptyState message="확정된 분류 대응이 없습니다." />}

      {data && rows.length > 0 && (
        <div className="overflow-hidden rounded-lg border border-gray-200">
          <table className="w-full text-body-md" data-testid="import-mapping-table">
            <thead>
              <tr className="border-b border-gray-200 bg-secondary-50">
                <th className={TH_CLASS}>종류</th>
                {/*
                  외부 분류 코드와 외부 표시 이름은 두 열로 유지한다 — 이벤트 축은 코드가 없어
                  두 열이 같은 값을 보이지만 그것은 그 축의 사실이고, 라벨 축은 영문 코드와
                  표시 이름이 실제로 달라 열을 합치면 라벨 축이 손해를 본다.
                */}
                <th className={TH_CLASS}>외부 분류 코드</th>
                <th className={TH_CLASS}>외부 표시 이름</th>
                <th className={TH_CLASS}>연결된 대상</th>
                <th className={TH_CLASS}>사용 여부</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr
                  key={m.mpngSn}
                  data-testid={`import-mapping-row-${m.mpngSn}`}
                  className="border-b border-gray-100 transition-colors hover:bg-rowHover"
                >
                  <td className="px-3 py-2 text-gray-700">{KIND_LABEL[m.kind]}</td>
                  <td className="px-3 py-2 text-gray-700">
                    <span className="font-mono text-mono">{m.externalCode}</span>
                  </td>
                  <td className="px-3 py-2 text-gray-700">{m.externalName ?? '-'}</td>
                  <td className="px-3 py-2 text-gray-700">{targetTextOf(m)}</td>
                  <td className="px-3 py-2">
                    {m.useYn === 'Y' ? (
                      <div className="flex items-center gap-2">
                        <span className="inline-flex items-center rounded-full bg-success/10 px-2 py-0.5 text-label font-medium text-success-700">
                          사용
                        </span>
                        <Button
                          size="sm"
                          variant="danger"
                          data-testid={`import-mapping-disable-${m.mpngSn}`}
                          disabled={isPending}
                          onClick={() => setPendingDisable(m)}
                        >
                          해제
                        </Button>
                      </div>
                    ) : (
                      <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600">
                        쓰지 않음
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {totalPages > 1 && (
            <div className="border-t border-gray-200 bg-gray-50 px-3 py-2">
              <Pagination page={currentPage} totalPages={totalPages} onChange={setPage} />
            </div>
          )}
        </div>
      )}

      {/* 해제는 확인 단계를 거쳐야 성립한다 — 되돌리면 그 분류의 적재가 막힌다. */}
      <ConfirmDialog
        open={pendingDisable !== null}
        variant="danger"
        confirmLabel="해제"
        loading={isPending}
        title="이 대응을 해제하시겠습니까?"
        description={
          pendingDisable
            ? `해제하면 '${pendingDisable.externalCode}' 분류가 다시 처음 보는 분류가 됩니다. ` +
              '다음 산출물을 가져올 때 사람이 다시 확정하기 전까지 적재가 막힙니다.'
            : undefined
        }
        onCancel={() => setPendingDisable(null)}
        onConfirm={() => {
          if (pendingDisable) disable(pendingDisable.mpngSn);
        }}
      />
    </section>
  );
}
