// 라벨링 페이지 우측 '메타' 탭에 삽입되는 접이식 시계열 메타 패널.
//
// useMeta / useUpdateMeta 훅 재사용(Phase 1에서 구현).
// GUI 통일(R3): 프레임 설명 패널과 동일한 MetaSection 래퍼 + 공통 textarea/저장 버튼 스타일 사용.
// 보안: React 자동 escape로 XSS 방어. dangerouslySetInnerHTML 미사용. maxLength 로 입력 크기 제한.

import { useEffect, useMemo, useState } from 'react';

import { useMeta } from '@/features/auto/hooks/useMeta';
import { useUpdateMeta } from '@/features/auto/hooks/useUpdateMeta';
import {
  compareByStartSec,
  editableMetaLabel,
  formatReadOnlyMetaValue,
  isEditableMetaKey,
  readOnlyMetaLabel,
  MANUAL_TIMESERIES_META_KEY,
} from '@/features/auto/metaKeys';
import type { MetaItem } from '@/features/auto/types';
import { useUiStore } from '@/stores/useUiStore';

import {
  MetaCharCount,
  MetaSection,
  META_SAVE_BUTTON_CLASS,
  META_TEXTAREA_CLASS,
} from './MetaSection';

export interface TimeseriesSidePanelProps {
  srcSn: number | undefined;
}

// BE LS_DATA_META.META_VL 길이(2000)와 정합 — 초과 시 BE 400. FE 에서 미리 입력 제한.
const MAX_LEN = 2000;

/**
 * 수동 시계열 메타 표준 metaKey 재노출 — 기존 import 경로 호환.
 * 정본은 {@code features/auto/metaKeys} 이며 새 코드는 그쪽을 참조한다.
 */
export { MANUAL_TIMESERIES_META_KEY };

const READONLY_LABEL_CLASS = 'block text-[11px] text-gray-400';
const READONLY_VALUE_CLASS = 'whitespace-pre-wrap break-words text-sm text-gray-300';

/** 편집 슬롯(= 저장 단위 metaKey 하나). */
interface EditableSlot {
  metaKey: string;
  original: string;
  /** 기존 항목이 없어 새로 등록하는 슬롯인지. */
  isNew: boolean;
}

/**
 * 읽기 전용 행 — 편집 입력 없이 라벨/값만 렌더한다.
 *
 * 값은 React 텍스트 노드로만 출력해 자동 escape 된다(CWE-79). 라벨을 명시적으로 붙여
 * 스크린리더가 "무엇의 값"인지 읽을 수 있게 한다.
 */
function ReadonlyMetaRow({
  testId,
  label,
  value,
}: {
  testId: string;
  label: string;
  value: string;
}) {
  return (
    <div className="rounded border border-gray-700 p-2" data-testid={testId}>
      <span className={READONLY_LABEL_CLASS}>{label}</span>
      <p className={READONLY_VALUE_CLASS}>{value}</p>
    </div>
  );
}

