package uk.gov.di.ipv.cri.address.api.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.OAuth2Error;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.AccessTokenType;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.lambda.powertools.logging.CorrelationIdPathConstants;
import software.amazon.lambda.powertools.logging.Logging;
import software.amazon.lambda.powertools.metrics.Metrics;
import uk.gov.di.ipv.cri.address.api.exception.CredentialRequestException;
import uk.gov.di.ipv.cri.address.api.service.VerifiableCredentialService;
import uk.gov.di.ipv.cri.address.library.service.AddressService;
import uk.gov.di.ipv.cri.common.library.domain.AuditEventType;
import uk.gov.di.ipv.cri.common.library.error.ErrorResponse;
import uk.gov.di.ipv.cri.common.library.exception.AccessTokenExpiredException;
import uk.gov.di.ipv.cri.common.library.exception.SessionExpiredException;
import uk.gov.di.ipv.cri.common.library.exception.SessionNotFoundException;
import uk.gov.di.ipv.cri.common.library.exception.SqsException;
import uk.gov.di.ipv.cri.common.library.service.AuditService;
import uk.gov.di.ipv.cri.common.library.service.ConfigurationService;
import uk.gov.di.ipv.cri.common.library.service.SessionService;
import uk.gov.di.ipv.cri.common.library.util.ApiGatewayResponseGenerator;
import uk.gov.di.ipv.cri.common.library.util.EventProbe;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.apache.logging.log4j.Level.ERROR;

public class IssueCredentialHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    public static final String AUTHORIZATION_HEADER_KEY = "Authorization";
    public static final String ADDRESS_CREDENTIAL_ISSUER = "address_credential_issuer";
    private final VerifiableCredentialService verifiableCredentialService;
    private final AddressService addressService;
    private final SessionService sessionService;
    private final EventProbe eventProbe;
    private final AuditService auditService;

    public IssueCredentialHandler(
            VerifiableCredentialService verifiableCredentialService,
            AddressService addressService,
            SessionService sessionService,
            EventProbe eventProbe,
            AuditService auditService) {
        this.verifiableCredentialService = verifiableCredentialService;
        this.addressService = addressService;
        this.sessionService = sessionService;
        this.eventProbe = eventProbe;
        this.auditService = auditService;
    }

    public IssueCredentialHandler() {
        ConfigurationService configurationService = new ConfigurationService();
        ObjectMapper objectMapper =
                new ObjectMapper()
                        .registerModule(new Jdk8Module())
                        .registerModule(new JavaTimeModule());
        this.verifiableCredentialService = getVerifiableCredentialService();
        this.addressService = new AddressService(configurationService, objectMapper);
        this.sessionService = new SessionService();
        this.eventProbe = new EventProbe();
        this.auditService =
                new AuditService(
                        SqsClient.builder().build(),
                        configurationService,
                        objectMapper,
                        Clock.systemUTC());
    }

    @Override
    @Logging(correlationIdPath = CorrelationIdPathConstants.API_GATEWAY_REST)
    @Metrics(captureColdStart = true)
    public APIGatewayProxyResponseEvent handleRequest(
            APIGatewayProxyRequestEvent input, Context context) {

        try {
            var accessToken = validateInputHeaderBearerToken(input.getHeaders());
            var sessionItem = sessionService.getSessionByAccessToken(accessToken);
            var addressItem = addressService.getAddressItem(sessionItem.getSessionId());

            SignedJWT signedJWT =
                    verifiableCredentialService.generateSignedVerifiableCredentialJwt(
                            sessionItem.getSubject(), addressItem.getAddresses());
            auditService.sendAuditEvent(AuditEventType.VC_ISSUED);
            eventProbe.counterMetric(ADDRESS_CREDENTIAL_ISSUER, 0d);

            return ApiGatewayResponseGenerator.proxyJwtResponse(
                    HttpStatusCode.OK, signedJWT.serialize());
        } catch (AwsServiceException ex) {
            eventProbe.log(ERROR, ex).counterMetric(ADDRESS_CREDENTIAL_ISSUER, 0d);

            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.SERVER_ERROR.getHTTPStatusCode(),
                    OAuth2Error.SERVER_ERROR.appendDescription(" - " + ex.awsErrorDetails().errorMessage()));
        } catch (CredentialRequestException | ParseException | JOSEException e) {
            eventProbe.log(ERROR, e).counterMetric(ADDRESS_CREDENTIAL_ISSUER, 0d);

            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.INVALID_REQUEST.getHTTPStatusCode(),
                    OAuth2Error.INVALID_REQUEST
                            .appendDescription(" - " + ErrorResponse.VERIFIABLE_CREDENTIAL_ERROR)
                            .toJSONObject());
        } catch (SqsException sqsException) {
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.SERVER_ERROR.getHTTPStatusCode(),
                    OAuth2Error.SERVER_ERROR
                            .appendDescription(" - " + sqsException.getMessage())
                            .toJSONObject());
        } catch (AccessTokenExpiredException e) {
            eventProbe.log(ERROR, e).counterMetric(ADDRESS_CREDENTIAL_ISSUER, 0d);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.ACCESS_DENIED.getHTTPStatusCode(),
                    OAuth2Error.ACCESS_DENIED
                            .appendDescription(" - " + ErrorResponse.ACCESS_TOKEN_EXPIRED)
                            .toJSONObject());
        } catch (SessionExpiredException e) {
            eventProbe.log(ERROR, e).counterMetric(ADDRESS_CREDENTIAL_ISSUER, 0d);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.ACCESS_DENIED.getHTTPStatusCode(),
                    OAuth2Error.ACCESS_DENIED
                            .appendDescription(" - " + ErrorResponse.SESSION_EXPIRED)
                            .toJSONObject());
        } catch (SessionNotFoundException e) {
            eventProbe.log(ERROR, e).counterMetric(ADDRESS_CREDENTIAL_ISSUER, 0d);
            return ApiGatewayResponseGenerator.proxyJsonResponse(
                    OAuth2Error.ACCESS_DENIED.getHTTPStatusCode(),
                    OAuth2Error.ACCESS_DENIED
                            .appendDescription(" - " + ErrorResponse.SESSION_NOT_FOUND)
                            .toJSONObject());
        }
    }

    private AccessToken validateInputHeaderBearerToken(Map<String, String> headers)
            throws CredentialRequestException, ParseException {
        var token =
                Optional.ofNullable(headers).stream()
                        .flatMap(x -> x.entrySet().stream())
                        .filter(
                                header ->
                                        AUTHORIZATION_HEADER_KEY.equalsIgnoreCase(header.getKey()))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new CredentialRequestException(
                                                ErrorResponse.MISSING_AUTHORIZATION_HEADER));

        return AccessToken.parse(token, AccessTokenType.BEARER);
    }

    private VerifiableCredentialService getVerifiableCredentialService() {
        Supplier<VerifiableCredentialService> factory = VerifiableCredentialService::new;
        return factory.get();
    }
}
