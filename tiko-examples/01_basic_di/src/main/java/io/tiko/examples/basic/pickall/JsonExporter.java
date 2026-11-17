package io.tiko.examples.basic.pickall;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.SINGLETON)
public class JsonExporter implements Exporter {

    @Override
    public String format() {
        return "json";
    }
}
