import { useMemo, useState } from 'react';
import { Pencil, Plus, Trash2 } from 'lucide-react';

import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { Drawer } from '@/components/common/Drawer';
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
import { cn } from '@/lib/cn';
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
 * 라벨 마스터의 속성 정의 목록 + 추가 / 수정 / 삭제 — REVIEWER 전용.
 *
 * 표현: 사양 SCREEN-035 「속성 정의 사이드 시트」 — 목록 행의 '속성' 버튼을 트리거로
 * **우측에서 슬라이드로 열리는 사이드 시트**다(공용 Drawer 재사용). 테이블 아래에 인라인으로
 * 펼치지 않는다.
 *
 * 진입 자체가 `/manage/labels`(RoleGuard=internalReviewerOnly) 하위이므로 라우트 게이트를
 * 상속한다(추가 인가 불필요). 요청 본문은 LabelAttrUpsert(허용 필드만) — Mass Assignment 방어.
 *
 * 닫기 정책(작성 중 데이터 보호):
 * - **바깥(백드롭) 클릭으로는 닫지 않는다.** 인라인이던 시절에는 다른 곳을 눌러도 패널이
 *   유지됐으므로, 시트로 바꾸면서 바깥 클릭 닫기를 켜면 오조작 한 번에 작성 중이던 속성
 *   정의가 사라지는 회귀가 된다. 닫기는 명시적 의도(X 버튼 / ESC)로만 이뤄진다.
 * - **중첩 다이얼로그(추가·수정 폼, 삭제 확인)가 열려 있는 동안 시트의 ESC 를 끈다.**
 *   ESC 는 가장 안쪽 다이얼로그 하나만 닫아야 하는데, 두 리스너가 같은 document 에 붙어
 *   있어 안쪽의 stopPropagation 으로는 바깥 리스너를 막지 못한다(같은 노드의 리스너는
 *   취소되지 않는다). 끄지 않으면 ESC 한 번에 폼과 시트가 함께 닫혀 입력이 사라진다.
 *
 * 보안(valuesJson XSS, CWE-79):
 * - 선택 항목 값은 React 기본 escape 로 텍스트 렌더(dangerouslySetInnerHTML 미사용).
 * - valuesJson 파싱은 try/catch(parseValues) 로 안전 폴백(파싱 실패 시 빈 목록, 크래시 없음).
 */

/**
 * 표 헤더 셀 클래스 — 모든 `<th>` 가 이 한 값을 공유한다(정렬만 호출부에서 덧붙인다).
 *
 * ⚠ **반드시 `<th>` 에 직접 건다.** 구 구현은 이 글자 클래스를 헤더 `<tr>` 에만 걸었는데,
 * `font-weight` 는 상속되더라도 브라우저 UA 기본 `th { font-weight: bold }`(700)가 **직접
 * 적용**되어 상속값을 이긴다 — 그래서 이 표만 700 으로 굵게 렌더됐다(브라우저 실측).
 * ⚠ 굵기는 `text-table-header` step(600)이 단독으로 정한다 — 별도 굵기 클래스를 겹치지 않는다.
 *
 * 글자색 하한은 `gray-600` 이다 — 헤더 배경이 secondary-50(#EEF2F7)이라 gray-500 은
 * 그 위에서 4.01:1 로 AA(4.5:1) 미달이다(gray-600 은 5.60:1).
 */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

interface LabelAttrDefPanelProps {
  /** 시트 열림 여부 — 트리거(행의 '속성' 버튼)를 소유한 부모가 제어한다. */
  open: boolean;
  /** 시트 닫기 요청 (X 버튼 / ESC). */
  onClose: () => void;
  labelId: number;
  labelName: string;
}

export function LabelAttrDefPanel({
  open,
  onClose,
  labelId,
  labelName,
}: LabelAttrDefPanelProps) {
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

  // 중첩 다이얼로그(폼 모달 / 삭제 확인)가 열려 있으면 시트의 ESC 를 비활성한다 — 위 주석 참조.
  const nestedDialogOpen = modalOpen || pendingDelete !== null;

  return (
    <Drawer
      open={open}
      onClose={onClose}
      side="right"
      width="560px"
      // 바깥 클릭으로 닫지 않는다 — 작성 중 속성 정의 유실 방지(위 주석 참조).
      closeOnBackdrop={false}
      closeOnEsc={!nestedDialogOpen}
      ariaLabel={`${labelName} 속성 정의`}
      title={
        <span>
          <span className="text-primary-600">{labelName}</span> 속성 정의
        </span>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="flex items-center justify-end">
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
          <p className="rounded-md border border-dashed border-gray-200 py-8 text-center text-body-md text-gray-500">
            등록된 속성이 없습니다. 이 라벨에 적용할 속성을 추가하세요.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-body-md">
              <thead>
                {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
                    회색을 쓰면 열 구조가 먼저 읽히지 않는다. 글자색 gray-600 은 그 위에서
                    5.60:1 로 AA 를 만족한다(gray-500 은 4.01 로 미달).
                    `<tr>` 에는 배경·테두리만 두고 **글자 축은 `<th>`(TH_CLASS)** 가 갖는다. */}
                <tr className="border-b border-gray-200 bg-secondary-50">
                  <th scope="col" className={TH_CLASS}>속성명</th>
                  <th scope="col" className={TH_CLASS}>입력 형식</th>
                  <th scope="col" className={TH_CLASS}>선택 항목</th>
                  <th scope="col" className={TH_CLASS}>기본값</th>
                  <th scope="col" className={TH_CLASS}>수정 가능</th>
                  <th scope="col" className={cn(TH_CLASS, 'text-right')}>관리</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((a) => (
                  <tr
                    key={a.attrId}
                    data-testid={`label-attr-row-${a.attrId}`}
                    className="border-b border-gray-100 transition-colors last:border-b-0 hover:bg-rowHover"
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
                          className="text-danger-700 hover:bg-danger/10"
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
      </div>

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
    </Drawer>
  );
}
