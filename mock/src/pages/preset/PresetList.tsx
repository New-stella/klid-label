import { useState } from 'react';
import { Layers, Plus, Pencil, Trash2 } from 'lucide-react';
import { useFetch, useMutation } from '../../api/queries';
import { api } from '../../api/client';
import type { Page, PresetDto } from '../../api/types';
import { Button } from '../../components/ui/Button';
import { Badge } from '../../components/ui/Badge';
import { Pagination } from '../../components/ui/Pagination';
import { Skeleton } from '../../components/ui/Skeleton';
import { Modal } from '../../components/ui/Modal';
import { useToast } from '../../components/common/Toast';
import { PresetFormModal } from '../../components/preset/PresetFormModal';

interface PresetFormData {
  name: string;
  description: string;
  labelCodes: string[];
}

const PAGE_SIZE = 10;

export function PresetList() {
  const { showToast } = useToast();

  const [page, setPage] = useState(0);
  const [formOpen, setFormOpen] = useState(false);
  const [editPreset, setEditPreset] = useState<PresetDto | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<PresetDto | null>(null);
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);

  const { data, isLoading, refetch } = useFetch<Page<PresetDto>>('/presets', {
    page,
    size: PAGE_SIZE,
  });

  const { mutate: createPreset } = useMutation<PresetFormData, PresetDto>(
    (body) => api.post<PresetDto>('/presets', body),
  );

  const { mutate: updatePreset } = useMutation<{ id: string; data: PresetFormData }, PresetDto>(
    ({ id, data: body }) => api.put<PresetDto>(`/presets/${id}`, body),
  );

  const { mutate: deletePreset, isLoading: deleting } = useMutation<string, unknown>(
    (id) => api.delete(`/presets/${id}`),
  );

  const handleCreate = async (formData: PresetFormData) => {
    await createPreset(formData);
    showToast('프리셋이 생성되었습니다.', 'success');
    refetch();
  };

  const handleUpdate = async (formData: PresetFormData) => {
    if (!editPreset) return;
    await updatePreset({ id: editPreset.id, data: formData });
    showToast('프리셋이 수정되었습니다.', 'success');
    refetch();
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    await deletePreset(deleteTarget.id);
    showToast(`"${deleteTarget.name}" 프리셋이 삭제되었습니다.`, 'info');
    setDeleteConfirmOpen(false);
    setDeleteTarget(null);
    refetch();
  };

  const openEdit = (preset: PresetDto) => {
    setEditPreset(preset);
    setFormOpen(true);
  };

  const openCreate = () => {
    setEditPreset(null);
    setFormOpen(true);
  };

  const openDeleteConfirm = (preset: PresetDto) => {
    setDeleteTarget(preset);
    setDeleteConfirmOpen(true);
  };

  const presets = data?.content ?? [];

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-orange-50">
            <Layers size={20} className="text-orange-600" />
          </div>
          <div>
            <h1 className="text-xl font-bold text-gray-900">프리셋 관리</h1>
            {data && (
              <p className="text-xs text-gray-400 mt-0.5">
                전체 {data.totalElements}개
              </p>
            )}
          </div>
        </div>
        <Button
          variant="primary"
          size="md"
          leftIcon={Plus}
          onClick={openCreate}
        >
          새 프리셋
        </Button>
      </div>

      {/* Preset cards */}
      {isLoading ? (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {Array.from({ length: 4 }).map((_, i) => <Skeleton key={i} height="10rem" />)}
        </div>
      ) : presets.length === 0 ? (
        <div className="bg-white border border-gray-200 rounded-lg p-12 text-center">
          <Layers size={40} className="mx-auto text-gray-300 mb-3" />
          <p className="text-gray-500 text-sm">프리셋이 없습니다.</p>
          <p className="text-gray-400 text-xs mt-1">새 프리셋을 만들어 라벨링 작업에 활용하세요.</p>
          <Button
            variant="primary"
            size="md"
            leftIcon={Plus}
            className="mt-4"
            onClick={openCreate}
          >
            새 프리셋 만들기
          </Button>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {presets.map((preset) => (
            <div
              key={preset.id}
              className="bg-white border border-gray-200 rounded-lg p-5 space-y-3 hover:border-primary-300 transition-colors"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="min-w-0">
                  <h3 className="font-semibold text-gray-900 text-sm">{preset.name}</h3>
                  {preset.description && (
                    <p className="text-xs text-gray-400 mt-0.5 truncate">{preset.description}</p>
                  )}
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <Button
                    variant="ghost"
                    size="sm"
                    leftIcon={Pencil}
                    onClick={() => openEdit(preset)}
                    aria-label="수정"
                  >
                    수정
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    leftIcon={Trash2}
                    onClick={() => openDeleteConfirm(preset)}
                    className="text-red-500 hover:text-red-700 hover:bg-red-50"
                    aria-label="삭제"
                  >
                    삭제
                  </Button>
                </div>
              </div>

              {/* Label codes */}
              <div className="flex flex-wrap gap-1.5">
                {preset.labelCodes.map((code) => (
                  <Badge key={code} tone="info" size="sm">
                    {code}
                  </Badge>
                ))}
                <Badge tone="neutral" size="sm">
                  {preset.labelCodes.length}개
                </Badge>
              </div>

              {/* Dates */}
              <div className="flex items-center justify-between text-xs text-gray-400 border-t border-gray-100 pt-2">
                <span>생성: {new Date(preset.createdAt).toLocaleDateString('ko-KR')}</span>
                <span>수정: {new Date(preset.updatedAt).toLocaleDateString('ko-KR')}</span>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Pagination */}
      {data && (
        <Pagination
          page={page}
          totalPages={data.totalPages}
          onChange={setPage}
        />
      )}

      {/* Create / Edit modal */}
      <PresetFormModal
        preset={editPreset}
        open={formOpen}
        onClose={() => setFormOpen(false)}
        onSave={editPreset ? handleUpdate : handleCreate}
      />

      {/* Delete confirm modal */}
      <Modal
        open={deleteConfirmOpen}
        onClose={() => setDeleteConfirmOpen(false)}
        title="프리셋 삭제"
        size="sm"
        footer={
          <>
            <Button
              variant="secondary"
              size="md"
              onClick={() => setDeleteConfirmOpen(false)}
              disabled={deleting}
            >
              취소
            </Button>
            <Button
              variant="danger"
              size="md"
              loading={deleting}
              onClick={() => { void handleDelete(); }}
            >
              삭제
            </Button>
          </>
        }
      >
        <p className="text-sm text-gray-600">
          <span className="font-semibold text-gray-900">"{deleteTarget?.name}"</span> 프리셋을
          삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
        </p>
      </Modal>
    </div>
  );
}

export default PresetList;
