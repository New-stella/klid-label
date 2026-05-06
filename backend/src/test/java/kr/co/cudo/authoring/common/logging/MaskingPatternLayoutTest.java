package kr.co.cudo.authoring.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingPatternLayoutTest {

    @Test
    @DisplayName("Logback_민감필드_password_token_authorization_마스킹_확인")
    void masksSensitiveFields() {
        MaskingPatternLayout layout = new MaskingPatternLayout();
        String input1 = "password=mypw123 something=ok";
        String input2 = "Authorization: Bearer abc.def.ghi";
        String input3 = "token=eyJhbGciOiJIUzI1NiJ9";
        String input4 = "secret=topsecret";

        assertThat(layout.mask(input1)).contains("password=***").doesNotContain("mypw123");
        assertThat(layout.mask(input2)).contains("Authorization: ***").doesNotContain("abc.def.ghi");
        assertThat(layout.mask(input3)).contains("token=***").doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(layout.mask(input4)).contains("secret=***").doesNotContain("topsecret");
    }
}
