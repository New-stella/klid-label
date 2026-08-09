import { useMemo, useState } from 'react';
import { Copy, Layers, Pencil, Plus, Trash2 } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Pagination, pageCountOf } from '@/components/common/Pagination';
import { Skeleton } from '@/components/common/Skeleton';
import { useEventTypes } from '@/features/eventType/hooks';
import { PresetCodeChip } from '@/features/preset/components/PresetCodeChip';
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
 * 카드에 그대로 노출하는 라벨 칩 최대 개수 — 초과분은 '+N' 배지로 접는다(사양 SCREEN-026).
 * 편집 모달의 라벨 선택 상한(20)과는 **다른 축**이다 — 이건 표시용 축소일 뿐이다.
 */
const VISIBLE_CHIP_COUNT = 6;

/**
 * SCR-MANAGE-PRESETS 라벨링 프리셋 관리 (V1.x mock 시각 정합) — REVIEWER 전용.
 *
 * - 2열 카드 그리드 + 클라이언트 페이지네이션 (10건/페이지)
 * - 프리셋 추가 / 수정 / 복사 / 삭제 (REVIEWER만)
 * - 라벨 항목 1~20종 제한 (zod presetSchema.labelIds min(1).max(20))
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
  const { create, update, remove, clone } = usePresetActions();
  const pushToast = useUiStore((s) => s.pushToast);
  const role = useAuthStore((s) => s.claims?.role);
  const isReviewer = role === Role.REVIEWER;

  const [editing, setEditing] = useState<Preset | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<Preset | null>(null);
  const [page, setPage] = useState(0);
  /** 라벨 칩을 모두 펼친 프리셋 id 집합 — 기본은 접힘(앞 6개 + '+N'). */
  const [expandedChips, setExpandedChips] = useState<Set<number>>(new Set());

  const toggleChips = (presetId: number) =>
    setExpandedChips((prev) => {
      const next = new Set(prev);
      if (next.has(presetId)) next.delete(presetId);
      else next.add(presetId);
      return next;
    });

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

  /**
   * 복제 — 클릭 즉시 서버에 요청한다(확인 단계 없음, 사양 SCREEN-026).
   * 서버가 이름 끝에 ' (복사본)' 을 붙인 새 프리셋을 새 ID 로 발급하고 매핑 이벤트는 상속하지 않는다.
   *
   * ★API·훅(`usePresetActions().clone`)은 이전부터 있었으나 화면에 버튼이 없어 도달 불가였다.
   */
  const handleClone = (preset: Preset) => {
    clone.mutate(preset.id, {
      onSuccess: () =>
        pushToast({ variant: 'success', message: '프리셋을 복제했습니다' }),
      onError: (err) =>
        pushToast({
          variant: 'error',
          message: resolveErrorMessage(err, '프리셋 복제에 실패했습니다'),
        }),
    });
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
  // 서버 페이징이 아니라 전체 목록을 받아 화면에서 자른다(SCREEN-026) — 페이지 수 계산 규칙은
  // 페이저와 같은 곳(pageCountOf)에서 가져와 0건·나머지 처리가 갈리지 않게 한다.
  const totalPages = pageCountOf(totalElements, PAGE_SIZE);
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
            <span className="inline-flex items-center justify-center rounded-lg bg-primary-50 p-2">
              <Layers className="h-5 w-5 text-primary-600" aria-hidden />
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
          <p className="text-body-md text-gray-500">등록된 프리셋이 없습니다.</p>
          <p className="mt-1 text-caption text-gray-400">
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
            const codes = preset.codes;
            return (
              <div
                key={preset.id}
                className="space-y-3 rounded-lg border border-gray-200 bg-white p-5 transition-colors hover:border-primary-300"
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0">
                    <div className="flex items-center gap-1.5">
                      <h3 className="text-title-sm font-semibold text-gray-900">{preset.name}</h3>
                      {preset.eventTypeCd ? (
                        <span
                          className="inline-flex items-center rounded-full bg-primary-50 px-2 py-0.5 text-[10px] font-medium text-primary-700"
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
                      <p className="mt-0.5 truncate text-caption text-gray-400">
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
                        onClick={() => handleClone(preset)}
                        disabled={clone.isPending}
                        aria-label={`${preset.name} 복제`}
                      >
                        <Copy className="mr-1 h-3.5 w-3.5" aria-hidden />
                        복제
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setPendingDelete(preset)}
                        aria-label="삭제"
                        className="text-danger-700 hover:bg-danger/10"
                      >
                        <Trash2 className="mr-1 h-3.5 w-3.5" aria-hidden />
                        삭제
                      </Button>
                    </div>
                  )}
                </div>

                {/* Label codes — 마스터 라벨명·형태 기준 표시(미연결은 배지 구분).
                    ★앞 6개만 칩으로 보이고 나머지는 '+N' 으로 접힌다(사양 SCREEN-026).
                    '+N' 은 정보 표시용 배지가 아니라 **펼치기 토글**이라 접근 가능한 button 이다 —
                    접기만 하고 펼칠 수단이 없으면 나머지 라벨이 화면에서 도달 불가능해진다. */}
                <div className="flex flex-wrap gap-1.5">
                  {(expandedChips.has(preset.id)
                    ? codes
                    : codes.slice(0, VISIBLE_CHIP_COUNT)
                  ).map((code) => (
                    <PresetCodeChip
                      key={code.labelId ?? code.code ?? code.labelName}
                      code={code}
                    />
                  ))}
                  {codes.length > VISIBLE_CHIP_COUNT && (
                    <button
                      type="button"
                      onClick={() => toggleChips(preset.id)}
                      aria-expanded={expandedChips.has(preset.id)}
                      data-testid={`preset-chip-toggle-${preset.id}`}
                      className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600 tabular-nums hover:bg-gray-200"
                    >
                      {expandedChips.has(preset.id)
                        ? '접기'
                        : `+${codes.length - VISIBLE_CHIP_COUNT}`}
                    </button>
                  )}
                  <span className="inline-flex items-center rounded-full bg-gray-100 px-2 py-0.5 text-label font-medium text-gray-600 tabular-nums">
                    {codes.length}개
                  </span>
                </div>

                {/* Dates */}
                <div className="flex items-center justify-between border-t border-gray-100 pt-2 text-caption text-gray-400">
                  <span>생성: {formatDate(preset.createdAt)}</span>
                  <span>수정: {formatDate(preset.updatedAt)}</span>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {totalElements > 0 && (
        <Pagination page={safePage} totalPages={totalPages} onChange={setPage} />
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
