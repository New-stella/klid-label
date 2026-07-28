package kr.co.cudo.authoring.portal.config;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code portal.upload.*} 바인딩 통합 검증 — 조사 리포트 D2.
 *
 * <p>배경: 같은 prefix 를 {@link PortalUploadProperties}(record 생성자 바인딩)와 흩어진
 * {@code @Value} 가 나눠 읽어 <b>바인딩이 이원화</b>돼 있었다(설정 문서화·타입 안전 모두 손실).
 * 통합 시 가장 큰 위험은 각 {@code @Value} 의 기존 기본값이 {@code @DefaultValue} 로 옮겨지는
 * 과정에서 조용히 달라지는 것이므로, 아래 상수로 <b>이관 전 기본값을 고정</b>한다.
 *
 * <p>{@code portal.upload.sweep.*} 는 {@code @Scheduled(fixedDelayString=...)} 에서 쓰여
 * (어노테이션은 상수 표현식만 허용) record 로 옮길 수 없다 — yml 명시만 하고 placeholder 를
 * 유지하며, 값 고정은 {@code ImplicitDefaultConfigGuardTest} 가 담당한다.
 */
class PortalUploadPropertiesBindingTest {

    /** 이관 전 {@code @Value} 기본값 — 값이 하나라도 달라지면 조용한 동작 변경이다. */
    private static final long MAX_CHUNK_BYTES = 16_777_216L;      // PortalVideoUploadTxService (16MB)
    private static final long MAX_LABEL_BODY_BYTES = 2_097_152L;  // PortalLabelBodySizeFilter (2MB)
    private static final long PROBE_TIMEOUT_SEC = 30L;            // PortalVideoProbeFfprobe
    private static final long STUCK_TIMEOUT_MINUTES = 30L;        // PortalUploadSweepJob

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PortalUploadConfig.class);

    @Test
    @DisplayName("portal_업로드_확장필드가_기본값으로_바인딩된다")
    void extendedFieldsBindToLegacyDefaults() {
        // given: 아무 설정도 주입하지 않은 상태(= 기존 @Value 기본값이 적용되던 상황)
        runner.run(context -> {
            // when
            PortalUploadProperties properties = context.getBean(PortalUploadProperties.class);

            // then: 이관 전 기본값과 1:1 동일해야 한다
            assertThat(properties.maxChunkBytes()).isEqualTo(MAX_CHUNK_BYTES);
            assertThat(properties.maxLabelBodyBytes()).isEqualTo(MAX_LABEL_BODY_BYTES);
            assertThat(properties.probeTimeoutSec()).isEqualTo(PROBE_TIMEOUT_SEC);
            assertThat(properties.stuckTimeoutMinutes()).isEqualTo(STUCK_TIMEOUT_MINUTES);
        });
    }

    @Test
    @DisplayName("portal_업로드_확장필드를_yml로_override_할_수_있다")
    void extendedFieldsAreOverridable() {
        // given
        runner.withPropertyValues(
                "portal.upload.max-chunk-bytes=1024",
                "portal.upload.max-label-body-bytes=2048",
                "portal.upload.probe-timeout-sec=7",
                "portal.upload.stuck-timeout-minutes=11"
        ).run(context -> {
            // when
            PortalUploadProperties properties = context.getBean(PortalUploadProperties.class);

            // then
            assertThat(properties.maxChunkBytes()).isEqualTo(1024L);
            assertThat(properties.maxLabelBodyBytes()).isEqualTo(2048L);
            assertThat(properties.probeTimeoutSec()).isEqualTo(7L);
            assertThat(properties.stuckTimeoutMinutes()).isEqualTo(11L);
        });
    }

    @Test
    @DisplayName("공통yml의_portal_업로드_설정이_record_기본값과_완전히_같다")
    void commonYmlMatchesRecordDefaults() {
        // given: 공통 yml 이 해석한 portal.upload.* 전체
        List<String> ymlProperties = resolvedCommonYmlProperties();

        runner.run(withDefaults -> runner.withPropertyValues(ymlProperties.toArray(String[]::new))
                .run(fromYml -> {
                    // when
                    PortalUploadProperties defaults = withDefaults.getBean(PortalUploadProperties.class);
                    PortalUploadProperties declared = fromYml.getBean(PortalUploadProperties.class);

                    // then: yml 명시가 동작을 바꾸지 않는다(D1 취지 — 발견 가능성만 얻는다)
                    assertThat(declared)
                            .as("공통 yml 의 portal.upload.* 값이 코드 기본값과 다르다(조용한 동작 변경): %s",
                                    ymlProperties)
                            .isEqualTo(defaults);
                }));
    }

    @Test
    @DisplayName("sweep_주기_설정이_yml에_명시되고_record_이관_대상에서_제외된다")
    void sweepScheduleStaysAsPlaceholder() {
        // given: @Scheduled 어노테이션 속성은 상수 표현식만 허용 → record 필드로 주입 불가
        Environment env = MainResourceYaml.environment("application.yml");

        // when / then: yml 에는 명시돼 발견 가능하되(값은 코드 기본값 그대로),
        assertThat(env.getProperty("portal.upload.sweep.interval-ms")).isEqualTo("1800000");
        assertThat(env.getProperty("portal.upload.sweep.initial-delay-ms")).isEqualTo("600000");

        // and: record 에는 sweep 필드를 두지 않는다(이중 바인딩 재발 방지)
        assertThat(PortalUploadProperties.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .as("sweep 주기는 @Scheduled 전용 placeholder 로만 유지한다")
                .doesNotContain("sweep");
    }

    /** 공통 yml 에서 {@code portal.upload.*} 키를 해석된 값으로 뽑아 property 배열 형태로 만든다. */
    private List<String> resolvedCommonYmlProperties() {
        Environment env = MainResourceYaml.environment("application.yml");
        List<String> properties = new ArrayList<>();
        for (String key : MainResourceYaml.keys("application.yml")) {
            if (key.startsWith("portal.upload.") && !key.startsWith("portal.upload.sweep.")) {
                properties.add(key + "=" + env.getProperty(key));
            }
        }
        assertThat(properties).as("공통 yml 에 portal.upload.* 선언이 있어야 한다").isNotEmpty();
        return properties;
    }
}
