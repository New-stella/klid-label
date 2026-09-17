/**
 * 데이터셋 영상 **등록** 실패 사유 → 사람이 읽는 문구 · 다시 등록해 볼 만한가.
 * [@design SCREEN-046] [@design API-253] [@design API-262]
 *
 * 조달 실패 사유 모듈(`failureReason.ts`)과 같은 모양이다 — 축이 다르다(조달 = 압축본을 가져오는
 * 단계 · 등록 = 가져온 해제본을 원장에 올리는 단계). 두 표를 합치면 같은 이름의 사유(`IO_ERROR`)가
 * 다른 문구를 가져야 하는데 하나로 뭉개진다.
 *
 * <h3>왜 별도 모듈인가</h3>
 * 통신 모듈(`api.ts`)에 합치면 그 모듈을 통째로 모의하는 시험에서 이 판정까지 `undefined` 가 되어
 * 화면이 조용히 「모르는 값」 분기로 떨어진다(이 저장소에 실사고 이력이 있다).
 *
 * <h3>★ 같은 답이 돌아올 사유와 그렇지 않은 사유를 가른다</h3>
 * 구조 불일치 계열(내용 없음 · 영상 없음 · 키 중복·형식 · 짝 불일치 · 문서 판독 불가 · JPEG 아님 ·
 * 값 규격 위반 · 심볼릭 링크)은 배포본이 바뀌기 전에는 재착수해도 같은 사유로 다시 실패한다. 그래서
 * 「다시 등록」을 <b>권하지 않고</b> 문구가 「같은 소재로는 같은 결과입니다」를 말한다. 버튼을
 * 숨기는 축이 아니다 — 어느 사유든 다시 착수할 수는 있다(서버가 막지 않는다).
 *
 * ⚠ <b>영상 파일명 없음(`VIDEO_FILENAME_MISSING`)은 구조 계열이지만 안내가 다르다.</b> 2026-09-16
 *   실사고의 사유였다 — 배포본 스키마가 갈려 파서가 파일명을 못 읽은 것이고, 파서를 고쳐 배포한
 *   뒤 사람이 한 번 다시 등록하면 풀린다. 「같은 결과」라고만 적으면 고쳐진 뒤에도 아무도 누르지
 *   않는다. 그래서 「저작도구가 갱신된 뒤에는 다시 등록해 볼 수 있습니다」로 안내한다.
 *
 * <h3>★ 모르는 사유가 와도 화면이 비지 않는다</h3>
 * `Record` 조회는 모르는 키에 `undefined` 를 주고 React 는 그것을 조용히 무시한다. 폴백을 두고,
 * 그 폴백을 실제로 타는 픽스처를 회귀 가드에 함께 둔다.
 *
 * ⚠ 사유 문구에 경로·표식 파일 이름·사유 코드를 담지 않는다.
 */

import { PortalDatasetRegistrationFailureReason } from './types';

export interface PortalDatasetRegistrationFailureNotice {
  /** 무슨 일이 일어났는가 — 한 줄. */
  title: string;
  /** 다음에 무엇을 하면 되는가. */
  description: string;
  /**
   * 「다시 등록」을 권할 만한가.
   *
   * ★ 버튼을 숨기는 축이 아니다 — 같은 답이 돌아올 사유에서 권하지 않을 뿐이고, 그때는 조작을
   *   보조 위계로 낮춘다.
   */
  retryWorthwhile: boolean;
}

/** 구조 불일치 계열이 공통으로 말하는 다음 걸음. */
const SAME_RESULT = '같은 소재로는 같은 결과입니다. 포털 쪽 배포본 구성을 확인해 주세요.';

/** 우리가 아는 사유. 서버가 값을 넓히면 아래 폴백이 받는다. */
const NOTICE: Record<PortalDatasetRegistrationFailureReason, PortalDatasetRegistrationFailureNotice> =
  {
    [PortalDatasetRegistrationFailureReason.CONTENT_MISSING]: {
      title: '가져온 소재에 등록할 내용이 없습니다.',
      description: SAME_RESULT,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.SYMLINK_REJECTED]: {
      title: '소재 안에 열 수 없는 항목이 있어 등록을 멈췄습니다.',
      description: `안전 점검에 걸려 열지 않고 멈췄습니다. ${SAME_RESULT}`,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.NO_VIDEO]: {
      title: '가져온 소재에 영상이 없습니다.',
      description: SAME_RESULT,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING]: {
      title: '소재 문서에서 영상 파일명을 읽지 못했습니다.',
      description:
        '배포본 형식이 저작도구가 아는 것과 다를 수 있습니다. 저작도구가 갱신된 뒤에는 다시 등록해 볼 수 있습니다.',
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.AMBIGUOUS_VIDEO_KEY]: {
      title: '소재의 영상 식별자가 하나로 정해지지 않습니다.',
      description: SAME_RESULT,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.INVALID_VIDEO_KEY]: {
      title: '소재의 영상 식별자 형식이 맞지 않습니다.',
      description: SAME_RESULT,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.PAIR_MISMATCH]: {
      title: '소재의 이미지와 문서가 짝이 맞지 않습니다.',
      description: SAME_RESULT,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.DOCUMENT_UNREADABLE]: {
      title: '소재 문서를 읽을 수 없습니다.',
      description: SAME_RESULT,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.IMAGE_NOT_JPEG]: {
      title: '소재의 이미지가 JPEG 형식이 아닙니다.',
      description: SAME_RESULT,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.INVALID_VALUE]: {
      title: '소재 문서의 값이 규격에 맞지 않습니다.',
      description: SAME_RESULT,
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.DISABLED]: {
      title: '영상 등록이 꺼져 있습니다.',
      description: '운영자가 등록을 켜야 풀립니다. 켜진 뒤에 다시 등록해 주세요.',
      retryWorthwhile: false,
    },
    [PortalDatasetRegistrationFailureReason.IO_ERROR]: {
      title: '영상을 등록하는 중 오류가 발생했습니다.',
      description: '일시적인 장애일 수 있습니다. 잠시 후 다시 등록해 주세요.',
      retryWorthwhile: true,
    },
  };

/**
 * 우리 표에 없는 사유가 왔을 때의 자리.
 *
 * ★ 사유 코드를 화면에 그대로 찍지 않는다. 다시 등록은 권한다 — 모르는 사유를 확정 실패로
 *   단정하면 실제로 일시 장애였을 때 회복 경로를 막는다.
 */
const UNKNOWN: PortalDatasetRegistrationFailureNotice = {
  title: '가져온 소재는 그대로 남아 있습니다.',
  description: '잠시 후 다시 등록해 주세요. 계속되면 운영자에게 알려 주세요.',
  retryWorthwhile: true,
};

/** 사유 → 안내. 모르는 값이면 폴백을 준다(`null`·`undefined` 포함). */
export function registrationFailureNotice(
  reason: string | null | undefined,
): PortalDatasetRegistrationFailureNotice {
  if (reason == null) return UNKNOWN;
  return (NOTICE as Record<string, PortalDatasetRegistrationFailureNotice>)[reason] ?? UNKNOWN;
}
