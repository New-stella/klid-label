package kr.co.cudo.authoring.preset.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PresetController — 「이벤트 + 라벨」 계약(V17) 엔드투엔드 검증.
 *
 * <p>요청은 이벤트유형코드와 labelIds 둘뿐이고 응답은 이벤트 표시명({@code eventTypeNm})을 함께
 * 싣는다. 이벤트는 필수이며 <b>등록된 전체 유형</b>이면 되는데, 필터 옵션에서 제외된 대분류의 유형도
 * 영상이 들어오면 오토라벨이 돌아야 하므로 프리셋을 만들 수 있어야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
// 프리셋 eventTypeCd 검증은 이벤트유형 마스터(LS_EVNT_TYPE, V168)의 등록 코드에 의존한다. 일반 테스트
// 컨텍스트는 dev-seed 를 비활성하므로 EventTypeControllerIT 와 동일하게 시드를 개별 활성화한다.
@TestPropertySource(properties = "authoring.dev.seed.enabled=true")
@Transactional("controlTransactionManager")
class PresetControllerTest {

    /** 등록 + 필터 노출 이벤트유형(침수(범람)) — 시드(V15) 기준. */
    private static final String EV_FLOOD = "EV01000101";
    private static final String EV_FLOOD_2 = "EV01000102";
    /** 등록 + 필터 노출 이벤트유형(산사태). */
    private static final String EV_LANDSLIDE = "EV01000201";
    /** 등록돼 있으나 제외 대분류(08 배회)라 필터 옵션에서 빠진 유형 — 그래도 프리셋은 만들 수 있어야 한다. */
    private static final String EV_EXCLUDED = "EV08000101";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private LsLabelPresetRepository presetRepository;
    @Autowired private LsLabelRepository labelRepository;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;

    private String reviewerToken;
    private long personLabelId;   // BBOX
    private long vehicleLabelId;  // POLYGON

    @BeforeEach
    void setUp() {
        reviewerToken = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        String suffix = String.valueOf(System.nanoTime());
        personLabelId = seedLabel("PERSON_" + suffix, "BBOX", "Y");
        vehicleLabelId = seedLabel("VEHICLE_" + suffix, "POLYGON", "Y");
    }

    private long seedLabel(String name, String type, String useYn) {
        LsLabel saved = labelRepository.saveAndFlush(LsLabel.create(name, "#FF0000", type, 0, "tester"));
        if (!"Y".equals(useYn)) {
            saved.softDelete("tester");
            labelRepository.saveAndFlush(saved);
        }
        return saved.getLabelId();
    }

