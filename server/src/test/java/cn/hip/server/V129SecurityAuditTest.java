package cn.hip.server;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real embedded Tomcat sockets exercise the production Security chain, MVC advice,
 * DispatcherServlet wrapping and AuditLogFilter. No MockMvc or mocked security handler.
 * Controlled wrapper routes are imported only into this test context. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"server.address=127.0.0.1","hip.audit.anon-throttle-ms=0"})
@Import(V129SecurityAuditTest.TestRoutes.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class V129SecurityAuditTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    static HttpClient client;
    static final AtomicInteger CLINICAL_WRITES=new AtomicInteger();
    String tag,nurse,admin,machine; final List<Long> staff=new ArrayList<>();
    @BeforeAll static void httpClient(){client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();}
    @AfterAll static void closeHttpClient(){client.close();}
    @BeforeEach void fixture(){tag="V129"+Long.toUnsignedString(System.nanoTime(),36);nurse=employee("NURSE");admin=employee("ADMIN");machine=employee("INTERFACE");CLINICAL_WRITES.set(0);}
    String employee(String role){String name=tag+role;Long id=jdbc.queryForObject("insert into sys_user(username,password,real_name,enabled) values (?,'x',?,true) returning id",Long.class,name,name);staff.add(id);assertEquals(1,jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",id,role));return jwt.issue(name);}
    record Outcome(int http,JsonNode body){}
    Outcome call(String method,String target,String token)throws Exception{
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+target)).timeout(Duration.ofSeconds(15)).method(method,HttpRequest.BodyPublishers.noBody());
        if(token!=null)req.header("Authorization","Bearer "+token);
        var response=client.send(req.build(),HttpResponse.BodyHandlers.ofString());return new Outcome(response.statusCode(),mapper.readTree(response.body()));
    }
    long maxAudit(){return jdbc.queryForObject("select coalesce(max(id),0) from sys_audit_log",Long.class);}
    List<Map<String,Object>> rows(long before){return jdbc.queryForList("select * from sys_audit_log where id>? order by id",before);}
    Instant instant(Object v){return v instanceof java.sql.Timestamp t?t.toInstant():((java.time.OffsetDateTime)v).toInstant();}
    Instant pgClock(){return instant(jdbc.queryForObject("select clock_timestamp()",Object.class));}
    Object business(){var out=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("empi_patient","outp_registration","outp_order","outp_charge","outp_lab_result","lis_sample","ris_exam","outp_critical_alert","cdr_document","outp_order_report","lab_micro_result","lab_micro_ast","lab_qc_record","pharm_stock","pharm_stock_flow"))out.put(table,jdbc.queryForList("select * from "+table+" order by id"));return out;}
    void outcome(Outcome out,int http,int code){assertAll(()->assertEquals(http,out.http(),out.body().toString()),()->assertEquals(code,out.body().get("code").asInt(),out.body().toString()));if(code!=0)assertTrue(out.body().get("data").isNull());}
    void singleAudit(long lowerId,String username,String method,String path,int status,Instant lower)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();List<Map<String,Object>> found;
        do{found=rows(lowerId);if(!found.isEmpty())break;Thread.sleep(10);}while(System.nanoTime()<deadline);
        // Select every new row, never prefilter status or the URI without its query.
        assertEquals(1,found.size(),"One HTTP request has exactly one complete terminal audit; no extra false 200: "+found);
        var actual=found.getFirst();assertTrue(((Number)actual.get("id")).longValue()>lowerId);
        Instant stamp=instant(actual.get("created_at"));assertFalse(stamp.isBefore(lower));assertFalse(stamp.isAfter(pgClock()));
        var expected=new LinkedHashMap<String,Object>();expected.put("id",actual.get("id"));expected.put("username",username);expected.put("method",method);expected.put("path",path);expected.put("http_status",status);expected.put("client_ip","127.0.0.1");expected.put("created_at",actual.get("created_at"));assertEquals(expected,actual);
        System.out.println("V129 TOMCAT terminal="+status+" auditRows=1 method="+method+" path="+path+" username="+username);
    }
    void denied(String path,String token,int status,int code,String name)throws Exception{long lowerId=maxAudit();Instant lower=pgClock();Object before=business();var out=call("POST",path+"?trace="+tag,token);outcome(out,status,code);assertEquals(before,business());assertEquals(0,CLINICAL_WRITES.get());singleAudit(lowerId,name,"POST",path,status,lower);}

    @Test void actualExistingMethodAuthorizationThroughMvcHasExactlyOne403Audit()throws Exception{denied("/api/outpatient/charges/9223372036854775807/refund",nurse,403,1005,tag+"NURSE");}
    @ParameterizedTest @CsvSource({"denied,direct","denied,servlet","denied,runtime","denied,deep","authentication,direct","authentication,servlet","authentication,runtime","authentication,deep"})
    void wrappedSecurityCauseKeepsItsRealHandlerStatusAndSingleAudit(String kind,String wrapper)throws Exception{
        String path="/api/v129-audit/"+tag+"/"+wrapper+"/"+kind;
        // sendStartAuthentication clears SecurityContext before the 401 writer; 403 retains the employee.
        denied(path,nurse,kind.equals("denied")?403:401,kind.equals("denied")?1005:1000,kind.equals("denied")?tag+"NURSE":null);
    }
    @Test void successfulWriteRetainsOrdinaryAuditWithoutClinicalMutation()throws Exception{String path="/api/v129-audit/"+tag+"/plain";long id=maxAudit();Instant start=pgClock();Object before=business();outcome(call("POST",path+"?trace="+tag,admin),200,0);assertEquals(1,CLINICAL_WRITES.get());assertEquals(before,business());singleAudit(id,tag+"ADMIN","POST",path+"?trace="+tag,200,start);}
    @Test void originalBusinessFailureStillGetsOneHttp200Audit()throws Exception{String path="/api/outpatient/charges/9223372036854775807/refund";long id=maxAudit();Instant start=pgClock();Object before=business();outcome(call("POST",path+"?trace="+tag,admin),200,5002);assertEquals(before,business());singleAudit(id,tag+"ADMIN","POST",path+"?trace="+tag,200,start);}
    @Test void sensitiveReadKeepsMaskedQueryAndOrdinarySingleAudit()throws Exception{String path="/api/patients?keyword=13812345678&trace="+tag;long id=maxAudit();Instant start=pgClock();Object before=business();outcome(call("GET",path,admin),200,0);assertEquals(before,business());singleAudit(id,tag+"ADMIN","GET","/api/patients?keyword=138****5678&trace="+tag,200,start);}
    @Test void ordinaryReadIsStillNotAudited()throws Exception{long id=maxAudit();Object before=business();outcome(call("GET","/api/v129-audit/"+tag+"/plain",admin),200,0);assertEquals(before,business());assertTrue(rows(id).isEmpty());}
    @Test void anonymousChainRefusalHasOnlyOneNullUser401()throws Exception{denied("/api/v129-audit/"+tag+"/plain",null,401,1000,null);}
    @Test void machineChainRefusalKeepsItsUsernameAndSingle403()throws Exception{denied("/api/v129-audit/"+tag+"/plain",machine,403,1005,tag+"INTERFACE");}
    @Test void truePatientNamespaceChainRefusalKeepsPortalSubject()throws Exception{denied("/api/v129-audit/"+tag+"/plain",jwt.issue("portal:1"),403,1005,"portal:1");}
    @AfterEach void cleanup(){jdbc.update("delete from sys_audit_log where path like ? or username like ?","%"+tag+"%",tag+"%");for(Long id:staff){jdbc.update("delete from sys_user_role where user_id=?",id);jdbc.update("delete from sys_user where id=?",id);}staff.clear();}

    /** @TestConfiguration prevents component scanning into unrelated test contexts. */
    @TestConfiguration(proxyBeanMethods=false)
    @RestController
    @RequestMapping("/api/v129-audit")
    static class TestRoutes {
        @Bean FilterRegistrationBean<WrappingFilter> v129Wrapper(){var bean=new FilterRegistrationBean<>(new WrappingFilter());bean.setOrder(40);bean.addUrlPatterns("/api/v129-audit/*");return bean;}
        @PostMapping("/{tag}/{wrapper}/denied") @PreAuthorize("hasRole('ADMIN')")
        public R<Void> denied(){CLINICAL_WRITES.incrementAndGet();return R.ok();}
        @PostMapping("/{tag}/{wrapper}/authentication") public R<Void> authentication(){throw new BadCredentialsException("controlled authentication refusal");}
        @PostMapping("/{tag}/plain") public R<Map<String,Boolean>> write(){CLINICAL_WRITES.incrementAndGet();return R.ok(Map.of("saved",true));}
        @GetMapping("/{tag}/plain") public R<Void> read(){return R.ok();}
    }
    static class WrappingFilter implements Filter {
        @Override public void doFilter(ServletRequest req,ServletResponse res,FilterChain chain)throws IOException,ServletException{
            try{chain.doFilter(req,res);}catch(ServletException|RuntimeException original){
                String[] bits=((HttpServletRequest)req).getRequestURI().split("/");if(bits.length!=6)throw original;
                Throwable leaf=original;var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
                while(leaf!=null&&seen.add(leaf)&&!(leaf instanceof AccessDeniedException)&&!(leaf instanceof AuthenticationException))leaf=leaf.getCause();
                if(!(leaf instanceof RuntimeException security))throw original;
                switch(bits[4]){case "direct"->throw security;case "servlet"->throw new ServletException("controlled Servlet wrapper",security);case "runtime"->throw new IllegalStateException("controlled Runtime wrapper",security);case "deep"->throw new ServletException("controlled outer",new IllegalStateException("controlled middle",new ServletException("controlled inner",security)));default->throw original;}
            }
        }
    }
}
