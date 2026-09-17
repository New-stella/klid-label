// 「영상 분석 설명」 칸 — 저장 축은 시계열 메타다. [@design UI-056] [@design SCREEN-005] [@design SCREEN-019]
//
// ★배치: 우측 메타 탭의 접이식 패널이 아니라 「영상 분석 설명 · 이벤트 어노테이션」 창의
//   <b>왼쪽 칸</b>이다. 메타 탭에는 이 패널 대신 요약 카드(TimeseriesAnnotationSummaryCard)를 둔다.
//
// ★표기: 이 칸에서는 「영상 분석 설명」이라고 쓰고 「시계열」·「시계열 서술」이라고 쓰지 않으며,
//   내부 저장 키(vlm.description · manual-timeseries)를 라벨로 노출하지 않는다. 키는 화면 문구가
//   아니라 저장 단위일 뿐이라 시험 선택자(data-testid)에만 남긴다.
//
// 보안: 값은 textarea value / 텍스트 노드로만 바인딩 — React 기본 escape. dangerouslySetInnerHTML
//   미사용. maxLength 로 입력 크기 제한(BE LS_DATA_META.META_VL 2000 정합).

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';

import { Textarea } from '@/components/common/Textarea';
import { useMeta } from '@/features/auto/hooks/useMeta';
import { useUpdateMeta } from '@/features/auto/hooks/useUpdateMeta';
import { isEditableMetaKey, MANUAL_TIMESERIES_META_KEY } from '@/features/auto/metaKeys';
import type { MetaItem } from '@/features/auto/types';

import { AnnotationColumn, type AnnotationColumnHandle } from './AnnotationColumn';
import {
  NOT_FILLED_LABEL,
  TIMESERIES_COLUMN_DESCRIPTION,
  TIMESERIES_COLUMN_TITLE,
} from './annotationWording';

export interface TimeseriesSidePanelProps {
  /**
   * 메타 조회/수정 요청에 쓰는 프레임 식별자.
   *
   * ★값 자체는 <b>영상 단위</b>다 — 이 값이 바뀌어(프레임 전환) 다시 조회되어도 편집 중인 값을
   * 덮지 않는다(아래 프리필 규칙 참조).
   */
  srcSn: number | undefined;
  /** 읽기 전용(검수) — 입력 칸·글자 수를 그리지 않고 값만 보인다. 검토 상태 배지도 두지 않는다. */
  readOnly?: boolean;
  /** 저장되지 않은 변경 여부를 창에 알린다(창 아래 저장 바가 이 값을 모아 판정한다). */
  onDirtyChange?: (dirty: boolean) => void;
}

/** BE LS_DATA_META.META_VL 길이(2000)와 정합 — 초과 시 BE 400. FE 에서 미리 입력 제한. */
const MAX_LEN = 2000;

/**
 * 수동 시계열 메타 표준 metaKey 재노출 — 기존 import 경로 호환.
 * 정본은 {@code features/auto/metaKeys} 이며 새 코드는 그쪽을 참조한다.
 */
export { MANUAL_TIMESERIES_META_KEY };

/** 편집 슬롯(= 저장 단위 metaKey 하나). */
interface EditableSlot {
  metaKey: string;
  original: string;
  /** 기존 항목이 없어 새로 등록하는 슬롯인지. */
  isNew: boolean;
}

/**
 * 영상 분석 설명 칸.
 *
 * <h3>편집 단위 = 저장 단위(metaKey)</h3>
 * 여러 키의 값을 하나의 입력 칸으로 합치지 않는다. 합치면 편집된 전문을 다시 항목으로 되돌릴
 * 방법이 없어, 항목이 2건 이상인 영상에서 저장이 <b>조용한 무동작</b>이 된다(2026-08-03 실사고).
 *
 * <h3>편집 대상 판정은 화이트리스트다</h3>
 * {@code vlm.description}(외부 위탁 서술 전문)·{@code manual-timeseries}(수동 등록)만 편집 슬롯이
 * 되며, 접두 문자열 파싱을 하지 않는다(fail-closed — BE 판정과 같은 방향). 편집 가능한 항목이
 * 하나도 없으면 신규 등록 슬롯 1개를 제공한다.
 *
 * <h3>[폐기] 이전 방식 구간 키</h3>
 * 구 산출물의 구간 키({@code "0-8"} 등)에는 <b>대응하지 않는다</b> — 나열·읽기 전용 병기·접기·
 * 대체 규칙을 두지 않는다(2026-09-14 사용자 확정: 그 값은 분석 결과가 아니라 모의 응답 서버가
 * 만든 값으로 판명됐고 개발 서버에서 삭제됐다). 편집 슬롯이 아니므로 저장 payload 에도 구조적으로
 * 들어갈 수 없다.
 *
 * <h3>프레임을 넘겨도 값이 흔들리지 않는다</h3>
 * 조회는 프레임 식별자로 하지만 값은 영상 단위다. 그래서 서버 응답으로 편집 값을 다시 채우는
 * 것은 <b>영상당 최초 1회</b>뿐이다 — 매번 채우면 창을 열어 둔 채 프레임을 넘길 때마다 편집 중인
 * 문장이 서버값으로 되돌아간다.
 *
 * <h3>저장</h3>
 * 이 칸에는 저장 버튼이 없다 — 창 아래 공통 「저장」 하나가 {@link AnnotationColumnHandle} 로
 * 부른다. 보내는 것은 <b>원본과 달라진 슬롯</b>뿐이라 손대지 않은 슬롯이 다른 사람의 수정을
 * 덮어쓰지 않는다.
 */
