package com.learning.coreweb.ioc;

/**
 * Two implementations exist. How Spring picks one is the classic DI interview question —
 * see {@link EnglishGreetingService} and {@link PirateGreetingService}.
 */
public interface GreetingService {

    String greet(String name);

    /** Used as the key when Spring injects a Map&lt;String, GreetingService&gt;. */
    String style();
}
