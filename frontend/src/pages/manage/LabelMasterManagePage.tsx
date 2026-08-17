import { useMemo, useState } from 'react';
import { Pencil, Plus, Tag, Trash2 } from 'lucide-react';

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
import { cn } from '@/lib/cn';
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
 * - 행의 '속성' 버튼은 해당 라벨의 속성 정의 사이드 시트(LabelAttrDefPanel)를 연다
 *   — 테이블 아래 인라인 패널이 아니다(사양 SCREEN-035 「속성 정의 사이드 시트」).
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
const TH_CLASS = 'px-4 py-3 text-left text-table-header uppercase tracking-wide text-gray-600';

// [design: SCREEN-035] AI 탐지 매핑 표시 — 목록에서 매핑 상태를 바로 보인다.
//
// ★별도 컬럼을 만들지 않는다. 표시 자리는 **`라벨명` 셀 안의 칩**이고 컬럼 구성
// (라벨명/형태/색상/정렬순/관리)은 그대로다(회귀 가드: LabelMasterManagePage.detectMapping.test).
//
// ★공용 `Badge`(UI-111) 를 쓰지 않는 이유: 그 컴포넌트의 variant 는 `pinned|success|neutral`
//   로 공지 도메인 의미에 묶여 있다. 주의 톤이 필요한 '미매핑'에 `pinned`(중요·고정)를 빌려
//   쓰면 코드가 거짓을 말하고, variant 를 새로 추가하는 것은 공용 컴포넌트(카탈로그) 계약
//   변경이라 이 범위 밖이다. 그래서 이 화면 안에 국소 표시 컴포넌트로 둔다
//   (같은 판단의 선례가 `PresetCodeChip` 이다).
const MAPPING_CHIP_BASE =
  'ml-2 inline-flex shrink-0 items-center rounded-full border px-2 py-0.5 text-label font-semibold align-middle';
/** 매핑됨 — 정보 톤(primary-700 on primary-50 = 9.42:1 AAA). */
const MAPPING_CHIP_MAPPED = 'border-primary-100 bg-primary-50 text-primary-700';
/**
 * 미매핑 — 주의 톤(warning-700 on warning-50). 조치가 필요한 상태라 중립이 아니다:
 * 매핑이 없는 라벨은 도메인 규칙상 **AI 탐지에서 선택할 수 없다**(`AutolabelOnlineService` 가
 * 마스터 매핑 화이트리스트와의 교집합만 추론 서버로 보낸다).
 */
const MAPPING_CHIP_UNMAPPED = 'border-warning-200 bg-warning-50 text-warning-700';

interface DetectMappingChipProps {
  labelId: number;
  /** 매핑된 검출 클래스명(COCO, 영문). 미매핑이면 null. */
  dtctTypeCd: string | null;
}

/**
 * AI 탐지 매핑 칩 — 매핑된 검출 클래스명 또는 '미매핑' 표기.
 *
 * 접근성: 색만으로 정보를 전달하지 않는다(항상 텍스트가 함께 있다). 칩 텍스트 단독으로는
 * `person` 이 무슨 축인지 알 수 없으므로 **축 이름을 sr-only 로 병기**한다 — 시각 표기까지
 * 늘리면 라벨명 셀이 장황해진다.
 *
 * 값은 텍스트로만 렌더한다(XSS 방어 — dangerouslySetInnerHTML 미사용).
 */
