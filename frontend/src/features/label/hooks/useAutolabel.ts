import { useCallback, useEffect, useRef } from 'react';

import { useIsBusyKind } from '@/stores/useLabelStore';

import { requestAutolabel, type AutolabelResponse, type DetectShapeType } from '../api';

import { useBusyTask } from './useBusyTask';

/** 결과 후처리에 전달되는 **그 호출의** 요청 컨텍스트. 공유 ref 로 넘기면 뒤 요청이 덮어쓴다. */
export interface AutolabelApplyContext {
  /** 이 결과를 만든 요청의 검출 형태. 안내 문구(탐지/분할) 분기에 쓴다. */
  shape?: DetectShapeType;
}

export interface UseAutolabelOptions {
  /**
   * 검출 결과를 작업본에 반영하는 후처리(병합·안내). **보호 구간(busy) 안에서** 호출되므로
   * 여기서 병합을 끝내야 한다 — 바깥에서 병합하면 busy 가 먼저 풀려 미완료 후처리와 재실행이
   * 겹친다. 여기서 던진 예외는 autolabel() 호출자에게 전파되며 busy 는 정상 해제된다.
   *
   * 요청 컨텍스트(ctx)는 **호출별 클로저**로 전달된다 — 공유 ref 로 읽으면 뒤이어 트리거된(그리고
   * 거부된) 요청이 값을 덮어써 앞선 결과의 안내 문구가 뒤바뀐다.
   */
  onApply?: (res: AutolabelResponse, ctx: AutolabelApplyContext) => void;
}

export interface UseAutolabelResult {
  /** 요청 진행 중 여부 (버튼 로딩용). store busy 파생값 — 로컬 진행 플래그를 두지 않는다. */
  isAutolabeling: boolean;
  /**
   * 현재 프레임에 AI 탐지를 실행한다.
   * - srcSn 미지정 시 즉시 null.
   * - 다른 장시간 작업이 진행 중이면 요청하지 않고 null(배타 실행) + **거부 안내 토스트**.
   * - 취소·리셋·프레임 전환 뒤 도착한 응답은 폐기되어 null 을 반환한다.
   * @param classIds (Phase 4 — R3) 검출 대상 클래스(COCO 영문명). 미지정/빈 → 전체 검출(하위호환).
   * @param shape    (Phase 4) 검출 형태 'BBOX'|'POLYGON'. 미지정이면 BE 기본(BBOX).
   * @param opts     (Phase 2 FE) 조절된 정밀도 옵션(confThreshold/simplifyTolerance). 미조절이면 미전달.
   * 반환: 적용 가능한 응답 또는 거부/폐기 시 null.
   */
  autolabel: (
    classIds?: string[],
    shape?: DetectShapeType,
    opts?: { confThreshold?: number; simplifyTolerance?: number },
  ) => Promise<AutolabelResponse | null>;
}

/**
 * AI 탐지(오토라벨) 수동 트리거 hook.
 *
 * 진행 상태·중복 차단·stale 폐기를 모두 store busy 토큰 하나로 처리한다(useBusyTask).
 * 로컬 inflight ref 를 함께 두면 취소가 store 만 풀어 재클릭이 조용히 무시된다.
 */
export function useAutolabel(
  srcSn: number | undefined,
  options: UseAutolabelOptions = {},
): UseAutolabelResult {
  const { runExclusiveOrNotify } = useBusyTask({ srcSn });
  const isAutolabeling = useIsBusyKind('AI_DETECT', srcSn);
  // 최신 콜백 참조 — onApply 가 매 렌더 새 함수여도 autolabel 의 참조 안정성을 지킨다.
  const onApplyRef = useRef(options.onApply);
  useEffect(() => {
    onApplyRef.current = options.onApply;
  }, [options.onApply]);

  const autolabel = useCallback(
    async (
      classIds?: string[],
      shape?: DetectShapeType,
      opts?: { confThreshold?: number; simplifyTolerance?: number },
    ): Promise<AutolabelResponse | null> => {
      if (srcSn === undefined) return null;
      return runExclusiveOrNotify('AI_DETECT', { srcSn }, async (isAlive) => {
        // shape/opts 미지정 + classIds 미지정 시 인자 없이 호출 — 기존 호출 형태 유지(무회귀).
        // shape 또는 opts 지정 시에만 확장 인자를 전달해 기존 시그니처 호출을 오염시키지 않는다.
        let res: AutolabelResponse;
        if (shape !== undefined || opts !== undefined) {
          res = await requestAutolabel(srcSn, classIds ?? [], shape, opts);
        } else if (classIds === undefined) {
          res = await requestAutolabel(srcSn);
        } else {
          res = await requestAutolabel(srcSn, classIds);
        }
        // 병합·안내는 보호 구간 안에서. 취소/프레임 전환 뒤면 반영하지 않는다.
        if (isAlive()) onApplyRef.current?.(res, { shape });
        return res;
      });
    },
    [srcSn, runExclusiveOrNotify],
  );

  return { isAutolabeling, autolabel };
}
