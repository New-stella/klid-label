package kr.co.cudo.authoring.controlnotify;

import kr.co.cudo.authoring.common.client.ControlNotifyClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 (통지 인프라) — enabled=false 시 ControlNotifyClient Bean 미등록 검증.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.control-notify.enabled=false")
class ControlNotifyClientConditionalTest {

    @Autowired(required = false)
    private ControlNotifyClient controlNotifyClient;

    @Test
    @DisplayName("enabled_false시_Client_Bean_미등록")
    void clientBeanNotRegisteredWhenDisabled() {
        // given / when / then
        assertThat(controlNotifyClient).isNull();
    }
}
