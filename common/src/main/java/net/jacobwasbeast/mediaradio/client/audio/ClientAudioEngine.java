package net.jacobwasbeast.mediaradio.client.audio;

import net.jacobwasbeast.mediaradio.block.entity.RadioBlockEntity;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.network.message.ServerboundHandheldStateMessage;
import net.jacobwasbeast.mediaradio.network.message.ServerboundRadioControlMessage;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientAudioEngine {

    private static final ClientAudioEngine INSTANCE = new ClientAudioEngine();

    private final Map<BlockPos, RadioAudioChannel> blockChannels = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> blockEndSuppressTicks = new ConcurrentHashMap<>();

    // Independent handheld playback sessions keyed by radio id.
    private final Map<String, HandheldSession> handheldSessions = new ConcurrentHashMap<>();
    private String activeHandheldRadioId = "";
    private InteractionHand activeHandheldHand = InteractionHand.MAIN_HAND;

    private int blockScanTicker;

    public static ClientAudioEngine getInstance() {
        return INSTANCE;
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            stopAll();
            return;
        }

        tickBlockEndSuppression();
        tickHandheld(minecraft);

        blockScanTicker++;
        if (blockScanTicker % 5 == 0) {
            scanBlockRadios(minecraft);
        }

        tickBlockChannels(minecraft);
    }

    public void playHandheld(String url, long positionMs, InteractionHand hand, String displayTitle, String artist, String thumbnail) {
        if (url == null || url.isBlank()) {
            return;
        }

        if (hand != null) {
            activeHandheldHand = hand;
        }
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }
        if (hand != null) {
            session.preferredHand = hand;
        }
        playSession(session, url, positionMs, displayTitle, artist, thumbnail);

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            persistRuntimeToSession(minecraft, session);
        }
    }

    public void togglePauseHandheld() {
        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }

        if (session.channel == null) {
            if (session.pausedState && !session.url.isBlank()) {
                session.intendedPlaying = true;
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft.player != null) {
                    resumeSessionIfHeld(minecraft, session);
                    persistRuntimeToSession(minecraft, session);
                }
            }
            return;
        }

        if (session.channel.isPlaying()) {
            session.pausedPositionMs = session.channel.getEstimatedPositionMs();
            session.pausedState = true;
            session.intendedPlaying = false;
            session.channel.pause();
        } else {
            session.pausedState = false;
            session.intendedPlaying = true;
            session.channel.resume();
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            persistRuntimeToSession(minecraft, session);
        }
    }

    public void stopHandheld() {
        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }
        stopSessionPlayback(session);
        session.intendedPlaying = false;
        session.pausedState = false;
        session.pausedPositionMs = 0L;
    }

    public void clearHandheldState() {
        HandheldSession session = activeSession();
        if (session == null) {
            activeHandheldRadioId = "";
            return;
        }
        stopSessionPlayback(session);
        session.title = "";
        session.artist = "";
        session.thumbnail = "";
        session.url = "";
        session.intendedPlaying = false;
        session.pausedState = false;
        session.pausedPositionMs = 0L;
        session.lastSyncedRuntimeKey = "";
    }

    public void setHandheldVolume(float volume) {
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }
        session.volume = Mth.clamp(volume, 0f, 2f);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            persistRuntimeToSession(minecraft, session);
        }
    }

    public float getHandheldVolume() {
        HandheldSession session = readSession();
        if (session == null) {
            return 1.0f;
        }
        return session.volume;
    }

    public String getHandheldNowPlaying() {
        HandheldSession session = readSession();
        if (session == null) {
            return "";
        }
        if (session.channel != null) {
            return session.channel.getDisplayTitle();
        }
        return session.title;
    }

    public boolean isHandheldPlaying() {
        HandheldSession session = readSession();
        return session != null && session.channel != null && session.channel.isPlaying();
    }

    public boolean isHandheldPaused() {
        HandheldSession session = readSession();
        if (session == null) {
            return false;
        }
        if (session.channel != null) {
            return session.channel.isPaused();
        }
        return session.pausedState && (!session.url.isBlank() || !session.title.isBlank());
    }

    public long getHandheldPlaybackPositionMs() {
        HandheldSession session = readSession();
        if (session == null) {
            return 0L;
        }
        return session.channel == null ? Math.max(0L, session.pausedPositionMs) : session.channel.getEstimatedPositionMs();
    }

    public long getHandheldTrackDurationMs() {
        HandheldSession session = readSession();
        if (session == null || session.channel == null) {
            return -1L;
        }
        return session.channel.getTrackDurationMs();
    }

    public void seekHandheld(long positionMs) {
        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }

        long clamped = Math.max(0L, positionMs);
        if (session.channel == null) {
            session.pausedPositionMs = clamped;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                persistRuntimeToSession(minecraft, session);
            }
            return;
        }

        session.channel.seekTo(clamped, session.channel.isPaused());
        session.pausedPositionMs = clamped;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            persistRuntimeToSession(minecraft, session);
        }
    }

    public String getHandheldArtist() {
        HandheldSession session = readSession();
        return session == null ? "" : session.artist;
    }

    public String getHandheldThumbnail() {
        HandheldSession session = readSession();
        return session == null ? "" : session.thumbnail;
    }

    public String getHandheldUrl() {
        HandheldSession session = readSession();
        return session == null ? "" : session.url;
    }

    public void setHandheldContext(String radioId, InteractionHand hand) {
        if (hand != null) {
            activeHandheldHand = hand;
        }

        String safeRadioId = safeRadioId(radioId);
        if (safeRadioId.isBlank()) {
            return;
        }

        if (!safeRadioId.equals(activeHandheldRadioId)) {
            HandheldSession previous = activeSession();
            if (previous != null && previous.channel != null && previous.channel.isPlaying()) {
                previous.pausedPositionMs = previous.channel.getEstimatedPositionMs();
                previous.pausedState = true;
                // Keep intent so it resumes when switching back to that radio.
                previous.intendedPlaying = true;
                previous.channel.pause();
            }
        }

        activeHandheldRadioId = safeRadioId;
        // Keep repository queue context aligned with the handheld session context.
        ClientMediaRepository.getInstance().setActiveRadioId(safeRadioId);
        HandheldSession current = activeOrCreateSession();
        if (current == null) {
            return;
        }
        if (hand != null) {
            current.preferredHand = hand;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            resumeSessionIfHeld(minecraft, current);
        }
    }

    public void updateHandheldMetadata(String title, String artist, String thumbnail) {
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }
        session.title = title == null ? "" : title;
        session.artist = artist == null ? "" : artist;
        session.thumbnail = thumbnail == null ? "" : thumbnail;
        if (session.channel != null && !session.title.isBlank()) {
            session.channel.setDisplayTitle(session.title);
        }
    }

    public void primeHandheldState(String url, String title, String artist, String thumbnail, long positionMs, float volume) {
        primeHandheldState(url, title, artist, thumbnail, positionMs, volume, false);
    }

    public void primeHandheldState(String url, String title, String artist, String thumbnail, long positionMs, float volume, boolean playing) {
        HandheldSession session = activeOrCreateSession();
        if (session == null) {
            return;
        }

        session.url = url == null ? "" : url;
        session.title = title == null ? "" : title;
        session.artist = artist == null ? "" : artist;
        session.thumbnail = thumbnail == null ? "" : thumbnail;
        session.volume = Mth.clamp(volume, 0f, 2f);
        session.pausedPositionMs = Math.max(0L, positionMs);
        session.pausedState = !playing && (!session.url.isBlank() || !session.title.isBlank());
        session.intendedPlaying = playing;

        if (session.channel != null) {
            if (!session.title.isBlank()) {
                session.channel.setDisplayTitle(session.title);
            }

            if (!session.url.isBlank()) {
                if (!session.url.equals(session.channel.getCurrentUrl())) {
                    session.channel.play(session.url, session.pausedPositionMs);
                }
                if (playing) {
                    session.channel.resume();
                    session.pausedState = false;
                } else {
                    session.channel.pause();
                    session.pausedState = true;
                }
            }
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (playing && minecraft.player != null) {
            resumeSessionIfHeld(minecraft, session);
        }
    }

    public void stopAll() {
        for (HandheldSession session : handheldSessions.values()) {
            stopSessionPlayback(session);
        }
        handheldSessions.clear();
        activeHandheldRadioId = "";
        activeHandheldHand = InteractionHand.MAIN_HAND;

        blockChannels.values().forEach(RadioAudioChannel::stop);
        blockChannels.clear();
        blockEndSuppressTicks.clear();
    }

    public long getBlockPlaybackPositionMs(BlockPos blockPos) {
        RadioAudioChannel channel = blockChannels.get(blockPos);
        return channel == null ? -1L : channel.getEstimatedPositionMs();
    }

    public long getBlockTrackDurationMs(BlockPos blockPos) {
        RadioAudioChannel channel = blockChannels.get(blockPos);
        return channel == null ? -1L : channel.getTrackDurationMs();
    }

    private void tickHandheld(Minecraft minecraft) {
        syncActiveContextFromHeldHands(minecraft);

        HandheldSession session = activeSession();
        if (session == null) {
            return;
        }

        if (!isRadioHeldInHands(minecraft, session.radioId)) {
            if (session.channel != null && session.channel.isPlaying()) {
                session.pausedPositionMs = session.channel.getEstimatedPositionMs();
                session.pausedState = true;
                // Keep intent so switching back resumes.
                session.intendedPlaying = true;
                session.channel.pause();
            }
            persistRuntimeToSession(minecraft, session);
            return;
        }

        if (session.channel == null) {
            if (session.intendedPlaying && !session.url.isBlank()) {
                resumeSessionIfHeld(minecraft, session);
            }
            persistRuntimeToSession(minecraft, session);
            return;
        }

        if (session.channel.isPaused() && session.intendedPlaying) {
            session.channel.resume();
            session.pausedState = false;
        }

        session.channel.tick();
        persistRuntimeToSession(minecraft, session);

        boolean ended = session.channel.consumeNaturalEnd();
        if (!ended && hasExceededTrackDuration(session.channel)) {
            session.channel.stop();
            ended = true;
        }
        if (!ended && !(session.channel.getCurrentUrl().isBlank() && !session.title.isBlank())) {
            return;
        }

        SharedMediaSnapshot.MediaEntry next = ClientMediaRepository.getInstance().nextQueueEntry();
        if (next != null && next.url != null && !next.url.isBlank()) {
            playSession(session, next.url, 0L, next.title, next.artist, next.thumbnail);
        } else {
            persistRuntimeToSession(minecraft, session);
            stopSessionPlayback(session);
            session.url = "";
            session.title = "";
            session.artist = "";
            session.thumbnail = "";
            session.pausedPositionMs = 0L;
            session.pausedState = false;
            session.intendedPlaying = false;
        }
    }

    private void playSession(HandheldSession session, String url, long positionMs, String displayTitle, String artist, String thumbnail) {
        if (session.channel == null) {
            session.channel = new RadioAudioChannel(false, () -> Vec3.ZERO, () -> session.volume, 1f);
        }
        session.channel.setDisplayTitle(displayTitle);
        session.channel.play(url, positionMs);
        session.url = url == null ? "" : url;
        session.title = displayTitle == null ? "" : displayTitle;
        session.artist = artist == null ? "" : artist;
        session.thumbnail = thumbnail == null ? "" : thumbnail;
        session.pausedPositionMs = Math.max(0L, positionMs);
        session.pausedState = false;
        session.intendedPlaying = true;
    }

    private void stopSessionPlayback(HandheldSession session) {
        if (session.channel != null) {
            session.channel.stop();
            session.channel = null;
        }
    }

    private void resumeSessionIfHeld(Minecraft minecraft, HandheldSession session) {
        if (session == null || !session.intendedPlaying || session.url.isBlank()) {
            return;
        }
        if (!isRadioHeldInHands(minecraft, session.radioId)) {
            return;
        }

        if (session.channel == null) {
            session.channel = new RadioAudioChannel(false, () -> Vec3.ZERO, () -> session.volume, 1f);
            session.channel.setDisplayTitle(session.title);
            session.channel.play(session.url, Math.max(0L, session.pausedPositionMs));
            session.pausedState = false;
            return;
        }

        if (!session.url.equals(session.channel.getCurrentUrl())) {
            session.channel.setDisplayTitle(session.title);
            session.channel.play(session.url, Math.max(0L, session.pausedPositionMs));
            session.pausedState = false;
            return;
        }

        if (session.channel.isPaused()) {
            session.channel.resume();
            session.pausedState = false;
        }
    }

    private void syncActiveContextFromHeldHands(Minecraft minecraft) {
        String mainRadioId = radioIdFromStack(minecraft.player.getMainHandItem());
        String offRadioId = radioIdFromStack(minecraft.player.getOffhandItem());

        if (activeHandheldRadioId.isBlank()) {
            if (!mainRadioId.isBlank()) {
                setHandheldContext(mainRadioId, InteractionHand.MAIN_HAND);
            } else if (!offRadioId.isBlank()) {
                setHandheldContext(offRadioId, InteractionHand.OFF_HAND);
            }
            return;
        }

        if (isRadioHeldInHands(minecraft, activeHandheldRadioId)) {
            return;
        }

        if (!mainRadioId.isBlank()) {
            setHandheldContext(mainRadioId, InteractionHand.MAIN_HAND);
            return;
        }

        if (!offRadioId.isBlank()) {
            setHandheldContext(offRadioId, InteractionHand.OFF_HAND);
        }
    }

    private void persistRuntimeToSession(Minecraft minecraft, HandheldSession session) {
        if (session == null || minecraft.player == null) {
            return;
        }
        if (session.radioId.isBlank()) {
            return;
        }

        ItemStack stack = findRadioStackById(minecraft, session.radioId);
        if (stack.isEmpty() || !stack.is(ModItems.RADIO_ITEM)) {
            return;
        }

        String queueStateJson = ClientMediaRepository.getInstance().exportActiveQueueStateJson();
        long position = session.channel == null ? Math.max(0L, session.pausedPositionMs) : session.channel.getEstimatedPositionMs();
        SharedMediaSnapshot.MediaEntry current = ClientMediaRepository.getInstance().getCurrentQueueEntry();
        String resolvedUrl = current != null && current.url != null && !current.url.isBlank() ? current.url : session.url;
        syncRuntimeStateToServer(session, stack, resolvedUrl, session.title, session.artist, session.thumbnail, queueStateJson, position, session.volume);
    }

    private void syncRuntimeStateToServer(
            HandheldSession session,
            ItemStack stack,
            String url,
            String title,
            String artist,
            String thumbnail,
            String queueStateJson,
            long position,
            float volume
    ) {
        String radioId = RadioItem.getOrCreateRadioId(stack);
        long positionBucket = Math.max(0L, position) / 500L;
        float clampedVolume = Mth.clamp(volume, 0f, 2f);
        String stateKey = radioId + "|" + safe(url) + "|" + safe(title) + "|" + safe(artist) + "|" + safe(thumbnail) + "|"
                + queueStateJson.hashCode() + "|" + positionBucket + "|" + clampedVolume;
        if (stateKey.equals(session.lastSyncedRuntimeKey)) {
            return;
        }

        ModNetworking.sendHandheldState(new ServerboundHandheldStateMessage(
                radioId,
                safe(url),
                safe(title),
                safe(artist),
                safe(thumbnail),
                queueStateJson == null ? "" : queueStateJson,
                clampedVolume,
                Math.max(0L, position),
                session.channel != null && session.channel.isPlaying()
        ));
        session.lastSyncedRuntimeKey = stateKey;
    }

    private ItemStack findRadioStackById(Minecraft minecraft, String targetRadioId) {
        if (targetRadioId == null || targetRadioId.isBlank()) {
            return ItemStack.EMPTY;
        }

        ItemStack main = minecraft.player.getMainHandItem();
        if (main.is(ModItems.RADIO_ITEM) && targetRadioId.equals(RadioItem.getRadioId(main))) {
            return main;
        }

        ItemStack off = minecraft.player.getOffhandItem();
        if (off.is(ModItems.RADIO_ITEM) && targetRadioId.equals(RadioItem.getRadioId(off))) {
            return off;
        }

        for (ItemStack stack : minecraft.player.getInventory().items) {
            if (!stack.is(ModItems.RADIO_ITEM)) {
                continue;
            }
            if (targetRadioId.equals(RadioItem.getRadioId(stack))) {
                return stack;
            }
        }
        for (ItemStack stack : minecraft.player.getInventory().offhand) {
            if (stack.is(ModItems.RADIO_ITEM) && targetRadioId.equals(RadioItem.getRadioId(stack))) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean isRadioHeldInHands(Minecraft minecraft, String radioId) {
        if (minecraft.player == null || radioId == null || radioId.isBlank()) {
            return false;
        }
        ItemStack main = minecraft.player.getMainHandItem();
        if (main.is(ModItems.RADIO_ITEM) && radioId.equals(RadioItem.getRadioId(main))) {
            return true;
        }
        ItemStack off = minecraft.player.getOffhandItem();
        return off.is(ModItems.RADIO_ITEM) && radioId.equals(RadioItem.getRadioId(off));
    }

    private String radioIdFromStack(ItemStack stack) {
        if (stack == null || !stack.is(ModItems.RADIO_ITEM)) {
            return "";
        }
        return safe(RadioItem.getRadioId(stack));
    }

    private HandheldSession activeSession() {
        if (activeHandheldRadioId == null || activeHandheldRadioId.isBlank()) {
            return null;
        }
        return handheldSessions.get(activeHandheldRadioId);
    }

    private HandheldSession activeOrCreateSession() {
        String radioId = activeHandheldRadioId;
        if (radioId == null || radioId.isBlank()) {
            radioId = safeRadioId(ClientMediaRepository.getInstance().getActiveRadioId());
            if (radioId.isBlank()) {
                return null;
            }
            activeHandheldRadioId = radioId;
        }
        return handheldSessions.computeIfAbsent(radioId, HandheldSession::new);
    }

    private HandheldSession readSession() {
        HandheldSession active = activeSession();
        if (active != null) {
            return active;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            String mainId = radioIdFromStack(minecraft.player.getMainHandItem());
            if (!mainId.isBlank()) {
                return handheldSessions.get(mainId);
            }
            String offId = radioIdFromStack(minecraft.player.getOffhandItem());
            if (!offId.isBlank()) {
                return handheldSessions.get(offId);
            }
        }

        return null;
    }

    private String safeRadioId(String value) {
        return value == null ? "" : value;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void scanBlockRadios(Minecraft minecraft) {
        Set<BlockPos> activePositions = new HashSet<>();
        BlockPos playerPos = minecraft.player.blockPosition();
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();
        int horizontalRange = 30;
        int verticalRange = 16;
        int minY = Math.max(minecraft.level.getMinBuildHeight(), playerPos.getY() - verticalRange);
        int maxY = Math.min(minecraft.level.getMaxBuildHeight() - 1, playerPos.getY() + verticalRange);

        for (int x = playerPos.getX() - horizontalRange; x <= playerPos.getX() + horizontalRange; x++) {
            for (int z = playerPos.getZ() - horizontalRange; z <= playerPos.getZ() + horizontalRange; z++) {
                for (int y = minY; y <= maxY; y++) {
                    mutableBlockPos.set(x, y, z);
                    if (!(minecraft.level.getBlockEntity(mutableBlockPos) instanceof RadioBlockEntity radioBlockEntity)) {
                        continue;
                    }

                    BlockPos blockPos = radioBlockEntity.getBlockPos();
                    if (!radioBlockEntity.isPlaying() || radioBlockEntity.getMediaUrl().isBlank()) {
                        RadioAudioChannel existing = blockChannels.remove(blockPos);
                        if (existing != null) {
                            existing.stop();
                        }
                        continue;
                    }

                    Integer suppressTicks = blockEndSuppressTicks.get(blockPos);
                    if (suppressTicks != null && suppressTicks > 0) {
                        continue;
                    }

                    activePositions.add(blockPos);

                    RadioAudioChannel channel = blockChannels.computeIfAbsent(blockPos,
                            ignored -> new RadioAudioChannel(
                                    true,
                                    () -> Vec3.atCenterOf(blockPos),
                                    radioBlockEntity::getVolume,
                                    30f));

                    long targetPosition = radioBlockEntity.getPlaybackPositionMs();
                    long delta = Math.abs(channel.getEstimatedPositionMs() - targetPosition);
                    if (!radioBlockEntity.getMediaUrl().equals(channel.getCurrentUrl()) || delta > 2500L) {
                        channel.setDisplayTitle(radioBlockEntity.getMediaTitle());
                        channel.play(radioBlockEntity.getMediaUrl(), targetPosition);
                    }
                }
            }
        }

        Set<BlockPos> stale = new HashSet<>(blockChannels.keySet());
        stale.removeAll(activePositions);
        for (BlockPos stalePos : stale) {
            RadioAudioChannel channel = blockChannels.remove(stalePos);
            if (channel != null) {
                channel.stop();
            }
        }
    }

    private void tickBlockChannels(Minecraft minecraft) {
        Set<BlockPos> endedNaturally = new HashSet<>();
        for (Map.Entry<BlockPos, RadioAudioChannel> entry : blockChannels.entrySet()) {
            RadioAudioChannel channel = entry.getValue();
            channel.tick();
            boolean ended = channel.consumeNaturalEnd();
            if (!ended && hasExceededTrackDuration(channel)) {
                channel.stop();
                ended = true;
            }
            if (ended) {
                endedNaturally.add(entry.getKey());
            }
        }

        for (BlockPos blockPos : endedNaturally) {
            blockEndSuppressTicks.put(blockPos, 40);
            RadioAudioChannel channel = blockChannels.remove(blockPos);
            if (channel != null) {
                channel.stop();
            }
            sendBlockStopIfPlaying(minecraft, blockPos);
        }
    }

    private void sendBlockStopIfPlaying(Minecraft minecraft, BlockPos blockPos) {
        if (minecraft.level == null) {
            return;
        }
        if (!(minecraft.level.getBlockEntity(blockPos) instanceof RadioBlockEntity radioBlockEntity)) {
            return;
        }
        if (!radioBlockEntity.isPlaying()) {
            return;
        }
        ModNetworking.sendBlockRadioControl(new ServerboundRadioControlMessage(
                blockPos,
                ServerboundRadioControlMessage.Action.STOP,
                "",
                "",
                "",
                "",
                radioBlockEntity.getVolume(),
                radioBlockEntity.getPlaybackPositionMs()
        ));
    }

    private void tickBlockEndSuppression() {
        blockEndSuppressTicks.forEach((blockPos, ticks) -> {
            if (ticks == null || ticks <= 1) {
                blockEndSuppressTicks.remove(blockPos);
            } else {
                blockEndSuppressTicks.put(blockPos, ticks - 1);
            }
        });
    }

    private boolean hasExceededTrackDuration(RadioAudioChannel channel) {
        long duration = channel.getTrackDurationMs();
        if (duration <= 0L) {
            return false;
        }
        long position = channel.getEstimatedPositionMs();
        return position > duration + 1200L && !channel.isPlaying();
    }

    private static class HandheldSession {
        private final String radioId;
        private RadioAudioChannel channel;
        private float volume = 1.0f;
        private InteractionHand preferredHand = InteractionHand.MAIN_HAND;
        private String title = "";
        private String artist = "";
        private String thumbnail = "";
        private String url = "";
        private boolean pausedState;
        private long pausedPositionMs;
        private boolean intendedPlaying;
        private String lastSyncedRuntimeKey = "";

        private HandheldSession(String radioId) {
            this.radioId = radioId;
        }
    }
}
