package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.dto.MarkingImportIngestCommand;
import kr.co.cudo.authoring.video.dto.MarkingImportIngestResult;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 마킹 이관 적재 1건 — 이 도메인이 이관 쪽에 내주는 자리의 계약 시험(ADR-053 · DFEAT-060 · SEQ-030).
 *
 * <p>검증 축은 넷이다.
 * <ol>
 *   <li><b>적재 + 비식별 선두 트리거</b> — 영상 식별자를 돌려주고 {@code VideoIngestedEvent} 를
 *       트랜잭션 안에서 발행한다(AC-1032 · EVT-005 예외 조항).</li>
 *   <li><b>중복 식별자는 건너뛴다</b> — 사전 조회와 제약 위반 이중 방어이며 덮어쓰지 않는다(AC-1033).</li>
 *   <li><b>관제 인입 원장을 건드리지 않는다</b> — 이 경로에는 그 원장 행이 없다.</li>
 *   <li><b>사람이 지정하는 값이 비면 적재하지 않는다</b> — 조용히 멈춘 영상을 만들지 않는다.</li>
 * </ol>
 *
 * <p>strictness 는 기본(STRICT_STUBS)이다 — 죽은 stub 이 "검증한 줄 알았던" 경로를 만들지 않게.
 */
@ExtendWith(MockitoExtension.class)
class MarkingImportIngestTest {

    private static final String CLIP_ID = "org_REPORT_20260101025100_6756";
    private static final String FILE_PATH = "/nas-storage/raw/imports/markings/1/clip.mp4";

    private static MarkingImportIngestCommand command() {
        return new MarkingImportIngestCommand(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH, LocalDateTime.of(2026, 1, 1, 2, 51));
    }

    /** 저장 시 PK 가 채워지는 것을 흉내 낸다 — 실제로는 IDENTITY 가 발급한다. */
    private static LsDataRaw savedWith(long rawSn, LsDataRaw raw) {
        ReflectionTestUtils.setField(raw, "rawSn", rawSn);
        return raw;
    }

    @Nested
    @DisplayName("트랜잭션 경계 빈 — 적재와 비식별 선두 트리거")
    class Persist {

        @Mock
        private VideoRepository videoRepository;

        @Mock
        private ApplicationEventPublisher eventPublisher;

        private MarkingImportIngestTx tx;

        @BeforeEach
        void setUp() {
            tx = new MarkingImportIngestTx(videoRepository, eventPublisher);
        }

        @Test
        @DisplayName("사람이_지정한_공통값이_그대로_실려_원본으로_적재된다")
        void 사람이_지정한_공통값이_그대로_적재된다() {
            // given
            when(videoRepository.saveAndFlush(any(LsDataRaw.class)))
                    .thenAnswer(inv -> savedWith(4242L, inv.getArgument(0)));

            // when
            long rawSn = tx.persist(command());

            // then
            assertThat(rawSn).isEqualTo(4242L);
            ArgumentCaptor<LsDataRaw> saved = ArgumentCaptor.forClass(LsDataRaw.class);
            verify(videoRepository).saveAndFlush(saved.capture());
            LsDataRaw raw = saved.getValue();
            assertThat(raw.getVmsClipId()).isEqualTo(CLIP_ID);
            assertThat(raw.getVmsCctvId()).isEqualTo("CCTV-0001");
            assertThat(raw.getEvntTypeCd()).isEqualTo("EV01000101");
            assertThat(raw.getLclgvCd()).isEqualTo("4113500000");
            assertThat(raw.getPrvcTypeCd()).isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
            assertThat(raw.getRawFilePathNm()).isEqualTo(FILE_PATH);
            assertThat(raw.getShtDt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 2, 51));
            assertThat(raw.getSrcType())
                    .as("이관 갈래를 가르는 값을 새로 만들지 않는다")
                    .isEqualTo(LsDataRaw.SRC_TYPE_IMPORTED);
        }

