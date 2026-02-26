package net.jacobwasbeast.mediaradio.client;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.client.BalmClient;
import net.blay09.mods.balm.api.event.TickPhase;
import net.blay09.mods.balm.api.event.TickType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.audio.ClientAudioEngine;
import net.jacobwasbeast.mediaradio.client.audio.LavaPlayerNativeLoader;
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.media.ThumbnailTextureManager;
import net.jacobwasbeast.mediaradio.client.render.RadioBlockEntityRenderer;
import net.jacobwasbeast.mediaradio.client.screen.RadioScreen;
import net.jacobwasbeast.mediaradio.client.media.MediaMetadataResolver;
import net.jacobwasbeast.mediaradio.client.settings.ClientAudioSettings;
import net.jacobwasbeast.mediaradio.item.RadioItem;
import net.jacobwasbeast.mediaradio.mixin.ItemPropertiesAccessor;
import net.jacobwasbeast.mediaradio.registry.ModBlockEntities;
import net.jacobwasbeast.mediaradio.registry.ModItems;
import net.jacobwasbeast.mediaradio.network.ModNetworking;
import net.jacobwasbeast.mediaradio.server.SharedMediaSnapshot;

import java.util.List;

public class MediaRadioClient {
    public static void initialize() {
        ModKeyMappings.initialize();
        ClientMediaRepository.initialize();
        LavaPlayerNativeLoader.initialize();
        ClientAudioSettings.get().load();

        BalmClient.getRenderers().registerBlockEntityRenderer(
                () -> ModBlockEntities.RADIO_BLOCK_ENTITY.get(),
                RadioBlockEntityRenderer::new
        );

        registerItemPredicates();

        Balm.getEvents().onTickEvent(TickType.Client, TickPhase.End,
                (net.blay09.mods.balm.api.event.client.ClientTickHandler) minecraft -> ClientAudioEngine.getInstance().tick(minecraft));

        Balm.getEvents().onEvent(net.blay09.mods.balm.api.event.client.DisconnectedFromServerEvent.class,
                event -> {
                    ClientAudioEngine.getInstance().stopAll();
                    ThumbnailTextureManager.getInstance().clearSessionCache();
                    ClientMediaRepository.getInstance().resetAndReloadCache();
                });
    }

