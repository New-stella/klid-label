/**
 * 조달 실패 사유 → 사람이 읽는 문구 · 다시 시도해 볼 만한가.
 *
 * <h3>왜 별도 모듈인가</h3>
 * 통신 모듈(`api.ts`)에 합치면 그 모듈을 통째로 모의하는 시험에서 이 판정까지 `undefined` 가 되어
 * 화면이 조용히 「모르는 값」 분기로 떨어진다(이 저장소에 실사고 이력이 있다). 판정은 통신과
 * 갈라 둔다.
 *
 * <h3>★ 모르는 사유가 와도 화면이 비지 않는다</h3>
 * 서버가 값을 넓히면 우리 표에 없는 코드가 온다. `Record` 조회는 모르는 키에 `undefined` 를 주고
 * React 는 그것을 <b>오류도 경고도 없이 조용히 무시</b>해 사유 칸만 빈칸이 된다. 그래서 폴백을
 * 두고, 그 폴백을 실제로 타는 픽스처를 회귀 가드에 함께 둔다(배선만 하고 안 타면 폴백을 지워도
 * 전건 초록이라 주장과 증명이 갈린다).
 *
 * ⚠ 사유 문구에 <b>경로·키·외부 응답 원문</b>을 담지 않는다 — 서버가 그것을 담지 않는 이유와 같다.
 *
 * @design INT-014
 */

import { PortalMaterialsFailureReason } from './types';

export interface PortalMaterialsFailureNotice {
  /** 무슨 일이 일어났는가 — 한 줄. */
  title: string;
  /** 다음에 무엇을 하면 되는가. */
  description: string;
  /**
   * 「다시 시도」를 권할 만한가.
   *
   * ★ <b>버튼을 숨기는 축이 아니다</b> — 어느 사유든 다시 착수할 수는 있다(서버가 막지 않는다).
   *   같은 답이 돌아올 사유에서 「다시 시도」를 <b>권하지 않을</b> 뿐이고, 그때는 조작을 보조
   *   위계로 낮춰 사람에게 알리는 쪽을 앞세운다.
   */
  retryWorthwhile: boolean;
}

/** 우리가 아는 사유. 서버가 값을 넓히면 아래 폴백이 받는다. */
const NOTICE: Record<PortalMaterialsFailureReason, PortalMaterialsFailureNotice> = {
  [PortalMaterialsFailureReason.NOT_CONFIGURED]: {
    title: '조달 연동이 설정되지 않았습니다.',
    description: '운영자가 포털 연동 설정을 채워야 풀립니다. 다시 시도해도 같은 결과입니다.',
    retryWorthwhile: false,
  },
  [PortalMaterialsFailureReason.FETCH_REJECTED]: {
    title: '포털에서 이 데이터셋의 소재를 내려주지 않았습니다.',
    description: '주소의 데이터셋 번호가 맞는지 확인해 주세요. 다시 물어도 같은 결과입니다.',
    retryWorthwhile: false,
  },
  [PortalMaterialsFailureReason.FETCH_FAILED]: {
    title: '포털에서 소재 정보를 가져오지 못했습니다.',
    description: '일시적인 장애일 수 있습니다. 잠시 후 다시 시도해 주세요.',
    retryWorthwhile: true,
  },
  [PortalMaterialsFailureReason.NO_DEPLOYMENT_ZIP]: {
    title: '이 데이터셋에 가져올 배포본이 없습니다.',
    description: '포털 쪽 소재 구성이 끝나야 합니다. 다시 시도해도 같은 결과입니다.',
    retryWorthwhile: false,
  },
  [PortalMaterialsFailureReason.MATERIAL_PATH_REJECTED]: {
    title: '소재를 열 수 없어 중단했습니다.',
    description: '안전 점검에 걸려 열지 않고 멈췄습니다. 운영자에게 알려 주세요.',
    retryWorthwhile: false,
  },
  [PortalMaterialsFailureReason.UNPACK_REJECTED]: {
    title: '배포본을 풀지 않고 멈췄습니다.',
    description: '배포본이 손상됐거나 허용 범위를 넘었습니다. 운영자에게 알려 주세요.',
    retryWorthwhile: false,
  },
  [PortalMaterialsFailureReason.IO_ERROR]: {
    title: '소재를 가져오는 중 오류가 발생했습니다.',
    description: '일시적인 장애일 수 있습니다. 잠시 후 다시 시도해 주세요.',
    retryWorthwhile: true,
  },
};

/**
 * 우리 표에 없는 사유가 왔을 때의 자리.
 *
 * ★ 사유 코드를 화면에 그대로 찍지 않는다 — 이용자에게 뜻이 없는 문자열이고, 서버가 사유를
 *   「값」으로 보존하는 목적은 화면 분기이지 노출이 아니다. 다시 시도는 권한다 — 모르는 사유를
 *   확정 실패로 단정하면 실제로 일시 장애였을 때 회복 경로를 막는다.
 */
const UNKNOWN: PortalMaterialsFailureNotice = {
  title: '소재를 가져오지 못했습니다.',
  description: '잠시 후 다시 시도해 주세요. 계속되면 운영자에게 알려 주세요.',
  retryWorthwhile: true,
};

/** 사유 → 안내. 모르는 값이면 폴백을 준다(`null`·`undefined` 포함). */
export function materialsFailureNotice(
  reason: PortalMaterialsFailureReason | null | undefined,
): PortalMaterialsFailureNotice {
  if (reason == null) return UNKNOWN;
  return NOTICE[reason] ?? UNKNOWN;
}
