package com.bridgecore.display;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Linkage check only: deliberately does not claim to be an FML/game startup test. */
class DisplayOptionalCcTest {
    private URL[] classpath() {
        return Arrays.stream(System.getProperty("bridgecore.linkClasspath").split(File.pathSeparator))
                .map(p -> { try { return new File(p).toURI().toURL(); } catch (Exception e) { throw new RuntimeException(e); } })
                .toArray(URL[]::new);
    }
    @Test void displayAndModEntrypointTypesLinkWhenCcApiCannotBeLoaded() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(classpath(), ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("dan200.computercraft.") || name.startsWith("org.squiddev.cobalt."))
                    throw new ClassNotFoundException("Optional CC deliberately absent: " + name);
                return super.loadClass(name, resolve);
            }
        }) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("dan200.computercraft.api.peripheral.IPeripheral"));
            for (String name : new String[]{"DisplayGpuBlockEntity", "DisplayTileBlockEntity", "DisplayContentSink",
                    "ExternalDisplaySession", "DisplayRaster", "Canvas", "DisplayApps"}) {
                Class<?> type = Class.forName("com.bridgecore.display." + name, false, loader);
                assertDoesNotThrow(type::getDeclaredMethods, name);
                assertDoesNotThrow(type::getDeclaredConstructors, name);
            }
            Class<?> mod = Class.forName("com.bridgecore.BridgeCoreMod", false, loader);
            assertDoesNotThrow(mod::getDeclaredMethods);
            assertDoesNotThrow(mod::getDeclaredConstructors);
        }
    }
    @Test void actualFmlParserSeesPackagedCcDependencyAsOptionalAndLegacyMandatoryFlagWouldNotWork() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(classpath(), ClassLoader.getPlatformClassLoader())) {
            Class<?> parser = loader.loadClass("com.electronwill.nightconfig.toml.TomlParser");
            Class<?> config = loader.loadClass("com.electronwill.nightconfig.core.UnmodifiableConfig");
            Object root = parser.getMethod("parse", java.io.Reader.class).invoke(parser.getConstructor().newInstance(),
                    new StringReader(Files.readString(Path.of("build/resources/main/META-INF/neoforge.mods.toml"))));
            List<?> dependencies = (List<?>)config.getMethod("get", String.class).invoke(root, "dependencies.bridgecore");
            Object cc = null;
            for (Object entry : dependencies) {
                if ("computercraft".equals(config.getMethod("get", String.class).invoke(entry, "modId"))) cc = entry;
            }
            assertNotNull(cc);
            assertEquals("OPTIONAL", dependencyType(loader, config, cc));
            Object legacy = parser.getMethod("parse", java.io.Reader.class).invoke(parser.getConstructor().newInstance(),
                    new StringReader("modId='computercraft'\nmandatory=false\n"));
            assertEquals("REQUIRED", dependencyType(loader, config, legacy));
        }
    }
    private String dependencyType(ClassLoader loader, Class<?> config, Object entry) throws Exception {
        Class<?> configurable = loader.loadClass("net.neoforged.neoforgespi.language.IConfigurable");
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{configurable}, (p, m, a) -> {
            if (m.getName().equals("getConfigElement"))
                return config.getMethod("getOptional", List.class).invoke(entry, Arrays.asList((String[])a[0]));
            if (m.getName().equals("getConfigList")) return List.of();
            return null;
        });
        Class<?> version = loader.loadClass("net.neoforged.fml.loading.moddiscovery.ModInfo$ModVersion");
        var constructor = version.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object dependency = constructor.newInstance(null, null, proxy);
        var getType = version.getDeclaredMethod("getType"); getType.setAccessible(true);
        return getType.invoke(dependency).toString();
    }
}
