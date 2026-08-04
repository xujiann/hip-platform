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
}
