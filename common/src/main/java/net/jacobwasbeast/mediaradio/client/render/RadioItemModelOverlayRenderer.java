package net.jacobwasbeast.mediaradio.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.media.MediaMetadataResolver;
import net.jacobwasbeast.mediaradio.client.media.PlaybackDisplayResolver;
import net.jacobwasbeast.mediaradio.client.media.ThumbnailTextureManager;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RadioItemModelOverlayRenderer {
    private static final int PANEL_WIDTH = 96;
    private static final int PANEL_HEIGHT = 78;
    private static final int LINE_HEIGHT = 10;
    private static final int PANEL_PADDING_X = 4;
    private static final int PANEL_PADDING_Y = 3;
    private static final int PANEL_BG_COLOR = 0xD0101318;
    private static final int THUMB_GAP = 3;
    private static final int TOP_ROW_HEIGHT = 26;
    private static final int THUMB_BOX_WIDTH = 34;

    private static final float DISPLAY_MIN_X = 9.25f / 16f;
    private static final float DISPLAY_MAX_X = 13.95f / 16f;
    private static final float DISPLAY_MIN_Y = 4.45f / 16f;
    private static final float DISPLAY_MAX_Y = 8.35f / 16f;
    private static final float DISPLAY_Z = (2.35f / 16f) - 0.0012f;

    private RadioItemModelOverlayRenderer() {
    }

    public static void render(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (!stack.is(ModItems.RADIO_ITEM) || RadioItem.isPlaceMode(stack)) {
            return;
        }
        if (displayContext == ItemDisplayContext.GUI || displayContext == ItemDisplayContext.HEAD) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        if (font == null) {
            return;
        }

        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        String radioId = RadioItem.getRadioId(stack);
        SharedMediaSnapshot.MediaEntry queueEntry = null;
        if (radioId != null && !radioId.isBlank()) {
            queueEntry = repository.getCurrentQueueEntryForRadioId(radioId);
        }

        ClientAudioEngine.HandheldRenderState runtime = ClientAudioEngine.getInstance().getRenderStateForRadioId(radioId);

        String sourceUrl = runtime != null && !runtime.url().isBlank() ? runtime.url() : RadioItem.getSavedUrl(stack);
        String sourceTitle = runtime != null && !runtime.title().isBlank() ? runtime.title() : RadioItem.getSavedTitle(stack);
        String sourceArtist = runtime != null && !runtime.artist().isBlank() ? runtime.artist() : RadioItem.getSavedArtist(stack);
        String sourceThumbnail = runtime != null && !runtime.thumbnail().isBlank() ? runtime.thumbnail() : RadioItem.getSavedThumbnail(stack);

        PlaybackDisplayResolver.DisplayInfo displayInfo = PlaybackDisplayResolver.resolve(
                sourceUrl,
                sourceTitle,
                sourceArtist,
                sourceThumbnail,
                queueEntry
        );

        String title = safe(displayInfo.title(), "No Media");
        String artist = safe(displayInfo.artist(), "Unknown Artist");
        boolean playing = runtime != null && runtime.playing();
        String status = playing ? "PLAY" : "PAUSE";
        long position = runtime != null ? runtime.positionMs() : RadioItem.getSavedPositionMs(stack);
        float volume = runtime != null ? runtime.volume() : RadioItem.getSavedVolume(stack);
        String clockAndVolume = formatTime(position) + " " + (int) (Math.max(0f, Math.min(2f, volume)) * 100f) + "%";
        String thumbnailUrl = MediaMetadataResolver.bestThumbnail(displayInfo.thumbnail(), sourceUrl);
        var thumbnailTexture = ThumbnailTextureManager.getInstance().getTexture(thumbnailUrl);

        int panelLeft = -(PANEL_WIDTH / 2);
        int panelTop = -(PANEL_HEIGHT / 2);
        int innerLeft = panelLeft + PANEL_PADDING_X;
        int innerRight = panelLeft + PANEL_WIDTH - PANEL_PADDING_X;
        int innerTop = panelTop + PANEL_PADDING_Y;
        int innerBottom = panelTop + PANEL_HEIGHT - PANEL_PADDING_Y;

        int thumbX = innerLeft;
        int thumbY = innerTop;
        int thumbW = THUMB_BOX_WIDTH;
        int thumbH = TOP_ROW_HEIGHT;

        int topTextX = thumbX + thumbW + THUMB_GAP;
        int topTextWrap = Math.max(16, innerRight - topTextX);

        int infoStartY = innerTop + TOP_ROW_HEIGHT + THUMB_GAP;
        int infoWrap = Math.max(20, innerRight - innerLeft);
        int infoMaxLines = Math.max(1, (innerBottom - infoStartY) / LINE_HEIGHT);

        List<DisplayLine> infoLines = new ArrayList<>();
        addWrapped(infoLines, font, title, infoWrap, 0xF6F7F8, 2);
        addWrapped(infoLines, font, artist, infoWrap, 0xB9CBD6, 1);
        while (infoLines.size() > infoMaxLines) {
            infoLines.remove(infoLines.size() - 1);
        }

        float centerX = (DISPLAY_MIN_X + DISPLAY_MAX_X) * 0.5f;
        float centerY = (DISPLAY_MIN_Y + DISPLAY_MAX_Y) * 0.5f;
        float scaleX = (DISPLAY_MAX_X - DISPLAY_MIN_X) / PANEL_WIDTH;
        float scaleY = (DISPLAY_MAX_Y - DISPLAY_MIN_Y) / PANEL_HEIGHT;

        poseStack.pushPose();
        poseStack.translate(centerX, centerY, DISPLAY_Z);
        // Flip 180 degrees so the panel faces outward from the radio item.
        poseStack.mulPose(Axis.YP.rotationDegrees(180f));
        poseStack.scale(scaleX, -scaleY, scaleX);

        drawPanel(poseStack, bufferSource, panelLeft, panelTop, PANEL_WIDTH, PANEL_HEIGHT);
        drawThumbnail(poseStack, bufferSource, font, thumbnailTexture, thumbX, thumbY, thumbW, thumbH, packedLight);
        drawSingleLine(poseStack, bufferSource, font, status, topTextX, innerTop, 0xFF66C7, packedLight, topTextWrap);
        drawSingleLine(poseStack, bufferSource, font, clockAndVolume, topTextX, innerTop + LINE_HEIGHT, 0x82F1C3, packedLight, topTextWrap);
        drawLines(poseStack, bufferSource, font, infoLines, innerLeft, infoStartY, packedLight);

        poseStack.popPose();
    }

    private static void addWrapped(List<DisplayLine> output, Font font, String text, int wrapWidth, int color, int maxLines) {
        List<FormattedCharSequence> wrapped = font.split(Component.literal(text), Math.max(12, wrapWidth));
        if (wrapped.isEmpty()) {
            wrapped = List.of(FormattedCharSequence.EMPTY);
        }
        int limit = Math.max(1, Math.min(maxLines, wrapped.size()));
        for (int i = 0; i < limit; i++) {
            output.add(new DisplayLine(wrapped.get(i), color));
        }
    }

    private static void drawSingleLine(PoseStack poseStack, MultiBufferSource bufferSource, Font font, String value, int x, int y, int color, int packedLight, int wrapWidth) {
        List<FormattedCharSequence> wrapped = font.split(Component.literal(value), Math.max(12, wrapWidth));
        FormattedCharSequence line = wrapped.isEmpty() ? FormattedCharSequence.EMPTY : wrapped.get(0);
        font.drawInBatch(line, x, y, color, true, poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, packedLight);
    }

    private static void drawLines(PoseStack poseStack, MultiBufferSource bufferSource, Font font, List<DisplayLine> lines, int textX, int startY, int packedLight) {
        for (int i = 0; i < lines.size(); i++) {
            DisplayLine line = lines.get(i);
            float y = startY + (i * LINE_HEIGHT);
            font.drawInBatch(line.text(), textX, y, line.color(), true, poseStack.last().pose(), bufferSource, Font.DisplayMode.SEE_THROUGH, 0, packedLight);
        }
    }

    private static void drawPanel(PoseStack poseStack, MultiBufferSource bufferSource, int x, int y, int width, int height) {
        VertexConsumer consumer = bufferSource.getBuffer(RenderType.debugQuads());
        var matrix = poseStack.last().pose();
        int a = (PANEL_BG_COLOR >> 24) & 0xFF;
        int r = (PANEL_BG_COLOR >> 16) & 0xFF;
        int g = (PANEL_BG_COLOR >> 8) & 0xFF;
        int b = PANEL_BG_COLOR & 0xFF;

        float z = 0.0f;
        consumer.vertex(matrix, x, y + height, z).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x + width, y + height, z).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x + width, y, z).color(r, g, b, a).endVertex();
        consumer.vertex(matrix, x, y, z).color(r, g, b, a).endVertex();
    }

    private static void drawThumbnail(PoseStack poseStack, MultiBufferSource bufferSource, Font font, ThumbnailTextureManager.TextureHandle texture, int x, int y, int width, int height, int packedLight) {
        if (texture == null || texture.location() == null) {
            drawMissingThumbnailPlaceholder(poseStack, bufferSource, font, x, y, width, height, packedLight);
            return;
        }
        if (width <= 0 || height <= 0) {
            return;
        }
        if (texture.location().equals(MissingTextureAtlasSprite.getLocation())) {
            drawMissingThumbnailPlaceholder(poseStack, bufferSource, font, x, y, width, height, packedLight);
            return;
        }

        int sourceWidth = Math.max(1, texture.width());
        int sourceHeight = Math.max(1, texture.height());
        float scale = Math.min(width / (float) sourceWidth, height / (float) sourceHeight);
        int drawW = Math.max(1, Math.round(sourceWidth * scale));
        int drawH = Math.max(1, Math.round(sourceHeight * scale));
        int drawX = x;
        int drawY = y;

        float left = drawX;
        float top = drawY;
        float right = drawX + drawW;
        float bottom = drawY + drawH;

        ResourceLocation target = texture.location();
        RenderType renderType = RenderType.entityCutoutNoCull(target);
        VertexConsumer consumer = bufferSource.getBuffer(renderType);
        var matrix = poseStack.last().pose();
        var normal = poseStack.last().normal();
        float z = 0.01f;

        consumer.vertex(matrix, left, bottom, z).color(255, 255, 255, 255).uv(0f, 1f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(normal, 0f, 0f, 1f).endVertex();
        consumer.vertex(matrix, right, bottom, z).color(255, 255, 255, 255).uv(1f, 1f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(normal, 0f, 0f, 1f).endVertex();
        consumer.vertex(matrix, right, top, z).color(255, 255, 255, 255).uv(1f, 0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(normal, 0f, 0f, 1f).endVertex();
        consumer.vertex(matrix, left, top, z).color(255, 255, 255, 255).uv(0f, 0f).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(packedLight).normal(normal, 0f, 0f, 1f).endVertex();
    }

    private static void drawMissingThumbnailPlaceholder(PoseStack poseStack, MultiBufferSource bufferSource, Font font, int x, int y, int width, int height, int packedLight) {
        int centerX = x + (width / 2);
        int centerY = y + (height / 2) - 4;
        String marker = "?";
        int markerX = centerX - (font.width(marker) / 2);
        font.drawInBatch(
                marker,
                markerX,
                centerY,
                0xFF9FB4C4,
                false,
                poseStack.last().pose(),
                bufferSource,
                Font.DisplayMode.SEE_THROUGH,
                0,
                packedLight
        );
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private record DisplayLine(FormattedCharSequence text, int color) {
    }
}