/**
 * 라벨링 RightPanel 메타 탭 접이식 시계열 메타 편집 패널.
 *
 * - MetaSection 토글로 펼침/접기
 * - useMeta(srcSn) 조회 결과를 <b>편집 가능 키만</b> textarea 로 표시(전문 1개가 표준)
 * - 수정 후 저장 버튼으로 useUpdateMeta(srcSn) 호출 — <b>편집한 슬롯만</b> 전송
 * - dirty 체크: 원본 값과 다른 슬롯이 하나 이상일 때만 저장 활성화
 *
 * <h3>편집 단위 = 저장 단위(metaKey) (2026-08-03 저장 버그 수정)</h3>
 * 구 구현은 모든 항목의 값을 개행으로 이어붙인 단일 textarea 하나만 제공했다. 편집된 전문을
 * 다시 항목으로 되돌릴 방법이 없어 저장 시 분기가 이렇게 갈렸다 — 0건이면 신규 등록, 1건이면
 * 그 1건을 전문으로 덮어쓰기, <b>2건 이상이면 원본 값을 그대로 재전송</b>. 그 결과 항목이
 * 2건 이상인 영상에서는 사용자가 고쳐 저장해도 아무것도 바뀌지 않고 성공 토스트만 떴다
 * (조용한 무동작). 여러 키를 하나의 textarea 로 합치지 않는다.
 *
 * <h3>세 부류의 표시 규칙 (2026-08-06)</h3>
 * <ul>
 *   <li><b>편집 슬롯</b> — {@code vlm.description}(외부 위탁 verify 서술 전문)·
 *       {@code manual-timeseries}(수동 등록). 편집 허용 키 <b>화이트리스트</b>로 판정하며
 *       접두 문자열 파싱을 하지 않는다(fail-closed — BE 판정과 같은 방향). [req: R8]</li>
 *   <li><b>레거시 구간행</b> — 구 describe 산출물({@code "{start_sec}-{end_sec}"}). BE 계약상
 *       {@code items} 에 있어 편집이 가능하지만 화면에서는 편집 동선을 주지 않고 읽기 전용으로
 *       <b>병기</b>한다. 삭제·숨김하지 않는다(보존 확정). 정렬은 {@code start_sec} 숫자순. [req: R9]</li>
 *   <li><b>읽기 전용 메타</b> — BE {@code readOnlyMeta}(일치도 등). 값만 보여주고 전송하지 않는다
 *       (요청에 섞이면 BE 400). 미지의 키가 늘어도 일반적으로 렌더한다. [req: R8]</li>
 * </ul>
 *
 * <p>편집 가능한 항목이 하나도 없으면(메타 0건 / 레거시 구간뿐) 신규 등록 슬롯
 * ({@code manual-timeseries}) 1개를 제공한다 — 레거시 구간만 있는 영상에서도 전문을 작성할 수
 * 있어야 하며, 이때 레거시 구간 값은 덮이지 않는다.
 *
 * 검토(승인/반려) 표면은 이 패널에 두지 않는다(2026-08-03 사용자 확정).
 * 시계열 메타 검토·수정 요구는 이 화면에서 메타 텍스트를 직접 수정하는 것으로 충족되며,
 * 검토 상태 확정은 영상 검수 승인 시 BE 자동 동결(MetaService.autoApproveOnVideoApproval)이
 * 담당한다. BE API(POST /v1/meta/{metaReviewSn}/approve|reject)는 존치하나 FE 진입점은 없다.
 *
 * 영상 기술메타({@code video.*})는 이 패널의 대상이 아니다 — BE 가 {@code technicalMeta} 로
 * 분리해 내려주고 수정 요청도 400 으로 거부한다. 여기서는 표시·편집 모두 하지 않는다.
 */
