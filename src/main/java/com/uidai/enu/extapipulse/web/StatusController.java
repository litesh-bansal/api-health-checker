package com.uidai.enu.extapipulse.web;

import com.uidai.enu.extapipulse.config.ProbeCatalog;
import com.uidai.enu.extapipulse.incident.IncidentState;
import com.uidai.enu.extapipulse.incident.IncidentState.IncidentSnapshot;
import com.uidai.enu.extapipulse.incident.IncidentStore;
import com.uidai.enu.extapipulse.model.CheckResult;
import com.uidai.enu.extapipulse.model.ProbeDefinition;
import com.uidai.enu.extapipulse.model.ProbeKey;
import com.uidai.enu.extapipulse.probe.ProbeRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * GET  /pulse/status                          current state of every probe and control check
 * GET  /pulse/probes                          resolved probe configuration (no secrets)
 * POST /pulse/probes/{source}/{docId}/check   run one probe now and see the result (no alerts sent)
 */
@RestController
@RequestMapping("/pulse")
public class StatusController {

    private final IncidentStore store;
    private final ProbeCatalog catalog;
    private final ProbeRunner runner;

    public StatusController(IncidentStore store, ProbeCatalog catalog, ProbeRunner runner) {
        this.store = store;
        this.catalog = catalog;
        this.runner = runner;
    }

    @GetMapping("/status")
    public List<IncidentSnapshot> status() {
        return store.all().stream()
                .map(IncidentState::snapshot)
                .sorted(Comparator.comparing((IncidentSnapshot s) -> s.key().source())
                        .thenComparing(s -> s.key().docId()))
                .toList();
    }

    @GetMapping("/probes")
    public List<ProbeView> probes() {
        return catalog.probes().stream().map(ProbeView::of).toList();
    }

    @PostMapping("/probes/{source}/{docId}/check")
    public ResponseEntity<CheckResult> checkNow(@PathVariable String source, @PathVariable String docId)
            throws InterruptedException {
        ProbeDefinition def = catalog.probe(new ProbeKey(source, docId));
        if (def == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(runner.check(def));
    }

    public record ProbeView(String source, String docId, String method, String url, String auth, String proxy,
                            long intervalSec, long timeoutMs, int retries, List<String> contacts) {
        static ProbeView of(ProbeDefinition d) {
            return new ProbeView(d.key().source(), d.key().docId(), d.request().method(), d.request().url(),
                    d.authProfile(), d.proxy(), d.interval().toSeconds(), d.timeout().toMillis(), d.retries(),
                    d.contacts());
        }
    }
}
