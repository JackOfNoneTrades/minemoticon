package org.fentanylsolutions.minemoticon.core;

import org.fentanylsolutions.fentlib.core.FentMixins;
import org.fentanylsolutions.fentlib.util.MiscUtil;
import org.fentanylsolutions.fentlib.util.MixinUtil;

public class Mixins extends FentMixins {

    private static final Mixins INSTANCE = new Mixins();

    @Override
    protected void registerMixins(MixinUtil.Registry registry) {
        registry.mixin("MixinFontRenderer")
            .phase(MixinUtil.Phase.EARLY)
            .side(MiscUtil.Side.CLIENT)
            .build();

        registry.mixin("AccessorGuiTextField")
            .phase(MixinUtil.Phase.EARLY)
            .side(MiscUtil.Side.CLIENT)
            .build();

        registry.mixin("MixinGuiChat")
            .phase(MixinUtil.Phase.EARLY)
            .side(MiscUtil.Side.CLIENT)
            .build();
    }

    public static java.util.List<String> getEarlyMixinsForLoader() {
        return INSTANCE.getEarlyMixins();
    }

    public static java.util.List<String> getLateMixinsForLoader(java.util.Set<String> loadedCoreMods) {
        return INSTANCE.getLateMixins(loadedCoreMods);
    }
}
