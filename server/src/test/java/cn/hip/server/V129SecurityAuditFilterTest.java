package cn.hip.server;

import cn.hip.server.web.AuditLogFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated filter-contract tests complement the separate real Tomcat/JWT suite.
 * These do not claim that ETF handles IOException, committed responses or cycles. */
class V129SecurityAuditFilterTest {
    RecordingJdbc jdbc;
    AuditLogFilter filter;
    MockHttpServletRequest request;
    MockHttpServletResponse response;
    static class RecordingJdbc extends JdbcTemplate {
        final List<List<Object>> rows=new ArrayList<>();boolean fail;
        @Override public int update(String sql,Object... args){assertTrue(sql.contains("insert into sys_audit_log"));rows.add(Arrays.asList(args));if(fail)throw new IllegalStateException("controlled audit storage failure");return 1;}
    }
    @BeforeEach void setup(){jdbc=new RecordingJdbc();filter=new AuditLogFilter(jdbc);request=new MockHttpServletRequest("POST","/api/v129-unit/security");request.setRemoteAddr("127.0.0.1");request.setQueryString("trace=known");response=new MockHttpServletResponse();response.setHeader("X-Controlled","kept");authenticate();}
    static void authenticate(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("v129-unit-employee",null,List.of()));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    RuntimeException leaf(String type){return type.equals("access")?new AccessDeniedException("controlled deny"):new BadCredentialsException("controlled auth");}
    Exception wrap(String type,RuntimeException security){return switch(type){
        case "direct"->security;
        case "servlet"->new ServletException("outer",security);
        case "runtime"->new IllegalStateException("outer",security);
        case "deep"->new ServletException("outer",new IllegalArgumentException("middle",new ServletException("inner",security)));
        // Match ETF's ServletException root-cause and InvocationTargetException extractors.
        case "legacy"->new ServletException("legacy root only"){@Override public Throwable getCause(){return null;}@Override public Throwable getRootCause(){return security;}};
        case "invocation"->new IllegalStateException("outer",new InvocationTargetException(security){@Override public Throwable getCause(){return null;}});
        case "io"->new IOException("IO is an ETF propagation boundary",security);
        default->throw new IllegalArgumentException(type);
    };}
    FilterChain throwing(Exception original){return (req,res)->{if(original instanceof ServletException e)throw e;if(original instanceof IOException e)throw e;if(original instanceof RuntimeException e)throw e;throw new AssertionError(original);};}
    void samePropagation(Exception original)throws Exception{Exception thrown=assertThrows(Exception.class,()->filter.doFilter(request,response,throwing(original)));assertSame(original,thrown,"Audit observation must never replace or unwrap the exception");assertEquals("kept",response.getHeader("X-Controlled"));}
    void ordinary(int status){assertEquals(List.of(Arrays.asList("v129-unit-employee","POST","/api/v129-unit/security?trace=known",status,"127.0.0.1")),jdbc.rows);}

    @ParameterizedTest @CsvSource({"access,direct","access,servlet","access,runtime","access,deep","access,legacy","access,invocation","authentication,direct","authentication,servlet","authentication,runtime","authentication,deep","authentication,legacy","authentication,invocation"})
    void uncommittedSecurityCausesDelegateAuditAndPropagateTheIdenticalOuterObject(String type,String wrapper)throws Exception{Exception original=wrap(wrapper,leaf(type));samePropagation(original);assertTrue(jdbc.rows.isEmpty(),"Only the later security handler may record an uncommitted security refusal");assertEquals(200,response.getStatus());assertFalse(response.isCommitted());}

    @ParameterizedTest @CsvSource({"access,direct","access,servlet","access,runtime","authentication,direct","authentication,servlet","authentication,runtime"})
    void committedSecurityFailureKeepsTheActualResponseAuditBecauseNoHandlerCanWrite(String type,String wrapper)throws Exception{response.setStatus(202);response.getWriter().write("already committed");response.flushBuffer();samePropagation(wrap(wrapper,leaf(type)));ordinary(202);assertTrue(response.isCommitted());assertEquals("already committed",response.getContentAsString());}

    @ParameterizedTest @ValueSource(strings={"access","authentication"})
    void ioWrapperIsNotTranslatedAndRetainsOrdinaryAudit(String type)throws Exception{samePropagation(wrap("io",leaf(type)));ordinary(200);}
    @ParameterizedTest @ValueSource(strings={"servlet","runtime","io"})
    void nonSecurityExceptionsKeepOrdinaryAuditAndTheOriginalException(String wrapper)throws Exception{Exception original=switch(wrapper){case "servlet"->new ServletException("ordinary",new IllegalArgumentException("inner"));case "runtime"->new IllegalStateException("ordinary",new IllegalArgumentException("inner"));default->new IOException("ordinary");};samePropagation(original);ordinary(200);}
    @ParameterizedTest @ValueSource(strings={"message","suppressed","inner-io"})
    void MessageSuppressedAndIoBoundaryDoNotInventASecurityHandler(String mode)throws Exception{Exception ordinary;
        if(mode.equals("message"))ordinary=new IllegalStateException("AccessDeniedException AuthenticationException");
        else if(mode.equals("suppressed")){ordinary=new ServletException("no security cause");ordinary.addSuppressed(leaf("access"));}
        else ordinary=new ServletException("outer",new IOException("nested IO is still visible to ETF's generic analyzer",new IllegalStateException("ordinary")));
        samePropagation(ordinary);ordinary(200);
    }
    @Test void nestedIoCauseStillDelegatesWhenTheOuterExceptionIsServlet()throws Exception{samePropagation(new ServletException("ETF catches this outer",new IOException("inner",leaf("access"))));assertTrue(jdbc.rows.isEmpty(),"Only a directly thrown IOException is ETF's early propagation boundary");}
    @Test void ordinaryCauseCycleTerminatesWithoutClaimingEtfCanTranslateIt(){assertTimeoutPreemptively(Duration.ofSeconds(2),()->{authenticate();try{var first=new ServletException("first");var second=new IllegalStateException("second",first);first.initCause(second);samePropagation(first);ordinary(200);}finally{SecurityContextHolder.clearContext();}});}
    @ParameterizedTest @ValueSource(strings={"success","ordinary-failure"})
    void auditFailureCannotChangeBusinessOrReplaceAnOriginalFailure(String mode)throws Exception{jdbc.fail=true;response.setStatus(201);if(mode.equals("success")){filter.doFilter(request,response,(req,res)->res.getWriter().write("business reply"));assertEquals("business reply",response.getContentAsString());}else samePropagation(new ServletException("original ordinary failure"));assertEquals(201,response.getStatus());ordinary(201);}
    @ParameterizedTest @CsvSource({"GET,/api/v129-unit/plain","PATCH,/api/v129-unit/plain"})
    void originalUnauditedMethodsAndReadsRemainOutsideOrdinaryAudit(String method,String path)throws Exception{request.setMethod(method);request.setRequestURI(path);filter.doFilter(request,response,(req,res)->{});assertTrue(jdbc.rows.isEmpty());}
}
