import { useMemo, useState } from 'react';
import { Layers, Pencil, Plus, Trash2 } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination } from '@/components/common/Pagination';
import { Skeleton } from '@/components/common/Skeleton';
import { useEventTypes } from '@/features/eventType/hooks';
import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import { usePresetActions } from '@/features/preset/hooks/usePresetActions';
import { usePresets } from '@/features/preset/hooks/usePresets';
import { type Preset, type PresetForm } from '@/features/preset/types';
import { ApiError } from '@/lib/api/errors';
import { Role } from '@/lib/api/types';
import { useAuthStore } from '@/stores/useAuthStore';
import { useUiStore } from '@/stores/useUiStore';

const PAGE_SIZE = 10;

/**
 * SCR-MANAGE-PRESETS 라벨링 프리셋 관리 (V1.x mock 시각 정합) — REVIEWER 전용.
 *
 * - 2열 카드 그리드 + 클라이언트 페이지네이션 (10건/페이지)
 * - 프리셋 추가 / 수정 / 복사 / 삭제 (REVIEWER만)
 * - 라벨 항목 최대 6종 제한 (zod presetSchema)
 */
export function PresetListPage() {
  const { data, isLoading, error } = usePresets();
  // 프리셋은 categoryKey 를 eventTypeCd 로 저장하므로 categoryKey→label 맵으로 해석한다
  // (EV-코드 맵 useEventTypeLabels 는 categoryKey 를 못 풀어 원문 노출되는 회귀가 있었음).
  const { data: eventTypeOptions } = useEventTypes();
  const catLabel = useMemo(
    () => new Map((eventTypeOptions ?? []).map((o) => [o.categoryKey, o.label])),
    [eventTypeOptions],
  );
  const { create, update, remove } = usePresetActions();
  const pushToast = useUiStore((s) => s.pushToast);
  const role = useAuthStore((s) => s.claims?.role);
  const isReviewer = role === Role.REVIEWER;

  const [editing, setEditing] = useState<Preset | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<Preset | null>(null);
  const [page, setPage] = useState(0);

  const openCreate = () => {
    setEditing(null);
    setModalOpen(true);
  };
  const openEdit = (p: Preset) => {
    setEditing(p);
    setModalOpen(true);
  };

  /** 409 CONFLICT 응답이면 BE 메시지를 그대로 사용자에게 노출 (이벤트 중복 / 이름 중복 분기). */
  const resolveErrorMessage = (err: unknown, fallback: string): string => {
    if (err instanceof ApiError && err.status === 409) {
      return err.userMessage || fallback;
    }
    return fallback;
  };

  const handleSubmit = (form: PresetForm) => {
    if (editing) {
      update.mutate(
        { id: editing.id, form },
        {
          onSuccess: () => {
            pushToast({ variant: 'success', message: '프리셋을 수정했습니다' });
            setModalOpen(false);
          },
          onError: (err) =>
            pushToast({
              variant: 'error',
              message: resolveErrorMessage(err, '프리셋 수정에 실패했습니다'),
            }),
        },
      );
    } else {
      create.mutate(form, {
        onSuccess: () => {
          pushToast({ variant: 'success', message: '프리셋을 추가했습니다' });
          setModalOpen(false);
        },
        onError: (err) =>
          pushToast({
            variant: 'error',
            message: resolveErrorMessage(err, '프리셋 추가에 실패했습니다'),
          }),
      });
    }
  };

  const handleConfirmDelete = () => {
    if (!pendingDelete) return;
    remove.mutate(pendingDelete.id, {
      onSuccess: () =>
        pushToast({ variant: 'success', message: '프리셋을 삭제했습니다' }),
      onError: () =>
        pushToast({ variant: 'error', message: '프리셋 삭제에 실패했습니다' }),
    });
    setPendingDelete(null);
  };

  const presets = useMemo(() => data ?? [], [data]);
  const totalElements = presets.length;
  const totalPages = Math.max(1, Math.ceil(totalElements / PAGE_SIZE));
  const safePage = Math.min(page, totalPages - 1);
  const pageItems = useMemo(
    () => presets.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE),
    [presets, safePage],
  );

  const submitting = create.isPending || update.isPending;

  const formatDate = (s?: string) => {
    if (!s) return '-';
    const d = new Date(s);
    return Number.isNaN(d.getTime()) ? '-' : d.toLocaleDateString('ko-KR');
  };

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title={
          <span className="inline-flex items-center gap-2">
            <span className="inline-flex items-center justify-center rounded-lg bg-orange-50 p-2">
              <Layers className="h-5 w-5 text-orange-500" aria-hidden />
            </span>
            <span>프리셋 관리</span>
          </span>
        }
        description={`라벨 코드 프리셋 관리 — 전체 ${totalElements.toLocaleString('ko-KR')}개`}
        actions={
          isReviewer ? (
            <Button variant="primary" onClick={openCreate}>
              <Plus className="mr-1 h-4 w-4" aria-hidden />
              프리셋 추가
            </Button>
          ) : undefined
        }
      />

      {error && <ErrorState title="프리셋 목록을 불러올 수 없습니다" />}

      {isLoading ? (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} height="10rem" />
          ))}
        </div>
      ) : totalElements === 0 ? (
        <div className="rounded-lg border border-gray-200 bg-white p-12 text-center">
          <Layers size={40} className="mx-auto mb-3 text-gray-300" aria-hidden />
          <p className="text-sm text-gray-500">등록된 프리셋이 없습니다.</p>
          <p className="mt-1 text-xs text-gray-400">
            새 프리셋을 만들어 라벨링 작업에 활용하세요.
          </p>
          {isReviewer && (
            <Button variant="primary" className="mt-4" onClick={openCreate}>
              <Plus className="mr-1 h-4 w-4" aria-hidden />
              새 프리셋 만들기
            </Button>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {pageItems.map((preset) => {
            const labelCodes = preset.labelCodes;
            return (
              <div
                key={preset.id}
                className="space-y-3 rounded-lg border border-gray-200 bg-white p-5 transition-colors hover:border-primary-300"
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-1.5">
                      <h3 className="text-sm font-semibold text-gray-900">{preset.name}</h3>
                      {preset.eventTypeCd ? (
                        <span
                          className="inline-flex items-center rounded-full bg-orange-50 px-2 py-0.5 text-[10px] font-medium text-orange-700"
                          data-testid={`preset-event-${preset.id}`}
                          title={`매핑 이벤트: ${preset.eventTypeCd}`}
                        >
                          {catLabel.get(preset.eventTypeCd) ?? preset.eventTypeCd}
                        </span>
                      ) : (
                        <span
                          className="inline-flex items-center rounded-full bg-gray-50 px-2 py-0.5 text-[10px] font-medium text-gray-400"
                          data-testid={`preset-event-${preset.id}`}
                        >
                          미매핑
                        </span>
                      )}
                    </div>
                    {preset.description && (
                      <p className="mt-0.5 truncate text-xs text-gray-400">
                        {preset.description}
                      </p>
                    )}
                  </div>
                  {isReviewer && (
                    <div className="flex shrink-0 items-center gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => openEdit(preset)}
                        aria-label="수정"
                      >
                        <Pencil className="mr-1 h-3.5 w-3.5" aria-hidden />
                        수정
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setPendingDelete(preset)}
                        aria-label="삭제"
                        className="text-danger hover:bg-red-50"
                      >
                        <Trash2 className="mr-1 h-3.5 w-3.5" aria-hidden />
                        삭제
                      </Button>
                    </div>
                  )}
                </div>

                {/* Label codes */}
                <div className="flex flex-wrap gap-1.5">
                  {labelCodes.map((code) => (
                    <span
                      key={code}
                      className="inline-flex items-center rounded-full bg-primary-50 px-2 py-0.5 text-xs font-medium text-primary-700"
                    >
                      {code}
                    </span>
                  ))}
                  <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600 tabular-nums">
                    {labelCodes.length}개
                  </span>
                </div>

                {/* Dates */}
                <div className="flex items-center justify-between border-t border-gray-100 pt-2 text-xs text-gray-400">
                  <span>생성: {formatDate(preset.createdAt)}</span>
                  <span>수정: {formatDate(preset.updatedAt)}</span>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {totalElements > 0 && (
        <Pagination
          page={safePage}
          size={PAGE_SIZE}
          totalElements={totalElements}
          onPageChange={setPage}
        />
      )}

      <PresetEditModal
        open={modalOpen}
        onClose={() => setModalOpen(false)}
        initial={editing ?? undefined}
        onSubmit={handleSubmit}
        submitting={submitting}
      />

      <ConfirmDialog
        open={!!pendingDelete}
        title="프리셋 삭제"
        description={
          pendingDelete
            ? `"${pendingDelete.name}" 프리셋을 삭제하시겠습니까?`
            : ''
        }
        variant="danger"
        confirmLabel="삭제"
        onConfirm={handleConfirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </section>
  );
}
