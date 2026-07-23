import { useMemo, useState } from 'react';
import { ListTree, Pencil, Plus, Tags, Trash2 } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { ErrorState } from '@/components/common/ErrorState';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { type LabelMaster, type LabelMasterUpsert } from '@/features/label/api/labelMaster';
import { LabelAttrDefPanel } from '@/features/label/components/LabelAttrDefPanel';
import { TYPE_LABEL } from '@/features/label/constants/labelTypes';
import {
  useCreateLabelMaster,
  useUpdateLabelMaster,
  useDeleteLabelMaster,
} from '@/features/label/hooks/useLabelMasterMutations';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import { resolveApiMessage } from '@/lib/api/resolveApiMessage';
import { useUiStore } from '@/stores/useUiStore';

import {
  LabelMasterFormModal,
  emptyForm,
  toForm,
  validate,
  type FormErrors,
  type LabelMasterForm,
} from './components/LabelMasterFormModal';

/**
 * SCR-MANAGE-LABELS 라벨 마스터(클래스) 관리 — REVIEWER 전용.
 *
 * 진행 범위: 라벨 마스터 목록 + 생성 / 수정 / 삭제 (속성 정의는 다음 Phase).
 *
 * 보안:
 * - REVIEWER 만 진입 (라우트 RoleGuard=internalReviewerOnly) + BE @PreAuthorize 이중 방어.
 * - 요청 본문은 LabelMasterUpsert(허용 필드만) — Mass Assignment(labelId/useYn) 차단(Phase 2 타입).
 * - name/color/type/sortNo 는 클라이언트 사전검증 + BE @Valid 이중 검증.
 * - 라벨명·색상 렌더는 React 기본 escape(XSS 방어), dangerouslySetInnerHTML 미사용.
 * - 삭제는 확인 모달 승인 후에만 실행(비가역 방지).
 * - 라벨 행 선택 시 해당 라벨의 속성 정의 패널(LabelAttrDefPanel)을 하단에 노출(Phase 4).
 */

