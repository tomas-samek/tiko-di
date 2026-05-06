package io.tiko.comparisons.dagger.app;

import dagger.Component;
import io.tiko.comparisons.dagger.modulea.ModuleADagger;
import io.tiko.comparisons.dagger.modulea.UserService;
import io.tiko.comparisons.dagger.moduleb.ModuleBDagger;
import io.tiko.comparisons.dagger.moduleb.NotificationService;

import javax.inject.Singleton;

@Singleton
@Component(modules = {ModuleADagger.class, ModuleBDagger.class})
public interface AppComponent {

    UserService userService();

    NotificationService notificationService();
}
