package com.jubitus.ultreia.gui;

import com.jubitus.jubiconfig.factory.AbstractModGuiFactory;
import com.jubitus.ultreia.Tags;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MPBGuiFactory extends AbstractModGuiFactory {

    @Override
    protected String getModId() {
        return Tags.MOD_ID;
    }
}