export const TimeseriesSidePanel = forwardRef<AnnotationColumnHandle, TimeseriesSidePanelProps>(
  function TimeseriesSidePanel({ srcSn, readOnly = false, onDirtyChange }, ref) {
    const { data } = useMeta(srcSn);
    const updateMutation = useUpdateMeta(srcSn);
    const [failed, setFailed] = useState(false);

    /** BE 원본 시계열 메타(기술메타·읽기 전용 메타 제외됨). 조회 전이면 빈 목록. */
    const sourceItems = useMemo<MetaItem[]>(() => data?.items ?? [], [data?.items]);

    /** 편집 대상(화이트리스트 통과분)만 남긴다. 여집합은 이 칸이 다루지 않는다. */
    const editableItems = useMemo(
      () => sourceItems.filter((it) => isEditableMetaKey(it.metaKey)),
      [sourceItems],
    );

    /**
     * 마지막으로 <b>응답이 실제로 있었을 때</b>의 슬롯. 아래 「조회 공백」 방어의 기억이다.
     *
     * ★렌더 중에 갱신한다(효과가 아니라) — 효과는 렌더 뒤에 돌아서, 공백이 시작되는 바로 그
     * 렌더에서는 이미 빈 슬롯이 그려진 뒤다. 그러면 방어가 한 박자 늦어 아무것도 막지 못한다.
     */
    const lastKnownSlotsRef = useRef<EditableSlot[] | null>(null);

    /**
     * 화면에 렌더할 편집 슬롯. 편집 대상이 없으면 신규 등록 슬롯 1개.
     *
     * <h3>★조회 공백에는 직전 슬롯을 유지한다 (2026-09-14 — 조용한 편집 유실 차단)</h3>
     * 조회 키가 <b>프레임</b>이라 창을 열어 둔 채 <b>처음 가는 프레임</b>으로 넘어가면 그 사이
     * {@code data === undefined} 가 된다. 그때 이 목록을 새로 만들면 편집 슬롯이 통째로 신규 등록
     * 슬롯으로 갈아끼워져 <b>넷이 한꺼번에</b> 무너진다 — ①고치던 입력칸이 화면에서 빠지고
     * ②바뀐 것이 없다고 판정돼 저장 버튼이 잠기고 ③제목 표시줄의 미저장 표시가 사라지고
     * ④그 상태에서 닫으면 <b>확인 없이</b> 닫혀 고친 내용이 조용히 사라진다.
     *
     * 값이 <b>영상 단위</b>라 직전 값을 유지하는 것이 의미상으로도 맞다(프레임이 달라도 같은 값이다).
     * ⚠ 이 창은 비모달이고 근거 「화면에서 지정」이 프레임 이동을 주 동선으로 쓰므로 <b>예외 경로가
     * 아니다</b>. 되돌리지 말 것.
     */
    const slots = useMemo<EditableSlot[]>(() => {
      if (data === undefined && lastKnownSlotsRef.current !== null) {
        return lastKnownSlotsRef.current;
      }
      const next: EditableSlot[] =
        editableItems.length === 0
          ? [{ metaKey: MANUAL_TIMESERIES_META_KEY, original: '', isNew: true }]
          : editableItems.map((it) => ({
              metaKey: it.metaKey,
              original: it.metaVal ?? '',
              isNew: false,
            }));
      if (data !== undefined) lastKnownSlotsRef.current = next;
      return next;
    }, [data, editableItems]);

    /** metaKey → 편집 중 텍스트. */
    const [drafts, setDrafts] = useState<Record<string, string>>({});
    /**
     * metaKey → <b>마지막으로 저장에 성공한</b> 값. 「저장 안 됨」 판정의 기준선이다.
     *
     * ★서버 재조회를 기다려 기준선을 갱신하지 않는 이유: 저장이 끝났는데도 재조회가 도착할 때까지
     * 「저장 안 됨」이 남아 있으면, 창 아래 저장 바가 계속 그 칸을 가리켜 사용자는 저장이 안 된 줄
     * 안다. 한쪽만 실패했을 때 <b>성공한 칸의 표시가 풀리는지</b>가 이 기준선으로 정해진다.
     */
    const [savedValues, setSavedValues] = useState<Record<string, string>>({});

    /**
     * 서버 값을 폼에 채우는 것은 <b>최초 1회</b>다(위 클래스 주석).
     * 저장에 성공하면 그 시점 값으로 기준선을 다시 잡는다 — 그래야 「저장 안 됨」이 풀린다.
     */
    const prefilledRef = useRef(false);
    useEffect(() => {
      if (data === undefined || prefilledRef.current) return;
      prefilledRef.current = true;
      const next: Record<string, string> = {};
      for (const slot of slots) next[slot.metaKey] = slot.original;
      setDrafts(next);
    }, [data, slots]);

    /** 아직 채워지지 않은 슬롯은 서버 원본을 그대로 보여준다(빈 칸으로 깜빡이지 않게). */
    const valueOf = useCallback(
      (slot: EditableSlot) => drafts[slot.metaKey] ?? slot.original,
      [drafts],
    );

    /**
     * 전송 대상 — 원본과 달라진 <b>편집 슬롯만</b>. 공백만 남긴 편집은 저장 의미가 없어 제외한다.
     * 기술메타·읽기 전용 메타는 슬롯이 아니므로 구조적으로 payload 에 들어갈 수 없다.
     */
    const dirtyItems = useMemo(
      () =>
        slots
          .filter((s) => {
            const draft = valueOf(s);
            const baseline = savedValues[s.metaKey] ?? s.original;
            return draft !== baseline && draft.trim().length > 0;
          })
          .map((s) => ({ metaKey: s.metaKey, metaVal: valueOf(s) })),
      [slots, valueOf, savedValues],
    );

    const dirty = !readOnly && dirtyItems.length > 0;
    useEffect(() => {
      onDirtyChange?.(dirty);
    }, [dirty, onDirtyChange]);

    useImperativeHandle(
      ref,
      () => ({
        save: async () => {
          if (readOnly || dirtyItems.length === 0) return true;
          try {
            await updateMutation.mutateAsync({ items: dirtyItems });
            // 저장된 값이 곧 새 기준선이다 — 재조회를 기다리지 않고 「저장 안 됨」을 내린다.
            setSavedValues((prev) => {
              const next = { ...prev };
              for (const item of dirtyItems) next[item.metaKey] = item.metaVal;
              return next;
            });
            setFailed(false);
            return true;
          } catch {
            setFailed(true);
            return false;
          }
        },
      }),
      [readOnly, dirtyItems, updateMutation],
    );

    // 저장에 성공한 뒤 다시 고치면 실패 표시를 내린다 — 지난 실패가 새 편집에 계속 붙어 있으면
    // 사용자는 무엇이 실패한 상태인지 알 수 없다.
    useEffect(() => {
      if (dirtyItems.length === 0) setFailed(false);
    }, [dirtyItems.length]);

    const description = readOnly
      ? TIMESERIES_COLUMN_DESCRIPTION.readOnly
      : TIMESERIES_COLUMN_DESCRIPTION.editable;

    return (
      <AnnotationColumn
        title={TIMESERIES_COLUMN_TITLE}
        description={description}
        dirty={dirty}
        failed={failed}
        fill={!readOnly}
        data-testid="timeseries-column"
      >
        {slots.map((slot, index) => {
          const value = valueOf(slot);
          const inputId = `timeseries-meta-input-${slot.metaKey}`;
          // 슬롯이 여럿이면 서로 구분되는 이름이 필요하다 — 저장 키를 이름에 쓰지 않으므로
          // 순번으로 가른다(사람이 읽는 이름에 내부 키를 노출하지 않는다).
          const inputLabel =
            slots.length > 1 ? `영상 분석 설명 입력 ${index + 1}` : '영상 분석 설명 입력';
          if (readOnly) {
            return (
              <p
                key={slot.metaKey}
                data-testid={`timeseries-readonly-${slot.metaKey}`}
                className={
                  value.trim() === ''
                    ? 'whitespace-pre-wrap break-words text-body-md text-gray-500'
                    : 'whitespace-pre-wrap break-words text-body-md text-gray-900'
                }
              >
                {value.trim() === '' ? NOT_FILLED_LABEL : value}
              </p>
            );
          }
          return (
            // 시안(`.colbody { display:flex; flex-direction:column }` + `.ta { flex:1 }`)대로
            // 입력 칸이 <b>칸 높이를 채운다</b> — 500자대 서술을 스크롤 없이 펼쳐 읽는 것이
            // 이 창을 만든 이유라, 고정 높이로 두면 창을 키워도 읽는 면적이 늘지 않는다.
            <div
              key={slot.metaKey}
              data-testid={`timeseries-segment-${slot.metaKey}`}
              className="flex min-h-0 flex-1 flex-col"
            >
              <label htmlFor={inputId} className="sr-only">
                {inputLabel}
              </label>
              <Textarea
                id={inputId}
                data-testid={`timeseries-input-${slot.metaKey}`}
                value={value}
                onChange={(e) =>
                  setDrafts((prev) => ({ ...prev, [slot.metaKey]: e.target.value }))
                }
                disabled={updateMutation.isPending}
                maxLength={MAX_LEN}
                aria-label={inputLabel}
                className="min-h-[220px] flex-1 resize-y text-body-md"
              />
              <div className="shrink-0 text-right text-caption text-gray-500">
                {value.length} / {MAX_LEN}자
              </div>
            </div>
          );
        })}
      </AnnotationColumn>
    );
  },
);
