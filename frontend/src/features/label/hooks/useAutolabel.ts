import { useCallback, useRef, useState } from 'react';

import { requestAutolabel, type AutolabelResponse, type DetectShapeType } from '../api';

export interface UseAutolabelResult {
  /** 요청 진행 중 여부 (버튼 로딩 + 진행 중 신규 요청 무시 가드). */
  isAutolabeling: boolean;
  /**
   * 현재 프레임에 YOLO 오토라벨을 실행한다.
   * - srcSn 미지정 시 즉시 null.
   * - 진행 중이면 무시(중복 방지 — BE in-flight 409 이전 클라이언트 1차 가드).
   * - 응답 도착 시 요청 시점 srcSn 과 현재 srcSn 이 다르면 폐기(프레임 전환 stale 가드).
   * @param classIds (Phase 4 — R3) 검출 대상 클래스(COCO 영문명). 미지정/빈 → 전체 검출(하위호환).
   * @param shape    (Phase 4) 검출 형태 'BBOX'|'POLYGON'. 미지정이면 BE 기본(BBOX).
   * @param opts     (Phase 2 FE) 조절된 정밀도 옵션(confThreshold/simplifyTolerance). 미조절이면 미전달.
   * 반환: 적용 가능한 응답 또는 폐기/무시 시 null.
   */
  autolabel: (
    classIds?: string[],
    shape?: DetectShapeType,
    opts?: { confThreshold?: number; simplifyTolerance?: number },
  ) => Promise<AutolabelResponse | null>;
}

/**
 * Phase 3 — YOLO 오토라벨 수동 트리거 mutation hook.
 *
 * useSam2Segment 와 동일한 동시성 가드 패턴:
 * - srcSn 미지정 시 즉시 null.
 * - 진행 중 재요청 무시(inflightRef, state 비동기 갱신 race 방지).
 * - 프레임 전환 후 도착한 응답 폐기(요청 시점 srcSn vs 현재 srcSn).
 */
export function useAutolabel(srcSn: number | undefined): UseAutolabelResult {
  const [isAutolabeling, setIsAutolabeling] = useState(false);
  const currentSrcSnRef = useRef<number | undefined>(srcSn);
  currentSrcSnRef.current = srcSn;
  const inflightRef = useRef(false);

  const autolabel = useCallback(
    async (
      classIds?: string[],
      shape?: DetectShapeType,
      opts?: { confThreshold?: number; simplifyTolerance?: number },
    ): Promise<AutolabelResponse | null> => {
      if (srcSn === undefined) return null;
      if (inflightRef.current) return null; // 진행 중 신규 요청 무시
      const requestedSrcSn = srcSn;
      inflightRef.current = true;
      setIsAutolabeling(true);
      try {
        // shape/opts 미지정 + classIds 미지정 시 인자 없이 호출 — 기존 호출 형태 유지(무회귀).
        // shape 또는 opts 지정 시에만 확장 인자를 전달해 기존 시그니처 호출을 오염시키지 않는다.
        let res: AutolabelResponse;
        if (shape !== undefined || opts !== undefined) {
          res = await requestAutolabel(requestedSrcSn, classIds ?? [], shape, opts);
        } else if (classIds === undefined) {
          res = await requestAutolabel(requestedSrcSn);
        } else {
          res = await requestAutolabel(requestedSrcSn, classIds);
        }
        // 프레임 전환 후 도착한 응답이면 폐기.
        if (currentSrcSnRef.current !== requestedSrcSn) return null;
        return res;
      } finally {
        inflightRef.current = false;
        setIsAutolabeling(false);
      }
    },
    [srcSn],
  );

  return { isAutolabeling, autolabel };
}
