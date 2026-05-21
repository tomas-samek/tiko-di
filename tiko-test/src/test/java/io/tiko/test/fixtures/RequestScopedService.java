package io.tiko.test.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(scope = Scope.REQUEST)
public class RequestScopedService {}
