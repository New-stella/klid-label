package kr.co.cudo.authoring.common.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingJsonValueMaskerTest {

    private final MaskingJsonValueMasker masker = new MaskingJsonValueMasker();

    @Test
    @DisplayName("JSON_로그_message_필드_password_token_authorization_마스킹_확인")
    void masksSensitiveFieldsInJsonMessage() {
        // dev/stg/prd 프로파일 JSON 로그(LoggingEventCompositeJsonEncoder) 에서
        // message 필드에 적용되는 마스커.
        assertThat(masker.maskString("password=mypw123 something=ok"))
                .contains("password=***").doesNotContain("mypw123");
        assertThat(masker.maskString("Authorization: Bearer abc.def.ghi"))
                .contains("Authorization: ***").doesNotContain("abc.def.ghi");
        assertThat(masker.maskString("token=eyJhbGciOiJIUzI1NiJ9"))
                .contains("token=***").doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(masker.maskString("secret=topsecret"))
                .contains("secret=***").doesNotContain("topsecret");
    }

    @Test
    @DisplayName("CharSequence_가_아닌_값은_원본_반환")
    void nonCharSequenceValueReturnedAsIs() {
        Object num = 12345;
        assertThat(masker.mask(null, num)).isSameAs(num);
        assertThat(masker.mask(null, null)).isNull();
    }

    @Test
    @DisplayName("CharSequence_값은_마스킹된_문자열_반환")
    void charSequenceValueMasked() {
        Object result = masker.mask(null, "user logged in token=abcd1234");
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).contains("token=***").doesNotContain("abcd1234");
    }

    @Test
    @DisplayName("빈_문자열_또는_null_입력은_그대로_반환")
    void emptyOrNullStringReturnedAsIs() {
        assertThat(masker.maskString(null)).isNull();
        assertThat(masker.maskString("")).isEmpty();
    }
}
