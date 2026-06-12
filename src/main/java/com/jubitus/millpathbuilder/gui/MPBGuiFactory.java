package com.jubitus.millpathbuilder.gui;

import com.jubitus.jubiconfig.factory.AbstractModGuiFactory;
import com.jubitus.millpathbuilder.Tags;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class MPBGuiFactory extends AbstractModGuiFactory {

    @Override
    protected String getModId() {
        return Tags.MOD_ID;
    }
}
