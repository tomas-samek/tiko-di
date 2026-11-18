package io.tiko.coroutines.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The ids of the {@link RequestId}s whose unit of work has closed, in closing order. */
@Component(scope = Scope.SINGLETON)
public class CloseLog {

    private final List<String> closed = new CopyOnWriteArrayList<>();

    void closed(String id) {
        closed.add(id);
    }

    public List<String> closed() {
        return List.copyOf(closed);
    }
}
