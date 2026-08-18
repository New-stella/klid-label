// 데이터마트 작업 데이터 다운로드 실패 안내 — 사유별 문구 판정. @design SCREEN-028
//
// ★ 사유를 뭉뚱그리지 않는다. 이 경로는 서로 다른 네 가지 이유로 거부될 수 있고 사용자가 해야 할
//   다음 행동이 각각 다르다(기다린다 / 재처리를 기다린다 / 먼저 라벨을 저장한다 / 접근 불가).
//   한 문구로 합치면 "다시 시도"만 반복하게 된다.
//
// ★ 판정은 **상태코드**로 한다. 이 요청은 `responseType: 'blob'` 이라 실패 본문이 표준 응답 형태로
//   해석되지 않고, 그 경우 클라이언트는 상태코드에서 코드를 추론한다 — 그 추론에 없는 값(410·429)은
//   일반 오류로 떨어지므로 코드 문자열에 기대면 두 사유가 통째로 뭉개진다.
//
// ★ **응답이 아예 오지 않는 실패**를 서버가 준 네 가지와 갈라 놓는다. 전송 중단·네트워크 단절·제한시간
//   초과는 상태코드가 없어 `status === 0` 으로 올라오는데(응답이 없으면 `ApiError.fromStatus(0, …)`),
//   이걸 그냥 두면 «잠시 후 다시 시도» 로 떨어진다. 그 문구는 "서버가 잠깐 바쁘다" 는 뜻이라
//   **기다려도 달라지지 않는 상황에 재시도를 권하게 된다** — 이 응답은 GB 급이라 사용자는 매번
//   전량 전송을 유발하고 서버는 매번 그것을 버린다.
//   ⚠ axios 의 실패 코드(`ECONNABORTED`·`ERR_NETWORK`·`ERR_CANCELED`) 로 더 잘게 가르지 않는다 —
//     공용 클라이언트가 이미 `ApiError` 로 감싸며 그 코드를 남기지 않고, 남은 것은 버전마다 달라지는
//     영문 메시지뿐이라 문자열 매칭은 조용히 깨진다. 세 원인 모두 사용자가 할 일이 같으므로
//     ("연결을 확인하고 다시") 한 문구로 둔다.
//
// ★ 서버 메시지를 그대로 노출하지 않는다(CWE-209).

import { ApiError } from '@/lib/api/errors';

/** 저장 라벨이 없어 내려받을 것이 없을 때. */
export const DOWNLOAD_ERROR_NO_LABEL =
  '내려받을 작업 데이터가 없습니다. 라벨을 저장한 뒤 다시 시도해 주세요.';
/** 비식별 재처리 대기 구간. */
export const DOWNLOAD_ERROR_DEIDENT =
  '비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.';
/** 요청량 초과. */
export const DOWNLOAD_ERROR_RATE_LIMIT = '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.';
/** 접근 권한 없음(데이터마트 미노출 포함 — 영상 존재 여부를 알려주지 않는다). */
export const DOWNLOAD_ERROR_FORBIDDEN = '이 영상의 작업 데이터를 내려받을 권한이 없습니다.';
/**
 * 응답이 끝나기 전에 전송이 끊겼을 때(중단·네트워크 단절·제한시간 초과).
 *
 * 서버가 거부한 것이 아니라 **받다가 끊긴 것**이라 "잠시 후" 기다리는 것은 답이 아니다.
 * 작업 데이터에 영상이 포함되면 응답이 GB 급이므로 연결이 안정적인 환경을 안내한다.
 */
export const DOWNLOAD_ERROR_INTERRUPTED =
  '작업 데이터를 모두 받기 전에 전송이 끊겼습니다. 영상이 포함되면 용량이 커질 수 있으니 연결이 안정적인 환경에서 다시 시도해 주세요.';
/** 그 밖의 실패. */
export const DOWNLOAD_ERROR_GENERIC = '다운로드에 실패했습니다. 잠시 후 다시 시도해 주세요.';

/** 실패 원인 → 사용자 안내 문구. */
export function datamartDownloadErrorMessage(error: unknown): string {
  // 서버 응답에서 비롯되지 않은 실패(예: 브라우저 다운로드 트리거 실패)는 전송 중단이 아니다.
  // 여기서 걸러 두지 않으면 아래 `status 0` 분기가 그것까지 "전송이 끊겼다"로 잘못 안내한다.
  if (!(error instanceof ApiError)) {
    return DOWNLOAD_ERROR_GENERIC;
  }
  switch (error.status) {
    case 429:
      return DOWNLOAD_ERROR_RATE_LIMIT;
    case 412:
      return DOWNLOAD_ERROR_DEIDENT;
    case 410:
      return DOWNLOAD_ERROR_NO_LABEL;
    case 403:
      return DOWNLOAD_ERROR_FORBIDDEN;
    // 상태코드가 없다 = 응답 자체가 오지 않았다(중단·네트워크·제한시간 초과).
    case 0:
      return DOWNLOAD_ERROR_INTERRUPTED;
    default:
      return DOWNLOAD_ERROR_GENERIC;
  }
}
