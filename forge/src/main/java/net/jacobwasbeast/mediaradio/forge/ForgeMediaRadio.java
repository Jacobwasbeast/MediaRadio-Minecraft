package net.jacobwasbeast.mediaradio.forge;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.client.BalmClient;
import net.jacobwasbeast.mediaradio.MediaRadio;
import net.jacobwasbeast.mediaradio.client.MediaRadioClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(MediaRadio.MOD_ID)
public class ForgeMediaRadio {

    public ForgeMediaRadio(FMLJavaModLoadingContext context) {
        Balm.initialize(MediaRadio.MOD_ID, MediaRadio::initialize);
        DistExecutor.runWhenOn(Dist.CLIENT,
                () -> () -> BalmClient.initialize(MediaRadio.MOD_ID, MediaRadioClient::initialize));
    }
}
