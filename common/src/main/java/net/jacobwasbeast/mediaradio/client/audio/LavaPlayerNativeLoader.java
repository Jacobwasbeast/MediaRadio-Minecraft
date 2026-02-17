package net.jacobwasbeast.mediaradio.client.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sedmelluq.discord.lavaplayer.natives.ConnectorNativeLibLoader;
import com.sedmelluq.lava.common.natives.NativeLibraryLoader;
import com.sedmelluq.lava.common.natives.NativeLibraryProperties;
import com.sedmelluq.lava.common.natives.ResourceNativeLibraryBinaryProvider;
import com.sedmelluq.lava.common.natives.architecture.DefaultOperatingSystemTypes;
import com.sedmelluq.lava.common.natives.architecture.SystemType;
import net.jacobwasbeast.mediaradio.MediaRadio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LavaPlayerNativeLoader {

    private static final String NATIVES_VERSION = "2.2.6";
    private static final String RELEASE_BASE_URL =
            "https://github.com/Jacobwasbeast/MediaRadio-Natives-Repo/releases/download/" + NATIVES_VERSION + "/";
    private static final String NATIVES_INDEX_URL = RELEASE_BASE_URL + "index.json";
    private static final Path NATIVES_ROOT = Path.of("mediaradio", "lavaplayer_natives", "lava-natives-" + NATIVES_VERSION);

    private static volatile boolean initialized;

    private LavaPlayerNativeLoader() {
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        try {
            var field = ConnectorNativeLibLoader.class.getDeclaredField("loaders");
            field.setAccessible(true);
            NativeLibraryLoader[] loaders = (NativeLibraryLoader[]) field.get(null);
            if (loaders == null || loaders.length < 2) {
                MediaRadio.LOGGER.warn("Lavaplayer native loaders not available");
                return;
            }
            loaders[0] = createFiltered(
                    ConnectorNativeLibLoader.class,
                    "libmpg123-0",
                    system -> system.osType == DefaultOperatingSystemTypes.WINDOWS
            );
            loaders[1] = create(ConnectorNativeLibLoader.class, "connector");
            initialized = true;
            MediaRadio.LOGGER.info("Configured external Lavaplayer native loader");
        } catch (Exception ex) {
            MediaRadio.LOGGER.error("Failed to configure Lavaplayer native loader", ex);
        }
    }

    private static NativeLibraryLoader create(Class<?> classLoaderSample, String libraryName) {
        return createFiltered(classLoaderSample, libraryName, null);
    }

    private static NativeLibraryLoader createFiltered(
            Class<?> classLoaderSample,
            String libraryName,
            Predicate<SystemType> systemFilter
    ) {
        ResourceNativeLibraryBinaryProvider provider = new NullResourceNativeLibraryBinaryProvider(classLoaderSample);
        return new NativeLibraryLoader(
                libraryName,
                systemFilter,
                new ExternalNativeLibraryProperties(libraryName, systemFilter),
                provider
        );
    }

    private static final class NullResourceNativeLibraryBinaryProvider extends ResourceNativeLibraryBinaryProvider {

        private NullResourceNativeLibraryBinaryProvider(Class<?> classLoaderSample) {
            super(classLoaderSample, "/natives/");
        }

        @Override
        public InputStream getLibraryStream(SystemType systemType, String libraryName) {
            return null;
        }
    }

    private static final class ExternalNativeLibraryProperties implements NativeLibraryProperties {
        private final Predicate<SystemType> systemFilter;
        private final String libraryName;

        private ExternalNativeLibraryProperties(String libraryName, Predicate<SystemType> systemFilter) {
            this.systemFilter = systemFilter;
            this.libraryName = libraryName;
        }

        @Override
        public String getLibraryPath() {
            return null;
        }

        @Override
        public String getLibraryDirectory() {
            SystemType system = detectSystemType(this, systemFilter);
            if (system == null) {
                throw new IllegalStateException("Unsupported system type for Lavaplayer natives");
            }
            String releasePlatformKey = releasePlatformKey(system);
            String fileName = system.formatLibraryName(libraryName);
            Path platformDir = NATIVES_ROOT.resolve(releasePlatformKey);
            Path libraryFile = platformDir.resolve(fileName);

            if (!Files.exists(libraryFile)) {
                downloadAndExtractNatives(releasePlatformKey, platformDir, libraryFile);
            }

            if (!Files.exists(libraryFile)) {
                throw new UnsatisfiedLinkError("Required library was not found");
            }
            return platformDir.toAbsolutePath().toString();
        }

        @Override
        public String getExtractionPath() {
            return null;
        }

        @Override
        public String getSystemName() {
            return null;
        }

        @Override
        public String getLibraryFileNamePrefix() {
            return null;
        }

        @Override
        public String getLibraryFileNameSuffix() {
            return null;
        }

        @Override
        public String getArchitectureName() {
            return null;
        }
    }

    private static synchronized void downloadAndExtractNatives(String releasePlatformKey, Path platformDir, Path requiredFile) {
        if (Files.exists(requiredFile)) {
            return;
        }
        try {
            Files.createDirectories(platformDir);
            JsonObject index = downloadIndex();
            JsonObject platforms = index.getAsJsonObject("platforms");
            if (platforms == null) {
                throw new IllegalStateException("Missing platforms in natives index");
            }
            JsonObject platform = platforms.getAsJsonObject(releasePlatformKey);
            if (platform == null || !platform.has("file")) {
                throw new IllegalStateException("Missing natives platform in index: " + releasePlatformKey);
            }
            String file = platform.get("file").getAsString();
            String url = RELEASE_BASE_URL + file;
            MediaRadio.LOGGER.info("Downloading Lavaplayer natives {} from {}", releasePlatformKey, url);

            Path tmpZip = platformDir.resolve("natives-" + System.currentTimeMillis() + ".zip");
            try {
                downloadFile(new URI(url).toURL(), tmpZip);
                extractZip(tmpZip, platformDir);
            } finally {
                Files.deleteIfExists(tmpZip);
            }
        } catch (Exception ex) {
            throw new UnsatisfiedLinkError("Failed to download Lavaplayer natives: " + ex.getMessage());
        }
    }

    private static JsonObject downloadIndex() throws IOException {
        URL url = URI.create(NATIVES_INDEX_URL).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "MediaRadio");
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new IOException("Index request failed with HTTP " + code);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void downloadFile(URL url, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "MediaRadio");
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new IOException("Download failed with HTTP " + code);
        }
        try (InputStream input = connection.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void extractZip(Path zipPath, Path outputDir) throws IOException {
        try (InputStream input = Files.newInputStream(zipPath, StandardOpenOption.READ);
             ZipInputStream zipInput = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zipInput.closeEntry();
                    continue;
                }
                String name = entry.getName();
                if (name.contains("__MACOSX") || name.startsWith("._")) {
                    zipInput.closeEntry();
                    continue;
                }
                Path out = outputDir.resolve(name);
                Files.createDirectories(out.getParent());
                Files.copy(zipInput, out, StandardCopyOption.REPLACE_EXISTING);
                if (isUnixLike() && (name.endsWith(".so") || name.endsWith(".dylib"))) {
                    out.toFile().setExecutable(true, false);
                }
                zipInput.closeEntry();
            }
        }
    }

    private static boolean isUnixLike() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("linux") || os.contains("mac");
    }

    private static String releasePlatformKey(SystemType system) {
        if (system.osType == DefaultOperatingSystemTypes.DARWIN) {
            return "darwin";
        }
        if (system.osType == DefaultOperatingSystemTypes.WINDOWS) {
            return "win-" + normalizeArchitecture(system.architectureType.identifier());
        }
        if (system.osType == DefaultOperatingSystemTypes.LINUX) {
            return "linux-" + normalizeArchitecture(system.architectureType.identifier());
        }
        throw new IllegalStateException("Unsupported OS for Lavaplayer natives: " + system.osType.identifier());
    }

    private static String normalizeArchitecture(String arch) {
        String normalized = arch.toLowerCase();
        if ("arm64".equals(normalized)) {
            return "aarch64";
        }
        if ("x86_64".equals(normalized)) {
            return "x86-64";
        }
        return normalized;
    }

    private static SystemType detectSystemType(NativeLibraryProperties properties, Predicate<SystemType> systemFilter) {
        SystemType system;
        try {
            system = SystemType.detect(properties);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        if (systemFilter != null && !systemFilter.test(system)) {
            return null;
        }
        return system;
    }
}
