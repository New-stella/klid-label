/**
 * 폐기된 업로드 자산 라벨링 주소(`/portal/uploads/:uldSn/label`)를 받아 **통합 라벨링 화면으로
 * 넘겨준다.**
 *
 * <h3>왜 화면을 없애면서 주소는 남기나</h3>
 * 목적지로서의 그 화면은 없어졌고 어디서도 그리로 보내지 않는다. 그런데 **이미 나가 있는
 * 주소**(북마크·주소 공유·열려 있던 탭)는 우리가 회수할 수 없다. 그 주소를 아무 데도 매칭되지
 * 않게 두면 사용자는 「없는 페이지」를 만나고 자기가 하던 작업으로 돌아갈 길이 없다 — 화면을
 * 합치면서 사용자를 막는 것은 통합의 목적과 정반대다.
 *
 * ★**되돌아갈 자리를 만드는 것이 아니다.** 여기에는 폐기된 화면으로 가는 링크도, 「예전 화면으로」
 *   같은 선택지도 없다. 받은 즉시 통합 화면으로 **갈아탄다**(이력에 남기지 않는다 — 뒤로가기가
 *   이 주소로 되돌아오면 다시 튕겨 나가는 고리가 된다).
 *
 * ⚠ 자산 번호로 읽히지 않는 주소는 자산 목록으로 보낸다 — 그 값으로 통합 화면을 열면 없는 자산을
 *   조회하는 안내가 뜬다. 어디로 가야 할지 아는 자리로 보내는 편이 낫다.
 *
 * @design NAV-002
 * @design SCREEN-034
 */

import { Navigate, useParams } from 'react-router-dom';

import { buildPortalUploadLabelPath } from '@/features/portal/labelingEntry';

export function PortalUploadLabelingRedirect() {
  const { uldSn } = useParams<{ uldSn: string }>();
  const n = Number(uldSn);
  const valid = Number.isInteger(n) && n > 0;

  return <Navigate to={valid ? buildPortalUploadLabelPath(n) : '/portal/uploads'} replace />;
}
