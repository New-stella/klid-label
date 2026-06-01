package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.DatamartLabelResponse;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PortalUserLabelServiceTest {

    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsPortalUserLabelRepository userLabelRepository;

    private PortalLabelService service;

    private final TokenClaims alice = new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL,
            Instant.now().plusSeconds(3600));
    private final TokenClaims bob = new TokenClaims("bob", Role.PORTAL_USER, Channel.PORTAL,
            Instant.now().plusSeconds(3600));

    @BeforeEach
    void setUp() {
        service = new PortalLabelService(lblRepository, srcRepository, userLabelRepository);

        when(userLabelRepository.save(any(LsPortalUserLabel.class))).thenAnswer(inv -> {
            LsPortalUserLabel e = inv.getArgument(0);
            setField(e, "userLblSn", 1L);
            return e;
        });
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    // ─── 데이터마트 라벨 Load ───

    @Test
    @DisplayName("V2_데이터마트_라벨_Load_정상_DTO_변환")
    void loadDatamartLabels_returnsDtoList() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(10L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        when(lblRepository.findAllByRawSn(100L)).thenReturn(List.of(lbl));

        List<DatamartLabelResponse> result = service.loadDatamartLabels(100L, 0, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).label()).isEqualTo("person");
        assertThat(result.get(0).srcSn()).isEqualTo(10L);
    }

    @Test
    @DisplayName("V2_데이터마트_rawSn_null_시_INVALID_INPUT")
    void loadDatamartLabels_nullRawSn_rejected() {
        assertThatThrownBy(() -> service.loadDatamartLabels(null, 0, 100))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    // ─── 사용자 라벨 저장 ───

    @Test
    @DisplayName("V2_사용자_작업_데이터_별도_적재_원본_미수정")
    void saveUserLabel_savesToPortalTable() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "[1,2,3,4]");

        PortalUserLabelResponse resp = service.saveUserLabel(req, alice);

        assertThat(resp.sourceRawSn()).isEqualTo(100L);
        assertThat(resp.sourceSrcSn()).isEqualTo(10L);
        assertThat(resp.lblTypeCd()).isEqualTo("BBOX");

        ArgumentCaptor<LsPortalUserLabel> captor = ArgumentCaptor.forClass(LsPortalUserLabel.class);
        verify(userLabelRepository).save(captor.capture());
        assertThat(captor.getValue().getPortalUserNo()).isEqualTo("alice");
        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("V2_사용자_라벨_저장_토큰_없으면_401")
    void saveUserLabel_noToken_rejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "[1,2,3,4]");

        assertThatThrownBy(() -> service.saveUserLabel(req, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("UNAUTHORIZED");
    }

    // ─── 본인 라벨 조회 (IDOR) ───

    @Test
    @DisplayName("V2_본인_작업_라벨_조회_IDOR_본인만")
    void listMyLabels_returnsOnlyOwnLabels() {
        LsPortalUserLabel aliceLabel = LsPortalUserLabel.create("alice", 100L, 10L, "BBOX", "person", "[1,2]");
        setField(aliceLabel, "userLblSn", 1L);
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", 100L))
                .thenReturn(List.of(aliceLabel));

        List<PortalUserLabelResponse> result = service.listMyLabels(100L, alice);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).lblTypeCd()).isEqualTo("BBOX");
    }

    @Test
    @DisplayName("V2_다른_사용자_데이터_접근_불가_IDOR")
    void listMyLabels_differentUser_returnsEmpty() {
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("bob", 100L))
                .thenReturn(List.of());

        List<PortalUserLabelResponse> result = service.listMyLabels(100L, bob);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("V2_본인_라벨_조회_rawSn_null_시_INVALID_INPUT")
    void listMyLabels_nullRawSn_rejected() {
        assertThatThrownBy(() -> service.listMyLabels(null, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }
}
