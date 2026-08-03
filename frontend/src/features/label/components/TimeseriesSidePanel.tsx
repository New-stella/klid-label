// 라벨링 페이지 우측 '메타' 탭에 삽입되는 접이식 시계열 메타 패널.
//
// useMeta / useUpdateMeta 훅 재사용(Phase 1에서 구현).
// GUI 통일(R3): 프레임 설명 패널과 동일한 MetaSection 래퍼 + 공통 textarea/저장 버튼 스타일 사용.
// 보안: React 자동 escape로 XSS 방어. dangerouslySetInnerHTML 미사용. maxLength 로 입력 크기 제한.

import { useEffect, useMemo, useState } from 'react';

import { useMeta } from '@/features/auto/hooks/useMeta';
import { useUpdateMeta } from '@/features/auto/hooks/useUpdateMeta';
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
 * 수동 시계열 메타의 표준 metaKey (BE↔FE 공유 상수).
 *
 * 기존 메타가 0건인 영상에서 신규 등록 시 사용하는 결정적 키.
 * VLM 세그먼트 키(start_sec-end_sec)와 충돌하지 않는다.
 */
export const MANUAL_TIMESERIES_META_KEY = 'manual-timeseries';

/**
 * 라벨링 RightPanel 메타 탭 접이식 시계열 메타 편집 패널.
 *
 * - MetaSection 토글로 펼침/접기
 * - useMeta(srcSn) 로 조회한 시계열 메타를 <b>세그먼트(metaKey)별 textarea</b> 로 표시
 * - 수정 후 저장 버튼으로 useUpdateMeta(srcSn) 호출 — <b>편집한 세그먼트만</b> 전송
 * - dirty 체크: 원본 값과 다른 세그먼트가 하나 이상일 때만 저장 활성화
 *
 * <h3>세그먼트별 편집인 이유 (2026-08-03 저장 버그 수정)</h3>
 * 구 구현은 모든 세그먼트의 값을 개행으로 이어붙인 단일 textarea 하나만 제공했다. 편집된 전문을
 * 다시 세그먼트로 되돌릴 방법이 없어 저장 시 분기가 이렇게 갈렸다 — 0건이면 신규 등록, 1건이면
 * 그 1건을 전문으로 덮어쓰기, <b>2건 이상이면 원본 값을 그대로 재전송</b>. 그 결과 세그먼트가
 * 2건 이상인 영상(=VLM 이 만든 실사용 대다수)에서는 사용자가 고쳐 저장해도 아무것도 바뀌지 않고
 * 성공 토스트만 떴다(조용한 무동작).
 *
 * 시계열 메타의 저장 단위는 {@code (rawSn, metaKey)} 이고 metaKey 는 VLM 이 만든 구간
 * ({@code "{start_sec}-{end_sec}"}) 이다. 편집 단위를 저장 단위와 일치시키면 구간 경계를 뭉개지
 * 않고 편집분이 그대로 반영된다. 여러 구간을 하나로 합쳐 한 키에 저장하면 시계열 구조가 파괴되므로
 * 하지 않는다.
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

  /** BE 원본 시계열 메타(기술메타 제외됨). 조회 전이면 빈 목록. */
  const sourceItems = useMemo(() => data?.items ?? [], [data?.items]);

  /** metaKey → 편집 중 텍스트. 조회 결과가 바뀌면 원본으로 재동기화한다. */
  const [drafts, setDrafts] = useState<Record<string, string>>({});

  useEffect(() => {
    const next: Record<string, string> = {};
    for (const it of sourceItems) {
      next[it.metaKey] = it.metaVal ?? '';
    }
    if (sourceItems.length === 0) {
      // 메타 0건 영상 — 신규 등록용 단일 입력.
      next[MANUAL_TIMESERIES_META_KEY] = '';
    }
    setDrafts(next);
  }, [sourceItems]);

  /** 화면에 렌더할 편집 슬롯. 0건이면 신규 등록 슬롯 1개. */
  const slots = useMemo(
    () =>
      sourceItems.length === 0
        ? [{ metaKey: MANUAL_TIMESERIES_META_KEY, original: '', isNew: true }]
        : sourceItems.map((it) => ({
            metaKey: it.metaKey,
            original: it.metaVal ?? '',
            isNew: false,
          })),
    [sourceItems],
  );

  /**
   * 전송 대상 — 원본과 달라진 슬롯만. 공백만 남긴 편집은 저장 의미가 없어 제외한다(기존 동작 유지).
   * 편집하지 않은 세그먼트는 보내지 않으므로 다른 사용자의 동시 수정을 덮어쓰지 않는다.
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
        // 신규 등록 슬롯은 구간 표기가 없으므로 라벨에 metaKey 를 노출하지 않는다.
        const inputLabel = slot.isNew
          ? '시계열 메타 입력'
          : `시계열 메타 ${slot.metaKey} 입력`;
        const value = drafts[slot.metaKey] ?? '';
        return (
          <div key={slot.metaKey} data-testid={`timeseries-segment-${slot.metaKey}`}>
            {!slot.isNew && (
              // 구간 표기는 BE 값 원문 그대로 노출한다(단위 변환·포맷팅 없음).
              <label
                htmlFor={inputId}
                className="block text-[11px] text-gray-400"
              >
                {slot.metaKey}
              </label>
            )}
            {slot.isNew && (
              <label htmlFor={inputId} className="sr-only">
                {inputLabel}
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
              rows={slots.length > 1 ? 4 : 8}
              aria-label={inputLabel}
              placeholder="외부 VLM 이 자동 생성한 시계열 정보입니다. 검토 후 수정할 수 있습니다."
              className={META_TEXTAREA_CLASS}
            />
            <MetaCharCount current={value.length} max={MAX_LEN} />
          </div>
        );
      })}

      <button
        type="button"
        onClick={handleSave}
        disabled={!canSave || updateMutation.isPending}
        className={META_SAVE_BUTTON_CLASS}
      >
        {updateMutation.isPending ? '저장 중...' : '저장'}
      </button>
    </MetaSection>
  );
}
