package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UC018 — KPST 토글 OFF 회귀 통합 테스트.
 *
 * <p>{@code kpst.deid.enabled=false} 면 KPST 폴링 빈({@link KpstDeidentService})이 등록되지 않는다.
 * 그때 비식별은 <b>수행되지 않고 설정 오류로 거부</b>된다 — 폴백 경로가 없다.
 *
 * <p><b>이 테스트는 뒤집힌 것이다.</b> 과거에는 같은 조건에서 자체 채움(mock) 경로로 넘어가
 * "비식별 완료"가 됐다. 그 경로는 외부 호출 없이 <b>원본을 비식별 경로로 복사</b>하고
 * {@code DE_IDNTF_YN='Y'} 로 마킹했으므로, 마스킹되지 않은 원본이 비식별본으로 통과했다.
 * 자체 채움을 폐지하면서 이 테스트도 "폴백한다" 에서 <b>"폴백하지 않는다"</b> 로 바뀌었다.
 *
 * <p>이 단언이 있어야 폴백이 편의를 이유로 되살아나는 것을 막는다 — 되살아나면 아무도 모르게
 * 원본이 산출물로 나간다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "authoring.storage.raw-mount-roots=" + ArtifactRootTestSupport.IT_MOUNT_ROOT,
        "kpst.deid.enabled=false"
})
class DeidentifyStepKpstDisabledIntegrationTest {

    @Autowired
    private DeidentifyStep deidentifyStep;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("KPST_토글_OFF면_폴백_없이_설정오류로_거부한다 — 자체_채움_경로가_되살아나지_않는다")
    void kpstDisabledRefusesWithoutFallback() throws Exception {
        // given — KPST 폴링 빈이 컨텍스트에 없다(토글 OFF).
        assertThat(applicationContext.getBeanNamesForType(KpstDeidentService.class)).isEmpty();

        // 원본 파일이 <실제로 존재>해도 마찬가지다. 과거에는 바로 이 조건에서 원본을 복사해
        // "비식별 완료" 를 만들어냈다.
        java.nio.file.Path rawFile = ArtifactRootTestSupport.seedOriginalVideo("no-fallback-raw");
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "clip-no-fallback", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, rawFile.toString(), null, 60));

        // when / then — 임의 동작 대신 명확히 거부한다.
        assertThatThrownBy(() -> deidentifyStep.run(raw))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        // 그리고 <아무것도 비식별되지 않았다> — 원본이 비식별본으로 둔갑하지 않는다.
        LsDataRaw reloaded = videoRepository.findById(raw.getRawSn()).orElseThrow();
        assertThat(reloaded.getDeIdntfYn())
                .as("비식별이 수행되지 않았는데 'Y' 이면 자체 채움이 되살아난 것이다")
                .isNotEqualTo("Y");
    }
}
