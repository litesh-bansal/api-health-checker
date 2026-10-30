package com.uidai.enu.extapipulse.classify;

import com.uidai.enu.extapipulse.auth.AuthStepException;
import com.uidai.enu.extapipulse.classify.ExpectationCheck.Mismatch;
import com.uidai.enu.extapipulse.http.HttpReply;
import com.uidai.enu.extapipulse.model.FailureCategory;
import com.uidai.enu.extapipulse.model.Stage;
import com.uidai.enu.extapipulse.template.ConfigException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps whatever went wrong to a {@link FailureCategory}.
 *
 * This only looks at the single failure. Whether it is really the vendor's fault is decided
 * afterwards by the control check: if the reference URL also fails, the failure is ours.
 */
public class FailureClassifier {

    public record Classification(FailureCategory category, String detail, Integer httpStatus) {}

    /** Retrying these would not help, and repeating a rejected login can lock the account. */
    private static final Set<FailureCategory> NON_RETRYABLE = EnumSet.of(
            FailureCategory.AUTH, FailureCategory.AUTH_REJECTED, FailureCategory.AUTH_EXTRACT_FAILED,
            FailureCategory.CONFIG_ERROR, FailureCategory.CONTRACT);

    private static final Pattern TUNNEL_STATUS = Pattern.compile("(\\d{3})");

    public boolean isRetryable(FailureCategory category) {
        return !NON_RETRYABLE.contains(category);
    }

    public Classification classifyException(Throwable error, Stage stage, boolean viaProxy) {
        String route = viaProxy ? " (via proxy)" : "";

        if (has(error, ConfigException.class)) {
            return of(FailureCategory.CONFIG_ERROR, message(find(error, ConfigException.class)));
        }
        if (has(error, UnresolvedAddressException.class) || has(error, UnknownHostException.class)) {
            return of(FailureCategory.DNS, "Could not resolve host" + route);
        }
        String tunnel = tunnelMessage(error);
        if (tunnel != null) {
            return classifyTunnelFailure(tunnel);
        }
        if (has(error, SSLException.class)) {
            return of(FailureCategory.TLS, "TLS handshake failed: " + message(find(error, SSLException.class)));
        }
        if (has(error, HttpConnectTimeoutException.class)) {
            return of(viaProxy ? FailureCategory.PROXY : FailureCategory.NETWORK,
                    "Connect timed out" + (viaProxy ? " reaching the proxy" : ""));
        }
        if (has(error, HttpTimeoutException.class)) {
            return of(dropCategory(stage), "Connected, but no response within the timeout");
        }
        if (has(error, ConnectException.class)) {
            return of(viaProxy ? FailureCategory.PROXY : FailureCategory.NETWORK,
                    "Connection refused or unreachable" + (viaProxy ? " (could not reach the proxy)" : "")
                            + ": " + message(find(error, ConnectException.class)));
        }
        if (error instanceof IOException || has(error, IOException.class)) {
            // Connected, then the other side dropped us (reset, EOF, empty reply).
            return of(dropCategory(stage), "Connection dropped: " + message(find(error, IOException.class)));
        }
        return of(FailureCategory.CONFIG_ERROR, "Unexpected error: " + error.getClass().getSimpleName()
                + ": " + message(error));
    }

    public Classification classifyMainReply(HttpReply reply, Mismatch mismatch) {
        int s = reply.status();
        String detail = mismatch.reason() + snippet(reply);
        return switch (mismatch.kind()) {
            case LATENCY -> new Classification(FailureCategory.SLOW_RESPONSE, mismatch.reason(), s);
            case BODY -> new Classification(FailureCategory.CONTRACT, detail, s);
            case STATUS -> {
                FailureCategory c;
                if (s == 407) c = FailureCategory.PROXY;
                else if (s == 401 || s == 403) c = FailureCategory.AUTH;
                else if (s == 429) c = FailureCategory.RATE_LIMITED;
                else if (s >= 500) c = FailureCategory.VENDOR_DOWN;
                else c = FailureCategory.CONTRACT; // 404, 400, unexpected redirect...
                yield new Classification(c, detail, s);
            }
        };
    }

    public Classification classifyAuthStep(AuthStepException e) {
        HttpReply reply = e.reply();
        int s = reply.status();
        String detail = e.getMessage() + snippet(reply);
        if (e.kind() == AuthStepException.Kind.EXTRACT_FAILED) {
            return new Classification(FailureCategory.AUTH_EXTRACT_FAILED, detail, s);
        }
        FailureCategory c;
        if (s == 407) c = FailureCategory.PROXY;
        else if (s == 429) c = FailureCategory.RATE_LIMITED;
        else if (s >= 500) c = FailureCategory.AUTH_ENDPOINT_DOWN;
        else c = FailureCategory.AUTH_REJECTED; // 4xx, or 2xx with a failure body
        return new Classification(c, detail, s);
    }

    /**
     * HTTPS through a proxy starts with CONNECT. If the proxy refuses it, the JDK reports
     * the proxy's status in the exception message.
     */
    private Classification classifyTunnelFailure(String message) {
        Matcher m = TUNNEL_STATUS.matcher(message);
        Integer code = m.find() ? Integer.parseInt(m.group(1)) : null;
        if (code != null && (code == 502 || code == 503 || code == 504)) {
            // The proxy is alive but could not reach the API host.
            return new Classification(FailureCategory.NETWORK,
                    "Proxy could not reach the API host (CONNECT returned " + code + ")", code);
        }
        String why = code == null ? message
                : code == 407 ? "proxy authentication failed (407)"
                : code == 403 ? "proxy policy blocked this host (403) - is it whitelisted?"
                : "CONNECT returned " + code;
        return new Classification(FailureCategory.PROXY, "Proxy refused the tunnel: " + why, code);
    }

    /** We connected, then got no (complete) answer: whose fault depends on which call it was. */
    private static FailureCategory dropCategory(Stage stage) {
        return switch (stage) {
            case AUTH -> FailureCategory.AUTH_ENDPOINT_DOWN;
            case MAIN -> FailureCategory.VENDOR_DOWN;
            case CONTROL -> FailureCategory.NETWORK; // the reference URLs are never "down"
        };
    }

    private static String tunnelMessage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (c instanceof IOException && msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("tunnel") || lower.contains("proxy authentication")) {
                    return msg;
                }
            }
        }
        return null;
    }

    private static String snippet(HttpReply reply) {
        String s = reply.bodySnippet();
        return s.isEmpty() ? "" : " | body: " + s;
    }

    private static Classification of(FailureCategory c, String detail) {
        return new Classification(c, detail, null);
    }

    private static boolean has(Throwable t, Class<? extends Throwable> type) {
        return find(t, type) != null;
    }

    private static Throwable find(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return c;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return null;
    }

    private static String message(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
