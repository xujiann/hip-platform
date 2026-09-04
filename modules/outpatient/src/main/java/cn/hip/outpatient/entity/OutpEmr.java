package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 门诊病历（每次挂号一份） */
@Getter
@Setter
@Entity
@Table(name = "outp_emr")
public class OutpEmr {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long registrationId;

    /** 主诉 */
    @Column(length = 512)
    private String chiefComplaint;

    /** 现病史 */
    @Column(length = 2000)
    private String presentIllness;

    /** 既往史 */
    @Column(length = 1000)
    private String pastHistory;

    /** 体格检查 */
    @Column(length = 1000)
    private String physicalExam;

    /** 处理意见 */
    @Column(length = 1000)
    private String advice;

    /** 书写医生 */
    private Long doctorId;

    /**
     * v45（V139）结构化录入**侧车列**：{@code {"fieldCode": 值}} 扁平对象，text 存 JSON（本仓惯例，不开 jsonb）。
     *
     * <p><b>它不是正文。</b>被 CA 签名的仍是上面五段（{@code signEmr} 以 '|' 拼接），
     * 结构化值在保存时已按 sort_no 渲染成可读全文写进 {@link #presentIllness}——
     * 侧车只服务前端回填与 1098★ 的结构化元素检索。
     *
     * <p>旧调用方不传 {@code fields} 时**本列保持 null 且不被触碰**（历史病历亦永远为 null，
     * 反解正文回填即伪造）——V45StructuredEmrTest 的契约保护用例钉死。
     */
    @Column(columnDefinition = "text")
    private String contentJson;

    /** v45（V139）：本份病历用的病历模板 id（emr_template）。不传 fields 时为 null。 */
    private Long templateId;

    /** 电子签名值（签名后病历冻结） */
    @Column(length = 128)
    private String signature;

    private Instant signedAt;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
