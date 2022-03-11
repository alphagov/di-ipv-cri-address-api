package uk.gov.di.ipv.cri.address.api.handler;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.HttpStatus;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.di.ipv.cri.address.library.domain.SessionRequest;
import uk.gov.di.ipv.cri.address.library.error.ErrorResponse;
import uk.gov.di.ipv.cri.address.library.exception.ClientConfigurationException;
import uk.gov.di.ipv.cri.address.library.exception.SessionValidationException;
import uk.gov.di.ipv.cri.address.library.helpers.EventProbe;
import uk.gov.di.ipv.cri.address.library.service.AddressSessionService;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.di.ipv.cri.address.api.handler.SessionHandler.SESSION_ID;

@ExtendWith(MockitoExtension.class)
class SessionHandlerTest {

    @Mock private AddressSessionService addressSessionService;

    @Mock private APIGatewayProxyRequestEvent apiGatewayProxyRequestEvent;

    @Mock private SessionRequest sessionRequest;

    @Mock private EventProbe eventProbe;

    private SessionHandler sessionHandler;

    @BeforeEach
    void setUp() {
        sessionHandler = new SessionHandler(addressSessionService, eventProbe);
    }

    @Test
    void shouldCreateAndSaveAddressSession()
            throws SessionValidationException, ClientConfigurationException, JsonProcessingException {

        when(eventProbe.counterMetric(anyString())).thenReturn(eventProbe);

        UUID sessionId = UUID.randomUUID();
        when(sessionRequest.getClientId()).thenReturn("ipv-core");
        when(apiGatewayProxyRequestEvent.getBody()).thenReturn("some json");
        when(addressSessionService.validateSessionRequest("some json")).thenReturn(sessionRequest);
        when(addressSessionService.createAndSaveAddressSession(sessionRequest))
                .thenReturn(sessionId);

        APIGatewayProxyResponseEvent responseEvent =
                sessionHandler.handleRequest(apiGatewayProxyRequestEvent, null);

        assertEquals(HttpStatus.SC_CREATED, responseEvent.getStatusCode());
        Map responseBody = new ObjectMapper().readValue(responseEvent.getBody(), Map.class);
        assertEquals(sessionId.toString(), responseBody.get(SESSION_ID));

        verify(eventProbe).addDimensions(Map.of("issuer", "ipv-core"));
        verify(eventProbe).counterMetric("session_created");
    }

    @Test
    void shouldCatchValidationExceptionAndReturn400Response()
            throws SessionValidationException, ClientConfigurationException, JsonProcessingException {

        when(apiGatewayProxyRequestEvent.getBody()).thenReturn("some json");
        SessionValidationException sessionValidationException = new SessionValidationException("");
        when(addressSessionService.validateSessionRequest("some json"))
                .thenThrow(sessionValidationException);
        setupEventProbeErrorBehaviour();

        APIGatewayProxyResponseEvent responseEvent =
                sessionHandler.handleRequest(apiGatewayProxyRequestEvent, null);
        assertEquals(HttpStatus.SC_BAD_REQUEST, responseEvent.getStatusCode());
        Map responseBody = new ObjectMapper().readValue(responseEvent.getBody(), Map.class);
        assertEquals(ErrorResponse.SESSION_VALIDATION_ERROR.getCode(), responseBody.get("code"));
        assertEquals(
                ErrorResponse.SESSION_VALIDATION_ERROR.getMessage(), responseBody.get("message"));

        verify(eventProbe).counterMetric("session_created", 0d);
        verify(eventProbe).log(Level.INFO, sessionValidationException);
        verify(addressSessionService, never()).createAndSaveAddressSession(sessionRequest);
    }

    @Test
    void shouldCatchServerExceptionAndReturn500Response()
            throws SessionValidationException, ClientConfigurationException, JsonProcessingException {

        when(apiGatewayProxyRequestEvent.getBody()).thenReturn("some json");
        when(addressSessionService.validateSessionRequest("some json"))
                .thenThrow(new ClientConfigurationException(new NullPointerException()));
        setupEventProbeErrorBehaviour();

        APIGatewayProxyResponseEvent responseEvent =
                sessionHandler.handleRequest(apiGatewayProxyRequestEvent, null);
        assertEquals(HttpStatus.SC_INTERNAL_SERVER_ERROR, responseEvent.getStatusCode());
        Map responseBody = new ObjectMapper().readValue(responseEvent.getBody(), Map.class);
        assertEquals(ErrorResponse.SERVER_CONFIG_ERROR.getCode(), responseBody.get("code"));
        assertEquals(ErrorResponse.SERVER_CONFIG_ERROR.getMessage(), responseBody.get("message"));

        verify(eventProbe).counterMetric("session_created", 0d);
        verify(addressSessionService, never()).createAndSaveAddressSession(sessionRequest);
    }

    private void setupEventProbeErrorBehaviour() {
        when(eventProbe.counterMetric(anyString(), anyDouble())).thenReturn(eventProbe);
        when(eventProbe.log(any(Level.class), any(Exception.class))).thenReturn(eventProbe);
    }
}
