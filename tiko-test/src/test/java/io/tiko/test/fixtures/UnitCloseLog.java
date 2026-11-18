package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The ids of the {@link UnitProbe}s whose unit of work has closed, in closing order. */
@Component(scope = Scope.SINGLETON)
public class UnitCloseLog {

    private final List<String> closed = new CopyOnWriteArrayList<>();

    void closed(String id) {
        closed.add(id);
    }

    public List<String> closed() {
        return List.copyOf(closed);
    }
}
