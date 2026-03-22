package org.fentanylsolutions.minemoticon.core;

import org.fentanylsolutions.fentlib.core.FentMixins;
import org.fentanylsolutions.fentlib.util.MixinUtil;

public class Mixins extends FentMixins {

    private static final Mixins INSTANCE = new Mixins();

    @Override
    protected void registerMixins(MixinUtil.Registry registry) {
        // Minecraft Accessors
        /*
         * registry.mixin("AccessorNetworkSystem")
         * .phase(MixinUtil.Phase.EARLY)
         * .side(MiscUtil.Side.BOTH)
         * .build();
         */

    }

    public static java.util.List<String> getEarlyMixinsForLoader() {
        return INSTANCE.getEarlyMixins();
    }

    public static java.util.List<String> getLateMixinsForLoader(java.util.Set<String> loadedCoreMods) {
        return INSTANCE.getLateMixins(loadedCoreMods);
    }
}
