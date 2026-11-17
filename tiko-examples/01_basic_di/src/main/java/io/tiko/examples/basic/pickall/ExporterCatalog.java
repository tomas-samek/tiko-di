package io.tiko.examples.basic.pickall;

import io.tiko.Picker;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.util.List;

/**
 * Lists every {@link Exporter}. Nothing injects one {@code Exporter} on its own, so the
 * implementations need no name: the picker takes them all (#812).
 */
@Component(scope = Scope.SINGLETON)
public class ExporterCatalog {

    private final Picker<Exporter> exporters;

    @Inject
    public ExporterCatalog(Picker<Exporter> exporters) {
        this.exporters = exporters;
    }

    public List<String> formats() {
        return exporters.list().stream().map(Exporter::format).toList();
    }
}
