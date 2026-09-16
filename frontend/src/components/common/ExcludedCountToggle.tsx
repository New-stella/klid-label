// 「제외됨 N건」 표시 + 제외분만 보기 전환 — 목록 **세 곳이 공유**하는 조각.
//
// [@design SCREEN-008] [@design SCREEN-012] [@design SCREEN-018]
// [@design AC-1124] [@design ADR-069]
//
// ★**0건이어도 감추지 않는다.** 0건일 때 사라지면 화면이 「제외된 것이 없다」와 「제외 기능이
//   없다」를 구분해 보여 주지 못해, 감춘 것이 있는지조차 알 수 없고 되돌아갈 길도 안내하지 못한다.
//
// ★**자리는 목록 표 바로 위**다(세 화면 공통). 집계 카드 구역에 넣으면 카드 하나로 읽히는데,
//   이 값은 버킷이 아니라 **별개 축**이라(총건수에서 이미 빠져 있다) 카드 합의 항이 아니다.
//
// ⚠⚠ **전환 요청에서 무엇을 빼는지는 이 컴포넌트가 정하지 않는다** — 화면마다 다르기 때문이다.
//   영상 처리 현황은 걸린 필터를 **하나도 빼지 않고**, 작업 목록은 **작업 진행 상태 축만**,
//   검수 목록은 **검수 상태 축만** 뺀다. 차이의 근거는 「제외됨 건수」를 세는 창구가 그 축을
//   반영하는지가 화면마다 다르다는 것이다(집계 창구가 있는 두 목록은 자기 상태 축을 지우고 센다).
//   그래서 이 컴포넌트는 **「눌렸다」만 알리고** 무엇을 빼는지는 호출부가 소유한다.
//   ★이 판정을 여기로 끌어올려 하나로 만들면 세 화면이 같아지고, 그 순간 **누른 숫자와 전환
//   결과가 어긋난다**(상태로 좁힌 채 누르면 전환 결과가 누른 숫자보다 적어진다).

import { Button } from '@/components/common/Button';

export interface ExcludedCountToggleProps {
  /**
   * 제외된 영상 건수 — **서버가 같은 필터 범위에서 센 값**이다(현재 페이지가 아니다).
   *
   * 아직 못 받았으면 `undefined` 이고 그때는 전환 버튼을 잠근다 — 모르는 숫자를 눌러
   * 그 결과와 대조할 수 없는 상태로 보내지 않는다. 값이 `0` 인 것과는 **다른 상태**이며
   * 0 은 정상값이라 그대로 보여준다.
   */
  count?: number;
  /** 지금 제외분만 보고 있는가. */
  active: boolean;
  /** 눌렸다 — 실제 요청 조건은 호출부가 만든다(위 ⚠⚠ 참조). */
  onToggle: () => void;
}

export function ExcludedCountToggle({ count, active, onToggle }: ExcludedCountToggleProps) {
  const loaded = typeof count === 'number';

  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="excluded-count-bar">
      <Button
        variant="ghost"
        size="sm"
        onClick={onToggle}
        disabled={!loaded}
        aria-pressed={active}
        data-testid="excluded-count-toggle"
      >
        제외됨 {loaded ? count.toLocaleString('ko-KR') : 0}건
      </Button>

      {/* 제외분만 보는 동안에만 둔다 — 지금 보는 것이 기본 목록이 아니라는 사실과 되돌아갈
          수단을 함께 세운다(SCREEN-018 이 둘을 명시적으로 요구한다). 위 숫자 버튼도 같은
          동작을 하지만, 「제외됨 N건」이라는 이름만으로는 그것이 되돌리는 버튼임이 드러나지 않는다. */}
      {active && (
        <>
          <span className="text-caption text-gray-600" data-testid="excluded-only-notice">
            제외분만 보는 중입니다.
          </span>
          <Button variant="ghost" size="sm" onClick={onToggle} data-testid="excluded-only-exit">
            기본 목록으로
          </Button>
        </>
      )}
    </div>
  );
}

export default ExcludedCountToggle;
