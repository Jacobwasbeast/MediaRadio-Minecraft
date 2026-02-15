package net.jacobwasbeast.mediaradio.client.render;

import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.jacobwasbeast.mediaradio.client.media.ThumbnailTextureManager;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;

public final class RadioHeldOverlayRenderer {

    private static final int PANEL_W = 168;
    private static final int PANEL_H = 62;
    private static final int THUMB = 42;

    private RadioHeldOverlayRenderer() {
    }

    public static void render(GuiGraphics guiGraphics, Minecraft minecraft) {
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        boolean holdingRadio = minecraft.player.getMainHandItem().is(ModItems.RADIO_ITEM)
                || minecraft.player.getOffhandItem().is(ModItems.RADIO_ITEM);
        if (!holdingRadio) {
            return;
        }

        ClientAudioEngine audio = ClientAudioEngine.getInstance();
        String title = audio.getHandheldNowPlaying();
        if (title == null || title.isBlank()) {
            title = "Nothing Playing";
        }

        String state = audio.isHandheldPlaying() ? "Playing" : (audio.isHandheldPaused() ? "Paused" : "Stopped");
        String artist = audio.getHandheldArtist();
        if (artist == null || artist.isBlank()) {
            artist = "Unknown Artist";
        }
        long pos = Math.max(0L, audio.getHandheldPlaybackPositionMs());
        long dur = audio.getHandheldTrackDurationMs();
        String time = dur > 0L ? (formatTime(pos) + " / " + formatTime(dur)) : formatTime(pos);

        int x = guiGraphics.guiWidth() - PANEL_W - 8;
        int y = 8;
        guiGraphics.fill(x, y, x + PANEL_W, y + PANEL_H, 0xC0101720);
        guiGraphics.fill(x, y, x + PANEL_W, y + 1, 0xFF4F7390);
        guiGraphics.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, 0xFF4F7390);
        guiGraphics.fill(x, y, x + 1, y + PANEL_H, 0xFF4F7390);
        guiGraphics.fill(x + PANEL_W - 1, y, x + PANEL_W, y + PANEL_H, 0xFF4F7390);

        drawThumb(guiGraphics, audio.getHandheldThumbnail(), x + 6, y + 10, THUMB);

        Font font = minecraft.font;
        int tx = x + 6 + THUMB + 6;
        guiGraphics.drawString(font, state, tx, y + 10, 0xFF69E3A3, false);
        guiGraphics.drawString(font, trim(title, 24), tx, y + 22, 0xFFEAF4FF, false);
        guiGraphics.drawString(font, trim(artist, 24), tx, y + 33, 0xFFA7BFD3, false);
        guiGraphics.drawString(font, time, tx, y + 45, 0xFF69CFFF, false);
    }

    private static void drawThumb(GuiGraphics guiGraphics, String url, int x, int y, int size) {
        guiGraphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x884A6E8B);
        guiGraphics.fill(x, y, x + size, y + size, 0xCC0E1822);

        var handle = ThumbnailTextureManager.getInstance().getTexture(url);
        if (handle.location().equals(MissingTextureAtlasSprite.getLocation())) {
            return;
        }

        int sourceWidth = Math.max(1, handle.width());
        int sourceHeight = Math.max(1, handle.height());
        float scale = Math.max(size / (float) sourceWidth, size / (float) sourceHeight);
        int drawW = Math.round(sourceWidth * scale);
        int drawH = Math.round(sourceHeight * scale);
        int drawX = x + (size - drawW) / 2;
        int drawY = y + (size - drawH) / 2;
        guiGraphics.enableScissor(x, y, x + size, y + size);
        guiGraphics.blit(handle.location(), drawX, drawY, drawW, drawH, 0f, 0f, sourceWidth, sourceHeight, sourceWidth, sourceHeight);
        guiGraphics.disableScissor();
    }

    private static String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String formatTime(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
}

