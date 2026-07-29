package kr.co.cudo.authoring.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QuartzClusteringGuard} 순수 판정 검증 — 컨테이너 없이 고정한다.
 *
 * <p>배선(=@PostConstruct 로 실제 기동 경로에 걸려 있는지)은
 * {@link QuartzClusteringBootGuardTest} 가 컨텍스트 refresh 로 별도 검증한다.
 */
class QuartzClusteringGuardTest {

    @Test
    @DisplayName("클러스터링이_켜져있으면_어떤_환경에서도_통과한다")
    void clusteredPassesEverywhere() {
        assertThatCode(() -> QuartzClusteringGuard.verify(List.of("prd"), "prd", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> QuartzClusteringGuard.verify(List.of("stg"), "stg", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> QuartzClusteringGuard.verify(List.of(), null, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prd_프로파일에서_Quartz_클러스터링이_꺼져있으면_기동이_실패한다")
    void prdWithoutClusteringIsRejected() {
        assertThatThrownBy(() -> QuartzClusteringGuard.verify(List.of("prd"), null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QuartzClusteringGuard.KEY_CLUSTERED)
                .hasMessageContaining("prd");
    }

    @Test
    @DisplayName("stg_프로파일에서도_동일하게_기동이_실패한다")
    void stgWithoutClusteringIsRejected() {
        assertThatThrownBy(() -> QuartzClusteringGuard.verify(List.of("stg"), null, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(QuartzClusteringGuard.KEY_CLUSTERED);
    }

    @Test
    @DisplayName("프로파일이_혼합되거나_오타면_엄격하게_판정한다")
    void mixedOrUnknownProfilesAreStrict() {
        // 혼합 — allowlist(containsAll) 이므로 prd 가 하나라도 섞이면 단일 노드로 인정하지 않는다.
        assertThatThrownBy(() -> QuartzClusteringGuard.verify(List.of("local", "prd"), null, false))
                .isInstanceOf(IllegalStateException.class);
        // 오타 — allowlist 밖이므로 자동으로 엄격(denylist 였다면 조용히 통과했을 값)
        assertThatThrownBy(() -> QuartzClusteringGuard.verify(List.of("prd1"), null, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> QuartzClusteringGuard.verify(List.of("LOCAL"), null, false))
                .isInstanceOf(IllegalStateException.class);
        // 미지정(default) — 활성 프로파일이 없으면 완화하지 않는다
        assertThatThrownBy(() -> QuartzClusteringGuard.verify(List.of(), null, false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("배포표식_ENV가_stg_prd면_프로파일이_dev여도_엄격하게_판정한다")
    void deployedEnvMarkerWins() {
        assertThatThrownBy(() -> QuartzClusteringGuard.verify(List.of("dev"), "prd", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENV");
        assertThatThrownBy(() -> QuartzClusteringGuard.verify(List.of("local"), " STG ", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("local_dev_는_클러스터링_없이도_정상_기동한다")
    void singleNodeProfilesPassWithoutClustering() {
        assertThatCode(() -> QuartzClusteringGuard.verify(List.of("local"), null, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> QuartzClusteringGuard.verify(List.of("dev"), null, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> QuartzClusteringGuard.verify(List.of("local", "dev"), "", false))
                .doesNotThrowAnyException();
        // 미지 라벨(qa 등)은 배포 표식이 아니므로 사내 임시 환경의 정상 기동을 막지 않는다
        // (DevProfileGuard / ProfileGatedUrlPolicy 와 동일 강도).
        assertThatCode(() -> QuartzClusteringGuard.verify(List.of("dev"), "qa", false))
                .doesNotThrowAnyException();
    }
}
