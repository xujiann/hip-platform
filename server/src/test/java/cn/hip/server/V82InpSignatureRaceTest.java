package cn.hip.server;

import cn.hip.inpatient.web.InpEmrController;
import cn.hip.platform.integration.signature.SignatureAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真提交、定向暂停签名，PG 等待事件证明修改确实参与竞争；不以线程数冒充并发。 */
@SpringBootTest
class V82InpSignatureRaceTest {
    @Autowired InpEmrController controller;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @MockBean SignatureAdapter signatureAdapter;
    Long patient, admission, record;

    @AfterEach void cleanup() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            if (record != null) {
                jdbc.update("delete from emr_version where emr_type='INP' and emr_id=?",record);
                jdbc.update("delete from inp_medical_record where id=?",record);
            }
            if (admission != null) jdbc.update("delete from inp_admission where id=?",admission);
            if (patient != null) jdbc.update("delete from empi_patient where id=?",patient);
        });
    }

    @Test void editingWaitsForSignatureAndCannotOverwriteItsBody() throws Exception {
        var auth=new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            patient=jdbc.queryForObject("insert into empi_patient(patient_no,name,sex) values (?,'V82签名竞争','M') returning id",Long.class,"V82R"+System.nanoTime());
            Long ward=jdbc.queryForObject("select ward_id from inp_bed order by id limit 1",Long.class);
            Long bed=jdbc.queryForObject("select id from inp_bed order by id limit 1",Long.class);
            Long author=jdbc.queryForObject("select id from sys_user where username='admin'",Long.class);
            admission=jdbc.queryForObject("insert into inp_admission(admission_no,patient_id,dept_id,ward_id,bed_id,status,admit_at) values (?,?,1,?,?,'IN_HOSPITAL',now()) returning id",Long.class,"V82R"+System.nanoTime(),patient,ward,bed);
            record=jdbc.queryForObject("insert into inp_medical_record(admission_id,record_type,title,content,doctor_id) values (?,'PROGRESS','竞争','签名前正文',?) returning id",Long.class,admission,author);
        });
        CountDownLatch signing=new CountDownLatch(1), release=new CountDownLatch(1), editStarted=new CountDownLatch(1);
        AtomicInteger editPid=new AtomicInteger();
        doAnswer(invocation -> {
            signing.countDown();
            assertTrue(release.await(15,TimeUnit.SECONDS),"签名暂停超时");
            return new SignatureAdapter.SignResult(true,"V82Signature",null);
        }).when(signatureAdapter).sign(anyString(),any());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var signed=pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(auth);
                try { return new TransactionTemplate(manager).execute(tx -> controller.signRecord(admission,record,auth)); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(signing.await(10,TimeUnit.SECONDS));
            var edited=pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(auth);
                try { return new TransactionTemplate(manager).execute(tx -> {
                    editPid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class)); editStarted.countDown();
                    return controller.updateRecord(admission,record,new InpEmrController.UpdateRecordRequest(null,"竞争修改",null),auth);
                }); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertTrue(editStarted.await(5,TimeUnit.SECONDS));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            boolean waiting=false;
            while (System.nanoTime()<deadline && !edited.isDone()) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid=? and wait_event_type='Lock')",Boolean.class,editPid.get()));
                if (waiting) break;
                Thread.sleep(20);
            }
            assertTrue(waiting,"修改必须在数据库等待签名所持的行锁，不能先行写入");
            release.countDown();
            assertEquals(0,signed.get(10,TimeUnit.SECONDS).getCode());
            assertEquals(9103,edited.get(10,TimeUnit.SECONDS).getCode());
            assertEquals("签名前正文",jdbc.queryForObject("select content from inp_medical_record where id=?",String.class,record));
            assertEquals("V82Signature",jdbc.queryForObject("select signature from inp_medical_record where id=?",String.class,record));
        } finally {
            release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));
        }
    }
}
