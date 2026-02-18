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

import java.util.ArrayList;
import java.util.List;

public final class RadioHeldOverlayRenderer {

    private static final int PANEL_MIN_W = 168;
    private static final int PANEL_MIN_H = 62;
    private static final int THUMB = 42;
    private static final int PANEL_MARGIN = 8;
    private static final int PANEL_INNER_PADDING = 6;
    private static final int TEXT_GAP = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int TEXT_MAX_WIDTH = 150;
    private static final int TITLE_MAX_LINES = 3;
    private static final int ARTIST_MAX_LINES = 2;

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

        int fixedWidth = (PANEL_INNER_PADDING * 2) + THUMB + TEXT_GAP;
        int textMaxWidth = Math.max(80, Math.min(TEXT_MAX_WIDTH, guiGraphics.guiWidth() - (PANEL_MARGIN * 2) - fixedWidth));
        int textMinWidth = Math.max(80, Math.min(textMaxWidth, 100));
        int desiredTextWidth = Math.max(
                Math.max(font.width(state), font.width(time)),
                Math.max(font.width(title), font.width(artist))
        );
        int textWidth = Math.max(textMinWidth, Math.min(textMaxWidth, desiredTextWidth));
        int panelW = Math.max(PANEL_MIN_W, fixedWidth + textWidth);
        int panelMaxW = Math.max(PANEL_MIN_W, guiGraphics.guiWidth() - (PANEL_MARGIN * 2));
        panelW = Math.min(panelW, panelMaxW);
        textWidth = Math.max(8, panelW - fixedWidth);

        List<String> titleLines = wrapToWidth(font, title, textWidth, TITLE_MAX_LINES);
        List<String> artistLines = wrapToWidth(font, artist, textWidth, ARTIST_MAX_LINES);
        int textLineCount = 2 + titleLines.size() + artistLines.size();
        int contentHeight = Math.max(THUMB, textLineCount * LINE_HEIGHT);
        int panelH = Math.max(PANEL_MIN_H, (PANEL_INNER_PADDING * 2) + contentHeight);
        panelH = Math.min(panelH, Math.max(PANEL_MIN_H, guiGraphics.guiHeight() - (PANEL_MARGIN * 2)));

        int x = guiGraphics.guiWidth() - panelW - PANEL_MARGIN;
        int y = PANEL_MARGIN;
        guiGraphics.fill(x, y, x + panelW, y + panelH, 0xC0101720);
        guiGraphics.fill(x, y, x + panelW, y + 1, 0xFF4F7390);
        guiGraphics.fill(x, y + panelH - 1, x + panelW, y + panelH, 0xFF4F7390);
        guiGraphics.fill(x, y, x + 1, y + panelH, 0xFF4F7390);
        guiGraphics.fill(x + panelW - 1, y, x + panelW, y + panelH, 0xFF4F7390);

        int thumbX = x + PANEL_INNER_PADDING;
        int thumbY = y + PANEL_INNER_PADDING + Math.max(0, (contentHeight - THUMB) / 2);
        drawThumb(guiGraphics, displayInfo.thumbnail(), thumbX, thumbY, THUMB);

        int tx = thumbX + THUMB + TEXT_GAP;
        int ty = y + PANEL_INNER_PADDING;
        int textRight = x + panelW - PANEL_INNER_PADDING;
        guiGraphics.enableScissor(tx, y + PANEL_INNER_PADDING, textRight, y + panelH - PANEL_INNER_PADDING);
        guiGraphics.drawString(font, trimToWidth(font, state, textWidth), tx, ty, 0xFF69E3A3, false);
        ty += LINE_HEIGHT;
        guiGraphics.drawString(font, trimToWidth(font, time, textWidth), tx, ty, 0xFF69CFFF, false);
        ty += LINE_HEIGHT;
        for (String line : titleLines) {
            guiGraphics.drawString(font, line, tx, ty, 0xFFEAF4FF, false);
            ty += LINE_HEIGHT;
        }
        for (String line : artistLines) {
            guiGraphics.drawString(font, line, tx, ty, 0xFFA7BFD3, false);
            ty += LINE_HEIGHT;
        }
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

    private static List<String> wrapToWidth(Font font, String value, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        if (font == null || maxWidth <= 0 || maxLines <= 0) {
            lines.add(value == null ? "" : value);
            return lines;
        }

        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            lines.add("");
            return lines;
        }

        String[] words = normalized.split("\\s+");
        StringBuilder current = new StringBuilder();
        int wordIndex = 0;
        boolean truncated = false;
        while (wordIndex < words.length) {
            String word = words[wordIndex];
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (font.width(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                wordIndex++;
                continue;
            }

            if (current.length() == 0) {
                lines.add(trimToWidth(font, word, maxWidth));
                wordIndex++;
            } else {
                lines.add(current.toString());
                current.setLength(0);
            }

            if (lines.size() >= maxLines) {
                truncated = wordIndex < words.length;
                break;
            }
        }

        if (lines.size() < maxLines && current.length() > 0) {
            lines.add(current.toString());
        } else if (current.length() > 0) {
            truncated = true;
        }

        if (lines.size() > maxLines) {
            lines = new ArrayList<>(lines.subList(0, maxLines));
            truncated = true;
        }

        if (truncated && !lines.isEmpty()) {
            int last = lines.size() - 1;
            lines.set(last, trimToWidth(font, lines.get(last) + "...", maxWidth));
        }
        return lines;
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
