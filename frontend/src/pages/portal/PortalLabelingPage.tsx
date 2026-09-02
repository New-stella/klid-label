/**
 * 포털 라벨링 화면 — **포털의 라벨링 화면은 이것 하나뿐이다.**
 *
 * 대상은 두 출처다. 데이터마트에서 불러온 영상의 프레임과, 본인이 올린 영상에서 추출된
 * 프레임이다. 예전에는 출처마다 화면이 갈려 있어 사용자가 같은 작업을 두 자리에서 배워야
 * 했고, 업로드 자산 전용 라벨링 화면은 그래서 폐기됐다(그 목적지도 없어졌다).
 *
 * <h3>이 파일이 하는 일</h3>
 * 주소가 말하는 자산 출처를 읽어 그 출처의 본문을 그린다. 판정은 이 파일이 하지 않고
 * `@/features/portal/labelingEntry` 에 위임한다 — 목록의 진입 주소를 만드는 쪽과 **같은
 * 함수**를 써야 「목록은 업로드로 보내는데 화면은 데이터마트로 읽는」 어긋남이 안 생긴다.
 *
 * ⚠ **출처를 주소에 적는 것은 원장이 아직 하나가 아니기 때문이다.** 두 출처의 프레임이 같은
 *   원장에 앉으면 표기 없이 `:id` 하나로 열리며, 그때 이 분기는 사라진다. 지금 표기가 없는
 *   주소는 데이터마트로 읽는다(fail-closed — 지금까지의 동작).
 *
 * ⚠ **이 화면은 이동 탭에 뜨지 않는다.** 목적지가 아니라 목록에서 행을 눌러 들어가는 몰입 편집
 *   화면이다. 노출 판정은 포털 내비게이션 선언의 허용 목록이 하므로 **그 목록에 넣지 않는 것**
 *   으로 지켜진다(여기에 «숨김» 을 따로 적지 않는다 — 두 번째 목록이 된다).
 *
 * ⚠ 라벨 내보내기·원본 파일 내려받기는 이 화면에 두지 않는다 — 자산 단위 조작이라 자산 목록
 *   화면의 행 액션이 갖는다(확정 사양).
 *
 * @design SCREEN-029
 * @design NAV-002
 */

import { useParams, useSearchParams } from 'react-router-dom';

import { resolvePortalLabelSource } from '@/features/portal/labelingEntry';
import { LabelingPage } from '@/pages/label/LabelingPage';
import { PortalUploadLabelingView } from '@/pages/portal/PortalUploadLabelingView';

export function PortalLabelingPage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const source = resolvePortalLabelSource(searchParams);

  if (source === 'upload') {
    // 업로드 출처에서 `:id` 는 프레임이 아니라 자산이다(진입 시 첫 프레임을 알 수 없다).
    // 숫자로 읽히지 않는 값도 그대로 넘긴다 — 안내는 본문이 한 자리에서 한다.
    return <PortalUploadLabelingView uldSn={Number(id)} />;
  }

  // 데이터마트 출처 — 라벨링 코어가 채널(PORTAL)로 스스로 분기한다. 지금 동작 그대로다.
  return <LabelingPage />;
}
