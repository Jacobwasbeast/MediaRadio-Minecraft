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
                    ClientMediaRepository.getInstance().reset();
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
                ModNetworking.requestRadioState(radioId);
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
        ModNetworking.requestRadioState(radioId);
        Minecraft.getInstance().setScreen(RadioScreen.forContraptionRadio(radioId, contraptionEntityId, localPos));
    }

    public static void applyServerRadioRuntimeState(
            String radioId,
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

            List<SharedMediaSnapshot.MediaEntry> queue = isActiveRadio ? repository.getQueueEntries() : List.of();
            if (queue.isEmpty()) {
                if (isActiveRadio) {
                    repository.enqueue(mediaId);
                    repository.setQueueIndex(0);
                }
            } else if (!hasAuthoritativeQueueState && isActiveRadio) {
                int matchingIndex = -1;
                for (int i = 0; i < queue.size(); i++) {
                    SharedMediaSnapshot.MediaEntry queued = queue.get(i);
                    if (queued != null && resolvedUrl.equals(queued.url)) {
                        matchingIndex = i;
                        break;
                    }
                }
                if (matchingIndex >= 0) {
                    repository.setQueueIndex(matchingIndex);
                } else {
                    repository.enqueue(mediaId);
                    repository.setQueueIndex(Math.max(0, repository.getQueueEntries().size() - 1));
                }
            } else if (isActiveRadio && repository.getCurrentQueueEntry() == null) {
                repository.setQueueIndex(0);
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
            ModNetworking.requestRadioState(radioId);
        }
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
