package kr.co.cudo.authoring.common.storage;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * 후보 경로가 <b>허용 루트 아래인가</b>를 판정하는 규칙 — 이 규칙의 단일 지점.
 *
 * <h3>왜 이 클래스가 있는가</h3>
 * <p>저작도구에는 위치를 판정하는 자리가 여럿이다(탐색·검사·적재·비식별 완료 기록·마킹 이관 등).
 * 그 자리들이 각자 판정 규칙을 적으면 <b>허용 범위 목록이 두 벌</b>이 되어, 한 창구에서 막히는 자리가
 * 다른 창구에서 열린다. 규칙을 여기 한 곳에 두고 호출부는 <b>부르기만</b> 한다.
 *
 * <p>⚠ <b>정확히는 「표기(lexical) 단계를 갖는 판정 자리」의 단일 지점이다.</b> 양변을 실경로로 접어
 * 비교하는 자리({@code TrainingVideoIngestTx}·{@code AugmentFrameProducer})는 그 단계가 아예 없어
 * 이 규칙의 대상이 아니고, 이번 결함의 영향권 밖이며 판정도 더 엄격하다. 「모든 판정 자리가 이것을
 * 쓴다」로 넓혀 읽지 말 것 — 그렇게 읽으면 그 둘을 여기로 끌어와 각자의 성질을 깨뜨리게 된다.
 *
 * <h3>루트의 표기와 실제 자리를 둘 다 인정한다 (ADR-065)</h3>
 * <p>허용 루트는 설정에 적힌 <b>표기</b>이고, 그 루트가 실제로 가리키는 자리는 다를 수 있다(심링크).
 * 그런데 위치 탐색 창구는 판정에 쓴 <b>실경로</b>를 응답에 싣고 화면은 그 값을 그대로 되돌려 보내는
 * 것이 계약이다. 그래서 표기만 인정하면 <b>서버가 자기가 내준 위치를 자기가 거부</b>한다 —
 * 운영 현장에서 허용 루트 바로 아래로 한 걸음도 나가지 못하고 위로 올라갈 수도 없는 상태가 실제로 났다.
 *
 * <p>그래서 후보가 루트의 <b>표기</b>로 시작하거나 루트가 <b>실제로 가리키는 자리</b>로 시작하면 통과시킨다.
 *
 * <h3>넓어지는 것은 시작점을 읽는 방식뿐이다</h3>
 * <ul>
 *   <li><b>상위 이동 표기</b>({@code ..})는 그대로 막힌다 — 호출부가 넘기는 후보는 이미 정규화된 값이라
 *       두 표기 어느 쪽으로도 루트 밖으로 떨어진다(CWE-22).</li>
 *   <li><b>허용 범위 밖을 가리키는 심링크</b>도 그대로 막힌다 — 이 판정은 호출부의 <b>첫</b> 단계이고,
 *       뒤따르는 실경로 재검사가 계속 그것을 담당한다(CWE-59). <b>이 판정만으로 통과시키지 말 것.</b></li>
 *   <li>심링크가 없는 형상에서는 {@code realResolver.apply(root)} 가 {@code root} 와 같아
 *       <b>판정 결과가 종전과 완전히 같다</b>.</li>
 * </ul>
 *
 * <h3>⚠ 실경로 해석기를 왜 주입받는가 — 합치면 한쪽 방어가 깨진다</h3>
 * <p>호출부마다 "해석할 수 없는 경로"를 다루는 방식이 <b>의도적으로 다르다</b>.
 * <ul>
 *   <li>산출물 쓰기 축({@link VideoArtifactRootResolver})은 기준 경로가 실재해야 하므로 해석 실패를
 *       <b>거부</b>로 끝낸다(fail-secure).</li>
 *   <li>외부 산출물 이관의 위치 판정은 <b>존재 확인을 범위 검사보다 뒤에</b> 둔다. 해석 실패를 여기서
 *       예외로 만들면 <b>허용 범위 밖 경로가 있는지 없는지를 응답이 알려주게</b> 된다(CWE-209).</li>
 * </ul>
 * <p>그래서 <b>규칙은 공유하고 해석기는 각자</b> 둔다. 두 해석기를 하나로 합치면 둘 중 한쪽의 성질이
 * 반드시 깨진다 — 합치지 말 것.
 *
 * <h3>⚠ 세 단계를 하나로 합치지 말 것</h3>
 * <p>이것은 호출부 판정의 <b>첫 단계</b>일 뿐이다. 셋은 서로 다른 것을 막는다 — 첫째는 상위로 거슬러
 * 올라가는 표기를, 둘째는 조상 자리에 놓인 심링크를, 셋째는 마지막 요소가 범위 밖을 가리키는 심링크를
 * 막는다. 「일관성」을 이유로 합치면 그중 무엇이 사라졌는지 드러나지 않은 채 방어가 뚫린다.
 *
 * @design ADR-065
 */
public final class AllowedRootMatcher {

    private AllowedRootMatcher() {
    }

    /**
     * 후보가 허용 루트 아래인가 — 루트의 <b>표기</b>와 루트가 <b>실제로 가리키는 자리</b>를 둘 다
     * 허용 범위의 시작점으로 인정한다.
     *
     * @param candidate    판정할 경로(호출부가 이미 정규화해 넘긴 값)
     * @param root         허용 루트(설정에 적힌 표기)
     * @param realResolver 루트를 실제로 가리키는 자리로 펴는 해석기. 호출부의 실패 시맨틱을 그대로 쓴다
     * @return 표기 또는 실제 자리 중 하나로 시작하면 {@code true}
     */
    public static boolean startsWithRoot(Path candidate, Path root, UnaryOperator<Path> realResolver) {
        if (candidate == null || root == null) {
            return false;
        }
        // 표기 그대로 — 심링크가 없는 형상에서는 여기서 끝나며 판정 결과가 종전과 같다.
        if (candidate.startsWith(root)) {
            return true;
        }
        if (realResolver == null) {
            return false;
        }
        Path realRoot = realResolver.apply(root);
        return realRoot != null && candidate.startsWith(realRoot);
    }

    /**
     * 후보가 <b>허용 루트 목록 중 하나</b> 아래인가.
     *
     * <p>목록이 비어 있으면 {@code false} 다 — 「허용 목록이 비었으니 다 통과」로 뒤집히지 않게 한다.
     */
    public static boolean startsWithAnyRoot(Path candidate, Iterable<Path> roots,
                                            UnaryOperator<Path> realResolver) {
        if (candidate == null || roots == null) {
            return false;
        }
        for (Path root : roots) {
            if (startsWithRoot(candidate, root, realResolver)) {
                return true;
            }
        }
        return false;
    }
}
