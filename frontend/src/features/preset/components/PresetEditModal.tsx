import { zodResolver } from '@hookform/resolvers/zod';
import { AlertCircle, Info } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';

import { Field, FieldError, FieldLabel, FieldTitle } from '@/components/common/Field';
import { Button } from '@/components/common/Button';
import { ConfirmDialog } from '@/components/common/ConfirmDialog';
import { Modal } from '@/components/common/Modal';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { useEventTypeAdminList } from '@/features/eventType/adminHooks';
import { TYPE_LABEL } from '@/features/label/constants/labelTypes';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';

import { LABEL_IDS_MAX_COUNT, presetSchema, type PresetFormValues } from '../schemas';
import type { Preset, PresetForm } from '../types';
import { formatEventTypeDisplay } from '../utils/eventTypeDisplay';

/** 필드 라벨 — DS-001 ladder `label`(14px/600). */
const FIELD_LABEL_CLASS = 'text-label font-semibold text-gray-900';
/**
 * 보조 도움말 — 흰 모달 표면 위 gray-600(6.30:1 AA).
 *
 * ⚠ gray-400/500 으로 내리지 말 것: 400 은 흰 배경에서도 AA 미달이고 500 은 경계(4.51)라
 *   모달이 회색 표면 위에 놓이는 변형이 생기면 곧바로 미달로 떨어진다.
 */
const FIELD_HELP_CLASS = 'text-caption text-gray-600';

export interface PresetEditModalProps {
  open: boolean;
  onClose: () => void;
  initial?: Preset;
  onSubmit: (form: PresetForm) => void;
  submitting?: boolean;
}

const EMPTY_FORM: PresetFormValues = {
  eventTypeCd: '',
  labelIds: [],
};

/**
 * 프리셋 편집 모달 — 입력은 <b>이벤트유형 + 라벨</b> 둘뿐이다. [@design SCREEN-026]
 *
 * - 프리셋은 이름·설명을 갖지 않는다 — 이벤트 1건에 프리셋 1건이라 이름은 이벤트명의 중복이었고
 *   설명은 읽는 화면이 없었다. 사람이 읽는 이름은 이벤트 표시명이 담당한다.
 * - 이벤트유형은 <b>필수</b>다('선택 안 함' 옵션 없음) — 이벤트에 걸리지 않은 프리셋은 어느
 *   영상에도 매칭되지 않는 죽은 행이다.
 * - 라벨: `useLabelMasters` 로 활성 마스터를 불러와 체크박스 멀티셀렉트. 형태는 마스터 소유이므로
 *   읽기 전용으로 표시(사용자 토글 불가)한다. 제출 시 선택한 labelId 배열만 전송한다.
 * - 편집 대상에 미연결(linked=false) 코드가 있으면 경고 배너로 재선택을 유도한다.
 * - ★라벨마다 <b>AI 검출 클래스 매핑 여부</b>를 표시한다. 매핑되지 않은 라벨도 <b>고를 수 있다</b> —
 *   선택을 막지 않는다. 나중에 라벨 관리에서 검출 클래스를 지정하면 그때부터 적용되는 동선을
 *   닫지 않기 위해서다. 매핑 여부는 <b>서버가 라벨 항목마다 내려주는 값</b>(`dtctTypeCd`)을
 *   그대로 쓰고 화면에서 다시 판정하지 않는다.
 * - ★<b>라벨을 하나도 고르지 않고 저장할 수 있다</b>. 그것은 그 이벤트유형을 오토라벨링 대상에서
 *   빼겠다는 선언이므로, 실수로 못 고른 것과 구분되도록 저장 <b>전에</b> 확인을 한 번 받는다.
 *   ⚠ 이 확인은 아래 「전부 미매핑」 경고와 <b>서로 다른 상태</b>를 맡는다 — 그 경고는 라벨을 하나
 *   이상 고른 프리셋의 것이고, 라벨이 비면 그 경고 대신 이 확인이 뜬다(둘이 함께 나오지 않는다).
 *
 * <h3>진입 모드별 초기 상태</h3>
 * <ul>
 *   <li><b>수정</b> — 저장돼 있는 이벤트유형과 라벨이 <b>둘 다 선택된 상태</b>로 열린다. 그 시점에
 *       필수 입력 오류 안내가 없고 저장 수단이 곧바로 활성이다. 초기 선택 상태는 목록 응답이 이미
 *       내려준 값이라 <b>다시 조회하지 않는다</b>.</li>
 *   <li><b>신규</b> — 두 축 모두 미선택이고, 이벤트유형 자리는 placeholder 로 미선택을 드러낸다.
 *       저장 수단은 이벤트유형이 미선택이라 쓸 수 없고 <b>고르는 순간 곧바로 활성</b>이 된다 —
 *       라벨 개수는 그 조건에 들어가지 않는다(그 구분은 저장 전 확인이 가른다).</li>
 * </ul>
 *
 * @design SCREEN-026
 * @design UC-032
 * @design AC-1061
 * @design SEQ-022
 * @design API-038
 * @design API-039
 * @design API-185
 * @design AC-114
 * @design AC-119
 */
