package com.jsparser.json;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Provider interface for AST JSON serialization/deserialization.
 * Implementations are discovered via Java's ServiceLoader mechanism.
 *
 * <p>To use a provider, add the implementation JAR (e.g., harmonica-jackson)
 * to your classpath. The provider will be automatically discovered.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * AstJsonProvider provider = AstJsonProvider.getProvider();
 * String json = provider.getSerializer().serialize(program);
 * Program parsed = provider.getDeserializer().deserializeProgram(json);
 * }</pre>
 */
public interface AstJsonProvider {

    /**
     * Returns the serializer for converting AST nodes to JSON.
     *
     * @return the AST JSON serializer
     */
    AstJsonSerializer getSerializer();

    /**
     * Returns the deserializer for converting JSON to AST nodes.
     *
     * @return the AST JSON deserializer
     */
    AstJsonDeserializer getDeserializer();

    /**
     * Returns the name of this provider (e.g., "Jackson", "Gson").
     *
     * @return the provider name
     */
    String getName();

    /**
     * Gets the first available AstJsonProvider via ServiceLoader.
     *
     * @return the provider
     * @throws IllegalStateException if no provider is found on the classpath
     */
    static AstJsonProvider getProvider() {
        ServiceLoader<AstJsonProvider> loader = ServiceLoader.load(AstJsonProvider.class);
        Iterator<AstJsonProvider> iterator = loader.iterator();
        if (iterator.hasNext()) {
            return iterator.next();
        }
        throw new IllegalStateException(
            "No AstJsonProvider found on the classpath. " +
            "Add harmonica-jackson (or another provider) to your dependencies."
        );
    }

    /**
     * Gets an AstJsonProvider by name via ServiceLoader.
     *
     * @param name the provider name (e.g., "Jackson")
     * @return the provider
     * @throws IllegalStateException if no matching provider is found
     */
    static AstJsonProvider getProvider(String name) {
        ServiceLoader<AstJsonProvider> loader = ServiceLoader.load(AstJsonProvider.class);
        for (AstJsonProvider provider : loader) {
            if (provider.getName().equalsIgnoreCase(name)) {
                return provider;
            }
        }
        throw new IllegalStateException(
            "No AstJsonProvider found with name '" + name + "'. " +
            "Ensure the appropriate provider JAR is on the classpath."
        );
    }

    /**
     * Checks if any provider is available on the classpath.
     *
     * @return true if at least one provider is available
     */
    static boolean isProviderAvailable() {
        ServiceLoader<AstJsonProvider> loader = ServiceLoader.load(AstJsonProvider.class);
        return loader.iterator().hasNext();
    }
}
