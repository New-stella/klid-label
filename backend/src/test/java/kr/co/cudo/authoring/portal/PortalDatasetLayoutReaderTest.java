package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetFrame;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetLayout;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetShape;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.DatasetVideo;
import kr.co.cudo.authoring.portal.service.PortalDatasetLayoutReader.LayoutMismatch;
import kr.co.cudo.authoring.portal.service.PortalDatasetRegistrationFailureReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.dir;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.frame;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.niaDoc;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.originalPair;
import static kr.co.cudo.authoring.portal.PortalDatasetLayoutFixture.realDoc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 해제본 파서 — <b>개발망 실물</b>(2026-09-16) 구성을 읽는 규칙과, 어긋날 때 추측하지 않고 멈추는 규칙.
 *
 * <p>실물은 해제본 아래에 이미지와 같은 이름의 문서가 짝으로 평평하게 놓이고, 영상 식별자는 폴더 이름이
 * 아니라 <b>문서의 영상 파일명</b>이다. 우리 산출물(NIA) 모양의 문서도 계속 읽힌다.
 *
 * @design ADR-068
 * @design AC-1118
 */
class PortalDatasetLayoutReaderTest {

    @TempDir Path tmp;

    private Path content;
    private PortalDatasetLayoutReader reader;

    @BeforeEach
    void setUp() throws IOException {
        content = Files.createDirectories(tmp.resolve("content"));
        reader = new PortalDatasetLayoutReader(new ObjectMapper());
    }

    private static PortalDatasetRegistrationFailureReason reasonOf(Throwable t) {
        return ((LayoutMismatch) t).reason();
    }

    // ==================================================== 실물 구성

