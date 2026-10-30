# enu-external-api-pulse

Checks the external state APIs we depend on, works out whose fault a failure is, and emails the right people without spamming them.

## Run

```bash
mvn spring-boot:run                                    # uses ./config/pulse-probes.yml
PULSE_CONFIG=/etc/pulse/pulse-probes.yml java -jar target/enu-external-api-pulse-0.1.0-SNAPSHOT.jar
```

Every `{{env.NAME}}` used in the config must be set as an environment variable (or Spring property), or startup fails and lists the missing ones.
Mail is off by default (`pulse.mail.enabled: false`), so alerts are only logged until the SMTP settings in `application.yml` are filled in.

Useful endpoints:

| Endpoint | What it does |
|---|---|
| `GET /pulse/status` | current state of every probe and control check |
| `GET /pulse/probes` | resolved probe config (no secrets) |
| `POST /pulse/probes/{source}/{docId}/check` | run one probe now and see the result; sends no alerts. Use this when onboarding a new API |

## Add an API (config only)

New docId for an existing state: add it under that source's `docs`. New state: add a `sources` entry. Then restart.
See `config/pulse-probes.yml` for every option with comments.

## How a check works

1. **Login** (if the API's auth profile is `token-call`): run the login request, check it, pull the token/session out of the JSON, cache it until it expires. `apiKey`, `basic` and `bearer` just add a header. `none` skips this.
2. **Main call** with `{{auth.*}}` / `{{env.*}}` filled in, compared with `expect` (status, JSON field, body text, max latency).
3. **Retries** for transient failures. Rejected credentials are never retried (account lockout risk).
4. **Control check** on failure: call always-up reference URLs through the same proxy. If they fail too, it's our network, so no vendor email.
5. **State machine** decides whether to email: DOWN after `failureThreshold` failures in a row, reminders at most every `reminderIntervalMin` (with optional backoff), an immediate email if the cause changes to one routed to different people, and a recovery email after `recoveryThreshold` passes in a row, which also resets every timer.
6. **Routing** by failure category decides who is told and through which channels.

## Failure categories

| Category | Meaning | Usually whose problem |
|---|---|---|
| DNS, PROXY, NETWORK | can't resolve / proxy refused / can't connect | ours |
| CONFIG_ERROR | missing secret, bad placeholder | ours |
| AUTH, AUTH_REJECTED | our credentials rejected | ours |
| TLS | certificate problem | either |
| AUTH_ENDPOINT_DOWN, AUTH_EXTRACT_FAILED | their login service is down / changed | theirs |
| VENDOR_DOWN, SLOW_RESPONSE, CONTRACT | 5xx or timeout / too slow / unexpected answer | theirs |
| RATE_LIMITED | 429 | check our quota |

## Code layout

```
config/    PulseProperties (YAML shape), ProbeCatalog (inheritance + validation), PulseConfiguration (wiring)
model/     ProbeKey(source, docId), ProbeDefinition, AuthProfile, Expectation, CheckResult, FailureCategory...
template/  TemplateRenderer ({{env}}, {{auth}}, {{uuid}}, {{now}}), SecretMasker
json/      JsonPathLite ($.a.b[0] on Jackson)
http/      HttpClientRegistry (one client per proxy), CallBuilder, HttpExecutor
auth/      AuthResolver (login flow + cache + re-login on 401), TokenCache
classify/  ExpectationCheck, FailureClassifier
control/   ControlCheckService (our network vs theirs)
incident/  IncidentTracker (state machine + throttling), IncidentStore, AlertEvent
alert/     AlertDispatcher (routing), RecipientResolver, AlertFormatter, EmailChannel, AlertChannel
probe/     ProbeRunner (one check end to end), ProbeScheduler
web/       StatusController
```

## Adding Jira later

Write a `JiraChannel implements AlertChannel` with `name()` returning `"jira"`, register it as a bean, and add `jira` to `channels` for the categories that should raise tickets. Keep one open ticket per `(source, docId)`: create it on DOWN, comment on REMINDER and CATEGORY_CHANGED, close on RECOVERED.

## Before production

- **Persist incident state.** `InMemoryIncidentStore` forgets everything on restart, so anything still down gets a fresh DOWN email after a deploy. Implement `IncidentStore` over a DB table or Redis.
- **Run one instance** until state is shared, or every instance will send its own emails.
- **Proxy auth:** `ExtApiPulseApplication` clears `jdk.http.auth.tunneling.disabledSchemes` so the JDK client can send Basic proxy credentials over HTTPS.