function DetectMappingChip({ labelId, dtctTypeCd }: DetectMappingChipProps) {
  const mapped = dtctTypeCd != null;
  return (
    <span
      data-testid={`label-detect-mapping-${labelId}`}
      className={cn(MAPPING_CHIP_BASE, mapped ? MAPPING_CHIP_MAPPED : MAPPING_CHIP_UNMAPPED)}
    >
      <span className="sr-only">{mapped ? 'AI 탐지 매핑 ' : 'AI 탐지 '}</span>
      <span>{mapped ? dtctTypeCd : '미매핑'}</span>
    </span>
  );
}

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
        // 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다.
        title="라벨 관리"
        // 부제는 고정 텍스트다 — 동적 개수는 포함하지 않는다(사양 SCREEN-035).
        // '전체 N개' 는 헤더가 아니라 **목록 바로 위**에 놓인다(아래 참조).
        description="라벨 클래스(마스터)의 이름·형태·색상·정렬 순서를 관리합니다."
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
          <Tag size={40} className="mx-auto mb-3 text-gray-300" aria-hidden />
          <p className="text-body-md text-gray-500">등록된 라벨이 없습니다.</p>
          <p className="mt-1 text-caption text-gray-400">
            새 라벨 클래스를 만들어 라벨링 작업에 활용하세요.
          </p>
          <Button variant="primary" className="mt-4" onClick={openCreate}>
            <Plus className="mr-1 h-4 w-4" aria-hidden />새 라벨 만들기
          </Button>
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          {/* '전체 N개' 는 헤더가 아니라 목록 바로 위에 놓인다(사양 SCREEN-035). */}
          <p data-testid="label-master-total" className="text-caption text-gray-600">
            전체 {rows.length.toLocaleString('ko-KR')}개
          </p>
          <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white">
          <table className="w-full text-left text-body-md">
            <thead>
              {/* 헤더 배경은 secondary 스케일 최옅단(DS-001 do_rules) — 페이지 배경과 같은
                  회색을 쓰면 열 구조가 먼저 읽히지 않는다. 글자색 gray-600 은 그 위에서
                  5.60:1 로 AA 를 만족한다(gray-500 은 4.01 로 미달).
                  `<tr>` 에는 배경·테두리만 두고 **글자 축은 `<th>`(TH_CLASS)** 가 갖는다. */}
              <tr className="border-b border-gray-200 bg-secondary-50">
                <th scope="col" className={TH_CLASS}>
                  라벨명
                </th>
                <th scope="col" className={TH_CLASS}>
                  형태
                </th>
                <th scope="col" className={TH_CLASS}>
                  색상
                </th>
                <th scope="col" className={TH_CLASS}>
                  정렬순
                </th>
                <th scope="col" className={cn(TH_CLASS, 'text-right')}>
                  관리
                </th>
              </tr>
            </thead>
            <tbody>
              {/* 사양 SCREEN-035 — 행 선택·강조 표시는 두지 않는다. 속성 정의가 사이드 시트로
                  분리돼 인라인 연동이 없으므로, 선택 상태를 행 배경으로 알릴 대상이 없다.
                  현재 열린 시트가 어느 라벨의 것인지는 시트 제목과 '속성' 버튼의
                  aria-pressed 가 알린다. */}
              {rows.map((m) => (
                <tr
                  key={m.labelId}
                  data-testid={`label-master-row-${m.labelId}`}
                  className="border-b border-gray-100 transition-colors last:border-b-0 hover:bg-rowHover"
                >
                  <td className="px-4 py-3 font-medium text-gray-900">
                    {m.name}
                    {/* [design: SCREEN-035] AI 탐지 매핑은 별도 컬럼이 아니라 이 셀 안의 칩이다. */}
                    <DetectMappingChip labelId={m.labelId} dtctTypeCd={m.dtctTypeCd} />
                  </td>
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
        </div>
      )}

      {/* 속성 정의는 테이블 아래 인라인이 아니라 **우측 사이드 시트**로 연다(사양 SCREEN-035).
          선택 라벨이 삭제되면 selectedLive 가 null 이 되어 시트가 자동으로 닫힌다. */}
      {selectedLive && (
        <LabelAttrDefPanel
          open
          onClose={() => setSelected(null)}
          labelId={selectedLive.labelId}
          labelName={selectedLive.name}
        />
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
            ? `"${pendingDelete.name}" 라벨을 삭제하시겠습니까? 라벨 목록과 라벨 선택 항목에서 빠지고, 이 라벨을 쓰던 프리셋에는 '미연결'로 표시됩니다. 이미 저장된 라벨 데이터와 속성 정의는 지워지지 않으며 표시 색상도 그대로 유지됩니다. 삭제한 라벨은 이 화면에서 되살릴 수 없습니다.`
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
