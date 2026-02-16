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
import net.jacobwasbeast.mediaradio.client.data.ClientMediaRepository;
import net.jacobwasbeast.mediaradio.client.render.RadioBlockEntityRenderer;
import net.jacobwasbeast.mediaradio.client.screen.RadioScreen;
import net.jacobwasbeast.mediaradio.client.media.MediaMetadataResolver;
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

        BalmClient.getRenderers().registerBlockEntityRenderer(
                MediaRadio.id("radio_block_entity"),
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

    public static void applyServerRadioRuntimeState(
            String radioId,
            String url,
            String title,
            String artist,
            String thumbnail,
            String queueStateJson,
            float volume,
            long positionMs,
            boolean playing
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ClientMediaRepository repository = ClientMediaRepository.getInstance();
        if (radioId == null || radioId.isBlank() || !radioId.equals(repository.getActiveRadioId())) {
            return;
        }

        boolean hasAuthoritativeQueueState = queueStateJson != null && !queueStateJson.isBlank();
        if (queueStateJson != null && !queueStateJson.isBlank()) {
            repository.importActiveQueueStateJson(queueStateJson);
        }

        String resolvedUrl = url == null ? "" : url;
        String resolvedTitle = (title == null || title.isBlank()) ? resolvedUrl : title;
        String resolvedArtist = artist == null ? "" : artist;
        String resolvedThumbnail = thumbnail == null ? "" : thumbnail;
        if (!resolvedUrl.isBlank()) {
            SharedMediaSnapshot.MediaEntry existing = repository.findByUrl(resolvedUrl);
            String mediaId;
            if (existing == null) {
                mediaId = repository.upsertMedia(resolvedUrl, resolvedTitle, resolvedArtist, resolvedThumbnail, List.of()).id;
            } else {
                mediaId = existing.id;
            }

            List<SharedMediaSnapshot.MediaEntry> queue = repository.getQueueEntries();
            if (queue.isEmpty()) {
                repository.enqueue(mediaId);
                repository.setQueueIndex(0);
            } else if (!hasAuthoritativeQueueState) {
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
            } else if (repository.getCurrentQueueEntry() == null) {
                repository.setQueueIndex(0);
            }
        }

        ClientAudioEngine.getInstance().primeHandheldState(
                resolvedUrl,
                resolvedTitle,
                resolvedArtist,
                MediaMetadataResolver.bestThumbnail(resolvedThumbnail, resolvedUrl),
                positionMs,
                volume,
                playing
        );
    }

    private static void registerItemPredicates() {
        ItemPropertiesAccessor.mediaradio$register(ModItems.RADIO_ITEM, MediaRadio.id("place_mode"),
                (stack, level, entity, seed) -> RadioItem.isPlaceMode(stack) ? 1f : 0f);

        ItemPropertiesAccessor.mediaradio$register(ModItems.RADIO_ITEM, MediaRadio.id("offhand_occupied"),
                MediaRadioClient::offhandOccupiedPredicate);
    }

    private static float offhandOccupiedPredicate(ItemStack stack, net.minecraft.client.multiplayer.ClientLevel level, LivingEntity entity, int seed) {
        if (entity == null) {
            return 0f;
        }

        boolean mainHandRadio = entity.getMainHandItem().is(ModItems.RADIO_ITEM);
        boolean offHandOccupied = !entity.getOffhandItem().isEmpty();

        return mainHandRadio && offHandOccupied ? 1f : 0f;
    }
}
