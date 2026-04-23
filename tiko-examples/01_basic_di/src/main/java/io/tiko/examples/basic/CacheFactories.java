package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Produces;

@Component(scope = Scope.SINGLETON)
public class CacheFactories {

    @Produces(scope = Scope.SINGLETON, name = "memory")
    public Cache memoryCache() {
        return () -> "memory";
    }

    @Produces(scope = Scope.SINGLETON, name = "distributed")
    public Cache distributedCache() {
        return () -> "distributed";
    }
}
