package net.jacobwasbeast.mediaradio.client.screen;

import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.media.MediaMetadataResolver;
import net.jacobwasbeast.mediaradio.client.media.ThumbnailTextureManager;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.network.message.ServerboundHandheldStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;

import java.util.List;
import java.util.Locale;

public class RadioScreen extends Screen {

    private static final int PANEL_WIDTH = 620;
    private static final int PANEL_HEIGHT = 352;
    private static final int HEADER_HEIGHT = 42;
    private static final int PADDING = 14;

    private static final int NOW_LEFT_W = 246;
    private static final int COLUMN_GAP = 12;
    private static final int QUEUE_ROW_HEIGHT = 26;
    private static final int QUEUE_THUMB_SIZE = 22;
    private static final int MEDIA_ROW_HEIGHT = 30;
    private static final int MEDIA_THUMB_SIZE = 24;
    private static final int PLAYLIST_ROW_HEIGHT = 20;

    private static final int COLOR_BG_TOP = 0xEC070A0D;
    private static final int COLOR_BG_BOTTOM = 0xF0010205;
    private static final int COLOR_PANEL = 0xF00E151C;
    private static final int COLOR_PANEL_ALT = 0xE8111A24;
    private static final int COLOR_HEADER = 0xF01A2532;
    private static final int COLOR_CARD = 0xC3121D28;
    private static final int COLOR_CARD_SOFT = 0xB31A2A39;
    private static final int COLOR_STROKE = 0xFF45647D;
    private static final int COLOR_TEXT = 0xFFEAF4FF;
    private static final int COLOR_MUTED = 0xFFA7BFD3;
    private static final int COLOR_ACCENT = 0xFFFFB85B;
    private static final int COLOR_ACCENT_ALT = 0xFF69CFFF;
    private static final int COLOR_GOOD = 0xFF6FE7A4;
    private static final int COLOR_WARN = 0xFFFFC96A;
    private static final int COLOR_BAD = 0xFFFF8B8B;

    private final BlockPos blockPos;
    private final InteractionHand hand;

    private Tab tab = Tab.NOW;

    private EditBox urlInput;
    private EditBox titleInput;
    private EditBox artistInput;
    private EditBox thumbnailInput;
    private EditBox playlistNameInput;

    private int panelX;
    private int panelY;

    private int selectedQueueIndex = -1;
    private int selectedLibraryIndex = -1;
    private int selectedPlaylistIndex = -1;
    private int selectedPlaylistTrackIndex = -1;
    private int queueScroll;
    private int libraryScroll;
    private int playlistScroll;
    private int playlistTrackScroll;

    private String selectedPlaylistId = "";

    private float blockVolume = 1.0f;

    private String draftUrl = "";
    private String draftTitle = "";
    private String draftArtist = "";
    private String draftThumbnail = "";
    private String draftPlaylistName = "";

    private StyledButton pauseResumeButton;
    private StyledButton loopModeButton;
    private boolean timelineDragging;
    private String lastPersistedQueueState = "";
    private String lastPersistedRuntimeKey = "";
    private String lastBoundBlockRadioId = "";

    private RadioScreen(BlockPos blockPos, InteractionHand hand) {
        super(Component.literal("Media Radio"));
        this.blockPos = blockPos;
        this.hand = hand;
    }

    public static RadioScreen forHand(InteractionHand hand) {
        return new RadioScreen(null, hand);
    }

    public static RadioScreen forBlock(BlockPos blockPos) {
        return new RadioScreen(blockPos, null);
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;

        ClientMediaRepository.getInstance().setActiveRadioId(resolveActiveRadioId());
        if (!isBlockMode() && hand != null) {
            ClientAudioEngine.getInstance().setHandheldContext(ClientMediaRepository.getInstance().getActiveRadioId(), hand);
        }

        if (isBlockMode()) {
            RadioBlockEntity blockEntity = getBlockEntity();
            if (blockEntity != null) {
                blockVolume = blockEntity.getVolume();
            }
            syncBlockRadioContext();
        }

        clampSelections();
        rebuildRadioWidgets();
    }

    private String resolveActiveRadioId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (isBlockMode()) {
            RadioBlockEntity blockEntity = getBlockEntity();
            if (blockEntity != null && blockEntity.getRadioId() != null && !blockEntity.getRadioId().isBlank()) {
                return blockEntity.getRadioId();
            }
            return blockContextKey();
        }

