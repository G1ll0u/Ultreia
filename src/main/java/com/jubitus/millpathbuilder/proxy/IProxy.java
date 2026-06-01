package com.jubitus.millpathbuilder.proxy;

public interface IProxy {
    void registerClientPackets();
    default void registerBlockModels() {}
}