        @Test
        @DisplayName("★받은_영상은_언제나_비식별_전_원본이라_DE_IDENT_YN이_N으로_적재된다")
        void 비식별_전_원본으로_적재된다() {
            // given
            when(videoRepository.saveAndFlush(any(LsDataRaw.class)))
                    .thenAnswer(inv -> savedWith(1L, inv.getArgument(0)));

            // when
            tx.persist(command());

            // then — 'Y' 로 적재되면 마스킹 전 영상이 비식별본으로 서빙된다(CWE-359)
            ArgumentCaptor<LsDataRaw> saved = ArgumentCaptor.forClass(LsDataRaw.class);
            verify(videoRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getDeIdntfYn()).isEqualTo("N");
            assertThat(saved.getValue().getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        }

        @Test
        @DisplayName("★적재하면_비식별_선두_이벤트를_같은_트랜잭션에서_발행한다")
        void 비식별_선두_이벤트를_발행한다() {
            // given
            when(videoRepository.saveAndFlush(any(LsDataRaw.class)))
                    .thenAnswer(inv -> savedWith(777L, inv.getArgument(0)));

            // when
            tx.persist(command());

            // then — 이 발행이 빠지면 비식별이 아예 돌지 않아 원본 PII 가 그대로 남는다
            ArgumentCaptor<VideoIngestedEvent> event = ArgumentCaptor.forClass(VideoIngestedEvent.class);
            verify(eventPublisher).publishEvent(event.capture());
            assertThat(event.getValue().rawSn()).isEqualTo(777L);
        }

        @Test
        @DisplayName("★식별자_유일제약_위반은_삼키지_않고_그대로_올려_호출부가_롤백_뒤에_판정하게_한다")
        void 유일제약_위반은_그대로_올라간다() {
            // given
            when(videoRepository.saveAndFlush(any(LsDataRaw.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_ls_data_raw_vms_clip"));

            // when / then — 여기서 잡아 값을 돌려주면 커밋 시점에 UnexpectedRollbackException 이 대신 난다
            assertThatThrownBy(() -> tx.persist(command()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("호출 자리 — 중복 건너뜀과 인입 원장 미접촉")
    class Seam {

        @Mock
        private LsDataIngestRepository ingestRepository;

        @Mock
        private TrainingVideoIngestTx ingestTx;

        @Mock
        private MarkingImportIngestTx markingImportIngestTx;

        @Mock
        private VideoRepository videoRepository;

        private TrainingVideoIngestService service;

        @BeforeEach
        void setUp() {
            service = new TrainingVideoIngestService(ingestRepository, ingestTx,
                    markingImportIngestTx, videoRepository, 120L);
        }

        @Test
        @DisplayName("새_식별자면_적재하고_영상_식별자를_돌려준다")
        void 새_식별자면_적재한다() {
            // given
            when(videoRepository.findByVmsClipId(CLIP_ID)).thenReturn(Optional.empty());
            when(markingImportIngestTx.persist(any(MarkingImportIngestCommand.class))).thenReturn(51L);

            // when
            MarkingImportIngestResult result = service.ingestMarkingImport(command());

            // then
            assertThat(result.duplicateClipId()).isFalse();
            assertThat(result.rawSn()).isEqualTo(51L);
        }

        @Test
        @DisplayName("★이미_쓰인_식별자면_그_항목만_건너뛰고_쓰기를_아예_열지_않는다")
        void 중복이면_건너뛴다() {
            // given
            LsDataRaw existing = savedWith(9L, LsDataRaw.createFromMarkingImport(
                    CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                    LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH, null));
            when(videoRepository.findByVmsClipId(CLIP_ID)).thenReturn(Optional.of(existing));

            // when
            MarkingImportIngestResult result = service.ingestMarkingImport(command());

            // then — 덮어쓰면 검수 중이거나 승인된 내용이 사라진다(AC-1033)
            assertThat(result.duplicateClipId()).isTrue();
            assertThat(result.rawSn()).as("무엇과 부딪혔는지 알려 준다").isEqualTo(9L);
            verify(markingImportIngestTx, never()).persist(any());
        }

        @Test
        @DisplayName("★사전조회_뒤_동시적재가_끼어들어_유일제약이_깨져도_중복_건너뜀으로_마감한다")
        void 동시적재_race도_중복으로_마감한다() {
            // given — 사전 조회 시점엔 없었는데 INSERT 시점엔 있다(2노드 Active-Active)
            LsDataRaw raced = savedWith(13L, LsDataRaw.createFromMarkingImport(
                    CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                    LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH, null));
            when(videoRepository.findByVmsClipId(CLIP_ID))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(raced));
            when(markingImportIngestTx.persist(any(MarkingImportIngestCommand.class)))
                    .thenThrow(new DataIntegrityViolationException("uk_ls_data_raw_vms_clip"));

            // when
            MarkingImportIngestResult result = service.ingestMarkingImport(command());

            // then
            assertThat(result.duplicateClipId()).isTrue();
            assertThat(result.rawSn()).isEqualTo(13L);
        }

        @Test
        @DisplayName("★식별자_충돌이_아닌_제약_위반은_중복으로_삼키지_않고_그대로_올린다")
        void 다른_제약_위반은_삼키지_않는다() {
            // given — 재조회해도 그 식별자의 영상이 없다 = 중복이 아니다
            when(videoRepository.findByVmsClipId(CLIP_ID)).thenReturn(Optional.empty());
            when(markingImportIngestTx.persist(any(MarkingImportIngestCommand.class)))
                    .thenThrow(new DataIntegrityViolationException("some_other_constraint"));

            // when / then — 삼키면 적재되지 않은 항목이 성공처럼 집계된다
            assertThatThrownBy(() -> service.ingestMarkingImport(command()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("★마킹_이관_적재는_관제_인입_원장을_읽지도_쓰지도_않는다")
        void 인입_원장을_건드리지_않는다() {
            // given
            when(videoRepository.findByVmsClipId(CLIP_ID)).thenReturn(Optional.empty());
            when(markingImportIngestTx.persist(any(MarkingImportIngestCommand.class))).thenReturn(1L);

            // when
            service.ingestMarkingImport(command());

            // then — 인입 원장은 "관제가 무엇을 보냈는가"의 기록이다. 우리가 행을 넣으면
            //   관제가 보낸 것과 우리가 넣은 것을 나중에 구분할 수 없다(ADR-048 과 같은 이유).
            verifyNoInteractions(ingestRepository);
            verifyNoInteractions(ingestTx);
        }
    }

    @Nested
    @DisplayName("입구 검증 — 사람이 지정하는 값과 저장할 경로")
    class Validation {

        private static MarkingImportIngestCommand with(String clipId, String cctvId, String evntTypeCd,
                                                       String lclgvCd, String prvcTypeCd, String path) {
            return new MarkingImportIngestCommand(clipId, cctvId, evntTypeCd, lclgvCd, prvcTypeCd,
                    path, null);
        }

        @Test
        @DisplayName("촬영일시는_비어도_통과한다_선택_입력이다")
        void 촬영일시는_선택이다() {
            MarkingImportIngestCommand validated = MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH));
            assertThat(validated.shtDt()).isNull();
        }

        @Test
        @DisplayName("앞뒤_공백은_정규화되어_실린다")
        void 공백을_정규화한다() {
            MarkingImportIngestCommand validated = MarkingImportIngestValidator.validate(
                    with("  " + CLIP_ID + " ", " CCTV-0001 ", " EV01000101 ", " 4113500000 ",
                            " " + LsDataRaw.PRVC_TYPE_PRVC + " ", " " + FILE_PATH + " "));
            assertThat(validated.vmsClipId()).isEqualTo(CLIP_ID);
            assertThat(validated.vmsCctvId()).isEqualTo("CCTV-0001");
            assertThat(validated.evntTypeCd()).isEqualTo("EV01000101");
            assertThat(validated.lclgvCd()).isEqualTo("4113500000");
            assertThat(validated.rawFilePathNm()).isEqualTo(FILE_PATH);
        }

        @Test
        @DisplayName("★사람이_지정하는_넷은_하나라도_비면_적재하지_않는다")
        void 필수_넷은_비울_수_없다() {
            assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "  ", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)))
                    .as("카메라 식별자")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", null, "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)))
                    .as("이벤트 유형 — 비면 비식별만 끝난 채 마킹에서 막힌 영상이 된다")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", "EV01000101", null,
                            LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)))
                    .as("지자체 코드")
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000", null, FILE_PATH)))
                    .as("개인정보 유형")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("★개인정보_유형은_ANONY_PRVC_PSDO_셋만_받고_미상은_받지_않는다")
        void 개인정보_유형은_셋뿐이다() {
            for (String allowed : new String[]{LsDataRaw.PRVC_TYPE_ANONY, LsDataRaw.PRVC_TYPE_PRVC,
                    LsDataRaw.PRVC_TYPE_PSDO}) {
                assertThat(MarkingImportIngestValidator.validate(
                        with(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000", allowed, FILE_PATH))
                        .prvcTypeCd()).isEqualTo(allowed);
            }
            // 미상을 열어 두면 화면이 값을 안 보냈을 때 조용히 미상으로 적재되어 결손이 드러나지 않는다
            assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_UNKNOWN, FILE_PATH)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("★컬럼_폭을_넘는_값은_자르지_않고_거부한다")
        void 컬럼_폭_초과는_거부한다() {
            String longClipId = "c".repeat(LsDataRaw.VMS_CLIP_ID_MAX + 1);
            assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                    with(longClipId, "CCTV-0001", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)))
                    .as("잘린 식별자는 다른 영상과 부딪힌다")
                    .isInstanceOf(IllegalArgumentException.class);
            String longPath = "/nas-storage/raw/" + "p".repeat(500);
            assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, longPath)))
                    .as("잘린 경로는 존재하지 않는 자리를 가리킨다")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("★식별자에_경로_구분자가_섞이면_거부한다_이름이_아니라_경로가_흘러든_것이다")
        void 식별자에_구분자가_섞이면_거부한다() {
            for (String bad : new String[]{"a/b", "a\\b", "../etc/passwd"}) {
                assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                        with(bad, "CCTV-0001", "EV01000101", "4113500000",
                                LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("★경로에_상위_이동이_남아_있으면_조용히_고치지_않고_거부한다")
        void 상위_이동이_섞인_경로는_거부한다() {
            assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, "/nas-storage/raw/../../etc/clip.mp4")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("★이벤트_유형_코드는_EV접두_숫자8자리_형식만_통과한다")
        void 이벤트_유형_코드는_형식을_강제한다() {
            // 정상값은 통과한다 — 형식 검사가 과잉 차단으로 굳지 않게 함께 고정한다
            assertThat(MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)).evntTypeCd())
                    .isEqualTo("EV01000101");
            // EV 접두 없음 · 자릿수 부족 · 소문자 · 숫자 아닌 문자
            for (String bad : new String[]{"01000101", "EV0100010", "ev01000101", "EV0100010A"}) {
                assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                        with(CLIP_ID, "CCTV-0001", bad, "4113500000",
                                LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)))
                        .as("이벤트 유형 코드 형식 위반: %s", bad)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("★지자체_코드에_숫자가_아닌_문자가_섞이면_거부한다")
        void 지자체_코드는_숫자만_받는다() {
            assertThat(MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV-0001", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)).lclgvCd())
                    .isEqualTo("4113500000");
            for (String bad : new String[]{"41135X0000", "4113-500000", "41135 00000",
                    "41135000001"}) {
                assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                        with(CLIP_ID, "CCTV-0001", "EV01000101", bad,
                                LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)))
                        .as("지자체 코드 형식 위반: %s", bad)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("★카메라_식별자에_공백이나_개행이_섞이면_거부한다")
        void 카메라_식별자는_문자집합을_강제한다() {
            assertThat(MarkingImportIngestValidator.validate(
                    with(CLIP_ID, "CCTV_gwanak-0001", "EV01000101", "4113500000",
                            LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)).vmsCctvId())
                    .isEqualTo("CCTV_gwanak-0001");
            // 개행은 로그 인젝션 축이다(CWE-117). 앞뒤 공백은 requireText 가 다듬으므로
            //   가운데에 넣어야 형식 게이트를 실제로 지난다.
            for (String bad : new String[]{"CCTV 0001", "CCTV-0001\nGRANTED", "CCTV\t0001",
                    "CCTV-0001\r\nX", "CCTV/0001"}) {
                assertThatThrownBy(() -> MarkingImportIngestValidator.validate(
                        with(CLIP_ID, bad, "EV01000101", "4113500000",
                                LsDataRaw.PRVC_TYPE_PRVC, FILE_PATH)))
                        .as("카메라 식별자 형식 위반")
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }
}
