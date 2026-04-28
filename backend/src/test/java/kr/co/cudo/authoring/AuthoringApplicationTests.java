package kr.co.cudo.authoring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("local")
class AuthoringApplicationTests {

    @Test
    void contextLoads() {
        // Phase 0에서 DataSource/Security 구성이 추가되면 확장
    }
}
