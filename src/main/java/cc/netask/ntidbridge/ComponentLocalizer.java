package cc.netask.ntidbridge;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ComponentLocalizer {
    private static final String VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String ASSET_DOWNLOAD_BASE_URL = "https://resources.download.minecraft.net/";
    private static final Pattern FORMAT_PATTERN = Pattern.compile("%(?:(\\d+)\\$)?s|%%");
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Map<String, Map<String, String>> LANGUAGES = new ConcurrentHashMap<>();
    private static volatile ResourceManager cachedResourceManager;
    private static volatile Map<String, Map<String, String>> resourceLanguages = Collections.emptyMap();

    private ComponentLocalizer() {
    }

    static String localize(Component component, MinecraftServer server, String languageCode) {
        if (component == null) {
            return "";
        }

        String normalizedLanguage = normalizeLanguage(languageCode);
        if (normalizedLanguage.isEmpty()) {
            return component.getString();
        }

        return localizeComponent(component, translations(server, normalizedLanguage));
    }

    private static String localizeComponent(Component component, Map<String, String> translations) {
        StringBuilder result = new StringBuilder(localizeContents(component, translations));
        for (Component sibling : component.getSiblings()) {
            result.append(localizeComponent(sibling, translations));
        }
        return result.toString();
    }

    private static String localizeContents(Component component, Map<String, String> translations) {
        if (component.getContents() instanceof PlainTextContents plainText) {
            return plainText.text();
        }

        if (component.getContents() instanceof TranslatableContents translatable) {
            String template = translations.get(translatable.getKey());
            if (template == null) {
                template = translatable.getFallback();
            }
            if (template == null) {
                template = Language.getInstance().getOrDefault(translatable.getKey());
            }

            return format(template, translatable.getArgs(), translations);
        }

        return component.getString();
    }

    private static String format(String template, Object[] args, Map<String, String> translations) {
        StringBuilder result = new StringBuilder();
        Matcher matcher = FORMAT_PATTERN.matcher(template);
        int previousEnd = 0;
        int nextArgument = 0;

        while (matcher.find()) {
            result.append(template, previousEnd, matcher.start());
            previousEnd = matcher.end();

            if ("%%".equals(matcher.group())) {
                result.append('%');
                continue;
            }

            int argumentIndex = matcher.group(1) == null ? nextArgument++ : Integer.parseInt(matcher.group(1)) - 1;
            if (argumentIndex >= 0 && argumentIndex < args.length) {
                result.append(localizeArgument(args[argumentIndex], translations));
            } else {
                result.append(matcher.group());
            }
        }

        result.append(template, previousEnd, template.length());
        return result.toString();
    }

    private static String localizeArgument(Object argument, Map<String, String> translations) {
        if (argument instanceof Component component) {
            return localizeComponent(component, translations);
        }
        return String.valueOf(argument);
    }

    private static Map<String, String> translations(MinecraftServer server, String languageCode) {
        if (server != null) {
            return resourceTranslations(server, languageCode);
        }

        return LANGUAGES.computeIfAbsent(languageCode, ComponentLocalizer::loadTranslations);
    }

    private static Map<String, String> resourceTranslations(MinecraftServer server, String languageCode) {
        ResourceManager resourceManager = server.getResourceManager();
        if (cachedResourceManager != resourceManager) {
            synchronized (ComponentLocalizer.class) {
                if (cachedResourceManager != resourceManager) {
                    resourceLanguages = new ConcurrentHashMap<>();
                    cachedResourceManager = resourceManager;
                }
            }
        }

        return resourceLanguages.computeIfAbsent(languageCode, key -> loadTranslations(server, key));
    }

    private static Map<String, String> loadTranslations(MinecraftServer server, String languageCode) {
        Map<String, String> translations = new HashMap<>();
        ResourceManager resourceManager = server.getResourceManager();

        loadLanguageFile(translations, "en_us");
        loadMinecraftAssetLanguage(server, translations, "en_us");
        loadLanguageFile(resourceManager, translations, "en_us");
        if (!"en_us".equals(languageCode)) {
            loadLanguageFile(translations, languageCode);
            loadMinecraftAssetLanguage(server, translations, languageCode);
            loadLanguageFile(resourceManager, translations, languageCode);
        }
        return translations;
    }

    private static Map<String, String> loadTranslations(String languageCode) {
        Map<String, String> translations = new HashMap<>();
        loadLanguageFile(translations, "en_us");
        if (!"en_us".equals(languageCode)) {
            loadLanguageFile(translations, languageCode);
        }
        return translations;
    }

    private static void loadLanguageFile(ResourceManager resourceManager, Map<String, String> translations, String languageCode) {
        String languagePath = "lang/" + languageCode + ".json";
        for (String namespace : resourceManager.getNamespaces()) {
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(namespace, languagePath);
            List<Resource> resources = resourceManager.getResourceStack(location);
            for (Resource resource : resources) {
                try (InputStream stream = resource.open()) {
                    Language.loadFromJson(stream, translations::put);
                } catch (IOException error) {
                    NtidBridgeMod.LOGGER.warn("Failed to load language resource {} from {}", location, resource.sourcePackId(), error);
                }
            }
        }
    }

    private static void loadLanguageFile(Map<String, String> translations, String languageCode) {
        loadLanguageResource(translations, "assets/minecraft/lang/" + languageCode + ".json");
        loadLanguageResource(translations, "assets/neoforge/lang/" + languageCode + ".json");
    }

    private static void loadLanguageResource(Map<String, String> translations, String resourcePath) {
        ClassLoader classLoader = ComponentLocalizer.class.getClassLoader();
        try (InputStream stream = classLoader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return;
            }

            Language.loadFromJson(stream, translations::put);
        } catch (IOException error) {
            NtidBridgeMod.LOGGER.warn("Failed to load language resource {}", resourcePath, error);
        }
    }

    private static void loadMinecraftAssetLanguage(MinecraftServer server, Map<String, String> translations, String languageCode) {
        String assetPath = "minecraft/lang/" + languageCode + ".json";
        for (Path assetRoot : minecraftAssetRoots(server)) {
            Path languageFile = findAssetObject(assetRoot, assetPath);
            if (languageFile == null) {
                continue;
            }

            try (InputStream stream = Files.newInputStream(languageFile)) {
                Language.loadFromJson(stream, translations::put);
                return;
            } catch (IOException error) {
                NtidBridgeMod.LOGGER.warn("Failed to load Minecraft asset language {} from {}", languageCode, languageFile, error);
            }
        }

        if (BridgeConfig.DOWNLOAD_MINECRAFT_LOCALIZATION.get()) {
            downloadMinecraftAssetLanguage(server, translations, languageCode);
        }
    }

    private static Set<Path> minecraftAssetRoots(MinecraftServer server) {
        Set<Path> roots = new LinkedHashSet<>();
        String configured = BridgeConfig.MINECRAFT_ASSETS_DIRECTORY.get();
        if (!configured.isBlank()) {
            roots.add(Paths.get(configured));
        }

        roots.add(server.getServerDirectory().resolve("assets"));

        String userHome = System.getProperty("user.home", "");
        if (!userHome.isBlank()) {
            roots.add(Paths.get(userHome, ".minecraft", "assets"));
            roots.add(Paths.get(userHome, ".gradle", "caches", "neoformruntime", "assets"));
        }

        return roots;
    }

    private static void downloadMinecraftAssetLanguage(MinecraftServer server, Map<String, String> translations, String languageCode) {
        try {
            Path assetRoot = server.getServerDirectory().resolve("ntid_bridge").resolve("assets");
            Files.createDirectories(assetRoot.resolve("indexes"));
            Files.createDirectories(assetRoot.resolve("objects"));

            String assetPath = "minecraft/lang/" + languageCode + ".json";
            Path indexFile = downloadAssetIndex(assetRoot);
            Path languageFile = findAssetObject(indexFile, assetRoot.resolve("objects"), assetPath);
            if (languageFile == null) {
                languageFile = downloadAssetObject(indexFile, assetRoot.resolve("objects"), assetPath);
            }
            if (languageFile == null) {
                NtidBridgeMod.LOGGER.warn("Minecraft asset index did not contain {}", assetPath);
                return;
            }

            try (InputStream stream = Files.newInputStream(languageFile)) {
                Language.loadFromJson(stream, translations::put);
                NtidBridgeMod.LOGGER.info("Loaded Minecraft {} localization from {}", languageCode, languageFile);
            }
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            NtidBridgeMod.LOGGER.warn("Failed to download Minecraft {} localization", languageCode, error);
        }
    }

    private static Path downloadAssetIndex(Path assetRoot) throws IOException, InterruptedException {
        String minecraftVersion = net.minecraft.SharedConstants.getCurrentVersion().getName();
        Path versionFile = assetRoot.resolve("version-" + minecraftVersion + ".json");
        Path indexesDirectory = assetRoot.resolve("indexes");

        if (!Files.isRegularFile(versionFile)) {
            JsonObject manifest = getJson(VERSION_MANIFEST_URL);
            String versionUrl = null;
            for (var versionElement : manifest.getAsJsonArray("versions")) {
                JsonObject version = versionElement.getAsJsonObject();
                if (minecraftVersion.equals(version.get("id").getAsString())) {
                    versionUrl = version.get("url").getAsString();
                    break;
                }
            }
            if (versionUrl == null) {
                throw new IOException("Version manifest did not include Minecraft " + minecraftVersion);
            }

            downloadTo(versionUrl, versionFile);
        }

        JsonObject version = readJson(versionFile);
        JsonObject assetIndex = version.getAsJsonObject("assetIndex");
        String assetIndexId = assetIndex.get("id").getAsString();
        String assetIndexUrl = assetIndex.get("url").getAsString();
        Path indexFile = indexesDirectory.resolve(assetIndexId + ".json");
        if (!Files.isRegularFile(indexFile)) {
            downloadTo(assetIndexUrl, indexFile);
        }

        return indexFile;
    }

    private static Path downloadAssetObject(Path indexFile, Path objects, String assetPath) throws IOException, InterruptedException {
        JsonObject root = readJson(indexFile);
        JsonObject object = root.getAsJsonObject("objects").getAsJsonObject(assetPath);
        if (object == null) {
            return null;
        }

        String hash = object.get("hash").getAsString();
        Path path = objects.resolve(hash.substring(0, 2)).resolve(hash);
        if (!Files.isRegularFile(path)) {
            Files.createDirectories(path.getParent());
            downloadTo(ASSET_DOWNLOAD_BASE_URL + hash.substring(0, 2) + "/" + hash, path);
        }

        return path;
    }

    private static Path findAssetObject(Path assetRoot, String assetPath) {
        Path indexes = assetRoot.resolve("indexes");
        Path objects = assetRoot.resolve("objects");
        if (!Files.isDirectory(indexes) || !Files.isDirectory(objects)) {
            return null;
        }

        try (var indexFiles = Files.list(indexes)) {
            for (Path indexFile : indexFiles.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                Path object = findAssetObject(indexFile, objects, assetPath);
                if (object != null) {
                    return object;
                }
            }
        } catch (IOException error) {
            NtidBridgeMod.LOGGER.warn("Failed to inspect Minecraft asset indexes in {}", indexes, error);
        }

        return null;
    }

    private static Path findAssetObject(Path indexFile, Path objects, String assetPath) {
        try (Reader reader = Files.newBufferedReader(indexFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject object = root.getAsJsonObject("objects").getAsJsonObject(assetPath);
            if (object == null) {
                return null;
            }

            String hash = object.get("hash").getAsString();
            Path path = objects.resolve(hash.substring(0, 2)).resolve(hash);
            return Files.isRegularFile(path) ? path : null;
        } catch (RuntimeException | IOException error) {
            NtidBridgeMod.LOGGER.warn("Failed to inspect Minecraft asset index {}", indexFile, error);
            return null;
        }
    }

    private static JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static JsonObject readJson(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void downloadTo(String url, Path path) throws IOException, InterruptedException {
        Files.createDirectories(path.getParent());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<Path> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofFile(path));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(path);
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode());
        }
    }

    private static String normalizeLanguage(String languageCode) {
        String normalized = languageCode == null ? "" : languageCode.trim().toLowerCase();
        if ("ru".equals(normalized)) {
            return "ru_ru";
        }
        return normalized;
    }
}
