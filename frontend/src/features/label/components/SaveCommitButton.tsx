import { useState } from 'react';

import { Button } from '@/components/common/Button';
import { useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';

import { useUpdateLabels } from '../hooks/useUpdateLabels';
import { formatBindingKeys } from '../hooks/labelingKeymap';
import type { Label } from '../types';

export interface SaveCommitButtonProps {
  /** 저장 대상 프레임 식별자. */
  srcSn: number | undefined;
  /** 저장할 라벨 전체(전량 교체 저장). */
  labels: Label[];
  /** 참이면 포털 전용 저장 경로로 라우팅한다(내부 저장 API 는 포털 채널에서 거부된다). */
  portalMode?: boolean;
  /**
   * 영상이 재비식별 대기 등으로 잠겨 있으면 저장을 비활성화한다.
   * 편집 차단(장시간 작업 진행) 여부와는 **별도 축**이며 함께 판정한다 —
   * 호출부가 전달하지 않으면 잠긴 영상에서 저장 버튼이 눌리는 것처럼 보인다.
   */
  locked?: boolean;
  /** 저장 성공 후 콜백. */
  onSaved?: () => void;
  /**
   * ★화면이 저장 절차를 소유할 때 주입한다(낙관적 동시성 토큰 `labelVersion`, 409 저장 충돌
   *   안내, 포털/내부 경로 라우팅, 잠금 토스트를 화면이 함께 처리해야 하는 경우).
   *   주입하면 이 컴포넌트는 **자체 저장을 수행하지 않고** 위임한다 — 같은 화면에 저장 경로가
   *   둘 생기면 한쪽만 토큰을 실어 다른 사용자의 라벨이 조용히 삭제된다(lost update).
   *   미지정 시에는 기존대로 이 컴포넌트가 자체 저장한다(단독 사용 경로 보존).
   */
  onRequestSave?: () => void | Promise<void>;
  /** `onRequestSave` 주입 시 진행 상태 — 스피너 + 중복 클릭 차단. */
  saving?: boolean;
}

/**
 * UI-053 저장 버튼 — **라벨링 화면의 유일한 저장 진입점**이며 위치는
 * 캔버스 상단 옵션바다(SCREEN-005 §캔버스 상단 옵션바). 헤더에도 좌측 도구바에도 두지 않는다.
 * Ctrl+S 단축키와 동일 동작.
 *
 * 클릭 시 작업본을 임시저장한다(버전 스냅샷은 만들지 않는다 — 버전은 검수 승인 시점에 BE 가 생성,
 * SFR-08). 자체 저장 경로에서는 useUpdateLabels 훅을 통해 호출해 저장 성공 시
 * LABEL/VIDEO/ASSIGNMENT/REVIEW 캐시를 일괄 invalidate 한다.
 *
 * ⚠️ 자체 저장 경로의 portalMode 주의: 자체 저장은 **내부 전용** PUT /frames/{srcSn}/labels 를
 *   호출한다(useUpdateLabels → putLabels). PORTAL 채널 사용자는 이 엔드포인트에서 403 을 받는다
 *   (포털 작업본은 LS_PORTAL_USER_LABEL 별도 적재 — ADR-013). 포털 저장은 화면이
 *   `onRequestSave` 로 포털 저장 훅(useSavePortalLabels)을 주입해 라우팅한다.
 */
export function SaveCommitButton({
  srcSn,
  labels,
  portalMode = false,
  locked = false,
  onSaved,
  onRequestSave,
  saving = false,
}: SaveCommitButtonProps) {
  if (portalMode && onRequestSave === undefined && import.meta.env.DEV) {
    // eslint-disable-next-line no-console
    console.warn(
      '[SaveCommitButton] portalMode=true 인데 onRequestSave 가 없습니다 — 자체 저장은 내부 PUT 만 수행하며 포털 저장을 지원하지 않습니다(403 위험).',
    );
  }
  const [error, setError] = useState<string | null>(null);
  const clearDirty = useLabelStore((s) => s.clearDirty);
  const dirtyCount = useLabelStore((s) => s.dirtyLabels.size);
  // 다른 장시간 작업(AI/불러오기)이 진행 중이면 저장 버튼도 비활성 — 눌러도 거부될 뿐이다.
  const editBlocked = useIsEditBlocked(srcSn);

  const { mutateAsync: updateLabels, isPending: selfSaving } = useUpdateLabels(srcSn);
  const delegated = onRequestSave !== undefined;
  const loading = delegated ? saving : selfSaving;

  async function handleSave() {
    if (editBlocked || locked) return;
    if (delegated) {
      // 화면이 저장 절차를 소유한다 — 오류 안내·충돌 안내도 화면이 담당한다.
      await onRequestSave?.();
      return;
    }
    if (srcSn === undefined) return;
    setError(null);
    try {
      // 폐기·거부된 저장(null)이면 dirty 를 비우거나 완료 콜백을 호출하지 않는다 —
      // 저장되지 않았는데 "저장됨"으로 취급하면 미저장 작업이 그대로 사라진다.
      const saved = await updateLabels(labels);
      if (saved === null) return;
      clearDirty();
      onSaved?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : '저장 실패');
    }
  }

  const saveKeys = formatBindingKeys('edit.save');

  return (
    <div className="flex flex-col items-end gap-1">
      <Button
        type="button"
        size="sm"
        onClick={handleSave}
        loading={loading}
        disabled={srcSn === undefined || editBlocked || locked}
        aria-label="저장"
        title={saveKeys ? `저장 (${saveKeys})` : '저장'}
        data-testid="label-toolbar-save"
      >
        저장 {dirtyCount > 0 && <span className="ml-1 text-caption">({dirtyCount})</span>}
      </Button>
      {error && (
        <span role="alert" className="text-caption text-danger">
          {error}
        </span>
      )}
    </div>
  );
}
