// 라벨 마스터 형태 코드 → 사용자 표기(한글) 매핑.
//
// 형태 코드(BBOX|POLYGON|POINT|SKELETON)의 진실원은 `../api/labelMaster` 의 LabelMasterType 이다.
// 표시 라벨은 라벨 마스터 관리 화면(select/목록)과 프리셋 화면(칩/체크박스)에서 공유하므로
// 특정 페이지가 아닌 label 피처 상수로 둔다(component.md — Feature 는 Page 에 의존하지 않는다).

import type { LabelMasterType } from '../api/labelMaster';

/** 형태 코드 → 사용자 표기. 목록·폼 select·프리셋 칩에서 공유. */
export const TYPE_LABEL: Record<LabelMasterType, string> = {
  BBOX: '바운딩박스',
  POLYGON: '폴리곤',
  POINT: '포인트',
  SKELETON: '스켈레톤',
};