        if (minecraft.player == null || hand == null) {
            return "hand:unknown";
        }
        var stack = minecraft.player.getItemInHand(hand);
        if (!stack.is(ModItems.RADIO_ITEM)) {
            return "hand:" + hand.name().toLowerCase(Locale.ROOT);
        }
        String id = RadioItem.getRadioId(stack);
        if (id == null || id.isBlank()) {
            id = RadioItem.getOrCreateRadioId(stack);
        }
        return id;
    }

    @Override
    public void tick() {
        super.tick();

        if (isBlockMode()) {
            RadioBlockEntity blockEntity = getBlockEntity();
            if (blockEntity != null) {
                blockVolume = blockEntity.getVolume();
            }
            syncBlockRadioContext();
        }

        if (urlInput != null) {
            urlInput.tick();
        }
        if (titleInput != null) {
            titleInput.tick();
        }
        if (artistInput != null) {
            artistInput.tick();
        }
        if (thumbnailInput != null) {
            thumbnailInput.tick();
        }
        if (playlistNameInput != null) {
            playlistNameInput.tick();
        }

        clampSelections();
        updatePauseResumeButtonLabel();
        persistRuntimeState();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        persistRuntimeState();
        super.onClose();
    }

    private void rebuildRadioWidgets() {
        captureInputDrafts();
        clearWidgets();

        int tabsY = panelY + 11;
        addRenderableWidget(new StyledButton(panelX + 16, tabsY, 104, 24, Component.literal("Now"), () -> switchTab(Tab.NOW), false, tab == Tab.NOW));
        addRenderableWidget(new StyledButton(panelX + 126, tabsY, 124, 24, Component.literal("Library"), () -> switchTab(Tab.LIBRARY), false, tab == Tab.LIBRARY));
        addRenderableWidget(new StyledButton(panelX + 256, tabsY, 132, 24, Component.literal("Playlists"), () -> switchTab(Tab.PLAYLISTS), false, tab == Tab.PLAYLISTS));
        addRenderableWidget(new StyledButton(panelX + PANEL_WIDTH - 114, tabsY, 96, 24, Component.literal("Close"), this::onClose, true, false));

        switch (tab) {
            case NOW -> buildNowTab();
            case LIBRARY -> buildLibraryTab();
            case PLAYLISTS -> buildPlaylistsTab();
        }
    }

    private void captureInputDrafts() {
        if (urlInput != null) {
            draftUrl = urlInput.getValue();
        }
        if (titleInput != null) {
            draftTitle = titleInput.getValue();
        }
        if (artistInput != null) {
            draftArtist = artistInput.getValue();
        }
        if (thumbnailInput != null) {
            draftThumbnail = thumbnailInput.getValue();
        }
        if (playlistNameInput != null) {
            draftPlaylistName = playlistNameInput.getValue();
        }
    }

    private void switchTab(Tab newTab) {
        tab = newTab;
        rebuildRadioWidgets();
    }

    private void buildNowTab() {
        int leftX = contentX();
        int leftY = contentY();
        int controlsX = leftX + 10;
        int controlsW = NOW_LEFT_W - 20;
        int row1Y = leftY + 126;
        int row2Y = row1Y + 30;
        int buttonGap = 6;
        int sideButtonW = 64;
        int centerButtonW = controlsW - (sideButtonW * 2) - (buttonGap * 2);
        if (centerButtonW < 78) {
            centerButtonW = 78;
            sideButtonW = (controlsW - centerButtonW - (buttonGap * 2)) / 2;
        }

        addRenderableWidget(new StyledButton(controlsX, row1Y, sideButtonW, 22, Component.literal("⏮"), this::playQueuePrevious, false, false));
        pauseResumeButton = new StyledButton(controlsX + sideButtonW + buttonGap, row1Y, centerButtonW, 22, Component.literal("⏸ Pause"), this::togglePause, false, false);
        addRenderableWidget(pauseResumeButton);
        addRenderableWidget(new StyledButton(controlsX + sideButtonW + buttonGap + centerButtonW + buttonGap, row1Y, sideButtonW, 22, Component.literal("⏭"), this::playQueueNext, false, false));

        addRenderableWidget(new StyledButton(controlsX, row2Y, sideButtonW, 22, Component.literal("⏹"), this::stopPlayback, false, false));
        addRenderableWidget(new StyledButton(controlsX + sideButtonW + buttonGap, row2Y, centerButtonW, 22, Component.literal("🔉"), () -> adjustVolume(-0.1f), false, false));
        addRenderableWidget(new StyledButton(controlsX + sideButtonW + buttonGap + centerButtonW + buttonGap, row2Y, sideButtonW, 22, Component.literal("🔊"), () -> adjustVolume(0.1f), false, false));

        int row3Y = row2Y + 30;
        int halfW = (controlsW - buttonGap) / 2;
        addRenderableWidget(new StyledButton(controlsX, row3Y, halfW, 22, Component.literal("🔀 Shuffle"), this::shuffleQueue, false, false));
        loopModeButton = new StyledButton(controlsX + halfW + buttonGap, row3Y, controlsW - halfW - buttonGap, 22, Component.literal("🔁 Loop: All"), this::cycleLoopMode, false, false);
        addRenderableWidget(loopModeButton);

        int rightX = nowRightPanelX();
        int rightW = nowRightPanelW();
        int actionY = nowRightActionY();
        int actionGap = 6;
        int actionW = (rightW - 16 - (actionGap * 3)) / 4;
        int actionX = rightX + 8;

        addRenderableWidget(new StyledButton(actionX, actionY, actionW, 20, Component.literal("▶ Play"), this::playSelectedQueue, false, false));
        addRenderableWidget(new StyledButton(actionX + actionW + actionGap, actionY, actionW, 20, Component.literal("✖ Remove"), this::removeSelectedQueue, false, false));
        addRenderableWidget(new StyledButton(actionX + (actionW + actionGap) * 2, actionY, actionW, 20, Component.literal("↑ Up"), () -> moveSelectedQueue(-1), false, false));
        addRenderableWidget(new StyledButton(actionX + (actionW + actionGap) * 3, actionY, actionW, 20, Component.literal("↓ Down"), () -> moveSelectedQueue(1), false, false));
    }

    private void buildLibraryTab() {
        int x = contentX();
        int y = contentY();
        int w = contentW();

        urlInput = createInput(x + 8, y + 22, w - 16, 20, "Paste URL or source identifier");
        urlInput.setHint(Component.literal("Paste URL or source identifier"));
        urlInput.setValue(draftUrl);
        addRenderableWidget(urlInput);

        int halfGap = 8;
        int halfW = (w - 16 - halfGap) / 2;
        titleInput = createInput(x + 8, y + 48, halfW, 20, "Custom title");
        titleInput.setHint(Component.literal("Custom title"));
        titleInput.setValue(draftTitle);
        addRenderableWidget(titleInput);

        artistInput = createInput(x + 8 + halfW + halfGap, y + 48, halfW, 20, "Custom artist");
        artistInput.setHint(Component.literal("Custom artist"));
        artistInput.setValue(draftArtist);
        addRenderableWidget(artistInput);

        thumbnailInput = createInput(x + 8, y + 74, w - 16, 20, "Thumbnail URL (optional)");
        thumbnailInput.setHint(Component.literal("Thumbnail URL (optional)"));
        thumbnailInput.setValue(draftThumbnail);
        addRenderableWidget(thumbnailInput);

        int sourceButtonY = y + 98;
        addRenderableWidget(new StyledButton(x + 8, sourceButtonY, 126, 20, Component.literal("▶ Play Now"), this::playFromInputs, true, false));
        addRenderableWidget(new StyledButton(x + 140, sourceButtonY, 154, 20, Component.literal("💾 Save To Library"), this::addInputToLibrary, false, false));
        addRenderableWidget(new StyledButton(x + 300, sourceButtonY, 126, 20, Component.literal("➕ Queue URL"), this::queueInputFromInputs, false, false));

        int controlsY = panelY + PANEL_HEIGHT - 34;
        addRenderableWidget(new StyledButton(x, controlsY, 132, 22, Component.literal("▶ Play Selected"), this::playSelectedLibrary, true, false));
        addRenderableWidget(new StyledButton(x + 138, controlsY, 124, 22, Component.literal("➕ Add To Queue"), this::enqueueSelectedLibrary, false, false));
        addRenderableWidget(new StyledButton(x + 268, controlsY, 90, 22, Component.literal("🗑 Delete"), this::removeSelectedLibrary, false, false));
        addRenderableWidget(new StyledButton(x + 364, controlsY, 134, 22, Component.literal("≡ Add To Playlist"), this::addSelectedLibraryToPlaylist, false, false));
    }

    private void buildPlaylistsTab() {
        int x = contentX();
        int y = contentY();
        int w = contentW();

        playlistNameInput = createInput(x, y, w - 196, 20, "Playlist name");
        playlistNameInput.setHint(Component.literal("Playlist name"));
        playlistNameInput.setValue(draftPlaylistName);
        addRenderableWidget(playlistNameInput);

        addRenderableWidget(new StyledButton(x + w - 190, y, 92, 20, Component.literal("＋ Create"), this::createPlaylist, true, false));
        addRenderableWidget(new StyledButton(x + w - 92, y, 92, 20, Component.literal("🗑 Delete"), this::deleteSelectedPlaylist, false, false));

        int footerY = panelY + PANEL_HEIGHT - 34;
        addRenderableWidget(new StyledButton(x, footerY, 120, 22, Component.literal("▶ Play List"), this::playSelectedPlaylist, true, false));
        addRenderableWidget(new StyledButton(x + 126, footerY, 120, 22, Component.literal("▶ Play Song"), this::playSelectedPlaylistTrack, false, false));
        addRenderableWidget(new StyledButton(x + 252, footerY, 136, 22, Component.literal("➕ Queue Song"), this::queueSelectedPlaylistTrack, false, false));
        addRenderableWidget(new StyledButton(x + 394, footerY, 104, 22, Component.literal("✖ Remove"), this::removeSelectedPlaylistTrack, false, false));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fillGradient(0, 0, width, height, COLOR_BG_TOP, COLOR_BG_BOTTOM);
        guiGraphics.fillGradient(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, COLOR_PANEL, COLOR_PANEL_ALT);
        guiGraphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + HEADER_HEIGHT, COLOR_HEADER);
        drawOutline(guiGraphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, COLOR_STROKE);

        String contextText = isBlockMode() ? "Placed Radio" : "Handheld Radio";
        guiGraphics.drawString(font, "MEDIA RADIO", panelX + 16, panelY + 16, COLOR_TEXT, false);
        guiGraphics.drawString(font, contextText, panelX + PANEL_WIDTH - 184, panelY + 16, COLOR_ACCENT_ALT, false);

        switch (tab) {
            case NOW -> renderNowTab(guiGraphics);
            case LIBRARY -> renderLibraryTab(guiGraphics);
            case PLAYLISTS -> renderPlaylistsTab(guiGraphics);
        }

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    private void renderNowTab(GuiGraphics guiGraphics) {
        int x = contentX();
        int y = contentY();
        int h = contentH();
        int rightX = nowRightPanelX();
        int rightW = nowRightPanelW();

        guiGraphics.fill(x, y, x + NOW_LEFT_W, y + h, COLOR_CARD);
        guiGraphics.fill(rightX, y, rightX + rightW, y + h, COLOR_CARD);
        drawOutline(guiGraphics, x, y, NOW_LEFT_W, h, 0x6653768F);
        drawOutline(guiGraphics, rightX, y, rightW, h, 0x6653768F);

        PlaybackView playback = getPlaybackView();
        int thumbX = x + 12;
        int thumbY = y + 12;
        int thumbSize = 88;
        drawNowPlayingThumbnail(guiGraphics, playback.thumbnail(), thumbX, thumbY, thumbSize);

        int textX = thumbX + thumbSize + 10;
        int textW = NOW_LEFT_W - (textX - x) - 10;
        int stateColor = switch (playback.state()) {
            case "Playing" -> COLOR_GOOD;
            case "Paused" -> COLOR_WARN;
            default -> COLOR_BAD;
        };
        guiGraphics.drawString(font, playback.state(), textX, y + 14, stateColor, false);
        int titleBottom = drawWrappedText(guiGraphics, playback.title(), textX, y + 30, textW, 2, COLOR_TEXT);
        int artistBottom = drawWrappedText(
                guiGraphics,
                playback.artist().isBlank() ? "Unknown Artist" : playback.artist(),
                textX,
                titleBottom + 4,
                textW,
                2,
                COLOR_MUTED
        );

        String timer = playback.durationMs() > 0L
                ? formatTime(playback.positionMs()) + " / " + formatTime(playback.durationMs())
                : formatTime(playback.positionMs());
        int timerY = Math.max(y + 86, artistBottom + 4);
        guiGraphics.drawString(font, "Time: " + timer, textX, timerY, COLOR_ACCENT_ALT, false);
        drawTimelineBar(guiGraphics, playback, textX, timerY + 12, textW, 8);
        guiGraphics.drawString(font, "Volume: " + String.format(Locale.ROOT, "%.0f%%", playback.volume() * 100f), x + 12, y + 214, COLOR_MUTED, false);

        guiGraphics.drawString(font, "Queue", rightX + 8, y + 10, COLOR_ACCENT, false);
        List<SharedMediaSnapshot.MediaEntry> queueEntries = ClientMediaRepository.getInstance().getQueueEntries();
        int queueCurrent = ClientMediaRepository.getInstance().getQueueIndex();

        int rowY = nowQueueListY();
        int maxRows = nowQueueVisibleRows();
        int start = clampScroll(queueScroll, queueEntries.size(), maxRows);
        queueScroll = start;
        int end = Math.min(queueEntries.size(), start + maxRows);
        for (int i = 0, index = start; index < end; i++, index++) {
            SharedMediaSnapshot.MediaEntry entry = queueEntries.get(index);
            boolean current = index == queueCurrent;
            boolean selected = index == selectedQueueIndex;

            int lineTop = rowY + i * QUEUE_ROW_HEIGHT;
            if (current) {
                guiGraphics.fill(nowQueueListX(), lineTop, nowQueueListX() + nowQueueListW(), lineTop + QUEUE_ROW_HEIGHT - 1, 0x55308DA6);
            }
            if (selected) {
                guiGraphics.fill(nowQueueListX() + 1, lineTop + 1, nowQueueListX() + nowQueueListW() - 1, lineTop + QUEUE_ROW_HEIGHT - 2, 0x55499FBD);
            }

            String title = entry.title == null || entry.title.isBlank() ? entry.url : entry.title;
            String thumbnail = MediaMetadataResolver.bestThumbnail(entry.thumbnail, entry.url);
            String artist = entry.artist == null || entry.artist.isBlank() ? "Unknown Artist" : entry.artist;
            drawListThumbnail(guiGraphics, thumbnail, nowQueueListX() + 4, rowThumbY(lineTop, QUEUE_ROW_HEIGHT, QUEUE_THUMB_SIZE), QUEUE_THUMB_SIZE);
            guiGraphics.drawString(font, trim((index + 1) + ". " + title, 34), nowQueueListX() + 30, lineTop + 4, selected ? 0xFFFFFFFF : COLOR_TEXT, false);
            guiGraphics.drawString(font, trim(artist, 30), nowQueueListX() + 30, lineTop + 14, COLOR_MUTED, false);
        }

        int footerTop = nowRightFooterY();
        guiGraphics.drawString(font, "Items: " + queueEntries.size(), rightX + 8, footerTop, COLOR_MUTED, false);
        if (queueCurrent >= 0 && queueCurrent < queueEntries.size()) {
            SharedMediaSnapshot.MediaEntry current = queueEntries.get(queueCurrent);
            String currentTitle = current.title == null || current.title.isBlank() ? current.url : current.title;
            guiGraphics.drawString(font, "Current: " + trim(currentTitle, 42), rightX + 8, footerTop + 10, COLOR_MUTED, false);
        }
    }

    private void renderLibraryTab(GuiGraphics guiGraphics) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        guiGraphics.fill(x, y, x + w, y + 118, COLOR_CARD_SOFT);
        drawOutline(guiGraphics, x, y, w, 118, 0x6653768F);
        guiGraphics.drawString(font, "Add Source", x + 8, y + 8, COLOR_ACCENT, false);

        int listY = y + 126;
        int listH = h - 156;
        guiGraphics.fill(x, listY, x + w, listY + listH, COLOR_CARD);
        drawOutline(guiGraphics, x, listY, w, listH, 0x6653768F);
        guiGraphics.drawString(font, "Library", x + 8, listY + 8, COLOR_ACCENT, false);

        List<SharedMediaSnapshot.MediaEntry> entries = ClientMediaRepository.getInstance().getSortedLibrary();
        int rowStartY = listY + 24;
        int maxRows = libraryVisibleRows(listH);
        int start = clampScroll(libraryScroll, entries.size(), maxRows);
        libraryScroll = start;
        int end = Math.min(entries.size(), start + maxRows);
        for (int i = 0, index = start; index < end; i++, index++) {
            int lineTop = rowStartY + i * MEDIA_ROW_HEIGHT;
            boolean selected = index == selectedLibraryIndex;
            if (selected) {
                guiGraphics.fill(x + 6, lineTop - 1, x + w - 6, lineTop + MEDIA_ROW_HEIGHT - 1, 0xAA365D77);
            }

            SharedMediaSnapshot.MediaEntry entry = entries.get(index);
            String title = entry.title == null || entry.title.isBlank() ? entry.url : entry.title;
            String artist = entry.artist == null || entry.artist.isBlank() ? "Unknown Artist" : entry.artist;
            String thumbnail = MediaMetadataResolver.bestThumbnail(entry.thumbnail, entry.url);
            drawListThumbnail(guiGraphics, thumbnail, x + 10, rowThumbY(lineTop, MEDIA_ROW_HEIGHT, MEDIA_THUMB_SIZE), MEDIA_THUMB_SIZE);
            guiGraphics.drawString(font, trim(title, 56), x + 38, lineTop + 6, selected ? 0xFFFFFFFF : COLOR_TEXT, false);
            guiGraphics.drawString(font, trim(artist, 52), x + 38, lineTop + 16, COLOR_MUTED, false);
        }

        guiGraphics.drawString(font, "Items: " + entries.size(), x + 8, listY + listH - 16, COLOR_MUTED, false);
        if (selectedLibraryIndex >= 0 && selectedLibraryIndex < entries.size()) {
            SharedMediaSnapshot.MediaEntry entry = entries.get(selectedLibraryIndex);
            guiGraphics.drawString(font, trim(entry.url, 68), x + 90, listY + listH - 16, COLOR_MUTED, false);
        }
    }

    private void renderPlaylistsTab(GuiGraphics guiGraphics) {
        int listY = contentY() + 30;
        int leftX = contentX();
        int leftW = 244;
        int rightX = leftX + leftW + 10;
        int rightW = contentW() - leftW - 10;
        int listH = contentH() - 66;

        guiGraphics.fill(leftX, listY, leftX + leftW, listY + listH, COLOR_CARD);
        guiGraphics.fill(rightX, listY, rightX + rightW, listY + listH, COLOR_CARD);
        drawOutline(guiGraphics, leftX, listY, leftW, listH, 0x6653768F);
        drawOutline(guiGraphics, rightX, listY, rightW, listH, 0x6653768F);

        guiGraphics.drawString(font, "Playlists", leftX + 8, listY + 8, COLOR_ACCENT, false);
        guiGraphics.drawString(font, "Tracks", rightX + 8, listY + 8, COLOR_ACCENT, false);

        List<SharedMediaSnapshot.PlaylistEntry> playlists = ClientMediaRepository.getInstance().getSortedPlaylists();
        int rowStartY = listY + 24;
        int maxPlaylistRows = playlistVisibleRows(listH);
        int playlistStart = clampScroll(playlistScroll, playlists.size(), maxPlaylistRows);
        playlistScroll = playlistStart;
        int playlistEnd = Math.min(playlists.size(), playlistStart + maxPlaylistRows);
        for (int i = 0, index = playlistStart; index < playlistEnd; i++, index++) {
            int lineTop = rowStartY + i * PLAYLIST_ROW_HEIGHT;
            boolean selected = index == selectedPlaylistIndex;
            if (selected) {
                guiGraphics.fill(leftX + 6, lineTop - 1, leftX + leftW - 6, lineTop + PLAYLIST_ROW_HEIGHT - 1, 0xAA365D77);
            }

            SharedMediaSnapshot.PlaylistEntry playlist = playlists.get(index);
            String label = playlist.name == null || playlist.name.isBlank() ? playlist.id : playlist.name;
            guiGraphics.drawString(font, trim(label, 30), leftX + 10, rowTextY(lineTop, PLAYLIST_ROW_HEIGHT), selected ? 0xFFFFFFFF : COLOR_TEXT, false);
        }

        List<SharedMediaSnapshot.MediaEntry> tracks = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId);
        int maxTrackRows = playlistTrackVisibleRows(listH);
        int trackStart = clampScroll(playlistTrackScroll, tracks.size(), maxTrackRows);
        playlistTrackScroll = trackStart;
        int trackEnd = Math.min(tracks.size(), trackStart + maxTrackRows);
        for (int i = 0, index = trackStart; index < trackEnd; i++, index++) {
            int lineTop = rowStartY + i * MEDIA_ROW_HEIGHT;
            boolean selected = index == selectedPlaylistTrackIndex;
            if (selected) {
                guiGraphics.fill(rightX + 6, lineTop - 1, rightX + rightW - 6, lineTop + MEDIA_ROW_HEIGHT - 1, 0xAA365D77);
            }

            SharedMediaSnapshot.MediaEntry entry = tracks.get(index);
            String title = entry.title == null || entry.title.isBlank() ? entry.url : entry.title;
            String artist = entry.artist == null || entry.artist.isBlank() ? "Unknown Artist" : entry.artist;
            String thumbnail = MediaMetadataResolver.bestThumbnail(entry.thumbnail, entry.url);
            drawListThumbnail(guiGraphics, thumbnail, rightX + 10, rowThumbY(lineTop, MEDIA_ROW_HEIGHT, MEDIA_THUMB_SIZE), MEDIA_THUMB_SIZE);
            guiGraphics.drawString(font, trim(title, 24), rightX + 38, lineTop + 6, selected ? 0xFFFFFFFF : COLOR_TEXT, false);
            guiGraphics.drawString(font, trim(artist, 22), rightX + 38, lineTop + 16, COLOR_MUTED, false);
        }

        guiGraphics.drawString(font, "Playlists: " + playlists.size(), leftX + 8, listY + listH - 16, COLOR_MUTED, false);
        guiGraphics.drawString(font, "Songs: " + tracks.size(), rightX + 8, listY + listH - 16, COLOR_MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == Tab.NOW && button == 0 && canSeekTimeline()
                && mouseX >= nowTimelineX() && mouseX <= nowTimelineX() + nowTimelineW()
                && mouseY >= nowTimelineY() && mouseY <= nowTimelineY() + nowTimelineH()) {
            timelineDragging = true;
            return true;
        }

        if (tab == Tab.NOW) {
            if (mouseX >= nowQueueListX() && mouseX <= nowQueueListX() + nowQueueListW()
                    && mouseY >= nowQueueListY() && mouseY <= nowQueueListY() + nowQueueListH()) {
                int row = (int) ((mouseY - nowQueueListY()) / QUEUE_ROW_HEIGHT);
                List<SharedMediaSnapshot.MediaEntry> queue = ClientMediaRepository.getInstance().getQueueEntries();
                int maxRows = nowQueueVisibleRows();
                int index = queueScroll + row;
                if (row >= 0 && row < maxRows && index >= 0 && index < queue.size()) {
                    selectedQueueIndex = index;
                    return true;
                }
            }
        }

        if (tab == Tab.LIBRARY) {
            int x = contentX();
            int listY = contentY() + 126;
            int w = contentW();
            int listH = contentH() - 156;
            int rowStartY = listY + 24;
            if (mouseX >= x && mouseX <= x + w && mouseY >= rowStartY && mouseY <= rowStartY + listH - 30) {
                int row = (int) ((mouseY - (listY + 24)) / MEDIA_ROW_HEIGHT);
                int maxRows = libraryVisibleRows(listH);
                int index = libraryScroll + row;
                int size = ClientMediaRepository.getInstance().getSortedLibrary().size();
                if (row >= 0 && row < maxRows && index >= 0 && index < size) {
                    selectedLibraryIndex = index;
                    return true;
                }
            }
        }

        if (tab == Tab.PLAYLISTS) {
            int listY = contentY() + 30;
            int leftX = contentX();
            int leftW = 244;
            int rightX = leftX + leftW + 10;
            int rightW = contentW() - leftW - 10;
            int listH = contentH() - 66;
            int rowStartY = listY + 24;

            if (mouseX >= leftX && mouseX <= leftX + leftW && mouseY >= rowStartY && mouseY <= rowStartY + listH - 30) {
                int row = (int) ((mouseY - rowStartY) / PLAYLIST_ROW_HEIGHT);
                List<SharedMediaSnapshot.PlaylistEntry> playlists = ClientMediaRepository.getInstance().getSortedPlaylists();
                int maxRows = playlistVisibleRows(listH);
                int index = playlistScroll + row;
                if (row >= 0 && row < maxRows && index >= 0 && index < playlists.size()) {
                    selectedPlaylistIndex = index;
                    selectedPlaylistId = playlists.get(index).id;
                    selectedPlaylistTrackIndex = -1;
                    return true;
                }
            }

            if (mouseX >= rightX && mouseX <= rightX + rightW && mouseY >= rowStartY && mouseY <= rowStartY + listH - 30) {
                int row = (int) ((mouseY - rowStartY) / MEDIA_ROW_HEIGHT);
                List<SharedMediaSnapshot.MediaEntry> tracks = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId);
                int maxRows = playlistTrackVisibleRows(listH);
                int index = playlistTrackScroll + row;
                if (row >= 0 && row < maxRows && index >= 0 && index < tracks.size()) {
                    selectedPlaylistTrackIndex = index;
                    return true;
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (timelineDragging && button == 0) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (timelineDragging && button == 0) {
            seekFromTimelineMouse(mouseX);
            timelineDragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY) {
        int step = deltaY > 0.0d ? -1 : deltaY < 0.0d ? 1 : 0;
        if (step == 0) {
            return super.mouseScrolled(mouseX, mouseY, deltaY);
        }

        if (tab == Tab.NOW
                && mouseX >= nowQueueListX() && mouseX <= nowQueueListX() + nowQueueListW()
                && mouseY >= nowQueueListY() && mouseY <= nowQueueListY() + nowQueueListH()) {
            int size = ClientMediaRepository.getInstance().getQueueEntries().size();
            queueScroll = clampScroll(queueScroll + step, size, nowQueueVisibleRows());
            return true;
        }

        if (tab == Tab.LIBRARY) {
            int x = contentX();
            int y = contentY() + 126;
            int w = contentW();
            int h = contentH() - 156;
            int rowStartY = y + 24;
            if (mouseX >= x && mouseX <= x + w && mouseY >= rowStartY && mouseY <= rowStartY + h - 30) {
                int size = ClientMediaRepository.getInstance().getSortedLibrary().size();
                libraryScroll = clampScroll(libraryScroll + step, size, libraryVisibleRows(h));
                return true;
            }
        }

        if (tab == Tab.PLAYLISTS) {
            int listY = contentY() + 30;
            int leftX = contentX();
            int leftW = 244;
            int rightX = leftX + leftW + 10;
            int rightW = contentW() - leftW - 10;
            int listH = contentH() - 66;
            int rowStartY = listY + 24;

            if (mouseX >= leftX && mouseX <= leftX + leftW && mouseY >= rowStartY && mouseY <= rowStartY + listH - 30) {
                int size = ClientMediaRepository.getInstance().getSortedPlaylists().size();
                playlistScroll = clampScroll(playlistScroll + step, size, playlistVisibleRows(listH));
                return true;
            }

            if (mouseX >= rightX && mouseX <= rightX + rightW && mouseY >= rowStartY && mouseY <= rowStartY + listH - 30) {
                int size = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId).size();
                playlistTrackScroll = clampScroll(playlistTrackScroll + step, size, playlistTrackVisibleRows(listH));
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, deltaY);
    }

    private void playFromInputs() {
        String url = urlInput == null ? "" : urlInput.getValue().trim();
        if (url.isBlank()) {
            return;
        }

        String title = titleInput == null ? "" : titleInput.getValue().trim();
        String artist = artistInput == null ? "" : artistInput.getValue().trim();
        String thumbnail = thumbnailInput == null ? "" : thumbnailInput.getValue().trim();

        SharedMediaSnapshot.MediaEntry entry = ClientMediaRepository.getInstance().upsertMedia(url, title, artist, thumbnail, List.of());
        playEntry(entry, true);
        resolveMetadataAndApply(url, title, artist, thumbnail, true);
    }

    private void queueInputFromInputs() {
        String url = urlInput == null ? "" : urlInput.getValue().trim();
        if (url.isBlank()) {
            return;
        }

        String title = titleInput == null ? "" : titleInput.getValue().trim();
        String artist = artistInput == null ? "" : artistInput.getValue().trim();
        String thumbnail = thumbnailInput == null ? "" : thumbnailInput.getValue().trim();

        SharedMediaSnapshot.MediaEntry entry = ClientMediaRepository.getInstance().upsertMedia(url, title, artist, thumbnail, List.of());
        ClientMediaRepository.getInstance().enqueue(entry.id);
        selectedQueueIndex = Math.max(0, ClientMediaRepository.getInstance().getQueueEntries().size() - 1);
        resolveMetadataAndApply(url, title, artist, thumbnail, false);
    }

    private void playMedia(String url, String title, String artist, String thumbnail) {
        String displayTitle = title == null || title.isBlank() ? url : title;
        String safeArtist = artist == null ? "" : artist;
        String safeThumbnail = thumbnail == null ? "" : thumbnail;

        if (isBlockMode()) {
            ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                    blockPos,
                    ServerboundRadioControlMessage.Action.PLAY_URL,
                    url,
                    displayTitle,
                    safeArtist,
                    safeThumbnail,
                    blockVolume,
                    0L
            ));
        } else {
            ClientAudioEngine.getInstance().playHandheld(url, 0L, hand, displayTitle, safeArtist, safeThumbnail);
        }
    }

    private void stopPlayback() {
        if (isBlockMode()) {
            ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                    blockPos,
                    ServerboundRadioControlMessage.Action.STOP,
                    "",
                    "",
                    "",
                    "",
                    blockVolume,
                    0L
            ));
        } else {
            ClientAudioEngine.getInstance().stopHandheld();
            ClientAudioEngine.getInstance().clearHandheldState();
        }
        persistRuntimeState();
    }

    private void togglePause() {
        if (isBlockMode()) {
            ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                    blockPos,
                    ServerboundRadioControlMessage.Action.TOGGLE_PAUSE,
                    "",
                    "",
                    "",
                    "",
                    blockVolume,
                    0L
            ));
        } else {
            ClientAudioEngine.getInstance().togglePauseHandheld();
        }
        updatePauseResumeButtonLabel();
        updateLoopModeButtonLabel();
    }

    private void addInputToLibrary() {
        String url = urlInput == null ? "" : urlInput.getValue().trim();
        if (url.isBlank()) {
            return;
        }

        String title = titleInput == null ? "" : titleInput.getValue().trim();
        String artist = artistInput == null ? "" : artistInput.getValue().trim();
        String thumbnail = thumbnailInput == null ? "" : thumbnailInput.getValue().trim();
        resolveMetadataAndApply(url, title, artist, thumbnail, false);
    }

    private void playSelectedLibrary() {
        List<SharedMediaSnapshot.MediaEntry> library = ClientMediaRepository.getInstance().getSortedLibrary();
        if (selectedLibraryIndex < 0 || selectedLibraryIndex >= library.size()) {
            return;
        }

        playEntry(library.get(selectedLibraryIndex), true);
    }

    private void enqueueSelectedLibrary() {
        List<SharedMediaSnapshot.MediaEntry> library = ClientMediaRepository.getInstance().getSortedLibrary();
        if (selectedLibraryIndex < 0 || selectedLibraryIndex >= library.size()) {
            return;
        }
        ClientMediaRepository.getInstance().enqueue(library.get(selectedLibraryIndex).id);
        selectedQueueIndex = Math.max(0, ClientMediaRepository.getInstance().getQueueEntries().size() - 1);
    }

    private void removeSelectedLibrary() {
        List<SharedMediaSnapshot.MediaEntry> library = ClientMediaRepository.getInstance().getSortedLibrary();
        if (selectedLibraryIndex < 0 || selectedLibraryIndex >= library.size()) {
            return;
        }
        ClientMediaRepository.getInstance().removeMedia(library.get(selectedLibraryIndex).id);
        selectedLibraryIndex = -1;
    }

    private void addSelectedLibraryToPlaylist() {
        List<SharedMediaSnapshot.MediaEntry> library = ClientMediaRepository.getInstance().getSortedLibrary();
        if (selectedLibraryIndex < 0 || selectedLibraryIndex >= library.size() || selectedPlaylistId.isBlank()) {
            return;
        }
        ClientMediaRepository.getInstance().addMediaToPlaylist(selectedPlaylistId, library.get(selectedLibraryIndex).id);
    }

    private void createPlaylist() {
        if (playlistNameInput == null) {
            return;
        }
        String name = playlistNameInput.getValue().trim();
        if (name.isBlank()) {
            return;
        }
        selectedPlaylistId = ClientMediaRepository.getInstance().createPlaylist(name);
        draftPlaylistName = "";
        playlistNameInput.setValue("");
    }

    private void deleteSelectedPlaylist() {
        if (selectedPlaylistId.isBlank()) {
            return;
        }
        ClientMediaRepository.getInstance().deletePlaylist(selectedPlaylistId);
        selectedPlaylistId = "";
        selectedPlaylistIndex = -1;
        selectedPlaylistTrackIndex = -1;
    }

    private void playSelectedPlaylist() {
        if (selectedPlaylistId.isBlank()) {
            return;
        }
        List<SharedMediaSnapshot.MediaEntry> tracks = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId);
        if (tracks.isEmpty()) {
            return;
        }
        ClientMediaRepository.getInstance().setQueueFromPlaylist(selectedPlaylistId);
        selectedQueueIndex = ClientMediaRepository.getInstance().getQueueIndex();
        playEntry(tracks.get(0), false);
    }

    private void playSelectedPlaylistTrack() {
        List<SharedMediaSnapshot.MediaEntry> tracks = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId);
        if (selectedPlaylistTrackIndex < 0 || selectedPlaylistTrackIndex >= tracks.size()) {
            return;
        }
        playEntry(tracks.get(selectedPlaylistTrackIndex), true);
    }

    private void queueSelectedPlaylistTrack() {
        List<SharedMediaSnapshot.MediaEntry> tracks = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId);
        if (selectedPlaylistTrackIndex < 0 || selectedPlaylistTrackIndex >= tracks.size()) {
            return;
        }
        ClientMediaRepository.getInstance().enqueue(tracks.get(selectedPlaylistTrackIndex).id);
        selectedQueueIndex = Math.max(0, ClientMediaRepository.getInstance().getQueueEntries().size() - 1);
    }

    private void removeSelectedPlaylistTrack() {
        if (selectedPlaylistId.isBlank() || selectedPlaylistTrackIndex < 0) {
            return;
        }
        ClientMediaRepository.getInstance().removeMediaFromPlaylist(selectedPlaylistId, selectedPlaylistTrackIndex);
        selectedPlaylistTrackIndex = -1;
    }

    private void playQueueNext() {
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        SharedMediaSnapshot.MediaEntry next = repository.nextQueueEntry();
        if (next != null) {
            selectedQueueIndex = repository.getQueueIndex();
            playEntry(next, false);
        }
    }

    private void playQueuePrevious() {
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        SharedMediaSnapshot.MediaEntry previous = repository.previousQueueEntry();
        if (previous != null) {
            selectedQueueIndex = repository.getQueueIndex();
            playEntry(previous, false);
        }
    }

    private void playSelectedQueue() {
        SharedMediaSnapshot.MediaEntry entry = ClientMediaRepository.getInstance().setQueueIndex(selectedQueueIndex);
        if (entry != null) {
            playEntry(entry, false);
        }
    }

    private void removeSelectedQueue() {
        if (selectedQueueIndex < 0) {
            return;
        }

        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        List<SharedMediaSnapshot.MediaEntry> queueEntries = repository.getQueueEntries();
        if (selectedQueueIndex >= queueEntries.size()) {
            return;
        }

        SharedMediaSnapshot.MediaEntry removedEntry = queueEntries.get(selectedQueueIndex);
        int currentQueueIndex = repository.getQueueIndex();
        boolean removedCurrentQueueItem = selectedQueueIndex == currentQueueIndex;
        boolean removedCurrentlyPlayingUrl = isRemovedEntryCurrentlyPlaying(removedEntry);

        repository.removeQueueIndex(selectedQueueIndex);
        int queueSize = repository.getQueueEntries().size();
        if (queueSize == 0) {
            selectedQueueIndex = -1;
        } else {
            selectedQueueIndex = Math.min(selectedQueueIndex, queueSize - 1);
        }

        if (removedCurrentQueueItem || removedCurrentlyPlayingUrl) {
            stopPlayback();
        } else {
            persistRuntimeState();
        }
    }

    private boolean isRemovedEntryCurrentlyPlaying(SharedMediaSnapshot.MediaEntry removedEntry) {
        if (removedEntry == null || removedEntry.url == null || removedEntry.url.isBlank()) {
            return false;
        }

        if (isBlockMode()) {
            RadioBlockEntity blockEntity = getBlockEntity();
            return blockEntity != null
                    && removedEntry.url.equals(blockEntity.getMediaUrl())
                    && (blockEntity.isPlaying() || blockEntity.getPlaybackPositionMs() > 0L);
        }

        ClientAudioEngine audioEngine = ClientAudioEngine.getInstance();
        String handheldUrl = audioEngine.getHandheldUrl();
        return handheldUrl != null
                && !handheldUrl.isBlank()
                && removedEntry.url.equals(handheldUrl)
                && (audioEngine.isHandheldPlaying() || audioEngine.isHandheldPaused());
    }

    private void moveSelectedQueue(int direction) {
        if (selectedQueueIndex < 0) {
            return;
        }
        int target = selectedQueueIndex + direction;
        int queueSize = ClientMediaRepository.getInstance().getQueueEntries().size();
        if (target < 0 || target >= queueSize) {
            return;
        }
        ClientMediaRepository.getInstance().moveQueueIndex(selectedQueueIndex, target);
        selectedQueueIndex = target;
    }

    private void shuffleQueue() {
        ClientMediaRepository.getInstance().shuffleQueue();
        selectedQueueIndex = ClientMediaRepository.getInstance().getQueueIndex();
    }

    private void cycleLoopMode() {
        ClientMediaRepository.getInstance().cycleLoopMode();
        updateLoopModeButtonLabel();
        persistRuntimeState();
    }

    private void playEntry(SharedMediaSnapshot.MediaEntry entry, boolean syncQueueSelection) {
        if (entry == null || entry.url == null || entry.url.isBlank()) {
            return;
        }
        if (syncQueueSelection) {
            ensureQueueCurrent(entry);
        }
        if (isBlockMode()) {
            ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                    blockPos,
                    ServerboundRadioControlMessage.Action.PLAY_URL,
                    entry.url,
                    entry.title,
                    entry.artist,
                    entry.thumbnail,
                    blockVolume,
                    0L
            ));
        } else {
            ClientAudioEngine.getInstance().playHandheld(entry.url, 0L, hand, entry.title, entry.artist, entry.thumbnail);
        }
        resolveMetadataAndApply(entry.url, entry.title, entry.artist, entry.thumbnail, false);
        persistRuntimeState();
    }

    private void ensureQueueCurrent(SharedMediaSnapshot.MediaEntry entry) {
        if (entry == null || entry.id == null || entry.id.isBlank()) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        List<SharedMediaSnapshot.MediaEntry> queue = repository.getQueueEntries();
        if (selectedQueueIndex >= 0 && selectedQueueIndex < queue.size()) {
            SharedMediaSnapshot.MediaEntry selectedEntry = queue.get(selectedQueueIndex);
            if (selectedEntry != null && entry.id.equals(selectedEntry.id)) {
                repository.setQueueIndex(selectedQueueIndex);
                return;
            }
        }
        repository.enqueue(entry.id);
        int newIndex = Math.max(0, repository.getQueueEntries().size() - 1);
        repository.setQueueIndex(newIndex);
        selectedQueueIndex = newIndex;
    }

    private void resolveMetadataAndApply(String url, String title, String artist, String thumbnail, boolean updateCurrentPlayback) {
        MediaMetadataResolver.resolve(url, title, artist, thumbnail).thenAccept(resolved -> {
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                String finalUrl = resolved.url().isBlank() ? url : resolved.url();
                if (!finalUrl.isBlank()) {
                    ClientMediaRepository.getInstance().upsertMedia(finalUrl, resolved.title(), resolved.artist(), resolved.thumbnail(), List.of());
                }

                if (titleInput != null && !titleInput.isFocused() && titleInput.getValue().isBlank()) {
                    titleInput.setValue(resolved.title());
                }
                if (artistInput != null && !artistInput.isFocused() && artistInput.getValue().isBlank()) {
                    artistInput.setValue(resolved.artist());
                }
                if (thumbnailInput != null && !thumbnailInput.isFocused() && thumbnailInput.getValue().isBlank()) {
                    thumbnailInput.setValue(resolved.thumbnail());
                }

                if (!updateCurrentPlayback) {
                    return;
                }

                if (isBlockMode()) {
                    ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                            blockPos,
                            ServerboundRadioControlMessage.Action.UPDATE_METADATA,
                            finalUrl,
                            resolved.title(),
                            resolved.artist(),
                            resolved.thumbnail(),
                            blockVolume,
                            0L
                    ));
                } else {
                    ClientAudioEngine.getInstance().updateHandheldMetadata(resolved.title(), resolved.artist(), resolved.thumbnail());
                }
            });
        });
    }

    private void drawNowPlayingThumbnail(GuiGraphics guiGraphics, String thumbnailUrl, int x, int y, int size) {
        int border = 0x884A6E8B;
        guiGraphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, border);
        guiGraphics.fill(x, y, x + size, y + size, 0xCC0E1822);

        var handle = ThumbnailTextureManager.getInstance().getTexture(thumbnailUrl);
        if (handle.location().equals(MissingTextureAtlasSprite.getLocation())) {
            guiGraphics.drawCenteredString(font, "?", x + size / 2, y + (size - 8) / 2, 0xFF88A2B8);
            return;
        }

        int sourceWidth = Math.max(1, handle.width());
        int sourceHeight = Math.max(1, handle.height());
        float scale = Math.max(size / (float) sourceWidth, size / (float) sourceHeight);
        int drawW = Mth.ceil(sourceWidth * scale);
        int drawH = Mth.ceil(sourceHeight * scale);
        int drawX = x + (size - drawW) / 2;
        int drawY = y + (size - drawH) / 2;
        guiGraphics.enableScissor(x, y, x + size, y + size);
        guiGraphics.blit(
                handle.location(),
                drawX,
                drawY,
                drawW,
                drawH,
                0f,
                0f,
                sourceWidth,
                sourceHeight,
                sourceWidth,
                sourceHeight
        );
        guiGraphics.disableScissor();
    }

    private void drawListThumbnail(GuiGraphics guiGraphics, String thumbnailUrl, int x, int y, int size) {
        guiGraphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, 0x884A6E8B);
        guiGraphics.fill(x, y, x + size, y + size, 0xCC0E1822);

        var handle = ThumbnailTextureManager.getInstance().getTexture(thumbnailUrl);
        if (handle.location().equals(MissingTextureAtlasSprite.getLocation())) {
            guiGraphics.drawCenteredString(font, "?", x + size / 2, y + (size - 8) / 2, 0xFF88A2B8);
            return;
        }

        int sourceWidth = Math.max(1, handle.width());
        int sourceHeight = Math.max(1, handle.height());
        float scale = Math.min(size / (float) sourceWidth, size / (float) sourceHeight);
        int drawW = Mth.ceil(sourceWidth * scale);
        int drawH = Mth.ceil(sourceHeight * scale);
        int drawX = x + (size - drawW) / 2;
        int drawY = y + (size - drawH) / 2;
        guiGraphics.blit(
                handle.location(),
                drawX,
                drawY,
                drawW,
                drawH,
                0f,
                0f,
                sourceWidth,
                sourceHeight,
                sourceWidth,
                sourceHeight
        );
    }

    private int rowTextY(int lineTop, int rowHeight) {
        return lineTop + (rowHeight - 8) / 2;
    }

    private int rowThumbY(int lineTop, int rowHeight, int thumbSize) {
        return lineTop + (rowHeight - thumbSize) / 2;
    }

    private void adjustVolume(float delta) {
        float newVolume = Math.max(0f, Math.min(2f, getVolume() + delta));
        if (isBlockMode()) {
            blockVolume = newVolume;
            ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                    blockPos,
                    ServerboundRadioControlMessage.Action.SET_VOLUME,
                    "",
                    "",
                    "",
                    "",
                    blockVolume,
                    0L
            ));
        } else {
            ClientAudioEngine.getInstance().setHandheldVolume(newVolume);
        }
        persistRuntimeState();
    }

    private void clampSelections() {
        ClientMediaRepository repository = ClientMediaRepository.getInstance();

        List<SharedMediaSnapshot.MediaEntry> library = repository.getSortedLibrary();
        if (selectedLibraryIndex >= library.size()) {
            selectedLibraryIndex = library.isEmpty() ? -1 : library.size() - 1;
        }
        libraryScroll = clampScroll(libraryScroll, library.size(), libraryVisibleRows(contentH() - 156));

        List<SharedMediaSnapshot.PlaylistEntry> playlists = repository.getSortedPlaylists();
        if (!selectedPlaylistId.isBlank()) {
            boolean exists = false;
            for (int i = 0; i < playlists.size(); i++) {
                if (selectedPlaylistId.equals(playlists.get(i).id)) {
                    selectedPlaylistIndex = i;
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                selectedPlaylistId = "";
                selectedPlaylistIndex = -1;
                selectedPlaylistTrackIndex = -1;
            }
        } else if (selectedPlaylistIndex >= 0 && selectedPlaylistIndex < playlists.size()) {
            selectedPlaylistId = playlists.get(selectedPlaylistIndex).id;
        } else if (selectedPlaylistIndex >= playlists.size()) {
            selectedPlaylistIndex = playlists.isEmpty() ? -1 : playlists.size() - 1;
            selectedPlaylistId = selectedPlaylistIndex >= 0 ? playlists.get(selectedPlaylistIndex).id : "";
        }
        playlistScroll = clampScroll(playlistScroll, playlists.size(), playlistVisibleRows(contentH() - 66));

        List<SharedMediaSnapshot.MediaEntry> playlistTracks = repository.getPlaylistMedia(selectedPlaylistId);
        if (selectedPlaylistTrackIndex >= playlistTracks.size()) {
            selectedPlaylistTrackIndex = playlistTracks.isEmpty() ? -1 : playlistTracks.size() - 1;
        }
        playlistTrackScroll = clampScroll(playlistTrackScroll, playlistTracks.size(), playlistTrackVisibleRows(contentH() - 66));

        List<SharedMediaSnapshot.MediaEntry> queue = repository.getQueueEntries();
        int queueCurrent = repository.getQueueIndex();
        if (queue.isEmpty()) {
            selectedQueueIndex = -1;
        } else if (selectedQueueIndex < 0 || selectedQueueIndex >= queue.size()) {
            if (queueCurrent >= 0 && queueCurrent < queue.size()) {
                selectedQueueIndex = queueCurrent;
            } else {
                selectedQueueIndex = 0;
            }
        }
        queueScroll = clampScroll(queueScroll, queue.size(), nowQueueVisibleRows());
    }

    private PlaybackView getPlaybackView() {
        if (isBlockMode()) {
            RadioBlockEntity blockEntity = getBlockEntity();
            if (blockEntity == null) {
                return new PlaybackView("Stopped", "No radio data", "", "", 0L, -1L, blockVolume);
            }

            String title = blockEntity.getMediaTitle();
            if (title == null || title.isBlank()) {
                title = blockEntity.getMediaUrl().isBlank() ? "Nothing queued" : blockEntity.getMediaUrl();
            }
            PlaybackDisplayInfo displayInfo = resolveDisplayInfoFromQueue(
                    blockEntity.getMediaUrl(),
                    title,
                    blockEntity.getMediaArtist(),
                    blockEntity.getMediaThumbnail()
            );
            String state = blockEntity.getMediaUrl().isBlank() ? "Stopped" : (blockEntity.isPlaying() ? "Playing" : "Paused");
            long channelPositionMs = blockPos == null ? -1L : ClientAudioEngine.getInstance().getBlockPlaybackPositionMs(blockPos);
            long channelDurationMs = blockPos == null ? -1L : ClientAudioEngine.getInstance().getBlockTrackDurationMs(blockPos);
            long positionMs = channelPositionMs >= 0L ? channelPositionMs : blockEntity.getPlaybackPositionMs();
            return new PlaybackView(
                    state,
                    displayInfo.title(),
                    displayInfo.artist(),
                    displayInfo.thumbnail(),
                    positionMs,
                    channelDurationMs,
                    blockEntity.getVolume()
            );
        }

        ClientAudioEngine audioEngine = ClientAudioEngine.getInstance();
        PlaybackDisplayInfo displayInfo = resolveDisplayInfoFromQueue(
                audioEngine.getHandheldUrl(),
                audioEngine.getHandheldNowPlaying(),
                audioEngine.getHandheldArtist(),
                audioEngine.getHandheldThumbnail()
        );
        String title = displayInfo.title();
        if (title == null || title.isBlank()) {
            title = "Nothing queued";
        }

        String state;
        if (audioEngine.isHandheldPlaying()) {
            state = "Playing";
        } else if (audioEngine.isHandheldPaused()) {
            state = "Paused";
        } else {
            state = "Stopped";
        }

        return new PlaybackView(
                state,
                title,
                displayInfo.artist(),
                displayInfo.thumbnail(),
                audioEngine.getHandheldPlaybackPositionMs(),
                audioEngine.getHandheldTrackDurationMs(),
                audioEngine.getHandheldVolume()
        );
    }

    private PlaybackDisplayInfo resolveDisplayInfoFromQueue(String playbackUrl, String title, String artist, String thumbnail) {
        String resolvedUrl = playbackUrl == null ? "" : playbackUrl.trim();
        String resolvedTitle = title == null ? "" : title.trim();
        String resolvedArtist = artist == null ? "" : artist.trim();
        String resolvedThumbnail = thumbnail == null ? "" : thumbnail.trim();

        SharedMediaSnapshot.MediaEntry currentQueue = ClientMediaRepository.getInstance().getCurrentQueueEntry();
        if (currentQueue == null) {
            return new PlaybackDisplayInfo(resolvedTitle, resolvedArtist, resolvedThumbnail);
        }

        String queueUrl = currentQueue.url == null ? "" : currentQueue.url.trim();
        String queueTitle = currentQueue.title == null || currentQueue.title.isBlank() ? queueUrl : currentQueue.title.trim();
        String queueArtist = currentQueue.artist == null ? "" : currentQueue.artist.trim();
        String queueThumbnail = MediaMetadataResolver.bestThumbnail(currentQueue.thumbnail, queueUrl);

        boolean titleLooksLikeUrl = resolvedTitle.startsWith("http://") || resolvedTitle.startsWith("https://");
        boolean artistMissing = resolvedArtist.isBlank() || "Unknown Artist".equalsIgnoreCase(resolvedArtist);
        boolean likelySameTrack = urlsMatch(resolvedUrl, queueUrl);

        if (!likelySameTrack && !titleLooksLikeUrl && !artistMissing) {
            return new PlaybackDisplayInfo(resolvedTitle, resolvedArtist, resolvedThumbnail);
        }

        if (!queueTitle.isBlank() && (resolvedTitle.isBlank() || titleLooksLikeUrl || likelySameTrack)) {
            resolvedTitle = queueTitle;
        }
        if (!queueArtist.isBlank() && (artistMissing || likelySameTrack)) {
            resolvedArtist = queueArtist;
        }
        if (!queueThumbnail.isBlank() && (resolvedThumbnail.isBlank() || likelySameTrack)) {
            resolvedThumbnail = queueThumbnail;
        }

        return new PlaybackDisplayInfo(resolvedTitle, resolvedArtist, resolvedThumbnail);
    }

    private boolean urlsMatch(String first, String second) {
        String a = normalizeUrlForCompare(first);
        String b = normalizeUrlForCompare(second);
        if (a.isBlank() || b.isBlank()) {
            return false;
        }
        if (a.equalsIgnoreCase(b)) {
            return true;
        }
        String aThumb = MediaMetadataResolver.bestThumbnail("", a);
        String bThumb = MediaMetadataResolver.bestThumbnail("", b);
        return !aThumb.isBlank() && aThumb.equalsIgnoreCase(bThumb);
    }

    private String normalizeUrlForCompare(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        int fragmentIndex = normalized.indexOf('#');
        if (fragmentIndex >= 0) {
            normalized = normalized.substring(0, fragmentIndex);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String formatTime(long ms) {
        if (ms < 0L) {
            return "--:--";
        }
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    private int drawWrappedText(GuiGraphics guiGraphics, String value, int x, int y, int width, int maxLines, int color) {
        List<FormattedCharSequence> lines = font.split(Component.literal(value == null ? "" : value), Math.max(20, width));
        int limit = Math.min(maxLines, lines.size());
        int drawY = y;
        for (int i = 0; i < limit; i++) {
            guiGraphics.drawString(font, lines.get(i), x, drawY, color, false);
            drawY += 10;
        }
        return drawY;
    }

    private void updatePauseResumeButtonLabel() {
        if (pauseResumeButton == null || tab != Tab.NOW) {
            return;
        }

        PlaybackView playback = getPlaybackView();
        if ("Paused".equals(playback.state()) || "Stopped".equals(playback.state())) {
            pauseResumeButton.setMessage(Component.literal("▶ Resume"));
        } else {
            pauseResumeButton.setMessage(Component.literal("⏸ Pause"));
        }
    }

    private void updateLoopModeButtonLabel() {
        if (loopModeButton == null || tab != Tab.NOW) {
            return;
        }
        ClientMediaRepository.LoopMode loopMode = ClientMediaRepository.getInstance().getLoopMode();
        String label = switch (loopMode) {
            case NONE -> "🔁 Loop: None";
            case ONE -> "🔂 Loop: One";
            case ALL -> "🔁 Loop: All";
        };
        loopModeButton.setMessage(Component.literal(label));
    }

    private void drawTimelineBar(GuiGraphics guiGraphics, PlaybackView playback, int x, int y, int width, int height) {
        int barX = nowTimelineX();
        int barY = nowTimelineY();
        int barW = nowTimelineW();
        int barH = nowTimelineH();

        guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xAA12202D);
        drawOutline(guiGraphics, barX, barY, barW, barH, 0x88597C98);

        if (playback.durationMs() <= 0L) {
            return;
        }

        float progress = Mth.clamp(playback.positionMs() / (float) playback.durationMs(), 0f, 1f);
        int filled = Math.max(1, Math.round((barW - 2) * progress));
        guiGraphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + barH - 1, 0xCC4FA2CB);

        int knobX = barX + 1 + filled;
        guiGraphics.fill(knobX - 1, barY - 2, knobX + 1, barY + barH + 2, COLOR_ACCENT_ALT);
    }

    private boolean canSeekTimeline() {
        if (isBlockMode()) {
            return blockPos != null && ClientAudioEngine.getInstance().getBlockTrackDurationMs(blockPos) > 0L;
        }
        return ClientAudioEngine.getInstance().getHandheldTrackDurationMs() > 0L;
    }

    private void seekFromTimelineMouse(double mouseX) {
        if (!canSeekTimeline()) {
            return;
        }
        int barX = nowTimelineX();
        int barW = nowTimelineW();
        float pct = (float) ((mouseX - barX) / Math.max(1.0, barW));
        pct = Mth.clamp(pct, 0f, 1f);
        if (isBlockMode()) {
            if (blockPos == null) {
                return;
            }
            long duration = ClientAudioEngine.getInstance().getBlockTrackDurationMs(blockPos);
            if (duration <= 0L) {
                return;
            }
            long target = (long) (duration * pct);
            ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                    blockPos,
                    ServerboundRadioControlMessage.Action.SEEK,
                    "",
                    "",
                    "",
                    "",
                    blockVolume,
                    target
            ));
            return;
        }
        long duration = ClientAudioEngine.getInstance().getHandheldTrackDurationMs();
        ClientAudioEngine.getInstance().seekHandheld((long) (duration * pct));
    }

    private int nowRightPanelX() {
        return contentX() + NOW_LEFT_W + COLUMN_GAP;
    }

    private int nowRightPanelW() {
        return contentW() - NOW_LEFT_W - COLUMN_GAP;
    }

    private int nowTimelineX() {
        int x = contentX();
        int thumbX = x + 12;
        int thumbSize = 88;
        return thumbX + thumbSize + 10;
    }

    private int nowTimelineY() {
        return contentY() + 108;
    }

    private int nowTimelineW() {
        return NOW_LEFT_W - (nowTimelineX() - contentX()) - 10;
    }

    private int nowTimelineH() {
        return 8;
    }

    private int nowQueueListX() {
        return nowRightPanelX() + 8;
    }

    private int nowQueueListY() {
        return contentY() + 28;
    }

    private int nowQueueListW() {
        return nowRightPanelW() - 16;
    }

    private int nowQueueListH() {
        int available = nowRightFooterY() - 6 - nowQueueListY();
        return Math.max(QUEUE_ROW_HEIGHT, available);
    }

    private int nowQueueVisibleRows() {
        return Math.max(1, nowQueueListH() / QUEUE_ROW_HEIGHT);
    }

    private int libraryVisibleRows(int listH) {
        return Math.max(1, (listH - 30) / MEDIA_ROW_HEIGHT);
    }

    private int playlistVisibleRows(int listH) {
        return Math.max(1, (listH - 30) / PLAYLIST_ROW_HEIGHT);
    }

    private int playlistTrackVisibleRows(int listH) {
        return Math.max(1, (listH - 30) / MEDIA_ROW_HEIGHT);
    }

    private int nowRightActionY() {
        return contentY() + contentH() - 28;
    }

    private int nowRightFooterY() {
        return nowRightActionY() - 24;
    }

    private int contentX() {
        return panelX + PADDING + 4;
    }

    private int contentY() {
        return panelY + HEADER_HEIGHT + 10;
    }

    private int contentW() {
        return PANEL_WIDTH - ((PADDING + 4) * 2);
    }

    private int contentH() {
        return PANEL_HEIGHT - HEADER_HEIGHT - 20;
    }

    private boolean isBlockMode() {
        return blockPos != null;
    }

    public boolean isBlockModeScreen() {
        return isBlockMode();
    }

    private float getVolume() {
        return isBlockMode() ? blockVolume : ClientAudioEngine.getInstance().getHandheldVolume();
    }

    private RadioBlockEntity getBlockEntity() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || blockPos == null) {
            return null;
        }
        if (!(minecraft.level.getBlockEntity(blockPos) instanceof RadioBlockEntity radioBlockEntity)) {
            return null;
        }
        return radioBlockEntity;
    }

    private void persistRuntimeState() {
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        if (isBlockMode()) {
            if (blockPos == null) {
                return;
            }
            RadioBlockEntity blockEntity = getBlockEntity();
            if (blockEntity == null) {
                return;
            }
            String radioId = blockEntity.getRadioId();
            if (radioId == null || radioId.isBlank()) {
                return;
            }
            if (!radioId.equals(repository.getActiveRadioId())) {
                repository.setActiveRadioId(radioId);
            }
            String queueStateJson = repository.exportActiveQueueStateJson();
            if (!queueStateJson.equals(lastPersistedQueueState)) {
                ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                        blockPos,
                        ServerboundRadioControlMessage.Action.UPDATE_QUEUE_STATE,
                        queueStateJson,
                        "",
                        "",
                        "",
                        blockVolume,
                        0L
                ));
                lastPersistedQueueState = queueStateJson;
            }
            return;
        }

        String queueStateJson = repository.exportActiveQueueStateJson();
        if (minecraft == null || minecraft.player == null || hand == null) {
            return;
        }
        var stack = minecraft.player.getItemInHand(hand);
        if (!stack.is(ModItems.RADIO_ITEM)) {
            return;
        }
        PlaybackView playbackView = getPlaybackView();
        String resolvedUrl = "";
        ClientAudioEngine audioEngine = ClientAudioEngine.getInstance();
        SharedMediaSnapshot.MediaEntry current = repository.getCurrentQueueEntry();
        if (current != null && current.url != null && !current.url.isBlank()) {
            resolvedUrl = current.url;
        } else if (!audioEngine.getHandheldUrl().isBlank()) {
            resolvedUrl = audioEngine.getHandheldUrl();
        } else if (!RadioItem.getSavedUrl(stack).isBlank()) {
            resolvedUrl = RadioItem.getSavedUrl(stack);
        } else if (playbackView.title() != null && playbackView.title().startsWith("http")) {
            resolvedUrl = playbackView.title();
        }
        String key = resolvedUrl + "|" + playbackView.title() + "|" + playbackView.artist() + "|" + playbackView.thumbnail() + "|"
                + playbackView.volume() + "|" + queueStateJson;
        if (key.equals(lastPersistedRuntimeKey)) {
            return;
        }
        String radioId = RadioItem.getOrCreateRadioId(stack);
        ModNetworking.sendHandheldState(new ServerboundHandheldStateMessage(
                radioId,
                resolvedUrl,
                playbackView.title(),
                playbackView.artist(),
                playbackView.thumbnail(),
                queueStateJson,
                playbackView.volume(),
                playbackView.positionMs(),
                ClientAudioEngine.getInstance().isHandheldPlaying()
        ));
        lastPersistedRuntimeKey = key;
    }

    private void syncBlockRadioContext() {
        if (!isBlockMode()) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        RadioBlockEntity blockEntity = getBlockEntity();
        if (blockEntity == null) {
            repository.setActiveRadioId(blockContextKey());
            return;
        }
        String radioId = blockEntity.getRadioId();
        if (radioId == null || radioId.isBlank()) {
            repository.setActiveRadioId(blockContextKey());
            return;
        }
        if (!radioId.equals(repository.getActiveRadioId())) {
            repository.setActiveRadioId(radioId);
        }
        if (!radioId.equals(lastBoundBlockRadioId)) {
            String queueState = blockEntity.getQueueStateJson();
            repository.importActiveQueueStateJson(queueState);
            lastPersistedQueueState = queueState;
            lastBoundBlockRadioId = radioId;
        }
    }

    private String blockContextKey() {
        if (blockPos == null) {
            return "block:unknown";
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            return "block:" + minecraft.level.dimension().location() + ":" + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
        }
        return "block:" + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private int clampScroll(int current, int itemCount, int visibleRows) {
        return Mth.clamp(current, 0, Math.max(0, itemCount - Math.max(1, visibleRows)));
    }

    private StyledEditBox createInput(int x, int y, int width, int height, String label) {
        StyledEditBox editBox = new StyledEditBox(font, x, y, width, height, Component.literal(label));
        editBox.setBordered(false);
        editBox.setTextColor(COLOR_TEXT);
        editBox.setTextColorUneditable(COLOR_MUTED);
        editBox.setMaxLength(32767);
        return editBox;
    }

    private void drawOutline(GuiGraphics guiGraphics, int x, int y, int width, int height, int color) {
        guiGraphics.fill(x, y, x + width, y + 1, color);
        guiGraphics.fill(x, y + height - 1, x + width, y + height, color);
        guiGraphics.fill(x, y, x + 1, y + height, color);
        guiGraphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static class StyledButton extends Button {
        private final Runnable action;
        private final boolean accent;
        private final boolean selected;

        protected StyledButton(int x, int y, int width, int height, Component component, Runnable action, boolean accent, boolean selected) {
            super(x, y, width, height, component, button -> action.run(), Button.DEFAULT_NARRATION);
            this.action = action;
            this.accent = accent;
            this.selected = selected;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            boolean hovered = isHoveredOrFocused();
            int fill = accent ? 0xFF6B4A1C : 0xFF1A3247;
            int border = accent ? 0xFFFFC97A : 0xFF507089;
            int text = 0xFFEAF6FF;

            if (selected) {
                fill = 0xFF2E6A95;
                border = 0xFF89D9FF;
            } else if (hovered) {
                fill = accent ? 0xFF8B632B : 0xFF284A65;
            }

            if (!active) {
                fill = 0xFF192533;
                border = 0xFF3C5368;
                text = 0xFF7E98AA;
            }

            guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, fill);
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + 1, border);
            guiGraphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, border);
            guiGraphics.fill(getX(), getY(), getX() + 1, getY() + height, border);
            guiGraphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, border);

            guiGraphics.drawCenteredString(Minecraft.getInstance().font, getMessage(), getX() + width / 2, getY() + (height - 8) / 2 + 1, text);
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        public void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            defaultButtonNarrationText(narrationElementOutput);
        }
    }

    private static class StyledEditBox extends EditBox {
        public StyledEditBox(net.minecraft.client.gui.Font font, int x, int y, int width, int height, Component component) {
            super(font, x, y, width, height, component);
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int border = isFocused() ? 0xFF9BE0FF : 0xFF4F7390;
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + height, 0xD0121C27);
            guiGraphics.fill(getX(), getY(), getX() + width, getY() + 1, border);
            guiGraphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, border);
            guiGraphics.fill(getX(), getY(), getX() + 1, getY() + height, border);
            guiGraphics.fill(getX() + width - 1, getY(), getX() + width, getY() + height, border);
            // EditBox without vanilla border renders text too high; center it in our custom field.
            int textYOffset = Math.max(0, (height - 8) / 2 - 1);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0f, textYOffset, 0.0f);
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            guiGraphics.pose().popPose();
        }
    }

    private enum Tab {
        NOW,
        LIBRARY,
        PLAYLISTS
    }

    private record PlaybackView(
            String state,
            String title,
            String artist,
            String thumbnail,
            long positionMs,
            long durationMs,
            float volume
    ) {
    }

    private record PlaybackDisplayInfo(
            String title,
            String artist,
            String thumbnail
    ) {
    }
}
