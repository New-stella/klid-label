// 포털 작업 화면의 메타·이벤트 어노테이션 계약 타입 — BE DTO 와 1:1.
//
// 창구: API-234/235(프레임 메타 Load·저장) · API-236/237(이벤트 어노테이션 Load·저장).
//
// ★이 채널은 <b>포털 전용 창구만</b> 부른다. 같은 값을 고치는 내부 창구를 부르면 원장 컬럼 쓰기·
//   재검토 표시·관제 통지·동결본 재동결이 함께 일어나 「원본·데이터마트를 수정하지 않는다(단방향)」는
//   최상위 불변이 한 번에 깨진다. 그 위반은 「중복 구현을 피하자」는 가장 자연스러운 판단에서 나온다.

// @design SCREEN-029 @design API-234 @design API-235 @design API-236 @design API-237 @design AC-1068

import type { EventAnnotationPayload } from '@/features/label/api/eventAnnotation';

/**
 * 메타 원소가 <b>영상 축인지 프레임 축인지</b>.
 *
 * ★★원소를 식별하는 것은 메타 키 단독이 아니라 <b>(축, 메타 키) 쌍</b>이다 — 개인정보 세 항목이
 * 영상 축과 프레임 축 양쪽에 <b>같은 이름</b>으로 있어, 키만 보면 영상 값과 프레임 값이 한 목록에서
 * 섞인다. 저장할 때도 받은 축을 그대로 되돌려 보내야 영상 축 값이 프레임에 매달리지 않는다.
 */
export type PortalMetaScope = 'video' | 'frame';

/**
 * <b>원본 쪽</b> 값의 출처. ★{@link PortalMetaItem.overridden} 과 <b>다른 축</b>이다 —
 * 그쪽은 「내 값이 덮었는가」이고 이쪽은 「덮인 쪽이 무엇이었는가」다. 둘을 합치면
 * 「자동으로 채워져 있던 값을 내가 바꿨다」를 표현하지 못한다.
 */
export type PortalMetaSource = 'MANUAL' | 'DERIVED' | 'STORED' | 'NONE';

/** 메타 원소 1건. */
export interface PortalMetaItem {
  /** 메타 키. 내부 원장과 같은 키 규격. */
  metaKey: string;
  /** 메타 값. 값이 없으면 `null`. */
  metaVl: string | null;
  scope: PortalMetaScope;
  /** 본인 오버레이가 <b>원본을 가린</b> 값인지. */
  overridden: boolean;
  source: PortalMetaSource;
}

/** 프레임 메타 Load·저장 응답(API-234·API-235). 세 목록의 배분은 <b>서버가 소유</b>한다. */
export interface PortalFrameMeta {
  rawSn: number;
  srcSn: number;
  /** 확인·수정·추가할 수 있는 메타. */
  items: PortalMetaItem[];
  /** 표시만 하고 고칠 수 없는 메타. */
  readOnlyMeta: PortalMetaItem[];
  /** 영상 기술 메타. 사람이 고치는 값이 아니다. */
  technicalMeta: PortalMetaItem[];
}

/** 저장 요청의 원소 1건(API-235). */
export interface PortalMetaSaveItem {
  metaKey: string;
  /** 비워 보내면 값 없음으로 적재된다(내부 원장과 같은 시맨틱). */
  metaVl: string | null;
  scope: PortalMetaScope;
}

/**
 * 이벤트 어노테이션 Load·저장 응답(API-236·API-237).
 *
 * 본문은 <b>내부 원장과 같은 구조체를 그대로</b> 담는다 — 키별로 펴지 않는다. 펴면 산출 문서와
 * 모양이 갈려 내보낼 때마다 재조립이 필요하고 중첩 구조가 무너진다.
 */
export interface PortalEventAnnotation {
  rawSn: number;
  /** 없으면 `null`(서버가 지어내지 않는다). */
  annotation: EventAnnotationPayload | null;
  overridden: boolean;
}
