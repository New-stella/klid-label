package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.entity.LsMngrPswd;
import kr.co.cudo.authoring.auth.repository.LsMngrPswdRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 관리자 자격 판정의 <b>조달 순서</b>. [@design AC-122] [@design ADR-046]
 *
 * <p>세 갈래가 각각 왜 그 방향이어야 하는지가 이 파일의 전부다 —
 * ①저장소가 이기고 ②비어 있으면 배포 설정으로 되돌아가며 ③둘 다 없거나 <b>읽지 못하면</b> 막는다.
 */
class AdminPasswordVerifierTest {

    private static final String STORED_PLAINTEXT = "stored-admin-secret";
    private static final String CONFIG_PLAINTEXT = "config-admin-secret";

    private static String hash(String raw) {
        return new BCryptPasswordEncoder(AdminPasswordVerifier.BCRYPT_COST).encode(raw);
    }

    private static LsMngrPswdRepository repositoryWith(LsMngrPswd row) {
        LsMngrPswdRepository repository = mock(LsMngrPswdRepository.class);
        when(repository.findById(LsMngrPswd.SINGLE_ROW_SN)).thenReturn(Optional.ofNullable(row));
        return repository;
    }

    @Test
    @DisplayName("저장소에_값이_있으면_그_값으로_판정한다 — 배포_설정값은_지지_않는다")
    void storedCredentialWins() {
        LsMngrPswd row = LsMngrPswd.of(hash(STORED_PLAINTEXT), "1", LocalDateTime.now());
        AdminPasswordVerifier verifier =
                new AdminPasswordVerifier(repositoryWith(row), hash(CONFIG_PLAINTEXT));

        assertThat(verifier.matches(STORED_PLAINTEXT)).isTrue();
        assertThat(verifier.matches(CONFIG_PLAINTEXT)).isFalse();
        assertThat(verifier.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("★저장소가_비어_있으면_배포_설정값으로_되돌아간다 — 없으면_최초_배포에서_관리_기능이_통째로_잠긴다")
    void fallsBackToConfigWhenStoreEmpty() {
        AdminPasswordVerifier verifier =
                new AdminPasswordVerifier(repositoryWith(null), hash(CONFIG_PLAINTEXT));

        assertThat(verifier.matches(CONFIG_PLAINTEXT)).isTrue();
        assertThat(verifier.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("★둘_다_없으면_어떤_패스워드도_통과하지_않는다 (fail-closed)")
    void deniesWhenNoCredentialAnywhere() {
        AdminPasswordVerifier verifier = new AdminPasswordVerifier(repositoryWith(null), "");

        assertThat(verifier.isConfigured()).isFalse();
        assertThat(verifier.matches(CONFIG_PLAINTEXT)).isFalse();
        assertThat(verifier.matches("")).isFalse();
        assertThat(verifier.matches(null)).isFalse();
    }

    @Test
    @DisplayName("★저장소를_읽지_못하면_배포_설정값으로_되돌아가지_않고_거부한다 — 되돌아가면_옛_자격_부활_경로가_된다")
    void deniesWhenStoreUnreadable() {
        LsMngrPswdRepository broken = mock(LsMngrPswdRepository.class);
        when(broken.findById(LsMngrPswd.SINGLE_ROW_SN)).thenThrow(new IllegalStateException("db down"));

        AdminPasswordVerifier verifier = new AdminPasswordVerifier(broken, hash(CONFIG_PLAINTEXT));

        assertThat(verifier.currentHash()).isEmpty();
        assertThat(verifier.isConfigured()).isFalse();
        assertThat(verifier.matches(CONFIG_PLAINTEXT)).isFalse();
    }

    @Test
    @DisplayName("교체하면_저장소에_해시로만_남고_평문은_어디에도_없다 (CWE-256)")
    void replaceStoresHashOnly() {
        LsMngrPswdRepository repository = mock(LsMngrPswdRepository.class);
        when(repository.findById(LsMngrPswd.SINGLE_ROW_SN)).thenReturn(Optional.empty());
        final LsMngrPswd[] saved = new LsMngrPswd[1];
        when(repository.save(any(LsMngrPswd.class))).thenAnswer(inv -> saved[0] = inv.getArgument(0));

        AdminPasswordVerifier verifier = new AdminPasswordVerifier(repository, "");
        verifier.replace("brand-new-secret", "1", LocalDateTime.now());

        assertThat(saved[0]).isNotNull();
        assertThat(saved[0].getMngrPswdSn()).isEqualTo(LsMngrPswd.SINGLE_ROW_SN);
        assertThat(saved[0].getPswdHash()).doesNotContain("brand-new-secret");
        assertThat(saved[0].getPswdHash()).startsWith("$2");
        assertThat(saved[0].getMdfrId()).isEqualTo("1");
        // 새로 만드는 해시의 비용은 12 이상이어야 한다.
        assertThat(saved[0].getPswdHash()).matches("^\\$2[aby]\\$(1[2-9]|[2-9][0-9])\\$.*");
    }

    @Test
    @DisplayName("평문_해시가_배포_설정에_들어오면_기동_시점에_거절한다 (CWE-256)")
    void rejectsPlaintextConfigHash() {
        assertThatThrownBy(() -> new AdminPasswordVerifier("plaintext-not-bcrypt"))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new AdminPasswordVerifier("")).doesNotThrowAnyException();
    }
}
