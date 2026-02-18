package net.jacobwasbeast.mediaradio.client.screen;

import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.jacobwasbeast.mediaradio.client.audio.LavaPlayerAccess;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.media.MediaMetadataResolver;
import net.jacobwasbeast.mediaradio.client.media.PlaybackDisplayResolver;
import net.jacobwasbeast.mediaradio.client.media.ThumbnailTextureManager;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
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
import java.util.ArrayList;

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
    private static final int SOURCE_ROW_HEIGHT = 34;
    private static final int SOURCE_THUMB_SIZE = 30;

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
    private final String fixedBlockRadioId;
    private final int contraptionEntityId;
    private final BlockPos contraptionLocalPos;

    private Tab tab = Tab.NOW;
    private LibraryView libraryView = LibraryView.BROWSE;
    private SourceMode sourceMode = SourceMode.YOUTUBE_SEARCH;
    private PlaylistPage playlistPage = PlaylistPage.BROWSE;
    private PlaylistImportSource playlistImportSource = PlaylistImportSource.YOUTUBE;

    private EditBox urlInput;
    private EditBox titleInput;
    private EditBox artistInput;
    private EditBox thumbnailInput;
    private EditBox playlistNameInput;
    private EditBox playlistInviteInput;
    private EditBox importPlaylistSourceInput;
    private EditBox importPlaylistNameInput;

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
    private int sourceResultScroll;
    private int playlistImportScroll;

    private String selectedPlaylistId = "";

    private float blockVolume = 1.0f;

    private String draftUrl = "";
    private String draftTitle = "";
    private String draftArtist = "";
    private String draftThumbnail = "";
    private String draftPlaylistName = "";
    private String draftPlaylistInvites = "";
    private String draftImportPlaylistSource = "";
    private String draftImportPlaylistName = "";

    private StyledButton pauseResumeButton;
    private StyledButton loopModeButton;
    private StyledButton playlistAccessButton;
    private StyledButton playlistRenameButton;
    private StyledButton playlistDeleteButton;
    private StyledButton playlistInviteApplyButton;
    private StyledButton playlistRemoveSongButton;
    private boolean timelineDragging;
    private long timelinePreviewPositionMs = -1L;
    private long lastKnownDurationMs = -1L;
    private String lastPersistedQueueState = "";
    private String lastPersistedRuntimeKey = "";
    private String lastBoundBlockRadioId = "";
    private final List<LavaPlayerAccess.SearchResult> sourceSearchResults = new ArrayList<>();
    private int selectedSourceResultIndex = -1;
    private boolean sourceSearchLoading;
    private String sourceStatusMessage = "Choose a source mode, then search or add.";
    private final List<LavaPlayerAccess.SearchResult> importYoutubeTracks = new ArrayList<>();
    private int selectedImportIndex = -1;
    private boolean playlistImportLoading;
    private String playlistImportStatus = "Pick an import source to begin.";

    private RadioScreen(BlockPos blockPos, InteractionHand hand, String fixedBlockRadioId, int contraptionEntityId, BlockPos contraptionLocalPos) {
        super(Component.literal("Media Radio"));
        this.blockPos = blockPos;
        this.hand = hand;
        this.fixedBlockRadioId = fixedBlockRadioId == null ? "" : fixedBlockRadioId;
        this.contraptionEntityId = contraptionEntityId;
        this.contraptionLocalPos = contraptionLocalPos;
    }

    public static RadioScreen forHand(InteractionHand hand) {
        return new RadioScreen(null, hand, "", -1, null);
    }

    public static RadioScreen forBlock(BlockPos blockPos) {
        return new RadioScreen(blockPos, null, "", -1, null);
    }

    public static RadioScreen forContraptionRadio(String radioId, int contraptionEntityId, BlockPos localPos) {
        return new RadioScreen(null, null, radioId, contraptionEntityId, localPos);
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
            if (!fixedBlockRadioId.isBlank()) {
                ClientAudioEngine.getInstance().setExternalContext(fixedBlockRadioId, contraptionEntityId, contraptionLocalPos);
            }
        }

        clampSelections();
        rebuildRadioWidgets();
    }

    private String resolveActiveRadioId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (isBlockMode()) {
            if (!fixedBlockRadioId.isBlank()) {
                return fixedBlockRadioId;
            }
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
        if (playlistInviteInput != null) {
            playlistInviteInput.tick();
        }
        if (importPlaylistSourceInput != null) {
            importPlaylistSourceInput.tick();
        }
        if (importPlaylistNameInput != null) {
            importPlaylistNameInput.tick();
        }

        clampSelections();
        updatePauseResumeButtonLabel();
        updatePlaylistBrowseButtonStates();
        persistRuntimeState();
        updatePlaylistAccessButtonLabel();
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
        if (playlistInviteInput != null) {
            draftPlaylistInvites = playlistInviteInput.getValue();
        }
        if (importPlaylistSourceInput != null) {
            draftImportPlaylistSource = importPlaylistSourceInput.getValue();
        }
        if (importPlaylistNameInput != null) {
            draftImportPlaylistName = importPlaylistNameInput.getValue();
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

        if (libraryView == LibraryView.SOURCES) {
            buildLibrarySourcesPage();
            return;
        }

        addRenderableWidget(new StyledButton(x + w - 178, y, 178, 20, Component.literal("＋ Add From Sources"), this::openSourcesPage, true, false));

        int controlsY = panelY + PANEL_HEIGHT - 34;
        addRenderableWidget(new StyledButton(x, controlsY, 132, 22, Component.literal("▶ Play Selected"), this::playSelectedLibrary, true, false));
        addRenderableWidget(new StyledButton(x + 138, controlsY, 124, 22, Component.literal("➕ Add To Queue"), this::enqueueSelectedLibrary, false, false));
        addRenderableWidget(new StyledButton(x + 268, controlsY, 90, 22, Component.literal("🗑 Delete"), this::removeSelectedLibrary, false, false));
        addRenderableWidget(new StyledButton(x + 364, controlsY, 134, 22, Component.literal("≡ Add To Playlist"), this::addSelectedLibraryToPlaylist, false, false));
    }

    private void buildLibrarySourcesPage() {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int modeY = y;
        int modeButtonW = 118;
        int modeGap = 6;
        int sourceInputTop = y + 42;

        addRenderableWidget(new StyledButton(x + 8, modeY, modeButtonW, 20, Component.literal("YouTube Search"), () -> setSourceMode(SourceMode.YOUTUBE_SEARCH), false, sourceMode == SourceMode.YOUTUBE_SEARCH));
        addRenderableWidget(new StyledButton(x + 8 + modeButtonW + modeGap, modeY, modeButtonW, 20, Component.literal("YouTube URL"), () -> setSourceMode(SourceMode.YOUTUBE_URL), false, sourceMode == SourceMode.YOUTUBE_URL));
        addRenderableWidget(new StyledButton(x + 8 + ((modeButtonW + modeGap) * 2), modeY, modeButtonW, 20, Component.literal("Direct URL/File"), () -> setSourceMode(SourceMode.DIRECT_URL_OR_FILE), false, sourceMode == SourceMode.DIRECT_URL_OR_FILE));
        addRenderableWidget(new StyledButton(x + w - 106, modeY, 106, 20, Component.literal("◀ Back"), this::closeSourcesPage, true, false));

        urlInput = createInput(x + 8, sourceInputTop, w - 16, 20, sourceMode.inputLabel);
        urlInput.setHint(Component.literal(sourceMode.inputHint));
        urlInput.setValue(draftUrl);
        addRenderableWidget(urlInput);

        int halfGap = 8;
        int halfW = (w - 16 - halfGap) / 2;
        titleInput = createInput(x + 8, sourceInputTop + 26, halfW, 20, "Custom title (optional)");
        titleInput.setHint(Component.literal("Custom title (optional)"));
        titleInput.setValue(draftTitle);
        addRenderableWidget(titleInput);

        artistInput = createInput(x + 8 + halfW + halfGap, sourceInputTop + 26, halfW, 20, "Custom artist (optional)");
        artistInput.setHint(Component.literal("Custom artist (optional)"));
        artistInput.setValue(draftArtist);
        addRenderableWidget(artistInput);

        thumbnailInput = createInput(x + 8, sourceInputTop + 52, w - 16, 20, "Thumbnail URL (optional)");
        thumbnailInput.setHint(Component.literal("Thumbnail URL (optional)"));
        thumbnailInput.setValue(draftThumbnail);
        addRenderableWidget(thumbnailInput);

        int actionY = sourceInputTop + 78;
        int actionGap = 6;
        int actionW = (w - 16 - (actionGap * 3)) / 4;
        if (sourceMode == SourceMode.YOUTUBE_SEARCH) {
            addRenderableWidget(new StyledButton(x + 8, actionY, actionW, 20, Component.literal("🔎 Search"), this::searchYoutubeFromInput, true, false));
            addRenderableWidget(new StyledButton(x + 8 + actionW + actionGap, actionY, actionW, 20, Component.literal("▶ Play Result"), this::playSelectedSearchResult, false, false));
            addRenderableWidget(new StyledButton(x + 8 + ((actionW + actionGap) * 2), actionY, actionW, 20, Component.literal("➕ Queue Result"), this::queueSelectedSearchResult, false, false));
            addRenderableWidget(new StyledButton(x + 8 + ((actionW + actionGap) * 3), actionY, actionW, 20, Component.literal("💾 Save Result"), this::saveSelectedSearchResult, false, false));
        } else {
            addRenderableWidget(new StyledButton(x + 8, actionY, actionW, 20, Component.literal("▶ Play Now"), this::playFromInputs, true, false));
            addRenderableWidget(new StyledButton(x + 8 + actionW + actionGap, actionY, actionW, 20, Component.literal("➕ Queue Source"), this::queueInputFromInputs, false, false));
            addRenderableWidget(new StyledButton(x + 8 + ((actionW + actionGap) * 2), actionY, actionW, 20, Component.literal("💾 Save Source"), this::addInputToLibrary, false, false));
            addRenderableWidget(new StyledButton(x + 8 + ((actionW + actionGap) * 3), actionY, actionW, 20, Component.literal("🔎 Resolve Meta"), this::resolveSourceMetadata, false, false));
        }
    }

    private void buildPlaylistsTab() {
        if (playlistPage == PlaylistPage.IMPORT) {
            buildPlaylistImportPage();
            return;
        }
        buildPlaylistBrowsePage();
    }

    private void buildPlaylistBrowsePage() {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int topButtonW = 86;
        int topGap = 6;

        playlistNameInput = createInput(x, y, w - ((topButtonW * 4) + (topGap * 3) + 8), 20, "Playlist name");
        playlistNameInput.setHint(Component.literal("Playlist name"));
        playlistNameInput.setValue(draftPlaylistName);
        addRenderableWidget(playlistNameInput);

        StyledButton createButton = new StyledButton(x + w - ((topButtonW * 4) + (topGap * 3)), y, topButtonW, 20, Component.literal("＋ Create"), this::createPlaylist, true, false);
        addRenderableWidget(createButton);
        playlistRenameButton = new StyledButton(x + w - ((topButtonW * 3) + (topGap * 2)), y, topButtonW, 20, Component.literal("✎ Rename"), this::renameSelectedPlaylist, false, false);
        addRenderableWidget(playlistRenameButton);
        playlistDeleteButton = new StyledButton(x + w - ((topButtonW * 2) + topGap), y, topButtonW, 20, Component.literal("🗑 Delete"), this::deleteSelectedPlaylist, false, false);
        addRenderableWidget(playlistDeleteButton);
        StyledButton importButton = new StyledButton(x + w - topButtonW, y, topButtonW, 20, Component.literal("⭳ Import"), this::openPlaylistImportPage, false, false);
        addRenderableWidget(importButton);

        playlistInviteInput = createInput(x, y + 26, w - 220, 20, "Invites (comma-separated player names)");
        playlistInviteInput.setHint(Component.literal("Invites (comma-separated player names)"));
        playlistInviteInput.setValue(draftPlaylistInvites);
        addRenderableWidget(playlistInviteInput);
        playlistInviteApplyButton = new StyledButton(x + w - 214, y + 26, 106, 20, Component.literal("✓ Set Invites"), this::applyPlaylistInvites, false, false);
        addRenderableWidget(playlistInviteApplyButton);
        playlistAccessButton = new StyledButton(x + w - 102, y + 26, 102, 20, Component.literal("Access"), this::cycleSelectedPlaylistAccess, false, false);
        addRenderableWidget(playlistAccessButton);
        updatePlaylistBrowseButtonStates();
        updatePlaylistAccessButtonLabel();

        int footerY = panelY + PANEL_HEIGHT - 34;
        StyledButton playListButton = new StyledButton(x, footerY, 120, 22, Component.literal("▶ Play List"), this::playSelectedPlaylist, true, false);
        addRenderableWidget(playListButton);
        StyledButton playSongButton = new StyledButton(x + 126, footerY, 120, 22, Component.literal("▶ Play Song"), this::playSelectedPlaylistTrack, false, false);
        addRenderableWidget(playSongButton);
        StyledButton queueSongButton = new StyledButton(x + 252, footerY, 136, 22, Component.literal("➕ Queue Song"), this::queueSelectedPlaylistTrack, false, false);
        addRenderableWidget(queueSongButton);
        playlistRemoveSongButton = new StyledButton(x + 394, footerY, 104, 22, Component.literal("✖ Remove"), this::removeSelectedPlaylistTrack, false, false);
        addRenderableWidget(playlistRemoveSongButton);
        updatePlaylistBrowseButtonStates();
    }

    private void buildPlaylistImportPage() {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int modeY = y;
        int modeW = 118;
        int modeGap = 6;

        addRenderableWidget(new StyledButton(x + 8, modeY, modeW, 20, Component.literal("YouTube"), () -> setPlaylistImportSource(PlaylistImportSource.YOUTUBE), false, playlistImportSource == PlaylistImportSource.YOUTUBE));
        addRenderableWidget(new StyledButton(x + 8 + modeW + modeGap, modeY, modeW, 20, Component.literal("Global"), () -> setPlaylistImportSource(PlaylistImportSource.GLOBAL), false, playlistImportSource == PlaylistImportSource.GLOBAL));
        addRenderableWidget(new StyledButton(x + 8 + ((modeW + modeGap) * 2), modeY, modeW, 20, Component.literal("Invites"), () -> setPlaylistImportSource(PlaylistImportSource.INVITES), false, playlistImportSource == PlaylistImportSource.INVITES));
        addRenderableWidget(new StyledButton(x + w - 106, modeY, 106, 20, Component.literal("◀ Back"), this::closePlaylistImportPage, true, false));

        importPlaylistSourceInput = createInput(x + 8, y + 52, w - 16, 20, playlistImportSource.sourceLabel);
        importPlaylistSourceInput.setHint(Component.literal(playlistImportSource.sourceHint));
        importPlaylistSourceInput.setValue(draftImportPlaylistSource);
        addRenderableWidget(importPlaylistSourceInput);

        importPlaylistNameInput = createInput(x + 8, y + 78, w - 16, 20, "New playlist name (optional)");
        importPlaylistNameInput.setHint(Component.literal("New playlist name (optional)"));
        importPlaylistNameInput.setValue(draftImportPlaylistName);
        addRenderableWidget(importPlaylistNameInput);

        int actionY = y + 104;
        if (playlistImportSource == PlaylistImportSource.YOUTUBE) {
            addRenderableWidget(new StyledButton(x + 8, actionY, 140, 20, Component.literal("🔎 Load Playlist"), this::loadYoutubePlaylistForImport, true, false));
            addRenderableWidget(new StyledButton(x + 154, actionY, 166, 20, Component.literal("⭳ Import As New"), this::importYoutubePlaylistAsNew, false, false));
        } else {
            addRenderableWidget(new StyledButton(x + 8, actionY, 140, 20, Component.literal("⭳ Import Selected"), this::importSelectedSharedPlaylistCopy, true, false));
            addRenderableWidget(new StyledButton(x + 154, actionY, 166, 20, Component.literal("↻ Refresh"), this::refreshPlaylistImportLists, false, false));
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fillGradient(0, 0, width, height, COLOR_BG_TOP, COLOR_BG_BOTTOM);
        guiGraphics.fillGradient(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, COLOR_PANEL, COLOR_PANEL_ALT);
        guiGraphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + HEADER_HEIGHT, COLOR_HEADER);
        drawOutline(guiGraphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, COLOR_STROKE);

        guiGraphics.drawString(font, "MEDIA RADIO", panelX + 16, panelY + 16, COLOR_TEXT, false);

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
        if (libraryView == LibraryView.SOURCES) {
            renderLibrarySourcesPage(guiGraphics);
            return;
        }
        renderLibraryBrowsePage(guiGraphics);
    }

    private void renderLibraryBrowsePage(GuiGraphics guiGraphics) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int controlsY = panelY + PANEL_HEIGHT - 34;
        int listY = y + 24;
        int listH = Math.max(MEDIA_ROW_HEIGHT + 40, (controlsY - 8) - listY);

        guiGraphics.drawString(font, "Library", x + 4, y + 6, COLOR_ACCENT, false);
        guiGraphics.drawString(font, "Use Add From Sources to import/search new media", x + 64, y + 6, COLOR_MUTED, false);
        guiGraphics.fill(x, listY, x + w, listY + listH, COLOR_CARD);
        drawOutline(guiGraphics, x, listY, w, listH, 0x6653768F);
        guiGraphics.drawString(font, "Saved Tracks", x + 8, listY + 8, COLOR_ACCENT, false);

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

    private void renderLibrarySourcesPage(GuiGraphics guiGraphics) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        int sourcePanelY = y + 22;
        int sourcePanelH = 124;
        guiGraphics.fill(x, sourcePanelY, x + w, sourcePanelY + sourcePanelH, COLOR_CARD_SOFT);
        drawOutline(guiGraphics, x, sourcePanelY, w, sourcePanelH, 0x6653768F);
        guiGraphics.drawString(font, "Sources: " + sourceMode.displayName, x + 8, sourcePanelY + 6, COLOR_ACCENT, false);
        guiGraphics.drawString(font, sourceMode.helperText, x + 152, sourcePanelY + 6, COLOR_MUTED, false);

        int listY = y + 152;
        int listH = Math.max(SOURCE_ROW_HEIGHT + 36, h - 152);
        guiGraphics.fill(x, listY, x + w, listY + listH, COLOR_CARD);
        drawOutline(guiGraphics, x, listY, w, listH, 0x6653768F);

        if (sourceMode == SourceMode.YOUTUBE_SEARCH) {
            guiGraphics.drawString(font, "YouTube Results", x + 8, listY + 8, COLOR_ACCENT, false);
            int statusColor = sourceSearchLoading ? COLOR_ACCENT_ALT : COLOR_MUTED;
            guiGraphics.drawString(font, trim(sourceStatusMessage, 68), x + 118, listY + 8, statusColor, false);

            int rowStartY = listY + 24;
            int maxRows = sourceVisibleRows(listH);
            int start = clampScroll(sourceResultScroll, sourceSearchResults.size(), maxRows);
            sourceResultScroll = start;
            int end = Math.min(sourceSearchResults.size(), start + maxRows);

            for (int i = 0, index = start; index < end; i++, index++) {
                LavaPlayerAccess.SearchResult result = sourceSearchResults.get(index);
                int lineTop = rowStartY + i * SOURCE_ROW_HEIGHT;
                boolean selected = index == selectedSourceResultIndex;
                if (selected) {
                    guiGraphics.fill(x + 6, lineTop - 1, x + w - 6, lineTop + SOURCE_ROW_HEIGHT - 1, 0xAA365D77);
                }

                drawListThumbnail(
                        guiGraphics,
                        MediaMetadataResolver.bestThumbnail(result.thumbnail(), result.identifier()),
                        x + 10,
                        rowThumbY(lineTop, SOURCE_ROW_HEIGHT, SOURCE_THUMB_SIZE),
                        SOURCE_THUMB_SIZE
                );

                String duration = result.durationMs() > 0L ? formatTime(result.durationMs()) : "--:--";
                guiGraphics.drawString(font, trim(result.title(), 54), x + 46, lineTop + 6, selected ? 0xFFFFFFFF : COLOR_TEXT, false);
                guiGraphics.drawString(font, trim(result.artist().isBlank() ? "Unknown Artist" : result.artist(), 40), x + 46, lineTop + 16, COLOR_MUTED, false);
                guiGraphics.drawString(font, duration, x + w - 44, lineTop + 6, COLOR_MUTED, false);
            }
            guiGraphics.drawString(font, "Results: " + sourceSearchResults.size(), x + 8, listY + listH - 16, COLOR_MUTED, false);
        } else {
            guiGraphics.drawString(font, "Source Preview", x + 8, listY + 8, COLOR_ACCENT, false);
            guiGraphics.drawString(font, "This mode accepts direct media URLs and local file paths.", x + 8, listY + 22, COLOR_MUTED, false);
            guiGraphics.drawString(font, "Play/Queue/Save happens immediately without closing this menu.", x + 8, listY + 34, COLOR_MUTED, false);

            String sourceValue = urlInput == null ? "" : urlInput.getValue().trim();
            if (sourceValue.isBlank()) {
                guiGraphics.drawString(font, "No source entered yet.", x + 8, listY + 54, COLOR_MUTED, false);
            } else {
                guiGraphics.drawString(font, "Source:", x + 8, listY + 54, COLOR_ACCENT_ALT, false);
                guiGraphics.drawString(font, trim(sourceValue, 78), x + 56, listY + 54, COLOR_TEXT, false);
                guiGraphics.drawString(font, "Title: " + trim(titleInput == null ? "" : titleInput.getValue().trim(), 48), x + 8, listY + 68, COLOR_MUTED, false);
                guiGraphics.drawString(font, "Artist: " + trim(artistInput == null ? "" : artistInput.getValue().trim(), 48), x + 8, listY + 80, COLOR_MUTED, false);
            }
        }
    }

    private void renderPlaylistsTab(GuiGraphics guiGraphics) {
        if (playlistPage == PlaylistPage.IMPORT) {
            renderPlaylistImportPage(guiGraphics);
            return;
        }
        renderPlaylistBrowsePage(guiGraphics);
    }

    private void renderPlaylistBrowsePage(GuiGraphics guiGraphics) {
        int listY = contentY() + 56;
        int leftX = contentX();
        int leftW = 244;
        int rightX = leftX + leftW + 10;
        int rightW = contentW() - leftW - 10;
        int listH = contentH() - 92;

        guiGraphics.fill(leftX, listY, leftX + leftW, listY + listH, COLOR_CARD);
        guiGraphics.fill(rightX, listY, rightX + rightW, listY + listH, COLOR_CARD);
        drawOutline(guiGraphics, leftX, listY, leftW, listH, 0x6653768F);
        drawOutline(guiGraphics, rightX, listY, rightW, listH, 0x6653768F);

        guiGraphics.drawString(font, "Playlists", leftX + 8, listY + 8, COLOR_ACCENT, false);
        guiGraphics.drawString(font, "Tracks", rightX + 8, listY + 8, COLOR_ACCENT, false);

        List<SharedMediaSnapshot.PlaylistEntry> playlists = ClientMediaRepository.getInstance().getSortedPlaylists();
        int rowStartY = listY + 24;
        int maxPlaylistRows = playlistVisibleRows(listH - 18);
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
            String access = playlist.access == null ? "Private" : playlist.access.label();
            guiGraphics.drawString(font, trim(label, 23), leftX + 10, rowTextY(lineTop, PLAYLIST_ROW_HEIGHT), selected ? 0xFFFFFFFF : COLOR_TEXT, false);
            guiGraphics.drawString(font, trim(access, 8), leftX + leftW - 58, rowTextY(lineTop, PLAYLIST_ROW_HEIGHT), COLOR_MUTED, false);
        }

        List<SharedMediaSnapshot.MediaEntry> tracks = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId);
        int maxTrackRows = playlistTrackVisibleRows(listH - 18);
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

        SharedMediaSnapshot.PlaylistEntry selected = selectedPlaylistEntry(playlists);
        String owner = selected == null ? "-" : (selected.ownerName == null || selected.ownerName.isBlank() ? "Unknown" : selected.ownerName);
        String access = selected == null || selected.access == null ? "Private" : selected.access.label();
        String inviteSummary = selected == null || selected.invitedPlayerNames == null || selected.invitedPlayerNames.isEmpty()
                ? "none"
                : trim(String.join(", ", selected.invitedPlayerNames), 34);
        guiGraphics.drawString(font, "Playlists: " + playlists.size(), leftX + 8, listY + listH - 16, COLOR_MUTED, false);
        guiGraphics.drawString(font, "Songs: " + tracks.size(), rightX + 8, listY + listH - 28, COLOR_MUTED, false);
        guiGraphics.drawString(font, "Owner: " + owner + "  Access: " + access, rightX + 8, listY + listH - 18, COLOR_MUTED, false);
        guiGraphics.drawString(font, "Invites: " + inviteSummary, rightX + 8, listY + listH - 8, COLOR_MUTED, false);
    }

    private void renderPlaylistImportPage(GuiGraphics guiGraphics) {
        int x = contentX();
        int y = contentY();
        int w = contentW();
        int h = contentH();

        int sourcePanelY = y + 22;
        int sourcePanelH = 120;
        guiGraphics.fill(x, sourcePanelY, x + w, sourcePanelY + sourcePanelH, COLOR_CARD_SOFT);
        drawOutline(guiGraphics, x, sourcePanelY, w, sourcePanelH, 0x6653768F);
        guiGraphics.drawString(font, "Import Source: " + playlistImportSource.label, x + 8, sourcePanelY + 6, COLOR_ACCENT, false);
        guiGraphics.drawString(font, trim(playlistImportSource.helper, 66), x + 176, sourcePanelY + 6, COLOR_MUTED, false);

        int listY = y + 152;
        int listH = Math.max(SOURCE_ROW_HEIGHT + 36, h - 152);
        guiGraphics.fill(x, listY, x + w, listY + listH, COLOR_CARD);
        drawOutline(guiGraphics, x, listY, w, listH, 0x6653768F);

        int statusColor = playlistImportLoading ? COLOR_ACCENT_ALT : COLOR_MUTED;
        guiGraphics.drawString(font, trim(playlistImportStatus, 88), x + 8, listY + 8, statusColor, false);
        int rowStartY = listY + 24;

        if (playlistImportSource == PlaylistImportSource.YOUTUBE) {
            int maxRows = sourceVisibleRows(listH);
            int start = clampScroll(playlistImportScroll, importYoutubeTracks.size(), maxRows);
            playlistImportScroll = start;
            int end = Math.min(importYoutubeTracks.size(), start + maxRows);
            for (int i = 0, index = start; index < end; i++, index++) {
                LavaPlayerAccess.SearchResult track = importYoutubeTracks.get(index);
                int lineTop = rowStartY + i * SOURCE_ROW_HEIGHT;
                boolean selected = index == selectedImportIndex;
                if (selected) {
                    guiGraphics.fill(x + 6, lineTop - 1, x + w - 6, lineTop + SOURCE_ROW_HEIGHT - 1, 0xAA365D77);
                }
                String thumbnail = MediaMetadataResolver.bestThumbnail(track.thumbnail(), track.identifier());
                drawListThumbnail(guiGraphics, thumbnail, x + 10, rowThumbY(lineTop, SOURCE_ROW_HEIGHT, SOURCE_THUMB_SIZE), SOURCE_THUMB_SIZE);
                guiGraphics.drawString(font, trim(track.title(), 52), x + 46, lineTop + 6, selected ? 0xFFFFFFFF : COLOR_TEXT, false);
                guiGraphics.drawString(font, trim(track.artist().isBlank() ? "Unknown Artist" : track.artist(), 40), x + 46, lineTop + 16, COLOR_MUTED, false);
            }
            guiGraphics.drawString(font, "Tracks: " + importYoutubeTracks.size(), x + 8, listY + listH - 16, COLOR_MUTED, false);
            return;
        }

        List<SharedMediaSnapshot.PlaylistEntry> sources = getFilteredImportPlaylists();
        int maxRows = playlistImportVisibleRows(listH);
        int start = clampScroll(playlistImportScroll, sources.size(), maxRows);
        playlistImportScroll = start;
        int end = Math.min(sources.size(), start + maxRows);
        for (int i = 0, index = start; index < end; i++, index++) {
            SharedMediaSnapshot.PlaylistEntry playlist = sources.get(index);
            int lineTop = rowStartY + i * PLAYLIST_ROW_HEIGHT;
            boolean selected = index == selectedImportIndex;
            if (selected) {
                guiGraphics.fill(x + 6, lineTop - 1, x + w - 6, lineTop + PLAYLIST_ROW_HEIGHT - 1, 0xAA365D77);
            }
            String label = playlist.name == null || playlist.name.isBlank() ? playlist.id : playlist.name;
            int count = playlist.mediaIds == null ? 0 : playlist.mediaIds.size();
            String owner = playlist.ownerName == null || playlist.ownerName.isBlank() ? "Unknown" : playlist.ownerName;
            guiGraphics.drawString(font, trim(label, 44), x + 10, rowTextY(lineTop, PLAYLIST_ROW_HEIGHT), selected ? 0xFFFFFFFF : COLOR_TEXT, false);
            guiGraphics.drawString(font, "by " + trim(owner, 14) + " · " + count + " songs", x + w - 136, rowTextY(lineTop, PLAYLIST_ROW_HEIGHT), COLOR_MUTED, false);
        }
        guiGraphics.drawString(font, "Available: " + sources.size(), x + 8, listY + listH - 16, COLOR_MUTED, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == Tab.NOW && button == 0 && canSeekTimeline()
                && mouseX >= nowTimelineX() && mouseX <= nowTimelineX() + nowTimelineW()
                && mouseY >= nowTimelineY() && mouseY <= nowTimelineY() + nowTimelineH()) {
            timelineDragging = true;
            updateTimelinePreview(mouseX);
            return true;
        }
        timelinePreviewPositionMs = -1L;

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
            if (libraryView == LibraryView.SOURCES) {
                if (sourceMode == SourceMode.YOUTUBE_SEARCH) {
                    int x = contentX();
                    int y = contentY();
                    int w = contentW();
                    int h = contentH();
                    int listY = y + 152;
                    int listH = Math.max(SOURCE_ROW_HEIGHT + 36, h - 152);
                    int rowStartY = listY + 24;
                    if (mouseX >= x && mouseX <= x + w && mouseY >= rowStartY && mouseY <= rowStartY + listH - 24) {
                        int row = (int) ((mouseY - rowStartY) / SOURCE_ROW_HEIGHT);
                        int maxRows = sourceVisibleRows(listH);
                        int index = sourceResultScroll + row;
                        if (row >= 0 && row < maxRows && index >= 0 && index < sourceSearchResults.size()) {
                            selectedSourceResultIndex = index;
                            applySearchResultToInputs(sourceSearchResults.get(index));
                            return true;
                        }
                    }
                }
            } else {
                int x = contentX();
                int w = contentW();
                int controlsY = panelY + PANEL_HEIGHT - 34;
                int listY = contentY() + 24;
                int listH = Math.max(MEDIA_ROW_HEIGHT + 40, (controlsY - 8) - listY);
                int rowStartY = listY + 24;
                if (mouseX >= x && mouseX <= x + w && mouseY >= rowStartY && mouseY <= rowStartY + listH - 30) {
                    int row = (int) ((mouseY - rowStartY) / MEDIA_ROW_HEIGHT);
                    int maxRows = libraryVisibleRows(listH);
                    int index = libraryScroll + row;
                    int size = ClientMediaRepository.getInstance().getSortedLibrary().size();
                    if (row >= 0 && row < maxRows && index >= 0 && index < size) {
                        selectedLibraryIndex = index;
                        return true;
                    }
                }
            }
        }

        if (tab == Tab.PLAYLISTS) {
            if (playlistPage == PlaylistPage.IMPORT) {
                int x = contentX();
                int y = contentY();
                int w = contentW();
                int h = contentH();
                int listY = y + 152;
                int listH = Math.max(SOURCE_ROW_HEIGHT + 36, h - 152);
                int rowStartY = listY + 24;
                if (mouseX >= x && mouseX <= x + w && mouseY >= rowStartY && mouseY <= rowStartY + listH - 24) {
                    if (playlistImportSource == PlaylistImportSource.YOUTUBE) {
                        int row = (int) ((mouseY - rowStartY) / SOURCE_ROW_HEIGHT);
                        int maxRows = sourceVisibleRows(listH);
                        int index = playlistImportScroll + row;
                        if (row >= 0 && row < maxRows && index >= 0 && index < importYoutubeTracks.size()) {
                            selectedImportIndex = index;
                            return true;
                        }
                    } else {
                        List<SharedMediaSnapshot.PlaylistEntry> importable = getFilteredImportPlaylists();
                        int row = (int) ((mouseY - rowStartY) / PLAYLIST_ROW_HEIGHT);
                        int maxRows = playlistImportVisibleRows(listH);
                        int index = playlistImportScroll + row;
                        if (row >= 0 && row < maxRows && index >= 0 && index < importable.size()) {
                            selectedImportIndex = index;
                            return true;
                        }
                    }
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }

            int listY = contentY() + 56;
            int leftX = contentX();
            int leftW = 244;
            int rightX = leftX + leftW + 10;
            int rightW = contentW() - leftW - 10;
            int listH = contentH() - 92;
            int rowStartY = listY + 24;

            if (mouseX >= leftX && mouseX <= leftX + leftW && mouseY >= rowStartY && mouseY <= rowStartY + listH - 18) {
                int row = (int) ((mouseY - rowStartY) / PLAYLIST_ROW_HEIGHT);
                List<SharedMediaSnapshot.PlaylistEntry> playlists = ClientMediaRepository.getInstance().getSortedPlaylists();
                int maxRows = playlistVisibleRows(listH - 18);
                int index = playlistScroll + row;
                if (row >= 0 && row < maxRows && index >= 0 && index < playlists.size()) {
                    selectedPlaylistIndex = index;
                    selectedPlaylistId = playlists.get(index).id;
                    selectedPlaylistTrackIndex = -1;
                    String selectedName = playlists.get(index).name == null || playlists.get(index).name.isBlank()
                            ? playlists.get(index).id
                            : playlists.get(index).name;
                    draftPlaylistName = selectedName;
                    if (playlistNameInput != null) {
                        playlistNameInput.setValue(selectedName);
                    }
                    if (playlistInviteInput != null) {
                        playlistInviteInput.setValue(String.join(", ", ClientMediaRepository.getInstance().getPlaylistInvites(selectedPlaylistId)));
                    }
                    return true;
                }
            }

            if (mouseX >= rightX && mouseX <= rightX + rightW && mouseY >= rowStartY && mouseY <= rowStartY + listH - 18) {
                int row = (int) ((mouseY - rowStartY) / MEDIA_ROW_HEIGHT);
                List<SharedMediaSnapshot.MediaEntry> tracks = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId);
                int maxRows = playlistTrackVisibleRows(listH - 18);
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
            updateTimelinePreview(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (timelineDragging && button == 0) {
            seekFromTimelineMouse(mouseX);
            timelineDragging = false;
            timelinePreviewPositionMs = -1L;
            return true;
        }
        timelinePreviewPositionMs = -1L;
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
            if (libraryView == LibraryView.SOURCES) {
                if (sourceMode == SourceMode.YOUTUBE_SEARCH) {
                    int x = contentX();
                    int y = contentY();
                    int w = contentW();
                    int h = contentH();
                    int listY = y + 152;
                    int listH = Math.max(SOURCE_ROW_HEIGHT + 36, h - 152);
                    int rowStartY = listY + 24;
                    if (mouseX >= x && mouseX <= x + w && mouseY >= rowStartY && mouseY <= rowStartY + listH - 24) {
                        sourceResultScroll = clampScroll(sourceResultScroll + step, sourceSearchResults.size(), sourceVisibleRows(listH));
                        return true;
                    }
                }
            } else {
                int x = contentX();
                int w = contentW();
                int controlsY = panelY + PANEL_HEIGHT - 34;
                int listY = contentY() + 24;
                int listH = Math.max(MEDIA_ROW_HEIGHT + 40, (controlsY - 8) - listY);
                int rowStartY = listY + 24;
                if (mouseX >= x && mouseX <= x + w && mouseY >= rowStartY && mouseY <= rowStartY + listH - 30) {
                    int size = ClientMediaRepository.getInstance().getSortedLibrary().size();
                    libraryScroll = clampScroll(libraryScroll + step, size, libraryVisibleRows(listH));
                    return true;
                }
            }
        }

        if (tab == Tab.PLAYLISTS) {
            if (playlistPage == PlaylistPage.IMPORT) {
                int x = contentX();
                int y = contentY();
                int w = contentW();
                int h = contentH();
                int listY = y + 152;
                int listH = Math.max(SOURCE_ROW_HEIGHT + 36, h - 152);
                int rowStartY = listY + 24;
                if (mouseX >= x && mouseX <= x + w && mouseY >= rowStartY && mouseY <= rowStartY + listH - 24) {
                    if (playlistImportSource == PlaylistImportSource.YOUTUBE) {
                        playlistImportScroll = clampScroll(playlistImportScroll + step, importYoutubeTracks.size(), sourceVisibleRows(listH));
                        return true;
                    }
                    int size = getFilteredImportPlaylists().size();
                    playlistImportScroll = clampScroll(playlistImportScroll + step, size, playlistImportVisibleRows(listH));
                    return true;
                }
                return super.mouseScrolled(mouseX, mouseY, deltaY);
            }

            int listY = contentY() + 56;
            int leftX = contentX();
            int leftW = 244;
            int rightX = leftX + leftW + 10;
            int rightW = contentW() - leftW - 10;
            int listH = contentH() - 92;
            int rowStartY = listY + 24;

            if (mouseX >= leftX && mouseX <= leftX + leftW && mouseY >= rowStartY && mouseY <= rowStartY + listH - 18) {
                int size = ClientMediaRepository.getInstance().getSortedPlaylists().size();
                playlistScroll = clampScroll(playlistScroll + step, size, playlistVisibleRows(listH - 18));
                return true;
            }

            if (mouseX >= rightX && mouseX <= rightX + rightW && mouseY >= rowStartY && mouseY <= rowStartY + listH - 18) {
                int size = ClientMediaRepository.getInstance().getPlaylistMedia(selectedPlaylistId).size();
                playlistTrackScroll = clampScroll(playlistTrackScroll + step, size, playlistTrackVisibleRows(listH - 18));
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, deltaY);
    }

    private void playFromInputs() {
        String url = normalizeSourceIdentifier(urlInput == null ? "" : urlInput.getValue().trim());
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
        String url = normalizeSourceIdentifier(urlInput == null ? "" : urlInput.getValue().trim());
        if (url.isBlank()) {
            return;
        }

        String title = titleInput == null ? "" : titleInput.getValue().trim();
        String artist = artistInput == null ? "" : artistInput.getValue().trim();
        String thumbnail = thumbnailInput == null ? "" : thumbnailInput.getValue().trim();

        SharedMediaSnapshot.MediaEntry entry = ClientMediaRepository.getInstance().upsertPlaylistOnlyMedia(url, title, artist, thumbnail, List.of());
        ClientMediaRepository.getInstance().enqueue(entry.id);
        selectedQueueIndex = Math.max(0, ClientMediaRepository.getInstance().getQueueEntries().size() - 1);
        resolveMetadataAndApply(url, title, artist, thumbnail, false, true);
    }

    private void resolveSourceMetadata() {
        String url = normalizeSourceIdentifier(urlInput == null ? "" : urlInput.getValue().trim());
        if (url.isBlank()) {
            return;
        }

        String title = titleInput == null ? "" : titleInput.getValue().trim();
        String artist = artistInput == null ? "" : artistInput.getValue().trim();
        String thumbnail = thumbnailInput == null ? "" : thumbnailInput.getValue().trim();
        resolveMetadataAndApply(url, title, artist, thumbnail, false);
    }

    private void openSourcesPage() {
        libraryView = LibraryView.SOURCES;
        sourceStatusMessage = sourceMode == SourceMode.YOUTUBE_SEARCH
                ? "Enter a query and click Search."
                : sourceMode.helperText;
        rebuildRadioWidgets();
    }

    private void closeSourcesPage() {
        libraryView = LibraryView.BROWSE;
        rebuildRadioWidgets();
    }

    private void setSourceMode(SourceMode newMode) {
        if (sourceMode == newMode) {
            return;
        }
        sourceMode = newMode;
        draftTitle = "";
        draftArtist = "";
        draftThumbnail = "";
        if (titleInput != null) {
            titleInput.setValue("");
        }
        if (artistInput != null) {
            artistInput.setValue("");
        }
        if (thumbnailInput != null) {
            thumbnailInput.setValue("");
        }
        selectedSourceResultIndex = -1;
        sourceResultScroll = 0;
        sourceStatusMessage = sourceMode == SourceMode.YOUTUBE_SEARCH
                ? "Enter a query and click Search."
                : sourceMode.helperText;
        rebuildRadioWidgets();
    }

    private void searchYoutubeFromInput() {
        String query = urlInput == null ? "" : urlInput.getValue().trim();
        if (query.isBlank()) {
            sourceStatusMessage = "Enter a YouTube search query first.";
            return;
        }

        sourceSearchLoading = true;
        sourceStatusMessage = "Searching YouTube for: " + trim(query, 36);
        sourceSearchResults.clear();
        selectedSourceResultIndex = -1;
        sourceResultScroll = 0;

        LavaPlayerAccess.get().searchYoutube(query, 40).whenComplete((results, error) -> {
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                sourceSearchLoading = false;
                sourceSearchResults.clear();
                selectedSourceResultIndex = -1;
                sourceResultScroll = 0;

                if (error != null) {
                    sourceStatusMessage = "Search failed. Try another query/source.";
                    return;
                }

                if (results != null) {
                    sourceSearchResults.addAll(results);
                }
                if (sourceSearchResults.isEmpty()) {
                    sourceStatusMessage = "No results found.";
                    return;
                }

                selectedSourceResultIndex = 0;
                sourceStatusMessage = "Found " + sourceSearchResults.size() + " results.";

                applySearchResultToInputs(sourceSearchResults.get(0));
            });
        });
    }

    private void applySearchResultToInputs(LavaPlayerAccess.SearchResult selected) {
        if (selected == null) {
            return;
        }
        if (titleInput != null) {
            titleInput.setValue(selected.title() == null ? "" : selected.title());
        }
        if (artistInput != null) {
            artistInput.setValue(selected.artist() == null ? "" : selected.artist());
        }
        if (thumbnailInput != null) {
            thumbnailInput.setValue(MediaMetadataResolver.bestThumbnail(selected.thumbnail(), selected.identifier()));
        }
    }

    private void playSelectedSearchResult() {
        applySelectedSearchResult(SourceAction.PLAY);
    }

    private void queueSelectedSearchResult() {
        applySelectedSearchResult(SourceAction.QUEUE);
    }

    private void saveSelectedSearchResult() {
        applySelectedSearchResult(SourceAction.SAVE);
    }

    private void applySelectedSearchResult(SourceAction action) {
        LavaPlayerAccess.SearchResult selected = getSelectedSearchResult();
        if (selected == null) {
            sourceStatusMessage = "Select a result first.";
            return;
        }

        String titleOverride = titleInput == null ? "" : titleInput.getValue().trim();
        String artistOverride = artistInput == null ? "" : artistInput.getValue().trim();
        String thumbnailOverride = thumbnailInput == null ? "" : thumbnailInput.getValue().trim();

        String finalTitle = titleOverride.isBlank() ? selected.title() : titleOverride;
        String finalArtist = artistOverride.isBlank() ? selected.artist() : artistOverride;
        String finalThumbnail = thumbnailOverride.isBlank()
                ? MediaMetadataResolver.bestThumbnail(selected.thumbnail(), selected.identifier())
                : thumbnailOverride;

        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        SharedMediaSnapshot.MediaEntry entry;
        if (action == SourceAction.QUEUE) {
            entry = repository.upsertPlaylistOnlyMedia(
                    selected.identifier(),
                    finalTitle,
                    finalArtist,
                    finalThumbnail,
                    List.of()
            );
        } else {
            entry = repository.upsertMedia(
                    selected.identifier(),
                    finalTitle,
                    finalArtist,
                    finalThumbnail,
                    List.of()
            );
        }

        switch (action) {
            case PLAY -> {
                playEntry(entry, true);
                sourceStatusMessage = "Playing: " + trim(finalTitle, 34);
            }
            case QUEUE -> {
                ClientMediaRepository.getInstance().enqueue(entry.id);
                selectedQueueIndex = Math.max(0, ClientMediaRepository.getInstance().getQueueEntries().size() - 1);
                sourceStatusMessage = "Queued: " + trim(finalTitle, 34);
            }
            case SAVE -> sourceStatusMessage = "Saved: " + trim(finalTitle, 34);
        }
        selectLibraryEntry(entry.id);
    }

    private LavaPlayerAccess.SearchResult getSelectedSearchResult() {
        if (sourceSearchResults.isEmpty()) {
            return null;
        }
        if (selectedSourceResultIndex < 0 || selectedSourceResultIndex >= sourceSearchResults.size()) {
            selectedSourceResultIndex = 0;
        }
        return sourceSearchResults.get(selectedSourceResultIndex);
    }

    private void selectLibraryEntry(String mediaId) {
        if (mediaId == null || mediaId.isBlank()) {
            return;
        }
        List<SharedMediaSnapshot.MediaEntry> entries = ClientMediaRepository.getInstance().getSortedLibrary();
        for (int i = 0; i < entries.size(); i++) {
            SharedMediaSnapshot.MediaEntry entry = entries.get(i);
            if (mediaId.equals(entry.id)) {
                selectedLibraryIndex = i;
                return;
            }
        }
    }

    private String normalizeSourceIdentifier(String value) {
        String source = value == null ? "" : value.trim();
        if (source.isBlank()) {
            return "";
        }
        if (sourceMode == SourceMode.YOUTUBE_URL && source.matches("^[A-Za-z0-9_-]{11}$")) {
            return "https://www.youtube.com/watch?v=" + source;
        }
        return source;
    }

    private void playMedia(String url, String title, String artist, String thumbnail) {
        String displayTitle = title == null || title.isBlank() ? url : title;
        String safeArtist = artist == null ? "" : artist;
        String safeThumbnail = thumbnail == null ? "" : thumbnail;

        if (isBlockMode()) {
            sendBlockRadioControl(ServerboundRadioControlMessage.Action.PLAY_URL, url, displayTitle, safeArtist, safeThumbnail, 0L);
        } else {
            ClientAudioEngine.getInstance().playHandheld(url, 0L, hand, displayTitle, safeArtist, safeThumbnail);
        }
    }

    private void stopPlayback() {
        if (isBlockMode()) {
            sendBlockRadioControl(ServerboundRadioControlMessage.Action.STOP, "", "", "", "", 0L);
        } else {
            ClientAudioEngine.getInstance().stopHandheld();
            ClientAudioEngine.getInstance().clearHandheldState();
        }
        timelinePreviewPositionMs = -1L;
        lastKnownDurationMs = -1L;
        persistRuntimeState();
    }

    private void togglePause() {
        if (isBlockMode()) {
            sendBlockRadioControl(ServerboundRadioControlMessage.Action.TOGGLE_PAUSE, "", "", "", "", 0L);
        } else {
            ClientAudioEngine.getInstance().togglePauseHandheld();
        }
        updatePauseResumeButtonLabel();
        updateLoopModeButtonLabel();
    }

    private void addInputToLibrary() {
        String url = normalizeSourceIdentifier(urlInput == null ? "" : urlInput.getValue().trim());
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
        selectedPlaylistId = ClientMediaRepository.getInstance().createPlaylist(name, SharedMediaSnapshot.PlaylistAccess.PRIVATE);
        draftPlaylistName = "";
        draftPlaylistInvites = "";
        playlistNameInput.setValue("");
        if (playlistInviteInput != null) {
            playlistInviteInput.setValue("");
        }
        selectedPlaylistTrackIndex = -1;
        playlistImportStatus = "Created playlist: " + trim(name, 32);
    }

    private void renameSelectedPlaylist() {
        if (playlistNameInput == null || selectedPlaylistId.isBlank()) {
            return;
        }
        String newName = playlistNameInput.getValue().trim();
        if (newName.isBlank()) {
            return;
        }
        boolean renamed = ClientMediaRepository.getInstance().renamePlaylist(selectedPlaylistId, newName);
        if (!renamed) {
            return;
        }
        draftPlaylistName = newName;
        playlistImportStatus = "Renamed playlist: " + trim(newName, 32);
    }

    private void deleteSelectedPlaylist() {
        if (selectedPlaylistId.isBlank() || !ClientMediaRepository.getInstance().canEditPlaylist(selectedPlaylistId)) {
            return;
        }
        ClientMediaRepository.getInstance().deletePlaylist(selectedPlaylistId);
        selectedPlaylistId = "";
        selectedPlaylistIndex = -1;
        selectedPlaylistTrackIndex = -1;
        draftPlaylistName = "";
        draftPlaylistInvites = "";
        if (playlistNameInput != null) {
            playlistNameInput.setValue("");
        }
        if (playlistInviteInput != null) {
            playlistInviteInput.setValue("");
        }
        updatePlaylistBrowseButtonStates();
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

    private void cycleSelectedPlaylistAccess() {
        if (selectedPlaylistId.isBlank()) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        SharedMediaSnapshot.PlaylistAccess current = repository.getPlaylistAccess(selectedPlaylistId);
        SharedMediaSnapshot.PlaylistAccess next = switch (current) {
            case PRIVATE -> SharedMediaSnapshot.PlaylistAccess.INVITES;
            case INVITES -> SharedMediaSnapshot.PlaylistAccess.GLOBAL;
            case GLOBAL -> SharedMediaSnapshot.PlaylistAccess.PRIVATE;
        };
        repository.setPlaylistAccess(selectedPlaylistId, next);
        updatePlaylistAccessButtonLabel();
    }

    private void applyPlaylistInvites() {
        if (selectedPlaylistId.isBlank() || playlistInviteInput == null) {
            return;
        }
        String raw = playlistInviteInput.getValue().trim();
        List<String> invites = new ArrayList<>();
        if (!raw.isBlank()) {
            String[] parts = raw.split(",");
            for (String part : parts) {
                String name = part == null ? "" : part.trim();
                if (!name.isBlank() && !invites.contains(name)) {
                    invites.add(name);
                }
            }
        }
        ClientMediaRepository.getInstance().setPlaylistInvites(selectedPlaylistId, invites);
    }

    private void openPlaylistImportPage() {
        playlistPage = PlaylistPage.IMPORT;
        selectedImportIndex = -1;
        playlistImportScroll = 0;
        playlistImportStatus = "Pick an import source to begin.";
        refreshPlaylistImportLists();
        rebuildRadioWidgets();
    }

    private void closePlaylistImportPage() {
        playlistPage = PlaylistPage.BROWSE;
        rebuildRadioWidgets();
    }

    private void setPlaylistImportSource(PlaylistImportSource source) {
        if (playlistImportSource == source) {
            return;
        }
        playlistImportSource = source;
        selectedImportIndex = -1;
        playlistImportScroll = 0;
        importYoutubeTracks.clear();
        playlistImportStatus = source == PlaylistImportSource.YOUTUBE
                ? "Paste a YouTube playlist URL, then load."
                : "Select a playlist and import a local copy.";
        rebuildRadioWidgets();
    }

    private void refreshPlaylistImportLists() {
        selectedImportIndex = -1;
        playlistImportScroll = 0;
        if (playlistImportSource == PlaylistImportSource.GLOBAL) {
            int count = ClientMediaRepository.getInstance().getImportableGlobalPlaylists().size();
            playlistImportStatus = "Global playlists available: " + count;
        } else if (playlistImportSource == PlaylistImportSource.INVITES) {
            int count = ClientMediaRepository.getInstance().getImportableInvitedPlaylists().size();
            playlistImportStatus = "Invited playlists available: " + count;
        }
    }

    private void loadYoutubePlaylistForImport() {
        String identifier = importPlaylistSourceInput == null ? "" : importPlaylistSourceInput.getValue().trim();
        if (identifier.isBlank()) {
            playlistImportStatus = "Enter a YouTube playlist URL first.";
            return;
        }
        playlistImportLoading = true;
        playlistImportStatus = "Loading playlist tracks...";
        importYoutubeTracks.clear();
        selectedImportIndex = -1;
        playlistImportScroll = 0;

        LavaPlayerAccess.get().loadPlaylistTracks(identifier, 200).whenComplete((tracks, error) -> {
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                playlistImportLoading = false;
                importYoutubeTracks.clear();
                selectedImportIndex = -1;
                playlistImportScroll = 0;
                if (error != null) {
                    playlistImportStatus = "Failed to load playlist.";
                    return;
                }
                if (tracks != null) {
                    importYoutubeTracks.addAll(tracks);
                }
                if (importYoutubeTracks.isEmpty()) {
                    playlistImportStatus = "No tracks found.";
                    return;
                }
                selectedImportIndex = 0;
                playlistImportStatus = "Loaded " + importYoutubeTracks.size() + " tracks.";
            });
        });
    }

    private void importYoutubePlaylistAsNew() {
        if (importYoutubeTracks.isEmpty()) {
            playlistImportStatus = "Load a YouTube playlist first.";
            return;
        }
        String name = importPlaylistNameInput == null ? "" : importPlaylistNameInput.getValue().trim();
        if (name.isBlank()) {
            name = "Imported YouTube Playlist";
        }

        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        String playlistId = repository.createPlaylist(name, SharedMediaSnapshot.PlaylistAccess.PRIVATE);
        for (LavaPlayerAccess.SearchResult track : importYoutubeTracks) {
            SharedMediaSnapshot.MediaEntry entry = repository.upsertPlaylistOnlyMedia(
                    track.identifier(),
                    track.title(),
                    track.artist(),
                    MediaMetadataResolver.bestThumbnail(track.thumbnail(), track.identifier()),
                    List.of()
            );
            repository.addMediaToPlaylist(playlistId, entry.id);
        }
        selectedPlaylistId = playlistId;
        playlistPage = PlaylistPage.BROWSE;
        selectedPlaylistTrackIndex = -1;
        playlistImportStatus = "Imported playlist: " + trim(name, 32);
        rebuildRadioWidgets();
    }

    private void importSelectedSharedPlaylistCopy() {
        List<SharedMediaSnapshot.PlaylistEntry> candidates = getFilteredImportPlaylists();
        if (selectedImportIndex < 0 || selectedImportIndex >= candidates.size()) {
            playlistImportStatus = "Select a playlist to import.";
            return;
        }
        SharedMediaSnapshot.PlaylistEntry source = candidates.get(selectedImportIndex);
        String desiredName = importPlaylistNameInput == null ? "" : importPlaylistNameInput.getValue().trim();
        String playlistId = ClientMediaRepository.getInstance().importPlaylistCopy(
                source.id,
                desiredName,
                SharedMediaSnapshot.PlaylistAccess.PRIVATE
        );
        if (playlistId.isBlank()) {
            playlistImportStatus = "Import failed.";
            return;
        }
        selectedPlaylistId = playlistId;
        selectedPlaylistTrackIndex = -1;
        playlistPage = PlaylistPage.BROWSE;
        playlistImportStatus = "Imported: " + trim(source.name == null || source.name.isBlank() ? source.id : source.name, 32);
        rebuildRadioWidgets();
    }

    private List<SharedMediaSnapshot.PlaylistEntry> getFilteredImportPlaylists() {
        List<SharedMediaSnapshot.PlaylistEntry> base = playlistImportSource == PlaylistImportSource.GLOBAL
                ? ClientMediaRepository.getInstance().getImportableGlobalPlaylists()
                : ClientMediaRepository.getInstance().getImportableInvitedPlaylists();
        String filter = importPlaylistSourceInput == null ? "" : importPlaylistSourceInput.getValue().trim().toLowerCase(Locale.ROOT);
        if (filter.isBlank()) {
            return base;
        }
        List<SharedMediaSnapshot.PlaylistEntry> filtered = new ArrayList<>();
        for (SharedMediaSnapshot.PlaylistEntry playlist : base) {
            if (playlist == null) {
                continue;
            }
            String playlistName = playlist.name == null ? "" : playlist.name.toLowerCase(Locale.ROOT);
            String ownerName = playlist.ownerName == null ? "" : playlist.ownerName.toLowerCase(Locale.ROOT);
            if (playlistName.contains(filter) || ownerName.contains(filter)) {
                filtered.add(playlist);
            }
        }
        return filtered;
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
            if (blockEntity != null) {
                return removedEntry.url.equals(blockEntity.getMediaUrl())
                        && (blockEntity.isPlaying() || blockEntity.getPlaybackPositionMs() > 0L);
            }
            String radioId = getTargetBlockRadioId();
            ClientAudioEngine.HandheldRenderState state = radioId.isBlank()
                    ? null
                    : ClientAudioEngine.getInstance().getRenderStateForRadioId(radioId);
            return state != null
                    && removedEntry.url.equals(state.url())
                    && (state.playing() || state.paused() || state.positionMs() > 0L);
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
            sendBlockRadioControl(
                    ServerboundRadioControlMessage.Action.PLAY_URL,
                    entry.url,
                    entry.title,
                    entry.artist,
                    entry.thumbnail,
                    0L
            );
        } else {
            ClientAudioEngine.getInstance().playHandheld(entry.url, 0L, hand, entry.title, entry.artist, entry.thumbnail);
        }
        resolveMetadataAndApply(entry.url, entry.title, entry.artist, entry.thumbnail, false, entry.hiddenFromLibrary);
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
        resolveMetadataAndApply(url, title, artist, thumbnail, updateCurrentPlayback, false);
    }

    private void resolveMetadataAndApply(String url, String title, String artist, String thumbnail, boolean updateCurrentPlayback, boolean preferPlaylistOnly) {
        MediaMetadataResolver.resolve(url, title, artist, thumbnail).thenAccept(resolved -> {
            if (minecraft == null) {
                return;
            }
            minecraft.execute(() -> {
                String finalUrl = resolved.url().isBlank() ? url : resolved.url();
                if (!finalUrl.isBlank()) {
                    ClientMediaRepository repository = ClientMediaRepository.getInstance();
                    SharedMediaSnapshot.MediaEntry existing = repository.findByUrl(finalUrl);
                    boolean keepOutOfLibrary = preferPlaylistOnly || (existing != null && existing.hiddenFromLibrary);
                    if (keepOutOfLibrary) {
                        repository.upsertPlaylistOnlyMedia(finalUrl, resolved.title(), resolved.artist(), resolved.thumbnail(), List.of());
                    } else {
                        repository.upsertMedia(finalUrl, resolved.title(), resolved.artist(), resolved.thumbnail(), List.of());
                    }
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
                    sendBlockRadioControl(
                            ServerboundRadioControlMessage.Action.UPDATE_METADATA,
                            finalUrl,
                            resolved.title(),
                            resolved.artist(),
                            resolved.thumbnail(),
                            0L
                    );
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
            sendBlockRadioControl(ServerboundRadioControlMessage.Action.SET_VOLUME, "", "", "", "", 0L);
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
        if (libraryView == LibraryView.BROWSE) {
            int controlsY = panelY + PANEL_HEIGHT - 34;
            int listY = contentY() + 24;
            int listH = Math.max(MEDIA_ROW_HEIGHT + 40, (controlsY - 8) - listY);
            libraryScroll = clampScroll(libraryScroll, library.size(), libraryVisibleRows(listH));
        }
        if (sourceMode == SourceMode.YOUTUBE_SEARCH) {
            if (selectedSourceResultIndex >= sourceSearchResults.size()) {
                selectedSourceResultIndex = sourceSearchResults.isEmpty() ? -1 : sourceSearchResults.size() - 1;
            }
            int sourceListH = Math.max(SOURCE_ROW_HEIGHT + 36, contentH() - 152);
            sourceResultScroll = clampScroll(sourceResultScroll, sourceSearchResults.size(), sourceVisibleRows(sourceListH));
        }

        List<SharedMediaSnapshot.PlaylistEntry> playlists = repository.getSortedPlaylists();
        SharedMediaSnapshot.PlaylistEntry selectedPlaylistEntry = null;
        if (!selectedPlaylistId.isBlank()) {
            boolean exists = false;
            for (int i = 0; i < playlists.size(); i++) {
                if (selectedPlaylistId.equals(playlists.get(i).id)) {
                    selectedPlaylistIndex = i;
                    selectedPlaylistEntry = playlists.get(i);
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
            selectedPlaylistEntry = playlists.get(selectedPlaylistIndex);
        } else if (selectedPlaylistIndex >= playlists.size()) {
            selectedPlaylistIndex = playlists.isEmpty() ? -1 : playlists.size() - 1;
            selectedPlaylistId = selectedPlaylistIndex >= 0 ? playlists.get(selectedPlaylistIndex).id : "";
            if (selectedPlaylistIndex >= 0) {
                selectedPlaylistEntry = playlists.get(selectedPlaylistIndex);
            }
        }
        if (selectedPlaylistEntry == null && selectedPlaylistIndex >= 0 && selectedPlaylistIndex < playlists.size()) {
            selectedPlaylistEntry = playlists.get(selectedPlaylistIndex);
        }
        if (playlistNameInput != null && !playlistNameInput.isFocused()) {
            String selectedName = selectedPlaylistEntry == null
                    ? draftPlaylistName
                    : (selectedPlaylistEntry.name == null || selectedPlaylistEntry.name.isBlank()
                    ? selectedPlaylistEntry.id
                    : selectedPlaylistEntry.name);
            if (selectedPlaylistEntry != null) {
                draftPlaylistName = selectedName;
            }
            if (!selectedName.equals(playlistNameInput.getValue())) {
                playlistNameInput.setValue(selectedName);
            }
        }
        int playlistListH = contentH() - 92;
        playlistScroll = clampScroll(playlistScroll, playlists.size(), playlistVisibleRows(Math.max(1, playlistListH - 18)));

        List<SharedMediaSnapshot.MediaEntry> playlistTracks = repository.getPlaylistMedia(selectedPlaylistId);
        if (selectedPlaylistTrackIndex >= playlistTracks.size()) {
            selectedPlaylistTrackIndex = playlistTracks.isEmpty() ? -1 : playlistTracks.size() - 1;
        }
        playlistTrackScroll = clampScroll(playlistTrackScroll, playlistTracks.size(), playlistTrackVisibleRows(Math.max(1, playlistListH - 18)));
        if (playlistInviteInput != null) {
            List<String> invites = repository.getPlaylistInvites(selectedPlaylistId);
            String inviteText = String.join(", ", invites);
            if (!playlistInviteInput.isFocused()) {
                playlistInviteInput.setValue(inviteText);
            }
        }

        if (playlistPage == PlaylistPage.IMPORT) {
            if (playlistImportSource == PlaylistImportSource.YOUTUBE) {
                if (selectedImportIndex >= importYoutubeTracks.size()) {
                    selectedImportIndex = importYoutubeTracks.isEmpty() ? -1 : importYoutubeTracks.size() - 1;
                }
                int importH = Math.max(SOURCE_ROW_HEIGHT + 36, contentH() - 152);
                playlistImportScroll = clampScroll(playlistImportScroll, importYoutubeTracks.size(), sourceVisibleRows(importH));
            } else {
                List<SharedMediaSnapshot.PlaylistEntry> importable = playlistImportSource == PlaylistImportSource.GLOBAL
                        ? repository.getImportableGlobalPlaylists()
                        : repository.getImportableInvitedPlaylists();
                if (selectedImportIndex >= importable.size()) {
                    selectedImportIndex = importable.isEmpty() ? -1 : importable.size() - 1;
                }
                int importH = Math.max(SOURCE_ROW_HEIGHT + 36, contentH() - 152);
                playlistImportScroll = clampScroll(playlistImportScroll, importable.size(), playlistImportVisibleRows(importH));
            }
        }

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
        PlaybackView playbackView;
        if (isBlockMode()) {
            String boundRadioId = getTargetBlockRadioId();
            if (boundRadioId != null && !boundRadioId.isBlank()) {
                ClientAudioEngine.HandheldRenderState runtime = ClientAudioEngine.getInstance().getRenderStateForRadioId(boundRadioId);
                SharedMediaSnapshot.MediaEntry queueCurrent = ClientMediaRepository.getInstance().getCurrentQueueEntry();
                if (runtime != null) {
                    PlaybackDisplayResolver.DisplayInfo displayInfo = PlaybackDisplayResolver.resolve(
                            runtime.url(),
                            runtime.title(),
                            runtime.artist(),
                            runtime.thumbnail(),
                            queueCurrent
                    );
                    String title = displayInfo.title();
                    if (title == null || title.isBlank()) {
                        title = "Nothing queued";
                    }
                    String state;
                    if (runtime.playing()) {
                        state = "Playing";
                    } else if (runtime.paused()) {
                        state = "Paused";
                    } else {
                        state = "Stopped";
                    }
                    playbackView = new PlaybackView(
                            state,
                            title,
                            displayInfo.artist(),
                            displayInfo.thumbnail(),
                            runtime.positionMs(),
                            runtime.durationMs(),
                            runtime.volume()
                    );
                    return finalizePlaybackView(playbackView);
                }
            }

            RadioBlockEntity blockEntity = getBlockEntity();
            if (blockEntity == null) {
                playbackView = new PlaybackView("Stopped", "No radio data", "", "", 0L, -1L, blockVolume);
                return finalizePlaybackView(playbackView);
            }

            String title = blockEntity.getMediaTitle();
            if (title == null || title.isBlank()) {
                title = blockEntity.getMediaUrl().isBlank() ? "Nothing queued" : blockEntity.getMediaUrl();
            }
            PlaybackDisplayResolver.DisplayInfo displayInfo = PlaybackDisplayResolver.resolve(
                    blockEntity.getMediaUrl(),
                    title,
                    blockEntity.getMediaArtist(),
                    blockEntity.getMediaThumbnail(),
                    ClientMediaRepository.getInstance().getCurrentQueueEntry()
            );
            String state = blockEntity.getMediaUrl().isBlank() ? "Stopped" : (blockEntity.isPlaying() ? "Playing" : "Paused");
            long channelPositionMs = blockPos == null ? -1L : ClientAudioEngine.getInstance().getBlockPlaybackPositionMs(blockPos);
            long channelDurationMs = blockPos == null ? -1L : ClientAudioEngine.getInstance().getBlockTrackDurationMs(blockPos);
            long positionMs = channelPositionMs >= 0L ? channelPositionMs : blockEntity.getPlaybackPositionMs();
            playbackView = new PlaybackView(
                    state,
                    displayInfo.title(),
                    displayInfo.artist(),
                    displayInfo.thumbnail(),
                    positionMs,
                    channelDurationMs,
                    blockEntity.getVolume()
            );
            return finalizePlaybackView(playbackView);
        }

        ClientAudioEngine audioEngine = ClientAudioEngine.getInstance();
        PlaybackDisplayResolver.DisplayInfo displayInfo = PlaybackDisplayResolver.resolve(
                audioEngine.getHandheldUrl(),
                audioEngine.getHandheldNowPlaying(),
                audioEngine.getHandheldArtist(),
                audioEngine.getHandheldThumbnail(),
                ClientMediaRepository.getInstance().getCurrentQueueEntry()
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

        playbackView = new PlaybackView(
                state,
                title,
                displayInfo.artist(),
                displayInfo.thumbnail(),
                audioEngine.getHandheldPlaybackPositionMs(),
                audioEngine.getHandheldTrackDurationMs(),
                audioEngine.getHandheldVolume()
        );
        return finalizePlaybackView(playbackView);
    }

    private PlaybackView finalizePlaybackView(PlaybackView base) {
        long duration = base.durationMs();
        if (duration > 0L) {
            lastKnownDurationMs = duration;
        } else if (!"Stopped".equals(base.state()) && lastKnownDurationMs > 0L) {
            duration = lastKnownDurationMs;
        }

        long position = Math.max(0L, base.positionMs());
        if (timelineDragging && timelinePreviewPositionMs >= 0L) {
            position = timelinePreviewPositionMs;
        }

        if (duration > 0L) {
            position = Math.min(position, duration);
        }

        if (duration == base.durationMs() && position == base.positionMs()) {
            return base;
        }
        return new PlaybackView(
                base.state(),
                base.title(),
                base.artist(),
                base.thumbnail(),
                position,
                duration,
                base.volume()
        );
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

    private void updatePlaylistAccessButtonLabel() {
        if (playlistAccessButton == null || tab != Tab.PLAYLISTS || playlistPage != PlaylistPage.BROWSE) {
            return;
        }
        SharedMediaSnapshot.PlaylistAccess access = ClientMediaRepository.getInstance().getPlaylistAccess(selectedPlaylistId);
        playlistAccessButton.setMessage(Component.literal("Access: " + access.label()));
    }

    private void updatePlaylistBrowseButtonStates() {
        if (tab != Tab.PLAYLISTS || playlistPage != PlaylistPage.BROWSE) {
            return;
        }

        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        boolean hasSelectedPlaylist = !selectedPlaylistId.isBlank();
        boolean canEditSelected = hasSelectedPlaylist && repository.canEditPlaylist(selectedPlaylistId);
        boolean hasSelectedTrack = selectedPlaylistTrackIndex >= 0 && selectedPlaylistTrackIndex < repository.getPlaylistMedia(selectedPlaylistId).size();

        if (playlistRenameButton != null) {
            playlistRenameButton.active = canEditSelected;
        }
        if (playlistDeleteButton != null) {
            playlistDeleteButton.active = canEditSelected;
        }
        if (playlistInviteApplyButton != null) {
            playlistInviteApplyButton.active = canEditSelected;
        }
        if (playlistAccessButton != null) {
            playlistAccessButton.active = canEditSelected;
        }
        if (playlistRemoveSongButton != null) {
            playlistRemoveSongButton.active = canEditSelected && hasSelectedTrack;
        }
    }

    private void drawTimelineBar(GuiGraphics guiGraphics, PlaybackView playback, int x, int y, int width, int height) {
        int barX = nowTimelineX();
        int barY = nowTimelineY();
        int barW = nowTimelineW();
        int barH = nowTimelineH();

        guiGraphics.fill(barX, barY, barX + barW, barY + barH, 0xAA12202D);
        drawOutline(guiGraphics, barX, barY, barW, barH, 0x88597C98);

        if (playback.durationMs() <= 0L) {
            guiGraphics.fill(barX + 1, barY + 1, barX + 2, barY + barH - 1, 0xCC4FA2CB);
            return;
        }

        float progress = Mth.clamp(playback.positionMs() / (float) playback.durationMs(), 0f, 1f);
        int filled = Math.max(1, Math.round((barW - 2) * progress));
        guiGraphics.fill(barX + 1, barY + 1, barX + 1 + filled, barY + barH - 1, 0xCC4FA2CB);

        int knobX = barX + 1 + filled;
        guiGraphics.fill(knobX - 1, barY - 2, knobX + 1, barY + barH + 2, COLOR_ACCENT_ALT);
    }

    private boolean canSeekTimeline() {
        return getPlaybackView().durationMs() > 0L;
    }

    private void seekFromTimelineMouse(double mouseX) {
        PlaybackView playback = getPlaybackView();
        long duration = playback.durationMs();
        if (duration <= 0L) {
            return;
        }
        int barX = nowTimelineX();
        int barW = nowTimelineW();
        float pct = (float) ((mouseX - barX) / Math.max(1.0, barW));
        pct = Mth.clamp(pct, 0f, 1f);
        long target = (long) (duration * pct);
        if (isBlockMode()) {
            sendBlockRadioControl(ServerboundRadioControlMessage.Action.SEEK, "", "", "", "", target);
            return;
        }
        ClientAudioEngine.getInstance().seekHandheld(target);
    }

    private void updateTimelinePreview(double mouseX) {
        PlaybackView playback = getPlaybackView();
        long duration = playback.durationMs();
        if (duration <= 0L) {
            timelinePreviewPositionMs = -1L;
            return;
        }
        int barX = nowTimelineX();
        int barW = nowTimelineW();
        float pct = (float) ((mouseX - barX) / Math.max(1.0, barW));
        pct = Mth.clamp(pct, 0f, 1f);
        timelinePreviewPositionMs = (long) (duration * pct);
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

    private int sourceVisibleRows(int listH) {
        return Math.max(1, (listH - 32) / SOURCE_ROW_HEIGHT);
    }

    private int playlistVisibleRows(int listH) {
        return Math.max(1, (listH - 30) / PLAYLIST_ROW_HEIGHT);
    }

    private int playlistTrackVisibleRows(int listH) {
        return Math.max(1, (listH - 30) / MEDIA_ROW_HEIGHT);
    }

    private int playlistImportVisibleRows(int listH) {
        return Math.max(1, (listH - 30) / PLAYLIST_ROW_HEIGHT);
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
        return blockPos != null || !fixedBlockRadioId.isBlank();
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
            String radioId = getTargetBlockRadioId();
            if (radioId == null || radioId.isBlank()) {
                return;
            }
            if (!radioId.equals(repository.getActiveRadioId())) {
                repository.setActiveRadioId(radioId);
            }
            String queueStateJson = repository.exportActiveQueueStateJson();
            if (!queueStateJson.equals(lastPersistedQueueState)) {
                sendBlockRadioControl(ServerboundRadioControlMessage.Action.UPDATE_QUEUE_STATE, queueStateJson, "", "", "", 0L);
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
        // ClientAudioEngine is the authoritative handheld runtime uploader.
        // Avoid duplicate uploads here to prevent out-of-order state churn.
        lastPersistedRuntimeKey = key;
    }

    private void syncBlockRadioContext() {
        if (!isBlockMode()) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        if (!fixedBlockRadioId.isBlank()) {
            if (!fixedBlockRadioId.equals(repository.getActiveRadioId())) {
                repository.setActiveRadioId(fixedBlockRadioId);
            }
            if (!fixedBlockRadioId.equals(lastBoundBlockRadioId)) {
                lastPersistedQueueState = "";
                lastBoundBlockRadioId = fixedBlockRadioId;
                ModNetworking.requestRadioState(fixedBlockRadioId);
            }
            return;
        }
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
        if (!fixedBlockRadioId.isBlank()) {
            return "contraption:" + fixedBlockRadioId;
        }
        if (blockPos == null) {
            return "block:unknown";
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            return "block:" + minecraft.level.dimension().location() + ":" + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
        }
        return "block:" + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ();
    }

    private String getTargetBlockRadioId() {
        if (!fixedBlockRadioId.isBlank()) {
            return fixedBlockRadioId;
        }
        RadioBlockEntity blockEntity = getBlockEntity();
        if (blockEntity == null) {
            return lastBoundBlockRadioId == null ? "" : lastBoundBlockRadioId;
        }
        String radioId = blockEntity.getRadioId();
        if (radioId == null || radioId.isBlank()) {
            return lastBoundBlockRadioId == null ? "" : lastBoundBlockRadioId;
        }
        return radioId;
    }

    private void sendBlockRadioControl(
            ServerboundRadioControlMessage.Action action,
            String url,
            String title,
            String artist,
            String thumbnail,
            long positionMs
    ) {
        ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                blockPos,
                getTargetBlockRadioId(),
                action,
                url,
                title,
                artist,
                thumbnail,
                blockVolume,
                positionMs
        ));

        if (fixedBlockRadioId.isBlank()) {
            return;
        }

        ClientAudioEngine audioEngine = ClientAudioEngine.getInstance();
        audioEngine.setExternalContext(fixedBlockRadioId, contraptionEntityId, contraptionLocalPos);
        switch (action) {
            case PLAY_URL -> {
                String displayTitle = title == null || title.isBlank() ? url : title;
                audioEngine.playExternal(fixedBlockRadioId, url, Math.max(0L, positionMs), displayTitle, artist, thumbnail);
            }
            case UPDATE_METADATA -> audioEngine.updateExternalMetadata(fixedBlockRadioId, title, artist, thumbnail);
            case TOGGLE_PAUSE -> audioEngine.togglePauseExternal(fixedBlockRadioId);
            case STOP -> audioEngine.stopExternal(fixedBlockRadioId);
            case SET_VOLUME -> audioEngine.setExternalVolume(fixedBlockRadioId, blockVolume);
            case SEEK -> audioEngine.seekExternal(fixedBlockRadioId, positionMs);
            case UPDATE_QUEUE_STATE -> {
                // Queue state is already maintained in the shared client repository.
            }
            case SYNC_RUNTIME -> {
                // Runtime sync is server-authoritative bookkeeping only.
            }
        }
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

    private SharedMediaSnapshot.PlaylistEntry selectedPlaylistEntry(List<SharedMediaSnapshot.PlaylistEntry> playlists) {
        if (playlists == null || playlists.isEmpty() || selectedPlaylistIndex < 0 || selectedPlaylistIndex >= playlists.size()) {
            return null;
        }
        return playlists.get(selectedPlaylistIndex);
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
            // EditBox without vanilla border starts text at the hard left edge; apply inner padding.
            int textXOffset = 3;
            // EditBox without vanilla border renders text too high; center it in our custom field.
            int textYOffset = Math.max(0, (height - 8) / 2 - 1);
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(textXOffset, textYOffset, 0.0f);
            super.renderWidget(guiGraphics, mouseX - textXOffset, mouseY - textYOffset, partialTick);
            guiGraphics.pose().popPose();
        }
    }

    private enum Tab {
        NOW,
        LIBRARY,
        PLAYLISTS
    }

    private enum LibraryView {
        BROWSE,
        SOURCES
    }

    private enum PlaylistPage {
        BROWSE,
        IMPORT
    }

    private enum PlaylistImportSource {
        YOUTUBE(
                "YouTube",
                "Paste a YouTube playlist URL to import tracks into a new playlist.",
                "YouTube playlist URL",
                "https://www.youtube.com/playlist?list=..."
        ),
        GLOBAL(
                "Global",
                "Import a copy of playlists shared globally by other players.",
                "Filter by playlist or owner (optional)",
                "Type playlist/owner text to filter"
        ),
        INVITES(
                "Invites",
                "Import playlists you were invited to by other players.",
                "Filter by playlist or owner (optional)",
                "Type playlist/owner text to filter"
        );

        private final String label;
        private final String helper;
        private final String sourceLabel;
        private final String sourceHint;

        PlaylistImportSource(String label, String helper, String sourceLabel, String sourceHint) {
            this.label = label;
            this.helper = helper;
            this.sourceLabel = sourceLabel;
            this.sourceHint = sourceHint;
        }
    }

    private enum SourceAction {
        PLAY,
        QUEUE,
        SAVE
    }

    private enum SourceMode {
        YOUTUBE_SEARCH(
                "YouTube Search",
                "Search YouTube and add results directly to your library/queue.",
                "Search YouTube",
                "Type a song, artist, or playlist query"
        ),
        YOUTUBE_URL(
                "YouTube URL",
                "Paste a YouTube URL (or video id) to add playback sources fast.",
                "YouTube URL or video id",
                "https://www.youtube.com/watch?v=..."
        ),
        DIRECT_URL_OR_FILE(
                "Direct URL/File",
                "Use direct media URLs, stream endpoints, or local file paths.",
                "Direct URL or local file path",
                "https://... or /path/to/file.mp3"
        );

        private final String displayName;
        private final String helperText;
        private final String inputLabel;
        private final String inputHint;

        SourceMode(String displayName, String helperText, String inputLabel, String inputHint) {
            this.displayName = displayName;
            this.helperText = helperText;
            this.inputLabel = inputLabel;
            this.inputHint = inputHint;
        }
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
}