    public static void openHandRadioScreen(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            ItemStack stack = minecraft.player.getItemInHand(hand);
            if (stack.is(ModItems.RADIO_ITEM)) {
                String radioId = RadioItem.getOrCreateRadioId(stack);
                ClientMediaRepository repository = ClientMediaRepository.getInstance();
                repository.setActiveRadioId(radioId);
                ClientAudioEngine.getInstance().setHandheldContext(radioId, hand);
                ModNetworking.requestRadioState(radioId, net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.HANDHELD);
            }
        }
        minecraft.setScreen(RadioScreen.forHand(hand));
    }

    public static void openBlockRadioScreen(BlockPos blockPos) {
        Minecraft.getInstance().setScreen(RadioScreen.forBlock(blockPos));
    }

    public static void openContraptionRadioScreen(String radioId, int contraptionEntityId, BlockPos localPos) {
        if (radioId == null || radioId.isBlank()) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        repository.setActiveRadioId(radioId);
        ClientAudioEngine.getInstance().setExternalContext(radioId, contraptionEntityId, localPos);
        ModNetworking.requestRadioState(radioId, net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.BLOCK);
        Minecraft.getInstance().setScreen(RadioScreen.forContraptionRadio(radioId, contraptionEntityId, localPos));
    }

    public static void applyServerRadioRuntimeState(
            String radioId,
            String sessionId,
            long revision,
            String url,
            String title,
            String artist,
            String thumbnail,
            String queueStateJson,
            float volume,
            long positionMs,
            long sentAtMs,
            boolean forcePositionSync,
            boolean seekEvent,
            boolean playing
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        if (radioId == null || radioId.isBlank()) {
            return;
        }
        boolean isActiveRadio = radioId.equals(repository.getActiveRadioId());
        ClientAudioEngine audioEngine = ClientAudioEngine.getInstance();
        boolean isExternalRadio = audioEngine.hasExternalContext(radioId);

        boolean hasAuthoritativeQueueState = queueStateJson != null && !queueStateJson.isBlank();
        if (hasAuthoritativeQueueState && (isActiveRadio || isExternalRadio)) {
            String previousActive = repository.getActiveRadioId();
            if (!radioId.equals(previousActive)) {
                repository.setActiveRadioId(radioId);
            }
            repository.importActiveQueueStateJson(queueStateJson);
            if (!radioId.equals(previousActive)) {
                repository.setActiveRadioId(previousActive);
            }
        }

        String resolvedUrl = url == null ? "" : url;
        String resolvedTitle = (title == null || title.isBlank()) ? resolvedUrl : title;
        String resolvedArtist = artist == null ? "" : artist;
        String resolvedThumbnail = thumbnail == null ? "" : thumbnail;
        if (!resolvedUrl.isBlank()) {
            SharedMediaSnapshot.MediaEntry existing = repository.findByUrl(resolvedUrl);
            String mediaId;
            if (existing == null) {
                mediaId = repository.upsertPlaylistOnlyMedia(resolvedUrl, resolvedTitle, resolvedArtist, resolvedThumbnail, List.of()).id;
            } else {
                mediaId = existing.id;
            }

            if (isActiveRadio) {
                int reconciledIndex = repository.reconcileCurrentQueueEntryForRadioId(radioId, resolvedUrl);
                if (reconciledIndex < 0 && !hasAuthoritativeQueueState) {
                    repository.enqueue(mediaId);
                    repository.setQueueIndex(Math.max(0, repository.getQueueEntries().size() - 1));
                }
            }
        }

        long latencyAdjustedPositionMs = Math.max(0L, positionMs);
        if (playing) {
            long ageMs = Math.max(0L, System.currentTimeMillis() - Math.max(0L, sentAtMs));
            // Guard against clock skew and stale packets.
            ageMs = Math.min(ageMs, 10_000L);
            latencyAdjustedPositionMs += ageMs;
        }

        audioEngine.primeRuntimeStateForRadio(
                radioId,
                sessionId,
                revision,
                resolvedUrl,
                resolvedTitle,
                resolvedArtist,
                MediaMetadataResolver.bestThumbnail(resolvedThumbnail, resolvedUrl),
                latencyAdjustedPositionMs,
                volume,
                playing,
                sentAtMs,
                forcePositionSync,
                seekEvent
        );
    }

    public static void applyServerPlayerRadioContext(String radioId, int entityId, boolean active, boolean inventoryPlayback) {
        if (radioId == null || radioId.isBlank()) {
            return;
        }
        ClientAudioEngine audioEngine = ClientAudioEngine.getInstance();
        if (!active) {
            audioEngine.releaseExternalContext(radioId);
            return;
        }
        if (entityId <= 0) {
            return;
        }
        boolean wasExternal = audioEngine.hasExternalContext(radioId);
        audioEngine.setExternalContext(radioId, entityId, null, inventoryPlayback);
        if (!wasExternal) {
            ModNetworking.requestRadioState(radioId, net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.HANDHELD);
        }
    }

    public static void handleServerSessionCommandResult(
            String radioId,
            String sessionId,
            long serverRevision,
            boolean accepted,
            net.jacobwasbeast.mediaradio.network.message.ClientboundSessionCommandResultMessage.Reason reason,
            net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context context
    ) {
        if (accepted) {
            return;
        }
        if (reason != net.jacobwasbeast.mediaradio.network.message.ClientboundSessionCommandResultMessage.Reason.STALE_REVISION
                && reason != net.jacobwasbeast.mediaradio.network.message.ClientboundSessionCommandResultMessage.Reason.UNAUTHORIZED) {
            return;
        }
        if (radioId == null || radioId.isBlank()) {
            return;
        }
        ModNetworking.requestRadioState(
                radioId,
                context == null ? net.jacobwasbeast.mediaradio.network.message.ServerboundRequestRadioStateMessage.Context.HANDHELD : context
        );
    }

    private static void registerItemPredicates() {
        ItemPropertiesAccessor.mediaradio$register(ModItems.RADIO_ITEM, MediaRadio.id("place_mode"),
                (stack, level, entity, seed) -> RadioItem.isPlaceMode(stack) ? 1f : 0f);

        ItemPropertiesAccessor.mediaradio$register(ModItems.RADIO_ITEM, MediaRadio.id("offhand_occupied"),
                MediaRadioClient::offhandOccupiedPredicate);
    }

    private static float offhandOccupiedPredicate(ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level, LivingEntity entity, int seed) {
        if (entity == null || !stack.is(ModItems.RADIO_ITEM)) {
            return 0f;
        }

        ItemStack main = entity.getMainHandItem();
        if (!main.is(ModItems.RADIO_ITEM)) {
            return 0f;
        }

        boolean offHandOccupied = !entity.getOffhandItem().isEmpty();
        if (!offHandOccupied) {
            return 0f;
        }

        // In larger modpacks, some render hooks pass copied ItemStacks.
        // Match by radio id first, then by item+tag as fallback.
        String stackRadioId = RadioItem.getRadioId(stack);
        String mainRadioId = RadioItem.getRadioId(main);
        if (!stackRadioId.isBlank() && stackRadioId.equals(mainRadioId)) {
            return 1f;
        }
        if (main == stack) {
            return 1f;
        }
        return ItemStack.isSameItemSameTags(main, stack) ? 1f : 0f;
    }
}
