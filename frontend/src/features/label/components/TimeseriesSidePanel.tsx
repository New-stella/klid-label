// 라벨링 페이지 우측 '메타' 탭에 삽입되는 접이식 시계열 메타 패널.
//
// useMeta / useUpdateMeta 훅 재사용(Phase 1에서 구현).
// GUI 통일(R3): 프레임 설명 패널과 동일한 MetaSection 래퍼 + 공통 textarea/저장 버튼 스타일 사용.
// 보안: React 자동 escape로 XSS 방어. dangerouslySetInnerHTML 미사용. maxLength 로 입력 크기 제한.

import { useEffect, useState } from 'react';

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

const TEXTAREA_ID = 'timeseries-meta-input';
// BE LS_DATA_META.META_VL 길이(2000)와 정합 — 초과 시 BE 400. FE 에서 미리 입력 제한.
const MAX_LEN = 2000;

/**
 * 수동 시계열 메타의 표준 metaKey (BE↔FE 공유 상수).
 *
 * 기존 메타가 0건인 영상에서 신규 등록 시 사용하는 결정적 키. BE 어댑터(toFrameMeta)가
 * 단일 item 의 metaVal 을 그대로 vlmText 로 표시하므로 저장→재조회 round-trip 이 성립한다.
 * VLM 세그먼트 키(start_sec-end_sec)와 충돌하지 않는다.
 */
export const MANUAL_TIMESERIES_META_KEY = 'manual-timeseries';

/**
 * 라벨링 RightPanel 메타 탭 접이식 시계열 메타 편집 패널.
 *
 * - MetaSection 토글로 펼침/접기
 * - useMeta(srcSn) 로 조회한 vlmText 표시
 * - 수정 후 저장 버튼으로 useUpdateMeta(srcSn) 호출
 * - dirty 체크: 원본 값과 다를 때만 저장 활성화
 *
 * 검토(승인/반려) 표면은 이 패널에 두지 않는다(2026-08-03 사용자 확정).
 * 시계열 메타 검토·수정 요구는 이 화면에서 메타 텍스트를 직접 수정하는 것으로 충족되며,
 * 검토 상태 확정은 영상 검수 승인 시 BE 자동 동결(MetaService.autoApproveOnVideoApproval)이
 * 담당한다. BE API(POST /v1/meta/{metaReviewSn}/approve|reject)는 존치하나 FE 진입점은 없다.
 */
export function TimeseriesSidePanel({ srcSn }: TimeseriesSidePanelProps) {
  const pushToast = useUiStore((s) => s.pushToast);

  const { data } = useMeta(srcSn);
  const updateMutation = useUpdateMeta(srcSn, {
    onSuccess: () =>
      pushToast({ variant: 'success', message: '시계열 메타가 저장되었습니다.' }),
    onError: () => pushToast({ variant: 'error', message: '저장에 실패했습니다.' }),
  });

  const [vlmText, setVlmText] = useState('');

  // data 변경 시 로컬 상태 동기화
  useEffect(() => {
    setVlmText(data?.vlmText ?? '');
  }, [data?.vlmText]);

  const dirty = vlmText !== (data?.vlmText ?? '');
  // 공백만인 텍스트 저장은 무의미 — 저장 비활성(기존 동작 존중).
  const canSave = dirty && vlmText.trim().length > 0;

  const handleSave = () => {
    if (!canSave) {
      return;
    }
    const sourceItems = data?.items ?? [];
    // 기존 메타 0건 → 표준 metaKey 로 신규 등록. 있으면 원본 metaKey 보존(수정).
    const items =
      sourceItems.length === 0
        ? [{ metaKey: MANUAL_TIMESERIES_META_KEY, metaVal: vlmText }]
        : sourceItems.length === 1
          ? [{ metaKey: sourceItems[0].metaKey, metaVal: vlmText }]
          : sourceItems.map((it) => ({ metaKey: it.metaKey, metaVal: it.metaVal }));
    updateMutation.mutate({ items });
  };

  return (
    <MetaSection title="시계열 메타">
      <label htmlFor={TEXTAREA_ID} className="sr-only">
        VLM 시계열 메타 입력
      </label>
      <textarea
        id={TEXTAREA_ID}
        value={vlmText}
        onChange={(e) => setVlmText(e.target.value)}
        disabled={updateMutation.isPending}
        maxLength={MAX_LEN}
        rows={8}
        aria-label="VLM 시계열 메타 입력"
        placeholder="외부 VLM 이 자동 생성한 시계열 정보입니다. 검토 후 수정할 수 있습니다."
        className={META_TEXTAREA_CLASS}
      />
      <MetaCharCount current={vlmText.length} max={MAX_LEN} />

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
