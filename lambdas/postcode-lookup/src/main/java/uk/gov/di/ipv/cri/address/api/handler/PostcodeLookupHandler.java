package uk.gov.di.ipv.cri.address.api.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import org.apache.logging.log4j.Level;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.lambda.powertools.logging.CorrelationIdPathConstants;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.metrics.Metrics;
import uk.gov.di.ipv.cri.address.api.exceptions.PostcodeLookupValidationException;
import uk.gov.di.ipv.cri.address.api.service.PostcodeLookupService;
import uk.gov.di.ipv.cri.common.library.annotations.ExcludeFromGeneratedCoverageReport;
import uk.gov.di.ipv.cri.common.library.error.ErrorResponse;
import uk.gov.di.ipv.cri.common.library.exception.SessionExpiredException;
import uk.gov.di.ipv.cri.common.library.exception.SessionNotFoundException;
import uk.gov.di.ipv.cri.common.library.persistence.item.CanonicalAddress;
import uk.gov.di.ipv.cri.common.library.service.SessionService;
import uk.gov.di.ipv.cri.common.library.util.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.cri.common.library.util.EventProbe;

import java.util.List;

public class PostcodeLookupHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final PostcodeLookupService postcodeLookupService;
    private final SessionService sessionService;
    private final EventProbe eventProbe;
    protected static final String SESSION_ID = "session_id";
    protected static final String LAMBDA_NAME = "postcode_lookup";

    public PostcodeLookupHandler(
            PostcodeLookupService postcodeLookupService,
            SessionService sessionService,
            EventProbe eventProbe) {
        this.postcodeLookupService = postcodeLookupService;
        this.sessionService = sessionService;
        this.eventProbe = eventProbe;
    }

    @ExcludeFromGeneratedCoverageReport
    public PostcodeLookupHandler() {

        this.postcodeLookupService = new PostcodeLookupService();
        this.sessionService = new SessionService();
        this.eventProbe = new EventProbe();
    }

    @Override
    @Logging(correlationIdPath = CorrelationIdPathConstants.API_GATEWAY_REST)
    @Metrics(captureColdStart = true)
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {

        String sessionId = input.getHeaders().get(SESSION_ID);
        String postcode = input.getPathParameters().get("postcode");

        try {
            sessionService.validateSessionId(sessionId);
            List<CanonicalAddress> results = postcodeLookupService.lookupPostcode(postcode);
            eventProbe.counterMetric(LAMBDA_NAME);

            return ApiGatewayResponseGenerator.proxyJsonResponse(HttpStatusCode.OK, results);

        } catch (PostcodeLookupValidationException e) {
            eventProbe.log(Level.ERROR, e).counterMetric(LAMBDA_NAME, 0d);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.INVALID_REQUEST.getHTTPStatusCode(),
                    OAuth2Error.INVALID_REQUEST
                            .appendDescription(" - " + ErrorResponse.INVALID_POSTCODE)
                            .toJSONObject());
        } catch (SessionExpiredException e) {
            eventProbe.log(Level.ERROR, e).counterMetric(LAMBDA_NAME, 0d);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.ACCESS_DENIED.getHTTPStatusCode(),
                    OAuth2Error.ACCESS_DENIED
                            .appendDescription(" - " + ErrorResponse.SESSION_EXPIRED)
                            .toJSONObject());
        } catch (SessionNotFoundException e) {
            eventProbe.log(Level.ERROR, e).counterMetric(LAMBDA_NAME, 0d);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.ACCESS_DENIED.getHTTPStatusCode(),
                    OAuth2Error.ACCESS_DENIED
                            .appendDescription(" - " + ErrorResponse.SESSION_NOT_FOUND)
                            .toJSONObject());
        } catch (Exception e) {
            eventProbe.log(Level.ERROR, e).counterMetric(LAMBDA_NAME, 0d);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.SERVER_ERROR.getHTTPStatusCode(),
                    OAuth2Error.SERVER_ERROR
                            .appendDescription(" - " + e.getMessage())
                            .toJSONObject());
        }
    }
}
