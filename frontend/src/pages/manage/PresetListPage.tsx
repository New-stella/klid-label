import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { DataTable, type DataTableColumn } from '@/components/common/DataTable';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { PresetEditModal } from '@/features/preset/components/PresetEditModal';
import { usePresetActions } from '@/features/preset/hooks/usePresetActions';
import { usePresets } from '@/features/preset/hooks/usePresets';
import type { PresetForm } from '@/features/preset/schemas';
import type { Preset } from '@/features/preset/types';
import { useUiStore } from '@/stores/useUiStore';

/**
 * SCR-MANAGE-PRESETS 라벨링 프리셋 관리 — REVIEWER 전용.
 *
 * - 프리셋 추가 / 수정 / 복사 / 삭제
 * - 라벨 항목 최대 6종 제한 (zod presetSchema)
 */
export function PresetListPage() {
  const { data, isLoading, error } = usePresets();
  const { create, update, remove, clone } = usePresetActions();
  const pushToast = useUiStore((s) => s.pushToast);

  const [editing, setEditing] = useState<Preset | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<Preset | null>(null);

  const openCreate = () => {
    setEditing(null);
    setModalOpen(true);
  };
  const openEdit = (p: Preset) => {
    setEditing(p);
    setModalOpen(true);
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
          onError: () =>
            pushToast({ variant: 'error', message: '프리셋 수정에 실패했습니다' }),
        },
      );
    } else {
      create.mutate(form, {
        onSuccess: () => {
          pushToast({ variant: 'success', message: '프리셋을 추가했습니다' });
          setModalOpen(false);
        },
        onError: () => pushToast({ variant: 'error', message: '프리셋 추가에 실패했습니다' }),
      });
    }
  };

  const handleClone = (p: Preset) => {
    clone.mutate(p.id, {
      onSuccess: () =>
        pushToast({ variant: 'success', message: `${p.name} 복사본을 만들었습니다` }),
      onError: () => pushToast({ variant: 'error', message: '복사에 실패했습니다' }),
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

  const columns: DataTableColumn<Preset>[] = [
    { key: 'name', header: '프리셋명', render: (p) => p.name },
    { key: 'eventTypeCd', header: '이벤트', render: (p) => p.eventTypeCd },
    { key: 'subType', header: '서브유형', render: (p) => p.subType ?? '-' },
    {
      key: 'items',
      header: '항목 수',
      align: 'right',
      render: (p) => `${p.items.length}`,
    },
    {
      key: 'actions',
      header: '액션',
      render: (p) => (
        <div className="flex gap-1">
          <Button size="sm" variant="ghost" onClick={() => openEdit(p)}>
            수정
          </Button>
          <Button size="sm" variant="ghost" onClick={() => handleClone(p)}>
            복사
          </Button>
          <Button
            size="sm"
            variant="ghost"
            onClick={() => setPendingDelete(p)}
            className="text-danger"
          >
            삭제
          </Button>
        </div>
      ),
    },
  ];

  const submitting = create.isPending || update.isPending;

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title="라벨링 프리셋"
        description="이벤트 유형별 라벨 항목 프리셋 관리 (최대 6항목)"
        actions={
          <Button variant="primary" onClick={openCreate}>
            + 프리셋 추가
          </Button>
        }
      />

      {error && <ErrorState title="프리셋 목록을 불러올 수 없습니다" />}

      <DataTable<Preset>
        columns={columns}
        rows={data ?? []}
        totalElements={data?.length ?? 0}
        page={0}
        size={(data?.length || 1) as number}
        loading={isLoading}
        emptyMessage="등록된 프리셋이 없습니다"
        rowKey={(p) => p.id}
        onPageChange={() => {
          // 페이징 미사용 — 통상 100건 이하
        }}
      />

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
