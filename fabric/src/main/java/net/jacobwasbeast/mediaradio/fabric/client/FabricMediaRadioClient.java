package net.jacobwasbeast.mediaradio.fabric.client;

import net.blay09.mods.balm.api.client.BalmClient;
import net.fabricmc.api.ClientModInitializer;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.MediaRadioClient;

public class FabricMediaRadioClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BalmClient.initialize(MediaRadio.MOD_ID, MediaRadioClient::initialize);
    }
}