    @Test
    @DisplayName("★실물처럼_평평한_짝을_읽고_문서의_영상_파일명으로_묶는다")
    void readsFlatPairsGroupedByVideoFileName() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire"));
        frame(content, 2, realDoc("a.mp4", 2, "fire"));
        frame(content, 3, realDoc("b.mp4", 3, "smoke"));

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos()).as("★영상 키는 폴더 이름이 아니라 문서의 영상 파일명이다")
                .extracting(DatasetVideo::videoKey).containsExactly("a.mp4", "b.mp4");
        DatasetVideo a = layout.videos().get(0);
        assertThat(a.frames()).extracting(DatasetFrame::frameNo).containsExactly(1L, 2L);
        // 파서는 링크를 따라가지 않고 실경로를 잡는다 — 시험도 같은 표기로 본다.
        assertThat(a.videoDir()).as("원천 위치는 그 프레임들이 공유하는 폴더")
                .isEqualTo(content.toRealPath(LinkOption.NOFOLLOW_LINKS));
        assertThat(a.meta().originalFilename()).isEqualTo("a.mp4");
        assertThat(a.meta().lengthSec()).isEqualTo(12);
        assertThat(a.meta().eventTypeCd()).isEqualTo("EV02000102");
        assertThat(a.meta().fps()).as("실물 문서에는 초당 프레임 수가 없다 — 지어내지 않는다").isNull();

        DatasetFrame f = a.frames().get(0);
        assertThat(f.videoFrameNo()).as("실물 문서에는 영상 안 프레임 번호가 없다").isNull();
        assertThat(f.shapes()).hasSize(1);
        DatasetShape s = f.shapes().get(0);
        assertThat(s.lblTypeCd()).isEqualTo(LsDataLbl.TYPE_BBOX);
        assertThat(s.pointsJson()).as("bbox [x,y,w,h] → 대각 두 점")
                .isEqualTo("[[400.0,200.0],[880.0,560.0]]");
        assertThat(s.labelName()).as("분류 이름 문자열을 그대로 옮긴다").isEqualTo("fire");
        assertThat(s.labelId()).as("★분류 식별자가 없으므로 라벨 마스터에 잇지 않는다").isNull();
    }

    @Test
    @DisplayName("★하위_폴더에_놓인_짝도_같은_규칙으로_읽혀_같은_영상으로_묶인다")
    void readsNestedPairsWithSameRule() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire"));
        frame(dir(content, "sub", "deeper"), 2, realDoc("a.mp4", 2, "fire"));

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos()).hasSize(1);
        assertThat(layout.videos().get(0).frames()).extracting(DatasetFrame::frameNo).containsExactly(1L, 2L);
        assertThat(layout.videos().get(0).videoDir()).as("두 자리를 아우르는 폴더")
                .isEqualTo(content.toRealPath(LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    @DisplayName("★우리_산출물_모양의_문서도_계속_읽힌다_키포인트는_옮기지_않는다")
    void readsOurExportShapeToo() throws IOException {
        frame(dir(content, "bundle", "cam-a", "v2", "deid"), 0, niaDoc("a.mp4", 100, "첫 장", "3"));
        frame(dir(content, "bundle", "cam-a", "v2", "deid"), 5, niaDoc("a.mp4", 150, "둘째 장", "3"));

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos()).extracting(DatasetVideo::videoKey).containsExactly("a.mp4");
        DatasetVideo a = layout.videos().get(0);
        assertThat(a.frames()).extracting(DatasetFrame::frameNo).containsExactly(0L, 5L);
        assertThat(a.frames().get(0).description()).isEqualTo("첫 장");
        assertThat(a.frames().get(0).videoFrameNo()).isEqualTo(100L);
        assertThat(a.frames().get(0).anonymity()).isEqualTo("Y");
        assertThat(a.meta().fps()).isEqualTo("30");
        assertThat(a.meta().width()).isEqualTo(1920);

        DatasetFrame f = a.frames().get(0);
        assertThat(f.shapes()).as("★키포인트는 옮기지 않는다").extracting(DatasetShape::lblTypeCd)
                .containsExactly(LsDataLbl.TYPE_BBOX, LsDataLbl.TYPE_POLYGON);
        assertThat(f.skippedShapes()).isEqualTo(1);
        assertThat(f.shapes().get(0).trackId()).isEqualTo("t-1");
        assertThat(f.shapes().get(0).labelId()).isEqualTo(3L);
        assertThat(f.shapes().get(0).labelName()).isEqualTo("사람");
        assertThat(f.shapes().get(1).pointsJson()).isEqualTo("[[0.0,0.0],[10.0,0.0],[10.0,10.0]]");
    }

    @Test
    @DisplayName("문서에_프레임_번호가_없으면_파일_이름의_숫자로_읽는다")
    void frameNoFallsBackToFileName() throws IOException {
        frame(content, 7, realDoc("a.mp4", 7, "fire").replace("\"frame_no\": 7", "\"frame_no\": null"));

        assertThat(reader.read(content).videos().get(0).frames())
                .extracting(DatasetFrame::frameNo).containsExactly(7L);
    }

    @Test
    @DisplayName("★원본으로_보이는_폴더는_짝이_온전해도_읽지_않는다")
    void originalDirIsNotRead() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire"));
        originalPair(content, 1, realDoc("z.mp4", 1, "fire"));

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos()).as("원본 폴더의 짝이 읽혔다면 영상이 둘이 된다")
                .extracting(DatasetVideo::videoKey).containsExactly("a.mp4");
    }

    // ==================================================== 어긋나면 멈춘다

    @Test
    @DisplayName("★이미지와_문서의_짝이_없으면_구성_불일치로_멈춘다")
    void missingPairFails() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire"));
        Files.write(content.resolve("0002.jpg"), PortalDatasetLayoutFixture.DEID_JPEG); // 문서 없음

        assertThatThrownBy(() -> reader.read(content))
                .isInstanceOf(LayoutMismatch.class)
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH));
    }

    @Test
    @DisplayName("한_영상_안에_같은_프레임_번호가_둘이면_고르지_않고_멈춘다")
    void duplicateFrameNumberFails() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire"));
        frame(dir(content, "sub"), 9, realDoc("a.mp4", 1, "fire")); // 이름은 다른데 프레임 번호가 같다

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.PAIR_MISMATCH));
    }

    @Test
    @DisplayName("★문서를_읽을_수_없으면_멈춘다")
    void unreadableDocumentFails() throws IOException {
        frame(content, 1, "{ not json");

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.DOCUMENT_UNREADABLE));
    }

    @Test
    @DisplayName("★짝이_하나도_없으면_멈춘다")
    void noPairFails() throws IOException {
        Files.writeString(content.resolve("readme.txt"), "x");

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t)).isEqualTo(PortalDatasetRegistrationFailureReason.NO_VIDEO));
    }

    @Test
    @DisplayName("★짝은_있는데_영상_파일명이_없으면_짝_없음과_다른_사유로_멈춘다")
    void missingVideoFileNameIsItsOwnReason() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire").replace("\"file_name\": \"a.mp4\"", "\"file_name\": \"\""));

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.VIDEO_FILENAME_MISSING));
    }

    @Test
    @DisplayName("★영상_파일명에_경로_구분자가_섞이면_키로_삼지_않는다")
    void videoFileNameWithPathSeparatorFails() throws IOException {
        frame(content, 1, realDoc("../../etc/passwd", 1, "fire"));

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.INVALID_VIDEO_KEY));
    }

    @Test
    @DisplayName("해제본_자리가_없으면_멈춘다")
    void missingContentFails() {
        assertThatThrownBy(() -> reader.read(tmp.resolve("nope")))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.CONTENT_MISSING));
    }

    @Test
    @DisplayName("★해제본_안에_링크가_있으면_따라가지_않고_멈춘다")
    void symlinkFails() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire"));
        Path outside = Files.write(tmp.resolve("outside.jpg"), PortalDatasetLayoutFixture.ORGNL_JPEG);
        Files.delete(content.resolve("0001.jpg"));
        Files.createSymbolicLink(content.resolve("0001.jpg"), outside);

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.SYMLINK_REJECTED));
    }

    @Test
    @DisplayName("프레임_이미지가_JPEG_가_아니면_멈춘다")
    void nonJpegFails() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire"));
        Files.writeString(content.resolve("0001.jpg"), "<html>not an image</html>");

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.IMAGE_NOT_JPEG));
    }

    @Test
    @DisplayName("좌표_형식이_어긋나면_고쳐_넣지_않고_멈춘다")
    void malformedBboxFails() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire").replace("[400, 200, 480, 360]", "[400, 200, 480]"));

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.INVALID_VALUE));
    }

    @Test
    @DisplayName("★사각_박스_좌표_표기가_우리가_아는_것이_아니면_짐작해_읽지_않는다")
    void unknownBboxFormatFails() throws IOException {
        frame(content, 1, realDoc("a.mp4", 1, "fire").replace("\"xywh\"", "\"xyxy\""));

        assertThatThrownBy(() -> reader.read(content))
                .satisfies(t -> assertThat(reasonOf(t))
                        .isEqualTo(PortalDatasetRegistrationFailureReason.INVALID_VALUE));
    }

    @Test
    @DisplayName("숫자가_아닌_분류_식별자는_라벨_마스터_후보가_아니다_이름으로_역매핑하지_않는다")
    void nonNumericCategoryIsNotLabelId() throws IOException {
        frame(content, 0, niaDoc("a.mp4", 0, "d", "person"));

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos().get(0).frames().get(0).shapes())
                .allSatisfy(s -> assertThat(s.labelId()).isNull());
    }

    // ==================================================== 실물 원문 그대로

    /**
     * 개발망에서 받은 문서 <b>원문 그대로</b>를 읽는다 — 우리가 만든 픽스처 문자열이 아니라 실측 본문이다.
     *
     * <p>데이터셋 5145(코드 DS-FLOOD-2025-01 · 구분 PRVC01)의 {@code 0001.json}.
     */
    @Test
    @DisplayName("★★개발망_실물_문서_원문을_그대로_읽는다")
    void readsTheActualSampleDocument() throws IOException {
        String sample = """
                {"dataset":{"name":"화재 데이터셋 구축","job_id":"DUMMY-001"},
                 "image":{"file_name":"0001.jpg","width":1280,"height":720,"frame_no":1},
                 "video":{"file_name":"video.mp4","vdo_len_sec":12,"evnt_type_cd":"EV02000102"},
                 "annotations":[{"id":1,"category":"fire","bbox":[400,200,480,360],"bbox_format":"xywh"}],
                 "lbl_type":"객체 검출","lbl_fmt":"COCO-JSON","lat":37.4783,"lon":126.9516}
                """;
        for (int i = 1; i <= 3; i++) {
            Files.write(content.resolve(String.format("%04d.jpg", i)), PortalDatasetLayoutFixture.DEID_JPEG);
            Files.writeString(content.resolve(String.format("%04d.json", i)),
                    sample.replace("\"frame_no\":1", "\"frame_no\":" + i), StandardCharsets.UTF_8);
        }

        DatasetLayout layout = reader.read(content);

        assertThat(layout.videos()).as("영상 파일명이 한 값이라 영상 1건").hasSize(1);
        DatasetVideo v = layout.videos().get(0);
        assertThat(v.videoKey()).isEqualTo("video.mp4");
        assertThat(v.frames()).extracting(DatasetFrame::frameNo).containsExactly(1L, 2L, 3L);
        assertThat(v.meta().lengthSec()).isEqualTo(12);
        assertThat(v.meta().eventTypeCd()).isEqualTo("EV02000102");
        assertThat(v.frames()).allSatisfy(f -> assertThat(f.shapes()).singleElement()
                .satisfies(s -> {
                    assertThat(s.lblTypeCd()).isEqualTo(LsDataLbl.TYPE_BBOX);
                    assertThat(s.labelName()).isEqualTo("fire");
                    assertThat(s.labelId()).isNull();
                }));
    }
}