export function LabelMasterManagePage() {
  const { data, isLoading, error } = useLabelMasters();
  const createMutation = useCreateLabelMaster();
  const updateMutation = useUpdateLabelMaster();
  const deleteMutation = useDeleteLabelMaster();
  const pushToast = useUiStore((s) => s.pushToast);

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<LabelMaster | null>(null);
  const [form, setForm] = useState<LabelMasterForm>(emptyForm(0));
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<LabelMaster | null>(null);
  const [selected, setSelected] = useState<LabelMaster | null>(null);

  // sortNo 오름차순 정렬 (동률은 labelId 오름차순으로 안정 정렬).
  const rows = useMemo(() => {
    return [...(data ?? [])].sort((a, b) => a.sortNo - b.sortNo || a.labelId - b.labelId);
  }, [data]);

  const nextSortNo = useMemo(
    () => rows.reduce((max, r) => Math.max(max, r.sortNo), 0) + 1,
    [rows],
  );

  // 선택 라벨을 최신 목록 기준으로 재조회 — 선택 라벨이 삭제되면 패널 자동 닫힘.
  const selectedLive = useMemo(
    () => (selected ? (rows.find((r) => r.labelId === selected.labelId) ?? null) : null),
    [rows, selected],
  );

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm(nextSortNo));
    setErrors({});
    setSubmitError(null);
    setModalOpen(true);
  };

  const openEdit = (m: LabelMaster) => {
    setEditing(m);
    setForm(toForm(m));
    setErrors({});
    setSubmitError(null);
    setModalOpen(true);
  };

  const closeModal = () => {
    setModalOpen(false);
    setEditing(null);
  };

  const patchForm = (patch: Partial<LabelMasterForm>) => {
    setForm((prev) => ({ ...prev, ...patch }));
  };

  const handleSubmit = () => {
    setSubmitError(null);
    const nextErrors = validate(form);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;

    const body: LabelMasterUpsert = {
      name: form.name.trim(),
      type: form.type,
      color: form.color,
      sortNo: form.sortNo,
      // 미지정(null)이면 매핑 해제. 값 검증(allowlist)·중복 매핑 409 는 BE 가 최종 판정.
      dtctTypeCd: form.dtctTypeCd,
    };

    if (editing) {
      updateMutation.mutate(
        { labelId: editing.labelId, body },
        {
          onSuccess: () => {
            pushToast({ variant: 'success', message: '라벨을 수정했습니다.' });
            closeModal();
          },
          onError: (err) => setSubmitError(resolveApiMessage(err, '라벨 수정에 실패했습니다.')),
        },
      );
    } else {
      createMutation.mutate(body, {
        onSuccess: () => {
          pushToast({ variant: 'success', message: '라벨을 추가했습니다.' });
          closeModal();
        },
        onError: (err) => setSubmitError(resolveApiMessage(err, '라벨 추가에 실패했습니다.')),
      });
    }
  };

  const handleConfirmDelete = () => {
    const target = pendingDelete;
    if (!target) return;
    deleteMutation.mutate(target.labelId, {
      onSuccess: () => pushToast({ variant: 'success', message: '라벨을 삭제했습니다.' }),
      onError: (err) =>
        pushToast({
          variant: 'error',
          message: resolveApiMessage(err, '라벨 삭제에 실패했습니다.'),
        }),
    });
    setPendingDelete(null);
  };

  const submitting = createMutation.isPending || updateMutation.isPending;

  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        title={
          <span className="inline-flex items-center gap-2">
            <span className="inline-flex items-center justify-center rounded-lg bg-primary-50 p-2">
              <Tags className="h-5 w-5 text-primary-600" aria-hidden />
            </span>
            <span>라벨 관리</span>
          </span>
        }
        description={`라벨 클래스(마스터) 관리 — 전체 ${rows.length.toLocaleString('ko-KR')}개`}
        actions={
          <Button variant="primary" onClick={openCreate}>
            <Plus className="mr-1 h-4 w-4" aria-hidden />
            라벨 추가
          </Button>
        }
      />

      {error && <ErrorState title="라벨 목록을 불러올 수 없습니다" />}

      {isLoading ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} height="3rem" />
          ))}
        </div>
      ) : rows.length === 0 ? (
        <div className="rounded-lg border border-gray-200 bg-white p-12 text-center">
          <Tags size={40} className="mx-auto mb-3 text-gray-300" aria-hidden />
          <p className="text-sm text-gray-500">등록된 라벨이 없습니다.</p>
          <p className="mt-1 text-xs text-gray-400">
            새 라벨 클래스를 만들어 라벨링 작업에 활용하세요.
          </p>
          <Button variant="primary" className="mt-4" onClick={openCreate}>
            <Plus className="mr-1 h-4 w-4" aria-hidden />새 라벨 만들기
          </Button>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
          <table className="w-full text-left text-sm">
            <thead>
              <tr className="border-b border-gray-200 text-xs font-semibold uppercase tracking-wide text-gray-500">
                <th scope="col" className="px-4 py-3">
                  라벨명
                </th>
                <th scope="col" className="px-4 py-3">
                  형태
                </th>
                <th scope="col" className="px-4 py-3">
                  색상
                </th>
                <th scope="col" className="px-4 py-3">
                  정렬순
                </th>
                <th scope="col" className="px-4 py-3 text-right">
                  관리
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <tr
                  key={m.labelId}
                  data-testid={`label-master-row-${m.labelId}`}
                  className={`border-b border-gray-100 last:border-b-0 hover:bg-gray-50 ${
                    selectedLive?.labelId === m.labelId ? 'bg-primary-50/60' : ''
                  }`}
                >
                  <td className="px-4 py-3 font-medium text-gray-900">{m.name}</td>
                  <td className="px-4 py-3 text-gray-600">{TYPE_LABEL[m.type]}</td>
                  <td className="px-4 py-3">
                    <span className="inline-flex items-center gap-2">
                      <span
                        className="inline-block h-4 w-4 rounded border border-gray-300"
                        style={{ backgroundColor: m.color }}
                        aria-hidden
                      />
                      <span className="tabular-nums text-gray-600">{m.color}</span>
                    </span>
                  </td>
                  <td className="px-4 py-3 tabular-nums text-gray-600">{m.sortNo}</td>
                  <td className="px-4 py-3">
                    <div className="flex items-center justify-end gap-1">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          setSelected((prev) => (prev?.labelId === m.labelId ? null : m))
                        }
                        aria-label={`${m.name} 속성 정의 관리`}
                        aria-pressed={selectedLive?.labelId === m.labelId}
                      >
                        <ListTree className="mr-1 h-3.5 w-3.5" aria-hidden />
                        속성
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => openEdit(m)}
                        aria-label={`${m.name} 수정`}
                      >
                        <Pencil className="mr-1 h-3.5 w-3.5" aria-hidden />
                        수정
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => setPendingDelete(m)}
                        aria-label={`${m.name} 삭제`}
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

      {selectedLive && (
        <LabelAttrDefPanel labelId={selectedLive.labelId} labelName={selectedLive.name} />
      )}

      <LabelMasterFormModal
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
        title="라벨 삭제"
        description={
          pendingDelete
            ? `"${pendingDelete.name}" 라벨을 삭제하시겠습니까? 이 라벨을 사용하던 기존 라벨의 색상이 기본값으로 표시될 수 있습니다.`
            : ''
        }
        variant="danger"
        confirmLabel="삭제"
        loading={deleteMutation.isPending}
        onConfirm={handleConfirmDelete}
        onCancel={() => setPendingDelete(null)}
      />
    </section>
  );
}