export function TimeseriesSidePanel({ srcSn }: TimeseriesSidePanelProps) {
  const pushToast = useUiStore((s) => s.pushToast);

  const { data } = useMeta(srcSn);
  const updateMutation = useUpdateMeta(srcSn, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '시계열 메타가 저장되었습니다.' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다.' }),
  });

  /** BE 원본 시계열 메타(기술메타·읽기 전용 메타 제외됨). 조회 전이면 빈 목록. */
  const sourceItems = useMemo<MetaItem[]>(() => data?.items ?? [], [data?.items]);

  /** 화면 전용 읽기 메타(일치도 등). 구 BE 응답이면 빈 목록. */
  const readOnlyItems = useMemo<MetaItem[]>(
    () => data?.readOnlyMeta ?? [],
    [data?.readOnlyMeta],
  );

  /**
   * 편집 대상(화이트리스트 통과분) ↔ 레거시 구간행을 <b>한 번의 분할</b>로 가른다.
   *
   * 두 목록을 각각 필터로 만들면 판정이 두 곳이 되어 한쪽만 바뀌어도 화면은 그럴듯하게 보인다
   * (같은 항목이 편집 슬롯과 읽기 전용 행에 동시에 뜨는 식). 분할점을 하나로 둬 그 클래스의
   * 드리프트를 구조적으로 없앤다. 레거시는 시간축(start_sec) 오름차순. [req: R8][req: R9]
   */
  const { editableItems, legacyItems } = useMemo(() => {
    const editable: MetaItem[] = [];
    const legacy: MetaItem[] = [];
    for (const it of sourceItems) {
      (isEditableMetaKey(it.metaKey) ? editable : legacy).push(it);
    }
    legacy.sort((a, b) => compareByStartSec(String(a.metaKey), String(b.metaKey)));
    return { editableItems: editable, legacyItems: legacy };
  }, [sourceItems]);

  /** 화면에 렌더할 편집 슬롯. 편집 대상이 없으면 신규 등록 슬롯 1개. */
  const slots = useMemo<EditableSlot[]>(
    () =>
      editableItems.length === 0
        ? [{ metaKey: MANUAL_TIMESERIES_META_KEY, original: '', isNew: true }]
        : editableItems.map((it) => ({
            metaKey: it.metaKey,
            original: it.metaVal ?? '',
            isNew: false,
          })),
    [editableItems],
  );

  /** metaKey → 편집 중 텍스트. 조회 결과가 바뀌면 원본으로 재동기화한다. */
  const [drafts, setDrafts] = useState<Record<string, string>>({});

  useEffect(() => {
    const next: Record<string, string> = {};
    for (const slot of slots) {
      next[slot.metaKey] = slot.original;
    }
    setDrafts(next);
  }, [slots]);

  /**
   * 전송 대상 — 원본과 달라진 <b>편집 슬롯만</b>. 공백만 남긴 편집은 저장 의미가 없어 제외한다
   * (기존 동작 유지). 편집하지 않은 슬롯은 보내지 않으므로 다른 사용자의 동시 수정을 덮어쓰지 않는다.
   * 레거시 구간·읽기 전용 메타·기술메타는 슬롯이 아니므로 구조적으로 payload 에 들어갈 수 없다.
   */
  const dirtyItems = slots
    .filter((s) => {
      const draft = drafts[s.metaKey] ?? '';
      return draft !== s.original && draft.trim().length > 0;
    })
    .map((s) => ({ metaKey: s.metaKey, metaVal: drafts[s.metaKey] ?? '' }));

  const canSave = dirtyItems.length > 0;

  const handleSave = () => {
    if (!canSave) {
      return;
    }
    updateMutation.mutate({ items: dirtyItems });
  };

  return (
    <MetaSection title="시계열 메타">
      {slots.map((slot) => {
        const inputId = `timeseries-meta-input-${slot.metaKey}`;
        const fieldLabel = editableMetaLabel(slot.metaKey);
        const inputLabel = `${fieldLabel} 입력`;
        const value = drafts[slot.metaKey] ?? '';
        return (
          <div key={slot.metaKey} data-testid={`timeseries-segment-${slot.metaKey}`}>
            {slot.isNew ? (
              <label htmlFor={inputId} className="sr-only">
                {inputLabel}
              </label>
            ) : (
              <label htmlFor={inputId} className={READONLY_LABEL_CLASS}>
                {fieldLabel}
              </label>
            )}
            <textarea
              id={inputId}
              value={value}
              onChange={(e) =>
                setDrafts((prev) => ({ ...prev, [slot.metaKey]: e.target.value }))
              }
              disabled={updateMutation.isPending}
              maxLength={MAX_LEN}
              rows={8}
              aria-label={inputLabel}
              placeholder="자동 생성된 시계열 정보입니다. 검토 후 수정할 수 있습니다."
              className={META_TEXTAREA_CLASS}
            />
            <MetaCharCount current={value.length} max={MAX_LEN} />
          </div>
        );
      })}

      {/* 읽기 전용 메타(일치도 등) — 값만 표시하고 저장 요청에 싣지 않는다. [req: R8] */}
      {readOnlyItems.length > 0 && (
        <div className="space-y-2 pt-1" aria-label="시계열 참고 정보">
          {readOnlyItems.map((item) => (
            <ReadonlyMetaRow
              key={item.metaSn}
              testId={`timeseries-readonly-${item.metaKey}`}
              label={readOnlyMetaLabel(item.metaKey)}
              value={formatReadOnlyMetaValue(item.metaKey, item.metaVal ?? '')}
            />
          ))}
        </div>
      )}

      <button
        type="button"
        onClick={handleSave}
        disabled={!canSave || updateMutation.isPending}
        className={META_SAVE_BUTTON_CLASS}
      >
        {updateMutation.isPending ? '저장 중...' : '저장'}
      </button>

      {/* 레거시 구간행 — 구 산출물 보존. 편집 동선 없음. [req: R9] */}
      {legacyItems.length > 0 && (
        <div className="space-y-2 pt-1" aria-label="이전 구간별 시계열 정보">
          <p className="text-[11px] text-gray-500">
            이전 방식으로 생성된 구간별 정보입니다. 참고용이며 수정할 수 없습니다.
          </p>
          {legacyItems.map((item) => (
            <ReadonlyMetaRow
              key={item.metaSn}
              testId={`timeseries-legacy-${item.metaKey}`}
              // 구간 표기는 BE 값 원문 그대로 노출한다(단위 변환·포맷팅 없음).
              label={item.metaKey}
              value={item.metaVal ?? ''}
            />
          ))}
        </div>
      )}
    </MetaSection>
  );
}
