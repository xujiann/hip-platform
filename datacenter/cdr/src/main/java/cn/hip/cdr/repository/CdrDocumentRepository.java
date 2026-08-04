package cn.hip.cdr.repository;

import cn.hip.cdr.entity.CdrDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CdrDocumentRepository extends JpaRepository<CdrDocument, Long> {

    Optional<CdrDocument> findByDocTypeAndRefId(String docType, Long refId);

    List<CdrDocument> findByPatientIdOrderByDocTimeDesc(Long patientId);

    List<CdrDocument> findByPatientIdAndDocTypeOrderByDocTimeDesc(Long patientId, String docType);

    long countByDocType(String docType);

    /** 全文检索起步版：ILIKE 匹配标题与内容（中文子串可用；数据量大后迁 OpenSearch） */
    @org.springframework.data.jpa.repository.Query("""
            from CdrDocument d where d.title like concat('%', :kw, '%')
              or d.content like concat('%', :kw, '%')
            order by d.docTime desc
            """)
    java.util.List<CdrDocument> search(@org.springframework.data.repository.query.Param("kw") String keyword,
                                       org.springframework.data.domain.Pageable pageable);
}
