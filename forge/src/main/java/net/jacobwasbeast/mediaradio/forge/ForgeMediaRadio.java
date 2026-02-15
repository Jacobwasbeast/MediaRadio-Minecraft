package net.jacobwasbeast.mediaradio.forge;

import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.api.EmptyLoadContext;
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
        Balm.initializeMod(MediaRadio.MOD_ID, EmptyLoadContext.INSTANCE, MediaRadio::initialize);
        DistExecutor.runWhenOn(Dist.CLIENT,
                () -> () -> BalmClient.initializeMod(MediaRadio.MOD_ID, EmptyLoadContext.INSTANCE, MediaRadioClient::initialize));
    }
}
