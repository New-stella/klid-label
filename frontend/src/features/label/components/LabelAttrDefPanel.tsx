import { useMemo, useState } from 'react';
import { Pencil, Plus, Trash2 } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { Skeleton } from '@/components/common/Skeleton';
import { type LabelAttrDef } from '@/features/label/api/labelAttr';
import {
  useCreateLabelAttr,
  useDeleteLabelAttr,
  useLabelAttrs,
  useUpdateLabelAttr,
} from '@/features/label/hooks/useLabelAttrs';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { useUiStore } from '@/stores/useUiStore';

import {
  INPUT_TYPE_LABEL,
  LabelAttrFormModal,
  emptyForm,
  hasChoices,
  parseValues,
  toForm,
  toUpsert,
  validate,
  type AttrForm,
  type AttrFormErrors,
} from './LabelAttrFormModal';

/**
 * 선택된 라벨 마스터의 속성 정의 목록 + 추가 / 수정 / 삭제 패널 — REVIEWER 전용.
 *
 * 진입 자체가 `/manage/labels`(RoleGuard=internalReviewerOnly) 하위이므로 라우트 게이트를
 * 상속한다(추가 인가 불필요). 요청 본문은 LabelAttrUpsert(허용 필드만) — Mass Assignment 방어.
 *
 * 보안(valuesJson XSS, CWE-79):
 * - 선택 항목 값은 React 기본 escape 로 텍스트 렌더(dangerouslySetInnerHTML 미사용).
 * - valuesJson 파싱은 try/catch(parseValues) 로 안전 폴백(파싱 실패 시 빈 목록, 크래시 없음).
 */

interface LabelAttrDefPanelProps {
  labelId: number;
  labelName: string;
}

export function LabelAttrDefPanel({ labelId, labelName }: LabelAttrDefPanelProps) {
  const { data, isLoading, error } = useLabelAttrs(labelId);
  const createMutation = useCreateLabelAttr(labelId);
  const updateMutation = useUpdateLabelAttr(labelId);
  const deleteMutation = useDeleteLabelAttr(labelId);
  const pushToast = useUiStore((s) => s.pushToast);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<LabelAttrDef | null>(null);
  const [form, setForm] = useState<AttrForm>(emptyForm(1));
  const [errors, setErrors] = useState<AttrFormErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<LabelAttrDef | null>(null);

  const rows = useMemo(
    () => [...(data ?? [])].sort((a, b) => a.sortNo - b.sortNo || a.attrId - b.attrId),
    [data],
  );
  const nextSortNo = useMemo(() => rows.reduce((m, r) => Math.max(m, r.sortNo), 0) + 1, [rows]);

  const patchForm = (patch: Partial<AttrForm>) => setForm((prev) => ({ ...prev, ...patch }));

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm(nextSortNo));
    setErrors({});
    setSubmitError(null);
    setModalOpen(true);
  };

  const openEdit = (a: LabelAttrDef) => {
    setEditing(a);
    setForm(toForm(a));
    setErrors({});
    setSubmitError(null);
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditing(null);
  };

  const handleSubmit = () => {
    setSubmitError(null);
    const nextErrors = validate(form);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    const body = toUpsert(form);
    if (editing) {
      updateMutation.mutate(
        { attrId: editing.attrId, body },
        {
          onSuccess: () => {
            pushToast({ variant: 'success', message: '속성을 수정했습니다.' });
            closeModal();
          },
          onError: (err) => setSubmitError(resolveApiMessage(err, '속성 수정에 실패했습니다.')),
        },
      );
    } else {
      createMutation.mutate(body, {
        onSuccess: () => {
          pushToast({ variant: 'success', message: '속성을 추가했습니다.' });
          closeModal();
        },
        onError: (err) => setSubmitError(resolveApiMessage(err, '속성 추가에 실패했습니다.')),
      });
    }
  };

  const handleConfirmDelete = () => {
    const target = pendingDelete;
    if (!target) return;
    deleteMutation.mutate(target.attrId, {
      onSuccess: () => pushToast({ variant: 'success', message: '속성을 삭제했습니다.' }),
      onError: (err) =>
        pushToast({ variant: 'error', message: resolveApiMessage(err, '속성 삭제에 실패했습니다.') }),
    });
    setPendingDelete(null);
  };

  const submitting = createMutation.isPending || updateMutation.isPending;

  return (
    <section
      className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-4"
      aria-label={`${labelName} 속성 정의`}
    >
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-gray-900">
          <span className="text-primary-600">{labelName}</span> 속성 정의
        </h3>
        <Button variant="outline" size="sm" onClick={openCreate}>
          <Plus className="mr-1 h-4 w-4" aria-hidden />
          속성 추가
        </Button>
      </div>

      {error ? (
        <ErrorState title="속성 정의를 불러올 수 없습니다" />
      ) : isLoading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} height="2.5rem" />
          ))}
        </div>
      ) : rows.length === 0 ? (
        <p className="rounded-md border border-dashed border-gray-200 py-8 text-center text-sm text-gray-500">
          등록된 속성이 없습니다. 이 라벨에 적용할 속성을 추가하세요.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-gray-200 text-xs font-semibold uppercase tracking-wide text-gray-500">
                <th scope="col" className="px-3 py-2">속성명</th>
                <th scope="col" className="px-3 py-2">입력 형식</th>
                <th scope="col" className="px-3 py-2">선택 항목</th>
                <th scope="col" className="px-3 py-2">기본값</th>
                <th scope="col" className="px-3 py-2">수정 가능</th>
                <th scope="col" className="px-3 py-2 text-right">관리</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((a) => (
                <tr
                  key={a.attrId}
                  data-testid={`label-attr-row-${a.attrId}`}
                  className="border-b border-gray-100 last:border-b-0 hover:bg-gray-50"
                >
                  <td className="px-3 py-2 font-medium text-gray-900">{a.name}</td>
                  <td className="px-3 py-2 text-gray-600">{INPUT_TYPE_LABEL[a.inputType]}</td>
                  <td className="max-w-[16rem] truncate px-3 py-2 text-gray-600">
                    {hasChoices(a.inputType) ? parseValues(a.valuesJson).join(', ') || '—' : '—'}
                  </td>
                  <td className="px-3 py-2 text-gray-600">{a.defaultVal || '—'}</td>
                  <td className="px-3 py-2 text-gray-600">{a.mutable === 'Y' ? '가능' : '고정'}</td>
                  <td className="px-3 py-2">
                    <div className="flex items-center justify-end gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => openEdit(a)}
                        aria-label={`${a.name} 속성 수정`}
                      >
                        <Pencil className="mr-1 h-3.5 w-3.5" aria-hidden />
                        수정
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setPendingDelete(a)}
                        aria-label={`${a.name} 속성 삭제`}
                        className="text-danger hover:bg-danger/10"
                      >
                        <Trash2 className="mr-1 h-3.5 w-3.5" aria-hidden />
                        삭제
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <LabelAttrFormModal
        open={modalOpen}
        isEditing={!!editing}
        form={form}
        errors={errors}
        submitError={submitError}
        submitting={submitting}
        onClose={closeModal}
        onSubmit={handleSubmit}
        onPatch={patchForm}
      />

      <ConfirmDialog
        open={!!pendingDelete}
        title="속성 삭제"
        description={pendingDelete ? `"${pendingDelete.name}" 속성을 삭제하시겠습니까?` : ''}
        variant="danger"
        confirmLabel="삭제"
        loading={deleteMutation.isPending}
        onConfirm={handleConfirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </section>
  );
}
