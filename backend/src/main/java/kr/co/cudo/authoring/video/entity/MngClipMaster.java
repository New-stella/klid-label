package kr.co.cudo.authoring.video.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/** 관제서버 클립 영상 마스터. 저작도구는 읽기 전용 (@Immutable). */
@Entity
@Table(name = "MNG_CLIP_MASTER")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MngClipMaster {

    @Id
    @Column(name = "CLIP_SN")
    private Long clipSn;

    @Column(name = "VMS_CLIP_ID", nullable = false, length = 128)
    private String vmsClipId;

    @Column(name = "VMS_CCTV_ID", nullable = false, length = 64)
    private String vmsCctvId;

    @Column(name = "JOB_DMND_YN", nullable = false, length = 1)
    private String jobDmndYn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;
}
