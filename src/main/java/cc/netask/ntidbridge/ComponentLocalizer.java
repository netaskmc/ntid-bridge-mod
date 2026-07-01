package cc.netask.ntidbridge;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ComponentLocalizer {
    private static final Pattern FORMAT_PATTERN = Pattern.compile("%(?:(\\d+)\\$)?s|%%");
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
            return resourceTranslations(server.getResourceManager(), languageCode);
        }

        return LANGUAGES.computeIfAbsent(languageCode, ComponentLocalizer::loadTranslations);
    }

    private static Map<String, String> resourceTranslations(ResourceManager resourceManager, String languageCode) {
        if (cachedResourceManager != resourceManager) {
            synchronized (ComponentLocalizer.class) {
                if (cachedResourceManager != resourceManager) {
                    resourceLanguages = new ConcurrentHashMap<>();
                    cachedResourceManager = resourceManager;
                }
            }
        }

        return resourceLanguages.computeIfAbsent(languageCode, key -> loadTranslations(resourceManager, key));
    }

    private static Map<String, String> loadTranslations(ResourceManager resourceManager, String languageCode) {
        Map<String, String> translations = new HashMap<>();
        loadLanguageFile(resourceManager, translations, "en_us");
        if (!"en_us".equals(languageCode)) {
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

    private static String normalizeLanguage(String languageCode) {
        String normalized = languageCode == null ? "" : languageCode.trim().toLowerCase();
        if ("ru".equals(normalized)) {
            return "ru_ru";
        }
        return normalized;
    }
}
