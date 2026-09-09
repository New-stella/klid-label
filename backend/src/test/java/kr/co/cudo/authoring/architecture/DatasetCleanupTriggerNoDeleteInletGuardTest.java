package kr.co.cudo.authoring.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import kr.co.cudo.authoring.portal.repository.LsDatstArngmtTrgrRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

/**
 * 정리 트리거 접수 원장에 <b>식별자만 받아 지우는 입구</b>가 생기지 않도록 고정하는 가드
 * (@design API-244 · ERD-035 · AC-1102).
 *
 * <h2>왜 이 시험이 있는가</h2>
 * <p>{@link LsDatstArngmtTrgrRepository} 는 이 저장소에서 <b>유일하게</b> {@code JpaRepository} 가
 * 아니라 {@link Repository} 마커를 직접 상속한다. 관례에서 벗어난 선택이고, 그래서 다음 사람이
 * <b>"관례에 맞춘다"며 되돌리기 쉽다.</b>
 *
 * <p>되돌리는 순간 {@code deleteById}·{@code deleteAll} 이 <b>공짜로 딸려 온다.</b> 그러면
 * 「식별자만 받아 지우는 창구를 두지 않는다」가 <b>아무도 코드를 고치지 않았는데</b> 깨진다 —
 * 상속만으로 삭제 입구가 열리기 때문이다.
 *
 * <p>그 원칙이 이 표에 특히 중요한 이유는 여기 담긴 값이 <b>정리 유예의 기산점</b>이라서다.
 * 이 행이 사라지면 그 데이터셋은 「받은 적 없는 것」이 되어 정리가 영영 일어나지 않거나,
 * 재수신 시 기산점이 새로 잡혀 유예가 통째로 리셋된다. 삭제는 비가역이고 그 값은 사본이 없다.
 *
 * <p>지금까지 이 선택을 지키는 것은 javadoc 뿐이었고 <b>javadoc 은 빌드를 실패시키지 않는다.</b>
 * 이 시험이 그 자리를 대신한다 — 규율이 아니라 구조로 지킨다.
 *
 * <p>⚠ 이 시험은 「삭제를 영원히 만들지 마라」가 아니다. 실제 해제본 삭제 축이 설계되면 그때
 * <b>조건을 최종 삭제 실행문 자체에 건</b> 전용 메서드를 두면 된다(파생영상 폐기에서 세운 원칙).
 * 이 가드가 막는 것은 <b>조건 없이 식별자만으로 지우는 입구</b>다.
 */
class DatasetCleanupTriggerNoDeleteInletGuardTest {

    /**
     * 삭제 의미를 갖는 메서드 이름의 접두 — Spring Data 가 파생 쿼리로 해석하는 형태를 덮는다.
     * {@code removeBy...} 도 Spring Data 에서 삭제로 파생되므로 함께 막는다.
     */
    private static final List<String> DELETE_PREFIXES = List.of("delete", "remove");

    @Test
    @DisplayName("★접수_원장_저장소는_삭제를_상속하지_않는다 — JpaRepository로_되돌리면_식별자만으로_지우는_입구가_열린다")
    void repositoryDoesNotInheritDeleteInlet() {
        assertThat(CrudRepository.class.isAssignableFrom(LsDatstArngmtTrgrRepository.class))
                .as("접수 원장 저장소가 CrudRepository 계열(JpaRepository 포함)을 상속하면 "
                        + "deleteById·deleteAll 이 상속만으로 딸려 와 「식별자만 받아 지우는 창구를 두지 "
                        + "않는다」가 코드를 고치지 않았는데도 깨진다. 이 표는 정리 유예의 기산점을 담고 "
                        + "그 값은 사본이 없다.")
                .isFalse();

        // 마커 상속 자체는 유지돼야 한다 — 이걸 빼면 Spring Data 가 이 인터페이스를 저장소로 보지 않아
        //   런타임에 빈이 만들어지지 않는다(시험이 그 회귀까지 함께 잡는다).
        assertThat(Repository.class.isAssignableFrom(LsDatstArngmtTrgrRepository.class))
                .as("Spring Data 가 저장소로 인식하려면 Repository 마커 상속은 있어야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("★접수_원장_저장소에_삭제_메서드를_직접_선언하지_않는다 — 조건_없는_삭제는_비가역이다")
    void repositoryDeclaresNoDeleteMethod() {
        // getMethods() 는 상속분까지 보므로 위 시험이 통과하는 한 여기 잡히는 것은 직접 선언분이다.
        List<String> deleteLike = Arrays.stream(LsDatstArngmtTrgrRepository.class.getMethods())
                .map(Method::getName)
                .filter(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return DELETE_PREFIXES.stream().anyMatch(lower::startsWith);
                })
                .toList();

        assertThat(deleteLike)
                .as("접수 원장에 조건 없는 삭제 입구를 두지 않는다. 삭제 축이 필요해지면 판정 조건을 "
                        + "최종 삭제 실행문 자체에 건 전용 메서드로 만들 것 — 부르는 쪽이 조건을 한 번만 "
                        + "잊어도 되돌릴 수 없다. 발견된 메서드: %s", deleteLike)
                .isEmpty();
    }
}
