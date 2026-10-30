package com.uidai.enu.extapipulse.classify;

import com.uidai.enu.extapipulse.auth.AuthStepException;
import com.uidai.enu.extapipulse.classify.ExpectationCheck.Kind;
import com.uidai.enu.extapipulse.classify.ExpectationCheck.Mismatch;
import com.uidai.enu.extapipulse.http.HttpReply;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.Stage;
import com.uidai.enu.extapipulse.template.ConfigException;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    // These exception shapes are what java.net.http actually throws (checked against a real proxy).

    @Test
    void dnsFailure() {
        Throwable e = new ConnectException();
        e.initCause(new UnresolvedAddressException());
        assertEquals(FailureCategory.DNS, cat(e, Stage.MAIN, false));
    }

    @Test
    void proxyPolicyBlockAndAuthFailure() {
        assertEquals(FailureCategory.PROXY, cat(new IOException("Tunnel failed, got: 403"), Stage.MAIN, true));
        assertEquals(FailureCategory.PROXY, cat(new IOException("Tunnel failed, got: 407"), Stage.AUTH, true));
    }

    @Test
    void proxyCannotReachApiHostIsNetwork() {
        assertEquals(FailureCategory.NETWORK, cat(new IOException("Tunnel failed, got: 503"), Stage.MAIN, true));
    }

    @Test
    void refusedConnectionDependsOnRoute() {
        assertEquals(FailureCategory.NETWORK, cat(new ConnectException(), Stage.MAIN, false));
        assertEquals(FailureCategory.PROXY, cat(new ConnectException(), Stage.MAIN, true));
        assertEquals(FailureCategory.PROXY, cat(new HttpConnectTimeoutException("x"), Stage.MAIN, true));
        assertEquals(FailureCategory.NETWORK, cat(new HttpConnectTimeoutException("x"), Stage.MAIN, false));
    }

    @Test
    void timeoutAfterConnectBlamesTheCallThatHung() {
        assertEquals(FailureCategory.VENDOR_DOWN, cat(new HttpTimeoutException("t"), Stage.MAIN, true));
        assertEquals(FailureCategory.AUTH_ENDPOINT_DOWN, cat(new HttpTimeoutException("t"), Stage.AUTH, true));
        assertEquals(FailureCategory.NETWORK, cat(new HttpTimeoutException("t"), Stage.CONTROL, true));
    }

    @Test
    void tlsAndConfig() {
        assertEquals(FailureCategory.TLS, cat(new SSLHandshakeException("PKIX path building failed"), Stage.MAIN, false));
        assertEquals(FailureCategory.CONFIG_ERROR, cat(new ConfigException("Missing value"), Stage.MAIN, false));
    }

    @Test
    void mainReplies() {
        assertEquals(FailureCategory.VENDOR_DOWN, reply(503, Kind.STATUS));
        assertEquals(FailureCategory.AUTH, reply(401, Kind.STATUS));
        assertEquals(FailureCategory.AUTH, reply(403, Kind.STATUS));
        assertEquals(FailureCategory.RATE_LIMITED, reply(429, Kind.STATUS));
        assertEquals(FailureCategory.PROXY, reply(407, Kind.STATUS));
        assertEquals(FailureCategory.CONTRACT, reply(404, Kind.STATUS));
        assertEquals(FailureCategory.CONTRACT, reply(200, Kind.BODY));
        assertEquals(FailureCategory.SLOW_RESPONSE, reply(200, Kind.LATENCY));
    }

    @Test
    void loginReplies() {
        assertEquals(FailureCategory.AUTH_ENDPOINT_DOWN, login(500, AuthStepException.Kind.UNEXPECTED_RESPONSE));
        assertEquals(FailureCategory.AUTH_REJECTED, login(401, AuthStepException.Kind.UNEXPECTED_RESPONSE));
        assertEquals(FailureCategory.AUTH_REJECTED, login(200, AuthStepException.Kind.UNEXPECTED_RESPONSE));
        assertEquals(FailureCategory.AUTH_EXTRACT_FAILED, login(200, AuthStepException.Kind.EXTRACT_FAILED));
    }

    @Test
    void credentialFailuresAreNotRetried() {
        assertFalse(classifier.isRetryable(FailureCategory.AUTH_REJECTED));
        assertFalse(classifier.isRetryable(FailureCategory.AUTH));
        assertTrue(classifier.isRetryable(FailureCategory.VENDOR_DOWN));
        assertTrue(classifier.isRetryable(FailureCategory.NETWORK));
    }

    private FailureCategory cat(Throwable e, Stage stage, boolean viaProxy) {
        return classifier.classifyException(e, stage, viaProxy).category();
    }

    private FailureCategory reply(int status, Kind kind) {
        return classifier.classifyMainReply(r(status), new Mismatch(kind, "x")).category();
    }

    private FailureCategory login(int status, AuthStepException.Kind kind) {
        return classifier.classifyAuthStep(new AuthStepException(kind, r(status), "x")).category();
    }

    private static HttpReply r(int status) {
        return new HttpReply(status, HttpHeaders.of(Map.of(), (a, b) -> true), "{}", 10);
    }
}
