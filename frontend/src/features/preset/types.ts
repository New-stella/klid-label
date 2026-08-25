// 프리셋 도메인 타입 — 라벨 마스터(LS_LABEL) 단일 진실원 참조.
//
// 프리셋은 <이벤트유형 1건 + 라벨 목록> 둘로 이루어진다. 이름·설명을 갖지 않는다 —
// 이벤트유형코드에 UNIQUE 가 걸려 이벤트 1건에 프리셋 1건이 대응하므로 이름은 이벤트명의
// 중복이었고, 설명은 읽는 화면이 없었다. 사람이 읽는 이름은 서버가 실어 주는 `eventTypeNm` 이다.
//
// 마스터 연동:
// - 프리셋 코드는 라벨 마스터 PK(labelId)로 연결된다. 라벨명·형태는 스냅샷 없이
//   BE 가 조회 시 마스터를 실시간 join 하여 파생한다(FE 하드코딩 금지).
// - 요청은 labelIds(number[]) 만 전송한다. 형태(BBOX/POLYGON 등)는 마스터가 소유하므로
//   FE 에서는 읽기 전용으로 표시만 한다.
// - 미연결(linked=false) 코드는 legacy 코드 문자열을 라벨명으로 표시하고 '미연결' 배지로 구분한다.

import type { LabelMasterType } from '@/features/label/api/labelMaster';

/**
 * 프리셋 코드 1건 — BE `LabelCodeOptionResponse` 정합(마스터 실시간 join 결과).
 * - `labelId`   : 마스터 PK. 미연결 레거시 행이면 null.
 * - `code`      : 미연결 레거시 코드 문자열. 연결 행이면 null.
 * - `labelName` : 연결 시 마스터 라벨명(실시간), 미연결 시 legacy 코드.
 * - `labelType` : 마스터 형태 코드. 미연결이면 null.
 * - `linked`    : 활성 마스터 연결 여부.
 * - `bboxEnabled`/`polygonEnabled` : 마스터 형태 파생(읽기 전용).
 */
export interface PresetCode {
  labelId: number | null;
  code: string | null;
  labelName: string;
  labelType: LabelMasterType | null;
  linked: boolean;
  bboxEnabled: boolean;
  polygonEnabled: boolean;
}

export interface Preset {
  id: number;
  /**
   * 대상 이벤트유형코드 — 프리셋의 유일한 식별 축(DB NOT NULL + UNIQUE).
   *
   * 구 계약의 '미매핑(null)' 은 사라졌다 — 이벤트에 걸리지 않은 프리셋은 어느 영상에도
   * 매칭되지 않는 죽은 행이라 저장 자체가 막힌다.
   */
  eventTypeCd: string;
  /**
   * 이벤트 표시명 — <b>서버가 채워 주는 값</b>이다(운영자 표시명 → 관제 수신 유형명 →
   * 카테고리명 → 유형코드 4단 폴백).
   *
   * ★화면이 이벤트 목록으로 코드를 역해석하지 않는다. 필터 옵션 목록은 제외 대분류를
   * 감추므로 그 목록으로 역해석하면 해당 유형이 코드로만 노출된다(실제로 배회 프리셋이
   * 그렇게 표시되고 있었다). 판정은 서버 한 곳에만 둔다.
   *
   * 이름이 없는 유형은 최종 폴백으로 유형코드가 실려 온다 — 즉 항상 채워진다.
   */
  eventTypeNm: string;
  /** 프리셋 라벨 코드 목록 — 마스터 실시간 join 결과. */
  codes: PresetCode[];
  /**
   * 이 프리셋이 <b>실효</b>하는지 — 담긴 라벨 중 AI 검출 클래스에 매핑된 것이 하나라도 있으면 true.
   *
   * ★<b>서버가 판정해 내려주는 값</b>이며 화면이 라벨 매핑을 보고 다시 유도하지 않는다. 판정의
   * 단일 진실원은 오토라벨 보류 판정과 같은 곳이라, 화면이 재유도하면 규칙이 바뀔 때 배치와
   * 화면이 조용히 갈라진다.
   *
   * ⚠ 라벨을 <b>하나도 담지 않은</b> 프리셋도 false 다 — 그쪽은 사고가 아니라 「오토라벨 대상에서
   * 뺀다」는 사람의 선언이라 화면에서 다른 배지로 갈라 보여야 한다. 그 구분은 이 값이 아니라
   * <b>라벨 건수</b>로 한다.
   *
   * ⚠ 응답에 값이 없으면(구 서버·부분 응답) <b>true</b> 로 둔다 — 모르는 상태를 "적용되지 않는다"고
   * 단정하면 멀쩡한 프리셋 전건에 경고 배지가 붙는다(없는 사고를 지어내지 않는다).
   */
  effective: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * 프리셋 생성/수정 폼 — BE `PresetRequest` 정합.
 *
 * 요청 필드는 <b>둘뿐</b>이다. 형태는 마스터가 소유하므로 요청에서 받지 않고 labelIds 만 보낸다.
 */
export interface PresetForm {
  /** 대상 이벤트유형코드. 필수 — 비우면 서버가 400 으로 거부한다. */
  eventTypeCd: string;
  /** 선택한 라벨 마스터 PK 배열. BE 요청 body 의 labelIds. */
  labelIds: number[];
}
