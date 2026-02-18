package net.jacobwasbeast.mediaradio.client.render;

import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.media.PlaybackDisplayResolver;
import net.jacobwasbeast.mediaradio.client.media.ThumbnailTextureManager;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;

public final class RadioHeldOverlayRenderer {

    private static final int PANEL_MIN_W = 168;
    private static final int PANEL_H = 62;
    private static final int THUMB = 42;
    private static final int PANEL_MARGIN = 8;
    private static final int PANEL_INNER_PADDING = 6;
    private static final int TEXT_GAP = 6;

    private RadioHeldOverlayRenderer() {
    }

    public static void render(GuiGraphics guiGraphics, Minecraft minecraft) {
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }

        var main = minecraft.player.getMainHandItem();
        var off = minecraft.player.getOffhandItem();
        boolean mainHandShowsOverlay = main.is(ModItems.RADIO_ITEM) && !RadioItem.isPlaceMode(main);
        boolean offHandShowsOverlay = off.is(ModItems.RADIO_ITEM) && !RadioItem.isPlaceMode(off);
        if (!mainHandShowsOverlay && !offHandShowsOverlay) {
            return;
        }

        ClientAudioEngine audio = ClientAudioEngine.getInstance();
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        String mainRadioId = mainHandShowsOverlay ? RadioItem.getRadioId(main) : "";
        String offRadioId = offHandShowsOverlay ? RadioItem.getRadioId(off) : "";
        String activeRadioId = repository.getActiveRadioId();
        String radioIdForQueue = "";
        if (!activeRadioId.isBlank()) {
            if (activeRadioId.equals(mainRadioId)) {
                radioIdForQueue = mainRadioId;
            } else if (activeRadioId.equals(offRadioId)) {
                radioIdForQueue = offRadioId;
            }
        }
        if (radioIdForQueue.isBlank()) {
            radioIdForQueue = !mainRadioId.isBlank() ? mainRadioId : offRadioId;
        }

        var queueEntry = radioIdForQueue.isBlank() ? null : repository.getCurrentQueueEntryForRadioId(radioIdForQueue);
        PlaybackDisplayResolver.DisplayInfo displayInfo = PlaybackDisplayResolver.resolve(
                audio.getHandheldUrl(),
                audio.getHandheldNowPlaying(),
                audio.getHandheldArtist(),
                audio.getHandheldThumbnail(),
                queueEntry
        );

        String title = displayInfo.title();
        if (title == null || title.isBlank()) {
            title = "Nothing Playing";
        }

        String state = audio.isHandheldPlaying() ? "Playing" : (audio.isHandheldPaused() ? "Paused" : "Stopped");
        String artist = displayInfo.artist();
        if (artist == null || artist.isBlank()) {
            artist = "Unknown Artist";
        }
        long pos = Math.max(0L, audio.getHandheldPlaybackPositionMs());
        long dur = audio.getHandheldTrackDurationMs();
        String time = dur > 0L ? (formatTime(pos) + " / " + formatTime(dur)) : formatTime(pos);
        Font font = minecraft.font;

        int panelMaxW = Math.max(PANEL_MIN_W, guiGraphics.guiWidth() - (PANEL_MARGIN * 2));
        int desiredTextW = Math.max(
                Math.max(font.width(state), font.width(title)),
                Math.max(font.width(artist), font.width(time))
        );
        int panelW = Math.min(
                panelMaxW,
                Math.max(PANEL_MIN_W, (PANEL_INNER_PADDING * 2) + THUMB + TEXT_GAP + desiredTextW)
        );
        int x = guiGraphics.guiWidth() - panelW - PANEL_MARGIN;
        int y = PANEL_MARGIN;
        guiGraphics.fill(x, y, x + panelW, y + PANEL_H, 0xC0101720);
        guiGraphics.fill(x, y, x + panelW, y + 1, 0xFF4F7390);
        guiGraphics.fill(x, y + PANEL_H - 1, x + panelW, y + PANEL_H, 0xFF4F7390);
        guiGraphics.fill(x, y, x + 1, y + PANEL_H, 0xFF4F7390);
        guiGraphics.fill(x + panelW - 1, y, x + panelW, y + PANEL_H, 0xFF4F7390);

        drawThumb(guiGraphics, displayInfo.thumbnail(), x + PANEL_INNER_PADDING, y + 10, THUMB);

        int tx = x + PANEL_INNER_PADDING + THUMB + TEXT_GAP;
        int textMaxWidth = Math.max(8, (x + panelW - PANEL_INNER_PADDING) - tx);
        guiGraphics.enableScissor(tx, y + 8, x + panelW - PANEL_INNER_PADDING, y + PANEL_H - 4);
        guiGraphics.drawString(font, trimToWidth(font, state, textMaxWidth), tx, y + 10, 0xFF69E3A3, false);
        guiGraphics.drawString(font, trimToWidth(font, title, textMaxWidth), tx, y + 22, 0xFFEAF4FF, false);
        guiGraphics.drawString(font, trimToWidth(font, artist, textMaxWidth), tx, y + 33, 0xFFA7BFD3, false);
        guiGraphics.drawString(font, trimToWidth(font, time, textMaxWidth), tx, y + 45, 0xFF69CFFF, false);
        guiGraphics.disableScissor();
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

    private static String trimToWidth(Font font, String value, int maxWidth) {
        if (font == null || value == null || value.isBlank() || maxWidth <= 0) {
            return value == null ? "" : value;
        }
        if (font.width(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        if (ellipsisWidth >= maxWidth) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            builder.append(c);
            if (font.width(builder.toString()) + ellipsisWidth > maxWidth) {
                builder.setLength(Math.max(0, builder.length() - 1));
                break;
            }
        }
        return builder + ellipsis;
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
