package io.tiko.examples.basic.pickall;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON)
public class CsvExporter implements Exporter {

    @Override
    public String format() {
        return "csv";
    }
}