    /** 요청 본문 — 이벤트유형코드 + labelIds 둘뿐이다(이름·설명은 폐지). */
    private ObjectNode presetBody(String eventTypeCd, long... labelIds) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("eventTypeCd", eventTypeCd);
        ArrayNode ids = body.putArray("labelIds");
        for (long id : labelIds) {
            ids.add(id);
        }
        return body;
    }

    @Test
    @DisplayName("PresetController_POST_labelId_정상요청은_마스터_형태파생과_함께_201")
    void createWithLabelIdsSucceeds() throws Exception {
        ObjectNode body = presetBody(EV_FLOOD, personLabelId, vehicleLabelId);

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(2))
                // PERSON = BBOX → bbox only
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelId").value((int) personLabelId))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].linked").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelType").value("BBOX"))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].bboxEnabled").value(true))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].polygonEnabled").value(false))
                // VEHICLE = POLYGON → polygon only
                .andExpect(jsonPath("$.data.labelCodeOptions[1].labelId").value((int) vehicleLabelId))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].labelType").value("POLYGON"))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].bboxEnabled").value(false))
                .andExpect(jsonPath("$.data.labelCodeOptions[1].polygonEnabled").value(true))
                // 레거시 labelCodes 는 라벨명 목록으로 노출
                .andExpect(jsonPath("$.data.labelCodes.length()").value(2));
    }

    @Test
    @DisplayName("PresetController_응답에_프리셋_이름과_설명_필드가_없다")
    void responseHasNoNameAndDescription() throws Exception {
        MvcResult res = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, personLabelId))))
                .andExpect(status().isCreated())
                .andReturn();

        var data = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.has("name")).as("프리셋은 이름을 갖지 않는다").isFalse();
        assertThat(data.has("description")).as("프리셋은 설명을 갖지 않는다").isFalse();
        assertThat(data.path("eventTypeNm").asText()).as("사람이 읽는 이름은 이벤트 표시명이다").isNotBlank();
    }

    @Test
    @DisplayName("PresetController_POST_labelIds_비어있어도_201_오토라벨_제외_선언")
    void createWithEmptyLabelIdsSucceedsAsAutolabelExclusion() throws Exception {
        // ★구 판은 이 요청을 400 으로 단언해 결함을 「정상 동작」으로 고정하고 있었다(CO-014 에서 반전).
        //   라벨을 담지 않은 프리셋은 그 이벤트 유형을 오토라벨 대상에서 빼겠다는 사람의 선언이다.
        ObjectNode body = objectMapper.createObjectNode();
        body.put("eventTypeCd", EV_FLOOD);
        body.putArray("labelIds");

        MvcResult res = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(0))
                .andExpect(jsonPath("$.data.eventTypeCd").value(EV_FLOOD))
                .andReturn();

        long id = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asLong();
        assertThat(presetRepository.findById(id).orElseThrow().getCodes()).isEmpty();
    }

    @Test
    @DisplayName("PresetController_POST_labelIds_키가_아예_없으면_400_전체교체_계약이라_생략은_허용하지_않는다")
    void createWithoutLabelIdsKeyReturns400() throws Exception {
        // 하한은 없앴지만 키 자체는 필수다 — 전체 교체 계약이라 키를 빼면 「비우겠다」와
        //   「안 건드리겠다」가 구분되지 않는다.
        ObjectNode body = objectMapper.createObjectNode();
        body.put("eventTypeCd", EV_FLOOD);

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_PUT_labelIds_를_모두_비우면_200_이고_코드가_전부_사라진다")
    void updateClearingAllLabelIdsSucceeds() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId, vehicleLabelId);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("eventTypeCd", EV_FLOOD);
        body.putArray("labelIds");

        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(0));

        assertThat(presetRepository.findById(id).orElseThrow().getCodes()).isEmpty();
    }

    @Test
    @DisplayName("PresetController_라벨_0건이어도_이벤트유형_검증은_그대로_400")
    void emptyLabelIdsStillRequireRegisteredEvent() throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("eventTypeCd", "EV99999999");
        body.putArray("labelIds");

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_응답에_실효여부와_라벨별_검출클래스_매핑이_실린다")
    void responseCarriesEffectiveAndDetectionClassMapping() throws Exception {
        MvcResult res = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, personLabelId))))
                .andExpect(status().isCreated())
                .andReturn();

        var data = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.has("effective")).as("프리셋이 실효하는지를 조회에서 알 수 있어야 한다").isTrue();
        // 시드 라벨은 검출 클래스 매핑이 없다 → 미매핑이므로 실효하지 않고, 매핑 코드는 null 이다.
        assertThat(data.path("effective").asBoolean()).isFalse();
        var opt = data.path("labelCodeOptions").get(0);
        assertThat(opt.has("dtctTypeCd")).as("불리언이 아니라 코드값 필드를 싣는다").isTrue();
        assertThat(opt.path("dtctTypeCd").isNull()).as("미매핑 라벨은 null").isTrue();
    }

    @Test
    @DisplayName("PresetController_POST_마스터에_없는_labelId면_400_INVALID_INPUT")
    void createWithUnknownLabelIdReturns400() throws Exception {
        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, 99999999L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_soft_delete된_labelId면_400_INVALID_INPUT")
    void createWithSoftDeletedLabelIdReturns400() throws Exception {
        long deletedId = seedLabel("DELETED_" + System.nanoTime(), "BBOX", "N");

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, deletedId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_음수_labelId면_400_INVALID_INPUT")
    void createWithNegativeLabelIdReturns400() throws Exception {
        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, -5L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_저장후_DB에는_labelId만_저장되고_LBL_CD는_null")
    void createStoresLabelIdWithoutCodeSnapshot() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId);

        var saved = presetRepository.findById(id).orElseThrow();
        assertThat(saved.getCodes()).hasSize(1);
        assertThat(saved.getCodes().get(0).getLabelId()).isEqualTo(personLabelId);
        assertThat(saved.getCodes().get(0).getCode()).isNull();  // 라벨명/코드 스냅샷 미저장
    }

    @Test
    @DisplayName("PresetController_PUT_코드목록_labelId_변경이_반영된다")
    void updateAppliesNewLabelIds() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId);

        // 이벤트는 그대로 두고 VEHICLE 로 교체.
        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, vehicleLabelId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(1))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelId").value((int) vehicleLabelId))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelType").value("POLYGON"))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].bboxEnabled").value(false))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].polygonEnabled").value(true));
    }

    @Test
    @DisplayName("프리셋_저장시_유효_이벤트유형코드면_201_이고_표시명이_함께_내려온다")
    void createWithValidEventTypeSucceeds() throws Exception {
        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, personLabelId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventTypeCd").value(EV_FLOOD))
                .andExpect(jsonPath("$.data.eventTypeNm").value("침수(범람)"));
    }

    @Test
    @DisplayName("프리셋_저장시_이벤트유형코드가_없으면_400_INVALID_INPUT")
    void createWithoutEventTypeReturns400() throws Exception {
        // 이벤트 미지정 / 빈 문자열 둘 다 거부한다 — 이벤트에 걸리지 않은 프리셋은 어느 영상에도
        //   매칭되지 않는 죽은 행이다.
        ObjectNode missing = objectMapper.createObjectNode();
        missing.putArray("labelIds").add(personLabelId);

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missing)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody("", personLabelId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("프리셋_저장시_필터에서_제외된_대분류의_이벤트유형도_201_이고_이름으로_읽힌다")
    void createWithExcludedClassEventSucceeds() throws Exception {
        // 제외 대분류(08 배회)는 필터 드롭다운에서만 감춰진다 — 그 유형의 영상이 들어오면 오토라벨이
        //   돌아야 하므로 프리셋을 만들 수 있어야 하고, 표시명도 코드가 아니라 이름으로 나와야 한다.
        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_EXCLUDED, personLabelId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.eventTypeCd").value(EV_EXCLUDED))
                .andExpect(jsonPath("$.data.eventTypeNm").value("배회"));
    }

    @Test
    @DisplayName("프리셋_저장시_구_EVT코드면_400_INVALID_INPUT")
    void createWithLegacyEvtCodeReturns400() throws Exception {
        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody("EVT_FALL", personLabelId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("프리셋_저장시_미등록_이벤트유형코드면_400_INVALID_INPUT")
    void createWithUnknownEventTypeReturns400() throws Exception {
        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody("EV99999999", personLabelId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_POST_이미_프리셋이_있는_이벤트면_409_이고_어느_이벤트인지_알린다")
    void createWithDuplicateEventReturns409() throws Exception {
        createPreset(EV_LANDSLIDE, personLabelId);

        mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_LANDSLIDE, vehicleLabelId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(EV_LANDSLIDE)));
    }

    @Test
    @DisplayName("PresetController_PUT_다른_프리셋이_쓰는_이벤트로_재매핑하면_409")
    void updateToEventOwnedByOtherPresetReturns409() throws Exception {
        createPreset(EV_FLOOD, personLabelId);
        long second = createPreset(EV_FLOOD_2, vehicleLabelId);

        mockMvc.perform(put("/v1/manage/presets/" + second)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, vehicleLabelId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONFLICT"));
    }

    @Test
    @DisplayName("PresetController_PUT_이벤트를_그대로_둔_수정은_409가_아니다")
    void updateKeepingSameEventIsNotConflict() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId);

        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, vehicleLabelId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventTypeCd").value(EV_FLOOD));
    }

    // ----- 중복/전체교체/삭제/복제폐지/목록/검증 -----

    /** 프리셋을 생성하고 생성된 id 를 반환한다. */
    private long createPreset(String eventTypeCd, long... labelIds) throws Exception {
        MvcResult res = mockMvc.perform(post("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(eventTypeCd, labelIds))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asLong();
    }

    @Test
    @DisplayName("PresetController_POST_중복labelId_요청시_DB에는_코드1건만_저장된다")
    void createDedupsDuplicateLabelIdInDb() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId, personLabelId, personLabelId);

        var saved = presetRepository.findById(id).orElseThrow();
        assertThat(saved.getCodes()).hasSize(1);
        assertThat(saved.getCodes().get(0).getLabelId()).isEqualTo(personLabelId);
    }

    @Test
    @DisplayName("PresetController_PUT_요청에서_제외된_labelId는_코드목록에서_소실된다")
    void updateDropsOmittedLabelId() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId, vehicleLabelId);

        // person 만 남기고 교체 → vehicle 소실.
        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, personLabelId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.labelCodeOptions.length()").value(1))
                .andExpect(jsonPath("$.data.labelCodeOptions[0].labelId").value((int) personLabelId));

        var saved = presetRepository.findById(id).orElseThrow();
        assertThat(saved.getCodes()).hasSize(1);
        assertThat(saved.getCodes().get(0).getLabelId()).isEqualTo(personLabelId);
    }

    @Test
    @DisplayName("PresetController_DELETE_존재_204_그리고_미존재도_204_idempotent")
    void deleteIsIdempotent() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId);

        mockMvc.perform(delete("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());
        assertThat(presetRepository.findById(id)).isEmpty();

        // 이미 삭제된(존재하지 않는) 프리셋 삭제도 204.
        mockMvc.perform(delete("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PresetController_복제_엔드포인트는_존재하지_않는다")
    void clonePathIsNotHandled() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId);

        // 이벤트유형 1건에 프리셋 1건이라 복제할 자리가 없고, 복제 결과는 어느 영상에도 매칭되지 않는다.
        mockMvc.perform(post("/v1/manage/presets/" + id + "/clone")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().is4xxClientError());

        // 매핑 자체가 없어야 한다 — 응답 코드만으로는 "우연히 막힌 것"과 구분되지 않는다.
        assertThat(Arrays.stream(PresetController.class.getDeclaredMethods()).map(Method::getName))
                .as("복제 핸들러가 컨트롤러에 남아 있으면 안 된다")
                .doesNotContain("clone");
    }

    @Test
    @DisplayName("PresetController_GET_목록조회시_생성한_프리셋이_이벤트표시명과_마스터_형태파생과_함께_노출된다")
    void listReturnsCreatedPresetWithMasterJoin() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId, vehicleLabelId);

        MvcResult res = mockMvc.perform(get("/v1/manage/presets")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        var data = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        boolean found = false;
        for (var node : data) {
            if (node.path("id").asLong() == id) {
                found = true;
                assertThat(node.path("eventTypeCd").asText()).isEqualTo(EV_FLOOD);
                assertThat(node.path("eventTypeNm").asText()).isEqualTo("침수(범람)");
                var opts = node.path("labelCodeOptions");
                assertThat(opts).hasSize(2);
                assertThat(opts.get(0).path("labelType").asText()).isEqualTo("BBOX");
                assertThat(opts.get(0).path("bboxEnabled").asBoolean()).isTrue();
                assertThat(opts.get(1).path("labelType").asText()).isEqualTo("POLYGON");
                assertThat(opts.get(1).path("polygonEnabled").asBoolean()).isTrue();
                assertThat(node.has("effective")).as("목록에도 실효 여부가 실린다").isTrue();
                assertThat(opts.get(0).has("dtctTypeCd")).as("목록에도 매핑 코드가 실린다").isTrue();
            }
        }
        assertThat(found).as("생성한 프리셋이 목록에 있어야 한다").isTrue();
    }

    @Test
    @DisplayName("PresetController_PUT_labelIds_21개초과면_400_INVALID_INPUT")
    void updateWithTooManyLabelIdsReturns400() throws Exception {
        ObjectNode update = objectMapper.createObjectNode();
        update.put("eventTypeCd", EV_FLOOD);
        ArrayNode ids = update.putArray("labelIds");
        for (int i = 1; i <= 21; i++) {
            ids.add(i);
        }

        // @Size(max=20) 본문 검증이 서비스 도달 전에 400 을 낸다(경로 id 유효성 무관).
        mockMvc.perform(put("/v1/manage/presets/1")
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_PUT_미존재_labelId면_400_INVALID_INPUT")
    void updateWithUnknownLabelIdReturns400() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId);

        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, 99999999L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("PresetController_PUT_음수_labelId면_400_INVALID_INPUT")
    void updateWithNegativeLabelIdReturns400() throws Exception {
        long id = createPreset(EV_FLOOD, personLabelId);

        mockMvc.perform(put("/v1/manage/presets/" + id)
                        .header("Authorization", "Bearer " + reviewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(presetBody(EV_FLOOD, -3L))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }
}