export function PresetEditModal({
  open,
  onClose,
  initial,
  onSubmit,
  submitting,
}: PresetEditModalProps) {
  const isEdit = !!initial;
  /**
   * 이벤트 옵션 — <b>등록된 전체 이벤트유형</b>(비수집·제외 대분류 포함)이다.
   *
   * ★필터 드롭다운용 옵션 목록(`useEventTypes`)을 쓰지 않는다. 그 목록은 제외 대분류(예: 배회)를
   *   감추므로, 그 목록으로 옵션을 채우면 해당 유형의 프리셋을 화면에서 만들거나 고칠 수 없다 —
   *   서버는 <b>등록 여부</b>로 검증하므로 받아 주는데 화면만 못 고르는 비대칭이 생긴다.
   *   또 필터 옵션은 같은 표시명을 가진 유형들을 한 그룹으로 접는데, 여기서는 유형을 개별로 고른다.
   *
   * ⚠ 목록 필터·다른 화면의 이벤트 드롭다운은 여전히 필터 옵션 축이 맞다 — 두 축을 통일하지 않는다.
   */
  const { data: eventTypes, isLoading: eventTypesLoading } = useEventTypeAdminList();
  // 라벨 마스터 목록 — 프리셋 라벨의 단일 진실원.
  const { data: masters, isLoading: mastersLoading } = useLabelMasters();

  const {
    handleSubmit,
    getValues,
    reset,
    setValue,
    watch,
    formState: { errors },
  } = useForm<PresetFormValues>({
    resolver: zodResolver(presetSchema),
    defaultValues: EMPTY_FORM,
  });

  // ⚠ `watch(...) ?? []` 를 그대로 쓰면 매 렌더마다 새 배열이 되어, 이 값을 dep 으로 삼는
  //   useMemo 가 전혀 memo 되지 않는다(그리고 lint 가 그 사실을 경고한다).
  const watchedLabelIds = watch('labelIds');
  const selectedIds = useMemo(() => watchedLabelIds ?? [], [watchedLabelIds]);
  const eventTypeCd = watch('eventTypeCd') ?? '';
  /**
   * 「라벨 없이 저장 확인」 열림 여부.
   *
   * ★확인이 뜨는 동안 편집 모달은 <b>닫히지만 이 컴포넌트는 그대로 마운트된 채</b>다 — 폼 상태가
   * 훅 안에 살아 있어 「돌아가서 라벨 고르기」로 되돌아오면 고른 이벤트유형과 선택 상태가 그대로다.
   * ⚠ 두 모달을 <b>동시에</b> 띄우지 않는 이유: 둘 다 document 에 ESC·포커스 트랩 리스너를 걸어
   *   ESC 한 번에 두 개가 함께 닫히고 트랩이 서로 다툰다.
   */
  const [confirmEmptyOpen, setConfirmEmptyOpen] = useState(false);

  // 활성 마스터만 정렬 노출 (useYn='Y', sortNo 오름차순).
  const activeMasters = useMemo(
    () =>
      (masters ?? [])
        .filter((m) => m.useYn === 'Y')
        .slice()
        .sort((a, b) => a.sortNo - b.sortNo),
    [masters],
  );

  /**
   * 이벤트 옵션 — 표시는 `이벤트명 (유형코드)`.
   *
   * 코드를 함께 보이는 이유는 같은 표시명을 가진 유형이 여럿일 수 있기 때문이다
   * (예: 화재 EV02000101 / 일반화재 EV02000102) — 이름만으로는 구분되지 않는다.
   * 표시명 자체는 서버가 해석한 `dsplNm` 을 그대로 쓴다(FE 가 폴백을 재현하지 않는다).
   */
  const eventOptions = useMemo(
    () =>
      (eventTypes ?? []).map((t) => ({
        value: t.evntTypeCd,
        label: formatEventTypeDisplay(t.dsplNm, t.evntTypeCd),
      })),
    [eventTypes],
  );

  // 편집 대상의 미연결 코드(재선택 필요) — legacy 라벨명 안내용.
  const unlinkedNames = useMemo(
    () => (initial?.codes ?? []).filter((c) => !c.linked).map((c) => c.labelName),
    [initial],
  );

  /**
   * 고른 라벨 가운데 <b>AI 검출 클래스에 매핑된 것이 하나도 없는지</b>.
   *
   * ★판정 재유도가 아니다 — 서버가 라벨마다 내려준 `dtctTypeCd` 의 유무를 그대로 읽는다.
   * ⚠ 마스터 로딩 중에는 `selectedMasters` 가 비어 `every` 가 참이 되므로(빈 배열의 every 는 true)
   *   <b>고른 라벨을 실제로 하나 이상 찾았을 때만</b> 판정한다 — 안 그러면 편집 진입 순간
   *   "전부 미매핑" 경고가 잘못 뜬다.
   */
  const selectedMasters = useMemo(
    () => activeMasters.filter((m) => selectedIds.includes(m.labelId)),
    [activeMasters, selectedIds],
  );
  const allSelectedUnmapped =
    selectedMasters.length > 0 && selectedMasters.every((m) => m.dtctTypeCd == null);

  useEffect(() => {
    if (!open) return;
    // 모달을 새로 열 때 확인 창이 남아 있지 않게 한다.
    setConfirmEmptyOpen(false);
    if (initial) {
      const linkedIds = initial.codes
        .filter((c): c is typeof c & { labelId: number } => c.linked && c.labelId != null)
        .map((c) => c.labelId);
      reset({
        eventTypeCd: initial.eventTypeCd ?? '',
        labelIds: linkedIds,
      });
    } else {
      reset(EMPTY_FORM);
    }
  }, [open, initial, reset]);

  // ⚠ 「옵션이 도착하면 편집 대상의 이벤트를 select 에 다시 반영한다」는 두 번째 효과를 두지
  //   않는다. 그 효과는 위 reset 이 넣은 값이 곧바로 지워지는 것을 뒤늦게 되돌리려던 것이었는데,
  //   지운 주체가 아래 handleEventTypeChange 로 올라오던 「빈 값 되쏨」이라 그 되쏨을 막으면
  //   되살릴 값이 애초에 사라지지 않는다.
  // ★되살리지 말 것 — 그 효과의 의존성에는 옵션 목록이 들어가는데, 그 목록은 내용이 바뀐 채로
  //   재조회되면 새 참조가 되어 효과가 다시 돈다. 그러면 편집 중인 사람이 방금 고른 이벤트유형이
  //   저장돼 있던 값으로 조용히 되돌아간다.

  /**
   * 이벤트유형 선택 반영 — <b>빈 값은 받지 않는다</b>.
   *
   * 이 셀렉트에는 '선택 안 함' 옵션이 없다(이벤트유형은 필수). 따라서 `''` 는 사람이 어떤
   * 클릭으로도 만들 수 없는 값이며, 올라온다면 그것은 선택이 아니라 <b>되쏨</b>이다.
   *
   * <h3>되쏨이 무엇인가 (이 결함의 원인)</h3>
   * Radix 셀렉트는 폼 제출을 위해 숨은 native `select` 를 함께 그리고, 값이 바뀌면 그 select 에
   * 새 값을 대입한 뒤 `change` 를 스스로 발생시켜 그 이벤트의 `target.value` 를 다시 올려 보낸다.
   * 그런데 <b>옵션이 아직 등록되지 않은 시점</b>에는 대입이 조용히 실패해 select 의 값이 `''`
   * 로 남고, 그래서 <b>`''` 가 선택된 것처럼 되돌아온다</b>.
   *
   * 편집으로 모달을 열면 정확히 그 순서가 된다 — 값 복원이 옵션 등록보다 한 박자 앞서므로,
   * 되쏨을 그대로 폼에 쓰면 방금 복원한 이벤트유형이 지워지고 `shouldValidate` 때문에 필수 입력
   * 오류까지 함께 떠서 <b>열자마자 저장이 잠긴다</b>. 그 되쏨을 여기서 버린다.
   *
   * ⚠ 사람이 고른 값은 언제나 비어 있지 않으므로 이 가드가 정상 선택을 막지 않는다.
   *
   * @design UC-032
   * @design AC-1061
   */
  const handleEventTypeChange = (next: string) => {
    if (next === '') return;
    setValue('eventTypeCd', next, { shouldValidate: true, shouldDirty: true });
  };

  const toggleLabel = (labelId: number) => {
    const next = selectedIds.includes(labelId)
      ? selectedIds.filter((id) => id !== labelId)
      : [...selectedIds, labelId];
    setValue('labelIds', next, { shouldValidate: true, shouldDirty: true });
  };

  const emit = (form: PresetFormValues) => {
    onSubmit({
      eventTypeCd: form.eventTypeCd,
      labelIds: form.labelIds,
    });
  };

  const submit = handleSubmit((form) => {
    // 라벨을 하나도 고르지 않았으면 <b>저장 전에</b> 확인을 받는다 — 실수로 못 고른 것과
    // 「오토라벨 대상에서 뺀다」는 선언을 사람이 여기서 가른다. 막는 것이 아니라 확인만 한다.
    if (form.labelIds.length === 0) {
      setConfirmEmptyOpen(true);
      return;
    }
    emit(form);
  });

  /** 확인 창의 「라벨 없이 저장」 — 편집 모달이 준비한 요청을 그대로 이어 보낸다. */
  const submitWithoutLabels = () => {
    setConfirmEmptyOpen(false);
    emit({ ...getValues(), labelIds: [] });
  };

  // 이벤트유형만 필수다 — 비운 채 저장하면 서버가 400 으로 되돌린다.
  // ★라벨 개수는 더 이상 저장 조건이 아니다(구 `selectedIds.length === 0` 차단 폐기) —
  //   라벨을 비우는 것이 정당한 선언이 됐고, 실수와의 구분은 확인 창이 맡는다.
  const saveDisabled = !!submitting || eventTypeCd === '';

  return (
    <>
    <Modal
      // 확인 창이 떠 있는 동안에는 편집 모달을 감춘다(두 모달을 겹치지 않는다).
      // 이 컴포넌트 자체는 마운트된 채라 폼 상태는 그대로 유지된다.
      open={open && !confirmEmptyOpen}
      onClose={onClose}
      size="lg"
      title={isEdit ? '프리셋 편집' : '새 프리셋 만들기'}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={onClose} disabled={submitting}>
            취소
          </Button>
          <Button
            type="button"
            variant="primary"
            onClick={submit}
            loading={submitting}
            disabled={saveDisabled}
          >
            {isEdit ? '저장' : '만들기'}
          </Button>
        </>
      }
    >
      <form
        className="space-y-5"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        {/* 대상 이벤트유형 — 프리셋의 유일한 식별 축이라 필수다. */}
        <Field className="gap-1.5">
          <FieldLabel className={FIELD_LABEL_CLASS} htmlFor="preset-event" required>
            이벤트유형
            <span className="ml-1 font-normal text-gray-600">
              (오토라벨 시 이 이벤트의 영상에 본 프리셋 적용)
            </span>
          </FieldLabel>
          <Select value={eventTypeCd} onValueChange={handleEventTypeChange}>
            <SelectTrigger id="preset-event">
              {/* '선택 안 함(미매핑)' 옵션을 두지 않는다 — 이벤트는 필수다. 아무것도 고르지 않은
                  상태는 옵션이 아니라 placeholder 로 표현한다. */}
              <SelectValue placeholder="이벤트유형을 선택하세요" />
            </SelectTrigger>
            <SelectContent>
              {eventOptions.map((o) => (
                <SelectItem key={o.value} value={o.value}>
                  {o.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <FieldError>{errors.eventTypeCd?.message}</FieldError>
          {/* 이 안내는 오류 여부와 무관하게 항상 노출한다 — FieldDescription 은 오류가 있으면
              aria-describedby 대상에서 밀리므로 일반 문단으로 둔다. */}
          <p className={FIELD_HELP_CLASS}>
            {eventTypesLoading
              ? '이벤트유형을 불러오는 중…'
              : '필수. 이벤트 1건에는 프리셋이 1건만 존재합니다. 이미 프리셋이 있는 이벤트를 고르면 저장 시 안내됩니다.'}
          </p>
        </Field>

        {/* Label master selection */}
        <div className="flex flex-col gap-2">
          <div className="flex items-baseline justify-between gap-2">
            {/* 특정 입력 하나가 아니라 목록 전체의 제목이라 label 이 아니라 FieldTitle(div) 이다 —
                아래 ul 이 aria-labelledby 로 이 id 를 가리킨다. */}
            {/* ★필수 표식을 두지 않는다 — 라벨을 비우는 것이 정당한 선언이 됐다(하한 폐지). */}
            <FieldTitle className={FIELD_LABEL_CLASS} id="preset-labels-label">
              라벨 항목
            </FieldTitle>
            {/* 상한이 있는 선택이라 고른 개수가 목록보다 먼저 읽혀야 한다 — 숫자를 굵게 강조한다. */}
            <span className={FIELD_HELP_CLASS} data-testid="preset-labels-count">
              <strong className="font-semibold tabular-nums text-gray-900">
                {selectedIds.length}
              </strong>{' '}
              / {LABEL_IDS_MAX_COUNT}개 선택
            </span>
          </div>
          <p className={FIELD_HELP_CLASS}>
            활성 라벨 마스터를 정렬순으로 나열했습니다. 형태는 라벨 마스터가 소유하는 읽기 전용
            값이라 여기서는 변경할 수 없습니다. 최대 {LABEL_IDS_MAX_COUNT}개까지 선택할 수 있고,
            하나도 고르지 않으면 이 이벤트유형은 오토라벨링 대상에서 빠집니다.
          </p>

          {/* 미연결 안내 (편집 시) — 오류가 아니라 "저장 시 자동 제외"를 알리는 정보 배너다. */}
          {unlinkedNames.length > 0 && (
            <div
              className="flex items-start gap-2 rounded-md border border-info-200 bg-info-50 px-4 py-3 text-body-sm text-info-700"
              role="alert"
              data-testid="preset-unlinked-warning"
            >
              <Info className="mt-1 h-4 w-4 shrink-0" aria-hidden="true" />
              <div className="flex min-w-0 flex-col gap-1">
                <p className="font-semibold">더 이상 라벨 마스터에 없는 항목이 있습니다</p>
                <ul className="list-disc pl-5">
                  {unlinkedNames.map((name) => (
                    <li key={name}>{name} · 미연결</li>
                  ))}
                </ul>
                <p>저장 시 이 항목은 자동으로 제외됩니다. 필요한 라벨을 아래에서 다시 선택하세요.</p>
              </div>
            </div>
          )}

          {/* 전부 미매핑 경고 — 저장을 <b>막지 않는다</b>. 저장 버튼은 그대로 활성이고 저장은
              성공한다. 나중에 라벨 마스터에 검출 클래스를 지정하면 그때부터 적용되는 동선을 닫지
              않기 위해서다. 문구는 사양 SCREEN-026 확정값이라 임의로 다듬지 말 것.
              ⚠ 라벨을 하나도 고르지 않은 상태에서는 뜨지 않는다 — 그 상태는 「라벨 없이 저장 확인」이
                맡는 <b>다른 상태</b>이고, 둘이 함께 나오면 사고와 선언이 뒤섞여 읽힌다. */}
          {allSelectedUnmapped && (
            <div
              className="flex items-start gap-2 rounded-md border border-warning-200 bg-warning-50 px-4 py-3 text-body-sm text-warning-700"
              role="alert"
              data-testid="preset-unmapped-warning"
            >
              <AlertCircle className="mt-1 h-4 w-4 shrink-0" aria-hidden="true" />
              <p className="min-w-0">
                담긴 라벨이 모두 AI 검출 클래스에 매핑되어 있지 않아 이 프리셋은 오토라벨링에
                적용되지 않습니다. 라벨 관리 화면에서 검출 클래스를 지정하면 그때부터 적용됩니다.
              </p>
            </div>
          )}

          {mastersLoading ? (
            <p className={FIELD_HELP_CLASS} role="status">
              라벨 마스터를 불러오는 중…
            </p>
          ) : activeMasters.length === 0 ? (
            <p
              className="rounded-md border border-gray-200 bg-gray-50 px-4 py-3 text-body-sm text-gray-600"
              data-testid="preset-no-masters"
            >
              선택할 라벨 마스터가 없습니다. 먼저 라벨 관리에서 라벨을 등록하세요.
            </p>
          ) : (
            // 스크롤 높이를 260px 로 묶는다 — 마스터가 수십 건이어도 모달이 밀리지 않고,
            // "스크롤해서 고른다"는 실제 사용 감각이 유지된다(사양 SCREEN-026 디자인).
            <div className="max-h-[260px] overflow-y-auto rounded-md border border-gray-200">
              <ul
                className="divide-y divide-gray-100"
                data-testid="preset-master-list"
                aria-labelledby="preset-labels-label"
              >
                {activeMasters.map((m) => {
                  const checkboxId = `preset-label-${m.labelId}`;
                  const checked = selectedIds.includes(m.labelId);
                  return (
                    <li key={m.labelId}>
                      {/* 44px 터치 타깃은 행 라벨이 만든다(KRDS) — 체크박스만으로는 좁다. */}
                      <label
                        htmlFor={checkboxId}
                        className={[
                          'flex min-h-11 cursor-pointer select-none items-center gap-2 px-3 py-2 text-body-sm transition-colors duration-100',
                          checked ? 'bg-primary-50' : 'bg-white hover:bg-gray-50',
                        ].join(' ')}
                      >
                        <input
                          id={checkboxId}
                          type="checkbox"
                          checked={checked}
                          onChange={() => toggleLabel(m.labelId)}
                          // 네이티브 체크박스의 체크 표면은 `accent-*`(accent-color)로 칠한다 —
                          // 이 저장소엔 @tailwindcss/forms 가 없어 `text-*` 는 체크박스에 아무
                          // 효과가 없고, 그래서 그동안 브라우저 기본색이 그대로 나왔다.
                          className={cn(
                            'h-5 w-5 shrink-0 rounded border-gray-400 accent-primary-600',
                            KRDS_FOCUS,
                          )}
                        />
                        <span
                          className="inline-block h-3 w-3 shrink-0 rounded-full"
                          style={{ backgroundColor: m.color }}
                          aria-hidden="true"
                        />
                        <span className="min-w-0 flex-1 truncate text-gray-900">{m.name}</span>
                        {/* AI 검출 클래스 미매핑 표시 — 어느 라벨이 오토라벨 대상이 아닌지 짚어 준다.
                            판정은 서버가 라벨마다 내려준 `dtctTypeCd` 의 유무 그대로다(재판정 금지).
                            ★표시일 뿐 선택을 막지 않는다 — 나중에 매핑을 지정하면 적용된다.
                            대비: warning-700/warning-50 8.43:1(AA 이상), 글자가 곧 뜻이라 색 단독 아님. */}
                        {m.dtctTypeCd == null && (
                          <span
                            data-testid={`preset-label-unmapped-${m.labelId}`}
                            className="shrink-0 rounded-sm bg-warning-50 px-1.5 text-caption font-semibold text-warning-700"
                          >
                            AI 미매핑
                          </span>
                        )}
                        <span className="shrink-0 rounded-sm border border-gray-200 bg-white px-1.5 text-caption font-semibold text-gray-600">
                          {TYPE_LABEL[m.type]}
                        </span>
                      </label>
                    </li>
                  );
                })}
              </ul>
            </div>
          )}

          {errors.labelIds?.message && (
            <p className="flex items-center gap-1 text-caption text-danger-700" role="alert">
              <AlertCircle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              {errors.labelIds.message}
            </p>
          )}
        </div>
      </form>
    </Modal>

    {/* 「라벨 없이 저장 확인」 — 라벨을 비우는 것은 그 이벤트유형을 오토라벨링 대상에서 빼겠다는
        선언이라, 실수로 못 고른 것과 일부러 비운 것을 사람이 여기서 구분한다. 막지 않는다.
        ★문구(제목·본문·버튼)는 사양 SCREEN-026 확정값이다 — 임의로 다듬지 말 것. 특히 본문의
          "보류되지 않고" 는 반드시 남긴다: 라벨을 담았는데 적용되지 않는 상태(보류)와 달리
          이쪽은 배치가 멈추지 않는다는 것이 두 상태를 가르는 핵심이다. */}
    <ConfirmDialog
      open={confirmEmptyOpen}
      title="라벨을 하나도 고르지 않았습니다"
      description="이대로 저장하면 이 이벤트유형은 오토라벨링 대상에서 빠집니다. 해당 유형의 영상은 보류되지 않고 배치가 그대로 완료되며 오토라벨링만 건너뜁니다. 의도한 선택이 맞습니까?"
      confirmLabel="라벨 없이 저장"
      cancelLabel="돌아가서 라벨 고르기"
      loading={submitting}
      onConfirm={submitWithoutLabels}
      // 되돌아가면 편집 모달이 다시 뜬다 — 고른 이벤트유형과 선택 상태는 그대로다.
      onCancel={() => setConfirmEmptyOpen(false)}
    />
    </>
  );
}
