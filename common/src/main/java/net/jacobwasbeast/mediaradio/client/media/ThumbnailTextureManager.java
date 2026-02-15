package net.jacobwasbeast.mediaradio.client.media;

import com.mojang.blaze3d.platform.NativeImage;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class ThumbnailTextureManager {

    private static final ThumbnailTextureManager INSTANCE = new ThumbnailTextureManager();
    private static final TextureHandle FALLBACK = new TextureHandle(MissingTextureAtlasSprite.getLocation(), 16, 16);
    private static final long RETRY_AFTER_FAILURE_MS = 15_000L;

    private final Map<String, TextureHandle> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> failedLoads = new ConcurrentHashMap<>();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private int index;

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MediaRadio-Thumbnail-" + index++);
            thread.setDaemon(true);
            return thread;
        }
    });

    private ThumbnailTextureManager() {
    }

    public static ThumbnailTextureManager getInstance() {
        return INSTANCE;
    }

    public TextureHandle getTexture(String url) {
        String safeUrl = safe(url);
        if (safeUrl.isBlank()) {
            return FALLBACK;
        }

        TextureHandle existing = cache.get(safeUrl);
        if (existing != null) {
            if (existing.location().equals(MissingTextureAtlasSprite.getLocation()) && shouldRetry(safeUrl)) {
                requestLoad(safeUrl);
            }
            return existing;
        }

        requestLoad(safeUrl);
        return FALLBACK;
    }

    private void requestLoad(String url) {
        if (!isHttp(url) || !loading.add(url)) {
            return;
        }

        ioExecutor.submit(() -> {
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            try {
                URLConnection rawConnection = new URL(url).openConnection();
                rawConnection.setConnectTimeout(5000);
                rawConnection.setReadTimeout(10000);
                rawConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (MediaRadio Thumbnail Loader)");
                rawConnection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                if (rawConnection instanceof HttpURLConnection httpURLConnection) {
                    httpURLConnection.setInstanceFollowRedirects(true);
                    connection = httpURLConnection;
                }

                inputStream = rawConnection.getInputStream();
                NativeImage image = NativeImage.read(inputStream);
                inputStream.close();
                inputStream = null;
                if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                    failedLoads.put(url, System.currentTimeMillis());
                    cache.put(url, FALLBACK);
                    return;
                }

                int width = image.getWidth();
                int height = image.getHeight();
                DynamicTexture dynamicTexture = new DynamicTexture(image);
                ResourceLocation id = MediaRadio.id("thumbnail/" + sha1(url));

                Minecraft minecraft = Minecraft.getInstance();
                minecraft.execute(() -> {
                    minecraft.getTextureManager().register(id, dynamicTexture);
                    cache.put(url, new TextureHandle(id, width, height));
                    failedLoads.remove(url);
                });
            } catch (Exception ignored) {
                failedLoads.put(url, System.currentTimeMillis());
                cache.put(url, FALLBACK);
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Exception ignored) {
                    }
                }
                if (connection != null) {
                    connection.disconnect();
                }
                loading.remove(url);
            }
        });
    }

    private boolean shouldRetry(String url) {
        Long failedAt = failedLoads.get(url);
        return failedAt == null || (System.currentTimeMillis() - failedAt) >= RETRY_AFTER_FAILURE_MS;
    }

    private static boolean isHttp(String url) {
        try {
            String scheme = URI.create(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception exception) {
            return false;
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record TextureHandle(ResourceLocation location, int width, int height) {
    }
}
