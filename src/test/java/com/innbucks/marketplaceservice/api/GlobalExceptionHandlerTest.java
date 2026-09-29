package com.innbucks.marketplaceservice.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins how {@link GlobalExceptionHandler} renders Spring MVC's OWN client
 * errors, through real MVC argument resolution / handler mapping (standalone
 * MockMvc over a throwaway controller) — including the shapes no production
 * endpoint can produce today (no required {@code @RequestParam} or
 * {@code @RequestHeader} exists yet), so the first one added cannot 500.
 *
 * <p>Before this, the {@code Exception} catch-all answered every one of them
 * {@code 500 INTERNAL_ERROR} and logged it at ERROR. The end-to-end half over
 * the real security chain is {@code FrameworkClientErrorsIT}.
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class Probe {
        @GetMapping("/t/get-only")
        Map<String, String> getOnly() {
            return Map.of("ok", "yes");
        }

        @GetMapping("/t/param")
        String param(@RequestParam("merchantId") String merchantId) {
            return merchantId;
        }

        @GetMapping("/t/header")
        String header(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }

        @PostMapping(value = "/t/part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String part(@RequestPart("listing") String listing) {
            return listing;
        }

        @PostMapping("/t/json")
        Map<String, Object> json(@RequestBody Map<String, Object> body) {
            return body;
        }

        @GetMapping(value = "/t/conditional", params = "mode")
        String conditional() {
            return "ok";
        }

        @GetMapping("/t/rse-conflict")
        String rseConflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "internal detail com.example.Secret");
        }

        @GetMapping("/t/rse-unavailable")
        String rseUnavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "upstream down");
        }

        @GetMapping("/t/boom")
        String boom() {
            throw new IllegalStateException("a real fault with com.example.Internal detail");
        }

        @GetMapping("/t/denied")
        String denied() {
            throw new AccessDeniedException("Access Denied");
        }

        @GetMapping("/t/validated")
        String validated(@RequestParam("size") @Min(1) int size) {
            return "ok";
        }

        @PostMapping("/t/file")
        String file(@RequestParam("file") MultipartFile file) {
            return "ok";
        }

        @PostMapping("/t/multipart-unparseable")
        String multipartUnparseable() {
            // What StandardMultipartHttpServletRequest throws for a body the
            // container cannot parse (no boundary, truncated) — MockMvc's mock
            // request never parses, so the shape is reproduced here.
            throw new MultipartException("Failed to parse multipart servlet request",
                    new IOException("the request was rejected because no multipart boundary was found"));
        }

        @PostMapping("/t/multipart-too-large")
        String multipartTooLarge() {
            throw new MaxUploadSizeExceededException(10L * 1024 * 1024);
        }

        @GetMapping("/t/unauthenticated")
        String unauthenticated() {
            throw new AuthenticationCredentialsNotFoundException("no credentials");
        }
    }

    private MockMvc mvc;
    private ListAppender<ILoggingEvent> logs;
    private Logger handlerLogger;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(logs);
    }

    private boolean anyErrorLogged() {
        return logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    void wrongVerbIs405WithAllowHeaderAndEnvelope() throws Exception {
        mvc.perform(delete("/t/get-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("method_not_allowed"))
                .andExpect(jsonPath("$.message").value("That HTTP method is not supported on this path"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void wrongContentTypeIs415WithAcceptHeader() throws Exception {
        mvc.perform(post("/t/json").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", containsString("application/json")))
                .andExpect(jsonPath("$.code").value("unsupported_media_type"))
                .andExpect(jsonPath("$.message").value("That Content-Type is not supported on this path"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void unsatisfiableAcceptIs406AndStillRendersTheJsonEnvelope() throws Exception {
        mvc.perform(get("/t/get-only").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("not_acceptable"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void missingRequiredParameterIs400NamingIt() throws Exception {
        mvc.perform(get("/t/param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_parameter"))
                .andExpect(jsonPath("$.message").value("Required parameter 'merchantId' is missing"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void missingRequiredHeaderIs400NamingIt() throws Exception {
        mvc.perform(get("/t/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_header"))
                .andExpect(jsonPath("$.message").value("Required header 'Idempotency-Key' is missing"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void missingRequiredMultipartPartIs400NamingIt() throws Exception {
        mvc.perform(multipart("/t/part")
                        .file(new MockMultipartFile("other", "x.txt", "text/plain", "x".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_part"))
                .andExpect(jsonPath("$.message").value("Required part 'listing' is missing"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void anyOtherFrameworkClientErrorKeepsItsStatusUnderAGenericCode() throws Exception {
        // UnsatisfiedServletRequestParameterException — no explicit handler;
        // covered by the ErrorResponse contract in the catch-all.
        mvc.perform(get("/t/conditional"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.message").value("The request could not be processed as sent"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void aResponseStatusException4xxKeepsItsStatusAndNeverEchoesItsReason() throws Exception {
        mvc.perform(get("/t/rse-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("conflict"))
                .andExpect(jsonPath("$.message").value("The request conflicts with the current state"))
                .andExpect(content().string(not(containsString("com.example"))));
    }

    @Test
    void anErrorResponseThatSays5xxIsStillAServerFault() throws Exception {
        mvc.perform(get("/t/rse-unavailable"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
        assertThat(anyErrorLogged()).isTrue();
    }

    @Test
    void aRealServerFaultIsStill500AndLoggedAtError() throws Exception {
        mvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("com.example"))));
        assertThat(anyErrorLogged()).isTrue();
    }

    @Test
    void methodSecurityDenialKeepsItsExact403Envelope() throws Exception {
        mvc.perform(get("/t/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Forbidden - insufficient role"));
    }

    @Test
    void anAuthenticationExceptionIsNotAFrameworkClientErrorAndKeepsItsPreExistingRoute() throws Exception {
        // Pinned as UNCHANGED by the framework-4xx mapping: an
        // AuthenticationException is not Spring MVC's ErrorResponse, so it
        // still takes the catch-all exactly as before. Not reachable through
        // the real chain — an anonymous caller is refused 401 by the filter
        // chain before any controller runs (SecuritySurfaceIT).
        mvc.perform(get("/t/unauthenticated"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @Test
    void aFailedParameterConstraintIsTheSameValidationErrorContractAsABody() throws Exception {
        mvc.perform(get("/t/validated").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.data.size").exists());
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void anUnparseableMultipartBodyIsAMalformedRequestNotA500() throws Exception {
        mvc.perform(post("/t/multipart-unparseable").contentType("multipart/form-data"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("Request body is malformed"))
                .andExpect(content().string(not(containsString("boundary"))));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void aFileParameterOnANonMultipartRequestIsAMalformedRequestNotA500() throws Exception {
        // Spring's own plain MultipartException ("Current request is not a
        // multipart request") — not an ErrorResponse, so the generic branch
        // would miss it.
        mvc.perform(post("/t/file").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void theMultipartSizeCapKeepsItsOwnMoreSpecificMapping() throws Exception {
        mvc.perform(post("/t/multipart-too-large"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("image_too_large"))
                .andExpect(jsonPath("$.message").value("That image is too large. Please use one under 10 MB."));
    }

    @Test
    void clientErrorLogLinesNeverCarryRequestData() throws Exception {
        // UnsatisfiedServletRequestParameterException's message lists every
        // query value the client sent — here a phone number.
        mvc.perform(get("/t/conditional").param("msisdn", "+263771234567"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/t/json").contentType("text/x-secret-263771234567").content("x"))
                .andExpect(status().isUnsupportedMediaType());
        assertThat(logs.list).isNotEmpty();
        assertThat(logs.list).allSatisfy(e ->
                assertThat(e.getFormattedMessage()).doesNotContain("263771234567"));
        assertThat(anyErrorLogged()).isFalse();
    }

    @Test
    void genericCodesAreAFixedTableNotHttpStatusNames() {
        assertThat(GlobalExceptionHandler.clientErrorCode(400)).isEqualTo("bad_request");
        assertThat(GlobalExceptionHandler.clientErrorCode(404)).isEqualTo("not_found");
        assertThat(GlobalExceptionHandler.clientErrorCode(405)).isEqualTo("method_not_allowed");
        assertThat(GlobalExceptionHandler.clientErrorCode(406)).isEqualTo("not_acceptable");
        assertThat(GlobalExceptionHandler.clientErrorCode(409)).isEqualTo("conflict");
        assertThat(GlobalExceptionHandler.clientErrorCode(413)).isEqualTo("request_too_large");
        assertThat(GlobalExceptionHandler.clientErrorCode(415)).isEqualTo("unsupported_media_type");
        assertThat(GlobalExceptionHandler.clientErrorCode(418)).isEqualTo("request_rejected");
    }
}
